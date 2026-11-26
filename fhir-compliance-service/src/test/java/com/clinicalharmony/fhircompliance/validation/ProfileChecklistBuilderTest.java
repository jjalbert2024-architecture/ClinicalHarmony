package com.clinicalharmony.fhircompliance.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checklist against the REAL US Core 6.1.0 snapshots from the bundled package (no hand-made
 * profiles), so a change in what the profile requires shows up here.
 */
class ProfileChecklistBuilderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ProfileChecklistBuilder builder = new ProfileChecklistBuilder(UsCoreValidatorFixture.validationSupport());

    private static final String PATIENT = """
            {"resourceType":"Patient","id":"p1",
             "extension":[{"url":"http://hl7.org/fhir/us/core/StructureDefinition/us-core-race",
                           "extension":[{"url":"text","valueString":"White"}]}],
             "identifier":[{"system":"urn:oid:1.2.3","value":"MRN1"}],
             "name":[{"family":"Doe","given":["Jane"]}],
             "gender":"female",
             "_birthDate":{"extension":[{"url":"http://hl7.org/fhir/StructureDefinition/data-absent-reason","valueCode":"unknown"}]}}
            """;

    @Test
    void patientChecklistMarksRequiredAndMustSupportElementsPresentOrAbsent() throws Exception {
        List<ChecklistItem> items = builder.build(UsCoreProfiles.PATIENT, JSON.readTree(PATIENT));

        assertThat(item(items, "Patient.identifier")).hasValueSatisfying(i -> {
            assertThat(i.required()).isTrue();
            assertThat(i.present()).isTrue();
            assertThat(i.depth()).isEqualTo(1);
        });
        assertThat(item(items, "Patient.name.family")).hasValueSatisfying(i -> {
            assertThat(i.mustSupport()).isTrue();
            assertThat(i.present()).isTrue();
            assertThat(i.depth()).isEqualTo(2);
        });
        // US Core 6.1 lists race as an Additional USCDI Requirement, not must-support.
        assertThat(item(items, "Patient.extension:race")).hasValueSatisfying(i -> {
            assertThat(i.mustSupport()).isFalse();
            assertThat(i.uscdi()).isTrue();
            assertThat(i.present()).isTrue();
            assertThat(i.label()).isEqualTo("race (extension)");
        });
        assertThat(item(items, "Patient.extension:ethnicity")).hasValueSatisfying(i -> assertThat(i.present()).isFalse());
        assertThat(item(items, "Patient.telecom")).hasValueSatisfying(i -> {
            assertThat(i.required()).isFalse();
            assertThat(i.present()).isFalse();
        });
        // A primitive carried only as _birthDate (data-absent-reason) still counts as present.
        assertThat(item(items, "Patient.birthDate")).hasValueSatisfying(i -> assertThat(i.present()).isTrue());
    }

    @Test
    void labObservationChecksTheLaboratoryCategorySliceAndChoiceElements() throws Exception {
        JsonNode lab = JSON.readTree("""
                {"resourceType":"Observation","status":"final",
                 "category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"laboratory"}]}],
                 "code":{"coding":[{"system":"http://loinc.org","code":"2345-7"}]},
                 "subject":{"reference":"Patient/p1"},
                 "valueQuantity":{"value":5.4,"unit":"mmol/L"}}
                """);
        List<ChecklistItem> items = builder.build(UsCoreProfiles.OBSERVATION_LAB, lab);

        assertThat(items.stream().filter(i -> i.path().startsWith("Observation.category:")))
                .isNotEmpty()
                .allSatisfy(i -> assertThat(i.present()).isTrue());
        assertThat(item(items, "Observation.value[x]")).hasValueSatisfying(i -> assertThat(i.present()).isTrue());
        assertThat(item(items, "Observation.effective[x]")).hasValueSatisfying(i -> assertThat(i.present()).isFalse());
    }

    @Test
    void wrongCategoryDoesNotSatisfyThePatternSlice() throws Exception {
        JsonNode vital = JSON.readTree("""
                {"resourceType":"Observation","status":"final",
                 "category":[{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/observation-category","code":"vital-signs"}]}]}
                """);
        List<ChecklistItem> items = builder.build(UsCoreProfiles.OBSERVATION_LAB, vital);

        assertThat(items.stream().filter(i -> i.path().startsWith("Observation.category:")))
                .isNotEmpty()
                .allSatisfy(i -> assertThat(i.present()).isFalse());
    }

    @Test
    void unknownProfileGivesAnEmptyChecklist() throws Exception {
        assertThat(builder.build("http://example.org/StructureDefinition/nope", JSON.readTree(PATIENT))).isEmpty();
        assertThat(builder.build(null, JSON.readTree(PATIENT))).isEmpty();
    }

    @Test
    void theValidatorAttachesTheChecklistToItsOutcome() {
        FhirValidationOutcome outcome = UsCoreValidatorFixture.validator().validateJson(PATIENT);

        assertThat(outcome.checklist()).isNotEmpty();
        assertThat(outcome.checklist()).extracting(ChecklistItem::path).contains("Patient.identifier", "Patient.gender");
    }

    @Test
    void selectFlattensArraysAndMatchesChoiceNames() throws Exception {
        JsonNode node = JSON.readTree("{\"a\":[{\"b\":1},{\"b\":2},{\"c\":3}],\"valueString\":\"x\",\"valueset\":\"no\"}");

        assertThat(ProfileChecklistBuilder.select(node, "R.a.b")).hasSize(2);
        assertThat(ProfileChecklistBuilder.select(node, "R.value[x]")).hasSize(1);
        assertThat(ProfileChecklistBuilder.select(node, "R.a.missing")).isEmpty();
    }

    private static Optional<ChecklistItem> item(List<ChecklistItem> items, String path) {
        return items.stream().filter(i -> i.path().equals(path)).findFirst();
    }
}
