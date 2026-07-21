from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest
from click.testing import CliRunner

from jseval.agent_utility_observations import successful_summaries
from jseval.commands.utility import cmd_utility_recompose
from jseval.agent_manifest import mcp_tool_surface_hash
from jseval.utility_evidence import _OBSERVATION_KEYS, _SOURCE_KEYS, read_evidence, sanitize_observation
from jseval.utility_recompose import finalize_observation_groups, finalize_evidence

_SURFACE = [{
    "name": "mcp__justsearch__search", "description": "Search",
    "input_schema": {"type": "object"},
}]
_SURFACE_HASH = mcp_tool_surface_hash(_SURFACE)


def _observation(condition="A", *, excluded=False, qid="q0") -> dict:
    return {
        "source": {
            "log_file": "raw.json",
            "model_alias": "haiku",
            "corpus": {"dataset": "fixture", "signature": "c" * 64},
            "packages": {},
            "cohort": {
                "source_git_sha": "a" * 40,
                "source_git_dirty": False,
                "source_git_state": {
                    "tracked_diff_sha256": "0" * 64,
                    "untracked_sha256": "0" * 64,
                    "untracked_count": 0,
                    "dirty": False,
                },
                "cli_version": "1",
                "mcp_tool_surface_hash": _SURFACE_HASH,
                "mcp_tool_surface": _SURFACE,
                "judge_kind": "substring-em",
                "prompt_template_hash": "prompt",
                "search_config_cohort_key": "search" if condition == "B" else None,
                "corpus_identity": {"signature": "c" * 64},
                "query_identity": {"sha256": "e" * 64, "row_count": 1},
                "campaign_identity": {
                    "conditions": ["A", "B"], "seeds": 1,
                    "expected_cells": ["A|0|q0", "B|0|q0"],
                },
                "environment": {
                    "captured_at": "private timestamp",
                    "platform": {"system": "Windows", "release": "11", "machine": "AMD64"},
                    "gpu": {"available": True, "name": "GPU", "driver_version": "1",
                            "mem_total_mb": 10, "temp_c": 99},
                    "services": {"PrivateSvc": "RUNNING"},
                    "top_processes": [{"name": "secret.exe", "pid": 123}],
                    "power_plan": "private plan",
                },
                "exposure_config": {
                    "enable_tool_search": "true", "always_load": False,
                    "exposure_mode": "deferred",
                },
                "mcp_initialize_identity": {
                    "instructions": "search the corpus", "instructions_sha256": "d" * 64,
                    "server_version": "1.0.0", "protocol_version": "2025-06-18",
                },
            },
        },
        "condition": condition,
        "seed": 0,
        "qid": qid,
        "attempted": True,
        "excluded": excluded,
        "error": "C:\\Users\\private\\secret.txt timed out" if excluded else None,
        "attempts": 2 if excluded else 1,
        "first_error": "credential-like raw first error" if excluded else None,
        "correct": not excluded,
        "cost_usd": 0.1,
        "unique_tokens": 10,
        "usage": {"input_tokens": 12},
        "model_usage": {"provider-v1": {"inputTokens": 12}},
        "resolved_model": "provider-v1",
        "num_turns": 2,
        "tool_calls": [{"tool": "mcp__justsearch__search", "input": {"query": "private text"}}],
        "tool_calls_blocked": [],
        "disallowed_tool_calls": [],
        "leak_suspect": False,
        "mcp_servers": [{"name": "justsearch", "status": "connected", "url": "secret"}],
        "mcp_tools_offered": 1,
        "mcp_tool_names_offered": ["mcp__justsearch__search"],
        "observed_mcp_tool_surface_hash": _SURFACE_HASH,
        "mcp_surface_unverified": False,
        "mcp_tools_deferred": False,
        "toolsearch_targets": ["mcp__justsearch__search"],
        "tool_call_sequence": [
            {"name": "ToolSearch", "status": "ok"},
            {"name": "mcp__justsearch__search", "status": "ok"},
        ],
        "completion": "third-party copyrighted text",
        "credential": "do-not-export",
    }


def test_sanitizer_denies_raw_content_paths_inputs_and_credentials():
    baseline = sanitize_observation(_observation(excluded=True))
    changed = _observation(excluded=True)
    changed.update({
        "completion": "different raw output",
        "credential": "different secret",
        "absolute_path": "D:\\private",
    })
    changed["tool_calls"][0]["input"] = {"query": "different private text"}
    assert sanitize_observation(changed) == baseline
    encoded = json.dumps(baseline)
    assert "private" not in encoded.lower()
    assert "secret" not in encoded.lower()
    assert baseline["error_class"] == "timeout"
    environment = baseline["source"]["environment"]
    assert set(environment) == {"platform", "gpu"}
    assert set(environment["gpu"]) == {
        "available", "driver_version", "name", "mem_total_mb",
    }


def test_gate_relevant_negative_fields_change_sanitized_bytes():
    baseline = sanitize_observation(_observation(excluded=False))
    errored = sanitize_observation(_observation(excluded=True))
    assert baseline != errored
    leaked = _observation(excluded=False)
    leaked["leak_suspect"] = True
    assert sanitize_observation(leaked) != baseline

    # tempdoc 725 increment 3: the funnel/adoption-audit fields are gate-relevant
    # too -- a status flip or a discovered-vs-not toggle must change sanitized
    # bytes, never silently disappear into an unchanged digest.
    different_targets = _observation(excluded=False)
    different_targets["toolsearch_targets"] = []
    assert sanitize_observation(different_targets) != baseline

    different_sequence = _observation(excluded=False)
    different_sequence["tool_call_sequence"] = [
        {"name": "ToolSearch", "status": "blocked"},
        {"name": "mcp__justsearch__search", "status": "blocked"},
    ]
    assert sanitize_observation(different_sequence) != baseline


def test_toolsearch_targets_free_text_query_cannot_survive_to_the_observation_boundary():
    """tempdoc 725 increment 3 redaction contract: `toolsearch_targets` carries
    ONLY resolved `mcp__justsearch__*` tool names, never a ToolSearch call's
    raw free-text query string -- proven two ways: the sanitizer preserves
    whatever the producer already resolved (never re-derives from raw text,
    so it cannot accidentally admit any), and the schema's pattern constraint
    fails closed if a future producer bug ever let free text through anyway."""
    baseline = sanitize_observation(_observation())
    changed = _observation()
    # A DIFFERENT ToolSearch free-text query would have produced this exact same
    # resolved toolsearch_targets/tool_call_sequence (the producer already
    # collapsed it to names+status before this observation existed) -- so
    # sanitize_observation must be indifferent to it, proving no raw query text
    # is threaded through anywhere in this boundary.
    changed["toolsearch_targets"] = list(baseline["toolsearch_targets"])
    changed["tool_call_sequence"] = [dict(item) for item in baseline["tool_call_sequence"]]
    assert sanitize_observation(changed) == baseline

    jsonschema = pytest.importorskip("jsonschema")
    schema_path = Path(__file__).parents[1] / "agent-utility-observation.v1.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    tampered = sanitize_observation(_observation())
    tampered["toolsearch_targets"] = ["notification jira slack search tools"]
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(tampered, schema)


def test_toolsearch_targets_schema_rejects_trailing_free_text_target():
    """tempdoc 725 review finding #1: the schema's `toolsearch_targets` item
    pattern must be full-grammar-anchored (`^mcp__justsearch__[A-Za-z0-9_]+$`),
    not merely start-anchored -- a value carrying a well-formed name PLUS a
    trailing space and extra token (the shape a prefix-only producer bug would
    have emitted) must fail validation, not pass because the string merely
    starts with `mcp__justsearch`."""
    jsonschema = pytest.importorskip("jsonschema")
    schema_path = Path(__file__).parents[1] / "agent-utility-observation.v1.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    tampered = sanitize_observation(_observation())
    tampered["toolsearch_targets"] = ["mcp__justsearch__search /etc/passwd bob@evil.com"]
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(tampered, schema)


def test_tool_result_digests_echo_leak_absent_from_sanitized_bytes():
    """tempdoc 736 D9 echo-leak assertion (mirrors the level-1 `toolsearch_targets`
    leak test): a result whose RAW content contains a known corpus string must
    produce a sanitized observation whose serialized bytes do NOT contain that
    string -- proven via the real producer (`_tool_result_digest_entry`), not a
    hand-rolled shortcut."""
    pytest.importorskip("inspect_ai")
    from jseval.agent_utility_inspect import _tool_result_digest_entry

    secret = "CORPUS_SECRET_STRING_zz998"
    digest = _tool_result_digest_entry({
        "is_error": False,
        "content": f"Evidence pack: 2 passages from 1 documents ({secret})",
    })
    observation = _observation()
    observation["tool_result_digests"] = [digest]

    sanitized = sanitize_observation(observation)
    encoded = json.dumps(sanitized)
    assert secret not in encoded
    assert sanitized["tool_result_digests"] == [digest]  # digest itself carries no raw text


def test_tool_result_digests_schema_rejects_raw_content_property():
    """The schema structurally forbids a raw-content property on a
    `tool_result_digests` entry -- `additionalProperties: false` fails closed even
    if a future producer bug ever tried to smuggle one through."""
    jsonschema = pytest.importorskip("jsonschema")
    schema_path = Path(__file__).parents[1] / "agent-utility-observation.v1.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    tampered = sanitize_observation(_observation())
    tampered["tool_result_digests"] = [{
        "content_sha256": "0" * 64,
        "content_len": 4,
        "content_is_error": False,
        "content_shape": "text",
        "furniture_markers": {
            "rationale": False, "evidence_pack": False, "coverage": False, "degradation": False,
        },
        "content": "raw corpus text must never validate here",
    }]
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(tampered, schema)


def test_schema_is_strict_at_observation_boundary():
    jsonschema = pytest.importorskip("jsonschema")
    schema_path = Path(__file__).parents[1] / "agent-utility-observation.v1.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    item = sanitize_observation(_observation())
    jsonschema.validate(item, schema)
    item["raw_completion"] = "forbidden"
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(item, schema)


def test_evidence_roundtrip_preserves_semantic_digest(tmp_path):
    observations = [
        _observation("A", qid="q0"),
        _observation("B", qid="q0"),
    ]
    direct = finalize_observation_groups([observations], composed_at="one")
    jsonschema = pytest.importorskip("jsonschema")
    schema = json.loads(
        (Path(__file__).parents[1] / "utility-comparison.v1.schema.json")
        .read_text(encoding="utf-8")
    )
    jsonschema.validate(json.loads(json.dumps(direct)), schema)
    path = tmp_path / "observations.v1.jsonl"
    path.write_text(
        "".join(json.dumps(sanitize_observation(item), sort_keys=True) + "\n" for item in observations),
        encoding="utf-8",
    )
    replay = finalize_evidence([path], composed_at="two")
    assert len(read_evidence(path)) == 2
    assert replay["semantic_digest"] == direct["semantic_digest"]

    tampered = copy.deepcopy(sanitize_observation(observations[1]))
    tampered["excluded"] = True
    tampered["error_class"] = "timeout"
    lines = path.read_text(encoding="utf-8").splitlines()
    lines[1] = json.dumps(tampered, sort_keys=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    changed = finalize_evidence([path], composed_at="two")
    assert changed["semantic_digest"] != direct["semantic_digest"]


def test_missing_expected_cell_fails_closed():
    with pytest.raises(ValueError, match="expected campaign matrix"):
        finalize_observation_groups([[_observation("A")]], composed_at="missing")


def test_duplicate_expected_cell_fails_closed():
    observations = [_observation("A"), _observation("B")]
    for item in observations:
        item["source"]["cohort"]["campaign_identity"]["expected_cells"].append(
            "B|0|q0"
        )
    with pytest.raises(ValueError, match="duplicate cells"):
        finalize_observation_groups([observations], composed_at="duplicate")


def test_excluded_unverified_b_cell_remains_in_authoritative_assertions():
    baseline = _observation("A")
    excluded = _observation("B", excluded=True)
    excluded["mcp_surface_unverified"] = True
    excluded["observed_mcp_tool_surface_hash"] = None
    record = finalize_observation_groups([[baseline, excluded]], composed_at="excluded")

    assertions = record["tool_call_assertions"]["B"]
    assert assertions["cells_total"] == 1
    assert assertions["cells_excluded"] == 1
    assert assertions["cells_mcp_surface_unverified"] == 1
    assert record["claim_verdict"]["accepted"] is False
    assert "verified_tool_surface" in record["claim_verdict"]["reasons"]


def test_surface_evidence_counts_conditionally_emitted_and_omitted_pre_755(tmp_path):
    """tempdoc 755 Track 1 items 3/4: `cells_by_surface_evidence` appears (with per-kind
    counts) once ANY observation carries surface_evidence, and is OMITTED entirely for
    evidence that never captured it -- keeping pre-755 record digests byte-identical."""
    # WITHOUT surface_evidence (pre-755-shaped): the key is omitted, and the digest is
    # byte-identical to composing the same observations after we DELETE the (None) key.
    baseline_obs = [_observation("A", qid="q0"), _observation("B", qid="q0")]
    without = finalize_observation_groups(
        [copy.deepcopy(baseline_obs)], composed_at="x")
    assert "cells_by_surface_evidence" not in without["tool_call_assertions"]["B"]
    assert "cells_by_surface_evidence" not in without["tool_call_assertions"]["A"]

    stripped = copy.deepcopy(baseline_obs)
    for obs in stripped:
        obs.pop("surface_evidence", None)
    without_stripped = finalize_observation_groups([stripped], composed_at="x")
    assert without_stripped["semantic_digest"] == without["semantic_digest"]

    # WITH surface_evidence on the B cell: counts are emitted for B, still omitted for A.
    with_obs = copy.deepcopy(baseline_obs)
    with_obs[1]["surface_evidence"] = "status"
    with_ev = finalize_observation_groups([with_obs], composed_at="x")
    assert with_ev["tool_call_assertions"]["B"]["cells_by_surface_evidence"] == {"status": 1}
    assert "cells_by_surface_evidence" not in with_ev["tool_call_assertions"]["A"]
    # The new field changes the record's digest (new discriminating measurement content).
    assert with_ev["semantic_digest"] != without["semantic_digest"]


def test_surface_evidence_unverified_bucket_counts_uncaptured_with_tool_cells():
    """A with-tool attempted non-excluded cell with no surface_evidence buckets as
    'unverified' -- but only once SOME cell in the arm captured a kind (else the whole dict
    is omitted). Here one B cell is status-verified and another is unverified."""
    verified = _observation("B", qid="q0")
    verified["surface_evidence"] = "status"
    unverified = _observation("B", qid="q1")
    unverified["surface_evidence"] = None
    # expand the expected matrix so require_complete passes for two B cells + one A cell
    obs = [_observation("A", qid="q0"), verified, unverified]
    for item in obs:
        item["source"]["cohort"]["campaign_identity"]["expected_cells"] = [
            "A|0|q0", "B|0|q0", "B|0|q1"]
    record = finalize_observation_groups([obs], composed_at="x")
    counts = record["tool_call_assertions"]["B"]["cells_by_surface_evidence"]
    assert counts == {"status": 1, "unverified": 1}


def test_real_2026_07_12_rejected_fixture_reproduces_false_green_loss():
    path = (
        Path(__file__).parent
        / "fixtures"
        / "agent-utility-rejected-2026-07-12"
        / "observations.v1.jsonl"
    )
    observations = read_evidence(path)
    assert len(observations) == 48

    record = finalize_evidence([path], composed_at="fixture")
    loss = record["comparability"]["per_arm_loss"]
    assert loss["A"] == {
        "n_attempted": 24, "n_planned": 24, "n_pending": 0,
        "n_completed": 16, "n_excluded": 8, "exclusion_rate": 0.3333,
    }
    assert loss["B"] == {
        "n_attempted": 24, "n_planned": 24, "n_pending": 0,
        "n_completed": 14, "n_excluded": 10, "exclusion_rate": 0.4167,
    }
    assert record["comparability"]["metrics"]["paired_n_retention"] == 0.5
    assert record["comparability"]["comparable"] is False
    assert record["claim_verdict"]["accepted"] is False
    assert "source_identity_complete" in record["claim_verdict"]["reasons"]


def test_historical_fixture_semantic_digest_repinned_after_624_itt_change():
    """tempdoc 736 U1: `tool_result_digests` (D9) is evidence/sanitizer-tier only
    and does not perturb this committed 48-row fixture's composed digest.

    tempdoc 624 (2026-07-17 resource-exhaustion-as-failure) DELIBERATELY moved
    this value (hence the re-pin): the ITT estimand now treats `other`-kind errors (this fixture's
    18 bucketed `timeout` cells classify as `other` under the raw-marker
    classifier -- fail-closed, since a bucketed class has lost the specific
    exhaustion signal) as MISSING DATA and drops them from the paired accuracy,
    where they were previously scored-incorrect-and-included. The comparability
    layer is unchanged (those cells stay residual exclusions -- see
    test_real_2026_07_12_rejected_fixture_reproduces_false_green_loss). New value
    re-captured with `finalize_evidence([path])["semantic_digest"]` after the
    2026-07-17 estimand change; the `outcome_rule` stamp is digest-excluded.

    Re-pinned AGAIN 2026-07-17 (claim-policy ACTIVATION, tempdoc 624
    §Confirmatory pre-registration): `claim_verdict` is digest-covered and
    evaluates against the checked-in policy, so activating
    `agent-utility-public-v1` (draft -> active, four required strata) moved this
    fixture's verdict payload (still rejected — its strata cannot match the
    confirmatory matrix) and therefore its digest."""
    path = (
        Path(__file__).parent
        / "fixtures"
        / "agent-utility-rejected-2026-07-12"
        / "observations.v1.jsonl"
    )
    record = finalize_evidence([path], composed_at="fixture")
    assert record["semantic_digest"] == (
        "2be7446c70b1177353a0c2f1bf127d4d2519299fc4c1d5166ec1ed26bf4071f5"
    )


def test_recompose_cli_accepts_sanitized_evidence_without_logs(tmp_path):
    fixture = (
        Path(__file__).parent
        / "fixtures"
        / "agent-utility-rejected-2026-07-12"
        / "observations.v1.jsonl"
    )
    result = CliRunner().invoke(
        cmd_utility_recompose,
        ["--evidence", str(fixture), "--output-dir", str(tmp_path)],
        obj={"json": False},
    )
    assert result.exit_code == 0, result.output
    assert (tmp_path / "utility-comparison.v1.json").is_file()


def test_observation_keys_match_schema_properties_exactly():
    schema_path = Path(__file__).parents[1] / "agent-utility-observation.v1.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    assert _OBSERVATION_KEYS == set(schema["properties"])
    assert _SOURCE_KEYS == set(schema["properties"]["source"]["properties"])


def _load_rejected_2026_07_12_fixture_rows() -> list[dict]:
    path = (
        Path(__file__).parent
        / "fixtures"
        / "agent-utility-rejected-2026-07-12"
        / "observations.v1.jsonl"
    )
    return [
        json.loads(line)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


@pytest.mark.parametrize(
    "row",
    _load_rejected_2026_07_12_fixture_rows(),
    ids=[f"row{i}" for i in range(len(_load_rejected_2026_07_12_fixture_rows()))],
)
def test_committed_fixture_rows_validate_against_schema(row):
    # Regression guard: every row of the immutable committed evidence fixture
    # must validate against agent-utility-observation.v1.schema.json. This
    # caught a real drift once (schema required source.source_git_state,
    # source.mcp_tool_surface, source.corpus_certification, source.query_identity,
    # and source.campaign_identity, but the fixture — captured before those
    # fields existed — omits all five; see the schema's $comment on "source").
    jsonschema = pytest.importorskip("jsonschema")
    schema_path = Path(__file__).parents[1] / "agent-utility-observation.v1.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    jsonschema.validate(row, schema)


def test_source_complete_evidence_digest_is_checkout_independent(tmp_path, monkeypatch):
    observations = [_observation("A"), _observation("B")]
    path = tmp_path / "source-complete.jsonl"
    path.write_text(
        "".join(json.dumps(sanitize_observation(item), sort_keys=True) + "\n" for item in observations),
        encoding="utf-8",
    )
    import jseval.manifest as manifest

    monkeypatch.setattr(manifest, "_git_sha_full", lambda: "1" * 40)
    from_checkout_one = finalize_evidence([path], composed_at="one")
    monkeypatch.setattr(manifest, "_git_sha_full", lambda: "2" * 40)
    from_checkout_two = finalize_evidence([path], composed_at="two")

    assert from_checkout_one["cohort"]["git_sha"] == "a" * 40
    assert from_checkout_two["cohort"]["git_sha"] == "a" * 40
    assert from_checkout_one["semantic_digest"] == from_checkout_two["semantic_digest"]


def test_judge_calibration_is_hashed_into_captured_cohort_identity():
    overlay = {
        "judge_identity": {"kind": "hybrid-em-llm", "model": "judge-v1"},
        "human_calibration": {"n": 40, "cohen_kappa": 0.8},
    }
    summaries = successful_summaries([_observation("A")], judge_overlay=overlay)
    judge = summaries[0]["manifest"]["judge"]
    assert judge["kind"] == "hybrid-em-llm"
    assert len(judge["calibration_hash"]) == 64
