package com.clinicalharmony.fhircompliance.validation;

import java.util.Map;
import java.util.Optional;

/**
 * Canonical URLs of the profiles this service validates against, and the default profile per
 * resource type used when a submitted resource declares no {@code meta.profile}.
 *
 * <p>Mostly US Core 6.1.0. The two CMS-0057-F types US Core doesn't profile come from Da Vinci:
 * MedicationKnowledge → US Drug Formulary 2.1.0 {@code usdf-FormularyDrug}, MeasureReport → DEQM
 * 5.0.0 {@code indv-measurereport-deqm} (individual reports — the only kind Gold holds today).
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
    public static final String COVERAGE = BASE + "us-core-coverage";
    public static final String PRACTITIONER_ROLE = BASE + "us-core-practitionerrole";
    public static final String PRACTITIONER = BASE + "us-core-practitioner";
    public static final String ORGANIZATION = BASE + "us-core-organization";
    public static final String IMPLANTABLE_DEVICE = BASE + "us-core-implantable-device";
    public static final String FORMULARY_DRUG =
            "http://hl7.org/fhir/us/davinci-drug-formulary/StructureDefinition/usdf-FormularyDrug";
    public static final String DEQM_INDIVIDUAL_MEASURE_REPORT =
            "http://hl7.org/fhir/us/davinci-deqm/StructureDefinition/indv-measurereport-deqm";

    private static final Map<String, String> DEFAULT_BY_TYPE = Map.ofEntries(
            Map.entry("Patient", PATIENT),
            Map.entry("Condition", CONDITION_PROBLEMS),
            Map.entry("Observation", OBSERVATION_LAB),
            Map.entry("MedicationRequest", MEDICATION_REQUEST),
            Map.entry("AllergyIntolerance", ALLERGY_INTOLERANCE),
            Map.entry("Encounter", ENCOUNTER),
            Map.entry("DiagnosticReport", DIAGNOSTIC_REPORT_LAB),
            Map.entry("Coverage", COVERAGE),
            Map.entry("PractitionerRole", PRACTITIONER_ROLE),
            Map.entry("Practitioner", PRACTITIONER),
            Map.entry("Organization", ORGANIZATION),
            Map.entry("Device", IMPLANTABLE_DEVICE),
            Map.entry("MedicationKnowledge", FORMULARY_DRUG),
            Map.entry("MeasureReport", DEQM_INDIVIDUAL_MEASURE_REPORT)
    );

    private UsCoreProfiles() {
    }

    public static Optional<String> defaultFor(String resourceType) {
        return Optional.ofNullable(DEFAULT_BY_TYPE.get(resourceType));
    }
}
