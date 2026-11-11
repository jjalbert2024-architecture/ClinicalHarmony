package com.clinicalharmony.fhircompliance.service.silver;

import com.clinicalharmony.fhircompliance.repository.silver.SilverObservationRow;
import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.clinicalharmony.fhircompliance.validation.FhirViolation;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import com.clinicalharmony.fhircompliance.validation.UsCoreValidatorFixture;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationResourceBuilderTest {

    private final ObservationResourceBuilder builder = new ObservationResourceBuilder();

    private static final OffsetDateTime EFFECTIVE = OffsetDateTime.parse("2024-03-10T08:30:00Z");

    @Test
    void mapsNumericLabResultAndIsUsCoreCompliant() {
        SilverObservationRow row = new SilverObservationRow(7L, 1L, "4548-4",
                "Hemoglobin A1c/Hemoglobin.total in Blood", new BigDecimal("7.2000"), null, "%", "final", EFFECTIVE);

        Observation observation = builder.build(row, "silver-patient-1");

        assertThat(observation.getIdPart()).isEqualTo("silver-observation-7");
        assertThat(observation.getMeta().hasProfile(UsCoreProfiles.OBSERVATION_LAB)).isTrue();
        assertThat(observation.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("laboratory");
        assertThat(observation.getCode().getCodingFirstRep().getSystem()).isEqualTo("http://loinc.org");
        Quantity value = observation.getValueQuantity();
        assertThat(value.getValue()).isEqualByComparingTo("7.2");
        assertThat(value.getSystem()).isEqualTo("http://unitsofmeasure.org");
        assertThat(value.getCode()).isEqualTo("%");

        FhirValidationOutcome outcome = UsCoreValidatorFixture.validator().validate(observation);
        assertThat(outcome.violations()).filteredOn(v -> "ERROR".equals(v.severity())).isEmpty();
    }

    @Test
    void mapsTextValue() {
        SilverObservationRow row = new SilverObservationRow(8L, 1L, "5778-6", "Color of Urine",
                null, "Yellow", null, "final", EFFECTIVE);

        Observation observation = builder.build(row, "silver-patient-1");

        assertThat(observation.getValueStringType().getValue()).isEqualTo("Yellow");
        assertThat(UsCoreValidatorFixture.validator().validate(observation).compliant()).isTrue();
    }

    @Test
    void blankCodeFailsUsCore() {
        SilverObservationRow row = new SilverObservationRow(9L, 3L, "", null,
                new BigDecimal("13.5"), null, "g/dL", "final", EFFECTIVE);

        Observation observation = builder.build(row, "silver-patient-3");

        FhirValidationOutcome outcome = UsCoreValidatorFixture.validator().validate(observation);
        assertThat(outcome.compliant()).isFalse();
        assertThat(outcome.violations())
                .filteredOn(v -> "ERROR".equals(v.severity()))
                .extracting(FhirViolation::location)
                .contains("Observation.code");
    }

    @Test
    void invalidUcumUnitIsCaught() {
        SilverObservationRow row = new SilverObservationRow(10L, 1L, "2345-7", "Glucose",
                new BigDecimal("142"), null, "milligrams per deciliter", "final", EFFECTIVE);

        FhirValidationOutcome outcome = UsCoreValidatorFixture.validator().validate(builder.build(row, "silver-patient-1"));

        assertThat(outcome.compliant()).isFalse();
        assertThat(outcome.violations())
                .filteredOn(v -> "ERROR".equals(v.severity()))
                .extracting(FhirViolation::location)
                .anyMatch(l -> l.startsWith("Observation.value"));
    }
}
