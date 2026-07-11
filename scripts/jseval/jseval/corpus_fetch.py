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

CLERC's raw fetch (qrels/queries text + the several-GB document collection) goes through
`dataset_cache.cached_dir` (tempdoc 709): a shared, cross-worktree, gitignored, integrity-verified
on-disk cache of the raw upstream bytes under the MAIN checkout, so the GB-scale collection stream
isn't re-downloaded once per worktree per day. This is purely a network-trip dedupe of the same
"fetch fresh, never commit/redistribute" bytes this module already only ever writes to a gitignored
tree — it changes nothing about the licensing posture above.
"""

from __future__ import annotations

import contextlib
import gzip
import io
import json
import random
import shutil
from pathlib import Path
from urllib.request import Request, urlopen

from . import dataset_cache

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

    # tempdoc 709: point ir_datasets' own download cache at the shared, cross-worktree
    # dataset-fetch cache root (config-only -- ir_datasets already does its own on-disk
    # caching + verification once IR_DATASETS_HOME is set).
    dataset_cache.apply_ir_datasets_home()

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


_CLERC_QRELS_FILE = "qrels-doc.test.direct.tsv"
_CLERC_QUERIES_FILE = "test.single-removed.direct.tsv"
_CLERC_COLLECTION_FILE = "collection.doc.tsv.gz"
_CLERC_RAW_FILES = [_CLERC_QRELS_FILE, _CLERC_QUERIES_FILE, _CLERC_COLLECTION_FILE]


def _populate_clerc_raw(dest: Path, *, base: str) -> None:
    """Fetch CLERC's three raw upstream artifacts into `dest` (tempdoc 709's `dataset_cache`
    `populate` callback) -- the qrels/queries text files, plus the (several-GB, gzip-compressed)
    document collection, downloaded whole to disk. Independent of seed/n_queries/n_docs: the raw
    bytes are the same regardless of how a caller later samples them, so this is cache-keyed on
    `base` alone -- caching at this layer serves every future seed/sample-size combination, not
    just the one that happened to trigger the first fetch.
    """
    (dest / _CLERC_QRELS_FILE).write_text(
        _fetch_text(f"{base}/qrels/qrels-doc.test.direct.tsv"), encoding="utf-8")
    (dest / _CLERC_QUERIES_FILE).write_text(
        _fetch_text(f"{base}/queries/test.single-removed.direct.tsv"), encoding="utf-8")
    req = Request(f"{base}/collection/collection.doc.tsv.gz", headers={"User-Agent": _USER_AGENT})
    with urlopen(req, timeout=None) as raw, (dest / _CLERC_COLLECTION_FILE).open("wb") as out:  # noqa: S310
        shutil.copyfileobj(raw, out)


def fetch_clerc_sample(out_dir: Path | str, *, seed: int, n_queries: int, n_docs: int | None = None) -> dict:
    """Fetch CLERC's test-split qrels + queries + document collection via plain HTTP (CLERC is not
    `ir_datasets`-registered), sample `n_queries` deterministically, and pull their qrelled documents
    from the (large — several GB) document collection.

    The raw fetch of all three artifacts goes through `dataset_cache.cached_dir` (tempdoc 709): a
    shared, cross-worktree, integrity-verified on-disk cache keyed on `base` alone (independent of
    `seed`/`n_queries`/`n_docs` — the raw bytes don't depend on how they're later sampled), so a
    same-day rerun in a different worktree (or a different seed/sample-size in the same worktree)
    reuses the already-downloaded collection instead of re-streaming several GB over the network
    again. When the shared cache is disabled (`JUSTSEARCH_DATASET_CACHE=0`) or unavailable, this
    falls back to a direct, uncached, ephemeral fetch — identical to this function's pre-709 behavior.

    `n_docs=None` (default) keeps the original qrelled-only behavior byte-compatible (including the
    early `break` once every wanted doc is found, now scanning the locally-cached collection file
    rather than a live network stream) so existing callers and the committed
    `666-corpora/legal-clerc-200/recipe.json` reproduction path are unaffected. When `n_docs` is set,
    mirrors `fetch_miracl_sample`'s pattern: the qrelled docs are kept, and `n_docs - len(wanted)`
    additional distractor documents are deterministically reservoir-sampled from the rest of the same
    pass (so the early break must NOT fire — the full collection needs to be seen for the
    reservoir sample to be uniform). Distractor sampling uses its own `random.Random(seed)` instance,
    separate from the `rng` used for query sampling above, so the sampled qrelled-doc set is identical
    across every `n_docs` value at a given `(seed, n_queries)` — verified in
    `test_fetch_clerc_sample_qrel_set_is_invariant_to_n_docs`.

    Uses the "single-removed/direct" task variant (the citing sentence with its citation redacted, as
    the query text; direct citation as the qrel) — the most standard of CLERC's four retrieval-task
    variants. See this module's docstring for the licensing note this design already accounts for.
    """
    base = "https://huggingface.co/datasets/jhu-clsp/CLERC/resolve/main"
    with dataset_cache.cached_dir(
        "clerc-raw",
        {"base": base, "task_variant": "single-removed/direct"},
        filenames=_CLERC_RAW_FILES,
        populate=lambda dest: _populate_clerc_raw(dest, base=base),
    ) as raw_dir:
        qrels_lines = (raw_dir / _CLERC_QRELS_FILE).read_text(encoding="utf-8").splitlines()
        queries_lines = (raw_dir / _CLERC_QUERIES_FILE).read_text(encoding="utf-8").splitlines()
        collection_path = raw_dir / _CLERC_COLLECTION_FILE
        return _sample_clerc_from_raw(
            out_dir, qrels_lines=qrels_lines, queries_lines=queries_lines,
            collection_path=collection_path, seed=seed, n_queries=n_queries, n_docs=n_docs)


def _sample_clerc_from_raw(
    out_dir: Path | str, *, qrels_lines: list[str], queries_lines: list[str], collection_path: Path,
    seed: int, n_queries: int, n_docs: int | None,
) -> dict:
    """Deterministic sampling over already-fetched raw CLERC artifacts -- split out from
    `fetch_clerc_sample` so the (cache-eligible) raw fetch and the (seed-dependent) sampling are two
    separate concerns; this function has no network access of its own."""
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

    # A separate rng instance (not `rng` above) so distractor reservoir sampling never perturbs the
    # query-sampling draw -- the qrelled-doc set stays byte-identical across every `n_docs` value.
    distractor_rng = random.Random(seed)
    n_distractors = max(0, n_docs - len(wanted_doc_ids)) if n_docs is not None else 0
    target_pool = max(n_distractors, 1)
    seen_candidates = 0
    reservoir: list[str] = []
    reservoir_texts: dict[str, str] = {}

    docs_by_id: dict[str, str] = {}
    with gzip.open(collection_path, "rt", encoding="utf-8", errors="replace") as text_stream:
        for line in text_stream:
            # The early break only applies to the qrelled-only (n_docs=None) path -- distractor
            # reservoir sampling needs the full stream to be uniform.
            if n_docs is None and len(docs_by_id) >= len(wanted_doc_ids):
                break
            tab = line.find("\t")
            if tab < 0:
                continue
            doc_id = line[:tab]
            text = line[tab + 1:].rstrip("\n")
            if doc_id in wanted_doc_ids:
                docs_by_id[doc_id] = text
                continue
            if not n_distractors:
                continue
            seen_candidates += 1
            if len(reservoir) < target_pool:
                reservoir.append(doc_id)
                reservoir_texts[doc_id] = text
            else:
                j = distractor_rng.randrange(seen_candidates)
                if j < target_pool:
                    del reservoir_texts[reservoir[j]]
                    reservoir[j] = doc_id
                    reservoir_texts[doc_id] = text

    for did in reservoir:
        docs_by_id[did] = reservoir_texts[did]

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

    provenance = {
        "method": "huggingface-direct-sample",
        "source": "jhu-clsp/CLERC (test split, single-removed/direct task variant)",
        "seed": seed, "n_docs": len(doc_list), "n_queries": len(query_list),
    }
    if n_docs is not None:
        # Only added when n_docs is set -- keeps the n_docs=None provenance dict (and thus any
        # committed recipe.json reproducing it, e.g. 666-corpora/legal-clerc-200) byte-compatible.
        provenance["n_docs_requested"] = n_docs
        provenance["n_distractors"] = len(reservoir)

    return _write_source(out_dir, docs=doc_list, queries=query_list, meta={
        "version": "1.0", "type_axis": "legal",
        "contamination_class": "public-benchmark",
        "generation_provenance": provenance,
    })
