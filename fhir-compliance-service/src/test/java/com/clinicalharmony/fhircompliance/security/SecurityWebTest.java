package com.clinicalharmony.fhircompliance.security;

import com.clinicalharmony.fhircompliance.cms0057.FhirApiException;
import com.clinicalharmony.fhircompliance.cms0057.GoldFhirReadService;
import com.clinicalharmony.fhircompliance.gold.GoldRevalidationService;
import com.clinicalharmony.fhircompliance.service.FhirGenerationService;
import com.clinicalharmony.fhircompliance.service.UsCoreRuleLoader;
import com.clinicalharmony.fhircompliance.service.UsCoreValidationService;
import com.clinicalharmony.fhircompliance.service.ccda.CcdaConversionService;
import com.clinicalharmony.fhircompliance.service.silver.SilverFhirBuildService;
import com.clinicalharmony.fhircompliance.validation.UsCoreFhirValidator;
import com.clinicalharmony.fhircompliance.web.CcdaConversionController;
import com.clinicalharmony.fhircompliance.web.FhirComplianceController;
import com.clinicalharmony.fhircompliance.web.FhirController;
import com.clinicalharmony.fhircompliance.web.FhirR4Controller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The access matrix over the real SecurityConfig and every controller (services mocked): what's
 * public, what needs a token, what needs which scope, and the 401/403 response shapes.
 */
@WebMvcTest(controllers = {FhirR4Controller.class, FhirController.class, FhirComplianceController.class,
        CcdaConversionController.class, SmartConfigurationController.class})
@Import({SecurityConfig.class, SecurityErrorResponses.class})
class SecurityWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private GoldFhirReadService readService;
    @MockitoBean private UsCoreFhirValidator validator;
    @MockitoBean private SilverFhirBuildService buildService;
    @MockitoBean private GoldRevalidationService revalidationService;
    @MockitoBean private UsCoreValidationService usCoreValidationService;
    @MockitoBean private FhirGenerationService generationService;
    @MockitoBean private UsCoreRuleLoader ruleLoader;
    @MockitoBean private CcdaConversionService conversionService;

    @BeforeEach
    void stubReadService() {
        when(readService.read(anyString(), anyString())).thenThrow(FhirApiException.notFound("Coverage", "x"));
        when(readService.search(anyString(), any(), anyString(), anyString())).thenReturn("{\"resourceType\":\"Bundle\"}");
    }

    private static JwtRequestPostProcessor scopes(String... scopes) {
        return jwt().jwt(j -> j.claim("scope", String.join(" ", scopes)));
    }

    /** Got past security: whatever the (mocked) controller did, it wasn't a 401/403. */
    private static ResultMatcher passedSecurity() {
        return result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    // ---- /fhir/r4: 401 / 403 as OperationOutcomes ----

    @Test
    void fhirWithoutTokenIs401OperationOutcome() throws Exception {
        mockMvc.perform(get("/fhir/r4/Coverage/cms-coverage-seed-0001").header("X-Correlation-Id", "sec-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"clinicalharmony\""))
                .andExpect(header().string("X-Correlation-Id", "sec-401"))
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"))
                .andExpect(jsonPath("$.issue[0].severity").value("error"))
                .andExpect(jsonPath("$.issue[0].code").value("login"));
    }

    @Test
    void fhirWithInvalidTokenIs401InvalidToken() throws Exception {
        when(jwtDecoder.decode("not-a-jwt")).thenThrow(new BadJwtException("Jwt expired at 2026-01-01T00:00:00Z"));
        mockMvc.perform(get("/fhir/r4/Coverage").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        org.hamcrest.Matchers.containsString("error=\"invalid_token\"")))
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"))
                .andExpect(jsonPath("$.issue[0].code").value("login"))
                .andExpect(jsonPath("$.issue[0].diagnostics").value(org.hamcrest.Matchers.containsString("Jwt expired")));
    }

    @Test
    void fhirSearchWithReadOnlyScopeIs403NamingTheMissingScope() throws Exception {
        mockMvc.perform(get("/fhir/r4/Coverage").with(scopes("system/Coverage.r")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
                .andExpect(header().string("WWW-Authenticate",
                        "Bearer realm=\"clinicalharmony\", error=\"insufficient_scope\", scope=\"system/Coverage.s\""))
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"))
                .andExpect(jsonPath("$.issue[0].code").value("forbidden"))
                .andExpect(jsonPath("$.issue[0].diagnostics").value(org.hamcrest.Matchers.containsString("system/Coverage.s")));
    }

    @Test
    void fhirReadWithOtherTypesScopeIs403() throws Exception {
        mockMvc.perform(get("/fhir/r4/Coverage/x").with(scopes("system/Device.rs")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.issue[0].diagnostics").value(org.hamcrest.Matchers.containsString("system/Coverage.r")));
    }

    @Test
    void fhirWithMatchingScopeReachesTheController() throws Exception {
        mockMvc.perform(get("/fhir/r4/Coverage").with(scopes("system/Coverage.rs")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/fhir/r4/Coverage/x").with(scopes("system/Coverage.read")))
                .andExpect(status().isNotFound()) // the mocked read service's 404, i.e. authorized
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"));
        mockMvc.perform(get("/fhir/r4/Device").with(scopes("system/*.rs")))
                .andExpect(status().isOk());
    }

    @Test
    void unsupportedFhirTypeNeedsOnlyAValidTokenAndStill404s() throws Exception {
        mockMvc.perform(get("/fhir/r4/Claim/1").with(scopes()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/fhir/r4/Claim/1"))
                .andExpect(status().isUnauthorized());
    }

    // ---- public endpoints ----

    @Test
    void smartDiscoveryIsPublicAtRootAndFhirBase() throws Exception {
        for (String path : new String[]{"/.well-known/smart-configuration", "/fhir/r4/.well-known/smart-configuration"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token_endpoint").value(
                            "http://localhost:8180/realms/clinicalharmony/protocol/openid-connect/token"));
        }
    }

    @Test
    void statelessValidatorsAndRuleReadsArePublic() throws Exception {
        mockMvc.perform(post("/api/fhir/validate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(passedSecurity());
        mockMvc.perform(post("/api/fhir-compliance/validate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(passedSecurity());
        mockMvc.perform(get("/api/fhir-compliance/rules")).andExpect(passedSecurity());
        mockMvc.perform(get("/api/fhir-compliance/rules/USCORE_PATIENT_COMPLETENESS")).andExpect(passedSecurity());
    }

    // ---- pipeline.write endpoints: plain JSON errors, not OperationOutcomes ----

    @Test
    void pipelineEndpointsWithoutTokenAre401PlainJson() throws Exception {
        mockMvc.perform(post("/api/fhir/build"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.resourceType").doesNotExist());
    }

    @Test
    void pipelineEndpointsNeedThePipelineWriteScope() throws Exception {
        var readOnly = scopes("system/*.rs");
        mockMvc.perform(post("/api/fhir/build").with(readOnly))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("clinicalharmony/pipeline.write")));
        mockMvc.perform(post("/api/fhir/build/patients/1").with(readOnly)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/fhir/gold/revalidate").with(readOnly)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/fhir-compliance/generate").with(readOnly)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/fhir-compliance/rules/USCORE_PATIENT_COMPLETENESS/toggle").with(readOnly))
                .andExpect(status().isForbidden());

        var writer = scopes(SmartScopes.PIPELINE_WRITE);
        mockMvc.perform(post("/api/fhir/build").with(writer)).andExpect(passedSecurity());
        mockMvc.perform(post("/api/fhir/gold/revalidate").with(writer)).andExpect(passedSecurity());
        mockMvc.perform(put("/api/fhir-compliance/rules/USCORE_PATIENT_COMPLETENESS/toggle").with(writer))
                .andExpect(passedSecurity());
    }

    @Test
    void ccdaConversionNeedsAnyValidToken() throws Exception {
        mockMvc.perform(post("/api/fhir/convert/ccda").contentType(MediaType.APPLICATION_XML).content("<x/>"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/fhir/convert/ccda").with(scopes()).contentType(MediaType.APPLICATION_XML).content("<x/>"))
                .andExpect(passedSecurity());
    }
}
