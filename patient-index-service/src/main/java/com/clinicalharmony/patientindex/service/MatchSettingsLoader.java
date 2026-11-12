package com.clinicalharmony.patientindex.service;

import com.clinicalharmony.patientindex.matching.MatchSettings;
import com.clinicalharmony.patientindex.repository.MatchRuleRepository;
import com.clinicalharmony.patientindex.repository.MatchRuleRepository.MatchRule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Loads PMI_MATCH fresh each time (no caching — edits and toggles apply on the next call). */
@Component
public class MatchSettingsLoader {

    private final MatchRuleRepository rules;
    private final ObjectMapper objectMapper;

    public MatchSettingsLoader(MatchRuleRepository rules, ObjectMapper objectMapper) {
        this.rules = rules;
        this.objectMapper = objectMapper;
    }

    /** Settings for an ad-hoc score: used whether or not the rule is active. */
    public MatchSettings load() {
        return parse(rules.load().orElseThrow(() -> new ConflictException(
                "Rule " + MatchRuleRepository.RULE_CODE + " is missing from ontology.clinical_rules (apply docs/db-init/20)")));
    }

    /** Settings for a matching run, which needs the rule to be active. */
    public MatchSettings loadActive() {
        MatchRule rule = rules.load().orElseThrow(() -> new ConflictException(
                "Rule " + MatchRuleRepository.RULE_CODE + " is missing from ontology.clinical_rules (apply docs/db-init/20)"));
        if (!rule.active()) {
            throw new ConflictException("Rule " + MatchRuleRepository.RULE_CODE + " is inactive; matching runs are disabled");
        }
        return parse(rule);
    }

    public String toJson(MatchSettings settings) {
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private MatchSettings parse(MatchRule rule) {
        try {
            return MatchSettings.fromJson(rule.expressionJson() == null ? null : objectMapper.readTree(rule.expressionJson()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rule " + MatchRuleRepository.RULE_CODE + " has an unparseable rule_expression", e);
        }
    }
}
