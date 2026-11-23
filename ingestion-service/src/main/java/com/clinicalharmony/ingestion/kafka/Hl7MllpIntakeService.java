package com.clinicalharmony.ingestion.kafka;

import com.clinicalharmony.ingestion.service.Checksums;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Phase 9a MLLP intake: frame received → light peek at MSH/PID → publish to
 * {@value KafkaTopics#HL7V2_RAW} → ACK {@code AA} only once Kafka confirms (acks=all), {@code AE} if it
 * can't. Nothing is written to Postgres here; bronze-listener-service does that.
 *
 * <p>Since 9d the REST paths publish to Kafka too ({@code intake.RestIntakeService}).
 */
@Service
public class Hl7MllpIntakeService {

    private static final Logger log = LoggerFactory.getLogger(Hl7MllpIntakeService.class);

    private final Hl7RawPublisher publisher;
    private final String defaultSourceSystem;
    private final Counter accepted;
    private final Counter failed;
    private final Timer ackLatency;

    public Hl7MllpIntakeService(Hl7RawPublisher publisher,
                                @Value("${ingestion.hl7.default-source-system}") String defaultSourceSystem,
                                MeterRegistry meters) {
        this.publisher = publisher;
        this.defaultSourceSystem = defaultSourceSystem;
        this.accepted = Counter.builder("clinicalharmony.hl7.mllp.received").tag("outcome", "published")
                .description("MLLP messages published to Kafka and ACKed AA").register(meters);
        this.failed = Counter.builder("clinicalharmony.hl7.mllp.received").tag("outcome", "publish_failed")
                .description("MLLP messages Kafka did not confirm; NAKed AE").register(meters);
        this.ackLatency = Timer.builder("clinicalharmony.hl7.mllp.ack.latency")
                .description("MLLP frame received → ACK built (includes the Kafka acks=all round trip)").register(meters);
    }

    public record Outcome(boolean published, String ack, String correlationId, Hl7RawPublisher.Published record) {
    }

    public Outcome handle(String rawPayload) {
        long start = System.nanoTime();
        Instant receivedAt = Instant.now();
        String correlationId = newCorrelationId();
        MDC.put("correlationId", correlationId);
        try {
            Hl7Envelope envelope = Hl7Envelope.peek(rawPayload);
            String sourceSystem = envelope.sendingFacility() == null ? defaultSourceSystem : envelope.sendingFacility();
            String checksum = Checksums.sha256(rawPayload);

            log.atInfo().addKeyValue("event", "hl7.received")
                    .addKeyValue("sourceSystem", sourceSystem)
                    .addKeyValue("hl7MessageType", envelope.messageType())
                    .addKeyValue("controlId", envelope.controlId())
                    .addKeyValue("payloadBytes", rawPayload.length())
                    .log("HL7 message received over MLLP");

            String ackControlId = "ACK" + correlationId.substring(correlationId.indexOf('-') + 1);
            try {
                Hl7RawPublisher.Published published =
                        publisher.publish(rawPayload, sourceSystem, checksum, envelope, correlationId, receivedAt);
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                log.atInfo().addKeyValue("event", "hl7.published")
                        .addKeyValue("topic", KafkaTopics.HL7V2_RAW)
                        .addKeyValue("partition", published.partition())
                        .addKeyValue("offset", published.offset())
                        .addKeyValue("eventId", published.eventId())
                        .addKeyValue("messageKey", published.key())
                        .addKeyValue("durationMs", durationMs)
                        .log("HL7 message published to Kafka, ACK AA");
                accepted.increment();
                return new Outcome(true, Hl7AckBuilder.accept(envelope, ackControlId), correlationId, published);
            } catch (PublishFailedException e) {
                long durationMs = (System.nanoTime() - start) / 1_000_000;
                log.atError().addKeyValue("event", "hl7.publish_failed")
                        .addKeyValue("topic", KafkaTopics.HL7V2_RAW)
                        .addKeyValue("durationMs", durationMs)
                        .addKeyValue("reason", e.getMessage())
                        .log("Kafka did not confirm the HL7 message, NAK AE (sender should retry)");
                failed.increment();
                return new Outcome(false,
                        Hl7AckBuilder.error(envelope, ackControlId, "Message not stored, retry later: Kafka unavailable"),
                        correlationId, null);
            }
        } finally {
            ackLatency.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
            MDC.remove("correlationId");
        }
    }

    /** Same shape as the dashboard's {@code ui-<12 hex>}, so ids from both entry points read alike. */
    static String newCorrelationId() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "mllp-" + HexFormat.of().formatHex(bytes);
    }
}
