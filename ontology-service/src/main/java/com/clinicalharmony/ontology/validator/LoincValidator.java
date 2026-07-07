package com.clinicalharmony.ontology.validator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Validates a lab observation code against ontology.loinc_codes (implements rule CODE-002).
 */
@Component
public class LoincValidator {

    private static final String LOOKUP_SQL =
            "SELECT long_common_name, scale_type FROM ontology.loinc_codes WHERE loinc_num = ?";

    private final JdbcTemplate jdbcTemplate;

    public LoincValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ValidationOutcome validate(String code) {
        if (code == null || code.isBlank()) {
            return ValidationOutcome.invalid(code, "Code is null or blank");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(LOOKUP_SQL, code);
        if (rows.isEmpty()) {
            return ValidationOutcome.invalid(code, "Code not found in LOINC reference set");
        }
        Map<String, Object> row = rows.get(0);
        return ValidationOutcome.valid(code, (String) row.get("long_common_name"), (String) row.get("scale_type"));
    }
}
