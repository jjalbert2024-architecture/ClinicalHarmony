package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MedicationAllergyConflictRuleTest {

    private final MedicationAllergyConflictRule rule = new MedicationAllergyConflictRule();
    private final ClinicalRule config = new ClinicalRule("MEDICATION_ALLERGY_CONFLICT", "Medication Allergy Conflict", "desc",
            "CLINICAL_LOGIC", "ERROR", true, NullNode.getInstance());

    @Test
    void medicationNotMatchingAnyAllergyPasses() {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null, List.of(), List.of(),
                List.of(new PatientRecordRequest.MedicationItem(30L, "6809", "Metformin", null)),
                List.of(new PatientRecordRequest.AllergyItem("723", "Amoxicillin")),
                List.of());

        List<RuleResult> results = rule.evaluate(request, config);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }

    @Test
    void medicationMatchingAllergyByRxnormCodeFails() {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null, List.of(), List.of(),
                List.of(new PatientRecordRequest.MedicationItem(30L, "723", "Amoxicillin", null)),
                List.of(new PatientRecordRequest.AllergyItem("723", "Amoxicillin")),
                List.of());

        List<RuleResult> results = rule.evaluate(request, config);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isFalse();
        assertThat(results.get(0).entityType()).isEqualTo("MEDICATION");
        assertThat(results.get(0).entityId()).isEqualTo(30L);
    }

    @Test
    void medicationMatchingAllergyBySubstanceTextFails() {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null, List.of(), List.of(),
                List.of(new PatientRecordRequest.MedicationItem(30L, "723", "Amoxicillin 500mg", null)),
                List.of(new PatientRecordRequest.AllergyItem(null, "amoxicillin")),
                List.of());

        List<RuleResult> results = rule.evaluate(request, config);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isFalse();
    }

    @Test
    void noAllergiesRecordedIsNotEvaluated() {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null, List.of(), List.of(),
                List.of(new PatientRecordRequest.MedicationItem(30L, "723", "Amoxicillin", null)),
                List.of(),
                List.of());

        List<RuleResult> results = rule.evaluate(request, config);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(results.get(0).entityType()).isEqualTo("PATIENT");
    }
}
