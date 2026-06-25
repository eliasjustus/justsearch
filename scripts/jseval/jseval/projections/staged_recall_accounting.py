"""Staged Recall Accounting — recall-funnel decomposition (tempdoc 636).

A *projection* (pure function over a run directory's artifacts) that
decomposes every judged query's outcome into the stage at which the
relevance funnel lost the gold document. It operationalizes tempdoc 636's
"Staged Recall Accounting" design and register decision D-005: capability is
measured by **recall-survival** (does each narrowing stage keep the correct
answer?), not just by an aggregate score.

For each judged query (one with qrels), using the per-mode artifacts of one
run, the outcome is classified into exactly one bucket:

- ``LEG_MISS``       — no retrieval leg surfaced the gold doc (a
  *component/representation* problem; the answer was never in the building).
- ``CASCADE_LEAK``   — a leg had the gold doc, but the fused/final stage
  dropped it before it could be ranked (a *leak*; tempdoc 636 v3 defect —
  the literature's *bounded recall problem*).
- ``JUDGE_RANK_LOW`` — the gold doc reached the final list but ranked > 1
  (a *judge/ranking* problem).
- ``OK_RANK1``       — the gold doc is at final rank 1.

**Inputs (read-only):**

- ``qrels.json`` — ``{qid: {doc_id: relevance}}``.
- ``{leg}_per_query.json`` for leg modes present in ``{vector, lexical,
  splade}`` — used for the leg-union *presence* check (order-independent).
- the **final** mode (prefer ``hybrid``, else ``full``): its score-ranked
  ``{final}_run.trec`` is the authority for *rank* (the per-query
  ``predictedDocIds`` is response-order, not score-order — tempdoc 636
  pre-implementation confidence pass), with ``predictedDocIds`` as fallback.

The projection **self-reconciles**: it cross-checks its computed "gold in
final top-N" against the harness-recorded ``recallAtK`` and reports the
mismatch count (0 on the needle-burial reference run).

**Doc-ID alignment** is already guaranteed upstream: every hit is normalized
to a uniform BEIR id by ``retriever.resolve_doc_id`` — the same namespace as
qrels — so legs / final / qrels all speak one identifier.

Output shape v1::

    {
      "status": "ok" | "insufficient-modes",
      "leg_modes": ["vector", ...],
      "final_mode": "hybrid",
      "top_n": 10,
      "n_queries_judged": 20,
      "aggregate": {
        "leak_rate": 0.55, "leg_miss_rate": 0.0,
        "judge_low_rate": 0.2, "ok_rate": 0.25,
        "leg_union_recall": 1.0, "final_recall": 0.45
      },
      "per_leg_recall": {"vector": 1.0, "lexical": 0.0, "splade": 0.0},
      "buckets": {"LEG_MISS": [...qids], "CASCADE_LEAK": [...], ...},
      "reconciliation": {"checked": 20, "mismatches": 0}
    }
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from .base import Projection

log = logging.getLogger(__name__)

PROJECTION_NAME = "staged_recall_accounting"
SCHEMA_VERSION = 1

# Single-leg retrieval modes (CE-off, fusion-none by construction —
# jseval.retriever.MODE_PIPELINES). The leg-union is computed over those present.
LEG_MODES = ("vector", "lexical", "splade")
# The "final" production-shaped list, in preference order.
FINAL_MODE_PREFERENCE = ("hybrid", "full")

BUCKETS = ("LEG_MISS", "CASCADE_LEAK", "JUDGE_RANK_LOW", "OK_RANK1")

# Conform the three *failure* buckets to the field's canonical retrieval failure-point
# vocabulary (Seven Failure Points, arXiv 2401.05856) so the output is legible to anyone
# who knows it — an annotation, not a rename (the keys above stay authoritative).
# OK_RANK1 is the success case and has no failure-point.
FP_MAPPING = {
    "LEG_MISS": "FP1 Missing-Content",        # no leg surfaced the gold doc
    "CASCADE_LEAK": "FP3 Not-in-Context",      # a leg had it; a pre-judge stage dropped it
    "JUDGE_RANK_LOW": "FP2 Missed-Top-Ranked",  # reached the final list but ranked > 1
}


def _load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def _load_trec(path: Path) -> dict[str, list[str]]:
    """Parse a ``{mode}_run.trec`` into ``{qid: [doc_id in score-rank order]}``.

    TREC line: ``qid Q0 docid rank score run_tag`` — already score-sorted by
    :func:`jseval.artifacts._write_trec_run`, so file order is rank order.
    Returns ``{}`` if the file is absent/unreadable.
    """
    ranked: dict[str, list[str]] = {}
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return ranked
    for line in text.splitlines():
        parts = line.split()
        if len(parts) >= 4:
            qid, _q0, doc_id = parts[0], parts[1], parts[2]
            ranked.setdefault(qid, []).append(doc_id)
    return ranked


def _ranked_by_qid(run_dir: Path, mode: str) -> dict[str, list[str]]:
    """Return ``{qid: [doc_id in rank order]}`` for ``mode``.

    Prefers the score-ranked ``{mode}_run.trec`` (the authority ir-measures
    scores); falls back to the response-order ``predictedDocIds`` (presence is
    order-independent, so the fallback is safe for the membership checks even
    though its *rank* is response-order).
    """
    trec = _load_trec(run_dir / f"{mode}_run.trec")
    if trec:
        return trec
    out: dict[str, list[str]] = {}
    entries = _load_json(run_dir / f"{mode}_per_query.json")
    if isinstance(entries, list):
        for e in entries:
            qid = e.get("qid")
            if qid:
                out[qid] = list(e.get("predictedDocIds") or [])
    return out


def _recall_flags(run_dir: Path, mode: str) -> dict[str, bool]:
    """``{qid: recallAtK>0}`` from the harness's recorded per-query metric."""
    flags: dict[str, bool] = {}
    entries = _load_json(run_dir / f"{mode}_per_query.json")
    if isinstance(entries, list):
        for e in entries:
            qid = e.get("qid")
            if qid:
                flags[qid] = bool((e.get("recallAtK") or 0) > 0)
    return flags


def _mean_ndcg(run_dir: Path, mode: str) -> float | None:
    """Mean ``ndcgAtK`` over the mode's per-query entries (None if absent)."""
    entries = _load_json(run_dir / f"{mode}_per_query.json")
    if not isinstance(entries, list):
        return None
    vals = [e["ndcgAtK"] for e in entries
            if isinstance(e, dict) and isinstance(e.get("ndcgAtK"), (int, float))]
    return sum(vals) / len(vals) if vals else None


def _present_modes(run_dir: Path) -> list[str]:
    stems = {p.stem.rsplit("_per_query", 1)[0] for p in run_dir.glob("*_per_query.json")}
    return [m for m in stems if m]


def _queried_qids(run_dir: Path) -> set[str]:
    """Qids actually executed in this run (union across per-mode ``*_per_query.json``).

    A capped/partial run (``--max-queries``) executes fewer queries than the corpus's
    qrels file contains. Restricting attribution to queries that were *actually run*
    prevents counting an un-executed qrels entry as a phantom ``LEG_MISS`` (which would
    inflate leg-miss and halve recall on a capped run). This aligns the bucket scope
    with the reconciliation scope, which already only checks queried qids.
    """
    qids: set[str] = set()
    for p in run_dir.glob("*_per_query.json"):
        entries = _load_json(p)
        if isinstance(entries, list):
            for e in entries:
                if isinstance(e, dict) and e.get("qid"):
                    qids.add(e["qid"])
    return qids


def _gold_set(qrels: dict, qid: str) -> set[str]:
    return {d for d, r in (qrels.get(qid) or {}).items() if isinstance(r, (int, float)) and r > 0}


def _empty(reason: str, legs: list[str], final: str | None) -> dict:
    return {
        "status": "insufficient-modes",
        "reason": reason,
        "leg_modes": legs,
        "final_mode": final,
        "n_queries_judged": 0,
        "aggregate": {},
        "per_leg_recall": {},
        "buckets": {b: [] for b in BUCKETS},
        "fp_mapping": FP_MAPPING,
        "reconciliation": {"checked": 0, "mismatches": 0},
    }


def produce(run_dir: Path) -> dict:
    """Produce the staged-recall-accounting projection (pure over artifacts)."""
    qrels = _load_json(run_dir / "qrels.json") or {}
    present = _present_modes(run_dir)
    legs = [m for m in LEG_MODES if m in present]
    final = next((m for m in FINAL_MODE_PREFERENCE if m in present), None)

    if not legs or final is None:
        return _empty("need >=1 leg mode and a final mode (hybrid/full)", legs, final)

    leg_ranked = {m: _ranked_by_qid(run_dir, m) for m in legs}
    final_ranked = _ranked_by_qid(run_dir, final)
    final_recall_flags = _recall_flags(run_dir, final)
    queried = _queried_qids(run_dir)  # restrict attribution to queries actually run

    buckets: dict[str, list[str]] = {b: [] for b in BUCKETS}
    per_leg_hits = {m: 0 for m in legs}
    leg_union_hits = 0
    final_hits = 0
    judged = 0
    recon_checked = 0
    recon_mismatch = 0

    for qid in sorted(qrels):
        gold = _gold_set(qrels, qid)
        if not gold:
            continue  # no gold → not a judged query
        if qid not in queried:
            continue  # query not executed in this (possibly capped) run — unmeasured, not a leg-miss
        judged += 1

        # leg presence (order-independent set membership)
        in_union = False
        for m in legs:
            hit = bool(gold & set(leg_ranked.get(m, {}).get(qid, [])))
            if hit:
                per_leg_hits[m] += 1
                in_union = True
        if in_union:
            leg_union_hits += 1

        # final presence + rank (score-ranked authority)
        f_ids = final_ranked.get(qid, [])
        f_rank = next((i + 1 for i, d in enumerate(f_ids) if d in gold), None)
        in_final = f_rank is not None
        if in_final:
            final_hits += 1

        # self-reconciliation against harness-recorded recall
        if qid in final_recall_flags:
            recon_checked += 1
            if in_final != final_recall_flags[qid]:
                recon_mismatch += 1

        # classify
        if in_final:
            buckets["OK_RANK1" if f_rank == 1 else "JUDGE_RANK_LOW"].append(qid)
        elif in_union:
            buckets["CASCADE_LEAK"].append(qid)
        else:
            buckets["LEG_MISS"].append(qid)

    n = judged or 1
    leg_union_recall = leg_union_hits / n
    # Judge-headroom CEILING (tempdoc 636 §5, AI-free): a *perfect* judge over the
    # current leg-union pool would rank the gold doc #1 whenever it is in the pool
    # (nDCG=1.0), so the oracle ceiling ≈ leg_union_recall. The gap to the actual
    # final nDCG is the maximum a better judge could add *given the current recall* —
    # large gap ⇒ the bottleneck is the judge/cascade; small gap with low ceiling ⇒
    # the bottleneck is the legs. A realistic (vs perfect) figure is the optional
    # LLM-oracle probe (jseval.judge_ceiling).
    final_ndcg = _mean_ndcg(run_dir, final)
    judge_headroom_ceiling = (
        max(0.0, leg_union_recall - final_ndcg) if isinstance(final_ndcg, (int, float)) else None
    )
    return {
        "status": "ok",
        "leg_modes": legs,
        "final_mode": final,
        "top_n": max((len(v) for v in final_ranked.values()), default=0),
        "n_queries_judged": judged,
        "aggregate": {
            "leak_rate": len(buckets["CASCADE_LEAK"]) / n,
            "leg_miss_rate": len(buckets["LEG_MISS"]) / n,
            "judge_low_rate": len(buckets["JUDGE_RANK_LOW"]) / n,
            "ok_rate": len(buckets["OK_RANK1"]) / n,
            "leg_union_recall": leg_union_recall,
            "final_recall": final_hits / n,
            "final_ndcg": final_ndcg,
            "oracle_judge_ndcg_ceiling": leg_union_recall,
            "judge_headroom_ceiling": judge_headroom_ceiling,
        },
        "per_leg_recall": {m: per_leg_hits[m] / n for m in legs},
        "buckets": buckets,
        "fp_mapping": FP_MAPPING,
        "reconciliation": {"checked": recon_checked, "mismatches": recon_mismatch},
    }


PROJECTION = Projection(
    name=PROJECTION_NAME,
    schema_version=SCHEMA_VERSION,
    description="Staged recall accounting: leg-recall / cascade-leak / judge-rank funnel (tempdoc 636 / D-005).",
    produce=produce,
)
