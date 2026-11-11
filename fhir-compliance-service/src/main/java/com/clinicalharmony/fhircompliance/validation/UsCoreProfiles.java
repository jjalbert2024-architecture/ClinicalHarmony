package com.clinicalharmony.fhircompliance.validation;

import java.util.Map;
import java.util.Optional;

/**
 * Canonical URLs of the US Core 6.1.0 profiles this service validates against, and the default
 * profile per resource type used when a submitted resource declares no {@code meta.profile}.
 */
public final class UsCoreProfiles {

    private static final String BASE = "http://hl7.org/fhir/us/core/StructureDefinition/";

    public static final String PATIENT = BASE + "us-core-patient";
    public static final String CONDITION_PROBLEMS = BASE + "us-core-condition-problems-health-concerns";
    public static final String OBSERVATION_LAB = BASE + "us-core-observation-lab";
    public static final String OBSERVATION_CLINICAL_RESULT = BASE + "us-core-observation-clinical-result";
    public static final String MEDICATION_REQUEST = BASE + "us-core-medicationrequest";
    public static final String ALLERGY_INTOLERANCE = BASE + "us-core-allergyintolerance";
    public static final String ENCOUNTER = BASE + "us-core-encounter";
    public static final String DIAGNOSTIC_REPORT_LAB = BASE + "us-core-diagnosticreport-lab";

    private static final Map<String, String> DEFAULT_BY_TYPE = Map.of(
            "Patient", PATIENT,
            "Condition", CONDITION_PROBLEMS,
            "Observation", OBSERVATION_LAB,
            "MedicationRequest", MEDICATION_REQUEST,
            "AllergyIntolerance", ALLERGY_INTOLERANCE,
            "Encounter", ENCOUNTER,
            "DiagnosticReport", DIAGNOSTIC_REPORT_LAB
    );

    private UsCoreProfiles() {
    }

    public static Optional<String> defaultFor(String resourceType) {
        return Optional.ofNullable(DEFAULT_BY_TYPE.get(resourceType));
    }
}
