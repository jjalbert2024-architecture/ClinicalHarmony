package com.clinicalharmony.fhircompliance.web;

import com.clinicalharmony.fhircompliance.gold.GoldMasterConflictException;
import com.clinicalharmony.fhircompliance.gold.GoldMasterNotFoundException;
import com.clinicalharmony.fhircompliance.gold.GoldWriter;
import com.clinicalharmony.fhircompliance.gold.MasterMergeCommand;
import com.clinicalharmony.fhircompliance.gold.MasterMergeResult;
import com.clinicalharmony.fhircompliance.repository.MasterPatientSnapshot;
import com.clinicalharmony.fhircompliance.security.WebSecurityTestConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GoldMasterController.class)
@Import(WebSecurityTestConfig.class)
class GoldMasterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GoldWriter goldWriter;

    private static final MasterPatientSnapshot SURVIVOR =
            new MasterPatientSnapshot(8, UUID.randomUUID(), "MATCHED", true, new BigDecimal("0.9118"), 2);
    private static final MasterPatientSnapshot ABSORBED =
            new MasterPatientSnapshot(3, UUID.randomUUID(), "MERGED", false, null, 0);

    @Test
    void mergeReturns201AndAuditsReviewerPlusCallingClient() throws Exception {
        when(goldWriter.mergeMasters(any())).thenReturn(new MasterMergeResult(40, SURVIVOR, ABSORBED, List.of(31L), List.of(501L)));

        mockMvc.perform(post("/api/fhir/gold/masters/merge")
                        .with(jwt().jwt(j -> j.claim("azp", "patient-index-service"))
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        "SCOPE_clinicalharmony/pipeline.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"survivorMasterId": 8, "absorbedMasterId": 3, "matchConfidenceScore": 0.9118,
                                 "matchMethod": "PROBABILISTIC", "decidedBy": "pmi-run-1",
                                 "evidence": {"silverPatientIds": [1, 31]}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mergeId").value(40))
                .andExpect(jsonPath("$.survivor.matchStatus").value("MATCHED"))
                .andExpect(jsonPath("$.absorbed.active").value(false))
                .andExpect(jsonPath("$.movedSilverPatientIds[0]").value(31));

        ArgumentCaptor<MasterMergeCommand> captor = ArgumentCaptor.forClass(MasterMergeCommand.class);
        verify(goldWriter).mergeMasters(captor.capture());
        assertThat(captor.getValue().decidedBy()).isEqualTo("pmi-run-1 via patient-index-service");
        assertThat(captor.getValue().evidenceJson()).isEqualTo("{\"silverPatientIds\":[1,31]}");
    }

    @Test
    void mergeValidatesBody() throws Exception {
        mockMvc.perform(post("/api/fhir/gold/masters/merge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"survivorMasterId\": 8, \"matchConfidenceScore\": 1.5, \"matchMethod\": \"GUESS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.fields.absorbedMasterId").exists())
                .andExpect(jsonPath("$.fields.matchConfidenceScore").exists())
                .andExpect(jsonPath("$.fields.matchMethod").exists())
                .andExpect(jsonPath("$.fields.decidedBy").exists());
        verifyNoInteractions(goldWriter);
    }

    @Test
    void conflictsAre409AndUnknownIdsAre404() throws Exception {
        when(goldWriter.confirmMaster(anyLong(), anyString())).thenThrow(new GoldMasterConflictException("Master 3 is MERGED"));
        mockMvc.perform(post("/api/fhir/gold/masters/3/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decidedBy\": \"pmi-run-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Master 3 is MERGED"));

        when(goldWriter.unmergeMasters(anyLong(), anyString(), any())).thenThrow(new GoldMasterNotFoundException("No merge 9"));
        mockMvc.perform(post("/api/fhir/gold/masters/merges/9/unmerge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decidedBy\": \"reviewer:dana\", \"reason\": \"twins\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not found"));
    }

    @Test
    void readOnlyTokenIsForbidden() throws Exception {
        mockMvc.perform(post("/api/fhir/gold/masters/3/confirm")
                        .with(jwt().authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "SCOPE_system/*.rs")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decidedBy\": \"x\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(goldWriter);
    }

    @Test
    void auditNameFallsBackAndTruncates() {
        assertThat(GoldMasterController.auditName("x", null)).isEqualTo("x");
        assertThat(GoldMasterController.auditName("y".repeat(60),
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "none")
                        .subject("svc-" + "z".repeat(60)).build())).hasSize(100);
    }
}
