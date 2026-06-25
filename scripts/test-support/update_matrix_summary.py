#!/usr/bin/env python3
"""Update reports/phase10/goldens/pr-matrix-summary.json."""

from __future__ import annotations

import json
from pathlib import Path
import sys


def main() -> int:
  if len(sys.argv) != 5:
    raise SystemExit(
        "Usage: update_matrix_summary.py <summary_path> <mode> <budget_profile> <artifact_rel>")
  summary_path = Path(sys.argv[1])
  mode = sys.argv[2]
  budget_profile = sys.argv[3]
  artifact_rel = sys.argv[4]

  data = {"runs": []}
  if summary_path.exists():
    data = json.loads(summary_path.read_text())

  runs = {(run["mode"], run["budget_profile"]): run for run in data.get("runs", [])}
  runs[(mode, budget_profile)] = {
      "mode": mode,
      "budget_profile": budget_profile,
      "status": "passed",
      "artifact": artifact_rel,
  }
  data["runs"] = sorted(runs.values(), key=lambda r: (r["mode"], r["budget_profile"]))

  summary_path.parent.mkdir(parents=True, exist_ok=True)
  summary_path.write_text(json.dumps(data, indent=2) + "\n")
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
