package com.clinicalharmony.fhircompliance.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** One gold.master_patient_merges row. */
public record MasterMergeRow(
        long id,
        long survivorMasterId,
        long absorbedMasterId,
        List<Long> movedSilverPatientIds,
        List<Long> movedFhirResourceIds,
        BigDecimal matchConfidenceScore,
        String matchMethod,
        String decidedBy,
        OffsetDateTime mergedAt,
        OffsetDateTime unmergedAt
) {
}
