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

from jseval.agent_retrieval_eval import (
    AgentResult,
    build_disallowed_tools,
    find_disallowed_tool_calls,
)


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
        "WebFetch", "WebSearch", "Agent", "Task", "Skill",
    }


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
