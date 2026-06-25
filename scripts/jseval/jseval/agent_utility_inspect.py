"""Agent-utility execution THROUGH Inspect AI (tempdoc 624 execution design).

Runs the cell matrix `{corpus × model × condition × seed × query}` as an Inspect
eval rather than a bespoke fan-out: **condition = task, seed = `epochs`,
query = `sample.id`, cohort identity = task-args**. Inspect's `eval_set` gives
durable resume (skip completed samples), bounded/adaptive concurrency, and a
schema-valid EvalLog — the parts a bespoke executor would re-implement (and
fork). Verified against `inspect-ai 0.3.240` in tempdoc 624 §Confidence-pass #2.

This is an **opt-in** path: `pip install jseval[agent]` (the `inspect-ai` extra).
The composer (`utility_comparison.compose_utility`) is unchanged — it projects the
per-cell results (read back from the EvalLogs) into `utility-comparison.v1`.

Identity carried, not forked (the "one identity, three roles" principle):
- `sample.id` = the stable query id  → resume key,
- task-args = the cohort identity (model / cli-version / mcp-surface / judge /
  prompt / decoding) → a config change segregates logs (no stale reuse),
- `epoch` = the seed → the seed envelope.

A4 wrinkle (verified): Inspect does NOT auto-capture a shell-out solver's usage,
so the solver stashes claude's cost / unique-tokens / turns into `state.metadata`
(round-trips to `EvalSample.metadata`).
"""

from __future__ import annotations

import asyncio
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

from inspect_ai import Task, task
from inspect_ai.dataset import Sample
from inspect_ai.scorer import Score, Target, accuracy, scorer
from inspect_ai.solver import Generate, TaskState, solver

from jseval.agent_retrieval_eval import _score_answer

# Condition semantics (tempdoc 346): A = file tools only (baseline),
# B = file + JustSearch, C = JustSearch only (substitution).
_WITH_TOOL = {"B", "C"}

_PROMPT = (
    "Answer the following question using only the documents in {corpus_dir}. "
    "Do not use prior knowledge. Be concise. Question: {query}"
)


def _build_argv(claude_bin, prompt, model, corpus_dir, condition, mcp_config, empty_mcp, max_budget):
    """The condition-appropriate `claude -p` argv (mirrors agent_retrieval_eval)."""
    cmd = [
        claude_bin, "-p", prompt,
        "--model", model,
        "--output-format", "json",
        "--max-budget-usd", str(max_budget),
        "--permission-mode", "bypassPermissions",
        "--add-dir", corpus_dir,
    ]
    if condition == "A":
        cmd += ["--strict-mcp-config", "--mcp-config", empty_mcp]
    elif condition in _WITH_TOOL:
        if mcp_config:
            cmd += ["--strict-mcp-config", "--mcp-config", mcp_config]
        if condition == "C":
            cmd += ["--disallowedTools", "Read,Grep,Glob"]
    return cmd


@solver
def claude_agent_solver(condition: str, corpus_dir: str, mcp_config: str | None = None,
                        model: str = "haiku", max_budget: float = 0.50, timeout_s: int = 180):
    """Per-sample solver: spawn a `claude -p` coding-agent subprocess for one query.

    Runs the blocking subprocess via ``asyncio.to_thread`` so Inspect's
    ``max_samples`` concurrency (asyncio) is not blocked. Stashes claude's
    cost / unique-tokens / turns in ``state.metadata`` (A4 — Inspect can't see a
    subprocess's usage). An errored cell sets ``metadata.error`` so the projection
    can exclude it (parity with the bespoke harness's valid-only aggregation).
    """
    claude_bin = shutil.which("claude")

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        if not claude_bin:
            state.metadata["error"] = "claude CLI not found in PATH"
            return state
        query_cwd = tempfile.mkdtemp(prefix="jseval-inspect-")  # isolate from repo CLAUDE.md
        empty_mcp = str(Path(query_cwd) / "_empty_mcp.json")
        if condition == "A":
            Path(empty_mcp).write_text('{"mcpServers":{}}', encoding="utf-8")
        prompt = _PROMPT.format(corpus_dir=corpus_dir, query=state.input_text)
        cmd = _build_argv(claude_bin, prompt, model, corpus_dir, condition, mcp_config, empty_mcp, max_budget)
        try:
            proc = await asyncio.to_thread(
                subprocess.run, cmd,
                capture_output=True, text=True, timeout=timeout_s,
                cwd=query_cwd, encoding="utf-8", errors="replace",
            )
            data = json.loads(proc.stdout or "{}")
            if data.get("is_error") or proc.returncode != 0:
                state.metadata["error"] = (data.get("result") or proc.stderr or f"exit {proc.returncode}")[:300]
                return state
            state.output.completion = data.get("result", "")
            usage = data.get("usage") or {}
            state.metadata.update({
                "cost_usd": data.get("total_cost_usd"),
                "unique_tokens": usage.get("cache_creation_input_tokens"),
                "num_turns": data.get("num_turns"),
            })
        except Exception as e:  # timeout / json / subprocess failure → excluded cell
            state.metadata["error"] = str(e)[:300]
        return state

    return solve


@scorer(metrics=[accuracy()])
def substring_scorer():
    """Reuse the harness substring + abstention scorer as an Inspect scorer."""
    async def score(state: TaskState, target: Target) -> Score:
        if state.metadata.get("error"):
            return Score(value="I", answer="", metadata={"error": state.metadata["error"]})
        ok = _score_answer(target.text, state.output.completion or "")
        return Score(value="C" if ok else "I", answer=(state.output.completion or "")[:200])
    return score


@task
def agent_utility_task(condition: str = "A", queries_path: str = "", corpus_dir: str = "",
                       mcp_config: str | None = None, model: str = "haiku",
                       max_queries: int | None = None, max_budget: float = 0.50,
                       timeout_s: int = 180,
                       # --- cohort identity (task-args → config-change log segregation, A2) ---
                       cli_version: str | None = None, mcp_tool_surface_hash: str | None = None,
                       judge_kind: str = "substring-em", prompt_template_hash: str | None = None,
                       corpus_dataset: str = "", corpus_signature: str = "") -> Task:
    """One Inspect task = one condition over the corpus×query dataset.

    The cohort-identity args are what `eval_set` segregates logs by, so a model /
    CLI / MCP-surface / judge / prompt change creates a new log instead of reusing
    a stale completed sample.
    """
    rows = json.load(open(queries_path, encoding="utf-8"))
    if max_queries:
        rows = rows[:max_queries]
    samples = [
        Sample(id=f"q{i}", input=r["query"], target=r["answer"],
               metadata={"question_type": r.get("question_type")})
        for i, r in enumerate(rows)
    ]
    return Task(
        dataset=samples,
        solver=claude_agent_solver(condition, corpus_dir, mcp_config, model, max_budget, timeout_s),
        scorer=substring_scorer(),
        metadata={
            "condition": condition, "model": model,
            "corpus": {"dataset": corpus_dataset, "signature": corpus_signature or corpus_dataset},
            "cohort": {
                "model": model, "cli_version": cli_version,
                "mcp_tool_surface_hash": mcp_tool_surface_hash,
                "judge_kind": judge_kind, "prompt_template_hash": prompt_template_hash,
            },
        },
    )


def run_utility_eval(*, queries_path: str, corpus_dir: str, mcp_config: str | None,
                     model: str = "haiku", conditions=("A", "C"), seeds: int = 3,
                     concurrency: int = 6, log_dir: str, max_queries: int | None = None,
                     max_budget: float = 0.50, timeout_s: int = 180,
                     cli_version: str | None = None,
                     mcp_tool_surface_hash: str | None = None,
                     corpus_dataset: str = "", corpus_signature: str = "") -> str:
    """Run the matrix through Inspect `eval_set` (resumable). seeds → `epochs`.

    Returns the log_dir; re-invoking with the same log_dir resumes (skips done
    samples). condition A passes no mcp_config; B/C pass the JustSearch one.
    """
    from inspect_ai import eval_set

    from jseval.manifest import _sha256_canonical

    # The prompt template is identical across conditions → its hash is a cohort
    # field (so A and C share an agent_cohort_key, but a prompt change segregates).
    prompt_template_hash = _sha256_canonical(_PROMPT)
    tasks = [
        agent_utility_task(
            condition=c, queries_path=queries_path, corpus_dir=corpus_dir,
            mcp_config=(mcp_config if c in _WITH_TOOL else None), model=model,
            max_queries=max_queries, max_budget=max_budget, timeout_s=timeout_s,
            cli_version=cli_version,
            mcp_tool_surface_hash=mcp_tool_surface_hash, judge_kind="substring-em",
            prompt_template_hash=prompt_template_hash, corpus_dataset=corpus_dataset,
            corpus_signature=corpus_signature,
        )
        for c in conditions
    ]
    # Pin a DETERMINISTIC eval_set_id (default is random per-process): without it,
    # re-invoking after a crash fails with "log file not associated with a task"
    # because the set identity differs across processes. Derived from the run
    # config so the same run resumes and a different run gets a fresh set.
    eval_set_id = _sha256_canonical({
        "log_dir": log_dir, "conditions": sorted(conditions), "model": model,
        "queries": queries_path, "prompt": prompt_template_hash,
    })[:22]
    # log_format="json": the .eval (zip) recorder breaks on Windows fsspec paths
    # during eval_set's log cleanup; JSON logs are text + portable.
    eval_set(tasks, log_dir=log_dir, epochs=seeds, model="mockllm/model",
             max_samples=concurrency, log_format="json", eval_set_id=eval_set_id)
    return log_dir
