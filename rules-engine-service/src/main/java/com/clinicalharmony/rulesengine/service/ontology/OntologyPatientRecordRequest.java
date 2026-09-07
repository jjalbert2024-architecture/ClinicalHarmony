package com.clinicalharmony.rulesengine.service.ontology;

import java.util.List;

/** Mirrors ontology-service's PatientRecordRequest — the subset of fields it validates. */
public record OntologyPatientRecordRequest(
        Long patientId,
        List<ConditionItem> conditions,
        List<ObservationItem> observations,
        List<MedicationItem> medications
) {
    public record ConditionItem(Long id, String codeSystem, String code) {
    }

    public record ObservationItem(Long id, String code) {
    }

    public record MedicationItem(Long id, String rxnormCode) {
    }
}
