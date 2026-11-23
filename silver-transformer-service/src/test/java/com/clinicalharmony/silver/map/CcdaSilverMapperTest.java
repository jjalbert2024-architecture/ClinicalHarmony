package com.clinicalharmony.silver.map;

import com.clinicalharmony.silver.hl7.MappingException;
import com.clinicalharmony.silver.hl7.SilverRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class CcdaSilverMapperTest {

    private final CcdaSilverMapper mapper = new CcdaSilverMapper();

    public static String fixture() throws IOException {
        try (InputStream in = CcdaSilverMapperTest.class.getResourceAsStream("/ccda/ccd_compliant.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void ccdMapsPatientProblemsMedicationsResults() throws IOException {
        SilverRecord r = mapper.map(fixture(), "NORTH_CLINIC");
        assertThat(r.messageType()).isEqualTo("C-CDA");
        assertThat(r.controlId()).isEqualTo("CCD-CH-0001");
        assertThat(r.patient()).satisfies(p -> {
            assertThat(p.sourceSystem()).isEqualTo("NORTH_CLINIC");
            assertThat(p.sourcePatientId()).isEqualTo("MRN-CCDA-1001");
            assertThat(p.firstName()).isEqualTo("Ana");
            assertThat(p.lastName()).isEqualTo("Rivera");
            assertThat(p.dateOfBirth()).isEqualTo(LocalDate.of(1978, 4, 12));
            assertThat(p.gender()).isEqualTo("female");
            assertThat(p.addressLine1()).isEqualTo("742 Juniper Lane");
            assertThat(p.state()).isEqualTo("IL");
            assertThat(p.phone()).isEqualTo("+1(217)555-0142");
        });
        assertThat(r.conditions()).extracting(SilverRecord.Condition::codeSystem, SilverRecord.Condition::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("SNOMED-CT", "44054006"),
                        org.assertj.core.groups.Tuple.tuple("ICD-10", "I10"),
                        org.assertj.core.groups.Tuple.tuple("SNOMED-CT", "10509002"));
        assertThat(r.conditions().get(0).onsetDate()).isEqualTo(LocalDate.of(2019, 3, 2));
        assertThat(r.medications()).extracting(SilverRecord.Medication::rxnormCode).hasSize(2).contains("861007");
        assertThat(r.medications().get(0).dosageText()).isEqualTo("1, Take 1 tablet by mouth every 12 hours with meals");
        assertThat(r.medications().get(0).startDate()).isEqualTo(LocalDate.of(2019, 3, 2));
        assertThat(r.observations()).extracting(SilverRecord.Observation::code).containsExactly("4548-4", "2345-7", "2160-0");
        SilverRecord.Observation a1c = r.observations().get(0);
        assertThat(a1c.valueNumeric()).isEqualByComparingTo("7.2");
        assertThat(a1c.unit()).isEqualTo("%");
        assertThat(a1c.effectiveAt()).isEqualTo(Instant.parse("2026-09-10T13:30:00Z"));
        assertThat(a1c.referenceHigh()).isEqualByComparingTo("5.6");
        assertThat(a1c.abnormalFlag()).isEqualTo("H");
        // Creatinine has no effectiveTime of its own: the organizer's
        assertThat(r.observations().get(2).effectiveAt()).isEqualTo(Instant.parse("2026-09-10T13:30:00Z"));
    }

    @Test
    void allergiesSectionMaps() throws IOException {
        String allergies = """
                <component><section><code code="48765-2" codeSystem="2.16.840.1.113883.6.1"/>
                  <entry><act classCode="ACT" moodCode="EVN"><entryRelationship typeCode="SUBJ"><observation classCode="OBS" moodCode="EVN">
                    <code code="ASSERTION" codeSystem="2.16.840.1.113883.5.4"/>
                    <value xsi:type="CD" code="416098002" codeSystem="2.16.840.1.113883.6.96"/>
                    <participant typeCode="CSM"><participantRole classCode="MANU"><playingEntity classCode="MMAT">
                      <code code="7980" codeSystem="2.16.840.1.113883.6.88" displayName="Penicillin G"/></playingEntity></participantRole></participant>
                    <entryRelationship typeCode="MFST"><observation classCode="OBS" moodCode="EVN"><code code="ASSERTION"/>
                      <value xsi:type="CD" code="247472004" codeSystem="2.16.840.1.113883.6.96" displayName="Hives"/></observation></entryRelationship>
                    <entryRelationship typeCode="SUBJ"><observation classCode="OBS" moodCode="EVN"><code code="SEV"/>
                      <value xsi:type="CD" code="24484000" codeSystem="2.16.840.1.113883.6.96" displayName="Severe"/></observation></entryRelationship>
                  </observation></entryRelationship></act></entry></section></component>
                """;
        String doc = fixture().replace("</structuredBody>", allergies + "</structuredBody>");
        SilverRecord.Allergy a = mapper.map(doc, "S").allergies().get(0);
        assertThat(a.codeSystem()).isEqualTo("RXNORM");
        assertThat(a.code()).isEqualTo("7980");
        assertThat(a.allergyType()).isEqualTo("DA");
        assertThat(a.severity()).isEqualTo("SV");
        assertThat(a.reaction()).isEqualTo("Hives");
    }

    @Test
    void allergyWithoutSeverityOrTypeStillMaps() throws IOException {
        // Found live: Map.of(...).get(null) threw a NullPointerException → retried → dead-lettered.
        String allergies = """
                <component><section><code code="48765-2" codeSystem="2.16.840.1.113883.6.1"/>
                  <entry><act classCode="ACT" moodCode="EVN"><entryRelationship typeCode="SUBJ"><observation classCode="OBS" moodCode="EVN">
                    <participant typeCode="CSM"><participantRole><playingEntity>
                      <code code="723" codeSystem="2.16.840.1.113883.6.88" displayName="Amoxicillin"/></playingEntity></participantRole></participant>
                  </observation></entryRelationship></act></entry></section></component>
                """;
        SilverRecord.Allergy a = mapper.map(fixture().replace("</structuredBody>", allergies + "</structuredBody>"), "S")
                .allergies().get(0);
        assertThat(a.code()).isEqualTo("723");
        assertThat(a.severity()).isNull();
        assertThat(a.allergyType()).isNull();
    }

    @Test
    void strictOnWhatSilverNeeds() throws IOException {
        assertThatThrownBy(() -> mapper.map("<x/>", "S")).isInstanceOf(MappingException.class)
                .hasMessage("Not an HL7 v3 ClinicalDocument");
        assertThatThrownBy(() -> mapper.map(fixture().replace("<birthTime value=\"19780412\"/>", ""), "S"))
                .hasMessage("patient/birthTime is missing");
        assertThatThrownBy(() -> mapper.map(fixture().replace("codeSystem=\"2.16.840.1.113883.6.88\"", "codeSystem=\"9.9\""), "S"))
                .hasMessageContaining("is not coded in RxNorm");
        assertThatThrownBy(() -> mapper.map("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>", "S"))
                .hasMessageContaining("not well-formed XML");
    }
}
