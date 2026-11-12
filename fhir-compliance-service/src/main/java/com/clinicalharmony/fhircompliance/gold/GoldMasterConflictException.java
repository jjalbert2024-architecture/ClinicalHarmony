package com.clinicalharmony.fhircompliance.gold;

/** A master-patient operation that the current Gold state doesn't allow (→ 409). Nothing was written. */
public class GoldMasterConflictException extends RuntimeException {
    public GoldMasterConflictException(String message) {
        super(message);
    }
}
