package com.clinicalharmony.bronze.inspect;

import com.clinicalharmony.bronze.config.Topics;

/** The four intake formats: the raw topic each arrives on and its bronze.raw_messages message_type / message_format. */
public enum BronzeFormat {

    HL7V2(Topics.HL7V2_RAW, "TEXT"),
    FHIR_JSON(Topics.FHIR_RAW, "JSON"),
    CLAIMS_CSV(Topics.CLAIMS_RAW, "CSV"),
    CCDA_XML(Topics.CCDA_RAW, "XML");

    private final String topic;
    private final String messageFormat;

    BronzeFormat(String topic, String messageFormat) {
        this.topic = topic;
        this.messageFormat = messageFormat;
    }

    public String topic() {
        return topic;
    }

    /** bronze.raw_messages.message_type */
    public String messageType() {
        return name();
    }

    /** bronze.raw_messages.message_format */
    public String messageFormat() {
        return messageFormat;
    }

    public static BronzeFormat ofTopic(String topic) {
        for (BronzeFormat f : values()) {
            if (f.topic.equals(topic)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Not a raw intake topic: " + topic);
    }
}
