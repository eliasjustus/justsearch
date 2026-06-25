"""Tests for rate_timeline projection (tempdoc 400 LR4-d)."""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

from jseval.projections.rate_timeline import (
    DEFAULT_STALL_SIGMA,
    DEFAULT_WINDOW_SIZE,
    PROJECTION,
    TRACKED_COUNTERS,
    produce,
)


def _iso(seconds: float) -> str:
    base = datetime(2026, 4, 22, 6, 0, 0, tzinfo=timezone.utc)
    return (base + timedelta(seconds=seconds)).isoformat().replace("+00:00", "Z")


def _metric_record(name: str, value: float, seconds: float) -> dict:
    return {"name": name, "value": value, "t": _iso(seconds)}


def _timeseries(name: str, values: list[float]) -> list[dict]:
    return [_metric_record(name, v, float(i)) for i, v in enumerate(values)]


class TestEmptyInput:
    def test_empty_run_dir(self, synthetic_run_dir):
        result = produce(synthetic_run_dir.run_dir)
        assert set(result["counters"].keys()) == set(TRACKED_COUNTERS)
        for c in result["counters"].values():
            assert c["total_ticks"] == 0
            assert c["stalls"] == []
            assert c["stall_count"] == 0

    def test_empty_metrics_file(self, synthetic_run_dir):
        synthetic_run_dir.with_metrics([])
        result = produce(synthetic_run_dir.run_dir)
        for c in result["counters"].values():
            assert c["total_ticks"] == 0

    def test_records_without_tracked_name_ignored(self, synthetic_run_dir):
        records = [_metric_record("other.metric", i * 1.0, float(i)) for i in range(20)]
        synthetic_run_dir.with_metrics(records)
        result = produce(synthetic_run_dir.run_dir)
        for c in result["counters"].values():
            assert c["total_ticks"] == 0


class TestRateComputation:
    def test_steady_rate_has_no_stalls(self, synthetic_run_dir):
        # 100 → 200 → 300 → ... per second
        records = _timeseries(
            "worker.documents.indexed.total",
            [100 * i for i in range(20)],
        )
        synthetic_run_dir.with_metrics(records)
        result = produce(synthetic_run_dir.run_dir, window_size=5)
        indexed = result["counters"]["worker.documents.indexed.total"]
        assert indexed["total_ticks"] == 20
        # Perfectly steady rate → rolling_stdev=0 → no stall calls.
        assert indexed["stalls"] == []
        # Final value = last sample, avg_rate ≈ 100/sec.
        assert indexed["final_value"] == 1900
        assert indexed["avg_rate"] > 0

    def test_injected_stall_is_detected(self, synthetic_run_dir):
        # 20 ticks at rate=100/sec then a stall tick with rate=5/sec.
        # Perfect-flat baseline → stdev=0 → the 50% relative-drop
        # fallback is what flags the 100→5 dip; sigma_below stays None.
        values = [100 * i for i in range(20)]
        values.append(values[-1] + 5)
        values.append(values[-1] + 100)
        records = _timeseries("worker.batches.submitted.total", values)
        synthetic_run_dir.with_metrics(records)
        result = produce(synthetic_run_dir.run_dir, window_size=5)
        batches = result["counters"]["worker.batches.submitted.total"]
        assert batches["total_ticks"] == 22
        assert batches["stall_count"] >= 1
        stall = batches["stalls"][0]
        assert stall["rate"] < stall["rolling_mean"]
        # Fallback case (zero-stdev baseline): sigma_below=None, stall
        # flagged via 50% relative drop.
        assert stall["rolling_stdev"] == 0
        assert stall["sigma_below"] is None

    def test_stall_with_noisy_baseline_gets_sigma(self, synthetic_run_dir):
        # Non-zero stdev baseline → sigma_below has a numeric value.
        import random
        random.seed(42)
        values: list[float] = []
        current = 0.0
        for _ in range(25):
            current += 100 + random.uniform(-5, 5)  # ~100/sec ± 5% jitter
            values.append(current)
        # Inject a stall: only +5 in the next tick.
        values.append(values[-1] + 5)
        # Resume normal rate for two ticks so total is at least 2 past stall.
        for _ in range(2):
            current = values[-1] + 100 + random.uniform(-5, 5)
            values.append(current)
        records = _timeseries("worker.documents.indexed.total", values)
        synthetic_run_dir.with_metrics(records)
        result = produce(synthetic_run_dir.run_dir, window_size=10)
        indexed = result["counters"]["worker.documents.indexed.total"]
        assert indexed["stall_count"] >= 1
        stall = indexed["stalls"][0]
        assert stall["sigma_below"] is not None
        assert stall["sigma_below"] >= DEFAULT_STALL_SIGMA

    def test_negative_delta_skipped(self, synthetic_run_dir):
        # Counter "reset" (decreasing value) must not flag a stall.
        values = [100, 200, 300, 400, 500, 500, 500, 500, 500, 500, 100]
        records = _timeseries("worker.commits.total", values)
        synthetic_run_dir.with_metrics(records)
        result = produce(synthetic_run_dir.run_dir, window_size=3)
        commits = result["counters"]["worker.commits.total"]
        # Steady flat region may flag on the 500→100 reset, but the
        # reset itself should not appear as a "rate" — it's a drop.
        for stall in commits["stalls"]:
            assert stall["rate"] >= 0

    def test_worker_side_metrics_merged(self, synthetic_run_dir):
        # Head + Worker files both present.
        synthetic_run_dir.with_metrics(_timeseries(
            "worker.documents.indexed.total",
            [100 * i for i in range(10)],
        ))
        # metrics-worker.ndjson lives beside metrics.ndjson in run_dir.
        worker_file = synthetic_run_dir.run_dir / "metrics-worker.ndjson"
        worker_file.write_text(
            "\n".join(json.dumps(r) for r in _timeseries(
                "worker.batches.submitted.total",
                [50 * i for i in range(10)],
            )) + "\n",
            encoding="utf-8",
        )
        result = produce(synthetic_run_dir.run_dir)
        assert result["counters"]["worker.documents.indexed.total"]["total_ticks"] == 10
        assert result["counters"]["worker.batches.submitted.total"]["total_ticks"] == 10


class TestEdgeCases:
    def test_single_tick_no_stall(self, synthetic_run_dir):
        synthetic_run_dir.with_metrics(_timeseries(
            "worker.commits.total", [42],
        ))
        result = produce(synthetic_run_dir.run_dir)
        commits = result["counters"]["worker.commits.total"]
        assert commits["total_ticks"] == 1
        assert commits["stalls"] == []
        assert commits["final_value"] == 42

    def test_zero_duration_avg_rate(self, synthetic_run_dir):
        # Same timestamp on all records → dt=0 for every pair.
        records = [_metric_record("worker.commits.total", i * 1.0, 0.0)
                   for i in range(5)]
        synthetic_run_dir.with_metrics(records)
        result = produce(synthetic_run_dir.run_dir)
        commits = result["counters"]["worker.commits.total"]
        assert commits["total_ticks"] == 5
        # No dt>0 pair → avg_rate stays 0.
        assert commits["avg_rate"] == 0

    def test_skips_unparseable_ts(self, synthetic_run_dir):
        records = [
            {"name": "worker.commits.total", "value": 1.0, "t": "not-a-date"},
            {"name": "worker.commits.total", "value": 2.0, "t": _iso(1)},
            {"name": "worker.commits.total", "value": 3.0, "t": _iso(2)},
        ]
        synthetic_run_dir.with_metrics(records)
        result = produce(synthetic_run_dir.run_dir)
        commits = result["counters"]["worker.commits.total"]
        assert commits["total_ticks"] == 3
        # Stall walk tolerates the None ts on record[0] (pair skipped).

    def test_monotonic_zero_rate_is_not_stall(self, synthetic_run_dir):
        # Counter never increments — rolling_mean stays 0, so no
        # stall flag (the guard `rolling_mean > 0`).
        records = _timeseries(
            "worker.documents.failed.total",
            [0] * 20,
        )
        synthetic_run_dir.with_metrics(records)
        result = produce(synthetic_run_dir.run_dir)
        failed = result["counters"]["worker.documents.failed.total"]
        assert failed["total_ticks"] == 20
        assert failed["stalls"] == []


class TestSchema:
    def test_output_has_expected_top_level_keys(self, synthetic_run_dir):
        result = produce(synthetic_run_dir.run_dir)
        assert "counters" in result
        assert "window_size" in result
        assert "stall_sigma_threshold" in result
        assert result["window_size"] == DEFAULT_WINDOW_SIZE
        assert result["stall_sigma_threshold"] == DEFAULT_STALL_SIGMA

    def test_every_tracked_counter_has_entry(self, synthetic_run_dir):
        result = produce(synthetic_run_dir.run_dir)
        assert set(result["counters"].keys()) == set(TRACKED_COUNTERS)
        for c in result["counters"].values():
            assert "total_ticks" in c
            assert "stalls" in c
            assert "stall_count" in c
            assert "final_value" in c
            assert "duration_s" in c
            assert "avg_rate" in c


class TestProjectionRegistration:
    def test_export_attributes(self):
        assert PROJECTION.name == "rate_timeline"
        assert PROJECTION.schema_version == 1

    def test_bootstrap_picks_up(self, synthetic_run_dir):
        from jseval.projections import registry, run_all_discovered
        from jseval.projections.base import reset_registry_for_tests

        reset_registry_for_tests()
        run_all_discovered(synthetic_run_dir.run_dir)
        assert "rate_timeline" in registry()
        reset_registry_for_tests()


class TestFlatBaselineThresholdConfig:
    """Phase 6 / 6.3: flat-baseline drop threshold is configurable."""

    def test_default_threshold_0_5_flags_at_half(self, synthetic_run_dir):
        # Perfectly flat baseline (all rates = 100); one tick at 30
        # (30% of mean — below default 50%).
        values = [100 * i for i in range(20)]
        values.append(values[-1] + 30)
        values.append(values[-1] + 100)
        synthetic_run_dir.with_metrics(_timeseries(
            "worker.batches.submitted.total", values,
        ))
        result = produce(synthetic_run_dir.run_dir, window_size=5)
        assert result["counters"][
            "worker.batches.submitted.total"]["stall_count"] >= 1

    def test_tighter_threshold_misses_moderate_drop(self, synthetic_run_dir):
        # Same flat baseline + 30% drop, but threshold now 0.2 → 30%
        # drop is ABOVE threshold, should NOT flag.
        values = [100 * i for i in range(20)]
        values.append(values[-1] + 30)
        values.append(values[-1] + 100)
        synthetic_run_dir.with_metrics(_timeseries(
            "worker.batches.submitted.total", values,
        ))
        result = produce(
            synthetic_run_dir.run_dir,
            window_size=5,
            flat_baseline_drop_threshold=0.2,
        )
        # With 20% threshold, only rates below 20 flag. Rate=30 is
        # above, so no stall.
        assert result["counters"][
            "worker.batches.submitted.total"]["stall_count"] == 0

    def test_threshold_in_output(self, synthetic_run_dir):
        result = produce(synthetic_run_dir.run_dir,
                          flat_baseline_drop_threshold=0.3)
        assert result["flat_baseline_drop_threshold"] == 0.3


class TestCountersOverride:
    """Phase 6 / 6.3: `counters` kwarg overrides TRACKED_COUNTERS."""

    def test_custom_counter_tracked(self, synthetic_run_dir):
        synthetic_run_dir.with_metrics(_timeseries(
            "my.custom.total", [10 * i for i in range(5)],
        ))
        result = produce(
            synthetic_run_dir.run_dir,
            counters=("my.custom.total",),
        )
        assert "my.custom.total" in result["counters"]
        assert result["counters"]["my.custom.total"]["total_ticks"] == 5
        # Default counters not in result.
        assert "worker.documents.indexed.total" not in result["counters"]

    def test_default_counters_unchanged_when_no_override(self, synthetic_run_dir):
        result = produce(synthetic_run_dir.run_dir)
        assert set(result["counters"].keys()) == set(TRACKED_COUNTERS)

    def test_tracked_counters_in_output(self, synthetic_run_dir):
        result = produce(synthetic_run_dir.run_dir,
                          counters=("a", "b"))
        assert result["tracked_counters"] == ["a", "b"]


class TestMetricNdjsonContract:
    """Tempdoc 400 §23.8 follow-up — pin the producer/consumer contract.

    The rate_timeline projection reads metric NDJSON with exactly four
    hardcoded counter names (TRACKED_COUNTERS). The real emitter is
    ``modules/telemetry/src/main/java/io/justsearch/telemetry/
    NdjsonMetricExporter.java`` (counter-type records at
    ``sumLine`` L269-275). Until this test existed, nothing asserted
    the consumer's read-shape matched the producer's write-shape —
    exactly the class of defect that hid D-1 (encoder_drift) for
    months (producer emits X, consumer reads Y, synthetic fixture
    matches Y so tests pass regardless).

    These tests construct NDJSON byte-for-byte matching what
    :class:`NdjsonMetricExporter` writes for counter-type metrics:
    ``{"t":"<ISO>","name":"<counter>","type":"counter",
    "value":<num>,"tags":{}}`` and validate the projection consumes
    them correctly. If the Java emitter changes field names or
    order, these tests fail loudly rather than silently returning
    empty counter sections.
    """

    @staticmethod
    def _counter_line(name: str, value: float, iso_ts: str) -> str:
        # Byte-for-byte match to NdjsonMetricExporter.sumLine:
        #   '{"t":"<t>","name":"<name>","type":"counter","value":<val>,"tags":{}}'
        return json.dumps(
            {"t": iso_ts, "name": name, "type": "counter",
             "value": value, "tags": {}},
            separators=(",", ":"),
        )

    def test_parses_exporter_counter_shape(self, synthetic_run_dir):
        """A counter-type NDJSON line emitted by NdjsonMetricExporter is
        parseable by rate_timeline. Uses the exact JSON shape from
        NdjsonMetricExporter.sumLine (L269-275)."""
        lines = "\n".join(
            self._counter_line(
                "worker.documents.indexed.total",
                float(i * 100),
                _iso(float(i)),
            )
            for i in range(6)
        ) + "\n"
        (synthetic_run_dir.run_dir / "metrics.ndjson").write_text(
            lines, encoding="utf-8",
        )
        result = produce(synthetic_run_dir.run_dir)
        c = result["counters"]["worker.documents.indexed.total"]
        assert c["total_ticks"] == 6
        assert c["final_value"] == 500.0
        assert c["avg_rate"] > 0

    def test_all_tracked_counter_names_parse(self, synthetic_run_dir):
        """Each of the 4 TRACKED_COUNTERS parses as expected from
        real-shape NDJSON. If any Worker-side instrumentation
        ever renames a counter, this test catches the rename at
        test time rather than silently losing the counter."""
        lines = []
        for name in TRACKED_COUNTERS:
            for i in range(4):
                lines.append(
                    self._counter_line(name, float(i * 10), _iso(float(i))),
                )
        (synthetic_run_dir.run_dir / "metrics.ndjson").write_text(
            "\n".join(lines) + "\n", encoding="utf-8",
        )
        result = produce(synthetic_run_dir.run_dir)
        for name in TRACKED_COUNTERS:
            assert name in result["counters"], (
                f"TRACKED_COUNTERS entry {name!r} not populated — "
                "producer may have renamed the counter; update "
                "TRACKED_COUNTERS + re-run"
            )
            assert result["counters"][name]["total_ticks"] == 4

    def test_unknown_counter_names_silently_dropped(self, synthetic_run_dir):
        """Records for counters NOT in TRACKED_COUNTERS are filtered
        out without raising. Belongs here (not TestEmptyInput)
        because we want the test expressed over the real exporter
        line shape, not the synthetic dict fixture."""
        mixed = [
            self._counter_line("worker.documents.indexed.total",
                               float(i * 5), _iso(float(i)))
            for i in range(3)
        ] + [
            # Not in TRACKED_COUNTERS — must be silently ignored.
            self._counter_line("worker.some.other.metric",
                               float(i * 5), _iso(float(i)))
            for i in range(3)
        ]
        (synthetic_run_dir.run_dir / "metrics.ndjson").write_text(
            "\n".join(mixed) + "\n", encoding="utf-8",
        )
        result = produce(synthetic_run_dir.run_dir)
        assert result["counters"]["worker.documents.indexed.total"]["total_ticks"] == 3
        # The unknown counter is not surfaced even though it parsed.
        assert "worker.some.other.metric" not in result["counters"]

    def test_gauge_type_records_ignored(self, synthetic_run_dir):
        """NdjsonMetricExporter also emits gauge- and histogram-type
        records to metrics.ndjson. rate_timeline only cares about
        counter-type flow; non-counter records on a tracked name
        should not accidentally feed into the rate computation.

        Current rate_timeline ignores the ``type`` field entirely
        and would treat a gauge value as a counter tick. Document
        that behavior here — if the projection ever starts
        filtering by type, this test will need an update."""
        gauge_line = json.dumps(
            {"t": _iso(0.0),
             "name": "worker.documents.indexed.total",
             "type": "gauge",
             "value": 100.0,
             "tags": {}},
            separators=(",", ":"),
        )
        (synthetic_run_dir.run_dir / "metrics.ndjson").write_text(
            gauge_line + "\n", encoding="utf-8",
        )
        result = produce(synthetic_run_dir.run_dir)
        # 1 record → 1 tick → no rate derivable.
        c = result["counters"]["worker.documents.indexed.total"]
        assert c["total_ticks"] == 1
        assert c["stalls"] == []
