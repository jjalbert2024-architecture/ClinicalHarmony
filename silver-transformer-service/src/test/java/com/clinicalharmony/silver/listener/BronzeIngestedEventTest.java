package com.clinicalharmony.silver.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BronzeIngestedEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesTheEventBronzeListenerWrites() {
        BronzeIngestedEvent e = BronzeIngestedEvent.parse("""
                {"rawMessageId": 42, "messageType": "HL7V2", "hl7MessageType": "ADT^A01", "sourceSystem": "CITY_HOSPITAL",
                 "payloadChecksum": "ab", "controlId": "S42-00001", "receivedAt": "2026-11-17T08:00:00Z"}
                """, mapper);
        assertThat(e.rawMessageId()).isEqualTo(42);
        assertThat(e.hl7MessageType()).isEqualTo("ADT^A01");
        assertThat(e.sourceSystem()).isEqualTo("CITY_HOSPITAL");
        assertThat(e.controlId()).isEqualTo("S42-00001");
    }

    @Test
    void brokenEventsAreInvalid() {
        assertThatThrownBy(() -> BronzeIngestedEvent.parse(null, mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> BronzeIngestedEvent.parse("not json", mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> BronzeIngestedEvent.parse("[1]", mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> BronzeIngestedEvent.parse("{\"messageType\":\"HL7V2\"}", mapper))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("rawMessageId");
        assertThatThrownBy(() -> BronzeIngestedEvent.parse("{\"rawMessageId\": \"abc\"}", mapper))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> BronzeIngestedEvent.parse("{\"rawMessageId\": 0}", mapper))
                .isInstanceOf(InvalidEventException.class);
    }
}
