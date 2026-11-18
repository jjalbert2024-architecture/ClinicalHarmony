package com.clinicalharmony.patientindex.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Value of a {@code clinicalharmony.gold.patient.changed} record (schema-version 1), written by
 * fhir-compliance-service's gold-builder. Only {@code masterPatientId} is required: the matcher doesn't act on
 * one event — it reads every PROVISIONAL master from Gold when its debounced run starts.
 */
public record GoldPatientChangedEvent(long masterPatientId, Long silverPatientId, String sourceSystem) {

    public static GoldPatientChangedEvent parse(String json, ObjectMapper mapper) {
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
        JsonNode id = n.get("masterPatientId");
        if (id == null || !id.canConvertToLong() || id.asLong() <= 0) {
            throw new InvalidEventException("Event has no positive masterPatientId");
        }
        JsonNode silver = n.get("silverPatientId");
        JsonNode source = n.get("sourceSystem");
        return new GoldPatientChangedEvent(id.asLong(),
                silver == null || !silver.canConvertToLong() ? null : silver.asLong(),
                source == null || source.isNull() ? null : source.asText());
    }
}
