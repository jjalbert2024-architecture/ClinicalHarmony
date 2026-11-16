package com.clinicalharmony.bronze.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hl7RawEventTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String SHA = "a".repeat(64);

    @Test
    void parsesSchemaVersion1() {
        Hl7RawEvent e = Hl7RawEvent.parse("""
                {"sourceSystem":"CITY_HOSPITAL","payloadChecksum":"%s","receivedAt":"2026-11-14T10:00:00Z",
                 "hl7MessageType":"ADT^A01","controlId":"C1","patientId":"MRN1","rawPayload":"MSH|..."}
                """.formatted(SHA), mapper);
        assertThat(e.sourceSystem()).isEqualTo("CITY_HOSPITAL");
        assertThat(e.receivedAt()).isEqualTo(Instant.parse("2026-11-14T10:00:00Z"));
        assertThat(e.rawPayload()).isEqualTo("MSH|...");
    }

    @Test
    void brokenEventsAreInvalidNotRetryable() {
        assertThatThrownBy(() -> Hl7RawEvent.parse("not json", mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> Hl7RawEvent.parse("[1]", mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> Hl7RawEvent.parse(null, mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> Hl7RawEvent.parse("{\"sourceSystem\":\"X\",\"payloadChecksum\":\"" + SHA + "\"}", mapper))
                .hasMessageContaining("rawPayload");
        assertThatThrownBy(() -> Hl7RawEvent.parse("{\"sourceSystem\":\"X\",\"payloadChecksum\":\"nope\",\"rawPayload\":\"x\"}", mapper))
                .hasMessageContaining("SHA-256");
        assertThatThrownBy(() -> Hl7RawEvent.parse("{\"payloadChecksum\":\"" + SHA + "\",\"rawPayload\":\"x\"}", mapper))
                .hasMessageContaining("sourceSystem");
        assertThatThrownBy(() -> Hl7RawEvent.parse("{\"sourceSystem\":\"X\",\"payloadChecksum\":\"" + SHA
                + "\",\"rawPayload\":\"x\",\"receivedAt\":\"yesterday\"}", mapper)).hasMessageContaining("receivedAt");
    }
}
