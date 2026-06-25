#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_FILE=""
OUTPUT_FILE="$ROOT_DIR/reports/phase8/ui/perf/frame-times.json"

usage() {
  cat <<'USAGE'
Usage: scripts/ui/frame-profiler.sh --log <pulse_log> [options]

Parse a JavaFX pulse logger trace and emit summary statistics (min/mean/p95/p99) in JSON.

Options:
  --log <path>    Pulse logger output to analyse (required).
  --out <path>    Output JSON path (default: reports/phase8/ui/perf/frame-times.json).
  -h, --help      Show this help.

Generate a pulse log by launching the UI with -Djavafx.pulseLogger=true and redirecting stdout to a file.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --log)
      LOG_FILE="$2"; shift 2;;
    --out)
      OUTPUT_FILE="$2"; shift 2;;
    -h|--help)
      usage; exit 0;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1;;
  esac
done

if [[ -z "$LOG_FILE" ]]; then
  echo "--log is required" >&2
  usage
  exit 1
fi

if [[ ! -f "$LOG_FILE" ]]; then
  echo "Pulse log not found: $LOG_FILE" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT_FILE")"

python3 - "$LOG_FILE" "$OUTPUT_FILE" <<'PYCODE'
import json
import math
import re
import statistics
import sys

log_path, out_path = sys.argv[1:3]
pattern = re.compile(r'([0-9]+(?:\.[0-9]+)?)\s*ms', re.IGNORECASE)
values = []
with open(log_path, 'r', encoding='utf-8', errors='ignore') as handle:
    for line in handle:
        if 'pulse' not in line.lower():
            continue
        match = pattern.search(line)
        if match:
            try:
                values.append(float(match.group(1)))
            except ValueError:
                continue

if not values:
    print(f"No pulse samples found in {log_path}", file=sys.stderr)
    sys.exit(1)

values.sort()

def percentile(data, pct):
    if not data:
        return math.nan
    k = (len(data) - 1) * (pct / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return data[int(k)]
    d0 = data[int(f)] * (c - k)
    d1 = data[int(c)] * (k - f)
    return d0 + d1

summary = {
    "samples": len(values),
    "min_ms": values[0],
    "max_ms": values[-1],
    "mean_ms": statistics.mean(values),
    "median_ms": statistics.median(values),
    "p90_ms": percentile(values, 90),
    "p95_ms": percentile(values, 95),
    "p99_ms": percentile(values, 99)
}

with open(out_path, 'w', encoding='utf-8') as fh:
    json.dump(summary, fh, indent=2)

print(json.dumps(summary, indent=2))
PYCODE
