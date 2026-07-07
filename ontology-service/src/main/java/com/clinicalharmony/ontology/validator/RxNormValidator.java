package com.clinicalharmony.ontology.validator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Validates a medication code against ontology.rxnorm_codes (implements rule CODE-003).
 */
@Component
public class RxNormValidator {

    private static final String LOOKUP_SQL =
            "SELECT name, term_type FROM ontology.rxnorm_codes WHERE rxcui = ?";

    private final JdbcTemplate jdbcTemplate;

    public RxNormValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ValidationOutcome validate(String code) {
        if (code == null || code.isBlank()) {
            return ValidationOutcome.invalid(code, "Code is null or blank");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(LOOKUP_SQL, code);
        if (rows.isEmpty()) {
            return ValidationOutcome.invalid(code, "Code not found in RxNorm reference set");
        }
        Map<String, Object> row = rows.get(0);
        return ValidationOutcome.valid(code, (String) row.get("name"), (String) row.get("term_type"));
    }
}
