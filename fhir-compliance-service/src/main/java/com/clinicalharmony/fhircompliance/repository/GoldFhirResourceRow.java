package com.clinicalharmony.fhircompliance.repository;

import java.time.OffsetDateTime;

/** A gold.fhir_resources row as read back (CMS-0057 read endpoints, seed revalidation). */
public record GoldFhirResourceRow(
        long id,
        String resourceType,
        String fhirId,
        String resourceJson,
        boolean usCoreCompliant,
        String complianceNotes,
        Long masterPatientId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
