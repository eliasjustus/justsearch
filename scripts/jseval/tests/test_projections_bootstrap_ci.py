"""Tests for bootstrap_ci projection (tempdoc 400 LR4-b)."""

from __future__ import annotations

import json

import numpy as np

from jseval.projections.bootstrap_ci import (
    DEFAULT_CI_LEVEL,
    MIN_QUERIES_FOR_CI,
    PROJECTION,
    bootstrap_ci,
    produce,
)


def _entries(ndcg_values: list[float], mode: str = "hybrid") -> list[dict]:
    """Synthesize per_query.json entries with a controlled nDCG@10 column."""
    return [
        {
            "qid": f"q{i}",
            "query": f"query {i}",
            "mode": mode,
            "ndcgAtK": v,
            "apAtK": v * 0.6,
            "mrrAtK": v * 0.9,
            "recallAtK": v * 1.1 if v * 1.1 <= 1.0 else 1.0,
            "p1AtK": 1.0 if v >= 0.5 else 0.0,
        }
        for i, v in enumerate(ndcg_values)
    ]


class TestBootstrapCI:
    def test_insufficient_returns_insufficient_status(self):
        result = bootstrap_ci([0.5, 0.6], rng_seed=1)
        assert result["ci_status"] == "insufficient"
        assert result["ci_low"] is None
        assert result["ci_high"] is None
        assert result["mean"] == 0.55
        assert result["n_queries"] == 2

    def test_empty_returns_empty_status(self):
        result = bootstrap_ci([], rng_seed=1)
        assert result["ci_status"] == "empty"
        assert result["mean"] is None
        assert result["n_queries"] == 0

    def test_ci_brackets_mean(self):
        # Reasonable spread over 50 values → CI should bracket the mean.
        rng = np.random.default_rng(42)
        values = rng.uniform(0.4, 0.9, size=50).tolist()
        result = bootstrap_ci(values, n_resamples=500, rng_seed=1)
        assert result["ci_status"] == "ok"
        assert result["ci_low"] is not None and result["ci_high"] is not None
        assert result["ci_low"] <= result["mean"] <= result["ci_high"]

    def test_determinism_given_seed(self):
        values = [0.5, 0.6, 0.7, 0.8, 0.55, 0.65, 0.75, 0.85, 0.45, 0.9]
        a = bootstrap_ci(values, n_resamples=200, rng_seed=7)
        b = bootstrap_ci(values, n_resamples=200, rng_seed=7)
        assert a == b

    def test_tight_vs_wide_distribution(self):
        tight = [0.70 + 1e-6 * (i - 5) for i in range(11)]
        wide = [0.70 + 0.3 * (i - 5) / 5 for i in range(11)]
        tight_ci = bootstrap_ci(tight, n_resamples=500, rng_seed=3)
        wide_ci = bootstrap_ci(wide, n_resamples=500, rng_seed=3)
        tight_width = tight_ci["ci_high"] - tight_ci["ci_low"]
        wide_width = wide_ci["ci_high"] - wide_ci["ci_low"]
        assert tight_width < wide_width


class TestProduce:
    def test_empty_run_dir_returns_empty_modes(self, synthetic_run_dir):
        result = produce(synthetic_run_dir.run_dir)
        assert result["modes"] == {}
        assert result["n_resamples"] == 1000
        assert result["ci_level"] == DEFAULT_CI_LEVEL

    def test_single_mode_per_query_produces_ci(self, synthetic_run_dir):
        ndcg = [0.5, 0.6, 0.7, 0.65, 0.55, 0.72, 0.68, 0.58, 0.62, 0.71]
        synthetic_run_dir.with_per_query("hybrid", _entries(ndcg))
        result = produce(synthetic_run_dir.run_dir, n_resamples=300)
        assert "hybrid" in result["modes"]
        hy = result["modes"]["hybrid"]
        assert hy["nDCG@10"]["ci_status"] == "ok"
        assert hy["nDCG@10"]["mean"] == sum(ndcg) / len(ndcg)
        assert hy["nDCG@10"]["ci_low"] <= hy["nDCG@10"]["mean"] <= hy["nDCG@10"]["ci_high"]
        # All 5 metric labels present.
        assert set(hy.keys()) == {"nDCG@10", "AP@10", "RR@10", "R@10", "P@1"}

    def test_multi_mode(self, synthetic_run_dir):
        synthetic_run_dir.with_per_query("hybrid", _entries([0.7] * 10))
        synthetic_run_dir.with_per_query("lexical", _entries([0.4] * 10))
        result = produce(synthetic_run_dir.run_dir, n_resamples=100)
        assert set(result["modes"].keys()) == {"hybrid", "lexical"}
        assert result["modes"]["hybrid"]["nDCG@10"]["mean"] == 0.7
        assert result["modes"]["lexical"]["nDCG@10"]["mean"] == 0.4

    def test_missing_metric_values_skipped(self, synthetic_run_dir):
        # Entries with None ndcgAtK should drop out of that metric's CI.
        entries = _entries([0.5, 0.6, 0.7, 0.8, 0.9])
        entries[2]["ndcgAtK"] = None
        synthetic_run_dir.with_per_query("hybrid", entries)
        result = produce(synthetic_run_dir.run_dir, n_resamples=100)
        assert result["modes"]["hybrid"]["nDCG@10"]["n_queries"] == 4
        # AP@10 still has all 5 values.
        assert result["modes"]["hybrid"]["AP@10"]["n_queries"] == 5

    def test_insufficient_below_threshold(self, synthetic_run_dir):
        synthetic_run_dir.with_per_query("hybrid", _entries([0.5, 0.6, 0.7]))
        result = produce(synthetic_run_dir.run_dir, n_resamples=100)
        assert result["modes"]["hybrid"]["nDCG@10"]["ci_status"] == "insufficient"
        assert result["modes"]["hybrid"]["nDCG@10"]["ci_low"] is None

    def test_unreadable_file_is_skipped(self, synthetic_run_dir, tmp_path):
        (synthetic_run_dir.run_dir / "broken_per_query.json").write_text(
            "not-json", encoding="utf-8",
        )
        result = produce(synthetic_run_dir.run_dir, n_resamples=100)
        # Skipped mode does not appear in the result.
        assert "broken" not in result["modes"]


class TestProjectionRegistration:
    def test_module_exports_projection(self):
        assert PROJECTION.name == "bootstrap_ci"
        assert PROJECTION.schema_version == 1

    def test_registered_when_bootstrap_runs(self, synthetic_run_dir):
        from jseval.projections import registry, run_all_discovered
        from jseval.projections.base import reset_registry_for_tests

        reset_registry_for_tests()
        synthetic_run_dir.with_per_query("hybrid", _entries([0.5] * 10))
        summary = run_all_discovered(synthetic_run_dir.run_dir)
        assert "bootstrap_ci" in summary
        assert summary["bootstrap_ci"]["status"] == "ok"
        reset_registry_for_tests()

    def test_output_includes_schema_version_and_modes(self, synthetic_run_dir):
        from jseval.projections import run_all_discovered
        from jseval.projections.base import reset_registry_for_tests

        reset_registry_for_tests()
        ndcg = [0.5, 0.6, 0.7, 0.65, 0.55, 0.72, 0.68, 0.58, 0.62, 0.71]
        synthetic_run_dir.with_per_query("hybrid", _entries(ndcg))
        run_all_discovered(synthetic_run_dir.run_dir)
        out = synthetic_run_dir.run_dir / "projections" / "bootstrap_ci.json"
        doc = json.loads(out.read_text(encoding="utf-8"))
        assert doc["projection_name"] == "bootstrap_ci"
        assert doc["schema_version"] == 1
        assert "hybrid" in doc["modes"]
        reset_registry_for_tests()
