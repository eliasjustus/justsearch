#!/usr/bin/env python3
"""Self-tests for gen_coverage_brief.py's Part D (tempdoc 750) reach-pointer
and mustWatch-derivability additions. gen_coverage_brief.py shipped with no
test file of its own; per the 750 implementation brief, these tests live in a
NEW file rather than test_check_coverage.py (owned by a different worker).

Covers, at minimum:

- reach round-trips register -> coverage-manifest.json (build_manifest
  carries a mustTouch item's `reach` verbatim when the testid is real).
- A fabricated/stale reach.testid is DROPPED from the manifest, with a
  printed (here: returned-as-warning) message naming the entry id and the
  testid -- generation continues, never emits an unverified testid (750
  Fork-risk control: a stale pointer is worse than none).
- `unknown: true` renders the "ENTRY POINT UNKNOWN - <note>" deliverable
  line, not a fabricated path.
- The register's `mustWatch` array reaches coverage-manifest.json verbatim.
- scan_data_testids() finds a literal `data-testid="..."` under a nested
  *.ts tree and ignores non-.ts files.

Run: python scripts/sandbox/test_gen_coverage_brief_reach.py
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from gen_coverage_brief import (  # noqa: E402
    build_manifest,
    classify,
    render_reach_line,
    run_drift_check,
    scan_data_testids,
    verify_reach_testids,
)


# run_drift_check hard-errors if ANY of the three derived sets (cohort/
# surface/shape) comes back empty (it reads that as "the source moved or the
# parser broke", not "this test doesn't care about that kind"). Every fixture
# below carries one filler cohort + one filler exempt surface/shape row so
# the derived sets are never empty regardless of what a given test actually
# exercises.
DUMMY_SURFACE_ID = "core.dummy-surface"
DUMMY_SHAPE_ID = "core.dummy-shape"


def _register(surface_coverage=None, shape_coverage=None, must_watch=None) -> dict:
    """A minimal register that passes run_drift_check cleanly against the
    matching derived sets from _cohort_routes()/_surface_placements()/
    _shapes() below -- one cohort, plus whatever surfaceCoverage/
    shapeCoverage rows the test supplies (plus the filler rows)."""
    surface_rows = list(surface_coverage or [])
    surface_rows.append(
        {"surfaceId": DUMMY_SURFACE_ID, "tier": "exempt", "reason": "filler row, keeps the derived surface set non-empty"}
    )
    shape_rows = list(shape_coverage or [])
    shape_rows.append(
        {"shape": DUMMY_SHAPE_ID, "tier": "exempt", "reason": "filler row, keeps the derived shape set non-empty"}
    )
    return {
        "cohortCoverage": [{"cohort": "c1", "tier": "sandbox", "validateHow": "x"}],
        "surfaceCoverage": surface_rows,
        "shapeCoverage": shape_rows,
        "cohortExempt": [],
        "surfaceExempt": [],
        "shapeExempt": [],
        "mustWatch": must_watch if must_watch is not None else [],
    }


def _cohort_routes() -> dict:
    return {"c1": ["GET /a"]}


def _surface_placements(extra: dict | None = None) -> dict:
    placements = {DUMMY_SURFACE_ID: "RAIL"}
    if extra:
        placements.update(extra)
    return placements


def _shapes(extra: list | None = None) -> list:
    ids = [DUMMY_SHAPE_ID]
    if extra:
        ids.extend(extra)
    return ids


class ScanDataTestidsTests(unittest.TestCase):
    def test_finds_testid_in_nested_ts_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            nested = root / "shell-v0" / "views"
            nested.mkdir(parents=True)
            (nested / "Foo.ts").write_text(
                'html`<div data-testid="foo-anchor"></div>`', encoding="utf-8"
            )
            found = scan_data_testids(root)
            self.assertIn("foo-anchor", found)

    def test_ignores_non_ts_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "Foo.md").write_text('data-testid="not-a-ts-file"', encoding="utf-8")
            found = scan_data_testids(root)
            self.assertEqual(found, set())

    def test_collects_multiple_testids_across_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "A.ts").write_text('data-testid="a-anchor"', encoding="utf-8")
            (root / "B.ts").write_text('data-testid="b-anchor"', encoding="utf-8")
            found = scan_data_testids(root)
            self.assertEqual(found, {"a-anchor", "b-anchor"})

    def test_empty_tree_returns_empty_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(scan_data_testids(Path(tmp)), set())


class RenderReachLineTests(unittest.TestCase):
    def test_none_reach_renders_none(self):
        self.assertIsNone(render_reach_line(None))

    def test_empty_reach_renders_none(self):
        self.assertIsNone(render_reach_line({}))

    def test_testid_renders_backtick_form(self):
        self.assertEqual(render_reach_line({"testid": "gallery-grid"}), "Reach: testid=`gallery-grid`")

    def test_navpath_renders(self):
        self.assertEqual(
            render_reach_line({"navPath": "Ctrl+K"}), "Reach: navPath: Ctrl+K"
        )

    def test_api_recipe_renders(self):
        self.assertEqual(
            render_reach_line({"apiRecipe": "GET /api/x"}), "Reach: apiRecipe: GET /api/x"
        )

    def test_unknown_true_renders_deliverable_line_verbatim(self):
        line = render_reach_line(
            {"unknown": True, "note": "no discoverable entry point as of round 6"}
        )
        self.assertEqual(
            line, "Reach: ENTRY POINT UNKNOWN - no discoverable entry point as of round 6"
        )

    def test_unknown_true_wins_over_a_stray_testid(self):
        line = render_reach_line({"unknown": True, "note": "n/a", "testid": "should-not-appear"})
        self.assertNotIn("should-not-appear", line)
        self.assertTrue(line.startswith("Reach: ENTRY POINT UNKNOWN"))


class VerifyReachTestidsTests(unittest.TestCase):
    """verify_reach_testids() mutates the register rows inside the classify()
    results in place, dropping any reach.testid that isn't in the known set,
    and returns one warning per drop naming the entry + testid."""

    def _results_for_surface(self, surface_row: dict) -> dict:
        return {
            "surface": classify(
                "surface", "surfaceId", {surface_row["surfaceId"]}, [surface_row], []
            ),
            "shape": classify("shape", "shape", set(), [], []),
        }

    def test_real_testid_survives_untouched(self):
        row = {
            "surfaceId": "core.x-surface",
            "tier": "sandbox",
            "validateHow": "v",
            "reach": {"testid": "real-anchor"},
        }
        results = self._results_for_surface(row)
        warnings = verify_reach_testids(results, {"real-anchor"})
        self.assertEqual(warnings, [])
        self.assertEqual(row["reach"], {"testid": "real-anchor"})

    def test_stale_testid_is_dropped_with_a_warning_naming_entry_and_testid(self):
        row = {
            "surfaceId": "core.x-surface",
            "tier": "sandbox",
            "validateHow": "v",
            "reach": {"testid": "totally-fabricated-testid"},
        }
        results = self._results_for_surface(row)
        warnings = verify_reach_testids(results, {"some-other-real-testid"})
        self.assertEqual(len(warnings), 1)
        self.assertIn("core.x-surface", warnings[0])
        self.assertIn("totally-fabricated-testid", warnings[0])
        self.assertIn("REACH-STALE-TESTID", warnings[0])
        # the testid must be gone from the row -- never emit an unverified testid
        self.assertNotIn("testid", row.get("reach", {}))

    def test_stale_testid_alone_drops_the_whole_reach_key_when_nothing_else_remains(self):
        row = {
            "surfaceId": "core.x-surface",
            "tier": "sandbox",
            "validateHow": "v",
            "reach": {"testid": "fabricated"},
        }
        results = self._results_for_surface(row)
        verify_reach_testids(results, set())
        self.assertNotIn("reach", row)

    def test_stale_testid_with_a_surviving_navpath_keeps_reach_minus_testid(self):
        row = {
            "surfaceId": "core.x-surface",
            "tier": "sandbox",
            "validateHow": "v",
            "reach": {"testid": "fabricated", "navPath": "still valid nav path"},
        }
        results = self._results_for_surface(row)
        verify_reach_testids(results, set())
        self.assertEqual(row["reach"], {"navPath": "still valid nav path"})

    def test_non_sandbox_tier_row_with_reach_is_still_checked(self):
        # verify_reach_testids doesn't filter by tier itself -- it operates on
        # whatever rows classify() indexed; tier filtering for what actually
        # ships happens later in build_manifest. A stale testid on a host-tier
        # row should still be caught (defense in depth).
        row = {
            "surfaceId": "core.x-surface",
            "tier": "host",
            "reach": {"testid": "fabricated"},
        }
        results = self._results_for_surface(row)
        warnings = verify_reach_testids(results, set())
        self.assertEqual(len(warnings), 1)

    def test_unknown_reach_has_no_testid_to_verify_and_is_left_alone(self):
        row = {
            "surfaceId": "core.x-surface",
            "tier": "sandbox",
            "validateHow": "v",
            "reach": {"unknown": True, "note": "n/a"},
        }
        results = self._results_for_surface(row)
        warnings = verify_reach_testids(results, set())
        self.assertEqual(warnings, [])
        self.assertEqual(row["reach"], {"unknown": True, "note": "n/a"})


class ReachRoundTripsRegisterToManifestTests(unittest.TestCase):
    """End-to-end through run_drift_check() + build_manifest(): a real
    reach.testid on a register row survives into coverage-manifest.json's
    mustTouch entry."""

    def test_reach_with_real_testid_appears_in_manifest_entry(self):
        surface_row = {
            "surfaceId": "core.brain-surface",
            "tier": "sandbox",
            "validateHow": "v",
            "reach": {"testid": "brain-simple-panel"},
        }
        register = _register(surface_coverage=[surface_row])
        errors, warnings, results = run_drift_check(
            register,
            _cohort_routes(),
            _surface_placements({"core.brain-surface": "RAIL"}),
            _shapes(),
            known_testids={"brain-simple-panel"},
        )
        self.assertEqual(errors, [])
        self.assertEqual(warnings, [])
        manifest = build_manifest(results, _cohort_routes(), register)
        entry = next(i for i in manifest["mustTouch"] if i["id"] == "core.brain-surface")
        self.assertEqual(entry["reach"], {"testid": "brain-simple-panel"})

    def test_unknown_reach_round_trips_into_manifest(self):
        shape_row = {
            "shape": "core.free-chat",
            "tier": "sandbox",
            "validateHow": "v",
            "reach": {"unknown": True, "note": "no discoverable entry point"},
        }
        register = _register(shape_coverage=[shape_row])
        errors, warnings, results = run_drift_check(
            register,
            _cohort_routes(),
            _surface_placements(),
            _shapes(["core.free-chat"]),
            known_testids=set(),
        )
        self.assertEqual(errors, [])
        manifest = build_manifest(results, _cohort_routes(), register)
        entry = next(i for i in manifest["mustTouch"] if i["id"] == "core.free-chat")
        self.assertEqual(entry["reach"], {"unknown": True, "note": "no discoverable entry point"})

    def test_fabricated_stale_testid_is_dropped_and_absent_from_manifest_with_a_warning(self):
        surface_row = {
            "surfaceId": "core.brain-surface",
            "tier": "sandbox",
            "validateHow": "v",
            "reach": {"testid": "this-testid-does-not-exist-anywhere"},
        }
        register = _register(surface_coverage=[surface_row])
        errors, warnings, results = run_drift_check(
            register,
            _cohort_routes(),
            _surface_placements({"core.brain-surface": "RAIL"}),
            _shapes(),
            known_testids={"some-unrelated-real-testid"},
        )
        self.assertEqual(errors, [])
        self.assertEqual(len(warnings), 1)
        self.assertIn("this-testid-does-not-exist-anywhere", warnings[0])
        self.assertIn("core.brain-surface", warnings[0])
        manifest = build_manifest(results, _cohort_routes(), register)
        entry = next(i for i in manifest["mustTouch"] if i["id"] == "core.brain-surface")
        self.assertNotIn("reach", entry)
        # and the raw testid string must not appear anywhere in the manifest
        import json

        self.assertNotIn("this-testid-does-not-exist-anywhere", json.dumps(manifest))

    def test_no_known_testids_argument_skips_reach_verification(self):
        # known_testids=None (the default) is the "caller doesn't care about
        # reach verification" mode -- e.g. a bare classify()-only caller.
        # This must not crash and must not drop anything.
        surface_row = {
            "surfaceId": "core.brain-surface",
            "tier": "sandbox",
            "validateHow": "v",
            "reach": {"testid": "whatever"},
        }
        register = _register(surface_coverage=[surface_row])
        errors, warnings, results = run_drift_check(
            register, _cohort_routes(), _surface_placements({"core.brain-surface": "RAIL"}), _shapes()
        )
        self.assertEqual(errors, [])
        self.assertEqual(warnings, [])
        manifest = build_manifest(results, _cohort_routes(), register)
        entry = next(i for i in manifest["mustTouch"] if i["id"] == "core.brain-surface")
        self.assertEqual(entry["reach"], {"testid": "whatever"})


class MustWatchInManifestTests(unittest.TestCase):
    def test_register_mustwatch_reaches_manifest_verbatim(self):
        must_watch = [
            {"id": "install-trust-prompts", "reason": "r", "observability": "blocked-by-posture", "note": "n"}
        ]
        register = _register(must_watch=must_watch)
        errors, warnings, results = run_drift_check(
            register, _cohort_routes(), _surface_placements(), _shapes()
        )
        self.assertEqual(errors, [])
        manifest = build_manifest(results, _cohort_routes(), register)
        self.assertEqual(manifest["mustWatch"], must_watch)

    def test_missing_mustwatch_key_defaults_to_empty_list_in_manifest(self):
        register = _register()
        del register["mustWatch"]
        errors, warnings, results = run_drift_check(
            register, _cohort_routes(), _surface_placements(), _shapes()
        )
        self.assertEqual(errors, [])
        manifest = build_manifest(results, _cohort_routes(), register)
        self.assertEqual(manifest["mustWatch"], [])

    def test_no_reach_on_a_row_is_backward_compatible_no_crash_no_key(self):
        # Old-style register row -- no 'reach' key at all. build_manifest must
        # not crash and must not fabricate a reach entry.
        surface_row = {"surfaceId": "core.brain-surface", "tier": "sandbox", "validateHow": "v"}
        register = _register(surface_coverage=[surface_row])
        errors, warnings, results = run_drift_check(
            register,
            _cohort_routes(),
            _surface_placements({"core.brain-surface": "RAIL"}),
            _shapes(),
            known_testids=set(),
        )
        self.assertEqual(errors, [])
        manifest = build_manifest(results, _cohort_routes(), register)
        entry = next(i for i in manifest["mustTouch"] if i["id"] == "core.brain-surface")
        self.assertNotIn("reach", entry)


if __name__ == "__main__":
    unittest.main()
