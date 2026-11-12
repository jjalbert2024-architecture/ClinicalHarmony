package com.clinicalharmony.fhircompliance.service.silver;

import java.util.List;

/**
 * Result of building every FHIR resource for one silver.patients row.
 *
 * @param masterPatientId the gold.master_patients row the resources are linked to — a PROVISIONAL
 *                        1:1 master until Phase 6's patient-index-service matches/merges it
 */
public record SilverPatientBuildResult(
        long silverPatientId,
        String patientFhirId,
        long masterPatientId,
        int resourceCount,
        int compliantCount,
        List<BuiltResourceSummary> resources
) {
}
