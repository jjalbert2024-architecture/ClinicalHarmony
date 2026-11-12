package com.clinicalharmony.fhircompliance.repository.silver;

/**
 * The Silver tables the FHIR builders read from — a closed set, so table names can be put into
 * SQL without ever coming from a request. {@code patientIdColumn} is how a row reaches its
 * silver.patients row (itself, for patients).
 */
public enum SilverTable {
    PATIENTS("patients", "id"),
    CONDITIONS("conditions", "patient_id"),
    OBSERVATIONS("observations", "patient_id"),
    MEDICATIONS("medications", "patient_id");

    private final String tableName;
    private final String patientIdColumn;

    SilverTable(String tableName, String patientIdColumn) {
        this.tableName = tableName;
        this.patientIdColumn = patientIdColumn;
    }

    public String tableName() {
        return tableName;
    }

    String patientIdColumn() {
        return patientIdColumn;
    }

    /** "silver.conditions/42" — the {@code ref} form used in lineage steps. */
    public String ref(long id) {
        return "silver." + tableName + "/" + id;
    }
}
