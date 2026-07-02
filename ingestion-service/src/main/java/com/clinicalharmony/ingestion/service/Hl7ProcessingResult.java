package com.clinicalharmony.ingestion.service;

/**
 * Outcome of {@link Hl7MessageProcessor#process(String)}. {@code responseMessage} is the
 * pipe-encoded HL7 ACK/NAK to send back to the sending system over MLLP.
 */
public record Hl7ProcessingResult(
        boolean accepted,
        long rawMessageId,
        String messageType,
        String responseMessage
) {
}
