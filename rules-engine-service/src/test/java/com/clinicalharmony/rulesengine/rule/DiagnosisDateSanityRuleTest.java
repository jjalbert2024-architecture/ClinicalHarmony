package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisDateSanityRuleTest {

    private final DiagnosisDateSanityRule rule = new DiagnosisDateSanityRule();
    private final ClinicalRule config = new ClinicalRule("DIAGNOSIS_DATE_SANITY", "Future-Dated Diagnosis", "desc",
            "DATA_QUALITY", "WARNING", true, NullNode.getInstance());

    @Test
    void pastOnsetDatePasses() {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null,
                List.of(new PatientRecordRequest.ConditionItem(10L, "ICD-10", "E11.9", LocalDate.now().minusDays(10), null)),
                List.of(), List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }

    @Test
    void futureOnsetDateFails() {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null,
                List.of(new PatientRecordRequest.ConditionItem(10L, "ICD-10", "E11.9", LocalDate.now().plusDays(5), null)),
                List.of(), List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isFalse();
        assertThat(results.get(0).entityType()).isEqualTo("CONDITION");
        assertThat(results.get(0).entityId()).isEqualTo(10L);
    }

    @Test
    void conditionWithoutOnsetDateIsNotEvaluated() {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null,
                List.of(new PatientRecordRequest.ConditionItem(10L, "ICD-10", "E11.9", null, null)),
                List.of(), List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(results.get(0).entityType()).isEqualTo("PATIENT");
    }
}
