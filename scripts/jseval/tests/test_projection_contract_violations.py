"""Tests for contract_violations projection (tempdoc 400 LR6-c)."""

from __future__ import annotations

import json
from pathlib import Path

from jseval.projections.contract_violations import aggregate, write_report


def _write_traces(path: Path, spans: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(json.dumps(s) for s in spans) + "\n",
        encoding="utf-8",
    )


def _violation_event(tempdoc: str, tier: str, description: str = "") -> dict:
    return {
        "name": "contract.violation",
        "attrs": {
            "contract.tempdoc": tempdoc,
            "contract.tier": tier,
            "contract.description": description,
        },
    }


def _span(name: str, events: list[dict]) -> dict:
    return {
        "trace_id": "t" + name,
        "span_id": "s" + name,
        "name": name,
        "events": events,
    }


class TestAggregate:
    def test_missing_file_returns_empty(self, tmp_path):
        result = aggregate(tmp_path / "does-not-exist.ndjson")
        assert result["total_violations"] == 0
        assert result["by_tempdoc"] == {}
        assert result["by_tier"] == {}
        assert result["samples"] == []

    def test_empty_file_returns_empty(self, tmp_path):
        traces = tmp_path / "traces.ndjson"
        traces.write_text("", encoding="utf-8")
        result = aggregate(traces)
        assert result["total_violations"] == 0

    def test_spans_without_violation_events_return_empty(self, tmp_path):
        traces = tmp_path / "traces.ndjson"
        _write_traces(traces, [
            _span("indexing.batch", []),
            _span("search/retrieval", [{"name": "other.event", "attrs": {}}]),
        ])
        assert aggregate(traces)["total_violations"] == 0

    def test_counts_single_violation(self, tmp_path):
        traces = tmp_path / "traces.ndjson"
        _write_traces(traces, [
            _span("encoder.ort_run", [_violation_event("397 §14.25", "@BuildContract")]),
        ])
        result = aggregate(traces)
        assert result["total_violations"] == 1
        assert result["by_tempdoc"] == {"397 §14.25": 1}
        assert result["by_tier"] == {"@BuildContract": 1}
        assert result["by_tempdoc_and_tier"] == {"397 §14.25 || @BuildContract": 1}

    def test_aggregates_by_tempdoc_and_tier(self, tmp_path):
        traces = tmp_path / "traces.ndjson"
        _write_traces(traces, [
            _span("a", [
                _violation_event("397 §14.25", "@BuildContract"),
                _violation_event("397 §14.25", "@BuildContract"),
                _violation_event("397 §14.25", "@AdvisoryContract"),
                _violation_event("400 §8.2", "@BuildContract"),
            ]),
        ])
        result = aggregate(traces)
        assert result["total_violations"] == 4
        assert result["by_tempdoc"] == {"397 §14.25": 3, "400 §8.2": 1}
        assert result["by_tier"] == {"@BuildContract": 3, "@AdvisoryContract": 1}
        assert result["by_tempdoc_and_tier"] == {
            "397 §14.25 || @BuildContract": 2,
            "397 §14.25 || @AdvisoryContract": 1,
            "400 §8.2 || @BuildContract": 1,
        }

    def test_samples_captures_first_10(self, tmp_path):
        traces = tmp_path / "traces.ndjson"
        spans = [
            _span(f"span-{i}", [_violation_event("t1", "tier1", f"desc {i}")])
            for i in range(15)
        ]
        _write_traces(traces, spans)
        result = aggregate(traces)
        assert result["total_violations"] == 15
        # Samples capped at 10.
        assert len(result["samples"]) == 10
        # Per-sample shape: trace_id, span_id, span_name, attrs
        assert all(k in result["samples"][0]
                   for k in ["trace_id", "span_id", "span_name", "attrs"])

    def test_missing_tempdoc_tier_attrs_default_to_unknown(self, tmp_path):
        traces = tmp_path / "traces.ndjson"
        _write_traces(traces, [
            _span("a", [
                {"name": "contract.violation", "attrs": {}},  # both missing
                {"name": "contract.violation", "attrs": {"contract.tempdoc": "x"}},  # tier missing
            ]),
        ])
        result = aggregate(traces)
        assert result["by_tempdoc"] == {"<unknown>": 1, "x": 1}
        assert result["by_tier"] == {"<unknown>": 2}

    def test_skips_unparseable_lines(self, tmp_path):
        traces = tmp_path / "traces.ndjson"
        traces.write_text(
            json.dumps(_span("ok", [_violation_event("td", "tier")])) + "\n"
            "not-json-garbage\n"
            + json.dumps(_span("ok2", [_violation_event("td", "tier")])) + "\n",
            encoding="utf-8",
        )
        result = aggregate(traces)
        assert result["total_violations"] == 2

    def test_ignores_non_contract_violation_events(self, tmp_path):
        traces = tmp_path / "traces.ndjson"
        _write_traces(traces, [
            _span("a", [
                _violation_event("td", "tier"),
                {"name": "cpu_fallback.triggered", "attrs": {"fallback.cause": "x"}},
                {"name": "some.other.event", "attrs": {}},
            ]),
        ])
        assert aggregate(traces)["total_violations"] == 1


class TestWriteReport:
    def test_writes_sorted_json(self, tmp_path):
        result = aggregate(tmp_path / "does-not-exist.ndjson")
        out = write_report(result, tmp_path)
        assert out.exists()
        reloaded = json.loads(out.read_text(encoding="utf-8"))
        assert reloaded == result


class TestSelfFeedFromProjectionErrors:
    """Phase 6 / 6.1: aggregator reads sibling
    `projections/_errors.ndjson` so projection-dispatcher failures
    surface in the same aggregate the nightly gate already checks."""

    def test_reads_projection_errors_alongside_traces(self, tmp_path):
        traces = tmp_path / "traces.ndjson"
        _write_traces(traces, [
            _span("parent", [_violation_event("td", "@BuildContract")]),
        ])
        errors = tmp_path / "projections" / "_errors.ndjson"
        errors.parent.mkdir(parents=True, exist_ok=True)
        errors.write_text(
            json.dumps({
                "name": "projection.failure",
                "trace_id": "projection-failure",
                "span_id": "broken_proj",
                "events": [{
                    "name": "contract.violation",
                    "attrs": {
                        "contract.tempdoc": "400 §27.3",
                        "contract.tier": "@AdvisoryContract",
                        "contract.projection": "broken_proj",
                        "contract.description": "boom",
                    },
                }],
            }) + "\n",
            encoding="utf-8",
        )

        result = aggregate(traces)
        assert result["total_violations"] == 2
        assert result["by_tier"]["@BuildContract"] == 1
        assert result["by_tier"]["@AdvisoryContract"] == 1
        assert result["by_tempdoc"]["td"] == 1
        assert result["by_tempdoc"]["400 §27.3"] == 1

    def test_missing_errors_file_is_not_error(self, tmp_path):
        traces = tmp_path / "traces.ndjson"
        _write_traces(traces, [
            _span("a", [_violation_event("td", "tier")]),
        ])
        result = aggregate(traces)
        assert result["total_violations"] == 1

    def test_traces_under_projections_dir_skips_self_feed(self, tmp_path):
        # Guard: if traces_path already lives under a projections/
        # dir, don't re-merge a sibling _errors.ndjson.
        proj = tmp_path / "projections"
        proj.mkdir()
        traces = proj / "traces.ndjson"
        _write_traces(traces, [_span("a", [_violation_event("td", "tier")])])
        (proj / "_errors.ndjson").write_text(
            json.dumps({
                "name": "projection.failure",
                "events": [{
                    "name": "contract.violation",
                    "attrs": {"contract.tempdoc": "x", "contract.tier": "y"},
                }],
            }) + "\n",
            encoding="utf-8",
        )
        result = aggregate(traces)
        assert result["total_violations"] == 1
