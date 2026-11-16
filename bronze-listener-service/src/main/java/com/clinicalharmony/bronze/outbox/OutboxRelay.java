package com.clinicalharmony.bronze.outbox;

import com.clinicalharmony.bronze.config.BronzeProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
 * Relays {@code bronze.outbox_events} to Kafka in id order (so per-key order is the commit order).
 * Each batch runs in one transaction holding row locks: an event is stamped {@code published_at} only
 * after Kafka confirms it (acks=all). On the first failure the batch stops — later events for the same
 * key must not overtake it — the failed row gets {@code attempts}/{@code last_error}, and the next poll
 * tries again. A crash after the send but before the commit re-sends that event: at-least-once, which
 * is why every consumer is idempotent.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final BronzeProperties props;
    private final Counter published;
    private final Counter failed;
    private final AtomicLong backlog = new AtomicLong();

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       BronzeProperties props, MeterRegistry meters) {
        this.repository = repository;
        this.kafka = kafka;
        this.tx = tx;
        this.props = props;
        this.published = Counter.builder("clinicalharmony.bronze.outbox.relayed").tag("outcome", "published").register(meters);
        this.failed = Counter.builder("clinicalharmony.bronze.outbox.relayed").tag("outcome", "failed").register(meters);
        Gauge.builder("clinicalharmony.bronze.outbox.backlog", backlog, AtomicLong::get)
                .description("Outbox events not yet confirmed by Kafka").register(meters);
    }

    @Scheduled(fixedDelayString = "${bronze.outbox.poll-interval}")
    public void poll() {
        try {
            int sent;
            do {
                Integer n = tx.execute(status -> relayBatch());
                sent = n == null ? 0 : n;
            } while (sent == props.outbox().batchSize());
            backlog.set(repository.backlog());
        } catch (Exception e) {
            // e.g. database down: the scheduler simply tries again next poll.
            log.atWarn().addKeyValue("event", "bronze.outbox.poll_failed").addKeyValue("reason", e.getMessage())
                    .log("Outbox poll failed");
        }
    }

    /** @return number of events published in this batch */
    int relayBatch() {
        List<OutboxRepository.OutboxEvent> batch = repository.lockBatch(props.outbox().batchSize());
        int sent = 0;
        for (OutboxRepository.OutboxEvent event : batch) {
            String correlationId = event.headers().get("correlation-id");
            MDC.put("correlationId", correlationId == null ? "" : correlationId);
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
                log.atInfo().addKeyValue("event", "bronze.published")
                        .addKeyValue("causationId", event.headers().get("causation-id"))
                        .addKeyValue("eventId", event.eventId())
                        .addKeyValue("rawMessageId", event.aggregateId())
                        .addKeyValue("topic", meta.topic())
                        .addKeyValue("partition", meta.partition())
                        .addKeyValue("offset", meta.offset())
                        .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                        .log("bronze.ingested event relayed from outbox");
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                repository.markFailed(event.id(), e.toString());
                failed.increment();
                log.atWarn().addKeyValue("event", "bronze.outbox.publish_failed")
                        .addKeyValue("eventId", event.eventId())
                        .addKeyValue("rawMessageId", event.aggregateId())
                        .addKeyValue("attempts", event.attempts() + 1)
                        .addKeyValue("reason", e.toString())
                        .log("Kafka did not confirm outbox event; will retry next poll");
                break;
            } finally {
                MDC.remove("correlationId");
            }
        }
        return sent;
    }

    private static RecordHeader header(String name, String value) {
        return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
