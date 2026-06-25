"""CLI entry points (run, compare, ab, requery, materialize, mix)."""

from __future__ import annotations

import json
import logging
import os
import sys
from pathlib import Path

import click
import httpx

from ._paths import DEFAULT_EVAL_RESULTS

log = logging.getLogger(__name__)

_DEFAULT_BASE_URL = f"http://127.0.0.1:{os.environ.get('JUSTSEARCH_API_PORT', '33221')}"


@click.group()
@click.option("--verbose", "-v", is_flag=True, help="Enable debug logging.")
@click.option("--json", "json_mode", is_flag=True, help="Emit JSON to stdout.")
@click.pass_context
def main(ctx, verbose: bool, json_mode: bool) -> None:
    """JustSearch search evaluation toolkit."""
    ctx.ensure_object(dict)
    ctx.obj["json"] = json_mode
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        level=level,
        format="%(asctime)s %(levelname)-8s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    # Suppress httpcore/httpx request-level logging — it floods the output
    # with ~20 lines per HTTP call, drowning progress logging (14:1 noise ratio).
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)


@main.command("run")
@click.option("--dataset", default=None, help="Dataset name (e.g., scifact, golden/desktop-v1).")
@click.option("--modes", default=None, help="Comma-separated modes (e.g., lexical,hybrid).")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--output-dir", type=click.Path(), default=str(DEFAULT_EVAL_RESULTS), show_default=True)
@click.option("--top-k", default=10, show_default=True)
@click.option("--embedding/--no-embedding", default=False, help="Enable dense/hybrid readiness checks.")
@click.option("--splade/--no-splade", default=False)
@click.option("--allow-errors", is_flag=True, help="Continue on query errors.")
@click.option("--max-queries", default=0, show_default=True, help="Cap queries for fast iteration (0 = all).")
@click.option("--lambdamart/--no-lambdamart", default=False)
@click.option("--ce/--no-ce", "cross_encoder", default=False, help="Enable cross-encoder reranking in all modes.")
@click.option("--context-coverage", is_flag=True, help="Compute excerpt coverage metrics.")
@click.option("--thresholds", default="0.25,0.5", show_default=True, help="Coverage threshold rates (comma-separated).")
@click.option("--history-db", type=click.Path(), default=None, help="Shared history database path.")
@click.option("--corpus-dir", type=click.Path(), default=None, help="Override corpus materialization directory.")
@click.option("--skip-ingest", is_flag=True, help="Skip materialization and ingestion (query only).")
@click.option("--pipeline", is_flag=True, help="Wait for ALL enrichments (embed, SPLADE, chunks, NER).")
@click.option("--timeline", "timeline_path", type=click.Path(), default=None, help="Record status snapshots to TSV.")
@click.option("--start-backend", is_flag=True, help="Start runHeadlessEval, run eval, stop when done.")
@click.option("--llm", is_flag=True, help="Enable LLM (Brain/llama-server) in the backend (requires --start-backend).")
@click.option("--qu", is_flag=True, help="Enable Query Understanding (requires --llm).")
@click.option("--filter-norm", is_flag=True, help="Enable filter value normalization (requires --llm).")
@click.option("--clean", is_flag=True, help="Clean data dir before starting backend (requires --start-backend).")
@click.option("--reset", is_flag=True, help="Reset index via API before ingestion (requires running backend in eval mode).")
@click.option("--cpu", is_flag=True, help="Force CPU-only mode (disable GPU for all ONNX encoders). For testing CPU inference paths on GPU machines.")
@click.option("--config", "config_path", type=click.Path(exists=True), default=None, help="YAML run config file.")
@click.option("--warmup", "warmup_count", type=int, default=0, show_default=True,
              help=(
                  "Run N warmup iterations of the full pipeline before the timed run (E-J-N10). "
                  "Warmup iterations share the same output-dir parent but land in _warmup_<N>/ "
                  "subdirs and do not print summaries. Use with --start-backend to warm up "
                  "OS page cache, CUDA kernel cache, and GPU graph cache on a cold dev box "
                  "before the measured run. "
                  "NOT RECOMMENDED AS DEFAULT: tempdoc 393 section 2.5 measured CV=4.0% "
                  "without warmup and found that outliers land on arbitrary runs (not "
                  "specifically run 1), so --warmup 1 doubles measurement time without a "
                  "validated CV improvement. Keep default=0 unless the 4.4%-to-1.5% claim "
                  "is validated with N=5+ matched pairs (currently deferred)."
              ))
@click.option("--json", "json_flag", is_flag=True, hidden=True, help="Alias for top-level --json.")
@click.option(
    "--skip-projection", "skip_projections", multiple=True,
    help="Skip named projection at end-of-run (repeatable). Tempdoc 400 "
         "post-implementation-critique 6.1 — useful when iterating on "
         "a single flaky projection without losing other signals.",
)
@click.pass_context
def cmd_run(ctx, dataset, modes, base_url, output_dir, top_k, embedding, splade, lambdamart, cross_encoder, allow_errors, max_queries, context_coverage, thresholds, history_db, corpus_dir, skip_ingest, pipeline, timeline_path, start_backend, llm, qu, filter_norm, clean, reset, cpu, config_path, warmup_count, json_flag, skip_projections):
    """Execute an evaluation run."""
    if json_flag:
        ctx.obj["json"] = True
    from . import ingest as ingest_mod
    from . import run as run_module

    # Apply YAML config if provided (item 11).
    backend_proc = None
    env_overrides: dict[str, str] = {}
    if config_path:
        from . import run_config
        config = run_config.load_config(Path(config_path))
        cli_args = run_config.config_to_cli_args(config)
        dataset = cli_args.get("dataset", dataset)
        modes = cli_args.get("modes", modes)
        embedding = cli_args.get("embedding", embedding)
        splade = cli_args.get("splade", splade)
        pipeline = cli_args.get("pipeline", pipeline)
        top_k = cli_args.get("top_k", top_k)
        max_queries = cli_args.get("max_queries", max_queries)
        output_dir = cli_args.get("output_dir", output_dir)
        context_coverage = cli_args.get("context_coverage", context_coverage)
        clean = cli_args.get("backend_clean", clean)
        env_overrides = run_config.apply_env_overrides(config)

    # Validate required args (either from CLI or config).
    if not dataset:
        click.echo("Error: --dataset is required (via CLI or --config)", err=True)
        sys.exit(1)
    if not modes and max_queries != 0:
        click.echo("Error: --modes is required (via CLI or --config) when --max-queries != 0", err=True)
        sys.exit(1)

    # Phase 6 / 6.1: forward --skip-projection through env so run.py can
    # pass it to run_all_discovered without plumbing another param
    # through _run_iteration's already-long signature.
    if skip_projections:
        os.environ["JUSTSEARCH_SKIP_PROJECTIONS"] = ",".join(skip_projections)

    # --pipeline implies --embedding --splade
    if pipeline:
        embedding = True
        splade = True

    # --reset and --start-backend are mutually exclusive (355).
    if reset and start_backend:
        click.echo("Error: --reset and --start-backend are mutually exclusive", err=True)
        sys.exit(1)

    # --llm requires --start-backend (369).
    if llm and not start_backend:
        click.echo("Error: --llm requires --start-backend", err=True)
        sys.exit(1)

    # 369: --llm injects autostart env var so the backend starts llama-server.
    # 366: also enable full GPU offload (99 layers) — CPU-only is 5-10x slower.
    if llm:
        env_overrides["JUSTSEARCH_AI_AUTOSTART_ENABLED"] = "true"
        env_overrides.setdefault("JUSTSEARCH_GPU_LAYERS", "99")

    # 366: --qu enables Query Understanding (experimental, requires --llm).
    if qu and not llm:
        click.echo("Error: --qu requires --llm", err=True)
        sys.exit(1)
    if qu:
        env_overrides["JUSTSEARCH_QU_ENABLED"] = "true"

    # 366: --filter-norm enables filter value normalization (experimental, requires --llm).
    if filter_norm and not llm:
        click.echo("Error: --filter-norm requires --llm", err=True)
        sys.exit(1)
    if filter_norm:
        env_overrides["JUSTSEARCH_FILTER_NORM_ENABLED"] = "true"

    # 381: --cpu disables GPU for all ONNX encoders (test CPU inference paths on GPU machines).
    if cpu:
        if llm:
            click.echo("Error: --cpu and --llm are mutually exclusive (LLM requires GPU)", err=True)
            sys.exit(1)
        env_overrides["JUSTSEARCH_GPU_ENABLED"] = "false"

    # Validate warmup count
    if warmup_count < 0:
        click.echo("Error: --warmup must be >= 0", err=True)
        sys.exit(1)

    # Run warmup iterations (if any), then the timed run.
    # Each iteration gets its own backend lifecycle when --start-backend is set,
    # so warmup runs genuinely exercise cold-start paths (OS cache, CUDA kernel
    # cache, GPU graph cache) before the timed iteration.
    total_iterations = warmup_count + 1
    base_output_dir = Path(output_dir)
    for iter_idx in range(total_iterations):
        is_warmup = iter_idx < warmup_count
        iter_output_dir = (
            base_output_dir / f"_warmup_{iter_idx + 1}" if is_warmup else base_output_dir
        )
        if warmup_count > 0:
            banner = (
                f"WARMUP iteration {iter_idx + 1}/{warmup_count} "
                f"(results → {iter_output_dir}; NOT reported)"
                if is_warmup
                else f"TIMED iteration (after {warmup_count} warmup{'s' if warmup_count > 1 else ''})"
            )
            log.info("=" * 72)
            log.info(banner)
            log.info("=" * 72)

        _run_iteration(
            ctx=ctx,
            dataset=dataset,
            modes=modes,
            base_url=base_url,
            output_dir=str(iter_output_dir),
            top_k=top_k,
            embedding=embedding,
            splade=splade,
            lambdamart=lambdamart,
            cross_encoder=cross_encoder,
            allow_errors=allow_errors,
            max_queries=max_queries,
            context_coverage=context_coverage,
            thresholds=thresholds,
            history_db=history_db,
            corpus_dir=corpus_dir,
            skip_ingest=skip_ingest,
            pipeline=pipeline,
            timeline_path=timeline_path,
            start_backend=start_backend,
            llm=llm,
            clean=clean,
            reset=reset,
            env_overrides=env_overrides,
            json_flag=json_flag,
            is_warmup=is_warmup,
        )


def _run_iteration(
    *,
    ctx,
    dataset,
    modes,
    base_url,
    output_dir,
    top_k,
    embedding,
    splade,
    lambdamart,
    cross_encoder,
    allow_errors,
    max_queries,
    context_coverage,
    thresholds,
    history_db,
    corpus_dir,
    skip_ingest,
    pipeline,
    timeline_path,
    start_backend,
    llm,
    clean,
    reset,
    env_overrides,
    json_flag,
    is_warmup,
):
    """Run a single pipeline iteration (start backend → ingest → eval → stop backend).

    For warmup iterations, summaries/JSON are suppressed on stdout so that only
    the final timed iteration's result reaches consumers (e.g., CI parsers).
    """
    backend_proc = None
    effective_base_url = base_url

    if start_backend:
        from . import backend as backend_mod
        backend_proc = backend_mod.start_backend(
            clean=clean, env_overrides=env_overrides or None, llm=llm,
        )
        port = env_overrides.get(
            "JUSTSEARCH_API_PORT", os.environ.get("JUSTSEARCH_API_PORT", "33221")
        )
        effective_base_url = f"http://127.0.0.1:{port}"

    if reset:
        _reset_index(effective_base_url)

    if not start_backend:
        _check_build_freshness(effective_base_url)

    from .types import IngestConfig
    backend_popen = backend_proc.proc if backend_proc else None
    process_check = (lambda: backend_popen.poll() is None) if backend_popen else None
    ingest_config = IngestConfig(
        base_url=effective_base_url,
        dense_enabled=embedding,
        splade_enabled=splade,
        pipeline=pipeline,
        timeline_path=Path(timeline_path) if timeline_path else None,
        json_mode=ctx.obj.get("json", False) or json_flag,
        process_check=process_check,
    )
    try:
        _do_run(
            ctx, dataset, modes, effective_base_url, output_dir, top_k, embedding,
            splade, lambdamart, cross_encoder, allow_errors, max_queries,
            context_coverage, thresholds, history_db, corpus_dir,
            skip_ingest, ingest_config, env_overrides,
            suppress_stdout=is_warmup,
        )
    finally:
        if backend_proc is not None:
            from . import backend as backend_mod
            backend_mod.stop_backend(backend_proc.proc)


def _reset_index(base_url: str) -> None:
    """Reset index via POST /api/debug/reset-index (tempdoc 355)."""
    import httpx

    url = f"{base_url}/api/debug/reset-index"
    log.info("Resetting index via %s", url)
    try:
        resp = httpx.post(url, timeout=30)
    except httpx.ConnectError:
        click.echo(f"Error: cannot connect to backend at {base_url}", err=True)
        sys.exit(1)
    if resp.status_code == 404:
        click.echo("Error: backend not in eval mode (got 404). Use runHeadlessEval.", err=True)
        sys.exit(1)
    if resp.status_code != 200:
        click.echo(f"Error: index reset failed: {resp.status_code} {resp.text}", err=True)
        sys.exit(1)
    log.info("Index reset complete")


def _check_build_freshness(base_url: str) -> None:
    """371: Warn if the running backend's build stamp doesn't match the on-disk distribution."""
    from ._paths import REPO_ROOT

    stamp_path = (
        REPO_ROOT / "modules" / "indexer-worker" / "build" / "install"
        / "indexer-worker" / "build-stamp.txt"
    )
    if not stamp_path.exists():
        log.debug("No build-stamp.txt found at %s — skipping freshness check", stamp_path)
        return

    disk_stamp = stamp_path.read_text().strip()
    if not disk_stamp:
        return

    try:
        from .readiness import flatten_status
        resp = httpx.get(f"{base_url}/api/status", timeout=5)
        resp.raise_for_status()
        running_stamp = flatten_status(resp.json()).get("buildStamp")
    except Exception:
        log.debug("Could not fetch /api/status for freshness check")
        return

    if not running_stamp:
        log.debug("Running backend has no build stamp — skipping freshness check")
        return

    if running_stamp != disk_stamp:
        click.echo(
            f"WARNING: Running backend build stamp ({running_stamp}) does not match "
            f"on-disk distribution ({disk_stamp}). The backend may be serving stale code. "
            f"Use --start-backend for a clean run, or restart the dev stack.",
            err=True,
        )


def _do_run(ctx, dataset, modes, base_url, output_dir, top_k, embedding,
            splade, lambdamart, cross_encoder, allow_errors, max_queries,
            context_coverage, thresholds, history_db, corpus_dir,
            skip_ingest, ingest_config, env_overrides=None,
            suppress_stdout=False):
    """Inner run logic (extracted for backend lifecycle try/finally).

    When suppress_stdout is True (used by warmup iterations of --warmup N), the
    per-iteration summary + JSON emissions are suppressed so that only the
    final timed run reaches consumers. Artifact files are still written to
    output_dir for post-hoc inspection.
    """
    from . import ingest as ingest_mod
    from . import run as run_module

    ingest_summary = None
    pipeline_summary = None
    if not skip_ingest:
        ingest_summary = ingest_mod.prepare_corpus(
            dataset_name=dataset,
            config=ingest_config,
            corpus_dir=Path(corpus_dir) if corpus_dir else None,
        )
        if not ingest_summary.get("readiness_passed"):
            click.echo("Warning: readiness gate did not pass after ingestion", err=True)
            click.echo(f"  Reasons: {ingest_summary.get('failure_reasons')}", err=True)
        pipeline_summary = ingest_summary.get("pipeline_summary")

    summary = run_module.execute_run(
        dataset_name=dataset,
        base_url=base_url,
        modes=[m.strip() for m in modes.split(",")] if modes else [],
        top_k=top_k,
        max_queries=max_queries,
        embedding_enabled=embedding,
        splade_enabled=splade,
        lambdamart_enabled=lambdamart,
        cross_encoder_enabled=cross_encoder,
        allow_errors=allow_errors,
        output_dir=Path(output_dir),
        context_coverage=context_coverage,
        coverage_thresholds=[float(t) for t in thresholds.split(",")],
        history_db=Path(history_db) if history_db else None,
        ingest_summary=ingest_summary,
        pipeline_summary=pipeline_summary,
        env_overrides=env_overrides,
    )
    if suppress_stdout:
        return
    if ctx.obj.get("json"):
        click.echo(json.dumps(summary, indent=2, default=str))
    else:
        _print_summary(summary)
        if pipeline_summary:
            from . import timeline as tl
            click.echo(tl.format_pipeline_summary(pipeline_summary))


@main.command("bench-concurrency")
@click.option("--dataset", required=True)
@click.option("--concurrency", type=int, required=True,
              help="Number of concurrent query streams (>=1).")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--output-dir", type=click.Path(), default=str(DEFAULT_EVAL_RESULTS), show_default=True)
@click.option("--top-k", default=10, show_default=True)
@click.option("--max-queries", default=0, show_default=True, help="Cap queries (0 = all).")
@click.option("--mode", default="hybrid", show_default=True,
              help="Search mode for every stream (lexical/hybrid/vector/full).")
@click.option("--allow-errors", is_flag=True,
              help="Record failed queries as null-latency records instead of raising.")
@click.option("--timeout", type=float, default=90.0, show_default=True)
@click.option("--max-connections", type=int, default=None,
              help="httpx connection-pool size. Phase 6 / 6.10 default = "
                   "concurrency (1:1). Override when the backend can "
                   "handle more than `concurrency` sockets.")
@click.option("--warmup", type=int, default=0, show_default=True,
              help="Discarded warmup queries per stream before the timed "
                   "pass. Amortizes httpx connection setup + backend "
                   "JIT warmup so p95/p99 aren't cold-inflated.")
@click.pass_context
def cmd_bench_concurrency(ctx, dataset, concurrency, base_url, output_dir,
                          top_k, max_queries, mode, allow_errors, timeout,
                          max_connections, warmup):
    """Concurrent-query benchmark (tempdoc 400 LR5-c).

    Spawns ``--concurrency`` query streams against
    ``/api/knowledge/search`` using the configured dataset and writes
    ``<run_dir>/concurrency-<N>.json`` with per-stream timelines +
    aggregate p50/p95/p99 + QPS. Each run lives under its own
    ``<output_dir>/<ts>_<dataset>_concurrency<N>/`` directory so
    successive benchmark runs at different N do not collide.
    """
    from datetime import datetime, timezone

    from . import bench_concurrency, corpora
    from .retriever import MODE_PIPELINES

    if concurrency < 1:
        click.echo("Error: --concurrency must be >= 1", err=True)
        sys.exit(1)

    pipeline_cfg = MODE_PIPELINES.get(mode)
    base_data_dir = Path(output_dir)
    query_records, qrels, meta = corpora.load(dataset, base_data_dir)
    query_records = {qid: qr for qid, qr in query_records.items() if qid in qrels}
    if max_queries > 0:
        query_records = dict(list(query_records.items())[:max_queries])
    queries = {qid: qr.text for qid, qr in query_records.items()}

    if not queries:
        click.echo("Error: no queries to run", err=True)
        sys.exit(1)

    result = bench_concurrency.run_benchmark(
        queries,
        concurrency=concurrency,
        base_url=base_url,
        mode=mode,
        top_k=top_k,
        pipeline=pipeline_cfg,
        allow_errors=allow_errors,
        timeout=timeout,
        max_connections=max_connections,
        warmup=warmup,
    )
    result["dataset"] = dataset
    result["mode"] = mode

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")
    run_dir = base_data_dir / f"{ts}_{dataset}_concurrency{concurrency}"
    run_dir.mkdir(parents=True, exist_ok=True)
    path = bench_concurrency.write_benchmark(result, run_dir)

    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(f"Wrote {path}")
        agg = result["aggregate_latency_ms"]
        click.echo(
            f"  concurrency={concurrency} queries={result['query_count']}"
            f" ok={result['ok']} errors={result['errors']} qps={result['qps']:.2f}"
        )
        click.echo(
            f"  latency_ms p50={agg['p50']:.1f} p95={agg['p95']:.1f}"
            f" p99={agg['p99']:.1f} max={agg['max']:.1f}"
        )


@main.command("counterfactual")
@click.option("--dataset", required=True)
@click.option(
    "--modes", default=None,
    help="Comma-separated counterfactual modes (default: all five "
         "[lexical_only, dense_only, splade_only, hybrid_no_ce, hybrid_full]).",
)
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--output-dir", type=click.Path(), default=str(DEFAULT_EVAL_RESULTS), show_default=True)
@click.option("--top-k", default=10, show_default=True)
@click.option("--max-queries", default=0, show_default=True, help="Cap queries (0 = all).")
@click.option("--allow-errors", is_flag=True)
@click.option(
    "--fusion-algorithm",
    type=click.Choice(["cc", "rrf"]), default="cc", show_default=True,
    help="Fusion algorithm for hybrid_no_ce + hybrid_full modes "
         "(Phase 6 / 6.4). CC (convex combination) is the primary "
         "eval default; RRF (reciprocal rank fusion) is the lighter "
         "comparison baseline.",
)
@click.pass_context
def cmd_counterfactual(ctx, dataset, modes, base_url, output_dir, top_k,
                       max_queries, allow_errors, fusion_algorithm):
    """Counterfactual mode runner (tempdoc 400 LR5-a).

    Issues each query under every configured counterfactual mode
    (lexical-only, dense-only, splade-only, hybrid-no-CE,
    hybrid-full) and emits per-query rankings + pairwise Jaccard
    divergence between modes.

    Multi-pass implementation (deviation from spec §8.5 LR5-a's
    single-pass design): one HTTP request per (query, mode) instead
    of a proto-level counterfactual_modes field. Documented in
    jseval/counterfactual.py module docstring.
    """
    from datetime import datetime, timezone

    from . import corpora, counterfactual

    resolved_modes = (
        [m.strip() for m in modes.split(",") if m.strip()]
        if modes else None
    )
    base_data_dir = Path(output_dir)
    query_records, qrels, meta = corpora.load(dataset, base_data_dir)
    query_records = {qid: qr for qid, qr in query_records.items() if qid in qrels}
    if max_queries > 0:
        query_records = dict(list(query_records.items())[:max_queries])
    queries = {qid: qr.text for qid, qr in query_records.items()}

    if not queries:
        click.echo("Error: no queries to run", err=True)
        sys.exit(1)

    result = counterfactual.run_counterfactual(
        queries,
        base_url=base_url,
        modes=resolved_modes,
        top_k=top_k,
        allow_errors=allow_errors,
        fusion_algorithm=fusion_algorithm,
    )
    result["dataset"] = dataset

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")
    run_dir = base_data_dir / f"{ts}_{dataset}_counterfactual"
    run_dir.mkdir(parents=True, exist_ok=True)
    path = counterfactual.write_report(result, run_dir)

    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(f"Wrote {path}")
        click.echo(f"  queries={result['query_count']} modes={len(result['modes'])}")
        for mode, agg in result["summary"].items():
            click.echo(
                f"    {mode}: ok={agg['ok']} errors={agg['errors']}"
                f" mean_ms={agg['mean_dispatch_ms'] or 0:.1f}"
            )
        # Print a small divergence hint: hybrid_full vs each counterfactual.
        piv = result.get("pairwise_divergence", {}).get("hybrid_full", {})
        if piv:
            click.echo("  Jaccard vs hybrid_full:")
            for mode, val in piv.items():
                if mode != "hybrid_full" and val is not None:
                    click.echo(f"    {mode}: {val:.3f}")


@main.command("shadow-eval")
@click.option("--dataset", required=True)
@click.option("--policy-a", type=click.Path(exists=True), required=True,
              help="JSON file containing the policy-A pipeline config.")
@click.option("--policy-b", type=click.Path(exists=True), required=True,
              help="JSON file containing the policy-B pipeline config.")
@click.option("--policy-a-name", default="A", show_default=True)
@click.option("--policy-b-name", default="B", show_default=True)
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--output-dir", type=click.Path(), default=str(DEFAULT_EVAL_RESULTS), show_default=True)
@click.option("--top-k", default=10, show_default=True)
@click.option("--max-queries", default=0, show_default=True, help="Cap queries (0 = all).")
@click.option("--allow-errors", is_flag=True)
@click.option(
    "--max-error-rate", type=float, default=None,
    help="Fail with RuntimeError if per-policy error fraction exceeds "
         "this threshold (e.g. 0.10 = 10%). Default: disabled.",
)
@click.pass_context
def cmd_shadow_eval(ctx, dataset, policy_a, policy_b, policy_a_name,
                    policy_b_name, base_url, output_dir, top_k, max_queries,
                    allow_errors, max_error_rate):
    """Shadow evaluation (tempdoc 400 LR5-b).

    Runs two policies against the same eval query set in the same
    order on the same running Worker. Emits per-query divergence
    signals (top-K Jaccard + Kendall-tau) plus an aggregate summary.
    Offline-only per §13.9 C3 — no production traffic sampling; no
    query-feature-conditional sampling.
    """
    from datetime import datetime, timezone

    from . import corpora, shadow_eval

    policy_a_cfg = json.loads(Path(policy_a).read_text(encoding="utf-8"))
    policy_b_cfg = json.loads(Path(policy_b).read_text(encoding="utf-8"))
    base_data_dir = Path(output_dir)
    query_records, qrels, meta = corpora.load(dataset, base_data_dir)
    query_records = {qid: qr for qid, qr in query_records.items() if qid in qrels}
    if max_queries > 0:
        query_records = dict(list(query_records.items())[:max_queries])
    queries = {qid: qr.text for qid, qr in query_records.items()}

    if not queries:
        click.echo("Error: no queries to run", err=True)
        sys.exit(1)

    result = shadow_eval.run_shadow(
        queries,
        policy_a=policy_a_cfg,
        policy_b=policy_b_cfg,
        policy_a_name=policy_a_name,
        policy_b_name=policy_b_name,
        base_url=base_url,
        top_k=top_k,
        allow_errors=allow_errors,
        max_error_rate=max_error_rate,
    )
    result["dataset"] = dataset

    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")
    run_dir = base_data_dir / f"{ts}_{dataset}_shadow"
    run_dir.mkdir(parents=True, exist_ok=True)
    path = shadow_eval.write_report(result, run_dir)

    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        s = result["summary"]
        click.echo(f"Wrote {path}")
        click.echo(
            f"  queries={result['query_count']} "
            f"identical={s['identical_top_k']} partial={s['partial_overlap']} "
            f"disjoint={s['disjoint']} errors={s['errors']}"
        )
        if s["mean_jaccard"] is not None:
            click.echo(
                f"  mean_top_k_jaccard={s['mean_jaccard']:.3f} "
                f"mean_kendall_tau={s['mean_kendall_tau'] or 0:.3f}"
            )


@main.command("bisect")
@click.option("--run-a", type=click.Path(exists=True), required=True,
              help="Path to run A's run_dir (baseline).")
@click.option("--run-b", type=click.Path(exists=True), required=True,
              help="Path to run B's run_dir (changed).")
@click.option("--output-dir", type=click.Path(), default=str(DEFAULT_EVAL_RESULTS),
              show_default=True,
              help="Eval-results directory hosting the manifest index.")
@click.option("--metric", default="nDCG@10", show_default=True)
@click.option("--mode", default=None,
              help="Mode to bisect on (default: pick the single mode in the envelope).")
@click.option("--sigma", type=float, default=2.0, show_default=True,
              help="Multiplier on envelope stdev for the significance threshold.")
@click.option("--report-out", type=click.Path(), default=None,
              help="Optional path to write the JSON report (default: stdout only).")
@click.option("--synthesize", is_flag=True,
              help="Phase 6 / 6.5: spawn synthetic `jseval run` for each "
                   "missing-cache cohort before bisecting. Requires "
                   "--dataset/--modes + JUSTSEARCH_DATA_DIR.")
@click.option("--dataset", default=None,
              help="Dataset for synthetic runs (required with --synthesize).")
@click.option("--modes", default="full", show_default=True,
              help="Modes for synthetic runs (comma-separated).")
@click.option("--max-queries", type=int, default=50, show_default=True,
              help="Query cap for synthetic runs.")
@click.option("--dry-run", is_flag=True,
              help="With --synthesize, print the plan without spawning runs.")
@click.pass_context
def cmd_bisect(ctx, run_a, run_b, output_dir, metric, mode, sigma, report_out,
                synthesize, dataset, modes, max_queries, dry_run):
    """Manifest-hash bisection (tempdoc 400 LR5-d).

    Given two runs A and B with a metric delta that exceeds the
    cohort's non-determinism envelope, walks the axis-wise diff
    between their manifests and attributes the delta to single-
    axis swaps. Cache lookup only — when no cached run matches a
    synthetic axis-swapped hash, that axis is reported as
    ``no-cached-run``.
    """
    from . import bisection
    from . import calibrate

    run_a_path = Path(run_a)
    run_b_path = Path(run_b)
    manifest_a = json.loads((run_a_path / "manifest.json").read_text(encoding="utf-8"))
    manifest_b = json.loads((run_b_path / "manifest.json").read_text(encoding="utf-8"))

    data_dir_env = os.environ.get("JUSTSEARCH_DATA_DIR")
    envelope = None
    if data_dir_env and manifest_a.get("manifest_hash"):
        envelope = calibrate.read_envelope(
            Path(data_dir_env), manifest_a["manifest_hash"],
        )
    # Fallback: read envelope embedded in manifest A (if any).
    if envelope is None:
        envelope = manifest_a.get("non_determinism_envelope")

    if synthesize:
        if not dataset:
            click.echo("Error: --synthesize requires --dataset", err=True)
            sys.exit(1)
        data_dir_for_sync = os.environ.get("JUSTSEARCH_DATA_DIR")
        if not data_dir_for_sync:
            click.echo("Error: --synthesize requires JUSTSEARCH_DATA_DIR env var",
                       err=True)
            sys.exit(1)
        result = bisection.synthesize_and_bisect(
            manifest_a, manifest_b,
            envelope=envelope or {"metrics": {}},
            output_dir=Path(output_dir),
            data_dir=Path(data_dir_for_sync),
            dataset=dataset,
            modes=modes,
            max_queries=max_queries,
            metric=metric,
            mode=mode,
            sigma_multiplier=sigma,
            dry_run=dry_run,
        )
    else:
        result = bisection.bisect(
            manifest_a, manifest_b,
            envelope=envelope or {"metrics": {}},
            output_dir=Path(output_dir),
            metric=metric,
            mode=mode,
            sigma_multiplier=sigma,
        )

    if report_out:
        bisection.write_report(result, Path(report_out))
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    elif synthesize:
        click.echo(f"synthesize_and_bisect status: {result['status']}")
        for syn in result.get("synthesized", []):
            click.echo(f"  axis={syn['axis']} status={syn.get('status')}"
                       + (f" returncode={syn.get('returncode')}" if syn.get('returncode') is not None else ""))
        bis = result.get("bisection", {})
        if bis:
            click.echo(f"final bisection status: {bis.get('status')}")
    else:
        click.echo(f"bisection status: {result['status']}")
        click.echo(f"  metric: {result['metric']} (mode={result['mode']})")
        click.echo(f"  metric_a={result['metric_a']} metric_b={result['metric_b']} sigma={result['envelope_sigma']}")
        click.echo(f"  axes_diff: {result['axes_diff']}")
        for a in result["attributions"]:
            line = f"    {a['axis']}: {a.get('status')}"
            if "delta" in a:
                line += f" delta={a['delta']:+.4f}"
            if "threshold" in a:
                line += f" threshold=±{a['threshold']:.4f}"
            click.echo(line)


@main.command("requery")
@click.option("--dataset", required=True)
@click.option("--modes", required=True)
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--output-dir", type=click.Path(), default=str(DEFAULT_EVAL_RESULTS), show_default=True)
@click.option("--top-k", default=10, show_default=True)
@click.option("--embedding/--no-embedding", default=False)
@click.option("--splade/--no-splade", default=False)
@click.option("--allow-errors", is_flag=True)
@click.option("--max-queries", default=0, show_default=True, help="Cap queries (0 = all).")
@click.option("--context-coverage", is_flag=True, help="Compute excerpt coverage metrics.")
@click.option("--thresholds", default="0.25,0.5", show_default=True, help="Coverage threshold rates.")
@click.option("--history-db", type=click.Path(), default=None, help="Shared history database path.")
@click.pass_context
def cmd_requery(ctx, dataset, modes, base_url, output_dir, top_k, embedding, splade, allow_errors, max_queries, context_coverage, thresholds, history_db):
    """Re-run queries only (skip ingest/readiness wait)."""
    from . import run as run_module

    summary = run_module.execute_run(
        dataset_name=dataset,
        base_url=base_url,
        modes=[m.strip() for m in modes.split(",")] if modes else [],
        top_k=top_k,
        max_queries=max_queries,
        embedding_enabled=embedding,
        splade_enabled=splade,
        skip_readiness=True,
        allow_errors=allow_errors,
        output_dir=Path(output_dir),
        context_coverage=context_coverage,
        coverage_thresholds=[float(t) for t in thresholds.split(",")],
        history_db=Path(history_db) if history_db else None,
    )
    if ctx.obj.get("json"):
        click.echo(json.dumps(summary, indent=2, default=str))
    else:
        _print_summary(summary)


@main.command("compare")
@click.argument("run_a", type=click.Path(exists=True))
@click.argument("run_b", type=click.Path(exists=True))
@click.option("--mode", default=None, help="Mode to compare (auto-detected if omitted).")
@click.option("--verbose", "-v", is_flag=True, help="Show per-query rank diffs.")
@click.option("--fail-on-regression", is_flag=True, help="Exit 1 if significant regression detected.")
@click.option(
    "--bucket-by",
    type=click.Choice(["decision_kind"]),
    default=None,
    help=(
        "Tempdoc 525: stratify the metric comparison by an additional "
        "dimension. Currently supports `decision_kind` (sourced from "
        "SearchIntrospection.decision.kind per-query)."
    ),
)
@click.pass_context
def cmd_compare(ctx, run_a, run_b, mode, verbose, fail_on_regression, bucket_by):
    """Compare two run directories (or summary.json files)."""
    from . import compare_runs

    a_data = _load_run_for_compare(Path(run_a), mode)
    b_data = _load_run_for_compare(Path(run_b), mode)

    # Need qrels for query alignment
    from . import corpora
    dataset = a_data["summary"].get("dataset", b_data["summary"].get("dataset"))
    if not dataset:
        click.echo("Error: cannot determine dataset from run files", err=True)
        sys.exit(1)
    _, qrels, _ = corpora.load(dataset)

    results = compare_runs.compare(a_data, b_data, qrels)

    # Pipeline timing comparison (item 8).
    pipeline_diff = compare_runs.compare_pipeline_timing(
        a_data["summary"], b_data["summary"],
    )

    # Tempdoc 525: optional per-bucket stratification.
    bucket_results: dict[str, dict] = {}
    if bucket_by == "decision_kind":
        bucket_results = _compare_by_decision_kind(a_data, b_data, qrels)

    if ctx.obj.get("json"):
        output: dict[str, object] = {"metrics": results}
        if pipeline_diff:
            output["pipeline_timing"] = pipeline_diff
        if bucket_results:
            output["by_decision_kind"] = bucket_results
        if not pipeline_diff and not bucket_results:
            output = results  # preserve original format when no extra data
        click.echo(json.dumps(output, indent=2, default=str))
    else:
        _print_comparison(results)
        if pipeline_diff:
            _print_pipeline_comparison(pipeline_diff)
        if bucket_results:
            _print_decision_kind_comparison(bucket_results)

    if fail_on_regression:
        for metric, r in results.items():
            if r.get("p_value", 1) < 0.05 and r.get("delta", 0) < 0:
                sys.exit(1)

    if verbose:
        resolved_mode = mode or _first_mode(a_data["summary"])
        if resolved_mode:
            a_pq = a_data.get("per_query_entries", [])
            b_pq = b_data.get("per_query_entries", [])
            diffs = compare_runs.per_query_diff(a_pq, b_pq, qrels)
            if diffs:
                click.echo(f"Per-query regressions ({len(diffs)} queries):")
                for d in diffs[:10]:
                    click.echo(f"  {d['qid']}: {d['metric']} {d['value_a']:.3f} -> {d['value_b']:.3f} (delta={d['delta']:+.3f})")
                    for rc in d["rank_changes"][:5]:
                        ra = rc["rank_a"] or "absent"
                        rb = rc["rank_b"] or "absent"
                        click.echo(f"    {rc['doc_id']}: rank {ra} -> {rb} (rel={rc['relevance']})")


def _load_run_for_compare(path: Path, mode: str | None) -> dict:
    """Load a run directory or summary.json for comparison."""
    if path.is_dir():
        run_dir = path
        summary_path = run_dir / "summary.json"
    else:
        # Assume it's summary.json, run dir is parent
        summary_path = path
        run_dir = path.parent

    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    resolved_mode = mode or _first_mode(summary)

    # Load per-query metrics into the format compare() expects
    per_query_metrics: dict[str, dict[str, float]] = {}
    per_query_entries: list[dict] = []
    if resolved_mode:
        pq_path = run_dir / f"{resolved_mode}_per_query.json"
        if pq_path.is_file():
            per_query_entries = json.loads(pq_path.read_text(encoding="utf-8"))
            for entry in per_query_entries:
                qid = entry["qid"]
                per_query_metrics[qid] = {
                    "nDCG@10": entry.get("ndcgAtK"),
                    "AP@10": entry.get("apAtK"),
                    "RR@10": entry.get("mrrAtK"),
                    "R@10": entry.get("recallAtK"),
                    "P@1": entry.get("p1AtK"),
                }

    return {
        "summary": summary,
        "per_query_metrics": per_query_metrics,
        "per_query_entries": per_query_entries,
    }


def _first_mode(summary: dict) -> str | None:
    modes = summary.get("modes", [])
    return modes[0] if modes else None


@main.command("trend")
@click.option("--dataset", required=True)
@click.option("--mode", required=True)
@click.option("--output-dir", type=click.Path(), default=str(DEFAULT_EVAL_RESULTS), show_default=True)
@click.option("--fail-on-regression", is_flag=True, help="Exit 1 if regression detected.")
@click.option(
    "--manifest-hash", default=None,
    help="Cohort identity (LR4-h). When provided, restricts the trend window to "
         "runs in the same cohort; omit for the legacy cross-cohort view.",
)
@click.option(
    "--metric", default="nDCG@10", show_default=True,
    help="Family to trend (tempdoc 640 R3): nDCG@10 | ce_p50_ms | primary_docs_s | "
         "enrich_docs_s | resident_bytes. Direction is metric-aware.",
)
def cmd_trend(dataset, mode, output_dir, fail_on_regression, manifest_hash, metric):
    """Check metric history and trends."""
    from . import history

    trend = history.check_trend(
        dataset, mode, Path(output_dir), manifest_hash=manifest_hash, metric=metric,
    )
    click.echo(json.dumps(trend, indent=2))
    if fail_on_regression and trend.get("status") == "regression":
        sys.exit(1)


@main.command("spot-check")
@click.option("--suite", type=click.Path(exists=True), default=None, help="Custom query suite JSON.")
@click.option("--modes", default="lexical,hybrid", show_default=True)
@click.option("--top-k", default=5, show_default=True)
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_spot_check(ctx, suite, modes, top_k, base_url, output_dir):
    """Developer ranking spot-check (no qrels needed)."""
    from . import spot_check

    query_suite = spot_check.load_suite(Path(suite) if suite else None)
    results = spot_check.execute_spot_check(
        query_suite, base_url,
        modes=[m.strip() for m in modes.split(",")] if modes else [],
        top_k=top_k,
    )
    if ctx.obj.get("json"):
        click.echo(json.dumps(results, indent=2, default=str))
    else:
        click.echo(spot_check.format_console(results))

    if output_dir:
        import json as json_mod
        out = Path(output_dir)
        out.mkdir(parents=True, exist_ok=True)
        (out / "spot-check.json").write_text(
            json_mod.dumps(results, indent=2, default=str), encoding="utf-8",
        )
        click.echo(f"Written to {out / 'spot-check.json'}")


@main.command("correction-probe")
@click.option("--manifest", type=click.Path(exists=True), default=None,
              help="Correction query manifest JSON.")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--top-k", default=10, show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_correction_probe(ctx, manifest, base_url, top_k, output_dir):
    """Evaluate search correction (did-you-mean) quality."""
    from . import correction_probe

    queries = correction_probe.load_manifest(Path(manifest) if manifest else None)
    result = correction_probe.execute_probe(queries, base_url, top_k=top_k)
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(correction_probe.format_console(result))

    if output_dir:
        import json as json_mod
        out = Path(output_dir)
        out.mkdir(parents=True, exist_ok=True)
        (out / "correction-probe.json").write_text(
            json_mod.dumps(result, indent=2, default=str), encoding="utf-8",
        )
        click.echo(f"Written to {out / 'correction-probe.json'}")


@main.command("ui-perf")
@click.option("--ui-url", default="http://localhost:5173", show_default=True)
@click.option("--api-base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--iterations", default=4, show_default=True)
@click.option("--warmup", default=1, show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_ui_perf(ctx, ui_url, api_base_url, iterations, warmup, output_dir):
    """UI latency benchmark (keystroke-to-paint, click-to-preview via Playwright)."""
    from . import ui_perf

    result = ui_perf.execute_ui_perf(
        ui_url=ui_url,
        api_base_url=api_base_url,
        iterations=iterations,
        warmup=warmup,
    )
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(ui_perf.format_console(result))
    _write_bench_output(result, output_dir, "ui-perf.json")


@main.command("ui-check")
@click.option("--ui-url", default="http://localhost:5173", show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.option("--cooldown-ms", default=250, show_default=True,
              help="Cooldown between screenshots (ms).")
@click.option("--timeout-ms", default=120_000, show_default=True,
              help="Overall timeout (ms).")
@click.option("--no-demo", is_flag=True, default=False,
              help="Use real backend instead of demo mode.")
@click.pass_context
def cmd_ui_check(ctx, ui_url, output_dir, cooldown_ms, timeout_ms, no_demo):
    """UI screenshot check (Playwright) — captures and verifies UI states."""
    from . import ui_check

    result = ui_check.execute_ui_check(
        ui_url=ui_url,
        output_dir=output_dir,
        demo=not no_demo,
        cooldown_ms=cooldown_ms,
        timeout_ms=timeout_ms,
    )
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(ui_check.format_console(result))
    _write_bench_output(result, output_dir, "ui-check.json")


@main.command("ui-shot")
@click.argument("step_name", required=False, default=None)
@click.option("--list", "list_steps", is_flag=True, default=False,
              help="List all available steps.")
@click.option("--affected", "affected_path", type=click.Path(), default=None,
              help="Capture steps affected by a file change.")
@click.option("--ui-url", default="http://localhost:5173", show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.option("--cooldown-ms", default=250, show_default=True)
@click.option("--timeout-ms", default=30_000, show_default=True,
              help="Per-step timeout (ms).")
@click.option("--no-demo", is_flag=True, default=False,
              help="Use real backend instead of demo mode.")
@click.option("--no-measure", is_flag=True, default=False,
              help="Skip the structured-measurement companion (615 §6.2); capture the PNG only.")
@click.option("--fixtures", is_flag=True, default=False,
              help="Deterministic mode (615 §16): route-mock every /api/* with schema-valid "
                   "fixtures + dismiss the first-run walkthrough — no backend, byte-stable, zero "
                   "env console noise. For STRUCTURAL steps; omit for the live AI-chain steps.")
@click.option("--trace", is_flag=True, default=False,
              help="TRACE mode (615 §11): also write <step>.trace.json — the {pre,post} measurement "
                   "delta across the step's interaction trajectory (what the flow changed).")
@click.pass_context
def cmd_ui_shot(ctx, step_name, list_steps, affected_path, ui_url, output_dir,
                cooldown_ms, timeout_ms, no_demo, no_measure, fixtures, trace):
    """Single-step UI screenshot for agent feedback loop."""
    from . import ui_shot

    if list_steps:
        result = ui_shot.execute_ui_shot_list(ui_url=ui_url)
        if ctx.obj.get("json"):
            click.echo(json.dumps(result, indent=2, default=str))
        else:
            click.echo(ui_shot.format_console_list(result))
        return

    if affected_path:
        results = ui_shot.execute_ui_shot_affected(
            affected_path,
            ui_url=ui_url, output_dir=output_dir,
            demo=not no_demo, cooldown_ms=cooldown_ms, timeout_ms=timeout_ms,
        )
        if ctx.obj.get("json"):
            click.echo(json.dumps(results, indent=2, default=str))
        else:
            click.echo(ui_shot.format_console_affected(results))
        return

    if not step_name:
        raise click.UsageError("Provide a step name, --list, or --affected <path>.")

    result = ui_shot.execute_ui_shot(
        step_name,
        ui_url=ui_url, output_dir=output_dir,
        demo=not no_demo, cooldown_ms=cooldown_ms, timeout_ms=timeout_ms,
        measure=not no_measure, fixtures=fixtures, trace=trace,
    )
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(ui_shot.format_console_shot(result))


@main.command("ui-a11y-gate")
@click.option("--ui-url", default="http://localhost:5173", show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.option("--timeout-ms", default=30_000, show_default=True)
@click.pass_context
def cmd_ui_a11y_gate(ctx, ui_url, output_dir, timeout_ms):
    """ASSERT gate (615 §11): fail on a NEW a11y violation vs the shared baseline.

    Captures every surface in governance/ui-a11y-baseline.v1.json in the deterministic
    --fixtures state and compares its axe violations to the surface's accepted
    knownRules. Exit 0 = clean, 1 = a NEW (non-baselined) violation, 2 = capture error.
    Local-first (ADR-0026): a runnable gate, not CI-wired.
    """
    from . import ui_a11y_gate, ui_shot

    def _capture(step: str) -> dict:
        return ui_shot.execute_ui_shot(
            step, ui_url=ui_url, output_dir=output_dir,
            demo=False, timeout_ms=timeout_ms, measure=True, fixtures=True,
        )

    report = ui_a11y_gate.evaluate(_capture)
    click.echo(json.dumps(report, indent=2, default=str), err=True)
    sys.exit(report["exit_code"])


@main.command("ui-diff")
@click.argument("before", type=click.Path(exists=True))
@click.argument("after", type=click.Path(exists=True))
@click.pass_context
def cmd_ui_diff(ctx, before, after):
    """DIFF (615 §11): semantic perceptual changelog between two <step>.measure.json.

    Reports what MEANINGFULLY changed — a landmark removed, an element moved/resized
    past 4px, a NEW axe rule, overflow flipped, real console errors — not a pixel diff.
    Exit 0 if no semantic change, 1 if changed (so it composes in scripts).
    """
    from . import ui_diff

    report = ui_diff.diff_files(before, after)
    if ctx.obj.get("json"):
        click.echo(json.dumps(report, indent=2, default=str))
    else:
        click.echo(ui_diff.format_diff(report))
    sys.exit(1 if report["changed"] else 0)


def _step_surface_map() -> dict:
    """uiShotStep -> surface, from the shared baseline register (the surfaces both the
    a11y baseline and the design reference are keyed by)."""
    from . import ui_a11y_gate
    return {s["uiShotStep"]: s["surface"] for s in ui_a11y_gate.load_register_surfaces() if s.get("uiShotStep")}


@main.command("ui-critic")
@click.argument("step")
@click.option("--surface", default=None, help="Design-reference surface key (default: from the register).")
@click.option("--ui-url", default="http://localhost:5173", show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_ui_critic(ctx, step, surface, ui_url, output_dir):
    """REASON (615 §11): emit the GROUNDED design-critique prompt for a captured surface.

    Captures STEP in the deterministic --fixtures state, pairs the measured facts with
    governance/design-reference.v1.json + the rubric, and prints the critique prompt.
    Feed it to a model (the agent, or the dev model via `agent_chat`) to get the
    structured critique — the LLM-rubric half of "unit tests + LLM rubrics for quality".
    """
    import json as _json

    from . import ui_critic, ui_shot

    surface = surface or _step_surface_map().get(step, step)
    res = ui_shot.execute_ui_shot(step, ui_url=ui_url, output_dir=output_dir,
                                  demo=False, measure=True, fixtures=True)
    if not res.get("ok"):
        click.echo(_json.dumps({"error": res.get("error")}), err=True)
        sys.exit(2)
    measure_path = (res.get("measure") or {}).get("measure_path")
    measure = _json.loads(Path(measure_path).read_text(encoding="utf-8"))
    prompt = ui_critic.assemble_prompt(measure, ui_critic.load_reference(), surface)
    # The prompt IS the product here; the model (agent/agent_chat) produces the critique.
    click.echo(prompt)


@main.command("ui-fuzz")
@click.option("--ui-url", default="http://localhost:5173", show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_ui_fuzz(ctx, ui_url, output_dir):
    """GENERATE (615 §11): fuzz the search surface across {data-variant x viewport x theme}.

    Renders every cell of the state matrix a human won't patiently check (narrow-viewport
    overflow, light-theme contrast, empty-data layout), measures each, and flags the cells
    with anomalies (NEW axe vs baseline / overflow / real console errors). Exit 0 = all
    clean, 1 = a cell flagged.
    """
    import asyncio

    from . import ui_fuzz

    out = Path(output_dir) if output_dir else Path("tmp/ui-fuzz")
    report = asyncio.run(ui_fuzz.run_fuzz(out, ui_url=ui_url))
    if ctx.obj.get("json"):
        click.echo(json.dumps(report, indent=2, default=str))
    else:
        click.echo(ui_fuzz.format_matrix(report))
    sys.exit(1 if report["flagged"] else 0)


@main.command("llm-bench")
@click.option("--profile", type=click.Choice(["smoke", "regression", "full"]),
              default="regression", show_default=True)
@click.option("--runs", default=1, show_default=True, help="Suite iterations.")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_llm_bench(ctx, profile, runs, base_url, output_dir):
    """LLM inference benchmark (TTFT, E2E latency, token rate)."""
    from . import llm_bench, suite_stats

    if runs == 1:
        result = llm_bench.execute_llm_bench(base_url, profile=profile)
    else:
        suite_results = suite_stats.run_suite(
            lambda: llm_bench.execute_llm_bench(base_url, profile=profile),
            runs=runs,
        )
        result = {
            "runs": suite_results,
            "statistics": suite_stats.summarize_suite(
                suite_results,
                ["e2e_latency_p50_ms", "ttft_p50_ms", "token_rate_median_tps"],
            ),
        }
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(llm_bench.format_console(result))
    _write_bench_output(result, output_dir, "llm-bench.json")


@main.command("ingest-bench")
@click.option("--corpus-dir", required=True, type=click.Path(exists=True))
@click.option("--runs", default=1, show_default=True)
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
def cmd_ingest_bench(corpus_dir, runs, base_url, output_dir):
    """Ingest throughput benchmark (Claim B)."""
    from . import ingest_bench

    result = ingest_bench.execute_ingest_bench(
        base_url, Path(corpus_dir), runs=runs,
    )
    click.echo(ingest_bench.format_console(result))
    _write_bench_output(result, output_dir, "ingest-bench.json")


@main.command("engine-bench")
@click.option("--corpus", required=True, type=click.Path(exists=True))
@click.option("--runs", default=5, show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
def cmd_engine_bench(corpus, runs, output_dir):
    """Engine-only indexing benchmark (Claim A)."""
    from . import gradle_bench

    result = gradle_bench.execute_engine_bench(
        Path(corpus), runs=runs, out_dir=Path(output_dir) if output_dir else None,
    )
    click.echo(gradle_bench.format_engine_console(result))
    _write_bench_output(result, output_dir, "engine-bench.json")


@main.command("knn-bench")
@click.option("--doc-counts", default="20000,200000", show_default=True)
@click.option("--chunk-doc-counts", default="200000,500000", show_default=True)
@click.option("--repeats", default=1, show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
def cmd_knn_bench(doc_counts, chunk_doc_counts, repeats, output_dir):
    """Filtered kNN latency benchmark (Track G)."""
    from . import gradle_bench

    result = gradle_bench.execute_knn_bench(
        doc_counts=[int(x) for x in doc_counts.split(",")],
        chunk_doc_counts=[int(x) for x in chunk_doc_counts.split(",")],
        repeats=repeats,
        out_dir=Path(output_dir) if output_dir else None,
    )
    click.echo(gradle_bench.format_knn_console(result))
    _write_bench_output(result, output_dir, "knn-bench.json")


@main.command("rag-eval")
@click.option("--profile", default="stub-jaccard", show_default=True)
@click.option("--baseline", type=click.Path(exists=True), default=None)
@click.option("--output-dir", type=click.Path(), default=None)
def cmd_rag_eval(profile, baseline, output_dir):
    """RAG quality evaluation (Gradle test + metric comparison)."""
    from . import rag_eval

    candidate = rag_eval.run_rag_eval(profile=profile)
    if "error" in candidate:
        click.echo(f"RAG eval failed: {candidate['error']}", err=True)
        sys.exit(1)

    if baseline:
        baseline_data = json.loads(Path(baseline).read_text(encoding="utf-8"))
        decision = rag_eval.diff_rag_eval(baseline_data, candidate)
        click.echo(rag_eval.format_console(decision))
        if decision["gate_status"] == "fail":
            sys.exit(1)
    else:
        click.echo(json.dumps(candidate, indent=2, default=str))

    _write_bench_output(candidate, output_dir, "rag-eval.json")


@main.command("diff")
@click.argument("baseline", type=click.Path(exists=True))
@click.argument("candidate", type=click.Path(exists=True))
@click.option("--lane", default="claim-a", show_default=True,
              help="Benchmark lane for threshold selection.")
def cmd_diff(baseline, candidate, lane):
    """Compare two benchmark artifacts for regression."""
    from . import diff_gate

    lane_configs = {
        "claim-a": [
            ("statistics.time_to_searchable_ms.median", True, 1.10),
            ("statistics.docs_per_s.median", False, 0.90),
        ],
        "claim-b": [
            ("statistics.docs_per_s.median", False, 0.90),
            ("statistics.elapsed_sec.median", True, 1.10),
        ],
        "llm-bench": [
            ("e2e_latency_p50_ms", True, 1.20),
            ("ttft_p50_ms", True, 1.20),
            ("token_rate_median_tps", False, 0.80),
        ],
        "track-g": [
            ("worst_p95_ms", True, 1.15),
        ],
    }
    config = lane_configs.get(lane, lane_configs["claim-a"])
    decision = diff_gate.diff_files(Path(baseline), Path(candidate), config)
    click.echo(f"Gate: {decision['gate_status'].upper()}  "
               f"({decision['regression_count']} regressions)")
    for c in decision["comparisons"]:
        ratio = f"{c['ratio']:.4f}" if c.get("ratio") is not None else "N/A"
        click.echo(f"  {c.get('metric', '?')}: {c['status']}  ratio={ratio}")
    if decision["gate_status"] == "fail":
        sys.exit(1)


def _write_bench_output(result: dict, output_dir: str | None, filename: str) -> None:
    """Write benchmark result JSON if output_dir is specified."""
    if not output_dir:
        return
    out = Path(output_dir)
    out.mkdir(parents=True, exist_ok=True)
    path = out / filename
    path.write_text(json.dumps(result, indent=2, default=str), encoding="utf-8")
    click.echo(f"Written to {path}")


@main.command("materialize")
@click.option("--dataset", required=True)
@click.option("--output-dir", required=True, type=click.Path())
def cmd_materialize(dataset, output_dir):
    """Materialize a BEIR corpus to .txt files."""
    import ir_datasets
    from . import corpora, materialize

    if dataset not in corpora.BEIR_DATASETS:
        click.echo(f"Error: unknown BEIR dataset: {dataset}", err=True)
        sys.exit(1)

    ds = ir_datasets.load(corpora.BEIR_DATASETS[dataset])
    count = materialize.materialize(ds.docs_iter(), Path(output_dir))
    click.echo(f"Materialized {count} documents to {output_dir}")


@main.command("preflight")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.pass_context
def cmd_preflight(ctx, base_url):
    """Check backend health, models, GPU status before running eval."""
    from . import preflight

    result = preflight.execute_preflight(base_url)
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(preflight.format_console(result))
    if result["status"] == "unreachable":
        sys.exit(1)


@main.command("log-path")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
def cmd_log_path(base_url):
    """Discover and print the worker.log path from the running backend."""
    from . import preflight

    status = preflight._fetch_endpoint(base_url, "/api/status", 10)
    if status is None:
        click.echo("Error: backend unreachable", err=True)
        sys.exit(1)

    index_base = status.get("indexBasePath", "")
    if not index_base:
        click.echo("Error: indexBasePath not available in status", err=True)
        sys.exit(1)

    # indexBasePath is like <data_dir>/index/default — go up 2 levels
    data_dir = Path(index_base).parent.parent
    log_path = data_dir / "logs" / "worker.log"
    click.echo(str(log_path))


@main.command("dev")
@click.option("--clean", is_flag=True, help="Clean data directory before starting.")
@click.option("--llm", is_flag=True, help="Enable LLM (Brain/llama-server) autostart in the backend.")
@click.option("--port", default=33221, show_default=True)
@click.pass_context
def cmd_dev(ctx, clean, llm, port):
    """Start the eval backend and keep it running until Ctrl-C.

    If a backend is already healthy on the target port, attaches to it
    (skips start, skips stop on exit). Otherwise starts a fresh backend.

    With --llm, passes -Pllm=true to Gradle and waits for inference
    readiness in addition to index health.
    """
    from . import backend as backend_mod
    from . import preflight

    base_url = f"http://127.0.0.1:{port}"
    attached = False

    # Check if backend is already running
    status = preflight._fetch_endpoint(base_url, "/api/status", 5)
    if status and status.get("indexAvailable"):
        click.echo(f"Backend already running on port {port} — attaching (won't stop on exit)")
        attached = True
    else:
        click.echo(f"Starting backend on port {port} (clean={clean}, llm={llm})...")
        backend_info = backend_mod.start_backend(clean=clean, port=port, llm=llm)
        click.echo(f"Backend ready (PID={backend_info.proc.pid})")

    click.echo("Press Ctrl-C to stop.")
    try:
        import threading
        threading.Event().wait()
    except KeyboardInterrupt:
        pass
    finally:
        if not attached:
            click.echo("Stopping backend...")
            backend_mod.stop_backend(backend_info.proc)
            click.echo("Stopped.")
        else:
            click.echo("Detached (backend still running).")


@main.command("search")
@click.option("--query", "-q", required=True, help="Search query text.")
@click.option("--mode", "-m", default="hybrid", show_default=True,
              help="Search mode (lexical, hybrid, vector, splade, etc.).")
@click.option("--limit", "-n", default=10, show_default=True)
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--ce/--no-ce", default=None,
              help="Force cross-encoder on/off (overrides mode default).")
@click.pass_context
def cmd_search(ctx, query, mode, limit, base_url, ce):
    """Send a single search query and display the full pipeline response."""
    from . import retriever

    pipeline = None
    if mode in retriever.MODE_PIPELINES:
        pipeline = dict(retriever.MODE_PIPELINES[mode])
        if ce is not None:
            pipeline["crossEncoderEnabled"] = ce

    body: dict = {"query": query, "limit": limit}
    if pipeline:
        body["pipeline"] = pipeline
    else:
        body["mode"] = mode

    try:
        resp = httpx.post(f"{base_url}/api/knowledge/search", json=body, timeout=60)
        resp.raise_for_status()
        data = resp.json()
    except Exception as e:
        click.echo(f"Search failed: {e}", err=True)
        sys.exit(1)

    if ctx.obj.get("json"):
        click.echo(json.dumps(data, indent=2))
        return

    # Human-readable output
    click.echo(f"Query: {query}")
    click.echo(f"Mode: {data.get('effectiveMode', mode)}  Hits: {data.get('totalHits', '?')}")
    click.echo()

    # Pipeline components
    pe = data.get("pipelineExecution", {})
    components = pe.get("components", {})
    if components:
        click.echo("Pipeline:")
        click.echo(f"  retrieval: {pe.get('retrievalMs', '?')}ms")
        for name, comp in sorted(components.items()):
            status = comp.get("status", "?")
            reason = comp.get("reason", "")
            timing_key = f"{name.replace('_', '')}Ms"
            # Try to find timing in pipelineExecution
            ms = pe.get(f"{name}Ms") or pe.get(timing_key)
            ms_str = f" ({ms}ms)" if ms else ""
            reason_str = f" [{reason}]" if reason else ""
            click.echo(f"  {name}: {status}{ms_str}{reason_str}")
        # Explicit CE/LM timing
        ce_ms = pe.get("crossEncoderMs")
        lm_ms = pe.get("lambdaMartMs")
        if ce_ms:
            click.echo(f"  cross_encoder_ms: {ce_ms}")
        if lm_ms:
            click.echo(f"  lambdamart_ms: {lm_ms}")
    click.echo()

    # Results
    results = data.get("results", [])
    click.echo(f"Results ({len(results)}):")
    for i, hit in enumerate(results[:limit], 1):
        fields = hit.get("fields", {})
        title = fields.get("title", "")
        filename = fields.get("filename", "")
        label = title or filename or hit.get("id", "?")
        click.echo(f"  {i:>2}. [{hit.get('score', 0):.3f}] {label[:70]}")
    click.echo()

    # Skip reasons
    skip = data.get("crossEncoderSkipReason")
    if skip:
        click.echo(f"CE skip: {skip}")
    cap = data.get("indexCapabilities", {})
    if cap:
        click.echo(f"Capabilities: embed={cap.get('embeddingCoverage', 0):.0%} "
                    f"splade={cap.get('spladeCoverage', 0):.0%} "
                    f"ce_avail={cap.get('crossEncoderAvailable')}")


@main.command("logs")
@click.option("--source", type=click.Choice(["worker", "head", "all"]),
              default="worker", show_default=True)
@click.option("--filter", "-f", "pattern", default=None, help="Filter by message content.")
@click.option("--level", "-l", "min_level", default=None,
              help="Minimum log level (DEBUG, INFO, WARN, ERROR).")
@click.option("--tail", "-t", "tail_mode", is_flag=True, help="Follow log file for new entries.")
@click.option("--lines", "-n", "max_lines", default=50, show_default=True,
              help="Number of recent lines to show (0 = all).")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
def cmd_logs(source, pattern, min_level, tail_mode, max_lines, base_url):
    """Read and filter structured JSON logs from the running backend."""
    from . import preflight

    # Discover data dir
    status = preflight._fetch_endpoint(base_url, "/api/status", 5)
    if status is None:
        click.echo("Error: backend unreachable — cannot discover log path", err=True)
        sys.exit(1)

    index_base = status.get("indexBasePath", "")
    if not index_base:
        click.echo("Error: indexBasePath not available", err=True)
        sys.exit(1)

    data_dir = Path(index_base).parent.parent
    log_files = []
    if source in ("worker", "all"):
        log_files.append(("worker", data_dir / "logs" / "worker.log"))
    if source in ("head", "all"):
        log_files.append(("head", data_dir / "logs" / "app.log"))

    level_order = {"TRACE": 0, "DEBUG": 10, "INFO": 20, "WARN": 30, "ERROR": 40}
    min_level_val = level_order.get((min_level or "").upper(), 0)

    def _parse_and_filter(line: str, src: str) -> str | None:
        try:
            entry = json.loads(line)
        except (json.JSONDecodeError, ValueError):
            return None

        level = entry.get("level", "INFO")
        if level_order.get(level, 20) < min_level_val:
            return None

        msg = entry.get("message", "")
        if pattern and pattern.lower() not in msg.lower():
            return None

        ts = entry.get("@timestamp", "")[-15:-6]  # HH:MM:SS
        logger = entry.get("logger_name", "")
        # Shorten logger: keep last segment
        short_logger = logger.rsplit(".", 1)[-1] if logger else ""
        prefix = f"[{src}] " if source == "all" else ""
        return f"{prefix}{ts} {level:<5} {short_logger}: {msg[:200]}"

    # Read existing lines
    for src, log_path in log_files:
        if not log_path.is_file():
            click.echo(f"Log file not found: {log_path}", err=True)
            continue

        lines = []
        with open(log_path, "r", encoding="utf-8", errors="replace") as f:
            for raw_line in f:
                formatted = _parse_and_filter(raw_line.strip(), src)
                if formatted:
                    lines.append(formatted)

        # Show last N lines
        if max_lines > 0:
            lines = lines[-max_lines:]
        for line in lines:
            click.echo(line)

    # Tail mode
    if tail_mode:
        import time as _time

        # Seek to end of each file
        handles = []
        for src, log_path in log_files:
            if log_path.is_file():
                fh = open(log_path, "r", encoding="utf-8", errors="replace")
                fh.seek(0, 2)  # EOF
                handles.append((src, fh))

        click.echo("--- tailing (Ctrl-C to stop) ---")
        try:
            while True:
                had_output = False
                for src, fh in handles:
                    for raw_line in fh:
                        formatted = _parse_and_filter(raw_line.strip(), src)
                        if formatted:
                            click.echo(formatted)
                            had_output = True
                if not had_output:
                    _time.sleep(0.5)
        except KeyboardInterrupt:
            pass
        finally:
            for _, fh in handles:
                fh.close()


@main.command("datasets")
@click.pass_context
def cmd_datasets(ctx):
    """List available datasets (BEIR, golden, mixed)."""
    from . import corpora

    datasets: list[dict] = []

    # BEIR datasets
    for name in sorted(corpora.BEIR_DATASETS):
        datasets.append({"name": name, "source": "beir", "ir_datasets_id": corpora.BEIR_DATASETS[name]})

    # Local datasets (mixed/, golden/)
    base = corpora._default_base_dir()
    for prefix in ("mixed", "golden"):
        prefix_dir = base / prefix
        if prefix_dir.is_dir():
            for sub in sorted(prefix_dir.iterdir()):
                if sub.is_dir():
                    datasets.append({"name": f"{prefix}/{sub.name}", "source": prefix})

    if ctx.obj.get("json"):
        click.echo(json.dumps(datasets, indent=2))
    else:
        click.echo("Available datasets:")
        for d in datasets:
            click.echo(f"  {d['name']:<30s}  ({d['source']})")


@main.command("modes")
@click.pass_context
def cmd_modes(ctx):
    """List available search modes and their pipeline components."""
    from . import retriever

    modes: dict[str, dict] = {}

    # Client-side modes (explicit pipeline dicts)
    for name, pipeline in sorted(retriever.MODE_PIPELINES.items()):
        components = [k.replace("Enabled", "") for k, v in pipeline.items()
                      if k.endswith("Enabled") and v]
        modes[name] = {"resolution": "client", "components": components}

    # Server-passthrough modes
    modes["hybrid"] = {
        "resolution": "server",
        "components": ["sparse", "dense", "rrf", "lambdamart"],
        "note": "Resolved server-side via SearchMode.HYBRID",
    }

    if ctx.obj.get("json"):
        click.echo(json.dumps(modes, indent=2))
    else:
        click.echo("Available modes:")
        for name, info in sorted(modes.items()):
            comps = ", ".join(info["components"]) if info["components"] else "(default)"
            res = info["resolution"]
            click.echo(f"  {name:<15s}  [{res}]  {comps}")
            if info.get("note"):
                click.echo(f"  {'':15s}  {info['note']}")


@main.command("extract-eval")
@click.option("--variant", required=True, type=click.Path(exists=True),
              help="Directory with extracted .txt files to evaluate")
@click.option("--ground-truth", required=True, type=click.Path(exists=True),
              help="Directory with ground truth .txt files")
@click.option("--manifest", type=click.Path(exists=True), default=None,
              help="Stratified manifest JSON for bracket breakdown (optional)")
@click.option("--reference", type=click.Path(exists=True), default=None,
              help="Reference extraction dir for comparison (e.g., Docling)")
@click.pass_context
def cmd_extract_eval(ctx, variant, ground_truth, manifest, reference):
    """Evaluate extraction quality via word overlap with ground truth."""
    from . import extract_eval

    result = extract_eval.evaluate(
        variant_dir=Path(variant),
        ground_truth_dir=Path(ground_truth),
        manifest_path=Path(manifest) if manifest else None,
        reference_dir=Path(reference) if reference else None,
    )

    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(extract_eval.format_console(result))


# ---------------------------------------------------------------------------
# Output formatting
# ---------------------------------------------------------------------------

def _print_summary(summary: dict) -> None:
    click.echo(f"\nDataset: {summary['dataset']}  ({summary['query_count']} queries)")
    click.echo(f"Git SHA: {summary.get('git_sha', 'unknown')}")
    click.echo()
    for mode, info in summary.get("per_mode", {}).items():
        metrics = info["aggregate_metrics"]
        click.echo(f"  {mode}:")
        for k, v in sorted(metrics.items()):
            click.echo(f"    {k}: {v:.4f}")
        lat = info.get("latency_stats", {})
        if lat.get("query_count"):
            click.echo(
                f"    latency: p50={lat['p50_ms']}ms  p95={lat['p95_ms']}ms  "
                f"p99={lat['p99_ms']}ms  mean={lat['mean_ms']}ms"
            )
        click.echo(f"    comparable: {info['comparable']}")
        if not info["comparable"]:
            click.echo(f"    reasons: {info['comparability_reasons']}")
        click.echo()


def _print_comparison(results: dict) -> None:
    click.echo("\nMetric comparison (run B vs run A):")
    click.echo(f"{'Metric':<12} {'Mean A':>8} {'Mean B':>8} {'Delta':>8} {'p-value':>8} {'d_z':>6} {'95% CI':>18}")
    click.echo("-" * 72)
    for metric, r in sorted(results.items()):
        ci = f"[{r['ci_95'][0]:+.4f}, {r['ci_95'][1]:+.4f}]"
        click.echo(
            f"{metric:<12} {r['mean_a']:8.4f} {r['mean_b']:8.4f} "
            f"{r['delta']:+8.4f} {r['p_value']:8.4f} {r['cohens_d_z']:6.3f} {ci:>18}"
        )
    click.echo()


@main.command("ner-eval")
@click.option(
    "--model-dir",
    type=click.Path(exists=True),
    default="models/onnx/ner",
    show_default=True,
    help="Path to NER ONNX model directory (must contain model.onnx + tokenizer.json).",
)
@click.option("--max-sentences", default=0, show_default=True, help="Cap sentences (0 = all).")
@click.option("--data-dir", type=click.Path(exists=True, resolve_path=True), default=None,
              help="CoNLL-2003 data directory (default: jseval/data/conll2003/).")
@click.pass_context
def cmd_ner_eval(ctx, model_dir, max_sentences, data_dir):
    """Evaluate NER model quality against CoNLL-2003 (entity-level F1)."""
    from . import ner_eval

    verbose = ctx.obj.get("json", False) is False
    results = ner_eval.run_ner_eval(
        model_dir=Path(model_dir),
        max_sentences=max_sentences,
        verbose=verbose,
        data_dir=Path(data_dir) if data_dir else None,
    )

    if ctx.obj.get("json"):
        click.echo(json.dumps(results, indent=2, default=str))
    else:
        ner_eval.print_ner_eval_results(results)


def _print_pipeline_comparison(diff: dict) -> None:
    click.echo("Pipeline timing comparison:")
    for key, comp in sorted(diff.items()):
        if isinstance(comp, dict) and "a" in comp and "b" in comp:
            a_val = comp["a"]
            b_val = comp["b"]
            delta = comp.get("delta", 0)
            ratio = comp.get("ratio")
            ratio_str = f" ({ratio:.2f}x)" if ratio else ""
            flag = " REGRESSED" if comp.get("regressed") else ""
            click.echo(f"  {key}: {a_val} -> {b_val} (delta={delta:+.1f}){ratio_str}{flag}")
    click.echo()


def _print_decision_kind_comparison(bucket_results: dict) -> None:
    """Tempdoc 525: render per-decision-kind metric deltas."""
    click.echo("Per-decision-kind metric comparison (tempdoc 525):")
    for kind in sorted(bucket_results.keys()):
        bucket = bucket_results[kind]
        count_a = bucket.get("count_a", 0)
        count_b = bucket.get("count_b", 0)
        click.echo(f"  decision_kind={kind} (n_a={count_a}, n_b={count_b}):")
        metrics = bucket.get("metrics", {})
        for metric in sorted(metrics.keys()):
            m = metrics[metric]
            mean_a = m.get("mean_a")
            mean_b = m.get("mean_b")
            delta = m.get("delta")
            if mean_a is None or mean_b is None:
                continue
            click.echo(
                f"    {metric}: {mean_a:.3f} -> {mean_b:.3f} (delta={delta:+.3f})"
            )
    click.echo()


def _compare_by_decision_kind(a_data: dict, b_data: dict, qrels: dict) -> dict:
    """Tempdoc 525: per-decision-kind metric breakdown.

    Buckets the per-query entries from both runs by
    ``entry["decision_kind"]`` (sourced from
    ``SearchIntrospection.decision.kind``) and computes mean / delta per
    metric per bucket. ``None`` decision_kind values land in ``"unknown"``.
    Returns an empty dict when no per-query entries are available.
    """
    a_entries = a_data.get("per_query_entries", [])
    b_entries = b_data.get("per_query_entries", [])
    if not a_entries or not b_entries:
        return {}

    metric_keys = {
        "ndcgAtK": "nDCG@10",
        "apAtK": "AP@10",
        "mrrAtK": "RR@10",
        "recallAtK": "R@10",
        "p1AtK": "P@1",
    }

    def _bucket(entries: list[dict]) -> dict[str, dict]:
        out: dict[str, dict] = {}
        for entry in entries:
            kind = entry.get("decision_kind") or "unknown"
            cell = out.setdefault(kind, {"count": 0, "sums": {}, "counts": {}})
            cell["count"] += 1
            for json_key, metric_label in metric_keys.items():
                raw = entry.get(json_key)
                if raw is None:
                    continue
                try:
                    val = float(raw)
                except (TypeError, ValueError):
                    continue
                cell["sums"][metric_label] = cell["sums"].get(metric_label, 0.0) + val
                cell["counts"][metric_label] = cell["counts"].get(metric_label, 0) + 1
        return out

    a_buckets = _bucket(a_entries)
    b_buckets = _bucket(b_entries)
    all_kinds = set(a_buckets.keys()) | set(b_buckets.keys())
    results: dict[str, dict] = {}
    for kind in all_kinds:
        a_cell = a_buckets.get(kind, {"count": 0, "sums": {}, "counts": {}})
        b_cell = b_buckets.get(kind, {"count": 0, "sums": {}, "counts": {}})
        metrics: dict[str, dict] = {}
        all_metrics = set(a_cell["sums"].keys()) | set(b_cell["sums"].keys())
        for metric in all_metrics:
            n_a = a_cell["counts"].get(metric, 0)
            n_b = b_cell["counts"].get(metric, 0)
            mean_a = a_cell["sums"][metric] / n_a if n_a else None
            mean_b = b_cell["sums"][metric] / n_b if n_b else None
            delta = (
                mean_b - mean_a if (mean_a is not None and mean_b is not None) else None
            )
            metrics[metric] = {
                "mean_a": mean_a,
                "mean_b": mean_b,
                "delta": delta,
                "n_a": n_a,
                "n_b": n_b,
            }
        results[kind] = {
            "count_a": a_cell["count"],
            "count_b": b_cell["count"],
            "metrics": metrics,
        }
    return results


_DEFAULT_BASE_URL_EVAL = "http://127.0.0.1:33221"


@main.command("retrieval-eval")
@click.option("--queries", type=click.Path(exists=True), required=True,
              help="Path to eval queries JSON (MultiHop-RAG format).")
@click.option("--corpus-dir", type=click.Path(exists=True), required=True,
              help="Path to corpus directory (for title→filename mapping).")
@click.option("--base-url", default=_DEFAULT_BASE_URL_EVAL, show_default=True)
@click.option("--top-k", default=10, show_default=True)
@click.option("--max-tokens", default=8192, show_default=True)
@click.option("--types", default=None, help="Comma-separated question types to include.")
@click.option("--max-queries", default=None, type=int, help="Limit number of queries.")
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_retrieval_eval(ctx, queries, corpus_dir, base_url, top_k, max_tokens, types, max_queries, output_dir):
    """Tier 1: Evaluate retrieval quality with Hits@K, MRR, and evidence recall ($0 cost)."""
    from . import agent_retrieval_eval as are

    qa = are.load_queries(Path(queries))
    question_types = [t.strip() for t in types.split(",")] if types else None
    result = are.run_retrieval_eval(
        qa, base_url=base_url, top_k=top_k, max_tokens=max_tokens,
        question_types=question_types, max_queries=max_queries,
        corpus_dir=Path(corpus_dir),
    )

    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(are.format_retrieval_console(result))

    if output_dir:
        out = Path(output_dir)
        out.mkdir(parents=True, exist_ok=True)
        (out / "retrieval-eval.json").write_text(
            json.dumps(result, indent=2, default=str), encoding="utf-8")
        click.echo(f"Written to {out / 'retrieval-eval.json'}")


@main.command("tier2-eval")
@click.option("--queries", type=click.Path(exists=True), required=True,
              help="Path to eval queries JSON (MultiHop-RAG format).")
@click.option("--base-url", default=_DEFAULT_BASE_URL_EVAL, show_default=True)
@click.option("--llm-url", default="http://127.0.0.1:8080", show_default=True,
              help="llama-server URL for chat completions.")
@click.option("--top-k", default=10, show_default=True)
@click.option("--max-context-tokens", default=8192, show_default=True)
@click.option("--types", default=None, help="Comma-separated question types to include.")
@click.option("--max-queries", default=None, type=int, help="Limit number of queries.")
@click.option("--no-structured", is_flag=True, help="Disable structured JSON output (plain text answers).")
@click.option("--paper-prompt", is_flag=True, help="Use the original MultiHop-RAG paper prompt (ablation for fair comparison).")
@click.option("--source-check", is_flag=True, help="Enable pre-retrieval source existence check (deterministic abstention for absent sources).")
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_tier2_eval(ctx, queries, base_url, llm_url, top_k, max_context_tokens,
                   types, max_queries, no_structured, paper_prompt, source_check, output_dir):
    """Tier 2: Single-shot RAG eval (retrieve + local LLM, $0 cost)."""
    from . import agent_retrieval_eval as are

    qa = are.load_queries(Path(queries))
    question_types = [t.strip() for t in types.split(",")] if types else None
    cp_dir = Path(output_dir) if output_dir else None
    result = are.run_tier2_eval(
        qa, base_url=base_url, llm_url=llm_url, top_k=top_k,
        max_context_tokens=max_context_tokens,
        question_types=question_types, max_queries=max_queries,
        structured=not no_structured,
        use_paper_prompt=paper_prompt,
        source_check=source_check,
        checkpoint_dir=cp_dir,
    )

    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(are.format_tier2_console(result))

    if output_dir:
        out = Path(output_dir)
        out.mkdir(parents=True, exist_ok=True)
        (out / "tier2-eval.json").write_text(
            json.dumps(result, indent=2, default=str), encoding="utf-8")
        click.echo(f"Written to {out / 'tier2-eval.json'}")


@main.command("agent-eval")
@click.option("--queries", type=click.Path(exists=True), required=True,
              help="Path to eval queries JSON (MultiHop-RAG format).")
@click.option("--corpus-dir", type=click.Path(exists=True), required=True,
              help="Path to corpus directory with documents.")
@click.option("--mcp-config", type=click.Path(exists=True), default=None,
              help="Path to JustSearch MCP config JSON (for conditions B/C).")
@click.option("--model", default="haiku", show_default=True)
@click.option("--condition", type=click.Choice(["A", "B", "C"]), default="A", show_default=True,
              help="A=file tools only, B=file+JustSearch, C=JustSearch only.")
@click.option("--types", default=None, help="Comma-separated question types to include.")
@click.option("--max-queries", default=None, type=int, help="Limit number of queries.")
@click.option("--max-budget", default=0.50, show_default=True, help="Max USD per query.")
@click.option("--parallel", default=1, show_default=True,
              help="Run N queries concurrently (default 1 = sequential).")
@click.option("--seeds", default=1, show_default=True, type=int,
              help="Run N independent repeats (seeds 0..N-1); seed-suffixes the "
                   "output so utility-compose can aggregate the seed envelope (tempdoc 624 R3).")
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_agent_eval(ctx, queries, corpus_dir, mcp_config, model, condition,
                   types, max_queries, max_budget, parallel, seeds, output_dir):
    """Phase 2: Compare agent performance with/without JustSearch MCP tools."""
    from . import agent_retrieval_eval as are

    qa = are.load_queries(Path(queries))
    question_types = [t.strip() for t in types.split(",")] if types else None
    for seed in range(max(1, seeds)):
        result = are.run_agent_eval(
            qa, corpus_dir=corpus_dir, mcp_config_path=mcp_config,
            model=model, condition=condition, max_queries=max_queries,
            max_budget_per_query=max_budget, question_types=question_types,
            parallel=parallel,
        )
        result["seed"] = seed

        if ctx.obj.get("json"):
            click.echo(json.dumps(result, indent=2, default=str))
        else:
            click.echo(are.format_agent_console(result))

        if output_dir:
            out = Path(output_dir)
            out.mkdir(parents=True, exist_ok=True)
            suffix = f"-seed{seed}" if seeds > 1 else ""
            fname = f"agent-eval-{condition}-{model}{suffix}.json"
            (out / fname).write_text(
                json.dumps(result, indent=2, default=str), encoding="utf-8")
            click.echo(f"Written to {out / fname}")


@main.command("utility-compose")
@click.option("--run", "runs", multiple=True, metavar="COND=PATH",
              help="Repeatable. An agent-eval result JSON tagged by condition, "
                   "e.g. --run A=out/agent-eval-A-haiku.json --run C=out/agent-eval-C-haiku.json. "
                   "Repeats of one condition are assigned successive seeds.")
@click.option("--dataset", required=True, help="Corpus slug, e.g. mixed/multihop-rag.")
@click.option("--corpus-signature", default=None, help="Corpus signature/sha256 (pairing identity).")
@click.option("--model", default="haiku", show_default=True)
@click.option("--search-config-key", default=None,
              help="623 config_cohort_key of the live search backend (the with-tool arm's identity).")
@click.option("--contamination-class",
              type=click.Choice(["public-pre-cutoff", "post-cutoff", "private-synthetic", "unknown"]),
              default="public-pre-cutoff", show_default=True)
@click.option("--confidence-tier", type=click.Choice(["A", "B", "C"]), default="C", show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_utility_compose(ctx, runs, dataset, corpus_signature, model, search_config_key,
                        contamination_class, confidence_tier, output_dir):
    """Compose agent-eval results into a utility-comparison.v1 record (tempdoc 624).

    Attaches a cohort identity to each run (agent_manifest), pairs the with/without
    -tool arms on condition, aggregates seeds, and emits the canonical record plus
    an Inspect-EvalLog projection. Pure composition over existing run artifacts.
    """
    import datetime as _dt

    from . import agent_utility_run as aur
    from . import utility_comparison as uc

    corpus = {"dataset": dataset, "signature": corpus_signature or dataset}
    prompt_template = (
        "Answer the following question using only the documents in <corpus>. "
        "Do not use prior knowledge. Be concise. Question: <query>"
    )
    cli_version = aur.claude_cli_version()
    seed_counter: dict = {}
    summaries = []
    for spec in runs:
        if "=" not in spec:
            raise click.BadParameter(f"--run must be COND=PATH, got {spec!r}")
        cond, path = spec.split("=", 1)
        cond = cond.strip().upper()
        result = json.loads(Path(path).read_text(encoding="utf-8"))
        seed = result.get("seed")
        if seed is None:
            seed = seed_counter.get(cond, 0)
            seed_counter[cond] = seed + 1
        with_tool = cond in ("B", "C")
        summaries.append(aur.build_compose_summary(
            result, condition=cond, model=model, corpus=corpus, seed=seed,
            prompt_template=prompt_template, cli_version=cli_version,
            search_config_cohort_key=(search_config_key if with_tool else None),
        ))

    record = uc.compose_utility(
        summaries,
        composed_at=_dt.datetime.now(_dt.timezone.utc).isoformat(),
        external_baselines=uc.CITED_BASELINES,
        contamination_class=contamination_class,
        confidence_tier=confidence_tier,
    )

    if ctx.obj.get("json"):
        click.echo(json.dumps(record, indent=2, default=str))
    else:
        for slug, by_model in record["measured"].items():
            for m, cell in by_model.items():
                acc, tok = cell["accuracy"], cell["tokens_unique"]
                click.echo(
                    f"[{slug}/{m}] acc {acc['baseline']}->{acc['with_tool']} "
                    f"(d={acc['delta']:+}, McNemar p={acc['mcnemar_p']}); "
                    f"unique-tokens median {tok['baseline']['median']}->"
                    f"{tok['with_tool']['median']} (d_mean={tok['delta_mean']:+})")
        click.echo(f"seeds={record['seed_count']} tier={record['confidence_tier']} "
                   f"contamination={record['coverage']['contamination_class']}")

    if output_dir:
        out = Path(output_dir)
        out.mkdir(parents=True, exist_ok=True)
        (out / "utility-comparison.v1.json").write_text(
            json.dumps(record, indent=2, default=str), encoding="utf-8")
        click.echo(f"Written record to {out}")


@main.command("utility-run")
@click.option("--queries", required=True, type=click.Path(exists=True), help="MultiHop-RAG-format queries JSON.")
@click.option("--corpus-dir", required=True, type=click.Path(exists=True), help="Corpus dir (for condition-A file tools).")
@click.option("--mcp-config", default=None, type=click.Path(exists=True), help="JustSearch MCP config (for B/C).")
@click.option("--model", default="haiku", show_default=True)
@click.option("--conditions", default="A,C", show_default=True, help="Comma list, e.g. A,C.")
@click.option("--seeds", default=3, show_default=True, type=int, help="Repeats per cell (Inspect epochs).")
@click.option("--concurrency", default=6, show_default=True, type=int, help="Inspect max_samples (B1: 8-way is safe).")
@click.option("--max-queries", default=None, type=int)
@click.option("--max-budget", default=0.50, show_default=True, help="Max USD per cell.")
@click.option("--timeout-s", default=180, show_default=True, type=int, help="Per-cell timeout (calibrate sets ~2x contended-p95).")
@click.option("--calibration", default=None, type=click.Path(exists=True), help="calibration.json from `utility-calibrate` (overrides timeout/concurrency/search-key + filters queries).")
@click.option("--dataset", required=True, help="Corpus slug, e.g. mixed/multihop-rag.")
@click.option("--corpus-signature", default=None)
@click.option("--mcp-tool-surface-hash", default=None, help="Hash of the live /mcp tools/list (cohort identity).")
@click.option("--search-config-key", default=None, help="623 config_cohort_key of the with-tool backend.")
@click.option("--contamination-class",
              type=click.Choice(["public-pre-cutoff", "post-cutoff", "private-synthetic", "unknown"]),
              default="public-pre-cutoff", show_default=True)
@click.option("--confidence-tier", type=click.Choice(["A", "B", "C"]), default="C", show_default=True)
@click.option("--log-dir", required=True, type=click.Path(), help="Inspect log dir (re-run = resume).")
@click.option("--output-dir", default=None, type=click.Path())
@click.pass_context
def cmd_utility_run(ctx, queries, corpus_dir, mcp_config, model, conditions, seeds, concurrency,
                    max_queries, max_budget, timeout_s, calibration, dataset, corpus_signature,
                    mcp_tool_surface_hash, search_config_key, contamination_class, confidence_tier,
                    log_dir, output_dir):
    """Run the agent-utility matrix THROUGH Inspect AI (resumable) and compose (tempdoc 624).

    Requires the `agent` extra: `pip install jseval[agent]`. condition=task,
    seed=epoch, sample.id=query, cohort=task-args. `eval_set` makes re-runs resume
    (skip completed cells). Then reads the EvalLogs into `utility-comparison.v1`.
    """
    import datetime as _dt
    import os

    from . import agent_utility_inspect as aui
    from . import agent_utility_run as aur
    from . import utility_comparison as uc

    conds = tuple(c.strip().upper() for c in conditions.split(",") if c.strip())
    cli_version = aur.claude_cli_version()
    # Calibration (from `utility-calibrate`) overrides timeout/concurrency/search-key
    # and filters the queries to the closed-book-retained (retrieval-relevant) set.
    calib_readiness = None  # threaded into the run's comparability verdict (readiness ∧ error_rate)
    if calibration:
        from .types import ReadinessResult
        calib = json.loads(Path(calibration).read_text(encoding="utf-8"))
        timeout_s = calib.get("timeout_s", timeout_s)
        concurrency = calib.get("concurrency", concurrency)
        search_config_key = calib.get("config_cohort_key", search_config_key)
        if not calib.get("readiness_passed", True):
            click.echo(f"WARNING: backend readiness FAILED at calibration: {calib.get('readiness_reasons')}")
            calib_readiness = ReadinessResult(
                passed=False, failure_reasons=calib.get("readiness_reasons", []))
        idx = calib.get("retained_query_indices")
        if idx is not None:
            rows = json.loads(Path(queries).read_text(encoding="utf-8"))
            kept = [rows[i] for i in idx if i < len(rows)]
            # STABLE path (next to the calibration) so queries_path — a task-identity
            # arg — is constant across re-runs and eval_set can resume (D2).
            stable_q = Path(calibration).parent / "_calibrated_queries.json"
            stable_q.write_text(json.dumps(kept), encoding="utf-8")
            queries = str(stable_q)
            click.echo(f"calibration: timeout={timeout_s}s concurrency={concurrency} "
                       f"queries={len(kept)} (dropped {calib.get('n_dropped_contaminated', 0)} contaminated)")
    # corpus-dir + mcp-config must be ABSOLUTE (the solver runs claude from a temp cwd).
    aui.run_utility_eval(
        queries_path=queries, corpus_dir=os.path.abspath(corpus_dir),
        mcp_config=(os.path.abspath(mcp_config) if mcp_config else None),
        model=model, conditions=conds, seeds=seeds, concurrency=concurrency,
        log_dir=log_dir, max_queries=max_queries, max_budget=max_budget, timeout_s=timeout_s,
        cli_version=cli_version, mcp_tool_surface_hash=mcp_tool_surface_hash,
        corpus_dataset=dataset, corpus_signature=corpus_signature or dataset,
    )
    summaries = aur.eval_logs_to_summaries(log_dir, search_config_cohort_key=search_config_key)
    # Run-governance: derive the comparability verdict from per-arm loss-accounting.
    from . import utility_governance as ug
    arms = ug.compute_loss_accounting(log_dir)
    verdict, gmetrics = ug.paired_comparability(arms, calib_readiness)
    governance = {
        "comparable": verdict.comparable, "reasons": verdict.reasons, "metrics": gmetrics,
        "per_arm_loss": {c: {"n_attempted": l.n_attempted, "n_completed": l.n_completed,
                             "n_excluded": l.n_excluded, "exclusion_rate": round(l.exclusion_rate, 4)}
                         for c, l in arms.items()},
    }
    record = uc.compose_utility(
        summaries, composed_at=_dt.datetime.now(_dt.timezone.utc).isoformat(),
        external_baselines=uc.CITED_BASELINES, contamination_class=contamination_class,
        confidence_tier=confidence_tier, governance=governance,
    )
    if ctx.obj.get("json"):
        click.echo(json.dumps(record, indent=2, default=str))
    else:
        for slug, by_model in record["measured"].items():
            for m, cell in by_model.items():
                acc, tok = cell["accuracy"], cell["tokens_unique"]
                click.echo(f"[{slug}/{m}] acc {acc['baseline']}->{acc['with_tool']} "
                           f"(d={acc['delta']:+}, McNemar p={acc['mcnemar_p']}); "
                           f"unique-tokens median {tok['baseline']['median']}->"
                           f"{tok['with_tool']['median']} (d_mean={tok['delta_mean']:+})")
        click.echo(f"COMPARABLE={verdict.comparable}" + ("" if verdict.comparable
                   else " — reasons: " + "; ".join(verdict.reasons)))
        click.echo(f"seeds={record['seed_count']} tier={record['confidence_tier']} "
                   f"contamination={record['coverage']['contamination_class']}")
    if output_dir:
        out = Path(output_dir)
        out.mkdir(parents=True, exist_ok=True)
        (out / "utility-comparison.v1.json").write_text(
            json.dumps(record, indent=2, default=str), encoding="utf-8")
        click.echo(f"Written record to {out} (Inspect logs in {log_dir})")


# --- Query Understanding spike (363) ---
from .qu_spike import qu_spike as _qu_spike_cmd  # noqa: E402
main.add_command(_qu_spike_cmd)
from .qu_v3_eval import qu_v3 as _qu_v3_cmd  # noqa: E402
main.add_command(_qu_v3_cmd)


# --- Non-determinism envelope calibration (tempdoc 400 LR1-b) ---


@main.command("recalibrate-nightly-baseline")
@click.option("--data-dir", type=click.Path(exists=True, resolve_path=True), required=True,
              help="Data dir containing cohort_baselines/ (new layout).")
@click.option("--cohort-hash", required=True,
              help="Cohort whose envelope should be sampled.")
@click.option("--metric", default="nDCG@10", show_default=True,
              help="Metric whose stdev drives the nightly gate threshold.")
@click.option("--mode", default="full", show_default=True,
              help="Envelope mode (typically 'full').")
@click.option("--output", type=click.Path(), default=None,
              help="Optional env file to write "
                   "`PHASE3_BASELINE_NDCG10_STDEV=<value>` to. When "
                   "omitted, prints the value to stdout.")
@click.pass_context
def cmd_recalibrate_nightly_baseline(ctx, data_dir, cohort_hash, metric,
                                      mode, output):
    """Read current σ(metric) for a cohort; prep nightly env update (Phase 6 / 6.6).

    Reads `<data_dir>/cohort_baselines/<cohort-hash>/envelope.json`,
    extracts `metrics[mode][metric].stdev`, and either prints it OR
    writes a sourceable env file. Operators use this when infrastructure
    drift (GPU driver update, model reload) is genuine and the nightly
    gate needs rebasing — see `docs/how-to/recalibrate-phase3-baseline.md`
    for the full workflow.
    """
    from . import cohort_baselines

    env_path = cohort_baselines.envelope_path(Path(data_dir), cohort_hash)
    if not env_path.is_file():
        click.echo(
            f"Error: envelope not found at {env_path}. "
            f"Run `jseval calibrate --dataset X` first.",
            err=True,
        )
        sys.exit(2)
    envelope = json.loads(env_path.read_text(encoding="utf-8"))
    metrics_block = (envelope.get("metrics") or {}).get(mode) or {}
    metric_block = metrics_block.get(metric) or {}
    stdev = metric_block.get("stdev")
    if stdev is None:
        click.echo(
            f"Error: envelope has no {metric} stdev under mode={mode}",
            err=True,
        )
        sys.exit(2)

    # Workflow env-var name mirrors the nightly workflow.
    env_var_name = f"PHASE3_BASELINE_{metric.replace('@', '').upper()}_STDEV"
    line = f"{env_var_name}={stdev}"
    if output:
        Path(output).write_text(line + "\n", encoding="utf-8")
        click.echo(
            f"wrote {line} to {output} (source this file in the nightly "
            f"workflow env block to accept drift)"
        )
    else:
        click.echo(line)


@main.command("gate")
@click.option("--data-dir", required=True, type=click.Path(exists=True, resolve_path=True),
              help="Data dir containing cohort_baselines/ + eval-results/.")
@click.option("--baseline-stdev", required=True, type=float,
              help="Reference stdev(nDCG@10) from B2 calibration (gate threshold).")
@click.option("--tolerance-pct", required=True, type=float,
              help="Drift tolerance band as a percent of the baseline stdev.")
@click.option("--report-out", type=click.Path(), default=None,
              help="Write the full gate decision JSON to this path.")
@click.pass_context
def cmd_gate(ctx, data_dir, baseline_stdev, tolerance_pct, report_out):
    """Phase 3 observability nightly gate (Phase 6 / 6.13).

    Validates the calibrated envelope + latest eval-results run matches
    the expected drift band and that required LR4 projections all
    produced outputs. Exit code 0 = pass, 1 = quality/layout drift,
    2 = infra issue (no envelope / run dir). The nightly workflow
    opens a GitHub issue on any non-zero exit.

    Moved from ``scripts/ci/phase3_observability_gate.py`` into the
    jseval package so operators get discovery via ``jseval --help``
    alongside every other Phase 3 subcommand.
    """
    from . import gate as _gate

    report = _gate.evaluate(
        Path(data_dir), baseline_stdev, tolerance_pct,
    )

    if report_out:
        out_path = Path(report_out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(
            json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False),
            encoding="utf-8",
        )

    # Legible stderr summary for CI logs (full JSON is in --report-out).
    summary = {
        "exit_code": report["exit_code"],
        "measured_stdev": report.get("measured_stdev"),
        "baseline_stdev": report["baseline_stdev"],
        "cohort_hash": report.get("cohort_hash"),
        "checks": {c["name"]: c["status"] for c in report["checks"]},
    }
    click.echo(json.dumps(summary, indent=2), err=True)
    sys.exit(report["exit_code"])


@main.command("relevance-gate")
@click.option("--data-dir", required=True, type=click.Path(exists=True, resolve_path=True),
              help="Data dir containing eval-results/ (the latest run's summary.json is checked).")
@click.option("--dataset", required=True,
              help="Dataset slug to gate (e.g. beir/scifact).")
@click.option("--baselines", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Path to relevance-ratchet baselines.v1.json "
                   "(default: repo gates/relevance-ratchet/baselines.v1.json).")
@click.option("--run-dir", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Specific run dir (with summary.json). Default: latest under data-dir/eval-results.")
@click.option("--report-out", type=click.Path(), default=None,
              help="Write the full gate decision JSON to this path.")
@click.pass_context
def cmd_relevance_gate(ctx, data_dir, dataset, baselines, run_dir, report_out):
    """Q-010 relevance ratchet (tempdoc 580 §4c) — fail on nDCG@10 regression.

    Reads the latest eval-results run's summary.json for DATASET and compares
    its nDCG@10 (in the pinned mode) against the per-corpus floor in
    gates/relevance-ratchet/baselines.v1.json. Unlike ``gate`` (which checks
    nDCG@10 *stdev* for drift), this checks the *mean* against a regression
    floor. Exit 0 = no regression (or un-pinned dataset), 1 = regression,
    2 = run metric missing.
    """
    from . import ratchet_kernel as _rk
    from . import relevance_gate as _rgate

    if baselines is None:
        # Baselines live with the jseval consumer (this is a jseval gate, not a kernel gate).
        baselines = Path(__file__).resolve().parents[1] / "relevance-ratchet-baselines.v1.json"
    # Tempdoc 640 K: the shared load→project→locate→evaluate→report flow lives in ratchet_kernel.
    # The `current_release` projection (tempdoc 623 T-5: floors PROJECTED live, never hand-typed)
    # reads this gate's tolerance from the baselines doc (the projector's 2nd arg).
    baselines_doc = _rk.load_baselines_doc(
        baselines,
        project_release=lambda rel, base: _rgate.project_release_to_baselines(
            rel,
            tolerance_default_abs=base.get("tolerance_default_abs", 0.02),
            per_corpus_tolerance=base.get("per_corpus_tolerance"),
        ),
    )
    rd = _rk.resolve_run_dir(run_dir, data_dir)
    run_summary = json.loads((rd / "summary.json").read_text(encoding="utf-8"))
    report = _rgate.evaluate(baselines_doc, run_summary, dataset)
    _rk.finalize_report(report, run_dir=rd, baselines_path=baselines,
                        report_out=report_out, summary_fields=("current", "baseline", "floor"))


@main.command("perf-gate")
@click.option("--data-dir", required=True, type=click.Path(exists=True, resolve_path=True),
              help="Data dir containing eval-results/ (the latest run's summary.json is checked).")
@click.option("--dataset", required=True,
              help="Dataset slug to gate (e.g. beir/scifact).")
@click.option("--baselines", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Path to perf-ratchet-baselines.v1.json "
                   "(default: repo scripts/jseval/perf-ratchet-baselines.v1.json).")
@click.option("--run-dir", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Specific run dir (with summary.json). Default: latest under data-dir/eval-results.")
@click.option("--report-out", type=click.Path(), default=None,
              help="Write the full gate decision JSON to this path.")
@click.option("--mode", default="hybrid", show_default=True,
              help="Mode to pin when --update-baseline.")
@click.option("--update-baseline", is_flag=True,
              help="Re-pin the floor for --dataset from the selected run (project its "
                   "measured metrics into the baselines file), then exit 0 without gating. "
                   "Use after a deliberate, justified perf change.")
@click.pass_context
def cmd_perf_gate(ctx, data_dir, dataset, baselines, run_dir, report_out, mode, update_baseline):
    """Performance ratchet (tempdoc 640) — fail on a latency/throughput/footprint regression.

    The perf-metric-family sibling of ``relevance-gate``. Reads the latest eval-results run's
    summary.json (+ manifest.json for footprint) for DATASET and compares each pinned metric
    against its RELATIVE band in scripts/jseval/perf-ratchet-baselines.v1.json (no absolute
    SLO — tempdoc 640 §C-6). Gate-able metrics: cross-encoder STAGE p50 latency, primary +
    enrichment throughput, retrieval ONNX footprint (best-effort). Exit 0 = no regression
    (or un-pinned dataset), 1 = regression, 2 = a pinned metric missing from the run.
    """
    from . import perf_gate as _pgate
    from . import ratchet_kernel as _rk

    if baselines is None:
        # Baselines live with the jseval consumer (a jseval gate, not a kernel gate).
        baselines = Path(__file__).resolve().parents[1] / "perf-ratchet-baselines.v1.json"
    # Tempdoc 640 K: shared load + `current_release` projection (the perf floor projects from the
    # canonical release, closing the per-run fork) via the kernel; the perf-specific dataset guard +
    # --update-baseline stay below.
    baselines_doc = _rk.load_baselines_doc(
        baselines,
        project_release=lambda rel, base: _pgate.project_release_to_perf_baselines(rel),
    )
    rd = _rk.resolve_run_dir(run_dir, data_dir)
    run_summary = json.loads((rd / "summary.json").read_text(encoding="utf-8"))
    manifest_path = rd / "manifest.json"
    run_manifest = (json.loads(manifest_path.read_text(encoding="utf-8"))
                    if manifest_path.is_file() else None)

    # Guard against comparing a run of a different corpus against this dataset's baseline
    # (review fix #4); also protects --update-baseline from pinning the wrong run.
    if not _pgate.run_dataset_ok(run_manifest, dataset):
        click.echo(json.dumps({
            "exit_code": 2,
            "error": f"latest run is for '{(run_manifest or {}).get('dataset')}', "
                     f"not '{dataset}' -- pass --run-dir for the right run",
        }, indent=2), err=True)
        sys.exit(2)

    if update_baseline:
        # Re-pin the floor from this (green) run -- measured, never hand-typed (review fix #3).
        modes_present = list((run_summary.get("per_mode") or {}).keys())
        use_mode = mode if mode in modes_present else (modes_present[0] if modes_present else mode)
        src = ((run_manifest or {}).get("git_sha") or "")[:10]
        proj = _pgate.project_run_to_perf_baselines(
            run_summary, dataset, use_mode, manifest=run_manifest, src=src)
        entry = proj["baselines"][dataset]
        baselines_doc.setdefault("baselines", {})[dataset] = entry
        Path(baselines).write_text(
            json.dumps(baselines_doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        click.echo(json.dumps({
            "updated": dataset, "mode": use_mode, "run_dir": str(rd), "entry": entry,
            "baselines_path": str(baselines),
        }, indent=2), err=True)
        sys.exit(0)

    report = _pgate.evaluate(baselines_doc, run_summary, dataset, manifest=run_manifest)
    _rk.finalize_report(report, run_dir=rd, baselines_path=baselines,
                        report_out=report_out, summary_fields=("mode",))


@main.command("leak-gate")
@click.option("--data-dir", required=True, type=click.Path(exists=True, resolve_path=True),
              help="Data dir containing eval-results/ (the latest run's projection is checked).")
@click.option("--dataset", required=True,
              help="Dataset slug to gate (e.g. mixed/enron-qa).")
@click.option("--baselines", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Path to leak-gate-baselines.v1.json "
                   "(default: jseval/leak-gate-baselines.v1.json).")
@click.option("--run-dir", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Specific run dir (with projections/). Default: latest under data-dir/eval-results.")
@click.option("--report-out", type=click.Path(), default=None,
              help="Write the full gate decision JSON to this path.")
@click.pass_context
def cmd_leak_gate(ctx, data_dir, dataset, baselines, run_dir, report_out):
    """Recall-leak ratchet (tempdoc 636 / register D-005) — fail on leak-rate regression.

    Reads the latest eval-results run's staged_recall_accounting projection for
    DATASET and compares its leak_rate against the per-corpus *ceiling* in
    leak-gate-baselines.v1.json. The recall-survival sibling of ``relevance-gate``.
    Exit 0 = no regression (or un-pinned dataset), 1 = regression, 2 = projection
    missing.
    """
    from . import leak_gate as _lgate
    from . import ratchet_kernel as _rk

    if baselines is None:
        baselines = Path(__file__).resolve().parents[1] / "leak-gate-baselines.v1.json"

    def _read_projection(rd: Path):
        # Leak's source is a projection artifact (not the run summary) — exit 2 if it's absent.
        pp = rd / "projections" / "staged_recall_accounting.json"
        if not pp.is_file():
            click.echo(json.dumps(
                {"exit_code": 2, "error": f"no staged_recall_accounting projection in {rd}"},
                indent=2), err=True)
            sys.exit(2)
        return json.loads(pp.read_text(encoding="utf-8"))

    # Tempdoc 640 K: leak is the SIMPLE case (no current_release, no extra guards) — it uses the
    # kernel's `run_gate` convenience directly; only its source reader differs (projection vs summary).
    _rk.run_gate(
        baselines_path=baselines, data_dir=data_dir, run_dir=run_dir, dataset=dataset,
        read_inputs=_read_projection, evaluate=_lgate.evaluate,
        report_out=report_out, summary_fields=("current", "baseline", "floor"),
    )


@main.command("llm-gate")
@click.option("--bench-file", required=True, type=click.Path(exists=True, resolve_path=True),
              help="llm-bench.json produced by `jseval llm-bench` (with AI active).")
@click.option("--baselines", type=click.Path(), default=None,
              help="Path to llm-gen-ratchet-baselines.v1.json (default: jseval/llm-gen-ratchet-baselines.v1.json).")
@click.option("--report-out", type=click.Path(), default=None,
              help="Write the full gate decision JSON to this path.")
@click.option("--update-baseline", is_flag=True,
              help="Re-pin the floor from this (green) bench via project_bench_to_llm_baselines.")
@click.pass_context
def cmd_llm_gate(ctx, bench_file, baselines, report_out, update_baseline):
    """LLM-generation-latency ratchet (tempdoc 640 L) — fail on a TTFT / e2e regression.

    The inference-runtime sibling of ``perf-gate``. Reads an ``llm-bench.json`` (``statistics.<metric>.median``)
    and compares TTFT + end-to-end summarization p50 against the RELATIVE floor in
    llm-gen-ratchet-baselines.v1.json. Per machine/config, not per corpus (the bench is corpus-agnostic).
    Exit 0 = no regression (or un-pinned), 1 = regression, 2 = a pinned metric missing. (tokens/sec is
    deferred — the chat SSE emits no token usage.)
    """
    from . import llm_gate as _lg
    from . import ratchet_kernel as _rk

    if baselines is None:
        baselines = Path(__file__).resolve().parents[1] / "llm-gen-ratchet-baselines.v1.json"
    baselines_doc = json.loads(Path(baselines).read_text(encoding="utf-8"))
    bench_doc = json.loads(Path(bench_file).read_text(encoding="utf-8"))

    if update_baseline:
        from .manifest import _git_sha_full
        proj = _lg.project_bench_to_llm_baselines(bench_doc, src=(_git_sha_full() or "")[:10])
        baselines_doc = {**baselines_doc, **proj}
        Path(baselines).write_text(
            json.dumps(baselines_doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        click.echo(json.dumps({"updated": "llm-gen", "metrics": proj["metrics"],
                               "baselines_path": str(baselines)}, indent=2), err=True)
        sys.exit(0)

    report = _lg.evaluate(baselines_doc, bench_doc)
    _rk.finalize_report(report, run_dir=Path(bench_file), baselines_path=baselines,
                        report_out=report_out, summary_fields=())


@main.command("leak-gate-derive")
@click.option("--data-dir", required=True, type=click.Path(exists=True, resolve_path=True),
              help="Data dir containing eval-results/ (each dataset's latest run projection is read).")
@click.option("--datasets", required=True, help="Comma-separated dataset slugs to pin (e.g. beir/scifact,mixed/enron-qa).")
@click.option("--out", type=click.Path(), default=None,
              help="Write the baselines JSON here (default: jseval/leak-gate-baselines.v1.json).")
@click.option("--tolerance", type=float, default=None,
              help="Default tolerance_abs added on top of the measured ceiling (else leak_gate.DEFAULT_TOLERANCE_ABS).")
@click.pass_context
def cmd_leak_gate_derive(ctx, data_dir, datasets, out, tolerance):
    """Derive leak-gate ceilings from each dataset's latest run projection (measured, not hand-typed).

    The recall-survival analogue of the relevance ratchet's release-projection (tempdoc 623 anti-fork):
    a corpus's pinned ``leak_rate_max`` is its *measured* leak rate in the latest multi-mode run, so there
    is no table of numbers to drift. ``evaluate`` adds ``tolerance_abs`` on top.
    """
    from . import leak_gate as _lgate

    eval_results = Path(data_dir) / "eval-results"
    slugs = [s.strip() for s in datasets.split(",") if s.strip()]
    projections: dict = {}
    for slug in slugs:
        suffix = "_" + slug.replace("/", "_")
        cands = sorted(
            (p for p in eval_results.iterdir()
             if p.is_dir() and p.name.endswith(suffix)
             and (p / "projections" / "staged_recall_accounting.json").is_file()),
            key=lambda p: p.name, reverse=True)
        if not cands:
            click.echo(f"WARN: no run with a staged_recall_accounting projection for {slug}", err=True)
            continue
        projections[slug] = json.loads(
            (cands[0] / "projections" / "staged_recall_accounting.json").read_text(encoding="utf-8"))

    kwargs = {} if tolerance is None else {"tolerance_default_abs": tolerance}
    derived = _lgate.derive_baselines(projections, **kwargs)
    out_path = Path(out) if out else (Path(__file__).resolve().parents[1] / "leak-gate-baselines.v1.json")
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(
        json.dumps(derived, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    click.echo(json.dumps(
        {"out": str(out_path), "pinned": derived["baselines"]}, indent=2), err=True)


@main.command("recall-profile")
@click.option("--data-dir", required=True, type=click.Path(exists=True, resolve_path=True),
              help="Data dir containing eval-results/ (the latest run per dataset is re-produced).")
@click.option("--datasets", required=True, help="Comma-separated dataset slugs (e.g. scifact,mixed/enron-qa).")
@click.option("--report-out", type=click.Path(), default=None, help="Write the full profile JSON here.")
@click.pass_context
def cmd_recall_profile(ctx, data_dir, datasets, report_out):
    """Cross-corpus recall-attribution profile (tempdoc 636) — a *profile, not a number*.

    Re-``produce``s the ``staged_recall_accounting`` projection (pure, no backend) for each
    dataset's latest run and aggregates into a regime-blind failure-attribution profile
    (which recall-funnel bucket dominates across the eval set) + a candidate next-lever
    recommendation. Read-only over existing run dirs.
    """
    from . import recall_profile as _rp
    from .projections import staged_recall_accounting as _sra

    eval_results = Path(data_dir) / "eval-results"
    slugs = [s.strip() for s in datasets.split(",") if s.strip()]
    projections: dict = {}
    for slug in slugs:
        suffix = "_" + slug.replace("/", "_")
        # Require a multi-mode run (≥1 leg-mode per_query) + qrels — a hybrid-only run can't
        # produce the staged-recall decomposition, so "latest by name" alone picks wrong.
        cands = sorted(
            (p for p in eval_results.iterdir()
             if p.is_dir() and p.name.endswith(suffix) and (p / "qrels.json").is_file()
             and any((p / f"{m}_per_query.json").is_file() for m in _sra.LEG_MODES)),
            key=lambda p: p.name, reverse=True)
        if not cands:
            click.echo(f"WARN: no multi-mode run dir (leg modes + qrels) for {slug}", err=True)
            continue
        projections[slug] = _sra.produce(cands[0])  # re-produce with current code (fp_mapping + fixes)

    git_sha = (ctx.obj or {}).get("git_sha") if ctx.obj else None
    profile = _rp.build_recall_profile(projections, engine_git_sha=git_sha)
    if report_out:
        Path(report_out).parent.mkdir(parents=True, exist_ok=True)
        Path(report_out).write_text(
            json.dumps(profile, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    click.echo(json.dumps(profile, indent=2))


@main.command("judge-ceiling")
@click.option("--run-dir", required=True, type=click.Path(exists=True, resolve_path=True),
              help="Eval run dir (with leg + final per_query.json, qrels.json, projections/).")
@click.option("--corpus-dir", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Corpus dir with docs.jsonl (for candidate text). Optional (degrades to text-light).")
@click.option("--llm-url", default="http://127.0.0.1:8080",
              help="llama-server base URL (OpenAI-compatible /v1/chat/completions).")
@click.option("--engine-generator", default=None,
              help="Engine's generator model name; warns if the judge model matches it (self-preference bias).")
@click.option("--report-out", type=click.Path(), default=None, help="Write the full report JSON here.")
@click.pass_context
def cmd_judge_ceiling(ctx, run_dir, corpus_dir, llm_url, engine_generator, report_out):
    """LLM judge-ceiling probe (tempdoc 636 §5) — realistic judge headroom.

    Reranks a run's leg-union pool with the local LLM and compares nDCG@10 to the
    final, reporting how much of the AI-free ``judge_headroom_ceiling`` a real model
    captures (with an order-swap position-sensitivity band). Degrades to
    ``AI_UNAVAILABLE`` (exit 0) when the model is unreachable — the projection's
    AI-free ceiling already stands.
    """
    from . import judge_ceiling as _jc

    rd = Path(run_dir)
    qrels = json.loads((rd / "qrels.json").read_text(encoding="utf-8")) if (rd / "qrels.json").is_file() else {}

    # queries + ceiling/final_ndcg from the projection (or recompute).
    proj_path = rd / "projections" / "staged_recall_accounting.json"
    if proj_path.is_file():
        proj = json.loads(proj_path.read_text(encoding="utf-8"))
    else:
        from .projections import staged_recall_accounting as _sra
        proj = _sra.produce(rd)
    agg = proj.get("aggregate") or {}
    ceiling = agg.get("judge_headroom_ceiling")
    final_ndcg = agg.get("final_ndcg")
    final_mode = proj.get("final_mode") or "hybrid"

    queries: dict = {}
    fpq = rd / f"{final_mode}_per_query.json"
    if fpq.is_file():
        for e in json.loads(fpq.read_text(encoding="utf-8")):
            if e.get("qid"):
                queries[e["qid"]] = e.get("query") or ""

    pool = _jc.assemble_pool(rd)
    texts = _jc.load_doc_texts(Path(corpus_dir)) if corpus_dir else {}
    rank_fn = _jc.make_chat_rank_fn(llm_url)

    # Self-preference guardrail (external-research Finding 2): a judge favours its own
    # generations, so the judge model should differ from the engine's generator. Advisory
    # only — never blocks; the order-swap band + coarse-read remain the load-bearing guards.
    if engine_generator:
        judge_model = _jc.served_model_name(llm_url)
        if judge_model and judge_model == engine_generator:
            click.echo(
                f"WARN: judge model '{judge_model}' == engine generator — self-preference "
                f"bias inflates the ceiling; use a distinct judge model (636 §5 / Finding 2).",
                err=True)

    try:
        report = _jc.judge_ceiling_report(
            pool, queries, qrels, rank_fn, final_ndcg=final_ndcg, ceiling=ceiling, texts=texts)
    except _jc.AIUnavailable as e:
        report = {"status": "AI_UNAVAILABLE", "reason": str(e),
                  "judge_headroom_ceiling": ceiling, "final_ndcg": final_ndcg,
                  "note": "projection's AI-free judge_headroom_ceiling stands"}

    report["run_dir"] = str(rd)
    if report_out:
        Path(report_out).parent.mkdir(parents=True, exist_ok=True)
        Path(report_out).write_text(json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False),
                                    encoding="utf-8")
    click.echo(json.dumps(report, indent=2), err=True)


@main.command("release")
@click.option("--run", "runs", multiple=True, type=click.Path(exists=True, resolve_path=True),
              help="Run dir (with summary.json) to include. Repeatable. One or more per corpus. "
                   "All must share one config-cohort key (else compose refuses).")
@click.option("--latest-per-dataset", is_flag=True,
              help="Auto-discover: the most recent run per dataset under --data-dir's eval-results "
                   "(via the manifest index). compose() then validates they are one cohort.")
@click.option("--data-dir", type=click.Path(), default=lambda: str(DEFAULT_EVAL_RESULTS.parent),
              help="Data dir whose eval-results/ holds runs (for --latest-per-dataset).")
@click.option("--default-mode", default="hybrid", show_default=True,
              help="Production-default search mode whose metrics are the per-corpus headline.")
@click.option("--external-baselines", type=click.Path(exists=True, resolve_path=True), default=None,
              help="Path to external-baselines.v1.json (cited published baselines, side-by-side).")
@click.option("--release-id", default=None, help="Optional human label for this release.")
@click.option("--allow-incomparable", is_flag=True,
              help="Compose even if a default-mode run is not comparable (diagnostics only).")
@click.option("--out", "out_path", type=click.Path(), default=None,
              help="Where to write release.v1.json (default: <data-dir>/release.v1.json).")
@click.pass_context
def cmd_release(ctx, runs, latest_per_dataset, data_dir, default_mode, external_baselines,
                release_id, allow_incomparable, out_path):
    """Compose cohort-identical eval runs into one publishable benchmark release (tempdoc 623).

    A release is a PROJECTION over runs that share one config/commit/hardware — not a new
    authority. The relevance-ratchet floors and the register headline read from it (see
    `jseval relevance-gate` re-rooting and scripts/docs/register-headline-sync.mjs).
    """
    from datetime import datetime, timezone

    from . import release as _release
    from . import bisection as _bisection

    run_dirs: list[Path] = [Path(r) for r in runs]
    if latest_per_dataset:
        idx_root = Path(data_dir) / "eval-results"
        rows = _bisection.load_index(idx_root)
        latest: dict[str, dict] = {}
        for row in rows:
            ds = row.get("dataset")
            if ds is None:
                continue
            prev = latest.get(ds)
            if prev is None or (row.get("timestamp") or "") > (prev.get("timestamp") or ""):
                latest[ds] = row
        # P1 (tempdoc 623): the latest run per dataset can span commits — the shared `main` moves
        # mid-sweep (the V5 split). Group the latest runs by config_cohort_key and pick the cohort
        # covering the MOST datasets, then WARN + EXCLUDE the split, rather than feeding a mixed set
        # to compose() (which would just refuse the whole thing with an opaque error).
        dataset_to_key: dict[str, str] = {}
        dataset_to_rd: dict[str, Path] = {}
        for ds, row in latest.items():
            rd = Path(row["run_dir"])
            sp = rd / "summary.json"
            if not sp.is_file():
                continue
            manifest = json.loads(sp.read_text(encoding="utf-8")).get("manifest") or {}
            dataset_to_key[ds] = _release.config_cohort_key(manifest)
            dataset_to_rd[ds] = rd
        chosen_key, excluded = _release.select_dominant_cohort(dataset_to_key)
        if excluded:
            click.echo(
                f"WARNING: {len(excluded)} dataset(s) are on a different config-cohort "
                f"(likely a mid-sweep commit move) and are EXCLUDED from this release: "
                f"{', '.join(excluded)}. Re-run them at the chosen cohort's commit to include them.",
                err=True,
            )
        run_dirs.extend(rd for ds, rd in dataset_to_rd.items() if dataset_to_key[ds] == chosen_key)

    if not run_dirs:
        click.echo("Error: provide --run (repeatable) or --latest-per-dataset", err=True)
        sys.exit(2)

    summaries: list[dict] = []
    for rd in run_dirs:
        sp = rd / "summary.json"
        if not sp.is_file():
            click.echo(f"Error: no summary.json in {rd}", err=True)
            sys.exit(2)
        summaries.append(json.loads(sp.read_text(encoding="utf-8")))

    ext = None
    if external_baselines:
        ext_doc = json.loads(Path(external_baselines).read_text(encoding="utf-8"))
        ext = ext_doc.get("baselines", ext_doc)

    try:
        release_doc = _release.compose(
            summaries,
            default_mode=default_mode,
            composed_at=datetime.now(timezone.utc).isoformat(),
            release_id=release_id,
            external_baselines=ext,
            require_comparable=not allow_incomparable,
        )
    except _release.ComposeError as e:
        click.echo(f"compose refused: {e}", err=True)
        sys.exit(1)

    out = Path(out_path) if out_path else Path(data_dir) / "release.v1.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        json.dumps(release_doc, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )
    click.echo(json.dumps({
        "release": str(out),
        "config_cohort_key": release_doc["cohort"]["config_cohort_key"][:12],
        "git_sha": (release_doc["cohort"].get("git_sha") or "")[:10],
        "default_mode": default_mode,
        "corpora": sorted(release_doc["measured"]),
        "missing_default_mode": release_doc["notes"]["missing_default_mode"],
    }, indent=2))


@main.command("extraction-gate")
@click.option("--baseline-run", required=True, type=click.Path(exists=True, resolve_path=True),
              help="Run dir (with summary.json) from the BASELINE extraction profile (e.g. qwen-vl).")
@click.option("--candidate-run", required=True, type=click.Path(exists=True, resolve_path=True),
              help="Run dir (with summary.json) from the CANDIDATE extraction profile (e.g. paddle-ocr-vl).")
@click.option("--mode", required=True,
              help="Primary retrieval mode whose nDCG@10 decides the ship verdict (e.g. hybrid).")
@click.option("--guard-mode", "guard_modes", multiple=True,
              help="Extra mode that must merely not regress (repeatable).")
@click.option("--min-improvement", type=float, default=0.005, show_default=True,
              help="Minimum nDCG@10 gain on the primary mode to justify the swap.")
@click.option("--regression-tol", type=float, default=0.005, show_default=True,
              help="A drop larger than this on any checked mode is a rejection.")
@click.option("--ocr-baseline", type=float, default=None,
              help="INFORMATIONAL: baseline extract-eval word-overlap (does not decide the verdict).")
@click.option("--ocr-candidate", type=float, default=None,
              help="INFORMATIONAL: candidate extract-eval word-overlap (does not decide the verdict).")
@click.option("--report-out", type=click.Path(), default=None,
              help="Write the full gate decision JSON to this path.")
@click.pass_context
def cmd_extraction_gate(ctx, baseline_run, candidate_run, mode, guard_modes,
                        min_improvement, regression_tol, ocr_baseline, ocr_candidate,
                        report_out):
    """Retrieval-aware extraction gate (tempdoc 580 Track D / F-009).

    Compares two eval runs over the SAME extraction-exercising corpus — one indexed
    with the baseline VLM profile, one with the candidate (PaddleOCR-VL) profile — and
    decides the swap on DOWNSTREAM nDCG@10, NOT the OCR word-overlap (§14.4 /
    InduOCRBench: OCR accuracy is a poor proxy for retrieval). If --ocr-baseline/
    --ocr-candidate are supplied, the gate also flags when the OCR signal DISAGREES
    with nDCG (the InduOCRBench trap) — but the verdict always follows nDCG.

    Exit 0 = ship (clear downstream win), 1 = reject (regression), 2 = metric missing,
    3 = neutral-hold (no clear win — don't pay the swap cost).
    """
    from . import extraction_gate as _xgate

    base_summary = json.loads((Path(baseline_run) / "summary.json").read_text(encoding="utf-8"))
    cand_summary = json.loads((Path(candidate_run) / "summary.json").read_text(encoding="utf-8"))

    ocr_overlap = None
    if ocr_baseline is not None and ocr_candidate is not None:
        ocr_overlap = {"baseline": ocr_baseline, "candidate": ocr_candidate}

    report = _xgate.evaluate(
        base_summary,
        cand_summary,
        mode,
        guard_modes=list(guard_modes),
        min_improvement_abs=min_improvement,
        regression_tol_abs=regression_tol,
        ocr_overlap=ocr_overlap,
    )
    report["baseline_run"] = str(baseline_run)
    report["candidate_run"] = str(candidate_run)

    if report_out:
        out_path = Path(report_out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(
            json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False),
            encoding="utf-8")

    click.echo(json.dumps({
        "exit_code": report["exit_code"],
        "verdict": report.get("verdict"),
        "mode": mode,
        "baseline_ndcg": report.get("baseline_ndcg"),
        "candidate_ndcg": report.get("candidate_ndcg"),
        "delta": report.get("delta"),
        "checks": {c["name"]: c["status"] for c in report["checks"]},
    }, indent=2), err=True)
    sys.exit(report["exit_code"])


@main.command("calibrate-drift-baseline")
@click.option("--cohort-hash", required=True,
              help="manifest_hash of the cohort to calibrate.")
@click.option("--data-dir", type=click.Path(resolve_path=True), required=True,
              help="Base data dir hosting cohort_baselines/.")
@click.option("--from-runs", type=click.Path(exists=True, resolve_path=True), multiple=True,
              required=True,
              help="Run dir(s) to merge into the baseline (repeatable). "
                   "Each must have manifest.manifest_hash == cohort-hash.")
@click.option("--force", is_flag=True,
              help="Overwrite an existing baseline for this cohort.")
@click.pass_context
def cmd_calibrate_drift_baseline(ctx, cohort_hash, data_dir, from_runs,
                                  force):
    """Capture encoder-drift baseline from N warm runs (Phase 6 / 6.2).

    Merges ``encoder.ort_run`` span durations across every
    ``--from-runs`` path into
    ``<data_dir>/cohort_baselines/<cohort-hash>/span_distributions.json``.
    Requires >= 3 runs to prevent a single cold-start outlier from
    defining the baseline (post-implementation-critique C-1.8.1).

    Operators typically run ``jseval run ...`` a few times at a
    stable git SHA with ``JUSTSEARCH_INDEX_TRACING_LEVEL=detailed``
    exported, then invoke this command pointing at the produced
    run directories.
    """
    from . import drift_calibration

    result = drift_calibration.calibrate(
        data_dir=Path(data_dir),
        cohort_hash=cohort_hash,
        from_runs=[Path(p) for p in from_runs],
        force=force,
    )
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
        return
    click.echo(f"status: {result['status']}")
    if result["status"] == "ok":
        click.echo(f"  merged {result['n_runs_merged']} runs")
        click.echo(f"  baseline: {result['baseline_path']}")
        for name, n in result["encoders"].items():
            click.echo(f"    {name}: {n} samples")
    else:
        for k, v in result.items():
            if k != "status":
                click.echo(f"  {k}: {v}")
        if result["status"] not in {"ok"}:
            sys.exit(1)


@main.command("calibrate")
@click.option(
    "--dataset", required=True,
    help="Dataset name (must match the value passed to `jseval run`; same"
         " cohort identity).")
@click.option(
    "--modes", required=True,
    help="Comma-separated modes (e.g. full). Must match `jseval run` modes"
         " for the cohort to share manifest_hash.")
@click.option(
    "--runs", default=5, show_default=True, type=int,
    help="Number of identical re-runs to calibrate stdev from. 5 is the"
         " default per tempdoc 400 B2 (measured stdev(nDCG@10)=0.00108"
         " at N=5). Higher N narrows the CI but doubles calibration time.")
@click.option(
    "--data-dir", type=click.Path(resolve_path=True), default=None,
    help="Override JUSTSEARCH_DATA_DIR. Defaults to the eval-mode data"
         " dir used by `jseval run --start-backend`.")
@click.option(
    "--max-queries", default=50, show_default=True, type=int,
    help="Cap queries per run for cheaper calibration. 0 = all. The same"
         " value should be used on every subsequent `jseval run` against"
         " this cohort for meaningful envelope lookup.")
@click.pass_context
def cmd_calibrate(
    ctx,
    dataset: str,
    modes: str,
    runs: int,
    data_dir: str | None,
    max_queries: int,
) -> None:
    """Calibrate the non-determinism envelope for a cohort (LR1-b).

    Runs the configured smoke N times and captures stdev per metric into
    ``<data_dir>/cohort_baselines/<cohort_hash>/envelope.json`` (Phase 3
    layout, §26.6 Decision 2). Subsequent ``jseval run`` invocations
    with matching cohort identity look the envelope up and embed it
    into their manifest.
    """
    from .calibrate import calibrate as _calibrate

    data_dir_path = (
        Path(data_dir) if data_dir
        else Path(os.environ.get("JUSTSEARCH_DATA_DIR", "tmp/headless-eval-data"))
    )
    result = _calibrate(
        dataset=dataset,
        modes=[m.strip() for m in modes.split(",") if m.strip()],
        runs=runs,
        data_dir=data_dir_path,
        max_queries=max_queries,
    )
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        cohort = result.get("cohort_hash", "?")[:16]
        n = result.get("n_runs", 0)
        modes_count = len(result.get("metrics") or {})
        metrics_count = sum(len(v) for v in (result.get("metrics") or {}).values())
        click.echo(
            f"Envelope calibrated for cohort {cohort} "
            f"(n_runs={n}, {modes_count} modes, {metrics_count} metrics).")


@main.command("utility-calibrate")
@click.option("--queries", required=True, type=click.Path(exists=True))
@click.option("--corpus-dir", required=True, type=click.Path(exists=True))
@click.option("--mcp-config", default=None, type=click.Path(exists=True), help="JustSearch MCP config (for the C pilot).")
@click.option("--base-url", required=True, help="Live backend, e.g. http://127.0.0.1:59423 (readiness + config_cohort_key).")
@click.option("--model", default="haiku", show_default=True)
@click.option("--concurrency", default=8, show_default=True, type=int, help="The TARGET concurrency (pilot runs at it — B1).")
@click.option("--seeds", default=3, show_default=True, type=int, help="For the cost/time estimate.")
@click.option("--conditions", default="A,C", show_default=True)
@click.option("--require-dense/--no-require-dense", default=True, show_default=True, help="Gate on dense+sparse readiness.")
@click.option("--pilot-n", default=5, show_default=True, type=int)
@click.option("--no-closed-book", is_flag=True, help="Skip the closed-book contamination filter.")
@click.option("--output", required=True, type=click.Path(), help="calibration.json (feed to `utility-run --calibration`).")
@click.pass_context
def cmd_utility_calibrate(ctx, queries, corpus_dir, mcp_config, base_url, model, concurrency,
                          seeds, conditions, require_dense, pilot_n, no_closed_book, output):
    """Pre-run calibration: readiness gate + config_cohort_key pin + target-concurrency pilot
    + closed-book filter (tempdoc 624 §Run-governance). Needs `jseval[agent]` + a live backend."""
    import os

    from . import utility_calibrate as ucal

    rows = json.loads(Path(queries).read_text(encoding="utf-8"))
    calib = ucal.calibrate(
        base_url=base_url, queries=rows, corpus_dir=os.path.abspath(corpus_dir),
        mcp_config=(os.path.abspath(mcp_config) if mcp_config else None),
        model=model, concurrency=concurrency, seeds=seeds,
        conditions=tuple(c.strip().upper() for c in conditions.split(",") if c.strip()),
        require_dense=require_dense, pilot_n=pilot_n, do_closed_book=not no_closed_book,
    )
    Path(output).write_text(json.dumps(calib, indent=2), encoding="utf-8")
    if not calib["readiness_passed"]:
        click.echo(f"READINESS FAILED: {calib['readiness_reasons']} (record/refuse before a full run)")
    click.echo(f"timeout={calib['timeout_s']}s concurrency={calib['concurrency']} "
               f"retained={len(calib['retained_query_indices'])} dropped={calib['n_dropped_contaminated']} "
               f"est=${calib['cost_estimate_usd']} / {calib['time_estimate_min']}min")
    click.echo(f"config_cohort_key={calib['config_cohort_key']}")
    click.echo(f"Written calibration to {output}")


@main.command("utility-status")
@click.argument("log_dir", type=click.Path(exists=True))
@click.option("--search-config-key", default=None)
@click.pass_context
def cmd_utility_status(ctx, log_dir, search_config_key):
    """Live-status projection over PARTIAL Inspect logs (tempdoc 624 §Run-governance):
    completion %, per-arm exclusion (exposes the timeouts Inspect swallows), emerging delta."""
    from . import agent_utility_run as aur
    from . import utility_comparison as uc
    from . import utility_governance as ug

    arms = ug.compute_loss_accounting(log_dir)
    for c, l in sorted(arms.items()):
        click.echo(f"  {c}: completed={l.n_completed}/{l.n_attempted} "
                   f"excluded={l.n_excluded} ({l.exclusion_rate:.0%})")
    verdict, m = ug.paired_comparability(arms)
    click.echo(f"  comparable(so far)={verdict.comparable}  {m}")
    if not verdict.comparable:
        for r in verdict.reasons:
            click.echo(f"    - {r}")
    try:
        summaries = aur.eval_logs_to_summaries(
            log_dir, search_config_cohort_key=search_config_key or "partial")
        if summaries:
            rec = uc.compose_utility(summaries, composed_at="partial")
            for slug, by_model in rec["measured"].items():
                for mdl, cell in by_model.items():
                    acc, tok = cell["accuracy"], cell["tokens_unique"]
                    click.echo(f"  [{slug}/{mdl}] acc {acc['baseline']}->{acc['with_tool']} "
                               f"(d={acc['delta']:+}); tokens median "
                               f"{tok['baseline']['median']}->{tok['with_tool']['median']}")
    except Exception as e:
        click.echo(f"  (no paired cells yet: {type(e).__name__})")


@main.command("utility-judge")
@click.argument("log_dir", type=click.Path(exists=True))
@click.option("--judge-url", default="http://127.0.0.1:8080", show_default=True,
              help="Local llama-server (OpenAI-compatible) — a DIFFERENT family than the claude agent (C-6).")
@click.option("--judge-model", default=None, help="Override the served model id (else auto-probed).")
@click.option("--search-config-key", default=None)
@click.option("--contamination-class",
              type=click.Choice(["public-pre-cutoff", "post-cutoff", "private-synthetic", "unknown"]),
              default="public-pre-cutoff", show_default=True)
@click.option("--confidence-tier", type=click.Choice(["A", "B", "C"]), default="C", show_default=True)
@click.option("--output-dir", default=None, type=click.Path(), help="Re-compose the JUDGED record here.")
@click.pass_context
def cmd_utility_judge(ctx, log_dir, judge_url, judge_model, search_config_key,
                      contamination_class, confidence_tier, output_dir):
    """Hybrid EM->LLM-judge re-score over EvalLogs, post-hoc (tempdoc 624 C-6/E-5).

    EM auto-passes; the EM-misses are judged by the local model (different family
    than the agent), dual-order, abstaining on disagreement. Writes a judge-overlay
    + (optionally) re-composes the JUDGED `utility-comparison.v1`. Requires the
    `agent` extra + a running judge model (`ai_activate`)."""
    import datetime as _dt

    from . import agent_utility_run as aur
    from . import utility_comparison as uc
    from . import utility_governance as ug
    from . import utility_judge as uj

    overlay = uj.judge_logs(log_dir, judge_url=judge_url, judge_model=judge_model)
    path = uj.write_overlay(log_dir, overlay)
    st = overlay["stats"]
    click.echo(f"judge: EM-pass={st['em_auto_pass']} judged-misses={st['judged_misses']} "
               f"flips={st['judge_flips']} disagreements={st['judge_disagreements']} "
               f"agreement={st['agreement_rate']} kind={overlay['judge_identity']['kind']}")
    if st["degraded_to_em"]:
        click.echo("WARNING: judge endpoint unreachable — overlay is EM-only (no LLM verdicts).")
    click.echo(f"Written overlay to {path}")

    if output_dir:
        summaries = aur.eval_logs_to_summaries(
            log_dir, search_config_cohort_key=search_config_key, judge_overlay=overlay)
        arms = ug.compute_loss_accounting(log_dir)
        verdict, gmetrics = ug.paired_comparability(arms)
        governance = {"comparable": verdict.comparable, "reasons": verdict.reasons,
                      "metrics": gmetrics,
                      "per_arm_loss": {c: {"n_excluded": l.n_excluded} for c, l in arms.items()}}
        record = uc.compose_utility(
            summaries, composed_at=_dt.datetime.now(_dt.timezone.utc).isoformat(),
            external_baselines=uc.CITED_BASELINES, contamination_class=contamination_class,
            confidence_tier=confidence_tier, governance=governance)
        out = Path(output_dir)
        out.mkdir(parents=True, exist_ok=True)
        (out / "utility-comparison.v1.json").write_text(
            json.dumps(record, indent=2, default=str), encoding="utf-8")
        for slug, by_model in record["measured"].items():
            for m, cell in by_model.items():
                acc = cell["accuracy"]
                click.echo(f"  JUDGED [{slug}/{m}] acc {acc['baseline']}->{acc['with_tool']} "
                           f"(d={acc['delta']:+}, McNemar p={acc['mcnemar_p']})")
        click.echo(f"Written JUDGED record to {out}")


@main.command("corpus-build")
@click.option("--source", required=True, type=click.Path(exists=True),
              help="Committed corpus source dir (scripts/jseval/635-corpora/<name>/).")
@click.option("--name", required=True,
              help="Golden dataset name, e.g. synth-multihop-v1 (-> datasets/golden/<name>/).")
@click.option("--datasets-dir", default=None, type=click.Path(),
              help="Base datasets dir (default: repo datasets/).")
@click.pass_context
def cmd_corpus_build(ctx, source, name, datasets_dir):
    """Materialize a committed corpus source -> golden/ BEIR layout + agent inputs (tempdoc 635).

    One source -> two projections: retrieval view (corpus.jsonl + queries.jsonl + qrels) and
    agent view (queries.json + raw corpus-dir). Writes a metadata.json with the 635 identity fields."""
    from . import corpus_build as cb
    from ._paths import REPO_ROOT

    base = Path(datasets_dir) if datasets_dir else (REPO_ROOT / "datasets")
    dataset_dir = base / "golden" / name
    meta = cb.build_golden(source, dataset_dir)
    if ctx.obj.get("json"):
        click.echo(json.dumps(meta, indent=2))
    else:
        click.echo(f"Built golden/{name}: {meta['corpus_size']} docs, "
                   f"{meta['query_count']} queries -> {dataset_dir}")
        click.echo(f"  sig={(meta['corpus_signature'] or '')[:16]} type={meta['type_axis']} "
                   f"suite={meta['suite']} class={meta['contamination_class']}")


@main.command("corpus-certify")
@click.option("--dataset", required=True, help="Golden dataset name, e.g. synth-multihop-v1.")
@click.option("--datasets-dir", default=None, type=click.Path())
@click.option("--model", default="haiku", show_default=True)
@click.option("--threshold", default=0.15, show_default=True, type=float,
              help="Max closed-book accuracy to PASS (a clean corpus should score ~0).")
@click.option("--concurrency", default=8, show_default=True, type=int)
@click.pass_context
def cmd_corpus_certify(ctx, dataset, datasets_dir, model, threshold, concurrency):
    """Closed-book certification: a corpus is clean only if the model FAILS it closed-book (tempdoc 635).

    Writes closed_book_certification + fidelity into the dataset's metadata.json. Needs the `claude` CLI
    (no JustSearch dev stack)."""
    from . import corpus_certify as cc
    from ._paths import REPO_ROOT

    base = Path(datasets_dir) if datasets_dir else (REPO_ROOT / "datasets")
    dataset_dir = base / "golden" / dataset
    queries = json.loads((dataset_dir / "queries.json").read_text(encoding="utf-8"))
    result = cc.certify_corpus(queries, model=model, threshold=threshold, concurrency=concurrency)

    meta_path = dataset_dir / "metadata.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.is_file() else {}
    # The two co-equal gates (memory = certify, retrieval-difficulty = fidelity) BOTH write the
    # `fidelity` block; MERGE the sub-block so neither clobbers the other regardless of run order
    # (symmetric to corpus-fidelity's merge — without this, certify-after-fidelity wiped the
    # retrieval_ndcg/by_mode/comparable fields).
    meta.update({k: v for k, v in result.items() if k != "fidelity"})
    fid = dict(meta.get("fidelity") or {})
    # Skip None values: certify emits `retrieval_difficulty: None` as a placeholder for the
    # post-retrieval-run population — it must NOT clobber a real value already set by
    # corpus-fidelity when certify runs second. (Only memory_independence is certify's to own.)
    fid.update({k: v for k, v in (result.get("fidelity") or {}).items() if v is not None})
    meta["fidelity"] = fid
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    cert = result["closed_book_certification"]
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2))
    else:
        verdict = "PASS" if cert["passed"] else "FAIL"
        click.echo(f"corpus-certify golden/{dataset}: closed-book acc={cert['closed_book_accuracy']:.3f} "
                   f"(<= {threshold}) -> {verdict}")
        click.echo(f"  memory_independence={result['fidelity']['memory_independence']} "
                   f"(retrieval_difficulty set post-retrieval-run)  written to {meta_path.name}")


@main.command("corpus-fidelity")
@click.option("--dataset", required=True, help="Golden dataset name (e.g. synth-code-v1).")
@click.option("--base-url", default="http://127.0.0.1:33221", show_default=True,
              help="Live backend with the corpus already ingested (ignored when --start-backend).")
@click.option("--datasets-dir", default=None, type=click.Path())
@click.option("--modes", default="bm25_splade", show_default=True, help="Retrieval modes; headline = last.")
@click.option("--embedding/--no-embedding", default=False, help="Enable dense (needs chunking docs).")
@click.option("--band-low", default=None, type=float,
              help="Min nDCG@10 to pass (default: corpus_fidelity.DEFAULT_BAND_LOW; multi-hop scores low).")
@click.option("--band-high", default=None, type=float, help="Max nDCG@10 to pass (default module constant).")
@click.option("--leak-threshold", default=None, type=float, help="Max single-doc shortcut-leak rate.")
@click.option("--model", default="haiku", show_default=True)
@click.option("--concurrency", default=8, show_default=True, type=int)
@click.option("--start-backend", is_flag=True,
              help="Self-contained: start runHeadlessEval, ingest the dataset, assess, stop. The "
                   "harness backend auto-discovers the reranker (default-on engine). Mirrors `jseval run`.")
@click.option("--clean", is_flag=True, help="Clean the data dir before starting (requires --start-backend).")
@click.pass_context
def cmd_corpus_fidelity(ctx, dataset, base_url, datasets_dir, modes, embedding,
                        band_low, band_high, leak_threshold, model, concurrency,
                        start_backend, clean):
    """FIDELITY gate (tempdoc 635 §D.5): a corpus passes only if it is non-trivial yet retrievable
    (in-band nDCG@10) AND genuinely multi-hop (low single-doc shortcut leaks). The retrieval-difficulty
    axis, symmetric to corpus-certify's memory axis. Stack-bound: pass --start-backend to ingest+assess
    self-contained (the harness backend's auto-discovered reranker is the DEFAULT-ON engine), or point
    --base-url at a backend with the corpus already ingested."""
    from . import corpus_fidelity as cf
    from ._paths import REPO_ROOT

    base = Path(datasets_dir) if datasets_dir else (REPO_ROOT / "datasets")
    dataset_dir = base / "golden" / dataset
    mode_list = tuple(m.strip() for m in modes.split(",") if m.strip())

    # Self-contained mode: bring up the harness backend, ingest the member, assess, stop.
    # Reuses the exact backend + ingest+readiness path `jseval run --start-backend` uses
    # (cli._run_single_iteration), so the reranker auto-discovery + reaper-free lifecycle apply.
    backend_proc = None
    if start_backend:
        from . import backend as backend_mod
        from . import ingest as ingest_mod
        from .types import IngestConfig
        if not clean:
            # A self-contained gate run must start from a clean index: cache-verification
            # (tempdoc 635) binds the materialized corpus to its source, but a dirty Lucene
            # index would still co-ingest a prior corpus. Both are needed to bind index==corpus,
            # so refuse rather than silently pollute the verdict.
            raise click.UsageError(
                "--start-backend requires --clean (a self-contained fidelity run must start "
                "from a clean index, else a prior corpus co-ingests and pollutes the verdict)."
            )
        # Resolve ONE port and pass it to both start_backend and base_url (D3): start_backend binds
        # `port` and sets the child's env from it, so deriving base_url from the parent's env would
        # diverge whenever JUSTSEARCH_API_PORT is set to a non-default value.
        port = int(os.environ.get("JUSTSEARCH_API_PORT", "33221"))
        backend_proc = backend_mod.start_backend(clean=clean, llm=False, port=port)
        base_url = f"http://127.0.0.1:{port}"
    try:
        if start_backend:
            popen = backend_proc.proc
            ingest_mod.prepare_corpus(
                f"golden/{dataset}",
                config=IngestConfig(
                    base_url=base_url, dense_enabled=embedding, splade_enabled=True,
                    pipeline=True, json_mode=ctx.obj.get("json", False),
                    process_check=(lambda: popen.poll() is None),
                ),
            )
        result = cf.assess_fidelity(
            dataset_dir, f"golden/{dataset}", base_url, modes=mode_list,
            embedding_enabled=embedding, splade_enabled=True,
            band_low=cf.DEFAULT_BAND_LOW if band_low is None else band_low,
            band_high=cf.DEFAULT_BAND_HIGH if band_high is None else band_high,
            leak_threshold=cf.DEFAULT_LEAK_THRESHOLD if leak_threshold is None else leak_threshold,
            model=model, concurrency=concurrency, base_dir=base)
    finally:
        if backend_proc is not None:
            from . import backend as backend_mod
            backend_mod.stop_backend(backend_proc.proc)

    meta_path = dataset_dir / "metadata.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8")) if meta_path.is_file() else {}
    # Merge into the cert's fidelity block, preserving memory_independence. Skip None values for
    # symmetry with corpus-certify's merge (D2) — neither co-equal gate clobbers the other's fields
    # regardless of run order.
    fid = dict(meta.get("fidelity") or {})
    fid.update({k: v for k, v in result.items() if v is not None})
    meta["fidelity"] = fid
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2))
    else:
        verdict = "PASS" if result["passed"] else "FAIL"
        click.echo(f"corpus-fidelity golden/{dataset}: nDCG@10={result['retrieval_ndcg']} "
                   f"(band {result['band'][0]}-{result['band'][1]}, in_band={result['in_band']}), "
                   f"difficulty={result['retrieval_difficulty']}, "
                   f"shortcut_leaks={result['shortcut_leak_rate']} -> {verdict}")


@main.command("corpus-probe")
@click.option("--dataset", required=True, help="Golden dataset name (e.g. synth-code-v1).")
@click.option("--base-url", default="http://127.0.0.1:33221", show_default=True,
              help="Live backend with the corpus ingested (ignored when --start-backend).")
@click.option("--datasets-dir", default=None, type=click.Path())
@click.option("--modes", default="vector,bm25_splade,hybrid", show_default=True,
              help="Retrieval modes to probe (comma-separated).")
@click.option("--embedding/--no-embedding", default=True, help="Enable dense (needed for vector/hybrid).")
@click.option("--top-k", default=10, show_default=True, type=int)
@click.option("--start-backend", is_flag=True,
              help="Self-contained: start runHeadlessEval, ingest, probe, stop.")
@click.option("--clean", is_flag=True, help="Clean the data dir before starting (requires --start-backend).")
@click.pass_context
def cmd_corpus_probe(ctx, dataset, base_url, datasets_dir, modes, embedding, top_k, start_backend, clean):
    """PROBE the retrieval binding (tempdoc 635 witness): per-query expected-head rank + top-k across modes,
    plus a control search of the head's own descriptor. The inspectable 'show your work' companion to
    corpus-fidelity's scalar verdict — diagnoses whether/why retrieval finds the certified head (and whether
    the measured index is even the certified corpus: an exactly-0.0 / head-never-found probe is the
    plumbing-mismatch signature, not a retrieval-quality one)."""
    import collections
    from . import retriever as retr
    from ._paths import REPO_ROOT

    base = Path(datasets_dir) if datasets_dir else (REPO_ROOT / "datasets")
    dataset_dir = base / "golden" / dataset
    mode_list = [m.strip() for m in modes.split(",") if m.strip()]

    queries: dict[str, str] = {}
    for line in (dataset_dir / "queries.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            d = json.loads(line); queries[d["_id"]] = d["text"]
    head: dict[str, str] = {}
    for line in (dataset_dir / "qrels" / "test.tsv").read_text(encoding="utf-8").splitlines()[1:]:
        if line.strip():
            qid, cid, _ = line.split("\t"); head[qid] = cid
    titles: dict[str, str] = {}
    for line in (dataset_dir / "corpus.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            d = json.loads(line); titles[d["_id"]] = d.get("title", "")

    backend_proc = None
    if start_backend:
        if not clean:
            raise click.UsageError("--start-backend requires --clean (see corpus-fidelity).")
        from . import backend as backend_mod
        from . import ingest as ingest_mod
        from .types import IngestConfig
        port = int(os.environ.get("JUSTSEARCH_API_PORT", "33221"))
        backend_proc = backend_mod.start_backend(clean=clean, llm=False, port=port)
        base_url = f"http://127.0.0.1:{port}"
    try:
        if start_backend:
            popen = backend_proc.proc
            ingest_mod.prepare_corpus(
                f"golden/{dataset}",
                config=IngestConfig(
                    base_url=base_url, dense_enabled=embedding, splade_enabled=True,
                    pipeline=True, json_mode=ctx.obj.get("json", False),
                    process_check=(lambda: popen.poll() is None)))
        rows = []
        for mode in mode_list:
            scored, _ = retr.retrieve(queries, base_url, mode=mode, top_k=top_k, allow_errors=True)
            by_q: dict[str, list[str]] = collections.defaultdict(list)
            for sd in scored:
                by_q[sd.query_id].append(sd.doc_id)
            ranks, found = [], 0
            for qid in queries:
                preds = by_q.get(qid, [])
                h = head.get(qid)
                if h and h in preds:
                    found += 1; ranks.append(preds.index(h) + 1)
            rows.append({"mode": mode, "head_at_topk": f"{found}/{len(queries)}",
                         "mean_rank": (round(sum(ranks) / len(ranks), 2) if ranks else None)})
        ctrl = None
        if queries:
            q0 = next(iter(queries)); h0 = head.get(q0)
            if h0:
                cs, _ = retr.retrieve({"ctrl": titles.get(h0, "")}, base_url,
                                      mode=mode_list[-1], top_k=top_k, allow_errors=True)
                preds = [sd.doc_id for sd in cs]
                ctrl = {"head": h0, "rank": (preds.index(h0) + 1 if h0 in preds else None)}
    finally:
        if backend_proc is not None:
            from . import backend as backend_mod
            backend_mod.stop_backend(backend_proc.proc)

    out = {"dataset": f"golden/{dataset}", "n_queries": len(queries), "modes": rows, "control": ctrl}
    if ctx.obj.get("json"):
        click.echo(json.dumps(out, indent=2))
    else:
        click.echo(f"corpus-probe golden/{dataset} ({len(queries)} queries):")
        for r in rows:
            click.echo(f"  [{r['mode']:12}] head@top{top_k}: {r['head_at_topk']}  mean_rank={r['mean_rank']}")
        if ctrl is not None:
            click.echo(f"  control (head's own descriptor): rank={ctrl['rank']}")


@main.command("suite-profile")
@click.option("--suite", required=True, help="Suite tag, e.g. 635-self-demo-v1.")
@click.option("--datasets-dir", default=None, type=click.Path())
@click.option("--records-root", default=None, type=click.Path(),
              help="Dir holding per-member 635-<name>/{release,utility-comparison}.v1.json (optional).")
@click.option("--output", default=None, type=click.Path(),
              help="Write the profile JSON here (the committed, engine-SHA-stamped ceiling snapshot).")
@click.pass_context
def cmd_suite_profile(ctx, suite, datasets_dir, records_root, output):
    """Project the suite's certified members into ONE profile (tempdoc 635 §R-3: a profile, not a number).

    Per-member: type, contamination class, closed-book + fidelity verdicts, retrieval nDCG/difficulty, and
    (where present) the agent delta — exposing where the engine is strong/weak across document/query types.
    With --output, writes a durable snapshot stamped with the engine git SHA (datasets/ is gitignored, so the
    committed snapshot is the durable ceiling record; the levers are default-on, so the SHA pins behaviour)."""
    import datetime as _dt
    import subprocess as _sp

    from . import suite_profile as sp
    from ._paths import REPO_ROOT

    base = Path(datasets_dir) if datasets_dir else (REPO_ROOT / "datasets")
    sha = None
    try:
        _p = _sp.run(["git", "rev-parse", "HEAD"], cwd=str(REPO_ROOT),
                     capture_output=True, text=True, timeout=10)
        # Only trust stdout on a clean exit (a non-zero exit with partial stdout would stamp garbage).
        sha = _p.stdout.strip() if _p.returncode == 0 else None
        sha = sha or None
    except Exception:
        sha = None
    prof = sp.build_profile(
        suite, base, Path(records_root) if records_root else None,
        engine_git_sha=sha, generated_date=_dt.datetime.now(_dt.timezone.utc).date().isoformat())
    if output:
        Path(output).write_text(json.dumps(prof, ensure_ascii=False, indent=2), encoding="utf-8")
        click.echo(f"Wrote suite profile -> {output} (engine_git_sha={sha[:12] if sha else '?'})", err=True)
    if ctx.obj.get("json"):
        click.echo(json.dumps(prof, indent=2))
    else:
        click.echo(f"Suite '{suite}' — {prof['n_members']} members (profile, not a number):")
        for r in prof["members"]:
            agent = (f" agent_acc_d={r['agent_acc_delta']:+}" if r.get("agent_acc_delta") is not None else "")
            click.echo(f"  [{r['type_axis']:>9}] {r['member']}: clean={r['closed_book_passed']} "
                       f"fidelity={r['fidelity_passed']} nDCG={r['retrieval_ndcg']} "
                       f"({r['retrieval_difficulty']}) leaks={r['shortcut_leak_rate']}{agent}")
