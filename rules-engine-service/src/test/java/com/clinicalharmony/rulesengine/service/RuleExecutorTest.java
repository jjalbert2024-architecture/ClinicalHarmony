package com.clinicalharmony.rulesengine.service;

import com.clinicalharmony.rulesengine.rule.RuleEvaluator;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuleExecutorTest {

    @Test
    void dispatchesEachActiveRuleToItsMatchingEvaluatorAndSkipsUnmatched() {
        ClinicalRule ruleWithEvaluator = new ClinicalRule("RULE_A", "name", "desc", "CLINICAL_LOGIC", "ERROR", true, NullNode.getInstance());
        ClinicalRule ruleWithoutEvaluator = new ClinicalRule("RULE_B", "name", "desc", "CLINICAL_LOGIC", "ERROR", true, NullNode.getInstance());

        RuleLoader ruleLoader = mock(RuleLoader.class);
        when(ruleLoader.loadActiveRules()).thenReturn(List.of(ruleWithEvaluator, ruleWithoutEvaluator));

        RuleEvaluator evaluatorA = mock(RuleEvaluator.class);
        when(evaluatorA.ruleCode()).thenReturn("RULE_A");
        RuleResult passResult = RuleResult.pass("RULE_A", "ERROR", "PATIENT", 1L, 1L);
        when(evaluatorA.evaluate(any(), any())).thenReturn(List.of(passResult));

        RuleExecutor executor = new RuleExecutor(ruleLoader, List.of(evaluatorA));
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of());

        List<RuleResult> results = executor.evaluate(request);

        assertThat(results).containsExactly(passResult);
        verify(evaluatorA).evaluate(request, ruleWithEvaluator);
    }

    @Test
    void noActiveRulesProducesEmptyResultList() {
        RuleLoader ruleLoader = mock(RuleLoader.class);
        when(ruleLoader.loadActiveRules()).thenReturn(List.of());
        RuleEvaluator evaluator = mock(RuleEvaluator.class);
        when(evaluator.ruleCode()).thenReturn("RULE_A");

        RuleExecutor executor = new RuleExecutor(ruleLoader, List.of(evaluator));
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of());

        List<RuleResult> results = executor.evaluate(request);

        assertThat(results).isEmpty();
        verify(evaluator, never()).evaluate(any(), any());
    }
}
