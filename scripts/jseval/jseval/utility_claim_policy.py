"""Versioned, outcome-neutral claim policy evaluation for tempdoc 719."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


def policy_path() -> Path:
    return Path(__file__).parents[1] / "utility-claim-policy.v1.json"


def load_policy(path: str | Path | None = None) -> dict:
    return json.loads(Path(path or policy_path()).read_text(encoding="utf-8"))


def policy_digest(policy: dict) -> str:
    encoded = json.dumps(
        policy, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _cells(record: dict) -> list[dict]:
    estimands = record.get("estimands") or {}
    return list(((estimands.get("intention_to_treat") or {}).get("strata") or []))


def evaluate_claim(record: dict, policy: dict | None = None) -> dict:
    """Evaluate without selecting favorable wording or filling owner thresholds."""
    selected = policy or load_policy()
    unresolved = list(selected.get("unresolved") or [])
    thresholds = selected.get("thresholds") or {}
    requirements = selected.get("requirements") or {}
    gates: list[dict] = []

    def gate(name: str, observed, threshold, passed: bool, reason: str = "") -> None:
        gates.append({
            "name": name,
            "observed": observed,
            "threshold": threshold,
            "passed": bool(passed),
            "reason": reason,
        })

    gate(
        "policy_resolved",
        not unresolved and selected.get("status") == "active",
        True,
        not unresolved and selected.get("status") == "active",
        "owner must settle adoption and accuracy margins" if unresolved else "",
    )
    supported_requirements = {
        "source_identity_complete", "clean_source_checkout",
        "computed_corpus_signature", "resolved_provider_model",
        "captured_search_config", "verified_tool_surface",
        "no_leak_suspect_cells", "contamination_classes",
        "judge_calibration", "accuracy_delta_interval",
        "intention_to_treat", "per_protocol_is_secondary",
        "per_stratum_promotion",
    }
    unknown_requirements = sorted(set(requirements) - supported_requirements)
    gate(
        "supported_policy_requirements", unknown_requirements, [],
        not unknown_requirements,
    )
    stratum_mode = requirements.get("per_stratum_promotion")
    gate(
        "per_stratum_promotion", stratum_mode, "all_required_strata_pass",
        stratum_mode == "all_required_strata_pass",
    )
    gate("minimum_seeds", record.get("seed_count", 0), thresholds.get("minimum_seeds"),
         record.get("seed_count", 0) >= thresholds.get("minimum_seeds", 1))
    comparability = record.get("comparability") or {}
    losses = comparability.get("per_arm_loss") or {}
    max_loss = max((item.get("exclusion_rate", 0) for item in losses.values()), default=1.0)
    gate("maximum_exclusion_rate", max_loss, thresholds.get("maximum_exclusion_rate"),
         bool(losses) and max_loss <= thresholds.get("maximum_exclusion_rate", 0.15))
    pending = sum(item.get("n_pending", 0) for item in losses.values())
    gate("complete_expected_matrix", pending, 0, bool(losses) and pending == 0)
    metrics = comparability.get("metrics") or {}
    retention = metrics.get("paired_n_retention")
    gate("minimum_paired_retention", retention, thresholds.get("minimum_paired_retention"),
         retention is not None and retention >= thresholds.get("minimum_paired_retention", 0.7))
    jaccard = metrics.get("excluded_jaccard")
    gate("minimum_excluded_jaccard", jaccard, thresholds.get("minimum_excluded_jaccard"),
         jaccard is not None and jaccard >= thresholds.get("minimum_excluded_jaccard", 0.5))
    gate("comparability", comparability.get("comparable"), True,
         comparability.get("comparable") is True,
         "; ".join(comparability.get("reasons") or []))

    cells = _cells(record)
    paired = [cell.get("n_paired_observations", 0) for cell in cells]
    gate(
        "minimum_paired_observations",
        min(paired) if paired else 0,
        thresholds.get("minimum_paired_observations"),
        bool(paired) and min(paired) >= thresholds.get("minimum_paired_observations", 1),
    )
    cohort = record.get("cohort") or {}
    environment_platform = (cohort.get("environment") or {}).get("platform") or {}
    environment_complete = all(
        bool(environment_platform.get(key)) for key in ("system", "release", "machine")
    )
    identity_complete = (
        bool(cells)
        and all(
            cell.get("corpus_signature") and cell.get("resolved_provider_model")
            and bool((cell.get("query_identity") or {}).get("sha256"))
            and bool((cell.get("campaign_identity") or {}).get("expected_cells"))
            for cell in cells
        )
        and bool(cohort.get("git_sha"))
        and cohort.get("git_dirty") is False
        and bool(cohort.get("source_git_state"))
        and environment_complete
    )
    gate("source_identity_complete", identity_complete, True, identity_complete)

    estimands = record.get("estimands") or {}
    has_itt = estimands.get("primary") == "intention_to_treat" and bool(cells)
    gate("intention_to_treat_primary", has_itt, True, has_itt)
    per_protocol_secondary = (estimands.get("per_protocol") or {}).get("role") == "secondary"
    gate("per_protocol_secondary", per_protocol_secondary, True, per_protocol_secondary)
    interval_present = bool(cells) and all(
        len((cell.get("accuracy") or {}).get("delta_ci")
            or (cell.get("accuracy") or {}).get("delta_ci95") or []) == 2
        for cell in cells
    )
    gate("accuracy_delta_interval", interval_present, True, interval_present)
    alpha = thresholds.get("significance_alpha")
    gate("significance_alpha", record.get("statistical_alpha"), alpha,
         record.get("statistical_alpha") == alpha)

    allowed_contamination = (selected.get("requirements") or {}).get("contamination_classes", [])
    contamination = (record.get("coverage") or {}).get("contamination_class")
    gate(
        "contamination_class",
        contamination,
        allowed_contamination,
        contamination in allowed_contamination,
    )
    search_config = cohort.get("search_config_cohort_key")
    gate("captured_search_config", bool(search_config), True, bool(search_config))

    assertions = record.get("tool_call_assertions") or {}
    with_tool_assertions = assertions.get("B") or {}
    verified_surface = (
        with_tool_assertions.get("cells_total", 0) > 0
        and with_tool_assertions.get("cells_total")
        == (losses.get("B") or {}).get("n_attempted")
        and with_tool_assertions.get("cells_with_mcp_surface_verified")
        == with_tool_assertions.get("cells_total")
        and with_tool_assertions.get("cells_mcp_surface_unverified", 0) == 0
        and with_tool_assertions.get("observed_mcp_tool_surface_consistent") is True
        and len(with_tool_assertions.get("observed_mcp_tool_surface_hashes") or []) == 1
        and cohort.get("mcp_tool_surface_hash")
        == (with_tool_assertions.get("observed_mcp_tool_surface_hashes") or [None])[0]
    )
    gate("verified_tool_surface", verified_surface, True, verified_surface)
    leak_count = sum(item.get("cells_with_leak_suspect", 0) for item in assertions.values())
    gate("no_leak_suspect_cells", leak_count, 0, leak_count == 0)
    disallowed_count = sum(
        item.get("cells_with_disallowed_violations", 0)
        for item in assertions.values()
    )
    gate("no_disallowed_tool_calls", disallowed_count, 0, disallowed_count == 0)

    judge = cohort.get("judge") or {}
    judge_calibrated = (
        "llm" not in str(judge.get("kind", ""))
        or bool(judge.get("calibration_hash"))
    )
    gate("judge_calibration", judge_calibrated, True, judge_calibrated)

    adoption_threshold = thresholds.get("minimum_adoption_rate")
    adoption_values = [
        ((cell.get("adoption") or {}).get("with_tool") or {}).get("adoption_rate")
        for cell in cells
    ]
    adoption_passed = (
        adoption_threshold is not None
        and bool(adoption_values)
        and all(value is not None and value >= adoption_threshold for value in adoption_values)
    )
    gate(
        "minimum_adoption_rate",
        min((value for value in adoption_values if value is not None), default=None),
        adoption_threshold,
        adoption_passed,
    )

    margin = thresholds.get("accuracy_noninferiority_margin")
    gate("accuracy_margin_resolved", margin, "non-null", margin is not None)

    # Adoption is an outcome/promotion threshold, not a scientific-validity fact.
    # A valid harmful result must remain publishable even when adoption is low.
    validity_gate_names = {item["name"] for item in gates} - {"minimum_adoption_rate"}
    validity_passed = bool(gates) and all(
        item["passed"] for item in gates if item["name"] in validity_gate_names
    )
    token_margin = thresholds.get("provider_token_equivalence_margin")
    cost_margin = thresholds.get("cost_equivalence_margin_usd")
    null_margins = {
        "provider_cache_creation_input_tokens": token_margin,
        "cost_usd": cost_margin,
    }
    def interval(cell: dict, metric: str) -> list:
        block = cell.get(metric) or {}
        return list(block.get("delta_ci") or block.get("delta_ci95") or [])

    stratum_outcomes = []
    for cell in cells:
        accuracy_interval = interval(cell, "accuracy")
        metric_intervals = {
            metric: interval(cell, metric)
            for metric in ("provider_cache_creation_input_tokens", "cost_usd")
        }
        usage_complete = (
            cell.get("usage_complete") is True
            and all(len(value) == 2 for value in metric_intervals.values())
        )
        adoption_value = (
            ((cell.get("adoption") or {}).get("with_tool") or {}).get("adoption_rate")
        )
        stratum_adoption = (
            adoption_threshold is not None
            and adoption_value is not None
            and adoption_value >= adoption_threshold
        )
        accuracy_harm = (
            margin is not None and len(accuracy_interval) == 2
            and accuracy_interval[1] < -margin
        )
        noninferior = (
            margin is not None and len(accuracy_interval) == 2
            and accuracy_interval[0] >= -margin
        )
        efficiency_harm = usage_complete and any(
            value[0] > 0 for value in metric_intervals.values()
        )
        efficiency_benefit = usage_complete and any(
            value[1] < 0 for value in metric_intervals.values()
        )
        accuracy_equivalent = (
            margin is not None and len(accuracy_interval) == 2
            and accuracy_interval[0] >= -margin
            and accuracy_interval[1] <= margin
        )
        efficiency_equivalent = usage_complete and all(
            null_margins[metric] is not None
            and value[0] >= -null_margins[metric]
            and value[1] <= null_margins[metric]
            for metric, value in metric_intervals.items()
        )
        if accuracy_harm or efficiency_harm:
            cell_outcome = "harm"
        elif stratum_adoption and noninferior and efficiency_benefit:
            cell_outcome = "benefit"
        elif accuracy_equivalent and efficiency_equivalent:
            cell_outcome = "null"
        elif stratum_adoption:
            cell_outcome = "adoption-only"
        else:
            cell_outcome = "inconclusive"
        stratum_outcomes.append({
            "corpus": cell.get("corpus"),
            "model": cell.get("model"),
            "outcome": cell_outcome,
        })

    outcomes = [item["outcome"] for item in stratum_outcomes]
    if not validity_passed or not outcomes:
        outcome = "inconclusive"
    elif "harm" in outcomes:
        outcome = "harm"
    elif all(value == "benefit" for value in outcomes):
        outcome = "benefit"
    elif all(value == "null" for value in outcomes):
        outcome = "null"
    elif adoption_passed:
        outcome = "adoption-only"
    else:
        outcome = "inconclusive"

    accepted = validity_passed and outcome != "inconclusive"
    status = "accepted" if accepted else "rejected"
    reasons = [
        item["name"] for item in gates
        if item["name"] in validity_gate_names and not item["passed"]
    ]
    if validity_passed and outcome == "inconclusive":
        reasons.append("outcome_inconclusive")
        if not adoption_passed:
            reasons.append("minimum_adoption_rate")
    if unresolved:
        reasons.insert(0, "policy_unresolved")
    return {
        "status": status,
        "accepted": accepted,
        "outcome": outcome,
        "arm": "addition_b",
        "strata": "all_required",
        "stratum_outcomes": stratum_outcomes,
        "policy_id": selected.get("policy_id"),
        "policy_hash": policy_digest(selected),
        "policy_status": selected.get("status"),
        "unresolved": unresolved,
        "gates": gates,
        "reasons": reasons,
        "wording_constraints": [
            "Do not publish numeric benefit language unless accepted is true.",
            "A rejected run may be described only as rejected or inconclusive.",
            "Condition C is substitution-only and never a deployment headline.",
        ],
    }
