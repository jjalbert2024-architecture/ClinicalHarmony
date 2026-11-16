package com.clinicalharmony.bronze.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "bronze")
public record BronzeProperties(int partitions, Retry retry, Outbox outbox, Hl7 hl7) {

    public record Retry(int maxRetries, Duration initialInterval, double multiplier) {
    }

    public record Outbox(Duration pollInterval, int batchSize, Duration sendTimeout) {
    }

    public record Hl7(String defaultSourceSystem) {
    }
}
