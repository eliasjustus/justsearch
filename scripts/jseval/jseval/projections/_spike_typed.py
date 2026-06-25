"""SPIKE — Pillar 3 (typed projection transformations) prototype.

Tempdoc 404 §3.3 proposes projections become typed transformations with
declared inputs/outputs/health checks and first-class execution
provenance. This module is the bounded half-day spike validating
whether the pattern is ergonomic. **NOT production code.** Reference
only. Delete if the spike's conclusions argue against adoption.

The spike exercise: retrofit `encoder_drift` (the most complex
projection) into the typed-transformation shape. If it fits cleanly
and generalizes to the other 6 projections, Pillar 3's 6/10 stays.
If it fights the existing base or produces awkward API, confidence
drops.

Not attempted in the spike: schema-backed validation (depends on
Pillar 2, scheduled later in §9); baseline lineage tracking (depends
on Pillar 1).
"""

from __future__ import annotations

import dataclasses
import time
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any, Protocol

# ---------------------------------------------------------------------------
# Decorator + metadata types
# ---------------------------------------------------------------------------


@dataclasses.dataclass(frozen=True)
class TypedProjectionSpec:
    """Declarative metadata for a projection.

    All fields are static: known at registration time, used by the
    registry to validate contracts + emit execution provenance.
    """

    name: str
    schema_version: int
    reads_kinds: tuple[str, ...]
    reads_fields: tuple[str, ...]
    reads_cohort_baseline: str | None
    writes_kind: str
    cadence: str
    health_checks: tuple[str, ...]
    expected_cost_ms: int | None


def projection(
    *,
    name: str,
    schema_version: int,
    reads_kinds: tuple[str, ...] = (),
    reads_fields: tuple[str, ...] = (),
    reads_cohort_baseline: str | None = None,
    writes_kind: str,
    cadence: str = "one per run",
    health_checks: tuple[str, ...] = (),
    expected_cost_ms: int | None = None,
) -> Callable[[Callable[..., dict]], "TypedProjection"]:
    """Declare a typed projection. The decorated function must have
    signature ``produce(ctx: ProjectionContext) -> dict``; the
    decorator wraps it into a :class:`TypedProjection` with metadata.
    """
    spec = TypedProjectionSpec(
        name=name, schema_version=schema_version,
        reads_kinds=reads_kinds, reads_fields=reads_fields,
        reads_cohort_baseline=reads_cohort_baseline,
        writes_kind=writes_kind, cadence=cadence,
        health_checks=health_checks, expected_cost_ms=expected_cost_ms,
    )

    def _wrap(fn: Callable[..., dict]) -> TypedProjection:
        return TypedProjection(spec=spec, produce_fn=fn)
    return _wrap


# ---------------------------------------------------------------------------
# ProjectionContext — typed accessor
# ---------------------------------------------------------------------------


class SchemaViolation(Exception):
    """Raised at projection registration when the spec declares a kind
    or field not present in the Pillar-2 canonical schema. Fail-loud
    at module import — the projection cannot be registered against a
    schema it disagrees with.

    Tempdoc 405 §3.1 H6 proved Pillar 3 alone silently fails on
    producer-consumer kind drift; the combined 2+3 spike wires schema
    validation into registration + per-span reads so drift fails
    loudly instead.
    """


class ProjectionContext:
    """Typed accessor the projection uses to read its inputs.

    **Combined Pillar 2+3 spike (Activity A in tempdoc 405).** When
    constructed with ``validate_schema=True``, every declared
    ``reads_kind`` is cross-checked against the Pillar-2 canonical
    schema at registration time; every span read is validated against
    the schema's required/identity attrs on the fly. Drift between
    the spec's declarations and the schema's definitions surfaces as
    a loud :class:`SchemaViolation` at registration; drift between a
    live producer and the schema surfaces as ``coverage
    .schema_violations`` on the output (non-fatal so projections stay
    read-robust, but operator-visible).

    Full design would also:
    1. Iterate artifact shards from the Pillar-1 manifest (not just
       the mirrored file — answers D-3 structurally). Not in this
       spike.
    """

    def __init__(
        self,
        run_dir: Path,
        spec: TypedProjectionSpec,
        *,
        validate_schema: bool = False,
    ):
        self.run_dir = run_dir
        self.spec = spec
        self.validate_schema = validate_schema
        self._kind_counts: dict[str, int] = {}
        self._kind_scanned: dict[str, int] = {}
        # Activity A: per-kind schema violations observed during reads.
        # Key: kind name; value: list of violation message strings.
        self._schema_violations: dict[str, list[str]] = {}

        if validate_schema:
            self._validate_spec_against_schema()

    def _validate_spec_against_schema(self) -> None:
        """Registration-time check: every kind in spec.reads_kinds
        must exist in the canonical schema. Kind name drift (H6)
        fails loudly here, not silently at read time.
        """
        from . import _spike_schema as _schema
        declared = set(_schema.CANONICAL_SCHEMA["span_kinds"].keys())
        for kind in self.spec.reads_kinds:
            if kind not in declared:
                raise SchemaViolation(
                    f"projection {self.spec.name!r} declares "
                    f"reads_kinds={kind!r} but canonical schema has no "
                    f"such kind (declared kinds: {sorted(declared)})",
                )

    def read_kind(self, kind: str) -> Iterator[dict]:
        """Yield every span whose ``name`` matches ``kind``. Records
        coverage metadata as a side effect. When ``validate_schema``
        is on, each yielded span is also checked against the
        schema's required/identity attrs; violations are collected
        in ``_schema_violations`` and surfaced in ``coverage()``.
        """
        if kind not in self.spec.reads_kinds:
            raise ValueError(
                f"projection {self.spec.name!r} did not declare "
                f"reads_kinds={kind!r}; declared: {self.spec.reads_kinds}",
            )
        traces_path = self.run_dir / "traces.ndjson"
        if not traces_path.is_file():
            self._kind_scanned[kind] = 0
            self._kind_counts[kind] = 0
            return

        if self.validate_schema:
            from . import _spike_schema as _schema
            validate_fn = _schema.validate_span
        else:
            validate_fn = None

        import json as _json
        violations_for_kind: list[str] = []
        scanned = 0
        matched = 0
        with traces_path.open("r", encoding="utf-8") as f:
            for line in f:
                if not line.strip():
                    continue
                scanned += 1
                try:
                    span = _json.loads(line)
                except Exception:
                    continue
                if span.get("name") == kind:
                    matched += 1
                    if validate_fn is not None:
                        errs = validate_fn(span)
                        violations_for_kind.extend(errs)
                    yield span
        self._kind_scanned[kind] = scanned
        self._kind_counts[kind] = matched
        if violations_for_kind:
            self._schema_violations[kind] = violations_for_kind

    def read_cohort_baseline(self) -> dict | None:
        """Load the cohort baseline declared by the spec (stub; in full
        design would traverse the Pillar-1 manifest)."""
        if not self.spec.reads_cohort_baseline:
            return None
        import json as _json
        import os
        data_dir = os.environ.get("JUSTSEARCH_DATA_DIR")
        if not data_dir:
            return None
        # Read manifest_hash from run_dir
        manifest_path = self.run_dir / "manifest.json"
        if not manifest_path.is_file():
            return None
        try:
            manifest = _json.loads(manifest_path.read_text(encoding="utf-8"))
        except Exception:
            return None
        cohort = manifest.get("manifest_hash")
        if not cohort:
            return None
        baseline_path = (
            Path(data_dir) / "cohort_baselines" / cohort
            / self.spec.reads_cohort_baseline
        )
        if not baseline_path.is_file():
            return None
        try:
            return _json.loads(baseline_path.read_text(encoding="utf-8"))
        except Exception:
            return None

    def coverage(self) -> dict[str, dict]:
        """Coverage summary — kind → {scanned, matched,
        schema_violations}. Available after the projection's
        iteration completes. ``schema_violations`` is present only
        when ``validate_schema=True`` was passed to __init__."""
        out: dict[str, dict] = {}
        for kind in self.spec.reads_kinds:
            entry: dict = {
                "spans_scanned": self._kind_scanned.get(kind, 0),
                "spans_matched": self._kind_counts.get(kind, 0),
            }
            if self.validate_schema:
                entry["schema_violations"] = self._schema_violations.get(
                    kind, [],
                )
            out[kind] = entry
        return out


# ---------------------------------------------------------------------------
# TypedProjection + execution
# ---------------------------------------------------------------------------


class TypedProjection:
    """Runnable typed projection. Records execution provenance around
    the produce call."""

    def __init__(
        self,
        spec: TypedProjectionSpec,
        produce_fn: Callable[[ProjectionContext], dict],
    ):
        self.spec = spec
        self.produce_fn = produce_fn

    def run(self, run_dir: Path, *, validate_schema: bool = False) -> dict:
        """Execute the projection. When ``validate_schema=True`` the
        ProjectionContext checks reads_kinds against the canonical
        schema at init (SchemaViolation on drift) and validates each
        yielded span against required/identity attrs, surfacing any
        drift in ``coverage.<kind>.schema_violations``.
        """
        ctx = ProjectionContext(
            run_dir, self.spec, validate_schema=validate_schema,
        )
        t0 = time.perf_counter()
        try:
            result = self.produce_fn(ctx)
            status = "ok"
            error: str | None = None
        except Exception as e:  # noqa: BLE001 - spike catches to record
            result = {}
            status = "error"
            error = f"{type(e).__name__}: {e}"
        duration_ms = (time.perf_counter() - t0) * 1000

        # Health-check evaluation. In full design each check is a
        # registered function; spike inlines two of encoder_drift's.
        health = _evaluate_health_checks(self.spec, ctx, result)

        output = {
            "projection_name": self.spec.name,
            "schema_version": self.spec.schema_version,
            "status": status,
            "error": error,
            "execution": {
                "duration_ms": round(duration_ms, 3),
                "expected_cost_ms": self.spec.expected_cost_ms,
                "cost_deviation": (
                    round(duration_ms / self.spec.expected_cost_ms, 2)
                    if self.spec.expected_cost_ms else None
                ),
            },
            "coverage": ctx.coverage(),
            "health": health,
            "result": result,
        }
        return output


def _evaluate_health_checks(
    spec: TypedProjectionSpec,
    ctx: ProjectionContext,
    result: dict,
) -> list[dict]:
    """Very small health-check runner; the spike implements just two
    check names. Full design would have a registry keyed by name.
    """
    out: list[dict] = []
    for check in spec.health_checks:
        if check == "input_span_count_nonzero":
            total = sum(c["spans_matched"] for c in ctx.coverage().values())
            out.append({
                "check": check,
                "level": "ok" if total > 0 else "warning",
                "observed": {"total_matched_spans": total},
            })
        elif check == "baseline_exists_or_status_is_no_baseline":
            has_baseline = ctx.read_cohort_baseline() is not None
            is_no_baseline = result.get("status") == "no-baseline"
            passes = has_baseline or is_no_baseline
            out.append({
                "check": check,
                "level": "ok" if passes else "warning",
                "observed": {
                    "baseline_exists": has_baseline,
                    "status_is_no_baseline": is_no_baseline,
                },
            })
        else:
            out.append({
                "check": check,
                "level": "skipped",
                "reason": "check not registered in spike",
            })
    return out


# ---------------------------------------------------------------------------
# Spike: encoder_drift as typed projection
# ---------------------------------------------------------------------------
#
# Validation target: does the @projection decorator + ProjectionContext
# abstraction fit cleanly, or does it fight the existing logic?
#
# Notes from the conversion:
# - `reads_kinds=("encoder.ort_run",)` replaces the hand-written span-
#   name filter inside _extract_encoder_durations. Cleaner.
# - `reads_fields=("duration_ms", "attrs.encoder.name")` is currently
#   documentation-only (no validator). Pillar 2 schema would enforce.
# - `reads_cohort_baseline="span_distributions.json"` replaces the
#   bespoke _resolve_data_dir + _load_baseline glue in encoder_drift.py.
#   Baseline lookup becomes a ctx method.
# - Coverage ({spans_scanned, spans_matched}) is FREE — no explicit
#   wiring in the projection body. Directly resolves §23.9.4 #5
#   (per-projection latency) + H3 (biased baseline surface).
# - Execution provenance (duration_ms, cost_deviation) is FREE.
# - Health checks declared in spec; implementation in the shared runner.


@projection(
    name="encoder_drift",
    schema_version=2,  # v2 schema includes typed execution provenance
    reads_kinds=("encoder.ort_run",),
    reads_fields=("duration_ms", "attrs.encoder.name"),
    reads_cohort_baseline="span_distributions.json",
    writes_kind="projection.encoder_drift",
    cadence="one per run",
    health_checks=(
        "input_span_count_nonzero",
        "baseline_exists_or_status_is_no_baseline",
    ),
    expected_cost_ms=200,
)
def encoder_drift_typed(ctx: ProjectionContext) -> dict:
    """Typed variant of encoder_drift. Same PSI computation; the
    typed-transformation shape takes over input routing + coverage."""
    from .encoder_drift import (
        DEFAULT_BINS,
        PSI_DRIFT_THRESHOLD,
        psi as _psi,
    )

    # Extract durations per encoder from the declared kind's spans.
    durations: dict[str, list[float]] = {}
    for span in ctx.read_kind("encoder.ort_run"):
        attrs = span.get("attrs") or {}
        encoder = attrs.get("encoder.name")
        if not encoder:
            continue
        d = span.get("duration_ms")
        if d is None:
            continue
        try:
            durations.setdefault(encoder, []).append(float(d))
        except (TypeError, ValueError):
            continue

    if not durations:
        return {"status": "no-encoder-spans"}

    baseline = ctx.read_cohort_baseline()
    if baseline is None:
        return {
            "status": "no-baseline",
            "encoders": {enc: {"current_n": len(vs)} for enc, vs in durations.items()},
        }

    encoders = baseline.get("encoders") or {}
    result_encoders = {}
    for enc, current in durations.items():
        ref = (encoders.get(enc) or {}).get("durations_ms") or []
        if not ref:
            result_encoders[enc] = {
                "status": "no-baseline", "current_n": len(current),
            }
            continue
        score = _psi(ref, current, bins=DEFAULT_BINS)
        result_encoders[enc] = {
            "status": "ok",
            "psi_score": score,
            "drift_flagged": score > PSI_DRIFT_THRESHOLD,
            "current_n": len(current),
            "reference_n": len(ref),
        }
    return {
        "status": "ok",
        "encoders": result_encoders,
        "threshold": PSI_DRIFT_THRESHOLD,
    }


# ---------------------------------------------------------------------------
# Registry — minimal stub
# ---------------------------------------------------------------------------


_REGISTRY: dict[str, TypedProjection] = {
    encoder_drift_typed.spec.name: encoder_drift_typed,
}


def registry() -> dict[str, TypedProjection]:
    return dict(_REGISTRY)


# ---------------------------------------------------------------------------
# Spike retrospective (notes from the conversion exercise)
# ---------------------------------------------------------------------------
#
# WHAT WENT WELL:
# - Decorator-driven declarative metadata is idiomatic Python; no
#   cognitive friction. All 7 projections could take this shape.
# - ProjectionContext.read_kind() neatly replaces the hand-written
#   filter in _extract_encoder_durations. Coverage metadata is a
#   pure side effect of iteration — no projection-side wiring.
# - Execution provenance (duration_ms, cost_deviation) is fully
#   transparent. Bounded ~40 LOC in the runner; every projection
#   gets it for free.
# - baseline lookup via ctx.read_cohort_baseline() is simpler than
#   the existing _resolve_data_dir + _load_baseline chain.
#
# WHAT DIDN'T:
# - **H6 confirmed.** Without a Pillar-2 schema, `reads_kinds=("encoder
#   .ort_run",)` is a string literal. If the Java producer renames the
#   kind, the projection's read_kind() yields zero spans — silently,
#   exactly the class of bug Pillar 3 was supposed to prevent. The
#   ValueError guard in read_kind() catches CONSUMER-side typos (it
#   raises if the projection reads a kind it didn't declare), but NOT
#   the producer-consumer shape drift. So H6 is right: Pillar 3's
#   MVP enforcement value is bounded until Pillar 2 ships.
# - **H3 partially confirmed.** Health check
#   `input_span_count_nonzero` is trivial (was anything read?). But
#   `input_span_count_within_10pct_of_cohort_baseline` requires the
#   baseline to be reliable — if the baseline was captured from a
#   D-3-biased run pre-fix, the health check validates against the
#   bias and passes. Pillar 3 cannot structurally prevent recursion
#   of trust without Pillar 1's lineage metadata on the baseline.
# - ProjectionContext.read_cohort_baseline() still reaches into
#   filesystem paths (JUSTSEARCH_DATA_DIR env, cohort hash from
#   manifest.json). Pillar 1's artifact manifest would let this
#   become a clean reference lookup. For the MVP it's tolerable but
#   fragile.
# - The spike's health-check registry is inline; productionizing
#   means a formal registry keyed by check name. Small scope (~50
#   LOC); not a showstopper.
#
# SCOPE ESTIMATE (all 7 projections):
# - Decorator + context + runner infra: ~300 LOC.
# - Retrofit of 7 projections × ~50 LOC each = ~350 LOC.
# - Unit tests + contract tests: ~400 LOC.
# - Total: ~1050 LOC, matches §10.1's Pillar 3 low estimate.
#
# ERGONOMICS VERDICT: the pattern fits. No friction in the conversion.
# Pillar 3's 6/10 confidence holds; the bottleneck is H6
# (dependency on Pillar 2) not the pattern itself.
