package com.clinicalharmony.ingestion.intake;

import com.clinicalharmony.ingestion.kafka.Hl7Envelope;
import com.clinicalharmony.ingestion.kafka.Hl7RawPublisher;
import com.clinicalharmony.ingestion.kafka.PayloadTooLargeException;
import com.clinicalharmony.ingestion.kafka.PublishFailedException;
import com.clinicalharmony.ingestion.kafka.RawEventPublisher;
import com.clinicalharmony.ingestion.service.Checksums;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Phase 9d REST intake: syntax check → publish to {@code clinicalharmony.bronze.<format>.raw} (blocking on
 * {@code acks=all}) → tell the caller. Nothing is written to Postgres here; bronze-listener-service stores the
 * payload (and, for claims, splits the file into one row per claim line).
 *
 * <p>A payload that fails the syntax check is STILL published (Bronze quarantines, never drops) and the caller
 * gets 422. If Kafka doesn't confirm, nothing was stored: {@link PublishFailedException} → 503 + Retry-After.
 */
@Service
public class RestIntakeService {

    private static final Logger log = LoggerFactory.getLogger(RestIntakeService.class);

    /** Bronze correlation_id is VARCHAR(64); keep caller-supplied ids log- and header-safe. */
    private static final Pattern CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    /** Bronze source_system is VARCHAR(100). */
    private static final Pattern SOURCE_SYSTEM = Pattern.compile("[A-Za-z0-9 ._:-]{1,100}");

    private final FormatChecks checks;
    private final RawEventPublisher publisher;
    private final Hl7RawPublisher hl7Publisher;
    private final MeterRegistry meters;
    private final Map<IntakeFormat, String> defaultSources;

    public RestIntakeService(FormatChecks checks, RawEventPublisher publisher, Hl7RawPublisher hl7Publisher,
                             MeterRegistry meters,
                             @Value("${ingestion.hl7.default-source-system}") String hl7Default,
                             @Value("${ingestion.fhir.default-source-system}") String fhirDefault,
                             @Value("${ingestion.claims.default-source-system}") String claimsDefault,
                             @Value("${ingestion.ccda.default-source-system:UNKNOWN_CCDA_SOURCE}") String ccdaDefault) {
        this.checks = checks;
        this.publisher = publisher;
        this.hl7Publisher = hl7Publisher;
        this.meters = meters;
        this.defaultSources = Map.of(IntakeFormat.HL7V2, hl7Default, IntakeFormat.FHIR_JSON, fhirDefault,
                IntakeFormat.CLAIMS_CSV, claimsDefault, IntakeFormat.CCDA_XML, ccdaDefault);
    }

    /**
     * The payload was NOT stored. {@code status}: 400 unusable header, 413 too large for one Kafka record,
     * 503 Kafka didn't confirm (the caller should retry after {@code retryAfterSeconds}).
     */
    public static class IntakeRejectedException extends RuntimeException {
        private final int status;
        private final String error;
        private final String correlationId;
        private final Integer retryAfterSeconds;

        IntakeRejectedException(int status, String error, String message, String correlationId, Integer retryAfterSeconds,
                                Throwable cause) {
            super(message, cause);
            this.status = status;
            this.error = error;
            this.correlationId = correlationId;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int status() {
            return status;
        }

        public String error() {
            return error;
        }

        public String correlationId() {
            return correlationId;
        }

        public Integer retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** Seconds a caller should wait before resending after a 503. */
    static final int RETRY_AFTER_SECONDS = 5;

    /**
     * @param sourceHeader      X-Source-System (ignored for HL7: MSH-4 names the source, as on MLLP)
     * @param correlationHeader X-Correlation-Id from the caller (the dashboard / gateway), else one is minted
     */
    public RestIntakeResult accept(IntakeFormat format, String raw, String sourceHeader, String correlationHeader) {
        String correlationId = correlationId(correlationHeader);
        MDC.put("correlationId", correlationId);
        long start = System.nanoTime();
        try {
            Instant receivedAt = Instant.now();
            String payload = raw == null ? "" : raw;
            String checksum = Checksums.sha256(payload);
            String sourceSystem = sourceSystem(format, payload, sourceHeader);
            if (sourceSystem == null) {
                count(format, "bad_header");
                throw new IntakeRejectedException(400, "bad_header",
                        "X-Source-System must be 1–100 letters, digits, spaces or ._:- characters", correlationId, null, null);
            }
            SyntaxCheck check = check(format, payload);

            log.atInfo().addKeyValue("event", "rest.received")
                    .addKeyValue("format", format.name())
                    .addKeyValue("sourceSystem", sourceSystem)
                    .addKeyValue("syntaxValid", check.valid())
                    .addKeyValue("payloadBytes", payload.length())
                    .log("REST payload received");

            RawEventPublisher.Published published;
            try {
                published = publish(format, payload, sourceSystem, checksum, check, correlationId, receivedAt);
            } catch (PayloadTooLargeException e) {
                count(format, "too_large");
                log.atWarn().addKeyValue("event", "rest.too_large").addKeyValue("format", format.name())
                        .addKeyValue("reason", e.getMessage()).log("REST payload too large for one Kafka record, rejected");
                throw new IntakeRejectedException(413, "payload_too_large", e.getMessage(), correlationId, null, e);
            } catch (PublishFailedException e) {
                count(format, "publish_failed");
                log.atError().addKeyValue("event", "rest.publish_failed").addKeyValue("format", format.name())
                        .addKeyValue("topic", format.topic())
                        .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                        .addKeyValue("reason", e.getMessage())
                        .log("Kafka did not confirm the REST payload, 503 (caller should retry)");
                throw new IntakeRejectedException(503, "kafka_unavailable", "Not stored, retry later: " + e.getMessage(),
                        correlationId, RETRY_AFTER_SECONDS, e);
            }

            count(format, check.valid() ? "published" : "published_invalid");
            log.atInfo().addKeyValue("event", "rest.published")
                    .addKeyValue("format", format.name())
                    .addKeyValue("topic", format.topic())
                    .addKeyValue("partition", published.partition())
                    .addKeyValue("offset", published.offset())
                    .addKeyValue("eventId", published.eventId())
                    .addKeyValue("messageKey", published.key())
                    .addKeyValue("syntaxValid", check.valid())
                    .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                    .log(check.valid() ? "REST payload published to Kafka, 202"
                            : "REST payload failed the syntax check, published for quarantine, 422");

            return new RestIntakeResult(check.valid(), format.name(), sourceSystem, correlationId, published.eventId(),
                    format.topic(), published.partition(), published.offset(), checksum, receivedAt.toString(),
                    check.details().isEmpty() ? null : check.details(), check.error(),
                    check.valid()
                            ? "Published to Kafka; bronze-listener-service stores it in Bronze. Trace it by correlationId."
                            : "Failed the syntax check. Published anyway: Bronze keeps it as QUARANTINED for a data steward.");
        } finally {
            MDC.remove("correlationId");
        }
    }

    private SyntaxCheck check(IntakeFormat format, String payload) {
        return switch (format) {
            case HL7V2 -> checks.hl7(payload);
            case FHIR_JSON -> checks.fhir(payload);
            case CLAIMS_CSV -> checks.claims(payload);
            case CCDA_XML -> checks.ccda(payload);
        };
    }

    private RawEventPublisher.Published publish(IntakeFormat format, String payload, String sourceSystem, String checksum,
                                                SyntaxCheck check, String correlationId, Instant receivedAt) {
        if (format == IntakeFormat.HL7V2) {
            // Same topic, value and key as MLLP, so both doors look alike downstream.
            Hl7RawPublisher.Published p = hl7Publisher.publish(payload, sourceSystem, checksum, Hl7Envelope.peek(payload),
                    correlationId, receivedAt, Hl7RawPublisher.CHANNEL_REST);
            return new RawEventPublisher.Published(p.eventId(), p.key(), p.partition(), p.offset());
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceSystem", sourceSystem);
        value.put("payloadChecksum", checksum);
        value.put("receivedAt", receivedAt.toString());
        value.put("intakeChannel", Hl7RawPublisher.CHANNEL_REST);
        value.put("messageType", format.name());
        value.putAll(check.details());
        value.put("rawPayload", payload);
        String key = check.patientId() != null ? sourceSystem + "|" + check.patientId() : "sha256:" + checksum;
        return publisher.publish(format.topic(), format.eventType(), key, value, correlationId);
    }

    private String sourceSystem(IntakeFormat format, String payload, String header) {
        if (format == IntakeFormat.HL7V2) {
            String facility = Hl7Envelope.peek(payload).sendingFacility();
            return facility == null || facility.isBlank() ? defaultSources.get(format) : facility;
        }
        if (header == null || header.isBlank()) {
            return defaultSources.get(format);
        }
        String h = header.strip();
        return SOURCE_SYSTEM.matcher(h).matches() ? h : null;   // null → 400
    }

    static String correlationId(String header) {
        if (header != null && CORRELATION_ID.matcher(header.strip()).matches()) {
            return header.strip();
        }
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "rest-" + HexFormat.of().formatHex(bytes);
    }

    private void count(IntakeFormat format, String outcome) {
        meters.counter("clinicalharmony.ingestion.rest.received", "format", format.tag(), "outcome", outcome).increment();
    }
}
