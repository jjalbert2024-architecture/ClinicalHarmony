package com.clinicalharmony.bronze.listener;

/**
 * The Kafka record itself is broken (not JSON, no rawPayload…). Retrying can't fix that, so the error
 * handler sends it straight to the dead-letter topic. Distinct from a malformed HL7 message INSIDE a
 * well-formed event, which is stored as QUARANTINED.
 */
public class InvalidEventException extends RuntimeException {
    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
