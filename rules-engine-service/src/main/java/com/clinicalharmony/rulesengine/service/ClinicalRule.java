package com.clinicalharmony.rulesengine.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Domain view of an ontology.clinical_rules row, with {@code expression} parsed from the raw
 * rule_expression JSON text. {@code expression} is a NullNode (never a Java null) when the
 * column is NULL, so evaluators can navigate it with plain .path()/.get() calls.
 */
public record ClinicalRule(
        String ruleCode,
        String ruleName,
        String description,
        String category,
        String severity,
        boolean active,
        JsonNode expression
) {
}
