package com.clinicalharmony.fhircompliance.repository.silver;

import java.time.LocalDate;

/** One row of silver.patients. */
public record SilverPatientRow(
        long id,
        String sourceSystem,
        String sourcePatientId,
        String mrn,
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        String gender,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String postalCode,
        String phone
) {
}
