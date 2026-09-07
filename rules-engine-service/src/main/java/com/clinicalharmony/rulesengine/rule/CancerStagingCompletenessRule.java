package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * CANCER_STAGING_COMPLETENESS: a condition coded in the ICD-10 neoplasm range (default C00-D49)
 * must carry staging information. Range comparison is a plain 3-character lexicographic compare
 * on the code prefix — safe here because every code in this range is a letter followed by two
 * zero-padded digits (C00..C99, D00..D49).
 */
@Component
public class CancerStagingCompletenessRule implements RuleEvaluator {

    @Override
    public String ruleCode() {
        return "CANCER_STAGING_COMPLETENESS";
    }

    @Override
    public List<RuleResult> evaluate(PatientRecordRequest request, ClinicalRule rule) {
        String rangeStart = rule.expression().path("icd10RangeStart").asText("C00").toUpperCase();
        String rangeEnd = rule.expression().path("icd10RangeEnd").asText("D49").toUpperCase();

        List<RuleResult> results = new ArrayList<>();
        if (request.conditions() != null) {
            for (PatientRecordRequest.ConditionItem condition : request.conditions()) {
                if (!"ICD-10".equalsIgnoreCase(condition.codeSystem()) || condition.code() == null) {
                    continue;
                }
                String prefix = condition.code().length() >= 3
                        ? condition.code().substring(0, 3).toUpperCase()
                        : condition.code().toUpperCase();
                if (prefix.compareTo(rangeStart) < 0 || prefix.compareTo(rangeEnd) > 0) {
                    continue;
                }
                boolean hasStaging = condition.stagingInfo() != null && !condition.stagingInfo().isBlank();
                if (hasStaging) {
                    results.add(RuleResult.pass(ruleCode(), rule.severity(), "CONDITION", condition.id(), request.patientId()));
                } else {
                    results.add(RuleResult.fail(ruleCode(), rule.severity(), "CONDITION", condition.id(), request.patientId(),
                            "conditions[].stagingInfo",
                            "Neoplasm diagnosis " + condition.code() + " is missing staging information",
                            condition.code(), "Add TNM or equivalent staging information"));
                }
            }
        }

        if (results.isEmpty()) {
            return List.of(RuleResult.pass(ruleCode(), rule.severity(), "PATIENT", request.patientId(), request.patientId()));
        }
        return results;
    }
}
