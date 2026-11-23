package com.clinicalharmony.ingestion.intake;

/** Small FHIR / claims / C-CDA payloads for the REST intake tests (HL7 ones are in Hl7SampleMessages). */
public final class IntakeSamples {

    public static final String FHIR_PATIENT = """
            {"resourceType":"Patient","id":"p1","identifier":[{"system":"urn:mrn","value":"MRN-F-1"}],
             "name":[{"family":"Doe","given":["John"]}],"gender":"male","birthDate":"1980-01-01"}""";

    public static final String FHIR_BUNDLE = """
            {"resourceType":"Bundle","type":"collection","entry":[
              {"resource":{"resourceType":"Condition","id":"c1"}},
              {"resource":{"resourceType":"Patient","id":"p2"}}]}""";

    public static final String CLAIMS = """
            claim_id,member_id,service_date,icd10_code,billed_amount
            CLM-1,MBR-1,2026-06-01,E11.9,150.00
            CLM-2,MBR-2,2026-06-02,I10,95.50
            """;

    public static final String CLAIMS_RAGGED = """
            claim_id,member_id,service_date,icd10_code,billed_amount
            CLM-1,MBR-1,2026-06-01,E11.9
            """;

    public static final String CCDA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ClinicalDocument xmlns="urn:hl7-org:v3">
              <id root="2.16.840.1.113883.19.5" extension="CCD-1"/>
              <title>Test CCD</title>
              <recordTarget><patientRole><id root="2.16.840.1.113883.19.5.2" extension="MRN-C-1"/></patientRole></recordTarget>
            </ClinicalDocument>
            """;

    public static final String CCDA_WITH_DOCTYPE = """
            <?xml version="1.0"?>
            <!DOCTYPE ClinicalDocument [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <ClinicalDocument xmlns="urn:hl7-org:v3"><title>&x;</title></ClinicalDocument>
            """;

    private IntakeSamples() {
    }
}
