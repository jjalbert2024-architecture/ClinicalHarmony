package com.clinicalharmony.patientindex.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code pmi-matcher.*} — the Phase 9c debounced matching trigger.
 *
 * @param quietPeriod  run this long after the LAST gold.patient.changed event of a burst (5 s)
 * @param maxDelay     … but never later than this after the FIRST pending event, so a steady stream still
 *                     gets a run at least this often (30 s)
 * @param tick         how often the debouncer checks whether a run is due
 * @param retryDelay   wait this long before trying again when a run failed (GoldWriter down) or the PMI lock
 *                     was busy (a manual run or review)
 * @param runOnStartup schedule one run after startup if PROVISIONAL masters are waiting — covers events whose
 *                     offsets were committed before a crash cut their debounced run short
 */
@ConfigurationProperties(prefix = "pmi-matcher")
public record PmiMatcherProperties(boolean enabled, int partitions, Duration quietPeriod, Duration maxDelay,
                                   Duration tick, Duration retryDelay, boolean runOnStartup, Retry retry) {

    public record Retry(int maxRetries, Duration initialInterval, double multiplier) {
    }
}
