"""ir-measures wrapper, context coverage, and statistical tests."""

from __future__ import annotations

import ir_measures
from ir_measures import AP, P, R, RR, nDCG

DEFAULT_METRICS = [nDCG @ 10, AP @ 10, RR @ 10, R @ 10, P @ 1]


def evaluate(
    qrels: dict[str, dict[str, int]],
    run: list,
    metrics: list | None = None,
) -> dict[str, float]:
    """Compute aggregate metrics across all queries.

    Args:
        qrels: {query_id: {doc_id: relevance}}
        run: list of ir_measures.ScoredDoc
        metrics: list of ir-measures Measure objects (default: nDCG@10, AP@10,
                 RR@10, R@10, P@1)

    Returns:
        {metric_name: value} e.g. {"nDCG@10": 0.731, "AP@10": 0.412, ...}
    """
    metrics = metrics or DEFAULT_METRICS
    result = ir_measures.calc_aggregate(metrics, _qrels_to_ir(qrels), run)
    return {str(m): v for m, v in result.items()}


def evaluate_per_query(
    qrels: dict[str, dict[str, int]],
    run: list,
    metrics: list | None = None,
) -> dict[str, dict[str, float]]:
    """Compute per-query metrics.

    Returns:
        {query_id: {metric_name: value}}
    """
    metrics = metrics or DEFAULT_METRICS
    per_query: dict[str, dict[str, float]] = {}
    for m in ir_measures.iter_calc(metrics, _qrels_to_ir(qrels), run):
        per_query.setdefault(m.query_id, {})[str(m.measure)] = m.value
    return per_query


def _qrels_to_ir(
    qrels: dict[str, dict[str, int]],
) -> list:
    """Convert nested dict qrels to ir-measures Qrel objects."""
    return [
        ir_measures.Qrel(qid, did, rel)
        for qid, docs in qrels.items()
        for did, rel in docs.items()
    ]
