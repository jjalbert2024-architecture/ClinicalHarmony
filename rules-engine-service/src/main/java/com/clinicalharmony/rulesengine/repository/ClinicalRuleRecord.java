package com.clinicalharmony.rulesengine.repository;

/** Mirrors a row of ontology.clinical_rules. {@code ruleExpression} is the raw JSON text (nullable). */
public record ClinicalRuleRecord(
        long id,
        String ruleCode,
        String ruleName,
        String description,
        String ruleCategory,
        String defaultSeverity,
        boolean active,
        String ruleExpression
) {
}
