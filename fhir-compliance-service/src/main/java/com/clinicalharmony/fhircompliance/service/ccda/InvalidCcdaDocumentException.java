package com.clinicalharmony.fhircompliance.service.ccda;

/**
 * The submitted payload can't be treated as a C-CDA document at all: not well-formed XML, a
 * DOCTYPE (rejected outright as an XXE defense), a root other than {@code urn:hl7-org:v3}
 * ClinicalDocument, or no recordTarget/patientRole. Mapped to HTTP 400.
 *
 * <p>Clinical gaps inside an otherwise valid document (a problem with no code, a patient with no
 * gender...) are not this exception. Those convert, and the US Core validator reports them.
 */
public class InvalidCcdaDocumentException extends RuntimeException {
    public InvalidCcdaDocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
