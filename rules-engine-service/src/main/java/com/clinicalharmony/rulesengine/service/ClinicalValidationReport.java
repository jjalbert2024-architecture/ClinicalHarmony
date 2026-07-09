package com.clinicalharmony.rulesengine.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Aggregated result of RulesOrchestrationService.evaluate(): ontology-service's code-validation
 * findings plus this service's own rule findings, in one response. {@code ontologyValidation}
 * is null when ontology-service was unreachable — check {@code ontologyValidationAvailable}
 * before relying on its absence to mean "all valid".
 */
public record ClinicalValidationReport(
        Long patientId,
        boolean allValid,
        boolean ontologyValidationAvailable,
        JsonNode ontologyValidation,
        List<RuleResult> ruleResults,
        List<Long> validationReportIds
) {
}
