"""Tests for spot_check.py — developer ranking spot-check."""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import MagicMock, patch

from jseval.spot_check import (
    DEFAULT_SUITE,
    QueryEntry,
    _extract_result,
    _summarize_mode,
    format_console,
    load_suite,
)


class TestDefaultSuite:
    def test_has_10_queries(self):
        assert len(DEFAULT_SUITE) == 10

    def test_categories_present(self):
        categories = {q.category for q in DEFAULT_SUITE}
        assert "exact" in categories
        assert "entity" in categories
        assert "acronym" in categories
        assert "paraphrase" in categories
        assert "navigation" in categories
        assert "negative" in categories

    def test_ids_unique(self):
        ids = [q.id for q in DEFAULT_SUITE]
        assert len(ids) == len(set(ids))


class TestLoadSuite:
    def test_default(self):
        suite = load_suite(None)
        assert len(suite) == 10
        assert suite[0].text == DEFAULT_SUITE[0].text

    def test_custom_json(self, tmp_path):
        p = tmp_path / "suite.json"
        p.write_text(json.dumps([
            {"id": "c1", "text": "custom query", "category": "test"},
        ]), encoding="utf-8")
        suite = load_suite(p)
        assert len(suite) == 1
        assert suite[0].text == "custom query"
        assert suite[0].category == "test"


class TestExtractResult:
    def test_with_response(self):
        entry = QueryEntry("q1", "test", "exact")
        response = {
            "results": [
                {"score": 0.9, "fields": {"filename": "doc.txt", "path": "/docs/doc.txt"}},
                {"score": 0.5, "fields": {"filename": "other.txt", "path": "/docs/other.txt"}},
            ],
            "totalHits": 42,
            "tookMs": 15,
        }
        r = _extract_result(entry, "lexical", response, 5)
        assert r["top1_score"] == 0.9
        assert r["mean_score"] == 0.7
        assert r["total_hits"] == 42
        assert r["took_ms"] == 15
        assert len(r["hits"]) == 2

    def test_with_none_response(self):
        entry = QueryEntry("q1", "test", "exact")
        r = _extract_result(entry, "lexical", None, 5)
        assert r["top1_score"] is None
        assert r["total_hits"] == 0


class TestSummarizeMode:
    def test_basic(self):
        results = [
            {"top1_score": 0.9, "mean_score": 0.7, "total_hits": 40, "took_ms": 10},
            {"top1_score": 0.5, "mean_score": 0.3, "total_hits": 20, "took_ms": 20},
        ]
        s = _summarize_mode(results)
        assert s["query_count"] == 2
        assert s["mean_top1_score"] == 0.7
        assert s["mean_topk_score"] == 0.5

    def test_handles_none(self):
        results = [
            {"top1_score": None, "mean_score": None, "total_hits": 0, "took_ms": None},
        ]
        s = _summarize_mode(results)
        assert s["mean_top1_score"] is None


class TestFormatConsole:
    def test_produces_output(self):
        results = {
            "per_query": [
                {
                    "query": "test query", "category": "exact", "mode": "lexical",
                    "total_hits": 10, "took_ms": 5, "hits": [
                        {"score": 0.9, "filename": "doc.txt", "path": "/doc.txt"},
                    ],
                },
            ],
            "per_mode": {
                "lexical": {"query_count": 1, "mean_top1_score": 0.9,
                            "mean_topk_score": 0.9, "mean_total_hits": 10.0,
                            "mean_took_ms": 5.0},
            },
        }
        output = format_console(results)
        assert '"test query"' in output
        assert "exact" in output
        assert "0.9000" in output
