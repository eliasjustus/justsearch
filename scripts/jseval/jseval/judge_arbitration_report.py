"""Judge-arbitration decision instrument (tempdoc 643 E3).

Promotes the one-off analysis scripts written during 643's §9-4 acceptance test and
Theorization/Confidence-building passes into durable, tested tooling:

- :func:`alpha_branch_breakdown` re-derives ``KnowledgeSearchEngine.computeJudgeArbitrationAlpha``'s
  exact two-branch decision from a run's per-query ``judgeSignals`` (CE margin + leg-agreement),
  so an agent can see how often the arbitration gate actually diverges from the pre-existing
  ``baseAlpha`` behavior without re-deriving the Java logic in a scratchpad script each time.
- :func:`perf_skip_firing_rate` does the same for ``isFusionDecisiveForSkip``'s independent,
  stricter, CE-free gate.
- :func:`regression_rate` compares two runs' final per-query gold-doc rank using ``predictedDocIds``
  read **directly** from each run's ``{mode}_per_query.json`` — deliberately bypassing
  ``staged_recall_accounting._ranked_by_qid``, which is trec-preferring and therefore blind to a
  stage's true reorder effect when only the response order (not the trec file) reflects it.

All three are pure functions over already-loaded per-query record lists (or paths, for the file
readers), mirroring :mod:`jseval.judge_ceiling`'s separation of pure core from I/O.
"""

from __future__ import annotations

import json
from pathlib import Path

# Mirrors KnowledgeSearchEngine's constants (app-services/.../worker/KnowledgeSearchEngine.java).
JUDGE_ARBITRATION_MARGIN_CONFIDENT_MIN = 0.2
JUDGE_ARBITRATION_OVERLAP_MIN = 0.5
JUDGE_ARBITRATION_TOP_K = 10


def _min_max_normalize(scores: list[float]) -> list[float]:
    lo, hi = min(scores), max(scores)
    if hi - lo < 1e-12:
        return [0.5] * len(scores)
    return [(s - lo) / (hi - lo) for s in scores]


def _ce_margin(ce_scores: list[float]) -> float | None:
    """Normalized top1-top2 gap, mirroring computeJudgeArbitrationAlpha's top-2 scan."""
    if len(ce_scores) < 2:
        return None
    norm = _min_max_normalize(ce_scores)
    top1 = top2 = float("-inf")
    for v in norm:
        if v > top1:
            top1, top2 = v, top1
        elif v > top2:
            top2 = v
    return top1 - top2


def _leg_agreement_jaccard(judge_signals: list[dict]) -> float:
    """Top-K doc-id Jaccard between sparse (bm25/splade) and dense legs, or -1.0 if either is empty.

    Mirrors ``legAgreementJaccard`` — approximates the "sparse-retrieval" HitStage (a fused
    bm25+splade leg in production) as the union of bm25_rank<=K and splade_rank<=K, the finest
    signal available from ``judgeSignals``.
    """
    sparse = {
        s["docId"] for s in judge_signals
        if (s.get("bm25_rank") or 999) <= JUDGE_ARBITRATION_TOP_K
        or (s.get("splade_rank") or 999) <= JUDGE_ARBITRATION_TOP_K
    }
    dense = {
        s["docId"] for s in judge_signals
        if (s.get("dense_rank") or 999) <= JUDGE_ARBITRATION_TOP_K
    }
    if not sparse or not dense:
        return -1.0
    inter = len(sparse & dense)
    union = len(sparse | dense)
    return inter / union if union else 1.0


def alpha_branch_breakdown(
    per_query_records: list[dict],
    base_alpha: float = 0.5,
    fusion_protect_alpha: float = 0.85,
) -> dict:
    """Re-derives computeJudgeArbitrationAlpha's branch counts from archived ``judgeSignals``.

    Each record is a ``{mode}_per_query.json`` entry with a ``judgeSignals`` list (``ce_score``,
    ``bm25_rank``, ``splade_rank``, ``dense_rank`` per candidate) and a ``chunkMergeApplied`` flag.
    """
    n_total = len(per_query_records)
    n_base_alpha = 0
    n_fusion_protect = 0
    n_skipped_lt2 = 0
    n_skipped_chunk = 0

    for rec in per_query_records:
        judge_signals = rec.get("judgeSignals") or []
        chunk_active = bool(rec.get("chunkMergeApplied"))
        ce_scores = [s["ce_score"] for s in judge_signals if s.get("ce_score") is not None]

        if len(ce_scores) < 2:
            n_skipped_lt2 += 1
            n_base_alpha += 1  # n<2 short-circuit -> baseAlpha, unchanged
            continue
        if chunk_active:
            n_skipped_chunk += 1
            n_base_alpha += 1  # chunk-branch short-circuit -> baseAlpha, unchanged
            continue

        margin = _ce_margin(ce_scores)
        jaccard = _leg_agreement_jaccard(judge_signals)
        legs_agree = jaccard < 0 or jaccard >= JUDGE_ARBITRATION_OVERLAP_MIN
        ce_confident = margin is not None and margin >= JUDGE_ARBITRATION_MARGIN_CONFIDENT_MIN

        if not ce_confident and legs_agree:
            n_fusion_protect += 1
        else:
            n_base_alpha += 1

    return {
        "n_total": n_total,
        "n_base_alpha": n_base_alpha,
        "n_fusion_protect": n_fusion_protect,
        "fusion_protect_rate": (n_fusion_protect / n_total) if n_total else None,
        "n_skipped_lt2_candidates": n_skipped_lt2,
        "n_skipped_chunk_active": n_skipped_chunk,
        "base_alpha": base_alpha,
        "fusion_protect_alpha": fusion_protect_alpha,
    }


def perf_skip_firing_rate(per_query_records: list[dict]) -> dict:
    """Re-derives isFusionDecisiveForSkip's firing rate — stricter, CE-independent gate.

    Unlike the blend gate, chunk-branch or an inconclusive leg signal means "not decisive" (skip
    does NOT fire), the opposite default from the blend gate's "unknown -> don't intervene".
    """
    n_total = len(per_query_records)
    n_fires = 0
    for rec in per_query_records:
        judge_signals = rec.get("judgeSignals") or []
        if bool(rec.get("chunkMergeApplied")):
            continue
        jaccard = _leg_agreement_jaccard(judge_signals)
        if jaccard >= JUDGE_ARBITRATION_OVERLAP_MIN:
            n_fires += 1
    return {
        "n_total": n_total,
        "n_fires": n_fires,
        "firing_rate": (n_fires / n_total) if n_total else None,
    }


def _load_predicted_doc_ids(run_dir: Path, mode: str) -> dict[str, list[str]]:
    """``{qid: predictedDocIds}`` read directly from ``{mode}_per_query.json``.

    Deliberately bypasses ``staged_recall_accounting._ranked_by_qid``, which prefers the trec
    file when present — correct for membership checks, but blind to a reorder-only stage's true
    effect when the trec file doesn't reflect it (the same trec-blindness this tempdoc's own §9-4
    acceptance test hit and worked around once already, ad hoc).
    """
    path = run_dir / f"{mode}_per_query.json"
    out: dict[str, list[str]] = {}
    if not path.is_file():
        return out
    for e in json.loads(path.read_text(encoding="utf-8")):
        qid = e.get("qid")
        if qid:
            out[qid] = list(e.get("predictedDocIds") or [])
    return out


def regression_rate(run_dir_a: Path, run_dir_b: Path, mode: str = "hybrid") -> dict:
    """Per-query gold-adjacent comparison of two runs' TRUE (predictedDocIds) result order.

    Not a relevance judgment (no qrels needed) — reports how many queries' result LIST changed
    between the two runs (any reordering at all) as a coarse instability signal. Pair with qrels
    yourself for a gold-rank-specific regression count when qrels are available.
    """
    a = _load_predicted_doc_ids(run_dir_a, mode)
    b = _load_predicted_doc_ids(run_dir_b, mode)
    common = sorted(set(a) & set(b))
    n_changed = sum(1 for qid in common if a[qid] != b[qid])
    return {
        "run_dir_a": str(run_dir_a),
        "run_dir_b": str(run_dir_b),
        "mode": mode,
        "n_common_queries": len(common),
        "n_changed": n_changed,
        "n_unchanged": len(common) - n_changed,
        "changed_rate": (n_changed / len(common)) if common else None,
    }
