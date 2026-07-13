"""Typed public sanitizer for all-attempt agent-utility observations."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Iterable

from jseval.agent_utility_observations import read_inspect_observations

SCHEMA = "agent-utility-observation.v1"
_OBSERVATION_KEYS = {
    "schema", "condition", "seed", "qid", "attempted", "excluded",
    "error_class", "attempts", "first_error_class", "correct", "cost_usd",
    "provider_usage", "provider_model_usage", "provider_cache_creation_input_tokens",
    "resolved_provider_model", "num_turns", "tool_call_names", "blocked_tool_call_names",
    "disallowed_tool_call_names", "leak_suspect", "mcp_server_statuses",
    "mcp_tools_offered", "mcp_surface_unverified", "mcp_tools_deferred", "source",
    "mcp_tool_names_offered", "observed_mcp_tool_surface_hash",
}
_SOURCE_KEYS = {
    "model_alias", "corpus", "packages", "source_git_sha", "source_git_dirty",
    "cli_version", "mcp_tool_surface_hash", "judge_kind", "prompt_template_hash",
    "decoding", "eval_limits", "search_config_cohort_key", "environment", "corpus_identity",
}


def _error_class(error: Any) -> str | None:
    if error is None:
        return None
    text = str(error).lower()
    for needle, category in (
        ("timeout", "timeout"),
        ("timed out", "timeout"),
        ("wall-clock", "timeout"),
        ("budget", "budget"),
        ("max_turn", "max_turns"),
        ("rate", "rate_limit"),
        ("authentication", "authentication"),
        ("mcp", "tool_surface"),
    ):
        if needle in text:
            return category
    return "executor_error"


def _numeric_tree(value: Any) -> Any:
    if value is None or isinstance(value, (bool, int, float)):
        return value
    if isinstance(value, dict):
        return {
            str(key): cleaned
            for key, item in sorted(value.items(), key=lambda pair: str(pair[0]))
            if (cleaned := _numeric_tree(item)) is not None
        }
    return None


def _tool_names(calls: Any) -> list[str] | None:
    if calls is None:
        return None
    return [str(call.get("tool")) for call in calls if isinstance(call, dict) and call.get("tool")]


def _mcp_statuses(servers: Any) -> list[dict] | None:
    if servers is None:
        return None
    return [
        {"name": str(server.get("name")), "status": str(server.get("status"))}
        for server in servers
        if isinstance(server, dict) and server.get("name")
    ]


def sanitize_observation(observation: dict) -> dict:
    source = observation.get("source") or {}
    cohort = source.get("cohort") or {}
    return {
        "schema": SCHEMA,
        "condition": observation.get("condition"),
        "seed": int(observation.get("seed", 0)),
        "qid": str(observation.get("qid")),
        "attempted": True,
        "excluded": bool(observation.get("excluded")),
        "error_class": _error_class(observation.get("error")),
        "attempts": observation.get("attempts"),
        "first_error_class": _error_class(observation.get("first_error")),
        "correct": bool(observation.get("correct")),
        "cost_usd": observation.get("cost_usd"),
        "provider_usage": _numeric_tree(observation.get("usage")) or {},
        "provider_model_usage": _numeric_tree(observation.get("model_usage")) or {},
        "provider_cache_creation_input_tokens": observation.get("unique_tokens"),
        "resolved_provider_model": observation.get("resolved_model"),
        "num_turns": observation.get("num_turns"),
        "tool_call_names": _tool_names(observation.get("tool_calls")),
        "blocked_tool_call_names": _tool_names(observation.get("tool_calls_blocked")),
        "disallowed_tool_call_names": _tool_names(observation.get("disallowed_tool_calls")),
        "leak_suspect": bool(observation.get("leak_suspect")),
        "mcp_server_statuses": _mcp_statuses(observation.get("mcp_servers")),
        "mcp_tools_offered": observation.get("mcp_tools_offered"),
        "mcp_tool_names_offered": observation.get("mcp_tool_names_offered"),
        "observed_mcp_tool_surface_hash": observation.get("observed_mcp_tool_surface_hash"),
        "mcp_surface_unverified": bool(observation.get("mcp_surface_unverified")),
        "mcp_tools_deferred": observation.get("mcp_tools_deferred"),
        "source": {
            "model_alias": source.get("model_alias"),
            "corpus": source.get("corpus") or {},
            "packages": source.get("packages") or {},
            "source_git_sha": cohort.get("source_git_sha") or cohort.get("git_sha"),
            "source_git_dirty": cohort.get("source_git_dirty"),
            "cli_version": cohort.get("cli_version"),
            "mcp_tool_surface_hash": cohort.get("mcp_tool_surface_hash"),
            "judge_kind": cohort.get("judge_kind"),
            "prompt_template_hash": cohort.get("prompt_template_hash"),
            "decoding": cohort.get("decoding") or {},
            "eval_limits": cohort.get("eval_limits") or {},
            "search_config_cohort_key": cohort.get("search_config_cohort_key"),
            "environment": cohort.get("environment"),
            "corpus_identity": cohort.get("corpus_identity"),
        },
    }


def sanitize_observations(observations: Iterable[dict]) -> list[dict]:
    sanitized = [sanitize_observation(observation) for observation in observations]
    return sorted(
        sanitized,
        key=lambda item: (str(item["condition"]), item["seed"], item["qid"]),
    )


def export_log_dir(log_dir: str | Path, output: str | Path) -> Path:
    observations = sanitize_observations(read_inspect_observations(log_dir))
    path = Path(output)
    path.parent.mkdir(parents=True, exist_ok=True)
    body = "".join(
        json.dumps(item, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n"
        for item in observations
    )
    path.write_text(body, encoding="utf-8")
    return path


def read_evidence(path: str | Path) -> list[dict]:
    observations = []
    for line_number, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        item = json.loads(line)
        if item.get("schema") != SCHEMA:
            raise ValueError(f"unsupported observation schema at line {line_number}")
        unknown = set(item) - _OBSERVATION_KEYS
        if unknown:
            raise ValueError(f"unknown observation fields at line {line_number}: {sorted(unknown)}")
        source = item.get("source") or {}
        unknown_source = set(source) - _SOURCE_KEYS
        if unknown_source:
            raise ValueError(
                f"unknown observation source fields at line {line_number}: {sorted(unknown_source)}"
            )
        cohort = {
            key: source.get(key)
            for key in (
                "source_git_sha", "source_git_dirty", "cli_version",
                "mcp_tool_surface_hash", "judge_kind", "prompt_template_hash",
                "decoding", "eval_limits", "search_config_cohort_key",
                "environment", "corpus_identity",
            )
        }
        observations.append({
            "source": {
                "log_file": Path(path).name,
                "model_alias": source.get("model_alias"),
                "corpus": source.get("corpus") or {},
                "cohort": cohort,
                "packages": source.get("packages") or {},
            },
            "condition": item.get("condition"),
            "seed": item.get("seed"),
            "qid": item.get("qid"),
            "attempted": True,
            "excluded": bool(item.get("excluded")),
            "error": item.get("error_class"),
            "attempts": item.get("attempts"),
            "first_error": item.get("first_error_class"),
            "correct": bool(item.get("correct")),
            "cost_usd": item.get("cost_usd"),
            "unique_tokens": item.get("provider_cache_creation_input_tokens"),
            "usage": item.get("provider_usage") or {},
            "model_usage": item.get("provider_model_usage") or {},
            "resolved_model": item.get("resolved_provider_model"),
            "num_turns": item.get("num_turns"),
            "tool_calls": ([{"tool": name} for name in item["tool_call_names"]]
                           if item.get("tool_call_names") is not None else None),
            "tool_calls_blocked": ([{"tool": name} for name in item["blocked_tool_call_names"]]
                                   if item.get("blocked_tool_call_names") is not None else None),
            "disallowed_tool_calls": [
                {"tool": name} for name in item.get("disallowed_tool_call_names") or []
            ],
            "leak_suspect_tool_calls": [],
            "leak_suspect": bool(item.get("leak_suspect")),
            "mcp_servers": item.get("mcp_server_statuses"),
            "mcp_tools_offered": item.get("mcp_tools_offered"),
            "mcp_tool_names_offered": item.get("mcp_tool_names_offered"),
            "observed_mcp_tool_surface_hash": item.get("observed_mcp_tool_surface_hash"),
            "mcp_surface_unverified": bool(item.get("mcp_surface_unverified")),
            "mcp_tools_deferred": item.get("mcp_tools_deferred"),
        })
    return observations
