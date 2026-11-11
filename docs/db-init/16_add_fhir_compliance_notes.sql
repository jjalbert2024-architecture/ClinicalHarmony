-- =============================================================================
-- 16_add_fhir_compliance_notes.sql
--
-- Phase 5b (fhir-compliance-service, slice 1): FHIR resources built from
-- Silver rows are validated with the official HL7 FHIR validator against
-- US Core 6.1.0. is_us_core_compliant already records the verdict; this
-- column records *why* — the validator's findings — so a non-compliant
-- resource in Gold carries its own explanation instead of just a false flag.
--
-- Shape (written by SilverFhirBuildService):
--   { "validator": "...", "profile": "<US Core profile URL>",
--     "validatedAt": "<ISO-8601>", "compliant": bool,
--     "errorCount": n, "warningCount": n,
--     "violations": [ { "severity", "location", "message", "rule", "suggestedFix" } ] }
--
-- NULL for rows written by the Phase 5 structural-check path
-- (/api/fhir-compliance/generate), which produces no such findings.
--
-- Non-destructive and idempotent: safe to apply to the running container.
-- =============================================================================

ALTER TABLE gold.fhir_resources
    ADD COLUMN IF NOT EXISTS compliance_notes JSONB;

COMMENT ON COLUMN gold.fhir_resources.compliance_notes IS
    'HL7 FHIR validator findings (US Core 6.1.0) for this resource: profile, counts, and each violation with severity, FHIRPath location, message, rule and suggested fix. NULL when written by the Phase 5 structural-check path.';
