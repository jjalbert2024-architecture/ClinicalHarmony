-- =============================================================================
-- 13_seed_rules_engine_rules.sql
--
-- Phase 4 (rules-engine-service) needs two things beyond what Phase 1/3 left
-- in ontology.clinical_rules:
--
-- 1. A `rule_expression` column to hold rule-specific parameters (code lists,
--    numeric ranges, required-field sets) as JSON, so new rule *instances*
--    (e.g. a different reference range, a different pregnancy code list) can
--    be added by inserting a row rather than shipping new Java. Nullable —
--    rules with no tunable parameters (e.g. DIAGNOSIS_DATE_SANITY) leave it
--    NULL and rely purely on their Java evaluator.
-- 2. Seven new rule_code rows: six built-in clinical rules plus the FHIR US
--    Core required-field completeness check. These are a distinct set from
--    the CODE-*/QUAL-001/LOGIC-001/IDENT-001 rows seeded in
--    06_seed_clinical_rules.sql and 12_seed_rules_update.sql — those are
--    evaluated by ontology-service (CODE-*) or not yet evaluated by anything
--    (QUAL-001/LOGIC-001/IDENT-001, still pending a future phase). rules-
--    engine-service's RuleExecutor only dispatches rule_codes it has a
--    registered RuleEvaluator for, so this addition doesn't affect those.
--
-- Note on FHIR_USCORE_COMPLETENESS: this checks only presence of a handful
-- of required fields on Patient/Observation/Condition, as a cheap Silver-
-- side sanity check. It is NOT full US Core IG conformance (cardinality,
-- terminology bindings, invariants, Must Support) — that remains
-- fhir-compliance-service's job in Phase 5.
-- =============================================================================

ALTER TABLE ontology.clinical_rules
    ADD COLUMN IF NOT EXISTS rule_expression JSONB;

COMMENT ON COLUMN ontology.clinical_rules.rule_expression IS 'Rule-specific parameters (code lists, numeric ranges, required fields) as JSON. NULL for rules with no tunable parameters.';

INSERT INTO ontology.clinical_rules
    (rule_code, rule_name, description, rule_category, default_severity, rule_expression)
VALUES
    (
        'GENDER_PREGNANCY_CONFLICT',
        'Gender/Pregnancy Diagnosis Conflict',
        'A patient recorded with a conflicting gender (e.g. male) must not carry a pregnancy diagnosis.',
        'CLINICAL_LOGIC',
        'ERROR',
        '{"conflictingGenders": ["male"], "pregnancyCodes": {"ICD-10": ["Z33.1", "Z34.90", "O09.90"], "SNOMED-CT": ["77386006"]}}'
    ),
    (
        'CANCER_STAGING_COMPLETENESS',
        'Cancer Diagnosis Missing Staging Information',
        'A condition coded in the ICD-10 neoplasm range (C00-D49) must carry staging information.',
        'DATA_QUALITY',
        'WARNING',
        '{"icd10RangeStart": "C00", "icd10RangeEnd": "D49"}'
    ),
    (
        'DIAGNOSIS_DATE_SANITY',
        'Future-Dated Diagnosis',
        'A condition onset or recorded date must not be dated in the future.',
        'DATA_QUALITY',
        'WARNING',
        NULL
    ),
    (
        'LAB_REFERENCE_RANGE',
        'Lab Result Outside Reference Range',
        'An observation''s numeric value must fall within its LOINC reference range.',
        'CLINICAL_LOGIC',
        'WARNING',
        '{"defaultRanges": {
            "2345-7":   {"low": 70,  "high": 99,  "unit": "mg/dL"},
            "4548-4":   {"low": 4.0, "high": 5.6,  "unit": "%"},
            "2160-0":   {"low": 0.6, "high": 1.3,  "unit": "mg/dL"},
            "718-7":    {"low": 12.0,"high": 17.5, "unit": "g/dL"},
            "789-8":    {"low": 4.2, "high": 5.9,  "unit": "M/uL"},
            "4544-3":   {"low": 36,  "high": 52,   "unit": "%"},
            "6690-2":   {"low": 4.5, "high": 11.0, "unit": "K/uL"},
            "777-3":    {"low": 150, "high": 450,  "unit": "K/uL"},
            "2093-3":   {"low": 0,   "high": 200,  "unit": "mg/dL"},
            "2571-8":   {"low": 0,   "high": 150,  "unit": "mg/dL"},
            "2085-9":   {"low": 40,  "high": 200,  "unit": "mg/dL"},
            "13457-7":  {"low": 0,   "high": 100,  "unit": "mg/dL"},
            "1742-6":   {"low": 7,   "high": 56,   "unit": "U/L"},
            "1920-8":   {"low": 10,  "high": 40,   "unit": "U/L"},
            "3094-0":   {"low": 7,   "high": 20,   "unit": "mg/dL"},
            "2951-2":   {"low": 135, "high": 145,  "unit": "mmol/L"},
            "2823-3":   {"low": 3.5, "high": 5.1,  "unit": "mmol/L"},
            "2075-0":   {"low": 96,  "high": 106,  "unit": "mmol/L"},
            "17861-6":  {"low": 8.5, "high": 10.5, "unit": "mg/dL"},
            "3016-3":   {"low": 0.4, "high": 4.0,  "unit": "mIU/L"}
        }}'
    ),
    (
        'PATIENT_AGE_CONSISTENCY',
        'Stated Age Inconsistent With Date of Birth',
        'A patient''s stated age must match the age calculated from their date of birth.',
        'DATA_QUALITY',
        'WARNING',
        '{"toleranceYears": 0}'
    ),
    (
        'MEDICATION_ALLERGY_CONFLICT',
        'Prescribed Medication Matches Known Allergy',
        'A prescribed medication must not match a substance on the patient''s known allergy list (by RxNorm code or substance name).',
        'CLINICAL_LOGIC',
        'ERROR',
        NULL
    ),
    (
        'FHIR_USCORE_COMPLETENESS',
        'FHIR Resource Missing US Core Required Field',
        'An incoming FHIR resource must carry the required fields for its resource type (Patient: id/name/gender/birthDate, Observation: status/code/subject, Condition: clinicalStatus/code/subject).',
        'DATA_QUALITY',
        'WARNING',
        '{"requiredFields": {"Patient": ["id","name","gender","birthDate"], "Observation": ["status","code","subject"], "Condition": ["clinicalStatus","code","subject"]}}'
    )
ON CONFLICT (rule_code) DO NOTHING;
