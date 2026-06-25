"""Multi-run comparison with statistical tests and per-query diagnostics."""

from __future__ import annotations

import json
import logging
from pathlib import Path

import numpy as np
from scipy import stats

log = logging.getLogger(__name__)

DEFAULT_METRICS = ["nDCG@10", "AP@10", "RR@10", "R@10", "P@1"]


def compare(
    run_a: dict,
    run_b: dict,
    qrels: dict[str, dict[str, int]],
    metrics: list[str] | None = None,
) -> dict[str, dict]:
    """Compare two runs with paired t-test, Cohen's d_z, and bootstrap CI.

    Args:
        run_a, run_b: dicts with 'per_query_metrics' key
            ({qid: {metric: value}})
        qrels: {qid: {did: rel}} — used to determine shared query set
        metrics: metric names to compare

    Returns:
        {metric_name: {mean_a, mean_b, delta, p_value, cohens_d_z, ci_95}}
    """
    metrics = metrics or DEFAULT_METRICS
    query_ids = sorted(qrels.keys())
    results = {}

    for metric in metrics:
        a_values = np.array([
            run_a.get("per_query_metrics", {}).get(qid, {}).get(metric, 0.0)
            for qid in query_ids
        ])
        b_values = np.array([
            run_b.get("per_query_metrics", {}).get(qid, {}).get(metric, 0.0)
            for qid in query_ids
        ])
        deltas = b_values - a_values

        # Paired t-test
        if len(query_ids) >= 2 and np.std(deltas) > 0:
            t_stat, p_value = stats.ttest_rel(a_values, b_values)
        else:
            t_stat, p_value = 0.0, 1.0

        # Cohen's d_z (paired effect size)
        std_delta = float(np.std(deltas, ddof=1))
        d_z = float(np.mean(deltas)) / std_delta if std_delta > 0 else 0.0

        # Bootstrap 95% CI
        ci_low, ci_high = _bootstrap_ci(deltas)

        results[metric] = {
            "mean_a": float(np.mean(a_values)),
            "mean_b": float(np.mean(b_values)),
            "delta": float(np.mean(deltas)),
            "p_value": float(p_value),
            "t_stat": float(t_stat),
            "cohens_d_z": round(d_z, 4),
            "ci_95": (round(ci_low, 6), round(ci_high, 6)),
        }

    return results


def per_query_diff(
    run_a_per_query: list[dict],
    run_b_per_query: list[dict],
    qrels: dict[str, dict[str, int]],
    metric: str = "ndcgAtK",
    threshold: float = 0.01,
) -> list[dict]:
    """Per-query regression diagnostics.

    Returns queries where the metric degraded beyond threshold, sorted by
    delta (worst regressions first). Each entry shows rank changes for
    relevant documents.
    """
    a_by_qid = {e["qid"]: e for e in run_a_per_query}
    b_by_qid = {e["qid"]: e for e in run_b_per_query}

    diffs = []
    for qid in sorted(set(a_by_qid) & set(b_by_qid)):
        a_entry = a_by_qid[qid]
        b_entry = b_by_qid[qid]

        val_a = a_entry.get(metric, 0) or 0
        val_b = b_entry.get(metric, 0) or 0
        delta = val_b - val_a

        if delta >= -threshold:
            continue  # Not a regression

        # Compute rank changes for relevant docs
        a_docs = a_entry.get("predictedDocIds", [])
        b_docs = b_entry.get("predictedDocIds", [])
        relevant = qrels.get(qid, {})

        rank_changes = []
        all_docs = set(a_docs) | set(b_docs)
        for doc_id in all_docs:
            rel = relevant.get(doc_id, 0)
            rank_a = a_docs.index(doc_id) + 1 if doc_id in a_docs else None
            rank_b = b_docs.index(doc_id) + 1 if doc_id in b_docs else None

            if rank_a != rank_b:
                rank_changes.append({
                    "doc_id": doc_id,
                    "rank_a": rank_a,
                    "rank_b": rank_b,
                    "relevance": rel,
                })

        # Sort rank changes: relevant docs first, then by abs rank delta
        rank_changes.sort(
            key=lambda rc: (-rc["relevance"], abs((rc["rank_b"] or 999) - (rc["rank_a"] or 999))),
            reverse=True,
        )

        diffs.append({
            "qid": qid,
            "metric": metric,
            "value_a": val_a,
            "value_b": val_b,
            "delta": round(delta, 6),
            "rank_changes": rank_changes,
        })

    # Sort by delta (worst regression first)
    diffs.sort(key=lambda d: d["delta"])
    return diffs


def load_per_query(path: Path) -> list[dict]:
    """Load a per-query JSON file."""
    return json.loads(path.read_text(encoding="utf-8"))


def compare_pipeline_timing(
    summary_a: dict,
    summary_b: dict,
    regression_threshold: float = 1.10,
) -> dict:
    """Compare pipeline_timing fields between two summaries.

    Returns a dict of {field: {a, b, delta, ratio, regressed}} for each
    numeric field found in either summary's pipeline_timing.
    """
    pt_a = summary_a.get("pipeline_timing") or {}
    pt_b = summary_b.get("pipeline_timing") or {}
    if not pt_a and not pt_b:
        return {}

    result: dict = {}

    # Compare top-level numeric fields.
    _compare_field(result, "total_elapsed_s", pt_a, pt_b, regression_threshold)

    # Compare primary_indexing sub-fields.
    pi_a = pt_a.get("primary_indexing") or {}
    pi_b = pt_b.get("primary_indexing") or {}
    for key in ("duration_s", "docs_per_s", "docs_indexed"):
        _compare_field(result, f"indexing_{key}", pi_a, pi_b,
                        regression_threshold,
                        lower_is_better=(key != "docs_per_s"))

    # Compare stage completion times.
    stages_a = pt_a.get("stages") or {}
    stages_b = pt_b.get("stages") or {}
    for key in sorted(set(stages_a) | set(stages_b)):
        _compare_field(result, key, stages_a, stages_b, regression_threshold)

    return result


def _compare_field(
    result: dict,
    key: str,
    dict_a: dict,
    dict_b: dict,
    threshold: float,
    lower_is_better: bool = True,
) -> None:
    a_val = dict_a.get(key)
    b_val = dict_b.get(key)
    if a_val is None and b_val is None:
        return
    a_val = a_val or 0
    b_val = b_val or 0
    delta = b_val - a_val
    ratio = b_val / a_val if a_val != 0 else None
    if lower_is_better:
        regressed = ratio is not None and ratio > threshold
    else:
        regressed = ratio is not None and ratio < (1 / threshold)
    result[key] = {
        "a": a_val,
        "b": b_val,
        "delta": round(delta, 1),
        "ratio": round(ratio, 4) if ratio is not None else None,
        "regressed": regressed,
    }


def _bootstrap_ci(
    deltas: np.ndarray,
    n_resamples: int = 10_000,
    alpha: float = 0.05,
) -> tuple[float, float]:
    """Bootstrap 95% confidence interval for the mean of deltas."""
    if len(deltas) < 2:
        mean = float(np.mean(deltas)) if len(deltas) > 0 else 0.0
        return mean, mean

    rng = np.random.default_rng(42)  # deterministic for reproducibility
    boot_means = np.array([
        np.mean(rng.choice(deltas, size=len(deltas), replace=True))
        for _ in range(n_resamples)
    ])
    return (
        float(np.percentile(boot_means, 100 * alpha / 2)),
        float(np.percentile(boot_means, 100 * (1 - alpha / 2))),
    )


def mcnemar(
    run_a_correct: dict[str, bool],
    run_b_correct: dict[str, bool],
    *,
    exact_max_discordant: int = 25,
) -> dict:
    """McNemar's paired test for binary outcomes (tempdoc 624 C-3 / R3).

    The apt paired test for a with-vs-without-tool *accuracy* delta: it keys on
    the *discordant* observations (one arm right, the other wrong) over the
    shared observation set, which is where a paired binary comparison has power.
    Uses the exact two-sided binomial when discordant pairs are few (the small-n
    regime an agent eval usually sits in), else the continuity-corrected
    chi-square. Complements :func:`compare`, which gives the paired bootstrap CI
    for the *continuous* cost / token deltas.

    ``run_a`` is the baseline arm (condition A), ``run_b`` the with-tool arm
    (condition C); the reported ``accuracy_delta`` is ``b - a`` (positive = the
    tool helps).

    :param run_a_correct, run_b_correct: ``{observation_id: bool}`` correctness.
    :returns: discordant counts, statistic, p_value, per-arm accuracy, delta, and
        which test was used.
    """
    obs = sorted(set(run_a_correct) & set(run_b_correct))
    n = len(obs)
    n_b_only = sum(1 for o in obs if run_b_correct[o] and not run_a_correct[o])
    n_a_only = sum(1 for o in obs if run_a_correct[o] and not run_b_correct[o])
    discordant = n_a_only + n_b_only
    acc_a = sum(1 for o in obs if run_a_correct[o]) / n if n else 0.0
    acc_b = sum(1 for o in obs if run_b_correct[o]) / n if n else 0.0

    if discordant == 0:
        statistic, p_value, used = 0.0, 1.0, "none (no discordant pairs)"
    elif discordant <= exact_max_discordant:
        k = min(n_a_only, n_b_only)
        p_value = float(min(1.0, 2.0 * stats.binom.cdf(k, discordant, 0.5)))
        statistic = float(k)
        used = "exact-binomial"
    else:
        statistic = (abs(n_a_only - n_b_only) - 1) ** 2 / discordant
        p_value = float(stats.chi2.sf(statistic, 1))
        used = "chi2-continuity"

    return {
        "n_paired": n,
        "n_b_only_correct": n_b_only,   # the tool FIXES these (a wrong, b right)
        "n_a_only_correct": n_a_only,   # the tool BREAKS these (a right, b wrong)
        "n_discordant": discordant,
        "accuracy_a": round(acc_a, 4),
        "accuracy_b": round(acc_b, 4),
        "accuracy_delta": round(acc_b - acc_a, 4),
        "statistic": round(statistic, 4),
        "p_value": round(p_value, 6),
        "test": used,
    }
