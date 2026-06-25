"""2-dim stratified metrics (tempdoc 400 LR4-c).

Closes §4.5 (aggregate metrics hide per-strata performance). For each
mode, computes per-bucket mean metrics over two dimensions:

- **query-length bucket** — whitespace-token count of the query text.
  Buckets: ``short (≤5)``, ``medium (6-10)``, ``long (11+)``,
  ``unknown`` (when ``query`` is missing).
- **first-relevant-rank bucket** — rank in ``predictedDocIds`` of
  the first document with qrels relevance > 0. Buckets:
  ``top-1``, ``top-2-5``, ``6-10``, ``>10``, ``unjudged`` (no
  relevant doc in the ranking or qrels missing for this qid).

**§26.6 Decision 1 (locked 2026-04-22):** The 3rd entity-density
dimension from the original spec is dropped. Reintroducing it
requires a query-understanding pipeline that does not exist in
Phase 3; running NER at projection time would violate the
"projections are pure functions over artifacts" principle.

Output shape v1:

.. code-block:: json

    {
      "modes": {
        "hybrid": {
          "strata": {
            "short || top-1": {
              "count": 8,
              "nDCG@10": 0.85, "AP@10": 0.52, ...
            },
            ...
          },
          "marginals": {
            "by_query_length": {"short": {"count": ..., "nDCG@10": ...}},
            "by_first_relevant_rank": {"top-1": {...}}
          }
        }
      },
      "bucket_definitions": {
        "query_length": ["short", "medium", "long", "unknown"],
        "first_relevant_rank": ["top-1", "top-2-5", "6-10", ">10", "unjudged"]
      }
    }
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from .base import Projection

log = logging.getLogger(__name__)

PROJECTION_NAME = "stratified_metrics"
SCHEMA_VERSION = 1

QUERY_LENGTH_BUCKETS = ("short", "medium", "long", "unknown")
FIRST_RELEVANT_RANK_BUCKETS = (
    "top-1", "top-2-5", "6-10", ">10", "unjudged",
)
# Tempdoc 525: third stratification dimension, sourced from
# ``SearchIntrospection.decision.kind`` (default-on per slice decision #1).
# The 4 values mirror the worker-side {@code SearchDecision} sealed-sum
# variants; ``"unknown"`` is the fall-through for pre-525 worker emissions
# or explicit introspection suppression.
DECISION_KIND_BUCKETS = (
    "empty_query", "blocked", "sparse_shortcut", "multi_leg", "unknown",
)

# Phase 6 / 6.12: bucket edges promoted to module constants so operators
# can override per-dataset via produce()/run_all() kwargs. Defaults match
# Phase 3 behaviour and the §26.6 Decision 1 spec.
#
# QUERY_LENGTH_BUCKET_EDGES = (short_max, medium_max). Query tokens ≤ 5
# → "short"; 6..10 → "medium"; 11+ → "long"; missing/empty → "unknown".
# Rationale: scifact queries average ~11 tokens; (5, 10) splits short
# dense queries from long analytical ones on that corpus. Other corpora
# (e.g. nfcorpus with longer queries) may want larger thresholds.
QUERY_LENGTH_BUCKET_EDGES: tuple[int, int] = (5, 10)

# FIRST_RELEVANT_RANK_BUCKET_EDGES = (top_single_max, head_max, mid_max).
# Rank 1 → "top-1"; 2..5 → "top-2-5"; 6..10 → "6-10"; 11+ → ">10".
# Rank = None → "unjudged" (unrelated axis, not an edge).
# Rationale: reflects the user-visible top-K (default 10) split into
# pole (rank 1 is the "right answer" bucket), head (top-5 is what
# users typically inspect), and long-tail within the K window.
FIRST_RELEVANT_RANK_BUCKET_EDGES: tuple[int, int, int] = (1, 5, 10)

_METRIC_KEYS = {
    "ndcgAtK": "nDCG@10",
    "apAtK": "AP@10",
    "mrrAtK": "RR@10",
    "recallAtK": "R@10",
    "p1AtK": "P@1",
}


def _query_length_bucket(
    query: str | None,
    edges: tuple[int, int] = QUERY_LENGTH_BUCKET_EDGES,
) -> str:
    """Return one of QUERY_LENGTH_BUCKETS.

    ``edges = (short_max, medium_max)`` — tokens ≤ short_max → short,
    tokens ≤ medium_max → medium, else long. Missing/empty → unknown.
    """
    if not isinstance(query, str) or not query.strip():
        return "unknown"
    tokens = len(query.split())
    short_max, medium_max = edges
    if tokens <= short_max:
        return "short"
    if tokens <= medium_max:
        return "medium"
    return "long"


def _first_relevant_rank(
    predicted_docs: list[str], qrels_for_query: dict,
) -> int | None:
    """Return 1-based rank of first relevant doc in ``predicted_docs``.

    ``None`` if no doc appears in ``predicted_docs`` with qrels > 0.
    """
    if not qrels_for_query:
        return None
    for rank, doc_id in enumerate(predicted_docs, start=1):
        if qrels_for_query.get(doc_id, 0) > 0:
            return rank
    return None


def _decision_kind_bucket(decision_kind: str | None) -> str:
    """Return one of DECISION_KIND_BUCKETS, falling through to ``"unknown"``.

    Tempdoc 525: sourced from ``SearchIntrospection.decision.kind`` on the
    eval driver's response. Pre-525 workers (or explicit introspection
    suppression via ``request.include_introspection=false``) produce
    ``None``; both land in the ``"unknown"`` bucket.
    """
    if isinstance(decision_kind, str) and decision_kind in DECISION_KIND_BUCKETS:
        return decision_kind
    return "unknown"


def _first_relevant_bucket(
    rank: int | None,
    edges: tuple[int, int, int] = FIRST_RELEVANT_RANK_BUCKET_EDGES,
) -> str:
    """Return one of FIRST_RELEVANT_RANK_BUCKETS.

    ``edges = (top_single_max, head_max, mid_max)`` — rank ≤ top_single_max
    → top-1 (caller supplies the labels); ≤ head_max → top-2-5; ≤ mid_max
    → 6-10; else >10. Rank is None → unjudged (unrelated axis).
    """
    if rank is None:
        return "unjudged"
    top_single_max, head_max, mid_max = edges
    if rank <= top_single_max:
        return "top-1"
    if rank <= head_max:
        return "top-2-5"
    if rank <= mid_max:
        return "6-10"
    return ">10"


def _load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


def _discover_modes(run_dir: Path) -> list[str]:
    modes: list[str] = []
    for path in sorted(run_dir.glob("*_per_query.json")):
        mode = path.stem.rsplit("_per_query", 1)[0]
        if mode:
            modes.append(mode)
    return modes


def _accumulate(
    entries: list[dict], qrels: dict,
    *,
    query_length_edges: tuple[int, int] = QUERY_LENGTH_BUCKET_EDGES,
    first_relevant_rank_edges: tuple[int, int, int] = FIRST_RELEVANT_RANK_BUCKET_EDGES,
) -> tuple[dict[str, dict], dict[str, dict], dict[str, dict], dict[str, dict]]:
    """Return (strata_cells, by_length, by_rank, by_decision_kind) aggregate dicts.

    Each cell: ``{count, <metric_label>: mean, ...}``.
    Missing metric values are excluded from the mean rather than
    treated as 0.

    Tempdoc 525: ``by_decision_kind`` is a new marginal dimension sourced
    from ``SearchIntrospection.decision.kind`` on the eval driver's
    per-query response.
    """
    strata: dict[str, dict] = {}
    by_length: dict[str, dict] = {}
    by_rank: dict[str, dict] = {}
    by_decision_kind: dict[str, dict] = {}

    def _bump(bucket: dict[str, dict], key: str, entry: dict):
        cell = bucket.setdefault(key, {"count": 0})
        cell["count"] += 1
        for json_key, metric_label in _METRIC_KEYS.items():
            raw = entry.get(json_key)
            if raw is None:
                continue
            try:
                val = float(raw)
            except (TypeError, ValueError):
                continue
            bucket_of_metric = cell.setdefault("_sums", {})
            bucket_of_counts = cell.setdefault("_sums_n", {})
            bucket_of_metric[metric_label] = bucket_of_metric.get(metric_label, 0.0) + val
            bucket_of_counts[metric_label] = bucket_of_counts.get(metric_label, 0) + 1

    for entry in entries:
        qid = entry.get("qid")
        query = entry.get("query")
        predicted = entry.get("predictedDocIds") or []
        qrels_q = qrels.get(qid, {}) if qid else {}
        q_bucket = _query_length_bucket(query, query_length_edges)
        rank = _first_relevant_rank(predicted, qrels_q)
        r_bucket = _first_relevant_bucket(rank, first_relevant_rank_edges)
        d_bucket = _decision_kind_bucket(entry.get("decision_kind"))
        composite = f"{q_bucket} || {r_bucket}"
        _bump(strata, composite, entry)
        _bump(by_length, q_bucket, entry)
        _bump(by_rank, r_bucket, entry)
        _bump(by_decision_kind, d_bucket, entry)

    # Finalize means + drop internal accumulators.
    def _finalize(bucket: dict[str, dict]) -> None:
        for cell in bucket.values():
            sums = cell.pop("_sums", {})
            counts = cell.pop("_sums_n", {})
            for metric_label, total in sums.items():
                n = counts.get(metric_label, 0)
                cell[metric_label] = total / n if n else None

    _finalize(strata)
    _finalize(by_length)
    _finalize(by_rank)
    _finalize(by_decision_kind)
    return strata, by_length, by_rank, by_decision_kind


def produce(
    run_dir: Path,
    *,
    query_length_edges: tuple[int, int] | None = None,
    first_relevant_rank_edges: tuple[int, int, int] | None = None,
) -> dict:
    """Produce the 2-dim stratified metrics projection.

    Phase 6 / 6.12: operators can override the default bucket edges
    (``QUERY_LENGTH_BUCKET_EDGES`` / ``FIRST_RELEVANT_RANK_BUCKET_EDGES``)
    for per-dataset tuning. The effective edges are echoed in
    ``bucket_definitions.edges`` so consumers can audit them.
    """
    q_edges = query_length_edges or QUERY_LENGTH_BUCKET_EDGES
    r_edges = first_relevant_rank_edges or FIRST_RELEVANT_RANK_BUCKET_EDGES
    qrels = _load_json(run_dir / "qrels.json") or {}
    modes_block: dict[str, dict] = {}
    for mode in _discover_modes(run_dir):
        entries = _load_json(run_dir / f"{mode}_per_query.json")
        if not isinstance(entries, list):
            modes_block[mode] = {
                "strata": {},
                "marginals": {
                    "by_query_length": {},
                    "by_first_relevant_rank": {},
                    "by_decision_kind": {},
                },
                "status": "missing-per-query",
            }
            continue
        strata, by_length, by_rank, by_decision = _accumulate(
            entries, qrels,
            query_length_edges=q_edges,
            first_relevant_rank_edges=r_edges,
        )
        modes_block[mode] = {
            "strata": strata,
            "marginals": {
                "by_query_length": by_length,
                "by_first_relevant_rank": by_rank,
                # Tempdoc 525: 3rd marginal dimension by SearchDecision.kind.
                "by_decision_kind": by_decision,
            },
            "status": "ok",
        }
    return {
        "modes": modes_block,
        "bucket_definitions": {
            "query_length": list(QUERY_LENGTH_BUCKETS),
            "first_relevant_rank": list(FIRST_RELEVANT_RANK_BUCKETS),
            "decision_kind": list(DECISION_KIND_BUCKETS),
            "edges": {
                "query_length": list(q_edges),
                "first_relevant_rank": list(r_edges),
            },
        },
    }


PROJECTION = Projection(
    name=PROJECTION_NAME,
    schema_version=SCHEMA_VERSION,
    description="2-dim (query-length × first-relevant-rank) stratified metrics (LR4-c).",
    produce=produce,
)
