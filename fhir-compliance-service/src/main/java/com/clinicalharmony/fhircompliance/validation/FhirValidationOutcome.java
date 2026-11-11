package com.clinicalharmony.fhircompliance.validation;

import java.util.List;

/**
 * Result of validating one FHIR resource against US Core.
 *
 * @param compliant  true iff there are no ERROR-severity violations (warnings don't fail a resource)
 * @param profile    the US Core profile actually validated against
 */
public record FhirValidationOutcome(
        String resourceType,
        String resourceId,
        String profile,
        boolean compliant,
        int errorCount,
        int warningCount,
        List<FhirViolation> violations
) {
}
