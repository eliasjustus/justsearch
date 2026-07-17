#!/usr/bin/env python3
"""golden_common.py -- shared doc-identity and per-leg-score extraction for the
golden-parity search-quality harness.

This module exists so gen_golden_parity.py (baseline generator) and
check_golden_parity.py (finalize-time checker) share ONE identity/leg-
extraction authority instead of maintaining two independently drifting
copies (tempdoc 750 Part A).

Pure Python 3 standard library only.
"""

from __future__ import annotations

from typing import Any

TOP_N = 10

# gen_golden_parity.py writes this into the baseline's top-level
# "formatVersion" field. Bump when the baseline's on-disk shape changes in a
# way a consumer needs to branch on.
BASELINE_FORMAT_VERSION = 2

# Maps a leg_scores() output key to the search-trace stage id it is read
# from. Verified against a live hybrid /api/knowledge/search response: each
# hit's "trace" array carries entries like {"id": "dense-retrieval", "rank":
# 5, "score": 0.57117754}.
LEG_STAGE_IDS: dict[str, str] = {
    "sparse": "sparse-retrieval",
    "dense": "dense-retrieval",
    "splade": "splade-retrieval",
    "fusion": "fusion",
}


def normalize_identity(raw: str) -> str:
    """Strip directory prefixes so host/sandbox path roots don't break parity.

    The corpus files' basenames are stable across environments even when the
    full path (host staging dir vs. sandbox mapped folder) differs. Handles
    both '/' and '\\' separators since a raw id may come from either a
    POSIX-staged corpus or a Windows-mapped one.
    """
    normalized = raw.replace("\\", "/")
    return normalized.rsplit("/", 1)[-1]


def extract_doc_identity(hit: dict[str, Any]) -> str | None:
    """Resolve a search-result hit to a normalized doc identity.

    Priority: fields.parent_doc_id (chunk hit, links to the parent document)
    -> fields.doc_id (whole-doc hit; every indexed doc carries this) -> hit.id
    (last-resort fallback if fields are absent). Returns None if nothing
    resolvable is present.
    """
    fields = hit.get("fields") or {}
    raw = fields.get("parent_doc_id") or fields.get("doc_id")
    if not raw:
        raw = hit.get("id")
    if not raw:
        return None
    return normalize_identity(str(raw))


def extract_top_identities(response: dict[str, Any], limit: int = TOP_N) -> list[str]:
    """Return the ordered, normalized doc identities for the top `limit` hits.

    Hits with no resolvable identity are skipped (not counted, not padded) --
    a malformed hit should not silently manufacture a phantom parity match.
    """
    identities: list[str] = []
    for hit in (response.get("results") or [])[:limit]:
        identity = extract_doc_identity(hit)
        if identity is not None:
            identities.append(identity)
    return identities


def leg_scores(hit: dict[str, Any]) -> dict[str, float]:
    """Read a hybrid hit's per-hit `trace` stage array into a flat per-leg
    score map: {"sparse": ..., "dense": ..., "splade": ..., "fusion": ...}.

    A leg absent from the trace (e.g. a stage that never ran for this hit) is
    OMITTED from the result rather than defaulted to 0.0 -- an absent leg and
    a present-but-zero score are not the same thing. Scores are copied
    verbatim (full float precision) from the trace, not rounded.
    """
    scores: dict[str, float] = {}
    trace = hit.get("trace") or []
    stage_by_id = {
        stage.get("id"): stage for stage in trace if isinstance(stage, dict) and stage.get("id")
    }
    for leg_name, stage_id in LEG_STAGE_IDS.items():
        stage = stage_by_id.get(stage_id)
        if stage is None:
            continue
        score = stage.get("score")
        if score is None:
            continue
        scores[leg_name] = score
    return scores
