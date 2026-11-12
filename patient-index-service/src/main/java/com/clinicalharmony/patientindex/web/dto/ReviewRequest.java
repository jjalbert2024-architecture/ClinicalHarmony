package com.clinicalharmony.patientindex.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/pmi/candidates/{id}/accept and /reject. */
public record ReviewRequest(
        @NotBlank @Size(max = 50) String reviewer,
        @Size(max = 2000) String note
) {
}
