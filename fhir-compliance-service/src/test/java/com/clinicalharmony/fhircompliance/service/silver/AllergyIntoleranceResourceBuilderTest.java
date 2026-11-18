package com.clinicalharmony.fhircompliance.service.silver;

import com.clinicalharmony.fhircompliance.repository.silver.SilverAllergyRow;
import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import com.clinicalharmony.fhircompliance.validation.UsCoreValidatorFixture;
import org.hl7.fhir.r4.model.AllergyIntolerance;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HL7 validator against US Core 6.1.0 (no mocks), like the other builder tests. */
class AllergyIntoleranceResourceBuilderTest {

    private final AllergyIntoleranceResourceBuilder builder = new AllergyIntoleranceResourceBuilder();

    @Test
    void drugAllergyFromAl1IsUsCoreCompliant() {
        SilverAllergyRow row = new SilverAllergyRow(7L, 3L, "RXNORM", "723", "Amoxicillin", "DA", "SV", "HIVES");

        AllergyIntolerance allergy = builder.build(row, "silver-patient-3");

        assertThat(allergy.getIdPart()).isEqualTo("silver-allergy-7");
        assertThat(allergy.getMeta().hasProfile(UsCoreProfiles.ALLERGY_INTOLERANCE)).isTrue();
        assertThat(allergy.getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo("active");
        assertThat(allergy.hasVerificationStatus()).as("AL1 doesn't say it was confirmed").isFalse();
        assertThat(allergy.getType()).isEqualTo(AllergyIntolerance.AllergyIntoleranceType.ALLERGY);
        assertThat(allergy.getCategory()).extracting(c -> c.getValue().toCode()).containsExactly("medication");
        assertThat(allergy.getCriticality()).isEqualTo(AllergyIntolerance.AllergyIntoleranceCriticality.HIGH);
        assertThat(allergy.getCode().getCodingFirstRep().getSystem()).isEqualTo("http://www.nlm.nih.gov/research/umls/rxnorm");
        assertThat(allergy.getCode().getCodingFirstRep().getCode()).isEqualTo("723");
        assertThat(allergy.getCode().getText()).isEqualTo("Amoxicillin");
        assertThat(allergy.getPatient().getReference()).isEqualTo("Patient/silver-patient-3");
        assertThat(allergy.getReactionFirstRep().getManifestationFirstRep().getText()).isEqualTo("HIVES");
        assertThat(allergy.getReactionFirstRep().getSeverity()).isEqualTo(AllergyIntolerance.AllergyIntoleranceSeverity.SEVERE);

        FhirValidationOutcome outcome = UsCoreValidatorFixture.validator().validate(allergy);
        assertThat(outcome.violations()).filteredOn(v -> "ERROR".equals(v.severity())).isEmpty();
        assertThat(outcome.compliant()).isTrue();
    }

    @Test
    void foodAndMiscTypesAndModerateSeverity() {
        AllergyIntolerance food = builder.build(new SilverAllergyRow(8L, 3L, "SNOMED-CT", "91935009", "Peanut",
                "FA", "MO", null), "silver-patient-3");
        assertThat(food.getCategory()).extracting(c -> c.getValue().toCode()).containsExactly("food");
        assertThat(food.getCode().getCodingFirstRep().getSystem()).isEqualTo("http://snomed.info/sct");
        assertThat(food.getCriticality()).isEqualTo(AllergyIntolerance.AllergyIntoleranceCriticality.LOW);
        assertThat(food.hasReaction()).as("no AL1-5 reaction → no invented manifestation").isFalse();
        assertThat(UsCoreValidatorFixture.validator().validate(food).compliant()).isTrue();

        AllergyIntolerance misc = builder.build(new SilverAllergyRow(9L, 3L, "LOCAL", "LATEX-GLOVES", null,
                "MA", "U", "RASH"), "silver-patient-3");
        assertThat(misc.hasType()).isFalse();
        assertThat(misc.hasCategory()).isFalse();
        assertThat(misc.getCode().hasCoding()).as("LOCAL code has no FHIR system").isFalse();
        assertThat(misc.getCode().getText()).isEqualTo("LATEX-GLOVES");
        assertThat(misc.getCriticality()).isEqualTo(AllergyIntolerance.AllergyIntoleranceCriticality.UNABLETOASSESS);
        assertThat(misc.getReactionFirstRep().hasSeverity()).isFalse();
    }
}
