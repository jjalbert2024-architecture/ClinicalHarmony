package com.clinicalharmony.fhircompliance.repository;

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
}
