"""tempdoc 719: the sanitized evidence export must reproduce the composed record.

Root cause this file pins: `export_log_dir` used to read its log directory with
NO judge overlay while `finalize_logs` resolved `judge-overlay.json` from that
same directory. Every cell the judge rescored was therefore exported with its
pre-judge `substring_scorer` verdict, so recomposing the export produced
different measured accuracy than recomposing the logs -- and the publication
builder's evidence-recompose check ("record does not semantically match the
supplied evidence") fired on the first real publication.

The fixture is a real Inspect `EvalLog` written by `eval_set` with a mock model
(mirrors `tests/test_agent_utility_run.py`), so it exercises the real producer's
data flow rather than a hand-built observation dict.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from click.testing import CliRunner

from jseval.agent_manifest import mcp_tool_surface_hash
from jseval.agent_utility_observations import read_inspect_observations
from jseval.commands.utility import cmd_utility_evidence_export
from jseval.utility_evidence import export_log_dir, read_evidence
from jseval.utility_recompose import finalize_evidence, finalize_logs, semantic_digest

_QIDS = ("q0", "q1", "q2")
_CONDITIONS = ("A", "B")
_SURFACE = [{
    "name": "mcp__justsearch__search", "description": "Search",
    "input_schema": {"type": "object"},
}]
_COHORT = {
    "model": "haiku",
    "source_git_sha": "a" * 40,
    "source_git_dirty": False,
    "cli_version": "v",
    "mcp_tool_surface_hash": mcp_tool_surface_hash(_SURFACE),
    "mcp_tool_surface": _SURFACE,
    "judge_kind": "substring-em",
    "prompt_template_hash": "p",
    "search_config_cohort_key": "sc",
    "corpus_identity": {"signature": "c" * 64},
    "query_identity": {"sha256": "e" * 64, "row_count": len(_QIDS)},
    "campaign_identity": {
        "conditions": list(_CONDITIONS),
        "seeds": 1,
        "expected_cells": [
            f"{condition}|0|{qid}" for condition in _CONDITIONS for qid in _QIDS
        ],
    },
}

# The judge RESCUES `q1` in the baseline arm (the substring scorer missed a
# paraphrase) and OVERTURNS `q2` in the with-tool arm -- both disagreement
# directions, and each arm's accuracy moves, so a fix that equalized the two
# readers by dropping the overlay on BOTH sides is still caught. Cells absent
# from the overlay keep the raw scorer verdict (the real overlay's
# partial-coverage shape).
_OVERLAY = {
    "judge_identity": {"kind": "llm-judge", "model": "fixture-judge"},
    "scores": {
        "A|0|q1": {"em": False, "judge": True, "final": True},
        "B|0|q2": {"em": True, "judge": False, "final": False},
    },
}


def _logs(tmp_path: Path, *, name: str = "logs", overlay: dict | None = _OVERLAY) -> Path:
    from inspect_ai import Task, eval_set, task
    from inspect_ai.dataset import Sample
    from inspect_ai.solver import solver

    from jseval.agent_utility_inspect import substring_scorer

    # q0 scores correct by substring; q1 misses it; q2 hits it. The overlay then
    # disagrees with the raw scorer on q1 (both arms) and on q2 (baseline only).
    completions = {
        "q0": "the answer is ANS0",
        "q1": "a paraphrase that never spells the gold token out",
        "q2": "the answer is ANS2",
    }

    @solver
    def fixed():
        async def solve(state, generate):
            qid = str(state.sample_id).split("|", 1)[-1]
            state.output.completion = completions[qid]
            state.metadata.update({
                "cost_usd": 0.1, "unique_tokens": 500, "num_turns": 2,
                "tool_calls": [{"tool": "mcp__justsearch__search", "input": {"q": "x"}}],
            })
            return state
        return solve

    @task
    def ct(condition="A"):
        samples = [
            Sample(id=f"{condition}|{qid}", input=qid, target=f"ANS{qid[1:]}",
                   metadata={"condition": condition})
            for qid in _QIDS
        ]
        return Task(dataset=samples, solver=fixed(), scorer=substring_scorer(),
                    metadata={"model": "haiku",
                              "corpus": {"dataset": "fixture", "signature": "c" * 64},
                              "cohort": _COHORT})

    log_dir = tmp_path / name
    eval_set([ct(condition=c) for c in _CONDITIONS], log_dir=log_dir.as_posix(),
             epochs=1, model="mockllm/model", log_format="json")
    if overlay is not None:
        (log_dir / "judge-overlay.json").write_text(
            json.dumps(overlay), encoding="utf-8")
    return log_dir


def _correct_by_cell(observations) -> dict[tuple, bool]:
    return {
        (o["condition"], int(o["seed"]), str(o["qid"])): bool(o["correct"])
        for o in observations
    }


def test_fixture_actually_has_judge_rescored_cells(tmp_path):
    """Precision guard: without a divergence to reproduce, the two tests below
    would pass for the wrong reason (any two identical readers agree)."""
    pytest.importorskip("inspect_ai")
    log_dir = _logs(tmp_path)
    raw = _correct_by_cell(read_inspect_observations(log_dir))
    judged = _correct_by_cell(read_inspect_observations(log_dir, judge_overlay=_OVERLAY))
    flipped = {key for key in raw if raw[key] != judged[key]}
    assert flipped == {("A", 0, "q1"), ("B", 0, "q2")}
    assert judged[("A", 0, "q1")] is True    # judge rescued a substring miss
    assert judged[("B", 0, "q2")] is False   # judge overturned a substring hit


def test_export_carries_the_judge_verdict_not_the_raw_scorer(tmp_path):
    """The per-cell divergence: pre-fix the export read the log dir with no
    overlay, so the three rescored cells were exported pre-judge."""
    pytest.importorskip("inspect_ai")
    log_dir = _logs(tmp_path)
    exported = read_evidence(export_log_dir(log_dir, tmp_path / "evidence.jsonl"))
    authoritative = read_inspect_observations(log_dir, judge_overlay=_OVERLAY)
    assert _correct_by_cell(exported) == _correct_by_cell(authoritative)


def test_export_then_finalize_reproduces_the_composed_record_digest(tmp_path):
    """The end-to-end property the publication builder depends on
    (`utility_publication.build_publication`: recomposing the supplied evidence
    must semantically match the stored record)."""
    pytest.importorskip("inspect_ai")
    log_dir = _logs(tmp_path)
    path = export_log_dir(log_dir, tmp_path / "evidence.jsonl")
    from_logs = finalize_logs([log_dir], composed_at="one")
    from_evidence = finalize_evidence([path], composed_at="two")
    assert semantic_digest(from_evidence) == semantic_digest(from_logs)
    # ...and the shared digest is the JUDGED measurement, not the raw-scorer one
    # (a fix that dropped the overlay on BOTH sides would also equalize digests).
    accuracy = from_logs["measured"]["beir/fixture"]["haiku"]["accuracy"]
    assert accuracy["baseline"] == pytest.approx(1.0)       # q0, q2 raw + q1 rescued
    assert accuracy["with_tool"] == pytest.approx(0.3333)   # q0 raw; q1 missed; q2 overturned


def test_log_dir_without_an_overlay_is_unaffected(tmp_path):
    """No overlay on disk => the export is byte-identical to the pre-fix output,
    so every pre-719 exported evidence file still recomposes unchanged."""
    pytest.importorskip("inspect_ai")
    log_dir = _logs(tmp_path, overlay=None)
    path = export_log_dir(log_dir, tmp_path / "evidence.jsonl")
    exported = read_evidence(path)
    assert _correct_by_cell(exported) == _correct_by_cell(read_inspect_observations(log_dir))
    assert semantic_digest(finalize_evidence([path], composed_at="two")) == semantic_digest(
        finalize_logs([log_dir], composed_at="one"))


def test_explicit_overlay_beats_the_directory_default(tmp_path):
    """Same precedence as `utility-recompose --judge-overlay`."""
    pytest.importorskip("inspect_ai")
    log_dir = _logs(tmp_path)
    other = tmp_path / "other-overlay.json"
    other.write_text(json.dumps({"scores": {"A|0|q0": {"final": False}}}), encoding="utf-8")
    exported = read_evidence(
        export_log_dir(log_dir, tmp_path / "evidence.jsonl", judge_overlay=other))
    correct = _correct_by_cell(exported)
    assert correct[("A", 0, "q0")] is False      # the explicit overlay applied
    assert correct[("A", 0, "q1")] is False      # the directory overlay's rescue did NOT


def test_cli_export_resolves_the_directory_overlay(tmp_path):
    pytest.importorskip("inspect_ai")
    log_dir = _logs(tmp_path)
    out = tmp_path / "cli-evidence.jsonl"
    result = CliRunner().invoke(
        cmd_utility_evidence_export, ["--log-dir", str(log_dir), "--out", str(out)])
    assert result.exit_code == 0, result.output
    assert _correct_by_cell(read_evidence(out)) == _correct_by_cell(
        read_inspect_observations(log_dir, judge_overlay=_OVERLAY))
