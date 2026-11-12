package com.clinicalharmony.fhircompliance.gold;

import com.clinicalharmony.fhircompliance.repository.DataLineageRecord;
import com.clinicalharmony.fhircompliance.repository.DataLineageRepository;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRepository;
import com.clinicalharmony.fhircompliance.repository.MasterMergeRepository;
import com.clinicalharmony.fhircompliance.repository.MasterMergeRow;
import com.clinicalharmony.fhircompliance.repository.MasterPatientRepository;
import com.clinicalharmony.fhircompliance.repository.MasterPatientSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** GoldWriter's Phase 6 operations (confirm / merge / unmerge) — the decision logic, with mocked SQL. */
class GoldWriterMasterOperationsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GoldFhirResourceRepository fhirResources;
    private MasterPatientRepository masterPatients;
    private MasterMergeRepository merges;
    private DataLineageRepository lineage;
    private GoldWriter writer;

    @BeforeEach
    void setUp() {
        fhirResources = mock(GoldFhirResourceRepository.class);
        masterPatients = mock(MasterPatientRepository.class);
        merges = mock(MasterMergeRepository.class);
        lineage = mock(DataLineageRepository.class);
        writer = new GoldWriter(fhirResources, masterPatients, merges, lineage, objectMapper,
                Clock.fixed(Instant.parse("2026-11-12T12:00:00Z"), ZoneOffset.UTC));
    }

    private static MasterPatientSnapshot master(long id, String status, boolean active) {
        return new MasterPatientSnapshot(id, UUID.randomUUID(), status, active, null, active ? 1 : 0);
    }

    @Test
    void confirmTurnsProvisionalIntoMatchedAndRecordsLineage() throws Exception {
        when(masterPatients.lock(7L)).thenReturn(Optional.of(master(7, "PROVISIONAL", true)));
        when(masterPatients.markMatched(7L, null)).thenReturn(master(7, "MATCHED", true));

        MasterPatientSnapshot result = writer.confirmMaster(7L, "pmi-run-3");

        assertThat(result.matchStatus()).isEqualTo("MATCHED");
        ArgumentCaptor<DataLineageRecord> captor = ArgumentCaptor.forClass(DataLineageRecord.class);
        verify(lineage).upsert(captor.capture());
        assertThat(captor.getValue().transformationStep()).isEqualTo(LineageSource.PMI_MATCH);
        assertThat(captor.getValue().targetTable()).isEqualTo("master_patients");
        JsonNode details = objectMapper.readTree(captor.getValue().transformationDetails());
        assertThat(details.get("steps").get(0).get("transformer").asText()).contains("confirm").contains("pmi-run-3");
    }

    @Test
    void confirmIsIdempotentOnAlreadyMatchedMaster() {
        when(masterPatients.lock(7L)).thenReturn(Optional.of(master(7, "MATCHED", true)));

        assertThat(writer.confirmMaster(7L, "x").matchStatus()).isEqualTo("MATCHED");
        verify(masterPatients, never()).markMatched(anyLong(), any());
        verifyNoInteractions(lineage);
    }

    @Test
    void confirmRefusesMergedMasterAndNamesTheSurvivor() {
        when(masterPatients.lock(7L)).thenReturn(Optional.of(master(7, "MERGED", false)));
        when(merges.findLiveMergeAbsorbing(7L)).thenReturn(Optional.of(mergeRow(12, 3, 7, null)));

        assertThatThrownBy(() -> writer.confirmMaster(7L, "x"))
                .isInstanceOf(GoldMasterConflictException.class)
                .hasMessageContaining("merged into master 3 by merge 12");
    }

    @Test
    void confirmUnknownMasterIsNotFound() {
        when(masterPatients.lock(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> writer.confirmMaster(99L, "x")).isInstanceOf(GoldMasterNotFoundException.class);
    }

    @Test
    void mergeMovesLinksAndResourcesRecordsHistoryAndLocksInIdOrder() throws Exception {
        when(masterPatients.lock(3L)).thenReturn(Optional.of(master(3, "PROVISIONAL", true)));
        when(masterPatients.lock(8L)).thenReturn(Optional.of(master(8, "PROVISIONAL", true)));
        when(masterPatients.moveSourceLinks(3L, 8L)).thenReturn(List.of(31L));
        when(fhirResources.repointMaster(3L, 8L)).thenReturn(List.of(501L, 502L));
        when(merges.insert(eq(8L), eq(3L), eq(List.of(31L)), eq(List.of(501L, 502L)), any(), eq("PROBABILISTIC"),
                any(), eq("pmi-run-1"))).thenReturn(40L);
        when(merges.minLiveScore(8L)).thenReturn(new BigDecimal("0.9118"));
        when(masterPatients.markMerged(3L)).thenReturn(master(3, "MERGED", false));
        when(masterPatients.markMatched(8L, new BigDecimal("0.9118"))).thenReturn(
                new MasterPatientSnapshot(8, UUID.randomUUID(), "MATCHED", true, new BigDecimal("0.9118"), 2));

        // survivor 8 absorbs 3: locks must still go 3 then 8
        MasterMergeResult result = writer.mergeMasters(new MasterMergeCommand(8L, 3L, new BigDecimal("0.9118"),
                "PROBABILISTIC", "{\"pair\":[31,32]}", "pmi-run-1"));

        InOrder locks = inOrder(masterPatients);
        locks.verify(masterPatients).lock(3L);
        locks.verify(masterPatients).lock(8L);
        assertThat(result.mergeId()).isEqualTo(40L);
        assertThat(result.survivor().sourcePatientCount()).isEqualTo(2);
        assertThat(result.absorbed().matchStatus()).isEqualTo("MERGED");
        assertThat(result.movedFhirResourceIds()).containsExactly(501L, 502L);

        ArgumentCaptor<DataLineageRecord> captor = ArgumentCaptor.forClass(DataLineageRecord.class);
        verify(lineage).upsert(captor.capture());
        DataLineageRecord row = captor.getValue();
        assertThat(row.transformationStep()).isEqualTo(LineageSource.PMI_MERGE);
        assertThat(row.targetTable()).isEqualTo("master_patient_merges");
        assertThat(row.targetId()).isEqualTo(40L);
        assertThat(row.sourceId()).isEqualTo(3L);
        JsonNode step = objectMapper.readTree(row.transformationDetails()).get("steps").get(0);
        assertThat(step.get("input_hash").asText()).isEqualTo(Hashes.sha256Hex("{\"pair\":[31,32]}"));
        assertThat(step.get("transformer").asText()).contains("PROBABILISTIC, score 0.9118");
    }

    @Test
    void mergeRefusesSelfMergeAndInactiveMasters() {
        assertThatThrownBy(() -> writer.mergeMasters(new MasterMergeCommand(5L, 5L, null, "MANUAL", null, "x")))
                .isInstanceOf(GoldMasterConflictException.class);

        when(masterPatients.lock(3L)).thenReturn(Optional.of(master(3, "MERGED", false)));
        when(masterPatients.lock(8L)).thenReturn(Optional.of(master(8, "MATCHED", true)));
        assertThatThrownBy(() -> writer.mergeMasters(new MasterMergeCommand(8L, 3L, null, "MANUAL", null, "x")))
                .isInstanceOf(GoldMasterConflictException.class)
                .hasMessageContaining("only active masters");
        verify(masterPatients, never()).moveSourceLinks(anyLong(), anyLong());
    }

    @Test
    void unmergeRestoresLinksResourcesAndBothMasters() {
        when(merges.lockById(40L)).thenReturn(Optional.of(mergeRow(40, 8, 3, null)));
        when(masterPatients.lock(anyLong())).thenAnswer(inv -> Optional.of(master(inv.getArgument(0), "MATCHED", true)));
        when(merges.findLiveMergeAbsorbing(8L)).thenReturn(Optional.empty());
        when(masterPatients.moveSourceLinks(List.of(31L), 8L, 3L)).thenReturn(1);
        when(fhirResources.repointIds(List.of(501L), 8L, 3L)).thenReturn(1);
        when(fhirResources.linkPatientReferencesToMaster("silver-patient-31", 3L)).thenReturn(1);
        when(masterPatients.markMatched(eq(3L), any())).thenReturn(master(3, "MATCHED", true));
        when(masterPatients.markMatched(eq(8L), any())).thenReturn(master(8, "MATCHED", true));

        MasterUnmergeResult result = writer.unmergeMasters(40L, "reviewer:dana", "twins");

        assertThat(result.restoredSilverPatientIds()).containsExactly(31L);
        assertThat(result.restoredFhirResourceCount()).isEqualTo(2);
        assertThat(result.restored().active()).isTrue();
        verify(merges).markUnmerged(40L, "reviewer:dana", "twins");
        ArgumentCaptor<DataLineageRecord> captor = ArgumentCaptor.forClass(DataLineageRecord.class);
        verify(lineage).upsert(captor.capture());
        assertThat(captor.getValue().transformationStep()).isEqualTo(LineageSource.PMI_UNMERGE);
    }

    @Test
    void unmergeRefusesWhenAlreadyUndone() {
        when(merges.lockById(40L)).thenReturn(Optional.of(mergeRow(40, 8, 3, OffsetDateTime.now())));

        assertThatThrownBy(() -> writer.unmergeMasters(40L, "x", null))
                .isInstanceOf(GoldMasterConflictException.class).hasMessageContaining("already undone");
    }

    @Test
    void unmergeRefusesWhenSurvivorWasMergedOnward() {
        when(merges.lockById(40L)).thenReturn(Optional.of(mergeRow(40, 8, 3, null)));
        when(masterPatients.lock(anyLong())).thenAnswer(inv -> Optional.of(master(inv.getArgument(0), "MATCHED", true)));
        when(merges.findLiveMergeAbsorbing(8L)).thenReturn(Optional.of(mergeRow(41, 2, 8, null)));

        assertThatThrownBy(() -> writer.unmergeMasters(40L, "x", null))
                .isInstanceOf(GoldMasterConflictException.class)
                .hasMessageContaining("merged into master 2 (merge 41); undo that merge first");
        verify(masterPatients, never()).moveSourceLinks(anyList(), anyLong(), anyLong());
    }

    @Test
    void unmergeUnknownMergeIsNotFound() {
        when(merges.lockById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> writer.unmergeMasters(404L, "x", null)).isInstanceOf(GoldMasterNotFoundException.class);
    }

    private static MasterMergeRow mergeRow(long id, long survivor, long absorbed, OffsetDateTime unmergedAt) {
        return new MasterMergeRow(id, survivor, absorbed, List.of(31L), List.of(501L), new BigDecimal("0.9118"),
                "PROBABILISTIC", "pmi-run-1", OffsetDateTime.now(), unmergedAt);
    }
}
