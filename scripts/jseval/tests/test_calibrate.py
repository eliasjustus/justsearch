"""Tests for calibrate.py — non-determinism envelope calibration (LR1-b).

Phase 2.2a (this commit) covers the sidecar registry round-trip. The
calibration math is exercised by test_calibrate_core added in
Phase 2.2b.
"""

from __future__ import annotations

import json

from jseval.calibrate import (
    CALIBRATED_LATENCY_METRICS,
    CALIBRATED_QUALITY_METRICS,
    ENVELOPE_SCHEMA_VERSION,
    _collect_metrics,
    compute_envelope,
    envelope_registry_dir,
    read_envelope,
    write_envelope,
)


class TestRegistryDir:
    def test_registry_path_under_data_dir(self, tmp_path):
        # Phase 3 layout (§26.6 Decision 2).
        got = envelope_registry_dir(tmp_path)
        assert got == tmp_path / "cohort_baselines"

    def test_registry_dir_not_created_on_read(self, tmp_path):
        # Pure accessor — callers that don't write shouldn't leave behind
        # an empty directory.
        envelope_registry_dir(tmp_path)
        assert not (tmp_path / "cohort_baselines").exists()


class TestReadEnvelope:
    def test_missing_cohort_returns_none(self, tmp_path):
        assert read_envelope(tmp_path, "cohort-xyz") is None

    def test_missing_registry_dir_returns_none(self, tmp_path):
        # No registry dir at all — still must not raise.
        assert read_envelope(tmp_path / "does-not-exist", "cohort-xyz") is None

    def test_invalid_json_new_path_returns_none(self, tmp_path):
        # Invalid JSON at the Phase-3 path → None (after legacy fallback
        # also misses).
        registry = tmp_path / "cohort_baselines" / "cohort-abc"
        registry.mkdir(parents=True)
        (registry / "envelope.json").write_text(
            "not-valid-json", encoding="utf-8")
        assert read_envelope(tmp_path, "cohort-abc") is None

    def test_legacy_shim_reads_phase2_sidecar(self, tmp_path):
        # Phase-2 sidecar at the legacy path — read_envelope must still
        # find it when the Phase-3 path is absent (§26.6 Decision 2
        # backward-compat shim).
        legacy = tmp_path / "non_determinism_envelopes"
        legacy.mkdir()
        envelope = {"cohort_hash": "legacy", "schema_version": 1,
                    "metrics": {}}
        (legacy / "legacy.json").write_text(
            json.dumps(envelope), encoding="utf-8")
        assert read_envelope(tmp_path, "legacy") == envelope

    def test_new_path_preferred_over_legacy(self, tmp_path):
        legacy = tmp_path / "non_determinism_envelopes"
        legacy.mkdir()
        (legacy / "x.json").write_text(
            json.dumps({"from": "legacy"}), encoding="utf-8")
        new_dir = tmp_path / "cohort_baselines" / "x"
        new_dir.mkdir(parents=True)
        (new_dir / "envelope.json").write_text(
            json.dumps({"from": "new"}), encoding="utf-8")
        assert read_envelope(tmp_path, "x") == {"from": "new"}


class TestWriteEnvelope:
    def test_writes_sidecar_and_creates_parent(self, tmp_path):
        envelope = {
            "cohort_hash": "abc",
            "schema_version": 1,
            "n_runs": 5,
            "metrics": {"full": {"nDCG@10": {"mean": 0.82, "stdev": 0.001, "n": 5}}},
        }
        path = write_envelope(tmp_path, "abc", envelope)
        assert path.exists()
        # Phase-3 layout: cohort_baselines/<hash>/envelope.json
        assert path.parent == tmp_path / "cohort_baselines" / "abc"
        assert path.name == "envelope.json"

    def test_round_trip_via_read(self, tmp_path):
        envelope = {
            "cohort_hash": "cohort-123",
            "schema_version": ENVELOPE_SCHEMA_VERSION,
            "calibrated_at": "2026-04-22T05:00:00Z",
            "git_sha": "abc123",
            "n_runs": 5,
            "metrics": {
                "full": {
                    "nDCG@10": {"mean": 0.82, "stdev": 0.00108, "n": 5},
                },
            },
        }
        write_envelope(tmp_path, "cohort-123", envelope)
        reloaded = read_envelope(tmp_path, "cohort-123")
        assert reloaded == envelope

    def test_sorted_keys_on_write(self, tmp_path):
        # Canonical output allows manual + diff tooling to compare
        # envelopes across versions without key-order noise.
        envelope = {"z": 1, "a": 2, "m": 3}
        path = write_envelope(tmp_path, "cohort", envelope)
        raw = path.read_text(encoding="utf-8")
        # Sort order: a < m < z.
        assert raw.index('"a"') < raw.index('"m"') < raw.index('"z"')


class TestCalibratedMetricSetConstants:
    def test_quality_metrics_cover_standard_set(self):
        # Lock the calibrated quality metric set so Phase 2.2b cannot
        # silently widen or narrow it without a deliberate test update.
        assert CALIBRATED_QUALITY_METRICS == (
            "nDCG@10", "P@1", "R@10", "RR@10", "AP@10",
        )

    def test_latency_metrics_exclude_tails(self):
        # Q1 finding (tempdoc 400 §23): p95/p99/max are cold-start-
        # dominated (cv ≥ 64%) and must not enter the envelope. The
        # calibrated latency set is restricted to stable statistics.
        assert CALIBRATED_LATENCY_METRICS == ("mean_ms", "p50_ms")
        assert "p95_ms" not in CALIBRATED_LATENCY_METRICS
        assert "p99_ms" not in CALIBRATED_LATENCY_METRICS
        assert "max_ms" not in CALIBRATED_LATENCY_METRICS

    def test_schema_version_locked_at_one(self):
        assert ENVELOPE_SCHEMA_VERSION == 1


class TestCollectMetrics:
    """Phase 2.2b: per-run metric extraction from summary.json."""

    def _write_summary(self, tmp_path, summary):
        import json
        (tmp_path / "summary.json").write_text(
            json.dumps(summary), encoding="utf-8")
        return tmp_path

    def test_extracts_quality_and_latency_metrics(self, tmp_path):
        run = self._write_summary(tmp_path, {
            "per_mode": {
                "full": {
                    "aggregate_metrics": {
                        "nDCG@10": 0.8215,
                        "P@1": 0.72,
                        "R@10": 0.922,
                        "RR@10": 0.7995,
                        "AP@10": 0.7807,
                    },
                    "latency_stats": {
                        "mean_ms": 33.5, "p50_ms": 32,
                        "p95_ms": 48, "p99_ms": 80, "max_ms": 80,
                    },
                },
            },
        })
        out = _collect_metrics(run)
        assert set(out.keys()) == {"full"}
        # Quality metrics present
        for m in CALIBRATED_QUALITY_METRICS:
            assert m in out["full"]
        # mean_ms + p50_ms present
        assert out["full"]["mean_ms"] == 33.5
        assert out["full"]["p50_ms"] == 32
        # Tail latencies excluded from calibrated set
        assert "p95_ms" not in out["full"]
        assert "p99_ms" not in out["full"]
        assert "max_ms" not in out["full"]

    def test_ignores_modes_without_metrics(self, tmp_path):
        run = self._write_summary(tmp_path, {
            "per_mode": {"full": {"aggregate_metrics": {}, "latency_stats": {}}},
        })
        out = _collect_metrics(run)
        assert out == {}

    def test_handles_multiple_modes(self, tmp_path):
        run = self._write_summary(tmp_path, {
            "per_mode": {
                "full": {
                    "aggregate_metrics": {"nDCG@10": 0.82},
                    "latency_stats": {},
                },
                "lexical": {
                    "aggregate_metrics": {"nDCG@10": 0.65},
                    "latency_stats": {},
                },
            },
        })
        out = _collect_metrics(run)
        assert out == {
            "full": {"nDCG@10": 0.82},
            "lexical": {"nDCG@10": 0.65},
        }


class TestComputeEnvelope:
    """Phase 2.2b: stdev + mean aggregation across N identical runs."""

    def test_schema_v1_shape(self):
        per_run = [
            {"full": {"nDCG@10": 0.82, "P@1": 0.72, "mean_ms": 33.0}},
            {"full": {"nDCG@10": 0.82, "P@1": 0.72, "mean_ms": 34.5}},
            {"full": {"nDCG@10": 0.82, "P@1": 0.72, "mean_ms": 32.1}},
        ]
        env = compute_envelope(per_run, cohort_hash="abc123", n_runs=3)
        assert env["cohort_hash"] == "abc123"
        assert env["schema_version"] == ENVELOPE_SCHEMA_VERSION
        assert env["n_runs"] == 3
        assert "calibrated_at" in env
        assert "git_sha" in env  # may be None off-tree; key must be present
        # Per-mode, per-metric: mean + stdev + n
        entry = env["metrics"]["full"]["nDCG@10"]
        assert set(entry.keys()) == {"mean", "stdev", "n"}

    def test_stdev_matches_expected_q1_values(self):
        # Replay Q1's empirical values: nDCG@10 across 3 scifact runs.
        per_run = [
            {"full": {"nDCG@10": 0.8215}},
            {"full": {"nDCG@10": 0.8211}},
            {"full": {"nDCG@10": 0.8211}},
        ]
        env = compute_envelope(per_run, cohort_hash="x", n_runs=3)
        entry = env["metrics"]["full"]["nDCG@10"]
        # statistics.stdev uses sample stdev (ddof=1); matches Q1's report.
        assert abs(entry["stdev"] - 0.00023) < 0.0001
        assert abs(entry["mean"] - 0.82123) < 0.0001
        assert entry["n"] == 3

    def test_zero_variance_metric_yields_zero_stdev(self):
        per_run = [
            {"full": {"P@1": 0.72}},
            {"full": {"P@1": 0.72}},
            {"full": {"P@1": 0.72}},
        ]
        env = compute_envelope(per_run, cohort_hash="x", n_runs=3)
        assert env["metrics"]["full"]["P@1"]["stdev"] == 0

    def test_metric_with_single_sample_is_omitted(self):
        # Can't compute stdev from one value; omit rather than synthesize.
        per_run = [
            {"full": {"nDCG@10": 0.82, "P@1": 0.72}},
            {"full": {"nDCG@10": 0.82}},  # P@1 missing in this run
        ]
        env = compute_envelope(per_run, cohort_hash="x", n_runs=2)
        assert "nDCG@10" in env["metrics"]["full"]
        assert "P@1" not in env["metrics"]["full"]

    def test_missing_mode_in_some_runs_still_calibrates_present_modes(self):
        per_run = [
            {"full": {"nDCG@10": 0.82}},
            {"full": {"nDCG@10": 0.82}},
            {"lexical": {"nDCG@10": 0.65}},
        ]
        env = compute_envelope(per_run, cohort_hash="x", n_runs=3)
        assert "full" in env["metrics"]
        assert "lexical" not in env["metrics"]  # only 1 sample — omitted


class TestCalibrateOrchestration:
    """Phase 2.2b: top-level calibrate() orchestration.

    Note: the full end-to-end orchestration is covered by the regression
    script (runs ~20 min for N=5 scifact smokes), not unit tests. These
    tests cover the guard conditions that can be checked synchronously.
    """

    def test_calibrate_rejects_single_run(self, tmp_path):
        import pytest
        from jseval.calibrate import calibrate
        with pytest.raises(ValueError, match="runs must be >= 2"):
            calibrate(
                dataset="scifact",
                modes=["full"],
                runs=1,
                data_dir=tmp_path,
                max_queries=5,
            )
