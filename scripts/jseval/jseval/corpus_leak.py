"""Gold-vs-native leak measurement (tempdoc 767).

Pure, offline, deterministic measurement functions that ask one question in five
different ways: are the planted "gold" documents in an eval corpus distinguishable
from the ~960 real host documents (legal opinions / emails) that surround them as
distractors? Three verified defects motivate this module:

1. A boilerplate paragraph is byte-identical across every gold doc — one grep
   selects 280/280 gold docs (see :func:`ngram_selectivity_report`).
2. Gold docs run ~730 words while real distractors run ~140 words, so document
   length alone may identify them (see :func:`length_profile_report`).
3. Gold document IDs are minted entity surfaces (``breldac18``) while the real
   CLERC hosts are bare integers (``1000731``), so a single character-class rule
   over the ID alone selects 40/40 gold docs with zero false positives WITHOUT
   reading any document body — and is visible in a plain directory listing of the
   ``<doc_id>.txt`` files the agent-utility harness materializes (see
   :func:`id_shape_report`).

**The null comes from inside the cell.** The real distractors in the same corpus
ARE the native sample. No function here reaches outside the corpus for a
comparison population — "is this abnormal?" is always answered by comparing the
gold subset against the non-gold (native) subset OF THE SAME corpus. This makes
every measure per-host by construction: a legal corpus is compared against its own
legal distractors, an email corpus against its own email distractors, and the
module never needs to know which domain it is looking at.

**Declare your unit — every function's result records ``matching_mode`` and
``unit``.** A prior measurement in this project was badly wrong because it used a
SUBSTRING match (searching "spa" matched "space", "newspaper", "disparate") and
compared one corpus counted per-FILE against another counted per-LINE. Every ratio
in this module is computed with the SAME unit (documents) and the SAME matching
mode (token-boundary, never substring) on both the gold and native side — mixing
units or matching modes silently invalidates a ratio without raising an error, so
each report makes its own units and mode explicit in its output rather than only in
a docstring.

Determinism: this project never sets ``PYTHONHASHSEED``, so nothing here iterates a
``set`` of strings in a way that reaches output ordering, and nothing calls
``hash()``. Every ordered output is produced via ``sorted()``; the one place this
module samples (the native n-gram base-rate control) takes an explicit
``random.Random(seed)`` instance rather than the global ``random`` module.
"""

from __future__ import annotations

import re
import statistics
from random import Random

TOOL_VERSION = "jseval.corpus_leak/1"

# Token-boundary word regex — matches context_coverage.tokenize_evidence /
# corpus_query_variant._tokenize's [a-z0-9]+ convention. Never substring: this is
# always run through re.findall against a lowercased string, so "spa" cannot match
# inside "newspaper" — the whole run of [a-z0-9]+ characters is captured, not a
# substring search.
_TOKEN_RE = re.compile(r"[a-z0-9']+")

# English stopwords + the fabricated-corpus question-scaffolding vocabulary (tempdoc
# 767 brief) — both are noise for query_overlap_report's content-token comparison.
_STOPWORDS = frozenset({
    "a", "an", "and", "are", "as", "at", "be", "been", "being", "but", "by",
    "can", "could", "did", "do", "does", "for", "from", "had", "has", "have",
    "he", "her", "hers", "him", "his", "how", "if", "in", "into", "is", "it",
    "its", "itself", "may", "might", "must", "no", "not", "of", "on", "or",
    "our", "ours", "she", "should", "so", "than", "that", "the", "their",
    "theirs", "them", "themselves", "then", "there", "these", "they", "this",
    "those", "to", "under", "until", "up", "was", "were", "what", "when",
    "where", "which", "while", "who", "whom", "why", "will", "with", "would",
    "you", "your", "yours",
    # question-scaffolding words specific to the fabricated-corpus query template
    # ("What is the value associated with the designer/founder/builder/leader of
    # ... ?") — these repeat in every query regardless of which doc answers it, so
    # counting them as "overlap" would make every query look falsely well-anchored.
    "value", "associated", "designer", "founder", "builder", "leader",
})

_DEFAULT_SEED = 767


def _tokenize(text: str) -> list[str]:
    """Lowercase + extract [a-z0-9']+ runs — the one tokenization rule this module
    uses. Token-boundary by construction: re.findall captures whole runs, so a short
    query term never matches as a substring of a longer unrelated token."""
    return _TOKEN_RE.findall((text or "").lower())


def _content_tokens(text: str) -> list[str]:
    """Tokenize then drop stopwords/scaffolding — for query/doc overlap and rare-token
    anchor measures, where stopwords carry no discriminative signal."""
    return [t for t in _tokenize(text) if t not in _STOPWORDS]


def _doc_text(doc: dict) -> str:
    return " ".join(part for part in (doc.get("title") or "", doc.get("text") or "") if part)


def _gold_ids(queries: list[dict] | None) -> set[str]:
    ids: set[str] = set()
    for q in queries or []:
        ids.update(q.get("evidence_ids") or [])
    return ids


def _split_gold_native(docs: list[dict], queries: list[dict] | None) -> tuple[list[dict], list[dict]]:
    gold_ids = _gold_ids(queries)
    gold = [d for d in docs if d.get("_id") in gold_ids]
    native = [d for d in docs if d.get("_id") not in gold_ids]
    return gold, native


def _word_count(doc: dict) -> int:
    text = doc.get("text") or ""
    return len(text.split())


def _percentile(sorted_values: list[float], pct: float) -> float:
    """Nearest-rank percentile over an already-sorted list (0 <= pct <= 100)."""
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return sorted_values[0]
    k = (pct / 100.0) * (len(sorted_values) - 1)
    lo = int(k)
    hi = min(lo + 1, len(sorted_values) - 1)
    frac = k - lo
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * frac


def _distribution(values: list[float]) -> dict:
    if not values:
        return {"min": 0, "median": 0, "p5": 0, "p95": 0, "max": 0, "mean": 0.0, "n": 0}
    s = sorted(values)
    return {
        "min": s[0],
        "median": statistics.median(s),
        "p5": _percentile(s, 5),
        "p95": _percentile(s, 95),
        "max": s[-1],
        "mean": statistics.fmean(s),
        "n": len(s),
    }


def _id_shape_class(doc_id: str) -> str:
    """The coarse character-class bucket a document ID falls in.

    Exactly one bucket per ID (the rule family below relies on these being disjoint,
    so a rule's fire set is unambiguous). Ordered most-specific first.
    """
    if not doc_id:
        return "empty"
    if doc_id.isdigit():
        return "all-digits"
    if doc_id.isalpha() and doc_id.islower():
        return "all-lower-alpha"
    if doc_id.isalnum() and doc_id.lower() == doc_id:
        return "lower-alphanumeric"
    if " " in doc_id:
        return "contains-space"
    if any(ch.isupper() for ch in doc_id):
        return "contains-uppercase"
    return "contains-punctuation"


_TRAILING_INT_RE = re.compile(r"(\d+)$")
_LEADING_INT_RE = re.compile(r"^(\d+)")


def _trailing_int(doc_id: str) -> int | None:
    m = _TRAILING_INT_RE.search(doc_id or "")
    return int(m.group(1)) if m else None


def _leading_int(doc_id: str) -> int | None:
    m = _LEADING_INT_RE.match(doc_id or "")
    return int(m.group(1)) if m else None


def _whole_int(doc_id: str) -> int | None:
    return int(doc_id) if (doc_id or "").isdigit() else None


#: The numeric-magnitude extractors the ``f(id) <= k`` axis ranges over. Ordered
#: most-diagnostic first; the labels are the rule text a reader sees in a report.
_NUMERIC_ID_EXTRACTORS = (
    ("trailing_int", _trailing_int),
    ("leading_int", _leading_int),
    ("int", _whole_int),
)


def _numeric_id_rules(
    positive_ids: list[str], all_ids: list[str]
) -> list[tuple[str, frozenset[str]]]:
    """The ``f(id) <= k`` numeric-magnitude axis, for each extractor in
    :data:`_NUMERIC_ID_EXTRACTORS`.

    Why this axis exists: a generator that numbers its gold/evidence documents
    ``1..N`` and its distractors ``N+1..M`` leaks the entire gold set through the
    integer alone, with zero range overlap — ``trailing_int(id) <= N`` is then a
    PERFECT separator (Youden J = 1.0) that no character-class, length, or 1-3
    character affix rule can see. Measured on the committed
    ``635-corpora/needle-burial-v1`` cell, whose gold IDs end in ``1..40`` and whose
    distractors end in ``41..280``: the pre-existing rule family reported its best
    separator as ``len(id) <= 9`` at J = 0.379, i.e. it MISSED a perfect enumeration
    channel. That is the one failure mode a gate must not have.

    Threshold selection is an exact argmax/argmin over every observed boundary — the
    same answer as enumerating one rule per distinct value the way the ``len(id) <= k``
    axis does, computed instead by a single sorted cumulative scan so the axis stays
    O(n log n) rather than O(n^2) when IDs carry thousands of distinct integers (a 10k
    cell). Both extremes are emitted because :func:`_best_id_rule` reports a
    negatively-separating rule in its negated form: the argmin threshold is what
    becomes ``not (trailing_int(id) <= k)``, which is exactly as cheap for an agent to
    type as the un-negated form.

    IDs the extractor finds no integer in simply do not fire the rule (they are not
    silently counted as ``<= k``), and the null in :func:`id_shape_report` enumerates
    its pseudo-gold candidates through this same function, so the control stays
    apples-to-apples.
    """
    positives = set(positive_ids)
    universe = sorted(set(all_ids))
    n_pos = sum(1 for i in universe if i in positives)
    n_neg = len(universe) - n_pos
    rules: list[tuple[str, frozenset[str]]] = []
    if not n_pos or not n_neg:
        return rules

    for name, extract in _NUMERIC_ID_EXTRACTORS:
        valued = sorted(
            (value, i)
            for i in universe
            for value in (extract(i),)
            if value is not None
        )
        if not valued:
            continue
        best_k = worst_k = None
        best_j = worst_j = None
        tp = fp = 0
        idx = 0
        while idx < len(valued):
            k = valued[idx][0]
            while idx < len(valued) and valued[idx][0] == k:
                if valued[idx][1] in positives:
                    tp += 1
                else:
                    fp += 1
                idx += 1
            j = tp / n_pos - fp / n_neg
            if best_j is None or j > best_j:
                best_j, best_k = j, k
            if worst_j is None or j < worst_j:
                worst_j, worst_k = j, k
        for k in sorted({best_k, worst_k}):
            rules.append((f"{name}(id) <= {k}", frozenset(
                i for i in universe
                if (value := extract(i)) is not None and value <= k)))
    return rules


def _id_rules(positive_ids: list[str], all_ids: list[str]) -> list[tuple[str, frozenset[str]]]:
    """Enumerate the candidate separating rules as (label, set-of-IDs-the-rule-fires-on).

    The family is deliberately small and *simple* — the question this answers is not
    "can a classifier learn the gold set" (any classifier can memorize 40 strings) but
    "can a rule an agent would plausibly type into ``grep`` or a shell glob separate
    them". Four axes:

    * **character class** — the ``_id_shape_class`` buckets (this is what catches the
      committed-cell defect: ``lower-alphanumeric`` vs ``all-digits``).
    * **length** — ``len <= k`` for every observed length boundary ``k``.
    * **numeric magnitude** — ``trailing_int / leading_int / int (id) <= k``, see
      :func:`_numeric_id_rules` (this is what catches the ``635-corpora`` defect:
      gold numbered ``1..N``, distractors ``N+1..M``, zero overlap).
    * **prefix / suffix** — every 1..3-character prefix and suffix occurring in the
      POSITIVE class. Enumerating from the positive class (rather than corpus-wide)
      is what keeps the null in :func:`id_shape_report` an apples-to-apples control:
      the pseudo-gold sample gets its candidate rules enumerated the same way.
    """
    rules: list[tuple[str, frozenset[str]]] = []
    universe = sorted(set(all_ids))

    for cls in sorted({_id_shape_class(i) for i in universe}):
        rules.append((f"shape_class == {cls!r}", frozenset(
            i for i in universe if _id_shape_class(i) == cls)))

    for k in sorted({len(i) for i in universe}):
        rules.append((f"len(id) <= {k}", frozenset(i for i in universe if len(i) <= k)))

    rules.extend(_numeric_id_rules(positive_ids, all_ids))

    affixes: set[tuple[str, str]] = set()
    for i in sorted(set(positive_ids)):
        for width in (1, 2, 3):
            if len(i) >= width:
                affixes.add(("prefix", i[:width]))
                affixes.add(("suffix", i[-width:]))
    for kind, affix in sorted(affixes):
        if kind == "prefix":
            rules.append((f"id.startswith({affix!r})", frozenset(
                i for i in universe if i.startswith(affix))))
        else:
            rules.append((f"id.endswith({affix!r})", frozenset(
                i for i in universe if i.endswith(affix))))
    return rules


def _best_id_rule(positive_ids: list[str], negative_ids: list[str]) -> dict:
    """The single most separating rule over IDs alone, scored by Youden's J.

    ``J = TPR - FPR`` (fraction of positives the rule fires on, minus the fraction of
    negatives it fires on). ``J == 0`` means the rule is no better than firing at
    random; ``J == 1`` means it fires on every positive and no negative. A rule whose
    J is negative is reported in its NEGATED form (``not (...)``) with ``J = -J``,
    because an agent can trivially invert a rule — treating ``shape_class ==
    'all-digits'`` as harmless while its complement perfectly selects the gold set
    would be a hole straight through this measure.
    """
    positives, negatives = set(positive_ids), set(negative_ids)
    n_pos, n_neg = len(positives), len(negatives)
    if not n_pos or not n_neg:
        return {"rule": None, "youden_j": 0.0, "precision": 0.0, "recall": 0.0, "n_fired": 0}

    best = None
    for label, fired in _id_rules(sorted(positives), sorted(positives | negatives)):
        tp = len(fired & positives)
        fp = len(fired & negatives)
        j = tp / n_pos - fp / n_neg
        if j < 0:
            label = f"not ({label})"
            tp, fp = n_pos - tp, n_neg - fp
            j = -j
        n_fired = tp + fp
        candidate = {
            "rule": label,
            "youden_j": j,
            "precision": (tp / n_fired) if n_fired else 0.0,
            "recall": tp / n_pos,
            "n_fired": n_fired,
            "true_positives": tp,
            "false_positives": fp,
        }
        # Tie-break on precision then the label text, so the reported rule is stable
        # across runs and interpreters rather than dependent on enumeration order.
        key = (candidate["youden_j"], candidate["precision"], candidate["rule"])
        if best is None or key > (best["youden_j"], best["precision"], best["rule"]):
            best = candidate
    return best


#: Draws behind :func:`id_shape_report`'s null. Measured (tempdoc 767): the null is a
#: MAX over draws, so it is monotone in this count and under-powered when it is small.
#: On the 1000-doc CLERC cell the same-population null max is 0.151 at 5 draws but
#: 0.227 by 20 and saturates at ~0.233 by 100 — and a same-population 40-doc draw
#: exceeds J = 0.185 in 12.5% of 200 draws. At 5 draws a shape-matched corpus therefore
#: fails roughly one time in eight on sampling noise alone, which is the false-positive
#: rate this gate cannot carry once certification depends on it. 25 sits past the knee
#: on every corpus measured while leaving real leaks untouched: the two known-leaking
#: cells score J = 1.0 against a null that saturates below 0.29.
_ID_NULL_SAMPLES = 25


def id_shape_report(
    docs: list[dict], queries: list[dict] | None = None, *,
    n_null_samples: int = _ID_NULL_SAMPLES,
) -> dict:
    """Can any simple rule over document IDs ALONE separate gold from native?

    (tempdoc 767 defect #3.) This is the cheapest enumeration channel a corpus can
    carry: it costs an agent nothing to evaluate — no document body, no search, just
    the file names — so a leak here dominates every text-level measure in this module.
    Measured on the committed ``en-legal-clerc-1k-verbose`` cell, the gold set is 40
    ``lower-alphanumeric`` IDs among 960 ``all-digits`` natives, which one character
    class separates at precision 1.0 / recall 1.0.

    ``matching_mode`` is ``"whole-id"``: every rule is evaluated against the complete
    ID string (``startswith``/``endswith``/``len``/character class), never a substring
    search inside it — the same never-substring discipline the token measures follow.
    ``unit`` is ``"per-document"``: one ID, one vote.

    The null, as everywhere in this module, comes from inside the cell.
    ``native_base_rate`` is the best Youden's J the SAME rule family achieves when a
    seeded, gold-sized subset of NATIVE IDs is relabelled as pseudo-gold and scored
    against the remaining natives — i.e. how separable a group of same-population IDs
    looks purely from sampling noise. It is the MAX over ``n_null_samples`` independent
    seeded draws, which biases the null upward on purpose: this measure should not
    fail a shape-matched corpus because one draw happened to be unusually bland.

    ``passed`` is ``best_rule.youden_j <= native_base_rate``. It is ``None`` when
    either side is empty (nothing to compare), never ``True`` — an absent measurement
    is not a passing one.
    """
    gold, native = _split_gold_native(docs, queries)
    gold_ids = [str(d.get("_id")) for d in gold if d.get("_id") is not None]
    native_ids = [str(d.get("_id")) for d in native if d.get("_id") is not None]
    n_gold, n_native = len(gold_ids), len(native_ids)

    if not n_gold or not n_native:
        return {
            "n_gold": n_gold,
            "n_native": n_native,
            "best_rule": None,
            "separability": 0.0,
            "native_base_rate": 0.0,
            "gold_shape_classes": {},
            "native_shape_classes": {},
            "matching_mode": "whole-id",
            "unit": "per-document",
            "method": "id-shape-rule-separability",
            "passed": None,
        }

    best = _best_id_rule(gold_ids, native_ids)

    # Null control: gold-blind, drawn only from natives.
    sorted_native = sorted(set(native_ids))
    sample_size = min(n_gold, len(sorted_native) - 1) if len(sorted_native) > 1 else 0
    null_j = 0.0
    for offset in range(n_null_samples):
        if sample_size <= 0:
            break
        rng = Random(_DEFAULT_SEED + offset)
        pseudo_gold = sorted(rng.sample(sorted_native, sample_size))
        pseudo_gold_set = set(pseudo_gold)
        pseudo_native = [i for i in sorted_native if i not in pseudo_gold_set]
        null_best = _best_id_rule(pseudo_gold, pseudo_native)
        null_j = max(null_j, null_best["youden_j"])

    def _class_counts(ids: list[str]) -> dict[str, int]:
        counts: dict[str, int] = {}
        for i in ids:
            cls = _id_shape_class(i)
            counts[cls] = counts.get(cls, 0) + 1
        return {k: counts[k] for k in sorted(counts)}

    return {
        "n_gold": n_gold,
        "n_native": n_native,
        "best_rule": best,
        "separability": best["youden_j"],
        "native_base_rate": null_j,
        "native_base_rate_seed": _DEFAULT_SEED,
        "native_base_rate_samples": n_null_samples,
        "gold_shape_classes": _class_counts(gold_ids),
        "native_shape_classes": _class_counts(native_ids),
        "gold_length": _distribution([float(len(i)) for i in gold_ids]),
        "native_length": _distribution([float(len(i)) for i in native_ids]),
        "matching_mode": "whole-id",
        "unit": "per-document",
        "method": "id-shape-rule-separability",
        "passed": best["youden_j"] <= null_j,
    }


def token_document_frequency(docs: list[dict], terms: list[str]) -> dict:
    """Token-boundary, per-document document frequency for ``terms``.

    For each term in ``terms`` (already-tokenized, e.g. from :func:`_tokenize` or
    ``_content_tokens``), counts the number of documents in ``docs`` whose
    tokenized title+text contains that exact token at least once — never a
    substring match. Returns per-term counts plus the total document count so a
    caller can compute a coverage ratio without re-deriving it.
    """
    term_set = sorted(set(terms))
    counts: dict[str, int] = {t: 0 for t in term_set}
    for d in docs:
        doc_tokens = set(_tokenize(_doc_text(d)))
        for t in term_set:
            if t in doc_tokens:
                counts[t] += 1
    return {
        "counts": counts,
        "n_docs": len(docs),
        "matching_mode": "token-boundary",
        "unit": "per-document",
        "method": "token-document-frequency",
    }


def _ngrams(tokens: list[str], n: int) -> set[tuple[str, ...]]:
    if len(tokens) < n:
        return set()
    return {tuple(tokens[i:i + n]) for i in range(len(tokens) - n + 1)}


def ngram_selectivity_report(
    docs: list[dict], queries: list[dict] | None = None, *, n: int = 5, top_k: int = 20
) -> dict:
    """For every contiguous n-gram of tokens, what fraction of GOLD docs contain it vs.
    what fraction of NON-GOLD (native) docs contain it (tempdoc 767 defect #1: a
    boilerplate paragraph byte-identical across every gold doc).

    Coverage is per-document (does the n-gram appear at least once in this doc?), not
    per-occurrence — a payload copy-pasted once per doc and a payload repeated ten
    times both count as coverage 1 for that doc. ``matching_mode`` is
    "token-boundary": the n-gram is a tuple of whole tokens, so a partial-token match
    never counts.

    The worst offenders are n-grams with HIGH gold coverage and LOW native coverage —
    a perfect grep anchor that would let an agent select every gold doc with one
    search. ``max_gold_coverage`` is the single most gold-selective n-gram's gold
    coverage.

    ``native_base_rate`` is the null this compares against: gold-blind, computed by
    drawing a deterministic (seeded) subset of NATIVE docs the same size as the gold
    set (or all native docs if fewer), then finding the max coverage any n-gram
    achieves among just that subset (checked against the same subset it was drawn
    from — this asks "how selective can an n-gram look purely from sampling noise,
    with no artificial payload at all?"). A payload is acceptable when
    ``max_gold_coverage`` does not exceed ``native_base_rate`` by more than sampling
    noise would explain; this function reports both numbers and leaves the actual
    threshold comparison to the caller.
    """
    gold, native = _split_gold_native(docs, queries)
    n_gold, n_native = len(gold), len(native)

    gold_ngram_docs: dict[tuple[str, ...], int] = {}
    for d in gold:
        for gram in _ngrams(_tokenize(_doc_text(d)), n):
            gold_ngram_docs[gram] = gold_ngram_docs.get(gram, 0) + 1

    native_ngram_sets: list[set[tuple[str, ...]]] = [
        _ngrams(_tokenize(_doc_text(d)), n) for d in native
    ]

    # Native per-gram document counts, single pass over native_ngram_sets — NOT a
    # per-offender O(n_native) scan. At real-cell scale (gold docs are full-length
    # host opinions, not short fabricated stubs) a corpus can carry 100k+ distinct
    # gold n-grams; scanning every native doc's set per offender is O(offenders *
    # n_native) (measured: ~116M set lookups / ~6s on a 1000-doc cell, tempdoc 767
    # real-cell validation) and would scale far worse on a 10k-doc cell. This dict
    # is built once in O(total native n-grams), then every offender lookup is O(1).
    native_ngram_doc_counts: dict[tuple[str, ...], int] = {}
    for s in native_ngram_sets:
        for gram in s:
            native_ngram_doc_counts[gram] = native_ngram_doc_counts.get(gram, 0) + 1

    offenders = []
    for gram, gold_count in gold_ngram_docs.items():
        gold_coverage = gold_count / n_gold if n_gold else 0.0
        native_count = native_ngram_doc_counts.get(gram, 0)
        native_coverage = native_count / n_native if n_native else 0.0
        offenders.append({
            "ngram": " ".join(gram),
            "gold_coverage": gold_coverage,
            "native_coverage": native_coverage,
            "gold_count": gold_count,
            "native_count": native_count,
            "selectivity_gap": gold_coverage - native_coverage,
        })

    offenders.sort(key=lambda o: (-o["selectivity_gap"], -o["gold_coverage"], o["ngram"]))
    max_gold_coverage = offenders[0]["gold_coverage"] if offenders else 0.0

    # Native base rate: the null. Draw a deterministic subset of native docs the same
    # size as the gold set (capped at n_native), then find the max per-subset n-gram
    # coverage WITHIN that subset alone — this is what "selective-looking by chance"
    # looks like with zero artificial payload.
    rng = Random(_DEFAULT_SEED)
    sample_size = min(n_gold, n_native) if n_gold else n_native
    native_indices = sorted(range(n_native))
    sample_indices = sorted(rng.sample(native_indices, sample_size)) if sample_size else []
    sample_sets = [native_ngram_sets[i] for i in sample_indices]
    native_base_rate = 0.0
    if sample_sets:
        sample_ngram_counts: dict[tuple[str, ...], int] = {}
        for s in sample_sets:
            for gram in s:
                sample_ngram_counts[gram] = sample_ngram_counts.get(gram, 0) + 1
        if sample_ngram_counts:
            native_base_rate = max(sample_ngram_counts.values()) / len(sample_sets)

    return {
        "n": n,
        "n_gold": n_gold,
        "n_native": n_native,
        "top_offenders": offenders[:top_k],
        "max_gold_coverage": max_gold_coverage,
        "native_base_rate": native_base_rate,
        "native_base_rate_seed": _DEFAULT_SEED,
        "native_base_rate_sample_size": sample_size,
        "matching_mode": "token-boundary",
        "unit": "per-document",
        "method": "ngram-coverage-gap",
        "passed": max_gold_coverage <= native_base_rate if (n_gold and n_native) else None,
    }


def length_profile_report(docs: list[dict], queries: list[dict] | None = None) -> dict:
    """Word-count distribution for gold vs native docs (tempdoc 767 defect #2: gold
    docs ~730 words vs. native distractors ~140 words — length alone may identify
    the gold set).

    ``unit`` is "per-document" (word count is a per-document scalar; there is no
    per-line variant to conflate it with here, but the field is included for the
    same-shape convention every report in this module follows). Reports min/median/
    p5/p95/max/mean for each side, whether the gold distribution falls inside the
    native [p5, p95] band, and ``separability`` — the fraction of gold docs whose
    word count falls OUTSIDE the native [p5, p95] range (0.0 = gold is
    indistinguishable from native by length; 1.0 = every gold doc is a length
    outlier relative to native).
    """
    gold, native = _split_gold_native(docs, queries)
    gold_wc = [_word_count(d) for d in gold]
    native_wc = [_word_count(d) for d in native]

    gold_dist = _distribution(gold_wc)
    native_dist = _distribution(native_wc)

    native_p5, native_p95 = native_dist["p5"], native_dist["p95"]
    gold_inside_band = native_p5 <= gold_dist["median"] <= native_p95 if native_wc else None

    if gold_wc and native_wc:
        n_outside = sum(1 for w in gold_wc if w < native_p5 or w > native_p95)
        separability = n_outside / len(gold_wc)
    else:
        separability = None

    return {
        "n_gold": len(gold),
        "n_native": len(native),
        "gold": gold_dist,
        "native": native_dist,
        "gold_median_inside_native_p5_p95": gold_inside_band,
        "separability": separability,
        "matching_mode": "n/a",
        "unit": "per-document",
        "method": "word-count-distribution-gap",
        "passed": (separability is not None and separability < 1.0) if separability is not None else None,
    }


def query_overlap_report(docs: list[dict], queries: list[dict] | None = None) -> dict:
    """Content-token overlap between each query's text and its gold evidence
    document(s) (tempdoc 767).

    **Polarity warning — this is the opposite of the other measures in this module.**
    Every other report here treats a HIGH gold-vs-native gap as bad (it means an
    agent could isolate gold docs by an artifact of corpus construction). Here, a
    LOW overlap is GOOD: it measures whether a grep/keyword-search agent could find
    the gold doc's entry point using only words that already appear in the query
    itself — the query is expected to share little vocabulary with its answer
    document by design (a good eval question does not leak its own answer's
    wording), so low overlap is the healthy, intended case and high overlap would
    indicate the query is trivially answerable by keyword lookup.

    Tokenizes both sides with :func:`_content_tokens` (lowercase, ``[a-z0-9']+``,
    stopwords + question-scaffolding words removed). For each query with at least
    one evidence doc present in ``docs``, computes the Jaccard index and the overlap
    coefficient (``|A ∩ B| / min(|A|, |B|)``) between the query's content-token set
    and the UNION of its evidence docs' content-token sets. Reports the
    min/median/max/mean of both statistics across queries and the count of
    zero-overlap queries (queries sharing no content token with their gold doc(s) —
    the best-case outcome under this measure's polarity).
    """
    by_id = {d.get("_id"): d for d in docs if d.get("_id")}
    per_query = []
    for q in queries or []:
        evidence_ids = q.get("evidence_ids") or []
        evidence_docs = [by_id[eid] for eid in evidence_ids if eid in by_id]
        if not evidence_docs:
            continue
        query_tokens = set(_content_tokens(q.get("query") or ""))
        doc_tokens: set[str] = set()
        for ed in evidence_docs:
            doc_tokens.update(_content_tokens(_doc_text(ed)))

        intersection = query_tokens & doc_tokens
        union = query_tokens | doc_tokens
        jaccard = len(intersection) / len(union) if union else 0.0
        smaller = min(len(query_tokens), len(doc_tokens))
        overlap_coefficient = len(intersection) / smaller if smaller else 0.0

        per_query.append({
            "query": q.get("query"),
            "evidence_ids": sorted(evidence_ids),
            "jaccard": jaccard,
            "overlap_coefficient": overlap_coefficient,
            "n_shared_tokens": len(intersection),
            "shared_tokens": sorted(intersection),
        })

    jaccards = [p["jaccard"] for p in per_query]
    overlaps = [p["overlap_coefficient"] for p in per_query]
    n_zero_overlap = sum(1 for p in per_query if p["n_shared_tokens"] == 0)

    return {
        "n_queries": len(per_query),
        "n_queries_total": len(queries or []),
        "per_query": per_query,
        "jaccard_distribution": _distribution(jaccards),
        "overlap_coefficient_distribution": _distribution(overlaps),
        "n_zero_overlap": n_zero_overlap,
        "matching_mode": "token-boundary",
        "unit": "per-query",
        "method": "content-token-jaccard-overlap",
        "polarity": "low-is-good",
    }


def rare_token_leak_report(
    docs: list[dict], queries: list[dict] | None = None, *, df_floor: int = 5
) -> dict:
    """For each query, find content tokens that appear in BOTH the query text AND its
    gold evidence document(s), whose corpus-wide document frequency is <= ``df_floor``
    (tempdoc 767 — the highest-priority measure in this module).

    Such a token is a perfect grep anchor: a document frequency of, say, 1 means the
    token pins exactly one document corpus-wide, so an agent searching for that token
    finds the gold doc without needing to understand the query at all. This is
    reported per-query (not just as an aggregate mean) because a healthy-looking mean
    can hide the case where most queries individually leak a df=1 anchor — the
    aggregate and the per-query view can tell opposite stories.

    Tokenizes with :func:`_content_tokens` (stopwords + question-scaffolding
    removed — those are never rare anchors by construction, they are near-universal).
    Document frequency is computed corpus-wide (gold + native together) via
    :func:`token_document_frequency`, token-boundary, per-document.

    Reports, per query: the leaked tokens with their df (sorted ascending by df, then
    token, so the most dangerous anchor is first), and whether the query leaks at all.
    Also reports, across several df thresholds (1, 2, 5, 10, 25), the fraction of
    queries leaking at least one token at or below that threshold — the widening
    fraction across thresholds shows how quickly the leak surface grows as the
    anchor requirement relaxes.
    """
    thresholds = (1, 2, 5, 10, 25)
    by_id = {d.get("_id"): d for d in docs if d.get("_id")}

    # Collect every candidate content token across all queries' (query ∩ evidence-doc)
    # pairs up front, then do ONE corpus-wide DF pass — avoids re-scanning the whole
    # corpus per query.
    query_candidates: list[tuple[dict, set[str]]] = []
    all_terms: set[str] = set()
    for q in queries or []:
        evidence_ids = q.get("evidence_ids") or []
        evidence_docs = [by_id[eid] for eid in evidence_ids if eid in by_id]
        if not evidence_docs:
            query_candidates.append((q, set()))
            continue
        query_tokens = set(_content_tokens(q.get("query") or ""))
        doc_tokens: set[str] = set()
        for ed in evidence_docs:
            doc_tokens.update(_content_tokens(_doc_text(ed)))
        shared = query_tokens & doc_tokens
        query_candidates.append((q, shared))
        all_terms.update(shared)

    df_report = token_document_frequency(docs, sorted(all_terms))
    df_counts = df_report["counts"]
    max_df = max(df_floor, *thresholds)

    per_query = []
    for q, shared in query_candidates:
        leaked = sorted(
            (
                {"token": t, "df": df_counts[t]}
                for t in shared
                if df_counts.get(t, 0) <= max_df
            ),
            key=lambda item: (item["df"], item["token"]),
        )
        leaks_at_floor = [item for item in leaked if item["df"] <= df_floor]
        per_query.append({
            "query": q.get("query"),
            "evidence_ids": sorted(q.get("evidence_ids") or []),
            "leaked_tokens": leaked,
            "leaks_at_floor": bool(leaks_at_floor),
            "min_df": leaked[0]["df"] if leaked else None,
        })

    n_queries = len(per_query)
    threshold_fractions = {}
    for t in thresholds:
        n_leaking = sum(
            1 for p in per_query
            if any(item["df"] <= t for item in p["leaked_tokens"])
        )
        threshold_fractions[str(t)] = (n_leaking / n_queries) if n_queries else 0.0

    n_leaking_at_floor = sum(1 for p in per_query if p["leaks_at_floor"])

    return {
        "n_queries": n_queries,
        "df_floor": df_floor,
        "per_query": per_query,
        "n_leaking_at_floor": n_leaking_at_floor,
        "fraction_leaking_at_floor": (n_leaking_at_floor / n_queries) if n_queries else 0.0,
        "fraction_leaking_by_threshold": threshold_fractions,
        "matching_mode": "token-boundary",
        "unit": "per-document",
        "method": "shared-rare-token-df",
        "passed": n_leaking_at_floor == 0 if n_queries else None,
    }
