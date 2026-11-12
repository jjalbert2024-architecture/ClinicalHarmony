package com.clinicalharmony.patientindex.matching;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Matching weights and thresholds, read from {@code ontology.clinical_rules.rule_expression} of
 * rule {@code PMI_MATCH} (migration 20) on every run — tuning is a data change. Any value missing
 * from the JSON falls back to {@link #DEFAULTS} (the values agreed at Phase 6 scoping).
 *
 * @param minComparableWeight the least "maximum possible" weight a pair needs before it may
 *                            auto-merge: two records that share only a birth date score 5/5 = 1.0,
 *                            which is too little evidence to merge without a human
 */
public record MatchSettings(
        double autoMergeThreshold,
        double reviewThreshold,
        double minComparableWeight,
        FieldWeights lastName,
        FieldWeights firstName,
        FieldWeights birthDate,
        FieldWeights gender,
        FieldWeights phone,
        FieldWeights postalCode,
        FieldWeights address
) {

    public static final MatchSettings DEFAULTS = new MatchSettings(0.85, 0.60, 10.0,
            new FieldWeights(4.0, 2.5, 0.92, 0, 0, -3.0),
            new FieldWeights(3.5, 2.0, 0.90, 0, 0.5, -3.0),
            new FieldWeights(5.0, 0, 0, 2.0, 0, -5.0),
            new FieldWeights(0.5, 0, 0, 0, 0, -4.0),
            new FieldWeights(4.0, 0, 0, 0, 0, -1.0),
            new FieldWeights(1.5, 0, 0, 0, 0, -0.5),
            new FieldWeights(2.5, 1.5, 0.90, 0, 0, -0.5));

    public MatchSettings {
        if (!(reviewThreshold > 0 && reviewThreshold <= autoMergeThreshold && autoMergeThreshold <= 1)) {
            throw new IllegalStateException("PMI_MATCH thresholds must satisfy 0 < review <= autoMerge <= 1, got review="
                    + reviewThreshold + ", autoMerge=" + autoMergeThreshold);
        }
    }

    public static MatchSettings fromJson(JsonNode expression) {
        if (expression == null || expression.isNull() || expression.isMissingNode()) {
            return DEFAULTS;
        }
        JsonNode thresholds = expression.path("thresholds");
        JsonNode weights = expression.path("weights");
        MatchSettings d = DEFAULTS;
        return new MatchSettings(
                thresholds.path("autoMerge").asDouble(d.autoMergeThreshold()),
                thresholds.path("review").asDouble(d.reviewThreshold()),
                thresholds.path("minComparableWeight").asDouble(d.minComparableWeight()),
                field(weights.path("lastName"), d.lastName()),
                field(weights.path("firstName"), d.firstName()),
                field(weights.path("birthDate"), d.birthDate()),
                field(weights.path("gender"), d.gender()),
                field(weights.path("phone"), d.phone()),
                field(weights.path("postalCode"), d.postalCode()),
                field(weights.path("address"), d.address()));
    }

    private static FieldWeights field(JsonNode node, FieldWeights fallback) {
        return new FieldWeights(
                node.path("exact").asDouble(fallback.exact()),
                node.path("similar").asDouble(fallback.similar()),
                node.path("similarMin").asDouble(fallback.similarMin()),
                node.path("partial").asDouble(fallback.partial()),
                node.path("initial").asDouble(fallback.initial()),
                node.path("disagree").asDouble(fallback.disagree()));
    }
}
