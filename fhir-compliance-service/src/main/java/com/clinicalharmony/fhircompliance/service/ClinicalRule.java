package com.clinicalharmony.fhircompliance.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Domain view of an ontology.clinical_rules row, with {@code expression} parsed from the raw
 * rule_expression JSON text. {@code expression} is a NullNode (never a Java null) when the
 * column is NULL. Same shape as rules-engine-service's ClinicalRule — the two services share no
 * code, so this is duplicated rather than imported.
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
