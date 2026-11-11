package com.clinicalharmony.fhircompliance.repository.silver;

import java.time.LocalDate;

/** One row of silver.conditions. {@code codeSystem} is 'ICD-10' or 'SNOMED-CT' (table CHECK). */
public record SilverConditionRow(
        long id,
        long patientId,
        String codeSystem,
        String code,
        String display,
        String clinicalStatus,
        LocalDate onsetDate,
        LocalDate recordedDate
) {
}
