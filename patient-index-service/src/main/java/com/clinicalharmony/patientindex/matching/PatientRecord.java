package com.clinicalharmony.patientindex.matching;

import java.time.LocalDate;

/**
 * One silver.patients row as the matcher sees it, plus the master it currently belongs to.
 *
 * @param masterPatientId null for a Silver patient fhir-compliance-service hasn't built yet (no master)
 * @param masterStatus    PROVISIONAL / MATCHED (only active masters are matched against), or null
 */
public record PatientRecord(
        long silverPatientId,
        Long masterPatientId,
        String masterStatus,
        String sourceSystem,
        String sourcePatientId,
        String mrn,
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        String gender,
        String addressLine1,
        String city,
        String state,
        String postalCode,
        String phone
) {
    public boolean provisional() {
        return "PROVISIONAL".equals(masterStatus);
    }
}
