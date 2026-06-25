"""Tests for encoder_drift projection (tempdoc 400 LR4-g)."""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from tests.conftest import SyntheticRunDir

from jseval.projections.encoder_drift import (
    PROJECTION,
    PSI_DRIFT_THRESHOLD,
    _extract_encoder_durations,
    _span_duration_ms,
    produce,
    psi,
)


def _ort_run_span(encoder: str, duration_ms: float, trace_id: str = "t0"):
    return {
        "name": "encoder.ort_run",
        "trace_id": trace_id,
        "span_id": f"s-{trace_id}",
        "duration_ms": duration_ms,
        "attrs": {"encoder.name": encoder},
        "events": [],
    }


def _ort_run_span_exporter_shape(
    encoder: str, start_iso: str, end_iso: str, trace_id: str = "t0",
):
    """Synthesize a span matching the ACTUAL shape emitted by
    :class:`NdjsonSpanExporter` (tempdoc 400 §23.8 D-1 regression): no
    ``duration_ms`` field, only ISO ``start``+``end`` strings. Used
    alongside :func:`_ort_run_span` to cover both the new-producer
    field and the legacy pre-D-1 traces.ndjson shape.
    """
    return {
        "name": "encoder.ort_run",
        "trace_id": trace_id,
        "span_id": f"s-{trace_id}",
        "parent_span_id": "p-" + trace_id,
        "start": start_iso,
        "end": end_iso,
        "status": "UNSET",
        "attrs": {"encoder.name": encoder},
    }


def _build_run(parent: Path, name: str, cohort_hash: str,
               encoder_durations: dict[str, list[float]]) -> SyntheticRunDir:
    s = SyntheticRunDir(parent / name)
    s.with_manifest({"manifest_hash": cohort_hash, "git_sha": "testsha"})
    spans = []
    for encoder, durations in encoder_durations.items():
        for i, d in enumerate(durations):
            spans.append(_ort_run_span(encoder, d, trace_id=f"t-{encoder}-{i}"))
    s.with_traces(spans)
    return s


class TestPSI:
    def test_identical_distributions_score_near_zero(self):
        values = [1.0, 1.2, 1.3, 1.5, 1.8, 2.0, 2.3, 2.7, 3.1, 3.5,
                  1.1, 1.4, 1.6, 1.9, 2.1, 2.4, 2.8, 3.2, 3.6, 1.0]
        score = psi(values, values)
        assert abs(score) < 1e-3

    def test_shifted_distribution_scores_above_threshold(self):
        reference = [1.0 + 0.1 * i for i in range(200)]  # 1.0..20.9
        # Shift current up by a large amount — clear drift.
        current = [v + 15.0 for v in reference]
        score = psi(reference, current)
        assert score > PSI_DRIFT_THRESHOLD

    def test_empty_inputs_score_zero(self):
        assert psi([], []) == 0.0
        assert psi([1.0, 2.0], []) == 0.0
        assert psi([], [1.0, 2.0]) == 0.0

    def test_slight_shift_below_threshold(self):
        # A 5% shift on moderate spread should stay below 0.2.
        reference = [1.0 + 0.1 * i for i in range(200)]
        current = [v * 1.05 for v in reference]
        score = psi(reference, current)
        assert score < PSI_DRIFT_THRESHOLD


class TestProduce:
    def test_no_manifest_status(self, synthetic_run_dir):
        result = produce(synthetic_run_dir.run_dir)
        assert result["status"] == "no-cohort-hash"

    def test_no_encoder_spans_status(self, tmp_path, monkeypatch):
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(tmp_path / "data"))
        run = _build_run(tmp_path / "eval", "r1", "cohort-a", {})
        result = produce(run.run_dir)
        assert result["status"] == "no-encoder-spans"
        assert result["cohort_hash"] == "cohort-a"

    def test_first_run_does_not_write_baseline(self, tmp_path, monkeypatch):
        """Phase 6 / 6.2: the first run for a new cohort no longer
        silently captures the baseline (C-1.8.1 from the post-
        implementation critique — cold-start outliers poisoned the
        cohort permanently). Status is ``no-baseline`` and the
        ``span_distributions.json`` file stays absent until the
        operator runs ``jseval calibrate-drift-baseline``.
        """
        data_dir = tmp_path / "data"
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(data_dir))
        run = _build_run(
            tmp_path / "eval", "r1", "cohort-a",
            {"BgeM3Encoder": [1.0 + 0.1 * i for i in range(30)]},
        )
        result = produce(run.run_dir)
        assert result["status"] == "no-baseline"
        assert result["encoders"]["BgeM3Encoder"]["status"] == "no-baseline"
        assert "baseline_hint" in result
        assert "calibrate-drift-baseline" in result["baseline_hint"]
        # Baseline file MUST NOT be created automatically.
        baseline_file = data_dir / "cohort_baselines" / "cohort-a" / "span_distributions.json"
        assert not baseline_file.exists()

    def test_second_run_computes_psi(self, tmp_path, monkeypatch):
        data_dir = tmp_path / "data"
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(data_dir))
        # Seed a baseline file directly so we control the reference.
        from jseval import cohort_baselines as cb
        cb.ensure_cohort_dir(data_dir, "cohort-a")
        cb.span_distributions_path(data_dir, "cohort-a").write_text(
            json.dumps({
                "schema_version": 1,
                "cohort_hash": "cohort-a",
                "encoders": {
                    "BgeM3Encoder": {
                        "durations_ms": [1.0 + 0.1 * i for i in range(200)],
                    },
                },
            }),
            encoding="utf-8",
        )
        # Current run — identical distribution, low PSI.
        run = _build_run(
            tmp_path / "eval", "r2", "cohort-a",
            {"BgeM3Encoder": [1.0 + 0.1 * i for i in range(200)]},
        )
        result = produce(run.run_dir)
        assert result["status"] == "ok"
        entry = result["encoders"]["BgeM3Encoder"]
        assert entry["status"] == "ok"
        assert entry["psi_score"] is not None
        assert entry["drift_flagged"] is False

    def test_shifted_distribution_flagged(self, tmp_path, monkeypatch):
        data_dir = tmp_path / "data"
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(data_dir))
        from jseval import cohort_baselines as cb
        cb.ensure_cohort_dir(data_dir, "cohort-a")
        cb.span_distributions_path(data_dir, "cohort-a").write_text(
            json.dumps({
                "schema_version": 1,
                "cohort_hash": "cohort-a",
                "encoders": {
                    "BgeM3Encoder": {
                        "durations_ms": [1.0 + 0.1 * i for i in range(200)],
                    },
                },
            }),
            encoding="utf-8",
        )
        # Current is shifted +15.
        run = _build_run(
            tmp_path / "eval", "r2", "cohort-a",
            {"BgeM3Encoder": [15.0 + 1.0 + 0.1 * i for i in range(200)]},
        )
        result = produce(run.run_dir)
        entry = result["encoders"]["BgeM3Encoder"]
        assert entry["status"] == "ok"
        assert entry["drift_flagged"] is True
        assert entry["psi_score"] > PSI_DRIFT_THRESHOLD

    def test_missing_encoder_in_baseline_surfaces_no_baseline(self, tmp_path, monkeypatch):
        data_dir = tmp_path / "data"
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(data_dir))
        from jseval import cohort_baselines as cb
        cb.ensure_cohort_dir(data_dir, "cohort-a")
        cb.span_distributions_path(data_dir, "cohort-a").write_text(
            json.dumps({
                "schema_version": 1,
                "cohort_hash": "cohort-a",
                "encoders": {
                    "OnlyThis": {
                        "durations_ms": [1.0] * 50,
                    },
                },
            }),
            encoding="utf-8",
        )
        run = _build_run(
            tmp_path / "eval", "r2", "cohort-a",
            {
                "OnlyThis": [1.0] * 30,
                "NewEncoder": [5.0] * 30,
            },
        )
        result = produce(run.run_dir)
        assert result["encoders"]["OnlyThis"]["status"] == "ok"
        assert result["encoders"]["NewEncoder"]["status"] == "no-baseline"


class TestDurationMsExtractionContract:
    """Tempdoc 400 §23.8 D-1 regression coverage.

    The original ``_extract_encoder_durations`` read ``span["duration_ms"]``
    unconditionally, but :class:`NdjsonSpanExporter` (pre-§23.8) emitted
    only ISO ``start``+``end`` fields — every real traces.ndjson produced
    ``status=no-encoder-spans``. Fixture-based unit tests injected
    ``duration_ms`` directly, so the drift never surfaced in tests.

    These tests pin both shapes (new producer field + legacy start/end
    fallback) so neither side of the contract can regress silently.
    """

    def test_reads_explicit_duration_ms_field(self):
        """Post-§23.8 producer emits duration_ms directly."""
        span = _ort_run_span("embed", duration_ms=2.5)
        assert _span_duration_ms(span) == 2.5

    def test_falls_back_to_iso_start_end_when_duration_absent(self):
        """Legacy traces.ndjson (pre-§23.8) has only start/end."""
        span = _ort_run_span_exporter_shape(
            "ner",
            start_iso="2026-04-22T11:54:10.046Z",
            end_iso="2026-04-22T11:54:10.049Z",
        )
        assert "duration_ms" not in span
        # 49ms - 46ms = 3ms
        assert _span_duration_ms(span) == pytest.approx(3.0, abs=0.01)

    def test_prefers_explicit_duration_over_start_end(self):
        """If both fields present, duration_ms wins (nanosecond precision
        at the producer > millisecond re-parse at the consumer)."""
        span = _ort_run_span_exporter_shape(
            "ner",
            start_iso="2026-04-22T11:54:10.046Z",
            end_iso="2026-04-22T11:54:10.049Z",
        )
        span["duration_ms"] = 2.734  # sub-ms precision the ISO form can't express
        assert _span_duration_ms(span) == pytest.approx(2.734, abs=1e-9)

    def test_missing_both_returns_none(self):
        span = {"name": "encoder.ort_run", "attrs": {"encoder.name": "ner"}}
        assert _span_duration_ms(span) is None

    def test_malformed_iso_returns_none(self):
        span = {"start": "not-a-date", "end": "also-not"}
        assert _span_duration_ms(span) is None

    def test_accepts_plus_00_00_offset_form(self):
        """Python datetime.fromisoformat on pre-3.11 doesn't parse ``Z``;
        NdjsonSpanExporter emits ``+00:00``-style via ISO_OFFSET_DATE_TIME
        formatter when the offset isn't collapsed. Handle both."""
        span = {"start": "2026-04-22T11:54:10.000+00:00",
                "end": "2026-04-22T11:54:10.010+00:00"}
        assert _span_duration_ms(span) == pytest.approx(10.0, abs=0.01)

    def test_negative_clamp_on_same_ms_start_end(self):
        """ms-precision ISO start/end that landed in the same ms must
        not produce a negative duration if the fromisoformat round-trip
        introduces jitter. Expected: clamped to 0.0."""
        span = {"start": "2026-04-22T11:54:10.046Z",
                "end": "2026-04-22T11:54:10.046Z"}
        v = _span_duration_ms(span)
        assert v == 0.0

    def test_extract_reads_realistic_exporter_ndjson(self, tmp_path):
        """End-to-end: construct a traces.ndjson byte-for-byte matching
        what :class:`NdjsonSpanExporter` writes (no duration_ms in the
        historical case), run the real :func:`_extract_encoder_durations`,
        assert it produces the expected distribution.
        """
        traces_path = tmp_path / "traces.ndjson"
        lines = []
        # 3 encoder.ort_run spans: 10ms, 20ms, 30ms (ISO ms-precision).
        for i, dur_ms in enumerate((10, 20, 30)):
            span = _ort_run_span_exporter_shape(
                "embed",
                start_iso=f"2026-04-22T11:54:10.{0:03d}Z",
                end_iso=f"2026-04-22T11:54:10.{dur_ms:03d}Z",
                trace_id=f"t{i}",
            )
            lines.append(json.dumps(span))
        # Plus one non-encoder span to confirm filtering still works.
        lines.append(json.dumps({
            "name": "indexing.batch",
            "start": "2026-04-22T11:54:10.000Z",
            "end": "2026-04-22T11:54:10.500Z",
            "attrs": {"batch.polled": "1"},
        }))
        traces_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

        result = _extract_encoder_durations(traces_path)
        assert set(result.keys()) == {"embed"}
        # Sorted for deterministic assertion (dict→list ordering not guaranteed).
        assert sorted(result["embed"]) == [10.0, 20.0, 30.0]


class TestProjectionRegistration:
    def test_module_export(self):
        assert PROJECTION.name == "encoder_drift"
        assert PROJECTION.schema_version == 1

    def test_bootstrap_registers(self, tmp_path):
        from jseval.projections import registry, run_all_discovered
        from jseval.projections.base import reset_registry_for_tests

        reset_registry_for_tests()
        rd = SyntheticRunDir(tmp_path / "run")
        run_all_discovered(rd.run_dir)
        assert "encoder_drift" in registry()
        reset_registry_for_tests()
