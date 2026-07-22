"""Per-offset recall instrument (tempdoc 783 §B.1) — recall-vs-evidence-offset curves.

The engine's deepest scale-dependent weakness (register F-040) is recall loss on long
documents: gold evidence at a large character offset stops being retrievable. This module
is the STANDING DIAGNOSTIC for that weakness — *before* any intervention — answering:

    at what character offset does gold evidence stop being retrievable,
    per corpus, per retrieval leg?

It is a *pure* analysis over an already-written eval-results run dir plus its corpus dir;
it needs no backend. For each query it locates the gold evidence's character offset inside
the gold document, bins queries by that offset, and reports per-bin recall@k and
rank-of-gold — PER MODE/LEG the run captured (one ``<mode>_per_query.json`` file each).

Evidence-offset resolution has two sources, in priority order (per query, per gold doc):

  (a) **generator metadata** — an ``evidence_offsets.json`` sidecar (written by
      :func:`corpus_inject.evidence_offsets_for_injection` for injected corpora) recording
      exactly where each injected gold sentence lands. Authoritative when present.
  (b) **fallback string-location** — find the query's answer / evidence string in the gold
      document text (:func:`evidence_offset.locate_offset`). This is what makes the
      instrument work on REAL corpora (legal-clerc-200) where no generator metadata exists.

Every query's resolution source is reported, and unresolved queries are COUNTED (never
silently dropped — a silent drop would fake the curve).
"""
from __future__ import annotations

import json
import statistics
from pathlib import Path

from .evidence_offset import locate_offset

# Character-offset bin edges (upper-exclusive). The final bin is open-ended ("<edge>+").
# 1k/2k/4k/8k brackets the F-040 landmine (bridge sentences at median offset ~5,000 chars).
DEFAULT_BIN_EDGES: tuple[int, ...] = (1000, 2000, 4000, 8000)

_PER_QUERY_SUFFIX = "_per_query.json"


def _fmt_edge(n: int) -> str:
    if n >= 1000 and n % 1000 == 0:
        return f"{n // 1000}k"
    return str(n)


def bin_labels(edges: tuple[int, ...] = DEFAULT_BIN_EDGES) -> list[str]:
    """Ordered bin labels for ``edges`` (e.g. ``0-1k``, ``1k-2k``, …, ``8k+``)."""
    labels = []
    prev = 0
    for edge in edges:
        labels.append(f"{_fmt_edge(prev)}-{_fmt_edge(edge)}")
        prev = edge
    labels.append(f"{_fmt_edge(prev)}+")
    return labels


def bin_for_offset(offset: int, edges: tuple[int, ...] = DEFAULT_BIN_EDGES) -> str:
    """Return the bin label an ``offset`` falls into (upper-exclusive edges)."""
    labels = bin_labels(edges)
    for i, edge in enumerate(edges):
        if offset < edge:
            return labels[i]
    return labels[-1]


def _candidate_needles(query_meta: dict) -> list[str]:
    """Evidence strings to search for, in priority order. The answer is the honest
    evidence anchor: for single-fact / injected-single-doc corpora it sits verbatim in the
    gold document. (Multi-hop answers live in a terminal doc, not the first-hop gold doc, so
    those resolve to ``unresolved`` under the fallback — correct, not a bug: use generator
    metadata to bin those.)"""
    needles = []
    answer = query_meta.get("answer")
    if isinstance(answer, str) and answer.strip():
        needles.append(answer)
    return needles


def resolve_offset(
    qid: str,
    gold_docs: list[str],
    doc_texts: dict[str, str],
    query_meta: dict[str, dict],
    evidence_offsets_meta: dict[str, dict] | None,
) -> tuple[int | None, str, str | None, str | None]:
    """Resolve one query's evidence offset. Returns ``(offset, source, doc, reason)``.

    Priority: generator metadata across ALL gold docs first, then string-location across
    all gold docs. ``offset is None`` ⇒ unresolved (``reason`` explains why); a resolved
    result names the ``source`` (``metadata`` / ``string_match``) and the gold ``doc`` the
    offset is measured within.
    """
    meta = evidence_offsets_meta or {}
    # (a) generator metadata — authoritative when present.
    for doc in gold_docs:
        entry = meta.get(doc)
        if isinstance(entry, dict):
            off = entry.get("char_offset")
            if isinstance(off, int) and not isinstance(off, bool) and off >= 0:
                return off, "metadata", doc, None
    # (b) fallback string-location of the answer/evidence string.
    needles = _candidate_needles(query_meta.get(qid, {}))
    for doc in gold_docs:
        text = doc_texts.get(doc)
        if not text:
            continue
        for needle in needles:
            off = locate_offset(text, needle)
            if off is not None:
                return off, "string_match", doc, None
    reason = "no_gold_text" if not any(doc_texts.get(d) for d in gold_docs) else \
        ("no_evidence_string" if not needles else "evidence_not_located")
    return None, "unresolved", (gold_docs[0] if gold_docs else None), reason


def build_report(
    modes: dict[str, dict[str, list[str]]],
    qrels: dict[str, dict[str, int]],
    doc_texts: dict[str, str],
    query_meta: dict[str, dict],
    evidence_offsets_meta: dict[str, dict] | None = None,
    *,
    k: int = 10,
    bin_edges: tuple[int, ...] = DEFAULT_BIN_EDGES,
) -> dict:
    """Build the offset-recall report. Pure — deterministic given the same inputs.

    ``modes``: ``{mode: {qid: [predicted_doc_id, …]}}`` (ranked, best-first).
    ``qrels``: ``{qid: {doc_id: relevance}}`` (gold = relevance > 0).
    """
    labels = bin_labels(bin_edges)
    mode_names = sorted(modes)

    per_query: list[dict] = []
    by_source: dict[str, int] = {"metadata": 0, "string_match": 0}
    unresolved = 0
    no_gold = 0

    for qid in sorted(qrels):
        gold_docs = sorted(
            d for d, rel in qrels[qid].items()
            if isinstance(rel, int) and not isinstance(rel, bool) and rel > 0
        )
        if not gold_docs:
            no_gold += 1
            continue

        offset, source, resolved_doc, reason = resolve_offset(
            qid, gold_docs, doc_texts, query_meta, evidence_offsets_meta,
        )
        if offset is None:
            unresolved += 1
            per_query.append({
                "qid": qid, "gold_doc": resolved_doc, "offset": None,
                "bin": None, "source": "unresolved", "reason": reason, "per_mode": {},
            })
            continue

        by_source[source] = by_source.get(source, 0) + 1
        b = bin_for_offset(offset, bin_edges)
        per_mode = {}
        for mode in mode_names:
            pred = modes[mode].get(qid, [])
            rank = pred.index(resolved_doc) + 1 if resolved_doc in pred else None
            per_mode[mode] = {"rank": rank, "hit": rank is not None and rank <= k}
        per_query.append({
            "qid": qid, "gold_doc": resolved_doc, "offset": offset,
            "bin": b, "source": source, "per_mode": per_mode,
        })

    curves: dict[str, dict[str, dict]] = {}
    for mode in mode_names:
        curves[mode] = {}
        for b in labels:
            rows = [q for q in per_query if q["bin"] == b and q["offset"] is not None]
            n = len(rows)
            hits = sum(1 for q in rows if q["per_mode"][mode]["hit"])
            ranks = sorted(
                q["per_mode"][mode]["rank"] for q in rows
                if q["per_mode"][mode]["rank"] is not None
            )
            curves[mode][b] = {
                "n": n,
                "recall_at_k": (hits / n) if n else None,
                "hits": hits,
                "n_ranked": len(ranks),
                "median_rank_when_found": (statistics.median(ranks) if ranks else None),
            }

    queries_with_gold = len(per_query)
    resolved = queries_with_gold - unresolved
    return {
        "schema": "offset-recall.v1",
        "k": k,
        "bins": labels,
        "modes": mode_names,
        "resolution": {
            "queries_with_gold": queries_with_gold,
            "resolved": resolved,
            "unresolved": unresolved,
            "no_gold": no_gold,
            "by_source": by_source,
        },
        "curves": curves,
        "per_query": per_query,
        "unresolved_qids": [q["qid"] for q in per_query if q["source"] == "unresolved"],
    }


# --- offline loaders (IO; the pure core above takes plain data) --------------------------


def load_run(run_dir: str | Path) -> tuple[dict[str, dict[str, list[str]]], dict]:
    """Load ``(modes, qrels)`` from an eval-results run dir written by ``artifacts.write_run``.

    ``modes`` is keyed by the ``<mode>`` prefix of each ``<mode>_per_query.json`` file; each
    per-query entry contributes ``{qid: predictedDocIds}``. ``qrels`` is ``qrels.json``.
    """
    run_dir = Path(run_dir)
    qrels_path = run_dir / "qrels.json"
    if not qrels_path.is_file():
        raise FileNotFoundError(f"no qrels.json in run dir: {run_dir}")
    qrels = json.loads(qrels_path.read_text(encoding="utf-8"))

    modes: dict[str, dict[str, list[str]]] = {}
    for path in sorted(run_dir.glob(f"*{_PER_QUERY_SUFFIX}")):
        mode = path.name[: -len(_PER_QUERY_SUFFIX)]
        entries = json.loads(path.read_text(encoding="utf-8"))
        modes[mode] = {
            str(e["qid"]): [str(d) for d in (e.get("predictedDocIds") or [])]
            for e in entries if e.get("qid")
        }
    if not modes:
        raise FileNotFoundError(f"no *{_PER_QUERY_SUFFIX} files in run dir: {run_dir}")
    return modes, qrels


def load_corpus(
    corpus_dir: str | Path,
) -> tuple[dict[str, str], dict[str, dict], dict[str, dict] | None]:
    """Load ``(doc_texts, query_meta, evidence_offsets_meta)`` from a corpus dir.

    ``doc_texts``: ``{doc_id: text}`` from ``corpus.jsonl`` (BEIR-materialized) or
    ``docs.jsonl`` (source). ``query_meta``: ``{qid: {answer, evidence_ids, …}}`` from
    ``queries.json``, keyed ``q0001``-style to match ``corpus_build.build_golden``.
    ``evidence_offsets_meta``: the ``offsets`` map from an ``evidence_offsets.json`` sidecar
    if present, else ``None``.
    """
    corpus_dir = Path(corpus_dir)

    doc_path = corpus_dir / "corpus.jsonl"
    if not doc_path.is_file():
        doc_path = corpus_dir / "docs.jsonl"
    doc_texts: dict[str, str] = {}
    if doc_path.is_file():
        for line in doc_path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            doc_texts[str(obj["_id"])] = str(obj.get("text", ""))

    query_meta: dict[str, dict] = {}
    qpath = corpus_dir / "queries.json"
    if qpath.is_file():
        arr = json.loads(qpath.read_text(encoding="utf-8"))
        for i, q in enumerate(arr, 1):
            query_meta[f"q{i:04d}"] = {
                "answer": q.get("answer"),
                "evidence_ids": q.get("evidence_ids", []),
                "question_type": q.get("question_type"),
                "query": q.get("query"),
            }

    evidence_offsets_meta = None
    epath = corpus_dir / "evidence_offsets.json"
    if epath.is_file():
        side = json.loads(epath.read_text(encoding="utf-8"))
        if isinstance(side, dict) and isinstance(side.get("offsets"), dict):
            evidence_offsets_meta = side["offsets"]

    return doc_texts, query_meta, evidence_offsets_meta


def analyze(
    run_dir: str | Path,
    corpus_dir: str | Path,
    *,
    k: int = 10,
    bin_edges: tuple[int, ...] = DEFAULT_BIN_EDGES,
) -> dict:
    """Load a run dir + corpus dir from disk and build the offset-recall report (no backend)."""
    modes, qrels = load_run(run_dir)
    doc_texts, query_meta, evidence_offsets_meta = load_corpus(corpus_dir)
    return build_report(
        modes, qrels, doc_texts, query_meta, evidence_offsets_meta,
        k=k, bin_edges=bin_edges,
    )


def format_table(report: dict) -> str:
    """A compact deterministic stdout table: one row per (mode, bin)."""
    res = report["resolution"]
    lines = [
        f"Offset-recall (schema={report['schema']}, k={report['k']})",
        (
            f"  resolved={res['resolved']}/{res['queries_with_gold']} "
            f"unresolved={res['unresolved']} "
            f"(metadata={res['by_source'].get('metadata', 0)} "
            f"string={res['by_source'].get('string_match', 0)}) "
            f"no_gold={res['no_gold']}"
        ),
        f"  {'mode':<16}{'bin':<10}{'n':>5}{'recall@k':>11}{'medRank':>9}",
    ]
    for mode in report["modes"]:
        for b in report["bins"]:
            cell = report["curves"][mode][b]
            recall = "-" if cell["recall_at_k"] is None else f"{cell['recall_at_k']:.3f}"
            med = "-" if cell["median_rank_when_found"] is None else f"{cell['median_rank_when_found']:g}"
            lines.append(
                f"  {mode:<16}{b:<10}{cell['n']:>5}{recall:>11}{med:>9}"
            )
    return "\n".join(lines)
