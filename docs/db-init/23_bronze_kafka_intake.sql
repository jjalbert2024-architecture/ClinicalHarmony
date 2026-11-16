-- =============================================================================
-- 23_bronze_kafka_intake.sql   (Phase 9a — Bronze over Kafka)
--
-- Non-destructive and idempotent: safe to apply to a running database.
--
-- 1. Provenance columns on bronze.raw_messages for rows written by bronze-listener-service from
--    topic clinicalharmony.bronze.hl7v2.raw: which intake channel, the correlation id set at MLLP
--    intake, and the exact Kafka record (topic / partition / offset) the row came from.
-- 2. Idempotency: a partial unique index on (source_system, payload_checksum) for MLLP_KAFKA rows
--    only. REST rows stay non-unique because the REST paths still write directly and the smoke test
--    (and existing data) legitimately post the same fixture many times. Phase 9d, when REST moves
--    onto Kafka, widens this to the whole table.
-- 3. bronze.outbox_events: the bronze.ingested event is written in the SAME transaction as the
--    Bronze row, then relayed to Kafka — never lost, never sent for a rolled-back write.
-- =============================================================================

ALTER TABLE bronze.raw_messages
    ADD COLUMN IF NOT EXISTS intake_channel  VARCHAR(20) NOT NULL DEFAULT 'REST',
    ADD COLUMN IF NOT EXISTS correlation_id  VARCHAR(64),
    ADD COLUMN IF NOT EXISTS kafka_topic     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS kafka_partition INTEGER,
    ADD COLUMN IF NOT EXISTS kafka_offset    BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_raw_messages_intake_channel') THEN
        ALTER TABLE bronze.raw_messages
            ADD CONSTRAINT chk_raw_messages_intake_channel CHECK (intake_channel IN ('REST', 'MLLP_KAFKA'));
    END IF;
END $$;

COMMENT ON COLUMN bronze.raw_messages.intake_channel IS 'REST (written directly by ingestion-service) or MLLP_KAFKA (MLLP → Kafka → bronze-listener-service).';
COMMENT ON COLUMN bronze.raw_messages.correlation_id IS 'Correlation id assigned at intake and carried on every Kafka hop and log line.';
COMMENT ON COLUMN bronze.raw_messages.kafka_offset IS 'Offset of the clinicalharmony.bronze.hl7v2.raw record this row was written from.';

CREATE UNIQUE INDEX IF NOT EXISTS uq_raw_messages_kafka_source_checksum
    ON bronze.raw_messages (source_system, payload_checksum)
    WHERE intake_channel = 'MLLP_KAFKA';

CREATE INDEX IF NOT EXISTS idx_raw_messages_correlation_id
    ON bronze.raw_messages (correlation_id) WHERE correlation_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS bronze.outbox_events (
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

COMMENT ON TABLE bronze.outbox_events IS 'Transactional outbox for bronze-listener-service; relayed to Kafka in id order, then stamped published_at.';
COMMENT ON COLUMN bronze.outbox_events.aggregate_id IS 'bronze.raw_messages.id the event announces.';

CREATE INDEX IF NOT EXISTS idx_outbox_events_unpublished
    ON bronze.outbox_events (id) WHERE published_at IS NULL;
