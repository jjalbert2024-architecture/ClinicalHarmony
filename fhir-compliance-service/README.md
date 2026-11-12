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
- **Not persisted**: conversion output isn't written to Gold (GoldWriter, slice 3, covers the
  Silver, seed and Phase 5 paths). The Result Organizer
  would become a `DiagnosticReport`; out of scope here, so only its effectiveTime is used.

Test documents (synthetic, fictional patients, hand-written — no external samples):
`src/test/resources/ccda/ccd_compliant.xml` (9 resources, all compliant) and
`ccd_noncompliant.xml` (7 resources, 4 deliberate gaps); copies in `scripts/fixtures/` for the
smoke test and inline in Postman items 16–21.

## Phase 5b — GoldWriter + CMS-0057-F payer read API (slice 3)

### GoldWriter — the one writer of the Gold layer

`gold/GoldWriter` owns every write to `gold.master_patients` (+ `patient_source_xref`),
`gold.fhir_resources` and `gold.data_lineage`. All three Gold write paths go through it: Silver →
FHIR builds (slice 1), Phase 5's caller-supplied `/api/fhir-compliance/generate`, and validation of
rows seeded straight into Gold (migration 19). There's no second write path.

- **Provisional master patients (pre-Phase 6).** patient-index-service doesn't exist yet, so each
  `silver.patients` row gets its own master: `match_status = 'PROVISIONAL'` (migration 18),
  `match_confidence_score` NULL ("single-source, unmatched"), `source_patient_count` 1, one
  `patient_source_xref` row (now unique per Silver patient). Rebuilds reuse it, and while it's
  still provisional its demographics follow the Silver row. Phase 6 picks up `WHERE match_status =
  'PROVISIONAL'` and marks survivors `MATCHED`, absorbed masters `MERGED`. Every Gold resource for
  that patient gets `master_patient_id`. That includes resources written *before* the master
  existed, such as the seeded Coverage: building a patient back-links anything referencing
  `Patient/<id>` as subject/patient/beneficiary. Concurrent builds of one patient serialize on a
  `SELECT … FOR UPDATE` of its Silver row.
- **Lineage.** One `gold.data_lineage` row per (Gold row, step), upserted on migration 18's unique
  index, so a rebuild refreshes the row instead of duplicating it. `transformation_details` holds
  the chain:

  ```json
  {"target": "Patient/silver-patient-4",
   "steps": [
     {"stage": "BRONZE", "timestamp": "…", "transformer": "ingestion-service (HL7V2)",
      "input_hash": "<bronze.raw_messages.payload_checksum>", "ref": "bronze.raw_messages/77"},
     {"stage": "SILVER", "timestamp": "…", "transformer": "Bronze → Silver parse (source_system=…)",
      "input_hash": "<same checksum>", "ref": "silver.patients/4"},
     {"stage": "GOLD", "timestamp": "…", "transformer": "fhir-compliance-service PatientResourceBuilder + HL7 validator (<profile>)",
      "input_hash": "<sha256 of the Silver row>", "output_hash": "<sha256 of the resource JSON>",
      "ref": "gold.fhir_resources/912"}]}
  ```

  | `transformation_step` | Source | Chain |
  |---|---|---|
  | `FHIR_MAP` | `silver.<table>/<id>` | BRONZE → SILVER → GOLD, or SILVER → GOLD when the Silver row has no `raw_message_id` |
  | `PMI_PROVISIONAL` | `silver.patients/<id>` → `gold.master_patients` | same, ending in the master |
  | `SEED_LOAD` | `seed/19_seed_cms0057_gold.sql`, no id | SEED → GOLD |
  | `API_GENERATE` | `api/POST /api/fhir-compliance/generate`, no id | API → GOLD |

  **Bronze link "where it exists"**: no Bronze → Silver parser exists yet, so migration 17's seed
  patients have no `raw_message_id` and their chains start at SILVER with a null `input_hash`.
  We don't guess which Bronze message they came from. The integration test inserts a Bronze HL7
  message plus a Silver patient linked to it and asserts the full three-stage chain.
- **Transactions**: GoldWriter's methods join the caller's transaction. A patient's master,
  resources and lineage commit together; a `/generate` Bundle is now one transaction (it used to
  be one per resource).

### CMS-0057-F read endpoints (served from Gold)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/fhir/r4/{type}/{id}` | Read. 200 `application/fhir+json` with the stored resource. Headers: `X-Profile-Compliant` (`true`/`false`/`pending`), `X-Validated-Profile`, `ETag`, `Last-Modified`. 404 OperationOutcome (`not-found`) for an unknown id |
| `GET` | `/fhir/r4/{type}?…` | Basic search → `searchset` Bundle with `total`, `self` link, `fullUrl`s. 400 OperationOutcome (`invalid`) for unknown parameters, modifiers/chaining, malformed values, `_count` > 100 |
| `POST` | `/api/fhir/gold/revalidate` | Validate every Gold row still marked `PENDING_VALIDATION` (also runs on startup) |

`{type}` is one of the five below; any other type → 404 OperationOutcome (`not-supported`).

| Type | Validated against | Search parameters (plus `_id`, `_count`, `_format`) |
|---|---|---|
| `Coverage` | US Core 6.1.0 `us-core-coverage` | `patient`, `beneficiary`, `payor`, `status`, `type` |
| `PractitionerRole` | US Core 6.1.0 `us-core-practitionerrole` | `practitioner`, `organization`, `active`, `role`, `specialty` |
| `MedicationKnowledge` | Da Vinci US Drug Formulary 2.1.0 `usdf-FormularyDrug` | `code`, `doseform`, `status` |
| `MeasureReport` | Da Vinci DEQM 5.0.0 `indv-measurereport-deqm` | `patient`, `subject`, `reporter`, `measure`, `status` |
| `Device` | US Core 6.1.0 `us-core-implantable-device` | `patient`, `type`, `status` |

- **Search semantics** follow FHIR. A repeated parameter means AND and a comma means OR. Tokens
  can be `system|code`, `code` or `system|`. A reference can be `Type/id`, or a bare id where the
  target type is implied (`patient=…`). An unversioned `measure` canonical matches any version.
  Handling is **strict**: an unknown parameter is a 400, not silently ignored, because for a
  payer API an ignored filter means returning more member data than was asked for. Parameters
  compile to JSONB containment (`resource_json @> ?::jsonb`, served by the existing GIN index).
  Names and JSON paths come only from a whitelist (`cms0057/Cms0057SearchParameters`); request
  values are only ever bind parameters.
- **Tomcat** is configured with `relaxed-query-chars: "|"`. Without it, a token search with an
  unencoded `|` gets Tomcat's bare HTML 400 before it ever reaches the controller.
- **Errors are OperationOutcomes** on `/fhir/r4/**` only (`FhirR4ExceptionHandler`, scoped to
  that controller). Elsewhere the service keeps its `{"error","message"}` shape. Unexpected
  failures are a 500 OperationOutcome that says "Internal error" without leaking internals.
- **What's served is exactly what Gold holds**: validation happened at write time, and the stored
  verdict is reported in the headers. A non-compliant resource is served, but flagged.

### The data: synthetic seed (migration 19)

No feed produces these types (no eligibility, provider-directory, formulary or quality-measure
source), so `docs/db-init/19_seed_cms0057_gold.sql` seeds them straight into Gold. The data is
synthetic but the codes are real. It holds 2 Organizations, 2 Practitioners, 2 PractitionerRoles,
2 Coverages (SEED-0001 commercial, SEED-0002 Medicare; SOPT codes), 3 formulary drugs for the seed
patients' meds (RxNorm SCD + SCDG, looked up on NLM RxNav; also added to `ontology.rxnorm_codes`),
1 DEQM individual report (SEED-0001's HbA1c-poor-control eCQM: 7.2% → not in the numerator) and
1 implantable pacemaker (UDI from the FHIR spec's own example).
`cms-practitionerrole-2` is **deliberately non-compliant**: it has no telecom and no endpoint,
which violates US Core `pd-1`.
Rows land as `compliance_notes = {"status":"PENDING_VALIDATION"}`, not with a verdict, because SQL
can't run the validator. `GoldRevalidationService` validates them on startup and writes the real
findings and a `SEED_LOAD` lineage row. The same JSON is in `src/test/resources/cms0057/`, where
`Cms0057ProfileValidationTest` checks it, along with a targeted non-compliant variant per type.

### Validator changes this needed

- **Two more IG packages**, bundled next to US Core: `hl7.fhir.us.davinci-drug-formulary-2.1.0.tgz`
  (972,555 B) and `hl7.fhir.us.davinci-deqm-5.0.0.tgz` (601,471 B). Their dependency trees
  (QI-Core, CRMI, US Core 3.1.1/7.0.0, …) are *not* bundled. Both profiles used here derive
  directly from base R4 and ship snapshots, so they validate without them.
- **HAPI 8.2.0 gap: version-pinned canonicals.** The core MeasureValidator resolves a pinned
  `MeasureReport.measure` (`url|version`) through `fetchResourcesByUrl`, which HAPI's worker context
  doesn't implement. It throws `HAPI-2509`, and DEQM's `deqm-0` *requires* the pin, so no
  conformant DEQM report could be validated, and `/api/fhir/validate` returned a 500 on one.
  `config/VersionedCanonicalFhirInstanceValidator` implements the lookup instead.
- **Formulary RxNorm value sets offline.** `usdf-FormularyDrug` slices `code.coding` by value sets
  defined as RxNorm `TTY` filters (SCD/SBD vs SCDG/SBDG). Without a terminology server every
  coding matches both slices (`Validation_VAL_Profile_MatchMultiple`), so every formulary drug
  failed. `config/RxNormFormularyValueSets` supplies those three value sets under their real
  URLs, evaluated against the RxNorm concepts whose TTY is known locally
  (`terminology/rxnorm-formulary-tty.csv`). This is the same approach slice 2 used for the OMB race
  sets. **Limitation**: an RxNorm code not in that file fails the binding offline.
- **DEQM extension slices.** DEQM's `deqm-3` requires a measure-scoring extension, and once any
  extension is present the validator must resolve every extension slice DEQM declares. Five of
  those live in packages we don't bundle (HL7 Extensions 5.2.0, CRMI, R5 cross-version), which
  raised `SLICING_CANNOT_BE_EVALUATED` ERRORs. Those specific "profile could not be resolved"
  messages are now reported as **WARNING**s, consistent with `setErrorForUnknownProfiles(false)`.
  Any other slicing error stays an error.

### Tests

- Unit: `GoldWriterTest`, `SilverLineageTest`, `GoldRevalidationServiceTest` (real validator),
  `Cms0057SearchParametersTest`, `FhirR4ControllerTest` (MockMvc with the real read service and
  parser), `Cms0057ProfileValidationTest` (all 13 seed resources plus non-compliant variants vs the
  real validator).
- **Integration (first in the project)**: `integration/GoldWriterIntegrationTest`, a
  `@SpringBootTest` over real HTTP against a throwaway `postgres:16-alpine` (Testcontainers
  1.21.4) initialized from the real `docs/db-init` scripts. It covers Bronze HL7 + Silver patient
  → compliant US Core Patient in Gold, plus the provisional master, xref and BRONZE → SILVER →
  GOLD lineage with the Bronze checksum, and checks that rebuilds are idempotent. It also checks
  the seed is validated on startup and served by all five `/fhir/r4` types, and that building
  SEED-0001 back-links its Coverage/MeasureReport. It runs in `mvn test` (about 10 s) and is
  skipped, not failed, when Docker isn't running. Images: `postgres:16-alpine` (same as compose)
  and `testcontainers/ryuk:0.12.0`.

## Endpoints — Phase 5 structural check (port 8084)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/fhir-compliance/validate` | Checks each resource in `fhirResources` against the active `USCORE_*_COMPLETENESS` rule for its `resourceType`; persists a `silver.validation_reports` row per failure |
| `POST` | `/api/fhir-compliance/generate` | Builds FHIR resources from a canonical patient record, persists each to `gold.fhir_resources` through GoldWriter (API → GOLD lineage, one transaction per Bundle), and returns them as a `Bundle` |
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
- **`FhirGenerationService`** persists each generated resource to `gold.fhir_resources`
  (through GoldWriter since slice 3) after cross-checking it against
  `UsCoreValidationService.checkResource()` rather than assuming the generator is correct by
  construction, so `is_us_core_compliant` reflects an actual check. `master_patient_id` stays
  null on this path. The input is a caller-supplied record, not a Silver patient, so there's
  nothing to anchor a master to.
- FHIR resource ids are strings, not the `BIGINT` `silver.validation_reports.entity_id` expects,
  so validation findings are recorded against the caller-supplied `patientId`, with the
  resource's own id captured in the failure message (same convention as rules-engine-service's
  `FHIR_USCORE_COMPLETENESS`).

## Schema additions this service required

`docs/db-init/14_seed_fhir_compliance_rules.sql` adds 7 `USCORE_*_COMPLETENESS` rows to the
existing `ontology.clinical_rules` table (no new columns — `rule_expression` already exists from
Phase 4). No schema changes were needed for `gold.fhir_resources` — it and its unique constraint
on `(resource_type, fhir_id)` already existed from Phase 1.

Phase 5b: `16` adds `gold.fhir_resources.compliance_notes`; `17` seeds Silver; `18` adds
`gold.master_patients.match_status` (`PROVISIONAL`/`MATCHED`/`MERGED`), makes
`gold.data_lineage.source_id` nullable, and adds unique indexes on
`data_lineage (target_schema, target_table, target_id, transformation_step)` and
`patient_source_xref (silver_patient_id)`; `19` seeds the CMS-0057 resources into Gold (+ 6 RxNorm
SCD/SCDG rows into `ontology.rxnorm_codes`). All are idempotent and were applied to the running
container without `down -v`.
