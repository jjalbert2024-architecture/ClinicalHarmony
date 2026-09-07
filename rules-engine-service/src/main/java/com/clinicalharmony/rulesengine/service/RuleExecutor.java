package com.clinicalharmony.rulesengine.service;

import com.clinicalharmony.rulesengine.rule.RuleEvaluator;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Evaluates every active clinical_rules row against a patient record. Each active rule is
 * dispatched to the RuleEvaluator bean whose ruleCode() matches; active rules with no
 * registered evaluator (e.g. CODE-*, which ontology-service evaluates) are skipped, not failed.
 */
@Service
public class RuleExecutor {

    private static final Logger log = LoggerFactory.getLogger(RuleExecutor.class);

    private final RuleLoader ruleLoader;
    private final Map<String, RuleEvaluator> evaluatorsByRuleCode;

    public RuleExecutor(RuleLoader ruleLoader, List<RuleEvaluator> evaluators) {
        this.ruleLoader = ruleLoader;
        this.evaluatorsByRuleCode = evaluators.stream()
                .collect(Collectors.toMap(RuleEvaluator::ruleCode, Function.identity()));
    }

    public List<RuleResult> evaluate(PatientRecordRequest request) {
        List<ClinicalRule> activeRules = ruleLoader.loadActiveRules();
        List<RuleResult> results = activeRules.stream()
                .filter(rule -> {
                    boolean hasEvaluator = evaluatorsByRuleCode.containsKey(rule.ruleCode());
                    if (!hasEvaluator) {
                        log.debug("No RuleEvaluator registered for active rule {}, skipping", rule.ruleCode());
                    }
                    return hasEvaluator;
                })
                .flatMap(rule -> evaluatorsByRuleCode.get(rule.ruleCode()).evaluate(request, rule).stream())
                .toList();
        log.info("Evaluated patientId={} rules={} results={} failures={}",
                request.patientId(), evaluatorsByRuleCode.size(), results.size(),
                results.stream().filter(r -> !r.passed()).count());
        return results;
    }
}
