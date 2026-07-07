package com.clinicalharmony.ontology.service;

import com.clinicalharmony.ontology.validator.Icd10Validator;
import com.clinicalharmony.ontology.validator.LoincValidator;
import com.clinicalharmony.ontology.validator.RxNormValidator;
import com.clinicalharmony.ontology.validator.SnomedValidator;
import com.clinicalharmony.ontology.validator.ValidationOutcome;
import com.clinicalharmony.ontology.web.dto.PatientRecordRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates the 4 code validators against a canonical patient record: conditions go to
 * ICD10Validator or SnomedValidator depending on their code system, observations to
 * LoincValidator, medications to RxNormValidator.
 */
@Service
public class OntologyService {

    private static final Logger log = LoggerFactory.getLogger(OntologyService.class);

    private final Icd10Validator icd10Validator;
    private final LoincValidator loincValidator;
    private final RxNormValidator rxNormValidator;
    private final SnomedValidator snomedValidator;

    public OntologyService(Icd10Validator icd10Validator,
                            LoincValidator loincValidator,
                            RxNormValidator rxNormValidator,
                            SnomedValidator snomedValidator) {
        this.icd10Validator = icd10Validator;
        this.loincValidator = loincValidator;
        this.rxNormValidator = rxNormValidator;
        this.snomedValidator = snomedValidator;
    }

    public OntologyValidationResult validate(PatientRecordRequest request) {
        List<ItemValidation> conditionResults = nullSafe(request.conditions()).stream()
                .map(this::validateCondition)
                .toList();
        List<ItemValidation> observationResults = nullSafe(request.observations()).stream()
                .map(o -> new ItemValidation(o.id(), "OBSERVATION", "LOINC", loincValidator.validate(o.code()), "CODE-002"))
                .toList();
        List<ItemValidation> medicationResults = nullSafe(request.medications()).stream()
                .map(m -> new ItemValidation(m.id(), "MEDICATION", "RxNorm", rxNormValidator.validate(m.rxnormCode()), "CODE-003"))
                .toList();

        OntologyValidationResult result =
                new OntologyValidationResult(request.patientId(), conditionResults, observationResults, medicationResults);
        log.info("Validated patientId={} conditions={} observations={} medications={} allValid={}",
                request.patientId(), conditionResults.size(), observationResults.size(), medicationResults.size(), result.allValid());
        return result;
    }

    private ItemValidation validateCondition(PatientRecordRequest.ConditionItem item) {
        if ("SNOMED-CT".equalsIgnoreCase(item.codeSystem())) {
            return new ItemValidation(item.id(), "CONDITION", "SNOMED-CT", snomedValidator.validate(item.code()), "CODE-004");
        }
        if ("ICD-10".equalsIgnoreCase(item.codeSystem())) {
            return new ItemValidation(item.id(), "CONDITION", "ICD-10", icd10Validator.validate(item.code()), "CODE-001");
        }
        ValidationOutcome outcome = ValidationOutcome.invalid(item.code(),
                "Unsupported code system '" + item.codeSystem() + "' — expected ICD-10 or SNOMED-CT");
        return new ItemValidation(item.id(), "CONDITION", item.codeSystem(), outcome, "CODE-001");
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
