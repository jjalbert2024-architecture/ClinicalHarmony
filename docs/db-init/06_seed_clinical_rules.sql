-- =============================================================================
-- 06_seed_clinical_rules.sql
--
-- Seeds the six built-in clinical validation rules evaluated by the Rules
-- Engine service. Additional rules can be added at runtime without a schema
-- change — this file only establishes the platform defaults.
-- =============================================================================

INSERT INTO ontology.clinical_rules
    (rule_code, rule_name, description, rule_category, default_severity)
VALUES
    (
        'CODE-001',
        'Invalid ICD-10 Code',
        'Condition.code must reference a valid, known code in the ontology.icd10_codes reference set.',
        'CODE_VALIDATION',
        'ERROR'
    ),
    (
        'CODE-002',
        'Invalid LOINC Code',
        'Observation.code must reference a valid, known code in the ontology.loinc_codes reference set.',
        'CODE_VALIDATION',
        'ERROR'
    ),
    (
        'CODE-003',
        'Invalid RxNorm Code',
        'Medication.rxnormCode must reference a valid, known code in the ontology.rxnorm_codes reference set.',
        'CODE_VALIDATION',
        'ERROR'
    ),
    (
        'QUAL-001',
        'Future-Dated Clinical Event',
        'A clinical event (condition onset, observation effective date, medication start date) must not be dated in the future.',
        'DATA_QUALITY',
        'WARNING'
    ),
    (
        'LOGIC-001',
        'Clinical Event Precedes Date of Birth',
        'A clinical event date must not precede the associated patient''s date of birth.',
        'CLINICAL_LOGIC',
        'ERROR'
    ),
    (
        'IDENT-001',
        'Duplicate MRN Within Source System',
        'A medical record number (MRN) must be unique within a single source system; duplicates indicate a likely registration or interface error.',
        'IDENTITY',
        'WARNING'
    )
ON CONFLICT (rule_code) DO NOTHING;
