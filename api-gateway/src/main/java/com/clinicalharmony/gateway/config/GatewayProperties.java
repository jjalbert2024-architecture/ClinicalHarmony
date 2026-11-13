package com.clinicalharmony.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * {@code gateway.*}: CORS, what the UI needs to log in, the gateway's own Keycloak client, and
 * where each downstream service lives (keys: ingestion, ontology, rules-engine, fhir-compliance,
 * patient-index — see {@link com.clinicalharmony.gateway.downstream.Downstream}).
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(Cors cors, Ui ui, ServiceToken serviceToken, Map<String, ServiceEndpoint> services) {

    public record Cors(List<String> allowedOrigins) {
    }

    /** Public login settings for the SPA: Keycloak issuer + the public PKCE client id. */
    public record Ui(String issuer, String clientId) {
    }

    /** The gateway's own confidential client (client_credentials) for calling downstream services. */
    public record ServiceToken(String tokenUri, String clientId, String clientSecret, String scope) {
    }

    public record ServiceEndpoint(String baseUrl, Duration connectTimeout, Duration readTimeout) {
    }
}
