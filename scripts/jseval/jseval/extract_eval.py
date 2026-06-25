"""
Extraction quality evaluation.

Compares extracted text against ground truth using word overlap,
broken down by difficulty bracket and domain. Optionally uses a
stratified manifest for reproducible fast-eval.

This module measures text fidelity independent of search quality --
it answers "how much of the ground truth content did the extractor
capture?" without running the search pipeline.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Optional

# Strip punctuation attached to words so "hello," matches "hello"
_WORD_RE = re.compile(r"[a-z0-9]+(?:[-'][a-z0-9]+)*", re.IGNORECASE)

# Default bracket thresholds (docling_overlap boundaries).
# Manifests may override via "bracket_thresholds" key.
DEFAULT_BRACKET_THRESHOLDS = {"hard": 0.50, "medium": 0.85}


def _tokenize(text: str) -> set[str]:
    """Extract lowercase alphanumeric words, stripping punctuation."""
    return set(_WORD_RE.findall(text.lower()))


def word_overlap(extracted: str, ground_truth: str) -> float:
    """Case-insensitive word overlap: |extracted & gt| / |gt|."""
    words_ext = _tokenize(extracted)
    words_gt = _tokenize(ground_truth)
    if not words_gt:
        return 0.0
    return len(words_ext & words_gt) / len(words_gt)


def _classify_bracket(docling_overlap: float, thresholds: dict) -> str:
    if docling_overlap < thresholds["hard"]:
        return "hard"
    elif docling_overlap < thresholds["medium"]:
        return "medium"
    return "easy"


def _bracket_aggregate(results: list[dict], bracket: str, key: str) -> Optional[dict]:
    pages = [r for r in results if r.get("bracket") == bracket and key in r]
    if not pages:
        return None
    return {
        "count": len(pages),
        "avg_overlap": round(sum(r[key] for r in pages) / len(pages), 4),
    }


def evaluate(
    variant_dir: Path,
    ground_truth_dir: Path,
    manifest_path: Optional[Path] = None,
    reference_dir: Optional[Path] = None,
) -> dict:
    """
    Evaluate extraction quality.

    Args:
        variant_dir: Directory with extracted .txt files to evaluate.
        ground_truth_dir: Directory with ground truth .txt files.
        manifest_path: Optional manifest JSON for stratified eval with
            difficulty brackets. If None, evaluates all matching .txt files
            without bracket breakdown.
        reference_dir: Optional reference extraction dir (e.g., Docling)
            for side-by-side comparison.

    Returns:
        dict with per-file results, bracket aggregates, domain aggregates,
        and optional reference comparison.
    """
    thresholds = dict(DEFAULT_BRACKET_THRESHOLDS)

    if manifest_path and manifest_path.exists():
        raw = json.loads(manifest_path.read_text(encoding="utf-8"))
        if isinstance(raw, dict) and "pages" in raw:
            manifest = raw["pages"]
            if "bracket_thresholds" in raw:
                thresholds.update(raw["bracket_thresholds"])
        else:
            manifest = raw  # flat list format
    else:
        manifest = []
        for gt_file in sorted(ground_truth_dir.glob("*.txt")):
            if gt_file.name.startswith("_"):
                continue
            manifest.append({
                "encoded": gt_file.stem,
                "domain": gt_file.stem.split("%2F")[0] if "%2F" in gt_file.stem else "unknown",
                "docling_overlap": None,
            })

    results = []
    for page in manifest:
        encoded = page["encoded"]
        gt_file = ground_truth_dir / (encoded + ".txt")
        var_file = variant_dir / (encoded + ".txt")

        if not gt_file.exists():
            continue

        gt_text = gt_file.read_text(encoding="utf-8")
        gt_words = _tokenize(gt_text)
        if len(gt_words) < 3:
            continue

        # Variant overlap
        if var_file.exists():
            var_text = var_file.read_text(encoding="utf-8")
            var_overlap = word_overlap(var_text, gt_text)
        else:
            var_text = ""
            var_overlap = 0.0

        entry = {
            "encoded": encoded,
            "domain": page.get("domain", "unknown"),
            "var_overlap": round(var_overlap, 4),
            "var_words": len(_tokenize(var_text)),
            "gt_words": len(gt_words),
        }

        # Bracket classification (only with manifest that has overlap data)
        doc_overlap = page.get("docling_overlap")
        if doc_overlap is not None:
            entry["bracket"] = _classify_bracket(doc_overlap, thresholds)
            entry["docling_overlap"] = round(doc_overlap, 4)

        # Reference overlap
        if reference_dir:
            ref_file = reference_dir / (encoded + ".txt")
            if ref_file.exists():
                ref_text = ref_file.read_text(encoding="utf-8")
                entry["ref_overlap"] = round(word_overlap(ref_text, gt_text), 4)
            else:
                entry["ref_overlap"] = 0.0

        results.append(entry)

    # Aggregates
    output = {
        "variant_dir": str(variant_dir),
        "ground_truth_dir": str(ground_truth_dir),
        "total_pages": len(results),
        "results": results,
    }

    if results:
        output["avg_overlap"] = round(
            sum(r["var_overlap"] for r in results) / len(results), 4
        )

    # By bracket (variant + reference)
    has_brackets = any("bracket" in r for r in results)
    if has_brackets:
        bracket_agg = {}
        for bracket in ("hard", "medium", "easy"):
            var_b = _bracket_aggregate(results, bracket, "var_overlap")
            if var_b:
                b = dict(var_b)
                ref_b = _bracket_aggregate(results, bracket, "ref_overlap")
                if ref_b:
                    b["ref_avg_overlap"] = ref_b["avg_overlap"]
                bracket_agg[bracket] = b
        output["brackets"] = bracket_agg

    # By domain
    domain_agg = {}
    for r in results:
        domain_agg.setdefault(r["domain"], []).append(r["var_overlap"])
    output["domains"] = {
        d: {"count": len(vals), "avg_overlap": round(sum(vals) / len(vals), 4)}
        for d, vals in sorted(domain_agg.items())
    }

    # Reference comparison
    if reference_dir and any("ref_overlap" in r for r in results):
        ref_pages = [r for r in results if "ref_overlap" in r]
        output["reference_dir"] = str(reference_dir)
        output["ref_avg_overlap"] = round(
            sum(r["ref_overlap"] for r in ref_pages) / len(ref_pages), 4
        )
        var_wins = sum(1 for r in ref_pages if r["var_overlap"] > r["ref_overlap"] + 0.02)
        ref_wins = sum(1 for r in ref_pages if r["ref_overlap"] > r["var_overlap"] + 0.02)
        ties = len(ref_pages) - var_wins - ref_wins
        output["comparison"] = {
            "variant_wins": var_wins,
            "reference_wins": ref_wins,
            "ties": ties,
        }

    return output


def format_console(result: dict) -> str:
    """Format evaluation result for console output."""
    lines = []
    lines.append(f"Extraction Quality: {result['variant_dir']}")
    lines.append(f"Ground truth: {result['ground_truth_dir']}")
    lines.append(f"Pages: {result['total_pages']}  Avg overlap: {result.get('avg_overlap', 0):.1%}")
    lines.append("")

    # Brackets (variant + reference side-by-side)
    if "brackets" in result:
        has_ref = any("ref_avg_overlap" in b for b in result["brackets"].values())
        if has_ref:
            lines.append(f"  {'Bracket':8s}  {'n':>3s}  {'Variant':>8s}  {'Reference':>10s}")
        else:
            lines.append(f"  {'Bracket':8s}  {'n':>3s}  {'Overlap':>8s}")
        for bracket in ("hard", "medium", "easy"):
            b = result["brackets"].get(bracket)
            if not b:
                continue
            row = f"  {bracket:8s}  {b['count']:3d}  {b['avg_overlap']:8.1%}"
            if has_ref and "ref_avg_overlap" in b:
                row += f"  {b['ref_avg_overlap']:10.1%}"
            lines.append(row)

    # Domains
    lines.append("")
    lines.append("By domain:")
    for domain, info in result.get("domains", {}).items():
        lines.append(f"  {domain:20s}  n={info['count']:2d}  avg={info['avg_overlap']:.1%}")

    # Reference comparison
    if "reference_dir" in result:
        lines.append("")
        ref_name = Path(result["reference_dir"]).name
        lines.append(f"vs {ref_name}: {result['ref_avg_overlap']:.1%}")
        c = result["comparison"]
        lines.append(f"  variant wins: {c['variant_wins']}  ref wins: {c['reference_wins']}  ties: {c['ties']}")

    return "\n".join(lines)
