-- =============================================================================
-- 20_patient_index.sql
--
-- Phase 6 (patient-index-service, the Patient Master Index / PMI).
--
-- Ownership (decided at Phase 6 scoping): fhir-compliance-service's GoldWriter
-- stays the ONLY writer of gold.*. patient-index-service reads Silver/Gold,
-- scores candidate pairs and keeps its own working state in a new `pmi`
-- schema; every change to a master (merge / unmerge / confirm) is an HTTP call
-- to GoldWriter, which applies it in one transaction.
--
-- 1. gold.master_patient_merges (written by GoldWriter)
--    One row per "master B was absorbed into master A". Records exactly which
--    patient_source_xref rows and gold.fhir_resources rows moved, so an unmerge
--    can put them back. unmerged_at marks a merge as undone (rows are kept as
--    history, never deleted).
--
-- 2. pmi.* (written by patient-index-service)
--    match_runs        — one row per matching run, with its counts.
--    match_candidates  — pairs scored into the review band (and auto-merged
--                        pairs, for the audit trail), with the per-field score
--                        breakdown and the reviewer's decision.
--    match_exclusions  — "these two Silver patients are NOT the same person"
--                        (a rejected review or an unmerge); later runs never
--                        propose that pair again.
--
-- 3. ontology.clinical_rules PMI_MATCH
--    The matching weights and thresholds as rule_expression JSON, so tuning the
--    matcher is a data change — the same pattern as the Phase 4/5 rules.
--
-- Non-destructive and idempotent: safe to apply to the running container.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS pmi;
COMMENT ON SCHEMA pmi IS 'patient-index-service working state: matching runs, the review queue and non-match decisions. Gold itself is written only by GoldWriter.';

-- -----------------------------------------------------------------------------
-- 1. Merge history (GoldWriter)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gold.master_patient_merges (
    id                       BIGSERIAL PRIMARY KEY,
    survivor_master_id       BIGINT          NOT NULL REFERENCES gold.master_patients (id) ON DELETE CASCADE,
    absorbed_master_id       BIGINT          NOT NULL REFERENCES gold.master_patients (id) ON DELETE CASCADE,
    moved_silver_patient_ids BIGINT[]        NOT NULL,
    moved_fhir_resource_ids  BIGINT[]        NOT NULL DEFAULT '{}',
    match_confidence_score   NUMERIC(5, 4)
        CONSTRAINT chk_master_patient_merges_confidence
        CHECK (match_confidence_score IS NULL OR match_confidence_score BETWEEN 0 AND 1),
    match_method             VARCHAR(20)     NOT NULL
        CONSTRAINT chk_master_patient_merges_method
        CHECK (match_method IN ('DETERMINISTIC', 'PROBABILISTIC', 'MANUAL')),
    evidence                 JSONB,
    decided_by               VARCHAR(100)    NOT NULL,
    merged_at                TIMESTAMPTZ     NOT NULL DEFAULT now(),
    unmerged_at              TIMESTAMPTZ,
    unmerged_by              VARCHAR(100),
    unmerge_reason           TEXT,
    CONSTRAINT chk_master_patient_merges_distinct CHECK (survivor_master_id <> absorbed_master_id)
);

COMMENT ON TABLE gold.master_patient_merges IS
    'Merge history written by GoldWriter on behalf of patient-index-service: which master absorbed which, and exactly which xref/FHIR rows moved, so the merge can be undone.';
COMMENT ON COLUMN gold.master_patient_merges.evidence IS
    'What justified the merge: the scored Silver pair and per-field breakdown (automatic), or the reviewer''s note (manual).';

CREATE INDEX IF NOT EXISTS idx_master_patient_merges_survivor ON gold.master_patient_merges (survivor_master_id);
CREATE INDEX IF NOT EXISTS idx_master_patient_merges_absorbed ON gold.master_patient_merges (absorbed_master_id);
-- A master can be absorbed by at most one live (not undone) merge at a time.
CREATE UNIQUE INDEX IF NOT EXISTS uq_master_patient_merges_live_absorbed
    ON gold.master_patient_merges (absorbed_master_id) WHERE unmerged_at IS NULL;

-- -----------------------------------------------------------------------------
-- 2. PMI working state (patient-index-service)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pmi.match_runs (
    id                     BIGSERIAL PRIMARY KEY,
    status                 VARCHAR(20)     NOT NULL DEFAULT 'RUNNING'
        CONSTRAINT chk_match_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    triggered_by           VARCHAR(100)    NOT NULL,
    started_at             TIMESTAMPTZ     NOT NULL DEFAULT now(),
    finished_at            TIMESTAMPTZ,
    provisional_masters    INTEGER         NOT NULL DEFAULT 0,
    pairs_compared         INTEGER         NOT NULL DEFAULT 0,
    auto_merges            INTEGER         NOT NULL DEFAULT 0,
    queued_for_review      INTEGER         NOT NULL DEFAULT 0,
    confirmed_singletons   INTEGER         NOT NULL DEFAULT 0,
    settings               JSONB,
    error_message          TEXT
);

COMMENT ON TABLE pmi.match_runs IS 'One row per PMI matching run; settings snapshots the PMI_MATCH weights/thresholds used.';

CREATE TABLE IF NOT EXISTS pmi.match_candidates (
    id                  BIGSERIAL PRIMARY KEY,
    run_id              BIGINT          NOT NULL REFERENCES pmi.match_runs (id) ON DELETE CASCADE,
    silver_patient_a_id BIGINT          NOT NULL REFERENCES silver.patients (id) ON DELETE CASCADE,
    silver_patient_b_id BIGINT          NOT NULL REFERENCES silver.patients (id) ON DELETE CASCADE,
    master_a_id         BIGINT          NOT NULL,
    master_b_id         BIGINT          NOT NULL,
    score               NUMERIC(5, 4)   NOT NULL
        CONSTRAINT chk_match_candidates_score CHECK (score BETWEEN 0 AND 1),
    match_method        VARCHAR(20)     NOT NULL
        CONSTRAINT chk_match_candidates_method CHECK (match_method IN ('DETERMINISTIC', 'PROBABILISTIC')),
    deterministic_rule  VARCHAR(50),
    breakdown           JSONB           NOT NULL,
    status              VARCHAR(20)     NOT NULL
        CONSTRAINT chk_match_candidates_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'AUTO_MERGED', 'SUPERSEDED')),
    merge_id            BIGINT,
    reviewed_by         VARCHAR(100),
    reviewed_at         TIMESTAMPTZ,
    review_note         TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT chk_match_candidates_ordered CHECK (silver_patient_a_id < silver_patient_b_id)
);

COMMENT ON TABLE pmi.match_candidates IS
    'Scored Silver patient pairs: PENDING = in the review band awaiting a human; AUTO_MERGED = merged by a run; ACCEPTED/REJECTED = reviewed; SUPERSEDED = its two records ended up under one master another way.';
COMMENT ON COLUMN pmi.match_candidates.master_a_id IS 'Master of silver_patient_a_id when scored (masters move as merges happen; re-resolved on review).';

CREATE INDEX IF NOT EXISTS idx_match_candidates_status ON pmi.match_candidates (status);
CREATE INDEX IF NOT EXISTS idx_match_candidates_run ON pmi.match_candidates (run_id);
-- At most one open review item per Silver pair.
CREATE UNIQUE INDEX IF NOT EXISTS uq_match_candidates_pending_pair
    ON pmi.match_candidates (silver_patient_a_id, silver_patient_b_id) WHERE status = 'PENDING';

CREATE TABLE IF NOT EXISTS pmi.match_exclusions (
    id                  BIGSERIAL PRIMARY KEY,
    silver_patient_a_id BIGINT          NOT NULL REFERENCES silver.patients (id) ON DELETE CASCADE,
    silver_patient_b_id BIGINT          NOT NULL REFERENCES silver.patients (id) ON DELETE CASCADE,
    reason              VARCHAR(20)     NOT NULL
        CONSTRAINT chk_match_exclusions_reason CHECK (reason IN ('REVIEW_REJECTED', 'UNMERGED')),
    decided_by          VARCHAR(100)    NOT NULL,
    note                TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT chk_match_exclusions_ordered CHECK (silver_patient_a_id < silver_patient_b_id),
    CONSTRAINT uq_match_exclusions_pair UNIQUE (silver_patient_a_id, silver_patient_b_id)
);

COMMENT ON TABLE pmi.match_exclusions IS 'Human "not the same person" decisions; matching runs never propose an excluded Silver pair again.';

-- -----------------------------------------------------------------------------
-- 3. Matching configuration
-- -----------------------------------------------------------------------------
INSERT INTO ontology.clinical_rules
    (rule_code, rule_name, description, rule_category, default_severity, rule_expression)
VALUES (
    'PMI_MATCH',
    'Patient Master Index Matching',
    'Cross-source patient matching: deterministic rules first, then Fellegi-Sunter-style weighted field agreement. score = sum of field weights / maximum possible over the fields present on both records. score >= autoMerge merges automatically (only when at least minComparableWeight points of evidence were comparable), score >= review queues the pair for a human, otherwise no match.',
    'IDENTITY',
    'INFO',
    '{
       "thresholds": {"autoMerge": 0.85, "review": 0.60, "minComparableWeight": 10.0},
       "weights": {
         "lastName":  {"exact": 4.0, "similar": 2.5, "similarMin": 0.92, "disagree": -3.0},
         "firstName": {"exact": 3.5, "similar": 2.0, "similarMin": 0.90, "initial": 0.5, "disagree": -3.0},
         "birthDate": {"exact": 5.0, "partial": 2.0, "disagree": -5.0},
         "gender":    {"exact": 0.5, "disagree": -4.0},
         "phone":     {"exact": 4.0, "disagree": -1.0},
         "postalCode":{"exact": 1.5, "disagree": -0.5},
         "address":   {"exact": 2.5, "similar": 1.5, "similarMin": 0.90, "disagree": -0.5}
       },
       "deterministic": ["SAME_SOURCE_MRN", "EXACT_NAME_DOB_PLUS_PHONE_OR_ZIP"],
       "blocking": ["BIRTH_DATE", "LAST_NAME_METAPHONE_BIRTH_YEAR", "PHONE"]
     }'::jsonb
)
ON CONFLICT (rule_code) DO NOTHING;
