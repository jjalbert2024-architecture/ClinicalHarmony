package com.clinicalharmony.fhircompliance.service;

import java.util.List;

/** Outcome of checking one submitted FHIR resource against its matching UsCoreProfile. */
public record ResourceValidationResult(
        String resourceType,
        String resourceId,
        boolean valid,
        String ruleCode,
        String severity,
        List<String> missingFields,
        String reason
) {

    public static ResourceValidationResult pass(String resourceType, String resourceId, String ruleCode, String severity) {
        return new ResourceValidationResult(resourceType, resourceId, true, ruleCode, severity, List.of(), null);
    }

    public static ResourceValidationResult fail(String resourceType, String resourceId, String ruleCode, String severity,
                                                 List<String> missingFields, String reason) {
        return new ResourceValidationResult(resourceType, resourceId, false, ruleCode, severity, missingFields, reason);
    }
}
