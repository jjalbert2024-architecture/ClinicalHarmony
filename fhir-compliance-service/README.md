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

## Phase 5b — HL7 validator against US Core 6.1.0 (slice 1)

Alongside the Phase 5 structural check (unchanged, below), this service now runs the **official
HL7 FHIR validator** (HAPI FHIR 8.2.0's `FhirInstanceValidator`) against the **US Core 6.1.0** IG
package, bundled at `src/main/resources/packages/hl7.fhir.us.core-6.1.0.tgz` and loaded from the
classpath — no network access at runtime. This is full profile conformance: cardinality,
fixed values/slices, FHIRPath invariants (e.g. `us-core-21`), datatype rules and UCUM units.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/fhir/validate` | Debugger: one FHIR R4 resource in (`application/json` or `application/fhir+json`), every violation out — `severity`, FHIRPath `location`, `message`, `rule`, `suggestedFix`. 200 even when non-compliant; 400 only for non-FHIR payloads |
| `POST` | `/api/fhir/build/patients/{silverPatientId}` | Build US Core Patient/Condition/Observation/MedicationRequest from one `silver.patients` row + its children, validate each, upsert to `gold.fhir_resources` with `compliance_notes` (404 if unknown id) |
| `POST` | `/api/fhir/build` | Same, for every Silver patient (one transaction per patient) |

- **Profile selection**: `meta.profile` if present; otherwise the US Core default for the
  resourceType (`Observation` → `us-core-observation-lab` when categorized `laboratory`, else
  `us-core-observation-clinical-result`). Without this, a bare resource would only be checked
  against base FHIR, where nearly everything is optional.
- **Validates raw JSON**, not a HAPI-parsed object — HAPI's lenient parser silently drops empty
  strings and unknown elements, which are exactly what a debugger needs to report.
- **Terminology**: no terminology server, so LOINC/SNOMED/RxNorm/ICD-10 code existence is a
  WARNING (`Terminology_PassThrough_TX_Message`) — ontology-service owns that check. Value sets
  from packages not bundled (VSAC etc.) are likewise warnings. UCUM *is* checked.
- **Validation records, it doesn't gate**: a non-compliant resource still lands in Gold with
  `is_us_core_compliant = false` and the reasons in `compliance_notes` (migration 16).
- **Builders map, never invent**: a blank Silver name → no `Patient.name` → a US Core error.
  The one exception is US Core's sanctioned pattern for required-but-unknown data: Silver has no
  prescriber, so `MedicationRequest.requester` carries a `data-absent-reason` (`unknown`)
  extension to satisfy `us-core-21`.
- **Deterministic ids** (`silver-patient-<id>`, `silver-condition-<id>`, ...) so rebuilds upsert.
- **Warm-up**: the validator's ~2 s first-use cost is paid at startup (`FhirValidatorWarmup`).
- **Correlation ids**: an incoming `X-Correlation-Id` header (safe charset, ≤64 chars) is reused
  in the logs and echoed in the response; otherwise one is generated.
- **pom.xml**: Spring Boot's BOM is imported *before* `hapi-fhir-bom`, otherwise HAPI's BOM
  silently downgrades Spring Framework to 6.1.x.

Demo data: `docs/db-init/17_seed_silver_clinical_data.sql` (3 Silver patients; `SEED-0003` is
deliberately non-compliant — blank name, blank condition code).

## Endpoints — Phase 5 structural check (port 8084)

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
