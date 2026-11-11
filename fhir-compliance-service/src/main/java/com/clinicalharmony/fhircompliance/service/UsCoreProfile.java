package com.clinicalharmony.fhircompliance.service;

import java.util.List;

/**
 * A parsed USCORE_*_COMPLETENESS rule: which FHIR resourceType it applies to, the flat list of
 * fields that must be present, and any "one-of" groups for FHIR choice-type elements (e.g.
 * MedicationRequest's {@code medication[x]}, satisfied by either medicationCodeableConcept or
 * medicationReference).
 */
public record UsCoreProfile(
        String ruleCode,
        String resourceType,
        String severity,
        List<String> requiredFields,
        List<List<String>> requiredAnyOf
) {
}
