package com.clinicalharmony.fhircompliance.goldbuilder;

/**
 * The Kafka record itself is broken (not JSON, no silverPatientId, no such Silver patient). Retrying can't fix
 * that, so the error handler sends it straight to the dead-letter topic. A Silver patient whose FHIR fails US Core
 * is NOT this: it is built and written to Gold with its violations in compliance_notes.
 */
public class InvalidEventException extends RuntimeException {
    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
