"""Developer ranking spot-check — score distribution without qrels."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path

import httpx

from . import retriever

log = logging.getLogger(__name__)


@dataclass
class QueryEntry:
    """A spot-check query with category."""

    id: str
    text: str
    category: str


DEFAULT_SUITE: list[QueryEntry] = [
    QueryEntry("q01", "testing strategy", "exact"),
    QueryEntry("q02", "tier 4 ai judge", "entity"),
    QueryEntry("q03", "session token x-justsearch-session", "entity"),
    QueryEntry("q04", "watched root indexing roots endpoint", "mixed"),
    QueryEntry("q05", "rerank_onnx stage", "acronym"),
    QueryEntry("q06", "reciprocal rank fusion rrf", "acronym"),
    QueryEntry("q07", "gpu booster pack", "exact"),
    QueryEntry("q08", "how to run tests on windows", "paraphrase"),
    QueryEntry("q09", "local agent gate win ps1", "navigation"),
    QueryEntry("q10", "bananas rocketship quantum flux capacitor", "negative"),
]


def load_suite(path: Path | None = None) -> list[QueryEntry]:
    """Load a query suite from JSON or return the default suite."""
    if path is None:
        return list(DEFAULT_SUITE)
    data = json.loads(path.read_text(encoding="utf-8"))
    entries = data if isinstance(data, list) else data.get("queries", [])
    return [
        QueryEntry(id=str(e["id"]), text=e["text"], category=e.get("category", ""))
        for e in entries
    ]


def execute_spot_check(
    suite: list[QueryEntry],
    base_url: str,
    modes: list[str],
    top_k: int = 5,
    timeout: float = 30.0,
    max_retries: int = 3,
) -> dict:
    """Run the spot-check suite and return results."""
    per_query: list[dict] = []
    per_mode: dict[str, dict] = {}

    with httpx.Client(base_url=base_url, timeout=timeout) as client:
        for mode in modes:
            mode_results: list[dict] = []
            for entry in suite:
                body = {"query": entry.text, "limit": top_k, "mode": mode}
                response = retriever.execute_query(
                    client, entry.id, body, max_retries, allow_errors=True,
                )
                result = _extract_result(entry, mode, response, top_k)
                per_query.append(result)
                mode_results.append(result)

            per_mode[mode] = _summarize_mode(mode_results)

    return {"per_query": per_query, "per_mode": per_mode}


def _extract_result(
    entry: QueryEntry, mode: str, response: dict | None, top_k: int,
) -> dict:
    """Extract score metrics from a single query response."""
    if response is None:
        return {
            "query_id": entry.id, "query": entry.text, "category": entry.category,
            "mode": mode, "top1_score": None, "mean_score": None,
            "total_hits": 0, "took_ms": None, "hits": [],
        }

    hits = response.get("results", [])[:top_k]
    scores = [h["score"] for h in hits if "score" in h]
    hit_summaries = []
    for h in hits:
        fields = h.get("fields") or {}
        hit_summaries.append({
            "score": h.get("score"),
            "filename": fields.get("filename", ""),
            "path": fields.get("path", ""),
        })

    return {
        "query_id": entry.id,
        "query": entry.text,
        "category": entry.category,
        "mode": mode,
        "top1_score": scores[0] if scores else None,
        "mean_score": sum(scores) / len(scores) if scores else None,
        "total_hits": response.get("totalHits", 0),
        "took_ms": response.get("tookMs"),
        "hits": hit_summaries,
    }


def _summarize_mode(results: list[dict]) -> dict:
    """Aggregate per-query results for a mode."""
    def _mean(vals: list[float | None]) -> float | None:
        valid = [v for v in vals if v is not None]
        return sum(valid) / len(valid) if valid else None

    return {
        "query_count": len(results),
        "mean_top1_score": _mean([r["top1_score"] for r in results]),
        "mean_topk_score": _mean([r["mean_score"] for r in results]),
        "mean_total_hits": _mean([float(r["total_hits"]) for r in results]),
        "mean_took_ms": _mean([r["took_ms"] for r in results]),
    }


def format_console(results: dict) -> str:
    """Format spot-check results for human-readable console output."""
    lines: list[str] = []
    for r in results["per_query"]:
        lines.append(
            f'=== "{r["query"]}" [{r["category"]}] mode={r["mode"]} ==='
        )
        lines.append(
            f'totalHits={r["total_hits"]}  tookMs={r["took_ms"]}  '
            f'results={len(r["hits"])}'
        )
        for h in r["hits"]:
            score = f'{h["score"]:.4f}' if h["score"] is not None else "None"
            lines.append(f'  {score}  {h["filename"]:<30s}  {h["path"]}')
        lines.append("")

    # Per-mode summary
    for mode, summary in results.get("per_mode", {}).items():
        lines.append(f"  {mode} summary:")
        for k, v in sorted(summary.items()):
            if k == "query_count":
                continue
            if v is not None:
                lines.append(f"    {k}: {v:.4f}")
        lines.append("")

    return "\n".join(lines)
