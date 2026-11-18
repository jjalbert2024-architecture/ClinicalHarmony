package com.clinicalharmony.fhircompliance.goldbuilder;

/** Topic names — {@code clinicalharmony.<layer>.<entity>.<event>} and {@code <topic>.dlt}. */
public final class Topics {

    /** Consumed (declared by silver-transformer-service, the producer). */
    public static final String SILVER_PATIENT_CHANGED = "clinicalharmony.silver.patient.changed";
    /** Dead letters for SILVER_PATIENT_CHANGED (declared here, by the consumer that dead-letters). */
    public static final String SILVER_PATIENT_CHANGED_DLT = SILVER_PATIENT_CHANGED + ".dlt";
    /** Produced via gold.outbox_events, for patient-index-service's pmi-matcher. */
    public static final String GOLD_PATIENT_CHANGED = "clinicalharmony.gold.patient.changed";

    public static final String CONSUMER_GROUP = "gold-builder";
    public static final String EVENT_TYPE = "gold.patient.changed";
    public static final String PRODUCER = "fhir-compliance-service";

    private Topics() {
    }
}
