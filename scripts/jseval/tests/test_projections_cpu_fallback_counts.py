"""Tests for cpu_fallback_counts projection (tempdoc 400 LR4-f)."""

from __future__ import annotations

import json
from pathlib import Path

from jseval.projections.cpu_fallback_counts import (
    FALLBACK_EVENT_NAME,
    MAX_SAMPLES_PER_GROUP,
    PROJECTION,
    aggregate,
    produce,
)


def _fallback_event(encoder: str, cause: str) -> dict:
    return {
        "name": FALLBACK_EVENT_NAME,
        "attrs": {"fallback.encoder": encoder, "fallback.cause": cause},
    }


def _span(name: str, events: list[dict], trace_id: str = "t0", span_id: str = "s0"):
    return {
        "name": name, "trace_id": trace_id, "span_id": span_id,
        "events": events,
    }


class TestAggregate:
    def test_missing_file_returns_empty(self, tmp_path):
        result = aggregate(tmp_path / "no-such.ndjson")
        assert result["total_fallbacks"] == 0
        assert result["by_encoder"] == {}
        assert result["by_cause"] == {}

    def test_empty_file_returns_empty(self, synthetic_run_dir):
        synthetic_run_dir.with_traces([])
        result = aggregate(synthetic_run_dir.run_dir / "traces.ndjson")
        assert result["total_fallbacks"] == 0

    def test_single_fallback_counts(self, synthetic_run_dir):
        synthetic_run_dir.with_traces([
            _span("encoder.ort_run",
                  [_fallback_event("BgeM3Encoder", "gpu-bfc-oom")]),
        ])
        result = aggregate(synthetic_run_dir.run_dir / "traces.ndjson")
        assert result["total_fallbacks"] == 1
        assert result["by_encoder"] == {"BgeM3Encoder": 1}
        assert result["by_cause"] == {"gpu-bfc-oom": 1}
        assert result["by_encoder_and_cause"] == {
            "BgeM3Encoder || gpu-bfc-oom": 1,
        }

    def test_aggregates_across_encoders(self, synthetic_run_dir):
        synthetic_run_dir.with_traces([
            _span("a", [_fallback_event("A", "cause1")]),
            _span("a", [_fallback_event("A", "cause1")]),
            _span("b", [_fallback_event("B", "cause2")]),
            _span("c", [_fallback_event("A", "cause2")]),
        ])
        result = aggregate(synthetic_run_dir.run_dir / "traces.ndjson")
        assert result["total_fallbacks"] == 4
        assert result["by_encoder"] == {"A": 3, "B": 1}
        assert result["by_cause"] == {"cause1": 2, "cause2": 2}
        assert result["by_encoder_and_cause"] == {
            "A || cause1": 2, "A || cause2": 1, "B || cause2": 1,
        }

    def test_samples_capped_per_encoder(self, synthetic_run_dir):
        spans = [
            _span(f"s{i}",
                  [_fallback_event("X", "cause")],
                  trace_id=f"t{i}", span_id=f"sp{i}")
            for i in range(MAX_SAMPLES_PER_GROUP + 5)
        ]
        synthetic_run_dir.with_traces(spans)
        result = aggregate(synthetic_run_dir.run_dir / "traces.ndjson")
        assert result["total_fallbacks"] == MAX_SAMPLES_PER_GROUP + 5
        assert len(result["samples"]["X"]) == MAX_SAMPLES_PER_GROUP

    def test_missing_attrs_default_to_unknown(self, synthetic_run_dir):
        synthetic_run_dir.with_traces([
            _span("a", [{"name": FALLBACK_EVENT_NAME, "attrs": {}}]),
            _span("b", [{"name": FALLBACK_EVENT_NAME,
                         "attrs": {"fallback.encoder": "Only"}}]),
        ])
        result = aggregate(synthetic_run_dir.run_dir / "traces.ndjson")
        assert result["by_encoder"] == {"<unknown>": 1, "Only": 1}
        assert result["by_cause"] == {"<unknown>": 2}

    def test_ignores_non_fallback_events(self, synthetic_run_dir):
        synthetic_run_dir.with_traces([
            _span("a", [
                _fallback_event("A", "c"),
                {"name": "contract.violation", "attrs": {}},
                {"name": "other", "attrs": {}},
            ]),
        ])
        assert aggregate(synthetic_run_dir.run_dir / "traces.ndjson")[
            "total_fallbacks"] == 1

    def test_unparseable_line_skipped(self, synthetic_run_dir):
        traces = synthetic_run_dir.run_dir / "traces.ndjson"
        synthetic_run_dir.run_dir.mkdir(parents=True, exist_ok=True)
        traces.write_text(
            json.dumps(_span("a", [_fallback_event("A", "c")])) + "\n"
            "not-json\n"
            + json.dumps(_span("b", [_fallback_event("B", "c")])) + "\n",
            encoding="utf-8",
        )
        assert aggregate(traces)["total_fallbacks"] == 2


class TestProduce:
    def test_delegates_to_aggregate(self, synthetic_run_dir):
        synthetic_run_dir.with_traces([
            _span("x", [_fallback_event("E", "gpu-oom")]),
        ])
        result = produce(synthetic_run_dir.run_dir)
        assert result["total_fallbacks"] == 1


class TestProjectionRegistration:
    def test_module_export(self):
        assert PROJECTION.name == "cpu_fallback_counts"
        assert PROJECTION.schema_version == 1

    def test_bootstrap_registers(self, synthetic_run_dir):
        from jseval.projections import registry, run_all_discovered
        from jseval.projections.base import reset_registry_for_tests

        reset_registry_for_tests()
        run_all_discovered(synthetic_run_dir.run_dir)
        assert "cpu_fallback_counts" in registry()
        reset_registry_for_tests()

    def test_output_file_written(self, synthetic_run_dir):
        from jseval.projections import run_all_discovered
        from jseval.projections.base import reset_registry_for_tests

        reset_registry_for_tests()
        synthetic_run_dir.with_traces([
            _span("x", [_fallback_event("Enc", "gpu-bfc-oom")]),
        ])
        run_all_discovered(synthetic_run_dir.run_dir)
        doc = json.loads(
            (synthetic_run_dir.run_dir / "projections" /
             "cpu_fallback_counts.json").read_text(encoding="utf-8"),
        )
        assert doc["projection_name"] == "cpu_fallback_counts"
        assert doc["schema_version"] == 1
        assert doc["total_fallbacks"] == 1
        reset_registry_for_tests()
