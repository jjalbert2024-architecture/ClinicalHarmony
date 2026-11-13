package com.clinicalharmony.gateway.web;

import org.springframework.http.HttpStatus;

/** A request the gateway itself refuses (bad filter combination, non-toggleable rule…) → that status as a problem. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String typeSlug;

    public ApiException(HttpStatus status, String typeSlug, String message) {
        super(message);
        this.status = status;
        this.typeSlug = typeSlug;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid-request", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String typeSlug() {
        return typeSlug;
    }
}
