package com.clinicalharmony.fhircompliance.service;

import java.util.List;

public record FhirValidationReport(
        Long patientId,
        boolean allValid,
        List<ResourceValidationResult> resourceResults,
        List<Long> validationReportIds
) {
}
