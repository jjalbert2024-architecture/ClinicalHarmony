package com.clinicalharmony.bronze.config;

/** Topic names — {@code clinicalharmony.<layer>.<entity>.<event>} and {@code <topic>.dlt}. */
public final class Topics {

    /** Consumed (declared by ingestion-service, the producer). */
    public static final String HL7V2_RAW = "clinicalharmony.bronze.hl7v2.raw";
    /** Dead letters for HL7V2_RAW (declared here, the consumer that dead-letters). */
    public static final String HL7V2_RAW_DLT = HL7V2_RAW + ".dlt";
    /** Produced via the outbox, for the 9b silver transformer. */
    public static final String BRONZE_INGESTED = "clinicalharmony.bronze.ingested";

    public static final String CONSUMER_GROUP = "bronze-writer";

    private Topics() {
    }
}
