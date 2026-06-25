"""Tests for jseval.gate (tempdoc 400 §26.6 D4; Phase 6 / 6.13 relocated)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from jseval import gate as _gate


@pytest.fixture(scope="module")
def gate():
    """Expose the gate module as the fixture the layout tests expect.

    Phase 6 / 6.13: module moved from ``scripts/ci/phase3_observability_gate.py``
    into the jseval package. The fixture is kept so the body of the
    existing layout tests compiles unchanged — only the import source
    changes.
    """
    return _gate


def _build_layout(
    root: Path, *,
    stdev: float,
    cohort: str = "cohort-x",
    include_envelope_embed: bool = True,
    projections: list[str] = None,
    schema_version: int = 1,
) -> Path:
    projections = projections if projections is not None else [
        "contract_violations",
        "rate_timeline",
        "stratified_metrics",
        "bootstrap_ci",
        "rank_diff",
        "cpu_fallback_counts",
        "encoder_drift",
    ]

    # Cohort baseline.
    baseline_dir = root / "cohort_baselines" / cohort
    baseline_dir.mkdir(parents=True, exist_ok=True)
    envelope = {
        "schema_version": schema_version,
        "cohort_hash": cohort,
        "n_runs": 5,
        "calibrated_at": "2026-04-22T03:05:00Z",
        "metrics": {
            "full": {
                "nDCG@10": {"mean": 0.74, "stdev": stdev, "n": 5},
            },
        },
    }
    (baseline_dir / "envelope.json").write_text(
        json.dumps(envelope), encoding="utf-8")

    # Run directory.
    run_dir = root / "eval-results" / "20260422T060000_scifact"
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "summary.json").write_text(json.dumps({"ok": True}),
                                          encoding="utf-8")
    manifest = {
        "manifest_hash": cohort,
        "non_determinism_envelope": envelope if include_envelope_embed else None,
    }
    (run_dir / "manifest.json").write_text(json.dumps(manifest),
                                            encoding="utf-8")

    # Projections.
    proj_dir = run_dir / "projections"
    proj_dir.mkdir(parents=True, exist_ok=True)
    for name in projections:
        (proj_dir / f"{name}.json").write_text(
            json.dumps({"projection_name": name, "schema_version": 1}),
            encoding="utf-8")

    return root


class TestEvaluate:
    def test_passes_on_healthy_layout(self, gate, tmp_path):
        _build_layout(tmp_path, stdev=0.00108)
        report = gate.evaluate(tmp_path, baseline_stdev=0.00108,
                               tolerance_pct=10)
        assert report["exit_code"] == 0
        check_names = {c["name"]: c["status"] for c in report["checks"]}
        assert check_names["envelope-schema"] == "ok"
        assert check_names["ndcg10-stdev-within-tolerance"] == "ok"
        assert check_names["manifest-envelope-embedded"] == "ok"
        assert check_names["required-projections-present"] == "ok"

    def test_fails_when_stdev_out_of_band(self, gate, tmp_path):
        _build_layout(tmp_path, stdev=0.003)  # 3x baseline
        report = gate.evaluate(tmp_path, baseline_stdev=0.00108,
                               tolerance_pct=10)
        assert report["exit_code"] == 1
        band_check = next(c for c in report["checks"]
                          if c["name"] == "ndcg10-stdev-within-tolerance")
        assert band_check["status"] == "fail"

    def test_fails_when_envelope_absent(self, gate, tmp_path):
        # Run layout exists but no cohort_baselines.
        eval_results = tmp_path / "eval-results"
        (eval_results / "20260422T060000_x").mkdir(parents=True)
        (eval_results / "20260422T060000_x" / "summary.json").write_text(
            "{}", encoding="utf-8")
        report = gate.evaluate(tmp_path, baseline_stdev=0.00108,
                               tolerance_pct=10)
        assert report["exit_code"] == 2

    def test_fails_when_manifest_envelope_missing(self, gate, tmp_path):
        _build_layout(tmp_path, stdev=0.00108, include_envelope_embed=False)
        report = gate.evaluate(tmp_path, baseline_stdev=0.00108,
                               tolerance_pct=10)
        assert report["exit_code"] == 1
        embed = next(c for c in report["checks"]
                     if c["name"] == "manifest-envelope-embedded")
        assert embed["status"] == "fail"

    def test_fails_when_required_projection_missing(self, gate, tmp_path):
        _build_layout(tmp_path, stdev=0.00108,
                      projections=["contract_violations", "rate_timeline"])
        report = gate.evaluate(tmp_path, baseline_stdev=0.00108,
                               tolerance_pct=10)
        assert report["exit_code"] == 1
        missing = next(c for c in report["checks"]
                       if c["name"] == "required-projections-present")
        assert missing["status"] == "fail"

    def test_passes_with_extra_projections(self, gate, tmp_path):
        _build_layout(tmp_path, stdev=0.00108,
                      projections=list(gate.REQUIRED_PROJECTIONS) +
                                  ["future_projection_x"])
        report = gate.evaluate(tmp_path, baseline_stdev=0.00108,
                               tolerance_pct=10)
        assert report["exit_code"] == 0

    def test_schema_version_mismatch_fails(self, gate, tmp_path):
        _build_layout(tmp_path, stdev=0.00108, schema_version=2)
        report = gate.evaluate(tmp_path, baseline_stdev=0.00108,
                               tolerance_pct=10)
        schema_check = next(c for c in report["checks"]
                            if c["name"] == "envelope-schema")
        assert schema_check["status"] == "fail"
        assert report["exit_code"] == 1


class TestReportOutput:
    def test_report_is_written(self, gate, tmp_path):
        _build_layout(tmp_path, stdev=0.00108)
        out = tmp_path / "gate-report.json"
        report = gate.evaluate(tmp_path, baseline_stdev=0.00108,
                               tolerance_pct=10)
        out.write_text(json.dumps(report, indent=2), encoding="utf-8")
        reloaded = json.loads(out.read_text(encoding="utf-8"))
        assert reloaded["exit_code"] == 0
        assert reloaded["cohort_hash"] == "cohort-x"
