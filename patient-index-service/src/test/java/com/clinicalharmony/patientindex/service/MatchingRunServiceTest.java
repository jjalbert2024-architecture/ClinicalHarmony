package com.clinicalharmony.patientindex.service;

import com.clinicalharmony.patientindex.client.GoldWriterClient;
import com.clinicalharmony.patientindex.client.GoldWriterRejectedException;
import com.clinicalharmony.patientindex.client.GoldWriterUnavailableException;
import com.clinicalharmony.patientindex.matching.MatchSettings;
import com.clinicalharmony.patientindex.matching.PatientMatcher;
import com.clinicalharmony.patientindex.matching.PatientRecord;
import com.clinicalharmony.patientindex.repository.MatchCandidate;
import com.clinicalharmony.patientindex.repository.MatchCandidateRepository;
import com.clinicalharmony.patientindex.repository.MatchExclusionRepository;
import com.clinicalharmony.patientindex.repository.MatchRun;
import com.clinicalharmony.patientindex.repository.MatchRunRepository;
import com.clinicalharmony.patientindex.repository.PmiReadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchingRunServiceTest {

    private PmiReadRepository reads;
    private MatchRunRepository runs;
    private MatchCandidateRepository candidates;
    private MatchExclusionRepository exclusions;
    private MatchSettingsLoader settings;
    private GoldWriterClient goldWriter;
    private MatchingRunService service;
    private final AtomicLong mergeIds = new AtomicLong(100);

    // Masters: Maria 11 (SEED-0001, the oldest), Maria 14 (claims copy), Maria 16 (DOB swapped),
    // James 12 + Jim 15, Robert 18, and James 20 who is already MATCHED.
    private static PatientRecord rec(long silver, long master, String status, String source, String mrn, String first,
                                     String last, String dob, String gender, String address, String zip, String phone) {
        return new PatientRecord(silver, master, status, source, "S-" + silver, mrn, first, last, LocalDate.parse(dob),
                gender, address, null, null, zip, phone);
    }

    private static final PatientRecord MARIA = rec(1, 11, "PROVISIONAL", "EPIC", "MRN1", "Maria", "Garcia", "1968-04-12",
            "female", "1200 Congress Ave", "78701", "512-555-0101");
    private static final PatientRecord MARIA_CLAIMS = rec(4, 14, "PROVISIONAL", "CLAIMS", null, "Maria", "Garcia",
            "1968-04-12", "female", "1200 Congress Avenue", "78701", "(512) 555-0101");
    private static final PatientRecord MARIA_SWAP = rec(6, 16, "PROVISIONAL", "EPIC", "MRN6", "Maria", "Garcia",
            "1968-12-04", "female", "77 Rainey St", "78701", null);
    private static final PatientRecord JAMES = rec(2, 12, "PROVISIONAL", "CERNER", "MRN2", "James", "Wilson", "1955-09-30",
            "male", "45 Beacon St", "02108", "617-555-0202");
    private static final PatientRecord JIM = rec(5, 15, "PROVISIONAL", "LAB", "L5", "Jim", "Wilson", "1955-09-30", "male",
            "45 Beacon Street", "02108", null);
    private static final PatientRecord ROBERT = rec(8, 18, "PROVISIONAL", "CLAIMS", null, "Robert", "Wilson", "1955-09-30",
            "male", "9 Camelback Rd", "85001", "602-555-0303");

    @BeforeEach
    void setUp() {
        reads = mock(PmiReadRepository.class);
        runs = mock(MatchRunRepository.class);
        candidates = mock(MatchCandidateRepository.class);
        exclusions = mock(MatchExclusionRepository.class);
        settings = mock(MatchSettingsLoader.class);
        goldWriter = mock(GoldWriterClient.class);
        service = new MatchingRunService(reads, runs, candidates, exclusions, settings, new PatientMatcher(), goldWriter,
                new PmiLock(), new ObjectMapper());

        when(settings.loadActive()).thenReturn(MatchSettings.DEFAULTS);
        when(settings.toJson(any())).thenReturn("{}");
        when(runs.start(anyString(), anyString())).thenReturn(7L);
        when(runs.complete(eq(7L), anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(inv -> new MatchRun(7L,
                "COMPLETED", "t", null, null, inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                inv.getArgument(4), inv.getArgument(5), null));
        when(exclusions.allPairKeys()).thenReturn(Set.of());
        when(goldWriter.merge(anyLong(), anyLong(), any(), anyString(), anyString(), any())).thenAnswer(inv ->
                JsonNodeFactory.instance.objectNode().put("mergeId", mergeIds.incrementAndGet()));
        AtomicLong candidateIds = new AtomicLong(500);
        when(candidates.insert(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyString(), any(), anyString(),
                anyString(), any())).thenAnswer(inv -> Optional.of(candidateIds.incrementAndGet()));
    }

    @Test
    void mergesClustersIntoOldestMasterQueuesReviewsAndConfirmsTheRest() {
        when(reads.activeMasterSources()).thenReturn(List.of(MARIA, JAMES, MARIA_CLAIMS, JIM, MARIA_SWAP, ROBERT));
        List<MatchCandidate> pendingAfter = new ArrayList<>();
        when(candidates.pending()).thenAnswer(inv -> pendingAfter);
        // the review item queued this run: MARIA_SWAP (6) vs the Garcia cluster
        when(candidates.insert(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyString(), any(),
                anyString(), eq(MatchCandidate.PENDING), isNull())).thenAnswer(inv -> {
            pendingAfter.add(new MatchCandidate(900, 7, inv.getArgument(1), inv.getArgument(2), inv.getArgument(3),
                    inv.getArgument(4), inv.getArgument(5), "PROBABILISTIC", null, "{}", "PENDING", null, null, null,
                    null, null));
            return Optional.of(900L);
        });

        RunResult result = service.run("api:test");

        // Maria 14 → 11 (deterministic), Jim 15 → 12 (probabilistic 0.9118)
        assertThat(result.autoMerges()).extracting(RunResult.AutoMerge::absorbedMasterId, RunResult.AutoMerge::survivorMasterId)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(14L, 11L), org.assertj.core.groups.Tuple.tuple(15L, 12L));
        verify(goldWriter).merge(eq(11L), eq(14L), eq(new BigDecimal("1.0000")), eq("DETERMINISTIC"), eq("pmi-run-7"), any());
        verify(goldWriter).merge(eq(12L), eq(15L), eq(new BigDecimal("0.9118")), eq("PROBABILISTIC"), eq("pmi-run-7"), any());

        // one review item for Maria-swap vs the Garcia cluster, keyed to the SURVIVOR master
        assertThat(result.queuedCandidateIds()).containsExactly(900L);
        assertThat(result.run().queuedForReview()).isEqualTo(1);
        assertThat(pendingAfter).singleElement().satisfies(c -> {
            assertThat(c.silverPatientAId()).isIn(1L, 4L);
            assertThat(c.silverPatientBId()).isEqualTo(6L);
            assertThat(c.masterAId()).isEqualTo(11L);
            assertThat(c.masterBId()).isEqualTo(16L);
        });

        // Robert has no match → confirmed; Maria-swap awaits review → stays PROVISIONAL; survivors are MATCHED by the merge
        assertThat(result.confirmedMasterIds()).containsExactly(18L);
        verify(goldWriter, never()).confirm(eq(16L), anyString());
        verify(goldWriter, never()).confirm(eq(11L), anyString());
        assertThat(result.run().autoMerges()).isEqualTo(2);
        assertThat(result.run().provisionalMasters()).isEqualTo(6);
    }

    @Test
    void excludedPairsAndPairsOfDecidedMastersAreNotCompared() {
        PatientRecord jamesMatched = new PatientRecord(2, 12L, "MATCHED", "CERNER", "S-2", "MRN2", "James", "Wilson",
                LocalDate.parse("1955-09-30"), "male", "45 Beacon St", null, null, "02108", "617-555-0202");
        PatientRecord jimMatched = new PatientRecord(5, 15L, "MATCHED", "LAB", "S-5", "L5", "Jim", "Wilson",
                LocalDate.parse("1955-09-30"), "male", "45 Beacon Street", null, null, "02108", null);
        when(reads.activeMasterSources()).thenReturn(List.of(MARIA, MARIA_CLAIMS, jamesMatched, jimMatched));
        when(exclusions.allPairKeys()).thenReturn(Set.of("1:4"));
        when(candidates.pending()).thenReturn(List.of());

        RunResult result = service.run("api:test");

        assertThat(result.autoMerges()).isEmpty();
        assertThat(result.run().pairsCompared()).isZero();
        assertThat(result.confirmedMasterIds()).containsExactly(11L, 14L);
    }

    @Test
    void refusedMergeIsSkippedAndReportedRunContinues() {
        when(reads.activeMasterSources()).thenReturn(List.of(MARIA, MARIA_CLAIMS, ROBERT));
        when(candidates.pending()).thenReturn(List.of());
        when(goldWriter.merge(eq(11L), eq(14L), any(), anyString(), anyString(), any()))
                .thenThrow(new GoldWriterRejectedException(409, "Master 14 is MERGED"));

        RunResult result = service.run("api:test");

        assertThat(result.autoMerges()).isEmpty();
        assertThat(result.skipped()).singleElement().asString().contains("Master 14 is MERGED");
        // 11 and 14 still look like duplicates: neither is confirmed as a singleton
        assertThat(result.confirmedMasterIds()).containsExactly(18L);
        verify(runs, never()).fail(anyLong(), anyString());
    }

    @Test
    void goldWriterDownFailsTheRun() {
        when(reads.activeMasterSources()).thenReturn(List.of(MARIA, MARIA_CLAIMS));
        when(goldWriter.merge(anyLong(), anyLong(), any(), anyString(), anyString(), any()))
                .thenThrow(new GoldWriterUnavailableException("GoldWriter unreachable", null));

        assertThatThrownBy(() -> service.run("api:test")).isInstanceOf(GoldWriterUnavailableException.class);
        verify(runs).fail(7L, "GoldWriter unreachable");
    }

    @Test
    void staleReviewItemsWhoseRecordsShareAMasterAreSuperseded() {
        when(reads.activeMasterSources()).thenReturn(List.of(MARIA, MARIA_CLAIMS));
        MatchCandidate stale = new MatchCandidate(77, 1, 1, 4, 11, 14, new BigDecimal("0.7"), "PROBABILISTIC", null, "{}",
                "PENDING", null, null, null, null, null);
        when(candidates.pending()).thenReturn(List.of(stale));

        service.run("api:test");

        verify(candidates).decide(eq(77L), eq(MatchCandidate.SUPERSEDED), eq("pmi-run-7"), anyString(), isNull());
    }

    @Test
    void aMasterPairAlreadyUnderReviewIsNotQueuedAgainViaAnotherSilverPair() {
        when(reads.activeMasterSources()).thenReturn(List.of(MARIA, MARIA_CLAIMS, MARIA_SWAP));
        // an earlier run queued (MARIA_CLAIMS 4, MARIA_SWAP 6); this run will score (1, 6) just as well
        MatchCandidate open = new MatchCandidate(77, 1, 4, 6, 14, 16, new BigDecimal("0.6471"), "PROBABILISTIC", null,
                "{}", "PENDING", null, null, null, null, null);
        when(candidates.pending()).thenReturn(List.of(open));

        RunResult result = service.run("api:test");

        assertThat(result.queuedCandidateIds()).isEmpty();
        verify(candidates, never()).insert(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyString(),
                any(), anyString(), eq(MatchCandidate.PENDING), any());
        verify(goldWriter, never()).confirm(eq(16L), anyString());
    }

    @Test
    void inactiveRuleBlocksTheRun() {
        when(settings.loadActive()).thenThrow(new ConflictException("Rule PMI_MATCH is inactive"));

        assertThatThrownBy(() -> service.run("x")).isInstanceOf(ConflictException.class);
        verify(runs, never()).start(anyString(), anyString());
    }

    @Test
    void unionFindBuildsSortedClusters() {
        MatchingRunService.UnionFind uf = new MatchingRunService.UnionFind();
        uf.union(30, 10);
        uf.union(20, 30);
        uf.union(1000, 2000);
        uf.find(5);

        assertThat(uf.clusters()).extracting(Object::toString).containsExactly("[10, 20, 30]", "[1000, 2000]");
    }
}
