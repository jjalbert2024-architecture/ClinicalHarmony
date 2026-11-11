package com.clinicalharmony.fhircompliance.service.ccda;

import com.clinicalharmony.fhircompliance.service.silver.Narratives;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Dosage;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationRequest.MedicationRequestStatus;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Timing;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.clinicalharmony.fhircompliance.service.ccda.CcdaXml.attr;

/**
 * Medications section (LOINC 10160-0) → US Core MedicationRequest, one per
 * {@code entry/substanceAdministration}.
 *
 * <ul>
 *   <li>medication: {@code consumable/manufacturedProduct/manufacturedMaterial/code} (RxNorm,
 *       with translations).</li>
 *   <li>status: C-CDA statusCode → MedicationRequest.status (active→active, completed→completed,
 *       aborted→stopped, suspended/held→on-hold, cancelled→cancelled, nullified→entered-in-error).
 *       No statusCode → no status, and the validator reports it.</li>
 *   <li>intent: always {@code order}. {@code moodCode="EVN"} (a medication the patient is/was
 *       taking, as opposed to {@code INT}, one that was prescribed) also sets
 *       {@code reportedBoolean=true}: the record is a report, not the original order.</li>
 *   <li>requester (us-core-21 requires one on an order): the {@code author}'s name as a display-only
 *       reference — C-CDA rarely carries enough to build a full US Core Practitioner. With no
 *       author, the requester is a data-absent-reason, same as the Silver builder.</li>
 *   <li>dosage: {@code doseQuantity}, {@code routeCode}, the PIVL_TS {@code effectiveTime}
 *       (frequency) and the sig text; the IVL_TS {@code effectiveTime} (start/stop) becomes
 *       {@code dispenseRequest.validityPeriod}.</li>
 * </ul>
 */
@Component
public class CcdaMedicationMapper implements CcdaSectionMapper {

    private static final Map<String, MedicationRequestStatus> STATUS = Map.of(
            "active", MedicationRequestStatus.ACTIVE,
            "completed", MedicationRequestStatus.COMPLETED,
            "aborted", MedicationRequestStatus.STOPPED,
            "suspended", MedicationRequestStatus.ONHOLD,
            "held", MedicationRequestStatus.ONHOLD,
            "cancelled", MedicationRequestStatus.CANCELLED,
            "nullified", MedicationRequestStatus.ENTEREDINERROR,
            "new", MedicationRequestStatus.DRAFT
    );

    /** PIVL_TS period units (UCUM) → FHIR Timing.repeat.periodUnit. */
    private static final Map<String, Timing.UnitsOfTime> PERIOD_UNITS = Map.of(
            "s", Timing.UnitsOfTime.S, "min", Timing.UnitsOfTime.MIN, "h", Timing.UnitsOfTime.H,
            "d", Timing.UnitsOfTime.D, "wk", Timing.UnitsOfTime.WK, "mo", Timing.UnitsOfTime.MO,
            "a", Timing.UnitsOfTime.A);

    @Override
    public String sectionName() {
        return "Medications";
    }

    @Override
    public String loincCode() {
        return "10160-0";
    }

    @Override
    public List<String> templateIds() {
        return List.of("2.16.840.1.113883.10.20.22.2.1", "2.16.840.1.113883.10.20.22.2.1.1");
    }

    @Override
    public List<MedicationRequest> map(Element section, CcdaMappingContext ctx) {
        CcdaXml xml = ctx.xml();
        List<MedicationRequest> requests = new ArrayList<>();
        int entryIndex = 0;
        for (Element entry : xml.elements(section, "hl7:entry")) {
            entryIndex++;
            Element administration = xml.element(entry, "hl7:substanceAdministration");
            if (administration == null) {
                ctx.note(label(), "Entry " + entryIndex + " has no substanceAdministration; skipped");
                continue;
            }
            requests.add(request(administration, section, ctx, entryIndex));
        }
        return requests;
    }

    private MedicationRequest request(Element sa, Element section, CcdaMappingContext ctx, int position) {
        CcdaXml xml = ctx.xml();
        MedicationRequest request = new MedicationRequest();
        request.setId(ctx.resourceId("MedicationRequest", sa, "medication-" + position));
        request.getMeta().addProfile(UsCoreProfiles.MEDICATION_REQUEST);

        String status = xml.string(sa, "hl7:statusCode/@code");
        if (status != null && STATUS.containsKey(status)) {
            request.setStatus(STATUS.get(status));
        } else if (status != null) {
            request.setStatus(MedicationRequestStatus.UNKNOWN);
            ctx.note(label(), "Entry " + position + " statusCode '" + status + "' has no MedicationRequest equivalent; status set to unknown");
        } else {
            ctx.note(label(), "Entry " + position + " has no statusCode; MedicationRequest.status left empty");
        }
        request.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
        if ("EVN".equals(attr(sa, "moodCode"))) {
            request.setReported(new org.hl7.fhir.r4.model.BooleanType(true));
        }

        CodeableConcept medication = ctx.codeableConcept(
                xml.element(sa, "hl7:consumable/hl7:manufacturedProduct/hl7:manufacturedMaterial/hl7:code"), section);
        if (medication != null) {
            request.setMedication(medication);
        } else {
            ctx.note(label(), "Entry " + position + " has no medication code or name; medication[x] left empty");
        }

        request.setSubject(ctx.patientReference());
        request.setRequester(requester(sa, xml));

        String authoredOn = Hl7Dates.toFhirDateTime(xml.string(sa, "hl7:author/hl7:time/@value"));
        String start = Hl7Dates.toFhirDateTime(xml.string(sa, "hl7:effectiveTime[hl7:low or hl7:high]/hl7:low/@value"));
        String end = Hl7Dates.toFhirDateTime(xml.string(sa, "hl7:effectiveTime[hl7:low or hl7:high]/hl7:high/@value"));
        if (authoredOn == null) {
            authoredOn = start;
        }
        if (authoredOn != null) {
            request.setAuthoredOnElement(new DateTimeType(authoredOn));
        }
        if (start != null || end != null) {
            Period validity = new Period();
            if (start != null) validity.setStartElement(new DateTimeType(start));
            if (end != null) validity.setEndElement(new DateTimeType(end));
            request.getDispenseRequest().setValidityPeriod(validity);
        }

        Dosage dosage = dosage(sa, section, ctx);
        if (dosage != null) {
            request.addDosageInstruction(dosage);
        }

        String display = medication == null ? null : medication.getText();
        request.setText(Narratives.generated("Medication: " + Narratives.orUnknown(display)
                + (dosage != null && dosage.hasText() ? ", " + dosage.getText() : "")
                + ", " + (request.hasStatus() ? request.getStatus().toCode() : "(status unknown)")));
        return request;
    }

    private static Reference requester(Element sa, CcdaXml xml) {
        Element author = xml.element(sa, "hl7:author/hl7:assignedAuthor");
        String name = null;
        if (author != null) {
            Element personName = xml.element(author, "hl7:assignedPerson/hl7:name");
            if (personName != null) {
                List<String> parts = new ArrayList<>();
                for (Element part : xml.elements(personName, "hl7:prefix | hl7:given | hl7:family | hl7:suffix")) {
                    String text = CcdaXml.text(part);
                    if (text != null) parts.add(text);
                }
                name = parts.isEmpty() ? CcdaXml.text(personName) : String.join(" ", parts);
            }
            if (name == null) {
                name = CcdaXml.text(xml.element(author, "hl7:representedOrganization/hl7:name"));
            }
        }
        if (name != null) {
            return new Reference().setDisplay(name);
        }
        return CcdaMappingContext.withDataAbsentReason(new Reference(), "UNK");
    }

    private Dosage dosage(Element sa, Element section, CcdaMappingContext ctx) {
        CcdaXml xml = ctx.xml();
        Dosage dosage = new Dosage();

        String sig = ctx.textOrReference(xml.element(sa, "hl7:text"), section);
        if (sig != null) {
            dosage.setText(sig);
        }

        Element period = xml.element(sa, "hl7:effectiveTime[contains(@xsi:type,'PIVL_TS')]/hl7:period");
        if (period != null) {
            BigDecimal value = decimal(attr(period, "value"));
            Timing.UnitsOfTime unit = PERIOD_UNITS.get(attr(period, "unit"));
            if (value != null && unit != null) {
                dosage.getTiming().getRepeat().setPeriod(value).setPeriodUnit(unit);
            } else {
                ctx.note(label(), "Unrecognized dosing period value='" + attr(period, "value")
                        + "' unit='" + attr(period, "unit") + "'; frequency omitted");
            }
        }

        Element route = xml.element(sa, "hl7:routeCode");
        CodeableConcept routeConcept = ctx.codeableConcept(route, section);
        if (routeConcept != null) {
            dosage.setRoute(routeConcept);
        }

        Element dose = xml.element(sa, "hl7:doseQuantity[@value]");
        if (dose != null) {
            BigDecimal value = decimal(attr(dose, "value"));
            if (value != null) {
                Quantity quantity = new Quantity().setValue(value);
                String unit = attr(dose, "unit");
                if (unit != null) {
                    quantity.setUnit(unit).setSystem(CodeSystems.UCUM).setCode(unit);
                }
                dosage.addDoseAndRate().setDose(quantity);
            }
        }

        return dosage.isEmpty() ? null : dosage;
    }

    static BigDecimal decimal(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
