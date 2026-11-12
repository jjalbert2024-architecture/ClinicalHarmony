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
 * Wires the official HL7 FHIR validator (via HAPI FHIR) against the IG packages bundled at
 * {@code src/main/resources/packages/}: US Core 6.1.0, plus (Phase 5b slice 3, for the CMS-0057-F
 * payer read endpoints) Da Vinci US Drug Formulary 2.1.0 (MedicationKnowledge) and Da Vinci DEQM
 * 5.0.0 (MeasureReport). Packages are loaded from the classpath — no network access at runtime, so
 * the validator behaves identically in Docker and offline.
 *
 * <p>Only the packages are bundled, not their dependency trees (QI-Core, CRMI, US Core 3.1.1/7.0.0,
 * Plan-Net, HRex...). That's deliberate: the two profiles used here ({@code usdf-FormularyDrug},
 * {@code indv-measurereport-deqm}) both derive directly from base R4 and ship with snapshots, so
 * they validate without those dependencies. Canonicals that can't be resolved locally (e.g. a
 * QI-Core target profile on MeasureReport.subject) degrade to warnings, see {@link #fhirValidator}.
 *
 * <p>Chain order matters: US Core profiles first, then the base FHIR R4 definitions, then
 * terminology. There is no terminology server (no LOINC/SNOMED/RxNorm/ICD-10 content loaded
 * into the validator — ontology-service owns code validity), so codes in those systems are
 * reported as warnings by {@link UnknownCodeSystemWarningValidationSupport} rather than errors.
 */
@Configuration
public class FhirValidationConfig {

    /** Comma-separated classpath locations of every IG package the validator loads. */
    public static final String DEFAULT_IG_PACKAGES =
            "classpath:packages/hl7.fhir.us.core-6.1.0.tgz,"
            + "classpath:packages/hl7.fhir.us.davinci-drug-formulary-2.1.0.tgz,"
            + "classpath:packages/hl7.fhir.us.davinci-deqm-5.0.0.tgz";

    private static final Logger log = LoggerFactory.getLogger(FhirValidationConfig.class);

    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4Cached();
    }

    @Bean
    public IValidationSupport usCoreValidationSupport(
            FhirContext fhirContext,
            @Value("${fhir-validator.ig-packages:" + DEFAULT_IG_PACKAGES + "}") String igPackages)
            throws IOException {
        NpmPackageValidationSupport npm = new NpmPackageValidationSupport(fhirContext);
        for (String pkg : igPackages.split(",")) {
            if (!pkg.isBlank()) {
                npm.loadPackageFromClasspath(pkg.trim());
                log.info("Loaded IG package {}", pkg.trim());
            }
        }

        UnknownCodeSystemWarningValidationSupport unknownCodeSystems = new UnknownCodeSystemWarningValidationSupport(fhirContext);
        unknownCodeSystems.setNonExistentCodeSystemSeverity(IValidationSupport.IssueSeverity.WARNING);

        return new ValidationSupportChain(
                // Before npm: overrides the Formulary package's filter-based RxNorm value sets.
                RxNormFormularyValueSets.create(fhirContext),
                npm,
                OmbRaceEthnicityValueSets.create(fhirContext),
                new DefaultProfileValidationSupport(fhirContext),
                new CommonCodeSystemsTerminologyService(fhirContext),
                new InMemoryTerminologyServerValidationSupport(fhirContext),
                new SnapshotGeneratingValidationSupport(fhirContext),
                unknownCodeSystems);
    }

    @Bean
    public FhirValidator fhirValidator(FhirContext fhirContext, IValidationSupport usCoreValidationSupport) {
        FhirInstanceValidator instanceValidator = new VersionedCanonicalFhirInstanceValidator(usCoreValidationSupport);
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
