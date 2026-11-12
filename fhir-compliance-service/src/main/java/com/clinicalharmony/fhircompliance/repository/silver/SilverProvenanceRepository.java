package com.clinicalharmony.fhircompliance.repository.silver;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

/** Reads a Silver row's provenance (and its Bronze source, if linked) for lineage. */
@Repository
public class SilverProvenanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public SilverProvenanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<SilverProvenance> find(SilverTable table, long id) {
        // Table/column names come from the SilverTable enum, never from input.
        String sql = """
                SELECT s.created_at, p.source_system, s.raw_message_id,
                       b.payload_checksum, b.received_at, b.message_type
                FROM silver.%s s
                JOIN silver.patients p ON p.id = s.%s
                LEFT JOIN bronze.raw_messages b ON b.id = s.raw_message_id
                WHERE s.id = ?
                """.formatted(table.tableName(), table.patientIdColumn());
        return jdbcTemplate.query(sql, (rs, n) -> new SilverProvenance(
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("source_system"),
                        rs.getObject("raw_message_id", Long.class),
                        rs.getString("payload_checksum"),
                        rs.getObject("received_at", OffsetDateTime.class),
                        rs.getString("message_type")),
                id).stream().findFirst();
    }
}
