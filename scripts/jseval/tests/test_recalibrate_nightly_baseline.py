"""Tests for `jseval recalibrate-nightly-baseline` (Phase 6 / 6.6)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from click.testing import CliRunner

from jseval.cli import main as cli_main


def _seed_envelope(data_dir: Path, cohort_hash: str, *,
                    metric: str = "nDCG@10", mode: str = "full",
                    stdev: float = 0.00142):
    cohort_dir = data_dir / "cohort_baselines" / cohort_hash
    cohort_dir.mkdir(parents=True, exist_ok=True)
    (cohort_dir / "envelope.json").write_text(
        json.dumps({
            "schema_version": 1,
            "cohort_hash": cohort_hash,
            "calibrated_at": "2026-04-22T00:00:00Z",
            "metrics": {mode: {metric: {"mean": 0.7, "stdev": stdev, "n": 5}}},
        }),
        encoding="utf-8",
    )


class TestRecalibrateNightly:
    def test_prints_value_when_no_output(self, tmp_path):
        _seed_envelope(tmp_path, "cohort-x")
        runner = CliRunner()
        result = runner.invoke(cli_main, [
            "recalibrate-nightly-baseline",
            "--data-dir", str(tmp_path),
            "--cohort-hash", "cohort-x",
        ])
        assert result.exit_code == 0, result.output
        assert "PHASE3_BASELINE_NDCG10_STDEV=0.00142" in result.output

    def test_writes_env_file_when_output_set(self, tmp_path):
        _seed_envelope(tmp_path, "cohort-x")
        env_file = tmp_path / "baseline.env"
        runner = CliRunner()
        result = runner.invoke(cli_main, [
            "recalibrate-nightly-baseline",
            "--data-dir", str(tmp_path),
            "--cohort-hash", "cohort-x",
            "--output", str(env_file),
        ])
        assert result.exit_code == 0, result.output
        assert env_file.is_file()
        assert "PHASE3_BASELINE_NDCG10_STDEV=0.00142" in env_file.read_text(
            encoding="utf-8",
        )

    def test_missing_envelope_exits_2(self, tmp_path):
        runner = CliRunner()
        result = runner.invoke(cli_main, [
            "recalibrate-nightly-baseline",
            "--data-dir", str(tmp_path),
            "--cohort-hash", "nope",
        ])
        assert result.exit_code == 2
        assert "envelope not found" in result.output

    def test_metric_not_in_envelope_exits_2(self, tmp_path):
        _seed_envelope(tmp_path, "cohort-x", metric="nDCG@10")
        runner = CliRunner()
        result = runner.invoke(cli_main, [
            "recalibrate-nightly-baseline",
            "--data-dir", str(tmp_path),
            "--cohort-hash", "cohort-x",
            "--metric", "RR@10",
        ])
        assert result.exit_code == 2
        assert "no RR@10 stdev" in result.output

    def test_env_var_name_normalized(self, tmp_path):
        # Metric with '@' → underscore and upper-cased in env name.
        _seed_envelope(tmp_path, "cohort-x", metric="P@1", stdev=0.05)
        runner = CliRunner()
        result = runner.invoke(cli_main, [
            "recalibrate-nightly-baseline",
            "--data-dir", str(tmp_path),
            "--cohort-hash", "cohort-x",
            "--metric", "P@1",
        ])
        assert result.exit_code == 0, result.output
        assert "PHASE3_BASELINE_P1_STDEV=0.05" in result.output
