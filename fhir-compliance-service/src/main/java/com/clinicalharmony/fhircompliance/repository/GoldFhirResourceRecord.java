package com.clinicalharmony.fhircompliance.repository;

/**
 * Mirrors a row of gold.fhir_resources. {@code masterPatientId} is left null until
 * patient-index-service (Phase 6) exists to populate gold.master_patients — the column is
 * nullable for exactly this reason.
 *
 * @param complianceNotes JSON text for the {@code compliance_notes} JSONB column (migration 16):
 *                        the HL7 validator's findings for this resource. Null for resources from
 *                        the Phase 5 structural-check path, which doesn't produce them.
 */
public record GoldFhirResourceRecord(
        String resourceType,
        String fhirId,
        String resourceJson,
        boolean usCoreCompliant,
        String complianceNotes
) {
    public GoldFhirResourceRecord(String resourceType, String fhirId, String resourceJson, boolean usCoreCompliant) {
        this(resourceType, fhirId, resourceJson, usCoreCompliant, null);
    }
}
