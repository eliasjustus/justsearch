"""jseval utility commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import os
from pathlib import Path
import logging

import click

log = logging.getLogger(__name__)


@click.command("utility-compose")
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

    from .. import agent_utility_run as aur
    from .. import utility_comparison as uc

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


@click.command("utility-run")
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

    from .. import agent_utility_inspect as aui
    from .. import agent_utility_run as aur
    from .. import utility_comparison as uc

    conds = tuple(c.strip().upper() for c in conditions.split(",") if c.strip())
    cli_version = aur.claude_cli_version()
    # Calibration (from `utility-calibrate`) overrides timeout/concurrency/search-key
    # and filters the queries to the closed-book-retained (retrieval-relevant) set.
    calib_readiness = None  # threaded into the run's comparability verdict (readiness ∧ error_rate)
    if calibration:
        from ..types import ReadinessResult
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
    from .. import utility_governance as ug
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


@click.command("utility-calibrate")
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

    from .. import utility_calibrate as ucal

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


@click.command("utility-status")
@click.argument("log_dir", type=click.Path(exists=True))
@click.option("--search-config-key", default=None)
@click.pass_context
def cmd_utility_status(ctx, log_dir, search_config_key):
    """Live-status projection over PARTIAL Inspect logs (tempdoc 624 §Run-governance):
    completion %, per-arm exclusion (exposes the timeouts Inspect swallows), emerging delta."""
    from .. import agent_utility_run as aur
    from .. import utility_comparison as uc
    from .. import utility_governance as ug

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


@click.command("utility-judge")
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

    from .. import agent_utility_run as aur
    from .. import utility_comparison as uc
    from .. import utility_governance as ug
    from .. import utility_judge as uj

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


COMMANDS = [cmd_utility_compose, cmd_utility_run, cmd_utility_calibrate, cmd_utility_status, cmd_utility_judge]
