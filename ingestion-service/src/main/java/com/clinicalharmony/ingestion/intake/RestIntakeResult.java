package com.clinicalharmony.ingestion.intake;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * REST intake response body (9d). There's no Bronze row id: the row is written asynchronously by
 * bronze-listener-service. Trace the payload by {@code correlationId} (Bronze {@code correlation_id}, every log
 * line, the dashboard's Data Lineage) or by {@code payloadChecksum}.
 *
 * @param accepted true = passed the syntax check (202); false = failed it (422) — published either way, so
 *                 Bronze keeps it as QUARANTINED
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RestIntakeResult(
        boolean accepted,
        String format,
        String sourceSystem,
        String correlationId,
        String eventId,
        String topic,
        Integer partition,
        Long offset,
        String payloadChecksum,
        String receivedAt,
        Map<String, Object> details,
        String error,
        String message
) {
}
