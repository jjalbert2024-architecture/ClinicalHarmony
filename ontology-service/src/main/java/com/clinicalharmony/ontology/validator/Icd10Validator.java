package com.clinicalharmony.ontology.validator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Validates a diagnosis code against ontology.icd10_codes (implements rule CODE-001).
 */
@Component
public class Icd10Validator {

    private static final String LOOKUP_SQL =
            "SELECT description, category FROM ontology.icd10_codes WHERE code = ?";

    private final JdbcTemplate jdbcTemplate;

    public Icd10Validator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ValidationOutcome validate(String code) {
        if (code == null || code.isBlank()) {
            return ValidationOutcome.invalid(code, "Code is null or blank");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(LOOKUP_SQL, code);
        if (rows.isEmpty()) {
            return ValidationOutcome.invalid(code, "Code not found in ICD-10 reference set");
        }
        Map<String, Object> row = rows.get(0);
        return ValidationOutcome.valid(code, (String) row.get("description"), (String) row.get("category"));
    }
}
