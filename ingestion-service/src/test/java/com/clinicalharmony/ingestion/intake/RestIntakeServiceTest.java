package com.clinicalharmony.ingestion.intake;

import com.clinicalharmony.ingestion.kafka.Hl7RawPublisher;
import com.clinicalharmony.ingestion.kafka.KafkaTopics;
import com.clinicalharmony.ingestion.kafka.PayloadTooLargeException;
import com.clinicalharmony.ingestion.kafka.PublishFailedException;
import com.clinicalharmony.ingestion.kafka.RawEventPublisher;
import com.clinicalharmony.ingestion.service.Checksums;
import com.clinicalharmony.ingestion.service.Hl7SampleMessages;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RestIntakeServiceTest {

    private final RawEventPublisher publisher = mock(RawEventPublisher.class);
    private final Hl7RawPublisher hl7Publisher = mock(Hl7RawPublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RestIntakeService service = new RestIntakeService(new FormatChecks(new ObjectMapper()), publisher,
            hl7Publisher, meters, "UNKNOWN_HL7_SOURCE", "UNKNOWN_FHIR_SOURCE", "UNKNOWN_CLAIMS_SOURCE", "UNKNOWN_CCDA_SOURCE");

    @SuppressWarnings("unchecked")
    private final ArgumentCaptor<Map<String, Object>> value = ArgumentCaptor.forClass(Map.class);

    @Test
    void hl7GoesToTheMllpTopicMarkedRestWithMsh4AsSource() {
        when(hl7Publisher.publish(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new Hl7RawPublisher.Published("e1", "CITY_HOSPITAL|MRN100234", 1, 9));

        RestIntakeResult r = service.accept(IntakeFormat.HL7V2, Hl7SampleMessages.ADT_A01, null, null);

        assertThat(r.accepted()).isTrue();
        assertThat(r.sourceSystem()).isEqualTo("CITY_HOSPITAL");
        assertThat(r.topic()).isEqualTo(KafkaTopics.HL7V2_RAW);
        assertThat(r.correlationId()).matches("rest-[0-9a-f]{12}");
        assertThat(r.details()).containsEntry("hl7MessageType", "ADT^A01");
        verify(hl7Publisher).publish(eq(Hl7SampleMessages.ADT_A01), eq("CITY_HOSPITAL"),
                eq(Checksums.sha256(Hl7SampleMessages.ADT_A01)), any(), eq(r.correlationId()), any(),
                eq(Hl7RawPublisher.CHANNEL_REST));
    }

    @Test
    void fhirIsPublishedWithHeaderSourceAndPatientKeyAndCallerCorrelationId() {
        when(publisher.publish(eq(KafkaTopics.FHIR_RAW), eq("fhir.raw"), anyString(), anyMap(), eq("ui-0123456789ab")))
                .thenReturn(new RawEventPublisher.Published("e2", "PARTNER|MRN-F-1", 0, 3));

        RestIntakeResult r = service.accept(IntakeFormat.FHIR_JSON, IntakeSamples.FHIR_PATIENT, "PARTNER", "ui-0123456789ab");

        assertThat(r.accepted()).isTrue();
        assertThat(r.correlationId()).isEqualTo("ui-0123456789ab");
        verify(publisher).publish(eq(KafkaTopics.FHIR_RAW), eq("fhir.raw"), eq("PARTNER|MRN-F-1"), value.capture(),
                eq("ui-0123456789ab"));
        assertThat(value.getValue()).containsEntry("sourceSystem", "PARTNER").containsEntry("intakeChannel", "REST")
                .containsEntry("messageType", "FHIR_JSON").containsEntry("resourceType", "Patient")
                .containsEntry("rawPayload", IntakeSamples.FHIR_PATIENT)
                .containsEntry("payloadChecksum", Checksums.sha256(IntakeSamples.FHIR_PATIENT));
    }

    @Test
    void invalidPayloadIsStillPublishedButNotAccepted() {
        when(publisher.publish(any(), any(), any(), anyMap(), any()))
                .thenReturn(new RawEventPublisher.Published("e3", "sha256:x", 2, 4));

        RestIntakeResult r = service.accept(IntakeFormat.CLAIMS_CSV, IntakeSamples.CLAIMS_RAGGED, null, null);

        assertThat(r.accepted()).isFalse();
        assertThat(r.error()).contains("Row 1 has 4 columns");
        assertThat(r.sourceSystem()).isEqualTo("UNKNOWN_CLAIMS_SOURCE");
        verify(publisher).publish(eq(KafkaTopics.CLAIMS_RAW), eq("claims.raw"),
                eq("sha256:" + Checksums.sha256(IntakeSamples.CLAIMS_RAGGED)), anyMap(), any());
        assertThat(meters.get("clinicalharmony.ingestion.rest.received").tag("format", "claims_csv")
                .tag("outcome", "published_invalid").counter().count()).isEqualTo(1.0);
    }

    @Test
    void ccdaKeyedBySourceAndPatientRoleId() {
        when(publisher.publish(any(), any(), any(), anyMap(), any()))
                .thenReturn(new RawEventPublisher.Published("e4", "k", 0, 1));
        service.accept(IntakeFormat.CCDA_XML, IntakeSamples.CCDA, "NORTH_CLINIC", null);
        verify(publisher).publish(eq(KafkaTopics.CCDA_RAW), eq("ccda.raw"), eq("NORTH_CLINIC|MRN-C-1"), anyMap(), any());
    }

    @Test
    void kafkaDownIs503WithRetryAfterAndTheCorrelationId() {
        when(publisher.publish(any(), any(), any(), anyMap(), any()))
                .thenThrow(new PublishFailedException("Kafka did not confirm within 10000 ms", null));

        assertThatThrownBy(() -> service.accept(IntakeFormat.FHIR_JSON, IntakeSamples.FHIR_PATIENT, null, "c-1"))
                .isInstanceOfSatisfying(RestIntakeService.IntakeRejectedException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.retryAfterSeconds()).isEqualTo(5);
                    assertThat(e.correlationId()).isEqualTo("c-1");
                    assertThat(e.getMessage()).startsWith("Not stored, retry later");
                });
    }

    @Test
    void tooLargeIs413() {
        when(publisher.publish(any(), any(), any(), anyMap(), any())).thenThrow(new PayloadTooLargeException("too big"));
        assertThatThrownBy(() -> service.accept(IntakeFormat.CCDA_XML, IntakeSamples.CCDA, null, null))
                .isInstanceOfSatisfying(RestIntakeService.IntakeRejectedException.class,
                        e -> assertThat(e.status()).isEqualTo(413));
    }

    @Test
    void unusableSourceHeaderIs400AndNothingPublished() {
        assertThatThrownBy(() -> service.accept(IntakeFormat.FHIR_JSON, IntakeSamples.FHIR_PATIENT, "x".repeat(101), null))
                .isInstanceOfSatisfying(RestIntakeService.IntakeRejectedException.class,
                        e -> assertThat(e.status()).isEqualTo(400));
        assertThatThrownBy(() -> service.accept(IntakeFormat.FHIR_JSON, IntakeSamples.FHIR_PATIENT, "a\nb", null))
                .isInstanceOf(RestIntakeService.IntakeRejectedException.class);
        verifyNoInteractions(publisher);
        verify(hl7Publisher, never()).publish(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unsafeCorrelationIdIsReplaced() {
        assertThat(RestIntakeService.correlationId("bad id\nwith newline")).matches("rest-[0-9a-f]{12}");
        assertThat(RestIntakeService.correlationId("x".repeat(65))).startsWith("rest-");
        assertThat(RestIntakeService.correlationId(" ui-abc ")).isEqualTo("ui-abc");
    }
}
