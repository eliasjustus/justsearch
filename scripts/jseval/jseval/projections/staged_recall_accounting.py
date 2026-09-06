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
final top-10" — always truncated to depth 10, matching the harness's
``recallAtK`` (``R@10``, fixed regardless of ``--top-k``; see
``jseval.artifacts``) — against that harness-recorded value, and reports the
mismatch count (0 on the needle-burial reference run). The depth-10 window is
only trustworthy when the final mode's ``run.trec`` (score-ranked) is
available; when only the response-order ``predictedDocIds`` fallback exists,
"first 10" isn't "top 10 by score", so reconciliation is marked
``"applicable": false`` instead of silently comparing at the wrong window.

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
        "leg_union_recall": 1.0, "final_recall": 0.45,
        "judge_rank_histogram": {"rank_2": 2, "rank_3_5": 1, "rank_6_10": 1, "rank_11_plus": 0},
        "judge_low_cost_weight": 0.4
      },
      "per_leg_recall": {"vector": 1.0, "lexical": 0.0, "splade": 0.0},
      "buckets": {"LEG_MISS": [...qids], "CASCADE_LEAK": [...], ...},
      "reconciliation": {"checked": 20, "mismatches": 0, "applicable": true}
    }
"""

from __future__ import annotations

import hashlib
import json
import logging
from collections import Counter
from pathlib import Path

from ..trec import load_trec_run
from .. import duplicate_prevalence
from ..result_identity import (
    SIDECAR_FILENAME,
    cluster_assignments_by_opaque_id,
    reconcile_delivered_hits,
    verify_result_identity_anchor,
)
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

# The harness's per-query recallAtK is hardcoded to R@10 regardless of --top-k
# (jseval.artifacts._write_per_query_json: "recallAtK": metrics.get("R@10")). A --top-k run
# retrieving more than 10 docs per query produces a final_ranked list longer than this depth, so
# self-reconciliation must truncate to this same fixed window rather than the full list — else a
# gold doc surfaced only at rank 11+ counts as "in final" here but not in the harness's R@10,
# manufacturing spurious mismatches (one per rank_11_plus query).
RECONCILIATION_DEPTH = 10
RESULT_REDUNDANCY_SCHEMA = "jseval.result-redundancy.v1"
RESULT_REDUNDANCY_DEPTH = 10

# Conform the *failure* buckets to the field's canonical retrieval failure-point vocabulary
# (Seven Failure Points, arXiv 2401.05856) so the output is legible to anyone who knows it — an
# annotation, not a rename (the keys above stay authoritative). OK_RANK1 is the success case and
# has no failure-point.
#
# Correction (tempdoc 643 investigation, 2026-07-01): the original mapping had CASCADE_LEAK and
# JUDGE_RANK_LOW backwards. FP2 "Missed the Top Ranked Documents" is defined verbatim by the paper
# as "did not rank highly enough to be returned to the user ... the top K documents are returned"
# — i.e. dropped before the returned cutoff, which is exactly CASCADE_LEAK (a leg had it; a
# pre-judge stage dropped it), not JUDGE_RANK_LOW. JUDGE_RANK_LOW ("reached the final/returned
# list but ranked > 1") has NO match in the Seven Failure Points taxonomy — it is a finer-grained
# distinction this instrument introduces *within* what the paper's FP2 would otherwise lump
# together as "missed the top." Deliberately left out of FP_MAPPING rather than mapped to a wrong
# FP (the dict is conform-don't-coin, not conform-don't-omit).
FP_MAPPING = {
    "LEG_MISS": "FP1 Missing-Content",     # no leg surfaced the gold doc
    "CASCADE_LEAK": "FP2 Missed-Top-Ranked",  # a leg had it; dropped before the returned cutoff
}


def _load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def _load_trec(path: Path) -> dict[str, list[str]]:
    """Parse a ``{mode}_run.trec`` into ``{qid: [doc_id in score-rank order]}``.

    Delegates to :func:`jseval.trec.load_trec_run` (right-anchored, so doc ids
    containing spaces survive); file order is rank order because
    :func:`jseval.artifacts._write_trec_run` writes score-sorted.
    """
    return load_trec_run(path)


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


def _result_redundancy(run_dir: Path, mode: str) -> dict | None:
    """Optionally measure duplicate clusters in the delivered top ten.

    Recall/rank accounting continues to use ``run.trec``. This independent
    section intentionally uses ``predictedDocIds`` because the product question
    is what the API delivered, and the identity sidecar records that same order
    before lossy BEIR normalization.
    """
    sidecar_path = run_dir / SIDECAR_FILENAME
    if not sidecar_path.is_file():
        return None
    sidecar = _load_json(sidecar_path)
    if sidecar is None:
        raise ValueError(f"unreadable result identity sidecar: {sidecar_path}")
    clusters = cluster_assignments_by_opaque_id(sidecar)
    if clusters is None:
        return None
    cluster_source = sidecar["cluster_assignments"]["cluster_source"]
    analysis_path = run_dir / cluster_source["analysis_artifact_filename"]
    analysis = _load_json(analysis_path)
    if analysis is None:
        raise ValueError(f"missing or unreadable result cluster analysis artifact: {analysis_path}")
    try:
        duplicate_prevalence.validate_artifact_hash(analysis)
    except duplicate_prevalence.DuplicatePrevalenceError as exc:
        raise ValueError(f"invalid result cluster analysis artifact: {exc}") from exc
    if analysis["artifact_hash"] != cluster_source["analysis_artifact_sha256"]:
        raise ValueError("result cluster analysis hash does not match the identity sidecar")
    analysis_input = analysis.get("input") or {}
    if (
        analysis_input.get("source_kind") != duplicate_prevalence.PRODUCTION_EXTRACTED
        or analysis_input.get("content_interpretation") != "production-extracted-content"
        or not isinstance(analysis_input.get("extraction_identity"), dict)
    ):
        raise ValueError("result clusters require a production-extracted analysis artifact")
    analysis_corpus_signature = (analysis_input.get("corpus_identity") or {}).get("signature")
    if analysis_corpus_signature != sidecar["corpus_signature"]:
        raise ValueError("result cluster analysis corpus does not match the identity sidecar")
    if analysis.get("privacy") != {
        "mode": "aggregate-only",
        "document_ids_emitted": False,
        "paths_emitted": False,
        "text_emitted": False,
    }:
        raise ValueError("result cluster analysis privacy contract is invalid")
    assignment_records = sidecar["cluster_assignments"]["assignments"]
    observed_fingerprint_counts = Counter(
        record["content_fingerprint_sha256"] for record in assignment_records
    )
    analyzed_duplicate_sizes = {
        record["digest"]: record["size"]
        for record in (analysis.get("content_exact") or {}).get("duplicate_groups", [])
        if isinstance(record, dict)
    }
    for fingerprint, observed_count in observed_fingerprint_counts.items():
        if observed_count >= 2 and analyzed_duplicate_sizes.get(fingerprint, 0) < observed_count:
            raise ValueError(
                "result sidecar exact-content cluster is not supported by the analysis artifact"
            )
    summary = _load_json(run_dir / "summary.json")
    try:
        verify_result_identity_anchor(
            sidecar,
            analysis,
            summary.get("result_identity_anchor") if isinstance(summary, dict) else None,
        )
    except Exception as exc:
        raise ValueError(f"invalid result identity anchor: {exc}") from exc
    run_corpus_signature = (
        (summary.get("corpus_identity") or {}).get("signature")
        if isinstance(summary, dict)
        else None
    )
    if run_corpus_signature is None or run_corpus_signature != sidecar["corpus_signature"]:
        raise ValueError(
            "result identity sidecar corpus_signature does not match the run summary"
        )
    entries = _load_json(run_dir / f"{mode}_per_query.json")
    if not isinstance(entries, list):
        raise ValueError(f"missing per-query artifact for result redundancy mode {mode!r}")
    opaque_by_qid = reconcile_delivered_hits(sidecar, mode, entries)

    per_query: list[dict] = []
    delivered_total = 0
    unique_total = 0
    redundant_total = 0
    affected = 0
    for qid in sorted(opaque_by_qid):
        top = opaque_by_qid[qid][:RESULT_REDUNDANCY_DEPTH]
        try:
            cluster_ids = [clusters[opaque] for opaque in top]
        except KeyError as exc:  # sidecar validation normally proves complete coverage
            raise ValueError(f"missing result cluster assignment for {exc.args[0]!r}") from exc
        delivered = len(cluster_ids)
        unique = len(set(cluster_ids))
        redundant = delivered - unique
        delivered_total += delivered
        unique_total += unique
        redundant_total += redundant
        affected += int(redundant > 0)
        per_query.append({
            "qid": qid,
            "delivered_hits_at_10": delivered,
            "unique_clusters_at_10": unique,
            "redundant_hits_at_10": redundant,
        })

    measured = len(per_query)
    denominator = measured or 1
    predicted_hit_count = sum(len(entry["predictedDocIds"]) for entry in entries)
    sidecar_mode = next(record for record in sidecar["modes"] if record["mode"] == mode)
    sidecar_hit_count = sum(len(query["hits"]) for query in sidecar_mode["queries"])
    return {
        "schema": RESULT_REDUNDANCY_SCHEMA,
        "identity_sidecar_schema": sidecar["schema"],
        "cluster_assignment_schema": sidecar["cluster_assignments"]["schema"],
        "sidecar_sha256": hashlib.sha256(sidecar_path.read_bytes()).hexdigest(),
        "corpus_signature": sidecar["corpus_signature"],
        "cluster_source": cluster_source,
        "mode": mode,
        "rank_authority": "delivered-predictedDocIds",
        "top_k": RESULT_REDUNDANCY_DEPTH,
        "queries_measured": measured,
        "queries_affected": affected,
        "queries_affected_rate": affected / denominator,
        "aggregate": {
            "delivered_hits_at_10": delivered_total,
            "unique_clusters_at_10": unique_total,
            "redundant_hits_at_10": redundant_total,
            "mean_unique_clusters_at_10": unique_total / denominator,
            "mean_redundant_hits_at_10": redundant_total / denominator,
        },
        "per_query": per_query,
        "reconciliation": {
            "predicted_hits": predicted_hit_count,
            "sidecar_hits": sidecar_hit_count,
            "mismatches": 0,
        },
    }


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


# Tempdoc 643 (E3): rank-2 is "nearly free" product-wise (T1-a — an interactive user scans past
# it for free; the RAG/agent path feeds the whole top-k as a set); rank-6-10 is a genuine
# mis-rank. A monotonically increasing weight per bucket turns a flat failure COUNT into a
# cost-weighted severity (T6-a "dominant-count ≠ dominant-cost") — a heuristic default, not a
# fitted/calibrated value (this projection stays label-free, per Research pass 2).
JUDGE_RANK_COST_WEIGHTS = {"rank_2": 0.1, "rank_3_5": 0.4, "rank_6_10": 1.0, "rank_11_plus": 1.0}


def _judge_low_cost_weight(histogram: dict[str, int]) -> float | None:
    """[0,1] weighted average of ``judge_rank_histogram`` via ``JUDGE_RANK_COST_WEIGHTS``.

    ``None`` when the histogram is empty (no JUDGE_RANK_LOW queries to weight) — mirrors this
    file's existing None-on-empty convention (e.g. ``judge_headroom_ceiling``).
    """
    total = sum(histogram.values())
    if total == 0:
        return None
    weighted = sum(JUDGE_RANK_COST_WEIGHTS[bucket] * count for bucket, count in histogram.items())
    return weighted / total


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
        "reconciliation": {"checked": 0, "mismatches": 0, "applicable": False,
                            "reason": "insufficient-modes: no reconciliation was computed"},
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
    # Reconciliation is only trustworthy against the score-ranked run.trec: truncating the
    # response-order predictedDocIds fallback to "first 10" is not "top 10 by score".
    recon_applicable = bool(_load_trec(run_dir / f"{final}_run.trec"))

    buckets: dict[str, list[str]] = {b: [] for b in BUCKETS}
    per_leg_hits = {m: 0 for m in legs}
    leg_union_hits = 0
    final_hits = 0
    judged = 0
    recon_checked = 0
    recon_mismatch = 0
    # Tempdoc 643: in-bucket rank distribution for JUDGE_RANK_LOW — a flat rate hides whether
    # the bucket is mostly rank-2 (near-ceiling: one slot off) or rank-6-10 (a real mis-rank).
    # "rank_11_plus" is a defensive catch-all (top_n is observed, not assumed to be exactly 10).
    judge_rank_histogram = {"rank_2": 0, "rank_3_5": 0, "rank_6_10": 0, "rank_11_plus": 0}

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

        # self-reconciliation against harness-recorded recall: truncate to the same fixed
        # depth-10 window as the harness's recallAtK (R@10, independent of --top-k) rather than
        # the full (possibly longer) final list, so a gold doc only surfaced past rank 10 isn't
        # counted as a mismatch.
        if recon_applicable and qid in final_recall_flags:
            recon_in_final = bool(gold & set(f_ids[:RECONCILIATION_DEPTH]))
            recon_checked += 1
            if recon_in_final != final_recall_flags[qid]:
                recon_mismatch += 1

        # classify
        if in_final:
            if f_rank == 1:
                buckets["OK_RANK1"].append(qid)
            else:
                buckets["JUDGE_RANK_LOW"].append(qid)
                if f_rank == 2:
                    judge_rank_histogram["rank_2"] += 1
                elif f_rank <= 5:
                    judge_rank_histogram["rank_3_5"] += 1
                elif f_rank <= 10:
                    judge_rank_histogram["rank_6_10"] += 1
                else:
                    judge_rank_histogram["rank_11_plus"] += 1
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
    judge_low_cost_weight = _judge_low_cost_weight(judge_rank_histogram)
    result = {
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
            # Tempdoc 643: JUDGE_RANK_LOW rank distribution (counts, not rates — sums to
            # len(buckets["JUDGE_RANK_LOW"])). near-ceiling (rank_2-heavy) vs real mis-rank
            # (rank_6_10-heavy) changes whether the bucket is worth a lever at all.
            "judge_rank_histogram": judge_rank_histogram,
            # Tempdoc 643 (E3, T6-a "dominant-count ≠ dominant-cost"): a [0,1] weighted average
            # over judge_rank_histogram turning a flat failure COUNT into a cost-weighted severity
            # — rank-2 is near-free (one slot off), rank-6-10 is a real mis-rank. None when the
            # bucket is empty (no judge-rank-low queries to weight).
            "judge_low_cost_weight": judge_low_cost_weight,
        },
        "per_leg_recall": {m: per_leg_hits[m] / n for m in legs},
        "buckets": buckets,
        "fp_mapping": FP_MAPPING,
        "reconciliation": {
            "checked": recon_checked,
            "mismatches": recon_mismatch,
            "applicable": recon_applicable,
            **({} if recon_applicable else {
                "reason": f"final mode {final!r} has no score-ranked run.trec — only the "
                          "response-order predictedDocIds fallback is available, so a "
                          f"depth-{RECONCILIATION_DEPTH} reconciliation window can't be trusted",
            }),
        },
    }
    redundancy = _result_redundancy(run_dir, final)
    if redundancy is not None:
        result["result_redundancy"] = redundancy
    return result


PROJECTION = Projection(
    name=PROJECTION_NAME,
    schema_version=SCHEMA_VERSION,
    description="Staged recall accounting: leg-recall / cascade-leak / judge-rank funnel (tempdoc 636 / D-005).",
    produce=produce,
)
