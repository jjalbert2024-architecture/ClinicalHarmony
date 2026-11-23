package com.clinicalharmony.ingestion.intake;

import com.clinicalharmony.ingestion.kafka.KafkaTopics;

/** The four REST intake formats: Kafka topic + event type each, and the Bronze message_type it becomes. */
public enum IntakeFormat {

    HL7V2(KafkaTopics.HL7V2_RAW, "hl7v2.raw"),
    FHIR_JSON(KafkaTopics.FHIR_RAW, "fhir.raw"),
    CLAIMS_CSV(KafkaTopics.CLAIMS_RAW, "claims.raw"),
    CCDA_XML(KafkaTopics.CCDA_RAW, "ccda.raw");

    private final String topic;
    private final String eventType;

    IntakeFormat(String topic, String eventType) {
        this.topic = topic;
        this.eventType = eventType;
    }

    public String topic() {
        return topic;
    }

    public String eventType() {
        return eventType;
    }

    /** Metric / log tag, e.g. {@code fhir_json}. */
    public String tag() {
        return name().toLowerCase();
    }
}
