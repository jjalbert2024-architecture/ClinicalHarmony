package com.clinicalharmony.fhircompliance.goldbuilder;

import com.clinicalharmony.fhircompliance.gold.GoldWriter;
import com.clinicalharmony.fhircompliance.service.silver.BuiltResourceSummary;
import com.clinicalharmony.fhircompliance.service.silver.SilverFhirBuildService;
import com.clinicalharmony.fhircompliance.service.silver.SilverPatientBuildResult;
import com.clinicalharmony.fhircompliance.service.silver.SilverPatientNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One Silver patient → Gold, in ONE transaction: the existing Silver → US Core build
 * ({@link SilverFhirBuildService#buildPatient}, which writes the master, every resource with its validator
 * findings and the BRONZE → SILVER → GOLD lineage through {@link GoldWriter}) and the
 * {@code clinicalharmony.gold.patient.changed} outbox row (also through GoldWriter). Either everything commits —
 * and the event will be relayed — or nothing does and the Kafka record is retried.
 *
 * <p>Idempotent: resources upsert on (resource_type, fhir_id), lineage on (target, step), and the master is
 * reused. A redelivered event rebuilds the same rows and announces the patient again, which the debounced
 * pmi-matcher absorbs.
 */
@Service
public class GoldBuilder {

    public record Result(SilverPatientBuildResult build, UUID outboxEventId) {
    }

    private final SilverFhirBuildService buildService;
    private final GoldWriter goldWriter;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    public GoldBuilder(SilverFhirBuildService buildService, GoldWriter goldWriter, TransactionTemplate tx,
                       JdbcTemplate jdbc) {
        this.buildService = buildService;
        this.goldWriter = goldWriter;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    public Result build(SilverPatientChangedEvent event, KafkaSource source) {
        try {
            return tx.execute(status -> {
                SilverPatientBuildResult result = buildService.buildPatient(event.silverPatientId());
                UUID eventId = goldWriter.recordEvent(Topics.GOLD_PATIENT_CHANGED, Topics.EVENT_TYPE,
                        String.valueOf(result.masterPatientId()), payload(event, result), headers(source),
                        result.masterPatientId());
                return new Result(result, eventId);
            });
        } catch (SilverPatientNotFoundException e) {
            // The Silver transformer announces a patient only after its commit, so this record can't be built.
            throw new InvalidEventException("No silver.patients row with id " + event.silverPatientId(), e);
        }
    }

    /** When the raw HL7 message behind this change reached Bronze (MLLP receipt time), if the event names one. */
    public Optional<Instant> bronzeReceivedAt(Long rawMessageId) {
        if (rawMessageId == null) {
            return Optional.empty();
        }
        return jdbc.query("SELECT received_at FROM bronze.raw_messages WHERE id = ?",
                        (rs, i) -> rs.getObject("received_at", OffsetDateTime.class), rawMessageId)
                .stream().findFirst().map(OffsetDateTime::toInstant);
    }

    static Map<String, Object> payload(SilverPatientChangedEvent event, SilverPatientBuildResult result) {
        List<Map<String, Object>> resources = new ArrayList<>();
        for (BuiltResourceSummary r : result.resources()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("resourceType", r.resourceType());
            m.put("fhirId", r.fhirId());
            m.put("goldId", r.goldId());
            m.put("usCoreCompliant", r.usCoreCompliant());
            m.put("errorCount", r.errorCount());
            resources.add(m);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("masterPatientId", result.masterPatientId());
        payload.put("silverPatientId", result.silverPatientId());
        payload.put("patientFhirId", result.patientFhirId());
        payload.put("sourceSystem", event.sourceSystem());
        payload.put("sourcePatientId", event.sourcePatientId());
        payload.put("rawMessageId", event.rawMessageId());
        payload.put("silverChange", event.change());
        payload.put("compliance", Map.of("resources", result.resourceCount(), "compliant", result.compliantCount(),
                "nonCompliant", result.resourceCount() - result.compliantCount()));
        payload.put("resources", resources);
        return payload;
    }

    static Map<String, String> headers(KafkaSource source) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("event-type", Topics.EVENT_TYPE);
        headers.put("schema-version", "1");
        headers.put("producer", Topics.PRODUCER);
        if (source.correlationId() != null) {
            headers.put("correlation-id", source.correlationId());
        }
        if (source.eventId() != null) {
            headers.put("causation-id", source.eventId());
        }
        return headers;
    }

    static Duration since(Instant start, Instant end) {
        return Duration.between(start, end).isNegative() ? Duration.ZERO : Duration.between(start, end);
    }
}
