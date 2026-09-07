package com.clinicalharmony.rulesengine.service;

import com.clinicalharmony.rulesengine.repository.ValidationReportRecord;
import com.clinicalharmony.rulesengine.repository.ValidationReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleFindingBuilderTest {

    private ValidationReportRepository repository;
    private RuleFindingBuilder builder;

    @BeforeEach
    void setUp() {
        repository = mock(ValidationReportRepository.class);
        builder = new RuleFindingBuilder(repository);
    }

    @Test
    void onlyFailedResultsArePersisted() {
        RuleResult pass = RuleResult.pass("RULE_A", "ERROR", "PATIENT", 1L, 1L);
        RuleResult fail = RuleResult.fail("RULE_A", "ERROR", "CONDITION", 10L, 1L, "field", "bad", "X", "fix");
        when(repository.insert(any(ValidationReportRecord.class))).thenReturn(99L);

        List<Long> ids = builder.buildAndPersist(List.of(pass, fail));

        assertThat(ids).containsExactly(99L);
        verify(repository).insert(any(ValidationReportRecord.class));
    }

    @Test
    void allPassingResultsPersistNothing() {
        RuleResult pass1 = RuleResult.pass("RULE_A", "ERROR", "PATIENT", 1L, 1L);
        RuleResult pass2 = RuleResult.pass("RULE_B", "WARNING", "PATIENT", 1L, 1L);

        List<Long> ids = builder.buildAndPersist(List.of(pass1, pass2));

        assertThat(ids).isEmpty();
        verify(repository, never()).insert(any());
    }
}
