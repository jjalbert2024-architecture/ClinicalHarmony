# fhir-compliance-service

**Phase 5** — Checks FHIR resources for US Core structural completeness (required-field
presence, not full profile conformance) and generates FHIR resources from a canonical patient
record, persisting them to `gold.fhir_resources`.

## Scope

Covers all 7 US Core resource types this project produces FHIR for: `Patient`, `Condition`,
`Observation`, `MedicationRequest`, `AllergyIntolerance`, `Encounter`, `DiagnosticReport`.

This checks required-element **presence** only — no cardinality beyond that, no terminology
binding validation, no FHIRPath invariants, and no HAPI FHIR object model (resources are built
and checked as plain JSON maps, consistent with how ingestion-service and rules-engine-service
already treat FHIR resources). It's broader (7 resource types vs. 3) but not deeper than
rules-engine-service's `FHIR_USCORE_COMPLETENESS` rule, which was explicitly scoped as a cheap
Silver-side sanity check pending this service. The two can coexist; this is the authoritative one.

## Endpoints (port 8084)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/fhir-compliance/validate` | Checks each resource in `fhirResources` against the active `USCORE_*_COMPLETENESS` rule for its `resourceType`; persists a `silver.validation_reports` row per failure |
| `POST` | `/api/fhir-compliance/generate` | Builds FHIR resources from a canonical patient record, persists each to `gold.fhir_resources`, and returns them as a `Bundle` |
| `GET` | `/api/fhir-compliance/rules` | List active `USCORE_*` rules |
| `GET` | `/api/fhir-compliance/rules/{code}` | Single rule details (404 if unknown) |
| `PUT` | `/api/fhir-compliance/rules/{code}/toggle` | Flips `is_active` for a rule |

`POST /api/fhir-compliance/validate` request shape — same `patientId` + `fhirResources` shape as
rules-engine-service's `PatientRecordRequest.fhirResources()`, so the same payload can be sent to
either service's FHIR check:

```json
{
  "patientId": 777,
  "fhirResources": [
    {"resourceType": "Patient", "id": "p1", "gender": "male"}
  ]
}
```

A resource whose `resourceType` has no active `USCORE_*` rule (unsupported type, or toggled off)
is skipped, not failed — same convention as rules-engine-service's `RuleExecutor`.

`POST /api/fhir-compliance/generate` request shape — see `web/dto/PatientRecordRequest.java` for
the full set of optional item lists (conditions, observations, medications, allergies,
encounters, diagnosticReports).

## Design notes

- **`UsCoreRuleLoader`** loads this service's `USCORE_*` rows from `ontology.clinical_rules`
  fresh on every call (no caching — matches rules-engine-service's `RuleLoader`) and parses
  `rule_expression` into a `UsCoreProfile` (resourceType, requiredFields, requiredAnyOf).
- **`UsCoreValidationService`** matches each submitted resource to its profile by
  `resourceType` and checks field presence, including `requiredAnyOf` groups for FHIR
  choice-type elements (e.g. MedicationRequest's `medication[x]`).
- **`FhirBundleGenerator`** builds resources as plain ordered maps using real canonical
  terminology system URIs (`http://snomed.info/sct`, `http://loinc.org`, etc.).
- **`FhirGenerationService`** persists each generated resource to `gold.fhir_resources` —
  a table Phase 1 already created for this purpose but that no service had written to until now
  — after cross-checking it against `UsCoreValidationService.checkResource()` rather than
  assuming the generator is correct by construction, so `is_us_core_compliant` reflects an
  actual check. `master_patient_id` is left null until patient-index-service (Phase 6) exists to
  populate `gold.master_patients`; the column is nullable for exactly this reason.
- FHIR resource ids are strings, not the `BIGINT` `silver.validation_reports.entity_id` expects,
  so validation findings are recorded against the caller-supplied `patientId`, with the
  resource's own id captured in the failure message (same convention as rules-engine-service's
  `FHIR_USCORE_COMPLETENESS`).

## Schema additions this service required

`docs/db-init/14_seed_fhir_compliance_rules.sql` adds 7 `USCORE_*_COMPLETENESS` rows to the
existing `ontology.clinical_rules` table (no new columns — `rule_expression` already exists from
Phase 4). No schema changes were needed for `gold.fhir_resources` — it and its unique constraint
on `(resource_type, fhir_id)` already existed from Phase 1.
