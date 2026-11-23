package com.clinicalharmony.ingestion.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes one raw intake event to a {@code clinicalharmony.bronze.<format>.raw} topic and BLOCKS until the
 * broker confirms it ({@code acks=all}), so a sender is only told "accepted" for what Kafka durably holds.
 *
 * <p>Envelope (shared by every Phase 9 topic): the JSON value carries the business data; Kafka headers carry
 * {@code event-id}, {@code event-type}, {@code schema-version}, {@code produced-at}, {@code producer} and
 * {@code correlation-id}. There's no {@code causation-id}: intake is the first hop, nothing caused it inside
 * the pipeline.
 */
@Component
public class RawEventPublisher {

    public static final String SCHEMA_VERSION = "1";
    public static final String PRODUCER = "ingestion-service";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Duration sendTimeout;
    private final int maxEventBytes;

    @Autowired
    public RawEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                             ObjectMapper objectMapper,
                             @Value("${ingestion.kafka.send-timeout:10s}") Duration sendTimeout,
                             @Value("${ingestion.kafka.max-event-bytes:1000000}") int maxEventBytes) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.sendTimeout = sendTimeout;
        this.maxEventBytes = maxEventBytes;
    }

    public RawEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, Duration sendTimeout) {
        this(kafkaTemplate, objectMapper, sendTimeout, 1_000_000);
    }

    public record Published(String eventId, String key, int partition, long offset) {
    }

    public Published publish(String topic, String eventType, String key, Map<String, Object> value, String correlationId) {
        String eventId = UUID.randomUUID().toString();
        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize " + eventType + " event", e);
        }
        int eventBytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (eventBytes > maxEventBytes) {
            throw new PayloadTooLargeException("Event is " + eventBytes + " bytes once wrapped (payload JSON-escaped), over the "
                    + maxEventBytes + "-byte limit for one Kafka record");
        }

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, json);
        header(record, "event-id", eventId);
        header(record, "event-type", eventType);
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

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        if (value != null) {
            record.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
