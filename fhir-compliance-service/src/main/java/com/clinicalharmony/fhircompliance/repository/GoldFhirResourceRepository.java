package com.clinicalharmony.fhircompliance.repository;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Persists generated FHIR resources to gold.fhir_resources — a table Phase 1 already created
 * for exactly this purpose but that no service has written to until this one. Upserts on
 * (resource_type, fhir_id) so regenerating a Bundle for the same patient updates the existing
 * row rather than violating the table's unique constraint.
 */
@Repository
public class GoldFhirResourceRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO gold.fhir_resources (resource_type, fhir_id, resource_json, is_us_core_compliant)
            VALUES (?, ?, ?::jsonb, ?)
            ON CONFLICT (resource_type, fhir_id)
            DO UPDATE SET resource_json = EXCLUDED.resource_json,
                          is_us_core_compliant = EXCLUDED.is_us_core_compliant,
                          updated_at = now()
            RETURNING id
            """;

    private final JdbcTemplate jdbcTemplate;

    public GoldFhirResourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long upsert(GoldFhirResourceRecord record) {
        List<Long> ids = jdbcTemplate.query(
                UPSERT_SQL,
                (rs, rowNum) -> rs.getLong("id"),
                record.resourceType(),
                record.fhirId(),
                record.resourceJson(),
                record.usCoreCompliant()
        );
        return DataAccessUtils.requiredSingleResult(ids);
    }
}
