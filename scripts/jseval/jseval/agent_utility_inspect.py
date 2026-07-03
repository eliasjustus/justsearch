"""Agent-utility execution THROUGH Inspect AI (tempdoc 624 execution design).

Runs the cell matrix `{corpus × model × condition × seed × query}` as an Inspect
eval rather than a bespoke fan-out: **condition = task, seed = `epochs`,
query = `sample.id`, cohort identity = task-args**. Inspect's `eval_set` gives
durable resume (skip completed samples), bounded/adaptive concurrency, and a
schema-valid EvalLog — the parts a bespoke executor would re-implement (and
fork). Verified against `inspect-ai 0.3.240` in tempdoc 624 §Confidence-pass #2.

This is an **opt-in** path: `pip install jseval[agent]` (the `inspect-ai` extra).
The composer (`utility_comparison.compose_utility`) projects the per-cell results
(read back from the EvalLogs via `agent_utility_run.eval_logs_to_summaries`) into
`utility-comparison.v1`, including the `tool_call_assertions` coverage block
(tempdoc 624 §As-built #5 residual-gap close).

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

from jseval.agent_retrieval_eval import (
    _score_answer,
    build_disallowed_tools,
    find_disallowed_tool_calls,
    find_leak_suspect_tool_calls,
    parse_claude_init_event,
    parse_claude_stream_json,
    stage_corpus_dir,
)
from jseval.utility_calibrate import (
    assert_mcp_config_http_typed,
    assert_watched_roots_scoped,
    base_url_from_mcp_config,
)

# Condition semantics (tempdoc 346): A = file tools only (baseline),
# B = file + JustSearch, C = JustSearch only (substitution).
_WITH_TOOL = {"B", "C"}

_PROMPT = (
    "Answer the following question using only the documents in {corpus_dir}. "
    "Do not use prior knowledge. Be concise. Question: {query}"
)


def _build_argv(claude_bin, prompt, model, corpus_dir, condition, mcp_config, empty_mcp, max_budget):
    """The condition-appropriate `claude -p` argv (mirrors
    agent_retrieval_eval._build_agent_cmd's exact argv pattern, byte-for-byte on
    the flags that matter for stream parsing).

    ``--output-format stream-json`` (with mandatory ``--verbose`` — the CLI
    requires it for stream-json under `-p`) instead of the single-shot ``json``
    format: gives the solver real per-tool-call data (name + args), which
    `claude_agent_solver` needs for an EMPIRICAL --disallowedTools check and the
    answer-key-leak backstop (tempdoc 624 §As-built #5 residual-gap close — the
    prior `json` format captured only the final result text, so neither check
    had any tool-call data to scan for an Inspect-executed cell)."""
    cmd = [
        claude_bin, "-p", prompt,
        "--model", model,
        "--output-format", "stream-json",
        "--verbose",
        "--max-budget-usd", str(max_budget),
        "--permission-mode", "bypassPermissions",
        "--add-dir", corpus_dir,
    ]
    if condition == "A":
        cmd += ["--strict-mcp-config", "--mcp-config", empty_mcp]
    elif condition in _WITH_TOOL:
        if mcp_config:
            cmd += ["--strict-mcp-config", "--mcp-config", mcp_config]
    cmd += ["--disallowedTools", ",".join(build_disallowed_tools(condition))]
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
        # Bounded per-cell retry (max 2 attempts) for TRANSIENT infra failures —
        # a silent `claude` CLI death (rc!=0, empty stderr) was measured at a
        # ~5%/cell background rate under sustained 8-way load (2026-07-03 probe:
        # 38/40 ok), and one such rate compounds into comparability-breaking
        # per-arm exclusion. The retry is symmetric across conditions and
        # DISCLOSED per cell (`attempts`, `first_error`) so the record shows
        # exactly which cells needed it; a cell failing both attempts is still
        # excluded via `error` as before.
        first_error: str | None = None
        for attempt in (1, 2):
            try:
                proc = await asyncio.to_thread(
                    subprocess.run, cmd,
                    capture_output=True, text=True, timeout=timeout_s,
                    cwd=query_cwd, encoding="utf-8", errors="replace",
                    stdin=subprocess.DEVNULL,  # never inherit the harness console
                )
                stdout = (proc.stdout or "").strip()
                # Same event-stream shape + parser as the classic runner (both call
                # agent_retrieval_eval.parse_claude_stream_json) — no forked logic.
                tool_calls, data, _session_id = parse_claude_stream_json(stdout)
                disallowed = build_disallowed_tools(condition)
                # Stash the tool-call capture + derived assertions UNCONDITIONALLY
                # (before the error check) so a cell that erred out mid-run still
                # carries whatever tool calls it made before failing — the
                # credibility bar (tempdoc 624 §M.8 item 2) is "every cell actually
                # run", not just the successful ones.
                state.metadata["tool_calls"] = tool_calls
                state.metadata["disallowed_tool_calls"] = find_disallowed_tool_calls(tool_calls, disallowed)
                state.metadata["leak_suspect_tool_calls"] = find_leak_suspect_tool_calls(tool_calls)
                # Offered MCP tool-surface capture + assertion (tempdoc 624 battlefield
                # retrospective): parse_claude_init_event reads the CLI's own disclosure of
                # what it actually connected/offered for THIS invocation -- the signal a
                # dead mcp_config (missing "type":"http", silently dropped by the CLI) does
                # NOT show up in (the process still exits 0 and answers from file tools).
                # Stashed unconditionally like tool_calls above; the ASSERTION below only
                # fires for a real with-tool config (B/C with mcp_config set) -- condition A
                # is exempt by construction (its argv always uses the empty config).
                init_event = parse_claude_init_event(stdout)
                mcp_servers = init_event["mcp_servers"] if init_event else None
                mcp_tools_offered = (
                    sum(1 for t in init_event["tools"] if t.startswith("mcp__"))
                    if init_event else None
                )
                state.metadata["mcp_servers"] = mcp_servers
                state.metadata["mcp_tools_offered"] = mcp_tools_offered
                if condition in _WITH_TOOL and mcp_config:
                    if init_event is None:
                        # Stream never reached/emitted an init event (crash/timeout
                        # before the CLI got that far) -- not proof the surface was
                        # missing, so this must not error; flag unverified instead so
                        # "unknown" is never conflated with "verified healthy".
                        state.metadata["mcp_surface_unverified"] = True
                    else:
                        justsearch_tools = [
                            t for t in init_event["tools"] if t.startswith("mcp__justsearch")
                        ]
                        if not justsearch_tools:
                            state.metadata["error"] = (
                                f"expected MCP tool surface not offered: mcp_servers={mcp_servers}"
                            )
                            break
                if data is None or data.get("is_error") or proc.returncode != 0:
                    # Forensics INTO the record: a bare "exit 1" with no stderr was
                    # unactionable across two failed runs — keep the evidence.
                    err = (data.get("result") if data else None) or (proc.stderr or "").strip() or f"exit {proc.returncode}"
                    err = (f"exit {proc.returncode}: {str(err)[:200]} | stderr: {(proc.stderr or '')[:150]!r}"
                           f" | stdout_tail: {stdout[-200:]!r}")
                    if attempt == 1:
                        first_error = err
                        continue
                    state.metadata["error"] = err[:600]
                    break
                state.output.completion = data.get("result", "")
                usage = data.get("usage") or {}
                state.metadata.update({
                    "cost_usd": data.get("total_cost_usd"),
                    "unique_tokens": usage.get("cache_creation_input_tokens"),
                    "num_turns": data.get("num_turns"),
                })
                break
            except Exception as e:  # timeout / json / subprocess failure
                if attempt == 1:
                    first_error = f"{type(e).__name__}: {str(e)[:250]}"
                    continue
                state.metadata["error"] = f"{type(e).__name__}: {str(e)[:250]}"
        state.metadata["attempts"] = attempt
        if first_error is not None:
            state.metadata["first_error"] = first_error
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
    # Dead-config fail-fast (tempdoc 624 battlefield retrospective): a `url`-only
    # `mcpServers` entry (no `"type":"http"`) is silently DROPPED by the `claude`
    # CLI — the run would otherwise complete "successfully" with zero MCP tool
    # calls per cell (proven: all 260 certified condition-B battlefield cells hit
    # exactly this). Checked before anything else in this function so a dead
    # config aborts before any staging/subprocess work happens.
    if mcp_config:
        assert_mcp_config_http_typed(mcp_config)

    from inspect_ai import eval_set

    from jseval.manifest import _sha256_canonical

    # Watched-roots safety gate (tempdoc 624 As-built #7 follow-up): this is the actual
    # eval-executing path, so — unlike the optional `utility-calibrate` CLI — a stray/
    # broader watched root must abort the run, not just get reported. Only meaningful
    # when a search backend is actually in play (mcp_config given); condition-A-only
    # runs never touch the backend.
    if mcp_config:
        watched_roots_base_url = base_url_from_mcp_config(mcp_config)
        if watched_roots_base_url:
            assert_watched_roots_scoped(watched_roots_base_url, corpus_dir)

    # The prompt template is identical across conditions → its hash is a cohort
    # field (so A and C share an agent_cohort_key, but a prompt change segregates).
    prompt_template_hash = _sha256_canonical(_PROMPT)
    # Stage an isolated, answer-key-free copy of corpus_dir ONCE for the whole run
    # (reused across every condition/seed/sample this call touches — the matrix is
    # resumable across process restarts, but corpus_dir isn't part of eval_set_id
    # identity below, so a fresh staged path per invocation is safe). See
    # stage_corpus_dir's docstring for why `--add-dir corpus_dir` cannot pass the
    # persistent, gold-answer-key-sibling `corpus-dir/` directly.
    staged_corpus_dir = stage_corpus_dir(corpus_dir)
    try:
        # `tasks` construction (agent_utility_task -> json.load on queries_path)
        # can raise on a bad/missing/corrupted queries file — kept inside this
        # try so a task-construction failure still hits the staged-dir cleanup
        # below, not just an eval_set failure.
        tasks = [
            agent_utility_task(
                condition=c, queries_path=queries_path, corpus_dir=staged_corpus_dir,
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
        # log_format="json": the .eval (zip) recorder breaks on Windows fsspec
        # paths during eval_set's log cleanup; JSON logs are text + portable.
        # max_tasks=1: run condition-tasks SEQUENTIALLY so the effective agent
        # concurrency equals max_samples — the concurrency the calibration pilot
        # sized the timeout at. Concurrent condition-tasks multiply the in-flight
        # `claude -p` cells (~n_conditions x max_samples), inflating contended
        # latency past the calibrated timeout (observed live 2026-07-03: 40%
        # arm-B timeout exclusions at ~16 effective concurrency vs 0% at the
        # calibrated 8-way in an otherwise-identical isolation repro).
        eval_set(tasks, log_dir=log_dir, epochs=seeds, model="mockllm/model",
                 max_samples=concurrency, max_tasks=1, log_format="json",
                 eval_set_id=eval_set_id)
    finally:
        shutil.rmtree(Path(staged_corpus_dir).parent, ignore_errors=True)
    return log_dir
