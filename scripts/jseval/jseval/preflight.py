"""Preflight checks — verify backend health and model identity before eval."""

from __future__ import annotations

import logging

import httpx

from .readiness import flatten_status

log = logging.getLogger(__name__)


def execute_preflight(
    base_url: str,
    *,
    timeout: float = 10.0,
) -> dict:
    """Check backend health, loaded models, GPU status, and index identity.

    Returns a structured dict suitable for agent consumption.
    """
    result: dict = {
        "status": "ok",
        "errors": [],
        "backend": {},
        "models": {},
        "gpu": {},
        "index": {},
    }

    # 1. Fetch /api/status (flatten nested worker fields for compat)
    status = _fetch_endpoint(base_url, "/api/status", timeout)
    if status is not None:
        status = flatten_status(status)
    if status is None:
        result["status"] = "unreachable"
        result["errors"].append("backend_unreachable: cannot reach /api/status")
        return result

    # Check Worker availability
    if not status.get("indexAvailable"):
        result["errors"].append(
            f"worker_unavailable: {status.get('indexStatusReason', 'unknown')}"
        )
    if status.get("knowledgeServerStartError"):
        result["errors"].append(
            f"worker_start_error: {status.get('knowledgeServerStartError')}"
        )

    meta = status.get("meta") or {}
    if meta.get("workerRpcStale"):
        result["errors"].append("worker_rpc_stale: Worker data is stale")

    # Backend info
    result["backend"] = {
        "uptime_ms": status.get("uptimeMs"),
        "index_available": status.get("indexAvailable"),
        "index_state": status.get("indexState"),
        "indexed_documents": status.get("indexedDocuments"),
        "index_base_path": status.get("indexBasePath"),
        "throughput_state": status.get("throughputWindowState"),
    }

    # Models
    result["models"] = {
        "embed_backend": status.get("embedBackend"),
        "embed_fingerprint_current": status.get("embeddingFingerprintCurrent"),
        "embed_fingerprint_stored": status.get("embeddingFingerprintStored"),
        "embed_compat_state": status.get("embeddingCompatState"),
        "splade_model_path": status.get("spladeModelPath"),
        "reranker_model_path": status.get("rerankerModelPath"),
    }

    # GPU
    embed_cuda = status.get("embedOrtCuda") or {}
    splade_cuda = status.get("spladeOrtCuda") or {}
    reranker_cuda = status.get("rerankerOrtCuda") or {}
    result["gpu"] = {
        "embed_cuda_available": embed_cuda.get("available"),
        "embed_cuda_attempted": embed_cuda.get("attempted"),
        "embed_cuda_failure": embed_cuda.get("failureReason"),
        "embed_gpu_layers": status.get("embedGpuLayers"),
        "splade_cuda_available": splade_cuda.get("available"),
        "reranker_cuda_available": reranker_cuda.get("available"),
    }

    # Enrichment coverage
    result["index"]["coverage"] = {
        "embedding_pct": status.get("embeddingCoveragePercent"),
        "splade_pct": status.get("spladeCoveragePercent"),
        "chunk_vector_pct": status.get("chunkVectorCoveragePercent"),
        "ner_completed": status.get("completedNerCount"),
        "ner_pending": status.get("pendingNerCount"),
    }

    # 2. Fetch /api/debug/commit-metadata for definitive model identity
    commit_meta = _fetch_endpoint(base_url, "/api/debug/commit-metadata", timeout)
    if commit_meta:
        result["index"]["commit_metadata"] = {
            "embedding_model_sha256": commit_meta.get("embedding_model_sha256"),
            "field_catalog_hash": commit_meta.get("field_catalog_hash"),
            "schema_fp": commit_meta.get("schema_fp"),
            "index_schema_fp": commit_meta.get("index_schema_fp"),
        }

    # F4: Model wiring validation — distinguish "path found" from "loaded and wired"
    wiring: dict[str, dict] = {}
    # Embedding: wired if embedOrtCuda was attempted (GPU path) or embedBackend is set
    embed_wired = bool(embed_cuda.get("attempted") or status.get("embedBackend"))
    wiring["embedding"] = {
        "wired": embed_wired,
        "gpu": embed_cuda.get("available", False),
        "compat": status.get("embeddingCompatState"),
    }
    # SPLADE: wired if spladeOrtCuda was attempted or spladeModelPath is non-empty
    splade_wired = bool(splade_cuda.get("attempted") or status.get("spladeModelPath"))
    wiring["splade"] = {
        "wired": splade_wired,
        "gpu": splade_cuda.get("available", False),
    }
    # NER: wired if nerModelPath is non-empty
    ner_wired = bool(status.get("nerModelPath"))
    wiring["ner"] = {
        "wired": ner_wired,
        "gpu": status.get("nerGpuEnabled", False),
    }
    # Reranker (360): wired if rerankerOrtCuda has any data (configured field exists
    # and is explicitly true or false — absence means initDeferredModels never reached it)
    reranker_has_status = "configured" in reranker_cuda
    wiring["reranker"] = {
        "wired": reranker_has_status,
        "gpu": reranker_cuda.get("available", False),
        "cpu_only": reranker_has_status and not reranker_cuda.get("configured", False),
    }

    # If model paths exist but wiring is absent, initDeferredModels likely failed.
    # Use presence of ORT CUDA status keys (not "attempted" — GPU init is lazy).
    init_failed = False
    if status.get("rerankerModelPath") and not reranker_has_status:
        init_failed = True
    splade_has_status = "configured" in splade_cuda
    if status.get("spladeModelPath") and not splade_has_status:
        init_failed = True
    if init_failed:
        result["errors"].append(
            "init_deferred_models_likely_failed: model paths configured but "
            "ORT status absent — check Worker log for schema mismatch or startup errors"
        )

    result["model_wiring"] = wiring

    # Determine overall status
    if result["errors"]:
        result["status"] = "degraded"

    return result


def format_console(result: dict) -> str:
    """Format preflight results for human-readable output."""
    lines: list[str] = []
    lines.append(f"Preflight: {result['status'].upper()}")

    if result["errors"]:
        for e in result["errors"]:
            lines.append(f"  ERROR: {e}")

    backend = result.get("backend", {})
    if backend:
        lines.append(f"\nBackend:")
        lines.append(f"  index_available: {backend.get('index_available')}")
        lines.append(f"  index_state: {backend.get('index_state')}")
        lines.append(f"  indexed_documents: {backend.get('indexed_documents')}")
        lines.append(f"  index_base_path: {backend.get('index_base_path')}")

    models = result.get("models", {})
    if models:
        lines.append(f"\nModels:")
        lines.append(f"  embed_backend: {models.get('embed_backend')}")
        lines.append(f"  embed_compat: {models.get('embed_compat_state')}")
        lines.append(f"  splade: {models.get('splade_model_path')}")
        lines.append(f"  reranker: {models.get('reranker_model_path')}")

    gpu = result.get("gpu", {})
    if gpu:
        lines.append(f"\nGPU:")
        lines.append(f"  embed_cuda: {gpu.get('embed_cuda_available')} "
                      f"(layers={gpu.get('embed_gpu_layers')})")
        lines.append(f"  splade_cuda: {gpu.get('splade_cuda_available')}")
        lines.append(f"  reranker_cuda: {gpu.get('reranker_cuda_available')}")
        if gpu.get("embed_cuda_failure"):
            lines.append(f"  embed_cuda_failure: {gpu.get('embed_cuda_failure')}")

    index = result.get("index", {})
    coverage = index.get("coverage", {})
    if coverage:
        lines.append(f"\nEnrichment coverage:")
        lines.append(f"  embedding: {coverage.get('embedding_pct', 0):.1f}%")
        lines.append(f"  splade: {coverage.get('splade_pct', 0):.1f}%")
        lines.append(f"  chunk_vector: {coverage.get('chunk_vector_pct', 0):.1f}%")
        ner_done = coverage.get('ner_completed', 0)
        ner_pend = coverage.get('ner_pending', 0)
        lines.append(f"  ner: {ner_done}/{ner_done + ner_pend}")

    wiring = result.get("model_wiring", {})
    if wiring:
        lines.append(f"\nModel wiring (initDeferredModels):")
        for name, info in wiring.items():
            wired = info.get("wired", False)
            gpu = info.get("gpu", False)
            extras = []
            if info.get("cpu_only"):
                extras.append("cpu-only")
            if info.get("compat"):
                extras.append(info["compat"])
            suffix = f" ({', '.join(extras)})" if extras else ""
            icon = "wired" if wired else "NOT wired"
            gpu_str = f", gpu={'yes' if gpu else 'no'}"
            lines.append(f"  {name}: {icon}{gpu_str}{suffix}")

    commit = index.get("commit_metadata", {})
    if commit:
        lines.append(f"\nIndex identity:")
        sha = commit.get("embedding_model_sha256")
        lines.append(f"  embedding_model_sha256: {sha[:16]}..." if sha else
                      "  embedding_model_sha256: (none)")

    return "\n".join(lines)


def _fetch_endpoint(base_url: str, path: str, timeout: float) -> dict | None:
    """Fetch a JSON endpoint, return None on failure."""
    try:
        with httpx.Client(base_url=base_url, timeout=timeout) as client:
            resp = client.get(path)
            resp.raise_for_status()
            return resp.json()
    except Exception as e:
        log.warning("Failed to fetch %s: %s", path, e)
        return None


# ===================================================================================
# Tempdoc 644 Axis 2 — instrument-integrity guard
# ===================================================================================
# An eval run is a measurement instrument; running with a different engine set than the
# one declared produces invalid data, not a degraded result. Before emitting numbers we
# compare the INTENDED engine set (derived from modes + flags) against the REALIZED one
# (read from /api/status) and fail closed on a mismatch, unless explicitly overridden.

# Server-resolved modes whose default pipeline runs the cross-encoder (the "default-on
# engine"). `hybrid` is resolved server-side (jseval.retriever.SERVER_MODES) and runs CE by
# default — the client never sends an explicit crossEncoderEnabled, so a worktree's missing
# reranker silently disables it (tempdoc 644 §Design U5).
_CE_BEARING_SERVER_MODES = frozenset({"hybrid"})


def _normalize_modes(modes) -> list[str]:
    if modes is None:
        return []
    if isinstance(modes, str):
        return [m.strip() for m in modes.split(",") if m.strip()]
    return [str(m).strip() for m in modes if str(m).strip()]


def derive_intended_engines(modes, *, cross_encoder: bool = False) -> set[str]:
    """Derive the engine set the pre-run guard should hard-check, from the run's modes + flags.

    Scoped to the **reranker** deliberately (tempdoc 644 §Design Axis 2 / U4 / live-debug):
    - The reranker is the documented silent-off trap (CE silently disabled → wrong-but-plausible
      hybrid numbers), and its realized signal (``rerankerModelPath``) is **wired at worker
      startup**, so it is reliably observable at the pre-ingest guard point.
    - ``dense``/``splade`` model-presence is NOT startup-stable — ``embedBackend`` /
      ``spladeModelPath`` only populate once the encoders initialize during enrichment, so
      asserting them pre-ingest produces **false refusals even when the models are present**
      (observed live). Their genuine absence instead surfaces as low embedding/splade *coverage*
      in the eval results (visible, not silent), so they are intentionally not hard-gated here.

    ``reranker`` is intended when ``--ce`` is set OR any mode is a CE-bearing server mode
    (``hybrid`` — CE-on by server default). Leg-isolation modes (vector/lexical/splade/dense)
    carry explicit ``crossEncoderEnabled:false`` and never intend the reranker.
    """
    mode_list = _normalize_modes(modes)
    if cross_encoder or any(m in _CE_BEARING_SERVER_MODES for m in mode_list):
        return {"reranker"}
    return set()


def assert_capabilities(
    status_or_url,
    intended: set[str],
    *,
    allow_degraded: bool = False,
    timeout: float = 10.0,
) -> dict:
    """Compare the INTENDED engine set against the REALIZED one from ``/api/status``.

    ``status_or_url`` is either a base URL (``str``, in which case ``/api/status`` is
    fetched) or an already-fetched status dict.

    Returns ``{ok, refusals[], warnings[], realized{}}``. ``ok`` is False when a refusal
    stands (engine intended but not realized) and ``allow_degraded`` is False.

    Realized signals are startup-stable, observable pre-query (tempdoc 644 U4):
    ``reranker`` ← ``rerankerModelPath`` non-empty; ``dense`` ← ``embedBackend`` non-empty;
    ``splade`` ← ``spladeModelPath`` non-empty. Device (``*OrtCuda.available``) is recorded
    and surfaced as a WARNING only — GPU resolution is out of 644's scope (T3-6); a CPU
    realization is never a refusal.
    """
    result: dict = {"ok": True, "refusals": [], "warnings": [], "realized": {}}

    if isinstance(status_or_url, str):
        raw = _fetch_endpoint(status_or_url, "/api/status", timeout)
        status = flatten_status(raw) if raw is not None else None
    elif status_or_url:
        status = flatten_status(status_or_url)
    else:
        status = None

    if status is None:
        result["refusals"].append(
            "backend_unreachable: cannot reach /api/status to verify the realized engine set"
        )
    else:
        reranker_path = status.get("rerankerModelPath") or ""
        embed_backend = status.get("embedBackend") or ""
        splade_path = status.get("spladeModelPath") or ""
        reranker_cuda = status.get("rerankerOrtCuda") or {}

        realized = {
            "reranker": (bool(reranker_path), {"model_path": reranker_path,
                                               "gpu": bool(reranker_cuda.get("available"))}),
            "dense": (bool(embed_backend), {"backend": embed_backend}),
            "splade": (bool(splade_path), {"model_path": splade_path}),
        }
        for engine in sorted(intended):
            is_realized, detail = realized.get(engine, (False, {}))
            result["realized"][engine] = {"realized": is_realized, **detail}
            if not is_realized:
                result["refusals"].append(
                    f"{engine}_intended_but_absent: the run intends '{engine}' but the backend "
                    f"reports it is not loaded (model not discovered). In an agent worktree this "
                    f"is the silent cross-encoder-off trap — the worktree's models/ holds only LFS "
                    f"pointer files (tempdoc 644). Fix: run on the main checkout, set "
                    f"JUSTSEARCH_MODELS_DIR=<main>/models, or pass --allow-degraded to accept "
                    f"degraded numbers."
                )
        # Device dimension: warning only (GPU deferred — tempdoc 644 T3-6).
        if "reranker" in intended and reranker_path and not reranker_cuda.get("available"):
            result["warnings"].append("reranker_cpu_only: reranker realized on CPU (no GPU)")

    if result["refusals"] and allow_degraded:
        result["warnings"].extend(result["refusals"])
        result["warnings"].append(
            "allow_degraded: proceeding despite the engine-set mismatch above"
        )
        result["refusals"] = []
    result["ok"] = not result["refusals"]
    return result
