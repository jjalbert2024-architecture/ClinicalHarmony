package com.clinicalharmony.fhircompliance.service.silver;

/**
 * Deterministic FHIR logical ids for resources built from Silver rows: the same Silver row always
 * maps to the same id, so rebuilding upserts the existing gold.fhir_resources row (unique on
 * resource_type + fhir_id) instead of piling up duplicates. Distinct prefix from Phase 5's
 * {@code patient-<n>} ids (caller-assigned, from /api/fhir-compliance/generate) so the two
 * sources can never collide.
 */
public final class FhirIds {

    private FhirIds() {
    }

    public static String patient(long silverId) {
        return "silver-patient-" + silverId;
    }

    public static String condition(long silverId) {
        return "silver-condition-" + silverId;
    }

    public static String observation(long silverId) {
        return "silver-observation-" + silverId;
    }

    public static String medicationRequest(long silverId) {
        return "silver-medication-" + silverId;
    }
}
