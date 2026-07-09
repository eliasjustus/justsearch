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


def _fake_clerc_urlopen(url, *, n_distractor_docs=1, **_kwargs):
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
        raw = "docA\ttext for doc A\ndocB\ttext for doc B\ndocC\ttext for doc C\n"
        raw += "".join(f"docD{i}\tirrelevant {i}\n" for i in range(n_distractor_docs))
        gz_bytes = gzip.compress(raw.encode("utf-8"))
        # Return the still-*compressed* bytes stream -- fetch_clerc_sample wraps this itself in
        # gzip.GzipFile(fileobj=...) to decompress, exactly like the real urlopen() response would be.
        raw_stream = __import__("io").BytesIO(gz_bytes)
        return MagicMock(__enter__=lambda s: raw_stream, __exit__=lambda *a: None)
    raise AssertionError(f"unexpected URL: {url}")


class _FakeReq:
    def __init__(self, url, headers=None):
        self.url = url


def _patched_clerc(*, n_distractor_docs=1):
    return (
        patch("jseval.corpus_fetch.Request", _FakeReq),
        patch("jseval.corpus_fetch.urlopen",
              side_effect=lambda req, timeout=None: _fake_clerc_urlopen(
                  req.url, n_distractor_docs=n_distractor_docs)),
    )


def test_fetch_clerc_sample_deterministic_and_uses_only_direct_qrels(tmp_path):
    with patch("jseval.corpus_fetch.Request", _FakeReq), \
         patch("jseval.corpus_fetch.urlopen", side_effect=lambda req, timeout=None: _fake_clerc_urlopen(req.url)):
        prov = corpus_fetch.fetch_clerc_sample(tmp_path, seed=7, n_queries=2)

    assert prov["n_queries"] == 2
    queries_out = json.loads((tmp_path / "queries.json").read_text(encoding="utf-8"))
    docs_out = [json.loads(l) for l in (tmp_path / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    doc_ids = {d["_id"] for d in docs_out}
    # docD0 is never referenced by any qrel -> must not be pulled in, confirming the filter works.
    assert "docD0" not in doc_ids
    assert "n_docs_requested" not in prov  # n_docs=None byte-compatibility: no new keys added
    assert "n_distractors" not in prov

    # tempdoc 666 fourth-pass regression guard (same as the MIRACL test above).
    written_meta = json.loads((tmp_path / "meta.json").read_text(encoding="utf-8"))
    assert written_meta["generation_provenance"] == prov
    assert "suite" not in written_meta
    assert all(qid in {"docA", "docB", "docC"} for q in queries_out for qid in q["evidence_ids"])


def test_fetch_clerc_sample_n_docs_none_is_byte_compatible_with_prior_behavior(tmp_path):
    """The exact provenance shape (keys + values) that existed before n_docs was added -- guards the
    committed `666-corpora/legal-clerc-200/recipe.json` reproduction path (tempdoc 624 R-scale-corpus)."""
    with patch("jseval.corpus_fetch.Request", _FakeReq), \
         patch("jseval.corpus_fetch.urlopen", side_effect=lambda req, timeout=None: _fake_clerc_urlopen(req.url)):
        prov = corpus_fetch.fetch_clerc_sample(tmp_path, seed=7, n_queries=2)

    assert set(prov.keys()) == {"method", "source", "seed", "n_docs", "n_queries"}
    assert prov["method"] == "huggingface-direct-sample"
    assert prov["seed"] == 7
    assert prov["n_docs"] == 2  # only qrelled docs for the 2 sampled queries


def test_fetch_clerc_sample_keeps_all_qrelled_docs_and_samples_distractors(tmp_path):
    p1, p2 = _patched_clerc(n_distractor_docs=20)
    with p1, p2:
        prov = corpus_fetch.fetch_clerc_sample(tmp_path, seed=7, n_queries=2, n_docs=5)

    assert prov["n_queries"] == 2
    assert prov["n_docs"] == 5  # qrelled + sampled distractors
    assert prov["n_docs_requested"] == 5
    assert prov["n_distractors"] == 3  # 5 - 2 qrelled

    docs_out = [json.loads(l) for l in (tmp_path / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    doc_ids = {d["_id"] for d in docs_out}
    assert len(docs_out) == 5
    queries_out = json.loads((tmp_path / "queries.json").read_text(encoding="utf-8"))
    qrelled_ids = {did for q in queries_out for did in q["evidence_ids"]}
    assert qrelled_ids <= doc_ids
    distractor_ids = doc_ids - qrelled_ids
    assert len(distractor_ids) == 3
    assert all(did.startswith("docD") for did in distractor_ids)  # distractors exclude qrelled docs


def test_fetch_clerc_sample_qrel_set_is_invariant_to_n_docs(tmp_path):
    """Same seed + same n_queries must select the same qrelled doc set (and query set) regardless of
    n_docs -- distractor reservoir sampling uses its own rng instance so it can never perturb query
    sampling, which runs first and only draws from `rng`."""
    p1, p2 = _patched_clerc(n_distractor_docs=20)
    with p1, p2:
        prov_none = corpus_fetch.fetch_clerc_sample(tmp_path / "none", seed=7, n_queries=2)
    p1, p2 = _patched_clerc(n_distractor_docs=20)
    with p1, p2:
        prov_5 = corpus_fetch.fetch_clerc_sample(tmp_path / "five", seed=7, n_queries=2, n_docs=5)
    p1, p2 = _patched_clerc(n_distractor_docs=20)
    with p1, p2:
        prov_10 = corpus_fetch.fetch_clerc_sample(tmp_path / "ten", seed=7, n_queries=2, n_docs=10)

    queries_none = json.loads((tmp_path / "none" / "queries.json").read_text(encoding="utf-8"))
    queries_5 = json.loads((tmp_path / "five" / "queries.json").read_text(encoding="utf-8"))
    queries_10 = json.loads((tmp_path / "ten" / "queries.json").read_text(encoding="utf-8"))

    def _qrel_shape(queries_out):
        return sorted((q["query"], tuple(sorted(q["evidence_ids"]))) for q in queries_out)

    assert _qrel_shape(queries_none) == _qrel_shape(queries_5) == _qrel_shape(queries_10)
    assert prov_none["n_queries"] == prov_5["n_queries"] == prov_10["n_queries"] == 2


def test_fetch_clerc_sample_distractors_deterministic_across_two_runs(tmp_path):
    p1, p2 = _patched_clerc(n_distractor_docs=20)
    with p1, p2:
        corpus_fetch.fetch_clerc_sample(tmp_path / "run1", seed=13, n_queries=1, n_docs=4)
    p1, p2 = _patched_clerc(n_distractor_docs=20)
    with p1, p2:
        corpus_fetch.fetch_clerc_sample(tmp_path / "run2", seed=13, n_queries=1, n_docs=4)

    docs1 = (tmp_path / "run1" / "docs.jsonl").read_text(encoding="utf-8")
    docs2 = (tmp_path / "run2" / "docs.jsonl").read_text(encoding="utf-8")
    assert docs1 == docs2
