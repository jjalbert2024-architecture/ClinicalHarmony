-- =============================================================================
-- 26_bronze_rest_kafka_intake.sql   (Phase 9d part 1 — REST intake over Kafka + C-CDA)
--
-- Non-destructive and idempotent: safe to apply to a running database. No row is changed or deleted.
--
-- 1. The REST intake paths (HL7 v2, FHIR JSON, claims CSV) no longer write Bronze directly: ingestion-service
--    publishes them to Kafka and bronze-listener-service stores them, like MLLP since 9a. Those rows get the
--    new intake_channel REST_KAFKA. Rows written directly by the pre-9d REST paths keep intake_channel REST.
-- 2. C-CDA documents become a Bronze format: message_type CCDA_XML, message_format XML.
-- 3. Idempotency: the partial unique index on (source_system, payload_checksum) now covers BOTH Kafka
--    channels (MLLP_KAFKA + REST_KAFKA), so the same payload from the same source is stored once whichever
--    door it came through. Legacy REST rows stay outside the index (the pre-9d smoke test posted the same
--    fixtures many times, so they hold legitimate identical copies — history, kept as is).
--    The 9a index (MLLP_KAFKA only) is replaced; dropping an index loses no data.
-- =============================================================================

-- 1 + 2: widen the CHECK constraints (drop + re-add with the same names).
ALTER TABLE bronze.raw_messages DROP CONSTRAINT IF EXISTS chk_raw_messages_message_type;
ALTER TABLE bronze.raw_messages
    ADD CONSTRAINT chk_raw_messages_message_type
    CHECK (message_type IN ('HL7V2', 'FHIR_JSON', 'CLAIMS_CSV', 'CCDA_XML'));

ALTER TABLE bronze.raw_messages DROP CONSTRAINT IF EXISTS chk_raw_messages_message_format;
ALTER TABLE bronze.raw_messages
    ADD CONSTRAINT chk_raw_messages_message_format
    CHECK (message_format IN ('TEXT', 'JSON', 'CSV', 'XML'));

ALTER TABLE bronze.raw_messages DROP CONSTRAINT IF EXISTS chk_raw_messages_intake_channel;
ALTER TABLE bronze.raw_messages
    ADD CONSTRAINT chk_raw_messages_intake_channel
    CHECK (intake_channel IN ('REST', 'MLLP_KAFKA', 'REST_KAFKA'));

COMMENT ON COLUMN bronze.raw_messages.intake_channel IS
    'REST (written directly by ingestion-service before 9d), MLLP_KAFKA (MLLP → Kafka → bronze-listener-service, 9a) '
    'or REST_KAFKA (REST → Kafka → bronze-listener-service, 9d).';
COMMENT ON COLUMN bronze.raw_messages.kafka_offset IS
    'Offset of the clinicalharmony.bronze.<format>.raw record this row was written from.';

-- 3: one idempotency index for both Kafka channels.
CREATE UNIQUE INDEX IF NOT EXISTS uq_raw_messages_kafka_intake_source_checksum
    ON bronze.raw_messages (source_system, payload_checksum)
    WHERE intake_channel IN ('MLLP_KAFKA', 'REST_KAFKA');

DROP INDEX IF EXISTS bronze.uq_raw_messages_kafka_source_checksum;
