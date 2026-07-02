-- =============================================================================
-- 05_ontology_tables.sql
--
-- Ontology schema: reference terminology used by the Ontology Validation
-- Engine (ICD-10, LOINC, RxNorm) plus the clinical rule definitions used by
-- the Rules Engine service. These are reference/lookup tables — low write
-- volume, high read volume, refreshed periodically from NLM/CMS source
-- distributions.
-- =============================================================================

CREATE TABLE IF NOT EXISTS ontology.icd10_codes (
    code            VARCHAR(10)     PRIMARY KEY,
    description     VARCHAR(500)    NOT NULL,
    category        VARCHAR(255),
    chapter         VARCHAR(255),
    is_billable     BOOLEAN         NOT NULL DEFAULT TRUE,
    effective_date  DATE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE ontology.icd10_codes IS 'ICD-10-CM diagnosis code reference set used to validate silver.conditions.code.';
COMMENT ON COLUMN ontology.icd10_codes.is_billable IS 'Whether this code is specific enough to be billable (leaf-level code).';

CREATE INDEX IF NOT EXISTS idx_icd10_codes_category ON ontology.icd10_codes (category);

-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ontology.loinc_codes (
    loinc_num           VARCHAR(20)     PRIMARY KEY,
    component           VARCHAR(255)    NOT NULL,
    property            VARCHAR(50),
    time_aspect         VARCHAR(50),
    system              VARCHAR(100),
    scale_type          VARCHAR(50),
    method_type         VARCHAR(100),
    long_common_name    VARCHAR(500),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE ontology.loinc_codes IS 'LOINC reference set used to validate silver.observations.code.';
COMMENT ON COLUMN ontology.loinc_codes.system IS 'LOINC "system" axis, e.g. Serum, Blood, Urine — not to be confused with a FHIR CodeSystem.';

CREATE INDEX IF NOT EXISTS idx_loinc_codes_component ON ontology.loinc_codes (component);

-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ontology.rxnorm_codes (
    rxcui           VARCHAR(20)     PRIMARY KEY,
    name            VARCHAR(500)    NOT NULL,
    term_type       VARCHAR(10),
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE ontology.rxnorm_codes IS 'RxNorm reference set used to validate silver.medications.rxnorm_code.';
COMMENT ON COLUMN ontology.rxnorm_codes.term_type IS 'RxNorm term type (TTY), e.g. SCD, SBD, IN, BN.';

CREATE INDEX IF NOT EXISTS idx_rxnorm_codes_name ON ontology.rxnorm_codes (name);

-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ontology.clinical_rules (
    id                  BIGSERIAL PRIMARY KEY,
    rule_code           VARCHAR(50)     NOT NULL UNIQUE,
    rule_name           VARCHAR(255)    NOT NULL,
    description         TEXT            NOT NULL,
    rule_category       VARCHAR(30)     NOT NULL
        CONSTRAINT chk_clinical_rules_category
        CHECK (rule_category IN ('CODE_VALIDATION', 'DATA_QUALITY', 'CLINICAL_LOGIC', 'IDENTITY')),
    default_severity    VARCHAR(10)     NOT NULL
        CONSTRAINT chk_clinical_rules_severity
        CHECK (default_severity IN ('ERROR', 'WARNING', 'INFO')),
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);

COMMENT ON TABLE ontology.clinical_rules IS 'Built-in and custom clinical validation rules evaluated by the Rules Engine service against Silver entities.';
COMMENT ON COLUMN ontology.clinical_rules.rule_code IS 'Stable machine-readable identifier referenced by silver.validation_reports.rule_code.';

CREATE TRIGGER trg_clinical_rules_set_updated_at
    BEFORE UPDATE ON ontology.clinical_rules
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE INDEX IF NOT EXISTS idx_clinical_rules_category ON ontology.clinical_rules (rule_category);
CREATE INDEX IF NOT EXISTS idx_clinical_rules_active ON ontology.clinical_rules (is_active) WHERE is_active = TRUE;

-- -----------------------------------------------------------------------------
-- Deferred FK: silver.validation_reports.rule_code -> ontology.clinical_rules
-- (added here because ontology loads after silver in init order; see
-- 03_silver_tables.sql for the column definition).
-- -----------------------------------------------------------------------------
ALTER TABLE silver.validation_reports
    ADD CONSTRAINT fk_validation_reports_rule_code
    FOREIGN KEY (rule_code) REFERENCES ontology.clinical_rules (rule_code);
