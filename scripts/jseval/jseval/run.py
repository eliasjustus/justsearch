"""Single evaluation run orchestration."""

from __future__ import annotations

import logging
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import httpx

from . import ann_proof as ann_proof_mod
from . import artifacts as artifacts_mod
from . import comparability as comparability_mod
from . import corpora
from . import history as history_mod
from . import manifest as manifest_mod
from . import provenance
from . import readiness
from . import retriever
from . import scoring
from .types import ReadinessResult

log = logging.getLogger(__name__)

METRIC_CONTRACT = {
    "gain_function": "linear",
    "aggregation": "mean_of_per_query",
    "unjudged_policy": "not_relevant",
}

EXPECTED_COMPONENTS: dict[str, set[str]] = {
    "lexical": set(),                    # BM25 is implicit, not a tracked component
    "hybrid": {"dense"},                 # sparse is implicit; only dense is tracked
    "vector": {"dense"},
    "splade": {"splade"},
    "bm25_splade": {"splade"},           # sparse is implicit
    "dense_splade": {"dense", "splade"},
    "full": {"dense", "splade"},         # sparse is implicit
}


def _snapshot_models(base_url: str) -> dict | None:
    """Fetch model identity from /api/status at run start (335 item 10)."""
    try:
        with httpx.Client(base_url=base_url, timeout=5) as client:
            status = client.get("/api/status").json()
        # 384: GPU diagnostics and compatibility are now nested under worker
        w = status.get("worker", {})
        gpu_diag = w.get("gpu", {})
        compat = w.get("compatibility", {})
        embed_cuda = gpu_diag.get("embedOrtCuda") or {}
        splade_cuda = gpu_diag.get("spladeOrtCuda") or {}
        reranker_cuda = gpu_diag.get("rerankerOrtCuda") or {}
        # tempdoc 644: make the realized engine SET first-class in cohort identity so a
        # silently-degraded run (e.g. cross-encoder off in a worktree) forms a distinct cohort
        # and cannot be averaged with a CE-on baseline. `realized_engines` (presence) is
        # startup-stable identity; `reranker_gpu` (device) is recorded but stripped from the
        # comparison key (release._MODEL_EXECUTION_FLAGS), exactly like embed_gpu/splade_gpu.
        from .preflight import realized_engine_set
        return {
            "embed_backend": gpu_diag.get("embedBackend"),
            "embed_fingerprint": compat.get("embeddingFingerprintCurrent"),
            "embed_compat_state": compat.get("embeddingCompatState"),
            "embed_gpu": embed_cuda.get("available"),
            "splade_model_path": gpu_diag.get("spladeModelPath"),
            "splade_gpu": splade_cuda.get("available"),
            "reranker_model_path": gpu_diag.get("rerankerModelPath"),
            "reranker_gpu": reranker_cuda.get("available"),
            "ner_model_path": gpu_diag.get("nerModelPath"),
            "ner_gpu": gpu_diag.get("nerGpuEnabled"),
            "realized_engines": realized_engine_set(gpu_diag),
        }
    except Exception:
        log.debug("Failed to snapshot model identity from /api/status")
        return None


def _snapshot_search_config(base_url: str) -> dict | None:
    """Fetch active search config from /api/status at run start (343 item 0.4).

    Captures the search pipeline config that produced this run so it can be
    compared across runs (e.g., chunk-ON vs chunk-OFF, balanced vs BM25-dom).
    """
    try:
        with httpx.Client(base_url=base_url, timeout=5) as client:
            status = client.get("/api/status").json()
        sc = status.get("searchConfig", {})
        return {
            "chunk_aware_enabled": sc.get("chunkAwareEnabled"),
            "cc_weight_sparse": sc.get("ccWeightSparse"),
            "cc_weight_dense": sc.get("ccWeightDense"),
            "cc_weight_splade": sc.get("ccWeightSplade"),
            "branch_cc_weight_whole": sc.get("branchCcWeightWhole"),
            "branch_cc_weight_chunk": sc.get("branchCcWeightChunk"),
            "branch_chunk_min_weight_multiplier": sc.get(
                "branchChunkMinWeightMultiplier"
            ),
            "title_boost": sc.get("titleBoost"),
            "entity_boost": sc.get("entityBoost"),
            "query_classification_enabled": sc.get(
                "queryClassificationEnabled"
            ),
        }
    except Exception:
        log.debug("Failed to snapshot search config from /api/status")
        return None


def execute_run(
    dataset_name: str,
    base_url: str,
    modes: list[str],
    *,
    base_dir: Path | None = None,
    top_k: int = 10,
    max_queries: int = 0,
    embedding_enabled: bool = False,
    splade_enabled: bool = False,
    lambdamart_enabled: bool = False,
    cross_encoder_enabled: bool = False,
    skip_readiness: bool = False,
    allow_errors: bool = False,
    output_dir: Path | None = None,
    context_coverage: bool = False,
    coverage_thresholds: list[float] | None = None,
    ingest_summary: dict | None = None,
    history_db: Path | None = None,
    pipeline_summary: dict | None = None,
    env_overrides: dict[str, str] | None = None,
) -> dict:
    """Execute a full evaluation run.

    Returns a summary dict with aggregate metrics, comparability, and timing.
    """
    # E-J-N11: capture environment fingerprint once per run. Informational only
    # (never used as a comparability gate). Safe to run early — best-effort with
    # graceful degradation.
    env_fingerprint = _capture_env_fingerprint()

    # [335 item 20] Skip dataset loading for ingest-only runs (no modes = no queries).
    if not modes:
        summary: dict = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "git_sha": _get_git_sha(),
            "dataset": dataset_name,
            "modes": [],
            "per_mode": {},
            "query_count": 0,
        }
        if ingest_summary:
            summary["ingest"] = ingest_summary
        if pipeline_summary:
            summary["pipeline_timing"] = pipeline_summary
        models_snapshot = _snapshot_models(base_url)
        if models_snapshot:
            summary["models"] = models_snapshot
        search_config = _snapshot_search_config(base_url)
        if search_config:
            summary["search_config"] = search_config
        if env_overrides:
            summary["env_overrides"] = env_overrides
        if env_fingerprint:
            summary["env_fingerprint"] = env_fingerprint
        return summary

    # 1. Load dataset
    query_records, qrels, meta = corpora.load(dataset_name, base_dir)

    # Filter to qrels-only queries (don't waste API calls on unjudged queries)
    query_records = {qid: qr for qid, qr in query_records.items() if qid in qrels}

    # Cap query count for fast iteration
    if max_queries > 0:
        query_records = dict(list(query_records.items())[:max_queries])

    # Extract plain text dict for retriever (which expects dict[str, str])
    query_texts = {qid: qr.text for qid, qr in query_records.items()}

    log.info(
        "Loaded dataset %s: %d queries (of %d total), %d docs",
        meta.name, len(query_records), meta.query_count, meta.doc_count,
    )

    # [335 item 10] Snapshot model identity at run start.
    models_snapshot = _snapshot_models(base_url)

    # 2. Readiness check
    readiness_result = ReadinessResult(passed=True)
    if not skip_readiness:
        readiness_result = readiness.check_search_ready(
            base_url, embedding_enabled, splade_enabled, lambdamart_enabled,
        )
        if not readiness_result.passed:
            log.warning("Readiness failed: %s", readiness_result.failure_reasons)

    # 3. For each mode: retrieve → score → provenance → ANN proof → comparability
    mode_results: dict[str, dict] = {}
    for mode in modes:
        log.info("Evaluating mode: %s", mode)
        debug = embedding_enabled  # need debug=true for ANN proof evidence

        # Inject cross-encoder flag into pipeline dict when --ce is set.
        ce_pipeline_override = None
        if cross_encoder_enabled:
            base_pipeline = retriever.MODE_PIPELINES.get(mode)
            if base_pipeline:
                ce_pipeline_override = {**base_pipeline, "crossEncoderEnabled": True}

        scored_docs, raw_responses = retriever.retrieve(
            query_texts, base_url, mode, top_k, debug,
            pipeline=ce_pipeline_override,
            allow_errors=allow_errors, include_excerpts=context_coverage,
        )

        # Filter qrels to only the queries that were actually evaluated,
        # so ir_measures aggregates over the correct denominator.
        eval_qrels = {qid: qrels[qid] for qid in query_texts if qid in qrels}
        aggregate = scoring.evaluate(eval_qrels, scored_docs)
        per_query = scoring.evaluate_per_query(eval_qrels, scored_docs)

        query_evidences = [
            provenance.extract_query_evidence(r)
            for r in raw_responses
            if r.get("error") is None
        ]
        run_evidence = provenance.aggregate_run_evidence(query_evidences)

        pt = _compute_pipeline_tracking(mode, run_evidence)
        ann = ann_proof_mod.compute_ann_proof(
            run_evidence, embedding_enabled,
            mode=mode, pipeline_tracking=pt,
        )

        error_count = run_evidence.get("error_count", 0)
        comp = comparability_mod.determine_comparability(
            readiness_result, ann, error_count, len(query_texts),
        )

        # Context coverage (optional)
        coverage_result = None
        if context_coverage:
            from . import context_coverage as cc_mod

            coverages = []
            for raw_resp in raw_responses:
                if raw_resp.get("error"):
                    continue
                qid = raw_resp["query_id"]
                qr = query_records.get(qid)
                if not qr:
                    continue
                evidence = cc_mod.extract_expected_evidence(qr.annotations, qr.text)
                cov = cc_mod.compute_query_coverage(
                    raw_resp, evidence, qrels.get(qid, {}),
                )
                coverages.append(cov)
            coverage_result = cc_mod.summarize_coverage(
                coverages, coverage_thresholds or [0.25, 0.5],
            )

        latency_stats = _compute_latency_stats(raw_responses)
        score_stats = _compute_score_stats(raw_responses, top_k)

        # Promote the always-present latency-stage p50s into aggregate_metrics so the perf-latency
        # family flows + calibrates like a quality metric (tempdoc 640 metric-family registry). The
        # cross-encoder is the dominant, noise-robust cost (§C-2); tempdoc 647 completes the
        # decomposition by also promoting the retrieval stage (the only other stage present on every
        # query — chunk-merge/branch-fusion/lambdamart are query-conditional and stay report-only in
        # stage_timing_stats, alongside the `unaccounted_ms` remainder + per-stage shares).
        _stage_stats = run_evidence.get("stage_timing_stats") or {}
        for _stage_key, _metric_key in (
            ("cross_encoder_ms", "ce_p50_ms"),
            ("retrieval_ms", "retrieval_p50_ms"),
        ):
            _p50 = (_stage_stats.get(_stage_key) or {}).get("p50")
            if isinstance(_p50, (int, float)):
                aggregate[_metric_key] = float(_p50)

        mode_results[mode] = {
            "aggregate_metrics": aggregate,
            "per_query_metrics": per_query,
            "scored_docs": scored_docs,
            "raw_responses": raw_responses,
            "run_evidence": run_evidence,
            "pipeline_tracking": pt,
            "ann_proof": ann,
            "comparability": comp,
            "context_coverage": coverage_result,
            "latency_stats": latency_stats,
            "score_stats": score_stats,
        }

        log.info(
            "  %s: nDCG@10=%.4f  comparable=%s",
            mode,
            aggregate.get("nDCG@10", 0),
            comp.comparable,
        )

    # 4. Build summary + run manifest (tempdoc 400 LR1-a)
    search_config = _snapshot_search_config(base_url)
    state_snapshots = manifest_mod.capture_state_snapshots(base_url)
    # Phase 2.2b: point compute_manifest at the data dir so calibrated
    # envelopes (written by `jseval calibrate`) are auto-embedded when the
    # run's cohort_hash matches a sidecar in
    # <data_dir>/non_determinism_envelopes/.
    envelope_data_dir_env = os.environ.get("JUSTSEARCH_DATA_DIR")
    envelope_data_dir = Path(envelope_data_dir_env) if envelope_data_dir_env else None
    # Phase 6 / 6.5: manifest override for LR5-d synthetic bisection.
    # When JUSTSEARCH_MANIFEST_OVERRIDE is set AND the
    # JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS safety flag is "1", skip
    # the endpoint-derived manifest computation and use the override
    # instead. Override manifests MUST mark `synthetic: true` so they
    # don't pollute the cross-cohort index for real-run consumers.
    override_path = os.environ.get("JUSTSEARCH_MANIFEST_OVERRIDE")
    override_safe = os.environ.get("JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS") == "1"
    if override_path and override_safe:
        log.warning(
            "Using JUSTSEARCH_MANIFEST_OVERRIDE=%s — this is a synthetic "
            "run for LR5-d bisection. Manifest identity is NOT derived "
            "from observed state; cohort_hash comes from the override.",
            override_path,
        )
        import json as _json
        run_manifest = _json.loads(Path(override_path).read_text(encoding="utf-8"))
        run_manifest["synthetic"] = True
        run_manifest["synthetic_source"] = override_path
    elif override_path and not override_safe:
        raise RuntimeError(
            "JUSTSEARCH_MANIFEST_OVERRIDE is set but "
            "JUSTSEARCH_MANIFEST_OVERRIDE_DANGEROUS != '1'. This flag "
            "bypasses identity derivation and produces cohort-misleading "
            "manifests; requires explicit opt-in."
        )
    else:
        run_manifest = manifest_mod.compute_manifest(
            dataset_name=dataset_name,
            meta=meta,
            env_fingerprint=env_fingerprint,
            models_snapshot=models_snapshot,
            eval_protocol=METRIC_CONTRACT,
            state_snapshots=state_snapshots,
            workflow_run_id=os.environ.get("JUSTSEARCH_WORKFLOW_RUN_ID"),
            envelope_data_dir=envelope_data_dir,
        )
    summary = _build_summary(dataset_name, modes, mode_results, meta, qrels,
                             ingest_summary, pipeline_summary, models_snapshot,
                             search_config, env_overrides, env_fingerprint,
                             run_manifest=run_manifest, base_dir=base_dir)

    # 5. Write artifacts + append history
    if output_dir:
        run_dir = artifacts_mod.write_run(
            summary, mode_results, qrels, Path(output_dir), query_records,
            data_dir=envelope_data_dir,
        )
        log.info("Artifacts written to %s", run_dir)

        history_dir = history_db or Path(output_dir)
        run_manifest_hash = run_manifest.get("manifest_hash") if isinstance(run_manifest, dict) else None
        envelope = run_manifest.get("non_determinism_envelope") if isinstance(run_manifest, dict) else None
        # Perf families trended alongside quality (tempdoc 640 R3): per-run throughput + the derived
        # resident footprint are run-level; CE-stage p50 is per-mode (from aggregate_metrics).
        _run_metrics = summary.get("run_metrics") or {}
        from . import perf_gate as _perf_gate
        _footprint = _perf_gate.derive_resident_model_bytes(run_manifest)
        for mode in modes:
            mr = mode_results[mode]
            history_mod.append_run(
                summary, mode, mr["aggregate_metrics"],
                mr["comparability"].comparable,
                history_dir,
                mean_latency_ms=mr["latency_stats"].get("mean_ms"),
                context_hit_rate=(mr.get("context_coverage") or {}).get(
                    "mean_best_term_coverage"),
                manifest_hash=run_manifest_hash,
                envelope=envelope,
                perf_metrics={
                    "ce_p50_ms": (mr["aggregate_metrics"] or {}).get("ce_p50_ms"),
                    "primary_docs_s": _run_metrics.get("primary_docs_s"),
                    "enrich_docs_s": _run_metrics.get("enrich_docs_s"),
                    "resident_bytes": _footprint,
                    # tempdoc 647: trend the latency decomposition — retrieval from aggregate_metrics
                    # (promoted), the unaccounted remainder from stage_timing_stats (report-only, so it
                    # is trended without being promoted/gated).
                    "retrieval_p50_ms": (mr["aggregate_metrics"] or {}).get("retrieval_p50_ms"),
                    "unaccounted_p50_ms": (
                        (mr["run_evidence"].get("stage_timing_stats") or {}).get("unaccounted_ms")
                        or {}
                    ).get("p50"),
                },
            )

        # Phase 3 LR4-a: invoke every registered projection against the
        # run artifacts. Failures are quarantined per-projection (see
        # jseval.projections.base.run_all) so a single broken projection
        # cannot tank the whole run.
        # Phase 6 / 6.1: per-projection exceptions now also emit a
        # synthetic contract.violation event (aggregated by LR6-c) so
        # silent failures surface in the nightly gate.
        from jseval.projections import run_all_discovered
        skip_csv = os.environ.get("JUSTSEARCH_SKIP_PROJECTIONS", "")
        skip_set = frozenset(
            name.strip() for name in skip_csv.split(",") if name.strip()
        )
        projection_summary = run_all_discovered(run_dir, skip=skip_set)
        if projection_summary:
            log.info("Projections ran: %s", {
                name: info["status"] for name, info in projection_summary.items()
            })

        # Phase 4 LR5-d: register the run in the cohort manifest index
        # so future bisection invocations can look up cached runs by
        # synthetic manifest hash.
        if run_manifest_hash:
            from jseval import bisection
            try:
                bisection.register_run(
                    Path(output_dir),
                    manifest_hash=run_manifest_hash,
                    run_dir=run_dir,
                    git_sha=run_manifest.get("git_sha") if isinstance(run_manifest, dict) else None,
                    dataset=dataset_name,
                    mode=",".join(modes) if modes else None,
                    timestamp=summary.get("timestamp"),
                )
            except OSError as e:
                log.debug("bisection register_run failed: %s", e)

    return summary


def _build_summary(
    dataset_name: str,
    modes: list[str],
    mode_results: dict,
    meta,
    qrels: dict,
    ingest_summary: dict | None = None,
    pipeline_summary: dict | None = None,
    models_snapshot: dict | None = None,
    search_config: dict | None = None,
    env_overrides: dict[str, str] | None = None,
    env_fingerprint: dict | None = None,
    run_manifest: dict | None = None,
    base_dir: Path | None = None,
) -> dict:
    summary: dict = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "git_sha": _get_git_sha(),
        "dataset": dataset_name,
        "modes": modes,
        "doc_count": meta.doc_count,
        "query_count": meta.query_count,
        "metric_contract": METRIC_CONTRACT,
        "qrels_summary": _compute_qrels_summary(qrels),
        "corpus_identity": _get_corpus_identity(dataset_name, meta, qrels, base_dir),
        "per_mode": {
            mode: {
                "aggregate_metrics": mr["aggregate_metrics"],
                "ann_proof_status": mr["ann_proof"].status,
                "comparable": mr["comparability"].comparable,
                "comparability_reasons": mr["comparability"].reasons,
                "error_count": mr["run_evidence"].get("error_count", 0),
                "pipeline_tracking": mr["pipeline_tracking"],
                "latency_stats": mr["latency_stats"],
                "score_stats": mr["score_stats"],
                "stage_timing_stats": mr["run_evidence"].get("stage_timing_stats", {}),
                **({"context_coverage": mr["context_coverage"]}
                   if mr.get("context_coverage") else {}),
            }
            for mode, mr in mode_results.items()
        },
    }
    if ingest_summary:
        summary["ingest"] = ingest_summary
        # Per-run metric family (tempdoc 640): surface throughput as first-class `run_metrics` so it
        # flows into the release + history like quality, instead of being read out of `ingest` ad hoc.
        # (Footprint/resident_bytes is derived from the manifest at gate/compose time, not here.)
        _prim = (ingest_summary.get("pipeline_summary") or {}).get("primary_indexing") or {}
        _run_metrics: dict[str, float] = {}
        if isinstance(_prim.get("docs_per_s"), (int, float)):
            _run_metrics["primary_docs_s"] = float(_prim["docs_per_s"])
        if isinstance(ingest_summary.get("docs_per_sec"), (int, float)):
            _run_metrics["enrich_docs_s"] = float(ingest_summary["docs_per_sec"])
        if _run_metrics:
            summary["run_metrics"] = _run_metrics
    if pipeline_summary:
        summary["pipeline_timing"] = pipeline_summary
    if models_snapshot:
        summary["models"] = models_snapshot
    if search_config:
        summary["search_config"] = search_config
    if env_overrides:
        summary["env_overrides"] = env_overrides
    if env_fingerprint:
        summary["env_fingerprint"] = env_fingerprint
    if run_manifest:
        summary["manifest"] = run_manifest
    return summary


def _compute_latency_stats(raw_responses: list[dict]) -> dict:
    """Compute latency distribution from per-query tookMs values."""
    values = sorted(
        r["tookMs"] for r in raw_responses
        if r.get("error") is None and r.get("tookMs") is not None
    )
    n = len(values)
    if n == 0:
        return {"query_count": 0}

    def _percentile(sorted_vals: list, p: float) -> float:
        idx = int(p / 100 * (len(sorted_vals) - 1) + 0.5)
        return sorted_vals[min(idx, len(sorted_vals) - 1)]

    return {
        "query_count": n,
        "mean_ms": round(sum(values) / n, 1),
        "p50_ms": _percentile(values, 50),
        "p95_ms": _percentile(values, 95),
        "p99_ms": _percentile(values, 99),
        "max_ms": values[-1],
        "min_ms": values[0],
    }


def _compute_score_stats(raw_responses: list[dict], top_k: int) -> dict:
    """Compute score distribution from per-query hit scores."""
    top1_scores: list[float] = []
    topk_means: list[float] = []
    for r in raw_responses:
        if r.get("error"):
            continue
        hits = r.get("results", [])[:top_k]
        scores = [h["score"] for h in hits if "score" in h]
        if scores:
            top1_scores.append(scores[0])
            topk_means.append(sum(scores) / len(scores))
    return {
        "mean_top1_score": round(sum(top1_scores) / len(top1_scores), 4) if top1_scores else None,
        "mean_topk_score": round(sum(topk_means) / len(topk_means), 4) if topk_means else None,
    }


def _compute_qrels_summary(qrels: dict) -> dict:
    max_rel = 0
    with_relevant = 0
    for docs in qrels.values():
        rels = [r for r in docs.values() if r > 0]
        if rels:
            with_relevant += 1
            max_rel = max(max_rel, max(rels))
    return {
        "relevance_mode": "binary" if max_rel <= 1 else "graded",
        "query_count": len(qrels),
        "max_relevance": max_rel,
        "queries_with_relevant": with_relevant,
    }


def _compute_pipeline_tracking(mode: str, run_evidence: dict) -> dict:
    expected = EXPECTED_COMPONENTS.get(mode, set())
    component_counts = run_evidence.get("component_status_counts", {})
    observed: set[str] = set()
    for comp, statuses in component_counts.items():
        if statuses.get("executed", 0) > 0:
            observed.add(comp)

    mismatches: list[str] = []
    for comp in sorted(expected - observed):
        mismatches.append(f"requested_{comp}_but_not_observed")
    for comp in sorted((observed - expected) & {"dense", "sparse", "splade"}):
        mismatches.append(f"unexpected_{comp}_observed")

    return {
        "requested": sorted(expected),
        "observed": sorted(observed),
        "mismatch_reasons": mismatches,
    }


def _get_corpus_identity(
    dataset_name: str | None = None,
    meta=None,
    qrels: dict | None = None,
    base_dir: Path | None = None,
) -> dict:
    """Corpus identity for the run manifest + benchmark-release pin (tempdoc 623 ③).

    Computes a real content **signature** (the previously-dead seam): a
    ``mixed/golden`` corpus is pinned by ``sha256(corpus.jsonl + qrels/test.tsv)``;
    a BEIR corpus is pinned by ``sha256({ir_datasets_id, qrels})`` — its ``.txt``
    files materialize only after ingest, so the stable inputs are the dataset id
    + the binary qrels. An explicit ``JUSTSEARCH_CORPUS_SIGNATURE`` still wins (an
    operator override); the computed value fills the seam when it is unset. The
    release composer (:func:`jseval.release._corpus_source`) reads ``signature``
    into ``corpus_source.sha256`` unchanged.
    """
    env_sig = os.environ.get("JUSTSEARCH_CORPUS_SIGNATURE")
    signature = env_sig
    if env_sig is None and meta is not None:
        try:
            source = getattr(meta, "source", None)
            if source in ("mixed", "golden") and dataset_name:
                # Single corpus-signature definition (tempdoc 635): the same
                # function the corpus metadata + agent-eval use, so all three
                # carry one identity (conform, don't fork).
                from . import corpus_identity
                dataset_dir = (base_dir or corpora._default_base_dir()) / dataset_name
                sig = corpus_identity.corpus_signature(dataset_dir)
                if sig is not None:
                    signature = sig
            elif source == "beir":
                ir_id = corpora.BEIR_DATASETS.get(getattr(meta, "name", None))
                if ir_id is not None:
                    signature = manifest_mod._sha256_canonical(
                        {"ir_datasets_id": ir_id, "qrels": qrels or {}}
                    )
        except OSError as e:  # best-effort — a missing/unreadable file never fails a run
            log.debug("corpus signature computation failed (best-effort): %s", e)
            signature = env_sig
    return {
        "profile_id": os.environ.get("JUSTSEARCH_CORPUS_PROFILE_ID"),
        "signature": signature,
    }


def _get_git_sha() -> str | None:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            capture_output=True, text=True, timeout=5,
        )
        return result.stdout.strip() or None
    except Exception:
        return None


def _capture_env_fingerprint() -> dict | None:
    """E-J-N11: capture machine-state snapshot for post-hoc variance context.

    Informational only — NEVER used as a comparability gate. Best-effort:
    probe failures yield null fields or an empty dict, never a run failure.
    """
    try:
        from . import env_fingerprint as ef
        return ef.capture_env_fingerprint()
    except Exception as e:
        log.debug("env_fingerprint capture failed (non-fatal): %s", e)
        return None
