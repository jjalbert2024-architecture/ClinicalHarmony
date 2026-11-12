package com.clinicalharmony.fhircompliance.gold;

import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRepository;
import com.clinicalharmony.fhircompliance.repository.GoldFhirResourceRow;
import com.clinicalharmony.fhircompliance.service.silver.BuiltResourceSummary;
import com.clinicalharmony.fhircompliance.validation.UsCoreValidatorFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real HL7 validator against the actual seed JSON; only the DB edges are mocked. */
class GoldRevalidationServiceTest {

    private static final String PENDING = "{\"status\":\"PENDING_VALIDATION\",\"seed\":\"19_seed_cms0057_gold.sql\"}";
    private static final OffsetDateTime SEEDED_AT = OffsetDateTime.parse("2026-11-11T09:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GoldFhirResourceRepository repository;
    private GoldWriter goldWriter;
    private TransactionTemplate transactionTemplate;
    private GoldRevalidationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(GoldFhirResourceRepository.class);
        goldWriter = mock(GoldWriter.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        when(goldWriter.writeResource(any())).thenReturn(30L, 31L);
        service = new GoldRevalidationService(repository, UsCoreValidatorFixture.validator(), goldWriter, objectMapper,
                transactionTemplate, Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void validatesPendingSeedRowsAndRewritesThemThroughGoldWriter() throws Exception {
        when(repository.findPendingValidation()).thenReturn(List.of(
                row(1L, "Coverage", "cms-coverage-seed-0001", fixture("coverage-0001")),
                row(2L, "PractitionerRole", "cms-practitionerrole-2", fixture("practitionerrole-2-noncompliant"))));
        when(repository.findMasterPatientIdForPatient("silver-patient-1")).thenReturn(Optional.of(42L));

        List<BuiltResourceSummary> results = service.revalidatePending();

        assertThat(results).extracting(BuiltResourceSummary::usCoreCompliant).containsExactly(true, false);
        assertThat(results.get(1).errors()).extracting(v -> v.rule()).contains("pd-1");
        verify(transactionTemplate, times(2)).execute(any());

        ArgumentCaptor<GoldResourceWrite> captor = ArgumentCaptor.forClass(GoldResourceWrite.class);
        verify(goldWriter, times(2)).writeResource(captor.capture());
        GoldResourceWrite coverage = captor.getAllValues().get(0);
        assertThat(coverage.masterPatientId()).isEqualTo(42L);
        assertThat(coverage.source()).isEqualTo(
                new LineageSource("seed", "19_seed_cms0057_gold.sql", null, LineageSource.SEED_LOAD));
        assertThat(coverage.upstreamSteps()).singleElement().satisfies(step -> {
            assertThat(step.stage()).isEqualTo(LineageStep.SEED);
            assertThat(step.transformer()).isEqualTo("docs/db-init/19_seed_cms0057_gold.sql");
            assertThat(step.timestamp()).isEqualTo("2026-11-11T09:00:00Z");
        });
        assertThat(coverage.goldTransformer()).contains("us-core-coverage");
        JsonNode notes = objectMapper.readTree(coverage.complianceNotes());
        assertThat(notes.get("status")).isNull();
        assertThat(notes.get("compliant").asBoolean()).isTrue();
        assertThat(notes.get("validatedAt").asText()).isEqualTo("2026-11-11T12:00:00Z");

        GoldResourceWrite role = captor.getAllValues().get(1);
        assertThat(role.usCoreCompliant()).isFalse();
        assertThat(role.masterPatientId()).isNull();
        assertThat(objectMapper.readTree(role.complianceNotes()).get("violations").toString()).contains("pd-1");
    }

    @Test
    void nothingPendingWritesNothing() {
        when(repository.findPendingValidation()).thenReturn(List.of());
        assertThat(service.revalidatePending()).isEmpty();
        verify(goldWriter, never()).writeResource(any());
    }

    @Test
    void patientReferenceIsFoundInSubjectPatientOrBeneficiary() {
        assertThat(service.patientReference("{\"subject\":{\"reference\":\"Patient/a\"}}")).contains("a");
        assertThat(service.patientReference("{\"patient\":{\"reference\":\"Patient/b\"}}")).contains("b");
        assertThat(service.patientReference("{\"beneficiary\":{\"reference\":\"Patient/c\"}}")).contains("c");
        assertThat(service.patientReference("{\"subject\":{\"reference\":\"Group/g\"}}")).isEmpty();
        assertThat(service.patientReference("{\"resourceType\":\"MedicationKnowledge\"}")).isEmpty();
    }

    private static GoldFhirResourceRow row(long id, String type, String fhirId, String json) {
        return new GoldFhirResourceRow(id, type, fhirId, json, false, PENDING, null, SEEDED_AT, SEEDED_AT);
    }

    private String fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/cms0057/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
