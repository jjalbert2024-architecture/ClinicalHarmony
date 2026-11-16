package com.clinicalharmony.ingestion.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * ingestion-service owns (declares) the topic it produces to. The broker's auto-create is off, so
 * {@code KafkaAdmin} creates it on startup if missing — 3 partitions, so the Bronze layer can scale to
 * three listener instances. Replication 1: single-broker dev cluster.
 */
@Configuration
public class KafkaTopics {

    public static final String HL7V2_RAW = "clinicalharmony.bronze.hl7v2.raw";

    @Bean
    NewTopic hl7v2RawTopic(@Value("${ingestion.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(HL7V2_RAW).partitions(partitions).replicas(1).build();
    }
}
