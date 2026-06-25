"""Pre-run calibration + readiness pass for the agent-utility eval (tempdoc 624).

The floor run committed $30 + 40 min blind: it ran on a degraded index, picked
timeout/concurrency from a guess (→ 26 % timeout), and labelled the search cohort
by hand. This module is the cheap pre-pass that *conditions* the expensive run —
the agent-eval instance of the `calibrate`/`readiness`/`preflight` seam:

1. **Readiness gate** — reuse `readiness.check_search_ready` (dense+sparse).
2. **Pin the real `config_cohort_key`** — reuse `manifest` snapshots +
   `release.config_cohort_key` (confidence pass #3 B3: `/api/debug/commit-metadata`,
   NOT `/api/status`, which carries the corpus-dependent fps).
3. **Pilot at the *target* concurrency** → `timeout ≈ 2× contended-p95`
   (confidence pass #3 B1: a *low*-concurrency pilot underestimates ~1.8×).
4. **Closed-book filter** — drop the memorizable queries (confidence pass #3 B2:
   the kept set ~doubles the C-beats-A signal).

The output is a `calibration` dict that `jseval utility-run --calibration` consumes.
"""

from __future__ import annotations

import concurrent.futures
import json
import subprocess
import tempfile
from pathlib import Path


def check_readiness(base_url: str, *, require_dense: bool = True, timeout_sec: float = 15.0):
    """Reuse `readiness.check_search_ready` — the index must be dense+sparse ready."""
    from jseval.readiness import check_search_ready
    return check_search_ready(
        base_url, dense_enabled=require_dense, splade_enabled=True, timeout_sec=timeout_sec)


def pin_config_cohort_key(base_url: str) -> tuple[str | None, dict]:
    """The *real* search backend `config_cohort_key` (B3) — reuse the manifest seam.

    Builds the config-global slice of a manifest from the live `/api/debug/commit-metadata`
    + session-policies + model snapshots and hashes it with `release.config_cohort_key`.
    """
    from jseval import manifest as mf
    from jseval import release
    from jseval.run import _snapshot_models

    snaps = mf.capture_state_snapshots(base_url)
    manifest = {
        "git_sha": mf._git_sha_full(),
        "commit_metadata": mf._normalise_commit_metadata(
            snaps.get("/api/debug/commit-metadata") or {}),
        "policy_hash": mf._sha256_canonical(snaps.get("/api/debug/session-policies") or {}),
        "model_fingerprints": _snapshot_models(base_url) or {},
        "eval_protocol_hash": mf._sha256_canonical({"agent_utility_eval": "v1"}),
    }
    return release.config_cohort_key(manifest), manifest["commit_metadata"]


def calibrate_timeout(pilot_log_dir: str, *, multiplier: float = 2.0,
                      floor_s: int = 120, ceil_s: int = 600) -> int:
    """timeout ≈ multiplier × the pilot's *contended* p95 (B1), clamped to [floor, ceil]."""
    from inspect_ai.log import read_eval_log

    times: list[float] = []
    for lf in sorted(Path(pilot_log_dir).glob("*.json")) + sorted(Path(pilot_log_dir).glob("*.eval")):
        if lf.name in ("eval-set.json", "logs.json"):
            continue
        try:
            log = read_eval_log(lf.as_posix())
        except Exception:
            continue
        for s in (log.samples or []):
            if not (s.metadata or {}).get("error") and s.total_time:
                times.append(s.total_time)
    if not times:
        return ceil_s
    s = sorted(times)
    p95 = s[min(len(s) - 1, int(len(s) * 0.95))]
    return int(max(floor_s, min(ceil_s, multiplier * p95)))


def closed_book_filter(queries: list[dict], *, model: str = "haiku",
                       concurrency: int = 8) -> tuple[list[int], int]:
    """Drop the memorizable (closed-book-correct) queries (B2).

    Returns ``(retained_indices, n_dropped)`` — the retained set is exactly the
    *retrieval-relevant* queries (closed-book-WRONG), where C-beats-A is sharpest.
    """
    from jseval.agent_retrieval_eval import _score_answer

    def _cb(i_q):
        i, q = i_q
        prompt = f"Answer this question concisely from your own knowledge. Question: {q['query']}"
        try:
            r = subprocess.run(
                ["claude", "-p", prompt, "--model", model, "--output-format", "json",
                 "--max-budget-usd", "0.10", "--permission-mode", "bypassPermissions"],
                capture_output=True, text=True, timeout=90, cwd=tempfile.mkdtemp())
            d = json.loads(r.stdout or "{}")
            return i, _score_answer(q["answer"], d.get("result", ""))
        except Exception:
            return i, False  # treat failures as retrieval-relevant (keep)

    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as ex:
        res = dict(ex.map(_cb, list(enumerate(queries))))
    retained = [i for i in range(len(queries)) if not res.get(i)]
    return retained, len(queries) - len(retained)


def calibrate(*, base_url: str, queries: list[dict], corpus_dir: str, mcp_config: str | None,
              model: str, concurrency: int, seeds: int, conditions=("A", "C"),
              require_dense: bool = True, pilot_n: int = 5, max_budget: float = 0.50,
              do_closed_book: bool = True) -> dict:
    """Orchestrate the pre-run calibration. Needs the live backend (readiness/pin/pilot)."""
    from jseval import agent_utility_inspect as aui
    from jseval import agent_utility_run as aur

    rd = check_readiness(base_url, require_dense=require_dense)
    cck, commit_meta = pin_config_cohort_key(base_url)

    # Pilot at the TARGET concurrency (a few queries, both arms) → contended p95 → timeout.
    pilot_dir = tempfile.mkdtemp(prefix="util-pilot-").replace("\\", "/")
    pq = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
    json.dump(queries[:pilot_n], pq); pq.close()
    aui.run_utility_eval(
        queries_path=pq.name, corpus_dir=corpus_dir, mcp_config=mcp_config, model=model,
        conditions=conditions, seeds=1, concurrency=concurrency, log_dir=pilot_dir,
        max_queries=pilot_n, max_budget=max_budget, cli_version=aur.claude_cli_version(),
        corpus_dataset="pilot", corpus_signature="pilot")
    timeout_s = calibrate_timeout(pilot_dir)

    retained, n_dropped = ([list(range(len(queries))), 0])
    if do_closed_book:
        retained, n_dropped = closed_book_filter(queries, model=model, concurrency=concurrency)

    # Per-cell cost estimate from the pilot → project the full matrix.
    from jseval.utility_governance import compute_loss_accounting
    pilot_arms = compute_loss_accounting(pilot_dir)
    per_cell_cost = 0.12  # fallback
    summ = aur.eval_logs_to_summaries(pilot_dir, search_config_cohort_key=cck)
    costs = [v["cost_usd"] for s in summ for v in s["per_query"].values() if v.get("cost_usd")]
    if costs:
        per_cell_cost = sum(costs) / len(costs)
    n_cells = len(retained) * len(conditions) * seeds
    return {
        "readiness_passed": rd.passed,
        "readiness_reasons": rd.failure_reasons,
        "config_cohort_key": cck,
        "timeout_s": timeout_s,
        "concurrency": concurrency,
        "retained_query_indices": retained,
        "n_dropped_contaminated": n_dropped,
        "n_cells": n_cells,
        "cost_estimate_usd": round(per_cell_cost * n_cells, 2),
        "time_estimate_min": round(n_cells * (timeout_s / 2) / max(concurrency, 1) / 60, 1),
    }
