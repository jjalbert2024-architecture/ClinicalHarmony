-- =============================================================================
-- 15_extend_validation_reports_entity_type.sql
--
-- Phase 5 (fhir-compliance-service) validates raw FHIR resources directly, so
-- its findings need entity_type values matching real FHIR resourceType names
-- (uppercased) for the 4 resource types earlier phases never touched:
-- MedicationRequest, AllergyIntolerance, Encounter, DiagnosticReport.
-- PATIENT/CONDITION/OBSERVATION were already covered; MEDICATION (the
-- canonical Silver-entity name ontology-service/rules-engine-service use,
-- not the FHIR resource type) is kept as-is rather than reused for
-- MedicationRequest, since the two mean different things — one is a
-- canonical medication item, the other a specific FHIR resource being
-- structurally validated.
-- =============================================================================

ALTER TABLE silver.validation_reports
    DROP CONSTRAINT chk_validation_reports_entity_type;

ALTER TABLE silver.validation_reports
    ADD CONSTRAINT chk_validation_reports_entity_type
    CHECK (entity_type IN (
        'PATIENT', 'CONDITION', 'OBSERVATION', 'MEDICATION', 'RAW_MESSAGE',
        'MEDICATIONREQUEST', 'ALLERGYINTOLERANCE', 'ENCOUNTER', 'DIAGNOSTICREPORT'
    ));
