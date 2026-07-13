"""Typed, all-attempt Inspect observation seam for agent utility evaluation.

The public replay and the internal validity calculation must start from the
same attempted cells.  Summary-only projections deliberately omit failures;
this module keeps failures and then derives the valid-only composer view from
that lossless input.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Iterable

_WITH_TOOL = {"B", "C"}


def _error_text(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, str):
        return value
    return str(value)


def _normalized_qid(raw_id: Any, condition: str | None) -> str:
    raw = str(raw_id)
    prefix = f"{condition}|" if condition else None
    return raw[len(prefix):] if prefix and raw.startswith(prefix) else raw


def read_inspect_observations(
    log_dir: str | Path,
    *,
    judge_overlay: dict | None = None,
) -> list[dict]:
    """Read every flushed Inspect sample into a stable observation dictionary.

    Imports Inspect lazily so deterministic public replay over an already
    exported observation set does not require the optional agent dependency.
    """
    from inspect_ai.log import read_eval_log

    observations: list[dict] = []
    root = Path(log_dir)
    files = sorted(root.glob("*.eval")) + sorted(root.glob("*.json"))
    overlay_scores = (judge_overlay or {}).get("scores", {})

    for path in files:
        if path.name in {"eval-set.json", "logs.json"}:
            continue
        try:
            log = read_eval_log(path.as_posix())
        except Exception:
            continue
        if not getattr(log, "eval", None):
            continue

        task_meta = log.eval.metadata or {}
        task_condition = task_meta.get("condition")
        source = {
            "log_file": path.name,
            "model_alias": task_meta.get("model"),
            "corpus": task_meta.get("corpus") or {},
            "cohort": task_meta.get("cohort") or {},
            "packages": getattr(log.eval, "packages", None),
            "source_identity": task_meta.get("source_identity") or {},
        }

        for sample in log.samples or []:
            metadata = sample.metadata or {}
            condition = metadata.get("condition") or task_condition
            seed = int(sample.epoch or 1) - 1
            qid = _normalized_qid(sample.id, condition)
            native_error = getattr(sample, "error", None)
            error = _error_text(metadata.get("error") or native_error)
            score = (sample.scores or {}).get("substring_scorer")
            correct = bool(score and score.value == "C")
            overlay = overlay_scores.get(f"{condition}|{seed}|{qid}")
            if overlay is not None:
                correct = bool(overlay.get("final"))

            tool_calls = metadata.get("tool_calls")
            disallowed = metadata.get("disallowed_tool_calls")
            leak_calls = metadata.get("leak_suspect_tool_calls")
            observations.append({
                "source": source,
                "condition": condition,
                "seed": seed,
                "qid": qid,
                "attempted": True,
                "excluded": error is not None,
                "error": error,
                "attempts": metadata.get("attempts"),
                "first_error": metadata.get("first_error"),
                "correct": correct,
                "cost_usd": metadata.get("cost_usd"),
                "unique_tokens": metadata.get("unique_tokens"),
                "usage": metadata.get("usage"),
                "model_usage": metadata.get("model_usage"),
                "num_turns": metadata.get("num_turns"),
                "tool_calls": tool_calls,
                "tool_calls_blocked": metadata.get("tool_calls_blocked"),
                "disallowed_tool_calls": disallowed,
                "leak_suspect_tool_calls": leak_calls,
                "leak_suspect": bool(leak_calls),
                "mcp_servers": metadata.get("mcp_servers"),
                "mcp_tools_offered": metadata.get("mcp_tools_offered"),
                "mcp_tool_names_offered": metadata.get("mcp_tool_names_offered"),
                "observed_mcp_tool_surface_hash": metadata.get("observed_mcp_tool_surface_hash"),
                "mcp_surface_unverified": bool(metadata.get("mcp_surface_unverified")),
                "mcp_tools_deferred": metadata.get("mcp_tools_deferred"),
                "resolved_model": metadata.get("resolved_model"),
            })
    return observations


def successful_summaries(
    observations: Iterable[dict],
    *,
    judge_overlay: dict | None = None,
    search_config_cohort_key: str | None = None,
) -> list[dict]:
    """Project all-attempt observations into the existing valid-cell composer shape."""
    from jseval.agent_manifest import agent_cohort_key, judge_identity
    from jseval.manifest import _sha256_canonical

    overlay_judge = dict((judge_overlay or {}).get("judge_identity") or {})
    if overlay_judge and (judge_overlay or {}).get("human_calibration") is not None:
        overlay_judge["calibration_hash"] = _sha256_canonical(
            judge_overlay["human_calibration"]
        )

    grouped: dict[tuple, dict] = {}
    for obs in observations:
        if obs.get("excluded"):
            continue
        source = obs.get("source") or {}
        cohort = source.get("cohort") or {}
        condition = obs.get("condition")
        seed = int(obs.get("seed", 0))
        group_key = (source.get("log_file"), condition, seed)
        entry = grouped.setdefault(group_key, {
            "source": source,
            "condition": condition,
            "seed": seed,
            "per_query": {},
            "resolved_models": set(),
        })
        qid = str(obs.get("qid"))
        if qid in entry["per_query"]:
            raise ValueError(
                f"duplicate agent-utility observation for condition={condition} seed={seed} qid={qid}")
        entry["per_query"][qid] = {
            key: obs.get(key)
            for key in (
                "correct", "cost_usd", "unique_tokens", "usage", "model_usage", "num_turns",
                "tool_calls", "tool_calls_blocked", "disallowed_tool_calls",
                "leak_suspect_tool_calls", "leak_suspect", "mcp_servers",
                "mcp_tools_offered", "mcp_surface_unverified", "mcp_tools_deferred",
                "mcp_tool_names_offered", "observed_mcp_tool_surface_hash",
            )
        }
        if obs.get("resolved_model"):
            entry["resolved_models"].add(obs["resolved_model"])

    summaries: list[dict] = []
    for _, group in sorted(grouped.items(), key=lambda item: str(item[0])):
        source = group["source"]
        cohort = source.get("cohort") or {}
        condition = group["condition"]
        with_tool = condition in _WITH_TOOL
        resolved_models = group["resolved_models"]
        if len(resolved_models) > 1:
            raise ValueError(
                f"mixed resolved provider models for condition={condition} "
                f"seed={group['seed']}: {sorted(resolved_models)!r}"
            )
        resolved_model = next(iter(resolved_models), None)
        captured_search_key = cohort.get("search_config_cohort_key")
        manifest = {
            "git_sha": cohort.get("source_git_sha") or cohort.get("git_sha"),
            "git_dirty": cohort.get("source_git_dirty"),
            "cli_version": cohort.get("cli_version"),
            "mcp_tool_surface_hash": cohort.get("mcp_tool_surface_hash"),
            "judge": (overlay_judge
                      or judge_identity(kind=cohort.get("judge_kind", "substring-em"))),
            "prompt_template_hash": cohort.get("prompt_template_hash"),
            "decoding": cohort.get("decoding") or {"temperature": 0, "max_tokens": None},
            "eval_limits": cohort.get("eval_limits") or {},
            "corpus": source.get("corpus") or {},
            "agent_model": source.get("model_alias"),
            "agent_model_version": resolved_model,
            "condition": condition,
            "seed": group["seed"],
            "search_config_cohort_key": (
                (captured_search_key or search_config_cohort_key) if with_tool else None),
            "identity_source": (
                "captured" if captured_search_key or not with_tool else
                "replay-override" if search_config_cohort_key else "missing"),
            "hardware": cohort.get("hardware"),
            "environment": cohort.get("environment"),
            "corpus_identity": cohort.get("corpus_identity"),
        }
        manifest["agent_cohort_key"] = agent_cohort_key(manifest)
        summaries.append({
            "manifest": manifest,
            "condition": condition,
            "agent_model": source.get("model_alias"),
            "corpus": source.get("corpus") or {},
            "per_query": group["per_query"],
            "executor": "inspect-ai",
        })
    return summaries
