package com.clinicalharmony.ingestion.web;

import com.clinicalharmony.ingestion.service.FhirMessageProcessor;
import com.clinicalharmony.ingestion.service.FhirProcessingResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FhirIngestionController.class)
class FhirIngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FhirMessageProcessor processor;

    @Test
    void returns201AndPassesSourceSystemHeader() throws Exception {
        when(processor.process(anyString(), eq("PARTNER_CLINIC")))
                .thenReturn(new FhirProcessingResult(true, 30L, "Bundle", 5));

        mockMvc.perform(post("/api/ingestion/fhir")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Source-System", "PARTNER_CLINIC")
                        .content("{\"resourceType\":\"Bundle\",\"entry\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.rawMessageId").value(30))
                .andExpect(jsonPath("$.resourceType").value("Bundle"))
                .andExpect(jsonPath("$.resourceCount").value(5));
    }

    @Test
    void returns422WhenQuarantinedAndHeaderOmitted() throws Exception {
        when(processor.process(anyString(), isNull()))
                .thenReturn(new FhirProcessingResult(false, 31L, null, 0));

        mockMvc.perform(post("/api/ingestion/fhir")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notFhir\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rawMessageId").value(31));
    }
}
