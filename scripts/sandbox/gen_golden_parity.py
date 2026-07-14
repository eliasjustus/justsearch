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
from check_golden_parity import extract_top_identities  # noqa: E402

DEFAULT_QUERIES_REL = "golden-queries.json"
DEFAULT_OUT_REL = "golden-parity.json"
TOP_N = 10
REQUEST_TIMEOUT_S = 30.0


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


def post_search(base_url: str, query: str, limit: int = TOP_N) -> dict[str, Any]:
    """POST /api/knowledge/search {query, limit, mode: hybrid} and return the parsed body."""
    url = f"{base_url}/api/knowledge/search"
    body = json.dumps({"query": query, "limit": limit, "mode": "hybrid"}).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_S) as resp:
        raw = resp.read().decode("utf-8")
    return json.loads(raw)


def queries_hash(queries: list[dict[str, Any]]) -> str:
    """Stable hash of the query set's (id, query) pairs so a golden baseline can be
    checked against drift in the query set itself, independent of file formatting."""
    canonical = json.dumps(
        [{"id": q.get("id"), "query": q.get("query")} for q in queries],
        sort_keys=True,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def generate(
    base_url: str, queries_doc: dict[str, Any], corpus: str, repo_root: Path
) -> dict[str, Any]:
    queries = queries_doc.get("queries", []) or []
    if not queries:
        sys.exit("Queries file has no 'queries' entries — nothing to generate.")

    results: list[dict[str, Any]] = []
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

        top10 = extract_top_identities(response, TOP_N)
        results.append(
            {
                "id": qid,
                "query": qtext,
                "kind": q.get("kind", ""),
                "expectedTop10": top10,
            }
        )

    return {
        "version": 1,
        "corpus": corpus,
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "gitHead": git_head(repo_root),
        "queriesHash": queries_hash(queries),
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
