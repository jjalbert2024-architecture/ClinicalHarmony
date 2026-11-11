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

> **Status (2026-11-11):** Phases 1–5 and Phase 5b slices 1–2 are built and tested. Four services
> are live: ingestion, ontology, rules engine, and FHIR compliance. See the [Roadmap](#roadmap).

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
                    └─────────────────────┬──────────────────────────────────────────┘
                                          ▼
  RESOLVE           patient-index-service  (cross-source deduplication)       ⏳ Phase 6
                                          │
                                          ▼
  GOLD              gold.master_patients · gold.fhir_resources · gold.data_lineage
                                          │
  SERVE             api-gateway ⏳ 7  ·  CMS-0057 FHIR read APIs + SMART on FHIR ⏳ 5b
                                          │
                                          ▼
  VIEW              dashboard-ui ⏳ 8   Patient Timeline · Ontology Coverage ·
                                       Validation Report · FHIR Viewer · Data Lineage
```

### Services

| Service | Port | Phase | Responsibility | Status |
|---|---|---|---|---|
| [`ingestion-service`](ingestion-service) | 8081 (+ MLLP 8887) | 2 / 2b | Apache Camel MLLP listener + REST intake for HL7 v2, FHIR JSON and claims CSV; checksums, quarantine, real HL7 ACK/NAK | ✅ Implemented |
| [`ontology-service`](ontology-service) | 8082 | 3 | Code validation (ICD-10, SNOMED CT, LOINC, RxNorm) and SNOMED ↔ ICD-10 concept mapping | ✅ Implemented |
| [`rules-engine-service`](rules-engine-service) | 8083 | 4 | 7 clinical plausibility rules, tunable as data (JSONB), orchestrated with ontology validation | ✅ Implemented |
| [`fhir-compliance-service`](fhir-compliance-service) | 8084 | 5 / 5b | Official HL7 FHIR validator vs US Core 6.1.0, Silver → FHIR builders, C-CDA → FHIR conversion, Gold writes | 🟡 Partial (Phase 5b slices 3–4 to go) |
| [`patient-index-service`](patient-index-service) | 8085 | 6 | Master patient index: cross-source matching and deduplication | ⏳ Planned |
| [`api-gateway`](api-gateway) | 8080 | 7 | Single REST surface for the dashboard | ⏳ Planned |
| [`dashboard-ui`](dashboard-ui) | 3000 | 8 | React dashboard, 5 views | ⏳ Planned |
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
| **5b.2** | C-CDA → FHIR | `POST /api/fhir/convert/ccda`: C-CDA R2.1 Problems / Medications / Results → FHIR R4 Bundle, every resource validated against US Core | 🔄 Built, in review |
| **5b.3** | GoldWriter + CMS-0057 | `gold.master_patients` / `fhir_resources` / `data_lineage` writer; FHIR read APIs for Coverage, PractitionerRole, MedicationKnowledge, MeasureReport, Device | ⏳ Next |
| **5b.4** | SMART on FHIR | Keycloak OAuth2 (client credentials), Bearer-protected `/fhir/**`, `/.well-known/smart-configuration` | ⏳ Planned |
| **6** | `patient-index-service` | Master patient index: probabilistic matching and cross-source deduplication into `gold.master_patients` | ⏳ Planned |
| **7** | `api-gateway` | One REST API for the dashboard | ⏳ Planned |
| **8** | `dashboard-ui` | React: Patient Timeline, Ontology Coverage, Validation Report, FHIR Viewer, Data Lineage | ⏳ Planned |
| **9** | `data-generator` | Python synthetic patients in HL7 v2, FHIR, claims CSV (and C-CDA) | ⏳ Planned |
| **10** | Polish | Final end-to-end Docker run, demo script, documentation | ⏳ Planned |

### Not wired up yet

- **Bronze → Silver parsing.** No service turns raw messages into `silver.*` rows yet. Silver is
  seeded by `docs/db-init/17`, and the ontology/rules APIs take a canonical patient record directly.
- **Kafka** (and Zookeeper) is provisioned in Docker Compose, but no service publishes to or consumes from it yet.
- **Terminology** runs offline: code *existence* is checked by ontology-service against seeded subsets,
  not full licensed code systems, so the FHIR validator reports LOINC/SNOMED/RxNorm codes as warnings.
- **No authentication** until Phase 5b.4.

## Standards covered

| Area | Standards |
|---|---|
| Messaging & documents | HL7 v2.5 (ADT, ORU) over MLLP · HL7 C-CDA R2.1 · claims CSV |
| FHIR | FHIR R4 · US Core 6.1.0 · *planned:* CMS-0057-F interoperability APIs, SMART App Launch (backend services) |
| Terminology | ICD-10-CM · SNOMED CT · LOINC · RxNorm · UCUM · CDC Race & Ethnicity (OMB) |

## Data platform

PostgreSQL 16 with a medallion layout plus a reference schema. Full DDL and seed data are in
[`docs/db-init`](docs/db-init); scripts run in order on first container start.

| Schema | Purpose | Key tables |
|---|---|---|
| `bronze` | Raw, immutable intake payloads, valid or quarantined | `raw_messages` |
| `silver` | Parsed, typed clinical entities (before deduplication) and every validation finding | `patients`, `conditions`, `observations`, `medications`, `validation_reports` |
| `gold` | Conformed, deduplicated, US Core FHIR resources with lineage | `master_patients`, `fhir_resources` (+ `compliance_notes`), `data_lineage` |
| `ontology` | Reference terminology and clinical rules (rule parameters stored as JSONB) | `icd10_codes`, `snomed_codes`, `loinc_codes`, `rxnorm_codes`, `concept_mappings`, `clinical_rules` |

## Tech stack

Java 17 · Spring Boot 3.5 · Apache Camel 4.4 (MLLP) · HAPI HL7 v2 · HAPI FHIR 8.2 (validator) ·
PostgreSQL 16 (plain `JdbcTemplate`, no ORM) · Docker Compose · JUnit 5 / Mockito / Spring MVC
test slices · *planned:* Keycloak, React, Python.

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
| api-gateway / dashboard-ui | `localhost:8080` / `localhost:3000` (placeholders) |

### Verify the whole stack

```bash
./scripts/smoke-test.sh
```

This runs 59 black-box assertions against the live services: intake accept and quarantine, code
validation, every clinical rule and live rule toggling, US Core validation, Silver → FHIR builds,
and C-CDA conversion. Each implemented service also has a Postman collection or curl examples in
its own README, and unit tests via `mvn test` from the service directory.

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

**Validate a FHIR resource against US Core 6.1.0.** Each violation comes back with its FHIRPath location and a suggested fix:

```bash
curl -s -X POST http://localhost:8084/api/fhir/validate -H "Content-Type: application/json" \
  -d '{"resourceType":"Patient","name":[{"family":""}]}'
```

**Convert a C-CDA document to a US Core FHIR Bundle:**

```bash
curl -s -X POST http://localhost:8084/api/fhir/convert/ccda -H "Content-Type: application/xml" \
  --data-binary @scripts/fixtures/ccda_compliant.xml
```

**Build Gold FHIR resources from the Silver demo patients.** One is deliberately non-compliant, to show how findings are recorded:

```bash
curl -s -X POST http://localhost:8084/api/fhir/build
```

Then look at what landed:

```bash
docker compose exec postgres psql -U clinicalharmony -d clinicalharmony -c \
  "SELECT resource_type, fhir_id, is_us_core_compliant FROM gold.fhir_resources ORDER BY id DESC LIMIT 10;"
```

## Project structure

```
ClinicalHarmony/
├── docker-compose.yml
├── docs/db-init/               # Ordered SQL: schemas, tables, terminology subsets, rules, Silver seed
├── scripts/
│   ├── smoke-test.sh           # Black-box test of every live endpoint
│   └── fixtures/               # HL7 / FHIR / CSV / C-CDA test payloads
├── ingestion-service/          # ✅ Spring Boot + Apache Camel (MLLP) + HAPI HL7 v2
├── ontology-service/           # ✅ Terminology validators + concept mapping
├── rules-engine-service/       # ✅ Clinical rules engine
├── fhir-compliance-service/    # 🟡 HAPI FHIR validator, US Core builders, C-CDA → FHIR
├── patient-index-service/      # ⏳ Master patient index + deduplication
├── api-gateway/                # ⏳ REST API for the dashboard
├── dashboard-ui/               # ⏳ React dashboard
└── data-generator/             # ⏳ Python synthetic data generator
```

Each implemented service has its own README with its endpoints, design notes and test instructions.
