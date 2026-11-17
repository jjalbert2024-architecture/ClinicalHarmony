# Dashboard click-through (Phase 8 + ordering fixes)

A short manual check of the five views, done in a browser against the running stack. It stands in for
a Postman collection. The edge cases are covered by `npm test` and `scripts/smoke-test.sh` section 17.

**Before you start:** run `docker compose up -d` from the repo root, open <http://localhost:3000>, and
use a private window so no Keycloak session is left over. The users are DEV-ONLY, from
`keycloak/realm-clinicalharmony.json`.

| # | Do | Expect |
|---|---|---|
| 1 | Open <http://localhost:3000/patients/1> | Keycloak's "Sign in to ClinicalHarmony" page. The address bar shows `code_challenge_method=S256` and `redirect_uri=…/callback` |
| 2 | Sign in as `dana` / `dana-dev-password` | Back on **/patients/1** (the deep link survives login): Maria Garcia, 2 source records, merge history, clinical timeline. The header shows "Dana Steward · Data steward" and "All services up" |
| 3 | Reload the page | Signed in again without a password prompt (Keycloak SSO), same page |
| 4 | Untick **Medications** in the timeline | The URL gains `?types=CONDITION,OBSERVATION`. Only conditions and observations are listed. Each event shows its code system, code and "known code" / "not in reference set" |
| 5 | Click an event's FHIR link, e.g. `Condition/silver-condition-1` | **FHIR Viewer** detail: compliance badge, validator profile, lineage **SILVER → GOLD** with hashes, resource JSON. **Full lineage** opens **Data Lineage** prefilled |
| 6 | **Overview** | Four layer cards (Bronze / Silver / Gold / Patient index) with counts, open findings by severity, and a service health table |
| 7 | **Validation Report** → click `CODE-001` under "By rule" | The findings table filters (a `ruleCode: CODE-001 ×` chip appears). Bad value and suggestion columns are filled in |
| 8 | **Rule catalog** → **Turn off…** on `LAB_REFERENCE_RANGE` → **Turn off**, then **Turn on…** → **Turn on** | The dialog names rules-engine-service. State goes Off, then Active. `CODE-*` rules say "not toggleable" |
| 9 | **PMI Review** → **Run matching…** → **Run matching** | A banner says "Run #N COMPLETED: … pair(s) compared …", and the run appears at the top of "Recent matching runs" |
| 10 | Copy the run's correlation id from the gateway log: `docker logs clinicalharmony-api-gateway \| grep "requested by dana"` | It looks like `ui-xxxxxxxxxxxx`, and `docker logs clinicalharmony-patient-index-service \| grep <id>` finds the same id |
| 11 | **Sign out** | `/signed-out`: "You're signed out" with **Sign in again**. Clicking it shows Keycloak's password form (the SSO session really ended) |
| 12 | Sign in as `vic` / `vic-dev-password`, then open **Rule catalog**, **PMI Review** and a patient's merge history | "Vic Viewer · Viewer". Every steward button is greyed out, with the tooltip "Requires the data-steward role". **FHIR Viewer → Validate / convert…** still works (no side effects) |
| 13 | Sign in as `dana` again. **PMI Review** → set Status to **REJECTED** (or **DECIDED**) | The most recently decided pair is at the **top** (hint: "Newest decision first."). Back on **PENDING**, the highest score is on top |
| 14 | **PMI Review** → **Recently decided** card | Up to 10 accepted / rejected pairs, newest first, each with reviewer · time · note. Accept or reject a pending pair: it appears at the top of the card at once |
| 15 | **Recent matching runs** → click a run's **To review** (or **Auto-merged**) number | The queue shows only that run's candidates (Status QUEUED or AUTO_MERGED) with a `run #N ×` chip. Clicking the chip clears the filter |
| 16 | **FHIR Viewer** → **Patient** | `silver-patient-8, -9, -10 … -80 … -100` in number order. Click **Resource**: ▼, reversed. Click **Updated**: newest first. Each click changes the URL (`sort`, `dir`) |
| 17 | Open any FHIR resource → **Download .json** (next to Copy JSON) | Saves `<Type>-<fhirId>.json`, the same JSON as on screen |
| 18 | **Data Lineage** → "Transformations recorded" | Pipeline order: FHIR_MAP, PMI_PROVISIONAL, PMI_MATCH, PMI_MERGE, PMI_UNMERGE, then SEED_LOAD, API_GENERATE |

**Optional degraded check:** run `docker stop clinicalharmony-patient-index-service`. Within 30 s
the header shows "Degraded (1)" and a banner names the service. PMI buttons say "patient-index-service
is down", while the patient pages still load. Run `docker start clinicalharmony-patient-index-service`
to restore it.
