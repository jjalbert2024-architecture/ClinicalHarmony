package com.clinicalharmony.silver.hl7;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hl7SilverMapperTest {

    private final Hl7SilverMapper mapper = new Hl7SilverMapper();

    @Test
    void adtMapsPatientDiagnosesAndAllergy() {
        SilverRecord r = mapper.map(Hl7Samples.adtA01("C1"), "CITY_HOSPITAL");

        assertThat(r.messageType()).isEqualTo("ADT^A01");
        assertThat(r.controlId()).isEqualTo("C1");
        assertThat(r.updatesDemographics()).isTrue();
        assertThat(r.messageTime()).isEqualTo(Instant.parse("2026-11-02T07:34:51Z"));
        SilverRecord.Patient p = r.patient();
        assertThat(p.sourceSystem()).isEqualTo("CITY_HOSPITAL");
        assertThat(p.sourcePatientId()).isEqualTo("CH645175");
        assertThat(p.mrn()).isEqualTo("CH645175");
        assertThat(p.firstName()).isEqualTo("JAMES");
        assertThat(p.lastName()).isEqualTo("SINGH");
        assertThat(p.dateOfBirth()).isEqualTo(LocalDate.of(1935, 2, 25));
        assertThat(p.gender()).isEqualTo("male");
        assertThat(p.addressLine1()).isEqualTo("5977 LAKE DR");
        assertThat(p.city()).isEqualTo("GREENVILLE");
        assertThat(p.state()).isEqualTo("SC");
        assertThat(p.postalCode()).isEqualTo("29608");
        assertThat(p.phone()).isEqualTo("5555539837");

        assertThat(r.conditions()).extracting(SilverRecord.Condition::code).containsExactly("K21.9", "I10");
        SilverRecord.Condition gerd = r.conditions().get(0);
        assertThat(gerd.codeSystem()).isEqualTo("ICD-10");
        assertThat(gerd.display()).isEqualTo("Gastro-esophageal reflux disease without esophagitis");
        assertThat(gerd.onsetDate()).isEqualTo(LocalDate.of(2026, 11, 2));

        assertThat(r.allergies()).singleElement().satisfies(a -> {
            assertThat(a.codeSystem()).isEqualTo("RXNORM");
            assertThat(a.code()).isEqualTo("723");
            assertThat(a.display()).isEqualTo("Amoxicillin");
            assertThat(a.allergyType()).isEqualTo("DA");
            assertThat(a.severity()).isEqualTo("SV");
            assertThat(a.reaction()).isEqualTo("HIVES");
        });
        assertThat(r.observations()).isEmpty();
        assertThat(r.medications()).isEmpty();
    }

    @Test
    void oruMapsObservationsWithRangesAndTimes() {
        SilverRecord r = mapper.map(Hl7Samples.oruR01("C2"), "REGIONAL_LAB");

        assertThat(r.updatesDemographics()).isFalse();
        // the lab result belongs to the HOSPITAL's patient (PID-3.4), not to REGIONAL_LAB (MSH-4)
        assertThat(r.patient().sourceSystem()).isEqualTo("CITY_HOSPITAL");
        assertThat(r.observations()).hasSize(3);

        SilverRecord.Observation glucose = r.observations().get(0);
        assertThat(glucose.code()).isEqualTo("2345-7");
        assertThat(glucose.valueNumeric()).isEqualByComparingTo("182");
        assertThat(glucose.unit()).isEqualTo("mg/dL");
        assertThat(glucose.referenceLow()).isEqualByComparingTo("70");
        assertThat(glucose.referenceHigh()).isEqualByComparingTo("99");
        assertThat(glucose.abnormalFlag()).isEqualTo("H");
        assertThat(glucose.status()).isEqualTo("final");
        assertThat(glucose.effectiveAt()).isEqualTo(Instant.parse("2026-11-02T06:20:00Z"));   // OBX-14

        assertThat(r.observations().get(1).effectiveAt()).isEqualTo(Instant.parse("2026-11-02T06:27:55Z")); // OBR-7
        SilverRecord.Observation potassium = r.observations().get(2);
        assertThat(potassium.valueNumeric()).isNull();
        assertThat(potassium.valueText()).isEqualTo("hemolyzed");
        assertThat(r.conditions()).isEmpty();
    }

    @Test
    void rdeMapsTheOrderToAMedication() {
        SilverRecord r = mapper.map(Hl7Samples.rdeO11("C3"), "CITY_PHARMACY");

        assertThat(r.patient().sourceSystem()).isEqualTo("CITY_HOSPITAL");
        assertThat(r.medications()).singleElement().satisfies(m -> {
            assertThat(m.rxnormCode()).isEqualTo("723");
            assertThat(m.display()).isEqualTo("Amoxicillin 500 MG Oral Capsule");
            assertThat(m.dosageText()).isEqualTo("500 mg, Take 1 capsule by mouth three times daily for 7 days");
            assertThat(m.status()).isEqualTo("active");
            assertThat(m.startDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        });
    }

    @Test
    void sendingFacilityIsTheSourceWhenPidHasNoAssigningAuthority() {
        String msg = Hl7Samples.adtA01("C4").replace("CH645175^^^CITY_HOSPITAL^MR", "CH645175");
        assertThat(mapper.map(msg, "X").patient().sourceSystem()).isEqualTo("CITY_HOSPITAL");
    }

    @Test
    void acceptsNewlineSegmentTerminators() {
        assertThat(mapper.map(Hl7Samples.adtA01("C5").replace("\r", "\n"), "X").conditions()).hasSize(2);
    }

    @Test
    void timestampsHonourAnExplicitZone() {
        assertThat(Hl7SilverMapper.timestamp("202611020730-0500", "T")).isEqualTo(Instant.parse("2026-11-02T12:30:00Z"));
        assertThat(Hl7SilverMapper.timestamp("20261102", "T")).isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
        assertThat(Hl7SilverMapper.timestamp(null, "T")).isNull();
        assertThatThrownBy(() -> Hl7SilverMapper.timestamp("2026-11-02", "PID-7")).isInstanceOf(MappingException.class)
                .hasMessageContaining("PID-7");
        assertThatThrownBy(() -> Hl7SilverMapper.timestamp("20261340", "PID-7")).isInstanceOf(MappingException.class);
    }

    @Test
    void orderControlMapsToMedicationStatus() {
        assertThat(Hl7SilverMapper.medicationStatus(null)).isEqualTo("active");
        assertThat(Hl7SilverMapper.medicationStatus("NW")).isEqualTo("active");
        assertThat(Hl7SilverMapper.medicationStatus("DC")).isEqualTo("stopped");
        assertThat(Hl7SilverMapper.medicationStatus("HD")).isEqualTo("on-hold");
        assertThatThrownBy(() -> Hl7SilverMapper.medicationStatus("ZZ")).isInstanceOf(MappingException.class);
    }

    // ---- bad data → MappingException (Bronze FAILED) ---------------------------------------------

    @Test
    void unsupportedMessageTypeFails() {
        String siu = Hl7Samples.adtA01("C6").replace("ADT^A01^ADT_A01", "SIU^S12^SIU_S12");
        assertThatThrownBy(() -> mapper.map(siu, "X")).isInstanceOf(MappingException.class)
                .hasMessageContaining("SIU^S12 is not mapped");
    }

    @Test
    void missingDateOfBirthFails() {
        String msg = Hl7Samples.adtA01("C7").replace("||19350225|M|", "|||M|");
        assertThatThrownBy(() -> mapper.map(msg, "X")).isInstanceOf(MappingException.class).hasMessageContaining("PID-7");
    }

    @Test
    void missingNameFails() {
        String msg = Hl7Samples.adtA01("C8").replace("SINGH^JAMES^D", "SINGH");
        assertThatThrownBy(() -> mapper.map(msg, "X")).isInstanceOf(MappingException.class).hasMessageContaining("PID-5");
    }

    @Test
    void unknownDiagnosisCodingSystemFails() {
        String msg = Hl7Samples.adtA01("C9").replace("I10^Essential (primary) hypertension^I10", "I10^Essential^I9");
        assertThatThrownBy(() -> mapper.map(msg, "X")).isInstanceOf(MappingException.class)
                .hasMessageContaining("DG1-3.3").hasMessageContaining("I9");
    }

    @Test
    void nonLoincObservationFails() {
        String msg = Hl7Samples.oruR01("C10").replace("2951-2^Sodium^LN", "NA^Sodium^L");
        assertThatThrownBy(() -> mapper.map(msg, "X")).isInstanceOf(MappingException.class).hasMessageContaining("not LOINC");
    }

    @Test
    void nonRxNormOrderFails() {
        String msg = Hl7Samples.rdeO11("C11").replace("^RXNORM|500", "^NDC|500");
        assertThatThrownBy(() -> mapper.map(msg, "X")).isInstanceOf(MappingException.class).hasMessageContaining("not RxNorm");
    }

    @Test
    void longStateFails() {
        String msg = Hl7Samples.adtA01("C12").replace("^SC^29608", "^SOUTH CAROLINA^29608");
        assertThatThrownBy(() -> mapper.map(msg, "X")).isInstanceOf(MappingException.class).hasMessageContaining("PID-11.4");
    }

    @Test
    void unknownSexCodeFails() {
        String msg = Hl7Samples.adtA01("C13").replace("|19350225|M|", "|19350225|Q|");
        assertThatThrownBy(() -> mapper.map(msg, "X")).isInstanceOf(MappingException.class).hasMessageContaining("PID-8");
    }

    @Test
    void emptyOrUnparseablePayloadFails() {
        assertThatThrownBy(() -> mapper.map("", "X")).isInstanceOf(MappingException.class);
        assertThatThrownBy(() -> mapper.map("hello world\r", "X")).isInstanceOf(MappingException.class);
    }

    @Test
    void numericValueTooPreciseIsKeptAsText() {
        String msg = Hl7Samples.oruR01("C14").replace("||182|mg/dL|", "||182.123456|mg/dL|");
        SilverRecord.Observation o = mapper.map(msg, "X").observations().get(0);
        assertThat(o.valueNumeric()).isNull();
        assertThat(o.valueText()).isEqualTo("182.123456");
    }
}
