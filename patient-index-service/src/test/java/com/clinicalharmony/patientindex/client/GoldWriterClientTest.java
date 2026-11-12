package com.clinicalharmony.patientindex.client;

import com.clinicalharmony.patientindex.config.MdcLoggingFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GoldWriterClient + ClientCredentialsTokenProvider over real HTTP against a stub server. */
class GoldWriterClientTest {

    private StubHttpServer stub;
    private GoldWriterClient client;
    private final AtomicInteger tokensIssued = new AtomicInteger();

    @BeforeEach
    void setUp() {
        stub = new StubHttpServer().route("/token", r -> new StubHttpServer.Response(200,
                "{\"access_token\":\"tok-" + tokensIssued.incrementAndGet() + "\",\"expires_in\":300}"));
        client = clientFor(stub.baseUrl(), stub.baseUrl() + "/token");
    }

    @AfterEach
    void tearDown() {
        stub.close();
        MDC.clear();
    }

    private static GoldWriterClient clientFor(String goldWriterUrl, String tokenUri) {
        GoldWriterProperties props = new GoldWriterProperties(goldWriterUrl, Duration.ofSeconds(2), Duration.ofSeconds(5),
                tokenUri, "patient-index-service", "s3cret", "clinicalharmony/pipeline.write");
        RestClient.Builder builder = RestClient.builder();
        return new GoldWriterClient(props, builder, new ClientCredentialsTokenProvider(props, builder), new ObjectMapper());
    }

    @Test
    void sendsItsOwnBearerTokenAndTheCallersCorrelationIdAndCachesTheToken() {
        stub.route("/api/fhir/gold/masters/merge", r -> new StubHttpServer.Response(201, "{\"mergeId\": 41}"))
                .route("/api/fhir/gold/masters/18/confirm", r -> new StubHttpServer.Response(200, "{\"id\": 18}"));
        MDC.put(MdcLoggingFilter.REQUEST_ID_KEY, "corr-123");

        JsonNode first = client.merge(11, 16, new BigDecimal("0.9118"), "PROBABILISTIC", "pmi-run-1", Map.of("k", "v"));
        client.confirm(18, "pmi-run-1");

        assertThat(first.get("mergeId").asLong()).isEqualTo(41);
        StubHttpServer.Request token = stub.requestsTo("/token").get(0);
        assertThat(stub.requestsTo("/token")).hasSize(1); // cached for the second call
        assertThat(token.header("Authorization")).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString("patient-index-service:s3cret".getBytes(StandardCharsets.UTF_8)));
        assertThat(token.body()).contains("grant_type=client_credentials").contains("scope=clinicalharmony%2Fpipeline.write");

        StubHttpServer.Request merge = stub.requestsTo("/api/fhir/gold/masters/merge").get(0);
        assertThat(merge.header("Authorization")).isEqualTo("Bearer tok-1");
        assertThat(merge.header("X-Correlation-Id")).isEqualTo("corr-123");
        assertThat(merge.body()).contains("\"survivorMasterId\":11").contains("\"absorbedMasterId\":16")
                .contains("\"matchConfidenceScore\":0.9118").contains("\"evidence\":{\"k\":\"v\"}");
        assertThat(stub.requestsTo("/api/fhir/gold/masters/18/confirm")).hasSize(1);
    }

    @Test
    void a401RefreshesTheTokenAndRetriesOnce() {
        AtomicInteger calls = new AtomicInteger();
        stub.route("/api/fhir/gold/masters/18/confirm", r -> calls.incrementAndGet() == 1
                ? new StubHttpServer.Response(401, "{\"error\":\"Unauthorized\"}")
                : new StubHttpServer.Response(200, "{\"id\":18,\"matchStatus\":\"MATCHED\"}"));

        assertThat(client.confirm(18, "x").get("matchStatus").asText()).isEqualTo("MATCHED");
        assertThat(tokensIssued.get()).isEqualTo(2);
        assertThat(stub.requestsTo("/api/fhir/gold/masters/18/confirm").get(1).header("Authorization")).isEqualTo("Bearer tok-2");
    }

    @Test
    void goldWriterRefusalsKeepStatusAndMessage() {
        stub.route("/api/fhir/gold/masters/merges/9/unmerge", r -> new StubHttpServer.Response(409,
                "{\"error\":\"Conflict\",\"message\":\"Merge 9 was already undone\"}"));

        assertThatThrownBy(() -> client.unmerge(9, "x", "r"))
                .isInstanceOfSatisfying(GoldWriterRejectedException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("Merge 9 was already undone");
                });
    }

    @Test
    void serverErrorsAndUnreachableHostsAreUnavailable() {
        stub.route("/api/fhir/gold/masters/1/confirm", r -> new StubHttpServer.Response(500, "{\"message\":\"boom\"}"));
        assertThatThrownBy(() -> client.confirm(1, "x")).isInstanceOf(GoldWriterUnavailableException.class)
                .hasMessageContaining("500 boom");

        GoldWriterClient nowhere = clientFor("http://127.0.0.1:1", stub.baseUrl() + "/token");
        assertThatThrownBy(() -> nowhere.confirm(1, "x")).isInstanceOf(GoldWriterUnavailableException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void tokenEndpointFailureIsUnavailable() {
        GoldWriterClient noKeycloak = clientFor(stub.baseUrl(), stub.baseUrl() + "/no-such-token-endpoint");
        assertThatThrownBy(() -> noKeycloak.confirm(1, "x")).isInstanceOf(GoldWriterUnavailableException.class)
                .hasMessageContaining("Could not get an access token");
    }
}
