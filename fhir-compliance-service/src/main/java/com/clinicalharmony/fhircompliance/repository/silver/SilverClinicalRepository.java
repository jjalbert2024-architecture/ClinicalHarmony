package com.clinicalharmony.fhircompliance.repository.silver;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the Silver-layer clinical tables the FHIR builders source from. Table names
 * are fully qualified (the pool's search path is {@code ontology}), same convention as every
 * other repository in this project.
 */
@Repository
public class SilverClinicalRepository {

    private static final String PATIENT_COLUMNS = """
            id, source_system, source_patient_id, mrn, first_name, last_name, date_of_birth, gender,
            address_line1, address_line2, city, state, postal_code, phone
            """;

    private final JdbcTemplate jdbcTemplate;

    public SilverClinicalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Long> findAllPatientIds() {
        return jdbcTemplate.queryForList("SELECT id FROM silver.patients ORDER BY id", Long.class);
    }

    public Optional<SilverPatientRow> findPatient(long id) {
        List<SilverPatientRow> rows = jdbcTemplate.query(
                "SELECT " + PATIENT_COLUMNS + " FROM silver.patients WHERE id = ?",
                (rs, n) -> new SilverPatientRow(
                        rs.getLong("id"),
                        rs.getString("source_system"),
                        rs.getString("source_patient_id"),
                        rs.getString("mrn"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        toLocalDate(rs.getDate("date_of_birth")),
                        rs.getString("gender"),
                        rs.getString("address_line1"),
                        rs.getString("address_line2"),
                        rs.getString("city"),
                        rs.getString("state"),
                        rs.getString("postal_code"),
                        rs.getString("phone")),
                id);
        return rows.stream().findFirst();
    }

    public List<SilverConditionRow> findConditions(long patientId) {
        return jdbcTemplate.query("""
                        SELECT id, patient_id, code_system, code, display, clinical_status, onset_date, recorded_date
                        FROM silver.conditions WHERE patient_id = ? ORDER BY id
                        """,
                (rs, n) -> new SilverConditionRow(
                        rs.getLong("id"),
                        rs.getLong("patient_id"),
                        rs.getString("code_system"),
                        rs.getString("code"),
                        rs.getString("display"),
                        rs.getString("clinical_status"),
                        toLocalDate(rs.getDate("onset_date")),
                        toLocalDate(rs.getDate("recorded_date"))),
                patientId);
    }

    public List<SilverObservationRow> findObservations(long patientId) {
        return jdbcTemplate.query("""
                        SELECT id, patient_id, code, display, value_numeric, value_text, unit, status, effective_date
                        FROM silver.observations WHERE patient_id = ? ORDER BY id
                        """,
                (rs, n) -> new SilverObservationRow(
                        rs.getLong("id"),
                        rs.getLong("patient_id"),
                        rs.getString("code"),
                        rs.getString("display"),
                        rs.getBigDecimal("value_numeric"),
                        rs.getString("value_text"),
                        rs.getString("unit"),
                        rs.getString("status"),
                        rs.getObject("effective_date", OffsetDateTime.class)),
                patientId);
    }

    public List<SilverMedicationRow> findMedications(long patientId) {
        return jdbcTemplate.query("""
                        SELECT id, patient_id, rxnorm_code, display, dosage_text, status, start_date, end_date
                        FROM silver.medications WHERE patient_id = ? ORDER BY id
                        """,
                (rs, n) -> new SilverMedicationRow(
                        rs.getLong("id"),
                        rs.getLong("patient_id"),
                        rs.getString("rxnorm_code"),
                        rs.getString("display"),
                        rs.getString("dosage_text"),
                        rs.getString("status"),
                        toLocalDate(rs.getDate("start_date")),
                        toLocalDate(rs.getDate("end_date"))),
                patientId);
    }

    private static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }
}
