# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test commands

All Spring Boot services use Maven. From a service directory (e.g. `ingestion-service/`):

```bash
# Build (skip tests)
mvn -q package -DskipTests

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=Hl7MessageProcessorTest

# Run a single test method
mvn test -Dtest=Hl7MessageProcessorTest#parseValidAdtMessage

# Run locally (requires Postgres on localhost:5432)
mvn spring-boot:run
```

Full stack via Docker Compose (from repo root):

```bash
docker-compose up          # start everything
docker-compose down -v     # tear down and wipe the database
```

The Postgres container auto-runs `docs/db-init/` scripts in lexical order on first start. DB credentials are `clinicalharmony / clinicalharmony / clinicalharmony`.

### Key library versions (ingestion-service; match when adding new services)

| Library | Version |
|---|---|
| Spring Boot | 3.5.16 |
| Apache Camel | 4.4.1 |
| HAPI HL7 v2 | 2.3 (`hapi-structures-v25`) |
| Apache Commons CSV | 1.10.0 |
| Java | 17 |
| Testcontainers (test scope, Boot-managed) | 1.21.4 |

New services should use the same Spring Boot parent version and align Camel via its BOM (`camel-spring-boot-bom`) to avoid dependency conflicts.

**Spring Boot version note (2026-07-03)**: bumped from 3.2.5 (EOL Dec 2024) to 3.5.16 — the last patch ever released for the whole Spring Boot 3.x line, which itself reached EOL June 30, 2026. The actively-supported version is now Spring Boot 4.1.x, but that's a real migration (Jakarta EE 11, Spring Framework 7, removed deprecated APIs) with unverified Camel/HAPI HL7v2 compatibility — deliberately deferred rather than attempted opportunistically. Revisit as its own dedicated task if currency with the ecosystem matters more than migration risk.

### Environment variables (ingestion-service)

All have sane defaults for local development:

| Variable | Default | Purpose |
|---|---|---|
| `DB_HOST` | `localhost` | Postgres host |
| `DB_PORT` | `5432` | Postgres port |
| `DB_NAME` | `clinicalharmony` | Database name |
| `DB_USER` | `clinicalharmony` | DB user |
| `DB_PASSWORD` | `clinicalharmony` | DB password |
| `HL7_MLLP_PORT` | `8887` | MLLP listener port |
| `HL7_DEFAULT_SOURCE_SYSTEM` | `UNKNOWN_HL7_SOURCE` | Fallback source tag |
| `FHIR_DEFAULT_SOURCE_SYSTEM` | `UNKNOWN_FHIR_SOURCE` | Fallback source tag |
| `CLAIMS_DEFAULT_SOURCE_SYSTEM` | `UNKNOWN_CLAIMS_SOURCE` | Fallback source tag |

Future services follow the same pattern: `DB_*` env vars for the datasource, a `default-source-system` config key per intake path.

### Environment variables (ontology-service)

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | `local` (datasource hardcoded to `localhost:5432`, for `mvn spring-boot:run`) or `docker` (datasource via `DB_*` env vars, set by docker-compose) |
| `DB_HOST` | `postgres` (docker profile only) | Postgres host |
| `DB_PORT` | `5432` | Postgres port |
| `DB_NAME` | `clinicalharmony` | Database name |
| `DB_USER` | `clinicalharmony` | DB user |
| `DB_PASSWORD` | `clinicalharmony` | DB password |

### Environment variables (rules-engine-service)

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | Same `local`/`docker` pattern as ontology-service |
| `DB_HOST` | `postgres` (docker profile only) | Postgres host |
| `DB_PORT` | `5432` | Postgres port |
| `DB_NAME` | `clinicalharmony` | Database name |
| `DB_USER` | `clinicalharmony` | DB user |
| `DB_PASSWORD` | `clinicalharmony` | DB password |
| `ONTOLOGY_SERVICE_HOST` | `ontology-service` (docker profile only) | Host for the ontology-service HTTP call made by `OntologyServiceClient` |
| `ONTOLOGY_SERVICE_PORT` | `8082` | Port for the same call |

### Environment variables (patient-index-service)

Same `SPRING_PROFILES_ACTIVE` + `DB_*` + inbound `OAUTH_ISSUER_URI` / `OAUTH_JWK_SET_URI` pattern as
fhir-compliance-service, plus its outbound GoldWriter client:

| Variable | Default | Purpose |
|---|---|---|
| `GOLD_WRITER_BASE_URL` | `http://localhost:8084` | fhir-compliance-service (compose: `http://fhir-compliance-service:8084`) |
| `OAUTH_TOKEN_URI` | `http://localhost:8180/realms/clinicalharmony/protocol/openid-connect/token` | Its own client_credentials token (compose: `keycloak:8080`) |
| `PMI_CLIENT_ID` / `PMI_CLIENT_SECRET` | `patient-index-service` / `patient-index-service-secret` | Its Keycloak client (DEV-only secret, in the realm JSON) |

### Environment variables (api-gateway)

Same `SPRING_PROFILES_ACTIVE` + `DB_*` + inbound `OAUTH_ISSUER_URI` / `OAUTH_JWK_SET_URI` pattern, but
`DB_USER`/`DB_PASSWORD` default to the read-only role `clinicalharmony_gateway_ro` / `gateway-ro-dev-password`
(migration 22), and inbound tokens are USER tokens with audience `clinicalharmony-gateway`. Plus:

| Variable | Default | Purpose |
|---|---|---|
| `OAUTH_TOKEN_URI` | `http://localhost:8180/realms/clinicalharmony/protocol/openid-connect/token` | Its own client_credentials token |
| `GATEWAY_CLIENT_ID` / `GATEWAY_CLIENT_SECRET` | `api-gateway` / `api-gateway-secret` | Its Keycloak client (DEV-only secret, in the realm JSON) |
| `DASHBOARD_CLIENT_ID` | `clinicalharmony-dashboard` | Public PKCE client, served by `GET /api/v1/config` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated allowlist |
| `INGESTION_BASE_URL` … `PATIENT_INDEX_BASE_URL` | `http://localhost:8081` … `:8085` | Downstream services (compose: service names) |

### Environment variables (dashboard-ui)

nginx container; both are URLs **as the browser sees them** (not compose service names), rendered at
container start by `docker/40-runtime-config.sh` into `/config.js` and the CSP `connect-src`:

| Variable | Default | Purpose |
|---|---|---|
| `GATEWAY_URL` | `http://localhost:8080` | The only runtime setting the SPA reads; everything about Keycloak comes from `GET /api/v1/config` |
| `KEYCLOAK_URL` | `http://localhost:8180` | CSP `connect-src` only (code exchange + refresh go to Keycloak's token endpoint) |

### Environment variables (fhir-compliance-service)

Same `SPRING_PROFILES_ACTIVE` + `DB_*` pattern as ontology-service, plus (Phase 5b slice 4):

| Variable | Default | Purpose |
|---|---|---|
| `OAUTH_ISSUER_URI` | `http://localhost:8180/realms/clinicalharmony` | Required `iss` of Bearer JWTs; also the base for SMART discovery URLs |
| `OAUTH_JWK_SET_URI` | `http://localhost:8180/realms/clinicalharmony/protocol/openid-connect/certs` | Where signing keys are fetched (compose: `http://keycloak:8080/...`) |

## Architecture

ClinicalHarmony is a Bronze → Silver → Gold medallion pipeline for healthcare data. Each layer is a PostgreSQL schema; each service is a standalone Spring Boot app orchestrated by Docker Compose.

### Data flow

```
Sources (HL7 v2 · FHIR JSON · Claims CSV)
        │
        ▼
ingestion-service  →  bronze.raw_messages  (every payload, valid or not)
        │
        ▼
ontology-service   →  silver.* validated against ICD-10/SNOMED/LOINC/RxNorm
        │
        ▼
rules-engine-service  →  clinical rules evaluated on Silver entities
        │
        ▼
fhir-compliance-service  →  US Core IG conformance + FHIR resource generation
        │
        ▼
patient-index-service  →  gold.master_patients  (cross-source deduplication)
        │
        ▼
api-gateway  →  dashboard-ui (React, 5 views)
```

### Service ports

| Service | REST port | Notes |
|---|---|---|
| ingestion-service | 8081 | + MLLP on 8887 |
| ontology-service | 8082 | implemented |
| fhir-compliance-service | 8084 | implemented |
| patient-index-service | 8085 | implemented (Phase 6) |
| rules-engine-service | 8083 | implemented |
| api-gateway | 8080 | implemented (Phase 7) |
| keycloak | 8180 | realm `clinicalharmony` (Phase 5b slice 4); container port 8080 |
| dashboard-ui | 3000 | implemented (Phase 8); nginx, static SPA |

Services still showing `[placeholder]` in docker-compose.yml boot a bare `eclipse-temurin:17-jre` image and idle. When a service is implemented, replace its `image`/`command` with a `build:` block pointing to that service's `Dockerfile`.

### ingestion-service (implemented — Phase 2b)

Three parallel intake paths, all sharing a single `RawMessageRepository` write to `bronze.raw_messages`:

- **HL7 v2 MLLP** — Apache Camel `mllp://` route (`Hl7MllpRoute`) on port 8887; returns a real ACK/NAK over the same TCP connection.
- **HL7 v2 REST** — `POST /api/ingestion/hl7` (`Hl7IngestionController`), `Content-Type: text/plain`. Normalizes `\n`/`\r\n` → `\r` before parsing (MLLP senders always use `\r`; REST clients often don't).
- **FHIR JSON REST** — `POST /api/ingestion/fhir` (`FhirIngestionController`), `Content-Type: application/json`. Syntactic check only (`resourceType` field present); US Core profile conformance is `fhir-compliance-service`'s job.
- **Claims CSV REST** — `POST /api/ingestion/claims` (`ClaimsIngestionController`), `Content-Type: text/plain` or `text/csv`. Lands **one Bronze row per claim line** (header + that line) so downstream Silver parsing is self-contained. Ingestion is all-or-nothing per file — a bad row quarantines the whole file.

All paths: SHA-256 checksum on the raw payload, quarantine (not drop) on parse failure, 201/422 on REST.

`ClaimsMessageProcessor.process()` is `@Transactional`: a DB failure during the write phase rolls back all rows for that file. Parse failures quarantine the whole file as a single row (intentional — a partial claim set is worse for downstream reconciliation than a rejected one).

### ontology-service (implemented — Phase 3)

Validates diagnosis (ICD-10, SNOMED CT), lab (LOINC), and medication (RxNorm) codes, and
answers cross-terminology mapping queries.

- **4 validators** (`Icd10Validator`, `LoincValidator`, `RxNormValidator`, `SnomedValidator`), each
  a plain `JdbcTemplate` lookup against its `ontology.*` table, returning a shared `ValidationOutcome`
  (`valid`, `display`, `category`, `reason`). Null/blank codes rejected before any DB call.
- **`OntologyService`** — orchestrator. Dispatches each condition to `Icd10Validator` or
  `SnomedValidator` based on its `codeSystem` ("ICD-10" or "SNOMED-CT"), observations always to
  `LoincValidator`, medications always to `RxNormValidator`.
- **`ValidationReportBuilder`** — persists a `silver.validation_reports` row only for *failed*
  validations, citing the rule violated (`CODE-001` ICD-10, `CODE-002` LOINC, `CODE-003` RxNorm,
  `CODE-004` SNOMED CT).
- **`ConceptMappingService`** — cross-terminology lookups via `ontology.concept_mappings`, with a
  small alias table so `ICD10` and `ICD-10` are both accepted as the `target`/`source` query param.
- **REST endpoints** (port 8082): `POST /api/ontology/validate` (canonical patient record in,
  aggregated result + created report ids out), `GET /api/ontology/codes/icd10/{code}` and
  `/codes/loinc/{code}` (200/404 single-code lookups), `GET /api/ontology/map?source=&code=&target=`.
- **Spring profiles**: `local` vs `docker` (see env var table above) — this is the first service to
  use real named profiles rather than just env-var defaults; carry the pattern into later phases.
- **MDC logging**: `MdcLoggingFilter` stamps a short correlation id into every request's log lines.
- No canonical Silver-layer patient parser exists yet (no phase has built HL7/FHIR/CSV → `silver.patients`
  parsing), so `PatientRecordRequest` accepts caller-assigned ids per condition/observation/medication
  item — these become `entity_id` in any resulting validation report — rather than looking up real
  persisted Silver rows.

### rules-engine-service (implemented — Phase 4)

Evaluates clinical rules against Silver entities and orchestrates ontology-service's code
validation alongside them.

- **7 built-in rules**, each a `RuleEvaluator` in `rule/`: `GENDER_PREGNANCY_CONFLICT`,
  `CANCER_STAGING_COMPLETENESS`, `DIAGNOSIS_DATE_SANITY`, `LAB_REFERENCE_RANGE`,
  `PATIENT_AGE_CONSISTENCY`, `MEDICATION_ALLERGY_CONFLICT`, `FHIR_USCORE_COMPLETENESS`. Each
  rule's tunable parameters (code lists, ranges, required fields) live in
  `ontology.clinical_rules.rule_expression` (JSONB) rather than in Java, parsed by `RuleLoader`
  into a Jackson `JsonNode` on every load (no caching — a `PUT .../toggle` takes effect
  immediately).
- **`RuleExecutor`** — dispatches each *active* clinical_rules row to the `RuleEvaluator` bean
  whose `ruleCode()` matches, and silently skips active rules with no registered evaluator (e.g.
  `CODE-001..004`, which ontology-service evaluates; `QUAL-001`/`LOGIC-001`/`IDENT-001`, seeded in
  Phase 1 but not yet evaluated by anything).
- Convention followed by every evaluator: one `RuleResult` per applicable entity item (pass or
  fail); if a rule finds nothing applicable in the request (e.g. no conditions in the ICD-10
  neoplasm range), it returns a single pass at `PATIENT` level rather than an empty list, so the
  API response always shows the rule as evaluated.
- **`OntologyServiceClient`** — calls ontology-service's `POST /api/ontology/validate` over HTTP
  (Spring's `RestClient`, base URL from `ontology-service.base-url`). Returns the raw JSON
  response as a `JsonNode` rather than a typed mirror, since the two services share no code.
- **`RulesOrchestrationService`** — runs ontology validation then rule evaluation, aggregates both
  into a `ClinicalValidationReport`, persists rule failures via `RuleFindingBuilder`. If
  ontology-service is unreachable it degrades to rules-only rather than failing the request
  (`ontologyValidationAvailable: false` on the response) — same fail-open posture as any
  cross-service call in a pipeline where downstream stages shouldn't block on an upstream
  read-only check being briefly down.
- **REST endpoints** (port 8083): `POST /api/rules/evaluate`, `GET /api/rules` (active rules),
  `GET /api/rules/{code}`, `PUT /api/rules/{code}/toggle` (flips `is_active`).
- `PatientRecordRequest` here is a superset of ontology-service's — same caller-assigned-id
  convention (no Silver-layer parser exists yet), but richer: patient gender/DOB/statedAge,
  condition staging info, observation reference ranges, allergies, and raw FHIR resource maps,
  since the rules need clinical context that pure code validation doesn't.

### fhir-compliance-service (implemented — Phase 5)

Checks FHIR resources for US Core **structural** completeness (required-element presence only —
no cardinality beyond that, no terminology bindings, no invariants) across all 7 resource types
this project generates FHIR for, and generates FHIR resources from a canonical patient record.

- **7 `USCORE_*_COMPLETENESS` rules** (`Patient`, `Condition`, `Observation`, `MedicationRequest`,
  `AllergyIntolerance`, `Encounter`, `DiagnosticReport`), seeded in
  `docs/db-init/14_seed_fhir_compliance_rules.sql` — a distinct, broader rule family from
  rules-engine-service's `FHIR_USCORE_COMPLETENESS` (3 resource types, explicitly scoped there as
  "a cheap Silver-side sanity check" pending this service). Both can coexist; this is the
  authoritative one.
- **`UsCoreRuleLoader`** — same fresh-load-every-call, no-caching convention as rules-engine-service's
  `RuleLoader`, parsing `rule_expression` into a `UsCoreProfile` (resourceType, requiredFields, and
  `requiredAnyOf` groups for FHIR choice-type elements like MedicationRequest's `medication[x]`).
- **`UsCoreValidationService`** — matches each submitted resource to its profile by `resourceType`;
  a type with no active profile is skipped, not failed (same convention as `RuleExecutor`).
  `checkResource()` is reused by the generator to cross-check its own output rather than assuming
  the generator is correct by construction.
- **`FhirBundleGenerator`** — builds resources as plain ordered maps (no HAPI FHIR object model,
  consistent with how ingestion-service/rules-engine-service already treat FHIR resources) using
  real canonical terminology system URIs (`http://snomed.info/sct`, `http://loinc.org`, etc.).
- **`FhirGenerationService`** — persists each generated resource to `gold.fhir_resources`, a table
  Phase 1 created for exactly this purpose but that no service had written to until now, making
  this the first service to touch the Gold layer. `master_patient_id` stays null until
  patient-index-service (Phase 6) populates `gold.master_patients` — the column is nullable for
  exactly this reason.
- **REST endpoints** (port 8084): `POST /api/fhir-compliance/validate`, `POST
  /api/fhir-compliance/generate`, `GET /api/fhir-compliance/rules`, `GET
  /api/fhir-compliance/rules/{code}`, `PUT /api/fhir-compliance/rules/{code}/toggle`.
- FHIR resource ids are strings, not the `BIGINT` `silver.validation_reports.entity_id` expects,
  so validation findings are recorded against the caller-supplied `patientId` (same convention as
  rules-engine-service's `FHIR_USCORE_COMPLETENESS`).

### fhir-compliance-service — Phase 5b slice 1 (HL7 validator)

- **Official HL7 validator vs US Core 6.1.0** (`validation/` package): HAPI FHIR 8.2.0
  `FhirInstanceValidator` in a `ValidationSupportChain` (NpmPackage US Core → DefaultProfile →
  CommonCodeSystems → InMemoryTerminology → SnapshotGenerating → UnknownCodeSystemWarning), US Core
  package bundled at `src/main/resources/packages/` (no network at runtime). Validates the *raw
  JSON* (HAPI's parser drops empty strings); auto-applies the default US Core profile when
  `meta.profile` is absent. Unknown code systems (LOINC/SNOMED/RxNorm/ICD-10) are WARNINGs —
  ontology-service owns code existence.
- **`POST /api/fhir/validate`** (debugger: severity, FHIRPath location, message, rule,
  suggestedFix), **`POST /api/fhir/build[/patients/{id}]`** (Silver → US Core
  Patient/Condition/Observation/MedicationRequest → `gold.fhir_resources` with
  `compliance_notes` JSONB, migration 16). The Phase 5 `/api/fhir-compliance/*` endpoints are unchanged.
- **Silver seed** `docs/db-init/17` — nothing populates silver.* yet; `SEED-0003` is deliberately
  non-compliant. Both 16 and 17 were applied to the running container (non-destructive, idempotent).
- **pom gotcha**: import `spring-boot-dependencies` *before* `hapi-fhir-bom` in
  `<dependencyManagement>` — first imported BOM wins, and HAPI's pins Spring Framework 6.1.x.
  Carry this into any other service that adds HAPI FHIR.
- Builder tests run the **real** validator (`UsCoreValidatorFixture`, built once per JVM), not mocks.
- `MdcLoggingFilter` here honors an incoming `X-Correlation-Id` header and echoes it back.

### fhir-compliance-service — Phase 5b slice 2 (C-CDA → FHIR)

- **`POST /api/fhir/convert/ccda`** (`application/xml`/`text/xml`) → R4 `collection` Bundle
  (Patient; Problems `11450-4` → Condition; Medications `10160-0` → MedicationRequest; Results
  `30954-2` → lab Observation) + each resource's slice 1 validator outcome + transformer `notes`.
  200 compliant or not; 400 bad XML/DOCTYPE/wrong root/no patientRole; 413; 415. Not persisted.
- **Hand-written XPath** (`service/ccda/`): `hapi-fhir-converter` is FHIR-version conversion only,
  and `org.hl7.fhir.convertors`' `CCDAConverter` is DSTU3 without Problems/Meds/Results. One
  `CcdaSectionMapper` per section, located by LOINC code (templateId fallback).
- Same "map, never invent" rule as the Silver builders; C-CDA `nullFlavor` → FHIR
  unknown/data-absent-reason. Deterministic UUIDv3 ids from C-CDA entry ids; `urn:uuid:` refs.
- **Validator fix (affects `/api/fhir/validate` too)**: US Core's OMB race/ethnicity value sets are
  pure VSAC includes under a *required* binding, so every Patient with race/ethnicity failed
  offline. `config/OmbRaceEthnicityValueSets` supplies those 3 small VSAC sets inline; detailed
  race/ethnicity (~900 codes) still errors offline (noted in the conversion response).
- Test C-CDAs are synthetic and hand-written (`src/test/resources/ccda/`, copied to `scripts/fixtures/`).

### fhir-compliance-service — Phase 5b slice 3 (GoldWriter + CMS-0057)

- **`gold/GoldWriter` is the ONLY writer of `gold.*`** (master_patients + patient_source_xref,
  fhir_resources, data_lineage). Every path goes through it: Silver builds, Phase 5
  `/api/fhir-compliance/generate`, and `GoldRevalidationService` (seed rows). Don't add a second
  path. Its methods join the caller's transaction.
- **Provisional masters**: one `PROVISIONAL` master per `silver.patients` row (migration 18's
  `match_status`; xref now unique per Silver patient). Building a patient back-links any Gold
  resource referencing `Patient/<id>` (subject/patient/beneficiary) to its master. Phase 6's PMI
  decides `MATCHED`/`MERGED`, but GoldWriter applies it (`confirmMaster` / `mergeMasters` /
  `unmergeMasters`, see patient-index-service below).
- **Lineage**: one `data_lineage` row per (target, step), upserted (unique index, migration 18).
  `transformation_details = {"target", "steps": [{stage, timestamp, transformer, input_hash,
  output_hash?, ref}]}`, with steps BRONZE→SILVER→GOLD (BRONZE only when `raw_message_id` is set,
  using `payload_checksum`), SEED→GOLD, or API→GOLD. Steps: `FHIR_MAP`, `PMI_PROVISIONAL`,
  `SEED_LOAD`, `API_GENERATE`. `source_id` is nullable now (seed/API rows).
- **CMS-0057-F read API** (`FhirR4Controller`, `cms0057/`): `GET /fhir/r4/{Coverage,
  PractitionerRole,MedicationKnowledge,MeasureReport,Device}[/{id}]`. Strict whitelisted search →
  JSONB containment; errors are OperationOutcomes (`FhirR4ExceptionHandler`, scoped to that
  controller only). `server.tomcat.relaxed-query-chars: "|"` is needed for token search.
- **Seed** `docs/db-init/19`: synthetic CMS-0057 resources inserted into Gold as
  `compliance_notes.status = PENDING_VALIDATION`; validated on startup (`GoldRevalidationRunner`)
  or via `POST /api/fhir/gold/revalidate`. `cms-practitionerrole-2` is deliberately non-compliant
  (pd-1). The source JSON is `src/test/resources/cms0057/`; regenerate 19 from it if it changes.
- **IG packages**: US Core 6.1.0 + `hl7.fhir.us.davinci-drug-formulary-2.1.0` +
  `hl7.fhir.us.davinci-deqm-5.0.0` (downloaded with the user's approval; their dependency packages
  are intentionally NOT bundled). Three validator workarounds, each documented in its class:
  `VersionedCanonicalFhirInstanceValidator` (HAPI 8.2.0 throws HAPI-2509 on version-pinned
  `MeasureReport.measure`), `RxNormFormularyValueSets` (offline RxNorm TTY value sets from
  `terminology/rxnorm-formulary-tty.csv`; must sit BEFORE npm in the chain), and
  `UsCoreFhirValidator.isUnresolvableSliceProfile` (a slice profile from an unbundled package →
  WARNING, not ERROR; the user chose this over downloading ~15 MB of dependency packages).
- **First integration test**: `integration/GoldWriterIntegrationTest` (`@SpringBootTest` +
  Testcontainers 1.21.4 `postgres:16-alpine` initialized from `../docs/db-init`, `@ServiceConnection`).
  Runs in `mvn test`, skipped without Docker. It needs the `testcontainers/ryuk:0.12.0` image (pulled
  with approval).

### fhir-compliance-service — Phase 5b slice 4 (SMART on FHIR / Keycloak)

- **Keycloak 26.8.2** (`quay.io/keycloak/keycloak:26.8.2`, pulled with approval) in compose,
  `start-dev --import-realm`, host **8180**. Realm from `keycloak/realm-clinicalharmony.json`
  (re-imported on every fresh container; dev DB inside the container, nothing in Postgres).
  Client `clinicalharmony-app` (confidential, client_credentials only, DEV secret
  `clinicalharmony-app-secret`) — plus, since Phase 6, client `patient-index-service` (secret
  `patient-index-service-secret`, only `clinicalharmony/pipeline.write` offered; the PMI's identity
  when calling GoldWriter). Since Phase 7 also: public PKCE client `clinicalharmony-dashboard`, DEV-ONLY
  direct-grant client `clinicalharmony-dashboard-dev`, confidential `api-gateway` (`api-gateway-secret`),
  realm roles `dashboard-viewer`/`data-steward` and dev users `dana`/`vic` (see api-gateway below). Adding a client means `docker compose up -d --force-recreate keycloak`
  (the realm is only imported into a fresh container). All client scopes OPTIONAL (`system/<Type>.rs` × 5, `system/*.rs`,
  `clinicalharmony/pipeline.write`) — clients must request them; mappers add `aud=clinicalharmony-fhir`
  and `sub`. Realm import with explicit `clientScopes` replaces Keycloak's built-ins (no basic/roles
  scopes), hence the client-level `sub` mapper. Keycloak only issues scopes it defines, so `.r`/`.s`
  alone can't be requested live (the server still enforces the split; Java tests cover it).
- **Issuer vs JWKS**: `KC_HOSTNAME=http://localhost:8180` pins `iss`; the service validates
  `issuer-uri` = that (`OAUTH_ISSUER_URI`) but fetches keys from `keycloak:8080` (`OAUTH_JWK_SET_URI`).
  Boot uses jwk-set-uri for keys and still adds the issuer + `audiences` validators.
- **`security/` package**: `SecurityConfig` (access matrix — see the service README),
  `SmartScopes` (v2 `cruds` + v1 `.read`/`.*`, `system/` only), `SmartScopeAuthorizationManager`
  (`/fhir/r4/{type}` = search `.s`, `/{type}/{id}` = read `.r`; unsupported types need only a valid
  token so the controller 404s), `SecurityErrorResponses` (401/403 as OperationOutcome under
  `/fhir/**`, `{"error","message"}` elsewhere, RFC 6750 `WWW-Authenticate`),
  `SmartConfigurationController` (`/.well-known/smart-configuration` + `/fhir/r4/.well-known/...`).
- User decisions: Gold/config writers need `clinicalharmony/pipeline.write`; C-CDA convert needs any
  token; stateless validators + rule GETs + discovery + health are public.
- **Tests**: existing `@WebMvcTest`s `@Import(WebSecurityTestConfig)` (real chain + all-scopes JWT by
  default); `SecurityWebTest` covers the matrix. Integration tests use self-signed RS256 JWTs
  (`TestJwts`, same validators as prod) — no Keycloak container. `AbstractIntegrationTest` holds a
  `@ServiceConnection` Postgres WITHOUT `@Container` so Boot starts it once and both integration
  classes share one context/DB (with `@Container` JUnit would stop it between classes under a cached context).
- Smoke test: `fc_curl`/`TOKEN` send a Keycloak token on every fhir-compliance call; section 14 = auth.

### patient-index-service (implemented — Phase 6)

Patient Master Index on port 8085. Package `com.clinicalharmony.patientindex`.

- **Never writes `gold.*`** (user decision at Phase 6 scoping — keeps GoldWriter the only Gold
  writer). Reads `silver.patients` + `gold.*` (SELECT only); its own state is the `pmi` schema; every
  Gold change is an HTTP call to fhir-compliance-service's `POST /api/fhir/gold/masters/merge`,
  `/{id}/confirm`, `/merges/{id}/unmerge` (`GoldMasterController` → new `GoldWriter.mergeMasters /
  confirmMaster / unmergeMasters`, one transaction each, `gold.master_patient_merges` history +
  `PMI_MERGE`/`PMI_UNMERGE`/`PMI_MATCH` lineage). `client/GoldWriterClient` uses the PMI's OWN
  Keycloak client (`client/ClientCredentialsTokenProvider`, hand-rolled client_credentials + cache —
  spring-security-oauth2-client wasn't in ~/.m2, so no download), forwards `X-Correlation-Id` from MDC,
  refreshes + retries once on 401, maps 4xx → `GoldWriterRejectedException` (status kept), 5xx/IO →
  `GoldWriterUnavailableException` (→ 502). Uses `JdkClientHttpRequestFactory` because
  HttpURLConnection throws `HttpRetryException` on a 401 to a streamed POST.
- **Matching** (`matching/`, user-approved weights; tunable via rule `PMI_MATCH`): blocking (DOB ·
  Double Metaphone surname + birth year via commons-codec · phone) → deterministic rules
  (`SAME_SOURCE_MRN`, `EXACT_NAME_DOB_PLUS_PHONE_OR_ZIP`, both blocked by DOB-disagree/sex conflict)
  → weighted agreement, score = Σ/Σmax over fields present on both; ≥0.85 auto, 0.60–0.85 review.
  Hand-written `JaroWinkler` (tested against Winkler's published values) + `Nicknames` table.
  Guard added during the build and approved by the user: `minComparableWeight` 10 — sparse
  pairs (e.g. DOB-only, 5/5 = 1.0) can't auto-merge, only go to review.
- **Run semantics** (`MatchingRunService`): only pairs with ≥1 PROVISIONAL master; auto pairs →
  union-find clusters → merge into the lowest master id; review items deduped per (post-merge)
  master pair incl. existing PENDING ones; stale PENDING → SUPERSEDED; remaining PROVISIONAL
  masters confirmed unless awaiting review or in a refused merge. `PmiLock` serializes runs/reviews.
- **Reviews** (`ReviewService`): accept → MANUAL merge of the *current* masters; reject and unmerge
  write `pmi.match_exclusions`, which runs honor forever.
- **Security**: GETs any valid token, POSTs `clinicalharmony/pipeline.write`, health public; same
  issuer/audience as fhir-compliance-service.
- **Tests (55)**: unit (matcher seed scenarios at exact scores, blocking, run/review services),
  `GoldWriterClientTest` against a JDK `HttpServer` stub (`client/StubHttpServer`, no WireMock),
  `@WebMvcTest` with the real security chain, and `PatientIndexIntegrationTest` (Testcontainers +
  self-signed JWTs + `FakeGoldWriter` that applies decisions with SQL). The real GoldWriter side is
  covered by fhir-compliance-service's `GoldMasterIntegrationTest` (270 tests there now).

### api-gateway (implemented — Phase 7)

Backend-for-frontend for the dashboard on port 8080, package `com.clinicalharmony.gateway`. Plain
Spring MVC + `RestClient` (no Spring Cloud Gateway — its Boot 3.5 line isn't in ~/.m2, and the dashboard
needs read models no service exposes, not routing). User decisions at Phase 7 scoping (all recommended):

- **Reads = SQL projections** (`read/` package) over a SELECT-only role `clinicalharmony_gateway_ro`
  (`docs/db-init/22`, `default_transaction_read_only = on`, default privileges for future tables;
  hikari `read-only: true`). **Actions = HTTP to the owning service** (`downstream/DownstreamClient`).
  The gateway never writes; GoldWriter stays the only Gold writer. Read views keep working while a
  service is down.
- **Auth**: UI uses Keycloak public client `clinicalharmony-dashboard` (Auth Code + PKCE, redirect
  `localhost:3000/*`); user tokens have `aud clinicalharmony-gateway`, realm roles in claim `roles`
  (`dashboard-viewer`, `data-steward`), `preferred_username`. Gateway validates the USER token and calls
  services with its OWN confidential client `api-gateway` (client_credentials, `pipeline.write`) via
  `ServiceTokenProvider` (hand-rolled, cached, refresh + retry once on 401). User tokens are never
  relayed. PMI `reviewer` = token's `preferred_username` (body value ignored). Dev users `dana`
  (steward) / `vic` (viewer); DEV-ONLY direct-grant client `clinicalharmony-dashboard-dev` for
  Postman/smoke. Realm-level `roles` + `users` were added to the realm JSON (force-recreate keycloak).
- **Errors**: RFC 7807 everywhere (`web/Problems`, `GlobalExceptionHandler` extends
  `ResponseEntityExceptionHandler`, `security/SecurityProblemResponses`), type
  `urn:clinicalharmony:problem:<slug>`, plus `correlationId`, `service`, `downstreamStatus`.
  Downstream: down → 503, read timeout → 504, 5xx / unusable / 401-403 on the gateway's own
  credentials → 502, own 4xx → passed through with the service's message (`DownstreamErrors.messageOf`
  reads `{message}`, `{detail}`, ontology's `{reason}`, OperationOutcome diagnostics). GET-only retry
  once on connection failure; POST/PUT never replayed.
- **Per-service read timeouts** (`gateway.services.<key>`): 5 s ingestion/ontology, 10 s rules-engine,
  30 s fhir-compliance and patient-index. `/api/v1/services/health` probes all with 2 s each → UP / DEGRADED.
- **Rule toggles** routed by `downstream/RuleOwnership` (7 hard-coded rules-engine codes, `USCORE_*` →
  fhir-compliance; CODE-*/PMI_MATCH/Phase 1 rules → 409 not toggleable); the response is re-read from
  the catalog so its shape doesn't depend on the owner.
- **Gotcha found**: `date::timestamptz` in SQL uses the session time zone, which pgjdbc sets from the
  JVM's — east of UTC a DATE shifted to the previous day. Timeline converts DATEs with
  `::timestamp AT TIME ZONE 'UTC'`.
- **Gotcha**: local `mvn package` fails offline (maven-jar-plugin 3.4.2's plexus-archiver etc. aren't in
  ~/.m2) — Docker builds download them in-container; locally run from `target/classes` +
  `dependency:build-classpath` if needed.
- **Tests (42)**: `DownstreamClientTest` (JDK `HttpServer` stub with delays: token, correlation id,
  401 retry, 4xx/5xx/timeout/down/Keycloak-down), `@WebMvcTest` `GatewayWebTest` with the real security
  chain (`@EnableConfigurationProperties(GatewayProperties)` + `@Import` security/CORS/`TestJwts`), and
  `ApiGatewayIntegrationTest` (Testcontainers, datasource = the read-only role, stubbed services,
  ontology on a dead port).

### dashboard-ui (implemented — Phase 8)

React 19 + TypeScript 7 (strict) SPA, Vite 8, React Router 8, TanStack Query 5, `oidc-client-ts` 3.5;
no UI/chart library (hand-written CSS tokens + CSS/SVG bars and timeline strip). Talks ONLY to the
api-gateway's `/api/v1` — no gateway change was needed for any view. User decisions at Phase 8
scoping (all recommended):

- **Toolchain in Docker, no Node on the host**: `docker run --rm -v "$PWD":/app -w /app node:24-alpine
  npm test|run build|ci` from `dashboard-ui/`. Exact versions pinned (`.npmrc` save-exact), each ≥2
  weeks old when chosen. The Dockerfile's build stage runs `npm test` + typecheck + `vite build`
  (red tests = no image), then `nginx:1.30.6-alpine` serves `dist/`.
- **Auth**: Auth Code + PKCE S256 against public client `clinicalharmony-dashboard`, endpoints passed
  as explicit metadata from `GET /api/v1/config` (no discovery). Tokens in memory only
  (`InMemoryWebStorage`); reload = silent redirect via Keycloak SSO back to the same route (route in
  OIDC `state`, `safeReturnTo` blocks open redirects). Refresh = refresh-token grant only (CSP
  forbids iframes). `ApiClient` on 401: refresh once, retry with the SAME correlation id, else
  re-login. Logout → Keycloak end-session → `/signed-out`.
- **Roles**: `/me.canAct` drives `StewardButton` — viewers see steward buttons disabled with
  "Requires the data-steward role"; also disabled with "<service> is down" when `/services/health`
  (polled 30 s) lists the owning service in `degraded`. Every steward action goes through
  `ConfirmDialog` (unmerge reason + reject note required); the UI never sends `reviewer`.
- **Errors**: one `ProblemView` for every RFC 7807 problem (detail, service, downstreamStatus,
  errors[], copyable correlation id); unreachable gateway/CORS and non-JSON bodies get synthesized
  problems. Every call sends `X-Correlation-Id: ui-<12 hex>`, visible in gateway + PMI logs.
- **nginx**: no proxying (CORS stays the gateway's); SPA fallback, real 404 for missing assets,
  no-cache index / no-store config.js / immutable hashed assets, strict CSP + nosniff/DENY/
  no-referrer, `server_tokens off`, `/healthz`.
- **Gotchas**: MSW 3 renamed `onUnhandledRequest` → `onUnhandledFrame` (old name silently ignored);
  React StrictMode double-runs effects so the `/callback` exchange is memoized per attempt
  (`startBoot` in `main.tsx`); the Vite dev server needs port 3000 (the only registered redirect
  URI), so stop the compose container first.
- **Tests (56, Vitest + jsdom + Testing Library + MSW)**: the MSW gateway stub serves REAL responses
  captured from the live gateway (`test/fixtures/`), and any unhandled request fails the test.
  No Playwright (2 GB image); instead smoke section 17 + `dashboard-ui/CHECKLIST.md` (12-step manual
  click-through, replaces a Postman collection for this phase).

### Bronze schema invariants

`bronze.raw_messages` CHECK constraints that Java code must match:
- `message_type`: `HL7V2`, `FHIR_JSON`, `CLAIMS_CSV`
- `message_format`: `TEXT`, `JSON`, `CSV`
- `processing_status`: `PENDING`, `PROCESSING`, `PROCESSED`, `FAILED`, `QUARANTINED`

`RawMessageRecord` factory methods (`accepted(...)` → `PENDING`, `quarantined(...)` → `QUARANTINED`) are the only correct way to build these records.

`RawMessageRepository` uses plain `JdbcTemplate` (not Spring Data JPA / no ORM). The HikariCP pool is configured with `hikari.schema: bronze`, so the Postgres search path is scoped to the Bronze schema automatically. Future services writing to `silver.*` or `gold.*` must set their own `hikari.schema` accordingly (or qualify table names explicitly).

### Ontology schema invariants

Phase 1 only created `ontology.icd10_codes`, `loinc_codes`, `rxnorm_codes`, `clinical_rules`. Phase 3 (`docs/db-init/08` through `12`) added, without wiping any existing data (applied directly to the running container, not via `docker-compose down -v`):
- `ontology.snomed_codes` (new table: `concept_id`, `term`, `semantic_tag` CHECK IN `disorder`/`finding`/`procedure`)
- `ontology.concept_mappings` (new table: `source_system`/`source_code`/`target_system`/`target_code`/`mapping_type` CHECK IN `EQUIVALENT`/`BROADER`/`NARROWER`)
- `CODE-004` rule in `ontology.clinical_rules` (SNOMED CT validation failures — needed because `silver.validation_reports.rule_code` has a hard FK to `clinical_rules`)
- `patient_id`, `bad_value`, `suggested_value` columns added to `silver.validation_reports` (originally only had a free-text `message` — the Phase 8 Validation Report dashboard view will want these as queryable fields)

Phase 4 (`docs/db-init/13`) added, same non-destructive pattern (applied directly to the running
container, not via `docker-compose down -v`):
- `ontology.clinical_rules.rule_expression` (JSONB, nullable) — rule-specific parameters (code
  lists, ranges, required fields), so tuning a rule's behavior is a data change, not a code change.
- 7 new `clinical_rules` rows for rules-engine-service's built-in rules (see that service's
  section above) — a distinct set from `CODE-*`/`QUAL-001`/`LOGIC-001`/`IDENT-001`, all within the
  existing `rule_category` CHECK constraint (no enum values needed adding).

Phase 5b slice 3 (`docs/db-init/18`, `19`, same non-destructive pattern, applied to the running
container): `gold.master_patients.match_status` (CHECK `PROVISIONAL`/`MATCHED`/`MERGED`), nullable
`gold.data_lineage.source_id`, unique indexes `uq_data_lineage_target_step` and
`uq_patient_source_xref_silver_patient`; 19 seeds 13 CMS-0057 Gold resources + 6 RxNorm SCD/SCDG
rows in `ontology.rxnorm_codes`.

Phase 5 (`docs/db-init/14`) added 7 more `clinical_rules` rows (`USCORE_*_COMPLETENESS`, see
fhir-compliance-service's section above) — no new columns needed, reusing `rule_expression` from
Phase 4. It also made the first-ever write to `gold.fhir_resources`, a table Phase 1 created but
left unused; no schema change was needed there either, since its columns and unique constraint on
`(resource_type, fhir_id)` already existed.

Phase 7 (`docs/db-init/22`, same non-destructive pattern, applied to the running container): role
`clinicalharmony_gateway_ro` (LOGIN, DEV-only password) with USAGE + SELECT on bronze/silver/gold/
ontology/pmi, default privileges for future tables, and `default_transaction_read_only = on`.

Phase 6 (`docs/db-init/20`, `21`, same non-destructive pattern, applied to the running container):
`gold.master_patient_merges` (merge history incl. `moved_silver_patient_ids` / `moved_fhir_resource_ids`
BIGINT[] so unmerge is exact; partial unique index = one live merge per absorbed master), new
schema **`pmi`** (`match_runs`, `match_candidates` with a partial unique index on PENDING pairs,
`match_exclusions`), rule `PMI_MATCH` (category `IDENTITY`, weights/thresholds in `rule_expression`);
21 seeds 6 cross-source duplicate Silver patients (`PMI-0001..0006`).

`ontology-service` and `rules-engine-service` both read `ontology.*` tables and write `silver.validation_reports` — always fully-qualify table names in SQL rather than relying solely on `hikari.schema` search-path scoping (same convention as `ingestion-service`). `fhir-compliance-service` follows the same convention, additionally writing `gold.fhir_resources`.

### Testing pattern

- **Service-layer tests** (`*ProcessorTest` / `*ValidatorTest` / `*ServiceTest` / `*RuleTest`): plain JUnit 5, `mock(...)` the JdbcTemplate/repository, no Spring context — fast.
- **Web-layer tests** (`*ControllerTest`): `@WebMvcTest` + `@MockBean` on the service layer — Spring MVC slice only, no DB.
- **Integration tests** (`*IntegrationTest`, fhir-compliance-service, patient-index-service and api-gateway): `@SpringBootTest`
  + Testcontainers Postgres initialized from `docs/db-init`, real HTTP via `TestRestTemplate`.
  `@Testcontainers(disabledWithoutDocker = true)`, so `mvn test` still passes without Docker.
  Extend `integration/AbstractIntegrationTest` (shared context + DB) and use its `withScopes(...)` /
  `anonymous()` clients — endpoints are Bearer-protected since Phase 5b slice 4.
- **UI tests** (dashboard-ui, `test/*.test.ts(x)`): Vitest + jsdom + Testing Library against an MSW gateway stub serving real captured `/api/v1` responses; run in `node:24-alpine` via `docker run` (no host Node).
- **Black-box smoke test** (`scripts/smoke-test.sh`): once the stack is up (`docker-compose up -d`), this hits all four implemented services' REST endpoints over HTTP and asserts on real responses — accept/quarantine outcomes, ontology validation results, all 7 rule-engine evaluations and live rule-toggle behavior, US Core structural validation (including a `requiredAnyOf` choice-type check), and FHIR generation round-tripped back through validation to cross-check the generator against the validator. Fixture payloads live in `scripts/fixtures/`. This replaces the earlier "verified manually" note — it's now a repeatable, scriptable check (239 assertions as of Phase 8; it fetches a Bearer token from the compose Keycloak for fhir-compliance-service and patient-index-service calls; section 15 = PMI, which inserts fresh random synthetic patients each run so it stays rerunnable; section 16 = api-gateway, with real dana/vic user tokens from the dev direct-grant client; section 17 = dashboard-ui's nginx: SPA fallback, caching, CSP/headers, runtime config, Keycloak accepting the dashboard's PKCE request and refusing it without PKCE or with a foreign redirect, CORS). Gold-layer checks with no HTTP surface yet (lineage, masters) use `check_sql` via `docker exec` into `clinicalharmony-postgres`.

## Local Docker environment

**Port conflicts**: an unrelated "medistream" project auto-starts containers on ports 5432, 3306, 9092, 2181, and 8083 whenever Docker Desktop restarts. Do not stop or reconfigure those containers. Use a throwaway `docker network create` + `docker run` for isolated local verification rather than `docker-compose.override.yml` (Compose merges `ports:` by concatenation, so overrides can't replace conflicting mappings).

**Apple Silicon image constraint**: `eclipse-temurin:*-alpine` tags have no arm64 manifest and will fail to pull on this machine. Always use the Debian-based tag (e.g. `eclipse-temurin:17-jre`) for all service Dockerfiles. This applies to Phase 3 onward, not just ingestion-service. (The `node:*-alpine` and `nginx:*-alpine` images used by dashboard-ui DO have arm64 manifests — the problem is specific to eclipse-temurin.)

**Network desync after multiple daemon restarts**: after several `open -a Docker` restarts in one session, a container's `HostConfig.NetworkMode` can say it's on `clinicalharmony_clinicalharmony-net` while `NetworkSettings.Networks` is actually empty (`{}`) — other containers then get `UnknownHostException` resolving it by service name, even though the container itself is `Up`/healthy. `docker network connect` and `docker restart` on just the affected container do **not** reliably fix this. The reliable fix: `docker compose down` (no `-v`, volume/data untouched) then `docker compose up -d` again — cheap since it doesn't touch the Postgres volume, and fully resets the network.

## Build order / phase plan

Phases are meant to be fully complete before the next starts. Current state as of 2026-11-13:

- **Phase 1** (done): Foundation — docker-compose.yml, all 7 `docs/db-init/` SQL scripts, seed data.
- **Phase 2 / 2b** (done): ingestion-service — all three intake paths (HL7 v2, FHIR JSON, Claims CSV) landing to Bronze.
- **Phase 3** (done): ontology-service — ICD10Validator, LoincValidator, RxNormValidator, SnomedValidator, an OntologyService orchestrator, a ValidationReportBuilder writing to `silver.validation_reports`, a ConceptMappingService for SNOMED CT <-> ICD-10 cross-terminology lookups, REST endpoints on port 8082. Resolved: US Core IG Check stays in Phase 5 (fhir-compliance-service) — the user's concrete Phase 3 spec doesn't include it in ontology-service's scope.
- **Phase 4** (done): rules-engine-service — 7 built-in `RuleEvaluator`s, a `RuleExecutor`/`RuleLoader` pair driven by `ontology.clinical_rules.rule_expression` (new JSONB column), an `OntologyServiceClient` + `RulesOrchestrationService` that calls ontology-service then aggregates both validation layers, REST endpoints on port 8083. Runs on port 8083 per the original spec. The earlier 8083 collision was a bug in docker-compose.yml's placeholder ports for fhir-compliance-service and patient-index-service (they had each other's ports) — fixed by correcting those placeholders to 8084/8085 rather than moving rules-engine-service off its assigned port.
- **Phase 5** (done): fhir-compliance-service — 7 `USCORE_*_COMPLETENESS` rules covering all 7 US
  Core resource types (Patient, Condition, Observation, MedicationRequest, AllergyIntolerance,
  Encounter, DiagnosticReport), a `UsCoreValidationService` for structural required-field
  validation, and a `FhirBundleGenerator` + `FhirGenerationService` that generates FHIR resources
  from a canonical patient record and persists them to `gold.fhir_resources` (Phase 1's table,
  unused until now) — REST endpoints on port 8084. Scoped deliberately as structural-only
  (required-field presence, not full cardinality/terminology-binding/invariant conformance) per
  the user's choice during Phase 5 scoping, prioritizing breadth (all 7 resource types) over depth.
- **Phase 5b** (done): the rest of the original Phase 5 plan, in four branches/PRs —
  (1) `phase-5b-fhir-validator`: HL7 validator vs US Core 6.1.0 + Silver builders (done, merged as
  PR #8); (2) `phase-5b-ccda-transformer`: C-CDA → FHIR (done, merged as PR #9);
  (3) `phase-5b-gold-writer-cms0057`: GoldWriter + data_lineage + CMS-0057 read endpoints (done,
  merged as PR #10); (4) `phase-5b-smart-oauth`: SMART on FHIR backend services / Keycloak 26.8
  (done, merged as PR #11).
- **Phase 6** (done, merged as PR #12): patient-index-service (PMI + deduplication) — see its
  section above.
- **Phase 7** (done, merged as PR #13): api-gateway — see its section above.
- **Phase 8** (done, merged as PR #14): dashboard-ui — see its section above.
- **Phase 9**: data-generator (Python)
- **Phase 10**: Polish, final Docker test, demo script
