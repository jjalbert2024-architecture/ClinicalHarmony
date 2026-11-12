package com.clinicalharmony.patientindex.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A gold.master_patients row (read-only here; GoldWriter writes it). */
public record MasterRow(long id, UUID masterPatientUuid, String firstName, String lastName, LocalDate dateOfBirth,
                        String gender, String matchStatus, boolean active, BigDecimal matchConfidenceScore,
                        int sourcePatientCount, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
