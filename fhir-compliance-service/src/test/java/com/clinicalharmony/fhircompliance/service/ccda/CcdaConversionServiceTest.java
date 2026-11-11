package com.clinicalharmony.fhircompliance.service.ccda;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End to end: C-CDA bytes → Bundle + per-resource validation, with the REAL US Core validator. */
class CcdaConversionServiceTest {

    private final CcdaConversionService service = CcdaFixtures.service();

    @Test
    void compliantCcdConvertsToFullyCompliantBundle() {
        CcdaConversionResult result = service.convert(CcdaFixtures.load("ccd_compliant.xml"));

        assertThat(result.documentId()).isEqualTo("2.16.840.1.113883.19.5.99999.1^CCD-CH-0001");
        assertThat(result.documentTitle()).isEqualTo("ClinicalHarmony Test CCD — Ana Rivera");
        assertThat(result.resourceCount()).isEqualTo(9);
        assertThat(result.compliantCount()).isEqualTo(9);
        assertThat(result.allCompliant()).isTrue();
        assertThat(result.notes()).isEmpty();
        assertThat(result.sections()).containsExactly(
                "Problems (11450-4): 3 resource(s)",
                "Medications (10160-0): 2 resource(s)",
                "Results (30954-2): 3 resource(s)");

        Map<String, Long> byType = result.resources().stream()
                .collect(Collectors.groupingBy(ConvertedResource::resourceType, Collectors.counting()));
        assertThat(byType).containsExactlyInAnyOrderEntriesOf(
                Map.of("Patient", 1L, "Condition", 3L, "MedicationRequest", 2L, "Observation", 3L));
        assertThat(result.resources()).allSatisfy(r -> {
            assertThat(r.validation().errorCount()).as(r.resourceType() + " " + r.resourceId()).isZero();
            assertThat(r.fullUrl()).isEqualTo("urn:uuid:" + r.resourceId());
        });
        assertThat(result.resources().get(0).sourceSection()).isEqualTo("Patient (recordTarget)");
        assertThat(result.resources().get(1).sourceSection()).isEqualTo("Problems (11450-4)");
    }

    @Test
    void bundleIsAnR4CollectionWhoseReferencesResolveInsideIt() {
        JsonNode bundle = service.convert(CcdaFixtures.load("ccd_compliant.xml")).bundle();

        assertThat(bundle.path("resourceType").asText()).isEqualTo("Bundle");
        assertThat(bundle.path("type").asText()).isEqualTo("collection");
        assertThat(bundle.path("timestamp").asText()).startsWith("2026-11-11T");
        assertThat(bundle.path("identifier").path("value").asText()).isEqualTo("CCD-CH-0001");
        List<JsonNode> entries = StreamSupport.stream(bundle.path("entry").spliterator(), false).toList();
        assertThat(entries).hasSize(9);

        String patientFullUrl = entries.get(0).path("fullUrl").asText();
        assertThat(entries.get(0).path("resource").path("resourceType").asText()).isEqualTo("Patient");
        List<String> subjects = new ArrayList<>();
        for (JsonNode entry : entries.subList(1, entries.size())) {
            subjects.add(entry.path("resource").path("subject").path("reference").asText());
        }
        assertThat(subjects).hasSize(8).containsOnly(patientFullUrl);
    }

    @Test
    void conversionIsDeterministic() {
        CcdaConversionResult first = service.convert(CcdaFixtures.load("ccd_compliant.xml"));
        CcdaConversionResult second = service.convert(CcdaFixtures.load("ccd_compliant.xml"));

        assertThat(second.resources()).extracting(ConvertedResource::resourceId)
                .containsExactlyElementsOf(first.resources().stream().map(ConvertedResource::resourceId).toList());
        assertThat(second.bundle().path("id")).isEqualTo(first.bundle().path("id"));
    }

    @Test
    void nonCompliantCcdStillConvertsAndReportsEachGap() {
        CcdaConversionResult result = service.convert(CcdaFixtures.load("ccd_noncompliant.xml"));

        assertThat(result.resourceCount()).isEqualTo(7);
        assertThat(result.compliantCount()).isEqualTo(3);
        assertThat(result.allCompliant()).isFalse();

        List<String> errorLocations = result.resources().stream()
                .flatMap(r -> r.validation().violations().stream())
                .filter(v -> "ERROR".equals(v.severity()))
                .map(v -> v.location())
                .sorted().toList();
        assertThat(errorLocations).containsExactly(
                "Condition.code", "MedicationRequest.status", "Observation.status", "Patient.gender");
        assertThat(result.resources()).filteredOn(r -> !r.validation().compliant())
                .allSatisfy(r -> assertThat(r.validation().violations()).filteredOn(v -> "ERROR".equals(v.severity()))
                        .allSatisfy(v -> assertThat(v.suggestedFix()).isNotBlank()));

        assertThat(result.notes()).extracting(ConversionNote::section).containsExactly(
                "Problems (11450-4)", "Medications (10160-0)", "Results (30954-2)");
    }

    @Test
    void missingSectionsAreReportedNotFatal() {
        CcdaConversionResult result = service.convert(CcdaFixtures.document(CcdaFixtures.MINIMAL_PATIENT_ROLE, ""));

        assertThat(result.resourceCount()).isEqualTo(1);
        assertThat(result.allCompliant()).isTrue();
        assertThat(result.sections()).containsExactly(
                "Problems (11450-4): not present", "Medications (10160-0): not present", "Results (30954-2): not present");
        assertThat(result.notes()).extracting(ConversionNote::message).containsOnly("Section not present in document");
    }

    @Test
    void sectionFoundByTemplateIdWhenLoincCodeIsMissing() {
        CcdaConversionResult result = service.convert(CcdaFixtures.document(CcdaFixtures.MINIMAL_PATIENT_ROLE, """
                <component><section>
                  <templateId root="2.16.840.1.113883.10.20.22.2.5.1"/>
                  <entry><act classCode="ACT" moodCode="EVN"><statusCode code="active"/>
                    <entryRelationship typeCode="SUBJ"><observation classCode="OBS" moodCode="EVN">
                      <value xsi:type="CD" code="38341003" codeSystem="2.16.840.1.113883.6.96" displayName="Hypertension"/>
                    </observation></entryRelationship></act></entry>
                </section></component>"""));

        assertThat(result.sections()).contains("Problems (11450-4): 1 resource(s)");
    }

    @Test
    void sectionWithNullFlavorNiIsReportedAsNoInformation() {
        CcdaConversionResult result = service.convert(CcdaFixtures.document(CcdaFixtures.MINIMAL_PATIENT_ROLE, """
                <component><section nullFlavor="NI"><code code="10160-0" codeSystem="2.16.840.1.113883.6.1"/></section></component>"""));

        assertThat(result.sections()).contains("Medications (10160-0): present, no information (nullFlavor=NI)");
    }

    @Test
    void documentWithoutPatientRoleIsRejected() {
        byte[] doc = "<ClinicalDocument xmlns=\"urn:hl7-org:v3\"><id root=\"1.2\"/></ClinicalDocument>".getBytes();

        assertThatThrownBy(() -> service.convert(doc))
                .isInstanceOf(InvalidCcdaDocumentException.class)
                .hasMessageContaining("recordTarget/patientRole");
    }

    @Test
    void oversizedDocumentIsRejectedBeforeParsing() {
        CcdaConversionService small = CcdaFixtures.service(100);

        assertThatThrownBy(() -> small.convert(CcdaFixtures.load("ccd_compliant.xml")))
                .isInstanceOf(CcdaDocumentTooLargeException.class)
                .hasMessageContaining("limit is 100 bytes");
    }

    @Test
    void documentWithoutIdGetsContentDerivedIdsAndANote() {
        byte[] doc = ("<ClinicalDocument xmlns=\"urn:hl7-org:v3\"><recordTarget>" + CcdaFixtures.MINIMAL_PATIENT_ROLE
                + "</recordTarget></ClinicalDocument>").getBytes();

        CcdaConversionResult result = service.convert(doc);

        assertThat(result.documentId()).isNull();
        assertThat(result.notes()).extracting(ConversionNote::message)
                .contains("ClinicalDocument has no id; resource ids derived from document content instead");
    }
}
