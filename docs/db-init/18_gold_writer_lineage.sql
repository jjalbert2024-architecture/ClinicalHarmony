-- =============================================================================
-- 18_gold_writer_lineage.sql
--
-- Phase 5b (fhir-compliance-service, slice 3): GoldWriter — the one component
-- that owns every Gold-layer write (master_patients, fhir_resources,
-- data_lineage). Three small schema changes make that possible:
--
-- 1. gold.master_patients.match_status
--    patient-index-service (Phase 6) doesn't exist yet, so GoldWriter creates a
--    PROVISIONAL master per silver.patients row (1:1, via patient_source_xref).
--    Phase 6 finds what still needs matching with WHERE match_status =
--    'PROVISIONAL', then marks survivors MATCHED and absorbed masters MERGED
--    (is_active = false).
--
-- 2. gold.patient_source_xref unique on silver_patient_id
--    A Silver patient belongs to exactly one master. Also lets GoldWriter find
--    "the master for this Silver patient" race-free with ON CONFLICT.
--
-- 3. gold.data_lineage
--    - source_id becomes nullable: Gold rows with no Silver/Bronze source (the
--      CMS-0057 seed in 19, Phase 5's caller-supplied /api/fhir-compliance/generate
--      records) still get a lineage row, with source_schema/source_table naming
--      where they came from and no id.
--    - unique (target_schema, target_table, target_id, transformation_step):
--      one lineage row per Gold row per step, upserted on rebuild instead of
--      appending a duplicate every time.
--    transformation_details carries the full chain as JSONB:
--      { "steps": [ { "stage", "timestamp", "transformer", "input_hash", "ref", ... } ] }
--    BRONZE → SILVER → GOLD where the Silver row links back to Bronze
--    (raw_message_id), SILVER → GOLD where it doesn't.
--
-- Non-destructive and idempotent: safe to apply to the running container.
-- =============================================================================

ALTER TABLE gold.master_patients
    ADD COLUMN IF NOT EXISTS match_status VARCHAR(20) NOT NULL DEFAULT 'PROVISIONAL';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_master_patients_match_status'
          AND conrelid = 'gold.master_patients'::regclass
    ) THEN
        ALTER TABLE gold.master_patients
            ADD CONSTRAINT chk_master_patients_match_status
            CHECK (match_status IN ('PROVISIONAL', 'MATCHED', 'MERGED'));
    END IF;
END $$;

COMMENT ON COLUMN gold.master_patients.match_status IS
    'PROVISIONAL: 1:1 placeholder master created by GoldWriter for a single silver.patients row before the Patient Master Index (Phase 6) exists. MATCHED: confirmed by the PMI. MERGED: absorbed into another master (is_active = false).';

CREATE INDEX IF NOT EXISTS idx_master_patients_provisional
    ON gold.master_patients (match_status) WHERE match_status = 'PROVISIONAL';

CREATE UNIQUE INDEX IF NOT EXISTS uq_patient_source_xref_silver_patient
    ON gold.patient_source_xref (silver_patient_id);

ALTER TABLE gold.data_lineage
    ALTER COLUMN source_id DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_data_lineage_target_step
    ON gold.data_lineage (target_schema, target_table, target_id, transformation_step);

COMMENT ON COLUMN gold.data_lineage.source_id IS
    'Id of the immediate source row; NULL when the Gold row has no table-row source (seed data, caller-supplied API records) — source_schema/source_table then name the origin.';
COMMENT ON COLUMN gold.data_lineage.transformation_details IS
    'Full lineage chain written by GoldWriter: {"steps": [{"stage": BRONZE|SILVER|GOLD|SEED|API, "timestamp", "transformer", "input_hash" (SHA-256 hex of what that stage consumed), "ref"}]}.';
