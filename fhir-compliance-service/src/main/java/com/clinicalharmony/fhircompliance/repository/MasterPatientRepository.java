package com.clinicalharmony.fhircompliance.repository;

import com.clinicalharmony.fhircompliance.repository.silver.SilverPatientRow;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.Optional;

/**
 * gold.master_patients + gold.patient_source_xref. Until patient-index-service (Phase 6) exists,
 * GoldWriter creates one PROVISIONAL master per silver.patients row (migration 18's match_status).
 */
@Repository
public class MasterPatientRepository {

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
}
