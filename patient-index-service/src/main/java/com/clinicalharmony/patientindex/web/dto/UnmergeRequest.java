package com.clinicalharmony.patientindex.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of POST /api/pmi/merges/{mergeId}/unmerge. A reason is required: it lands in Gold's merge history. */
public record UnmergeRequest(
        @NotBlank @Size(max = 50) String reviewer,
        @NotBlank @Size(max = 2000) String reason
) {
}
