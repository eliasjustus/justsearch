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


def _cells(record: dict):
    measured = record.get("measured") or {}
    if record.get("schema") == "utility-comparison-cross-corpus.v1":
        yield from measured.values()
        return
    for models in measured.values():
        yield from models.values()


def evaluate_claim(record: dict, policy: dict | None = None) -> dict:
    """Evaluate without selecting favorable wording or filling owner thresholds."""
    selected = policy or load_policy()
    unresolved = list(selected.get("unresolved") or [])
    thresholds = selected.get("thresholds") or {}
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
    gate(
        "minimum_seeds",
        record.get("seed_count", 0),
        thresholds.get("minimum_seeds"),
        record.get("seed_count", 0) >= thresholds.get("minimum_seeds", 1),
    )
    comparability = record.get("comparability") or {}
    gate(
        "comparability",
        comparability.get("comparable"),
        True,
        comparability.get("comparable") is True,
        "; ".join(comparability.get("reasons") or []),
    )

    cells = list(_cells(record))
    paired = [cell.get("n_paired_observations", 0) for cell in cells]
    gate(
        "minimum_paired_observations",
        min(paired) if paired else 0,
        thresholds.get("minimum_paired_observations"),
        bool(paired) and min(paired) >= thresholds.get("minimum_paired_observations", 1),
    )
    identities = [cell.get("identity") or {} for cell in cells]
    identity_complete = bool(identities) and all(
        (identity.get("corpus_signature") or (
            identity.get("corpus_signatures")
            and all(identity["corpus_signatures"].values())
        ))
        and identity.get("resolved_provider_model")
        for identity in identities
    )
    cohort = record.get("cohort") or {}
    identity_complete = bool(cohort.get("git_sha")) and cohort.get("git_dirty") is False and identity_complete
    gate("source_identity_complete", identity_complete, True, identity_complete)

    has_addition = bool(cells) and all(cell.get("primary_arm") == "addition_b" for cell in cells)
    gate("primary_estimand_available", has_addition, "addition_b", has_addition)
    interval_present = bool(cells) and all(
        len((cell.get("accuracy") or {}).get("delta_ci95") or []) == 2 for cell in cells
    )
    gate("accuracy_delta_interval", interval_present, True, interval_present)

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
        and with_tool_assertions.get("cells_with_mcp_surface_verified")
        == with_tool_assertions.get("cells_total")
        and with_tool_assertions.get("observed_mcp_tool_surface_consistent") is True
        and len(with_tool_assertions.get("observed_mcp_tool_surface_hashes") or []) == 1
    )
    gate("verified_tool_surface", verified_surface, True, verified_surface)
    leak_count = sum(item.get("cells_with_leak_suspect", 0) for item in assertions.values())
    gate("no_leak_suspect_cells", leak_count, 0, leak_count == 0)

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
    lower_bounds = [
        (cell.get("accuracy") or {}).get("delta_ci95", [None, None])[0]
        for cell in cells
    ]
    noninferior = (
        margin is not None
        and bool(lower_bounds)
        and all(value is not None and value >= -margin for value in lower_bounds)
    )
    gate(
        "accuracy_noninferiority",
        min((value for value in lower_bounds if value is not None), default=None),
        None if margin is None else -margin,
        noninferior,
    )

    efficiency_upper_bounds = []
    for cell in cells:
        for metric in ("provider_cache_creation_input_tokens", "cost_usd"):
            interval = (cell.get(metric) or {}).get("delta_ci95") or []
            if len(interval) == 2:
                efficiency_upper_bounds.append(interval[1])
    efficiency_benefit = bool(efficiency_upper_bounds) and any(
        value is not None and value < 0 for value in efficiency_upper_bounds
    )
    gate(
        "efficiency_benefit_interval",
        min(efficiency_upper_bounds, default=None),
        "at least one upper CI bound < 0",
        efficiency_benefit,
    )

    accepted = bool(gates) and all(item["passed"] for item in gates)
    status = "accepted" if accepted else "rejected"
    outcome = "benefit" if accepted and efficiency_benefit else "inconclusive"
    reasons = [item["name"] for item in gates if not item["passed"]]
    if unresolved:
        reasons.insert(0, "policy_unresolved")
    return {
        "status": status,
        "accepted": accepted,
        "outcome": outcome,
        "arm": "addition_b",
        "strata": "all_required",
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
