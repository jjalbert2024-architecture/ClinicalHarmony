package com.clinicalharmony.gateway.read;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Patient Timeline view: Gold masters (search + detail) and one master's clinical timeline across
 * every Silver record linked to it — conditions, observations and medications from all sources,
 * each tagged with its source system, whether its code is in the reference terminology, and the
 * Gold FHIR resource it was mapped to (via {@code gold.data_lineage} FHIR_MAP rows).
 */
@Repository
public class PatientQueries {

    public static final Set<String> EVENT_KINDS = Set.of("CONDITION", "OBSERVATION", "MEDICATION");

    public record MasterSummary(long id, UUID uuid, String firstName, String lastName, LocalDate dateOfBirth,
                                String gender, String matchStatus, BigDecimal matchConfidenceScore,
                                int sourcePatientCount, boolean active, List<String> sourceSystems,
                                OffsetDateTime updatedAt) {
    }

    public record SourceRecord(long silverPatientId, String sourceSystem, String sourcePatientId, String mrn,
                               String firstName, String lastName, LocalDate dateOfBirth, String gender,
                               String addressLine1, String city, String state, String postalCode, String phone,
                               Long rawMessageId) {
    }

    public record MergeEvent(long mergeId, long survivorMasterId, long absorbedMasterId, BigDecimal score,
                             String matchMethod, String decidedBy, OffsetDateTime mergedAt, int movedSilverPatients,
                             OffsetDateTime unmergedAt, String unmergedBy, String unmergeReason) {
    }

    public record ResourceCount(String resourceType, long total, long usCoreCompliant) {
    }

    public record MasterDetail(MasterSummary master, Long mergedIntoMasterId, List<SourceRecord> sources,
                               List<MergeEvent> merges, List<ResourceCount> fhirResources, long pendingReviews) {
    }

    /**
     * One timeline entry. {@code datePrecision} is {@code day} for conditions/medications (DATE
     * columns, reported as UTC midnight of that date) and {@code instant} for observations.
     */
    public record TimelineEvent(String kind, long id, OffsetDateTime occurredAt, String datePrecision,
                                OffsetDateTime endedAt, String codeSystem, String code, String display,
                                boolean codeKnown, String status, String value, String sourceSystem,
                                long silverPatientId, Long rawMessageId, Long fhirResourceId, String fhirReference,
                                Boolean usCoreCompliant) {
    }

    public record Timeline(long masterPatientId, int eventCount, List<TimelineEvent> events) {
    }

    private static final String MASTER_COLUMNS = """
            m.id, m.master_patient_uuid, m.first_name, m.last_name, m.date_of_birth, m.gender, m.match_status,
            m.match_confidence_score, m.source_patient_count, m.is_active, m.updated_at,
            (SELECT array_agg(DISTINCT p.source_system ORDER BY p.source_system)
               FROM gold.patient_source_xref x JOIN silver.patients p ON p.id = x.silver_patient_id
              WHERE x.master_patient_id = m.id) AS source_systems
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PatientQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page<MasterSummary> search(String q, LocalDate dob, String status, boolean includeMerged, int page, int size) {
        Sql.Where where = new Sql.Where()
                .and(Sql.has(q), "((m.first_name || ' ' || m.last_name) ILIKE :q OR m.last_name ILIKE :q)",
                        "q", Sql.has(q) ? Sql.contains(q.trim()) : null)
                .and(dob != null, "m.date_of_birth = :dob", "dob", dob)
                .and(Sql.has(status), "m.match_status = :status", "status", status)
                .and(!includeMerged, "m.is_active", null, null);
        Long total = jdbc.queryForObject("SELECT count(*) FROM gold.master_patients m" + where.sql(), where.params, Long.class);
        where.params.addValue("limit", size).addValue("offset", (long) page * size);
        List<MasterSummary> items = jdbc.query("SELECT " + MASTER_COLUMNS + " FROM gold.master_patients m" + where.sql()
                + " ORDER BY m.last_name, m.first_name, m.id LIMIT :limit OFFSET :offset", where.params, (rs, i) -> master(rs));
        return Page.of(items, page, size, total == null ? 0 : total);
    }

    public MasterDetail detail(long masterId) {
        MapSqlParameterSource id = new MapSqlParameterSource("id", masterId);
        List<MasterSummary> found = jdbc.query("SELECT " + MASTER_COLUMNS + " FROM gold.master_patients m WHERE m.id = :id",
                id, (rs, i) -> master(rs));
        if (found.isEmpty()) {
            throw new NotFoundException("No master patient " + masterId);
        }
        List<Long> mergedInto = jdbc.queryForList("""
                SELECT survivor_master_id FROM gold.master_patient_merges
                WHERE absorbed_master_id = :id AND unmerged_at IS NULL
                """, id, Long.class);
        List<SourceRecord> sources = jdbc.query("""
                SELECT p.id, p.source_system, p.source_patient_id, p.mrn, p.first_name, p.last_name, p.date_of_birth,
                       p.gender, p.address_line1, p.city, p.state, p.postal_code, p.phone, p.raw_message_id
                FROM gold.patient_source_xref x JOIN silver.patients p ON p.id = x.silver_patient_id
                WHERE x.master_patient_id = :id ORDER BY p.id
                """, id, (rs, i) -> new SourceRecord(rs.getLong("id"), rs.getString("source_system"),
                rs.getString("source_patient_id"), rs.getString("mrn"), rs.getString("first_name"),
                rs.getString("last_name"), Sql.date(rs, "date_of_birth"), rs.getString("gender"),
                rs.getString("address_line1"), rs.getString("city"), rs.getString("state"),
                rs.getString("postal_code"), rs.getString("phone"), Sql.longOrNull(rs, "raw_message_id")));
        List<MergeEvent> merges = jdbc.query("""
                SELECT id, survivor_master_id, absorbed_master_id, match_confidence_score, match_method, decided_by,
                       merged_at, cardinality(moved_silver_patient_ids) AS moved, unmerged_at, unmerged_by, unmerge_reason
                FROM gold.master_patient_merges
                WHERE survivor_master_id = :id OR absorbed_master_id = :id
                ORDER BY merged_at DESC, id DESC
                """, id, (rs, i) -> new MergeEvent(rs.getLong("id"), rs.getLong("survivor_master_id"),
                rs.getLong("absorbed_master_id"), rs.getBigDecimal("match_confidence_score"),
                rs.getString("match_method"), rs.getString("decided_by"), Sql.ts(rs, "merged_at"), rs.getInt("moved"),
                Sql.ts(rs, "unmerged_at"), rs.getString("unmerged_by"), rs.getString("unmerge_reason")));
        List<ResourceCount> resources = jdbc.query("""
                SELECT resource_type, count(*) AS total, count(*) FILTER (WHERE is_us_core_compliant) AS compliant
                FROM gold.fhir_resources WHERE master_patient_id = :id
                GROUP BY resource_type ORDER BY resource_type
                """, id, (rs, i) -> new ResourceCount(rs.getString("resource_type"), rs.getLong("total"),
                rs.getLong("compliant")));
        Long pending = jdbc.queryForObject("""
                SELECT count(*) FROM pmi.match_candidates
                WHERE status = 'PENDING' AND (master_a_id = :id OR master_b_id = :id)
                """, id, Long.class);
        return new MasterDetail(found.get(0), mergedInto.isEmpty() ? null : mergedInto.get(0), sources, merges,
                resources, pending == null ? 0 : pending);
    }

    public Timeline timeline(long masterId, LocalDate from, LocalDate to, Set<String> kinds) {
        MapSqlParameterSource id = new MapSqlParameterSource("id", masterId);
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM gold.master_patients WHERE id = :id", id, Integer.class);
        if (exists == null || exists == 0) {
            throw new NotFoundException("No master patient " + masterId);
        }
        // DATE columns become UTC midnight explicitly: a bare date::timestamptz would use the session
        // time zone (the JVM's, set by the JDBC driver) and shift dates east of UTC to the day before.
        Sql.Where where = new Sql.Where()
                .and(true, "e.kind IN (:kinds)", "kinds", kinds)
                .and(from != null, "e.occurred_at >= (CAST(:from AS date)::timestamp AT TIME ZONE 'UTC')", "from", from)
                .and(to != null, "e.occurred_at < ((CAST(:to AS date) + 1)::timestamp AT TIME ZONE 'UTC')", "to", to);
        where.params.addValue("id", masterId);
        List<TimelineEvent> events = jdbc.query("""
                WITH linked AS (SELECT silver_patient_id AS id FROM gold.patient_source_xref WHERE master_patient_id = :id),
                events AS (
                    SELECT 'CONDITION' AS kind, c.id, (COALESCE(c.onset_date, c.recorded_date)::timestamp AT TIME ZONE 'UTC') AS occurred_at,
                           'day' AS date_precision, NULL::timestamptz AS ended_at, c.code_system, c.code, c.display,
                           CASE WHEN c.code_system = 'ICD-10'
                                THEN EXISTS (SELECT 1 FROM ontology.icd10_codes i WHERE i.code = c.code)
                                ELSE EXISTS (SELECT 1 FROM ontology.snomed_codes s WHERE s.concept_id = c.code) END AS code_known,
                           c.clinical_status AS status, NULL::text AS value, c.patient_id, c.raw_message_id,
                           'conditions' AS silver_table
                    FROM silver.conditions c WHERE c.patient_id IN (SELECT id FROM linked)
                    UNION ALL
                    SELECT 'OBSERVATION', o.id, o.effective_date, 'instant', NULL, o.code_system, o.code, o.display,
                           EXISTS (SELECT 1 FROM ontology.loinc_codes l WHERE l.loinc_num = o.code),
                           o.status,
                           COALESCE(trim_scale(o.value_numeric)::text || COALESCE(' ' || o.unit, ''), o.value_text),
                           o.patient_id, o.raw_message_id, 'observations'
                    FROM silver.observations o WHERE o.patient_id IN (SELECT id FROM linked)
                    UNION ALL
                    SELECT 'MEDICATION', m.id, (m.start_date::timestamp AT TIME ZONE 'UTC'), 'day', (m.end_date::timestamp AT TIME ZONE 'UTC'), 'RxNorm',
                           m.rxnorm_code, m.display,
                           EXISTS (SELECT 1 FROM ontology.rxnorm_codes r WHERE r.rxcui = m.rxnorm_code AND r.is_active),
                           m.status, m.dosage_text, m.patient_id, m.raw_message_id, 'medications'
                    FROM silver.medications m WHERE m.patient_id IN (SELECT id FROM linked)
                )
                SELECT e.*, p.source_system, g.id AS fhir_id_pk, g.resource_type, g.fhir_id, g.is_us_core_compliant
                FROM events e
                JOIN silver.patients p ON p.id = e.patient_id
                LEFT JOIN LATERAL (
                    SELECT f.id, f.resource_type, f.fhir_id, f.is_us_core_compliant
                    FROM gold.data_lineage dl JOIN gold.fhir_resources f ON f.id = dl.target_id
                    WHERE dl.source_schema = 'silver' AND dl.source_table = e.silver_table AND dl.source_id = e.id
                      AND dl.target_schema = 'gold' AND dl.target_table = 'fhir_resources'
                      AND dl.transformation_step = 'FHIR_MAP'
                    ORDER BY dl.id DESC LIMIT 1
                ) g ON TRUE
                """ + where.sql() + " ORDER BY e.occurred_at DESC, e.kind, e.id", where.params, (rs, i) -> {
            Long fhirPk = Sql.longOrNull(rs, "fhir_id_pk");
            return new TimelineEvent(rs.getString("kind"), rs.getLong("id"), Sql.ts(rs, "occurred_at"),
                    rs.getString("date_precision"), Sql.ts(rs, "ended_at"), rs.getString("code_system"),
                    rs.getString("code"), rs.getString("display"), rs.getBoolean("code_known"), rs.getString("status"),
                    rs.getString("value"), rs.getString("source_system"), rs.getLong("patient_id"),
                    Sql.longOrNull(rs, "raw_message_id"), fhirPk,
                    fhirPk == null ? null : rs.getString("resource_type") + "/" + rs.getString("fhir_id"),
                    fhirPk == null ? null : rs.getBoolean("is_us_core_compliant"));
        });
        return new Timeline(masterId, events.size(), events);
    }

    private static MasterSummary master(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MasterSummary(rs.getLong("id"), rs.getObject("master_patient_uuid", UUID.class),
                rs.getString("first_name"), rs.getString("last_name"), Sql.date(rs, "date_of_birth"),
                rs.getString("gender"), rs.getString("match_status"), rs.getBigDecimal("match_confidence_score"),
                rs.getInt("source_patient_count"), rs.getBoolean("is_active"), Sql.strings(rs, "source_systems"),
                Sql.ts(rs, "updated_at"));
    }
}
