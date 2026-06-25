"""Query JustSearch API and resolve doc IDs to BEIR IDs."""

from __future__ import annotations

import logging
import time
import urllib.parse
from pathlib import PurePosixPath

import httpx
import ir_measures

log = logging.getLogger(__name__)

ScoredDoc = ir_measures.ScoredDoc

LEXICAL_PIPELINE: dict = {
    "sparseEnabled": True,
    "denseEnabled": False,
    "spladeEnabled": False,
    "fusionAlgorithm": "none",
    "lambdamartEnabled": False,
    "crossEncoderEnabled": False,
    "crossEncoderWindow": 0,
    "expansionEnabled": False,
    "freshnessEnabled": False,
}

VECTOR_PIPELINE: dict = {
    "sparseEnabled": False,
    "denseEnabled": True,
    "spladeEnabled": False,
    "fusionAlgorithm": "none",
    "lambdamartEnabled": False,
    "crossEncoderEnabled": False,
    "crossEncoderWindow": 0,
    "expansionEnabled": False,
    "freshnessEnabled": False,
}

SPLADE_PIPELINE: dict = {
    "sparseEnabled": False,
    "denseEnabled": False,
    "spladeEnabled": True,
    "fusionAlgorithm": "none",
    "lambdamartEnabled": False,
    "crossEncoderEnabled": False,
    "crossEncoderWindow": 0,
    "expansionEnabled": False,
    "freshnessEnabled": False,
}

BM25_SPLADE_PIPELINE: dict = {
    "sparseEnabled": True,
    "denseEnabled": False,
    "spladeEnabled": True,
    "lambdamartEnabled": False,
    "crossEncoderEnabled": False,
    "crossEncoderWindow": 0,
    "expansionEnabled": False,
    "freshnessEnabled": False,
}

DENSE_SPLADE_PIPELINE: dict = {
    "sparseEnabled": False,
    "denseEnabled": True,
    "spladeEnabled": True,
    "lambdamartEnabled": False,
    "crossEncoderEnabled": False,
    "crossEncoderWindow": 0,
    "expansionEnabled": False,
    "freshnessEnabled": False,
}

FULL_PIPELINE: dict = {
    "sparseEnabled": True,
    "denseEnabled": True,
    "spladeEnabled": True,
    "lambdamartEnabled": False,
    "crossEncoderEnabled": False,
    "crossEncoderWindow": 0,
    "expansionEnabled": False,
    "freshnessEnabled": False,
}

# Server-passthrough modes (not in MODE_PIPELINES, resolved server-side).
SERVER_MODES = {"hybrid"}

# Map mode names to explicit pipeline dicts. Modes not listed here
# are passed as body["mode"] to the API (server-side resolution).
MODE_PIPELINES: dict[str, dict] = {
    "lexical": LEXICAL_PIPELINE,
    "vector": VECTOR_PIPELINE,
    "splade": SPLADE_PIPELINE,
    "bm25_splade": BM25_SPLADE_PIPELINE,
    "dense_splade": DENSE_SPLADE_PIPELINE,
    "full": FULL_PIPELINE,
}


def retrieve(
    queries: dict[str, str],
    base_url: str,
    mode: str = "hybrid",
    top_k: int = 10,
    debug: bool = False,
    pipeline: dict | None = None,
    timeout: float = 90.0,
    max_retries: int = 5,
    allow_errors: bool = False,
    include_excerpts: bool = False,
) -> tuple[list[ScoredDoc], list[dict]]:
    """Query the API for each query sequentially with retry.

    Returns:
        scored_docs: list of ScoredDoc for ir-measures
        raw_responses: list of dicts (one per query, for provenance)
    """
    scored_docs: list[ScoredDoc] = []
    raw_responses: list[dict] = []

    with httpx.Client(base_url=base_url, timeout=timeout) as client:
        total = len(queries)
        for i, (qid, qtext) in enumerate(queries.items(), 1):
            body = _build_request(qtext, mode, top_k, debug, pipeline, include_excerpts)
            response = execute_query(client, qid, body, max_retries, allow_errors)

            if response is None:
                # Error recorded, continue to next query
                raw_responses.append({"query_id": qid, "error": "all retries failed"})
                continue

            resolution_errors = 0
            for hit in response.get("results", []):
                try:
                    doc_id = resolve_doc_id(hit)
                except ValueError as e:
                    resolution_errors += 1
                    if allow_errors:
                        log.warning("Doc ID resolution failed for query %s: %s", qid, e)
                        continue
                    raise
                scored_docs.append(ScoredDoc(query_id=qid, doc_id=doc_id, score=hit["score"]))

            raw_responses.append({
                "query_id": qid,
                "identity_resolution_errors": resolution_errors,
                **response,
            })

            if i % 50 == 0 or i == total:
                log.info("Queried %d/%d", i, total)

    return scored_docs, raw_responses


# ---------------------------------------------------------------------------
# Request building
# ---------------------------------------------------------------------------

def _build_request(
    query: str,
    mode: str,
    top_k: int,
    debug: bool,
    pipeline: dict | None,
    include_excerpts: bool = False,
) -> dict:
    body: dict = {"query": query, "limit": top_k}
    if pipeline:
        body["pipeline"] = pipeline
    elif mode in MODE_PIPELINES:
        body["pipeline"] = MODE_PIPELINES[mode]
    else:
        body["mode"] = mode
    if debug:
        body["debug"] = True
    if include_excerpts:
        body["includeExcerpts"] = True
    return body


# ---------------------------------------------------------------------------
# Query execution with retry
# ---------------------------------------------------------------------------

def execute_query(
    client: httpx.Client,
    qid: str,
    body: dict,
    max_retries: int,
    allow_errors: bool,
) -> dict | None:
    last_error: Exception | None = None
    for attempt in range(max_retries):
        try:
            resp = client.post("/api/knowledge/search", json=body)
            resp.raise_for_status()
            return resp.json()
        except (httpx.HTTPError, httpx.TimeoutException) as e:
            last_error = e
            if attempt < max_retries - 1:
                delay = min(0.5 * 2**attempt, 5.0)
                log.warning(
                    "Query %s attempt %d failed (%s), retrying in %.1fs",
                    qid, attempt + 1, e, delay,
                )
                time.sleep(delay)

    if allow_errors:
        log.error("Query %s failed after %d attempts: %s", qid, max_retries, last_error)
        return None
    raise RuntimeError(
        f"Query {qid} failed after {max_retries} attempts: {last_error}"
    ) from last_error


# ---------------------------------------------------------------------------
# Doc ID resolution
# ---------------------------------------------------------------------------

def resolve_doc_id(hit: dict) -> str:
    """Resolve a search hit to a BEIR document ID.

    Priority chain (from PS1 Resolve-BeirHitDocumentIdentity):
    1. hit.fields.filename → strip .txt → URL-decode
    2. hit.fields.path or hit.provenance.path → leaf → same
    3. hit.id if file-backed (contains / or \\ or ends .txt) → leaf → same
    """
    # Source 1: fields.filename
    fields = hit.get("fields") or {}
    filename = fields.get("filename")
    if filename:
        return _filename_to_doc_id(filename)

    # Source 2: fields.path, then provenance.path
    path = fields.get("path") or (hit.get("provenance") or {}).get("path")
    if path:
        leaf = _extract_leaf(path)
        return _filename_to_doc_id(leaf)

    # Source 3: hit.id (file-backed heuristic)
    hit_id = hit.get("id", "")
    if "/" in hit_id or "\\" in hit_id or hit_id.endswith(".txt"):
        leaf = _extract_leaf(hit_id)
        return _filename_to_doc_id(leaf)

    raise ValueError(f"Cannot resolve doc ID from hit: {hit_id!r}")


def _filename_to_doc_id(filename: str) -> str:
    """Strip file extension and URL-decode to recover the BEIR doc ID."""
    stem = PurePosixPath(filename).stem
    if not stem and "\\" in filename:
        # Windows-style path that PurePosixPath can't parse
        stem = filename.rsplit("\\", 1)[-1]
        if "." in stem:
            stem = stem.rsplit(".", 1)[0]
    try:
        return urllib.parse.unquote(stem)
    except Exception:
        return stem


def _extract_leaf(path: str) -> str:
    """Extract the leaf filename from a path (handles / and \\)."""
    leaf = PurePosixPath(path).name
    if not leaf and "\\" in path:
        leaf = path.rsplit("\\", 1)[-1]
    return leaf or path
