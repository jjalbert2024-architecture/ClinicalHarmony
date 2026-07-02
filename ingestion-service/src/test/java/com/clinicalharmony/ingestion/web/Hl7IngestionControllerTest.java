package com.clinicalharmony.ingestion.web;

import com.clinicalharmony.ingestion.service.Hl7MessageProcessor;
import com.clinicalharmony.ingestion.service.Hl7ProcessingResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(Hl7IngestionController.class)
class Hl7IngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private Hl7MessageProcessor processor;

    @Test
    void returns201WhenMessageAccepted() throws Exception {
        when(processor.process(anyString()))
                .thenReturn(new Hl7ProcessingResult(true, 55L, "ADT^A01", "MSH|...ACK...\rMSA|AA|MSG00001\r"));

        mockMvc.perform(post("/api/ingestion/hl7")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("MSH|^~\\&|A|B|C|D|20260702||ADT^A01|1|P|2.5\r"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.rawMessageId").value(55))
                .andExpect(jsonPath("$.messageType").value("ADT^A01"));
    }

    @Test
    void returns422WhenMessageQuarantined() throws Exception {
        when(processor.process(anyString()))
                .thenReturn(new Hl7ProcessingResult(false, 56L, null, "MSH|...NAK...\rMSA|AE|UNKNOWN|bad message\r"));

        mockMvc.perform(post("/api/ingestion/hl7")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("NOT HL7"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rawMessageId").value(56));
    }
}
