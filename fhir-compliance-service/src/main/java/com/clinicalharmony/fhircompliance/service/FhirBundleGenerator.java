package com.clinicalharmony.fhircompliance.service;

import com.clinicalharmony.fhircompliance.web.dto.PatientRecordRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a FHIR {@code Bundle} (type "collection") from a canonical PatientRecordRequest — the
 * generation half of this service's "US Core IG conformance + FHIR resource generation" scope.
 * Resources are built directly as ordered maps (not a FHIR object model library) — consistent
 * with how ingestion-service and rules-engine-service already treat FHIR resources as plain
 * {@code Map<String, Object>}, and every resource this produces is US Core structurally
 * complete by construction (it can be round-tripped through this service's own
 * {@code /validate} endpoint to demonstrate that).
 */
@Service
public class FhirBundleGenerator {

    private static final Map<String, String> CODE_SYSTEM_URIS = Map.of(
            "ICD-10", "http://hl7.org/fhir/sid/icd-10-cm",
            "SNOMED-CT", "http://snomed.info/sct",
            "LOINC", "http://loinc.org",
            "RXNORM", "http://www.nlm.nih.gov/research/umls/rxnorm"
    );

    public Map<String, Object> generateBundle(PatientRecordRequest request) {
        String patientResourceId = "patient-" + request.patientId();
        Map<String, Object> subjectRef = Map.of("reference", "Patient/" + patientResourceId);

        List<Object> resources = new ArrayList<>();
        resources.add(buildPatient(request, patientResourceId));

        if (request.conditions() != null) {
            request.conditions().forEach(c -> resources.add(buildCondition(c, subjectRef)));
        }
        if (request.observations() != null) {
            request.observations().forEach(o -> resources.add(buildObservation(o, subjectRef)));
        }
        if (request.medications() != null) {
            request.medications().forEach(m -> resources.add(buildMedicationRequest(m, subjectRef)));
        }
        if (request.allergies() != null) {
            request.allergies().forEach(a -> resources.add(buildAllergyIntolerance(a, subjectRef)));
        }
        if (request.encounters() != null) {
            request.encounters().forEach(e -> resources.add(buildEncounter(e, subjectRef)));
        }
        if (request.diagnosticReports() != null) {
            request.diagnosticReports().forEach(d -> resources.add(buildDiagnosticReport(d, subjectRef)));
        }

        List<Object> entries = resources.stream()
                .map(resource -> ordered("resource", resource))
                .map(Object.class::cast)
                .toList();

        return ordered("resourceType", "Bundle", "type", "collection", "entry", entries);
    }

    private Map<String, Object> buildPatient(PatientRecordRequest request, String id) {
        List<Object> identifier = List.of(ordered(
                "system", "urn:clinicalharmony:patientId",
                "value", String.valueOf(request.patientId())));
        List<Object> name = (request.givenName() == null && request.familyName() == null) ? null : List.of(ordered(
                "family", request.familyName(),
                "given", request.givenName() == null ? null : List.of(request.givenName())));

        return ordered(
                "resourceType", "Patient",
                "id", id,
                "identifier", identifier,
                "name", name,
                "gender", request.gender(),
                "birthDate", request.dateOfBirth() == null ? null : request.dateOfBirth().toString());
    }

    private Map<String, Object> buildCondition(PatientRecordRequest.ConditionItem item, Map<String, Object> subjectRef) {
        Map<String, Object> code = ordered("coding", List.of(ordered(
                "system", codeSystemUri(item.codeSystem()),
                "code", item.code(),
                "display", item.display())));
        Map<String, Object> clinicalStatus = ordered("coding", List.of(ordered(
                "system", "http://terminology.hl7.org/CodeSystem/condition-clinical",
                "code", "active")));

        return ordered(
                "resourceType", "Condition",
                "id", "condition-" + item.id(),
                "clinicalStatus", clinicalStatus,
                "code", code,
                "subject", subjectRef,
                "onsetDateTime", item.onsetDate() == null ? null : item.onsetDate().toString());
    }

    private Map<String, Object> buildObservation(PatientRecordRequest.ObservationItem item, Map<String, Object> subjectRef) {
        Map<String, Object> category = ordered("coding", List.of(ordered(
                "system", "http://terminology.hl7.org/CodeSystem/observation-category",
                "code", "laboratory")));
        Map<String, Object> code = ordered("coding", List.of(ordered(
                "system", codeSystemUri("LOINC"),
                "code", item.code(),
                "display", item.display())));
        Map<String, Object> valueQuantity = item.valueNumeric() == null ? null : ordered(
                "value", item.valueNumeric(),
                "unit", item.unit(),
                "system", "http://unitsofmeasure.org",
                "code", item.unit());

        return ordered(
                "resourceType", "Observation",
                "id", "observation-" + item.id(),
                "status", "final",
                "category", List.of(category),
                "code", code,
                "subject", subjectRef,
                "effectiveDateTime", item.effectiveDate() == null ? null : item.effectiveDate().toString(),
                "valueQuantity", valueQuantity);
    }

    private Map<String, Object> buildMedicationRequest(PatientRecordRequest.MedicationItem item, Map<String, Object> subjectRef) {
        Map<String, Object> medication = ordered("coding", List.of(ordered(
                "system", codeSystemUri("RXNORM"),
                "code", item.rxnormCode(),
                "display", item.display())));

        return ordered(
                "resourceType", "MedicationRequest",
                "id", "medicationrequest-" + item.id(),
                "status", "active",
                "intent", "order",
                "medicationCodeableConcept", medication,
                "subject", subjectRef,
                "authoredOn", item.startDate() == null ? null : item.startDate().toString());
    }

    private Map<String, Object> buildAllergyIntolerance(PatientRecordRequest.AllergyItem item, Map<String, Object> patientRef) {
        Map<String, Object> code = item.rxnormCode() != null
                ? ordered("coding", List.of(ordered("system", codeSystemUri("RXNORM"), "code", item.rxnormCode(), "display", item.substance())))
                : ordered("text", item.substance());
        Map<String, Object> clinicalStatus = ordered("coding", List.of(ordered(
                "system", "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical",
                "code", "active")));

        return ordered(
                "resourceType", "AllergyIntolerance",
                "id", "allergyintolerance-" + item.id(),
                "clinicalStatus", clinicalStatus,
                "code", code,
                "patient", patientRef);
    }

    private Map<String, Object> buildEncounter(PatientRecordRequest.EncounterItem item, Map<String, Object> subjectRef) {
        Map<String, Object> classCoding = ordered(
                "system", "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                "code", item.classCode());
        Map<String, Object> period = ordered(
                "start", item.startDate() == null ? null : item.startDate().toString(),
                "end", item.endDate() == null ? null : item.endDate().toString());

        return ordered(
                "resourceType", "Encounter",
                "id", "encounter-" + item.id(),
                "status", item.status(),
                "class", classCoding,
                "subject", subjectRef,
                "period", period);
    }

    private Map<String, Object> buildDiagnosticReport(PatientRecordRequest.DiagnosticReportItem item, Map<String, Object> subjectRef) {
        Map<String, Object> code = ordered("coding", List.of(ordered(
                "system", codeSystemUri("LOINC"),
                "code", item.code(),
                "display", item.display())));

        return ordered(
                "resourceType", "DiagnosticReport",
                "id", "diagnosticreport-" + item.id(),
                "status", item.status() == null ? "final" : item.status(),
                "code", code,
                "subject", subjectRef,
                "issued", item.issued() == null ? null : item.issued().toString());
    }

    private static String codeSystemUri(String codeSystem) {
        if (codeSystem == null) {
            return null;
        }
        return CODE_SYSTEM_URIS.getOrDefault(codeSystem.toUpperCase(), codeSystem);
    }

    /** Builds a LinkedHashMap from alternating key/value pairs, omitting any pair whose value is null. */
    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                map.put((String) kv[i], kv[i + 1]);
            }
        }
        return map;
    }
}
