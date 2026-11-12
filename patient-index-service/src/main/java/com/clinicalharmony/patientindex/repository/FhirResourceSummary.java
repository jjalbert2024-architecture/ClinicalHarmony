package com.clinicalharmony.patientindex.repository;

/** A gold.fhir_resources row linked to a master, without its JSON body. */
public record FhirResourceSummary(long id, String resourceType, String fhirId, boolean usCoreCompliant) {
}
