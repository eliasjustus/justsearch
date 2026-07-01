"""Metric-family registry (tempdoc 640) — the single source of truth for the engine-quality
ratchet family.

Today the families are scattered: quality in `run.py`'s `aggregate_metrics` +
`calibrate.CALIBRATED_QUALITY_METRICS`, latency in `calibrate.CALIBRATED_LATENCY_METRICS` +
`perf_gate` readers, perf bands in `perf_gate._DEFAULT_BANDS`/`_LOWER_IS_BETTER`, leak in
`leak_gate`'s ceiling. This module consolidates them so `calibrate` / `history` / the published
renderers / the gates all read ONE definition (tempdoc 640 §Long-term design — genericize the
record). The Python consumers (`calibrate`, `history`, `perf_gate` + the gates) read this directly and
must not re-declare a family. The published renderers (`gen-public-benchmark.mjs` /
`register-headline-sync.mjs`) are JavaScript and *cannot* import this module, so they intentionally
re-declare their perf columns — a documented language boundary (tempdoc 640 R4), not a fork to remove.

`source_class` says WHERE a family's metrics live in a run's artifacts:

- `per_mode`   — in `summary.per_mode.<mode>.<source_path>` (a per-mode metric map). Quality lives in
                 `aggregate_metrics`; the cross-encoder STAGE p50 latency is promoted into the same map
                 (`run.py`) so it flows like nDCG.
- `per_run`    — in `summary.<source_path>` at the top level (a per-run map). Throughput + footprint are
                 run-level, not per-mode, so they get their own `run_metrics` map.
- `projection` — in a separate projection artifact `<run_dir>/projections/<source_path>` (leak). Leak is a
                 *cross-mode projection* metric that does NOT live in the per-mode/per-run record (tempdoc
                 640 §confidence-pass: the leak-fold test). It is registered here so the family CONCEPT is
                 unified across all three gates, but its gate stays projection-sourced — it is NOT migrated.

`comparator` says how a run is judged vs its pinned baseline: `ratio` (perf — a `diff_gate` band:
lower-is-better → max_ratio, higher → min_ratio), `abs_tolerance` (quality — floor = baseline − tolerance),
`ceiling` (leak — limit = baseline + tolerance).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Literal

SourceClass = Literal["per_mode", "per_run", "projection", "bench"]
Comparator = Literal["ratio", "abs_tolerance", "ceiling"]


@dataclass(frozen=True)
class MetricFamily:
    """Canonical descriptor for one metric family. Read by calibrate/history/renderers/gates."""

    name: str
    source_class: SourceClass
    # per_mode/per_run: the key of the metric MAP within its source object.
    # projection: the projection file name under <run_dir>/projections/.
    source_path: str
    metric_keys: tuple[str, ...]
    lower_is_better: dict[str, bool]
    comparator: Comparator
    # ratio bands (lower_is_better → max_ratio; higher → min_ratio); empty for abs_tolerance/ceiling.
    bands: dict[str, float] = field(default_factory=dict)
    tolerance_abs: float = 0.0
    # include in the within-machine `calibrate` envelope (only low-noise per-mode metrics).
    calibrate: bool = False


QUALITY = MetricFamily(
    name="quality",
    source_class="per_mode",
    source_path="aggregate_metrics",
    metric_keys=("nDCG@10", "P@1", "R@10", "RR@10", "AP@10"),
    lower_is_better={k: False for k in ("nDCG@10", "P@1", "R@10", "RR@10", "AP@10")},
    comparator="abs_tolerance",
    tolerance_abs=0.02,
    calibrate=True,
)

# The per-stage latency decomposition (tempdoc 640 §C-2 + tempdoc 647). The cross-encoder is the
# dominant, noise-robust cost; tempdoc 647 completes the family with the retrieval stage — the only
# other stage present on every query (chunk-merge/branch-fusion/lambdamart are query-conditional, so
# they stay report-only in `stage_timing_stats` with the `unaccounted_ms` remainder + shares, never
# gated). Both gated stages are promoted into `aggregate_metrics` (run.py) so they flow + calibrate
# like a quality metric; each gates RELATIVELY (the band self-widens via the calibrate envelope ±2σ,
# so the tiny/noisier retrieval stage does not flap — its fixed fallback band is wider than CE's).
PERF_LATENCY = MetricFamily(
    name="perf-latency",
    source_class="per_mode",
    source_path="aggregate_metrics",
    metric_keys=("ce_p50_ms", "retrieval_p50_ms"),
    lower_is_better={"ce_p50_ms": True, "retrieval_p50_ms": True},
    comparator="ratio",
    bands={"ce_p50_ms": 1.25, "retrieval_p50_ms": 1.5},
    calibrate=True,
)

PERF_THROUGHPUT = MetricFamily(
    name="perf-throughput",
    source_class="per_run",
    source_path="run_metrics",
    metric_keys=("primary_docs_s", "enrich_docs_s"),
    lower_is_better={"primary_docs_s": False, "enrich_docs_s": False},
    comparator="ratio",
    bands={"primary_docs_s": 0.65, "enrich_docs_s": 0.75},
    calibrate=False,  # per-run + noisier — not in the per-mode envelope
)

# Resident footprint (tempdoc 640) + its per-component allocation (tempdoc 647). `resident_bytes` is the
# summed total; the component keys (embed / SPLADE / reranker / NER / LLM) are its deterministic
# addends — footprint is config-determined (workload-independent), so an ABSOLUTE per-component
# allocation check is admissible (the one place a per-part absolute allowance is legitimate; 647 §C-2).
# All are best-effort (SKIP when a model path is unresolvable, and `llm_bytes` is absent on AI-offline
# runs where the LLM is not resident); tight bands because the values are deterministic per config.
PERF_FOOTPRINT = MetricFamily(
    name="perf-footprint",
    source_class="per_run",
    source_path="run_metrics",
    metric_keys=(
        "resident_bytes",
        "embed_bytes",
        "splade_bytes",
        "reranker_bytes",
        "ner_bytes",
        "llm_bytes",
    ),
    lower_is_better={
        k: True
        for k in (
            "resident_bytes",
            "embed_bytes",
            "splade_bytes",
            "reranker_bytes",
            "ner_bytes",
            "llm_bytes",
        )
    },
    comparator="ratio",
    bands={
        k: 1.05
        for k in (
            "resident_bytes",
            "embed_bytes",
            "splade_bytes",
            "reranker_bytes",
            "ner_bytes",
            "llm_bytes",
        )
    },
    calibrate=False,
)

# Cross-mode projection metric — registered to unify the family concept, but its gate stays
# projection-sourced (tempdoc 640 leak-fold finding); NOT migrated into the per-mode/per-run record.
LEAK = MetricFamily(
    name="leak",
    source_class="projection",
    source_path="staged_recall_accounting.json",
    metric_keys=("leak_rate",),
    lower_is_better={"leak_rate": True},  # higher leak is worse → ceiling comparator
    comparator="ceiling",
    tolerance_abs=0.05,
    calibrate=False,
)

# LLM-generation latency + throughput (tempdoc 640 L) — a `bench` source-class family read from
# llm-bench.json, NOT a per-corpus run metric (it's a property of the configured LLM + hardware).
# TTFT + e2e medians are gate-able (measured CV 3% / 9%); generous ratio bands absorb the noisier tail.
# token_rate_median_tps (tokens/sec, higher-is-better) is now captured (tempdoc 640 D: llm_bench derives
# completion = totalTokens − promptTokens from the chat done-event, which emits both flat).
LLM_GEN = MetricFamily(
    name="llm-gen",
    source_class="bench",
    source_path="llm-bench.json",
    metric_keys=("ttft_p50_ms", "e2e_latency_p50_ms", "token_rate_median_tps"),
    lower_is_better={"ttft_p50_ms": True, "e2e_latency_p50_ms": True, "token_rate_median_tps": False},
    comparator="ratio",
    bands={"ttft_p50_ms": 1.4, "e2e_latency_p50_ms": 1.3, "token_rate_median_tps": 0.7},
    calibrate=False,
)

REGISTRY: tuple[MetricFamily, ...] = (
    QUALITY, PERF_LATENCY, PERF_THROUGHPUT, PERF_FOOTPRINT, LEAK, LLM_GEN,
)
BY_NAME: dict[str, MetricFamily] = {f.name: f for f in REGISTRY}


def families(
    *,
    source_class: SourceClass | None = None,
    comparator: Comparator | None = None,
    calibrate: bool | None = None,
) -> tuple[MetricFamily, ...]:
    """Filtered view over the registry (the canonical iteration point for consumers)."""
    out = REGISTRY
    if source_class is not None:
        out = tuple(f for f in out if f.source_class == source_class)
    if comparator is not None:
        out = tuple(f for f in out if f.comparator == comparator)
    if calibrate is not None:
        out = tuple(f for f in out if f.calibrate == calibrate)
    return out


def perf_families() -> tuple[MetricFamily, ...]:
    """The performance families (latency + throughput + footprint) — what the perf gate reads."""
    return tuple(f for f in REGISTRY if f.name.startswith("perf-"))


def calibrated_per_mode_metrics() -> dict[str, list[str]]:
    """``{source_path: [metric_keys]}`` for calibrated per-mode families — drives `calibrate`."""
    out: dict[str, list[str]] = {}
    for f in families(source_class="per_mode", calibrate=True):
        out.setdefault(f.source_path, []).extend(f.metric_keys)
    return out
