package com.clinicalharmony.patientindex.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /api/pmi/merge: absorb one master into another, by hand. */
public record ManualMergeRequest(
        @NotNull Long survivorMasterId,
        @NotNull Long absorbedMasterId,
        @NotBlank @Size(max = 50) String reviewer,
        @Size(max = 2000) String note
) {
}
