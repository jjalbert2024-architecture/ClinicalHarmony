# ingestion-service

Spring Boot + Apache Camel + HAPI HL7v2. The Ingestion & Harmonization Layer's intake tier: accepts all four
ClinicalHarmony source formats, checks their syntax, and publishes every payload — valid or not — to Kafka.
[bronze-listener-service](../bronze-listener-service) then lands it in `bronze.raw_messages` with a SHA-256
checksum (malformed payloads QUARANTINED with the reason, never dropped). **No database since Phase 9d.**

## Intake paths

| Source | Transport | Endpoint / Port | Kafka topic |
|---|---|---|---|
| HL7 v2 (ADT, ORU, RDE, …) | MLLP (real-time hospital interface, since 9a) | TCP `8887` | `clinicalharmony.bronze.hl7v2.raw` |
| HL7 v2 | REST (since 9d) | `POST /api/ingestion/hl7`, `text/plain` | same topic, value `intakeChannel: REST` |
| FHIR JSON (resource or Bundle) | REST | `POST /api/ingestion/fhir`, `application/json` or `application/fhir+json` | `clinicalharmony.bronze.fhir.raw` |
| Claims CSV (whole file) | REST | `POST /api/ingestion/claims`, `text/plain` or `text/csv` | `clinicalharmony.bronze.claims.raw` |
| C-CDA document (9d) | REST | `POST /api/ingestion/ccda`, `application/xml` or `text/xml` | `clinicalharmony.bronze.ccda.raw` |

- **Source system**: HL7 from `MSH-4` (sending facility); the others from an `X-Source-System` header
  (`[A-Za-z0-9 ._:-]{1,100}`, else 400), or the per-format default.
- **Syntax checks** (same rules as bronze-listener's PENDING/QUARANTINED decision): HL7 parses (HAPI 2.3, v2.5
  structures, no conformance validation) with MSH-9 and MSH-10; FHIR is a JSON object with a `resourceType` (US Core
  conformance is `fhir-compliance-service`'s job); claims = header + ≥1 row, every row as wide as the header; C-CDA is
  well-formed XML with no DOCTYPE (no external entities ever resolved), root `{urn:hl7-org:v3}ClinicalDocument` with
  `recordTarget/patientRole`.
- **Claims** files are ONE Kafka event; bronze-listener-service splits them into one Bronze row per claim line
  (header + that line), all-or-nothing per file.
- **Delivery**: every path blocks until Kafka confirms (`acks=all`, idempotent producer, 10 s max), so a sender is
  only told "accepted" for what Kafka holds.
  - **MLLP**: peeks at MSH/PID (no HAPI), key `source system | PID-3`, correlation id `mllp-<12 hex>`. ACK `AA` — even
    for a malformed message (the listener quarantines it) — or `AE` "not stored, retry later". The ACK echoes MSH-10
    in MSA-2. (Before 9a Camel auto-generated the ACK; the route now sets `CamelMllpAcknowledgementString`.)
  - **REST** (JSON body, `X-Correlation-Id` echoed): **202** passed the check + published (`correlationId`, `eventId`,
    topic/partition/offset, checksum, format details — no Bronze id, the row is written asynchronously); **422** failed
    the check, published anyway (Bronze → QUARANTINED); **503** + `Retry-After: 5` Kafka didn't confirm, nothing stored;
    **413** the event would exceed one Kafka record (`ingestion.kafka.max-event-bytes`, 1 000 000); **400** unusable
    `X-Source-System`. Correlation id = the caller's `X-Correlation-Id` (`[A-Za-z0-9._:-]{1,64}`) else `rest-<12 hex>`.
    Partition key: source | patient id (FHIR Patient identifier, C-CDA patientRole id) else `sha256:<checksum>`.

## Running

Built and started via `docker-compose up` from the repo root (see the root README), or
locally with `mvn spring-boot:run` against Kafka on `localhost:19092` (`KAFKA_BOOTSTRAP_SERVERS`; no database).
Logs are one JSON object per line (Logstash layout, `event` = `hl7.received` / `hl7.published` /
`hl7.publish_failed` / `rest.received` / `rest.published` / `rest.publish_failed` / `rest.too_large`); metrics
`clinicalharmony.hl7.mllp.received{outcome}`, `clinicalharmony.hl7.mllp.ack.latency` and
`clinicalharmony.ingestion.rest.received{format,outcome}` at `/actuator/metrics`. Health:
`GET /actuator/health` on `8081`.
