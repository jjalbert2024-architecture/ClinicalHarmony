package com.clinicalharmony.ingestion.repository;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class RawMessageRepository {

    private static final String INSERT_SQL = """
            INSERT INTO bronze.raw_messages
                (source_system, message_type, message_format, raw_payload, payload_checksum,
                 processing_status, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;

    private final JdbcTemplate jdbcTemplate;

    public RawMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(RawMessageRecord record) {
        List<Long> ids = jdbcTemplate.query(
                INSERT_SQL,
                (rs, rowNum) -> rs.getLong("id"),
                record.sourceSystem(),
                record.messageType(),
                record.messageFormat(),
                record.rawPayload(),
                record.payloadChecksum(),
                record.processingStatus(),
                record.errorMessage()
        );
        return DataAccessUtils.requiredSingleResult(ids);
    }
}
