package com.clinicalharmony.gateway.read;

import com.clinicalharmony.gateway.downstream.Downstream;
import com.clinicalharmony.gateway.downstream.RuleOwnership;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Validation Report view over {@code silver.validation_reports} (written by ontology-service,
 * rules-engine-service and fhir-compliance-service) joined to the rule catalog
 * {@code ontology.clinical_rules}.
 */
@Repository
public class ValidationQueries {

    public record Bucket(String key, String label, String category, long open, long total, OffsetDateTime lastSeen) {
    }

    public record Summary(long total, long open, long resolved, OffsetDateTime lastValidatedAt,
                          List<Bucket> bySeverity, List<Bucket> byRule, List<Bucket> byEntityType) {
    }

    public record Finding(long id, String ruleCode, String ruleName, String ruleCategory, String severity,
                          String entityType, long entityId, Long patientId, String message, String badValue,
                          String suggestedValue, boolean resolved, OffsetDateTime resolvedAt,
                          OffsetDateTime validatedAt) {
    }

    public record Rule(String code, String name, String description, String category, String defaultSeverity,
                       boolean active, String evaluatedBy, boolean toggleable, long openFindings,
                       OffsetDateTime updatedAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ValidationQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Summary summary() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        var totals = jdbc.queryForMap("""
                SELECT count(*) AS total, count(*) FILTER (WHERE NOT is_resolved) AS open,
                       count(*) FILTER (WHERE is_resolved) AS resolved, max(validated_at) AS last
                FROM silver.validation_reports
                """, none);
        List<Bucket> bySeverity = jdbc.query("""
                SELECT severity AS key, severity AS label, NULL AS category,
                       count(*) FILTER (WHERE NOT is_resolved) AS open, count(*) AS total, max(validated_at) AS last
                FROM silver.validation_reports GROUP BY severity
                ORDER BY CASE severity WHEN 'ERROR' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END
                """, none, (rs, i) -> bucket(rs));
        List<Bucket> byRule = jdbc.query("""
                SELECT v.rule_code AS key, r.rule_name AS label, r.rule_category AS category,
                       count(*) FILTER (WHERE NOT v.is_resolved) AS open, count(*) AS total, max(v.validated_at) AS last
                FROM silver.validation_reports v LEFT JOIN ontology.clinical_rules r ON r.rule_code = v.rule_code
                GROUP BY v.rule_code, r.rule_name, r.rule_category
                ORDER BY open DESC, total DESC, v.rule_code
                """, none, (rs, i) -> bucket(rs));
        List<Bucket> byEntity = jdbc.query("""
                SELECT entity_type AS key, entity_type AS label, NULL AS category,
                       count(*) FILTER (WHERE NOT is_resolved) AS open, count(*) AS total, max(validated_at) AS last
                FROM silver.validation_reports GROUP BY entity_type ORDER BY open DESC, entity_type
                """, none, (rs, i) -> bucket(rs));
        return new Summary(((Number) totals.get("total")).longValue(), ((Number) totals.get("open")).longValue(),
                ((Number) totals.get("resolved")).longValue(), toOffset(totals.get("last")), bySeverity, byRule, byEntity);
    }

    public Page<Finding> findings(String ruleCode, String severity, String entityType, String category, Long patientId,
                                  Boolean resolved, int page, int size) {
        Sql.Where where = new Sql.Where()
                .and(Sql.has(ruleCode), "v.rule_code = :ruleCode", "ruleCode", ruleCode)
                .and(Sql.has(severity), "v.severity = :severity", "severity", severity)
                .and(Sql.has(entityType), "v.entity_type = :entityType", "entityType", entityType)
                .and(Sql.has(category), "r.rule_category = :category", "category", category)
                .and(patientId != null, "v.patient_id = :patientId", "patientId", patientId)
                .and(resolved != null, "v.is_resolved = :resolved", "resolved", resolved);
        String from = " FROM silver.validation_reports v LEFT JOIN ontology.clinical_rules r ON r.rule_code = v.rule_code";
        Long total = jdbc.queryForObject("SELECT count(*)" + from + where.sql(), where.params, Long.class);
        where.params.addValue("limit", size).addValue("offset", (long) page * size);
        List<Finding> items = jdbc.query("""
                SELECT v.id, v.rule_code, r.rule_name, r.rule_category, v.severity, v.entity_type, v.entity_id,
                       v.patient_id, v.message, v.bad_value, v.suggested_value, v.is_resolved, v.resolved_at, v.validated_at
                """ + from + where.sql() + " ORDER BY v.validated_at DESC, v.id DESC LIMIT :limit OFFSET :offset",
                where.params, (rs, i) -> new Finding(rs.getLong("id"), rs.getString("rule_code"),
                        rs.getString("rule_name"), rs.getString("rule_category"), rs.getString("severity"),
                        rs.getString("entity_type"), rs.getLong("entity_id"), Sql.longOrNull(rs, "patient_id"),
                        rs.getString("message"), rs.getString("bad_value"), rs.getString("suggested_value"),
                        rs.getBoolean("is_resolved"), Sql.ts(rs, "resolved_at"), Sql.ts(rs, "validated_at")));
        return Page.of(items, page, size, total == null ? 0 : total);
    }

    public List<Rule> rules() {
        return jdbc.query(RULES_SQL + " ORDER BY r.rule_category, r.rule_code", new MapSqlParameterSource(),
                (rs, i) -> rule(rs));
    }

    public Rule rule(String code) {
        List<Rule> found = jdbc.query(RULES_SQL + " WHERE r.rule_code = :code", new MapSqlParameterSource("code", code),
                (rs, i) -> rule(rs));
        if (found.isEmpty()) {
            throw new NotFoundException("No clinical rule " + code);
        }
        return found.get(0);
    }

    private static final String RULES_SQL = """
            SELECT r.rule_code, r.rule_name, r.description, r.rule_category, r.default_severity, r.is_active, r.updated_at,
                   (SELECT count(*) FROM silver.validation_reports v WHERE v.rule_code = r.rule_code AND NOT v.is_resolved)
                       AS open_findings
            FROM ontology.clinical_rules r
            """;

    private static Rule rule(java.sql.ResultSet rs) throws java.sql.SQLException {
        String code = rs.getString("rule_code");
        Downstream owner = RuleOwnership.evaluatedBy(code);
        return new Rule(code, rs.getString("rule_name"), rs.getString("description"), rs.getString("rule_category"),
                rs.getString("default_severity"), rs.getBoolean("is_active"),
                owner == null ? null : owner.serviceName(), RuleOwnership.toggleable(code),
                rs.getLong("open_findings"), Sql.ts(rs, "updated_at"));
    }

    private static Bucket bucket(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Bucket(rs.getString("key"), rs.getString("label"), rs.getString("category"), rs.getLong("open"),
                rs.getLong("total"), Sql.ts(rs, "last"));
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        return value instanceof OffsetDateTime odt ? odt : null;
    }
}
