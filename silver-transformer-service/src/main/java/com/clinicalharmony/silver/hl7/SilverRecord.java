package com.clinicalharmony.silver.hl7;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One HL7 message mapped to Silver shapes, before anything is written. Every field is either copied
 * from the message or explicitly defaulted here; nothing is invented ("map, never invent").
 *
 * @param messageType     e.g. {@code ADT^A08}
 * @param updatesDemographics true for ADT: the registration system is the source of truth for
 *                        demographics, so an ADT overwrites them; results and orders only create a
 *                        patient that isn't there yet
 */
public record SilverRecord(String messageType, String controlId, Instant messageTime, boolean updatesDemographics,
                           Patient patient, List<Condition> conditions, List<Observation> observations,
                           List<Medication> medications, List<Allergy> allergies) {

    /**
     * @param sourceSystem    PID-3.4 assigning authority (whose MRN this is), else MSH-4 sending facility —
     *                        so a lab result for a hospital's patient lands on that hospital's Silver patient
     */
    public record Patient(String sourceSystem, String sourcePatientId, String mrn, String firstName, String lastName,
                          LocalDate dateOfBirth, String gender, String addressLine1, String addressLine2, String city,
                          String state, String postalCode, String phone) {
    }

    /** {@code codeSystem}: ICD-10 or SNOMED-CT (silver.conditions CHECK). */
    public record Condition(String codeSystem, String code, String display, LocalDate onsetDate, LocalDate recordedDate) {
    }

    public record Observation(String code, String display, BigDecimal valueNumeric, String valueText, String unit,
                              String status, Instant effectiveAt, BigDecimal referenceLow, BigDecimal referenceHigh,
                              String abnormalFlag) {
    }

    public record Medication(String rxnormCode, String display, String dosageText, String status, LocalDate startDate) {
    }

    /** {@code codeSystem}: RXNORM, SNOMED-CT or LOCAL (silver.allergies CHECK). */
    public record Allergy(String codeSystem, String code, String display, String allergyType, String severity,
                          String reaction) {
    }
}
