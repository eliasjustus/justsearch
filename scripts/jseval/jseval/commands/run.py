"""jseval run commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path
import httpx
import logging

import click

from .._paths import DEFAULT_EVAL_RESULTS
from ._common import _DEFAULT_BASE_URL

log = logging.getLogger(__name__)


@click.command("run")
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
    from .. import ingest as ingest_mod
    from .. import run as run_module

    # Apply YAML config if provided (item 11).
    backend_proc = None
    env_overrides: dict[str, str] = {}
    if config_path:
        from .. import run_config
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


@click.command("requery")
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
    from .. import run as run_module

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
        from .. import backend as backend_mod
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

    from ..types import IngestConfig
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
            from .. import backend as backend_mod
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
    from .._paths import REPO_ROOT

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
        from ..readiness import flatten_status
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
    from .. import ingest as ingest_mod
    from .. import run as run_module

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
            from .. import timeline as tl
            click.echo(tl.format_pipeline_summary(pipeline_summary))


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


COMMANDS = [cmd_run, cmd_requery]
