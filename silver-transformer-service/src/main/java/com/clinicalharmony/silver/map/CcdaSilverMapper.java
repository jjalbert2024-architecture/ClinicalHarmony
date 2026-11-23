package com.clinicalharmony.silver.map;

import com.clinicalharmony.silver.hl7.Hl7SilverMapper;
import com.clinicalharmony.silver.hl7.MappingException;
import com.clinicalharmony.silver.hl7.SilverRecord;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.clinicalharmony.silver.map.Fields.blankToNull;
import static com.clinicalharmony.silver.map.Fields.clip;
import static com.clinicalharmony.silver.map.Fields.fit;
import static com.clinicalharmony.silver.map.Fields.utcDate;

/**
 * C-CDA R2.1 document → Silver (9d part 2). Hand-written XPath like fhir-compliance-service's C-CDA → FHIR
 * converter (the two services share no code): recordTarget/patientRole → patient (source system = the Bronze row's,
 * id = the first patientRole/id/@extension), and four sections found by LOINC code — Problems 11450-4 → conditions,
 * Medications 10160-0 → medications, Results 30954-2 → observations, Allergies 48765-2 → allergies. A document is
 * authoritative for its demographics (like an ADT). Strict on what Silver needs; descriptive text clipped.
 */
@Component
public class CcdaSilverMapper {

    static final String NS = "urn:hl7-org:v3";
    static final String ICD10_OID = "2.16.840.1.113883.6.90";
    static final String SNOMED_OID = "2.16.840.1.113883.6.96";
    static final String LOINC_OID = "2.16.840.1.113883.6.1";
    static final String RXNORM_OID = "2.16.840.1.113883.6.88";

    private static final Map<String, String> GENDERS = Map.of("M", "male", "F", "female", "UN", "other", "UNK", "unknown");
    private static final Map<String, String> MED_STATUS = Map.of("active", "active", "completed", "completed",
            "aborted", "stopped", "suspended", "on-hold", "nullified", "entered-in-error", "held", "on-hold",
            "new", "active", "cancelled", "stopped");
    private static final Map<String, String> RESULT_STATUS = Map.of("completed", "final", "active", "preliminary",
            "aborted", "cancelled", "cancelled", "cancelled");
    private static final Set<String> DRUG_ALLERGY = Set.of("416098002", "59037007", "419511003");
    private static final Set<String> FOOD_ALLERGY = Set.of("414285001", "235719002");
    private static final Set<String> ENV_ALLERGY = Set.of("426232007");
    private static final Map<String, String> SEVERITY = Map.of("24484000", "SV", "6736007", "MO", "255604002", "MI");

    public SilverRecord map(String raw, String sourceSystem) {
        Doc d = Doc.parse(raw);
        Element root = d.doc.getDocumentElement();
        Element role = d.element(root, "hl7:recordTarget/hl7:patientRole");
        if (role == null) {
            throw new MappingException("ClinicalDocument has no recordTarget/patientRole");
        }
        SilverRecord.Patient patient = patient(d, role, sourceSystem);

        List<SilverRecord.Condition> conditions = new ArrayList<>();
        Element problems = section(d, root, "11450-4");
        if (problems != null) {
            for (Element obs : d.elements(problems, "hl7:entry/hl7:act/hl7:entryRelationship/hl7:observation | hl7:entry/hl7:observation")) {
                conditions.add(condition(d, obs));
            }
        }
        List<SilverRecord.Medication> medications = new ArrayList<>();
        Element meds = section(d, root, "10160-0");
        if (meds != null) {
            for (Element sa : d.elements(meds, "hl7:entry/hl7:substanceAdministration")) {
                medications.add(medication(d, sa));
            }
        }
        List<SilverRecord.Observation> observations = new ArrayList<>();
        Element results = section(d, root, "30954-2");
        if (results != null) {
            for (Element entry : d.elements(results, "hl7:entry")) {
                Element organizer = d.element(entry, "hl7:organizer");
                Instant organizerTime = organizer == null ? null : time(d, organizer, "organizer");
                for (Element obs : organizer != null ? d.elements(organizer, "hl7:component/hl7:observation")
                        : d.elements(entry, "hl7:observation")) {
                    SilverRecord.Observation o = observation(d, obs, organizerTime);
                    if (o != null) {
                        observations.add(o);
                    }
                }
            }
        }
        List<SilverRecord.Allergy> allergies = new ArrayList<>();
        Element allergySection = section(d, root, "48765-2");
        if (allergySection != null) {
            for (Element obs : d.elements(allergySection, "hl7:entry/hl7:act/hl7:entryRelationship/hl7:observation")) {
                allergies.add(allergy(d, obs));
            }
        }
        String docId = d.string(root, "hl7:id/@extension");
        Instant docTime = Hl7SilverMapper.timestamp(blankToNull(d.string(root, "hl7:effectiveTime/@value")), "ClinicalDocument.effectiveTime");
        return new SilverRecord("C-CDA", blankToNull(docId), docTime, true, patient, conditions, observations,
                medications, allergies);
    }

    private static SilverRecord.Patient patient(Doc d, Element role, String sourceSystem) {
        String id = blankToNull(d.string(role, "hl7:id[@extension][1]/@extension"));
        if (id == null) {
            throw new MappingException("patientRole/id has no @extension (patient identifier)");
        }
        Element person = d.element(role, "hl7:patient");
        if (person == null) {
            throw new MappingException("patientRole has no patient");
        }
        String given = blankToNull(d.string(person, "hl7:name[1]/hl7:given[1]"));
        String family = blankToNull(d.string(person, "hl7:name[1]/hl7:family[1]"));
        if (given == null || family == null) {
            throw new MappingException("patient/name needs given and family");
        }
        Instant birth = Hl7SilverMapper.timestamp(blankToNull(d.string(person, "hl7:birthTime/@value")), "patient/birthTime");
        if (birth == null) {
            throw new MappingException("patient/birthTime is missing");
        }
        String sex = blankToNull(d.string(person, "hl7:administrativeGenderCode/@code"));
        String gender = sex == null ? "unknown" : GENDERS.get(sex.toUpperCase());
        if (gender == null) {
            throw new MappingException("administrativeGenderCode '" + sex + "' is not M / F / UN");
        }
        String state = blankToNull(d.string(role, "hl7:addr[1]/hl7:state"));
        if (state != null && state.length() != 2) {
            throw new MappingException("addr/state '" + state + "' is not a 2-letter code");
        }
        String phone = blankToNull(d.string(role, "hl7:telecom[starts-with(@value,'tel:')][1]/@value"));
        if (phone != null) {
            phone = phone.substring(4);
        }
        return new SilverRecord.Patient(fit(sourceSystem, 100, "source system"), fit(id, 100, "patientRole/id"),
                fit(id, 50, "patientRole/id (MRN)"), fit(given, 100, "name/given"), fit(family, 100, "name/family"),
                utcDate(birth), gender, fit(d.string(role, "hl7:addr[1]/hl7:streetAddressLine[1]"), 200, "addr/streetAddressLine"),
                fit(d.string(role, "hl7:addr[1]/hl7:streetAddressLine[2]"), 200, "addr/streetAddressLine[2]"),
                fit(d.string(role, "hl7:addr[1]/hl7:city"), 100, "addr/city"), state,
                fit(d.string(role, "hl7:addr[1]/hl7:postalCode"), 10, "addr/postalCode"), fit(phone, 20, "telecom tel:"));
    }

    private static SilverRecord.Condition condition(Doc d, Element obs) {
        Element value = d.element(obs, "hl7:value");
        String code = value == null ? null : blankToNull(value.getAttribute("code"));
        String oid = value == null ? "" : value.getAttribute("codeSystem");
        if (code == null) {
            throw new MappingException("Problem observation has no value/@code");
        }
        String system = ICD10_OID.equals(oid) ? "ICD-10" : SNOMED_OID.equals(oid) ? "SNOMED-CT" : null;
        if (system == null) {
            throw new MappingException("Problem " + code + " codeSystem " + oid + " is not ICD-10-CM or SNOMED CT");
        }
        Instant onset = Hl7SilverMapper.timestamp(blankToNull(d.string(obs, "hl7:effectiveTime/hl7:low/@value")), "problem effectiveTime/low");
        Instant recorded = Hl7SilverMapper.timestamp(blankToNull(d.string(obs, "hl7:author/hl7:time/@value")), "problem author/time");
        return new SilverRecord.Condition(system, fit(code, 20, "problem value/@code"), clip(value.getAttribute("displayName"), 255),
                onset == null ? null : utcDate(onset), recorded == null ? null : utcDate(recorded));
    }

    private static SilverRecord.Medication medication(Doc d, Element sa) {
        Element code = d.element(sa, "hl7:consumable/hl7:manufacturedProduct/hl7:manufacturedMaterial/hl7:code");
        String rx = code == null ? null : blankToNull(code.getAttribute("code"));
        if (rx == null || !RXNORM_OID.equals(code.getAttribute("codeSystem"))) {
            throw new MappingException("Medication " + (rx == null ? "(no code)" : rx) + " is not coded in RxNorm");
        }
        String rawStatus = blankToNull(d.string(sa, "hl7:statusCode/@code"));
        String status = rawStatus == null ? "active" : MED_STATUS.get(rawStatus);
        if (status == null) {
            throw new MappingException("Medication " + rx + " statusCode '" + rawStatus + "' has no Silver equivalent");
        }
        Instant start = Hl7SilverMapper.timestamp(blankToNull(d.string(sa, "hl7:effectiveTime[hl7:low]/hl7:low/@value")), "medication effectiveTime/low");
        if (start == null) {
            start = Hl7SilverMapper.timestamp(blankToNull(d.string(sa, "hl7:author/hl7:time/@value")), "medication author/time");
        }
        if (start == null) {
            throw new MappingException("Medication " + rx + " has no start (effectiveTime/low or author/time)");
        }
        String dose = blankToNull(d.string(sa, "hl7:doseQuantity/@value"));
        String unit = blankToNull(d.string(sa, "hl7:doseQuantity/@unit"));
        String text = narrative(d, sa);
        String dosage = dose == null ? text : dose + (unit == null || "1".equals(unit) ? "" : " " + unit) + (text == null ? "" : ", " + text);
        return new SilverRecord.Medication(fit(rx, 20, "medication code"), clip(code.getAttribute("displayName"), 255),
                clip(dosage, 255), status, utcDate(start));
    }

    /** @return null for an aborted result with no value */
    private static SilverRecord.Observation observation(Doc d, Element obs, Instant organizerTime) {
        Element code = d.element(obs, "hl7:code");
        String loinc = code == null ? null : blankToNull(code.getAttribute("code"));
        if (loinc == null || !LOINC_OID.equals(code.getAttribute("codeSystem"))) {
            throw new MappingException("Result " + (loinc == null ? "(no code)" : loinc) + " is not coded in LOINC");
        }
        String rawStatus = blankToNull(d.string(obs, "hl7:statusCode/@code"));
        String status = rawStatus == null ? "final" : RESULT_STATUS.get(rawStatus);
        if (status == null) {
            throw new MappingException("Result " + loinc + " statusCode '" + rawStatus + "' has no Silver equivalent");
        }
        Element value = d.element(obs, "hl7:value");
        BigDecimal numeric = null;
        String text = null;
        String unit = null;
        if (value != null) {
            String type = value.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type");
            if (type.endsWith("PQ")) {
                numeric = Fields.number(value.getAttribute("value"));
                unit = blankToNull(value.getAttribute("unit"));
                if (numeric == null) {
                    text = clip(value.getAttribute("value"), 500);
                }
            } else if (type.endsWith("CD") || type.endsWith("CE") || type.endsWith("CO")) {
                text = clip(blankToNull(value.getAttribute("displayName")) != null ? value.getAttribute("displayName")
                        : value.getAttribute("code"), 500);
            } else {
                text = clip(value.getTextContent(), 500);
            }
        }
        if (numeric == null && text == null) {
            if ("cancelled".equals(status)) {
                return null;
            }
            throw new MappingException("Result " + loinc + " has no value");
        }
        Instant effective = time(d, obs, "result " + loinc);
        if (effective == null) {
            effective = organizerTime;
        }
        if (effective == null) {
            throw new MappingException("Result " + loinc + " has no effectiveTime (nor its organizer)");
        }
        if ("1".equals(unit)) {
            unit = null;
        }
        return new SilverRecord.Observation(fit(loinc, 20, "result code"), clip(code.getAttribute("displayName"), 255),
                numeric, text, fit(unit, 50, "result unit"), status, effective,
                Fields.number(d.string(obs, "hl7:referenceRange/hl7:observationRange/hl7:value/hl7:low/@value")),
                Fields.number(d.string(obs, "hl7:referenceRange/hl7:observationRange/hl7:value/hl7:high/@value")),
                fit(d.string(obs, "hl7:interpretationCode/@code"), 5, "interpretationCode"));
    }

    private static SilverRecord.Allergy allergy(Doc d, Element obs) {
        Element allergen = d.element(obs, "hl7:participant/hl7:participantRole/hl7:playingEntity/hl7:code");
        String code = allergen == null ? null : blankToNull(allergen.getAttribute("code"));
        String display = allergen == null ? null : blankToNull(allergen.getAttribute("displayName"));
        if (code == null && display == null) {
            throw new MappingException("Allergy observation has no allergen (participant/…/playingEntity/code)");
        }
        String oid = allergen.getAttribute("codeSystem");
        String system = RXNORM_OID.equals(oid) ? "RXNORM" : SNOMED_OID.equals(oid) ? "SNOMED-CT" : "LOCAL";
        // Map.of / Set.of reject null lookups (NullPointerException): every optional code is checked first.
        String typeCode = d.string(obs, "hl7:value/@code");
        String type = typeCode == null ? null : DRUG_ALLERGY.contains(typeCode) ? "DA" : FOOD_ALLERGY.contains(typeCode) ? "FA"
                : ENV_ALLERGY.contains(typeCode) ? "EA" : "MA";
        String severityCode = d.string(obs, ".//hl7:observation[hl7:code/@code='SEV']/hl7:value/@code");
        String severity = severityCode == null ? null : SEVERITY.get(severityCode);
        String reaction = blankToNull(d.string(obs,
                "hl7:entryRelationship/hl7:observation[not(hl7:code/@code='SEV')][1]/hl7:value/@displayName"));
        return new SilverRecord.Allergy(system, fit(code != null ? code : display, 20, "allergen code"), clip(display, 255),
                type, severity, clip(reaction, 255));
    }

    private static Instant time(Doc d, Element e, String where) {
        String v = blankToNull(d.string(e, "hl7:effectiveTime/@value"));
        if (v == null) {
            v = blankToNull(d.string(e, "hl7:effectiveTime/hl7:low/@value"));
        }
        return Hl7SilverMapper.timestamp(v, where + " effectiveTime");
    }

    /** An entry's own text, or the section narrative it points to with {@code <reference value="#id"/>}. */
    private static String narrative(Doc d, Element e) {
        String own = blankToNull(d.string(e, "hl7:text"));
        if (own != null) {
            return own;
        }
        String ref = blankToNull(d.string(e, "hl7:text/hl7:reference/@value"));
        if (ref == null || !ref.startsWith("#") || !ref.substring(1).matches("[A-Za-z0-9._-]+")) {
            return null;
        }
        return blankToNull(d.string(d.doc.getDocumentElement(), "//*[@ID='" + ref.substring(1) + "']"));
    }

    private static Element section(Doc d, Element root, String loinc) {
        return d.element(root, "hl7:component/hl7:structuredBody/hl7:component/hl7:section[hl7:code/@code='" + loinc + "']");
    }

    /** A parsed document + namespace-aware XPath (neither is thread-safe: one per call). */
    private static final class Doc {
        final Document doc;
        final XPath xpath;

        private Doc(Document doc) {
            this.doc = doc;
            this.xpath = XPathFactory.newInstance().newXPath();
            this.xpath.setNamespaceContext(new NamespaceContext() {
                @Override
                public String getNamespaceURI(String prefix) {
                    return "hl7".equals(prefix) ? NS : XMLConstants.NULL_NS_URI;
                }

                @Override
                public String getPrefix(String uri) {
                    return NS.equals(uri) ? "hl7" : null;
                }

                @Override
                public Iterator<String> getPrefixes(String uri) {
                    return List.of("hl7").iterator();
                }
            });
        }

        static Doc parse(String raw) {
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(true);
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                f.setFeature("http://xml.org/sax/features/external-general-entities", false);
                f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                f.setXIncludeAware(false);
                f.setExpandEntityReferences(false);
                DocumentBuilder b = f.newDocumentBuilder();
                b.setErrorHandler(new DefaultHandler());
                Document doc = b.parse(new InputSource(new StringReader(raw)));
                Element root = doc.getDocumentElement();
                if (!"ClinicalDocument".equals(root.getLocalName()) || !NS.equals(root.getNamespaceURI())) {
                    throw new MappingException("Not an HL7 v3 ClinicalDocument");
                }
                return new Doc(doc);
            } catch (MappingException e) {
                throw e;
            } catch (Exception e) {
                throw new MappingException("C-CDA is not well-formed XML: " + e.getMessage());
            }
        }

        Element element(Element context, String expr) {
            return (Element) eval(context, expr, XPathConstants.NODE);
        }

        List<Element> elements(Element context, String expr) {
            NodeList nodes = (NodeList) eval(context, expr, XPathConstants.NODESET);
            List<Element> out = new ArrayList<>(nodes.getLength());
            for (int i = 0; i < nodes.getLength(); i++) {
                out.add((Element) nodes.item(i));
            }
            return out;
        }

        String string(Element context, String expr) {
            String s = (String) eval(context, expr, XPathConstants.STRING);
            return s == null || s.isBlank() ? null : s.trim();
        }

        private Object eval(Element context, String expr, javax.xml.namespace.QName type) {
            try {
                return xpath.evaluate(expr, context, type);
            } catch (XPathExpressionException e) {
                throw new IllegalStateException("Bad XPath expression: " + expr, e);
            }
        }
    }
}
