#!/usr/bin/env python3
"""Self-tests for check_token_health.py (tempdoc 805 Part G.4).

Round 11's blocker (a stale session token surviving a backend restart) left
a mechanical fingerprint in the trace: a POST/PUT/DELETE span rejected 401
in under 5ms -- the auth-filter `app.before` handler denying the request
before any real handler logic runs. This promotes that discriminator from
prose to a gate; these tests prove the gate actually bites.

Covers:
- load_spans(): parses HTTP spans, skips malformed/non-object lines
  (counted), silently ignores well-formed non-HTTP spans (not counted as
  malformed).
- find_violations(): flags a sub-5ms 401 on a mutating method; does NOT flag
  a >=5ms 401 on a mutating method; does NOT flag a 401 on a non-mutating
  (GET) method regardless of duration; does NOT flag a 200 regardless of
  duration.
- Allowlist: a sub-5ms 401 on /mcp followed by a 200 on /mcp within 60s is
  NOT a violation; the same span WITHOUT a following 200 (or with the 200
  outside the 60s window, or on a different target) IS a violation.
- The checked-in healthy fixture (fixtures/healthy-traces.ndjson) passes
  (exit 0, zero violations).
- The REAL round-11 evidence file (tmp/sandbox-round11/share/evidence/
  traces.ndjson, if present in this checkout) FAILS overall -- round 11's
  genuine defect (dozens of 401 action-ledger/virtual-operations spans) is
  the positive control this check exists to catch. Skipped (not failed) if
  that file isn't present, since it's local evidence output, not committed.

BITE PROOF (documented here, not committed): temporarily changing
find_violations()'s threshold comparison from `span.duration_ms >=
DURATION_MS_THRESHOLD` to `span.duration_ms >= 0` (i.e. "always big enough,
never a violation") makes every violation-detecting test in this file fail
closed-the-wrong-way (they expect violations and get none), AND the real
round-11 file test flips from "fails" to "wrongly passes". Restored after
confirming the failure register moved as expected.

Run: python scripts/sandbox/test_check_token_health.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
sys.path.insert(0, str(SCRIPT_DIR))

from check_token_health import (  # noqa: E402
    find_violations,
    load_spans,
    main,
)

HEALTHY_FIXTURE = SCRIPT_DIR / "fixtures" / "healthy-traces.ndjson"
ROUND_11_TRACES = REPO_ROOT / "tmp" / "sandbox-round11" / "share" / "evidence" / "traces.ndjson"


def _span(
    *,
    target: str,
    method: str = "POST",
    status: str = "401",
    duration_ms: float = 1.0,
    start: str = "2026-01-01T00:00:00.000Z",
) -> str:
    return (
        '{"name":"http.span","start":"%s","duration_ms":%s,'
        '"attrs":{"http.target":"%s","http.method":"%s","http.status_code":"%s"}}'
        % (start, duration_ms, target, method, status)
    )


def _write_lines(lines: list[str]) -> Path:
    tmp = tempfile.NamedTemporaryFile(
        mode="w", suffix=".ndjson", delete=False, encoding="utf-8"
    )
    try:
        for line in lines:
            tmp.write(line + "\n")
    finally:
        tmp.close()
    return Path(tmp.name)


class LoadSpansTests(unittest.TestCase):
    def test_malformed_and_non_object_lines_are_skipped_and_counted(self):
        path = _write_lines(
            [
                "not json at all",
                "[1, 2, 3]",
                _span(target="/api/x"),
            ]
        )
        try:
            spans, skipped = load_spans(str(path))
        finally:
            path.unlink()
        self.assertEqual(2, skipped)
        self.assertEqual(1, len(spans))

    def test_non_http_span_is_ignored_and_not_counted_as_malformed(self):
        path = _write_lines(
            [
                '{"name":"composition.phase.infra","start":"2026-01-01T00:00:00.000Z",'
                '"duration_ms":5.0,"attrs":{}}',
                _span(target="/api/x"),
            ]
        )
        try:
            spans, skipped = load_spans(str(path))
        finally:
            path.unlink()
        self.assertEqual(0, skipped, "a well-formed non-HTTP span is not malformed")
        self.assertEqual(1, len(spans))

    def test_blank_lines_are_skipped_silently(self):
        path = _write_lines(["", "   ", _span(target="/api/x")])
        try:
            spans, skipped = load_spans(str(path))
        finally:
            path.unlink()
        self.assertEqual(0, skipped)
        self.assertEqual(1, len(spans))


class FindViolationsTests(unittest.TestCase):
    def test_sub_5ms_401_on_mutating_method_is_a_violation(self):
        path = _write_lines([_span(target="/api/action-ledger/events", method="POST", status="401", duration_ms=0.5)])
        try:
            spans, _ = load_spans(str(path))
        finally:
            path.unlink()
        violations = find_violations(spans)
        self.assertEqual(1, len(violations))
        self.assertEqual("/api/action-ledger/events", violations[0].target)

    def test_401_at_exactly_the_threshold_is_not_a_violation(self):
        # DURATION_MS_THRESHOLD is exclusive on the high side (< 5, not <= 5).
        path = _write_lines([_span(target="/api/x", method="POST", status="401", duration_ms=5.0)])
        try:
            spans, _ = load_spans(str(path))
        finally:
            path.unlink()
        self.assertEqual(0, len(find_violations(spans)))

    def test_slow_401_on_mutating_method_is_not_a_violation(self):
        path = _write_lines([_span(target="/api/x", method="POST", status="401", duration_ms=45.0)])
        try:
            spans, _ = load_spans(str(path))
        finally:
            path.unlink()
        self.assertEqual(0, len(find_violations(spans)))

    def test_fast_401_on_get_is_not_a_violation(self):
        path = _write_lines([_span(target="/api/x", method="GET", status="401", duration_ms=0.5)])
        try:
            spans, _ = load_spans(str(path))
        finally:
            path.unlink()
        self.assertEqual(0, len(find_violations(spans)))

    def test_fast_200_on_mutating_method_is_not_a_violation(self):
        path = _write_lines([_span(target="/api/x", method="POST", status="200", duration_ms=0.5)])
        try:
            spans, _ = load_spans(str(path))
        finally:
            path.unlink()
        self.assertEqual(0, len(find_violations(spans)))

    def test_put_and_delete_are_also_mutating(self):
        path = _write_lines(
            [
                _span(target="/api/a", method="PUT", status="401", duration_ms=0.5),
                _span(target="/api/b", method="DELETE", status="401", duration_ms=0.5),
            ]
        )
        try:
            spans, _ = load_spans(str(path))
        finally:
            path.unlink()
        self.assertEqual(2, len(find_violations(spans)))


class McpAllowlistTests(unittest.TestCase):
    def test_mcp_401_followed_by_200_within_window_is_allowlisted(self):
        path = _write_lines(
            [
                _span(target="/mcp", status="401", duration_ms=2.0, start="2026-01-01T00:00:00.000Z"),
                _span(target="/mcp", status="200", duration_ms=1.0, start="2026-01-01T00:00:30.000Z"),
            ]
        )
        try:
            spans, _ = load_spans(str(path))
        finally:
            path.unlink()
        self.assertEqual(0, len(find_violations(spans)))

    def test_mcp_401_with_no_following_200_is_still_a_violation(self):
        path = _write_lines([_span(target="/mcp", status="401", duration_ms=2.0)])
        try:
            spans, _ = load_spans(str(path))
        finally:
            path.unlink()
        self.assertEqual(1, len(find_violations(spans)))

    def test_mcp_401_followed_by_200_outside_window_is_still_a_violation(self):
        path = _write_lines(
            [
                _span(target="/mcp", status="401", duration_ms=2.0, start="2026-01-01T00:00:00.000Z"),
                _span(target="/mcp", status="200", duration_ms=1.0, start="2026-01-01T00:02:00.000Z"),  # 120s later
            ]
        )
        try:
            spans, _ = load_spans(str(path))
        finally:
            path.unlink()
        self.assertEqual(1, len(find_violations(spans)))

    def test_mcp_401_followed_by_200_on_a_different_target_is_still_a_violation(self):
        path = _write_lines(
            [
                _span(target="/mcp", status="401", duration_ms=2.0, start="2026-01-01T00:00:00.000Z"),
                _span(target="/api/other", status="200", duration_ms=1.0, start="2026-01-01T00:00:05.000Z"),
            ]
        )
        try:
            spans, _ = load_spans(str(path))
        finally:
            path.unlink()
        self.assertEqual(1, len(find_violations(spans)))

    def test_non_mcp_target_gets_no_allowlist_even_with_a_following_200(self):
        # The allowlist is /mcp-specific -- a webview endpoint doesn't get the
        # same "external client probing" benefit of the doubt.
        path = _write_lines(
            [
                _span(target="/api/action-ledger/events", status="401", duration_ms=0.5, start="2026-01-01T00:00:00.000Z"),
                _span(target="/api/action-ledger/events", status="200", duration_ms=1.0, start="2026-01-01T00:00:05.000Z"),
            ]
        )
        try:
            spans, _ = load_spans(str(path))
        finally:
            path.unlink()
        self.assertEqual(1, len(find_violations(spans)))


class FixtureTests(unittest.TestCase):
    def test_healthy_fixture_passes(self):
        self.assertTrue(HEALTHY_FIXTURE.is_file(), f"missing fixture: {HEALTHY_FIXTURE}")
        exit_code = main([str(HEALTHY_FIXTURE)])
        self.assertEqual(0, exit_code)

    def test_healthy_fixture_has_zero_violations(self):
        spans, _ = load_spans(str(HEALTHY_FIXTURE))
        self.assertEqual(0, len(find_violations(spans)))

    def test_round_11_real_evidence_file_fails_overall(self):
        """Positive control: round 11's actual traces.ndjson carries the
        genuine defect (dozens of 401 action-ledger/virtual-operations
        spans) this check exists to catch, so it must FAIL (exit 1) even
        with the /mcp allowlist applied.
        """
        if not ROUND_11_TRACES.is_file():
            self.skipTest(f"round-11 evidence file not present in this checkout: {ROUND_11_TRACES}")
        exit_code = main([str(ROUND_11_TRACES)])
        self.assertEqual(1, exit_code, "round-11's genuine defect must fail this check")

        spans, _ = load_spans(str(ROUND_11_TRACES))
        violations = find_violations(spans)
        self.assertGreater(
            len(violations), 0, "round-11 must produce at least one non-allowlisted violation"
        )
        # The /mcp probe pair must be allowlisted away, not merely absent from the file.
        mcp_violations = [v for v in violations if v.target == "/mcp"]
        self.assertEqual(
            0, len(mcp_violations), "round-11's single /mcp probe-then-retry pair must be allowlisted"
        )


if __name__ == "__main__":
    unittest.main()
