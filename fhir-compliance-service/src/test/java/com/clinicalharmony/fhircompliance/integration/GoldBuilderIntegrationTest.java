package com.clinicalharmony.fhircompliance.integration;

import com.clinicalharmony.fhircompliance.gold.Hashes;
import com.clinicalharmony.fhircompliance.goldbuilder.Topics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9c gold-builder end to end, in the shared integration context (Postgres from docs/db-init incl.
 * migration 25, and a real cp-kafka broker): a Kafka-fed Bronze row + the Silver patient mapped from it (with a
 * diagnosis and an AL1 allergy) are inserted the way bronze-listener-service and silver-transformer-service write
 * them, then announced on clinicalharmony.silver.patient.changed the way the Silver outbox relay does. Out come
 * US Core resources in Gold (incl. the new AllergyIntolerance) with BRONZE → SILVER → GOLD lineage, and one
 * clinicalharmony.gold.patient.changed event keyed by the master id, carrying the correlation id through.
 */
class GoldBuilderIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ---- tail consumer (started once the context — and so the containers — are up) -----------------
    static final Map<String, List<ConsumerRecord<String, String>>> SEEN = new ConcurrentHashMap<>();
    static volatile boolean tailing = true;
    static volatile boolean tailReady;
    static Thread tail;

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void startTail() {
        synchronized (SEEN) {
            if (tail == null) {
                tail = new Thread(GoldBuilderIntegrationTest::tailLoop, "test-tail");
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
            c.subscribe(List.of(Topics.GOLD_PATIENT_CHANGED, Topics.SILVER_PATIENT_CHANGED_DLT));
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
    static void stopTail() {
        tailing = false;
    }

    // ---- tests --------------------------------------------------------------------------------------

    @Test
    void silverPatientChangedBuildsGoldWithLineageAndAnnouncesTheMaster() throws Exception {
        String mrn = "GB" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String hl7 = "MSH|^~\\&|REG_SYSTEM|CITY_HOSPITAL|CLINICALHARMONY|INGESTION|20261118090000||ADT^A01|"
                + mrn + "|P|2.5\rPID|1||" + mrn + "^^^CITY_HOSPITAL^MR||OKAFOR^GRACE^A||19800214|F\r";
        long silverId = insertKafkaFedPatient(mrn, hl7);
        long conditionId = jdbc.queryForObject("""
                INSERT INTO silver.conditions (patient_id, code_system, code, display, clinical_status, raw_message_id)
                VALUES (?, 'ICD-10', 'E11.9', 'Type 2 diabetes mellitus without complications', 'active',
                        (SELECT raw_message_id FROM silver.patients WHERE id = ?)) RETURNING id
                """, Long.class, silverId, silverId);
        long allergyId = jdbc.queryForObject("""
                INSERT INTO silver.allergies (patient_id, code_system, code, display, allergy_type, severity, reaction,
                                              raw_message_id)
                VALUES (?, 'RXNORM', '723', 'Amoxicillin', 'DA', 'MO', 'HIVES',
                        (SELECT raw_message_id FROM silver.patients WHERE id = ?)) RETURNING id
                """, Long.class, silverId, silverId);

        String correlationId = "mllp-" + mrn.toLowerCase();
        String silverEventId = UUID.randomUUID().toString();
        publish(silverId, mrn, correlationId, silverEventId);

        // Gold: Patient + Condition + the new AllergyIntolerance, all on one master.
        await(() -> count("SELECT count(*) FROM gold.outbox_events WHERE aggregate_id IN "
                + "(SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = " + silverId
                + ") AND published_at IS NOT NULL") == 1, "gold outbox event published");
        List<Map<String, Object>> gold = jdbc.queryForList("""
                SELECT resource_type, fhir_id, is_us_core_compliant, master_patient_id
                FROM gold.fhir_resources WHERE fhir_id IN (?, ?, ?) ORDER BY id
                """, "silver-patient-" + silverId, "silver-condition-" + conditionId, "silver-allergy-" + allergyId);
        assertThat(gold).extracting(r -> r.get("resource_type"))
                .containsExactlyInAnyOrder("Patient", "Condition", "AllergyIntolerance");
        assertThat(gold).extracting(r -> r.get("is_us_core_compliant")).containsOnly(true);
        long masterId = ((Number) gold.get(0).get("master_patient_id")).longValue();
        assertThat(gold).extracting(r -> ((Number) r.get("master_patient_id")).longValue()).containsOnly(masterId);

        // Lineage of the allergy: BRONZE (the raw HL7, via the Kafka path) → SILVER → GOLD.
        JsonNode details = JSON.readTree(jdbc.queryForObject("""
                SELECT transformation_details::text FROM gold.data_lineage
                WHERE target_table = 'fhir_resources' AND source_table = 'allergies' AND source_id = ?
                """, String.class, allergyId));
        assertThat(details.get("steps")).extracting(s -> s.get("stage").asText()).containsExactly("BRONZE", "SILVER", "GOLD");
        assertThat(details.get("steps").get(0).get("input_hash").asText()).isEqualTo(Hashes.sha256Hex(hl7));
        assertThat(details.get("steps").get(0).get("transformer").asText()).contains("bronze-listener-service");
        assertThat(details.get("steps").get(1).get("ref").asText()).isEqualTo("silver.allergies/" + allergyId);
        assertThat(details.get("steps").get(2).get("transformer").asText()).contains("AllergyIntoleranceResourceBuilder");

        // The event: keyed by master id, correlation id carried through, causation = the Silver event.
        ConsumerRecord<String, String> event = awaitRecord(Topics.GOLD_PATIENT_CHANGED, String.valueOf(masterId));
        assertThat(header(event, "correlation-id")).isEqualTo(correlationId);
        assertThat(header(event, "causation-id")).isEqualTo(silverEventId);
        assertThat(header(event, "event-type")).isEqualTo("gold.patient.changed");
        assertThat(header(event, "producer")).isEqualTo("fhir-compliance-service");
        JsonNode payload = JSON.readTree(event.value());
        assertThat(payload.get("masterPatientId").asLong()).isEqualTo(masterId);
        assertThat(payload.get("silverPatientId").asLong()).isEqualTo(silverId);
        assertThat(payload.get("compliance").get("resources").asInt()).isEqualTo(3);
        assertThat(payload.get("compliance").get("nonCompliant").asInt()).isZero();
        assertThat(payload.get("resources")).extracting(r -> r.get("resourceType").asText())
                .containsExactly("Patient", "Condition", "AllergyIntolerance");

        // Redelivery (at-least-once): same rows rebuilt in place, the patient announced again.
        publish(silverId, mrn, correlationId, silverEventId);
        await(() -> recordsFor(Topics.GOLD_PATIENT_CHANGED, String.valueOf(masterId)).size() == 2, "second gold event");
        assertThat(count("SELECT count(*) FROM gold.fhir_resources WHERE fhir_id = 'silver-patient-" + silverId + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM gold.patient_source_xref WHERE silver_patient_id = " + silverId))
                .isEqualTo(1);
    }

    @Test
    void brokenEventsGoStraightToTheDeadLetterTopic() {
        String key = "DLT|" + UUID.randomUUID();
        kafka.send(new ProducerRecord<>(Topics.SILVER_PATIENT_CHANGED, key, "this is not json"));
        String unknownKey = "DLT|" + UUID.randomUUID();
        kafka.send(new ProducerRecord<>(Topics.SILVER_PATIENT_CHANGED, unknownKey, "{\"silverPatientId\":987654321}"));

        ConsumerRecord<String, String> dead = awaitRecord(Topics.SILVER_PATIENT_CHANGED_DLT, key);
        assertThat(dead.value()).isEqualTo("this is not json");
        assertThat(header(dead, "kafka_dlt-exception-fqcn")).endsWith("ListenerExecutionFailedException");
        ConsumerRecord<String, String> unknown = awaitRecord(Topics.SILVER_PATIENT_CHANGED_DLT, unknownKey);
        assertThat(header(unknown, "kafka_dlt-exception-cause-fqcn")).endsWith("InvalidEventException");
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private long insertKafkaFedPatient(String mrn, String hl7) {
        long bronzeId = jdbc.queryForObject("""
                INSERT INTO bronze.raw_messages (source_system, message_type, message_format, raw_payload,
                                                 payload_checksum, processing_status, intake_channel)
                VALUES ('CITY_HOSPITAL', 'HL7V2', 'TEXT', ?, ?, 'PROCESSED', 'MLLP_KAFKA') RETURNING id
                """, Long.class, hl7, Hashes.sha256Hex(hl7));
        return jdbc.queryForObject("""
                INSERT INTO silver.patients (source_system, source_patient_id, mrn, first_name, last_name,
                                             date_of_birth, gender, raw_message_id)
                VALUES ('CITY_HOSPITAL', ?, ?, 'GRACE', 'OKAFOR', '1980-02-14', 'female', ?) RETURNING id
                """, Long.class, mrn, mrn, bronzeId);
    }

    private void publish(long silverId, String mrn, String correlationId, String eventId) {
        String value = "{\"silverPatientId\":" + silverId + ",\"sourceSystem\":\"CITY_HOSPITAL\",\"sourcePatientId\":\""
                + mrn + "\",\"rawMessageId\":null,\"hl7MessageType\":\"ADT^A01\",\"change\":\"CREATED\"}";
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.SILVER_PATIENT_CHANGED,
                "CITY_HOSPITAL|" + mrn, value);
        record.headers().add(new RecordHeader("event-id", eventId.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("correlation-id", correlationId.getBytes(StandardCharsets.UTF_8)));
        kafka.send(record);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private static List<ConsumerRecord<String, String>> recordsFor(String topic, String key) {
        List<ConsumerRecord<String, String>> all = SEEN.getOrDefault(topic, List.of());
        synchronized (all) {
            return all.stream().filter(r -> key.equals(r.key())).toList();
        }
    }

    private static ConsumerRecord<String, String> awaitRecord(String topic, String key) {
        await(() -> !recordsFor(topic, key).isEmpty(), "record " + key + " on " + topic);
        return recordsFor(topic, key).get(0);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    static void await(BooleanSupplier condition, String what) {
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
