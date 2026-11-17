package com.clinicalharmony.silver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase 9b: consumes {@code clinicalharmony.bronze.ingested}, maps each Bronze HL7 message into
 * {@code silver.*}, validates the patient through rules-engine-service (which calls ontology-service),
 * and announces it on {@code clinicalharmony.silver.patient.changed} via a transactional outbox.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class SilverTransformerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SilverTransformerApplication.class, args);
    }
}
