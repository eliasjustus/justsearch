"""Shadow evaluation runner (tempdoc 400 LR5-b).

Runs two search policies (``policy_a``, ``policy_b``) against the
same eval query set in the same order on the same running Worker
— so both policies observe an identical index + reader generation.
Emits per-query divergence signals (top-K overlap, Jaccard,
Kendall-τ on the top-K rankings) plus aggregate metric comparison
when qrels are provided.

**Selection-bias scope (hard constraint, §13.9 C3 locked 2026-04-21):**

    LR5-b operates offline only, on a fixed evaluation query set,
    with no production traffic. Worker serves both policies with the
    same index and same reader generation. Queries issued in identical
    order. No query-feature-conditional sampling. Selection bias
    reduces to "eval distribution ≠ production distribution" — an
    acknowledged assumption of the entire eval harness, not a new
    bias LR5-b introduces. Production canary extensions are explicitly
    out of scope.

The runner enforces this constraint at two levels:

1. The query order is fixed (Python dict preserves insertion order
   since 3.7). Both policies receive queries in the same order;
   neither reshuffles.
2. :func:`run_shadow` asserts ``a_qids == b_qids`` post-run, matching
   the §13.9 C3 test assertion.
"""

from __future__ import annotations

import json
import logging
import statistics
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

import httpx

from .retriever import _build_request, execute_query

log = logging.getLogger(__name__)

SCHEMA_VERSION = 1


def _top_k_ids(response: dict, k: int) -> list[str]:
    results = response.get("results") or []
    return [h.get("id") for h in results[:k] if h.get("id") is not None]


def _jaccard(a: list[str], b: list[str]) -> float:
    sa, sb = set(a), set(b)
    if not sa and not sb:
        return 1.0
    union = sa | sb
    return len(sa & sb) / len(union) if union else 0.0


def _kendall_tau_on_shared(a: list[str], b: list[str]) -> float | None:
    """Kendall-τ rank correlation over the intersection of ``a`` and ``b``.

    ``None`` when the shared set has fewer than 2 elements (tau is
    undefined for a single-element list).
    """
    shared = [x for x in a if x in set(b)]
    if len(shared) < 2:
        return None
    rank_a = {doc: i for i, doc in enumerate(a)}
    rank_b = {doc: i for i, doc in enumerate(b)}
    concordant = 0
    discordant = 0
    n = len(shared)
    for i in range(n):
        for j in range(i + 1, n):
            d_a = rank_a[shared[i]] - rank_a[shared[j]]
            d_b = rank_b[shared[i]] - rank_b[shared[j]]
            sign = d_a * d_b
            if sign > 0:
                concordant += 1
            elif sign < 0:
                discordant += 1
    denom = n * (n - 1) / 2
    return (concordant - discordant) / denom if denom > 0 else None


def run_shadow(
    queries: dict[str, str],
    *,
    policy_a: dict,
    policy_b: dict,
    base_url: str,
    policy_a_name: str = "A",
    policy_b_name: str = "B",
    top_k: int = 10,
    timeout: float = 90.0,
    max_retries: int = 5,
    allow_errors: bool = False,
    max_error_rate: float | None = None,
) -> dict:
    """Run the shadow comparison; return an LR5-b schema-v1 document.

    Both policies run sequentially against the same Worker so the
    index + reader generation are identical. Queries are issued in
    insertion order (Python dict semantics) for both policies;
    identical order is asserted post-run.

    Phase 6 / 6.9 changes:

    - **Fail-fast invariant.** The §13.9 C3 qid-sequence assertion
      used to run only post-both-policies. Now it runs twice:
      after policy A's records are collected, and after policy B's.
      A mid-policy-A reorder now aborts before B even starts — ~50%
      wall-time saved on the failure path for N-query runs.
    - **``max_error_rate``.** Optional. When the per-policy error
      fraction exceeds the threshold (e.g. 0.10 = 10%), raise
      RuntimeError with the count. Prevents grinding through N
      queries against a broken backend.
    """
    ordered_qids = list(queries.keys())
    expected_qid_snapshot = list(ordered_qids)  # frozen for invariant checks

    def _issue(policy: dict, stream_label: str) -> list[dict]:
        records: list[dict] = []
        with httpx.Client(base_url=base_url, timeout=timeout) as client:
            for qid in ordered_qids:
                qtext = queries[qid]
                body = _build_request(qtext, mode="hybrid", top_k=top_k,
                                      debug=False, pipeline=policy)
                t0 = time.perf_counter()
                response = execute_query(
                    client, qid, body, max_retries, allow_errors,
                )
                dispatch_ms = (time.perf_counter() - t0) * 1000.0
                if response is None:
                    records.append({
                        "qid": qid, "dispatch_ms": dispatch_ms, "status": "error",
                    })
                else:
                    records.append({
                        "qid": qid, "dispatch_ms": dispatch_ms, "status": "ok",
                        "took_ms": response.get("tookMs"),
                        "total_hits": response.get("totalHits"),
                        "top_k_ids": _top_k_ids(response, top_k),
                    })
        return records

    def _assert_qid_sequence(records: list[dict], policy_label: str) -> None:
        observed = [r["qid"] for r in records]
        if observed != expected_qid_snapshot:
            first_mismatch = next(
                (i for i, (x, y) in enumerate(zip(observed, expected_qid_snapshot))
                 if x != y), None,
            )
            raise RuntimeError(
                f"shadow-eval invariant violated after policy {policy_label}: "
                f"qid sequence differs from the entry snapshot (§13.9 C3). "
                f"observed_len={len(observed)} expected_len={len(expected_qid_snapshot)} "
                f"first_mismatch_index={first_mismatch}"
            )

    def _check_error_rate(records: list[dict], policy_label: str) -> None:
        if max_error_rate is None:
            return
        errors = sum(1 for r in records if r["status"] == "error")
        rate = errors / len(records) if records else 0.0
        if rate > max_error_rate:
            raise RuntimeError(
                f"shadow-eval error-rate invariant violated after policy "
                f"{policy_label}: {errors}/{len(records)} ({rate:.1%}) > "
                f"max_error_rate={max_error_rate:.1%}"
            )

    start_ts = datetime.now(timezone.utc).isoformat()
    t0 = time.perf_counter()
    records_a = _issue(policy_a, policy_a_name)
    # Phase 6 / 6.9: fail-fast invariant + error-budget guard after policy A
    # (don't grind through policy B if A already deviated from the snapshot
    # or blew the error budget).
    _assert_qid_sequence(records_a, policy_a_name)
    _check_error_rate(records_a, policy_a_name)
    records_b = _issue(policy_b, policy_b_name)
    _assert_qid_sequence(records_b, policy_b_name)
    _check_error_rate(records_b, policy_b_name)
    wall_ms = (time.perf_counter() - t0) * 1000.0
    end_ts = datetime.now(timezone.utc).isoformat()

    a_qids = [r["qid"] for r in records_a]
    b_qids = [r["qid"] for r in records_b]

    divergences: list[dict] = []
    by_qid_a = {r["qid"]: r for r in records_a}
    by_qid_b = {r["qid"]: r for r in records_b}
    identical_top_k = 0
    partial_overlap = 0
    disjoint = 0
    jaccards: list[float] = []
    kendalls: list[float] = []
    for qid in ordered_qids:
        ra, rb = by_qid_a[qid], by_qid_b[qid]
        if ra["status"] != "ok" or rb["status"] != "ok":
            divergences.append({"qid": qid, "status": "error"})
            continue
        a_ids = ra["top_k_ids"]
        b_ids = rb["top_k_ids"]
        jaccard = _jaccard(a_ids, b_ids)
        tau = _kendall_tau_on_shared(a_ids, b_ids)
        jaccards.append(jaccard)
        if tau is not None:
            kendalls.append(tau)
        if a_ids == b_ids:
            identical_top_k += 1
            status = "identical"
        elif not (set(a_ids) & set(b_ids)):
            disjoint += 1
            status = "disjoint"
        else:
            partial_overlap += 1
            status = "partial-overlap"
        divergences.append({
            "qid": qid, "status": status,
            "top_k_jaccard": jaccard,
            "kendall_tau": tau,
            "a_top_k": a_ids,
            "b_top_k": b_ids,
        })

    return {
        "schema_version": SCHEMA_VERSION,
        "policy_a_name": policy_a_name,
        "policy_b_name": policy_b_name,
        "policy_a": policy_a,
        "policy_b": policy_b,
        "start_ts": start_ts,
        "end_ts": end_ts,
        "wall_ms": wall_ms,
        "top_k": top_k,
        "query_count": len(ordered_qids),
        "summary": {
            "identical_top_k": identical_top_k,
            "partial_overlap": partial_overlap,
            "disjoint": disjoint,
            "errors": sum(1 for r in divergences if r["status"] == "error"),
            "mean_jaccard": statistics.mean(jaccards) if jaccards else None,
            "mean_kendall_tau": statistics.mean(kendalls) if kendalls else None,
        },
        "records_a": records_a,
        "records_b": records_b,
        "divergences": divergences,
    }


def write_report(result: dict, run_dir: Path) -> Path:
    """Write the shadow-eval result as ``<run_dir>/shadow-eval.json``."""
    path = run_dir / "shadow-eval.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )
    return path
