"""Orchestration glue: agent-eval runs -> compose-ready summaries (tempdoc 624).

Bridges the existing Phase-2 harness (``agent_retrieval_eval.run_agent_eval``) to
the utility-comparison composer by attaching a cohort-identified agent manifest
and reshaping per-query results into the ``{qid: {correct, cost_usd,
unique_tokens, num_turns}}`` form ``compose_utility`` expects.

``unique_tokens`` = ``cache_creation_input_tokens`` — the unique-content metric
(tempdoc 624 D-1 / R7): cumulative ``cache_read`` re-reads are excluded so the
token-efficiency comparison is not confounded by prompt caching.
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
        pq[qid] = {
            "correct": bool(r.get("correct")),
            "cost_usd": r.get("cost_usd"),
            "unique_tokens": r.get("cache_creation_tokens"),  # unique-content (D-1/R7)
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
    }


# --- Inspect-AI path (tempdoc 624 execution design): EvalLogs -> compose input ---

def eval_logs_to_summaries(log_dir: str, *, search_config_cohort_key: str | None = None,
                           judge_overlay: dict | None = None) -> list[dict]:
    """Read Inspect EvalLogs (`jseval utility-run`) into `compose_utility` summaries.

    Each task = one condition; each sample carries `epoch` (=seed), `scores`
    (correct), and stashed `metadata` (cost / unique_tokens / num_turns, A4). We
    group by (condition, seed) into one summary per cell-arm, attaching a cohort
    manifest whose `agent_cohort_key` is identical across A and C (the cohort
    fields live in task metadata) so the composer pairs them. Errored cells are
    excluded (valid-only, parity with the bespoke aggregation).

    ``judge_overlay`` (from ``utility_judge.judge_logs``) overrides the EM
    ``correct`` with the hybrid EM->LLM-judge ``final`` verdict and stamps the
    cohort's `judge` identity to the judge that actually scored it (tempdoc 624 C-6).
    """
    from pathlib import Path

    from inspect_ai.log import read_eval_log

    from jseval.agent_manifest import agent_cohort_key, judge_identity
    from jseval.manifest import _git_sha_full

    git_sha = _git_sha_full()
    summaries: list[dict] = []
    # Glob the log files ourselves + pass forward-slash paths: Inspect's
    # list_eval_logs returns drive-letter-stripped URIs on Windows that
    # read_eval_log can't open. Skip the eval_set index ("logs.json") and any
    # non-EvalLog json (read_eval_log raises ValidationError on those).
    log_files = sorted(Path(log_dir).glob("*.eval")) + sorted(Path(log_dir).glob("*.json"))
    for lf in log_files:
        if lf.name == "logs.json":  # the eval_set manifest, not an EvalLog
            continue
        try:
            log = read_eval_log(lf.as_posix())
        except Exception:
            continue  # not an EvalLog (index / partial file)
        if not getattr(log, "eval", None):
            continue
        meta = (log.eval.metadata or {})
        condition = meta.get("condition")
        model = meta.get("model")
        corpus = meta.get("corpus") or {}
        cohort = meta.get("cohort") or {}
        with_tool = condition in _WITH_TOOL

        overlay_scores = (judge_overlay or {}).get("scores", {})
        by_seed: dict = {}
        for s in (log.samples or []):
            if (s.metadata or {}).get("error"):
                continue  # excluded cell
            seed = int(s.epoch or 1) - 1  # Inspect epochs are 1-based; seed 0-based
            qid = str(s.id)
            score = (s.scores or {}).get("substring_scorer")
            correct = bool(score and score.value == "C")
            ov = overlay_scores.get(f"{condition}|{seed}|{qid}")
            if ov is not None:  # hybrid judge verdict supersedes EM
                correct = bool(ov.get("final"))
            # Real per-call tool-use data (tempdoc 624 §As-built #5 residual-gap
            # close): `agent_utility_inspect.claude_agent_solver` now runs claude
            # with `--output-format stream-json --verbose` (mirroring
            # agent_retrieval_eval.run_agent_eval's exact argv) and stashes the
            # parsed tool_calls + the two derived assertions
            # (find_disallowed_tool_calls / find_leak_suspect_tool_calls) into
            # `state.metadata`. A `None` here (vs. an observed empty `[]`) means
            # this sample predates the fix (an older EvalLog written by the
            # `--output-format json` solver) — `tool_call_assertions` below
            # reports that as "no tool data", never a fabricated zero.
            tool_calls = (s.metadata or {}).get("tool_calls")
            disallowed_tool_calls = (s.metadata or {}).get("disallowed_tool_calls")
            leak_suspect_tool_calls = (s.metadata or {}).get("leak_suspect_tool_calls")
            # Offered MCP tool-surface capture (tempdoc 624 battlefield retrospective):
            # `claude_agent_solver` stashes these alongside tool_calls above. A cell
            # whose surface assertion FAILED already set `error` and was excluded by
            # the `continue` above, so anything reaching here is either surface-clean,
            # surface-unverified, or condition A (exempt, both fields absent -> None).
            by_seed.setdefault(seed, {})[qid] = {
                "correct": correct,
                "cost_usd": (s.metadata or {}).get("cost_usd"),
                "unique_tokens": (s.metadata or {}).get("unique_tokens"),
                "num_turns": (s.metadata or {}).get("num_turns"),
                "tool_calls": tool_calls,
                "disallowed_tool_calls": disallowed_tool_calls,
                "leak_suspect_tool_calls": leak_suspect_tool_calls,
                "leak_suspect": bool(leak_suspect_tool_calls),
                "mcp_servers": (s.metadata or {}).get("mcp_servers"),
                "mcp_tools_offered": (s.metadata or {}).get("mcp_tools_offered"),
                "mcp_surface_unverified": bool((s.metadata or {}).get("mcp_surface_unverified")),
            }

        for seed, per_query in sorted(by_seed.items()):
            manifest = {
                "git_sha": git_sha,
                "cli_version": cohort.get("cli_version"),
                "mcp_tool_surface_hash": cohort.get("mcp_tool_surface_hash"),
                "judge": ((judge_overlay or {}).get("judge_identity")
                          or judge_identity(kind=cohort.get("judge_kind", "substring-em"))),
                "prompt_template_hash": cohort.get("prompt_template_hash"),
                "decoding": {"temperature": 0, "max_tokens": None},
                "eval_limits": {},
                "corpus": corpus, "agent_model": model, "condition": condition, "seed": seed,
                "search_config_cohort_key": (search_config_cohort_key if with_tool else None),
            }
            manifest["agent_cohort_key"] = agent_cohort_key(manifest)
            summaries.append({
                "manifest": manifest, "condition": condition, "agent_model": model,
                "corpus": corpus, "per_query": per_query,
            })
    return summaries


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
        meta = log.eval.metadata or {}
        condition = meta.get("condition")
        for s in (log.samples or []):
            if (s.metadata or {}).get("error"):
                continue  # excluded cell already, not part of any paired stat
            seed = int(s.epoch or 1) - 1
            qid = str(s.id)
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
