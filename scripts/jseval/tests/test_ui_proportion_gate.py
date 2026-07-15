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
