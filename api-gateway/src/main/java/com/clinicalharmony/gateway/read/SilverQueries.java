package com.clinicalharmony.gateway.read;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Data Lineage layer browser, Silver layer: the {@code silver.patients} list and one Silver patient with
 * everything around it — the Bronze messages that fed it, its clinical rows (each linked to the Gold FHIR
 * resource built from it), its validation findings, and its Gold master. Read-only, like every read model.
 */
@Repository
public class SilverQueries {

    /** Allowed {@code validationStatus} filter values; NOT_TRACKED = seed rows (validation_status NULL). */
    public static final String VALIDATION_STATUSES = "VALIDATED|PENDING_VALIDATION|NOT_TRACKED";

    public record SilverPatientSummary(long id, String sourceSystem, String sourcePatientId, String mrn,
                                       String firstName, String lastName, LocalDate dateOfBirth, String gender,
                                       String validationStatus, OffsetDateTime validatedAt, int conditions,
                                       int observations, int medications, int allergies, int openFindings,
                                       Long masterPatientId, String masterMatchStatus, Long rawMessageId,
                                       OffsetDateTime updatedAt) {
    }

    public record SourceCount(String sourceSystem, long patients) {
    }

    /**
     * One Silver clinical row of any kind, with the Gold FHIR resource mapped from it (null when not built yet).
     *
     * @param kind CONDITION | OBSERVATION | MEDICATION | ALLERGY
     */
    public record SilverRow(String kind, long id, String codeSystem, String code, String display, String status,
                            String value, LocalDate date, OffsetDateTime instant, Long rawMessageId,
                            Long fhirResourceId, String fhirResourceType, String fhirId, Boolean usCoreCompliant) {
    }

    public record Finding(long id, String ruleCode, String severity, String entityType, Long entityId, String message,
                          String badValue, String suggestedValue, boolean resolved, OffsetDateTime validatedAt) {
    }

    public record GoldLink(Long masterPatientId, String masterMatchStatus, Long patientFhirResourceId,
                           String patientFhirId, Boolean patientUsCoreCompliant) {
    }

    public record SilverPatientDetail(SilverPatientSummary summary, String addressLine1, String addressLine2, String city,
                                      String state, String postalCode, String phone, String validationError,
                                      int validationAttempts, OffsetDateTime createdAt,
                                      List<LineageQueries.BronzeSummary> bronzeMessages, List<SilverRow> rows,
                                      List<Finding> findings, GoldLink gold) {
    }

    /** Where a Silver row of any table lives: its patient. */
    public record RowOwner(String table, long id, long silverPatientId) {
    }

    private static final String SUMMARY_COLUMNS = """
            p.id, p.source_system, p.source_patient_id, p.mrn, p.first_name, p.last_name, p.date_of_birth, p.gender,
            p.validation_status, p.validated_at, p.raw_message_id, p.updated_at,
            (SELECT count(*) FROM silver.conditions c WHERE c.patient_id = p.id) AS n_conditions,
            (SELECT count(*) FROM silver.observations o WHERE o.patient_id = p.id) AS n_observations,
            (SELECT count(*) FROM silver.medications m WHERE m.patient_id = p.id) AS n_medications,
            (SELECT count(*) FROM silver.allergies a WHERE a.patient_id = p.id) AS n_allergies,
            (SELECT count(*) FROM silver.validation_reports r WHERE r.patient_id = p.id AND NOT r.is_resolved) AS n_findings,
            x.master_patient_id, mp.match_status AS master_match_status
            """;

    private static final String FROM = " " + """
             FROM silver.patients p
             LEFT JOIN gold.patient_source_xref x ON x.silver_patient_id = p.id
             LEFT JOIN gold.master_patients mp ON mp.id = x.master_patient_id""";

    /** FHIR_MAP lineage row → the Gold resource built from one Silver row. */
    private static final String FHIR_OF = " " + """
            LEFT JOIN LATERAL (SELECT f.id, f.resource_type, f.fhir_id, f.is_us_core_compliant
                                 FROM gold.data_lineage l JOIN gold.fhir_resources f ON f.id = l.target_id
                                WHERE l.source_schema = 'silver' AND l.source_table = '%s' AND l.source_id = s.id
                                  AND l.target_schema = 'gold' AND l.target_table = 'fhir_resources'
                                  AND l.transformation_step = 'FHIR_MAP'
                                ORDER BY l.id DESC LIMIT 1) g ON true""";

    private final NamedParameterJdbcTemplate jdbc;
    private final LineageQueries lineage;

    public SilverQueries(NamedParameterJdbcTemplate jdbc, LineageQueries lineage) {
        this.jdbc = jdbc;
        this.lineage = lineage;
    }

    /**
     * Newest first. {@code q}: part of the name, or EXACTLY (any case) the MRN / source patient id — the Patient
     * Timeline rule — or the Silver id itself when it's a number.
     */
    public Page<SilverPatientSummary> patients(String q, String sourceSystem, String validationStatus, int page, int size) {
        String text = Sql.has(q) ? q.trim() : null;
        Long numeric = text != null && text.matches("\\d{1,18}") ? Long.parseLong(text) : null;
        Sql.Where where = new Sql.Where()
                .and(text != null, """
                        ((p.first_name || ' ' || p.last_name) ILIKE :qLike OR p.last_name ILIKE :qLike
                         OR upper(p.mrn) = upper(:q) OR upper(p.source_patient_id) = upper(:q)
                         OR p.id = :qId)""", "q", text)
                .and(Sql.has(sourceSystem), "p.source_system = :source", "source", sourceSystem)
                .and("NOT_TRACKED".equals(validationStatus), "p.validation_status IS NULL", null, null)
                .and(Sql.has(validationStatus) && !"NOT_TRACKED".equals(validationStatus),
                        "p.validation_status = :vstatus", "vstatus", validationStatus);
        if (text != null) {
            where.params.addValue("qLike", Sql.contains(text)).addValue("qId", numeric == null ? -1L : numeric);
        }
        Long total = jdbc.queryForObject("SELECT count(*)" + FROM + where.sql(), where.params, Long.class);
        where.params.addValue("limit", size).addValue("offset", (long) page * size);
        List<SilverPatientSummary> items = jdbc.query("SELECT " + SUMMARY_COLUMNS + FROM + where.sql()
                        + " ORDER BY p.updated_at DESC, p.id DESC LIMIT :limit OFFSET :offset", where.params,
                (rs, i) -> summary(rs));
        return Page.of(items, page, size, total == null ? 0 : total);
    }

    public List<SourceCount> sourceSystems() {
        return jdbc.query("SELECT source_system, count(*) AS n FROM silver.patients GROUP BY 1 ORDER BY 1",
                new MapSqlParameterSource(), (rs, i) -> new SourceCount(rs.getString("source_system"), rs.getLong("n")));
    }

    public SilverPatientDetail patient(long id) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", id);
        List<SilverPatientDetail> found = jdbc.query("SELECT " + SUMMARY_COLUMNS + """
                        , p.address_line1, p.address_line2, p.city, p.state, p.postal_code, p.phone, p.validation_error,
                          p.validation_attempts, p.created_at""" + FROM + " WHERE p.id = :id", p,
                (rs, i) -> new SilverPatientDetail(summary(rs), rs.getString("address_line1"),
                        rs.getString("address_line2"), rs.getString("city"), rs.getString("state"),
                        rs.getString("postal_code"), rs.getString("phone"), rs.getString("validation_error"),
                        rs.getInt("validation_attempts"), Sql.ts(rs, "created_at"), List.of(), List.of(), List.of(), null));
        if (found.isEmpty()) {
            throw new NotFoundException("No Silver patient " + id);
        }
        SilverPatientDetail d = found.get(0);

        List<SilverRow> rows = new java.util.ArrayList<>();
        rows.addAll(jdbc.query("""
                SELECT s.id, s.code_system, s.code, s.display, s.clinical_status AS status, NULL AS value,
                       coalesce(s.onset_date, s.recorded_date) AS d, NULL::timestamptz AS t, s.raw_message_id,
                       g.id AS fid, g.resource_type, g.fhir_id, g.is_us_core_compliant
                FROM silver.conditions s""" + FHIR_OF.formatted("conditions") + " WHERE s.patient_id = :id ORDER BY s.id",
                p, (rs, i) -> row("CONDITION", rs)));
        rows.addAll(jdbc.query("""
                SELECT s.id, s.code_system, s.code, s.display, s.status,
                       coalesce(s.value_numeric::text || coalesce(' ' || s.unit, ''), s.value_text) AS value,
                       NULL::date AS d, s.effective_date AS t, s.raw_message_id,
                       g.id AS fid, g.resource_type, g.fhir_id, g.is_us_core_compliant
                FROM silver.observations s""" + FHIR_OF.formatted("observations") + " WHERE s.patient_id = :id ORDER BY s.id",
                p, (rs, i) -> row("OBSERVATION", rs)));
        rows.addAll(jdbc.query("""
                SELECT s.id, 'RXNORM' AS code_system, s.rxnorm_code AS code, s.display, s.status, s.dosage_text AS value,
                       s.start_date AS d, NULL::timestamptz AS t, s.raw_message_id,
                       g.id AS fid, g.resource_type, g.fhir_id, g.is_us_core_compliant
                FROM silver.medications s""" + FHIR_OF.formatted("medications") + " WHERE s.patient_id = :id ORDER BY s.id",
                p, (rs, i) -> row("MEDICATION", rs)));
        rows.addAll(jdbc.query("""
                SELECT s.id, s.code_system, s.code, s.display, s.severity AS status, s.reaction AS value,
                       NULL::date AS d, NULL::timestamptz AS t, s.raw_message_id,
                       g.id AS fid, g.resource_type, g.fhir_id, g.is_us_core_compliant
                FROM silver.allergies s""" + FHIR_OF.formatted("allergies") + " WHERE s.patient_id = :id ORDER BY s.id",
                p, (rs, i) -> row("ALLERGY", rs)));

        java.util.Set<Long> bronzeIds = new java.util.TreeSet<>();
        if (d.summary().rawMessageId() != null) {
            bronzeIds.add(d.summary().rawMessageId());
        }
        rows.stream().map(SilverRow::rawMessageId).filter(java.util.Objects::nonNull).forEach(bronzeIds::add);

        List<Finding> findings = jdbc.query("""
                SELECT id, rule_code, severity, entity_type, entity_id, message, bad_value, suggested_value, is_resolved,
                       validated_at
                FROM silver.validation_reports WHERE patient_id = :id
                ORDER BY is_resolved, validated_at DESC, id DESC LIMIT 200
                """, p, (rs, i) -> new Finding(rs.getLong("id"), rs.getString("rule_code"), rs.getString("severity"),
                rs.getString("entity_type"), Sql.longOrNull(rs, "entity_id"), rs.getString("message"),
                rs.getString("bad_value"), rs.getString("suggested_value"), rs.getBoolean("is_resolved"),
                Sql.ts(rs, "validated_at")));

        List<GoldLink> gold = jdbc.query("""
                SELECT f.id, f.fhir_id, f.is_us_core_compliant FROM gold.fhir_resources f
                WHERE f.resource_type = 'Patient' AND f.fhir_id = 'silver-patient-' || :id
                """, p, (rs, i) -> new GoldLink(d.summary().masterPatientId(), d.summary().masterMatchStatus(),
                rs.getLong("id"), rs.getString("fhir_id"), rs.getBoolean("is_us_core_compliant")));
        GoldLink goldLink = gold.isEmpty()
                ? new GoldLink(d.summary().masterPatientId(), d.summary().masterMatchStatus(), null, null, null)
                : gold.get(0);

        return new SilverPatientDetail(d.summary(), d.addressLine1(), d.addressLine2(), d.city(), d.state(),
                d.postalCode(), d.phone(), d.validationError(), d.validationAttempts(), d.createdAt(),
                lineage.bronzeByIds(bronzeIds), rows, findings, goldLink);
    }

    /** The Silver patient a row of any Silver table belongs to — so a lineage ref like silver.allergies/165 can link. */
    public RowOwner owner(String table, long id) {
        if (!LineageQueries.SILVER_TABLES.contains(table)) {
            throw new NotFoundException("Unknown Silver table " + table);
        }
        String column = "patients".equals(table) ? "id" : "patient_id";   // both constants
        List<Long> found = jdbc.queryForList("SELECT " + column + " FROM silver." + table + " WHERE id = :id",
                new MapSqlParameterSource("id", id), Long.class);
        if (found.isEmpty()) {
            throw new NotFoundException("No silver." + table + " row " + id);
        }
        return new RowOwner(table, id, found.get(0));
    }

    private static SilverRow row(String kind, java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SilverRow(kind, rs.getLong("id"), rs.getString("code_system"), rs.getString("code"),
                rs.getString("display"), rs.getString("status"), rs.getString("value"), Sql.date(rs, "d"),
                Sql.ts(rs, "t"), Sql.longOrNull(rs, "raw_message_id"), Sql.longOrNull(rs, "fid"),
                rs.getString("resource_type"), rs.getString("fhir_id"),
                rs.getObject("is_us_core_compliant") == null ? null : rs.getBoolean("is_us_core_compliant"));
    }

    private static SilverPatientSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SilverPatientSummary(rs.getLong("id"), rs.getString("source_system"), rs.getString("source_patient_id"),
                rs.getString("mrn"), rs.getString("first_name"), rs.getString("last_name"), Sql.date(rs, "date_of_birth"),
                rs.getString("gender"), rs.getString("validation_status"), Sql.ts(rs, "validated_at"),
                rs.getInt("n_conditions"), rs.getInt("n_observations"), rs.getInt("n_medications"),
                rs.getInt("n_allergies"), rs.getInt("n_findings"), Sql.longOrNull(rs, "master_patient_id"),
                rs.getString("master_match_status"), Sql.longOrNull(rs, "raw_message_id"), Sql.ts(rs, "updated_at"));
    }
}
