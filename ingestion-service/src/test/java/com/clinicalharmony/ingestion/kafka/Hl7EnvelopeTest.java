package com.clinicalharmony.ingestion.kafka;

import com.clinicalharmony.ingestion.service.Hl7SampleMessages;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Hl7EnvelopeTest {

    @Test
    void readsMshAndPidFromAdt() {
        Hl7Envelope e = Hl7Envelope.peek(Hl7SampleMessages.ADT_A01);
        assertThat(e.sendingApplication()).isEqualTo("REG_SYSTEM");
        assertThat(e.sendingFacility()).isEqualTo("CITY_HOSPITAL");
        assertThat(e.messageType()).isEqualTo("ADT^A01");
        assertThat(e.triggerEvent()).isEqualTo("A01");
        assertThat(e.controlId()).isEqualTo("MSG00001");
        assertThat(e.patientId()).isEqualTo("MRN100234");
    }

    @Test
    void acceptsNewlineTerminatedSegments() {
        Hl7Envelope e = Hl7Envelope.peek(Hl7SampleMessages.ORU_R01.replace("\r", "\n"));
        assertThat(e.messageType()).isEqualTo("ORU^R01");
        assertThat(e.patientId()).isEqualTo("MRN100234");
    }

    @Test
    void takesFirstRepetitionOfPid3() {
        String msg = "MSH|^~\\&|A|F|||20260101||ADT^A08|C1|P|2.5\rPID|1||MRN1^^^F^MR~SSN9^^^SSA^SS||X^Y\r";
        assertThat(Hl7Envelope.peek(msg).patientId()).isEqualTo("MRN1");
    }

    @Test
    void missingPidLeavesPatientIdNull() {
        String msg = "MSH|^~\\&|A|F|||20260101||ADT^A01|C2|P|2.5\rEVN|A01\r";
        Hl7Envelope e = Hl7Envelope.peek(msg);
        assertThat(e.controlId()).isEqualTo("C2");
        assertThat(e.patientId()).isNull();
    }

    @Test
    void garbageGivesAllNullsWithoutThrowing() {
        Hl7Envelope e = Hl7Envelope.peek(Hl7SampleMessages.MALFORMED);
        assertThat(e).isEqualTo(new Hl7Envelope(null, null, null, null, null, null));
        assertThat(Hl7Envelope.peek("")).isEqualTo(e);
        assertThat(Hl7Envelope.peek(null)).isEqualTo(e);
    }

    @Test
    void partitionKeyFallsBackFromPidToControlIdToChecksum() {
        assertThat(Hl7RawPublisher.partitionKey("CITY_HOSPITAL", Hl7Envelope.peek(Hl7SampleMessages.ADT_A01), "abc"))
                .isEqualTo("CITY_HOSPITAL|MRN100234");
        assertThat(Hl7RawPublisher.partitionKey("F",
                Hl7Envelope.peek("MSH|^~\\&|A|F|||20260101||ADT^A01|C2|P|2.5\r"), "abc"))
                .isEqualTo("F|msh10:C2");
        assertThat(Hl7RawPublisher.partitionKey("X", Hl7Envelope.peek("junk"), "abc")).isEqualTo("sha256:abc");
    }
}
