"""Tests for jseval.agent_retrieval_eval's tool-disallow logic (tempdoc 624
confidence pass).

Covers the pure-Python pieces of the condition A/B/C `--disallowedTools`
construction and the empirical tool_calls scan, without needing a live
`claude` CLI invocation:

  - No condition disallowed WebFetch/WebSearch, so an agent could silently
    answer via a live web lookup instead of the local corpus.
  - Condition C's `Read,Grep,Glob` disallow list didn't include Bash, so its
    "JustSearch-only" premise (no native file access) wasn't enforced.
  - A blocked WebSearch was observed being routed around via a spawned
    subagent (Agent/Task), so those must be disallowed everywhere too.
  - A blocked WebSearch/Agent/Task was then observed being routed around via
    a locally-installed Skill that internally orchestrated its own
    multi-agent workflow, so Skill must be disallowed everywhere too.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

from jseval.agent_retrieval_eval import (
    AgentResult,
    _build_agent_cmd,
    _score_answer,
    build_disallowed_tools,
    find_disallowed_tool_calls,
    find_leak_suspect_tool_calls,
    parse_claude_init_event,
    parse_claude_stream_json,
    run_agent_eval,
    stage_corpus_dir,
)


# --- _score_answer scorer semantics fixture (tempdoc 624 §M.8 amendment, Step 0
# item 6): pins the exact-match scorer's case/whitespace/punctuation behavior so
# a future refactor can't silently loosen or tighten what counts as "correct"
# without a test noticing. ---

class TestScoreAnswerSemantics:
    def test_exact_case_different_match(self):
        assert _score_answer("Paris", "the capital is paris") is True

    def test_leading_and_trailing_whitespace_in_answer_still_matches(self):
        assert _score_answer("Paris", "   paris   ") is True

    def test_answer_embedded_in_longer_sentence_matches(self):
        assert _score_answer(
            "3.5 million", "According to the article, the population is 3.5 million people.",
        ) is True

    def test_wrong_sibling_near_miss_does_not_match(self):
        """A similar-but-different value must NOT match via substring luck --
        '3.4 million' is not a substring of an answer only containing '3.5
        million', unlike e.g. 'Paris'/'Parisian' where substring containment
        would accidentally succeed."""
        assert _score_answer("3.4 million", "the population is 3.5 million people") is False

    def test_ground_truth_trailing_period_is_stripped(self):
        assert _score_answer("Paris.", "the capital is paris") is True

    def test_abstention_phrase_accepted_for_insufficient_information(self):
        assert _score_answer(
            "Insufficient information.", "I cannot find any relevant articles in the corpus.",
        ) is True

    def test_non_abstaining_answer_rejected_for_insufficient_information(self):
        assert _score_answer("Insufficient information.", "The answer is Paris.") is False


def test_build_disallowed_tools_condition_a_blocks_web_and_subagent():
    disallowed = build_disallowed_tools("A")
    assert set(disallowed) == {"WebFetch", "WebSearch", "Agent", "Task", "Skill"}


def test_build_disallowed_tools_condition_b_blocks_web_and_subagent():
    disallowed = build_disallowed_tools("B")
    assert set(disallowed) == {"WebFetch", "WebSearch", "Agent", "Task", "Skill"}


def test_build_disallowed_tools_condition_c_also_blocks_file_and_shell_tools():
    disallowed = build_disallowed_tools("C")
    assert set(disallowed) == {
        "Read", "Grep", "Glob", "Bash",
        "ReadMcpResourceTool", "ReadMcpResourceDirTool", "ListMcpResourcesTool",
        "WebFetch", "WebSearch", "Agent", "Task", "Skill",
    }


def test_build_disallowed_tools_condition_c_blocks_mcp_resource_read_channel():
    """tempdoc 624 §M.8 amendment (Step 0 item 3): a condition-C cell attempted a
    resource-read tool (an MCP corpus-file-access channel outside the retrieval
    surface, distinct from Read/Grep/Glob/Bash) on 2026-07-07 and it went
    unflagged. A/B keep these allowed -- only C's "no native file access" premise
    is violated by them."""
    disallowed_c = build_disallowed_tools("C")
    for tool in ("ReadMcpResourceTool", "ReadMcpResourceDirTool", "ListMcpResourcesTool"):
        assert tool in disallowed_c
    for cond in ("A", "B"):
        disallowed = build_disallowed_tools(cond)
        for tool in ("ReadMcpResourceTool", "ReadMcpResourceDirTool", "ListMcpResourcesTool"):
            assert tool not in disallowed


def test_find_disallowed_tool_calls_empty_when_clean():
    tool_calls = [
        {"tool": "mcp__justsearch__retrieve_context", "input": {}},
        {"tool": "Read", "input": {}},
    ]
    disallowed = build_disallowed_tools("B")  # Read is allowed under B
    assert find_disallowed_tool_calls(tool_calls, disallowed) == []


def test_find_disallowed_tool_calls_flags_web_search():
    tool_calls = [
        {"tool": "Read", "input": {}},
        {"tool": "WebSearch", "input": {"query": "..."}},
    ]
    disallowed = build_disallowed_tools("A")
    flagged = find_disallowed_tool_calls(tool_calls, disallowed)
    assert len(flagged) == 1
    assert flagged[0]["tool"] == "WebSearch"


def test_find_disallowed_tool_calls_flags_bash_under_condition_c():
    """The concrete bug this session found: condition C's original
    Read,Grep,Glob list left Bash open as a file-read backdoor."""
    tool_calls = [
        {"tool": "Bash", "input": {"command": "cat article_001.md"}},
        {"tool": "mcp__justsearch__retrieve_context", "input": {}},
    ]
    disallowed = build_disallowed_tools("C")
    flagged = find_disallowed_tool_calls(tool_calls, disallowed)
    assert len(flagged) == 1
    assert flagged[0]["tool"] == "Bash"


def test_find_disallowed_tool_calls_flags_subagent_routing_around_web_block():
    """The subagent-routing bug: a blocked WebSearch pursued indirectly via
    a spawned Agent/Task tool must also be caught."""
    tool_calls = [
        {"tool": "Agent", "input": {"prompt": "look this up"}},
    ]
    disallowed = build_disallowed_tools("A")
    flagged = find_disallowed_tool_calls(tool_calls, disallowed)
    assert len(flagged) == 1
    assert flagged[0]["tool"] == "Agent"


def test_find_disallowed_tool_calls_flags_skill_routing_around_web_block():
    """The Skill-routing bug: a blocked WebSearch/Agent/Task pursued
    indirectly via an installed Skill (e.g. "deep-research", which internally
    orchestrates its own multi-agent workflow) must also be caught."""
    tool_calls = [
        {"tool": "Skill", "input": {"skill": "deep-research"}},
    ]
    disallowed = build_disallowed_tools("A")
    flagged = find_disallowed_tool_calls(tool_calls, disallowed)
    assert len(flagged) == 1
    assert flagged[0]["tool"] == "Skill"


def test_agent_result_disallowed_tool_calls_defaults_empty():
    result = AgentResult(query="q", answer="a", question_type="t", condition="A", model="haiku")
    assert result.disallowed_tool_calls == []


# --- find_leak_suspect_tool_calls: the answer-key leak detection backstop
# (tempdoc 624 §As-built #7). A real leak was found where an agent under a
# file-tool condition read/globbed the eval's own gold-answer file
# (queries.json) directly instead of the corpus, producing a leaked-but-correct
# answer that no other check distinguishes from a genuine one. This scan reuses
# the SAME tool_calls capture find_disallowed_tool_calls already reads.

def test_find_leak_suspect_tool_calls_flags_read_of_queries_json():
    tool_calls = [
        {"tool": "Read", "input": {"file_path": "/eval/data/queries.json"}},
    ]
    flagged = find_leak_suspect_tool_calls(tool_calls)
    assert len(flagged) == 1
    assert flagged[0]["tool"] == "Read"


def test_find_leak_suspect_tool_calls_flags_glob_pattern_for_queries_json():
    tool_calls = [
        {"tool": "Glob", "input": {"pattern": "**/queries.json"}},
    ]
    flagged = find_leak_suspect_tool_calls(tool_calls)
    assert len(flagged) == 1
    assert flagged[0]["tool"] == "Glob"


def test_find_leak_suspect_tool_calls_empty_when_clean():
    """A clean run: Read of the actual corpus article, an MCP retrieval call,
    and a Glob over the corpus directory — none of it names queries.json."""
    tool_calls = [
        {"tool": "Read", "input": {"file_path": "/corpus/article_042.md"}},
        {"tool": "mcp__justsearch__retrieve_context", "input": {"query": "who founded X"}},
        {"tool": "Glob", "input": {"pattern": "*.md"}},
    ]
    assert find_leak_suspect_tool_calls(tool_calls) == []


def test_find_leak_suspect_tool_calls_ignores_other_tools_naming_the_file():
    """Only Read/Glob carry a path argument that can leak the file's contents;
    e.g. a Bash call merely mentioning "queries.json" in an unrelated string
    isn't the same class of concern this scan targets."""
    tool_calls = [{"tool": "Bash", "input": {"command": "echo queries.json"}}]
    assert find_leak_suspect_tool_calls(tool_calls) == []


def test_agent_result_leak_suspect_tool_calls_defaults_empty():
    result = AgentResult(query="q", answer="a", question_type="t", condition="A", model="haiku")
    assert result.leak_suspect_tool_calls == []


# --- _build_agent_cmd: the actual subprocess-argv wiring (tempdoc 624 confidence
# pass follow-up). build_disallowed_tools/find_disallowed_tool_calls are correct
# in isolation, but nothing previously exercised the code path that joins their
# output into the real `claude -p ... --disallowedTools ...` argv that
# _run_single_query hands to subprocess.run. These tests fail if that wiring
# breaks (wrong join, wrong variable, wrong flag) even if the helpers themselves
# are untouched and still pass their own tests. ---

def _disallowed_tools_arg(cmd: list[str]) -> str:
    idx = cmd.index("--disallowedTools")
    return cmd[idx + 1]


def test_build_agent_cmd_condition_a_disallowed_tools_matches_helper():
    cmd = _build_agent_cmd(
        "claude", "prompt", "haiku", "/corpus", "A",
        None, "/tmp/empty_mcp.json", 0.50,
    )
    assert _disallowed_tools_arg(cmd) == ",".join(build_disallowed_tools("A"))


def test_build_agent_cmd_condition_b_disallowed_tools_matches_helper():
    cmd = _build_agent_cmd(
        "claude", "prompt", "haiku", "/corpus", "B",
        "/mcp.json", "/tmp/empty_mcp.json", 0.50,
    )
    assert _disallowed_tools_arg(cmd) == ",".join(build_disallowed_tools("B"))


def test_build_agent_cmd_condition_c_disallowed_tools_matches_helper():
    cmd = _build_agent_cmd(
        "claude", "prompt", "haiku", "/corpus", "C",
        "/mcp.json", "/tmp/empty_mcp.json", 0.50,
    )
    assert _disallowed_tools_arg(cmd) == ",".join(build_disallowed_tools("C"))


def test_build_agent_cmd_condition_a_uses_empty_mcp_strict_config():
    """Condition A must pass the empty MCP config so no MCP tools are wired in
    at all (the file-tools-only baseline)."""
    cmd = _build_agent_cmd(
        "claude", "prompt", "haiku", "/corpus", "A",
        "/should-be-ignored-mcp.json", "/tmp/empty_mcp.json", 0.50,
    )
    assert "--strict-mcp-config" in cmd
    mcp_idx = cmd.index("--mcp-config")
    assert cmd[mcp_idx + 1] == "/tmp/empty_mcp.json"


def test_build_agent_cmd_condition_b_uses_real_mcp_config():
    cmd = _build_agent_cmd(
        "claude", "prompt", "haiku", "/corpus", "B",
        "/mcp.json", "/tmp/empty_mcp.json", 0.50,
    )
    assert "--strict-mcp-config" in cmd
    mcp_idx = cmd.index("--mcp-config")
    assert cmd[mcp_idx + 1] == "/mcp.json"


def test_build_agent_cmd_condition_b_without_mcp_config_omits_mcp_flags():
    cmd = _build_agent_cmd(
        "claude", "prompt", "haiku", "/corpus", "B",
        None, "/tmp/empty_mcp.json", 0.50,
    )
    assert "--strict-mcp-config" not in cmd
    assert "--mcp-config" not in cmd


def test_build_agent_cmd_carries_model_prompt_and_budget():
    cmd = _build_agent_cmd(
        "claude", "what is X?", "opus", "/corpus", "B",
        "/mcp.json", "/tmp/empty_mcp.json", 1.25,
    )
    assert cmd[0] == "claude"
    assert "-p" in cmd
    assert cmd[cmd.index("-p") + 1] == "what is X?"
    assert cmd[cmd.index("--model") + 1] == "opus"
    assert cmd[cmd.index("--max-budget-usd") + 1] == "1.25"


# --- stage_corpus_dir: the answer-key isolation fix (tempdoc 624 §As-built #7).
#
# `--add-dir corpus_dir` handed the Claude Code CLI's Read/Glob tools a directory
# that is NOT sandboxed against `../` traversal. With `corpus_dir` scoped to
# `datasets/golden/<name>/corpus-dir/`, an agent could `Read ../queries.json` — the
# sibling gold answer key — and the CLI didn't block it. These tests build the
# ACTUAL staging directory `stage_corpus_dir` produces and assert the answer key
# is structurally absent from it, not merely unlisted. ---

def _make_dataset_dir_with_answer_key(tmp_path) -> Path:
    """A `datasets/golden/<name>/` layout: `corpus-dir/` sibling to `queries.json`
    (the exact shape `corpus_build.build_golden` produces)."""
    dataset_dir = tmp_path / "dataset"
    corpus_dir = dataset_dir / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")
    (corpus_dir / "doc2.txt").write_text("another doc", encoding="utf-8")
    (dataset_dir / "queries.json").write_text(
        json.dumps([{"query": "q", "answer": "the secret answer"}]), encoding="utf-8")
    return dataset_dir


def test_stage_corpus_dir_copies_contents(tmp_path):
    dataset_dir = _make_dataset_dir_with_answer_key(tmp_path)
    staged = stage_corpus_dir(str(dataset_dir / "corpus-dir"))
    try:
        assert (Path(staged) / "doc1.txt").read_text(encoding="utf-8") == "hello world"
        assert (Path(staged) / "doc2.txt").read_text(encoding="utf-8") == "another doc"
    finally:
        import shutil
        shutil.rmtree(Path(staged).parent, ignore_errors=True)


def test_stage_corpus_dir_answer_key_not_present_anywhere_in_staged_tree(tmp_path):
    dataset_dir = _make_dataset_dir_with_answer_key(tmp_path)
    staged = stage_corpus_dir(str(dataset_dir / "corpus-dir"))
    try:
        for _root, _dirs, files in os.walk(staged):
            assert "queries.json" not in files
    finally:
        import shutil
        shutil.rmtree(Path(staged).parent, ignore_errors=True)


def test_stage_corpus_dir_answer_key_not_reachable_via_parent_traversal(tmp_path):
    """The concrete leak this fix closes: `Read ../queries.json` from inside the
    directory handed to `--add-dir`. The staged dir's immediate parent (the fresh
    isolated temp root) must genuinely contain nothing but the staged copy."""
    dataset_dir = _make_dataset_dir_with_answer_key(tmp_path)
    staged = stage_corpus_dir(str(dataset_dir / "corpus-dir"))
    try:
        parent_listing = os.listdir(Path(staged).parent)
        assert "queries.json" not in parent_listing
        assert parent_listing == [Path(staged).name]

        traversal_target = Path(staged) / ".." / "queries.json"
        assert not os.path.exists(traversal_target)
        # sanity: the ORIGINAL corpus_dir's parent traversal DOES resolve to the
        # answer key -- this is the bug being fixed, confirmed still true of the
        # raw path so the staged-path assertion above is meaningful, not vacuous.
        original_traversal = Path(dataset_dir / "corpus-dir") / ".." / "queries.json"
        assert os.path.exists(original_traversal)
    finally:
        import shutil
        shutil.rmtree(Path(staged).parent, ignore_errors=True)


def test_stage_corpus_dir_returns_a_fresh_isolated_path_each_call(tmp_path):
    dataset_dir = _make_dataset_dir_with_answer_key(tmp_path)
    staged_a = stage_corpus_dir(str(dataset_dir / "corpus-dir"))
    staged_b = stage_corpus_dir(str(dataset_dir / "corpus-dir"))
    try:
        assert staged_a != staged_b
        assert Path(staged_a).parent != Path(staged_b).parent
    finally:
        import shutil
        shutil.rmtree(Path(staged_a).parent, ignore_errors=True)
        shutil.rmtree(Path(staged_b).parent, ignore_errors=True)


def test_run_agent_eval_invokes_subprocess_with_staged_not_original_corpus_dir(tmp_path, monkeypatch):
    """Regression test for the leak: run_agent_eval must pass a STAGED corpus_dir
    to `--add-dir`, never the original persistent path whose parent holds the
    sibling gold answer key (queries.json). Mocks the CLI subprocess so no live
    `claude` invocation is needed."""
    from jseval import agent_retrieval_eval as are

    dataset_dir = _make_dataset_dir_with_answer_key(tmp_path)
    original_corpus_dir = str(dataset_dir / "corpus-dir")

    monkeypatch.setattr(are.shutil, "which", lambda name: "claude")

    captured = {}

    class FakeCompletedProcess:
        returncode = 0
        stderr = ""
        stdout = json.dumps({
            "type": "result", "is_error": False, "result": "answer",
            "total_cost_usd": 0.01, "duration_ms": 5, "num_turns": 1,
            "usage": {}, "session_id": "",
        }) + "\n"

    def fake_run(cmd, **kwargs):
        add_dir_idx = cmd.index("--add-dir")
        used_corpus_dir = cmd[add_dir_idx + 1]
        captured["used_corpus_dir"] = used_corpus_dir
        # capture the staged dir's parent listing NOW, before run_agent_eval's
        # own cleanup removes it once every query has finished.
        captured["staged_parent_listing"] = sorted(os.listdir(Path(used_corpus_dir).parent))
        return FakeCompletedProcess()

    monkeypatch.setattr(are.subprocess, "run", fake_run)

    result = run_agent_eval(
        queries=[{"query": "q1", "answer": "answer", "question_type": "t"}],
        corpus_dir=original_corpus_dir,
        condition="A",
    )

    assert "used_corpus_dir" in captured, "subprocess.run should have been invoked"
    used_corpus_dir = captured["used_corpus_dir"]
    assert used_corpus_dir != original_corpus_dir
    assert Path(used_corpus_dir).name == "corpus-dir"
    assert captured["staged_parent_listing"] == ["corpus-dir"]  # no queries.json sibling

    # the staging directory is a temp artifact -- must be cleaned up after the run
    assert not Path(used_corpus_dir).parent.exists()

    assert result["condition"] == "A"
    assert result["errors"] == 0


# --- Watched-roots safety gate: run_agent_eval is the actual eval-executing path,
# so (unlike the optional `utility-calibrate` CLI) a stray/broader watched root must
# abort the run before any subprocess work happens (tempdoc 624 As-built #7 follow-up).

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


def test_run_agent_eval_raises_on_stray_watched_root(tmp_path, monkeypatch):
    """mcp_config_path given (condition C actually queries the search backend) + a
    stray/broader watched root reported live -> run_agent_eval must raise
    StrayWatchedRootError and must NOT reach subprocess.run (no staging, no spawn)."""
    import pytest

    from jseval import agent_retrieval_eval as are
    from jseval.utility_calibrate import StrayWatchedRootError

    dataset_dir = _make_dataset_dir_with_answer_key(tmp_path)
    corpus_dir = dataset_dir / "corpus-dir"

    mcp_config = tmp_path / "mcp.json"
    mcp_config.write_text(
        '{"mcpServers":{"justsearch":{"url":"http://127.0.0.1:56300/mcp"}}}', encoding="utf-8")

    monkeypatch.setattr(are.shutil, "which", lambda name: "claude")
    called = {"subprocess_run": False}
    monkeypatch.setattr(are.subprocess, "run",
                         lambda *a, **k: called.__setitem__("subprocess_run", True))

    from unittest.mock import patch
    with patch("jseval.utility_calibrate.httpx.Client") as MockClient:
        # a stray root broader than corpus_dir -- the corpus's own PARENT.
        _mock_roots_client(MockClient, [str(dataset_dir)])

        with pytest.raises(StrayWatchedRootError):
            run_agent_eval(
                queries=[{"query": "q1", "answer": "answer", "question_type": "t"}],
                corpus_dir=str(corpus_dir),
                mcp_config_path=str(mcp_config),
                condition="C",
            )

    assert called["subprocess_run"] is False


def test_run_agent_eval_proceeds_when_roots_correctly_scoped(tmp_path, monkeypatch):
    """The mirror-positive: when the live backend's watched roots are exactly
    corpus_dir, the safety gate must NOT block the run."""
    from unittest.mock import patch

    from jseval import agent_retrieval_eval as are

    dataset_dir = _make_dataset_dir_with_answer_key(tmp_path)
    corpus_dir = dataset_dir / "corpus-dir"

    mcp_config = tmp_path / "mcp.json"
    mcp_config.write_text(
        '{"mcpServers":{"justsearch":{"url":"http://127.0.0.1:56300/mcp"}}}', encoding="utf-8")

    monkeypatch.setattr(are.shutil, "which", lambda name: "claude")

    class FakeCompletedProcess:
        returncode = 0
        stderr = ""
        stdout = json.dumps({
            "type": "result", "is_error": False, "result": "answer",
            "total_cost_usd": 0.01, "duration_ms": 5, "num_turns": 1,
            "usage": {}, "session_id": "",
        }) + "\n"

    monkeypatch.setattr(are.subprocess, "run", lambda *a, **k: FakeCompletedProcess())

    with patch("jseval.utility_calibrate.httpx.Client") as MockClient:
        _mock_roots_client(MockClient, [str(corpus_dir)])

        result = run_agent_eval(
            queries=[{"query": "q1", "answer": "answer", "question_type": "t"}],
            corpus_dir=str(corpus_dir),
            mcp_config_path=str(mcp_config),
            condition="C",
        )

    assert result["condition"] == "C"
    assert result["errors"] == 0


def test_run_agent_eval_skips_gate_when_no_mcp_config(tmp_path, monkeypatch):
    """condition A (mcp_config_path=None) never touches the search backend, so the
    gate must not attempt an HTTP call at all."""
    from unittest.mock import patch

    from jseval import agent_retrieval_eval as are

    dataset_dir = _make_dataset_dir_with_answer_key(tmp_path)
    corpus_dir = dataset_dir / "corpus-dir"

    monkeypatch.setattr(are.shutil, "which", lambda name: "claude")

    class FakeCompletedProcess:
        returncode = 0
        stderr = ""
        stdout = json.dumps({
            "type": "result", "is_error": False, "result": "answer",
            "total_cost_usd": 0.01, "duration_ms": 5, "num_turns": 1,
            "usage": {}, "session_id": "",
        }) + "\n"

    monkeypatch.setattr(are.subprocess, "run", lambda *a, **k: FakeCompletedProcess())

    with patch("jseval.utility_calibrate.httpx.Client") as MockClient:
        result = run_agent_eval(
            queries=[{"query": "q1", "answer": "answer", "question_type": "t"}],
            corpus_dir=str(corpus_dir),
            mcp_config_path=None,
            condition="A",
        )
        MockClient.assert_not_called()

    assert result["condition"] == "A"


# --- parse_claude_stream_json (tempdoc 624 §As-built #5 residual-gap close) --
#
# Shared by run_agent_eval (above) and agent_utility_inspect.claude_agent_solver
# -- these fixtures pin the line-delimited stream-json event shape both callers
# depend on, independent of a live `claude` CLI invocation.

def _stream_json(*events: dict) -> str:
    return "\n".join(json.dumps(e) for e in events)


class TestParseClaudeStreamJson:
    def test_parses_tool_use_and_result_event(self):
        stdout = _stream_json(
            {"type": "assistant", "message": {"content": [
                {"type": "tool_use", "name": "Read", "input": {"file_path": "/corpus/doc1.txt"}},
            ]}},
            {"type": "result", "is_error": False, "result": "final answer",
             "total_cost_usd": 0.02, "num_turns": 1, "session_id": "sess-1",
             "usage": {"input_tokens": 10, "cache_creation_input_tokens": 5,
                       "cache_read_input_tokens": 0, "output_tokens": 20}},
        )
        tool_calls, data, session_id = parse_claude_stream_json(stdout)
        assert tool_calls == [{"tool": "Read", "input": {"file_path": "/corpus/doc1.txt"}}]
        assert data["result"] == "final answer"
        assert data["total_cost_usd"] == 0.02
        assert data["usage"]["cache_creation_input_tokens"] == 5
        assert session_id == "sess-1"

    def test_attaches_tool_result_response_preview_to_last_tool_call(self):
        stdout = _stream_json(
            {"type": "assistant", "message": {"content": [
                {"type": "tool_use", "name": "Read", "input": {"file_path": "/corpus/doc1.txt"}},
                {"type": "tool_result", "content": [{"type": "text", "text": "file body preview"}]},
            ]}},
            {"type": "result", "is_error": False, "result": "ok"},
        )
        tool_calls, _data, _sid = parse_claude_stream_json(stdout)
        assert tool_calls[0]["response_preview"] == "file body preview"

    def test_attaches_is_error_false_and_no_error_snippet_on_healthy_call(self):
        """tempdoc 624 §M.8 amendment (Step 0 item 2): the per-call outcome must
        come from the tool_result block's own `is_error` flag -- not the agent's
        self-reported reflection."""
        stdout = _stream_json(
            {"type": "assistant", "message": {"content": [
                {"type": "tool_use", "name": "Read", "input": {"file_path": "/corpus/doc1.txt"}},
                {"type": "tool_result", "is_error": False,
                 "content": [{"type": "text", "text": "file body preview"}]},
            ]}},
            {"type": "result", "is_error": False, "result": "ok"},
        )
        tool_calls, _data, _sid = parse_claude_stream_json(stdout)
        assert tool_calls[0]["is_error"] is False
        assert "error_snippet" not in tool_calls[0]

    def test_attaches_is_error_true_and_error_snippet_on_failed_call(self):
        stdout = _stream_json(
            {"type": "assistant", "message": {"content": [
                {"type": "tool_use", "name": "Read", "input": {"file_path": "/nope.txt"}},
                {"type": "tool_result", "is_error": True,
                 "content": [{"type": "text", "text": "ENOENT: no such file or directory"}]},
            ]}},
            {"type": "result", "is_error": False, "result": "could not find it"},
        )
        tool_calls, _data, _sid = parse_claude_stream_json(stdout)
        assert tool_calls[0]["is_error"] is True
        assert tool_calls[0]["error_snippet"] == "ENOENT: no such file or directory"

    def test_error_snippet_truncated_to_200_chars(self):
        long_error = "E" * 500
        stdout = _stream_json(
            {"type": "assistant", "message": {"content": [
                {"type": "tool_use", "name": "Bash", "input": {"command": "boom"}},
                {"type": "tool_result", "is_error": True,
                 "content": [{"type": "text", "text": long_error}]},
            ]}},
        )
        tool_calls, _data, _sid = parse_claude_stream_json(stdout)
        assert len(tool_calls[0]["error_snippet"]) <= 200

    def test_summarizes_long_string_and_nonscalar_params(self):
        long_val = "x" * 250
        stdout = _stream_json({"type": "assistant", "message": {"content": [
            {"type": "tool_use", "name": "Grep", "input": {"pattern": long_val, "paths": ["a", "b"]}},
        ]}})
        tool_calls, _data, _sid = parse_claude_stream_json(stdout)
        summary = tool_calls[0]["input"]
        assert summary["pattern"] == "x" * 100 + "..."
        assert summary["paths"] == json.dumps(["a", "b"])[:100]

    def test_skips_malformed_json_lines(self):
        stdout = "\n".join([
            "not json at all {{{",
            json.dumps({"type": "result", "is_error": False, "result": "ok"}),
        ])
        tool_calls, data, _sid = parse_claude_stream_json(stdout)
        assert tool_calls == []
        assert data["result"] == "ok"

    def test_no_result_event_returns_none_data_and_empty_session(self):
        """Mirrors a crash/timeout: the stream never emits a `result` event."""
        stdout = _stream_json({"type": "assistant", "message": {"content": [
            {"type": "tool_use", "name": "Read", "input": {"file_path": "x"}},
        ]}})
        tool_calls, data, session_id = parse_claude_stream_json(stdout)
        assert data is None
        assert session_id == ""
        assert len(tool_calls) == 1  # tool calls made before the crash are still captured

    def test_disallowed_tool_call_fixture_flows_through_find_disallowed_tool_calls(self):
        """Fixture with a disallowed call: WebSearch under condition A."""
        stdout = _stream_json({"type": "assistant", "message": {"content": [
            {"type": "tool_use", "name": "WebSearch", "input": {"query": "leak"}},
        ]}})
        tool_calls, _data, _sid = parse_claude_stream_json(stdout)
        flagged = find_disallowed_tool_calls(tool_calls, build_disallowed_tools("A"))
        assert len(flagged) == 1
        assert flagged[0]["tool"] == "WebSearch"

    def test_queries_json_read_fixture_flows_through_find_leak_suspect_tool_calls(self):
        """Fixture with a queries.json-touching Read call."""
        stdout = _stream_json({"type": "assistant", "message": {"content": [
            {"type": "tool_use", "name": "Read", "input": {"file_path": "/eval/queries.json"}},
        ]}})
        tool_calls, _data, _sid = parse_claude_stream_json(stdout)
        flagged = find_leak_suspect_tool_calls(tool_calls)
        assert len(flagged) == 1
        assert flagged[0]["tool"] == "Read"

    def test_run_agent_eval_and_inspect_solver_both_call_the_shared_parser(self):
        """Not a forked reimplementation: both modules' module-level name IS the
        shared function (import-identity, not just behavioral parity)."""
        import pytest
        pytest.importorskip("inspect_ai")  # agent_utility_inspect needs the opt-in extra

        from jseval import agent_retrieval_eval as are
        from jseval import agent_utility_inspect as aui

        assert are.parse_claude_stream_json is parse_claude_stream_json
        assert aui.parse_claude_stream_json is parse_claude_stream_json


# --- parse_claude_init_event (tempdoc 624 battlefield retrospective): reads the
# `system`/`init` event -- the CLI's own disclosure of what it actually connected/
# offered, distinct from parse_claude_stream_json's tool_use/result extraction. ---

class TestParseClaudeInitEvent:
    def test_parses_mcp_servers_and_tools(self):
        stdout = _stream_json(
            {"type": "system", "subtype": "init",
             "mcp_servers": [{"name": "justsearch", "status": "connected"}],
             "tools": ["Read", "Grep", "mcp__justsearch__search_query",
                       "mcp__justsearch__ingest"]},
            {"type": "result", "is_error": False, "result": "ok"},
        )
        event = parse_claude_init_event(stdout)
        assert event["mcp_servers"] == [{"name": "justsearch", "status": "connected"}]
        assert event["tools"] == ["Read", "Grep", "mcp__justsearch__search_query",
                                   "mcp__justsearch__ingest"]

    def test_dead_config_shows_empty_mcp_servers_and_no_mcp_tools(self):
        """The exact signature of a silently-dropped `url`-only mcp_config entry:
        the init event still fires, but mcp_servers is empty and no mcp__ tool
        is offered -- clean exit, no error, just a missing surface."""
        stdout = _stream_json(
            {"type": "system", "subtype": "init", "mcp_servers": [],
             "tools": ["Read", "Grep", "Glob", "Bash"]},
            {"type": "result", "is_error": False, "result": "answered from files"},
        )
        event = parse_claude_init_event(stdout)
        assert event["mcp_servers"] == []
        assert all(not t.startswith("mcp__") for t in event["tools"])

    def test_returns_none_when_no_init_event(self):
        """Crash/timeout-shaped stdout: the stream never reached an init event."""
        stdout = _stream_json({"type": "assistant", "message": {"content": [
            {"type": "tool_use", "name": "Read", "input": {"file_path": "x"}},
        ]}})
        assert parse_claude_init_event(stdout) is None

    def test_skips_malformed_lines_before_init_event(self):
        stdout = "\n".join([
            "not json {{{",
            json.dumps({"type": "system", "subtype": "init", "mcp_servers": [],
                        "tools": ["Read"]}),
        ])
        event = parse_claude_init_event(stdout)
        assert event["tools"] == ["Read"]

    def test_ignores_non_init_system_events(self):
        stdout = _stream_json(
            {"type": "system", "subtype": "other", "tools": ["ignored"]},
            {"type": "system", "subtype": "init", "mcp_servers": [], "tools": ["Read"]},
        )
        event = parse_claude_init_event(stdout)
        assert event["tools"] == ["Read"]
