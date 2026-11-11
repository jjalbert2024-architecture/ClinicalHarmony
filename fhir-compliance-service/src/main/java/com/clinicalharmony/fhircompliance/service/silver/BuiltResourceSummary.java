package com.clinicalharmony.fhircompliance.service.silver;

import com.clinicalharmony.fhircompliance.validation.FhirViolation;

import java.util.List;

/** What happened to one FHIR resource built from Silver: where it landed in Gold and how it validated. */
public record BuiltResourceSummary(
        String resourceType,
        String fhirId,
        long goldId,
        boolean usCoreCompliant,
        int errorCount,
        int warningCount,
        List<FhirViolation> errors
) {
}
