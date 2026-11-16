package com.clinicalharmony.bronze.integration;

import com.clinicalharmony.bronze.config.Topics;
import com.clinicalharmony.bronze.hl7.Hl7Samples;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * bronze-listener-service end to end: real Postgres initialized from docs/db-init (incl. migration 23)
 * and a real Kafka broker (cp-kafka 7.6.1, KRaft — the compose image). Records are produced exactly as
 * ingestion-service's Hl7RawPublisher produces them.
 */
@SpringBootTest(properties = {
        "bronze.retry.initial-interval=100ms",
        "bronze.outbox.poll-interval=200ms",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
@Import(BronzeListenerIntegrationTest.TopicConfig.class)
class BronzeListenerIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("clinicalharmony")
            .withUsername("clinicalharmony")
            .withPassword("clinicalharmony")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("..", "docs", "db-init").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/");

    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.1");

    /** Everything that reaches bronze.ingested or the DLT, read by a background test consumer. */
    static final Map<String, List<ConsumerRecord<String, String>>> SEEN = new ConcurrentHashMap<>();
    static Thread tail;
    static volatile boolean tailing = true;

    @TestConfiguration
    static class TopicConfig {
        /** In compose ingestion-service declares this topic; here the test does. */
        @Bean
        NewTopic hl7v2Raw() {
            return TopicBuilder.name(Topics.HL7V2_RAW).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KafkaListenerEndpointRegistry registry;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Started once the context (and so the Boot-managed containers) is up — not in @BeforeAll. */
    @BeforeEach
    void startTail() {
        synchronized (SEEN) {
            if (tail != null) {
                return;
            }
            startTailThread();
        }
        // Wait until the tail consumer has its partitions, so nothing produced by a test is missed.
        await(() -> tailReady, "test tail consumer assigned");
    }

    static volatile boolean tailReady;

    static void startTailThread() {
        tail = new Thread(() -> {
            Map<String, Object> props = Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ConsumerConfig.GROUP_ID_CONFIG, "test-tail-" + UUID.randomUUID(),
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.METADATA_MAX_AGE_CONFIG, 1000);
            try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
                c.subscribe(List.of(Topics.BRONZE_INGESTED, Topics.HL7V2_RAW_DLT));
                while (tailing) {
                    if (!c.assignment().isEmpty()) {
                        tailReady = true;
                    }
                    for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(200))) {
                        SEEN.computeIfAbsent(r.topic(), t -> java.util.Collections.synchronizedList(new ArrayList<>())).add(r);
                    }
                }
            }
        }, "test-tail");
        tail.setDaemon(true);
        tail.start();
    }

    @AfterAll
    static void stopTail() {
        tailing = false;
    }

    @Test
    void validMessageIsStoredPendingAndAnnouncedWithLineageHeaders() throws Exception {
        String raw = unique(Hl7Samples.ADT_A01, "T1");
        String eventId = send(raw, "CITY_HOSPITAL", "corr-t1");

        Map<String, Object> row = awaitRow(raw);
        assertThat(row.get("processing_status")).isEqualTo("PENDING");
        assertThat(row.get("intake_channel")).isEqualTo("MLLP_KAFKA");
        assertThat(row.get("correlation_id")).isEqualTo("corr-t1");
        assertThat(row.get("kafka_topic")).isEqualTo(Topics.HL7V2_RAW);
        assertThat(row.get("kafka_offset")).isNotNull();
        assertThat(row.get("raw_payload")).isEqualTo(raw);
        long id = ((Number) row.get("id")).longValue();

        ConsumerRecord<String, String> announced = awaitIngested(id);
        assertThat(announced.key()).isEqualTo("CITY_HOSPITAL|MRN100234");
        assertThat(header(announced, "event-type")).isEqualTo("bronze.ingested");
        assertThat(header(announced, "correlation-id")).isEqualTo("corr-t1");
        assertThat(header(announced, "causation-id")).isEqualTo(eventId);
        assertThat(header(announced, "producer")).isEqualTo("bronze-listener-service");
        assertThat(header(announced, "event-id")).isNotBlank();
        JsonNode value = mapper.readTree(announced.value());
        assertThat(value.get("hl7MessageType").asText()).isEqualTo("ADT^A01");
        assertThat(value.get("payloadChecksum").asText()).isEqualTo(sha256(raw));

        await(() -> jdbc.queryForObject("SELECT count(*) FROM bronze.outbox_events WHERE aggregate_id = ? AND published_at IS NOT NULL",
                Long.class, id) == 1, "outbox row stamped published");
    }

    @Test
    void malformedHl7IsQuarantinedAndNotAnnounced() throws Exception {
        String raw = "NOT HL7 AT ALL " + UUID.randomUUID() + "\r";
        send(raw, "UNKNOWN_HL7_SOURCE", "corr-t2");

        Map<String, Object> row = awaitRow(raw);
        assertThat(row.get("processing_status")).isEqualTo("QUARANTINED");
        assertThat((String) row.get("error_message")).isNotBlank();
        long id = ((Number) row.get("id")).longValue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bronze.outbox_events WHERE aggregate_id = ?", Long.class, id))
                .isZero();
    }

    @Test
    void redeliveredRecordIsSkippedNotDuplicated() throws Exception {
        String raw = unique(Hl7Samples.ORU_R01, "T3");
        send(raw, "REGIONAL_LAB", "corr-t3a");
        long id = ((Number) awaitRow(raw).get("id")).longValue();
        awaitIngested(id);

        // Same payload again (a sender retry, or Kafka redelivery after a crash before the offset commit).
        send(raw, "REGIONAL_LAB", "corr-t3b");
        String marker = unique(Hl7Samples.ADT_A01, "T3-after");
        send(marker, "REGIONAL_LAB", "corr-t3c");
        awaitRow(marker);   // same key → same partition → processed after the duplicate

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bronze.raw_messages WHERE payload_checksum = ?", Long.class,
                sha256(raw))).isEqualTo(1);
        Thread.sleep(800);   // give the relay a few polls to (wrongly) announce a second event
        assertThat(ingestedFor(id)).hasSize(1);
    }

    @Test
    void listenerStoppedMidRunResumesWithoutLossOrDuplicates() throws Exception {
        MessageListenerContainer container = registry.getListenerContainer("bronze-writer");
        List<String> batch = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            batch.add(unique(Hl7Samples.ADT_A01, "T4-" + i));
        }
        for (int i = 0; i < 10; i++) {
            send(batch.get(i), "CITY_HOSPITAL", "corr-t4-" + i);
        }
        container.stop();
        for (int i = 10; i < 20; i++) {
            send(batch.get(i), "CITY_HOSPITAL", "corr-t4-" + i);
        }
        container.start();

        for (String raw : batch) {
            awaitRow(raw);
        }
        List<String> checksums = batch.stream().map(BronzeListenerIntegrationTest::sha256).toList();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bronze.raw_messages WHERE payload_checksum = ANY (?)",
                Long.class, (Object) checksums.toArray(new String[0]))).isEqualTo(20);
    }

    @Test
    void brokenEventGoesStraightToDeadLetterTopic() {
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.HL7V2_RAW, "k-t5", "{not json");
        record.headers().add(new RecordHeader("correlation-id", "corr-t5".getBytes(StandardCharsets.UTF_8)));
        kafka.send(record).join();

        ConsumerRecord<String, String> dead = awaitDlt("corr-t5");
        assertThat(dead.value()).isEqualTo("{not json");
        assertThat(header(dead, "kafka_dlt-exception-fqcn")).contains("ListenerExecutionFailedException");
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).endsWith("InvalidEventException");
    }

    @Test
    void checksumMismatchIsDeadLettered() throws Exception {
        Map<String, Object> value = event(Hl7Samples.ADT_A01, "CITY_HOSPITAL");
        value.put("payloadChecksum", "0".repeat(64));
        produce(mapper.writeValueAsString(value), "corr-t6");
        assertThat(header(awaitDlt("corr-t6"), "kafka_dlt-exception-cause-fqcn")).endsWith("InvalidEventException");
    }

    @Test
    void technicalFailureIsRetriedThenDeadLettered() throws Exception {
        // A source system longer than VARCHAR(100): the database rejects the insert every time, which is
        // what a persistent technical failure looks like to the listener (retried, then dead-lettered).
        String raw = unique(Hl7Samples.ADT_A01, "T7");
        produce(mapper.writeValueAsString(event(raw, "X".repeat(150))), "corr-t7");

        ConsumerRecord<String, String> dead = awaitDlt("corr-t7");
        assertThat(header(dead, "kafka_dlt-exception-cause-fqcn")).contains("DataIntegrityViolation");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bronze.raw_messages WHERE payload_checksum = ?", Long.class,
                sha256(raw))).isZero();
    }

    // ---------------------------------------------------------------------------------------------

    /** Make each test's payload unique so tests don't dedupe against each other. */
    private static String unique(String hl7, String tag) {
        return hl7.replaceFirst("\\|P\\|2\\.5\r", "|P|2.5\rNTE|1||" + tag + "-" + UUID.randomUUID() + "\r");
    }

    private Map<String, Object> event(String raw, String source) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sourceSystem", source);
        v.put("payloadChecksum", sha256(raw));
        v.put("receivedAt", Instant.now().toString());
        v.put("hl7MessageType", "ADT^A01");
        v.put("controlId", "MSG00001");
        v.put("patientId", "MRN100234");
        v.put("rawPayload", raw);
        return v;
    }

    private String send(String raw, String source, String correlationId) throws Exception {
        return produce(mapper.writeValueAsString(event(raw, source)), correlationId, source + "|MRN100234");
    }

    private String produce(String value, String correlationId) {
        return produce(value, correlationId, "k-" + correlationId);
    }

    private String produce(String value, String correlationId, String key) {
        String eventId = UUID.randomUUID().toString();
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.HL7V2_RAW, key, value);
        record.headers().add(new RecordHeader("event-id", eventId.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("event-type", "hl7v2.raw".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("correlation-id", correlationId.getBytes(StandardCharsets.UTF_8)));
        kafka.send(record).join();
        return eventId;
    }

    private Map<String, Object> awaitRow(String raw) {
        String sha = sha256(raw);
        await(() -> jdbc.queryForObject("SELECT count(*) FROM bronze.raw_messages WHERE payload_checksum = ?", Long.class, sha) > 0,
                "Bronze row for " + sha);
        return jdbc.queryForMap("SELECT * FROM bronze.raw_messages WHERE payload_checksum = ?", sha);
    }

    private ConsumerRecord<String, String> awaitIngested(long rawMessageId) {
        await(() -> !ingestedFor(rawMessageId).isEmpty(), "bronze.ingested for raw message " + rawMessageId);
        return ingestedFor(rawMessageId).get(0);
    }

    private List<ConsumerRecord<String, String>> ingestedFor(long rawMessageId) {
        List<ConsumerRecord<String, String>> all = SEEN.getOrDefault(Topics.BRONZE_INGESTED, List.of());
        synchronized (all) {
            return all.stream().filter(r -> {
                try {
                    return mapper.readTree(r.value()).get("rawMessageId").asLong() == rawMessageId;
                } catch (Exception e) {
                    return false;
                }
            }).toList();
        }
    }

    private ConsumerRecord<String, String> awaitDlt(String correlationId) {
        await(() -> dlt(correlationId) != null, "DLT record " + correlationId);
        return dlt(correlationId);
    }

    private ConsumerRecord<String, String> dlt(String correlationId) {
        List<ConsumerRecord<String, String>> all = SEEN.getOrDefault(Topics.HL7V2_RAW_DLT, List.of());
        synchronized (all) {
            return all.stream().filter(r -> correlationId.equals(header(r, "correlation-id"))).findFirst().orElse(null);
        }
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private static String header(ConsumerRecord<?, ?> r, String name) {
        var h = r.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
