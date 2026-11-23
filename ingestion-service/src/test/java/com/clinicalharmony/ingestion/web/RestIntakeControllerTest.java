package com.clinicalharmony.ingestion.web;

import com.clinicalharmony.ingestion.intake.IntakeFormat;
import com.clinicalharmony.ingestion.intake.RestIntakeResult;
import com.clinicalharmony.ingestion.intake.RestIntakeService;
import com.clinicalharmony.ingestion.kafka.PublishFailedException;
import com.clinicalharmony.ingestion.kafka.RawEventPublisher;
import com.clinicalharmony.ingestion.intake.FormatChecks;
import com.clinicalharmony.ingestion.kafka.Hl7RawPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RestIntakeController.class)
class RestIntakeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RestIntakeService intake;

    private static RestIntakeResult result(boolean accepted, String format) {
        return new RestIntakeResult(accepted, format, "PARTNER", "rest-0123456789ab", "e1", "t", 0, 5L, "abc",
                "2026-11-19T10:00:00Z", Map.of("resourceType", "Patient"), accepted ? null : "bad", "m");
    }

    @Test
    void validFhirIs202WithCorrelationHeaderAndPassesSourceAndCorrelation() throws Exception {
        when(intake.accept(eq(IntakeFormat.FHIR_JSON), any(), eq("PARTNER"), eq("ui-1")))
                .thenReturn(result(true, "FHIR_JSON"));

        mockMvc.perform(post("/api/ingestion/fhir").contentType("application/fhir+json")
                        .header("X-Source-System", "PARTNER").header("X-Correlation-Id", "ui-1")
                        .content("{\"resourceType\":\"Patient\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("X-Correlation-Id", "rest-0123456789ab"))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.eventId").value("e1"))
                .andExpect(jsonPath("$.details.resourceType").value("Patient"))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.rawMessageId").doesNotExist());
    }

    @Test
    void invalidIs422() throws Exception {
        when(intake.accept(eq(IntakeFormat.HL7V2), any(), isNull(), isNull())).thenReturn(result(false, "HL7V2"));
        mockMvc.perform(post("/api/ingestion/hl7").contentType(MediaType.TEXT_PLAIN).content("garbage"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.error").value("bad"));
    }

    @Test
    void claimsAcceptTextCsvAndCcdaAcceptsXml() throws Exception {
        when(intake.accept(eq(IntakeFormat.CLAIMS_CSV), any(), any(), any())).thenReturn(result(true, "CLAIMS_CSV"));
        when(intake.accept(eq(IntakeFormat.CCDA_XML), any(), any(), any())).thenReturn(result(true, "CCDA_XML"));
        mockMvc.perform(post("/api/ingestion/claims").contentType("text/csv").content("a,b\n1,2\n"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/ingestion/ccda").contentType(MediaType.APPLICATION_XML).content("<x/>"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/ingestion/ccda").contentType(MediaType.TEXT_XML).content("<x/>"))
                .andExpect(status().isAccepted());
    }

    @Test
    void kafkaDownIs503WithRetryAfter() throws Exception {
        // Real service with a failing publisher, so the exception is the one the service really throws.
        RawEventPublisher publisher = org.mockito.Mockito.mock(RawEventPublisher.class);
        when(publisher.publish(any(), any(), any(), anyMap(), any()))
                .thenThrow(new PublishFailedException("Kafka unavailable: timeout", null));
        RestIntakeService real = new RestIntakeService(new FormatChecks(new ObjectMapper()), publisher,
                org.mockito.Mockito.mock(Hl7RawPublisher.class), new SimpleMeterRegistry(), "H", "F", "C", "D");
        when(intake.accept(any(), any(), any(), any()))
                .thenAnswer(inv -> real.accept(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));

        mockMvc.perform(post("/api/ingestion/fhir").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "ui-2").content("{\"resourceType\":\"Patient\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(header().string("X-Correlation-Id", "ui-2"))
                .andExpect(jsonPath("$.error").value("kafka_unavailable"))
                .andExpect(jsonPath("$.correlationId").value("ui-2"))
                .andExpect(jsonPath("$.message").value("Not stored, retry later: Kafka unavailable: timeout"));
    }

    @Test
    void wrongContentTypeIs415() throws Exception {
        mockMvc.perform(post("/api/ingestion/ccda").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
