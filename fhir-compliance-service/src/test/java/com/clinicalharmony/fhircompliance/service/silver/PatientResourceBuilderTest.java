package com.clinicalharmony.fhircompliance.service.silver;

import com.clinicalharmony.fhircompliance.repository.silver.SilverPatientRow;
import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.clinicalharmony.fhircompliance.validation.FhirViolation;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import com.clinicalharmony.fhircompliance.validation.UsCoreValidatorFixture;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PatientResourceBuilderTest {

    private final PatientResourceBuilder builder = new PatientResourceBuilder();

    static SilverPatientRow completeRow() {
        return new SilverPatientRow(1L, "SEED_EPIC_ADT", "SEED-0001", "MRN100001", "Maria", "Garcia",
                LocalDate.of(1968, 4, 12), "female", "1200 Congress Ave", null, "Austin", "TX", "78701", "512-555-0101");
    }

    @Test
    void mapsSilverRowToUsCorePatient() {
        Patient patient = builder.build(completeRow());

        assertThat(patient.getIdPart()).isEqualTo("silver-patient-1");
        assertThat(patient.getMeta().hasProfile(UsCoreProfiles.PATIENT)).isTrue();
        assertThat(patient.getIdentifier()).hasSize(2);
        assertThat(patient.getIdentifierFirstRep().getSystem()).isEqualTo("urn:clinicalharmony:source:SEED_EPIC_ADT:mrn");
        assertThat(patient.getIdentifierFirstRep().getType().getCodingFirstRep().getCode()).isEqualTo("MR");
        assertThat(patient.getNameFirstRep().getFamily()).isEqualTo("Garcia");
        assertThat(patient.getNameFirstRep().getGivenAsSingleString()).isEqualTo("Maria");
        assertThat(patient.getGender().toCode()).isEqualTo("female");
        assertThat(patient.getBirthDateElement().getValueAsString()).isEqualTo("1968-04-12");
        assertThat(patient.getAddressFirstRep().getState()).isEqualTo("TX");
        assertThat(patient.getTelecomFirstRep().getValue()).isEqualTo("512-555-0101");
        assertThat(patient.getText().getDivAsString()).contains("Maria Garcia");
    }

    @Test
    void completeRowIsUsCoreCompliant() {
        FhirValidationOutcome outcome = UsCoreValidatorFixture.validator().validate(builder.build(completeRow()));

        assertThat(outcome.violations()).filteredOn(v -> "ERROR".equals(v.severity())).isEmpty();
        assertThat(outcome.compliant()).isTrue();
    }

    @Test
    void blankNameIsNotInventedAndFailsUsCore() {
        SilverPatientRow row = new SilverPatientRow(3L, "SEED_CLAIMS_CSV", "SEED-0003", null, "", "  ",
                LocalDate.of(1990, 1, 1), "unknown", null, null, null, null, null, null);

        Patient patient = builder.build(row);
        assertThat(patient.hasName()).isFalse();

        FhirValidationOutcome outcome = UsCoreValidatorFixture.validator().validate(patient);
        assertThat(outcome.compliant()).isFalse();
        assertThat(outcome.violations())
                .filteredOn(v -> "ERROR".equals(v.severity()))
                .extracting(FhirViolation::location)
                .containsExactly("Patient.name");
    }

    @Test
    void narrativeEscapesMarkup() {
        SilverPatientRow row = new SilverPatientRow(9L, "S", "X", null, "<b>Bob</b>", "O'Neil & Sons",
                LocalDate.of(2000, 1, 1), "male", null, null, null, null, null, null);

        assertThat(builder.build(row).getText().getDivAsString())
                .contains("&lt;b&gt;Bob&lt;/b&gt;")
                .doesNotContain("<b>");
    }

    @Test
    void sourceSystemIsSanitizedIntoIdentifierUrn() {
        assertThat(PatientResourceBuilder.systemFor("Epic Prod/1")).isEqualTo("urn:clinicalharmony:source:Epic_Prod_1");
        assertThat(PatientResourceBuilder.systemFor(null)).isEqualTo("urn:clinicalharmony:source:UNKNOWN");
    }
}
