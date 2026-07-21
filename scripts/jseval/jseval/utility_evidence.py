"""Typed public sanitizer for all-attempt agent-utility observations."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Iterable

from jseval.agent_utility_observations import read_inspect_observations
from jseval.env_fingerprint import safe_environment_identity

SCHEMA = "agent-utility-observation.v1"
_OBSERVATION_KEYS = {
    "schema", "condition", "seed", "qid", "attempted", "excluded",
    "error_class", "attempts", "first_error_class", "correct", "cost_usd",
    "working_time", "total_time",
    "provider_usage", "provider_model_usage", "provider_cache_creation_input_tokens",
    "resolved_provider_model", "num_turns", "tool_call_names", "blocked_tool_call_names",
    "disallowed_tool_call_names", "leak_suspect", "mcp_server_statuses",
    "mcp_tools_offered", "mcp_surface_unverified", "mcp_tools_deferred", "source",
    "mcp_tool_names_offered", "observed_mcp_tool_surface_hash",
    "surface_evidence", "mcp_surface_fallback",
    "toolsearch_targets", "tool_call_sequence", "tool_result_digests",
}
_SURFACE_EVIDENCE_KINDS = {"status", "status-retry", "fallback-listing"}
_SOURCE_KEYS = {
    "model_alias", "corpus", "packages", "source_git_sha", "source_git_dirty", "source_git_state",
    "cli_version", "mcp_tool_surface_hash", "mcp_tool_surface", "judge_kind", "prompt_template_hash",
    "decoding", "eval_limits", "search_config_cohort_key", "environment", "corpus_identity",
    "corpus_certification", "query_identity", "campaign_identity",
    "exposure_config", "mcp_initialize_identity",
}


def _error_class(error: Any) -> str | None:
    if error is None:
        return None
    text = str(error).lower()
    # tempdoc 624 (2026-07-17): the two resource-EXHAUSTION shapes bucket to
    # DISTINCT categories the raw markers uniquely produce, so `classify_error_kind`
    # can recover exhaustion on the evidence-recompose path exactly as it does on
    # the raw-logs path (otherwise the exhaustion-as-failure rule silently no-ops
    # through sanitize->evidence, and raw vs evidence composes disagree on the
    # semantic_digest -- the publication builder's evidence-recompose check). A
    # GENERIC infra timeout/budget still buckets below and stays `other`.
    if "per-cell wall-clock budget exhausted" in text:
        return "wall_clock_budget_exhausted"
    if "error_max_budget_usd" in text:
        return "usd_budget_exhausted"
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


def _toolsearch_targets(names: Any) -> list[str] | None:
    if names is None:
        return None
    return [str(name) for name in names]


def _tool_call_sequence(sequence: Any) -> list[dict] | None:
    if sequence is None:
        return None
    return [
        {"name": str(item.get("name")), "status": str(item.get("status"))}
        for item in sequence
        if isinstance(item, dict)
    ]


_DELIVERED_TIERS = {"structured-json", "prose", "blocks"}
_DELIVERED_FIELD_KEYS = (
    "quality", "matchedTerms", "degradation", "excerpts", "citations", "searchTrace", "results",
)


def _tool_result_digests(value: Any) -> list[dict] | None:
    """tempdoc 736 D9 (extended by tempdoc 735 G2): pass through only the seven
    declared digest fields, never a raw-content key, even if one were ever
    (mistakenly) present upstream -- this projection is itself part of the leak
    boundary, not just the schema.

    `furniture_markers` and `delivered_fields` are each either a dict of the
    declared booleans OR `None` -- `None` is preserved as-is (never coerced to an
    all-False dict), because for a `structured-json` delivery `furniture_markers`
    is genuinely not-applicable (the text-grep tier was never delivered) and for
    a `prose`/`blocks` delivery `delivered_fields` is genuinely not-applicable
    (nothing was parsed). Historical entries captured before tempdoc 735 lack
    `delivered_tier`/`delivered_fields` entirely (`item.get(...)` -> `None`),
    which is the schema-optional case, not an error."""
    if value is None:
        return None
    digests: list[dict] = []
    for item in value:
        if not isinstance(item, dict):
            continue
        markers = item.get("furniture_markers")
        markers_out = (
            {
                "rationale": bool(markers.get("rationale")),
                "evidence_pack": bool(markers.get("evidence_pack")),
                "coverage": bool(markers.get("coverage")),
                "degradation": bool(markers.get("degradation")),
            }
            if isinstance(markers, dict) else None
        )
        delivered_fields = item.get("delivered_fields")
        delivered_fields_out = (
            {key: bool(delivered_fields.get(key)) for key in _DELIVERED_FIELD_KEYS}
            if isinstance(delivered_fields, dict) else None
        )
        tier = item.get("delivered_tier")
        digests.append({
            "content_sha256": item.get("content_sha256"),
            "content_len": item.get("content_len"),
            "content_is_error": item.get("content_is_error"),
            "content_shape": item.get("content_shape"),
            "furniture_markers": markers_out,
            "delivered_tier": tier if tier in _DELIVERED_TIERS else None,
            "delivered_fields": delivered_fields_out,
        })
    return digests


def _surface_evidence(value: Any) -> str | None:
    """tempdoc 755 Track 1: pass through only a declared surface-evidence kind, else null
    (an unknown/garbled kind is dropped to null, the unverified case, never invented)."""
    return value if value in _SURFACE_EVIDENCE_KINDS else None


def _mcp_surface_fallback(value: Any) -> dict | None:
    """tempdoc 755 Track 1 item 2: pass through only the three declared cross-check keys.
    `verified` is coerced to bool (always false in practice -- the integrity rule forbids
    verifying from execution alone); the subset flag preserves its tri-state None."""
    if not isinstance(value, dict):
        return None
    subset = value.get("executed_justsearch_subset_of_declared")
    reason = value.get("reason")
    return {
        "executed_justsearch_subset_of_declared": None if subset is None else bool(subset),
        "verified": bool(value.get("verified")),
        "reason": None if reason is None else str(reason),
    }


def _exposure_config(value: Any) -> dict | None:
    if not isinstance(value, dict):
        return None
    return {
        "enable_tool_search": value.get("enable_tool_search"),
        "always_load": value.get("always_load"),
        "exposure_mode": value.get("exposure_mode"),
    }


def _mcp_initialize_identity(value: Any) -> dict | None:
    if not isinstance(value, dict):
        return None
    return {
        "instructions": value.get("instructions"),
        "instructions_sha256": value.get("instructions_sha256"),
        "server_version": value.get("server_version"),
        "protocol_version": value.get("protocol_version"),
    }


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
        # tempdoc 624 (2026-07-17 duration axis): per-sample wall-clock, carried
        # through the sanitized evidence so an offline replay recomposes the
        # duration metric family without re-reading the ephemeral Inspect log.
        "working_time": observation.get("working_time"),
        "total_time": observation.get("total_time"),
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
        "surface_evidence": _surface_evidence(observation.get("surface_evidence")),
        "mcp_surface_fallback": _mcp_surface_fallback(observation.get("mcp_surface_fallback")),
        "mcp_tools_deferred": observation.get("mcp_tools_deferred"),
        "toolsearch_targets": _toolsearch_targets(observation.get("toolsearch_targets")),
        "tool_call_sequence": _tool_call_sequence(observation.get("tool_call_sequence")),
        "tool_result_digests": _tool_result_digests(observation.get("tool_result_digests")),
        "source": {
            "model_alias": source.get("model_alias"),
            "corpus": source.get("corpus") or {},
            "packages": source.get("packages") or {},
            "source_git_sha": cohort.get("source_git_sha") or cohort.get("git_sha"),
            "source_git_dirty": cohort.get("source_git_dirty"),
            "source_git_state": cohort.get("source_git_state"),
            "cli_version": cohort.get("cli_version"),
            "mcp_tool_surface_hash": cohort.get("mcp_tool_surface_hash"),
            "mcp_tool_surface": cohort.get("mcp_tool_surface"),
            "judge_kind": cohort.get("judge_kind"),
            "prompt_template_hash": cohort.get("prompt_template_hash"),
            "decoding": cohort.get("decoding") or {},
            "eval_limits": cohort.get("eval_limits") or {},
            "search_config_cohort_key": cohort.get("search_config_cohort_key"),
            "environment": safe_environment_identity(cohort.get("environment") or {}),
            "corpus_identity": cohort.get("corpus_identity"),
            "corpus_certification": cohort.get("corpus_certification"),
            "query_identity": cohort.get("query_identity"),
            "campaign_identity": cohort.get("campaign_identity"),
            "exposure_config": _exposure_config(cohort.get("exposure_config")),
            "mcp_initialize_identity": _mcp_initialize_identity(cohort.get("mcp_initialize_identity")),
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
                "source_git_sha", "source_git_dirty", "source_git_state", "cli_version",
                "mcp_tool_surface_hash", "mcp_tool_surface", "judge_kind", "prompt_template_hash",
                "decoding", "eval_limits", "search_config_cohort_key",
                "environment", "corpus_identity",
                "corpus_certification",
                "query_identity", "campaign_identity",
                "exposure_config", "mcp_initialize_identity",
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
            "working_time": item.get("working_time"),
            "total_time": item.get("total_time"),
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
            "surface_evidence": item.get("surface_evidence"),
            "mcp_surface_fallback": item.get("mcp_surface_fallback"),
            "mcp_tools_deferred": item.get("mcp_tools_deferred"),
            "toolsearch_targets": item.get("toolsearch_targets"),
            "tool_call_sequence": item.get("tool_call_sequence"),
            "tool_result_digests": item.get("tool_result_digests"),
        })
    return observations
