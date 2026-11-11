package com.clinicalharmony.fhircompliance.service.ccda;

/** The C-CDA payload exceeds {@code ccda.max-document-bytes}. Mapped to HTTP 413. */
public class CcdaDocumentTooLargeException extends RuntimeException {
    public CcdaDocumentTooLargeException(long actualBytes, long maxBytes) {
        super("C-CDA document is " + actualBytes + " bytes; the limit is " + maxBytes + " bytes");
    }
}
