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
            by_seed.setdefault(seed, {})[qid] = {
                "correct": correct,
                "cost_usd": (s.metadata or {}).get("cost_usd"),
                "unique_tokens": (s.metadata or {}).get("unique_tokens"),
                "num_turns": (s.metadata or {}).get("num_turns"),
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


_WITH_TOOL = {"B", "C"}
