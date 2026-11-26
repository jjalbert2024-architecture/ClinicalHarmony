package com.clinicalharmony.gateway.read;

/** A read filter the gateway refuses (unknown attribute, bad date…) → 400 invalid-request. */
public class InvalidFilterException extends RuntimeException {

    public InvalidFilterException(String message) {
        super(message);
    }
}
