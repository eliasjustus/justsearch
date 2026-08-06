"""Tests for the chrome proportion shrink-only ratchet gate (tempdoc 697).

Mirrors `test_ui_a11y_gate.py`'s shape: the gate's failure mode is a SILENT WRONG
VERDICT (green when a persistent-chrome element actually GREW, or red on a harmless
shrink), so its evaluate() is pinned here without needing a browser (capture_fn is
injected).
"""
from __future__ import annotations

import json

from jseval import ui_proportion_gate


def _measure_file(tmp_path, elements: dict[str, int]):
    """Write a minimal `<step>.measure.json` with the given {selector: heightPx}
    geometry; return its path."""
    p = tmp_path / "step.measure.json"
    geometry = {"elements": {sel: {"rect": {"h": h}} for sel, h in elements.items()}}
    p.write_text(json.dumps({"geometry": geometry}), encoding="utf-8")
    return str(p)


def _cap(measure_path, ok=True):
    return {"ok": ok, "measure": {"measure_path": measure_path}}


def _register(monkeypatch, elements, tolerance_px=2):
    monkeypatch.setattr(
        ui_proportion_gate, "load_register_steps",
        lambda: [{"uiShotStep": "home", "tolerancePx": tolerance_px, "elements": elements}],
    )


class TestGateEvaluate:
    def test_clean_when_exactly_at_baseline(self, monkeypatch, tmp_path):
        _register(monkeypatch, [{"selector": "jf-rail", "maxHeightPx": 100}])
        mf = _measure_file(tmp_path, {"jf-rail": 100})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0
        assert report["rows"][0]["status"] == "ok"

    def test_clean_within_tolerance(self, monkeypatch, tmp_path):
        _register(monkeypatch, [{"selector": "jf-rail", "maxHeightPx": 100}], tolerance_px=2)
        mf = _measure_file(tmp_path, {"jf-rail": 102})  # exactly at the +tolerance edge
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0
        assert report["rows"][0]["status"] == "ok"

    def test_clean_when_shrunk(self, monkeypatch, tmp_path):
        # Shrinks are always allowed, however large.
        _register(monkeypatch, [{"selector": "jf-rail", "maxHeightPx": 100}])
        mf = _measure_file(tmp_path, {"jf-rail": 40})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0
        assert report["rows"][0]["status"] == "ok"

    def test_fails_when_grown_past_tolerance(self, monkeypatch, tmp_path):
        _register(monkeypatch, [{"selector": "jf-rail", "maxHeightPx": 100}], tolerance_px=2)
        mf = _measure_file(tmp_path, {"jf-rail": 103})  # 1px past the +tolerance edge
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        assert report["rows"][0]["status"] == "GROWN"
        assert report["rows"][0]["measuredHeight"] == 103
        assert report["rows"][0]["maxHeightPx"] == 100

    def test_multiple_elements_one_grown_still_flags(self, monkeypatch, tmp_path):
        _register(monkeypatch, [
            {"selector": "jf-rail", "maxHeightPx": 100},
            {"selector": "jf-stage", "maxHeightPx": 200},
        ])
        mf = _measure_file(tmp_path, {"jf-rail": 90, "jf-stage": 250})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        statuses = {row["selector"]: row["status"] for row in report["rows"]}
        assert statuses["jf-rail"] == "ok"
        assert statuses["jf-stage"] == "GROWN"

    def test_capture_error_is_exit_2(self, monkeypatch):
        _register(monkeypatch, [{"selector": "jf-rail", "maxHeightPx": 100}])
        report = ui_proportion_gate.evaluate(lambda step: {"ok": False, "error": "boom"})
        assert report["exit_code"] == 2
        assert report["rows"][0]["status"] == "ERROR"

    def test_missing_measure_is_exit_2(self, monkeypatch):
        # No measure_path on a registered step = measurement was off -> error, not a
        # false pass (mirrors ui_a11y_gate's same guarantee).
        _register(monkeypatch, [{"selector": "jf-rail", "maxHeightPx": 100}])
        report = ui_proportion_gate.evaluate(lambda step: {"ok": True, "measure": {}})
        assert report["exit_code"] == 2

    def test_selector_missing_from_geometry_is_exit_2(self, monkeypatch, tmp_path):
        # A registered selector that vanished from the captured geometry (e.g. a
        # markup rename) must not silently pass — it's an error, not a shrink to 0.
        _register(monkeypatch, [{"selector": "jf-rail", "maxHeightPx": 100}])
        mf = _measure_file(tmp_path, {})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 2
        assert report["rows"][0]["status"] == "ERROR"

    def test_empty_register_is_clean_and_does_not_capture(self, monkeypatch):
        # A placeholder baseline (no registered steps yet) must be clean without
        # invoking capture_fn at all.
        monkeypatch.setattr(ui_proportion_gate, "load_register_steps", lambda: [])

        def _boom(step):
            raise AssertionError("capture_fn should not be called with an empty register")

        report = ui_proportion_gate.evaluate(_boom)
        assert report["exit_code"] == 0
        assert report["rows"] == []

    def test_element_with_no_constraint_is_exit_2(self, monkeypatch, tmp_path):
        # A registered element that declares NO constraint asserts nothing; reporting it clean
        # would be a dangling guard (green while checking nothing), so it is an error.
        _register(monkeypatch, [{"selector": "jf-rail"}])
        mf = _measure_file(tmp_path, {"jf-rail": 100})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 2
        assert report["rows"][0]["status"] == "ERROR"

    def test_step_with_no_elements_is_skipped(self, monkeypatch):
        monkeypatch.setattr(
            ui_proportion_gate, "load_register_steps",
            lambda: [{"uiShotStep": "home", "tolerancePx": 2, "elements": []}],
        )

        def _boom(step):
            raise AssertionError("capture_fn should not be called for a step with no elements")

        report = ui_proportion_gate.evaluate(_boom)
        assert report["exit_code"] == 0
        assert report["rows"] == []


def _rect_measure_file(tmp_path, rects: dict[str, dict[str, int]]):
    """Write a `<step>.measure.json` with full x/y/w/h geometry; return its path."""
    p = tmp_path / "rects.measure.json"
    p.write_text(
        json.dumps({"geometry": {"elements": {s: {"rect": r} for s, r in rects.items()}}}),
        encoding="utf-8",
    )
    return str(p)


class TestMinWidthFloor:
    """Sandbox round 7 defect (1): the RAG reading column was starved to ~102px by the
    document pane beside it. A height ceiling cannot express "must not get SMALLER", so
    the floor is its own constraint kind."""

    def test_clean_when_above_floor(self, monkeypatch, tmp_path):
        _register(monkeypatch, [{"selector": ".conversation", "minWidthPx": 384}])
        mf = _rect_measure_file(tmp_path, {".conversation": {"x": 0, "y": 0, "w": 410, "h": 500}})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0
        assert report["rows"][0]["status"] == "ok"

    def test_clean_exactly_at_floor(self, monkeypatch, tmp_path):
        _register(monkeypatch, [{"selector": ".conversation", "minWidthPx": 384}])
        mf = _rect_measure_file(tmp_path, {".conversation": {"x": 0, "y": 0, "w": 384, "h": 500}})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0

    def test_clean_within_tolerance_below_floor(self, monkeypatch, tmp_path):
        _register(monkeypatch, [{"selector": ".conversation", "minWidthPx": 384}], tolerance_px=2)
        mf = _rect_measure_file(tmp_path, {".conversation": {"x": 0, "y": 0, "w": 382, "h": 500}})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0

    def test_fails_when_starved_past_tolerance(self, monkeypatch, tmp_path):
        # The measured pre-fix value: `minmax(0, 50rem)` sized the track to 102px at 1050x800.
        _register(monkeypatch, [{"selector": ".conversation", "minWidthPx": 384}], tolerance_px=2)
        mf = _rect_measure_file(tmp_path, {".conversation": {"x": 0, "y": 0, "w": 102, "h": 500}})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        assert report["rows"][0]["status"] == "STARVED"
        assert report["rows"][0]["measuredWidth"] == 102
        assert report["rows"][0]["minWidthPx"] == 384

    def test_growing_wider_is_never_a_violation(self, monkeypatch, tmp_path):
        _register(monkeypatch, [{"selector": ".conversation", "minWidthPx": 384}])
        mf = _rect_measure_file(tmp_path, {".conversation": {"x": 0, "y": 0, "w": 800, "h": 500}})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0


class TestMustNotOverlap:
    """Sandbox round 7 defect (2): the toast column docked at 48px, inside the chat
    surface's 56-88px header band. Both elements were individually within every size
    budget — the defect was the RELATION, so it needs its own constraint kind."""

    def test_flags_a_real_overlap(self, monkeypatch, tmp_path):
        # The measured pre-fix rects: 32px of vertical intersection across 280px of width.
        _register(monkeypatch, [{"selector": ".toast", "mustNotOverlapSelector": ".header"}])
        mf = _rect_measure_file(tmp_path, {
            ".toast": {"x": 754, "y": 56, "w": 288, "h": 50},
            ".header": {"x": 68, "y": 56, "w": 966, "h": 32},
        })
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        assert report["rows"][0]["status"] == "OVERLAPS"

    def test_clean_when_the_toast_column_clears_the_header_band(self, monkeypatch, tmp_path):
        # The measured post-fix rects: the column docks at 135px, 47px below the header band.
        _register(monkeypatch, [{"selector": ".toast", "mustNotOverlapSelector": ".header"}])
        mf = _rect_measure_file(tmp_path, {
            ".toast": {"x": 754, "y": 135, "w": 288, "h": 50},
            ".header": {"x": 68, "y": 56, "w": 966, "h": 32},
        })
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0
        assert report["rows"][0]["status"] == "ok"

    def test_clean_when_separated_horizontally_despite_sharing_rows(self, monkeypatch, tmp_path):
        # Same vertical band, disjoint columns — not an occlusion.
        _register(monkeypatch, [{"selector": ".toast", "mustNotOverlapSelector": ".header"}])
        mf = _rect_measure_file(tmp_path, {
            ".toast": {"x": 754, "y": 56, "w": 288, "h": 50},
            ".header": {"x": 68, "y": 56, "w": 200, "h": 32},
        })
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0

    def test_a_touching_edge_is_not_an_overlap(self, monkeypatch, tmp_path):
        # Header ends at y=88; the column starts at y=88. Flush, not occluding.
        _register(monkeypatch, [{"selector": ".toast", "mustNotOverlapSelector": ".header"}])
        mf = _rect_measure_file(tmp_path, {
            ".toast": {"x": 754, "y": 88, "w": 288, "h": 50},
            ".header": {"x": 68, "y": 56, "w": 966, "h": 32},
        })
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0

    def test_sub_tolerance_intersection_is_not_an_overlap(self, monkeypatch, tmp_path):
        _register(monkeypatch, [{"selector": ".toast", "mustNotOverlapSelector": ".header"}],
                  tolerance_px=2)
        mf = _rect_measure_file(tmp_path, {
            ".toast": {"x": 754, "y": 86, "w": 288, "h": 50},  # 2px into the header band
            ".header": {"x": 68, "y": 56, "w": 966, "h": 32},
        })
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0

    def test_missing_counterpart_is_exit_2(self, monkeypatch, tmp_path):
        # A renamed/absent counterpart must not read as "nothing to overlap, therefore clean".
        _register(monkeypatch, [{"selector": ".toast", "mustNotOverlapSelector": ".header"}])
        mf = _rect_measure_file(tmp_path, {".toast": {"x": 754, "y": 56, "w": 288, "h": 50}})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 2
        assert report["rows"][0]["status"] == "ERROR"


class TestScrollableRegionBounds:
    """The one-scroller rule (D3) is a BAND, not a ceiling. The closure audit found the
    ceiling alone vacuous: every captured state had `scrollableCount` 0, so `<= 1` never
    witnessed a scroller and would have stayed green through a regression that removed the
    scroller (or a setup that stopped reaching the overflowing state)."""

    def _scroll_measure_file(self, tmp_path, count: int, regions=None):
        p = tmp_path / "step.measure.json"
        p.write_text(json.dumps({"geometry": {
            "elements": {},
            "scrollableCount": count,
            "scrollableRegions": regions if regions is not None else [
                {"selector": f"div.r{i}", "scrollDelta": 100} for i in range(count)
            ],
        }}), encoding="utf-8")
        return str(p)

    def _step(self, monkeypatch, **step_keys):
        monkeypatch.setattr(
            ui_proportion_gate, "load_register_steps",
            lambda: [{"uiShotStep": "home", "tolerancePx": 2, "elements": [], **step_keys}],
        )

    def test_exactly_one_scroller_satisfies_the_band(self, monkeypatch, tmp_path):
        self._step(monkeypatch, minScrollableRegions=1, maxScrollableRegions=1)
        mf = self._scroll_measure_file(tmp_path, 1)
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0
        statuses = {row["constraint"]: row["status"] for row in report["rows"]}
        assert statuses == {"minScrollableRegions": "ok", "maxScrollableRegions": "ok"}

    def test_zero_scrollers_fails_the_floor(self, monkeypatch, tmp_path):
        # The vacuity case: the ceiling reports ok, the floor is what catches it.
        self._step(monkeypatch, minScrollableRegions=1, maxScrollableRegions=1)
        mf = self._scroll_measure_file(tmp_path, 0)
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        statuses = {row["constraint"]: row["status"] for row in report["rows"]}
        assert statuses["maxScrollableRegions"] == "ok"
        assert statuses["minScrollableRegions"] == "NO_SCROLLER"

    def test_two_scrollers_fails_the_ceiling(self, monkeypatch, tmp_path):
        self._step(monkeypatch, minScrollableRegions=1, maxScrollableRegions=1)
        mf = self._scroll_measure_file(tmp_path, 2)
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        statuses = {row["constraint"]: row["status"] for row in report["rows"]}
        assert statuses["minScrollableRegions"] == "ok"
        assert statuses["maxScrollableRegions"] == "MULTI_SCROLL"

    def test_floor_alone_runs_on_a_step_with_no_elements(self, monkeypatch, tmp_path):
        # A step declaring only the floor must still be captured — the elements-empty
        # skip must not swallow it (the same escape-hatch bug `absentSelectors` avoided).
        self._step(monkeypatch, minScrollableRegions=1)
        mf = self._scroll_measure_file(tmp_path, 0)
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        assert report["rows"][0]["status"] == "NO_SCROLLER"

    def test_missing_scrollable_count_is_exit_2(self, monkeypatch, tmp_path):
        # A stale geometry probe must not read as "no scrollers, therefore clean".
        self._step(monkeypatch, minScrollableRegions=1)
        p = tmp_path / "step.measure.json"
        p.write_text(json.dumps({"geometry": {"elements": {}}}), encoding="utf-8")
        report = ui_proportion_gate.evaluate(lambda step: _cap(str(p)))
        assert report["exit_code"] == 2
        assert report["rows"][0]["status"] == "ERROR"


class TestRegisterAndCaptureAgreeOnSelectors:
    """The capture unions the register's selectors into its geometry probe. An overlap
    counterpart is only ever NAMED (never its own row), so it must be collected too —
    otherwise every overlap constraint reports a capture error instead of a verdict."""

    def test_counterpart_selectors_are_collected_for_capture(self):
        from jseval import ui_measure

        sels = ui_measure._find_proportion_baseline().get("chat-occlusion") or []
        assert ".toast" in sels
        assert ".header" in sels, "the overlap counterpart must be captured, not just named"
        assert ".conversation" in sels

    def test_registered_selectors_have_no_duplicates(self):
        from jseval import ui_measure

        for step, sels in ui_measure._find_proportion_baseline().items():
            assert len(sels) == len(set(sels)), f"{step} registers a duplicate selector"
