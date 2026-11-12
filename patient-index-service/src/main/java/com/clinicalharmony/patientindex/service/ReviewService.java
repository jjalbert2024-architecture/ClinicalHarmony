package com.clinicalharmony.patientindex.service;

import com.clinicalharmony.patientindex.client.GoldWriterClient;
import com.clinicalharmony.patientindex.client.GoldWriterRejectedException;
import com.clinicalharmony.patientindex.matching.MatchScore;
import com.clinicalharmony.patientindex.matching.MatchSettings;
import com.clinicalharmony.patientindex.matching.PatientMatcher;
import com.clinicalharmony.patientindex.matching.PatientRecord;
import com.clinicalharmony.patientindex.repository.MasterRow;
import com.clinicalharmony.patientindex.repository.MatchCandidate;
import com.clinicalharmony.patientindex.repository.MatchCandidateRepository;
import com.clinicalharmony.patientindex.repository.MatchExclusionRepository;
import com.clinicalharmony.patientindex.repository.MergeRow;
import com.clinicalharmony.patientindex.repository.PmiReadRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Human decisions on the PMI: accept / reject a review item, merge two masters by hand, undo a
 * merge. Gold changes go through GoldWriter; this service records the decision in pmi.* and,
 * for "not the same person" decisions (reject, unmerge), adds exclusions so no later run proposes
 * that pairing again.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final PmiReadRepository reads;
    private final MatchCandidateRepository candidates;
    private final MatchExclusionRepository exclusions;
    private final MatchSettingsLoader settingsLoader;
    private final PatientMatcher matcher;
    private final GoldWriterClient goldWriter;
    private final PmiLock lock;
    private final ObjectMapper objectMapper;

    public ReviewService(PmiReadRepository reads, MatchCandidateRepository candidates,
                         MatchExclusionRepository exclusions, MatchSettingsLoader settingsLoader,
                         PatientMatcher matcher, GoldWriterClient goldWriter, PmiLock lock, ObjectMapper objectMapper) {
        this.reads = reads;
        this.candidates = candidates;
        this.exclusions = exclusions;
        this.settingsLoader = settingsLoader;
        this.matcher = matcher;
        this.goldWriter = goldWriter;
        this.lock = lock;
        this.objectMapper = objectMapper;
    }

    public record Decision(MatchCandidate candidate, JsonNode merge, List<Long> confirmedMasterIds) {
    }

    public record UnmergeOutcome(JsonNode unmerge, int exclusionsAdded) {
    }

    /** Same person: merge the two records' current masters (into the older one). */
    public Decision accept(long candidateId, String reviewer, String note) {
        return lock.reviewExclusively(() -> {
            MatchCandidate c = pendingCandidate(candidateId);
            long ma = currentMaster(c.silverPatientAId());
            long mb = currentMaster(c.silverPatientBId());
            if (ma == mb) {
                candidates.decide(c.id(), MatchCandidate.SUPERSEDED, reviewer, "Both records already under master " + ma, null);
                throw new ConflictException("Candidate " + candidateId + ": both records are already under master " + ma
                        + "; marked SUPERSEDED");
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("source", "patient-index-service review of candidate " + candidateId);
            evidence.put("reviewer", reviewer);
            evidence.put("note", note);
            evidence.put("silverPatientIds", List.of(c.silverPatientAId(), c.silverPatientBId()));
            evidence.put("score", c.score());
            evidence.put("breakdown", readJson(c.breakdown()));
            JsonNode merge = goldWriter.merge(Math.min(ma, mb), Math.max(ma, mb), c.score(), "MANUAL",
                    "reviewer:" + reviewer, evidence);
            candidates.decide(c.id(), MatchCandidate.ACCEPTED, reviewer, note, merge.path("mergeId").asLong());
            log.info("Candidate {} accepted by {}: master {} merged into {}", candidateId, reviewer,
                    Math.max(ma, mb), Math.min(ma, mb));
            return new Decision(candidates.find(c.id()).orElseThrow(), merge, List.of());
        });
    }

    /** Not the same person: exclude the pair; confirm either master if nothing else awaits review. */
    public Decision reject(long candidateId, String reviewer, String note) {
        return lock.reviewExclusively(() -> {
            MatchCandidate c = pendingCandidate(candidateId);
            candidates.decide(c.id(), MatchCandidate.REJECTED, reviewer, note, null);
            exclusions.insert(c.silverPatientAId(), c.silverPatientBId(), MatchExclusionRepository.REVIEW_REJECTED,
                    reviewer, note);
            List<Long> confirmed = confirmIfSettled(
                    List.of(currentMaster(c.silverPatientAId()), currentMaster(c.silverPatientBId())), reviewer);
            log.info("Candidate {} rejected by {}; confirmed masters {}", candidateId, reviewer, confirmed);
            return new Decision(candidates.find(c.id()).orElseThrow(), null, confirmed);
        });
    }

    /** A reviewer merges two masters directly (no review item needed). */
    public JsonNode manualMerge(long survivorMasterId, long absorbedMasterId, String reviewer, String note) {
        return lock.reviewExclusively(() -> {
            if (survivorMasterId == absorbedMasterId) {
                throw new ConflictException("A master can't be merged into itself (id " + survivorMasterId + ")");
            }
            activeMaster(survivorMasterId);
            activeMaster(absorbedMasterId);
            List<PatientRecord> survivorSources = reads.sourcesOfMaster(survivorMasterId);
            List<PatientRecord> absorbedSources = reads.sourcesOfMaster(absorbedMasterId);

            // Best-scoring cross pair, recorded as evidence (the merge is MANUAL either way).
            MatchSettings settings = settingsLoader.load();
            MatchScore best = null;
            for (PatientRecord s : survivorSources) {
                for (PatientRecord a : absorbedSources) {
                    MatchScore score = matcher.score(s, a, settings);
                    if (best == null || score.score().compareTo(best.score()) > 0) {
                        best = score;
                    }
                }
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("source", "patient-index-service manual merge");
            evidence.put("reviewer", reviewer);
            evidence.put("note", note);
            if (best != null) {
                evidence.put("bestPair", best);
            }
            JsonNode merge = goldWriter.merge(survivorMasterId, absorbedMasterId, best == null ? null : best.score(),
                    "MANUAL", "reviewer:" + reviewer, evidence);
            long mergeId = merge.path("mergeId").asLong();

            // Open review items between these two masters are now answered.
            Set<Long> survivorIds = ids(survivorSources);
            Set<Long> absorbedIds = ids(absorbedSources);
            List<Long> all = new ArrayList<>(survivorIds);
            all.addAll(absorbedIds);
            for (MatchCandidate c : candidates.pendingInvolving(all)) {
                boolean crosses = (survivorIds.contains(c.silverPatientAId()) && absorbedIds.contains(c.silverPatientBId()))
                        || (absorbedIds.contains(c.silverPatientAId()) && survivorIds.contains(c.silverPatientBId()));
                if (crosses) {
                    candidates.decide(c.id(), MatchCandidate.SUPERSEDED, reviewer, "Merged manually (merge " + mergeId + ")",
                            mergeId);
                }
            }
            log.info("{} manually merged master {} into {} (merge {})", reviewer, absorbedMasterId, survivorMasterId, mergeId);
            return merge;
        });
    }

    /** Undo a merge, and exclude every pairing it had joined so no run re-merges them. */
    public UnmergeOutcome unmerge(long mergeId, String reviewer, String reason) {
        return lock.reviewExclusively(() -> {
            MergeRow merge = reads.findMerge(mergeId)
                    .orElseThrow(() -> new NotFoundException("No merge with id " + mergeId));
            if (!merge.live()) {
                throw new ConflictException("Merge " + mergeId + " was already undone at " + merge.unmergedAt());
            }
            Set<Long> survivorBefore = ids(reads.sourcesOfMaster(merge.survivorMasterId()));
            JsonNode result = goldWriter.unmerge(mergeId, "reviewer:" + reviewer, reason);

            int added = 0;
            for (long moved : merge.movedSilverPatientIds()) {
                for (long remaining : survivorBefore) {
                    if (!merge.movedSilverPatientIds().contains(remaining)
                            && exclusions.insert(moved, remaining, MatchExclusionRepository.UNMERGED, reviewer, reason)) {
                        added++;
                    }
                }
            }
            log.info("{} undid merge {} ({} exclusion(s) added)", reviewer, mergeId, added);
            return new UnmergeOutcome(result, added);
        });
    }

    private List<Long> confirmIfSettled(List<Long> masterIds, String reviewer) {
        List<Long> confirmed = new ArrayList<>();
        for (Long masterId : masterIds.stream().distinct().sorted(Comparator.naturalOrder()).toList()) {
            MasterRow master = reads.findMaster(masterId).orElse(null);
            if (master == null || !master.active() || !"PROVISIONAL".equals(master.matchStatus())) {
                continue;
            }
            if (!candidates.pendingInvolving(List.copyOf(ids(reads.sourcesOfMaster(masterId)))).isEmpty()) {
                continue;
            }
            try {
                goldWriter.confirm(masterId, "reviewer:" + reviewer);
                confirmed.add(masterId);
            } catch (GoldWriterRejectedException e) {
                log.info("Could not confirm master {} after review: {}", masterId, e.getMessage());
            }
        }
        return confirmed;
    }

    private MatchCandidate pendingCandidate(long candidateId) {
        MatchCandidate c = candidates.find(candidateId)
                .orElseThrow(() -> new NotFoundException("No match candidate with id " + candidateId));
        if (!MatchCandidate.PENDING.equals(c.status())) {
            throw new ConflictException("Candidate " + candidateId + " is " + c.status() + ", not PENDING");
        }
        return c;
    }

    private long currentMaster(long silverPatientId) {
        PatientRecord r = reads.findPatient(silverPatientId)
                .orElseThrow(() -> new NotFoundException("Silver patient " + silverPatientId + " no longer exists"));
        if (r.masterPatientId() == null) {
            throw new ConflictException("Silver patient " + silverPatientId + " has no master");
        }
        return r.masterPatientId();
    }

    private void activeMaster(long masterId) {
        MasterRow m = reads.findMaster(masterId).orElseThrow(() -> new NotFoundException("No master patient with id " + masterId));
        if (!m.active()) {
            throw new ConflictException("Master " + masterId + " is " + m.matchStatus() + "; only active masters can be merged");
        }
    }

    private static Set<Long> ids(List<PatientRecord> records) {
        return records.stream().map(PatientRecord::silverPatientId).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private JsonNode readJson(String json) {
        try {
            return json == null ? null : objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
