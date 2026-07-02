-- =============================================================================
-- 02_bronze_tables.sql
--
-- Bronze layer: append-only landing zone for raw ingestion payloads.
-- Nothing here is parsed or validated — it exists purely as an audit trail
-- and replay source for the Silver-layer transformation jobs.
-- =============================================================================

CREATE TABLE IF NOT EXISTS bronze.raw_messages (
    id                  BIGSERIAL PRIMARY KEY,
    source_system       VARCHAR(100)    NOT NULL,
    message_type        VARCHAR(30)     NOT NULL
        CONSTRAINT chk_raw_messages_message_type
        CHECK (message_type IN ('HL7V2', 'FHIR_JSON', 'CLAIMS_CSV')),
    message_format      VARCHAR(20)     NOT NULL DEFAULT 'TEXT'
        CONSTRAINT chk_raw_messages_message_format
        CHECK (message_format IN ('TEXT', 'JSON', 'CSV')),
    raw_payload         TEXT            NOT NULL,
    payload_checksum    VARCHAR(64),
    processing_status   VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_raw_messages_processing_status
        CHECK (processing_status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED', 'QUARANTINED')),
    error_message       TEXT,
    received_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),
    processed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE bronze.raw_messages IS 'Immutable landing table for raw HL7 v2 messages, FHIR JSON bundles, and Claims CSV rows as received.';
COMMENT ON COLUMN bronze.raw_messages.source_system IS 'Originating system identifier, e.g. EPIC_EHR, CLAIMS_VENDOR_A.';
COMMENT ON COLUMN bronze.raw_messages.payload_checksum IS 'SHA-256 of raw_payload, used for idempotent re-ingestion detection.';
COMMENT ON COLUMN bronze.raw_messages.processing_status IS 'Lifecycle state as the Silver-layer pipeline consumes this message.';

CREATE INDEX IF NOT EXISTS idx_raw_messages_status_received
    ON bronze.raw_messages (processing_status, received_at);

CREATE INDEX IF NOT EXISTS idx_raw_messages_source_system
    ON bronze.raw_messages (source_system);

CREATE INDEX IF NOT EXISTS idx_raw_messages_message_type
    ON bronze.raw_messages (message_type);
