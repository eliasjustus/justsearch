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
    _score_answer,
    build_disallowed_tools,
    find_disallowed_tool_calls,
    find_leak_suspect_tool_calls,
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

