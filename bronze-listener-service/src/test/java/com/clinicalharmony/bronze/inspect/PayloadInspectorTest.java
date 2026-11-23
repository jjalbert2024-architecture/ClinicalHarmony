package com.clinicalharmony.bronze.inspect;

import com.clinicalharmony.bronze.hl7.Hl7Inspector;
import com.clinicalharmony.bronze.hl7.Hl7Samples;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadInspectorTest {

    private final PayloadInspector inspector = new PayloadInspector(new Hl7Inspector(), new ObjectMapper());

    @Test
    void hl7KeepsThePayloadAndReportsTypeAndControlId() {
        PayloadInspector.Inspection i = inspector.inspect(BronzeFormat.HL7V2, Hl7Samples.ADT_A01);
        assertThat(i.parsed()).isTrue();
        assertThat(i.rowPayloads()).containsExactly(Hl7Samples.ADT_A01);
        assertThat(i.details()).containsEntry("hl7MessageType", "ADT^A01");
        assertThat(inspector.inspect(BronzeFormat.HL7V2, "NOT HL7").parsed()).isFalse();
    }

    @Test
    void fhirNeedsResourceType() {
        PayloadInspector.Inspection ok = inspector.inspect(BronzeFormat.FHIR_JSON,
                "{\"resourceType\":\"Bundle\",\"entry\":[{},{},{}]}");
        assertThat(ok.details()).containsEntry("fhirResourceType", "Bundle").containsEntry("fhirResourceCount", 3);
        assertThat(inspector.inspect(BronzeFormat.FHIR_JSON, "{\"notFhir\":true}").error()).contains("no resourceType");
        assertThat(inspector.inspect(BronzeFormat.FHIR_JSON, "{oops").error()).startsWith("Not valid JSON");
    }

    @Test
    void claimsFileBecomesOneSelfContainedPayloadPerLine() {
        PayloadInspector.Inspection i = inspector.inspect(BronzeFormat.CLAIMS_CSV, """
                claim_id,member_id,service_date,icd10_code,billed_amount
                CLM-1,MBR-1,2026-06-01,E11.9,150.00
                CLM-2,MBR-2,2026-06-02,I10,95.50
                """);
        assertThat(i.parsed()).isTrue();
        assertThat(i.details()).containsEntry("claimLines", 2);
        assertThat(i.rowPayloads()).containsExactly(
                "claim_id,member_id,service_date,icd10_code,billed_amount\nCLM-1,MBR-1,2026-06-01,E11.9,150.00",
                "claim_id,member_id,service_date,icd10_code,billed_amount\nCLM-2,MBR-2,2026-06-02,I10,95.50");
    }

    @Test
    void claimsValueWithCommaIsQuotedSoItReadsBackAsOneValue() throws Exception {
        PayloadInspector.Inspection i = inspector.inspect(BronzeFormat.CLAIMS_CSV,
                "claim_id,provider\nCLM-9,\"Smith, Jones & Co\"\n");
        String row = i.rowPayloads().get(0);
        List<CSVRecord> back = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build()
                .parse(new StringReader(row)).getRecords();
        assertThat(back.get(0).get("provider")).isEqualTo("Smith, Jones & Co");
    }

    @Test
    void raggedOrEmptyClaimsFileIsQuarantinedWhole() {
        assertThat(inspector.inspect(BronzeFormat.CLAIMS_CSV, "a,b,c\n1,2\n").error()).isEqualTo("Row 1 has 2 columns, header has 3");
        assertThat(inspector.inspect(BronzeFormat.CLAIMS_CSV, "a,b,c\n").error()).contains("no claim rows");
    }

    @Test
    void ccdaNeedsClinicalDocumentWithPatientRoleAndRefusesDoctype() {
        String ok = "<ClinicalDocument xmlns=\"urn:hl7-org:v3\"><id root=\"1.2.3\" extension=\"D1\"/>"
                + "<recordTarget><patientRole><id extension=\"MRN-1\"/></patientRole></recordTarget></ClinicalDocument>";
        PayloadInspector.Inspection i = inspector.inspect(BronzeFormat.CCDA_XML, ok);
        assertThat(i.parsed()).isTrue();
        assertThat(i.details()).containsEntry("ccdaDocumentId", "1.2.3^D1");
        assertThat(inspector.inspect(BronzeFormat.CCDA_XML, "<ClinicalDocument xmlns=\"urn:hl7-org:v3\"/>").error())
                .contains("recordTarget/patientRole");
        assertThat(inspector.inspect(BronzeFormat.CCDA_XML, "<html/>").error()).contains("ClinicalDocument");
        assertThat(inspector.inspect(BronzeFormat.CCDA_XML,
                "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>").error()).contains("DOCTYPE");
    }

    @Test
    void everyTopicMapsToAFormat() {
        for (BronzeFormat f : BronzeFormat.values()) {
            assertThat(BronzeFormat.ofTopic(f.topic())).isEqualTo(f);
        }
    }
}
