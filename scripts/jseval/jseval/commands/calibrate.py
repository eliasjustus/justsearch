"""jseval calibrate commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path
import logging

import click

log = logging.getLogger(__name__)


@click.command("recalibrate-nightly-baseline")
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
    from .. import cohort_baselines

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


@click.command("calibrate-drift-baseline")
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
    from .. import drift_calibration

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


@click.command("calibrate")
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
    from ..calibrate import calibrate as _calibrate

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


COMMANDS = [cmd_recalibrate_nightly_baseline, cmd_calibrate_drift_baseline, cmd_calibrate]
