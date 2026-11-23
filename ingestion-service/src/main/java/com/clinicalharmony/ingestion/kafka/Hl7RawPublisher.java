package com.clinicalharmony.ingestion.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes one raw HL7 v2 message to {@value KafkaTopics#HL7V2_RAW} (blocking on {@code acks=all}, see
 * {@link RawEventPublisher}). Used by MLLP intake (9a) and, since 9d, by {@code POST /api/ingestion/hl7};
 * the value's {@code intakeChannel} (MLLP / REST) tells bronze-listener-service which door it came through.
 */
@Component
public class Hl7RawPublisher {

    public static final String EVENT_TYPE = "hl7v2.raw";
    public static final String CHANNEL_MLLP = "MLLP";
    public static final String CHANNEL_REST = "REST";

    /** Same shape as {@link RawEventPublisher.Published}, kept for the MLLP path's callers. */
    public record Published(String eventId, String key, int partition, long offset) {
    }

    private final RawEventPublisher publisher;

    @Autowired
    public Hl7RawPublisher(RawEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** Convenience for tests that build the publisher without Spring. */
    public Hl7RawPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, Duration sendTimeout) {
        this(new RawEventPublisher(kafkaTemplate, objectMapper, sendTimeout));
    }

    /** MLLP intake. */
    public Published publish(String rawPayload, String sourceSystem, String checksum, Hl7Envelope envelope,
                             String correlationId, Instant receivedAt) {
        return publish(rawPayload, sourceSystem, checksum, envelope, correlationId, receivedAt, CHANNEL_MLLP);
    }

    public Published publish(String rawPayload, String sourceSystem, String checksum, Hl7Envelope envelope,
                             String correlationId, Instant receivedAt, String intakeChannel) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceSystem", sourceSystem);
        value.put("payloadChecksum", checksum);
        value.put("receivedAt", receivedAt.toString());
        value.put("intakeChannel", intakeChannel);
        value.put("hl7MessageType", envelope.messageType());
        value.put("controlId", envelope.controlId());
        value.put("patientId", envelope.patientId());
        value.put("rawPayload", rawPayload);

        RawEventPublisher.Published p = publisher.publish(KafkaTopics.HL7V2_RAW, EVENT_TYPE,
                partitionKey(sourceSystem, envelope, checksum), value, correlationId);
        return new Published(p.eventId(), p.key(), p.partition(), p.offset());
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
}
