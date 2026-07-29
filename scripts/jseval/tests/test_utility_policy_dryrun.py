"""Pre-freeze policy dry-run — tempdoc 791 axis 4.

The load-bearing tests here are ARCHAEOLOGY, not fiction: they replay the REAL
frozen tempdoc 782 design (`scripts/jseval/782-hero/cells.v1.json`) against the
REAL superseded policy documents, and assert that the dry-run would have caught
both freeze defects for $0 before a single cell was measured.

* BLOCKER-1 — v3 at freeze required a `2_hop` schema breakdown from corpora that
  are 100% `1_hop` by construction. Caught pre-launch by a refusal gate, but
  only after the design was frozen.
* FREEZE DEFECT #2 — v3/v4-pre-subset-gate compared a 50-query certification
  against a 20-qid design for EXACT count equality. Caught at COMPOSE, after
  ~$278 of measured cells.
"""

from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest

from jseval._paths import REPO_ROOT
from jseval.utility_claim_policy import load_policy, load_previous_policy
from jseval.utility_policy_dryrun import (
    PLACEHOLDER,
    STRUCTURAL,
    UNDETERMINED,
    DryRunError,
    dryrun,
    format_report,
    load_design,
    structural_findings,
    synthesize_record,
)

HERO_DESIGN = REPO_ROOT / "scripts" / "jseval" / "782-hero" / "cells.v1.json"


def _design() -> dict:
    return load_design(HERO_DESIGN)


def _activated(path_name: str) -> dict:
    """A superseded policy document restored to its pre-supersede ACTIVE shape.

    Read from the committed file so it cannot drift from the real history, the
    way the claim-policy re-pin tests reconstruct their predecessors.
    """
    policy = json.loads(
        (REPO_ROOT / "scripts" / "jseval" / path_name).read_text(encoding="utf-8")
    )
    assert policy["status"] == "superseded"
    policy.pop("superseded_by")
    policy["status"] = "active"
    return policy


def _gate(report: dict, name: str) -> dict:
    return next(item for item in report["gates"] if item["name"] == name)


# --- the two historical defects ---------------------------------------------

def test_dryrun_catches_blocker_1_schema_strata_on_the_real_frozen_design():
    """BLOCKER-1, replayed from the real artifacts.

    v3 AT FREEZE declared `known_schemas: ["1_hop", "2_hop"]` with
    `require_all_present: true` (the shape its own Amendment-1 changelog entry
    records it was narrowed FROM). Every certified hero corpus is 100% `1_hop`,
    so no run under the frozen design could ever have reported a `2_hop`
    breakdown. The dry-run says so, per stratum, before any spend."""
    policy = _activated("utility-claim-policy.v3.json")
    amendment = next(
        entry for entry in policy["policy_changelog"] if entry.get("amendment") == 1
    )
    assert amendment["changed"] == [
        'required_schema_strata.known_schemas: ["1_hop", "2_hop"] -> ["1_hop"]'
    ]
    # Restore the pre-amendment shape the changelog names, rather than inventing one.
    policy["required_schema_strata"]["known_schemas"] = ["1_hop", "2_hop"]

    report = dryrun(_design(), policy, repo_root=REPO_ROOT)

    gate = _gate(report, "schema_strata_reported")
    assert gate["passed"] is False
    assert gate["category"] == STRUCTURAL
    assert len(gate["details"]) == 3, "one finding per frozen stratum"
    assert all("'2_hop'" in detail or "2_hop" in detail for detail in gate["details"])
    assert "schema_strata_reported" in report["blocking_gates"]
    assert report["compatible"] is False
    assert report["exit_code"] == 1


def test_dryrun_catches_freeze_defect_2_fifty_vs_twenty_on_the_real_frozen_design():
    """FREEZE DEFECT #2, replayed from the real artifacts.

    The 781 structural certifications certify the 50-query committed gold set;
    the frozen §E.1 design measures the pre-registered 20-qid leading prefix.
    Under v3 (and any policy without `certified_query_subset`) the gate compares
    the counts for exact equality, so 50 != 20 refuses every stratum — the
    defect that cost a compose, not a launch."""
    policy = _activated("utility-claim-policy.v3.json")
    assert "certified_query_subset" not in policy["requirements"]

    report = dryrun(_design(), policy, repo_root=REPO_ROOT)

    gate = _gate(report, "corpus_certification_complete")
    assert gate["passed"] is False
    assert gate["category"] == STRUCTURAL
    assert len(gate["details"]) == 3
    for detail in gate["details"]:
        assert "certifies 50 queries and the design measures 20" in detail
    assert report["compatible"] is False
    assert report["exit_code"] == 1


def test_dryrun_clears_the_certification_gate_once_v4_adds_the_subset_branch():
    """The before/after that proves the catch is real work, not a constant NO.

    Same design, same certifications, same 50-vs-20 counts — only the policy's
    subset branch differs. v3 refuses; v4 passes. A dry-run that reported
    "incompatible" unconditionally could not produce this pair."""
    v3 = _activated("utility-claim-policy.v3.json")
    v4 = _activated("utility-claim-policy.v4.json")
    assert v4["requirements"]["certified_query_subset"] is True

    assert _gate(dryrun(_design(), v3, repo_root=REPO_ROOT),
                 "corpus_certification_complete")["passed"] is False
    v4_report = dryrun(_design(), v4, repo_root=REPO_ROOT)
    assert _gate(v4_report, "corpus_certification_complete")["passed"] is True
    assert v4_report["compatible"] is True
    assert v4_report["exit_code"] == 0


def test_the_active_v5_policy_is_compatible_with_the_frozen_hero_design():
    """v5's own pre-freeze check, run on the one frozen design that exists.

    This is the assertion tempdoc 791 axis 4 asks for: the new policy is
    dry-run against a design-shaped record BEFORE any campaign freezes against
    it, so a v5-vs-design incompatibility cannot survive to run time."""
    report = dryrun(_design(), load_policy(), repo_root=REPO_ROOT)

    assert report["policy_id"] == "agent-utility-public-v5"
    assert report["compatible"] is True
    assert report["exit_code"] == 0
    assert report["blocking_gates"] == []
    assert _gate(report, "question_level_primary_reported")["passed"] is True
    assert _gate(report, "schema_strata_reported")["passed"] is True
    assert _gate(report, "corpus_certification_complete")["passed"] is True


# --- honesty of the classification ------------------------------------------

def test_placeholder_gates_are_named_rather_than_silently_vouched_for():
    """The dry-run must not imply it checked what it stubbed.

    Every gate whose input the design does not pin is reported as `placeholder`
    with the stub named, so "31/31 pass" is never read as "31 verified"."""
    report = dryrun(_design(), load_policy(), repo_root=REPO_ROOT)

    placeholders = {
        item["name"] for item in report["gates"] if item["category"] == PLACEHOLDER
    }
    assert {"verified_tool_surface", "source_identity_complete", "judge_calibration",
            "minimum_adoption_rate", "accuracy_delta_interval"} <= placeholders
    for item in report["gates"]:
        if item["category"] == PLACEHOLDER:
            assert item["details"], f"{item['name']} is stubbed but says nothing"
    # …and the gates the DESIGN actually determines are NOT excused as placeholders.
    assert not placeholders & {
        "required_strata_exact", "corpus_certification_complete",
        "schema_strata_reported", "closed_book_at_hero_tier", "minimum_seeds",
        "question_level_primary_reported", "contamination_class",
    }


def test_a_design_the_policy_does_not_require_fails_on_the_matrix():
    """A design/policy matrix mismatch is structural, not a run-time surprise."""
    design = _design()
    design["campaign"]["model"] = "haiku"

    report = dryrun(design, load_policy(), repo_root=REPO_ROOT)

    gate = _gate(report, "required_strata_exact")
    assert gate["passed"] is False
    assert gate["category"] == STRUCTURAL
    assert "does not measure the policy's pre-registered matrix" in gate["details"][0]
    assert report["exit_code"] == 1


def test_a_seed_count_below_the_policy_floor_is_structural():
    design = _design()
    design["campaign"]["seed_ids"] = [0]
    design["campaign"]["seeds"] = 1

    report = dryrun(design, load_policy(), repo_root=REPO_ROOT)

    assert _gate(report, "minimum_seeds")["category"] == STRUCTURAL
    # …and so is the paired-observation floor the seed count makes unreachable.
    paired = _gate(report, "minimum_paired_observations")
    assert paired["category"] == STRUCTURAL
    assert "at most 20 paired observations" in paired["details"][0]
    assert report["exit_code"] == 1


def test_an_unreadable_gold_file_is_undetermined_never_compatible():
    """Fail-closed on ambiguity: a schema census the harness cannot read is
    reported as `undetermined` and blocks, never silently as a pass."""
    design = _design()
    for stratum in design["strata"]:
        stratum["gold_source_cell"] = "scripts/jseval/does-not-exist/queries.json"

    report = dryrun(design, load_policy(), repo_root=REPO_ROOT)

    gate = _gate(report, "schema_strata_reported")
    assert gate["category"] == UNDETERMINED
    assert "not present in this checkout" in gate["details"][-1]
    assert report["compatible"] is False
    assert report["exit_code"] == 1


def test_a_missing_certification_is_undetermined_never_compatible():
    design = _design()
    for stratum in design["strata"]:
        stratum["corpus_certification"] = "scripts/jseval/does-not-exist/cert.json"

    report = dryrun(design, load_policy(), repo_root=REPO_ROOT)

    assert _gate(report, "corpus_certification_complete")["category"] == UNDETERMINED
    assert report["exit_code"] == 1


def test_a_contamination_class_outside_the_policy_is_structural():
    design = _design()
    design["campaign"]["contamination_class"] = "public-pre-cutoff"

    report = dryrun(design, load_policy(), repo_root=REPO_ROOT)

    assert _gate(report, "contamination_class")["category"] == STRUCTURAL
    assert report["exit_code"] == 1


def test_question_level_primary_needs_at_least_two_question_clusters():
    """v5's own design-reachability check: a one-question design can never
    support a question-clustered test, and that is knowable at freeze."""
    design = _design()
    design["campaign"]["max_queries"] = 1

    findings = structural_findings(design, load_policy(), repo_root=REPO_ROOT)

    assert any(
        item["gate"] == "question_level_primary_reported"
        and item["category"] == STRUCTURAL
        for item in findings
    )


# --- the synthesizer itself ---------------------------------------------------

def test_the_synthetic_record_mirrors_the_designs_declared_shape():
    """The record is derived from the design, not from a hardcoded template —
    otherwise a dry-run would answer a question about some other campaign."""
    design = _design()
    record = synthesize_record(design, load_policy(), repo_root=REPO_ROOT)

    strata = record["estimands"]["intention_to_treat"]["strata"]
    assert [item["stratum_id"] for item in strata] == [
        item["stratum_id"] for item in design["strata"]
    ]
    for cell, declared in zip(strata, design["strata"]):
        assert cell["seed_ids"] == design["campaign"]["seed_ids"]
        assert cell["query_count"] == design["campaign"]["max_queries"]
        assert cell["corpus_signature"] == declared["corpus_signature"]
        assert cell["query_identity"]["sha256"] == declared["query_gold_sha256"]
        assert cell["model"] == design["campaign"]["model"]
        # 2 conditions x 3 seeds x 20 queries, the design's full factorial.
        assert len(cell["campaign_identity"]["expected_cells"]) == 120
        assert cell["question_level"]["n_qids"] == 20


def test_the_synthetic_schema_breakdown_comes_from_the_gold_file_not_the_policy():
    """The BLOCKER-1 catch depends on this. If the synthesizer mirrored the
    POLICY's `known_schemas` the record would satisfy `schema_strata_reported`
    by construction and the defect would be invisible here too."""
    policy = copy.deepcopy(load_policy())
    policy["required_schema_strata"]["known_schemas"] = ["1_hop", "2_hop", "invented"]

    record = synthesize_record(_design(), policy, repo_root=REPO_ROOT)

    for by_model in record["measured"].values():
        for cell in by_model.values():
            assert set(cell["schema_stratified"]["by_stratum"]) == {"1_hop"}


def test_the_synthetic_record_carries_no_measurement():
    """A structural placeholder must never be mistakable for evidence."""
    record = synthesize_record(_design(), load_policy(), repo_root=REPO_ROOT)

    assert record["synthetic"]["source"] == "utility-policy-dryrun.v1"
    for cell in record["estimands"]["intention_to_treat"]["strata"]:
        assert cell["accuracy"]["delta_ci"] == [0.0, 0.0]
        assert cell["question_level"]["aggregate_delta"] == 0.0


def test_a_report_names_its_synthetic_outcome_as_predicting_nothing():
    report = dryrun(_design(), load_policy(), repo_root=REPO_ROOT)
    assert "predicts nothing" in report["synthetic_verdict"]["_note"]
    assert "COMPATIBLE" in format_report(report)


def test_an_unsupported_design_schema_is_refused(tmp_path):
    design = _design()
    design["schema"] = "some-other-design.v9"
    path = tmp_path / "cells.v1.json"
    path.write_text(json.dumps(design), encoding="utf-8")

    with pytest.raises(DryRunError):
        load_design(path)


def test_a_design_with_no_strata_is_refused():
    design = _design()
    design["strata"] = []
    with pytest.raises(DryRunError):
        dryrun(design, load_policy(), repo_root=REPO_ROOT)


# --- the command surface ------------------------------------------------------

def test_cli_exits_zero_and_writes_a_report_for_a_compatible_design(tmp_path):
    """The exit code is the whole point of the command: a freeze step that
    shells out to this must be able to STOP on a non-zero."""
    from click.testing import CliRunner

    from jseval.commands.utility import cmd_utility_policy_dryrun

    out = tmp_path / "dryrun.json"
    result = CliRunner().invoke(
        cmd_utility_policy_dryrun,
        ["--design", str(HERO_DESIGN), "--out", str(out)],
        obj={"json": False},
    )

    assert result.exit_code == 0, result.output
    assert "COMPATIBLE" in result.output
    report = json.loads(out.read_text(encoding="utf-8"))
    assert report["policy_id"] == "agent-utility-public-v5"
    assert report["compatible"] is True


def test_cli_exits_non_zero_when_a_gate_can_never_pass(tmp_path):
    """The 782 replay, through the command a preflight would actually call."""
    from click.testing import CliRunner

    from jseval.commands.utility import cmd_utility_policy_dryrun

    policy_file = tmp_path / "v3-at-freeze.json"
    policy = _activated("utility-claim-policy.v3.json")
    policy["required_schema_strata"]["known_schemas"] = ["1_hop", "2_hop"]
    policy_file.write_text(json.dumps(policy), encoding="utf-8")

    result = CliRunner().invoke(
        cmd_utility_policy_dryrun,
        ["--design", str(HERO_DESIGN), "--policy", str(policy_file)],
        obj={"json": False},
    )

    assert result.exit_code == 1
    assert "INCOMPATIBLE" in result.output
    assert "schema_strata_reported" in result.output
    assert "corpus_certification_complete" in result.output


def test_the_previous_policy_is_v4_and_still_dry_runs_clean():
    """The supersede chain stays dry-runnable: v5's predecessor is v4, and v4 is
    compatible with the frozen design (that is what its own re-compose showed)."""
    previous = load_previous_policy()
    assert previous["policy_id"] == "agent-utility-public-v4"
    report = dryrun(_design(), _activated("utility-claim-policy.v4.json"),
                    repo_root=REPO_ROOT)
    assert report["compatible"] is True
    # v4 declares no question-level requirement, so the gate is never emitted.
    assert not any(item["name"] == "question_level_primary_reported"
                   for item in report["gates"])
