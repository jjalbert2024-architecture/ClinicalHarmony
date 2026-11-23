package com.clinicalharmony.fhircompliance.goldbuilder;

import com.clinicalharmony.fhircompliance.service.silver.SilverPatientBuildResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Consumer group {@value Topics#CONSUMER_GROUP} on {@value Topics#SILVER_PATIENT_CHANGED}: each record rebuilds
 * that Silver patient's US Core resources into Gold and queues {@value Topics#GOLD_PATIENT_CHANGED}
 * ({@link GoldBuilder#build}, one transaction). The offset is committed after that commit ({@code ack-mode: record}).
 * Exceptions thrown from here are technical failures (retried, then dead-lettered) or a broken event (straight to
 * the DLT) — a non-compliant resource is never an exception.
 */
@Component
@ConditionalOnProperty(prefix = "gold-builder", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SilverPatientChangedListener {

    private static final Logger log = LoggerFactory.getLogger(SilverPatientChangedListener.class);

    private final GoldBuilder builder;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final Timer endToEnd;

    public SilverPatientChangedListener(GoldBuilder builder, ObjectMapper mapper, MeterRegistry meters) {
        this.builder = builder;
        this.mapper = mapper;
        this.meters = meters;
        this.endToEnd = Timer.builder("clinicalharmony.gold.end_to_end.latency")
                .description("Intake (MLLP or REST) of the raw payload → its patient's Gold build committed")
                .register(meters);
    }

    @KafkaListener(id = Topics.CONSUMER_GROUP, topics = Topics.SILVER_PATIENT_CHANGED, groupId = Topics.CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record) {
        long start = System.nanoTime();
        String correlationId = header(record, "correlation-id");
        String eventId = header(record, "event-id");
        MDC.put("correlationId", correlationId == null ? "" : correlationId);
        MDC.put("causationId", eventId == null ? "" : eventId);
        // The service's log pattern prints [requestId]; reuse it so a Kafka-driven build greps like an HTTP one.
        MDC.put("requestId", correlationId == null ? "gold-builder" : correlationId);
        try {
            SilverPatientChangedEvent event = SilverPatientChangedEvent.parse(record.value(), mapper);
            log.atInfo().addKeyValue("event", "gold.consumed")
                    .addKeyValue("silverPatientId", event.silverPatientId())
                    .addKeyValue("rawMessageId", event.rawMessageId())
                    .addKeyValue("topic", record.topic())
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset())
                    .log("silver.patient.changed event received");

            GoldBuilder.Result result = builder.build(event,
                    new KafkaSource(record.topic(), record.partition(), record.offset(), record.key(), eventId, correlationId));
            SilverPatientBuildResult build = result.build();

            // MLLP / REST receipt of the raw payload (Bronze received_at) → this Gold build: the end-to-end latency, as a
            // timer (metrics) and on the log line (so Splunk can also compute exact percentiles from the logs).
            Long endToEndMs = builder.bronzeReceivedAt(event.rawMessageId())
                    .map(received -> {
                        Duration latency = GoldBuilder.since(received, Instant.now());
                        endToEnd.record(latency);
                        return latency.toMillis();
                    })
                    .orElse(null);
            log.atInfo().addKeyValue("event", "gold.built")
                    .addKeyValue("silverPatientId", build.silverPatientId())
                    .addKeyValue("masterPatientId", build.masterPatientId())
                    .addKeyValue("rawMessageId", event.rawMessageId())
                    .addKeyValue("resources", build.resourceCount())
                    .addKeyValue("compliant", build.compliantCount())
                    .addKeyValue("nonCompliant", build.resourceCount() - build.compliantCount())
                    .addKeyValue("outboxEventId", result.outboxEventId())
                    .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                    .addKeyValue("endToEndMs", endToEndMs)
                    .log("Silver patient built into Gold (resources, master and BRONZE → SILVER → GOLD lineage)");
            log.atDebug().addKeyValue("event", "gold.lineage.written")
                    .addKeyValue("masterPatientId", build.masterPatientId())
                    .addKeyValue("lineageRows", build.resourceCount() + 1)
                    .log("Lineage upserted for the master and every resource");
            meters.counter("clinicalharmony.gold.builds", "outcome", "built").increment();
            meters.counter("clinicalharmony.gold.resources", "compliant", "true").increment(build.compliantCount());
            meters.counter("clinicalharmony.gold.resources", "compliant", "false")
                    .increment(build.resourceCount() - build.compliantCount());
        } finally {
            MDC.remove("correlationId");
            MDC.remove("causationId");
            MDC.remove("requestId");
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
