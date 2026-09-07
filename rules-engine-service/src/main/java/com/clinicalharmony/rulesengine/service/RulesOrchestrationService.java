package com.clinicalharmony.rulesengine.service;

import com.clinicalharmony.rulesengine.service.ontology.OntologyPatientRecordRequest;
import com.clinicalharmony.rulesengine.service.ontology.OntologyServiceClient;
import com.clinicalharmony.rulesengine.web.dto.PatientRecordRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Accepts a Silver patient record, runs ontology validation (delegated to ontology-service over
 * HTTP) then rule evaluation, and aggregates both into a single ClinicalValidationReport. If
 * ontology-service is unreachable, validation degrades gracefully to rules-only rather than
 * failing the whole request — {@code ontologyValidationAvailable=false} on the response signals
 * the gap to the caller.
 */
@Service
public class RulesOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RulesOrchestrationService.class);

    private final OntologyServiceClient ontologyServiceClient;
    private final RuleExecutor ruleExecutor;
    private final RuleFindingBuilder ruleFindingBuilder;

    public RulesOrchestrationService(OntologyServiceClient ontologyServiceClient,
                                      RuleExecutor ruleExecutor,
                                      RuleFindingBuilder ruleFindingBuilder) {
        this.ontologyServiceClient = ontologyServiceClient;
        this.ruleExecutor = ruleExecutor;
        this.ruleFindingBuilder = ruleFindingBuilder;
    }

    public ClinicalValidationReport evaluate(PatientRecordRequest request) {
        JsonNode ontologyValidation = null;
        boolean ontologyAvailable = true;
        try {
            ontologyValidation = ontologyServiceClient.validate(toOntologyRequest(request));
        } catch (RestClientException e) {
            log.warn("ontology-service validation unavailable for patientId={}, continuing with rules only: {}",
                    request.patientId(), e.getMessage());
            ontologyAvailable = false;
        }

        List<RuleResult> ruleResults = ruleExecutor.evaluate(request);
        List<Long> reportIds = ruleFindingBuilder.buildAndPersist(ruleResults);

        boolean ontologyAllValid = ontologyValidation == null || ontologyValidation.path("allValid").asBoolean(true);
        boolean rulesAllPassed = ruleResults.stream().allMatch(RuleResult::passed);

        ClinicalValidationReport report = new ClinicalValidationReport(
                request.patientId(),
                ontologyAllValid && rulesAllPassed,
                ontologyAvailable,
                ontologyValidation,
                ruleResults,
                reportIds);
        log.info("Orchestrated validation for patientId={} allValid={} ontologyAvailable={} ruleFindings={}",
                request.patientId(), report.allValid(), ontologyAvailable, reportIds.size());
        return report;
    }

    private static OntologyPatientRecordRequest toOntologyRequest(PatientRecordRequest request) {
        List<OntologyPatientRecordRequest.ConditionItem> conditions = nullSafe(request.conditions()).stream()
                .map(c -> new OntologyPatientRecordRequest.ConditionItem(c.id(), c.codeSystem(), c.code()))
                .toList();
        List<OntologyPatientRecordRequest.ObservationItem> observations = nullSafe(request.observations()).stream()
                .map(o -> new OntologyPatientRecordRequest.ObservationItem(o.id(), o.code()))
                .toList();
        List<OntologyPatientRecordRequest.MedicationItem> medications = nullSafe(request.medications()).stream()
                .map(m -> new OntologyPatientRecordRequest.MedicationItem(m.id(), m.rxnormCode()))
                .toList();
        return new OntologyPatientRecordRequest(request.patientId(), conditions, observations, medications);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
