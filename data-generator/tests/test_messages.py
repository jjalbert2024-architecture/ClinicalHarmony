import unittest

from hl7gen import catalog
from hl7gen.messages import generate


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

    def test_bad_arguments(self):
        with self.assertRaises(ValueError):
            generate(0)
        with self.assertRaises(ValueError):
            generate(10, malformed_rate=1.5)


if __name__ == "__main__":
    unittest.main()
