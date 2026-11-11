package com.clinicalharmony.fhircompliance.service.ccda;

import com.clinicalharmony.fhircompliance.service.silver.Narratives;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.clinicalharmony.fhircompliance.service.ccda.CcdaXml.attr;

/**
 * Results section (LOINC 30954-2) → US Core Laboratory Result Observation, one per Result
 * Observation ({@code entry/organizer/component/observation}, or a bare {@code entry/observation}).
 * The Result Organizer itself would be a DiagnosticReport; that's outside this slice's scope, so
 * only its effectiveTime is used (as a fallback for observations without their own).
 *
 * <ul>
 *   <li>status: completed→final, active→preliminary, aborted/cancelled→cancelled,
 *       nullified→entered-in-error. No statusCode → no status (validator reports it).</li>
 *   <li>value by {@code xsi:type}: PQ/REAL → Quantity (UCUM), INT → integer, ST/ED → string,
 *       CD/CE/CO/CV → CodeableConcept, BL → boolean. A {@code nullFlavor} value becomes
 *       {@code dataAbsentReason}. Anything else is noted and omitted.</li>
 *   <li>interpretationCode → interpretation; referenceRange/observationRange IVL_PQ → referenceRange.</li>
 * </ul>
 */
@Component
public class CcdaResultMapper implements CcdaSectionMapper {

    static final String CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/observation-category";
    static final String DATA_ABSENT_REASON_SYSTEM = "http://terminology.hl7.org/CodeSystem/data-absent-reason";

    private static final Map<String, ObservationStatus> STATUS = Map.of(
            "completed", ObservationStatus.FINAL,
            "active", ObservationStatus.PRELIMINARY,
            "aborted", ObservationStatus.CANCELLED,
            "cancelled", ObservationStatus.CANCELLED,
            "nullified", ObservationStatus.ENTEREDINERROR
    );

    @Override
    public String sectionName() {
        return "Results";
    }

    @Override
    public String loincCode() {
        return "30954-2";
    }

    @Override
    public List<String> templateIds() {
        return List.of("2.16.840.1.113883.10.20.22.2.3", "2.16.840.1.113883.10.20.22.2.3.1");
    }

    @Override
    public List<Observation> map(Element section, CcdaMappingContext ctx) {
        CcdaXml xml = ctx.xml();
        List<Observation> observations = new ArrayList<>();
        int entryIndex = 0;
        for (Element entry : xml.elements(section, "hl7:entry")) {
            entryIndex++;
            Element organizer = xml.element(entry, "hl7:organizer");
            List<Element> results = organizer != null
                    ? xml.elements(organizer, "hl7:component/hl7:observation")
                    : xml.elements(entry, "hl7:observation");
            if (results.isEmpty()) {
                ctx.note(label(), "Entry " + entryIndex + " has no Result Observation; skipped");
                continue;
            }
            int obsIndex = 0;
            for (Element result : results) {
                obsIndex++;
                observations.add(observation(result, organizer, section, ctx, entryIndex + "." + obsIndex));
            }
        }
        return observations;
    }

    private Observation observation(Element obs, Element organizer, Element section,
                                    CcdaMappingContext ctx, String position) {
        CcdaXml xml = ctx.xml();
        Observation observation = new Observation();
        observation.setId(ctx.resourceId("Observation", obs, "result-" + position));
        observation.getMeta().addProfile(UsCoreProfiles.OBSERVATION_LAB);

        String status = xml.string(obs, "hl7:statusCode/@code");
        if (status != null && STATUS.containsKey(status)) {
            observation.setStatus(STATUS.get(status));
        } else if (status != null) {
            observation.setStatus(ObservationStatus.UNKNOWN);
            ctx.note(label(), "Entry " + position + " statusCode '" + status + "' has no Observation equivalent; status set to unknown");
        } else {
            ctx.note(label(), "Entry " + position + " has no statusCode; Observation.status left empty");
        }
        observation.addCategory().addCoding()
                .setSystem(CATEGORY_SYSTEM).setCode("laboratory").setDisplay("Laboratory");

        CodeableConcept code = ctx.codeableConcept(xml.element(obs, "hl7:code"), section);
        if (code != null) {
            observation.setCode(code);
        } else {
            ctx.note(label(), "Entry " + position + " has no result code; Observation.code left empty");
        }
        observation.setSubject(ctx.patientReference());

        String effective = effectiveTime(obs, xml);
        if (effective == null) {
            effective = effectiveTime(organizer, xml);
        }
        if (effective != null) {
            observation.setEffective(new DateTimeType(effective));
        }

        mapValue(xml.element(obs, "hl7:value"), observation, section, ctx, position);

        for (Element interpretation : xml.elements(obs, "hl7:interpretationCode")) {
            CodeableConcept concept = ctx.codeableConcept(interpretation, section);
            if (concept != null) {
                observation.addInterpretation(concept);
            }
        }

        for (Element range : xml.elements(obs, "hl7:referenceRange/hl7:observationRange")) {
            Observation.ObservationReferenceRangeComponent component = new Observation.ObservationReferenceRangeComponent();
            Quantity low = simpleQuantity(xml.element(range, "hl7:value/hl7:low[@value]"));
            Quantity high = simpleQuantity(xml.element(range, "hl7:value/hl7:high[@value]"));
            if (low != null) component.setLow(low);
            if (high != null) component.setHigh(high);
            String text = ctx.textOrReference(xml.element(range, "hl7:text"), section);
            if (text != null) component.setText(text);
            if (!component.isEmpty()) {
                observation.addReferenceRange(component);
            }
        }

        observation.setText(Narratives.generated("Lab: " + Narratives.orUnknown(code == null ? null : code.getText())
                + (code != null && code.hasCoding() ? " (LOINC " + code.getCodingFirstRep().getCode() + ")" : "")
                + " = " + valueSummary(observation)));
        return observation;
    }

    private static String effectiveTime(Element element, CcdaXml xml) {
        if (element == null) {
            return null;
        }
        String value = Hl7Dates.toFhirDateTime(xml.string(element, "hl7:effectiveTime/@value"));
        return value != null ? value : Hl7Dates.toFhirDateTime(xml.string(element, "hl7:effectiveTime/hl7:low/@value"));
    }

    private void mapValue(Element value, Observation observation, Element section, CcdaMappingContext ctx, String position) {
        if (value == null) {
            ctx.note(label(), "Entry " + position + " has no value; Observation.value[x] left empty");
            return;
        }
        String nullFlavor = attr(value, "nullFlavor");
        if (nullFlavor != null && attr(value, "value") == null && attr(value, "code") == null) {
            observation.setDataAbsentReason(new CodeableConcept().addCoding(new org.hl7.fhir.r4.model.Coding(
                    DATA_ABSENT_REASON_SYSTEM, CcdaMappingContext.dataAbsentReason(nullFlavor), null)));
            return;
        }
        String type = CcdaXml.xsiType(value);
        switch (type == null ? "" : type) {
            case "PQ", "REAL" -> {
                Quantity quantity = simpleQuantity(value);
                if (quantity != null) {
                    observation.setValue(quantity);
                } else {
                    ctx.note(label(), "Entry " + position + " " + type + " value '" + attr(value, "value") + "' is not numeric; value omitted");
                }
            }
            case "INT" -> {
                try {
                    observation.setValue(new IntegerType(Integer.parseInt(attr(value, "value"))));
                } catch (NumberFormatException | NullPointerException e) {
                    ctx.note(label(), "Entry " + position + " INT value '" + attr(value, "value") + "' is not an integer; value omitted");
                }
            }
            case "ST", "ED" -> {
                String text = ctx.textOrReference(value, section);
                if (text != null) observation.setValue(new StringType(text));
            }
            case "CD", "CE", "CO", "CV" -> {
                CodeableConcept concept = ctx.codeableConcept(value, section);
                if (concept != null) observation.setValue(concept);
            }
            case "BL" -> observation.setValue(new BooleanType(Boolean.parseBoolean(attr(value, "value"))));
            default -> ctx.note(label(), "Entry " + position + " value type '" + (type == null ? "(none)" : type)
                    + "' isn't supported; value omitted");
        }
    }

    /** PQ-shaped element (value + optional UCUM unit) → Quantity; null if the value isn't numeric. */
    private static Quantity simpleQuantity(Element pq) {
        BigDecimal value = CcdaMedicationMapper.decimal(attr(pq, "value"));
        if (value == null) {
            return null;
        }
        Quantity quantity = new Quantity().setValue(value);
        String unit = attr(pq, "unit");
        if (unit != null) {
            quantity.setUnit(unit).setSystem(CodeSystems.UCUM).setCode(unit);
        }
        return quantity;
    }

    private static String valueSummary(Observation observation) {
        if (observation.hasValueQuantity()) {
            Quantity q = observation.getValueQuantity();
            return q.getValue().toPlainString() + (q.hasUnit() ? " " + q.getUnit() : "");
        }
        if (observation.hasValueCodeableConcept()) {
            return Narratives.orUnknown(observation.getValueCodeableConcept().getText());
        }
        if (observation.hasValue()) {
            return observation.getValue().primitiveValue();
        }
        return "(no value)";
    }
}
