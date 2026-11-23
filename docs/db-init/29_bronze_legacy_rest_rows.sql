-- =============================================================================
-- 29_bronze_legacy_rest_rows.sql   (Phase 9d follow-up — no Bronze row "pending" forever)
--
-- Non-destructive and idempotent: safe to apply to a running database. Nothing is deleted; the payloads stay.
--
-- Before Phase 9d, ingestion-service's REST paths wrote bronze.raw_messages directly (intake_channel = 'REST').
-- Their valid rows were left PENDING: nothing transformed REST rows then, and since 9d the backlog sweeper only
-- takes MLLP_KAFKA / REST_KAFKA rows (a 9d decision: legacy rows untouched). So 226 rows (HL7 v2, FHIR JSON,
-- claims CSV; 2026-07-02 … 2026-11-18, mostly smoke-test fixtures) sat at PENDING forever and looked like stuck
-- work on the dashboard and in the PENDING count.
--
-- They are now FAILED with the reason, so the status tells the truth: this row will never reach Silver. Resending
-- the same payload to POST /api/ingestion/... runs it through Kafka like any new payload (a new REST_KAFKA row).
-- processed_at stays NULL — nothing processed them; FAILED here means "closed without processing".
-- No new legacy rows can appear: since 9d only bronze-listener-service writes Bronze, always with a Kafka channel.
-- =============================================================================

UPDATE bronze.raw_messages
SET processing_status = 'FAILED',
    error_message     = 'Legacy row: written straight to Bronze by the pre-Phase 9d REST intake, which nothing '
                        || 'transforms; it will never reach Silver. Resend the payload to POST /api/ingestion/... '
                        || 'to process it through Kafka.'
WHERE intake_channel = 'REST'
  AND processing_status = 'PENDING';
