package com.clinicalharmony.ingestion.service;

import com.clinicalharmony.ingestion.repository.RawMessageRecord;
import com.clinicalharmony.ingestion.repository.RawMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaimsMessageProcessorTest {

    private static final String VALID_CSV = """
            claim_id,member_id,service_date,icd10_code,billed_amount
            CLM-001,MBR-1001,2026-06-01,E11.9,150.00
            CLM-002,MBR-1002,2026-06-02,I10,95.50
            CLM-003,MBR-1001,2026-06-15,J45.909,210.25
            """;

    private RawMessageRepository repository;
    private ClaimsMessageProcessor processor;

    @BeforeEach
    void setUp() {
        repository = mock(RawMessageRepository.class);
        processor = new ClaimsMessageProcessor(repository, "DEFAULT_CLAIMS_SOURCE");
    }

    @Test
    void landsOneBronzeRowPerClaimLine() {
        AtomicLong nextId = new AtomicLong(100);
        when(repository.insert(any())).thenAnswer(inv -> nextId.getAndIncrement());

        ClaimsProcessingResult result = processor.process(VALID_CSV, "CLAIMS_VENDOR_A");

        assertThat(result.accepted()).isTrue();
        assertThat(result.rowsLanded()).isEqualTo(3);
        assertThat(result.rawMessageIds()).containsExactly(100L, 101L, 102L);

        ArgumentCaptor<RawMessageRecord> captor = ArgumentCaptor.forClass(RawMessageRecord.class);
        verify(repository, times(3)).insert(captor.capture());
        RawMessageRecord first = captor.getAllValues().get(0);
        assertThat(first.sourceSystem()).isEqualTo("CLAIMS_VENDOR_A");
        assertThat(first.messageType()).isEqualTo("CLAIMS_CSV");
        assertThat(first.messageFormat()).isEqualTo("CSV");
        assertThat(first.processingStatus()).isEqualTo("PENDING");
        // each row payload is self-describing: header line + that one claim line
        assertThat(first.rawPayload()).isEqualTo(
                "claim_id,member_id,service_date,icd10_code,billed_amount\n"
                        + "CLM-001,MBR-1001,2026-06-01,E11.9,150.00");
        assertThat(captor.getAllValues().get(2).rawPayload()).endsWith("CLM-003,MBR-1001,2026-06-15,J45.909,210.25");
    }

    @Test
    void quarantinesWholeFileWhenARowHasWrongColumnCount() {
        when(repository.insert(any())).thenReturn(200L);
        String raggedCsv = """
                claim_id,member_id,service_date
                CLM-001,MBR-1001,2026-06-01
                CLM-002,MBR-1002
                """;

        ClaimsProcessingResult result = processor.process(raggedCsv, null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rowsLanded()).isZero();
        assertThat(result.errorMessage()).contains("columns");

        // exactly one insert: the quarantined whole file — no partial claim rows landed
        ArgumentCaptor<RawMessageRecord> captor = ArgumentCaptor.forClass(RawMessageRecord.class);
        verify(repository, times(1)).insert(captor.capture());
        assertThat(captor.getValue().processingStatus()).isEqualTo("QUARANTINED");
        assertThat(captor.getValue().rawPayload()).isEqualTo(raggedCsv);
        assertThat(captor.getValue().sourceSystem()).isEqualTo("DEFAULT_CLAIMS_SOURCE");
    }

    @Test
    void quarantinesHeaderOnlyFile() {
        when(repository.insert(any())).thenReturn(201L);

        ClaimsProcessingResult result = processor.process("claim_id,member_id,service_date\n", "CLAIMS_VENDOR_A");

        assertThat(result.accepted()).isFalse();
        assertThat(result.errorMessage()).contains("no claim rows");

        ArgumentCaptor<RawMessageRecord> captor = ArgumentCaptor.forClass(RawMessageRecord.class);
        verify(repository, times(1)).insert(captor.capture());
        assertThat(captor.getValue().processingStatus()).isEqualTo("QUARANTINED");
    }
}
