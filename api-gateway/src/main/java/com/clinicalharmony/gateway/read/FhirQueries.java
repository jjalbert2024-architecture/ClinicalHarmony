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
 * ({@code compliance_notes}), where it came from, the patient it's about, and lineage.
 */
@Repository
public class FhirQueries {

    public record TypeCount(String resourceType, long total, long usCoreCompliant) {
    }

    /**
     * @param origin          where the resource came from, read from its gold.data_lineage row: {@code pipeline} (built
     *                        from a Silver row that links back to a raw Bronze message), {@code silver-seed} (built from a
     *                        seeded Silver row with no Bronze message), {@code api} (Phase 5's caller-supplied
     *                        /api/fhir-compliance/generate) or {@code gold-seed} (inserted straight into Gold, migration
     *                        19); null when no lineage row says
     * @param sourceSystem    source system of the Silver patient the resource is about (its
     *                        {@code Patient/silver-patient-N} reference); null when it isn't about one
     * @param sourcePatientId that patient's id in its source system (the MRN for the HL7 feeds)
     */
    public record ResourceSummary(long id, String resourceType, String fhirId, Long masterPatientId,
                                  boolean usCoreCompliant, String complianceStatus, Integer errorCount,
                                  Integer warningCount, String profile, OffsetDateTime updatedAt, String origin,
                                  String sourceSystem, String sourcePatientId) {
    }

    /** A source system with Gold resources about its patients — the FHIR Viewer's source dropdown. */
    public record SourceCount(String sourceSystem, long resources) {
    }

    public record ResourceDetail(ResourceSummary summary, JsonNode resource, JsonNode complianceNotes,
                                 List<LineageQueries.LineageRecord> lineage) {
    }

    /** Allowed values of the {@code origin} filter. */
    public static final String ORIGINS = "pipeline|silver-seed|api|gold-seed";

    /**
     * The silver.patients id a resource is about: the resource itself for a Patient, otherwise its subject /
     * patient / beneficiary reference — when that is a {@code Patient/silver-patient-N} built from Silver.
     */
    private static final String SILVER_PATIENT_ID = """
            (substring(CASE WHEN f.resource_type = 'Patient' THEN 'Patient/' || f.fhir_id
                            ELSE coalesce(f.resource_json #>> '{subject,reference}', f.resource_json #>> '{patient,reference}',
                                          f.resource_json #>> '{beneficiary,reference}') END
                       from '^Patient/silver-patient-([0-9]+)$'))::bigint""";

    private static final String ORIGIN = """
            (SELECT CASE WHEN l.transformation_step = 'FHIR_MAP' AND l.transformation_details #>> '{steps,0,stage}' = 'BRONZE'
                              THEN 'pipeline'
                         WHEN l.transformation_step = 'FHIR_MAP' THEN 'silver-seed'
                         WHEN l.transformation_step = 'API_GENERATE' THEN 'api'
                         ELSE 'gold-seed' END
             FROM gold.data_lineage l
             WHERE l.target_schema = 'gold' AND l.target_table = 'fhir_resources' AND l.target_id = f.id
               AND l.transformation_step IN ('FHIR_MAP', 'API_GENERATE', 'SEED_LOAD')
             ORDER BY l.id LIMIT 1)""";

    /**
     * Code filter: an exact coding code (any case), or a display / text containing it — over {@code code}
     * (Condition, Observation, AllergyIntolerance…) and {@code medicationCodeableConcept} (MedicationRequest).
     */
    private static final String CODE_MATCH = """
            (f.resource_json #>> '{code,text}' ILIKE :codeLike
             OR f.resource_json #>> '{medicationCodeableConcept,text}' ILIKE :codeLike
             OR EXISTS (SELECT 1 FROM jsonb_array_elements(
                            (CASE WHEN jsonb_typeof(f.resource_json #> '{code,coding}') = 'array'
                                  THEN f.resource_json #> '{code,coding}' ELSE '[]'::jsonb END)
                            || (CASE WHEN jsonb_typeof(f.resource_json #> '{medicationCodeableConcept,coding}') = 'array'
                                     THEN f.resource_json #> '{medicationCodeableConcept,coding}' ELSE '[]'::jsonb END)) c
                        WHERE upper(c ->> 'code') = upper(:code) OR c ->> 'display' ILIKE :codeLike))""";

    /**
     * Patient filter, the same rule as Patient Timeline's search: masters whose name contains the text, or with a
     * linked Silver record whose MRN / source id equals it (any case). Every resource of that person, whichever of
     * their records it came from.
     */
    private static final String PATIENT_MATCH = """
            f.master_patient_id IN (
                SELECT m.id FROM gold.master_patients m
                WHERE (m.first_name || ' ' || m.last_name) ILIKE :patientLike OR m.last_name ILIKE :patientLike
                   OR EXISTS (SELECT 1 FROM gold.patient_source_xref x JOIN silver.patients p ON p.id = x.silver_patient_id
                              WHERE x.master_patient_id = m.id
                                AND (upper(p.mrn) = upper(:patient) OR upper(p.source_patient_id) = upper(:patient))))""";

    private static final String FROM = " FROM gold.fhir_resources f LEFT JOIN silver.patients sp ON sp.id = "
            + SILVER_PATIENT_ID;

    private static final String SUMMARY_COLUMNS = """
            f.id, f.resource_type, f.fhir_id, f.master_patient_id, f.is_us_core_compliant, f.updated_at,
            f.compliance_notes ->> 'status' AS compliance_status,
            (f.compliance_notes ->> 'errorCount')::int AS error_count,
            (f.compliance_notes ->> 'warningCount')::int AS warning_count,
            f.compliance_notes ->> 'profile' AS profile,
            sp.source_system, sp.source_patient_id, """ + ORIGIN + " AS origin";

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

    /** Source systems whose patients have Gold resources, with how many. */
    public List<SourceCount> sourceSystems() {
        return jdbc.query("SELECT sp.source_system, count(*) AS n" + FROM
                        + " WHERE sp.source_system IS NOT NULL GROUP BY sp.source_system ORDER BY sp.source_system",
                new MapSqlParameterSource(), (rs, i) -> new SourceCount(rs.getString("source_system"), rs.getLong("n")));
    }

    /** Sortable columns of the FHIR Viewer list (the {@code sort} query parameter). */
    public static final String SORTS = "resource|master|compliance|updated";

    /**
     * Natural order of {@code fhir_id}: the text before a trailing number, then that number numerically
     * (so {@code silver-patient-8 < -80 < -100}), then the full id as a tie-break.
     */
    private static String naturalFhirId(String dir) {
        return "regexp_replace(f.fhir_id, '[0-9]+$', '') " + dir
                + ", substring(f.fhir_id from '([0-9]+)$')::numeric " + dir + " NULLS FIRST, f.fhir_id " + dir;
    }

    static String orderBy(String sort, boolean descending) {
        String dir = descending ? "DESC" : "ASC";
        String resource = "f.resource_type " + dir + ", " + naturalFhirId(dir);
        return switch (sort == null ? "resource" : sort) {
            case "master" -> "f.master_patient_id " + dir + " NULLS LAST, " + resource;
            case "compliance" -> "f.is_us_core_compliant " + dir + ", (f.compliance_notes ->> 'errorCount')::int "
                    + (descending ? "ASC" : "DESC") + " NULLS LAST, " + resource;
            case "updated" -> "f.updated_at " + dir + ", f.id " + dir;
            default -> resource;
        };
    }

    /** Every filter is optional; values are bound, never concatenated. */
    public record Filter(String type, Long masterPatientId, Boolean compliant, String q, String patient, String origin,
                         String sourceSystem, String code) {

        public static Filter none() {
            return new Filter(null, null, null, null, null, null, null, null);
        }
    }

    public Page<ResourceSummary> search(Filter filter, String sort, boolean descending, int page, int size) {
        String patient = trimmed(filter.patient());
        String code = trimmed(filter.code());
        Sql.Where where = new Sql.Where()
                .and(Sql.has(filter.type()), "f.resource_type = :type", "type", filter.type())
                .and(filter.masterPatientId() != null, "f.master_patient_id = :master", "master", filter.masterPatientId())
                .and(filter.compliant() != null, "f.is_us_core_compliant = :compliant", "compliant", filter.compliant())
                .and(Sql.has(filter.q()), "f.fhir_id ILIKE :q", "q", Sql.has(filter.q()) ? Sql.contains(filter.q().trim()) : null)
                .and(patient != null, PATIENT_MATCH, "patient", patient)
                .and(Sql.has(filter.origin()), ORIGIN + " = :origin", "origin", filter.origin())
                .and(Sql.has(filter.sourceSystem()), "sp.source_system = :sourceSystem", "sourceSystem", filter.sourceSystem())
                .and(code != null, CODE_MATCH, "code", code);
        if (patient != null) {
            where.params.addValue("patientLike", Sql.contains(patient));
        }
        if (code != null) {
            where.params.addValue("codeLike", Sql.contains(code));
        }
        Long total = jdbc.queryForObject("SELECT count(*)" + FROM + where.sql(), where.params, Long.class);
        where.params.addValue("limit", size).addValue("offset", (long) page * size);
        List<ResourceSummary> items = jdbc.query("SELECT " + SUMMARY_COLUMNS + FROM + where.sql()
                + " ORDER BY " + orderBy(sort, descending) + " LIMIT :limit OFFSET :offset", where.params, (rs, i) -> summary(rs));
        return Page.of(items, page, size, total == null ? 0 : total);
    }

    public ResourceDetail detail(String type, String fhirId) {
        List<ResourceDetail> found = jdbc.query("SELECT " + SUMMARY_COLUMNS + ", f.resource_json, f.compliance_notes"
                        + FROM + " WHERE f.resource_type = :type AND f.fhir_id = :fhirId",
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

    private static String trimmed(String s) {
        return Sql.has(s) ? s.trim() : null;
    }

    private static ResourceSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ResourceSummary(rs.getLong("id"), rs.getString("resource_type"), rs.getString("fhir_id"),
                Sql.longOrNull(rs, "master_patient_id"), rs.getBoolean("is_us_core_compliant"),
                rs.getString("compliance_status"), Sql.intOrNull(rs, "error_count"),
                Sql.intOrNull(rs, "warning_count"), rs.getString("profile"), Sql.ts(rs, "updated_at"),
                rs.getString("origin"), rs.getString("source_system"), rs.getString("source_patient_id"));
    }
}
