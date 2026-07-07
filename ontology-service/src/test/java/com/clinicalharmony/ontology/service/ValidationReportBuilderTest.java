package com.clinicalharmony.ontology.service;

import com.clinicalharmony.ontology.repository.ValidationReportRecord;
import com.clinicalharmony.ontology.repository.ValidationReportRepository;
import com.clinicalharmony.ontology.validator.ValidationOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ValidationReportBuilderTest {

    @Test
    void onlyFailedValidationsArePersisted() {
        ValidationReportRepository repository = mock(ValidationReportRepository.class);
        when(repository.insert(any())).thenReturn(500L, 501L);
        ValidationReportBuilder builder = new ValidationReportBuilder(repository);

        ItemValidation validCondition = new ItemValidation(101L, "CONDITION", "ICD-10",
                ValidationOutcome.valid("E11.9", "Diabetes", "Endocrine"), "CODE-001");
        ItemValidation invalidCondition = new ItemValidation(102L, "CONDITION", "SNOMED-CT",
                ValidationOutcome.invalid("999999", "Code not found in SNOMED CT reference set"), "CODE-004");
        ItemValidation invalidMedication = new ItemValidation(301L, "MEDICATION", "RxNorm",
                ValidationOutcome.invalid("000000", "Code not found in RxNorm reference set"), "CODE-003");

        OntologyValidationResult result = new OntologyValidationResult(
                42L, List.of(validCondition, invalidCondition), List.of(), List.of(invalidMedication));

        List<Long> reportIds = builder.buildAndPersist(result);

        assertThat(reportIds).containsExactly(500L, 501L);
        ArgumentCaptor<ValidationReportRecord> captor = ArgumentCaptor.forClass(ValidationReportRecord.class);
        verify(repository, times(2)).insert(captor.capture());

        ValidationReportRecord first = captor.getAllValues().get(0);
        assertThat(first.entityType()).isEqualTo("CONDITION");
        assertThat(first.entityId()).isEqualTo(102L);
        assertThat(first.ruleCode()).isEqualTo("CODE-004");
        assertThat(first.severity()).isEqualTo("ERROR");
        assertThat(first.patientId()).isEqualTo(42L);
        assertThat(first.badValue()).isEqualTo("999999");

        ValidationReportRecord second = captor.getAllValues().get(1);
        assertThat(second.entityType()).isEqualTo("MEDICATION");
        assertThat(second.ruleCode()).isEqualTo("CODE-003");
    }
}
