"""Tests for drift-baseline calibration (Phase 6 / 6.2).

Covers the new explicit `jseval calibrate-drift-baseline` path that
replaces the removed first-run-silent-baseline (C-1.8.1).
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from jseval import drift_calibration


def _ort_run_span(encoder: str, duration_ms: float, i: int = 0):
    return {
        "name": "encoder.ort_run",
        "trace_id": f"t{i}",
        "span_id": f"s{i}",
        "duration_ms": duration_ms,
        "attrs": {"encoder.name": encoder},
        "events": [],
    }


def _make_run(
    parent: Path,
    name: str,
    cohort_hash: str,
    encoder_durations: dict[str, list[float]],
    git_sha: str = "sha-x",
) -> Path:
    run_dir = parent / name
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "manifest.json").write_text(
        json.dumps({"manifest_hash": cohort_hash, "git_sha": git_sha}),
        encoding="utf-8",
    )
    spans: list[dict] = []
    i = 0
    for encoder, durations in encoder_durations.items():
        for d in durations:
            spans.append(_ort_run_span(encoder, d, i))
            i += 1
    (run_dir / "traces.ndjson").write_text(
        "\n".join(json.dumps(s) for s in spans) + ("\n" if spans else ""),
        encoding="utf-8",
    )
    return run_dir


class TestCalibrate:
    def test_happy_path_writes_baseline(self, tmp_path):
        data_dir = tmp_path / "data"
        runs = [
            _make_run(tmp_path / "eval", f"r{i}", "cohort-a",
                      {"BgeM3Encoder": [1.0 + 0.05 * j for j in range(20)]})
            for i in range(3)
        ]
        result = drift_calibration.calibrate(
            data_dir=data_dir, cohort_hash="cohort-a", from_runs=runs,
        )
        assert result["status"] == "ok"
        assert result["n_runs_merged"] == 3
        baseline = json.loads(
            Path(result["baseline_path"]).read_text(encoding="utf-8"),
        )
        assert baseline["cohort_hash"] == "cohort-a"
        assert baseline["n_runs_merged"] == 3
        assert baseline["encoders"]["BgeM3Encoder"]["n_samples"] == 60

    def test_insufficient_runs_rejected(self, tmp_path):
        data_dir = tmp_path / "data"
        runs = [
            _make_run(tmp_path / "eval", f"r{i}", "cohort-a",
                      {"BgeM3Encoder": [1.0] * 10})
            for i in range(2)  # below MIN_RUNS_FOR_BASELINE (3)
        ]
        result = drift_calibration.calibrate(
            data_dir=data_dir, cohort_hash="cohort-a", from_runs=runs,
        )
        assert result["status"] == "insufficient-runs"
        assert result["n_runs_required"] == 3
        assert not (data_dir / "cohort_baselines").exists()

    def test_cohort_mismatch_rejected(self, tmp_path):
        data_dir = tmp_path / "data"
        good1 = _make_run(tmp_path / "eval", "g1", "cohort-a",
                          {"E": [1.0] * 10})
        good2 = _make_run(tmp_path / "eval", "g2", "cohort-a",
                          {"E": [1.0] * 10})
        bad = _make_run(tmp_path / "eval", "bad", "cohort-OTHER",
                        {"E": [1.0] * 10})
        result = drift_calibration.calibrate(
            data_dir=data_dir, cohort_hash="cohort-a",
            from_runs=[good1, good2, bad],
        )
        assert result["status"] == "cohort-mismatch"
        assert "bad" in result["mismatch_run"]
        assert result["mismatch_manifest_hash"] == "cohort-OTHER"

    def test_no_encoder_spans(self, tmp_path):
        data_dir = tmp_path / "data"
        runs = [
            _make_run(tmp_path / "eval", f"r{i}", "cohort-a", {})
            for i in range(3)
        ]
        result = drift_calibration.calibrate(
            data_dir=data_dir, cohort_hash="cohort-a", from_runs=runs,
        )
        assert result["status"] == "no-encoder-spans"
        assert "tracing" in result["hint"].lower()

    def test_baseline_exists_guards_overwrite(self, tmp_path):
        data_dir = tmp_path / "data"
        runs = [
            _make_run(tmp_path / "eval", f"r{i}", "cohort-a",
                      {"E": [1.0] * 10})
            for i in range(3)
        ]
        drift_calibration.calibrate(
            data_dir=data_dir, cohort_hash="cohort-a", from_runs=runs,
        )
        # Second invocation without --force → baseline-exists.
        result = drift_calibration.calibrate(
            data_dir=data_dir, cohort_hash="cohort-a", from_runs=runs,
        )
        assert result["status"] == "baseline-exists"

    def test_force_overwrites(self, tmp_path):
        data_dir = tmp_path / "data"
        runs_v1 = [
            _make_run(tmp_path / "eval_v1", f"r{i}", "cohort-a",
                      {"E": [1.0] * 5})
            for i in range(3)
        ]
        drift_calibration.calibrate(
            data_dir=data_dir, cohort_hash="cohort-a", from_runs=runs_v1,
        )
        runs_v2 = [
            _make_run(tmp_path / "eval_v2", f"r{i}", "cohort-a",
                      {"E": [2.0] * 10})
            for i in range(3)
        ]
        result = drift_calibration.calibrate(
            data_dir=data_dir, cohort_hash="cohort-a", from_runs=runs_v2,
            force=True,
        )
        assert result["status"] == "ok"
        baseline = json.loads(
            Path(result["baseline_path"]).read_text(encoding="utf-8"),
        )
        # v2 replaced v1 (5 samples/run * 3 runs = 15 → v2 has 10/run * 3 = 30)
        assert baseline["encoders"]["E"]["n_samples"] == 30

    def test_merges_multiple_encoders(self, tmp_path):
        data_dir = tmp_path / "data"
        runs = [
            _make_run(tmp_path / "eval", f"r{i}", "cohort-a", {
                "EncoderA": [1.0] * 10,
                "EncoderB": [2.0] * 15,
            })
            for i in range(3)
        ]
        result = drift_calibration.calibrate(
            data_dir=data_dir, cohort_hash="cohort-a", from_runs=runs,
        )
        assert result["status"] == "ok"
        assert result["encoders"]["EncoderA"] == 30  # 10 * 3
        assert result["encoders"]["EncoderB"] == 45  # 15 * 3
