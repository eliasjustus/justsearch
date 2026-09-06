"""Tests for encoder_latency.py — the per-encoder p50/p95 summary block (930 §18.1 row 7)."""

from __future__ import annotations

import json

from jseval import encoder_latency


def _write_traces(data_dir, spans, name="traces.ndjson"):
    telemetry = data_dir / "telemetry"
    telemetry.mkdir(parents=True, exist_ok=True)
    (telemetry / name).write_text(
        "\n".join(json.dumps(s) for s in spans) + "\n", encoding="utf-8",
    )


def _ort_span(encoder, duration_ms=None, start=None, end=None):
    span = {"name": "encoder.ort_run", "attrs": {"encoder.name": encoder}}
    if duration_ms is not None:
        span["duration_ms"] = duration_ms
    if start is not None:
        span["start"] = start
    if end is not None:
        span["end"] = end
    return span


class TestBlockShape:
    def test_block_is_emitted_with_no_telemetry_dir_at_all(self, tmp_path):
        assert encoder_latency.build_block(tmp_path / "nothing-here") == {"encoders": {}}

    def test_block_is_emitted_when_traces_hold_no_ort_run_spans(self, tmp_path):
        _write_traces(tmp_path, [{"name": "indexing.batch", "duration_ms": 5.0}])
        assert encoder_latency.build_block(tmp_path) == {"encoders": {}}

    def test_per_encoder_n_p50_p95(self, tmp_path):
        # 1..10 ms for BgeM3Encoder. Nearest rank on (n-1), mirroring
        # run._compute_latency_stats: p50 -> idx int(0.50*9+0.5)=5 (6.0),
        # p95 -> idx int(0.95*9+0.5)=9 (10.0).
        spans = [_ort_span("BgeM3Encoder", duration_ms=float(i)) for i in range(1, 11)]
        spans.append(_ort_span("SpladeEncoder", duration_ms=2.5))
        _write_traces(tmp_path, spans)

        block = encoder_latency.build_block(tmp_path)

        assert set(block) == {"encoders"}
        assert block["encoders"] == {
            "BgeM3Encoder": {"n": 10, "p50_ms": 6.0, "p95_ms": 10.0},
            "SpladeEncoder": {"n": 1, "p50_ms": 2.5, "p95_ms": 2.5},
        }

    def test_encoders_are_sorted_by_name(self, tmp_path):
        _write_traces(tmp_path, [
            _ort_span("SpladeEncoder", duration_ms=1.0),
            _ort_span("BgeM3Encoder", duration_ms=1.0),
            _ort_span("RerankerEncoder", duration_ms=1.0),
        ])
        assert list(encoder_latency.build_block(tmp_path)["encoders"]) == [
            "BgeM3Encoder", "RerankerEncoder", "SpladeEncoder",
        ]

    def test_spans_without_an_encoder_name_are_dropped(self, tmp_path):
        _write_traces(tmp_path, [
            {"name": "encoder.ort_run", "attrs": {}, "duration_ms": 9.0},
            _ort_span("BgeM3Encoder", duration_ms=3.0),
        ])
        assert encoder_latency.build_block(tmp_path)["encoders"] == {
            "BgeM3Encoder": {"n": 1, "p50_ms": 3.0, "p95_ms": 3.0},
        }

    def test_unparseable_lines_do_not_abort_the_block(self, tmp_path):
        telemetry = tmp_path / "telemetry"
        telemetry.mkdir(parents=True)
        (telemetry / "traces.ndjson").write_text(
            "{not json\n" + json.dumps(_ort_span("BgeM3Encoder", duration_ms=4.0)) + "\n",
            encoding="utf-8",
        )
        assert encoder_latency.build_block(tmp_path)["encoders"] == {
            "BgeM3Encoder": {"n": 1, "p50_ms": 4.0, "p95_ms": 4.0},
        }

    def test_rotated_siblings_are_included(self, tmp_path):
        # artifacts.collect_rotated_siblings orders rotated-first, active-last; a run that
        # crossed the 10 MB rotation threshold must not lose the rotated spans (400 §23.9.3).
        _write_traces(tmp_path, [_ort_span("BgeM3Encoder", duration_ms=1.0)],
                      name="traces.20260905-100000.ndjson")
        _write_traces(tmp_path, [_ort_span("BgeM3Encoder", duration_ms=3.0)])
        assert encoder_latency.build_block(tmp_path)["encoders"]["BgeM3Encoder"]["n"] == 2


class TestDurationMsExtractionContract:
    """Producer/consumer contract for the span duration field (tempdoc 400 §23.8 D-1).

    ``NdjsonSpanExporter`` emits a structural ``duration_ms``; older traces.ndjson files
    carry only ms-truncated ``start``/``end``. Inherited from the retired
    ``test_projections_encoder_drift.py`` when 930 §18.1 row 7 moved the reader into
    :mod:`jseval.encoder_latency` — the producer-side field is unchanged, so the contract is.
    """

    def test_structural_duration_ms_is_preferred(self):
        span = {"duration_ms": 1.25, "start": "2026-04-22T11:54:10.046Z",
                "end": "2026-04-22T11:54:10.999Z"}
        assert encoder_latency.span_duration_ms(span) == 1.25

    def test_falls_back_to_start_end_when_duration_ms_absent(self):
        span = {"start": "2026-04-22T11:54:10.046Z", "end": "2026-04-22T11:54:10.058Z"}
        assert encoder_latency.span_duration_ms(span) == 12.0

    def test_accepts_explicit_utc_offset_as_well_as_trailing_z(self):
        span = {"start": "2026-04-22T11:54:10.046+00:00",
                "end": "2026-04-22T11:54:10.058+00:00"}
        assert encoder_latency.span_duration_ms(span) == 12.0

    def test_non_numeric_duration_ms_falls_back_rather_than_raising(self):
        span = {"duration_ms": "n/a", "start": "2026-04-22T11:54:10.046Z",
                "end": "2026-04-22T11:54:10.058Z"}
        assert encoder_latency.span_duration_ms(span) == 12.0

    def test_same_millisecond_start_end_clamps_to_zero_not_negative(self):
        span = {"start": "2026-04-22T11:54:10.046Z", "end": "2026-04-22T11:54:10.046Z"}
        assert encoder_latency.span_duration_ms(span) == 0.0

    def test_missing_both_forms_yields_none_so_the_span_is_dropped(self):
        assert encoder_latency.span_duration_ms({"name": "encoder.ort_run"}) is None

    def test_unparseable_timestamps_yield_none(self):
        span = {"start": "not-a-timestamp", "end": "also-not"}
        assert encoder_latency.span_duration_ms(span) is None
