package com.clinicalharmony.ingestion.service;

import com.clinicalharmony.ingestion.repository.RawMessageRecord;
import com.clinicalharmony.ingestion.repository.RawMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Hl7MessageProcessorTest {

    private RawMessageRepository repository;
    private Hl7MessageProcessor processor;

    @BeforeEach
    void setUp() {
        repository = mock(RawMessageRepository.class);
        processor = new Hl7MessageProcessor(repository, "DEFAULT_SOURCE");
    }

    @Test
    void landsAdtMessageAndGeneratesAckWithMatchingControlId() {
        when(repository.insert(any())).thenReturn(42L);

        Hl7ProcessingResult result = processor.process(Hl7SampleMessages.ADT_A01);

        assertThat(result.accepted()).isTrue();
        assertThat(result.rawMessageId()).isEqualTo(42L);
        assertThat(result.messageType()).isEqualTo("ADT^A01");
        assertThat(result.responseMessage()).contains("MSA|AA|MSG00001");

        ArgumentCaptor<RawMessageRecord> captor = ArgumentCaptor.forClass(RawMessageRecord.class);
        verify(repository).insert(captor.capture());
        RawMessageRecord record = captor.getValue();
        assertThat(record.sourceSystem()).isEqualTo("CITY_HOSPITAL");
        assertThat(record.messageType()).isEqualTo("HL7V2");
        assertThat(record.processingStatus()).isEqualTo("PENDING");
        assertThat(record.rawPayload()).isEqualTo(Hl7SampleMessages.ADT_A01);
        assertThat(record.payloadChecksum()).hasSize(64);
    }

    @Test
    void landsOruMessageWithCorrectSendingFacility() {
        when(repository.insert(any())).thenReturn(7L);

        Hl7ProcessingResult result = processor.process(Hl7SampleMessages.ORU_R01);

        assertThat(result.accepted()).isTrue();
        assertThat(result.messageType()).isEqualTo("ORU^R01");

        ArgumentCaptor<RawMessageRecord> captor = ArgumentCaptor.forClass(RawMessageRecord.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().sourceSystem()).isEqualTo("REGIONAL_LAB");
    }

    @Test
    void quarantinesMalformedMessageInsteadOfDroppingIt() {
        when(repository.insert(any())).thenReturn(99L);

        Hl7ProcessingResult result = processor.process(Hl7SampleMessages.MALFORMED);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rawMessageId()).isEqualTo(99L);
        assertThat(result.responseMessage()).contains("MSA|AE|");

        ArgumentCaptor<RawMessageRecord> captor = ArgumentCaptor.forClass(RawMessageRecord.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().processingStatus()).isEqualTo("QUARANTINED");
        assertThat(captor.getValue().errorMessage()).isNotBlank();
    }

    @Test
    void fallsBackToDefaultSourceSystemWhenSendingFacilityMissing() {
        when(repository.insert(any())).thenReturn(1L);
        String noFacility = "MSH|^~\\&|REG_SYSTEM||CLINICALHARMONY|INGESTION|20260702101500||ADT^A01^ADT_A01|MSG00003|P|2.5\r"
                + "PID|1||MRN1^^^X^MR||DOE^JOHN||19900101|M\r";

        processor.process(noFacility);

        ArgumentCaptor<RawMessageRecord> captor = ArgumentCaptor.forClass(RawMessageRecord.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().sourceSystem()).isEqualTo("DEFAULT_SOURCE");
    }

    @Test
    void parsesMessageSentWithLineFeedInsteadOfCarriageReturn() {
        // REST clients (e.g. Postman's raw text editor) commonly submit \n between segments
        // instead of the HL7-spec \r; the processor must still parse it, unlike a real MLLP
        // sender which always uses \r.
        when(repository.insert(any())).thenReturn(5L);
        String lfOnly = Hl7SampleMessages.ADT_A01.replace("\r", "\n");

        Hl7ProcessingResult result = processor.process(lfOnly);

        assertThat(result.accepted()).isTrue();
        assertThat(result.messageType()).isEqualTo("ADT^A01");

        ArgumentCaptor<RawMessageRecord> captor = ArgumentCaptor.forClass(RawMessageRecord.class);
        verify(repository).insert(captor.capture());
        // bronze must still land the original bytes, unmodified
        assertThat(captor.getValue().rawPayload()).isEqualTo(lfOnly);
    }
}
