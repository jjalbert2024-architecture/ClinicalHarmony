package com.clinicalharmony.bronze.listener;

import com.clinicalharmony.bronze.config.Topics;
import com.clinicalharmony.bronze.inspect.BronzeFormat;
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
 * Consumer group {@value Topics#CONSUMER_GROUP} on all four raw intake topics (HL7 v2, FHIR JSON, claims CSV,
 * C-CDA): every record becomes {@code bronze.raw_messages} row(s) — PENDING (+ an outbox event each) or
 * QUARANTINED — or is recognized as a duplicate. Exceptions thrown from here are TECHNICAL failures (retried,
 * then dead-lettered to {@code <topic>.dlt} by {@code KafkaConfig}'s error handler); bad data is never an exception.
 */
@Component
public class RawIntakeListener {

    private static final Logger log = LoggerFactory.getLogger(RawIntakeListener.class);

    private final BronzeWriter writer;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public RawIntakeListener(BronzeWriter writer, ObjectMapper mapper, MeterRegistry meters) {
        this.writer = writer;
        this.mapper = mapper;
        this.meters = meters;
    }

    @KafkaListener(id = "bronze-writer", groupId = Topics.CONSUMER_GROUP,
            topics = {Topics.HL7V2_RAW, Topics.FHIR_RAW, Topics.CLAIMS_RAW, Topics.CCDA_RAW})
    public void onMessage(ConsumerRecord<String, String> record) {
        long start = System.nanoTime();
        String correlationId = header(record, "correlation-id");
        String eventId = header(record, "event-id");
        MDC.put("correlationId", correlationId == null ? "" : correlationId);
        MDC.put("causationId", eventId == null ? "" : eventId);
        try {
            BronzeFormat format = BronzeFormat.ofTopic(record.topic());
            RawEvent event = RawEvent.parse(record.value(), mapper);
            KafkaSource source = new KafkaSource(record.topic(), record.partition(), record.offset(), record.key(),
                    eventId, correlationId);

            BronzeWriter.Result result = writer.store(format, event, source);

            long durationMs = (System.nanoTime() - start) / 1_000_000;
            var line = switch (result.outcome()) {
                case STORED -> log.atInfo().addKeyValue("event", "bronze.stored")
                        .addKeyValue("rows", result.rawMessageIds().size())
                        .addKeyValue("outboxEventIds", result.outboxEventIds());
                case QUARANTINED -> log.atWarn().addKeyValue("event", "bronze.quarantined")
                        .addKeyValue("reason", result.error());
                case DUPLICATE -> log.atInfo().addKeyValue("event", "bronze.duplicate");
            };
            result.details().forEach(line::addKeyValue);
            line.addKeyValue("messageType", format.messageType())
                    .addKeyValue("intakeChannel", event.bronzeChannel())
                    .addKeyValue("rawMessageId", result.rawMessageId())
                    .addKeyValue("rawMessageIds", result.rawMessageIds())
                    .addKeyValue("duplicates", result.duplicates())
                    .addKeyValue("sourceSystem", event.sourceSystem())
                    .addKeyValue("topic", record.topic())
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset())
                    .addKeyValue("durationMs", durationMs)
                    .log(switch (result.outcome()) {
                        case STORED -> format.messageType() + " stored in Bronze as PENDING";
                        case QUARANTINED -> format.messageType() + " stored in Bronze as QUARANTINED";
                        case DUPLICATE -> format.messageType() + " already in Bronze, skipped";
                    });
            meters.counter("clinicalharmony.bronze.messages", "outcome", result.outcome().name().toLowerCase(),
                    "format", format.name().toLowerCase(), "channel", event.bronzeChannel().toLowerCase()).increment();
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
