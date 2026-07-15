#!/usr/bin/env python3
"""Self-tests for derive_round_plan.py (tempdoc 729-followup mechanical
brief-to-plan derivation). Shipped with zero tests originally.

Covers, at minimum (per the brief this test file closes):

- The `requiredRoutes` vs `routes` distinction -- the exact case a real round
  got wrong. A cohort can carry BOTH keys (e.g. the real `mcp` cohort in
  coverage-manifest.json: `routes` lists 3 endpoints, `requiredRoutes` lists
  only `POST /mcp`); when `requiredRoutes` is present it means ALL of those
  routes are mandatory, not "any ONE of `routes`". The rendered checklist item
  must say so unambiguously.
- `mustWatch` items are declared-not-covered, not silently dropped: the
  schema has no `mustWatch` key (derive_round_plan.py's own module docstring
  says so), so the script cannot mechanically list them -- but the rendered
  plan must explicitly say they exist and are not on this checklist, rather
  than producing an output that looks complete when it isn't.
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
    render_cohort_item,
    render_plan,
    render_shape_item,
    render_surface_item,
    sandbox_must_touch,
    shape_token,
)


def _manifest(must_touch=None, covered_elsewhere=None, exempt=None) -> dict:
    return {
        "version": 1,
        "mustTouch": must_touch or [],
        "coveredElsewhere": covered_elsewhere or [],
        "exempt": exempt or [],
    }


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
            exempt=[{"kind": "surface", "id": "core.governance-surface"}],
        )
        text = render_plan(manifest, "coverage-manifest.json")
        self.assertIn("coveredElsewhere (1): surface:core.api-explorer-surface", text)
        self.assertIn("exempt (1): surface:core.governance-surface", text)

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
