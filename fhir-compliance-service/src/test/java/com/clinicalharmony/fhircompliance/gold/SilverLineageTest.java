package com.clinicalharmony.fhircompliance.gold;

import com.clinicalharmony.fhircompliance.repository.silver.SilverProvenance;
import com.clinicalharmony.fhircompliance.repository.silver.SilverProvenanceRepository;
import com.clinicalharmony.fhircompliance.repository.silver.SilverTable;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SilverLineageTest {

    private final SilverProvenanceRepository repository = mock(SilverProvenanceRepository.class);
    private final SilverLineage lineage = new SilverLineage(repository);

    @Test
    void silverRowLinkedToBronzeGetsBronzeThenSilverWithPayloadChecksum() {
        when(repository.find(SilverTable.CONDITIONS, 10L)).thenReturn(Optional.of(new SilverProvenance(
                OffsetDateTime.parse("2026-11-01T10:00:05Z"), "EPIC_ADT", 77L, "c".repeat(64),
                OffsetDateTime.parse("2026-11-01T10:00:00Z"), "HL7V2")));

        List<LineageStep> steps = lineage.stepsFor(SilverTable.CONDITIONS, 10L);

        assertThat(steps).extracting(LineageStep::stage).containsExactly("BRONZE", "SILVER");
        assertThat(steps.get(0).inputHash()).isEqualTo("c".repeat(64));
        assertThat(steps.get(0).ref()).isEqualTo("bronze.raw_messages/77");
        assertThat(steps.get(0).transformer()).isEqualTo("ingestion-service (HL7V2)");
        assertThat(steps.get(0).timestamp()).isEqualTo("2026-11-01T10:00:00Z");
        assertThat(steps.get(1).inputHash()).isEqualTo("c".repeat(64));
        assertThat(steps.get(1).ref()).isEqualTo("silver.conditions/10");
    }

    @Test
    void silverRowWithoutBronzeLinkStartsAtSilverWithNoInputHash() {
        when(repository.find(SilverTable.PATIENTS, 1L)).thenReturn(Optional.of(new SilverProvenance(
                OffsetDateTime.parse("2026-11-01T10:00:05Z"), "SEED_EPIC_ADT", null, null, null, null)));

        List<LineageStep> steps = lineage.stepsFor(SilverTable.PATIENTS, 1L);

        assertThat(steps).singleElement().satisfies(step -> {
            assertThat(step.stage()).isEqualTo("SILVER");
            assertThat(step.inputHash()).isNull();
            assertThat(step.transformer()).contains("no Bronze link", "SEED_EPIC_ADT");
            assertThat(step.ref()).isEqualTo("silver.patients/1");
        });
    }

    @Test
    void missingSilverRowYieldsNoSteps() {
        when(repository.find(SilverTable.OBSERVATIONS, 5L)).thenReturn(Optional.empty());
        assertThat(lineage.stepsFor(SilverTable.OBSERVATIONS, 5L)).isEmpty();
    }
}
