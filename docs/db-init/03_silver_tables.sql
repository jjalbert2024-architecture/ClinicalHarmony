-- =============================================================================
-- 03_silver_tables.sql
--
-- Silver layer: parsed and typed clinical entities, one row per source fact.
-- Rows here are still source-scoped (not yet deduplicated into a single
-- master patient record) — that reconciliation happens in Gold via the
-- Patient Master Index service.
-- =============================================================================

CREATE TABLE IF NOT EXISTS silver.patients (
    id                  BIGSERIAL PRIMARY KEY,
    source_system       VARCHAR(100)    NOT NULL,
    source_patient_id   VARCHAR(100)    NOT NULL,
    mrn                 VARCHAR(50),
    first_name          VARCHAR(100)    NOT NULL,
    last_name           VARCHAR(100)    NOT NULL,
    date_of_birth       DATE            NOT NULL,
    gender              VARCHAR(10)     NOT NULL
        CONSTRAINT chk_patients_gender
        CHECK (gender IN ('male', 'female', 'other', 'unknown')),
    address_line1       VARCHAR(200),
    address_line2       VARCHAR(200),
    city                VARCHAR(100),
    state               VARCHAR(2),
    postal_code         VARCHAR(10),
    phone               VARCHAR(20),
    raw_message_id      BIGINT REFERENCES bronze.raw_messages (id),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_patients_source
        UNIQUE (source_system, source_patient_id)
);

COMMENT ON TABLE silver.patients IS 'Parsed patient demographics, scoped to their source system, prior to master patient index deduplication.';
COMMENT ON COLUMN silver.patients.mrn IS 'Medical record number as assigned by the source system, not globally unique.';

CREATE TRIGGER trg_patients_set_updated_at
    BEFORE UPDATE ON silver.patients
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE INDEX IF NOT EXISTS idx_patients_mrn ON silver.patients (mrn);
CREATE INDEX IF NOT EXISTS idx_patients_name_dob ON silver.patients (last_name, first_name, date_of_birth);

-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS silver.conditions (
    id                  BIGSERIAL PRIMARY KEY,
    patient_id          BIGINT          NOT NULL REFERENCES silver.patients (id) ON DELETE CASCADE,
    code_system         VARCHAR(20)     NOT NULL
        CONSTRAINT chk_conditions_code_system
        CHECK (code_system IN ('ICD-10', 'SNOMED-CT')),
    code                VARCHAR(20)     NOT NULL,
    display             VARCHAR(255),
    clinical_status     VARCHAR(20)     NOT NULL DEFAULT 'active'
        CONSTRAINT chk_conditions_clinical_status
        CHECK (clinical_status IN ('active', 'recurrence', 'relapse', 'inactive', 'remission', 'resolved')),
    onset_date          DATE,
    recorded_date       DATE            NOT NULL DEFAULT CURRENT_DATE,
    raw_message_id      BIGINT REFERENCES bronze.raw_messages (id),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE silver.conditions IS 'Patient conditions/diagnoses coded against ICD-10 or SNOMED CT.';

CREATE TRIGGER trg_conditions_set_updated_at
    BEFORE UPDATE ON silver.conditions
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE INDEX IF NOT EXISTS idx_conditions_patient_id ON silver.conditions (patient_id);
CREATE INDEX IF NOT EXISTS idx_conditions_code ON silver.conditions (code_system, code);

-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS silver.observations (
    id                  BIGSERIAL PRIMARY KEY,
    patient_id          BIGINT          NOT NULL REFERENCES silver.patients (id) ON DELETE CASCADE,
    code_system         VARCHAR(20)     NOT NULL DEFAULT 'LOINC'
        CONSTRAINT chk_observations_code_system
        CHECK (code_system IN ('LOINC')),
    code                VARCHAR(20)     NOT NULL,
    display             VARCHAR(255),
    value_numeric       NUMERIC(18, 4),
    value_text          VARCHAR(500),
    unit                VARCHAR(50),
    status              VARCHAR(20)     NOT NULL DEFAULT 'final'
        CONSTRAINT chk_observations_status
        CHECK (status IN ('registered', 'preliminary', 'final', 'amended', 'corrected', 'cancelled')),
    effective_date      TIMESTAMPTZ     NOT NULL,
    raw_message_id      BIGINT REFERENCES bronze.raw_messages (id),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT chk_observations_has_value
        CHECK (value_numeric IS NOT NULL OR value_text IS NOT NULL)
);

COMMENT ON TABLE silver.observations IS 'Lab results and clinical measurements coded against LOINC.';

CREATE TRIGGER trg_observations_set_updated_at
    BEFORE UPDATE ON silver.observations
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE INDEX IF NOT EXISTS idx_observations_patient_id ON silver.observations (patient_id);
CREATE INDEX IF NOT EXISTS idx_observations_code ON silver.observations (code_system, code);
CREATE INDEX IF NOT EXISTS idx_observations_effective_date ON silver.observations (effective_date);

-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS silver.medications (
    id                  BIGSERIAL PRIMARY KEY,
    patient_id          BIGINT          NOT NULL REFERENCES silver.patients (id) ON DELETE CASCADE,
    rxnorm_code         VARCHAR(20)     NOT NULL,
    display             VARCHAR(255),
    dosage_text         VARCHAR(255),
    status              VARCHAR(20)     NOT NULL DEFAULT 'active'
        CONSTRAINT chk_medications_status
        CHECK (status IN ('active', 'completed', 'entered-in-error', 'stopped', 'on-hold', 'unknown')),
    start_date          DATE            NOT NULL,
    end_date            DATE,
    raw_message_id      BIGINT REFERENCES bronze.raw_messages (id),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT chk_medications_date_range
        CHECK (end_date IS NULL OR end_date >= start_date)
);

COMMENT ON TABLE silver.medications IS 'Medication orders/statements coded against RxNorm.';

CREATE TRIGGER trg_medications_set_updated_at
    BEFORE UPDATE ON silver.medications
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE INDEX IF NOT EXISTS idx_medications_patient_id ON silver.medications (patient_id);
CREATE INDEX IF NOT EXISTS idx_medications_rxnorm_code ON silver.medications (rxnorm_code);

-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS silver.validation_reports (
    id                  BIGSERIAL PRIMARY KEY,
    entity_type         VARCHAR(30)     NOT NULL
        CONSTRAINT chk_validation_reports_entity_type
        CHECK (entity_type IN ('PATIENT', 'CONDITION', 'OBSERVATION', 'MEDICATION', 'RAW_MESSAGE')),
    entity_id           BIGINT          NOT NULL,
    -- FK to ontology.clinical_rules is added in 05_ontology_tables.sql,
    -- once that table exists (ontology loads after silver in init order).
    rule_code           VARCHAR(50)     NOT NULL,
    severity            VARCHAR(10)     NOT NULL
        CONSTRAINT chk_validation_reports_severity
        CHECK (severity IN ('ERROR', 'WARNING', 'INFO')),
    message             TEXT            NOT NULL,
    is_resolved         BOOLEAN         NOT NULL DEFAULT FALSE,
    resolved_at         TIMESTAMPTZ,
    validated_at        TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE silver.validation_reports IS 'Findings emitted by the Ontology/Rules validation engines against Silver entities.';

CREATE INDEX IF NOT EXISTS idx_validation_reports_entity ON silver.validation_reports (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_validation_reports_rule_code ON silver.validation_reports (rule_code);
CREATE INDEX IF NOT EXISTS idx_validation_reports_unresolved
    ON silver.validation_reports (is_resolved) WHERE is_resolved = FALSE;
