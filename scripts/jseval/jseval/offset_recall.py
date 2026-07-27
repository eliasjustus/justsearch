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

Evidence-offset resolution has three sources, in strict priority order (per query, per
gold doc) — the first two MEASURE the evidence offset, the third only PROXIES it:

  (a) **generator metadata** — an ``evidence_offsets.json`` sidecar (written by
      :func:`corpus_inject.evidence_offsets_for_injection` for injected corpora) recording
      exactly where each injected gold sentence lands. Authoritative when present.
  (b) **fallback string-location** — find the query's answer / evidence string in the gold
      document text (:func:`evidence_offset.locate_offset`).
  (c) **query-locus PROXY** — :func:`query_locus.locate_query_locus`: where the query's
      corpus-locally-distinguishing terms sit in the gold document. Sources (a) and (b)
      resolve on ZERO real benchmark corpora (CLERC/MIRACL carry ``answer: ""``; measured
      legal-clerc-200 0/200, miracl-de-2k 0/305, miracl-fr-2k 0/343 on 2026-07-22), so
      this is what makes the instrument produce a curve there at all. It answers a
      DIFFERENT question than (a)/(b) — see :mod:`query_locus` for the validity caveat.

Proxy-resolved queries are accounted separately (``by_source['query_locus']``) and their
curve is emitted as a SEPARATE, labeled ``proxy_curves`` block — the report never merges a
proxy offset into ``curves``, whose cells are measured-offset cells only. Every query's
resolution source is reported, and unresolved queries are COUNTED (never silently dropped —
a silent drop would fake the curve).
"""
from __future__ import annotations

import json
import statistics
from pathlib import Path

from . import query_locus
from .evidence_offset import locate_offset

# Character-offset bin edges (upper-exclusive). The final bin is open-ended ("<edge>+").
# 1k/2k/4k/8k brackets the F-040 landmine (bridge sentences at median offset ~5,000 chars).
DEFAULT_BIN_EDGES: tuple[int, ...] = (1000, 2000, 4000, 8000)

_PER_QUERY_SUFFIX = "_per_query.json"

# Resolution sources that MEASURE the evidence offset, vs the one that PROXIES it. The
# report keys one curve block per class and never mixes them.
MEASURED_SOURCES: tuple[str, ...] = ("metadata", "string_match")
PROXY_SOURCE = "query_locus"

_PROXY_LABEL = (
    "PROXY (query_locus): offsets are where the query's distinguishing terms sit in the "
    "gold doc, NOT where the answer sits — not comparable to measured curves"
)


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
    *,
    locus_weights: dict[str, int] | None = None,
    window_chars: int = query_locus.DEFAULT_WINDOW_CHARS,
) -> tuple[int | None, str, str | None, str | None]:
    """Resolve one query's evidence offset. Returns ``(offset, source, doc, reason)``.

    Priority: generator metadata across ALL gold docs first, then answer-string location
    across all gold docs, then — only when ``locus_weights`` is supplied — the query-locus
    PROXY. The proxy is last by construction so it can never shadow a real resolution.
    ``offset is None`` ⇒ unresolved (``reason`` names the last tier that failed); a resolved
    result names the ``source`` (``metadata`` / ``string_match`` / ``query_locus``) and the
    gold ``doc`` the offset sits within.
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
    # (c) query-locus PROXY — labeled, separately accounted, never merged with (a)/(b).
    query_text = query_meta.get(qid, {}).get("query")
    proxy_ran = bool(locus_weights) and isinstance(query_text, str) and bool(query_text)
    if proxy_ran:
        for doc in gold_docs:
            text = doc_texts.get(doc)
            if not text:
                continue
            hit = query_locus.locate_query_locus(
                text, query_text, locus_weights, window_chars=window_chars,
            )
            if hit is not None:
                return hit[0], PROXY_SOURCE, doc, None

    if not any(doc_texts.get(d) for d in gold_docs):
        reason = "no_gold_text"
    elif proxy_ran:
        reason = "no_query_locus"
    elif needles:
        reason = "evidence_not_located"
    else:
        reason = "no_evidence_string"
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
    proxy_window_chars: int = query_locus.DEFAULT_WINDOW_CHARS,
) -> dict:
    """Build the offset-recall report. Pure — deterministic given the same inputs.

    ``modes``: ``{mode: {qid: [predicted_doc_id, …]}}`` (ranked, best-first).
    ``qrels``: ``{qid: {doc_id: relevance}}`` (gold = relevance > 0).

    Measured (metadata / answer-string) and PROXY (query-locus) resolutions are binned into
    two SEPARATE curve blocks — ``curves`` and ``proxy_curves`` — over disjoint query sets.
    """
    labels = bin_labels(bin_edges)
    mode_names = sorted(modes)

    gold_by_qid: dict[str, list[str]] = {}
    no_gold = 0
    for qid in sorted(qrels):
        gold_docs = sorted(
            d for d, rel in qrels[qid].items()
            if isinstance(rel, int) and not isinstance(rel, bool) and rel > 0
        )
        if gold_docs:
            gold_by_qid[qid] = gold_docs
        else:
            no_gold += 1

    # Pass 1 — measured sources only (locus_weights=None disables the proxy). Pass 2 runs
    # the proxy over ONLY what pass 1 left unresolved, so the proxy structurally cannot
    # shadow a measured resolution, and the corpus-wide rarity pass is skipped entirely
    # when every query already resolved.
    resolutions: dict[str, tuple[int | None, str, str | None, str | None]] = {
        qid: resolve_offset(qid, docs, doc_texts, query_meta, evidence_offsets_meta)
        for qid, docs in gold_by_qid.items()
    }
    still_unresolved = [qid for qid, r in resolutions.items() if r[0] is None]
    if still_unresolved:
        weights = _locus_weights(doc_texts, query_meta)
        if weights:
            for qid in still_unresolved:
                resolutions[qid] = resolve_offset(
                    qid, gold_by_qid[qid], doc_texts, query_meta, evidence_offsets_meta,
                    locus_weights=weights, window_chars=proxy_window_chars,
                )

    per_query: list[dict] = []
    by_source: dict[str, int] = {"metadata": 0, "string_match": 0, PROXY_SOURCE: 0}
    unresolved = 0

    for qid, gold_docs in gold_by_qid.items():
        offset, source, resolved_doc, reason = resolutions[qid]
        if offset is None:
            unresolved += 1
            per_query.append({
                "qid": qid, "gold_doc": resolved_doc, "offset": None,
                "bin": None, "source": "unresolved", "reason": reason, "per_mode": {},
            })
            continue

        by_source[source] = by_source.get(source, 0) + 1
        per_mode = {}
        for mode in mode_names:
            pred = modes[mode].get(qid, [])
            rank = pred.index(resolved_doc) + 1 if resolved_doc in pred else None
            per_mode[mode] = {"rank": rank, "hit": rank is not None and rank <= k}
        per_query.append({
            "qid": qid, "gold_doc": resolved_doc, "offset": offset,
            "bin": bin_for_offset(offset, bin_edges), "source": source,
            "resolution_class": ("proxy" if source == PROXY_SOURCE else "measured"),
            "per_mode": per_mode,
        })

    measured_rows = [q for q in per_query if q["source"] in MEASURED_SOURCES]
    proxy_rows = [q for q in per_query if q["source"] == PROXY_SOURCE]
    curves = _curves(measured_rows, mode_names, labels)
    proxy_curves = _curves(proxy_rows, mode_names, labels)

    queries_with_gold = len(per_query)
    resolved = queries_with_gold - unresolved
    return {
        "schema": "offset-recall.v2",
        "k": k,
        "bins": labels,
        "modes": mode_names,
        "resolution": {
            "queries_with_gold": queries_with_gold,
            "resolved": resolved,
            "resolved_measured": len(measured_rows),
            "resolved_proxy": len(proxy_rows),
            "unresolved": unresolved,
            "no_gold": no_gold,
            "by_source": by_source,
        },
        "curves": curves,
        "curves_resolution_sources": list(MEASURED_SOURCES),
        "curves_are_proxy": False,
        "proxy_curves": proxy_curves,
        "proxy": {
            "resolution_source": PROXY_SOURCE,
            "is_proxy": True,
            "n_resolved": len(proxy_rows),
            "window_chars": proxy_window_chars,
            "label": _PROXY_LABEL,
            "definition": (
                "character offset of the highest-scoring window_chars-wide window of the "
                "gold document, scored by corpus-local rarity of the distinct query terms "
                "it contains"
            ),
            "validity_caveat": (
                "answers 'where does the query's distinguishing content sit', which is a "
                "DIFFERENT question from 'where does the answer sit'. For citation-retrieval "
                "corpora the two largely coincide by construction; for factoid corpora they "
                "can diverge (query terms in the lead, answer deeper). Never compare a "
                "proxy_curves cell against a curves cell."
            ),
        },
        "per_query": per_query,
        "unresolved_qids": [q["qid"] for q in per_query if q["source"] == "unresolved"],
    }


def _curves(
    rows: list[dict], mode_names: list[str], labels: list[str]
) -> dict[str, dict[str, dict]]:
    """Per-(mode, bin) cells over ONE resolution class's rows. Callers pass disjoint row
    sets, which is what keeps measured and proxy curves from ever being merged."""
    curves: dict[str, dict[str, dict]] = {}
    for mode in mode_names:
        curves[mode] = {}
        for b in labels:
            in_bin = [q for q in rows if q["bin"] == b]
            n = len(in_bin)
            hits = sum(1 for q in in_bin if q["per_mode"][mode]["hit"])
            ranks = sorted(
                q["per_mode"][mode]["rank"] for q in in_bin
                if q["per_mode"][mode]["rank"] is not None
            )
            curves[mode][b] = {
                "n": n,
                "recall_at_k": (hits / n) if n else None,
                "hits": hits,
                "n_ranked": len(ranks),
                "median_rank_when_found": (statistics.median(ranks) if ranks else None),
            }
    return curves


def _locus_weights(doc_texts: dict[str, str], query_meta: dict[str, dict]) -> dict[str, int]:
    """Corpus-local rarity weights for every query term. Empty when no query carries text
    (then the proxy cannot run at all and the corpus-wide pass is skipped)."""
    terms: list[str] = []
    for meta in query_meta.values():
        q = meta.get("query")
        if isinstance(q, str) and q:
            terms.extend(tok for _, tok in query_locus.tokens_with_offsets(q))
    if not terms:
        return {}
    df, n_docs = query_locus.document_frequency(doc_texts.values())
    return query_locus.rarity_weights(terms, df, n_docs)


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
    proxy_window_chars: int = query_locus.DEFAULT_WINDOW_CHARS,
) -> dict:
    """Load a run dir + corpus dir from disk and build the offset-recall report (no backend)."""
    modes, qrels = load_run(run_dir)
    doc_texts, query_meta, evidence_offsets_meta = load_corpus(corpus_dir)
    return build_report(
        modes, qrels, doc_texts, query_meta, evidence_offsets_meta,
        k=k, bin_edges=bin_edges, proxy_window_chars=proxy_window_chars,
    )


def _curve_section(report: dict, key: str, header: str, mode_suffix: str) -> list[str]:
    lines = [f"  {header}"]
    curves = report[key]
    if not any(curves[m][b]["n"] for m in report["modes"] for b in report["bins"]):
        return lines + ["    (no queries resolved from this source)"]
    lines.append(f"  {'mode':<20}{'bin':<10}{'n':>5}{'recall@k':>11}{'medRank':>9}")
    for mode in report["modes"]:
        for b in report["bins"]:
            cell = curves[mode][b]
            recall = "-" if cell["recall_at_k"] is None else f"{cell['recall_at_k']:.3f}"
            med = ("-" if cell["median_rank_when_found"] is None
                   else f"{cell['median_rank_when_found']:g}")
            lines.append(
                f"  {mode + mode_suffix:<20}{b:<10}{cell['n']:>5}{recall:>11}{med:>9}"
            )
    return lines


def format_table(report: dict) -> str:
    """A compact deterministic stdout table: one row per (mode, bin), in two clearly
    separated sections — measured offsets and PROXY offsets. The proxy section carries the
    caveat inline and its mode column is suffixed ``~proxy``, so a proxy row can never be
    read as an answer-span row."""
    res = report["resolution"]
    lines = [
        f"Offset-recall (schema={report['schema']}, k={report['k']})",
        (
            f"  resolved={res['resolved']}/{res['queries_with_gold']} "
            f"unresolved={res['unresolved']} "
            f"(metadata={res['by_source'].get('metadata', 0)} "
            f"string={res['by_source'].get('string_match', 0)} "
            f"query_locus={res['by_source'].get(PROXY_SOURCE, 0)}) "
            f"no_gold={res['no_gold']}"
        ),
    ]
    lines += _curve_section(
        report, "curves",
        "[measured] offsets from generator metadata / answer-string location:", "",
    )
    lines += _curve_section(
        report, "proxy_curves",
        f"[{_PROXY_LABEL}]"
        f" window_chars={report['proxy']['window_chars']}:",
        "~proxy",
    )
    return "\n".join(lines)
