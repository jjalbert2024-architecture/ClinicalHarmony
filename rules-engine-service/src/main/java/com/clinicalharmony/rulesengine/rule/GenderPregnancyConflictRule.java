package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GENDER_PREGNANCY_CONFLICT: a patient recorded with a conflicting gender (configured, default
 * "male") must not carry a pregnancy diagnosis (configured ICD-10/SNOMED-CT code list).
 */
@Component
public class GenderPregnancyConflictRule implements RuleEvaluator {

    @Override
    public String ruleCode() {
        return "GENDER_PREGNANCY_CONFLICT";
    }

    @Override
    public List<RuleResult> evaluate(PatientRecordRequest request, ClinicalRule rule) {
        Set<String> conflictingGenders = new HashSet<>();
        rule.expression().path("conflictingGenders").forEach(n -> conflictingGenders.add(n.asText().toLowerCase()));

        String gender = request.gender();
        boolean genderConflicts = gender != null && conflictingGenders.contains(gender.toLowerCase());
        if (!genderConflicts || request.conditions() == null) {
            return List.of(RuleResult.pass(ruleCode(), rule.severity(), "PATIENT", request.patientId(), request.patientId()));
        }

        Set<String> icd10PregnancyCodes = toCodeSet(rule.expression().path("pregnancyCodes").path("ICD-10"));
        Set<String> snomedPregnancyCodes = toCodeSet(rule.expression().path("pregnancyCodes").path("SNOMED-CT"));

        List<RuleResult> results = new ArrayList<>();
        for (PatientRecordRequest.ConditionItem condition : request.conditions()) {
            boolean isPregnancyCode = "ICD-10".equalsIgnoreCase(condition.codeSystem())
                    ? icd10PregnancyCodes.contains(condition.code())
                    : "SNOMED-CT".equalsIgnoreCase(condition.codeSystem()) && snomedPregnancyCodes.contains(condition.code());
            if (isPregnancyCode) {
                results.add(RuleResult.fail(ruleCode(), rule.severity(), "CONDITION", condition.id(), request.patientId(),
                        "conditions[].code",
                        "Patient gender '" + gender + "' conflicts with pregnancy diagnosis " + condition.code(),
                        condition.code(), null));
            }
        }

        if (results.isEmpty()) {
            return List.of(RuleResult.pass(ruleCode(), rule.severity(), "PATIENT", request.patientId(), request.patientId()));
        }
        return results;
    }

    private static Set<String> toCodeSet(JsonNode arrayNode) {
        Set<String> codes = new HashSet<>();
        arrayNode.forEach(n -> codes.add(n.asText()));
        return codes;
    }
}
