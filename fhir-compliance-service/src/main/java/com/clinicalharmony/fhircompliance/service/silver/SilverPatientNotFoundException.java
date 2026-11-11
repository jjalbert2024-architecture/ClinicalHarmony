package com.clinicalharmony.fhircompliance.service.silver;

public class SilverPatientNotFoundException extends RuntimeException {
    public SilverPatientNotFoundException(long silverPatientId) {
        super("No silver.patients row with id " + silverPatientId);
    }
}
