"""Command line: write seeded files (+ manifest.json) for every feed, then send them — HL7 over MLLP, FHIR / claims /
C-CDA over REST — as one burst or, with --trickle, at a steady rate like a live feed."""
import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Callable, List, Optional, Tuple

from .feeds import CONTENT_TYPES, ENDPOINTS, FEEDS, FeedRun, generate_feeds, send_order
from .messages import generate_run
from .mllp import MllpClient
from .rest import RestClient

ALL_FEEDS = ("hl7",) + FEEDS


# ---- --reset namespace ------------------------------------------------------------------------------------

def load_namespace(out_root: Path, reset: bool) -> int:
    """out/state.json keeps the identifier namespace. --reset moves to the next one (nothing is deleted anywhere):
    the same seed then makes NEW patients, MRNs and claim ids instead of being deduplicated by Bronze."""
    state = out_root / "state.json"
    ns = 0
    if state.exists():
        try:
            ns = int(json.loads(state.read_text(encoding="utf-8")).get("namespace", 0))
        except (ValueError, OSError):
            ns = 0
    if reset:
        ns += 1
        out_root.mkdir(parents=True, exist_ok=True)
        state.write_text(json.dumps({"namespace": ns}) + "\n", encoding="utf-8")
    return ns


def run_dir(out_root: Path, seed: int, namespace: int) -> Path:
    return out_root / ("seed-%d" % seed if namespace == 0 else "seed-%d-ns%d" % (seed, namespace))


# ---- files ------------------------------------------------------------------------------------------------

def write_files(messages, out_dir: Path, seed: int, malformed_rate: float, duplicates=(),
                duplicate_rate: float = 0.0, feed_run: Optional[FeedRun] = None, order=None, namespace: int = 0,
                options: Optional[dict] = None) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob("*.hl7"):
        old.unlink()
    for m in messages:
        # newline="" keeps the \r segment terminators byte-for-byte.
        with open(out_dir / m.filename, "w", encoding="utf-8", newline="") as f:
            f.write(m.payload)
    for feed in FEEDS:
        sub = out_dir / feed
        if sub.exists():
            for old in sub.iterdir():
                old.unlink()
    records = feed_run.records if feed_run else []
    for r in records:
        (out_dir / r.feed).mkdir(exist_ok=True)
        with open(out_dir / r.feed / r.name, "w", encoding="utf-8", newline="") as f:
            f.write(r.payload)
    if order:
        for n, (kind, i) in enumerate(order, 1):
            if kind == "feed":
                records[i].send_index = n
    manifest = {
        "generator": "clinicalharmony-data-generator",
        "version": 2,
        "seed": seed,
        "namespace": namespace,
        "options": options or {},
        "count": len(messages),
        "malformedRate": malformed_rate,
        "valid": sum(1 for m in messages if m.kind == "valid"),
        "malformed": sum(1 for m in messages if m.kind == "malformed"),
        "duplicateRate": duplicate_rate,
        # 9c: the same person at CITY_HOSPITAL and NORTH_CLINIC (different MRNs), for the PMI to find.
        "duplicates": [d.manifest_entry() for d in duplicates],
        "messages": [m.manifest_entry() for m in messages],
        # 9d: FHIR / claims / C-CDA records, cross-feed duplicates (with the expected PMI outcome) and planted defects.
        "feeds": {f: sum(1 for r in records if r.feed == f) for f in FEEDS},
        "feedRecords": [r.manifest_entry() for r in records],
        "crossFeedDuplicates": [c.manifest_entry() for c in feed_run.cross_feed] if feed_run else [],
        "defects": [d.manifest_entry() for d in feed_run.defects] if feed_run else [],
        # The send order: ["hl7", message index] or ["feed", 1-based position in feedRecords].
        "sendOrder": [[k, i] if k == "hl7" else [k, i + 1] for k, i in (order or [])],
    }
    path = out_dir / "manifest.json"
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return path


# ---- sending ----------------------------------------------------------------------------------------------

class Sender:
    """Sends one ordered list of items — ("hl7", file) over MLLP and ("feed", record) over REST — counting outcomes.
    ``pace`` seconds between items (trickle); ``deadline`` stops early (--duration)."""

    def __init__(self, host: str, port: int, ingestion_url: str, quiet: bool, label: str,
                 sleep: Callable[[float], None] = time.sleep, clock: Callable[[], float] = time.time):
        self.host, self.port, self.quiet, self.label = host, port, quiet, label
        self.rest = RestClient(ingestion_url, sleep=sleep)
        self.sleep, self.clock = sleep, clock
        self.counts = {"AA": 0, "retried": 0, "failed": 0, "202": 0, "rest_failed": 0}

    def run(self, items: List[Tuple[str, str, dict]], pace: float = 0.0, deadline: Optional[float] = None,
            progress_every: int = 0) -> int:
        """items: (kind, name, payload info). Returns how many were sent before stopping."""
        sent = 0
        client = MllpClient(self.host, self.port) if any(k == "hl7" for k, _, _ in items) else None
        try:
            if client:
                client.__enter__()
            for kind, name, info in items:
                if deadline is not None and self.clock() >= deadline:
                    break
                if kind == "hl7":
                    self._send_hl7(client, name, info["payload"])
                else:
                    self._send_feed(name, info)
                sent += 1
                if progress_every and sent % progress_every == 0:
                    print("… %d / %d sent" % (sent, len(items)), flush=True)
                if pace and sent < len(items):
                    self.sleep(pace)
        except KeyboardInterrupt:
            print("Interrupted after %d / %d items." % (sent, len(items)), file=sys.stderr)
        finally:
            if client:
                client.__exit__(None, None, None)
        return sent

    def _send_hl7(self, client, name, payload):
        result = client.send(payload)
        if result.accepted:
            self.counts["AA"] += 1
            if result.attempts > 1:
                self.counts["retried"] += 1
        else:
            self.counts["failed"] += 1
            print("FAILED  %s after %d attempts: %s" % (name, result.attempts, result.error), file=sys.stderr)
        if not self.quiet:
            print("%-4s %s%s" % (result.ack_code or "--", name, "" if result.attempts == 1 else "  (attempts: %d)" % result.attempts))

    def _send_feed(self, name, info):
        result = self.rest.post(ENDPOINTS[info["feed"]], info["payload"], CONTENT_TYPES[info["feed"]],
                                info["source"], "gen-%s-%s" % (self.label, info["n"]))
        if result.accepted:
            self.counts["202"] += 1
        else:
            self.counts["rest_failed"] += 1
            print("FAILED  %s: HTTP %s after %d attempts %s" % (name, result.status, result.attempts, result.error or ""),
                  file=sys.stderr)
        if not self.quiet:
            print("%-4s %s" % (result.status or "--", name))

    def summary(self, n_hl7: int, n_feed: int, elapsed: float) -> str:
        c = self.counts
        lines = ["Sent %d files to %s:%d in %.1fs: %d ACK AA (%d after retry), %d failed"
                 % (n_hl7, self.host, self.port, elapsed, c["AA"], c["retried"], c["failed"])]
        if n_feed:
            lines.append("Posted %d feed payloads to %s: %d 202 Accepted, %d failed"
                         % (n_feed, self.rest.base_url, c["202"], c["rest_failed"]))
        return "\n".join(lines)

    @property
    def ok(self) -> bool:
        return self.counts["failed"] == 0 and self.counts["rest_failed"] == 0


def items_from_dir(directory: Path) -> List[Tuple[str, str, dict]]:
    """The run's files in its manifest's send order (v2), or every .hl7 file in name order (v1 / no manifest)."""
    manifest_path = directory / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
    if manifest.get("version", 1) >= 2 and manifest.get("sendOrder"):
        files = {m["index"]: m["file"] for m in manifest["messages"]}
        records = manifest["feedRecords"]
        items = []
        for kind, i in manifest["sendOrder"]:
            if kind == "hl7":
                items.append(("hl7", files[i], {"payload": _read(directory / files[i])}))
            else:
                r = records[i - 1]
                items.append(("feed", r["file"], {"payload": _read(directory / r["file"]), "feed": r["feed"],
                                                  "source": r["sourceSystem"], "n": r["sendIndex"]}))
        return items
    return [("hl7", p.name, {"payload": _read(p)}) for p in sorted(directory.glob("*.hl7"))]


def _read(path: Path) -> str:
    with open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()


def send_items(items, label: str, args) -> int:
    if not items:
        print("Nothing to send", file=sys.stderr)
        return 2
    sender = Sender(args.host, args.port, args.ingestion_url, args.quiet, label)
    pace = 0.0
    deadline = None
    progress = 0
    if args.trickle:
        pace = 1.0 / args.rate
        progress = 10
        if args.duration:
            deadline = time.time() + args.duration
        print("Trickle: %d items at %.2g/s (≈ %.0f s)%s" % (len(items), args.rate, len(items) / args.rate,
                                                            "" if not args.duration else ", stopping after %d s" % args.duration))
    elif args.delay_ms:
        pace = args.delay_ms / 1000.0
    started = time.time()
    sent = sender.run(items, pace, deadline, progress)
    n_hl7 = sum(1 for k, _, _ in items[:sent] if k == "hl7")
    print(sender.summary(n_hl7, sent - n_hl7, time.time() - started))
    if sent < len(items):
        print("Stopped early: %d of %d items not sent." % (len(items) - sent, len(items)))
    return 0 if sender.ok else 1


def main(argv=None) -> int:
    here = Path(__file__).resolve().parent.parent
    p = argparse.ArgumentParser(
        prog="generate.py",
        description="Seeded synthetic hospital data: HL7 v2.5 ADT / ORU / RDE over MLLP plus (9d) FHIR R4 Bundles, claims "
                    "CSV and C-CDA documents over REST — some deliberately malformed, some people in more than one feed, "
                    "some with planted clinical defects. Written to files with a manifest, then sent to ingestion-service.")
    p.add_argument("--count", type=int, default=100, help="HL7 messages to generate (default 100); the other feeds scale "
                                                           "with it: FHIR count/6, claims members count/5, C-CDA count/10")
    p.add_argument("--seed", type=int, default=42, help="same seed (and namespace) → same bytes (default 42)")
    p.add_argument("--feeds", default=",".join(ALL_FEEDS),
                   help="comma-separated: hl7, fhir, claims, ccda (default: all)")
    p.add_argument("--malformed-rate", type=float, default=0.10, help="share of HL7 deliberately malformed (default 0.10)")
    p.add_argument("--duplicate-rate", type=float, default=0.10,
                   help="share of HL7 patients also registered at the other facility, half identical (auto-merge), "
                        "half fuzzy (review queue) (default 0.10)")
    p.add_argument("--cross-feed-rate", type=float, default=0.20,
                   help="share of HL7 people also in one other feed, as EXACT / NICKNAME / TYPO / SWAPPED_DATE (default 0.20)")
    p.add_argument("--defect-rate", type=float, default=0.12,
                   help="share of feed-only records with one planted clinical defect (default 0.12)")
    p.add_argument("--reset", action="store_true",
                   help="start a new identifier namespace (out/state.json) so the same seed makes new patients; "
                        "nothing is deleted")
    p.add_argument("--trickle", action="store_true", help="send at --rate items/second, like a live feed")
    p.add_argument("--rate", type=float, default=1.0, help="items per second with --trickle (default 1)")
    p.add_argument("--duration", type=int, help="with --trickle: stop after this many seconds")
    p.add_argument("--out", type=Path, default=here / "out",
                   help="output root; files go to <out>/seed-<seed>[-ns<k>]/ (default data-generator/out)")
    p.add_argument("--host", default=os.environ.get("MLLP_HOST", "localhost"))
    p.add_argument("--port", type=int, default=int(os.environ.get("MLLP_PORT", "8887")))
    p.add_argument("--ingestion-url", default=os.environ.get("INGESTION_URL", "http://localhost:8081"),
                   help="ingestion-service REST base URL for the FHIR / claims / C-CDA feeds")
    p.add_argument("--no-send", action="store_true", help="only write the files")
    p.add_argument("--send-dir", type=Path, help="skip generation; send the files of an earlier run in its order")
    p.add_argument("--delay-ms", type=int, default=0, help="pause between items (without --trickle)")
    p.add_argument("--quiet", action="store_true", help="only print the summary")
    args = p.parse_args(argv)
    if args.rate <= 0:
        p.error("--rate must be > 0")

    if args.send_dir:
        return send_items(items_from_dir(args.send_dir), args.send_dir.name, args)

    feeds = [f.strip() for f in args.feeds.split(",") if f.strip()]
    unknown = [f for f in feeds if f not in ALL_FEEDS]
    if unknown or not feeds:
        p.error("--feeds: unknown %s (choose from %s)" % (unknown, ", ".join(ALL_FEEDS)))
    namespace = load_namespace(args.out, args.reset)
    patients: list = []
    messages, duplicates = generate_run(args.count, args.seed, args.malformed_rate, args.duplicate_rate, namespace, patients)
    # Cross-feed twins need their HL7 original in the same run.
    feed_run = generate_feeds(args.seed, args.count, patients, [f for f in feeds if f != "hl7"],
                              args.cross_feed_rate if "hl7" in feeds else 0.0, args.defect_rate, namespace)
    if "hl7" not in feeds:
        messages, duplicates = [], []
        for r in feed_run.records:
            r.after_index = 0
    order = send_order("9d-order:%d:%d" % (args.seed, namespace), len(messages), feed_run.records) if messages else \
        [("feed", j) for j in range(len(feed_run.records))]
    out_dir = run_dir(args.out, args.seed, namespace)
    options = {"feeds": feeds, "crossFeedRate": args.cross_feed_rate, "defectRate": args.defect_rate}
    manifest = write_files(messages, out_dir, args.seed, args.malformed_rate, duplicates, args.duplicate_rate, feed_run,
                           order, namespace, options)

    valid = sum(1 for m in messages if m.kind == "valid")
    if namespace:
        print("Namespace %d (--reset): new identifiers for seed %d" % (namespace, args.seed))
    print("Wrote %d HL7 messages (%d valid, %d malformed) to %s" % (len(messages), valid, len(messages) - valid, out_dir))
    if feed_run.records:
        print("Wrote %s" % ", ".join("%d %s" % (sum(1 for r in feed_run.records if r.feed == f), f)
                                     for f in FEEDS if f in feeds))
    for d in duplicates:
        print("Duplicate %-12s %s: %s %s (DOB %s) = %s %s (DOB %s)" % (
            d.kind, "%s %s" % (d.first, d.last), d.original_facility, d.original_mrn, d.original_dob,
            d.twin_facility, d.twin_mrn, d.twin_dob))
    for c in feed_run.cross_feed:
        e = c.manifest_entry()
        print("Cross-feed %-12s %s %s (DOB %s) = %s %s %s (DOB %s) → %s %.4f" % (
            c.variation, e["original"]["sourceSystem"], e["original"]["mrn"], e["original"]["dob"],
            e["twin"]["sourceSystem"], e["twin"]["id"], e["twin"]["name"], e["twin"]["dob"], c.outcome, c.score))
    for d in feed_run.defects:
        print("Defect %-22s %s %s %s (DOB %s) code %s → %s" % (
            d.kind, d.source_system, d.patient_id, d.name, d.dob, d.code, d.manifest_entry()["expectedRule"]))
    print("Manifest: %s" % manifest)
    if args.no_send:
        return 0
    return send_items(items_from_dir(out_dir), out_dir.name, args)
