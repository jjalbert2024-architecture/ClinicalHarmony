package com.clinicalharmony.patientindex.integration;

import com.clinicalharmony.patientindex.kafka.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9c pmi-matcher end to end: a real cp-kafka broker, Postgres from docs/db-init (migration 21's
 * cross-source duplicates, each with a PROVISIONAL master as GoldWriter creates them on build) and the fake
 * GoldWriter. A burst of clinicalharmony.gold.patient.changed events yields exactly ONE debounced matching run
 * (quiet period shortened to 1 s here), which merges the seed duplicates; a broken event is dead-lettered.
 */
@SpringBootTest(properties = {
        "pmi-matcher.quiet-period=1s",
        "pmi-matcher.max-delay=20s",
        "pmi-matcher.tick=100ms",
        "pmi-matcher.run-on-startup=false",
        "pmi-matcher.retry.initial-interval=100ms",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@Testcontainers(disabledWithoutDocker = true)
@Import({TestJwts.class, PmiMatcherIntegrationTest.TopicConfig.class})
class PmiMatcherIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("clinicalharmony")
            .withUsername("clinicalharmony")
            .withPassword("clinicalharmony")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("..", "docs", "db-init").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/");

    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.1");

    static final FakeGoldWriter GOLD_WRITER = new FakeGoldWriter();

    @DynamicPropertySource
    static void goldWriter(DynamicPropertyRegistry registry) {
        registry.add("gold-writer.base-url", GOLD_WRITER::baseUrl);
        registry.add("gold-writer.token-uri", () -> GOLD_WRITER.baseUrl() + "/token");
    }

    @TestConfiguration
    static class TopicConfig {
        /** In compose fhir-compliance-service (the producer) declares this topic; here the test does. */
        @Bean
        NewTopic goldPatientChanged() {
            return TopicBuilder.name(Topics.GOLD_PATIENT_CHANGED).partitions(3).replicas(1).build();
        }
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KafkaTemplate<String, String> kafka;

    @Test
    void aBurstOfGoldEventsTriggersOneDebouncedRunAndBrokenEventsAreDeadLettered() throws Exception {
        GOLD_WRITER.useDatabase(jdbc);
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
        List<Long> masters = jdbc.queryForList(
                "SELECT id FROM gold.master_patients WHERE match_status = 'PROVISIONAL' ORDER BY id LIMIT 3", Long.class);
        assertThat(masters).hasSize(3);

        // A burst: three events within a few hundred ms (two for the same master).
        String correlationId = "mllp-" + UUID.randomUUID().toString().substring(0, 12);
        for (long m : List.of(masters.get(0), masters.get(1), masters.get(1))) {
            ProducerRecord<String, String> r = new ProducerRecord<>(Topics.GOLD_PATIENT_CHANGED, String.valueOf(m),
                    "{\"masterPatientId\":" + m + ",\"silverPatientId\":1}");
            r.headers().add(new RecordHeader("correlation-id", correlationId.getBytes(StandardCharsets.UTF_8)));
            r.headers().add(new RecordHeader("event-id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
            kafka.send(r).get();
        }

        await(() -> kafkaRuns().size() == 1 && "COMPLETED".equals(kafkaRuns().get(0).get("status")),
                "one completed debounced run");
        Thread.sleep(2500); // > quiet period: no second run follows from the same burst
        List<Map<String, Object>> runs = kafkaRuns();
        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).get("triggered_by")).isEqualTo("kafka:pmi-matcher (3 events, 2 masters)");
        assertThat(((Number) runs.get(0).get("auto_merges")).intValue()).isEqualTo(3);
        assertThat(((Number) runs.get(0).get("queued_for_review")).intValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gold.master_patients WHERE match_status = 'PROVISIONAL' "
                + "AND is_active AND id NOT IN (SELECT master_a_id FROM pmi.match_candidates WHERE status = 'PENDING' "
                + "UNION SELECT master_b_id FROM pmi.match_candidates WHERE status = 'PENDING')", Long.class)).isZero();

        // A broken event: straight to the DLT, and no run.
        String key = "broken-" + UUID.randomUUID();
        kafka.send(new ProducerRecord<>(Topics.GOLD_PATIENT_CHANGED, key, "{\"silverPatientId\":5}")).get();
        ConsumerRecord<String, String> dead = awaitDeadLetter(key);
        assertThat(new String(dead.headers().lastHeader("kafka_dlt-exception-cause-fqcn").value(), StandardCharsets.UTF_8))
                .endsWith("InvalidEventException");
        Thread.sleep(1500);
        assertThat(kafkaRuns()).hasSize(1);
    }

    private List<Map<String, Object>> kafkaRuns() {
        return jdbc.queryForList("SELECT id, status, triggered_by, auto_merges, queued_for_review FROM pmi.match_runs "
                + "WHERE triggered_by LIKE 'kafka:%' ORDER BY id");
    }

    private ConsumerRecord<String, String> awaitDeadLetter(String key) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props)) {
            c.subscribe(List.of(Topics.GOLD_PATIENT_CHANGED_DLT));
            Instant deadline = Instant.now().plusSeconds(60);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> r : c.poll(Duration.ofMillis(300))) {
                    if (key.equals(r.key())) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("No dead letter for " + key);
    }

    static void await(BooleanSupplier condition, String what) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(60);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
