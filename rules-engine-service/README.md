# rules-engine-service

**Phase 4** — Evaluates the built-in clinical validation rules (`ontology.clinical_rules`) against
Silver-layer entities, orchestrates ontology-service's code validation alongside them, and writes
findings to `silver.validation_reports`.

## Built-in rules

Seven rule_codes (`docs/db-init/13_seed_rules_engine_rules.sql`), each with a `RuleEvaluator`
implementation in `rule/`:

| rule_code | Checks |
|---|---|
| `GENDER_PREGNANCY_CONFLICT` | Conflicting gender (default: male) + a pregnancy diagnosis |
| `CANCER_STAGING_COMPLETENESS` | ICD-10 neoplasm codes (C00-D49) carry staging information |
| `DIAGNOSIS_DATE_SANITY` | Condition onset date is not in the future |
| `LAB_REFERENCE_RANGE` | Observation numeric value falls within its LOINC reference range |
| `PATIENT_AGE_CONSISTENCY` | Stated age matches age calculated from date of birth |
| `MEDICATION_ALLERGY_CONFLICT` | Prescribed medication doesn't match a known allergy |
| `FHIR_USCORE_COMPLETENESS` | Incoming Patient/Observation/Condition FHIR resources carry required fields |

`FHIR_USCORE_COMPLETENESS` is a lightweight required-field presence check, not full US Core IG
conformance (cardinality, terminology bindings, invariants) — that's fhir-compliance-service's job
in Phase 5.

Each rule's parameters (code lists, ranges, required fields) live in
`ontology.clinical_rules.rule_expression` (JSONB), so tuning a rule doesn't require a code change.
`RuleExecutor` only evaluates active rules it has a registered `RuleEvaluator` for — it silently
skips rule_codes owned by other services (e.g. `CODE-001..004`, evaluated by ontology-service).

## REST endpoints (port 8083)

- `POST /api/rules/evaluate` — runs ontology-service validation (via HTTP) then rule evaluation
  against a canonical patient record, aggregates both into a `ClinicalValidationReport`, persists
  every failure to `silver.validation_reports`. Degrades gracefully to rules-only
  (`ontologyValidationAvailable: false`) if ontology-service is unreachable.
- `GET /api/rules` — list active rules.
- `GET /api/rules/{code}` — single rule details (404 if unknown).
- `PUT /api/rules/{code}/toggle` — flips `is_active` for a rule (404 if unknown).
