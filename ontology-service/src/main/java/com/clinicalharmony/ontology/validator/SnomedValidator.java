package com.clinicalharmony.ontology.validator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Validates a clinical concept code against ontology.snomed_codes (implements rule CODE-004).
 */
@Component
public class SnomedValidator {

    private static final String LOOKUP_SQL =
            "SELECT term, semantic_tag FROM ontology.snomed_codes WHERE concept_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public SnomedValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ValidationOutcome validate(String code) {
        if (code == null || code.isBlank()) {
            return ValidationOutcome.invalid(code, "Code is null or blank");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(LOOKUP_SQL, code);
        if (rows.isEmpty()) {
            return ValidationOutcome.invalid(code, "Code not found in SNOMED CT reference set");
        }
        Map<String, Object> row = rows.get(0);
        return ValidationOutcome.valid(code, (String) row.get("term"), (String) row.get("semantic_tag"));
    }
}
