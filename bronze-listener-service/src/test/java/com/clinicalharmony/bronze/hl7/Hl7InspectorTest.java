package com.clinicalharmony.bronze.hl7;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Hl7InspectorTest {

    private final Hl7Inspector inspector = new Hl7Inspector();

    @Test
    void validAdtParses() {
        Hl7Inspector.Result r = inspector.inspect(Hl7Samples.ADT_A01);
        assertThat(r.parsed()).isTrue();
        assertThat(r.messageType()).isEqualTo("ADT^A01");
        assertThat(r.sendingFacility()).isEqualTo("CITY_HOSPITAL");
        assertThat(r.controlId()).isEqualTo("MSG00001");
        assertThat(r.error()).isNull();
    }

    @Test
    void newlineTerminatedOruParses() {
        Hl7Inspector.Result r = inspector.inspect(Hl7Samples.ORU_R01.replace("\r", "\n"));
        assertThat(r.parsed()).isTrue();
        assertThat(r.messageType()).isEqualTo("ORU^R01");
    }

    @Test
    void garbageIsQuarantinedNotThrown() {
        Hl7Inspector.Result r = inspector.inspect(Hl7Samples.NOT_HL7);
        assertThat(r.parsed()).isFalse();
        assertThat(r.error()).isNotBlank();
    }

    @Test
    void missingControlIdIsQuarantined() {
        assertThat(inspector.inspect(Hl7Samples.NO_CONTROL_ID).error()).contains("MSH-10");
    }

    @Test
    void missingMessageTypeIsQuarantined() {
        assertThat(inspector.inspect(Hl7Samples.NO_MESSAGE_TYPE).parsed()).isFalse();
    }

    @Test
    void emptyIsQuarantined() {
        assertThat(inspector.inspect("  ").error()).isEqualTo("Empty payload");
    }
}
