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

    /**
     * @param patientName     PID-5 given + family name as the message carries it (HL7 v2 only; null when unreadable)
     * @param patientMrn      PID-3.1, the patient id in the sender's system
     * @param silverPatientId the Silver patient this message is about, keyed the way silver-transformer-service keys
     *                        patients: source system = PID-3.4 (assigning authority), else MSH-4, + PID-3.1. Null when
     *                        that patient isn't in Silver (quarantined / failed before any message of theirs mapped)
     */
    public record BronzeSummary(long id, String sourceSystem, String messageType, String messageFormat,
                                String processingStatus, String payloadChecksum, int payloadLength,
                                String errorMessage, OffsetDateTime receivedAt, OffsetDateTime processedAt,
                                String hl7MessageType, String patientName, String patientMrn, Long silverPatientId) {
    }

    public record LineageGraph(String target, long targetId, List<LineageRecord> records,
                               List<BronzeSummary> bronzeMessages) {
    }

    /** A Silver row written from a Bronze message, with the Silver patient it belongs to (for links). */
    public record DownstreamRow(String table, long id, long silverPatientId) {
    }

    /**
     * @param correlationId the id every service logged this payload under (Kafka intake only; null for legacy direct
     *                      REST rows) — the key to its trace in Splunk
     */
    public record BronzeDetail(BronzeSummary summary, String rawPayload, List<DownstreamRow> silverRows,
                               String intakeChannel, String correlationId) {
    }

    public record StepCount(String step, String sourceSchema, String targetTable, long count,
                            OffsetDateTime lastTransformedAt) {
    }

    /** Silver tables that carry raw_message_id (constants — safe to interpolate). */
    /** Every Silver table with a raw_message_id / patient_id; {@code claims} since 9d part 2 (never mapped to Gold). */
    static final List<String> SILVER_TABLES = List.of("patients", "conditions", "observations", "medications", "allergies",
            "claims");

    private static final String RECORD_COLUMNS = """
            l.id, l.transformation_step, l.source_schema, l.source_table, l.source_id, l.target_schema,
            l.target_table, l.target_id, l.transformed_at, l.transformation_details
            """;

    /**
     * MSH-9.1^MSH-9.2 (e.g. {@code ORU^R01}) read from the raw HL7 header — only when the payload starts with an MSH
     * segment and only from that first line (a truncated header never borrows fields from the next segment); NULL for
     * FHIR / claims rows and for HL7 without a usable MSH-9.
     */
    static final String HL7_TYPE_SQL = """
            (CASE WHEN b.message_type = 'HL7V2' THEN NULLIF(rtrim(substring(b.raw_payload
                  from '^MSH\\|(?:[^|\\r\\n]*\\|){7}([^|^\\r\\n]*\\^?[^|^\\r\\n]*)'), '^'), '') END)""";

    private static final String BRONZE_SUMMARY_COLUMNS = """
            b.id, b.source_system, b.message_type, b.message_format, b.processing_status, b.payload_checksum,
            length(b.raw_payload) AS payload_length, left(b.error_message, 300) AS error_message, b.received_at,
            b.processed_at,
            coalesce(pid.patient_name, NULLIF(btrim(own.first_name || ' ' || own.last_name), '')) AS patient_name,
            coalesce(pid.patient_mrn, own.source_patient_id) AS patient_mrn,
            coalesce(sp.id, own.id) AS silver_patient_id,""" + " " + HL7_TYPE_SQL
            + " AS hl7_message_type\n";

    /**
     * The PID segment's patient (first PID-3 repetition: id ^ … ^ assigning authority; PID-5 family ^ given), read
     * from any line of an HL7 v2 payload, and the Silver patient with that key. A FHIR / claims / C-CDA payload has no
     * PID: {@code own} = the Silver patient built from it (the patient row it wrote, else the owner of a claim,
     * condition or observation it wrote — a claims line never rewrites the member's demographics).
     */
    private static final String BRONZE_FROM = " FROM bronze.raw_messages b" + " " + """
            LEFT JOIN LATERAL (
                SELECT NULLIF(split_part(x.pid3, '^', 1), '') AS patient_mrn,
                       coalesce(NULLIF(split_part(x.pid3, '^', 4), ''), b.source_system) AS patient_source,
                       NULLIF(btrim(split_part(x.pid5, '^', 2) || ' ' || split_part(x.pid5, '^', 1)), '') AS patient_name
                FROM (SELECT split_part(substring(b.raw_payload from '(?:^|[\\r\\n])PID\\|(?:[^|\\r\\n]*\\|){2}([^|\\r\\n]*)'), '~', 1) AS pid3,
                             split_part(substring(b.raw_payload from '(?:^|[\\r\\n])PID\\|(?:[^|\\r\\n]*\\|){4}([^|\\r\\n]*)'), '~', 1) AS pid5
                      WHERE b.message_type = 'HL7V2') x) pid ON true
            LEFT JOIN silver.patients sp ON sp.source_system = pid.patient_source AND sp.source_patient_id = pid.patient_mrn
            LEFT JOIN LATERAL (
                SELECT o.id, o.first_name, o.last_name, o.source_patient_id FROM silver.patients o
                WHERE b.message_type <> 'HL7V2' AND o.id = coalesce(
                    (SELECT p.id FROM silver.patients p WHERE p.raw_message_id = b.id ORDER BY p.id LIMIT 1),
                    (SELECT c.patient_id FROM silver.claims c WHERE c.raw_message_id = b.id ORDER BY c.id LIMIT 1),
                    (SELECT c.patient_id FROM silver.conditions c WHERE c.raw_message_id = b.id ORDER BY c.id LIMIT 1),
                    (SELECT o2.patient_id FROM silver.observations o2 WHERE o2.raw_message_id = b.id ORDER BY o2.id LIMIT 1))
            ) own ON true""";

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

    /**
     * One row per (step, source schema, target table), in pipeline order rather than alphabetically: the
     * Silver → Gold mapping, then the PMI steps on the masters it creates, then the side entrances into Gold
     * (seed load, API generate). Steps not listed here (later phases) sort last, alphabetically.
     */
    public List<StepCount> summary() {
        return jdbc.query("""
                SELECT transformation_step, source_schema, target_table, count(*) AS n, max(transformed_at) AS last
                FROM gold.data_lineage GROUP BY 1, 2, 3
                ORDER BY CASE transformation_step
                             WHEN 'FHIR_MAP' THEN 1
                             WHEN 'PMI_PROVISIONAL' THEN 2
                             WHEN 'PMI_MATCH' THEN 3
                             WHEN 'PMI_MERGE' THEN 4
                             WHEN 'PMI_UNMERGE' THEN 5
                             WHEN 'SEED_LOAD' THEN 6
                             WHEN 'API_GENERATE' THEN 7
                             ELSE 99 END, 1, 2, 3
                """, new MapSqlParameterSource(), (rs, i) -> new StepCount(rs.getString("transformation_step"),
                rs.getString("source_schema"), rs.getString("target_table"), rs.getLong("n"), Sql.ts(rs, "last")));
    }

    /**
     * {@code hl7Type} = the message code (MSH-9.1, e.g. ORU); it only ever matches HL7 v2 rows. {@code q} = part of the
     * patient's name (as the message carries it, or the Silver patient's), the exact MRN / PID-3.1 (any case), or the
     * message id itself.
     */
    public Page<BronzeSummary> bronzeMessages(String status, String messageType, String hl7Type, String sourceSystem,
                                              String q, ListWindow window, int page, int size) {
        String text = Sql.has(q) ? q.trim() : null;
        Sql.Where where = new Sql.Where()
                .and(text != null, """
                        (pid.patient_name ILIKE :qLike OR (sp.first_name || ' ' || sp.last_name) ILIKE :qLike
                         OR (own.first_name || ' ' || own.last_name) ILIKE :qLike
                         OR upper(pid.patient_mrn) = upper(:q) OR upper(own.source_patient_id) = upper(:q) OR b.id = :qId)""", "q", text)
                .and(Sql.has(status), "b.processing_status = :status", "status", status)
                .and(Sql.has(messageType), "b.message_type = :type", "type", messageType)
                .and(Sql.has(hl7Type), "split_part(" + HL7_TYPE_SQL + ", '^', 1) = :hl7Type", "hl7Type", hl7Type)
                .and(Sql.has(sourceSystem), "b.source_system = :source", "source", sourceSystem);
        window.restrict(where, "b.id");
        if (text != null) {
            where.params.addValue("qLike", Sql.contains(text))
                    .addValue("qId", text.matches("#?\\d{1,18}") ? Long.parseLong(text.replace("#", "")) : -1L);
        }
        Sql.Counted counted = Sql.count(jdbc, "b.id", BRONZE_FROM + where.sql(), where.params);
        where.params.addValue("limit", size).addValue("offset", (long) page * size);
        List<BronzeSummary> items = jdbc.query("SELECT " + BRONZE_SUMMARY_COLUMNS + BRONZE_FROM
                + where.sql() + window.idOrder("b.id") + " LIMIT :limit OFFSET :offset", where.params,
                (rs, i) -> bronzeSummary(rs));
        return Page.of(items, page, size, counted);
    }

    public BronzeDetail bronzeMessage(long id) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        List<BronzeDetail> found = jdbc.query("SELECT " + BRONZE_SUMMARY_COLUMNS
                + ", b.raw_payload, b.intake_channel, b.correlation_id" + BRONZE_FROM
                + " WHERE b.id = :id", params, (rs, i) -> new BronzeDetail(bronzeSummary(rs), rs.getString("raw_payload"),
                List.of(), rs.getString("intake_channel"), rs.getString("correlation_id")));
        if (found.isEmpty()) {
            throw new NotFoundException("No Bronze message " + id);
        }
        List<DownstreamRow> silver = new ArrayList<>();
        for (String table : SILVER_TABLES) {
            String patientColumn = "patients".equals(table) ? "id" : "patient_id";   // constants
            jdbc.query("SELECT id, " + patientColumn + " AS pid FROM silver." + table + " WHERE raw_message_id = :id ORDER BY id",
                    params, (rs, i) -> new DownstreamRow("silver." + table, rs.getLong("id"), rs.getLong("pid")))
                    .forEach(silver::add);
        }
        BronzeDetail detail = found.get(0);
        return new BronzeDetail(detail.summary(), detail.rawPayload(), silver, detail.intakeChannel(),
                detail.correlationId());
    }

    /**
     * Everything one Silver patient became in Gold: the lineage rows whose source is the patient or one of its
     * conditions / observations / medications / allergies (FHIR_MAP, PMI_PROVISIONAL), plus the Bronze messages
     * behind them.
     */
    public LineageGraph forSilverPatient(long silverPatientId) {
        MapSqlParameterSource id = new MapSqlParameterSource("id", silverPatientId);
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM silver.patients WHERE id = :id", id, Integer.class);
        if (exists == null || exists == 0) {
            throw new NotFoundException("No Silver patient " + silverPatientId);
        }
        StringBuilder sources = new StringBuilder("(l.source_table = 'patients' AND l.source_id = :id)");
        for (String table : SILVER_TABLES.subList(1, SILVER_TABLES.size())) {
            sources.append(" OR (l.source_table = '").append(table).append("' AND l.source_id IN (SELECT id FROM silver.")
                    .append(table).append(" WHERE patient_id = :id))");
        }
        List<LineageRecord> records = jdbc.query("SELECT " + RECORD_COLUMNS + " FROM gold.data_lineage l"
                + " WHERE l.source_schema = 'silver' AND (" + sources + ") ORDER BY l.transformed_at, l.id", id,
                (rs, i) -> record(rs));
        Set<Long> bronzeIds = new TreeSet<>(jdbc.queryForList(
                "SELECT raw_message_id FROM silver.patients WHERE id = :id AND raw_message_id IS NOT NULL", id, Long.class));
        for (String table : SILVER_TABLES.subList(1, SILVER_TABLES.size())) {
            bronzeIds.addAll(jdbc.queryForList("SELECT raw_message_id FROM silver." + table
                    + " WHERE patient_id = :id AND raw_message_id IS NOT NULL", id, Long.class));
        }
        return new LineageGraph("silver.patients", silverPatientId, records, bronze(bronzeIds));
    }

    /** What one Bronze message became: the Gold lineage rows of every Silver row written from it. */
    public LineageGraph forBronzeMessage(long bronzeMessageId) {
        MapSqlParameterSource id = new MapSqlParameterSource("id", bronzeMessageId);
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM bronze.raw_messages WHERE id = :id", id, Integer.class);
        if (exists == null || exists == 0) {
            throw new NotFoundException("No Bronze message " + bronzeMessageId);
        }
        StringBuilder sources = new StringBuilder();
        for (String table : SILVER_TABLES) {
            if (!sources.isEmpty()) {
                sources.append(" OR ");
            }
            sources.append("(l.source_table = '").append(table).append("' AND l.source_id IN (SELECT id FROM silver.")
                    .append(table).append(" WHERE raw_message_id = :id))");
        }
        List<LineageRecord> records = jdbc.query("SELECT " + RECORD_COLUMNS + " FROM gold.data_lineage l"
                + " WHERE l.source_schema = 'silver' AND (" + sources + ") ORDER BY l.transformed_at, l.id", id,
                (rs, i) -> record(rs));
        return new LineageGraph("bronze.raw_messages", bronzeMessageId, records, bronze(Set.of(bronzeMessageId)));
    }

    /** Bronze message summaries by id, oldest first. */
    public List<BronzeSummary> bronzeByIds(Set<Long> ids) {
        return bronze(ids);
    }

    private List<BronzeSummary> bronze(Set<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + BRONZE_SUMMARY_COLUMNS + BRONZE_FROM + " WHERE b.id IN (:ids) ORDER BY b.id",
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
                Sql.ts(rs, "processed_at"), rs.getString("hl7_message_type"), rs.getString("patient_name"),
                rs.getString("patient_mrn"), Sql.longOrNull(rs, "silver_patient_id"));
    }
}
