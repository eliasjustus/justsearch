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

import pytest

pytest.importorskip("inspect_ai")

from jseval.agent_retrieval_eval import build_disallowed_tools  # noqa: E402
from jseval.agent_utility_inspect import _build_argv  # noqa: E402


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
