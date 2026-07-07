# ClinicalHarmony

**A FHIR-compliant Clinical Data Harmonization & Ontology Validation Platform.**

ClinicalHarmony ingests patient data from heterogeneous healthcare sources —
HL7 v2 messages, FHIR JSON bundles, and Claims CSV extracts — validates every
clinical code against standard terminologies (ICD-10, SNOMED CT, LOINC,
RxNorm), resolves duplicate patient identities across source systems, and
conforms the result into US Core Implementation Guide-compliant FHIR
resources. The platform exposes this pipeline end-to-end through a React
dashboard covering patient timelines, ontology coverage, validation reporting,
raw FHIR inspection, and full data lineage — modeling the kind of clinical
interoperability and data quality tooling used by health systems and payers
adopting FHIR at scale.

## Architecture Overview

```
Multi-source ingestion              Ontology Validation           Patient Master Index
(HL7 v2 / FHIR JSON / Claims CSV)    (ICD-10, SNOMED CT,      →    (deduplication across
        │                            LOINC, RxNorm)                 source systems)
        ▼                                  │                              │
   Bronze schema                           ▼                              ▼
   (raw_messages)                    Silver schema                 US Core FHIR
                                (patients, conditions,              Compliance Layer
                                 observations, medications,               │
                                 validation_reports)                     ▼
                                                                    Gold schema
                                                              (master_patients,
                                                               fhir_resources,
                                                               data_lineage)
                                                                          │
                                                                          ▼
                                                                 React Dashboard
                                                    (Patient Timeline · Ontology Coverage ·
                                                     Validation Report · FHIR Viewer ·
                                                     Data Lineage)
```

**Services** (each an independently deployable Spring Boot / Apache Camel
module, orchestrated via Docker Compose):

| Service                     | Responsibility                                              | Status |
|-----------------------------|---------------------------------------------------------------|--------|
| `ingestion-service`         | Apache Camel routes for HL7 v2, FHIR JSON, and Claims CSV intake | ✅ Implemented |
| `ontology-service`          | Validates codes against ICD-10, SNOMED CT, LOINC, RxNorm       | ✅ Implemented |
| `patient-index-service`     | Patient Master Index — cross-source deduplication              | Not yet implemented |
| `fhir-compliance-service`   | HAPI FHIR-based US Core IG conformance and resource generation | Not yet implemented |
| `rules-engine-service`      | Evaluates clinical validation rules against Silver entities    | Not yet implemented |
| `api-gateway`               | REST API surface consumed by the dashboard                     | Not yet implemented |
| `dashboard-ui`              | React dashboard (5 views)                                      | Not yet implemented |
| `data-generator`            | Python-based synthetic clinical data generator                 | Not yet implemented |

**Data platform**: PostgreSQL, organized as a Bronze → Silver → Gold medallion
architecture plus a dedicated Ontology schema for reference terminology and
clinical rules. See [`docs/db-init`](docs/db-init) for the full DDL.

| Schema     | Purpose                                                          |
|------------|-------------------------------------------------------------------|
| `bronze`   | Raw, immutable ingestion payloads                                 |
| `silver`   | Parsed, typed clinical entities (pre-deduplication)                |
| `gold`     | Conformed, deduplicated, FHIR-compliant entities                  |
| `ontology` | Reference code systems (ICD-10, LOINC, RxNorm) and clinical rules  |

## Getting Started

**Prerequisites**: Docker and Docker Compose.

Start the full platform — PostgreSQL, Zookeeper, Kafka, and all services —
with a single command from the project root:

```bash
docker-compose up
```

On first startup, PostgreSQL automatically runs the initialization scripts in
[`docs/db-init`](docs/db-init) in order, creating the four schemas, all
tables, and seed data (built-in clinical rules and a sample ICD-10 code set).

To reset the database and re-run initialization from scratch:

```bash
docker-compose down -v
docker-compose up
```

Once running:

| Component          | Address                                                  |
|---------------------|-----------------------------------------------------------|
| PostgreSQL          | `localhost:5432` (`clinicalharmony` / `clinicalharmony`)   |
| Kafka               | `localhost:9092`                                           |
| **ingestion-service** | `localhost:8081` (REST) + `localhost:8887` (HL7 v2 MLLP) |
| **ontology-service** | `localhost:8082`                                          |
| API Gateway         | `localhost:8080` (placeholder)                             |
| Dashboard UI        | `localhost:3000` (placeholder)                             |

> **Status**: Phase 1 (Foundation), Phase 2 (`ingestion-service`, all three
> intake formats), and Phase 3 (`ontology-service`, all 4 code validators +
> cross-terminology mapping) are implemented. Every other application service
> still starts as a placeholder and will be implemented in subsequent phases.

### Try the live ingestion endpoints

`ingestion-service` is the only fully working service right now — every
payload it accepts (or rejects) lands in `bronze.raw_messages` with a SHA-256
checksum; malformed payloads are quarantined, never dropped.

```bash
# Health check
curl http://localhost:8081/actuator/health

# HL7 v2 (ADT admit message)
curl -i -X POST http://localhost:8081/api/ingestion/hl7 \
  -H "Content-Type: text/plain" \
  --data-binary $'MSH|^~\\&|REG_SYSTEM|CITY_HOSPITAL|CLINICALHARMONY|INGESTION|20260703101500||ADT^A01^ADT_A01|MSG00001|P|2.5\rPID|1||MRN100234^^^CITY_HOSPITAL^MR||DOE^JANE^A||19800515|F\r'

# FHIR JSON (single resource or Bundle)
curl -i -X POST http://localhost:8081/api/ingestion/fhir \
  -H "Content-Type: application/json" \
  -H "X-Source-System: PARTNER_CLINIC" \
  -d '{"resourceType":"Patient","id":"pat-1","name":[{"family":"Doe","given":["Jane"]}]}'

# Claims CSV (lands one Bronze row per claim line)
curl -i -X POST http://localhost:8081/api/ingestion/claims \
  -H "Content-Type: text/plain" \
  -H "X-Source-System: CLAIMS_VENDOR_A" \
  --data-binary $'claim_id,member_id,service_date,icd10_code,billed_amount\nCLM-001,MBR-1001,2026-06-01,E11.9,150.00\n'
```

Then inspect what landed:

```bash
docker compose exec postgres psql -U clinicalharmony -d clinicalharmony \
  -c "SELECT id, source_system, message_type, message_format, processing_status FROM bronze.raw_messages ORDER BY id;"
```

### Try the live ontology validation endpoints

`ontology-service` validates diagnosis (ICD-10, SNOMED CT), lab (LOINC), and medication
(RxNorm) codes, and answers cross-terminology mapping queries.

```bash
# Health check
curl http://localhost:8082/actuator/health

# Look up a single code directly
curl http://localhost:8082/api/ontology/codes/icd10/E11.9
curl http://localhost:8082/api/ontology/codes/loinc/2093-3

# Cross-terminology mapping: SNOMED CT -> ICD-10
curl "http://localhost:8082/api/ontology/map?source=SNOMED&code=44054006&target=ICD10"

# Validate a whole patient record (mix of valid and deliberately invalid codes)
curl -X POST http://localhost:8082/api/ontology/validate \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": 555,
    "conditions": [
      {"id": 9001, "codeSystem": "ICD-10", "code": "E11.9"},
      {"id": 9002, "codeSystem": "SNOMED-CT", "code": "999999"}
    ],
    "observations": [{"id": 9101, "code": "2093-3"}],
    "medications": [{"id": 9201, "rxnormCode": "6809"}]
  }'
```

Then inspect the findings:

```bash
docker compose exec postgres psql -U clinicalharmony -d clinicalharmony \
  -c "SELECT id, entity_type, entity_id, rule_code, severity, patient_id, bad_value FROM silver.validation_reports ORDER BY id;"
```

## Project Structure

```
ClinicalHarmony/
├── docker-compose.yml
├── README.md
├── docs/
│   └── db-init/              # Ordered SQL init scripts (schemas, tables, seed data)
├── ingestion-service/         # Spring Boot + Apache Camel
├── ontology-service/          # Spring Boot validators
├── fhir-compliance-service/   # HAPI FHIR + US Core
├── patient-index-service/     # Patient Master Index + deduplication
├── rules-engine-service/      # Clinical rules engine
├── api-gateway/                # REST API for the dashboard
├── dashboard-ui/               # React
└── data-generator/             # Python synthetic data generator
```
