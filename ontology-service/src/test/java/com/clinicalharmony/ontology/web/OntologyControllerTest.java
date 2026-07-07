package com.clinicalharmony.ontology.web;

import com.clinicalharmony.ontology.service.ConceptMapping;
import com.clinicalharmony.ontology.service.ConceptMappingService;
import com.clinicalharmony.ontology.service.ItemValidation;
import com.clinicalharmony.ontology.service.OntologyService;
import com.clinicalharmony.ontology.service.OntologyValidationResult;
import com.clinicalharmony.ontology.service.ValidationReportBuilder;
import com.clinicalharmony.ontology.validator.Icd10Validator;
import com.clinicalharmony.ontology.validator.LoincValidator;
import com.clinicalharmony.ontology.validator.ValidationOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OntologyController.class)
class OntologyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OntologyService ontologyService;
    @MockBean
    private ValidationReportBuilder validationReportBuilder;
    @MockBean
    private Icd10Validator icd10Validator;
    @MockBean
    private LoincValidator loincValidator;
    @MockBean
    private ConceptMappingService conceptMappingService;

    @Test
    void validateReturnsAggregatedResult() throws Exception {
        ItemValidation conditionResult = new ItemValidation(101L, "CONDITION", "ICD-10",
                ValidationOutcome.valid("E11.9", "Diabetes", "Endocrine"), "CODE-001");
        OntologyValidationResult result = new OntologyValidationResult(1L, List.of(conditionResult), List.of(), List.of());
        when(ontologyService.validate(any())).thenReturn(result);
        when(validationReportBuilder.buildAndPersist(any())).thenReturn(List.of());

        mockMvc.perform(post("/api/ontology/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientId": 1, "conditions": [{"id": 101, "codeSystem": "ICD-10", "code": "E11.9"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientId").value(1))
                .andExpect(jsonPath("$.allValid").value(true));
    }

    @Test
    void validateRejectsMissingPatientId() throws Exception {
        mockMvc.perform(post("/api/ontology/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"));
    }

    @Test
    void icd10LookupReturns200WhenValid() throws Exception {
        when(icd10Validator.validate(anyString())).thenReturn(ValidationOutcome.valid("E11.9", "Diabetes", "Endocrine"));

        mockMvc.perform(get("/api/ontology/codes/icd10/E11.9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void icd10LookupReturns404WhenNotFound() throws Exception {
        when(icd10Validator.validate(anyString())).thenReturn(ValidationOutcome.invalid("Z99.99", "Code not found in ICD-10 reference set"));

        mockMvc.perform(get("/api/ontology/codes/icd10/Z99.99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void loincLookupReturns200WhenValid() throws Exception {
        when(loincValidator.validate(anyString())).thenReturn(ValidationOutcome.valid("2093-3", "Cholesterol", "Qn"));

        mockMvc.perform(get("/api/ontology/codes/loinc/2093-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display").value("Cholesterol"));
    }

    @Test
    void mapReturnsMappingList() throws Exception {
        when(conceptMappingService.map("SNOMED", "44054006", "ICD10"))
                .thenReturn(List.of(new ConceptMapping("SNOMED", "44054006", "ICD-10", "E11.9", "EQUIVALENT", null)));

        mockMvc.perform(get("/api/ontology/map").param("source", "SNOMED").param("code", "44054006").param("target", "ICD10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].targetCode").value("E11.9"));
    }
}
