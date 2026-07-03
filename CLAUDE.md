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
| ontology-service | 8082 | placeholder |
| fhir-compliance-service | 8083 | placeholder |
| patient-index-service | 8084 | placeholder |
| rules-engine-service | 8085 | placeholder |
| api-gateway | 8080 | placeholder |
| dashboard-ui | 3000 | placeholder |

Services still showing `[placeholder]` in docker-compose.yml boot a bare `eclipse-temurin:17-jre` image and idle. When a service is implemented, replace its `image`/`command` with a `build:` block pointing to that service's `Dockerfile`.

### ingestion-service (implemented — Phase 2b)

The only fully implemented service. Three parallel intake paths, all sharing a single `RawMessageRepository` write to `bronze.raw_messages`:

- **HL7 v2 MLLP** — Apache Camel `mllp://` route (`Hl7MllpRoute`) on port 8887; returns a real ACK/NAK over the same TCP connection.
- **HL7 v2 REST** — `POST /api/ingestion/hl7` (`Hl7IngestionController`), `Content-Type: text/plain`. Normalizes `\n`/`\r\n` → `\r` before parsing (MLLP senders always use `\r`; REST clients often don't).
- **FHIR JSON REST** — `POST /api/ingestion/fhir` (`FhirIngestionController`), `Content-Type: application/json`. Syntactic check only (`resourceType` field present); US Core profile conformance is `fhir-compliance-service`'s job.
- **Claims CSV REST** — `POST /api/ingestion/claims` (`ClaimsIngestionController`), `Content-Type: text/plain` or `text/csv`. Lands **one Bronze row per claim line** (header + that line) so downstream Silver parsing is self-contained. Ingestion is all-or-nothing per file — a bad row quarantines the whole file.

All paths: SHA-256 checksum on the raw payload, quarantine (not drop) on parse failure, 201/422 on REST.

`ClaimsMessageProcessor.process()` is `@Transactional`: a DB failure during the write phase rolls back all rows for that file. Parse failures quarantine the whole file as a single row (intentional — a partial claim set is worse for downstream reconciliation than a rejected one).

### Bronze schema invariants

`bronze.raw_messages` CHECK constraints that Java code must match:
- `message_type`: `HL7V2`, `FHIR_JSON`, `CLAIMS_CSV`
- `message_format`: `TEXT`, `JSON`, `CSV`
- `processing_status`: `PENDING`, `PROCESSING`, `PROCESSED`, `FAILED`, `QUARANTINED`

`RawMessageRecord` factory methods (`accepted(...)` → `PENDING`, `quarantined(...)` → `QUARANTINED`) are the only correct way to build these records.

`RawMessageRepository` uses plain `JdbcTemplate` (not Spring Data JPA / no ORM). The HikariCP pool is configured with `hikari.schema: bronze`, so the Postgres search path is scoped to the Bronze schema automatically. Future services writing to `silver.*` or `gold.*` must set their own `hikari.schema` accordingly (or qualify table names explicitly).

### Testing pattern

- **Service-layer tests** (`*ProcessorTest`): plain JUnit 5, `mock(RawMessageRepository.class)`, no Spring context — fast.
- **Web-layer tests** (`*ControllerTest`): `@WebMvcTest` + `@MockBean` on the processor — Spring MVC slice only, no DB.
- There are no integration tests yet; the end-to-end path was verified manually against a live container.

## Local Docker environment

**Port conflicts**: an unrelated "medistream" project auto-starts containers on ports 5432, 3306, 9092, 2181, and 8083 whenever Docker Desktop restarts. Do not stop or reconfigure those containers. Use a throwaway `docker network create` + `docker run` for isolated local verification rather than `docker-compose.override.yml` (Compose merges `ports:` by concatenation, so overrides can't replace conflicting mappings).

**Apple Silicon image constraint**: `eclipse-temurin:*-alpine` tags have no arm64 manifest and will fail to pull on this machine. Always use the Debian-based tag (e.g. `eclipse-temurin:17-jre`) for all service Dockerfiles. This applies to Phase 3 onward, not just ingestion-service.

## Build order / phase plan

Phases are meant to be fully complete before the next starts. Current state as of 2026-07-03:

- **Phase 1** (done): Foundation — docker-compose.yml, all 7 `docs/db-init/` SQL scripts, seed data.
- **Phase 2 / 2b** (done): ingestion-service — all three intake paths (HL7 v2, FHIR JSON, Claims CSV) landing to Bronze.
- **Phase 3** (next): ontology-service — ICD10Validator, LoincValidator, RxNormValidator, SnomedValidator (each a separate Spring Bean), an OntologyService orchestrator, a ValidationReportBuilder writing to `silver.validation_reports`, REST endpoints on port 8082. Resolved: US Core IG Check stays in Phase 5 (fhir-compliance-service) — the user's concrete Phase 3 spec doesn't include it in ontology-service's scope.
- **Phase 4**: rules-engine-service
- **Phase 5**: fhir-compliance-service (US Core IG)
- **Phase 6**: patient-index-service (PMI + deduplication)
- **Phase 7**: api-gateway
- **Phase 8**: dashboard-ui (React, 5 views)
- **Phase 9**: data-generator (Python)
- **Phase 10**: Polish, final Docker test, demo script
