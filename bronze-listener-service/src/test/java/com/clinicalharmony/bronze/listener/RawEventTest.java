package com.clinicalharmony.bronze.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RawEventTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String SHA = "a".repeat(64);

    @Test
    void parsesSchemaVersion1AndTreatsMissingChannelAsMllp() {
        RawEvent e = RawEvent.parse("""
                {"sourceSystem":"CITY_HOSPITAL","payloadChecksum":"%s","receivedAt":"2026-11-14T10:00:00Z",
                 "hl7MessageType":"ADT^A01","controlId":"C1","patientId":"MRN1","rawPayload":"MSH|..."}
                """.formatted(SHA), mapper);
        assertThat(e.sourceSystem()).isEqualTo("CITY_HOSPITAL");
        assertThat(e.receivedAt()).isEqualTo(Instant.parse("2026-11-14T10:00:00Z"));
        assertThat(e.rawPayload()).isEqualTo("MSH|...");
        assertThat(e.intakeChannel()).isEqualTo("MLLP");
        assertThat(e.bronzeChannel()).isEqualTo("MLLP_KAFKA");
    }

    @Test
    void restChannelBecomesRestKafka() {
        RawEvent e = RawEvent.parse("{\"sourceSystem\":\"X\",\"payloadChecksum\":\"" + SHA
                + "\",\"intakeChannel\":\"REST\",\"messageType\":\"FHIR_JSON\",\"rawPayload\":\"{}\"}", mapper);
        assertThat(e.bronzeChannel()).isEqualTo("REST_KAFKA");
    }

    @Test
    void brokenEventsAreInvalidNotRetryable() {
        assertThatThrownBy(() -> RawEvent.parse("not json", mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> RawEvent.parse("[1]", mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> RawEvent.parse(null, mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> RawEvent.parse("{\"sourceSystem\":\"X\",\"payloadChecksum\":\"" + SHA + "\"}", mapper))
                .hasMessageContaining("rawPayload");
        assertThatThrownBy(() -> RawEvent.parse("{\"sourceSystem\":\"X\",\"payloadChecksum\":\"nope\",\"rawPayload\":\"x\"}", mapper))
                .hasMessageContaining("SHA-256");
        assertThatThrownBy(() -> RawEvent.parse("{\"payloadChecksum\":\"" + SHA + "\",\"rawPayload\":\"x\"}", mapper))
                .hasMessageContaining("sourceSystem");
        assertThatThrownBy(() -> RawEvent.parse("{\"sourceSystem\":\"X\",\"payloadChecksum\":\"" + SHA
                + "\",\"rawPayload\":\"x\",\"receivedAt\":\"yesterday\"}", mapper)).hasMessageContaining("receivedAt");
        assertThatThrownBy(() -> RawEvent.parse("{\"sourceSystem\":\"X\",\"payloadChecksum\":\"" + SHA
                + "\",\"rawPayload\":\"x\",\"intakeChannel\":\"FTP\"}", mapper)).hasMessageContaining("intakeChannel");
    }
}
