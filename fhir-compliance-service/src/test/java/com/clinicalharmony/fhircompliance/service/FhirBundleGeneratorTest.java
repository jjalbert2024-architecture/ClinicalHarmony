package com.clinicalharmony.fhircompliance.service;

import com.clinicalharmony.fhircompliance.web.dto.PatientRecordRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies both the shape of generated resources and — via a real UsCoreValidationService wired
 * with the same 7 profiles docs/db-init/14_seed_fhir_compliance_rules.sql seeds — that every
 * resource this generator produces actually passes US Core structural validation. This is the
 * regression guard against the generator and the profile field lists silently drifting apart.
 */
class FhirBundleGeneratorTest {

    private final FhirBundleGenerator generator = new FhirBundleGenerator();
    private UsCoreValidationService validationService;

    @BeforeEach
    void setUp() {
        UsCoreRuleLoader ruleLoader = mock(UsCoreRuleLoader.class);
        when(ruleLoader.loadActiveProfiles()).thenReturn(List.of(
                new UsCoreProfile("USCORE_PATIENT_COMPLETENESS", "Patient", "WARNING",
                        List.of("id", "identifier", "name", "gender", "birthDate"), List.of()),
                new UsCoreProfile("USCORE_CONDITION_COMPLETENESS", "Condition", "WARNING",
                        List.of("id", "clinicalStatus", "code", "subject"), List.of()),
                new UsCoreProfile("USCORE_OBSERVATION_COMPLETENESS", "Observation", "WARNING",
                        List.of("id", "status", "code", "subject"), List.of()),
                new UsCoreProfile("USCORE_MEDICATIONREQUEST_COMPLETENESS", "MedicationRequest", "WARNING",
                        List.of("id", "status", "intent", "subject"),
                        List.of(List.of("medicationCodeableConcept", "medicationReference"))),
                new UsCoreProfile("USCORE_ALLERGYINTOLERANCE_COMPLETENESS", "AllergyIntolerance", "WARNING",
                        List.of("id", "patient", "code"), List.of()),
                new UsCoreProfile("USCORE_ENCOUNTER_COMPLETENESS", "Encounter", "WARNING",
                        List.of("id", "status", "class", "subject"), List.of()),
                new UsCoreProfile("USCORE_DIAGNOSTICREPORT_COMPLETENESS", "DiagnosticReport", "WARNING",
                        List.of("id", "status", "code", "subject"), List.of())
        ));
        validationService = new UsCoreValidationService(ruleLoader, mock(FhirValidationReportBuilder.class));
    }

    private PatientRecordRequest fullRequest() {
        return new PatientRecordRequest(
                777L, "John", "Doe", "male", LocalDate.of(1980, 1, 1),
                List.of(new PatientRecordRequest.ConditionItem(1L, "ICD-10", "E11.9", "Type 2 diabetes", LocalDate.of(2026, 1, 1))),
                List.of(new PatientRecordRequest.ObservationItem(2L, "2093-3", "Cholesterol", 180.0, "mg/dL", LocalDateTime.of(2026, 6, 1, 10, 0))),
                List.of(new PatientRecordRequest.MedicationItem(3L, "6809", "Metformin", LocalDate.of(2026, 1, 1))),
                List.of(new PatientRecordRequest.AllergyItem(4L, "7980", "Penicillin")),
                List.of(new PatientRecordRequest.EncounterItem(5L, "finished", "AMB", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1))),
                List.of(new PatientRecordRequest.DiagnosticReportItem(6L, "24323-8", "Comprehensive metabolic panel", "final", LocalDateTime.of(2026, 6, 1, 12, 0)))
        );
    }

    @Test
    void generatesOneEntryPerResourceType() {
        Map<String, Object> bundle = generator.generateBundle(fullRequest());

        assertThat(bundle.get("resourceType")).isEqualTo("Bundle");
        @SuppressWarnings("unchecked")
        List<Object> entries = (List<Object>) bundle.get("entry");
        assertThat(entries).hasSize(7);
    }

    @Test
    void everyGeneratedResourcePassesItsOwnUsCoreProfile() {
        Map<String, Object> bundle = generator.generateBundle(fullRequest());

        @SuppressWarnings("unchecked")
        List<Object> entries = (List<Object>) bundle.get("entry");
        for (Object entryObj : entries) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) entryObj;
            @SuppressWarnings("unchecked")
            Map<String, Object> resource = (Map<String, Object>) entry.get("resource");

            ResourceValidationResult result = validationService.checkResource(resource);

            assertThat(result).as("resourceType=%s should have a matching profile", resource.get("resourceType")).isNotNull();
            assertThat(result.valid())
                    .as("%s/%s should be US Core complete but is missing %s",
                            resource.get("resourceType"), resource.get("id"),
                            result.missingFields())
                    .isTrue();
        }
    }

    @Test
    void patientResourceUsesCallerAssignedPatientIdAsIdentifier() {
        Map<String, Object> bundle = generator.generateBundle(fullRequest());

        @SuppressWarnings("unchecked")
        List<Object> entries = (List<Object>) bundle.get("entry");
        @SuppressWarnings("unchecked")
        Map<String, Object> patient = (Map<String, Object>) ((Map<String, Object>) entries.get(0)).get("resource");

        assertThat(patient.get("resourceType")).isEqualTo("Patient");
        assertThat(patient.get("id")).isEqualTo("patient-777");
        assertThat(patient.get("gender")).isEqualTo("male");
    }

    @Test
    void conditionCodeUsesRealIcd10CanonicalSystemUri() {
        Map<String, Object> bundle = generator.generateBundle(fullRequest());

        @SuppressWarnings("unchecked")
        List<Object> entries = (List<Object>) bundle.get("entry");
        @SuppressWarnings("unchecked")
        Map<String, Object> condition = (Map<String, Object>) ((Map<String, Object>) entries.get(1)).get("resource");
        @SuppressWarnings("unchecked")
        Map<String, Object> code = (Map<String, Object>) condition.get("code");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> coding = (List<Map<String, Object>>) code.get("coding");

        assertThat(coding.get(0).get("system")).isEqualTo("http://hl7.org/fhir/sid/icd-10-cm");
        assertThat(coding.get(0).get("code")).isEqualTo("E11.9");
    }
}
