package com.clinicalharmony.fhircompliance.service;

import com.clinicalharmony.fhircompliance.repository.ClinicalRuleRecord;
import com.clinicalharmony.fhircompliance.repository.ClinicalRuleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Loads this service's own rule_code family (USCORE_*_COMPLETENESS, one row per US Core
 * resource type) from ontology.clinical_rules and parses rule_expression into UsCoreProfile.
 * Queries fresh on every call — no caching — so a PUT .../toggle takes effect immediately, same
 * convention as rules-engine-service's RuleLoader.
 */
@Service
public class UsCoreRuleLoader {

    private static final Logger log = LoggerFactory.getLogger(UsCoreRuleLoader.class);
    private static final String RULE_PREFIX = "USCORE_";

    private final ClinicalRuleRepository repository;
    private final ObjectMapper objectMapper;

    public UsCoreRuleLoader(ClinicalRuleRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public List<ClinicalRule> loadActiveRules() {
        return repository.findAllActiveWithPrefix(RULE_PREFIX).stream().map(this::toDomain).toList();
    }

    public List<ClinicalRule> loadAllRules() {
        return repository.findAllWithPrefix(RULE_PREFIX).stream().map(this::toDomain).toList();
    }

    public Optional<ClinicalRule> loadByCode(String ruleCode) {
        return repository.findByCode(ruleCode).map(this::toDomain);
    }

    public Optional<ClinicalRule> toggle(String ruleCode) {
        return repository.toggleActive(ruleCode).map(this::toDomain);
    }

    /** Active USCORE_* rules parsed into the resourceType/requiredFields shape validation needs. */
    public List<UsCoreProfile> loadActiveProfiles() {
        return loadActiveRules().stream().map(this::toProfile).toList();
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

    private UsCoreProfile toProfile(ClinicalRule rule) {
        JsonNode expr = rule.expression();
        String resourceType = expr.path("resourceType").asText(null);
        List<String> requiredFields = new ArrayList<>();
        expr.path("requiredFields").forEach(node -> requiredFields.add(node.asText()));

        List<List<String>> requiredAnyOf = new ArrayList<>();
        expr.path("requiredAnyOf").forEach(group -> {
            List<String> options = new ArrayList<>();
            group.forEach(option -> options.add(option.asText()));
            requiredAnyOf.add(options);
        });

        return new UsCoreProfile(rule.ruleCode(), resourceType, rule.severity(), requiredFields, requiredAnyOf);
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
