"""jseval utility commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path
import logging

import click

from ._common import _attach_revision, _write_bench_output

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
@click.option("--exclude-leaked", is_flag=True,
              help="Scan each run's agent_answer text for an answer-key-leak signature "
                   "(a queries.json/queries.jsonl mention) and exclude matched cells from "
                   "the paired statistics before composing (tempdoc 624 §As-built #7 "
                   "follow-up leak-free reanalysis).")
@click.option("--supersedes", default=None, type=click.Path(),
              help="Relative path to the prior record's JSON file this run corrects. "
                   "Must be given together with --revision-reason (tempdoc 624 Design 1). "
                   "When both are given, the composed record gets a `revision` block; "
                   "when neither is given, the record is unchanged (no `revision` field).")
@click.option("--revision-reason", default=None,
              type=click.Choice(sorted(["leak_correction", "judge_rescore", "reseed", "other"])),
              help="Why this record supersedes --supersedes. Must be given together with "
                   "--supersedes. The revision's `changed_fields` is left empty at the CLI "
                   "level -- the caller isn't expected to know exactly which fields changed "
                   "when composing from the command line; that's a deliberate, honest default.")
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_utility_compose(ctx, runs, dataset, corpus_signature, model, search_config_key,
                        contamination_class, confidence_tier, exclude_leaked,
                        supersedes, revision_reason, output_dir):
    """Compose agent-eval results into a utility-comparison.v1 record (tempdoc 624).

    Attaches a cohort identity to each run (agent_manifest), pairs the with/without
    -tool arms on condition, aggregates seeds, and emits the canonical record plus
    an Inspect-EvalLog projection. Pure composition over existing run artifacts.
    """
    import datetime as _dt

    from .. import agent_utility_run as aur
    from .. import utility_comparison as uc

    _attach_revision(None, supersedes, revision_reason)  # fail fast before reading run files

    corpus = {"dataset": dataset, "signature": corpus_signature or dataset}
    prompt_template = (
        "Answer the following question using only the documents in <corpus>. "
        "Do not use prior knowledge. Be concise. Question: <query>"
    )
    cli_version = aur.claude_cli_version()
    seed_counter: dict = {}
    summaries = []
    n_flagged = 0
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
        summary = aur.build_compose_summary(
            result, condition=cond, model=model, corpus=corpus, seed=seed,
            prompt_template=prompt_template, cli_version=cli_version,
            search_config_cohort_key=(search_config_key if with_tool else None),
        )
        if exclude_leaked:
            leaked = aur.scan_leaked_answers(result.get("results", []), condition=cond, seed=seed)
            n_flagged += aur.apply_leak_flags([summary], leaked)
        summaries.append(summary)

    if exclude_leaked:
        click.echo(f"leak-scan: flagged {n_flagged} per-query entries (excluded from paired stats)")

    record = uc.compose_utility(
        summaries,
        composed_at=_dt.datetime.now(_dt.timezone.utc).isoformat(),
        external_baselines=uc.CITED_BASELINES,
        contamination_class=contamination_class,
        confidence_tier=confidence_tier,
    )
    _attach_revision(record, supersedes, revision_reason)

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

    _write_bench_output(record, output_dir, "utility-comparison.v1.json")


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
        _write_bench_output(record, output_dir, "utility-comparison.v1.json")
        click.echo(f"(Inspect logs in {log_dir})")


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
    if not calib["readiness_passed"]:
        # Non-zero exit — a failed calibration must not be mistaken for a passing one by
        # a caller/script that doesn't itself inspect `readiness_passed` in the output
        # file (matches the `sys.exit(1)` convention used across jseval's other commands
        # for a failed readiness/capability gate, e.g. `_common.assert_run_capabilities`).
        sys.exit(1)


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
@click.option("--judge-url", default="http://127.0.0.1:33221", show_default=True,
              help="The JustSearch Head API's OWN base URL (its OpenAI-compat proxy — "
                   "OpenAiCompatController.java forwards /v1/chat/completions and /v1/models "
                   "to whatever port llama-server actually bound), NOT llama-server's raw "
                   "ephemeral port. Default matches jseval's own eval-backend port "
                   "(_DEFAULT_BASE_URL_EVAL in jseval/commands/_common.py) — override with the "
                   "live backend's base URL if it was started on a different port. A different "
                   "model family than the claude agent under test is the self-preference "
                   "control (C-6).")
@click.option("--judge-model", default=None, help="Override the served model id (else auto-probed).")
@click.option("--search-config-key", default=None)
@click.option("--contamination-class",
              type=click.Choice(["public-pre-cutoff", "post-cutoff", "private-synthetic", "unknown"]),
              default="public-pre-cutoff", show_default=True)
@click.option("--confidence-tier", type=click.Choice(["A", "B", "C"]), default="C", show_default=True)
@click.option("--output-dir", default=None, type=click.Path(), help="Re-compose the JUDGED record here.")
@click.option("--calibrate", is_flag=True,
              help="Also run the human-calibration DRY RUN (tempdoc 624 §M.4/§T.3) and attach it "
                   "to the overlay as `human_calibration`. Uses two deterministic AGENT-SUBSTITUTE "
                   "raters, NOT real human raters — proves the sampling + Cohen's-kappa machinery "
                   "end-to-end, not a validated judge-accuracy figure.")
@click.option("--calibration-n", default=40, show_default=True, type=int,
              help="Calibration sample size (only with --calibrate).")
@click.option("--calibration-seed", default=0, show_default=True, type=int,
              help="Calibration sample RNG seed (only with --calibrate).")
@click.option("--exclude-leaked", is_flag=True,
              help="Scan the Inspect log dir for an answer-key-leak signature "
                   "(a queries.json/queries.jsonl mention in each sample's completion "
                   "text) and exclude matched cells from the paired statistics before "
                   "composing (tempdoc 624 §As-built #7 follow-up leak-free reanalysis).")
@click.option("--supersedes", default=None, type=click.Path(),
              help="Relative path to the prior record's JSON file this run corrects. "
                   "Must be given together with --revision-reason (tempdoc 624 Design 1). "
                   "When both are given, the re-composed record gets a `revision` block; "
                   "when neither is given, the record is unchanged (no `revision` field). "
                   "Only takes effect with --output-dir (no re-composed record otherwise).")
@click.option("--revision-reason", default=None,
              type=click.Choice(sorted(["leak_correction", "judge_rescore", "reseed", "other"])),
              help="Why this record supersedes --supersedes. Must be given together with "
                   "--supersedes. The revision's `changed_fields` is left empty at the CLI "
                   "level -- the caller isn't expected to know exactly which fields changed "
                   "when composing from the command line; that's a deliberate, honest default.")
@click.pass_context
def cmd_utility_judge(ctx, log_dir, judge_url, judge_model, search_config_key,
                      contamination_class, confidence_tier, output_dir,
                      calibrate, calibration_n, calibration_seed, exclude_leaked,
                      supersedes, revision_reason):
    """Hybrid EM->LLM-judge re-score over EvalLogs, post-hoc (tempdoc 624 C-6/E-5).

    EM auto-passes; the EM-misses are judged by the local model (different family
    than the agent), dual-order, abstaining on disagreement. Writes a judge-overlay
    + (optionally) re-composes the JUDGED `utility-comparison.v1`, and (with
    --calibrate) attaches the human-calibration dry run. Requires the `agent`
    extra + a running judge model (`ai_activate`)."""
    import datetime as _dt

    from .. import agent_utility_run as aur
    from .. import utility_comparison as uc
    from .. import utility_governance as ug
    from .. import utility_judge as uj

    _attach_revision(None, supersedes, revision_reason)  # fail fast before judging

    overlay = uj.judge_logs(log_dir, judge_url=judge_url, judge_model=judge_model)
    if calibrate:
        uj.attach_human_calibration(overlay, log_dir, n=calibration_n, seed=calibration_seed)
    path = uj.write_overlay(log_dir, overlay)
    st = overlay["stats"]
    click.echo(f"judge: EM-pass={st['em_auto_pass']} judged-misses={st['judged_misses']} "
               f"flips={st['judge_flips']} disagreements={st['judge_disagreements']} "
               f"agreement={st['agreement_rate']} kind={overlay['judge_identity']['kind']}")
    if st["degraded_to_em"]:
        click.echo("WARNING: judge endpoint unreachable — overlay is EM-only (no LLM verdicts).")
    elif st["call_failures"] > 0:
        click.echo(f"NOTE: {st['call_failures']} of {st['judged_misses'] + st['call_failures']} "
                   f"judge calls failed and fell back to EM for those cases.")
    if calibrate:
        hc = overlay["human_calibration"]
        jvr, rvr = hc["judge_vs_rater_agreement"], hc["rater_vs_rater_agreement"]
        click.echo(f"calibration (rater_kind={hc['rater_kind']!r}, n={hc['n']}): "
                   f"judge-vs-rater kappa={jvr['value']} degenerate_pe={jvr['degenerate_pe']}; "
                   f"rater-vs-rater kappa={rvr['value']} degenerate_pe={rvr['degenerate_pe']}")
    click.echo(f"Written overlay to {path}")

    if output_dir:
        summaries = aur.eval_logs_to_summaries(
            log_dir, search_config_cohort_key=search_config_key, judge_overlay=overlay)
        if exclude_leaked:
            leaked = aur.scan_leaked_cells(log_dir)
            n_flagged = aur.apply_leak_flags(summaries, leaked)
            click.echo(f"leak-scan: {len(leaked)} leaked cell(s), "
                       f"{n_flagged} per-query entries flagged (excluded from paired stats)")
        arms = ug.compute_loss_accounting(log_dir)
        verdict, gmetrics = ug.paired_comparability(arms)
        governance = {"comparable": verdict.comparable, "reasons": verdict.reasons,
                      "metrics": gmetrics,
                      "per_arm_loss": {c: {"n_excluded": l.n_excluded} for c, l in arms.items()}}
        record = uc.compose_utility(
            summaries, composed_at=_dt.datetime.now(_dt.timezone.utc).isoformat(),
            external_baselines=uc.CITED_BASELINES, contamination_class=contamination_class,
            confidence_tier=confidence_tier, governance=governance)
        _attach_revision(record, supersedes, revision_reason)
        # Write BEFORE the print loop: an already-computed record must survive a
        # crash in the print loop (e.g. a malformed cell field) instead of being
        # silently lost. Restores this command's own pre-consolidation order (the
        # cli.py-split refactor moved the write to after the loop here only).
        _write_bench_output(record, output_dir, "utility-comparison.v1.json")
        for slug, by_model in record["measured"].items():
            for m, cell in by_model.items():
                acc = cell["accuracy"]
                click.echo(f"  JUDGED [{slug}/{m}] acc {acc['baseline']}->{acc['with_tool']} "
                           f"(d={acc['delta']:+}, McNemar p={acc['mcnemar_p']})")


@click.command("utility-compose-cross-corpus")
@click.option("--log-dir", "log_dirs", multiple=True, required=True, type=click.Path(exists=True),
              help="Repeatable. One completed `jseval utility-run --log-dir` Inspect log dir per "
                   "corpus (e.g. one per language/battlefield-dimension). Need 2+.")
@click.option("--search-config-key", default=None,
              help="623 config_cohort_key of the live search backend (the with-tool arms' identity).")
@click.option("--contamination-class",
              type=click.Choice(["public-pre-cutoff", "post-cutoff", "private-synthetic", "unknown"]),
              default="public-pre-cutoff", show_default=True)
@click.option("--confidence-tier", type=click.Choice(["A", "B", "C"]), default="C", show_default=True)
@click.option("--exclude-leaked", is_flag=True,
              help="Scan each --log-dir for an answer-key-leak signature (a "
                   "queries.json/queries.jsonl mention in each sample's completion text) "
                   "and exclude matched cells from the paired statistics before pooling "
                   "(tempdoc 624 §As-built #7 follow-up leak-free reanalysis).")
@click.option("--supersedes", default=None, type=click.Path(),
              help="Relative path to the prior record's JSON file this run corrects. "
                   "Must be given together with --revision-reason (tempdoc 624 Design 1). "
                   "When both are given, the pooled record gets a `revision` block; "
                   "when neither is given, the record is unchanged (no `revision` field).")
@click.option("--revision-reason", default=None,
              type=click.Choice(sorted(["leak_correction", "judge_rescore", "reseed", "other"])),
              help="Why this record supersedes --supersedes. Must be given together with "
                   "--supersedes. The revision's `changed_fields` is left empty at the CLI "
                   "level -- the caller isn't expected to know exactly which fields changed "
                   "when composing from the command line; that's a deliberate, honest default.")
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_utility_compose_cross_corpus(ctx, log_dirs, search_config_key, contamination_class,
                                     confidence_tier, exclude_leaked, supersedes, revision_reason,
                                     output_dir):
    """Pool multiple corpora's Inspect logs into ONE cross-corpus stratified record (tempdoc 624).

    `utility-compose`/`utility-run` always produce one SEPARATE top-level record per
    corpus (dataset slug) — so a real cross-corpus split (e.g. English/German/scan)
    can only ever be read off as three individually-pooled records, never as one
    stratified breakdown. This command reuses the exact same EvalLog -> summary
    projection (`eval_logs_to_summaries`) as `utility-run`, one call per --log-dir,
    then pools every corpus's summaries into `compose_utility_cross_corpus`, which
    stratifies the pooled McNemar+bootstrap-CI comparison by corpus — reusing
    `_arm_comparison`'s existing `stratify_by` machinery, not a new statistics path.
    """
    import datetime as _dt

    from .. import agent_utility_run as aur
    from .. import utility_comparison as uc
    from .. import utility_governance as ug

    _attach_revision(None, supersedes, revision_reason)  # fail fast before pooling logs

    all_summaries: list = []
    per_dir = []
    n_flagged = 0
    for ld in log_dirs:
        summaries = aur.eval_logs_to_summaries(ld, search_config_cohort_key=search_config_key)
        if exclude_leaked:
            leaked = aur.scan_leaked_cells(ld)
            n_flagged += aur.apply_leak_flags(summaries, leaked)
        all_summaries.extend(summaries)
        arms = ug.compute_loss_accounting(ld)
        verdict, gmetrics = ug.paired_comparability(arms)
        per_dir.append((ld, verdict, gmetrics, arms))

    if exclude_leaked:
        click.echo(f"leak-scan: {n_flagged} per-query entries flagged across "
                   f"{len(log_dirs)} log dir(s) (excluded from paired stats)")

    # A cross-corpus record cannot be more trustworthy than its least-comparable
    # input: comparable only if EVERY pooled corpus's run was itself comparable.
    def _label(ld):
        return Path(ld).name

    governance = {
        "comparable": all(v.comparable for _, v, _, _ in per_dir),
        "reasons": [f"{_label(ld)}: {r}" for ld, v, _, _ in per_dir for r in v.reasons],
        "metrics": {_label(ld): gm for ld, _, gm, _ in per_dir},
        "per_arm_loss": {
            f"{_label(ld)}:{c}": {"n_attempted": l.n_attempted, "n_completed": l.n_completed,
                                  "n_excluded": l.n_excluded, "exclusion_rate": round(l.exclusion_rate, 4)}
            for ld, _, _, arms in per_dir for c, l in arms.items()
        },
    }

    record = uc.compose_utility_cross_corpus(
        all_summaries, composed_at=_dt.datetime.now(_dt.timezone.utc).isoformat(),
        external_baselines=uc.CITED_BASELINES, contamination_class=contamination_class,
        confidence_tier=confidence_tier, governance=governance,
    )
    _attach_revision(record, supersedes, revision_reason)

    if ctx.obj.get("json"):
        click.echo(json.dumps(record, indent=2, default=str))
    else:
        click.echo(f"corpora={record['corpora']}")
        for m, cell in record["measured"].items():
            acc, tok = cell["accuracy"], cell["tokens_unique"]
            click.echo(
                f"[pooled/{m}] acc {acc['baseline']}->{acc['with_tool']} "
                f"(d={acc['delta']:+}, McNemar p={acc['mcnemar_p']}); n={cell['n_paired_observations']}; "
                f"unique-tokens median {tok['baseline']['median']}->{tok['with_tool']['median']} "
                f"(d_mean={tok['delta_mean']:+})")
            for slabel, strat in (cell.get("stratified") or {}).get("by_stratum", {}).items():
                sacc = strat["accuracy"]
                click.echo(
                    f"    stratum[{slabel}] acc {sacc['baseline']}->{sacc['with_tool']} "
                    f"(d={sacc['delta']:+}, McNemar p={sacc['mcnemar_p']}, n={strat['n_paired_observations']})")
        click.echo(f"COMPARABLE={governance['comparable']}" + ("" if governance["comparable"]
                   else " — reasons: " + "; ".join(governance["reasons"])))
        click.echo(f"seeds={record['seed_count']} tier={record['confidence_tier']} "
                   f"contamination={record['coverage']['contamination_class']}")

    _write_bench_output(record, output_dir, "utility-comparison-cross-corpus.v1.json")


COMMANDS = [cmd_utility_compose, cmd_utility_run, cmd_utility_calibrate, cmd_utility_status,
           cmd_utility_judge, cmd_utility_compose_cross_corpus]
