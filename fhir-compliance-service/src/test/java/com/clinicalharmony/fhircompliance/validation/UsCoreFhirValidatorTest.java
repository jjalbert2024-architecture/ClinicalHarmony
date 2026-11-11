package com.clinicalharmony.fhircompliance.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsCoreFhirValidatorTest {

    private final UsCoreFhirValidator validator = UsCoreValidatorFixture.validator();

    private static final String COMPLIANT_PATIENT = """
            {"resourceType":"Patient","id":"p1",
             "identifier":[{"system":"urn:example:mrn","value":"123"}],
             "name":[{"family":"Doe","given":["Jane"]}],
             "gender":"female","birthDate":"1980-01-01"}
            """;

    @Test
    void compliantPatientHasNoErrorsAndDefaultsToUsCorePatientProfile() {
        FhirValidationOutcome outcome = validator.validateJson(COMPLIANT_PATIENT);

        assertThat(outcome.compliant()).isTrue();
        assertThat(outcome.errorCount()).isZero();
        assertThat(outcome.resourceType()).isEqualTo("Patient");
        assertThat(outcome.resourceId()).isEqualTo("p1");
        assertThat(outcome.profile()).isEqualTo(UsCoreProfiles.PATIENT);
    }

    @Test
    void bareResourceIsCheckedAgainstUsCoreNotJustBaseFhir() {
        // Valid base R4 — every element is optional there. Only US Core makes these required.
        FhirValidationOutcome outcome = validator.validateJson("{\"resourceType\":\"Patient\"}");

        assertThat(outcome.compliant()).isFalse();
        assertThat(outcome.violations())
                .filteredOn(v -> "ERROR".equals(v.severity()))
                .extracting(FhirViolation::location)
                .contains("Patient.identifier", "Patient.name", "Patient.gender");
    }

    @Test
    void missingElementViolationCarriesRuleAndSuggestedFix() {
        FhirValidationOutcome outcome = validator.validateJson("{\"resourceType\":\"Patient\"}");

        FhirViolation gender = outcome.violations().stream()
                .filter(v -> "Patient.gender".equals(v.location())).findFirst().orElseThrow();
        assertThat(gender.severity()).isEqualTo("ERROR");
        assertThat(gender.rule()).isEqualTo("Validation_VAL_Profile_Minimum");
        assertThat(gender.message()).contains("minimum required = 1");
        assertThat(gender.suggestedFix()).contains("'gender'");
    }

    @Test
    void emptyStringIsReportedAtItsExactLocation() {
        // HAPI's parser silently drops "" — validating the raw JSON is what catches it.
        FhirValidationOutcome outcome = validator.validateJson("""
                {"resourceType":"Patient","identifier":[{"value":"1"}],
                 "name":[{"family":""}],"gender":"male"}
                """);

        assertThat(outcome.compliant()).isFalse();
        assertThat(outcome.violations())
                .anySatisfy(v -> {
                    assertThat(v.location()).isEqualTo("Patient.name[0].family");
                    assertThat(v.severity()).isEqualTo("ERROR");
                    assertThat(v.suggestedFix()).isNotBlank();
                });
    }

    @Test
    void errorsAreListedBeforeWarnings() {
        FhirValidationOutcome outcome = validator.validateJson("{\"resourceType\":\"Patient\"}");

        assertThat(outcome.violations().get(0).severity()).isEqualTo("ERROR");
        assertThat(outcome.violations().get(outcome.violations().size() - 1).severity()).isNotEqualTo("ERROR");
    }

    @Test
    void declaredMetaProfileIsHonoredInsteadOfTheDefault() {
        FhirValidationOutcome outcome = validator.validateJson("""
                {"resourceType":"Observation","meta":{"profile":["http://hl7.org/fhir/us/core/StructureDefinition/us-core-observation-lab"]},
                 "status":"final","code":{"text":"x"},"subject":{"reference":"Patient/p1"}}
                """);

        assertThat(outcome.profile()).isEqualTo(UsCoreProfiles.OBSERVATION_LAB);
        // Lab profile requires the 'laboratory' category slice.
        assertThat(outcome.compliant()).isFalse();
    }

    @Test
    void observationDefaultProfileDependsOnCategory() {
        String lab = """
                {"resourceType":"Observation","status":"final",
                 "category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"laboratory"}]}],
                 "code":{"coding":[{"system":"http://loinc.org","code":"718-7"}]},"subject":{"reference":"Patient/p1"}}
                """;
        String other = "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"text\":\"x\"}}";

        assertThat(validator.validateJson(lab).profile()).isEqualTo(UsCoreProfiles.OBSERVATION_LAB);
        assertThat(validator.validateJson(other).profile()).isEqualTo(UsCoreProfiles.OBSERVATION_CLINICAL_RESULT);
    }

    @Test
    void unknownCodeSystemIsAWarningNotAnError() {
        FhirValidationOutcome outcome = validator.validateJson("""
                {"resourceType":"Observation","status":"final",
                 "category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"laboratory"}]}],
                 "code":{"coding":[{"system":"http://loinc.org","code":"718-7"}]},"subject":{"reference":"Patient/p1"},
                 "effectiveDateTime":"2024-01-01","valueQuantity":{"value":13.5,"unit":"g/dL","system":"http://unitsofmeasure.org","code":"g/dL"}}
                """);

        assertThat(outcome.compliant()).isTrue();
        assertThat(outcome.violations())
                .anySatisfy(v -> {
                    assertThat(v.rule()).isEqualTo("Terminology_PassThrough_TX_Message");
                    assertThat(v.severity()).isEqualTo("WARNING");
                });
    }

    @Test
    void rejectsNonJson() {
        assertThatThrownBy(() -> validator.validateJson("not json"))
                .isInstanceOf(InvalidFhirResourceException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsJsonWithoutResourceType() {
        assertThatThrownBy(() -> validator.validateJson("{\"id\":\"x\"}"))
                .isInstanceOf(InvalidFhirResourceException.class)
                .hasMessageContaining("resourceType");
    }

    @Test
    void rejectsUnknownResourceType() {
        assertThatThrownBy(() -> validator.validateJson("{\"resourceType\":\"Spaceship\"}"))
                .isInstanceOf(InvalidFhirResourceException.class)
                .hasMessageContaining("Spaceship");
    }

    @Test
    void rejectsJsonArray() {
        assertThatThrownBy(() -> validator.validateJson("[]"))
                .isInstanceOf(InvalidFhirResourceException.class);
    }

    @Test
    void refineLocationLiftsMissingElementIntoPath() {
        assertThat(UsCoreFhirValidator.refineLocation("Patient", "Patient.name: minimum required = 1, but only found 0"))
                .isEqualTo("Patient.name");
        assertThat(UsCoreFhirValidator.refineLocation("Patient.name[0]", "Patient.name.family: minimum required = 1, but only found 0"))
                .isEqualTo("Patient.name[0].family");
        assertThat(UsCoreFhirValidator.refineLocation("Patient.gender", "Some other message"))
                .isEqualTo("Patient.gender");
    }

    @Test
    void shortRuleStripsStructureDefinitionUrl() {
        assertThat(UsCoreFhirValidator.shortRule("http://hl7.org/fhir/StructureDefinition/DomainResource#dom-6")).isEqualTo("dom-6");
        assertThat(UsCoreFhirValidator.shortRule("Validation_VAL_Profile_Minimum")).isEqualTo("Validation_VAL_Profile_Minimum");
        assertThat(UsCoreFhirValidator.shortRule(null)).isNull();
    }
}
