package com.clinicalharmony.silver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "silver")
public record SilverProperties(int partitions, Retry retry, Outbox outbox, Validation validation, RulesEngine rulesEngine) {

    public record Retry(int maxRetries, Duration initialInterval, double multiplier) {
    }

    public record Outbox(Duration pollInterval, int batchSize, Duration sendTimeout) {
    }

    public record Validation(Duration sweepInterval, Duration minAge, int batchSize) {
    }

    public record RulesEngine(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    }
}
