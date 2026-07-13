"""Orchestration glue: agent-eval runs -> compose-ready summaries (tempdoc 624).

Bridges the existing Phase-2 harness (``agent_retrieval_eval.run_agent_eval``) to
the utility-comparison composer by attaching a cohort-identified agent manifest
and reshaping per-query results into the ``{qid: {correct, cost_usd,
unique_tokens, num_turns}}`` form ``compose_utility`` expects.

``unique_tokens`` is the legacy internal name for the provider-reported
``cache_creation_input_tokens`` counter. It excludes cache reads but is not
described as a universal unique-content metric.
"""

from __future__ import annotations

import re
import subprocess

from jseval.agent_manifest import build_agent_manifest, judge_identity


def claude_cli_version() -> str | None:
    """The live ``claude`` CLI version — a mandatory agentic-eval identity field
    (HAL / Evaluation-Cards provenance). ``None`` if the CLI is unavailable."""
    try:
        r = subprocess.run(
            ["claude", "--version"], capture_output=True, text=True, timeout=10,
        )
        return (r.stdout.strip() or None) if r.returncode == 0 else None
    except Exception:
        return None


def _per_query_from_result(run_result: dict) -> dict:
    pq: dict = {}
    for r in run_result.get("results", []):
        qid = r.get("query")
        if not qid:
            continue
        # A cell that errored (timeout/LLM failure) still carries a query plus default
        # correct=False/cost_usd=0/tool_calls=[]; excluding it here mirrors the Inspect
        # path's valid-only parity so an errored run is not silently reshaped into a
        # genuine zero-cost, zero-tool, incorrect observation in the paired comparison.
        if r.get("error"):
            continue
        pq[qid] = {
            "correct": bool(r.get("correct")),
            "cost_usd": r.get("cost_usd"),
            "unique_tokens": r.get("cache_creation_tokens"),  # provider cache-creation counter
            "num_turns": r.get("num_turns"),
            # Answer-key leak backstop (tempdoc 624 §As-built #7): carries
            # agent_retrieval_eval.find_leak_suspect_tool_calls's verdict through to
            # the composer, which excludes a flagged (seed, qid) observation from
            # the paired statistics and surfaces it in `leak_suspect_cells` instead
            # of silently accepting or silently dropping it.
            "leak_suspect": bool(r.get("leak_suspect_tool_calls")),
            # Empirical tool-call assertion data (tempdoc 624 §As-built #5
            # residual-gap close): `run_agent_eval` always parses stream-json, so
            # these are the real per-call capture, not derived/approximated. A
            # `None` list (vs. an empty `[]`) is preserved so
            # `utility_comparison._tool_call_assertions` can tell "no tool data
            # captured for this cell" from "captured and clean" — see that
            # function's docstring for why the distinction matters.
            "tool_calls": r.get("tool_calls"),
            "disallowed_tool_calls": r.get("disallowed_tool_calls"),
            "leak_suspect_tool_calls": r.get("leak_suspect_tool_calls"),
            # Offered MCP tool-surface capture (tempdoc 624 battlefield retrospective):
            # only `agent_utility_inspect.claude_agent_solver` currently populates these
            # (`r.get(...)` reads None for a classic `run_agent_eval` result, same "no
            # data" semantics as `tool_calls` above -- see `_tool_call_assertions`).
            "mcp_servers": r.get("mcp_servers"),
            "mcp_tools_offered": r.get("mcp_tools_offered"),
            "mcp_surface_unverified": bool(r.get("mcp_surface_unverified")),
            # Tool-surfacing-mode stamp (tempdoc 624 §M.8 amendment, Step 0 item 4):
            # only agent_utility_inspect.claude_agent_solver populates this today --
            # `r.get(...)` reads None for a classic run_agent_eval result (same
            # "no data" semantics as mcp_servers/mcp_tools_offered above).
            "mcp_tools_deferred": r.get("mcp_tools_deferred"),
        }
    return pq


def build_compose_summary(
    run_result: dict,
    *,
    condition: str,
    model: str,
    corpus: dict,
    seed: int,
    prompt_template: str,
    mcp_tool_surface: list[dict] | None = None,
    judge: dict | None = None,
    cli_version: str | None = None,
    search_config_cohort_key: str | None = None,
    hardware: dict | None = None,
    decoding: dict | None = None,
    eval_limits: dict | None = None,
    model_version: str | None = None,
) -> dict:
    """Attach a cohort identity + reshape a ``run_agent_eval`` result for the composer.

    The with-tool arm (B/C) should pass the live search backend's 623
    ``config_cohort_key`` as ``search_config_cohort_key``; arm A passes ``None``
    (it has no search backend — R1/R2).
    """
    manifest = build_agent_manifest(
        corpus=corpus,
        agent_model=model,
        agent_model_version=model_version,
        cli_version=cli_version if cli_version is not None else claude_cli_version(),
        mcp_tool_surface=mcp_tool_surface,
        judge=judge or judge_identity(kind="substring-em"),
        prompt_template=prompt_template,
        condition=condition,
        seed=seed,
        decoding=decoding,
        eval_limits=eval_limits,
        search_config_cohort_key=search_config_cohort_key,
        hardware=hardware,
    )
    return {
        "manifest": manifest,
        "condition": condition,
        "agent_model": model,
        "corpus": corpus,
        "per_query": _per_query_from_result(run_result),
        # Executor provenance (tempdoc 624 §M.8 amendment, Step 0 item 6): this
        # summary came from the classic `run_agent_eval` shell-out, which is
        # smoke-only / non-record-grade (see agent_retrieval_eval's module
        # docstring) -- the composer needs this to distinguish a legacy-sourced
        # record from an Inspect-sourced one (utility_comparison stamps it at
        # `cohort.executor`).
        "executor": "legacy-agent-eval",
    }


# --- Inspect-AI path (tempdoc 624 execution design): EvalLogs -> compose input ---

def eval_logs_to_summaries(log_dir: str, *, search_config_cohort_key: str | None = None,
                           judge_overlay: dict | None = None) -> list[dict]:
    """Project Inspect EvalLogs into the valid-cell composer input.

    The lossless reader first retains every attempted cell, including Inspect
    native errors. This function is intentionally only the valid-cell status
    projection. ``search_config_cohort_key`` remains a compatibility override
    for old logs; record-grade logs capture it at source time.
    """
    from jseval.agent_utility_observations import (
        read_inspect_observations,
        successful_summaries,
    )

    observations = read_inspect_observations(log_dir, judge_overlay=judge_overlay)
    return successful_summaries(
        observations,
        judge_overlay=judge_overlay,
        search_config_cohort_key=search_config_cohort_key,
    )


# --- Answer-key leak text-scan (tempdoc 624 §As-built #7 follow-up, promoted from
# the throwaway `_leak_free_recompose.py`) --------------------------------------
#
# `agent_utility_inspect.claude_agent_solver` now runs `--output-format
# stream-json` and stashes real tool_calls (§As-built #5 residual-gap close,
# see `eval_logs_to_summaries` above), so `find_leak_suspect_tool_calls` has a
# real per-call signal for a freshly-run Inspect log. This text-scan stays as
# an INDEPENDENT second backstop, not dead code: it also catches an EvalLog
# written before this fix (no `tool_calls` in `state.metadata` — `leak_suspect`
# from the pairing code would otherwise read as unflagged, not "unknown"), and
# it re-derives the identical leak signature directly from each sample's
# completion TEXT: a case-insensitive `queries.json`/`queries.jsonl` mention
# (the eval's own gold-answer file), applied exhaustively to every (condition,
# seed, qid) cell in a completed Inspect log dir.

_LEAK_NEEDLE_RE = re.compile(r"queries\.jsonl?", re.IGNORECASE)


def _completion_text(sample) -> str:
    out = getattr(sample, "output", None)
    if out is None:
        return ""
    comp = getattr(out, "completion", None)
    if comp:
        return str(comp)
    # fall back: some samples carry completion only inside .choices[].message.text
    choices = getattr(out, "choices", None) or []
    parts = []
    for ch in choices:
        msg = getattr(ch, "message", None)
        content = getattr(msg, "content", None) if msg else None
        if isinstance(content, str):
            parts.append(content)
        elif isinstance(content, list):
            for c in content:
                t = getattr(c, "text", None) if not isinstance(c, dict) else c.get("text")
                if t:
                    parts.append(str(t))
    return "\n".join(parts)


def scan_leaked_cells(log_dir) -> dict[str, dict]:
    """Exhaustive scan: every sample in every Inspect EvalLog file under log_dir.

    :returns: ``{"{condition}|{seed}|{qid}": {"condition":, "seed":, "qid":,
        "n_matches":, "completion_excerpt":}}`` for every LEAKED cell.
    """
    from pathlib import Path

    from inspect_ai.log import read_eval_log

    leaked: dict[str, dict] = {}
    log_dir = Path(log_dir)
    log_files = sorted(log_dir.glob("*.eval")) + sorted(log_dir.glob("*.json"))
    for lf in log_files:
        if lf.name == "logs.json":
            continue
        try:
            log = read_eval_log(lf.as_posix())
        except Exception:
            continue
        if not getattr(log, "eval", None):
            continue
        for s in (log.samples or []):
            if (s.metadata or {}).get("error") or getattr(s, "error", None):
                continue  # excluded cell already, not part of any paired stat
            # tempdoc 675 single pool: condition is a sample field; sample.id carries it.
            condition = (s.metadata or {}).get("condition")
            seed = int(s.epoch or 1) - 1
            qid = str(s.id).split("|", 1)[-1]
            text = _completion_text(s)
            matches = _LEAK_NEEDLE_RE.findall(text)
            if matches:
                key = f"{condition}|{seed}|{qid}"
                leaked[key] = {
                    "condition": condition,
                    "seed": seed,
                    "qid": qid,
                    "n_matches": len(matches),
                    "completion_excerpt": text[:400],
                }
    return leaked


def apply_leak_flags(summaries: list[dict], leaked: dict[str, dict]) -> int:
    """Stamp ``per_query[qid]["leak_suspect"] = True`` on every summary entry whose
    ``{condition}|{seed}|{qid}`` key is present in ``leaked`` (from `scan_leaked_cells`
    or `scan_leaked_answers`) — the exact field `utility_comparison._pair_observations`
    already knows how to exclude from the paired statistics.

    :returns: the number of per-query entries flagged.
    """
    n_flagged = 0
    for s in summaries:
        cond = s.get("condition")
        for qid, entry in s.get("per_query", {}).items():
            seed = s["manifest"].get("seed")
            key = f"{cond}|{seed}|{qid}"
            if key in leaked:
                entry["leak_suspect"] = True
                n_flagged += 1
    return n_flagged


def scan_leaked_answers(results: list[dict], *, condition: str, seed: int) -> dict[str, dict]:
    """`scan_leaked_cells`'s non-Inspect counterpart: the ``run_agent_eval`` path
    (``agent_retrieval_eval.AgentResult.agent_answer``) has no EvalLog to read, but
    does carry the same raw completion text directly on each result dict — this
    matches the identical `_LEAK_NEEDLE_RE` needle against ``agent_answer`` so
    `apply_leak_flags` can consume either source's output unmodified (same
    ``{"{condition}|{seed}|{qid}": {...}}`` shape).
    """
    leaked: dict[str, dict] = {}
    for r in results:
        qid = r.get("query")
        if not qid:
            continue
        text = r.get("agent_answer") or ""
        matches = _LEAK_NEEDLE_RE.findall(text)
        if matches:
            key = f"{condition}|{seed}|{qid}"
            leaked[key] = {
                "condition": condition,
                "seed": seed,
                "qid": qid,
                "n_matches": len(matches),
                "completion_excerpt": text[:400],
            }
    return leaked


_WITH_TOOL = {"B", "C"}
