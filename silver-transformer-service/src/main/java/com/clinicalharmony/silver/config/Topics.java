package com.clinicalharmony.silver.config;

/** Topic names — {@code clinicalharmony.<layer>.<entity>.<event>} and {@code <topic>.dlt}. */
public final class Topics {

    /** Consumed (declared by bronze-listener-service, the producer). */
    public static final String BRONZE_INGESTED = "clinicalharmony.bronze.ingested";
    /** Dead letters for BRONZE_INGESTED (declared here, the consumer that dead-letters). */
    public static final String BRONZE_INGESTED_DLT = BRONZE_INGESTED + ".dlt";
    /** Produced via the outbox, for the 9c gold-builder. */
    public static final String SILVER_PATIENT_CHANGED = "clinicalharmony.silver.patient.changed";

    public static final String CONSUMER_GROUP = "silver-transformer";

    private Topics() {
    }
}
