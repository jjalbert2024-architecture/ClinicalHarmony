package com.clinicalharmony.fhircompliance.validation;

/** The submitted payload isn't parseable as a FHIR R4 resource at all (bad JSON, unknown resourceType...). */
public class InvalidFhirResourceException extends RuntimeException {
    public InvalidFhirResourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
