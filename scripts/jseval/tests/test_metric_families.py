"""Tests for metric_families.py — the engine-quality metric-family registry (tempdoc 640)."""

from __future__ import annotations

from jseval import metric_families as mf


def test_registry_covers_the_families():
    names = {f.name for f in mf.REGISTRY}
    assert names == {"quality", "perf-latency", "perf-throughput", "perf-footprint", "leak", "llm-gen"}


def test_every_metric_key_has_a_direction():
    for fam in mf.REGISTRY:
        assert set(fam.lower_is_better) == set(fam.metric_keys), fam.name


def test_source_classes_are_valid_and_leak_is_projection():
    assert {f.source_class for f in mf.REGISTRY} <= {"per_mode", "per_run", "projection", "bench"}
    # the leak-fold finding: leak is a cross-mode projection metric, not per-mode/per-run
    assert mf.BY_NAME["leak"].source_class == "projection"
    assert mf.BY_NAME["leak"].comparator == "ceiling"
    # llm-gen is a bench-sourced family (tempdoc 640 L): TTFT/e2e from llm-bench.json
    assert mf.BY_NAME["llm-gen"].source_class == "bench"
    assert mf.BY_NAME["llm-gen"].metric_keys == ("ttft_p50_ms", "e2e_latency_p50_ms", "token_rate_median_tps")
    # tokens/sec is higher-is-better (more throughput = better); latencies are lower-is-better
    assert mf.BY_NAME["llm-gen"].lower_is_better["token_rate_median_tps"] is False


def test_perf_families_use_ratio_bands():
    for fam in mf.perf_families():
        assert fam.comparator == "ratio"
        assert set(fam.bands) == set(fam.metric_keys), fam.name


def test_ce_latency_lives_in_aggregate_metrics_so_it_flows_like_quality():
    # perf-latency (CE-stage p50) is promoted into the per-mode aggregate_metrics map
    lat = mf.BY_NAME["perf-latency"]
    assert lat.source_class == "per_mode"
    assert lat.source_path == "aggregate_metrics"
    assert lat.metric_keys == ("ce_p50_ms",)
    assert lat.lower_is_better["ce_p50_ms"] is True


def test_per_run_families_share_the_run_metrics_map():
    for name in ("perf-throughput", "perf-footprint"):
        fam = mf.BY_NAME[name]
        assert fam.source_class == "per_run"
        assert fam.source_path == "run_metrics"


def test_calibrated_per_mode_metrics_unions_quality_and_ce_latency():
    cal = mf.calibrated_per_mode_metrics()
    # both quality and CE-latency calibrate via the same aggregate_metrics source
    assert "aggregate_metrics" in cal
    keys = set(cal["aggregate_metrics"])
    assert "nDCG@10" in keys and "ce_p50_ms" in keys
    # per-run families are not calibrated (noisier, not per-mode)
    assert mf.families(source_class="per_run", calibrate=True) == ()


def test_families_filter():
    assert {f.name for f in mf.families(source_class="per_mode")} == {"quality", "perf-latency"}
    assert {f.name for f in mf.families(comparator="ratio")} == {
        "perf-latency", "perf-throughput", "perf-footprint", "llm-gen",
    }
    # perf_families() is name-scoped, so the bench-sourced llm-gen is NOT a perf family
    assert "llm-gen" not in {f.name for f in mf.perf_families()}
