package com.clinicalharmony.bronze.listener;

import com.clinicalharmony.bronze.config.Topics;
import com.clinicalharmony.bronze.store.BronzeWriter;
import com.clinicalharmony.bronze.store.KafkaSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumer group {@value Topics#CONSUMER_GROUP} on {@value Topics#HL7V2_RAW}: every record becomes one
 * {@code bronze.raw_messages} row — PENDING (+ an outbox event) or QUARANTINED — or is recognized as a
 * duplicate. Exceptions thrown from here are TECHNICAL failures (retried, then dead-lettered by
 * {@code KafkaConfig}'s error handler); bad HL7 is never an exception.
 */
@Component
public class Hl7RawListener {

    private static final Logger log = LoggerFactory.getLogger(Hl7RawListener.class);

    private final BronzeWriter writer;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public Hl7RawListener(BronzeWriter writer, ObjectMapper mapper, MeterRegistry meters) {
        this.writer = writer;
        this.mapper = mapper;
        this.meters = meters;
    }

    @KafkaListener(id = "bronze-writer", topics = Topics.HL7V2_RAW, groupId = Topics.CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record) {
        long start = System.nanoTime();
        String correlationId = header(record, "correlation-id");
        String eventId = header(record, "event-id");
        MDC.put("correlationId", correlationId == null ? "" : correlationId);
        MDC.put("causationId", eventId == null ? "" : eventId);
        try {
            Hl7RawEvent event = Hl7RawEvent.parse(record.value(), mapper);
            KafkaSource source = new KafkaSource(record.topic(), record.partition(), record.offset(), record.key(),
                    eventId, correlationId);

            BronzeWriter.Result result = writer.store(event, source);

            long durationMs = (System.nanoTime() - start) / 1_000_000;
            var line = switch (result.outcome()) {
                case STORED -> log.atInfo().addKeyValue("event", "bronze.stored")
                        .addKeyValue("hl7MessageType", result.hl7MessageType())
                        .addKeyValue("outboxEventId", result.outboxEventId());
                case QUARANTINED -> log.atWarn().addKeyValue("event", "bronze.quarantined")
                        .addKeyValue("reason", result.error());
                case DUPLICATE -> log.atInfo().addKeyValue("event", "bronze.duplicate");
            };
            line.addKeyValue("rawMessageId", result.rawMessageId())
                    .addKeyValue("sourceSystem", event.sourceSystem())
                    .addKeyValue("controlId", event.controlId())
                    .addKeyValue("topic", record.topic())
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset())
                    .addKeyValue("durationMs", durationMs)
                    .log(switch (result.outcome()) {
                        case STORED -> "HL7 message stored in Bronze as PENDING";
                        case QUARANTINED -> "HL7 message stored in Bronze as QUARANTINED";
                        case DUPLICATE -> "HL7 message already in Bronze, skipped";
                    });
            meters.counter("clinicalharmony.bronze.messages", "outcome", result.outcome().name().toLowerCase()).increment();
        } finally {
            MDC.remove("correlationId");
            MDC.remove("causationId");
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
