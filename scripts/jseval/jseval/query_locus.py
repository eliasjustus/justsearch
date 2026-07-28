"""Query-locus PROXY offset resolution (tempdoc 783 §B.1a).

The offset-recall instrument's two honest resolution sources — generator metadata and
answer-string location — resolve on ZERO real benchmark corpora: CLERC queries carry
``answer: ""`` (citation-retrieval: the gold *document* is the answer, there is no answer
span), and the MIRACL slices likewise carry no non-empty answer strings. Measured
2026-07-22: legal-clerc-200 resolved 0/200, miracl-de-2k 0/305, miracl-fr-2k 0/343.

This module adds a THIRD, explicitly-labeled source so curves can be computed on those
corpora at all:

    within the gold document, where does the query's *distinguishing* content sit?

Concretely: the character offset of the highest-scoring ``window_chars``-wide window of the
gold document, scored by the corpus-local rarity of the DISTINCT query terms it contains.

**Validity caveat — read before using a proxy curve.** This answers "where do the query's
distinguishing terms sit in the gold doc", which is a *different question* from "where does
the answer sit". For citation-retrieval corpora the two largely coincide by construction
(the cited passage is both what the query quotes around and the evidence), but for factoid
corpora they can diverge — query terms may sit in the lead while the answer is buried
deeper. A proxy-derived curve is therefore NOT interchangeable with a metadata- or
answer-span-derived curve, and :mod:`offset_recall` never merges the two.

Determinism / cost. No network, no model, no external resource, no per-language table —
rarity is computed corpus-locally, so common words fall out by weight rather than by a
stopword list. Building document frequency is one pass over the corpus,
``O(total tokens)``. Locating one query's locus is ``O(H)`` where ``H`` is the number of
gold-document token positions carrying a *weighted* query term (a two-pointer sweep over
those positions only) — trivially cheap for the 200-350-query corpora this targets, even
with ~30 k-character documents.
"""
from __future__ import annotations

import math
import re
from collections import Counter
from collections.abc import Iterable

# Window width in characters. 1 000 matches the finest DEFAULT_BIN_EDGES bracket — the
# proxy is never more precise than the curve it feeds.
DEFAULT_WINDOW_CHARS = 1000

# Rarity weights are carried as scaled integers so window scores accumulate and decumulate
# EXACTLY as the two-pointer sweep slides (float drift could flip a strict-greater compare
# and make the "best window" depend on traversal order).
_IDF_SCALE = 1_000_000

# Unicode-general token runs (letters/digits, apostrophe-joined). Follows the token-boundary
# convention of ``corpus_leak._tokenize`` ([a-z0-9']+ over lowercased text) but is
# offset-preserving — it runs against the RAW text and lowercases each match, so a returned
# index is always a valid index into the original string. The same function feeds BOTH the
# document-frequency pass and the window sweep, so the two can never disagree on vocabulary.
_TOKEN_RE = re.compile(r"[^\W_]+(?:['’][^\W_]+)*", re.UNICODE)


def tokens_with_offsets(text: str) -> list[tuple[int, str]]:
    """``[(char_offset, lowercased_token), …]`` in document order."""
    if not text:
        return []
    return [(m.start(), m.group(0).lower()) for m in _TOKEN_RE.finditer(text)]


def document_frequency(doc_texts: Iterable[str]) -> tuple[dict[str, int], int]:
    """Per-document token frequency over a corpus. Returns ``(df, n_docs)``.

    Token-boundary by construction (whole ``_TOKEN_RE`` runs, never substrings); a term
    counts once per document no matter how often it repeats.
    """
    df: Counter[str] = Counter()
    n_docs = 0
    for text in doc_texts:
        n_docs += 1
        df.update({tok for _, tok in tokens_with_offsets(text)})
    return dict(df), n_docs


def rarity_weights(
    terms: Iterable[str], df: dict[str, int], n_docs: int
) -> dict[str, int]:
    """Scaled-integer BM25-style IDF per term, floored at 0.

    ``log(1 + (N - df + 0.5) / (df + 0.5))``: a term present in every document takes the
    minimum weight — negligible against a term present in few (and → 0 as the corpus grows,
    e.g. ~1/1900th of a df=1 term at N=200) — so ubiquitous words drop out of the scoring
    WITHOUT any language-specific stopword list: the corpus decides what is distinguishing.
    """
    weights: dict[str, int] = {}
    for term in set(terms):
        d = df.get(term, 0)
        idf = math.log(1.0 + (n_docs - d + 0.5) / (d + 0.5)) if n_docs > 0 else 0.0
        weights[term] = max(0, round(idf * _IDF_SCALE))
    return weights


def locate_query_locus(
    text: str,
    query: str,
    weights: dict[str, int],
    *,
    window_chars: int = DEFAULT_WINDOW_CHARS,
) -> tuple[int, int] | None:
    """Return ``(char_offset, score)`` of the query's locus in ``text``, or ``None``.

    The offset is the start of the first weighted query-term occurrence inside the
    best-scoring window; ``score`` is the scaled sum of DISTINCT weighted query terms in
    that window (distinct, so one repeated term cannot outrank genuine term coverage).
    Ties resolve to the EARLIEST window, making the result order-independent.

    ``None`` when the query contributes no weighted term (empty query, or every query term
    is corpus-ubiquitous) or no such term occurs in ``text``. A caller must treat ``None``
    as unresolved, never as offset 0.
    """
    if not text or not query or window_chars <= 0:
        return None
    query_terms = {tok for _, tok in tokens_with_offsets(query) if weights.get(tok, 0) > 0}
    if not query_terms:
        return None
    hits = [(off, tok) for off, tok in tokens_with_offsets(text) if tok in query_terms]
    if not hits:
        return None

    counts: Counter[str] = Counter()
    score = 0
    best_offset, best_score = hits[0][0], -1
    right = 0
    for left in range(len(hits)):
        while right < len(hits) and hits[right][0] < hits[left][0] + window_chars:
            tok = hits[right][1]
            if counts[tok] == 0:
                score += weights[tok]
            counts[tok] += 1
            right += 1
        if score > best_score:
            best_offset, best_score = hits[left][0], score
        tok = hits[left][1]
        counts[tok] -= 1
        if counts[tok] == 0:
            score -= weights[tok]
    return best_offset, best_score
