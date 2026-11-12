package com.clinicalharmony.fhircompliance.web;

import com.clinicalharmony.fhircompliance.security.WebSecurityTestConfig;
import com.clinicalharmony.fhircompliance.cms0057.Cms0057SearchParameters;
import com.clinicalharmony.fhircompliance.cms0057.GoldFhirReadService;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRepository;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP slice with the real read service and search parser; only the Gold repository is mocked. */
@WebMvcTest(FhirR4Controller.class)
@Import({GoldFhirReadService.class, Cms0057SearchParameters.class, WebSecurityTestConfig.class})
class FhirR4ControllerTest {

    private static final OffsetDateTime UPDATED = OffsetDateTime.parse("2026-11-11T12:00:00Z");
    private static final String COVERAGE_JSON =
            "{\"resourceType\":\"Coverage\",\"id\":\"cms-coverage-seed-0001\",\"status\":\"active\"}";
    private static final String NOTES = "{\"profile\":\"http://hl7.org/fhir/us/core/StructureDefinition/us-core-coverage\",\"compliant\":true}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GoldFhirResourceRepository repository;

    private static GoldFhirResourceRow coverage(String notes, boolean compliant) {
        return new GoldFhirResourceRow(1L, "Coverage", "cms-coverage-seed-0001", COVERAGE_JSON, compliant, notes, 42L,
                UPDATED, UPDATED);
    }

    @Test
    void readReturnsStoredResourceWithComplianceHeaders() throws Exception {
        when(repository.findByTypeAndFhirId("Coverage", "cms-coverage-seed-0001"))
                .thenReturn(Optional.of(coverage(NOTES, true)));

        mockMvc.perform(get("/fhir/r4/Coverage/cms-coverage-seed-0001").header("X-Correlation-Id", "cms-read-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
                .andExpect(jsonPath("$.resourceType").value("Coverage"))
                .andExpect(jsonPath("$.id").value("cms-coverage-seed-0001"))
                .andExpect(header().string("X-Profile-Compliant", "true"))
                .andExpect(header().string("X-Validated-Profile", "http://hl7.org/fhir/us/core/StructureDefinition/us-core-coverage"))
                .andExpect(header().string("ETag", "W/\"" + UPDATED.toInstant().toEpochMilli() + "\""))
                .andExpect(header().exists("Last-Modified"))
                .andExpect(header().string("X-Correlation-Id", "cms-read-1"));
    }

    @Test
    void readOfUnvalidatedSeedRowSaysPending() throws Exception {
        when(repository.findByTypeAndFhirId("Coverage", "cms-coverage-seed-0001")).thenReturn(Optional.of(
                coverage("{\"status\":\"PENDING_VALIDATION\",\"seed\":\"19_seed_cms0057_gold.sql\"}", false)));

        mockMvc.perform(get("/fhir/r4/Coverage/cms-coverage-seed-0001"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Profile-Compliant", "pending"))
                .andExpect(header().doesNotExist("X-Validated-Profile"));
    }

    @Test
    void unknownIdIs404OperationOutcome() throws Exception {
        when(repository.findByTypeAndFhirId("Device", "nope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/fhir/r4/Device/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"))
                .andExpect(jsonPath("$.issue[0].severity").value("error"))
                .andExpect(jsonPath("$.issue[0].code").value("not-found"))
                .andExpect(jsonPath("$.issue[0].diagnostics").value("Resource Device/nope is not known"));
    }

    @Test
    void unsupportedTypeIs404OperationOutcomeOnReadAndSearch() throws Exception {
        mockMvc.perform(get("/fhir/r4/Patient/silver-patient-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.issue[0].code").value("not-supported"));
        mockMvc.perform(get("/fhir/r4/Claim"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.issue[0].code").value("not-supported"));
        verify(repository, never()).findByTypeAndFhirId(any(), any());
    }

    @Test
    void searchReturnsSearchsetBundle() throws Exception {
        when(repository.count(eq("Coverage"), any())).thenReturn(1L);
        when(repository.search(eq("Coverage"), any(), anyInt())).thenReturn(List.of(coverage(NOTES, true)));

        mockMvc.perform(get("/fhir/r4/Coverage?patient=silver-patient-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
                .andExpect(jsonPath("$.resourceType").value("Bundle"))
                .andExpect(jsonPath("$.type").value("searchset"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.link[0].relation").value("self"))
                .andExpect(jsonPath("$.link[0].url").value("http://localhost/fhir/r4/Coverage?patient=silver-patient-1"))
                .andExpect(jsonPath("$.entry[0].fullUrl").value("http://localhost/fhir/r4/Coverage/cms-coverage-seed-0001"))
                .andExpect(jsonPath("$.entry[0].resource.id").value("cms-coverage-seed-0001"))
                .andExpect(jsonPath("$.entry[0].search.mode").value("match"));
    }

    @Test
    void emptySearchHasTotalZeroAndNoEntries() throws Exception {
        when(repository.count(eq("Device"), any())).thenReturn(0L);
        when(repository.search(eq("Device"), any(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/fhir/r4/Device").param("status", "inactive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.entry").doesNotExist());
    }

    @Test
    void countZeroReturnsTotalOnly() throws Exception {
        when(repository.count(eq("Device"), any())).thenReturn(3L);

        mockMvc.perform(get("/fhir/r4/Device").param("_count", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.entry").doesNotExist());
        verify(repository, never()).search(any(), any(), anyInt());
    }

    @Test
    void badSearchParameterIs400OperationOutcome() throws Exception {
        mockMvc.perform(get("/fhir/r4/MedicationKnowledge").param("ingredient", "6809"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"))
                .andExpect(jsonPath("$.issue[0].code").value("invalid"))
                .andExpect(jsonPath("$.issue[0].diagnostics").value(org.hamcrest.Matchers.containsString("Unknown search parameter 'ingredient'")));
        verify(repository, never()).count(any(), any());
    }

    @Test
    void unexpectedFailureIs500OperationOutcomeWithoutInternals() throws Exception {
        when(repository.findByTypeAndFhirId("Coverage", "x")).thenThrow(new IllegalStateException("db password is hunter2"));

        mockMvc.perform(get("/fhir/r4/Coverage/x"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.resourceType").value("OperationOutcome"))
                .andExpect(jsonPath("$.issue[0].severity").value("fatal"))
                .andExpect(jsonPath("$.issue[0].diagnostics").value("Internal error"));
    }
}
