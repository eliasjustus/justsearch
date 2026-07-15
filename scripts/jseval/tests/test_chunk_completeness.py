"""Tests for chunk_completeness.py — the eval-time chunk-completeness validity guard
(tempdoc 718)."""

from __future__ import annotations

import json

from jseval.chunk_completeness import (
    CHUNK_THRESHOLD_CHARS,
    chunk_completeness_verdict,
    expected_chunk_docs,
)


def _write_corpus(tmp_path, docs):
    p = tmp_path / "corpus.jsonl"
    with p.open("w", encoding="utf-8") as f:
        for d in docs:
            f.write(json.dumps(d) + "\n")
    return p


# --- expected_chunk_docs -----------------------------------------------------


class TestExpectedChunkDocs:
    def test_long_doc_corpus_expects_chunks(self, tmp_path):
        long_text = "x" * (CHUNK_THRESHOLD_CHARS + 500)
        p = _write_corpus(tmp_path, [
            {"_id": "d1", "title": "", "text": long_text},
            {"_id": "d2", "title": "", "text": long_text},
        ])
        assert expected_chunk_docs(p) == 2

    def test_short_doc_corpus_expects_no_chunks(self, tmp_path):
        p = _write_corpus(tmp_path, [
            {"_id": "d1", "title": "short", "text": "just a short doc"},
            {"_id": "d2", "title": "", "text": "also short"},
        ])
        assert expected_chunk_docs(p) == 0

    def test_mixed_corpus_counts_only_long_docs(self, tmp_path):
        long_text = "x" * (CHUNK_THRESHOLD_CHARS + 1)
        p = _write_corpus(tmp_path, [
            {"_id": "d1", "title": "", "text": long_text},
            {"_id": "d2", "title": "", "text": "short"},
            {"_id": "d3", "title": "", "text": long_text},
        ])
        assert expected_chunk_docs(p) == 2

    def test_boundary_exactly_at_threshold_counts(self, tmp_path):
        text = "x" * CHUNK_THRESHOLD_CHARS  # len == threshold -> ChunkDocumentWriter chunks (>=)
        p = _write_corpus(tmp_path, [{"_id": "d1", "title": "", "text": text}])
        assert expected_chunk_docs(p) == 1

    def test_boundary_one_under_threshold_does_not_count(self, tmp_path):
        text = "x" * (CHUNK_THRESHOLD_CHARS - 1)
        p = _write_corpus(tmp_path, [{"_id": "d1", "title": "", "text": text}])
        assert expected_chunk_docs(p) == 0

    def test_title_is_folded_into_content_length(self, tmp_path):
        # title + "\n\n" + text must reach the threshold together (materialize.py:57's format).
        title = "T" * 10
        text = "x" * (CHUNK_THRESHOLD_CHARS - 8)  # alone: under threshold
        p = _write_corpus(tmp_path, [{"_id": "d1", "title": title, "text": text}])
        # len(title) + len("\n\n") + len(text) = 10 + 2 + (threshold - 8) = threshold + 4
        assert expected_chunk_docs(p) == 1

    def test_missing_corpus_jsonl_returns_zero(self, tmp_path):
        # BEIR datasets have no local corpus.jsonl (materialized on the fly via ir_datasets) --
        # a missing file must degrade to 0, never raise.
        assert expected_chunk_docs(tmp_path / "does-not-exist.jsonl") == 0

    def test_blank_lines_are_skipped(self, tmp_path):
        long_text = "x" * (CHUNK_THRESHOLD_CHARS + 1)
        p = tmp_path / "corpus.jsonl"
        p.write_text(
            json.dumps({"_id": "d1", "title": "", "text": long_text}) + "\n\n\n",
            encoding="utf-8",
        )
        assert expected_chunk_docs(p) == 1


# --- chunk_completeness_verdict ----------------------------------------------


class TestChunkCompletenessVerdict:
    def test_expected_zero_is_chunk_free_regardless_of_observed(self):
        # The two 0-chunk cases must be distinguishable: this is the "short docs" case.
        result = chunk_completeness_verdict(0, 0, 0.0, False)
        assert result.verdict == "chunk-free"
        assert result.expected == 0
        assert result.reasons

    def test_expected_zero_is_chunk_free_even_if_observed_looks_healthy(self):
        # expected==0 short-circuits before reading observed signals at all.
        result = chunk_completeness_verdict(0, 500, 100.0, True)
        assert result.verdict == "chunk-free"

    def test_expected_positive_observed_zero_is_degenerate(self):
        # The other 0-chunk case: this is the twice-observed tempdoc-717 failure mode.
        result = chunk_completeness_verdict(50, 0, 0.0, False)
        assert result.verdict == "degenerate"
        assert any("chunk_doc_count == 0" in r for r in result.reasons)

    def test_expected_positive_observed_healthy_is_ok(self):
        result = chunk_completeness_verdict(50, 48, 100.0, True)
        assert result.verdict == "ok"

    def test_coverage_below_floor_is_degenerate(self):
        result = chunk_completeness_verdict(50, 48, 42.0, True)
        assert result.verdict == "degenerate"
        assert any("chunkVectorCoveragePercent" in r for r in result.reasons)

    def test_coverage_at_floor_is_ok(self):
        result = chunk_completeness_verdict(50, 48, 99.9, True)
        assert result.verdict == "ok"

    def test_chunk_merge_absent_is_degenerate_even_with_healthy_counts(self):
        result = chunk_completeness_verdict(50, 48, 100.0, False)
        assert result.verdict == "degenerate"
        assert any("chunk_merge" in r for r in result.reasons)

    def test_coverage_none_treated_as_zero(self):
        # An older backend / a snapshot without the field -- never crash, never a vacuous pass.
        result = chunk_completeness_verdict(50, 48, None, True)
        assert result.verdict == "degenerate"
        assert any("chunkVectorCoveragePercent=0.0" in r for r in result.reasons)

    def test_custom_coverage_floor_is_honored(self):
        result = chunk_completeness_verdict(50, 48, 80.0, True, coverage_floor=50.0)
        assert result.verdict == "ok"

    def test_reasons_never_boolean_only(self):
        for args in [(0, 0, 0.0, False), (50, 0, 0.0, False), (50, 48, 100.0, True)]:
            result = chunk_completeness_verdict(*args)
            assert isinstance(result.reasons, list)
            assert all(isinstance(r, str) and r for r in result.reasons)
            assert result.reasons  # never empty -- always documents why

    def test_multiple_degeneracy_reasons_all_surface(self):
        result = chunk_completeness_verdict(50, 0, 0.0, False)
        assert result.verdict == "degenerate"
        assert len(result.reasons) == 3  # count, coverage, and chunk_merge all fire
