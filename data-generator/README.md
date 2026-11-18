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

## Tests

```bash
cd data-generator && python3 -m unittest discover -s tests -t .   # 18 tests
```

Determinism, exact malformed count and defect coverage, HL7 structure of every valid message, pharmacy
orders (RxNorm, home-facility MRN, ORC-9 / ORC-12 positions, at least one planted allergy conflict),
cross-facility duplicates (share, alternating kinds, other facility + new MRN, sent right after the original,
identical vs fuzzy demographics), and the
MLLP client against a local fake server (framing, many messages per connection, retry on `AE`,
reconnect after a dropped connection, giving up).

## Later (9d)

All feeds (FHIR JSON, claims CSV, C-CDA alongside HL7), ~20% of patients in more than one feed with
typos / nicknames / swapped dates for the PMI, deliberate clinical defects for validation, `--reset`
and a `--trickle` mode that looks like a live feed. See the [roadmap](../README.md#roadmap).
