-- =============================================================================
-- 25_gold_kafka_build.sql   (Phase 9c — Gold over Kafka)
--
-- Non-destructive and idempotent: safe to apply to a running database.
--
-- gold.outbox_events: fhir-compliance-service's gold-builder consumer rebuilds a Silver patient into Gold
-- (through GoldWriter) and, in the SAME transaction, GoldWriter writes the
-- clinicalharmony.gold.patient.changed event here; a poller in the same service relays it to Kafka
-- (same shape and pattern as bronze.outbox_events / silver.outbox_events). patient-index-service's
-- pmi-matcher consumes it.
--
-- No other schema change: gold.fhir_resources (upsert on resource_type + fhir_id) and gold.data_lineage
-- (upsert on target + step, migration 18) already make a rebuild idempotent, and the new US Core
-- AllergyIntolerance resources need no new column.
-- =============================================================================

CREATE TABLE IF NOT EXISTS gold.outbox_events (
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

COMMENT ON TABLE gold.outbox_events IS 'Transactional outbox for fhir-compliance-service''s gold-builder (rows written by GoldWriter in the Gold build transaction); relayed to Kafka in id order, then stamped published_at.';
COMMENT ON COLUMN gold.outbox_events.aggregate_id IS 'gold.master_patients.id the event announces (also the Kafka key).';

CREATE INDEX IF NOT EXISTS idx_gold_outbox_events_unpublished
    ON gold.outbox_events (id) WHERE published_at IS NULL;

-- The api-gateway's read-only role picks up new tables via 22's default privileges; grant explicitly
-- too, in case this runs against a database where 22 was applied by a different owner.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'clinicalharmony_gateway_ro') THEN
        GRANT SELECT ON gold.outbox_events TO clinicalharmony_gateway_ro;
    END IF;
END $$;
