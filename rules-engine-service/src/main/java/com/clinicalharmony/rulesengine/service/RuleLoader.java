package com.clinicalharmony.rulesengine.service;

import com.clinicalharmony.rulesengine.repository.ClinicalRuleRecord;
import com.clinicalharmony.rulesengine.repository.ClinicalRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Loads ontology.clinical_rules rows and converts them into ClinicalRule domain objects,
 * parsing rule_expression JSON. Queries the database fresh on every call (no caching) so a
 * PUT /api/rules/{code}/toggle takes effect on the very next evaluation.
 */
@Service
public class RuleLoader {

    private static final Logger log = LoggerFactory.getLogger(RuleLoader.class);

    private final ClinicalRuleRepository repository;
    private final ObjectMapper objectMapper;

    public RuleLoader(ClinicalRuleRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public List<ClinicalRule> loadActiveRules() {
        return repository.findAllActive().stream().map(this::toDomain).toList();
    }

    public List<ClinicalRule> loadAllRules() {
        return repository.findAll().stream().map(this::toDomain).toList();
    }

    public Optional<ClinicalRule> loadByCode(String ruleCode) {
        return repository.findByCode(ruleCode).map(this::toDomain);
    }

    /** Flips is_active for the given rule code and returns the updated domain object. */
    public Optional<ClinicalRule> toggle(String ruleCode) {
        return repository.toggleActive(ruleCode).map(this::toDomain);
    }

    private ClinicalRule toDomain(ClinicalRuleRecord record) {
        return new ClinicalRule(
                record.ruleCode(),
                record.ruleName(),
                record.description(),
                record.ruleCategory(),
                record.defaultSeverity(),
                record.active(),
                parseExpression(record.ruleCode(), record.ruleExpression()));
    }

    private JsonNode parseExpression(String ruleCode, String json) {
        if (json == null || json.isBlank()) {
            return NullNode.getInstance();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse rule_expression for rule {}, treating as empty: {}", ruleCode, e.getMessage());
            return NullNode.getInstance();
        }
    }
}
