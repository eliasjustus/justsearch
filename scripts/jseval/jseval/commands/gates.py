"""jseval gates commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import sys
from pathlib import Path
import logging

import click

log = logging.getLogger(__name__)


@click.command("gate")
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
    from .. import gate as _gate

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


@click.command("relevance-gate")
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
@click.option("--allow-engine-mismatch", is_flag=True,
              help="Override the tempdoc-644 homogeneity refusal (run vs baseline realized engine "
                   "set differ — e.g. cross-encoder on vs off). Use only when comparing degraded "
                   "numbers deliberately.")
@click.pass_context
def cmd_relevance_gate(ctx, data_dir, dataset, baselines, run_dir, report_out,
                       allow_engine_mismatch):
    """Q-010 relevance ratchet (tempdoc 580 §4c) — fail on nDCG@10 regression.

    Reads the latest eval-results run's summary.json for DATASET and compares
    its nDCG@10 (in the pinned mode) against the per-corpus floor in
    gates/relevance-ratchet/baselines.v1.json. Unlike ``gate`` (which checks
    nDCG@10 *stdev* for drift), this checks the *mean* against a regression
    floor. Exit 0 = no regression (or un-pinned dataset), 1 = regression,
    2 = run metric missing.
    """
    from .. import ratchet_kernel as _rk
    from .. import relevance_gate as _rgate

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
    # tempdoc 644: refuse to compare a run whose realized engine set differs from the baseline's
    # (e.g. a CE-off worktree run vs a CE-on baseline) — apples-to-oranges. Backward-compatible.
    _rk.assert_cohort_engines(rd, baselines, allow_mismatch=allow_engine_mismatch)
    run_summary = json.loads((rd / "summary.json").read_text(encoding="utf-8"))
    report = _rgate.evaluate(baselines_doc, run_summary, dataset)
    _rk.finalize_report(report, run_dir=rd, baselines_path=baselines,
                        report_out=report_out, summary_fields=("current", "baseline", "floor"))


@click.command("perf-gate")
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
@click.option("--allow-engine-mismatch", is_flag=True,
              help="Override the tempdoc-644 homogeneity refusal (run vs baseline realized engine "
                   "set differ — e.g. cross-encoder on vs off).")
@click.pass_context
def cmd_perf_gate(ctx, data_dir, dataset, baselines, run_dir, report_out, mode, update_baseline,
                  allow_engine_mismatch):
    """Performance ratchet (tempdoc 640) — fail on a latency/throughput/footprint regression.

    The perf-metric-family sibling of ``relevance-gate``. Reads the latest eval-results run's
    summary.json (+ manifest.json for footprint) for DATASET and compares each pinned metric
    against its RELATIVE band in scripts/jseval/perf-ratchet-baselines.v1.json (no absolute
    SLO — tempdoc 640 §C-6). Gate-able metrics: cross-encoder STAGE p50 latency, primary +
    enrichment throughput, retrieval ONNX footprint (best-effort). Exit 0 = no regression
    (or un-pinned dataset), 1 = regression, 2 = a pinned metric missing from the run.
    """
    from .. import perf_gate as _pgate
    from .. import ratchet_kernel as _rk

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

    # tempdoc 644: refuse a cross-engine-set comparison (run vs baseline realized engines differ);
    # also protects --update-baseline from pinning a degraded (e.g. CE-off) run. Backward-compatible.
    _rk.assert_cohort_engines(rd, baselines, allow_mismatch=allow_engine_mismatch)

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


@click.command("leak-gate")
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
@click.option("--allow-engine-mismatch", is_flag=True,
              help="Override the tempdoc-644 homogeneity refusal (run vs baseline realized engine "
                   "set differ — e.g. cross-encoder on vs off).")
@click.pass_context
def cmd_leak_gate(ctx, data_dir, dataset, baselines, run_dir, report_out, allow_engine_mismatch):
    """Recall-leak ratchet (tempdoc 636 / register D-005) — fail on leak-rate regression.

    Reads the latest eval-results run's staged_recall_accounting projection for
    DATASET and compares its leak_rate against the per-corpus *ceiling* in
    leak-gate-baselines.v1.json. The recall-survival sibling of ``relevance-gate``.
    Exit 0 = no regression (or un-pinned dataset), 1 = regression, 2 = projection
    missing.
    """
    from .. import leak_gate as _lgate
    from .. import ratchet_kernel as _rk

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

    # tempdoc 644: refuse a cross-engine-set comparison before gating. resolve_run_dir is
    # deterministic so calling it here + inside run_gate is consistent. Backward-compatible.
    _rk.assert_cohort_engines(
        _rk.resolve_run_dir(run_dir, data_dir), baselines, allow_mismatch=allow_engine_mismatch)
    # Tempdoc 640 K: leak is the SIMPLE case (no current_release, no extra guards) — it uses the
    # kernel's `run_gate` convenience directly; only its source reader differs (projection vs summary).
    _rk.run_gate(
        baselines_path=baselines, data_dir=data_dir, run_dir=run_dir, dataset=dataset,
        read_inputs=_read_projection, evaluate=_lgate.evaluate,
        report_out=report_out, summary_fields=("current", "baseline", "floor"),
    )


@click.command("llm-gate")
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
    from .. import llm_gate as _lg
    from .. import ratchet_kernel as _rk

    if baselines is None:
        baselines = Path(__file__).resolve().parents[1] / "llm-gen-ratchet-baselines.v1.json"
    baselines_doc = json.loads(Path(baselines).read_text(encoding="utf-8"))
    bench_doc = json.loads(Path(bench_file).read_text(encoding="utf-8"))

    if update_baseline:
        from ..manifest import _git_sha_full
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


@click.command("leak-gate-derive")
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
    from .. import leak_gate as _lgate

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


@click.command("recall-profile")
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
    from .. import recall_profile as _rp
    from ..projections import staged_recall_accounting as _sra

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


@click.command("judge-ceiling")
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
    from .. import judge_ceiling as _jc

    rd = Path(run_dir)
    qrels = json.loads((rd / "qrels.json").read_text(encoding="utf-8")) if (rd / "qrels.json").is_file() else {}

    # queries + ceiling/final_ndcg from the projection (or recompute).
    proj_path = rd / "projections" / "staged_recall_accounting.json"
    if proj_path.is_file():
        proj = json.loads(proj_path.read_text(encoding="utf-8"))
    else:
        from ..projections import staged_recall_accounting as _sra
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


@click.command("extraction-gate")
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
    from .. import extraction_gate as _xgate

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


COMMANDS = [cmd_gate, cmd_relevance_gate, cmd_perf_gate, cmd_leak_gate, cmd_llm_gate, cmd_leak_gate_derive, cmd_recall_profile, cmd_judge_ceiling, cmd_extraction_gate]
