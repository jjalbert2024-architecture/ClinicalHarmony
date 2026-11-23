"""A Python port of patient-index-service's PatientMatcher scoring (PMI_MATCH rule, docs/db-init/20), so the
generator can write the EXPECTED outcome of every planted cross-feed duplicate into its manifest.

Keep in step with patient-index-service/.../matching/PatientMatcher.java, JaroWinkler.java, Nicknames.java and
Normalizer.java. Only what the generator's records need is ported: names, DOB, gender, phone, ZIP, address line 1.
"""
import re
import unicodedata
from dataclasses import dataclass
from datetime import date
from typing import Optional

WEIGHTS = {
    "lastName": {"exact": 4.0, "similar": 2.5, "similarMin": 0.92, "disagree": -3.0},
    "firstName": {"exact": 3.5, "similar": 2.0, "similarMin": 0.90, "initial": 0.5, "disagree": -3.0},
    "birthDate": {"exact": 5.0, "partial": 2.0, "disagree": -5.0},
    "gender": {"exact": 0.5, "disagree": -4.0},
    "phone": {"exact": 4.0, "disagree": -1.0},
    "postalCode": {"exact": 1.5, "disagree": -0.5},
    "address": {"exact": 2.5, "similar": 1.5, "similarMin": 0.90, "disagree": -0.5},
}
AUTO_MERGE = 0.85
REVIEW = 0.60
MIN_COMPARABLE_WEIGHT = 10.0

# The nickname groups the generator uses (a subset of Nicknames.java's).
NICKNAME_GROUPS = [
    ("james", "jim", "jimmy", "jamie"),
    ("william", "will", "bill", "billy", "liam"),
    ("daniel", "dan", "danny"),
    ("samuel", "sam", "sammy"),
]
# Generator first name → the nickname another feed uses for the same person.
NICKNAMES = {"JAMES": "JIM", "DANIEL": "DAN", "SAMUEL": "SAM", "LIAM": "WILLIAM"}


@dataclass
class Person:
    """Demographics as Silver stores them (what the PMI compares). None = not sent by that feed."""
    first: str
    last: str
    dob: date
    gender: str                    # male / female / other / unknown
    phone: Optional[str] = None
    zip: Optional[str] = None
    address: Optional[str] = None


def name(v: Optional[str]) -> Optional[str]:
    if v is None:
        return None
    ascii_ = unicodedata.normalize("NFD", v).encode("ascii", "ignore").decode()
    letters = re.sub("[^a-z]", "", ascii_.lower())
    return letters or None


def phone(v: Optional[str]) -> Optional[str]:
    if v is None:
        return None
    digits = re.sub(r"\D", "", v)
    if len(digits) == 11 and digits.startswith("1"):
        digits = digits[1:]
    return digits if len(digits) == 10 else None


def postal(v: Optional[str]) -> Optional[str]:
    if v is None:
        return None
    digits = re.sub(r"\D", "", v)
    return digits[:5] if len(digits) >= 5 else None


def address(v: Optional[str]) -> Optional[str]:
    if v is None:
        return None
    s = re.sub(r"[^a-z0-9 ]", " ", v.lower())
    s = re.sub(" +", " ", s).strip()
    return s or None


def jaro(s1: str, s2: str) -> float:
    l1, l2 = len(s1), len(s2)
    if l1 == 0 or l2 == 0:
        return 0.0
    window = max(0, max(l1, l2) // 2 - 1)
    m1, m2 = [False] * l1, [False] * l2
    matches = 0
    for i in range(l1):
        for j in range(max(0, i - window), min(l2 - 1, i + window) + 1):
            if not m2[j] and s1[i] == s2[j]:
                m1[i] = m2[j] = True
                matches += 1
                break
    if matches == 0:
        return 0.0
    t, k = 0, 0
    for i in range(l1):
        if m1[i]:
            while not m2[k]:
                k += 1
            if s1[i] != s2[k]:
                t += 1
            k += 1
    return (matches / l1 + matches / l2 + (matches - t / 2) / matches) / 3.0


def jaro_winkler(s1: str, s2: str) -> float:
    if s1 == s2:
        return 1.0
    j = jaro(s1, s2)
    if j <= 0.7:
        return j
    prefix = 0
    while prefix < min(4, len(s1), len(s2)) and s1[prefix] == s2[prefix]:
        prefix += 1
    return j + prefix * 0.1 * (1.0 - j)


def _r4(x: float) -> float:
    return round(x + 0.0, 4)


def _nick(a: str, b: str) -> bool:
    return any(a in g and b in g for g in NICKNAME_GROUPS)


def score(a: Person, b: Person):
    """(decision, score, total, max) — decision AUTO_MERGED | PENDING_REVIEW | NO_MATCH, as PatientMatcher decides."""
    fields = []   # (outcome, weight, max)
    la, lb, fa, fb = name(a.last), name(b.last), name(a.first), name(b.first)
    w = WEIGHTS["lastName"]
    if la and lb:
        if la == lb:
            fields.append(("EXACT", w["exact"], w["exact"]))
        else:
            fields.append(("SIMILAR", w["similar"], w["exact"]) if _r4(jaro_winkler(la, lb)) >= w["similarMin"]
                          else ("DISAGREE", w["disagree"], w["exact"]))
    w = WEIGHTS["firstName"]
    if fa and fb:
        if fa == fb:
            fields.append(("EXACT", w["exact"], w["exact"]))
        elif _nick(fa, fb):
            fields.append(("NICKNAME", w["similar"], w["exact"]))
        elif len(fa) == 1 or len(fb) == 1:
            fields.append(("INITIAL", w["initial"], w["exact"]) if fa[0] == fb[0] else ("DISAGREE", w["disagree"], w["exact"]))
        else:
            fields.append(("SIMILAR", w["similar"], w["exact"]) if _r4(jaro_winkler(fa, fb)) >= w["similarMin"]
                          else ("DISAGREE", w["disagree"], w["exact"]))
    w = WEIGHTS["birthDate"]
    da, db = a.dob, b.dob
    if da == db:
        dob = ("EXACT", w["exact"], w["exact"])
    else:
        swapped = da.year == db.year and da.month == db.day and da.day == db.month
        differing = (da.year != db.year) + (da.month != db.month) + (da.day != db.day)
        dob = ("PARTIAL", w["partial"], w["exact"]) if swapped or differing == 1 else ("DISAGREE", w["disagree"], w["exact"])
    fields.append(dob)
    w = WEIGHTS["gender"]
    gender = None
    if a.gender in ("male", "female") and b.gender in ("male", "female"):
        gender = ("EXACT", w["exact"], w["exact"]) if a.gender == b.gender else ("DISAGREE", w["disagree"], w["exact"])
        fields.append(gender)
    for key, va, vb in (("phone", phone(a.phone), phone(b.phone)), ("postalCode", postal(a.zip), postal(b.zip))):
        w = WEIGHTS[key]
        if va and vb:
            fields.append(("EXACT", w["exact"], w["exact"]) if va == vb else ("DISAGREE", w["disagree"], w["exact"]))
    w = WEIGHTS["address"]
    aa, ab = address(a.address), address(b.address)
    if aa and ab:
        if aa == ab:
            fields.append(("EXACT", w["exact"], w["exact"]))
        else:
            fields.append(("SIMILAR", w["similar"], w["exact"]) if _r4(jaro_winkler(aa, ab)) >= w["similarMin"]
                          else ("DISAGREE", w["disagree"], w["exact"]))
    total = sum(f[1] for f in fields)
    maximum = sum(f[2] for f in fields)
    weighted = _r4(max(0.0, min(1.0, total / maximum)) if maximum > 0 else 0.0)

    contradicted = dob[0] == "DISAGREE" or (gender is not None and gender[0] == "DISAGREE")
    if not contradicted and fa and fa == fb and la and la == lb and dob[0] == "EXACT" and (
            (phone(a.phone) and phone(a.phone) == phone(b.phone)) or (postal(a.zip) and postal(a.zip) == postal(b.zip))):
        return "AUTO_MERGED", 1.0, total, maximum
    if weighted >= AUTO_MERGE and maximum >= MIN_COMPARABLE_WEIGHT:
        return "AUTO_MERGED", weighted, total, maximum
    if weighted >= REVIEW:
        return "PENDING_REVIEW", weighted, total, maximum
    return "NO_MATCH", weighted, total, maximum
