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
| Testcontainers (test scope, Boot-managed) | 1.21.4 (`postgresql`, `kafka` modules) |
| spring-kafka / kafka-clients (Boot-managed, Phase 9a) | 3.3.16 / 3.9.2 |

New services should use the same Spring Boot parent version and align Camel via its BOM (`camel-spring-boot-bom`) to avoid dependency conflicts.

**Spring Boot version note (2026-07-03)**: bumped from 3.2.5 (EOL Dec 2024) to 3.5.16 — the last patch ever released for the whole Spring Boot 3.x line, which itself reached EOL June 30, 2026. The actively-supported version is now Spring Boot 4.1.x, but that's a real migration (Jakarta EE 11, Spring Framework 7, removed deprecated APIs) with unverified Camel/HAPI HL7v2 compatibility — deliberately deferred rather than attempted opportunistically. Revisit as its own dedicated task if currency with the ecosystem matters more than migration risk.

### Environment variables (ingestion-service)

All have sane defaults for local development. No `DB_*` since 9d: ingestion-service has no database (every
path publishes to Kafka).

| Variable | Default | Purpose |
|---|---|---|
| `HL7_MLLP_PORT` | `8887` | MLLP listener port |
| `HL7_DEFAULT_SOURCE_SYSTEM` | `UNKNOWN_HL7_SOURCE` | Fallback source tag |
| `FHIR_DEFAULT_SOURCE_SYSTEM` | `UNKNOWN_FHIR_SOURCE` | Fallback source tag |
| `CLAIMS_DEFAULT_SOURCE_SYSTEM` | `UNKNOWN_CLAIMS_SOURCE` | Fallback source tag |
| `CCDA_DEFAULT_SOURCE_SYSTEM` | `UNKNOWN_CCDA_SOURCE` | Fallback source tag (9d) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:19092` | MLLP (9a) and REST (9d) intake publish here (compose: `kafka:29092`) |

Future services follow the same pattern: `DB_*` env vars for the datasource, a `default-source-system` config key per intake path.

### Environment variables (bronze-listener-service)

Same `SPRING_PROFILES_ACTIVE` (`local`/`docker`) + `DB_*` pattern as ontology-service, plus
`KAFKA_BOOTSTRAP_SERVERS` (docker profile; default `kafka:29092`; the `local` profile uses `localhost:19092`).
Tunables under `bronze.*` in `application.yml` (partitions, retry, outbox poll/batch/send-timeout).

### Environment variables (silver-transformer-service)

Same `SPRING_PROFILES_ACTIVE` (`local`/`docker`) + `DB_*` + `KAFKA_BOOTSTRAP_SERVERS` pattern as
bronze-listener-service, plus `RULES_ENGINE_BASE_URL` (docker profile; default
`http://rules-engine-service:8083`; `local` uses `localhost:8083`). Tunables under `silver.*` in
`application.yml` (partitions, retry, outbox, `validation.sweep-interval` 30 s / `min-age` 10 s / `batch-size`,
`rules-engine` timeouts).

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
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:29092` (docker profile; `local` = `localhost:19092`) | Phase 9c pmi-matcher |
| `PMI_MATCHER_ENABLED` | `true` | `false` = matching only on request (pre-9c); tunables under `pmi-matcher.*` (quiet-period 5 s, max-delay 30 s, tick, retry-delay 30 s, run-on-startup) |

### Environment variables (api-gateway)

Same `SPRING_PROFILES_ACTIVE` + `DB_*` + inbound `OAUTH_ISSUER_URI` / `OAUTH_JWK_SET_URI` pattern, but
`DB_USER`/`DB_PASSWORD` default to the read-only role `clinicalharmony_gateway_ro` / `gateway-ro-dev-password`
(migration 22), and inbound tokens are USER tokens with audience `clinicalharmony-gateway`. Plus:

| Variable | Default | Purpose |
|---|---|---|
| `OAUTH_TOKEN_URI` | `http://localhost:8180/realms/clinicalharmony/protocol/openid-connect/token` | Its own client_credentials token |
| `GATEWAY_CLIENT_ID` / `GATEWAY_CLIENT_SECRET` | `api-gateway` / `api-gateway-secret` | Its Keycloak client (DEV-only secret, in the realm JSON) |
| `DASHBOARD_CLIENT_ID` | `clinicalharmony-dashboard` | Public PKCE client, served by `GET /api/v1/config` |
| `SPLUNK_WEB_URL` | `http://localhost:8000` | Splunk Web as the browser sees it; `GET /api/v1/config` → `observability.splunkTraceUrl` (9e) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated allowlist |
| `INGESTION_BASE_URL` … `PATIENT_INDEX_BASE_URL` | `http://localhost:8081` … `:8085` | Downstream services (compose: service names) |

### Phase 9e — observability (done, merged as PR #20 with 9d)

Smoke 444/444 twice (after one fix: the gateway "q=garcia" check outgrew a page — now "maria garcia"). Generator seed 2611
(count 40) verified live: CH798022 = NC533077 (#666), NC614416 = PC422736 (#1764), NC455061 = MB464015 (#1780) merged;
CH520097 ARJUN BROWN vs HI916410 Arjun Brwon candidate #380 PENDING 0.6905; PC473578 (Silver #4034) CODE-001 E11.999;
MB893598 (Silver #4019) GENDER_PREGNANCY_CONFLICT + CODE-001 for Z34.90 (not in the ICD-10 seed subset). CHECKLIST rows
47–50, Postman `observability/postman/phase9e-observability-splunk` (7 items). gold.built logs `endToEndMs`.

**Dashboard ↔ Splunk** (follow-up 2026-11-23, the user asked why the two screens' numbers differ): Splunk counts LOG
EVENTS since 9e started (first event 2026-11-19 11:22 UTC), the dashboard DATABASE ROWS all-time — reconciled exactly
(payloads in 1,363 = stored 1,128 + quarantined 85 + duplicate 150; gold.built 1,259 = gold.outbox_events since then, for
569 distinct patients; the ~30 masters without an event are smoke section 15's SQL-inserted PMI patients built through
the API, which emits none). Added: gateway Bronze detail `correlationId` + `intakeChannel`, `GET /config` →
`observability.splunkTraceUrl`; UI Bronze page "Intake" + "Correlation id · Open trace in Splunk" (opens the Trace
dashboard 1 h before receipt; legacy REST rows say they have none); Trace dashboard: autoRun, columns bronze / silver /
master link back to `localhost:3000` (`$ui|n$` — without `|n` Splunk URL-encodes the token and the link turns relative;
Splunk asks "Redirecting away from Splunk" first); Pipeline: "Patients built" (dc) + p50 tiles, Dead letters excludes
correlation id `smoke-*`, by-service chart shows "smoke test" apart, description explains events vs rows. Smoke sections
20 / 24 now send their broken records with header `correlation-id: smoke-…` (`--property parse.headers=true`); the 10
dead letters logged before that stay untagged. **Gotcha**: after editing the app's XML, `POST
/servicesNS/nobody/clinicalharmony/data/ui/views/_reload` — once it read a half-synced file through the Docker mount
(truncated → XML error page); a second reload fixed it. Tests: gateway 61, UI 86; smoke section 24 +4; CHECKLIST 52.

### Environment variables (Phase 9e, every Java service)

| Variable | Default | Purpose |
|---|---|---|
| `OTLP_METRICS_ENABLED` | `false` | Micrometer → OTLP export (compose: `true`), so tests / `mvn spring-boot:run` export nothing |
| `OTLP_METRICS_URL` | `http://localhost:4318/v1/metrics` | compose: `http://otel-collector:4318/v1/metrics` |
| `DEPLOYMENT_ENVIRONMENT` | `dev` | OTel resource attribute `deployment.environment` |

Compose also gives every service `logging: *splunk-logging` (Docker splunk log driver → `https://localhost:8088`, DEV-ONLY
HEC token `9e0c1a7c-0000-4c1f-9e00-c11a1c4a7d0e`, also in `observability/splunk/apps/clinicalharmony/default/inputs.conf`
and the collector's env). Splunk admin password `clinicalharmony-splunk-dev` (DEV-ONLY).

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
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:29092` (docker profile; `local` = `localhost:19092`) | Phase 9c gold-builder + Gold outbox relay |
| `GOLD_BUILDER_ENABLED` | `true` | `false` = HTTP only (pre-9c); tunables under `gold-builder.*` (partitions, retry, outbox) |

## Architecture

ClinicalHarmony is a Bronze → Silver → Gold medallion pipeline for healthcare data. Each layer is a PostgreSQL schema; each service is a standalone Spring Boot app orchestrated by Docker Compose.

### Data flow

```
Sources (HL7 v2 · FHIR JSON · Claims CSV)
        │
        ▼
ingestion-service  →  bronze.raw_messages  (every payload, valid or not)
   │ MLLP since 9a: → Kafka clinicalharmony.bronze.hl7v2.raw → bronze-listener-service → bronze.raw_messages
   │                  (+ outbox → clinicalharmony.bronze.ingested)
   │ since 9b:     bronze.ingested → silver-transformer-service → silver.* (+ rules-engine/ontology validation)
   │                  (+ outbox → clinicalharmony.silver.patient.changed)
   │ since 9c:     silver.patient.changed → gold-builder (in fhir-compliance-service) → Gold via GoldWriter
   │                  (+ gold.outbox_events → clinicalharmony.gold.patient.changed → pmi-matcher in
   │                  patient-index-service: one debounced matching run per burst)
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
| bronze-listener-service | 8086 | implemented (Phase 9a, PR #15); health/metrics only |
| silver-transformer-service | 8087 | implemented (Phase 9b, PR #17); health/metrics only |
| kafka | 19092 | KRaft, no Zookeeper (Phase 9a); containers use `kafka:29092` |
| kafbat-ui | 8090 | Kafka console, read-only (Phase 9a); container port 8080 |

Services still showing `[placeholder]` in docker-compose.yml boot a bare `eclipse-temurin:17-jre` image and idle. When a service is implemented, replace its `image`/`command` with a `build:` block pointing to that service's `Dockerfile`.

### ingestion-service (implemented — Phase 2b)

> Historical (Phase 2b). Since 9d part 1 no path writes Bronze directly: see "Phase 9d part 1" below.

Three parallel intake paths, all sharing a single `RawMessageRepository` write to `bronze.raw_messages`:

- **HL7 v2 MLLP** — Apache Camel `mllp://` route (`Hl7MllpRoute`) on port 8887; returns a real ACK/NAK over the same TCP connection.
- **HL7 v2 REST** — `POST /api/ingestion/hl7` (`Hl7IngestionController`), `Content-Type: text/plain`. Normalizes `\n`/`\r\n` → `\r` before parsing (MLLP senders always use `\r`; REST clients often don't).
- **FHIR JSON REST** — `POST /api/ingestion/fhir` (`FhirIngestionController`), `Content-Type: application/json`. Syntactic check only (`resourceType` field present); US Core profile conformance is `fhir-compliance-service`'s job.
- **Claims CSV REST** — `POST /api/ingestion/claims` (`ClaimsIngestionController`), `Content-Type: text/plain` or `text/csv`. Lands **one Bronze row per claim line** (header + that line) so downstream Silver parsing is self-contained. Ingestion is all-or-nothing per file — a bad row quarantines the whole file.

All paths: SHA-256 checksum on the raw payload, quarantine (not drop) on parse failure, 201/422 on REST.

`ClaimsMessageProcessor.process()` is `@Transactional`: a DB failure during the write phase rolls back all rows for that file. Parse failures quarantine the whole file as a single row (intentional — a partial claim set is worse for downstream reconciliation than a rejected one).

### ingestion-service — Phase 9a change (MLLP → Kafka)

- MLLP no longer writes Postgres. `kafka/Hl7MllpIntakeService`: `Hl7Envelope.peek` (string split of
  MSH/PID, never throws, no HAPI) → `Hl7RawPublisher` → topic `clinicalharmony.bronze.hl7v2.raw`
  (declared here, 3 partitions), key `source|PID-3` → `source|msh10:<id>` → `sha256:<checksum>`,
  headers `event-id/event-type/schema-version/produced-at/producer/correlation-id` (`mllp-<12 hex>`),
  value JSON with `rawPayload` + `sourceSystem` + `payloadChecksum` + `receivedAt`. Blocks on
  `send().get(10s)` (`acks=all`, idempotence, `max.block.ms` 5 s) → `Hl7AckBuilder` AA, else AE.
  Malformed HL7 still gets AA (the listener quarantines it).
- **Gotcha found**: camel-mllp sends the exchange PROPERTY `CamelMllpAcknowledgementString`, not the
  body; without it it auto-ACKs AA from the inbound MSH. The pre-9a route set only the body, so MLLP
  NAKs never reached senders. The route now sets the property.
- (9a) REST paths were unchanged then — superseded by 9d part 1, below.
- Structured JSON logs (Boot `logging.structured.format.console: logstash` + `json.add.service`);
  SLF4J fluent `addKeyValue` fields and MDC become top-level JSON keys. Same in bronze-listener-service.

### Phase 9d part 1 — REST intake over Kafka + C-CDA (done, merged as PR #20)

- **ingestion-service** (no datasource, no jdbc/postgres deps; `RawMessageRepository` + the 3 processors deleted):
  `intake/RestIntakeService` = `FormatChecks` (HL7 = HAPI parse + MSH-9/10, FHIR = JSON object + resourceType, claims =
  header + ≥1 row + equal widths, C-CDA = secure DOM, no DOCTYPE, `{urn:hl7-org:v3}ClinicalDocument` +
  `recordTarget/patientRole`) → publish ALWAYS (valid or not) → `RestIntakeResult` 202 / 422. `kafka/RawEventPublisher`
  (generic, blocking `acks=all`, refuses an event > `ingestion.kafka.max-event-bytes` 1 000 000 → 413) +
  `Hl7RawPublisher` (wrapper; value gains `intakeChannel` MLLP|REST). Topics declared here: `clinicalharmony.bronze.
  {hl7v2,fhir,claims,ccda}.raw` (HL7 REST shares MLLP's). `web/RestIntakeController` (`/hl7`, `/fhir` incl.
  `application/fhir+json`, `/claims`, `/ccda` xml) + `IntakeExceptionHandler`: `IntakeRejectedException` → 400 bad
  X-Source-System (`[A-Za-z0-9 ._:-]{1,100}`), 413, 503 + `Retry-After: 5`, body `{error,message,correlationId}`.
  Correlation id = caller's `X-Correlation-Id` if `[A-Za-z0-9._:-]{1,64}`, else `rest-<12 hex>`; echoed as a header.
  HL7 source = MSH-4 (header ignored); others = `X-Source-System` else the per-format default. Keys: source|patient id
  (FHIR first Patient identifier/id, C-CDA patientRole/id/@extension), else `sha256:<checksum>`.
- **bronze-listener-service**: `listener/RawIntakeListener` (id `bronze-writer`, all 4 topics), `listener/RawEvent`
  (replaces Hl7RawEvent; `intakeChannel` absent = MLLP → `MLLP_KAFKA`, REST → `REST_KAFKA`, else invalid → DLT),
  `inspect/PayloadInspector` (same rules as FormatChecks; claims re-encoded with CSV quoting, header + line per row),
  `inspect/BronzeFormat`. `BronzeWriter.store(format, event, source)`: claims = one row + one outbox event per line
  (`claimLine`/`claimLines`) in ONE transaction, per-line `ON CONFLICT` (all lines dup → DUPLICATE); quarantine = one
  row with the whole payload. `bronze.ingested` payload gains `intakeChannel` + format details (`fhirResourceType`,
  `ccdaDocumentId`, …). DLT beans for the 3 new topics. commons-csv 1.10.0 added (approved).
- **silver-transformer-service**: (part 1 only) non-HL7 Bronze rows → `DEFERRED`, stayed PENDING — replaced in part 2 by
  the mappers, and the backlog sweeper picked those rows up.
- **fhir-compliance-service**: lineage BRONZE step names the door: `ingestion-service MLLP|REST → Kafka →
  bronze-listener-service (<type>)` (`SilverProvenance.kafkaDoor()`).
- **Gateway/UI**: Bronze `messageType` filter accepts `CCDA_XML`; UI label "C-CDA XML".
- Live 2026-11-19: smoke 402/402 twice; Kafka stopped → REST 503 + `Retry-After: 5` in ~9 s, nothing stored; 202 once
  back. CHECKLIST rows 37–41. Postman `ingestion-service/postman/phase9d-rest-intake-over-kafka` (7 items).
  **Known 9c race (not fixed)**: smoke section 20's "fuzzy pair both PROVISIONAL" failed once — a debounced pmi-matcher
  run fired between the original (master created) and its twin, confirmed the original MATCHED alone; the next run
  queued the pair (PENDING 0.6190 as expected). Passed on the reruns.
- Migration 26 (applied to the running DB 2026-11-19), see Bronze schema invariants. Tests: ingestion 37, bronze-listener
  32, silver 26, fhir-compliance 279, gateway 56, UI 79; smoke section 22.

### Phase 9d part 2 — every feed into Silver + full generator (done, merged as PR #20)

- **silver-transformer-service** `map/`: `FhirSilverMapper` (Bundle or lone Patient; Patient id = identifier typed MR
  else the first; Condition ICD-10-CM / SNOMED, Observation LOINC (valueQuantity/String/CodeableConcept, range,
  interpretation, effectiveDateTime → issued), MedicationRequest RxNorm (status cancelled→stopped, draft→unknown;
  authoredOn required), AllergyIntolerance (RxNorm/SNOMED else LOCAL, category → 0127, reaction severity else
  criticality); a resource referencing anything but the Bundle's Patient (fullUrl or Patient/id) → FAILED),
  `ClaimsSilverMapper` (commons-csv; required `claim_id, member_id, member_first_name, member_last_name, member_dob,
  member_gender, service_date, icd10_code, billed_amount`, optional `member_zip/phone`; member = patient (source,
  member_id), mrn NULL, never overwrites demographics; ICD-10 condition onset = service date; `SilverRecord.Claim`),
  `CcdaSilverMapper` (secure DOM + XPath, sections by LOINC 11450-4 / 10160-0 / 30954-2 / 48765-2, OIDs ICD-10 .90,
  SNOMED .96, LOINC .1, RxNorm .88; `#id` narrative references resolved; result time = own effectiveTime else the
  organizer's). `SilverWriter.map` dispatches on Bronze message_type; `DEFERRED` removed; claims upsert on (patient,
  claim_id); event payload gains `messageType`, counts `claims`. `store/BronzeBacklogSweeper` (`silver.backlog.*`:
  enabled, initial-delay 15 s, interval 60 s, min-age 2 m, batch 50): PENDING MLLP_KAFKA/REST_KAFKA rows → same
  writer + validator, log `silver.backlog_swept` / `silver.backlog_sweep`, gauge `clinicalharmony.silver.backlog.pending`.
  `Fields` helper; `Hl7SilverMapper.timestamp` now public (C-CDA TS). commons-csv 1.10.0 added (same approved jar).
- **Migration 27** `silver.claims` (patient_id, claim_id, service_date, icd10_code, billed_amount NUMERIC(12,2) ≥ 0,
  raw_message_id; unique (patient_id, claim_id)) — applied to the running DB 2026-11-19.
- **Gateway/UI**: `LineageQueries.SILVER_TABLES` += `claims` (Bronze page silver rows, row owner, lineage loops);
  Silver patient detail rows gain kind `CLAIM` (code = ICD-10, display "Claim <id>", value "billed <amount>", date =
  service date, no FHIR); UI "Claims" card, "stays in Silver (its diagnosis is a condition)". Follow-up found by the user
  while testing (2026-11-23): the Claims card sat under four "None." cards — the Silver patient page now has a **Records
  strip** under the medallion path (count per kind + Findings, non-zero counts jump to `#records-<table>` / `#findings`)
  and empty kinds collapse into one line ("No observations, medications or allergies."); UI tests 84, CHECKLIST row 43.
- **Claim lifecycle** (follow-up, user's idea 2026-11-23: "where is the claim now, who has to act"): **migration 28**
  (applied to the running DB) adds optional `silver.claims` columns `payer_id, payer_name, claim_status` (SUBMITTED /
  ACCEPTED / PENDED / PAID / PARTIALLY_PAID / DENIED / REJECTED / APPEALED / VOIDED), `submitted_date, status_date,
  allowed_amount, paid_amount, patient_responsibility, status_reason` (CARC like CO-50 / PR-1). `ClaimsSilverMapper`
  reads them when present (old files still load, NULLs), strict on the values; `SilverWriter`'s upsert keeps the row with
  the NEWEST status_date (a late older line can't roll PAID back to PENDED). Gateway `read/ClaimLifecycle` derives
  `waitingOn` PAYER (submitted/accepted/pended/appealed) / PROVIDER (rejected, denied CO-*) / PATIENT (denied PR-*, or
  paid with a balance) / NOBODY + `waitingSince`; `SilverRow.claim` carries it all. UI: the Claims card has its own
  columns (claim · payer, status badge + reason in words, waiting on + days, amounts, dates) and the strip says e.g.
  "Claims 3 · 1 rejected, 1 patient owes". Generator: claims lines carry the lifecycle (own RNG per claim id → no other
  byte changed). Tests: silver 48 (mapper 7, integration 13), gateway 61 (ClaimLifecycleTest 4), UI 84, generator 39;
  smoke section 23 +3; CHECKLIST row 51; Postman 9d-part-2 items 3 + 7.
- **data-generator** (see its README): `hl7gen/feeds.py` (FHIR / claims / C-CDA, sources PARTNER_CLINIC /
  CLAIMS_VENDOR_A / COMMUNITY_HIE, ids PC/MB/HI######, sizes count/6, /5, /10; second RNG `"9d-feeds:<seed>:<ns>"`,
  so HL7 bytes = 9c's), `hl7gen/pmi.py` (PatientMatcher port → `expectedPmiOutcome` / `expectedScore`),
  `hl7gen/rest.py` (REST client, 503 + Retry-After retry), cli `--feeds`, `--cross-feed-rate` 0.20,
  `--defect-rate` 0.12, `--reset` (out/state.json namespace), `--trickle --rate --duration`, `--ingestion-url`,
  manifest v2 (`feedRecords`, `crossFeedDuplicates`, `defects`, `sendOrder`). Smoke 18/20 pass `--feeds hl7`.
- Tests: silver 44 (mappers 13, integration 12), gateway 57 (integration 17), UI 80, generator 37.
- Live 2026-11-19: migration 27 applied; the sweeper's first run took the 24 part-1 rows (8 PROCESSED, 16 FAILED as
  expected: 14 pre-9d claims lines without member columns, 2 FHIR Patients without an identifier). **Bug found live**:
  `Map.of(..).get(null)` / `Set.of(..).contains(null)` throw NPE — a C-CDA allergy with no severity was retried and
  dead-lettered; fixed (+ regression test), and the sweeper now catches per row (one poison row used to abort the whole
  sweep); after the fix the sweeper healed the dead-lettered row on its own. Smoke 423/423 twice. Fixtures
  `fhir_valid_patient.json` (MR identifier) and `claims_valid.csv` (member columns) updated to the current formats.
  CHECKLIST rows 42–46; Postman `silver-transformer-service/postman/phase9d-every-feed-into-silver` (8 items).

### bronze-listener-service (Phase 9a — done, merged as PR #15)

Port 8086, package `com.clinicalharmony.bronze`, consumer group `bronze-writer` on
`clinicalharmony.bronze.hl7v2.raw` (`ack-mode: record`, `concurrency: 3`).

- `store/BronzeWriter.store()` — ONE transaction: verify checksum, `hl7/Hl7Inspector` (HAPI 2.3 v25,
  noValidation, thread-local parser; QUARANTINED if it won't parse or MSH-9/MSH-10 empty), `INSERT …
  ON CONFLICT (source_system, payload_checksum) WHERE intake_channel = 'MLLP_KAFKA' DO NOTHING`, and
  for PENDING rows an outbox row. Conflict → DUPLICATE (no event).
- `outbox/OutboxRelay` — `@Scheduled` 500 ms, `FOR UPDATE SKIP LOCKED` batch in id order, send `.get()`,
  stamp `published_at`; stops at the first failure (`attempts`/`last_error`). Event
  `clinicalharmony.bronze.ingested` keeps the input key, adds `causation-id` = input `event-id`.
- `config/KafkaConfig` — `DefaultErrorHandler`: `ExponentialBackOff` 1 s ×2, 3 retries →
  `DeadLetterPublishingRecoverer` to `<topic>.dlt` same partition; `InvalidEventException` (bad JSON,
  no rawPayload, checksum mismatch) is not retryable. Declares `.dlt` + `bronze.ingested` topics.
  Broker auto-create is OFF: every topic is declared by its owner (`NewTopic` beans).
- Bronze rows from here: `intake_channel = MLLP_KAFKA`, `correlation_id`, `kafka_topic/partition/offset`,
  `received_at` = MLLP receipt time from the event.
- Tests (17): inspector, event parsing, relay ordering; `BronzeListenerIntegrationTest` (Testcontainers
  Postgres from `docs/db-init` + `ConfluentKafkaContainer` `confluentinc/cp-kafka:7.6.1`, both via
  `@ServiceConnection` without `@Container`; a background tail consumer must be started in
  `@BeforeEach`, NOT `@BeforeAll` — the containers aren't up before the context).

### silver-transformer-service (Phase 9b — done, merged as PR #17)

Port 8087, package `com.clinicalharmony.silver`, consumer group `silver-transformer` on
`clinicalharmony.bronze.ingested` (`ack-mode: record`, `concurrency: 3`). No business REST API.

- `store/SilverWriter.transform()` — ONE transaction: `SELECT … FOR UPDATE` the Bronze row; not PENDING →
  SKIPPED (no event: idempotency); `hl7/Hl7SilverMapper` (HAPI 2.3 v25 noValidation, thread-local parser,
  walks every segment in order whatever group HAPI put it in) → `SilverRecord`; `MappingException` →
  Bronze FAILED + reason, nothing in Silver. Else: patient upsert on (source_system = **PID-3.4** assigning
  authority, else MSH-4; source_patient_id = PID-3.1) — ADT overwrites demographics, ORU/RDE only insert a
  missing patient; DG1/AL1 matched on (patient, code system, code) (every ADT repeats them); OBX/RXE replaced
  per `raw_message_id`; patient → `PENDING_VALIDATION`; Bronze → PROCESSED; outbox row (key = input key).
- Mapped: `ADT^*` (PID, DG1, AL1), `ORU^R01` (PID, OBX; time OBX-14 → OBR-7 → MSH-7; OBX-7 range, OBX-8 flag),
  `RDE^O11` / `OMP^O09` (PID, ORC-1 → status, ORC-9 → start, RXE-2/3/5/7 or RXO). Anything else → FAILED.
  Strict on identity/code fields (length, ICD-10/SNOMED, LOINC, RxNorm, table 0127/0001/0085/0119 codes),
  descriptive text clipped. Times without zone = UTC.
- `validation/PatientValidator` — AFTER the commit, outside any transaction: builds rules-engine's
  `PatientRecordRequest` from the patient's WHOLE Silver record (incl. allergies, OBX ranges), `RestClient`
  (`JdkClientHttpRequestFactory`, 2 s connect / 10 s read, forwards `X-Correlation-Id`). OK +
  `ontologyValidationAvailable` → VALIDATED and the patient's older open findings (`validated_at` < the call)
  marked resolved (rules-engine/ontology append findings on every run — "latest evaluation wins"). **Race fixed 2026-11-23** (smoke 19 failed 3 checks once): a hospital ADT and a pharmacy RDE for one patient
  ride different partitions (different keys), were transformed in the same ms and validated in parallel — neither run saw
  the other's finding, both stayed open. `validate()` now runs in a transaction holding only
  `pg_advisory_xact_lock(0x5117, patientId)` (one validation per patient, across instances too) and the cutoff is
  `clock_timestamp()` (now() is frozen at tx start, before the lock wait); test
  `concurrentValidationsOfOnePatientLeaveOneOpenFindingPerProblem` (stand-in rules-engine now multi-threaded +
  `rulesDelayMs`) fails without the fix. Silver tests 49; else stays
  PENDING_VALIDATION with `validation_error`. `RevalidationSweeper` (`@Scheduled` 30 s, oldest first, stops at
  the first deferral). rules-engine is NOT a compose `depends_on` (fail-open).
- `outbox/` + `config/KafkaConfig` copied from bronze-listener (silver schema, `silver.*` log events/metrics):
  declares `clinicalharmony.bronze.ingested.dlt` and `clinicalharmony.silver.patient.changed`.
  `InvalidEventException` (bad JSON, no `rawMessageId`, no such Bronze row) → straight to the DLT.
- Live: the first start backfilled every historical `bronze.ingested` event (378 PROCESSED, 7 FAILED = the
  9a hand-written test messages without DOB); generator seed 917 → 90 PROCESSED, 16 medications, 6
  MEDICATION_ALLERGY_CONFLICT; rules-engine stopped → PENDING_VALIDATION, sweeper → VALIDATED once back.
- Tests (25): `Hl7SilverMapperTest` (17), `BronzeIngestedEventTest` (2), `SilverTransformerIntegrationTest` (6:
  Testcontainers Postgres + cp-kafka, stand-in rules-engine on a JDK `HttpServer` that writes findings).
- Gateway follow-ups made in 9b: `LineageQueries.SILVER_TABLES` += `allergies`; the dashboard's Bronze message
  page printed `silver.silver.<table>` (the gateway already sends the schema) — fixed, + a Vitest test (UI 62).
  Patient Timeline's "Name or MRN" box never matched MRNs (found by the user while testing 9b): `PatientQueries.search`
  `q` now also matches, EXACTLY and ignoring case, the `mrn` / `source_patient_id` of any Silver record linked to the
  master (partial MRNs match nothing) — + an integration test + a smoke check in section 16.
  Data Lineage → Bronze messages (also found by the user): `BronzeSummary.hl7MessageType` = MSH-9.1^9.2 derived in SQL
  (`LineageQueries.HL7_TYPE_SQL`, first line only, NULL for FHIR/claims/no MSH-9) + `hl7Type` filter (`[A-Z][A-Z0-9]{2}`,
  matches MSH-9.1); UI: "Any type" → "Any payload format", column Type → Format, new **HL7 type** column + an "Any HL7
  message type" dropdown (ADT/ORU/RDE/OMP) — picking one implies HL7 v2; FHIR JSON/Claims CSV clear + disable it; all in
  the URL. Tests: gateway 48 (web + integration), UI 65, refreshed `bronze-messages.json`/`bronze-message.json`,
  smoke 297, CHECKLIST rows 19–21.

### Phase 9c — Gold over Kafka (done, merged as PR #18)

- **gold-builder** = consumer group `gold-builder` INSIDE fhir-compliance-service (`goldbuilder/` package) on
  `clinicalharmony.silver.patient.changed` (`ack-mode: record`, concurrency 3, max-poll-records 20). `GoldBuilder.build`
  = ONE transaction: `SilverFhirBuildService.buildPatient` (unchanged path: master, resources + validator notes,
  lineage, all via GoldWriter) + `GoldWriter.recordEvent` → `gold.outbox_events` (migration 25; GoldWriter writes the
  outbox row so it stays the only Gold writer — the relay only stamps published_at/attempts). `GoldOutboxRelay` (500 ms,
  copied from silver-transformer) → `clinicalharmony.gold.patient.changed`, key = master id, headers correlation-id +
  causation-id (= silver event-id). Retries 1/2/4 s → `clinicalharmony.silver.patient.changed.dlt`; `InvalidEventException`
  (bad JSON, no silverPatientId, unknown Silver patient) → DLT at once. `gold-builder.enabled` / `GOLD_BUILDER_ENABLED`
  (all its beans are `@ConditionalOnProperty`). `POST /api/fhir/build` still works but emits no event.
- **AllergyIntoleranceResourceBuilder** (silver.allergies → US Core AllergyIntolerance, id `silver-allergy-<n>`):
  clinicalStatus active, no verificationStatus, AL1-2 → type/category, AL1-4 → criticality + reaction.severity, AL1-5 →
  manifestation text; compliant with the real validator. `SilverTable.ALLERGIES`, `SilverClinicalRepository.findAllergies`.
- **Lineage**: `SilverProvenance.bronzeIntakeChannel`; MLLP_KAFKA rows get transformer
  `ingestion-service MLLP → Kafka → bronze-listener-service (HL7V2)` / `silver-transformer-service: Bronze → Silver parse`.
  No new `transformation_step` (a Kafka-triggered build is the same FHIR_MAP / PMI_PROVISIONAL transformation; separate
  steps would have given one resource two lineage rows) — so `LineageQueries.summary()`'s CASE needed no change.
- **pmi-matcher** = consumer group `pmi-matcher` INSIDE patient-index-service (`kafka/` package). The listener only
  `signal`s `MatchRunDebouncer`; `@Scheduled tick` runs `MatchingRunService.runIfIdle` (new; `PmiLock.tryExclusively`,
  no wait) when 5 s quiet since the last event OR 30 s since the first pending one. Busy lock / failed run → batch
  requeued, retry after 30 s. Startup catch-up run when PROVISIONAL masters wait. `triggered_by =
  "kafka:pmi-matcher (<n> events, <m> masters)"`. API runs (`POST /match/run`) now WAIT up to 15 s for the lock (was
  instant 409). Broken event → `clinicalharmony.gold.patient.changed.dlt` (declared by the PMI).
- **Logs**: both services keep their pattern layout (the smoke test greps `[correlation-id]`) with `%kvp` appended, so
  `event="gold.built" masterPatientId="…"` / `event="pmi.run" runId="…"` appear on the line. MDC requestId = the MLLP
  correlation id on Kafka-driven work.
- **Generator**: `--duplicate-rate` 0.10 (`make_duplicates`, `generate_run` returns (messages, duplicates); manifest
  `duplicates[]` with `expectedPmiOutcome`; console prints source + MRN + DOB). IDENTICAL twin = same demographics,
  other facility + MRN → deterministic auto-merge; FUZZY = birth day ±1 (same year/month) + other phone → 0.6190 review.
  Twin ADT sent right after the original's first ADT. Changed every seed's bytes again (vs 9b).
- **FHIR Viewer filters (9c follow-up, found by the user while testing 9c — finding `silver-allergy-165` meant paging
  or guessing that the box searched the id)**: gateway `FhirQueries.search(Filter, …)` + `GET /api/v1/fhir/source-systems`;
  new params `patient` (name contains / exact MRN or source id, the Patient Timeline rule), `origin`
  (`pipeline|silver-seed|api|gold-seed`, from the resource's FHIR_MAP / API_GENERATE / SEED_LOAD lineage row; pipeline =
  steps[0] BRONZE), `sourceSystem` (of the Silver patient the resource references: itself / subject / patient /
  beneficiary = `Patient/silver-patient-N`), `code` (exact coding code or display/text contains, over `code` and
  `medicationCodeableConcept`); default sort now `updated` desc (`dir` defaults desc for updated, asc otherwise); rows gain
  `origin`, `sourceSystem`, `sourcePatientId`. UI: labelled filter row (Patient, Source system, Origin, US Core, Code or
  name, Resource id contains) + hint "id = the part after the slash", Patient + Origin columns, Clear filters, all in the
  URL. Deferred (user agreed): a per-resource "why compliant" checklist of required / must-support elements recorded at
  validation time. Tests: gateway 50, UI 66 (fixtures `fhir-resources.json` re-captured, `fhir-source-systems.json` new),
  smoke +9 in section 20 (333 total), CHECKLIST rows 22–24 (16 reworded).
- **Live**: the first start replayed all 680 historical silver events → 680 gold events, 73 AllergyIntolerance (all
  compliant), and exactly ONE debounced PMI run (waited 14.8 s).
- **Tests**: fhir-compliance 276 (new: AllergyIntolerance builder 2, event parse 3, Kafka lineage 1,
  `GoldBuilderIntegrationTest` 2 — the shared `AbstractIntegrationTest` now also starts cp-kafka 7.6.1 via
  `@ServiceConnection`), PMI 64 (`MatchRunDebouncerTest` 5 on a hand-moved clock, event parse 2,
  `PmiMatcherIntegrationTest` 1: burst → one run; `PatientIndexIntegrationTest` runs with `pmi-matcher.enabled=false`),
  generator 18. Smoke section 20; Postman `fhir-compliance-service/postman/phase9c-gold-over-kafka` (8 items).

### data-generator (Phase 9a v1 — done, merged as PR #15; RDE^O11 added in 9b)

Python 3.9+ stdlib only, run on the host: `python3 data-generator/generate.py [--count 100] [--seed 42]
[--malformed-rate 0.10] [--no-send] [--send-dir DIR]`. Writes `data-generator/out/seed-<n>/*.hl7` +
`manifest.json` (git-ignored), then sends over MLLP with retry on AE/timeout. Same seed → same bytes.
Exactly round(count × rate) malformed, cycling MISSING_MSH / NOT_HL7 / EMPTY_CONTROL_ID /
EMPTY_MESSAGE_TYPE / TRUNCATED_HEADER. Codes from the db-init terminology subsets. 15 unittest tests
(`cd data-generator && python3 -m unittest discover -s tests -t .`).
9b: later messages are ORU 55% / **RDE^O11 20%** / A08 25%. RDE comes from `CITY_PHARMACY` (`RX_SYSTEM`):
ORC (NW, ORC-9, ORC-12) + RXE (RxNorm ingredient from db-init 09, dose, instructions) + RXR; PID-3.4 stays
the home facility. Half the orders of a patient with an AL1 allergy are for the allergen (planted
MEDICATION_ALLERGY_CONFLICT). Changing the mix changed every seed's bytes vs 9a.

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
- **Tests (56)**: unit (matcher seed scenarios at exact scores, blocking, run/review services),
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
- **Tests (55)**: `DownstreamClientTest` (JDK `HttpServer` stub with delays: token, correlation id,
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
- **Tests (78, Vitest + jsdom + Testing Library + MSW)**: the MSW gateway stub serves REAL responses
  captured from the live gateway (`test/fixtures/`), and any unhandled request fails the test.
  No Playwright (2 GB image); instead smoke section 17 + `dashboard-ui/CHECKLIST.md` (12-step manual
  click-through, replaces a Postman collection for this phase).

### Bronze schema invariants

`bronze.raw_messages` CHECK constraints that Java code must match:
- `message_type`: `HL7V2`, `FHIR_JSON`, `CLAIMS_CSV`, `CCDA_XML` (9d)
- `message_format`: `TEXT`, `JSON`, `CSV`, `XML` (9d)
- `processing_status`: `PENDING`, `PROCESSING`, `PROCESSED`, `FAILED`, `QUARANTINED`
- `intake_channel`: `REST` (pre-9d direct writes), `MLLP_KAFKA`, `REST_KAFKA` (9d)

Phase 9d follow-up (`docs/db-init/29`, idempotent UPDATE, nothing deleted): the 226 legacy pre-9d REST rows (`intake_channel
= 'REST'`) that sat PENDING forever (nothing transforms them; the sweeper only takes Kafka-channel rows) are FAILED with
the reason "Legacy row: written straight to Bronze by the pre-Phase 9d REST intake … Resend the payload …". The Bronze
page says "Why it failed:" (vs "Quarantine reason:") and Intake "REST, written directly (before Phase 9d)". Smoke 23
now checks no legacy row is PENDING.

Phase 9d follow-up (`docs/db-init/28`, non-destructive + idempotent, applied to the running container): claim lifecycle
columns on `silver.claims` + CHECKs `chk_claims_claim_status` / `chk_claims_lifecycle_amounts` (see 9d part 2 above).

Phase 9d part 1 (`docs/db-init/26`, non-destructive + idempotent, applied to the running container): the three CHECKs
above widened; unique index `uq_raw_messages_kafka_intake_source_checksum` on (source_system, payload_checksum) WHERE
intake_channel IN ('MLLP_KAFKA','REST_KAFKA') replaces 9a's MLLP-only one (legacy REST rows stay outside it).

Phase 9c (`docs/db-init/25`, non-destructive + idempotent, applied to the running container): `gold.outbox_events`
(same shape as bronze/silver's; rows written by GoldWriter, relayed by fhir-compliance-service's `GoldOutboxRelay`) +
grant to the gateway's read-only role. No other schema change.

Phase 9b (`docs/db-init/24`, non-destructive + idempotent, applied to the running container):
`silver.allergies` (code_system RXNORM/SNOMED-CT/LOCAL, allergy_type table 0127, severity SV/MO/MI/U, unique
(patient, code_system, code)); `validation_reports` entity_type += `ALLERGY`; `silver.patients` +=
`validation_status` (PENDING_VALIDATION/VALIDATED, NULL = seed rows), `validation_requested_at`,
`validated_at`, `validation_attempts`, `validation_error`; `silver.observations` += `reference_low/high`,
`abnormal_flag`; `raw_message_id` indexes; `silver.outbox_events` (same shape as bronze's).

Phase 9a (`docs/db-init/23`, non-destructive, applied to the running container): `intake_channel`
(`REST` default / `MLLP_KAFKA`, CHECK), `correlation_id`, `kafka_topic`, `kafka_partition`,
`kafka_offset`; partial unique index `uq_raw_messages_kafka_source_checksum` on (source_system,
payload_checksum) WHERE intake_channel = 'MLLP_KAFKA' (REST rows stay non-unique — the smoke test
posts the same fixture every run; 9d widens it when REST moves onto Kafka); table
`bronze.outbox_events`. The gateway's read-only role sees it via default privileges.

Since 9d only bronze-listener-service writes `bronze.raw_messages` (plain `JdbcTemplate`, fully-qualified table names).
Services writing to `silver.*` or `gold.*` qualify table names explicitly (or set their own `hikari.schema`).

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
- **Kafka tests**: Testcontainers `org.testcontainers.kafka.ConfluentKafkaContainer` with the compose
  image `confluentinc/cp-kafka:7.6.1` (KRaft) — no new image. ingestion-service's publisher test uses
  it without Spring; bronze-listener / silver-transformer / fhir-compliance (shared `AbstractIntegrationTest`) /
  patient-index (`PmiMatcherIntegrationTest`) via `@ServiceConnection`. The topic the service consumes but doesn't own
  is declared by a `@TestConfiguration` `NewTopic` in the test.
- **UI tests** (dashboard-ui, `test/*.test.ts(x)`): Vitest + jsdom + Testing Library against an MSW gateway stub serving real captured `/api/v1` responses; run in `node:24-alpine` via `docker run` (no host Node).
- **Black-box smoke test** (`scripts/smoke-test.sh`): once the stack is up (`docker-compose up -d`), this hits all four implemented services' REST endpoints over HTTP and asserts on real responses — accept/quarantine outcomes, ontology validation results, all 7 rule-engine evaluations and live rule-toggle behavior, US Core structural validation (including a `requiredAnyOf` choice-type check), and FHIR generation round-tripped back through validation to cross-check the generator against the validator. Fixture payloads live in `scripts/fixtures/`. This replaces the earlier "verified manually" note — it's now a repeatable, scriptable check (474 assertions as of the Phase 10 list controls, section 16 +7; 467 before — section 20 +16: checklist 2, FHIR Viewer per-type filters / lab flags / allergies 14; 453 after the checklist — section 20 +2 for the "why compliant" checklist; the legacy-REST-rows check in section 23 now also holds on a wiped DB with 0 legacy rows (it failed once after the 2026-11-24 `down -v`); clean run 2026-11-24: 452 + fix, then 453/453; 451 assertions as of PR #20 — 9d follow-ups: claim lifecycle, dashboard ↔ Splunk links, legacy rows closed, RDE check PROCESSED-only; 444 as of Phase 9e — section 24 = Splunk: HEC, app, indexes, dashboards, alerts provisioned, logs from all 8 services, a correlation-id trace, metrics + consumer lag + health checks, and each of the three alerts made to fire (~15 min per run); 423 as of 9d part 2 — section 23 = the full generator: every feed PROCESSED into Silver and Gold, cross-feed duplicates at their expected PMI outcome, planted defects as open findings, backlog sweeper, --reset, --trickle; sections 18/20 run the generator with `--feeds hl7`; section 22 = REST intake over Kafka for all four formats, run-unique payloads; sections 1–3 expect 202/422 — section 20 = Gold over Kafka: a random seed guaranteed to hold one IDENTICAL and one FUZZY cross-facility duplicate, every Silver patient built into Gold with no manual build, one resource per Silver row, AllergyIntolerance compliant, lineage from the raw HL7 via Kafka, one gold event per silver event (causation + correlation), the debounced pmi-matcher run, identical pair auto-merged / fuzzy pair PENDING at 0.6190 (also via the gateway), a broken event → DLT, log events, Kafbat groups; section 18 runs the generator with a random seed over MLLP and checks Bronze/outbox/idempotency/Kafbat, expecting valid rows PROCESSED since 9b; section 19 = Silver: rows mapped, PID-3.4 patient keying, one silver event per row, a hand-sent allergy + conflicting order flagged against real Silver ids, an ADT without DOB → FAILED, both via the gateway; it fetches a Bearer token from the compose Keycloak for fhir-compliance-service and patient-index-service calls; section 15 = PMI, which inserts fresh random synthetic patients each run so it stays rerunnable; section 16 = api-gateway, with real dana/vic user tokens from the dev direct-grant client; section 17 = dashboard-ui's nginx: SPA fallback, caching, CSP/headers, runtime config, Keycloak accepting the dashboard's PKCE request and refusing it without PKCE or with a foreign redirect, CORS). Gold-layer checks with no HTTP surface yet (lineage, masters) use `check_sql` via `docker exec` into `clinicalharmony-postgres`.

## Local Docker environment

**Port conflicts**: an unrelated "medistream" project auto-starts containers on ports 5432, 3306, 9092, 2181, and 8083 whenever Docker Desktop restarts. Do not stop or reconfigure those containers without asking (on 2026-11-14 the user approved `docker stop` of all six medistream containers to run the stack — ask again each time). ClinicalHarmony's Kafka is on host 19092 (KRaft, no Zookeeper) to avoid 9092/2181, but Postgres 5432 and rules-engine 8083 still collide. Use a throwaway `docker network create` + `docker run` for isolated local verification rather than `docker-compose.override.yml` (Compose merges `ports:` by concatenation, so overrides can't replace conflicting mappings).

**Apple Silicon image constraint**: `eclipse-temurin:*-alpine` tags have no arm64 manifest and will fail to pull on this machine. Always use the Debian-based tag (e.g. `eclipse-temurin:17-jre`) for all service Dockerfiles. This applies to Phase 3 onward, not just ingestion-service. (The `node:*-alpine` and `nginx:*-alpine` images used by dashboard-ui DO have arm64 manifests — the problem is specific to eclipse-temurin.)

**Teardown with the Splunk log driver (found 2026-11-24)**: every service logs through Docker's `splunk` driver. If
Splunk stops BEFORE the services (as `docker compose down` may do), a service with buffered logs can hang on stop while
its driver retries the flush — `docker stop`, `docker kill` and `docker logs` all block, and the daemon can wedge
(the clean-run wipe needed a Docker Desktop restart). Stop the services first, Splunk last:
`docker compose stop $(docker compose config --services | grep -v -e splunk -e otel-collector)` then `docker compose down
[-v]`. Remember `down -v` removes ALL three volumes: postgres-data, kafka-data AND splunk-var (Splunk's indexed history).

**Network desync after multiple daemon restarts**: after several `open -a Docker` restarts in one session, a container's `HostConfig.NetworkMode` can say it's on `clinicalharmony_clinicalharmony-net` while `NetworkSettings.Networks` is actually empty (`{}`) — other containers then get `UnknownHostException` resolving it by service name, even though the container itself is `Up`/healthy. `docker network connect` and `docker restart` on just the affected container do **not** reliably fix this. The reliable fix: `docker compose down` (no `-v`, volume/data untouched) then `docker compose up -d` again — cheap since it doesn't touch the Postgres volume, and fully resets the network.

## Build order / phase plan

Phases are meant to be fully complete before the next starts. Current state as of 2026-11-23 (Phase 9 complete: 9d parts 1 + 2
and 9e merged as PR #20 from branch `phase-9d-full-generator`, smoke 451/451; Phase 10 next):

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
- **Phase 9** (done — 9a–9c PRs #15/#17/#18, 9d + 9e PR #20; planned 2026-11-13): event-driven pipeline on Kafka + data-generator, one layer
  at a time, each its own branch/PR (details in `docs/build-history.md`):
  - Full per-layer design (topics, consumer groups, writes, partition keys, idempotency, outbox, DLTs,
    log events, metrics, done-when) is `docs/architecture/event-pipeline.md` — keep
    it and the code in sync. Topic naming `clinicalharmony.<layer>.<entity>.<event>`, `<topic>.dlt`.
  - **9a** (done, merged as PR #15 on 2026-11-16, branch `data-generator`)
    Bronze: generator v1 (host python3, 100 seeded HL7 over MLLP) → ingestion-service publishes
    `clinicalharmony.bronze.hl7v2.raw` (ACK AA only after `acks=all`, AE otherwise) → bronze-listener-service
    (group `bronze-writer`, port 8086) → `bronze.raw_messages` idempotently (migration 23 partial unique
    index), QUARANTINED kept, retries → `.dlt` → outbox → `clinicalharmony.bronze.ingested`. Kafka moved to
    KRaft on host 19092 (Zookeeper removed); Kafbat UI `kafbat/kafka-ui:v1.5.0` on 8090 (read-only).
    User decisions at scoping (all recommended): KRaft/19092; Boot-managed spring-kafka 3.3.16 + kafka-clients
    3.9.2 (downloaded with approval); Testcontainers `kafka` module with the local cp-kafka image; host python3;
    outbox + relay in the listener; partial unique index scoped to MLLP_KAFKA rows; name/port/profiles as above.
    Verified live: 100 → 90 PENDING + 10 QUARANTINED, no duplicates after `docker restart` and `docker kill`
    mid-run, same-seed resend → 100 duplicates skipped, Kafka stopped → AE then AA once back, rows in the
    dashboard's Data Lineage → Bronze; smoke 263/263 twice.
  - **9b** (done, merged as PR #17 on 2026-11-17, branch `phase-9b-silver-transformer`): silver-transformer-service, see its
    section above. Migration 24, generator RDE^O11, smoke 297/297 (twice), Postman
    `silver-transformer-service/postman/phase9b-silver-over-kafka` (6 items). Patient Timeline still lists
    Gold masters only, so generated patients appear there after `POST /api/fhir/build` until 9c.
  - **9c** (done, merged as PR #18 on 2026-11-18, branch `phase-9c-gold-over-kafka`, migration 25 applied to the running DB; see the
    9c section above) Gold: consumer `gold-builder` INSIDE fhir-compliance-service (GoldWriter stays the only Gold
    writer) → US Core build + validator → Gold + lineage BRONZE→SILVER→GOLD →
    `clinicalharmony.gold.patient.changed` → `pmi-matcher` inside patient-index-service (batched runs).
    **9c decisions** (settled 2026-11-17, all recommended options): gold-builder also builds US Core
    **AllergyIntolerance** from `silver.allergies`; the generator registers a small share (~10%) of patients at
    BOTH CITY_HOSPITAL and NORTH_CLINIC (different MRNs, some identical → auto-merge, some fuzzy → review queue);
    `pmi-matcher` **debounces** gold.patient.changed events — one run 5 s after the last event, at most every 30 s
    in a burst; the Data Lineage layer browser (Bronze/Silver/Gold cards as tabs + linked detail pages) is its
    OWN branch after 9c. Kafka deps = Boot-managed spring-kafka 3.3.16 / kafka-clients 3.9.2, already in ~/.m2.
  - **9d** (done, merged as PR #20 on 2026-11-23, together with 9e) Full generator (FHIR / claims / C-CDA, cross-feed duplicates, defects, `--seed`/`--reset`/`--trickle`).
    Branch `phase-9d-full-generator`. Part 1 = intake on Kafka, part 2 = Silver mapping + generator feeds.
    **9d part 1 decisions** (settled 2026-11-19, all recommended options): REST intake (HL7 / FHIR JSON / claims CSV +
    new C-CDA) only PUBLISHES to Kafka — same syntax check as before, valid → **202** with correlationId + eventId (no
    Bronze id), malformed → **422 AND still published** (the listener quarantines it, never dropped), Kafka down →
    **503 + Retry-After**; a claims file is ONE event, **bronze-listener splits it** into per-line rows in one DB
    transaction (all-or-nothing stays a DB transaction); new `intake_channel = REST_KAFKA`, the unique index covers
    MLLP_KAFKA + REST_KAFKA, **legacy REST rows kept** (smoke makes each run's REST fixtures unique); **C-CDA is a Bronze
    format** (`POST /api/ingestion/ccda`, message_type `CCDA_XML`, format `XML`), mapped to Silver in part 2.
    **9d part 2 decisions** (settled 2026-11-19, all recommended options): claims CSV gains member columns
    (`member_first_name/last_name/dob/gender`) → patient upsert on (source, member_id), ICD-10 → `silver.conditions`, the
    line → new `silver.claims`; a line without them → Bronze FAILED. FHIR: a Bundle (or lone Patient) maps — Patient
    (source = X-Source-System, id = MR identifier else the first) + Condition / Observation / MedicationRequest /
    AllergyIntolerance referencing it; a clinical resource with no Patient in the Bundle → FAILED. Backlog: a
    `@Scheduled` Bronze sweeper in silver-transformer (startup + 60 s) transforms MLLP_KAFKA / REST_KAFKA rows still
    PENDING after 2 min (legacy REST rows untouched). Generator: ~20% cross-feed duplicates (HL7 + one of FHIR / claims /
    C-CDA, other MRN) with variation EXACT / NICKNAME / TYPO / SWAPPED_DATE and the expected PMI outcome from the
    PMI_MATCH weights in the manifest; ~10–15% rule-targeted defects (unknown ICD-10/LOINC/RxNorm, lab out of range,
    pregnancy on a male, diagnosis dated in the future — DIAGNOSIS_DATE_SANITY checks future onsets, not
    before-birth ones, found while building — + the med–allergy conflicts), listed in the manifest; `--reset` = fresh
    MRN/claim namespace in `out/state.json` (nothing deleted); `--trickle` = the same set at `--rate`/s (default 1),
    `--duration` N s, Ctrl-C safe.
  - **9e** (done, merged as PR #20 on 2026-11-23; smoke section 24) Observability. **Decisions** (settled 2026-11-19, all recommended options): **Splunk Enterprise in a local
    container** (no account, nothing leaves the laptop; 60-day trial license has alerting, the Free license after it
    doesn't); metrics via the **OpenTelemetry Collector** (services export Micrometer meters over OTLP; the collector also
    reads Kafka consumer lag itself and forwards to Splunk) — vendor-neutral; **every service logs the same Logstash JSON**
    (ontology, rules-engine, fhir-compliance, patient-index, api-gateway switched; smoke greps updated); **alerts stay inside
    Splunk** (triggered-alerts list + dashboard panel; nothing sent out).
  - **Data Lineage layer browser** (done, merged as PR #19 on 2026-11-18, branch `lineage-layer-browser`; the user's idea from 9b
    testing, design agreed 2026-11-17: cards-as-tabs + one full-width list + linked detail pages, NOT 3 side-by-side tables).
    Gateway: new `read/SilverQueries` (`/api/v1/silver/patients` list — newest first, `q` = name part / exact MRN or source
    id / Silver id, `sourceSystem`, `validationStatus` VALIDATED|PENDING_VALIDATION|NOT_TRACKED; `/silver/patients/{id}` —
    Bronze messages, every condition/observation/medication/allergy with its Gold FHIR resource via the FHIR_MAP lineage
    row, findings, master; `/silver/source-systems`; `/silver/rows/{table}/{id}` → its patient), `/lineage` also takes
    `silverPatientId` / `bronzeMessageId` (exactly one of four), Bronze detail `silverRows[].silverPatientId`, `/summary`
    `silverByValidation` + `silver.allergies`, `MasterSummary.fhirResourceCount`. Gotcha: a Java text block strips leading
    spaces, so SQL fragments concatenated after another (`FROM`, `LEFT JOIN LATERAL`) need an explicit `" " +`.
    UI: `LineagePage` (trace with 4 kinds, `LayerTabs` from `/summary`, Bronze/Silver/Gold cards, `?layer=`),
    `SilverPatientPage` (path strip, demographics, validation, Bronze messages, rows per kind with Bronze + FHIR links,
    findings linking to their row `#<table>-<id>`), `SilverRowRedirect` (`/lineage/silver/:table/:rowId`), `refLink` links
    silver.* and gold.fhir_resources refs, Bronze page Silver rows + "Trace this message", patient sources link to Silver,
    Overview Bronze/Silver cards → the tabs. Follow-ups found by the user while testing: the trace box accepts an MRN for
    the Silver / master kinds (exact match via `/silver/patients?q=`; several sources → pick one; it used to strip the
    letters silently and 404 on the digits), and "Transformations recorded" says it's pipeline order (not by date) with
    Records / Last sortable client-side; Bronze list gains a **Patient** column + search (`/bronze/messages?q=` = PID name part
    or the Silver patient's, exact PID-3.1 MRN, or message id): `BronzeSummary.patientName/patientMrn/silverPatientId` parsed
    from the PID segment in SQL (`BRONZE_FROM` lateral: first PID-3 repetition, source = PID-3.4 else MSH-4 — the
    transformer's patient key), shown on the Bronze message page too. Tests: gateway 55, UI 78 (fixtures silver-patients / silver-patient /
    silver-source-systems / lineage-silver new, summary re-captured), smoke 360/360 (twice) incl. new section 21, CHECKLIST rows 25–36 (18 reworded).
  - **Dashboard ordering fixes** (done, merged as PR #16 on 2026-11-17, branch `pmi-review-recent-decisions`):
    patient-index `MatchCandidateRepository.list(status, runId, limit)`: PENDING `score DESC, id`, every
    other view `COALESCE(reviewed_at, created_at) DESC, id DESC`; pseudo-statuses `DECIDED` (ACCEPTED +
    REJECTED) and `QUEUED` (all but AUTO_MERGED) + optional `runId`, passed through by the gateway's
    `/api/v1/pmi/candidates`. Gateway `FhirQueries`: natural `fhir_id` order (text prefix, then trailing
    number as numeric) + whitelisted `sort=resource|master|compliance|updated` & `dir=asc|desc`;
    `LineageQueries.summary()` CASE-ranked pipeline order (FHIR_MAP, PMI_PROVISIONAL, PMI_MATCH, PMI_MERGE,
    PMI_UNMERGE, SEED_LOAD, API_GENERATE; unknown steps last — add 9b/9c steps to the CASE). UI: "Recently
    decided" card (`status=DECIDED&limit=10`), run counts link to `/pmi?status=AUTO_MERGED|QUEUED&run=<id>`
    (chip clears it), sortable FHIR headers (`aria-sort`), `JsonView downloadName` → Blob download
    (`application/fhir+json`). Tests: PMI 56, gateway 45, UI 61; smoke 271 (8 new in section 16).
  - **9b decisions** (settled 2026-11-17, branch `phase-9b-silver-transformer`, all recommended options):
    AL1 → new `silver.allergies` (migration 24), passed to rules-engine so `MEDICATION_ALLERGY_CONFLICT` runs on
    real data (FHIR AllergyIntolerance waits for 9c); the generator gains RDE^O11 pharmacy orders (ORC + RXE,
    RxNorm from the db-init subset, some conflicting with the patient's AL1) in 9b; new service
    `silver-transformer-service` on port 8087, group `silver-transformer`, package `com.clinicalharmony.silver`;
    rules-engine down → Silver still written with `silver.patients.validation_status = PENDING_VALIDATION`, and a
    `@Scheduled` sweeper in the transformer re-validates (~30 s) — no DLT for that case.
- **Phase 10**: Polish, final Docker test, interview demo script, the draw.io diagrams listed in `docs/diagrams/README.md`.
  - **Decisions (2026-11-24, all recommended)**: build the FHIR Viewer "why compliant" checklist (done, below); stay on
    Boot 3.5.16 and write a Boot 4 upgrade-path note; IntelliJ uses system `/usr/bin/python3` 3.9.6, no venv.
  - **README trimmed (2026-11-24)**: roadmap → `docs/build-history.md`, Phase 9 design → `docs/architecture/
    event-pipeline.md`, curl examples → `docs/try-it.md`, diagram to-do → `docs/diagrams/README.md`; architecture is a
    Mermaid stage-card diagram (GitHub renders it).
  - **"Why compliant" checklist**: `validation/ProfileChecklistBuilder` reads the profile's snapshot from the SAME
    validation-support chain (cached per profile) and lists depth-1 elements + their children that are required
    (min ≥ 1), must-support, or an Additional USCDI Requirement (`uscdi-requirement` extension — US Core 6.1 race /
    ethnicity / telecom are USCDI, NOT must-support), plus extension slices (by URL) and CodeableConcept pattern slices;
    presence is evaluated on the raw JSON (`_element` counts). `FhirValidationOutcome.checklist` →
    `compliance_notes.checklist` (also in `/api/fhir/validate`); gateway passes it through unchanged; UI
    `views/fhir/Checklist.tsx` card "Why it is compliant" / "What it was checked for". Tests: fhir-compliance
    `ProfileChecklistBuilderTest` (6, real US Core snapshots; fhir-compliance 283 total), UI 88; smoke section 20 +2; CHECKLIST row 54.
  - **FHIR Viewer per-type filters** (user's request 2026-11-24, after testing the checklist): gateway
    `read/FhirAttributes` = a FIXED catalog per resource type (Patient gender / birthDate / address state, city,
    postalCode / has phone; Observation status / category / interpretation / abnormal / has referenceRange / effective;
    Condition clinical + verification status / category / code system / onset; MedicationRequest status / intent /
    authoredOn; AllergyIntolerance clinicalStatus / type / category / criticality; CMS types status or active), kinds
    CHOICE (exact) / TEXT (contains) / DATE (`attr.<key>From/To`, `YYYY[-MM[-DD]]` prefix compare) / FLAG (yes|no); SQL
    lives in the catalog, values are bound; unknown key / no type / bad date → `InvalidFilterException` → 400.
    `GET /api/v1/fhir/attributes?type=` returns the catalog + CHOICE values present in Gold with counts;
    `/fhir/resources` takes `attr.*`. UI `views/fhir/AttributeFilters.tsx`: a "<Type> attributes" row (dropdowns at
    once, text/date boxes on Search), changing type drops `attr.*`. Left out on purpose (our sources never send them):
    encounter, performer, generalPractitioner, maritalStatus, deceased, contact, race.
    **Gap found + fixed**: Gold Observations had no `interpretation` / `referenceRange` although Silver holds OBX-8 /
    OBX-7 (516 of 522 flagged) — `ObservationResourceBuilder` now maps both (v3 ObservationInterpretation for
    N/H/L/HH/LL/A/AA, any other flag text-only; range in the result's UCUM unit), validator-clean. Resources built
    earlier change only when their patient is rebuilt. Tests: fhir-compliance +2 (`ObservationResourceBuilderTest` 6),
    gateway 67 (`FhirAttributesTest` 4, web +1, integration +1), UI 90 (fixture `fhir-attributes-patient.json` from the
    live DB; the sort test no longer depends on test order); smoke section 20 +8 (461); CHECKLIST row 55.
  - **Showing what the filters match** (user's follow-up 2026-11-24: "Criticality low → #346, but nothing on its page
    says low"): the patient page read conditions / observations / medications only — allergies appeared nowhere and lab
    flags weren't shown. Gateway: `MasterDetail.allergies` (every linked Silver allergy + its Gold AllergyIntolerance via
    the FHIR_MAP lineage row: clinicalStatus, **criticality**, reaction severity; high first), `TimelineEvent`
    `abnormalFlag` / `referenceLow` / `referenceHigh` (observations; `trim_scale` in SQL — `stripTrailingZeros()` would
    serialize 200 as `2E+2`), FHIR list rows `attributes` = the type's catalog values (same SQL as the filters; dates to
    the day, flags yes/no, absent left out; empty without a type). UI: **Allergies** card (criticality badge, "AL1-4 SV
    (severe)" source severity, Bronze + FHIR links), flag badge + "ref low–high" on timeline labs, **Key attributes**
    column (filtered values bold), US Core no-filter option renamed **Any**. Tests: gateway 68, UI 94; smoke section 20
    +6 (467); CHECKLIST rows 56–57.
  - **Gold masters list: MERGED filter** (found by the user in the CHECKLIST walk, row 31, 2026-11-26): the Data Lineage
    Gold card's status dropdown lacked MERGED (the gateway already took it). Added it; `MasterSummary.mergedIntoMasterId`
    (live merge's survivor, same rule as the detail page) → Sources reads "merged into #N"; "Any" = active masters only,
    said in the card's note. Tests: gateway 69 (integration +1), UI 95. CHECKLIST rows 32–53 re-pointed at post-wipe ids.
  - **FHIR Viewer type totals** (user's idea, same day): the Resource types list is a table Type · Compliant · Not ·
    Total with a Total row (sums of `/fhir/resource-types`, = the Lineage Gold card's resource count); a non-zero Not is a
    link to that type + `compliant=false`; a "Gold totals" line under the heading shows the selected type's (or all)
    numbers. UI only. Tests: UI 96. CHECKLIST row 58.
  - **Trace kind follows the layer card** (user, same day): `TraceCard` defaults its kind to the selected layer's
    (bronze → bronzeMessageId, silver → silverPatientId, gold → masterPatientId) instead of always Silver; while a trace
    is in the URL it keeps that trace's kind. Tests: UI 98 (2 new; 2 MRN tests now start on `?layer=silver`).
  - **List controls on every paged list** (user, same day, finding Bronze #996 among 173 claims rows; rule: a list fix
    goes to EVERY list): gateway `read/ListWindow(idFrom, idTo, order)` on `/bronze/messages`, `/silver/patients`,
    `/patients` (orders `name|newest|oldest`, default name) and `/validation/findings` (`newest|oldest`): range on the
    list's own id (reversed = swapped, `@Min(1)`), `timeOrder()` on its time column. Bronze `own` lateral: a FHIR /
    claims / C-CDA row (no PID) shows + is searchable by the Silver patient it built (patient row, else a claim /
    condition / observation it wrote). UI `components/common.tsx`: `Pager` (ids on screen via `idOf`, Rows 15/25/50/100,
    First / Previous / go-to-page / Next / Last), `WindowFilter` (from / to / Apply / Clear range / Order),
    `readWindow` / `windowKeys(prefix)` — URL keys `idFrom/idTo/order/size` (Bronze `bronzeFrom/…`); Validation findings
    gain a Finding # column; FHIR Viewer gets rows per page + ids on screen only (text ids). Follow-up: `Page.minId/maxId`
    (`Sql.count` = count + min + max of the list's id over the filtered result) → `ListSummary` under each list's title
    next to its search box (one line with the title: "Bronze messages | Total # messages: 1,039 | Range: #1 (oldest) → #1148 (newest)";
    ids have gaps — ON CONFLICT DO NOTHING still burns a sequence value — so count < span; on-screen ids stay in the pager); newest / oldest = ORDER BY the list's own id (`ListWindow.idOrder`), not a time column — Bronze rows from
    parallel partitions were received out of number order, so a page read #1141, #1117, #1148 … (user, 2026-11-26); search boxes `.search-box`
    (min 300 px, whole hint visible); a `#996` search = message / Silver id 996. UI 107. Tests: gateway 71
    (web +1, integration +1), UI 105 (+7, 107 with the summary); smoke section 16 +7 (474, not yet run); CHECKLIST row 59 (+ rows 34, 40).
  - PMI Review run counts (found by the user in the CHECKLIST walk, row 15): clicking a run's To review / Auto-merged
    number DID filter the queue, but the queue is the top card and the runs table the bottom one, so it changed off
    screen. The link now scrolls the queue into view and focuses its heading (UI test extended).
