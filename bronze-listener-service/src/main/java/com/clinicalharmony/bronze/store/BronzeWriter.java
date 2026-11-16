package com.clinicalharmony.bronze.store;

import com.clinicalharmony.bronze.config.Topics;
import com.clinicalharmony.bronze.hl7.Hl7Inspector;
import com.clinicalharmony.bronze.listener.Hl7RawEvent;
import com.clinicalharmony.bronze.listener.InvalidEventException;
import com.clinicalharmony.bronze.outbox.OutboxRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One transaction per Kafka record: the Bronze row and (for PENDING rows) its
 * {@code clinicalharmony.bronze.ingested} outbox event commit together or not at all. The listener's
 * offset is committed only after this returns (ack-mode RECORD).
 *
 * <p>Idempotent: the insert is {@code ON CONFLICT DO NOTHING} on migration 23's partial unique index
 * (source_system, payload_checksum) for MLLP_KAFKA rows. A redelivered record — or the same message
 * sent twice — is a DUPLICATE: nothing written, no second event.
 */
@Service
public class BronzeWriter {

    public enum Outcome { STORED, QUARANTINED, DUPLICATE }

    public record Result(Outcome outcome, long rawMessageId, String hl7MessageType, String error, UUID outboxEventId) {
    }

    public static final String CHANNEL = "MLLP_KAFKA";

    private static final String INSERT_SQL = """
            INSERT INTO bronze.raw_messages
                (source_system, message_type, message_format, raw_payload, payload_checksum, processing_status,
                 error_message, received_at, intake_channel, correlation_id, kafka_topic, kafka_partition, kafka_offset)
            VALUES (?, 'HL7V2', 'TEXT', ?, ?, ?, ?, ?, 'MLLP_KAFKA', ?, ?, ?, ?)
            ON CONFLICT (source_system, payload_checksum) WHERE intake_channel = 'MLLP_KAFKA' DO NOTHING
            RETURNING id
            """;

    private final JdbcTemplate jdbc;
    private final Hl7Inspector inspector;
    private final OutboxRepository outbox;

    public BronzeWriter(JdbcTemplate jdbc, Hl7Inspector inspector, OutboxRepository outbox) {
        this.jdbc = jdbc;
        this.inspector = inspector;
        this.outbox = outbox;
    }

    @Transactional
    public Result store(Hl7RawEvent event, KafkaSource source) {
        String actual = sha256(event.rawPayload());
        if (!actual.equals(event.payloadChecksum())) {
            throw new InvalidEventException("payloadChecksum does not match rawPayload (got " + actual + ")");
        }

        Hl7Inspector.Result inspection = inspector.inspect(event.rawPayload());
        String status = inspection.parsed() ? "PENDING" : "QUARANTINED";
        Instant receivedAt = event.receivedAt() == null ? Instant.now() : event.receivedAt();

        List<Long> ids = jdbc.queryForList(INSERT_SQL, Long.class,
                event.sourceSystem(), event.rawPayload(), event.payloadChecksum(), status, inspection.error(),
                Timestamp.from(receivedAt), source.correlationId(), source.topic(), source.partition(), source.offset());

        if (ids.isEmpty()) {
            Long existing = jdbc.queryForObject("""
                    SELECT id FROM bronze.raw_messages
                    WHERE source_system = ? AND payload_checksum = ? AND intake_channel = 'MLLP_KAFKA'
                    """, Long.class, event.sourceSystem(), event.payloadChecksum());
            return new Result(Outcome.DUPLICATE, existing == null ? -1 : existing, inspection.messageType(), null, null);
        }

        long id = ids.get(0);
        if (!inspection.parsed()) {
            // Quarantined messages stay in Bronze for a data steward; nothing is announced downstream.
            return new Result(Outcome.QUARANTINED, id, null, inspection.error(), null);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rawMessageId", id);
        payload.put("messageType", "HL7V2");
        payload.put("hl7MessageType", inspection.messageType());
        payload.put("sourceSystem", event.sourceSystem());
        payload.put("payloadChecksum", event.payloadChecksum());
        payload.put("controlId", inspection.controlId());
        payload.put("receivedAt", receivedAt.toString());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("event-type", "bronze.ingested");
        headers.put("schema-version", "1");
        headers.put("producer", "bronze-listener-service");
        if (source.correlationId() != null) {
            headers.put("correlation-id", source.correlationId());
        }
        if (source.eventId() != null) {
            headers.put("causation-id", source.eventId());
        }

        UUID eventId = outbox.insert(Topics.BRONZE_INGESTED, "bronze.ingested", source.key(), payload, headers, id);
        return new Result(Outcome.STORED, id, inspection.messageType(), null, eventId);
    }

    static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
