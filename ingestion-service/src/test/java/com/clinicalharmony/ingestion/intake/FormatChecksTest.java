package com.clinicalharmony.ingestion.intake;

import com.clinicalharmony.ingestion.service.Hl7SampleMessages;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FormatChecksTest {

    private final FormatChecks checks = new FormatChecks(new ObjectMapper());

    @Test
    void hl7ParsesAndReportsTypeAndControlId() {
        SyntaxCheck c = checks.hl7(Hl7SampleMessages.ADT_A01);
        assertThat(c.valid()).isTrue();
        assertThat(c.details()).containsEntry("hl7MessageType", "ADT^A01").containsEntry("controlId", "MSG00001");
    }

    @Test
    void hl7ToleratesNewlineTerminators() {
        assertThat(checks.hl7(Hl7SampleMessages.ORU_R01.replace("\r", "\n")).valid()).isTrue();
    }

    @Test
    void hl7RejectsGarbageAndEmptyControlId() {
        assertThat(checks.hl7(Hl7SampleMessages.MALFORMED).valid()).isFalse();
        assertThat(checks.hl7("").error()).isEqualTo("Empty payload");
        SyntaxCheck noId = checks.hl7(Hl7SampleMessages.ADT_A01.replace("|MSG00001|", "||"));
        assertThat(noId.valid()).isFalse();
        assertThat(noId.error()).contains("MSH-10");
    }

    @Test
    void fhirPatientGivesIdentifierAsPatientKey() {
        SyntaxCheck c = checks.fhir(IntakeSamples.FHIR_PATIENT);
        assertThat(c.valid()).isTrue();
        assertThat(c.patientId()).isEqualTo("MRN-F-1");
        assertThat(c.details()).containsEntry("resourceType", "Patient").containsEntry("resourceCount", 1);
    }

    @Test
    void fhirBundleCountsEntriesAndFindsItsPatient() {
        SyntaxCheck c = checks.fhir(IntakeSamples.FHIR_BUNDLE);
        assertThat(c.details()).containsEntry("resourceType", "Bundle").containsEntry("resourceCount", 2);
        assertThat(c.patientId()).isEqualTo("p2");
    }

    @Test
    void fhirRejectsNonJsonAndMissingResourceType() {
        assertThat(checks.fhir("{not json").error()).startsWith("Not valid JSON");
        assertThat(checks.fhir("{\"notFhir\":true}").error()).contains("no resourceType");
        assertThat(checks.fhir("[1,2]").valid()).isFalse();
    }

    @Test
    void claimsCountsLinesAndRejectsRaggedOrEmptyFiles() {
        assertThat(checks.claims(IntakeSamples.CLAIMS).details()).containsEntry("claimLines", 2);
        assertThat(checks.claims(IntakeSamples.CLAIMS_RAGGED).error()).contains("Row 1 has 4 columns, header has 5");
        assertThat(checks.claims("claim_id,member_id\n").error()).contains("no claim rows");
    }

    @Test
    void ccdaNeedsClinicalDocumentWithPatientRole() {
        SyntaxCheck c = checks.ccda(IntakeSamples.CCDA);
        assertThat(c.valid()).isTrue();
        assertThat(c.patientId()).isEqualTo("MRN-C-1");
        assertThat(c.details()).containsEntry("documentId", "2.16.840.1.113883.19.5^CCD-1").containsEntry("title", "Test CCD");

        assertThat(checks.ccda("<ClinicalDocument xmlns=\"urn:hl7-org:v3\"><title>x</title></ClinicalDocument>").error())
                .contains("no recordTarget/patientRole");
        assertThat(checks.ccda("<Foo/>").error()).contains("expected {urn:hl7-org:v3}ClinicalDocument");
        assertThat(checks.ccda("<ClinicalDocument xmlns=\"urn:hl7-org:v3\"><title>").error()).startsWith("Not well-formed XML");
    }

    @Test
    void ccdaRefusesDoctypeSoNoEntityIsEverResolved() {
        SyntaxCheck c = checks.ccda(IntakeSamples.CCDA_WITH_DOCTYPE);
        assertThat(c.valid()).isFalse();
        assertThat(c.error()).contains("DOCTYPE");
    }
}
