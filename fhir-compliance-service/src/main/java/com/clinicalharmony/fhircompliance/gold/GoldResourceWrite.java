package com.clinicalharmony.fhircompliance.gold;

import java.util.List;

/**
 * Everything GoldWriter needs to persist one FHIR resource and its lineage.
 *
 * @param complianceNotes  JSON text for gold.fhir_resources.compliance_notes, or null (Phase 5 path)
 * @param masterPatientId  gold.master_patients id, or null when the resource isn't patient-linked
 *                         (or its patient has no master yet)
 * @param upstreamSteps    lineage steps before Gold (BRONZE/SILVER, SEED or API); GoldWriter appends
 *                         the GOLD step itself, since only it knows the Gold row id
 * @param goldTransformer  what produced the Gold resource (builder + validator/profile)
 * @param goldInputHash    SHA-256 of what the Gold stage consumed (e.g. the Silver row)
 */
public record GoldResourceWrite(
        String resourceType,
        String fhirId,
        String resourceJson,
        boolean usCoreCompliant,
        String complianceNotes,
        Long masterPatientId,
        LineageSource source,
        List<LineageStep> upstreamSteps,
        String goldTransformer,
        String goldInputHash
) {
}
