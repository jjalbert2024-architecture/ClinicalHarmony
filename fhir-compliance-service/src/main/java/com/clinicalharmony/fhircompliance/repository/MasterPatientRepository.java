package com.clinicalharmony.fhircompliance.repository;

import com.clinicalharmony.fhircompliance.repository.silver.SilverPatientRow;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * gold.master_patients + gold.patient_source_xref. GoldWriter creates one PROVISIONAL master per
 * silver.patients row (migration 18's match_status); patient-index-service (Phase 6) then decides
 * matches, and GoldWriter applies them here (confirm → MATCHED, merge → survivor MATCHED + absorbed
 * MERGED, unmerge → both MATCHED again).
 */
@Repository
public class MasterPatientRepository {

    private static final String SNAPSHOT_COLUMNS =
            "id, master_patient_uuid, match_status, is_active, match_confidence_score, source_patient_count";

    private final JdbcTemplate jdbcTemplate;

    public MasterPatientRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Row-locks the Silver patient for the rest of the transaction, so two concurrent builds of the
     * same patient serialize instead of both creating a master (the second would then hit the
     * unique xref index and fail).
     */
    public void lockSilverPatient(long silverPatientId) {
        jdbcTemplate.query("SELECT id FROM silver.patients WHERE id = ? FOR UPDATE", rs -> null, silverPatientId);
    }

    public Optional<Long> findMasterIdForSilverPatient(long silverPatientId) {
        return jdbcTemplate.query("SELECT master_patient_id FROM gold.patient_source_xref WHERE silver_patient_id = ?",
                (rs, n) -> rs.getLong(1), silverPatientId).stream().findFirst();
    }

    public long insertProvisional(SilverPatientRow patient) {
        return DataAccessUtils.requiredSingleResult(jdbcTemplate.query("""
                        INSERT INTO gold.master_patients
                            (first_name, last_name, date_of_birth, gender, match_confidence_score,
                             source_patient_count, match_status)
                        VALUES (?, ?, ?, ?, NULL, 1, 'PROVISIONAL')
                        RETURNING id
                        """,
                (rs, n) -> rs.getLong(1),
                patient.firstName(), patient.lastName(), Date.valueOf(patient.dateOfBirth()), patient.gender()));
    }

    public void insertSourceLink(long masterPatientId, long silverPatientId) {
        jdbcTemplate.update("INSERT INTO gold.patient_source_xref (master_patient_id, silver_patient_id) VALUES (?, ?)",
                masterPatientId, silverPatientId);
    }

    /**
     * Keeps a provisional master's demographics in step with its single Silver source. Masters the
     * PMI has matched/merged are left alone — their golden demographics are the PMI's call.
     */
    public int refreshProvisionalDemographics(long masterPatientId, SilverPatientRow patient) {
        return jdbcTemplate.update("""
                        UPDATE gold.master_patients
                        SET first_name = ?, last_name = ?, date_of_birth = ?, gender = ?
                        WHERE id = ? AND match_status = 'PROVISIONAL'
                          AND (first_name, last_name, date_of_birth, gender) IS DISTINCT FROM (?, ?, ?, ?)
                        """,
                patient.firstName(), patient.lastName(), Date.valueOf(patient.dateOfBirth()), patient.gender(),
                masterPatientId,
                patient.firstName(), patient.lastName(), Date.valueOf(patient.dateOfBirth()), patient.gender());
    }

    /** Row-locks one master; empty if it doesn't exist. Callers lock in ascending id order. */
    public Optional<MasterPatientSnapshot> lock(long masterPatientId) {
        return jdbcTemplate.query("SELECT " + SNAPSHOT_COLUMNS + " FROM gold.master_patients WHERE id = ? FOR UPDATE",
                (rs, n) -> snapshot(rs), masterPatientId).stream().findFirst();
    }

    /** Moves every source link of {@code fromMasterId} to {@code toMasterId}; returns the Silver ids moved. */
    public List<Long> moveSourceLinks(long fromMasterId, long toMasterId) {
        return jdbcTemplate.query("""
                        UPDATE gold.patient_source_xref SET master_patient_id = ?
                        WHERE master_patient_id = ?
                        RETURNING silver_patient_id
                        """,
                (rs, n) -> rs.getLong(1), toMasterId, fromMasterId);
    }

    /** Moves just these Silver patients' links back; returns how many were still on {@code fromMasterId}. */
    public int moveSourceLinks(List<Long> silverPatientIds, long fromMasterId, long toMasterId) {
        return jdbcTemplate.update("""
                        UPDATE gold.patient_source_xref SET master_patient_id = ?
                        WHERE master_patient_id = ? AND silver_patient_id = ANY (?::bigint[])
                        """,
                toMasterId, fromMasterId, PgArrays.bigintArray(silverPatientIds));
    }

    /** Absorbed by a merge: MERGED, inactive, no sources of its own any more. */
    public MasterPatientSnapshot markMerged(long masterPatientId) {
        return DataAccessUtils.requiredSingleResult(jdbcTemplate.query("""
                        UPDATE gold.master_patients
                        SET match_status = 'MERGED', is_active = FALSE, match_confidence_score = NULL,
                            source_patient_count = (SELECT count(*) FROM gold.patient_source_xref WHERE master_patient_id = ?)
                        WHERE id = ?
                        RETURNING\s""" + SNAPSHOT_COLUMNS,
                (rs, n) -> snapshot(rs), masterPatientId, masterPatientId));
    }

    /**
     * Confirmed by the PMI: MATCHED and active, source count recomputed from the xref, confidence =
     * {@code score} (the weakest live merge into it; null for a confirmed single-source master).
     */
    public MasterPatientSnapshot markMatched(long masterPatientId, BigDecimal score) {
        return DataAccessUtils.requiredSingleResult(jdbcTemplate.query("""
                        UPDATE gold.master_patients
                        SET match_status = 'MATCHED', is_active = TRUE, match_confidence_score = ?,
                            source_patient_count = (SELECT count(*) FROM gold.patient_source_xref WHERE master_patient_id = ?)
                        WHERE id = ?
                        RETURNING\s""" + SNAPSHOT_COLUMNS,
                (rs, n) -> snapshot(rs), score, masterPatientId, masterPatientId));
    }

    private static MasterPatientSnapshot snapshot(ResultSet rs) throws SQLException {
        return new MasterPatientSnapshot(rs.getLong("id"), rs.getObject("master_patient_uuid", UUID.class),
                rs.getString("match_status"), rs.getBoolean("is_active"), rs.getBigDecimal("match_confidence_score"),
                rs.getInt("source_patient_count"));
    }
}
