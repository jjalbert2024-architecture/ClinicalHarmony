package com.clinicalharmony.rulesengine.web;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.ClinicalValidationReport;
import com.clinicalharmony.rulesengine.service.RuleLoader;
import com.clinicalharmony.rulesengine.service.RulesOrchestrationService;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RulesController.class)
class RulesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RulesOrchestrationService rulesOrchestrationService;
    @MockBean
    private RuleLoader ruleLoader;

    private static final ClinicalRule SAMPLE_RULE = new ClinicalRule(
            "PATIENT_AGE_CONSISTENCY", "Age Consistency", "desc", "DATA_QUALITY", "WARNING", true, NullNode.getInstance());

    @Test
    void evaluateReturnsAggregatedReport() throws Exception {
        ClinicalValidationReport report = new ClinicalValidationReport(1L, true, true, null, List.of(), List.of());
        when(rulesOrchestrationService.evaluate(any())).thenReturn(report);

        mockMvc.perform(post("/api/rules/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value(1))
                .andExpect(jsonPath("$.allValid").value(true));
    }

    @Test
    void evaluateRejectsMissingPatientId() throws Exception {
        mockMvc.perform(post("/api/rules/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    void listActiveRulesReturnsRuleList() throws Exception {
        when(ruleLoader.loadActiveRules()).thenReturn(List.of(SAMPLE_RULE));

        mockMvc.perform(get("/api/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ruleCode").value("PATIENT_AGE_CONSISTENCY"));
    }

    @Test
    void getRuleReturns200WhenFound() throws Exception {
        when(ruleLoader.loadByCode("PATIENT_AGE_CONSISTENCY")).thenReturn(Optional.of(SAMPLE_RULE));

        mockMvc.perform(get("/api/rules/PATIENT_AGE_CONSISTENCY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleName").value("Age Consistency"));
    }

    @Test
    void getRuleReturns404WhenNotFound() throws Exception {
        when(ruleLoader.loadByCode(eq("UNKNOWN"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/rules/UNKNOWN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void toggleReturns200WhenFound() throws Exception {
        when(ruleLoader.toggle("PATIENT_AGE_CONSISTENCY")).thenReturn(Optional.of(SAMPLE_RULE));

        mockMvc.perform(put("/api/rules/PATIENT_AGE_CONSISTENCY/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value("PATIENT_AGE_CONSISTENCY"));
    }

    @Test
    void toggleReturns404WhenNotFound() throws Exception {
        when(ruleLoader.toggle(eq("UNKNOWN"))).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/rules/UNKNOWN/toggle"))
                .andExpect(status().isNotFound());
    }
}
