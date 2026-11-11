package com.clinicalharmony.fhircompliance.service.ccda;

import com.clinicalharmony.fhircompliance.service.silver.Narratives;
import com.clinicalharmony.fhircompliance.validation.UsCoreProfiles;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.DateTimeType;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.clinicalharmony.fhircompliance.service.ccda.CcdaXml.attr;

/**
 * Problems section (LOINC 11450-4) → US Core Condition (Problems and Health Concerns profile).
 *
 * <p>Each Problem Concern Act ({@code entry/act}) wraps one or more Problem Observations
 * ({@code entryRelationship/observation}); one Condition is produced per observation. The
 * diagnosis is the observation's {@code value} (CD), with any {@code translation}s — typically an
 * ICD-10-CM code alongside SNOMED CT — as extra codings.
 *
 * <p>clinicalStatus, in order of precedence: an explicit Problem Status observation (SNOMED
 * 55561003 active / 73425007 inactive / 413322009 resolved); else {@code resolved} if the problem
 * has an end date ({@code effectiveTime/high}); else {@code resolved} if the concern act is
 * {@code completed}; else {@code active}. {@code negationInd="true"} → verificationStatus
 * {@code refuted} (the document asserts the patient does NOT have it).
 */
@Component
public class CcdaProblemMapper implements CcdaSectionMapper {

    static final String CLINICAL_STATUS_SYSTEM = "http://terminology.hl7.org/CodeSystem/condition-clinical";
    static final String VERIFICATION_STATUS_SYSTEM = "http://terminology.hl7.org/CodeSystem/condition-ver-status";
    static final String CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/condition-category";

    private static final Map<String, String> PROBLEM_STATUS = Map.of(
            "55561003", "active", "73425007", "inactive", "413322009", "resolved");

    @Override
    public String sectionName() {
        return "Problems";
    }

    @Override
    public String loincCode() {
        return "11450-4";
    }

    @Override
    public List<String> templateIds() {
        return List.of("2.16.840.1.113883.10.20.22.2.5", "2.16.840.1.113883.10.20.22.2.5.1");
    }

    @Override
    public List<Condition> map(Element section, CcdaMappingContext ctx) {
        CcdaXml xml = ctx.xml();
        List<Condition> conditions = new ArrayList<>();
        int entryIndex = 0;
        for (Element entry : xml.elements(section, "hl7:entry")) {
            entryIndex++;
            Element act = xml.element(entry, "hl7:act");
            List<Element> observations = act != null
                    ? xml.elements(act, "hl7:entryRelationship/hl7:observation")
                    : xml.elements(entry, "hl7:observation");
            if (observations.isEmpty()) {
                ctx.note(label(), "Entry " + entryIndex + " has no Problem Observation; skipped");
                continue;
            }
            int obsIndex = 0;
            for (Element observation : observations) {
                obsIndex++;
                conditions.add(condition(observation, act, section, ctx, entryIndex + "." + obsIndex));
            }
        }
        return conditions;
    }

    private Condition condition(Element observation, Element concernAct, Element section,
                                CcdaMappingContext ctx, String position) {
        CcdaXml xml = ctx.xml();
        Condition condition = new Condition();
        condition.setId(ctx.resourceId("Condition", observation, "problem-" + position));
        condition.getMeta().addProfile(UsCoreProfiles.CONDITION_PROBLEMS);

        String onset = Hl7Dates.toFhirDateTime(xml.string(observation, "hl7:effectiveTime/hl7:low/@value"));
        if (onset == null) {
            onset = Hl7Dates.toFhirDateTime(xml.string(observation, "hl7:effectiveTime/@value"));
        }
        String abatement = Hl7Dates.toFhirDateTime(xml.string(observation, "hl7:effectiveTime/hl7:high/@value"));

        String clinicalStatus = clinicalStatus(observation, concernAct, abatement != null, xml);
        condition.setClinicalStatus(new CodeableConcept().addCoding(
                new org.hl7.fhir.r4.model.Coding(CLINICAL_STATUS_SYSTEM, clinicalStatus, null)));
        if ("true".equals(attr(observation, "negationInd"))) {
            condition.setVerificationStatus(new CodeableConcept().addCoding(
                    new org.hl7.fhir.r4.model.Coding(VERIFICATION_STATUS_SYSTEM, "refuted", "Refuted")));
        }
        condition.addCategory().addCoding()
                .setSystem(CATEGORY_SYSTEM).setCode("problem-list-item").setDisplay("Problem List Item");

        CodeableConcept code = ctx.codeableConcept(xml.element(observation, "hl7:value"), section);
        if (code != null) {
            condition.setCode(code);
        } else {
            ctx.note(label(), "Entry " + position + " has no problem code or text (value missing or nullFlavor); Condition.code left empty");
        }

        condition.setSubject(ctx.patientReference());
        if (onset != null) {
            condition.setOnset(new DateTimeType(onset));
        }
        if (abatement != null) {
            condition.setAbatement(new DateTimeType(abatement));
        }
        String recorded = Hl7Dates.toFhirDateTime(xml.string(observation, "hl7:author/hl7:time/@value"));
        if (recorded == null) {
            recorded = Hl7Dates.toFhirDateTime(xml.string(concernAct, "hl7:author/hl7:time/@value"));
        }
        if (recorded != null) {
            condition.setRecordedDateElement(new DateTimeType(recorded));
        }

        String display = code == null ? null : code.getText();
        condition.setText(Narratives.generated("Problem: " + Narratives.orUnknown(display)
                + (code != null && code.hasCoding() ? " (" + code.getCodingFirstRep().getCode() + ")" : "")
                + ", " + clinicalStatus + (onset == null ? "" : ", onset " + onset)));
        return condition;
    }

    private static String clinicalStatus(Element observation, Element concernAct, boolean hasAbatement, CcdaXml xml) {
        String statusCode = xml.string(observation,
                "hl7:entryRelationship/hl7:observation[hl7:code/@code='33999-4']/hl7:value/@code");
        if (statusCode != null && PROBLEM_STATUS.containsKey(statusCode)) {
            return PROBLEM_STATUS.get(statusCode);
        }
        if (hasAbatement) {
            return "resolved";
        }
        if ("completed".equals(xml.string(concernAct, "hl7:statusCode/@code"))) {
            return "resolved";
        }
        return "active";
    }
}
