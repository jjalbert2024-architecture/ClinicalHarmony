package com.clinicalharmony.patientindex.web;

import com.clinicalharmony.patientindex.client.GoldWriterRejectedException;
import com.clinicalharmony.patientindex.client.GoldWriterUnavailableException;
import com.clinicalharmony.patientindex.repository.MatchRun;
import com.clinicalharmony.patientindex.security.SecurityConfig;
import com.clinicalharmony.patientindex.security.SecurityErrorResponses;
import com.clinicalharmony.patientindex.service.ConflictException;
import com.clinicalharmony.patientindex.service.MatchingRunService;
import com.clinicalharmony.patientindex.service.NotFoundException;
import com.clinicalharmony.patientindex.service.PatientIndexQueryService;
import com.clinicalharmony.patientindex.service.ReviewService;
import com.clinicalharmony.patientindex.service.RunResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PatientIndexController.class)
@Import({SecurityConfig.class, SecurityErrorResponses.class})
class PatientIndexControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private MatchingRunService runService;
    @MockitoBean
    private ReviewService reviewService;
    @MockitoBean
    private PatientIndexQueryService queries;

    private static final RequestPostProcessor READER = jwt().jwt(j -> j.claim("azp", "dashboard"));
    private static final RequestPostProcessor WRITER = jwt().jwt(j -> j.claim("azp", "clinicalharmony-app"))
            .authorities(new SimpleGrantedAuthority("SCOPE_clinicalharmony/pipeline.write"));

    @Test
    void noTokenIs401WithChallengeAndCorrelationId() throws Exception {
        mockMvc.perform(get("/api/pmi/masters").header("X-Correlation-Id", "corr-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"clinicalharmony\""))
                .andExpect(header().string("X-Correlation-Id", "corr-1"))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
        verifyNoInteractions(queries);
    }

    @Test
    void anyValidTokenCanReadButWritesNeedPipelineWrite() throws Exception {
        when(queries.masters(null, 50)).thenReturn(List.of());
        mockMvc.perform(get("/api/pmi/masters").with(READER)).andExpect(status().isOk());

        mockMvc.perform(post("/api/pmi/match/run").with(READER))
                .andExpect(status().isForbidden())
                .andExpect(header().string("WWW-Authenticate", containsString("insufficient_scope")))
                .andExpect(jsonPath("$.message", containsString("clinicalharmony/pipeline.write")));
        verifyNoInteractions(runService);
    }

    @Test
    void runRecordsWhichClientTriggeredIt() throws Exception {
        when(runService.run("api:clinicalharmony-app")).thenReturn(new RunResult(
                new MatchRun(3, "COMPLETED", "api:clinicalharmony-app", null, null, 9, 12, 3, 2, 2, null),
                List.of(), List.of(), List.of(), List.of(), 0));

        mockMvc.perform(post("/api/pmi/match/run").with(WRITER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.autoMerges").value(3))
                .andExpect(jsonPath("$.run.queuedForReview").value(2));
    }

    @Test
    void invalidInputIs400() throws Exception {
        mockMvc.perform(post("/api/pmi/candidates/5/accept").with(WRITER).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.fields.reviewer").exists());
        mockMvc.perform(post("/api/pmi/merges/5/unmerge").with(WRITER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewer\": \"dana\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.reason").exists());
        mockMvc.perform(get("/api/pmi/masters?status=BOGUS").with(READER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid request"));
        mockMvc.perform(get("/api/pmi/score?a=x&b=2").with(READER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("'a'")));
        mockMvc.perform(post("/api/pmi/merge").with(WRITER).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed request body"));
    }

    @Test
    void domainErrorsMapToConsistentStatuses() throws Exception {
        when(queries.master(99)).thenThrow(new NotFoundException("No master patient with id 99"));
        mockMvc.perform(get("/api/pmi/masters/99").with(READER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not found"))
                .andExpect(jsonPath("$.message").value("No master patient with id 99"));

        when(reviewService.reject(eq(5L), anyString(), anyString())).thenThrow(new ConflictException("Candidate 5 is REJECTED, not PENDING"));
        mockMvc.perform(post("/api/pmi/candidates/5/reject").with(WRITER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewer\": \"dana\", \"note\": \"twins\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"));

        when(reviewService.accept(eq(6L), anyString(), anyString())).thenThrow(new GoldWriterRejectedException(409, "Master 3 is MERGED"));
        mockMvc.perform(post("/api/pmi/candidates/6/accept").with(WRITER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewer\": \"dana\", \"note\": \"ok\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("GoldWriter refused the change: Master 3 is MERGED"));

        when(reviewService.unmerge(anyLong(), anyString(), anyString())).thenThrow(new GoldWriterUnavailableException("GoldWriter unreachable", null));
        mockMvc.perform(post("/api/pmi/merges/7/unmerge").with(WRITER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewer\": \"dana\", \"reason\": \"twins\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("GoldWriter unavailable"));
    }

    @Test
    void candidateListDefaultsToPendingAndAcceptsAll() throws Exception {
        when(queries.candidates("PENDING", 100)).thenReturn(List.of());
        when(queries.candidates(null, 100)).thenReturn(List.of());

        mockMvc.perform(get("/api/pmi/candidates").with(READER)).andExpect(status().isOk());
        mockMvc.perform(get("/api/pmi/candidates?status=ALL").with(READER)).andExpect(status().isOk());
        verify(queries).candidates("PENDING", 100);
        verify(queries).candidates(null, 100);
    }
}
