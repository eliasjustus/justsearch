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

from jseval.agent_behavioral import aggregate_behavioral
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
    OTHER_ERROR,
    RESOURCE_EXHAUSTION,
    classify_error_kind,
    loss_accounting_from_observations,
    paired_comparability,
)

# tempdoc 624 (2026-07-17): the primary ITT outcome rule this recompose applies,
# stamped on every composed record so a hostile reviewer sees WHICH rule scored
# the numbers and WHEN it was adopted relative to the data. A byte-identical
# constant -- pure self-description -- so it is excluded from the semantic digest
# (see `_NON_SEMANTIC_TOP_LEVEL_FIELDS`): the digest fingerprints the
# MEASUREMENT (which the rule changes via the numbers), not the rule's label.
OUTCOME_RULE = {
    "name": "resource-exhaustion-as-failure",
    "adopted": "2026-07-17",
    "post_hoc_for": ["step2 campaign logs composed 2026-07-17"],
}

_VOLATILE_SEMANTIC_FIELDS = frozenset({"composed_at", "semantic_digest"})

# tempdoc 736 D13/B2 (cross-chain finding, U1 discipline): these fields are
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
# recomposition stays digest-STABLE (tempdoc 725 precedent / tempdoc 736 U1)
# even though the record SHAPE gains these fields -- the digest fingerprints
# the MEASUREMENT, not its self-description.
_NON_SEMANTIC_TOP_LEVEL_FIELDS = frozenset({
    "denominators", "seed_floor_met", "exposure_contrast_ineligible",
    # tempdoc 624 (2026-07-17): the `outcome_rule` provenance stamp is a fixed
    # constant (byte-identical on every record) -- pure self-description, never
    # discriminating measurement content -- so it is excluded from the digest by
    # the same rationale as `denominators` (the numbers the rule produces ARE
    # digested; two records under different rules necessarily differ in those).
    "outcome_rule",
    # tempdoc 789 Phase 1 item 4: the `behavioral` block is a DESCRIPTIVE projection
    # of the observations (continuation-survival telemetry). No gate, verdict,
    # comparability rule or estimand reads it -- it is attached AFTER `claim_verdict`
    # is computed, so it cannot reach one by construction. Excluded from the digest so
    # every historical record recomposes byte-stably (the publication builder verifies
    # `semantic_digest(recomposed) == semantic_digest(stored)`), exactly as the 755
    # optional-field precedent required. When Phase 2 gives it a DECIDING consumer, it
    # moves into the digest as a declared schema change -- not silently.
    "behavioral",
})


def semantic_projection(record: dict) -> dict:
    """Return the record with the volatile transport set (`_VOLATILE_SEMANTIC_FIELDS`)
    AND tempdoc 736's purely-declarative self-description fields
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
    observations = list(observations)

    def _coarse_key(observation: dict) -> tuple:
        source = observation.get("source") or {}
        corpus = source.get("corpus") or {}
        cohort = source.get("cohort") or {}
        return (
            corpus.get("dataset"), corpus.get("signature"), source.get("model_alias"),
            canonical_bytes(cohort.get("corpus_certification")),
            # tempdoc 725 increment 2: exposure/instructions identity joins the
            # stratum key -- evidence from two differently-configured campaigns
            # must never silently share one ITT stratum.
            (cohort.get("exposure_config") or {}).get("exposure_mode"),
            (cohort.get("mcp_initialize_identity") or {}).get("instructions_sha256"),
        )

    # tempdoc 624 (2026-07-17): a resource-exhaustion cell can terminate BEFORE
    # the provider model version resolves, leaving `resolved_model=None`. Under
    # the exhaustion-as-failure rule that cell MUST be scored in its arm's
    # stratum, but `None` is not a different model VERSION -- it is absence. So a
    # `None`-resolved cell inherits the coarse group's resolved model WHEN that
    # group has exactly one concrete value (the identity guard still splits a
    # genuine multi-version pool: >1 concrete value keeps each cell's own
    # `resolved_model`, and an all-`None` group -- pre-resolution evidence --
    # stays `None`). Without this, wall-clock timeouts orphan into a `None`
    # stratum and the failure rule silently no-ops on exactly the cells it exists
    # to capture.
    concrete_by_coarse: dict[tuple, set] = {}
    for observation in observations:
        resolved = observation.get("resolved_model")
        if resolved is not None:
            concrete_by_coarse.setdefault(_coarse_key(observation), set()).add(resolved)

    def _canonical_resolved(observation: dict) -> object:
        resolved = observation.get("resolved_model")
        if resolved is not None:
            return resolved
        concrete = concrete_by_coarse.get(_coarse_key(observation)) or set()
        return next(iter(concrete)) if len(concrete) == 1 else None

    grouped: dict[tuple, dict[str, dict[tuple[int, str], dict]]] = {}
    for observation in observations:
        source = observation.get("source") or {}
        corpus = source.get("corpus") or {}
        cohort = source.get("cohort") or {}
        key = (
            corpus.get("dataset"), corpus.get("signature"), source.get("model_alias"),
            _canonical_resolved(observation),
            canonical_bytes(cohort.get("corpus_certification")),
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
    completion_strata = []
    for (
        dataset, signature, model, resolved_model, certification_bytes,
        _exposure_mode, _instructions_sha256,
    ), arms in sorted(grouped.items(), key=lambda item: str(item[0])):
        shared = sorted(set(arms.get("A", {})) & set(arms.get("B", {})))
        pairs = {}
        adopted = 0
        usage_complete = True
        # tempdoc 757 conservative-direction rule. Truncated usage is a LOWER BOUND; the
        # efficiency delta is `with_tool - baseline` (lower is better). Treating a lower
        # bound as exact is conservative for a B-favouring efficiency claim ONLY when the
        # truncation is in the BASELINE (A) arm -- understating A understates B's advantage
        # (safe, handled automatically because A's captured values are non-None so
        # `usage_complete` stays True). A truncated WITH-TOOL (B) arm understates B's own
        # cost -> over-states B's advantage -> anti-conservative -> the efficiency intervals
        # are forced unavailable (fail closed), exactly as the incomplete-evidence path.
        with_tool_usage_truncated = False
        per_protocol_pairs = 0
        for seed, qid in shared:
            baseline = arms["A"][(seed, qid)]
            with_tool = arms["B"][(seed, qid)]
            # tempdoc 624 (2026-07-17) exhaustion-as-failure outcome rule:
            #  - `other`-kind error either side => MISSING DATA => drop the pair
            #    from the ITT estimand (residual exclusion, handled per-arm below).
            #  - resource-exhaustion => cell is ATTEMPTED and scored INCORRECT
            #    (not dropped); its recorded wall-clock is right-censored.
            a_kind = classify_error_kind(baseline.get("error")) if baseline.get("excluded") else None
            b_kind = classify_error_kind(with_tool.get("error")) if with_tool.get("excluded") else None
            # Adoption is a with-tool-arm behavioural signal available on every
            # shared cell regardless of the outcome rule, so it is counted over
            # the full shared set (denominator `len(shared)`), before any drop.
            tool_names = [
                str(call.get("tool"))
                for call in (with_tool.get("tool_calls") or [])
                if isinstance(call, dict) and call.get("tool")
            ]
            adopted += int(any(name.startswith("mcp__justsearch") for name in tool_names))
            if a_kind == OTHER_ERROR or b_kind == OTHER_ERROR:
                continue
            a_exhausted = a_kind == RESOURCE_EXHAUSTION
            b_exhausted = b_kind == RESOURCE_EXHAUSTION
            # Residual-retained (neither arm a residual `other` error). This is
            # the ITT paired denominator; the CLEAN per-protocol set lives in the
            # secondary `measured` block (successful_summaries drops all errors).
            per_protocol_pairs += 1
            values = (
                baseline.get("cost_usd"), with_tool.get("cost_usd"),
                baseline.get("unique_tokens"), with_tool.get("unique_tokens"),
            )
            usage_complete = usage_complete and all(value is not None for value in values)
            # tempdoc 757: a truncated WITH-TOOL cell taints the whole stratum's efficiency
            # family (its lower-bound cost/tokens cannot be treated as exact without
            # flattering the with-tool arm). Baseline-arm truncation is NOT tracked here --
            # it is direction-safe and already admitted by the non-None `usage_complete`.
            # §I (independent review): gate on the AUTHORITATIVE `usage_truncated` stamp
            # ALONE, not on `b_exhausted`. Classification implies the stamp today, so the
            # `b_exhausted` conjunct was redundant -- but it becomes a silent hole if the
            # stamp and the error-classification ever diverge: a truncated-but-unclassified
            # with-tool cell would then be treated as exact and flatter the with-tool arm.
            if with_tool.get("usage_truncated") is True:
                with_tool_usage_truncated = True
            pairs[f"{seed}|{qid}"] = {
                "seed": seed,
                "a_correct": bool(baseline.get("correct")) and not a_exhausted,
                "c_correct": bool(with_tool.get("correct")) and not b_exhausted,
                "a_cost": baseline.get("cost_usd"),
                "c_cost": with_tool.get("cost_usd"),
                "a_tok": baseline.get("unique_tokens"),
                "c_tok": with_tool.get("unique_tokens"),
                "a_turns": baseline.get("num_turns"),
                "c_turns": with_tool.get("num_turns"),
                "a_dur": baseline.get("total_time"),
                "c_dur": with_tool.get("total_time"),
                "a_censored": a_exhausted,
                "c_censored": b_exhausted,
                "a_tool_calls": baseline.get("tool_calls"),
                "c_tool_calls": with_tool.get("tool_calls"),
                "c_toolsearch_targets": with_tool.get("toolsearch_targets"),
                "c_tool_call_sequence": with_tool.get("tool_call_sequence"),
            }
        stats = (
            _stats_from_pairs(
                pairs,
                statistical_alpha=statistical_alpha,
                with_tool_usage_truncated=with_tool_usage_truncated,
            )
            if pairs else None
        )
        if stats and (not usage_complete or with_tool_usage_truncated):
            # tempdoc 757: distinguish the two fail-closed causes. The anti-conservative
            # reason fires only when usage is otherwise COMPLETE (all values present) but a
            # with-tool cell's usage is a lower bound; when values are outright missing the
            # incomplete-evidence reason wins (byte-identical to the pre-757 path).
            reason = (
                "with-tool usage truncated (lower bound); treating as exact would overstate "
                "the with-tool efficiency advantage (anti-conservative)"
                if usage_complete and with_tool_usage_truncated
                else "incomplete ITT usage evidence"
            )
            stats["provider_cache_creation_input_tokens"] = {
                "available": False, "reason": reason,
            }
            stats["cost_usd"] = {
                "available": False, "reason": reason,
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
        # tempdoc 624 (2026-07-17): split each arm's errored cells into RESIDUAL
        # (`other`, a true exclusion / missing data) and resource-EXHAUSTION
        # (scored-incorrect, retained). n_completed stays the CLEAN count; the
        # comparability axes (exclusion_rate, excluded_jaccard, paired_retention)
        # recompute over the residual set only.
        residual_excluded: dict[str, set] = {"A": set(), "B": set()}
        n_exhausted_by_arm: dict[str, int] = {"A": 0, "B": 0}
        per_arm_loss: dict[str, dict] = {}
        for condition in ("A", "B"):
            arm = arms.get(condition, {})
            n_clean = n_other = n_exhausted = 0
            for key, item in arm.items():
                if not item.get("excluded"):
                    n_clean += 1
                elif classify_error_kind(item.get("error")) == RESOURCE_EXHAUSTION:
                    n_exhausted += 1
                else:
                    n_other += 1
                    residual_excluded[condition].add(key)
            n_exhausted_by_arm[condition] = n_exhausted
            per_arm_loss[condition] = {
                "n_expected": len(expected_by_arm[condition]),
                "n_attempted": len(arm),
                "n_completed": n_clean,
                "n_excluded": n_other,
                "n_pending": len(expected_by_arm[condition] - set(arm)),
                "exclusion_rate": (n_other / len(arm) if arm else 0.0),
            }
        record_has_exhaustion = any(n_exhausted_by_arm.values())
        if record_has_exhaustion:
            for condition in ("A", "B"):
                per_arm_loss[condition]["n_exhausted"] = n_exhausted_by_arm[condition]
        excluded = residual_excluded
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
        # Duration metric family (tempdoc 624, 2026-07-17): projected from the
        # SAME `pairs` (whose exhausted cells are right-censored). OMITTED when
        # `_stats_from_pairs` found no wall-clock on any paired cell -- a stratum
        # composed purely from pre-duration evidence has no "duration" key,
        # byte-identical to before it existed (the funnel precedent).
        if stats and "duration" in stats:
            stratum["duration"] = stats["duration"]
        strata.append(stratum)
        # Completion estimand (762 §T4, the third of the ITT/per-protocol/
        # completion triple). Completion = finished within budget; under the
        # exhaustion-as-failure rule a budget-exhausted cell is a NON-completion,
        # so completion_rate = n_completed / n_attempted (n_completed already
        # excludes exhausted + errored cells). This is the per-arm rate that
        # SEPARATES the ITT accuracy (exhaustion scored incorrect) from the
        # per-protocol accuracy (exhausted cells dropped) — reported per arm
        # (A vs B) so the "tool rescues completion at scale" story is legible.
        completion_by_arm = {}
        for cond in ("A", "B"):
            loss = per_arm_loss[cond]
            n_attempted = loss["n_attempted"]
            completion_by_arm[cond] = {
                "n_expected": loss["n_expected"],
                "n_attempted": n_attempted,
                "n_completed": loss["n_completed"],
                "n_exhausted": n_exhausted_by_arm[cond],
                "completion_rate": (
                    loss["n_completed"] / n_attempted if n_attempted else None
                ),
            }
        completion_strata.append({
            "stratum_id": stratum_id,
            "corpus": dataset,
            "model": model,
            "by_arm": completion_by_arm,
        })
    return {
        "primary": "intention_to_treat",
        "intention_to_treat": {"strata": strata},
        "per_protocol": {
            "role": "secondary",
            "source": "measured",
        },
        # 762 §T4 completion triple: always emitted alongside ITT + per-protocol.
        "completion": {
            "role": "secondary",
            "source": "measured",
            "strata": completion_strata,
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
    status_has_exhaustion = any(loss.n_exhausted for loss in arms.values())
    result = {
        "per_arm_loss": {
            condition: {
                "n_attempted": loss.n_attempted,
                "n_planned": loss.n_planned,
                "n_pending": loss.n_pending,
                "n_completed": loss.n_completed,
                "n_excluded": loss.n_excluded,
                "exclusion_rate": loss.exclusion_rate,
                **({"n_exhausted": loss.n_exhausted} if status_has_exhaustion else {}),
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
    # tempdoc 624 (2026-07-17): surface resource-exhaustion in the top-level loss
    # accounting only when present -- a record with zero exhausted cells projects
    # byte-identical loss to before this field existed (historical digest-stable).
    governance_has_exhaustion = any(loss.n_exhausted for loss in arms.values())
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
                **({"n_exhausted": loss.n_exhausted} if governance_has_exhaustion else {}),
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
    # tempdoc 624 (2026-07-17): explicit provenance stamp of the primary outcome
    # rule -- excluded from the digest (a constant), present for the reviewer.
    record["outcome_rule"] = dict(OUTCOME_RULE)
    record["claim_verdict"] = evaluate_claim(record, selected_policy)
    # tempdoc 789 Phase 1 item 4: descriptive behavioral aggregates, attached AFTER the
    # verdict so no gate can read them, and omitted entirely when no observation carries
    # a behavioral record (pre-789 evidence composes byte-identically).
    behavioral = aggregate_behavioral(all_observations)
    if behavioral:
        record["behavioral"] = behavioral
    record["semantic_digest"] = semantic_digest(record)
    return record


def write_record(record: dict, output_dir: str | Path) -> Path:
    root = Path(output_dir)
    root.mkdir(parents=True, exist_ok=True)
    filename = f"{record['schema']}.json"
    path = root / filename
    path.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return path
