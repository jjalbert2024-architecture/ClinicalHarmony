package com.clinicalharmony.fhircompliance.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Reads/toggles ontology.clinical_rules rows scoped to this service's USCORE_* rule codes
 * (in practice this queries the whole table, same as ontology-service/rules-engine-service do —
 * each service just ignores rule codes it doesn't own).
 */
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

    public List<ClinicalRuleRecord> findAllActiveWithPrefix(String rulePrefix) {
        return jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE is_active = TRUE AND rule_code LIKE ? ORDER BY rule_code",
                ROW_MAPPER, rulePrefix + "%");
    }

    public List<ClinicalRuleRecord> findAllWithPrefix(String rulePrefix) {
        return jdbcTemplate.query(
                SELECT_COLUMNS + " WHERE rule_code LIKE ? ORDER BY rule_code",
                ROW_MAPPER, rulePrefix + "%");
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
