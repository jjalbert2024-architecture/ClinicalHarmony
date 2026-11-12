package com.clinicalharmony.fhircompliance.validation;

import ca.uhn.fhir.context.FhirContext;
import com.clinicalharmony.fhircompliance.config.FhirValidationConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * One real, fully-wired validator (US Core 6.1.0 + Da Vinci Formulary/DEQM) shared across test classes. Building it loads the
 * US Core package and base R4 definitions (~2-3 s), so it's created once per JVM, not per test.
 * Tests that use it exercise the actual HL7 validator — no mocks — which is the point: the builders'
 * compliant/non-compliant claims are checked against the real thing.
 */
public final class UsCoreValidatorFixture {

    private static UsCoreFhirValidator instance;

    private UsCoreValidatorFixture() {
    }

    public static synchronized UsCoreFhirValidator validator() {
        if (instance == null) {
            try {
                FhirValidationConfig config = new FhirValidationConfig();
                FhirContext ctx = config.fhirContext();
                instance = new UsCoreFhirValidator(ctx,
                        config.fhirValidator(ctx,
                                config.usCoreValidationSupport(ctx, FhirValidationConfig.DEFAULT_IG_PACKAGES)),
                        new SuggestedFixAdvisor(), new ObjectMapper());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return instance;
    }

    public static FhirContext fhirContext() {
        return FhirContext.forR4Cached();
    }
}
