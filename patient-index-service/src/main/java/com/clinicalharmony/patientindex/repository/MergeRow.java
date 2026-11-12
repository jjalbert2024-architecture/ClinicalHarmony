package com.clinicalharmony.patientindex.repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** A gold.master_patient_merges row (written by GoldWriter on this service's behalf). */
public record MergeRow(long id, long survivorMasterId, long absorbedMasterId, List<Long> movedSilverPatientIds,
                       int movedFhirResourceCount, BigDecimal matchConfidenceScore, String matchMethod,
                       String decidedBy, OffsetDateTime mergedAt, OffsetDateTime unmergedAt, String unmergedBy,
                       String unmergeReason) {
    public boolean live() {
        return unmergedAt == null;
    }
}
