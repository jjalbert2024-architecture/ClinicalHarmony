# Phase 11 kickoff — an Indian hospital group on ABDM

Written 2026-11-26, at the end of Phase 10 (PR on `phase-10-polish`). The interview prep documents and the draw.io
diagrams (`docs/diagrams/README.md`) are **parked**; come back to them after Phase 11.

**Goal of Phase 11:** add the integration-engine skills Indian HL7 / interoperability job posts ask for (Mirth-style
channels, MLLP, SFTP, XSLT, ORM / SIU / MDM, outbound ORU, TLS / mTLS) — driven by one realistic Indian problem
instead of a feature checklist.

> Facts about ABDM / NRCeS / NHCX below are from memory (cutoff mid-2026). **Check the current specs and versions
> first** (abdm.gov.in, nrces.in, NHCX docs) and correct this file before scoping.

---

## The use case

**Deccan Care Hospitals** (fictional): 3 hospitals and 1 day-care clinic in Maharashtra, an in-house lab at each
hospital, and an outside **reference lab** for special tests. It is applying for NABH accreditation, wants to be an
**ABDM Health Information Provider (HIP)**, and wants cashless insurance claims through **NHCX** instead of emailing
PDFs to TPAs.

### What they run today
| System | Talks | What it sends |
|---|---|---|
| HIS (registration, OPD, IPD, billing) | HL7 v2 over MLLP | ADT (A04/A08), ORM^O01 lab orders, SIU^S12 OPD appointments |
| LIS at each hospital | HL7 v2 over MLLP | ORU^R01 results |
| Reference lab | SFTP, nightly | one XML batch of results per night (its own schema) |
| Discharge summary module | HL7 v2 over MLLP | MDM^T02 with the summary as a base64 PDF + key fields |
| TPAs / insurers | e-mail today | pre-auth and claim decisions |

### The problems (each one real in Indian hospitals)
1. **Results don't close the loop.** Reference-lab results arrive as a nightly XML file; a lab technician re-keys them
   into the HIS. Doctors see them a day late, and typing errors happen.
2. **One patient, many records.** The same person has a different UHID at each hospital. Names are spelt many ways
   (Mohd / Mohammed, initials first: "K. Ramesh"), many patients give only an age ("45Y") instead of a date of birth, and
   mobile numbers are shared within a family. Matching on name + DOB alone fails. The **ABHA number** (14 digits) is
   the national identifier, but only some patients have one.
3. **ABDM compliance.** As a HIP, the group must link records to a patient's ABHA and, when the patient consents
   through a Consent Manager (a PHR app), share them as **ABDM FHIR bundles** (NRCeS profiles: DiagnosticReportRecord,
   DischargeSummaryRecord, PrescriptionRecord, OPConsultRecord).
4. **Claims are slow.** A cashless claim needs the diagnosis, procedures, bills and discharge summary; today it is a
   PDF e-mailed to a TPA. NHCX expects a FHIR claim bundle over an encrypted, signed API.
5. **Security and privacy.** Interfaces run as plain TCP inside the network; ABDM and NHCX require HTTPS with
   certificates, and the **DPDP Act 2023** requires consent, purpose limitation and an audit trail for personal data.

### How each technology answers a problem
| Problem | Technology introduced | Phase |
|---|---|---|
| Many HIS / LIS feeds, each a bit different | **Open Integration Engine** (Mirth-compatible) channels: MLLP listeners, JavaScript filters / transformers, routing, ACK / NAK, error queue + reprocess | 11a |
| Orders, appointments, discharge summaries | **ORM^O01, SIU^S12, MDM^T02** in the generator, the channels and Silver | 11a |
| Reference lab's nightly file | **SFTP reader channel** + **XSLT** (lab XML → HL7 ORU / canonical) | 11b |
| Results back to the ordering doctor | **Outbound ORU^R01** channel to the HIS stub, ACK handling, retries | 11c |
| Same patient at 3 hospitals | **ABHA number** in PID-3 as a deterministic PMI match key; Indian name / age-only rules in the matcher | 11d |
| ABDM sharing | **ABDM FHIR profiles** (NRCeS IG) in the validator chain next to US Core; DiagnosticReportRecord from ORU, DischargeSummaryRecord from MDM; a **stub** ABDM gateway / consent flow | 11d |
| Secure interfaces | **TLS + mutual TLS**: dev CA, keystore / truststore, MLLP over TLS, HTTPS mTLS to the stubs, expiry alert in Splunk | 11e |
| Cashless claims (optional) | NHCX-style FHIR **Claim / CoverageEligibilityRequest** bundle from `silver.claims` to a stub exchange (JWE) | 11f |

### India-specific data (generator)
- Indian names (incl. initials-first and single names), UHIDs per hospital, some ABHA numbers
  (`XX-XXXX-XXXX-XXXX`), +91 mobiles, 6-digit PIN codes, Indian states, age-only registrations.
- Codes: India uses **WHO ICD-10** (not ICD-10-CM) and **SNOMED CT** (India is a member; ABDM codes medicines in
  SNOMED CT, not RxNorm). LOINC for labs. Decide whether to add an India code subset next to the US one.

---

## Decisions to settle at the start (ask, with recommended options)
1. **OIE**: current release, Docker image and licence (MPL 2.0 expected); Mirth 4.5.2 channel compatibility. Image needs approval.
2. **SFTP server** image (e.g. `atmoz/sftp`) — needs approval.
3. **ABDM FHIR IG package** (NRCeS) — download needs approval; check its version and dependencies.
4. **No real ABDM / NHCX sandbox** (registration needs a real organisation): build stubs that follow the published
   API shapes. Confirm with the user.
5. Where OIE sits: in front of ingestion-service (channels → REST intake over Kafka) — recommended — or replacing the
   Camel MLLP listener.
6. MLLP over TLS: OIE's own support (open-source Mirth's TLS was a paid extension) vs the Camel listener vs stunnel.
7. Keep US Core as is; ABDM profiles are an **addition** (each source tagged US or IN).

## Done when (whole phase)
- Generator sends an Indian hospital day (ADT, ORM, SIU, ORU, MDM, reference-lab SFTP file); every message reaches
  Silver / Gold through OIE.
- A reference-lab result goes SFTP → XSLT → Silver → outbound ORU → HIS stub ACK, traceable by one correlation id
  in Splunk.
- Same patient at 3 hospitals with an ABHA auto-merges; without ABHA goes to review.
- An ABDM DischargeSummaryRecord and DiagnosticReportRecord validate against the NRCeS profiles.
- An untrusted client certificate is refused; a certificate near expiry raises a Splunk alert.
- Channels exported as XML in git (later: the public channels repo — community action item 1).
- Tests + smoke sections + CHECKLIST rows + one minimal Postman collection per sub-phase, as in earlier phases.

## Not in Phase 11
UK items (UK Core, NHS number), XDS.b / XCA (optional later), MCCD certification.
