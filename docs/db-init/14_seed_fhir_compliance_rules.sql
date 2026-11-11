-- =============================================================================
-- 14_seed_fhir_compliance_rules.sql
--
-- Phase 5 (fhir-compliance-service) needs 7 new rule_code rows, one per US Core
-- resource type this service checks: USCORE_<TYPE>_COMPLETENESS. Each row's
-- rule_expression names the resourceType it applies to and the flat list of
-- required fields (plus, for MedicationRequest, a requiredAnyOf group for its
-- medication[x] choice-type element) — the same "tune via data, not code"
-- pattern rules-engine-service established in 13_seed_rules_engine_rules.sql.
--
-- This is a distinct, broader check than rules-engine-service's
-- FHIR_USCORE_COMPLETENESS (Patient/Observation/Condition only, a handful of
-- hardcoded fields, explicitly scoped as "a cheap Silver-side sanity check" —
-- see that rule's Javadoc). These 7 rows are the authoritative US Core
-- structural check across all 7 resource types this project generates FHIR
-- for. Both checks can coexist: a caller may run the cheap check inline
-- during rule evaluation and the full check separately via
-- fhir-compliance-service.
--
-- Required-field lists here are simplified for a structural presence check —
-- they approximate US Core's base cardinality/required elements, not a full
-- profile (no terminology bindings, invariants, or Must Support obligations).
-- =============================================================================

INSERT INTO ontology.clinical_rules
    (rule_code, rule_name, description, rule_category, default_severity, rule_expression)
VALUES
    (
        'USCORE_PATIENT_COMPLETENESS',
        'US Core Patient Missing Required Field',
        'A Patient resource must carry US Core''s required elements: identifier, name, gender, birthDate.',
        'DATA_QUALITY',
        'WARNING',
        '{"resourceType": "Patient", "requiredFields": ["id", "identifier", "name", "gender", "birthDate"]}'
    ),
    (
        'USCORE_CONDITION_COMPLETENESS',
        'US Core Condition Missing Required Field',
        'A Condition resource must carry US Core''s required elements: clinicalStatus, code, subject.',
        'DATA_QUALITY',
        'WARNING',
        '{"resourceType": "Condition", "requiredFields": ["id", "clinicalStatus", "code", "subject"]}'
    ),
    (
        'USCORE_OBSERVATION_COMPLETENESS',
        'US Core Observation Missing Required Field',
        'An Observation resource must carry US Core''s required elements: status, code, subject.',
        'DATA_QUALITY',
        'WARNING',
        '{"resourceType": "Observation", "requiredFields": ["id", "status", "code", "subject"]}'
    ),
    (
        'USCORE_MEDICATIONREQUEST_COMPLETENESS',
        'US Core MedicationRequest Missing Required Field',
        'A MedicationRequest resource must carry US Core''s required elements: status, intent, subject, and one of medicationCodeableConcept/medicationReference.',
        'DATA_QUALITY',
        'WARNING',
        '{"resourceType": "MedicationRequest", "requiredFields": ["id", "status", "intent", "subject"], "requiredAnyOf": [["medicationCodeableConcept", "medicationReference"]]}'
    ),
    (
        'USCORE_ALLERGYINTOLERANCE_COMPLETENESS',
        'US Core AllergyIntolerance Missing Required Field',
        'An AllergyIntolerance resource must carry US Core''s required elements: patient, code.',
        'DATA_QUALITY',
        'WARNING',
        '{"resourceType": "AllergyIntolerance", "requiredFields": ["id", "patient", "code"]}'
    ),
    (
        'USCORE_ENCOUNTER_COMPLETENESS',
        'US Core Encounter Missing Required Field',
        'An Encounter resource must carry US Core''s required elements: status, class, subject.',
        'DATA_QUALITY',
        'WARNING',
        '{"resourceType": "Encounter", "requiredFields": ["id", "status", "class", "subject"]}'
    ),
    (
        'USCORE_DIAGNOSTICREPORT_COMPLETENESS',
        'US Core DiagnosticReport Missing Required Field',
        'A DiagnosticReport resource must carry US Core''s required elements: status, code, subject.',
        'DATA_QUALITY',
        'WARNING',
        '{"resourceType": "DiagnosticReport", "requiredFields": ["id", "status", "code", "subject"]}'
    )
ON CONFLICT (rule_code) DO NOTHING;
