# Design diagrams (to draw)

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
  Kafka, Kafbat UI, plus the Phase 9 bronze listener and silver transformer. Arrows labelled with protocol
  (MLLP, REST, Kafka, JDBC, OIDC).
- [ ] **3. Event flow (Phase 9 target)** — the [Phase 9 design](../architecture/event-pipeline.md)
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
