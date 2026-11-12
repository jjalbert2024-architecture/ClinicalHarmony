package com.clinicalharmony.patientindex.service;

import com.clinicalharmony.patientindex.client.GoldWriterClient;
import com.clinicalharmony.patientindex.client.GoldWriterRejectedException;
import com.clinicalharmony.patientindex.matching.BlockingIndex;
import com.clinicalharmony.patientindex.matching.MatchScore;
import com.clinicalharmony.patientindex.matching.MatchSettings;
import com.clinicalharmony.patientindex.matching.PatientMatcher;
import com.clinicalharmony.patientindex.matching.PatientRecord;
import com.clinicalharmony.patientindex.repository.MatchCandidate;
import com.clinicalharmony.patientindex.repository.MatchCandidateRepository;
import com.clinicalharmony.patientindex.repository.MatchExclusionRepository;
import com.clinicalharmony.patientindex.repository.MatchRun;
import com.clinicalharmony.patientindex.repository.MatchRunRepository;
import com.clinicalharmony.patientindex.repository.PmiReadRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One matching run over every PROVISIONAL master:
 * <ol>
 *   <li>Load the Silver patients under active masters (one consistent snapshot).</li>
 *   <li>Block, then score each pair that crosses two masters with at least one of them
 *       PROVISIONAL (pairs of already-decided masters aren't re-litigated) and that no human has
 *       excluded.</li>
 *   <li><b>AUTO_MERGE</b> pairs → connected clusters of masters; each cluster collapses into its
 *       oldest master (lowest id) through GoldWriter, one call per absorbed master, each carrying
 *       its best-scoring pair as evidence.</li>
 *   <li><b>REVIEW</b> pairs → one PENDING review item per pair of (post-merge) masters, the
 *       best-scoring Silver pair; existing review items whose records now share a master are
 *       SUPERSEDED.</li>
 *   <li>Every other PROVISIONAL master with no open review item is confirmed MATCHED (it's its
 *       own person). Masters waiting on review, or in a merge GoldWriter refused, stay PROVISIONAL.</li>
 * </ol>
 * GoldWriter applies each decision atomically; if it refuses one (state changed underneath the
 * run) that decision is skipped and reported, and the run carries on. If GoldWriter is unreachable
 * the run stops and is marked FAILED; what was applied stays applied and the next run resumes
 * from Gold's state.
 */
@Service
public class MatchingRunService {

    private static final Logger log = LoggerFactory.getLogger(MatchingRunService.class);

    private final PmiReadRepository reads;
    private final MatchRunRepository runs;
    private final MatchCandidateRepository candidates;
    private final MatchExclusionRepository exclusions;
    private final MatchSettingsLoader settingsLoader;
    private final PatientMatcher matcher;
    private final GoldWriterClient goldWriter;
    private final PmiLock lock;
    private final ObjectMapper objectMapper;

    public MatchingRunService(PmiReadRepository reads, MatchRunRepository runs, MatchCandidateRepository candidates,
                              MatchExclusionRepository exclusions, MatchSettingsLoader settingsLoader,
                              PatientMatcher matcher, GoldWriterClient goldWriter, PmiLock lock,
                              ObjectMapper objectMapper) {
        this.reads = reads;
        this.runs = runs;
        this.candidates = candidates;
        this.exclusions = exclusions;
        this.settingsLoader = settingsLoader;
        this.matcher = matcher;
        this.goldWriter = goldWriter;
        this.lock = lock;
        this.objectMapper = objectMapper;
    }

    public RunResult run(String triggeredBy) {
        return lock.runExclusively(() -> {
            MatchSettings settings = settingsLoader.loadActive();
            long runId = runs.start(triggeredBy, settingsLoader.toJson(settings));
            try {
                return execute(runId, settings);
            } catch (RuntimeException e) {
                runs.fail(runId, e.getMessage());
                log.warn("PMI run {} failed: {}", runId, e.getMessage());
                throw e;
            }
        });
    }

    private RunResult execute(long runId, MatchSettings settings) {
        String decidedBy = "pmi-run-" + runId;
        List<PatientRecord> records = reads.activeMasterSources();
        Map<Long, PatientRecord> bySilverId = new HashMap<>();
        Set<Long> provisionalMasters = new TreeSet<>();
        for (PatientRecord r : records) {
            bySilverId.put(r.silverPatientId(), r);
            if (r.provisional()) {
                provisionalMasters.add(r.masterPatientId());
            }
        }
        int withoutMaster = reads.countSilverPatientsWithoutMaster();

        // 1-2: block and score
        Set<String> excluded = exclusions.allPairKeys();
        List<MatchScore> autoPairs = new ArrayList<>();
        List<MatchScore> reviewPairs = new ArrayList<>();
        int compared = 0;
        for (BlockingIndex.Pair pair : BlockingIndex.candidatePairs(records)) {
            PatientRecord a = pair.a();
            PatientRecord b = pair.b();
            if (a.masterPatientId().equals(b.masterPatientId()) || !(a.provisional() || b.provisional())
                    || excluded.contains(MatchExclusionRepository.pairKey(a.silverPatientId(), b.silverPatientId()))) {
                continue;
            }
            compared++;
            MatchScore score = matcher.score(a, b, settings);
            if (MatchScore.AUTO_MERGE.equals(score.decision())) {
                autoPairs.add(score);
            } else if (MatchScore.REVIEW.equals(score.decision())) {
                reviewPairs.add(score);
            }
        }

        // 3: auto-merge clusters into their oldest master
        List<RunResult.AutoMerge> merges = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<Long> unresolved = new HashSet<>();
        Map<Long, Long> mergedInto = applyAutoMerges(runId, decidedBy, autoPairs, bySilverId, merges, skipped, unresolved);

        // 4a: existing open review items — supersede those whose records now share a master; the
        //     rest already cover their pair of masters.
        Set<String> masterPairsUnderReview = new HashSet<>();
        Set<Long> mastersAwaitingReview = new HashSet<>();
        for (MatchCandidate c : candidates.pending()) {
            Long ma = currentOf(c.silverPatientAId(), bySilverId, mergedInto);
            Long mb = currentOf(c.silverPatientBId(), bySilverId, mergedInto);
            if (ma != null && ma.equals(mb)) {
                candidates.decide(c.id(), MatchCandidate.SUPERSEDED, decidedBy,
                        "Both records are now under master " + ma, null);
                continue;
            }
            if (ma != null && mb != null) {
                masterPairsUnderReview.add(masterPairKey(ma, mb));
            }
            if (ma != null) {
                mastersAwaitingReview.add(ma);
            }
            if (mb != null) {
                mastersAwaitingReview.add(mb);
            }
        }

        // 4b: new review items, one per pair of (post-merge) masters not already under review —
        //     the best-scoring Silver pair between them.
        Map<String, MatchScore> bestPerMasterPair = new TreeMap<>();
        for (MatchScore s : reviewPairs) {
            long ma = current(bySilverId.get(s.silverPatientAId()).masterPatientId(), mergedInto);
            long mb = current(bySilverId.get(s.silverPatientBId()).masterPatientId(), mergedInto);
            if (ma == mb || masterPairsUnderReview.contains(masterPairKey(ma, mb))) {
                continue;
            }
            bestPerMasterPair.merge(masterPairKey(ma, mb), s, (x, y) -> x.score().compareTo(y.score()) >= 0 ? x : y);
        }
        List<Long> queued = new ArrayList<>();
        for (MatchScore s : bestPerMasterPair.values()) {
            long ma = current(bySilverId.get(s.silverPatientAId()).masterPatientId(), mergedInto);
            long mb = current(bySilverId.get(s.silverPatientBId()).masterPatientId(), mergedInto);
            candidates.insert(runId, s.silverPatientAId(), s.silverPatientBId(), ma, mb, s.score(), s.method(),
                    s.deterministicRule(), json(s), MatchCandidate.PENDING, null).ifPresent(queued::add);
            mastersAwaitingReview.add(ma);
            mastersAwaitingReview.add(mb);
        }

        // 5: confirm the rest
        Set<Long> survivors = new HashSet<>(mergedInto.values());
        List<Long> confirmed = new ArrayList<>();
        for (Long masterId : provisionalMasters) {
            if (mergedInto.containsKey(masterId) || survivors.contains(masterId) || mastersAwaitingReview.contains(masterId)
                    || unresolved.contains(masterId)) {
                continue;
            }
            try {
                goldWriter.confirm(masterId, decidedBy);
                confirmed.add(masterId);
            } catch (GoldWriterRejectedException e) {
                skipped.add("confirm master " + masterId + ": " + e.getMessage());
            }
        }

        MatchRun run = runs.complete(runId, provisionalMasters.size(), compared, merges.size(), queued.size(),
                confirmed.size());
        log.info("PMI run {}: {} provisional master(s), {} pair(s) compared, {} auto-merge(s), {} queued for review, "
                        + "{} confirmed, {} skipped, {} Silver patient(s) not yet in Gold",
                runId, provisionalMasters.size(), compared, merges.size(), queued.size(), confirmed.size(),
                skipped.size(), withoutMaster);
        return new RunResult(run, merges, queued, confirmed, skipped, withoutMaster);
    }

    /**
     * Returns absorbed master id → survivor id for every merge GoldWriter applied. Both masters of a
     * merge GoldWriter refused go into {@code unresolved}: they still look like duplicates, so they
     * must not be confirmed as singletons — they stay PROVISIONAL for the next run.
     */
    private Map<Long, Long> applyAutoMerges(long runId, String decidedBy, List<MatchScore> autoPairs,
                                            Map<Long, PatientRecord> bySilverId, List<RunResult.AutoMerge> merges,
                                            List<String> skipped, Set<Long> unresolved) {
        UnionFind clusters = new UnionFind();
        Map<Long, MatchScore> bestEdgeOf = new HashMap<>();
        Comparator<MatchScore> stronger = Comparator.comparing(MatchScore::score)
                .thenComparing(s -> MatchScore.DETERMINISTIC.equals(s.method()));
        for (MatchScore s : autoPairs) {
            long ma = bySilverId.get(s.silverPatientAId()).masterPatientId();
            long mb = bySilverId.get(s.silverPatientBId()).masterPatientId();
            clusters.union(ma, mb);
            for (long m : new long[]{ma, mb}) {
                bestEdgeOf.merge(m, s, (x, y) -> stronger.compare(x, y) >= 0 ? x : y);
            }
        }

        Map<Long, Long> mergedInto = new LinkedHashMap<>();
        for (Set<Long> cluster : clusters.clusters()) {
            long survivor = cluster.iterator().next(); // TreeSet: lowest id = oldest master
            for (long absorbed : cluster) {
                if (absorbed == survivor) {
                    continue;
                }
                MatchScore edge = bestEdgeOf.get(absorbed);
                try {
                    JsonNode result = goldWriter.merge(survivor, absorbed, edge.score(), edge.method(), decidedBy,
                            evidence(runId, edge));
                    long mergeId = result.path("mergeId").asLong();
                    mergedInto.put(absorbed, survivor);
                    candidates.insert(runId, edge.silverPatientAId(), edge.silverPatientBId(),
                            bySilverId.get(edge.silverPatientAId()).masterPatientId(),
                            bySilverId.get(edge.silverPatientBId()).masterPatientId(), edge.score(), edge.method(),
                            edge.deterministicRule(), json(edge), MatchCandidate.AUTO_MERGED, mergeId);
                    merges.add(new RunResult.AutoMerge(mergeId, survivor, absorbed, edge.score(), edge.method(),
                            edge.deterministicRule(), edge.silverPatientAId(), edge.silverPatientBId()));
                } catch (GoldWriterRejectedException e) {
                    skipped.add("merge master " + absorbed + " into " + survivor + ": " + e.getMessage());
                    unresolved.add(absorbed);
                    unresolved.add(survivor);
                }
            }
        }
        return mergedInto;
    }

    private Map<String, Object> evidence(long runId, MatchScore score) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", "patient-index-service matching run " + runId);
        evidence.put("silverPatientIds", List.of(score.silverPatientAId(), score.silverPatientBId()));
        evidence.put("score", score.score());
        evidence.put("weightedScore", score.weightedScore());
        evidence.put("method", score.method());
        if (score.deterministicRule() != null) {
            evidence.put("deterministicRule", score.deterministicRule());
        }
        evidence.put("fields", score.fields());
        return evidence;
    }

    private static String masterPairKey(long a, long b) {
        return Math.min(a, b) + ":" + Math.max(a, b);
    }

    private static long current(long masterId, Map<Long, Long> mergedInto) {
        return mergedInto.getOrDefault(masterId, masterId);
    }

    private static Long currentOf(long silverId, Map<Long, PatientRecord> bySilverId, Map<Long, Long> mergedInto) {
        PatientRecord r = bySilverId.get(silverId);
        return r == null ? null : current(r.masterPatientId(), mergedInto);
    }

    private String json(MatchScore score) {
        try {
            return objectMapper.writeValueAsString(score);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Disjoint sets of master ids; clusters come back as sorted sets (lowest id first). */
    static final class UnionFind {
        private final Map<Long, Long> parent = new HashMap<>();

        long find(long x) {
            parent.putIfAbsent(x, x);
            long root = x;
            for (long p = parent.get(root); p != root; p = parent.get(root)) {
                root = p;
            }
            for (long p = parent.get(x); p != root; p = parent.get(x)) {
                parent.put(x, root); // path compression
                x = p;
            }
            return root;
        }

        void union(long a, long b) {
            long ra = find(a);
            long rb = find(b);
            if (ra != rb) {
                parent.put(Math.max(ra, rb), Math.min(ra, rb));
            }
        }

        List<Set<Long>> clusters() {
            Map<Long, Set<Long>> byRoot = new TreeMap<>();
            for (Long x : new ArrayList<>(parent.keySet())) {
                byRoot.computeIfAbsent(find(x), r -> new TreeSet<>()).add(x);
            }
            List<Set<Long>> out = new ArrayList<>();
            for (Set<Long> c : byRoot.values()) {
                if (c.size() > 1) {
                    out.add(c);
                }
            }
            return out;
        }
    }
}
