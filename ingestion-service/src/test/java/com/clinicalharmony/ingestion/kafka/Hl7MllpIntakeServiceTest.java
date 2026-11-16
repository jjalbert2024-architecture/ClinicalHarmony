package com.clinicalharmony.ingestion.kafka;

import com.clinicalharmony.ingestion.service.Checksums;
import com.clinicalharmony.ingestion.service.Hl7SampleMessages;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Hl7MllpIntakeServiceTest {

    private final Hl7RawPublisher publisher = mock(Hl7RawPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Hl7MllpIntakeService service = new Hl7MllpIntakeService(publisher, "UNKNOWN_HL7_SOURCE", meters);

    @Test
    void acksAaOnlyAfterKafkaConfirms() {
        when(publisher.publish(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Hl7RawPublisher.Published("e1", "CITY_HOSPITAL|MRN100234", 2, 41));

        Hl7MllpIntakeService.Outcome outcome = service.handle(Hl7SampleMessages.ADT_A01);

        assertThat(outcome.published()).isTrue();
        assertThat(outcome.ack()).contains("\rMSA|AA|MSG00001\r").contains("|ACK^A01^ACK|");
        assertThat(outcome.correlationId()).matches("mllp-[0-9a-f]{12}");
        verify(publisher).publish(eq(Hl7SampleMessages.ADT_A01), eq("CITY_HOSPITAL"),
                eq(Checksums.sha256(Hl7SampleMessages.ADT_A01)), any(), startsWith("mllp-"), any());
        assertThat(meters.get("clinicalharmony.hl7.mllp.received").tag("outcome", "published").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void malformedMessageIsStillPublishedAndAccepted() {
        when(publisher.publish(any(), any(), any(), any(), any(), any()))
                .thenReturn(new Hl7RawPublisher.Published("e2", "sha256:x", 0, 7));

        Hl7MllpIntakeService.Outcome outcome = service.handle(Hl7SampleMessages.MALFORMED);

        // Bronze keeps everything: the listener quarantines it, the door doesn't reject it.
        assertThat(outcome.ack()).contains("MSA|AA|UNKNOWN");
        verify(publisher).publish(eq(Hl7SampleMessages.MALFORMED), eq("UNKNOWN_HL7_SOURCE"), any(), any(), any(), any());
    }

    @Test
    void naksAeWhenKafkaDoesNotConfirm() {
        when(publisher.publish(any(), any(), any(), any(), any(), any()))
                .thenThrow(new Hl7RawPublisher.PublishFailedException("Kafka did not confirm within 10000 ms", null));

        Hl7MllpIntakeService.Outcome outcome = service.handle(Hl7SampleMessages.ORU_R01);

        assertThat(outcome.published()).isFalse();
        assertThat(outcome.ack()).contains("MSA|AE|MSG00002|Message not stored, retry later");
        assertThat(meters.get("clinicalharmony.hl7.mllp.received").tag("outcome", "publish_failed").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void ackTextNeverBreaksHl7Delimiters() {
        String ack = Hl7AckBuilder.error(Hl7Envelope.peek(Hl7SampleMessages.ADT_A01), "ACK1", "bad|thing^here\rx");
        assertThat(ack.split("\r")).hasSize(2);
        assertThat(ack).endsWith("MSA|AE|MSG00001|bad thing here x\r");
    }
}
