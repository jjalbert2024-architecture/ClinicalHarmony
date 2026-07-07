package com.clinicalharmony.ontology.web;

import com.clinicalharmony.ontology.service.ConceptMapping;
import com.clinicalharmony.ontology.service.ConceptMappingService;
import com.clinicalharmony.ontology.service.OntologyService;
import com.clinicalharmony.ontology.service.OntologyValidationResult;
import com.clinicalharmony.ontology.service.ValidationReportBuilder;
import com.clinicalharmony.ontology.validator.Icd10Validator;
import com.clinicalharmony.ontology.validator.LoincValidator;
import com.clinicalharmony.ontology.validator.ValidationOutcome;
import com.clinicalharmony.ontology.web.dto.OntologyValidationResponse;
import com.clinicalharmony.ontology.web.dto.PatientRecordRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ontology")
public class OntologyController {

    private final OntologyService ontologyService;
    private final ValidationReportBuilder validationReportBuilder;
    private final Icd10Validator icd10Validator;
    private final LoincValidator loincValidator;
    private final ConceptMappingService conceptMappingService;

    public OntologyController(OntologyService ontologyService,
                               ValidationReportBuilder validationReportBuilder,
                               Icd10Validator icd10Validator,
                               LoincValidator loincValidator,
                               ConceptMappingService conceptMappingService) {
        this.ontologyService = ontologyService;
        this.validationReportBuilder = validationReportBuilder;
        this.icd10Validator = icd10Validator;
        this.loincValidator = loincValidator;
        this.conceptMappingService = conceptMappingService;
    }

    @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OntologyValidationResponse> validate(@Valid @RequestBody PatientRecordRequest request) {
        OntologyValidationResult result = ontologyService.validate(request);
        List<Long> reportIds = validationReportBuilder.buildAndPersist(result);
        return ResponseEntity.ok(OntologyValidationResponse.from(result, reportIds));
    }

    @GetMapping(value = "/codes/icd10/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ValidationOutcome> lookupIcd10(@PathVariable String code) {
        ValidationOutcome outcome = icd10Validator.validate(code);
        return outcome.valid() ? ResponseEntity.ok(outcome) : ResponseEntity.status(HttpStatus.NOT_FOUND).body(outcome);
    }

    @GetMapping(value = "/codes/loinc/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ValidationOutcome> lookupLoinc(@PathVariable String code) {
        ValidationOutcome outcome = loincValidator.validate(code);
        return outcome.valid() ? ResponseEntity.ok(outcome) : ResponseEntity.status(HttpStatus.NOT_FOUND).body(outcome);
    }

    @GetMapping(value = "/map", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ConceptMapping>> map(
            @RequestParam String source,
            @RequestParam String code,
            @RequestParam String target) {
        return ResponseEntity.ok(conceptMappingService.map(source, code, target));
    }
}
