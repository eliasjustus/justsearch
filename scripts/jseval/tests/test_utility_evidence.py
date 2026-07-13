from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest
from click.testing import CliRunner

from jseval.agent_utility_observations import successful_summaries
from jseval.commands.utility import cmd_utility_recompose
from jseval.agent_manifest import mcp_tool_surface_hash
from jseval.utility_evidence import read_evidence, sanitize_observation
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
