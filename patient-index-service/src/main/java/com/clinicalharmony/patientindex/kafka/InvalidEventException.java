package com.clinicalharmony.patientindex.kafka;

/** The Kafka record itself is broken (not JSON, no masterPatientId): straight to the dead-letter topic. */
public class InvalidEventException extends RuntimeException {
    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
