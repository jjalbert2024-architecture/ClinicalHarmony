package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PatientAgeConsistencyRuleTest {

    private final PatientAgeConsistencyRule rule = new PatientAgeConsistencyRule();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ClinicalRule config() throws Exception {
        return new ClinicalRule("PATIENT_AGE_CONSISTENCY", "Age Consistency", "desc",
                "DATA_QUALITY", "WARNING", true, objectMapper.readTree("{\"toleranceYears\": 0}"));
    }

    @Test
    void statedAgeMatchingDateOfBirthPasses() throws Exception {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, LocalDate.now().minusYears(40), 40,
                List.of(), List.of(), List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }

    @Test
    void statedAgeInconsistentWithDateOfBirthFails() throws Exception {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, LocalDate.now().minusYears(40), 25,
                List.of(), List.of(), List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isFalse();
        assertThat(results.get(0).entityType()).isEqualTo("PATIENT");
        assertThat(results.get(0).entityId()).isEqualTo(1L);
    }

    @Test
    void missingStatedAgeIsNotEvaluated() throws Exception {
        PatientRecordRequest request = new PatientRecordRequest(1L, null, LocalDate.now().minusYears(40), null,
                List.of(), List.of(), List.of(), List.of(), List.of());

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
    }
}
