package com.clinicalharmony.fhircompliance.service.ccda;

import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;

/**
 * One FHIR resource produced from the C-CDA, and how it fared against US Core.
 *
 * @param fullUrl       the resource's {@code Bundle.entry.fullUrl} ({@code urn:uuid:...})
 * @param sourceSection where in the C-CDA it came from, e.g. {@code "Problems (11450-4)"}
 * @param validation    the Slice 1 HL7 validator's full outcome for this resource
 */
public record ConvertedResource(
        String fullUrl,
        String resourceType,
        String resourceId,
        String sourceSection,
        FhirValidationOutcome validation
) {
}
