package com.clinicalharmony.rulesengine.service;

import com.clinicalharmony.rulesengine.service.ontology.OntologyServiceClient;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RulesOrchestrationServiceTest {

    private OntologyServiceClient ontologyServiceClient;
    private RuleExecutor ruleExecutor;
    private RuleFindingBuilder ruleFindingBuilder;
    private RulesOrchestrationService orchestrationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final PatientRecordRequest REQUEST =
            new PatientRecordRequest(1L, "female", null, null, List.of(), List.of(), List.of(), List.of(), List.of());

    @BeforeEach
    void setUp() {
        ontologyServiceClient = mock(OntologyServiceClient.class);
        ruleExecutor = mock(RuleExecutor.class);
        ruleFindingBuilder = mock(RuleFindingBuilder.class);
        orchestrationService = new RulesOrchestrationService(ontologyServiceClient, ruleExecutor, ruleFindingBuilder);
    }

    @Test
    void allValidWhenOntologyAndRulesBothPass() throws Exception {
        JsonNode ontologyResponse = objectMapper.readTree("{\"allValid\": true}");
        when(ontologyServiceClient.validate(any())).thenReturn(ontologyResponse);
        RuleResult pass = RuleResult.pass("RULE_A", "ERROR", "PATIENT", 1L, 1L);
        when(ruleExecutor.evaluate(REQUEST)).thenReturn(List.of(pass));
        when(ruleFindingBuilder.buildAndPersist(List.of(pass))).thenReturn(List.of());

        ClinicalValidationReport report = orchestrationService.evaluate(REQUEST);

        assertThat(report.allValid()).isTrue();
        assertThat(report.ontologyValidationAvailable()).isTrue();
    }

    @Test
    void notAllValidWhenARuleFailsEvenIfOntologyPasses() throws Exception {
        JsonNode ontologyResponse = objectMapper.readTree("{\"allValid\": true}");
        when(ontologyServiceClient.validate(any())).thenReturn(ontologyResponse);
        RuleResult fail = RuleResult.fail("RULE_A", "ERROR", "PATIENT", 1L, 1L, "field", "bad", "X", null);
        when(ruleExecutor.evaluate(REQUEST)).thenReturn(List.of(fail));
        when(ruleFindingBuilder.buildAndPersist(List.of(fail))).thenReturn(List.of(500L));

        ClinicalValidationReport report = orchestrationService.evaluate(REQUEST);

        assertThat(report.allValid()).isFalse();
        assertThat(report.validationReportIds()).containsExactly(500L);
    }

    @Test
    void degradesGracefullyWhenOntologyServiceIsUnreachable() {
        when(ontologyServiceClient.validate(any())).thenThrow(new ResourceAccessException("connection refused"));
        RuleResult pass = RuleResult.pass("RULE_A", "ERROR", "PATIENT", 1L, 1L);
        when(ruleExecutor.evaluate(REQUEST)).thenReturn(List.of(pass));
        when(ruleFindingBuilder.buildAndPersist(List.of(pass))).thenReturn(List.of());

        ClinicalValidationReport report = orchestrationService.evaluate(REQUEST);

        assertThat(report.ontologyValidationAvailable()).isFalse();
        assertThat(report.ontologyValidation()).isNull();
        assertThat(report.allValid()).isTrue();
        assertThat(report.ruleResults()).containsExactly(pass);
    }
}
