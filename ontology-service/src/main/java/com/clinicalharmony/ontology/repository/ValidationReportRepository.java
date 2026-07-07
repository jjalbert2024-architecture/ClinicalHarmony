package com.clinicalharmony.ontology.repository;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ValidationReportRepository {

    private static final String INSERT_SQL = """
            INSERT INTO silver.validation_reports
                (entity_type, entity_id, rule_code, severity, message, patient_id, bad_value, suggested_value)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private final JdbcTemplate jdbcTemplate;

    public ValidationReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(ValidationReportRecord record) {
        List<Long> ids = jdbcTemplate.query(
                INSERT_SQL,
                (rs, rowNum) -> rs.getLong("id"),
                record.entityType(),
                record.entityId(),
                record.ruleCode(),
                record.severity(),
                record.message(),
                record.patientId(),
                record.badValue(),
                record.suggestedValue()
        );
        return DataAccessUtils.requiredSingleResult(ids);
    }
}
