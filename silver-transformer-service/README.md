# silver-transformer-service

**Phase 9b — Silver over Kafka.** Spring Boot 3.5 + Spring for Apache Kafka 3.3 + HAPI HL7 v2 2.3,
port **8087** (health / metrics only — its input is Kafka, not HTTP).

```
clinicalharmony.bronze.ingested (3 partitions, from bronze-listener-service's outbox)
        │  consumer group silver-transformer          (the event carries the Bronze row id, not the payload)
        ▼
silver-transformer-service
  ├─ Bronze row PENDING?  lock it (FOR UPDATE), map the HL7, then in ONE transaction:
  │     silver.patients / conditions / observations / medications / allergies  (raw_message_id = Bronze id)
  │     Bronze row → PROCESSED, patient → PENDING_VALIDATION, outbox row
  ├─ can't be mapped      → Bronze row FAILED + reason, nothing in Silver, no event
  ├─ already PROCESSED / FAILED → skipped (silver.skipped), no second event
  └─ DB down              → 3 retries (1 s, 2 s, 4 s) → clinicalharmony.bronze.ingested.dlt
        │
        ├─ after the commit: POST rules-engine-service /api/rules/evaluate with the patient's WHOLE Silver
        │  record (rules-engine calls ontology-service) → VALIDATED, older open findings retired
        │  rules-engine / ontology down → stays PENDING_VALIDATION → sweeper retries every 30 s
        │
        └─ outbox relay (500 ms) → clinicalharmony.silver.patient.changed → 9c gold-builder
```

## What is mapped

| HL7 message | Segments → Silver |
|---|---|
| `ADT^*` (A01, A04, A08…) | **PID** → `silver.patients` (demographics updated) · **DG1** → `conditions` · **AL1** → `allergies` (new, migration 24) |
| `ORU^R01` | **PID** → `patients` (created if new) · **OBX** → `observations` with OBX-7 reference range + OBX-8 flag; time = OBX-14, else OBR-7, else MSH-7 |
| `RDE^O11`, `OMP^O09` | **PID** → `patients` (created if new) · **ORC + RXE / RXO** → `medications` (ORC-1 → status, ORC-9 → start date, dose + instructions → `dosage_text`) |

- **Which patient**: `(source_system, source_patient_id)` = (**PID-3.4** assigning authority, PID-3.1), falling
  back to MSH-4. So a lab result from `REGIONAL_LAB` or an order from `CITY_PHARMACY` for a `CITY_HOSPITAL`
  MRN lands on the hospital's Silver patient — it doesn't create a "lab patient".
- **Repeated lists vs events**: every ADT repeats the patient's diagnoses and allergies, so DG1 / AL1 are
  matched on (patient, code system, code) — a repeat refreshes the row, it doesn't add one. OBX / RXE are
  events owned by their message (`raw_message_id`).
- **Map, never invent**: anything that can't be mapped faithfully → Bronze `FAILED` with the reason, e.g.
  `PID-7 (date of birth) is empty`, `Message type SIU^S12 is not mapped to Silver`, `DG1-3.3 coding system
  'I9' … is not ICD-10 or SNOMED CT`, `OBX-3.3 … is not LOINC`, `RXE-2.3 … is not RxNorm`. Code *existence*
  (is E11.9 a real ICD-10 code?) is not checked here — ontology-service reports it as a `CODE-*` finding.
- Times without a zone are read as UTC.

## What it guarantees

| | How |
|---|---|
| **One transaction per message** | Silver rows, the Bronze status and the outbox event commit together or not at all. Offsets are committed after the transaction (`ack-mode: record`). |
| **Idempotent** | Only a `PENDING` Bronze row is transformed, under a row lock; a redelivered event finds it `PROCESSED` / `FAILED` and is a no-op (`silver.skipped`, no event). |
| **Event never lost** | `silver.outbox_events` (migration 24), relayed in id order like bronze-listener's outbox; key = the input key (`source|PID-3`), so one patient's events stay in order. |
| **Fail-open validation** | rules-engine is called *after* the commit, outside any transaction. If it (or ontology-service behind it) is down, Silver is still written and the event still goes out; the patient stays `PENDING_VALIDATION` with `validation_error`, and `RevalidationSweeper` retries every 30 s (oldest first, stops at the first failure). |
| **Findings show the current state** | rules-engine and ontology-service append findings on every evaluation. After a successful one, the patient's older open findings are marked resolved, so a problem that's still there is listed once (re-reported by the latest run). |
| **Technical failures** | 3 retries with exponential backoff, then `clinicalharmony.bronze.ingested.dlt` (same partition). A broken event (not JSON, no `rawMessageId`, no such Bronze row) goes straight to the DLT. |

## Events

**In** — `clinicalharmony.bronze.ingested` (bronze-listener-service), value:
`{"rawMessageId":42,"messageType":"HL7V2","hl7MessageType":"ADT^A01","sourceSystem":"CITY_HOSPITAL","payloadChecksum":"…","controlId":"S42-00001","receivedAt":"…"}`

**Out** — `clinicalharmony.silver.patient.changed`, value:

```json
{"silverPatientId":231,"sourceSystem":"CITY_HOSPITAL","sourcePatientId":"CH645175","rawMessageId":42,
 "hl7MessageType":"ORU^R01","controlId":"S42-00008","change":"UPDATED",
 "counts":{"conditions":0,"observations":4,"medications":0,"allergies":0}}
```

Headers: `event-id`, `event-type: silver.patient.changed`, `schema-version: 1`, `producer`, `produced-at`,
`correlation-id` (from MLLP intake, `mllp-<12 hex>`), `causation-id` (the `bronze.ingested` event id).

## Logs and metrics

One JSON object per line (Logstash layout), `correlationId` / `causationId` from MDC:
`silver.mapped` (Silver patient id, CREATED / UPDATED, row counts), `silver.failed` (reason), `silver.skipped`,
`silver.validated` (findings by severity, superseded findings), `silver.validation_deferred` (reason),
`silver.revalidation_sweep`, `silver.published` (outbox relay), `silver.delivery_failed`, `silver.deadlettered`.

Micrometer (`/actuator/metrics`): `clinicalharmony.silver.messages{outcome=transformed|failed|skipped|deadlettered}`,
`clinicalharmony.silver.validations{outcome=validated|deferred}`, `clinicalharmony.silver.findings{severity}`,
`clinicalharmony.silver.validation.pending`, `clinicalharmony.silver.outbox.backlog`, `clinicalharmony.silver.outbox.relayed{outcome}`.

## Run

```bash
docker compose up -d silver-transformer-service            # from the repo root (needs postgres + kafka)
cd silver-transformer-service && mvn spring-boot:run        # or on the host: profile local, localhost:5432 / :19092 / :8083
mvn test                                                    # 25 tests: mapper, event parsing, Testcontainers end to end
```

Config under `silver.*` in `application.yml` (partitions, retry, outbox, validation sweep, rules-engine URL /
timeouts); `RULES_ENGINE_BASE_URL` in the docker profile.

## Tests (25)

- `Hl7SilverMapperTest` (17): ADT / ORU / RDE in the generator's exact shape, PID-3.4 vs MSH-4, reference
  ranges and time fallbacks, zones, order-control → status, and every bad-data rule.
- `BronzeIngestedEventTest` (2).
- `SilverTransformerIntegrationTest` (6): Testcontainers Postgres from `docs/db-init` + cp-kafka 7.6.1, and a
  stand-in rules-engine on a JDK `HttpServer` that writes findings like the real one. Admit + result + order →
  one patient, three events, validated with a conflict; ADT update without duplicating DG1 / AL1; unmappable →
  FAILED; redelivery → no second event; rules-engine down → PENDING → sweeper → VALIDATED; broken / unknown
  events → DLT.

Postman: `postman/phase9b-silver-over-kafka.postman_collection.json` (happy path, 6 requests).
