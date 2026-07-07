"""jseval analysis commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path
import logging

import click

from .._paths import DEFAULT_EVAL_RESULTS
from ._common import _DEFAULT_BASE_URL

log = logging.getLogger(__name__)


@click.command("counterfactual")
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

    from .. import corpora, counterfactual

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


@click.command("shadow-eval")
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

    from .. import corpora, shadow_eval

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


@click.command("bisect")
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
    from .. import bisection
    from .. import calibrate

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


@click.command("compare")
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
    from .. import compare_runs

    a_data = _load_run_for_compare(Path(run_a), mode)
    b_data = _load_run_for_compare(Path(run_b), mode)

    # Need qrels for query alignment
    from .. import corpora
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

    # Tempdoc 647: per-query latency STAGE decomposition diff + "which-stage-moved" attribution.
    stage_diff = compare_runs.compare_stage_decomposition(
        a_data["summary"], b_data["summary"], mode or _first_mode(a_data["summary"]),
    )

    # Tempdoc 525: optional per-bucket stratification.
    bucket_results: dict[str, dict] = {}
    if bucket_by == "decision_kind":
        bucket_results = _compare_by_decision_kind(a_data, b_data, qrels)

    if ctx.obj.get("json"):
        output: dict[str, object] = {"metrics": results}
        if pipeline_diff:
            output["pipeline_timing"] = pipeline_diff
        if stage_diff:
            output["stage_decomposition"] = stage_diff
        if bucket_results:
            output["by_decision_kind"] = bucket_results
        if not pipeline_diff and not stage_diff and not bucket_results:
            output = results  # preserve original format when no extra data
        click.echo(json.dumps(output, indent=2, default=str))
    else:
        _print_comparison(results)
        if pipeline_diff:
            _print_pipeline_comparison(pipeline_diff)
        if stage_diff:
            _print_stage_decomposition_comparison(stage_diff)
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


@click.command("trend")
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
    help="Family to trend: nDCG@10 | ce_p50_ms | primary_docs_s | enrich_docs_s | resident_bytes | "
         "retrieval_p50_ms | unaccounted_p50_ms (tempdoc 640 R3 + 647). Direction is metric-aware "
         "(latency/footprint lower-is-better).",
)
def cmd_trend(dataset, mode, output_dir, fail_on_regression, manifest_hash, metric):
    """Check metric history and trends."""
    from .. import history

    trend = history.check_trend(
        dataset, mode, Path(output_dir), manifest_hash=manifest_hash, metric=metric,
    )
    click.echo(json.dumps(trend, indent=2))
    if fail_on_regression and trend.get("status") == "regression":
        sys.exit(1)


@click.command("spot-check")
@click.option("--suite", type=click.Path(exists=True), default=None, help="Custom query suite JSON.")
@click.option("--modes", default="lexical,hybrid", show_default=True)
@click.option("--top-k", default=5, show_default=True)
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_spot_check(ctx, suite, modes, top_k, base_url, output_dir):
    """Developer ranking spot-check (no qrels needed)."""
    from .. import spot_check

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


@click.command("correction-probe")
@click.option("--manifest", type=click.Path(exists=True), default=None,
              help="Correction query manifest JSON.")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--top-k", default=10, show_default=True)
@click.option("--output-dir", type=click.Path(), default=None)
@click.pass_context
def cmd_correction_probe(ctx, manifest, base_url, top_k, output_dir):
    """Evaluate search correction (did-you-mean) quality."""
    from .. import correction_probe

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


@click.command("diff")
@click.argument("baseline", type=click.Path(exists=True))
@click.argument("candidate", type=click.Path(exists=True))
@click.option("--lane", default="claim-a", show_default=True,
              help="Benchmark lane for threshold selection.")
def cmd_diff(baseline, candidate, lane):
    """Compare two benchmark artifacts for regression."""
    from .. import diff_gate

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


def _print_stage_decomposition_comparison(diff: dict) -> None:
    """Tempdoc 647: per-stage query-latency p50 diffs + the which-stage-moved attribution."""
    click.echo("Query-latency stage decomposition (run B vs run A, p50 ms):")
    for key, comp in sorted((diff.get("stages") or {}).items()):
        if isinstance(comp, dict) and "a" in comp and "b" in comp:
            ratio = comp.get("ratio")
            ratio_str = f" ({ratio:.2f}x)" if ratio else ""
            flag = " REGRESSED" if comp.get("regressed") else ""
            click.echo(
                f"  {key}: {comp['a']} -> {comp['b']} (delta={comp.get('delta', 0):+.1f})"
                f"{ratio_str}{flag}"
            )
    attr = diff.get("attribution") or {}
    mover = attr.get("primary_mover")
    total = attr.get("total_p50_delta_ms")
    total_str = f"latency p50 {total:+.1f} ms" if isinstance(total, (int, float)) else "latency p50 (n/a)"
    if mover:
        click.echo(
            f"  -> {total_str} | primary mover: {mover} ({attr.get('mover_delta_ms', 0):+.1f} ms)"
        )
    else:
        click.echo(f"  -> {total_str} | no stage moved")
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


# --- tempdoc 647: per-run performance-attribution report -------------------

def build_perf_report(summary: dict, manifest: dict | None, mode: str | None) -> dict:
    """Assemble the per-run performance-attribution view (tempdoc 647) as a pure projection of the
    MATERIALIZED decomposition — unit-testable, and it never re-derives what the record already holds.

    Latency reads ``per_mode.<mode>.stage_timing_stats`` (each stage's p50/p95 + the *materialized*
    ``share``, plus the ``unaccounted_ms`` remainder); the share is **read**, never recomputed
    (tempdoc 647 "consumers read, never re-derive"). Footprint uses the per-component split from
    :func:`jseval.perf_gate.derive_resident_component_bytes` (the single authority); component
    *display* shares (``component/total``) are computed here only because footprint shares are not
    materialized anywhere. Returns ``{"mode", "latency", "footprint"}`` with ``None`` sections when a
    source is absent (old-format runs, unresolvable model paths) — never raises.
    """
    from .. import perf_gate

    report: dict = {"mode": mode, "latency": None, "footprint": None}

    pm = ((summary.get("per_mode") or {}).get(mode) or {}) if mode else {}
    st = pm.get("stage_timing_stats") or {}
    if st:
        stages = [
            {
                "stage": name,
                "p50_ms": entry.get("p50"),
                "p95_ms": entry.get("p95"),
                "share": entry.get("share"),  # materialized; None on pre-647 runs
            }
            for name, entry in st.items()
            if isinstance(entry, dict)
        ]
        stages.sort(key=lambda s: (s["p50_ms"] is None, -(s["p50_ms"] or 0)))
        report["latency"] = {
            "total_p50_ms": (pm.get("latency_stats") or {}).get("p50_ms"),
            "stages": stages,
        }

    components = perf_gate.derive_resident_component_bytes(manifest)
    if components:
        total = sum(components.values())
        report["footprint"] = {
            "total_bytes": total,
            "components": [
                {"component": k, "bytes": v, "share": round(v / total, 4) if total else None}
                for k, v in sorted(components.items(), key=lambda kv: -kv[1])
            ],
        }
    return report


def _bar(share: object, width: int = 32) -> str:
    """An ASCII share bar (empty when the share is unavailable — e.g. a pre-647 run)."""
    if not isinstance(share, (int, float)):
        return ""
    return "#" * max(0, min(width, round(share * width)))


def _pct(share: object) -> str:
    return f"{share * 100:5.1f}%" if isinstance(share, (int, float)) else "   -- "


def _print_perf_report(report: dict) -> None:
    click.echo(f"Performance attribution report (mode: {report.get('mode') or '?'})")
    click.echo()
    lat = report.get("latency")
    if lat:
        total = lat.get("total_p50_ms")
        total_str = f"{total} ms" if isinstance(total, (int, float)) else "n/a"
        click.echo(f"Query latency (p50 total: {total_str}) -- stage decomposition:")
        for s in lat["stages"]:
            p95 = s.get("p95_ms")
            p95_str = f"(p95 {p95})" if p95 is not None else ""
            click.echo(
                f"  {s['stage']:<18} {str(s.get('p50_ms')) + ' ms':>9} {p95_str:<11}"
                f" [{_pct(s.get('share'))}] {_bar(s.get('share'))}"
            )
        click.echo()
    else:
        click.echo("Query latency: no stage_timing_stats for this mode.\n")
    foot = report.get("footprint")
    if foot:
        total_mb = foot["total_bytes"] / 1024 / 1024
        click.echo(f"Resident footprint (total: {total_mb:.0f} MB) -- per-component allocation:")
        for c in foot["components"]:
            mb = c["bytes"] / 1024 / 1024
            click.echo(f"  {c['component']:<18} {mb:7.0f} MB [{_pct(c.get('share'))}] {_bar(c.get('share'))}")
        click.echo()
    else:
        click.echo("Resident footprint: unresolvable (no manifest model paths).\n")


@click.command("perf-report")
@click.argument("run_dir", type=click.Path(exists=True))
@click.option("--mode", default=None, help="Mode to report (auto-detected if omitted).")
@click.pass_context
def cmd_perf_report(ctx, run_dir, mode):
    """Tempdoc 647: a per-run performance-attribution report -- the query-latency stage decomposition
    (p50 + p95 tail + materialized shares) and the resident-footprint per-component allocation.

    Reads a run's `summary.json` + `manifest.json`; reads the materialized decomposition (never
    re-derives it). Old-format runs (pre-647) render p50/p95 without shares."""
    run = Path(run_dir)
    summary_path = run / "summary.json"
    if not summary_path.is_file():
        raise click.ClickException(
            f"no summary.json in {run} -- expected an eval-results run directory.")
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    manifest_path = run / "manifest.json"
    manifest = (
        json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else None
    )
    report = build_perf_report(summary, manifest, mode or _first_mode(summary))
    if ctx.obj.get("json"):
        click.echo(json.dumps(report, indent=2, default=str))
    else:
        _print_perf_report(report)


COMMANDS = [cmd_counterfactual, cmd_shadow_eval, cmd_bisect, cmd_compare, cmd_trend, cmd_spot_check, cmd_correction_probe, cmd_diff, cmd_perf_report]
