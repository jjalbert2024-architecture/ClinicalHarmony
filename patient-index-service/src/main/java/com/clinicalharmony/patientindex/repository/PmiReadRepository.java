package com.clinicalharmony.patientindex.repository;

import com.clinicalharmony.patientindex.matching.PatientRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Date;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to silver.patients and gold.* — this service never writes Gold (GoldWriter in
 * fhir-compliance-service does). Table names are always schema-qualified.
 */
@Repository
public class PmiReadRepository {

    private static final String PATIENT_SELECT = """
            SELECT p.id, x.master_patient_id, m.match_status, p.source_system, p.source_patient_id, p.mrn,
                   p.first_name, p.last_name, p.date_of_birth, p.gender, p.address_line1, p.city, p.state,
                   p.postal_code, p.phone
            FROM silver.patients p
            LEFT JOIN gold.patient_source_xref x ON x.silver_patient_id = p.id
            LEFT JOIN gold.master_patients m ON m.id = x.master_patient_id
            """;

    private static final RowMapper<PatientRecord> PATIENT = (rs, n) -> {
        long master = rs.getLong("master_patient_id");
        Date dob = rs.getDate("date_of_birth");
        return new PatientRecord(rs.getLong("id"), rs.wasNull() ? null : master, rs.getString("match_status"),
                rs.getString("source_system"), rs.getString("source_patient_id"), rs.getString("mrn"),
                rs.getString("first_name"), rs.getString("last_name"), dob == null ? null : dob.toLocalDate(),
                rs.getString("gender"), rs.getString("address_line1"), rs.getString("city"), rs.getString("state"),
                rs.getString("postal_code"), rs.getString("phone"));
    };

    private static final String MASTER_COLUMNS = """
            id, master_patient_uuid, first_name, last_name, date_of_birth, gender, match_status, is_active,
            match_confidence_score, source_patient_count, created_at, updated_at
            """;

    private static final RowMapper<MasterRow> MASTER = (rs, n) -> new MasterRow(rs.getLong("id"),
            rs.getObject("master_patient_uuid", UUID.class), rs.getString("first_name"), rs.getString("last_name"),
            rs.getDate("date_of_birth").toLocalDate(), rs.getString("gender"), rs.getString("match_status"),
            rs.getBoolean("is_active"), rs.getBigDecimal("match_confidence_score"), rs.getInt("source_patient_count"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));

    private static final String MERGE_COLUMNS = """
            id, survivor_master_id, absorbed_master_id, moved_silver_patient_ids,
            cardinality(moved_fhir_resource_ids) AS moved_fhir_resource_count, match_confidence_score, match_method,
            decided_by, merged_at, unmerged_at, unmerged_by, unmerge_reason
            """;

    private static final RowMapper<MergeRow> MERGE = (rs, n) -> new MergeRow(rs.getLong("id"),
            rs.getLong("survivor_master_id"), rs.getLong("absorbed_master_id"), longs(rs.getArray("moved_silver_patient_ids")),
            rs.getInt("moved_fhir_resource_count"), rs.getBigDecimal("match_confidence_score"),
            rs.getString("match_method"), rs.getString("decided_by"), rs.getObject("merged_at", OffsetDateTime.class),
            rs.getObject("unmerged_at", OffsetDateTime.class), rs.getString("unmerged_by"), rs.getString("unmerge_reason"));

    private final JdbcTemplate jdbc;

    public PmiReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every Silver patient under an active master — the matcher's working set for a run. */
    public List<PatientRecord> activeMasterSources() {
        return jdbc.query(PATIENT_SELECT + " WHERE m.is_active ORDER BY p.id", PATIENT);
    }

    public Optional<PatientRecord> findPatient(long silverPatientId) {
        return jdbc.query(PATIENT_SELECT + " WHERE p.id = ?", PATIENT, silverPatientId).stream().findFirst();
    }

    public List<PatientRecord> sourcesOfMaster(long masterId) {
        return jdbc.query(PATIENT_SELECT + " WHERE x.master_patient_id = ? ORDER BY p.id", PATIENT, masterId);
    }

    /** Silver patients fhir-compliance-service hasn't built yet, so they have no master to match. */
    public int countSilverPatientsWithoutMaster() {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM silver.patients p
                WHERE NOT EXISTS (SELECT 1 FROM gold.patient_source_xref x WHERE x.silver_patient_id = p.id)
                """, Integer.class);
        return n == null ? 0 : n;
    }

    public Optional<MasterRow> findMaster(long masterId) {
        return jdbc.query("SELECT " + MASTER_COLUMNS + " FROM gold.master_patients WHERE id = ?", MASTER, masterId)
                .stream().findFirst();
    }

    public List<MasterRow> listMasters(String matchStatus, int limit) {
        if (matchStatus == null) {
            return jdbc.query("SELECT " + MASTER_COLUMNS + " FROM gold.master_patients ORDER BY id LIMIT ?", MASTER, limit);
        }
        return jdbc.query("SELECT " + MASTER_COLUMNS + " FROM gold.master_patients WHERE match_status = ? ORDER BY id LIMIT ?",
                MASTER, matchStatus, limit);
    }

    public List<FhirResourceSummary> fhirResourcesOfMaster(long masterId) {
        return jdbc.query("""
                        SELECT id, resource_type, fhir_id, is_us_core_compliant FROM gold.fhir_resources
                        WHERE master_patient_id = ? ORDER BY resource_type, fhir_id
                        """,
                (rs, n) -> new FhirResourceSummary(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)),
                masterId);
    }

    public List<MergeRow> mergesInvolving(long masterId) {
        return jdbc.query("SELECT " + MERGE_COLUMNS + " FROM gold.master_patient_merges "
                + "WHERE survivor_master_id = ? OR absorbed_master_id = ? ORDER BY id", MERGE, masterId, masterId);
    }

    public Optional<MergeRow> findMerge(long mergeId) {
        return jdbc.query("SELECT " + MERGE_COLUMNS + " FROM gold.master_patient_merges WHERE id = ?", MERGE, mergeId)
                .stream().findFirst();
    }

    private static List<Long> longs(Array array) throws SQLException {
        List<Long> out = new ArrayList<>();
        if (array != null) {
            for (Object o : (Object[]) array.getArray()) {
                out.add(((Number) o).longValue());
            }
        }
        return out;
    }
}
