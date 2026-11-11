package com.clinicalharmony.fhircompliance.service.ccda;

import java.util.Map;

/**
 * C-CDA identifies code systems by OID; FHIR by canonical URI. This maps the OIDs that show up in
 * Problems / Medications / Results / patient demographics to the URIs US Core expects (per the
 * HL7 terminology registry). Unknown OIDs fall back to {@code urn:oid:<oid>}, which is still a
 * valid, resolvable FHIR system — just not one the validator has content for.
 */
public final class CodeSystems {

    public static final String SNOMED_OID = "2.16.840.1.113883.6.96";
    public static final String LOINC_OID = "2.16.840.1.113883.6.1";
    public static final String RXNORM_OID = "2.16.840.1.113883.6.88";
    public static final String ICD10CM_OID = "2.16.840.1.113883.6.90";

    public static final String SNOMED = "http://snomed.info/sct";
    public static final String LOINC = "http://loinc.org";
    public static final String RXNORM = "http://www.nlm.nih.gov/research/umls/rxnorm";
    public static final String UCUM = "http://unitsofmeasure.org";
    public static final String CDC_RACE_ETHNICITY = "urn:oid:2.16.840.1.113883.6.238";
    public static final String BCP47 = "urn:ietf:bcp:47";

    private static final Map<String, String> BY_OID = Map.ofEntries(
            Map.entry(SNOMED_OID, SNOMED),
            Map.entry(LOINC_OID, LOINC),
            Map.entry(RXNORM_OID, RXNORM),
            Map.entry(ICD10CM_OID, "http://hl7.org/fhir/sid/icd-10-cm"),
            Map.entry("2.16.840.1.113883.6.3", "http://hl7.org/fhir/sid/icd-10"),
            Map.entry("2.16.840.1.113883.6.103", "http://hl7.org/fhir/sid/icd-9-cm"),
            Map.entry("2.16.840.1.113883.6.8", UCUM),
            Map.entry("2.16.840.1.113883.6.69", "http://hl7.org/fhir/sid/ndc"),
            Map.entry("2.16.840.1.113883.12.292", "http://hl7.org/fhir/sid/cvx"),
            Map.entry("2.16.840.1.113883.6.238", CDC_RACE_ETHNICITY),
            Map.entry("2.16.840.1.113883.3.26.1.1", "http://ncimeta.nci.nih.gov"),
            Map.entry("2.16.840.1.113883.5.83", "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation"),
            Map.entry("2.16.840.1.113883.5.1", "http://terminology.hl7.org/CodeSystem/v3-AdministrativeGender"),
            Map.entry("2.16.840.1.113883.5.4", "http://terminology.hl7.org/CodeSystem/v3-ActCode"),
            Map.entry("2.16.840.1.113883.5.1008", "http://terminology.hl7.org/CodeSystem/v3-NullFlavor")
    );

    private CodeSystems() {
    }

    /** FHIR system URI for a C-CDA codeSystem OID; null in → null out. */
    public static String uriForOid(String oid) {
        if (oid == null || oid.isBlank()) {
            return null;
        }
        return BY_OID.getOrDefault(oid.trim(), "urn:oid:" + oid.trim());
    }
}
