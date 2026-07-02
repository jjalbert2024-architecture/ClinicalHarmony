package com.clinicalharmony.ingestion.repository;

/**
 * Mirrors a row of {@code bronze.raw_messages}. message_type/message_format/processing_status
 * values must match the CHECK constraints defined in docs/db-init/02_bronze_tables.sql.
 */
public record RawMessageRecord(
        String sourceSystem,
        String messageType,
        String messageFormat,
        String rawPayload,
        String payloadChecksum,
        String processingStatus,
        String errorMessage
) {

    public static RawMessageRecord accepted(String sourceSystem, String rawPayload, String checksum) {
        return new RawMessageRecord(sourceSystem, "HL7V2", "TEXT", rawPayload, checksum, "PENDING", null);
    }

    public static RawMessageRecord quarantined(String sourceSystem, String rawPayload, String checksum, String errorMessage) {
        return new RawMessageRecord(sourceSystem, "HL7V2", "TEXT", rawPayload, checksum, "QUARANTINED", errorMessage);
    }
}
