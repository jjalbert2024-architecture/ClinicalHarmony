-- =============================================================================
-- 12_seed_rules_update.sql
--
-- Two changes needed for ontology-service (Phase 3):
--
-- 1. silver.validation_reports was designed in Phase 1 before any validator
--    existed, so it only captured a free-text `message`. Phase 3's
--    ValidationReportBuilder needs to report the offending value, the
--    patient it belongs to, and (when known) a suggested correction as
--    their own queryable columns, not buried in text — the Validation
--    Report dashboard view (Phase 8) will want to filter/sort on these
--    directly.
-- 2. CODE-004 (invalid SNOMED CT code) is added to ontology.clinical_rules,
--    alongside the 6 rules already seeded in 06_seed_clinical_rules.sql —
--    needed because silver.validation_reports.rule_code has a hard foreign
--    key to ontology.clinical_rules, so SnomedValidator failures need a
--    rule row to reference.
-- =============================================================================

ALTER TABLE silver.validation_reports
    ADD COLUMN IF NOT EXISTS patient_id      BIGINT,
    ADD COLUMN IF NOT EXISTS bad_value       VARCHAR(500),
    ADD COLUMN IF NOT EXISTS suggested_value VARCHAR(500);

COMMENT ON COLUMN silver.validation_reports.patient_id IS 'Which patient this finding belongs to — distinct from entity_id, which identifies the specific condition/observation/medication that failed.';
COMMENT ON COLUMN silver.validation_reports.bad_value IS 'The offending code/value that failed validation.';
COMMENT ON COLUMN silver.validation_reports.suggested_value IS 'A suggested correction, when the validator can determine one (NULL otherwise — no fuzzy-matching is implemented yet).';

CREATE INDEX IF NOT EXISTS idx_validation_reports_patient_id ON silver.validation_reports (patient_id);

INSERT INTO ontology.clinical_rules
    (rule_code, rule_name, description, rule_category, default_severity)
VALUES
    (
        'CODE-004',
        'Invalid SNOMED CT Code',
        'Condition.code (when coded as SNOMED-CT) must reference a valid, known concept in the ontology.snomed_codes reference set.',
        'CODE_VALIDATION',
        'ERROR'
    )
ON CONFLICT (rule_code) DO NOTHING;
