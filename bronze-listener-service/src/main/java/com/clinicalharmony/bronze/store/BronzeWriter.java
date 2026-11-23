package com.clinicalharmony.bronze.store;

import com.clinicalharmony.bronze.config.Topics;
import com.clinicalharmony.bronze.inspect.BronzeFormat;
import com.clinicalharmony.bronze.inspect.PayloadInspector;
import com.clinicalharmony.bronze.listener.InvalidEventException;
import com.clinicalharmony.bronze.listener.RawEvent;
import com.clinicalharmony.bronze.outbox.OutboxRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One transaction per Kafka record: the Bronze row(s) and, for each PENDING row, its
 * {@code clinicalharmony.bronze.ingested} outbox event commit together or not at all. The listener's offset is
 * committed only after this returns (ack-mode RECORD).
 *
 * <p>A claims file is ONE record and becomes one row per claim line (header + that line), all in this one
 * transaction — all-or-nothing per file; a file that fails the check is ONE quarantined row.
 *
 * <p>Idempotent: every insert is {@code ON CONFLICT DO NOTHING} on migration 26's partial unique index
 * (source_system, payload_checksum) over the Kafka channels (MLLP_KAFKA + REST_KAFKA). A redelivered record —
 * or the same payload sent twice, through either door — is a DUPLICATE: nothing written, no second event.
 * For a claims file each line is checked on its own, so a resent file whose lines are all stored is a DUPLICATE.
 */
@Service
public class BronzeWriter {

    public enum Outcome { STORED, QUARANTINED, DUPLICATE }

    /**
     * @param rawMessageIds the rows written, or for a DUPLICATE the existing rows
     * @param duplicates    rows skipped because they were already stored (claims lines can be partly duplicate)
     */
    public record Result(Outcome outcome, BronzeFormat format, List<Long> rawMessageIds, int duplicates,
                         Map<String, Object> details, String error, List<UUID> outboxEventIds) {

        /** The first row's id (-1 if none) — enough for single-row formats and for logs. */
        public long rawMessageId() {
            return rawMessageIds.isEmpty() ? -1 : rawMessageIds.get(0);
        }
    }

    private static final String INSERT_SQL = """
            INSERT INTO bronze.raw_messages
                (source_system, message_type, message_format, raw_payload, payload_checksum, processing_status,
                 error_message, received_at, intake_channel, correlation_id, kafka_topic, kafka_partition, kafka_offset)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (source_system, payload_checksum) WHERE intake_channel IN ('MLLP_KAFKA', 'REST_KAFKA') DO NOTHING
            RETURNING id
            """;

    private static final String EXISTING_SQL = """
            SELECT id FROM bronze.raw_messages
            WHERE source_system = ? AND payload_checksum = ? AND intake_channel IN ('MLLP_KAFKA', 'REST_KAFKA')
            """;

    private final JdbcTemplate jdbc;
    private final PayloadInspector inspector;
    private final OutboxRepository outbox;

    public BronzeWriter(JdbcTemplate jdbc, PayloadInspector inspector, OutboxRepository outbox) {
        this.jdbc = jdbc;
        this.inspector = inspector;
        this.outbox = outbox;
    }

    @Transactional
    public Result store(BronzeFormat format, RawEvent event, KafkaSource source) {
        String actual = sha256(event.rawPayload());
        if (!actual.equals(event.payloadChecksum())) {
            throw new InvalidEventException("payloadChecksum does not match rawPayload (got " + actual + ")");
        }
        Instant receivedAt = event.receivedAt() == null ? Instant.now() : event.receivedAt();
        PayloadInspector.Inspection inspection = inspector.inspect(format, event.rawPayload());

        if (!inspection.parsed()) {
            // Quarantined payloads stay in Bronze (a claims file whole) for a data steward; nothing is announced.
            Long id = insert(format, event, source, event.rawPayload(), event.payloadChecksum(), "QUARANTINED",
                    inspection.error(), receivedAt);
            return id == null
                    ? duplicate(format, event, List.of(event.payloadChecksum()), inspection)
                    : new Result(Outcome.QUARANTINED, format, List.of(id), 0, Map.of(), inspection.error(), List.of());
        }

        List<String> rows = inspection.rowPayloads();
        List<Long> stored = new ArrayList<>();
        List<UUID> events = new ArrayList<>();
        List<String> duplicateChecksums = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String payload = rows.get(i);
            // Single-payload formats keep the event's checksum; each claims line gets its own.
            String checksum = rows.size() == 1 && payload.equals(event.rawPayload()) ? event.payloadChecksum() : sha256(payload);
            Long id = insert(format, event, source, payload, checksum, "PENDING", null, receivedAt);
            if (id == null) {
                duplicateChecksums.add(checksum);
                continue;
            }
            stored.add(id);
            events.add(announce(format, event, source, id, checksum, receivedAt, inspection.details(),
                    format == BronzeFormat.CLAIMS_CSV ? i + 1 : null, rows.size()));
        }
        if (stored.isEmpty()) {
            return duplicate(format, event, duplicateChecksums, inspection);
        }
        return new Result(Outcome.STORED, format, stored, duplicateChecksums.size(), inspection.details(), null, events);
    }

    private Long insert(BronzeFormat format, RawEvent event, KafkaSource source, String payload, String checksum,
                        String status, String error, Instant receivedAt) {
        List<Long> ids = jdbc.queryForList(INSERT_SQL, Long.class,
                event.sourceSystem(), format.messageType(), format.messageFormat(), payload, checksum, status, error,
                Timestamp.from(receivedAt), event.bronzeChannel(), source.correlationId(), source.topic(),
                source.partition(), source.offset());
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Result duplicate(BronzeFormat format, RawEvent event, List<String> checksums,
                             PayloadInspector.Inspection inspection) {
        List<Long> existing = new ArrayList<>();
        for (String checksum : checksums) {
            existing.addAll(jdbc.queryForList(EXISTING_SQL, Long.class, event.sourceSystem(), checksum));
        }
        return new Result(Outcome.DUPLICATE, format, existing, checksums.size(), inspection.details(), null, List.of());
    }

    private UUID announce(BronzeFormat format, RawEvent event, KafkaSource source, long id, String checksum,
                          Instant receivedAt, Map<String, Object> details, Integer claimLine, int claimLines) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rawMessageId", id);
        payload.put("messageType", format.messageType());
        payload.put("intakeChannel", event.bronzeChannel());
        payload.put("sourceSystem", event.sourceSystem());
        payload.put("payloadChecksum", checksum);
        payload.put("receivedAt", receivedAt.toString());
        if (format == BronzeFormat.CLAIMS_CSV) {
            payload.put("claimLine", claimLine);
            payload.put("claimLines", claimLines);
        } else {
            payload.putAll(details);   // hl7MessageType + controlId, fhirResourceType + count, ccdaDocumentId
        }

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
        return outbox.insert(Topics.BRONZE_INGESTED, "bronze.ingested", source.key(), payload, headers, id);
    }

    static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
