package com.clinicalharmony.fhircompliance.repository;

/**
 * A row to upsert into gold.fhir_resources. Written only through GoldWriter.
 *
 * @param complianceNotes JSON text for the {@code compliance_notes} JSONB column (migration 16):
 *                        the HL7 validator's findings for this resource. Null for resources from
 *                        the Phase 5 structural-check path, which doesn't produce them.
 * @param masterPatientId gold.master_patients id (a provisional 1:1 master until Phase 6's PMI), or
 *                        null — on upsert a null never clears an existing link
 */
public record GoldFhirResourceRecord(
        String resourceType,
        String fhirId,
        String resourceJson,
        boolean usCoreCompliant,
        String complianceNotes,
        Long masterPatientId
) {
}
