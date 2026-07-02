-- =============================================================================
-- 04_gold_tables.sql
--
-- Gold layer: conformed, deduplicated, US Core FHIR-compliant entities ready
-- for downstream consumption by the API Gateway and React dashboard.
-- =============================================================================

CREATE TABLE IF NOT EXISTS gold.master_patients (
    id                      BIGSERIAL PRIMARY KEY,
    master_patient_uuid     UUID            NOT NULL DEFAULT gen_random_uuid(),
    first_name              VARCHAR(100)    NOT NULL,
    last_name               VARCHAR(100)    NOT NULL,
    date_of_birth           DATE            NOT NULL,
    gender                  VARCHAR(10)     NOT NULL
        CONSTRAINT chk_master_patients_gender
        CHECK (gender IN ('male', 'female', 'other', 'unknown')),
    match_confidence_score  NUMERIC(5, 4)
        CONSTRAINT chk_master_patients_confidence
        CHECK (match_confidence_score IS NULL OR match_confidence_score BETWEEN 0 AND 1),
    source_patient_count    INTEGER         NOT NULL DEFAULT 1,
    is_active                BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_master_patients_uuid UNIQUE (master_patient_uuid)
);

COMMENT ON TABLE gold.master_patients IS 'Deduplicated golden patient record produced by the Patient Master Index service.';
COMMENT ON COLUMN gold.master_patients.match_confidence_score IS 'Deduplication match confidence in [0,1]; NULL for a single-source, unmatched patient.';
COMMENT ON COLUMN gold.master_patients.source_patient_count IS 'Number of silver.patients rows merged into this master record.';

CREATE TRIGGER trg_master_patients_set_updated_at
    BEFORE UPDATE ON gold.master_patients
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE INDEX IF NOT EXISTS idx_master_patients_name_dob
    ON gold.master_patients (last_name, first_name, date_of_birth);
CREATE INDEX IF NOT EXISTS idx_master_patients_active
    ON gold.master_patients (is_active) WHERE is_active = TRUE;

-- -----------------------------------------------------------------------------
-- Cross-reference between Gold master patients and their contributing
-- Silver-layer source records, one row per (master patient, source record).
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS gold.patient_source_xref (
    id                  BIGSERIAL PRIMARY KEY,
    master_patient_id   BIGINT          NOT NULL REFERENCES gold.master_patients (id) ON DELETE CASCADE,
    silver_patient_id   BIGINT          NOT NULL REFERENCES silver.patients (id) ON DELETE CASCADE,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_patient_source_xref UNIQUE (master_patient_id, silver_patient_id)
);

COMMENT ON TABLE gold.patient_source_xref IS 'Links each gold.master_patients row to the silver.patients rows that were merged into it.';

CREATE INDEX IF NOT EXISTS idx_patient_source_xref_silver
    ON gold.patient_source_xref (silver_patient_id);

-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS gold.fhir_resources (
    id                  BIGSERIAL PRIMARY KEY,
    resource_type       VARCHAR(50)     NOT NULL,
    fhir_id             VARCHAR(100)    NOT NULL,
    fhir_version        VARCHAR(10)     NOT NULL DEFAULT 'R4',
    master_patient_id   BIGINT          REFERENCES gold.master_patients (id) ON DELETE CASCADE,
    resource_json        JSONB           NOT NULL,
    is_us_core_compliant BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_fhir_resources_type_id UNIQUE (resource_type, fhir_id)
);

COMMENT ON TABLE gold.fhir_resources IS 'US Core-conformant FHIR resources (Patient, Condition, Observation, MedicationStatement, ...) materialized from Silver/Gold entities.';
COMMENT ON COLUMN gold.fhir_resources.resource_json IS 'Full FHIR resource, serialized as JSON, validated against the US Core Implementation Guide.';

CREATE TRIGGER trg_fhir_resources_set_updated_at
    BEFORE UPDATE ON gold.fhir_resources
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE INDEX IF NOT EXISTS idx_fhir_resources_master_patient
    ON gold.fhir_resources (master_patient_id);
CREATE INDEX IF NOT EXISTS idx_fhir_resources_type
    ON gold.fhir_resources (resource_type);
CREATE INDEX IF NOT EXISTS idx_fhir_resources_json_gin
    ON gold.fhir_resources USING GIN (resource_json);

-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS gold.data_lineage (
    id                      BIGSERIAL PRIMARY KEY,
    source_schema           VARCHAR(20)     NOT NULL,
    source_table            VARCHAR(100)    NOT NULL,
    source_id               BIGINT          NOT NULL,
    target_schema           VARCHAR(20)     NOT NULL,
    target_table            VARCHAR(100)    NOT NULL,
    target_id               BIGINT          NOT NULL,
    transformation_step     VARCHAR(100)    NOT NULL,
    transformation_details  JSONB,
    transformed_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE gold.data_lineage IS 'Audit trail recording how each downstream record was derived, hop by hop, from Bronze through Gold — powers the Data Lineage dashboard view.';
COMMENT ON COLUMN gold.data_lineage.transformation_step IS 'Named pipeline step that produced this hop, e.g. HL7_PARSE, ONTOLOGY_VALIDATE, PMI_MERGE, FHIR_MAP.';

CREATE INDEX IF NOT EXISTS idx_data_lineage_source
    ON gold.data_lineage (source_schema, source_table, source_id);
CREATE INDEX IF NOT EXISTS idx_data_lineage_target
    ON gold.data_lineage (target_schema, target_table, target_id);
