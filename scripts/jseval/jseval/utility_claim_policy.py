"""Versioned, outcome-neutral claim policy evaluation for tempdoc 719."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from jseval.utility_comparison import SEED_FLOOR

_HEX = frozenset("0123456789abcdef")

SUPPORTED_REQUIREMENTS = frozenset({
    "source_identity_complete", "clean_source_checkout",
    "computed_corpus_signature", "corpus_certification", "resolved_provider_model",
    "captured_search_config", "verified_tool_surface", "verified_exposure_mode",
    "no_leak_suspect_cells", "contamination_classes",
    "judge_calibration", "accuracy_delta_interval",
    "intention_to_treat", "per_protocol_is_secondary",
    "per_stratum_promotion",
})


def _is_sha256(value: object) -> bool:
    return isinstance(value, str) and len(value) == 64 and set(value) <= _HEX


def _is_git_sha(value: object) -> bool:
    return isinstance(value, str) and len(value) == 40 and set(value) <= _HEX


def _certification_snapshot_valid(value: object) -> bool:
    # Local import avoids pulling corpus tooling into lightweight policy imports.
    from .corpus_certify import certification_snapshot_valid

    return certification_snapshot_valid(value)


def policy_path() -> Path:
    return Path(__file__).parents[1] / "utility-claim-policy.v1.json"


def load_policy(path: str | Path | None = None) -> dict:
    return json.loads(Path(path or policy_path()).read_text(encoding="utf-8"))


def canonical_bytes(value: object) -> bytes:
    return json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")


def canonical_digest(value: object) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def policy_digest(policy: dict) -> str:
    return canonical_digest(policy)


def _cells(record: dict) -> list[dict]:
    estimands = record.get("estimands") or {}
    return list(((estimands.get("intention_to_treat") or {}).get("strata") or []))


def _required_projection(item: dict) -> dict:
    return {
        "stratum_id": item.get("stratum_id"),
        "corpus_member": item.get("corpus_member"),
        "dataset": item.get("dataset"),
        "size": item.get("size"),
        "query_variant": item.get("query_variant"),
        "requested_model": item.get("requested_model"),
        "query_count": item.get("query_count"),
        "seed_ids": sorted(item.get("seed_ids") or []),
    }


def _observed_projection(cell: dict) -> dict:
    return {
        "stratum_id": cell.get("stratum_id"),
        "corpus_member": cell.get("corpus_member"),
        "dataset": cell.get("corpus"),
        "size": cell.get("corpus_size"),
        "query_variant": cell.get("query_variant"),
        "requested_model": cell.get("model"),
        "query_count": cell.get("query_count"),
        "seed_ids": sorted(cell.get("seed_ids") or []),
    }


def _stratum_matrix_consistent(cell: dict) -> bool:
    raw = list((cell.get("campaign_identity") or {}).get("expected_cells") or [])
    parsed = {"A": set(), "B": set()}
    try:
        for value in raw:
            condition, seed, qid = str(value).split("|", 2)
            if condition not in parsed or not qid:
                return False
            parsed[condition].add((int(seed), qid))
    except (TypeError, ValueError):
        return False
    if len(raw) != len(set(raw)) or not raw or parsed["A"] != parsed["B"]:
        return False
    pairs = parsed["A"]
    seeds = sorted({seed for seed, _ in pairs})
    queries = {qid for _, qid in pairs}
    # A declared seed/query stratum is the full factorial campaign. Merely
    # mentioning every seed and query at least once permits a sparse subset to
    # masquerade as the required matrix while still satisfying aggregate n.
    if pairs != {(seed, qid) for seed in seeds for qid in queries}:
        return False
    loss = cell.get("per_arm_loss") or {}
    if set(loss) != {"A", "B"}:
        return False
    attempted = 0
    pending = 0
    for condition in ("A", "B"):
        arm = loss[condition]
        n_attempted = arm.get("n_attempted")
        n_excluded = arm.get("n_excluded")
        n_completed = arm.get("n_completed")
        n_pending = arm.get("n_pending")
        exclusion_rate = arm.get("exclusion_rate")
        # tempdoc 624 (2026-07-17): resource-exhaustion is a THIRD attempted
        # disposition (scored-incorrect, retained), so the closure identity is
        # n_completed + n_exhausted + n_excluded == n_attempted. `n_exhausted`
        # is omitted (defaults 0) on records with no exhausted cells, which
        # recovers the original two-way identity byte-for-byte.
        n_exhausted = arm.get("n_exhausted", 0)
        if (
            arm.get("n_expected") != len(pairs)
            or not isinstance(n_attempted, int)
            or not isinstance(n_excluded, int)
            or not isinstance(n_completed, int)
            or not isinstance(n_exhausted, int)
            or not isinstance(n_pending, int)
            or not isinstance(exclusion_rate, (int, float))
            or n_exhausted < 0
            or n_completed + n_exhausted + n_excluded != n_attempted
            or n_pending != len(pairs) - n_attempted
            or n_attempted < 0
            or n_attempted > len(pairs)
            or abs(
                exclusion_rate
                - (n_excluded / n_attempted if n_attempted else 0.0)
            ) > 1e-9
        ):
            return False
        attempted += n_attempted
        pending += n_pending
    expected_pairs = len(pairs)
    return (
        seeds == sorted(cell.get("seed_ids") or [])
        and cell.get("seed_count") == len(seeds)
        and cell.get("query_count") == len(queries)
        and cell.get("n_expected_pairs") == expected_pairs
        and cell.get("n_expected_cells") == expected_pairs * 2
        and cell.get("n_observed_cells") == attempted
        and cell.get("n_pending_cells") == pending
        and 0 <= cell.get("n_per_protocol_pairs", -1)
        <= cell.get("n_paired_observations", -1)
        <= expected_pairs
        and abs(
            cell.get("paired_retention", -1)
            - (
                cell.get("n_per_protocol_pairs", 0) / expected_pairs
                if expected_pairs else 0.0
            )
        ) <= 1e-9
    )


def evaluate_claim(record: dict, policy: dict | None = None) -> dict:
    """Evaluate without selecting favorable wording or filling owner thresholds."""
    selected = policy or load_policy()
    unresolved = list(selected.get("unresolved") or [])
    required_strata = list(selected.get("required_strata") or [])
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
        not unresolved and bool(required_strata) and selected.get("status") == "active",
        True,
        not unresolved and bool(required_strata) and selected.get("status") == "active",
        "owner must settle the campaign matrix and scientific margins"
        if unresolved or not required_strata else "",
    )
    unknown_requirements = sorted(set(requirements) - SUPPORTED_REQUIREMENTS)
    gate(
        "supported_policy_requirements", unknown_requirements, [],
        not unknown_requirements,
    )
    stratum_mode = requirements.get("per_stratum_promotion")
    gate(
        "per_stratum_promotion", stratum_mode, "all_required_strata_pass",
        stratum_mode == "all_required_strata_pass",
    )
    cells = _cells(record)
    expected_rows = [_required_projection(item) for item in required_strata]
    observed_rows = [_observed_projection(cell) for cell in cells]
    expected_ids = [item["stratum_id"] for item in expected_rows]
    observed_ids = [item["stratum_id"] for item in observed_rows]
    exact_strata = (
        bool(expected_rows)
        and len(expected_ids) == len(set(expected_ids))
        and len(observed_ids) == len(set(observed_ids))
        and sorted(expected_rows, key=lambda item: str(item["stratum_id"]))
        == sorted(observed_rows, key=lambda item: str(item["stratum_id"]))
    )
    gate(
        "required_strata_exact",
        observed_rows,
        expected_rows,
        exact_strata,
        "the record must contain exactly the pre-registered member/size/query/model/seed matrix",
    )
    derived_strata = [
        {
            "stratum_id": cell.get("stratum_id"),
            "consistent": _stratum_matrix_consistent(cell),
        }
        for cell in cells
    ]
    gate(
        "itt_strata_derived",
        derived_strata,
        "every count is derived from the exact expected cell matrix",
        bool(derived_strata) and all(item["consistent"] for item in derived_strata),
    )
    per_stratum_seeds = [cell.get("seed_count", 0) for cell in cells]
    gate(
        "minimum_seeds",
        min(per_stratum_seeds) if per_stratum_seeds else 0,
        thresholds.get("minimum_seeds"),
        bool(per_stratum_seeds)
        and min(per_stratum_seeds) >= thresholds.get("minimum_seeds", 1),
    )
    # tempdoc 736 D15: the SEED_FLOOR code constant (3) is a decision-grade
    # accuracy-claim floor, distinct from `thresholds.minimum_seeds` (an
    # owner-configurable, generally-stricter policy knob already gated
    # above). Like `minimum_adoption_rate` below, this gate is reported for
    # observability but EXCLUDED from `validity_gate_names` -- it does not
    # block `accepted`/`validity_passed` on its own. The actual enforcement
    # is at the per-stratum OUTCOME level (below): a stratum under the floor
    # can never resolve to "benefit"/"null" (an accuracy-based claim), but
    # CAN still resolve to "harm" or "adoption-only" -- a single-seed
    # campaign remains publishable as exploratory/smoke or as a harmful
    # finding, just never as a promoted accuracy decision.
    gate(
        "seed_floor_met",
        min(per_stratum_seeds) if per_stratum_seeds else 0,
        SEED_FLOOR,
        bool(per_stratum_seeds) and min(per_stratum_seeds) >= SEED_FLOOR,
    )
    stratum_losses = [
        loss
        for cell in cells
        for loss in (cell.get("per_arm_loss") or {}).values()
    ]
    max_loss = max(
        (item.get("exclusion_rate", 0) for item in stratum_losses), default=1.0
    )
    gate("maximum_exclusion_rate", max_loss, thresholds.get("maximum_exclusion_rate"),
         bool(stratum_losses)
         and max_loss <= thresholds.get("maximum_exclusion_rate", 0.15))
    pending = sum(cell.get("n_pending_cells", 0) for cell in cells)
    gate("complete_expected_matrix", pending, 0, bool(cells) and pending == 0)
    retention_values = [cell.get("paired_retention") for cell in cells]
    retention = min(
        (value for value in retention_values if value is not None), default=None
    )
    gate("minimum_paired_retention", retention, thresholds.get("minimum_paired_retention"),
         bool(retention_values)
         and all(value is not None for value in retention_values)
         and retention >= thresholds.get("minimum_paired_retention", 0.7))
    jaccard_values = [cell.get("excluded_jaccard") for cell in cells]
    jaccard = min(
        (value for value in jaccard_values if value is not None), default=None
    )
    gate("minimum_excluded_jaccard", jaccard, thresholds.get("minimum_excluded_jaccard"),
         bool(jaccard_values)
         and all(value is not None for value in jaccard_values)
         and jaccard >= thresholds.get("minimum_excluded_jaccard", 0.5))

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
            _is_sha256(cell.get("corpus_signature"))
            and bool(cell.get("resolved_provider_model"))
            and _is_sha256((cell.get("query_identity") or {}).get("sha256"))
            and isinstance((cell.get("query_identity") or {}).get("row_count"), int)
            and (cell.get("query_identity") or {}).get("row_count") == cell.get("query_count")
            and bool((cell.get("campaign_identity") or {}).get("expected_cells"))
            for cell in cells
        )
        and _is_git_sha(cohort.get("git_sha"))
        and cohort.get("git_dirty") is False
        and set(cohort.get("source_git_state") or {}) == {
            "tracked_diff_sha256", "untracked_sha256", "untracked_count", "dirty",
        }
        and _is_sha256((cohort.get("source_git_state") or {}).get("tracked_diff_sha256"))
        and _is_sha256((cohort.get("source_git_state") or {}).get("untracked_sha256"))
        and isinstance((cohort.get("source_git_state") or {}).get("untracked_count"), int)
        and (cohort.get("source_git_state") or {}).get("untracked_count") >= 0
        and (cohort.get("source_git_state") or {}).get("dirty") is False
        and _is_sha256(cohort.get("mcp_tool_surface_hash"))
        and environment_complete
        # tempdoc 725 increment 2: exposure-mode + MCP-server-identity capture is
        # part of source identity now -- a record whose exposure mode never
        # resolved past "unknown" (or wasn't captured at all) is not a
        # source-identity-complete record, same bar as the git/environment checks
        # above.
        and (cohort.get("exposure_config") or {}).get("exposure_mode") in ("eager", "deferred")
        and bool((cohort.get("mcp_initialize_identity") or {}).get("instructions_sha256"))
        and bool((cohort.get("mcp_initialize_identity") or {}).get("server_version"))
    )
    gate("source_identity_complete", identity_complete, True, identity_complete)
    certifications = [cell.get("corpus_certification") or {} for cell in cells]
    certification_complete = bool(certifications) and all(
        _certification_snapshot_valid(item)
        and item.get("dataset") == cell.get("corpus")
        and item.get("member") == cell.get("corpus_member")
        and item.get("size") == cell.get("corpus_size")
        and item.get("query_variant") == cell.get("query_variant")
        and item.get("query_count") == cell.get("query_count")
        and item.get("query_gold_sha256")
        == (cell.get("query_identity") or {}).get("sha256")
        and item.get("corpus_signature") == cell.get("corpus_signature")
        for cell, item in zip(cells, certifications)
    )
    gate(
        "corpus_certification_complete",
        certification_complete,
        True,
        certification_complete,
    )

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
    attempted_with_tool = sum(
        ((cell.get("per_arm_loss") or {}).get("B") or {}).get("n_attempted", 0)
        for cell in cells
    )
    verified_surface = (
        with_tool_assertions.get("cells_total", 0) > 0
        and with_tool_assertions.get("cells_total")
        == attempted_with_tool
        and with_tool_assertions.get("cells_with_mcp_surface_verified")
        == with_tool_assertions.get("cells_total")
        and with_tool_assertions.get("cells_mcp_surface_unverified", 0) == 0
        and with_tool_assertions.get("observed_mcp_tool_surface_consistent") is True
        and len(with_tool_assertions.get("observed_mcp_tool_surface_hashes") or []) == 1
        and cohort.get("mcp_tool_surface_hash")
        == (with_tool_assertions.get("observed_mcp_tool_surface_hashes") or [None])[0]
    )
    gate("verified_tool_surface", verified_surface, True, verified_surface)

    # tempdoc 725 increment 2: only add this gate when the record actually
    # captured exposure identity somewhere (`cohort.exposure_config` is present
    # -- itself conditionally excluded, never emitted as a bare `null`, for
    # evidence that never captured it). Evidence composed entirely from
    # pre-725 observations must project to a BYTE-IDENTICAL record to before
    # this gate existed -- appending a gate that could only ever read as
    # "not applicable" would silently change every historical record's
    # claim_verdict shape and semantic_digest for no signal gained (there is
    # nothing to verify when nothing was captured). Once ANY exposure identity
    # is present, the gate always fires -- same as verified_tool_surface -- and
    # legitimately fails on genuinely incomplete/inconsistent 725-era evidence.
    if cohort.get("exposure_config") is not None:
        declared_exposure_mode = (cohort.get("exposure_config") or {}).get("exposure_mode")
        # Same shape as verified_tool_surface above: every with-tool attempted
        # cell must carry the SAME exposure_mode, and it must match the
        # cohort's declared value. exposure_mode is a cohort-level constant
        # (derived from config, never a per-cell SDK signal), so this is
        # primarily a mix-detection check -- it catches evidence assembled
        # from two differently-configured campaigns, not per-cell variance
        # (there is none to observe).
        verified_exposure = (
            with_tool_assertions.get("cells_total", 0) > 0
            and with_tool_assertions.get("cells_total") == attempted_with_tool
            and with_tool_assertions.get("cells_with_exposure_mode_verified")
            == with_tool_assertions.get("cells_total")
            and with_tool_assertions.get("observed_exposure_mode_consistent") is True
            and len(with_tool_assertions.get("observed_exposure_modes") or []) == 1
            and declared_exposure_mode in ("eager", "deferred")
            and declared_exposure_mode
            == (with_tool_assertions.get("observed_exposure_modes") or [None])[0]
        )
        gate("verified_exposure_mode", verified_exposure, True, verified_exposure)

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
    # Same reasoning for `seed_floor_met` (tempdoc 736 D15/U5): a valid harmful
    # finding must remain publishable even below the seed floor -- the floor
    # is enforced at the per-stratum OUTCOME level below (accuracy-based
    # outcomes only), never via this overall validity check.
    validity_gate_names = (
        {item["name"] for item in gates} - {"minimum_adoption_rate", "seed_floor_met"}
    )
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
        # tempdoc 736 D15/U5: a stratum below SEED_FLOOR can never back an
        # ACCURACY-based claim (benefit/null) -- a single-seed campaign
        # remains publishable as exploratory/smoke ("adoption-only") or as a
        # harmful finding ("harm", checked below and NEVER gated on this),
        # just never as a promoted accuracy decision.
        stratum_seed_floor_met = cell.get("seed_count", 0) >= SEED_FLOOR
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
        elif stratum_adoption and stratum_seed_floor_met and noninferior and efficiency_benefit:
            cell_outcome = "benefit"
        elif stratum_adoption and stratum_seed_floor_met and accuracy_equivalent and efficiency_equivalent:
            cell_outcome = "null"
        elif stratum_adoption:
            cell_outcome = "adoption-only"
        else:
            cell_outcome = "inconclusive"
        cell_loss = list((cell.get("per_arm_loss") or {}).values())
        cell_max_exclusion = max(
            (item.get("exclusion_rate", 0) for item in cell_loss), default=1.0
        )
        stratum_outcomes.append({
            "stratum_id": cell.get("stratum_id"),
            "corpus": cell.get("corpus"),
            "model": cell.get("model"),
            "outcome": cell_outcome,
            "gates": {
                "derived_matrix": {
                    "observed": _stratum_matrix_consistent(cell),
                    "threshold": True,
                    "passed": _stratum_matrix_consistent(cell),
                },
                "minimum_seeds": {
                    "observed": cell.get("seed_count", 0),
                    "threshold": thresholds.get("minimum_seeds"),
                    "passed": cell.get("seed_count", 0)
                    >= thresholds.get("minimum_seeds", 1),
                },
                "minimum_paired_observations": {
                    "observed": cell.get("n_paired_observations", 0),
                    "threshold": thresholds.get("minimum_paired_observations"),
                    "passed": cell.get("n_paired_observations", 0)
                    >= thresholds.get("minimum_paired_observations", 1),
                },
                "maximum_exclusion_rate": {
                    "observed": cell_max_exclusion,
                    "threshold": thresholds.get("maximum_exclusion_rate"),
                    "passed": bool(cell_loss) and cell_max_exclusion
                    <= thresholds.get("maximum_exclusion_rate", 0.15),
                },
                "complete_expected_matrix": {
                    "observed": cell.get("n_pending_cells", 0),
                    "threshold": 0,
                    "passed": cell.get("n_pending_cells", 0) == 0,
                },
                "minimum_paired_retention": {
                    "observed": cell.get("paired_retention"),
                    "threshold": thresholds.get("minimum_paired_retention"),
                    "passed": cell.get("paired_retention") is not None
                    and cell["paired_retention"]
                    >= thresholds.get("minimum_paired_retention", 0.7),
                },
                "minimum_excluded_jaccard": {
                    "observed": cell.get("excluded_jaccard"),
                    "threshold": thresholds.get("minimum_excluded_jaccard"),
                    "passed": cell.get("excluded_jaccard") is not None
                    and cell["excluded_jaccard"]
                    >= thresholds.get("minimum_excluded_jaccard", 0.5),
                },
                "accuracy_delta_interval": {
                    "observed": accuracy_interval,
                    "threshold": "two-sided interval",
                    "passed": len(accuracy_interval) == 2,
                },
                "minimum_adoption_rate": {
                    "observed": adoption_value,
                    "threshold": adoption_threshold,
                    "passed": stratum_adoption,
                },
                "seed_floor_met": {
                    "observed": cell.get("seed_count", 0),
                    "threshold": SEED_FLOOR,
                    "passed": stratum_seed_floor_met,
                },
                "outcome_resolved": {
                    "observed": cell_outcome,
                    "threshold": "benefit|harm|null|adoption-only",
                    "passed": cell_outcome != "inconclusive",
                },
            },
        })

    outcomes = [item["outcome"] for item in stratum_outcomes]
    if not validity_passed or not outcomes or "inconclusive" in outcomes:
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
