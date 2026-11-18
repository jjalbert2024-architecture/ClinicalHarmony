"""Command line: write seeded .hl7 files (+ manifest.json), then send them over MLLP."""
import argparse
import json
import os
import sys
import time
from pathlib import Path

from .messages import generate_run
from .mllp import MllpClient


def write_files(messages, out_dir: Path, seed: int, malformed_rate: float, duplicates=(),
                duplicate_rate: float = 0.0) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    for old in out_dir.glob("*.hl7"):
        old.unlink()
    for m in messages:
        # newline="" keeps the \r segment terminators byte-for-byte.
        with open(out_dir / m.filename, "w", encoding="utf-8", newline="") as f:
            f.write(m.payload)
    manifest = {
        "generator": "clinicalharmony-data-generator",
        "version": 1,
        "seed": seed,
        "count": len(messages),
        "malformedRate": malformed_rate,
        "valid": sum(1 for m in messages if m.kind == "valid"),
        "malformed": sum(1 for m in messages if m.kind == "malformed"),
        "duplicateRate": duplicate_rate,
        # 9c: the same person at CITY_HOSPITAL and NORTH_CLINIC (different MRNs), for the PMI to find.
        "duplicates": [d.manifest_entry() for d in duplicates],
        "messages": [m.manifest_entry() for m in messages],
    }
    path = out_dir / "manifest.json"
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return path


def send_dir(directory: Path, host: str, port: int, delay_ms: int, quiet: bool) -> int:
    files = sorted(directory.glob("*.hl7"))
    if not files:
        print("No .hl7 files in %s" % directory, file=sys.stderr)
        return 2
    counts = {"AA": 0, "retried": 0, "failed": 0}
    started = time.time()
    with MllpClient(host, port) as client:
        for path in files:
            with open(path, "r", encoding="utf-8", newline="") as f:
                payload = f.read()
            result = client.send(payload)
            if result.accepted:
                counts["AA"] += 1
                if result.attempts > 1:
                    counts["retried"] += 1
            else:
                counts["failed"] += 1
                print("FAILED  %s after %d attempts: %s" % (path.name, result.attempts, result.error), file=sys.stderr)
            if not quiet:
                print("%-4s %s%s" % (result.ack_code or "--", path.name,
                                     "" if result.attempts == 1 else "  (attempts: %d)" % result.attempts))
            if delay_ms:
                time.sleep(delay_ms / 1000.0)
    elapsed = time.time() - started
    print("Sent %d files to %s:%d in %.1fs: %d ACK AA (%d after retry), %d failed"
          % (len(files), host, port, elapsed, counts["AA"], counts["retried"], counts["failed"]))
    return 0 if counts["failed"] == 0 else 1


def main(argv=None) -> int:
    here = Path(__file__).resolve().parent.parent
    p = argparse.ArgumentParser(
        prog="generate.py",
        description="Seeded synthetic HL7 v2.5 ADT / ORU / RDE messages (some deliberately malformed, some patients "
                    "registered at both facilities), "
                    "written to .hl7 files and sent over MLLP to ingestion-service.")
    p.add_argument("--count", type=int, default=100, help="messages to generate (default 100)")
    p.add_argument("--seed", type=int, default=42, help="same seed → same bytes (default 42)")
    p.add_argument("--malformed-rate", type=float, default=0.10, help="share deliberately malformed (default 0.10)")
    p.add_argument("--duplicate-rate", type=float, default=0.10,
                   help="share of patients also registered at the other facility, half identical (auto-merge), "
                        "half fuzzy (review queue) (default 0.10)")
    p.add_argument("--out", type=Path, default=here / "out",
                   help="output root; files go to <out>/seed-<seed>/ (default data-generator/out)")
    p.add_argument("--host", default=os.environ.get("MLLP_HOST", "localhost"))
    p.add_argument("--port", type=int, default=int(os.environ.get("MLLP_PORT", "8887")))
    p.add_argument("--no-send", action="store_true", help="only write the files")
    p.add_argument("--send-dir", type=Path, help="skip generation; send the .hl7 files already in this directory")
    p.add_argument("--delay-ms", type=int, default=0, help="pause between messages")
    p.add_argument("--quiet", action="store_true", help="only print the summary")
    args = p.parse_args(argv)

    if args.send_dir:
        return send_dir(args.send_dir, args.host, args.port, args.delay_ms, args.quiet)

    messages, duplicates = generate_run(args.count, args.seed, args.malformed_rate, args.duplicate_rate)
    out_dir = args.out / ("seed-%d" % args.seed)
    manifest = write_files(messages, out_dir, args.seed, args.malformed_rate, duplicates, args.duplicate_rate)
    valid = sum(1 for m in messages if m.kind == "valid")
    print("Wrote %d messages (%d valid, %d malformed) to %s" % (len(messages), valid, len(messages) - valid, out_dir))
    for d in duplicates:
        print("Duplicate %-9s %s: %s %s (DOB %s) = %s %s (DOB %s)" % (
            d.kind, "%s %s" % (d.first, d.last), d.original_facility, d.original_mrn, d.original_dob,
            d.twin_facility, d.twin_mrn, d.twin_dob))
    print("Manifest: %s" % manifest)
    if args.no_send:
        return 0
    return send_dir(out_dir, args.host, args.port, args.delay_ms, args.quiet)
