package com.clinicalharmony.patientindex.client;

/** GoldWriter (or Keycloak, for its token) couldn't be reached or failed with a 5xx (→ 502). */
public class GoldWriterUnavailableException extends RuntimeException {
    public GoldWriterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
