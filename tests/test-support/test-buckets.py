#!/usr/bin/env python3
"""Print duration buckets and nearest-rank percentiles for recent recorded runs.

Usage: python3 tests/test-support/test-buckets.py [N]
Or: make test-buckets [N=10]. DATA may select a directory of per-run files.
Reads at most 8 MiB per file and 64 MiB total; never scans the legacy journal.
"""

import json
import math
import os
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path
from urllib.parse import unquote

MAX_FILE = 8 * 1024 * 1024
MAX_TOTAL = 64 * 1024 * 1024
BOUNDS = (1, 5, 10, 25, 50, 100, 250, 500, 1000, 5000, 10000, 30000)


def run_time(path):
    name = unquote(path.stem)
    try:
        return datetime.fromisoformat(name.split("Z", 1)[0] + "+00:00").timestamp()
    except ValueError:
        return path.stat().st_mtime


def report(directory, count):
    if not 1 <= count <= 1000:
        raise ValueError("N must be between 1 and 1000")
    if not directory.is_dir():
        raise ValueError(f"Expected a directory of per-run files: {directory}")
    files = sorted(directory.glob("*.jsonl"), key=lambda p: (run_time(p), p.name), reverse=True)[:count]
    sizes = [path.stat().st_size for path in files]
    if any(size > MAX_FILE for size in sizes) or sum(sizes) > MAX_TOTAL:
        raise ValueError("Input exceeds 8 MiB per file or 64 MiB total; select fewer/smaller runs")
    durations = Counter()
    statuses = Counter()
    partial = False
    for path, size in zip(files, sizes):
        with path.open("rb") as source:
            data = source.read(size)
        if len(data) != size:
            raise ValueError(f"Input changed while reading: {path}")
        if data and not data.endswith(b"\n"):
            partial = True
            data = data[:data.rfind(b"\n") + 1]
        for number, line in enumerate(data.splitlines(), 1):
            if not line.strip():
                continue
            record = json.loads(line)
            status = record.get("status")
            duration = record.get("durationMillis")
            if status not in ("SUCCESSFUL", "FAILED", "ABORTED", "SKIPPED"):
                raise ValueError(f"Unknown status in {path.name}:{number}")
            if type(duration) is not int or duration < 0:
                raise ValueError(f"Invalid duration in {path.name}:{number}")
            statuses[status] += 1
            if status != "SKIPPED":
                durations[duration] += 1
    total = sum(durations.values())
    print("Runs: " + (", ".join(unquote(path.stem) for path in files) or "none"))
    print(f"Executions: {total} | skipped: {statuses['SKIPPED']} | "
          f"failed: {statuses['FAILED']} | aborted: {statuses['ABORTED']}")
    print("Scope: recorded executions; whole-build completion unknown.")
    if partial:
        print("PARTIAL INPUT: unfinished final records were excluded.")
    if not total:
        print("No executed tests; percentiles unavailable.")
        return
    values = sorted(durations.items())
    percentiles = {}
    cumulative = 0
    for duration, occurrences in values:
        cumulative += occurrences
        for percentile in (50, 80, 95):
            if percentile not in percentiles and cumulative >= math.ceil(total * percentile / 100):
                percentiles[percentile] = duration
    print(" | ".join(f"p{p}: {percentiles[p]} ms" for p in (50, 80, 95)))
    print("Percentiles: nearest rank; repeated executions count separately.")
    print(f"\n{'DURATION ms':<18} {'COUNT':>9} {'SHARE %':>9} {'CUMUL %':>9} {'TOTAL ms':>12}")
    low = 0
    cumulative = 0
    for high in (*BOUNDS, None):
        selected = [(value, n) for value, n in values if value >= low and (high is None or value < high)]
        occurrences = sum(n for _, n in selected)
        elapsed = sum(value * n for value, n in selected)
        cumulative += occurrences
        label = f"[{low}, {high})" if high is not None else f"[{low}, +inf)"
        print(f"{label:<18} {occurrences:>9} {100 * occurrences / total:>9.2f} "
              f"{100 * cumulative / total:>9.2f} {elapsed:>12}")
        low = high


def main():
    if len(sys.argv) > 2:
        raise ValueError("Usage: test-buckets.py [N]")
    count = int(sys.argv[1] if len(sys.argv) == 2 else os.environ.get("ORION_TEST_ANALYSIS_N", "1"))
    default = Path(__file__).resolve().parents[2] / ".test-durations" / "runs"
    directory = Path(os.environ.get("ORION_TEST_ANALYSIS_DATA", str(default)))
    report(directory, count)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        print(f"test-buckets: {error}", file=sys.stderr)
        sys.exit(1)
