package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** DIAGNOSIS_DATE_SANITY: a condition's onset date must not be dated in the future. */
@Component
public class DiagnosisDateSanityRule implements RuleEvaluator {

    @Override
    public String ruleCode() {
        return "DIAGNOSIS_DATE_SANITY";
    }

    @Override
    public List<RuleResult> evaluate(PatientRecordRequest request, ClinicalRule rule) {
        List<RuleResult> results = new ArrayList<>();
        LocalDate today = LocalDate.now();

        if (request.conditions() != null) {
            for (PatientRecordRequest.ConditionItem condition : request.conditions()) {
                if (condition.onsetDate() == null) {
                    continue;
                }
                if (condition.onsetDate().isAfter(today)) {
                    results.add(RuleResult.fail(ruleCode(), rule.severity(), "CONDITION", condition.id(), request.patientId(),
                            "conditions[].onsetDate",
                            "Condition onset date " + condition.onsetDate() + " is in the future",
                            condition.onsetDate().toString(), "Correct the onset date to a past or current date"));
                } else {
                    results.add(RuleResult.pass(ruleCode(), rule.severity(), "CONDITION", condition.id(), request.patientId()));
                }
            }
        }

        if (results.isEmpty()) {
            return List.of(RuleResult.pass(ruleCode(), rule.severity(), "PATIENT", request.patientId(), request.patientId()));
        }
        return results;
    }
}
