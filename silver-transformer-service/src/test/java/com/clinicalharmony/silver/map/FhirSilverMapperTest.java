package com.clinicalharmony.silver.map;

import com.clinicalharmony.silver.hl7.MappingException;
import com.clinicalharmony.silver.hl7.SilverRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class FhirSilverMapperTest {

    private final FhirSilverMapper mapper = new FhirSilverMapper(new ObjectMapper());

    public static final String BUNDLE = """
            {"resourceType":"Bundle","id":"b1","type":"collection","entry":[
             {"fullUrl":"urn:uuid:p-1","resource":{"resourceType":"Patient","id":"p1",
               "identifier":[{"system":"urn:other","value":"X-9"},
                             {"type":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/v2-0203","code":"MR"}]},"value":"PC100200"}],
               "name":[{"family":"Rivera","given":["Ana","Lucia"]}],"gender":"female","birthDate":"1978-04-12",
               "address":[{"line":["742 Juniper Lane"],"city":"Springfield","state":"IL","postalCode":"62704"}],
               "telecom":[{"system":"email","value":"a@x.org"},{"system":"phone","value":"2175550142"}]}},
             {"resource":{"resourceType":"Condition","id":"c1","subject":{"reference":"urn:uuid:p-1"},
               "code":{"coding":[{"system":"http://hl7.org/fhir/sid/icd-10-cm","code":"E11.9","display":"Type 2 diabetes"}]},
               "onsetDateTime":"2019-03-02","recordedDate":"2019-03-05T10:00:00Z"}},
             {"resource":{"resourceType":"Observation","id":"o1","status":"final","subject":{"reference":"Patient/p1"},
               "code":{"coding":[{"system":"http://loinc.org","code":"4548-4","display":"Hemoglobin A1c"}]},
               "valueQuantity":{"value":7.2,"unit":"%"},"effectiveDateTime":"2026-09-10T08:30:00-05:00",
               "referenceRange":[{"low":{"value":4.0},"high":{"value":5.6}}],
               "interpretation":[{"coding":[{"code":"H"}]}]}},
             {"resource":{"resourceType":"MedicationRequest","id":"m1","status":"active","intent":"order",
               "subject":{"reference":"Patient/p1"},
               "medicationCodeableConcept":{"coding":[{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"861007","display":"metformin 500 MG"}]},
               "authoredOn":"2019-03-02","dosageInstruction":[{"text":"1 tablet twice daily"}]}},
             {"resource":{"resourceType":"AllergyIntolerance","id":"a1","patient":{"reference":"Patient/p1"},
               "category":["medication"],"criticality":"high",
               "code":{"coding":[{"system":"http://www.nlm.nih.gov/research/umls/rxnorm","code":"7980","display":"Penicillin G"}]},
               "reaction":[{"manifestation":[{"text":"Hives"}],"severity":"moderate"}]}},
             {"resource":{"resourceType":"Encounter","id":"e1"}}]}""";

    @Test
    void bundleMapsPatientAndEveryClinicalResource() {
        SilverRecord r = mapper.map(BUNDLE, "PARTNER_CLINIC");
        assertThat(r.messageType()).isEqualTo("FHIR Bundle");
        assertThat(r.updatesDemographics()).isTrue();
        SilverRecord.Patient p = r.patient();
        assertThat(p.sourceSystem()).isEqualTo("PARTNER_CLINIC");
        assertThat(p.sourcePatientId()).isEqualTo("PC100200");   // the MR identifier, not the first one
        assertThat(p.firstName()).isEqualTo("Ana");
        assertThat(p.lastName()).isEqualTo("Rivera");
        assertThat(p.dateOfBirth()).isEqualTo(LocalDate.of(1978, 4, 12));
        assertThat(p.gender()).isEqualTo("female");
        assertThat(p.state()).isEqualTo("IL");
        assertThat(p.phone()).isEqualTo("2175550142");

        assertThat(r.conditions()).singleElement().satisfies(c -> {
            assertThat(c.codeSystem()).isEqualTo("ICD-10");
            assertThat(c.code()).isEqualTo("E11.9");
            assertThat(c.onsetDate()).isEqualTo(LocalDate.of(2019, 3, 2));
            assertThat(c.recordedDate()).isEqualTo(LocalDate.of(2019, 3, 5));
        });
        assertThat(r.observations()).singleElement().satisfies(o -> {
            assertThat(o.code()).isEqualTo("4548-4");
            assertThat(o.valueNumeric()).isEqualByComparingTo("7.2");
            assertThat(o.unit()).isEqualTo("%");
            assertThat(o.effectiveAt()).isEqualTo(Instant.parse("2026-09-10T13:30:00Z"));
            assertThat(o.referenceLow()).isEqualByComparingTo(new BigDecimal("4.0"));
            assertThat(o.referenceHigh()).isEqualByComparingTo(new BigDecimal("5.6"));
            assertThat(o.abnormalFlag()).isEqualTo("H");
        });
        assertThat(r.medications()).singleElement().satisfies(m -> {
            assertThat(m.rxnormCode()).isEqualTo("861007");
            assertThat(m.status()).isEqualTo("active");
            assertThat(m.startDate()).isEqualTo(LocalDate.of(2019, 3, 2));
            assertThat(m.dosageText()).isEqualTo("1 tablet twice daily");
        });
        assertThat(r.allergies()).singleElement().satisfies(a -> {
            assertThat(a.codeSystem()).isEqualTo("RXNORM");
            assertThat(a.code()).isEqualTo("7980");
            assertThat(a.allergyType()).isEqualTo("DA");
            assertThat(a.severity()).isEqualTo("MO");
            assertThat(a.reaction()).isEqualTo("Hives");
        });
    }

    @Test
    void lonePatientMapsWithFirstIdentifier() {
        SilverRecord r = mapper.map("""
                {"resourceType":"Patient","identifier":[{"value":"F-1"}],"name":[{"family":"Doe","given":["Jo"]}],
                 "birthDate":"1980-01-01"}""", "SRC");
        assertThat(r.messageType()).isEqualTo("FHIR Patient");
        assertThat(r.patient().sourcePatientId()).isEqualTo("F-1");
        assertThat(r.patient().gender()).isEqualTo("unknown");
    }

    @Test
    void resourceReferencingAnotherPatientFails() {
        String bad = BUNDLE.replace("\"status\":\"final\",\"subject\":{\"reference\":\"Patient/p1\"}",
                "\"status\":\"final\",\"subject\":{\"reference\":\"Patient/someone-else\"}");
        assertThatThrownBy(() -> mapper.map(bad, "S")).isInstanceOf(MappingException.class)
                .hasMessage("Observation/o1 references Patient/someone-else, not the Bundle's Patient");
    }

    @Test
    void strictOnWhatSilverNeeds() {
        assertThatThrownBy(() -> mapper.map("{\"resourceType\":\"Condition\"}", "S"))
                .hasMessageContaining("send a Bundle with its Patient");
        assertThatThrownBy(() -> mapper.map("{\"resourceType\":\"Bundle\",\"entry\":[]}", "S"))
                .hasMessage("Bundle has no Patient");
        assertThatThrownBy(() -> mapper.map(BUNDLE.replace("\"birthDate\":\"1978-04-12\",", ""), "S"))
                .hasMessage("Patient.birthDate is missing");
        assertThatThrownBy(() -> mapper.map(BUNDLE.replace("http://hl7.org/fhir/sid/icd-10-cm", "urn:local"), "S"))
                .hasMessage("Condition/c1 has no ICD-10-CM or SNOMED CT coding");
        assertThatThrownBy(() -> mapper.map(BUNDLE.replace("\"authoredOn\":\"2019-03-02\",", ""), "S"))
                .hasMessageContaining("MedicationRequest/m1 has no authoredOn");
        assertThatThrownBy(() -> mapper.map(BUNDLE.replace("\"gender\":\"female\"", "\"gender\":\"F\""), "S"))
                .hasMessageContaining("Patient.gender 'f'");
    }

    @Test
    void unknownCodesStillMapSoValidationCanFlagThem() {
        // A code the ontology doesn't know is a FINDING, not a mapping failure.
        SilverRecord r = mapper.map(BUNDLE.replace("\"E11.9\"", "\"Z99.999\""), "S");
        assertThat(r.conditions().get(0).code()).isEqualTo("Z99.999");
    }
}
