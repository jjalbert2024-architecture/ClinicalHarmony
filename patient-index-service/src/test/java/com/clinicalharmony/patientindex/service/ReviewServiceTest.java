package com.clinicalharmony.patientindex.service;

import com.clinicalharmony.patientindex.client.GoldWriterClient;
import com.clinicalharmony.patientindex.matching.MatchSettings;
import com.clinicalharmony.patientindex.matching.PatientMatcher;
import com.clinicalharmony.patientindex.matching.PatientRecord;
import com.clinicalharmony.patientindex.repository.MasterRow;
import com.clinicalharmony.patientindex.repository.MatchCandidate;
import com.clinicalharmony.patientindex.repository.MatchCandidateRepository;
import com.clinicalharmony.patientindex.repository.MatchExclusionRepository;
import com.clinicalharmony.patientindex.repository.MergeRow;
import com.clinicalharmony.patientindex.repository.PmiReadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewServiceTest {

    private PmiReadRepository reads;
    private MatchCandidateRepository candidates;
    private MatchExclusionRepository exclusions;
    private GoldWriterClient goldWriter;
    private ReviewService service;

    private static final MatchCandidate PENDING = new MatchCandidate(900, 7, 1, 6, 11, 16, new BigDecimal("0.6471"),
            "PROBABILISTIC", null, "{\"fields\":[]}", "PENDING", null, null, null, null, null);

    private static PatientRecord rec(long silver, long master, String status) {
        return new PatientRecord(silver, master, status, "SRC", "S-" + silver, null, "Maria", "Garcia",
                LocalDate.parse("1968-04-12"), "female", null, null, null, "78701", null);
    }

    private static MasterRow master(long id, String status, boolean active) {
        return new MasterRow(id, UUID.randomUUID(), "Maria", "Garcia", LocalDate.parse("1968-04-12"), "female", status,
                active, null, 1, null, null);
    }

    @BeforeEach
    void setUp() {
        reads = mock(PmiReadRepository.class);
        candidates = mock(MatchCandidateRepository.class);
        exclusions = mock(MatchExclusionRepository.class);
        goldWriter = mock(GoldWriterClient.class);
        MatchSettingsLoader settings = mock(MatchSettingsLoader.class);
        when(settings.load()).thenReturn(MatchSettings.DEFAULTS);
        service = new ReviewService(reads, candidates, exclusions, settings, new PatientMatcher(), goldWriter,
                new PmiLock(), new ObjectMapper());
        when(goldWriter.merge(anyLong(), anyLong(), any(), anyString(), anyString(), any()))
                .thenReturn(JsonNodeFactory.instance.objectNode().put("mergeId", 41));
    }

    @Test
    void acceptMergesTheNewerCurrentMasterIntoTheOlder() {
        when(candidates.find(900)).thenReturn(Optional.of(PENDING));
        // since scoring, Silver 1's master 11 absorbed nothing new; Silver 6 is still on 16
        when(reads.findPatient(1)).thenReturn(Optional.of(rec(1, 11, "MATCHED")));
        when(reads.findPatient(6)).thenReturn(Optional.of(rec(6, 16, "PROVISIONAL")));

        service.accept(900, "dana", "same address history");

        verify(goldWriter).merge(eq(11L), eq(16L), eq(new BigDecimal("0.6471")), eq("MANUAL"), eq("reviewer:dana"), any());
        verify(candidates).decide(900, MatchCandidate.ACCEPTED, "dana", "same address history", 41L);
    }

    @Test
    void acceptWhenAlreadyUnderOneMasterSupersedesAndConflicts() {
        when(candidates.find(900)).thenReturn(Optional.of(PENDING));
        when(reads.findPatient(1)).thenReturn(Optional.of(rec(1, 11, "MATCHED")));
        when(reads.findPatient(6)).thenReturn(Optional.of(rec(6, 11, "MATCHED")));

        assertThatThrownBy(() -> service.accept(900, "dana", null)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("SUPERSEDED");
        verify(candidates).decide(eq(900L), eq(MatchCandidate.SUPERSEDED), eq("dana"), anyString(), isNull());
        verify(goldWriter, never()).merge(anyLong(), anyLong(), any(), anyString(), anyString(), any());
    }

    @Test
    void decidedCandidatesCannotBeDecidedAgain() {
        MatchCandidate rejected = new MatchCandidate(900, 7, 1, 6, 11, 16, BigDecimal.ONE, "PROBABILISTIC", null, "{}",
                "REJECTED", null, "dana", OffsetDateTime.now(), null, null);
        when(candidates.find(900)).thenReturn(Optional.of(rejected));

        assertThatThrownBy(() -> service.accept(900, "x", null)).isInstanceOf(ConflictException.class)
                .hasMessage("Candidate 900 is REJECTED, not PENDING");
        assertThatThrownBy(() -> service.reject(901, "x", null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void rejectExcludesThePairAndConfirmsAProvisionalMasterWithNothingElsePending() {
        when(candidates.find(900)).thenReturn(Optional.of(PENDING));
        when(reads.findPatient(1)).thenReturn(Optional.of(rec(1, 11, "MATCHED")));
        when(reads.findPatient(6)).thenReturn(Optional.of(rec(6, 16, "PROVISIONAL")));
        when(reads.findMaster(11)).thenReturn(Optional.of(master(11, "MATCHED", true)));
        when(reads.findMaster(16)).thenReturn(Optional.of(master(16, "PROVISIONAL", true)));
        when(reads.sourcesOfMaster(16)).thenReturn(List.of(rec(6, 16, "PROVISIONAL")));
        when(candidates.pendingInvolving(List.of(6L))).thenReturn(List.of());

        ReviewService.Decision decision = service.reject(900, "dana", "twins");

        verify(candidates).decide(900, MatchCandidate.REJECTED, "dana", "twins", null);
        verify(exclusions).insert(1, 6, MatchExclusionRepository.REVIEW_REJECTED, "dana", "twins");
        verify(goldWriter).confirm(16, "reviewer:dana");
        verify(goldWriter, never()).confirm(eq(11L), anyString());
        assertThat(decision.confirmedMasterIds()).containsExactly(16L);
    }

    @Test
    void unmergeExcludesEveryPairingTheMergeJoined() {
        MergeRow merge = new MergeRow(41, 11, 16, List.of(6L, 7L), 2, new BigDecimal("0.9"), "MANUAL", "reviewer:dana",
                OffsetDateTime.now(), null, null, null);
        when(reads.findMerge(41)).thenReturn(Optional.of(merge));
        when(reads.sourcesOfMaster(11)).thenReturn(List.of(rec(1, 11, "MATCHED"), rec(4, 11, "MATCHED"),
                rec(6, 11, "MATCHED"), rec(7, 11, "MATCHED")));
        when(exclusions.insert(anyLong(), anyLong(), anyString(), anyString(), anyString())).thenReturn(true);
        when(goldWriter.unmerge(41, "reviewer:dana", "different people"))
                .thenReturn(JsonNodeFactory.instance.objectNode().put("mergeId", 41));

        ReviewService.UnmergeOutcome outcome = service.unmerge(41, "dana", "different people");

        // moved {6, 7} × remaining {1, 4}
        assertThat(outcome.exclusionsAdded()).isEqualTo(4);
        verify(exclusions).insert(6, 1, MatchExclusionRepository.UNMERGED, "dana", "different people");
        verify(exclusions).insert(7, 4, MatchExclusionRepository.UNMERGED, "dana", "different people");
        verify(exclusions, never()).insert(eq(6L), eq(7L), anyString(), anyString(), anyString());
    }

    @Test
    void unmergeOfUndoneOrUnknownMergeIsRefusedWithoutCallingGoldWriter() {
        when(reads.findMerge(41)).thenReturn(Optional.of(new MergeRow(41, 11, 16, List.of(6L), 0, null, "MANUAL", "x",
                OffsetDateTime.now(), OffsetDateTime.now(), "x", "y")));

        assertThatThrownBy(() -> service.unmerge(41, "dana", "r")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.unmerge(42, "dana", "r")).isInstanceOf(NotFoundException.class);
        verify(goldWriter, never()).unmerge(anyLong(), anyString(), anyString());
    }

    @Test
    void manualMergeRecordsBestPairEvidenceAndSupersedesCrossingReviewItems() {
        when(reads.findMaster(11)).thenReturn(Optional.of(master(11, "MATCHED", true)));
        when(reads.findMaster(16)).thenReturn(Optional.of(master(16, "PROVISIONAL", true)));
        when(reads.sourcesOfMaster(11)).thenReturn(List.of(rec(1, 11, "MATCHED")));
        when(reads.sourcesOfMaster(16)).thenReturn(List.of(rec(6, 16, "PROVISIONAL")));
        when(candidates.pendingInvolving(anyList())).thenReturn(List.of(PENDING));

        service.manualMerge(11, 16, "dana", "confirmed by phone");

        verify(goldWriter).merge(eq(11L), eq(16L), eq(new BigDecimal("1.0000")), eq("MANUAL"), eq("reviewer:dana"), any());
        verify(candidates).decide(eq(900L), eq(MatchCandidate.SUPERSEDED), eq("dana"), anyString(), eq(41L));
    }

    @Test
    void manualMergeRejectsSelfAndInactiveMasters() {
        assertThatThrownBy(() -> service.manualMerge(11, 11, "dana", null)).isInstanceOf(ConflictException.class);
        when(reads.findMaster(11)).thenReturn(Optional.of(master(11, "MATCHED", true)));
        when(reads.findMaster(16)).thenReturn(Optional.of(master(16, "MERGED", false)));
        assertThatThrownBy(() -> service.manualMerge(11, 16, "dana", null)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.manualMerge(11, 99, "dana", null)).isInstanceOf(NotFoundException.class);
    }
}
