package com.clinicalharmony.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * ClinicalHarmony's single REST entry point for the dashboard (Phase 7): a backend-for-frontend.
 * Dashboard reads are SQL projections over a SELECT-only Postgres role; every action is proxied to
 * the service that owns it. See README.md for the endpoint list and access matrix.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
