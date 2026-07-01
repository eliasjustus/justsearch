"""Performance ratchet (tempdoc 640) — the perf-metric-family sibling of the
relevance ratchet (:mod:`jseval.relevance_gate` / register Q-010).

It fails loudly when a build makes the engine slower or heavier, instead of letting
latency / throughput / footprint silently regress (the "boiling-frog" — tempdoc 640).
It is a **relative** ratchet: each metric is compared to a pinned baseline as a *ratio*
(reusing :func:`jseval.diff_gate.compare_ratio`), with per-metric bands derived from the
measured within-machine noise (tempdoc 640 §Pre-implementation confidence pass), so it
bites on a real regression without flapping on cold-start jitter. There is **no absolute
SLO** — the no-users rule forbids privileging a workload (tempdoc 640 §C-6).

Gate-able metrics (confidence pass §C — chosen because their cross-run CV is low):

- **cross-encoder STAGE p50** latency — the dominant (~82%) cost, CV 1-10%; a median
  absorbs the single cold query, so no warmup machinery is needed. The *total* p50
  (``latency_stats.p50_ms``) is deliberately **excluded** (CV 35-112%, cold-start).
- **primary-indexing** + **enrichment-complete** throughput (coarse bands).
- **resident model footprint** (deterministic; best-effort — SKIPs if the model paths are
  unresolvable): the ONNX stack (embed + SPLADE + reranker + NER) **plus the LLM** gguf (+ mmproj if
  vision) when the run captured an AI-online inference snapshot (tempdoc 640 R1 — the LLM is ~75% of
  the stack). AI-offline eval runs yield the ONNX-only footprint (the LLM is not resident then).
  ``index_size_bytes`` is excluded (CV 11-62%, Lucene segment-merge non-determinism).

Exit codes (see :func:`jseval.commands.gates.cmd_perf_gate`), mirroring :mod:`jseval.relevance_gate`:

- 0 — no regression (or the dataset is not pinned → does not gate).
- 1 — regression: a pinned metric crossed its band.
- 2 — data problem (a pinned metric is missing from the run).

:func:`evaluate` is a pure function over already-parsed dicts so it is unit-testable
without a live eval run; :func:`jseval.diff_gate.compare_ratio` is the comparison primitive.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from . import diff_gate, metric_families

# Per-metric relative bands + directions are SOURCED FROM THE REGISTRY (metric_families) — the single
# source of truth for the perf families (tempdoc 640 R4; nothing else should define them). diff_gate
# semantics: lower-is-better → max_ratio, higher-is-better → min_ratio. The per-band CV rationale lives
# on the registry's MetricFamily entries. A pinned baseline may still override per corpus.
_DEFAULT_BANDS: dict[str, float] = {
    k: v for fam in metric_families.perf_families() for k, v in fam.bands.items()
}
_LOWER_IS_BETTER: dict[str, bool] = {
    k: v for fam in metric_families.perf_families() for k, v in fam.lower_is_better.items()
}
# Footprint is best-effort: if it cannot be derived it SKIPs (never a data error).
_BEST_EFFORT = {"resident_bytes"}

# When a pinned metric is missing from a run, name the run shape that produces it: a bare
# `jseval run --modes hybrid` runs neither the cross-encoder (--ce defaults off) nor the
# pipeline timeline (--pipeline), so latency + throughput are absent (review fix #1c).
_FULL_RUN_CMD = "jseval run --start-backend --clean --pipeline --ce --embedding --splade"
_RUN_SHAPE_HINT = {
    "ce_p50_ms": "needs --ce (cross-encoder reranking)",
    "primary_docs_s": "needs --pipeline (full ingest timeline)",
    "enrich_docs_s": "needs --pipeline (full ingest timeline)",
}


# --- current-value readers (from a run's summary.json) ----------------------

def ce_p50_ms(summary: dict, mode: str) -> Any:
    """CE-stage p50. Canonical: `aggregate_metrics.ce_p50_ms` (tempdoc 640, promoted so it flows like
    a quality metric); fallback to `stage_timing_stats.cross_encoder_ms.p50` for legacy runs."""
    pm = (summary.get("per_mode") or {}).get(mode) or {}
    v = (pm.get("aggregate_metrics") or {}).get("ce_p50_ms")
    if isinstance(v, (int, float)):
        return v
    return ((pm.get("stage_timing_stats") or {}).get("cross_encoder_ms") or {}).get("p50")


def primary_docs_s(summary: dict, mode: str) -> Any:
    """Primary-indexing throughput. Canonical: `run_metrics.primary_docs_s` (tempdoc 640); fallback
    to `ingest.pipeline_summary.primary_indexing.docs_per_s` for legacy runs."""
    v = (summary.get("run_metrics") or {}).get("primary_docs_s")
    if isinstance(v, (int, float)):
        return v
    ing = summary.get("ingest") or {}
    return ((ing.get("pipeline_summary") or {}).get("primary_indexing") or {}).get("docs_per_s")


def enrich_docs_s(summary: dict, mode: str) -> Any:
    """Enrichment-complete throughput. Canonical: `run_metrics.enrich_docs_s` (tempdoc 640); fallback
    to `ingest.docs_per_sec` for legacy runs."""
    v = (summary.get("run_metrics") or {}).get("enrich_docs_s")
    if isinstance(v, (int, float)):
        return v
    return (summary.get("ingest") or {}).get("docs_per_sec")


_READERS = {
    "ce_p50_ms": ce_p50_ms,
    "primary_docs_s": primary_docs_s,
    "enrich_docs_s": enrich_docs_s,
}


# --- footprint derivation (deterministic, no backend change) ----------------

def _file_bytes(path: Path) -> int:
    """Size of an ONNX file + any sibling external-weights ``*onnx_data`` file."""
    total = path.stat().st_size
    for sib in path.parent.glob("*onnx_data"):
        total += sib.stat().st_size
    return total


def _pick_variant(dir_path: Path, prefer_gpu: bool) -> Path | None:
    """Pick the active ONNX variant in a model dir (fp16 on GPU, fp32 on CPU), falling
    back to whichever exists."""
    fp16, fp32 = dir_path / "model_fp16.onnx", dir_path / "model.onnx"
    order = (fp16, fp32) if prefer_gpu else (fp32, fp16)
    for cand in order:
        if cand.is_file():
            return cand
    return None


def _llm_resident_bytes(manifest: dict | None, models_dir: Path) -> int:
    """LLM gguf (+ mmproj projector if vision-capable) bytes — the ~75% of the resident stack the
    retrieval-ONNX footprint misses (tempdoc 640 R1).

    The configured/active LLM is named by ``activeModelId`` in the run's captured **non-hashed**
    ``inference_status_snapshot`` (verified live: ``/api/inference/status`` returns e.g.
    ``"Qwen_Qwen3.5-9B-Q4_K_M.gguf"`` when AI is online). It sits at the models-dir root, beside the
    ``onnx/``+``splade/`` layout the ONNX footprint already resolves. Returns ``0`` when no active
    model is recorded (AI-offline eval runs — the LLM is not resident then, so footprint stays
    ONNX-only). The gguf file bytes are the deterministic configured-weight footprint; a KV-cache
    estimate is arch-dependent (needs a gguf-header parse) and intentionally omitted.
    """
    inf = (manifest or {}).get("inference_status_snapshot") or {}
    active = inf.get("activeModelId")
    if not active:
        return 0
    total = 0
    gguf = models_dir / active
    if gguf.is_file():
        try:
            total += gguf.stat().st_size
        except OSError:
            return total
    if inf.get("hasVisionCapability"):  # a VLM also loads an mmproj projector
        for mm in sorted(models_dir.glob("mmproj*.gguf")):
            try:
                total += mm.stat().st_size
            except OSError:
                pass
            break  # one projector
    return total


def derive_resident_model_bytes(manifest: dict | None) -> int | None:
    """Derive the engine's resident model footprint deterministically from a run's
    ``manifest`` (``model_fingerprints`` + the captured inference snapshot) + the fixed model-dir layout.

    Sums the active ONNX variants (embed + SPLADE + reranker + NER) **and the LLM** gguf (+ mmproj if
    vision) when the run captured an AI-online inference snapshot (tempdoc 640 R1 — the LLM dominates the
    stack at ~75%). Returns ``None`` (→ the footprint check SKIPs) if the model dir / paths cannot be
    resolved, so this is a best-effort metric that never turns the gate into a data error. The LLM
    portion is itself best-effort: AI-offline eval runs (no ``activeModelId``) yield the ONNX-only
    footprint, since the LLM is not resident then.
    """
    mf = (manifest or {}).get("model_fingerprints") or {}
    splade_path = mf.get("splade_model_path")
    if not splade_path:
        return None
    splade = Path(splade_path)
    # `splade_model_path` may be the model DIR (…/splade/<name>) or a FILE
    # (…/splade/<name>/model.onnx). Locate the models dir by its layout marker
    # (the ancestor that holds both `onnx/` and `splade/`), robust to either form.
    models_dir = None
    for cand in ([splade] if splade.is_dir() else []) + list(splade.parents):
        if (cand / "onnx").is_dir() and (cand / "splade").is_dir():
            models_dir = cand
            break
    if models_dir is None:
        return None
    prefer_gpu = bool(mf.get("embed_gpu"))

    def _resolve(path_str: str | None, *fallback_dirs: Path) -> Path | None:
        """Resolve a model file from a path that may be a file or a dir, or a fallback dir."""
        if path_str:
            p = Path(path_str)
            if p.is_file():
                return p
            if p.is_dir():
                v = _pick_variant(p, prefer_gpu)
                if v:
                    return v
        for d in fallback_dirs:
            v = _pick_variant(d, prefer_gpu)
            if v:
                return v
        return None

    files: list[Path] = []
    for resolved in (
        _resolve(splade_path),                                            # SPLADE
        _pick_variant(models_dir / "onnx" / "gte-multilingual-base", prefer_gpu),  # embed
        _pick_variant(models_dir / "onnx" / "reranker", prefer_gpu),      # reranker
        _resolve(mf.get("ner_model_path"), models_dir / "onnx" / "ner"),  # NER
    ):
        if resolved and resolved.is_file():
            files.append(resolved)

    if not files:
        return None
    total = 0
    for f in files:
        try:
            total += _file_bytes(f)
        except OSError:
            continue
    total += _llm_resident_bytes(manifest, models_dir)  # tempdoc 640 R1: include the LLM when resident
    return total or None


def _current_value(metric: str, summary: dict, mode: str, manifest: dict | None) -> Any:
    if metric == "resident_bytes":
        return derive_resident_model_bytes(manifest)
    reader = _READERS.get(metric)
    return reader(summary, mode) if reader else None


def run_dataset_ok(manifest: dict | None, dataset: str) -> bool:
    """True if the run's recorded dataset matches ``dataset`` (or cannot be verified).

    Guards against a silent cross-corpus comparison — throughput especially is strongly
    corpus-size-dependent, so gating run A against corpus B's baseline yields a garbage
    verdict (review fix #4). None-tolerant: if the manifest is absent or carries no
    ``dataset`` we cannot verify, so we do not block.
    """
    run_ds = (manifest or {}).get("dataset")
    if run_ds is None or run_ds == dataset:
        return True
    # The release (and its projected baselines) key on the CANONICAL slug (e.g. `beir/scifact`) while a
    # run's manifest carries the raw `--dataset` arg (e.g. `scifact`). Match on the canonical form so the
    # release-projection path gates correctly (tempdoc 640 live-validation finding).
    try:
        from .release import canonical_dataset_slug

        return canonical_dataset_slug(run_ds) == canonical_dataset_slug(dataset)
    except Exception:
        return False


# --- envelope-derived band (tempdoc 640 R2) --------------------------------

def _envelope_band(
    manifest: dict | None, mode: str, metric: str, lower: bool, *, k: float = 2.0
) -> float | None:
    """A *data-driven* ratio band from the cohort calibrate envelope: ``1 ± k·CV``.

    The run manifest embeds its cohort's ``non_determinism_envelope`` (``manifest.py``), whose
    ``metrics.<mode>.<metric>.{mean,stdev}`` is the within-machine ±kσ reproducibility band
    (``calibrate.compute_envelope``; ``k=2`` ≈ the ±2σ band tempdoc 640 names). Converting σ to a
    *ratio* (``CV = stdev/mean``) keeps ``diff_gate``'s ratio model unchanged — the band just becomes
    measured-noise-adaptive instead of a fixed guess: tight where the metric is stable, wide where it
    is noisy. Returns ``None`` when the cohort has no calibrated value for this (mode, metric) — only
    the calibrated per-mode metric (``ce_p50_ms``) qualifies; the caller then falls back to the fixed
    band (so the gate is unchanged until a calibrate envelope exists — never flappier).
    """
    env = (manifest or {}).get("non_determinism_envelope") or {}
    cell = (((env.get("metrics") or {}).get(mode) or {}).get(metric)) or {}
    mean, stdev = cell.get("mean"), cell.get("stdev")
    if not isinstance(mean, (int, float)) or not isinstance(stdev, (int, float)) or mean <= 0:
        return None
    cv = stdev / mean
    return (1.0 + k * cv) if lower else max(0.0, 1.0 - k * cv)


# --- the gate ---------------------------------------------------------------

def evaluate(
    baselines: dict,
    summary: dict,
    dataset: str,
    manifest: dict | None = None,
) -> dict:
    """Compare a run's perf metrics against the pinned baseline for ``dataset``.

    :param baselines: parsed ``perf-ratchet-baselines.v1.json`` (``{"baselines": {...}}``).
    :param summary: parsed run ``summary.json``.
    :param dataset: the dataset slug (e.g. ``beir/scifact``).
    :param manifest: parsed run ``manifest.json`` (for the footprint metric); optional.
    :returns: a report dict with ``exit_code`` and per-metric ``checks``.
    """
    report: dict = {"dataset": dataset, "checks": [], "exit_code": 0}

    pinned = (baselines.get("baselines") or {}).get(dataset)
    if pinned is None:
        report["checks"].append({
            "name": "baseline-pinned",
            "status": "skip",
            "detail": f"no pinned baseline for {dataset}; not gated",
        })
        return report  # un-pinned datasets do not gate (exit 0)

    mode = pinned.get("mode")
    metrics = pinned.get("metrics") or {}
    bands = pinned.get("bands") or {}
    # Band resolution order: per-entry `bands` -> file-level `default_bands` -> module
    # default. The file-level block must be honored (review fix #2), mirroring how
    # relevance_gate reads `tolerance_default_abs` from the baselines file.
    file_default_bands = baselines.get("default_bands") or {}
    report["mode"] = mode

    regressed_any = False
    data_error = False
    for metric, baseline_val in metrics.items():
        current = _current_value(metric, summary, mode, manifest)
        if not isinstance(current, (int, float)):
            if metric in _BEST_EFFORT:
                report["checks"].append({
                    "name": metric,
                    "status": "skip",
                    "detail": "value unavailable (best-effort metric)",
                })
                continue
            detail = f"run is missing '{metric}' for mode '{mode}'"
            hint = _RUN_SHAPE_HINT.get(metric)
            if hint:
                detail += f" -- {hint}; re-run with `{_FULL_RUN_CMD}`"
            report["checks"].append({"name": metric, "status": "fail", "detail": detail})
            data_error = True
            continue

        lower = _LOWER_IS_BETTER.get(metric, True)
        # Band resolution: an explicit per-entry pin wins (operator override); else a data-driven
        # envelope band (1 ± k·CV) when the cohort calibrated this metric (tempdoc 640 R2); else the
        # file-level / module fixed default. The envelope band adapts to measured noise; the fixed
        # default is the graceful fallback when no calibrate envelope exists.
        explicit = bands.get(metric)
        if explicit is not None:
            band, band_src = explicit, "pinned"
        else:
            env_band = _envelope_band(manifest, mode, metric, lower)
            if env_band is not None:
                band, band_src = env_band, "envelope±2σ"
            else:
                band = file_default_bands.get(metric, _DEFAULT_BANDS.get(metric, 1.10))
                band_src = "default"
        kwargs = {"lower_is_better": lower}
        kwargs["max_ratio" if lower else "min_ratio"] = band
        comp = diff_gate.compare_ratio(float(baseline_val), float(current), **kwargs)
        status = {"OK": "ok", "REGRESSED": "fail", "SKIP": "skip"}[comp["status"]]
        report["checks"].append({
            "name": metric,
            "status": status,
            "detail": (
                f"current={current:.4g} baseline={float(baseline_val):.4g} "
                f"ratio={comp['ratio']} band={'<=' if lower else '>='}{band:.4g} [{band_src}] "
                f"({'lower' if lower else 'higher'}-is-better)"
            ),
        })
        if comp["status"] == "REGRESSED":
            regressed_any = True

    # A real regression (1) is louder than a data error (2); both beat 0.
    report["exit_code"] = 1 if regressed_any else (2 if data_error else 0)
    return report


# --- baseline regeneration from a green run (projection, not hand-typing) ----

def project_run_to_perf_baselines(
    summary: dict,
    dataset: str,
    mode: str,
    *,
    manifest: dict | None = None,
    bands: dict | None = None,
    src: str = "",
) -> dict:
    """Project a *green run's* measured perf metrics into the ratchet baseline shape.

    The floor for each metric is the run's **measured** value, never a hand-typed number
    (the projection-not-fork discipline — tempdoc 623 / 640). Returns the exact
    ``{"baselines": {<dataset>: {mode, metrics, bands, src}}}`` shape :func:`evaluate`
    consumes. Metrics that the run does not expose are simply omitted.
    """
    measured: dict[str, float] = {}
    for metric in _DEFAULT_BANDS:
        val = _current_value(metric, summary, mode, manifest)
        if isinstance(val, (int, float)):
            measured[metric] = float(val)
    entry = {
        "mode": mode,
        "metrics": measured,
        "bands": dict(bands or _DEFAULT_BANDS),
        "src": f"projected from run {src}".strip(),
    }
    return {
        "schema": "perf-ratchet-baseline.v1",
        "projected_from_run": True,
        "baselines": {dataset: entry},
    }


def project_release_to_perf_baselines(release: dict, *, bands: dict | None = None) -> dict:
    """Project a ``release.v1`` object into perf-ratchet baselines — the sibling of
    ``relevance_gate.project_release_to_baselines`` (tempdoc 640 / 623 anti-fork).

    The per-mode CE latency comes from ``measured.<ds>.metrics`` and the per-run
    throughput/footprint from ``measured.<ds>.run_metrics`` (the metric families promoted
    into the canonical record). Datasets carrying no perf metrics (e.g. an extraction-only
    release) are skipped. This closes the per-run fork: the floor is the release's *measured*
    value across one cohort, not a hand-pinned single run.
    """
    bands = dict(bands or _DEFAULT_BANDS)
    cohort = release.get("cohort") or {}
    src_tag = release.get("release_id") or cohort.get("git_sha", "")[:10]
    # tempdoc 664: perf metrics (latency/throughput) are hardware-sensitive, unlike relevance —
    # refuse to project a floor from a release whose members are explicitly known to have run on
    # different hardware (`compose()` now records this). A MISSING key (a release composed before
    # this check existed) is treated as permissive, not refused, for backward compatibility with
    # already-published releases; recomposing naturally picks up the check going forward.
    if cohort.get("hardware_homogeneous") is False:
        return {
            "schema": "perf-ratchet-baseline.v1",
            "projected_from_release": True,
            "hardware_homogeneous": False,
            "baselines": {},
        }
    out: dict[str, dict] = {}
    for dataset, measured in (release.get("measured") or {}).items():
        if not isinstance(measured, dict):
            continue
        pm_metrics = measured.get("metrics") or {}
        run_metrics = measured.get("run_metrics") or {}
        metrics: dict[str, float] = {}
        for metric in _DEFAULT_BANDS:
            v = pm_metrics.get(metric)  # CE latency lives in the per-mode map
            if not isinstance(v, (int, float)):
                v = run_metrics.get(metric)  # throughput/footprint in the per-run map
            if isinstance(v, (int, float)):
                metrics[metric] = float(v)
        if not metrics:
            continue  # no perf family in this corpus's release entry
        out[dataset] = {
            "mode": measured.get("config_mode"),
            "metrics": metrics,
            "bands": bands,
            "src": f"projected from release {src_tag}".strip(),
        }
    return {
        "schema": "perf-ratchet-baseline.v1",
        "projected_from_release": True,
        "baselines": out,
    }
