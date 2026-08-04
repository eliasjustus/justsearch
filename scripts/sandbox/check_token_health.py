#!/usr/bin/env python3
"""Token-health assertion over a sandbox round's traces.ndjson (tempdoc 805 Part G.4).

Round 11's blocker (a stale session token surviving a backend restart) left a
mechanical fingerprint in the trace: a POST/PUT/DELETE span rejected 401 in
under 5ms. That duration is the tell -- ApiSecurityFilters'
setupSessionTokenEnforcement `app.before` handler rejects a missing/invalid
token BEFORE the request reaches any real handler logic, so a genuine
auth-filter rejection completes in sub-millisecond time. A legitimate 401
that reached actual business logic (e.g. a downstream capability check) does
not look like this.

This promotes round 11's own discriminator from prose (docs/tempdocs/734 /
805) to a mechanical gate: FAIL (exit 1, listing offending spans) on any
sub-5ms 401 response to a mutating (POST/PUT/DELETE) span.

Allowlist: a span targeting `/mcp` is not a violation if a 200 on the same
`http.target` follows within 60 seconds -- an external MCP client probing
once without a token and then retrying with one is expected, not a defect.
Round 11's own traces.ndjson has exactly one such probe pair; every other
sub-5ms mutating 401 in that file (dozens, on /api/action-ledger/events and
/api/chat/agent/virtual-operations) is the genuine defect this check exists
to catch, so running this script against the real round-11 evidence file
must still FAIL overall.

NDJSON format (sandbox evidence, one JSON object per line): {"name":...,
"start": ISO-8601, "duration_ms": float, "attrs": {"http.method":...,
"http.status_code":..., "http.target":...}, ...}. Roughly 50 malformed lines
per file is normal for this format (see check_coverage.py's
load_exercised_endpoints) -- skipped and counted, never a failure on their
own.

Pure Python 3 stdlib. No network access.

Run: python scripts/sandbox/check_token_health.py <path/to/traces.ndjson>
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from datetime import datetime

MUTATING_METHODS = {"POST", "PUT", "DELETE"}
DURATION_MS_THRESHOLD = 5.0
ALLOWLIST_TARGET = "/mcp"
ALLOWLIST_WINDOW_SEC = 60.0


@dataclass
class HttpSpan:
    line_no: int
    start: datetime | None
    method: str | None
    status_code: str | None
    target: str | None
    duration_ms: float | None


def _parse_start(raw: object) -> datetime | None:
    """Parse an OTel-style ISO-8601 'start' timestamp (e.g.
    '2026-08-04T01:57:21.198Z'). Returns None on anything unparsable --
    callers must treat that as "can't time-window this span", not a crash.
    """
    if not isinstance(raw, str) or not raw:
        return None
    text = raw[:-1] + "+00:00" if raw.endswith("Z") else raw
    try:
        return datetime.fromisoformat(text)
    except ValueError:
        return None


def load_spans(path: str) -> tuple[list[HttpSpan], int]:
    """Parse traces.ndjson into HTTP spans (file order preserved) plus a
    count of skipped malformed/non-object lines.

    A line is "malformed" only if it fails to parse as JSON or isn't a JSON
    object -- a well-formed non-HTTP span (e.g. `composition.phase.*`, which
    carries no `http.method`/`http.target`) is skipped silently and NOT
    counted as malformed, since it isn't malformed at all.
    """
    spans: list[HttpSpan] = []
    skipped = 0
    with open(path, "r", encoding="utf-8") as fh:
        for line_no, raw_line in enumerate(fh, start=1):
            line = raw_line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                skipped += 1
                continue
            if not isinstance(obj, dict):
                skipped += 1
                continue

            attrs = obj.get("attrs")
            if not isinstance(attrs, dict):
                continue

            method = attrs.get("http.method")
            target = attrs.get("http.target") or attrs.get("http.route")
            if method is None and target is None:
                continue  # not an HTTP span

            status = attrs.get("http.status_code")
            duration = obj.get("duration_ms")
            try:
                duration_val = float(duration) if duration is not None else None
            except (TypeError, ValueError):
                duration_val = None

            spans.append(
                HttpSpan(
                    line_no=line_no,
                    start=_parse_start(obj.get("start")),
                    method=method.strip().upper() if isinstance(method, str) else None,
                    status_code=str(status).strip() if status is not None else None,
                    target=target if isinstance(target, str) else None,
                    duration_ms=duration_val,
                )
            )
    return spans, skipped


def _followed_within_window_by_200(spans: list[HttpSpan], index: int) -> bool:
    """True if some LATER span in `spans` targets the same http.target as
    spans[index], with http.status_code == "200", within
    ALLOWLIST_WINDOW_SEC seconds of spans[index]'s start.

    Deliberately scans the whole remainder of the list rather than stopping
    at the first out-of-window candidate: NDJSON line order tracks emission
    order, not a guaranteed-monotonic clock, so a later line can carry an
    earlier-in-window timestamp than one before it.
    """
    origin = spans[index]
    if origin.start is None or origin.target is None:
        return False
    for candidate in spans[index + 1 :]:
        if candidate.target != origin.target or candidate.start is None:
            continue
        delta = (candidate.start - origin.start).total_seconds()
        if 0 <= delta <= ALLOWLIST_WINDOW_SEC and candidate.status_code == "200":
            return True
    return False


def find_violations(spans: list[HttpSpan]) -> list[HttpSpan]:
    """Return the sub-5ms 401 mutating spans that are NOT allowlisted."""
    violations: list[HttpSpan] = []
    for i, span in enumerate(spans):
        if span.method not in MUTATING_METHODS:
            continue
        if span.status_code != "401":
            continue
        if span.duration_ms is None or span.duration_ms >= DURATION_MS_THRESHOLD:
            continue
        if span.target == ALLOWLIST_TARGET and _followed_within_window_by_200(spans, i):
            continue
        violations.append(span)
    return violations


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Fails on any sub-5ms 401 response to a POST/PUT/DELETE span in a sandbox "
            "round's traces.ndjson -- the auth-filter-rejection fingerprint round 11's "
            "stale-token blocker left behind (tempdoc 805 Part G.4)."
        )
    )
    parser.add_argument("traces", help="Path to the round's traces.ndjson")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)

    try:
        spans, skipped = load_spans(args.traces)
    except OSError as exc:
        print(f"ERROR: could not read traces file {args.traces!r}: {exc}", file=sys.stderr)
        return 2

    print(f"Parsed {len(spans)} HTTP span(s); skipped {skipped} malformed line(s).")

    violations = find_violations(spans)
    if violations:
        print("=" * 72)
        print(
            f"BLOCKING: {len(violations)} sub-{DURATION_MS_THRESHOLD:g}ms 401 response(s) to a "
            "mutating (POST/PUT/DELETE) request -- the auth-filter-rejection fingerprint "
            "(missing/stale session token, tempdoc 805 Part G.4):"
        )
        for span in violations:
            print(
                f"  - line {span.line_no}: {span.method} {span.target} -> 401 in "
                f"{span.duration_ms:.4f}ms (start={span.start})"
            )
        print("=" * 72)
        return 1

    print("OK: no sub-5ms 401 responses to mutating requests (token health holds).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
