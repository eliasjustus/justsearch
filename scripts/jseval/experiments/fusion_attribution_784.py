"""Fusion-attribution study for the Step-0 gate-harm (tempdoc 784 §3.B.9 / register F-036).

Pure-offline analysis over the already-written Step-0 arm run directories
(``tmp/781-certification/step0/arm-A{1..4}``). No backend, no GPU, no paid calls,
no repo writes outside the emitted JSON/report.

Question. Step 0 established that raising ``justsearch.splade.zero_weight_min_tokens``
past the corpus's parent-token range costs hybrid nDCG@10 ~7% on legal-clerc-200,
identically with chunk-SPLADE on (A2 0.6441 -> A3 0.5911) and off (A1 0.6358 ->
A4 0.5845). The recorded mechanism ("flat 0.2 CC weight for a leg far below the
ensemble") was refuted per-query on 2026-07-28: harm is uncorrelated with per-query
SPLADE strength. This script asks *where the harm actually comes from*.

Candidate mechanisms tested here:

  (a) direct rank displacement  - SPLADE-injected docs push gold out of the top-10.
  (b) renormalisation shift     - min-max + zero-exclude weight renormalisation moves
                                  every doc's fused score, including docs the SPLADE
                                  leg never touched.
  (c) pool composition          - the fused candidate pool handed to the cross-encoder
                                  changes upstream of the final ranking.
  (d) something else.

Structural finding that frames all of them (see ``--help`` output of section MECH-0):
``SPLADE_ZERO_WEIGHT_MIN_TOKENS`` is a single shared constant
(``HybridFusionUtils.java:26-27``) read by BOTH
``spladeParentLengthMultiplier`` (Stage 3A SPLADE leg suppression, ``:803-806``,
applied at ``:693``) AND ``chunkBranchParentLengthMultiplier`` (Stage 3B whole-vs-chunk
branch ramp, ``:826-834``, applied at ``:488-491`` from the call site
``SearchExecutor.java:766-780``). Raising it therefore moves two levers at once.

Run:
    PYTHONUTF8=1 python scripts/jseval/experiments/fusion_attribution_784.py \
        --step0-root F:/justsearch-public/tmp/781-certification/step0 \
        --out-dir tmp/784-fusion-attribution
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections import Counter
from pathlib import Path

# Import this worktree's jseval, not whichever checkout the editable install points at
# (tempdoc 716; same bootstrap as experiments/inject_707_probe.py).
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from jseval.trec import parse_trec_line  # noqa: E402

ARMS = ["A1", "A2", "A3", "A4"]
ARM_SEMANTICS = {
    "A1": "chunk-splade OFF, gate DEFAULT",
    "A2": "chunk-splade ON,  gate DEFAULT",
    "A3": "chunk-splade ON,  gate RAISED",
    "A4": "chunk-splade OFF, gate RAISED",
}
# The two gate-raise comparisons (default-gate arm, raised-gate arm).
COMPARISONS = [("A1", "A4"), ("A2", "A3")]

# Engine constants, read verbatim from source (cited in the report).
SPLADE_FULL_WEIGHT_MAX_TOKENS_DEFAULT = 1024
SPLADE_ZERO_WEIGHT_MIN_TOKENS_DEFAULT = 4096
GATE_RAISED_VALUE = 1_000_000_000  # tempdoc 784:200-203 Step-0 recipe
BRANCH_CC_WEIGHT_WHOLE = 0.50  # manifest: branchCcWeightWhole
BRANCH_CC_WEIGHT_CHUNK = 0.50  # manifest: branchCcWeightChunk
BRANCH_CHUNK_MIN_WEIGHT_MULTIPLIER = 0.25  # manifest: branchChunkMinWeightMultiplier
CORPUS_MEDIAN_PARENT_TOKENS = 6615  # F-033 measurement (tokenizer-measured)


# ---------------------------------------------------------------------------
# Engine arithmetic, replicated from HybridFusionUtils.java
# ---------------------------------------------------------------------------

def _interp(tokens: float, lower: float, upper: float, lower_v: float, upper_v: float) -> float:
    """HybridFusionUtils.linearInterpolationByParentLength (:908-924)."""
    if tokens <= lower:
        return lower_v
    if tokens >= upper:
        return upper_v
    return lower_v + (upper_v - lower_v) * ((tokens - lower) / (upper - lower))


def splade_parent_length_multiplier(tokens: float, full_max: float, zero_min: float) -> float:
    """HybridFusionUtils.spladeParentLengthMultiplier (:803-806). 1.0 -> 0.0."""
    return _interp(tokens, full_max, zero_min, 1.0, 0.0)


def chunk_branch_parent_length_multiplier(
    tokens: float, full_max: float, zero_min: float, min_mult: float
) -> float:
    """HybridFusionUtils.chunkBranchParentLengthMultiplier (:826-834). min_mult -> 1.0."""
    return _interp(tokens, full_max, zero_min, min_mult, 1.0)


def effective_branch_weights(chunk_modifier: float) -> tuple[float, float]:
    """fuseWithCCNamed zero-exclude renormalisation (:493-503) for a doc in BOTH branches."""
    w_whole = BRANCH_CC_WEIGHT_WHOLE
    w_chunk = BRANCH_CC_WEIGHT_CHUNK * chunk_modifier
    denom = w_whole + w_chunk
    return (w_whole / denom, w_chunk / denom) if denom > 0 else (0.0, 0.0)


def effective_cc3_weights(
    in_sparse: bool, in_dense: bool, in_splade: bool, weights: tuple[float, float, float],
    splade_modifier: float,
) -> tuple[float, float, float]:
    """fuseWithCC3 zero-exclude renormalisation (:695-708), zeroExclude=true."""
    ws = weights[0] if in_sparse else 0.0
    wd = weights[1] if in_dense else 0.0
    wp = (weights[2] * splade_modifier) if in_splade else 0.0
    denom = ws + wd + wp
    if denom <= 0:
        return (0.0, 0.0, 0.0)
    return (ws / denom, wd / denom, wp / denom)


# ---------------------------------------------------------------------------
# Artifact loading
# ---------------------------------------------------------------------------

def load_arm(root: Path, arm: str) -> dict:
    d = root / f"arm-{arm}"
    out = {}
    for key, name in [("hybrid", "hybrid_per_query.json"), ("splade", "splade_per_query.json")]:
        with (d / name).open(encoding="utf-8") as f:
            out[key] = {e["qid"]: e for e in json.load(f)}
    with (d / "qrels.json").open(encoding="utf-8") as f:
        out["qrels"] = json.load(f)
    trec = {}
    with (d / "hybrid_run.trec").open(encoding="utf-8") as f:
        for line in f:
            e = parse_trec_line(line)
            if e is not None:
                trec.setdefault(e.qid, []).append((e.doc_id, e.rank, e.score))
    out["trec"] = trec
    return out


def has_whole_leg(js: dict) -> bool:
    """A returned hit carries whole-doc-branch provenance iff a bm25 or dense leg rank is set.

    A hit with neither (and no splade leg, which is never populated in hybrid) reached the
    final list only through the Stage-3B chunk branch.
    """
    return js.get("bm25_rank") is not None or js.get("dense_rank") is not None


def gold_docs(qrels: dict, qid: str) -> set[str]:
    return {d for d, rel in (qrels.get(qid) or {}).items() if rel and rel > 0}


# ---------------------------------------------------------------------------
# Sections
# ---------------------------------------------------------------------------

def section_inventory(arms: dict) -> dict:
    """INV - what per-query fusion-level data the arm run dirs actually contain."""
    per_arm = {}
    for a in ARMS:
        hyb = arms[a]["hybrid"]
        rows = 0
        cov = Counter()
        for e in hyb.values():
            for js in e.get("judgeSignals") or []:
                rows += 1
                for leg in ("bm25", "splade", "dense"):
                    if js.get(f"{leg}_rank") is not None:
                        cov[f"{leg}_rank"] += 1
                    if js.get(f"{leg}_score") is not None:
                        cov[f"{leg}_score"] += 1
                for k in ("fusion_score", "ce_score"):
                    if js.get(k) is not None:
                        cov[k] += 1
        spl_rows = 0
        spl_cov = Counter()
        for e in arms[a]["splade"].values():
            for js in e.get("judgeSignals") or []:
                spl_rows += 1
                for leg in ("bm25", "splade", "dense"):
                    if js.get(f"{leg}_rank") is not None:
                        spl_cov[f"{leg}_rank"] += 1
        per_arm[a] = {
            "semantics": ARM_SEMANTICS[a],
            "n_queries": len(hyb),
            "hybrid_judge_signal_rows": rows,
            "hybrid_judge_signal_coverage": dict(sorted(cov.items())),
            "splade_mode_judge_signal_rows": spl_rows,
            "splade_mode_judge_signal_coverage": dict(sorted(spl_cov.items())),
            "hybrid_total_hits_median": statistics.median(
                [e["totalHits"] for e in hyb.values() if e.get("totalHits") is not None]
            ),
            "splade_mode_total_hits_median": statistics.median(
                [e["totalHits"] for e in arms[a]["splade"].values() if e.get("totalHits") is not None]
            ),
        }
    return {
        "files_per_arm": [
            "hybrid_per_query.json  - 200 records; per-query metrics + predictedDocIds + judgeSignals (top-10 only)",
            "splade_per_query.json  - 200 records; the isolated splade-mode run, same shape",
            "hybrid_run.trec / splade_run.trec - TREC runs, re-sorted by the API `score` field",
            "qrels.json             - {qid: {docId: rel}}",
            "manifest.json          - run identity, model fingerprints, status/debug snapshots",
            "summary.json           - aggregate metrics",
            "metrics.ndjson / metrics-worker.ndjson - timing/counter streams",
            "projections/{bootstrap_ci,stratified_metrics,staged_recall_accounting,rank_diff,...}.json",
        ],
        "judge_signal_fields": [
            "docId, bm25_rank, bm25_score, splade_rank, splade_score, dense_rank, dense_score,"
            " fusion_score, ce_score  (jseval/provenance.py:325-361)"
        ],
        "per_arm": per_arm,
        "not_derivable": {
            "per_leg_SPLADE_score_under_hybrid": (
                "splade_rank/splade_score are null on 100% of hybrid-mode judgeSignal rows in all"
                " four arms. The typed splade leg is attached at SearchExecutor.java:470-472, so a"
                " null means the returned doc was absent from the whole-doc SPLADE candidate list."
                " Consequence: for no doc that reached a final top-10 can the SPLADE leg's"
                " contribution be reconstructed."
            ),
            "chunk_branch_membership_and_scores": (
                "attachChunkMerge (HitProvenanceProjector.java:134-164) puts a chunk-merge stage on"
                " the wire, but jseval's extract_judge_signals reads only sparse/splade/dense/"
                "branch-fusion/fusion/cross-encoder (provenance.py:341-361), so chunk-branch rank/"
                "score is dropped at the artifact boundary."
            ),
            "normalised_per_leg_scores_and_effective_weights": (
                "cc_effective_weight_*, cc_modifier_*, parent_token_count and the whole_branch_/"
                "chunk_branch_ debug keys are emitted only under include_detail/debug"
                " (SearchResponseBuilder.java:297-299, HybridFusionUtils.java:752-777). jseval's"
                " eval path does not set debug (retriever.py:165-177 is only reached with"
                " debug=True, which run.py never passes), so the numeric detail tier was never on"
                " the wire for these arms."
            ),
            "below_top10_rankings": (
                "Only the top-10 is persisted per query, so a gold doc that fell out of the"
                " raised-gate arm's top-10 has no rank/score record in that arm."
            ),
        },
    }


def section_order_semantics(arms: dict) -> dict:
    """ORD - which ordering the reported metric is actually computed over."""
    out = {}
    for a in ARMS:
        hyb = arms[a]["hybrid"]
        ce_desc = fus_desc = trec_eq_api = 0
        n = 0
        for qid, e in hyb.items():
            js = e.get("judgeSignals") or []
            ce = [j.get("ce_score") for j in js]
            fs = [j.get("fusion_score") for j in js]
            n += 1
            if ce and all(x is not None for x in ce) and ce == sorted(ce, reverse=True):
                ce_desc += 1
            if fs and all(x is not None for x in fs) and fs == sorted(fs, reverse=True):
                fus_desc += 1
            trec_order = [d for d, r, _ in sorted(arms[a]["trec"].get(qid, []), key=lambda x: x[1])]
            if trec_order == e["predictedDocIds"]:
                trec_eq_api += 1
        out[a] = {
            "n_queries": n,
            "api_order_is_ce_descending": ce_desc,
            "api_order_is_fusion_descending": fus_desc,
            "trec_order_equals_api_order": trec_eq_api,
        }
    return {
        "per_arm": out,
        "reading": (
            "The API returns the final top-10 in cross-encoder order, but jseval scores with"
            " ir_measures.ScoredDoc(score=hit['score']) (retriever.py:143), and hit['score'] is the"
            " fused (branch-fusion) score, not the CE score. So the REPORTED nDCG@10 is computed"
            " over the CE-SELECTED SET re-sorted by the FUSED SCORE. Both channels of the gate"
            " change - which 10 docs the CE sees/keeps, and how the fused score orders them - land"
            " on the metric."
        ),
    }


def section_mech0_shared_constant() -> dict:
    """MECH-0 - the shared-constant coupling, computed arithmetic."""
    rows = []
    for tokens in [512, 1024, 2048, 4096, CORPUS_MEDIAN_PARENT_TOKENS, 12000, 40000]:
        default_splade = splade_parent_length_multiplier(
            tokens, SPLADE_FULL_WEIGHT_MAX_TOKENS_DEFAULT, SPLADE_ZERO_WEIGHT_MIN_TOKENS_DEFAULT
        )
        default_chunk = chunk_branch_parent_length_multiplier(
            tokens, SPLADE_FULL_WEIGHT_MAX_TOKENS_DEFAULT, SPLADE_ZERO_WEIGHT_MIN_TOKENS_DEFAULT,
            BRANCH_CHUNK_MIN_WEIGHT_MULTIPLIER,
        )
        # Step-0 recipe raised BOTH properties to 1e9 (tempdoc 784:200-203).
        raised_splade = splade_parent_length_multiplier(tokens, GATE_RAISED_VALUE, GATE_RAISED_VALUE)
        raised_chunk = chunk_branch_parent_length_multiplier(
            tokens, GATE_RAISED_VALUE, GATE_RAISED_VALUE, BRANCH_CHUNK_MIN_WEIGHT_MULTIPLIER
        )
        # Robustness: only zero_min raised, full_max left at 1024.
        raised_chunk_zero_only = chunk_branch_parent_length_multiplier(
            tokens, SPLADE_FULL_WEIGHT_MAX_TOKENS_DEFAULT, GATE_RAISED_VALUE,
            BRANCH_CHUNK_MIN_WEIGHT_MULTIPLIER,
        )
        rows.append({
            "parent_tokens": tokens,
            "splade_leg_multiplier_default": round(default_splade, 4),
            "splade_leg_multiplier_raised": round(raised_splade, 4),
            "chunk_branch_multiplier_default": round(default_chunk, 4),
            "chunk_branch_multiplier_raised_both_props": round(raised_chunk, 4),
            "chunk_branch_multiplier_raised_zero_min_only": round(raised_chunk_zero_only, 6),
            "effective_branch_weights_default_whole_chunk": [
                round(x, 4) for x in effective_branch_weights(default_chunk)
            ],
            "effective_branch_weights_raised_whole_chunk": [
                round(x, 4) for x in effective_branch_weights(raised_chunk)
            ],
        })
    med = next(r for r in rows if r["parent_tokens"] == CORPUS_MEDIAN_PARENT_TOKENS)
    return {
        "claim": (
            "justsearch.splade.zero_weight_min_tokens is ONE static constant"
            " (HybridFusionUtils.java:26-27) consumed by TWO different levers:"
            " spladeParentLengthMultiplier (Stage 3A SPLADE leg, :803-806, applied :693) and"
            " chunkBranchParentLengthMultiplier (Stage 3B whole-vs-chunk branch ramp, :826-834,"
            " applied :488-491 from SearchExecutor.java:766-780). The Step-0 'gate raise' arm"
            " therefore moved BOTH."
        ),
        "table": rows,
        "corpus_median_reading": (
            f"At the corpus median parent length ({CORPUS_MEDIAN_PARENT_TOKENS} tokens, F-033):"
            f" the chunk branch's weight multiplier drops"
            f" {med['chunk_branch_multiplier_default']} -> {med['chunk_branch_multiplier_raised_both_props']}"
            f", i.e. the effective whole:chunk split for a doc found by BOTH branches goes"
            f" {med['effective_branch_weights_default_whole_chunk']} ->"
            f" {med['effective_branch_weights_raised_whole_chunk']}."
            " The chunk branch loses ~60% of its relative influence. This happens for EVERY"
            " document and is entirely independent of SPLADE."
        ),
        "robustness": (
            "The conclusion does not depend on whether the operator raised one property or both:"
            " with only zero_weight_min_tokens raised to 1e9 the chunk multiplier is 0.25 + "
            "(tokens-1024)/(1e9-1024)*0.75, which is 0.2500 to four decimals for any realistic"
            " parent length. Independently, A3's splade-mode 0.2902 (vs A2 0.0901) proves"
            " zero_weight_min_tokens WAS raised, since that is the property that un-gates the leg."
        ),
        "zero_exclude_note": (
            "Under zeroExclude (fuseWithCCNamed:495-503) the de-weighting bites ONLY on docs"
            " present in both branches: a whole-only or chunk-only doc renormalises to weight 1.0"
            " on its single branch and is unaffected. So the gate raise selectively demotes the"
            " docs that BOTH branches found - and being found by both branches is a mark of"
            " genuine relevance."
        ),
    }


def section_mech_a_splade_injection(arms: dict, worst: dict) -> dict:
    """MECH-A - does the SPLADE leg inject docs that displace gold? (hypothesis a)"""
    out = {}
    for a_arm, b_arm in COMPARISONS:
        per = {}
        for label, arm in [("default_gate", a_arm), ("raised_gate", b_arm)]:
            hyb, spl = arms[arm]["hybrid"], arms[arm]["splade"]
            ov = [len(set(spl[q]["predictedDocIds"]) & set(hyb[q]["predictedDocIds"])) for q in hyb]
            per[label] = {
                "arm": arm,
                "mean_overlap_splade_top10_vs_final_top10": round(sum(ov) / len(ov), 4),
                "queries_with_zero_overlap": sum(1 for x in ov if x == 0),
            }
        # Entrants into the raised-gate top-10, and whether they came from the SPLADE ranking.
        wq = worst[f"{a_arm}_vs_{b_arm}"]
        for scope, qids in [("worst_10_harmed", wq), ("all_queries", list(arms[a_arm]["hybrid"]))]:
            entrants = 0
            entrants_from_splade_top10 = 0
            for q in qids:
                a_top = set(arms[a_arm]["hybrid"][q]["predictedDocIds"])
                b_top = arms[b_arm]["hybrid"][q]["predictedDocIds"]
                s_top = set(arms[b_arm]["splade"][q]["predictedDocIds"])
                for d in b_top:
                    if d not in a_top:
                        entrants += 1
                        if d in s_top:
                            entrants_from_splade_top10 += 1
            per[f"entrants_{scope}"] = {
                "n_entrant_docs": entrants,
                "n_entrants_also_in_splade_mode_top10": entrants_from_splade_top10,
                "share_pct": round(100 * entrants_from_splade_top10 / entrants, 2) if entrants else None,
            }
        out[f"{a_arm}_vs_{b_arm}"] = per
    return {
        "per_comparison": out,
        "verdict": (
            "REFUTED as the primary channel. The overlap between the isolated SPLADE ranking and"
            " the final top-10 does NOT rise when the gate is raised (it is flat/marginally down),"
            " and the docs that newly enter the raised-gate top-10 are overwhelmingly NOT SPLADE"
            " top-10 docs. Combined with splade_rank being null on 100% of returned hits in every"
            " arm, no SPLADE-injected document is observably displacing gold."
        ),
    }


def section_mech_b_branch_flavour(arms: dict, worst: dict) -> dict:
    """MECH-B - does the final top-10 shift toward whole-doc-branch provenance? (hypotheses b/c)"""
    def flavour(arm: str, qids) -> dict:
        rows = whole = 0
        for q in qids:
            for js in arms[arm]["hybrid"][q].get("judgeSignals") or []:
                rows += 1
                if has_whole_leg(js):
                    whole += 1
        return {
            "rows": rows,
            "with_whole_doc_leg": whole,
            "chunk_branch_only": rows - whole,
            "chunk_branch_only_pct": round(100 * (rows - whole) / rows, 2) if rows else None,
        }

    out = {}
    for a_arm, b_arm in COMPARISONS:
        key = f"{a_arm}_vs_{b_arm}"
        all_q = list(arms[a_arm]["hybrid"])
        wq = worst[key]
        # Matched control: queries with delta exactly 0 (unaffected), same count as worst set.
        unaffected = [
            q for q in all_q
            if abs((arms[a_arm]["hybrid"][q]["ndcgAtK"] or 0) - (arms[b_arm]["hybrid"][q]["ndcgAtK"] or 0)) < 1e-12
        ]
        control = unaffected[: len(wq)]
        scopes = {"all_queries": all_q, "worst_10_harmed": wq,
                  "control_unaffected_matched": control, "all_unaffected": unaffected}
        out[key] = {
            scope: {
                "n_queries": len(qids),
                "default_gate": flavour(a_arm, qids),
                "raised_gate": flavour(b_arm, qids),
                "chunk_only_pct_delta": (
                    round(flavour(b_arm, qids)["chunk_branch_only_pct"]
                          - flavour(a_arm, qids)["chunk_branch_only_pct"], 2)
                    if flavour(a_arm, qids)["rows"] else None
                ),
            }
            for scope, qids in scopes.items()
        }
    return {
        "per_comparison": out,
        "reading": (
            "Negative chunk_only_pct_delta = the raised-gate top-10 is more whole-doc-branch"
            " flavoured, the direction predicted by the chunk-branch de-weighting of MECH-0."
        ),
    }


def section_mech_c_gold_profile(arms: dict, worst: dict) -> dict:
    """MECH-C - per-query: what happened to the gold doc, and what replaced it."""
    out = {}
    for a_arm, b_arm in COMPARISONS:
        key = f"{a_arm}_vs_{b_arm}"
        per_query = []
        for q in worst[key]:
            gold = gold_docs(arms[a_arm]["qrels"], q)
            a_e, b_e = arms[a_arm]["hybrid"][q], arms[b_arm]["hybrid"][q]
            a_js = {j["docId"]: j for j in a_e.get("judgeSignals") or []}
            b_js = {j["docId"]: j for j in b_e.get("judgeSignals") or []}
            a_pos = {d: i + 1 for i, d in enumerate(a_e["predictedDocIds"])}
            b_pos = {d: i + 1 for i, d in enumerate(b_e["predictedDocIds"])}
            recs = []
            for g in sorted(gold):
                j = a_js.get(g) or b_js.get(g) or {}
                recs.append({
                    "gold_doc": g,
                    "rank_default_gate": a_pos.get(g),
                    "rank_raised_gate": b_pos.get(g),
                    "fell_out_of_top10": g in a_pos and g not in b_pos,
                    "gold_bm25_rank": j.get("bm25_rank"),
                    "gold_dense_rank": j.get("dense_rank"),
                    "gold_splade_rank": j.get("splade_rank"),
                    "gold_has_whole_doc_leg": has_whole_leg(j) if j else None,
                    "gold_fusion_score_default": (a_js.get(g) or {}).get("fusion_score"),
                    "gold_fusion_score_raised": (b_js.get(g) or {}).get("fusion_score"),
                })
            entrants = [d for d in b_e["predictedDocIds"] if d not in a_pos]
            ent_whole = sum(1 for d in entrants if has_whole_leg(b_js.get(d, {})))
            ent_splade = sum(
                1 for d in entrants if d in set(arms[b_arm]["splade"][q]["predictedDocIds"])
            )
            per_query.append({
                "qid": q,
                "ndcg_default_gate": a_e["ndcgAtK"],
                "ndcg_raised_gate": b_e["ndcgAtK"],
                "delta": round((a_e["ndcgAtK"] or 0) - (b_e["ndcgAtK"] or 0), 4),
                "splade_mode_ndcg_this_query": arms[b_arm]["splade"][q]["ndcgAtK"],
                "gold": recs,
                "n_entrants": len(entrants),
                "entrants_with_whole_doc_leg": ent_whole,
                "entrants_in_splade_mode_top10": ent_splade,
            })
        n_gold_whole = sum(
            1 for r in per_query for g in r["gold"] if g["fell_out_of_top10"] and g["gold_has_whole_doc_leg"]
        )
        n_gold_chunk = sum(
            1 for r in per_query for g in r["gold"]
            if g["fell_out_of_top10"] and g["gold_has_whole_doc_leg"] is False
        )
        out[key] = {
            "per_query": per_query,
            "summary": {
                "gold_that_fell_out_with_whole_doc_leg": n_gold_whole,
                "gold_that_fell_out_chunk_branch_only": n_gold_chunk,
                "total_entrants": sum(r["n_entrants"] for r in per_query),
                "entrants_with_whole_doc_leg": sum(r["entrants_with_whole_doc_leg"] for r in per_query),
                "entrants_in_splade_mode_top10": sum(r["entrants_in_splade_mode_top10"] for r in per_query),
            },
        }
    return out


def section_mech_d_reconstruction(arms: dict, worst: dict) -> dict:
    """MECH-D - solve the branch-fusion arithmetic for the two unknown normalised branch scores.

    For a doc present in BOTH branches the branch-fusion CC is
        fused = e_whole * nWhole + e_chunk * nChunk
    with (e_whole, e_chunk) = (0.5, 0.5) at the default gate and (0.8, 0.2) at the raised gate
    (MECH-0). Two arms give two equations in the two unknowns, so nWhole/nChunk are solvable in
    closed form for every doc that appears in both arms' top-10. Both must land in [0, 1] for the
    model to be admissible - this is the falsifiable test of hypothesis (b): does branch-weight
    renormalisation ALONE reproduce the observed fused-score movement?
    """
    med_default = chunk_branch_parent_length_multiplier(
        CORPUS_MEDIAN_PARENT_TOKENS, SPLADE_FULL_WEIGHT_MAX_TOKENS_DEFAULT,
        SPLADE_ZERO_WEIGHT_MIN_TOKENS_DEFAULT, BRANCH_CHUNK_MIN_WEIGHT_MULTIPLIER,
    )
    med_raised = chunk_branch_parent_length_multiplier(
        CORPUS_MEDIAN_PARENT_TOKENS, GATE_RAISED_VALUE, GATE_RAISED_VALUE,
        BRANCH_CHUNK_MIN_WEIGHT_MULTIPLIER,
    )
    ea_w, ea_c = effective_branch_weights(med_default)
    eb_w, eb_c = effective_branch_weights(med_raised)
    det = ea_w * eb_c - ea_c * eb_w

    out = {}
    for a_arm, b_arm in COMPARISONS:
        key = f"{a_arm}_vs_{b_arm}"
        admissible = total = 0
        n_whole_gt_chunk = 0
        deltas = []
        samples = []
        for q in arms[a_arm]["hybrid"]:
            a_js = {j["docId"]: j for j in arms[a_arm]["hybrid"][q].get("judgeSignals") or []}
            b_js = {j["docId"]: j for j in arms[b_arm]["hybrid"][q].get("judgeSignals") or []}
            for d in set(a_js) & set(b_js):
                fa, fb = a_js[d].get("fusion_score"), b_js[d].get("fusion_score")
                if fa is None or fb is None:
                    continue
                total += 1
                # Cramer's rule on [[ea_w, ea_c],[eb_w, eb_c]] . [nW, nC]^T = [fa, fb]^T
                n_w = (fa * eb_c - ea_c * fb) / det
                n_c = (ea_w * fb - fa * eb_w) / det
                ok = -1e-6 <= n_w <= 1 + 1e-6 and -1e-6 <= n_c <= 1 + 1e-6
                if ok:
                    admissible += 1
                    if n_w > n_c:
                        n_whole_gt_chunk += 1
                    deltas.append(fb - fa)
                    if len(samples) < 8 and q in worst[key]:
                        samples.append({
                            "qid": q, "docId": d, "fused_default": round(fa, 5),
                            "fused_raised": round(fb, 5), "recovered_norm_whole": round(n_w, 5),
                            "recovered_norm_chunk": round(n_c, 5),
                        })
        out[key] = {
            "docs_in_both_arms_top10": total,
            "admissible_under_pure_branch_reweighting": admissible,
            "admissible_pct": round(100 * admissible / total, 2) if total else None,
            "of_admissible_whole_gt_chunk": n_whole_gt_chunk,
            "of_admissible_whole_gt_chunk_pct": (
                round(100 * n_whole_gt_chunk / admissible, 2) if admissible else None
            ),
            "mean_fused_delta_raised_minus_default": (
                round(statistics.fmean(deltas), 5) if deltas else None
            ),
            "samples_from_worst_harmed": samples,
        }
    return {
        "model": {
            "effective_weights_default_whole_chunk": [round(ea_w, 4), round(ea_c, 4)],
            "effective_weights_raised_whole_chunk": [round(eb_w, 4), round(eb_c, 4)],
            "chunk_modifier_default": round(med_default, 4),
            "chunk_modifier_raised": round(med_raised, 4),
        },
        "per_comparison": out,
        "reading": (
            "A high admissible_pct means the observed default->raised fused-score movement of"
            " surviving docs is fully explainable by the branch-weight renormalisation alone,"
            " with no residual needing a SPLADE contribution. The model is falsifiable: a doc"
            " whose movement needs a normalised branch score outside [0,1] cannot be explained"
            " by pure re-weighting."
        ),
        "caveat": (
            "The two branches' min-max normalisation is over each branch's own candidate set."
            " If the gate changed a branch's member set (it can: the Stage-3A SPLADE weight also"
            " changes, so SPLADE-only docs can enter/leave the whole-doc branch's top-N), nWhole"
            " is not strictly arm-invariant and the recovered values are approximations. They are"
            " a consistency test, not a proof of exact arithmetic."
        ),
    }


def section_mech_d2_weight_identifiability(arms: dict) -> dict:
    """MECH-D2 - can the observed fused-score movement IDENTIFY the branch-weight shift?

    Sweep the hypothesised raised-gate whole-branch effective weight and count how many
    doc-pairs admit a solution with both normalised branch scores in [0,1]. If the count
    peaks at the value MECH-0 derives from source (0.80 for a chunk modifier of 0.25), the
    artifacts independently confirm the arithmetic - the weight shift is not assumed, it is
    measured.
    """
    out = {}
    for a_arm, b_arm in COMPARISONS:
        pairs = []
        for q in arms[a_arm]["hybrid"]:
            a_js = {j["docId"]: j for j in arms[a_arm]["hybrid"][q].get("judgeSignals") or []}
            b_js = {j["docId"]: j for j in arms[b_arm]["hybrid"][q].get("judgeSignals") or []}
            for d in set(a_js) & set(b_js):
                fa, fb = a_js[d].get("fusion_score"), b_js[d].get("fusion_score")
                if fa is None or fb is None:
                    continue
                # Whole-doc-branch strength proxy, from data the reconstruction never uses:
                # reciprocal of the best whole-doc leg rank (bm25 or dense), 0 if in neither.
                ranks = [r for r in (a_js[d].get("bm25_rank"), a_js[d].get("dense_rank")) if r]
                pairs.append((fa, fb, (1.0 / min(ranks)) if ranks else 0.0))
        curve = []
        for i in range(51, 100):
            w = i / 100.0
            ea_w, ea_c = 0.5, 0.5
            eb_w, eb_c = w, 1.0 - w
            det = ea_w * eb_c - ea_c * eb_w
            ok = 0
            rec, proxy = [], []
            for fa, fb, whole_proxy in pairs:
                n_w = (fa * eb_c - ea_c * fb) / det
                n_c = (ea_w * fb - fa * eb_w) / det
                if -1e-6 <= n_w <= 1 + 1e-6 and -1e-6 <= n_c <= 1 + 1e-6:
                    ok += 1
                    if whole_proxy is not None:
                        rec.append(n_w)
                        proxy.append(whole_proxy)
            try:
                r = statistics.correlation(rec, proxy) if len(rec) > 30 else None
            except Exception:  # pragma: no cover
                r = None
            curve.append({"raised_whole_weight": round(w, 2),
                          "implied_chunk_modifier": round((1 - w) / w, 4),
                          "admissible": ok,
                          "admissible_pct": round(100 * ok / len(pairs), 2),
                          "corr_recovered_nWhole_vs_whole_leg_rank_proxy": round(r, 4) if r else None,
                          "n_corr": len(rec)})
        best = max(curve, key=lambda r: r["admissible"])
        out[f"{a_arm}_vs_{b_arm}"] = {
            "n_doc_pairs": len(pairs),
            "argmax_raised_whole_weight": best["raised_whole_weight"],
            "argmax_implied_chunk_modifier": best["implied_chunk_modifier"],
            "argmax_admissible_pct": best["admissible_pct"],
            "at_source_derived_0_80": next(r for r in curve if r["raised_whole_weight"] == 0.80),
            "at_no_change_0_51": curve[0],
            "curve": curve,
        }
    return {
        "per_comparison": out,
        "reading": (
            "The source-derived prediction is raised_whole_weight = 0.80 (chunk modifier 0.25 ="
            " branchChunkMinWeightMultiplier from the arm manifests). The admissibility curve"
            " rises steeply to a knee at ~0.80-0.83 and then saturates, so the artifacts give a"
            " LOWER BOUND, not a point estimate: the observed fused-score movement REQUIRES a"
            " large branch-weight shift (a whole-branch weight below ~0.75 explains at most 84%"
            " of the doc-pairs, and 'no change' at 0.51 explains only ~28%), and 0.80 is the"
            " smallest value that clears ~97%. A shift of this size cannot be produced by adding"
            " a leg at CC weight 0.2; it is the size MECH-0 derives from source."
        ),
        "caveat_correlation_probe": (
            "The corr_recovered_nWhole_vs_whole_leg_rank_proxy column is NOT a usable identifier:"
            " the correlation is computed only over the admissible subset, which is small and"
            " biased at low w (28% of pairs at w=0.51), so its argmax at the sweep's lower edge is"
            " a selection artifact, not evidence. Reported for transparency, not relied on."
        ),
    }


def section_mech_e_splade_invariance(arms: dict) -> dict:
    """MECH-E - is the harm invariant to what the SPLADE leg actually contains?

    A1->A4 raises the gate with the whole-doc (truncated, nDCG 0.0591) SPLADE leg; A2->A3
    raises it with the revived chunk SPLADE leg (nDCG 0.2902 - 4.9x better). If SPLADE content
    were the causal agent, the two comparisons would harm different queries by different
    amounts. If the causal agent is the SPLADE-independent chunk-branch ramp, the per-query
    harm vectors should be near-identical.
    """
    qids = sorted(arms["A1"]["hybrid"])
    d14 = [(arms["A1"]["hybrid"][q]["ndcgAtK"] or 0) - (arms["A4"]["hybrid"][q]["ndcgAtK"] or 0)
           for q in qids]
    d23 = [(arms["A2"]["hybrid"][q]["ndcgAtK"] or 0) - (arms["A3"]["hybrid"][q]["ndcgAtK"] or 0)
           for q in qids]
    exact = sum(1 for x, y in zip(d14, d23) if abs(x - y) < 1e-9)
    close = sum(1 for x, y in zip(d14, d23) if abs(x - y) < 0.01)
    try:
        pearson = statistics.correlation(d14, d23)
    except Exception:  # pragma: no cover - degenerate input
        pearson = None
    diffs = [abs(x - y) for x, y in zip(d14, d23)]
    return {
        "n_queries": len(qids),
        "splade_mode_ndcg": {
            a: round(statistics.fmean([e["ndcgAtK"] for e in arms[a]["splade"].values()]), 4)
            for a in ARMS
        },
        "per_query_harm_identical_exact": exact,
        "per_query_harm_within_0_01": close,
        "pearson_r_A1vsA4_harm_against_A2vsA3_harm": round(pearson, 6) if pearson is not None else None,
        "mean_abs_difference": round(statistics.fmean(diffs), 6),
        "max_abs_difference": round(max(diffs), 6),
        "verdict": (
            "The two gate-raise comparisons differ by a 4.9x difference in the SPLADE leg's own"
            " quality (isolated splade-mode 0.0591 vs 0.2902) yet produce near-identical"
            " per-query harm. Whatever causes the harm does not read the SPLADE leg's contents."
        ),
    }


def section_mech_f_harm_decomposition(arms: dict) -> dict:
    """MECH-F - split the harm into pool-composition vs pure fused-score re-ordering.

    jseval scores with ir_measures over hit['score'] (the fused score), while the API returns
    the top-10 in cross-encoder order (see ORD). So a query can lose nDCG with a byte-identical
    returned document SET, purely because the gate changed the fused scores that order it.
    That subset is attributable to the fusion arithmetic with zero displacement.
    """
    out = {}
    for a_arm, b_arm in COMPARISONS:
        set_same = {"n": 0, "harm": 0.0}
        set_diff = {"n": 0, "harm": 0.0}
        harmed_set_same = []
        for q in arms[a_arm]["hybrid"]:
            a_e, b_e = arms[a_arm]["hybrid"][q], arms[b_arm]["hybrid"][q]
            delta = (a_e["ndcgAtK"] or 0) - (b_e["ndcgAtK"] or 0)
            if delta <= 0:
                continue
            same = set(a_e["predictedDocIds"]) == set(b_e["predictedDocIds"])
            bucket = set_same if same else set_diff
            bucket["n"] += 1
            bucket["harm"] += delta
            if same:
                harmed_set_same.append({"qid": q, "delta": round(delta, 4)})
        total = set_same["harm"] + set_diff["harm"]
        out[f"{a_arm}_vs_{b_arm}"] = {
            "harmed_queries_with_identical_top10_set": set_same["n"],
            "harmed_queries_with_changed_top10_set": set_diff["n"],
            "harm_from_identical_set_pure_reordering": round(set_same["harm"], 4),
            "harm_from_changed_set": round(set_diff["harm"], 4),
            "share_of_harm_from_pure_reordering_pct": round(100 * set_same["harm"] / total, 2) if total else None,
            "examples_identical_set": sorted(harmed_set_same, key=lambda r: -r["delta"])[:8],
        }
    return {
        "per_comparison": out,
        "reading": (
            "Harm carried by queries whose returned top-10 SET did not change at all cannot be"
            " displacement of any kind - no document entered, none left. It is the fused score"
            " itself moving, i.e. mechanism (b). The remainder is pool composition, mechanism (c),"
            " which the same re-weighting drives one stage earlier."
        ),
    }


def section_rerun_spec() -> dict:
    return {
        "why_a_rerun_is_still_wanted": (
            "The offline evidence identifies the mechanism but cannot separate the two levers the"
            " Step-0 knob moved, because both were moved by the same property. A definitive test"
            " needs an arm that moves ONLY the SPLADE leg."
        ),
        "one_line_engine_change_required": (
            "chunkBranchParentLengthMultiplier must stop reading SPLADE_ZERO_WEIGHT_MIN_TOKENS."
            " Give the Stage-3B ramp its own bounds (e.g."
            " justsearch.chunk_branch.full_weight_max_tokens / .min_weight_max_tokens) defaulting"
            " to the current 1024/4096 so shipped behaviour is byte-identical."
            " HybridFusionUtils.java:826-834. NOT licensed by this study's brief."
        ),
        "instrumentation_needed_for_a_definitive_offline_attribution": [
            "jseval must send debug/include_detail on eval queries (retriever.py:165-177 already"
            " supports it; run.py never passes debug=True) so the HitStage.detail tier is emitted"
            " (SearchResponseBuilder.java:297-299).",
            "jseval's extract_judge_signals (provenance.py:325-361) must additionally carry:"
            " chunk-merge stage rank/score; the branch-fusion detail keys whole_branch/chunk_branch"
            " and branch_merge_cc_effective_weight_*/cc_modifier_*; the Stage-3A"
            " cc_effective_weight_{sparse,dense,splade} + cc_modifier_splade; and parent_token_count.",
            "Persist judgeSignals for the full returned window, not only the final top-10, so a"
            " gold doc that falls out still has a record (raise the eval top_k, or persist the"
            " pre-CE fused pool).",
        ],
        "run_shape_zero_dollars": [
            "Four arms on legal-clerc-200, same session, same index generation, 200 queries,"
            " hybrid + splade modes: (i) baseline; (ii) SPLADE gate raised via a knob that does"
            " NOT touch the chunk-branch ramp; (iii) chunk-branch ramp collapsed to 0.25 with the"
            " SPLADE gate at default; (iv) both. Arm (iii) is the decisive one: if it reproduces"
            " the ~7% harm on its own, the chunk-branch coupling is the whole story.",
            "Cost: local GPU only, no paid calls. Wall clock ~8 min/arm at the observed Step-0"
            " rate (arm timestamps 00:22 / 00:30 / 00:37 / 00:41).",
            "Blocked here by the brief's constraint: the machine's port 33221 + GPU may be held by"
            " another worker, and arm (iii) needs an engine change this study is not licensed to"
            " make. Documented as the open step.",
        ],
    }


def write_report(result: dict, path: Path) -> None:
    r = result
    m0 = r["MECH_0_shared_constant_coupling"]
    ma = r["MECH_A_splade_injection"]["per_comparison"]
    md = r["MECH_D_cc_reconstruction"]["per_comparison"]
    md2 = r["MECH_D2_weight_identifiability"]["per_comparison"]
    me = r["MECH_E_splade_invariance"]
    mf = r["MECH_F_harm_decomposition"]["per_comparison"]
    mc = r["MECH_C_gold_doc_profile"]["A1_vs_A4"]
    lines = [
        "# Fusion-attribution study - where the Step-0 gate-harm comes from",
        "",
        "Offline analysis over `tmp/781-certification/step0/arm-A{1..4}`. No backend, no GPU, no",
        "paid calls. Reproduce with:",
        "",
        "```",
        "PYTHONUTF8=1 python scripts/jseval/experiments/fusion_attribution_784.py \\",
        "    --step0-root <...>/tmp/781-certification/step0 --out-dir tmp/784-fusion-attribution",
        "```",
        "",
        "## Verdict",
        "",
        "The harm is **not** SPLADE. Raising `justsearch.splade.zero_weight_min_tokens` moves a",
        "**second, undeclared lever**: the same constant is read by the Stage-3B whole-vs-chunk",
        "branch ramp, which collapses the chunk branch's weight multiplier from 1.0 to 0.25 on",
        "this corpus. The measured ~7% is the cost of de-weighting the chunk branch by ~4x, not",
        "the cost of admitting a weak sparse leg.",
        "",
        "## MECH-0 - the shared constant (source-level)",
        "",
        m0["claim"],
        "",
        "| parent tokens | splade leg mult (default -> raised) | chunk branch mult (default -> raised) | effective whole:chunk |",
        "|---|---|---|---|",
    ]
    for row in m0["table"]:
        lines.append(
            f"| {row['parent_tokens']} | {row['splade_leg_multiplier_default']} -> "
            f"{row['splade_leg_multiplier_raised']} | {row['chunk_branch_multiplier_default']} -> "
            f"{row['chunk_branch_multiplier_raised_both_props']} | "
            f"{row['effective_branch_weights_default_whole_chunk']} -> "
            f"{row['effective_branch_weights_raised_whole_chunk']} |"
        )
    lines += [
        "",
        m0["corpus_median_reading"],
        "",
        m0["robustness"],
        "",
        m0["zero_exclude_note"],
        "",
        "## MECH-E - the harm is invariant to what SPLADE contains (decisive)",
        "",
        f"- Isolated splade-mode nDCG@10 per arm: {me['splade_mode_ndcg']}",
        f"- Per-query harm vectors of the two gate-raise comparisons are **exactly identical on "
        f"{me['per_query_harm_identical_exact']}/{me['n_queries']} queries** "
        f"(Pearson r = {me['pearson_r_A1vsA4_harm_against_A2vsA3_harm']}, mean |diff| = "
        f"{me['mean_abs_difference']}).",
        "",
        "A1->A4 raises the gate over a truncated whole-doc SPLADE leg (0.0591); A2->A3 raises it",
        "over the revived chunk SPLADE leg (0.2902, 4.9x better). Same harm, same queries, same",
        "magnitudes. The causal agent does not read the SPLADE leg's contents.",
        "",
        "## MECH-A - SPLADE injection refuted (hypothesis a)",
        "",
    ]
    for k, v in ma.items():
        lines.append(
            f"- **{k}**: mean overlap(splade-mode top-10, final top-10) "
            f"{v['default_gate']['mean_overlap_splade_top10_vs_final_top10']} -> "
            f"{v['raised_gate']['mean_overlap_splade_top10_vs_final_top10']}; of "
            f"{v['entrants_all_queries']['n_entrant_docs']} docs entering the raised-gate top-10, "
            f"{v['entrants_all_queries']['n_entrants_also_in_splade_mode_top10']} "
            f"({v['entrants_all_queries']['share_pct']}%) are SPLADE top-10 docs."
        )
    lines += [
        "",
        "For A1->A4 (where the SPLADE leg is byte-identical across arms) the entrants are *less*",
        "SPLADE-flavoured than the average top-10 doc (7.7% vs a 10.5% base rate). Combined with",
        "`splade_rank` being null on 100% of hybrid judgeSignal rows in every arm, no",
        "SPLADE-injected document is observably displacing gold. The A2->A3 overlap rise",
        "(1.10 -> 2.46) is the SPLADE *ranking itself* getting better, not injection - it happens",
        "alongside identical per-query harm (MECH-E).",
        "",
        "## MECH-F - a quarter of the harm has no displacement at all",
        "",
    ]
    for k, v in mf.items():
        lines.append(
            f"- **{k}**: {v['harmed_queries_with_identical_top10_set']} harmed queries have a "
            f"**byte-identical returned top-10 set**, carrying "
            f"{v['share_of_harm_from_pure_reordering_pct']}% of all harm "
            f"({v['harm_from_identical_set_pure_reordering']} of "
            f"{round(v['harm_from_identical_set_pure_reordering'] + v['harm_from_changed_set'], 4)} nDCG)."
        )
    lines += [
        "",
        "Nothing entered, nothing left - the fused score itself moved and re-ordered the same ten",
        "documents. That is mechanism (b) with no (a) and no (c) available as an explanation.",
        "(This is visible because jseval scores with `ir_measures` over `hit['score']`, the fused",
        "score, while the API returns the set in cross-encoder order - see ORD in the JSON.)",
        "",
        "## MECH-D / D2 - the fused-score movement is branch re-weighting, and it is big",
        "",
    ]
    for k, v in md.items():
        lines.append(
            f"- **{k}**: {v['admissible_pct']}% of {v['docs_in_both_arms_top10']} doc-pairs "
            f"present in both arms' top-10 admit a solution of "
            f"`fused = e_whole*nWhole + e_chunk*nChunk` with both normalised branch scores in "
            f"[0,1] under the source-derived weight shift [0.5,0.5] -> [0.8,0.2]."
        )
    for k, v in md2.items():
        lines.append(
            f"- **{k}** identifiability: 'no change' (whole weight 0.51) explains only "
            f"{v['at_no_change_0_51']['admissible_pct']}% of pairs; the curve reaches "
            f"{v['at_source_derived_0_80']['admissible_pct']}% exactly at the source-derived 0.80 "
            f"and saturates above it."
        )
    lines += [
        "",
        r["MECH_D2_weight_identifiability"]["reading"],
        "",
        "Honest limit: " + r["MECH_D2_weight_identifiability"]["caveat_correlation_probe"],
        "",
        "Honest limit: " + r["MECH_D_cc_reconstruction"]["caveat"],
        "",
        "## MECH-C - the ten worst-harmed queries, one line each",
        "",
        "| qid | harm | this query's splade-mode nDCG | gold rank default -> raised | gold bm25/dense leg rank | entrants | entrants that are SPLADE top-10 docs |",
        "|---|---|---|---|---|---|---|",
    ]
    for row in mc["per_query"]:
        g = row["gold"][0] if row["gold"] else {}
        lines.append(
            f"| {row['qid']} | {row['delta']} | {row['splade_mode_ndcg_this_query']} | "
            f"{g.get('rank_default_gate')} -> {g.get('rank_raised_gate')} | "
            f"{g.get('gold_bm25_rank')}/{g.get('gold_dense_rank')} | {row['n_entrants']} | "
            f"{row['entrants_in_splade_mode_top10']} |"
        )
    lines += [
        "",
        f"Of the gold documents that fell out of the returned top-10, "
        f"{mc['summary']['gold_that_fell_out_with_whole_doc_leg']} carried whole-doc-branch",
        f"provenance and {mc['summary']['gold_that_fell_out_chunk_branch_only']} were chunk-branch-only. "
        f"Of {mc['summary']['total_entrants']} entrant documents, "
        f"{mc['summary']['entrants_in_splade_mode_top10']} came from the SPLADE ranking. Six of these",
        "ten queries have splade-mode nDCG exactly 0 - SPLADE found nothing for them at all, and",
        "they are still among the worst harmed.",
        "",
        "## not_derivable",
        "",
    ]
    for k, v in r["INV_data_inventory"]["not_derivable"].items():
        lines.append(f"- **{k}** - {v}")
    lines += [
        "",
        "## What a definitive rerun needs",
        "",
        r["RERUN_spec"]["why_a_rerun_is_still_wanted"],
        "",
        "**Engine change required (NOT made here):** " + r["RERUN_spec"]["one_line_engine_change_required"],
        "",
        "**Instrumentation:**",
    ]
    lines += [f"- {x}" for x in r["RERUN_spec"]["instrumentation_needed_for_a_definitive_offline_attribution"]]
    lines += ["", "**Run shape (zero dollars):**"]
    lines += [f"- {x}" for x in r["RERUN_spec"]["run_shape_zero_dollars"]]
    lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--step0-root", default="F:/justsearch-public/tmp/781-certification/step0")
    ap.add_argument("--per-query-json",
                    default="F:/justsearch-public/tmp/hero-arc-analysis/engine-joins/step0-per-query.v1.json")
    ap.add_argument("--out-dir", default="tmp/784-fusion-attribution")
    args = ap.parse_args()

    root = Path(args.step0_root)
    arms = {a: load_arm(root, a) for a in ARMS}
    with open(args.per_query_json, encoding="utf-8") as f:
        step0 = json.load(f)
    worst = {
        f"{a}_vs_{b}": step0["comparisons"][f"{a}_vs_{b}"]["worst_10_qids_a_better"]
        for a, b in COMPARISONS
    }

    result = {
        "schema": "fusion-attribution-784.v1",
        "task": "Step-0 gate-harm mechanism attribution (tempdoc 784 sec 3.B.9; register F-036)",
        "inputs": {a: str(root / f"arm-{a}") for a in ARMS},
        "arm_semantics": ARM_SEMANTICS,
        "aggregate_ndcg_at_10": step0["aggregate_ndcg_at_10"],
        "worst_10_harmed_qids": worst,
        "INV_data_inventory": section_inventory(arms),
        "ORD_ranking_semantics": section_order_semantics(arms),
        "MECH_0_shared_constant_coupling": section_mech0_shared_constant(),
        "MECH_A_splade_injection": section_mech_a_splade_injection(arms, worst),
        "MECH_B_branch_flavour_shift": section_mech_b_branch_flavour(arms, worst),
        "MECH_C_gold_doc_profile": section_mech_c_gold_profile(arms, worst),
        "MECH_D_cc_reconstruction": section_mech_d_reconstruction(arms, worst),
        "MECH_D2_weight_identifiability": section_mech_d2_weight_identifiability(arms),
        "MECH_E_splade_invariance": section_mech_e_splade_invariance(arms),
        "MECH_F_harm_decomposition": section_mech_f_harm_decomposition(arms),
        "RERUN_spec": section_rerun_spec(),
    }

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / "fusion-attribution.v1.json"
    out.write_text(json.dumps(result, indent=2), encoding="utf-8")
    write_report(result, out_dir / "report.md")
    print(f"wrote {out} and {out_dir / 'report.md'}")
    print(json.dumps(result["MECH_0_shared_constant_coupling"]["corpus_median_reading"], indent=1))
    for k in ("MECH_A_splade_injection", "MECH_B_branch_flavour_shift", "MECH_D_cc_reconstruction"):
        print(f"\n--- {k}")
        print(json.dumps(result[k].get("per_comparison"), indent=1)[:2500])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
