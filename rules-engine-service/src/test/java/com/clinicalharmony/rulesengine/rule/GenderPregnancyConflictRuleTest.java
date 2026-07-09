package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GenderPregnancyConflictRuleTest {

    private final GenderPregnancyConflictRule rule = new GenderPregnancyConflictRule();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ClinicalRule config() throws Exception {
        String json = """
                {"conflictingGenders": ["male"], "pregnancyCodes": {"ICD-10": ["Z33.1"], "SNOMED-CT": ["77386006"]}}
                """;
        return new ClinicalRule("GENDER_PREGNANCY_CONFLICT", "Gender/Pregnancy Conflict", "desc",
                "CLINICAL_LOGIC", "ERROR", true, objectMapper.readTree(json));
    }

    @Test
    void malePatientWithPregnancyDiagnosisFails() throws Exception {
        PatientRecordRequest request = new PatientRecordRequest(1L, "male", null, null,
                List.of(new PatientRecordRequest.ConditionItem(10L, "ICD-10", "Z33.1", null, null)),
                List.of(), List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isFalse();
        assertThat(results.get(0).entityType()).isEqualTo("CONDITION");
        assertThat(results.get(0).entityId()).isEqualTo(10L);
        assertThat(results.get(0).failureReason()).contains("Z33.1");
    }

    @Test
    void malePatientWithoutPregnancyDiagnosisPasses() throws Exception {
        PatientRecordRequest request = new PatientRecordRequest(1L, "male", null, null,
                List.of(new PatientRecordRequest.ConditionItem(10L, "ICD-10", "E11.9", null, null)),
                List.of(), List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(results.get(0).entityType()).isEqualTo("PATIENT");
    }

    @Test
    void femalePatientWithPregnancyDiagnosisPasses() throws Exception {
        PatientRecordRequest request = new PatientRecordRequest(1L, "female", null, null,
                List.of(new PatientRecordRequest.ConditionItem(10L, "ICD-10", "Z33.1", null, null)),
                List.of(), List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }
}
