package com.clinicalharmony.ontology.repository;

import com.clinicalharmony.ontology.service.ItemValidation;

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

    /** default_severity for all CODE-* rules seeded so far is ERROR (see 06/12_seed_*.sql). */
    public static ValidationReportRecord forFailedValidation(ItemValidation item, Long patientId) {
        String message = "Invalid " + item.codeSystem() + " code: " + item.outcome().reason();
        return new ValidationReportRecord(
                item.entityType(), item.entityId(), item.ruleCode(), "ERROR", message,
                patientId, item.outcome().code(), null);
    }
}
