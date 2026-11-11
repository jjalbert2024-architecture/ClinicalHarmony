package com.clinicalharmony.fhircompliance.service.silver;

import com.clinicalharmony.fhircompliance.repository.silver.SilverMedicationRow;
import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.clinicalharmony.fhircompliance.validation.FhirViolation;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import com.clinicalharmony.fhircompliance.validation.UsCoreValidatorFixture;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MedicationRequestResourceBuilderTest {

    private final MedicationRequestResourceBuilder builder = new MedicationRequestResourceBuilder();

    @Test
    void mapsActiveMedicationAndIsUsCoreCompliant() {
        SilverMedicationRow row = new SilverMedicationRow(3L, 1L, "6809", "Metformin",
                "500 mg by mouth twice daily with meals", "active", LocalDate.of(2015, 6, 3), null);

        MedicationRequest request = builder.build(row, "silver-patient-1");

        assertThat(request.getIdPart()).isEqualTo("silver-medication-3");
        assertThat(request.getMeta().hasProfile(UsCoreProfiles.MEDICATION_REQUEST)).isTrue();
        assertThat(request.getStatus().toCode()).isEqualTo("active");
        assertThat(request.getIntent().toCode()).isEqualTo("order");
        assertThat(request.getMedicationCodeableConcept().getCodingFirstRep().getSystem())
                .isEqualTo("http://www.nlm.nih.gov/research/umls/rxnorm");
        assertThat(request.getMedicationCodeableConcept().getCodingFirstRep().getCode()).isEqualTo("6809");
        assertThat(request.getAuthoredOnElement().getValueAsString()).isEqualTo("2015-06-03");
        assertThat(request.getDosageInstructionFirstRep().getText()).contains("twice daily");
        // No prescriber in Silver → explicit data-absent-reason, not an invented practitioner.
        assertThat(request.getRequester().hasReference()).isFalse();
        assertThat(request.getRequester().getExtensionByUrl(MedicationRequestResourceBuilder.DATA_ABSENT_REASON)
                .getValue().primitiveValue()).isEqualTo("unknown");

        FhirValidationOutcome outcome = UsCoreValidatorFixture.validator().validate(request);
        assertThat(outcome.violations()).filteredOn(v -> "ERROR".equals(v.severity())).isEmpty();
    }

    @Test
    void stoppedMedicationCarriesValidityPeriodEnd() {
        SilverMedicationRow row = new SilverMedicationRow(4L, 2L, "17767", "Amlodipine", "5 mg daily",
                "stopped", LocalDate.of(2012, 1, 10), LocalDate.of(2016, 7, 1));

        MedicationRequest request = builder.build(row, "silver-patient-2");

        assertThat(request.getStatus().toCode()).isEqualTo("stopped");
        assertThat(request.getDispenseRequest().getValidityPeriod().getEndElement().getValueAsString()).isEqualTo("2016-07-01");
        assertThat(UsCoreValidatorFixture.validator().validate(request).compliant()).isTrue();
    }

    @Test
    void blankRxNormCodeFailsUsCore() {
        SilverMedicationRow row = new SilverMedicationRow(5L, 3L, "", null, null, "active",
                LocalDate.of(2024, 1, 1), null);

        MedicationRequest request = builder.build(row, "silver-patient-3");
        assertThat(request.hasMedication()).isFalse();

        FhirValidationOutcome outcome = UsCoreValidatorFixture.validator().validate(request);
        assertThat(outcome.compliant()).isFalse();
        assertThat(outcome.violations())
                .filteredOn(v -> "ERROR".equals(v.severity()))
                .extracting(FhirViolation::location)
                .anyMatch(l -> l.startsWith("MedicationRequest.medication"));
    }
}
