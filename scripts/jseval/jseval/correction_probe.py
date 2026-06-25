"""Spell-correction quality probe — TP/FP/FN/WC/TN classification."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path

import httpx

from . import retriever

log = logging.getLogger(__name__)

_DEFAULT_MANIFEST = Path(__file__).parent / "data" / "correction-eval-queries.v1.json"


@dataclass
class CorrectionQuery:
    """A correction probe query with expected correction."""

    query: str
    expected_correction: str | None
    error_type: str


def load_manifest(path: Path | None = None) -> list[CorrectionQuery]:
    """Load the correction query manifest from JSON."""
    p = path or _DEFAULT_MANIFEST
    data = json.loads(p.read_text(encoding="utf-8"))
    entries = data.get("queries", data) if isinstance(data, dict) else data
    return [
        CorrectionQuery(
            query=e["query"],
            expected_correction=e.get("expected_correction"),
            error_type=e.get("type", "unknown"),
        )
        for e in entries
    ]


def classify(query: CorrectionQuery, response: dict) -> str:
    """Classify a correction probe response as TP/FN/WC/FP/TN."""
    expects_correction = query.expected_correction is not None
    # Tempdoc 549 Phase E4: correction is carried by the unified trace's CORRECTION stage
    # (status=executed, detail=corrected query); SearchIntrospection was retired.
    stages = {s.get("id"): s for s in ((response.get("searchTrace") or {}).get("stages") or [])}
    correction_stage = stages.get("correction") or {}
    correction_applied = correction_stage.get("status") == "executed"
    corrected_query = correction_stage.get("detail") if correction_applied else None

    if expects_correction:
        if not correction_applied:
            return "FN"
        if (
            corrected_query
            and corrected_query.lower() == query.expected_correction.lower()
        ):
            return "TP"
        return "WC"
    else:
        if correction_applied:
            return "FP"
        return "TN"


def compute_metrics(classifications: list[dict]) -> dict:
    """Compute precision, recall, and false positive rate."""
    counts = {"TP": 0, "FN": 0, "WC": 0, "FP": 0, "TN": 0}
    for c in classifications:
        label = c["classification"]
        counts[label] = counts.get(label, 0) + 1

    tp, fn, wc, fp, tn = counts["TP"], counts["FN"], counts["WC"], counts["FP"], counts["TN"]

    prec_denom = tp + wc + fp
    recall_denom = tp + fn
    fpr_denom = fp + tn

    return {
        "precision": tp / prec_denom if prec_denom > 0 else None,
        "recall": tp / recall_denom if recall_denom > 0 else None,
        "false_positive_rate": fp / fpr_denom if fpr_denom > 0 else None,
        "true_positives": tp,
        "false_negatives": fn,
        "wrong_corrections": wc,
        "false_positives": fp,
        "true_negatives": tn,
        "total": len(classifications),
    }


def execute_probe(
    manifest: list[CorrectionQuery],
    base_url: str,
    top_k: int = 10,
    timeout: float = 30.0,
    max_retries: int = 3,
) -> dict:
    """Execute the correction probe against the search API."""
    classifications: list[dict] = []

    with httpx.Client(base_url=base_url, timeout=timeout) as client:
        for i, cq in enumerate(manifest):
            body = {"query": cq.query, "limit": top_k}
            response = retriever.execute_query(
                client, f"c{i}", body, max_retries, allow_errors=True,
            )

            if response is None:
                classifications.append({
                    "query": cq.query,
                    "error_type": cq.error_type,
                    "expected_correction": cq.expected_correction,
                    "correction_applied": False,
                    "corrected_query": None,
                    "classification": "FN" if cq.expected_correction else "TN",
                    "hit_count": 0,
                    "error": "query failed",
                })
                continue

            label = classify(cq, response)
            classifications.append({
                "query": cq.query,
                "error_type": cq.error_type,
                "expected_correction": cq.expected_correction,
                "correction_applied": response.get("correctionApplied") is True,
                "corrected_query": response.get("correctedQuery"),
                "classification": label,
                "hit_count": len(response.get("results", [])),
            })

    return {
        "classifications": classifications,
        "metrics": compute_metrics(classifications),
    }


def format_console(result: dict) -> str:
    """Format correction probe results for console output."""
    m = result["metrics"]
    typo_count = m["true_positives"] + m["false_negatives"] + m["wrong_corrections"]
    control_count = m["false_positives"] + m["true_negatives"]

    lines = [
        f"Correction Probe: {m['total']} queries ({typo_count} typo, {control_count} control)",
        f"  TP: {m['true_positives']}  FN: {m['false_negatives']}  "
        f"WC: {m['wrong_corrections']}  FP: {m['false_positives']}  TN: {m['true_negatives']}",
    ]
    if m["precision"] is not None:
        lines.append(f"  Precision: {m['precision']:.4f}  Recall: {m['recall']:.4f}  "
                     f"FPR: {m['false_positive_rate']:.4f}")
    return "\n".join(lines)
