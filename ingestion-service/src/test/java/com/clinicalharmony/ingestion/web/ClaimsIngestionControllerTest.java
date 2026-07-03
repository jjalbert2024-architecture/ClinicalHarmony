package com.clinicalharmony.ingestion.web;

import com.clinicalharmony.ingestion.service.ClaimsMessageProcessor;
import com.clinicalharmony.ingestion.service.ClaimsProcessingResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClaimsIngestionController.class)
class ClaimsIngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ClaimsMessageProcessor processor;

    @Test
    void returns201WithPerRowIds() throws Exception {
        when(processor.process(anyString(), eq("CLAIMS_VENDOR_A")))
                .thenReturn(new ClaimsProcessingResult(true, 2, List.of(40L, 41L), null));

        mockMvc.perform(post("/api/ingestion/claims")
                        .contentType(MediaType.TEXT_PLAIN)
                        .header("X-Source-System", "CLAIMS_VENDOR_A")
                        .content("claim_id,member_id\nCLM-1,MBR-1\nCLM-2,MBR-2\n"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.rowsLanded").value(2))
                .andExpect(jsonPath("$.rawMessageIds[0]").value(40))
                .andExpect(jsonPath("$.rawMessageIds[1]").value(41));
    }

    @Test
    void returns422WhenFileQuarantined() throws Exception {
        when(processor.process(anyString(), eq("CLAIMS_VENDOR_A")))
                .thenReturn(new ClaimsProcessingResult(false, 0, List.of(42L), "CSV contains a header but no claim rows"));

        mockMvc.perform(post("/api/ingestion/claims")
                        .contentType(MediaType.TEXT_PLAIN)
                        .header("X-Source-System", "CLAIMS_VENDOR_A")
                        .content("claim_id,member_id\n"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.errorMessage").value("CSV contains a header but no claim rows"));
    }
}
