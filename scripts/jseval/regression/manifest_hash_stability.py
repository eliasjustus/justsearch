"""Cohort-hash stability regression test (tempdoc 400 §24, Phase 2.0).

Runs 3 identical ``--clean --pipeline`` scifact smokes and asserts that
the resulting ``manifest_hash`` values are identical across all three
runs. This is the exact failure signature Q1 surfaced — three runs
produced three distinct hashes because the manifest included runtime
state (uptime, queue depths, commit UUIDs, captured_at timestamps) in
the cohort-identity hash.

Runtime: ~12-15 minutes (3 × ~4-5 min per smoke with N=5 max-queries).

Usage:

    cd scripts/jseval
    python -m regression.manifest_hash_stability

Not part of the PR-gate test suite because of the cost. Run manually
after changes to:

- ``scripts/jseval/jseval/manifest.py`` — any change to what enters the
  cohort hash
- ``/api/status``, ``/api/debug/state``, ``/api/debug/commit-metadata``,
  ``/api/debug/session-policies``, ``/api/inference/status``,
  ``/api/telemetry/health`` — any change to endpoint response shape that
  could leak new runtime-state fields into the manifest
- ``modules/worker-services/.../CommitMetadataSpanAttrs.java::KEYS``  —
  any change to the 8 identity fields that the Python manifest mirrors

Exit codes:

- 0 — all three runs produced the same manifest_hash
- 1 — hashes diverged (prints diffing field summary)
- 2 — a smoke run itself failed
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
JSEVAL_DIR = REPO_ROOT / "scripts" / "jseval"
RESULTS_DIR = JSEVAL_DIR / "tmp" / "eval-results"
N_RUNS = 3
MAX_QUERIES = 5


def _run_smoke(i: int) -> Path:
    """Run a single jseval smoke and return the result directory."""
    env = dict(os.environ)
    env["JUSTSEARCH_INDEX_TRACING_LEVEL"] = "detailed"
    print(f"=== Run {i}/{N_RUNS} starting ===", flush=True)
    proc = subprocess.run(
        [
            sys.executable, "-m", "jseval", "run",
            "--dataset", "scifact",
            "--modes", "full",
            "--max-queries", str(MAX_QUERIES),
            "--start-backend",
            "--clean",
            "--pipeline",
        ],
        cwd=str(JSEVAL_DIR),
        env=env,
        check=False,
    )
    if proc.returncode != 0:
        print(f"Run {i} failed with exit {proc.returncode}", file=sys.stderr)
        sys.exit(2)
    # Latest scifact run dir is the one we just created.
    candidates = sorted(
        (p for p in RESULTS_DIR.iterdir() if p.is_dir() and "scifact" in p.name),
        key=lambda p: p.stat().st_mtime,
    )
    if not candidates:
        print(f"Run {i} produced no results dir", file=sys.stderr)
        sys.exit(2)
    return candidates[-1]


def _load_hash(run_dir: Path) -> tuple[str, dict]:
    """Load manifest_hash and full manifest from a run dir."""
    manifest_path = run_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    return manifest["manifest_hash"], manifest


def _diff_cohort_inputs(manifests: list[dict]) -> list[str]:
    """Return human-readable lines describing which cohort-identity
    fields differ across the manifests (diagnostic output on failure)."""
    from jseval.manifest import _VOLATILE_FIELDS

    keys = set()
    for m in manifests:
        keys.update(m.keys())
    keys -= _VOLATILE_FIELDS

    lines = []
    for key in sorted(keys):
        vals = [m.get(key) for m in manifests]
        canon = [json.dumps(v, sort_keys=True) for v in vals]
        if len(set(canon)) > 1:
            lines.append(f"  {key}:")
            for i, v in enumerate(canon, 1):
                v_short = v if len(v) < 120 else v[:117] + "..."
                lines.append(f"    run {i}: {v_short}")
    return lines


def main() -> int:
    hashes: list[str] = []
    manifests: list[dict] = []
    for i in range(1, N_RUNS + 1):
        run_dir = _run_smoke(i)
        h, m = _load_hash(run_dir)
        hashes.append(h)
        manifests.append(m)
        print(f"Run {i}: manifest_hash={h[:24]}", flush=True)

    if len(set(hashes)) == 1:
        print(f"\nPASS — all {N_RUNS} runs produced identical manifest_hash.")
        return 0

    print(f"\nFAIL — cohort hash is not stable across {N_RUNS} identical runs.")
    print("Distinct hashes observed:")
    for i, h in enumerate(hashes, 1):
        print(f"  run {i}: {h}")
    diff_lines = _diff_cohort_inputs(manifests)
    if diff_lines:
        print("\nCohort-identity fields that differ (excluding _VOLATILE_FIELDS):")
        for line in diff_lines:
            print(line)
    return 1


if __name__ == "__main__":
    sys.exit(main())
