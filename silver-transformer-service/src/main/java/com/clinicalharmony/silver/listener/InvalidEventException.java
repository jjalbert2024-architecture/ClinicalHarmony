package com.clinicalharmony.silver.listener;

/**
 * The Kafka record itself is broken (not JSON, no rawMessageId, no such Bronze row…). Retrying can't fix
 * that, so the error handler sends it straight to the dead-letter topic. Distinct from an HL7 message that
 * can't be mapped to Silver inside a well-formed event: that's a business outcome (Bronze row FAILED).
 */
public class InvalidEventException extends RuntimeException {
    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
