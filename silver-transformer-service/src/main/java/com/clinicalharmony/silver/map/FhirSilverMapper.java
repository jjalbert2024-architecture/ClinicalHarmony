package com.clinicalharmony.silver.map;

import com.clinicalharmony.silver.hl7.MappingException;
import com.clinicalharmony.silver.hl7.SilverRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.clinicalharmony.silver.map.Fields.blankToNull;
import static com.clinicalharmony.silver.map.Fields.clip;
import static com.clinicalharmony.silver.map.Fields.fhirDate;
import static com.clinicalharmony.silver.map.Fields.fhirInstant;
import static com.clinicalharmony.silver.map.Fields.fit;
import static com.clinicalharmony.silver.map.Fields.lower;

/**
 * FHIR R4 JSON → Silver (9d part 2). Maps a Bundle (or a lone Patient): its Patient is the Silver patient
 * (source system = the Bronze row's, i.e. the X-Source-System header; id = the identifier typed MR, else the first
 * identifier), and every Condition / Observation / MedicationRequest / AllergyIntolerance that references that
 * Patient becomes a Silver row. Other resource types are not part of the Silver model and are ignored.
 *
 * <p>Strict like the HL7 mapper ("map, never invent"): a clinical resource that references anything but the
 * Bundle's Patient, a code from an unsupported system, or a missing date the column needs → MappingException
 * (Bronze FAILED with the reason). A Patient is authoritative for its demographics (like an ADT).
 */
@Component
public class FhirSilverMapper {

    static final String ICD10 = "http://hl7.org/fhir/sid/icd-10-cm";
    static final String ICD10_WHO = "http://hl7.org/fhir/sid/icd-10";
    static final String SNOMED = "http://snomed.info/sct";
    static final String LOINC = "http://loinc.org";
    static final String RXNORM = "http://www.nlm.nih.gov/research/umls/rxnorm";

    private static final Set<String> GENDERS = Set.of("male", "female", "other", "unknown");
    private static final Map<String, String> MED_STATUS = Map.of("active", "active", "completed", "completed",
            "stopped", "stopped", "on-hold", "on-hold", "entered-in-error", "entered-in-error", "cancelled", "stopped",
            "draft", "unknown", "unknown", "unknown");
    private static final Set<String> OBS_STATUS = Set.of("registered", "preliminary", "final", "amended", "corrected",
            "cancelled");
    private static final Map<String, String> ALLERGY_CATEGORY = Map.of("medication", "DA", "food", "FA",
            "environment", "EA", "biologic", "MA");
    private static final Map<String, String> REACTION_SEVERITY = Map.of("severe", "SV", "moderate", "MO", "mild", "MI");

    private final ObjectMapper mapper;

    public FhirSilverMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public SilverRecord map(String raw, String sourceSystem) {
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (Exception e) {
            throw new MappingException("FHIR payload is not JSON: " + e.getMessage());
        }
        String type = root.path("resourceType").asText("");
        List<JsonNode> resources = new ArrayList<>();
        Set<String> patientRefs = new HashSet<>();
        JsonNode patientNode = null;
        if ("Bundle".equals(type)) {
            for (JsonNode entry : root.path("entry")) {
                JsonNode r = entry.path("resource");
                if ("Patient".equals(r.path("resourceType").asText())) {
                    if (patientNode != null) {
                        throw new MappingException("Bundle holds more than one Patient");
                    }
                    patientNode = r;
                    String fullUrl = blankToNull(entry.path("fullUrl").asText(null));
                    if (fullUrl != null) {
                        patientRefs.add(fullUrl);
                    }
                } else {
                    resources.add(r);
                }
            }
        } else if ("Patient".equals(type)) {
            patientNode = root;
        } else {
            throw new MappingException("FHIR " + type + " is not mapped on its own: send a Bundle with its Patient");
        }
        if (patientNode == null) {
            throw new MappingException("Bundle has no Patient");
        }
        String patientId = blankToNull(patientNode.path("id").asText(null));
        if (patientId != null) {
            patientRefs.add("Patient/" + patientId);
        }

        SilverRecord.Patient patient = patient(patientNode, sourceSystem);
        List<SilverRecord.Condition> conditions = new ArrayList<>();
        List<SilverRecord.Observation> observations = new ArrayList<>();
        List<SilverRecord.Medication> medications = new ArrayList<>();
        List<SilverRecord.Allergy> allergies = new ArrayList<>();
        for (JsonNode r : resources) {
            String rt = r.path("resourceType").asText("");
            String label = rt + "/" + r.path("id").asText("?");
            switch (rt) {
                case "Condition" -> {
                    checkSubject(r, "subject", patientRefs, label);
                    conditions.add(condition(r, label));
                }
                case "Observation" -> {
                    checkSubject(r, "subject", patientRefs, label);
                    observations.add(observation(r, label));
                }
                case "MedicationRequest" -> {
                    checkSubject(r, "subject", patientRefs, label);
                    medications.add(medication(r, label));
                }
                case "AllergyIntolerance" -> {
                    checkSubject(r, "patient", patientRefs, label);
                    allergies.add(allergy(r, label));
                }
                default -> {
                    // Encounter, Practitioner, Organization…: not part of the Silver model
                }
            }
        }
        String messageType = "Bundle".equals(type) ? "FHIR Bundle" : "FHIR Patient";
        return new SilverRecord(messageType, blankToNull(root.path("id").asText(null)), null, true, patient, conditions,
                observations, medications, allergies);
    }

    private static void checkSubject(JsonNode r, String field, Set<String> patientRefs, String label) {
        String ref = blankToNull(r.path(field).path("reference").asText(null));
        if (ref == null) {
            throw new MappingException(label + " has no " + field + " reference");
        }
        if (!patientRefs.contains(ref)) {
            throw new MappingException(label + " references " + ref + ", not the Bundle's Patient");
        }
    }

    private static SilverRecord.Patient patient(JsonNode p, String sourceSystem) {
        String id = null;
        for (JsonNode identifier : p.path("identifier")) {
            for (JsonNode coding : identifier.path("type").path("coding")) {
                if ("MR".equals(coding.path("code").asText())) {
                    id = blankToNull(identifier.path("value").asText(null));
                }
            }
            if (id != null) {
                break;
            }
        }
        if (id == null) {
            id = blankToNull(p.path("identifier").path(0).path("value").asText(null));
        }
        if (id == null) {
            throw new MappingException("Patient has no identifier value");
        }
        JsonNode name = p.path("name").path(0);
        String family = blankToNull(name.path("family").asText(null));
        String given = blankToNull(name.path("given").path(0).asText(null));
        if (family == null || given == null) {
            throw new MappingException("Patient.name needs family and given name");
        }
        LocalDate dob = fhirDate(p.path("birthDate").asText(null), "Patient.birthDate");
        if (dob == null) {
            throw new MappingException("Patient.birthDate is missing");
        }
        String gender = lower(p.path("gender").asText(null));
        if (gender == null) {
            gender = "unknown";
        } else if (!GENDERS.contains(gender)) {
            throw new MappingException("Patient.gender '" + gender + "' is not male / female / other / unknown");
        }
        JsonNode addr = p.path("address").path(0);
        String state = blankToNull(addr.path("state").asText(null));
        if (state != null && state.length() != 2) {
            throw new MappingException("Patient.address.state '" + state + "' is not a 2-letter code");
        }
        String phone = null;
        for (JsonNode t : p.path("telecom")) {
            if ("phone".equals(t.path("system").asText())) {
                phone = t.path("value").asText(null);
                break;
            }
        }
        return new SilverRecord.Patient(fit(sourceSystem, 100, "source system"), fit(id, 100, "Patient.identifier"),
                fit(id, 50, "Patient.identifier (MRN)"), fit(given, 100, "Patient.name.given"),
                fit(family, 100, "Patient.name.family"), dob, gender,
                fit(addr.path("line").path(0).asText(null), 200, "Patient.address.line[0]"),
                fit(addr.path("line").path(1).asText(null), 200, "Patient.address.line[1]"),
                fit(addr.path("city").asText(null), 100, "Patient.address.city"), state,
                fit(addr.path("postalCode").asText(null), 10, "Patient.address.postalCode"),
                fit(phone, 20, "Patient.telecom phone"));
    }

    private static SilverRecord.Condition condition(JsonNode r, String label) {
        String codeSystem = null;
        String code = null;
        String display = null;
        for (JsonNode c : r.path("code").path("coding")) {
            String system = c.path("system").asText("");
            if (ICD10.equals(system) || ICD10_WHO.equals(system)) {
                codeSystem = "ICD-10";
            } else if (SNOMED.equals(system)) {
                codeSystem = "SNOMED-CT";
            } else {
                continue;
            }
            code = blankToNull(c.path("code").asText(null));
            display = c.path("display").asText(null);
            break;
        }
        if (code == null) {
            throw new MappingException(label + " has no ICD-10-CM or SNOMED CT coding");
        }
        LocalDate onset = fhirDate(r.path("onsetDateTime").asText(null), label + ".onsetDateTime");
        LocalDate recorded = fhirDate(r.path("recordedDate").asText(null), label + ".recordedDate");
        return new SilverRecord.Condition(codeSystem, fit(code, 20, label + ".code"),
                clip(display != null ? display : r.path("code").path("text").asText(null), 255), onset, recorded);
    }

    private static SilverRecord.Observation observation(JsonNode r, String label) {
        JsonNode coding = codingOf(r.path("code"), LOINC);
        if (coding == null) {
            throw new MappingException(label + " has no LOINC coding");
        }
        String status = lower(r.path("status").asText(null));
        if (status == null) {
            status = "final";
        } else if (!OBS_STATUS.contains(status)) {
            throw new MappingException(label + ".status '" + status + "' has no Silver equivalent");
        }
        BigDecimal numeric = null;
        String text = null;
        String unit = null;
        if (r.has("valueQuantity")) {
            JsonNode q = r.path("valueQuantity");
            numeric = Fields.number(q.path("value").asText(null));
            if (numeric == null) {
                text = clip(q.path("value").asText(null), 500);
            }
            unit = blankToNull(q.path("unit").asText(null));
            if (unit == null) {
                unit = blankToNull(q.path("code").asText(null));
            }
        } else if (r.has("valueString")) {
            text = clip(r.path("valueString").asText(null), 500);
        } else if (r.has("valueCodeableConcept")) {
            JsonNode cc = r.path("valueCodeableConcept");
            text = clip(cc.has("text") ? cc.path("text").asText() : cc.path("coding").path(0).path("display").asText(null), 500);
        }
        if (numeric == null && text == null) {
            throw new MappingException(label + " has no value (valueQuantity / valueString / valueCodeableConcept)");
        }
        Instant effective = fhirInstant(r.path("effectiveDateTime").asText(null), label + ".effectiveDateTime");
        if (effective == null) {
            effective = fhirInstant(r.path("issued").asText(null), label + ".issued");
        }
        if (effective == null) {
            throw new MappingException(label + " has no effectiveDateTime or issued");
        }
        JsonNode range = r.path("referenceRange").path(0);
        String flag = blankToNull(r.path("interpretation").path(0).path("coding").path(0).path("code").asText(null));
        return new SilverRecord.Observation(fit(coding.path("code").asText(null), 20, label + ".code"),
                clip(coding.path("display").asText(null), 255), numeric, text, fit(unit, 50, label + ".valueQuantity.unit"),
                status, effective, Fields.number(range.path("low").path("value").asText(null)),
                Fields.number(range.path("high").path("value").asText(null)), fit(flag, 5, label + ".interpretation"));
    }

    private static SilverRecord.Medication medication(JsonNode r, String label) {
        JsonNode coding = codingOf(r.path("medicationCodeableConcept"), RXNORM);
        if (coding == null) {
            throw new MappingException(label + " has no RxNorm medicationCodeableConcept");
        }
        String raw = lower(r.path("status").asText(null));
        String status = raw == null ? "active" : MED_STATUS.get(raw);
        if (status == null) {
            throw new MappingException(label + ".status '" + raw + "' has no Silver equivalent");
        }
        LocalDate start = fhirDate(r.path("authoredOn").asText(null), label + ".authoredOn");
        if (start == null) {
            throw new MappingException(label + " has no authoredOn (Silver needs a start date)");
        }
        return new SilverRecord.Medication(fit(coding.path("code").asText(null), 20, label + ".medication"),
                clip(coding.path("display").asText(null), 255),
                clip(r.path("dosageInstruction").path(0).path("text").asText(null), 255), status, start);
    }

    private static SilverRecord.Allergy allergy(JsonNode r, String label) {
        String codeSystem = "LOCAL";
        String code = null;
        String display = null;
        for (JsonNode c : r.path("code").path("coding")) {
            String system = c.path("system").asText("");
            codeSystem = RXNORM.equals(system) ? "RXNORM" : SNOMED.equals(system) ? "SNOMED-CT" : "LOCAL";
            code = blankToNull(c.path("code").asText(null));
            display = c.path("display").asText(null);
            if (!"LOCAL".equals(codeSystem)) {
                break;
            }
        }
        String text = blankToNull(r.path("code").path("text").asText(null));
        if (code == null && text == null) {
            throw new MappingException(label + " has no allergen code or text");
        }
        String type = ALLERGY_CATEGORY.get(r.path("category").path(0).asText(""));
        String severity = REACTION_SEVERITY.get(r.path("reaction").path(0).path("severity").asText(""));
        if (severity == null) {
            String criticality = r.path("criticality").asText("");
            severity = "high".equals(criticality) ? "SV" : "low".equals(criticality) ? "MI" : null;
        }
        JsonNode manifestation = r.path("reaction").path(0).path("manifestation").path(0);
        String reaction = manifestation.has("text") ? manifestation.path("text").asText()
                : manifestation.path("coding").path(0).path("display").asText(null);
        return new SilverRecord.Allergy(codeSystem, fit(code != null ? code : text, 20, label + ".code"),
                clip(display != null ? display : text, 255), type, severity, clip(reaction, 255));
    }

    private static JsonNode codingOf(JsonNode concept, String system) {
        for (JsonNode c : concept.path("coding")) {
            if (system.equals(c.path("system").asText()) && blankToNull(c.path("code").asText(null)) != null) {
                return c;
            }
        }
        return null;
    }
}
