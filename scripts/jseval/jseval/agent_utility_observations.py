"""Typed, all-attempt Inspect observation seam for agent utility evaluation.

The public replay and the internal validity calculation must start from the
same attempted cells.  Summary-only projections deliberately omit failures;
this module keeps failures and then derives the valid-only composer view from
that lossless input.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Iterable

WITH_TOOL_CONDITIONS = frozenset({"B", "C"})


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
    require_complete: bool = True,
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

    ignored_json = {
        "eval-set.json", "logs.json", "source-identity.v1.json",
        "judge-overlay.json",
    }
    for path in files:
        if path.name in ignored_json:
            continue
        try:
            log = read_eval_log(path.as_posix())
        except Exception as exc:
            raise ValueError(f"failed to read candidate EvalLog {path}") from exc
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
                "toolsearch_targets": metadata.get("toolsearch_targets"),
                "tool_call_sequence": metadata.get("tool_call_sequence"),
                # tempdoc 729 D9: evidence-tier only -- deliberately NOT threaded
                # into `successful_summaries`'s per_query composer projection below
                # (out of Chain A's scope: composition/comparison lives in
                # utility_comparison.py, which this tempdoc's Chain A does not
                # touch). This keeps the composed-record semantic_digest (U1)
                # untouched by construction.
                "tool_result_digests": metadata.get("tool_result_digests"),
            })
    if require_complete:
        expected_sets = {
            tuple(((item.get("source") or {}).get("cohort") or {})
                  .get("campaign_identity", {}).get("expected_cells") or [])
            for item in observations
            if (((item.get("source") or {}).get("cohort") or {})
                .get("campaign_identity"))
        }
        if len(expected_sets) > 1:
            raise ValueError("observations mix incompatible expected campaign matrices")
        if expected_sets:
            expected = set(next(iter(expected_sets)))
            actual = {
                f"{item.get('condition')}|{int(item.get('seed', 0))}|{item.get('qid')}"
                for item in observations
            }
            missing = sorted(expected - actual)
            extra = sorted(actual - expected)
            if missing or extra:
                raise ValueError(
                    "agent-utility evidence does not match the expected campaign matrix: "
                    f"missing={missing[:5]!r} extra={extra[:5]!r}"
                )
    return observations


def successful_summaries(
    observations: Iterable[dict],
    *,
    judge_overlay: dict | None = None,
    observed_mcp_tool_surface_hash: str | None = None,
) -> list[dict]:
    """Project all-attempt observations into the existing valid-cell composer shape."""
    from jseval.agent_manifest import agent_cohort_key, judge_identity
    from jseval.env_fingerprint import safe_environment_identity
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
                "toolsearch_targets", "tool_call_sequence",
            )
        }
        if obs.get("resolved_model"):
            entry["resolved_models"].add(obs["resolved_model"])

    summaries: list[dict] = []
    for _, group in sorted(grouped.items(), key=lambda item: str(item[0])):
        source = group["source"]
        cohort = source.get("cohort") or {}
        condition = group["condition"]
        with_tool = condition in WITH_TOOL_CONDITIONS
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
            "source_git_state": cohort.get("source_git_state"),
            "cli_version": cohort.get("cli_version"),
            "mcp_tool_surface_hash": observed_mcp_tool_surface_hash,
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
                captured_search_key if with_tool else None),
            "identity_source": (
                "captured" if captured_search_key or not with_tool else
                "missing"),
            "hardware": cohort.get("hardware"),
            "environment": safe_environment_identity(cohort.get("environment") or {}),
            "corpus_identity": cohort.get("corpus_identity"),
            "corpus_certification": cohort.get("corpus_certification"),
            "query_identity": cohort.get("query_identity"),
            "campaign_identity": cohort.get("campaign_identity"),
            "exposure_config": cohort.get("exposure_config"),
            "mcp_initialize_identity": cohort.get("mcp_initialize_identity"),
            "exposure_mode": (cohort.get("exposure_config") or {}).get("exposure_mode"),
            "instructions_sha256": (
                (cohort.get("mcp_initialize_identity") or {}).get("instructions_sha256")
            ),
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


def all_attempt_tool_call_assertions(observations: Iterable[dict]) -> dict:
    """Roll up negative and positive tool evidence over every attempted cell."""
    by_condition: dict[str, dict] = {}
    for observation in observations:
        condition = observation.get("condition")
        if condition is None:
            continue
        aggregate = by_condition.setdefault(condition, {
            "cells_total": 0,
            "cells_excluded": 0,
            "cells_with_tool_data": 0,
            "cells_with_disallowed_violations": 0,
            "cells_with_leak_suspect": 0,
            "cells_with_mcp_surface_verified": 0,
            "cells_mcp_surface_unverified": 0,
            "observed_mcp_tool_surface_hashes": set(),
            "cells_with_exposure_mode_verified": 0,
            "observed_exposure_modes": set(),
            # Private bookkeeping, popped before return -- tracks whether ANY
            # observation in this condition ever captured exposure identity at
            # all, so the three exposure_* keys above can be OMITTED entirely
            # (not merely zero-valued) for a condition composed purely from
            # pre-725 evidence (tempdoc 725 increment 2: this rollup must not
            # change shape for evidence that never captured this).
            "_exposure_config_seen": False,
        })
        aggregate["cells_total"] += 1
        if observation.get("excluded"):
            aggregate["cells_excluded"] += 1
        if observation.get("tool_calls") is not None:
            aggregate["cells_with_tool_data"] += 1
        if observation.get("disallowed_tool_calls"):
            aggregate["cells_with_disallowed_violations"] += 1
        if observation.get("leak_suspect"):
            aggregate["cells_with_leak_suspect"] += 1
        offered = observation.get("mcp_tools_offered")
        observed_hash = observation.get("observed_mcp_tool_surface_hash")
        if offered is not None and offered > 0 and observed_hash:
            aggregate["cells_with_mcp_surface_verified"] += 1
        if observation.get("mcp_surface_unverified") or (
            condition in WITH_TOOL_CONDITIONS and not observation.get("excluded") and not observed_hash
        ):
            aggregate["cells_mcp_surface_unverified"] += 1
        if observed_hash:
            aggregate["observed_mcp_tool_surface_hashes"].add(observed_hash)
        # Exposure-mode consistency (tempdoc 725 increment 2 claim-policy gate
        # `verified_exposure_mode`): `exposure_config` is cohort-level (same
        # source.cohort dict on every observation of one campaign), so this is a
        # mix-detection check, same spirit as the tool-surface-hash consistency
        # check above -- it catches evidence merged from two differently-
        # configured campaigns, not per-cell variance (there is none to observe;
        # exposure_mode is derived from config, never from a per-cell SDK signal).
        exposure_config = ((observation.get("source") or {}).get("cohort") or {}).get(
            "exposure_config")
        if exposure_config is not None:
            aggregate["_exposure_config_seen"] = True
        exposure_mode = (exposure_config or {}).get("exposure_mode")
        if exposure_mode in ("eager", "deferred"):
            aggregate["cells_with_exposure_mode_verified"] += 1
        if exposure_mode:
            aggregate["observed_exposure_modes"].add(exposure_mode)

    for aggregate in by_condition.values():
        hashes = aggregate["observed_mcp_tool_surface_hashes"]
        aggregate["observed_mcp_tool_surface_hashes"] = sorted(hashes)
        aggregate["observed_mcp_tool_surface_consistent"] = bool(hashes) and len(hashes) == 1
        exposure_config_seen = aggregate.pop("_exposure_config_seen")
        modes = aggregate.pop("observed_exposure_modes")
        cells_with_exposure_mode_verified = aggregate.pop("cells_with_exposure_mode_verified")
        if exposure_config_seen:
            aggregate["cells_with_exposure_mode_verified"] = cells_with_exposure_mode_verified
            aggregate["observed_exposure_modes"] = sorted(modes)
            aggregate["observed_exposure_mode_consistent"] = bool(modes) and len(modes) == 1
    return by_condition
