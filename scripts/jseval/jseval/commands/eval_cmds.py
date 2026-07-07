"""jseval eval_cmds commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import sys
from pathlib import Path
import logging

import click
from ._common import _DEFAULT_BASE_URL_EVAL, _write_bench_output

log = logging.getLogger(__name__)


@click.command("rag-eval")
@click.option("--profile", default="stub-jaccard", show_default=True)
@click.option("--baseline", type=click.Path(exists=True), default=None)
@click.option("--output-dir", type=click.Path(), default=None)
def cmd_rag_eval(profile, baseline, output_dir):
    """RAG quality evaluation (Gradle test + metric comparison)."""
    from .. import rag_eval

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


@click.command("extract-eval")
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
    from .. import extract_eval

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


@click.command("ner-eval")
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
    from .. import ner_eval

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


@click.command("retrieval-eval")
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
    from .. import agent_retrieval_eval as are

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


@click.command("tier2-eval")
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
    from .. import agent_retrieval_eval as are

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


@click.command("agent-eval")
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
    from .. import agent_retrieval_eval as are

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


COMMANDS = [cmd_rag_eval, cmd_extract_eval, cmd_ner_eval, cmd_retrieval_eval, cmd_tier2_eval, cmd_agent_eval]
