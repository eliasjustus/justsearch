#!/usr/bin/env python3
"""Self-tests for derive_round_plan.py (tempdoc 729-followup mechanical
brief-to-plan derivation; extended tempdoc 750 Part D for reach pointers +
mustWatch derivability). Shipped with zero tests originally.

Covers, at minimum (per the brief this test file closes):

- The `requiredRoutes` vs `routes` distinction -- the exact case a real round
  got wrong. A cohort can carry BOTH keys (e.g. the real `mcp` cohort in
  coverage-manifest.json: `routes` lists 3 endpoints, `requiredRoutes` lists
  only `POST /mcp`); when `requiredRoutes` is present it means ALL of those
  routes are mandatory, not "any ONE of `routes`". The rendered checklist item
  must say so unambiguously.
- `mustWatch` items are declared-not-covered, not silently dropped. Before
  tempdoc 750 Part D, the manifest schema had no `mustWatch` key at all, so
  the script could not mechanically list them -- the rendered plan had to say
  explicitly that they exist and are not on this checklist. Part D adds the
  key; a manifest WITHOUT it (older schema) must still fall back to that old
  explicit-not-derivable text (backward compat), while a manifest WITH it
  (even an empty list) gets a real derived checklist section instead.
- Part D reach pointers (testid / navPath / apiRecipe / unknown) render as a
  "Reach:" line under each mustTouch checklist item when present, and are
  silently absent (no crash, no line) when an item carries no `reach` key --
  the pre-750 case.
- tier filtering (sandbox-only), end-to-end main() wiring (stdout vs --out,
  I/O and JSON error codes), and the smaller per-kind renderers.

Run: python scripts/sandbox/test_derive_round_plan.py
"""

from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from derive_round_plan import (  # noqa: E402
    load_manifest,
    main,
    must_watch_items,
    render_cohort_item,
    render_must_watch_section,
    render_plan,
    render_reach_line,
    render_shape_item,
    render_surface_item,
    sandbox_must_touch,
    shape_token,
)


def _manifest(must_touch=None, covered_elsewhere=None, exempt=None, must_watch=None) -> dict:
    manifest: dict = {
        "version": 1,
        "mustTouch": must_touch or [],
        "coveredElsewhere": covered_elsewhere or [],
        "exempt": exempt or [],
    }
    # Only add the 'mustWatch' key when explicitly given -- omitting it by
    # default reproduces the pre-750 manifest schema shape the backward-compat
    # tests need (see MustWatchSectionTests / the legacy MustWatchDeclaredNot-
    # CoveredTests below).
    if must_watch is not None:
        manifest["mustWatch"] = must_watch
    return manifest


class ShapeTokenTests(unittest.TestCase):
    def test_strips_core_prefix(self):
        self.assertEqual(shape_token("core.rag-ask"), "rag-ask")

    def test_leaves_non_core_prefixed_id_unchanged(self):
        self.assertEqual(shape_token("rag-ask"), "rag-ask")


class SandboxMustTouchTests(unittest.TestCase):
    def test_filters_to_sandbox_tier_only(self):
        manifest = _manifest(must_touch=[
            {"kind": "cohort", "id": "agent", "tier": "sandbox"},
            {"kind": "cohort", "id": "host-only-thing", "tier": "host"},
        ])
        items = sandbox_must_touch(manifest)
        self.assertEqual([i["id"] for i in items], ["agent"])

    def test_missing_musttouch_key_is_empty(self):
        self.assertEqual(sandbox_must_touch({}), [])

    def test_null_musttouch_value_is_empty_not_a_crash(self):
        self.assertEqual(sandbox_must_touch({"mustTouch": None}), [])


class RequiredRoutesVsRoutesTests(unittest.TestCase):
    """The case a real round got wrong: a cohort with BOTH `routes` (any-one
    style) and `requiredRoutes` (all-of style) must be planned as an ALL
    requirement, not an any-ONE requirement -- exactly the real `mcp` cohort's
    shape in coverage-manifest.json (routes: 3 entries, requiredRoutes:
    ["POST /mcp"])."""

    def test_required_routes_present_uses_ALL_wording_not_any_one(self):
        item = {
            "kind": "cohort",
            "id": "mcp",
            "validateHow": "x",
            "routes": ["DELETE /mcp", "GET /api/mcp/token", "POST /mcp"],
            "requiredRoutes": ["POST /mcp"],
        }
        text = "\n".join(render_cohort_item(item))
        self.assertIn("REQUIRED routes (ALL must be hit): POST /mcp", text)
        self.assertNotIn("any ONE of these routes must be hit", text)

    def test_routes_only_uses_any_ONE_wording(self):
        item = {
            "kind": "cohort",
            "id": "agent",
            "validateHow": "x",
            "routes": ["GET /api/agent/hard-stop", "POST /api/agent/hard-stop"],
        }
        text = "\n".join(render_cohort_item(item))
        self.assertIn(
            "any ONE of these routes must be hit: GET /api/agent/hard-stop, POST /api/agent/hard-stop", text
        )
        self.assertNotIn("REQUIRED routes", text)

    def test_neither_routes_nor_required_routes_flags_missing_declaration(self):
        item = {"kind": "cohort", "id": "empty-cohort", "validateHow": "x"}
        text = "\n".join(render_cohort_item(item))
        self.assertIn("no routes declared on this item", text)

    def test_empty_required_routes_list_falls_back_to_any_one_routes_wording(self):
        # requiredRoutes: [] is falsy (mirrors check_coverage.py's own
        # `if item.required_routes` truthiness gate) -- an empty list must NOT
        # be treated as "ALL of nothing satisfied"; the plan must still render
        # the any-ONE `routes` requirement instead of silently declaring the
        # item satisfied or undeclared.
        item = {
            "kind": "cohort",
            "id": "x",
            "validateHow": "x",
            "routes": ["GET /a"],
            "requiredRoutes": [],
        }
        text = "\n".join(render_cohort_item(item))
        self.assertIn("any ONE of these routes must be hit: GET /a", text)
        self.assertNotIn("REQUIRED routes", text)


class RenderSurfaceAndShapeItemTests(unittest.TestCase):
    def test_surface_item_names_evidence_token(self):
        item = {"kind": "surface", "id": "core.memory-surface", "validateHow": "x", "evidenceToken": "memory"}
        text = "\n".join(render_surface_item(item))
        self.assertIn("surface:core.memory-surface", text)
        self.assertIn("filename contains `memory`", text)

    def test_shape_item_uses_shape_token_not_raw_id(self):
        item = {"kind": "shape", "id": "core.rag-ask", "validateHow": "x"}
        text = "\n".join(render_shape_item(item))
        self.assertIn("shape:core.rag-ask", text)
        self.assertIn("filename contains `rag-ask`", text)
        self.assertNotIn("filename contains `core.rag-ask`", text)


class MustWatchDeclaredNotCoveredTests(unittest.TestCase):
    """mustWatch items (e.g. warm-reinstall-over-existing-data) have no key in
    coverage-manifest.json's schema, so derive_round_plan.py CANNOT mechanically
    list them -- but the plan must say so explicitly rather than producing a
    checklist that silently looks complete. A round trusting only this
    checklist must be told must-watch items exist and live elsewhere, not left
    to assume the checklist is the whole ask (the exact 'declared, not silently
    omitted' behaviour the module docstring commits to)."""

    def test_plan_explicitly_flags_mustwatch_items_are_not_included(self):
        manifest = _manifest(must_touch=[{"kind": "cohort", "id": "agent", "tier": "sandbox", "routes": ["GET /a"]}])
        text = render_plan(manifest, "coverage-manifest.json")
        self.assertIn("must-watch items are NOT on this checklist", text)

    def test_plan_names_coverage_brief_as_the_mustwatch_source_and_cannot_derive(self):
        manifest = _manifest()
        text = render_plan(manifest, "coverage-manifest.json")
        self.assertIn("coverage-brief.md", text)
        self.assertIn("cannot be mechanically derived here", text)

    def test_reminder_present_even_when_musttouch_is_empty(self):
        # The reminder is not conditional on there being anything else to
        # show -- an empty round-plan must still surface it.
        manifest = _manifest(must_touch=[])
        text = render_plan(manifest, "coverage-manifest.json")
        self.assertIn("## Reminder: must-watch items are NOT on this checklist", text)


class ReachLineRenderingTests(unittest.TestCase):
    """Part D (tempdoc 750): render_reach_line() renders a mustTouch item's
    optional `reach` pointer as a single checklist line."""

    def test_missing_reach_renders_nothing(self):
        self.assertIsNone(render_reach_line(None))

    def test_empty_reach_dict_renders_nothing(self):
        self.assertIsNone(render_reach_line({}))

    def test_testid_only(self):
        line = render_reach_line({"testid": "escalation-delegate"})
        self.assertEqual(line, "      Reach: testid=`escalation-delegate`")

    def test_navpath_only(self):
        line = render_reach_line({"navPath": "Ctrl+K command palette"})
        self.assertEqual(line, "      Reach: navPath: Ctrl+K command palette")

    def test_testid_and_navpath_combine_with_semicolon(self):
        line = render_reach_line(
            {"testid": "activity-surface-action-ledger", "navPath": "System page tabs"}
        )
        self.assertEqual(
            line,
            "      Reach: testid=`activity-surface-action-ledger`; navPath: System page tabs",
        )

    def test_api_recipe_renders(self):
        line = render_reach_line({"apiRecipe": "GET /api/foo"})
        self.assertEqual(line, "      Reach: apiRecipe: GET /api/foo")

    def test_unknown_true_renders_entry_point_unknown_with_note_not_a_fabricated_path(self):
        line = render_reach_line(
            {"unknown": True, "note": "no discoverable entry point as of round 6"}
        )
        self.assertEqual(
            line, "      Reach: ENTRY POINT UNKNOWN - no discoverable entry point as of round 6"
        )
        self.assertNotIn("testid", line)
        self.assertNotIn("navPath", line)

    def test_unknown_true_ignores_any_stray_testid(self):
        # unknown:true always wins -- never render a fabricated path alongside it.
        line = render_reach_line({"unknown": True, "note": "n/a", "testid": "should-not-appear"})
        self.assertNotIn("should-not-appear", line)


class ReachLineWiredIntoItemRenderersTests(unittest.TestCase):
    """The three per-kind renderers thread `item['reach']` through
    render_reach_line() -- present renders a Reach line, absent renders none
    (the pre-750 shape, must not crash or fabricate anything)."""

    def test_cohort_item_with_reach_renders_reach_line(self):
        item = {
            "kind": "cohort",
            "id": "mcp",
            "validateHow": "x",
            "routes": ["POST /mcp"],
            "reach": {"navPath": "settings deep-link"},
        }
        text = "\n".join(render_cohort_item(item))
        self.assertIn("Reach: navPath: settings deep-link", text)

    def test_cohort_item_without_reach_renders_no_reach_line(self):
        item = {"kind": "cohort", "id": "agent", "validateHow": "x", "routes": ["GET /a"]}
        text = "\n".join(render_cohort_item(item))
        self.assertNotIn("Reach:", text)

    def test_surface_item_with_reach_renders_reach_line(self):
        item = {
            "kind": "surface",
            "id": "core.brain-surface",
            "validateHow": "x",
            "evidenceToken": "brain",
            "reach": {"testid": "brain-simple-panel"},
        }
        text = "\n".join(render_surface_item(item))
        self.assertIn("Reach: testid=`brain-simple-panel`", text)

    def test_surface_item_without_reach_renders_no_reach_line(self):
        item = {"kind": "surface", "id": "core.memory-surface", "validateHow": "x", "evidenceToken": "memory"}
        text = "\n".join(render_surface_item(item))
        self.assertNotIn("Reach:", text)

    def test_shape_item_with_unknown_reach_renders_entry_point_unknown(self):
        item = {
            "kind": "shape",
            "id": "core.free-chat",
            "validateHow": "x",
            "reach": {"unknown": True, "note": "no discoverable entry point"},
        }
        text = "\n".join(render_shape_item(item))
        self.assertIn("Reach: ENTRY POINT UNKNOWN - no discoverable entry point", text)

    def test_shape_item_without_reach_renders_no_reach_line(self):
        item = {"kind": "shape", "id": "core.rag-ask", "validateHow": "x"}
        text = "\n".join(render_shape_item(item))
        self.assertNotIn("Reach:", text)


class MustWatchSectionTests(unittest.TestCase):
    """Part D (tempdoc 750): coverage-manifest.json now carries the
    register's mustWatch array (gen_coverage_brief.py's build_manifest), so
    derive_round_plan.py derives a real checklist section from it instead of
    only pointing at coverage-brief.md."""

    def test_must_watch_items_returns_empty_list_when_key_absent(self):
        self.assertEqual(must_watch_items({}), [])

    def test_must_watch_items_returns_empty_list_when_key_null(self):
        self.assertEqual(must_watch_items({"mustWatch": None}), [])

    def test_must_watch_items_returns_the_list_when_present(self):
        items = [{"id": "x", "reason": "y", "observability": "sandbox"}]
        self.assertEqual(must_watch_items({"mustWatch": items}), items)

    def test_key_absent_falls_back_to_legacy_reminder_text(self):
        # Backward compat: an OLDER manifest (pre-750, no 'mustWatch' key at
        # all) must still derive a plan, and must say explicitly that
        # must-watch items are not derivable from it.
        manifest = _manifest()
        lines = render_must_watch_section(manifest)
        text = "\n".join(lines)
        self.assertIn("## Reminder: must-watch items are NOT on this checklist", text)
        self.assertIn("predates tempdoc 750 Part D", text)
        self.assertIn("cannot be mechanically derived here", text)
        self.assertIn("coverage-brief.md", text)

    def test_key_present_but_empty_renders_derived_none_section_not_the_legacy_text(self):
        manifest = _manifest(must_watch=[])
        lines = render_must_watch_section(manifest)
        text = "\n".join(lines)
        self.assertIn("## Must-watch items (re-injected every round)", text)
        self.assertIn("(none)", text)
        # The legacy "cannot be mechanically derived" framing must NOT leak in
        # once the manifest actually carries the key -- that would contradict
        # the (none) line right above it.
        self.assertNotIn("cannot be mechanically derived here", text)

    def test_key_present_with_items_renders_checklist_with_id_reason_observability_note(self):
        manifest = _manifest(
            must_watch=[
                {
                    "id": "install-trust-prompts",
                    "reason": "SmartScreen cannot be reproduced in CI",
                    "origin": "recurring across rounds",
                    "observability": "blocked-by-posture",
                    "note": "not observable under the current sandbox posture",
                },
                {
                    "id": "ui-api-truthfulness-under-load",
                    "reason": "Reconnecting while healthy is a timing finding",
                    "observability": "sandbox",
                },
            ]
        )
        lines = render_must_watch_section(manifest)
        text = "\n".join(lines)
        self.assertIn("- [ ] mustWatch:install-trust-prompts", text)
        self.assertIn("reason: SmartScreen cannot be reproduced in CI", text)
        self.assertIn("observability: blocked-by-posture", text)
        self.assertIn("note: not observable under the current sandbox posture", text)
        self.assertIn("- [ ] mustWatch:ui-api-truthfulness-under-load", text)
        self.assertIn("observability: sandbox", text)

    def test_item_without_note_renders_no_note_line(self):
        manifest = _manifest(must_watch=[{"id": "x", "reason": "y", "observability": "sandbox"}])
        text = "\n".join(render_must_watch_section(manifest))
        self.assertNotIn("note:", text)


class MustWatchAndReachIntegrationInRenderPlanTests(unittest.TestCase):
    """End-to-end through render_plan(): both Part D features actually reach
    the generated round-plan text, not just their own unit-level renderers."""

    def test_render_plan_includes_derived_must_watch_checklist_when_key_present(self):
        manifest = _manifest(
            must_touch=[{"kind": "cohort", "id": "agent", "tier": "sandbox", "routes": ["GET /a"]}],
            must_watch=[{"id": "warm-reinstall-over-existing-data", "reason": "regression home", "observability": "sandbox"}],
        )
        text = render_plan(manifest, "coverage-manifest.json")
        self.assertIn("## Must-watch items (re-injected every round)", text)
        self.assertIn("- [ ] mustWatch:warm-reinstall-over-existing-data", text)
        # And the old "not on this checklist" framing must not linger once
        # mustWatch really is derived onto a section of this checklist.
        self.assertNotIn("## Reminder: must-watch items are NOT on this checklist", text)

    def test_render_plan_includes_reach_line_for_a_mustTouch_item(self):
        manifest = _manifest(
            must_touch=[
                {
                    "kind": "shape",
                    "id": "core.agent-run",
                    "tier": "sandbox",
                    "validateHow": "x",
                    "reach": {"testid": "escalation-delegate"},
                }
            ]
        )
        text = render_plan(manifest, "coverage-manifest.json")
        self.assertIn("Reach: testid=`escalation-delegate`", text)

    def test_backward_compat_old_manifest_with_no_reach_or_mustwatch_keys_still_derives_a_plan(self):
        # The exact backward-compat case tempdoc 750 Part D requires: a
        # manifest written by a pre-750 gen_coverage_brief.py (no 'reach' on
        # any mustTouch item, no top-level 'mustWatch' key at all) must still
        # produce a complete, non-crashing round plan.
        manifest = _manifest(
            must_touch=[
                {"kind": "cohort", "id": "agent", "tier": "sandbox", "routes": ["GET /a"]},
                {"kind": "surface", "id": "core.memory-surface", "tier": "sandbox", "evidenceToken": "memory"},
                {"kind": "shape", "id": "core.rag-ask", "tier": "sandbox"},
            ]
        )
        text = render_plan(manifest, "coverage-manifest.json")
        self.assertIn("cohort:agent", text)
        self.assertIn("surface:core.memory-surface", text)
        self.assertIn("shape:core.rag-ask", text)
        self.assertNotIn("Reach:", text)
        self.assertIn("## Reminder: must-watch items are NOT on this checklist", text)


class RenderPlanStructureTests(unittest.TestCase):
    def test_counts_and_sections_by_kind(self):
        manifest = _manifest(must_touch=[
            {"kind": "cohort", "id": "agent", "tier": "sandbox", "routes": ["GET /a"]},
            {"kind": "cohort", "id": "conversation", "tier": "sandbox", "routes": ["GET /b"]},
            {"kind": "surface", "id": "core.memory-surface", "tier": "sandbox", "evidenceToken": "memory"},
            {"kind": "shape", "id": "core.rag-ask", "tier": "sandbox"},
        ])
        text = render_plan(manifest, "coverage-manifest.json")
        self.assertIn("4 total (2 cohort, 1 surface, 1 shape)", text)
        self.assertIn("- [ ] cohort:agent", text)
        self.assertIn("- [ ] cohort:conversation", text)
        self.assertIn("- [ ] surface:core.memory-surface", text)
        self.assertIn("- [ ] shape:core.rag-ask", text)

    def test_non_sandbox_tier_items_excluded_from_plan(self):
        manifest = _manifest(must_touch=[
            {"kind": "cohort", "id": "sandbox-thing", "tier": "sandbox", "routes": ["GET /a"]},
            {"kind": "cohort", "id": "host-thing", "tier": "host", "routes": ["GET /b"]},
        ])
        text = render_plan(manifest, "coverage-manifest.json")
        self.assertIn("cohort:sandbox-thing", text)
        self.assertNotIn("host-thing", text)

    def test_empty_sections_say_none(self):
        text = render_plan(_manifest(must_touch=[]), "coverage-manifest.json")
        self.assertIn("(none)", text)

    def test_items_within_a_kind_are_sorted_by_id(self):
        manifest = _manifest(must_touch=[
            {"kind": "cohort", "id": "zzz-last", "tier": "sandbox", "routes": ["GET /z"]},
            {"kind": "cohort", "id": "aaa-first", "tier": "sandbox", "routes": ["GET /a"]},
        ])
        text = render_plan(manifest, "coverage-manifest.json")
        self.assertLess(text.index("cohort:aaa-first"), text.index("cohort:zzz-last"))

    def test_covered_elsewhere_and_exempt_listed_as_informational(self):
        manifest = _manifest(
            covered_elsewhere=[{"kind": "surface", "id": "core.api-explorer-surface"}],
            exempt=[{"kind": "surface", "id": "core.search-v3-surface"}],
        )
        text = render_plan(manifest, "coverage-manifest.json")
        self.assertIn("coveredElsewhere (1): surface:core.api-explorer-surface", text)
        self.assertIn("exempt (1): surface:core.search-v3-surface", text)

    def test_generated_header_names_the_source_manifest_and_forbids_hand_edit(self):
        text = render_plan(_manifest(), "my-manifest.json")
        self.assertIn("GENERATED by scripts/sandbox/derive_round_plan.py", text)
        self.assertIn("my-manifest.json", text)
        self.assertIn("Do not hand-edit", text)


class LoadManifestTests(unittest.TestCase):
    def test_loads_valid_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "coverage-manifest.json"
            p.write_text(json.dumps(_manifest()), encoding="utf-8")
            loaded = load_manifest(str(p))
            self.assertEqual(loaded["version"], 1)


class MainWiringTests(unittest.TestCase):
    """End-to-end main() wiring -- mirrors test_check_coverage.py's
    MainWiringTests pattern: exercise the CLI entry point, not just the
    rendering helpers underneath it."""

    def test_missing_manifest_file_returns_2(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = main(["--manifest", "/does/not/exist/coverage-manifest.json"])
        self.assertEqual(rc, 2)

    def test_invalid_json_returns_2(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "coverage-manifest.json"
            p.write_text("{not valid json", encoding="utf-8")
            rc = main(["--manifest", str(p)])
            self.assertEqual(rc, 2)

    def test_valid_manifest_without_out_prints_plan_to_stdout_and_returns_0(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest_path = Path(tmp) / "coverage-manifest.json"
            manifest_path.write_text(
                json.dumps(_manifest(must_touch=[
                    {"kind": "cohort", "id": "agent", "tier": "sandbox", "routes": ["GET /a"]}
                ])),
                encoding="utf-8",
            )
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = main(["--manifest", str(manifest_path)])
            self.assertEqual(rc, 0)
            self.assertIn("cohort:agent", buf.getvalue())

    def test_valid_manifest_with_out_writes_file_and_returns_0(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest_path = Path(tmp) / "coverage-manifest.json"
            manifest_path.write_text(
                json.dumps(_manifest(must_touch=[
                    {"kind": "cohort", "id": "agent", "tier": "sandbox", "routes": ["GET /a"]}
                ])),
                encoding="utf-8",
            )
            out_path = Path(tmp) / "round-plan.md"
            buf = io.StringIO()
            with redirect_stdout(buf):
                rc = main(["--manifest", str(manifest_path), "--out", str(out_path)])
            self.assertEqual(rc, 0)
            self.assertTrue(out_path.exists())
            self.assertIn("cohort:agent", out_path.read_text(encoding="utf-8"))
            self.assertIn("Wrote", buf.getvalue())


if __name__ == "__main__":
    unittest.main()
