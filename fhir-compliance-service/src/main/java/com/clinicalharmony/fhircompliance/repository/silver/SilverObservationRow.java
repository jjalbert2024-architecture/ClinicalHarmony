package com.clinicalharmony.fhircompliance.repository.silver;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One row of silver.observations (always LOINC-coded, per the table CHECK). */
public record SilverObservationRow(
        long id,
        long patientId,
        String code,
        String display,
        BigDecimal valueNumeric,
        String valueText,
        String unit,
        String status,
        OffsetDateTime effectiveDate
) {
}
