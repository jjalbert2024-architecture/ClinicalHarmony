package com.clinicalharmony.rulesengine.service;

import com.clinicalharmony.rulesengine.repository.ClinicalRuleRecord;
import com.clinicalharmony.rulesengine.repository.ClinicalRuleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleLoaderTest {

    private ClinicalRuleRepository repository;
    private RuleLoader ruleLoader;

    @BeforeEach
    void setUp() {
        repository = mock(ClinicalRuleRepository.class);
        ruleLoader = new RuleLoader(repository, new ObjectMapper());
    }

    @Test
    void loadActiveRulesParsesRuleExpressionJson() {
        ClinicalRuleRecord record = new ClinicalRuleRecord(1L, "PATIENT_AGE_CONSISTENCY", "name", "desc",
                "DATA_QUALITY", "WARNING", true, "{\"toleranceYears\": 1}");
        when(repository.findAllActive()).thenReturn(List.of(record));

        List<ClinicalRule> rules = ruleLoader.loadActiveRules();

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).expression().path("toleranceYears").asInt()).isEqualTo(1);
        assertThat(rules.get(0).active()).isTrue();
    }

    @Test
    void nullRuleExpressionBecomesNullNode() {
        ClinicalRuleRecord record = new ClinicalRuleRecord(1L, "DIAGNOSIS_DATE_SANITY", "name", "desc",
                "DATA_QUALITY", "WARNING", true, null);
        when(repository.findAllActive()).thenReturn(List.of(record));

        List<ClinicalRule> rules = ruleLoader.loadActiveRules();

        assertThat(rules.get(0).expression().isNull()).isTrue();
    }

    @Test
    void malformedRuleExpressionIsTreatedAsEmptyRatherThanThrowing() {
        ClinicalRuleRecord record = new ClinicalRuleRecord(1L, "BROKEN_RULE", "name", "desc",
                "DATA_QUALITY", "WARNING", true, "{not valid json");
        when(repository.findAllActive()).thenReturn(List.of(record));

        List<ClinicalRule> rules = ruleLoader.loadActiveRules();

        assertThat(rules.get(0).expression().isNull()).isTrue();
    }

    @Test
    void toggleDelegatesToRepositoryAndReturnsUpdatedDomainObject() {
        ClinicalRuleRecord record = new ClinicalRuleRecord(1L, "PATIENT_AGE_CONSISTENCY", "name", "desc",
                "DATA_QUALITY", "WARNING", false, null);
        when(repository.toggleActive("PATIENT_AGE_CONSISTENCY")).thenReturn(Optional.of(record));

        Optional<ClinicalRule> result = ruleLoader.toggle("PATIENT_AGE_CONSISTENCY");

        assertThat(result).isPresent();
        assertThat(result.get().active()).isFalse();
    }

    @Test
    void toggleReturnsEmptyForUnknownRuleCode() {
        when(repository.toggleActive("NOPE")).thenReturn(Optional.empty());

        Optional<ClinicalRule> result = ruleLoader.toggle("NOPE");

        assertThat(result).isEmpty();
    }
}
