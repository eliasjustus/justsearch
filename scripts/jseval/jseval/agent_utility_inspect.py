"""Agent-utility execution THROUGH Inspect AI (tempdoc 624 execution design; v2 executor tempdoc 675).

Runs the cell matrix `{corpus × model × condition × seed × query}` as an Inspect
eval rather than a bespoke fan-out. **Executor v2 (tempdoc 675):** the cell is an
in-process Claude Agent SDK session (`ClaudeSDKClient`), not a `claude -p`
subprocess — tool calls, tool *results*, the offered MCP surface, and usage/cost
come back as objects (no stdout parsing). The matrix is ONE Inspect task whose
samples are the flat `condition × query` cross-product (**condition = a sample
field**, seed = `epochs`, query id carried in `sample.id`), run in ONE bounded
concurrency pool at `max_samples` — the per-condition-task serialization
(`max_tasks=1`) that doubled the wall-clock is gone.

Inspect's `eval_set` still gives durable resume (skip completed samples),
bounded/adaptive concurrency, and a schema-valid EvalLog. The composer
(`utility_comparison.compose_utility`) projects the per-cell results (read back
via `agent_utility_run.eval_logs_to_summaries`) into `utility-comparison.v1`,
including the `tool_call_assertions` coverage block.

Identity carried, not forked (the "one identity, three roles" principle):
- `sample.id` = `"{condition}|q{i}"`  → resume key (unique across conditions in one task),
- task-args = the cohort identity (model / cli-version / mcp-surface / judge /
  prompt / decoding) → a config change segregates logs (no stale reuse),
- `epoch` = the seed → the seed envelope.

Executed-vs-blocked (tempdoc 675 §Pre-implementation verification finding 3): the
SDK reports a *blocked* disallowed-tool attempt in the message stream too (unlike
the CLI stdout), so the leak/disallowed assertions scan only tools that ACTUALLY
executed — a tool_use with a non-error `ToolResultBlock` and not in
`ResultMessage.permission_denials`. The blocked attempts are stashed separately as
forensics. This preserves the "did a disallowed tool actually run" semantics.

A4 wrinkle (unchanged): Inspect does NOT auto-capture the cell's usage, so the
solver stashes cost / unique-tokens / turns into `state.metadata`.
"""

from __future__ import annotations

import asyncio
import json
import shutil
import tempfile
from pathlib import Path

from inspect_ai import Task, task
from inspect_ai.dataset import Sample
from inspect_ai.scorer import Score, Target, accuracy, scorer
from inspect_ai.solver import Generate, TaskState, solver

from claude_agent_sdk import (
    AssistantMessage,
    ClaudeAgentOptions,
    ClaudeSDKClient,
    ResultMessage,
    TextBlock,
    ToolResultBlock,
    ToolUseBlock,
    UserMessage,
)

from jseval.agent_retrieval_eval import (
    _score_answer,
    build_disallowed_tools,
    find_disallowed_tool_calls,
    find_leak_suspect_tool_calls,
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

# Generous default per-cell turn cap (tempdoc 675 lever 3): clips the pathological
# many-turn tail, set well ABOVE the useful range so it does not bias the measured
# tool-use. Raised 40 -> 100 (tempdoc 675 review F1): live cells were observed at
# 36-39 turns, i.e. 40 sat INSIDE the useful range and clipped valid cells. The
# calibrated wall-clock budget (`timeout_s`) remains the primary bound; this is the
# safety net. Overridable via `--max-turns`.
_DEFAULT_MAX_TURNS = 100

# Neutral prompt (tempdoc 624 §M.8 pre-registration, Step 0 item 1): the prior
# "using only the documents in {corpus_dir}" wording primed the agent toward
# filesystem tools before it ever saw its actual tool surface -- an experimental
# confound the with/without-tool comparison cannot tolerate. Pre-registered BEFORE
# the next paid run; must not be edited post-hoc without a new pre-registration.
_PROMPT = (
    "Answer the following question about the document collection at {corpus_dir}. "
    "You may use any tools available to you. "
    "Do not use prior knowledge. Be concise. Question: {query}"
)


def _mcp_servers_from_config(mcp_config: str | None) -> dict:
    """The Agent SDK `mcp_servers` dict for a with-tool cell.

    The harness passes `mcp_config` as a `--mcp-config`-style FILE path whose body
    is `{"mcpServers": {"justsearch": {"type":"http","url": ...}}}`; the SDK wants
    the inner `mcpServers` dict directly. Empty for condition A (no config)."""
    if not mcp_config:
        return {}
    cfg = json.loads(Path(mcp_config).read_text(encoding="utf-8"))
    return cfg.get("mcpServers") or {}


async def _mcp_surface(client: ClaudeSDKClient) -> tuple[list | None, list[str]]:
    """Return `(mcp_servers, justsearch_tool_names)` from the SDK's own MCP status.

    Replaces the CLI init-event parse (tempdoc 675 finding 2: `query()`'s
    `SystemMessage` init does not list the offered tools). Defensive across the
    `McpStatusResponse` shape: returns `(None, [])` if status is unavailable so the
    caller can flag "unverified" rather than conflate unknown with healthy."""
    try:
        status = await client.get_mcp_status()
    except Exception:
        return None, []
    if not isinstance(status, dict):
        return None, []
    servers = status.get("servers") or status.get("mcp_servers") or status.get("mcpServers")
    if servers is None:
        return None, []
    js_tools: list[str] = []
    for srv in servers:
        if srv.get("name") != "justsearch":
            continue
        for t in (srv.get("tools") or []):
            tn = t.get("name") if isinstance(t, dict) else str(t)
            js_tools.append(tn if str(tn).startswith("mcp__") else f"mcp__justsearch__{tn}")
    return servers, js_tools


@solver
def claude_agent_solver(corpus_dir: str, mcp_config: str | None = None,
                        model: str = "haiku", max_budget: str = "0.50",
                        timeout_s: int = 180, max_turns: int = _DEFAULT_MAX_TURNS):
    """Per-sample solver: run one query as an in-process Claude Agent SDK cell.

    `condition` is read from `state.metadata["condition"]` (the single-pool sample
    field), NOT a closure arg — so ONE solver serves all conditions in one task.
    The whole cell (including the disclosed transient retry) runs under a single
    `asyncio.wait_for(timeout_s)` wall-clock budget, so a retry can never hold a
    concurrency slot for 2× the budget (tempdoc 675 per-cell budget). Stashes cost /
    unique-tokens / turns in `state.metadata` (A4). An errored cell sets
    `metadata.error` so the projection excludes it (valid-only parity).

    `max_budget` is a STR by design (tempdoc 675 F0 resume fix): it is threaded
    through the Inspect task/solver IDENTITY, and a *float* there breaks `eval_set`
    resume — the JSON recorder reads persisted floats back as `Decimal` (ijson
    without `use_float`), so `task_identifier` re-hashes the resumed task to a
    different id than the persisted log and raises PrerequisiteError. A str
    round-trips identically. Converted to float only at the point of use below. Do
    NOT reintroduce any float into the task/solver arg surface."""
    mcp_servers = _mcp_servers_from_config(mcp_config)

    def _fresh_capture() -> dict:
        return {"attempts": {}, "results": {}, "texts": [], "rmsg": None,
                "mcp_servers": None, "justsearch_tools": []}

    async def _one_attempt(condition: str, prompt: str, disallowed: list[str], capture: dict) -> None:
        """Run one SDK session, writing captured objects INCREMENTALLY into the shared
        `capture` dict so partial evidence survives an `asyncio.wait_for` cancellation
        or exception (tempdoc 675 F2 — 'timed-out cells lose their partial evidence'
        was the failure mode v2 must fix; capture is owned by the caller, so a
        cancelled coroutine's partial writes are not lost)."""
        query_cwd = tempfile.mkdtemp(prefix="jseval-cell-")  # isolate from any ambient CLAUDE.md
        opts = ClaudeAgentOptions(
            model=model,
            permission_mode="bypassPermissions",
            max_turns=max_turns,
            max_budget_usd=float(max_budget),
            cwd=query_cwd,
            add_dirs=[corpus_dir],
            setting_sources=None,  # tempdoc 675 finding: zero ambient-context leakage
            disallowed_tools=disallowed,
            mcp_servers=(mcp_servers if condition in _WITH_TOOL else {}),
            strict_mcp_config=True,
        )
        try:
            async with ClaudeSDKClient(options=opts) as client:
                await client.query(prompt)
                async for msg in client.receive_response():
                    if isinstance(msg, AssistantMessage):
                        for b in (msg.content or []):
                            if isinstance(b, ToolUseBlock):
                                capture["attempts"][b.id] = {"tool": b.name, "input": b.input}
                            elif isinstance(b, TextBlock):
                                capture["texts"].append(b.text)
                    elif isinstance(msg, UserMessage):
                        for b in (getattr(msg, "content", None) or []):
                            if isinstance(b, ToolResultBlock):
                                capture["results"][b.tool_use_id] = {"is_error": bool(b.is_error)}
                    elif isinstance(msg, ResultMessage):
                        capture["rmsg"] = msg
                capture["mcp_servers"], capture["justsearch_tools"] = await _mcp_surface(client)
        finally:
            shutil.rmtree(query_cwd, ignore_errors=True)

    async def solve(state: TaskState, generate: Generate) -> TaskState:
        condition = state.metadata.get("condition")
        disallowed = build_disallowed_tools(condition)
        prompt = _PROMPT.format(corpus_dir=corpus_dir, query=state.input_text)
        # Bounded 2-attempt DISCLOSED retry for transient infra failures, all under
        # ONE shared wall-clock budget (tempdoc 675). `attempts`/`first_error` make
        # the retry visible per cell; a cell failing both is excluded via `error`.
        first_error: str | None = None
        attempt = 0
        capture: dict | None = None
        deadline = asyncio.get_event_loop().time() + timeout_s
        for attempt in (1, 2):
            remaining = deadline - asyncio.get_event_loop().time()
            if remaining <= 0:
                state.metadata.setdefault("error", "per-cell wall-clock budget exhausted")
                break
            capture = _fresh_capture()
            try:
                await asyncio.wait_for(
                    _one_attempt(condition, prompt, disallowed, capture), timeout=remaining)
            except asyncio.TimeoutError:
                state.metadata["error"] = "per-cell wall-clock budget exhausted"
                break  # `capture` holds whatever arrived before the timeout (F2)
            except Exception as e:  # noqa: BLE001 — max_turns/budget/SDK/connection failure
                err = f"{type(e).__name__}: {str(e)[:250]}"
                if attempt == 1:
                    first_error = err
                    continue
                state.metadata["error"] = err
                break  # `capture` holds this attempt's partial evidence (F2)
            break  # success
        # ALWAYS project what was captured (partial or complete) so a timed-out /
        # errored cell still records what it ran (tempdoc 675 F2 forensic completeness).
        # `_record_cell` stashes tool_calls unconditionally and uses setdefault for its
        # own errors, so it never clobbers an error already set above.
        if capture is not None:
            try:
                _record_cell(state, capture, condition, disallowed, mcp_config)
            except Exception as e:  # noqa: BLE001 — a projection bug must never fabricate a cell
                state.metadata.setdefault(
                    "error", f"record_cell failed: {type(e).__name__}: {str(e)[:200]}")
        state.metadata["attempts"] = attempt
        if first_error is not None:
            state.metadata["first_error"] = first_error
        return state

    return solve


def _record_cell(state: TaskState, got: dict, condition: str,
                 disallowed: list[str], mcp_config: str | None) -> None:
    """Project the captured SDK objects into `state.metadata` (the composer contract).

    `tool_calls` (composer-facing) = tools that ACTUALLY EXECUTED — a tool_use with a
    non-error result and not in `permission_denials`. Blocked attempts are recorded
    separately as forensics. Stashed UNCONDITIONALLY (before the surface assertion)
    so a cell erroring on the assertion still carries what it ran (credibility bar:
    "every cell actually run")."""
    attempts, results = got["attempts"], got["results"]
    rmsg: ResultMessage | None = got["rmsg"]
    # `ResultMessage.permission_denials` is a raw JSON pass-through — dicts on the
    # real SDK (`{"tool_name", "tool_use_id", "tool_input"}`), possibly bare strings
    # on other builds. Extract the tool name robustly and NEVER `set()` a list of
    # dicts (unhashable -> a TypeError in the measurement path). Empirically this is
    # usually empty under bypassPermissions (a disallowed tool is removed from the
    # toolset, so it never appears as an attempt); the executed-vs-blocked signal
    # below rests on the tool_result, this is a belt-and-suspenders cross-check.
    denied: set = set()
    for _d in (getattr(rmsg, "permission_denials", None) or []):
        _n = (_d.get("tool_name") or _d.get("tool") or _d.get("name")) if isinstance(_d, dict) else _d
        if _n:
            denied.add(_n)

    def _blocked(tid: str, entry: dict) -> bool:
        r = results.get(tid)
        return (r is None) or r["is_error"] or (entry["tool"] in denied)

    executed = [e for tid, e in attempts.items() if not _blocked(tid, e)]
    blocked = [e for tid, e in attempts.items() if _blocked(tid, e)]
    state.metadata["tool_calls"] = executed
    state.metadata["tool_calls_blocked"] = blocked  # tempdoc 675 forensic bonus (objects)
    state.metadata["disallowed_tool_calls"] = find_disallowed_tool_calls(executed, disallowed)
    state.metadata["leak_suspect_tool_calls"] = find_leak_suspect_tool_calls(executed)

    # Offered MCP surface from the SDK's own status (tempdoc 675 finding 2).
    servers = got["mcp_servers"]
    justsearch_tools = got["justsearch_tools"]
    state.metadata["mcp_servers"] = servers
    state.metadata["mcp_tools_offered"] = (
        len(justsearch_tools) if servers is not None else None)
    justsearch_connected = bool(servers) and any(
        (s.get("name") == "justsearch"
         and str(s.get("status", "")).lower() in ("connected", "ready", "ok"))
        for s in (servers or []))
    state.metadata["mcp_tools_deferred"] = (
        bool(justsearch_connected and not justsearch_tools) if servers is not None else None)

    # With-tool surface assertion (condition A exempt by construction). Preserve the
    # tri-state: status unavailable -> "unverified" (never conflated with healthy).
    if condition in _WITH_TOOL and mcp_config:
        if servers is None:
            state.metadata["mcp_surface_unverified"] = True
        elif not justsearch_tools:
            state.metadata.setdefault(
                "error", f"expected MCP tool surface not offered: mcp_servers={servers}")
            return

    if rmsg is not None:
        if getattr(rmsg, "is_error", False):
            # Forensically complete (tempdoc 675): a bare "result error: None" is
            # unactionable — carry the SDK's own error signals (subtype/stop_reason
            # distinguish e.g. a max-turns/​budget exhaustion from an API error).
            err_bits = {
                "subtype": getattr(rmsg, "subtype", None),
                "stop_reason": getattr(rmsg, "stop_reason", None),
                "num_turns": getattr(rmsg, "num_turns", None),
                "api_error_status": getattr(rmsg, "api_error_status", None),
                "errors": getattr(rmsg, "errors", None),
            }
            state.metadata.setdefault(
                "error", (f"result error: {str(getattr(rmsg, 'result', ''))[:200]} | {err_bits}")[:600])
            return
        state.output.completion = getattr(rmsg, "result", "") or ""
        usage = getattr(rmsg, "usage", None) or {}
        state.metadata.update({
            "cost_usd": getattr(rmsg, "total_cost_usd", None),
            "unique_tokens": usage.get("cache_creation_input_tokens"),
            "num_turns": getattr(rmsg, "num_turns", None),
        })
    else:
        state.metadata.setdefault(
            "error", "no ResultMessage (stream ended without a terminal result)")


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
def agent_utility_task(conditions=("A", "C"), queries_path: str = "", corpus_dir: str = "",
                       mcp_config: str | None = None, model: str = "haiku",
                       max_queries: int | None = None, max_budget: str = "0.50",
                       timeout_s: int = 180, max_turns: int = _DEFAULT_MAX_TURNS,
                       # --- cohort identity (task-args → config-change log segregation, A2) ---
                       cli_version: str | None = None, mcp_tool_surface_hash: str | None = None,
                       judge_kind: str = "substring-em", prompt_template_hash: str | None = None,
                       corpus_dataset: str = "", corpus_signature: str = "") -> Task:
    """ONE Inspect task over the whole matrix (tempdoc 675 single pool).

    Samples are the flat `condition × query` cross-product; `condition` is a sample
    field (`Sample.metadata`), `sample.id = "{c}|q{i}"` (unique across conditions so
    Inspect resume stays keyed on the cell). The cohort-identity args are what
    `eval_set` segregates logs by."""
    rows = json.load(open(queries_path, encoding="utf-8"))
    if max_queries:
        rows = rows[:max_queries]
    samples = [
        Sample(id=f"{c}|q{i}", input=r["query"], target=r["answer"],
               metadata={"condition": c, "question_type": r.get("question_type")})
        for c in conditions
        for i, r in enumerate(rows)
    ]
    return Task(
        dataset=samples,
        solver=claude_agent_solver(corpus_dir, mcp_config, model, max_budget, timeout_s, max_turns),
        scorer=substring_scorer(),
        metadata={
            "model": model,
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
                     max_turns: int = _DEFAULT_MAX_TURNS,
                     cli_version: str | None = None,
                     mcp_tool_surface_hash: str | None = None,
                     corpus_dataset: str = "", corpus_signature: str = "") -> str:
    """Run the matrix through Inspect `eval_set` (resumable). seeds → `epochs`.

    Returns the log_dir; re-invoking with the same log_dir resumes (skips done
    samples). condition A uses no MCP; B/C use the JustSearch one. Executor v2
    (tempdoc 675): ONE task, one concurrency pool at `max_samples` (no `max_tasks=1`
    condition serialization)."""
    # Dead-config fail-fast (tempdoc 624 battlefield retrospective): a `url`-only
    # `mcpServers` entry (no `"type":"http"`) is silently DROPPED — the run would
    # otherwise complete "successfully" with zero MCP tool calls per cell. Checked
    # before any staging so a dead config aborts early.
    if mcp_config:
        assert_mcp_config_http_typed(mcp_config)

    from inspect_ai import eval_set

    from jseval.manifest import _sha256_canonical

    # Watched-roots safety gate (tempdoc 624 As-built #7 follow-up): the eval-executing
    # path aborts on a stray/broader watched root. Only when a backend is in play.
    if mcp_config:
        watched_roots_base_url = base_url_from_mcp_config(mcp_config)
        if watched_roots_base_url:
            assert_watched_roots_scoped(watched_roots_base_url, corpus_dir)

    # The prompt template is identical across conditions → its hash is a cohort field.
    prompt_template_hash = _sha256_canonical(_PROMPT)
    # Pin a DETERMINISTIC eval_set_id (default is random per-process): without it,
    # re-invoking fails with "log file not associated with a task". It ALSO keys the
    # stable staged-corpus path below — tempdoc 675 F0 resume fix: a random `mkdtemp`
    # staged path is part of Inspect's task identity, so a resume must re-stage to the
    # SAME path (and see claude_agent_solver re: the `max_budget` str — the other half
    # of the resume fix).
    eval_set_id = _sha256_canonical({
        "log_dir": log_dir, "conditions": sorted(conditions), "model": model,
        "queries": queries_path, "prompt": prompt_template_hash,
    })[:22]
    # Stage an isolated, answer-key-free copy of corpus_dir ONCE for the whole run, at a
    # DETERMINISTIC path (keyed by eval_set_id) so a resume re-stages to the same path
    # and the task identity matches the persisted log.
    staged_corpus_dir = stage_corpus_dir(corpus_dir, stable_key=eval_set_id)
    try:
        # ONE task over the full condition × query matrix (tempdoc 675 single pool).
        tasks = [
            agent_utility_task(
                conditions=conditions, queries_path=queries_path, corpus_dir=staged_corpus_dir,
                mcp_config=mcp_config, model=model,
                max_queries=max_queries, max_budget=str(max_budget), timeout_s=timeout_s,
                max_turns=max_turns, cli_version=cli_version,
                mcp_tool_surface_hash=mcp_tool_surface_hash, judge_kind="substring-em",
                prompt_template_hash=prompt_template_hash, corpus_dataset=corpus_dataset,
                corpus_signature=corpus_signature,
            )
        ]
        # log_format="json": the .eval (zip) recorder breaks on Windows fsspec paths
        # during eval_set's log cleanup; JSON logs are text + portable.
        # ONE task → conditions interleave in ONE `max_samples` pool (tempdoc 675):
        # the `max_tasks=1` per-condition serialization (a 2× wall-clock cost) is gone,
        # and the arms are contemporaneous (temporal-confound fix).
        eval_set(tasks, log_dir=log_dir, epochs=seeds, model="mockllm/model",
                 max_samples=concurrency, log_format="json",
                 eval_set_id=eval_set_id)
    finally:
        shutil.rmtree(Path(staged_corpus_dir).parent, ignore_errors=True)
    return log_dir
