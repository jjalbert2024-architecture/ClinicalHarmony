"""Seeded HL7 v2.5 message generation.

Everything is derived from one ``random.Random(seed)``: the same seed and count always produce the same
bytes, so a re-run re-sends identical messages (and Bronze, being idempotent, stores none of them twice).
"""
import hashlib
import random
from dataclasses import dataclass, field, asdict
from datetime import datetime, timedelta
from typing import List, Optional

from . import catalog

SEGMENT_END = "\r"  # HL7 v2 segment terminator; MLLP senders never use \n
BASE_TIME = datetime(2026, 11, 2, 7, 30, 0)


@dataclass
class Patient:
    mrn: str
    facility: str          # assigning authority (home facility)
    last: str
    first: str
    middle: str
    sex: str
    dob: str               # YYYYMMDD
    street: str
    city: str
    state: str
    zip: str
    phone: str
    diagnoses: List[tuple] = field(default_factory=list)
    allergy: Optional[tuple] = None
    admitted: bool = False


@dataclass
class GeneratedMessage:
    index: int             # 1-based position in the send order
    kind: str              # "valid" | "malformed"
    defect: Optional[str]  # one of catalog.MALFORMED_KINDS for malformed messages
    message_type: str      # e.g. ADT^A01 (what it was meant to be, for malformed ones)
    source_system: str     # MSH-4 as sent (what ingestion-service will tag it with)
    control_id: Optional[str]
    patient_mrn: Optional[str]
    payload: str

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.payload.encode("utf-8")).hexdigest()

    @property
    def filename(self) -> str:
        label = self.message_type.replace("^", "_")
        if self.kind == "malformed":
            label += "_MALFORMED_" + self.defect
        return "%04d_%s_%s.hl7" % (self.index, label, self.source_system)

    def manifest_entry(self) -> dict:
        d = asdict(self)
        del d["payload"]
        d["file"] = self.filename
        d["sha256"] = self.sha256
        d["expectedStatus"] = "PENDING" if self.kind == "valid" else "QUARANTINED"
        return d


def ts(t: datetime) -> str:
    return t.strftime("%Y%m%d%H%M%S")


def make_patients(rng: random.Random, n: int) -> List[Patient]:
    patients = []
    for i in range(n):
        app, facility, _ = rng.choice(catalog.ADT_FACILITIES)
        sex = rng.choice("FM")
        first = rng.choice(catalog.FIRST_NAMES_F if sex == "F" else catalog.FIRST_NAMES_M)
        city, state, zip3 = rng.choice(catalog.CITIES)
        dob = datetime(1935, 1, 1) + timedelta(days=rng.randrange(0, 365 * 85))
        p = Patient(
            mrn="%s%06d" % ("CH" if facility == "CITY_HOSPITAL" else "NC", rng.randrange(100000, 999999)),
            facility=facility,
            last=rng.choice(catalog.LAST_NAMES),
            first=first,
            middle=rng.choice("ABCDEJKLMR"),
            sex=sex,
            dob=dob.strftime("%Y%m%d"),
            street="%d %s" % (rng.randrange(10, 9999), rng.choice(catalog.STREETS)),
            city=city, state=state, zip="%s%02d" % (zip3, rng.randrange(0, 99)),
            phone="555%07d" % rng.randrange(0, 9999999),
            diagnoses=rng.sample(catalog.DIAGNOSES, rng.randint(1, 3)),
            allergy=rng.choice(catalog.ALLERGENS) if rng.random() < 0.3 else None,
        )
        patients.append(p)
    return patients


def msh(app: str, facility: str, when: datetime, msg_type: str, structure: str, control_id: str) -> str:
    return "MSH|^~\\&|%s|%s|CLINICALHARMONY|INGESTION|%s||%s^%s|%s|P|2.5" % (
        app, facility, ts(when), msg_type, structure, control_id)


def pid(p: Patient) -> str:
    return "PID|1||%s^^^%s^MR||%s^%s^%s||%s|%s|||%s^^%s^%s^%s||%s" % (
        p.mrn, p.facility, p.last, p.first, p.middle, p.dob, p.sex, p.street, p.city, p.state, p.zip, p.phone)


def build_adt(rng: random.Random, p: Patient, event: str, when: datetime, control_id: str) -> GeneratedMessage:
    app = next(a for a, f, _ in catalog.FACILITIES if f == p.facility)
    doc = rng.choice(catalog.DOCTORS)
    patient_class = "I" if event == "A01" else "O"
    segs = [
        msh(app, p.facility, when, "ADT^" + event, "ADT_A01", control_id),
        "EVN|%s|%s" % (event, ts(when)),
        pid(p),
        "PV1|1|%s|%s^%d^%d||||%s^%s^%s|||MED||||%s|A0" % (
            patient_class, rng.choice(["MED", "SURG", "CARD"]), rng.randrange(100, 499), rng.randrange(1, 3),
            doc[0], doc[1], doc[2], "ADM" if event == "A01" else "REF"),
    ]
    for i, (code, desc) in enumerate(p.diagnoses, start=1):
        segs.append("DG1|%d||%s^%s^I10||%s|%s" % (i, code, desc, ts(when), "A" if event == "A01" else "W"))
    if p.allergy:
        segs.append("AL1|1|DA|%s^%s^RXNORM|%s|%s" % (p.allergy[0], p.allergy[1], rng.choice(["MI", "MO", "SV"]),
                                                     rng.choice(catalog.REACTIONS)))
    return GeneratedMessage(0, "valid", None, "ADT^" + event, p.facility, control_id, p.mrn,
                            SEGMENT_END.join(segs) + SEGMENT_END)


def build_oru(rng: random.Random, p: Patient, when: datetime, control_id: str) -> GeneratedMessage:
    # A home clinic that runs its own lab sends its own results; otherwise the regional lab does.
    app, facility, _ = next(f for f in catalog.FACILITIES if f[1] == p.facility) \
        if p.facility == "NORTH_CLINIC" and rng.random() < 0.5 else rng.choice(
            [f for f in catalog.ORU_FACILITIES if f[1] == "REGIONAL_LAB"])
    panel_code, panel_name, tests = rng.choice(catalog.PANELS)
    collected = when - timedelta(minutes=rng.randrange(30, 240))
    order = rng.randrange(100000, 999999)
    segs = [
        msh(app, facility, when, "ORU^R01", "ORU_R01", control_id),
        pid(p),
        "OBR|1|ORD%d|FIL%d|%s^%s^LN|||%s|||||||||%s^%s^%s||||||%s|||F" % (
            order, order, panel_code, panel_name, ts(collected), *rng.choice(catalog.DOCTORS), ts(when)),
    ]
    for i, loinc in enumerate(tests, start=1):
        name, unit, low, high, decimals = catalog.LABS[loinc]
        span = high - low
        value = rng.uniform(low - 0.25 * span, high + 0.35 * span)
        flag = "L" if value < low else ("H" if value > high else "N")
        fmt = "%." + str(decimals) + "f"
        segs.append("OBX|%d|NM|%s^%s^LN||%s|%s|%s-%s|%s|||F|||%s" % (
            i, loinc, name, fmt % value, unit, fmt % low, fmt % high, flag, ts(collected)))
    return GeneratedMessage(0, "valid", None, "ORU^R01", facility, control_id, p.mrn,
                            SEGMENT_END.join(segs) + SEGMENT_END)


def build_rde(rng: random.Random, p: Patient, when: datetime, control_id: str) -> GeneratedMessage:
    """RDE^O11 pharmacy order: ORC (new order) + RXE (RxNorm give code, dose, instructions) + RXR.

    For a patient with a drug allergy, half the orders are for that very drug — a deliberate
    MEDICATION_ALLERGY_CONFLICT for the Silver validation to catch."""
    app, facility, _ = next(f for f in catalog.FACILITIES if "RDE" in f[2])
    if p.allergy and p.allergy[0] in catalog.MEDICATIONS and rng.random() < 0.5:
        rxcui = p.allergy[0]
    else:
        options = [rx for code, _ in p.diagnoses for rx in catalog.TREATMENTS.get(code, [])]
        rxcui = rng.choice(options or sorted(catalog.MEDICATIONS))
    name, amount, unit, instructions = catalog.MEDICATIONS[rxcui]
    doc = rng.choice(catalog.DOCTORS)
    order = rng.randrange(100000, 999999)
    segs = [
        msh(app, facility, when, "RDE^O11", "RDE_O11", control_id),
        pid(p),
        "ORC|NW|RX%d|PH%d||||||%s|||%s^%s^%s" % (order, order, ts(when), doc[0], doc[1], doc[2]),
        "RXE|^^^%s^^R|%s^%s^RXNORM|%s||%s^%s^UCUM||^%s|||30|TAB" % (
            ts(when), rxcui, name, amount, unit, unit, instructions),
        "RXR|PO^Oral^HL70162",
    ]
    return GeneratedMessage(0, "valid", None, "RDE^O11", facility, control_id, p.mrn,
                            SEGMENT_END.join(segs) + SEGMENT_END)


def corrupt(rng: random.Random, msg: GeneratedMessage, defect: str) -> GeneratedMessage:
    """Turn a valid message into one Bronze must QUARANTINE (still sent, still kept)."""
    segments = msg.payload.rstrip(SEGMENT_END).split(SEGMENT_END)
    msh_fields = segments[0].split("|")
    if defect == "MISSING_MSH":
        payload = SEGMENT_END.join(segments[1:]) + SEGMENT_END
        source, control = "UNKNOWN_HL7_SOURCE", None
    elif defect == "NOT_HL7":
        payload = "Results for patient %s attached, please call the lab on ext %d. Ref %s\r" % (
            msg.patient_mrn, rng.randrange(1000, 9999), msg.control_id)
        source, control = "UNKNOWN_HL7_SOURCE", None
    elif defect == "EMPTY_CONTROL_ID":
        msh_fields[9] = ""
        payload = SEGMENT_END.join(["|".join(msh_fields)] + segments[1:]) + SEGMENT_END
        source, control = msg.source_system, None
    elif defect == "EMPTY_MESSAGE_TYPE":
        msh_fields[8] = ""
        payload = SEGMENT_END.join(["|".join(msh_fields)] + segments[1:]) + SEGMENT_END
        source, control = msg.source_system, msg.control_id
    elif defect == "TRUNCATED_HEADER":
        # Cut inside MSH-7 (the timestamp): MSH-9 and everything after never arrived.
        cut = len("|".join(msh_fields[:6])) + 1 + rng.randrange(4, 12)
        payload = segments[0][:cut] + SEGMENT_END
        source, control = msg.source_system, None
    else:
        raise ValueError("unknown defect " + defect)
    return GeneratedMessage(msg.index, "malformed", defect, msg.message_type, source, control, msg.patient_mrn, payload)


def generate(count: int = 100, seed: int = 42, malformed_rate: float = 0.10) -> List[GeneratedMessage]:
    """``count`` messages in send order; exactly ``round(count * malformed_rate)`` are malformed."""
    if count < 1:
        raise ValueError("count must be >= 1")
    if not 0 <= malformed_rate <= 1:
        raise ValueError("malformed_rate must be between 0 and 1")
    rng = random.Random(seed)
    patients = make_patients(rng, max(1, count // 3))
    n_bad = round(count * malformed_rate)
    bad_positions = set(rng.sample(range(count), n_bad))
    kinds = list(catalog.MALFORMED_KINDS)
    rng.shuffle(kinds)

    messages = []
    when = BASE_TIME
    bad_seen = 0
    for i in range(count):
        when += timedelta(minutes=rng.randrange(2, 15), seconds=rng.randrange(0, 60))
        control_id = "S%d-%05d" % (seed, i + 1)
        p = rng.choice(patients)
        if not p.admitted:
            msg = build_adt(rng, p, "A01" if p.facility == "CITY_HOSPITAL" else "A04", when, control_id)
            p.admitted = True
        else:
            r = rng.random()
            if r < 0.55:
                msg = build_oru(rng, p, when, control_id)
            elif r < 0.75:
                msg = build_rde(rng, p, when, control_id)
            else:
                if rng.random() < 0.5:
                    p.phone = "555%07d" % rng.randrange(0, 9999999)
                msg = build_adt(rng, p, "A08", when, control_id)
        msg.index = i + 1
        if i in bad_positions:
            msg = corrupt(rng, msg, kinds[bad_seen % len(kinds)])
            bad_seen += 1
        messages.append(msg)
    return messages
