package com.clinicalharmony.patientindex.matching;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How one field compared: its outcome band, the points it scored, and what it could have scored
 * (0 when the field is missing on either side, so it doesn't count toward the maximum).
 *
 * @param outcome    EXACT, SIMILAR, NICKNAME, INITIAL, PARTIAL, DISAGREE or MISSING
 * @param valueA     the normalized value compared from record A (null if missing)
 * @param similarity Jaro-Winkler similarity, where that decided the band
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldScore(String field, String outcome, double weight, double maxWeight, String valueA,
                         String valueB, Double similarity) {

    public static final String EXACT = "EXACT";
    public static final String SIMILAR = "SIMILAR";
    public static final String NICKNAME = "NICKNAME";
    public static final String INITIAL = "INITIAL";
    public static final String PARTIAL = "PARTIAL";
    public static final String DISAGREE = "DISAGREE";
    public static final String MISSING = "MISSING";
}
