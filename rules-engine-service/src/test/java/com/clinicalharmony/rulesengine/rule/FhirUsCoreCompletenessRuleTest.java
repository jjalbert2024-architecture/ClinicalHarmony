package com.clinicalharmony.rulesengine.rule;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.RuleResult;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FhirUsCoreCompletenessRuleTest {

    private final FhirUsCoreCompletenessRule rule = new FhirUsCoreCompletenessRule();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ClinicalRule config() throws Exception {
        String json = """
                {"requiredFields": {"Patient": ["id", "name", "gender", "birthDate"]}}
                """;
        return new ClinicalRule("FHIR_USCORE_COMPLETENESS", "FHIR US Core Completeness", "desc",
                "DATA_QUALITY", "WARNING", true, objectMapper.readTree(json));
    }

    @Test
    void patientResourceWithAllRequiredFieldsPasses() throws Exception {
        Map<String, Object> resource = Map.of(
                "resourceType", "Patient", "id", "abc-123", "name", "Jane Doe",
                "gender", "female", "birthDate", "1980-01-01");
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(resource));

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(results.get(0).entityType()).isEqualTo("PATIENT");
    }

    @Test
    void patientResourceMissingBirthDateFails() throws Exception {
        Map<String, Object> resource = Map.of(
                "resourceType", "Patient", "id", "abc-123", "name", "Jane Doe", "gender", "female");
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(resource));

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isFalse();
        assertThat(results.get(0).failureReason()).contains("birthDate");
    }

    @Test
    void unconfiguredResourceTypeIsNotEvaluated() throws Exception {
        Map<String, Object> resource = Map.of("resourceType", "Encounter", "id", "enc-1");
        PatientRecordRequest request = new PatientRecordRequest(1L, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(resource));

        List<RuleResult> results = rule.evaluate(request, config());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).passed()).isTrue();
        assertThat(results.get(0).entityType()).isEqualTo("PATIENT");
    }
}
