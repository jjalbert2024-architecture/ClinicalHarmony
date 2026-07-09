package com.clinicalharmony.rulesengine.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Canonical patient record submitted for clinical rule evaluation. Richer than
 * ontology-service's PatientRecordRequest — rules need clinical context (gender, DOB, staging,
 * allergies, reference ranges, raw FHIR resources) that pure code validation doesn't. Each item
 * carries a caller-assigned {@code id}, used as silver.validation_reports.entity_id, since no
 * Silver-layer parser exists yet to assign real persisted row ids (see ontology-service's
 * PatientRecordRequest for the same convention).
 */
public record PatientRecordRequest(
        @NotNull Long patientId,
        String gender,
        LocalDate dateOfBirth,
        Integer statedAge,
        @Valid List<ConditionItem> conditions,
        @Valid List<ObservationItem> observations,
        @Valid List<MedicationItem> medications,
        List<AllergyItem> allergies,
        List<Map<String, Object>> fhirResources
) {

    /** {@code codeSystem} is "ICD-10" or "SNOMED-CT". {@code stagingInfo} is free text (e.g. "Stage IIIA"). */
    public record ConditionItem(
            @NotNull Long id,
            String codeSystem,
            String code,
            LocalDate onsetDate,
            String stagingInfo
    ) {
    }

    /** {@code referenceLow}/{@code referenceHigh} override LAB_REFERENCE_RANGE's built-in defaults when supplied. */
    public record ObservationItem(
            @NotNull Long id,
            String code,
            Double valueNumeric,
            LocalDateTime effectiveDate,
            Double referenceLow,
            Double referenceHigh
    ) {
    }

    public record MedicationItem(
            @NotNull Long id,
            String rxnormCode,
            String display,
            LocalDate startDate
    ) {
    }

    /** Either field may be present; MEDICATION_ALLERGY_CONFLICT matches on whichever is supplied. */
    public record AllergyItem(String rxnormCode, String substance) {
    }
}
