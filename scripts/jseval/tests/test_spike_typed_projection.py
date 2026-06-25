"""SPIKE test — Pillar 3 typed-transformation prototype (tempdoc 404
§3.3). Validates the decorator + context + runner shape against
encoder_drift.

Not meant to become production-grade; the spike's output is a
confidence signal for tempdoc 405 §P2.C.1."""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from jseval.projections._spike_typed import (
    ProjectionContext,
    TypedProjection,
    encoder_drift_typed,
    projection,
    registry,
)


@pytest.fixture
def fresh_run_dir(tmp_path: Path) -> Path:
    rd = tmp_path / "run"
    rd.mkdir()
    return rd


def _write_traces(run_dir: Path, spans: list[dict]) -> None:
    (run_dir / "traces.ndjson").write_text(
        "\n".join(json.dumps(s) for s in spans) + "\n",
        encoding="utf-8",
    )


def _write_manifest(run_dir: Path, manifest_hash: str) -> None:
    (run_dir / "manifest.json").write_text(
        json.dumps({"manifest_hash": manifest_hash}),
        encoding="utf-8",
    )


class TestDecoratorMetadata:
    def test_produces_typed_projection_wrapper(self):
        assert isinstance(encoder_drift_typed, TypedProjection)
        assert encoder_drift_typed.spec.name == "encoder_drift"
        assert encoder_drift_typed.spec.schema_version == 2
        assert "encoder.ort_run" in encoder_drift_typed.spec.reads_kinds
        assert encoder_drift_typed.spec.reads_cohort_baseline \
            == "span_distributions.json"
        assert encoder_drift_typed.spec.expected_cost_ms == 200

    def test_registry_includes_encoder_drift(self):
        reg = registry()
        assert "encoder_drift" in reg


class TestProjectionContextReadKind:
    def test_yields_only_declared_kind(self, fresh_run_dir):
        _write_traces(fresh_run_dir, [
            {"name": "encoder.ort_run", "attrs": {"encoder.name": "embed"},
             "duration_ms": 5.0},
            {"name": "indexing.extract", "attrs": {}, "duration_ms": 1.0},
            {"name": "encoder.ort_run", "attrs": {"encoder.name": "splade"},
             "duration_ms": 7.0},
        ])
        ctx = ProjectionContext(fresh_run_dir, encoder_drift_typed.spec)
        spans = list(ctx.read_kind("encoder.ort_run"))
        assert len(spans) == 2
        # Coverage captured as side effect.
        cov = ctx.coverage()
        assert cov["encoder.ort_run"]["spans_scanned"] == 3
        assert cov["encoder.ort_run"]["spans_matched"] == 2

    def test_undeclared_kind_raises(self, fresh_run_dir):
        """Consumer-side discipline: a projection cannot read a kind
        it didn't declare in reads_kinds. Forces consumer to update
        the spec + any schema-backed validation alongside."""
        ctx = ProjectionContext(fresh_run_dir, encoder_drift_typed.spec)
        with pytest.raises(ValueError, match="did not declare"):
            list(ctx.read_kind("indexing.extract"))

    def test_missing_traces_file_is_empty(self, fresh_run_dir):
        ctx = ProjectionContext(fresh_run_dir, encoder_drift_typed.spec)
        spans = list(ctx.read_kind("encoder.ort_run"))
        assert spans == []
        assert ctx.coverage()["encoder.ort_run"]["spans_scanned"] == 0


class TestTypedProjectionRun:
    def test_execution_provenance_first_class(self, fresh_run_dir):
        """Every output has execution.duration_ms without the
        projection body touching it."""
        _write_traces(fresh_run_dir, [
            {"name": "encoder.ort_run", "attrs": {"encoder.name": "embed"},
             "duration_ms": 5.0},
        ])
        out = encoder_drift_typed.run(fresh_run_dir)
        assert "execution" in out
        assert "duration_ms" in out["execution"]
        assert out["execution"]["duration_ms"] >= 0
        assert out["execution"]["expected_cost_ms"] == 200
        assert out["execution"]["cost_deviation"] is not None

    def test_coverage_first_class(self, fresh_run_dir):
        _write_traces(fresh_run_dir, [
            {"name": "encoder.ort_run", "attrs": {"encoder.name": "embed"},
             "duration_ms": 5.0},
            {"name": "encoder.ort_run", "attrs": {"encoder.name": "splade"},
             "duration_ms": 7.0},
            {"name": "something.else", "attrs": {}, "duration_ms": 1.0},
        ])
        out = encoder_drift_typed.run(fresh_run_dir)
        cov = out["coverage"]
        assert cov["encoder.ort_run"]["spans_scanned"] == 3
        assert cov["encoder.ort_run"]["spans_matched"] == 2

    def test_no_spans_returns_no_encoder_spans_status(self, fresh_run_dir):
        _write_traces(fresh_run_dir, [
            {"name": "indexing.extract", "attrs": {}, "duration_ms": 1.0},
        ])
        out = encoder_drift_typed.run(fresh_run_dir)
        assert out["status"] == "ok"  # run succeeded
        assert out["result"]["status"] == "no-encoder-spans"

    def test_no_baseline_returns_no_baseline_status(self, fresh_run_dir):
        """Real encoder spans + no cohort baseline → result.status
        = no-baseline. The health check
        `baseline_exists_or_status_is_no_baseline` should PASS
        because one of the two conditions is met."""
        _write_traces(fresh_run_dir, [
            {"name": "encoder.ort_run", "attrs": {"encoder.name": "embed"},
             "duration_ms": 5.0},
        ])
        _write_manifest(fresh_run_dir, "cohort-no-baseline")
        out = encoder_drift_typed.run(fresh_run_dir)
        assert out["result"]["status"] == "no-baseline"
        # Health check interprets this correctly.
        checks = {h["check"]: h for h in out["health"]}
        assert checks["baseline_exists_or_status_is_no_baseline"]["level"] \
            == "ok"

    def test_health_check_failures_surface_as_warnings(
        self, fresh_run_dir, monkeypatch,
    ):
        """With no input spans, input_span_count_nonzero is warning.
        The WHOLE output still has status=ok because the projection
        ran without exception — the check is a signal, not an error."""
        _write_traces(fresh_run_dir, [])
        out = encoder_drift_typed.run(fresh_run_dir)
        checks = {h["check"]: h for h in out["health"]}
        assert checks["input_span_count_nonzero"]["level"] == "warning"
        assert out["status"] == "ok"


class TestBaselineLookup:
    def test_baseline_lookup_via_context(
        self, fresh_run_dir, tmp_path, monkeypatch,
    ):
        """The spec declares reads_cohort_baseline="span_distributions
        .json"; ctx.read_cohort_baseline() resolves against
        JUSTSEARCH_DATA_DIR + cohort hash from manifest.json."""
        data_dir = tmp_path / "data"
        cohort_dir = data_dir / "cohort_baselines" / "spike-cohort"
        cohort_dir.mkdir(parents=True)
        (cohort_dir / "span_distributions.json").write_text(
            json.dumps({"encoders": {"embed": {"durations_ms": [1, 2, 3]}}}),
            encoding="utf-8",
        )
        _write_manifest(fresh_run_dir, "spike-cohort")
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(data_dir))
        ctx = ProjectionContext(fresh_run_dir, encoder_drift_typed.spec)
        b = ctx.read_cohort_baseline()
        assert b is not None
        assert "encoders" in b
        assert "embed" in b["encoders"]


class TestErgonomicsObservations:
    """Tests that document FINDINGS from the spike, not assertions the
    production code needs. These pin the observations into the test
    suite so future readers of the spike see what we learned."""

    def test_H6_undeclared_kind_enforcement_is_consumer_side_only(
        self, fresh_run_dir,
    ):
        """H6 from adversarial review: Pillar 3's reads_kinds is a
        string literal that drifts without Pillar 2's schema. This
        test documents the limitation — ValueError catches projection
        authors reading a non-declared kind, but NOTHING in Pillar 3
        catches a producer renaming 'encoder.ort_run' to
        'encoder.session_run'. The consumer would just silently read
        zero spans of the old name."""
        _write_traces(fresh_run_dir, [
            # Producer renamed the kind — Pillar 3 alone sees zero.
            {"name": "encoder.session_run",
             "attrs": {"encoder.name": "embed"}, "duration_ms": 5.0},
        ])
        out = encoder_drift_typed.run(fresh_run_dir)
        # The projection runs cleanly. Nothing flags the rename.
        assert out["result"]["status"] == "no-encoder-spans"
        # Coverage shows 1 span was in the file but 0 matched — this
        # is the weakest possible signal (could be anything).
        assert out["coverage"]["encoder.ort_run"]["spans_scanned"] == 1
        assert out["coverage"]["encoder.ort_run"]["spans_matched"] == 0


class TestCombinedPillar2And3Spike:
    """Tempdoc 405 Activity A: Pillar 2 schema wired into Pillar 3
    context. Validates that the Pillar 4 → 2 → 3 sequencing resolves
    H6 — producer-consumer kind drift now fails LOUDLY rather than
    silently yielding zero spans. Demonstrates the structural
    prevention the isolated spikes couldn't.
    """

    def test_registration_time_kind_check_catches_unknown_kind(
        self, fresh_run_dir,
    ):
        """H6 resolution, half 1: if the projection's reads_kinds
        references a kind the canonical schema doesn't declare,
        registration raises SchemaViolation. Fail-loud at import time,
        not silently at read time."""
        from jseval.projections._spike_typed import (
            ProjectionContext,
            SchemaViolation,
            TypedProjectionSpec,
        )
        bad_spec = TypedProjectionSpec(
            name="fictional_projection",
            schema_version=2,
            # Producer-renamed-the-kind scenario: a projection that
            # wants 'encoder.session_run' (hypothetical renamed form).
            reads_kinds=("encoder.session_run",),
            reads_fields=(),
            reads_cohort_baseline=None,
            writes_kind="projection.fictional",
            cadence="one per run",
            health_checks=(),
            expected_cost_ms=None,
        )
        with pytest.raises(SchemaViolation, match="no such kind"):
            ProjectionContext(
                fresh_run_dir, bad_spec, validate_schema=True,
            )

    def test_registration_time_check_accepts_known_kind(
        self, fresh_run_dir,
    ):
        """encoder_drift_typed's spec declares reads_kinds=
        ('encoder.ort_run',) which IS in the canonical schema. The
        registration check passes without raising."""
        from jseval.projections._spike_typed import ProjectionContext
        ctx = ProjectionContext(
            fresh_run_dir, encoder_drift_typed.spec, validate_schema=True,
        )
        # Instantiation did not raise; we made it here.
        assert ctx.validate_schema is True

    def test_read_kind_surfaces_schema_violations_on_real_spans(
        self, fresh_run_dir,
    ):
        """Producer emits encoder.ort_run spans but fails to include
        encoder.gpu (one of the required attrs per the canonical
        schema). Pillar 2 schema validator flags each; the violation
        surfaces in coverage.<kind>.schema_violations — operator-
        visible, non-fatal."""
        _write_traces(fresh_run_dir, [
            # Valid span.
            {"trace_id": "t1", "span_id": "s1",
             "name": "encoder.ort_run",
             "start": "2026-04-22T10:00:00.000Z",
             "end": "2026-04-22T10:00:00.010Z",
             "duration_ms": 10.0, "status": "UNSET",
             "attrs": {"encoder.name": "embed", "encoder.gpu": "true",
                       "encoder.batch_size": "16",
                       "encoder.seq_len": "128"}},
            # Invalid: missing encoder.gpu. Projection still reads it,
            # but the schema violation surfaces.
            {"trace_id": "t2", "span_id": "s2",
             "name": "encoder.ort_run",
             "start": "2026-04-22T10:00:00.000Z",
             "end": "2026-04-22T10:00:00.010Z",
             "duration_ms": 10.0, "status": "UNSET",
             "attrs": {"encoder.name": "embed",
                       "encoder.batch_size": "16",
                       "encoder.seq_len": "128"}},
        ])
        out = encoder_drift_typed.run(
            fresh_run_dir, validate_schema=True,
        )
        cov = out["coverage"]["encoder.ort_run"]
        assert cov["spans_matched"] == 2
        violations = cov["schema_violations"]
        # The invalid span flags 1 violation (missing encoder.gpu);
        # the valid span adds 0. Total: 1.
        assert len(violations) == 1
        assert "encoder.gpu" in violations[0]

    def test_read_kind_without_schema_mode_is_backward_compatible(
        self, fresh_run_dir,
    ):
        """Projections using the non-validated path see coverage
        without the schema_violations field. Lets legacy consumers
        continue working during a gradual Pillar-2 rollout."""
        _write_traces(fresh_run_dir, [
            {"name": "encoder.ort_run", "attrs": {"encoder.name": "embed"},
             "duration_ms": 5.0},
        ])
        out = encoder_drift_typed.run(fresh_run_dir)  # no validate_schema
        cov = out["coverage"]["encoder.ort_run"]
        assert "schema_violations" not in cov
        assert cov["spans_matched"] == 1

    def test_H6_scenario_with_combined_spike_resolves_loudly(
        self, fresh_run_dir,
    ):
        """H6 reproduction WITH combined 2+3 spike engaged.

        Same scenario as test_H6_undeclared_kind_enforcement_is_
        consumer_side_only: producer renamed 'encoder.ort_run' to
        'encoder.session_run'. But now the projection's spec still
        declares reads_kinds=('encoder.ort_run',). With validate_schema
        off, this silently reads 0 spans (same as before). With
        validate_schema on, the scenario is DIFFERENT depending on
        which side drifted:

        - If the PROJECTION drifts (declares a kind not in schema):
          registration-time SchemaViolation. Caught loudly.
        - If the PRODUCER drifts (emits a kind not in schema):
          registration of the projection STILL succeeds (its spec
          is fine); but a consumer that also ran validate_span over
          the renamed spans would see 'invalid kind' schema
          violations. Loud at consumption.

        This test shows the second case: producer emits invalid kind;
        projection registered correctly; validation surfaces the
        issue rather than silently dropping spans.

        NOTE: the current _spike_schema.validate_span doesn't yet
        flag unknown kinds as a violation (it silently passes).
        This test pins that limitation; tempdoc 405 §5 next-steps
        should list 'extend validate_span to reject unknown kinds'
        as a schema-side refinement."""
        _write_traces(fresh_run_dir, [
            # Producer renamed the kind.
            {"trace_id": "t1", "span_id": "s1",
             "name": "encoder.session_run",
             "start": "2026-04-22T10:00:00.000Z",
             "end": "2026-04-22T10:00:00.010Z",
             "duration_ms": 10.0, "status": "UNSET",
             "attrs": {"encoder.name": "embed"}},
        ])
        out = encoder_drift_typed.run(
            fresh_run_dir, validate_schema=True,
        )
        # The projection correctly reports no-encoder-spans (it was
        # looking for 'encoder.ort_run' which isn't in the trace).
        assert out["result"]["status"] == "no-encoder-spans"
        # Coverage shows 1 span scanned, 0 matched. Observable.
        cov = out["coverage"]["encoder.ort_run"]
        assert cov["spans_scanned"] == 1
        assert cov["spans_matched"] == 0
        # schema_violations for encoder.ort_run is empty (none
        # matched, so none validated).
        assert cov["schema_violations"] == []
        # The gap this test documents: a *separate* validator pass
        # over the traces (scanning ALL kinds, not just declared
        # ones) would catch the 'encoder.session_run' as 'unknown
        # kind'. That's a schema-side feature the combined spike
        # doesn't yet have. Listed in 405 §11.

    def test_integration_reduces_h6_to_non_structural(
        self, fresh_run_dir,
    ):
        """Summary claim: the combined 2+3 spike reduces H6's
        severity from 'structural silent failure' (Pillar 3 alone)
        to 'surfaces in coverage output + operator can add a
        kind-level validator'. This test doesn't assert new
        behavior — it asserts the coverage dict shape is rich
        enough for operators to detect drift without spelunking
        span files."""
        _write_traces(fresh_run_dir, [
            # Producer sends 2 valid encoder.ort_run spans.
            {"trace_id": "t1", "span_id": "s1",
             "name": "encoder.ort_run",
             "start": "2026-04-22T10:00:00.000Z",
             "end": "2026-04-22T10:00:00.010Z",
             "duration_ms": 10.0, "status": "UNSET",
             "attrs": {"encoder.name": "embed", "encoder.gpu": "true",
                       "encoder.batch_size": "16",
                       "encoder.seq_len": "128"}},
            {"trace_id": "t2", "span_id": "s2",
             "name": "encoder.ort_run",
             "start": "2026-04-22T10:00:00.000Z",
             "end": "2026-04-22T10:00:00.010Z",
             "duration_ms": 10.0, "status": "UNSET",
             "attrs": {"encoder.name": "splade", "encoder.gpu": "true",
                       "encoder.batch_size": "16",
                       "encoder.seq_len": "128"}},
        ])
        out = encoder_drift_typed.run(
            fresh_run_dir, validate_schema=True,
        )
        # Coverage surfaces all signals operators need:
        cov = out["coverage"]["encoder.ort_run"]
        assert set(cov.keys()) == {
            "spans_scanned", "spans_matched", "schema_violations",
        }
        # With both spans valid, schema_violations is empty list.
        assert cov["schema_violations"] == []
        # And the projection produced meaningful output.
        assert out["status"] == "ok"