from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

import pytest

from jseval.utility_claim_policy import (
    MANDATORY_REQUIREMENTS,
    SUPPORTED_REQUIREMENTS,
    canonical_bytes,
    evaluate_claim,
    load_policy,
    load_previous_policy,
    load_superseded_policy,
    policy_digest,
)
from jseval.utility_question_level import (
    METHOD_ID as QUESTION_LEVEL_METHOD_ID,
    question_level_statistics,
)
from tests.test_corpus_inject import _certification_snapshot_fixture

# Derived from the checked-in ACTIVE policy, not hand-pinned: the fixture below
# mirrors what the real producer composes for the certified cohort, and the
# refusal test asserts the same vocabulary the gate reads. Amendment 1
# (2026-07-28) narrowed this to ["1_hop"] because the certified hero corpora are
# 100% 1_hop by construction; deriving it here means a later corpus that DOES
# carry a second schema updates both sites at once.
_KNOWN_SCHEMAS = tuple(load_policy()["required_schema_strata"]["known_schemas"])


def _record(seed_ids: list[int] | None = None) -> dict:
    seed_ids = [0, 1, 2, 3, 4] if seed_ids is None else seed_ids
    expected_cells = [
        f"{condition}|{seed}|q{query}"
        for condition in ("A", "B")
        for seed in seed_ids
        for query in range(20)
    ]
    n_attempted = len(seed_ids) * 20
    certification = _certification_snapshot_fixture()
    record = {
        "schema": "utility-comparison.v1",
        "schema_version": 2,
        "seed_count": len(seed_ids),
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
                "A": {"n_attempted": n_attempted, "n_planned": n_attempted, "n_pending": 0, "exclusion_rate": 0.0},
                "B": {"n_attempted": n_attempted, "n_planned": n_attempted, "n_pending": 0, "exclusion_rate": 0.0},
            },
        },
        "coverage": {"contamination_class": "private-synthetic"},
        "tool_call_assertions": {
            "B": {
                "cells_total": n_attempted,
                "cells_with_mcp_surface_verified": n_attempted,
                "cells_mcp_surface_unverified": 0,
                "cells_with_leak_suspect": 0,
                "observed_mcp_tool_surface_hashes": ["f" * 64],
                "observed_mcp_tool_surface_consistent": True,
                "cells_with_exposure_mode_verified": n_attempted,
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
                "n_expected_cells": n_attempted * 2,
                "n_observed_cells": n_attempted * 2,
                "n_pending_cells": 0,
                "n_expected_pairs": n_attempted,
                "n_paired_observations": n_attempted,
                "n_per_protocol_pairs": n_attempted,
                "paired_retention": 1.0,
                "excluded_jaccard": 1.0,
                "per_arm_loss": {
                    "A": {"n_expected": n_attempted, "n_attempted": n_attempted, "n_completed": n_attempted,
                          "n_excluded": 0, "n_pending": 0, "exclusion_rate": 0.0},
                    "B": {"n_expected": n_attempted, "n_attempted": n_attempted, "n_completed": n_attempted,
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
                    "n_paired_observations": n_attempted,
                    "accuracy": {"delta_ci95": [-0.01, 0.05]},
                    "provider_cache_creation_input_tokens": {"delta_ci95": [-30, -10]},
                    "cost_usd": {"delta_ci95": [-0.03, -0.01]},
                    "adoption": {"with_tool": {"adoption_rate": 0.8}},
                }
            }
        },
    }
    _sync_v3_reporting(record)
    _sync_v5_reporting(record)
    return record


def _sync_v5_reporting(record: dict) -> None:
    """Mirror the producer's v5 `question_level` block on every ITT stratum.

    Built by calling the REAL producer (`question_level_statistics`) over a
    paired set reconstructed from the stratum's own `campaign_identity`, with
    the SAME `seed_material` recipe `utility_recompose` uses — so a fixture that
    appends or mutates a stratum stays consistent with what the harness would
    have composed for it, rather than carrying a hand-pinned block that no
    producer could emit (`unreachable-seed-green`).

    The two arms are deliberately IDENTICAL, so every per-question delta is 0
    and the cluster interval is a degenerate `[0.0, 0.0]`. That is what keeps
    this fixture's purpose intact: it exists to exercise the OTHER gates, and a
    zero-difference stratum satisfies any non-inferiority margin, exactly as its
    hand-pinned cell-level `accuracy.delta_ci95` always did. The non-degenerate
    behaviour of the estimators is covered by
    `tests/test_utility_question_level.py` and by the v5 gate tests below.
    """
    policy = load_policy()
    thresholds = policy["thresholds"]
    for cell in record["estimands"]["intention_to_treat"]["strata"]:
        expected = (cell.get("campaign_identity") or {}).get("expected_cells") or []
        pairs = {}
        for value in expected:
            condition, seed, qid = str(value).split("|", 2)
            if condition != "A":
                continue
            correct = (int(seed) + len(qid)) % 2 == 0
            pairs[f"{seed}|{qid}"] = {
                "seed": int(seed), "a_correct": correct, "c_correct": correct,
            }
        cell["question_level"] = question_level_statistics(
            pairs,
            seed_material={
                "method": QUESTION_LEVEL_METHOD_ID,
                "stratum_id": cell.get("stratum_id"),
                "corpus_signature": cell.get("corpus_signature"),
                "query_identity_sha256": (cell.get("query_identity") or {}).get("sha256"),
                "n_expected_cells": len(expected),
            },
            alpha=record["statistical_alpha"],
            permutation_draws=thresholds["minimum_permutation_draws"],
            bootstrap_draws=thresholds["minimum_cluster_bootstrap_draws"],
        )


def _sync_v3_reporting(record: dict) -> None:
    """Mirror the two producer-emitted reporting blocks the v3 policy requires.

    `utility_recompose._project_estimands` always emits the per-arm completion
    estimand alongside ITT, and `utility_comparison._compose_cell` attaches
    `schema_stratified.by_stratum` to every measured cell whose queries carry a
    `question_type` — including a single-schema cell, which reports its one
    schema (`_default_schema_stratify` returns `None` only when NO query is
    tagged). The fixture reproduces BOTH from its own ITT strata rather
    than hand-pinning them, so a test that appends a stratum stays consistent
    with what the real producer would have composed for it (the
    `unreachable-seed-green` discipline)."""
    cells = record["estimands"]["intention_to_treat"]["strata"]
    record["estimands"]["completion"] = {
        "role": "secondary",
        "source": "measured",
        "strata": [
            {
                "stratum_id": cell["stratum_id"],
                "corpus": cell["corpus"],
                "model": cell["model"],
                "by_arm": {
                    arm: {
                        "n_expected": cell["per_arm_loss"][arm]["n_expected"],
                        "n_attempted": cell["per_arm_loss"][arm]["n_attempted"],
                        "n_completed": cell["per_arm_loss"][arm]["n_completed"],
                        "n_exhausted": 0,
                        "completion_rate": 1.0,
                    }
                    for arm in ("A", "B")
                },
            }
            for cell in cells
        ],
    }
    measured = record.setdefault("measured", {})
    for cell in cells:
        by_model = measured.setdefault(cell["corpus"], {})
        block = by_model.setdefault(cell["model"], {})
        block.setdefault("primary_arm", "addition_b")
        block["schema_stratified"] = {"by_stratum": {
            schema: {"n_paired_observations": cell["n_paired_observations"] // 2,
                     "accuracy": {"delta_ci95": [-0.01, 0.05]}}
            for schema in _KNOWN_SCHEMAS
        }}


def _load_v3_policy() -> dict:
    """The v3 document, by path.

    `load_previous_policy()` follows the ACTIVE policy's immediate predecessor
    and now resolves to v4, so the chain tests that still need the v3 link read
    it explicitly rather than through a helper whose target moves each
    ratification.
    """
    return json.loads(
        (Path(__file__).parents[1] / "utility-claim-policy.v3.json")
        .read_text(encoding="utf-8")
    )


def _set_primary_accuracy_interval(cell: dict, interval: list) -> None:
    """Set the accuracy interval the OUTCOME CLASSIFIER reads, on both tiers.

    Under v5 the per-stratum accuracy outcome reads the question-level cluster
    interval and the cell-level one is descriptive (782 §I second-pass: the
    cell-level test counts a question's seed replicates as independent). These
    tests exercise the CLASSIFIER, not the estimators, so they must move the
    primary; both tiers are set so the fixture stays internally coherent and the
    same call keeps expressing the same scenario under either source.

    Call AFTER any `_sync_tool_assertion_counts`, which re-derives the
    question-level block from the stratum's paired grid.
    """
    cell["accuracy"]["delta_ci95"] = list(interval)
    block = cell.get("question_level")
    if isinstance(block, dict):
        block["delta_ci"] = list(interval)
        block["delta_ci_percentile"] = list(interval)
        block["aggregate_delta"] = (interval[0] + interval[1]) / 2.0


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
    # A test that appends a stratum must also carry that stratum's producer-emitted
    # completion + schema-stratification blocks (v3 requirements) -- same resync
    # point, so there is one place to keep a mutated fixture self-consistent.
    _sync_v3_reporting(record)
    _sync_v5_reporting(record)


def test_checked_in_policy_is_ratified_v5_three_stratum_sonnet_hero():
    """Ratification (2026-07-29, tempdoc 791 axis 4): the checked-in ACTIVE
    policy is `agent-utility-public-v5`, carrying forward the tempdoc 782 §E.1
    three-stratum sonnet hero matrix VERBATIM — enron-1k, enron-10k, legal-1k,
    verbose, seeds {0,1,2}, 20q. legal-10k is EXCLUDED (771 §E M5), and haiku is
    not run in this campaign.

    Supersedes the 2026-07-17 four-stratum haiku confirmatory activation, its
    2026-07-21 v2 amendment (tempdoc 755 §J, whose rate-based
    verified_tool_surface semantics carry over unchanged — 782 §E.2 R2), the
    2026-07-28 v3 ratification (766 §G decision 2), and the 2026-07-28 v4
    ratification (782 §J, additive `certified_query_subset`), which v5 changes
    in exactly one respect: the additive `question_level_primary` requirement
    and the two resampling-effort floors it needs."""
    policy = load_policy()
    jsonschema = pytest.importorskip("jsonschema")
    schema_path = Path(__file__).parents[1] / "utility-claim-policy.v1.schema.json"
    jsonschema.validate(policy, json.loads(schema_path.read_text(encoding="utf-8")))

    assert policy["status"] == "active"
    assert policy["unresolved"] == []
    assert policy["policy_id"] == "agent-utility-public-v5"
    assert policy["thresholds"]["minimum_surface_verification_rate"] == 0.9
    assert policy["requirements"]["question_level_primary"] is True
    assert policy["thresholds"]["minimum_permutation_draws"] == 20000
    assert policy["thresholds"]["minimum_cluster_bootstrap_draws"] == 20000
    assert [item["stratum_id"] for item in policy["required_strata"]] == [
        "en-email-enron-raw|mixed/en-email-enron-raw-1k-verbose|1000|verbose|sonnet",
        "en-email-enron-raw|mixed/en-email-enron-raw-10k-verbose|10000|verbose|sonnet",
        "en-legal-clerc|mixed/en-legal-clerc-1k-verbose|1000|verbose|sonnet",
    ]
    assert all(item["seed_ids"] == [0, 1, 2] and item["query_count"] == 20
               for item in policy["required_strata"])
    thresholds = policy["thresholds"]
    assert thresholds["minimum_seeds"] == 3
    assert thresholds["minimum_paired_observations"] == 54
    assert thresholds["minimum_adoption_rate"] == 0.9
    assert thresholds["accuracy_noninferiority_margin"] == 0.1
    # A fixture record (wrong strata) must NOT promote under the active policy —
    # required_strata_exact fails, not policy_unresolved.
    verdict = evaluate_claim(_record(), policy)
    assert verdict["accepted"] is False
    assert verdict["policy_hash"] == policy_digest(policy)


def test_v3_ratification_tuned_no_threshold_and_dropped_legal_10k():
    """The ratification's composition rule, enforced literally rather than in
    prose: v3 = v3-DRAFT machinery + v2's thresholds VERBATIM + decision-2 strata.

    Comparing v3's thresholds against the SUPERSEDED v2 document byte-for-byte on
    every shared key is what makes "no threshold was tuned during ratification" a
    mechanical property. The only permitted difference is the additive
    closed-book ceiling, which has no v2 counterpart."""
    policy = load_policy()
    superseded = load_superseded_policy()
    previous = load_previous_policy()
    v3 = _load_v3_policy()

    # The supersede chain, asserted link by link: v2 -> v3 -> v4 -> v5 (ACTIVE).
    assert superseded["status"] == "superseded"
    assert superseded["superseded_by"] == v3["policy_id"] == "agent-utility-public-v3"
    assert v3["status"] == "superseded"
    assert v3["superseded_by"] == previous["policy_id"] == "agent-utility-public-v4"
    assert previous["status"] == "superseded"
    assert previous["superseded_by"] == policy["policy_id"]
    # v5 carries v4's (and so v3's) numbers forward untouched on every shared
    # key, so comparing the ACTIVE policy against the v2 byte-source still
    # enforces "no threshold was ever tuned". The two v5 resampling-effort
    # floors are NEW keys with no predecessor counterpart.
    assert set(policy["thresholds"]) - set(previous["thresholds"]) == {
        "minimum_permutation_draws", "minimum_cluster_bootstrap_draws",
    }
    assert set(previous["thresholds"]) - set(policy["thresholds"]) == set()
    assert previous["thresholds"] == v3["thresholds"]
    assert all(
        policy["thresholds"][name] == previous["thresholds"][name]
        for name in previous["thresholds"]
    )

    shared = set(policy["thresholds"]) & set(superseded["thresholds"])
    assert {name: policy["thresholds"][name] for name in sorted(shared)} == {
        name: superseded["thresholds"][name] for name in sorted(shared)
    }
    # The additive keys are the ONLY divergence, in the later direction only.
    assert set(policy["thresholds"]) - set(superseded["thresholds"]) == {
        "maximum_closed_book_accuracy",
        "minimum_permutation_draws", "minimum_cluster_bootstrap_draws",
    }
    assert set(superseded["thresholds"]) - set(policy["thresholds"]) == set()

    strata = policy["required_strata"]
    assert len(strata) == 3
    assert all(item["requested_model"] == "sonnet" for item in strata)
    assert not any(
        item["corpus_member"] == "en-legal-clerc" and item["size"] == 10000
        for item in strata
    ), "legal-10k is excluded by founder decision 2 (766 §G / 782 §E.1)"
    assert policy["requirements"]["closed_book_at_hero_tier"] is True
    assert policy["hero_tier"]["requested_model_class"] == "sonnet-or-stronger"
    assert policy["policy_changelog"][0]["policy_id"] == policy["policy_id"]
    # v4's own composition rule: v3 verbatim + exactly ONE additive requirement.
    assert (set(previous["requirements"]) - set(v3["requirements"])
            == {"certified_query_subset"})
    assert set(v3["requirements"]) - set(previous["requirements"]) == set()
    # v5's own composition rule: v4 verbatim + exactly ONE additive requirement.
    assert (set(policy["requirements"]) - set(previous["requirements"])
            == {"question_level_primary"})
    assert set(previous["requirements"]) - set(policy["requirements"]) == set()
    assert policy["required_schema_strata"] == previous["required_schema_strata"]
    assert policy["hero_tier"] == previous["hero_tier"]
    assert policy["triple_reporting_semantics"] == previous["triple_reporting_semantics"]
    # Everything v4 already carried is byte-identical in v5 — the composition
    # rule is mechanical, not prose.
    assert policy["required_strata"] == previous["required_strata"]
    assert (policy["certified_query_subset_semantics"]
            == previous["certified_query_subset_semantics"])
    assert (policy["verified_tool_surface_semantics"]
            == previous["verified_tool_surface_semantics"])
    assert policy["policy_changelog"][1:] == previous["policy_changelog"]


# --- v3 additive gates: the REFUSAL branch ----------------------------------
#
# The happy path is covered by every `_active_policy()` test above (the fixture
# now carries the producer's completion + schema blocks). These three prove each
# gate can still REJECT -- without them a gate inverted to always-True ships
# green, since a passing gate and an absent gate are indistinguishable from
# `accepted is True` alone. Each asserts the SPECIFIC gate name surfaces.

def _failed_gate_names(verdict: dict) -> set[str]:
    return {item["name"] for item in verdict["gates"] if not item["passed"]}


def test_completion_triple_reported_rejects_when_completion_estimand_is_missing():
    """762 §T4: the ITT headline may not be published without its completion
    sibling. Stripping `estimands.completion` must fail THIS gate by name."""
    record = _record()
    del record["estimands"]["completion"]

    verdict = evaluate_claim(record, _active_policy(record))

    assert "completion_triple_reported" in verdict["reasons"]
    assert "completion_triple_reported" in _failed_gate_names(verdict)
    assert verdict["accepted"] is False


def test_schema_strata_reported_rejects_when_the_breakdown_is_missing():
    """768 D4 / v3 `required_schema_strata`: a measured cell with no
    `schema_stratified` block cannot satisfy `require_all_present`."""
    record = _record()
    for by_model in record["measured"].values():
        for cell in by_model.values():
            del cell["schema_stratified"]

    verdict = evaluate_claim(record, _active_policy(record))

    assert "schema_strata_reported" in verdict["reasons"]
    gate = next(item for item in verdict["gates"]
                if item["name"] == "schema_strata_reported")
    assert gate["passed"] is False
    assert gate["observed"][0]["missing"] == list(_KNOWN_SCHEMAS)
    assert verdict["accepted"] is False


def _closed_book_gate(verdict: dict) -> dict:
    return next(item for item in verdict["gates"]
                if item["name"] == "closed_book_at_hero_tier")


def test_closed_book_at_hero_tier_rejects_without_a_measured_closed_book_number():
    """768 item 6 / v3 `hero_tier`: with no measured closed-book accuracy there is
    nothing licensing the retrieval attribution, so the gate refuses.

    Isolation note (honest, not assumed): the closed-book measurement rides the
    707 certification snapshot, which is cross-validated against its own embedded
    certificate — so ANY mutation of it also trips `corpus_certification_complete`.
    That co-failure is asserted rather than glossed. What proves this gate is
    doing independent work is the before/after on the SAME gate: it passes on the
    untouched fixture and fails on the mutated one, which a gate inverted to
    always-True could not satisfy."""
    baseline = evaluate_claim(_record(), _active_policy())
    assert _closed_book_gate(baseline)["passed"] is True

    record = _record()
    for cell in record["estimands"]["intention_to_treat"]["strata"]:
        cell["corpus_certification"]["scientific_gates"]["closed_book"] = {
            "passed": True, "status": "passed",
        }

    verdict = evaluate_claim(record, _active_policy(record))

    assert _closed_book_gate(verdict)["passed"] is False
    assert "closed_book_at_hero_tier" in verdict["reasons"]
    assert _failed_gate_names(verdict) == {
        "closed_book_at_hero_tier", "corpus_certification_complete",
    }
    assert verdict["accepted"] is False


def test_closed_book_at_hero_tier_rejects_accuracy_above_the_ceiling():
    """The ceiling is load-bearing, not decorative: a materially non-zero
    closed-book accuracy means the answers are derivable without the corpus, so
    the with-tool uplift cannot be attributed to retrieval.

    Same snapshot-cross-validation caveat as the test above — the co-failure is
    asserted, and the gate's own observed payload is checked to prove it read the
    mutated number rather than failing incidentally."""
    record = _record()
    policy = _active_policy(record)
    ceiling = policy["thresholds"]["maximum_closed_book_accuracy"]
    for cell in record["estimands"]["intention_to_treat"]["strata"]:
        observed = (cell["corpus_certification"]["scientific_gates"]
                    ["closed_book"]["observed"])
        observed["closed_book_accuracy"] = ceiling + 0.5

    verdict = evaluate_claim(record, policy)

    gate = _closed_book_gate(verdict)
    assert gate["passed"] is False
    assert gate["threshold"] == ceiling
    assert gate["observed"][0]["closed_book_accuracy"] == ceiling + 0.5
    assert "closed_book_at_hero_tier" in verdict["reasons"]
    assert verdict["accepted"] is False


# --- v4 `certified_query_subset` (tempdoc 782 §J / FREEZE DEFECT #2) ---------
#
# v3's `corpus_certification_complete` required `cert.query_count ==
# cell.query_count` EXACTLY, which refused every legitimate pre-registered
# SUBSET run: the 781 certifications certify the 50-query committed gold set
# while the frozen 782 §E.1 design measures the pre-registered 20-qid leading
# prefix. `query_gold_sha256` -- the identity that actually chains the certified
# queries -- matched all along. v4 replaces the count comparison with a
# three-legged check that is STRICTER for a subset and unchanged for a full run.

_SUBSET_QID_SHA256 = hashlib.sha256(
    ("\n".join(f"q{index + 1:04d}" for index in range(20)) + "\n").encode("utf-8")
).hexdigest()


def _subset_record() -> dict:
    """The hero-cohort shape: a certification over the FULL 50-query committed
    gold set, a run over the pre-registered 20-qid leading prefix.

    Only the certification's `query_count` moves -- `query_gold_sha256` still
    chains the whole committed file, exactly as the real harness emits it (it
    hashes the queries file's bytes and truncates only the sample rows)."""
    record = _record()
    for cell in record["estimands"]["intention_to_treat"]["strata"]:
        cell["corpus_certification"] = _certification_snapshot_fixture(query_count=50)
    return record


def _subset_policy(record: dict, qid_sha: str | None = _SUBSET_QID_SHA256) -> dict:
    policy = _active_policy(record)
    assert policy["requirements"]["certified_query_subset"] is True
    for item in policy["required_strata"]:
        if qid_sha is None:
            item.pop("qid_list_sha256", None)
        else:
            item["qid_list_sha256"] = qid_sha
    return policy


def _certification_gate(verdict: dict) -> dict:
    return next(item for item in verdict["gates"]
                if item["name"] == "corpus_certification_complete")


def test_certified_query_subset_accepts_the_pre_registered_subset():
    """The defect's direct regression: a 20-of-50 subset run passes ONLY when the
    record's derived qid-list digest equals the policy's pre-registered one.

    The before/after on the SAME gate is what proves the new branch does real
    work (a branch inverted to always-True could not produce the `False` half):
    the identical record is REFUSED by a policy that does not declare the
    requirement, because that is v3's literal count comparison."""
    record = _subset_record()

    accepted = evaluate_claim(record, _subset_policy(record))
    assert _certification_gate(accepted)["passed"] is True
    assert "corpus_certification_complete" not in accepted["reasons"]
    assert accepted["accepted"] is True

    v3_shaped = _subset_policy(record)
    v3_shaped["requirements"].pop("certified_query_subset")
    refused = evaluate_claim(record, v3_shaped)
    assert _certification_gate(refused)["passed"] is False
    assert "corpus_certification_complete" in refused["reasons"]
    assert refused["accepted"] is False


def test_certified_query_subset_fails_closed_without_a_pre_registered_identity():
    """Fail-closed, not permissive: a count mismatch with NO pre-registered
    subset digest in the policy is refused. Otherwise v4 would be a blanket
    relaxation of the count rule rather than a subset-identity check."""
    record = _subset_record()

    verdict = evaluate_claim(record, _subset_policy(record, None))

    assert _certification_gate(verdict)["passed"] is False
    assert "corpus_certification_complete" in verdict["reasons"]
    assert verdict["accepted"] is False


def test_certified_query_subset_fails_on_a_mismatched_subset_digest():
    """A subset that is not the pre-registered one is refused — this is the leg
    that makes the gate a cryptographic check rather than a `<=` on counts."""
    record = _subset_record()

    verdict = evaluate_claim(record, _subset_policy(record, "a" * 64))

    assert _certification_gate(verdict)["passed"] is False
    assert "corpus_certification_complete" in verdict["reasons"]
    assert verdict["accepted"] is False


def test_certified_query_subset_fails_when_the_qid_set_is_not_a_leading_prefix():
    """Truncation in committed order is the ONLY certification-preserving
    selection (any other subset needs its own gold file, which breaks
    `query_gold_sha256`). A hole in the ordinal set yields NO derivable subset
    identity, so the gate refuses even though the digest would be re-derivable
    from some other vocabulary."""
    record = _subset_record()
    for cell in record["estimands"]["intention_to_treat"]["strata"]:
        cell["campaign_identity"]["expected_cells"] = [
            value.replace("|q19", "|q25")
            for value in cell["campaign_identity"]["expected_cells"]
        ]

    verdict = evaluate_claim(record, _subset_policy(record))

    assert _certification_gate(verdict)["passed"] is False
    assert verdict["accepted"] is False


def test_certified_query_subset_refuses_a_superset_of_the_certified_set():
    """`cell.query_count <= cert.query_count` is a real leg: a run claiming MORE
    queries than the certification covers is not a subset and is refused
    regardless of any declared digest."""
    record = _subset_record()
    for cell in record["estimands"]["intention_to_treat"]["strata"]:
        cell["corpus_certification"] = _certification_snapshot_fixture(query_count=5)

    verdict = evaluate_claim(record, _subset_policy(record))

    assert _certification_gate(verdict)["passed"] is False
    assert verdict["accepted"] is False


def test_certified_query_subset_leaves_full_count_runs_byte_identical():
    """The conditional-gate discipline, asserted directly: a policy that does
    NOT declare `certified_query_subset` evaluates any record exactly as the
    committed v3 document does, so no historical verdict moves.

    Compared as canonical bytes with the policy identity removed (`policy_id` /
    `policy_hash` necessarily differ between two different documents — that is
    the re-pin the digest test in `test_utility_evidence.py` covers)."""
    pre_supersede_v3 = _load_v3_policy()
    assert pre_supersede_v3.pop("superseded_by") == "agent-utility-public-v4"
    pre_supersede_v3["status"] = "active"
    v4_without_the_requirement = copy.deepcopy(load_previous_policy())
    assert v4_without_the_requirement.pop("superseded_by") == "agent-utility-public-v5"
    v4_without_the_requirement["status"] = "active"
    v4_without_the_requirement["requirements"].pop("certified_query_subset")

    for record in (_record(), _subset_record()):
        left = evaluate_claim(record, pre_supersede_v3)
        right = evaluate_claim(record, v4_without_the_requirement)
        for verdict in (left, right):
            del verdict["policy_id"]
            del verdict["policy_hash"]
        assert canonical_bytes(left) == canonical_bytes(right)

    # …and that shared behaviour is v3's literal rule: equal counts pass,
    # unequal counts fail. (Passes for the RIGHT reason, not incidentally.)
    assert _certification_gate(
        evaluate_claim(_record(), v4_without_the_requirement))["passed"] is True
    assert _certification_gate(
        evaluate_claim(_subset_record(), v4_without_the_requirement))["passed"] is False


def test_checked_in_v4_policy_pins_the_frozen_782_subset_digests():
    """The pre-registered digests are copied from the frozen `cells.v1.json`
    (§E.8, frozen before any measured cell) — not minted during this fix. Read
    both sides and compare, so a drift in either file fails here."""
    cells = json.loads(
        (Path(__file__).parents[1] / "782-hero" / "cells.v1.json")
        .read_text(encoding="utf-8")
    )
    frozen = {item["stratum_id"]: item["qid_list_sha256"] for item in cells["strata"]}
    declared = {
        item["stratum_id"]: item["qid_list_sha256"]
        for item in load_policy()["required_strata"]
    }
    assert declared == frozen
    assert set(declared.values()) == {_SUBSET_QID_SHA256}


# --- v5 `question_level_primary` (2026-07-29, tempdoc 791 axis 4) -----------
#
# The defect: the cell-level exact McNemar over the (seed, qid) grid treats a
# question's seed replicates as independent observations, inflating n by the
# replicate factor. Measured on the 782 hero cohort's decisive stratum,
# cell-level p=0.045 vs question-level sign-flip p=0.136. v5 promotes the
# question-level pair to primary; the cell-level numbers stay REPORTED.

def _question_level_gate(verdict: dict) -> dict:
    return next(item for item in verdict["gates"]
                if item["name"] == "question_level_primary_reported")


def test_question_level_primary_rejects_when_the_block_is_missing():
    """A record with no question-level statistics cannot back a v5 primary
    claim, and must not silently fall back to the cell-level numbers the
    requirement exists to demote."""
    record = _record()
    for cell in record["estimands"]["intention_to_treat"]["strata"]:
        del cell["question_level"]

    verdict = evaluate_claim(record, _active_policy(record))

    assert _question_level_gate(verdict)["passed"] is False
    assert _question_level_gate(verdict)["observed"][0]["defects"] == ["absent"]
    assert "question_level_primary_reported" in verdict["reasons"]
    assert verdict["accepted"] is False


@pytest.mark.parametrize(
    ("mutation", "defect"),
    [
        ({"permutation_draws": 19999}, "permutation_draws_below_policy_floor"),
        ({"bootstrap_draws": 19999}, "bootstrap_draws_below_policy_floor"),
        ({"n_qids": 1}, "fewer_than_two_question_clusters"),
        ({"signflip_p_two_sided": 1.5}, "permutation_p_out_of_range"),
        ({"delta_ci": [0.1]}, "cluster_interval_malformed"),
        ({"delta_ci": [0.4, -0.4]}, "cluster_interval_malformed"),
        ({"ci_method": "eyeballed"}, "interval_method_unnamed"),
        ({"ci_alpha": 0.1}, "interval_alpha_disagrees_with_policy"),
        ({"unit": "cell"}, "unit_is_not_the_question"),
        ({"method": "something-else"}, "unknown_method"),
        ({"rng_seed": 12345}, "rng_seed_does_not_re_derive_from_its_material"),
        ({"rng_seed_bootstrap": 7}, "rng_streams_not_derived_from_the_recorded_seed"),
    ],
)
def test_question_level_primary_fails_closed_on_each_malformation(mutation, defect):
    """Every branch of the fail-closed contract, one at a time.

    Without this parametrization a gate inverted to always-True would ship
    green: a passing gate and an absent gate look identical from
    `accepted is True` alone, and a single refusal case only proves ONE branch
    bites."""
    record = _record()
    for cell in record["estimands"]["intention_to_treat"]["strata"]:
        cell["question_level"].update(mutation)

    verdict = evaluate_claim(record, _active_policy(record))

    assert defect in _question_level_gate(verdict)["observed"][0]["defects"]
    assert _question_level_gate(verdict)["passed"] is False
    assert "question_level_primary_reported" in verdict["reasons"]
    assert verdict["accepted"] is False


def test_question_level_primary_moves_the_outcome_the_cell_level_interval_would_give():
    """The switch does real WORK, not just extra reporting.

    One record, two policies. Its CELL-level interval is tight enough to read as
    equivalence; its QUESTION-level cluster interval — the honest one, because
    it does not count a question's seed replicates as independent — is wide
    enough that no accuracy claim survives. v4 promotes it to `null`; v5 demotes
    it to `adoption-only`. That is exactly the 782 §I disagreement, in one
    assertion pair."""
    record = _record()
    cell = record["estimands"]["intention_to_treat"]["strata"][0]
    cell["accuracy"]["delta_ci95"] = [-0.01, 0.01]
    cell["provider_cache_creation_input_tokens"]["delta_ci95"] = [-5, 5]
    cell["cost_usd"]["delta_ci95"] = [-0.005, 0.005]
    cell["question_level"]["delta_ci"] = [-0.30, 0.30]

    v5_policy = _active_policy(record)
    v5_policy["thresholds"].update({
        "minimum_adoption_rate": 0.5, "accuracy_noninferiority_margin": 0.05,
        "provider_token_equivalence_margin": 50, "cost_equivalence_margin_usd": 0.05,
    })
    v4_shaped = copy.deepcopy(v5_policy)
    v4_shaped["requirements"].pop("question_level_primary")

    assert evaluate_claim(record, v4_shaped)["outcome"] == "null"

    v5_verdict = evaluate_claim(record, v5_policy)
    assert v5_verdict["outcome"] == "adoption-only"
    # …and the record says WHICH interval it read, rather than leaving a reader
    # to infer it from the policy id.
    stratum_gate = v5_verdict["stratum_outcomes"][0]["gates"]["question_level_primary"]
    assert stratum_gate["observed"]["accuracy_interval_source"] == "question_level.delta_ci"
    assert stratum_gate["observed"]["delta_ci"] == [-0.30, 0.30]
    # The cell-level McNemar is DEMOTED, never dropped.
    assert "question_level_primary" not in evaluate_claim(
        record, v4_shaped)["stratum_outcomes"][0]["gates"]


def test_question_level_primary_leaves_pre_v5_policies_byte_identical():
    """The conditional-gate discipline, asserted directly (the same shape as the
    v4 subset test above): a policy that does NOT declare
    `question_level_primary` evaluates any record exactly as the committed v4
    document does, so no historical verdict moves.

    Compared as canonical bytes with the policy identity removed (`policy_id` /
    `policy_hash` necessarily differ between two different documents — that is
    the re-pin `test_utility_evidence.py` covers)."""
    pre_supersede_v4 = load_previous_policy()
    assert pre_supersede_v4.pop("superseded_by") == "agent-utility-public-v5"
    assert "question_level_primary" not in pre_supersede_v4["requirements"]
    pre_supersede_v4["status"] = "active"
    v5_without_the_requirement = copy.deepcopy(load_policy())
    v5_without_the_requirement["requirements"].pop("question_level_primary")

    for record in (_record(), _subset_record()):
        left = evaluate_claim(record, pre_supersede_v4)
        right = evaluate_claim(record, v5_without_the_requirement)
        for verdict in (left, right):
            del verdict["policy_id"]
            del verdict["policy_hash"]
        assert canonical_bytes(left) == canonical_bytes(right)
        # Passes for the RIGHT reason: neither appends the v5 gate at all.
        assert not any(item["name"] == "question_level_primary_reported"
                       for item in left["gates"])

    # …and the ACTIVE policy DOES append it, so the byte-identity above is a
    # property of the requirement being absent, not of the gate never firing.
    assert any(item["name"] == "question_level_primary_reported"
               for item in evaluate_claim(_record(), _active_policy())["gates"])


def test_favorable_outcomes_cannot_override_unresolved_policy():
    """The unresolved-blocks-promotion behavior, now tested via a draft fixture
    (the checked-in policy activated 2026-07-17 — tempdoc 624)."""
    record = _record()
    record["measured"]["fixture"]["haiku"]["accuracy"]["delta_ci95"] = [0.5, 0.7]
    record["measured"]["fixture"]["haiku"]["adoption"]["with_tool"]["adoption_rate"] = 1.0
    draft = copy.deepcopy(load_policy())
    draft["status"] = "draft"
    draft["unresolved"] = ["required_strata"]
    verdict = evaluate_claim(record, draft)
    assert verdict["accepted"] is False
    assert verdict["reasons"][0] == "policy_unresolved"


def test_settled_active_policy_is_machine_evaluable_without_posthoc_wording():
    policy = _active_policy()

    verdict = evaluate_claim(_record(), policy)
    assert verdict["accepted"] is True
    assert verdict["outcome"] == "benefit"

    harmed = _record()
    _set_primary_accuracy_interval(
        harmed["estimands"]["intention_to_treat"]["strata"][0], [-0.2, -0.1])
    harmed["estimands"]["intention_to_treat"]["strata"][0]["adoption"]["with_tool"]["adoption_rate"] = 0.1
    accepted_harm = evaluate_claim(harmed, policy)
    assert accepted_harm["accepted"] is True
    assert accepted_harm["outcome"] == "harm"


# --- Seed floor (tempdoc 736 D15/U5) ------------------------------------------
#
# `SEED_FLOOR` (code constant, 3) is deliberately distinct from the policy's
# own `thresholds.minimum_seeds` (owner-configurable, currently 5 in the draft
# policy) -- these tests isolate the NEW floor by relaxing minimum_seeds to 1,
# so a failure here can only be attributed to `seed_floor_met`.

def _single_seed_policy(record: dict) -> dict:
    policy = _active_policy(record)
    # Relax the OTHER scale-sensitive thresholds (pre-existing, unrelated to
    # SEED_FLOOR) so a 1-seed/20-query fixture isolates the new floor alone.
    policy["thresholds"]["minimum_seeds"] = 1
    policy["thresholds"]["minimum_paired_observations"] = 1
    return policy


def test_seed_floor_met_gate_reports_min_seed_count_and_the_floor():
    policy = _active_policy()
    verdict = evaluate_claim(_record(), policy)
    seed_floor_gate = next(g for g in verdict["gates"] if g["name"] == "seed_floor_met")
    assert seed_floor_gate["observed"] == 5
    assert seed_floor_gate["threshold"] == 3
    assert seed_floor_gate["passed"] is True


def test_single_seed_accuracy_claim_is_refused_below_seed_floor():
    """D15: a record below SEED_FLOOR (single-seed here) can never resolve to
    an accuracy-based outcome (benefit/null) -- it demotes to adoption-only
    (still publishable as exploratory/smoke), never silently promoted."""
    record = _record(seed_ids=[0])
    policy = _single_seed_policy(record)

    verdict = evaluate_claim(record, policy)

    assert verdict["stratum_outcomes"][0]["outcome"] == "adoption-only"
    assert verdict["stratum_outcomes"][0]["gates"]["seed_floor_met"] == {
        "observed": 1, "threshold": 3, "passed": False,
    }
    assert verdict["outcome"] != "benefit"
    assert verdict["outcome"] != "null"


def test_single_seed_harmful_claim_still_publishes_below_seed_floor():
    """U5: the seed floor gates ONLY accuracy-based claims -- a valid harmful
    finding remains publishable even below the floor (mirrors how
    minimum_adoption_rate never blocks a harm claim, utility_claim_policy.py
    ~line 470)."""
    record = _record(seed_ids=[0])
    _set_primary_accuracy_interval(
        record["estimands"]["intention_to_treat"]["strata"][0], [-0.2, -0.1])
    policy = _single_seed_policy(record)

    verdict = evaluate_claim(record, policy)

    assert verdict["stratum_outcomes"][0]["outcome"] == "harm"
    assert verdict["stratum_outcomes"][0]["gates"]["seed_floor_met"]["passed"] is False
    assert verdict["outcome"] == "harm"
    assert verdict["accepted"] is True


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
            "accuracy_delta_interval", "minimum_adoption_rate", "seed_floor_met",
            "outcome_resolved",
            # v5 (2026-07-29): the per-stratum record of which interval the
            # accuracy outcome read. Appended, never inserted -- a v1-v4
            # verdict's gate map is unchanged.
            "question_level_primary",
        }


def test_inconclusive_required_stratum_prevents_harm_promotion():
    record = _record()
    first = record["estimands"]["intention_to_treat"]["strata"][0]
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
    second["provider_cache_creation_input_tokens"]["delta_ci95"] = [-100, 100]
    second["cost_usd"]["delta_ci95"] = [-0.1, 0.1]
    second["adoption"]["with_tool"]["adoption_rate"] = 0.1
    record["estimands"]["intention_to_treat"]["strata"].append(second)
    _sync_tool_assertion_counts(record)
    # After the resync (which re-derives every stratum's question-level block
    # from its paired grid), so the primary interval the classifier reads is the
    # one this scenario intends.
    _set_primary_accuracy_interval(first, [-0.2, -0.1])
    _set_primary_accuracy_interval(second, [-0.04, 0.20])

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
    _set_primary_accuracy_interval(stratum, [-0.01, 0.01])
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
    _set_primary_accuracy_interval(stratum, [-0.01, 0.01])
    stratum["provider_cache_creation_input_tokens"]["delta_ci95"] = [-5, 5]
    stratum["cost_usd"]["delta_ci95"] = [-0.005, 0.005]
    assert evaluate_claim(null_record, policy)["outcome"] == "null"

    adoption_only = copy.deepcopy(null_record)
    adoption_stratum = adoption_only["estimands"]["intention_to_treat"]["strata"][0]
    _set_primary_accuracy_interval(adoption_stratum, [-0.04, 0.20])
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
    # Every requirement the evaluator supports is a schema property, and every
    # MANDATORY one is schema-`required`. The v3 ratification (2026-07-28) split
    # these two sets: `completion_triple_reported` / `closed_book_at_hero_tier` /
    # `schema_strata_reported` are additive requirements a policy MAY declare
    # (their gates fire only when declared), so the superseded v2 document stays
    # schema-valid history without back-dating them into it.
    assert SUPPORTED_REQUIREMENTS == set(requirements_schema["properties"])
    assert MANDATORY_REQUIREMENTS == set(requirements_schema["required"])
    assert MANDATORY_REQUIREMENTS < SUPPORTED_REQUIREMENTS


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


# --- tempdoc 755 Track 2: rate-based verified_tool_surface (INERT under v1) ----
#
# When a policy carries `thresholds.minimum_surface_verification_rate`, the gate
# switches from all-cells-verified to: rate >= threshold AND single declared hash
# AND no different hash. A MISSING per-cell hash is a tolerated capture miss; a
# DIFFERENT hash stays fatal regardless of rate. Absent the threshold (v1), the
# gate is byte-identical to before (observed is a plain bool).


def _rate_policy(record: dict, rate: float = 0.9) -> dict:
    policy = _active_policy(record)
    policy["thresholds"]["minimum_surface_verification_rate"] = rate
    return policy


def _surface_gate(verdict: dict) -> dict:
    return next(
        item for item in verdict["gates"] if item["name"] == "verified_tool_surface"
    )


def test_verified_tool_surface_v1_gate_is_bool_and_fails_on_unverified_cell():
    """v1 policy (no minimum_surface_verification_rate): byte-identical behavior --
    the gate observed is a plain bool True/False and a single unverified cell fails
    it. This guards the digest-covered v1 verdict against the Track-2 rate branch.

    The checked-in policy is now v2 (ratified 2026-07-21, carries the rate
    threshold), so this test reconstructs a v1-shaped policy by removing
    minimum_surface_verification_rate — the None-threshold bool branch stays live
    in the evaluator and load-bearing for historical/v1 verdicts, so it keeps
    explicit coverage."""
    record = _record()
    assertions = record["tool_call_assertions"]["B"]
    total = assertions["cells_total"]
    assertions["cells_with_mcp_surface_verified"] = total - 1
    assertions["cells_mcp_surface_unverified"] = 1

    v1_policy = _active_policy(record)
    v1_policy["thresholds"].pop("minimum_surface_verification_rate", None)
    verdict = evaluate_claim(record, v1_policy)

    gate = _surface_gate(verdict)
    assert gate["observed"] is False
    assert gate["threshold"] is True
    assert gate["passed"] is False
    assert verdict["accepted"] is False
    assert "verified_tool_surface" in verdict["reasons"]


def test_verified_tool_surface_rate_passes_above_threshold_and_exposes_rate():
    """0.92 verified >= 0.9 threshold, single declared hash, consistent -> passes;
    the gate observed makes the rate visible as {rate, verified, total}."""
    record = _record()
    assertions = record["tool_call_assertions"]["B"]
    total = assertions["cells_total"]  # 100 attempted with-tool cells
    assertions["cells_with_mcp_surface_verified"] = 92
    assertions["cells_mcp_surface_unverified"] = total - 92

    verdict = evaluate_claim(record, _rate_policy(record, 0.9))

    gate = _surface_gate(verdict)
    assert gate["passed"] is True
    assert gate["threshold"] == 0.9
    assert gate["observed"]["verified"] == 92
    assert gate["observed"]["total"] == 100
    assert gate["observed"]["rate"] == pytest.approx(0.92)
    assert "verified_tool_surface" not in verdict["reasons"]
    assert verdict["accepted"] is True


def test_verified_tool_surface_rate_fails_below_threshold():
    """0.88 verified < 0.9 threshold fails, even though every observed hash matches
    the declared hash (a missing hash is a capture miss, but too many of them)."""
    record = _record()
    assertions = record["tool_call_assertions"]["B"]
    total = assertions["cells_total"]
    assertions["cells_with_mcp_surface_verified"] = 88
    assertions["cells_mcp_surface_unverified"] = total - 88

    verdict = evaluate_claim(record, _rate_policy(record, 0.9))

    gate = _surface_gate(verdict)
    assert gate["passed"] is False
    assert gate["observed"]["rate"] == pytest.approx(0.88)
    assert verdict["accepted"] is False
    assert "verified_tool_surface" in verdict["reasons"]


def test_verified_tool_surface_rate_fails_fatally_on_second_distinct_hash():
    """A different observed hash is a genuine surface mismatch, never a capture
    miss: it fails the gate at ANY rate (here rate == 1.0) even if the consistency
    flag were (incorrectly) True -- the single-declared-hash / no-different-hash
    checks catch it independently of the rate."""
    record = _record()
    assertions = record["tool_call_assertions"]["B"]
    total = assertions["cells_total"]
    assertions["cells_with_mcp_surface_verified"] = total  # rate == 1.0
    assertions["cells_mcp_surface_unverified"] = 0
    assertions["observed_mcp_tool_surface_hashes"] = ["f" * 64, "e" * 64]
    assertions["observed_mcp_tool_surface_consistent"] = True  # deliberately optimistic

    verdict = evaluate_claim(record, _rate_policy(record, 0.9))

    gate = _surface_gate(verdict)
    assert gate["passed"] is False
    assert gate["observed"]["rate"] == pytest.approx(1.0)
    assert verdict["accepted"] is False
    assert "verified_tool_surface" in verdict["reasons"]


def test_verified_tool_surface_rate_fails_when_single_hash_not_declared():
    """A single observed hash that does NOT equal the declared cohort hash fails
    regardless of a passing rate -- it is a mismatch, not a miss."""
    record = _record()
    assertions = record["tool_call_assertions"]["B"]
    total = assertions["cells_total"]
    assertions["cells_with_mcp_surface_verified"] = total  # rate == 1.0
    assertions["cells_mcp_surface_unverified"] = 0
    assertions["observed_mcp_tool_surface_hashes"] = ["e" * 64]  # != declared "f"*64

    verdict = evaluate_claim(record, _rate_policy(record, 0.9))

    gate = _surface_gate(verdict)
    assert gate["passed"] is False
    assert verdict["accepted"] is False
    assert "verified_tool_surface" in verdict["reasons"]


def test_checked_in_active_policy_evaluates_surface_via_rate_branch():
    """RATIFIED v2 (2026-07-21, tempdoc 755 §J): the CHECKED-IN active policy now
    carries thresholds.minimum_surface_verification_rate, so its
    verified_tool_surface gate evaluates via the #257 rate branch against the REAL
    active policy (not only a synthetic _rate_policy fixture). Observed is the
    {rate, verified, total} shape and threshold is the rate; a within-tolerance
    capture-miss rate (0.92 >= 0.9) passes. This is the runnable proof that the
    rate semantics hold against the policy the harness actually loads."""
    policy = load_policy()
    assert policy["policy_id"] == "agent-utility-public-v5"
    assert policy["thresholds"]["minimum_surface_verification_rate"] == 0.9

    record = _record()
    assertions = record["tool_call_assertions"]["B"]
    total = assertions["cells_total"]  # 100 attempted with-tool cells
    assertions["cells_with_mcp_surface_verified"] = 92
    assertions["cells_mcp_surface_unverified"] = total - 92

    gate = _surface_gate(evaluate_claim(record, policy))
    assert gate["threshold"] == 0.9
    assert gate["observed"]["verified"] == 92
    assert gate["observed"]["total"] == total
    assert gate["observed"]["rate"] == pytest.approx(0.92)
    assert gate["passed"] is True


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


# --- corpus-root identity axis: certification -> stratum -> policy end-to-end
#     (tempdoc 624 confirmatory pre-registration, 2026-07-17) -----------------
#
# This closes the chain the corpus-root axis exists to enable: an attached
# certification (only attachable on a leak-safe run because identity now resolves
# from the dataset ROOT) makes utility_recompose's ITT stratum carry a populated
# member/size/query_variant/stratum_id, which required_strata_exact then matches.


def _certified_pair(member: str, dataset: str, signature: str, model_alias: str = "haiku") -> list[dict]:
    """One A/B paired-cell observation set carrying a certification snapshot in the
    cohort — the shape utility_recompose groups into one ITT stratum."""
    certification = _certification_snapshot_fixture(
        member=member, dataset=dataset, signature=signature)
    expected_cells = [f"{c}|0|q0" for c in ("A", "B")]
    cohort = {
        "corpus_certification": certification,
        "query_identity": {"sha256": "b" * 64, "row_count": 1},
        "campaign_identity": {"conditions": ["A", "B"], "seeds": 1, "expected_cells": expected_cells},
        "exposure_config": {"enable_tool_search": "true", "always_load": False, "exposure_mode": "deferred"},
        "mcp_initialize_identity": {"instructions_sha256": "d" * 64, "server_version": "1.0.0"},
    }
    return [
        {
            "source": {
                "model_alias": model_alias,
                "corpus": {"dataset": dataset, "signature": signature},
                "cohort": cohort,
            },
            "condition": condition,
            "seed": 0,
            "qid": "q0",
            "attempted": True,
            "excluded": False,
            "error": None,
            "correct": condition == "B",
            "cost_usd": 0.1,
            "unique_tokens": 10,
            "num_turns": 2,
            "resolved_model": "claude-haiku-versioned",
            "tool_calls": [{"tool": "mcp__justsearch__search"}] if condition == "B" else [],
        }
        for condition in ("A", "B")
    ]


def test_root_mode_certification_populates_stratum_id_and_matches_required_strata():
    from jseval.utility_recompose import _intention_to_treat_estimand

    observations = _certified_pair("fixture-member", "fixture", "c" * 64)
    estimand = _intention_to_treat_estimand(observations)
    strata = estimand["intention_to_treat"]["strata"]
    assert len(strata) == 1
    stratum = strata[0]

    # utility_recompose derived the identity FROM the attached certification
    # (member/size/query_variant) + grouping dataset/model — not hand-set.
    assert stratum["stratum_id"] == "fixture-member|fixture|1000|verbose|haiku"
    assert stratum["corpus_member"] == "fixture-member"
    assert stratum["corpus_size"] == 1000
    assert stratum["query_variant"] == "verbose"

    record = {"estimands": {"intention_to_treat": {"strata": [stratum]}}}
    base_policy = {
        "status": "active",
        "unresolved": [],
        "thresholds": {},
        "requirements": {"per_stratum_promotion": "all_required_strata_pass"},
        "required_strata": [{
            "stratum_id": stratum["stratum_id"],
            "corpus_member": stratum["corpus_member"],
            "dataset": stratum["corpus"],
            "size": stratum["corpus_size"],
            "query_variant": stratum["query_variant"],
            "requested_model": stratum["model"],
            "query_count": stratum["query_count"],
            "seed_ids": stratum["seed_ids"],
        }],
    }

    def _exact_gate(policy: dict) -> dict:
        verdict = evaluate_claim(record, policy)
        return next(g for g in verdict["gates"] if g["name"] == "required_strata_exact"), verdict

    gate, verdict = _exact_gate(base_policy)
    assert gate["passed"] is True
    assert "required_strata_exact" not in verdict["reasons"]

    mismatched = copy.deepcopy(base_policy)
    mismatched["required_strata"][0]["corpus_member"] = "other-member"
    mismatched["required_strata"][0]["stratum_id"] = "other-member|fixture|1000|verbose|haiku"
    gate, verdict = _exact_gate(mismatched)
    assert gate["passed"] is False
    assert "required_strata_exact" in verdict["reasons"]
