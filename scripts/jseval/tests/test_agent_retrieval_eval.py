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
from unittest.mock import MagicMock

from jseval.agent_retrieval_eval import (
    AgentResult,
    _score_answer,
    build_disallowed_tools,
    find_disallowed_tool_calls,
    find_leak_suspect_tool_calls,
    rag_reachability_probe,
    stage_corpus_dir,
)
from jseval.chunk_completeness import CHUNK_THRESHOLD_CHARS


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


# --- rag_reachability_probe: the retrieval-completeness invariant guard (tempdoc 749).
#
# ChunkDocumentWriter writes ZERO chunk documents for docs < CHUNK_THRESHOLD_CHARS, so RAG
# chunk retrieval (which filters IS_CHUNK:true) is structurally blind to them unless the
# doc-level-union fix is actually wired into the primary retrieval path. This probe samples
# the shortest chunkless-by-construction docs from a BEIR-format corpus.jsonl and asserts each
# is still reachable via an UNSCOPED retrieve-context call (no `doc_ids` -- the backend keys
# docs by ingest path, not the corpus id, so scoping can't be expressed here), fail-closed on
# regression.

def _write_corpus(tmp_path: Path, docs: list[dict]) -> Path:
    p = tmp_path / "corpus.jsonl"
    with p.open("w", encoding="utf-8") as f:
        for d in docs:
            f.write(json.dumps(d) + "\n")
    return p


def _mock_response(*, mode: str, chunks: list[dict]):
    resp = MagicMock()
    resp.raise_for_status = MagicMock()
    resp.json.return_value = {
        "quality": {"retrieval_mode": mode},
        "chunks": chunks,
        "context": "",
    }
    return resp


class TestRagReachabilityProbeSampling:
    def test_boundary_doc_at_exactly_threshold_is_not_sampled(self, tmp_path):
        """A doc whose content is exactly CHUNK_THRESHOLD_CHARS long DOES get chunked by
        ChunkDocumentWriter (its guard is `< CHUNK_THRESHOLD_CHARS`), so it must not be
        treated as chunkless-by-construction here either."""
        p = _write_corpus(tmp_path, [
            {"_id": "at-threshold", "title": "", "text": "x" * CHUNK_THRESHOLD_CHARS},
        ])
        mock_client = MagicMock()

        result = rag_reachability_probe(p, mock_client, n=10, top_k=5)

        assert result == {"sampled": 0, "passed": 0, "failed": [], "verdict": "not-applicable"}
        mock_client.post.assert_not_called()

    def test_sampling_is_deterministic_ascending_length_then_doc_id(self, tmp_path):
        # The probe is unscoped (no `doc_ids` in the request, see
        # test_request_body_is_unscoped_no_doc_ids below), so this test can no longer read the
        # sampled doc id back off the request body. Each doc gets a distinct (short, so it
        # doesn't perturb the length-based sample ordering -- `content` is `title + "\n\n" +
        # text`) title instead -- the title becomes the `query` sent, which lets the
        # side_effect (and the assertion) identify which doc a given call was for.
        p = _write_corpus(tmp_path, [
            {"_id": "d3", "title": "T3", "text": "x" * 1500},
            {"_id": "d2", "title": "T2", "text": "x" * 500},
            {"_id": "d1", "title": "T1", "text": "x" * 500},  # ties d2 on length -> d1 first
            {"_id": "d4", "title": "T4", "text": "x" * CHUNK_THRESHOLD_CHARS},  # not chunkless
        ])
        mock_client = MagicMock()
        title_to_id = {"T1": "d1", "T2": "d2", "T3": "d3"}

        def _side_effect(url, json):
            doc_id = title_to_id[json["query"]]
            return _mock_response(
                mode="CHUNK_HYBRID", chunks=[{"parent_doc_id": f"/corpus/{doc_id}.txt"}],
            )

        mock_client.post.side_effect = _side_effect

        result = rag_reachability_probe(p, mock_client, n=10, top_k=5)

        called_queries = [c.kwargs["json"]["query"] for c in mock_client.post.call_args_list]
        assert called_queries == ["T1", "T2", "T3"]
        assert result["sampled"] == 3
        assert result["verdict"] == "ok"

    def test_sample_size_truncated_to_n(self, tmp_path):
        p = _write_corpus(tmp_path, [
            {"_id": f"d{i}", "title": "", "text": "x" * (100 + i)} for i in range(5)
        ])
        mock_client = MagicMock()
        mock_client.post.return_value = _mock_response(
            mode="CHUNK_HYBRID", chunks=[{"parent_doc_id": "/corpus/whichever.txt"}],
        )

        result = rag_reachability_probe(p, mock_client, n=2, top_k=5)

        assert result["sampled"] == 2
        assert mock_client.post.call_count == 2

    def test_request_body_is_unscoped_no_doc_ids(self, tmp_path):
        """Bug #1 regression (tempdoc 749): the probe used to POST `doc_ids: [d.doc_id]`,
        scoping the request to the corpus id. The backend keys retrieved docs by ingest PATH,
        not the corpus doc id, so that scoped a non-existent doc and retrieved nothing. The
        probe is now UNSCOPED -- the request body is exactly `{"query": ..., "top_k": ...}`,
        with no `doc_ids` key at all."""
        p = _write_corpus(tmp_path, [{"_id": "d1", "title": "T", "text": "x" * 50}])
        mock_client = MagicMock()
        mock_client.post.return_value = _mock_response(
            mode="CHUNK_HYBRID", chunks=[{"parent_doc_id": "/corpus/d1.txt"}],
        )

        rag_reachability_probe(p, mock_client, n=10, top_k=5)

        sent = mock_client.post.call_args.kwargs["json"]
        assert "doc_ids" not in sent
        assert sent == {"query": "T", "top_k": 5}

    def test_question_uses_title_when_present(self, tmp_path):
        p = _write_corpus(tmp_path, [
            {"_id": "d1", "title": "The FTX Trial", "text": "x" * 50},
        ])
        mock_client = MagicMock()
        mock_client.post.return_value = _mock_response(
            mode="CHUNK_HYBRID", chunks=[{"parent_doc_id": "/corpus/d1.txt"}],
        )

        rag_reachability_probe(p, mock_client, n=10, top_k=5)

        sent = mock_client.post.call_args.kwargs["json"]
        assert sent["query"] == "The FTX Trial"

    def test_question_falls_back_to_first_12_words_of_text_when_title_blank(self, tmp_path):
        words = [f"w{i}" for i in range(20)]
        p = _write_corpus(tmp_path, [
            {"_id": "d1", "title": "", "text": " ".join(words)},
        ])
        mock_client = MagicMock()
        mock_client.post.return_value = _mock_response(
            mode="CHUNK_HYBRID", chunks=[{"parent_doc_id": "/corpus/d1.txt"}],
        )

        rag_reachability_probe(p, mock_client, n=10, top_k=5)

        sent = mock_client.post.call_args.kwargs["json"]
        assert sent["query"] == " ".join(words[:12])


class TestRagReachabilityProbeVerdict:
    def test_pass_when_non_fallback_mode_and_matching_parent_doc_id_path(self, tmp_path):
        """Bug #2 regression (tempdoc 749): the probe used to check reachability via exact
        `c.get("parent_doc_id") == d.doc_id`. Real `parent_doc_id` values are full ingest
        paths (e.g. `/some/path/d1.txt`), never equal to the bare corpus id (`d1`) -- so the
        old exact-`==` code would treat this doc as UNREACHABLE and this test would FAIL
        against it. The fix uses `_doc_id_matches_title`, the same filename-stem matcher the
        Tier-1 metrics use, which resolves the path back to the bare id."""
        p = _write_corpus(tmp_path, [{"_id": "d1", "title": "T", "text": "x" * 50}])
        mock_client = MagicMock()
        mock_client.post.return_value = _mock_response(
            mode="CHUNK_HYBRID", chunks=[{"parent_doc_id": "/some/path/d1.txt"}],
        )

        result = rag_reachability_probe(p, mock_client, n=10, top_k=5)

        assert result == {"sampled": 1, "passed": 1, "failed": [], "verdict": "ok"}

    def test_fail_on_fulltext_fallback_even_with_matching_parent_doc_id(self, tmp_path):
        """The exact tempdoc 749 regression shape: chunk retrieval fell back to whole-doc
        BM25 for a chunkless doc. A matching parent_doc_id alone must not count as reachable
        when the response says the primary chunk path didn't find it."""
        p = _write_corpus(tmp_path, [{"_id": "d1", "title": "T", "text": "x" * 50}])
        mock_client = MagicMock()
        mock_client.post.return_value = _mock_response(
            mode="FULLTEXT_FALLBACK", chunks=[{"parent_doc_id": "/some/path/d1.txt"}],
        )

        result = rag_reachability_probe(p, mock_client, n=10, top_k=5)

        assert result == {"sampled": 1, "passed": 0, "failed": ["d1"], "verdict": "fail"}

    def test_fail_when_no_returned_chunk_matches_the_doc_id(self, tmp_path):
        p = _write_corpus(tmp_path, [{"_id": "d1", "title": "T", "text": "x" * 50}])
        mock_client = MagicMock()
        mock_client.post.return_value = _mock_response(
            mode="CHUNK_HYBRID", chunks=[{"parent_doc_id": "/some/other/path/some-other-doc.txt"}],
        )

        result = rag_reachability_probe(p, mock_client, n=10, top_k=5)

        assert result["verdict"] == "fail"
        assert result["failed"] == ["d1"]

    def test_not_applicable_on_all_long_corpus(self, tmp_path):
        long_text = "x" * (CHUNK_THRESHOLD_CHARS + 500)
        p = _write_corpus(tmp_path, [
            {"_id": "d1", "title": "", "text": long_text},
            {"_id": "d2", "title": "", "text": long_text},
        ])
        mock_client = MagicMock()

        result = rag_reachability_probe(p, mock_client, n=10, top_k=5)

        assert result == {"sampled": 0, "passed": 0, "failed": [], "verdict": "not-applicable"}
        mock_client.post.assert_not_called()

    def test_not_applicable_when_corpus_jsonl_missing(self, tmp_path):
        mock_client = MagicMock()

        result = rag_reachability_probe(
            tmp_path / "does-not-exist.jsonl", mock_client, n=10, top_k=5,
        )

        assert result["verdict"] == "not-applicable"
        mock_client.post.assert_not_called()

    def test_http_error_on_a_sampled_doc_counts_as_failed(self, tmp_path):
        p = _write_corpus(tmp_path, [{"_id": "d1", "title": "T", "text": "x" * 50}])
        mock_client = MagicMock()
        mock_client.post.side_effect = RuntimeError("connection refused")

        result = rag_reachability_probe(p, mock_client, n=10, top_k=5)

        assert result == {"sampled": 1, "passed": 0, "failed": ["d1"], "verdict": "fail"}

    def test_owns_and_closes_its_own_client_when_given_a_base_url_string(self, tmp_path, monkeypatch):
        p = _write_corpus(tmp_path, [{"_id": "d1", "title": "T", "text": "x" * 50}])
        mock_client = MagicMock()
        mock_client.post.return_value = _mock_response(
            mode="CHUNK_HYBRID", chunks=[{"parent_doc_id": "/corpus/d1.txt"}],
        )

        created_with = {}

        def _fake_client(*, base_url, timeout):
            created_with["base_url"] = base_url
            return mock_client

        monkeypatch.setattr("jseval.agent_retrieval_eval.httpx.Client", _fake_client)

        result = rag_reachability_probe(p, "http://127.0.0.1:33221", n=10, top_k=5)

        assert created_with["base_url"] == "http://127.0.0.1:33221"
        assert result["verdict"] == "ok"
        mock_client.close.assert_called_once()

    def test_does_not_close_a_caller_supplied_client(self, tmp_path):
        p = _write_corpus(tmp_path, [{"_id": "d1", "title": "T", "text": "x" * 50}])
        mock_client = MagicMock()
        mock_client.post.return_value = _mock_response(
            mode="CHUNK_HYBRID", chunks=[{"parent_doc_id": "/corpus/d1.txt"}],
        )

        rag_reachability_probe(p, mock_client, n=10, top_k=5)

        mock_client.close.assert_not_called()


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

