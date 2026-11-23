-- =============================================================================
-- 27_silver_claims.sql   (Phase 9d part 2 — every feed into Silver)
--
-- Non-destructive and idempotent: safe to apply to a running database.
--
-- silver-transformer-service now maps FHIR JSON Bundles, claims CSV lines and C-CDA documents into Silver as
-- well as HL7 v2. Patients, conditions, observations, medications and allergies reuse the existing tables. A
-- claims line also needs a home of its own: silver.claims (one row per claim line). Its diagnosis is ALSO written
-- to silver.conditions (as an ICD-10 condition of the member), which is what reaches Gold and the rules engine.
--
-- The member is a silver.patients row keyed on (source_system, member_id), built from the line's member columns.
-- The gateway's read-only role sees the new table through migration 22's default privileges.
-- =============================================================================

CREATE TABLE IF NOT EXISTS silver.claims (
    id               BIGSERIAL PRIMARY KEY,
    patient_id       BIGINT         NOT NULL REFERENCES silver.patients (id) ON DELETE CASCADE,
    claim_id         VARCHAR(50)    NOT NULL,
    service_date     DATE           NOT NULL,
    icd10_code       VARCHAR(20)    NOT NULL,
    billed_amount    NUMERIC(12, 2) NOT NULL,
    raw_message_id   BIGINT REFERENCES bronze.raw_messages (id),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_claims_patient_claim UNIQUE (patient_id, claim_id),
    CONSTRAINT chk_claims_billed_amount CHECK (billed_amount >= 0)
);

COMMENT ON TABLE silver.claims IS 'One claim line per row, from a claims CSV line in Bronze (9d). The diagnosis is also a silver.conditions row.';
COMMENT ON COLUMN silver.claims.claim_id IS 'The payer''s claim id; unique per member — a resent line updates its row.';

CREATE INDEX IF NOT EXISTS idx_claims_patient ON silver.claims (patient_id);
CREATE INDEX IF NOT EXISTS idx_claims_raw_message ON silver.claims (raw_message_id);
