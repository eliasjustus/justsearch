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
            "search_config_cohort_key": "search-1",
            "judge": {"kind": "substring-em"},
        },
        "comparability": {"comparable": True, "reasons": []},
        "coverage": {"contamination_class": "private-synthetic"},
        "tool_call_assertions": {
            "B": {
                "cells_total": 100,
                "cells_with_mcp_surface_verified": 100,
                "cells_with_leak_suspect": 0,
                "observed_mcp_tool_surface_hashes": ["f" * 64],
                "observed_mcp_tool_surface_consistent": True,
            }
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

    verdict = evaluate_claim(_record(), policy)
    assert verdict["accepted"] is True
    assert verdict["outcome"] == "benefit"

    harmed = _record()
    harmed["measured"]["fixture"]["haiku"]["accuracy"]["delta_ci95"] = [-0.2, -0.1]
    rejected = evaluate_claim(harmed, policy)
    assert rejected["accepted"] is False
    assert "accuracy_noninferiority" in rejected["reasons"]
