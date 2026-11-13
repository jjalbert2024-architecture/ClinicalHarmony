package com.clinicalharmony.gateway.web;

import com.clinicalharmony.gateway.config.CorsConfig;
import com.clinicalharmony.gateway.config.GatewayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.clinicalharmony.gateway.downstream.Downstream;
import com.clinicalharmony.gateway.downstream.DownstreamClient;
import com.clinicalharmony.gateway.downstream.DownstreamException;
import com.clinicalharmony.gateway.downstream.ServiceHealthService;
import com.clinicalharmony.gateway.read.FhirQueries;
import com.clinicalharmony.gateway.read.LineageQueries;
import com.clinicalharmony.gateway.read.NotFoundException;
import com.clinicalharmony.gateway.read.OntologyQueries;
import com.clinicalharmony.gateway.read.Page;
import com.clinicalharmony.gateway.read.PatientQueries;
import com.clinicalharmony.gateway.read.SummaryQueries;
import com.clinicalharmony.gateway.read.ValidationQueries;
import com.clinicalharmony.gateway.security.SecurityConfig;
import com.clinicalharmony.gateway.security.SecurityProblemResponses;
import com.clinicalharmony.gateway.support.TestJwts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web layer with the REAL security chain (self-signed user JWTs through production's
 * validators and role mapping), CORS, correlation ids and the problem+json error shape. Read models
 * and the downstream client are mocks.
 */
@WebMvcTest
@EnableConfigurationProperties(GatewayProperties.class)
@Import({SecurityConfig.class, SecurityProblemResponses.class, CorsConfig.class, TestJwts.class})
class GatewayWebTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PatientQueries patients;
    @MockitoBean
    private ValidationQueries validation;
    @MockitoBean
    private OntologyQueries ontology;
    @MockitoBean
    private FhirQueries fhir;
    @MockitoBean
    private LineageQueries lineage;
    @MockitoBean
    private SummaryQueries summary;
    @MockitoBean
    private DownstreamClient downstream;
    @MockitoBean
    private ServiceHealthService health;

    private final ObjectMapper json = new ObjectMapper();

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    // ---- public + authentication ---------------------------------------------------------------

    @Test
    void configIsPublicAndNamesThePkceClient() throws Exception {
        mvc.perform(get("/api/v1/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auth.clientId").value("clinicalharmony-dashboard"))
                .andExpect(jsonPath("$.auth.pkce").value("S256"))
                .andExpect(jsonPath("$.auth.issuer").value(TestJwts.ISSUER));
    }

    @Test
    void noTokenIs401ProblemWithChallengeAndCorrelationId() throws Exception {
        mvc.perform(get("/api/v1/summary").header("X-Correlation-Id", "ui-click-42"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer realm=\"clinicalharmony\"")))
                .andExpect(header().string("X-Correlation-Id", "ui-click-42"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:clinicalharmony:problem:unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/v1/summary"))
                .andExpect(jsonPath("$.correlationId").value("ui-click-42"));
        verifyNoInteractions(summary);
    }

    @Test
    void aServiceTokenIsNotAUserTokenWrongAudienceIs401() throws Exception {
        mvc.perform(get("/api/v1/summary").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.serviceToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("invalid_token")));
    }

    @Test
    void aUserWithoutDashboardRolesIs403() throws Exception {
        mvc.perform(get("/api/v1/summary").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.user("eve"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:clinicalharmony:problem:forbidden"));
    }

    @Test
    void meReflectsTheTokensUserAndRoles() throws Exception {
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("vic"))
                .andExpect(jsonPath("$.roles[0]").value("dashboard-viewer"))
                .andExpect(jsonPath("$.canAct").value(false));
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.steward())))
                .andExpect(jsonPath("$.username").value("dana"))
                .andExpect(jsonPath("$.canAct").value(true));
    }

    // ---- roles ---------------------------------------------------------------------------------

    @Test
    void viewerCanReadButNotAct() throws Exception {
        when(summary.summary()).thenReturn(new SummaryQueries.Summary(Map.of(), Map.of(), Map.of(), 0, 0, 0, Map.of(), 0, null));
        mvc.perform(get("/api/v1/summary").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/pmi/match/run").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.requiredRole").value("data-steward"));
        mvc.perform(put("/api/v1/validation/rules/LAB_REFERENCE_RANGE/toggle")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(downstream);
    }

    @Test
    void viewerMayUseTheSideEffectFreeFhirValidator() throws Exception {
        when(downstream.post(eq(Downstream.FHIR_COMPLIANCE), eq("/api/fhir/validate"), any(), any()))
                .thenReturn(ResponseEntity.ok(json.readTree("{\"compliant\":false}")));
        String raw = "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"\"}]}";
        mvc.perform(post("/api/v1/fhir/validate").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer()))
                        .contentType("application/fhir+json").content(raw))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compliant").value(false));
        // body forwarded verbatim (the validator checks the raw JSON)
        verify(downstream).post(Downstream.FHIR_COMPLIANCE, "/api/fhir/validate", MediaType.APPLICATION_JSON, raw);
    }

    // ---- actions -------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void reviewerIsTakenFromTheTokenNeverFromTheBody() throws Exception {
        when(downstream.post(eq(Downstream.PATIENT_INDEX), eq("/api/pmi/candidates/{id}/accept"), any(), any(), any()))
                .thenReturn(ResponseEntity.ok(json.readTree("{\"decision\":\"ACCEPTED\"}")));

        mvc.perform(post("/api/v1/pmi/candidates/12/accept").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.steward()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"same person\",\"reviewer\":\"someone-else\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ACCEPTED"));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(downstream).post(eq(Downstream.PATIENT_INDEX), eq("/api/pmi/candidates/{id}/accept"),
                eq(MediaType.APPLICATION_JSON), body.capture(), eq(12L));
        assertReviewer((Map<String, Object>) body.getValue(), "dana", "same person");
    }

    private static void assertReviewer(Map<String, Object> body, String reviewer, String note) {
        org.assertj.core.api.Assertions.assertThat(body).containsEntry("reviewer", reviewer).containsEntry("note", note);
    }

    @Test
    void unmergeNeedsAReason() throws Exception {
        mvc.perform(post("/api/v1/pmi/merges/3/unmerge").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.steward()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:clinicalharmony:problem:invalid-request"))
                .andExpect(jsonPath("$.correlationId").exists());
        verifyNoInteractions(downstream);
    }

    @Test
    void toggleRoutesToTheOwnerAndReturnsTheGatewaysRuleView() throws Exception {
        ValidationQueries.Rule before = rule("USCORE_PATIENT_COMPLETENESS", true, "fhir-compliance-service", true);
        ValidationQueries.Rule after = rule("USCORE_PATIENT_COMPLETENESS", false, "fhir-compliance-service", true);
        when(validation.rule("USCORE_PATIENT_COMPLETENESS")).thenReturn(before, after);
        when(downstream.put(any(), any(), any())).thenReturn(ResponseEntity.ok(null));

        mvc.perform(put("/api/v1/validation/rules/USCORE_PATIENT_COMPLETENESS/toggle")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.steward())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.evaluatedBy").value("fhir-compliance-service"));
        verify(downstream).put(Downstream.FHIR_COMPLIANCE, "/api/fhir-compliance/rules/{code}/toggle",
                "USCORE_PATIENT_COMPLETENESS");
    }

    @Test
    void ruleWithNoToggleEndpointIs409() throws Exception {
        when(validation.rule("CODE-001")).thenReturn(rule("CODE-001", true, "ontology-service", false));
        mvc.perform(put("/api/v1/validation/rules/CODE-001/toggle")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.steward())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:clinicalharmony:problem:rule-not-toggleable"));
        verify(downstream, never()).put(any(), any(), any());
    }

    private static ValidationQueries.Rule rule(String code, boolean active, String owner, boolean toggleable) {
        return new ValidationQueries.Rule(code, code, "d", "DATA_QUALITY", "WARNING", active, owner, toggleable, 0,
                OffsetDateTime.now());
    }

    // ---- downstream failures → problems --------------------------------------------------------

    @Test
    void serviceDownIs503ProblemNamingTheService() throws Exception {
        when(downstream.get(eq(Downstream.ONTOLOGY), any(), any(), any())).thenThrow(new DownstreamException(
                DownstreamException.Kind.UNAVAILABLE, "ontology-service", null, "ontology-service is unreachable", null));
        mvc.perform(get("/api/v1/ontology/codes/icd10/E11.9").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:clinicalharmony:problem:downstream-unavailable"))
                .andExpect(jsonPath("$.service").value("ontology-service"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void timeoutIs504AndRejectionKeepsTheServicesStatusAndMessage() throws Exception {
        when(downstream.get(eq(Downstream.PATIENT_INDEX), eq("/api/pmi/candidates/{id}"), any())).thenThrow(
                new DownstreamException(DownstreamException.Kind.TIMEOUT, "patient-index-service", null, "slow", null));
        mvc.perform(get("/api/v1/pmi/candidates/1").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.type").value("urn:clinicalharmony:problem:downstream-timeout"));

        when(downstream.get(eq(Downstream.PATIENT_INDEX), eq("/api/pmi/candidates/{id}"), eq(404L))).thenThrow(
                new DownstreamException(DownstreamException.Kind.REJECTED, "patient-index-service", 404,
                        "No match candidate 404", null));
        mvc.perform(get("/api/v1/pmi/candidates/404").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No match candidate 404"))
                .andExpect(jsonPath("$.downstreamStatus").value(404))
                .andExpect(jsonPath("$.service").value("patient-index-service"));
    }

    // ---- request validation + own errors -------------------------------------------------------

    @Test
    void badParametersAre400WithOneErrorPerParameter() throws Exception {
        mvc.perform(get("/api/v1/patients?size=999&status=BOGUS").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:clinicalharmony:problem:invalid-request"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.instance").value("/api/v1/patients"));
        mvc.perform(get("/api/v1/ontology/codes/snomed/1").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("supported systems: icd10, loinc")));
        verifyNoInteractions(patients, downstream);
    }

    @Test
    void timelineValidatesTypesAndDates() throws Exception {
        mvc.perform(get("/api/v1/patients/1/timeline?types=VITALS").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Unknown timeline type 'VITALS'")));
        mvc.perform(get("/api/v1/patients/1/timeline?from=2024-02-01&to=2024-01-01")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
        when(patients.timeline(eq(1L), isNull(), isNull(), eq(Set.of("CONDITION", "MEDICATION"))))
                .thenReturn(new PatientQueries.Timeline(1, 0, List.of()));
        mvc.perform(get("/api/v1/patients/1/timeline?types=condition,MEDICATION")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventCount").value(0));
    }

    @Test
    void notFoundReadModelAndUnknownRouteAreBoth404Problems() throws Exception {
        when(patients.detail(99L)).thenThrow(new NotFoundException("No master patient 99"));
        mvc.perform(get("/api/v1/patients/99").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:clinicalharmony:problem:not-found"))
                .andExpect(jsonPath("$.detail").value("No master patient 99"));
        mvc.perform(get("/api/v1/nope").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:clinicalharmony:problem:not-found"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void lineageNeedsExactlyOneTarget() throws Exception {
        mvc.perform(get("/api/v1/lineage").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/lineage?fhirResourceId=1&masterPatientId=1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(lineage);
    }

    @Test
    void pagingDefaultsArePassedToTheReadModel() throws Exception {
        when(fhir.search(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.of(List.of(), 0, 25, 0));
        mvc.perform(get("/api/v1/fhir/resources?type=Patient&compliant=false")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalPages").value(0));
        verify(fhir).search("Patient", null, false, null, 0, 25);
    }

    // ---- CORS ----------------------------------------------------------------------------------

    @Test
    void corsPreflightAllowsTheDashboardOriginOnly() throws Exception {
        mvc.perform(options("/api/v1/summary").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,x-correlation-id"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
        mvc.perform(options("/api/v1/summary").header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name()))
                .andExpect(status().isForbidden());
    }

    @Test
    void actualCorsResponseExposesTheCorrelationId() throws Exception {
        when(summary.summary()).thenReturn(new SummaryQueries.Summary(Map.of(), Map.of(), Map.of(), 0, 0, 0, Map.of(), 0, null));
        mvc.perform(get("/api/v1/summary").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("X-Correlation-Id")));
    }
}
