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

| Service                     | Responsibility                                              |
|-----------------------------|---------------------------------------------------------------|
| `ingestion-service`         | Apache Camel routes for HL7 v2, FHIR JSON, and Claims CSV intake |
| `ontology-service`          | Validates codes against ICD-10, SNOMED CT, LOINC, RxNorm       |
| `patient-index-service`     | Patient Master Index — cross-source deduplication              |
| `fhir-compliance-service`   | HAPI FHIR-based US Core IG conformance and resource generation |
| `rules-engine-service`      | Evaluates clinical validation rules against Silver entities    |
| `api-gateway`               | REST API surface consumed by the dashboard                     |
| `dashboard-ui`              | React dashboard (5 views)                                      |
| `data-generator`            | Python-based synthetic clinical data generator                 |

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

| Component        | Address                          |
|-------------------|-----------------------------------|
| PostgreSQL        | `localhost:5432` (`clinicalharmony` / `clinicalharmony`) |
| Kafka             | `localhost:9092`                  |
| API Gateway       | `localhost:8080`                  |
| Dashboard UI       | `localhost:3000`                  |

> **Status**: This is Phase 1 (Foundation) — data platform and infrastructure
> are fully defined. Application services currently start as placeholders and
> will be implemented in subsequent phases.

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
