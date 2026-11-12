package com.clinicalharmony.fhircompliance.gold;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One hop in a Gold row's lineage chain, stored (in order) under
 * {@code gold.data_lineage.transformation_details -> 'steps'}.
 *
 * @param stage       BRONZE, SILVER, GOLD — or SEED / API for Gold rows with no Bronze/Silver source
 * @param timestamp   ISO-8601 instant the stage produced its output
 * @param transformer what produced this stage's output (service/component, plus profile for GOLD)
 * @param inputHash   SHA-256 hex of what this stage consumed: the raw payload for BRONZE (=
 *                    bronze.raw_messages.payload_checksum) and for a SILVER row parsed from it; the
 *                    Silver row for GOLD. Null where the input isn't known (e.g. Silver rows loaded
 *                    with no Bronze link).
 * @param outputHash  SHA-256 hex of the stored output — only for GOLD, where it's the resource JSON
 * @param ref         schema.table/id of the row this stage produced, when there is one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LineageStep(
        String stage,
        String timestamp,
        String transformer,
        @JsonProperty("input_hash") String inputHash,
        @JsonProperty("output_hash") String outputHash,
        String ref
) {
    public static final String BRONZE = "BRONZE";
    public static final String SILVER = "SILVER";
    public static final String GOLD = "GOLD";
    public static final String SEED = "SEED";
    public static final String API = "API";
}
