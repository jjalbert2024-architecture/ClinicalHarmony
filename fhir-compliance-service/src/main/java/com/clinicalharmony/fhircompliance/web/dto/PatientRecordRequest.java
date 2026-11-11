package com.clinicalharmony.fhircompliance.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Canonical patient record submitted for FHIR Bundle generation. Each item carries a
 * caller-assigned {@code id}, used to build that entry's resource id (e.g. "condition-7") —
 * same caller-assigned-id convention as ontology-service/rules-engine-service's
 * PatientRecordRequest, since no Silver-layer parser exists yet to assign real persisted ids.
 */
public record PatientRecordRequest(
        @NotNull Long patientId,
        String givenName,
        String familyName,
        String gender,
        LocalDate dateOfBirth,
        @Valid List<ConditionItem> conditions,
        @Valid List<ObservationItem> observations,
        @Valid List<MedicationItem> medications,
        @Valid List<AllergyItem> allergies,
        @Valid List<EncounterItem> encounters,
        @Valid List<DiagnosticReportItem> diagnosticReports
) {

    /** {@code codeSystem} is "ICD-10" or "SNOMED-CT". */
    public record ConditionItem(
            @NotNull Long id,
            String codeSystem,
            String code,
            String display,
            LocalDate onsetDate
    ) {
    }

    public record ObservationItem(
            @NotNull Long id,
            String code,
            String display,
            Double valueNumeric,
            String unit,
            LocalDateTime effectiveDate
    ) {
    }

    public record MedicationItem(
            @NotNull Long id,
            String rxnormCode,
            String display,
            LocalDate startDate
    ) {
    }

    public record AllergyItem(
            @NotNull Long id,
            String rxnormCode,
            String substance
    ) {
    }

    public record EncounterItem(
            @NotNull Long id,
            String status,
            String classCode,
            LocalDate startDate,
            LocalDate endDate
    ) {
    }

    public record DiagnosticReportItem(
            @NotNull Long id,
            String code,
            String display,
            String status,
            LocalDateTime issued
    ) {
    }
}
