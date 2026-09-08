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
| fhir-compliance-service | 8084 | placeholder |
| patient-index-service | 8085 | placeholder |
| rules-engine-service | 8083 | implemented |
| api-gateway | 8080 | placeholder |
| dashboard-ui | 3000 | placeholder |

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

`ontology-service` and `rules-engine-service` both read `ontology.*` tables and write `silver.validation_reports` — always fully-qualify table names in SQL rather than relying solely on `hikari.schema` search-path scoping (same convention as `ingestion-service`).

### Testing pattern

- **Service-layer tests** (`*ProcessorTest` / `*ValidatorTest` / `*ServiceTest` / `*RuleTest`): plain JUnit 5, `mock(...)` the JdbcTemplate/repository, no Spring context — fast.
- **Web-layer tests** (`*ControllerTest`): `@WebMvcTest` + `@MockBean` on the service layer — Spring MVC slice only, no DB.
- **Black-box smoke test** (`scripts/smoke-test.sh`): once the stack is up (`docker-compose up -d`), this hits all three implemented services' REST endpoints over HTTP and asserts on real responses — accept/quarantine outcomes, ontology validation results, all 7 rule-engine evaluations, and the live rule-toggle behavior. Fixture payloads live in `scripts/fixtures/`. This replaces the earlier "verified manually" note — it's now a repeatable, scriptable check, though it's still HTTP-level (no JUnit `@SpringBootTest`/Testcontainers integration tests exist).

## Local Docker environment

**Port conflicts**: an unrelated "medistream" project auto-starts containers on ports 5432, 3306, 9092, 2181, and 8083 whenever Docker Desktop restarts. Do not stop or reconfigure those containers. Use a throwaway `docker network create` + `docker run` for isolated local verification rather than `docker-compose.override.yml` (Compose merges `ports:` by concatenation, so overrides can't replace conflicting mappings).

**Apple Silicon image constraint**: `eclipse-temurin:*-alpine` tags have no arm64 manifest and will fail to pull on this machine. Always use the Debian-based tag (e.g. `eclipse-temurin:17-jre`) for all service Dockerfiles. This applies to Phase 3 onward, not just ingestion-service.

**Network desync after multiple daemon restarts**: after several `open -a Docker` restarts in one session, a container's `HostConfig.NetworkMode` can say it's on `clinicalharmony_clinicalharmony-net` while `NetworkSettings.Networks` is actually empty (`{}`) — other containers then get `UnknownHostException` resolving it by service name, even though the container itself is `Up`/healthy. `docker network connect` and `docker restart` on just the affected container do **not** reliably fix this. The reliable fix: `docker compose down` (no `-v`, volume/data untouched) then `docker compose up -d` again — cheap since it doesn't touch the Postgres volume, and fully resets the network.

## Build order / phase plan

Phases are meant to be fully complete before the next starts. Current state as of 2026-07-09:

- **Phase 1** (done): Foundation — docker-compose.yml, all 7 `docs/db-init/` SQL scripts, seed data.
- **Phase 2 / 2b** (done): ingestion-service — all three intake paths (HL7 v2, FHIR JSON, Claims CSV) landing to Bronze.
- **Phase 3** (done): ontology-service — ICD10Validator, LoincValidator, RxNormValidator, SnomedValidator, an OntologyService orchestrator, a ValidationReportBuilder writing to `silver.validation_reports`, a ConceptMappingService for SNOMED CT <-> ICD-10 cross-terminology lookups, REST endpoints on port 8082. Resolved: US Core IG Check stays in Phase 5 (fhir-compliance-service) — the user's concrete Phase 3 spec doesn't include it in ontology-service's scope.
- **Phase 4** (done): rules-engine-service — 7 built-in `RuleEvaluator`s, a `RuleExecutor`/`RuleLoader` pair driven by `ontology.clinical_rules.rule_expression` (new JSONB column), an `OntologyServiceClient` + `RulesOrchestrationService` that calls ontology-service then aggregates both validation layers, REST endpoints on port 8083. Runs on port 8083 per the original spec. The earlier 8083 collision was a bug in docker-compose.yml's placeholder ports for fhir-compliance-service and patient-index-service (they had each other's ports) — fixed by correcting those placeholders to 8084/8085 rather than moving rules-engine-service off its assigned port.
- **Phase 5** (next): fhir-compliance-service (US Core IG)
- **Phase 6**: patient-index-service (PMI + deduplication)
- **Phase 7**: api-gateway
- **Phase 8**: dashboard-ui (React, 5 views)
- **Phase 9**: data-generator (Python)
- **Phase 10**: Polish, final Docker test, demo script
