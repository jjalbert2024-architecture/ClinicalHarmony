package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LabReferenceRangeRuleTest {

    private final LabReferenceRangeRule rule = new LabReferenceRangeRule();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ClinicalRule config() throws Exception {
        String json = """
                {"defaultRanges": {"2345-7": {"low": 70, "high": 99, "unit": "mg/dL"}}}
                """;
        return new ClinicalRule("LAB_REFERENCE_RANGE", "Lab Reference Range", "desc",
                "CLINICAL_LOGIC", "WARNING", true, objectMapper.readTree(json));
    }

    @Test
    void valueWithinDefaultRangePasses() throws Exception {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null, List.of(),
                List.of(new PatientRecordRequest.ObservationItem(20L, "2345-7", 85.0, null, null, null)),
                List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }

    @Test
    void valueOutsideDefaultRangeFails() throws Exception {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null, List.of(),
                List.of(new PatientRecordRequest.ObservationItem(20L, "2345-7", 250.0, null, null, null)),
                List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isFalse();
        assertThat(results.get(0).entityType()).isEqualTo("OBSERVATION");
        assertThat(results.get(0).entityId()).isEqualTo(20L);
    }

    @Test
    void itemSuppliedRangeOverridesDefault() throws Exception {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null, List.of(),
                List.of(new PatientRecordRequest.ObservationItem(20L, "2345-7", 150.0, null, 100.0, 200.0)),
                List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }

    @Test
    void observationWithNoResolvableRangeIsNotEvaluated() throws Exception {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null, List.of(),
                List.of(new PatientRecordRequest.ObservationItem(20L, "9999-9", 50.0, null, null, null)),
                List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(results.get(0).entityType()).isEqualTo("PATIENT");
    }
}
