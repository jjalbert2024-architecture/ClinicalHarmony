# ClinicalHarmony

**A FHIR-compliant Clinical Data Harmonization & Ontology Validation Platform.**

ClinicalHarmony takes patient data from the formats US healthcare actually exchanges —
**HL7 v2** messages, **FHIR R4** JSON, **C-CDA** documents, and **claims CSV** extracts — and
turns it into trustworthy, standards-conformant FHIR:

1. **Ingest** every payload, valid or not, into an immutable raw layer (nothing is silently dropped).
2. **Validate** every clinical code against the reference terminologies (ICD-10-CM, SNOMED CT,
   LOINC, RxNorm) and evaluate clinical plausibility rules.
3. **Conform** the data to **US Core 6.1.0** FHIR resources, checked by the official HL7 validator.
4. **Resolve** duplicate patients across source systems into a master patient index.
5. **Expose** it through payer/provider interoperability APIs (CMS-0057-F, SMART on FHIR) and a
   React dashboard covering patient timelines, ontology coverage, validation findings, raw FHIR
   and end-to-end data lineage.

It models the clinical interoperability and data-quality tooling that health systems and payers
need as they adopt FHIR at scale. The guiding rule throughout: **the pipeline reports data-quality
problems, it never hides them** — malformed input is quarantined rather than dropped, and gaps are
never papered over with invented values, so a non-compliant resource is flagged with the exact
reason instead of quietly "fixed".

> **Status (2026-11-13):** Phases 1–8 and all four Phase 5b slices are done (Phase 8, the React
> dashboard, merged as PR #14). Next is Phase 9, the synthetic data generator. Six services
> are live — ingestion, ontology, rules engine, FHIR compliance, patient index and the API gateway
> in front of them — plus Keycloak as the authorization server, and the dashboard on
> `localhost:3000`. See the [Roadmap](#roadmap).

## Architecture

A Bronze → Silver → Gold **medallion** pipeline on PostgreSQL, with one Spring Boot service per
stage, orchestrated by Docker Compose.

```
  SOURCES          HL7 v2 (MLLP / REST)   FHIR R4 JSON   Claims CSV           C-CDA R2.1
                            │                  │              │                    │
                            ▼                  ▼              ▼                    │
  INGEST            ┌─────────────────────────────────────────────┐                │
                    │ ingestion-service              ✅ Phase 2   │                │
                    └─────────────────────┬───────────────────────┘                │
                                          ▼                                        │
  BRONZE            bronze.raw_messages  (every payload + SHA-256; bad → QUARANTINED)
                                          │
                                          ▼  (Silver parsing: not yet built)       │
  SILVER            silver.patients · conditions · observations · medications       │
                                          │                                        │
  VALIDATE          ┌─────────────────────┴───────────────────────┐                │
                    │ ontology-service   ICD-10 · SNOMED · LOINC ·  ✅ Phase 3       │
                    │                    RxNorm + concept mapping │                │
                    │ rules-engine-service  7 clinical rules        ✅ Phase 4       │
                    └─────────────────────┬───────────────────────┘                │
                                          ▼                                        ▼
                    silver.validation_reports (findings, by rule)
                                          │
  CONFORM           ┌─────────────────────┴──────────────────────────────────────────┐
                    │ fhir-compliance-service   HL7 validator vs US Core 6.1.0   ✅ 5 / 5b │
                    │   Silver → FHIR builders · C-CDA → FHIR Bundle · debugger API    │
                    │   GoldWriter: provisional masters + lineage (every Gold write)   │
                    └─────────────────────┬──────────────────────────────────────────┘
                                          ▼
  RESOLVE           patient-index-service  (match · review · merge, via GoldWriter)  ✅ 6
                                          │
                                          ▼
  GOLD              gold.master_patients · gold.fhir_resources · gold.data_lineage
                                          │
  SERVE             CMS-0057 FHIR read APIs ✅ 5b  ·  SMART on FHIR ✅ 5b  ·  api-gateway ✅ 7
                                          │
                                          ▼
  VIEW              dashboard-ui 🔄 8   Patient Timeline · Ontology Coverage ·
                                       Validation Report · FHIR Viewer · Data Lineage
```

### Services

| Service | Port | Phase | Responsibility | Status |
|---|---|---|---|---|
| [`ingestion-service`](ingestion-service) | 8081 (+ MLLP 8887) | 2 / 2b | Apache Camel MLLP listener + REST intake for HL7 v2, FHIR JSON and claims CSV; checksums, quarantine, real HL7 ACK/NAK | ✅ Implemented |
| [`ontology-service`](ontology-service) | 8082 | 3 | Code validation (ICD-10, SNOMED CT, LOINC, RxNorm) and SNOMED ↔ ICD-10 concept mapping | ✅ Implemented |
| [`rules-engine-service`](rules-engine-service) | 8083 | 4 | 7 clinical plausibility rules, tunable as data (JSONB), orchestrated with ontology validation | ✅ Implemented |
| [`fhir-compliance-service`](fhir-compliance-service) | 8084 | 5 / 5b | Official HL7 FHIR validator vs US Core 6.1.0 + Da Vinci Formulary/DEQM, Silver → FHIR builders, C-CDA → FHIR conversion, GoldWriter (master patients, FHIR resources, lineage), CMS-0057-F read API, SMART on FHIR backend-services auth (Keycloak) | ✅ Done (PR #7–#11) |
| [`patient-index-service`](patient-index-service) | 8085 | 6 | Master patient index: blocking + deterministic rules + Fellegi-Sunter-style weighted matching across sources, auto-merge / review queue / unmerge, applied to Gold through GoldWriter | ✅ Done (PR #12) |
| [`api-gateway`](api-gateway) | 8080 | 7 | Backend-for-frontend for the dashboard: `/api/v1` read models for the 5 views over a read-only DB role, actions proxied to the owning service with its own Keycloak client; user login via Keycloak (PKCE), viewer/steward roles, RFC 7807 errors, correlation ids end to end | ✅ Done (PR #13) |
| [`dashboard-ui`](dashboard-ui) | 3000 | 8 | React + TypeScript SPA for the 5 views, talking only to `/api/v1`; Keycloak Auth Code + PKCE (tokens in memory), viewer/steward UI with confirmed steward actions, RFC 7807 problems with correlation ids, DEGRADED-aware; static build behind nginx with a strict CSP | ✅ Done (PR #14) |
| [`data-generator`](data-generator) | — | 9 | Python synthetic data in all source formats | ⏳ Planned |

Planned services boot as placeholder containers so the full topology and port map exist from day one.

## Roadmap

Each phase is finished — implemented, unit-tested, smoke-tested against the running stack, and
hand-verified in Postman — before the next one starts.

| Phase | Component | Purpose | Status |
|---|---|---|---|
| **1** | Foundation | Docker Compose stack, the four PostgreSQL schemas (bronze / silver / gold / ontology), seed terminology and clinical rules | ✅ Done |
| **2** | `ingestion-service`: HL7 v2 | MLLP listener + REST endpoint parsing ADT/ORU messages into Bronze, with real ACK/NAK | ✅ Done (PR #1) |
| **2b** | `ingestion-service`: FHIR + claims | FHIR JSON and claims CSV intake. Claims files are all-or-nothing, one Bronze row per claim line | ✅ Done (PR #2, #3) |
| **3** | `ontology-service` | ICD-10 / SNOMED CT / LOINC / RxNorm validators, concept mapping, findings in `silver.validation_reports` | ✅ Done (PR #4) |
| **4** | `rules-engine-service` | 7 clinical rules (gender ↔ pregnancy, cancer staging, date sanity, lab ranges, age consistency, med ↔ allergy, FHIR completeness); degrades to rules-only if ontology-service is down | ✅ Done (PR #5) |
| — | Smoke test | Black-box HTTP test of every live endpoint (`scripts/smoke-test.sh`) | ✅ Done (PR #6) |
| **5** | `fhir-compliance-service`: structural | US Core required-element checks for 7 resource types; FHIR generation; first Gold-layer writes | ✅ Done (PR #7) |
| **5b.1** | HL7 FHIR validator | Official validator (HAPI FHIR 8.2) against the bundled US Core 6.1.0 package: severity, FHIRPath location, rule and suggested fix per violation. Silver → US Core builders write to Gold with compliance notes | ✅ Done (PR #8) |
| **5b.2** | C-CDA → FHIR | `POST /api/fhir/convert/ccda`: C-CDA R2.1 Problems / Medications / Results → FHIR R4 Bundle, every resource validated against US Core | ✅ Done (PR #9) |
| **5b.3** | GoldWriter + CMS-0057 | One writer for `gold.master_patients` (provisional 1:1 masters until Phase 6) / `fhir_resources` / `data_lineage` (BRONZE → SILVER → GOLD chains with hashes); `GET /fhir/r4/{Coverage,PractitionerRole,MedicationKnowledge,MeasureReport,Device}` read + search from Gold, validated against US Core / Da Vinci; first `@SpringBootTest` + Testcontainers integration test | ✅ Done (PR #10) |
| **5b.4** | SMART on FHIR | Keycloak 26.8 realm imported from JSON (confidential client, client credentials); Spring Security resource server: Bearer JWT on `/fhir/**` with SMART v2 scopes (`system/<Type>.rs`, read vs search), `clinicalharmony/pipeline.write` for Gold writers, 401/403 as OperationOutcomes; `/.well-known/smart-configuration` | ✅ Done (PR #11) |
| **6** | `patient-index-service` | Master patient index: blocking (DOB · Double Metaphone surname + birth year · phone), deterministic rules, weighted field agreement (Jaro-Winkler, nicknames, DOB day/month swaps); ≥ 0.85 auto-merges, 0.60–0.85 goes to a review queue; accept / reject / manual merge / unmerge, with human "not the same person" decisions remembered. GoldWriter stays the only Gold writer (PMI calls it with its own Keycloak client) | ✅ Done (PR #12) |
| **7** | `api-gateway` | Backend-for-frontend: one `/api/v1` for the dashboard's 5 views. Reads are SQL projections over a SELECT-only Postgres role (patient timeline across every source, ontology coverage, validation findings, Gold FHIR + validator notes, lineage back to Bronze); actions (PMI review, matching runs, rule toggles, FHIR validation, C-CDA) are proxied to the service that owns them. Keycloak user login (Auth Code + PKCE) with `dashboard-viewer` / `data-steward` roles; RFC 7807 errors; 503/504/502 when a service is down, slow or broken | ✅ Done (PR #13) |
| **8** | `dashboard-ui` | React: Patient Timeline (with the PMI review queue), Ontology Coverage, Validation Report (with the rule catalog), FHIR Viewer (with validate / C-CDA tools), Data Lineage (back to the raw Bronze message). Signs users in with Keycloak Auth Code + PKCE; viewers see everything, data stewards also run matching, accept / reject / unmerge, and toggle rules, each behind a confirmation | ✅ Done (PR #14) |
| **9** | Event-driven pipeline + `data-generator` | Rebuild the Bronze → Silver → Gold flow on Kafka, **one layer at a time** (9a–9e below), fed by a Python generator of synthetic hospital data. Each sub-phase is its own branch and PR | ⏳ Planned |
| **9a** | Bronze over Kafka | `data-generator` v1 sends 100 seeded HL7 v2 messages over **MLLP**; ingestion-service publishes them to Kafka; a new **bronze listener** stores them in `bronze.raw_messages` and announces each one. Details in [Phase 9 design](#phase-9-design-one-layer-at-a-time). FHIR and claims REST intake unchanged for now | ⏳ Next |
| **9b** | Silver transformation | A new **silver transformer** turns each Bronze HL7 message into `silver.*` rows linked to the raw message, runs ontology + rules validation, and announces each changed patient. Closes the "no Bronze → Silver parser" gap. Details in [Phase 9 design](#phase-9-design-one-layer-at-a-time) | ⏳ Planned |
| **9c** | Gold over Kafka | A consumer in fhir-compliance-service builds and validates US Core FHIR for each changed patient and writes Gold through **GoldWriter**; lineage now starts at the raw HL7 message; a consumer in patient-index-service runs matching. Details in [Phase 9 design](#phase-9-design-one-layer-at-a-time) | ⏳ Planned |
| **9d** | Full generator | All feeds: FHIR JSON, claims CSV and C-CDA alongside HL7; ~20% of patients in more than one feed (typos, nicknames, swapped dates) for the PMI; ~10–15% defects for validation; `--seed` for repeatable demos, `--reset`, and a `--trickle` mode that looks like a live feed. REST intake paths move onto Kafka too | ⏳ Planned |
| **9e** | Observability | Ship the structured logs to **Datadog or Splunk** (to be decided) and build the views listed under [Design diagrams](#design-diagrams-to-draw): pipeline throughput, consumer lag, dead letters, quarantine rate, end-to-end latency, trace-by-correlation-id. Alerts on lag, dead letters and service down | ⏳ Planned |
| **10** | Polish | Final end-to-end Docker run, interview demo script (legacy HL7 → FHIR → dashboard), finished architecture diagrams, documentation | ⏳ Planned |

### Phase 9 design: one layer at a time

Each layer is decoupled from the next by a Kafka topic. A service only knows the topic it reads and
the topic it writes, never the service on the other side, so any layer can be stopped, replayed,
scaled or replaced on its own. The same building blocks repeat in every layer: **topic in → listener
(consumer group) → tables → topic out**, with a dead-letter topic and structured logs at each step.

| | **9a — Bronze** | **9b — Silver** | **9c — Gold** |
|---|---|---|---|
| **Input** | HL7 v2 over MLLP (port 8887) from `data-generator` | Topic `clinicalharmony.bronze.ingested` | Topic `clinicalharmony.silver.patient.changed` |
| **Producer → topic** | ingestion-service validates the MLLP frame and publishes the raw message to `clinicalharmony.bronze.hl7v2.raw`. It sends ACK `AA` only after Kafka confirms the write (`acks=all`); if Kafka can't take it, the sender gets a NAK and retries | — | — |
| **Listener (consumer group)** | New **bronze listener** service (`bronze-writer`) | New **silver transformer** service (`silver-transformer`) | Consumer inside **fhir-compliance-service** (`gold-builder`), so GoldWriter stays the only Gold writer; then a consumer inside **patient-index-service** (`pmi-matcher`) |
| **What it does** | Stores every message as it arrived and checks it parses as HL7 v2 | Reads the raw message from Bronze by id (the event carries the id, not the payload). Maps **MSH** → source, **PID** → patient, **DG1** → conditions, **OBX** → observations, **RXE / RXO** → medications (**AL1** → allergies needs a new `silver.allergies` table). Then calls rules-engine `POST /api/rules/evaluate` (which calls ontology-service) with the real Silver ids | Builds US Core Patient / Condition / Observation / MedicationRequest, checks each with the official HL7 validator, and writes them through GoldWriter. Non-compliant resources are still written, with their violations in `compliance_notes`. `pmi-matcher` then runs matching for the changed patients, batched a few seconds apart (runs are serialized) |
| **Writes** | `bronze.raw_messages`: `PENDING`, or `QUARANTINED` with the parse error | `silver.patients` (upsert on `source_system` + `source_patient_id`), `silver.conditions` / `observations` / `medications` with `raw_message_id`; findings in `silver.validation_reports`; Bronze row → `PROCESSED`, or `FAILED` with the reason | `gold.fhir_resources`, `gold.master_patients` (PROVISIONAL) + `patient_source_xref`, `gold.data_lineage` with the full **BRONZE → SILVER → GOLD** chain and hashes; PMI merges and review queue as today |
| **Output topic** | `clinicalharmony.bronze.ingested` (raw message id, type, checksum), for `PENDING` rows only. Quarantined messages stay in Bronze for a data steward | `clinicalharmony.silver.patient.changed` (Silver patient id, source, raw message id, `CREATED` / `UPDATED`) | `clinicalharmony.gold.patient.changed` (master id, resource ids, compliance summary) → read by `pmi-matcher` |
| **Partition key** (keeps one patient's messages in order) | Source system + PID-3 patient id; MSH-10 control id when PID can't be read | Same patient key | Master patient id |
| **Safe when a message arrives twice** | Unique constraint on source system + `payload_checksum` (new migration); a repeat is skipped | Child rows for a `raw_message_id` are replaced in one transaction; the patient upsert is naturally repeatable | `fhir_resources` upsert on (`resource_type`, `fhir_id`), lineage upsert on (target, step), both existing unique indexes |
| **Event never lost or sent for a rolled-back write** | The output event is written to an **outbox** table in the same transaction as the Bronze row, then relayed to Kafka (to confirm at scoping) | Same outbox pattern | Same outbox pattern |
| **Bad data** (a business problem, kept and shown) | `QUARANTINED` in Bronze | Bronze row `FAILED` with the mapping error; nothing half-written to Silver | Violations recorded in `compliance_notes` and the Validation Report |
| **Technical failure** (database or a service down) | 3 retries with backoff, then dead-letter topic `clinicalharmony.bronze.hl7v2.raw.dlt` | Retries, then `clinicalharmony.bronze.ingested.dlt`. If rules-engine is down, Silver is still written and the patient is marked for re-validation | Retries, then `clinicalharmony.silver.patient.changed.dlt` / `clinicalharmony.gold.patient.changed.dlt` |
| **Structured log events** | `hl7.received`, `hl7.published` (partition, offset), `bronze.stored` (raw message id), `bronze.quarantined` (reason), `bronze.deadlettered` | `silver.consumed`, `silver.mapped` (segment counts, Silver ids), `silver.validated` (findings by severity), `silver.failed`, `silver.published` | `gold.consumed`, `gold.built` (resources, compliant vs not), `gold.lineage.written`, `pmi.run` (run id, auto-merges, queued for review), `gold.published` |
| **Metrics** (for the 9e dashboards) | Messages in, quarantined, ACK latency, consumer lag | Messages mapped, failed, findings per rule, consumer lag | Resources built, US Core compliance %, PMI auto-merges vs reviews, end-to-end latency (MLLP received → Gold written) |
| **Done when** | 100 generated messages → ~90 `PENDING` + ~10 `QUARANTINED`, no duplicates after a listener restart mid-run, visible in **Data Lineage → Bronze** | The same messages appear as Silver patients in **Patient Timeline**, with validation findings in **Validation Report** | **FHIR Viewer** shows the resources, **Data Lineage** traces them back to the raw HL7 bytes, **PMI Review** shows the generated duplicates |

**Shared by every layer**

- **Event envelope.** JSON value plus Kafka headers: `event-id` (UUID), `event-type`, `schema-version`,
  `produced-at`, `producer` (service name), `correlation-id` (set once at MLLP intake and carried through
  every hop, the same id the dashboard already shows) and `causation-id` (the event that caused this one).
- **Delivery.** At least once: every consumer is idempotent, so redelivery is harmless.
  Offsets are committed only after the database transaction commits.
- **Naming.** `clinicalharmony.<layer>.<entity>.<event>` and `<topic>.dlt`; one consumer group per
  listener. 3 partitions per topic to start, so a layer can scale to 3 instances.
- **Replay.** A dead-lettered message can be sent back to its topic once the cause is fixed. Because
  every layer is idempotent, re-reading a whole topic rebuilds that layer.
- **Logs.** Every service writes one JSON line per event: `timestamp`, `level`, `service`, `event`,
  `correlationId`, `causationId`, `topic`, `partition`, `offset`, `rawMessageId` / `silverPatientId` /
  `masterPatientId` as they become known, `durationMs`. That is everything the 9e Datadog / Splunk views
  need to trace one HL7 message from MLLP to Gold.

### Not wired up yet

- **Bronze → Silver parsing** (planned: Phase 9b). No service turns raw messages into `silver.*` rows yet. Silver is
  seeded by `docs/db-init/17`, and the ontology/rules APIs take a canonical patient record directly.
  So Gold lineage for the seed patients starts at Silver; the BRONZE step appears once a Silver row
  carries a `raw_message_id` (the integration test exercises exactly that).
- **Matching runs on demand.** patient-index-service matches when `POST /api/pmi/match/run` is
  called (nothing schedules it yet), and only sees Silver patients fhir-compliance-service has built
  into Gold (`POST /api/fhir/build` creates their `PROVISIONAL` masters). Blocking is in memory per
  run — fine at demo scale, not at millions of records.
- **CMS-0057 payer data is a synthetic seed** (`docs/db-init/19`). Nothing upstream produces
  Coverage, PractitionerRole, MedicationKnowledge, MeasureReport or Device yet.
- **Kafka** (and Zookeeper) is provisioned in Docker Compose, but no service publishes to or consumes from it yet
  (planned: Phase 9a onward).
- **Terminology** runs offline: code *existence* is checked by ontology-service against seeded subsets,
  not full licensed code systems, so the FHIR validator reports LOINC/SNOMED/RxNorm codes as warnings.
- **Dev-grade auth.** Keycloak runs in dev mode with a fixed client secret checked into
  `keycloak/realm-clinicalharmony.json`, and clients authenticate with that shared secret
  (`client-confidential-symmetric`) rather than the `private_key_jwt` SMART Backend Services
  recommends. Only the backend-services (`system/`) flow exists for the FHIR API: no SMART app
  launch, no `patient/` or `user/` scopes. Dashboard users (api-gateway) sign in with Auth Code +
  PKCE as two dev users with passwords in the realm JSON, and a DEV-ONLY direct-grant client lets
  Postman and the smoke test log in without a browser. ingestion, ontology and rules-engine are
  still unauthenticated — reach them through the gateway.

## Standards covered

| Area | Standards |
|---|---|
| Messaging & documents | HL7 v2.5 (ADT, ORU) over MLLP · HL7 C-CDA R2.1 · claims CSV |
| FHIR | FHIR R4 · US Core 6.1.0 · CMS-0057-F payer read APIs (Coverage, PractitionerRole, MedicationKnowledge, MeasureReport, Device) · Da Vinci US Drug Formulary 2.1.0 · Da Vinci DEQM 5.0.0 · SMART App Launch v2 (backend services, `system/` scopes) |
| Terminology | ICD-10-CM · SNOMED CT · LOINC · RxNorm · UCUM · CDC Race & Ethnicity (OMB) |

## Data platform

PostgreSQL 16 with a medallion layout plus a reference schema. Full DDL and seed data are in
[`docs/db-init`](docs/db-init); scripts run in order on first container start.

| Schema | Purpose | Key tables |
|---|---|---|
| `bronze` | Raw, immutable intake payloads, valid or quarantined | `raw_messages` |
| `silver` | Parsed, typed clinical entities (before deduplication) and every validation finding | `patients`, `conditions`, `observations`, `medications`, `validation_reports` |
| `gold` | Conformed, deduplicated, US Core FHIR resources with lineage — all written by one component, GoldWriter | `master_patients` (+ `match_status`), `patient_source_xref`, `master_patient_merges`, `fhir_resources` (+ `compliance_notes`), `data_lineage` (JSONB step chains) |
| `pmi` | patient-index-service's working state | `match_runs`, `match_candidates` (review queue), `match_exclusions` |
| `ontology` | Reference terminology and clinical rules (rule parameters stored as JSONB) | `icd10_codes`, `snomed_codes`, `loinc_codes`, `rxnorm_codes`, `concept_mappings`, `clinical_rules` |

## Tech stack

Java 17 · Spring Boot 3.5 · Apache Camel 4.4 (MLLP) · HAPI HL7 v2 · HAPI FHIR 8.2 (validator) ·
PostgreSQL 16 (plain `JdbcTemplate`, no ORM) · Docker Compose · JUnit 5 / Mockito / Spring MVC
test slices · Testcontainers (`@SpringBootTest` integration) · Keycloak 26.8 + Spring Security OAuth2 resource server · *planned:* React, Python.

## Getting started

**Prerequisites:** Docker with Docker Compose (Java 17 + Maven only to run a service outside Docker).

```bash
docker-compose up -d
```

On first start, PostgreSQL runs every script in [`docs/db-init`](docs/db-init) in order: schemas,
tables, terminology subsets, clinical rules, and Silver demo patients. To wipe everything and start over:

```bash
docker-compose down -v
```

| Component | Address |
|---|---|
| PostgreSQL | `localhost:5432` (db / user / password: `clinicalharmony`) |
| Kafka | `localhost:9092` |
| ingestion-service | `localhost:8081` (REST) · `localhost:8887` (HL7 v2 MLLP) |
| ontology-service | `localhost:8082` |
| rules-engine-service | `localhost:8083` |
| fhir-compliance-service | `localhost:8084` |
| patient-index-service | `localhost:8085` |
| Keycloak (realm `clinicalharmony`) | `localhost:8180` (admin console: `admin` / `admin`, dev only) |
| api-gateway | `localhost:8080` (`/api/v1`, users `dana` / `vic`, dev only) |
| dashboard-ui | `localhost:3000` (sign in as `dana` or `vic`, dev only) |

### Verify the whole stack

```bash
./scripts/smoke-test.sh
```

This runs 239 black-box assertions against the live services: intake accept and quarantine, code
validation, every clinical rule and live rule toggling, US Core validation, Silver → FHIR builds,
C-CDA conversion, GoldWriter master patients and lineage, the CMS-0057 read/search API, SMART
auth (it gets real tokens from Keycloak, then checks discovery, 401/403 and scope enforcement), and
patient matching end to end (fresh synthetic duplicates each run: auto-merge, review, reject,
accept, unmerge, re-run), and the API gateway (user logins and roles, every dashboard read model
cross-checked against SQL, actions relayed with the signed-in user as reviewer, correlation ids,
CORS, and a database login that can't write), and the dashboard's nginx (SPA fallback, runtime
config, CSP and security headers, Keycloak accepting its PKCE login). Each implemented service also has Postman collections (one per
phase/slice, in `fhir-compliance-service/postman/`, `patient-index-service/postman/` and
`api-gateway/postman/`) or curl
examples in its own README, and unit tests via `mvn test` from the service directory.

## Try it

**Ingest** — every payload lands in `bronze.raw_messages`. Malformed ones are quarantined (HTTP 422), not dropped.

```bash
# HL7 v2 ADT^A01
curl -i -X POST http://localhost:8081/api/ingestion/hl7 -H "Content-Type: text/plain" \
  --data-binary $'MSH|^~\\&|REG_SYSTEM|CITY_HOSPITAL|CLINICALHARMONY|INGESTION|20260703101500||ADT^A01^ADT_A01|MSG00001|P|2.5\rPID|1||MRN100234^^^CITY_HOSPITAL^MR||DOE^JANE^A||19800515|F\r'

# FHIR JSON
curl -i -X POST http://localhost:8081/api/ingestion/fhir -H "Content-Type: application/json" \
  -H "X-Source-System: PARTNER_CLINIC" \
  -d '{"resourceType":"Patient","id":"pat-1","name":[{"family":"Doe","given":["Jane"]}]}'

# Claims CSV (one Bronze row per claim line)
curl -i -X POST http://localhost:8081/api/ingestion/claims -H "Content-Type: text/csv" \
  -H "X-Source-System: CLAIMS_VENDOR_A" \
  --data-binary $'claim_id,member_id,service_date,icd10_code,billed_amount\nCLM-001,MBR-1001,2026-06-01,E11.9,150.00\n'
```

**Validate codes and map across terminologies:**

```bash
curl http://localhost:8082/api/ontology/codes/icd10/E11.9
curl "http://localhost:8082/api/ontology/map?source=SNOMED&code=44054006&target=ICD10"
```

**Evaluate clinical rules.** The fixture is a deliberately inconsistent record that trips all 7
rules: among other things, a pregnant male patient, a diagnosis dated in the future, an
out-of-range lab, and a medication the patient is allergic to.

```bash
curl -s -X POST http://localhost:8083/api/rules/evaluate -H "Content-Type: application/json" \
  --data-binary @scripts/fixtures/rules_evaluate_request.json
```

**Get a SMART backend-services token** (client credentials against Keycloak; the token lives 5 minutes).
`/fhir/**`, the Gold builders and C-CDA conversion need one; the validators and discovery don't:

```bash
curl -s http://localhost:8084/.well-known/smart-configuration
TOKEN=$(curl -s -u clinicalharmony-app:clinicalharmony-app-secret -d grant_type=client_credentials \
  --data-urlencode "scope=system/*.rs clinicalharmony/pipeline.write" \
  http://localhost:8180/realms/clinicalharmony/protocol/openid-connect/token | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
```

**Validate a FHIR resource against US Core 6.1.0.** Each violation comes back with its FHIRPath location and a suggested fix:

```bash
curl -s -X POST http://localhost:8084/api/fhir/validate -H "Content-Type: application/json" \
  -d '{"resourceType":"Patient","name":[{"family":""}]}'
```

**Convert a C-CDA document to a US Core FHIR Bundle:**

```bash
curl -s -X POST http://localhost:8084/api/fhir/convert/ccda -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/xml" \
  --data-binary @scripts/fixtures/ccda_compliant.xml
```

**Build Gold FHIR resources from the Silver demo patients.** One is deliberately non-compliant, to show how findings are recorded:

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8084/api/fhir/build
```

Then look at what landed, and how each Gold resource traces back through Silver:

```bash
docker compose exec postgres psql -U clinicalharmony -d clinicalharmony -c \
  "SELECT resource_type, fhir_id, is_us_core_compliant, master_patient_id FROM gold.fhir_resources ORDER BY id DESC LIMIT 10;"
docker compose exec postgres psql -U clinicalharmony -d clinicalharmony -c \
  "SELECT source_table, source_id, transformation_step, jsonb_pretty(transformation_details) FROM gold.data_lineage ORDER BY id DESC LIMIT 1;"
```

**Query the CMS-0057-F payer API** (served from Gold; errors, including 401/403, are FHIR OperationOutcomes):

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8084/fhir/r4/Coverage?patient=silver-patient-1"
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8084/fhir/r4/MedicationKnowledge?code=http://www.nlm.nih.gov/research/umls/rxnorm|861007"
curl -si -H "Authorization: Bearer $TOKEN" http://localhost:8084/fhir/r4/PractitionerRole/cms-practitionerrole-2 | grep -i x-profile
```

**Match patients across sources** (patient-index-service; `docs/db-init/21` seeds the same people
arriving from different feeds). Build first so every Silver patient has a master, then run:

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8084/api/fhir/build > /dev/null
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8085/api/pmi/match/run
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8085/api/pmi/candidates   # the review queue
```

The run auto-merges the claims-feed copy of Maria Garcia (deterministic) and "Jim" Wilson (0.91), and
queues two for a human: a day/month-swapped birth date (0.65) and Maria's opposite-sex twin (0.71 —
the gender conflict keeps him out of auto-merge). Accept, reject or undo with
`POST /api/pmi/candidates/{id}/accept|reject` and `POST /api/pmi/merges/{id}/unmerge`.

**Use the dashboard API** (api-gateway). Sign in as a dashboard user — `dana` is a data steward,
`vic` a viewer (DEV-ONLY password grant; the real UI uses Auth Code + PKCE) — then everything the
five dashboard views show comes from `/api/v1`:

```bash
USER_TOKEN=$(curl -s -d grant_type=password -d client_id=clinicalharmony-dashboard-dev -d username=dana \
  -d password=dana-dev-password http://localhost:8180/realms/clinicalharmony/protocol/openid-connect/token \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
curl -s -H "Authorization: Bearer $USER_TOKEN" http://localhost:8080/api/v1/summary
curl -s -H "Authorization: Bearer $USER_TOKEN" "http://localhost:8080/api/v1/patients?q=garcia"
curl -s -H "Authorization: Bearer $USER_TOKEN" http://localhost:8080/api/v1/patients/1/timeline
curl -s -H "Authorization: Bearer $USER_TOKEN" http://localhost:8080/api/v1/ontology/coverage
curl -s -H "Authorization: Bearer $USER_TOKEN" http://localhost:8080/api/v1/services/health
```

Errors are RFC 7807 problems with a correlation id; a service that's down is a 503 naming it.

**Use the dashboard.** Open <http://localhost:3000> and sign in as `dana` / `dana-dev-password`
(data steward) or `vic` / `vic-dev-password` (viewer) — DEV-ONLY users from the realm import.
[`dashboard-ui/CHECKLIST.md`](dashboard-ui/CHECKLIST.md) is a 12-step click-through of all five views.

## Design diagrams (to draw)

A to-do list for the architecture diagrams, drawn by hand in [draw.io](https://app.diagrams.net). Save
each as `docs/diagrams/<name>.drawio` plus an exported PNG, and embed the PNG here when it's done.
Colour convention: Bronze `#CD7F32`, Silver `#A8A9AD`, Gold `#D4AF37`, Kafka topics as queue shapes,
services as rounded rectangles, databases as cylinders, external actors as people/boxes outside a
"ClinicalHarmony" boundary.

- [ ] **1. System context** — Sources on the left (EHR HL7 v2 feed, FHIR API client, payer claims CSV,
  C-CDA documents); ClinicalHarmony in the middle as one box; on the right the people and systems it
  serves (data steward, viewer, payer / CMS-0057 FHIR API clients); Keycloak and Datadog/Splunk at the edge.
- [ ] **2. Containers and ports** — every Docker container with its port: ingestion 8081 + MLLP 8887,
  ontology 8082, rules-engine 8083, fhir-compliance 8084, patient-index 8085, api-gateway 8080,
  dashboard-ui 3000, Keycloak 8180, Postgres 5432 (schemas bronze / silver / gold / ontology / pmi),
  Kafka, plus the Phase 9 bronze listener and silver transformer. Arrows labelled with protocol
  (MLLP, REST, Kafka, JDBC, OIDC).
- [ ] **3. Event flow (Phase 9 target)** — the [Phase 9 design](#phase-9-design-one-layer-at-a-time)
  table, drawn left to right: data-generator → MLLP → ingestion-service → `bronze.hl7v2.raw` →
  bronze listener → `bronze.raw_messages` (+ outbox) → `bronze.ingested` → silver transformer (calls
  rules-engine → ontology) → `silver.*` → `silver.patient.changed` → `gold-builder` in
  fhir-compliance (GoldWriter) → `gold.*` → `gold.patient.changed` → `pmi-matcher` in patient-index →
  api-gateway → dashboard. Label every listener with its consumer group, draw each `.dlt` topic hanging
  off its consumer, and mark the partition key on each topic.
- [ ] **4. One HL7 message, end to end (sequence diagram)** — sender, ingestion, Kafka, each listener,
  Postgres, with the moment the ACK is sent and the correlation id carried through every hop.
- [ ] **5. Medallion data model** — the main tables per schema and their links: `raw_message_id`
  (Silver → Bronze), `patient_source_xref` (Silver ↔ Gold master), `fhir_resources`, `data_lineage`,
  `master_patient_merges`, `pmi.match_candidates`.
- [ ] **6. Security** — dashboard sign-in (Auth Code + PKCE) → api-gateway with the user token →
  services with the gateway's own client-credentials token; PMI → GoldWriter with its own client; SMART
  `system/` scopes on `/fhir/r4/**`; roles `dashboard-viewer` / `data-steward`.
- [ ] **7. Observability** — log path: services write structured JSON logs → agent (Datadog Agent, or
  Splunk Universal Forwarder / HTTP Event Collector) → Datadog / Splunk. Then sketch the planned views:
  - *Pipeline overview*: messages per minute per topic, consumer lag per group, dead-letter count,
    quarantine rate, end-to-end latency p50 / p95 (MLLP received → Gold written).
  - *Message trace*: search one correlation id or MSH-10 control id → every log line across services, in order.
  - *Errors*: top quarantine reasons, dead-letter messages, 4xx / 5xx by service, downstream timeouts.
  - *Data quality*: validation findings by rule and severity over time, US Core compliance %.
  - *Patient index*: auto-merges vs review queue size, review backlog age.
  - *Alerts*: consumer lag above threshold, any dead letter, service health DEGRADED.

## Project structure

```
ClinicalHarmony/
├── docker-compose.yml
├── docs/db-init/               # Ordered SQL: schemas, tables, terminology subsets, rules, Silver + CMS-0057 + PMI seeds
├── scripts/
│   ├── smoke-test.sh           # Black-box test of every live endpoint
│   └── fixtures/               # HL7 / FHIR / CSV / C-CDA test payloads
├── ingestion-service/          # ✅ Spring Boot + Apache Camel (MLLP) + HAPI HL7 v2
├── ontology-service/           # ✅ Terminology validators + concept mapping
├── rules-engine-service/       # ✅ Clinical rules engine
├── fhir-compliance-service/    # ✅ HAPI FHIR validator, US Core builders, C-CDA → FHIR, GoldWriter, CMS-0057 API, SMART auth
├── keycloak/                   # Realm import: clients, SMART scopes, audience mappers, dashboard roles + dev users
├── patient-index-service/      # ✅ Master patient index: matching, review queue, merge/unmerge via GoldWriter
├── api-gateway/                # ✅ Dashboard BFF: /api/v1 read models + proxied actions
├── dashboard-ui/               # 🔄 React + TypeScript dashboard (Vite, nginx)
└── data-generator/             # ⏳ Python synthetic data generator
```

Each implemented service has its own README with its endpoints, design notes and test instructions.
