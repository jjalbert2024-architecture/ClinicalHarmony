package com.clinicalharmony.rulesengine.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ClinicalRuleRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, rule_code, rule_name, description, rule_category, default_severity,
                   is_active, rule_expression::text AS rule_expression
            FROM ontology.clinical_rules
            """;

    private static final RowMapper<ClinicalRuleRecord> ROW_MAPPER = (rs, rowNum) -> new ClinicalRuleRecord(
            rs.getLong("id"),
            rs.getString("rule_code"),
            rs.getString("rule_name"),
            rs.getString("description"),
            rs.getString("rule_category"),
            rs.getString("default_severity"),
            rs.getBoolean("is_active"),
            rs.getString("rule_expression")
    );

    private final JdbcTemplate jdbcTemplate;

    public ClinicalRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ClinicalRuleRecord> findAllActive() {
        return jdbcTemplate.query(SELECT_COLUMNS + " WHERE is_active = TRUE ORDER BY rule_code", ROW_MAPPER);
    }

    public List<ClinicalRuleRecord> findAll() {
        return jdbcTemplate.query(SELECT_COLUMNS + " ORDER BY rule_code", ROW_MAPPER);
    }

    public Optional<ClinicalRuleRecord> findByCode(String ruleCode) {
        List<ClinicalRuleRecord> rows = jdbcTemplate.query(SELECT_COLUMNS + " WHERE rule_code = ?", ROW_MAPPER, ruleCode);
        return rows.stream().findFirst();
    }

    /** Flips is_active and returns the updated row, or empty if the rule code doesn't exist. */
    public Optional<ClinicalRuleRecord> toggleActive(String ruleCode) {
        int rowsUpdated = jdbcTemplate.update(
                "UPDATE ontology.clinical_rules SET is_active = NOT is_active WHERE rule_code = ?",
                ruleCode);
        return rowsUpdated == 0 ? Optional.empty() : findByCode(ruleCode);
    }
}
