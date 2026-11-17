package com.clinicalharmony.silver.integration;

import com.clinicalharmony.silver.config.Topics;
import com.clinicalharmony.silver.hl7.Hl7Samples;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * silver-transformer-service end to end: Postgres initialized from docs/db-init (incl. migration 24),
 * a real Kafka broker (cp-kafka 7.6.1, KRaft — the compose image), and a stand-in rules-engine on a JDK
 * HttpServer that records each request and, like the real one, writes a finding into
 * silver.validation_reports (a MEDICATION_ALLERGY_CONFLICT when a medication's RxNorm code equals an
 * allergy's). Bronze rows are inserted the way bronze-listener-service writes them, then announced on
 * clinicalharmony.bronze.ingested the way its outbox relay does.
 */
@SpringBootTest(properties = {
        "silver.retry.initial-interval=100ms",
        "silver.outbox.poll-interval=200ms",
        "silver.validation.sweep-interval=500ms",
        "silver.validation.min-age=0s",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@Import(SilverTransformerIntegrationTest.TopicConfig.class)
class SilverTransformerIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("clinicalharmony")
            .withUsername("clinicalharmony")
            .withPassword("clinicalharmony")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("..", "docs", "db-init").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/");

    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.1");

    // ---- stand-in rules-engine ----------------------------------------------------------------------
    static final HttpServer RULES = startRulesEngine();
    static volatile boolean rulesUp = true;
    static volatile JdbcTemplate rulesDb;   // set once the context is up
    static final List<JsonNode> RULES_REQUESTS = new CopyOnWriteArrayList<>();
    static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void rulesEngine(DynamicPropertyRegistry registry) {
        registry.add("silver.rules-engine.base-url", () -> "http://localhost:" + RULES.getAddress().getPort());
    }

    static HttpServer startRulesEngine() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/api/rules/evaluate", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (!rulesUp || rulesDb == null) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                JsonNode request = JSON.readTree(body);
                RULES_REQUESTS.add(request);
                long patientId = request.get("patientId").asLong();
                List<Long> ids = new ArrayList<>();
                for (JsonNode med : request.path("medications")) {
                    for (JsonNode allergy : request.path("allergies")) {
                        if (med.path("rxnormCode").asText().equals(allergy.path("rxnormCode").asText())) {
                            ids.add(rulesDb.queryForObject("""
                                    INSERT INTO silver.validation_reports (entity_type, entity_id, patient_id, rule_code, severity, message)
                                    VALUES ('MEDICATION', ?, ?, 'MEDICATION_ALLERGY_CONFLICT', 'ERROR', 'conflict') RETURNING id
                                    """, Long.class, med.get("id").asLong(), patientId));
                        }
                    }
                }
                byte[] out = JSON.writeValueAsBytes(Map.of("patientId", patientId, "allValid", ids.isEmpty(),
                        "ontologyValidationAvailable", true, "validationReportIds", ids));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class TopicConfig {
        /** In compose bronze-listener-service declares this topic; here the test does. */
        @Bean
        NewTopic bronzeIngested() {
            return TopicBuilder.name(Topics.BRONZE_INGESTED).partitions(3).replicas(1).build();
        }
    }

    // ---- tail consumer -----------------------------------------------------------------------------
    static final Map<String, List<ConsumerRecord<String, String>>> SEEN = new ConcurrentHashMap<>();
    static volatile boolean tailing = true;
    static volatile boolean tailReady;
    static Thread tail;

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JdbcTemplate jdbc;

    /** Started once the context (and so the Boot-managed containers) is up — not in @BeforeAll. */
    @BeforeEach
    void setUp() {
        rulesDb = jdbc;
        rulesUp = true;
        synchronized (SEEN) {
            if (tail == null) {
                tail = new Thread(SilverTransformerIntegrationTest::tailLoop, "test-tail");
                tail.setDaemon(true);
                tail.start();
            }
        }
        await(() -> tailReady, "test tail consumer assigned");
    }

    static void tailLoop() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-tail-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.METADATA_MAX_AGE_CONFIG, 1000);
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            c.subscribe(List.of(Topics.SILVER_PATIENT_CHANGED, Topics.BRONZE_INGESTED_DLT));
            while (tailing) {
                if (!c.assignment().isEmpty()) {
                    tailReady = true;
                }
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(200))) {
                    SEEN.computeIfAbsent(r.topic(), t -> Collections.synchronizedList(new ArrayList<>())).add(r);
                }
            }
        }
    }

    @AfterAll
    static void stop() {
        tailing = false;
        RULES.stop(0);
    }

    // ---- tests -------------------------------------------------------------------------------------

    @Test
    void admitResultAndOrderBuildOneSilverPatientAndAreAnnounced() throws Exception {
        String mrn = mrn();
        String key = "CITY_HOSPITAL|" + mrn;
        long adt = bronze(patient(Hl7Samples.adtA01("A-" + mrn), mrn), "CITY_HOSPITAL");
        long oru = bronze(patient(Hl7Samples.oruR01("O-" + mrn), mrn), "REGIONAL_LAB");
        long rde = bronze(patient(Hl7Samples.rdeO11("R-" + mrn), mrn), "CITY_PHARMACY");
        String e1 = announce(adt, key, "corr-a");
        announce(oru, key, "corr-o");
        announce(rde, key, "corr-r");

        List<ConsumerRecord<String, String>> events = awaitChanged(key, 3);
        long patientId = JSON.readTree(events.get(0).value()).get("silverPatientId").asLong();
        assertThat(events).allSatisfy(e -> {
            assertThat(e.key()).isEqualTo(key);
            assertThat(header(e, "event-type")).isEqualTo("silver.patient.changed");
            assertThat(header(e, "producer")).isEqualTo("silver-transformer-service");
        });
        JsonNode first = JSON.readTree(events.get(0).value());
        assertThat(first.get("change").asText()).isEqualTo("CREATED");
        assertThat(first.get("rawMessageId").asLong()).isEqualTo(adt);
        assertThat(first.get("hl7MessageType").asText()).isEqualTo("ADT^A01");
        assertThat(first.at("/counts/conditions").asInt()).isEqualTo(2);
        assertThat(first.at("/counts/allergies").asInt()).isEqualTo(1);
        assertThat(header(events.get(0), "correlation-id")).isEqualTo("corr-a");
        assertThat(header(events.get(0), "causation-id")).isEqualTo(e1);
        assertThat(JSON.readTree(events.get(1).value()).get("change").asText()).isEqualTo("UPDATED");
        assertThat(JSON.readTree(events.get(2).value()).get("silverPatientId").asLong()).isEqualTo(patientId);

        // one Silver patient, keyed on the HOSPITAL's MRN even though the lab and pharmacy sent two of the messages
        assertThat(jdbc.queryForObject("SELECT count(*) FROM silver.patients WHERE source_patient_id = ?", Long.class, mrn))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_system FROM silver.patients WHERE id = ?", String.class, patientId))
                .isEqualTo("CITY_HOSPITAL");
        assertThat(count("silver.conditions", patientId)).isEqualTo(2);
        assertThat(count("silver.observations", patientId)).isEqualTo(3);
        assertThat(count("silver.medications", patientId)).isEqualTo(1);
        assertThat(count("silver.allergies", patientId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reference_high::text FROM silver.observations WHERE patient_id = ? AND code = '2345-7'",
                String.class, patientId)).isEqualTo("99.0000");
        for (long id : List.of(adt, oru, rde)) {
            assertThat(jdbc.queryForObject("SELECT processing_status FROM bronze.raw_messages WHERE id = ?", String.class, id))
                    .isEqualTo("PROCESSED");
        }
        assertThat(jdbc.queryForObject("SELECT raw_message_id FROM silver.medications WHERE patient_id = ?", Long.class, patientId))
                .isEqualTo(rde);

        // validated with the WHOLE record; the amoxicillin order conflicts with the amoxicillin allergy
        await(() -> "VALIDATED".equals(status(patientId)) && openFindings(patientId) == 1, "validated with one open conflict");
        JsonNode last = RULES_REQUESTS.stream().filter(r -> r.get("patientId").asLong() == patientId)
                .reduce((a, b) -> b).orElseThrow();
        assertThat(last.get("gender").asText()).isEqualTo("male");
        assertThat(last.get("dateOfBirth").asText()).isEqualTo("1935-02-25");
        assertThat(last.get("allergies").get(0).get("rxnormCode").asText()).isEqualTo("723");
        assertThat(last.get("observations").get(0).get("referenceHigh").asDouble()).isEqualTo(99.0);

        // a later message re-validates: the conflict is reported again, the old finding is retired
        long adt2 = bronze(patient(Hl7Samples.adtA08("A2-" + mrn), mrn), "CITY_HOSPITAL");
        announce(adt2, key, "corr-a2");
        awaitChanged(key, 4);
        await(() -> resolvedFindings(patientId) == 1 && openFindings(patientId) == 1, "old finding superseded");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bronze.outbox_events", Long.class)).isZero(); // not ours
        await(() -> jdbc.queryForObject("SELECT count(*) FROM silver.outbox_events WHERE aggregate_id = ? AND published_at IS NULL",
                Long.class, patientId) == 0, "outbox drained");
    }

    @Test
    void adtUpdateRefreshesDemographicsWithoutDuplicatingListsItRepeats() {
        String mrn = mrn();
        String key = "CITY_HOSPITAL|" + mrn;
        announce(bronze(patient(Hl7Samples.adtA01("A-" + mrn), mrn), "CITY_HOSPITAL"), key, "c1");
        long patientId = JSON_id(awaitChanged(key, 1).get(0));
        announce(bronze(patient(Hl7Samples.adtA08("B-" + mrn), mrn), "CITY_HOSPITAL"), key, "c2");
        awaitChanged(key, 2);

        assertThat(jdbc.queryForObject("SELECT phone FROM silver.patients WHERE id = ?", String.class, patientId))
                .isEqualTo("5550001111");
        assertThat(jdbc.queryForList("SELECT code FROM silver.conditions WHERE patient_id = ? ORDER BY code", String.class,
                patientId)).containsExactly("E11.9", "I10", "K21.9");
        assertThat(count("silver.allergies", patientId)).isEqualTo(1);
    }

    @Test
    void unmappableMessageFailsTheBronzeRowAndWritesNothing() {
        String mrn = mrn();
        String raw = patient(Hl7Samples.adtA01("F-" + mrn), mrn).replace("||19350225|M|", "|||M|");
        long id = bronze(raw, "CITY_HOSPITAL");
        announce(id, "CITY_HOSPITAL|" + mrn, "corr-f");

        await(() -> "FAILED".equals(jdbc.queryForObject("SELECT processing_status FROM bronze.raw_messages WHERE id = ?",
                String.class, id)), "Bronze row FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM bronze.raw_messages WHERE id = ?", String.class, id))
                .contains("PID-7");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM silver.patients WHERE source_patient_id = ?", Long.class, mrn))
                .isZero();
        assertThat(changedFor("CITY_HOSPITAL|" + mrn)).isEmpty();
    }

    @Test
    void redeliveredEventIsSkippedWithoutASecondAnnouncement() throws Exception {
        String mrn = mrn();
        String key = "CITY_HOSPITAL|" + mrn;
        long id = bronze(patient(Hl7Samples.oruR01("D-" + mrn), mrn), "REGIONAL_LAB");
        announce(id, key, "corr-d1");
        awaitChanged(key, 1);
        announce(id, key, "corr-d2");   // Kafka redelivery / relay re-send after a crash
        long marker = bronze(patient(Hl7Samples.adtA08("M-" + mrn), mrn), "CITY_HOSPITAL");
        announce(marker, key, "corr-d3");   // same key → same partition → processed after the duplicate
        awaitChanged(key, 2);
        Thread.sleep(800);

        assertThat(changedFor(key)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM silver.observations WHERE raw_message_id = ?", Long.class, id))
                .isEqualTo(3);
    }

    @Test
    void rulesEngineDownLeavesThePatientPendingUntilTheSweeperValidatesIt() {
        rulesUp = false;
        String mrn = mrn();
        String key = "CITY_HOSPITAL|" + mrn;
        announce(bronze(patient(Hl7Samples.adtA01("P-" + mrn), mrn), "CITY_HOSPITAL"), key, "corr-p");
        long patientId = JSON_id(awaitChanged(key, 1).get(0));

        // Silver was still written (fail-open) and the event still went out
        assertThat(count("silver.conditions", patientId)).isEqualTo(2);
        await(() -> jdbc.queryForObject("SELECT validation_attempts FROM silver.patients WHERE id = ?", Integer.class,
                patientId) >= 1, "first validation attempt made");
        assertThat(status(patientId)).isEqualTo("PENDING_VALIDATION");
        assertThat(jdbc.queryForObject("SELECT validation_error FROM silver.patients WHERE id = ?", String.class, patientId))
                .contains("rules-engine-service unavailable");

        rulesUp = true;
        await(() -> "VALIDATED".equals(status(patientId)), "sweeper validated the patient");
        assertThat(jdbc.queryForObject("SELECT validation_error FROM silver.patients WHERE id = ?", String.class, patientId))
                .isNull();
    }

    @Test
    void brokenOrUnknownEventsAreDeadLettered() {
        ProducerRecord<String, String> junk = new ProducerRecord<>(Topics.BRONZE_INGESTED, "k-x", "{not json");
        junk.headers().add(new RecordHeader("correlation-id", "corr-x1".getBytes(StandardCharsets.UTF_8)));
        kafka.send(junk).join();
        assertThat(header(awaitDlt("corr-x1"), "kafka_dlt-exception-cause-fqcn")).endsWith("InvalidEventException");

        announce(987_654_321L, "k-y", "corr-x2");   // no such Bronze row
        assertThat(header(awaitDlt("corr-x2"), "kafka_dlt-exception-cause-fqcn")).endsWith("InvalidEventException");
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static String mrn() {
        return "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private static String patient(String message, String mrn) {
        return message.replace("CH645175", mrn);
    }

    /** A PENDING Bronze row, as bronze-listener-service writes it. */
    private long bronze(String raw, String source) {
        return jdbc.queryForObject("""
                INSERT INTO bronze.raw_messages (source_system, message_type, message_format, raw_payload, payload_checksum,
                    processing_status, intake_channel)
                VALUES (?, 'HL7V2', 'TEXT', ?, md5(?) || md5(?), 'PENDING', 'MLLP_KAFKA') RETURNING id
                """, Long.class, source, raw, raw, raw + "x");
    }

    /** A clinicalharmony.bronze.ingested event, as bronze-listener-service's outbox relay sends it. */
    private String announce(long rawMessageId, String key, String correlationId) {
        String eventId = UUID.randomUUID().toString();
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.BRONZE_INGESTED, key,
                "{\"rawMessageId\":" + rawMessageId + ",\"messageType\":\"HL7V2\",\"receivedAt\":\"" + Instant.now() + "\"}");
        record.headers().add(new RecordHeader("event-id", eventId.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("correlation-id", correlationId.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("event-type", "bronze.ingested".getBytes(StandardCharsets.UTF_8)));
        kafka.send(record).join();
        return eventId;
    }

    private long count(String table, long patientId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE patient_id = ?", Long.class, patientId);
    }

    private String status(long patientId) {
        return jdbc.queryForObject("SELECT validation_status FROM silver.patients WHERE id = ?", String.class, patientId);
    }

    private long openFindings(long patientId) {
        return jdbc.queryForObject("SELECT count(*) FROM silver.validation_reports WHERE patient_id = ? AND NOT is_resolved",
                Long.class, patientId);
    }

    private long resolvedFindings(long patientId) {
        return jdbc.queryForObject("SELECT count(*) FROM silver.validation_reports WHERE patient_id = ? AND is_resolved",
                Long.class, patientId);
    }

    private static long JSON_id(ConsumerRecord<String, String> r) {
        try {
            return JSON.readTree(r.value()).get("silverPatientId").asLong();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<ConsumerRecord<String, String>> changedFor(String key) {
        List<ConsumerRecord<String, String>> all = SEEN.getOrDefault(Topics.SILVER_PATIENT_CHANGED, List.of());
        synchronized (all) {
            return all.stream().filter(r -> key.equals(r.key())).toList();
        }
    }

    private static List<ConsumerRecord<String, String>> awaitChanged(String key, int n) {
        await(() -> changedFor(key).size() >= n, n + " silver.patient.changed event(s) for " + key);
        return changedFor(key);
    }

    private static ConsumerRecord<String, String> awaitDlt(String correlationId) {
        await(() -> dlt(correlationId) != null, "dead letter for " + correlationId);
        return dlt(correlationId);
    }

    private static ConsumerRecord<String, String> dlt(String correlationId) {
        List<ConsumerRecord<String, String>> all = SEEN.getOrDefault(Topics.BRONZE_INGESTED_DLT, List.of());
        synchronized (all) {
            return all.stream().filter(r -> correlationId.equals(header(r, "correlation-id"))).findFirst().orElse(null);
        }
    }

    private static String header(ConsumerRecord<?, ?> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // not there yet
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
