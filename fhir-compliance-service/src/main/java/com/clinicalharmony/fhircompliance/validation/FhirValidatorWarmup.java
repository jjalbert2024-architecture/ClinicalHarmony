package com.clinicalharmony.fhircompliance.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The HL7 validator lazily loads all base FHIR R4 definitions and generates US Core snapshots on
 * first use (~2 s). Pay that once at startup instead of on the first caller's request. A failure
 * here is logged, never fatal — the validator will just warm up on first real use instead.
 */
@Component
public class FhirValidatorWarmup {

    private static final Logger log = LoggerFactory.getLogger(FhirValidatorWarmup.class);

    private static final String WARMUP_PATIENT = """
            {"resourceType":"Patient","id":"warmup",
             "identifier":[{"system":"urn:clinicalharmony:warmup","value":"0"}],
             "name":[{"family":"Warmup"}],"gender":"unknown"}
            """;

    private final UsCoreFhirValidator validator;

    public FhirValidatorWarmup(UsCoreFhirValidator validator) {
        this.validator = validator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        long start = System.currentTimeMillis();
        try {
            validator.validateJson(WARMUP_PATIENT);
            log.info("US Core validator warmed up in {} ms", System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            log.warn("US Core validator warm-up failed; it will initialize on first request instead", e);
        }
    }
}
