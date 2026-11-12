package com.clinicalharmony.fhircompliance.repository;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * gold.data_lineage. One row per (Gold target row, transformation step) — upserted on the unique
 * index migration 18 added, so rebuilding refreshes the chain instead of appending duplicates.
 */
@Repository
public class DataLineageRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO gold.data_lineage
                (source_schema, source_table, source_id, target_schema, target_table, target_id,
                 transformation_step, transformation_details, transformed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
            ON CONFLICT (target_schema, target_table, target_id, transformation_step)
            DO UPDATE SET source_schema = EXCLUDED.source_schema,
                          source_table = EXCLUDED.source_table,
                          source_id = EXCLUDED.source_id,
                          transformation_details = EXCLUDED.transformation_details,
                          transformed_at = now()
            RETURNING id
            """;

    private final JdbcTemplate jdbcTemplate;

    public DataLineageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long upsert(DataLineageRecord record) {
        return DataAccessUtils.requiredSingleResult(jdbcTemplate.query(UPSERT_SQL, (rs, n) -> rs.getLong(1),
                record.sourceSchema(), record.sourceTable(), record.sourceId(),
                record.targetSchema(), record.targetTable(), record.targetId(),
                record.transformationStep(), record.transformationDetails()));
    }
}
