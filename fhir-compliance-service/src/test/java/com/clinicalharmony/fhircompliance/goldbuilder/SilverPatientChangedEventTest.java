package com.clinicalharmony.fhircompliance.goldbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SilverPatientChangedEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesTheSilverTransformersEvent() {
        SilverPatientChangedEvent e = SilverPatientChangedEvent.parse("""
                {"silverPatientId":42,"sourceSystem":"CITY_HOSPITAL","sourcePatientId":"CH123456","rawMessageId":901,
                 "hl7MessageType":"ADT^A01","controlId":"S7-00001","change":"CREATED",
                 "counts":{"conditions":2,"observations":0,"medications":0,"allergies":1}}
                """, mapper);
        assertThat(e).isEqualTo(new SilverPatientChangedEvent(42L, "CITY_HOSPITAL", "CH123456", 901L, "ADT^A01", "CREATED"));
    }

    @Test
    void onlySilverPatientIdIsRequired() {
        SilverPatientChangedEvent e = SilverPatientChangedEvent.parse("{\"silverPatientId\":7,\"rawMessageId\":null}", mapper);
        assertThat(e.silverPatientId()).isEqualTo(7L);
        assertThat(e.rawMessageId()).isNull();
        assertThat(e.sourceSystem()).isNull();
    }

    @Test
    void brokenRecordsAreInvalidEvents() {
        assertThatThrownBy(() -> SilverPatientChangedEvent.parse(null, mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> SilverPatientChangedEvent.parse("not json", mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> SilverPatientChangedEvent.parse("[1]", mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> SilverPatientChangedEvent.parse("{\"silverPatientId\":0}", mapper))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("silverPatientId");
        assertThatThrownBy(() -> SilverPatientChangedEvent.parse("{\"rawMessageId\":3}", mapper))
                .isInstanceOf(InvalidEventException.class);
    }
}
