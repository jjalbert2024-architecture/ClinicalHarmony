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
            else:
                self.assertTrue(any(s.startswith("DG1|") for s in segs))

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
