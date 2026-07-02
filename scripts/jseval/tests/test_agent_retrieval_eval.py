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
    _build_agent_cmd,
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
