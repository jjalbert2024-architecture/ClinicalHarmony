package com.clinicalharmony.fhircompliance.web;

import com.clinicalharmony.fhircompliance.service.ccda.CcdaConversionResult;
import com.clinicalharmony.fhircompliance.service.ccda.CcdaConversionService;
import com.clinicalharmony.fhircompliance.service.ccda.CcdaDocumentTooLargeException;
import com.clinicalharmony.fhircompliance.service.ccda.ConversionNote;
import com.clinicalharmony.fhircompliance.service.ccda.ConvertedResource;
import com.clinicalharmony.fhircompliance.service.ccda.InvalidCcdaDocumentException;
import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CcdaConversionController.class)
class CcdaConversionControllerTest {

    private static final String URL = "/api/fhir/convert/ccda";
    private static final String CCDA = "<ClinicalDocument xmlns=\"urn:hl7-org:v3\"/>";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CcdaConversionService conversionService;

    private static CcdaConversionResult result(boolean compliant) throws Exception {
        FhirValidationOutcome outcome = new FhirValidationOutcome("Patient", "p1",
                "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient", compliant, compliant ? 0 : 1, 0, List.of());
        return new CcdaConversionResult("1.2.3^DOC", "Test CCD", 1, compliant ? 1 : 0, compliant,
                List.of("Problems (11450-4): not present"),
                List.of(new ConversionNote("Problems (11450-4)", "Section not present in document")),
                List.of(new ConvertedResource("urn:uuid:p1", "Patient", "p1", "Patient (recordTarget)", outcome)),
                new ObjectMapper().readTree("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}"));
    }

    @Test
    void convertsApplicationXml() throws Exception {
        when(conversionService.convert(any())).thenReturn(result(true));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_XML).content(CCDA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allCompliant").value(true))
                .andExpect(jsonPath("$.resources[0].fullUrl").value("urn:uuid:p1"))
                .andExpect(jsonPath("$.resources[0].validation.compliant").value(true))
                .andExpect(jsonPath("$.notes[0].message").value("Section not present in document"))
                .andExpect(jsonPath("$.bundle.resourceType").value("Bundle"));
    }

    @Test
    void acceptsTextXmlAndReturns200EvenWhenNonCompliant() throws Exception {
        when(conversionService.convert(any())).thenReturn(result(false));

        mockMvc.perform(post(URL).contentType(MediaType.TEXT_XML).content(CCDA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allCompliant").value(false))
                .andExpect(jsonPath("$.resources[0].validation.errorCount").value(1));
    }

    @Test
    void malformedXmlIs400() throws Exception {
        when(conversionService.convert(any())).thenThrow(
                new InvalidCcdaDocumentException("Not well-formed XML (line 1, column 9): oops", null));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_XML).content("<broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid C-CDA document"))
                .andExpect(jsonPath("$.message").value("Not well-formed XML (line 1, column 9): oops"));
    }

    @Test
    void oversizedDocumentIs413() throws Exception {
        when(conversionService.convert(any())).thenThrow(new CcdaDocumentTooLargeException(10, 5));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_XML).content(CCDA))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("Document too large"));
    }

    @Test
    void jsonContentTypeIs415() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void emptyBodyIs400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_XML))
                .andExpect(status().isBadRequest());
    }

    @Test
    void responseEchoesCallerCorrelationId() throws Exception {
        when(conversionService.convert(any())).thenReturn(result(true));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_XML)
                        .header("X-Correlation-Id", "ccda-trace-1").content(CCDA))
                .andExpect(header().string("X-Correlation-Id", "ccda-trace-1"));
    }
}
