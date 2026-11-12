package com.clinicalharmony.fhircompliance.repository;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * gold.fhir_resources access. Writes go only through GoldWriter (the single owner of Gold-layer
 * writes); reads back the CMS-0057 read endpoints and seed revalidation.
 *
 * <p>Upserts on (resource_type, fhir_id) so rebuilding the same resource updates its row rather
 * than violating the table's unique constraint.
 */
@Repository
public class GoldFhirResourceRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO gold.fhir_resources
                (resource_type, fhir_id, resource_json, is_us_core_compliant, compliance_notes, master_patient_id)
            VALUES (?, ?, ?::jsonb, ?, ?::jsonb, ?)
            ON CONFLICT (resource_type, fhir_id)
            DO UPDATE SET resource_json = EXCLUDED.resource_json,
                          is_us_core_compliant = EXCLUDED.is_us_core_compliant,
                          compliance_notes = EXCLUDED.compliance_notes,
                          master_patient_id = COALESCE(EXCLUDED.master_patient_id, gold.fhir_resources.master_patient_id),
                          updated_at = now()
            RETURNING id
            """;

    private static final String ROW_COLUMNS = """
            id, resource_type, fhir_id, resource_json::text AS resource_json, is_us_core_compliant,
            compliance_notes::text AS compliance_notes, master_patient_id, created_at, updated_at
            """;

    private static final RowMapper<GoldFhirResourceRow> ROW_MAPPER = (rs, n) -> new GoldFhirResourceRow(
            rs.getLong("id"),
            rs.getString("resource_type"),
            rs.getString("fhir_id"),
            rs.getString("resource_json"),
            rs.getBoolean("is_us_core_compliant"),
            rs.getString("compliance_notes"),
            rs.getObject("master_patient_id", Long.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    public GoldFhirResourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long upsert(GoldFhirResourceRecord record) {
        List<Long> ids = jdbcTemplate.query(
                UPSERT_SQL,
                (rs, rowNum) -> rs.getLong("id"),
                record.resourceType(),
                record.fhirId(),
                record.resourceJson(),
                record.usCoreCompliant(),
                record.complianceNotes(),
                record.masterPatientId()
        );
        return DataAccessUtils.requiredSingleResult(ids);
    }

    public Optional<GoldFhirResourceRow> findByTypeAndFhirId(String resourceType, String fhirId) {
        return jdbcTemplate.query("SELECT " + ROW_COLUMNS + " FROM gold.fhir_resources WHERE resource_type = ? AND fhir_id = ?",
                ROW_MAPPER, resourceType, fhirId).stream().findFirst();
    }

    /** Rows seeded straight into Gold that no validator has checked yet (migration 19). */
    public List<GoldFhirResourceRow> findPendingValidation() {
        return jdbcTemplate.query("SELECT " + ROW_COLUMNS
                        + " FROM gold.fhir_resources WHERE compliance_notes->>'status' = 'PENDING_VALIDATION' ORDER BY id",
                ROW_MAPPER);
    }

    /** The master_patient_id of the Gold Patient with this FHIR id, if it exists and has one. */
    public Optional<Long> findMasterPatientIdForPatient(String patientFhirId) {
        return jdbcTemplate.query("""
                        SELECT master_patient_id FROM gold.fhir_resources
                        WHERE resource_type = 'Patient' AND fhir_id = ? AND master_patient_id IS NOT NULL
                        """,
                (rs, n) -> rs.getLong(1), patientFhirId).stream().findFirst();
    }

    /**
     * Points every Gold resource that references {@code Patient/<patientFhirId>} — as subject,
     * patient or beneficiary — at the given master. Back-fills resources written before the
     * patient had a master (e.g. the CMS-0057 seed). Returns how many rows changed.
     */
    public int linkPatientReferencesToMaster(String patientFhirId, long masterPatientId) {
        String reference = "Patient/" + patientFhirId;
        return jdbcTemplate.update("""
                        UPDATE gold.fhir_resources SET master_patient_id = ?, updated_at = now()
                        WHERE master_patient_id IS DISTINCT FROM ?
                          AND ((resource_type = 'Patient' AND fhir_id = ?)
                               OR resource_json->'subject'->>'reference' = ?
                               OR resource_json->'patient'->>'reference' = ?
                               OR resource_json->'beneficiary'->>'reference' = ?)
                        """,
                masterPatientId, masterPatientId, patientFhirId, reference, reference, reference);
    }

    /**
     * Search one resource type. Each criterion is an SQL predicate over {@code resource_json} /
     * {@code fhir_id} with {@code ?} placeholders (built by the caller from a whitelist — never
     * from raw request text); all must match.
     */
    public List<GoldFhirResourceRow> search(String resourceType, List<SqlCriterion> criteria, int limit) {
        Query q = query(resourceType, criteria);
        List<Object> args = new ArrayList<>(q.args());
        args.add(limit);
        return jdbcTemplate.query("SELECT " + ROW_COLUMNS + " FROM gold.fhir_resources WHERE " + q.where()
                + " ORDER BY fhir_id LIMIT ?", ROW_MAPPER, args.toArray());
    }

    public long count(String resourceType, List<SqlCriterion> criteria) {
        Query q = query(resourceType, criteria);
        Long total = jdbcTemplate.queryForObject("SELECT count(*) FROM gold.fhir_resources WHERE " + q.where(),
                Long.class, q.args().toArray());
        return total == null ? 0 : total;
    }

    private static Query query(String resourceType, List<SqlCriterion> criteria) {
        StringBuilder where = new StringBuilder("resource_type = ?");
        List<Object> args = new ArrayList<>();
        args.add(resourceType);
        for (SqlCriterion criterion : criteria) {
            where.append(" AND (").append(criterion.sql()).append(')');
            args.addAll(criterion.args());
        }
        return new Query(where.toString(), args);
    }

    private record Query(String where, List<Object> args) {
    }

    /** One search predicate: trusted SQL text with positional {@code ?} args. */
    public record SqlCriterion(String sql, List<Object> args) {
    }
}
