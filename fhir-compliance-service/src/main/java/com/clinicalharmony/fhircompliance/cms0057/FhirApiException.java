package com.clinicalharmony.fhircompliance.cms0057;

/**
 * Raised by the CMS-0057-F read API; rendered as a FHIR OperationOutcome with this HTTP status
 * and issue code by FhirR4ExceptionHandler.
 */
public class FhirApiException extends RuntimeException {

    private final int httpStatus;
    private final String issueCode;

    private FhirApiException(int httpStatus, String issueCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.issueCode = issueCode;
    }

    public static FhirApiException notFound(String resourceType, String id) {
        return new FhirApiException(404, "not-found", "Resource " + resourceType + "/" + id + " is not known");
    }

    public static FhirApiException unsupportedType(String resourceType) {
        return new FhirApiException(404, "not-supported", "Resource type '" + resourceType
                + "' is not supported by this endpoint. Supported: " + String.join(", ", Cms0057SearchParameters.SUPPORTED_TYPES));
    }

    public static FhirApiException invalidSearch(String message) {
        return new FhirApiException(400, "invalid", message);
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String issueCode() {
        return issueCode;
    }
}
