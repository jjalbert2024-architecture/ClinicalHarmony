-- =============================================================================
-- 01_create_schemas.sql
--
-- ClinicalHarmony schema layout follows a Bronze/Silver/Gold medallion
-- pattern, plus a dedicated Ontology schema for reference terminology
-- (ICD-10, LOINC, RxNorm) and rule definitions used by the validation engine.
--
--   bronze   - raw, unmodified payloads as received from source systems
--   silver   - parsed/typed clinical entities, one row per fact, pre-dedup
--   gold     - conformed, deduplicated, FHIR-compliant entities for
--              downstream consumption (dashboard, API gateway)
--   ontology - reference code systems and clinical validation rules
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS bronze;
CREATE SCHEMA IF NOT EXISTS silver;
CREATE SCHEMA IF NOT EXISTS gold;
CREATE SCHEMA IF NOT EXISTS ontology;

COMMENT ON SCHEMA bronze IS 'Raw, immutable ingestion payloads (HL7 v2, FHIR JSON, Claims CSV) prior to any parsing.';
COMMENT ON SCHEMA silver IS 'Parsed and typed clinical entities, pre-deduplication, one row per source fact.';
COMMENT ON SCHEMA gold IS 'Conformed, deduplicated, US Core FHIR-compliant entities for downstream consumption.';
COMMENT ON SCHEMA ontology IS 'Reference terminology (ICD-10, LOINC, RxNorm) and clinical validation rule definitions.';

-- -----------------------------------------------------------------------------
-- Shared utility: generic trigger to maintain an `updated_at` timestamp column.
-- Reused by every table across schemas that tracks row modification time.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION public.set_updated_at() IS 'Trigger function: stamps NEW.updated_at with the current timestamp on UPDATE.';
