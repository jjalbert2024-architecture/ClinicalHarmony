package com.clinicalharmony.ingestion.web;

import com.clinicalharmony.ingestion.service.Hl7ProcessingResult;

public record Hl7IngestionResponse(
        boolean accepted,
        long rawMessageId,
        String messageType
) {
    public static Hl7IngestionResponse from(Hl7ProcessingResult result) {
        return new Hl7IngestionResponse(result.accepted(), result.rawMessageId(), result.messageType());
    }
}
