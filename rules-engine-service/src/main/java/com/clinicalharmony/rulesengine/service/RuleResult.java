package com.clinicalharmony.rulesengine.service;

/**
 * Outcome of evaluating one clinical_rules row against one entity in a PatientRecordRequest.
 * {@code entityType}/{@code entityId} identify what silver.validation_reports row this becomes
 * on failure — PATIENT-level rules use the patientId as both entityId and patientId.
 */
public record RuleResult(
        String ruleCode,
        String severity,
        boolean passed,
        String entityType,
        Long entityId,
        Long patientId,
        String affectedField,
        String failureReason,
        String badValue,
        String suggestedFix
) {

    public static RuleResult pass(String ruleCode, String severity, String entityType, Long entityId, Long patientId) {
        return new RuleResult(ruleCode, severity, true, entityType, entityId, patientId, null, null, null, null);
    }

    public static RuleResult fail(String ruleCode, String severity, String entityType, Long entityId, Long patientId,
                                   String affectedField, String failureReason, String badValue, String suggestedFix) {
        return new RuleResult(ruleCode, severity, false, entityType, entityId, patientId, affectedField, failureReason, badValue, suggestedFix);
    }
}
