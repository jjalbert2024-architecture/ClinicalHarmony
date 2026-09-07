package com.clinicalharmony.rulesengine.repository;

import com.clinicalharmony.rulesengine.service.RuleResult;

/** Mirrors a row of silver.validation_reports. */
public record ValidationReportRecord(
        String entityType,
        Long entityId,
        String ruleCode,
        String severity,
        String message,
        Long patientId,
        String badValue,
        String suggestedValue
) {

    public static ValidationReportRecord forFailedRule(RuleResult result) {
        return new ValidationReportRecord(
                result.entityType(), result.entityId(), result.ruleCode(), result.severity(), result.failureReason(),
                result.patientId(), result.badValue(), result.suggestedFix());
    }
}
