package com.clinicalharmony.gateway.downstream;

import com.clinicalharmony.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The gateway's own access token for downstream services: client_credentials against the
 * Keycloak realm (client {@code api-gateway}, scope {@code clinicalharmony/pipeline.write}),
 * cached until 30 s before expiry. Hand-rolled like patient-index-service's — one grant type
 * doesn't justify spring-security-oauth2-client. Keycloak down → 503 naming "keycloak".
 */
@Component
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);
    private static final long EXPIRY_MARGIN_SECONDS = 30;
    static final String KEYCLOAK = "keycloak";

    private final GatewayProperties.ServiceToken properties;
    private final RestClient http;
    private final Clock clock;

    private String token;
    private Instant expiresAt = Instant.EPOCH;

    @Autowired
    public ServiceTokenProvider(GatewayProperties properties, RestClient.Builder builder) {
        this(properties.serviceToken(), builder, Clock.systemUTC());
    }

    ServiceTokenProvider(GatewayProperties.ServiceToken properties, RestClient.Builder builder, Clock clock) {
        this.properties = properties;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.clone().requestFactory(factory).build();
        this.clock = clock;
    }

    public synchronized String token() {
        if (token == null || !Instant.now(clock).isBefore(expiresAt)) {
            fetch();
        }
        return token;
    }

    /** Drop the cached token (a service answered 401, e.g. Keycloak was recreated with new keys). */
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
                throw new DownstreamException(DownstreamException.Kind.BAD_GATEWAY, KEYCLOAK, null,
                        "Keycloak's token endpoint returned no access_token for client " + properties.clientId(), null);
            }
            long expiresIn = body.path("expires_in").asLong(60);
            token = accessToken;
            expiresAt = Instant.now(clock).plusSeconds(Math.max(0, expiresIn - EXPIRY_MARGIN_SECONDS));
            log.debug("Fetched client_credentials token for {} (expires in {} s)", properties.clientId(), expiresIn);
        } catch (RestClientException e) {
            throw DownstreamErrors.translate(KEYCLOAK, e,
                    "get a service token for client " + properties.clientId() + " from " + properties.tokenUri());
        }
    }
}
