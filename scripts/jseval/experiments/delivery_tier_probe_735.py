#!/usr/bin/env python3
"""Delivery-tier probe (tempdoc 735 G4/W3): the refresh procedure for the recorded fixtures
under ``scripts/jseval/tests/fixtures/recorded/``.

QUESTION this refreshes, not re-litigates: tempdoc 735's raw-SDK debug probe (Claude Code CLI
2.1.209, 2026-07-14) established the delivery RULE once -- structured-if-present (the CLI hands
the model the tool's ``structuredContent``, serialized as a ``content`` STRING, dropping the
human-readable text tier entirely) else text/blocks. This script does not re-derive that rule
from first principles every run; it exercises the SAME classification function the harness
measures with, imported (never duplicated) from ``jseval.agent_utility_inspect``, against a
fresh capture -- so a rule change (the CLI silently flipped once before between v2.0.10 and
v2.0.22, anthropics/claude-code#9962) shows up as a startling result on the next refresh, not a
silent drift baked into a stale fixture.

TWO capture modes:

  --mode sdk (default, recommended -- this IS live delivery-tier verification): drives a real
    single-turn Claude Agent SDK session (``ClaudeSDKClient``, the same primitive
    ``jseval/agent_utility_inspect.py``'s executor uses) against the configured MCP endpoint,
    forces exactly the target tool call via ``allowed_tools`` + an explicit single-call prompt,
    and inspects the raw ``ToolResultBlock.content`` the CLI actually handed back to the model.
    This is the tempdoc 735 debug-cell method. Fixtures written under this mode (with
    ``--write-fixtures``) carry ``"provenance": "recorded"``.

  --mode direct-mcp: a plain JSON-RPC ``tools/call`` HTTP POST straight at the MCP endpoint (no
    SDK, no model in the loop) -- reads the SERVER's raw response (``content`` +
    ``structuredContent``, both always present server-side when applicable) and APPLIES the
    known delivery rule in Python to derive what a rule-following CLI would forward. Useful for
    a fast content-shape diff after a server-side change (no live-agent cost), but it is NOT a
    live re-verification of CLI behavior. Fixtures written under this mode carry
    ``"provenance": "reconstructed-from-source, not recorded"`` and the script prints a warning
    -- never silently upgrade a direct-mcp capture to "recorded" (that is exactly the
    "never present synthesized data as recorded" violation tempdoc 735 W3 forbids).

USAGE:
  python delivery_tier_probe_735.py --base-url http://127.0.0.1:33221/mcp
  python delivery_tier_probe_735.py --base-url http://127.0.0.1:33221/mcp --write-fixtures
  python delivery_tier_probe_735.py --mode direct-mcp --write-fixtures
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.request import Request, urlopen

# Make `jseval` importable when run as a file (mirrors experiments/route_mock_home.py).
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from jseval.agent_utility_inspect import (  # noqa: E402
    _delivered_fields,
    _delivered_tier,
)

DEFAULT_BASE_URL = "http://127.0.0.1:33221/mcp"
FIXTURES_DIR = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "recorded"

# One representative call per tool -- deliberately fixed, non-corpus-dependent arguments so a
# refresh run is reproducible across corpora (the tool NAME/argument SHAPE matters for this
# probe, not the retrieval quality of a particular query).
_PROBE_CALLS = {
    "justsearch_status": {"name": "justsearch_status", "arguments": {}},
    "justsearch_search": {
        "name": "justsearch_search",
        "arguments": {"query": "troubleshooting timeout errors", "limit": 5},
    },
    "justsearch_answer": {
        "name": "justsearch_answer",
        "arguments": {"question": "What causes timeout errors?"},
    },
}

_FIXTURE_FILES = {
    "justsearch_status": FIXTURES_DIR / "justsearch_status_prose.json",
    "justsearch_search": FIXTURES_DIR / "justsearch_search_structured.json",
    "justsearch_answer": FIXTURES_DIR / "justsearch_answer_structured.json",
}


# ---------------------------------------------------------------------------
# --mode direct-mcp: raw JSON-RPC tools/call, delivery rule applied in Python
# ---------------------------------------------------------------------------

def _mcp_call(base_url: str, method: str, params: dict, request_id: str, timeout: float) -> dict:
    body = json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}).encode(
        "utf-8"
    )
    request = Request(
        base_url,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "User-Agent": "jseval-delivery-tier-probe-735/1",
        },
        method="POST",
    )
    with urlopen(request, timeout=timeout) as response:  # noqa: S310 - operator-supplied MCP URL
        payload = json.loads(response.read().decode("utf-8"))
    if payload.get("error"):
        raise RuntimeError(f"MCP {method} failed: {payload['error']}")
    result = payload.get("result")
    if not isinstance(result, dict):
        raise ValueError(f"MCP {method} returned a malformed result")
    return result


def _apply_known_delivery_rule(server_result: dict) -> str | list:
    """Reconstruct what a rule-following CLI (tempdoc 735's established rule) would deliver to
    the model, from the SERVER's raw tool-call result (which always carries both tiers when
    applicable). This is a deterministic Python re-application of a KNOWN rule -- not a live
    observation of actual CLI behavior; callers must not present its output as "recorded"."""
    structured = server_result.get("structuredContent")
    if structured is not None:
        return json.dumps(structured, separators=(",", ":"), sort_keys=False)
    return server_result.get("content")


def probe_direct_mcp(base_url: str, timeout: float) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for tool, call in _PROBE_CALLS.items():
        server_result = _mcp_call(
            base_url, "tools/call", call, request_id=f"jseval-735-{tool}", timeout=timeout
        )
        delivered_content = _apply_known_delivery_rule(server_result)
        out[tool] = {
            "is_error": bool(server_result.get("isError")),
            "content": delivered_content,
            "had_structured_content": server_result.get("structuredContent") is not None,
        }
    return out


# ---------------------------------------------------------------------------
# --mode sdk: a real single-turn Claude Agent SDK session (the tempdoc 735 method)
# ---------------------------------------------------------------------------

async def _run_one_sdk_probe(base_url: str, tool: str, call: dict, model: str, timeout_s: float) -> dict:
    """Drive one forced single-tool-call SDK turn and return the raw
    ``{"is_error", "content"}`` the CLI handed back for it -- the same shape
    ``jseval/agent_utility_inspect.py``'s ``_one_attempt`` stashes into
    ``capture["results"]`` (that function is this probe's production analogue;
    mirrored here deliberately, not duplicated logic -- this script has no
    Inspect/eval-log dependency to share code with it directly)."""
    from claude_agent_sdk import (
        ClaudeAgentOptions,
        ClaudeSDKClient,
        ToolResultBlock,
        UserMessage,
    )

    full_tool_name = f"mcp__justsearch__{tool}"
    prompt = (
        f"Call the tool {full_tool_name} with EXACTLY these arguments and nothing else: "
        f"{json.dumps(call['arguments'])}. Do not call any other tool. Do not explain."
    )
    opts = ClaudeAgentOptions(
        model=model,
        permission_mode="bypassPermissions",
        max_turns=3,
        mcp_servers={"justsearch": {"type": "http", "url": base_url}},
        strict_mcp_config=True,
        allowed_tools=[full_tool_name],
        setting_sources=None,
    )
    found: dict | None = None

    async def _drive():
        nonlocal found
        async with ClaudeSDKClient(options=opts) as client:
            await client.query(prompt)
            async for msg in client.receive_response():
                if isinstance(msg, UserMessage):
                    for b in (getattr(msg, "content", None) or []):
                        if isinstance(b, ToolResultBlock) and found is None:
                            found = {"is_error": bool(b.is_error), "content": b.content}

    await asyncio.wait_for(_drive(), timeout=timeout_s)
    if found is None:
        raise RuntimeError(
            f"SDK probe for {full_tool_name} completed without a ToolResultBlock -- "
            "the model may have refused the forced call; inspect manually."
        )
    return found


async def probe_via_sdk_all(base_url: str, model: str, timeout_s: float) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for tool, call in _PROBE_CALLS.items():
        out[tool] = await _run_one_sdk_probe(base_url, tool, call, model, timeout_s)
    return out


# ---------------------------------------------------------------------------
# Reporting + fixture write-back
# ---------------------------------------------------------------------------

def _report(captured: dict[str, dict]) -> list[dict]:
    rows = []
    for tool, entry in captured.items():
        content = entry.get("content")
        tier = _delivered_tier(content)
        fields = _delivered_fields(content)
        shape = "list" if isinstance(content, list) else ("str" if isinstance(content, str) else type(content).__name__)
        rows.append({
            "tool": tool,
            "content_python_type": shape,
            "content_len": len(content) if isinstance(content, (str, list)) else None,
            "delivered_tier": tier,
            "delivered_fields": fields,
            "is_error": entry.get("is_error"),
        })
    return rows


def _write_fixtures(captured: dict[str, dict], *, provenance: str, cli_version: str | None) -> None:
    stamped = datetime.now(timezone.utc).date().isoformat()
    for tool, entry in captured.items():
        path = _FIXTURE_FILES.get(tool)
        if path is None:
            continue
        content = entry.get("content")
        tier = _delivered_tier(content)
        payload = {
            "tool": tool,
            "delivered_tier": tier,
            "provenance": provenance,
            "provenance_note": (
                f"Refreshed by experiments/delivery_tier_probe_735.py on {stamped}."
            ),
            "cli_version": cli_version,
            "mcp_tool_surface_version": None,
            "stamped": stamped,
            "result": {"is_error": bool(entry.get("is_error")), "content": content},
        }
        path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"wrote {path} (provenance={provenance!r}, delivered_tier={tier!r})")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="MCP HTTP endpoint URL")
    parser.add_argument("--mode", choices=["sdk", "direct-mcp"], default="sdk")
    parser.add_argument("--model", default="haiku", help="model alias for --mode sdk")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--write-fixtures", action="store_true")
    args = parser.parse_args()

    if args.mode == "direct-mcp":
        print("WARNING: --mode direct-mcp does NOT observe real CLI delivery behavior -- it "
              "applies the tempdoc 735 rule in Python to the server's raw response. Fixtures "
              "written from this mode are 'reconstructed-from-source, not recorded'.")
        captured = probe_direct_mcp(args.base_url, args.timeout)
        provenance = "reconstructed-from-source, not recorded"
        cli_version = None
    else:
        captured = asyncio.run(probe_via_sdk_all(args.base_url, args.model, args.timeout))
        provenance = "recorded"
        cli_version = "observed at runtime -- see `claude --version` on the machine that ran this probe"

    for row in _report(captured):
        print(json.dumps(row, indent=2, default=str))

    if args.write_fixtures:
        _write_fixtures(captured, provenance=provenance, cli_version=cli_version)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
