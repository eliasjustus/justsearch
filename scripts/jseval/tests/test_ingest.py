"""Tests for ingest.py — ingestion orchestration."""

from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock, call, patch

import pytest

from jseval.ingest import (
    HIGH_WATERMARK,
    LOW_WATERMARK,
    _SIDECAR,
    _ensure_materialized,
    _get_indexed_doc_count,
    _iter_corpus_jsonl,
    _source_signature,
    _wait_for_backpressure,
    _watcher_settle_timeout,
    add_watched_root,
    prepare_corpus,
)
from jseval.types import IngestConfig


# ---------------------------------------------------------------------------
# _watcher_settle_timeout [-1c]
# ---------------------------------------------------------------------------

class TestWatcherSettleTimeout:
    def test_zero_corpus(self):
        assert _watcher_settle_timeout(0) == 30.0

    def test_small_corpus(self):
        assert _watcher_settle_timeout(1000) == 30.0  # 1000/100 = 10 < 30

    def test_medium_corpus(self):
        assert _watcher_settle_timeout(5000) == 50.0  # 5000/100 = 50

    def test_large_corpus(self):
        assert _watcher_settle_timeout(50000) == 300.0  # capped at max

    def test_negative_corpus(self):
        assert _watcher_settle_timeout(-1) == 30.0


# ---------------------------------------------------------------------------
# add_watched_root
# ---------------------------------------------------------------------------

@patch("jseval.ingest.httpx.Client")
def test_add_watched_root(MockClient, tmp_path):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)
    mock_client.post.return_value = MagicMock(status_code=200)
    mock_client.post.return_value.raise_for_status = MagicMock()

    add_watched_root("http://localhost:8080", tmp_path)

    mock_client.post.assert_called_once()
    args, kwargs = mock_client.post.call_args
    assert args[0] == "/api/indexing/roots"
    assert "path" in kwargs["json"]


# ---------------------------------------------------------------------------
# _get_indexed_doc_count
# ---------------------------------------------------------------------------

@patch("jseval.ingest.httpx.Client")
def test_get_indexed_doc_count(MockClient):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)
    resp = MagicMock()
    resp.json.return_value = {"indexedDocuments": 500}
    resp.raise_for_status = MagicMock()
    mock_client.get.return_value = resp

    count = _get_indexed_doc_count("http://localhost:8080")
    assert count == 500


@patch("jseval.ingest.httpx.Client")
def test_get_indexed_doc_count_on_error(MockClient):
    mock_client = MagicMock()
    MockClient.return_value.__enter__ = MagicMock(return_value=mock_client)
    MockClient.return_value.__exit__ = MagicMock(return_value=False)
    mock_client.get.side_effect = Exception("connection refused")

    count = _get_indexed_doc_count("http://localhost:8080")
    assert count == 0


# ---------------------------------------------------------------------------
# _wait_for_backpressure
# ---------------------------------------------------------------------------

def test_backpressure_no_wait():
    """Queue below high watermark → no waiting."""
    client = MagicMock()
    resp = MagicMock()
    resp.json.return_value = {"pendingJobs": 100}
    resp.raise_for_status = MagicMock()
    client.get.return_value = resp

    _wait_for_backpressure(client, HIGH_WATERMARK, LOW_WATERMARK, 0.01)
    # Should return immediately after one check
    assert client.get.call_count == 1


@patch("jseval.ingest.time.sleep")
def test_backpressure_waits_then_resumes(mock_sleep):
    """Queue above high watermark → wait until below low watermark."""
    client = MagicMock()

    responses = [
        {"pendingJobs": 95_000},  # Above high watermark
        {"pendingJobs": 80_000},  # Still above low watermark
        {"pendingJobs": 60_000},  # Below low watermark → resume
    ]
    resp_mocks = []
    for r in responses:
        m = MagicMock()
        m.json.return_value = r
        m.raise_for_status = MagicMock()
        resp_mocks.append(m)

    client.get.side_effect = resp_mocks

    _wait_for_backpressure(client, HIGH_WATERMARK, LOW_WATERMARK, 0.01)
    assert client.get.call_count == 3


# ---------------------------------------------------------------------------
# _iter_corpus_jsonl
# ---------------------------------------------------------------------------

def test_iter_corpus_jsonl(tmp_path):
    jsonl = tmp_path / "corpus.jsonl"
    jsonl.write_text(
        '{"_id": "d1", "text": "hello", "title": "T1"}\n'
        '{"_id": "d2", "text": "world"}\n'
        '\n',
        encoding="utf-8",
    )
    docs = list(_iter_corpus_jsonl(jsonl))
    assert len(docs) == 2
    assert docs[0]["_id"] == "d1"
    assert docs[1]["text"] == "world"


# ---------------------------------------------------------------------------
# prepare_corpus
# ---------------------------------------------------------------------------

@patch("jseval.ingest.ingest_and_wait")
@patch("jseval.materialize.materialize")
def test_prepare_corpus_beir_materializes(mock_materialize, mock_ingest, tmp_path, monkeypatch):
    """BEIR dataset with no corpus_dir triggers materialization to default path."""
    # Redirect default corpus dir to tmp_path so the directory doesn't pre-exist.
    monkeypatch.setattr(
        "jseval._paths.default_corpus_dir",
        lambda name: tmp_path / "tmp" / "eval-corpora" / name,
    )

    mock_materialize.return_value = 1
    mock_ingest.return_value = {"readiness_passed": True, "docs_indexed": 1}

    mock_ds = MagicMock()
    mock_ds.docs_iter.return_value = iter([])
    mock_ir = MagicMock()
    mock_ir.load.return_value = mock_ds

    import sys
    sys.modules["ir_datasets"] = mock_ir
    try:
        result = prepare_corpus(
            "scifact", IngestConfig(base_url="http://localhost:33221"),
            # No corpus_dir — uses default path, triggers materialization
        )
    finally:
        del sys.modules["ir_datasets"]

    assert mock_materialize.called
    assert mock_ingest.called
    assert result["readiness_passed"] is True


def test_prepare_corpus_explicit_dir_empty_raises(tmp_path):
    """Explicit --corpus-dir with no .txt files raises FileNotFoundError."""
    corpus_dir = tmp_path / "empty"
    corpus_dir.mkdir()

    with pytest.raises(FileNotFoundError, match=r"no \.txt/\.png files"):
        prepare_corpus("scifact", IngestConfig(base_url="http://localhost:33221"), corpus_dir=corpus_dir)


@patch("jseval.ingest.ingest_and_wait")
def test_prepare_corpus_explicit_dir_uses_existing(mock_ingest, tmp_path):
    """Explicit --corpus-dir with .txt files uses them without materializing."""
    corpus_dir = tmp_path / "existing"
    corpus_dir.mkdir()
    (corpus_dir / "doc1.txt").write_text("content")
    (corpus_dir / "doc2.txt").write_text("content")

    mock_ingest.return_value = {"readiness_passed": True, "docs_indexed": 0}

    result = prepare_corpus(
        "scifact", IngestConfig(base_url="http://localhost:33221"), corpus_dir=corpus_dir,
    )

    assert mock_ingest.called
    call_kwargs = mock_ingest.call_args
    # corpus_doc_count is now a keyword arg to ingest_and_wait
    assert call_kwargs.kwargs["corpus_doc_count"] == 2


# ---------------------------------------------------------------------------
# _ensure_materialized — the cache is a VERIFIED PROJECTION of the source
# (tempdoc 635 verification-binding; regression for the stale-cache nDCG-0.0 class)
# ---------------------------------------------------------------------------

def _seed_source(base: Path, name: str, ids: list[str]) -> None:
    import json
    src = base / name
    (src / "qrels").mkdir(parents=True, exist_ok=True)
    (src / "corpus.jsonl").write_text(
        "\n".join(json.dumps({"_id": i, "title": i, "text": f"body {i}"}) for i in ids),
        encoding="utf-8")
    (src / "qrels" / "test.tsv").write_text(
        "query-id\tcorpus-id\tscore\nq1\t" + ids[0] + "\t1\n", encoding="utf-8")


def test_ensure_materialized_reverifies_on_source_change(tmp_path, monkeypatch):
    """A regenerated source (new signature) must trigger re-materialization, and an unchanged
    source must reuse the cache — closing the stale-cache class structurally."""
    from jseval import corpora, materialize as mat
    from jseval.materialize import doc_id_to_filename

    base = tmp_path / "datasets"
    _seed_source(base, "golden/probe-x", ["a", "b"])
    monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)
    cache = tmp_path / "cache" / "golden" / "probe-x"

    # 1) empty cache → materialize + write sidecar (2 docs + sentinel)
    assert _ensure_materialized("golden/probe-x", cache, None) == 3
    sidecar = cache / _SIDECAR
    assert sidecar.is_file()
    sig1 = sidecar.read_text(encoding="utf-8").strip()

    # 2) unchanged source → REUSE (materialize not called again)
    calls: list[int] = []
    real = mat.materialize
    monkeypatch.setattr(mat, "materialize", lambda *a, **k: (calls.append(1), real(*a, **k))[1])
    assert _ensure_materialized("golden/probe-x", cache, None) == 3
    assert calls == []  # verified-identity reuse, no re-materialize

    # 3) mutate source (new ids → new signature) → RE-MATERIALIZE, stale .txt gone
    _seed_source(base, "golden/probe-x", ["c", "d", "e"])
    assert _ensure_materialized("golden/probe-x", cache, None) == 4  # 3 docs + sentinel
    assert calls == [1]  # re-materialized exactly once
    names = {p.name for p in cache.glob("*.txt")}
    assert doc_id_to_filename("a") not in names  # stale doc removed
    assert doc_id_to_filename("c") in names      # fresh doc present
    assert sidecar.read_text(encoding="utf-8").strip() != sig1  # sidecar updated


def test_source_signature_none_for_beir(monkeypatch):
    """BEIR/unknown datasets have no local source that can go stale → no sidecar, plain
    materialize-if-empty behaviour preserved."""
    assert _source_signature("beir/scifact") is None
    assert _source_signature("anything-else") is None


# ---------------------------------------------------------------------------
# _ensure_materialized / _materialize_into — scan-axis regression (tempdoc 624 follow-up)
#
# `corpus_build.build_golden` already renders `axis="scan"` docs as `.png` in its OWN
# `corpus-dir/` (covered by test_corpus_governance.py's `test_build_golden_materializes_
# scan_docs_as_png`). That coverage did NOT catch this bug: `jseval run`'s real ingestion
# path is `prepare_corpus` -> `_ensure_materialized` -> `_materialize_into`, which reads
# `corpus.jsonl` directly and used to call `materialize.materialize()` without ever
# checking `type_axis` — silently substituting plain ground-truth `.txt` files for the
# degraded `.png` scans the dataset exists to test. This test exercises THAT path.
# ---------------------------------------------------------------------------

def test_ensure_materialized_renders_scan_docs_as_png(tmp_path, monkeypatch):
    """A golden `axis="scan"` dataset materialized via `_ensure_materialized` (the function
    `prepare_corpus`/`jseval run` actually calls) must produce real `.png` files, not `.txt`
    ground-truth substitutes, for every document."""
    pytest.importorskip("PIL")
    import json

    from jseval import corpora, corpus_build
    from jseval import corpus_generate as cg
    from jseval.materialize import doc_id_to_filename

    src = tmp_path / "src"
    cg.generate(src, axis="scan", n_chains=2, hops=1, distractor_ratio=1, doc_words=40, seed=5)
    docs = [json.loads(line) for line in (src / "docs.jsonl").read_text(encoding="utf-8").splitlines()]

    base = tmp_path / "datasets"
    ds = base / "golden" / "scan-ingest-x"
    corpus_build.build_golden(src, ds, now="2026-07-02")
    monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)

    cache = tmp_path / "cache" / "golden" / "scan-ingest-x"
    n = _ensure_materialized("golden/scan-ingest-x", cache, None)

    assert n == len(docs) + 1  # + sentinel
    for d in docs:
        png_path = cache / doc_id_to_filename(d["_id"], ext="png")
        assert png_path.is_file(), f"{d['_id']} was materialized as .txt, not .png — scan axis was dropped"
        assert png_path.read_bytes().startswith(b"\x89PNG"), f"{d['_id']}.png is not a real PNG"
        assert not (cache / doc_id_to_filename(d["_id"])).exists()  # no stray .txt

    sentinel = cache / doc_id_to_filename("__jseval_sentinel__")
    assert sentinel.is_file()  # sentinel is unaffected — always plain .txt


def test_ensure_materialized_plain_axis_unaffected(tmp_path, monkeypatch):
    """A `type_axis` other than "scan" (or missing metadata.json entirely) must keep the
    pre-existing plain-.txt materialization — this fix is additive, not axis-blind."""
    from jseval import corpora

    base = tmp_path / "datasets"
    _seed_source(base, "golden/plain-x", ["a", "b"])
    monkeypatch.setattr(corpora, "_default_base_dir", lambda: base)

    cache = tmp_path / "cache" / "golden" / "plain-x"
    n = _ensure_materialized("golden/plain-x", cache, None)

    assert n == 3  # 2 docs + sentinel
    assert len(list(cache.glob("*.txt"))) == 3
    assert not list(cache.glob("*.png"))
