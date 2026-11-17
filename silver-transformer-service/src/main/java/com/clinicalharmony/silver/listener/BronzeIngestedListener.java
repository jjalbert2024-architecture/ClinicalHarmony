package com.clinicalharmony.silver.listener;

import com.clinicalharmony.silver.config.Topics;
import com.clinicalharmony.silver.store.KafkaSource;
import com.clinicalharmony.silver.store.SilverWriter;
import com.clinicalharmony.silver.validation.PatientValidator;
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
 * Consumer group {@value Topics#CONSUMER_GROUP} on {@value Topics#BRONZE_INGESTED}. Per record:
 * <ol>
 *   <li>{@link SilverWriter#transform} — one transaction: Bronze row → Silver rows → Bronze PROCESSED
 *       (or FAILED) → outbox event;</li>
 *   <li>after that commit, {@link PatientValidator#validate} through rules-engine-service. Its failure
 *       never fails the record: the patient stays PENDING_VALIDATION for the sweeper.</li>
 * </ol>
 * Exceptions thrown from here are TECHNICAL failures (retried, then dead-lettered by
 * {@code KafkaConfig}'s error handler); an unmappable message is never an exception.
 */
@Component
public class BronzeIngestedListener {

    private static final Logger log = LoggerFactory.getLogger(BronzeIngestedListener.class);

    private final SilverWriter writer;
    private final PatientValidator validator;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public BronzeIngestedListener(SilverWriter writer, PatientValidator validator, ObjectMapper mapper, MeterRegistry meters) {
        this.writer = writer;
        this.validator = validator;
        this.mapper = mapper;
        this.meters = meters;
    }

    @KafkaListener(id = "silver-transformer", topics = Topics.BRONZE_INGESTED, groupId = Topics.CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record) {
        long start = System.nanoTime();
        String correlationId = header(record, "correlation-id");
        String eventId = header(record, "event-id");
        MDC.put("correlationId", correlationId == null ? "" : correlationId);
        MDC.put("causationId", eventId == null ? "" : eventId);
        try {
            BronzeIngestedEvent event = BronzeIngestedEvent.parse(record.value(), mapper);
            log.atDebug().addKeyValue("event", "silver.consumed")
                    .addKeyValue("rawMessageId", event.rawMessageId())
                    .addKeyValue("topic", record.topic())
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset())
                    .log("bronze.ingested event received");
            KafkaSource source = new KafkaSource(record.topic(), record.partition(), record.offset(), record.key(),
                    eventId, correlationId);

            SilverWriter.Result result = writer.transform(event, source);

            var line = switch (result.outcome()) {
                case TRANSFORMED -> log.atInfo().addKeyValue("event", "silver.mapped")
                        .addKeyValue("silverPatientId", result.silverPatientId())
                        .addKeyValue("change", result.change())
                        .addKeyValue("conditions", result.counts().conditions())
                        .addKeyValue("observations", result.counts().observations())
                        .addKeyValue("medications", result.counts().medications())
                        .addKeyValue("allergies", result.counts().allergies())
                        .addKeyValue("outboxEventId", result.outboxEventId());
                case FAILED -> log.atWarn().addKeyValue("event", "silver.failed").addKeyValue("reason", result.error());
                case SKIPPED -> log.atInfo().addKeyValue("event", "silver.skipped")
                        .addKeyValue("bronzeStatus", result.bronzeStatus());
            };
            line.addKeyValue("rawMessageId", result.rawMessageId())
                    .addKeyValue("hl7MessageType", result.messageType())
                    .addKeyValue("sourceSystem", event.sourceSystem())
                    .addKeyValue("controlId", event.controlId())
                    .addKeyValue("topic", record.topic())
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset())
                    .addKeyValue("durationMs", (System.nanoTime() - start) / 1_000_000)
                    .log(switch (result.outcome()) {
                        case TRANSFORMED -> "HL7 message mapped to Silver; Bronze row PROCESSED";
                        case FAILED -> "HL7 message could not be mapped to Silver; Bronze row FAILED";
                        case SKIPPED -> "Bronze row already " + result.bronzeStatus() + ", skipped";
                    });
            meters.counter("clinicalharmony.silver.messages", "outcome", result.outcome().name().toLowerCase()).increment();

            if (result.outcome() == SilverWriter.Outcome.TRANSFORMED) {
                validator.validate(result.silverPatientId());
            }
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
