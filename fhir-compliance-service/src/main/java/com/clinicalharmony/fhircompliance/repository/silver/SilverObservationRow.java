package com.clinicalharmony.fhircompliance.repository.silver;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One row of silver.observations (always LOINC-coded, per the table CHECK). {@code referenceLow/High} = OBX-7,
 * {@code abnormalFlag} = OBX-8 (HL7 table 0078: N, H, L, HH, LL, A, AA …), all optional.
 */
public record SilverObservationRow(
        long id,
        long patientId,
        String code,
        String display,
        BigDecimal valueNumeric,
        String valueText,
        String unit,
        String status,
        OffsetDateTime effectiveDate,
        BigDecimal referenceLow,
        BigDecimal referenceHigh,
        String abnormalFlag
) {
    /** A row without a reference range or flag. */
    public SilverObservationRow(long id, long patientId, String code, String display, BigDecimal valueNumeric,
                                String valueText, String unit, String status, OffsetDateTime effectiveDate) {
        this(id, patientId, code, display, valueNumeric, valueText, unit, status, effectiveDate, null, null, null);
    }
}
