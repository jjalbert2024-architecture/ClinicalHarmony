# patient-index-service

**Phase 6** — the Patient Master Index (PMI). It decides which `silver.patients` rows, arriving from
different source systems, are the same person, and keeps `gold.master_patients` /
`gold.patient_source_xref` / `gold.fhir_resources.master_patient_id` consistent with those
decisions. Port **8085**.

## Who writes Gold

GoldWriter in fhir-compliance-service stays the **only** writer of `gold.*`. This service:

- **reads** `silver.patients` and `gold.*` directly (SELECT only, schema-qualified);
- keeps its **own working state** in the `pmi` schema — runs, the review queue, exclusions;
- applies every Gold change by **calling GoldWriter over HTTP**
  (`POST /api/fhir/gold/masters/merge`, `/{id}/confirm`, `/merges/{id}/unmerge` on 8084), with its
  **own** Keycloak client (`patient-index-service`, client_credentials, scope
  `clinicalharmony/pipeline.write`) — never the caller's token. GoldWriter applies each decision in
  one transaction and refuses (409) anything Gold's current state doesn't allow.

```
caller ──Bearer (pipeline.write)──▶ patient-index-service ──own Bearer + X-Correlation-Id──▶ GoldWriter ──▶ gold.*
                                         │ reads silver.patients, gold.*      (fhir-compliance-service)
                                         └ writes pmi.match_runs / match_candidates / match_exclusions
```

The caller's `X-Correlation-Id` is forwarded, so one id follows a decision through both services'
logs (the smoke test checks this).

## Matching algorithm

Weights and thresholds live in `ontology.clinical_rules` rule **`PMI_MATCH`** (`rule_expression`
JSON, migration 20), read fresh on every call — tuning is a data change, like the Phase 4/5 rules.
Values below are the defaults.

**1. Working set.** Silver patients under *active* masters. A pair is scored only if its two records
are under different masters, at least one of them `PROVISIONAL` (decided masters aren't
re-litigated), and no human has excluded the pair.

**2. Blocking** — only records sharing a key are compared: exact birth date · Double Metaphone of
the surname + birth year (catches Wilson/Willson) · phone. Built in memory per run (fine at demo
scale; production would persist and index the keys). Blocks over 500 records are skipped with a
warning.

**3. Normalization** — names: lower-case, accents and punctuation stripped (`Garcia-Lopez` →
`garcialopez`); phone: 10 digits; ZIP: 5 digits; address: USPS abbreviations (`Avenue` → `ave`,
`#4` → `apt 4`); `unknown` gender, blank names = **missing**. A missing value scores 0 and drops
out of the maximum: it is neither evidence for nor against.

**4. Deterministic rules** (score 1.0, auto-merge) — unless the birth dates disagree outright or
the sexes conflict:
- `SAME_SOURCE_MRN` — same source system and same MRN (one feed registered the person twice);
- `EXACT_NAME_DOB_PLUS_PHONE_OR_ZIP` — exact first + last name + DOB, plus exact phone or ZIP.

**5. Weighted agreement** (Fellegi-Sunter-style log-likelihood points):

| Field | Agree | Partial | Disagree |
|---|---|---|---|
| Last name | +4.0 exact | +2.5 Jaro-Winkler ≥ 0.92 | −3.0 |
| First name | +3.5 exact | +2.0 nickname (Jim ≈ James) or JW ≥ 0.90 · +0.5 initial only | −3.0 |
| Birth date | +5.0 exact | +2.0 day/month swapped, or one of y/m/d differs | −5.0 |
| Gender | +0.5 same | — (unknown/other = missing) | −4.0 male vs female |
| Phone | +4.0 | — | −1.0 |
| ZIP (5) | +1.5 | — | −0.5 |
| Address line | +2.5 normalized exact | +1.5 JW ≥ 0.90 | −0.5 |

`score = Σ weights / Σ max weights of the fields present on both`, clamped to [0, 1].

**6. Decision** — `≥ 0.85` **auto-merge** · `0.60 – 0.85` **review queue** · `< 0.60` no match.
One guard beyond the agreed weights: a pair may only auto-merge if at least **10 points** of
evidence were comparable (`minComparableWeight`). Two records that share nothing but a birth date
score 5/5 = 1.0, which is far too little to merge without a human; they go to review instead.

**7. Applying a run** (`POST /api/pmi/match/run`):
- auto-merge pairs form clusters of masters (union-find); each cluster collapses into its **oldest
  master** (lowest id), one GoldWriter merge per absorbed master, each carrying its best-scoring
  pair (score, method, per-field breakdown) as evidence;
- review pairs become one `PENDING` item per pair of (post-merge) masters — the best-scoring Silver
  pair — unless that master pair is already under review; review items whose records have since
  ended up under one master are marked `SUPERSEDED`;
- every other `PROVISIONAL` master is **confirmed** `MATCHED` (its own person). Masters waiting on
  review, or in a merge GoldWriter refused, stay `PROVISIONAL` for the next run.

If GoldWriter refuses one decision (409: the state changed underneath), it's skipped and reported in
`skipped`; the run carries on. If GoldWriter is unreachable the run is marked `FAILED`; what was
applied stays applied, and the next run resumes from Gold's state. Runs and review decisions are
serialized within the instance.

**Human decisions are remembered.** Rejecting a review item, or undoing a merge, writes
`pmi.match_exclusions` rows ("these two Silver patients are not the same person"); runs never
propose an excluded pair again.

### On the seed data (`docs/db-init/21`)

| Record | vs | Score | Outcome |
|---|---|---|---|
| PMI-0001 Maria Garcia (claims, `(512) 555-0101`, "Avenue") | SEED-0001 | 1.0 deterministic | auto-merged |
| PMI-0002 "Jim" Wilson (lab, no phone, "Street") | SEED-0002 | 0.9118 | auto-merged |
| PMI-0006 James "Willson" (same Cerner MRN) | SEED-0002 | 1.0 `SAME_SOURCE_MRN` | auto-merged |
| PMI-0003 Maria Garcia, DOB 12-04 vs 04-12, no phone | Garcia master | 0.6471 | review |
| PMI-0004 **Mario** Garcia, same DOB/address/phone, male | Garcia master | 0.7143 (gender −4) | review |
| PMI-0005 Robert Wilson, same DOB as James | SEED-0002 | 0.2143 | no match → confirmed |

## Endpoints (port 8085)

`GET`s need any valid Bearer token from the `clinicalharmony` realm (they show demographics, so
they're never public); `POST`s need `clinicalharmony/pipeline.write`. `/actuator/health` is public.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/pmi/match/run` | Run matching over every `PROVISIONAL` master → run counts, auto-merges, queued review ids, confirmed masters, skipped decisions, and how many Silver patients aren't in Gold yet |
| `GET` | `/api/pmi/runs[?limit=]` · `/api/pmi/runs/{id}` | Run history (`triggeredBy` = `api:<client>`) |
| `GET` | `/api/pmi/masters[?status=PROVISIONAL\|MATCHED\|MERGED&limit=]` | List masters |
| `GET` | `/api/pmi/masters/{id}` | One master: its Silver sources, linked Gold FHIR resources, merges (as survivor or absorbed, incl. undone), `mergedInto` if absorbed, pending review items |
| `GET` | `/api/pmi/candidates[?status=PENDING\|…\|ALL&limit=]` | Review queue (default `PENDING`, best score first) |
| `GET` | `/api/pmi/candidates/{id}` | One review item with both Silver records side by side |
| `POST` | `/api/pmi/candidates/{id}/accept` | `{reviewer, note?}` → merge the two records' *current* masters (into the older) via GoldWriter, `MANUAL`. 409 if not `PENDING`, or if both records already share a master (item → `SUPERSEDED`) |
| `POST` | `/api/pmi/candidates/{id}/reject` | `{reviewer, note?}` → `REJECTED` + exclusion; confirms either master if it's `PROVISIONAL` with nothing else pending |
| `POST` | `/api/pmi/merge` | `{survivorMasterId, absorbedMasterId, reviewer, note?}` → manual merge (best cross pair stored as evidence); 404 unknown, 409 inactive or self |
| `POST` | `/api/pmi/merges/{mergeId}/unmerge` | `{reviewer, reason}` (reason required) → GoldWriter undoes the merge; exclusions added between the records it had joined |
| `GET` | `/api/pmi/score?a={silverId}&b={silverId}` | Explain how two Silver patients score right now (read-only) |

Errors are `{"error", "message"}` (+ `fields` for body validation): 400 invalid input, 401/403
with an RFC 6750 `WWW-Authenticate`, 404, 409, and **502** when GoldWriter (or Keycloak, for the
service's own token) can't be reached. GoldWriter's own 404/409 keep their status, prefixed
"GoldWriter refused the change: …".

## Data

| Table | Written by | Holds |
|---|---|---|
| `pmi.match_runs` | this service | One row per run: who triggered it, counts, settings snapshot, status |
| `pmi.match_candidates` | this service | Scored pairs: `PENDING` review items, `AUTO_MERGED` (audit), `ACCEPTED` / `REJECTED` / `SUPERSEDED`, with the breakdown JSON |
| `pmi.match_exclusions` | this service | "Not the same person" pairs (`REVIEW_REJECTED`, `UNMERGED`) |
| `gold.master_patient_merges` | GoldWriter | Merge history: survivor, absorbed, exactly which xref / FHIR rows moved, score, method, evidence, unmerge details |
| `gold.data_lineage` | GoldWriter | `PMI_MERGE` / `PMI_UNMERGE` (target: the merge row), `PMI_MATCH` (target: the master) |

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | `local` (datasource `localhost:5432`) or `docker` (`DB_*` vars) |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `postgres` / `5432` / `clinicalharmony` ×3 | docker profile datasource |
| `OAUTH_ISSUER_URI` | `http://localhost:8180/realms/clinicalharmony` | Required `iss` of incoming tokens |
| `OAUTH_JWK_SET_URI` | `http://localhost:8180/realms/.../certs` | Signing keys (compose: `keycloak:8080`) |
| `GOLD_WRITER_BASE_URL` | `http://localhost:8084` | fhir-compliance-service (compose: `fhir-compliance-service:8084`) |
| `OAUTH_TOKEN_URI` | `http://localhost:8180/realms/clinicalharmony/protocol/openid-connect/token` | Where this service gets its own token (compose: `keycloak:8080`) |
| `PMI_CLIENT_ID` / `PMI_CLIENT_SECRET` | `patient-index-service` / `patient-index-service-secret` | Its Keycloak client — **dev-only** secret, from `keycloak/realm-clinicalharmony.json` |

## Run and test

```bash
mvn test                      # 55 tests; the integration test needs Docker (skipped without it)
mvn spring-boot:run           # local profile: Postgres on 5432, Keycloak on 8180, fhir-compliance on 8084
```

- **Unit** — `JaroWinklerTest` (Winkler's published values), `NormalizerTest`, `PatientMatcherTest`
  (every seed scenario at its exact score, guards, settings from JSON), `BlockingIndexTest`,
  `MatchingRunServiceTest` (clusters → oldest master, review dedup per master pair, confirm rules,
  refused merges, GoldWriter down), `ReviewServiceTest`.
- **HTTP client** — `GoldWriterClientTest` runs against a real JDK `HttpServer` stub: token caching,
  Basic-auth client_credentials form, Bearer + correlation id forwarding, 401 → new token → one
  retry, 409 → rejected with GoldWriter's message, 5xx / unreachable → unavailable.
- **Web** — `PatientIndexControllerTest`: the real security chain (401/403, read vs write), 400s,
  404/409/502 mapping.
- **Integration** — `integration/PatientIndexIntegrationTest`: Testcontainers Postgres 16 from
  `../docs/db-init` (incl. the seed duplicates), self-signed JWTs, and a *fake* GoldWriter over real
  HTTP that applies decisions with plain SQL (the real GoldWriter has its own integration test in
  fhir-compliance-service; the smoke test runs both together). Covers run → no-op re-run → reject →
  accept → master view → unmerge → re-run respecting every human decision.
- **Smoke** — section 15 of `scripts/smoke-test.sh` creates fresh synthetic duplicates each run
  (random surname and birth date) and drives the real services end to end.
- **Postman** — [`postman/phase6-patient-index.postman_collection.json`](postman): 8 requests, run
  1 → 8 in order — `1` auth, `2` run, `3` review queue (stores the ids the rest use), `4` master
  view, `5` reject the twin, `6` accept the DOB swap, `7` unmerge, `8` run again. The two seed review
  items can only be decided once; auth and error cases are covered by the smoke test, not Postman.
