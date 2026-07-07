package com.clinicalharmony.ontology.service;

import com.clinicalharmony.ontology.validator.ValidationOutcome;

/**
 * One validated item from a PatientRecordRequest, paired with the clinical_rules.rule_code
 * that applies if it's invalid (so ValidationReportBuilder knows which rule to cite).
 */
public record ItemValidation(
        Long entityId,
        String entityType,
        String codeSystem,
        ValidationOutcome outcome,
        String ruleCode
) {
}
