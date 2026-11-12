package com.clinicalharmony.patientindex.service;

/** A master, merge, candidate, run or Silver patient that doesn't exist (→ 404). */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
