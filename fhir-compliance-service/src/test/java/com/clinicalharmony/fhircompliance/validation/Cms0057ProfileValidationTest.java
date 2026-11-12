package com.clinicalharmony.fhircompliance.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CMS-0057-F seed resources (src/test/resources/cms0057/, the same JSON that
 * docs/db-init/19_seed_cms0057_gold.sql loads into Gold) against the REAL validator with all
 * three bundled IGs: US Core 6.1.0 for Coverage/PractitionerRole/Device (+ the Organization and
 * Practitioner they reference), Da Vinci Formulary 2.1.0 for MedicationKnowledge, DEQM 5.0.0 for
 * MeasureReport. Each type also gets a targeted non-compliant variant so the profiles are shown
 * to actually bite, not just to be loaded.
 */
class Cms0057ProfileValidationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UsCoreFhirValidator validator = UsCoreValidatorFixture.validator();

    @ParameterizedTest
    @ValueSource(strings = {
            "organization-payer", "organization-provider", "practitioner-1", "practitioner-2",
            "practitionerrole-1", "coverage-0001", "coverage-0002",
            "medicationknowledge-861007", "medicationknowledge-617310", "medicationknowledge-314076",
            "measurereport-0001", "device-0002"})
    void seedResourceIsCompliantWithItsProfile(String fixture) {
        FhirValidationOutcome outcome = validator.validateJson(read(fixture).toString());
        assertThat(outcome.violations())
                .as("errors for %s", fixture)
                .filteredOn(v -> "ERROR".equals(v.severity()))
                .isEmpty();
        assertThat(outcome.compliant()).isTrue();
    }

    @Test
    void seededNonCompliantPractitionerRoleFailsContactInvariant() {
        FhirValidationOutcome outcome = validator.validateJson(read("practitionerrole-2-noncompliant").toString());
        assertThat(outcome.profile()).isEqualTo(UsCoreProfiles.PRACTITIONER_ROLE);
        assertThat(outcome.compliant()).isFalse();
        assertThat(outcome.violations()).anySatisfy(v -> {
            assertThat(v.severity()).isEqualTo("ERROR");
            assertThat(v.rule()).isEqualTo("pd-1");
        });
    }

    @Test
    void coverageWithoutBeneficiaryOrPayorFailsUsCore() {
        FhirValidationOutcome outcome = validate("coverage-0001", r -> {
            r.remove("beneficiary");
            r.remove("payor");
        });
        assertThat(outcome.profile()).isEqualTo(UsCoreProfiles.COVERAGE);
        assertThat(outcome.compliant()).isFalse();
        assertThat(errorLocations(outcome)).anyMatch(l -> l.contains("beneficiary"));
        assertThat(errorLocations(outcome)).anyMatch(l -> l.contains("payor"));
    }

    @Test
    void formularyDrugWithoutRxNormCodeFailsDaVinciFormulary() {
        FhirValidationOutcome outcome = validate("medicationknowledge-861007", r -> r.remove("code"));
        assertThat(outcome.profile()).isEqualTo(UsCoreProfiles.FORMULARY_DRUG);
        assertThat(outcome.compliant()).isFalse();
        assertThat(errorLocations(outcome)).anyMatch(l -> l.contains("MedicationKnowledge.code"));
    }

    @Test
    void formularyDrugWithoutLastUpdatedFailsDaVinciFormulary() {
        FhirValidationOutcome outcome = validate("medicationknowledge-617310",
                r -> ((ObjectNode) r.get("meta")).remove("lastUpdated"));
        assertThat(outcome.compliant()).isFalse();
        assertThat(errorLocations(outcome)).anyMatch(l -> l.contains("lastUpdated"));
    }

    @Test
    void measureReportWithUnversionedMeasureFailsDeqm() {
        FhirValidationOutcome outcome = validate("measurereport-0001", r -> r.put("measure",
                "http://ecqi.healthit.gov/ecqms/Measure/DiabetesHemoglobinA1cHbA1cPoorControl9FHIR"));
        assertThat(outcome.profile()).isEqualTo(UsCoreProfiles.DEQM_INDIVIDUAL_MEASURE_REPORT);
        assertThat(outcome.compliant()).isFalse();
        assertThat(outcome.violations()).anyMatch(v -> "deqm-0".equals(v.rule()));
    }

    @Test
    void measureReportWithoutReporterFailsDeqm() {
        FhirValidationOutcome outcome = validate("measurereport-0001", r -> r.remove("reporter"));
        assertThat(outcome.compliant()).isFalse();
        assertThat(errorLocations(outcome)).anyMatch(l -> l.contains("reporter"));
    }

    @Test
    void implantableDeviceWithoutTypeOrPatientFailsUsCore() {
        FhirValidationOutcome outcome = validate("device-0002", r -> {
            r.remove("type");
            r.remove("patient");
        });
        assertThat(outcome.profile()).isEqualTo(UsCoreProfiles.IMPLANTABLE_DEVICE);
        assertThat(outcome.compliant()).isFalse();
        assertThat(errorLocations(outcome)).anyMatch(l -> l.contains("type"));
        assertThat(errorLocations(outcome)).anyMatch(l -> l.contains("patient"));
    }

    @Test
    void cms0057TypesWithoutMetaProfileGetTheirDefaultProfile() {
        FhirValidationOutcome mk = validate("medicationknowledge-314076", r -> ((ObjectNode) r.get("meta")).remove("profile"));
        FhirValidationOutcome mr = validate("measurereport-0001", r -> r.remove("meta"));
        FhirValidationOutcome dev = validate("device-0002", r -> r.remove("meta"));
        assertThat(mk.profile()).isEqualTo(UsCoreProfiles.FORMULARY_DRUG);
        assertThat(mr.profile()).isEqualTo(UsCoreProfiles.DEQM_INDIVIDUAL_MEASURE_REPORT);
        assertThat(dev.profile()).isEqualTo(UsCoreProfiles.IMPLANTABLE_DEVICE);
        assertThat(mk.compliant()).isTrue();
        assertThat(mr.compliant()).isTrue();
        assertThat(dev.compliant()).isTrue();
    }

    private FhirValidationOutcome validate(String fixture, Consumer<ObjectNode> mutation) {
        ObjectNode resource = read(fixture);
        mutation.accept(resource);
        return validator.validateJson(resource.toString());
    }

    private static java.util.List<String> errorLocations(FhirValidationOutcome outcome) {
        return outcome.violations().stream()
                .filter(v -> "ERROR".equals(v.severity()))
                .map(v -> v.location() + " " + v.message())
                .toList();
    }

    ObjectNode read(String fixture) {
        try (InputStream in = getClass().getResourceAsStream("/cms0057/" + fixture + ".json")) {
            return (ObjectNode) mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
