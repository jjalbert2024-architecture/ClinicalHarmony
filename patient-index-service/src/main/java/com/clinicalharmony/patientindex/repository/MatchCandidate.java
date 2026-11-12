package com.clinicalharmony.patientindex.repository;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A pmi.match_candidates row.
 *
 * @param breakdown the scored pair's per-field breakdown, as stored (raw JSON)
 */
public record MatchCandidate(long id, long runId, long silverPatientAId, long silverPatientBId, long masterAId,
                             long masterBId, BigDecimal score, String matchMethod, String deterministicRule,
                             @JsonRawValue String breakdown, String status, Long mergeId, String reviewedBy,
                             OffsetDateTime reviewedAt, String reviewNote, OffsetDateTime createdAt) {

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";
    public static final String AUTO_MERGED = "AUTO_MERGED";
    public static final String SUPERSEDED = "SUPERSEDED";
}
