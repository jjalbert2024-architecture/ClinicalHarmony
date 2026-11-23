package com.clinicalharmony.ingestion.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * ingestion-service owns (declares) the topics it produces to — one per intake format. The broker's
 * auto-create is off, so {@code KafkaAdmin} creates them on startup if missing — 3 partitions, so the Bronze
 * layer can scale to three listener instances. Replication 1: single-broker dev cluster.
 */
@Configuration
public class KafkaTopics {

    /** HL7 v2 from MLLP (9a) and REST (9d). */
    public static final String HL7V2_RAW = "clinicalharmony.bronze.hl7v2.raw";
    /** FHIR JSON resources / Bundles from REST (9d). */
    public static final String FHIR_RAW = "clinicalharmony.bronze.fhir.raw";
    /** Whole claims CSV files from REST (9d); bronze-listener-service splits them into one row per claim line. */
    public static final String CLAIMS_RAW = "clinicalharmony.bronze.claims.raw";
    /** C-CDA XML documents from REST (9d). */
    public static final String CCDA_RAW = "clinicalharmony.bronze.ccda.raw";

    @Bean
    NewTopic hl7v2RawTopic(@Value("${ingestion.kafka.partitions:3}") int partitions) {
        return topic(HL7V2_RAW, partitions);
    }

    @Bean
    NewTopic fhirRawTopic(@Value("${ingestion.kafka.partitions:3}") int partitions) {
        return topic(FHIR_RAW, partitions);
    }

    @Bean
    NewTopic claimsRawTopic(@Value("${ingestion.kafka.partitions:3}") int partitions) {
        return topic(CLAIMS_RAW, partitions);
    }

    @Bean
    NewTopic ccdaRawTopic(@Value("${ingestion.kafka.partitions:3}") int partitions) {
        return topic(CCDA_RAW, partitions);
    }

    private static NewTopic topic(String name, int partitions) {
        return TopicBuilder.name(name).partitions(partitions).replicas(1).build();
    }
}
