package com.clinicalharmony.fhircompliance.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Body of POST /api/fhir/gold/masters/merge (called by patient-index-service). */
public record MergeMastersRequest(
        @NotNull Long survivorMasterId,
        @NotNull Long absorbedMasterId,
        @DecimalMin("0") @DecimalMax("1") BigDecimal matchConfidenceScore,
        @NotBlank @Pattern(regexp = "DETERMINISTIC|PROBABILISTIC|MANUAL",
                message = "must be DETERMINISTIC, PROBABILISTIC or MANUAL") String matchMethod,
        @NotBlank @Size(max = 60) String decidedBy,
        JsonNode evidence
) {
}
