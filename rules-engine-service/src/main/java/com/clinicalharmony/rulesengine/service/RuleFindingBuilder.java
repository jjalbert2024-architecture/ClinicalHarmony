package com.clinicalharmony.rulesengine.service;

import com.clinicalharmony.rulesengine.repository.ValidationReportRecord;
import com.clinicalharmony.rulesengine.repository.ValidationReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns every failed RuleResult into a silver.validation_reports row. Passing results are not
 * written — only findings need a permanent audit trail (same convention as ontology-service's
 * ValidationReportBuilder).
 */
@Service
public class RuleFindingBuilder {

    private static final Logger log = LoggerFactory.getLogger(RuleFindingBuilder.class);

    private final ValidationReportRepository repository;

    public RuleFindingBuilder(ValidationReportRepository repository) {
        this.repository = repository;
    }

    public List<Long> buildAndPersist(List<RuleResult> results) {
        List<Long> reportIds = new ArrayList<>();
        results.stream()
                .filter(result -> !result.passed())
                .forEach(result -> {
                    ValidationReportRecord record = ValidationReportRecord.forFailedRule(result);
                    long id = repository.insert(record);
                    reportIds.add(id);
                    log.info("Recorded validation_report id={} rule={} entityType={} entityId={}",
                            id, result.ruleCode(), result.entityType(), result.entityId());
                });
        return reportIds;
    }
}
