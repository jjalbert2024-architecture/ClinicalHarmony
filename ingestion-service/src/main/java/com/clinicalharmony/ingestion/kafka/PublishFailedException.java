package com.clinicalharmony.ingestion.kafka;

/**
 * Kafka did not confirm a write ({@code acks=all}) in time, or rejected it. The caller tells the sender to
 * retry: MLLP NAKs {@code AE}, REST answers 503 + {@code Retry-After}. Nothing was stored.
 */
public class PublishFailedException extends RuntimeException {

    public PublishFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
