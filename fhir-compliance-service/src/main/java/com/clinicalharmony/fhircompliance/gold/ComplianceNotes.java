package com.clinicalharmony.fhircompliance.gold;

import com.clinicalharmony.fhircompliance.validation.FhirValidationOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the gold.fhir_resources.compliance_notes JSON (shape documented in migration 16) from an
 * HL7 validator outcome. Shared by every validated Gold write path — Silver builds and seed
 * revalidation — so the notes look the same regardless of where the resource came from.
 */
public final class ComplianceNotes {

    public static final String VALIDATOR_NAME =
            "HL7 FHIR Validator (HAPI FHIR 8.2.0) / US Core 6.1.0, Da Vinci US Drug Formulary 2.1.0, Da Vinci DEQM 5.0.0";

    /** compliance_notes.status of rows seeded straight into Gold that no validator has looked at yet (migration 19). */
    public static final String PENDING_VALIDATION = "PENDING_VALIDATION";

    private ComplianceNotes() {
    }

    public static String toJson(FhirValidationOutcome outcome, Instant validatedAt, ObjectMapper objectMapper) {
        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("validator", VALIDATOR_NAME);
        notes.put("profile", outcome.profile());
        notes.put("validatedAt", validatedAt.toString());
        notes.put("compliant", outcome.compliant());
        notes.put("errorCount", outcome.errorCount());
        notes.put("warningCount", outcome.warningCount());
        notes.put("violations", outcome.violations());
        try {
            return objectMapper.writeValueAsString(notes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize compliance notes for "
                    + outcome.resourceType() + "/" + outcome.resourceId(), e);
        }
    }
}
