import unittest

from hl7gen import catalog
from datetime import datetime

from hl7gen.messages import generate, generate_run


class GenerateTest(unittest.TestCase):

    def test_same_seed_same_bytes(self):
        a = [m.payload for m in generate(100, 42)]
        b = [m.payload for m in generate(100, 42)]
        self.assertEqual(a, b)
        self.assertNotEqual(a, [m.payload for m in generate(100, 43)])

    def test_exactly_ten_percent_malformed_covering_every_defect(self):
        msgs = generate(100, 42, 0.10)
        bad = [m for m in msgs if m.kind == "malformed"]
        self.assertEqual(len(bad), 10)
        self.assertEqual({m.defect for m in bad}, set(catalog.MALFORMED_KINDS))

    def test_every_payload_is_unique(self):
        msgs = generate(300, 7)
        self.assertEqual(len({m.sha256 for m in msgs}), 300)

    def test_valid_messages_are_well_formed_hl7(self):
        for m in generate(100, 42):
            if m.kind != "valid":
                continue
            self.assertTrue(m.payload.endswith("\r"))
            self.assertNotIn("\n", m.payload)
            segs = m.payload.rstrip("\r").split("\r")
            msh = segs[0].split("|")
            self.assertEqual(msh[0], "MSH")
            self.assertEqual(msh[3], m.source_system)            # MSH-4
            self.assertEqual(msh[8].split("^")[:2], m.message_type.split("^"))  # MSH-9
            self.assertEqual(msh[9], m.control_id)               # MSH-10
            self.assertEqual(msh[11], "2.5")                     # MSH-12
            pid = next(s for s in segs if s.startswith("PID|")).split("|")
            self.assertEqual(pid[3].split("^")[0], m.patient_mrn)
            if m.message_type == "ORU^R01":
                self.assertTrue(any(s.startswith("OBX|") for s in segs))
            elif m.message_type == "RDE^O11":
                self.assertTrue(any(s.startswith("RXE|") for s in segs))
            else:
                self.assertTrue(any(s.startswith("DG1|") for s in segs))

    def test_pharmacy_orders_use_rxnorm_and_the_home_facility_mrn(self):
        msgs = [m for m in generate(300, 5, 0.0) if m.message_type == "RDE^O11"]
        self.assertGreater(len(msgs), 20)
        for m in msgs:
            segs = m.payload.rstrip("\r").split("\r")
            self.assertEqual([s.split("|")[0] for s in segs], ["MSH", "PID", "ORC", "RXE", "RXR"])
            self.assertEqual(m.source_system, "CITY_PHARMACY")
            pid3 = segs[1].split("|")[3].split("^")
            self.assertIn(pid3[3], ("CITY_HOSPITAL", "NORTH_CLINIC"))  # assigning authority = home facility
            give = segs[3].split("|")[2].split("^")
            self.assertIn(give[0], catalog.MEDICATIONS)
            self.assertEqual(give[2], "RXNORM")
            orc = segs[2].split("|")
            self.assertEqual(orc[1], "NW")                       # ORC-1 order control
            self.assertEqual(orc[9], m.payload.split("|")[6])    # ORC-9 transaction time = MSH-7
            self.assertEqual(len(orc[12].split("^")), 3)         # ORC-12 ordering provider

    def test_some_orders_conflict_with_the_patients_allergy(self):
        msgs = generate(300, 5, 0.0)
        allergy_of = {}
        for m in msgs:
            for seg in m.payload.split("\r"):
                if seg.startswith("AL1|"):
                    allergy_of[m.patient_mrn] = seg.split("|")[3].split("^")[0]
        conflicts = [m for m in msgs if m.message_type == "RDE^O11"
                     and allergy_of.get(m.patient_mrn) == m.payload.split("RXE|")[1].split("|")[1].split("^")[0]]
        self.assertGreater(len(conflicts), 0)

    def test_first_message_per_patient_is_an_admit_or_registration(self):
        seen = set()
        for m in generate(100, 42):
            if m.kind == "valid" and m.patient_mrn not in seen:
                self.assertIn(m.message_type, ("ADT^A01", "ADT^A04"))
            seen.add(m.patient_mrn)

    def test_malformed_defects(self):
        for m in generate(100, 42):
            if m.kind != "malformed":
                continue
            first = m.payload.split("\r")[0]
            if m.defect in ("MISSING_MSH", "NOT_HL7"):
                self.assertFalse(first.startswith("MSH"))
            elif m.defect == "EMPTY_CONTROL_ID":
                self.assertEqual(first.split("|")[9], "")
            elif m.defect == "EMPTY_MESSAGE_TYPE":
                self.assertEqual(first.split("|")[8], "")
            elif m.defect == "TRUNCATED_HEADER":
                self.assertLess(len(first.split("|")), 9)
            self.assertEqual(m.manifest_entry()["expectedStatus"], "QUARANTINED")

    # ---- 9c: cross-facility duplicates -------------------------------------------------------------

    @staticmethod
    def _pid(m):
        seg = next(s for s in m.payload.split("\r") if s.startswith("PID|")).split("|")
        name = seg[5].split("^")
        addr = seg[11].split("^")
        return {"mrn": seg[3].split("^")[0], "aa": seg[3].split("^")[3], "last": name[0], "first": name[1],
                "dob": seg[7], "sex": seg[8], "street": addr[0], "zip": addr[4], "phone": seg[13]}

    def test_about_ten_percent_of_patients_are_registered_at_both_facilities(self):
        messages, dups = generate_run(100, 42, 0.10, 0.10)
        self.assertEqual(len(dups), 3)                        # round(33 × 0.10)
        self.assertEqual([d.kind for d in dups], ["IDENTICAL", "FUZZY", "IDENTICAL"])
        for d in dups:
            self.assertEqual({d.original_facility, d.twin_facility}, {"CITY_HOSPITAL", "NORTH_CLINIC"})
            self.assertNotEqual(d.original_mrn, d.twin_mrn)
            self.assertTrue(d.twin_mrn.startswith("CH" if d.twin_facility == "CITY_HOSPITAL" else "NC"))
        self.assertEqual(generate_run(100, 42)[1], dups)       # same seed → same duplicates
        self.assertEqual(generate_run(100, 42, 0.10, 0.0)[1], [])

    def test_twin_registration_follows_the_originals_first_adt_at_the_other_facility(self):
        messages, dups = generate_run(300, 11, 0.0, 0.10)
        by_mrn = {}
        for m in messages:
            by_mrn.setdefault(m.patient_mrn, []).append(m)
        sent = [d for d in dups if d.original_mrn in by_mrn]
        self.assertGreater(len(sent), 5)
        for d in sent:
            first = by_mrn[d.original_mrn][0]
            twin_first = by_mrn[d.twin_mrn][0]
            self.assertEqual(twin_first.index, first.index + 1)
            self.assertEqual(twin_first.message_type, "ADT^A01" if d.twin_facility == "CITY_HOSPITAL" else "ADT^A04")
            self.assertEqual(twin_first.source_system, d.twin_facility)
            self.assertEqual(self._pid(twin_first)["aa"], d.twin_facility)   # PID-3.4 = the twin's facility

    def test_identical_twins_match_deterministically_and_fuzzy_ones_differ_in_birth_day_and_phone(self):
        messages, dups = generate_run(300, 11, 0.0, 0.10)
        first_adt = {}
        for m in messages:
            first_adt.setdefault(m.patient_mrn, m)
        kinds = set()
        for d in dups:
            if d.original_mrn not in first_adt:
                continue
            a, b = self._pid(first_adt[d.original_mrn]), self._pid(first_adt[d.twin_mrn])
            for f in ("last", "first", "sex", "street", "zip"):
                self.assertEqual(a[f], b[f], f)
            if d.kind == "IDENTICAL":
                self.assertEqual(a["dob"], b["dob"])            # EXACT_NAME_DOB_PLUS_PHONE_OR_ZIP
                self.assertEqual(a["phone"], b["phone"])
            else:
                da, db = datetime.strptime(a["dob"], "%Y%m%d"), datetime.strptime(b["dob"], "%Y%m%d")
                self.assertEqual((da.year, da.month), (db.year, db.month))
                self.assertEqual(abs((da - db).days), 1)       # PARTIAL birth date: review band, not auto
                self.assertNotEqual(a["phone"], b["phone"])
            kinds.add(d.kind)
        self.assertEqual(kinds, {"IDENTICAL", "FUZZY"})

    def test_bad_arguments(self):
        with self.assertRaises(ValueError):
            generate(0)
        with self.assertRaises(ValueError):
            generate(10, malformed_rate=1.5)
        with self.assertRaises(ValueError):
            generate(10, duplicate_rate=-0.1)


if __name__ == "__main__":
    unittest.main()
