package com.clinicalharmony.fhircompliance.service;

import com.clinicalharmony.fhircompliance.web.dto.FhirValidationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsCoreValidationServiceTest {

    private UsCoreRuleLoader ruleLoader;
    private FhirValidationReportBuilder reportBuilder;
    private UsCoreValidationService service;

    private static final UsCoreProfile PATIENT_PROFILE = new UsCoreProfile(
            "USCORE_PATIENT_COMPLETENESS", "Patient", "WARNING",
            List.of("id", "name", "gender", "birthDate"), List.of());

    private static final UsCoreProfile MEDICATION_REQUEST_PROFILE = new UsCoreProfile(
            "USCORE_MEDICATIONREQUEST_COMPLETENESS", "MedicationRequest", "WARNING",
            List.of("id", "status", "intent", "subject"),
            List.of(List.of("medicationCodeableConcept", "medicationReference")));

    @BeforeEach
    void setUp() {
        ruleLoader = mock(UsCoreRuleLoader.class);
        reportBuilder = mock(FhirValidationReportBuilder.class);
        service = new UsCoreValidationService(ruleLoader, reportBuilder);
    }

    @Test
    void resourceWithAllRequiredFieldsPasses() {
        when(ruleLoader.loadActiveProfiles()).thenReturn(List.of(PATIENT_PROFILE));
        Map<String, Object> patient = Map.of(
                "resourceType", "Patient", "id", "p1", "name", List.of(Map.of("family", "Doe")),
                "gender", "male", "birthDate", "1980-01-01");

        ResourceValidationResult result = service.checkResource(patient);

        assertThat(result.valid()).isTrue();
        assertThat(result.missingFields()).isEmpty();
    }

    @Test
    void resourceMissingRequiredFieldFails() {
        when(ruleLoader.loadActiveProfiles()).thenReturn(List.of(PATIENT_PROFILE));
        Map<String, Object> patient = Map.of("resourceType", "Patient", "id", "p1", "gender", "male");

        ResourceValidationResult result = service.checkResource(patient);

        assertThat(result.valid()).isFalse();
        assertThat(result.missingFields()).containsExactlyInAnyOrder("name", "birthDate");
        assertThat(result.reason()).contains("Patient/p1");
    }

    @Test
    void resourceTypeWithNoActiveProfileIsSkippedNotFailed() {
        when(ruleLoader.loadActiveProfiles()).thenReturn(List.of(PATIENT_PROFILE));
        Map<String, Object> encounter = Map.of("resourceType", "Encounter", "id", "e1");

        ResourceValidationResult result = service.checkResource(encounter);

        assertThat(result).isNull();
    }

    @Test
    void requiredAnyOfSatisfiedByEitherChoiceTypeField() {
        when(ruleLoader.loadActiveProfiles()).thenReturn(List.of(MEDICATION_REQUEST_PROFILE));
        Map<String, Object> medicationRequest = Map.of(
                "resourceType", "MedicationRequest", "id", "m1", "status", "active", "intent", "order",
                "subject", Map.of("reference", "Patient/p1"),
                "medicationReference", Map.of("reference", "Medication/123"));

        ResourceValidationResult result = service.checkResource(medicationRequest);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void requiredAnyOfFailsWhenNeitherChoiceTypeFieldPresent() {
        when(ruleLoader.loadActiveProfiles()).thenReturn(List.of(MEDICATION_REQUEST_PROFILE));
        Map<String, Object> medicationRequest = Map.of(
                "resourceType", "MedicationRequest", "id", "m1", "status", "active", "intent", "order",
                "subject", Map.of("reference", "Patient/p1"));

        ResourceValidationResult result = service.checkResource(medicationRequest);

        assertThat(result.valid()).isFalse();
        assertThat(result.missingFields()).contains("medicationCodeableConcept|medicationReference");
    }

    @Test
    void validatePersistsOnlyFailuresAndReportsAllValidCorrectly() {
        when(ruleLoader.loadActiveProfiles()).thenReturn(List.of(PATIENT_PROFILE));
        when(reportBuilder.buildAndPersist(any(), any())).thenReturn(List.of(42L));
        Map<String, Object> invalidPatient = Map.of("resourceType", "Patient", "id", "p1");

        FhirValidationReport report = service.validate(new FhirValidationRequest(777L, List.of(invalidPatient)));

        assertThat(report.allValid()).isFalse();
        assertThat(report.validationReportIds()).containsExactly(42L);
        verify(reportBuilder).buildAndPersist(any(), any());
    }
}
