"""CLI-level regression tests for `--supersedes`/`--revision-reason` across the
three composing utility commands (tempdoc 624 hardening pass, independent-reviewer
gap: `build_revision()` was correct code with ZERO production callers).

Structural template: `test_utility_exclude_leaked_cli.py` (the sibling flag-pair
pattern already established for `--exclude-leaked` across these same three
commands). Each command gets one test class with four cases: both flags given
attaches a `revision` block whose shape is genuinely produced by
`utility_comparison.build_revision()` (compared against a direct call, not
hand-shaped JSON); only `--supersedes` given is rejected; only `--revision-reason`
given is rejected; neither flag given is a true no-op (no `revision` key at all).
"""

from __future__ import annotations

import json

import pytest
from click.testing import CliRunner

from jseval import utility_comparison as uc
from jseval.commands.utility import (
    cmd_utility_compose,
    cmd_utility_compose_cross_corpus,
    cmd_utility_judge,
)

# --- utility-compose (plain JSON run-result files, no inspect_ai needed) -----


def _compose_run_files(tmp_path):
    results_a = [
        {"query": "q0", "agent_answer": "the answer is ANS0", "correct": True,
         "cost_usd": 0.01, "cache_creation_tokens": 100, "num_turns": 1},
    ]
    results_c = [
        {"query": "q0", "agent_answer": "the answer is ANS0", "correct": True,
         "cost_usd": 0.02, "cache_creation_tokens": 120, "num_turns": 2},
    ]
    path_a = tmp_path / "run-A.json"
    path_c = tmp_path / "run-C.json"
    path_a.write_text(json.dumps({"results": results_a}), encoding="utf-8")
    path_c.write_text(json.dumps({"results": results_c}), encoding="utf-8")
    return str(path_a), str(path_c)


class TestUtilityComposeRevisionCli:
    def test_both_flags_attaches_a_genuine_build_revision_block(self, tmp_path):
        path_a, path_c = _compose_run_files(tmp_path)
        out_dir = tmp_path / "out"
        runner = CliRunner()
        result = runner.invoke(cmd_utility_compose, [
            "--run", f"A={path_a}", "--run", f"C={path_c}",
            "--dataset", "mixed/multihop-rag",
            "--supersedes", "../out-prior/utility-comparison.v1.json",
            "--revision-reason", "leak_correction",
            "--output-dir", str(out_dir),
        ], obj={"json": False})
        assert result.exit_code == 0, result.output

        record = json.loads((out_dir / "utility-comparison.v1.json").read_text(encoding="utf-8"))
        expected = uc.build_revision(
            supersedes="../out-prior/utility-comparison.v1.json",
            reason="leak_correction", changed_fields=[])
        assert record["revision"] == expected

    def test_only_supersedes_is_rejected(self, tmp_path):
        path_a, path_c = _compose_run_files(tmp_path)
        runner = CliRunner()
        result = runner.invoke(cmd_utility_compose, [
            "--run", f"A={path_a}", "--run", f"C={path_c}",
            "--dataset", "mixed/multihop-rag",
            "--supersedes", "../out-prior/utility-comparison.v1.json",
        ], obj={"json": False})
        assert result.exit_code != 0
        assert "must be given together" in result.output

    def test_only_revision_reason_is_rejected(self, tmp_path):
        path_a, path_c = _compose_run_files(tmp_path)
        runner = CliRunner()
        result = runner.invoke(cmd_utility_compose, [
            "--run", f"A={path_a}", "--run", f"C={path_c}",
            "--dataset", "mixed/multihop-rag",
            "--revision-reason", "leak_correction",
        ], obj={"json": False})
        assert result.exit_code != 0
        assert "must be given together" in result.output

    def test_neither_flag_leaves_record_unchanged(self, tmp_path):
        path_a, path_c = _compose_run_files(tmp_path)
        out_dir = tmp_path / "out"
        runner = CliRunner()
        result = runner.invoke(cmd_utility_compose, [
            "--run", f"A={path_a}", "--run", f"C={path_c}",
            "--dataset", "mixed/multihop-rag",
            "--output-dir", str(out_dir),
        ], obj={"json": False})
        assert result.exit_code == 0, result.output

        record = json.loads((out_dir / "utility-comparison.v1.json").read_text(encoding="utf-8"))
        assert "revision" not in record


# --- utility-judge (real Inspect EvalLogs, reuses the existing leak fixture) -


class TestUtilityJudgeRevisionCli:
    def test_both_flags_attaches_a_genuine_build_revision_block(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        from jseval import utility_judge as uj
        from tests.test_agent_utility_run import _leak_scan_logs

        log_dir = _leak_scan_logs(tmp_path)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")

        out_dir = tmp_path / "out"
        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge, [
            log_dir, "--judge-url", "http://x",
            "--supersedes", "../out-prior/utility-comparison.v1.json",
            "--revision-reason", "judge_rescore",
            "--output-dir", str(out_dir),
        ])
        assert result.exit_code == 0, result.output

        record = json.loads((out_dir / "utility-comparison.v1.json").read_text(encoding="utf-8"))
        expected = uc.build_revision(
            supersedes="../out-prior/utility-comparison.v1.json",
            reason="judge_rescore", changed_fields=[])
        assert record["revision"] == expected

    def test_only_supersedes_is_rejected(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        from jseval import utility_judge as uj
        from tests.test_agent_utility_run import _leak_scan_logs

        log_dir = _leak_scan_logs(tmp_path)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge, [
            log_dir, "--judge-url", "http://x",
            "--supersedes", "../out-prior/utility-comparison.v1.json",
        ])
        assert result.exit_code != 0
        assert "must be given together" in result.output

    def test_only_revision_reason_is_rejected(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        from jseval import utility_judge as uj
        from tests.test_agent_utility_run import _leak_scan_logs

        log_dir = _leak_scan_logs(tmp_path)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")

        runner = CliRunner()
        result = runner.invoke(cmd_utility_judge, [
            log_dir, "--judge-url", "http://x",
            "--revision-reason", "judge_rescore",
        ])
        assert result.exit_code != 0
        assert "must be given together" in result.output

    def test_neither_flag_leaves_record_unchanged(self, tmp_path, monkeypatch):
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

        record = json.loads((out_dir / "utility-comparison.v1.json").read_text(encoding="utf-8"))
        assert "revision" not in record


# --- utility-compose-cross-corpus (two distinct corpora) ---------------------


def _corpus_logs(tmp_path, dataset, *, conditions=("A", "C")):
    from inspect_ai import Task, eval_set, task
    from inspect_ai.dataset import Sample
    from inspect_ai.solver import solver

    from jseval.agent_utility_inspect import substring_scorer

    completions = {"q0": "the answer is ANS0", "q1": "the answer is ANS1"}
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


class TestUtilityComposeCrossCorpusRevisionCli:
    def test_both_flags_attaches_a_genuine_build_revision_block(self, tmp_path):
        pytest.importorskip("inspect_ai")
        log_dir_1 = _corpus_logs(tmp_path / "en", "golden/battlefield-en-v1")
        log_dir_2 = _corpus_logs(tmp_path / "de", "golden/battlefield-de-v1")

        out_dir = tmp_path / "out"
        runner = CliRunner()
        result = runner.invoke(cmd_utility_compose_cross_corpus, [
            "--log-dir", log_dir_1, "--log-dir", log_dir_2,
            "--supersedes", "../out-prior/utility-comparison-cross-corpus.v1.json",
            "--revision-reason", "reseed",
            "--output-dir", str(out_dir),
        ], obj={"json": False})
        assert result.exit_code == 0, result.output

        record = json.loads(
            (out_dir / "utility-comparison-cross-corpus.v1.json").read_text(encoding="utf-8"))
        expected = uc.build_revision(
            supersedes="../out-prior/utility-comparison-cross-corpus.v1.json",
            reason="reseed", changed_fields=[])
        assert record["revision"] == expected

    def test_only_supersedes_is_rejected(self, tmp_path):
        pytest.importorskip("inspect_ai")
        log_dir_1 = _corpus_logs(tmp_path / "en", "golden/battlefield-en-v1")
        log_dir_2 = _corpus_logs(tmp_path / "de", "golden/battlefield-de-v1")

        runner = CliRunner()
        result = runner.invoke(cmd_utility_compose_cross_corpus, [
            "--log-dir", log_dir_1, "--log-dir", log_dir_2,
            "--supersedes", "../out-prior/utility-comparison-cross-corpus.v1.json",
        ], obj={"json": False})
        assert result.exit_code != 0
        assert "must be given together" in result.output

    def test_only_revision_reason_is_rejected(self, tmp_path):
        pytest.importorskip("inspect_ai")
        log_dir_1 = _corpus_logs(tmp_path / "en", "golden/battlefield-en-v1")
        log_dir_2 = _corpus_logs(tmp_path / "de", "golden/battlefield-de-v1")

        runner = CliRunner()
        result = runner.invoke(cmd_utility_compose_cross_corpus, [
            "--log-dir", log_dir_1, "--log-dir", log_dir_2,
            "--revision-reason", "reseed",
        ], obj={"json": False})
        assert result.exit_code != 0
        assert "must be given together" in result.output

    def test_neither_flag_leaves_record_unchanged(self, tmp_path):
        pytest.importorskip("inspect_ai")
        log_dir_1 = _corpus_logs(tmp_path / "en", "golden/battlefield-en-v1")
        log_dir_2 = _corpus_logs(tmp_path / "de", "golden/battlefield-de-v1")

        out_dir = tmp_path / "out"
        runner = CliRunner()
        result = runner.invoke(cmd_utility_compose_cross_corpus, [
            "--log-dir", log_dir_1, "--log-dir", log_dir_2,
            "--output-dir", str(out_dir),
        ], obj={"json": False})
        assert result.exit_code == 0, result.output

        record = json.loads(
            (out_dir / "utility-comparison-cross-corpus.v1.json").read_text(encoding="utf-8"))
        assert "revision" not in record
