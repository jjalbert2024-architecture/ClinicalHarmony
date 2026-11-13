package com.clinicalharmony.gateway.security;

/**
 * Keycloak realm roles the gateway authorizes on (claim {@code roles} in the dashboard clients'
 * tokens). A steward is also given {@code dashboard-viewer} in the realm, but the rules below
 * don't rely on that.
 */
public final class Roles {

    public static final String VIEWER = "dashboard-viewer";
    public static final String STEWARD = "data-steward";

    private Roles() {
    }
}
