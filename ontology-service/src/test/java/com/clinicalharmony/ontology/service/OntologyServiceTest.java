package com.clinicalharmony.ontology.service;

import com.clinicalharmony.ontology.validator.Icd10Validator;
import com.clinicalharmony.ontology.validator.LoincValidator;
import com.clinicalharmony.ontology.validator.RxNormValidator;
import com.clinicalharmony.ontology.validator.SnomedValidator;
import com.clinicalharmony.ontology.validator.ValidationOutcome;
import com.clinicalharmony.ontology.web.dto.PatientRecordRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OntologyServiceTest {

    private Icd10Validator icd10Validator;
    private LoincValidator loincValidator;
    private RxNormValidator rxNormValidator;
    private SnomedValidator snomedValidator;
    private OntologyService ontologyService;

    @BeforeEach
    void setUp() {
        icd10Validator = mock(Icd10Validator.class);
        loincValidator = mock(LoincValidator.class);
        rxNormValidator = mock(RxNormValidator.class);
        snomedValidator = mock(SnomedValidator.class);
        ontologyService = new OntologyService(icd10Validator, loincValidator, rxNormValidator, snomedValidator);
    }

    @Test
    void dispatchesEachConditionToTheRightValidatorByCodeSystem() {
        when(icd10Validator.validate(eq("E11.9"))).thenReturn(ValidationOutcome.valid("E11.9", "Diabetes", "Endocrine"));
        when(snomedValidator.validate(eq("44054006"))).thenReturn(ValidationOutcome.valid("44054006", "Diabetes mellitus type 2", "disorder"));

        var request = new PatientRecordRequest(1L,
                List.of(new PatientRecordRequest.ConditionItem(101L, "ICD-10", "E11.9"),
                        new PatientRecordRequest.ConditionItem(102L, "SNOMED-CT", "44054006")),
                List.of(), List.of());

        OntologyValidationResult result = ontologyService.validate(request);

        assertThat(result.conditionResults()).hasSize(2);
        assertThat(result.conditionResults().get(0).ruleCode()).isEqualTo("CODE-001");
        assertThat(result.conditionResults().get(1).ruleCode()).isEqualTo("CODE-004");
        assertThat(result.allValid()).isTrue();
    }

    @Test
    void unsupportedCodeSystemIsInvalidWithoutCallingAnyValidator() {
        var request = new PatientRecordRequest(1L,
                List.of(new PatientRecordRequest.ConditionItem(101L, "CPT", "99213")),
                List.of(), List.of());

        OntologyValidationResult result = ontologyService.validate(request);

        assertThat(result.conditionResults().get(0).outcome().valid()).isFalse();
        assertThat(result.conditionResults().get(0).outcome().reason()).contains("Unsupported code system");
    }

    @Test
    void observationsGoToLoincAndMedicationsGoToRxNorm() {
        when(loincValidator.validate(eq("2093-3"))).thenReturn(ValidationOutcome.invalid("2093-3", "Code not found in LOINC reference set"));
        when(rxNormValidator.validate(eq("6809"))).thenReturn(ValidationOutcome.valid("6809", "Metformin", "IN"));

        var request = new PatientRecordRequest(1L, List.of(),
                List.of(new PatientRecordRequest.ObservationItem(201L, "2093-3")),
                List.of(new PatientRecordRequest.MedicationItem(301L, "6809")));

        OntologyValidationResult result = ontologyService.validate(request);

        assertThat(result.observationResults().get(0).ruleCode()).isEqualTo("CODE-002");
        assertThat(result.observationResults().get(0).outcome().valid()).isFalse();
        assertThat(result.medicationResults().get(0).ruleCode()).isEqualTo("CODE-003");
        assertThat(result.medicationResults().get(0).outcome().valid()).isTrue();
        assertThat(result.allValid()).isFalse();
    }
}
