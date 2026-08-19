"""Tests for retriever.py — doc ID resolution and request building."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from jseval.retriever import (
    LEXICAL_PIPELINE,
    _build_request,
    _filename_to_doc_id,
    resolve_doc_id,
    retrieve,
)


# ---------------------------------------------------------------------------
# Doc ID resolution
# ---------------------------------------------------------------------------

class TestResolveDocId:
    def test_from_filename(self):
        hit = {"fields": {"filename": "5%2E1234.txt"}}
        assert resolve_doc_id(hit) == "5.1234"

    def test_from_filename_simple(self):
        hit = {"fields": {"filename": "abc.txt"}}
        assert resolve_doc_id(hit) == "abc"

    def test_from_path(self):
        hit = {"fields": {"path": "/docs/abc.txt"}}
        assert resolve_doc_id(hit) == "abc"

    def test_from_provenance_path(self):
        hit = {"fields": {}, "provenance": {"path": "/data/doc%20name.txt"}}
        assert resolve_doc_id(hit) == "doc name"

    def test_from_hit_id_file_backed_slash(self):
        hit = {"id": "docs/5%2E1234.txt"}
        assert resolve_doc_id(hit) == "5.1234"

    def test_from_hit_id_file_backed_txt(self):
        hit = {"id": "mydoc.txt"}
        assert resolve_doc_id(hit) == "mydoc"

    def test_from_hit_id_not_file_backed(self):
        hit = {"id": "abc123"}
        with pytest.raises(ValueError, match="Cannot resolve doc ID"):
            resolve_doc_id(hit)

    def test_priority_filename_over_path(self):
        hit = {
            "fields": {"filename": "correct.txt", "path": "/wrong/wrong.txt"},
        }
        assert resolve_doc_id(hit) == "correct"

    def test_priority_path_over_hit_id(self):
        hit = {
            "id": "wrong/wrong.txt",
            "fields": {"path": "/data/correct.txt"},
        }
        assert resolve_doc_id(hit) == "correct"

    def test_url_encoded_special_chars(self):
        hit = {"fields": {"filename": "hello%20world%21.txt"}}
        assert resolve_doc_id(hit) == "hello world!"

    def test_numeric_id_with_dots(self):
        hit = {"fields": {"filename": "10%2E1038%2Fnphys3025.txt"}}
        assert resolve_doc_id(hit) == "10.1038/nphys3025"

    def test_empty_fields(self):
        hit = {"id": "no-slash-no-txt", "fields": {}}
        with pytest.raises(ValueError):
            resolve_doc_id(hit)


class TestFilenameToDocId:
    def test_basic(self):
        assert _filename_to_doc_id("abc.txt") == "abc"

    def test_url_decode(self):
        assert _filename_to_doc_id("5%2E1234.txt") == "5.1234"

    def test_no_extension(self):
        assert _filename_to_doc_id("abc") == "abc"

    def test_case_normalized(self):
        """Windows filesystems are case-preserving but not case-authoritative; the resolved
        doc-id must lowercase so it matches `corpora.py:_read_qrels_tsv`'s lowercased qrel keys
        regardless of on-disk casing."""
        assert _filename_to_doc_id("MiXeDCaSe.TXT") == "mixedcase"


# ---------------------------------------------------------------------------
# Request building
# ---------------------------------------------------------------------------

class TestBuildRequest:
    def test_lexical_sends_pipeline(self):
        req = _build_request("query", "lexical", 10, False, None)
        assert "pipeline" in req
        assert req["pipeline"] == LEXICAL_PIPELINE
        assert "mode" not in req

    def test_hybrid_sends_mode(self):
        req = _build_request("query", "hybrid", 10, False, None)
        assert req["mode"] == "hybrid"
        assert "pipeline" not in req

    def test_vector_sends_pipeline(self):
        from jseval.retriever import VECTOR_PIPELINE
        req = _build_request("query", "vector", 5, False, None)
        assert "pipeline" in req
        assert req["pipeline"] == VECTOR_PIPELINE
        assert req["limit"] == 5

    def test_splade_sends_pipeline(self):
        from jseval.retriever import SPLADE_PIPELINE
        req = _build_request("query", "splade", 10, False, None)
        assert "pipeline" in req
        assert req["pipeline"] == SPLADE_PIPELINE

    def test_custom_modes_send_pipeline(self):
        from jseval.retriever import BM25_SPLADE_PIPELINE, DENSE_SPLADE_PIPELINE, FULL_PIPELINE
        for mode_name, expected_pipeline in [
            ("bm25_splade", BM25_SPLADE_PIPELINE),
            ("dense_splade", DENSE_SPLADE_PIPELINE),
            ("full", FULL_PIPELINE),
        ]:
            req = _build_request("query", mode_name, 10, False, None)
            assert "pipeline" in req, f"{mode_name} should send pipeline"
            assert req["pipeline"] == expected_pipeline

    def test_explicit_pipeline_overrides_mode(self):
        custom = {"sparseEnabled": True, "denseEnabled": True}
        req = _build_request("query", "hybrid", 10, False, custom)
        assert req["pipeline"] == custom
        assert "mode" not in req

    def test_debug_flag(self):
        req = _build_request("query", "hybrid", 10, True, None)
        assert req["debug"] is True

    def test_no_debug_by_default(self):
        req = _build_request("query", "hybrid", 10, False, None)
        assert "debug" not in req

    # -----------------------------------------------------------------------
    # Q-020 / F-046: query_syntax field threading
    # -----------------------------------------------------------------------

    def test_query_syntax_absent_by_default(self):
        """`query_syntax=None` (the default) sends no `querySyntax` field at all -- the
        wire request is byte-identical to every pre-Q-020 caller."""
        req = _build_request("query", "hybrid", 10, False, None)
        assert "querySyntax" not in req

    def test_query_syntax_omitted_when_not_passed_matches_default(self):
        """Not passing `query_syntax` at all (positional/keyword omission) behaves
        identically to passing `query_syntax=None` explicitly -- pins the default's
        zero-effect claim at the call-signature level, not just the value level."""
        with_default_arg = _build_request("query", "hybrid", 10, False, None, query_syntax=None)
        without_arg = _build_request("query", "hybrid", 10, False, None)
        assert with_default_arg == without_arg

    def test_query_syntax_simple_sent_explicitly(self):
        req = _build_request("query", "hybrid", 10, False, None, query_syntax="simple")
        assert req["querySyntax"] == "simple"

    def test_query_syntax_lucene_sent(self):
        req = _build_request("query", "hybrid", 10, False, None, query_syntax="lucene")
        assert req["querySyntax"] == "lucene"

    def test_query_syntax_independent_of_pipeline_mode(self):
        """querySyntax threads alongside a mode-derived pipeline body, not instead of it."""
        req = _build_request("query", "lexical", 10, False, None, query_syntax="lucene")
        assert req["pipeline"] == LEXICAL_PIPELINE
        assert req["querySyntax"] == "lucene"


# ---------------------------------------------------------------------------
# Retrieve (integration with mock HTTP)
# ---------------------------------------------------------------------------

def _mock_search_response(results):
    resp = MagicMock()
    resp.json.return_value = {
        "totalHits": len(results),
        "tookMs": 15,
        "results": results,
    }
    resp.raise_for_status = MagicMock()
    return resp


@patch("jseval.retriever.httpx.Client")
def test_retrieve_basic(MockClient):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)

    mock_client.post.return_value = _mock_search_response([
        {"id": "d1.txt", "score": 1.5, "fields": {"filename": "d1.txt"}},
        {"id": "d2.txt", "score": 0.8, "fields": {"filename": "d2.txt"}},
    ])

    scored, raw = retrieve(
        {"q1": "test query"},
        "http://localhost:8080",
        mode="hybrid",
    )

    assert len(scored) == 2
    assert scored[0].query_id == "q1"
    assert scored[0].doc_id == "d1"
    # Tempdoc 803: was `== 1.5`, the engine's own `hit["score"]`. That assertion was a faithful
    # description of the defect: the engine's score is the PRE-RERANK fusion score, so scoring
    # by it made ir_measures sort the delivered list back into fusion order. Score is now
    # derived from delivered rank (len(hits) - idx), which is what the engine actually returned.
    assert scored[0].score == 2.0
    assert len(raw) == 1


@patch("jseval.retriever.httpx.Client")
def test_retrieve_forwards_query_syntax_to_the_wire_request(MockClient):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)
    mock_client.post.return_value = _mock_search_response([])

    retrieve({"q1": "test query"}, "http://localhost:8080", mode="hybrid", query_syntax="lucene")

    sent_body = mock_client.post.call_args.kwargs["json"]
    assert sent_body["querySyntax"] == "lucene"


@patch("jseval.retriever.httpx.Client")
def test_retrieve_default_query_syntax_omits_the_field(MockClient):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)
    mock_client.post.return_value = _mock_search_response([])

    retrieve({"q1": "test query"}, "http://localhost:8080", mode="hybrid")

    sent_body = mock_client.post.call_args.kwargs["json"]
    assert "querySyntax" not in sent_body


@patch("jseval.retriever.httpx.Client")
def test_retrieve_allow_errors(MockClient):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)

    import httpx as real_httpx
    mock_client.post.side_effect = real_httpx.ConnectError("connection refused")

    scored, raw = retrieve(
        {"q1": "test"},
        "http://localhost:8080",
        allow_errors=True,
        max_retries=1,
    )

    assert len(scored) == 0
    assert len(raw) == 1
    assert "error" in raw[0]


# ---------------------------------------------------------------------------
# Tempdoc 803: the metric must evaluate the DELIVERED ranking
# ---------------------------------------------------------------------------

@patch("jseval.retriever.httpx.Client")
def test_retrieve_scores_by_delivered_rank_not_engine_score(MockClient):
    """The regression test for the defect 800/802 measured.

    The engine returns results already in cross-encoder order but leaves each hit's `score`
    at its PRE-RERANK fusion value — so the delivered order and the score order disagree.
    This response is built to make that disagreement total: delivered order is d1, d2, d3
    while the engine scores rank them d3, d2, d1. Scoring by `hit["score"]` (the old
    behaviour) would make ir_measures evaluate the exact reverse of what shipped.
    """
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)

    mock_client.post.return_value = _mock_search_response([
        {"id": "d1.txt", "score": 0.1, "fields": {"filename": "d1.txt"}},
        {"id": "d2.txt", "score": 0.5, "fields": {"filename": "d2.txt"}},
        {"id": "d3.txt", "score": 0.9, "fields": {"filename": "d3.txt"}},
    ])

    scored, _ = retrieve({"q1": "test"}, "http://localhost:8080", mode="hybrid")

    # Sorting by the recorded score must reproduce the DELIVERED order, not the engine's.
    by_score = [sd.doc_id for sd in sorted(scored, key=lambda s: -s.score)]
    assert by_score == ["d1", "d2", "d3"]

    # Strictly decreasing: ties would let a sort break the delivered order arbitrarily,
    # which is how the helper in tempdoc 802 disagreed with ir_measures on absolute values.
    scores = [sd.score for sd in scored]
    assert scores == sorted(scores, reverse=True)
    assert len(set(scores)) == len(scores)

    # And the recorded score must NOT be the engine's — the thing that made this invisible.
    assert scores != [0.1, 0.5, 0.9]


@patch("jseval.retriever.httpx.Client")
def test_retrieve_rank_scores_survive_unresolvable_hit(MockClient):
    """A skipped hit leaves a gap in the rank scores; order must still be exact.

    Only the ORDER carries meaning, so a gap is harmless — but the remaining docs must stay
    strictly decreasing, or the skip would silently corrupt the ranking it was meant to drop.
    """
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)

    mock_client.post.return_value = _mock_search_response([
        {"id": "d1.txt", "score": 0.1, "fields": {"filename": "d1.txt"}},
        {"id": "", "score": 0.5, "fields": {}},                       # unresolvable → skipped
        {"id": "d3.txt", "score": 0.9, "fields": {"filename": "d3.txt"}},
    ])

    scored, raw = retrieve(
        {"q1": "test"}, "http://localhost:8080", mode="hybrid", allow_errors=True,
    )

    assert [sd.doc_id for sd in scored] == ["d1", "d3"]
    assert [sd.doc_id for sd in sorted(scored, key=lambda s: -s.score)] == ["d1", "d3"]
    assert scored[0].score > scored[1].score
    assert raw[0]["identity_resolution_errors"] == 1
