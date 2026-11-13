package com.clinicalharmony.gateway.web;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The acting user's name as recorded downstream (PMI {@code reviewer}, max 50 chars): always from
 * the token's {@code preferred_username}, never from the request body, so the UI can't act as
 * someone else.
 */
final class Reviewer {

    private Reviewer() {
    }

    static String of(Jwt jwt) {
        String name = jwt.getClaimAsString("preferred_username");
        if (name == null || name.isBlank()) {
            name = jwt.getSubject();
        }
        return name.length() <= 50 ? name : name.substring(0, 50);
    }
}
