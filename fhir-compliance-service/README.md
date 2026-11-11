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
  from packages not bundled (VSAC etc.) are likewise warnings, *except* under a **required**
  binding, where an unexpandable value set is an ERROR. US Core's OMB race/ethnicity value sets
  are exactly that case (pure VSAC includes), so the three small VSAC sets they're built from are
  supplied inline (`config/OmbRaceEthnicityValueSets`) — added in slice 2; before that, *any*
  Patient with a race/ethnicity extension failed. The ~900-code *detailed* race/ethnicity sets
  are not supplied, so a `detailed` sub-extension still errors offline. UCUM *is* checked.
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

## Phase 5b — C-CDA → FHIR R4 (slice 2)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/fhir/convert/ccda` | A C-CDA R2.1 document (`application/xml` or `text/xml`) in; a FHIR R4 `Bundle` (type `collection`) out, plus each resource's US Core 6.1.0 validation outcome from the slice 1 validator. 200 whether or not compliant; 400 malformed XML / DOCTYPE / non-`ClinicalDocument` root / no `recordTarget/patientRole`; 413 over `ccda.max-document-bytes` (default 5 MB); 415 non-XML |

| C-CDA | FHIR (US Core 6.1.0 profile) |
|---|---|
| `recordTarget/patientRole` | `Patient` — identifiers, names, gender, birth date, addresses, telecoms, OMB race/ethnicity extensions, language |
| Problems section (LOINC `11450-4`), Problem Observation in each Concern Act | `Condition` (problems-health-concerns) — `value` CD + `translation`s as codings, onset/abatement, clinicalStatus, `negationInd` → refuted |
| Medications section (`10160-0`), each `substanceAdministration` | `MedicationRequest` — RxNorm code, status, intent `order` (`moodCode=EVN` → `reportedBoolean`), author → `requester.display`, dose/route/frequency/sig, start/stop |
| Results section (`30954-2`), each Result Observation in an organizer | `Observation` (lab) — LOINC code, status, value by `xsi:type` (PQ/INT/ST/CD/BL), interpretation, reference range |

Response shape (summary first, then detail, then the Bundle): `documentId`, `documentTitle`,
`resourceCount`, `compliantCount`, `allCompliant`, `sections` (what was found per section),
`notes` (what the transformer couldn't carry over — distinct from validator findings),
`resources[]` (`fullUrl`, `resourceType`, `resourceId`, `sourceSection`, `validation`), `bundle`.

- **Why hand-written XPath, not a library**: `hapi-fhir-converter` (Maven Central, latest 8.x)
  only converts between FHIR versions (`VersionCanonicalizer`). The `CCDAConverter` inside its
  transitive `org.hl7.fhir.convertors` emits DSTU3, needs a DSTU3 worker context, and doesn't
  handle Problems, Medications or Results at all.
- **Sections located by LOINC code**, with the section templateIds as a fallback. A section
  that's absent is a note, not an error; `nullFlavor="NI"` is reported as "no information".
  Adding Allergies/Encounters later is one more `CcdaSectionMapper`.
- **Maps, never invents** (same rule as slice 1): a missing `statusCode` → no `status` → a US
  Core error. An explicit C-CDA `nullFlavor` is information, not a gap, so it maps to FHIR's own
  "known unknown": gender `unknown`, a name/requester with `data-absent-reason`, an Observation
  `dataAbsentReason`. A timestamp with a time but no timezone is truncated to its date rather
  than given an invented offset.
- **`originalText/reference`** pointers (`#problem1`) are resolved into the section narrative.
- **Deterministic ids**: UUIDv3 of the C-CDA entry's `id` root/extension, so re-converting a
  document — or the same entry in a later document from the same source — yields the same
  resource id. References use the Bundle-internal `urn:uuid:` form.
- **XXE-safe parsing**: any DOCTYPE is refused outright (C-CDA never needs one); external
  entities/DTDs off; secure processing on. The body is read as bytes so the XML prolog's
  encoding is honored.
- **Not persisted**: Gold writes and lineage are slice 3 (GoldWriter). The Result Organizer
  would become a `DiagnosticReport`; out of scope here, so only its effectiveTime is used.

Test documents (synthetic, fictional patients, hand-written — no external samples):
`src/test/resources/ccda/ccd_compliant.xml` (9 resources, all compliant) and
`ccd_noncompliant.xml` (7 resources, 4 deliberate gaps); copies in `scripts/fixtures/` for the
smoke test and inline in Postman items 16–21.

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
