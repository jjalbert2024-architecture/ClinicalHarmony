package com.clinicalharmony.fhircompliance.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/fhir/gold/masters/{id}/confirm and /merges/{id}/unmerge. */
public record MasterDecisionRequest(
        @NotBlank @Size(max = 60) String decidedBy,
        @Size(max = 2000) String reason
) {
}
