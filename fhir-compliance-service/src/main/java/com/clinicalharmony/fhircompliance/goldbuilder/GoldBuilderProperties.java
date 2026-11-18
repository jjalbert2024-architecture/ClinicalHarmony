package com.clinicalharmony.fhircompliance.goldbuilder;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code gold-builder.*}: the Phase 9c Kafka consumer + outbox relay. {@code enabled=false} leaves the service
 * exactly as before 9c (HTTP only) — e.g. for a run without Kafka.
 */
@ConfigurationProperties(prefix = "gold-builder")
public record GoldBuilderProperties(boolean enabled, int partitions, Retry retry, Outbox outbox) {

    public record Retry(int maxRetries, Duration initialInterval, double multiplier) {
    }

    public record Outbox(Duration pollInterval, int batchSize, Duration sendTimeout) {
    }
}
