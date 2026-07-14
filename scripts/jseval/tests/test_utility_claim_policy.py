from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest

from jseval.utility_claim_policy import (
    SUPPORTED_REQUIREMENTS,
    evaluate_claim,
    load_policy,
    policy_digest,
)
from tests.test_corpus_inject import _certification_snapshot_fixture


def _record() -> dict:
    seed_ids = [0, 1, 2, 3, 4]
    expected_cells = [
        f"{condition}|{seed}|q{query}"
        for condition in ("A", "B")
        for seed in seed_ids
        for query in range(20)
    ]
    certification = _certification_snapshot_fixture()
    return {
        "schema": "utility-comparison.v1",
        "schema_version": 2,
        "seed_count": 5,
        "cohort": {
            "git_sha": "a" * 40,
            "git_dirty": False,
            "source_git_state": {
                "tracked_diff_sha256": "0" * 64,
                "untracked_sha256": "0" * 64,
                "untracked_count": 0,
                "dirty": False,
            },
            "environment": {"platform": {
                "system": "test", "release": "1", "machine": "test64",
            }},
            "search_config_cohort_key": "search-1",
            "mcp_tool_surface_hash": "f" * 64,
            "query_identity": {"sha256": "b" * 64, "row_count": 20},
            "campaign_identity": {"expected_cells": expected_cells},
            "judge": {"kind": "substring-em"},
            "exposure_config": {
                "enable_tool_search": "true", "always_load": False, "exposure_mode": "deferred",
            },
            "mcp_initialize_identity": {
                "instructions": "search the corpus", "instructions_sha256": "d" * 64,
                "server_version": "1.0.0", "protocol_version": "2025-06-18",
            },
        },
        "comparability": {
            "comparable": True,
            "reasons": [],
            "metrics": {"paired_n_retention": 1.0, "excluded_jaccard": 1.0},
            "per_arm_loss": {
                "A": {"n_attempted": 100, "n_planned": 100, "n_pending": 0, "exclusion_rate": 0.0},
                "B": {"n_attempted": 100, "n_planned": 100, "n_pending": 0, "exclusion_rate": 0.0},
            },
        },
        "coverage": {"contamination_class": "private-synthetic"},
        "tool_call_assertions": {
            "B": {
                "cells_total": 100,
                "cells_with_mcp_surface_verified": 100,
                "cells_mcp_surface_unverified": 0,
                "cells_with_leak_suspect": 0,
                "observed_mcp_tool_surface_hashes": ["f" * 64],
                "observed_mcp_tool_surface_consistent": True,
                "cells_with_exposure_mode_verified": 100,
                "observed_exposure_modes": ["deferred"],
                "observed_exposure_mode_consistent": True,
            }
        },
        "statistical_alpha": 0.05,
        "estimands": {
            "primary": "intention_to_treat",
            "per_protocol": {"role": "secondary"},
            "intention_to_treat": {"strata": [{
                "stratum_id": "fixture-member|fixture|1000|verbose|haiku",
                "corpus_member": "fixture-member",
                "corpus": "fixture",
                "corpus_size": 1000,
                "query_variant": "verbose",
                "corpus_signature": "c" * 64,
                "model": "haiku",
                "resolved_provider_model": "claude-haiku-versioned",
                "corpus_certification": certification,
                "query_identity": {"sha256": "b" * 64, "row_count": 20},
                "campaign_identity": {"expected_cells": expected_cells},
                "seed_ids": seed_ids,
                "seed_count": len(seed_ids),
                "query_count": 20,
                "n_expected_cells": 200,
                "n_observed_cells": 200,
                "n_pending_cells": 0,
                "n_expected_pairs": 100,
                "n_paired_observations": 100,
                "n_per_protocol_pairs": 100,
                "paired_retention": 1.0,
                "excluded_jaccard": 1.0,
                "per_arm_loss": {
                    "A": {"n_expected": 100, "n_attempted": 100, "n_completed": 100,
                          "n_excluded": 0, "n_pending": 0, "exclusion_rate": 0.0},
                    "B": {"n_expected": 100, "n_attempted": 100, "n_completed": 100,
                          "n_excluded": 0, "n_pending": 0, "exclusion_rate": 0.0},
                },
                "usage_complete": True,
                "accuracy": {"delta_ci95": [-0.01, 0.05]},
                "provider_cache_creation_input_tokens": {"delta_ci95": [-30, -10]},
                "cost_usd": {"delta_ci95": [-0.03, -0.01]},
                "adoption": {"with_tool": {"adoption_rate": 0.8}},
            }]},
        },
        "measured": {
            "fixture": {
                "haiku": {
                    "identity": {
                        "corpus_signature": "c" * 64,
                        "resolved_provider_model": "claude-haiku-versioned",
                    },
                    "primary_arm": "addition_b",
                    "n_paired_observations": 100,
                    "accuracy": {"delta_ci95": [-0.01, 0.05]},
                    "provider_cache_creation_input_tokens": {"delta_ci95": [-30, -10]},
                    "cost_usd": {"delta_ci95": [-0.03, -0.01]},
                    "adoption": {"with_tool": {"adoption_rate": 0.8}},
                }
            }
        },
    }


def _active_policy(record: dict | None = None) -> dict:
    record = record or _record()
    policy = copy.deepcopy(load_policy())
    policy["status"] = "active"
    policy["unresolved"] = []
    policy["thresholds"].update({
        "minimum_adoption_rate": 0.5,
        "accuracy_noninferiority_margin": 0.02,
        "provider_token_equivalence_margin": 5,
        "cost_equivalence_margin_usd": 0.001,
    })
    policy["required_strata"] = [
        {
            "stratum_id": cell["stratum_id"],
            "corpus_member": cell["corpus_member"],
            "dataset": cell["corpus"],
            "size": cell["corpus_size"],
            "query_variant": cell["query_variant"],
            "requested_model": cell["model"],
            "query_count": cell["query_count"],
            "seed_ids": cell["seed_ids"],
        }
        for cell in record["estimands"]["intention_to_treat"]["strata"]
    ]
    return policy


def _sync_tool_assertion_counts(record: dict) -> None:
    total = sum(
        cell["per_arm_loss"]["B"]["n_attempted"]
        for cell in record["estimands"]["intention_to_treat"]["strata"]
    )
    assertions = record["tool_call_assertions"]["B"]
    assertions["cells_total"] = total
    assertions["cells_with_mcp_surface_verified"] = total
    assertions["cells_with_exposure_mode_verified"] = total


def test_checked_in_policy_validates_and_is_deliberately_unresolved():
    policy = load_policy()
    jsonschema = pytest.importorskip("jsonschema")
    schema_path = __import__("pathlib").Path(__file__).parents[1] / "utility-claim-policy.v1.schema.json"
    jsonschema.validate(policy, json.loads(schema_path.read_text(encoding="utf-8")))

    verdict = evaluate_claim(_record(), policy)
    assert verdict["accepted"] is False
    assert verdict["status"] == "rejected"
    assert verdict["outcome"] == "inconclusive"
    assert verdict["reasons"][0] == "policy_unresolved"
    assert verdict["policy_hash"] == policy_digest(policy)


def test_favorable_outcomes_cannot_override_unresolved_policy():
    record = _record()
    record["measured"]["fixture"]["haiku"]["accuracy"]["delta_ci95"] = [0.5, 0.7]
    record["measured"]["fixture"]["haiku"]["adoption"]["with_tool"]["adoption_rate"] = 1.0
    assert evaluate_claim(record)["accepted"] is False


def test_settled_active_policy_is_machine_evaluable_without_posthoc_wording():
    policy = _active_policy()

    verdict = evaluate_claim(_record(), policy)
    assert verdict["accepted"] is True
    assert verdict["outcome"] == "benefit"

    harmed = _record()
    harmed["estimands"]["intention_to_treat"]["strata"][0]["accuracy"]["delta_ci95"] = [-0.2, -0.1]
    harmed["estimands"]["intention_to_treat"]["strata"][0]["adoption"]["with_tool"]["adoption_rate"] = 0.1
    accepted_harm = evaluate_claim(harmed, policy)
    assert accepted_harm["accepted"] is True
    assert accepted_harm["outcome"] == "harm"


def test_every_required_stratum_must_support_a_benefit_claim():
    record = _record()
    second = copy.deepcopy(record["estimands"]["intention_to_treat"]["strata"][0])
    second["corpus"] = "second"
    second["corpus_signature"] = "d" * 64
    second["stratum_id"] = "second-member|second|1000|verbose|haiku"
    second["corpus_member"] = "second-member"
    second["corpus_certification"] = _certification_snapshot_fixture(
        member="second-member", dataset="second", signature="d" * 64,
    )
    second["provider_cache_creation_input_tokens"]["delta_ci95"] = [-10, 10]
    second["cost_usd"]["delta_ci95"] = [-0.01, 0.01]
    record["estimands"]["intention_to_treat"]["strata"].append(second)
    _sync_tool_assertion_counts(record)
    policy = _active_policy(record)

    verdict = evaluate_claim(record, policy)

    assert verdict["outcome"] == "adoption-only"
    assert [item["outcome"] for item in verdict["stratum_outcomes"]] == [
        "benefit", "adoption-only",
    ]
    for item in verdict["stratum_outcomes"]:
        assert set(item["gates"]) == {
            "derived_matrix", "minimum_seeds", "minimum_paired_observations",
            "maximum_exclusion_rate", "complete_expected_matrix",
            "minimum_paired_retention", "minimum_excluded_jaccard",
            "accuracy_delta_interval", "minimum_adoption_rate", "outcome_resolved",
        }


def test_inconclusive_required_stratum_prevents_harm_promotion():
    record = _record()
    first = record["estimands"]["intention_to_treat"]["strata"][0]
    first["accuracy"]["delta_ci95"] = [-0.2, -0.1]
    second = copy.deepcopy(first)
    second.update({
        "stratum_id": "second-member|second|1000|verbose|haiku",
        "corpus_member": "second-member",
        "corpus": "second",
        "corpus_signature": "d" * 64,
    })
    second["corpus_certification"] = copy.deepcopy(second["corpus_certification"])
    second["corpus_certification"].update({
        "member": "second-member", "dataset": "second", "corpus_signature": "d" * 64,
    })
    second["accuracy"]["delta_ci95"] = [-0.04, 0.20]
    second["provider_cache_creation_input_tokens"]["delta_ci95"] = [-100, 100]
    second["cost_usd"]["delta_ci95"] = [-0.1, 0.1]
    second["adoption"]["with_tool"]["adoption_rate"] = 0.1
    record["estimands"]["intention_to_treat"]["strata"].append(second)
    _sync_tool_assertion_counts(record)

    verdict = evaluate_claim(record, _active_policy(record))

    assert [item["outcome"] for item in verdict["stratum_outcomes"]] == [
        "harm", "inconclusive",
    ]
    assert verdict["outcome"] == "inconclusive"
    assert verdict["accepted"] is False


@pytest.mark.parametrize("mutation", ["missing", "extra", "query-count"])
def test_required_strata_matrix_is_exact(mutation):
    record = _record()
    policy = _active_policy(record)
    if mutation == "missing":
        policy["required_strata"] = []
    elif mutation == "extra":
        extra = copy.deepcopy(policy["required_strata"][0])
        extra["stratum_id"] = "extra|fixture|1000|verbose|haiku"
        extra["corpus_member"] = "extra"
        policy["required_strata"].append(extra)
    else:
        policy["required_strata"][0]["query_count"] += 1

    verdict = evaluate_claim(record, policy)

    assert verdict["accepted"] is False
    assert "required_strata_exact" in verdict["reasons"]


def test_itt_counts_must_be_derived_from_expected_cells():
    record = _record()
    record["estimands"]["intention_to_treat"]["strata"][0][
        "n_expected_cells"
    ] += 2

    verdict = evaluate_claim(record, _active_policy(record))

    assert verdict["accepted"] is False
    assert "itt_strata_derived" in verdict["reasons"]


def test_minimum_seed_gate_is_per_stratum_not_global():
    record = _record()
    second = copy.deepcopy(record["estimands"]["intention_to_treat"]["strata"][0])
    second.update({
        "stratum_id": "second-member|second|1000|verbose|haiku",
        "corpus_member": "second-member",
        "corpus": "second",
        "corpus_signature": "d" * 64,
        "seed_ids": [0],
        "seed_count": 1,
    })
    second["corpus_certification"] = copy.deepcopy(second["corpus_certification"])
    second["corpus_certification"].update({
        "member": "second-member", "dataset": "second", "corpus_signature": "d" * 64,
    })
    record["estimands"]["intention_to_treat"]["strata"].append(second)
    _sync_tool_assertion_counts(record)
    record["seed_count"] = 5
    policy = _active_policy(record)

    verdict = evaluate_claim(record, policy)

    assert verdict["accepted"] is False
    assert "minimum_seeds" in verdict["reasons"]


def test_incomplete_or_mismatched_corpus_certification_rejects_promotion():
    record = _record()
    policy = _active_policy(record)
    record["estimands"]["intention_to_treat"]["strata"][0][
        "corpus_certification"
    ]["scientific_gates"].pop("leak_floor")

    verdict = evaluate_claim(record, policy)

    assert verdict["accepted"] is False
    assert "corpus_certification_complete" in verdict["reasons"]


def test_sparse_seed_query_matrix_cannot_satisfy_required_stratum():
    record = _record()
    cell = record["estimands"]["intention_to_treat"]["strata"][0]
    pairs = {
        (seed, f"q{query}")
        for query in range(50)
        for seed in (query % 5, (query + 1) % 5)
    }
    expected = [
        f"{condition}|{seed}|{qid}"
        for condition in ("A", "B")
        for seed, qid in sorted(pairs)
    ]
    cell["query_count"] = 50
    cell["query_identity"]["row_count"] = 50
    cell["campaign_identity"]["expected_cells"] = expected
    cell["corpus_certification"] = _certification_snapshot_fixture(query_count=50)
    record["cohort"]["query_identity"]["row_count"] = 50
    record["cohort"]["campaign_identity"]["expected_cells"] = expected
    policy = _active_policy(record)

    verdict = evaluate_claim(record, policy)

    assert verdict["accepted"] is False
    assert "itt_strata_derived" in verdict["reasons"]


def test_disallowed_tool_call_in_any_arm_rejects_scientific_validity():
    policy = _active_policy()
    record = _record()
    record["tool_call_assertions"]["A"] = {
        "cells_total": 100,
        "cells_with_disallowed_violations": 1,
        "cells_with_leak_suspect": 0,
    }

    verdict = evaluate_claim(record, policy)

    assert verdict["accepted"] is False
    assert "no_disallowed_tool_calls" in verdict["reasons"]


def test_all_null_environment_identity_fails_closed():
    policy = _active_policy()
    record = _record()
    record["cohort"]["environment"] = {
        "platform": {"system": None, "release": None, "machine": None},
        "gpu": {"available": False},
    }
    verdict = evaluate_claim(record, policy)
    assert verdict["accepted"] is False
    assert "source_identity_complete" in verdict["reasons"]


def test_incomplete_git_source_state_fails_closed():
    record = _record()
    record["cohort"]["source_git_state"] = {"dirty": False}

    verdict = evaluate_claim(record, _active_policy(record))

    assert verdict["accepted"] is False
    assert "source_identity_complete" in verdict["reasons"]


def test_unsupported_per_stratum_policy_mode_fails_closed():
    policy = _active_policy()
    policy["requirements"]["per_stratum_promotion"] = "unsupported-any-pass"
    verdict = evaluate_claim(_record(), policy)
    assert verdict["accepted"] is False
    assert "per_stratum_promotion" in verdict["reasons"]


@pytest.mark.parametrize(
    ("field", "value", "reason"),
    [
        ("maximum_exclusion_rate", 0.0, "maximum_exclusion_rate"),
        ("minimum_paired_retention", 1.0, "minimum_paired_retention"),
        ("minimum_excluded_jaccard", 1.0, "minimum_excluded_jaccard"),
        ("significance_alpha", 0.01, "significance_alpha"),
    ],
)
def test_every_declared_governance_threshold_changes_the_verdict(field, value, reason):
    policy = _active_policy()
    policy["thresholds"][field] = value
    record = _record()
    if field == "maximum_exclusion_rate":
        record["estimands"]["intention_to_treat"]["strata"][0][
            "per_arm_loss"
        ]["B"]["exclusion_rate"] = 0.01
    elif field == "minimum_paired_retention":
        record["estimands"]["intention_to_treat"]["strata"][0][
            "paired_retention"
        ] = 0.99
    elif field == "minimum_excluded_jaccard":
        record["estimands"]["intention_to_treat"]["strata"][0][
            "excluded_jaccard"
        ] = 0.99
    verdict = evaluate_claim(record, policy)
    assert verdict["accepted"] is False
    assert reason in verdict["reasons"]


def test_aggregate_comparability_counts_are_descriptive_not_authoritative():
    record = _record()
    record["comparability"] = {
        "comparable": False,
        "reasons": ["stale aggregate"],
        "metrics": {"paired_n_retention": 0.0, "excluded_jaccard": 0.0},
        "per_arm_loss": {
            "A": {"n_attempted": 100, "n_pending": 100, "exclusion_rate": 1.0},
            "B": {"n_attempted": 100, "n_pending": 100, "exclusion_rate": 1.0},
        },
    }

    verdict = evaluate_claim(record, _active_policy(record))

    assert verdict["accepted"] is True
    assert verdict["outcome"] == "benefit"


@pytest.mark.parametrize(
    ("field", "value", "reason"),
    [
        ("minimum_seeds", 6, "minimum_seeds"),
        ("minimum_paired_observations", 101, "minimum_paired_observations"),
        ("minimum_adoption_rate", 0.9, "minimum_adoption_rate"),
    ],
)
def test_every_count_or_adoption_threshold_can_reject(field, value, reason):
    policy = _active_policy()
    policy["thresholds"][field] = value
    verdict = evaluate_claim(_record(), policy)
    assert verdict["accepted"] is False
    assert reason in verdict["reasons"]


def test_accuracy_and_equivalence_margins_change_outcome_classification():
    policy = _active_policy()
    policy["thresholds"].update({
        "minimum_adoption_rate": 0.5,
        "accuracy_noninferiority_margin": 0.05,
        "provider_token_equivalence_margin": 50,
        "cost_equivalence_margin_usd": 0.05,
    })
    record = _record()
    stratum = record["estimands"]["intention_to_treat"]["strata"][0]
    stratum["accuracy"]["delta_ci95"] = [-0.01, 0.01]
    stratum["provider_cache_creation_input_tokens"]["delta_ci95"] = [-5, 5]
    stratum["cost_usd"]["delta_ci95"] = [-0.005, 0.005]
    assert evaluate_claim(record, policy)["outcome"] == "null"

    narrow_accuracy = copy.deepcopy(policy)
    narrow_accuracy["thresholds"]["accuracy_noninferiority_margin"] = 0.001
    assert evaluate_claim(record, narrow_accuracy)["outcome"] == "adoption-only"

    narrow_tokens = copy.deepcopy(policy)
    narrow_tokens["thresholds"]["provider_token_equivalence_margin"] = 1
    assert evaluate_claim(record, narrow_tokens)["outcome"] == "adoption-only"

    narrow_cost = copy.deepcopy(policy)
    narrow_cost["thresholds"]["cost_equivalence_margin_usd"] = 0.001
    assert evaluate_claim(record, narrow_cost)["outcome"] == "adoption-only"


def test_all_outcome_classes_are_reachable_under_one_active_policy():
    policy = _active_policy()
    policy["thresholds"].update({
        "minimum_adoption_rate": 0.5,
        "accuracy_noninferiority_margin": 0.05,
        "provider_token_equivalence_margin": 50,
        "cost_equivalence_margin_usd": 0.05,
    })

    null_record = _record()
    stratum = null_record["estimands"]["intention_to_treat"]["strata"][0]
    stratum["accuracy"]["delta_ci95"] = [-0.01, 0.01]
    stratum["provider_cache_creation_input_tokens"]["delta_ci95"] = [-5, 5]
    stratum["cost_usd"]["delta_ci95"] = [-0.005, 0.005]
    assert evaluate_claim(null_record, policy)["outcome"] == "null"

    adoption_only = copy.deepcopy(null_record)
    adoption_stratum = adoption_only["estimands"]["intention_to_treat"]["strata"][0]
    adoption_stratum["accuracy"]["delta_ci95"] = [-0.04, 0.20]
    adoption_stratum["provider_cache_creation_input_tokens"]["delta_ci95"] = [-100, 100]
    adoption_stratum["cost_usd"]["delta_ci95"] = [-0.1, 0.1]
    assert evaluate_claim(adoption_only, policy)["outcome"] == "adoption-only"

    inconclusive = copy.deepcopy(adoption_only)
    inconclusive["estimands"]["intention_to_treat"]["strata"][0]["adoption"]["with_tool"]["adoption_rate"] = 0.1
    verdict = evaluate_claim(inconclusive, policy)
    assert verdict["outcome"] == "inconclusive"
    assert verdict["accepted"] is False


def test_supported_requirements_match_schema_properties_exactly():
    schema_path = Path(__file__).parents[1] / "utility-claim-policy.v1.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    requirements_schema = schema["properties"]["requirements"]
    assert SUPPORTED_REQUIREMENTS == set(requirements_schema["properties"])
    assert SUPPORTED_REQUIREMENTS == set(requirements_schema["required"])


# --- verified_exposure_mode (tempdoc 725 increment 2) -----------------------
#
# `_record()`'s cohort already carries a consistent, real exposure_config /
# mcp_initialize_identity + a matching tool_call_assertions["B"] exposure
# rollup, so the settled-active-policy test above already proves the HAPPY
# path (gate passes, record accepted). These tests exercise the two OTHER
# cases: the gate is entirely ABSENT when the record never captured exposure
# identity at all (byte-identical to pre-725 evidence, no digest footprint),
# and it correctly REJECTS when exposure identity was captured but disagrees.

def test_verified_exposure_mode_gate_absent_when_no_exposure_identity_captured():
    """Evidence that never captured exposure identity (pre-tempdoc-725) must
    not even carry a `verified_exposure_mode` gate entry -- omission, not a
    failing gate, is what keeps old records byte-identical (tempdoc 725
    increment 2 digest-preservation requirement)."""
    record = _record()
    del record["cohort"]["exposure_config"]
    del record["cohort"]["mcp_initialize_identity"]
    del record["tool_call_assertions"]["B"]["cells_with_exposure_mode_verified"]
    del record["tool_call_assertions"]["B"]["observed_exposure_modes"]
    del record["tool_call_assertions"]["B"]["observed_exposure_mode_consistent"]

    verdict = evaluate_claim(record, _active_policy(record))

    gate_names = {item["name"] for item in verdict["gates"]}
    assert "verified_exposure_mode" not in gate_names
    # source_identity_complete legitimately fails on its OWN account (tempdoc
    # 725 increment 2 also requires exposure identity there) -- but the
    # verified_exposure_mode gate itself must never appear when there is
    # nothing for it to check.
    assert "source_identity_complete" in verdict["reasons"]
    assert "verified_exposure_mode" not in verdict["reasons"]


def test_verified_exposure_mode_gate_fails_on_mismatched_declared_and_observed():
    record = _record()
    # Declared cohort value says "deferred"; the tool_call_assertions rollup
    # observed a different (or inconsistent) value -- a mix of two
    # differently-configured campaigns' evidence, the case this gate exists
    # to catch (same failure mode as verified_tool_surface's hash mismatch).
    record["tool_call_assertions"]["B"]["observed_exposure_modes"] = ["eager"]

    verdict = evaluate_claim(record, _active_policy(record))

    gate = next(item for item in verdict["gates"] if item["name"] == "verified_exposure_mode")
    assert gate["passed"] is False
    assert verdict["accepted"] is False
    assert "verified_exposure_mode" in verdict["reasons"]


def test_verified_exposure_mode_gate_fails_when_not_every_cell_verified():
    record = _record()
    record["tool_call_assertions"]["B"]["cells_with_exposure_mode_verified"] = 99  # < cells_total

    verdict = evaluate_claim(record, _active_policy(record))

    gate = next(item for item in verdict["gates"] if item["name"] == "verified_exposure_mode")
    assert gate["passed"] is False
    assert verdict["accepted"] is False


def test_source_identity_complete_requires_well_formed_mcp_initialize_identity():
    """tempdoc 725 increment 2: source_identity_complete additionally requires
    exposure_config.exposure_mode resolved past "unknown" AND a well-formed
    mcp_initialize_identity (non-null instructions_sha256 + server_version)."""
    missing_server_version = _record()
    missing_server_version["cohort"]["mcp_initialize_identity"]["server_version"] = None
    verdict = evaluate_claim(missing_server_version, _active_policy(missing_server_version))
    assert "source_identity_complete" in verdict["reasons"]

    unknown_exposure_mode = _record()
    unknown_exposure_mode["cohort"]["exposure_config"]["exposure_mode"] = "unknown"
    verdict = evaluate_claim(unknown_exposure_mode, _active_policy(unknown_exposure_mode))
    assert "source_identity_complete" in verdict["reasons"]
