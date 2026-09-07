package com.clinicalharmony.rulesengine.web;

import com.clinicalharmony.rulesengine.service.ClinicalRule;
import com.clinicalharmony.rulesengine.service.ClinicalValidationReport;
import com.clinicalharmony.rulesengine.service.RuleLoader;
import com.clinicalharmony.rulesengine.service.RulesOrchestrationService;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
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

@RestController
@RequestMapping("/api/rules")
public class RulesController {

    private final RulesOrchestrationService rulesOrchestrationService;
    private final RuleLoader ruleLoader;

    public RulesController(RulesOrchestrationService rulesOrchestrationService, RuleLoader ruleLoader) {
        this.rulesOrchestrationService = rulesOrchestrationService;
        this.ruleLoader = ruleLoader;
    }

    @PostMapping(value = "/evaluate", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClinicalValidationReport> evaluate(@Valid @RequestBody PatientRecordRequest request) {
        return ResponseEntity.ok(rulesOrchestrationService.evaluate(request));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<ClinicalRule>> listActiveRules() {
        return ResponseEntity.ok(ruleLoader.loadActiveRules());
    }

    @GetMapping(value = "/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClinicalRule> getRule(@PathVariable String code) {
        return ruleLoader.loadByCode(code)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/{code}/toggle", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClinicalRule> toggleRule(@PathVariable String code) {
        return ruleLoader.toggle(code)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
