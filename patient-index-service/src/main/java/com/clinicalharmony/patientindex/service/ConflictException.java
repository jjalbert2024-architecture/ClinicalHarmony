package com.clinicalharmony.patientindex.service;

/** The request is valid but the current state doesn't allow it (→ 409). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
