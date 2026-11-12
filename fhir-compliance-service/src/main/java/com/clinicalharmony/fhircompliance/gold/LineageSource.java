package com.clinicalharmony.fhircompliance.gold;

/**
 * The immediate source of a Gold row, i.e. the {@code source_*} columns of its gold.data_lineage
 * row, plus the named step that produced it.
 *
 * @param id null when the source isn't a table row (seed file, API request) — migration 18 made
 *           data_lineage.source_id nullable for exactly this
 */
public record LineageSource(String schema, String table, Long id, String transformationStep) {

    /** Silver → Gold FHIR mapping (Slice 1 builders). */
    public static final String FHIR_MAP = "FHIR_MAP";
    /** Silver patient → provisional 1:1 master, pending Phase 6's PMI. */
    public static final String PMI_PROVISIONAL = "PMI_PROVISIONAL";
    /** Seeded straight into Gold (migration 19), then validated by GoldWriter. */
    public static final String SEED_LOAD = "SEED_LOAD";
    /** Caller-supplied record via Phase 5's POST /api/fhir-compliance/generate. */
    public static final String API_GENERATE = "API_GENERATE";

    public static LineageSource silver(String table, long id, String step) {
        return new LineageSource("silver", table, id, step);
    }
}
