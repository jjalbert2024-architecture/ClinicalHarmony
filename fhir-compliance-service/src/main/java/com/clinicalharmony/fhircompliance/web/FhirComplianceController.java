package com.clinicalharmony.fhircompliance.web;

import com.clinicalharmony.fhircompliance.service.ClinicalRule;
import com.clinicalharmony.fhircompliance.service.FhirGenerationService;
import com.clinicalharmony.fhircompliance.service.FhirValidationReport;
import com.clinicalharmony.fhircompliance.service.UsCoreRuleLoader;
import com.clinicalharmony.fhircompliance.service.UsCoreValidationService;
import com.clinicalharmony.fhircompliance.web.dto.FhirValidationRequest;
import com.clinicalharmony.fhircompliance.web.dto.PatientRecordRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fhir-compliance")
public class FhirComplianceController {

    private final UsCoreValidationService validationService;
    private final FhirGenerationService generationService;
    private final UsCoreRuleLoader ruleLoader;

    public FhirComplianceController(UsCoreValidationService validationService, FhirGenerationService generationService,
                                     UsCoreRuleLoader ruleLoader) {
        this.validationService = validationService;
        this.generationService = generationService;
        this.ruleLoader = ruleLoader;
    }

    @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FhirValidationReport> validate(@Valid @RequestBody FhirValidationRequest request) {
        return ResponseEntity.ok(validationService.validate(request));
    }

    @PostMapping(value = "/generate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> generate(@Valid @RequestBody PatientRecordRequest request) {
        return ResponseEntity.ok(generationService.generateAndPersist(request));
    }

    @GetMapping(value = "/rules", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ClinicalRule>> listActiveRules() {
        return ResponseEntity.ok(ruleLoader.loadActiveRules());
    }

    @GetMapping(value = "/rules/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClinicalRule> getRule(@PathVariable String code) {
        return ruleLoader.loadByCode(code)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/rules/{code}/toggle", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClinicalRule> toggleRule(@PathVariable String code) {
        return ruleLoader.toggle(code)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
