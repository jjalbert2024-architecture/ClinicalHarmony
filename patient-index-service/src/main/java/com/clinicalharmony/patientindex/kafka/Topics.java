package com.clinicalharmony.patientindex.kafka;

/** Topic names — {@code clinicalharmony.<layer>.<entity>.<event>} and {@code <topic>.dlt}. */
public final class Topics {

    /** Consumed (declared by fhir-compliance-service's gold-builder, the producer). */
    public static final String GOLD_PATIENT_CHANGED = "clinicalharmony.gold.patient.changed";
    /** Dead letters for GOLD_PATIENT_CHANGED (declared here, by the consumer that dead-letters). */
    public static final String GOLD_PATIENT_CHANGED_DLT = GOLD_PATIENT_CHANGED + ".dlt";

    public static final String CONSUMER_GROUP = "pmi-matcher";

    private Topics() {
    }
}
