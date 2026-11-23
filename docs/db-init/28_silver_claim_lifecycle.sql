-- =============================================================================
-- 28_silver_claim_lifecycle.sql   (Phase 9d follow-up — where is the claim now?)
--
-- Non-destructive and idempotent: safe to apply to a running database.
--
-- Migration 27's silver.claims only knew what was billed. A claim has a lifecycle after that (in X12 terms:
-- 837 sent → 277CA acknowledged → 277 status → 835 paid / denied), and the claims CSV may now carry it in
-- OPTIONAL columns (old files without them still load; these columns stay NULL):
--   payer_id, payer_name, claim_status, submitted_date, status_date, allowed_amount, paid_amount,
--   patient_responsibility, status_reason (a claim adjustment reason code such as CO-50, CO-16, PR-1).
-- A later line with the same (member, claim_id) moves the claim forward — the writer keeps the row with the newest
-- status_date, so a late-arriving older line can't roll a PAID claim back to PENDED. Each line's Bronze row stays
-- the history.
--
-- "Waiting on" (payer / provider / patient / nobody) is NOT stored: the gateway derives it from status + amounts.
-- =============================================================================

ALTER TABLE silver.claims ADD COLUMN IF NOT EXISTS payer_id               VARCHAR(50);
ALTER TABLE silver.claims ADD COLUMN IF NOT EXISTS payer_name             VARCHAR(100);
ALTER TABLE silver.claims ADD COLUMN IF NOT EXISTS claim_status           VARCHAR(20);
ALTER TABLE silver.claims ADD COLUMN IF NOT EXISTS submitted_date         DATE;
ALTER TABLE silver.claims ADD COLUMN IF NOT EXISTS status_date            DATE;
ALTER TABLE silver.claims ADD COLUMN IF NOT EXISTS allowed_amount         NUMERIC(12, 2);
ALTER TABLE silver.claims ADD COLUMN IF NOT EXISTS paid_amount            NUMERIC(12, 2);
ALTER TABLE silver.claims ADD COLUMN IF NOT EXISTS patient_responsibility NUMERIC(12, 2);
ALTER TABLE silver.claims ADD COLUMN IF NOT EXISTS status_reason          VARCHAR(20);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_claims_claim_status') THEN
        ALTER TABLE silver.claims ADD CONSTRAINT chk_claims_claim_status CHECK (claim_status IN
            ('SUBMITTED', 'ACCEPTED', 'PENDED', 'PAID', 'PARTIALLY_PAID', 'DENIED', 'REJECTED', 'APPEALED', 'VOIDED'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_claims_lifecycle_amounts') THEN
        ALTER TABLE silver.claims ADD CONSTRAINT chk_claims_lifecycle_amounts CHECK (
            (allowed_amount IS NULL OR allowed_amount >= 0) AND (paid_amount IS NULL OR paid_amount >= 0)
            AND (patient_responsibility IS NULL OR patient_responsibility >= 0));
    END IF;
END $$;

COMMENT ON COLUMN silver.claims.claim_status IS 'Where the claim is now (9d follow-up); NULL = the feed did not say (pre-lifecycle file).';
COMMENT ON COLUMN silver.claims.status_reason IS 'Claim adjustment reason code for a denial / adjustment, e.g. CO-50, CO-16, PR-1.';
COMMENT ON COLUMN silver.claims.status_date IS 'When claim_status last changed; the newest one wins when a claim line is resent.';
