package com.clinicalharmony.silver.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Value of a {@code clinicalharmony.bronze.ingested} record (schema-version 1), as written to
 * {@code bronze.outbox_events} by bronze-listener-service. The event carries the Bronze row id, not the
 * payload: the transformer reads the raw message from Bronze. Envelope fields (event-id,
 * correlation-id, causation-id…) are Kafka headers.
 */
public record BronzeIngestedEvent(long rawMessageId, String messageType, String hl7MessageType, String sourceSystem,
                                  String payloadChecksum, String controlId) {

    public static BronzeIngestedEvent parse(String json, ObjectMapper mapper) {
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
        JsonNode id = n.get("rawMessageId");
        if (id == null || !id.canConvertToLong() || id.asLong() <= 0) {
            throw new InvalidEventException("Event has no positive rawMessageId");
        }
        return new BronzeIngestedEvent(id.asLong(), text(n, "messageType"), text(n, "hl7MessageType"),
                text(n, "sourceSystem"), text(n, "payloadChecksum"), text(n, "controlId"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
