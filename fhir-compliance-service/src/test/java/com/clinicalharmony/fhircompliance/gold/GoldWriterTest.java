package com.clinicalharmony.fhircompliance.gold;

import com.clinicalharmony.fhircompliance.repository.DataLineageRecord;
import com.clinicalharmony.fhircompliance.repository.DataLineageRepository;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRecord;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRepository;
import com.clinicalharmony.fhircompliance.repository.MasterPatientRepository;
import com.clinicalharmony.fhircompliance.repository.silver.SilverPatientRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoldWriterTest {

    private static final SilverPatientRow MARIA = new SilverPatientRow(1L, "SEED_EPIC_ADT", "SEED-0001", "MRN100001",
            "Maria", "Garcia", LocalDate.of(1968, 4, 12), "female", null, null, null, null, null, null);
    private static final LineageStep BRONZE = new LineageStep(LineageStep.BRONZE, "2026-11-01T10:00:00Z",
            "ingestion-service (HL7V2)", "a".repeat(64), null, "bronze.raw_messages/5");
    private static final LineageStep SILVER = new LineageStep(LineageStep.SILVER, "2026-11-01T10:00:01Z",
            "Bronze → Silver parse (source_system=EPIC)", "a".repeat(64), null, "silver.patients/1");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GoldFhirResourceRepository fhirResources;
    private MasterPatientRepository masterPatients;
    private DataLineageRepository lineage;
    private GoldWriter writer;

    @BeforeEach
    void setUp() {
        fhirResources = mock(GoldFhirResourceRepository.class);
        masterPatients = mock(MasterPatientRepository.class);
        lineage = mock(DataLineageRepository.class);
        writer = new GoldWriter(fhirResources, masterPatients, lineage, objectMapper,
                Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void firstBuildCreatesProvisionalMasterXrefAndLineage() throws Exception {
        when(masterPatients.findMasterIdForSilverPatient(1L)).thenReturn(Optional.empty());
        when(masterPatients.insertProvisional(MARIA)).thenReturn(42L);

        long masterId = writer.ensureProvisionalMaster(MARIA, List.of(BRONZE, SILVER));

        assertThat(masterId).isEqualTo(42L);
        InOrder order = inOrder(masterPatients);
        order.verify(masterPatients).lockSilverPatient(1L);
        order.verify(masterPatients).insertProvisional(MARIA);
        order.verify(masterPatients).insertSourceLink(42L, 1L);
        verify(masterPatients, never()).refreshProvisionalDemographics(anyLong(), any());

        ArgumentCaptor<DataLineageRecord> captor = ArgumentCaptor.forClass(DataLineageRecord.class);
        verify(lineage).upsert(captor.capture());
        DataLineageRecord row = captor.getValue();
        assertThat(row.sourceSchema()).isEqualTo("silver");
        assertThat(row.sourceTable()).isEqualTo("patients");
        assertThat(row.sourceId()).isEqualTo(1L);
        assertThat(row.targetTable()).isEqualTo("master_patients");
        assertThat(row.targetId()).isEqualTo(42L);
        assertThat(row.transformationStep()).isEqualTo(LineageSource.PMI_PROVISIONAL);

        JsonNode steps = objectMapper.readTree(row.transformationDetails()).get("steps");
        assertThat(steps).hasSize(3);
        assertThat(steps.get(0).get("stage").asText()).isEqualTo("BRONZE");
        assertThat(steps.get(0).get("input_hash").asText()).isEqualTo("a".repeat(64));
        assertThat(steps.get(2).get("stage").asText()).isEqualTo("GOLD");
        assertThat(steps.get(2).get("ref").asText()).isEqualTo("gold.master_patients/42");
        assertThat(steps.get(2).get("input_hash").asText()).isEqualTo(Hashes.sha256Hex(MARIA.toString()));
        assertThat(steps.get(2).get("timestamp").asText()).isEqualTo("2026-11-11T12:00:00Z");
        assertThat(steps.get(2).get("transformer").asText()).contains("provisional 1:1 master");
    }

    @Test
    void laterBuildReusesMasterAndRefreshesDemographics() {
        when(masterPatients.findMasterIdForSilverPatient(1L)).thenReturn(Optional.of(42L));

        long masterId = writer.ensureProvisionalMaster(MARIA, List.of(SILVER));

        assertThat(masterId).isEqualTo(42L);
        verify(masterPatients).refreshProvisionalDemographics(42L, MARIA);
        verify(masterPatients, never()).insertProvisional(any());
        verify(masterPatients, never()).insertSourceLink(anyLong(), anyLong());
        verify(lineage).upsert(any());
    }

    @Test
    void writeResourceUpsertsResourceThenLineageWithGoldStepAppended() throws Exception {
        String json = "{\"resourceType\":\"Condition\",\"id\":\"silver-condition-10\"}";
        when(fhirResources.upsert(any())).thenReturn(500L);

        long goldId = writer.writeResource(new GoldResourceWrite("Condition", "silver-condition-10", json, true,
                "{\"compliant\":true}", 42L, LineageSource.silver("conditions", 10L, LineageSource.FHIR_MAP),
                List.of(SILVER), "fhir-compliance-service ConditionResourceBuilder + HL7 validator (p)", "b".repeat(64)));

        assertThat(goldId).isEqualTo(500L);
        ArgumentCaptor<GoldFhirResourceRecord> record = ArgumentCaptor.forClass(GoldFhirResourceRecord.class);
        verify(fhirResources).upsert(record.capture());
        assertThat(record.getValue().masterPatientId()).isEqualTo(42L);
        assertThat(record.getValue().complianceNotes()).isEqualTo("{\"compliant\":true}");

        ArgumentCaptor<DataLineageRecord> lineageRow = ArgumentCaptor.forClass(DataLineageRecord.class);
        verify(lineage).upsert(lineageRow.capture());
        assertThat(lineageRow.getValue().sourceTable()).isEqualTo("conditions");
        assertThat(lineageRow.getValue().sourceId()).isEqualTo(10L);
        assertThat(lineageRow.getValue().targetTable()).isEqualTo("fhir_resources");
        assertThat(lineageRow.getValue().targetId()).isEqualTo(500L);
        assertThat(lineageRow.getValue().transformationStep()).isEqualTo("FHIR_MAP");

        JsonNode details = objectMapper.readTree(lineageRow.getValue().transformationDetails());
        assertThat(details.get("target").asText()).isEqualTo("Condition/silver-condition-10");
        JsonNode gold = details.get("steps").get(1);
        assertThat(gold.get("stage").asText()).isEqualTo("GOLD");
        assertThat(gold.get("input_hash").asText()).isEqualTo("b".repeat(64));
        assertThat(gold.get("output_hash").asText()).isEqualTo(Hashes.sha256Hex(json));
        assertThat(gold.get("ref").asText()).isEqualTo("gold.fhir_resources/500");
        // Null fields are omitted rather than serialized as null.
        assertThat(details.get("steps").get(0).has("output_hash")).isFalse();
    }

    @Test
    void writeResourceWithNoSourceRowRecordsNullSourceId() {
        when(fhirResources.upsert(any())).thenReturn(9L);

        writer.writeResource(new GoldResourceWrite("Coverage", "cms-coverage-seed-0001", "{}", true, null, null,
                new LineageSource("seed", "19_seed_cms0057_gold.sql", null, LineageSource.SEED_LOAD),
                List.of(), "t", null));

        ArgumentCaptor<DataLineageRecord> lineageRow = ArgumentCaptor.forClass(DataLineageRecord.class);
        verify(lineage).upsert(lineageRow.capture());
        assertThat(lineageRow.getValue().sourceSchema()).isEqualTo("seed");
        assertThat(lineageRow.getValue().sourceId()).isNull();
        assertThat(lineageRow.getValue().transformationStep()).isEqualTo("SEED_LOAD");
    }

    @Test
    void linkDelegatesToRepository() {
        when(fhirResources.linkPatientReferencesToMaster("silver-patient-1", 42L)).thenReturn(3);
        assertThat(writer.linkPatientReferencesToMaster("silver-patient-1", 42L)).isEqualTo(3);
    }
}
