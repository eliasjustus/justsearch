"""Cross-corpus recall-attribution profile (tempdoc 636) — a *profile, not a number*.

Aggregates per-corpus ``staged_recall_accounting`` projections into one regime-blind
failure-attribution snapshot (the instrument's D-3 stated output): *which* recall-funnel
bucket dominates across the engine's eval set, with a candidate next-lever recommendation.

Pure over already-produced projection dicts (so it is unit-testable with no eval run), and
conforms to :func:`suite_profile.build_profile`'s ``*-profile.v1`` snapshot shape (schema +
members + aggregate + optional git-sha / date stamp). The CLI (`jseval recall-profile`)
re-``produce``s the latest run per dataset from disk (pure, no backend) and feeds it here.
"""

from __future__ import annotations

from typing import Any

from .projections.staged_recall_accounting import FP_MAPPING

# Dominant *failure* bucket → candidate lever. A **candidate, not a verdict**: the mapping is
# a judgment (leg-miss could be encoder / SPLADE / extraction), so the profile names the
# regime and the engineer picks the lever — it does not prescribe a fix.
#
# Tempdoc 643 correction (2026-07-01): the FP labels were backwards (FP2 "Missed the Top Ranked"
# is verbatim "didn't rank highly enough to be RETURNED" — that's CASCADE_LEAK, not
# JUDGE_RANK_LOW; JUDGE_RANK_LOW has no canonical FP match). Also corrected JUDGE_RANK_LOW's
# recommendation: the 643 investigation found "a sharper reranker" / "a judge-guided recall loop"
# are, respectively, measurement-rejected (F-006: CE model swaps move nDCG ~0 when retrieval is
# strong) or harmful/mis-targeted (F-002/F-008: CE hurts email; judge-guided recall targets
# LEG_MISS/CASCADE_LEAK recall, not an already-in-window rank). The surviving lever is a
# confidence-bounded judge floor (gated on real-corpus headroom) — see tempdoc 643.
_RECOMMENDATION = {
    "LEG_MISS": "leg-recall bound (FP1) — the doc never enters the pool. Candidate levers: component/"
                "representation quality (encoder / SPLADE / extraction, register F-009) — NOT a fusion or judge fix.",
    "CASCADE_LEAK": "cascade-leak bound (FP2) — a leg finds it but a pre-judge stage drops it before the "
                    "returned cutoff. Candidate lever: the recall-complete rerank pool (v3, shipped) / widen "
                    "the rerank window.",
    "JUDGE_RANK_LOW": "judge-rank bound (no canonical FP match — finer than FP2: reaches the RETURNED list "
                      "but ranks > 1) — NOT a sharper/LLM judge (tempdoc 643: dead/harmful on the corpora "
                      "where this bucket dominates). Candidate lever: a relative-confidence-gated judge "
                      "floor (blend toward fusion when the judge is uncertain), gated on a real-corpus "
                      "headroom probe (§5 judge-ceiling) — see tempdoc 643.",
}


def recommend(dominant_bucket: str | None) -> str:
    """Candidate next-lever for the dominant failure bucket (candidate, not a verdict)."""
    return _RECOMMENDATION.get(
        dominant_bucket or "", "no dominant failure bucket (mostly OK_RANK1) — capability is strong on this set.")


def _failure_shares(agg: dict) -> dict:
    """The three *failure* bucket shares from a projection aggregate (OK excluded)."""
    return {
        "LEG_MISS": agg.get("leg_miss_rate"),
        "CASCADE_LEAK": agg.get("leak_rate"),
        "JUDGE_RANK_LOW": agg.get("judge_low_rate"),
    }


def _dominant(shares: dict) -> Any:
    present = {k: v for k, v in shares.items() if isinstance(v, (int, float))}
    return max(present, key=present.get) if present else None


def build_recall_profile(
    projections_by_dataset: dict,
    *,
    engine_git_sha: str | None = None,
    generated_date: str | None = None,
) -> dict:
    """Aggregate per-corpus ``staged_recall_accounting`` projections → ``recall-profile.v1``.

    :param projections_by_dataset: ``{dataset_slug: parsed staged_recall_accounting.json}``.
    Only ``status == "ok"`` projections contribute to the cross-corpus aggregate.
    """
    members = []
    ok_failure_shares: list[dict] = []
    for dataset, proj in (projections_by_dataset or {}).items():
        agg = (proj or {}).get("aggregate") or {}
        shares = _failure_shares(agg)
        recon = (proj or {}).get("reconciliation") or {}
        row = {
            "dataset": dataset,
            "status": (proj or {}).get("status"),
            "n_queries_judged": (proj or {}).get("n_queries_judged"),
            "failure_shares": shares,
            "ok_rate": agg.get("ok_rate"),
            "leg_union_recall": agg.get("leg_union_recall"),
            "judge_headroom_ceiling": agg.get("judge_headroom_ceiling"),
            "per_leg_recall": (proj or {}).get("per_leg_recall"),
            "reconciliation_mismatches": recon.get("mismatches"),
        }
        if (proj or {}).get("status") == "ok":
            row["dominant_failure"] = _dominant(shares)
            ok_failure_shares.append(shares)
        members.append(row)

    # Cross-corpus aggregate: mean of each failure share across the ok corpora.
    mean_shares = {}
    for b in ("LEG_MISS", "CASCADE_LEAK", "JUDGE_RANK_LOW"):
        vals = [s[b] for s in ok_failure_shares if isinstance(s.get(b), (int, float))]
        mean_shares[b] = (sum(vals) / len(vals)) if vals else None
    dominant = _dominant(mean_shares)

    out: dict = {
        "schema": "recall-profile.v1",
        "n_members": len(members),
        "n_ok": len(ok_failure_shares),
        "members": members,
        "aggregate": {
            "mean_failure_shares": mean_shares,
            "dominant_bucket": dominant,
            "fp_mapping": FP_MAPPING,
        },
        "recommendation": recommend(dominant),
    }
    if engine_git_sha:
        out["engine_git_sha"] = engine_git_sha
    if generated_date:
        out["generated_date"] = generated_date
    return out
