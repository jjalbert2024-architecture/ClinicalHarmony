package com.clinicalharmony.gateway.downstream;

import java.util.Set;

/**
 * Which service evaluates each {@code ontology.clinical_rules} row — and so which one the gateway
 * asks to toggle it. Only rules-engine-service ({@code PUT /api/rules/{code}/toggle}) and
 * fhir-compliance-service ({@code PUT /api/fhir-compliance/rules/{code}/toggle}) expose a toggle;
 * the CODE-* rules (ontology-service), PMI_MATCH (patient-index-service) and the Phase 1 rules
 * nothing evaluates yet are listed but not toggleable through the gateway.
 */
public final class RuleOwnership {

    /** rules-engine-service's 7 built-in RuleEvaluators (rule codes from docs/db-init/13). */
    static final Set<String> RULES_ENGINE_RULES = Set.of(
            "GENDER_PREGNANCY_CONFLICT", "CANCER_STAGING_COMPLETENESS", "DIAGNOSIS_DATE_SANITY",
            "LAB_REFERENCE_RANGE", "PATIENT_AGE_CONSISTENCY", "MEDICATION_ALLERGY_CONFLICT",
            "FHIR_USCORE_COMPLETENESS");

    private RuleOwnership() {
    }

    /** The evaluating service, or null when nothing evaluates the rule yet. */
    public static Downstream evaluatedBy(String ruleCode) {
        if (ruleCode == null) {
            return null;
        }
        if (RULES_ENGINE_RULES.contains(ruleCode)) {
            return Downstream.RULES_ENGINE;
        }
        if (ruleCode.startsWith("USCORE_")) {
            return Downstream.FHIR_COMPLIANCE;
        }
        if (ruleCode.startsWith("CODE-")) {
            return Downstream.ONTOLOGY;
        }
        if (ruleCode.equals("PMI_MATCH")) {
            return Downstream.PATIENT_INDEX;
        }
        return null;
    }

    public static boolean toggleable(String ruleCode) {
        Downstream owner = evaluatedBy(ruleCode);
        return owner == Downstream.RULES_ENGINE || owner == Downstream.FHIR_COMPLIANCE;
    }

    /** The owner's toggle endpoint (URI template with one {code} variable). */
    public static String toggleUri(String ruleCode) {
        return switch (evaluatedBy(ruleCode)) {
            case RULES_ENGINE -> "/api/rules/{code}/toggle";
            case FHIR_COMPLIANCE -> "/api/fhir-compliance/rules/{code}/toggle";
            default -> throw new IllegalArgumentException(ruleCode + " is not toggleable");
        };
    }
}
