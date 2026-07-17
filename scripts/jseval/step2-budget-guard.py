#!/usr/bin/env python
"""Running budget guard for chain-step2.bat (per-dataset, with extrapolation).

Called after EACH dataset's `jseval utility-calibrate`, BEFORE that dataset's
utility-run. Projects the whole-campaign spend from the calibrations known so far
and aborts (exit 1) if it would exceed the HARD CAP -- so no utility-run launches
once the projection crosses the cap.

Projection (owner-ratified):
    projected_total = sum(known cost_estimate_usd)
                      + max(known cost_estimate_usd) * (datasets_without_an_estimate)
where datasets_without_an_estimate = --total minus the number of calibrations found.
The max-based extrapolation is conservative: an un-calibrated dataset is charged the
most-expensive known dataset's estimate.

A missing/malformed calibration.json is fatal (a silently-skipped cost is exactly how
a budget guard lies). cost_estimate_usd is an ESTIMATE; utility-run's own per-cell
--max-budget is the independent hard per-cell ceiling.
"""
from __future__ import annotations

import argparse
import glob
import json
import sys
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--glob", required=True, help="Glob for known calibration.json files.")
    ap.add_argument("--cap", type=float, required=True, help="Hard USD cap.")
    ap.add_argument("--total", type=int, required=True, help="Total datasets in the campaign.")
    a = ap.parse_args()

    paths = sorted(glob.glob(a.glob))
    known: list[float] = []
    for p in paths:
        try:
            doc = json.loads(Path(p).read_text(encoding="utf-8"))
            known.append(float(doc["cost_estimate_usd"]))
        except Exception as exc:  # noqa: BLE001 - fail-closed
            print(f"ABORT: cannot read cost_estimate_usd from {p}: {exc}", file=sys.stderr)
            return 1
        print(f"  {p}: cost_estimate_usd={known[-1]:.2f}")

    if not known:
        print("ABORT: budget guard called with zero known calibrations.", file=sys.stderr)
        return 1

    n_missing = max(0, a.total - len(known))
    extrapolated = max(known) * n_missing
    projected = sum(known) + extrapolated
    print(f"known={len(known)}/{a.total}  sum(known)=${sum(known):.2f}  "
          f"+ max(known)${max(known):.2f} x {n_missing} missing = ${extrapolated:.2f}")
    print(f"PROJECTED TOTAL = ${projected:.2f}  (cap ${a.cap:.2f})")
    if projected > a.cap:
        print(f"ABORT: projected total ${projected:.2f} exceeds hard cap ${a.cap:.2f} "
              f"-- refusing to launch this or any further utility-run.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
