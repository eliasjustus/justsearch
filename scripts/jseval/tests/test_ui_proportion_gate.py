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


class TestMinHeightFloor:
    """The 814 review pass's ceiling companion. `chat-bands-detailed` registers the
    EXPANDED degradation banner at maxHeightPx 176 — but the collapsed pill is 34px, so the
    ceiling alone reports a comfortable "ok" precisely when Detailed expansion has regressed
    to the pill and the row is measuring the wrong element. The floor is what notices."""

    def test_clean_when_expanded(self, monkeypatch, tmp_path):
        _register(monkeypatch, [{"selector": ".degradation-banner",
                                 "maxHeightPx": 176, "minHeightPx": 64}])
        mf = _measure_file(tmp_path, {".degradation-banner": 110})  # the measured expanded height
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0
        statuses = {row["constraint"]: row["status"] for row in report["rows"]}
        assert statuses == {"maxHeightPx": "ok", "minHeightPx": "ok"}

    def test_collapsed_to_the_pill_fails_the_floor_while_the_ceiling_reads_ok(
        self, monkeypatch, tmp_path,
    ):
        # The vacuity this constraint exists for, stated as a test: 34px is the collapsed
        # pill's measured height (`chat-bands`' `.degradation-banner-collapsed` row, 42px
        # ceiling). The ceiling passes; only the floor is red.
        _register(monkeypatch, [{"selector": ".degradation-banner",
                                 "maxHeightPx": 176, "minHeightPx": 64}])
        mf = _measure_file(tmp_path, {".degradation-banner": 34})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        statuses = {row["constraint"]: row["status"] for row in report["rows"]}
        assert statuses["maxHeightPx"] == "ok"
        assert statuses["minHeightPx"] == "UNDER_MIN_HEIGHT"

    def test_clean_within_tolerance_below_floor(self, monkeypatch, tmp_path):
        _register(monkeypatch, [{"selector": ".degradation-banner", "minHeightPx": 64}],
                  tolerance_px=2)
        mf = _measure_file(tmp_path, {".degradation-banner": 62})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0

    def test_growing_taller_is_not_a_floor_violation(self, monkeypatch, tmp_path):
        # The floor is one-directional; growth is the CEILING's business.
        _register(monkeypatch, [{"selector": ".degradation-banner", "minHeightPx": 64}])
        mf = _measure_file(tmp_path, {".degradation-banner": 400})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0

    def test_min_height_alone_is_a_valid_constraint(self, monkeypatch, tmp_path):
        # An element declaring ONLY the floor must not report "declares no constraint".
        _register(monkeypatch, [{"selector": ".degradation-banner", "minHeightPx": 64}])
        mf = _measure_file(tmp_path, {".degradation-banner": 20})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        assert report["rows"][0]["status"] == "UNDER_MIN_HEIGHT"

    def test_missing_height_is_exit_2(self, monkeypatch, tmp_path):
        _register(monkeypatch, [{"selector": ".degradation-banner", "minHeightPx": 64}])
        mf = _rect_measure_file(tmp_path, {".degradation-banner": {"x": 0, "y": 0, "w": 100}})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 2
        assert report["rows"][0]["status"] == "ERROR"


class TestRequiredSelectors:
    """`absentSelectors`' positive twin (814 §D7.2's spine PAIR). `chat-spine-single`
    asserts the spine is absent on a one-turn conversation; the multi-turn sibling has to
    assert it is PRESENT, and a step-level presence assertion cannot be an element row (an
    element row with no size constraint is an ERROR by design)."""

    def _step(self, monkeypatch, **step_keys):
        monkeypatch.setattr(
            ui_proportion_gate, "load_register_steps",
            lambda: [{"uiShotStep": "home", "tolerancePx": 2, "elements": [], **step_keys}],
        )

    def test_present_is_clean(self, monkeypatch, tmp_path):
        self._step(monkeypatch, requiredSelectors=[".run-spine"])
        mf = _measure_file(tmp_path, {".run-spine": 300})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0
        assert report["rows"][0]["constraint"] == "requiredSelectors"
        assert report["rows"][0]["status"] == "ok"

    def test_absent_is_a_violation(self, monkeypatch, tmp_path):
        self._step(monkeypatch, requiredSelectors=[".run-spine"])
        mf = _measure_file(tmp_path, {".conversation": 300})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        assert report["rows"][0]["status"] == "MISSING_REQUIRED"

    def test_required_alone_runs_on_a_step_with_no_elements(self, monkeypatch, tmp_path):
        # Same escape-hatch bug `absentSelectors` / `minScrollableRegions` had to avoid.
        self._step(monkeypatch, requiredSelectors=[".run-spine"])
        mf = _measure_file(tmp_path, {})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        assert report["rows"][0]["status"] == "MISSING_REQUIRED"


class TestForbiddenVisibleText:
    """The 814 §D5 chip-yield witness. The duplication D5 arbitrates is CROSS-WORDED — the
    chat banner says "Semantic search degraded." and the status chip says "Service
    degraded" — so the register-wide phrase counter measures 1 whether the yield works or
    not. What a capture CAN decide is that the banner-owning surface never shows the
    chip's wording."""

    def _facts_measure_file(self, tmp_path, facts: dict[str, int]):
        p = tmp_path / "facts.measure.json"
        p.write_text(json.dumps({
            "geometry": {"elements": {}},
            "statusFacts": [{"phrase": k, "count": v} for k, v in facts.items()],
        }), encoding="utf-8")
        return str(p)

    def _step(self, monkeypatch, **step_keys):
        monkeypatch.setattr(
            ui_proportion_gate, "load_register_steps",
            lambda: [{"uiShotStep": "home", "tolerancePx": 2, "elements": [], **step_keys}],
        )

    def test_absent_wording_is_clean(self, monkeypatch, tmp_path):
        self._step(monkeypatch, forbiddenVisibleText=["Service degraded"])
        mf = self._facts_measure_file(tmp_path, {"Service degraded": 0})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0
        assert report["rows"][0]["status"] == "ok"

    def test_one_visible_render_is_a_violation(self, monkeypatch, tmp_path):
        # The yield regressing = the chip renders the verdict headline again = count 1.
        self._step(monkeypatch, forbiddenVisibleText=["Service degraded"])
        mf = self._facts_measure_file(tmp_path, {"Service degraded": 1})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        assert report["rows"][0]["status"] == "FORBIDDEN_TEXT_VISIBLE"
        assert report["rows"][0]["count"] == 1

    def test_phrase_missing_from_the_capture_is_exit_2(self, monkeypatch, tmp_path):
        # A probe that never counted the phrase must not read as "absent, therefore clean".
        self._step(monkeypatch, forbiddenVisibleText=["Service degraded"])
        mf = self._facts_measure_file(tmp_path, {"Over budget": 0})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 2
        assert report["rows"][0]["status"] == "ERROR"

    def test_capture_unions_step_scoped_phrases_into_the_probe(self):
        # The register→probe half of the same contract `TestRegisterAndCaptureAgreeOnSelectors`
        # pins for selectors: a step-scoped phrase the capture never counts is exit 2 above.
        from jseval import ui_measure

        registered = ui_measure._find_proportion_forbidden_text()
        for step, phrases in registered.items():
            assert isinstance(phrases, list)
            for p in phrases:
                assert p, f"{step} registers an empty forbiddenVisibleText phrase"


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


class TestNonScrollableSelectors:
    """Tempdoc 814 §D8 — §D3's one-scroller rule addressed at a NAMED element. The
    surface-wide `maxScrollableRegions` counts scrollers but cannot say WHICH element may
    be one, so a regression that moved the scroller from `.conversation` to the evidence
    rail still counts 1 and passes. These rows judge the registered element's own captured
    `scrollable` flag (`ui_measure.py` computes it with the SAME overflow+delta predicate
    that builds `scrollableRegions`)."""

    def _step(self, monkeypatch, **step_keys):
        monkeypatch.setattr(
            ui_proportion_gate, "load_register_steps",
            lambda: [{"uiShotStep": "home", "tolerancePx": 2, "elements": [], **step_keys}],
        )

    def _scroll_measure_file(self, tmp_path, elements: dict):
        p = tmp_path / "scroll.measure.json"
        p.write_text(json.dumps({"geometry": {"elements": elements}}), encoding="utf-8")
        return str(p)

    def test_non_scrolling_element_is_clean(self, monkeypatch, tmp_path):
        self._step(monkeypatch, nonScrollableSelectors=[".evidence-rail"])
        mf = self._scroll_measure_file(
            tmp_path, {".evidence-rail": {"rect": {"h": 300}, "scrollable": False, "scrollDelta": 0}}
        )
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0
        assert report["rows"][0]["constraint"] == "nonScrollableSelectors"
        assert report["rows"][0]["status"] == "ok"

    def test_a_scrolling_element_is_a_violation(self, monkeypatch, tmp_path):
        self._step(monkeypatch, nonScrollableSelectors=[".evidence-rail"])
        mf = self._scroll_measure_file(
            tmp_path, {".evidence-rail": {"rect": {"h": 300}, "scrollable": True, "scrollDelta": 210}}
        )
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        assert report["rows"][0]["status"] == "IS_SCROLLER"
        assert report["rows"][0]["scrollDelta"] == 210

    def test_a_missing_element_is_a_violation_not_a_pass(self, monkeypatch, tmp_path):
        # The anti-vacuity half: "the rail does not scroll" must not be satisfiable by a
        # rail that never mounted.
        self._step(monkeypatch, nonScrollableSelectors=[".evidence-rail"])
        mf = self._scroll_measure_file(tmp_path, {})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        assert report["rows"][0]["status"] == "IS_SCROLLER"

    def test_a_capture_without_the_flag_is_an_error(self, monkeypatch, tmp_path):
        # A stale ui_measure probe must report a capture ERROR, never a silent pass.
        self._step(monkeypatch, nonScrollableSelectors=[".evidence-rail"])
        mf = self._scroll_measure_file(tmp_path, {".evidence-rail": {"rect": {"h": 300}}})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 2
        assert report["rows"][0]["status"] == "ERROR"

    def test_non_scrollable_alone_runs_on_a_step_with_no_elements(self, monkeypatch, tmp_path):
        self._step(monkeypatch, nonScrollableSelectors=[".evidence-rail"])
        mf = self._scroll_measure_file(tmp_path, {})
        report = ui_proportion_gate.evaluate(lambda step: _cap(mf))
        assert len(report["rows"]) == 1

    def test_registered_non_scrollable_selectors_reach_the_capture_probe(self):
        # Same collection discipline as the overlap counterpart: a selector judged off its
        # own captured element must be unioned into the geometry probe, or every row of
        # this kind reports "not found" instead of a verdict.
        from jseval import ui_measure

        sels = ui_measure._find_proportion_baseline().get("chat-evidence-rail") or []
        assert ".evidence-rail" in sels
