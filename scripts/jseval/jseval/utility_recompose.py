"""Pure offline finalizer for agent-utility evidence (tempdoc 719).

This module performs no backend, credential, model, or judge calls. All command
surfaces that need a canonical record should converge here.
"""

from __future__ import annotations

import copy
import datetime as dt
import json
from pathlib import Path
from typing import Iterable

from jseval.agent_utility_observations import (
    WITH_TOOL_CONDITIONS,
    all_attempt_tool_call_assertions,
    read_inspect_observations,
    successful_summaries,
)
from jseval.utility_claim_policy import canonical_bytes, canonical_digest
from jseval.utility_comparison import (
    CITED_BASELINES,
    _stats_from_pairs,
    compose_utility,
    compose_utility_cross_corpus,
)
from jseval.utility_governance import (
    loss_accounting_from_observations,
    paired_comparability,
)

_VOLATILE_SEMANTIC_FIELDS = frozenset({"composed_at", "semantic_digest"})

# tempdoc 729 D13/B2 (cross-chain finding, U1 discipline): these fields are
# PURE self-description, never new discriminating information --
# `denominators` (and its mirrored `denominator_note` strings) is a FIXED
# constant, byte-identical across every record regardless of content;
# `seed_floor_met` and `exposure_contrast_ineligible` are deterministic
# re-derivations of fields ALREADY covered by the digest (`seed_count`;
# `measured` emptiness / `cohort.exposure_config` + `mcp_initialize_identity`
# absence, respectively) -- two records that differ in either of these
# necessarily already differ in an already-digested field, so excluding them
# from digest coverage loses no discriminating power. Declared here (a
# coverage rule, not ad hoc per-callsite guessing) so historical-record
# recomposition stays digest-STABLE (tempdoc 725 precedent / tempdoc 729 U1)
# even though the record SHAPE gains these fields -- the digest fingerprints
# the MEASUREMENT, not its self-description.
_NON_SEMANTIC_TOP_LEVEL_FIELDS = frozenset({
    "denominators", "seed_floor_met", "exposure_contrast_ineligible",
})


def semantic_projection(record: dict) -> dict:
    """Return the record with the volatile transport set (`_VOLATILE_SEMANTIC_FIELDS`)
    AND tempdoc 729's purely-declarative self-description fields
    (`_NON_SEMANTIC_TOP_LEVEL_FIELDS` plus their nested `denominator_note`
    mirrors, plus the `seed_floor_met` claim-policy gate -- same
    deterministic-re-derivation rationale, this time of `evaluate_claim`'s
    own gate list rather than of `compose_utility`'s output) removed."""
    projected = copy.deepcopy(record)
    for field in _VOLATILE_SEMANTIC_FIELDS | _NON_SEMANTIC_TOP_LEVEL_FIELDS:
        projected.pop(field, None)
    comparability = projected.get("comparability")
    if isinstance(comparability, dict):
        comparability.pop("denominator_note", None)
    for by_model in (projected.get("measured") or {}).values():
        for cell in (by_model or {}).values():
            funnel = cell.get("funnel") if isinstance(cell, dict) else None
            if isinstance(funnel, dict):
                funnel.pop("denominator_note", None)
    claim_verdict = projected.get("claim_verdict")
    if isinstance(claim_verdict, dict):
        gates = claim_verdict.get("gates")
        if isinstance(gates, list):
            claim_verdict["gates"] = [
                g for g in gates
                if not (isinstance(g, dict) and g.get("name") == "seed_floor_met")
            ]
        for stratum in claim_verdict.get("stratum_outcomes") or []:
            if isinstance(stratum, dict) and isinstance(stratum.get("gates"), dict):
                stratum["gates"].pop("seed_floor_met", None)
    return projected


def semantic_digest(record: dict) -> str:
    return canonical_digest(semantic_projection(record))


def _load_overlay(log_dir: Path, explicit: str | Path | None) -> dict | None:
    path = Path(explicit) if explicit else log_dir / "judge-overlay.json"
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def _namespace_for_loss(observations: Iterable[dict], namespace: str) -> list[dict]:
    out = []
    for observation in observations:
        item = dict(observation)
        item["qid"] = f"{namespace}::{observation.get('qid')}"
        out.append(item)
    return out


def _intention_to_treat_estimand(
    observations: Iterable[dict], *, statistical_alpha: float = 0.05
) -> dict:
    """Compose the primary ITT view; errored attempts count as incorrect/non-adoption."""
    grouped: dict[tuple, dict[str, dict[tuple[int, str], dict]]] = {}
    for observation in observations:
        source = observation.get("source") or {}
        corpus = source.get("corpus") or {}
        cohort = source.get("cohort") or {}
        key = (
            corpus.get("dataset"), corpus.get("signature"), source.get("model_alias"),
            observation.get("resolved_model"),
            canonical_bytes(cohort.get("corpus_certification")),
            # tempdoc 725 increment 2: additively join exposure/instructions
            # identity into the stratum grouping key, the same discipline as
            # agent_cohort_key/the compose_utility mix-guard tuple -- evidence
            # from two differently-configured campaigns must never silently
            # share one ITT stratum.
            (cohort.get("exposure_config") or {}).get("exposure_mode"),
            (cohort.get("mcp_initialize_identity") or {}).get("instructions_sha256"),
        )
        condition = observation.get("condition")
        if condition not in {"A", "B"}:
            continue
        grouped.setdefault(key, {}).setdefault(condition, {})[
            (int(observation.get("seed", 0)), str(observation.get("qid")))
        ] = observation

    strata = []
    for (
        dataset, signature, model, resolved_model, certification_bytes,
        _exposure_mode, _instructions_sha256,
    ), arms in sorted(grouped.items(), key=lambda item: str(item[0])):
        shared = sorted(set(arms.get("A", {})) & set(arms.get("B", {})))
        pairs = {}
        adopted = 0
        usage_complete = True
        per_protocol_pairs = 0
        for seed, qid in shared:
            baseline = arms["A"][(seed, qid)]
            with_tool = arms["B"][(seed, qid)]
            a_excluded = bool(baseline.get("excluded"))
            b_excluded = bool(with_tool.get("excluded"))
            if not a_excluded and not b_excluded:
                per_protocol_pairs += 1
            tool_names = [
                str(call.get("tool"))
                for call in (with_tool.get("tool_calls") or [])
                if isinstance(call, dict) and call.get("tool")
            ]
            adopted += int(any(name.startswith("mcp__justsearch") for name in tool_names))
            values = (
                baseline.get("cost_usd"), with_tool.get("cost_usd"),
                baseline.get("unique_tokens"), with_tool.get("unique_tokens"),
            )
            usage_complete = usage_complete and all(value is not None for value in values)
            pairs[f"{seed}|{qid}"] = {
                "seed": seed,
                "a_correct": bool(baseline.get("correct")) and not a_excluded,
                "c_correct": bool(with_tool.get("correct")) and not b_excluded,
                "a_cost": baseline.get("cost_usd"),
                "c_cost": with_tool.get("cost_usd"),
                "a_tok": baseline.get("unique_tokens"),
                "c_tok": with_tool.get("unique_tokens"),
                "a_turns": baseline.get("num_turns"),
                "c_turns": with_tool.get("num_turns"),
                "a_tool_calls": baseline.get("tool_calls"),
                "c_tool_calls": with_tool.get("tool_calls"),
                "c_toolsearch_targets": with_tool.get("toolsearch_targets"),
                "c_tool_call_sequence": with_tool.get("tool_call_sequence"),
            }
        stats = (
            _stats_from_pairs(pairs, statistical_alpha=statistical_alpha)
            if pairs else None
        )
        if stats and not usage_complete:
            stats["provider_cache_creation_input_tokens"] = {
                "available": False, "reason": "incomplete ITT usage evidence",
            }
            stats["cost_usd"] = {
                "available": False, "reason": "incomplete ITT usage evidence",
            }
        source_cohorts = [
            ((observation.get("source") or {}).get("cohort") or {})
            for arm in arms.values() for observation in arm.values()
        ]
        query_identities = {
            canonical_bytes(item.get("query_identity")) for item in source_cohorts
        }
        campaign_identities = {
            canonical_bytes(item.get("campaign_identity")) for item in source_cohorts
        }
        certifications = {
            canonical_bytes(item.get("corpus_certification")) for item in source_cohorts
        }
        if (
            len(query_identities) != 1
            or len(campaign_identities) != 1
            or len(certifications) != 1
        ):
            raise ValueError(
                "one ITT stratum mixes query, campaign, or corpus-certification identities"
            )
        query_identity = json.loads(next(iter(query_identities)).decode("utf-8"))
        campaign_identity = json.loads(next(iter(campaign_identities)).decode("utf-8"))
        certification = json.loads(next(iter(certifications)).decode("utf-8"))
        if canonical_bytes(certification) != certification_bytes:
            raise ValueError("ITT stratum certification grouping drifted")
        expected_by_arm = {"A": set(), "B": set()}
        for expected_cell in (campaign_identity or {}).get("expected_cells") or []:
            condition, seed, qid = str(expected_cell).split("|", 2)
            if condition in expected_by_arm:
                expected_by_arm[condition].add((int(seed), qid))
        expected_pairs = expected_by_arm["A"] & expected_by_arm["B"]
        seed_ids = sorted({seed for seed, _ in expected_pairs})
        query_ids = sorted({qid for _, qid in expected_pairs})
        member = (certification or {}).get("member")
        size = (certification or {}).get("size")
        query_variant = (certification or {}).get("query_variant")
        stratum_id = (
            f"{member}|{dataset}|{size}|{query_variant}|{model}"
            if member and size and query_variant and model else None
        )
        per_arm_loss = {
            condition: {
                "n_expected": len(expected_by_arm[condition]),
                "n_attempted": len(arms.get(condition, {})),
                "n_completed": sum(
                    not bool(item.get("excluded"))
                    for item in arms.get(condition, {}).values()
                ),
                "n_excluded": sum(
                    bool(item.get("excluded"))
                    for item in arms.get(condition, {}).values()
                ),
                "n_pending": len(
                    expected_by_arm[condition] - set(arms.get(condition, {}))
                ),
                "exclusion_rate": (
                    sum(
                        bool(item.get("excluded"))
                        for item in arms.get(condition, {}).values()
                    ) / len(arms.get(condition, {}))
                    if arms.get(condition) else 0.0
                ),
            }
            for condition in ("A", "B")
        }
        excluded = {
            condition: {
                key for key, item in arms.get(condition, {}).items()
                if bool(item.get("excluded"))
            }
            for condition in ("A", "B")
        }
        excluded_union = excluded["A"] | excluded["B"]
        excluded_jaccard = (
            len(excluded["A"] & excluded["B"]) / len(excluded_union)
            if excluded_union else 1.0
        )
        stratum = {
            "stratum_id": stratum_id,
            "corpus_member": member,
            "corpus": dataset,
            "corpus_size": size,
            "query_variant": query_variant,
            "corpus_signature": signature,
            "model": model,
            "resolved_provider_model": resolved_model,
            "query_identity": query_identity,
            "campaign_identity": campaign_identity,
            "corpus_certification": certification,
            "seed_ids": seed_ids,
            "seed_count": len(seed_ids),
            "query_count": len(query_ids),
            "n_expected_cells": len((campaign_identity or {}).get("expected_cells") or []),
            "n_observed_cells": sum(len(arm) for arm in arms.values()),
            "n_pending_cells": sum(item["n_pending"] for item in per_arm_loss.values()),
            "n_expected_pairs": len(expected_pairs),
            "n_paired_observations": len(shared),
            "n_per_protocol_pairs": per_protocol_pairs,
            "paired_retention": (
                per_protocol_pairs / len(expected_pairs) if expected_pairs else 0.0
            ),
            "excluded_jaccard": excluded_jaccard,
            "per_arm_loss": per_arm_loss,
            "usage_complete": usage_complete,
            "accuracy": (stats or {}).get("accuracy"),
            "provider_cache_creation_input_tokens": (
                (stats or {}).get("provider_cache_creation_input_tokens")
            ),
            "cost_usd": (stats or {}).get("cost_usd"),
            "adoption": {
                "with_tool": {
                    "adopted_cells": adopted,
                    "eligible_cells": len(shared),
                    "adoption_rate": adopted / len(shared) if shared else None,
                }
            },
        }
        # Adoption funnel (tempdoc 725 increment 3): `_stats_from_pairs` computes
        # this from the SAME `pairs` dict built above (which now also carries
        # `c_toolsearch_targets`/`c_tool_call_sequence`), so it is never a second,
        # divergent adoption computation. `_stats_from_pairs` itself OMITS
        # "funnel" (rather than emitting a null-marker dict) when none of this
        # stratum's paired cells carry funnel data at all, so a stratum composed
        # purely from pre-725 evidence has no "funnel" key at all -- byte-identical
        # to before this key existed.
        if stats and "funnel" in stats:
            stratum["funnel"] = stats["funnel"]
        strata.append(stratum)
    return {
        "primary": "intention_to_treat",
        "intention_to_treat": {"strata": strata},
        "per_protocol": {
            "role": "secondary",
            "source": "measured",
        },
    }


def _validate_expected_campaign(observations: list[dict]) -> None:
    expected_sets = {
        tuple((((item.get("source") or {}).get("cohort") or {})
              .get("campaign_identity") or {}).get("expected_cells") or [])
        for item in observations
        if (((item.get("source") or {}).get("cohort") or {}).get("campaign_identity"))
    }
    if not expected_sets:
        return
    if len(expected_sets) != 1:
        raise ValueError("evidence mixes incompatible expected campaign matrices")
    expected_sequence = next(iter(expected_sets))
    expected = set(expected_sequence)
    if len(expected) != len(expected_sequence):
        raise ValueError("expected campaign matrix contains duplicate cells")
    actual = {
        f"{item.get('condition')}|{int(item.get('seed', 0))}|{item.get('qid')}"
        for item in observations
    }
    if expected != actual:
        raise ValueError(
            "evidence does not match the expected campaign matrix: "
            f"missing={sorted(expected - actual)[:5]!r} "
            f"extra={sorted(actual - expected)[:5]!r}"
        )


def finalize_logs(
    log_dirs: Iterable[str | Path],
    *,
    judge_overlays: Iterable[str | Path | None] | None = None,
    composed_at: str | None = None,
    contamination_class: str = "unknown",
    confidence_tier: str = "C",
    readiness=None,
    policy: dict | None = None,
    leaked_cells_by_log: Iterable[set[tuple[str, int, str]] | dict] | None = None,
) -> dict:
    """Recompose completed Inspect logs into one canonical scientific record."""
    roots = [Path(path) for path in log_dirs]
    if not roots:
        raise ValueError("at least one log directory is required")
    overlays = list(judge_overlays or [])
    if overlays and len(overlays) != len(roots):
        raise ValueError("judge_overlays must be empty or match log_dirs one-for-one")
    leak_sets = list(leaked_cells_by_log or [])
    if leak_sets and len(leak_sets) != len(roots):
        raise ValueError("leaked_cells_by_log must be empty or match log_dirs one-for-one")

    observation_groups: list[list[dict]] = []
    for index, root in enumerate(roots):
        overlay = _load_overlay(root, overlays[index] if overlays else None)
        observations = read_inspect_observations(root, judge_overlay=overlay)
        if not observations:
            raise ValueError(f"no Inspect observations found in {root}")
        if leak_sets:
            leaked = leak_sets[index]
            for observation in observations:
                key = (
                    observation.get("condition"),
                    int(observation.get("seed", 0)),
                    str(observation.get("qid")),
                )
                serialized_key = f"{key[0]}|{key[1]}|{key[2]}"
                if key in leaked or serialized_key in leaked:
                    observation["leak_suspect"] = True
        observation_groups.append(observations)
    return finalize_observation_groups(
        observation_groups,
        composed_at=composed_at,
        contamination_class=contamination_class,
        confidence_tier=confidence_tier,
        readiness=readiness,
        policy=policy,
    )


def partial_status_projection(log_dir: str | Path, *, policy: dict | None = None) -> dict:
    """Project a truthful live status through the shared all-attempt seam.

    This deliberately does not emit a claim verdict: partial evidence is not a
    scientific record. It does use the selected policy's loss thresholds so the
    live comparability signal cannot drift from finalization.
    """
    from jseval.utility_claim_policy import load_policy

    selected_policy = policy or load_policy()
    thresholds = selected_policy.get("thresholds") or {}
    observations = read_inspect_observations(log_dir, require_complete=False)
    arms = loss_accounting_from_observations(observations)
    verdict, metrics = paired_comparability(
        arms,
        max_exclusion_rate=thresholds.get("maximum_exclusion_rate", 0.15),
        min_paired_retention=thresholds.get("minimum_paired_retention", 0.70),
        min_excluded_jaccard=thresholds.get("minimum_excluded_jaccard", 0.50),
    )
    result = {
        "per_arm_loss": {
            condition: {
                "n_attempted": loss.n_attempted,
                "n_planned": loss.n_planned,
                "n_pending": loss.n_pending,
                "n_completed": loss.n_completed,
                "n_excluded": loss.n_excluded,
                "exclusion_rate": loss.exclusion_rate,
            }
            for condition, loss in arms.items()
        },
        "comparability": {
            "comparable": verdict.comparable,
            "reasons": verdict.reasons,
            "metrics": metrics,
        },
        "measured": None,
    }
    observed_hashes = {
        row.get("observed_mcp_tool_surface_hash")
        for row in observations
        if row.get("observed_mcp_tool_surface_hash")
    }
    if len(observed_hashes) > 1:
        raise ValueError("partial observations span multiple MCP tools/list surfaces")
    summaries = successful_summaries(
        observations,
        observed_mcp_tool_surface_hash=next(iter(observed_hashes), None),
    )
    if summaries:
        result["measured"] = compose_utility(summaries, composed_at="partial")["measured"]
    return result


def finalize_evidence(
    evidence_paths: Iterable[str | Path],
    *,
    composed_at: str | None = None,
    contamination_class: str = "unknown",
    confidence_tier: str = "C",
    policy: dict | None = None,
) -> dict:
    from jseval.utility_evidence import read_evidence

    groups = [read_evidence(path) for path in evidence_paths]
    return finalize_observation_groups(
        groups,
        composed_at=composed_at,
        contamination_class=contamination_class,
        confidence_tier=confidence_tier,
        policy=policy,
    )


def finalize_observation_groups(
    observation_groups: Iterable[list[dict]],
    *,
    composed_at: str | None = None,
    contamination_class: str = "unknown",
    confidence_tier: str = "C",
    readiness=None,
    policy: dict | None = None,
) -> dict:
    from jseval.utility_claim_policy import evaluate_claim, load_policy

    selected_policy = policy or load_policy()
    thresholds = selected_policy.get("thresholds") or {}
    summaries: list[dict] = []
    loss_observations: list[dict] = []
    all_observations: list[dict] = []
    corpus_identities: set[tuple[str | None, str | None]] = set()
    groups = list(observation_groups)
    observed_surface_hashes = {
        observation.get("observed_mcp_tool_surface_hash")
        for group in groups
        for observation in group
        if observation.get("condition") in WITH_TOOL_CONDITIONS
        and observation.get("observed_mcp_tool_surface_hash")
    }
    if len(observed_surface_hashes) > 1:
        raise ValueError("with-tool observations span multiple MCP tools/list surfaces")
    observed_surface_hash = next(iter(observed_surface_hashes), None)
    declared_surface_hashes = {
        ((observation.get("source") or {}).get("cohort") or {}).get("mcp_tool_surface_hash")
        for group in groups
        for observation in group
        if ((observation.get("source") or {}).get("cohort") or {}).get("mcp_tool_surface_hash")
    }
    captured_surfaces = {
        canonical_bytes(((observation.get("source") or {}).get("cohort") or {}).get("mcp_tool_surface"))
        for group in groups
        for observation in group
        if ((observation.get("source") or {}).get("cohort") or {}).get("mcp_tool_surface")
    }
    if len(captured_surfaces) > 1:
        raise ValueError("evidence spans multiple captured canonical MCP tools/list payloads")
    if captured_surfaces:
        from jseval.agent_manifest import mcp_tool_surface_hash

        captured_surface = json.loads(next(iter(captured_surfaces)).decode("utf-8"))
        captured_hash = mcp_tool_surface_hash(captured_surface)
        if declared_surface_hashes != {captured_hash}:
            raise ValueError("captured canonical MCP tools/list disagrees with declared hash")
    if declared_surface_hashes and (
        len(declared_surface_hashes) != 1
        or (observed_surface_hash is not None
            and next(iter(declared_surface_hashes)) != observed_surface_hash)
    ):
        raise ValueError("declared MCP tool-surface hash disagrees with observed tools/list")

    for raw_group in groups:
        if not raw_group:
            raise ValueError("an evidence group contains no observations")
        _validate_expected_campaign(raw_group)
        # One sanitized evidence file may intentionally carry several corpora.
        # Partition before summary projection so no group silently inherits the
        # first observation's corpus identity.
        by_corpus: dict[tuple[str | None, str | None], list[dict]] = {}
        for observation in raw_group:
            corpus = (observation.get("source") or {}).get("corpus") or {}
            identity = (corpus.get("dataset"), corpus.get("signature"))
            by_corpus.setdefault(identity, []).append(observation)
        for corpus_identity, observations in sorted(by_corpus.items(), key=lambda item: str(item[0])):
            summaries.extend(successful_summaries(
                observations,
                observed_mcp_tool_surface_hash=observed_surface_hash,
            ))
            all_observations.extend(observations)
            corpus_identities.add(corpus_identity)
            namespace = f"{corpus_identity[0]}:{corpus_identity[1]}"
            loss_observations.extend(_namespace_for_loss(observations, namespace))

    arms = loss_accounting_from_observations(loss_observations)
    verdict, metrics = paired_comparability(
        arms,
        readiness,
        max_exclusion_rate=thresholds.get("maximum_exclusion_rate", 0.15),
        min_paired_retention=thresholds.get("minimum_paired_retention", 0.70),
        min_excluded_jaccard=thresholds.get("minimum_excluded_jaccard", 0.50),
    )
    governance = {
        "comparable": verdict.comparable,
        "reasons": verdict.reasons,
        "metrics": metrics,
        "per_arm_loss": {
            condition: {
                "n_attempted": loss.n_attempted,
                "n_planned": loss.n_planned,
                "n_pending": loss.n_pending,
                "n_completed": loss.n_completed,
                "n_excluded": loss.n_excluded,
                "exclusion_rate": round(loss.exclusion_rate, 4),
            }
            for condition, loss in arms.items()
        },
    }
    timestamp = composed_at or dt.datetime.now(dt.timezone.utc).isoformat()
    alpha = thresholds.get("significance_alpha", 0.05)
    kwargs = {
        "composed_at": timestamp,
        "contamination_class": contamination_class,
        "confidence_tier": confidence_tier,
        "governance": governance,
        "external_baselines": CITED_BASELINES,
        "statistical_alpha": alpha,
    }
    if len(corpus_identities) > 1:
        record = compose_utility_cross_corpus(summaries, **kwargs)
    else:
        record = compose_utility(summaries, **kwargs)
    record["tool_call_assertions"] = all_attempt_tool_call_assertions(all_observations)
    record["estimands"] = _intention_to_treat_estimand(
        all_observations, statistical_alpha=alpha
    )
    record["statistical_alpha"] = alpha
    record["claim_verdict"] = evaluate_claim(record, selected_policy)
    record["semantic_digest"] = semantic_digest(record)
    return record


def write_record(record: dict, output_dir: str | Path) -> Path:
    root = Path(output_dir)
    root.mkdir(parents=True, exist_ok=True)
    filename = f"{record['schema']}.json"
    path = root / filename
    path.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return path
