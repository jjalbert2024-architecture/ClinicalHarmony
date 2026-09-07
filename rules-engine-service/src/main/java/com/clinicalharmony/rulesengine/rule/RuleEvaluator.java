package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;

import java.util.List;

/**
 * One built-in rule type. {@code ruleCode()} is the ontology.clinical_rules.rule_code this
 * evaluator implements — RuleExecutor dispatches each active rule to the evaluator whose
 * ruleCode() matches, and silently skips active rules with no registered evaluator (e.g.
 * CODE-* rules, which ontology-service evaluates instead).
 */
public interface RuleEvaluator {

    String ruleCode();

    List<RuleResult> evaluate(PatientRecordRequest request, ClinicalRule rule);
}
