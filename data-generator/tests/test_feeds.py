import json
import tempfile
import threading
import unittest
import xml.dom.minidom
from datetime import date
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

from hl7gen import cli, feeds, pmi
from hl7gen.messages import generate, generate_run
from hl7gen.rest import RestClient


def run(seed=42, count=100, **kw):
    patients = []
    namespace = kw.pop("namespace", 0)
    msgs, dups = generate_run(count, seed, patients_out=patients, namespace=namespace)
    return msgs, patients, feeds.generate_feeds(seed, count, patients, namespace=namespace, **kw)


class PmiPortTest(unittest.TestCase):

    def test_9c_fuzzy_pair_scores_13_of_21(self):
        a = pmi.Person("AISHA", "OKAFOR", date(2019, 10, 14), "female", "5551112222", "62701", "12 OAK ST")
        b = pmi.Person("AISHA", "OKAFOR", date(2019, 10, 15), "female", "5553334444", "62701", "12 OAK ST")
        decision, score, total, maximum = pmi.score(a, b)
        self.assertEqual((decision, score, total, maximum), ("PENDING_REVIEW", 0.619, 13.0, 21.0))

    def test_exact_name_dob_plus_zip_is_deterministic(self):
        a = pmi.Person("Jo", "Doe", date(1980, 1, 1), "female", "5551112222", "62701")
        b = pmi.Person("JO", "DOE", date(1980, 1, 1), "female", None, "62701-1234")
        self.assertEqual(pmi.score(a, b)[:2], ("AUTO_MERGED", 1.0))

    def test_nickname_and_contradictions(self):
        a = pmi.Person("JAMES", "SINGH", date(1935, 2, 25), "male", "5550000001", "62701", "1 ELM ST")
        self.assertEqual(pmi.score(a, pmi.Person("Jim", "Singh", date(1935, 2, 25), "male", "5550000001", "62701", "1 Elm St"))[0],
                         "AUTO_MERGED")
        # male vs female blocks the deterministic rule and costs 4.5: 14 / 18.5 → review, not a merge
        self.assertEqual(pmi.score(a, pmi.Person("JAMES", "SINGH", date(1935, 2, 25), "female", "5550000001", "62701"))[:2],
                         ("PENDING_REVIEW", 0.7568))

    def test_jaro_winkler_matches_winklers_published_values(self):
        self.assertAlmostEqual(pmi.jaro_winkler("martha", "marhta"), 0.9611, places=4)
        self.assertAlmostEqual(pmi.jaro_winkler("dwayne", "duane"), 0.84, places=2)


class FeedsTest(unittest.TestCase):

    def test_adding_feeds_never_changes_the_hl7_bytes(self):
        msgs, _, _ = run()
        self.assertEqual([m.payload for m in msgs], [m.payload for m in generate(100, 42)])

    def test_same_seed_same_feed_bytes(self):
        a = [r.payload for r in run()[2].records]
        b = [r.payload for r in run()[2].records]
        self.assertEqual(a, b)
        self.assertNotEqual(a, [r.payload for r in run(seed=43)[2].records])

    def test_sizes_per_feed(self):
        counts = {f: sum(1 for r in run()[2].records if r.feed == f) for f in feeds.FEEDS}
        self.assertEqual(counts, feeds.sizes(100))
        self.assertEqual(counts, {"fhir": 16, "claims": 20, "ccda": 10})

    def test_about_a_fifth_of_hl7_people_also_in_another_feed_with_every_variation(self):
        _, patients, fr = run()
        originals = [p for p in patients if p.twin is not None or not any(q.twin is p for q in patients)]
        self.assertAlmostEqual(len(fr.cross_feed) / len(originals), 0.20, delta=0.03)
        self.assertEqual({c.variation for c in fr.cross_feed}, set(feeds.VARIATIONS))
        self.assertEqual({c.feed for c in fr.cross_feed}, set(feeds.FEEDS))
        for c in fr.cross_feed:
            self.assertFalse(c.original.phone_changed or c.original.adt_corrupted)
            self.assertNotEqual(c.twin.pid, c.original.mrn)
            expected = {"EXACT": "AUTO_MERGED", "NICKNAME": "AUTO_MERGED", "TYPO": "PENDING_REVIEW",
                        "SWAPPED_DATE": "PENDING_REVIEW"}[c.variation]
            self.assertEqual(c.outcome, expected, c.manifest_entry())

    def test_variations_change_exactly_what_they_say(self):
        for c in run()[2].cross_feed:
            o, t = c.original, c.twin
            if c.variation == "NICKNAME":
                self.assertEqual(t.first.upper(), pmi.NICKNAMES[o.first])
            elif c.variation == "TYPO":
                self.assertNotEqual(t.last.upper(), o.last)
                self.assertEqual(sorted(t.last.upper()), sorted(o.last))
            elif c.variation == "SWAPPED_DATE":
                self.assertEqual(t.dob.strftime("%Y%d%m"), o.dob)
            else:
                self.assertEqual((t.first.upper(), t.last.upper(), t.dob.strftime("%Y%m%d")), (o.first, o.last, o.dob))

    def test_twins_are_sent_right_after_the_originals_first_adt(self):
        msgs, _, fr = run()
        order = feeds.send_order("x", len(msgs), fr.records)
        for c in fr.cross_feed:
            j = next(i for i, r in enumerate(fr.records) if r.people[0] is c.twin)
            pos = order.index(("feed", j))
            self.assertEqual(order[pos - 1], ("hl7", c.original.first_adt_index))

    def test_defects_are_listed_and_really_in_the_payload(self):
        fr = run(count=300)[2]
        self.assertAlmostEqual(len(fr.defects) / sum(1 for r in fr.records if r.after_index == 0), 0.12, delta=0.02)
        self.assertEqual({d.kind for d in fr.defects}, set(feeds.DEFECTS))
        for d in fr.defects:
            rec = next(r for r in fr.records if "%s/%s" % (r.feed, r.name) == d.file)
            self.assertIn(d.code, rec.payload)
            self.assertIn(d.feed, feeds.DEFECTS[d.kind][1])
            if d.kind == "PREGNANCY_ON_MALE":
                self.assertEqual(rec.people[0].sex, "M")

    def test_payload_shapes(self):
        for r in run()[2].records:
            if r.feed == "fhir":
                b = json.loads(r.payload)
                self.assertEqual(b["resourceType"], "Bundle")
                patient = b["entry"][0]["resource"]
                self.assertEqual(patient["identifier"][0]["type"]["coding"][0]["code"], "MR")
                self.assertEqual(patient["identifier"][0]["value"], r.people[0].pid)
                refs = {e["resource"].get("subject", e["resource"].get("patient", {})).get("reference") for e in b["entry"][1:]}
                self.assertLessEqual(refs, {b["entry"][0]["fullUrl"]})
            elif r.feed == "claims":
                lines = r.payload.strip().split("\n")
                self.assertEqual(lines[0], feeds.CLAIMS_HEADER)
                self.assertEqual({l.split(",")[1] for l in lines[1:]}, {p.pid for p in r.people})
            else:
                self.assertNotIn("DOCTYPE", r.payload)
                doc = xml.dom.minidom.parseString(r.payload.encode("utf-8"))
                self.assertEqual(doc.documentElement.tagName, "ClinicalDocument")
                self.assertIn('extension="%s"' % r.people[0].pid, r.payload)

    def test_claim_lifecycle_is_consistent_and_mixed(self):
        from datetime import date
        statuses = set()
        for i in range(400):
            cid = "CLM-%07d" % (1000000 + i)
            cols = feeds.claim_lifecycle(cid, date(2026, 11, 1), 150.0)
            self.assertEqual(cols, feeds.claim_lifecycle(cid, date(2026, 11, 1), 150.0))  # same id → same lifecycle
            payer_id, payer, status, sent, changed, allowed, paid, owes, reason = cols
            statuses.add(status)
            self.assertIn((payer_id, payer), feeds.PAYERS)
            self.assertGreaterEqual(changed, sent)
            self.assertGreater(sent, "2026-11-01")
            if status in ("PAID", "PARTIALLY_PAID"):
                self.assertLessEqual(float(paid) + float(owes), float(allowed) + 0.001)
                self.assertLessEqual(float(allowed), 150.0)
            elif status == "DENIED":
                self.assertIn(reason, feeds.DENIAL_REASONS)
                self.assertEqual(paid, "0.00")
                self.assertEqual(owes, "150.00" if reason == "PR-96" else "0.00")
            else:
                self.assertEqual((allowed, paid, owes), ("", "", ""))
        self.assertEqual(statuses, {s for s, _ in feeds.CLAIM_STATUS_MIX})

    def test_claims_lines_carry_the_lifecycle(self):
        n = 0
        for r in run()[2].records:
            if r.feed == "claims":
                for line in r.payload.strip().split("\n")[1:]:
                    cols = line.split(",")
                    self.assertEqual(len(cols), len(feeds.CLAIMS_HEADER.split(",")))
                    self.assertIn(cols[13], {s for s, _ in feeds.CLAIM_STATUS_MIX})
                    n += 1
        self.assertGreater(n, 0)

    def test_reset_namespace_gives_new_identifiers_same_shape(self):
        a = run()[2]
        b = run(namespace=2)[2]
        self.assertTrue(all(p.pid[2] == "2" for r in b.records for p in r.people))
        self.assertTrue({p.pid for r in a.records for p in r.people}.isdisjoint({p.pid for r in b.records for p in r.people}))


class CliTest(unittest.TestCase):

    def test_reset_moves_the_namespace_and_nothing_else(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self.assertEqual(cli.load_namespace(root, False), 0)
            self.assertEqual(cli.load_namespace(root, True), 1)
            self.assertEqual(cli.load_namespace(root, False), 1)
            self.assertEqual(cli.load_namespace(root, True), 2)
            self.assertEqual(cli.run_dir(root, 7, 0).name, "seed-7")
            self.assertEqual(cli.run_dir(root, 7, 2).name, "seed-7-ns2")

    def test_files_and_manifest_round_trip_in_send_order(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(cli.main(["--count", "30", "--seed", "5", "--out", d, "--no-send", "--quiet"]), 0)
            out = Path(d) / "seed-5"
            m = json.loads((out / "manifest.json").read_text())
            self.assertEqual(m["version"], 2)
            self.assertEqual(len(m["sendOrder"]), 30 + len(m["feedRecords"]))
            items = cli.items_from_dir(out)
            self.assertEqual([k for k, _, _ in items], [k for k, _ in m["sendOrder"]])
            feed_items = [i for i in items if i[0] == "feed"]
            self.assertEqual([x["n"] for _, _, x in feed_items], sorted(r["sendIndex"] for r in m["feedRecords"]))

    def test_hl7_only_run_keeps_the_9c_manifest_fields(self):
        with tempfile.TemporaryDirectory() as d:
            cli.main(["--count", "20", "--seed", "9", "--feeds", "hl7", "--out", d, "--no-send", "--quiet"])
            m = json.loads((Path(d) / "seed-9" / "manifest.json").read_text())
            self.assertEqual((m["count"], m["feedRecords"], m["crossFeedDuplicates"]), (20, [], []))
            self.assertEqual(len(list((Path(d) / "seed-9").glob("*.hl7"))), 20)

    def test_trickle_paces_and_stops_at_the_duration(self):
        slept = []
        now = [0.0]

        def fake_sleep(s):
            slept.append(s)
            now[0] += s

        sender = cli.Sender("localhost", 1, "http://localhost:1", True, "t", sleep=fake_sleep, clock=lambda: now[0])
        sender._send_feed = lambda name, info: None
        items = [("feed", "f%d" % i, {}) for i in range(10)]
        sent = sender.run(items, pace=0.5, deadline=2.0)
        self.assertEqual(sent, 4)                    # t = 0, 0.5, 1.0, 1.5; stopped at 2.0
        self.assertEqual(slept, [0.5] * 4)


class _Handler(BaseHTTPRequestHandler):
    calls = []

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        _Handler.calls.append((self.path, self.headers["X-Source-System"], body))
        if len(_Handler.calls) == 1:
            self.send_response(503)
            self.send_header("Retry-After", "0")
            self.end_headers()
            return
        self.send_response(202)
        self.send_header("X-Correlation-Id", self.headers["X-Correlation-Id"])
        self.end_headers()

    def log_message(self, *args):
        pass


class RestClientTest(unittest.TestCase):

    def test_retries_a_503_then_counts_the_202(self):
        server = HTTPServer(("localhost", 0), _Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            client = RestClient("http://localhost:%d" % server.server_port, sleep=lambda s: None)
            result = client.post("/api/ingestion/fhir", "{}", "application/json", "PARTNER_CLINIC", "gen-1")
            self.assertTrue(result.accepted)
            self.assertEqual((result.attempts, result.correlation_id), (2, "gen-1"))
            self.assertEqual(_Handler.calls[-1][:2], ("/api/ingestion/fhir", "PARTNER_CLINIC"))
        finally:
            server.shutdown()

    def test_unreachable_gives_up(self):
        client = RestClient("http://localhost:1", sleep=lambda s: None, max_attempts=2, timeout=1)
        result = client.post("/x", "a", "text/csv", "S", "c")
        self.assertFalse(result.accepted)
        self.assertEqual(result.attempts, 2)


if __name__ == "__main__":
    unittest.main()
