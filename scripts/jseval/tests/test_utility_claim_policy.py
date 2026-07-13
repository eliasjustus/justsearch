from __future__ import annotations

import copy
import json

import pytest

from jseval.utility_claim_policy import evaluate_claim, load_policy, policy_digest


def _record() -> dict:
    return {
        "schema": "utility-comparison.v1",
        "schema_version": 2,
        "seed_count": 5,
        "cohort": {
            "git_sha": "a" * 40,
            "git_dirty": False,
            "source_git_state": {"tracked_diff_sha256": "0" * 64},
            "environment": {"platform": {
                "system": "test", "release": "1", "machine": "test64",
            }},
            "search_config_cohort_key": "search-1",
            "mcp_tool_surface_hash": "f" * 64,
            "query_identity": {"sha256": "q" * 64},
            "campaign_identity": {"expected_cells": ["A|0|q0", "B|0|q0"]},
            "judge": {"kind": "substring-em"},
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
            }
        },
        "statistical_alpha": 0.05,
        "estimands": {
            "primary": "intention_to_treat",
            "per_protocol": {"role": "secondary"},
            "intention_to_treat": {"strata": [{
                "corpus": "fixture",
                "corpus_signature": "c" * 64,
                "model": "haiku",
                "resolved_provider_model": "claude-haiku-versioned",
                "query_identity": {"sha256": "q" * 64},
                "campaign_identity": {"expected_cells": ["A|0|q0", "B|0|q0"]},
                "n_paired_observations": 100,
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
    policy = copy.deepcopy(load_policy())
    policy["status"] = "active"
    policy["unresolved"] = []
    policy["thresholds"]["minimum_adoption_rate"] = 0.5
    policy["thresholds"]["accuracy_noninferiority_margin"] = 0.02
    policy["thresholds"]["provider_token_equivalence_margin"] = 5
    policy["thresholds"]["cost_equivalence_margin_usd"] = 0.001

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
    policy = copy.deepcopy(load_policy())
    policy["status"] = "active"
    policy["unresolved"] = []
    policy["thresholds"].update({
        "minimum_adoption_rate": 0.5,
        "accuracy_noninferiority_margin": 0.02,
        "provider_token_equivalence_margin": 5,
        "cost_equivalence_margin_usd": 0.001,
    })
    record = _record()
    second = copy.deepcopy(record["estimands"]["intention_to_treat"]["strata"][0])
    second["corpus"] = "second"
    second["corpus_signature"] = "d" * 64
    second["provider_cache_creation_input_tokens"]["delta_ci95"] = [-10, 10]
    second["cost_usd"]["delta_ci95"] = [-0.01, 0.01]
    record["estimands"]["intention_to_treat"]["strata"].append(second)

    verdict = evaluate_claim(record, policy)

    assert verdict["outcome"] == "adoption-only"
    assert [item["outcome"] for item in verdict["stratum_outcomes"]] == [
        "benefit", "adoption-only",
    ]


def test_disallowed_tool_call_in_any_arm_rejects_scientific_validity():
    policy = copy.deepcopy(load_policy())
    policy["status"] = "active"
    policy["unresolved"] = []
    policy["thresholds"].update({
        "minimum_adoption_rate": 0.5,
        "accuracy_noninferiority_margin": 0.02,
        "provider_token_equivalence_margin": 5,
        "cost_equivalence_margin_usd": 0.001,
    })
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
    policy = copy.deepcopy(load_policy())
    policy["status"] = "active"
    policy["unresolved"] = []
    policy["thresholds"].update({
        "minimum_adoption_rate": 0.5,
        "accuracy_noninferiority_margin": 0.02,
        "provider_token_equivalence_margin": 5,
        "cost_equivalence_margin_usd": 0.001,
    })
    record = _record()
    record["cohort"]["environment"] = {
        "platform": {"system": None, "release": None, "machine": None},
        "gpu": {"available": False},
    }
    verdict = evaluate_claim(record, policy)
    assert verdict["accepted"] is False
    assert "source_identity_complete" in verdict["reasons"]


def test_unsupported_per_stratum_policy_mode_fails_closed():
    policy = copy.deepcopy(load_policy())
    policy["status"] = "active"
    policy["unresolved"] = []
    policy["requirements"]["per_stratum_promotion"] = "unsupported-any-pass"
    policy["thresholds"].update({
        "minimum_adoption_rate": 0.5,
        "accuracy_noninferiority_margin": 0.02,
        "provider_token_equivalence_margin": 5,
        "cost_equivalence_margin_usd": 0.001,
    })
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
    policy = copy.deepcopy(load_policy())
    policy["status"] = "active"
    policy["unresolved"] = []
    policy["thresholds"].update({
        "minimum_adoption_rate": 0.5,
        "accuracy_noninferiority_margin": 0.02,
        "provider_token_equivalence_margin": 5,
        "cost_equivalence_margin_usd": 0.001,
        field: value,
    })
    record = _record()
    if field == "maximum_exclusion_rate":
        record["comparability"]["per_arm_loss"]["B"]["exclusion_rate"] = 0.01
    elif field == "minimum_paired_retention":
        record["comparability"]["metrics"]["paired_n_retention"] = 0.99
    elif field == "minimum_excluded_jaccard":
        record["comparability"]["metrics"]["excluded_jaccard"] = 0.99
    verdict = evaluate_claim(record, policy)
    assert verdict["accepted"] is False
    assert reason in verdict["reasons"]


@pytest.mark.parametrize(
    ("field", "value", "reason"),
    [
        ("minimum_seeds", 6, "minimum_seeds"),
        ("minimum_paired_observations", 101, "minimum_paired_observations"),
        ("minimum_adoption_rate", 0.9, "minimum_adoption_rate"),
    ],
)
def test_every_count_or_adoption_threshold_can_reject(field, value, reason):
    policy = copy.deepcopy(load_policy())
    policy["status"] = "active"
    policy["unresolved"] = []
    policy["thresholds"].update({
        "minimum_adoption_rate": 0.5,
        "accuracy_noninferiority_margin": 0.02,
        "provider_token_equivalence_margin": 5,
        "cost_equivalence_margin_usd": 0.001,
        field: value,
    })
    verdict = evaluate_claim(_record(), policy)
    assert verdict["accepted"] is False
    assert reason in verdict["reasons"]


def test_accuracy_and_equivalence_margins_change_outcome_classification():
    policy = copy.deepcopy(load_policy())
    policy["status"] = "active"
    policy["unresolved"] = []
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
    policy = copy.deepcopy(load_policy())
    policy["status"] = "active"
    policy["unresolved"] = []
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
