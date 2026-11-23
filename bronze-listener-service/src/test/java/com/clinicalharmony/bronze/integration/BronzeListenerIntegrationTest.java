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
 * bronze-listener-service end to end: real Postgres initialized from docs/db-init (incl. migrations 23 + 26)
 * and a real Kafka broker (cp-kafka 7.6.1, KRaft — the compose image). Records are produced exactly as
 * ingestion-service produces them (MLLP since 9a; REST HL7 / FHIR / claims / C-CDA since 9d).
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

        @Bean
        NewTopic fhirRaw() {
            return TopicBuilder.name(Topics.FHIR_RAW).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic claimsRaw() {
            return TopicBuilder.name(Topics.CLAIMS_RAW).partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic ccdaRaw() {
            return TopicBuilder.name(Topics.CCDA_RAW).partitions(3).replicas(1).build();
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
                c.subscribe(List.of(Topics.BRONZE_INGESTED, Topics.HL7V2_RAW_DLT, Topics.FHIR_RAW_DLT,
                        Topics.CLAIMS_RAW_DLT, Topics.CCDA_RAW_DLT));
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

    @Test
    void restHl7IsStoredAsRestKafkaAndTheSameMessageThroughMllpIsADuplicate() throws Exception {
        String raw = unique(Hl7Samples.ADT_A01, "T8");
        Map<String, Object> rest = event(raw, "CITY_HOSPITAL");
        rest.put("intakeChannel", "REST");
        produce(Topics.HL7V2_RAW, mapper.writeValueAsString(rest), "corr-t8a", "CITY_HOSPITAL|MRN100234");
        Map<String, Object> row = awaitRow(raw);
        assertThat(row.get("intake_channel")).isEqualTo("REST_KAFKA");
        assertThat(row.get("message_type")).isEqualTo("HL7V2");
        long id = ((Number) row.get("id")).longValue();
        assertThat(mapper.readTree(awaitIngested(id).value()).get("intakeChannel").asText()).isEqualTo("REST_KAFKA");

        send(raw, "CITY_HOSPITAL", "corr-t8b");   // same bytes, other door
        String marker = unique(Hl7Samples.ADT_A01, "T8-after");
        send(marker, "CITY_HOSPITAL", "corr-t8c");
        awaitRow(marker);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bronze.raw_messages WHERE payload_checksum = ?", Long.class,
                sha256(raw))).isEqualTo(1);
    }

    @Test
    void fhirIsStoredAsJsonAndAnnouncedWithItsResourceType() throws Exception {
        String raw = "{\"resourceType\":\"Patient\",\"id\":\"" + UUID.randomUUID() + "\"}";
        produce(Topics.FHIR_RAW, mapper.writeValueAsString(restEvent(raw, "PARTNER_CLINIC", "FHIR_JSON")), "corr-t9",
                "PARTNER_CLINIC|p");
        Map<String, Object> row = awaitRow(raw);
        assertThat(row).containsEntry("message_type", "FHIR_JSON").containsEntry("message_format", "JSON")
                .containsEntry("processing_status", "PENDING").containsEntry("intake_channel", "REST_KAFKA")
                .containsEntry("kafka_topic", Topics.FHIR_RAW).containsEntry("source_system", "PARTNER_CLINIC");
        JsonNode value = mapper.readTree(awaitIngested(((Number) row.get("id")).longValue()).value());
        assertThat(value.get("messageType").asText()).isEqualTo("FHIR_JSON");
        assertThat(value.get("fhirResourceType").asText()).isEqualTo("Patient");
        assertThat(value.has("hl7MessageType")).isFalse();
    }

    @Test
    void fhirWithoutResourceTypeIsQuarantined() throws Exception {
        String raw = "{\"notFhir\":\"" + UUID.randomUUID() + "\"}";
        produce(Topics.FHIR_RAW, mapper.writeValueAsString(restEvent(raw, "PARTNER_CLINIC", "FHIR_JSON")), "corr-t10", "k10");
        Map<String, Object> row = awaitRow(raw);
        assertThat(row.get("processing_status")).isEqualTo("QUARANTINED");
        assertThat((String) row.get("error_message")).contains("no resourceType");
    }

    @Test
    void claimsFileIsSplitIntoOneAnnouncedRowPerLineAndAResendIsADuplicate() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String header = "claim_id,member_id,service_date,icd10_code,billed_amount";
        String line1 = "CLM-" + tag + "-1,MBR-1,2026-06-01,E11.9,150.00";
        String line2 = "CLM-" + tag + "-2,MBR-2,2026-06-02,I10,95.50";
        String file = header + "\n" + line1 + "\n" + line2 + "\n";
        produce(Topics.CLAIMS_RAW, mapper.writeValueAsString(restEvent(file, "CLAIMS_VENDOR_A", "CLAIMS_CSV")), "corr-t11a", "k11");

        Map<String, Object> row1 = awaitRow(header + "\n" + line1);
        Map<String, Object> row2 = awaitRow(header + "\n" + line2);
        for (Map<String, Object> row : List.of(row1, row2)) {
            assertThat(row).containsEntry("message_type", "CLAIMS_CSV").containsEntry("message_format", "CSV")
                    .containsEntry("processing_status", "PENDING").containsEntry("correlation_id", "corr-t11a");
        }
        JsonNode e2 = mapper.readTree(awaitIngested(((Number) row2.get("id")).longValue()).value());
        assertThat(e2.get("claimLine").asInt()).isEqualTo(2);
        assertThat(e2.get("claimLines").asInt()).isEqualTo(2);
        assertThat(e2.get("payloadChecksum").asText()).isEqualTo(sha256(header + "\n" + line2));
        // The whole file is never stored, only its lines.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bronze.raw_messages WHERE payload_checksum = ?", Long.class,
                sha256(file))).isZero();

        produce(Topics.CLAIMS_RAW, mapper.writeValueAsString(restEvent(file, "CLAIMS_VENDOR_A", "CLAIMS_CSV")), "corr-t11b", "k11");
        String marker = header + "\nCLM-" + tag + "-3,MBR-3,2026-06-03,I10,10.00\n";
        produce(Topics.CLAIMS_RAW, mapper.writeValueAsString(restEvent(marker, "CLAIMS_VENDOR_A", "CLAIMS_CSV")), "corr-t11c", "k11");
        awaitRow(marker.strip());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bronze.raw_messages WHERE raw_payload LIKE ?", Long.class,
                "%CLM-" + tag + "-%")).isEqualTo(3);
    }

    @Test
    void raggedClaimsFileIsQuarantinedWhole() throws Exception {
        String file = "claim_id,member_id,billed_amount\nCLM-" + UUID.randomUUID() + ",MBR-1\n";
        produce(Topics.CLAIMS_RAW, mapper.writeValueAsString(restEvent(file, "CLAIMS_VENDOR_A", "CLAIMS_CSV")), "corr-t12", "k12");
        Map<String, Object> row = awaitRow(file);
        assertThat(row.get("processing_status")).isEqualTo("QUARANTINED");
        assertThat((String) row.get("error_message")).isEqualTo("Row 1 has 2 columns, header has 3");
    }

    @Test
    void ccdaIsStoredAsXml() throws Exception {
        String raw = "<ClinicalDocument xmlns=\"urn:hl7-org:v3\"><id root=\"1.2.3\" extension=\"" + UUID.randomUUID()
                + "\"/><recordTarget><patientRole><id extension=\"MRN-1\"/></patientRole></recordTarget></ClinicalDocument>";
        produce(Topics.CCDA_RAW, mapper.writeValueAsString(restEvent(raw, "NORTH_CLINIC", "CCDA_XML")), "corr-t13", "NORTH_CLINIC|MRN-1");
        Map<String, Object> row = awaitRow(raw);
        assertThat(row).containsEntry("message_type", "CCDA_XML").containsEntry("message_format", "XML")
                .containsEntry("processing_status", "PENDING");
        JsonNode value = mapper.readTree(awaitIngested(((Number) row.get("id")).longValue()).value());
        assertThat(value.get("ccdaDocumentId").asText()).startsWith("1.2.3^");
    }

    @Test
    void brokenFhirEventGoesToTheFhirDeadLetterTopic() {
        produce(Topics.FHIR_RAW, "{not json", "corr-t14", "k14");
        ConsumerRecord<String, String> dead = awaitDlt("corr-t14");
        assertThat(dead.topic()).isEqualTo(Topics.FHIR_RAW_DLT);
    }

    // ---------------------------------------------------------------------------------------------

    private Map<String, Object> restEvent(String raw, String source, String messageType) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sourceSystem", source);
        v.put("payloadChecksum", sha256(raw));
        v.put("receivedAt", Instant.now().toString());
        v.put("intakeChannel", "REST");
        v.put("messageType", messageType);
        v.put("rawPayload", raw);
        return v;
    }

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
        return produce(Topics.HL7V2_RAW, value, correlationId, key);
    }

    private String produce(String topic, String value, String correlationId, String key) {
        String eventId = UUID.randomUUID().toString();
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
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
        for (String topic : List.of(Topics.HL7V2_RAW_DLT, Topics.FHIR_RAW_DLT, Topics.CLAIMS_RAW_DLT, Topics.CCDA_RAW_DLT)) {
            List<ConsumerRecord<String, String>> all = SEEN.getOrDefault(topic, List.of());
            synchronized (all) {
                ConsumerRecord<String, String> hit = all.stream()
                        .filter(r -> correlationId.equals(header(r, "correlation-id"))).findFirst().orElse(null);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
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
