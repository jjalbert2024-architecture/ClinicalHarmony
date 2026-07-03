# ingestion-service

Spring Boot + Apache Camel + HAPI HL7v2. The Ingestion & Harmonization Layer's intake tier:
accepts all three ClinicalHarmony source formats and lands every payload — valid or not — in
`bronze.raw_messages` with a SHA-256 checksum. Malformed payloads are quarantined with the
parse error captured, never dropped.

## Intake paths

| Source | Transport | Endpoint / Port |
|---|---|---|
| HL7 v2 (ADT, ORU, ...) | MLLP (real-time hospital interface) | TCP `8887` |
| HL7 v2 | REST (testing/demo) | `POST /api/ingestion/hl7` on `8081`, `Content-Type: text/plain` |
| FHIR JSON (resource or Bundle) | REST | `POST /api/ingestion/fhir` on `8081`, `Content-Type: application/json` |
| Claims CSV (whole file) | REST | `POST /api/ingestion/claims` on `8081`, `Content-Type: text/plain` or `text/csv` |

- FHIR and Claims callers may identify themselves with an `X-Source-System` header; HL7
  senders are identified from `MSH-4` (sending facility).
- Claims files land **one Bronze row per claim line** (each payload self-describing:
  header + that line), all-or-nothing per file.
- FHIR validation here is syntactic only (well-formed JSON with a `resourceType`) —
  US Core profile conformance belongs to `fhir-compliance-service`.
- HL7 senders receive a real ACK/NAK; REST callers get JSON
  (`201 Created` = landed as `PENDING`, `422 Unprocessable Entity` = landed as `QUARANTINED`).

## Running

Built and started via `docker-compose up` from the repo root (see the root README), or
locally with `mvn spring-boot:run` against a Postgres on `localhost:5432`. Health:
`GET /actuator/health` on `8081`.
