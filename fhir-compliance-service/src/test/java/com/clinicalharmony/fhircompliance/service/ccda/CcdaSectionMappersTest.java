package com.clinicalharmony.fhircompliance.service.ccda;

import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.clinicalharmony.fhircompliance.validation.UsCoreValidatorFixture;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.Timing;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section mappers against the synthetic CCDs. "Compliant" assertions run the REAL US Core
 * validator, so they prove the mapping, not just the shape.
 */
class CcdaSectionMappersTest {

    private static <T extends Resource> Mapped<T> map(CcdaSectionMapper mapper, byte[] document, Class<T> type) {
        CcdaXml xml = CcdaXml.parse(document);
        CcdaMappingContext ctx = new CcdaMappingContext(xml, "doc");
        new CcdaPatientMapper().map(xml.element(xml.root(), "hl7:recordTarget/hl7:patientRole"), ctx);
        Element section = xml.element(xml.root(),
                "hl7:component/hl7:structuredBody/hl7:component/hl7:section[hl7:code/@code='" + mapper.loincCode() + "']");
        List<T> resources = mapper.map(section, ctx).stream().map(type::cast).toList();
        return new Mapped<>(resources, ctx);
    }

    private record Mapped<T>(List<T> resources, CcdaMappingContext ctx) {
    }

    private static FhirValidationOutcome validate(Resource resource) {
        return UsCoreValidatorFixture.validator().validate(resource);
    }

    private static List<String> errorLocations(Resource resource) {
        return validate(resource).violations().stream()
                .filter(v -> "ERROR".equals(v.severity())).map(v -> v.location()).toList();
    }

    // ---------------------------------------------------------------- Problems

    @Test
    void problemsMapToCompliantConditionsWithTranslationsAndNarrativeText() {
        Mapped<Condition> mapped = map(new CcdaProblemMapper(), CcdaFixtures.load("ccd_compliant.xml"), Condition.class);

        assertThat(mapped.resources()).hasSize(3);
        Condition diabetes = mapped.resources().get(0);
        assertThat(diabetes.getCode().getCoding()).extracting(Coding::getSystem, Coding::getCode).containsExactly(
                org.assertj.core.groups.Tuple.tuple("http://snomed.info/sct", "44054006"),
                org.assertj.core.groups.Tuple.tuple("http://hl7.org/fhir/sid/icd-10-cm", "E11.9"));
        // originalText/reference="#problem1" resolved into the section narrative
        assertThat(diabetes.getCode().getText()).isEqualTo("Type 2 diabetes mellitus without complications");
        assertThat(diabetes.getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo("active");
        assertThat(diabetes.getOnsetDateTimeType().getValueAsString()).isEqualTo("2019-03-02");
        assertThat(diabetes.getRecordedDateElement().getValueAsString()).isEqualTo("2019-03-02");
        assertThat(diabetes.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("problem-list-item");
        assertThat(diabetes.getSubject().getReference()).startsWith("urn:uuid:");

        Condition bronchitis = mapped.resources().get(2);
        assertThat(bronchitis.getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo("resolved");
        assertThat(bronchitis.getAbatementDateTimeType().getValueAsString()).isEqualTo("2025-01-24");

        for (Condition condition : mapped.resources()) {
            assertThat(errorLocations(condition)).as(condition.getCode().getText()).isEmpty();
        }
        assertThat(mapped.ctx().notes()).isEmpty();
    }

    @Test
    void nullFlavoredProblemValueLeavesCodeEmptyNotesItAndFailsUsCore() {
        Mapped<Condition> mapped = map(new CcdaProblemMapper(), CcdaFixtures.load("ccd_noncompliant.xml"), Condition.class);

        Condition unknown = mapped.resources().get(0);
        assertThat(unknown.hasCode()).isFalse();
        assertThat(errorLocations(unknown)).containsExactly("Condition.code");
        assertThat(errorLocations(mapped.resources().get(1))).isEmpty();
        assertThat(mapped.ctx().notes()).singleElement()
                .satisfies(n -> assertThat(n.message()).startsWith("Entry 1.1 has no problem code"));
    }

    @Test
    void problemStatusObservationAndNegationAreHonored() {
        byte[] doc = CcdaFixtures.document(CcdaFixtures.MINIMAL_PATIENT_ROLE, """
                <component><section>
                  <code code="11450-4" codeSystem="2.16.840.1.113883.6.1"/>
                  <entry><act classCode="ACT" moodCode="EVN"><statusCode code="active"/>
                    <entryRelationship typeCode="SUBJ"><observation classCode="OBS" moodCode="EVN" negationInd="true">
                      <id root="1.2.3" extension="neg"/>
                      <value xsi:type="CD" code="38341003" codeSystem="2.16.840.1.113883.6.96" displayName="Hypertension"/>
                      <entryRelationship typeCode="REFR"><observation classCode="OBS" moodCode="EVN">
                        <code code="33999-4" codeSystem="2.16.840.1.113883.6.1"/>
                        <value xsi:type="CD" code="73425007" codeSystem="2.16.840.1.113883.6.96"/>
                      </observation></entryRelationship>
                    </observation></entryRelationship>
                  </act></entry>
                </section></component>""");

        Condition condition = map(new CcdaProblemMapper(), doc, Condition.class).resources().get(0);

        assertThat(condition.getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo("inactive");
        assertThat(condition.getVerificationStatus().getCodingFirstRep().getCode()).isEqualTo("refuted");
    }

    // ---------------------------------------------------------------- Medications

    @Test
    void medicationsMapToCompliantRequestsWithDosageAndRequester() {
        Mapped<MedicationRequest> mapped = map(new CcdaMedicationMapper(), CcdaFixtures.load("ccd_compliant.xml"),
                MedicationRequest.class);

        assertThat(mapped.resources()).hasSize(2);
        MedicationRequest metformin = mapped.resources().get(0);
        assertThat(metformin.getStatus()).isEqualTo(MedicationRequest.MedicationRequestStatus.ACTIVE);
        assertThat(metformin.getIntent()).isEqualTo(MedicationRequest.MedicationRequestIntent.ORDER);
        assertThat(metformin.getMedicationCodeableConcept().getCodingFirstRep().getSystem())
                .isEqualTo("http://www.nlm.nih.gov/research/umls/rxnorm");
        assertThat(metformin.getMedicationCodeableConcept().getCodingFirstRep().getCode()).isEqualTo("861007");
        assertThat(metformin.getMedicationCodeableConcept().getText()).isEqualTo("Metformin 500 MG Oral Tablet");
        assertThat(metformin.getRequester().getDisplay()).isEqualTo("Dr. Henry Seven");
        assertThat(metformin.getAuthoredOnElement().getValueAsString()).isEqualTo("2019-03-02");
        assertThat(metformin.hasReported()).isFalse();
        var dosage = metformin.getDosageInstructionFirstRep();
        assertThat(dosage.getText()).isEqualTo("Take 1 tablet by mouth every 12 hours with meals");
        assertThat(dosage.getTiming().getRepeat().getPeriod()).isEqualByComparingTo(new BigDecimal("12"));
        assertThat(dosage.getTiming().getRepeat().getPeriodUnit()).isEqualTo(Timing.UnitsOfTime.H);
        assertThat(dosage.getRoute().getCodingFirstRep().getCode()).isEqualTo("C38288");
        assertThat(dosage.getDoseAndRateFirstRep().getDoseQuantity().getValue()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(metformin.getDispenseRequest().getValidityPeriod().getStartElement().getValueAsString()).isEqualTo("2019-03-02");

        MedicationRequest lisinopril = mapped.resources().get(1);
        assertThat(lisinopril.getReportedBooleanType().booleanValue()).as("moodCode EVN → reported").isTrue();
        // No author in the C-CDA → requester carries data-absent-reason, not an invented prescriber.
        assertThat(lisinopril.getRequester().hasDisplay()).isFalse();
        assertThat(lisinopril.getRequester().getExtensionByUrl(CcdaMappingContext.DATA_ABSENT_REASON)).isNotNull();

        for (MedicationRequest request : mapped.resources()) {
            assertThat(errorLocations(request)).as(request.getMedicationCodeableConcept().getText()).isEmpty();
        }
    }

    @Test
    void medicationWithoutStatusCodeFailsUsCore() {
        Mapped<MedicationRequest> mapped = map(new CcdaMedicationMapper(), CcdaFixtures.load("ccd_noncompliant.xml"),
                MedicationRequest.class);

        assertThat(mapped.resources().get(0).hasStatus()).isFalse();
        assertThat(errorLocations(mapped.resources().get(0))).containsExactly("MedicationRequest.status");
        assertThat(errorLocations(mapped.resources().get(1))).isEmpty();
        assertThat(mapped.ctx().notes()).extracting(ConversionNote::message)
                .containsExactly("Entry 1 has no statusCode; MedicationRequest.status left empty");
    }

    // ---------------------------------------------------------------- Results

    @Test
    void resultsMapToCompliantLabObservations() {
        Mapped<Observation> mapped = map(new CcdaResultMapper(), CcdaFixtures.load("ccd_compliant.xml"), Observation.class);

        assertThat(mapped.resources()).hasSize(3);
        Observation a1c = mapped.resources().get(0);
        assertThat(a1c.getStatus()).isEqualTo(Observation.ObservationStatus.FINAL);
        assertThat(a1c.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("laboratory");
        assertThat(a1c.getCode().getCodingFirstRep().getCode()).isEqualTo("4548-4");
        assertThat(a1c.getCode().getText()).isEqualTo("Hemoglobin A1c");
        assertThat(a1c.getValueQuantity().getValue()).isEqualByComparingTo(new BigDecimal("7.2"));
        assertThat(a1c.getValueQuantity().getSystem()).isEqualTo("http://unitsofmeasure.org");
        assertThat(a1c.getValueQuantity().getCode()).isEqualTo("%");
        assertThat(a1c.getEffectiveDateTimeType().getValueAsString()).isEqualTo("2026-09-10T08:30:00-05:00");
        assertThat(a1c.getInterpretationFirstRep().getCodingFirstRep().getCode()).isEqualTo("H");
        assertThat(a1c.getReferenceRangeFirstRep().getLow().getValue()).isEqualByComparingTo(new BigDecimal("4.0"));
        assertThat(a1c.getReferenceRangeFirstRep().getHigh().getValue()).isEqualByComparingTo(new BigDecimal("5.6"));

        // Creatinine has no effectiveTime of its own → falls back to the organizer's.
        assertThat(mapped.resources().get(2).getEffectiveDateTimeType().getValueAsString())
                .isEqualTo("2026-09-10T08:30:00-05:00");

        for (Observation observation : mapped.resources()) {
            assertThat(errorLocations(observation)).as(observation.getCode().getText()).isEmpty();
        }
    }

    @Test
    void resultWithoutStatusFailsUsCoreAndOthersStillPass() {
        Mapped<Observation> mapped = map(new CcdaResultMapper(), CcdaFixtures.load("ccd_noncompliant.xml"), Observation.class);

        assertThat(errorLocations(mapped.resources().get(0))).containsExactly("Observation.status");
        assertThat(errorLocations(mapped.resources().get(1))).isEmpty();
    }

    @Test
    void resultValueTypesAndNullFlavor() {
        byte[] doc = CcdaFixtures.document(CcdaFixtures.MINIMAL_PATIENT_ROLE, """
                <component><section>
                  <code code="30954-2" codeSystem="2.16.840.1.113883.6.1"/>
                  <entry><organizer classCode="BATTERY" moodCode="EVN"><statusCode code="completed"/>
                    <effectiveTime value="20260101"/>
                    <component><observation classCode="OBS" moodCode="EVN">
                      <code code="5778-6" codeSystem="2.16.840.1.113883.6.1" displayName="Color of Urine"/>
                      <statusCode code="completed"/><value xsi:type="ST">Yellow</value></observation></component>
                    <component><observation classCode="OBS" moodCode="EVN">
                      <code code="5802-4" codeSystem="2.16.840.1.113883.6.1" displayName="Nitrite"/>
                      <statusCode code="completed"/>
                      <value xsi:type="CD" code="260385009" codeSystem="2.16.840.1.113883.6.96" displayName="Negative"/></observation></component>
                    <component><observation classCode="OBS" moodCode="EVN">
                      <code code="2093-3" codeSystem="2.16.840.1.113883.6.1" displayName="Cholesterol"/>
                      <statusCode code="completed"/><value xsi:type="PQ" nullFlavor="NAV"/></observation></component>
                    <component><observation classCode="OBS" moodCode="EVN">
                      <code code="1234-5" codeSystem="2.16.840.1.113883.6.1"/>
                      <statusCode code="completed"/><value xsi:type="RTO"/></observation></component>
                  </organizer></entry>
                </section></component>""");

        Mapped<Observation> mapped = map(new CcdaResultMapper(), doc, Observation.class);

        assertThat(mapped.resources().get(0).getValueStringType().getValue()).isEqualTo("Yellow");
        assertThat(mapped.resources().get(1).getValueCodeableConcept().getCodingFirstRep().getCode()).isEqualTo("260385009");
        assertThat(mapped.resources().get(2).hasValue()).isFalse();
        assertThat(mapped.resources().get(2).getDataAbsentReason().getCodingFirstRep().getCode()).isEqualTo("temp-unknown");
        assertThat(mapped.resources().get(3).hasValue()).isFalse();
        assertThat(mapped.ctx().notes()).extracting(ConversionNote::message)
                .containsExactly("Entry 1.4 value type 'RTO' isn't supported; value omitted");
    }
}
