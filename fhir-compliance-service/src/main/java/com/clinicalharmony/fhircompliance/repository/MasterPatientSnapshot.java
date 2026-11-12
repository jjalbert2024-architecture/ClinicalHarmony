package com.clinicalharmony.fhircompliance.repository;

import java.math.BigDecimal;
import java.util.UUID;

/** gold.master_patients state as returned by the PMI-facing GoldWriter operations. */
public record MasterPatientSnapshot(
        long id,
        UUID masterPatientUuid,
        String matchStatus,
        boolean active,
        BigDecimal matchConfidenceScore,
        int sourcePatientCount
) {
    public static final String PROVISIONAL = "PROVISIONAL";
    public static final String MATCHED = "MATCHED";
    public static final String MERGED = "MERGED";
}
