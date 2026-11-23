package com.clinicalharmony.bronze.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Value of a {@code clinicalharmony.bronze.<format>.raw} record (schema-version 1), as published by
 * ingestion-service. Envelope fields (event-id, correlation-id…) are Kafka headers.
 *
 * @param intakeChannel {@code MLLP} or {@code REST} — absent on 9a-era MLLP events, so absent means MLLP
 * @param controlId     HL7 MSH-10 (HL7 topic only; logging)
 */
public record RawEvent(String sourceSystem, String payloadChecksum, Instant receivedAt, String intakeChannel,
                       String controlId, String rawPayload) {

    public static final String MLLP = "MLLP";
    public static final String REST = "REST";

    /** bronze.raw_messages.intake_channel for this event. */
    public String bronzeChannel() {
        return REST.equals(intakeChannel) ? "REST_KAFKA" : "MLLP_KAFKA";
    }

    public static RawEvent parse(String json, ObjectMapper mapper) {
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
        String channel = text(n, "intakeChannel");
        if (channel == null) {
            channel = MLLP;
        } else if (!MLLP.equals(channel) && !REST.equals(channel)) {
            throw new InvalidEventException("Event intakeChannel must be MLLP or REST, got " + channel);
        }
        Instant receivedAt;
        try {
            String r = text(n, "receivedAt");
            receivedAt = r == null ? null : Instant.parse(r);
        } catch (DateTimeParseException e) {
            throw new InvalidEventException("Event receivedAt is not an ISO-8601 instant", e);
        }
        return new RawEvent(source, checksum, receivedAt, channel, text(n, "controlId"), raw);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
