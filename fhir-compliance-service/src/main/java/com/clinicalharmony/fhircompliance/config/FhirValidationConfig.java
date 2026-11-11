package com.clinicalharmony.fhircompliance.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.UnknownCodeSystemWarningValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Wires the official HL7 FHIR validator (via HAPI FHIR) against the US Core 6.1.0 IG package
 * bundled at {@code src/main/resources/packages/}. The package is loaded from the classpath —
 * no network access at runtime, so the validator behaves identically in Docker and offline.
 *
 * <p>Chain order matters: US Core profiles first, then the base FHIR R4 definitions, then
 * terminology. There is no terminology server (no LOINC/SNOMED/RxNorm/ICD-10 content loaded
 * into the validator — ontology-service owns code validity), so codes in those systems are
 * reported as warnings by {@link UnknownCodeSystemWarningValidationSupport} rather than errors.
 */
@Configuration
public class FhirValidationConfig {

    private static final Logger log = LoggerFactory.getLogger(FhirValidationConfig.class);

    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4Cached();
    }

    @Bean
    public IValidationSupport usCoreValidationSupport(
            FhirContext fhirContext,
            @Value("${fhir-validator.us-core-package:classpath:packages/hl7.fhir.us.core-6.1.0.tgz}") String usCorePackage)
            throws IOException {
        NpmPackageValidationSupport npm = new NpmPackageValidationSupport(fhirContext);
        npm.loadPackageFromClasspath(usCorePackage);
        log.info("Loaded US Core IG package {}", usCorePackage);

        UnknownCodeSystemWarningValidationSupport unknownCodeSystems = new UnknownCodeSystemWarningValidationSupport(fhirContext);
        unknownCodeSystems.setNonExistentCodeSystemSeverity(IValidationSupport.IssueSeverity.WARNING);

        return new ValidationSupportChain(
                npm,
                new DefaultProfileValidationSupport(fhirContext),
                new CommonCodeSystemsTerminologyService(fhirContext),
                new InMemoryTerminologyServerValidationSupport(fhirContext),
                new SnapshotGeneratingValidationSupport(fhirContext),
                unknownCodeSystems);
    }

    @Bean
    public FhirValidator fhirValidator(FhirContext fhirContext, IValidationSupport usCoreValidationSupport) {
        FhirInstanceValidator instanceValidator = new FhirInstanceValidator(usCoreValidationSupport);
        // US Core references profiles/value sets from packages we deliberately don't bundle (VSAC,
        // PHIN VADS, SDC...). Don't fail a resource just because a referenced canonical can't be
        // resolved locally — report it as a warning instead.
        instanceValidator.setErrorForUnknownProfiles(false);
        instanceValidator.setNoExtensibleWarnings(true);
        FhirValidator validator = fhirContext.newValidator();
        validator.registerValidatorModule(instanceValidator);
        return validator;
    }
}
