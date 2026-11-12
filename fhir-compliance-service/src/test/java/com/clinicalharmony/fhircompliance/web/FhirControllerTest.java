package com.clinicalharmony.fhircompliance.web;

import com.clinicalharmony.fhircompliance.gold.GoldRevalidationService;
import com.clinicalharmony.fhircompliance.service.silver.BuiltResourceSummary;
import com.clinicalharmony.fhircompliance.service.silver.SilverFhirBuildService;
import com.clinicalharmony.fhircompliance.service.silver.SilverPatientBuildResult;
import com.clinicalharmony.fhircompliance.service.silver.SilverPatientNotFoundException;
import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.clinicalharmony.fhircompliance.validation.FhirViolation;
import com.clinicalharmony.fhircompliance.validation.InvalidFhirResourceException;
import com.clinicalharmony.fhircompliance.validation.UsCoreFhirValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FhirController.class)
class FhirControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UsCoreFhirValidator validator;

    @MockitoBean
    private SilverFhirBuildService buildService;

    @MockitoBean
    private GoldRevalidationService revalidationService;

    @Test
    void validateReturnsViolationsWith200EvenWhenNonCompliant() throws Exception {
        when(validator.validateJson(anyString())).thenReturn(new FhirValidationOutcome("Patient", null,
                "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient", false, 1, 0,
                List.of(new FhirViolation("ERROR", "Patient.gender", "Patient.gender: minimum required = 1",
                        "Validation_VAL_Profile_Minimum", "Add a 'gender' element"))));

        mockMvc.perform(post("/api/fhir/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceType\":\"Patient\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compliant").value(false))
                .andExpect(jsonPath("$.violations[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.violations[0].location").value("Patient.gender"))
                .andExpect(jsonPath("$.violations[0].rule").value("Validation_VAL_Profile_Minimum"))
                .andExpect(jsonPath("$.violations[0].suggestedFix").value("Add a 'gender' element"));
    }

    @Test
    void validateAcceptsFhirJsonContentType() throws Exception {
        when(validator.validateJson(anyString())).thenReturn(
                new FhirValidationOutcome("Patient", "p1", "x", true, 0, 0, List.of()));

        mockMvc.perform(post("/api/fhir/validate").contentType("application/fhir+json")
                        .content("{\"resourceType\":\"Patient\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compliant").value(true));
    }

    @Test
    void validateReturns400ForNonFhirPayload() throws Exception {
        when(validator.validateJson(anyString())).thenThrow(
                new InvalidFhirResourceException("Missing 'resourceType' — not a FHIR resource", null));

        mockMvc.perform(post("/api/fhir/validate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid FHIR resource"))
                .andExpect(jsonPath("$.message").value("Missing 'resourceType' — not a FHIR resource"));
    }

    @Test
    void validateReturns415ForWrongContentType() throws Exception {
        mockMvc.perform(post("/api/fhir/validate").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void responseEchoesCallerCorrelationId() throws Exception {
        when(validator.validateJson(anyString())).thenReturn(
                new FhirValidationOutcome("Patient", "p1", "x", true, 0, 0, List.of()));

        mockMvc.perform(post("/api/fhir/validate").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "trace-abc-123").content("{\"resourceType\":\"Patient\"}"))
                .andExpect(header().string("X-Correlation-Id", "trace-abc-123"));
    }

    @Test
    void buildPatientReturnsSummary() throws Exception {
        when(buildService.buildPatient(1L)).thenReturn(new SilverPatientBuildResult(1L, "silver-patient-1", 7L, 1, 1,
                List.of(new BuiltResourceSummary("Patient", "silver-patient-1", 100L, true, 0, 0, List.of()))));

        mockMvc.perform(post("/api/fhir/build/patients/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patientFhirId").value("silver-patient-1"))
                .andExpect(jsonPath("$.masterPatientId").value(7))
                .andExpect(jsonPath("$.resources[0].goldId").value(100));
    }

    @Test
    void buildPatientReturns404ForUnknownSilverPatient() throws Exception {
        when(buildService.buildPatient(99L)).thenThrow(new SilverPatientNotFoundException(99L));

        mockMvc.perform(post("/api/fhir/build/patients/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No silver.patients row with id 99"));
    }

    @Test
    void buildAllReturnsOneResultPerPatient() throws Exception {
        when(buildService.buildAllPatients()).thenReturn(List.of(
                new SilverPatientBuildResult(1L, "silver-patient-1", 7L, 0, 0, List.of()),
                new SilverPatientBuildResult(2L, "silver-patient-2", 8L, 0, 0, List.of())));

        mockMvc.perform(post("/api/fhir/build"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void revalidateGoldReturnsOneSummaryPerPendingRow() throws Exception {
        when(revalidationService.revalidatePending()).thenReturn(List.of(
                new BuiltResourceSummary("Coverage", "cms-coverage-seed-0001", 30L, true, 0, 3, List.of()),
                new BuiltResourceSummary("PractitionerRole", "cms-practitionerrole-2", 31L, false, 1, 3,
                        List.of(new FhirViolation("ERROR", "PractitionerRole", "pd-1 failed", "pd-1", null)))));

        mockMvc.perform(post("/api/fhir/gold/revalidate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].usCoreCompliant").value(false))
                .andExpect(jsonPath("$[1].errors[0].rule").value("pd-1"));
    }
}
