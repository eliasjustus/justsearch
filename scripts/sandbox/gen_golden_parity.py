#!/usr/bin/env python3
"""gen_golden_parity.py — generate the per-candidate golden-parity baseline
for the Sandbox golden-parity search-quality harness.

Design (settled with the owner): the Sandbox cannot measure absolute search
quality (no jseval there). Instead it checks PARITY WITH DEV. Run this script
against a RUNNING DEV STACK, on the SAME build + SAME corpus (scifact) that
the Sandbox round will validate, to produce a "golden" expected-results
baseline. The Sandbox agent later runs the same fixed query set
(golden-queries.json) against the installed candidate's API and saves raw
responses as evidence; check_golden_parity.py (host-side, at finalize)
compares the two with tolerance (see that script's docstring).

Goldens are regenerated per candidate — this is self-maintaining across
intentional ranking changes, not a frozen fixture that goes stale.

Provenance (model-identity audit, 2026-07-14): `/api/knowledge/status`'s
`embeddingFingerprintCurrent` IS the SHA-256 of the loaded embedding model
file (verified against the registry's declared hash), so it is a free, exact
model-identity key. This generator refuses (exit 1) to write a baseline
unless the backend reports a non-empty fingerprint AND
`embeddingCompatState == "COMPATIBLE"` — a baseline generated against a
not-yet-enriched or incompatible index is not meaningful. The written
baseline also records `embeddingFingerprint`, `indexedDocuments`, and
`probeExecutedRetrievalLegs` (from one probe query's `searchTrace.stages`)
so `check_golden_parity.py` can detect a round that ran different embedding
weights (e.g. CPU FP32 vs GPU FP16 — confirmed NOT byte-identical on CPU) or
an under-ingested corpus before trusting a ranking comparison.

Usage (from the repo root, against a running dev backend):
    python scripts/sandbox/gen_golden_parity.py --api-port 51823

Pure Python 3 standard library only (uses urllib, not httpx/requests, so it
has no dependency on a project virtualenv being active).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
from golden_common import (  # noqa: E402
    BASELINE_FORMAT_VERSION,
    extract_doc_identity,
    extract_top_identities,
    leg_scores,
)

DEFAULT_QUERIES_REL = "golden-queries.json"
DEFAULT_OUT_REL = "golden-parity.json"
TOP_N = 10
REQUEST_TIMEOUT_S = 30.0
LEG_MODES = ("vector", "text", "splade")


def find_repo_root(start: Path) -> Path:
    """Walk up from `start` until a directory containing gradlew.bat is found."""
    cur = start.resolve()
    for candidate in [cur, *cur.parents]:
        if (candidate / "gradlew.bat").exists():
            return candidate
    raise RuntimeError(
        f"Could not locate repo root (no gradlew.bat found walking up from {start})"
    )


def git_head(repo_root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=str(repo_root),
            capture_output=True,
            text=True,
            timeout=10,
        )
        if result.returncode == 0:
            return result.stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        pass
    return "UNKNOWN"


def post_search(base_url: str, query: str, limit: int = TOP_N, mode: str = "hybrid") -> dict[str, Any]:
    """POST /api/knowledge/search {query, limit, mode} and return the parsed body.

    `mode` defaults to "hybrid" (the golden baseline's primary leg). The
    public API also accepts "text"/"lexical" (BM25-only), "vector"
    (dense-only), and "splade" (SPLADE-only) -- used by `generate()` to
    capture per-leg top-10s (`SearchPipelinePresets.java:31-34`).
    """
    url = f"{base_url}/api/knowledge/search"
    body = json.dumps({"query": query, "limit": limit, "mode": mode}).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_S) as resp:
        raw = resp.read().decode("utf-8")
    return json.loads(raw)


def get_knowledge_status(base_url: str) -> dict[str, Any]:
    """GET /api/knowledge/status and return the parsed body.

    This is the same endpoint the model-identity audit verified: its
    `embeddingFingerprintCurrent` field IS the SHA-256 of the loaded embedding
    model file (dev's live value matched sha256(model_fp16.onnx) and the
    registry's declared hash for that asset) — a free, exact model-identity key
    already on the wire. It also carries `embeddingCompatState` and
    `indexedDocuments`, which is why this generator reads all three from here
    instead of `/api/status`.
    """
    url = f"{base_url}/api/knowledge/status"
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_S) as resp:
        raw = resp.read().decode("utf-8")
    return json.loads(raw)


HELP_DOC_PROBE_QUERY = "keyboard shortcuts"
HELP_DOC_PROBE_FILENAME = "keyboard-shortcuts.md"


def check_help_docs_indexed(base_url: str) -> str | None:
    """Corpus-comparability precondition (tempdoc 734 finding 5). Returns a
    REFUSING message, or None if satisfied.

    Every real (non-eval-mode) install auto-ingests 5 bundled help `.md` files
    under `SSOT/docs/help/` on boot (`KnowledgeServerBootstrap.tryIngestHelpFiles`
    — skipped ONLY in eval mode). A baseline generated against a dev stack that
    skipped this ingestion (e.g. started via jseval's `--start-backend`, which
    sets `justsearch.eval.mode=true`) is not corpus-comparable to any real
    Sandbox candidate, which always has them. Live-verified 2026-07-16: this
    corpus difference alone shifts 3 of 10 golden queries' top-10 by one
    document, even though the help docs never appear in the results
    themselves (indirect effect, most likely HNSW graph-structure sensitivity
    to insertion history) — small on its own, but silent and compounds with
    whatever else varies between the baseline dev stack and a real candidate."""
    try:
        response = post_search(base_url, HELP_DOC_PROBE_QUERY, limit=5)
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as exc:
        return f"could not query {base_url} to check for bundled help docs: {exc}"

    for hit in response.get("results") or []:
        filename = (hit.get("fields") or {}).get("filename", "")
        if filename == HELP_DOC_PROBE_FILENAME:
            return None

    return (
        f"REFUSING: a search for {HELP_DOC_PROBE_QUERY!r} did not surface "
        f"{HELP_DOC_PROBE_FILENAME!r} — the bundled help docs are not indexed on this dev "
        "stack. This baseline would not be corpus-comparable to a real Sandbox candidate, "
        "which always has them (KnowledgeServerBootstrap.tryIngestHelpFiles runs on every "
        "boot except eval mode). Start the dev stack WITHOUT eval mode (i.e. not via "
        "jseval's --start-backend / runHeadlessEval) so the 5 help docs get auto-ingested "
        "before generating this baseline."
    )


def queries_hash(queries: list[dict[str, Any]]) -> str:
    """Stable hash of the query set's (id, query) pairs so a golden baseline can be
    checked against drift in the query set itself, independent of file formatting."""
    canonical = json.dumps(
        [{"id": q.get("id"), "query": q.get("query")} for q in queries],
        sort_keys=True,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def calibration_block() -> dict[str, Any]:
    """Same-machine-population calibration metadata for the golden baseline.

    Records the axes that were actually sampled (HNSW rebuild noise, tempdoc
    734 + tempdoc 750 derisk) versus the axes deliberately held constant
    (GPU/driver, embedding-inference environment, index-insertion
    environment) while sampling them -- so a comparison that crosses one of
    the held-constant axes (e.g. dev vs. a Sandbox candidate on different
    hardware) can see explicitly that the overlap floors below were never
    measured across that axis, instead of silently trusting them there too."""
    return {
        "population": "same-machine-dev-rebuilds",
        "axesSampled": ["hnsw-rebuild-noise"],
        "axesHeldConstant": [
            "gpu+driver",
            "embedding-inference-env",
            "index-insertion-environment",
        ],
        "n": 3,
        "denseScoreEnvelopeAbs": 2.0e-4,
        "source": (
            "tempdoc 734 calibration (n=3 dev rebuilds) + tempdoc 750 derisk "
            "round5-vs-round6 dense-score deltas <= 1.8e-4"
        ),
        "note": (
            "dense-score envelope measured on the sandbox population (round5 vs round6, "
            "fingerprint-identical weights); overlap floors were sampled ONLY on the "
            "same-machine population - applying them cross-environment is exactly what "
            "this block exists to make visible"
        ),
    }


def generate(
    base_url: str, queries_doc: dict[str, Any], corpus: str, repo_root: Path
) -> dict[str, Any]:
    queries = queries_doc.get("queries", []) or []
    if not queries:
        sys.exit("Queries file has no 'queries' entries — nothing to generate.")

    # Provenance gate: refuse to generate a baseline against a backend that
    # isn't fully enriched + index-compatible. Without this, a baseline could
    # silently capture a NO_EMBEDDING_MODEL / incompatible-index round and
    # later comparisons would show a phantom ranking regression that is
    # really just "the round that generated the baseline wasn't meaningful."
    try:
        status = get_knowledge_status(base_url)
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as exc:
        sys.exit(
            f"Could not fetch /api/knowledge/status from {base_url}: {exc}. "
            "Is the dev backend running and reachable at --api-port?"
        )

    embedding_fingerprint = status.get("embeddingFingerprintCurrent")
    embedding_compat_state = status.get("embeddingCompatState")
    indexed_documents = status.get("indexedDocuments")

    if not embedding_fingerprint or embedding_compat_state != "COMPATIBLE":
        sys.exit(
            "REFUSING: /api/knowledge/status reports embeddingFingerprintCurrent="
            f"{embedding_fingerprint!r}, embeddingCompatState={embedding_compat_state!r}. A "
            "golden-parity baseline is only meaningful when the backend is fully enriched "
            "(embedding model loaded) and the index is compatible with it. Ingest/enrich the "
            "corpus and wait for embeddingCompatState=COMPATIBLE (poll /api/knowledge/status) "
            "before regenerating this baseline."
        )

    help_docs_error = check_help_docs_indexed(base_url)
    if help_docs_error:
        sys.exit(help_docs_error)

    results: list[dict[str, Any]] = []
    probe_executed_legs: list[str] | None = None
    for q in queries:
        qid = q.get("id")
        qtext = q.get("query")
        print(f"  querying {qid}: {qtext!r} ...", file=sys.stderr)
        try:
            response = post_search(base_url, qtext, TOP_N)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as exc:
            sys.exit(
                f"Query {qid!r} ({qtext!r}) failed against {base_url}: {exc}. "
                "Is the dev backend running and reachable at --api-port?"
            )

        hit_count = len(response.get("results") or [])
        if hit_count < TOP_N:
            sys.exit(
                f"REFUSING: query {qid!r} ({qtext!r}) returned only {hit_count} hit(s), "
                f"fewer than the required {TOP_N}. The '{corpus}' corpus is either too small "
                "or not fully ingested on this dev stack. Ingest it first, e.g. from "
                "scripts/jseval: `python -m jseval run --dataset scifact --modes hybrid --pipeline`, "
                "then re-run this generator."
            )

        if probe_executed_legs is None:
            # Record which retrieval legs actually ran for one probe query
            # (the first) so the baseline itself proves dense retrieval was
            # active when it was generated, not just asserted.
            stages = (response.get("searchTrace") or {}).get("stages") or []
            probe_executed_legs = sorted(
                {
                    stage.get("id")
                    for stage in stages
                    if stage.get("status") == "executed" and stage.get("id")
                }
            )

        top10 = extract_top_identities(response, TOP_N)

        # Per-hit leg scores (tempdoc 750 Part A): each hybrid hit's `trace`
        # array carries per-stage scores at full float precision. Recorded
        # keyed by the SAME normalized identity used in expectedTop10, so a
        # consumer can look up a leg's contribution without re-deriving
        # identity extraction.
        leg_scores_by_identity: dict[str, dict[str, float]] = {}
        for hit in (response.get("results") or [])[:TOP_N]:
            identity = extract_doc_identity(hit)
            if identity is None:
                continue
            leg_scores_by_identity[identity] = leg_scores(hit)

        # Per-leg top-10 (tempdoc 750 Part A): one additional POST per mode
        # (vector/text/splade) so a consumer can see what each retrieval leg
        # alone would have surfaced, independent of hybrid fusion. Unlike the
        # hybrid capture above, a leg returning fewer than TOP_N hits records
        # what it returns rather than failing -- only the hybrid <10 check
        # stays fail-closed.
        leg_top10: dict[str, list[str]] = {}
        for leg_mode in LEG_MODES:
            try:
                leg_response = post_search(base_url, qtext, TOP_N, mode=leg_mode)
            except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as exc:
                sys.exit(
                    f"Query {qid!r} ({qtext!r}) leg capture (mode={leg_mode!r}) failed "
                    f"against {base_url}: {exc}. Is the dev backend running and reachable "
                    "at --api-port?"
                )
            leg_top10[leg_mode] = extract_top_identities(leg_response, TOP_N)

        results.append(
            {
                "id": qid,
                "query": qtext,
                "kind": q.get("kind", ""),
                "expectedTop10": top10,
                "legScores": leg_scores_by_identity,
                "legTop10": leg_top10,
            }
        )

    return {
        "version": 1,
        "formatVersion": BASELINE_FORMAT_VERSION,
        "corpus": corpus,
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "gitHead": git_head(repo_root),
        "queriesHash": queries_hash(queries),
        # Provenance (model-identity audit, 2026-07-14). See
        # check_golden_parity.py's preconditions for how these are used.
        "embeddingFingerprint": embedding_fingerprint,
        "indexedDocuments": indexed_documents,
        "probeExecutedRetrievalLegs": probe_executed_legs or [],
        "calibration": calibration_block(),
        "queries": results,
    }


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Generate a per-candidate golden-parity baseline by running the fixed golden "
            "query set (golden-queries.json) against a RUNNING DEV STACK on the SAME build "
            "+ SAME corpus that the Sandbox round will validate. Host-side tool; run before "
            "staging a Sandbox round with sandbox-launch.py."
        )
    )
    parser.add_argument(
        "--api-port",
        type=int,
        required=True,
        help="Port of the running dev backend's local API (127.0.0.1:<port>)",
    )
    parser.add_argument(
        "--queries",
        type=str,
        default=None,
        help=f"Path to the golden query set (default: scripts/sandbox/{DEFAULT_QUERIES_REL})",
    )
    parser.add_argument(
        "--out",
        type=str,
        default=None,
        help=(
            f"Path to write the golden-parity baseline (default: scripts/sandbox/{DEFAULT_OUT_REL}). "
            "NOTE: this default is a convenience for ad-hoc local runs — the per-candidate "
            "baseline used by a real Sandbox round normally belongs in that round's staging "
            "dir (tmp/sandbox/...), which the operator picks explicitly with --out."
        ),
    )
    parser.add_argument(
        "--corpus",
        type=str,
        default="scifact",
        help="Corpus label recorded in the baseline metadata (default: scifact)",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])

    repo_root = find_repo_root(SCRIPT_DIR)
    queries_path = Path(args.queries) if args.queries else (SCRIPT_DIR / DEFAULT_QUERIES_REL)
    out_path = Path(args.out) if args.out else (SCRIPT_DIR / DEFAULT_OUT_REL)

    if not queries_path.is_file():
        sys.exit(f"Queries file not found: {queries_path}")

    queries_doc = json.loads(queries_path.read_text(encoding="utf-8"))
    base_url = f"http://127.0.0.1:{args.api_port}"

    print(f"Generating golden-parity baseline against {base_url} ({args.corpus})...", file=sys.stderr)
    baseline = generate(base_url, queries_doc, args.corpus, repo_root)

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(baseline, indent=2) + "\n", encoding="utf-8")

    print(f"Wrote {out_path} ({len(baseline['queries'])} queries, gitHead={baseline['gitHead']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
