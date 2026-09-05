#!/usr/bin/env python3
"""Tempdoc 916 Part 1 step 0 — deterministic RAG question/gold-span fixture generator.

Tempdoc 916 §B.11 established that the "845/881 question sets" the lane brief asks for do not
exist as artefacts. This is the replacement instrument, and it is built by DERIVATION, not by
generation: every question, gold document and gold span comes from an existing qrel plus a fixed
rule, so the fixture is reproducible from the recipe alone and no LLM is involved at any point.

What a triple is
----------------
* **question** — the qrel's query text, verbatim. Its shape differs per corpus and the README
  says so: ``enron-qa`` queries are natural questions, ``beir/scifact`` queries are claims to
  verify, ``legal-clerc-200`` queries are ~1600-char passages whose task is citation retrieval.
* **gold document** — the corpus id the qrel marks relevant (score >= 1).
* **gold span** — the single sentence of the gold document with the highest lexical overlap with
  the question, located back into the raw document with ``jseval.evidence_offset.locate_offset``
  (the ONE shared offset primitive, tempdoc 783 §B.1 — this is a projection of that
  representation, not a second one).

There is no gold ANSWER string. None of the three corpora ships one (checked: every
``legal-clerc-200`` ``queries.json`` ``answer`` field is empty, ``enron-qa`` and BEIR carry no
answer field at all), so the fixture writes the gold SPAN into the ``answer`` slot of the
MultiHop-RAG shape ``jseval tier2-eval`` already parses. That makes ``correct_exact`` and
``correct_substring`` meaningless by construction and ``correct_has_intersection`` plus the
AI-judge tier the meaningful readings. The README states this; do not read the exact-match
column from a run of this fixture.

Outputs (NOT committed — they carry corpus text, and the convention every other
``NNN-corpora`` member follows is recipe-only; see ``789-corpora/enron-qa-answers/recipe.json``):

    <out>/<slug>/queries.json          MultiHop-RAG shape for `jseval tier2-eval --queries`
    <out>/<slug>/evidence_offsets.json schema "evidence-offsets.v1" (corpus_inject.py:488-526)
    <out>/digests.json                 the sha256s this run produced

Committed: ``recipe.json`` (method + seed + pinned digests) and ``README.md``.

Usage:
    python generate.py [--out <dir>] [--per-corpus 50] [--min-gold-chars 2000]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
JSEVAL_ROOT = HERE.parents[1]
REPO_ROOT = JSEVAL_ROOT.parents[1]
sys.path.insert(0, str(JSEVAL_ROOT))

from jseval.evidence_offset import locate_offset  # noqa: E402

SCHEMA = "916-rag-qa.v1"
OFFSET_SCHEMA = "evidence-offsets.v1"
METHOD = "qrel-derived-span-v1"

# The splitter's own sentence pattern (ChunkSplitter.SENTENCE_END), so a gold span never straddles
# a boundary the chunker would not have chosen either.
SENTENCE_END = re.compile(r"(?<=[.!?])\s+")
WORD = re.compile(r"[^\W\d_]+", re.UNICODE)

CORPORA = ("mixed/enron-qa", "mixed/legal-clerc-200", "beir/scifact")

QUESTION_SHAPE = {
    "mixed/enron-qa": "natural-question",
    "mixed/legal-clerc-200": "citation-retrieval-passage",
    "beir/scifact": "claim-to-verify",
}


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def tokens(text: str) -> list[str]:
    return [w.lower() for w in WORD.findall(text)]


def best_span(question: str, doc_text: str) -> tuple[str, float] | None:
    """The gold span rule, stated once so the recipe can name it.

    Score a sentence by ``|Q ∩ S| / sqrt(|S|)`` over lowercased unicode word tokens: the
    intersection rewards on-topic sentences, the ``sqrt`` denominator stops a very long sentence
    winning purely by containing more words. Ties break to the EARLIEST sentence, so the rule is
    total and the output is byte-stable across runs and platforms.

    Returns ``None`` when no sentence shares a single token with the question — an honest
    "unresolved", never a silent first-sentence fallback.
    """
    q = set(tokens(question))
    if not q:
        return None
    best: tuple[float, int, str] | None = None
    for i, sentence in enumerate(SENTENCE_END.split(doc_text)):
        s = sentence.strip()
        if len(s) < 20:
            continue
        st = tokens(s)
        if not st:
            continue
        overlap = len(q.intersection(st))
        if overlap == 0:
            continue
        score = overlap / (len(st) ** 0.5)
        if best is None or score > best[0]:
            best = (score, i, s)
    if best is None:
        return None
    return best[2], round(best[0], 6)


def load_mixed(slug: str) -> tuple[dict[str, str], dict[str, str], dict[str, list[str]], Path]:
    root = REPO_ROOT / "datasets" / "mixed" / slug
    docs: dict[str, str] = {}
    with (root / "corpus.jsonl").open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            title = (d.get("title") or "").strip()
            body = d.get("text") or ""
            docs[d["_id"]] = (title + "\n" + body) if title else body
    queries: dict[str, str] = {}
    with (root / "queries.jsonl").open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            q = json.loads(line)
            queries[q["_id"]] = q["text"]
    qrels: dict[str, list[str]] = {}
    with (root / "qrels" / "test.tsv").open(encoding="utf-8") as fh:
        for i, line in enumerate(fh):
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3 or (i == 0 and parts[0] == "query-id"):
                continue
            qid, did, score = parts[0], parts[1], parts[2]
            try:
                if int(score) < 1:
                    continue
            except ValueError:
                continue
            qrels.setdefault(qid, []).append(did)
    return docs, queries, qrels, root


def load_beir(slug: str) -> tuple[dict[str, str], dict[str, str], dict[str, list[str]], Path]:
    import ir_datasets  # noqa: PLC0415

    from jseval import dataset_cache  # noqa: PLC0415

    dataset_cache.apply_ir_datasets_home()
    homes = [os.environ.get("IR_DATASETS_HOME")]
    shared = Path("F:/justsearch-public/scripts/jseval/tmp/dataset-fetch-cache/ir_datasets")
    if shared.exists():
        homes.append(str(shared))
    ds = None
    for home in homes:
        if not home:
            continue
        os.environ["IR_DATASETS_HOME"] = home
        try:
            ds = ir_datasets.load(f"beir/{slug}/test")
            break
        except Exception:  # noqa: BLE001 — try the next cache root
            continue
    if ds is None:
        raise RuntimeError(f"beir/{slug} not available in any ir_datasets cache root")

    docs = {}
    for d in ds.docs_iter():
        title = (d.title or "").strip()
        body = d.text or ""
        docs[d.doc_id] = (title + "\n" + body) if title else body
    queries = {q.query_id: q.text for q in ds.queries_iter()}
    qrels: dict[str, list[str]] = {}
    for qr in ds.qrels_iter():
        if qr.relevance >= 1:
            qrels.setdefault(qr.query_id, []).append(qr.doc_id)
    return docs, queries, qrels, Path(os.environ.get("IR_DATASETS_HOME", ""))


def build(corpus: str, per_corpus: int, min_gold_chars: int) -> dict:
    family, slug = corpus.split("/", 1)
    if family == "mixed":
        docs, queries, qrels, root = load_mixed(slug)
    else:
        docs, queries, qrels, root = load_beir(slug)

    entries = []
    offsets = {}
    skipped_short = 0
    skipped_unresolved = 0
    # Deterministic order: qrel query ids sorted as strings. No sampling, no seed — the first N
    # eligible ids ARE the fixture, so "reproduce it" needs nothing but this file.
    for qid in sorted(qrels):
        if len(entries) >= per_corpus:
            break
        question = (queries.get(qid) or "").strip()
        if not question:
            continue
        gold_ids = [d for d in sorted(qrels[qid]) if d in docs]
        gold_ids = [d for d in gold_ids if len(docs[d]) >= min_gold_chars]
        if not gold_ids:
            skipped_short += 1
            continue
        did = gold_ids[0]
        doc_text = docs[did]
        span = best_span(question, doc_text)
        if span is None:
            skipped_unresolved += 1
            continue
        sentence, score = span
        offset = locate_offset(doc_text, sentence)
        if offset is None:
            skipped_unresolved += 1
            continue
        entries.append(
            {
                "query_family_id": qid,
                "query": question,
                # The gold SPAN, not a gold answer — see the module docstring.
                "answer": sentence,
                "question_type": QUESTION_SHAPE[corpus],
                "gold_doc_id": did,
                "gold_span_score": score,
                "evidence_list": [{"title": did, "doc_id": did}],
            }
        )
        offsets[did] = {
            "char_offset": offset,
            "doc_len": len(doc_text),
            "evidence": sentence,
        }

    return {
        "corpus": corpus,
        "root": str(root),
        "entries": entries,
        "offsets": {"schema": OFFSET_SCHEMA, "method": METHOD, "offsets": offsets},
        "eligible_qrel_queries": len(qrels),
        "skipped_gold_below_min_chars": skipped_short,
        "skipped_span_unresolved": skipped_unresolved,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--out", default=str(JSEVAL_ROOT / "tmp" / "916-part1" / "rag-qa-v1")
    )
    ap.add_argument("--per-corpus", type=int, default=50)
    ap.add_argument(
        "--min-gold-chars",
        type=int,
        default=2000,
        help="only qrels whose gold document is long enough to be CHUNKED are eligible; a fixture "
        "of un-chunked gold documents would be blind to the variable this campaign sweeps",
    )
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    digests = {"schema": SCHEMA, "method": METHOD, "corpora": {}}
    for corpus in CORPORA:
        try:
            built = build(corpus, args.per_corpus, args.min_gold_chars)
        except Exception as exc:  # noqa: BLE001 — a missing corpus must not void the others
            digests["corpora"][corpus] = {"error": str(exc)}
            print(f"{corpus:<26} ERROR {exc}")
            continue
        slug_dir = out / corpus.replace("/", "_")
        slug_dir.mkdir(parents=True, exist_ok=True)
        qpath = slug_dir / "queries.json"
        opath = slug_dir / "evidence_offsets.json"
        qpath.write_text(
            json.dumps(built["entries"], indent=2, ensure_ascii=False, sort_keys=True),
            encoding="utf-8",
            newline="\n",
        )
        opath.write_text(
            json.dumps(built["offsets"], indent=2, ensure_ascii=False, sort_keys=True),
            encoding="utf-8",
            newline="\n",
        )
        digests["corpora"][corpus] = {
            "question_shape": QUESTION_SHAPE[corpus],
            "questions": len(built["entries"]),
            "gold_docs": len(built["offsets"]["offsets"]),
            "eligible_qrel_queries": built["eligible_qrel_queries"],
            "skipped_gold_below_min_chars": built["skipped_gold_below_min_chars"],
            "skipped_span_unresolved": built["skipped_span_unresolved"],
            "query_gold_sha256": sha256_file(qpath),
            "evidence_offsets_sha256": sha256_file(opath),
        }
        print(
            f"{corpus:<26} n={len(built['entries']):<4} "
            f"query_gold_sha256={digests['corpora'][corpus]['query_gold_sha256'][:16]}…"
        )

    dpath = out / "digests.json"
    dpath.write_text(
        json.dumps(digests, indent=2, ensure_ascii=False, sort_keys=True),
        encoding="utf-8",
        newline="\n",
    )
    print(f"\nwrote {dpath}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
