"""Tests for corpus_fetch.py — deterministic sampling of real external IR datasets (tempdoc 666).

Mocks the external data sources (ir_datasets, HTTP) to test the pure sampling/parsing logic without
needing network access or multi-GB downloads in the unit test suite. Live end-to-end verification against
the real sources happens separately, matching this tempdoc family's established discipline.
"""

from __future__ import annotations

import gzip
import json
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

from jseval import corpus_fetch


def _miracl_query(qid, text):
    return SimpleNamespace(query_id=qid, text=text)


def _miracl_qrel(qid, doc_id, relevance):
    return SimpleNamespace(query_id=qid, doc_id=doc_id, relevance=relevance)


def _miracl_doc(doc_id, title, text):
    return SimpleNamespace(doc_id=doc_id, title=title, text=text)


def test_fetch_miracl_sample_keeps_all_qrelled_docs_and_samples_distractors(tmp_path):
    queries = [_miracl_query("q1", "query one"), _miracl_query("q2", "query two")]
    qrels = [_miracl_qrel("q1", "d1", 1), _miracl_qrel("q2", "d2", 1)]
    docs = [_miracl_doc("d1", "T1", "text one"), _miracl_doc("d2", "T2", "text two")]
    docs += [_miracl_doc(f"x{i}", f"X{i}", f"distractor {i}") for i in range(10)]

    fake_ds = MagicMock()
    fake_ds.queries_iter.return_value = iter(queries)
    fake_ds.qrels_iter.return_value = iter(qrels)
    fake_ds.docs_iter.return_value = iter(docs)

    with patch("ir_datasets.load", return_value=fake_ds):
        prov = corpus_fetch.fetch_miracl_sample(tmp_path, lang="de", seed=1, n_docs=5)

    assert prov["source"] == "miracl/de/dev"
    assert prov["n_queries"] == 2
    assert prov["n_docs"] == 5  # 2 qrelled + 3 sampled distractors

    # tempdoc 666 fourth-pass regression guard: build_golden() only threads through
    # meta["generation_provenance"] into the materialized metadata.json (confirmed live: an earlier version
    # of this module wrote a differently-named "fetch_provenance" key, which build_golden() silently
    # dropped). Assert on the actual on-disk meta.json key, not just the returned dict, since that's
    # precisely what let the original bug through undetected.
    written_meta = json.loads((tmp_path / "meta.json").read_text(encoding="utf-8"))
    assert written_meta["generation_provenance"] == prov
    assert "suite" not in written_meta  # these are real external corpora, not tempdoc-635 suite members

    written_docs = [json.loads(l) for l in (tmp_path / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    written_ids = {d["_id"] for d in written_docs}
    assert {"d1", "d2"} <= written_ids  # qrelled docs always kept
    assert len(written_docs) == 5

    queries_out = json.loads((tmp_path / "queries.json").read_text(encoding="utf-8"))
    assert {q["evidence_ids"][0] for q in queries_out} == {"d1", "d2"}


def test_fetch_miracl_sample_is_deterministic_across_two_calls(tmp_path):
    queries = [_miracl_query("q1", "query one")]
    qrels = [_miracl_qrel("q1", "d1", 1)]
    docs = [_miracl_doc("d1", "T1", "text one")]
    docs += [_miracl_doc(f"x{i}", f"X{i}", f"distractor {i}") for i in range(30)]

    fake_ds = MagicMock()
    fake_ds.queries_iter.side_effect = lambda: iter(queries)
    fake_ds.qrels_iter.side_effect = lambda: iter(qrels)
    fake_ds.docs_iter.side_effect = lambda: iter(docs)

    out1, out2 = tmp_path / "run1", tmp_path / "run2"
    with patch("ir_datasets.load", return_value=fake_ds):
        corpus_fetch.fetch_miracl_sample(out1, lang="de", seed=42, n_docs=6)
        corpus_fetch.fetch_miracl_sample(out2, lang="de", seed=42, n_docs=6)

    assert (out1 / "docs.jsonl").read_text(encoding="utf-8") == (out2 / "docs.jsonl").read_text(encoding="utf-8")


def test_fetch_miracl_sample_only_keeps_queries_with_a_positive_qrel(tmp_path):
    # q2 has no qrel entry -> must be dropped, not written with an empty evidence list.
    queries = [_miracl_query("q1", "query one"), _miracl_query("q2", "unjudged query")]
    qrels = [_miracl_qrel("q1", "d1", 1)]
    docs = [_miracl_doc("d1", "T1", "text one")]

    fake_ds = MagicMock()
    fake_ds.queries_iter.return_value = iter(queries)
    fake_ds.qrels_iter.return_value = iter(qrels)
    fake_ds.docs_iter.return_value = iter(docs)

    with patch("ir_datasets.load", return_value=fake_ds):
        prov = corpus_fetch.fetch_miracl_sample(tmp_path, lang="fr", seed=1, n_docs=1)

    assert prov["n_queries"] == 1
    queries_out = json.loads((tmp_path / "queries.json").read_text(encoding="utf-8"))
    assert len(queries_out) == 1
    assert queries_out[0]["query"] == "query one"


def _fake_clerc_urlopen(url, **_kwargs):
    """Route CLERC's three fetch URLs to small, fixed fixture content."""
    if url.endswith("qrels-doc.test.direct.tsv"):
        body = "q1\t0\tdocA\t1\nq2\t0\tdocB\t1\nq3\t0\tdocC\t1\n"
        return MagicMock(__enter__=lambda s: MagicMock(read=lambda: body.encode("utf-8")),
                          __exit__=lambda *a: None)
    if url.endswith("test.single-removed.direct.tsv"):
        body = "q1\tfirst query text\nq2\tsecond query text\nq3\tthird query text\n"
        return MagicMock(__enter__=lambda s: MagicMock(read=lambda: body.encode("utf-8")),
                          __exit__=lambda *a: None)
    if url.endswith("collection.doc.tsv.gz"):
        raw = "docA\ttext for doc A\ndocB\ttext for doc B\ndocC\ttext for doc C\ndocD\tirrelevant\n"
        gz_bytes = gzip.compress(raw.encode("utf-8"))
        # Return the still-*compressed* bytes stream -- fetch_clerc_sample wraps this itself in
        # gzip.GzipFile(fileobj=...) to decompress, exactly like the real urlopen() response would be.
        raw_stream = __import__("io").BytesIO(gz_bytes)
        return MagicMock(__enter__=lambda s: raw_stream, __exit__=lambda *a: None)
    raise AssertionError(f"unexpected URL: {url}")


def test_fetch_clerc_sample_deterministic_and_uses_only_direct_qrels(tmp_path):
    class _FakeReq:
        def __init__(self, url, headers=None):
            self.url = url

    with patch("jseval.corpus_fetch.Request", _FakeReq), \
         patch("jseval.corpus_fetch.urlopen", side_effect=lambda req, timeout=None: _fake_clerc_urlopen(req.url)):
        prov = corpus_fetch.fetch_clerc_sample(tmp_path, seed=7, n_queries=2)

    assert prov["n_queries"] == 2
    queries_out = json.loads((tmp_path / "queries.json").read_text(encoding="utf-8"))
    docs_out = [json.loads(l) for l in (tmp_path / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    doc_ids = {d["_id"] for d in docs_out}
    # docD is never referenced by any qrel -> must not be pulled in, confirming the filter works.
    assert "docD" not in doc_ids

    # tempdoc 666 fourth-pass regression guard (same as the MIRACL test above).
    written_meta = json.loads((tmp_path / "meta.json").read_text(encoding="utf-8"))
    assert written_meta["generation_provenance"] == prov
    assert "suite" not in written_meta
    assert all(qid in {"docA", "docB", "docC"} for q in queries_out for qid in q["evidence_ids"])
