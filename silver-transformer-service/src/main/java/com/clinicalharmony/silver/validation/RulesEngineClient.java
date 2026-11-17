package com.clinicalharmony.silver.validation;

import com.clinicalharmony.silver.config.SilverProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Map;

/**
 * {@code POST /api/rules/evaluate} on rules-engine-service, which runs its 7 clinical rules and calls
 * ontology-service for code validation; both persist their findings in {@code silver.validation_reports}.
 * rules-engine has no auth (internal service). The correlation id is forwarded as {@code X-Correlation-Id}.
 * Any exception here means "couldn't validate now": the caller leaves the patient PENDING_VALIDATION.
 */
@Component
public class RulesEngineClient {

    private final RestClient client;

    public RulesEngineClient(RestClient.Builder builder, SilverProperties props) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(props.rulesEngine().connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.rulesEngine().readTimeout());
        this.client = builder.baseUrl(props.rulesEngine().baseUrl()).requestFactory(factory).build();
    }

    public JsonNode evaluate(Map<String, Object> patientRecord) {
        String correlationId = MDC.get("correlationId");
        return client.post().uri("/api/rules/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (correlationId != null && !correlationId.isBlank()) {
                        h.set("X-Correlation-Id", correlationId);
                    }
                })
                .body(patientRecord)
                .retrieve()
                .body(JsonNode.class);
    }
}
