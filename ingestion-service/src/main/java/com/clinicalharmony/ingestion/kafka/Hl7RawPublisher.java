package com.clinicalharmony.ingestion.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes one raw HL7 v2 message to {@value KafkaTopics#HL7V2_RAW} and BLOCKS until the broker
 * confirms it ({@code acks=all}), so the MLLP route only ACKs what Kafka durably holds.
 *
 * <p>Envelope (shared by every Phase 9 topic): the JSON value carries the business data; Kafka headers
 * carry {@code event-id}, {@code event-type}, {@code schema-version}, {@code produced-at},
 * {@code producer} and {@code correlation-id}. There's no {@code causation-id} here: MLLP intake is
 * the first hop, nothing caused it inside the pipeline.
 */
@Component
public class Hl7RawPublisher {

    public static final String EVENT_TYPE = "hl7v2.raw";
    public static final String SCHEMA_VERSION = "1";
    public static final String PRODUCER = "ingestion-service";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Duration sendTimeout;

    public Hl7RawPublisher(KafkaTemplate<String, String> kafkaTemplate,
                           ObjectMapper objectMapper,
                           @Value("${ingestion.kafka.send-timeout:10s}") Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.sendTimeout = sendTimeout;
    }

    public record Published(String eventId, String key, int partition, long offset) {
    }

    /** Thrown when Kafka did not confirm the write; the caller NAKs so the sender retries. */
    public static class PublishFailedException extends RuntimeException {
        public PublishFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Published publish(String rawPayload, String sourceSystem, String checksum, Hl7Envelope envelope,
                             String correlationId, Instant receivedAt) {
        String eventId = UUID.randomUUID().toString();
        String key = partitionKey(sourceSystem, envelope, checksum);

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceSystem", sourceSystem);
        value.put("payloadChecksum", checksum);
        value.put("receivedAt", receivedAt.toString());
        value.put("hl7MessageType", envelope.messageType());
        value.put("controlId", envelope.controlId());
        value.put("patientId", envelope.patientId());
        value.put("rawPayload", rawPayload);

        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize HL7 raw event", e);
        }

        ProducerRecord<String, String> record = new ProducerRecord<>(KafkaTopics.HL7V2_RAW, key, json);
        header(record, "event-id", eventId);
        header(record, "event-type", EVENT_TYPE);
        header(record, "schema-version", SCHEMA_VERSION);
        header(record, "produced-at", Instant.now().toString());
        header(record, "producer", PRODUCER);
        header(record, "correlation-id", correlationId);

        try {
            SendResult<String, String> result = kafkaTemplate.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Published(eventId, key, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException("Interrupted while waiting for Kafka", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new PublishFailedException("Kafka rejected the message: " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new PublishFailedException("Kafka did not confirm within " + sendTimeout.toMillis() + " ms", e);
        } catch (RuntimeException e) {
            // KafkaTemplate.send can throw synchronously, e.g. when metadata can't be fetched in max.block.ms.
            throw new PublishFailedException("Kafka unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Keeps one patient's messages in order on one partition: source system + PID-3. Falls back to
     * the MSH-10 control id when PID can't be read, and to the checksum when even MSH can't.
     */
    static String partitionKey(String sourceSystem, Hl7Envelope envelope, String checksum) {
        if (envelope.patientId() != null) {
            return sourceSystem + "|" + envelope.patientId();
        }
        if (envelope.controlId() != null) {
            return sourceSystem + "|msh10:" + envelope.controlId();
        }
        return "sha256:" + checksum;
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        if (value != null) {
            record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
