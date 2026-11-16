package com.clinicalharmony.bronze.store;

/** Where a record came from, plus its envelope headers — stored on the Bronze row and carried forward. */
public record KafkaSource(String topic, int partition, long offset, String key, String eventId, String correlationId) {
}
