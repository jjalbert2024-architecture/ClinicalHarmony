package com.clinicalharmony.fhircompliance.service.silver;

import java.util.List;

/** Result of building every FHIR resource for one silver.patients row. */
public record SilverPatientBuildResult(
        long silverPatientId,
        String patientFhirId,
        int resourceCount,
        int compliantCount,
        List<BuiltResourceSummary> resources
) {
}
