"""Tests for the ASSERT gate + the shared NEW-vs-known split (tempdoc 615 §11 ASSERT).

The gate's failure mode is a SILENT WRONG VERDICT (green when a NEW violation slipped,
or red on accepted debt), so its evaluate() + the shared split are pinned here without
needing a browser (capture_fn is injected).
"""
from __future__ import annotations

from jseval import ui_a11y_gate
from jseval.ui_measure import split_new_vs_known


class TestSplitNewVsKnown:
    def test_no_baseline_returns_none(self):
        assert split_new_vs_known([{"id": "x"}], None) == (None, None)

    def test_all_known(self):
        new, known = split_new_vs_known([{"id": "color-contrast"}], ["color-contrast"])
        assert new == [] and known == 1

    def test_new_violation_surfaces(self):
        new, known = split_new_vs_known(
            [{"id": "color-contrast"}, {"id": "aria-valid-attr-value"}], ["color-contrast"])
        assert new == ["aria-valid-attr-value"] and known == 1

    def test_empty_violations(self):
        assert split_new_vs_known([], ["color-contrast"]) == ([], 0)


def _measure_file(tmp_path, rule_ids):
    """Write a minimal `<step>.measure.json` with the given axe rule ids; return its path."""
    import json
    p = tmp_path / "step.measure.json"
    p.write_text(json.dumps({"axe": {"violations": [{"id": r} for r in rule_ids]}}), encoding="utf-8")
    return str(p)


def _cap(measure_path, ok=True):
    return {"ok": ok, "measure": {"measure_path": measure_path}}


class TestGateEvaluate:
    def test_clean_when_no_new(self, monkeypatch, tmp_path):
        monkeypatch.setattr(ui_a11y_gate, "load_register_surfaces",
                            lambda: [{"surface": "search", "uiShotStep": "home", "knownRules": ["color-contrast"]}])
        mf = _measure_file(tmp_path, ["color-contrast"])  # the only violation is accepted
        report = ui_a11y_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 0
        assert report["surfaces"][0]["status"] == "ok"
        assert report["surfaces"][0]["known"] == 1

    def test_fails_on_new(self, monkeypatch, tmp_path):
        monkeypatch.setattr(ui_a11y_gate, "load_register_surfaces",
                            lambda: [{"surface": "search", "uiShotStep": "home", "knownRules": []}])
        mf = _measure_file(tmp_path, ["aria-valid-attr-value"])  # not accepted → NEW
        report = ui_a11y_gate.evaluate(lambda step: _cap(mf))
        assert report["exit_code"] == 1
        assert report["surfaces"][0]["status"] == "NEW"
        assert "aria-valid-attr-value" in report["surfaces"][0]["new"]

    def test_capture_error_is_exit_2(self, monkeypatch):
        monkeypatch.setattr(ui_a11y_gate, "load_register_surfaces",
                            lambda: [{"surface": "search", "uiShotStep": "home", "knownRules": []}])
        report = ui_a11y_gate.evaluate(lambda step: {"ok": False, "error": "boom"})
        assert report["exit_code"] == 2

    def test_missing_measure_is_exit_2(self, monkeypatch):
        # No measure_path on a registered surface = measurement was off → error, not a false pass.
        monkeypatch.setattr(ui_a11y_gate, "load_register_surfaces",
                            lambda: [{"surface": "search", "uiShotStep": "home", "knownRules": []}])
        report = ui_a11y_gate.evaluate(lambda step: {"ok": True, "measure": {}})
        assert report["exit_code"] == 2
