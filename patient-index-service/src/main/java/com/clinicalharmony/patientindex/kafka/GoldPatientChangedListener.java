package com.clinicalharmony.patientindex.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consumer group {@value Topics#CONSUMER_GROUP} on {@value Topics#GOLD_PATIENT_CHANGED}: each record only
 * signals {@link MatchRunDebouncer}; the matching run itself happens once the burst settles. A broken record is
 * dead-lettered.
 */
@Component
@ConditionalOnProperty(prefix = "pmi-matcher", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GoldPatientChangedListener {

    private static final Logger log = LoggerFactory.getLogger(GoldPatientChangedListener.class);

    private final MatchRunDebouncer debouncer;
    private final ObjectMapper mapper;

    public GoldPatientChangedListener(MatchRunDebouncer debouncer, ObjectMapper mapper) {
        this.debouncer = debouncer;
        this.mapper = mapper;
    }

    @KafkaListener(id = Topics.CONSUMER_GROUP, topics = Topics.GOLD_PATIENT_CHANGED, groupId = Topics.CONSUMER_GROUP)
    public void onMessage(ConsumerRecord<String, String> record) {
        String correlationId = header(record, "correlation-id");
        MDC.put("requestId", correlationId == null ? "pmi-matcher" : correlationId);
        try {
            GoldPatientChangedEvent event = GoldPatientChangedEvent.parse(record.value(), mapper);
            debouncer.signal(event.masterPatientId(), correlationId);
            log.atDebug().addKeyValue("event", "pmi.signalled")
                    .addKeyValue("masterPatientId", event.masterPatientId())
                    .addKeyValue("silverPatientId", event.silverPatientId())
                    .addKeyValue("causationId", header(record, "event-id"))
                    .addKeyValue("topic", record.topic())
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset())
                    .log("gold.patient.changed received; matching run debounced");
        } finally {
            MDC.remove("requestId");
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
