package com.clinicalharmony.ontology.validator;

/**
 * Common result shape returned by all 4 code validators. {@code display} is the primary
 * human-readable label (ICD-10 description, LOINC long common name, RxNorm drug name, SNOMED
 * term); {@code category} is a secondary classifier (ICD-10 category, LOINC scale type, RxNorm
 * term type, SNOMED semantic tag).
 */
public record ValidationOutcome(
        boolean valid,
        String code,
        String display,
        String category,
        String reason
) {

    public static ValidationOutcome valid(String code, String display, String category) {
        return new ValidationOutcome(true, code, display, category, null);
    }

    public static ValidationOutcome invalid(String code, String reason) {
        return new ValidationOutcome(false, code, null, null, reason);
    }
}
