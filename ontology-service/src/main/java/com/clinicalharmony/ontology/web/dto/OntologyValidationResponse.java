package com.clinicalharmony.ontology.web.dto;

import com.clinicalharmony.ontology.service.ItemValidation;
import com.clinicalharmony.ontology.service.OntologyValidationResult;

import java.util.List;

public record OntologyValidationResponse(
        Long patientId,
        boolean allValid,
        List<ItemValidation> conditionResults,
        List<ItemValidation> observationResults,
        List<ItemValidation> medicationResults,
        List<Long> validationReportIds
) {

    public static OntologyValidationResponse from(OntologyValidationResult result, List<Long> validationReportIds) {
        return new OntologyValidationResponse(
                result.patientId(),
                result.allValid(),
                result.conditionResults(),
                result.observationResults(),
                result.medicationResults(),
                validationReportIds);
    }
}
