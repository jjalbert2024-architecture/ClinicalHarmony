package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;

/**
 * PATIENT_AGE_CONSISTENCY: a patient's stated age must match the age calculated from their date
 * of birth, within a configurable tolerance (default 0 years). Not evaluable — and passes
 * vacuously — when either dateOfBirth or statedAge is absent.
 */
@Component
public class PatientAgeConsistencyRule implements RuleEvaluator {

    @Override
    public String ruleCode() {
        return "PATIENT_AGE_CONSISTENCY";
    }

    @Override
    public List<RuleResult> evaluate(PatientRecordRequest request, ClinicalRule rule) {
        if (request.dateOfBirth() == null || request.statedAge() == null) {
            return List.of(RuleResult.pass(ruleCode(), rule.severity(), "PATIENT", request.patientId(), request.patientId()));
        }

        int toleranceYears = rule.expression().path("toleranceYears").asInt(0);
        int calculatedAge = Period.between(request.dateOfBirth(), LocalDate.now()).getYears();
        int delta = Math.abs(calculatedAge - request.statedAge());

        if (delta > toleranceYears) {
            return List.of(RuleResult.fail(ruleCode(), rule.severity(), "PATIENT", request.patientId(), request.patientId(),
                    "statedAge",
                    "Stated age " + request.statedAge() + " does not match calculated age " + calculatedAge
                            + " from date of birth " + request.dateOfBirth(),
                    String.valueOf(request.statedAge()), String.valueOf(calculatedAge)));
        }
        return List.of(RuleResult.pass(ruleCode(), rule.severity(), "PATIENT", request.patientId(), request.patientId()));
    }
}
