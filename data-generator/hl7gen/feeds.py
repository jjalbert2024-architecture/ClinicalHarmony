"""Phase 9d: FHIR JSON, claims CSV and C-CDA feeds alongside the HL7 stream.

Everything here is drawn from its OWN ``random.Random`` (derived from the seed), after the HL7 stream has been
generated, so adding feeds never changes the HL7 bytes of a seed.

* **Sources** (the ``X-Source-System`` each feed posts with): FHIR Bundles from ``PARTNER_CLINIC``, claims CSV files
  from ``CLAIMS_VENDOR_A``, C-CDA documents from ``COMMUNITY_HIE``. Each feed has its own patient identifiers.
* **Cross-feed duplicates** (``cross_feed_rate``): that share of the HL7 people ALSO appear in one other feed under that
  feed's identifier, with one variation — EXACT, NICKNAME, TYPO (one surname slip) or SWAPPED_DATE (day/month swapped
  DOB). The manifest records the variation and the outcome the PMI should reach, computed with ``pmi.score`` (a port
  of PatientMatcher). A twin is sent right after the original's first ADT.
* **Defects** (``defect_rate``): that share of feed-only records carries one deliberate clinical defect, each aimed at
  one existing check (ontology CODE-001/2/3, LAB_REFERENCE_RANGE, GENDER_PREGNANCY_CONFLICT, DIAGNOSIS_DATE_SANITY —
  which flags an onset in the future, so that defect dates a diagnosis in 2031).
"""
import hashlib
import json
import random
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from typing import Dict, List, Optional, Tuple
from xml.sax.saxutils import escape

from . import catalog, pmi
from .messages import Patient, with_namespace

FEEDS = ("fhir", "claims", "ccda")
SOURCES = {"fhir": "PARTNER_CLINIC", "claims": "CLAIMS_VENDOR_A", "ccda": "COMMUNITY_HIE"}
ID_PREFIX = {"fhir": "PC", "claims": "MB", "ccda": "HI"}
ENDPOINTS = {"fhir": "/api/ingestion/fhir", "claims": "/api/ingestion/claims", "ccda": "/api/ingestion/ccda"}
CONTENT_TYPES = {"fhir": "application/fhir+json", "claims": "text/csv", "ccda": "application/xml"}
EXTENSIONS = {"fhir": "json", "claims": "csv", "ccda": "xml"}

VARIATIONS = ("EXACT", "NICKNAME", "TYPO", "SWAPPED_DATE")

# Defect kind → (the check it trips, feeds that can carry it)
DEFECTS = {
    "UNKNOWN_ICD10": ("CODE-001", ("fhir", "claims", "ccda")),
    "UNKNOWN_LOINC": ("CODE-002", ("fhir", "ccda")),
    "UNKNOWN_RXNORM": ("CODE-003", ("fhir", "ccda")),
    "LAB_OUT_OF_RANGE": ("LAB_REFERENCE_RANGE", ("fhir", "ccda")),
    "PREGNANCY_ON_MALE": ("GENDER_PREGNANCY_CONFLICT", ("fhir", "claims", "ccda")),
    # DIAGNOSIS_DATE_SANITY flags an onset dated in the future (a fixed 2031 date keeps the bytes deterministic).
    "DIAGNOSIS_IN_FUTURE": ("DIAGNOSIS_DATE_SANITY", ("fhir", "claims", "ccda")),
}
UNKNOWN_ICD10 = ("E11.999", "Type 2 diabetes, unlisted complication (not a real code)")
UNKNOWN_LOINC = ("99999-9", "Unlisted lab test (not a real code)")
UNKNOWN_RXNORM = ("9999991", "Unlisted medication (not a real code)")
FUTURE = date(2031, 1, 1)
PREGNANCY = ("Z34.90", "Encounter for supervision of normal pregnancy, unspecified trimester")

ICD10_SYSTEM = "http://hl7.org/fhir/sid/icd-10-cm"
LOINC_SYSTEM = "http://loinc.org"
RXNORM_SYSTEM = "http://www.nlm.nih.gov/research/umls/rxnorm"
ICD10_OID, LOINC_OID, RXNORM_OID, SNOMED_OID = ("2.16.840.1.113883.6.90", "2.16.840.1.113883.6.1",
                                                "2.16.840.1.113883.6.88", "2.16.840.1.113883.6.96")


@dataclass
class FeedPerson:
    """One person as a non-HL7 feed knows them (Title Case names, ISO dates, its own identifier)."""
    feed: str
    pid: str
    first: str
    last: str
    sex: str              # M / F
    dob: date
    street: str
    city: str
    state: str
    zip: str
    phone: str
    diagnoses: List[tuple] = field(default_factory=list)
    allergy: Optional[tuple] = None

    @property
    def gender(self) -> str:
        return "male" if self.sex == "M" else "female"

    def as_pmi(self) -> pmi.Person:
        # A claims line carries no street address (Silver keeps none for a claims member).
        return pmi.Person(self.first, self.last, self.dob, self.gender, self.phone, self.zip,
                          None if self.feed == "claims" else self.street)


@dataclass
class FeedRecord:
    """One payload a feed POSTs to ingestion-service."""
    feed: str
    name: str                     # file name
    payload: str
    people: List[FeedPerson]
    after_index: int = 0          # send right after this HL7 message (0 = placed by the shuffle)
    send_index: int = 0
    defect: Optional[str] = None
    defect_code: Optional[str] = None   # the code the defect planted (or the condition it back-dated)

    @property
    def source_system(self) -> str:
        return SOURCES[self.feed]

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.payload.encode("utf-8")).hexdigest()

    def manifest_entry(self) -> dict:
        return {
            "sendIndex": self.send_index, "feed": self.feed, "file": "%s/%s" % (self.feed, self.name),
            "endpoint": ENDPOINTS[self.feed], "sourceSystem": self.source_system, "sha256": self.sha256,
            "people": [{"id": p.pid, "name": "%s %s" % (p.first, p.last), "dob": p.dob.isoformat()} for p in self.people],
            "defect": self.defect,
            # Bronze: one row per payload, claims one per LINE (a member may have several), PENDING → Silver PROCESSED.
            "bronzeRows": self.payload.strip("\n").count("\n") if self.feed == "claims" else 1,
        }


@dataclass
class CrossFeedDuplicate:
    variation: str
    feed: str
    original: Patient
    twin: FeedPerson
    outcome: str
    score: float

    def manifest_entry(self) -> dict:
        o = self.original
        return {
            "variation": self.variation, "feed": self.feed, "expectedPmiOutcome": self.outcome,
            "expectedScore": round(self.score, 4),
            "original": {"sourceSystem": o.facility, "mrn": o.mrn, "name": "%s %s" % (o.first, o.last),
                         "dob": "%s-%s-%s" % (o.dob[:4], o.dob[4:6], o.dob[6:])},
            "twin": {"sourceSystem": SOURCES[self.feed], "id": self.twin.pid,
                     "name": "%s %s" % (self.twin.first, self.twin.last), "dob": self.twin.dob.isoformat()},
        }


@dataclass
class DefectEntry:
    kind: str
    feed: str
    file: str
    source_system: str
    patient_id: str
    name: str
    dob: str
    code: Optional[str]

    def manifest_entry(self) -> dict:
        return {"kind": self.kind, "expectedRule": DEFECTS[self.kind][0], "feed": self.feed, "file": self.file,
                "sourceSystem": self.source_system, "patientId": self.patient_id, "name": self.name, "dob": self.dob,
                "code": self.code}


# ---- people ---------------------------------------------------------------------------------------------

def _dob(p: Patient) -> date:
    return datetime.strptime(p.dob, "%Y%m%d").date()


def _title(s: str) -> str:
    return s.title()


def new_id(xrng: random.Random, feed: str, used: set, namespace: int) -> str:
    while True:
        pid = with_namespace("%s%06d" % (ID_PREFIX[feed], xrng.randrange(100000, 999999)), namespace)
        if pid not in used:
            used.add(pid)
            return pid


def twin_of(xrng: random.Random, p: Patient, feed: str, variation: str, used: set, namespace: int) -> FeedPerson:
    t = FeedPerson(feed, new_id(xrng, feed, used, namespace), _title(p.first), _title(p.last), p.sex, _dob(p),
                   _title(p.street), _title(p.city), p.state, p.zip, p.phone, list(p.diagnoses), p.allergy)
    if variation == "NICKNAME":
        t.first = _title(pmi.NICKNAMES[p.first])
    elif variation == "TYPO":
        last = t.last
        i = _typo_at(last.upper())
        t.last = last[:i] + last[i + 1] + last[i] + last[i + 2:]
    elif variation == "SWAPPED_DATE":
        t.dob = date(t.dob.year, t.dob.day, t.dob.month)
    if variation in ("TYPO", "SWAPPED_DATE"):
        # A new phone too, unless that drops the pair below the review band (then only the variation differs).
        new_phone = "555%07d" % xrng.randrange(0, 9999999)
        candidate = FeedPerson(**{**t.__dict__, "phone": new_phone})
        if pmi.score(_hl7_person(p), candidate.as_pmi())[0] == "PENDING_REVIEW":
            t.phone = new_phone
    return t


def _hl7_person(p: Patient) -> pmi.Person:
    return pmi.Person(p.first, p.last, _dob(p), "male" if p.sex == "M" else "female", p.phone, p.zip, p.street)


def eligible(p: Patient, variation: str) -> bool:
    d = _dob(p)
    if variation == "NICKNAME":
        return p.first in pmi.NICKNAMES
    if variation == "SWAPPED_DATE":
        return d.day <= 12 and d.day != d.month
    if variation == "TYPO":
        i = _typo_at(p.last)
        return i is not None
    return True


def _typo_at(last: str) -> Optional[int]:
    """Where to swap two neighbouring letters: after the first two (keeps the sound and the Jaro-Winkler prefix)."""
    i = 2 if len(last) > 4 else 1
    return i if len(last) >= 4 and last[i] != last[i + 1] else None


def feed_only_person(xrng: random.Random, feed: str, used: set, namespace: int) -> FeedPerson:
    sex = xrng.choice("FM")
    first = xrng.choice(catalog.FIRST_NAMES_F if sex == "F" else catalog.FIRST_NAMES_M)
    city, state, zip3 = xrng.choice(catalog.CITIES)
    dob = date(1935, 1, 1) + timedelta(days=xrng.randrange(0, 365 * 85))
    return FeedPerson(feed, new_id(xrng, feed, used, namespace), _title(first), _title(xrng.choice(catalog.LAST_NAMES)),
                      sex, dob, "%d %s" % (xrng.randrange(10, 9999), _title(xrng.choice(catalog.STREETS))),
                      _title(city), state, "%s%02d" % (zip3, xrng.randrange(0, 99)),
                      "555%07d" % xrng.randrange(0, 9999999), xrng.sample(catalog.DIAGNOSES, xrng.randint(1, 3)),
                      xrng.choice(catalog.ALLERGENS) if xrng.random() < 0.3 else None)


# ---- clinical content shared by FHIR and C-CDA ------------------------------------------------------------

@dataclass
class Clinical:
    conditions: List[Tuple[str, str, date]]                         # code, display, onset
    labs: List[Tuple[str, str, float, str, float, float, str, datetime]]  # loinc, name, value, unit, low, high, flag, time
    meds: List[Tuple[str, str, str, date]]                          # rxcui, display, instructions, start
    allergy: Optional[Tuple[str, str, str]]                         # rxcui, display, reaction


def clinical(xrng: random.Random, person: FeedPerson, when: datetime, defect: Optional[str]) -> Tuple[Clinical, Optional[str]]:
    onset = (when - timedelta(days=xrng.randrange(30, 3000))).date()
    conditions = [(c, d, max(onset, person.dob + timedelta(days=400))) for c, d in person.diagnoses]
    _, _, tests = xrng.choice(catalog.PANELS)
    labs = []
    for loinc in tests:
        name, unit, low, high, decimals = catalog.LABS[loinc]
        value = round(xrng.uniform(low, high), decimals)        # in range: only a planted defect is out of range
        labs.append((loinc, name, value, unit, low, high, "N", when - timedelta(hours=2)))
    options = [rx for code, _ in person.diagnoses for rx in catalog.TREATMENTS.get(code, [])]
    meds = []
    for rx in sorted(set(options))[:2]:
        name, _, _, instructions = catalog.MEDICATIONS[rx]
        meds.append((rx, name, instructions, onset))
    allergy = (person.allergy[0], person.allergy[1], xrng.choice(catalog.REACTIONS).title()) if person.allergy else None
    code = None
    if defect == "UNKNOWN_ICD10":
        conditions.append((UNKNOWN_ICD10[0], UNKNOWN_ICD10[1], onset))
        code = UNKNOWN_ICD10[0]
    elif defect == "UNKNOWN_LOINC":
        labs.append((UNKNOWN_LOINC[0], UNKNOWN_LOINC[1], 1.0, "1", 0.0, 2.0, "N", when - timedelta(hours=2)))
        code = UNKNOWN_LOINC[0]
    elif defect == "UNKNOWN_RXNORM":
        meds.append((UNKNOWN_RXNORM[0], UNKNOWN_RXNORM[1], "Take as directed", onset))
        code = UNKNOWN_RXNORM[0]
    elif defect == "LAB_OUT_OF_RANGE":
        name, unit, low, high, _ = catalog.LABS["2345-7"]
        labs.append(("2345-7", name, 312.0, unit, low, high, "H", when - timedelta(hours=1)))
        code = "2345-7"
    elif defect == "PREGNANCY_ON_MALE":
        conditions.append((PREGNANCY[0], PREGNANCY[1], onset))
        code = PREGNANCY[0]
    elif defect == "DIAGNOSIS_IN_FUTURE":
        c, d, _ = conditions[0]
        conditions[0] = (c, d, FUTURE + timedelta(days=xrng.randrange(0, 300)))
        code = c
    return Clinical(conditions, labs, meds, allergy), code


# ---- FHIR R4 Bundle -----------------------------------------------------------------------------------------

def fhir_bundle(person: FeedPerson, c: Clinical, bundle_id: str) -> str:
    pref = "urn:uuid:patient-%s" % person.pid.lower()
    entries = [{"fullUrl": pref, "resource": {
        "resourceType": "Patient", "id": person.pid.lower(),
        "identifier": [{"type": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0203", "code": "MR"}]},
                        "system": "urn:clinicalharmony:partner-clinic:mrn", "value": person.pid}],
        "name": [{"family": person.last, "given": [person.first]}],
        "gender": person.gender, "birthDate": person.dob.isoformat(),
        "address": [{"line": [person.street], "city": person.city, "state": person.state, "postalCode": person.zip}],
        "telecom": [{"system": "phone", "value": "%s-%s-%s" % (person.phone[:3], person.phone[3:6], person.phone[6:])}]}}]
    subject = {"reference": pref}
    for i, (code, display, onset) in enumerate(c.conditions, 1):
        entries.append({"resource": {"resourceType": "Condition", "id": "%s-c%d" % (bundle_id, i), "subject": subject,
                                     "code": {"coding": [{"system": ICD10_SYSTEM, "code": code, "display": display}]},
                                     "onsetDateTime": onset.isoformat()}})
    for i, (loinc, name, value, unit, low, high, flag, t) in enumerate(c.labs, 1):
        entries.append({"resource": {"resourceType": "Observation", "id": "%s-o%d" % (bundle_id, i), "status": "final",
                                     "subject": subject,
                                     "code": {"coding": [{"system": LOINC_SYSTEM, "code": loinc, "display": name}]},
                                     "valueQuantity": {"value": value, "unit": unit},
                                     "effectiveDateTime": t.strftime("%Y-%m-%dT%H:%M:%SZ"),
                                     "referenceRange": [{"low": {"value": low}, "high": {"value": high}}],
                                     "interpretation": [{"coding": [{"code": flag}]}]}})
    for i, (rx, display, sig, start) in enumerate(c.meds, 1):
        entries.append({"resource": {"resourceType": "MedicationRequest", "id": "%s-m%d" % (bundle_id, i),
                                     "status": "active", "intent": "order", "subject": subject,
                                     "medicationCodeableConcept": {"coding": [{"system": RXNORM_SYSTEM, "code": rx,
                                                                               "display": display}]},
                                     "authoredOn": start.isoformat(), "dosageInstruction": [{"text": sig}]}})
    if c.allergy:
        rx, display, reaction = c.allergy
        entries.append({"resource": {"resourceType": "AllergyIntolerance", "id": "%s-a1" % bundle_id,
                                     "patient": subject, "category": ["medication"], "criticality": "high",
                                     "code": {"coding": [{"system": RXNORM_SYSTEM, "code": rx, "display": display}]},
                                     "reaction": [{"manifestation": [{"text": reaction}], "severity": "moderate"}]}})
    return json.dumps({"resourceType": "Bundle", "id": bundle_id, "type": "collection", "entry": entries}, indent=1) + "\n"


# ---- Claims CSV ---------------------------------------------------------------------------------------------

CLAIMS_HEADER = ("claim_id,member_id,member_first_name,member_last_name,member_dob,member_gender,member_zip,"
                 "member_phone,service_date,icd10_code,billed_amount,payer_id,payer_name,claim_status,submitted_date,"
                 "status_date,allowed_amount,paid_amount,patient_responsibility,status_reason")

# Claim lifecycle (Silver migration 28): where each claim is now. Weights add up to 100.
PAYERS = [("60054", "AETNA"), ("87726", "UNITEDHEALTHCARE"), ("62308", "CIGNA"), ("00431", "MEDICARE PART B"),
          ("SB580", "BLUE CROSS BLUE SHIELD")]
CLAIM_STATUS_MIX = [("PAID", 45), ("PARTIALLY_PAID", 10), ("PENDED", 12), ("SUBMITTED", 5), ("ACCEPTED", 5),
                    ("DENIED", 12), ("REJECTED", 4), ("APPEALED", 7)]
DENIAL_REASONS = ["CO-50", "CO-16", "CO-197", "PR-96"]


def claim_lifecycle(claim_id: str, service, billed: float) -> List[str]:
    """The lifecycle columns of one claim line. Its own RNG, seeded by the claim id, so adding the lifecycle didn't
    move any other random draw (every non-claims byte of a seed is unchanged)."""
    r = random.Random("claim-lifecycle:" + claim_id)
    payer_id, payer = r.choice(PAYERS)
    status = r.choices([s for s, _ in CLAIM_STATUS_MIX], [w for _, w in CLAIM_STATUS_MIX])[0]
    submitted = service + timedelta(days=r.randrange(1, 6))
    changed = submitted if status == "SUBMITTED" else submitted + timedelta(days=r.randrange(3, 31))
    allowed = paid = owes = None
    reason = ""
    if status == "PAID":
        allowed = round(billed * r.choice([0.6, 0.7, 0.8, 0.9]), 2)
        reason, owes = r.choice([("", 0.0), ("PR-3", 20.0), ("PR-2", round(allowed * 0.2, 2)), ("PR-1", allowed)])
        owes = min(owes, allowed)
        paid = round(allowed - owes, 2)
    elif status == "PARTIALLY_PAID":
        allowed = round(billed * 0.7, 2)
        paid = round(allowed * 0.5, 2)
        owes = 0.0
        reason = "CO-45"
    elif status == "DENIED":
        reason = r.choice(DENIAL_REASONS)
        allowed, paid, owes = 0.0, 0.0, (billed if reason == "PR-96" else 0.0)
    elif status == "REJECTED":
        reason = "CO-16"
    elif status == "APPEALED":
        reason = "CO-50"
    money = lambda v: "" if v is None else "%.2f" % v
    return [payer_id, payer, status, submitted.isoformat(), changed.isoformat(), money(allowed), money(paid),
            money(owes), reason]


def claim_lines(xrng: random.Random, person: FeedPerson, when: datetime, claim_ids: set, namespace: int,
                defect: Optional[str]) -> Tuple[List[str], Optional[str]]:
    lines = []
    dx = list(person.diagnoses)
    code = None
    service = (when - timedelta(days=xrng.randrange(1, 60))).date()
    if defect == "UNKNOWN_ICD10":
        dx = [UNKNOWN_ICD10]
        code = UNKNOWN_ICD10[0]
    elif defect == "PREGNANCY_ON_MALE":
        dx = [PREGNANCY]
        code = PREGNANCY[0]
    elif defect == "DIAGNOSIS_IN_FUTURE":
        dx = dx[:1]
        service = FUTURE + timedelta(days=xrng.randrange(0, 300))
        code = dx[0][0]
    for icd, _ in dx[:3]:
        while True:
            claim_id = "CLM%s-%07d" % ("" if namespace == 0 else "N%d" % namespace, xrng.randrange(1000000, 9999999))
            if claim_id not in claim_ids:
                claim_ids.add(claim_id)
                break
        billed = xrng.choice([45.0, 95.5, 150.0, 220.0, 480.75])
        lines.append(",".join([claim_id, person.pid, person.first, person.last, person.dob.isoformat(), person.sex,
                               person.zip, person.phone, service.isoformat(), icd, "%.2f" % billed]
                              + claim_lifecycle(claim_id, service, billed)))
    return lines, code


# ---- C-CDA R2.1 CCD ------------------------------------------------------------------------------------------

def _ts(d) -> str:
    return d.strftime("%Y%m%d%H%M%S") + "+0000" if isinstance(d, datetime) else d.strftime("%Y%m%d")


def ccda_document(person: FeedPerson, c: Clinical, doc_id: str, when: datetime) -> str:
    e = escape
    problems = "".join(
        """
        <entry><act classCode="ACT" moodCode="EVN"><templateId root="2.16.840.1.113883.10.20.22.4.3" extension="2015-08-01"/>
          <statusCode code="active"/><entryRelationship typeCode="SUBJ"><observation classCode="OBS" moodCode="EVN">
            <templateId root="2.16.840.1.113883.10.20.22.4.4" extension="2015-08-01"/>
            <code code="55607006" codeSystem="%s" displayName="Problem"/><statusCode code="completed"/>
            <effectiveTime><low value="%s"/></effectiveTime>
            <value xsi:type="CD" code="%s" codeSystem="%s" displayName="%s"/>
          </observation></entryRelationship></act></entry>""" % (SNOMED_OID, _ts(onset), e(code), ICD10_OID, e(display))
        for code, display, onset in c.conditions)
    meds = "".join(
        """
        <entry><substanceAdministration classCode="SBADM" moodCode="INT">
          <templateId root="2.16.840.1.113883.10.20.22.4.16" extension="2014-06-09"/>
          <text>%s</text><statusCode code="active"/>
          <effectiveTime xsi:type="IVL_TS"><low value="%s"/></effectiveTime>
          <consumable><manufacturedProduct classCode="MANU"><manufacturedMaterial>
            <code code="%s" codeSystem="%s" displayName="%s"/></manufacturedMaterial></manufacturedProduct></consumable>
        </substanceAdministration></entry>""" % (e(sig), _ts(start), e(rx), RXNORM_OID, e(display))
        for rx, display, sig, start in c.meds)
    results = "".join(
        """
          <component><observation classCode="OBS" moodCode="EVN">
            <templateId root="2.16.840.1.113883.10.20.22.4.2" extension="2015-08-01"/>
            <code code="%s" codeSystem="%s" displayName="%s"/><statusCode code="completed"/>
            <effectiveTime value="%s"/><value xsi:type="PQ" value="%s" unit="%s"/>
            <interpretationCode code="%s" codeSystem="2.16.840.1.113883.5.83"/>
            <referenceRange><observationRange><value xsi:type="IVL_PQ"><low value="%s" unit="%s"/><high value="%s" unit="%s"/></value></observationRange></referenceRange>
          </observation></component>""" % (e(loinc), LOINC_OID, e(name), _ts(t), value, e(unit), flag, low, e(unit), high, e(unit))
        for loinc, name, value, unit, low, high, flag, t in c.labs)
    allergy = ""
    if c.allergy:
        rx, display, reaction = c.allergy
        allergy = """
    <component><section><templateId root="2.16.840.1.113883.10.20.22.2.6.1" extension="2015-08-01"/>
      <code code="48765-2" codeSystem="%s" displayName="Allergies and adverse reactions"/><title>ALLERGIES</title><text>%s</text>
      <entry><act classCode="ACT" moodCode="EVN"><statusCode code="active"/><entryRelationship typeCode="SUBJ">
        <observation classCode="OBS" moodCode="EVN"><code code="ASSERTION" codeSystem="2.16.840.1.113883.5.4"/>
          <statusCode code="completed"/><value xsi:type="CD" code="416098002" codeSystem="%s" displayName="Drug allergy"/>
          <participant typeCode="CSM"><participantRole classCode="MANU"><playingEntity classCode="MMAT">
            <code code="%s" codeSystem="%s" displayName="%s"/></playingEntity></participantRole></participant>
          <entryRelationship typeCode="MFST" inversionInd="true"><observation classCode="OBS" moodCode="EVN">
            <code code="ASSERTION" codeSystem="2.16.840.1.113883.5.4"/><value xsi:type="CD" code="271807003" codeSystem="%s" displayName="%s"/>
          </observation></entryRelationship>
        </observation></entryRelationship></act></entry>
    </section></component>""" % (LOINC_OID, e(display), SNOMED_OID, e(rx), RXNORM_OID, e(display), SNOMED_OID, e(reaction))
    return """<?xml version="1.0" encoding="UTF-8"?>
<!-- Synthetic C-CDA R2.1 CCD from the ClinicalHarmony data-generator. Fictional patient. -->
<ClinicalDocument xmlns="urn:hl7-org:v3" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <realmCode code="US"/>
  <typeId root="2.16.840.1.113883.1.3" extension="POCD_HD000040"/>
  <templateId root="2.16.840.1.113883.10.20.22.1.2" extension="2015-08-01"/>
  <id root="2.16.840.1.113883.19.5.99999.1" extension="%s"/>
  <code code="34133-9" codeSystem="%s" displayName="Summarization of Episode Note"/>
  <title>Continuity of Care Document</title>
  <effectiveTime value="%s"/>
  <recordTarget><patientRole>
    <id root="2.16.840.1.113883.19.5.99999.2" extension="%s"/>
    <addr use="HP"><streetAddressLine>%s</streetAddressLine><city>%s</city><state>%s</state><postalCode>%s</postalCode></addr>
    <telecom use="HP" value="tel:+1-%s-%s-%s"/>
    <patient><name use="L"><given>%s</given><family>%s</family></name>
      <administrativeGenderCode code="%s" codeSystem="2.16.840.1.113883.5.1"/><birthTime value="%s"/></patient>
  </patientRole></recordTarget>
  <component><structuredBody>
    <component><section><templateId root="2.16.840.1.113883.10.20.22.2.5.1" extension="2015-08-01"/>
      <code code="11450-4" codeSystem="%s" displayName="Problem list"/><title>PROBLEMS</title><text>Problems</text>%s
    </section></component>
    <component><section><templateId root="2.16.840.1.113883.10.20.22.2.1.1" extension="2014-06-09"/>
      <code code="10160-0" codeSystem="%s" displayName="History of medication use"/><title>MEDICATIONS</title><text>Medications</text>%s
    </section></component>
    <component><section><templateId root="2.16.840.1.113883.10.20.22.2.3.1" extension="2015-08-01"/>
      <code code="30954-2" codeSystem="%s" displayName="Relevant diagnostic tests and/or laboratory data"/><title>RESULTS</title><text>Results</text>
      <entry><organizer classCode="BATTERY" moodCode="EVN"><statusCode code="completed"/>
        <effectiveTime value="%s"/>%s
      </organizer></entry>
    </section></component>%s
  </structuredBody></component>
</ClinicalDocument>
""" % (e(doc_id), LOINC_OID, _ts(when), e(person.pid), e(person.street), e(person.city), e(person.state), e(person.zip),
       person.phone[:3], person.phone[3:6], person.phone[6:], e(person.first), e(person.last), person.sex,
       _ts(person.dob), LOINC_OID, problems, LOINC_OID, meds, LOINC_OID, _ts(when), results, allergy)


# ---- the whole feed set ----------------------------------------------------------------------------------------

@dataclass
class FeedRun:
    records: List[FeedRecord]
    cross_feed: List[CrossFeedDuplicate]
    defects: List[DefectEntry]


def sizes(count: int) -> Dict[str, int]:
    """Records per feed for an HL7 --count: FHIR Bundles count/6, claims members count/5, C-CDA documents count/10."""
    return {"fhir": max(1, count // 6), "claims": max(1, count // 5), "ccda": max(1, count // 10)}


def generate_feeds(seed: int, count: int, hl7_patients: List[Patient], feeds=FEEDS, cross_feed_rate: float = 0.20,
                   defect_rate: float = 0.12, namespace: int = 0) -> FeedRun:
    if not 0 <= cross_feed_rate <= 1:
        raise ValueError("cross_feed_rate must be between 0 and 1")
    if not 0 <= defect_rate <= 1:
        raise ValueError("defect_rate must be between 0 and 1")
    feeds = [f for f in FEEDS if f in feeds]
    xrng = random.Random("9d-feeds:%d:%d" % (seed, namespace))
    used: set = set()
    claim_ids: set = set()
    when = datetime(2026, 11, 19, 9, 0, 0)
    records: List[FeedRecord] = []
    cross: List[CrossFeedDuplicate] = []
    defects: List[DefectEntry] = []
    if not feeds:
        return FeedRun(records, cross, defects)

    # 1. Cross-feed duplicates: HL7 people that reached Silver as sent (first ADT intact, phone never changed by an
    #    A08, not a 9c facility twin) — so the expected PMI outcome holds whenever matching runs.
    twins_9c = {id(p.twin) for p in hl7_patients if p.twin is not None}
    pool = [p for p in hl7_patients if p.first_adt_index and not p.adt_corrupted and not p.phone_changed
            and id(p) not in twins_9c and p.twin is None]
    xrng.shuffle(pool)
    originals = sum(1 for p in hl7_patients if id(p) not in twins_9c)
    n_cross = min(len(pool), round(originals * cross_feed_rate))
    for i in range(n_cross):
        variation = VARIATIONS[i % len(VARIATIONS)]
        p = next((q for q in pool if eligible(q, variation)), None)
        if p is None:
            variation = "EXACT"
            p = pool[0]
        pool.remove(p)
        feed = feeds[i % len(feeds)]
        t = twin_of(xrng, p, feed, variation, used, namespace)
        outcome, score, _, _ = pmi.score(_hl7_person(p), t.as_pmi())
        cross.append(CrossFeedDuplicate(variation, feed, p, t, outcome, score))
        records.append(_record(xrng, feed, [t], when, claim_ids, namespace, None, len(records) + 1, p.first_adt_index))

    # 2. Feed-only people up to each feed's size; a share of them with a planted defect.
    budget = sizes(count)
    for c in cross:
        budget[c.feed] -= 1
    plan = [f for f in feeds for _ in range(max(0, budget[f]))]
    n_defects = round(len(plan) * defect_rate)
    defect_slots = set(xrng.sample(range(len(plan)), min(n_defects, len(plan))))
    used_kinds = {k: 0 for k in DEFECTS}
    for slot, feed in enumerate(plan):
        defect = None
        if slot in defect_slots:
            # The least-used kind this feed can carry, so a run covers as many checks as it has defects.
            defect = min((k for k in DEFECTS if feed in DEFECTS[k][1]), key=lambda k: used_kinds[k])
            used_kinds[defect] += 1
        when += timedelta(minutes=xrng.randrange(3, 20))
        if feed == "claims" and defect is None and xrng.random() < 0.5:
            people = [feed_only_person(xrng, feed, used, namespace) for _ in range(2)]   # a 2-member claims file
        else:
            people = [feed_only_person(xrng, feed, used, namespace)]
        if defect == "PREGNANCY_ON_MALE":
            people[0].sex = "M"
            people[0].first = _title(xrng.choice(catalog.FIRST_NAMES_M))
        rec = _record(xrng, feed, people, when, claim_ids, namespace, defect, len(records) + 1, 0)
        records.append(rec)
        if defect:
            p0 = people[0]
            defects.append(DefectEntry(defect, feed, "%s/%s" % (feed, rec.name), SOURCES[feed], p0.pid,
                                       "%s %s" % (p0.first, p0.last), p0.dob.isoformat(), rec.defect_code))
    return FeedRun(records, cross, defects)


def _record(xrng, feed, people, when, claim_ids, namespace, defect, n, after_index) -> FeedRecord:
    code = None
    if feed == "claims":
        lines = []
        for i, person in enumerate(people):
            ls, c = claim_lines(xrng, person, when, claim_ids, namespace, defect if i == 0 else None)
            lines += ls
            code = code or c
        payload = CLAIMS_HEADER + "\n" + "\n".join(lines) + "\n"
        name = "%03d_claims_%s.csv" % (n, people[0].pid)
    else:
        c, code = clinical(xrng, people[0], when, defect)
        if feed == "fhir":
            payload = fhir_bundle(people[0], c, "bundle-%s" % people[0].pid.lower())
            name = "%03d_fhir_%s.json" % (n, people[0].pid)
        else:
            payload = ccda_document(people[0], c, "CCD-%s" % people[0].pid, when)
            name = "%03d_ccda_%s.xml" % (n, people[0].pid)
    return FeedRecord(feed, name, payload, people, after_index=after_index, defect=defect, defect_code=code)


def send_order(xrng_seed: str, hl7_count: int, records: List[FeedRecord]) -> List[Tuple[str, int]]:
    """Interleaves HL7 messages (('hl7', i)) and feed records (('feed', j)): a cross-feed twin right after its
    original's first ADT, feed-only records at seeded random positions."""
    xrng = random.Random(xrng_seed)
    slots: Dict[int, List[int]] = {}
    for j, r in enumerate(records):
        pos = r.after_index if r.after_index else xrng.randrange(1, hl7_count + 1)
        slots.setdefault(pos, []).append(j)
    order: List[Tuple[str, int]] = []
    for i in range(1, hl7_count + 1):
        order.append(("hl7", i))
        for j in slots.get(i, []):
            order.append(("feed", j))
    return order
