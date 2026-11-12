package com.clinicalharmony.patientindex.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code gold-writer.*}: where GoldWriter lives and this service's Keycloak client for calling it. */
@ConfigurationProperties("gold-writer")
public record GoldWriterProperties(String baseUrl, Duration connectTimeout, Duration readTimeout, String tokenUri,
                                   String clientId, String clientSecret, String scope) {
}
