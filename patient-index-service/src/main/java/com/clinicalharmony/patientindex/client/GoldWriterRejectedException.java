package com.clinicalharmony.patientindex.client;

/**
 * GoldWriter refused a decision with a 4xx — e.g. 409 because a master was merged elsewhere since
 * this service read it. Nothing was written to Gold.
 */
public class GoldWriterRejectedException extends RuntimeException {

    private final int status;

    public GoldWriterRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
