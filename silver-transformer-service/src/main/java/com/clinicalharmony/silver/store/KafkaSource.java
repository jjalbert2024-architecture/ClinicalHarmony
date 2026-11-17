package com.clinicalharmony.silver.store;

/** Where a record came from, plus its envelope headers — carried forward onto the outbox event. */
public record KafkaSource(String topic, int partition, long offset, String key, String eventId, String correlationId) {
}
