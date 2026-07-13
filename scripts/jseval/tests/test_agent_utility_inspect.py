"""Tests for jseval.agent_utility_inspect — the Inspect-AI-based executor.

Executor v2 (tempdoc 675): each cell now runs as an in-process Claude Agent SDK
session (`ClaudeSDKClient`), not a `claude -p` subprocess, so there is no argv
to build and no stdout stream-json to parse. These tests unit-test the PURE
LOGIC that sits around the SDK call:

- `agent_utility_task` — the single-pool `condition × query` cross-product
  construction (one Inspect task for the whole matrix, not one per condition).
- `_record_cell` — the executed-vs-blocked projection of the SDK's own
  objects (tool-use blocks, tool-result blocks, `ResultMessage`) into
  `state.metadata`, plus the offered-MCP-surface tri-state assertion.
- `run_utility_eval` — corpus-dir isolation, cleanup-on-raise, the
  watched-roots safety gate, and the no-mcp gate skip.

`ClaudeSDKClient`/the async SDK session itself is NOT mocked here — a live
smoke test covers the SDK-integration path separately.

`inspect_ai` is an opt-in extra (`pip install jseval[agent]`); skipped if
not installed.
"""

from __future__ import annotations

import json
import hashlib
import os
import subprocess
import types
from pathlib import Path

import pytest

pytest.importorskip("inspect_ai")

import inspect_ai  # noqa: E402
from inspect_ai import Task  # noqa: E402
from inspect_ai.model import ChatMessageUser, ModelName  # noqa: E402
from inspect_ai.solver import TaskState  # noqa: E402

from jseval import agent_utility_inspect as aui  # noqa: E402
from jseval.agent_retrieval_eval import (  # noqa: E402
    build_disallowed_tools,
    find_disallowed_tool_calls,
    find_leak_suspect_tool_calls,
)


def _state(input_text="what is x?", sample_id="q0"):
    return TaskState(
        model=ModelName("mockllm/model"), sample_id=sample_id, epoch=0,
        input=input_text, messages=[ChatMessageUser(content=input_text)],
    )


def _rmsg(**overrides):
    """A minimal stand-in for `claude_agent_sdk.ResultMessage` (tests only need
    the attributes `_record_cell` reads off it, not the real SDK class)."""
    defaults = dict(
        total_cost_usd=0.02, usage={}, num_turns=1, is_error=False,
        permission_denials=[], result="the answer",
    )
    defaults.update(overrides)
    return types.SimpleNamespace(**defaults)


# --- agent_utility_task: single-pool condition x query cross-product
# (tempdoc 675 -- replaces the old per-condition-task construction). ---

def test_agent_utility_task_builds_one_task_over_the_full_cross_product(tmp_path):
    queries_path = tmp_path / "queries.json"
    queries_path.write_text(json.dumps([
        {"query": "q1", "answer": "a1", "question_type": "t1"},
        {"query": "q2", "answer": "a2", "question_type": "t2"},
    ]), encoding="utf-8")

    t = aui.agent_utility_task(
        conditions=("A", "B"), queries_path=str(queries_path), corpus_dir=str(tmp_path),
        mcp_config=None, model="haiku", corpus_dataset="ds", corpus_signature="sig",
    )

    assert isinstance(t, Task)
    samples = list(t.dataset)
    # ONE task; its dataset is the flat condition x query cross-product.
    assert len(samples) == 4  # 2 conditions x 2 queries

    by_id = {s.id: s for s in samples}
    assert set(by_id) == {"A|q0", "A|q1", "B|q0", "B|q1"}
    for cond in ("A", "B"):
        for i in (0, 1):
            sample = by_id[f"{cond}|q{i}"]
            assert sample.metadata["condition"] == cond

    # Task metadata carries the cohort identity, but NOT a top-level condition
    # (condition lives on the sample, not the task -- one task, many conditions).
    assert "model" in t.metadata
    assert "corpus" in t.metadata
    assert "cohort" in t.metadata
    assert "condition" not in t.metadata


def test_agent_utility_task_respects_max_queries(tmp_path):
    queries_path = tmp_path / "queries.json"
    queries_path.write_text(json.dumps([
        {"query": "q1", "answer": "a1"},
        {"query": "q2", "answer": "a2"},
        {"query": "q3", "answer": "a3"},
    ]), encoding="utf-8")

    t = aui.agent_utility_task(
        conditions=("A", "B", "C"), queries_path=str(queries_path), corpus_dir=str(tmp_path),
        mcp_config=None, model="haiku", max_queries=1,
    )

    samples = list(t.dataset)
    assert len(samples) == 3  # 3 conditions x 1 query


# --- _record_cell: executed-vs-blocked tool-call projection (tempdoc 675
# finding 3 -- the SDK reports a *blocked* disallowed-tool attempt too, so
# only ACTUALLY EXECUTED tools may feed the leak/disallowed assertions). ---

def test_record_cell_splits_executed_from_blocked_tool_calls():
    state = _state()
    got = {
        "attempts": {
            "t1": {"tool": "Read", "input": {"file_path": "/corpus/doc1.txt"}},
            "t2": {"tool": "Bash", "input": {"command": "ls"}},   # errored result -> blocked
            "t3": {"tool": "WebSearch", "input": {"query": "x"}},  # no result at all -> blocked
        },
        "results": {
            "t1": {"is_error": False},
            "t2": {"is_error": True},
        },
        "texts": ["the answer"],
        "rmsg": _rmsg(),
        "mcp_servers": None,
        "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), None)

    assert state.metadata["tool_calls"] == [
        {"tool": "Read", "input": {"file_path": "/corpus/doc1.txt"}},
    ]
    blocked_tools = {tc["tool"] for tc in state.metadata["tool_calls_blocked"]}
    assert blocked_tools == {"Bash", "WebSearch"}


def test_record_cell_treats_permission_denied_tool_as_blocked_not_executed():
    """A tool_use whose name IS in `rmsg.permission_denials` counts as blocked
    even if a (spurious) non-error result entry exists for it.

    Uses the REAL SDK dict shape for `permission_denials`
    (`{"tool_name", "tool_use_id", "tool_input"}`) — a prior version of this test
    used bare strings, which cannot occur from the live SDK and masked a
    `set([{...}])` TypeError crash in `_record_cell` (tempdoc 675 review finding)."""
    state = _state()
    got = {
        "attempts": {"t1": {"tool": "WebFetch", "input": {"url": "http://x"}}},
        "results": {"t1": {"is_error": False}},
        "texts": [],
        "rmsg": _rmsg(permission_denials=[
            {"tool_name": "WebFetch", "tool_use_id": "t1", "tool_input": {"url": "http://x"}}]),
        "mcp_servers": None,
        "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), None)

    assert state.metadata["tool_calls"] == []
    assert [tc["tool"] for tc in state.metadata["tool_calls_blocked"]] == ["WebFetch"]


def test_record_cell_disallowed_and_leak_suspect_computed_over_executed_only():
    """A blocked disallowed/leak-suspect tool must NOT be flagged; an executed
    one IS flagged -- reuses the real `find_disallowed_tool_calls` /
    `find_leak_suspect_tool_calls` helpers, not reimplemented logic."""
    state = _state()
    disallowed = build_disallowed_tools("C")  # Bash is disallowed under C
    got = {
        "attempts": {
            "t1": {"tool": "Bash", "input": {"command": "cat /eval/queries.json"}},  # executes
            "t2": {"tool": "Bash", "input": {"command": "cat /corpus/secret"}},       # blocked
            "t3": {"tool": "Read", "input": {"file_path": "/eval/queries.json"}},     # executes, leak-suspect
        },
        "results": {
            "t1": {"is_error": False},
            "t3": {"is_error": False},
            # t2 has no result entry at all -> blocked.
        },
        "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": None,
        "justsearch_tools": [],
    }

    aui._record_cell(state, got, "C", disallowed, None)

    executed = state.metadata["tool_calls"]
    assert {tc["tool"] for tc in executed} == {"Bash", "Read"}

    # Sanity: the projection matches calling the real helpers directly on the
    # executed list (i.e. _record_cell isn't reimplementing this logic).
    assert state.metadata["disallowed_tool_calls"] == find_disallowed_tool_calls(executed, disallowed)
    assert state.metadata["leak_suspect_tool_calls"] == find_leak_suspect_tool_calls(executed)

    # The blocked Bash call must not leak into either assertion list (Read is also
    # disallowed under C, so both executed calls are flagged -- only check the
    # Bash entries specifically).
    disallowed_bash_commands = [
        tc["input"]["command"] for tc in state.metadata["disallowed_tool_calls"]
        if tc["tool"] == "Bash"
    ]
    assert disallowed_bash_commands == ["cat /eval/queries.json"]  # the EXECUTED Bash call only


def test_record_cell_stashes_cost_turns_and_tokens_from_rmsg():
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": ["ok"],
        "rmsg": _rmsg(total_cost_usd=0.1234, num_turns=5,
                       usage={"cache_creation_input_tokens": 77}, result="ok"),
        "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), None)

    assert state.metadata["cost_usd"] == 0.1234
    assert state.metadata["num_turns"] == 5
    assert state.metadata["unique_tokens"] == 77
    assert state.output.completion == "ok"
    assert "error" not in state.metadata


def test_record_cell_stashes_raw_usage_and_resolved_provider_model():
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": ["ok"],
        "resolved_models": {"claude-haiku-4-5-20251001"},
        "rmsg": _rmsg(
            usage={"input_tokens": 11, "cache_creation_input_tokens": 7},
            model_usage={"claude-haiku-4-5-20251001": {"inputTokens": 18}},
        ),
        "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), None)

    assert state.metadata["resolved_model"] == "claude-haiku-4-5-20251001"
    assert state.metadata["usage"]["input_tokens"] == 11
    assert state.metadata["model_usage"]["claude-haiku-4-5-20251001"]["inputTokens"] == 18
    assert "error" not in state.metadata


def test_source_identity_resume_rejects_git_drift(tmp_path, monkeypatch):
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    (corpus / "doc.txt").write_text("stable corpus", encoding="utf-8")
    logs = tmp_path / "logs"

    import jseval.manifest as manifest
    monkeypatch.setattr(manifest, "_git_sha_full", lambda: "a" * 40)
    first = aui._capture_or_load_source_identity(
        log_dir=str(logs), corpus_dir=str(corpus), corpus_dataset="fixture",
        declared_corpus_signature="", search_config_cohort_key="search-1",
    )
    monkeypatch.setattr(manifest, "_git_sha_full", lambda: "b" * 40)
    with pytest.raises(ValueError, match="source_git_sha"):
        aui._capture_or_load_source_identity(
            log_dir=str(logs), corpus_dir=str(corpus), corpus_dataset="fixture",
            declared_corpus_signature="", search_config_cohort_key="search-1",
        )

    assert first["source_git_sha"] == "a" * 40
    assert first["corpus"]["signature"] is not None


def test_source_identity_hashes_untracked_files_but_excludes_its_log_dir(tmp_path, monkeypatch):
    repo = tmp_path / "repo"
    repo.mkdir()
    subprocess.run(["git", "init", "-q"], cwd=repo, check=True)
    subprocess.run(["git", "config", "user.email", "fixture@example.test"], cwd=repo, check=True)
    subprocess.run(["git", "config", "user.name", "Fixture"], cwd=repo, check=True)
    (repo / "tracked.txt").write_text("tracked", encoding="utf-8")
    subprocess.run(["git", "add", "tracked.txt"], cwd=repo, check=True)
    subprocess.run(["git", "commit", "-qm", "fixture"], cwd=repo, check=True)
    corpus = tmp_path / "corpus-outside-repo"
    corpus.mkdir()
    (corpus / "doc.txt").write_text("corpus", encoding="utf-8")
    logs = repo / "logs"
    monkeypatch.chdir(repo)

    kwargs = dict(
        log_dir=str(logs), corpus_dir=str(corpus), corpus_dataset="fixture",
        declared_corpus_signature="", search_config_cohort_key="search-1",
    )
    first = aui._capture_or_load_source_identity(**kwargs)
    assert first["source_git_state"]["dirty"] is False
    # The sidecar created inside logs is run output and must not poison resume.
    assert aui._capture_or_load_source_identity(**kwargs) == first

    (repo / "new-source.txt").write_text("untracked source", encoding="utf-8")
    with pytest.raises(ValueError, match="source_git_dirty|source_git_state"):
        aui._capture_or_load_source_identity(**kwargs)


def test_source_identity_resume_rejects_changed_corpus_and_query_bytes(tmp_path, monkeypatch):
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    (corpus / "doc.txt").write_text("stable corpus", encoding="utf-8")
    queries = tmp_path / "queries.json"
    queries.write_text('[{"query":"q","answer":"a"}]', encoding="utf-8")
    logs = tmp_path / "logs"

    import jseval.manifest as manifest
    monkeypatch.setattr(manifest, "_git_sha_full", lambda: "a" * 40)
    monkeypatch.setattr(aui, "_git_source_state", lambda **_: {
        "tracked_diff_sha256": "0" * 64,
        "untracked_sha256": "0" * 64,
        "untracked_count": 0,
        "dirty": False,
    })
    kwargs = dict(
        log_dir=str(logs), corpus_dir=str(corpus), corpus_dataset="fixture",
        declared_corpus_signature="", search_config_cohort_key="search-1",
        queries_path=str(queries), conditions=("A", "B"), seeds=2,
        mcp_tool_surface=[{
            "name": "mcp__justsearch__search", "description": "Search",
            "input_schema": {"type": "object"},
        }],
    )
    captured = aui._capture_or_load_source_identity(**kwargs)
    assert len(captured["campaign"]["expected_cells"]) == 4

    changed_surface = dict(kwargs)
    changed_surface["mcp_tool_surface"] = [{
        "name": "mcp__justsearch__search", "description": "Search",
        "input_schema": {"type": "object", "required": ["q"]},
    }]
    with pytest.raises(ValueError, match="mcp_tool_surface"):
        aui._capture_or_load_source_identity(**changed_surface)

    (corpus / "doc.txt").write_text("changed corpus", encoding="utf-8")
    with pytest.raises(ValueError, match="corpus"):
        aui._capture_or_load_source_identity(**kwargs)

    (corpus / "doc.txt").write_text("stable corpus", encoding="utf-8")
    queries.write_text('[{"query":"changed","answer":"a"}]', encoding="utf-8")
    with pytest.raises(ValueError, match="queries"):
        aui._capture_or_load_source_identity(**kwargs)


def test_source_identity_captures_and_rechecks_corpus_certification(tmp_path, monkeypatch):
    from jseval.corpus_certify import SCIENTIFIC_GATES
    from jseval.corpus_identity import corpus_signature
    from tests.test_corpus_inject import _complete_certificate, _gate_evidence

    corpus = tmp_path / "corpus"
    corpus.mkdir()
    (corpus / "doc.txt").write_text("stable corpus", encoding="utf-8")
    signature = corpus_signature(corpus, [corpus / "doc.txt"])
    queries = tmp_path / "queries.json"
    queries.write_text('[{"query":"q","answer":"a"}]', encoding="utf-8")
    query_gold_sha256 = hashlib.sha256(queries.read_bytes()).hexdigest()
    gate_rows = {
        gate: _gate_evidence(
            gate, dataset="fixture", signature=signature,
            query_gold_sha256=query_gold_sha256, query_count=1,
        )
        for gate in SCIENTIFIC_GATES
    }
    certification = tmp_path / "certification.json"
    certification.write_text(json.dumps(_complete_certificate(
        "fixture-member", "fixture", signature, gate_rows, query_count=1,
        query_gold_sha256=query_gold_sha256,
    )), encoding="utf-8")
    logs = tmp_path / "logs"

    import jseval.manifest as manifest
    monkeypatch.setattr(manifest, "_git_sha_full", lambda: "a" * 40)
    monkeypatch.setattr(aui, "_git_source_state", lambda **_: {
        "tracked_diff_sha256": "0" * 64,
        "untracked_sha256": "0" * 64,
        "untracked_count": 0,
        "dirty": False,
    })
    kwargs = dict(
        log_dir=str(logs), corpus_dir=str(corpus), corpus_dataset="fixture",
        declared_corpus_signature=signature, search_config_cohort_key="search-1",
        corpus_certification=str(certification),
        queries_path=str(queries), conditions=("A", "B"), seeds=1,
    )
    first = aui._capture_or_load_source_identity(**kwargs)
    assert first["corpus_certification"]["fully_certified"] is True

    changed = json.loads(certification.read_text(encoding="utf-8"))
    changed["datasets"]["1000"]["verbose"]["scientific_gates"][
        "closed_book"
    ]["threshold"] = {"maximum": 0.01}
    certification.write_text(json.dumps(changed), encoding="utf-8")
    with pytest.raises(ValueError, match="corpus_certification"):
        aui._capture_or_load_source_identity(**kwargs)


def test_source_identity_resume_recomputes_safe_environment(tmp_path, monkeypatch):
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    (corpus / "doc.txt").write_text("stable", encoding="utf-8")
    queries = tmp_path / "queries.json"
    queries.write_text('[{"query":"q","answer":"a"}]', encoding="utf-8")
    monkeypatch.setattr(aui, "_git_source_state", lambda **_: {
        "tracked_diff_sha256": "0" * 64, "untracked_sha256": "0" * 64,
        "untracked_count": 0, "dirty": False,
    })
    import jseval.env_fingerprint as env
    monkeypatch.setattr(env, "safe_environment_identity", lambda: {
        "platform": {"system": "one"}, "gpu": {"available": False},
    })
    kwargs = dict(
        log_dir=str(tmp_path / "logs"), corpus_dir=str(corpus),
        corpus_dataset="fixture", declared_corpus_signature="",
        search_config_cohort_key="search", queries_path=str(queries),
        conditions=("A", "B"), seeds=1, mcp_tool_surface=[],
    )
    aui._capture_or_load_source_identity(**kwargs)
    monkeypatch.setattr(env, "safe_environment_identity", lambda: {
        "platform": {"system": "two"}, "gpu": {"available": False},
    })
    with pytest.raises(ValueError, match="environment"):
        aui._capture_or_load_source_identity(**kwargs)


def test_record_cell_result_error_sets_error_and_stops():
    state = _state()
    got = {
        "attempts": {"t1": {"tool": "Read", "input": {"file_path": "/corpus/doc1.txt"}}},
        "results": {"t1": {"is_error": False}},
        "texts": [],
        "rmsg": _rmsg(is_error=True, result="budget exceeded"),
        "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), None)

    # Credibility bar (tempdoc 624 sec M.8 item 2, preserved under v2): tool
    # calls are stashed UNCONDITIONALLY, before the error short-circuit.
    assert state.metadata["tool_calls"] == [
        {"tool": "Read", "input": {"file_path": "/corpus/doc1.txt"}},
    ]
    assert "budget exceeded" in state.metadata["error"]
    assert "cost_usd" not in state.metadata  # short-circuited before usage stash


def test_record_cell_no_result_message_sets_error():
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": None, "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), None)

    assert "no ResultMessage" in state.metadata["error"]


# --- _record_cell: offered MCP tool-surface tri-state (tempdoc 675 finding 2
# -- replaces the CLI init-event parse with the SDK's own `get_mcp_status()`
# result, already captured into `got["mcp_servers"]`/`got["justsearch_tools"]`
# by the solver before `_record_cell` runs). ---

def test_capture_canonical_mcp_surface_keeps_full_tools_list(tmp_path, monkeypatch):
    config = tmp_path / "mcp.json"
    config.write_text(json.dumps({
        "mcpServers": {"justsearch": {"type": "http", "url": "http://localhost/mcp"}},
    }), encoding="utf-8")
    payload = {"jsonrpc": "2.0", "id": "x", "result": {"tools": [{
        "name": "search_query", "description": "Search documents",
        "inputSchema": {"type": "object", "properties": {"q": {"type": "string"}}},
        "outputSchema": {"type": "object", "required": ["hits"]},
        "annotations": {"readOnlyHint": True},
    }]}}

    class Response:
        def __enter__(self):
            return self
        def __exit__(self, *_):
            return False
        def read(self):
            return json.dumps(payload).encode("utf-8")

    monkeypatch.setattr(aui, "urlopen", lambda request, timeout: Response())
    assert aui._capture_canonical_mcp_surface(str(config)) == [{
        "name": "mcp__justsearch__search_query",
        "description": "Search documents",
        "inputSchema": {"type": "object", "properties": {"q": {"type": "string"}}},
        "outputSchema": {"type": "object", "required": ["hits"]},
        "annotations": {"readOnlyHint": True},
    }]

def test_record_cell_condition_a_exempt_from_surface_assertion():
    """Condition A never carries a real mcp_config -- and even if a caller
    passed one through, A must not be held to the with-tool surface assertion."""
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "A", build_disallowed_tools("A"), "/mcp.json")

    assert "error" not in state.metadata
    assert "mcp_surface_unverified" not in state.metadata


def test_record_cell_condition_b_unverified_when_status_unavailable():
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": None, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "B", build_disallowed_tools("B"), "/mcp.json")

    assert state.metadata["mcp_surface_unverified"] is True
    assert "error" not in state.metadata


def test_record_cell_condition_b_healthy_surface_no_error():
    state = _state()
    servers = [{"name": "justsearch", "status": "connected",
                "tools": [{"name": "search_query"}]}]
    justsearch_tools = ["mcp__justsearch__search_query"]
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": servers, "justsearch_tools": justsearch_tools,
    }

    aui._record_cell(state, got, "B", build_disallowed_tools("B"), "/mcp.json")

    assert state.metadata["mcp_tools_offered"] == len(justsearch_tools)
    assert state.metadata["mcp_tool_names_offered"] == justsearch_tools
    from jseval.agent_manifest import mcp_tool_surface_hash
    expected = mcp_tool_surface_hash([{
        "name": justsearch_tools[0], "description": None, "input_schema": None,
    }])
    assert state.metadata["observed_mcp_tool_surface_hash"] == expected
    assert "error" not in state.metadata
    assert "mcp_surface_unverified" not in state.metadata


def test_record_cell_surface_hash_includes_schema_and_rejects_declared_mismatch():
    """Same-name tools with different schemas are different source identities."""
    def capture(schema, declared=None):
        state = _state()
        got = {
            "attempts": {}, "results": {}, "texts": [], "rmsg": _rmsg(),
            "mcp_servers": [{
                "name": "justsearch", "status": "connected",
                "tools": [{
                    "name": "search_query", "description": "Search",
                    "inputSchema": schema,
                }],
            }],
            "justsearch_tools": ["mcp__justsearch__search_query"],
        }
        aui._record_cell(
            state, got, "B", build_disallowed_tools("B"), "/mcp.json", declared)
        return state

    string_surface = capture({"type": "object", "properties": {"q": {"type": "string"}}})
    integer_surface = capture({"type": "object", "properties": {"q": {"type": "integer"}}})
    assert (string_surface.metadata["observed_mcp_tool_surface_hash"]
            != integer_surface.metadata["observed_mcp_tool_surface_hash"])

    mismatch = capture({"type": "object"}, declared="0" * 64)
    assert "declared MCP tool-surface hash disagrees" in mismatch.metadata["error"]


def test_record_cell_condition_b_connected_but_no_tools_errors_and_deferred():
    """Server connected (unlike a genuinely dead config) but no mcp__justsearch
    tool was offered -- the ToolSearch-deferred surfacing shape (tempdoc 624
    Step 0 item 4, preserved under v2)."""
    state = _state()
    servers = [{"name": "justsearch", "status": "connected"}]
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": servers, "justsearch_tools": [],
    }

    aui._record_cell(state, got, "B", build_disallowed_tools("B"), "/mcp.json")

    assert "expected MCP tool surface not offered" in state.metadata["error"]
    assert state.metadata["mcp_tools_deferred"] is True


def test_record_cell_condition_b_dead_config_not_misread_as_deferred():
    """The server never connected at all -- must NOT be misread as 'deferred'."""
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": [], "justsearch_tools": [],
    }

    aui._record_cell(state, got, "B", build_disallowed_tools("B"), "/mcp.json")

    assert "expected MCP tool surface not offered" in state.metadata["error"]
    assert state.metadata["mcp_tools_deferred"] is False


def test_record_cell_with_tool_condition_without_mcp_config_is_exempt():
    """B/C with mcp_config=None (no real config in play, e.g. a negative
    control) must not be held to the offered-surface assertion."""
    state = _state()
    got = {
        "attempts": {}, "results": {}, "texts": [],
        "rmsg": _rmsg(),
        "mcp_servers": [], "justsearch_tools": [],
    }

    aui._record_cell(state, got, "B", build_disallowed_tools("B"), None)

    assert "error" not in state.metadata


# --- run_utility_eval: the corpus-dir isolation fix, cleanup-on-raise, the
# watched-roots safety gate, and the no-mcp gate skip. run_utility_eval now
# builds exactly ONE task for the whole matrix (tempdoc 675 single pool), so
# `eval_set` is called WITHOUT `max_tasks=1`. ---

def test_run_utility_eval_builds_one_task_with_staged_not_original_corpus_dir(tmp_path, monkeypatch):
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

    task_calls = []

    def fake_agent_utility_task(*, corpus_dir, conditions, **kwargs):
        task_calls.append({"corpus_dir": corpus_dir, "conditions": conditions})
        # capture the staged dir's parent listing NOW -- before run_utility_eval's
        # cleanup (which fires once the mocked eval_set below returns) removes it.
        task_calls[-1]["staged_parent_listing"] = sorted(os.listdir(Path(corpus_dir).parent))
        return object()  # eval_set is mocked too, so any placeholder Task works

    eval_set_calls = []
    monkeypatch.setattr(aui, "agent_utility_task", fake_agent_utility_task)
    monkeypatch.setattr(inspect_ai, "eval_set",
                         lambda tasks, **k: eval_set_calls.append((tasks, k)))

    original_corpus_dir = str(corpus_dir)
    aui.run_utility_eval(
        queries_path=str(queries_for_eval), corpus_dir=original_corpus_dir, mcp_config=None,
        conditions=("A", "C"), seeds=1, concurrency=1, log_dir=str(tmp_path / "logs"),
    )

    # ONE task built for the whole matrix (tempdoc 675) -- not one per condition.
    assert len(task_calls) == 1
    call = task_calls[0]
    assert call["conditions"] == ("A", "C")
    used_corpus_dir = call["corpus_dir"]
    assert used_corpus_dir != original_corpus_dir
    assert Path(used_corpus_dir).name == "corpus-dir"
    assert call["staged_parent_listing"] == ["corpus-dir"]  # no queries.json sibling

    # the staging directory is a temp artifact -- must be cleaned up after the run
    assert not Path(used_corpus_dir).parent.exists()

    # ONE task handed to eval_set, and no per-condition max_tasks=1 serialization.
    assert len(eval_set_calls) == 1
    tasks_arg, kwargs = eval_set_calls[0]
    assert len(tasks_arg) == 1
    assert "max_tasks" not in kwargs


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

    def spying_stage_corpus_dir(cdir, **kwargs):
        staged = real_stage(cdir, **kwargs)
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

    # "type":"http" is mandatory (McpConfigMissingTypeError) -- these tests exercise
    # the watched-roots gate specifically, so the config must pass the earlier
    # dead-config fail-fast check to reach it.
    mcp_config = tmp_path / "mcp.json"
    mcp_config.write_text(
        '{"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:56300/mcp"}}}',
        encoding="utf-8")

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

    # "type":"http" is mandatory (McpConfigMissingTypeError) -- these tests exercise
    # the watched-roots gate specifically, so the config must pass the earlier
    # dead-config fail-fast check to reach it.
    mcp_config = tmp_path / "mcp.json"
    mcp_config.write_text(
        '{"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:56300/mcp"}}}',
        encoding="utf-8")

    called = {"eval_set": False}
    monkeypatch.setattr(aui, "agent_utility_task", lambda **kw: object())
    monkeypatch.setattr(inspect_ai, "eval_set",
                         lambda *a, **k: called.__setitem__("eval_set", True))
    monkeypatch.setattr(aui, "_capture_canonical_mcp_surface", lambda _: [{
        "name": "mcp__justsearch__search", "description": "search",
        "input_schema": {"type": "object"},
    }])

    with patch("jseval.utility_calibrate.httpx.Client") as MockClient:
        _mock_roots_client(MockClient, [str(corpus_dir)])

        aui.run_utility_eval(
            queries_path=str(queries_for_eval), corpus_dir=str(corpus_dir),
            mcp_config=str(mcp_config), conditions=("A", "C"), seeds=1, concurrency=1,
            log_dir=str(tmp_path / "logs"),
        )

    assert called["eval_set"] is True


def test_run_utility_eval_rejects_mcp_schema_drift_during_campaign(tmp_path, monkeypatch):
    from unittest.mock import patch

    corpus_dir = tmp_path / "dataset" / "corpus-dir"
    corpus_dir.mkdir(parents=True)
    (corpus_dir / "doc1.txt").write_text("hello world", encoding="utf-8")
    queries = tmp_path / "queries.json"
    queries.write_text('[{"query":"q","answer":"a"}]', encoding="utf-8")
    config = tmp_path / "mcp.json"
    config.write_text(
        '{"mcpServers":{"justsearch":{"type":"http","url":"http://127.0.0.1:56300/mcp"}}}',
        encoding="utf-8",
    )
    surfaces = iter([
        [{"name": "mcp__justsearch__search", "inputSchema": {"type": "object"},
          "outputSchema": {"type": "string"}}],
        [{"name": "mcp__justsearch__search", "inputSchema": {"type": "object"},
          "outputSchema": {"type": "integer"}}],
    ])
    monkeypatch.setattr(aui, "_capture_canonical_mcp_surface", lambda _: next(surfaces))
    monkeypatch.setattr(aui, "agent_utility_task", lambda **kw: object())
    monkeypatch.setattr(inspect_ai, "eval_set", lambda *a, **k: None)

    with patch("jseval.utility_calibrate.httpx.Client") as client:
        _mock_roots_client(client, [str(corpus_dir)])
        with pytest.raises(RuntimeError, match="tools/list changed"):
            aui.run_utility_eval(
                queries_path=str(queries), corpus_dir=str(corpus_dir),
                mcp_config=str(config), conditions=("A", "B"), seeds=1,
                concurrency=1, log_dir=str(tmp_path / "logs"),
            )


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


def test_record_cell_preserves_partial_tool_calls_and_does_not_clobber_timeout_error():
    """F2 (tempdoc 675 review): a timed-out cell — `solve()` pre-set the timeout error and the
    SDK loop was cancelled so there is NO ResultMessage — must STILL record its partial
    `tool_calls`, and `_record_cell` must NOT overwrite the timeout error with 'no ResultMessage'.
    Regression for 'timed-out cells lose their partial evidence', the failure v2 exists to fix."""
    state = _state()
    state.metadata["error"] = "per-cell wall-clock budget exhausted"  # solve() set this on timeout
    capture = {
        "attempts": {"t1": {"tool": "Grep", "input": {"pattern": "x"}}},
        "results": {"t1": {"is_error": False}},
        "texts": [], "rmsg": None, "mcp_servers": None, "justsearch_tools": [],
    }
    aui._record_cell(state, capture, "A", build_disallowed_tools("A"), None)
    assert [tc["tool"] for tc in state.metadata["tool_calls"]] == ["Grep"]  # partial evidence preserved
    assert state.metadata["error"] == "per-cell wall-clock budget exhausted"  # not clobbered


def test_agent_utility_task_rejects_a_float_identity_arg(tmp_path):
    """Regression guard (tempdoc 675 F0 follow-up): a float anywhere in
    `agent_utility_task`'s bound args must raise, not silently ship — Inspect's JSON
    recorder reads a persisted float back as Decimal, which breaks eval_set resume
    (the root cause of F0). Converts the prior prose-only docstring warning into an
    enforced check."""
    queries_path = tmp_path / "queries.json"
    queries_path.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t1"}]), encoding="utf-8")
    with pytest.raises(AssertionError, match="float"):
        aui.agent_utility_task(
            conditions=("A",), queries_path=str(queries_path), corpus_dir=str(tmp_path),
            mcp_config=None, model="haiku", max_budget=0.5,  # float, not the required str
            corpus_dataset="ds", corpus_signature="sig",
        )


def test_agent_utility_task_happy_path_has_no_float_args(tmp_path):
    """Sibling to the raise-on-float test above: the real call shape (all current
    default/str/int/None/tuple args) must NOT trip the guard."""
    queries_path = tmp_path / "queries.json"
    queries_path.write_text(
        json.dumps([{"query": "q1", "answer": "a1", "question_type": "t1"}]), encoding="utf-8")
    t = aui.agent_utility_task(
        conditions=("A",), queries_path=str(queries_path), corpus_dir=str(tmp_path),
        mcp_config=None, model="haiku", corpus_dataset="ds", corpus_signature="sig",
    )
    assert isinstance(t, Task)


def test_run_utility_eval_resumes_on_rerun_same_log_dir(tmp_path, monkeypatch):
    """F0 (tempdoc 675 review): re-invoking `run_utility_eval` with the same `log_dir` RESUMES
    (skips the completed sample) instead of raising Inspect's `PrerequisiteError`. Regression for
    the task-identity drift (a float `max_budget` read back as Decimal + a random staged-corpus
    path). Uses a trivial instant solver — no real agent/backend. This test FAILS pre-fix."""
    from inspect_ai.solver import solver as _solver_dec

    corpus = tmp_path / "corpus-dir"
    corpus.mkdir()
    (corpus / "d.txt").write_text("hello", encoding="utf-8")
    queries = tmp_path / "q.json"
    queries.write_text(
        json.dumps([{"query": "q", "answer": "a", "question_type": "t"}]), encoding="utf-8")
    log_dir = str(tmp_path / "logs")
    calls = {"n": 0}

    @_solver_dec
    def trivial(*a, **k):
        async def solve(state, generate):
            calls["n"] += 1
            state.output.completion = "a"
            state.metadata.update({"cost_usd": 0.0, "unique_tokens": 0, "num_turns": 1})
            return state
        return solve

    monkeypatch.setattr(aui, "claude_agent_solver", trivial)
    kw = dict(queries_path=str(queries), corpus_dir=str(corpus), mcp_config=None, model="haiku",
              conditions=("A",), seeds=1, concurrency=1, log_dir=log_dir, max_queries=1,
              corpus_dataset="d", corpus_signature="s")
    aui.run_utility_eval(**kw)
    assert calls["n"] == 1
    aui.run_utility_eval(**kw)  # RESUME: must not raise, must not re-run the completed cell
    assert calls["n"] == 1


def test_run_utility_eval_resumes_a_multi_sample_full_completion(tmp_path, monkeypatch):
    """Strengthens the single-sample resume test above to N=3 samples across 2 conditions
    (tempdoc 675 review follow-up): proves resume isn't a degenerate single-sample-only
    behavior — a full N-sample completion, re-invoked with IDENTICAL args, must not
    re-run ANY of the N cells. (A genuinely partial/interrupted-mid-run resume needs an
    actual process kill to be faithful — covered by a live, non-unit-test check per the
    675 tempdoc's §Unverified assumptions, not simulated here: eval_set's retry/
    fail-on-error semantics for an in-process raised exception are a different, untested
    code path and changing `max_queries` between invocations would change task identity,
    defeating resume entirely — neither is a safe basis for a deterministic unit test.)"""
    from inspect_ai.solver import solver as _solver_dec

    corpus = tmp_path / "corpus-dir"
    corpus.mkdir()
    (corpus / "d.txt").write_text("hello", encoding="utf-8")
    queries = tmp_path / "q.json"
    queries.write_text(json.dumps([
        {"query": f"q{i}", "answer": f"a{i}", "question_type": "t"} for i in range(3)
    ]), encoding="utf-8")
    log_dir = str(tmp_path / "logs")
    calls = {"n": 0}

    @_solver_dec
    def trivial(*a, **k):
        async def solve(state, generate):
            calls["n"] += 1
            state.output.completion = "a"
            state.metadata.update({"cost_usd": 0.0, "unique_tokens": 0, "num_turns": 1})
            return state
        return solve

    monkeypatch.setattr(aui, "claude_agent_solver", trivial)
    kw = dict(queries_path=str(queries), corpus_dir=str(corpus), mcp_config=None, model="haiku",
              conditions=("A", "C"), seeds=1, concurrency=2, log_dir=log_dir, max_queries=3,
              corpus_dataset="d", corpus_signature="s")
    aui.run_utility_eval(**kw)
    assert calls["n"] == 6  # 3 queries x 2 conditions, all completed
    aui.run_utility_eval(**kw)  # RESUME: none of the 6 completed cells re-run
    assert calls["n"] == 6
