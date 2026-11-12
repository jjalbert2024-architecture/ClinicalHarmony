package com.clinicalharmony.patientindex.matching;

import java.math.BigDecimal;
import java.util.List;

/**
 * The verdict on one pair of Silver patients.
 *
 * @param score             confidence in [0, 1]: 1 for a deterministic match, else {@code weightedScore}
 * @param weightedScore     sum of field weights / sum of their maxima, clamped to [0, 1]
 * @param method            DETERMINISTIC or PROBABILISTIC
 * @param deterministicRule which deterministic rule fired, or null
 * @param decision          AUTO_MERGE, REVIEW or NO_MATCH
 */
public record MatchScore(
        long silverPatientAId,
        long silverPatientBId,
        BigDecimal score,
        BigDecimal weightedScore,
        double totalWeight,
        double maxWeight,
        String method,
        String deterministicRule,
        String decision,
        List<FieldScore> fields
) {
    public static final String DETERMINISTIC = "DETERMINISTIC";
    public static final String PROBABILISTIC = "PROBABILISTIC";
    public static final String AUTO_MERGE = "AUTO_MERGE";
    public static final String REVIEW = "REVIEW";
    public static final String NO_MATCH = "NO_MATCH";

    /** Rule: same source system and same MRN — one source registered the same person twice. */
    public static final String SAME_SOURCE_MRN = "SAME_SOURCE_MRN";
    /** Rule: exact first + last name + DOB, plus exact phone or ZIP. */
    public static final String EXACT_NAME_DOB_PLUS_PHONE_OR_ZIP = "EXACT_NAME_DOB_PLUS_PHONE_OR_ZIP";
}
