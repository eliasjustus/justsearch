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
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from urllib.request import Request, urlopen

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
from jseval.agent_utility_observations import WITH_TOOL_CONDITIONS
from jseval.utility_governance import RESOURCE_EXHAUSTION, classify_error_kind
from jseval.utility_calibrate import (
    assert_mcp_config_http_typed,
    assert_watched_roots_scoped,
    base_url_from_mcp_config,
)

# Condition semantics (tempdoc 346): A = file tools only (baseline),
# B = file + JustSearch, C = JustSearch only (substitution).
_WITH_TOOL = WITH_TOOL_CONDITIONS

# MCP surface-capture retry (tempdoc 755 Track 1 item 1). `get_mcp_status()` returns
# nothing for ~8%/cell (a transient flake, tempdocs 675/725: the 2026-07-18 confirmatory
# campaign saw 4-12 unverified B-cells per 60-cell stratum). The call is read-only, so a
# bounded reprobe with a short backoff is cheap against a ~195s cell. Up to
# `_MCP_SURFACE_PROBE_ATTEMPTS` total probes (1 initial + retries) for a WITH-TOOL cell whose
# justsearch surface is still empty; a condition-A cell legitimately reports `servers==[]`
# and is probed exactly once (never retried into a false surface).
_MCP_SURFACE_PROBE_ATTEMPTS = 3
_MCP_SURFACE_RETRY_BACKOFF_S = 1.0

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


def _capture_canonical_mcp_surface(mcp_config: str) -> list[dict]:
    """Fetch the authoritative full MCP ``tools/list`` payload from the run endpoint."""
    servers = _mcp_servers_from_config(mcp_config)
    server = servers.get("justsearch") or {}
    url = server.get("url")
    if not url:
        raise ValueError("justsearch MCP config is missing its HTTP url")
    tools: list[dict] = []
    cursor = None
    while True:
        params = {"cursor": cursor} if cursor is not None else {}
        body = json.dumps({
            "jsonrpc": "2.0", "id": "jseval-source-identity",
            "method": "tools/list", "params": params,
        }).encode("utf-8")
        request = Request(
            url,
            data=body,
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json, text/event-stream",
                "User-Agent": "jseval-agent-utility-source-identity/1",
            },
            method="POST",
        )
        with urlopen(request, timeout=30) as response:  # noqa: S310 - configured MCP URL
            payload = json.loads(response.read().decode("utf-8"))
        if payload.get("error"):
            raise RuntimeError(f"MCP tools/list failed: {payload['error']}")
        result = payload.get("result") or {}
        page = result.get("tools")
        if not isinstance(page, list):
            raise ValueError("MCP tools/list returned a malformed tools page")
        tools.extend(page)
        cursor = result.get("nextCursor")
        if not cursor:
            break
    if not tools:
        raise ValueError("MCP tools/list returned no tools")
    canonical = []
    for tool in tools:
        if not isinstance(tool, dict) or not tool.get("name"):
            raise ValueError("MCP tools/list contains a malformed tool definition")
        raw_name = str(tool["name"])
        item = dict(tool)
        item["name"] = (
            raw_name if raw_name.startswith("mcp__") else f"mcp__justsearch__{raw_name}"
        )
        if "input_schema" in item and "inputSchema" not in item:
            item["inputSchema"] = item.pop("input_schema")
        canonical.append(item)
    canonical.sort(key=lambda item: item["name"])
    if len({item["name"] for item in canonical}) != len(canonical):
        raise ValueError("MCP tools/list contains duplicate tool names")
    return canonical


def _capture_mcp_initialize_identity(mcp_config: str) -> dict:
    """Fetch the authoritative MCP ``initialize`` response for source-time cohort
    identity (tempdoc 725 increment 2): the server's advertised ``instructions``
    (verbatim + hashed), ``serverInfo.version``, and the negotiated
    ``protocolVersion``. Nothing captures the ``initialize`` response anywhere
    today (`_capture_canonical_mcp_surface` only speaks ``tools/list``); this is
    that missing capture, at the same source-time point.

    Fail-closed like `_capture_canonical_mcp_surface`: a JSON-RPC/HTTP/transport
    failure RAISES -- it never returns a silently-empty identity block, which
    would let a run proceed with an unverified 'None' identity that reads as
    healthy. A missing (optional, per the MCP spec) ``instructions`` field is NOT
    a failure -- it lawfully hashes to `None`.
    """
    servers = _mcp_servers_from_config(mcp_config)
    server = servers.get("justsearch") or {}
    url = server.get("url")
    if not url:
        raise ValueError("justsearch MCP config is missing its HTTP url")
    body = json.dumps({
        "jsonrpc": "2.0", "id": "jseval-source-identity-initialize",
        "method": "initialize",
        "params": {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "jseval-agent-utility-source-identity", "version": "1"},
        },
    }).encode("utf-8")
    request = Request(
        url,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "User-Agent": "jseval-agent-utility-source-identity/1",
        },
        method="POST",
    )
    with urlopen(request, timeout=30) as response:  # noqa: S310 - configured MCP URL
        payload = json.loads(response.read().decode("utf-8"))
    if payload.get("error"):
        raise RuntimeError(f"MCP initialize failed: {payload['error']}")
    result = payload.get("result")
    if not isinstance(result, dict):
        raise ValueError("MCP initialize returned a malformed result")
    instructions = result.get("instructions")
    instructions = instructions if isinstance(instructions, str) else None
    server_info = result.get("serverInfo") or {}
    return {
        "instructions": instructions,
        "instructions_sha256": (
            hashlib.sha256(instructions.encode("utf-8")).hexdigest()
            if instructions is not None else None
        ),
        "server_version": server_info.get("version") if isinstance(server_info, dict) else None,
        "protocol_version": result.get("protocolVersion"),
    }


def _derive_exposure_mode(*, enable_tool_search: str | None, always_load: bool | None) -> str:
    """Config-only exposure-mode derivation (tempdoc 725 derisk): NEVER inferred
    from the SDK's init/`mcp_status` response -- purely a function of the
    ``ENABLE_TOOL_SEARCH`` harness env var and the justsearch MCP server entry's
    ``alwaysLoad`` flag.

    - ``always_load is True`` OR ``enable_tool_search == "false"`` -> ``"eager"``
      (the tool surface is exposed directly, ToolSearch gating is off).
    - no ``always_load`` and ``enable_tool_search`` in ``(None, "", "true")`` ->
      ``"deferred"`` (tools are reachable only via ToolSearch).
    - anything else (e.g. ``"auto"``) -> ``"unknown"``.
    """
    normalized_search = enable_tool_search if enable_tool_search is None else str(enable_tool_search)
    if always_load is True or normalized_search == "false":
        return "eager"
    if not always_load and normalized_search in (None, "", "true"):
        return "deferred"
    return "unknown"


def _capture_exposure_config(
    mcp_config: str | None,
    *,
    enable_tool_search: str | None = None,
    always_load: bool | None = None,
) -> dict | None:
    """Cohort-level ``exposure_config`` block (tempdoc 725 increment 2): how the
    justsearch MCP tool surface was made available to the agent this campaign --
    directly offered ("eager") vs. reachable only via ToolSearch ("deferred").

    ``enable_tool_search``/``always_load`` are explicit parameters (not hidden
    env/config reads) so a later agent can thread per-campaign values; they
    default to the harness process's own ``ENABLE_TOOL_SEARCH`` env var and the
    ``mcp_config``'s ``justsearch`` server entry's ``alwaysLoad`` key,
    respectively. Returns ``None`` when there is no with-tool arm at all
    (``mcp_config`` falsy) -- mirrors the empty ``mcp_tool_surface`` convention.
    """
    if not mcp_config:
        return None
    if enable_tool_search is None:
        enable_tool_search = os.environ.get("ENABLE_TOOL_SEARCH")
    if always_load is None:
        servers = _mcp_servers_from_config(mcp_config)
        server = servers.get("justsearch") or {}
        raw_always_load = server.get("alwaysLoad")
        always_load = bool(raw_always_load) if raw_always_load is not None else None
    return {
        "enable_tool_search": enable_tool_search,
        "always_load": always_load,
        "exposure_mode": _derive_exposure_mode(
            enable_tool_search=enable_tool_search, always_load=always_load),
    }


async def _mcp_surface(
    client: ClaudeSDKClient, with_tool: bool
) -> tuple[list | None, list[str], list[dict], str | None]:
    """Return server status, offered names, canonical full tool definitions, AND how the
    surface was obtained (`surface_evidence`).

    Replaces the CLI init-event parse (tempdoc 675 finding 2: `query()`'s
    `SystemMessage` init does not list the offered tools). Defensive across the
    `McpStatusResponse` shape: returns `(None, [], [], None)` if status is unavailable so
    the caller can flag "unverified" rather than conflate unknown with healthy.

    Tri-state on `servers` (tempdoc 725 A/B smoke fix 2): `[]` (known-empty --
    status WAS available and reported zero servers) and `None` (unknown -- status
    unavailable) are distinct and must be captured deterministically. The prior
    `status.get("servers") or status.get("mcp_servers") or status.get("mcpServers")`
    `or`-chain returns the LAST operand when all are falsy, so an empty list under
    the FIRST present key silently collapsed to the NEXT key's value (or None) --
    inconsistent depending on which key the SDK happened to populate. First
    NON-NULL key lookup fixes this: the first key holding a non-None value wins,
    empty list and all; an explicit `null` is treated as absent (typed serializers
    commonly emit every alternative key with null padding for the unused ones).

    `surface_evidence` (tempdoc 755 Track 1): "status" when the FIRST probe reported the
    justsearch surface, "status-retry" when a bounded reprobe recovered it (the ~8%/cell
    `get_mcp_status()` flake is transient -- 675/725 -- and a read-only reprobe is cheap vs
    a ~195s cell), None when no probe ever reported it (the caller flags the cell unverified;
    NEVER a fabricated hash). Retry fires ONLY for a with-tool cell whose justsearch surface
    is still empty; a condition-A cell (`with_tool == False`) legitimately reports
    `servers==[]` and is probed once, never retried into a false surface."""

    async def _probe_once() -> tuple[list | None, list[str], list[dict]]:
        try:
            status = await client.get_mcp_status()
        except Exception:
            return None, [], []
        if not isinstance(status, dict):
            return None, [], []
        servers = None
        for key in ("servers", "mcp_servers", "mcpServers"):
            value = status.get(key)
            if value is not None:
                servers = value
                break
        if servers is None:
            return None, [], []
        js_tools: list[str] = []
        js_surface: list[dict] = []
        for srv in servers:
            if srv.get("name") != "justsearch":
                continue
            for t in (srv.get("tools") or []):
                tn = t.get("name") if isinstance(t, dict) else str(t)
                normalized_name = tn if str(tn).startswith("mcp__") else f"mcp__justsearch__{tn}"
                js_tools.append(normalized_name)
                if isinstance(t, dict):
                    js_surface.append({
                        "name": normalized_name,
                        "description": t.get("description"),
                        "input_schema": t.get("inputSchema", t.get("input_schema")),
                    })
                else:
                    js_surface.append({
                        "name": normalized_name,
                        "description": None,
                        "input_schema": None,
                    })
        return servers, js_tools, sorted(js_surface, key=lambda item: item["name"])

    servers: list | None = None
    js_tools: list[str] = []
    js_surface: list[dict] = []
    for attempt in range(_MCP_SURFACE_PROBE_ATTEMPTS):
        if attempt > 0:
            await asyncio.sleep(_MCP_SURFACE_RETRY_BACKOFF_S)
        servers, js_tools, js_surface = await _probe_once()
        if js_tools:
            return servers, js_tools, js_surface, ("status" if attempt == 0 else "status-retry")
        if not with_tool:
            break  # condition-A: `servers==[]` is legitimate; never retry into a false surface
    return servers, js_tools, js_surface, None


@solver
def claude_agent_solver(corpus_dir: str, mcp_config: str | None = None,
                        model: str = "haiku", max_budget: str = "0.50",
                        timeout_s: int = 180, max_turns: int = _DEFAULT_MAX_TURNS,
                        mcp_tool_surface_json: str = "[]", agent_env_json: str = "{}",
                        timeout_s_by_condition_json: str = "{}"):
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
    NOT reintroduce any float into the task/solver arg surface.

    `agent_env_json` (tempdoc 725 increment 4 — exposure A/B wiring): a JSON-encoded
    ``{str: str}`` env-var overlay threaded into every cell's `ClaudeAgentOptions.env`
    (e.g. ``{"ENABLE_TOOL_SEARCH": "false"}`` for the eager arm). A STR for the same
    resume reason as `max_budget` above — a dict is fine (no floats), but keeping every
    solver identity arg JSON-string-shaped is the established pattern here. Empty
    (``"{}"``, the default) round-trips to `env={}`, which the SDK's
    `default_factory=dict` makes byte-identical to omitting `env` entirely — today's
    behavior is preserved when no `--agent-env` is passed.

    `timeout_s_by_condition_json` (tempdoc 624 §Harness lessons -- per-arm timeout
    calibration): a JSON-encoded ``{condition: int}`` map of per-condition wall-clock
    budgets. A cell resolves its budget by its own condition, falling back to the scalar
    `timeout_s` for any condition absent from the map. A STR for the same `eval_set` resume
    reason as `max_budget`/`agent_env_json` above (its int values also keep a float out of
    the task-identity args -- `_assert_no_float_task_args`). Empty (``"{}"``, the default)
    means every cell uses the scalar `timeout_s` -- today's behavior byte-for-byte when no
    per-condition calibration is supplied."""
    mcp_servers = _mcp_servers_from_config(mcp_config)
    agent_env = json.loads(agent_env_json) if agent_env_json else {}
    timeout_s_by_condition = json.loads(timeout_s_by_condition_json) if timeout_s_by_condition_json else {}
    declared_mcp_tool_surface = json.loads(mcp_tool_surface_json)
    from jseval.agent_manifest import mcp_tool_surface_hash
    declared_mcp_tool_surface_hash = (
        mcp_tool_surface_hash(declared_mcp_tool_surface)
        if declared_mcp_tool_surface else None
    )

    def _fresh_capture() -> dict:
        return {"attempts": {}, "results": {}, "texts": [], "rmsg": None,
                "mcp_servers": None, "justsearch_tools": [],
                "justsearch_tool_surface": [], "surface_evidence": None,
                "resolved_models": set(), "usage_accum": {}}

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
            env=agent_env,
        )
        try:
            async with ClaudeSDKClient(options=opts) as client:
                await client.query(prompt)
                async for msg in client.receive_response():
                    if isinstance(msg, AssistantMessage):
                        if getattr(msg, "model", None):
                            capture["resolved_models"].add(msg.model)
                        # tempdoc 757: accumulate a partial token LOWER BOUND from the
                        # streamed per-message usage so a cell KILLED before its terminal
                        # ResultMessage (wall-clock cancel) still carries some usage. Use
                        # per-field MAX, not SUM: MAX is a valid lower bound whether the
                        # SDK's `usage` is per-turn (max_turn <= sum_turns = true) or
                        # cumulative (max = last = true); SUM would over-count under
                        # cumulative semantics -> over-state cost -> anti-conservative.
                        _u = getattr(msg, "usage", None)
                        if isinstance(_u, dict):
                            _acc = capture["usage_accum"]
                            for _k, _v in _u.items():
                                if isinstance(_v, (int, float)) and not isinstance(_v, bool):
                                    _acc[_k] = max(_acc.get(_k, 0), _v)
                        for b in (msg.content or []):
                            if isinstance(b, ToolUseBlock):
                                capture["attempts"][b.id] = {"tool": b.name, "input": b.input}
                            elif isinstance(b, TextBlock):
                                capture["texts"].append(b.text)
                    elif isinstance(msg, UserMessage):
                        for b in (getattr(msg, "content", None) or []):
                            if isinstance(b, ToolResultBlock):
                                # tempdoc 736 D9 (issue 9): stash raw content alongside
                                # is_error. `b.content` is `str | list[dict] | None`
                                # (SDK-confirmed). This is the EPHEMERAL tier only --
                                # it lives in `capture`/`state.metadata` and never
                                # reaches the committed record directly; `_record_cell`
                                # derives a redacting digest (hash/len/shape/flags,
                                # never raw text) from it for the committed tier.
                                capture["results"][b.tool_use_id] = {
                                    "is_error": bool(b.is_error),
                                    "content": b.content,
                                }
                    elif isinstance(msg, ResultMessage):
                        capture["rmsg"] = msg
                (capture["mcp_servers"], capture["justsearch_tools"],
                 capture["justsearch_tool_surface"],
                 capture["surface_evidence"]) = await _mcp_surface(
                    client, condition in _WITH_TOOL)
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
        # Per-arm budget: resolve this cell's wall-clock timeout by its condition, falling
        # back to the scalar `timeout_s` (tempdoc 624 §Harness lessons — a pooled timeout
        # under-budgets the long-tail A arm on large corpora).
        cell_timeout_s = timeout_s_by_condition.get(condition, timeout_s)
        deadline = asyncio.get_event_loop().time() + cell_timeout_s
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
                _record_cell(
                    state, capture, condition, disallowed, mcp_config,
                    declared_mcp_tool_surface_hash,
                    declared_mcp_tool_surface,
                )
            except Exception as e:  # noqa: BLE001 — a projection bug must never fabricate a cell
                state.metadata.setdefault(
                    "error", f"record_cell failed: {type(e).__name__}: {str(e)[:200]}")
        state.metadata["attempts"] = attempt
        if first_error is not None:
            state.metadata["first_error"] = first_error
        # tempdoc 757: flag a resource-exhausted cell whose cost/tokens survived as a
        # partial LOWER BOUND (usd-budget: cost + tokens from the is_error ResultMessage;
        # wall-clock: tokens from the streamed usage accumulation). Stamped here, after
        # `_record_cell` (whose is_error path early-returns), so BOTH exhaustion shapes are
        # covered from one site. The composer treats a lower bound as exact only in the
        # conservative baseline-arm direction; a `None`-usage residual `other` error stays
        # unflagged. `working_time`/`total_time` are read off the sample, not here.
        _final_error = state.metadata.get("error")
        if (
            _final_error is not None
            and classify_error_kind(_final_error) == RESOURCE_EXHAUSTION
            and (state.metadata.get("cost_usd") is not None
                 or state.metadata.get("unique_tokens") is not None)
        ):
            state.metadata["usage_truncated"] = True
        return state

    return solve


_TOOL_SEARCH_TOOL_NAME = "ToolSearch"
_MCP_JUSTSEARCH_PREFIX = "mcp__justsearch"
_MCP_JUSTSEARCH_NAME_RE = re.compile(r"^mcp__justsearch__[A-Za-z0-9_]+$")


def _toolsearch_targets(attempts: dict) -> list[str]:
    """Ordered, deduplicated ``mcp__justsearch__*`` tool names referenced by a
    ``select:...`` ToolSearch call input in this cell (tempdoc 725 increment 3).

    ONLY the resolved tool names named after a ``select:`` prefix are extracted
    -- a keyword/free-text ToolSearch query (e.g. ``"notification jira slack"``)
    is a search string, not a resolved-tool declaration, and must NEVER leak
    into this field (the redaction contract this field exists under: raw query
    text is never public evidence).

    A comma-segment is captured ONLY if it FULLMATCHES the tool-name grammar
    ``^mcp__justsearch__[A-Za-z0-9_]+$`` -- a prefix check alone (tempdoc 725
    review finding #1) lets a segment like
    ``mcp__justsearch__search /etc/passwd bob@evil.com`` (a space-joined
    ``select:`` list whose first token happens to start with the prefix) leak
    the trailing free text verbatim into durable sanitized evidence. A segment
    that fails the fullmatch is dropped entirely; it is simply not evidence
    that a well-formed tool name was referenced there."""
    seen: list[str] = []
    for entry in attempts.values():
        if entry.get("tool") != _TOOL_SEARCH_TOOL_NAME:
            continue
        query = (entry.get("input") or {}).get("query")
        if not isinstance(query, str) or not query.startswith("select:"):
            continue
        for raw_name in query[len("select:"):].split(","):
            name = raw_name.strip()
            if _MCP_JUSTSEARCH_NAME_RE.fullmatch(name) and name not in seen:
                seen.append(name)
    return seen


# tempdoc 736 D9/U2: product-emitted CONSTANT text prefixes (never corpus text) --
# detecting their presence in tool-result content is leak-safe by construction.
# Verbatim grep sources (2026-07-14):
#   modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java:523
#     sb.append("Evidence pack: ")...                          -> evidence_pack
#   modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java:725
#     sb.append("    Matched: ").append(quoted);                -> rationale
#   modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java:549
#     sb.append("\n\n--- Quality ---\n");                        -> coverage
#   modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java:878
#     sb.append("\nNote: semantic ranking degraded (")...        -> degradation
_FURNITURE_MARKERS: dict[str, str] = {
    "rationale": "Matched: ",
    "evidence_pack": "Evidence pack: ",
    "coverage": "--- Quality ---",
    "degradation": "semantic ranking degraded (",
}


def _content_text(content) -> str:
    """Concatenated text view of a `ToolResultBlock.content` payload, used ONLY for
    leak-safe furniture-marker detection -- never stored or returned verbatim."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for block in content:
            if isinstance(block, dict):
                text = block.get("text")
                if isinstance(text, str):
                    parts.append(text)
        return "\n".join(parts)
    return ""


def _content_shape(content) -> str:
    """Coarse structural tag derived from type/first-block -- NEVER the text."""
    if content is None:
        return "empty"
    if isinstance(content, str):
        return "empty" if content == "" else "text"
    if isinstance(content, list):
        if not content:
            return "empty"
        first = content[0]
        if isinstance(first, dict) and first.get("type") == "json":
            return "json"
        return "blocks"
    return "json"


def _content_len(content) -> int | None:
    if content is None:
        return None
    if isinstance(content, (str, list)):
        return len(content)
    return None


def _content_sha256(content) -> str | None:
    if content is None:
        return None
    canonical = json.dumps(content, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _furniture_marker_flags(content) -> dict[str, bool]:
    text = _content_text(content)
    return {key: (marker in text) for key, marker in _FURNITURE_MARKERS.items()}


# tempdoc 735 G2: which tier the CLI actually DELIVERED to the model for this call --
# a fact distinct from what the server authored (the settled-design principle: "what
# the model is delivered is a cohort fact ... capture it, never assume it"). A
# raw-SDK debug probe (tempdoc 735, CLI 2.1.209) proved the delivery rule is
# structured-if-present, text-otherwise: when the tool response carries
# `structuredContent`, the CLI hands the model that JSON serialized as a `content`
# STRING (not the human-readable text tier, and not a `type":"json"` block) --
# `justsearch_answer`/`justsearch_search` both observed this way. When the tool
# response has no `structuredContent` (e.g. `justsearch_status`,
# McpToolSurface.java:939-940 emits only a `type":"text"` block), the CLI forwards
# the SDK's own block-list `ToolResultBlock.content` shape.
_DELIVERED_TIER_STRUCTURED = "structured-json"
_DELIVERED_TIER_PROSE = "prose"
_DELIVERED_TIER_BLOCKS = "blocks"


def _delivered_tier(content) -> str | None:
    """Classify the raw delivered `content` into one of three tiers (never a
    fourth value -- `None` only means "nothing to classify", e.g. the call never
    executed): `"blocks"` for the SDK's list-of-block shape (`ToolResultBlock`,
    `claude_agent_sdk`: `content: str | list[dict[str, Any]] | None`); a `str`
    that parses (after stripping) as a JSON **object** is `"structured-json"` (a
    JSON array/number/etc. does not count -- every real structuredContent payload
    this surface emits is a top-level object, `McpEvidenceProjection.searchEvidence`/
    `answerEvidence` both return `Map<String,Object>`); any other `str` is
    `"prose"`."""
    if content is None:
        return None
    if isinstance(content, list):
        return _DELIVERED_TIER_BLOCKS
    if isinstance(content, str):
        try:
            parsed = json.loads(content.strip())
        except (ValueError, TypeError):
            return _DELIVERED_TIER_PROSE
        return _DELIVERED_TIER_STRUCTURED if isinstance(parsed, dict) else _DELIVERED_TIER_PROSE
    return None


# tempdoc 735 G2: the top-level structured-evidence fields worth knowing the
# presence of, replacing the text-grep furniture markers for structured-json
# deliveries. `matchedTerms`/`excerpts` are verified nested PER-HIT under
# `results[]` for `justsearch_search`
# (McpEvidenceProjection.java:75-93 -- `h.put("matchedTerms", ...)` /
# `h.put("excerpts", ...)` inside the per-hit loop, never at the top level);
# `quality`/`citations` are top-level-only, produced by `answerEvidence`
# (McpEvidenceProjection.java:136,151); `searchTrace`/`degradation` are
# top-level-only, produced by `searchEvidence` (McpEvidenceProjection.java:50-58).
# `results` itself is the top-level array `searchEvidence` always emits
# (McpEvidenceProjection.java:108).
# tempdoc 735 W6 (tool-surface 0.4.0) added the tier-equivalence fields
# `hints`/`facets`/`coverage`/`truncated` to both tools' structured tier --
# tracked here so structured-delivery cohorts' exposure to the formerly
# text-only furniture is measurable per call. Absent on <=0.3.1 responses
# (presence booleans just read False -- no schema impact).
_DELIVERED_FIELD_KEYS = (
    "quality", "matchedTerms", "degradation", "excerpts", "citations", "searchTrace", "results",
    "hints", "facets", "coverage", "truncated",
)


def _delivered_fields(content) -> dict[str, bool] | None:
    """Top-level structured-evidence field presence for a structured-json
    delivery. `None` for prose/blocks deliveries (or content that no longer
    parses) -- never a fabricated all-False dict standing in for "not
    applicable"; `_furniture_marker_flags` remains the signal for those tiers."""
    if _delivered_tier(content) != _DELIVERED_TIER_STRUCTURED:
        return None
    parsed = json.loads(content.strip())
    results = parsed.get("results")
    results = results if isinstance(results, list) else []
    _nested_ok_keys = ("matchedTerms", "excerpts")

    def _present(key: str) -> bool:
        if key in parsed:
            return True
        if key in _nested_ok_keys:
            return any(isinstance(r, dict) and key in r for r in results)
        return False

    return {key: _present(key) for key in _DELIVERED_FIELD_KEYS}


def _tool_result_digest_entry(result: dict | None) -> dict:
    """Redacted, committed-safe derivation of one tool result (tempdoc 736 D9,
    extended by tempdoc 735 G2 with `delivered_tier`/`delivered_fields`):
    hash/len/is_error/shape/furniture-marker booleans plus the delivered-tier
    classification -- NEVER the raw content, which stays in the ephemeral
    (gitignored) log only. `result` is None when the call never executed
    (blocked/disallowed) -- honest nulls throughout, never a fabricated
    zero/empty for the size/hash/is_error fields.

    `furniture_markers` is computed (as before) for `prose`/`blocks` deliveries;
    for `structured-json` deliveries it is `None` and `delivered_fields` carries
    the signal instead -- text-grepping a delivered JSON string for product
    furniture strings is measuring the wrong tier (this is the exact bug this
    increment fixes: tempdoc 735's 0/153 furniture-marker mystery was caused by
    grepping content that was never delivered as text in the first place)."""
    if result is None:
        return {
            "content_sha256": None,
            "content_len": None,
            "content_is_error": None,
            "content_shape": "empty",
            "furniture_markers": _furniture_marker_flags(None),
            "delivered_tier": None,
            "delivered_fields": None,
        }
    content = result.get("content")
    tier = _delivered_tier(content)
    return {
        "content_sha256": _content_sha256(content),
        "content_len": _content_len(content),
        "content_is_error": bool(result.get("is_error")),
        "content_shape": _content_shape(content),
        "furniture_markers": (
            _furniture_marker_flags(content) if tier != _DELIVERED_TIER_STRUCTURED else None
        ),
        "delivered_tier": tier,
        "delivered_fields": _delivered_fields(content),
    }


def _call_status(tid: str, entry: dict, results: dict, denied: set, disallowed: list[str]) -> str:
    """tempdoc 736 D10 (issue 10): the four-state per-call status authority for
    `tool_call_sequence` -- DISTINCT from `_blocked()` below, whose executed/blocked
    split for `tool_calls`/`tool_calls_blocked` stays deliberately UNCHANGED (an
    errored call is still "not executed" for that side-array's purposes; only this
    ordered-sequence status gains the new `errored` state). Partition, in order:
    disallowed (name in the campaign's disallowed set) > blocked (no result arrived,
    OR a permission denial -- the call never executed) > errored (a result arrived
    with is_error=True -- executed and returned an error) > ok."""
    if entry["tool"] in disallowed:
        return "disallowed"
    r = results.get(tid)
    if r is None or entry["tool"] in denied:
        return "blocked"
    if r["is_error"]:
        return "errored"
    return "ok"


def _record_cell(state: TaskState, got: dict, condition: str,
                 disallowed: list[str], mcp_config: str | None,
                 declared_mcp_tool_surface_hash: str | None = None,
                 declared_mcp_tool_surface: list[dict] | None = None) -> None:
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

    # Adoption-funnel fields (tempdoc 725 increment 3): `toolsearch_targets` is the
    # ONLY resolved mcp__justsearch__* names a `select:` ToolSearch call named in
    # this cell (never free text); `tool_call_sequence` is the FULL ordered
    # attempts sequence (unlike `tool_calls` above, which is executed-only) with a
    # per-attempt ok/blocked/disallowed status, so funnel metrics can see e.g. a
    # blocked-then-abandoned mcp call that `tool_calls` alone would hide entirely.
    state.metadata["toolsearch_targets"] = _toolsearch_targets(attempts)
    state.metadata["tool_call_sequence"] = [
        {
            "name": entry["tool"],
            "status": _call_status(tid, entry, results, denied, disallowed),
        }
        for tid, entry in attempts.items()
    ]
    # tempdoc 736 D9 (issue 9, committed tier): one redacted digest entry per
    # attempt, in the same order as `tool_call_sequence` -- `content_is_error`
    # here and the `errored` status above are cross-consistent BY CONSTRUCTION
    # (both derive from the same `results[tid]["is_error"]`).
    state.metadata["tool_result_digests"] = [
        _tool_result_digest_entry(results.get(tid)) for tid in attempts
    ]

    # Offered MCP surface from the SDK's own status (tempdoc 675 finding 2).
    servers = got["mcp_servers"]
    justsearch_tools = got["justsearch_tools"]
    justsearch_surface = got.get("justsearch_tool_surface") or []
    if not justsearch_surface and servers is not None:
        for server in servers:
            if not isinstance(server, dict) or server.get("name") != "justsearch":
                continue
            for tool in server.get("tools") or []:
                raw_name = tool.get("name") if isinstance(tool, dict) else str(tool)
                name = raw_name if str(raw_name).startswith("mcp__") else f"mcp__justsearch__{raw_name}"
                justsearch_surface.append({
                    "name": name,
                    "description": tool.get("description") if isinstance(tool, dict) else None,
                    "input_schema": (
                        tool.get("inputSchema", tool.get("input_schema"))
                        if isinstance(tool, dict) else None
                    ),
                })
        justsearch_surface.sort(key=lambda item: item["name"])
    state.metadata["mcp_servers"] = servers
    state.metadata["mcp_tools_offered"] = (
        len(justsearch_tools) if servers is not None else None)
    state.metadata["mcp_tool_names_offered"] = (
        sorted(set(justsearch_tools)) if servers is not None else None)
    from jseval.agent_manifest import mcp_tool_surface_hash

    observed_names = sorted(set(justsearch_tools))
    declared_names = sorted(
        str(tool.get("name")) for tool in (declared_mcp_tool_surface or [])
        if tool.get("name")
    )
    if declared_names and servers is not None and observed_names == declared_names:
        observed_surface_hash = declared_mcp_tool_surface_hash
    else:
        observed_surface_hash = (
            mcp_tool_surface_hash(justsearch_surface)
            if servers is not None and justsearch_surface else None
        )
    state.metadata["observed_mcp_tool_surface_hash"] = observed_surface_hash
    # Condition A (baseline) is exempt by construction: `_one_attempt` passes
    # `mcp_servers={}` for any condition not in `_WITH_TOOL` (line ~386), so an A
    # cell never offers the campaign-declared surface and must not be compared
    # against it. Without this gate, A cells were voided at record time against
    # the declared 6-tool surface -- 17/20 A-cells errored in the 2026-07-14
    # exposure A/B smoke (tempdoc 725) because the SDK's `get_mcp_status()`
    # legitimately returned `servers == []` (empty list, not None) for a cell
    # that was never given any MCP servers to begin with.
    with_tool_cell = condition in _WITH_TOOL and bool(mcp_config)
    # How the surface hash was obtained (tempdoc 755 Track 1 item 2). "status"/"status-retry"
    # come from `_mcp_surface`'s probe/reprobe; None means no probe reported a surface.
    surface_evidence = got.get("surface_evidence")
    state.metadata["surface_evidence"] = surface_evidence
    # Fallback forensic cross-check: a with-tool cell whose status probe never reported a
    # surface (`observed_surface_hash is None` -> unverified) but which DID execute >=1
    # `mcp__justsearch__*` tool. INTEGRITY RULE (charter section B item 2): a subset of EXECUTED
    # tools proves only that those tools were offered -- it does NOT establish that the FULL
    # offered surface EQUALLED the declared surface (unexecuted extra tools and per-tool
    # schemas are unobservable; the declared hash fingerprints name+description+input_schema
    # of the whole set). So this cross-check can NEVER equate the observed hash with the
    # declared hash; the cell stays UNVERIFIED (`surface_evidence` None, observed hash None).
    # No genuine independent tools-listing seam exists to upgrade it: `get_server_info()`
    # returns the cached `initialize` result documented for commands/output-styles only, and
    # `get_context_usage()` re-issues the same flaky control request as `get_mcp_status()`.
    # Hence `surface_evidence == "fallback-listing"` is never emitted here (kept in the enum
    # for forward-compat only); we record the basis and leave verification unmanufactured.
    if with_tool_cell and not observed_surface_hash:
        executed_js = sorted({
            e["tool"] for e in state.metadata["tool_calls"]
            if str(e.get("tool", "")).startswith("mcp__justsearch__")
        })
        if executed_js:
            state.metadata["mcp_surface_fallback"] = {
                "executed_justsearch_subset_of_declared": (
                    bool(declared_names) and set(executed_js) <= set(declared_names)),
                "verified": False,
                "reason": (
                    "status probe empty after retries; executed-tool subset cross-check "
                    "cannot establish full offered surface == declared surface (integrity "
                    "rule) -- cell left unverified"),
            }
    if (with_tool_cell and declared_mcp_tool_surface_hash and observed_surface_hash
            and declared_mcp_tool_surface_hash != observed_surface_hash):
        state.metadata.setdefault(
            "error",
            "declared MCP tool-surface hash disagrees with observed tools/list",
        )
    if (with_tool_cell and declared_names and servers is not None
            and observed_names != declared_names):
        state.metadata.setdefault(
            "error",
            "offered MCP tool names disagree with captured canonical tools/list",
        )
    justsearch_connected = bool(servers) and any(
        (s.get("name") == "justsearch"
         and str(s.get("status", "")).lower() in ("connected", "ready", "ok"))
        for s in (servers or []))
    state.metadata["mcp_tools_deferred"] = (
        bool(justsearch_connected and not justsearch_tools) if servers is not None else None)

    # With-tool surface assertion (condition A exempt by construction). Preserve the
    # tri-state: status unavailable -> "unverified" (never conflated with healthy).
    if with_tool_cell:
        if servers is None:
            state.metadata["mcp_surface_unverified"] = True
        elif not justsearch_tools:
            state.metadata.setdefault(
                "error", f"expected MCP tool surface not offered: mcp_servers={servers}")
            return

    if rmsg is not None:
        usage = getattr(rmsg, "usage", None) or {}
        model_usage = getattr(rmsg, "model_usage", None) or {}
        resolved_models = set(got.get("resolved_models") or ()) | set(model_usage)
        state.metadata.update({
            "usage": usage,
            "model_usage": model_usage,
            "resolved_model": next(iter(resolved_models), None),
        })
        if len(resolved_models) > 1:
            state.metadata.setdefault(
                "error",
                "resolved provider model changed within one cell: "
                f"{sorted(resolved_models)!r}",
            )
        # tempdoc 736 D12 (issue 12): `total_cost_usd`/`num_turns` are fields of
        # EVERY ResultMessage, independent of `is_error` (SDK-confirmed via
        # `inspect.getsource(ResultMessage)`) -- populate them here, BEFORE the
        # is_error early-return below, so an errored cell's incremental spend/turn
        # count is not silently dropped. Only the no-ResultMessage `else` branch
        # below leaves them null -- genuinely unknowable there, an honest null
        # rather than a fabricated zero (P3 tri-state).
        state.metadata.update({
            "cost_usd": getattr(rmsg, "total_cost_usd", None),
            "num_turns": getattr(rmsg, "num_turns", None),
            # tempdoc 757: set tokens here, BEFORE the is_error early-return below, so a
            # usd-budget-exhausted cell (which DOES deliver an is_error ResultMessage)
            # keeps its cache-creation token count instead of dropping it at the return.
            "unique_tokens": usage.get("cache_creation_input_tokens"),
        })
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
    else:
        state.metadata.setdefault(
            "error", "no ResultMessage (stream ended without a terminal result)")
        # tempdoc 757: no terminal ResultMessage (wall-clock cancel) -> fall back to the
        # partial token LOWER BOUND accumulated from the streamed AssistantMessages. Cost
        # is genuinely unrecoverable here (it lives only on the ResultMessage), so it stays
        # null and the composer fails the cost interval closed for this cell.
        accum = got.get("usage_accum") or {}
        if accum:
            state.metadata.setdefault("usage", accum)
            if state.metadata.get("unique_tokens") is None:
                state.metadata["unique_tokens"] = accum.get("cache_creation_input_tokens")


@scorer(metrics=[accuracy()])
def substring_scorer():
    """Reuse the harness substring + abstention scorer as an Inspect scorer."""
    async def score(state: TaskState, target: Target) -> Score:
        if state.metadata.get("error"):
            return Score(value="I", answer="", metadata={"error": state.metadata["error"]})
        ok = _score_answer(target.text, state.output.completion or "")
        return Score(value="C" if ok else "I", answer=(state.output.completion or "")[:200])
    return score


def _assert_no_float_task_args(bound_locals: dict) -> None:
    """Inspect's JSON recorder reads persisted floats back as Decimal (`ijson` without
    `use_float=True`), so a `float` in a task's identity args breaks `eval_set` resume
    (tempdoc 675 F0 — see the docstring note on `max_budget` below). Guard against
    reintroducing one; call with `locals()` at the top of `agent_utility_task`."""
    floats = {k: v for k, v in bound_locals.items() if isinstance(v, float)}
    assert not floats, (
        f"float value(s) in agent_utility_task identity args break eval_set resume: "
        f"{floats!r} — pass as str/int instead")


@task
def agent_utility_task(conditions=("A", "C"), queries_path: str = "", corpus_dir: str = "",
                       mcp_config: str | None = None, model: str = "haiku",
                       max_queries: int | None = None, max_budget: str = "0.50",
                       timeout_s: int = 180, max_turns: int = _DEFAULT_MAX_TURNS,
                       # --- cohort identity (task-args → config-change log segregation, A2) ---
                       cli_version: str | None = None, mcp_tool_surface_hash: str | None = None,
                       mcp_tool_surface_json: str = "[]",
                       judge_kind: str = "substring-em", prompt_template_hash: str | None = None,
                       corpus_dataset: str = "", corpus_signature: str = "",
                       source_identity_json: str = "{}",
                       agent_env_json: str = "{}",
                       timeout_s_by_condition_json: str = "{}") -> Task:
    """ONE Inspect task over the whole matrix (tempdoc 675 single pool).

    Samples are the flat `condition × query` cross-product; `condition` is a sample
    field (`Sample.metadata`), `sample.id = "{c}|q{i}"` (unique across conditions so
    Inspect resume stays keyed on the cell). The cohort-identity args are what
    `eval_set` segregates logs by."""
    _assert_no_float_task_args(locals())
    source_identity = json.loads(source_identity_json)
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
        solver=claude_agent_solver(
            corpus_dir, mcp_config, model, max_budget, timeout_s, max_turns,
            mcp_tool_surface_json, agent_env_json, timeout_s_by_condition_json,
        ),
        scorer=substring_scorer(),
        metadata={
            "model": model,
            "corpus": {
                "dataset": corpus_dataset,
                "signature": ((source_identity.get("corpus") or {}).get("signature")
                              or corpus_signature or corpus_dataset),
                "declared_signature": corpus_signature or None,
            },
            "cohort": {
                "model": model, "cli_version": cli_version,
                "mcp_tool_surface_hash": mcp_tool_surface_hash,
                "mcp_tool_surface": json.loads(mcp_tool_surface_json),
                "judge_kind": judge_kind, "prompt_template_hash": prompt_template_hash,
                "source_git_sha": source_identity.get("source_git_sha"),
                "source_git_dirty": source_identity.get("source_git_dirty"),
                "source_git_state": source_identity.get("source_git_state"),
                "search_config_cohort_key": source_identity.get("search_config_cohort_key"),
                "environment": source_identity.get("environment"),
                "corpus_identity": source_identity.get("corpus"),
                "corpus_certification": source_identity.get("corpus_certification"),
                "query_identity": source_identity.get("queries"),
                "campaign_identity": source_identity.get("campaign"),
                "exposure_config": source_identity.get("exposure_config"),
                "mcp_initialize_identity": source_identity.get("mcp_initialize_identity"),
            },
        },
    )


def _git_source_state(*, exclude: str | Path | None = None) -> dict | None:
    """Hash tracked changes and every untracked source byte, excluding run output."""
    try:
        root_result = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        if root_result.returncode != 0:
            return None
        root = Path(root_result.stdout.strip()).resolve()
        excluded = Path(exclude).resolve() if exclude else None
        tracked = subprocess.run(
            ["git", "diff", "--binary", "HEAD", "--"], cwd=root,
            capture_output=True, timeout=10, check=False,
        )
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=root,
            capture_output=True, timeout=10, check=False,
        )
        if tracked.returncode != 0 or untracked.returncode != 0:
            return None
        untracked_hash = hashlib.sha256()
        count = 0
        for raw_path in untracked.stdout.split(b"\0"):
            if not raw_path:
                continue
            relative = raw_path.decode("utf-8", errors="surrogateescape")
            path = (root / relative).resolve()
            if excluded and (path == excluded or excluded in path.parents):
                continue
            if not path.is_file():
                continue
            untracked_hash.update(relative.replace("\\", "/").encode("utf-8"))
            untracked_hash.update(b"\0")
            untracked_hash.update(path.read_bytes())
            untracked_hash.update(b"\0")
            count += 1
        return {
            "tracked_diff_sha256": hashlib.sha256(tracked.stdout).hexdigest(),
            "untracked_sha256": untracked_hash.hexdigest(),
            "untracked_count": count,
            "dirty": bool(tracked.stdout) or count > 0,
        }
    except Exception:
        return None


def _git_dirty() -> bool | None:
    state = _git_source_state()
    return state.get("dirty") if state is not None else None


_DERIVATION_SAMPLE_N = 20


def _verify_corpus_dir_derivation(
    *,
    corpus_root: Path,
    staged_dir: Path,
    staged_files: list[Path],
    signature: str,
    sample_n: int = _DERIVATION_SAMPLE_N,
) -> None:
    """Fail CLOSED unless the exploded ``staged_dir`` is the derivation of
    ``corpus_root/corpus.jsonl`` (tempdoc 624 identity hardening).

    Root mode SIGNS ``corpus.jsonl`` but only *attests* the ``corpus-dir`` the agents
    actually read (``corpus_dir_files_signature``): a stale or swapped explosion passes
    every identity gate while agents search divergent text. This re-derives the
    explosion from the SAME logic the build/ingest paths use — a *projection* of
    ``corpus_generate.materialize_doc_entry`` + ``materialize.materialize`` (the one
    place the axis-aware scan-vs-text decision and the on-disk write live), not a second
    fork of it — and checks two properties:

    1. **Exact file set** — expected filenames (one per ``corpus.jsonl`` doc via
       ``doc_id_to_filename`` + the materialize sentinel) equal the on-disk file set.
       A count/name mismatch fails closed naming the delta.
    2. **Sampled content** — ``sample_n`` docs chosen deterministically by a seed
       derived from the corpus SIGNATURE; each sampled doc's on-disk bytes must equal
       its re-materialized bytes. A divergence fails closed naming the doc.

    Cost is one probe render + ``sample_n`` re-materializations — O(sample), not
    O(corpus) — so it stays fast even on a 10k-doc corpus."""
    from jseval import corpus_generate
    from jseval import materialize as mat_mod

    corpus_jsonl = corpus_root / "corpus.jsonl"
    if not corpus_jsonl.is_file():
        # The root-mode signature path already requires a signable root; a root with
        # only qrels/ (no corpus.jsonl) has nothing to derive an explosion from.
        return
    docs = [
        json.loads(line)
        for line in corpus_jsonl.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]

    metadata_path = corpus_root / "metadata.json"
    type_axis: str | None = None
    if metadata_path.is_file():
        try:
            type_axis = json.loads(metadata_path.read_text(encoding="utf-8")).get("type_axis")
        except (json.JSONDecodeError, OSError):
            type_axis = None

    # Derive the .txt-vs-.png extension from the REAL helper (a single probe render),
    # not a hardcoded `type_axis == "scan"` check — projection, not a re-implementation.
    probe = corpus_generate.materialize_doc_entry(
        {"_id": "__derivation_probe__", "title": "", "text": ""}, type_axis)
    ext = "png" if probe.get("image_b64") else "txt"

    expected_names = {mat_mod.doc_id_to_filename(str(d["_id"]), ext=ext) for d in docs}
    expected_names.add(mat_mod.doc_id_to_filename(mat_mod.SENTINEL_DOC_ID))
    actual_names = {p.relative_to(staged_dir).as_posix() for p in staged_files}
    if expected_names != actual_names:
        missing = sorted(expected_names - actual_names)
        extra = sorted(actual_names - expected_names)
        raise ValueError(
            "corpus-dir is not the derivation of corpus.jsonl (file-set mismatch): "
            f"expected {len(expected_names)} files, found {len(actual_names)}; "
            f"missing={missing[:5]} extra={extra[:5]}"
        )

    if not docs:
        return
    # Deterministic sample: the seed is a pure function of the corpus signature, so the
    # same corpus always samples the same docs (reproducible fail-closed evidence).
    rng = random.Random(int(signature[:16], 16))
    sampled = [docs[i] for i in rng.sample(range(len(docs)), min(sample_n, len(docs)))]
    sampled_entries = [
        (d, corpus_generate.materialize_doc_entry(d, type_axis)) for d in sampled
    ]
    with tempfile.TemporaryDirectory(prefix="jseval-derivation-check-") as tmp:
        tmp_dir = Path(tmp)
        # Reuse the exact on-disk write path (content formatting, png decode, filename
        # encoding, utf-8) rather than re-deriving the byte content here.
        mat_mod.materialize((e for _, e in sampled_entries), tmp_dir, skip_existing=False)
        for d, entry in sampled_entries:
            name = mat_mod.doc_id_to_filename(
                str(d["_id"]), ext=("png" if entry.get("image_b64") else "txt"))
            on_disk = staged_dir / name
            if not on_disk.is_file():
                raise ValueError(
                    f"corpus-dir derivation mismatch: doc {d['_id']!r} expected file "
                    f"{name!r} is absent from the staged corpus-dir"
                )
            if on_disk.read_bytes() != (tmp_dir / name).read_bytes():
                raise ValueError(
                    f"corpus-dir derivation mismatch: doc {d['_id']!r} on-disk content "
                    f"({name}) diverges from its derivation of corpus.jsonl"
                )


def _capture_or_load_source_identity(
    *,
    log_dir: str,
    corpus_dir: str,
    corpus_dataset: str,
    declared_corpus_signature: str,
    search_config_cohort_key: str | None,
    corpus_root: str | Path | None = None,
    queries_path: str | None = None,
    conditions: tuple[str, ...] | list[str] = (),
    seeds: int = 0,
    max_queries: int | None = None,
    mcp_tool_surface: list[dict] | None = None,
    corpus_certification: str | Path | None = None,
    exposure_config: dict | None = None,
    mcp_initialize_identity: dict | None = None,
) -> dict:
    """Persist source-time identity and fail closed when resumed inputs drift.

    `corpus_root` (tempdoc 624 confirmatory pre-registration, 2026-07-17 — corpus
    identity/staging decoupling): when given it is the dataset ROOT (`corpus.jsonl`
    + `qrels/`), and the corpus SIGNATURE — the value a `--corpus-certification`
    verifies against — is computed from that root, not from the leak-safe staged
    `corpus_dir` SUBDIR. `corpus_dir` stays the raw agent-facing text axis and its
    files hash is recorded as an audit-only attestation (`corpus_dir_files_signature`).
    Fails CLOSED if `corpus_root` is not a dataset root (no `corpus.jsonl`/`qrels`)
    or if `corpus_dir` is not its immediate child. `None` = today's behavior
    byte-for-byte (the staged/declared single-axis path)."""
    from jseval.corpus_identity import corpus_signature
    from jseval.env_fingerprint import safe_environment_identity
    from jseval.manifest import _git_sha_full

    root = Path(corpus_dir)
    corpus_dir_files_signature: str | None = None
    corpus_root_resolved: str | None = None
    if corpus_root is not None:
        corpus_root_path = Path(corpus_root)
        signature = corpus_signature(corpus_root_path)
        if signature is None:
            raise ValueError(
                f"corpus_root {corpus_root} is not a dataset root "
                "(no corpus.jsonl or qrels/test.tsv to sign) — no files-mode "
                "fallback on the root, this is a config error"
            )
        # The staged corpus_dir must be the root's OWN exploded subdir: this
        # prevents attaching corpus A's certified root identity to corpus B's
        # staged text (the whole point of a separate root axis).
        if Path(corpus_dir).resolve().parent != corpus_root_path.resolve():
            raise ValueError(
                f"corpus_dir {corpus_dir} is not an immediate child of "
                f"corpus_root {corpus_root}"
            )
        corpus_root_resolved = str(corpus_root_path.resolve())
        # Attestation only: the raw-text axis stays auditable even though it is no
        # longer part of identity in root mode.
        staged_files = sorted(
            (path for path in root.rglob("*") if path.is_file()),
            key=lambda path: path.relative_to(root).as_posix(),
        )
        corpus_dir_files_signature = corpus_signature(root, staged_files)
        # Attestation is not enough: fail CLOSED unless the exploded corpus-dir is
        # actually the derivation of the signed corpus.jsonl (tempdoc 624 hardening) —
        # a stale explosion otherwise passes every identity gate while agents search
        # divergent text.
        _verify_corpus_dir_derivation(
            corpus_root=corpus_root_path,
            staged_dir=root,
            staged_files=staged_files,
            signature=signature,
        )
    else:
        signature = corpus_signature(root)
        if signature is None:
            materialized_files = sorted(
                (path for path in root.rglob("*") if path.is_file()),
                key=lambda path: path.relative_to(root).as_posix(),
            )
            signature = corpus_signature(root, materialized_files)
    declared = declared_corpus_signature or None
    signature_matches = declared == signature if declared else None
    if declared and len(declared) == 64 and signature_matches is False:
        raise ValueError(
            f"declared corpus signature {declared} disagrees with materialized {signature}"
        )
    certification = None
    if corpus_certification is not None:
        from jseval.corpus_certify import certification_snapshot

        try:
            certification = certification_snapshot(
                corpus_certification,
                dataset=corpus_dataset,
                expected_signature=signature,
            )
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            raise ValueError(f"corpus_certification rejected: {exc}") from exc
    query_identity = None
    campaign = None
    if queries_path is not None:
        query_path = Path(queries_path)
        query_bytes = query_path.read_bytes()
        rows = json.loads(query_bytes.decode("utf-8"))
        if max_queries:
            rows = rows[:max_queries]
        condition_list = sorted(str(item) for item in conditions)
        expected_cells = [
            f"{condition}|{seed}|q{index}"
            for seed in range(int(seeds))
            for condition in condition_list
            for index in range(len(rows))
        ]
        query_identity = {
            "sha256": hashlib.sha256(query_bytes).hexdigest(),
            "row_count": len(rows),
        }
        campaign = {
            "conditions": condition_list,
            "seeds": int(seeds),
            "expected_cells": expected_cells,
        }
    if (
        certification is not None
        and query_identity is not None
        and certification.get("query_gold_sha256") != query_identity.get("sha256")
    ):
        raise ValueError(
            "corpus_certification rejected: query-and-gold digest disagrees with queries"
        )

    git_state = _git_source_state(exclude=log_dir)
    corpus_block = {
        "dataset": corpus_dataset,
        "declared_signature": declared,
        "signature": signature,
        "signature_matches": signature_matches,
    }
    if corpus_root is not None:
        # Root-mode keys are added ONLY when corpus_root is given, so a declared-mode
        # run's persisted sidecar stays byte-identical to pre-change and a root-mode
        # run is a distinct identity vs a declared-mode one (both correct).
        corpus_block["corpus_root"] = corpus_root_resolved
        corpus_block["corpus_dir_files_signature"] = corpus_dir_files_signature
    stable_identity = {
        "schema": "agent-utility-source-identity.v1",
        "source_git_sha": _git_sha_full(),
        "source_git_dirty": git_state.get("dirty") if git_state is not None else None,
        "source_git_state": git_state,
        "search_config_cohort_key": search_config_cohort_key,
        "mcp_tool_surface": mcp_tool_surface,
        "corpus": corpus_block,
        "corpus_certification": certification,
        "queries": query_identity,
        "campaign": campaign,
        "environment": safe_environment_identity(),
        "exposure_config": exposure_config,
        "mcp_initialize_identity": mcp_initialize_identity,
    }
    path = Path(log_dir) / "source-identity.v1.json"
    if path.is_file():
        identity = json.loads(path.read_text(encoding="utf-8"))
        for key, value in stable_identity.items():
            if identity.get(key) != value:
                raise ValueError(
                    f"source identity sidecar does not match resumed {key}"
                )
        return identity

    identity = stable_identity
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(identity, indent=2, sort_keys=True), encoding="utf-8")
    return identity


def run_utility_eval(*, queries_path: str, corpus_dir: str, mcp_config: str | None,
                     model: str = "haiku", conditions=("A", "C"), seeds: int = 3,
                     concurrency: int = 6, log_dir: str, max_queries: int | None = None,
                     max_budget: float = 0.50, timeout_s: int = 180,
                     max_turns: int = _DEFAULT_MAX_TURNS,
                     cli_version: str | None = None,
                     corpus_dataset: str = "", corpus_signature: str = "",
                     corpus_root: str | None = None,
                     search_config_cohort_key: str | None = None,
                     corpus_certification: str | Path | None = None,
                     agent_env: dict[str, str] | None = None,
                     timeout_s_by_condition: dict[str, int] | None = None) -> str:
    """Run the matrix through Inspect `eval_set` (resumable). seeds → `epochs`.

    Returns the log_dir; re-invoking with the same log_dir resumes (skips done
    samples). condition A uses no MCP; B/C use the JustSearch one. Executor v2
    (tempdoc 675): ONE task, one concurrency pool at `max_samples` (no `max_tasks=1`
    condition serialization).

    `agent_env` (tempdoc 725 increment 4 — exposure A/B wiring): an optional
    ``{str: str}`` env-var overlay threaded into every cell's child Agent SDK
    session (e.g. ``{"ENABLE_TOOL_SEARCH": "false"}`` for the eager arm). `None`/empty
    is today's behavior byte-for-byte. Its `ENABLE_TOOL_SEARCH` entry (if present) is
    also the EFFECTIVE value fed to the exposure-config capture below — the child
    session sees `agent_env` merged OVER the harness process's own env (the SDK's
    `subprocess_cli.py` does `{**inherited_env, **options.env}`), so an `agent_env`
    override, not just the parent process's own `os.environ`, is what the recorded
    `exposure_config` must describe to match the child's actual config.

    `timeout_s_by_condition` (tempdoc 624 §Harness lessons — per-arm timeout calibration):
    an optional ``{condition: int}`` map of per-condition wall-clock budgets (from
    `utility-calibrate`'s `timeout_s_by_condition`). Threaded through the task identity as a
    canonical-JSON int-valued string; a cell resolves its budget by its own condition and
    falls back to the scalar `timeout_s` for any absent condition. `None`/empty is today's
    behavior byte-for-byte (every cell uses `timeout_s`)."""
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

    captured_mcp_surface = (
        _capture_canonical_mcp_surface(mcp_config) if mcp_config else []
    )
    from jseval.agent_manifest import mcp_tool_surface_hash as _surface_hash
    captured_mcp_surface_hash = (
        _surface_hash(captured_mcp_surface) if captured_mcp_surface else None
    )
    # tempdoc 725 increment 2: the missing `initialize` capture + the config-only
    # exposure-mode derivation, at the same source-time point as the tools/list
    # capture above. Both fail closed (raise) rather than degrade to a silent None.
    captured_mcp_initialize_identity = (
        _capture_mcp_initialize_identity(mcp_config) if mcp_config else None
    )
    # Effective ENABLE_TOOL_SEARCH the child session will actually see: `agent_env`'s
    # entry wins (it is applied OVER the harness process env by the SDK), falling back
    # to the harness process's own env only when `agent_env` doesn't set it — so the
    # capture below describes the child's real config, not merely this parent process.
    effective_enable_tool_search = (
        agent_env.get("ENABLE_TOOL_SEARCH")
        if agent_env and "ENABLE_TOOL_SEARCH" in agent_env
        else os.environ.get("ENABLE_TOOL_SEARCH")
    )
    captured_exposure_config = _capture_exposure_config(
        mcp_config, enable_tool_search=effective_enable_tool_search)

    # The prompt template is identical across conditions → its hash is a cohort field.
    prompt_template_hash = _sha256_canonical(_PROMPT)
    source_identity = _capture_or_load_source_identity(
        log_dir=log_dir,
        corpus_dir=corpus_dir,
        corpus_dataset=corpus_dataset,
        declared_corpus_signature=corpus_signature,
        corpus_root=corpus_root,
        search_config_cohort_key=search_config_cohort_key,
        queries_path=queries_path,
        conditions=conditions,
        seeds=seeds,
        max_queries=max_queries,
        mcp_tool_surface=captured_mcp_surface,
        corpus_certification=corpus_certification,
        exposure_config=captured_exposure_config,
        mcp_initialize_identity=captured_mcp_initialize_identity,
    )
    source_identity_json = json.dumps(source_identity, sort_keys=True, separators=(",", ":"))
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
                mcp_tool_surface_hash=captured_mcp_surface_hash,
                mcp_tool_surface_json=json.dumps(
                    captured_mcp_surface, sort_keys=True, separators=(",", ":")),
                judge_kind="substring-em",
                prompt_template_hash=prompt_template_hash, corpus_dataset=corpus_dataset,
                corpus_signature=corpus_signature,
                source_identity_json=source_identity_json,
                agent_env_json=json.dumps(agent_env or {}, sort_keys=True, separators=(",", ":")),
                # int-coerced so a float can never enter the task-identity args
                # (`_assert_no_float_task_args`); canonical (sorted keys) for a stable identity.
                timeout_s_by_condition_json=json.dumps(
                    {str(k): int(v) for k, v in (timeout_s_by_condition or {}).items()},
                    sort_keys=True, separators=(",", ":")),
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
        if mcp_config:
            final_mcp_surface = _capture_canonical_mcp_surface(mcp_config)
            if final_mcp_surface != captured_mcp_surface:
                raise RuntimeError("canonical MCP tools/list changed during the campaign")
    finally:
        shutil.rmtree(Path(staged_corpus_dir).parent, ignore_errors=True)
    return log_dir
