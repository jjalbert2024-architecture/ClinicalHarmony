package com.clinicalharmony.patientindex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PatientIndexServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PatientIndexServiceApplication.class, args);
    }
}
