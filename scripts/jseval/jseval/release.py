"""Benchmark-release composition (tempdoc 623).

A *release* is a **projection over cohort-identical eval runs** — one config /
commit / hardware, ranged over N corpora — not a new authority. Every measured
number derives from a ``jseval run`` ``summary.json``; the search-quality
register headline and the relevance-ratchet floors become projections of a
release (tempdoc 623 T-5), which removes the hand-maintained-fork drift class
the tempdoc opened with.

Key design points (tempdoc 623, verified against on-disk artifacts):

- **The config-cohort key is the *config-global subset only*** (T-1 / resolved
  ledger U1). A naive "hash every non-dataset manifest field" key would *never*
  group ``beir/scifact`` with the ``mixed/*`` corpora, because four
  ``commit_metadata`` fingerprints (``field_catalog_hash``, ``index_schema_fp``,
  ``analyzer_fp``, ``synonyms_hash``) are **corpus-dependent** — they describe
  which fields a corpus populates, not a configuration choice. So
  :func:`config_cohort_key` hashes an explicit allow-list and excludes those.

- **The metrics map is family-keyed, NOT nDCG-hard-wired** (T-3 / §F favor 1).
  A retrieval release populates ``{nDCG@10, P@1, ...}``; an extraction-quality
  sibling reuses this exact object with ``{WER, CER, route_accuracy, ...}``. The
  release object is "a config-cohort's scorecard over a corpus set," metric
  family parametric — so the sibling extends 623 instead of forking it.

- **The reproduction tolerance is the run's within-machine ±2σ envelope**, scoped
  to "equivalent setup" (decision F-α). ``None`` when the cohort is uncalibrated.

- ``mode`` (hybrid / full / lexical) is a per-run eval parameter, **not** part of
  cohort identity, so one cohort can carry several modes; a release picks one
  ``default_mode`` per corpus and records the rest as labelled ablations.
"""

from __future__ import annotations

import hashlib
import json
from typing import Any

RELEASE_SCHEMA = "release.v1"
RELEASE_SCHEMA_VERSION = 1

# --- The config-cohort key field-set (tempdoc 623 T-1 / U1) -----------------
#
# commit_metadata fingerprints that are CONFIG-global (verified identical across
# scifact / courtlistener-200 / cord19-qddf on disk):
_CONFIG_GLOBAL_COMMIT_METADATA = (
    "schema_fp",
    "similarity_fp",
    "boosts_fp",
    "grammar_hash",
)
# commit_metadata fingerprints that are CORPUS-dependent (differ scifact vs
# mixed) — documented here so the exclusion is legible, never hashed into the key:
_CORPUS_DEPENDENT_COMMIT_METADATA = (
    "field_catalog_hash",
    "index_schema_fp",
    "analyzer_fp",
    "synonyms_hash",
)
# model_fingerprints keys that are execution-context (vary GPU vs CPU), excluded
# from model identity:
_MODEL_EXECUTION_FLAGS = ("embed_gpu", "splade_gpu", "ner_gpu", "reranker_gpu")


class ComposeError(ValueError):
    """Raised when a candidate run-set cannot form one coherent release."""


def select_dominant_cohort(dataset_to_key: dict[str, str]) -> tuple[str | None, list[str]]:
    """Pick the config-cohort covering the most datasets; report the excluded split (P1, 623).

    ``--latest-per-dataset`` can pick runs spanning commits when the shared ``main`` moves
    mid-sweep (the V5 split). Rather than feed a mixed set to :func:`compose` (which refuses
    the whole thing), choose the dominant cohort and surface the rest. Deterministic tie-break
    (count desc, then key desc).

    :param dataset_to_key: ``{dataset_slug: config_cohort_key}`` for the latest run per dataset.
    :returns: ``(chosen_key | None, sorted excluded dataset slugs)``.
    """
    if not dataset_to_key:
        return None, []
    counts: dict[str, int] = {}
    for key in dataset_to_key.values():
        counts[key] = counts.get(key, 0) + 1
    chosen = max(counts, key=lambda k: (counts[k], k))
    excluded = sorted(ds for ds, k in dataset_to_key.items() if k != chosen)
    return chosen, excluded


def canonical_dataset_slug(dataset: str | None) -> str | None:
    """Normalize a run's ``summary.dataset`` to the register/ratchet slug form.

    ``jseval run --dataset`` uses the short BEIR key (``scifact``), but the
    search-quality register, the relevance-ratchet pointer, and the external
    baselines all key on the ``beir/<name>`` slug. A BEIR short name (no ``/``)
    is prefixed with ``beir/``; ``mixed/*`` and ``golden/*`` are already slugged.
    Keeping the release keyed on the canonical slug is what lets the projected
    floors line up with ``jseval relevance-gate --dataset beir/scifact``.
    """
    if dataset is None:
        return None
    return dataset if "/" in dataset else f"beir/{dataset}"


def _canonical_sha(obj: Any) -> str:
    """SHA-256 of canonical JSON (sorted keys, compact). Mirrors manifest.py."""
    blob = json.dumps(
        obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
    )
    return hashlib.sha256(blob.encode("utf-8")).hexdigest()


def _model_identity(model_fingerprints: dict | None) -> dict:
    """Model identity minus execution-context flags (T-1 exclusion)."""
    mf = model_fingerprints or {}
    return {k: v for k, v in mf.items() if k not in _MODEL_EXECUTION_FLAGS}


def config_cohort_key(manifest: dict) -> str:
    """Equivalence key over the *config-global* manifest subset (T-1 / U1).

    Two runs sharing this key are the *same configuration on the same hardware*,
    regardless of corpus. Hashes ONLY: ``git_sha``, ``eval_protocol_hash``,
    ``policy_hash``, the four config-global ``commit_metadata`` fps, and model
    identity (minus the ``*_gpu`` flags). Excludes the dataset family and the
    four corpus-dependent ``commit_metadata`` fps — including those would refuse
    every multi-corpus release (the U1 finding).
    """
    cm = manifest.get("commit_metadata") or {}
    key_surface = {
        "git_sha": manifest.get("git_sha"),
        "eval_protocol_hash": manifest.get("eval_protocol_hash"),
        "policy_hash": manifest.get("policy_hash"),
        "commit_metadata_global": {
            k: cm.get(k) for k in _CONFIG_GLOBAL_COMMIT_METADATA
        },
        "model_identity": _model_identity(manifest.get("model_fingerprints")),
    }
    return _canonical_sha(key_surface)


# --- Per-run projections ----------------------------------------------------

def _tolerance_band(manifest: dict, mode: str) -> dict | None:
    """Within-machine ±2σ envelope for ``mode`` from the run's manifest (F-α).

    Returns ``{"metric": {"mean", "stdev", "two_sigma", "n"}}`` or ``None`` when
    the cohort is uncalibrated (no ``non_determinism_envelope`` embedded). The
    reproduction claim that rides this band is scoped to "equivalent setup".
    """
    env = manifest.get("non_determinism_envelope")
    if not isinstance(env, dict):
        return None
    mode_metrics = (env.get("metrics") or {}).get(mode)
    if not isinstance(mode_metrics, dict):
        return None
    band: dict = {}
    for metric, stats in mode_metrics.items():
        if not isinstance(stats, dict):
            continue
        stdev = stats.get("stdev")
        band[metric] = {
            "mean": stats.get("mean"),
            "stdev": stdev,
            "two_sigma": (2 * stdev) if isinstance(stdev, (int, float)) else None,
            "n": stats.get("n"),
        }
    return band or None


def _hardware_projection(manifest: dict) -> dict:
    """Stable hardware identity projected from the run's (volatile) snapshots.

    GPU name from ``env_fingerprint.gpu``; driver/VRAM from
    ``inference_status_snapshot.gpu``; ORT/CUDA version from the same gpu object
    when present (tempdoc 623 U7 — added worker-side). All best-effort: a missing
    probe yields ``None``, never an error.
    """
    gpu_fp = (manifest.get("env_fingerprint") or {}).get("gpu") or {}
    inf = manifest.get("inference_status_snapshot") or {}
    gpu_inf = inf.get("gpu") or {}
    # ORT version comes from the WORKER (where ORT is initialized), surfaced via the worker
    # effective_config map (debug-only WorkerDebugView) into /api/debug/state — retained, un-hashed
    # (tempdoc 623 U7). The Head cannot initialize ORT, so it is NOT read from inference-status.
    # cudaVersion is the pinned toolkit major, captured Head-side (a constant, no ORT init).
    worker = (manifest.get("debug_state_snapshot") or {}).get("worker") or {}
    effective_config = worker.get("effective_config") or {}
    return {
        "gpu_name": gpu_fp.get("name"),
        "gpu_vram_bytes": gpu_inf.get("totalVramBytes") or gpu_fp.get("mem_total_mb"),
        "gpu_driver_version": gpu_inf.get("nvmlDriverVersion") or gpu_fp.get("driver_version"),
        "ort_version": effective_config.get("ort.version"),
        "cuda_version": gpu_inf.get("cudaVersion"),
        "tier": inf.get("tier"),
    }


def _corpus_source(summary: dict) -> dict:
    """Pointer reference for a corpus (pointer-only distribution, F-ε).

    Records the dataset id, the per-corpus signature seam (``corpus_identity``),
    and counts. The ``sha256`` slot is the checksum-pinning seam (reuse the
    ``SSOT/manifests/repro`` digest model) — populated when a corpus signature
    exists, ``None`` otherwise (honest: not yet checksummed).
    """
    ci = summary.get("corpus_identity") or {}
    dataset = summary.get("dataset")
    src: dict = {
        "dataset": dataset,
        "doc_count": summary.get("doc_count"),
        "query_count": summary.get("query_count"),
        "signature": ci.get("signature"),
        "sha256": ci.get("signature"),  # the digest seam; None until populated
    }
    if isinstance(dataset, str) and dataset.startswith("beir/"):
        # BEIR corpora are fetched by ir_datasets — the id IS the pointer.
        src["ir_datasets_id"] = dataset.split("beir/", 1)[1]
    return src


def _measured_for_mode(summary: dict, mode: str) -> dict | None:
    """Family-keyed metrics for ``mode`` from a run summary, or ``None``.

    The ``metrics`` map is NOT nDCG-specific: it copies whatever
    ``aggregate_metrics`` the run reported (T-3 / §F favor 1).
    """
    per_mode = (summary.get("per_mode") or {}).get(mode)
    if not isinstance(per_mode, dict):
        return None
    metrics = per_mode.get("aggregate_metrics")
    if not isinstance(metrics, dict) or not metrics:
        return None
    manifest = summary.get("manifest") or {}
    qsum = summary.get("qrels_summary") or {}
    # Per-run metric family (tempdoc 640): throughput from the summary's `run_metrics` + a derived
    # resident footprint from the manifest's model fingerprints. Carried as a sibling to `metrics`
    # (the per-mode family) so the perf gate can project a per-run floor from the release like
    # relevance projects nDCG. Best-effort — keys are omitted when their source is unresolvable.
    run_metrics = dict(summary.get("run_metrics") or {})
    from . import perf_gate as _pg
    _footprint = _pg.derive_resident_model_bytes(manifest)
    if isinstance(_footprint, (int, float)):
        run_metrics["resident_bytes"] = float(_footprint)
    return {
        "config_mode": mode,
        "metrics": dict(metrics),
        "run_metrics": run_metrics,
        "comparable": bool(per_mode.get("comparable")),
        "ann_proof_status": per_mode.get("ann_proof_status"),
        "tolerance_band": _tolerance_band(manifest, mode),
        "confidence_tier": _confidence_tier(summary, qsum),
        "corpus_source": _corpus_source(summary),
        "qrels_id": qsum.get("relevance_mode"),
    }


def _confidence_tier(summary: dict, qrels_summary: dict) -> str:
    """A/B/C tier mirroring the register convention (≥200 q = A, else B; C unused here)."""
    qc = summary.get("query_count") or qrels_summary.get("query_count") or 0
    try:
        qc = int(qc)
    except (TypeError, ValueError):
        qc = 0
    return "A" if qc >= 200 else "B"


# --- Composition ------------------------------------------------------------

def compose(
    run_summaries: list[dict],
    *,
    default_mode: str = "hybrid",
    composed_at: str,
    release_id: str | None = None,
    external_baselines: dict | None = None,
    require_comparable: bool = True,
) -> dict:
    """Compose cohort-identical run summaries into one benchmark release.

    :param run_summaries: parsed ``summary.json`` dicts (each carries an embedded
        ``manifest`` — verified present, U4), one or more per corpus.
    :param default_mode: the production-default search mode whose metrics are the
        per-corpus headline; other modes become labelled ablations.
    :param composed_at: ISO timestamp (passed in — scripts can't call ``Date.now``).
    :param external_baselines: optional ``{dataset: [{model, value, source, ...}]}``
        cited (immutable) references shown side-by-side; never a projection of ours.
    :raises ComposeError: if the runs don't all share one ``config_cohort_key``,
        or (when ``require_comparable``) a default-mode run isn't ``comparable``.
    :returns: the release document (``release.v1`` schema).
    """
    if not run_summaries:
        raise ComposeError("no run summaries provided")

    # 1. All members must share one config-cohort key (the equivalence class).
    keyed: list[tuple[str, dict]] = []
    for s in run_summaries:
        manifest = s.get("manifest")
        if not isinstance(manifest, dict):
            raise ComposeError(
                f"run for dataset {s.get('dataset')!r} has no embedded manifest",
            )
        keyed.append((config_cohort_key(manifest), s))

    keys = {k for k, _ in keyed}
    if len(keys) != 1:
        # Build a legible diff so the operator sees WHY they're not one cohort.
        by_key: dict[str, list] = {}
        for k, s in keyed:
            by_key.setdefault(k[:12], []).append(s.get("dataset"))
        raise ComposeError(
            "runs are not cohort-identical (differ on the config-global key); "
            f"groups: {by_key}",
        )
    cohort_key = keys.pop()

    # 2. Reference cohort tuple (from the first member's manifest — all equal).
    ref_manifest = run_summaries[0]["manifest"]
    cohort = {
        "config_cohort_key": cohort_key,
        "git_sha": ref_manifest.get("git_sha"),
        "eval_protocol_hash": ref_manifest.get("eval_protocol_hash"),
        "policy_hash": ref_manifest.get("policy_hash"),
        "model_identity": _model_identity(ref_manifest.get("model_fingerprints")),
        # tempdoc 644: the realized engine set this release was measured under (also inside
        # model_identity; surfaced here as the legible read-path the homogeneity gate compares a
        # HEAD run against). All members share it — engine-set differences split config_cohort_key.
        "realized_engines": (ref_manifest.get("model_fingerprints") or {}).get("realized_engines"),
        "hardware": _hardware_projection(ref_manifest),
    }

    # 3. Per-corpus measured projection (one row per dataset) + ablations.
    measured: dict[str, dict] = {}
    ablations: dict[str, list] = {}
    missing_default: list[str] = []
    for s in run_summaries:
        ds = canonical_dataset_slug(s.get("dataset"))
        head = _measured_for_mode(s, default_mode)
        if head is not None:
            if require_comparable and not head["comparable"]:
                raise ComposeError(
                    f"default-mode run for {ds!r} is not comparable "
                    f"(reasons in summary); refusing to publish",
                )
            measured[ds] = head
        else:
            missing_default.append(ds)
        # Any non-default modes present become labelled ablations.
        for mode in (s.get("per_mode") or {}):
            if mode == default_mode:
                continue
            abl = _measured_for_mode(s, mode)
            if abl is not None:
                ablations.setdefault(ds, []).append(abl)

    return {
        "schema": RELEASE_SCHEMA,
        "schema_version": RELEASE_SCHEMA_VERSION,
        "release_id": release_id,
        "composed_at": composed_at,
        "default_mode": default_mode,
        "cohort": cohort,
        "measured": measured,
        "ablations": ablations,
        "external_baselines": external_baselines or {},
        # First-class negative-space statement (tempdoc 623 T-3 / §F): a release
        # must never imply the extraction front-half is measured.
        "coverage": {
            "measures": "retrieval ranking quality (per-corpus metrics above)",
            "does_not_measure": (
                "document extraction / OCR / VDU routing quality "
                "(see tempdoc 623 §F — extraction-quality sibling)"
            ),
        },
        "notes": {
            "missing_default_mode": missing_default,
            "tolerance_semantics": (
                "reproduction tolerance is the within-machine ±2sigma envelope, "
                "scoped to equivalent hardware/setup (tempdoc 623 F-alpha)"
            ),
        },
    }
