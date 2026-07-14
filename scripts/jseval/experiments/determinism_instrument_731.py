#!/usr/bin/env python3
"""Determinism instrument: top-K overlap + rank-shift + gating-flip diff across two ingest
passes over the SAME corpus (tempdoc 731 Increment I5 / Live-ask A2).

QUESTION (tempdoc 731 DESIGN §3.2, hypothesis H4-B): a rank-1-to-outside-top-10 swing
between two clean re-ingests of the same corpus is INHERENT HNSW/ANN neighbor-jitter (bound
and disclose, do not chase to zero) UNLESS the low-signal gating
(`HybridSearchOps.computeLowSignalGating`, `HybridSearchOps.java:157-206`; fusion vectorWeight
0.75 high-signal / 0.3 low-signal, `:42,:50`) is a THRESHOLD-KEYED AMPLIFIER: if a rebuild's
dense/BM25 top score crosses the low-signal threshold, the fusion weight flips for the WHOLE
query and turns small inherent ANN variance into a large rank swing (the profile the druker7
case matches). This script does not decide H4-B by itself — it produces the per-query
overlap/rank-shift/gating-flip evidence an operator reads to confirm or refute it.

SCOPE NOTE — why this lives in experiments/, not a new jseval subcommand: tempdoc 731 frames
I5 as "run once to classify, kept as regression guard" (its own words), not a permanent CLI
surface. jseval's command inventory is pinned by a sync test
(scripts/jseval/tests/test_command_surface.py) — adding a subcommand for a single-decision
instrument is the wrong tier. This follows the established experiments/ convention
(encoder_bakeoff_708.py, splade_chunk_truncation_check_712.py): a standalone script, run
manually; its JSON output is the artifact, not a durable CLI surface. The compare LOGIC is
still unit-tested (scripts/jseval/tests/test_determinism_instrument_731.py) so the "regression
guard" half of the framing holds without a CLI-surface commitment.

INPUT SHAPES (both auto-detected by `--compare`; verified against the actual wire records,
not guessed — file:line citations below):

  1. Raw-capture directory (this script's own `--capture` mode writes this): one JSON file
     per query, `<qid>.json`, holding exactly the POST /api/knowledge/search response body
     (`KnowledgeSearchResponse.java:16-40`) — `results[]` (each hit:
     `{id, score, trace: [HitStage...]}`, `KnowledgeSearchResponse.java:56-66`) and
     `searchTrace` (`{effectiveMode, decisionKind, degradation, stages}`,
     `SearchTrace.java:31-42`). This is the ONLY shape with per-hit stage `detail` maps (the
     Tier-2 gating derivation below needs it), which requires the request to set
     `"debug": true` (`KnowledgeSearchController.java:319-323`); `--capture` always sets it.

  2. A jseval run dir's `{mode}_per_query.json` — read verbatim from the actual writer
     (`scripts/jseval/jseval/artifacts.py:227-253`) and the judge-signal reader
     (`scripts/jseval/jseval/provenance.py:312-348`, `extract_judge_signals`), not assumed.
     Each entry carries `qid`, `predictedDocIds` (ranked doc-id list), `decision_kind`,
     `effectiveMode`, and `judgeSignals` (per-hit bm25/splade/dense/fusion/ce rank+score) —
     but NOT the raw per-leg RRF detail keys (`extract_judge_signals` does not surface them),
     so a jseval-run-dir pass can only judge gating via the Tier-1 coarse signal below, never
     Tier 2. The comparison report states which tier was actually used per query.

GATING-DECISION FIDELITY (per-pass, per-query) — two tiers, because the raw low-signal
vectorWeight is NOT a first-class wire field. Verified: `HybridFusionUtils.fuseWithRRFNamed`'s
debug-only `hitScores` emit `{leg}_rank` / `{leg}_rrf` / `rrf_base` / `rrf`
(`HybridFusionUtils.java:295-315`) — never the raw weight scalar itself. (The `cc_weight_*`
keys DO carry a raw weight, but those belong to the CONVEX-COMBINATION fusion path
(`fuseWithCC`/`fuseWithCC3`, `HybridFusionUtils.java:552-559,752-756`), a different algorithm
from the RRF path the low-signal gating actually feeds (`HybridSearchOps.java:474-484` calls
`fuseWithRRF` with `gating.vectorWeight()`) — tempdoc 731 §3.2 itself notes RRF, not CC, is
the default path this hypothesis concerns. Conflating the two would silently read the wrong
number.)

  Tier 1 (coarse, always available in BOTH input shapes): `searchTrace.decisionKind` /
    `searchTrace.effectiveMode` (raw capture) or `decision_kind` / `effectiveMode` (jseval
    per_query.json). A change in either between passes is necessary-but-not-sufficient
    evidence of the specific low-signal weight flip — it is the fallback tier.

  Tier 2 (fine, raw-capture only, opportunistic): DERIVE the vectorWeight algebraically from
    the dense leg's own debug scores. The default 2-leg RRF call hardcodes leg labels
    "sparse"/"vector" (`HybridFusionUtils.java:118-138`); `SearchResponseBuilder.
    classifyDebugKey` routes any `vector*`-prefixed debug key to the **dense-retrieval**
    HitStage's `detail` map, not the fusion stage's (`SearchResponseBuilder.java:345-362`).
    So: `vector_rrf = vectorWeight / (rrfK + vector_rank)`, i.e.
    `vectorWeight = vector_rrf * (rrfK + vector_rank)`, reading both keys from the FIRST
    hit's `dense-retrieval` HitStage detail map (the same weight applies uniformly to the
    whole query — `HybridSearchOps.java:474-484` passes one scalar into the whole fusion
    call). `rrfK` defaults to the code fallback constant 60
    (`HybridFusionUtils.java:191: k = hs != null ? hs.rrfK() : 60`) — pass `--rrf-k` if the
    target deployment's `hybridSearch.rrfK` config overrides it. Tier 2 is skipped
    (falls back to Tier 1) whenever CC/CC3 fusion is active, the query has no dense hits, or
    the capture did not set `debug: true`.
    low_signal := derived_vector_weight < --gating-weight-threshold (default 0.5, the
    midpoint of the 0.3/0.75 pair).

MODES:
  --capture   Hit a RUNNING backend's POST /api/knowledge/search with a query file and save
              one raw-response JSON per query. This is a CONSUMER of an already-leased dev
              stack — it issues HTTP requests only, never starts/stops/ingests anything (this
              instrument does not orchestrate the stack; the orchestrator's lease does).
  --compare   Read two already-captured directories/run-dirs and emit the overlap/rank-shift/
              gating-flip report (JSON + printed table). Decides H4-B (tempdoc 731 §I5).

DRIVER PROCEDURE (orchestrator-run, under one dev-stack lease; tempdoc 731 §Live asks A2):
  1. Clean ingest pass 1 of the target corpus (regression queries: druker7 + 725's q8/q14,
     tempdoc 731 §Live asks A2).
  2. python determinism_instrument_731.py --capture --queries <queries.json> \
       --base-url http://127.0.0.1:<port> --out tmp/731-determinism/pass-a
  3. Clean RE-ingest (same corpus, same config) — pass 2.
  4. python determinism_instrument_731.py --capture --queries <queries.json> \
       --base-url http://127.0.0.1:<port> --out tmp/731-determinism/pass-b
  5. python determinism_instrument_731.py --compare tmp/731-determinism/pass-a \
       tmp/731-determinism/pass-b --out tmp/731-determinism/report.json

QUERY FILE FORMAT (--queries): a JSON list of `{"qid": str, "query": str}` objects, OR a
BEIR-style `queries.jsonl` (one `{"_id": str, "text": str}` per line — the same shape
`splade_chunk_truncation_check_712.py:load_queries` and `encoder_bakeoff_708.py` read),
auto-detected by extension (`.jsonl`) or (for `.json`) top-level list vs NDJSON content.

USAGE:
  python determinism_instrument_731.py --capture --queries queries.json \
      --base-url http://127.0.0.1:8095 --out tmp/731-determinism/pass-a
  python determinism_instrument_731.py --compare tmp/731-determinism/pass-a \
      tmp/731-determinism/pass-b --out tmp/731-determinism/report.json
"""

from __future__ import annotations

import argparse
import json
import logging
import statistics
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

log = logging.getLogger("determinism_instrument_731")

DEFAULT_TOP_K = 10
DEFAULT_RANK_SHIFT_TOP_N = 3
DEFAULT_GATING_WEIGHT_THRESHOLD = 0.5
DEFAULT_RRF_K = 60  # HybridFusionUtils.java:191 code-fallback default for hybridSearch.rrfK


# ---------------------------------------------------------------------------
# Query file loading (--capture)
# ---------------------------------------------------------------------------

def load_queries(path: Path) -> list[dict]:
    """Load a query file as a list of ``{"qid": str, "query": str}``.

    Accepts a JSON list of ``{qid, query}`` objects, or a BEIR-style
    ``queries.jsonl`` (``{_id, text}`` per line), auto-detected.
    """
    text = path.read_text(encoding="utf-8")
    is_jsonl = path.suffix == ".jsonl" or (
        text.lstrip()[:1] == "{" and "\n{" in text and not text.lstrip().startswith("[")
    )
    out: list[dict] = []
    if is_jsonl:
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            qid = obj.get("qid", obj.get("_id"))
            query = obj.get("query", obj.get("text"))
            if qid is None or query is None:
                raise ValueError(f"query line missing qid/query (or _id/text): {line!r}")
            out.append({"qid": str(qid), "query": str(query)})
        return out

    obj = json.loads(text)
    if not isinstance(obj, list):
        raise ValueError(f"unrecognized queries file shape (expected a JSON list): {path}")
    for item in obj:
        qid = item.get("qid", item.get("_id"))
        query = item.get("query", item.get("text"))
        if qid is None or query is None:
            raise ValueError(f"query entry missing qid/query (or _id/text): {item!r}")
        out.append({"qid": str(qid), "query": str(query)})
    return out


# ---------------------------------------------------------------------------
# Capture mode — consumes an already-leased, running backend
# ---------------------------------------------------------------------------

def capture(
    base_url: str,
    queries: list[dict],
    out_dir: Path,
    *,
    top_k: int,
    mode: str | None,
    timeout: float,
) -> None:
    """POST each query to /api/knowledge/search (debug=true) and save the raw response.

    One consumer HTTP call per query against a stack the caller already leased — this
    function starts, stops, and ingests nothing.
    """
    import httpx  # local import: --compare-only usage (and all unit tests) never needs it

    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "base_url": base_url,
        "top_k": top_k,
        "mode": mode,
        "query_count": len(queries),
        "captured_at": datetime.now(timezone.utc).isoformat(),
    }
    with httpx.Client(base_url=base_url, timeout=timeout) as client:
        for i, q in enumerate(queries, 1):
            body: dict = {"query": q["query"], "limit": top_k, "debug": True}
            if mode:
                body["mode"] = mode
            resp = client.post("/api/knowledge/search", json=body)
            resp.raise_for_status()
            data = resp.json()
            (out_dir / f"{q['qid']}.json").write_text(
                json.dumps(data, indent=2), encoding="utf-8"
            )
            if i % 25 == 0 or i == len(queries):
                log.info("captured %d/%d", i, len(queries))
    (out_dir / "_manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")


# ---------------------------------------------------------------------------
# Normalized per-query record (both input shapes fold into this)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class QueryRecord:
    qid: str
    doc_ids: list[str]
    decision_kind: str | None
    effective_mode: str | None
    gating_weight: float | None  # Tier-2 derived vectorWeight; None when unavailable
    source: str  # "raw-capture" | "jseval-per-query"


def _extract_gating_weight(results: list, rrf_k: float) -> float | None:
    """Tier-2 derivation — see module docstring for the algebraic derivation + caveats."""
    for hit in results:
        if not isinstance(hit, dict):
            continue
        for stage in hit.get("trace") or []:
            if not isinstance(stage, dict) or stage.get("id") != "dense-retrieval":
                continue
            detail = stage.get("detail") or {}
            rrf = detail.get("vector_rrf")
            rank = detail.get("vector_rank")
            if rrf is None or rank is None:
                continue
            try:
                return float(rrf) * (rrf_k + float(rank))
            except (TypeError, ValueError):
                continue
    return None


def _load_raw_capture_dir(dir_path: Path, *, rrf_k: float) -> dict[str, QueryRecord]:
    records: dict[str, QueryRecord] = {}
    for f in sorted(dir_path.glob("*.json")):
        if f.name.startswith("_"):
            continue  # _manifest.json and any other sidecar
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            raise ValueError(f"malformed capture file {f}: {e}") from e
        qid = f.stem
        results = data.get("results") or []
        doc_ids = [h.get("id") for h in results if isinstance(h, dict) and h.get("id")]
        trace = data.get("searchTrace") or {}
        records[qid] = QueryRecord(
            qid=qid,
            doc_ids=doc_ids,
            decision_kind=trace.get("decisionKind"),
            effective_mode=trace.get("effectiveMode"),
            gating_weight=_extract_gating_weight(results, rrf_k),
            source="raw-capture",
        )
    return records


def _select_mode_file(files: list[Path], mode: str | None) -> Path:
    if mode:
        for f in files:
            if f.stem == f"{mode}_per_query":
                return f
        raise ValueError(
            f"--mode {mode!r} not found; available: {sorted(f.stem for f in files)}"
        )
    if len(files) > 1:
        raise ValueError(
            "multiple *_per_query.json modes found "
            f"({sorted(f.name for f in files)}); pass a --pass-*-mode to disambiguate"
        )
    return files[0]


def _load_jseval_run_dir(
    dir_path: Path, per_query_files: list[Path], mode: str | None
) -> dict[str, QueryRecord]:
    chosen = _select_mode_file(per_query_files, mode)
    entries = json.loads(chosen.read_text(encoding="utf-8"))
    records: dict[str, QueryRecord] = {}
    for e in entries:
        qid = str(e.get("qid"))
        records[qid] = QueryRecord(
            qid=qid,
            doc_ids=list(e.get("predictedDocIds") or []),
            decision_kind=e.get("decision_kind"),
            effective_mode=e.get("effectiveMode"),
            gating_weight=None,  # Tier 2 unavailable from this artifact — see docstring
            source="jseval-per-query",
        )
    return records


def load_pass(dir_path: Path, *, mode: str | None = None, rrf_k: float = DEFAULT_RRF_K) -> dict[str, QueryRecord]:
    """Load one pass directory, auto-detecting its shape (see module docstring)."""
    per_query_files = sorted(dir_path.glob("*_per_query.json"))
    if per_query_files:
        return _load_jseval_run_dir(dir_path, per_query_files, mode)
    return _load_raw_capture_dir(dir_path, rrf_k=rrf_k)


# ---------------------------------------------------------------------------
# Compare logic (pure functions — unit-tested with synthetic fixtures)
# ---------------------------------------------------------------------------

def jaccard(a_ids: list[str], b_ids: list[str], k: int) -> float:
    """Jaccard overlap of the top-k doc-id sets. Two empty top-k sets are defined as 1.0
    (perfect agreement on "nothing"), not 0/0."""
    sa, sb = set(a_ids[:k]), set(b_ids[:k])
    if not sa and not sb:
        return 1.0
    return len(sa & sb) / len(sa | sb)


def rank_of(doc_id: str, ids: list[str]) -> int | None:
    """1-based rank of doc_id in ids, or None if absent."""
    try:
        return ids.index(doc_id) + 1
    except ValueError:
        return None


def rank_shifts(a_ids: list[str], b_ids: list[str], top_n: int) -> list[dict]:
    """Rank displacement of each of pass-A's first ``top_n`` docs, as seen in pass B."""
    shifts = []
    for doc_id in a_ids[:top_n]:
        rank_a = rank_of(doc_id, a_ids)
        rank_b = rank_of(doc_id, b_ids)
        dropped = rank_b is None
        shifts.append(
            {
                "doc_id": doc_id,
                "rank_a": rank_a,
                "rank_b": rank_b,
                "shift": None if dropped else rank_b - rank_a,
                "dropped": dropped,
            }
        )
    return shifts


def swing_magnitude(shifts: list[dict], *, out_of_topk_floor: int) -> int:
    """Largest observed rank displacement among the tracked docs.

    A doc dropped entirely out of pass B's captured window has an unknown true rank; it
    contributes a FLOOR of ``out_of_topk_floor - rank_a`` (documented as a lower bound, not
    the true displacement) rather than being silently excluded.
    """
    mags = []
    for s in shifts:
        if s["dropped"]:
            mags.append(max(0, out_of_topk_floor - (s["rank_a"] or 0)))
        elif s["shift"] is not None:
            mags.append(abs(s["shift"]))
    return max(mags) if mags else 0


def gating_low_signal(rec: QueryRecord, threshold: float) -> bool | None:
    if rec.gating_weight is None:
        return None
    return rec.gating_weight < threshold


def gating_differs(a: QueryRecord, b: QueryRecord, threshold: float) -> dict:
    tier2_a = gating_low_signal(a, threshold)
    tier2_b = gating_low_signal(b, threshold)
    tier2_flip = None
    if tier2_a is not None and tier2_b is not None:
        tier2_flip = tier2_a != tier2_b
    tier1_flip = (a.decision_kind != b.decision_kind) or (a.effective_mode != b.effective_mode)
    return {
        "tier": "fine" if tier2_flip is not None else "coarse",
        "tier2_low_signal_a": tier2_a,
        "tier2_low_signal_b": tier2_b,
        "tier2_gating_weight_a": a.gating_weight,
        "tier2_gating_weight_b": b.gating_weight,
        "tier2_flip": tier2_flip,
        "tier1_decision_kind_a": a.decision_kind,
        "tier1_decision_kind_b": b.decision_kind,
        "tier1_effective_mode_a": a.effective_mode,
        "tier1_effective_mode_b": b.effective_mode,
        "tier1_flip": tier1_flip,
        "flip": tier2_flip if tier2_flip is not None else tier1_flip,
    }


def _pearson_r(xs: list[float], ys: list[float]) -> float:
    mean_x, mean_y = statistics.fmean(xs), statistics.fmean(ys)
    cov = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys))
    var_x = sum((x - mean_x) ** 2 for x in xs)
    var_y = sum((y - mean_y) ** 2 for y in ys)
    denom = (var_x * var_y) ** 0.5
    return cov / denom if denom else 0.0


def _dominant_tier(per_query: list[dict]) -> str:
    tiers = {q["gating"]["tier"] for q in per_query}
    if not tiers:
        return "none"
    if len(tiers) == 1:
        return next(iter(tiers))
    return "mixed"


def _h4b_verdict(per_query: list[dict], aggregate: dict) -> dict:
    """Descriptive (not a significance test, per tempdoc 731 §I5) gating-flip vs
    rank-swing-magnitude comparison that decides H4-B."""
    flip_swings = [q["swing_magnitude"] for q in per_query if q["gating"]["flip"] is True]
    stable_swings = [q["swing_magnitude"] for q in per_query if q["gating"]["flip"] is False]

    result: dict = {
        "gating_flip_count": aggregate["gating_flip_count"],
        "flip_group_mean_swing": round(statistics.fmean(flip_swings), 4) if flip_swings else None,
        "stable_group_mean_swing": (
            round(statistics.fmean(stable_swings), 4) if stable_swings else None
        ),
        "correlation_r": None,
    }

    pairs = [
        (1.0 if q["gating"]["flip"] else 0.0, float(q["swing_magnitude"]))
        for q in per_query
        if q["gating"]["flip"] is not None
    ]
    if len(pairs) >= 3:
        flips = [p[0] for p in pairs]
        swings = [p[1] for p in pairs]
        if len(set(flips)) > 1 and len(set(swings)) > 1:
            result["correlation_r"] = round(_pearson_r(flips, swings), 4)

    if aggregate["gating_flip_count"] == 0:
        verdict = "no_gating_flips_observed"
        note = (
            "No query's gating decision changed between passes; H4-B's amplifier mechanism "
            "was not triggered by this run. This does not by itself confirm pure-ANN-jitter "
            "for whatever swings WERE observed (see per-query swing_magnitude) — only that "
            "the amplifier was not the cause here."
        )
    elif (
        result["flip_group_mean_swing"] is not None
        and result["stable_group_mean_swing"] is not None
        and result["flip_group_mean_swing"] > 2 * max(result["stable_group_mean_swing"], 1e-9)
    ):
        verdict = "confirmed"
        note = (
            "Gating-flip queries show a substantially larger mean rank swing than "
            "stable-gating queries — consistent with the low-signal threshold amplifying "
            "ANN variance (H4-B)."
        )
    elif result["flip_group_mean_swing"] is not None:
        verdict = "not_confirmed"
        note = (
            "Gating flips occurred but did not co-occur with a substantially larger rank "
            "swing than stable-gating queries in this run."
        )
    else:
        verdict = "inconclusive"
        note = (
            "Gating-flip queries were observed but no stable-gating comparison group exists "
            "in this run (every compared query flipped)."
        )
    result["verdict"] = verdict
    result["note"] = note
    return result


def compare_passes(
    pass_a: dict[str, QueryRecord],
    pass_b: dict[str, QueryRecord],
    *,
    top_k: int = DEFAULT_TOP_K,
    rank_shift_top_n: int = DEFAULT_RANK_SHIFT_TOP_N,
    gating_weight_threshold: float = DEFAULT_GATING_WEIGHT_THRESHOLD,
) -> dict:
    shared = sorted(set(pass_a) & set(pass_b))
    missing_in_a = sorted(set(pass_b) - set(pass_a))
    missing_in_b = sorted(set(pass_a) - set(pass_b))

    per_query = []
    for qid in shared:
        a, b = pass_a[qid], pass_b[qid]
        jac = jaccard(a.doc_ids, b.doc_ids, top_k)
        shifts = rank_shifts(a.doc_ids, b.doc_ids, rank_shift_top_n)
        swing = swing_magnitude(shifts, out_of_topk_floor=top_k + 1)
        gating = gating_differs(a, b, gating_weight_threshold)
        per_query.append(
            {
                "qid": qid,
                "jaccard_top_k": round(jac, 4),
                "top_k": top_k,
                "rank_shifts": shifts,
                "swing_magnitude": swing,
                "gating": gating,
            }
        )

    jaccards = [q["jaccard_top_k"] for q in per_query]
    aggregate = {
        "query_count": len(per_query),
        "missing_in_pass_a": missing_in_a,
        "missing_in_pass_b": missing_in_b,
        "jaccard_mean": round(statistics.fmean(jaccards), 4) if jaccards else None,
        "jaccard_median": round(statistics.median(jaccards), 4) if jaccards else None,
        "jaccard_min": round(min(jaccards), 4) if jaccards else None,
        "gating_flip_count": sum(1 for q in per_query if q["gating"]["flip"]),
        "gating_tier_used": _dominant_tier(per_query),
    }

    return {
        "per_query": per_query,
        "aggregate": aggregate,
        "h4b": _h4b_verdict(per_query, aggregate),
    }


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------

def print_report(report: dict) -> None:
    agg = report["aggregate"]
    print(
        f"Compared {agg['query_count']} shared queries "
        f"({len(agg['missing_in_pass_a'])} missing in A, "
        f"{len(agg['missing_in_pass_b'])} missing in B)"
    )
    print(f"{'qid':<24} {'jaccard@k':>10} {'swing':>6} {'gate_flip':>10} {'tier':>8}")
    for q in report["per_query"]:
        g = q["gating"]
        print(
            f"{q['qid']:<24} {q['jaccard_top_k']:>10.4f} {q['swing_magnitude']:>6} "
            f"{str(g['flip']):>10} {g['tier']:>8}"
        )
    print()
    print(
        f"jaccard: mean={agg['jaccard_mean']} median={agg['jaccard_median']} "
        f"min={agg['jaccard_min']}"
    )
    print(
        f"gating flips: {agg['gating_flip_count']}/{agg['query_count']} "
        f"(tier used: {agg['gating_tier_used']})"
    )
    h4b = report["h4b"]
    print(f"H4-B verdict: {h4b['verdict']} — {h4b['note']}")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    mode_group = p.add_mutually_exclusive_group(required=True)
    mode_group.add_argument(
        "--capture", action="store_true", help="Capture raw responses from a running backend."
    )
    mode_group.add_argument(
        "--compare",
        nargs=2,
        metavar=("PASS_A_DIR", "PASS_B_DIR"),
        help="Compare two already-captured pass directories (or jseval run dirs).",
    )
    p.add_argument(
        "--queries", type=Path,
        help="(--capture) query file: JSON list of {qid,query} or BEIR queries.jsonl.",
    )
    p.add_argument(
        "--base-url", default="http://127.0.0.1:8095",
        help="(--capture) backend base URL (default: %(default)s).",
    )
    p.add_argument(
        "--out", type=Path, required=True,
        help="Output directory (--capture) or report JSON path (--compare).",
    )
    p.add_argument(
        "--top-k", type=int, default=DEFAULT_TOP_K,
        help="Capture request limit / compare overlap window (default: %(default)s).",
    )
    p.add_argument(
        "--rank-shift-top-n", type=int, default=DEFAULT_RANK_SHIFT_TOP_N,
        help="(--compare) number of pass-A top docs tracked for rank displacement "
        "(default: %(default)s).",
    )
    p.add_argument(
        "--gating-weight-threshold", type=float, default=DEFAULT_GATING_WEIGHT_THRESHOLD,
        help="(--compare) Tier-2 low-signal classification threshold for the derived "
        "fusion vectorWeight (default: %(default)s, the 0.3/0.75 midpoint).",
    )
    p.add_argument(
        "--rrf-k", type=float, default=DEFAULT_RRF_K,
        help="(--compare, Tier-2) the server's hybridSearch.rrfK — see module docstring "
        "(default: %(default)s, the code fallback).",
    )
    p.add_argument(
        "--capture-mode", default=None,
        help="(--capture) explicit search 'mode' request field; omit for the server default.",
    )
    p.add_argument(
        "--pass-a-mode", default=None,
        help="(--compare) explicit jseval per_query mode name for pass A, if ambiguous.",
    )
    p.add_argument(
        "--pass-b-mode", default=None,
        help="(--compare) explicit jseval per_query mode name for pass B, if ambiguous.",
    )
    p.add_argument(
        "--timeout", type=float, default=30.0, help="(--capture) HTTP timeout seconds."
    )
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    if args.capture:
        if not args.queries:
            print("error: --capture requires --queries", file=sys.stderr)
            return 2
        queries = load_queries(args.queries)
        capture(
            args.base_url, queries, args.out,
            top_k=args.top_k, mode=args.capture_mode, timeout=args.timeout,
        )
        print(f"Captured {len(queries)} query responses to {args.out}")
        return 0

    pass_a_dir, pass_b_dir = (Path(p) for p in args.compare)
    pass_a = load_pass(pass_a_dir, mode=args.pass_a_mode, rrf_k=args.rrf_k)
    pass_b = load_pass(pass_b_dir, mode=args.pass_b_mode, rrf_k=args.rrf_k)
    report = compare_passes(
        pass_a, pass_b,
        top_k=args.top_k,
        rank_shift_top_n=args.rank_shift_top_n,
        gating_weight_threshold=args.gating_weight_threshold,
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print_report(report)
    print(f"\nWrote report to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
