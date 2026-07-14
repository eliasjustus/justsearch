#!/usr/bin/env python3
"""FINALIZE-time coverage assertion for a JustSearch Sandbox validation round.

Diffs what a round was REQUIRED to exercise (a generated must-touch manifest,
see gen_coverage_brief.py) against what it ACTUALLY exercised (endpoint traces
+ evidence screenshots), and fails closed on any untouched required surface.

This is the fail-closed half of "coverage follows shipment" (tempdoc 728): it
lives where coverage happens (the validation round), not in CI.

Pure Python 3 stdlib. No network access.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass, field
from typing import Iterable


# UI surfaces/shapes are proven by SCREENSHOTS, not by the mechanical API
# snapshots the capture harness (collect-evidence.ps1) also drops into the
# evidence dir (e.g. api-api-health.json). Crediting a surface token from an
# api-*.json filename is a false positive (tempdoc 728 review, defect F1), so
# surface/shape matching considers only image files.
IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp"}


# --------------------------------------------------------------------------
# Data model
# --------------------------------------------------------------------------


@dataclass
class MustTouchItem:
    kind: str
    id: str
    tier: str
    validate_how: str
    routes: list[str] = field(default_factory=list)
    required_routes: list[str] = field(default_factory=list)
    evidence_token: str | None = None

    @staticmethod
    def from_dict(raw: dict) -> "MustTouchItem":
        return MustTouchItem(
            kind=raw.get("kind", ""),
            id=raw.get("id", ""),
            tier=raw.get("tier", ""),
            validate_how=raw.get("validateHow", ""),
            routes=list(raw.get("routes", []) or []),
            required_routes=list(raw.get("requiredRoutes", []) or []),
            evidence_token=raw.get("evidenceToken"),
        )


@dataclass
class CoverageResult:
    item: MustTouchItem
    covered: bool
    reason: str


# --------------------------------------------------------------------------
# Manifest loading
# --------------------------------------------------------------------------


def load_manifest(path: str) -> dict:
    """Load and return the coverage manifest. Caller handles I/O/JSON errors."""
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


# --------------------------------------------------------------------------
# Trace parsing -> exercised endpoint set
# --------------------------------------------------------------------------


def load_exercised_endpoints(path: str | None) -> tuple[set[tuple[str, str]], set[str]]:
    """Parse traces.ndjson and return (exercised_pairs, exercised_paths).

    734-followup fix (method-blind route matching, verified defect): the prior
    version returned a single path-only set, so `requiredRoutes: ["POST /mcp"]`
    was satisfiable by a DELETE-only (or any-method) trace on the same path.

    - `exercised_pairs`: {(METHOD, path)} built from every span that carries a
      non-empty `attrs["http.method"]`.
    - `exercised_paths`: every endpoint path seen regardless of method — the
      fallback set a *bare* (no-method-prefix) route still matches against, and
      also covers spans that lack an `http.method` attr entirely.

    For every line whose span `name` starts with "http." (or which carries
    an `attrs["http.method"]`), the endpoint path is
    `attrs.get("http.route")`, falling back to `attrs.get("http.target")`.
    Non-http spans are ignored. Malformed lines are skipped with a warning,
    not a crash. A missing file yields empty sets plus a warning.
    """
    exercised_pairs: set[tuple[str, str]] = set()
    exercised_paths: set[str] = set()

    if not path:
        print("WARNING: no --traces path given; exercised-endpoint set is empty.", file=sys.stderr)
        return exercised_pairs, exercised_paths

    if not os.path.isfile(path):
        print(f"WARNING: traces file not found: {path!r} (treating as empty).", file=sys.stderr)
        return exercised_pairs, exercised_paths

    with open(path, "r", encoding="utf-8") as fh:
        for line_no, raw_line in enumerate(fh, start=1):
            line = raw_line.strip()
            if not line:
                continue
            try:
                span = json.loads(line)
            except json.JSONDecodeError as exc:
                print(
                    f"WARNING: skipping malformed trace line {line_no}: {exc}",
                    file=sys.stderr,
                )
                continue

            if not isinstance(span, dict):
                print(
                    f"WARNING: skipping non-object trace line {line_no}.",
                    file=sys.stderr,
                )
                continue

            name = span.get("name", "")
            attrs = span.get("attrs", {}) or {}
            is_http_span = isinstance(name, str) and name.startswith("http.")
            has_http_method = isinstance(attrs, dict) and "http.method" in attrs

            if not (is_http_span or has_http_method):
                continue

            if not isinstance(attrs, dict):
                print(
                    f"WARNING: skipping trace line {line_no} with non-object attrs.",
                    file=sys.stderr,
                )
                continue

            endpoint = attrs.get("http.route") or attrs.get("http.target")
            if not endpoint:
                continue

            exercised_paths.add(endpoint)
            method = attrs.get("http.method")
            if isinstance(method, str) and method.strip():
                exercised_pairs.add((method.strip().upper(), endpoint))

    return exercised_pairs, exercised_paths


# --------------------------------------------------------------------------
# Evidence directory -> filename set
# --------------------------------------------------------------------------


def load_evidence_filenames(path: str | None) -> set[str]:
    """Collect the set of lowercased evidence filenames in `path`.

    A missing directory yields an empty set plus a warning.
    """
    filenames: set[str] = set()

    if not path:
        print("WARNING: no --evidence-dir given; evidence set is empty.", file=sys.stderr)
        return filenames

    if not os.path.isdir(path):
        print(f"WARNING: evidence dir not found: {path!r} (treating as empty).", file=sys.stderr)
        return filenames

    for entry in os.listdir(path):
        full = os.path.join(path, entry)
        if os.path.isfile(full):
            filenames.add(entry.lower())

    return filenames


# --------------------------------------------------------------------------
# Coverage logic
# --------------------------------------------------------------------------


def parse_route(route: str) -> tuple[str | None, str]:
    """Split a route string into (METHOD, path).

    "POST /mcp" -> ("POST", "/mcp"). A route with no method prefix (no
    whitespace) returns (None, path) — a bare path, matched method-blind.
    """
    parts = route.split(" ", 1)
    if len(parts) == 2:
        return parts[0].strip().upper(), parts[1].strip()
    return None, route.strip()


def route_path(route: str) -> str:
    """Strip the leading "<METHOD> " from a route string, returning the path.

    "POST /mcp" -> "/mcp". A route with no method prefix (no whitespace) is
    returned unchanged.
    """
    _, path = parse_route(route)
    return path


def route_matches(
    route: str, exercised_pairs: set[tuple[str, str]], exercised_paths: set[str]
) -> bool:
    """734-followup fix: a route carrying a method prefix (every route in this
    checker's inputs does — both requiredRoutes and manifest-derived routes are
    "METHOD path" strings) must match the exact (method, path) pair; a bare path
    (no method prefix) keeps the old method-blind path-only match."""
    method, path = parse_route(route)
    if method is None:
        return path in exercised_paths
    return (method, path) in exercised_pairs


def shape_token(shape_id: str) -> str:
    """Derive the evidence-filename search token for a `shape` mustTouch item.

    `core.rag-ask` -> `rag-ask` (strip the leading "core." prefix if present).
    """
    prefix = "core."
    if shape_id.startswith(prefix):
        return shape_id[len(prefix):]
    return shape_id


def check_cohort(
    item: MustTouchItem, exercised_pairs: set[tuple[str, str]], exercised_paths: set[str]
) -> CoverageResult:
    # requiredRoutes (tempdoc 728 review, defect F2): when a cohort names the
    # specific product route(s) that MUST be hit (e.g. mcp -> "POST /mcp"),
    # any-route OR-semantics is a hole — GET /api/mcp/token would satisfy the
    # cohort without ever touching the endpoint the release exists for. So when
    # requiredRoutes is present, EVERY required route must be exercised (AND).
    # 734-followup fix (defect: method-blind matching): route_matches() now
    # requires the exact (METHOD, path) pair for a method-prefixed route, so
    # a DELETE-only (or any-other-method) trace can no longer satisfy a
    # "POST /mcp" requirement.
    if item.required_routes:
        missing = [
            r for r in item.required_routes if not route_matches(r, exercised_pairs, exercised_paths)
        ]
        if missing:
            return CoverageResult(
                item=item,
                covered=False,
                reason=f"required route(s) not exercised: {missing!r}",
            )
        return CoverageResult(
            item=item,
            covered=True,
            reason=f"all required route(s) {item.required_routes!r} exercised",
        )

    matched_route = None
    for route in item.routes:
        # Baseline: exact (method, path) match on the route template (both the
        # manifest route and the trace's http.method/http.route are Javalin
        # registration strings).
        if route_matches(route, exercised_pairs, exercised_paths):
            matched_route = route
            break

    if matched_route is not None:
        return CoverageResult(
            item=item,
            covered=True,
            reason=f"route {matched_route!r} exercised (matched path {route_path(matched_route)!r})",
        )
    return CoverageResult(
        item=item,
        covered=False,
        reason=f"none of routes {item.routes!r} found in exercised endpoints",
    )


def check_surface(item: MustTouchItem, evidence_filenames: set[str]) -> CoverageResult:
    token = (item.evidence_token or "").lower()
    if not token:
        return CoverageResult(
            item=item,
            covered=False,
            reason="surface item has no evidenceToken to match against",
        )

    for filename in evidence_filenames:
        if token in filename:
            return CoverageResult(
                item=item,
                covered=True,
                reason=f"evidenceToken {item.evidence_token!r} matched evidence file {filename!r}",
            )
    return CoverageResult(
        item=item,
        covered=False,
        reason=f"evidenceToken {item.evidence_token!r} not found in any evidence filename",
    )


def check_shape(item: MustTouchItem, evidence_filenames: set[str]) -> CoverageResult:
    token = shape_token(item.id).lower()
    for filename in evidence_filenames:
        if token in filename:
            return CoverageResult(
                item=item,
                covered=True,
                reason=f"shape token {token!r} matched evidence file {filename!r}",
            )
    return CoverageResult(
        item=item,
        covered=False,
        reason=f"shape token {token!r} not found in any evidence filename",
    )


def evaluate_item(
    item: MustTouchItem,
    exercised_pairs: set[tuple[str, str]],
    exercised_paths: set[str],
    evidence_filenames: set[str],
) -> CoverageResult:
    if item.kind == "cohort":
        return check_cohort(item, exercised_pairs, exercised_paths)
    if item.kind == "surface":
        return check_surface(item, evidence_filenames)
    if item.kind == "shape":
        return check_shape(item, evidence_filenames)
    return CoverageResult(
        item=item,
        covered=False,
        reason=f"unrecognized mustTouch kind {item.kind!r}",
    )


def sort_key(item: MustTouchItem) -> tuple[str, str]:
    return (item.kind, item.id)


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------


def print_report(
    results: list[CoverageResult],
    covered_elsewhere: Iterable[dict],
    exempt: Iterable[dict],
) -> bool:
    """Print the deterministic coverage report. Returns True iff all covered."""
    covered_results = [r for r in results if r.covered]
    uncovered_results = [r for r in results if not r.covered]

    print("=" * 72)
    print("Sandbox coverage report (tier=sandbox mustTouch items)")
    print("=" * 72)

    for result in sorted(results, key=lambda r: sort_key(r.item)):
        status = "COVERED" if result.covered else "UNCOVERED"
        marker = "  " if result.covered else "**"
        print(f"{marker}[{status}]{marker} {result.item.kind}:{result.item.id}")
        print(f"    validateHow: {result.item.validate_how}")
        print(f"    reason:      {result.reason}")

    print("-" * 72)
    print(
        f"Sandbox mustTouch items: {len(results)} total, "
        f"{len(covered_results)} covered, {len(uncovered_results)} UNCOVERED"
    )

    covered_elsewhere = sorted(
        (str(entry.get("id", entry)) for entry in covered_elsewhere),
    )
    exempt_sorted = sorted(str(entry.get("id", entry)) for entry in exempt)

    print("-" * 72)
    print(f"coveredElsewhere (informational, never fails): {len(covered_elsewhere)}")
    for entry_id in covered_elsewhere:
        print(f"    - {entry_id}")

    print(f"exempt (informational, never fails): {len(exempt_sorted)}")
    for entry_id in exempt_sorted:
        print(f"    - {entry_id}")

    if uncovered_results:
        print("=" * 72)
        print("BLOCKING: the following sandbox-tier mustTouch items were not exercised:")
        for result in sorted(uncovered_results, key=lambda r: sort_key(r.item)):
            print(f"  - {result.item.kind}:{result.item.id} ({result.reason})")
        print("=" * 72)

    return len(uncovered_results) == 0


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "FINALIZE-time coverage assertion for a JustSearch Sandbox "
            "validation round (tempdoc 728)."
        )
    )
    parser.add_argument(
        "--manifest",
        required=True,
        help="Path to coverage-manifest.json produced by gen_coverage_brief.py",
    )
    parser.add_argument(
        "--traces",
        required=False,
        default=None,
        help="Path to the round's traces.ndjson (OpenTelemetry NDJSON)",
    )
    parser.add_argument(
        "--evidence-dir",
        required=False,
        default=None,
        help="Path to the round's evidence directory (screenshots etc.)",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    try:
        manifest = load_manifest(args.manifest)
    except OSError as exc:
        print(f"ERROR: could not read manifest {args.manifest!r}: {exc}", file=sys.stderr)
        return 2
    except json.JSONDecodeError as exc:
        print(f"ERROR: manifest {args.manifest!r} is not valid JSON: {exc}", file=sys.stderr)
        return 2

    all_items = [MustTouchItem.from_dict(raw) for raw in manifest.get("mustTouch", []) or []]
    sandbox_items = [item for item in all_items if item.tier == "sandbox"]

    exercised_pairs, exercised_paths = load_exercised_endpoints(args.traces)
    evidence_filenames = load_evidence_filenames(args.evidence_dir)
    # F1: only screenshots count as UI-surface/shape evidence — the API-snapshot
    # JSON the harness also writes here must not satisfy a surface token.
    screenshots = {
        f for f in evidence_filenames if os.path.splitext(f)[1].lower() in IMAGE_EXTS
    }
    if evidence_filenames and not screenshots:
        print(
            "WARNING: evidence dir has files but no image/screenshot files; UI "
            "surfaces are proven by screenshots (not API snapshots) and will read "
            "UNCOVERED.",
            file=sys.stderr,
        )

    results = [
        evaluate_item(item, exercised_pairs, exercised_paths, screenshots) for item in sandbox_items
    ]

    all_covered = print_report(
        results,
        covered_elsewhere=manifest.get("coveredElsewhere", []) or [],
        exempt=manifest.get("exempt", []) or [],
    )

    return 0 if all_covered else 1


if __name__ == "__main__":
    sys.exit(main())
