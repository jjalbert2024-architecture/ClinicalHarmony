package com.clinicalharmony.fhircompliance.goldbuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Value of a {@code clinicalharmony.silver.patient.changed} record (schema-version 1), as written to
 * {@code silver.outbox_events} by silver-transformer-service. Only {@code silverPatientId} is required: the
 * builder reads the patient's whole Silver record by id. Envelope fields (event-id, correlation-id…) are headers.
 */
public record SilverPatientChangedEvent(long silverPatientId, String sourceSystem, String sourcePatientId,
                                        Long rawMessageId, String hl7MessageType, String change) {

    public static SilverPatientChangedEvent parse(String json, ObjectMapper mapper) {
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
        JsonNode id = n.get("silverPatientId");
        if (id == null || !id.canConvertToLong() || id.asLong() <= 0) {
            throw new InvalidEventException("Event has no positive silverPatientId");
        }
        JsonNode raw = n.get("rawMessageId");
        Long rawMessageId = raw == null || raw.isNull() || !raw.canConvertToLong() ? null : raw.asLong();
        return new SilverPatientChangedEvent(id.asLong(), text(n, "sourceSystem"), text(n, "sourcePatientId"),
                rawMessageId, text(n, "hl7MessageType"), text(n, "change"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
