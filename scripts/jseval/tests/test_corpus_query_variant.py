"""Tests for corpus_query_variant.py — deterministic keyword + llm-reduced query-variant
datasets (tempdoc 678 §Pillar-5 E5-C).
"""

from __future__ import annotations

import hashlib
import json
from unittest.mock import MagicMock, patch

from jseval import corpus_query_variant as cqv


def _write_source_dataset(base, name="mixed/toy-corpus"):
    """A tiny synthetic golden/mixed-layout dataset: corpus.jsonl + queries.jsonl (+
    queries.json) + qrels/test.tsv, mirroring `corpus_build.build_golden()`'s output shape.
    """
    source_dir = base / name
    source_dir.mkdir(parents=True)

    docs = [
        # "gadget" appears in every doc (DF=4, common); "widget" in 2 docs; "zephyr" in 1.
        {"_id": "d1", "title": "", "text": "the gadget widget zephyr assembly manual"},
        {"_id": "d2", "title": "", "text": "gadget widget maintenance procedure"},
        {"_id": "d3", "title": "", "text": "gadget repair notes for technicians"},
        {"_id": "d4", "title": "", "text": "gadget safety warnings and disclaimers"},
    ]
    with (source_dir / "corpus.jsonl").open("w", encoding="utf-8") as f:
        for d in docs:
            f.write(json.dumps(d) + "\n")

    queries = [
        {"_id": "q1", "text": "zephyr widget gadget"},
        {"_id": "q2", "text": "the gadget"},  # "the" too short/absent, "gadget" only eligible
        {"_id": "q3", "text": "xyzzy plugh"},  # zero eligible tokens -> fallback
    ]
    with (source_dir / "queries.jsonl").open("w", encoding="utf-8") as f:
        for q in queries:
            f.write(json.dumps(q) + "\n")

    (source_dir / "queries.json").write_text(
        json.dumps([{"query": q["text"], "answer": "", "question_type": "t",
                     "evidence_ids": ["d1"]} for q in queries], indent=1),
        encoding="utf-8",
    )

    qrels_dir = source_dir / "qrels"
    qrels_dir.mkdir()
    (qrels_dir / "test.tsv").write_text(
        "query-id\tcorpus-id\tscore\nq1\td1\t1\nq2\td1\t1\nq3\td1\t1\n", encoding="utf-8"
    )
    return source_dir


# ---------------------------------------------------------------------------
# (a) DF/IDF ordering — rarest tokens (lowest corpus DF) are kept first
# ---------------------------------------------------------------------------

def test_keyword_variant_keeps_rarest_tokens_by_ascending_df(tmp_path):
    source_dir = _write_source_dataset(tmp_path)
    df = cqv.document_frequencies(source_dir / "corpus.jsonl")
    assert df["gadget"] == 4
    assert df["widget"] == 2
    assert df["zephyr"] == 1

    # top_k=2 of {"zephyr"(df1), "widget"(df2), "gadget"(df4)} -> keep the 2 rarest: zephyr, widget.
    text, used_fallback = cqv.keyword_variant("zephyr widget gadget", df, top_k=2)
    assert not used_fallback
    assert text == "zephyr widget"  # gadget (most common) dropped, not the rarest two


# ---------------------------------------------------------------------------
# (b) determinism — two runs produce byte-identical queries.jsonl
# ---------------------------------------------------------------------------

def test_build_query_variant_is_deterministic_across_two_runs(tmp_path):
    source_dir = _write_source_dataset(tmp_path)
    dest1 = tmp_path / "out1"
    dest2 = tmp_path / "out2"
    cqv.build_query_variant(source_dir, dest1, variant="keyword", top_k=8)
    cqv.build_query_variant(source_dir, dest2, variant="keyword", top_k=8)

    assert (dest1 / "queries.jsonl").read_bytes() == (dest2 / "queries.jsonl").read_bytes()
    assert (dest1 / "queries.json").read_bytes() == (dest2 / "queries.json").read_bytes()


# ---------------------------------------------------------------------------
# (c) original query order preserved in the kept-token output
# ---------------------------------------------------------------------------

def test_keyword_variant_preserves_original_query_order_not_rank_order():
    df = {"alpha": 5, "beta": 1, "gamma": 3}
    # Rank order (ascending DF) would be beta, gamma, alpha — but query order is alpha, beta, gamma.
    text, used_fallback = cqv.keyword_variant("alpha beta gamma", df, top_k=3)
    assert not used_fallback
    assert text == "alpha beta gamma"  # NOT "beta gamma alpha"


def test_keyword_variant_top_k_selection_still_respects_original_order():
    df = {"alpha": 5, "beta": 1, "gamma": 3, "delta": 2}
    # top_k=2 keeps the 2 rarest: beta(1), delta(2). Output order must be their ORIGINAL
    # query order (delta appears before beta in the query), not rank order.
    text, used_fallback = cqv.keyword_variant("alpha delta gamma beta", df, top_k=2)
    assert not used_fallback
    assert text == "delta beta"


# ---------------------------------------------------------------------------
# (d) short-token and absent-from-vocabulary filtering
# ---------------------------------------------------------------------------

def test_keyword_variant_drops_short_and_out_of_vocabulary_tokens():
    df = {"gadget": 1}
    # "to", "it" too short (<3 chars); "xyzzy" absent from corpus vocabulary; "gadget" eligible.
    text, used_fallback = cqv.keyword_variant("to it xyzzy gadget", df, top_k=8)
    assert not used_fallback
    assert text == "gadget"


def test_keyword_variant_deduplicates_repeated_terms():
    df = {"gadget": 1, "widget": 2}
    text, used_fallback = cqv.keyword_variant("gadget widget gadget", df, top_k=8)
    assert not used_fallback
    assert text == "gadget widget"  # second "gadget" occurrence not repeated


# ---------------------------------------------------------------------------
# (e) qrels + corpus passthrough intact (byte-identical copy, not regenerated)
# ---------------------------------------------------------------------------

def test_build_query_variant_copies_corpus_and_qrels_verbatim(tmp_path):
    source_dir = _write_source_dataset(tmp_path)
    dest_dir = tmp_path / "out"
    cqv.build_query_variant(source_dir, dest_dir, variant="keyword", top_k=8)

    assert (dest_dir / "corpus.jsonl").read_bytes() == (source_dir / "corpus.jsonl").read_bytes()
    assert (dest_dir / "qrels" / "test.tsv").read_bytes() == \
        (source_dir / "qrels" / "test.tsv").read_bytes()


def test_build_query_variant_does_not_copy_corpus_dir(tmp_path):
    source_dir = _write_source_dataset(tmp_path)
    (source_dir / "corpus-dir").mkdir()
    (source_dir / "corpus-dir" / "d1.txt").write_text("irrelevant", encoding="utf-8")

    dest_dir = tmp_path / "out"
    cqv.build_query_variant(source_dir, dest_dir, variant="keyword", top_k=8)

    assert not (dest_dir / "corpus-dir").exists()


# ---------------------------------------------------------------------------
# (f) zero-eligible-token fallback: counted, and text left unchanged
# ---------------------------------------------------------------------------

def test_keyword_variant_falls_back_to_original_text_when_no_tokens_eligible():
    df = {"gadget": 1}
    text, used_fallback = cqv.keyword_variant("xyzzy plugh", df, top_k=8)
    assert used_fallback
    assert text == "xyzzy plugh"  # unchanged, not emptied


def test_build_query_variant_counts_fallbacks_in_metadata(tmp_path):
    source_dir = _write_source_dataset(tmp_path)
    dest_dir = tmp_path / "out"
    meta = cqv.build_query_variant(source_dir, dest_dir, variant="keyword", top_k=8)

    assert meta["total_queries"] == 3
    assert meta["fallback_count"] == 1  # only q3 ("xyzzy plugh") has zero eligible tokens

    lines = [json.loads(l) for l in (dest_dir / "queries.jsonl").read_text(encoding="utf-8").splitlines()]
    q3 = next(d for d in lines if d["_id"] == "q3")
    assert q3["text"] == "xyzzy plugh"


# ---------------------------------------------------------------------------
# metadata content + queries.json field (query, not text)
# ---------------------------------------------------------------------------

def test_build_query_variant_writes_metadata_fields(tmp_path):
    source_dir = _write_source_dataset(tmp_path)
    dest_dir = tmp_path / "out"
    meta = cqv.build_query_variant(source_dir, dest_dir, variant="keyword", top_k=8)

    assert meta["source_dataset"] == "mixed/toy-corpus"
    assert meta["variant"] == "keyword"
    assert meta["top_k"] == 8
    assert meta["tool_version"] == cqv.TOOL_VERSION
    assert isinstance(meta["source_corpus_signature"], str)

    on_disk = json.loads((dest_dir / "metadata.json").read_text(encoding="utf-8"))
    assert on_disk == meta


def test_build_query_variant_transforms_queries_json_query_field(tmp_path):
    source_dir = _write_source_dataset(tmp_path)
    dest_dir = tmp_path / "out"
    cqv.build_query_variant(source_dir, dest_dir, variant="keyword", top_k=8)

    data = json.loads((dest_dir / "queries.json").read_text(encoding="utf-8"))
    assert data[0]["query"] == "zephyr widget gadget"  # same transform as queries.jsonl's q1
    # non-query fields untouched
    assert data[0]["evidence_ids"] == ["d1"]


def test_unknown_variant_raises():
    import pytest

    with pytest.raises(ValueError, match="Unknown variant"):
        cqv.build_query_variant(None, None, variant="bogus-variant", top_k=8)


# ---------------------------------------------------------------------------
# CLI surface registration
# ---------------------------------------------------------------------------

def test_corpus_query_variant_is_registered_in_corpus_group():
    from jseval.commands import command_groups
    import jseval.cli  # noqa: F401 — populates the registry

    assert command_groups()["corpus-query-variant"] == "corpus"


# ---------------------------------------------------------------------------
# llm-reduced variant (tempdoc 678 §Pillar-5 E5-C) — mocked at the same client
# seam tests/test_judge_ceiling.py exercises: the running backend's OpenAI-compatible
# httpx.post call, and the served_model_name discovery helper.
# ---------------------------------------------------------------------------

def _mock_llm_response(content):
    resp = MagicMock()
    resp.json.return_value = {"choices": [{"message": {"content": content}}]}
    return resp


class TestLlmReducedVariant:
    @patch("jseval.corpus_query_variant.served_model_name", return_value="test-served-model")
    @patch("jseval.corpus_query_variant.httpx.post")
    def test_happy_path_writes_transformed_queries_and_metadata(self, mock_post, mock_model, tmp_path):
        source_dir = _write_source_dataset(tmp_path)
        dest_dir = tmp_path / "out"
        mock_post.return_value = _mock_llm_response("eviction notice tenant rights case")

        meta = cqv.build_query_variant(
            source_dir, dest_dir, variant="llm-reduced", top_k=8,
            api_url="http://127.0.0.1:33221",
        )

        lines = [json.loads(l) for l in
                 (dest_dir / "queries.jsonl").read_text(encoding="utf-8").splitlines()]
        assert all(d["text"] == "eviction notice tenant rights case" for d in lines)

        assert meta["variant"] == "llm-reduced"
        assert meta["fallback_count"] == 0
        llm_meta = meta["llm_reduced"]
        assert llm_meta["api_url"] == "http://127.0.0.1:33221"
        assert llm_meta["endpoint"] == "/v1/chat/completions"
        assert llm_meta["prompt_sha256"] == hashlib.sha256(
            cqv.LLM_REDUCE_PROMPT.encode("utf-8")).hexdigest()
        assert llm_meta["model"] == "test-served-model"
        assert llm_meta["sampling_params"]["temperature"] == 0.0
        assert llm_meta["sampling_params"]["seed"] == cqv._LLM_REDUCE_SEED
        assert llm_meta["fallback_count"] == 0

        # Request body actually sent used the fixed prompt template + deterministic sampling
        # (first call == q1, processed via the queries.jsonl loop before queries.json's pass).
        sent = mock_post.call_args_list[0].kwargs["json"]
        assert sent["messages"][0]["content"] == cqv.LLM_REDUCE_PROMPT.format(
            query="zephyr widget gadget")
        assert sent["temperature"] == 0.0
        assert sent["seed"] == cqv._LLM_REDUCE_SEED
        assert sent["model"] == "test-served-model"

    @patch("jseval.corpus_query_variant.served_model_name", return_value=None)
    @patch("jseval.corpus_query_variant.httpx.post")
    def test_per_query_failure_falls_back_to_original_and_is_counted(self, mock_post, mock_model, tmp_path):
        source_dir = _write_source_dataset(tmp_path)
        dest_dir = tmp_path / "out"

        def side_effect(url, json, timeout):
            if json["messages"][0]["content"].endswith("zephyr widget gadget"):
                raise RuntimeError("connection refused")
            return _mock_llm_response("some short query")

        mock_post.side_effect = side_effect

        meta = cqv.build_query_variant(
            source_dir, dest_dir, variant="llm-reduced", top_k=8,
            api_url="http://127.0.0.1:33221",
        )

        assert meta["fallback_count"] == 1  # only q1 (the one whose call raised) falls back
        by_id = {d["_id"]: d["text"] for d in
                  (json.loads(l) for l in
                   (dest_dir / "queries.jsonl").read_text(encoding="utf-8").splitlines())}
        assert by_id["q1"] == "zephyr widget gadget"  # unchanged original on the failing call
        assert by_id["q2"] == "some short query"

    @patch("jseval.corpus_query_variant.served_model_name", return_value="m")
    @patch("jseval.corpus_query_variant.httpx.post")
    def test_empty_response_falls_back_to_original(self, mock_post, mock_model, tmp_path):
        source_dir = _write_source_dataset(tmp_path)
        dest_dir = tmp_path / "out"
        mock_post.return_value = _mock_llm_response("   \n  \n")

        meta = cqv.build_query_variant(
            source_dir, dest_dir, variant="llm-reduced", top_k=8,
            api_url="http://127.0.0.1:33221",
        )
        assert meta["fallback_count"] == 3  # all 3 queries fall back (blank/whitespace response)

    @patch("jseval.corpus_query_variant.served_model_name", return_value="m")
    @patch("jseval.corpus_query_variant.httpx.post")
    def test_multiline_preamble_response_takes_last_non_empty_line(self, mock_post, mock_model, tmp_path):
        source_dir = _write_source_dataset(tmp_path)
        dest_dir = tmp_path / "out"
        mock_post.return_value = _mock_llm_response(
            "Sure, here's a concise search query:\n\n  eviction notice tenant rights  \n"
        )

        meta = cqv.build_query_variant(
            source_dir, dest_dir, variant="llm-reduced", top_k=8,
            api_url="http://127.0.0.1:33221",
        )
        assert meta["fallback_count"] == 0
        lines = [json.loads(l) for l in
                 (dest_dir / "queries.jsonl").read_text(encoding="utf-8").splitlines()]
        assert all(d["text"] == "eviction notice tenant rights" for d in lines)

    @patch("jseval.corpus_query_variant.served_model_name", return_value="m")
    @patch("jseval.corpus_query_variant.httpx.post")
    def test_long_response_capped_at_200_chars(self, mock_post, mock_model, tmp_path):
        source_dir = _write_source_dataset(tmp_path)
        dest_dir = tmp_path / "out"
        mock_post.return_value = _mock_llm_response("x" * 250)

        cqv.build_query_variant(
            source_dir, dest_dir, variant="llm-reduced", top_k=8,
            api_url="http://127.0.0.1:33221",
        )
        lines = [json.loads(l) for l in
                 (dest_dir / "queries.jsonl").read_text(encoding="utf-8").splitlines()]
        assert all(len(d["text"]) == 200 for d in lines)

    def test_missing_llm_ctx_raises(self):
        import pytest

        with pytest.raises(ValueError, match="LlmReduceContext"):
            cqv.llm_reduced_variant("some text", {}, 8, None)

    def test_build_query_variant_requires_api_url_for_llm_reduced(self, tmp_path):
        import pytest

        source_dir = _write_source_dataset(tmp_path)
        with pytest.raises(ValueError, match="requires api_url"):
            cqv.build_query_variant(source_dir, tmp_path / "out", variant="llm-reduced", top_k=8)


# ---------------------------------------------------------------------------
# CLI --api-url validation: keyword rejects it, llm-reduced requires it
# ---------------------------------------------------------------------------

class TestApiUrlCliValidation:
    def test_keyword_rejects_api_url(self, tmp_path):
        from click.testing import CliRunner

        from jseval.cli import main

        _write_source_dataset(tmp_path)
        result = CliRunner().invoke(main, [
            "corpus-query-variant", "--source", "mixed/toy-corpus", "--variant", "keyword",
            "--api-url", "http://127.0.0.1:33221", "--datasets-dir", str(tmp_path),
        ])
        assert result.exit_code != 0
        assert "api-url" in result.output.lower()
        assert "keyword" in result.output.lower()

    def test_llm_reduced_requires_api_url(self, tmp_path):
        from click.testing import CliRunner

        from jseval.cli import main

        _write_source_dataset(tmp_path)
        result = CliRunner().invoke(main, [
            "corpus-query-variant", "--source", "mixed/toy-corpus", "--variant", "llm-reduced",
            "--datasets-dir", str(tmp_path),
        ])
        assert result.exit_code != 0
        assert "api-url" in result.output.lower()
        assert "llm-reduced" in result.output.lower()
