package com.clinicalharmony.patientindex.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Reads rule PMI_MATCH (migration 20) fresh on every call — no caching, a toggle applies at once. */
@Repository
public class MatchRuleRepository {

    public static final String RULE_CODE = "PMI_MATCH";

    public record MatchRule(boolean active, String expressionJson) {
    }

    private final JdbcTemplate jdbc;

    public MatchRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MatchRule> load() {
        return jdbc.query("SELECT is_active, rule_expression::text FROM ontology.clinical_rules WHERE rule_code = ?",
                (rs, n) -> new MatchRule(rs.getBoolean(1), rs.getString(2)), RULE_CODE).stream().findFirst();
    }
}
