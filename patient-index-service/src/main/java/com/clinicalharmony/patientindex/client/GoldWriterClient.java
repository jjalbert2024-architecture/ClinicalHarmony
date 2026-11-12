package com.clinicalharmony.patientindex.client;

import com.clinicalharmony.patientindex.config.MdcLoggingFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * HTTP client for GoldWriter's master-patient endpoints in fhir-compliance-service
 * ({@code /api/fhir/gold/masters/**}). Every request carries this service's own Bearer token and
 * the current request's {@code X-Correlation-Id}, so one id spans both services' logs.
 *
 * <p>A 401 from GoldWriter drops the cached token and retries once. Other 4xx →
 * {@link GoldWriterRejectedException} (status + GoldWriter's message); network errors and 5xx →
 * {@link GoldWriterUnavailableException}. Responses come back as JsonNode — the services share no
 * code, same as rules-engine-service's OntologyServiceClient.
 */
@Component
public class GoldWriterClient {

    private static final Logger log = LoggerFactory.getLogger(GoldWriterClient.class);

    private final RestClient http;
    private final ClientCredentialsTokenProvider tokens;
    private final ObjectMapper objectMapper;

    public GoldWriterClient(GoldWriterProperties properties, RestClient.Builder builder,
                            ClientCredentialsTokenProvider tokens, ObjectMapper objectMapper) {
        // JDK HttpClient, not HttpURLConnection: the latter throws HttpRetryException on a 401 to a
        // streamed POST, which would break the refresh-token-and-retry path below.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        this.http = builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = MDC.get(MdcLoggingFilter.REQUEST_ID_KEY);
                    if (correlationId != null) {
                        request.getHeaders().set(MdcLoggingFilter.CORRELATION_HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
        this.tokens = tokens;
        this.objectMapper = objectMapper;
    }

    /** POST /api/fhir/gold/masters/merge → GoldWriter's MasterMergeResult. */
    public JsonNode merge(long survivorMasterId, long absorbedMasterId, BigDecimal score, String matchMethod,
                          String decidedBy, Object evidence) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("survivorMasterId", survivorMasterId);
        body.put("absorbedMasterId", absorbedMasterId);
        body.put("matchConfidenceScore", score);
        body.put("matchMethod", matchMethod);
        body.put("decidedBy", decidedBy);
        body.put("evidence", evidence);
        return post("/api/fhir/gold/masters/merge", body, "merge master " + absorbedMasterId + " into " + survivorMasterId);
    }

    /** POST /api/fhir/gold/masters/{id}/confirm → the master's snapshot. */
    public JsonNode confirm(long masterId, String decidedBy) {
        return post("/api/fhir/gold/masters/" + masterId + "/confirm", Map.of("decidedBy", decidedBy),
                "confirm master " + masterId);
    }

    /** POST /api/fhir/gold/masters/merges/{mergeId}/unmerge → GoldWriter's MasterUnmergeResult. */
    public JsonNode unmerge(long mergeId, String decidedBy, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decidedBy", decidedBy);
        body.put("reason", reason);
        return post("/api/fhir/gold/masters/merges/" + mergeId + "/unmerge", body, "unmerge merge " + mergeId);
    }

    private JsonNode post(String path, Object body, String what) {
        try {
            return withRetryOn401(() -> http.post().uri(path)
                    .headers(h -> h.setBearerAuth(tokens.token()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class));
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            String message = messageOf(e.getResponseBodyAsString());
            if (status.is4xxClientError()) {
                log.info("GoldWriter refused to {}: {} {}", what, status.value(), message);
                throw new GoldWriterRejectedException(status.value(), message);
            }
            throw new GoldWriterUnavailableException("GoldWriter failed to " + what + ": " + status.value() + " " + message, e);
        } catch (RestClientException e) {
            throw new GoldWriterUnavailableException("GoldWriter unreachable trying to " + what + ": " + e.getMessage(), e);
        }
    }

    private JsonNode withRetryOn401(Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 401) {
                throw e;
            }
            log.info("GoldWriter rejected the cached token (401); fetching a new one and retrying once");
            tokens.invalidate();
            return call.get();
        }
    }

    private String messageOf(String responseBody) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            if (node != null && node.hasNonNull("message")) {
                return node.get("message").asText();
            }
        } catch (Exception ignored) {
            // not JSON — fall through to the raw body
        }
        return responseBody == null || responseBody.isBlank() ? "(no body)" : responseBody;
    }
}
