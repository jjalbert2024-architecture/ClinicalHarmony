package com.clinicalharmony.fhircompliance.service.ccda;

import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import com.clinicalharmony.fhircompliance.validation.UsCoreValidatorFixture;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CcdaPatientMapperTest {

    private final CcdaPatientMapper mapper = new CcdaPatientMapper();

    private static Mapped map(byte[] document) {
        CcdaXml xml = CcdaXml.parse(document);
        CcdaMappingContext ctx = new CcdaMappingContext(xml, "doc");
        Patient patient = new CcdaPatientMapper().map(xml.element(xml.root(), "hl7:recordTarget/hl7:patientRole"), ctx);
        return new Mapped(patient, ctx);
    }

    private record Mapped(Patient patient, CcdaMappingContext ctx) {
    }

    @Test
    void mapsFullDemographicsAndIsUsCoreCompliant() {
        Patient patient = map(CcdaFixtures.load("ccd_compliant.xml")).patient();

        assertThat(patient.getMeta().getProfile().get(0).getValue()).isEqualTo(UsCoreProfiles.PATIENT);
        assertThat(patient.getIdentifierFirstRep().getSystem()).isEqualTo("urn:oid:2.16.840.1.113883.19.5.99999.2");
        assertThat(patient.getIdentifierFirstRep().getValue()).isEqualTo("MRN-CCDA-1001");
        HumanName name = patient.getNameFirstRep();
        assertThat(name.getUse()).isEqualTo(HumanName.NameUse.OFFICIAL);
        assertThat(name.getFamily()).isEqualTo("Rivera");
        assertThat(name.getGivenAsSingleString()).isEqualTo("Ana Lucia");
        assertThat(patient.getGender()).isEqualTo(AdministrativeGender.FEMALE);
        assertThat(patient.getBirthDateElement().getValueAsString()).isEqualTo("1978-04-12");
        assertThat(patient.getAddressFirstRep().getLine().get(0).getValue()).isEqualTo("742 Juniper Lane");
        assertThat(patient.getAddressFirstRep().getPostalCode()).isEqualTo("62704");
        assertThat(patient.getTelecom()).extracting(ContactPoint::getSystem, ContactPoint::getValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ContactPoint.ContactPointSystem.PHONE, "+1(217)555-0142"),
                        org.assertj.core.groups.Tuple.tuple(ContactPoint.ContactPointSystem.EMAIL, "ana.rivera@example.org"));
        assertThat(patient.getCommunicationFirstRep().getLanguage().getCodingFirstRep().getCode()).isEqualTo("es");
        assertThat(patient.getCommunicationFirstRep().getPreferred()).isTrue();

        Extension race = patient.getExtensionByUrl(CcdaPatientMapper.US_CORE_RACE);
        assertThat(((Coding) race.getExtensionByUrl("ombCategory").getValue()).getCode()).isEqualTo("2106-3");
        assertThat(race.getExtensionByUrl("text").getValue().primitiveValue()).isEqualTo("White");
        Extension ethnicity = patient.getExtensionByUrl(CcdaPatientMapper.US_CORE_ETHNICITY);
        assertThat(((Coding) ethnicity.getExtensionByUrl("ombCategory").getValue()).getCode()).isEqualTo("2135-2");

        FhirValidationOutcome outcome = UsCoreValidatorFixture.validator().validate(patient);
        assertThat(outcome.violations()).filteredOn(v -> "ERROR".equals(v.severity())).isEmpty();
        assertThat(outcome.compliant()).isTrue();
    }

    @Test
    void missingGenderIsLeftMissingAndFlaggedByTheValidator() {
        Patient patient = map(CcdaFixtures.load("ccd_noncompliant.xml")).patient();

        assertThat(patient.hasGender()).isFalse();
        FhirValidationOutcome outcome = UsCoreValidatorFixture.validator().validate(patient);
        assertThat(outcome.compliant()).isFalse();
        assertThat(outcome.violations()).filteredOn(v -> "ERROR".equals(v.severity()))
                .extracting(v -> v.location()).containsExactly("Patient.gender");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "M, NULL, MALE",
            "F, NULL, FEMALE",
            "UN, NULL, OTHER",
            "NULL, UNK, UNKNOWN",
            "NULL, ASKU, UNKNOWN",
            "X, NULL, NULL",
            "NULL, NULL, NULL"
    })
    void mapsAdministrativeGender(String code, String nullFlavor, AdministrativeGender expected) {
        assertThat(CcdaPatientMapper.gender(code, nullFlavor)).isEqualTo(expected);
    }

    @Test
    void nullFlavoredNameBecomesDataAbsentReasonAndSatisfiesUsCore() {
        Patient patient = map(CcdaFixtures.document("""
                <patientRole>
                  <id root="2.16.840.1.113883.19.5.99999.2" extension="UT-2"/>
                  <patient>
                    <name nullFlavor="UNK"/>
                    <administrativeGenderCode nullFlavor="UNK"/>
                  </patient>
                </patientRole>""", "")).patient();

        HumanName name = patient.getNameFirstRep();
        assertThat(name.hasFamily()).isFalse();
        assertThat(name.getExtensionByUrl(CcdaMappingContext.DATA_ABSENT_REASON).getValue().primitiveValue()).isEqualTo("unknown");
        assertThat(patient.getGender()).isEqualTo(AdministrativeGender.UNKNOWN);
        assertThat(UsCoreValidatorFixture.validator().validate(patient).compliant()).isTrue();
    }

    @Test
    void detailedRaceCodeIsMappedAndNoted() {
        Mapped mapped = map(CcdaFixtures.document("""
                <patientRole>
                  <id root="2.16.840.1.113883.19.5.99999.2" extension="UT-3"/>
                  <patient>
                    <name><given>A</given><family>B</family></name>
                    <administrativeGenderCode code="F"/>
                    <raceCode code="2106-3" codeSystem="2.16.840.1.113883.6.238" displayName="White"/>
                    <sdtc:raceCode code="2108-9" codeSystem="2.16.840.1.113883.6.238" displayName="European"/>
                  </patient>
                </patientRole>""", ""));

        Extension race = mapped.patient().getExtensionByUrl(CcdaPatientMapper.US_CORE_RACE);
        assertThat(((Coding) race.getExtensionByUrl("detailed").getValue()).getCode()).isEqualTo("2108-9");
        assertThat(race.getExtensionByUrl("text").getValue().primitiveValue()).isEqualTo("White, European");
        assertThat(mapped.ctx().notes()).singleElement()
                .satisfies(n -> assertThat(n.message()).contains("2108-9").contains("VSAC"));
    }

    @Test
    void bareRootIdentifierUsesUriSystem() {
        Patient patient = map(CcdaFixtures.document("""
                <patientRole>
                  <id root="AB1791B0-5C71-11DB-B0DE-0800200C9A66"/>
                  <patient><name><family>X</family></name><administrativeGenderCode code="M"/></patient>
                </patientRole>""", "")).patient();

        assertThat(patient.getIdentifierFirstRep().getSystem()).isEqualTo("urn:ietf:rfc:3986");
        assertThat(patient.getIdentifierFirstRep().getValue()).isEqualTo("urn:uuid:ab1791b0-5c71-11db-b0de-0800200c9a66");
    }

    @Test
    void sameSourceIdentityGivesSameFhirIdAcrossDocuments() {
        String a = map(CcdaFixtures.document(CcdaFixtures.MINIMAL_PATIENT_ROLE, "")).patient().getIdPart();
        CcdaXml xml = CcdaXml.parse(CcdaFixtures.document(CcdaFixtures.MINIMAL_PATIENT_ROLE, ""));
        String b = new CcdaPatientMapper().map(xml.element(xml.root(), "hl7:recordTarget/hl7:patientRole"),
                new CcdaMappingContext(xml, "a-different-document")).getIdPart();

        assertThat(a).isEqualTo(b).matches("[0-9a-f-]{36}");
    }
}
