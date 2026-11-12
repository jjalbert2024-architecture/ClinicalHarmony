package com.clinicalharmony.fhircompliance.gold;

/** A master patient or merge id the caller named doesn't exist (→ 404). */
public class GoldMasterNotFoundException extends RuntimeException {
    public GoldMasterNotFoundException(String message) {
        super(message);
    }
}
