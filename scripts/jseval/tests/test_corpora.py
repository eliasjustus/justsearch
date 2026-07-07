"""Tests for corpora.py — dataset loading."""

from __future__ import annotations

import json
import warnings
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from jseval.corpora import (
    BEIR_DATASETS,
    _read_qrels_tsv,
    _read_queries_jsonl,
    load,
)


# ---------------------------------------------------------------------------
# BEIR loading (mocked ir-datasets)
# ---------------------------------------------------------------------------

def _make_mock_dataset(queries=None, qrels_dict=None, docs_count=100):
    """Create a mock ir-datasets dataset object."""
    ds = MagicMock()
    queries = queries or [
        MagicMock(query_id="q1", text="what is gravity"),
        MagicMock(query_id="q2", text="neural networks"),
    ]
    ds.queries_iter.return_value = queries
    ds.qrels_dict.return_value = qrels_dict or {
        "q1": {"d1": 1, "d2": 0},
        "q2": {"d3": 2, "d4": 1},
    }
    ds.docs_count.return_value = docs_count
    return ds


@patch("jseval.corpora.ir_datasets")
def test_load_beir_scifact(mock_ir):
    mock_ir.load.return_value = _make_mock_dataset()
    queries, qrels, meta = load("scifact")

    mock_ir.load.assert_called_once_with("beir/scifact/test")
    assert queries["q1"].text == "what is gravity"
    assert queries["q2"].text == "neural networks"
    assert queries["q1"].annotations == {}
    assert "q1" in qrels
    assert qrels["q1"]["d1"] == 1
    assert meta.name == "scifact"
    assert meta.source == "beir"
    assert meta.doc_count == 100
    assert meta.query_count == 2


def test_load_beir_unknown_dataset():
    with pytest.raises(ValueError, match="Unknown dataset"):
        load("nonexistent_dataset")


# ---------------------------------------------------------------------------
# Golden set loading (local files)
# ---------------------------------------------------------------------------

def _create_golden_set(tmp_path: Path, *, metadata=None, expected_terms=None):
    """Create a minimal golden set directory."""
    ds_dir = tmp_path / "golden" / "test-v1"
    ds_dir.mkdir(parents=True)

    # queries.jsonl
    queries_file = ds_dir / "queries.jsonl"
    queries_file.write_text(
        '{"_id": "q1", "text": "search query one"}\n'
        '{"_id": "q2", "text": "search query two"}\n',
        encoding="utf-8",
    )

    # qrels/test.tsv
    qrels_dir = ds_dir / "qrels"
    qrels_dir.mkdir()
    (qrels_dir / "test.tsv").write_text(
        "q1\td1\t2\n"
        "q1\td2\t0\n"
        "q2\td3\t1\n",
        encoding="utf-8",
    )

    # corpus.jsonl
    (ds_dir / "corpus.jsonl").write_text(
        '{"_id": "d1", "title": "Doc 1", "text": "content"}\n'
        '{"_id": "d2", "title": "Doc 2", "text": "content"}\n'
        '{"_id": "d3", "title": "Doc 3", "text": "content"}\n',
        encoding="utf-8",
    )

    if metadata is not None:
        (ds_dir / "metadata.json").write_text(
            json.dumps(metadata), encoding="utf-8"
        )

    if expected_terms is not None:
        (ds_dir / "expected_terms.json").write_text(
            json.dumps(expected_terms), encoding="utf-8"
        )

    return ds_dir


def test_load_golden_set(tmp_path):
    _create_golden_set(tmp_path)
    queries, qrels, meta = load("golden/test-v1", base_dir=tmp_path)

    assert queries["q1"].text == "search query one"
    assert queries["q2"].text == "search query two"
    assert qrels["q1"]["d1"] == 2
    assert qrels["q1"]["d2"] == 0
    assert qrels["q2"]["d3"] == 1
    assert meta.source == "golden"
    assert meta.doc_count == 3
    assert meta.query_count == 2
    assert not meta.has_expected_terms


def test_load_golden_set_staleness_warning(tmp_path):
    _create_golden_set(tmp_path, metadata={
        "version": "1.0",
        "created_date": "2025-01-01",
        "query_type_distribution": {},
    })
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        _, _, meta = load("golden/test-v1", base_dir=tmp_path)
        age_warnings = [x for x in w if "days old" in str(x.message)]
        assert len(age_warnings) == 1
    assert meta.version == "1.0"


def test_load_golden_set_missing_query_types(tmp_path):
    _create_golden_set(tmp_path, metadata={
        "version": "1.0",
        "created_date": "2026-03-16",
        "query_type_distribution": {
            "NAVIGATIONAL": 0,
            "INFORMATIONAL": 5,
        },
    })
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        load("golden/test-v1", base_dir=tmp_path)
        type_warnings = [x for x in w if "NAVIGATIONAL" in str(x.message)]
        assert len(type_warnings) == 1


def test_load_golden_set_expected_terms(tmp_path):
    _create_golden_set(tmp_path, expected_terms={
        "q1": ["gravity", "force"],
        "q2": ["neural", "network"],
    })
    _, _, meta = load("golden/test-v1", base_dir=tmp_path)
    assert meta.has_expected_terms


def test_load_mixed_corpus(tmp_path):
    # Mixed uses same format as golden, just different prefix
    ds_dir = tmp_path / "mixed" / "sf_nf"
    ds_dir.mkdir(parents=True)
    (ds_dir / "queries.jsonl").write_text(
        '{"_id": "scifact__q1", "text": "query"}\n', encoding="utf-8"
    )
    (ds_dir / "qrels").mkdir()
    (ds_dir / "qrels" / "test.tsv").write_text(
        "scifact__q1\tscifact__d1\t1\n", encoding="utf-8"
    )
    (ds_dir / "corpus.jsonl").write_text(
        '{"_id": "scifact__d1", "text": "doc"}\n', encoding="utf-8"
    )

    queries, qrels, meta = load("mixed/sf_nf", base_dir=tmp_path)
    assert meta.source == "mixed"
    assert queries["scifact__q1"].text == "query"
    assert qrels["scifact__q1"]["scifact__d1"] == 1


# ---------------------------------------------------------------------------
# Qrels TSV parsing
# ---------------------------------------------------------------------------

def test_read_qrels_tsv_with_header(tmp_path):
    tsv = tmp_path / "qrels.tsv"
    tsv.write_text(
        "query-id\tcorpus-id\tscore\n"
        "q1\td1\t2\n"
        "q1\td2\t0\n",
        encoding="utf-8",
    )
    qrels = _read_qrels_tsv(tsv)
    assert qrels["q1"]["d1"] == 2
    assert qrels["q1"]["d2"] == 0


def test_read_qrels_tsv_without_header(tmp_path):
    tsv = tmp_path / "qrels.tsv"
    tsv.write_text("q1\td1\t1\nq2\td2\t0\n", encoding="utf-8")
    qrels = _read_qrels_tsv(tsv)
    assert qrels["q1"]["d1"] == 1
    assert qrels["q2"]["d2"] == 0


def test_read_qrels_tsv_trec_format(tmp_path):
    """TREC format: query-id  iter  doc-id  relevance (4 columns)."""
    tsv = tmp_path / "qrels.tsv"
    tsv.write_text("q1\t0\td1\t2\nq1\t0\td2\t1\n", encoding="utf-8")
    qrels = _read_qrels_tsv(tsv)
    assert qrels["q1"]["d1"] == 2
    assert qrels["q1"]["d2"] == 1


def test_read_qrels_tsv_trec_format_with_header(tmp_path):
    """A 4-column TREC file WITH a header must still take the TREC branch (regression for the
    bug where `start == 0` silently misrouted a headered TREC file into the 3-column BEIR
    branch, reading the constant `iter` column as the doc-id)."""
    tsv = tmp_path / "qrels.tsv"
    tsv.write_text(
        "query-id\titer\tdoc-id\trelevance\n"
        "q1\t0\td1\t2\n"
        "q1\t0\td2\t1\n",
        encoding="utf-8",
    )
    qrels = _read_qrels_tsv(tsv)
    assert qrels["q1"]["d1"] == 2
    assert qrels["q1"]["d2"] == 1


def test_read_qrels_tsv_normalizes_doc_id_case(tmp_path):
    """Doc-ids are lowercased at the mint site so a filesystem-resolved doc-id (also lowercased
    by `retriever.py:_filename_to_doc_id`) matches regardless of on-disk casing."""
    tsv = tmp_path / "qrels.tsv"
    tsv.write_text("q1\tD1\t2\nq1\tMiXeDCaSe2\t1\n", encoding="utf-8")
    qrels = _read_qrels_tsv(tsv)
    assert qrels["q1"]["d1"] == 2
    assert qrels["q1"]["mixedcase2"] == 1


def test_read_queries_jsonl(tmp_path):
    f = tmp_path / "queries.jsonl"
    f.write_text(
        '{"_id": "q1", "text": "hello"}\n'
        '\n'
        '{"_id": "q2", "text": "world"}\n',
        encoding="utf-8",
    )
    queries = _read_queries_jsonl(f)
    assert queries["q1"].text == "hello"
    assert queries["q2"].text == "world"
    assert queries["q1"].annotations == {}


def test_read_queries_jsonl_preserves_annotations(tmp_path):
    f = tmp_path / "queries.jsonl"
    f.write_text(
        '{"_id": "q1", "text": "hello", "expectedTerms": ["greeting"]}\n',
        encoding="utf-8",
    )
    queries = _read_queries_jsonl(f)
    assert queries["q1"].text == "hello"
    assert queries["q1"].annotations == {"expectedTerms": ["greeting"]}
