package com.clinicalharmony.gateway.downstream;

import com.clinicalharmony.gateway.config.GatewayProperties;
import com.clinicalharmony.gateway.config.MdcLoggingFilter;
import com.clinicalharmony.gateway.support.StubHttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DownstreamClient over real HTTP against a JDK stub server (token endpoint + services). */
class DownstreamClientTest {

    private final StubHttpServer stub = new StubHttpServer();
    private final AtomicInteger tokensIssued = new AtomicInteger();
    private DownstreamClient client;

    @BeforeEach
    void setUp() {
        stub.route("/token", r -> new StubHttpServer.Response(200,
                "{\"access_token\":\"svc-token-" + tokensIssued.incrementAndGet() + "\",\"expires_in\":300}"));
        client = client(stub.baseUrl(), Duration.ofMillis(800));
        MDC.put(MdcLoggingFilter.REQUEST_ID_KEY, "corr-123");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        stub.close();
    }

    private DownstreamClient client(String baseUrl, Duration readTimeout) {
        Map<String, GatewayProperties.ServiceEndpoint> services = new LinkedHashMap<>();
        for (Downstream d : Downstream.values()) {
            services.put(d.key(), new GatewayProperties.ServiceEndpoint(baseUrl, Duration.ofSeconds(1), readTimeout));
        }
        GatewayProperties properties = new GatewayProperties(new GatewayProperties.Cors(List.of()), null,
                new GatewayProperties.ServiceToken(stub.baseUrl() + "/token", "api-gateway", "secret",
                        "clinicalharmony/pipeline.write"), services);
        RestClient.Builder builder = RestClient.builder();
        return new DownstreamClient(properties, builder, new ServiceTokenProvider(properties, builder));
    }

    @Test
    void securedServiceGetsTheGatewaysOwnTokenAndTheCorrelationId() {
        stub.route("/api/pmi/candidates", r -> new StubHttpServer.Response(200, "[{\"id\":1}]"));

        ResponseEntity<JsonNode> response = client.get(Downstream.PATIENT_INDEX,
                "/api/pmi/candidates?status={s}&limit={l}", "PENDING", 5);

        assertThat(response.getBody().get(0).get("id").asInt()).isEqualTo(1);
        StubHttpServer.Request request = stub.requestsTo("/api/pmi/candidates").get(0);
        assertThat(request.header("Authorization")).isEqualTo("Bearer svc-token-1");
        assertThat(request.header("X-Correlation-Id")).isEqualTo("corr-123");
        assertThat(request.query()).isEqualTo("status=PENDING&limit=5");
        // the token's form request used client_credentials with Basic auth
        StubHttpServer.Request tokenRequest = stub.requestsTo("/token").get(0);
        assertThat(tokenRequest.body()).contains("grant_type=client_credentials")
                .contains("scope=clinicalharmony%2Fpipeline.write");
        assertThat(tokenRequest.header("Authorization")).startsWith("Basic ");
    }

    @Test
    void unsecuredServiceGetsNoBearerToken() {
        stub.route("/api/ontology/map", r -> new StubHttpServer.Response(200, "[]"));

        client.get(Downstream.ONTOLOGY, "/api/ontology/map?source={s}&code={c}&target={t}", "SNOMED", "1", "ICD10");

        assertThat(stub.requestsTo("/api/ontology/map").get(0).header("Authorization")).isNull();
        assertThat(stub.requestsTo("/token")).isEmpty();
    }

    @Test
    void tokenIsCachedAcrossCalls() {
        stub.route("/api/pmi/runs", r -> new StubHttpServer.Response(200, "[]"));

        client.get(Downstream.PATIENT_INDEX, "/api/pmi/runs");
        client.get(Downstream.PATIENT_INDEX, "/api/pmi/runs");

        assertThat(stub.requestsTo("/token")).hasSize(1);
    }

    @Test
    void a401DropsTheCachedTokenAndRetriesOnceWithAFreshOne() {
        AtomicInteger calls = new AtomicInteger();
        stub.route("/api/pmi/match/run", r -> calls.incrementAndGet() == 1
                ? new StubHttpServer.Response(401, "{\"error\":\"Unauthorized\",\"message\":\"expired\"}")
                : new StubHttpServer.Response(200, "{\"run\":{\"id\":7}}"));

        ResponseEntity<JsonNode> response = client.post(Downstream.PATIENT_INDEX, "/api/pmi/match/run", null, null);

        assertThat(response.getBody().at("/run/id").asInt()).isEqualTo(7);
        List<StubHttpServer.Request> runs = stub.requestsTo("/api/pmi/match/run");
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).header("Authorization")).isEqualTo("Bearer svc-token-1");
        assertThat(runs.get(1).header("Authorization")).isEqualTo("Bearer svc-token-2");
    }

    @Test
    void persistent401IsTheGatewaysProblemSoItIs502NotPassedToTheUser() {
        stub.route("/api/pmi/runs", r -> new StubHttpServer.Response(401, "{\"message\":\"bad client\"}"));

        assertThatThrownBy(() -> client.get(Downstream.PATIENT_INDEX, "/api/pmi/runs"))
                .isInstanceOfSatisfying(DownstreamException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DownstreamException.Kind.BAD_GATEWAY);
                    assertThat(e.gatewayStatus().value()).isEqualTo(502);
                    assertThat(e.downstreamStatus()).isEqualTo(401);
                    assertThat(e.service()).isEqualTo("patient-index-service");
                });
    }

    @Test
    void downstream4xxIsPassedThroughWithItsMessage() {
        stub.route("/api/pmi/candidates/9/accept", r -> new StubHttpServer.Response(409,
                "{\"error\":\"Conflict\",\"message\":\"Candidate 9 is ACCEPTED, not PENDING\"}"));

        assertThatThrownBy(() -> client.post(Downstream.PATIENT_INDEX, "/api/pmi/candidates/{id}/accept",
                MediaType.APPLICATION_JSON, Map.of("reviewer", "dana"), 9))
                .isInstanceOfSatisfying(DownstreamException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DownstreamException.Kind.REJECTED);
                    assertThat(e.gatewayStatus().value()).isEqualTo(409);
                    assertThat(e.getMessage()).isEqualTo("Candidate 9 is ACCEPTED, not PENDING");
                });
        assertThat(stub.requestsTo("/api/pmi/candidates/9/accept")).hasSize(1);
    }

    @Test
    void downstream5xxIsBadGatewayAndAPostIsNeverReplayed() {
        stub.route("/api/pmi/match/run", r -> new StubHttpServer.Response(500, "{\"message\":\"boom\"}"));

        assertThatThrownBy(() -> client.post(Downstream.PATIENT_INDEX, "/api/pmi/match/run", null, null))
                .isInstanceOfSatisfying(DownstreamException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DownstreamException.Kind.BAD_GATEWAY);
                    assertThat(e.getMessage()).contains("boom");
                });
        assertThat(stub.requestsTo("/api/pmi/match/run")).hasSize(1);
    }

    @Test
    void slowServiceIsAGatewayTimeout() {
        stub.route("/api/pmi/runs", r -> new StubHttpServer.Response(200, "[]", 2_000));

        assertThatThrownBy(() -> client.get(Downstream.PATIENT_INDEX, "/api/pmi/runs"))
                .isInstanceOfSatisfying(DownstreamException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DownstreamException.Kind.TIMEOUT);
                    assertThat(e.gatewayStatus().value()).isEqualTo(504);
                });
    }

    @Test
    void serviceDownIsUnavailable() {
        DownstreamClient down = client(StubHttpServer.deadBaseUrl(), Duration.ofSeconds(1));

        assertThatThrownBy(() -> down.get(Downstream.ONTOLOGY, "/api/ontology/codes/icd10/E11.9"))
                .isInstanceOfSatisfying(DownstreamException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DownstreamException.Kind.UNAVAILABLE);
                    assertThat(e.gatewayStatus().value()).isEqualTo(503);
                    assertThat(e.service()).isEqualTo("ontology-service");
                });
    }

    @Test
    void keycloakDownIsUnavailableNamingKeycloak() {
        Map<String, GatewayProperties.ServiceEndpoint> services = new LinkedHashMap<>();
        for (Downstream d : Downstream.values()) {
            services.put(d.key(), new GatewayProperties.ServiceEndpoint(stub.baseUrl(), Duration.ofSeconds(1),
                    Duration.ofSeconds(1)));
        }
        GatewayProperties properties = new GatewayProperties(null, null, new GatewayProperties.ServiceToken(
                StubHttpServer.deadBaseUrl() + "/token", "api-gateway", "secret", "s"), services);
        DownstreamClient noKeycloak = new DownstreamClient(properties, RestClient.builder(),
                new ServiceTokenProvider(properties, RestClient.builder()));

        assertThatThrownBy(() -> noKeycloak.get(Downstream.PATIENT_INDEX, "/api/pmi/runs"))
                .isInstanceOfSatisfying(DownstreamException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DownstreamException.Kind.UNAVAILABLE);
                    assertThat(e.service()).isEqualTo("keycloak");
                });
    }
}
