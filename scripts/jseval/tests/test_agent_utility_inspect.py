"""Tests for jseval.agent_utility_inspect's `_build_argv` — the actual
subprocess-argv wiring used by the Inspect-AI-based executor (the executor
used for real eval runs, per tempdoc 624).

`build_disallowed_tools` (in agent_retrieval_eval) is unit-tested directly
and correctly, but nothing previously exercised the code path where
`_build_argv` joins its output into the real `claude -p ... --disallowedTools
...` argv that `claude_agent_solver` hands to `subprocess.run`. These tests
fail if that wiring breaks (wrong join, wrong variable, wrong flag name)
even if `build_disallowed_tools` itself is untouched and still passes its
own tests.

`inspect_ai` is an opt-in extra (`pip install jseval[agent]`); skipped if
not installed.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

pytest.importorskip("inspect_ai")

import inspect_ai  # noqa: E402

from jseval.agent_retrieval_eval import build_disallowed_tools  # noqa: E402
from jseval.agent_utility_inspect import _build_argv  # noqa: E402
from jseval import agent_utility_inspect as aui  # noqa: E402


def _disallowed_tools_arg(cmd: list[str]) -> str:
    idx = cmd.index("--disallowedTools")
    return cmd[idx + 1]


def test_build_argv_condition_a_disallowed_tools_matches_helper():
    cmd = _build_argv(
        "claude", "prompt", "haiku", "/corpus", "A",
        mcp_config=None, empty_mcp="/tmp/empty_mcp.json", max_budget=0.50,
    )
    assert _disallowed_tools_arg(cmd) == ",".join(build_disallowed_tools("A"))


def test_build_argv_condition_b_disallowed_tools_matches_helper():
    cmd = _build_argv(
        "claude", "prompt", "haiku", "/corpus", "B",
        mcp_config="/mcp.json", empty_mcp="/tmp/empty_mcp.json", max_budget=0.50,
    )
    assert _disallowed_tools_arg(cmd) == ",".join(build_disallowed_tools("B"))


def test_build_argv_condition_c_disallowed_tools_matches_helper():
    cmd = _build_argv(
        "claude", "prompt", "haiku", "/corpus", "C",
        mcp_config="/mcp.json", empty_mcp="/tmp/empty_mcp.json", max_budget=0.50,
    )
    assert _disallowed_tools_arg(cmd) == ",".join(build_disallowed_tools("C"))


def test_build_argv_condition_a_uses_empty_mcp_strict_config():
    """Condition A (file tools only, baseline) must wire in the empty MCP
    config regardless of what mcp_config was passed — no MCP tools at all."""
    cmd = _build_argv(
        "claude", "prompt", "haiku", "/corpus", "A",
        mcp_config="/should-be-ignored-mcp.json", empty_mcp="/tmp/empty_mcp.json",
        max_budget=0.50,
    )
    assert "--strict-mcp-config" in cmd
    mcp_idx = cmd.index("--mcp-config")
    assert cmd[mcp_idx + 1] == "/tmp/empty_mcp.json"


def test_build_argv_condition_b_uses_real_mcp_config():
    cmd = _build_argv(
        "claude", "prompt", "haiku", "/corpus", "B",
        mcp_config="/mcp.json", empty_mcp="/tmp/empty_mcp.json", max_budget=0.50,
    )
    assert "--strict-mcp-config" in cmd
    mcp_idx = cmd.index("--mcp-config")
    assert cmd[mcp_idx + 1] == "/mcp.json"


def test_build_argv_condition_c_uses_real_mcp_config():
    cmd = _build_argv(
        "claude", "prompt", "haiku", "/corpus", "C",
        mcp_config="/mcp.json", empty_mcp="/tmp/empty_mcp.json", max_budget=0.50,
    )
    assert "--strict-mcp-config" in cmd
    mcp_idx = cmd.index("--mcp-config")
    assert cmd[mcp_idx + 1] == "/mcp.json"


def test_build_argv_condition_b_without_mcp_config_omits_mcp_flags():
    cmd = _build_argv(
        "claude", "prompt", "haiku", "/corpus", "B",
        mcp_config=None, empty_mcp="/tmp/empty_mcp.json", max_budget=0.50,
    )
    assert "--strict-mcp-config" not in cmd
    assert "--mcp-config" not in cmd


def test_build_argv_carries_model_prompt_and_budget():
    cmd = _build_argv(
        "claude", "what is X?", "opus", "/corpus", "B",
        mcp_config="/mcp.json", empty_mcp="/tmp/empty_mcp.json", max_budget=1.25,
    )
    assert cmd[0] == "claude"
    assert "-p" in cmd
    assert cmd[cmd.index("-p") + 1] == "what is X?"
    assert cmd[cmd.index("--model") + 1] == "opus"
    assert cmd[cmd.index("--max-budget-usd") + 1] == "1.25"


# --- run_utility_eval: the corpus-dir isolation fix must apply to the Inspect
# executor too, not just the bespoke `agent_retrieval_eval.run_agent_eval` path
# (one shared `stage_corpus_dir` helper, not two independent forks of the same
# `--add-dir` leak). ---

def test_run_utility_eval_builds_tasks_with_staged_not_original_corpus_dir(tmp_path, monkeypatch):
    """Mocks `agent_utility_task` (the Inspect `@task` factory) and `eval_set`
    (the actual multi-hour agent runner) so this stays a fast unit test, while
    still exercising run_utility_eval's real staging + cleanup wiring."""
    dataset_dir = tmp_path / "dataset"
    corpus_dir = dataset_dir / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")
    (dataset_dir / "queries.json").write_text(
        json.dumps([{"query": "q", "answer": "the secret answer"}]), encoding="utf-8")

    queries_for_eval = tmp_path / "eval_queries.json"
    queries_for_eval.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t"}]), encoding="utf-8")

    captured = {}

    def fake_agent_utility_task(*, corpus_dir, **kwargs):
        captured["used_corpus_dir"] = corpus_dir
        # capture the staged dir's parent listing NOW -- before run_utility_eval's
        # cleanup (which fires once the mocked eval_set below returns) removes it.
        captured["staged_parent_listing"] = sorted(os.listdir(Path(corpus_dir).parent))
        return object()  # eval_set is mocked too, so any placeholder Task works

    monkeypatch.setattr(aui, "agent_utility_task", fake_agent_utility_task)
    monkeypatch.setattr(inspect_ai, "eval_set", lambda *a, **k: None)

    original_corpus_dir = str(corpus_dir)
    aui.run_utility_eval(
        queries_path=str(queries_for_eval), corpus_dir=original_corpus_dir, mcp_config=None,
        conditions=("A",), seeds=1, concurrency=1, log_dir=str(tmp_path / "logs"),
    )

    assert "used_corpus_dir" in captured, "agent_utility_task should have been invoked"
    used_corpus_dir = captured["used_corpus_dir"]
    assert used_corpus_dir != original_corpus_dir
    assert Path(used_corpus_dir).name == "corpus-dir"
    assert captured["staged_parent_listing"] == ["corpus-dir"]  # no queries.json sibling

    # the staging directory is a temp artifact -- must be cleaned up after the run
    assert not Path(used_corpus_dir).parent.exists()


def test_run_utility_eval_cleans_up_staged_dir_when_task_construction_raises(tmp_path, monkeypatch):
    """Regression test (independent-reviewer nit #1): `tasks = [...]` construction
    calls `agent_utility_task`, which does `json.load(open(queries_path))` and can
    raise on a bad/missing/corrupted queries file. Before the fix, only the
    subsequent `eval_set(...)` call was wrapped in the cleanup try/finally, so a
    task-construction failure leaked the staged temp directory. This forces the
    failure IN task construction (not in eval_set) and asserts the staged dir is
    still cleaned up."""
    dataset_dir = tmp_path / "dataset"
    corpus_dir = dataset_dir / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")

    queries_for_eval = tmp_path / "eval_queries.json"
    queries_for_eval.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t"}]), encoding="utf-8")

    captured = {}
    real_stage = aui.stage_corpus_dir

    def spying_stage_corpus_dir(cdir):
        staged = real_stage(cdir)
        captured["staged_dir"] = staged
        return staged

    def raising_agent_utility_task(*, corpus_dir, **kwargs):
        raise ValueError("simulated bad queries.json during task construction")

    monkeypatch.setattr(aui, "stage_corpus_dir", spying_stage_corpus_dir)
    monkeypatch.setattr(aui, "agent_utility_task", raising_agent_utility_task)
    monkeypatch.setattr(inspect_ai, "eval_set", lambda *a, **k: None)

    with pytest.raises(ValueError, match="simulated bad queries.json"):
        aui.run_utility_eval(
            queries_path=str(queries_for_eval), corpus_dir=str(corpus_dir), mcp_config=None,
            conditions=("A",), seeds=1, concurrency=1, log_dir=str(tmp_path / "logs"),
        )

    assert "staged_dir" in captured, "stage_corpus_dir should have been invoked"
    # the staging directory is a temp artifact -- must be cleaned up even though
    # task construction (not eval_set) is what raised.
    assert not Path(captured["staged_dir"]).parent.exists()


# --- Watched-roots safety gate: run_utility_eval is the actual eval-executing path,
# so (unlike the optional `utility-calibrate` CLI) a stray/broader watched root must
# abort the run before any subprocess or eval_set work happens (tempdoc 624 As-built
# #7 follow-up). ---

def _mock_roots_client(MockClient, roots_paths):
    from unittest.mock import MagicMock

    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)
    resp = MagicMock()
    resp.json.return_value = {
        "roots": [{"collection": "default", "path": p, "fileCount": 3} for p in roots_paths]
    }
    resp.raise_for_status = MagicMock()
    mock_client.get.return_value = resp
    return mock_client


def test_run_utility_eval_raises_on_stray_watched_root(tmp_path, monkeypatch):
    """mcp_config given (a real search backend is in play) + a stray/broader watched
    root reported by the live backend -> run_utility_eval must raise StrayWatchedRootError
    and must NOT reach agent_utility_task / eval_set (no staging, no subprocess work)."""
    from unittest.mock import patch

    from jseval.utility_calibrate import StrayWatchedRootError

    dataset_dir = tmp_path / "dataset"
    corpus_dir = dataset_dir / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")

    queries_for_eval = tmp_path / "eval_queries.json"
    queries_for_eval.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t"}]), encoding="utf-8")

    mcp_config = tmp_path / "mcp.json"
    mcp_config.write_text(
        '{"mcpServers":{"justsearch":{"url":"http://127.0.0.1:56300/mcp"}}}', encoding="utf-8")

    called = {"agent_utility_task": False, "eval_set": False}
    monkeypatch.setattr(aui, "agent_utility_task",
                         lambda **kw: called.__setitem__("agent_utility_task", True))
    monkeypatch.setattr(inspect_ai, "eval_set",
                         lambda *a, **k: called.__setitem__("eval_set", True))

    with patch("jseval.utility_calibrate.httpx.Client") as MockClient:
        # a stray root broader than corpus_dir -- the corpus's own PARENT.
        _mock_roots_client(MockClient, [str(dataset_dir)])

        with pytest.raises(StrayWatchedRootError):
            aui.run_utility_eval(
                queries_path=str(queries_for_eval), corpus_dir=str(corpus_dir),
                mcp_config=str(mcp_config), conditions=("A", "C"), seeds=1, concurrency=1,
                log_dir=str(tmp_path / "logs"),
            )

    assert called["agent_utility_task"] is False
    assert called["eval_set"] is False


def test_run_utility_eval_proceeds_when_roots_correctly_scoped(tmp_path, monkeypatch):
    """The mirror-positive: when the live backend's watched roots are exactly
    corpus_dir, the safety gate must NOT block the run."""
    from unittest.mock import patch

    dataset_dir = tmp_path / "dataset"
    corpus_dir = dataset_dir / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")

    queries_for_eval = tmp_path / "eval_queries.json"
    queries_for_eval.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t"}]), encoding="utf-8")

    mcp_config = tmp_path / "mcp.json"
    mcp_config.write_text(
        '{"mcpServers":{"justsearch":{"url":"http://127.0.0.1:56300/mcp"}}}', encoding="utf-8")

    called = {"eval_set": False}
    monkeypatch.setattr(aui, "agent_utility_task", lambda **kw: object())
    monkeypatch.setattr(inspect_ai, "eval_set",
                         lambda *a, **k: called.__setitem__("eval_set", True))

    with patch("jseval.utility_calibrate.httpx.Client") as MockClient:
        _mock_roots_client(MockClient, [str(corpus_dir)])

        aui.run_utility_eval(
            queries_path=str(queries_for_eval), corpus_dir=str(corpus_dir),
            mcp_config=str(mcp_config), conditions=("A", "C"), seeds=1, concurrency=1,
            log_dir=str(tmp_path / "logs"),
        )

    assert called["eval_set"] is True


def test_run_utility_eval_skips_gate_when_no_mcp_config(tmp_path, monkeypatch):
    """condition-A-only runs (mcp_config=None) never touch the search backend, so the
    gate must not attempt an HTTP call at all -- covered implicitly by the pre-existing
    staging test above (mcp_config=None there too), pinned explicitly here so a future
    change can't silently make mcp_config=None try to hit a backend anyway."""
    from unittest.mock import patch

    dataset_dir = tmp_path / "dataset"
    corpus_dir = dataset_dir / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")

    queries_for_eval = tmp_path / "eval_queries.json"
    queries_for_eval.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t"}]), encoding="utf-8")

    monkeypatch.setattr(aui, "agent_utility_task", lambda **kw: object())
    monkeypatch.setattr(inspect_ai, "eval_set", lambda *a, **k: None)

    with patch("jseval.utility_calibrate.httpx.Client") as MockClient:
        aui.run_utility_eval(
            queries_path=str(queries_for_eval), corpus_dir=str(corpus_dir), mcp_config=None,
            conditions=("A",), seeds=1, concurrency=1, log_dir=str(tmp_path / "logs"),
        )
        MockClient.assert_not_called()
