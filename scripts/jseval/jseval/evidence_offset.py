"""Locate an evidence string's character offset inside a document (tempdoc 783 §B.1).

The one shared primitive behind BOTH evidence-offset resolution paths:

  * the generator-metadata *writer* (`corpus_inject.evidence_offsets_for_injection`
    records where each injected gold sentence lands in its host document); and
  * the analysis-time *fallback* (`offset_recall.resolve_offset` finds the answer /
    evidence string in the gold document when no generator metadata exists).

Keeping the match logic in one place is what keeps the two paths honest: the offset
a corpus *records* and the offset the instrument *recovers* are computed the same way.
"""
from __future__ import annotations

import re

_WS = re.compile(r"\s+")


def locate_offset(text: str, needle: str) -> int | None:
    """Return the character offset of ``needle`` within ``text``, or ``None``.

    Two match tiers, in order:

    1. **Exact** substring (``str.find``) — the common case, since injection places
       gold sentences verbatim.
    2. **Whitespace-tolerant** — a run of whitespace in ``needle`` matches any run of
       whitespace in ``text`` (``\\s+``). Covers the case where sentence-splitting /
       joining collapsed or re-spaced internal whitespace between record and document.

    Returns the offset into the RAW ``text`` (tier 2 uses the regex match start, so the
    returned index is always a valid index into the original string). ``None`` when the
    needle is empty or is not present under either tier — a caller must treat ``None`` as
    "unresolved", never as offset 0 (a silent 0 would fake the recall curve at the head).
    """
    if not text or not needle:
        return None
    needle = needle.strip()
    if not needle:
        return None
    idx = text.find(needle)
    if idx >= 0:
        return idx
    tokens = [re.escape(tok) for tok in _WS.split(needle) if tok]
    if not tokens:
        return None
    match = re.search(r"\s+".join(tokens), text)
    return match.start() if match else None
