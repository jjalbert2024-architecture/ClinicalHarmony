package com.clinicalharmony.fhircompliance.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.List;

/**
 * Request to check FHIR resources for US Core required-field completeness. Uses the same
 * {@code patientId} + {@code fhirResources} shape as rules-engine-service's
 * PatientRecordRequest.fhirResources() field, so the same payload can be sent to either
 * service's FHIR check.
 */
public record FhirValidationRequest(
        @NotNull Long patientId,
        @NotEmpty List<Map<String, Object>> fhirResources
) {
}
