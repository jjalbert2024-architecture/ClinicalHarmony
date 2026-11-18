package com.clinicalharmony.patientindex.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoldPatientChangedEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesTheGoldBuildersEvent() {
        GoldPatientChangedEvent e = GoldPatientChangedEvent.parse("""
                {"masterPatientId":12,"silverPatientId":40,"patientFhirId":"silver-patient-40",
                 "sourceSystem":"NORTH_CLINIC","compliance":{"resources":4,"compliant":4,"nonCompliant":0}}
                """, mapper);
        assertThat(e).isEqualTo(new GoldPatientChangedEvent(12L, 40L, "NORTH_CLINIC"));
    }

    @Test
    void brokenRecordsAreInvalidEvents() {
        assertThatThrownBy(() -> GoldPatientChangedEvent.parse(null, mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> GoldPatientChangedEvent.parse("nope", mapper)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> GoldPatientChangedEvent.parse("{\"silverPatientId\":3}", mapper))
                .isInstanceOf(InvalidEventException.class).hasMessageContaining("masterPatientId");
    }
}
