package com.clinicalharmony.fhircompliance.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SMART App Launch discovery document, pointing clients at the Keycloak realm.
 *
 * <p>Served at the spec location, {@code [FHIR base]/.well-known/smart-configuration} =
 * {@code /fhir/r4/.well-known/smart-configuration}, and at the server root for clients that look
 * there. Public (no token: it's how a client learns where to get one).
 *
 * <p>Advertises only what's actually wired up: the {@code client_credentials} grant with a client
 * secret ({@code client-confidential-symmetric}). SMART Backend Services recommends
 * {@code private_key_jwt} (asymmetric) client auth; this dev realm uses a shared secret for
 * simplicity, and there is no browser launch, so no {@code authorization_endpoint}.
 */
@RestController
public class SmartConfigurationController {

    static final String ROOT_PATH = "/.well-known/smart-configuration";
    static final String FHIR_BASE_PATH = "/fhir/r4/.well-known/smart-configuration";

    private final String issuer;

    public SmartConfigurationController(@Value("${smart.issuer}") String issuer) {
        this.issuer = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
    }

    @GetMapping(value = {ROOT_PATH, FHIR_BASE_PATH}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> smartConfiguration() {
        String oidc = issuer + "/protocol/openid-connect";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", issuer);
        body.put("jwks_uri", oidc + "/certs");
        body.put("token_endpoint", oidc + "/token");
        body.put("grant_types_supported", List.of("client_credentials"));
        body.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post"));
        body.put("scopes_supported", SmartScopes.supported());
        body.put("introspection_endpoint", oidc + "/token/introspect");
        body.put("revocation_endpoint", oidc + "/revoke");
        body.put("code_challenge_methods_supported", List.of("S256"));
        body.put("capabilities", List.of("client-confidential-symmetric", "permission-v1", "permission-v2"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(body);
    }
}
