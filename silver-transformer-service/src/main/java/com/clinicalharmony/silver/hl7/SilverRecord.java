package com.clinicalharmony.silver.hl7;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One Bronze payload (HL7 v2 message, FHIR Bundle, claims line or C-CDA document) mapped to Silver shapes,
 * before anything is written. Every field is either copied
 * from the message or explicitly defaulted here; nothing is invented ("map, never invent").
 *
 * @param messageType     e.g. {@code ADT^A08}, {@code FHIR Bundle}, {@code CLAIM}, {@code C-CDA}
 * @param updatesDemographics true for ADT: the registration system is the source of truth for
 *                        demographics, so an ADT overwrites them; results and orders only create a
 *                        patient that isn't there yet
 */
public record SilverRecord(String messageType, String controlId, Instant messageTime, boolean updatesDemographics,
                           Patient patient, List<Condition> conditions, List<Observation> observations,
                           List<Medication> medications, List<Allergy> allergies, List<Claim> claims) {

    /** Without claim lines — every format but claims CSV. */
    public SilverRecord(String messageType, String controlId, Instant messageTime, boolean updatesDemographics,
                        Patient patient, List<Condition> conditions, List<Observation> observations,
                        List<Medication> medications, List<Allergy> allergies) {
        this(messageType, controlId, messageTime, updatesDemographics, patient, conditions, observations, medications,
                allergies, List.of());
    }

    /**
     * One claims CSV line (silver.claims, migration 27) and, when the feed says, where the claim is now (migration 28;
     * every lifecycle field is optional / NULL).
     */
    public record Claim(String claimId, LocalDate serviceDate, String icd10Code, BigDecimal billedAmount,
                        Lifecycle lifecycle) {

        public Claim(String claimId, LocalDate serviceDate, String icd10Code, BigDecimal billedAmount) {
            this(claimId, serviceDate, icd10Code, billedAmount, Lifecycle.NONE);
        }
    }

    /** A claim's payer-side state; {@link #NONE} = a file without the lifecycle columns. */
    public record Lifecycle(String payerId, String payerName, String status, LocalDate submittedDate,
                            LocalDate statusDate, BigDecimal allowedAmount, BigDecimal paidAmount,
                            BigDecimal patientResponsibility, String statusReason) {
        public static final Lifecycle NONE = new Lifecycle(null, null, null, null, null, null, null, null, null);
    }

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
