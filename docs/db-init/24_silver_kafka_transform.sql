-- =============================================================================
-- 24_silver_kafka_transform.sql   (Phase 9b — Silver over Kafka)
--
-- Non-destructive and idempotent: safe to apply to a running database.
--
-- 1. silver.allergies: HL7 AL1 segments (RxNorm allergen, type, severity, reaction), so rules-engine's
--    MEDICATION_ALLERGY_CONFLICT can run on real Silver data. FHIR AllergyIntolerance waits for 9c.
-- 2. silver.patients validation tracking: silver-transformer-service writes Silver even when
--    rules-engine-service is down (fail-open), marking the patient PENDING_VALIDATION; a scheduled
--    sweeper re-validates it later. NULL = not tracked (seed / pre-9b rows).
-- 3. silver.observations reference range + abnormal flag from OBX-7 / OBX-8, so a re-validation built
--    from Silver alone gives LAB_REFERENCE_RANGE the sender's own range.
-- 4. silver.outbox_events: the clinicalharmony.silver.patient.changed event is written in the SAME
--    transaction as the Silver rows, then relayed to Kafka (same pattern as bronze.outbox_events).
-- =============================================================================

-- ---- 1. allergies -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS silver.allergies (
    id                  BIGSERIAL PRIMARY KEY,
    patient_id          BIGINT          NOT NULL REFERENCES silver.patients (id) ON DELETE CASCADE,
    code_system         VARCHAR(20)     NOT NULL DEFAULT 'RXNORM'
        CONSTRAINT chk_allergies_code_system
        CHECK (code_system IN ('RXNORM', 'SNOMED-CT', 'LOCAL')),
    code                VARCHAR(20)     NOT NULL,
    display             VARCHAR(255),
    allergy_type        VARCHAR(10)
        CONSTRAINT chk_allergies_type
        CHECK (allergy_type IS NULL OR allergy_type IN ('DA', 'FA', 'MA', 'MC', 'EA', 'AA', 'PA', 'LA')),
    severity            VARCHAR(10)
        CONSTRAINT chk_allergies_severity
        CHECK (severity IS NULL OR severity IN ('SV', 'MO', 'MI', 'U')),
    reaction            VARCHAR(255),
    raw_message_id      BIGINT REFERENCES bronze.raw_messages (id),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE silver.allergies IS 'Patient allergies from HL7 AL1 (type DA drug / FA food / MA misc / EA environmental; severity SV / MO / MI / U). One row per (patient, code system, code); raw_message_id = the message that first reported it.';

DROP TRIGGER IF EXISTS trg_allergies_set_updated_at ON silver.allergies;
CREATE TRIGGER trg_allergies_set_updated_at
    BEFORE UPDATE ON silver.allergies
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE UNIQUE INDEX IF NOT EXISTS uq_allergies_patient_code ON silver.allergies (patient_id, code_system, code);
CREATE INDEX IF NOT EXISTS idx_allergies_raw_message_id ON silver.allergies (raw_message_id);

-- validation_reports can now point at an allergy row
ALTER TABLE silver.validation_reports DROP CONSTRAINT IF EXISTS chk_validation_reports_entity_type;
ALTER TABLE silver.validation_reports
    ADD CONSTRAINT chk_validation_reports_entity_type
    CHECK (entity_type IN (
        'PATIENT', 'CONDITION', 'OBSERVATION', 'MEDICATION', 'RAW_MESSAGE',
        'MEDICATIONREQUEST', 'ALLERGYINTOLERANCE', 'ENCOUNTER', 'DIAGNOSTICREPORT', 'ALLERGY'
    ));

-- ---- 2. validation tracking on patients ------------------------------------
ALTER TABLE silver.patients
    ADD COLUMN IF NOT EXISTS validation_status        VARCHAR(25),
    ADD COLUMN IF NOT EXISTS validation_requested_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS validated_at             TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS validation_attempts      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS validation_error         TEXT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_patients_validation_status') THEN
        ALTER TABLE silver.patients
            ADD CONSTRAINT chk_patients_validation_status
            CHECK (validation_status IS NULL OR validation_status IN ('PENDING_VALIDATION', 'VALIDATED'));
    END IF;
END $$;

COMMENT ON COLUMN silver.patients.validation_status IS 'PENDING_VALIDATION (Silver written, rules-engine / ontology not yet run on it) or VALIDATED; NULL = not tracked (seed rows).';

CREATE INDEX IF NOT EXISTS idx_patients_pending_validation
    ON silver.patients (validation_requested_at) WHERE validation_status = 'PENDING_VALIDATION';

-- ---- 3. observation reference range ---------------------------------------
ALTER TABLE silver.observations
    ADD COLUMN IF NOT EXISTS reference_low   NUMERIC(18, 4),
    ADD COLUMN IF NOT EXISTS reference_high  NUMERIC(18, 4),
    ADD COLUMN IF NOT EXISTS abnormal_flag   VARCHAR(5);

CREATE INDEX IF NOT EXISTS idx_observations_raw_message_id ON silver.observations (raw_message_id);
CREATE INDEX IF NOT EXISTS idx_conditions_raw_message_id ON silver.conditions (raw_message_id);
CREATE INDEX IF NOT EXISTS idx_medications_raw_message_id ON silver.medications (raw_message_id);

-- ---- 4. outbox --------------------------------------------------------------
CREATE TABLE IF NOT EXISTS silver.outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID          NOT NULL UNIQUE,
    event_type      VARCHAR(100)  NOT NULL,
    topic           VARCHAR(255)  NOT NULL,
    message_key     VARCHAR(255),
    payload         JSONB         NOT NULL,
    headers         JSONB         NOT NULL DEFAULT '{}'::jsonb,
    aggregate_id    BIGINT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INTEGER       NOT NULL DEFAULT 0,
    last_error      TEXT
);

COMMENT ON TABLE silver.outbox_events IS 'Transactional outbox for silver-transformer-service; relayed to Kafka in id order, then stamped published_at.';
COMMENT ON COLUMN silver.outbox_events.aggregate_id IS 'silver.patients.id the event announces.';

CREATE INDEX IF NOT EXISTS idx_silver_outbox_events_unpublished
    ON silver.outbox_events (id) WHERE published_at IS NULL;

-- The api-gateway's read-only role picks up new tables via 22's default privileges; grant explicitly
-- too, in case this runs against a database where 22 was applied by a different owner.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'clinicalharmony_gateway_ro') THEN
        GRANT SELECT ON silver.allergies, silver.outbox_events TO clinicalharmony_gateway_ro;
    END IF;
END $$;
