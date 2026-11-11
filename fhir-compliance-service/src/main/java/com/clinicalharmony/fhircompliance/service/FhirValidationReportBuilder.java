package com.clinicalharmony.fhircompliance.service;

import com.clinicalharmony.fhircompliance.repository.ValidationReportRecord;
import com.clinicalharmony.fhircompliance.repository.ValidationReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns every failed ResourceValidationResult into a silver.validation_reports row. Passing
 * results are not written — only findings need a permanent audit trail (same convention as
 * ontology-service's ValidationReportBuilder / rules-engine-service's RuleFindingBuilder).
 */
@Service
public class FhirValidationReportBuilder {

    private static final Logger log = LoggerFactory.getLogger(FhirValidationReportBuilder.class);

    private final ValidationReportRepository repository;

    public FhirValidationReportBuilder(ValidationReportRepository repository) {
        this.repository = repository;
    }

    public List<Long> buildAndPersist(List<ResourceValidationResult> results, Long patientId) {
        List<Long> reportIds = new ArrayList<>();
        for (ResourceValidationResult result : results) {
            if (result.valid()) {
                continue;
            }
            ValidationReportRecord record = new ValidationReportRecord(
                    result.resourceType().toUpperCase(),
                    patientId,
                    result.ruleCode(),
                    result.severity(),
                    result.reason(),
                    patientId,
                    String.join(",", result.missingFields()),
                    "Populate the missing required field(s) before FHIR export"
            );
            long id = repository.insert(record);
            reportIds.add(id);
            log.info("Recorded validation_report id={} rule={} resourceType={} resourceId={}",
                    id, result.ruleCode(), result.resourceType(), result.resourceId());
        }
        return reportIds;
    }
}
