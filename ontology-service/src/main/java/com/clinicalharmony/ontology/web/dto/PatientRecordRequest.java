package com.clinicalharmony.ontology.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Canonical patient record submitted for ontology validation. Each item carries its own
 * caller-assigned {@code id} — used as silver.validation_reports.entity_id for any finding
 * against it — since no Silver-layer parser exists yet to assign real persisted row ids.
 */
public record PatientRecordRequest(
        @NotNull Long patientId,
        @Valid List<ConditionItem> conditions,
        @Valid List<ObservationItem> observations,
        @Valid List<MedicationItem> medications
) {

    /** {@code codeSystem} is "ICD-10" or "SNOMED-CT", matching silver.conditions.code_system. */
    public record ConditionItem(@NotNull Long id, @NotNull String codeSystem, String code) {
    }

    /** Always validated against LOINC, matching silver.observations.code_system. */
    public record ObservationItem(@NotNull Long id, String code) {
    }

    /** Always validated against RxNorm, matching silver.medications.rxnorm_code. */
    public record MedicationItem(@NotNull Long id, String rxnormCode) {
    }
}
