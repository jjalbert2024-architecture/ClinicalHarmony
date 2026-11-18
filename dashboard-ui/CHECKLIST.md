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
| 16 | **FHIR Viewer** → **Patient**, click **Resource** | `silver-patient-8, -9, -10 … -80 … -100` in number order (▲). Click again: ▼, reversed. Click **Updated**: back to the default, newest first. Each click changes the URL (`sort`, `dir`) |
| 17 | Open any FHIR resource → **Download .json** (next to Copy JSON) | Saves `<Type>-<fhirId>.json`, the same JSON as on screen |
| 18 | **Data Lineage** → "Transformations recorded" | Pipeline order (the caption says so — not by date): FHIR_MAP, PMI_PROVISIONAL, PMI_MATCH, PMI_MERGE, PMI_UNMERGE, then SEED_LOAD, API_GENERATE. Click **Last**: newest first, SEED_LOAD (oldest) at the bottom; **Records**: biggest first; **Step**: back to pipeline order |
| 19 | **Patient Timeline** → type an MRN, e.g. `MRN100001` (any case) → **Search** | Exactly Maria Garcia's master. A part of an MRN (`MRN1000`) finds nothing |
| 20 | **Data Lineage** → Bronze messages | Columns **Format** (HL7 v2 / FHIR JSON / Claims CSV) and **HL7 type** (e.g. `ORU^R01`, `—` when none). Filters "Any payload format" and "Any HL7 message type" |
| 21 | Pick HL7 message type **RDE (pharmacy order)** | Format switches to **HL7 v2**, only `RDE^O11` rows remain, the URL gains `messageType=HL7V2&hl7Type=RDE`. Pick format **FHIR JSON**: the HL7 type is cleared and greyed out |
| 22 | **FHIR Viewer** (no filters) | Newest first (**Updated ▼**): what the pipeline just built is on page 1. Columns **Patient** (source · MRN, e.g. `NORTH_CLINIC · NC700343`) and **Origin** (HL7 pipeline / Silver seed / API-generated / Gold seed; hover for what each means) |
| 23 | **Patient** = `NC700343` → **Search**, then click **AllergyIntolerance** on the left | Only HUGO PATEL's resources (NORTH_CLINIC NC700343, DOB 1942-12-13); with the type, exactly `AllergyIntolerance/silver-allergy-165`, HL7 pipeline, compliant. A part of the MRN (`NC7003`) finds nothing; a name part (`patel`) finds every PATEL |
| 24 | **Clear filters**, then **Source system** = NORTH_CLINIC, **Origin** = HL7 pipeline, **Code or name** = `Amoxicillin` → **Search** | Only NORTH_CLINIC allergies (and orders) for amoxicillin, all with origin HL7 pipeline; the URL holds every filter. **Origin** = Gold seed instead: the CMS-0057 seed rows (`cms-…`). **Clear filters** empties every box |
| 25 | **Data Lineage** | Three layer cards on top — **Bronze** (messages by status), **Silver** (patients by validation status), **Gold** (masters by match status + FHIR resource count). Bronze is selected and the Bronze messages list is below |
| 26 | Click the **Silver** card → in the **Silver patients** search box (right of the "Silver patients" heading, below the cards) type `NC700343` → **Search** | The URL gains `layer=silver&q=NC700343`. One row: Silver patient **#1181**, NORTH_CLINIC · NC700343, HUGO PATEL, 13 Dec 1942, Validated, records 2 / 10 / 0 / 1, Gold master **#523** MATCHED. A part of the MRN (`NC7003`) finds nothing |
| 27 | Click **#1181** | **Silver patient #1181 · HUGO PATEL**. The path strip reads Bronze **#1866, #1870, #1883, #1952** → Silver patient #1181 → Gold master **#523** · `Patient/silver-patient-1181`. Below: demographics, validation (Validated, 4 open findings), the 4 Bronze messages, conditions / observations / medications / allergies, findings |
| 28 | In **Allergies**, the row `silver.allergies/165` (RxNorm 6809 Metformin, SV, NAUSEA) | **From Bronze #1866** opens that ADT^A04; **AllergyIntolerance/silver-allergy-165** opens it in the FHIR Viewer (compliant). In **Validation findings**, a link like `OBSERVATION #1289` jumps to and highlights that observation row |
| 29 | Open **Bronze #1866** | "Silver records from this message" are links: `silver.patients #1181`, `silver.conditions #711`, `#712`, `silver.allergies #165` (each to patient #1181's page). **Trace this message into Gold** shows its lineage |
| 30 | **Trace a record** → kind **Silver patient id** → `681` → **Trace** | No more 404: target `silver.patients #681` (SAMUEL MULLER, NORTH_CLINIC NC974045, DOB 10 Apr 2005), 10 lineage records, 3 Bronze source messages. In each chain, `silver.<table>/<id>` and `gold.fhir_resources/<id>` refs are links |
| 31 | Click the **Gold** card | Gold master patients with match status, sources and **FHIR resources** count (link → FHIR Viewer for that master) and **trace** (→ that master's lineage). **Overview**: the Bronze and Silver cards now open these tabs |
| 32 | **Trace a record** (top box) → kind **Silver patient id** → type the MRN `NC700343` → **Trace** | A note "NORTH_CLINIC · NC700343 (HUGO PATEL, DOB 13 Dec 1942) → Silver patient #1181", the box now shows `1181`, the URL `silverPatientId=1181`, and his 15 lineage records. Kind **Master patient id** + the same MRN traces master **#523**. An MRN no patient has says "No Silver patient has MRN or source id …" |
| 33 | Kind **Bronze message id** → type `NC700343` → **Trace** | No request, no 404: "A bronze message id is a number. "NC700343" looks like an MRN: pick Silver patient id or Master patient id …" |
| 34 | **Data Lineage** → **Bronze messages** (default tab) | New **Patient** column: the name and MRN from each message's PID segment, e.g. **#2842** → `LIAM NGUYEN` · `CH799449`. The name links to that Silver patient (#2842 → `silver.patients #1849`). A lab result (REGIONAL_LAB) links to the hospital's patient (PID-3.4). A message that never reached Silver shows the name with "not in Silver"; one with no readable PID shows `—` |
| 35 | In the Bronze search box type `nguyen` → **Search** (or an exact MRN like `CH799449`, or a message id like `2842`) | The URL gains `bronzeQ=nguyen`; only messages about a NGUYEN, newest first — LIAM NGUYEN CH799449 and NC760335 (two people with the same name, told apart by MRN), ROSA NGUYEN … A part of an MRN (`CH7994`) finds nothing |
| 36 | Open **#2842** | Under the header: **Patient** LIAM NGUYEN · CH799449 → **Silver patient #1849** (link) |

**Optional degraded check:** run `docker stop clinicalharmony-patient-index-service`. Within 30 s
the header shows "Degraded (1)" and a banner names the service. PMI buttons say "patient-index-service
is down", while the patient pages still load. Run `docker start clinicalharmony-patient-index-service`
to restore it.
