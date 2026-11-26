# Build history

How ClinicalHarmony was built, phase by phase. Pull request numbers refer to this repository.

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
| **9** | Event-driven pipeline + `data-generator` | Rebuild the Bronze → Silver → Gold flow on Kafka, **one layer at a time** (9a–9e below), fed by a Python generator of synthetic hospital data. Each sub-phase is its own branch and PR | ✅ Done (PR #15 – #20) |
| **9a** | Bronze over Kafka | `data-generator` v1 sends 100 seeded HL7 v2 messages over **MLLP**; ingestion-service publishes them to Kafka; a new **bronze listener** stores them in `bronze.raw_messages` and announces each one. **Kafbat UI** is added to Compose to watch topics, messages and consumer lag. Details in [Phase 9 design](architecture/event-pipeline.md). FHIR and claims REST intake unchanged for now | ✅ Done (PR #15) |
| — | Dashboard ordering fixes | PMI Review: decided candidates newest first (the queue stays highest score first), a "Recently decided" card (who · when · note), and a run's Auto-merged / To review counts link to that run's candidates. FHIR Viewer: ids sorted by number (`-8 < -80 < -100`) with clickable column headers, and **Download .json** next to Copy JSON. Data Lineage: transformations in pipeline order | ✅ Done (PR #16) |
| **9b** | Silver transformation | A new **silver transformer** turns each Bronze HL7 message into `silver.*` rows linked to the raw message (PID → patient keyed on the MRN's assigning authority, DG1 → conditions, OBX → observations with reference ranges, RXE → medications, AL1 → the new `silver.allergies`), marks the Bronze row PROCESSED or FAILED with the reason, validates the patient's whole record through rules-engine + ontology (fail-open, with a re-validation sweeper), and announces each changed patient. The generator gains pharmacy orders, some for a drug the patient is allergic to. Closes the "no Bronze → Silver parser" gap. Details in [Phase 9 design](architecture/event-pipeline.md) | ✅ Done (PR #17) |
| **9c** | Gold over Kafka | A consumer in fhir-compliance-service (`gold-builder`) builds and validates US Core FHIR for each changed patient — now including **AllergyIntolerance** from the AL1 allergies — and writes Gold through **GoldWriter**, so no more `POST /api/fhir/build`; lineage starts at the raw HL7 message; a consumer in patient-index-service (`pmi-matcher`) runs matching, debounced (one run 5 s after a burst, at most every 30 s). The generator registers ~10% of patients at both CITY_HOSPITAL and NORTH_CLINIC: identical ones auto-merge, fuzzy ones go to PMI Review. **FHIR Viewer** (follow-up from testing): newest first, filters by patient (MRN or name), source system, origin (HL7 pipeline / Silver seed / API / Gold seed) and code or name, and each row shows its patient and origin. Details in [Phase 9 design](architecture/event-pipeline.md) | ✅ Done (PR #18) |
| — | Data Lineage layer browser | Bronze / Silver / Gold as tabs (the Overview's layer cards, with counts): a new **Silver patients** list and **Silver patient page** (its Bronze messages, conditions / labs / meds / allergies each linked to the Gold FHIR resource built from it, validation findings, Gold master), the Gold masters with their FHIR resource counts, a trace that starts from **any** layer (Bronze message, Silver patient, master, FHIR resource), and every id on every page a link both ways. Bronze messages show (and search by) the patient each message is about | ✅ Done (PR #19) |
| **9d** | Full generator | All feeds: FHIR JSON, claims CSV and C-CDA alongside HL7; ~20% of patients in more than one feed (typos, nicknames, swapped dates) for the PMI; ~10–15% defects for validation; `--seed` for repeatable demos, `--reset`, and a `--trickle` mode that looks like a live feed. REST intake paths move onto Kafka too | ✅ Done (PR #20): part 1 REST intake (HL7, FHIR, claims, **C-CDA**) on Kafka, migration 26; part 2 FHIR / claims / C-CDA → Silver (`silver.claims`, migration 27), Bronze backlog sweeper, generator for every feed; claim lifecycle (migration 28), legacy REST rows closed (migration 29) |
| **9e** | Observability | **Splunk** (local container) gets every service's JSON logs (Docker's splunk log driver → HEC) and metrics (Micrometer → OTLP → **OpenTelemetry Collector** → HEC; the collector also reads Kafka consumer lag and checks every service's health). A **Pipeline** dashboard (throughput by layer, consumer lag, dead letters, quarantine rate, end-to-end latency, service health, triggered alerts) and a **Trace by correlation id** dashboard; alerts on consumer lag, dead letters and service down, all configuration as code in [`observability/`](../observability) | ✅ Done (PR #20) — see [Observability](../README.md#observability) |
| **10** | Polish | Final end-to-end Docker run, interview demo script (legacy HL7 → FHIR → dashboard), finished architecture diagrams, documentation | 🔄 In progress |

## Known limitations, in detail

- **Matching only sees what's in Gold, and only Kafka-fed patients trigger it.** Since 9c the gold-builder
  builds every Silver patient announced on `clinicalharmony.silver.patient.changed`, and pmi-matcher runs
  matching after each burst. `POST /api/fhir/build` still builds on demand (seed patients, `docs/db-init/17`,
  `21`, have no Bronze message, so their lineage still starts at Silver) but announces nothing, so it doesn't
  trigger matching — `POST /api/pmi/match/run` does. Blocking is in memory per run — fine at demo scale, not
  at millions of records.
- **CMS-0057 payer data is a synthetic seed** (`docs/db-init/19`). Nothing upstream produces
  Coverage, PractitionerRole, MedicationKnowledge, MeasureReport or Device yet.
- **Claims stay in Silver.** A claims line becomes a member, an ICD-10 condition (which reaches Gold as a US Core
  Condition) and a `silver.claims` row; there's no FHIR Claim / ExplanationOfBenefit in Gold. Rows posted over REST
  *before* 9d (`intake_channel = REST`) were never announced; migration 29 marks them `FAILED` with a "resend it" reason.
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
