package com.clinicalharmony.ontology.service;

import com.clinicalharmony.ontology.repository.ValidationReportRecord;
import com.clinicalharmony.ontology.repository.ValidationReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Turns every failed item in an OntologyValidationResult into a silver.validation_reports row.
 * Passing validations are not written — only findings need a permanent audit trail.
 */
@Service
public class ValidationReportBuilder {

    private static final Logger log = LoggerFactory.getLogger(ValidationReportBuilder.class);

    private final ValidationReportRepository repository;

    public ValidationReportBuilder(ValidationReportRepository repository) {
        this.repository = repository;
    }

    public List<Long> buildAndPersist(OntologyValidationResult result) {
        List<Long> reportIds = new ArrayList<>();
        Stream.of(result.conditionResults(), result.observationResults(), result.medicationResults())
                .flatMap(List::stream)
                .filter(item -> !item.outcome().valid())
                .forEach(item -> {
                    ValidationReportRecord record = ValidationReportRecord.forFailedValidation(item, result.patientId());
                    long id = repository.insert(record);
                    reportIds.add(id);
                    log.info("Recorded validation_report id={} rule={} entityType={} entityId={}",
                            id, item.ruleCode(), item.entityType(), item.entityId());
                });
        return reportIds;
    }
}
