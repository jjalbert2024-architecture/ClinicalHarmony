package com.clinicalharmony.gateway.read;

/** A read-model lookup found nothing → 404 problem. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
