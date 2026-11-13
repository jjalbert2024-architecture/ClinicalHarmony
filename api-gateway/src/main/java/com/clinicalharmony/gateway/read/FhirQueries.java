package com.clinicalharmony.gateway.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * FHIR Viewer: every Gold resource ({@code gold.fhir_resources}, all types — not just the CMS-0057
 * ones fhir-compliance-service's {@code /fhir/r4} serves), with its validator outcome
 * ({@code compliance_notes}) and lineage.
 */
@Repository
public class FhirQueries {

    public record TypeCount(String resourceType, long total, long usCoreCompliant) {
    }

    public record ResourceSummary(long id, String resourceType, String fhirId, Long masterPatientId,
                                  boolean usCoreCompliant, String complianceStatus, Integer errorCount,
                                  Integer warningCount, String profile, OffsetDateTime updatedAt) {
    }

    public record ResourceDetail(ResourceSummary summary, JsonNode resource, JsonNode complianceNotes,
                                 List<LineageQueries.LineageRecord> lineage) {
    }

    private static final String SUMMARY_COLUMNS = """
            f.id, f.resource_type, f.fhir_id, f.master_patient_id, f.is_us_core_compliant, f.updated_at,
            f.compliance_notes ->> 'status' AS compliance_status,
            (f.compliance_notes ->> 'errorCount')::int AS error_count,
            (f.compliance_notes ->> 'warningCount')::int AS warning_count,
            f.compliance_notes ->> 'profile' AS profile
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final LineageQueries lineage;

    public FhirQueries(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper, LineageQueries lineage) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.lineage = lineage;
    }

    public List<TypeCount> types() {
        return jdbc.query("""
                SELECT resource_type, count(*) AS total, count(*) FILTER (WHERE is_us_core_compliant) AS compliant
                FROM gold.fhir_resources GROUP BY resource_type ORDER BY resource_type
                """, new MapSqlParameterSource(), (rs, i) -> new TypeCount(rs.getString("resource_type"),
                rs.getLong("total"), rs.getLong("compliant")));
    }

    public Page<ResourceSummary> search(String type, Long masterPatientId, Boolean compliant, String q, int page, int size) {
        Sql.Where where = new Sql.Where()
                .and(Sql.has(type), "f.resource_type = :type", "type", type)
                .and(masterPatientId != null, "f.master_patient_id = :master", "master", masterPatientId)
                .and(compliant != null, "f.is_us_core_compliant = :compliant", "compliant", compliant)
                .and(Sql.has(q), "f.fhir_id ILIKE :q", "q", Sql.has(q) ? Sql.contains(q.trim()) : null);
        Long total = jdbc.queryForObject("SELECT count(*) FROM gold.fhir_resources f" + where.sql(), where.params, Long.class);
        where.params.addValue("limit", size).addValue("offset", (long) page * size);
        List<ResourceSummary> items = jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM gold.fhir_resources f" + where.sql()
                + " ORDER BY f.resource_type, f.fhir_id LIMIT :limit OFFSET :offset", where.params, (rs, i) -> summary(rs));
        return Page.of(items, page, size, total == null ? 0 : total);
    }

    public ResourceDetail detail(String type, String fhirId) {
        List<ResourceDetail> found = jdbc.query("SELECT " + SUMMARY_COLUMNS + ", f.resource_json, f.compliance_notes"
                        + " FROM gold.fhir_resources f WHERE f.resource_type = :type AND f.fhir_id = :fhirId",
                new MapSqlParameterSource("type", type).addValue("fhirId", fhirId),
                (rs, i) -> new ResourceDetail(summary(rs), Sql.json(objectMapper, rs, "resource_json"),
                        Sql.json(objectMapper, rs, "compliance_notes"), List.of()));
        if (found.isEmpty()) {
            throw new NotFoundException("No Gold FHIR resource " + type + "/" + fhirId);
        }
        ResourceDetail detail = found.get(0);
        return new ResourceDetail(detail.summary(), detail.resource(), detail.complianceNotes(),
                lineage.forFhirResource(detail.summary().id()).records());
    }

    private static ResourceSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ResourceSummary(rs.getLong("id"), rs.getString("resource_type"), rs.getString("fhir_id"),
                Sql.longOrNull(rs, "master_patient_id"), rs.getBoolean("is_us_core_compliant"),
                rs.getString("compliance_status"), Sql.intOrNull(rs, "error_count"),
                Sql.intOrNull(rs, "warning_count"), rs.getString("profile"), Sql.ts(rs, "updated_at"));
    }
}
