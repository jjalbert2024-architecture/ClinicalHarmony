package com.clinicalharmony.ingestion.service;

import java.util.List;

/**
 * Outcome of {@link ClaimsMessageProcessor#process(String, String)}. On success there is one
 * bronze.raw_messages row per claim line, so {@code rawMessageIds} has {@code rowsLanded}
 * entries; on quarantine the whole file lands as a single row and {@code rawMessageIds} holds
 * just that id.
 */
public record ClaimsProcessingResult(
        boolean accepted,
        int rowsLanded,
        List<Long> rawMessageIds,
        String errorMessage
) {
}
