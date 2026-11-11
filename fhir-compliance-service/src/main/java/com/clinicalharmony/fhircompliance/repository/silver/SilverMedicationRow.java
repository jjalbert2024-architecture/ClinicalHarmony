package com.clinicalharmony.fhircompliance.repository.silver;

import java.time.LocalDate;

/** One row of silver.medications (RxNorm-coded). */
public record SilverMedicationRow(
        long id,
        long patientId,
        String rxnormCode,
        String display,
        String dosageText,
        String status,
        LocalDate startDate,
        LocalDate endDate
) {
}
