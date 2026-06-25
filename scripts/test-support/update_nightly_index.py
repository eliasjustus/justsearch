#!/usr/bin/env python3
"""Maintain reports/phase10/goldens/nightly-index.json."""

from __future__ import annotations

import json
from pathlib import Path
import sys


def main() -> int:
  if len(sys.argv) != 4:
    raise SystemExit("Usage: update_nightly_index.py <index_path> <timestamp> <artifact_rel>")
  index_path = Path(sys.argv[1])
  timestamp = sys.argv[2]
  artifact_rel = sys.argv[3]

  data = {"nightly_runs": []}
  if index_path.exists():
    data = json.loads(index_path.read_text())

  runs = {run["timestamp"]: run for run in data.get("nightly_runs", [])}
  runs[timestamp] = {
      "timestamp": timestamp,
      "artifact_root": artifact_rel,
      "diff_report": f"{artifact_rel}/diff-report.html",
      "matrix_summary": f"{artifact_rel}/pr-matrix-summary.json",
  }
  data["nightly_runs"] = [runs[k] for k in sorted(runs)]

  index_path.parent.mkdir(parents=True, exist_ok=True)
  index_path.write_text(json.dumps(data, indent=2) + "\n")
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
