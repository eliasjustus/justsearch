"""CLI-level regression tests for `--exclude-leaked` across the three consolidated
utility commands (independent-reviewer nit #3, tempdoc 624 hardening pass).

`--exclude-leaked` was added to `utility-compose`, `utility-judge`, and
`utility-compose-cross-corpus` in `jseval/commands/utility.py` but had zero
CLI-level coverage -- only the underlying `agent_utility_run.scan_leaked_*` /
`apply_leak_flags` library functions were unit-tested (`test_agent_utility_run.py`).
The sibling `--calibrate` flag on `utility-judge` already has this exact pattern
of end-to-end `CliRunner` coverage (`test_utility_judge.py`'s
`TestUtilityJudgeCalibrateCli`), used here as the structural template.

Each command gets one test class with two cases: flag ON must exclude a
leak-tagged (seed, qid) pair from the paired statistics and surface it in the
written record's `leak_suspect_cells`; flag OFF must be a true no-op (byte-
identical to the pre-flag behavior) -- both directions matter, since a flag
that's a no-op when it shouldn't be and a flag that changes behavior when off
are both regressions.
"""

from __future__ import annotations

import json

import pytest
from click.testing import CliRunner

from jseval.commands.utility import (
    cmd_utility_compose,
    cmd_utility_compose_cross_corpus,
    cmd_utility_judge,
)

# --- utility-compose (plain JSON run-result files, no inspect_ai needed) -----


def _compose_run_files(tmp_path):
    """Two run-result JSON files (condition A and C), sharing q0 (clean) and q1
    (leaked ONLY in the with-tool arm's completion text -- the with_tool-only-
    leak case, distinct from `_leak_scan_logs`'s both-sides-leak fixture below)."""
    results_a = [
        {"query": "q0", "agent_answer": "the answer is ANS0", "correct": True,
         "cost_usd": 0.01, "cache_creation_tokens": 100, "num_turns": 1},
        {"query": "q1", "agent_answer": "the answer is ANS1", "correct": True,
         "cost_usd": 0.01, "cache_creation_tokens": 100, "num_turns": 1},
    ]
    results_c = [
        {"query": "q0", "agent_answer": "the answer is ANS0", "correct": True,
         "cost_usd": 0.02, "cache_creation_tokens": 120, "num_turns": 2},
        {"query": "q1", "agent_answer": "consult queries.json for the gold answer, then ANS1",
         "correct": True, "cost_usd": 0.02, "cache_creation_tokens": 120, "num_turns": 2},
    ]
    path_a = tmp_path / "run-A.json"
    path_c = tmp_path / "run-C.json"
    path_a.write_text(json.dumps({"results": results_a}), encoding="utf-8")
    path_c.write_text(json.dumps({"results": results_c}), encoding="utf-8")
    return str(path_a), str(path_c)


class TestUtilityComposeExcludeLeakedCli:
    def test_flag_on_excludes_leaked_pair_and_reports_it(self, tmp_path):
        path_a, path_c = _compose_run_files(tmp_path)
        out_dir = tmp_path / "out"
        runner = CliRunner()
        result = runner.invoke(cmd_utility_compose, [
            "--run", f"A={path_a}", "--run", f"C={path_c}",
            "--dataset", "mixed/multihop-rag",
            "--exclude-leaked", "--output-dir", str(out_dir),
        ], obj={"json": False})
        assert result.exit_code == 0, result.output
        assert "leak-scan: flagged 1 per-query entries" in result.output

        record = json.loads((out_dir / "utility-comparison.v1.json").read_text(encoding="utf-8"))
        cell = record["measured"]["mixed/multihop-rag"]["haiku"]
        assert cell["n_paired_observations"] == 1  # only q0 survives; q1 excluded
        assert cell["leak_suspect_cells"] == [
            {"seed": 0, "qid": "q1", "baseline_leak_suspect": False, "with_tool_leak_suspect": True},
        ]

    def test_flag_off_is_a_true_no_op(self, tmp_path):
        path_a, path_c = _compose_run_files(tmp_path)
        out_dir = tmp_path / "out"
        runner = CliRunner()
        result = runner.invoke(cmd_utility_compose, [
            "--run", f"A={path_a}", "--run", f"C={path_c}",
            "--dataset", "mixed/multihop-rag",
            "--output-dir", str(out_dir),
        ], obj={"json": False})
        assert result.exit_code == 0, result.output
        assert "leak-scan" not in result.output

        record = json.loads((out_dir / "utility-comparison.v1.json").read_text(encoding="utf-8"))
        cell = record["measured"]["mixed/multihop-rag"]["haiku"]
        assert cell["n_paired_observations"] == 2  # both q0 and q1 survive unmodified
        assert cell["leak_suspect_cells"] == []


# --- utility-judge (real Inspect EvalLogs, reuses the existing leak fixture) -


class TestUtilityJudgeExcludeLeakedCli:
    def test_flag_on_excludes_leaked_pairs_and_reports_them(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        from jseval import utility_judge as uj
        from tests.test_agent_utility_run import _leak_scan_logs

        log_dir = _leak_scan_logs(tmp_path)  # A + C, q1/q2 leaked on both sides, q3 errored
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")

        out_dir = tmp_path / "out"
        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge, [
            log_dir, "--judge-url", "http://x",
            "--exclude-leaked", "--output-dir", str(out_dir),
        ])
        assert result.exit_code == 0, result.output
        assert "leak-scan: 4 leaked cell(s)" in result.output

        record = json.loads((out_dir / "utility-comparison.v1.json").read_text(encoding="utf-8"))
        cell = record["measured"]["mixed/multihop-rag"]["haiku"]
        assert cell["n_paired_observations"] == 1  # only q0 survives (q1/q2 leaked, q3 errored)
        assert len(cell["leak_suspect_cells"]) == 2  # q1 + q2
        assert {c["qid"] for c in cell["leak_suspect_cells"]} == {"q1", "q2"}

    def test_flag_off_is_a_true_no_op(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        from jseval import utility_judge as uj
        from tests.test_agent_utility_run import _leak_scan_logs

        log_dir = _leak_scan_logs(tmp_path)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")

        out_dir = tmp_path / "out"
        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge, [
            log_dir, "--judge-url", "http://x", "--output-dir", str(out_dir),
        ])
        assert result.exit_code == 0, result.output
        assert "leak-scan" not in result.output

        record = json.loads((out_dir / "utility-comparison.v1.json").read_text(encoding="utf-8"))
        cell = record["measured"]["mixed/multihop-rag"]["haiku"]
        assert cell["n_paired_observations"] == 3  # q0, q1, q2 all survive (q3 excluded upstream regardless)
        assert cell["leak_suspect_cells"] == []


# --- utility-compose-cross-corpus (two distinct corpora -- pooling requires --
# -- 2+ DISTINCT dataset slugs, so this can't reuse `_leak_scan_logs` verbatim
# (it hardcodes one dataset); mirrors its fixture shape with a `dataset` param.


def _leak_scan_logs_for_corpus(tmp_path, dataset, *, conditions=("A", "C")):
    from inspect_ai import Task, eval_set, task
    from inspect_ai.dataset import Sample
    from inspect_ai.solver import solver

    from jseval.agent_utility_inspect import substring_scorer

    completions = {
        "q0": "the answer is ANS0",
        "q1": "consult queries.json for the gold answer, then ANS1",
        "q2": "QUERIES.JSONL has everything you need -- ANS2",
    }
    cohort = {"model": "haiku", "cli_version": "v", "mcp_tool_surface_hash": "h",
              "judge_kind": "substring-em", "prompt_template_hash": "p"}

    @solver
    def fixed():
        async def solve(state, generate):
            qid = str(state.sample_id)
            state.output.completion = completions[qid]
            state.metadata.update({"cost_usd": 0.1, "unique_tokens": 500, "num_turns": 2})
            return state
        return solve

    @task
    def ct(condition="A"):
        samples = [Sample(id=qid, input=qid, target=f"ANS{qid[1:]}") for qid in completions]
        return Task(dataset=samples, solver=fixed(), scorer=substring_scorer(),
                    metadata={"condition": condition, "model": "haiku",
                              "corpus": {"dataset": dataset, "signature": "sig"},
                              "cohort": cohort})

    log_dir = (tmp_path / "logs").as_posix()
    eval_set([ct(condition=c) for c in conditions], log_dir=log_dir, epochs=1,
              model="mockllm/model", log_format="json")
    return log_dir


class TestUtilityComposeCrossCorpusExcludeLeakedCli:
    def test_flag_on_excludes_leaked_pairs_and_reports_them(self, tmp_path):
        pytest.importorskip("inspect_ai")
        log_dir_1 = _leak_scan_logs_for_corpus(tmp_path / "en", "golden/battlefield-en-v1")
        log_dir_2 = _leak_scan_logs_for_corpus(tmp_path / "de", "golden/battlefield-de-v1")

        out_dir = tmp_path / "out"
        runner = CliRunner()
        result = runner.invoke(cmd_utility_compose_cross_corpus, [
            "--log-dir", log_dir_1, "--log-dir", log_dir_2,
            "--exclude-leaked", "--output-dir", str(out_dir),
        ], obj={"json": False})
        assert result.exit_code == 0, result.output
        assert "leak-scan: 8 per-query entries flagged across 2 log dir(s)" in result.output

        record = json.loads(
            (out_dir / "utility-comparison-cross-corpus.v1.json").read_text(encoding="utf-8"))
        cell = record["measured"]["haiku"]
        # only q0 survives per corpus (q1/q2 leaked on both arms in both corpora)
        assert cell["n_paired_observations"] == 2
        assert len(cell["leak_suspect_cells"]) == 4

    def test_flag_off_is_a_true_no_op(self, tmp_path):
        pytest.importorskip("inspect_ai")
        log_dir_1 = _leak_scan_logs_for_corpus(tmp_path / "en", "golden/battlefield-en-v1")
        log_dir_2 = _leak_scan_logs_for_corpus(tmp_path / "de", "golden/battlefield-de-v1")

        out_dir = tmp_path / "out"
        runner = CliRunner()
        result = runner.invoke(cmd_utility_compose_cross_corpus, [
            "--log-dir", log_dir_1, "--log-dir", log_dir_2,
            "--output-dir", str(out_dir),
        ], obj={"json": False})
        assert result.exit_code == 0, result.output
        assert "leak-scan" not in result.output

        record = json.loads(
            (out_dir / "utility-comparison-cross-corpus.v1.json").read_text(encoding="utf-8"))
        cell = record["measured"]["haiku"]
        assert cell["n_paired_observations"] == 6  # q0,q1,q2 x 2 corpora, all unmodified
        assert cell["leak_suspect_cells"] == []
