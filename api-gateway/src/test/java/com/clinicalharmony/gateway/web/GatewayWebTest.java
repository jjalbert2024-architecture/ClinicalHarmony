package com.clinicalharmony.gateway.web;

import com.clinicalharmony.gateway.config.CorsConfig;
import com.clinicalharmony.gateway.config.GatewayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.clinicalharmony.gateway.downstream.Downstream;
import com.clinicalharmony.gateway.downstream.DownstreamClient;
import com.clinicalharmony.gateway.downstream.DownstreamException;
import com.clinicalharmony.gateway.downstream.ServiceHealthService;
import com.clinicalharmony.gateway.read.FhirQueries;
import com.clinicalharmony.gateway.read.InvalidFilterException;
import com.clinicalharmony.gateway.read.LineageQueries;
import com.clinicalharmony.gateway.read.ListWindow;
import com.clinicalharmony.gateway.read.NotFoundException;
import com.clinicalharmony.gateway.read.OntologyQueries;
import com.clinicalharmony.gateway.read.Page;
import com.clinicalharmony.gateway.read.PatientQueries;
import com.clinicalharmony.gateway.read.SilverQueries;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
    private SilverQueries silver;
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
                .andExpect(jsonPath("$.auth.issuer").value(TestJwts.ISSUER))
                // 9e: where the UI links a correlation id to its Splunk trace (SPLUNK_WEB_URL, as the browser sees it)
                .andExpect(jsonPath("$.observability.splunkTraceUrl")
                        .value("http://localhost:8000/en-US/app/clinicalharmony/clinicalharmony_trace"));
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
        when(summary.summary()).thenReturn(new SummaryQueries.Summary(Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, Map.of(), 0, null));
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
    void bronzeListPassesTheHl7TypeFilterAndRejectsMalformedOnes() throws Exception {
        when(lineage.bronzeMessages(any(), any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.of(List.of(), 0, 15, 0));
        mvc.perform(get("/api/v1/bronze/messages?messageType=HL7V2&hl7Type=ORU&size=15")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk());
        verify(lineage).bronzeMessages(null, "HL7V2", "ORU", null, null, ListWindow.NONE, 0, 15);
        for (String bad : List.of("oru", "ORU%5ER01", "ORUX", "'--")) {
            mvc.perform(get("/api/v1/bronze/messages?hl7Type=" + bad).header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                    .andExpect(status().isBadRequest());
        }
        verifyNoMoreInteractions(lineage);
    }

    @Test
    void everyPagedListTakesANumberRangeAndAnOrder() throws Exception {
        when(lineage.bronzeMessages(any(), any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.of(List.of(), 0, 50, 0));
        when(silver.patients(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.of(List.of(), 0, 50, 0));
        when(patients.search(any(), any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(Page.of(List.of(), 0, 50, 0));
        String viewer = bearer(TestJwts.viewer());
        mvc.perform(get("/api/v1/bronze/messages?idFrom=990&idTo=1003&order=oldest&size=50").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk());
        verify(lineage).bronzeMessages(null, null, null, null, null, new ListWindow(990L, 1003L, "oldest"), 0, 50);
        // a reversed range is read the way it was meant
        mvc.perform(get("/api/v1/silver/patients?idFrom=900&idTo=800").header(HttpHeaders.AUTHORIZATION, viewer)).andExpect(status().isOk());
        verify(silver).patients(null, null, null, new ListWindow(800L, 900L, null), 0, 25);
        // people also sort by name; time-ordered lists don't
        mvc.perform(get("/api/v1/patients?order=name&idFrom=40").header(HttpHeaders.AUTHORIZATION, viewer)).andExpect(status().isOk());
        verify(patients).search(null, null, null, false, new ListWindow(40L, null, "name"), 0, 25);
        for (String bad : List.of("order=name", "order=random", "idFrom=0", "idTo=-5", "idFrom=abc")) {
            mvc.perform(get("/api/v1/bronze/messages?" + bad).header(HttpHeaders.AUTHORIZATION, viewer))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void bronzeListAcceptsTheCcdaFormatAndRejectsUnknownOnes() throws Exception {
        when(lineage.bronzeMessages(any(), any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.of(List.of(), 0, 15, 0));
        mvc.perform(get("/api/v1/bronze/messages?messageType=CCDA_XML&size=15")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk());
        verify(lineage).bronzeMessages(null, "CCDA_XML", null, null, null, ListWindow.NONE, 0, 15);
        mvc.perform(get("/api/v1/bronze/messages?messageType=PDF").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lineageNeedsExactlyOneTarget() throws Exception {
        mvc.perform(get("/api/v1/lineage").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/lineage?fhirResourceId=1&masterPatientId=1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/lineage?silverPatientId=1&bronzeMessageId=1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(lineage);
    }

    @Test
    void lineageTracesFromSilverAndBronzeToo() throws Exception {
        when(lineage.forSilverPatient(681L)).thenReturn(new LineageQueries.LineageGraph("silver.patients", 681L, List.of(), List.of()));
        when(lineage.forBronzeMessage(12L)).thenReturn(new LineageQueries.LineageGraph("bronze.raw_messages", 12L, List.of(), List.of()));
        mvc.perform(get("/api/v1/lineage?silverPatientId=681").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value("silver.patients"));
        mvc.perform(get("/api/v1/lineage?bronzeMessageId=12").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value("bronze.raw_messages"));
    }

    @Test
    void silverEndpointsValidateTheirInputs() throws Exception {
        when(silver.patients(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.of(List.of(), 0, 25, 0));
        mvc.perform(get("/api/v1/silver/patients?q=NC700343&sourceSystem=NORTH_CLINIC&validationStatus=NOT_TRACKED")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk());
        verify(silver).patients("NC700343", "NORTH_CLINIC", "NOT_TRACKED", ListWindow.NONE, 0, 25);
        mvc.perform(get("/api/v1/silver/patients?validationStatus=MAYBE").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/silver/rows/pg_user/1").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/silver/patients")).andExpect(status().isUnauthorized());
    }

    @Test
    void pagingDefaultsArePassedToTheReadModelNewestFirst() throws Exception {
        when(fhir.search(any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(Page.of(List.of(), 0, 25, 0));
        mvc.perform(get("/api/v1/fhir/resources?type=Patient&compliant=false")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(25))
                .andExpect(jsonPath("$.totalPages").value(0));
        verify(fhir).search(new FhirQueries.Filter("Patient", null, false, null, null, null, null, null),
                "updated", true, 0, 25);
    }

    @Test
    void attributeFiltersArePassedThroughAndUnknownOnesAre400() throws Exception {
        when(fhir.search(any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(Page.of(List.of(), 0, 25, 0));
        mvc.perform(get("/api/v1/fhir/resources?type=Patient&attr.gender=female&attr.birthDateFrom=1970")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk());
        verify(fhir).search(new FhirQueries.Filter("Patient", null, null, null, null, null, null, null,
                Map.of("gender", "female", "birthDateFrom", "1970")), "updated", true, 0, 25);

        when(fhir.search(any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenThrow(new InvalidFilterException("Unknown attribute filter attr.shoeSize for Patient"));
        mvc.perform(get("/api/v1/fhir/resources?type=Patient&attr.shoeSize=9")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:clinicalharmony:problem:invalid-request"))
                .andExpect(jsonPath("$.detail").value("Unknown attribute filter attr.shoeSize for Patient"));
        mvc.perform(get("/api/v1/fhir/attributes?type=patient;drop")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fhirSortIsWhitelistedAndOtherColumnsDefaultAscending() throws Exception {
        when(fhir.search(any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(Page.of(List.of(), 0, 25, 0));
        mvc.perform(get("/api/v1/fhir/resources?sort=updated&dir=asc")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk());
        verify(fhir).search(FhirQueries.Filter.none(), "updated", false, 0, 25);
        mvc.perform(get("/api/v1/fhir/resources?sort=resource")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk());
        verify(fhir).search(FhirQueries.Filter.none(), "resource", false, 0, 25);
        mvc.perform(get("/api/v1/fhir/resources?sort=fhir_id;drop").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/fhir/resources?dir=sideways").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fhirFiltersArePassedThroughAndValidated() throws Exception {
        when(fhir.search(any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(Page.of(List.of(), 0, 25, 0));
        mvc.perform(get("/api/v1/fhir/resources?patient=NC700343&origin=pipeline&sourceSystem=NORTH_CLINIC&code=723")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk());
        verify(fhir).search(new FhirQueries.Filter(null, null, null, null, "NC700343", "pipeline", "NORTH_CLINIC", "723"),
                "updated", true, 0, 25);
        mvc.perform(get("/api/v1/fhir/resources?origin=everything").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/fhir/resources?sourceSystem=A%27%3B--").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pmiCandidatesForwardDecidedAndRunFilter() throws Exception {
        when(downstream.get(eq(Downstream.PATIENT_INDEX), eq("/api/pmi/candidates?status={status}&runId={runId}&limit={limit}"),
                any(), any(), any())).thenReturn(ResponseEntity.ok(new com.fasterxml.jackson.databind.ObjectMapper().readTree("[]")));
        mvc.perform(get("/api/v1/pmi/candidates?status=DECIDED&runId=3&limit=10")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk());
        verify(downstream).get(Downstream.PATIENT_INDEX, "/api/pmi/candidates?status={status}&runId={runId}&limit={limit}",
                "DECIDED", 3L, 10);
        mvc.perform(get("/api/v1/pmi/candidates?runId=0").header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isBadRequest());
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
        when(summary.summary()).thenReturn(new SummaryQueries.Summary(Map.of(), Map.of(), Map.of(), Map.of(), 0, 0, 0, Map.of(), 0, null));
        mvc.perform(get("/api/v1/summary").header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwts.viewer())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("X-Correlation-Id")));
    }
}
