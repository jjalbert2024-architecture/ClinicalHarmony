# Event pipeline design (Phase 9)

How Bronze → Silver → Gold runs on Kafka, layer by layer: topics, consumer groups, writes, partition keys,
idempotency, outbox, dead letters, log events and metrics. The [README](../../README.md) has the overview;
this is the detail behind it. Keep it in sync with the code.

Each layer is decoupled from the next by a Kafka topic. A service only knows the topic it reads and
the topic it writes, never the service on the other side, so any layer can be stopped, replayed,
scaled or replaced on its own. The same building blocks repeat in every layer: **topic in → listener
(consumer group) → tables → topic out**, with a dead-letter topic and structured logs at each step.

| | **9a — Bronze** | **9b — Silver** | **9c — Gold** |
|---|---|---|---|
| **Input** | HL7 v2 over MLLP (port 8887) from `data-generator` | Topic `clinicalharmony.bronze.ingested` | Topic `clinicalharmony.silver.patient.changed` |
| **Producer → topic** | ingestion-service validates the MLLP frame and publishes the raw message to `clinicalharmony.bronze.hl7v2.raw`. It sends ACK `AA` only after Kafka confirms the write (`acks=all`, idempotent producer); if Kafka can't take it, the sender gets `AE` ("not stored, retry later") and retries. It only *peeks* at MSH/PID for the key and source system, so a malformed message is still accepted (`AA`) and quarantined by the listener — Bronze keeps everything | — | — |
| **Listener (consumer group)** | New **bronze listener** service (`bronze-writer`) | New **silver transformer** service (`silver-transformer`) | Consumer inside **fhir-compliance-service** (`gold-builder`), so GoldWriter stays the only Gold writer; then a consumer inside **patient-index-service** (`pmi-matcher`) |
| **What it does** | Stores every message as it arrived and checks it parses as HL7 v2 | Reads the raw message from Bronze by id (the event carries the id, not the payload). Maps **PID** → patient (keyed on PID-3.4, the MRN's assigning authority, so a lab result or pharmacy order lands on the hospital's patient), **DG1** → conditions, **OBX** → observations (+ reference range), **RXE / RXO** → medications, **AL1** → allergies (new `silver.allergies`, migration 24). After the commit it calls rules-engine `POST /api/rules/evaluate` (which calls ontology-service) with the patient's whole Silver record and real Silver ids | Rebuilds the changed Silver patient (read by id, the event carries no clinical data): US Core Patient / Condition / Observation / MedicationRequest and, new in 9c, **AllergyIntolerance** from `silver.allergies`, checks each with the official HL7 validator, and writes them through GoldWriter in one transaction with the outbox event. Non-compliant resources are still written, with their violations in `compliance_notes`. `pmi-matcher` then runs matching — **debounced**: one run 5 s after the last event of a burst, at most 30 s after the first (runs are serialized by the PMI lock; a busy lock or a failed run is retried after 30 s) |
| **Writes** | `bronze.raw_messages`: `PENDING`, or `QUARANTINED` with the parse error | `silver.patients` (upsert on `source_system` + `source_patient_id`; ADT updates demographics), `silver.conditions` / `observations` / `medications` / `allergies` with `raw_message_id`; findings in `silver.validation_reports` (older open findings of the patient retired after each successful run); Bronze row → `PROCESSED`, or `FAILED` with the reason | `gold.fhir_resources`, `gold.master_patients` (PROVISIONAL) + `patient_source_xref`, `gold.data_lineage` with the full **BRONZE → SILVER → GOLD** chain and hashes (the BRONZE step names the MLLP → Kafka → bronze-listener path), `gold.outbox_events` (migration 25, written by GoldWriter); PMI merges and review queue as today (`pmi.match_runs.triggered_by = kafka:pmi-matcher (…)`) |
| **Output topic** | `clinicalharmony.bronze.ingested` (raw message id, type, checksum), for `PENDING` rows only. Quarantined messages stay in Bronze for a data steward | `clinicalharmony.silver.patient.changed` (Silver patient id, source, raw message id, `CREATED` / `UPDATED`) | `clinicalharmony.gold.patient.changed` (master id, Silver patient id, resources with their Gold ids and compliance, compliance summary, raw message id) → read by `pmi-matcher` |
| **Partition key** (keeps one patient's messages in order) | Source system + PID-3 patient id; MSH-10 control id when PID can't be read | Same patient key | Master patient id |
| **Safe when a message arrives twice** | Partial unique index on source system + `payload_checksum` for the Kafka-fed rows (`intake_channel` `MLLP_KAFKA`, and since 9d `REST_KAFKA`: migration 26); the insert is `ON CONFLICT DO NOTHING`, a repeat — through either door — is logged `bronze.duplicate` and gets no second event. (Rows written directly by the pre-9d REST paths, `intake_channel = REST`, stay outside the index: they hold legitimate identical copies) Only a `PENDING` Bronze row is transformed (row-locked); a redelivered event finds it `PROCESSED` / `FAILED` and does nothing. Diagnoses and allergies that every ADT repeats are matched on (patient, code), not added again | `fhir_resources` upsert on (`resource_type`, `fhir_id`), lineage upsert on (target, step), both existing unique indexes; the master is reused. A redelivered event rebuilds the same rows and announces the patient again, which the debounced matcher absorbs |
| **Event never lost or sent for a rolled-back write** | The output event is written to an **outbox** table in the same transaction as the Bronze row, then relayed to Kafka by a poller in the same service (`bronze.outbox_events`, id order, `FOR UPDATE SKIP LOCKED`, stops at the first failure so nothing overtakes it) | Same outbox pattern | Same outbox pattern (`gold.outbox_events`, migration 25). `pmi-matcher` commits each offset once the event is noted; a crash before the debounced run loses only the trigger, and a catch-up run is scheduled at startup when PROVISIONAL masters wait |
| **Bad data** (a business problem, kept and shown) | `QUARANTINED` in Bronze | Bronze row `FAILED` with the mapping error; nothing half-written to Silver | Violations recorded in `compliance_notes` and the Validation Report |
| **Technical failure** (database or a service down) | 3 retries with exponential backoff (1 s, 2 s, 4 s), then dead-letter topic `clinicalharmony.bronze.hl7v2.raw.dlt` (same partition, with Spring Kafka's exception headers). A broken event (not JSON, checksum mismatch) can't be fixed by retrying: straight to the DLT | Retries, then `clinicalharmony.bronze.ingested.dlt`. If rules-engine (or ontology behind it) is down, Silver is still written and the patient stays `PENDING_VALIDATION`; a sweeper re-validates every 30 s | Retries (1 s, 2 s, 4 s), then `clinicalharmony.silver.patient.changed.dlt`; a broken event (not JSON, no `silverPatientId`, unknown Silver patient) straight to it. `pmi-matcher` dead-letters broken events to `clinicalharmony.gold.patient.changed.dlt` |
| **Structured log events** | `hl7.received`, `hl7.published` (partition, offset), `hl7.publish_failed`, `bronze.stored` (raw message id), `bronze.quarantined` (reason), `bronze.duplicate`, `bronze.delivery_failed` (attempt), `bronze.deadlettered`, `bronze.published` (outbox relay) | `silver.consumed`, `silver.mapped` (Silver patient id, CREATED / UPDATED, row counts), `silver.failed` (reason), `silver.skipped`, `silver.validated` (findings by severity), `silver.validation_deferred`, `silver.revalidation_sweep`, `silver.published` | `gold.consumed`, `gold.built` (resources, compliant vs not, master id), `gold.lineage.written`, `gold.published`, `gold.delivery_failed`, `gold.deadlettered`; `pmi.signalled`, `pmi.run` (run id, events, auto-merges, queued for review, waited ms), `pmi.run_deferred`, `pmi.run_failed`, `pmi.catch_up_scheduled` |
| **Watch it live** (dev) | **Kafbat UI** on `localhost:8090` (read-only, Apache-2.0 Kafka console): message counts per topic, `bronze-writer` lag per partition, raw HL7 on the topic, the `.dlt` topic | Same console: `bronze.ingested`, `silver-transformer` lag | Same console: `silver.patient.changed` / `gold.patient.changed`, `gold-builder` and `pmi-matcher` lag |
| **Metrics** (for the 9e dashboards) | Messages in, quarantined, ACK latency, consumer lag — as Micrometer meters `clinicalharmony.hl7.mllp.received{outcome}`, `clinicalharmony.hl7.mllp.ack.latency`, `clinicalharmony.bronze.messages{outcome}`, `clinicalharmony.bronze.outbox.backlog` (`/actuator/metrics`); lag from Kafbat / `kafka-consumer-groups` `clinicalharmony.silver.messages{outcome}`, `clinicalharmony.silver.validations{outcome}`, `clinicalharmony.silver.findings{severity}`, `clinicalharmony.silver.validation.pending`, `clinicalharmony.silver.outbox.backlog`; lag from Kafbat | `clinicalharmony.gold.builds{outcome}`, `clinicalharmony.gold.resources{compliant}`, `clinicalharmony.gold.outbox.backlog`, `clinicalharmony.gold.end_to_end.latency` (MLLP received → Gold written), `clinicalharmony.pmi.runs{trigger,outcome}`, `clinicalharmony.pmi.auto_merges`, `clinicalharmony.pmi.reviews_queued` |
| **Done when** | 100 generated messages → ~90 `PENDING` + ~10 `QUARANTINED`, no duplicates after a listener restart mid-run, visible in **Data Lineage → Bronze** The valid messages become `PROCESSED` with their Silver rows listed in **Data Lineage → Bronze**, findings (incl. the planted medication–allergy conflicts) in **Validation Report**; the patients reach **Patient Timeline** after `POST /api/fhir/build` (automatic from 9c) | **FHIR Viewer** shows the resources (incl. AllergyIntolerance) with no manual build, **Data Lineage** traces them back to the raw HL7 bytes, **PMI Review** shows the generated fuzzy duplicates and the identical ones are already merged (**Patient Timeline**: one master, both MRNs) |

**9d part 1 — REST intake on Kafka.** `POST /api/ingestion/{hl7,fhir,claims,ccda}` run the same syntax check as
before, then publish to `clinicalharmony.bronze.{hl7v2,fhir,claims,ccda}.raw` (HL7 shares MLLP's topic, value
`intakeChannel: REST`) and block on `acks=all`: **202** with `correlationId` + `eventId`, **422** when the check fails
(published anyway → `QUARANTINED`), **503** + `Retry-After: 5` when Kafka doesn't confirm, **413** above one Kafka
record (~1 MB), **400** for an unusable `X-Source-System`. ingestion-service no longer has a database. The
`bronze-writer` group consumes all four topics (DLT `<topic>.dlt` each); a **claims file is one event that
bronze-listener splits into one row (+ one `bronze.ingested`, `claimLine` n of `claimLines`) per line in one
transaction**. C-CDA is a new Bronze format (`CCDA_XML` / `XML`: well-formed, no DOCTYPE, HL7 v3 `ClinicalDocument`
with `recordTarget/patientRole`). Partition keys: source + patient id (FHIR Patient identifier, C-CDA patientRole id),
else the checksum. Metrics `clinicalharmony.ingestion.rest.received{format,outcome}` and
`clinicalharmony.bronze.messages{outcome,format,channel}`; log events `rest.received` / `rest.published` /
`rest.publish_failed` / `rest.too_large`.

**9d part 2 — every feed into Silver.** silver-transformer picks the mapper by the Bronze row's format: HL7 v2 as
before; a **FHIR Bundle** (its Patient, by the MR identifier, + Conditions / Observations / MedicationRequests /
AllergyIntolerances that reference it); a **claims line** (the member from the new member columns, the diagnosis as an
ICD-10 condition, the line itself in `silver.claims`, migration 27); a **C-CDA** document (patientRole + Problems,
Medications, Results and Allergies sections). Anything Silver can't hold faithfully (a resource about another patient,
a code from an unsupported system, a missing date, a claims line without member columns) → Bronze `FAILED` with the
reason. A **Bronze backlog sweeper** (every 60 s and at startup) transforms Kafka-fed rows still `PENDING` after 2
minutes — the rows stored during part 1, or any row whose event was lost. The generator feeds all four formats (see
[data-generator](../../data-generator/README.md)): ~20% of people in two feeds as EXACT / NICKNAME / TYPO / SWAPPED_DATE
variants with the PMI outcome they should get, and ~12% of feed records with one planted clinical defect.

**Shared by every layer**

- **Event envelope.** JSON value plus Kafka headers: `event-id` (UUID), `event-type`, `schema-version`,
  `produced-at`, `producer` (service name), `correlation-id` (set once at intake — MLLP `mllp-…`, REST `rest-…` or the caller's `X-Correlation-Id` — and carried through
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
