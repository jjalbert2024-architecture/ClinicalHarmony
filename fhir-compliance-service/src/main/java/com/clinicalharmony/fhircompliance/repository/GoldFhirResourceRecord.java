package com.clinicalharmony.fhircompliance.repository;

/**
 * Mirrors a row of gold.fhir_resources. {@code masterPatientId} is left null until
 * patient-index-service (Phase 6) exists to populate gold.master_patients — the column is
 * nullable for exactly this reason.
 */
public record GoldFhirResourceRecord(
        String resourceType,
        String fhirId,
        String resourceJson,
        boolean usCoreCompliant
) {
}
