"""Fetch + deterministically sample real external IR datasets into this project's committed corpus
source shape (tempdoc 666).

Neither corpus's *materialized* content is ever committed here — `datasets/` is wholesale gitignored,
this project's existing, universal policy for every corpus regardless of source (confirmed via
`.gitignore:211`; the original `mixed/courtlistener-200` files found in the private archive's history
were themselves stripped out during the public-release cutover, never present in this repo). What's
committed instead is a small **recipe** (mirroring `corpus_generate.py`'s `generation_provenance`
discipline): source id, seed, and target sizes — see `scripts/jseval/666-corpora/<name>/recipe.json`.
Re-running the recipe re-fetches and re-samples deterministically; nothing about the corpus content
itself needs to be preserved in git.

The recorded provenance is written under the `generation_provenance` key (not a differently-named one) so
`corpus_build.build_golden()` — which only threads through `src_meta.get("generation_provenance")` — actually
carries it into the materialized `metadata.json` rather than silently dropping it (tempdoc 666, fourth-pass
fix). Deliberately no `suite` key is set here: these are real external corpora, not tempdoc-635 self-demo
suite members, and `corpora._validate_golden_set()` treats any non-empty `suite` as exactly that, firing
warnings (`closed_book_certification`/`fidelity`/`descriptor_collisions`/`regeneration_determinism`) that are
meaningless for a real BEIR-style corpus — confirmed live before this fix (loading `mixed/miracl-de-2k`
produced all four).

Two sources:
- **MIRACL** (Apache 2.0) via the already-installed `ir_datasets` dependency — no new dependency.
- **CLERC** — its own added structure (query/positive/negative construction on top of the Caselaw Access
  Project) has no stated license anywhere, checked exhaustively (tempdoc 666, second pass: the GitHub
  repo's file listing via the GitHub API, GitHub's own license detector, the HuggingFace Hub API's dataset
  card metadata, and a full-text search of the paper's Ethical Considerations/Data Availability sections —
  all confirm no license is stated). Its underlying source (the Caselaw Access Project) is CC0. Nothing
  from CLERC is ever committed here — this module only ever writes to the gitignored `datasets/` tree,
  fetching fresh each time, the same "fetch, never commit" policy this project already applies to every
  BEIR corpus (SciFact, NFCorpus, etc. are never committed either).
"""

from __future__ import annotations

import contextlib
import gzip
import io
import json
import random
from pathlib import Path
from urllib.request import Request, urlopen

_USER_AGENT = "justsearch-jseval/corpus_fetch (tempdoc-666)"


def _write_source(out_dir: Path | str, *, docs: list[dict], queries: list[dict], meta: dict) -> dict:
    """Write the committed-source shape `corpus_build.build_golden` already expects
    (`docs.jsonl`/`queries.json`/`meta.json`) — see `corpus_build.py`'s module docstring for the contract.
    """
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    with (out_dir / "docs.jsonl").open("w", encoding="utf-8") as f:
        for d in docs:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")
    (out_dir / "queries.json").write_text(
        json.dumps(queries, ensure_ascii=False, indent=1), encoding="utf-8")
    (out_dir / "meta.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    return meta["generation_provenance"]


@contextlib.contextmanager
def _utf8_default_text_io():
    """Work around a real, confirmed bug (tempdoc 666, second/third pass): `ir_datasets`' TSV reader
    (`ir_datasets/formats/tsv.py::FileLineIter.__next__`) opens its download stream as
    ``io.TextIOWrapper(stream)`` with no explicit `encoding=` — which on Windows resolves through a
    C-level `sys.flags.utf8_mode`/codepage check, not the Python `locale` module, so patching
    `locale.getpreferredencoding` (tried first; verified to be a no-op via a full-file re-read, not just
    a lucky first line) has zero effect. The topics/qrels files are genuinely UTF-8 (confirmed by
    inspecting the raw downloaded bytes directly: `heißt`/`größte`/`berühmte` are valid UTF-8 sequences,
    e.g. `\\xc3\\x9f` = "ß") — Windows' codepage default (cp1252) is simply the wrong choice for them.
    Subclassing `io.TextIOWrapper` to default to UTF-8 when no encoding is given intercepts the actual
    failing call site directly, regardless of interpreter startup flags.

    Scoped as a context manager, not a permanent global patch: a first version patched `io.TextIOWrapper`
    unconditionally and never restored it, which silently broke unrelated tests later in the same pytest
    process (`fsspec`'s `PickleableTextIOWrapper`, used by `inspect_ai`, subclasses `io.TextIOWrapper` and
    passes positional args the patched `__init__` didn't expect) — caught by running the full suite, not
    just this module's own tests.
    """
    _original = io.TextIOWrapper

    class _Utf8DefaultTextIOWrapper(_original):
        def __init__(self, *args, encoding=None, **kwargs):
            super().__init__(*args, encoding=encoding or "utf-8", **kwargs)

    io.TextIOWrapper = _Utf8DefaultTextIOWrapper
    try:
        yield
    finally:
        io.TextIOWrapper = _original


def fetch_miracl_sample(out_dir: Path | str, *, lang: str, seed: int, n_docs: int,
                         split: str = "dev") -> dict:
    """Fetch `miracl/{lang}/{split}` via `ir_datasets`, take every query + its qrelled documents, and
    deterministically sample additional non-relevant documents up to `n_docs` total (a single streaming
    pass over the corpus via reservoir sampling — no full random-access index build required beyond what
    `ir_datasets` itself needs for its own docs_iter).

    :returns: the `generation_provenance` dict recorded into the written `meta.json`.
    """
    import ir_datasets  # deferred: only this function's caller pays the import/network cost

    with _utf8_default_text_io():
        ds = ir_datasets.load(f"miracl/{lang}/{split}")
        queries = list(ds.queries_iter())
        qrels = [q for q in ds.qrels_iter() if q.relevance > 0]
        qrelled_doc_ids = {q.doc_id for q in qrels}

        rng = random.Random(seed)
        n_distractors = max(0, n_docs - len(qrelled_doc_ids))
        docs_by_id: dict[str, tuple[str, str]] = {}
        reservoir: list[str] = []
        reservoir_docs: dict[str, tuple[str, str]] = {}
        target_pool = max(n_distractors, 1)
        seen_candidates = 0

        for doc in ds.docs_iter():
            if doc.doc_id in qrelled_doc_ids:
                docs_by_id[doc.doc_id] = (getattr(doc, "title", "") or "", doc.text)
                continue
            if not n_distractors:
                continue
            seen_candidates += 1
            entry = (getattr(doc, "title", "") or "", doc.text)
            if len(reservoir) < target_pool:
                reservoir.append(doc.doc_id)
                reservoir_docs[doc.doc_id] = entry
            else:
                j = rng.randrange(seen_candidates)
                if j < target_pool:
                    del reservoir_docs[reservoir[j]]
                    reservoir[j] = doc.doc_id
                    reservoir_docs[doc.doc_id] = entry

        for did in reservoir:
            docs_by_id[did] = reservoir_docs[did]

    doc_list = [{"_id": did, "title": title, "text": text} for did, (title, text) in docs_by_id.items()]

    qrels_by_query: dict[str, list[str]] = {}
    for q in qrels:
        qrels_by_query.setdefault(q.query_id, []).append(q.doc_id)

    query_list = [
        {"query": q.text, "answer": "", "question_type": "factoid",
         "evidence_ids": qrels_by_query[q.query_id]}
        for q in queries if q.query_id in qrels_by_query
    ]

    return _write_source(out_dir, docs=doc_list, queries=query_list, meta={
        "version": "1.0", "type_axis": "wikipedia",
        "contamination_class": "public-benchmark",
        "generation_provenance": {
            "method": "ir_datasets-sample", "source": f"miracl/{lang}/{split}",
            "seed": seed, "n_docs": len(doc_list), "n_queries": len(query_list),
        },
    })


def _fetch_text(url: str) -> str:
    req = Request(url, headers={"User-Agent": _USER_AGENT})
    with urlopen(req, timeout=60) as resp:  # noqa: S310 (fixed, HF-hosted, non-user-controlled URL)
        return resp.read().decode("utf-8")


def fetch_clerc_sample(out_dir: Path | str, *, seed: int, n_queries: int) -> dict:
    """Fetch CLERC's test-split qrels + queries + document collection via plain HTTP (CLERC is not
    `ir_datasets`-registered), sample `n_queries` deterministically, and pull only their qrelled documents
    from the (large — several GB) document collection via a single streaming decompress pass, never
    materializing the full collection to disk or holding it fully in memory.

    Uses the "single-removed/direct" task variant (the citing sentence with its citation redacted, as
    the query text; direct citation as the qrel) — the most standard of CLERC's four retrieval-task
    variants. See this module's docstring for the licensing note this design already accounts for.
    """
    base = "https://huggingface.co/datasets/jhu-clsp/CLERC/resolve/main"
    qrels_lines = _fetch_text(f"{base}/qrels/qrels-doc.test.direct.tsv").splitlines()
    queries_lines = _fetch_text(f"{base}/queries/test.single-removed.direct.tsv").splitlines()

    qrels_by_query: dict[str, list[str]] = {}
    for line in qrels_lines:
        if not line.strip():
            continue
        qid, _unused, doc_id, rel = line.split("\t")
        if int(rel) > 0:
            qrels_by_query.setdefault(qid, []).append(doc_id)

    query_text_by_id: dict[str, str] = {}
    for line in queries_lines:
        if not line.strip():
            continue
        qid, text = line.split("\t", 1)
        query_text_by_id[qid] = text

    eligible = sorted(qid for qid in qrels_by_query if qid in query_text_by_id)
    rng = random.Random(seed)
    sampled_qids = set(rng.sample(eligible, min(n_queries, len(eligible))))
    wanted_doc_ids = {did for qid in sampled_qids for did in qrels_by_query[qid]}

    docs_by_id: dict[str, str] = {}
    req = Request(f"{base}/collection/collection.doc.tsv.gz", headers={"User-Agent": _USER_AGENT})
    with urlopen(req, timeout=None) as raw, gzip.GzipFile(fileobj=raw) as gz:  # noqa: S310
        text_stream = io.TextIOWrapper(gz, encoding="utf-8", errors="replace")
        for line in text_stream:
            if len(docs_by_id) >= len(wanted_doc_ids):
                break
            tab = line.find("\t")
            if tab < 0:
                continue
            doc_id = line[:tab]
            if doc_id in wanted_doc_ids:
                docs_by_id[doc_id] = line[tab + 1:].rstrip("\n")

    missing = wanted_doc_ids - docs_by_id.keys()
    if missing:
        raise RuntimeError(
            f"CLERC collection stream ended before finding {len(missing)} expected doc id(s): "
            f"{sorted(missing)[:5]}...")

    doc_list = [{"_id": did, "title": "", "text": text} for did, text in docs_by_id.items()]
    query_list = [
        {"query": query_text_by_id[qid], "answer": "", "question_type": "citation-retrieval",
         "evidence_ids": qrels_by_query[qid]}
        for qid in sorted(sampled_qids)
    ]

    return _write_source(out_dir, docs=doc_list, queries=query_list, meta={
        "version": "1.0", "type_axis": "legal",
        "contamination_class": "public-benchmark",
        "generation_provenance": {
            "method": "huggingface-direct-sample",
            "source": "jhu-clsp/CLERC (test split, single-removed/direct task variant)",
            "seed": seed, "n_docs": len(doc_list), "n_queries": len(query_list),
        },
    })
