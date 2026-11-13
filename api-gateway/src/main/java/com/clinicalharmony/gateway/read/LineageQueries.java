package com.clinicalharmony.gateway.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Data Lineage view: GoldWriter's {@code gold.data_lineage} rows (each with its
 * BRONZE→SILVER→GOLD step chain in {@code transformation_details}) for one Gold FHIR resource or
 * for everything about one master patient, plus the Bronze messages behind them.
 */
@Repository
public class LineageQueries {

    public record LineageRecord(long id, String step, String sourceSchema, String sourceTable, Long sourceId,
                                String targetSchema, String targetTable, long targetId, OffsetDateTime transformedAt,
                                JsonNode details) {
    }

    public record BronzeSummary(long id, String sourceSystem, String messageType, String messageFormat,
                                String processingStatus, String payloadChecksum, int payloadLength,
                                String errorMessage, OffsetDateTime receivedAt, OffsetDateTime processedAt) {
    }

    public record LineageGraph(String target, long targetId, List<LineageRecord> records,
                               List<BronzeSummary> bronzeMessages) {
    }

    public record DownstreamRow(String table, long id) {
    }

    public record BronzeDetail(BronzeSummary summary, String rawPayload, List<DownstreamRow> silverRows) {
    }

    public record StepCount(String step, String sourceSchema, String targetTable, long count,
                            OffsetDateTime lastTransformedAt) {
    }

    /** Silver tables that carry raw_message_id (constants — safe to interpolate). */
    static final List<String> SILVER_TABLES = List.of("patients", "conditions", "observations", "medications");

    private static final String RECORD_COLUMNS = """
            l.id, l.transformation_step, l.source_schema, l.source_table, l.source_id, l.target_schema,
            l.target_table, l.target_id, l.transformed_at, l.transformation_details
            """;

    private static final String BRONZE_SUMMARY_COLUMNS = """
            b.id, b.source_system, b.message_type, b.message_format, b.processing_status, b.payload_checksum,
            length(b.raw_payload) AS payload_length, left(b.error_message, 300) AS error_message, b.received_at,
            b.processed_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LineageQueries(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public LineageGraph forFhirResource(long fhirResourceId) {
        MapSqlParameterSource id = new MapSqlParameterSource("id", fhirResourceId);
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM gold.fhir_resources WHERE id = :id", id, Integer.class);
        if (exists == null || exists == 0) {
            throw new NotFoundException("No Gold FHIR resource with id " + fhirResourceId);
        }
        List<LineageRecord> records = jdbc.query("SELECT " + RECORD_COLUMNS + """
                 FROM gold.data_lineage l
                WHERE l.target_schema = 'gold' AND l.target_table = 'fhir_resources' AND l.target_id = :id
                ORDER BY l.transformed_at, l.id
                """, id, (rs, i) -> record(rs));
        Set<Long> bronzeIds = new TreeSet<>();
        for (LineageRecord r : records) {
            if ("silver".equals(r.sourceSchema()) && SILVER_TABLES.contains(r.sourceTable()) && r.sourceId() != null) {
                bronzeIds.addAll(jdbc.queryForList("SELECT raw_message_id FROM silver." + r.sourceTable()
                        + " WHERE id = :id AND raw_message_id IS NOT NULL", new MapSqlParameterSource("id", r.sourceId()),
                        Long.class));
            }
        }
        return new LineageGraph("gold.fhir_resources", fhirResourceId, records, bronze(bronzeIds));
    }

    public LineageGraph forMasterPatient(long masterId) {
        MapSqlParameterSource id = new MapSqlParameterSource("id", masterId);
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM gold.master_patients WHERE id = :id", id, Integer.class);
        if (exists == null || exists == 0) {
            throw new NotFoundException("No master patient " + masterId);
        }
        List<LineageRecord> records = jdbc.query("SELECT " + RECORD_COLUMNS + """
                 FROM gold.data_lineage l
                WHERE l.target_schema = 'gold' AND (
                      (l.target_table = 'master_patients' AND l.target_id = :id)
                   OR (l.target_table = 'fhir_resources' AND l.target_id IN
                          (SELECT f.id FROM gold.fhir_resources f WHERE f.master_patient_id = :id))
                   OR (l.target_table = 'master_patient_merges' AND l.target_id IN
                          (SELECT g.id FROM gold.master_patient_merges g
                            WHERE g.survivor_master_id = :id OR g.absorbed_master_id = :id)))
                ORDER BY l.transformed_at, l.id
                """, id, (rs, i) -> record(rs));
        Set<Long> bronzeIds = new TreeSet<>();
        String linked = "(SELECT silver_patient_id FROM gold.patient_source_xref WHERE master_patient_id = :id)";
        bronzeIds.addAll(jdbc.queryForList("SELECT raw_message_id FROM silver.patients WHERE id IN " + linked
                + " AND raw_message_id IS NOT NULL", id, Long.class));
        for (String table : SILVER_TABLES.subList(1, SILVER_TABLES.size())) {
            bronzeIds.addAll(jdbc.queryForList("SELECT raw_message_id FROM silver." + table + " WHERE patient_id IN "
                    + linked + " AND raw_message_id IS NOT NULL", id, Long.class));
        }
        return new LineageGraph("gold.master_patients", masterId, records, bronze(bronzeIds));
    }

    public List<StepCount> summary() {
        return jdbc.query("""
                SELECT transformation_step, source_schema, target_table, count(*) AS n, max(transformed_at) AS last
                FROM gold.data_lineage GROUP BY 1, 2, 3 ORDER BY 1, 2, 3
                """, new MapSqlParameterSource(), (rs, i) -> new StepCount(rs.getString("transformation_step"),
                rs.getString("source_schema"), rs.getString("target_table"), rs.getLong("n"), Sql.ts(rs, "last")));
    }

    public Page<BronzeSummary> bronzeMessages(String status, String messageType, String sourceSystem, int page, int size) {
        Sql.Where where = new Sql.Where()
                .and(Sql.has(status), "b.processing_status = :status", "status", status)
                .and(Sql.has(messageType), "b.message_type = :type", "type", messageType)
                .and(Sql.has(sourceSystem), "b.source_system = :source", "source", sourceSystem);
        Long total = jdbc.queryForObject("SELECT count(*) FROM bronze.raw_messages b" + where.sql(), where.params, Long.class);
        where.params.addValue("limit", size).addValue("offset", (long) page * size);
        List<BronzeSummary> items = jdbc.query("SELECT " + BRONZE_SUMMARY_COLUMNS + " FROM bronze.raw_messages b"
                + where.sql() + " ORDER BY b.received_at DESC, b.id DESC LIMIT :limit OFFSET :offset", where.params,
                (rs, i) -> bronzeSummary(rs));
        return Page.of(items, page, size, total == null ? 0 : total);
    }

    public BronzeDetail bronzeMessage(long id) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        List<BronzeDetail> found = jdbc.query("SELECT " + BRONZE_SUMMARY_COLUMNS + ", b.raw_payload FROM bronze.raw_messages b"
                + " WHERE b.id = :id", params, (rs, i) -> new BronzeDetail(bronzeSummary(rs), rs.getString("raw_payload"),
                List.of()));
        if (found.isEmpty()) {
            throw new NotFoundException("No Bronze message " + id);
        }
        List<DownstreamRow> silver = new ArrayList<>();
        for (String table : SILVER_TABLES) {
            jdbc.queryForList("SELECT id FROM silver." + table + " WHERE raw_message_id = :id ORDER BY id", params, Long.class)
                    .forEach(rowId -> silver.add(new DownstreamRow("silver." + table, rowId)));
        }
        BronzeDetail detail = found.get(0);
        return new BronzeDetail(detail.summary(), detail.rawPayload(), silver);
    }

    private List<BronzeSummary> bronze(Set<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + BRONZE_SUMMARY_COLUMNS + " FROM bronze.raw_messages b WHERE b.id IN (:ids) ORDER BY b.id",
                new MapSqlParameterSource(Map.of("ids", ids)), (rs, i) -> bronzeSummary(rs));
    }

    private LineageRecord record(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LineageRecord(rs.getLong("id"), rs.getString("transformation_step"), rs.getString("source_schema"),
                rs.getString("source_table"), Sql.longOrNull(rs, "source_id"), rs.getString("target_schema"),
                rs.getString("target_table"), rs.getLong("target_id"), Sql.ts(rs, "transformed_at"),
                Sql.json(objectMapper, rs, "transformation_details"));
    }

    private static BronzeSummary bronzeSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BronzeSummary(rs.getLong("id"), rs.getString("source_system"), rs.getString("message_type"),
                rs.getString("message_format"), rs.getString("processing_status"), rs.getString("payload_checksum"),
                rs.getInt("payload_length"), rs.getString("error_message"), Sql.ts(rs, "received_at"),
                Sql.ts(rs, "processed_at"));
    }
}
