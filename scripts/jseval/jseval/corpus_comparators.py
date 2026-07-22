"""Deterministic gold-answer comparators keyed on ``gold_kind`` (tempdoc 767 §F6 / 776 §A.1).

Each schema's gold declares a ``gold_kind``; scoring is a small deterministic comparator
registry keyed on it — no judge, exact after normalization. This is the generalization of
the single substring-exact scorer (``agent_retrieval_eval._score_answer``): the
``single_value`` comparator here reproduces that substring semantics exactly, and the
aggregation schema adds ``set`` / ``count`` / ``extremum``. A query that carries no
``gold_kind`` (every pre-767 bridge cell) scores as ``single_value``, so behaviour is
unchanged for the existing corpora.

Registry contract: every comparator is ``(gold_answer: str, predicted: str) -> bool``,
pure, and deterministic. ``gold_answer`` is the committed answer string from
``queries.json`` (never a structured object — the answer field stays a plain string so the
``query_gold_sha256`` commitment is unaffected). For ``set`` the committed answer is the
member values joined by :data:`SET_DELIMITER` in sorted order; the comparator splits on it.
"""

from __future__ import annotations

import re

#: The committed join delimiter for a ``set`` gold answer. Chosen so it cannot appear
#: inside a minted value (values are drawn from :data:`entity_bank._VALUE_SHAPES`, none of
#: which contains ``;``).
SET_DELIMITER = "; "

_WHITESPACE_RE = re.compile(r"\s+")


def normalize(text: str) -> str:
    """Lowercase, collapse internal whitespace, strip surrounding space + trailing period.

    Mirrors ``agent_retrieval_eval._score_answer``'s ``gt.lower().strip().rstrip('.')`` so a
    ``single_value`` verdict here is byte-for-byte the same decision the pre-767 scorer made.
    """
    return _WHITESPACE_RE.sub(" ", str(text).lower()).strip().strip(".").strip()


def single_value(gold_answer: str, predicted: str) -> bool:
    """Exact-normalized substring match (the pre-767 default)."""
    gold = normalize(gold_answer)
    return bool(gold) and gold in normalize(predicted)


def set_all_present(gold_answer: str, predicted: str) -> bool:
    """Order-insensitive all-present: every committed member value appears in ``predicted``."""
    parts = [normalize(p) for p in gold_answer.split(SET_DELIMITER)]
    parts = [p for p in parts if p]
    if not parts:
        return False
    haystack = normalize(predicted)
    return all(part in haystack for part in parts)


def count(gold_answer: str, predicted: str) -> bool:
    """Numeric exact: the gold integer appears as a standalone number in ``predicted``.

    Guards against a substring false-positive (gold ``4`` matching the ``4`` in ``40``) by
    requiring the integer to stand alone among ``predicted``'s digit runs.
    """
    gold = normalize(gold_answer)
    if not re.fullmatch(r"\d+", gold):
        return False
    return gold in re.findall(r"\d+", predicted)


def extremum(gold_answer: str, predicted: str) -> bool:
    """Single latest/max value, exact after normalization (the extremum is precomputed at
    generation time, so the comparator only has to confirm that one value)."""
    return single_value(gold_answer, predicted)


#: gold_kind -> comparator. ``None``/absent gold_kind resolves to ``single_value``.
COMPARATORS = {
    "single_value": single_value,
    "set": set_all_present,
    "count": count,
    "extremum": extremum,
}


def score(gold_kind: str | None, gold_answer: str, predicted: str) -> bool:
    """Dispatch to the comparator for ``gold_kind`` (default ``single_value``)."""
    comparator = COMPARATORS.get(gold_kind or "single_value")
    if comparator is None:
        raise ValueError(f"unknown gold_kind: {gold_kind!r} (known: {sorted(COMPARATORS)})")
    return comparator(gold_answer, predicted)
