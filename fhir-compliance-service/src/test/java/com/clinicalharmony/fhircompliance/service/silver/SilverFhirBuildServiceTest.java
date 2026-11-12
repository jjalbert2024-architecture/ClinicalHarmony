package com.clinicalharmony.fhircompliance.service.silver;

import ca.uhn.fhir.context.FhirContext;
import com.clinicalharmony.fhircompliance.gold.GoldResourceWrite;
import com.clinicalharmony.fhircompliance.gold.GoldWriter;
import com.clinicalharmony.fhircompliance.gold.LineageSource;
import com.clinicalharmony.fhircompliance.gold.LineageStep;
import com.clinicalharmony.fhircompliance.gold.SilverLineage;
import com.clinicalharmony.fhircompliance.repository.silver.SilverClinicalRepository;
import com.clinicalharmony.fhircompliance.repository.silver.SilverConditionRow;
import com.clinicalharmony.fhircompliance.repository.silver.SilverMedicationRow;
import com.clinicalharmony.fhircompliance.repository.silver.SilverObservationRow;
import com.clinicalharmony.fhircompliance.repository.silver.SilverPatientRow;
import com.clinicalharmony.fhircompliance.repository.silver.SilverTable;
import com.clinicalharmony.fhircompliance.validation.UsCoreValidatorFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mocks the DB edges (Silver reads, GoldWriter, lineage lookups, transactions) but uses the real
 * builders and the real HL7 validator, so the compliance verdicts asserted here are genuine.
 */
class SilverFhirBuildServiceTest {

    private SilverClinicalRepository silverRepository;
    private GoldWriter goldWriter;
    private SilverLineage silverLineage;
    private TransactionTemplate transactionTemplate;
    private SilverFhirBuildService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        silverRepository = mock(SilverClinicalRepository.class);
        goldWriter = mock(GoldWriter.class);
        silverLineage = mock(SilverLineage.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        when(goldWriter.writeResource(any())).thenReturn(100L, 101L, 102L, 103L, 104L, 105L);
        when(goldWriter.ensureProvisionalMaster(any(), any())).thenReturn(7L);
        when(silverLineage.stepsFor(any(), anyLong())).thenAnswer(inv -> List.of(new LineageStep(LineageStep.SILVER,
                "2026-11-01T00:00:00Z", "Silver load with no Bronze link (source_system=SEED_EPIC_ADT)", null, null,
                ((SilverTable) inv.getArgument(0)).ref(inv.getArgument(1)))));

        service = new SilverFhirBuildService(silverRepository, new PatientResourceBuilder(),
                new ConditionResourceBuilder(), new ObservationResourceBuilder(),
                new MedicationRequestResourceBuilder(), UsCoreValidatorFixture.validator(), goldWriter, silverLineage,
                FhirContext.forR4Cached(), objectMapper, transactionTemplate,
                Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC));
    }

    private void stubCompliantPatient(long id) {
        when(silverRepository.findPatient(id)).thenReturn(Optional.of(new SilverPatientRow(id, "SEED_EPIC_ADT",
                "SEED-000" + id, "MRN" + id, "Maria", "Garcia", LocalDate.of(1968, 4, 12), "female",
                null, null, null, null, null, null)));
        when(silverRepository.findConditions(id)).thenReturn(List.of(new SilverConditionRow(10L, id, "ICD-10",
                "E11.9", "Type 2 diabetes mellitus without complications", "active", null, LocalDate.of(2015, 6, 3))));
        when(silverRepository.findObservations(id)).thenReturn(List.of(new SilverObservationRow(20L, id, "4548-4",
                "Hemoglobin A1c", new BigDecimal("7.2"), null, "%", "final", OffsetDateTime.parse("2024-03-10T08:30:00Z"))));
        when(silverRepository.findMedications(id)).thenReturn(List.of(new SilverMedicationRow(30L, id, "6809",
                "Metformin", "500 mg BID", "active", LocalDate.of(2015, 6, 3), null)));
    }

    @Test
    void buildsValidatesAndPersistsEveryResourceForAPatient() throws Exception {
        stubCompliantPatient(1L);

        SilverPatientBuildResult result = service.buildPatient(1L);

        assertThat(result.silverPatientId()).isEqualTo(1L);
        assertThat(result.patientFhirId()).isEqualTo("silver-patient-1");
        assertThat(result.masterPatientId()).isEqualTo(7L);
        assertThat(result.resourceCount()).isEqualTo(4);
        assertThat(result.compliantCount()).isEqualTo(4);
        assertThat(result.resources()).extracting(BuiltResourceSummary::resourceType)
                .containsExactly("Patient", "Condition", "Observation", "MedicationRequest");
        assertThat(result.resources()).extracting(BuiltResourceSummary::goldId)
                .containsExactly(100L, 101L, 102L, 103L);

        ArgumentCaptor<GoldResourceWrite> captor = ArgumentCaptor.forClass(GoldResourceWrite.class);
        verify(goldWriter, times(4)).writeResource(captor.capture());
        GoldResourceWrite patientRecord = captor.getAllValues().get(0);
        assertThat(patientRecord.fhirId()).isEqualTo("silver-patient-1");
        assertThat(patientRecord.usCoreCompliant()).isTrue();
        assertThat(captor.getAllValues()).extracting(GoldResourceWrite::masterPatientId).containsOnly(7L);
        assertThat(objectMapper.readTree(patientRecord.resourceJson()).get("resourceType").asText()).isEqualTo("Patient");

        JsonNode notes = objectMapper.readTree(patientRecord.complianceNotes());
        assertThat(notes.get("compliant").asBoolean()).isTrue();
        assertThat(notes.get("errorCount").asInt()).isZero();
        assertThat(notes.get("profile").asText()).endsWith("us-core-patient");
        assertThat(notes.get("validatedAt").asText()).isEqualTo("2026-11-11T12:00:00Z");
        assertThat(notes.get("validator").asText()).contains("US Core 6.1.0");
        assertThat(notes.get("violations").isArray()).isTrue();

        // Lineage: each resource traces to its own Silver row, with that row's hash as the Gold input.
        assertThat(captor.getAllValues()).extracting(GoldResourceWrite::source).containsExactly(
                LineageSource.silver("patients", 1L, LineageSource.FHIR_MAP),
                LineageSource.silver("conditions", 10L, LineageSource.FHIR_MAP),
                LineageSource.silver("observations", 20L, LineageSource.FHIR_MAP),
                LineageSource.silver("medications", 30L, LineageSource.FHIR_MAP));
        GoldResourceWrite condition = captor.getAllValues().get(1);
        assertThat(condition.upstreamSteps()).extracting(LineageStep::ref).containsExactly("silver.conditions/10");
        assertThat(condition.goldInputHash()).hasSize(64);
        assertThat(condition.goldTransformer()).contains("ConditionResourceBuilder", "us-core-condition");

        verify(goldWriter).ensureProvisionalMaster(any(), any());
        verify(goldWriter).linkPatientReferencesToMaster("silver-patient-1", 7L);
        verify(transactionTemplate).execute(any());
    }

    @Test
    void nonCompliantResourceIsStillPersistedWithReasons() throws Exception {
        when(silverRepository.findPatient(3L)).thenReturn(Optional.of(new SilverPatientRow(3L, "SEED_CLAIMS_CSV",
                "SEED-0003", null, "", "", LocalDate.of(1990, 1, 1), "unknown", null, null, null, null, null, null)));
        when(silverRepository.findConditions(3L)).thenReturn(List.of(new SilverConditionRow(11L, 3L, "ICD-10",
                "", null, "active", null, LocalDate.of(2024, 5, 1))));
        when(silverRepository.findObservations(3L)).thenReturn(List.of());
        when(silverRepository.findMedications(3L)).thenReturn(List.of());

        SilverPatientBuildResult result = service.buildPatient(3L);

        assertThat(result.resourceCount()).isEqualTo(2);
        assertThat(result.compliantCount()).isZero();
        BuiltResourceSummary patient = result.resources().get(0);
        assertThat(patient.usCoreCompliant()).isFalse();
        assertThat(patient.errors()).extracting(v -> v.location()).containsExactly("Patient.name");

        ArgumentCaptor<GoldResourceWrite> captor = ArgumentCaptor.forClass(GoldResourceWrite.class);
        verify(goldWriter, times(2)).writeResource(captor.capture());
        GoldResourceWrite condition = captor.getAllValues().get(1);
        assertThat(condition.usCoreCompliant()).isFalse();
        JsonNode notes = objectMapper.readTree(condition.complianceNotes());
        assertThat(notes.get("compliant").asBoolean()).isFalse();
        assertThat(notes.get("violations").toString()).contains("Condition.code", "suggestedFix");
    }

    @Test
    void unknownSilverPatientThrowsAndWritesNothing() {
        when(silverRepository.findPatient(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buildPatient(99L))
                .isInstanceOf(SilverPatientNotFoundException.class)
                .hasMessageContaining("99");
        verify(goldWriter, never()).writeResource(any());
        verify(goldWriter, never()).ensureProvisionalMaster(any(), any());
    }

    @Test
    void buildAllRunsOneTransactionPerPatient() {
        stubCompliantPatient(1L);
        stubCompliantPatient(2L);
        when(silverRepository.findAllPatientIds()).thenReturn(List.of(1L, 2L));

        List<SilverPatientBuildResult> results = service.buildAllPatients();

        assertThat(results).extracting(SilverPatientBuildResult::silverPatientId).containsExactly(1L, 2L);
        verify(transactionTemplate, times(2)).execute(any());
    }
}
