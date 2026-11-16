package com.clinicalharmony.bronze.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Value of a {@code clinicalharmony.bronze.hl7v2.raw} record (schema-version 1), as published by
 * ingestion-service's Hl7RawPublisher. Envelope fields (event-id, correlation-id…) are Kafka headers.
 */
public record Hl7RawEvent(String sourceSystem, String payloadChecksum, Instant receivedAt,
                          String hl7MessageType, String controlId, String patientId, String rawPayload) {

    public static Hl7RawEvent parse(String json, ObjectMapper mapper) {
        if (json == null) {
            throw new InvalidEventException("Record has no value");
        }
        JsonNode n;
        try {
            n = mapper.readTree(json);
        } catch (Exception e) {
            throw new InvalidEventException("Record value is not JSON", e);
        }
        if (n == null || !n.isObject()) {
            throw new InvalidEventException("Record value is not a JSON object");
        }
        String raw = text(n, "rawPayload");
        String source = text(n, "sourceSystem");
        String checksum = text(n, "payloadChecksum");
        if (raw == null) {
            throw new InvalidEventException("Event has no rawPayload");
        }
        if (source == null || source.isBlank()) {
            throw new InvalidEventException("Event has no sourceSystem");
        }
        if (checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new InvalidEventException("Event payloadChecksum is not a SHA-256 hex digest");
        }
        Instant receivedAt;
        try {
            String r = text(n, "receivedAt");
            receivedAt = r == null ? null : Instant.parse(r);
        } catch (DateTimeParseException e) {
            throw new InvalidEventException("Event receivedAt is not an ISO-8601 instant", e);
        }
        return new Hl7RawEvent(source, checksum, receivedAt, text(n, "hl7MessageType"), text(n, "controlId"),
                text(n, "patientId"), raw);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
