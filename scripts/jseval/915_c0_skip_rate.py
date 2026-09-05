"""Tempdoc 915 C5b / tempdoc 931 3a: per-corpus dense-skip rate for lane D PR-C0.

PR-C0 replaced the English stop-word gate on the dense leg with a planner-owned,
content-field document-frequency signal and types the skip on the search trace
(``SKIPPED_SHORT_QUERY`` / ``SKIPPED_NO_DISCRIMINATIVE_TERM``). C5b's merge
prerequisite is *comparable per-language skip rates* across the six pre-registered
corpora. This script is the auditable roll-up: it reads each run's
``hybrid_per_query.json`` (``denseStatus`` / ``denseReason``, persisted by
``jseval.artifacts`` since tempdoc 931) plus ``summary.json`` and prints one row per
corpus. It never runs a backend; feed it run directories.

Usage::

    python 915_c0_skip_rate.py <run_dir> [<run_dir> ...] [--json out.json]

A run directory is a ``<timestamp>_<dataset>`` directory written by ``jseval run``.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

SKIP_REASONS = ("SKIPPED_SHORT_QUERY", "SKIPPED_NO_DISCRIMINATIVE_TERM")


def summarize_run(run_dir: Path, mode: str = "hybrid") -> dict:
    """Roll one run directory up into a per-corpus skip-rate row."""
    per_query_path = run_dir / f"{mode}_per_query.json"
    summary_path = run_dir / "summary.json"
    if not per_query_path.exists():
        raise FileNotFoundError(f"{per_query_path} not found")
    entries = json.loads(per_query_path.read_text(encoding="utf-8"))
    summary = json.loads(summary_path.read_text(encoding="utf-8")) if summary_path.exists() else {}

    statuses: Counter[str] = Counter()
    reasons: Counter[str] = Counter()
    unreported = 0
    for e in entries:
        status = e.get("denseStatus")
        if status is None:
            unreported += 1
            continue
        statuses[status] += 1
        if status != "executed":
            reasons[e.get("denseReason") or "UNKNOWN"] += 1

    n = len(entries)
    reported = n - unreported
    planner_skips = sum(reasons[r] for r in SKIP_REASONS)
    per_mode = ((summary.get("per_mode") or {}).get(mode) or {})
    agg = per_mode.get("aggregate_metrics") or {}
    return {
        "run_dir": str(run_dir),
        "dataset": summary.get("dataset") or run_dir.name.split("_", 1)[-1],
        "git_sha": summary.get("git_sha"),
        "mode": mode,
        "queries": n,
        "dense_reported": reported,
        "dense_unreported": unreported,
        "dense_executed": statuses.get("executed", 0),
        "planner_skips": planner_skips,
        "planner_skip_rate": (planner_skips / reported) if reported else None,
        "skip_reasons": dict(reasons),
        "ndcg10": agg.get("nDCG@10"),
        "r10": agg.get("R@10"),
        "comparable": per_mode.get("comparable"),
    }


def render(rows: list[dict]) -> str:
    head = "| corpus | queries | dense reported | executed | planner skips | skip rate | by reason | nDCG@10 | R@10 | comparable |"
    sep = "|---|---:|---:|---:|---:|---:|---|---:|---:|---|"
    out = [head, sep]
    for r in rows:
        rate = "—" if r["planner_skip_rate"] is None else f"{r['planner_skip_rate']:.3f}"
        nd = "—" if r["ndcg10"] is None else f"{r['ndcg10']:.4f}"
        rc = "—" if r["r10"] is None else f"{r['r10']:.3f}"
        by = ", ".join(f"{k}={v}" for k, v in sorted(r["skip_reasons"].items())) or "—"
        out.append(
            f"| {r['dataset']} | {r['queries']} | {r['dense_reported']} | {r['dense_executed']} | "
            f"{r['planner_skips']} | {rate} | {by} | {nd} | {rc} | {r['comparable']} |"
        )
    unreported = [r for r in rows if r["dense_unreported"]]
    if unreported:
        out.append("")
        out.append(
            "WARNING: some queries carry no denseStatus (run predates the tempdoc 931 field or "
            "the response had no trace): "
            + ", ".join(f"{r['dataset']}={r['dense_unreported']}" for r in unreported)
        )
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("run_dirs", nargs="+", type=Path)
    p.add_argument("--mode", default="hybrid")
    p.add_argument("--json", type=Path, default=None, help="also write the rows as JSON")
    a = p.parse_args(argv)
    rows = [summarize_run(d, a.mode) for d in a.run_dirs]
    print(render(rows))
    if a.json:
        a.json.write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
