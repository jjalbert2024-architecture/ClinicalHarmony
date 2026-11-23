# data-generator

**Phase 9** — Python generator of synthetic hospital data, used to feed the pipeline the way real
source systems would. Standard library only (Python 3.9+): runs on the host's `python3`, nothing to install.

## v1 (Phase 9a, + pharmacy orders in 9b, + cross-facility duplicates in 9c): seeded HL7 v2 over MLLP

```bash
python3 data-generator/generate.py                     # 100 messages, seed 42, 10% malformed → MLLP localhost:8887
python3 data-generator/generate.py --seed 7 --count 20 # another reproducible set
python3 data-generator/generate.py --no-send           # only write the files
python3 data-generator/generate.py --duplicate-rate 0  # no cross-facility duplicates
python3 data-generator/generate.py --send-dir data-generator/out/seed-42   # re-send existing files
```

1. **Writes** `data-generator/out/seed-<seed>/NNNN_<TYPE>_<SOURCE>.hl7` (raw bytes, `\r` segment
   terminators) plus `manifest.json` (per message: kind, defect, MSH-4 source, MSH-10 control id, MRN,
   SHA-256, `expectedStatus` PENDING / QUARANTINED, and since 9c the planted `duplicates` with their
   `expectedPmiOutcome`). `out/` is git-ignored. The run also prints each duplicate as source + MRN + DOB.
2. **Sends** them in order over MLLP (`<VT>…<FS><CR>`, one connection), and only counts a message as
   sent on ACK `AA`. On `AE`, a timeout or a dropped connection it reconnects and retries with
   backoff (1, 2, 4, 8 s; 5 attempts) — what a real interface engine does. Exit code 0 = all `AA`.

**Same seed → same bytes.** Re-running a seed re-sends identical messages; Bronze is idempotent, so
they're ACKed and skipped (a quick way to show "no duplicates").

### What's in a run

- ~`count / 3` synthetic patients at `CITY_HOSPITAL` (ADT `REG_SYSTEM`) or `NORTH_CLINIC` (`EHR_NC`);
  names, addresses and MRNs are invented.
- Each patient's first message is an **ADT^A01** (hospital admit) or **ADT^A04** (clinic registration)
  with PID, PV1, DG1 diagnoses and sometimes (30%) an AL1 drug allergy; later messages are **ORU^R01** lab
  results (55%, from `REGIONAL_LAB` or the clinic: lipid, CBC, metabolic or HbA1c panels, values
  sometimes out of range with H/L flags), **RDE^O11** pharmacy orders (20%, since 9b) or **ADT^A08** updates.
- **RDE^O11** (9b) comes from `CITY_PHARMACY` (`RX_SYSTEM`): ORC (new order, ORC-9 time, ordering
  provider) + RXE (RxNorm give code, dose, instructions) + RXR. The drug treats one of the patient's
  diagnoses — or, for half the orders of an allergic patient, it **is** the allergen: a deliberate
  `MEDICATION_ALLERGY_CONFLICT` for the Silver validation to catch. PID-3.4 keeps the home facility, so
  the order lands on that facility's Silver patient.
- **Cross-facility duplicates** (9c, `--duplicate-rate`, default 0.10): round(patients × rate) people are
  registered a second time at the *other* facility (CITY_HOSPITAL ↔ NORTH_CLINIC) under a new MRN; the
  second registration (A01 / A04) is sent right after the first one's ADT. They alternate:
  - **IDENTICAL** — same name, DOB, sex, address, ZIP and phone → patient-index-service's deterministic
    rule `EXACT_NAME_DOB_PLUS_PHONE_OR_ZIP` auto-merges them (still true after an A08 changes one phone:
    the ZIP matches);
  - **FUZZY** — same name, sex, address and ZIP, but the birth *day* is off by one (same year and month)
    and the phone differs → weighted score 13 / 21 = **0.6190**, inside the review band (0.60–0.85), so
    it waits in **PMI Review** for a data steward.

  Names repeat across generated patients, so identify them by **source + MRN + DOB** (as the manifest
  and the console output do).
- Codes come from the terminology subsets seeded in `docs/db-init` (ICD-10-CM, LOINC, RxNorm), so the
  9b Silver layer can validate them against ontology-service.
- Exactly `round(count × malformed-rate)` messages are **malformed**, cycling through five defects
  that Bronze must quarantine: `MISSING_MSH`, `NOT_HL7` (free text), `EMPTY_CONTROL_ID` (MSH-10),
  `EMPTY_MESSAGE_TYPE` (MSH-9), `TRUNCATED_HEADER` (cut mid-MSH). They still get `AA`: the door
  accepts everything, Bronze keeps and quarantines it.

## 9d: every feed — FHIR JSON, claims CSV and C-CDA alongside HL7

```bash
python3 data-generator/generate.py                                   # all feeds: HL7 over MLLP + the rest over REST
python3 data-generator/generate.py --feeds hl7                       # HL7 only (the 9a–9c behaviour, same bytes)
python3 data-generator/generate.py --feeds fhir,claims,ccda          # no HL7 (then no cross-feed duplicates)
python3 data-generator/generate.py --reset                           # same seed, NEW people (new id namespace)
python3 data-generator/generate.py --trickle --rate 2 --duration 120 # a live feed: 2 items/s, stop after 2 min
python3 data-generator/generate.py --send-dir data-generator/out/seed-42   # re-send a run in its original order
```

The HL7 stream is unchanged: the new feeds come from a second random stream derived from the seed, so
`--feeds hl7` (and the HL7 part of an all-feeds run) is byte-for-byte what 9c produced.

| Feed | Source system (`X-Source-System`) | Endpoint | Ids | Per `--count` |
|---|---|---|---|---|
| FHIR R4 **Bundle**: Patient (MR identifier) + Condition (ICD-10-CM), Observation (LOINC, range, flag), MedicationRequest (RxNorm), AllergyIntolerance | `PARTNER_CLINIC` | `POST /api/ingestion/fhir` | `PC######` | count / 6 |
| **Claims CSV** file (1–3 lines per member, 1–2 members per file) with member columns `member_first_name, member_last_name, member_dob, member_gender, member_zip, member_phone` and the claim's lifecycle `payer_id, payer_name, claim_status, submitted_date, status_date, allowed_amount, paid_amount, patient_responsibility, status_reason` (5 payers; status mix PAID 45 / PARTIALLY_PAID 10 / PENDED 12 / SUBMITTED 5 / ACCEPTED 5 / DENIED 12 / REJECTED 4 / APPEALED 7 %, denial reasons CO-50 / CO-16 / CO-197 / PR-96; drawn from an RNG seeded by the claim id, so no other byte of a seed changed) | `CLAIMS_VENDOR_A` | `POST /api/ingestion/claims` | `MB######`, claims `CLM-#######` | count / 5 members |
| **C-CDA R2.1 CCD**: patientRole + Problems, Medications, Results, Allergies sections | `COMMUNITY_HIE` | `POST /api/ingestion/ccda` | `HI######` | count / 10 |

- **Cross-feed duplicates** (`--cross-feed-rate`, default 0.20): that share of the HL7 people also appears in ONE
  other feed (rotating FHIR → claims → C-CDA) under that feed's id, sent right after the person's first ADT, with
  one variation (rotating):
  - **EXACT** — same demographics → the PMI's deterministic rule merges them (`AUTO_MERGED`, 1.0);
  - **NICKNAME** — `JAMES → Jim`, `DANIEL → Dan`, `SAMUEL → Sam`, `LIAM → William` → weighted score ≈ 0.93 /
    0.92 → `AUTO_MERGED`;
  - **TYPO** — two neighbouring surname letters swapped (`JOHNSON → Jonhson`) and a new phone → ≈ 0.69 →
    `PENDING_REVIEW`;
  - **SWAPPED_DATE** — DOB day and month swapped (`2014-01-10 → 2014-10-01`), new phone unless that would drop it
    below review → 0.6190 (FHIR / C-CDA) or 0.8378 (claims) → `PENDING_REVIEW`.

  Only HL7 people whose first ADT was sent intact and whose phone no A08 changed are used, so the outcome holds
  whenever matching runs. `hl7gen/pmi.py` is a Python port of PatientMatcher's scoring: the manifest records the
  `expectedPmiOutcome` and `expectedScore`, and the console prints each pair as source + id + DOB.
- **Defects** (`--defect-rate`, default 0.12 of the feed-only records), each the least-used kind its feed can carry,
  listed in the manifest with the rule it should trip and the planted code:

  | Defect | Expected finding | Feeds |
  |---|---|---|
  | `UNKNOWN_ICD10` (`E11.999`) | `CODE-001` (ontology) | all |
  | `UNKNOWN_LOINC` (`99999-9`) | `CODE-002` | FHIR, C-CDA |
  | `UNKNOWN_RXNORM` (`9999991`) | `CODE-003` | FHIR, C-CDA |
  | `LAB_OUT_OF_RANGE` (glucose 312 mg/dL, range 70–99) | `LAB_REFERENCE_RANGE` | FHIR, C-CDA |
  | `PREGNANCY_ON_MALE` (`Z34.90` on a male) | `GENDER_PREGNANCY_CONFLICT` | all |
  | `DIAGNOSIS_IN_FUTURE` (onset / service date in 2031) | `DIAGNOSIS_DATE_SANITY` (it flags future onsets) | all |

  The feeds' own labs are in range, so a `LAB_REFERENCE_RANGE` finding on a feed patient is a planted one (HL7 lab
  values are random and often out of range, as before).
- **`--reset`** moves `out/state.json` to the next identifier namespace (`CH123456 → CH1123456`, `PC1…`, `S42N1-…`
  control ids, `CLMN1-…` claims). Nothing is deleted anywhere; the same seed simply makes new patients, so a demo can
  be replayed without Bronze deduplicating it. Runs go to `out/seed-<seed>-ns<k>/`.
- **`--trickle`** sends the same set (same order, same bytes) at `--rate` items per second (default 1) instead of a
  burst, prints progress every 10, stops after `--duration` seconds if given, and stops cleanly on Ctrl-C.
- **Files**: `out/seed-<seed>/` keeps the `.hl7` files and adds `fhir/*.json`, `claims/*.csv`, `ccda/*.xml`;
  `manifest.json` (version 2) adds `feeds`, `feedRecords` (file, endpoint, source, people with id + DOB, defect,
  Bronze rows), `crossFeedDuplicates`, `defects` and the `sendOrder`.
- **Sending**: HL7 over MLLP as before; feeds over REST (`--ingestion-url`, default `http://localhost:8081`) with an
  `X-Correlation-Id: gen-<run>-<n>`. `202` = on Kafka; a `503` (Kafka down) or a connection error is retried after
  `Retry-After` (else 1, 2, 4, 8 s). Exit code 0 = every HL7 message `AA` and every feed payload `202`.

## Tests

```bash
cd data-generator && python3 -m unittest discover -s tests -t .   # 37 tests
```

9a–9c: determinism, malformed count and defect coverage, HL7 structure, pharmacy orders, cross-facility duplicates,
the MLLP client against a fake server. 9d (`tests/test_feeds.py`): the PMI port (9c's fuzzy pair at 13 / 21,
deterministic rule, nickname, a male/female conflict, Jaro-Winkler against Winkler's values), feeds never changing
the HL7 bytes, per-feed sizes, ~20% cross-feed with every variation and its expected outcome, twins sent right after
the original's first ADT, defects present in their payloads, FHIR / CSV / C-CDA shapes, `--reset` namespaces,
`--trickle` pacing and `--duration` (fake clock), and the REST client retrying a 503 against a local HTTP server.
