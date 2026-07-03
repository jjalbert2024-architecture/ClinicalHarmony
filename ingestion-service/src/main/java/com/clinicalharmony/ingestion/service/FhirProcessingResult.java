package com.clinicalharmony.ingestion.service;

/**
 * Outcome of {@link FhirMessageProcessor#process(String, String)}. {@code resourceCount} is the
 * number of entries for a Bundle, or 1 for a single resource.
 */
public record FhirProcessingResult(
        boolean accepted,
        long rawMessageId,
        String resourceType,
        int resourceCount
) {
}
