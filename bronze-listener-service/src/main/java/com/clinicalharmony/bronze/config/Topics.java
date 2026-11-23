package com.clinicalharmony.bronze.config;

/** Topic names — {@code clinicalharmony.<layer>.<entity>.<event>} and {@code <topic>.dlt}. */
public final class Topics {

    /** Consumed (declared by ingestion-service, the producer): HL7 v2 from MLLP (9a) and REST (9d). */
    public static final String HL7V2_RAW = "clinicalharmony.bronze.hl7v2.raw";
    /** Consumed (declared by ingestion-service): FHIR JSON from REST (9d). */
    public static final String FHIR_RAW = "clinicalharmony.bronze.fhir.raw";
    /** Consumed (declared by ingestion-service): whole claims CSV files from REST (9d), split here. */
    public static final String CLAIMS_RAW = "clinicalharmony.bronze.claims.raw";
    /** Consumed (declared by ingestion-service): C-CDA XML from REST (9d). */
    public static final String CCDA_RAW = "clinicalharmony.bronze.ccda.raw";

    /** Dead letters for each raw topic (declared here, the consumer that dead-letters): {@code <topic>.dlt}. */
    public static final String HL7V2_RAW_DLT = HL7V2_RAW + ".dlt";
    public static final String FHIR_RAW_DLT = FHIR_RAW + ".dlt";
    public static final String CLAIMS_RAW_DLT = CLAIMS_RAW + ".dlt";
    public static final String CCDA_RAW_DLT = CCDA_RAW + ".dlt";

    /** Produced via the outbox, for the silver transformer. */
    public static final String BRONZE_INGESTED = "clinicalharmony.bronze.ingested";

    public static final String CONSUMER_GROUP = "bronze-writer";

    private Topics() {
    }
}
