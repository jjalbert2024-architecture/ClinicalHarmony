package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CancerStagingCompletenessRuleTest {

    private final CancerStagingCompletenessRule rule = new CancerStagingCompletenessRule();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ClinicalRule config() throws Exception {
        String json = """
                {"icd10RangeStart": "C00", "icd10RangeEnd": "D49"}
                """;
        return new ClinicalRule("CANCER_STAGING_COMPLETENESS", "Cancer Staging Completeness", "desc",
                "DATA_QUALITY", "WARNING", true, objectMapper.readTree(json));
    }

    @Test
    void neoplasmDiagnosisWithStagingPasses() throws Exception {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null,
                List.of(new PatientRecordRequest.ConditionItem(10L, "ICD-10", "C50.912", null, "Stage IIIA")),
                List.of(), List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(results.get(0).entityType()).isEqualTo("CONDITION");
    }

    @Test
    void neoplasmDiagnosisMissingStagingFails() throws Exception {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null,
                List.of(new PatientRecordRequest.ConditionItem(10L, "ICD-10", "C50.912", null, null)),
                List.of(), List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isFalse();
        assertThat(results.get(0).entityId()).isEqualTo(10L);
        assertThat(results.get(0).suggestedFix()).containsIgnoringCase("staging");
    }

    @Test
    void nonNeoplasmDiagnosisIsNotEvaluated() throws Exception {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null,
                List.of(new PatientRecordRequest.ConditionItem(10L, "ICD-10", "E11.9", null, null)),
                List.of(), List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(results.get(0).entityType()).isEqualTo("PATIENT");
    }
}
