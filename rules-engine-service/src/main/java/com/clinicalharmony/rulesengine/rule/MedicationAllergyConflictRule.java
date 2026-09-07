package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MEDICATION_ALLERGY_CONFLICT: a prescribed medication must not match a substance on the
 * patient's known allergy list. Matches by exact RxNorm code (the reliable signal, since
 * ontology.rxnorm_codes is seeded at ingredient level) or, failing that, a case-insensitive
 * substring match between the medication display name and the allergy's free-text substance —
 * a best-effort fallback for allergy lists that only carry text (e.g. from an HL7 AL1 segment).
 */
@Component
public class MedicationAllergyConflictRule implements RuleEvaluator {

    @Override
    public String ruleCode() {
        return "MEDICATION_ALLERGY_CONFLICT";
    }

    @Override
    public List<RuleResult> evaluate(PatientRecordRequest request, ClinicalRule rule) {
        List<RuleResult> results = new ArrayList<>();

        if (request.medications() != null && request.allergies() != null && !request.allergies().isEmpty()) {
            for (PatientRecordRequest.MedicationItem medication : request.medications()) {
                PatientRecordRequest.AllergyItem conflict = findConflict(medication, request.allergies());
                if (conflict != null) {
                    String conflictLabel = conflict.rxnormCode() != null ? conflict.rxnormCode() : conflict.substance();
                    results.add(RuleResult.fail(ruleCode(), rule.severity(), "MEDICATION", medication.id(), request.patientId(),
                            "medications[].rxnormCode",
                            "Medication " + label(medication) + " conflicts with known allergy: " + conflictLabel,
                            label(medication), "Discontinue or verify with prescriber before administering"));
                } else {
                    results.add(RuleResult.pass(ruleCode(), rule.severity(), "MEDICATION", medication.id(), request.patientId()));
                }
            }
        }

        if (results.isEmpty()) {
            return List.of(RuleResult.pass(ruleCode(), rule.severity(), "PATIENT", request.patientId(), request.patientId()));
        }
        return results;
    }

    private static PatientRecordRequest.AllergyItem findConflict(PatientRecordRequest.MedicationItem medication,
                                                                   List<PatientRecordRequest.AllergyItem> allergies) {
        for (PatientRecordRequest.AllergyItem allergy : allergies) {
            if (allergy.rxnormCode() != null && allergy.rxnormCode().equals(medication.rxnormCode())) {
                return allergy;
            }
            if (allergy.substance() != null && medication.display() != null
                    && medication.display().toLowerCase().contains(allergy.substance().toLowerCase())) {
                return allergy;
            }
        }
        return null;
    }

    private static String label(PatientRecordRequest.MedicationItem medication) {
        return medication.display() != null ? medication.display() : medication.rxnormCode();
    }
}
