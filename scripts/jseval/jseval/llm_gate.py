"""LLM-generation-latency ratchet (tempdoc 640 L) — the inference-runtime sibling of perf-gate.

Gates the configured LLM's generation latency — **TTFT** (time-to-first-token) and **end-to-end**
summarization p50 — against a pinned RELATIVE floor, reusing :func:`jseval.diff_gate.compare_ratio`.
The metrics come from ``jseval llm-bench``'s output (``llm-bench.json`` → ``statistics.<metric>.median``),
which already does warmup-discard + multi-sample, so the medians are noise-robust (measured cross-run
CV 3% / 9% — the noisy tail is absorbed by the generous ratio bands in the ``llm-gen`` registry family).

Unlike the per-corpus gates this is per-**machine/config** (the bench is corpus-agnostic — it summarizes
whatever the dev stack has indexed), so the baseline is a SINGLE entry, not per-dataset.

``tokens/sec`` is intentionally absent: the chat ``BatchSummarizeShape`` SSE ``done`` event emits no token
usage, so ``llm_bench`` reports it null — gating it needs a conversation-subsystem backend change (the one
recorded 640 L deferral).

Exit codes mirror perf-gate: 0 = no regression (or un-pinned), 1 = regression, 2 = a pinned metric missing.
:func:`evaluate` is a pure function over parsed dicts, so it is unit-testable without a live LLM.
"""

from __future__ import annotations

from typing import Any

from . import diff_gate, metric_families

_FAMILY = metric_families.BY_NAME["llm-gen"]
_BANDS = dict(_FAMILY.bands)
_LOWER = dict(_FAMILY.lower_is_better)


def _median(bench_doc: dict, metric: str) -> Any:
    """Read ``statistics.<metric>.median`` from an llm-bench.json document."""
    return ((bench_doc.get("statistics") or {}).get(metric) or {}).get("median")


def evaluate(baselines: dict, bench_doc: dict) -> dict:
    """Compare a bench's TTFT/e2e medians against the pinned floors (ratio bands)."""
    report: dict = {"dataset": "llm-gen", "checks": [], "exit_code": 0}
    pinned = baselines.get("metrics") or {}
    if not pinned:
        report["checks"].append({
            "name": "baseline-pinned", "status": "skip",
            "detail": "no pinned llm-gen floor; not gated",
        })
        return report
    bands = baselines.get("bands") or _BANDS
    regressed = data_error = False
    for metric, floor in pinned.items():
        current = _median(bench_doc, metric)
        if not isinstance(current, (int, float)):
            report["checks"].append({
                "name": metric, "status": "fail",
                "detail": f"llm-bench missing '{metric}' median -- run `jseval llm-bench` with AI active",
            })
            data_error = True
            continue
        lower = _LOWER.get(metric, True)
        band = bands.get(metric, _BANDS.get(metric, 1.3))
        kwargs = {"lower_is_better": lower}
        kwargs["max_ratio" if lower else "min_ratio"] = band
        comp = diff_gate.compare_ratio(float(floor), float(current), **kwargs)
        status = {"OK": "ok", "REGRESSED": "fail", "SKIP": "skip"}[comp["status"]]
        report["checks"].append({
            "name": metric, "status": status,
            "detail": (f"current={current:.4g} floor={float(floor):.4g} "
                       f"ratio={comp['ratio']} band=<={band}"),
        })
        if comp["status"] == "REGRESSED":
            regressed = True
    report["exit_code"] = 1 if regressed else (2 if data_error else 0)
    return report


def project_bench_to_llm_baselines(bench_doc: dict, *, bands: dict | None = None, src: str = "") -> dict:
    """Project a *green bench's* measured medians into the llm-gen baseline shape (measured, not
    hand-typed — the same projection discipline as perf_gate.project_run_to_perf_baselines)."""
    metrics: dict[str, float] = {}
    for metric in _FAMILY.metric_keys:
        v = _median(bench_doc, metric)
        if isinstance(v, (int, float)):
            metrics[metric] = float(v)
    return {
        "schema": "llm-gen-ratchet-baseline.v1",
        "projected_from_bench": True,
        "metrics": metrics,
        "bands": dict(bands or _BANDS),
        "src": f"projected from bench {src}".strip(),
    }
