package com.clinicalharmony.ontology.service;

import java.util.List;

public record OntologyValidationResult(
        Long patientId,
        List<ItemValidation> conditionResults,
        List<ItemValidation> observationResults,
        List<ItemValidation> medicationResults
) {

    public boolean allValid() {
        return conditionResults.stream().allMatch(v -> v.outcome().valid())
                && observationResults.stream().allMatch(v -> v.outcome().valid())
                && medicationResults.stream().allMatch(v -> v.outcome().valid());
    }
}
