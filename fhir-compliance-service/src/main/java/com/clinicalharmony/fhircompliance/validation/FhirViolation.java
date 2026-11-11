package com.clinicalharmony.fhircompliance.validation;

/**
 * One finding from the HL7 validator, shaped for a human debugging a resource.
 *
 * @param severity     ERROR / WARNING / INFORMATION (FATAL is folded into ERROR)
 * @param location     FHIRPath expression to the offending element, e.g. {@code Patient.name[0].family}
 * @param message      the validator's own message text
 * @param rule         the validator's message id or constraint key (e.g. {@code us-core-6},
 *                     {@code Validation_VAL_Profile_Minimum}) — stable across runs, unlike the text
 * @param suggestedFix a plain-English hint at how to fix it, or null when we have none
 */
public record FhirViolation(
        String severity,
        String location,
        String message,
        String rule,
        String suggestedFix
) {
}
