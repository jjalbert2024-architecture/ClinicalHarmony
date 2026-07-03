package com.clinicalharmony.ingestion.service;

import com.clinicalharmony.ingestion.repository.RawMessageRecord;
import com.clinicalharmony.ingestion.repository.RawMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FhirMessageProcessorTest {

    private static final String PATIENT_RESOURCE = """
            {"resourceType": "Patient", "id": "pat-1",
             "name": [{"family": "Doe", "given": ["Jane"]}],
             "gender": "female", "birthDate": "1980-05-15"}
            """;

    private static final String BUNDLE = """
            {"resourceType": "Bundle", "type": "collection", "entry": [
              {"resource": {"resourceType": "Patient", "id": "pat-1"}},
              {"resource": {"resourceType": "Condition", "id": "cond-1",
                "code": {"coding": [{"system": "http://hl7.org/fhir/sid/icd-10-cm", "code": "E11.9"}]}}}
            ]}
            """;

    private RawMessageRepository repository;
    private FhirMessageProcessor processor;

    @BeforeEach
    void setUp() {
        repository = mock(RawMessageRepository.class);
        processor = new FhirMessageProcessor(repository, new ObjectMapper(), "DEFAULT_FHIR_SOURCE");
    }

    @Test
    void landsSingleResourceWithCallerDeclaredSource() {
        when(repository.insert(any())).thenReturn(10L);

        FhirProcessingResult result = processor.process(PATIENT_RESOURCE, "PARTNER_CLINIC");

        assertThat(result.accepted()).isTrue();
        assertThat(result.rawMessageId()).isEqualTo(10L);
        assertThat(result.resourceType()).isEqualTo("Patient");
        assertThat(result.resourceCount()).isEqualTo(1);

        ArgumentCaptor<RawMessageRecord> captor = ArgumentCaptor.forClass(RawMessageRecord.class);
        verify(repository).insert(captor.capture());
        RawMessageRecord record = captor.getValue();
        assertThat(record.sourceSystem()).isEqualTo("PARTNER_CLINIC");
        assertThat(record.messageType()).isEqualTo("FHIR_JSON");
        assertThat(record.messageFormat()).isEqualTo("JSON");
        assertThat(record.processingStatus()).isEqualTo("PENDING");
        assertThat(record.rawPayload()).isEqualTo(PATIENT_RESOURCE);
    }

    @Test
    void landsBundleAndCountsEntries() {
        when(repository.insert(any())).thenReturn(11L);

        FhirProcessingResult result = processor.process(BUNDLE, null);

        assertThat(result.accepted()).isTrue();
        assertThat(result.resourceType()).isEqualTo("Bundle");
        assertThat(result.resourceCount()).isEqualTo(2);

        ArgumentCaptor<RawMessageRecord> captor = ArgumentCaptor.forClass(RawMessageRecord.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().sourceSystem()).isEqualTo("DEFAULT_FHIR_SOURCE");
    }

    @Test
    void quarantinesJsonWithoutResourceType() {
        when(repository.insert(any())).thenReturn(12L);

        FhirProcessingResult result = processor.process("{\"foo\": \"bar\"}", "PARTNER_CLINIC");

        assertThat(result.accepted()).isFalse();
        assertThat(result.rawMessageId()).isEqualTo(12L);

        ArgumentCaptor<RawMessageRecord> captor = ArgumentCaptor.forClass(RawMessageRecord.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().processingStatus()).isEqualTo("QUARANTINED");
        assertThat(captor.getValue().errorMessage()).contains("resourceType");
    }

    @Test
    void quarantinesMalformedJson() {
        when(repository.insert(any())).thenReturn(13L);

        FhirProcessingResult result = processor.process("this is not json {", null);

        assertThat(result.accepted()).isFalse();

        ArgumentCaptor<RawMessageRecord> captor = ArgumentCaptor.forClass(RawMessageRecord.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().processingStatus()).isEqualTo("QUARANTINED");
        assertThat(captor.getValue().errorMessage()).isNotBlank();
    }
}
