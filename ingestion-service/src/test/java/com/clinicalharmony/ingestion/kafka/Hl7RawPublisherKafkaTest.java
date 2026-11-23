package com.clinicalharmony.ingestion.kafka;

import com.clinicalharmony.ingestion.service.Checksums;
import com.clinicalharmony.ingestion.service.Hl7SampleMessages;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The publisher against a real broker (the same cp-kafka 7.6.1 image as docker-compose, KRaft):
 * value, key, envelope headers, and that an unreachable broker becomes a PublishFailedException
 * (→ NAK AE) rather than a hang.
 */
@Testcontainers(disabledWithoutDocker = true)
class Hl7RawPublisherKafkaTest {

    @Container
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.1");

    private static KafkaTemplate<String, String> template;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void setUp() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(KafkaTopics.HL7V2_RAW, 3, (short) 1))).all().get();
        }
        template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps(KAFKA.getBootstrapServers())));
    }

    @AfterAll
    static void tearDown() {
        if (template != null) {
            template.destroy();
        }
    }

    @Test
    void publishesRawMessageWithKeyAndEnvelopeHeaders() throws Exception {
        Hl7RawPublisher publisher = new Hl7RawPublisher(template, mapper, Duration.ofSeconds(10));
        String raw = Hl7SampleMessages.ADT_A01;

        Hl7RawPublisher.Published published = publisher.publish(raw, "CITY_HOSPITAL", Checksums.sha256(raw),
                Hl7Envelope.peek(raw), "mllp-abcdef012345", Instant.parse("2026-11-14T10:00:00Z"));

        ConsumerRecord<String, String> record = readOne(published.partition(), published.offset());
        assertThat(record.key()).isEqualTo("CITY_HOSPITAL|MRN100234");
        JsonNode value = mapper.readTree(record.value());
        assertThat(value.get("rawPayload").asText()).isEqualTo(raw);   // byte-for-byte, \r kept
        assertThat(value.get("sourceSystem").asText()).isEqualTo("CITY_HOSPITAL");
        assertThat(value.get("payloadChecksum").asText()).isEqualTo(Checksums.sha256(raw));
        assertThat(value.get("receivedAt").asText()).isEqualTo("2026-11-14T10:00:00Z");
        assertThat(header(record, "event-id")).isEqualTo(published.eventId());
        assertThat(header(record, "event-type")).isEqualTo("hl7v2.raw");
        assertThat(header(record, "schema-version")).isEqualTo("1");
        assertThat(header(record, "producer")).isEqualTo("ingestion-service");
        assertThat(header(record, "correlation-id")).isEqualTo("mllp-abcdef012345");
        assertThat(value.get("intakeChannel").asText()).isEqualTo("MLLP");
        assertThat(header(record, "produced-at")).isNotBlank();
    }

    @Test
    void sameKeyLandsOnSamePartition() {
        Hl7RawPublisher publisher = new Hl7RawPublisher(template, mapper, Duration.ofSeconds(10));
        String a = Hl7SampleMessages.ADT_A01;
        String b = Hl7SampleMessages.ORU_R01;   // same source + PID-3 → same key
        int p1 = publisher.publish(a, "CITY_HOSPITAL", "1", Hl7Envelope.peek(a), "c1", Instant.now()).partition();
        int p2 = publisher.publish(b, "CITY_HOSPITAL", "2", Hl7Envelope.peek(b), "c2", Instant.now()).partition();
        assertThat(p2).isEqualTo(p1);
    }

    @Test
    void unreachableBrokerFailsFastInsteadOfAcking() {
        Map<String, Object> props = producerProps("localhost:1");
        KafkaTemplate<String, String> dead = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        try {
            Hl7RawPublisher publisher = new Hl7RawPublisher(dead, mapper, Duration.ofSeconds(3));
            String raw = Hl7SampleMessages.ADT_A01;
            assertThatThrownBy(() -> publisher.publish(raw, "CITY_HOSPITAL", "x", Hl7Envelope.peek(raw), "c", Instant.now()))
                    .isInstanceOf(PublishFailedException.class);
        } finally {
            dead.destroy();
        }
    }

    @Test
    void restHl7IsMarkedRestOnTheSameTopic() throws Exception {
        Hl7RawPublisher publisher = new Hl7RawPublisher(template, mapper, Duration.ofSeconds(10));
        String raw = Hl7SampleMessages.ORU_R01;
        Hl7RawPublisher.Published published = publisher.publish(raw, "REGIONAL_LAB", Checksums.sha256(raw),
                Hl7Envelope.peek(raw), "rest-abcdef012345", Instant.now(), Hl7RawPublisher.CHANNEL_REST);
        JsonNode value = mapper.readTree(readOne(published.partition(), published.offset()).value());
        assertThat(value.get("intakeChannel").asText()).isEqualTo("REST");
    }

    @Test
    void oversizedEventIsRefusedBeforeSending() {
        RawEventPublisher publisher = new RawEventPublisher(template, mapper, Duration.ofSeconds(10), 1_000);
        assertThatThrownBy(() -> publisher.publish(KafkaTopics.FHIR_RAW, "fhir.raw", "k",
                Map.of("rawPayload", "x".repeat(2_000)), "c"))
                .isInstanceOf(PayloadTooLargeException.class)
                .hasMessageContaining("over the 1000-byte limit");
    }

    private static Map<String, Object> producerProps(String bootstrap) {
        return Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2500,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    }

    private static ConsumerRecord<String, String> readOne(int partition, long offset) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            org.apache.kafka.common.TopicPartition tp = new org.apache.kafka.common.TopicPartition(KafkaTopics.HL7V2_RAW, partition);
            consumer.assign(List.of(tp));
            consumer.seek(tp, offset);
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    if (r.offset() == offset) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("record " + partition + "@" + offset + " not found");
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
