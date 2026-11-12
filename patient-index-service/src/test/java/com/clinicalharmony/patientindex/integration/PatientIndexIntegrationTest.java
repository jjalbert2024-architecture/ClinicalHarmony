package com.clinicalharmony.patientindex.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * patient-index-service end to end against a real Postgres initialized from docs/db-init (incl.
 * migration 21's cross-source duplicates) and a fake GoldWriter over real HTTP. Silver patients
 * get PROVISIONAL masters the way fhir-compliance-service's GoldWriter creates them on build.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@Import(TestJwts.class)
class PatientIndexIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("clinicalharmony")
            .withUsername("clinicalharmony")
            .withPassword("clinicalharmony")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("..", "docs", "db-init").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/");

    static final FakeGoldWriter GOLD_WRITER = new FakeGoldWriter();

    @DynamicPropertySource
    static void goldWriter(DynamicPropertyRegistry registry) {
        registry.add("gold-writer.base-url", GOLD_WRITER::baseUrl);
        registry.add("gold-writer.token-uri", () -> GOLD_WRITER.baseUrl() + "/token");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();
    /** silver.patients.source_patient_id → its provisional master id. */
    private final Map<String, Long> master = new HashMap<>();
    private final Map<String, Long> silver = new HashMap<>();

    @BeforeEach
    void provisionalMasters() {
        GOLD_WRITER.useDatabase(jdbc);
        GOLD_WRITER.server().reset();
        // What GoldWriter.ensureProvisionalMaster does on build: one PROVISIONAL master per Silver patient.
        List<Long> unmastered = jdbc.queryForList("""
                SELECT id FROM silver.patients p
                WHERE NOT EXISTS (SELECT 1 FROM gold.patient_source_xref x WHERE x.silver_patient_id = p.id)
                ORDER BY id
                """, Long.class);
        for (long silverId : unmastered) {
            Long masterId = jdbc.queryForObject("""
                    INSERT INTO gold.master_patients (first_name, last_name, date_of_birth, gender, match_status)
                    SELECT first_name, last_name, date_of_birth, gender, 'PROVISIONAL' FROM silver.patients WHERE id = ?
                    RETURNING id
                    """, Long.class, silverId);
            jdbc.update("INSERT INTO gold.patient_source_xref (master_patient_id, silver_patient_id) VALUES (?, ?)",
                    masterId, silverId);
        }
        jdbc.query("SELECT p.source_patient_id, p.id, x.master_patient_id FROM silver.patients p "
                + "JOIN gold.patient_source_xref x ON x.silver_patient_id = p.id", rs -> {
            silver.put(rs.getString(1), rs.getLong(2));
            master.put(rs.getString(1), rs.getLong(3));
        });
    }

    private TestRestTemplate http(String... scopes) {
        RestTemplateBuilder builder = new RestTemplateBuilder().rootUri("http://localhost:" + port);
        if (scopes.length > 0) {
            builder = builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwts.mint(scopes));
        }
        return new TestRestTemplate(builder);
    }

    private ResponseEntity<String> post(String path, String json, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (correlationId != null) {
            headers.set("X-Correlation-Id", correlationId);
        }
        return http("clinicalharmony/pipeline.write").postForEntity(path, new HttpEntity<>(json, headers), String.class);
    }

    private JsonNode body(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private String status(long masterId) {
        return jdbc.queryForObject("SELECT match_status FROM gold.master_patients WHERE id = ?", String.class, masterId);
    }

    private long masterOfSilver(String sourcePatientId) {
        return jdbc.queryForObject("SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = ?",
                Long.class, silver.get(sourcePatientId));
    }

    @Test
    void runReviewUnmergeAndRerunOverTheSeedDuplicates() throws Exception {
        // ---- run 1 ----
        ResponseEntity<String> run1 = post("/api/pmi/match/run", null, "it-run-1");
        assertThat(run1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(run1.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("it-run-1");
        JsonNode r1 = body(run1);
        assertThat(r1.at("/run/status").asText()).isEqualTo("COMPLETED");
        assertThat(r1.at("/run/autoMerges").asInt()).isEqualTo(3);
        assertThat(r1.at("/run/queuedForReview").asInt()).isEqualTo(2);
        assertThat(r1.at("/run/confirmedSingletons").asInt()).isEqualTo(2);

        // every GoldWriter call carried the PMI's own token and the caller's correlation id
        List<com.clinicalharmony.patientindex.client.StubHttpServer.Request> goldCalls =
                GOLD_WRITER.server().requestsTo("/api/fhir/gold/masters");
        assertThat(goldCalls).hasSize(5).allSatisfy(c -> {
            assertThat(c.header("Authorization")).isEqualTo("Bearer pmi-service-token");
            assertThat(c.header("X-Correlation-Id")).isEqualTo("it-run-1");
        });

        // Garcia: PMI-0001 → SEED-0001 (deterministic). Wilson: PMI-0002 + PMI-0006 → SEED-0002.
        long garcia = master.get("SEED-0001");
        long wilson = master.get("SEED-0002");
        assertThat(masterOfSilver("PMI-0001")).isEqualTo(garcia);
        assertThat(masterOfSilver("PMI-0002")).isEqualTo(wilson);
        assertThat(masterOfSilver("PMI-0006")).isEqualTo(wilson);
        assertThat(status(master.get("PMI-0001"))).isEqualTo("MERGED");
        assertThat(status(garcia)).isEqualTo("MATCHED");
        Map<String, Object> jimMerge = jdbc.queryForMap("SELECT match_method, match_confidence_score::text AS score, "
                + "evidence->>'method' AS ev FROM gold.master_patient_merges WHERE absorbed_master_id = ?", master.get("PMI-0002"));
        assertThat(jimMerge).containsEntry("match_method", "PROBABILISTIC").containsEntry("score", "0.9118");

        // review: DOB-swapped Maria and twin Mario, each against the Garcia master; singletons confirmed
        assertThat(status(master.get("SEED-0003"))).isEqualTo("MATCHED");
        assertThat(status(master.get("PMI-0005"))).isEqualTo("MATCHED");
        assertThat(status(master.get("PMI-0003"))).isEqualTo("PROVISIONAL");
        assertThat(status(master.get("PMI-0004"))).isEqualTo("PROVISIONAL");
        JsonNode pending = objectMapper.readTree(http("system/*.rs").getForObject("/api/pmi/candidates", String.class));
        assertThat(pending).hasSize(2);
        Map<Long, JsonNode> byOther = new HashMap<>();
        for (JsonNode c : pending) {
            assertThat(c.get("masterAId").asLong()).isEqualTo(garcia);
            byOther.put(c.get("masterBId").asLong(), c);
        }
        JsonNode swapCandidate = byOther.get(master.get("PMI-0003"));
        JsonNode twinCandidate = byOther.get(master.get("PMI-0004"));
        assertThat(swapCandidate.get("score").decimalValue()).isEqualByComparingTo("0.6471");
        assertThat(twinCandidate.get("score").decimalValue()).isEqualByComparingTo("0.7143");
        assertThat(twinCandidate.at("/breakdown/fields").findValuesAsText("outcome")).contains("DISAGREE");

        // ---- run 2 is a no-op: nothing new, the open review items aren't duplicated ----
        JsonNode r2 = body(post("/api/pmi/match/run", null, null));
        assertThat(r2.at("/run/provisionalMasters").asInt()).isEqualTo(2);
        assertThat(r2.at("/run/autoMerges").asInt()).isZero();
        assertThat(r2.at("/run/queuedForReview").asInt()).isZero();
        assertThat(r2.at("/run/confirmedSingletons").asInt()).isZero();

        // ---- reject the twin: excluded, and with nothing else pending, Mario's master is confirmed ----
        JsonNode rejected = body(post("/api/pmi/candidates/" + twinCandidate.get("id").asLong() + "/reject",
                "{\"reviewer\": \"dana\", \"note\": \"twin brother, different sex\"}", null));
        assertThat(rejected.at("/candidate/status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("confirmedMasterIds").get(0).asLong()).isEqualTo(master.get("PMI-0004"));
        assertThat(status(master.get("PMI-0004"))).isEqualTo("MATCHED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pmi.match_exclusions WHERE reason = 'REVIEW_REJECTED'",
                Integer.class)).isEqualTo(1);
        ResponseEntity<String> again = post("/api/pmi/candidates/" + twinCandidate.get("id").asLong() + "/accept",
                "{\"reviewer\": \"dana\"}", null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // ---- accept the DOB swap: merged (MANUAL) into the Garcia master ----
        JsonNode accepted = body(post("/api/pmi/candidates/" + swapCandidate.get("id").asLong() + "/accept",
                "{\"reviewer\": \"dana\", \"note\": \"DOB keyed day-first at registration\"}", null));
        long acceptMergeId = accepted.at("/merge/mergeId").asLong();
        assertThat(accepted.at("/candidate/status").asText()).isEqualTo("ACCEPTED");
        assertThat(masterOfSilver("PMI-0003")).isEqualTo(garcia);
        assertThat(jdbc.queryForObject("SELECT match_method || ' ' || decided_by FROM gold.master_patient_merges WHERE id = ?",
                String.class, acceptMergeId)).isEqualTo("MANUAL reviewer:dana");

        // ---- master view ----
        JsonNode view = objectMapper.readTree(http("system/*.rs").getForObject("/api/pmi/masters/" + garcia, String.class));
        assertThat(view.at("/master/matchStatus").asText()).isEqualTo("MATCHED");
        assertThat(view.get("sources").findValuesAsText("sourcePatientId")).containsExactlyInAnyOrder("SEED-0001", "PMI-0001", "PMI-0003");
        assertThat(view.get("merges")).hasSize(2);
        assertThat(view.get("pendingCandidates")).isEmpty();
        JsonNode absorbedView = objectMapper.readTree(http("system/*.rs")
                .getForObject("/api/pmi/masters/" + master.get("PMI-0001"), String.class));
        assertThat(absorbedView.at("/mergedInto/survivorMasterId").asLong()).isEqualTo(garcia);

        // ---- unmerge the accepted merge: excluded from both remaining Garcia records ----
        ResponseEntity<String> unmerged = post("/api/pmi/merges/" + acceptMergeId + "/unmerge",
                "{\"reviewer\": \"lee\", \"reason\": \"different person after chart review\"}", null);
        assertThat(unmerged.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(unmerged).get("exclusionsAdded").asInt()).isEqualTo(2);
        assertThat(masterOfSilver("PMI-0003")).isEqualTo(master.get("PMI-0003"));
        assertThat(post("/api/pmi/merges/" + acceptMergeId + "/unmerge", "{\"reviewer\": \"lee\", \"reason\": \"again\"}", null)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // ---- a later run respects every human decision: nothing re-merged or re-queued ----
        JsonNode r3 = body(post("/api/pmi/match/run", null, null));
        assertThat(r3.at("/run/autoMerges").asInt()).isZero();
        assertThat(r3.at("/run/queuedForReview").asInt()).isZero();
        assertThat(masterOfSilver("PMI-0003")).isEqualTo(master.get("PMI-0003"));

        // ---- ad-hoc score, runs list ----
        JsonNode score = objectMapper.readTree(http("system/*.rs").getForObject(
                "/api/pmi/score?a=" + silver.get("SEED-0001") + "&b=" + silver.get("PMI-0004"), String.class));
        assertThat(score.get("decision").asText()).isEqualTo("REVIEW");
        assertThat(score.get("score").decimalValue()).isEqualByComparingTo("0.7143");
        JsonNode runs = objectMapper.readTree(http("system/*.rs").getForObject("/api/pmi/runs", String.class));
        assertThat(runs.get(0).get("triggeredBy").asText()).isEqualTo("api:clinicalharmony-app");
    }

    @Test
    void authAndNotFound() throws Exception {
        assertThat(http().getForEntity("/api/pmi/masters", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> forbidden = http("system/*.rs").postForEntity("/api/pmi/match/run", null, String.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(body(forbidden).get("message").asText()).contains("clinicalharmony/pipeline.write");
        assertThat(http().getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> missing = http("system/*.rs").getForEntity("/api/pmi/masters/999999", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(body(missing).get("error").asText()).isEqualTo("Not found");
        assertThat(post("/api/pmi/candidates/999999/accept", "{\"reviewer\": \"dana\"}", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post("/api/pmi/merges/999999/unmerge", "{\"reviewer\": \"dana\", \"reason\": \"x\"}", null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
