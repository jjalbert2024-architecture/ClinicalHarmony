package com.clinicalharmony.fhircompliance.service.silver;

import com.clinicalharmony.fhircompliance.repository.silver.SilverConditionRow;
import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.clinicalharmony.fhircompliance.validation.FhirViolation;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import com.clinicalharmony.fhircompliance.validation.UsCoreValidatorFixture;
import org.hl7.fhir.r4.model.Condition;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionResourceBuilderTest {

    private final ConditionResourceBuilder builder = new ConditionResourceBuilder();

    @Test
    void mapsIcd10ConditionAndIsUsCoreCompliant() {
        SilverConditionRow row = new SilverConditionRow(5L, 1L, "ICD-10", "E11.9",
                "Type 2 diabetes mellitus without complications", "active",
                LocalDate.of(2015, 6, 1), LocalDate.of(2015, 6, 3));

        Condition condition = builder.build(row, "silver-patient-1");

        assertThat(condition.getIdPart()).isEqualTo("silver-condition-5");
        assertThat(condition.getMeta().hasProfile(UsCoreProfiles.CONDITION_PROBLEMS)).isTrue();
        assertThat(condition.getCode().getCodingFirstRep().getSystem()).isEqualTo("http://hl7.org/fhir/sid/icd-10-cm");
        assertThat(condition.getCode().getCodingFirstRep().getCode()).isEqualTo("E11.9");
        assertThat(condition.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("problem-list-item");
        assertThat(condition.getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo("active");
        assertThat(condition.getSubject().getReference()).isEqualTo("Patient/silver-patient-1");

        FhirValidationOutcome outcome = UsCoreValidatorFixture.validator().validate(condition);
        assertThat(outcome.violations()).filteredOn(v -> "ERROR".equals(v.severity())).isEmpty();
    }

    @Test
    void mapsSnomedCodeSystemUri() {
        SilverConditionRow row = new SilverConditionRow(6L, 2L, "SNOMED-CT", "13645005",
                "Chronic obstructive lung disease", "active", null, LocalDate.of(2019, 3, 5));

        Condition condition = builder.build(row, "silver-patient-2");

        assertThat(condition.getCode().getCodingFirstRep().getSystem()).isEqualTo("http://snomed.info/sct");
        assertThat(UsCoreValidatorFixture.validator().validate(condition).compliant()).isTrue();
    }

    @Test
    void blankCodeYieldsNoCodeAndFailsUsCore() {
        SilverConditionRow row = new SilverConditionRow(9L, 3L, "ICD-10", "", null, "active",
                null, LocalDate.of(2024, 5, 1));

        Condition condition = builder.build(row, "silver-patient-3");
        assertThat(condition.hasCode()).isFalse();

        FhirValidationOutcome outcome = UsCoreValidatorFixture.validator().validate(condition);
        assertThat(outcome.compliant()).isFalse();
        assertThat(outcome.violations())
                .filteredOn(v -> "ERROR".equals(v.severity()))
                .extracting(FhirViolation::location)
                .contains("Condition.code");
    }

    @Test
    void blankCodeWithDisplayKeepsTextOnly() {
        SilverConditionRow row = new SilverConditionRow(10L, 3L, "ICD-10", " ", "Chest pain", "active", null,
                LocalDate.of(2024, 5, 1));

        Condition condition = builder.build(row, "silver-patient-3");

        assertThat(condition.getCode().hasCoding()).isFalse();
        assertThat(condition.getCode().getText()).isEqualTo("Chest pain");
    }
}
