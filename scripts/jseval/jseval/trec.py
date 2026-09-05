"""Shared TREC run-file I/O — the single authority for ``*_run.trec`` format.

A TREC run line is ``qid Q0 doc_id rank score run_tag``. Real corpora carry doc
ids with spaces (OHR-bench: ``law/…-ex-10.4-franchise agreement_p8``), so a
left-anchored ``parts[2]`` read silently truncates the id at its first space and
every downstream set-membership check against qrels then misses. Reading
**right-anchored** — the four fixed fields are ``qid``/``Q0`` at the head and
``rank``/``score``/``run_tag`` at the tail, so everything between is the id —
recovers it exactly. Writer and readers live in one module so the delimiter and
the parse cannot drift apart (tempdoc 916 §L.8).
"""

from __future__ import annotations

from pathlib import Path
from typing import NamedTuple

#: Column delimiter used by :func:`format_trec_line`. A tab makes a space-bearing
#: doc id unambiguous on the wire; readers still split on any whitespace, so
#: space-delimited files written before this change parse identically.
DELIMITER = "\t"

#: Fixed fields: ``qid``, ``Q0`` lead; ``rank``, ``score``, ``run_tag`` trail.
MIN_FIELDS = 6


class TrecEntry(NamedTuple):
    qid: str
    doc_id: str
    rank: int
    score: float
    run_tag: str


def format_trec_line(qid: str, doc_id: str, rank: int, score: float, run_tag: str) -> str:
    """Render one TREC run line (no trailing newline)."""
    return DELIMITER.join([qid, "Q0", doc_id, str(rank), f"{score:.6f}", run_tag])


def parse_trec_line(line: str) -> TrecEntry | None:
    """Parse one TREC run line right-anchored, or ``None`` if it is not one.

    Splits on any whitespace run (so tab- and space-delimited files both parse)
    and reassembles fields 2..-4 into the doc id, preserving embedded spaces.
    """
    parts = line.split()
    if len(parts) < MIN_FIELDS:
        return None
    doc_id = " ".join(parts[2:-3])
    try:
        rank = int(parts[-3])
        score = float(parts[-2])
    except ValueError:
        return None
    return TrecEntry(parts[0], doc_id, rank, score, parts[-1])


def load_trec_run(path: Path) -> dict[str, list[str]]:
    """Parse a run file into ``{qid: [doc_id in file order]}``.

    File order is rank order for files written by :mod:`jseval.artifacts`.
    Returns ``{}`` if the file is absent or unreadable.
    """
    ranked: dict[str, list[str]] = {}
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return ranked
    for line in text.splitlines():
        entry = parse_trec_line(line)
        if entry is not None:
            ranked.setdefault(entry.qid, []).append(entry.doc_id)
    return ranked
