package com.clinicalharmony.fhircompliance.service;

import com.clinicalharmony.fhircompliance.gold.GoldResourceWrite;
import com.clinicalharmony.fhircompliance.gold.GoldWriter;
import com.clinicalharmony.fhircompliance.gold.LineageSource;
import com.clinicalharmony.fhircompliance.gold.LineageStep;
import com.clinicalharmony.fhircompliance.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FhirGenerationServiceTest {

    private FhirBundleGenerator bundleGenerator;
    private UsCoreValidationService validationService;
    private GoldWriter goldWriter;
    private FhirGenerationService generationService;

    private static final PatientRecordRequest MINIMAL_REQUEST = new PatientRecordRequest(
            777L, "John", "Doe", "male", null, null, null, null, null, null, null);

    @BeforeEach
    void setUp() {
        bundleGenerator = mock(FhirBundleGenerator.class);
        validationService = mock(UsCoreValidationService.class);
        goldWriter = mock(GoldWriter.class);
        generationService = new FhirGenerationService(bundleGenerator, validationService, goldWriter, new ObjectMapper());
    }

    @Test
    void persistsEachEntryAsUsCoreCompliantWhenValidationPasses() {
        Map<String, Object> patient = Map.of("resourceType", "Patient", "id", "patient-777", "gender", "male");
        Map<String, Object> bundle = Map.of("resourceType", "Bundle", "type", "collection",
                "entry", List.of(Map.of("resource", patient)));
        when(bundleGenerator.generateBundle(MINIMAL_REQUEST)).thenReturn(bundle);
        when(validationService.checkResource(patient)).thenReturn(
                ResourceValidationResult.pass("Patient", "patient-777", "USCORE_PATIENT_COMPLETENESS", "WARNING"));

        Map<String, Object> result = generationService.generateAndPersist(MINIMAL_REQUEST);

        assertThat(result).isSameAs(bundle);
        ArgumentCaptor<GoldResourceWrite> captor = ArgumentCaptor.forClass(GoldResourceWrite.class);
        verify(goldWriter).writeResource(captor.capture());
        assertThat(captor.getValue().resourceType()).isEqualTo("Patient");
        assertThat(captor.getValue().fhirId()).isEqualTo("patient-777");
        assertThat(captor.getValue().usCoreCompliant()).isTrue();
        assertThat(captor.getValue().resourceJson()).contains("\"gender\":\"male\"");
        // Phase 5's caller-supplied path: API → GOLD lineage, no Silver source, no master patient.
        assertThat(captor.getValue().complianceNotes()).isNull();
        assertThat(captor.getValue().masterPatientId()).isNull();
        assertThat(captor.getValue().source())
                .isEqualTo(new LineageSource("api", "POST /api/fhir-compliance/generate", null, LineageSource.API_GENERATE));
        assertThat(captor.getValue().upstreamSteps()).singleElement().satisfies(step -> {
            assertThat(step.stage()).isEqualTo(LineageStep.API);
            assertThat(step.inputHash()).hasSize(64).isEqualTo(captor.getValue().goldInputHash());
        });
    }

    @Test
    void marksResourceNonCompliantWhenValidationFails() {
        Map<String, Object> patient = Map.of("resourceType", "Patient", "id", "patient-777");
        Map<String, Object> bundle = Map.of("resourceType", "Bundle", "type", "collection",
                "entry", List.of(Map.of("resource", patient)));
        when(bundleGenerator.generateBundle(MINIMAL_REQUEST)).thenReturn(bundle);
        when(validationService.checkResource(patient)).thenReturn(
                ResourceValidationResult.fail("Patient", "patient-777", "USCORE_PATIENT_COMPLETENESS", "WARNING",
                        List.of("name"), "missing name"));

        generationService.generateAndPersist(MINIMAL_REQUEST);

        ArgumentCaptor<GoldResourceWrite> captor = ArgumentCaptor.forClass(GoldResourceWrite.class);
        verify(goldWriter).writeResource(captor.capture());
        assertThat(captor.getValue().usCoreCompliant()).isFalse();
    }

    @Test
    void treatsSkippedValidationAsCompliant() {
        Map<String, Object> encounter = Map.of("resourceType", "Encounter", "id", "encounter-1");
        Map<String, Object> bundle = Map.of("resourceType", "Bundle", "type", "collection",
                "entry", List.of(Map.of("resource", encounter)));
        when(bundleGenerator.generateBundle(MINIMAL_REQUEST)).thenReturn(bundle);
        when(validationService.checkResource(encounter)).thenReturn(null);

        generationService.generateAndPersist(MINIMAL_REQUEST);

        ArgumentCaptor<GoldResourceWrite> captor = ArgumentCaptor.forClass(GoldResourceWrite.class);
        verify(goldWriter).writeResource(captor.capture());
        assertThat(captor.getValue().usCoreCompliant()).isTrue();
    }
}
