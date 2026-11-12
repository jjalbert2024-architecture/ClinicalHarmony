package com.clinicalharmony.patientindex.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Instant;

/**
 * This service's own access token for GoldWriter: an OAuth 2.0 client_credentials grant against
 * the Keycloak realm (client {@code patient-index-service}, scope {@code clinicalharmony/pipeline.write}),
 * cached until 30 s before it expires. Hand-rolled (≈ 40 lines) rather than pulling in
 * spring-security-oauth2-client for one grant type.
 */
@Component
public class ClientCredentialsTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ClientCredentialsTokenProvider.class);
    private static final long EXPIRY_MARGIN_SECONDS = 30;

    private final GoldWriterProperties properties;
    private final RestClient http;
    private final Clock clock;

    private String token;
    private Instant expiresAt = Instant.EPOCH;

    @Autowired
    public ClientCredentialsTokenProvider(GoldWriterProperties properties, RestClient.Builder builder) {
        this(properties, builder, Clock.systemUTC());
    }

    ClientCredentialsTokenProvider(GoldWriterProperties properties, RestClient.Builder builder, Clock clock) {
        this.properties = properties;
        this.http = builder.clone().build();
        this.clock = clock;
    }

    public synchronized String token() {
        if (token == null || !Instant.now(clock).isBefore(expiresAt)) {
            fetch();
        }
        return token;
    }

    /** Drop the cached token (GoldWriter answered 401, e.g. Keycloak was restarted with new keys). */
    public synchronized void invalidate() {
        token = null;
    }

    private void fetch() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", properties.scope());
        try {
            JsonNode body = http.post().uri(properties.tokenUri())
                    .headers(h -> h.setBasicAuth(properties.clientId(), properties.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            String accessToken = body == null ? null : body.path("access_token").asText(null);
            if (accessToken == null) {
                throw new GoldWriterUnavailableException("Token endpoint returned no access_token", null);
            }
            long expiresIn = body.path("expires_in").asLong(60);
            token = accessToken;
            expiresAt = Instant.now(clock).plusSeconds(Math.max(0, expiresIn - EXPIRY_MARGIN_SECONDS));
            log.debug("Fetched client_credentials token for {} (expires in {} s)", properties.clientId(), expiresIn);
        } catch (RestClientException e) {
            throw new GoldWriterUnavailableException("Could not get an access token for " + properties.clientId()
                    + " from " + properties.tokenUri() + ": " + e.getMessage(), e);
        }
    }
}
