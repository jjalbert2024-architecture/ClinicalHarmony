package com.clinicalharmony.fhircompliance.goldbuilder;

import com.clinicalharmony.fhircompliance.repository.GoldOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Relays {@code gold.outbox_events} to Kafka in id order (so per-key order is commit order) — same pattern as
 * bronze-listener-service and silver-transformer-service: one transaction per batch holding row locks; an event
 * is stamped {@code published_at} only after Kafka confirms it (acks=all); the first failure stops the batch so
 * nothing overtakes it, and the next poll retries. A crash between send and commit re-sends: at-least-once.
 */
@Component
@ConditionalOnProperty(prefix = "gold-builder", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GoldOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(GoldOutboxRelay.class);

    private final GoldOutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final GoldBuilderProperties props;
    private final Counter published;
    private final Counter failed;
    private final AtomicLong backlog = new AtomicLong();

    public GoldOutboxRelay(GoldOutboxRepository repository, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                           GoldBuilderProperties props, MeterRegistry meters) {
        this.repository = repository;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
        this.published = Counter.builder("clinicalharmony.gold.outbox.relayed").tag("outcome", "published").register(meters);
        this.failed = Counter.builder("clinicalharmony.gold.outbox.relayed").tag("outcome", "failed").register(meters);
        Gauge.builder("clinicalharmony.gold.outbox.backlog", backlog, AtomicLong::get)
                .description("Gold outbox events not yet confirmed by Kafka").register(meters);
    }

    @Scheduled(fixedDelayString = "${gold-builder.outbox.poll-interval}")
    public void poll() {
        try {
            int sent;
            do {
                Integer n = tx.execute(status -> relayBatch());
                sent = n == null ? 0 : n;
            } while (sent == props.outbox().batchSize());
            backlog.set(repository.backlog());
        } catch (Exception e) {
            log.atWarn().addKeyValue("event", "gold.outbox.poll_failed").addKeyValue("reason", e.getMessage())
                    .log("Outbox poll failed");
        }
    }

    /** @return number of events published in this batch */
    int relayBatch() {
        List<GoldOutboxRepository.OutboxEvent> batch = repository.lockBatch(props.outbox().batchSize());
        int sent = 0;
        for (GoldOutboxRepository.OutboxEvent event : batch) {
            String correlationId = event.headers().get("correlation-id");
            MDC.put("requestId", correlationId == null ? "gold-outbox" : correlationId);
            long start = System.nanoTime();
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.key(), event.payload());
                record.headers().add(header("event-id", event.eventId().toString()));
                record.headers().add(header("produced-at", Instant.now().toString()));
                for (Map.Entry<String, String> h : event.headers().entrySet()) {
                    record.headers().add(header(h.getKey(), h.getValue()));
                }
                RecordMetadata meta = kafka.send(record)
                        .get(props.outbox().sendTimeout().toMillis(), TimeUnit.MILLISECONDS).getRecordMetadata();
                repository.markPublished(event.id());
                published.increment();
                sent++;
                log.atInfo().addKeyValue("event", "gold.published")
                        .addKeyValue("correlationId", correlationId)
                        .addKeyValue("causationId", event.headers().get("causation-id"))
                        .addKeyValue("eventId", event.eventId())
                        .addKeyValue("masterPatientId", event.aggregateId())
                        .addKeyValue("topic", meta.topic())
                        .addKeyValue("partition", meta.partition())
                        .addKeyValue("offset", meta.offset())
                        .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                        .log("gold.patient.changed event relayed from outbox");
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                repository.markFailed(event.id(), e.toString());
                failed.increment();
                log.atWarn().addKeyValue("event", "gold.outbox.publish_failed")
                        .addKeyValue("eventId", event.eventId())
                        .addKeyValue("masterPatientId", event.aggregateId())
                        .addKeyValue("attempts", event.attempts() + 1)
                        .addKeyValue("reason", e.toString())
                        .log("Kafka did not confirm outbox event; will retry next poll");
                break;
            } finally {
                MDC.remove("requestId");
            }
        }
        return sent;
    }

    private static RecordHeader header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
