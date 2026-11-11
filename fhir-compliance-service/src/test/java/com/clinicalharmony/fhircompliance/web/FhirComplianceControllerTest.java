package com.clinicalharmony.fhircompliance.web;

import com.clinicalharmony.fhircompliance.service.ClinicalRule;
import com.clinicalharmony.fhircompliance.service.FhirGenerationService;
import com.clinicalharmony.fhircompliance.service.FhirValidationReport;
import com.clinicalharmony.fhircompliance.service.UsCoreRuleLoader;
import com.clinicalharmony.fhircompliance.service.UsCoreValidationService;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FhirComplianceController.class)
class FhirComplianceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UsCoreValidationService validationService;
    @MockBean
    private FhirGenerationService generationService;
    @MockBean
    private UsCoreRuleLoader ruleLoader;

    private static final ClinicalRule SAMPLE_RULE = new ClinicalRule(
            "USCORE_PATIENT_COMPLETENESS", "Patient Completeness", "desc", "DATA_QUALITY", "WARNING", true, NullNode.getInstance());

    @Test
    void validateReturnsAggregatedReport() throws Exception {
        FhirValidationReport report = new FhirValidationReport(1L, true, List.of(), List.of());
        when(validationService.validate(any())).thenReturn(report);

        mockMvc.perform(post("/api/fhir-compliance/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\": 1, \"fhirResources\": [{\"resourceType\": \"Patient\", \"id\": \"p1\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value(1))
                .andExpect(jsonPath("$.allValid").value(true));
    }

    @Test
    void validateRejectsMissingPatientId() throws Exception {
        mockMvc.perform(post("/api/fhir-compliance/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fhirResources\": [{\"resourceType\": \"Patient\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    void validateRejectsEmptyFhirResources() throws Exception {
        mockMvc.perform(post("/api/fhir-compliance/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\": 1, \"fhirResources\": []}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generateReturnsBundleFromGenerationService() throws Exception {
        Map<String, Object> bundle = Map.of("resourceType", "Bundle", "type", "collection", "entry", List.of());
        when(generationService.generateAndPersist(any())).thenReturn(bundle);

        mockMvc.perform(post("/api/fhir-compliance/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\": 777}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceType").value("Bundle"));
    }

    @Test
    void listActiveRulesReturnsRuleList() throws Exception {
        when(ruleLoader.loadActiveRules()).thenReturn(List.of(SAMPLE_RULE));

        mockMvc.perform(get("/api/fhir-compliance/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ruleCode").value("USCORE_PATIENT_COMPLETENESS"));
    }

    @Test
    void getRuleReturns404WhenNotFound() throws Exception {
        when(ruleLoader.loadByCode(eq("UNKNOWN"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/fhir-compliance/rules/UNKNOWN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void toggleReturns200WhenFound() throws Exception {
        when(ruleLoader.toggle("USCORE_PATIENT_COMPLETENESS")).thenReturn(Optional.of(SAMPLE_RULE));

        mockMvc.perform(put("/api/fhir-compliance/rules/USCORE_PATIENT_COMPLETENESS/toggle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value("USCORE_PATIENT_COMPLETENESS"));
    }
}
