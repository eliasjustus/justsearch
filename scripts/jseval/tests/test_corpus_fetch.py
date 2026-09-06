"""Tests for corpus_fetch.py — deterministic sampling of real external IR datasets (tempdoc 666).

Mocks the external data sources (ir_datasets, HTTP) to test the pure sampling/parsing logic without
needing network access or multi-GB downloads in the unit test suite. Live end-to-end verification against
the real sources happens separately, matching this tempdoc family's established discipline.
"""

from __future__ import annotations

from collections import Counter
import gzip
import hashlib
import io
import json
import os
import tarfile
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


# ---------------------------------------------------------------------------
# Shared dataset-fetch cache integration (tempdoc 709)
# ---------------------------------------------------------------------------

def test_fetch_clerc_sample_raw_fetch_is_cached_across_different_seeds(tmp_path, monkeypatch):
    """A second `fetch_clerc_sample` call (even a different seed/n_queries/n_docs) must reuse
    the already-fetched raw CLERC artifacts from the shared cache rather than re-fetching them
    over the network -- the whole point of caching at the raw layer instead of the sampled-
    output layer (709 pinned constraint e)."""
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path / "cache"))
    out_dir = tmp_path / "out"

    p1, p2 = _patched_clerc(n_distractor_docs=20)
    with p1, p2:
        prov1 = corpus_fetch.fetch_clerc_sample(out_dir / "one", seed=7, n_queries=2, n_docs=5)

    # Second call: patch urlopen to explode if invoked -- the raw fetch must come entirely
    # from the shared cache this time, not the network.
    def _urlopen_must_not_be_called(*args, **kwargs):
        raise AssertionError("urlopen() must not be called on a cached raw-fetch hit")

    with patch("jseval.corpus_fetch.Request", _FakeReq), \
         patch("jseval.corpus_fetch.urlopen", side_effect=_urlopen_must_not_be_called):
        prov2 = corpus_fetch.fetch_clerc_sample(out_dir / "two", seed=99, n_queries=3, n_docs=5)

    assert prov1["n_docs"] == prov2["n_docs"] == 5
    docs2 = [json.loads(l) for l in (out_dir / "two" / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    assert len(docs2) == 5

    # The raw cache entry itself is on disk with a verified signature.
    cache_dir = tmp_path / "cache" / "clerc-raw"
    assert cache_dir.is_dir()
    entries = list(cache_dir.iterdir())
    assert len(entries) == 1
    assert (entries[0] / "collection.doc.tsv.gz").is_file()
    assert (entries[0] / "signature.json").is_file()


def test_fetch_miracl_sample_sets_ir_datasets_home_when_cache_enabled(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path))
    monkeypatch.delenv("IR_DATASETS_HOME", raising=False)

    queries = [_miracl_query("q1", "query one")]
    qrels = [_miracl_qrel("q1", "d1", 1)]
    docs = [_miracl_doc("d1", "T1", "text one")]
    fake_ds = MagicMock()
    fake_ds.queries_iter.return_value = iter(queries)
    fake_ds.qrels_iter.return_value = iter(qrels)
    fake_ds.docs_iter.return_value = iter(docs)

    with patch("ir_datasets.load", return_value=fake_ds):
        corpus_fetch.fetch_miracl_sample(tmp_path / "out", lang="de", seed=1, n_docs=1)

    assert __import__("os").environ["IR_DATASETS_HOME"] == str(tmp_path / "ir_datasets")


# ---------------------------------------------------------------------------
# fetch_enron_raw_sample -- raw CMU Enron maildir corpus (tempdoc 707 ratified pivot)
# ---------------------------------------------------------------------------

def _maildir_message(*, subject: str | None, body: str) -> bytes:
    """A tiny fake RFC-822 maildir message: headers, a blank line, then the body -- exactly
    the shape `_split_email_headers` parses."""
    headers = "Message-ID: <1.fake@example.com>\nDate: Mon, 1 Jan 2001 00:00:00 -0800\n"
    if subject is not None:
        headers += f"Subject: {subject}\n"
    return (headers + "\n" + body).encode("utf-8")


_NORMAL_BODY = ("This is a normal length body with enough words to pass the minimum word "
                 "filter threshold easily today for sure. " * 3).strip()
_NO_SUBJECT_BODY = ("No subject header on this body but plenty of words to pass the sample "
                     "filter threshold check for real today. " * 3).strip()
_ANOTHER_BODY = ("Another distinct body with plenty of words for the sample gate word filter "
                  "threshold check yes indeed today. " * 3).strip()
_FIFTH_BODY = ("Fifth distinct message body containing more than the minimum number of words "
                "for inclusion in the sample today. " * 3).strip()


def _fake_enron_entries() -> dict[str, bytes]:
    return {
        "maildir/allen-p/inbox/2.": _maildir_message(subject="Too short", body="Too short."),
        "maildir/kaminski-v/all/1.": _maildir_message(subject="Fifth message", body=_FIFTH_BODY),
        "maildir/allen-p/inbox/1.": _maildir_message(subject="Meeting notes", body=_NORMAL_BODY),
        "maildir/bass-e/sent/2.": _maildir_message(subject="Another topic", body=_ANOTHER_BODY),
        "maildir/allen-p/inbox/3.": _maildir_message(subject="Duplicate of one", body=_NORMAL_BODY),
        "maildir/bass-e/sent/1.": _maildir_message(subject=None, body=_NO_SUBJECT_BODY),
    }


def _build_fake_enron_tar_gz() -> bytes:
    """~6 fake maildir entries exercising every filter `_sample_enron_from_raw` applies:
    one normal message, one too-short body, one exact-duplicate body (different subject --
    dedup is on body content, not subject), one with no Subject header (title-fallback path),
    and two more distinct normal messages. Member names are deliberately NOT pre-sorted (tar
    write order != alphabetical) so a passing test proves the sampler sorts by name itself."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for name, content in _fake_enron_entries().items():
            info = tarfile.TarInfo(name=name)
            info.size = len(content)
            tar.addfile(info, io.BytesIO(content))
    return buf.getvalue()


def _patched_enron(tar_gz_bytes: bytes):
    def _urlopen(req, timeout=None):
        if req.url != corpus_fetch._ENRON_URL:
            raise AssertionError(f"unexpected URL: {req.url}")
        stream = io.BytesIO(tar_gz_bytes)
        return MagicMock(__enter__=lambda s: stream, __exit__=lambda *a: None)

    return (
        patch("jseval.corpus_fetch.Request", _FakeReq),
        patch("jseval.corpus_fetch.urlopen", side_effect=_urlopen),
    )


def test_iter_enron_source_stages_exposes_prefilter_counts_and_duplicate_links():
    messages = sorted(_fake_enron_entries().items())
    events = list(corpus_fetch.iter_enron_source_stages(messages, min_words=10))

    assert Counter(event.stage for event in events) == {
        "raw_member": 6,
        "parsed_body": 6,
        "eligible_body": 5,
        "retained_body": 4,
    }
    duplicate = next(
        event for event in events
        if event.stage == "eligible_body" and event.member_name == "maildir/allen-p/inbox/3."
    )
    assert duplicate.duplicate_of == "maildir/allen-p/inbox/1."
    assert duplicate.body_sha256 == hashlib.sha256(_NORMAL_BODY.encode("utf-8")).hexdigest()
    assert [event.member_name for event in events if event.stage == "retained_body"] == [
        "maildir/allen-p/inbox/1.",
        "maildir/bass-e/sent/1.",
        "maildir/bass-e/sent/2.",
        "maildir/kaminski-v/all/1.",
    ]


def test_iter_enron_source_stages_preserves_decode_and_line_normalization():
    raw = b"Subject: Replacement\r\n\r\nfirst\xff second third"
    events = list(corpus_fetch.iter_enron_source_stages([("maildir/example/1.", raw)], min_words=3))

    assert [event.stage for event in events] == [
        "raw_member", "parsed_body", "eligible_body", "retained_body",
    ]
    assert events[-1].subject == "Replacement"
    assert events[-1].body == "first\ufffd second third"


def test_fetch_enron_raw_sample_is_deterministic_across_two_calls(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path / "cache"))
    tar_bytes = _build_fake_enron_tar_gz()

    p1, p2 = _patched_enron(tar_bytes)
    with p1, p2:
        prov1 = corpus_fetch.fetch_enron_raw_sample(tmp_path / "one", seed=7, n_docs=3, min_words=10)

    # Second call: patch urlopen to explode if invoked -- the raw fetch must come entirely from
    # the shared cache (tempdoc 709 pattern, mirroring the CLERC cache test above), not the network.
    def _urlopen_must_not_be_called(*args, **kwargs):
        raise AssertionError("urlopen() must not be called on a cached raw-fetch hit")

    with patch("jseval.corpus_fetch.Request", _FakeReq), \
         patch("jseval.corpus_fetch.urlopen", side_effect=_urlopen_must_not_be_called):
        prov2 = corpus_fetch.fetch_enron_raw_sample(tmp_path / "two", seed=7, n_docs=3, min_words=10)

    assert prov1 == prov2
    docs1 = (tmp_path / "one" / "docs.jsonl").read_bytes()
    docs2 = (tmp_path / "two" / "docs.jsonl").read_bytes()
    assert docs1 == docs2
    assert len(docs1) > 0


def test_fetch_enron_raw_sample_strips_headers_filters_and_dedupes(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path / "cache"))
    tar_bytes = _build_fake_enron_tar_gz()

    # n_docs=4 -- exactly the number of eligible (post min-words, post-dedup) entries -- so the
    # reservoir keeps all of them deterministically, letting this test assert on the full set.
    p1, p2 = _patched_enron(tar_bytes)
    with p1, p2:
        prov = corpus_fetch.fetch_enron_raw_sample(tmp_path / "out", seed=1, n_docs=4, min_words=10)

    docs = [json.loads(l) for l in (tmp_path / "out" / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    doc_ids = {d["_id"] for d in docs}

    assert len(docs) == 4  # 6 raw entries - 1 too-short - 1 exact-duplicate body = 4 eligible
    assert prov["n_docs"] == 4
    assert prov["stage_counts"] == {
        "raw_member": 6,
        "parsed_body": 6,
        "eligible_body": 5,
        "retained_body": 4,
    }
    assert "maildir__allen-p__inbox__2." not in doc_ids  # too-short body, filtered by min_words
    assert "maildir__allen-p__inbox__3." not in doc_ids  # exact-duplicate body, filtered by dedup
    assert "maildir__allen-p__inbox__1." in doc_ids  # the original (first in sorted order) survives

    # Header stripping: no header field leaks into any sampled body.
    assert all("Message-ID" not in d["text"] for d in docs)
    assert all("Date:" not in d["text"] for d in docs)

    # Title: Subject header when present, else first-8-words fallback (the no-subject entry).
    no_subject_doc = next(d for d in docs if d["_id"] == "maildir__bass-e__sent__1.")
    assert no_subject_doc["title"] == " ".join(_NO_SUBJECT_BODY.split()[:8])
    subject_doc = next(d for d in docs if d["_id"] == "maildir__allen-p__inbox__1.")
    assert subject_doc["title"] == "Meeting notes"

    # 707 distractor-mass-only contract: no queries/qrels of any kind are sampled or written.
    queries_out = json.loads((tmp_path / "out" / "queries.json").read_text(encoding="utf-8"))
    assert queries_out == []

    written_meta = json.loads((tmp_path / "out" / "meta.json").read_text(encoding="utf-8"))
    assert written_meta["generation_provenance"] == prov
    assert "suite" not in written_meta

    assert prov["method"] == "cmu-enron-raw-sample"
    assert prov["source"] == corpus_fetch._ENRON_URL
    assert prov["seed"] == 1
    assert prov["min_words"] == 10
    assert prov["license"] == "LicenseRef-Enron-FERC-public-record"
    assert prov["raw_source_signature"] == hashlib.sha256(tar_bytes).hexdigest()

    expected_docs = [
        {"_id": "maildir__allen-p__inbox__1.", "title": "Meeting notes", "text": _NORMAL_BODY},
        {
            "_id": "maildir__bass-e__sent__1.",
            "title": " ".join(_NO_SUBJECT_BODY.split()[:8]),
            "text": _NO_SUBJECT_BODY,
        },
        {"_id": "maildir__bass-e__sent__2.", "title": "Another topic", "text": _ANOTHER_BODY},
        {"_id": "maildir__kaminski-v__all__1.", "title": "Fifth message", "text": _FIFTH_BODY},
    ]
    expected_bytes = "".join(
        json.dumps(doc, ensure_ascii=False) + os.linesep for doc in expected_docs
    ).encode("utf-8")
    assert (tmp_path / "out" / "docs.jsonl").read_bytes() == expected_bytes


def test_fetch_enron_raw_sample_reservoir_is_seed_dependent_but_reproducible(tmp_path, monkeypatch):
    """n_docs smaller than the eligible pool actually exercises reservoir replacement (not just
    'keep everything') -- two different seeds must be allowed to pick different subsets, but the
    same seed must always reproduce the same subset (covered by the determinism test above)."""
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path / "cache"))
    tar_bytes = _build_fake_enron_tar_gz()

    p1, p2 = _patched_enron(tar_bytes)
    with p1, p2:
        prov_a = corpus_fetch.fetch_enron_raw_sample(tmp_path / "a", seed=1, n_docs=2, min_words=10)
    p1, p2 = _patched_enron(tar_bytes)
    with p1, p2:
        prov_b = corpus_fetch.fetch_enron_raw_sample(tmp_path / "b", seed=99, n_docs=2, min_words=10)

    assert prov_a["n_docs"] == prov_b["n_docs"] == 2
    docs_a = [json.loads(l) for l in (tmp_path / "a" / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    docs_b = [json.loads(l) for l in (tmp_path / "b" / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    assert {d["_id"] for d in docs_a} <= {
        "maildir__allen-p__inbox__1.", "maildir__bass-e__sent__1.",
        "maildir__bass-e__sent__2.", "maildir__kaminski-v__all__1.",
    }
    assert {d["_id"] for d in docs_b} <= {
        "maildir__allen-p__inbox__1.", "maildir__bass-e__sent__1.",
        "maildir__bass-e__sent__2.", "maildir__kaminski-v__all__1.",
    }
    assert [d["_id"] for d in docs_a] == [
        "maildir__bass-e__sent__1.", "maildir__kaminski-v__all__1.",
    ]
    assert [d["_id"] for d in docs_b] == [
        "maildir__allen-p__inbox__1.", "maildir__bass-e__sent__2.",
    ]


def test_corpus_fetch_enron_raw_end_to_end_builds_zero_query_mixed_pool(tmp_path, monkeypatch):
    """The full `corpus-fetch-enron-raw` CLI path: fetch -> `corpus_build.build_golden` (a
    0-query source -- the least-forked shape for a distractor-mass-only pool, tempdoc 707) ->
    a committed 666-corpora recipe. `REPO_ROOT` is patched so the recipe write (hardcoded under
    the real repo root, no --datasets-dir override) lands in a scratch dir, never the checked-in
    tree, this being a unit test."""
    from click.testing import CliRunner

    from jseval.cli import main

    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path / "cache"))
    monkeypatch.setattr("jseval._paths.REPO_ROOT", tmp_path / "scratch-repo")
    tar_bytes = _build_fake_enron_tar_gz()

    runner = CliRunner()
    p1, p2 = _patched_enron(tar_bytes)
    with p1, p2:
        result = runner.invoke(main, [
            "corpus-fetch-enron-raw", "--name", "test-enron-pool", "--seed", "7",
            "--n-docs", "3", "--min-words", "10",
            "--datasets-dir", str(tmp_path / "datasets"),
        ])
    assert result.exit_code == 0, result.output

    mixed_dir = tmp_path / "datasets" / "mixed" / "test-enron-pool"
    corpus_docs = [json.loads(l) for l in (mixed_dir / "corpus.jsonl").read_text(encoding="utf-8").splitlines()]
    assert len(corpus_docs) == 3

    metadata = json.loads((mixed_dir / "metadata.json").read_text(encoding="utf-8"))
    assert metadata["query_count"] == 0
    assert metadata["corpus_size"] == 3

    # An empty queries.jsonl and a header-only qrels/test.tsv -- build_golden's existing,
    # unforked handling of a 0-query source (verified directly, not just assumed).
    assert (mixed_dir / "queries.jsonl").read_text(encoding="utf-8") == ""
    assert (mixed_dir / "qrels" / "test.tsv").read_text(encoding="utf-8") == "query-id\tcorpus-id\tscore\n"

    recipe_path = tmp_path / "scratch-repo" / "scripts" / "jseval" / "666-corpora" / "test-enron-pool" / "recipe.json"
    assert recipe_path.is_file()
    recipe_bytes = recipe_path.read_bytes()
    assert b"\r" not in recipe_bytes  # committed-artifact convention: LF-only, never CRLF
    recipe = json.loads(recipe_bytes.decode("utf-8"))
    assert recipe["method"] == "cmu-enron-raw-sample"
    assert recipe["n_docs"] == 3


def test_corpus_fetch_enron_raw_is_registered_in_cli_help():
    from click.testing import CliRunner

    from jseval.cli import main

    runner = CliRunner()
    result = runner.invoke(main, ["--help"])
    assert result.exit_code == 0
    assert "corpus-fetch-enron-raw" in result.output
