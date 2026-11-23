package com.clinicalharmony.ingestion.kafka;

/**
 * The serialized event would exceed {@code ingestion.kafka.max-event-bytes} (kept under Kafka's default 1 MiB
 * {@code max.request.size} / broker {@code message.max.bytes}), so it is never sent. REST answers 413 (resending
 * won't help); MLLP NAKs AE like any other failed publish.
 */
public class PayloadTooLargeException extends PublishFailedException {

    public PayloadTooLargeException(String message) {
        super(message, null);
    }
}
