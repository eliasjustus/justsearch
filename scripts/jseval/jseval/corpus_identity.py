"""Corpus identity signature (tempdoc 635).

A self-demonstration corpus is a *canonical artifact* and needs its own identity —
the input-side twin of the run `manifest_hash` (623/624 govern runs/outputs; 635
governs the input). This is the **single** corpus-signature definition: the same
algorithm the retrieval eval already uses for a golden/mixed corpus
(`run._get_corpus_identity`, the tempdoc-623 ③ seam) — `sha256(corpus.jsonl bytes +
qrels/test.tsv bytes)`. `run._get_corpus_identity` delegates here so the corpus
metadata signature, the run manifest's `corpus_identity.signature`, and the
release's `corpus_source.sha256` are one value (conform, don't fork). It is a pure
function of the corpus files — computable with no dev stack.
"""

from __future__ import annotations

import hashlib
from pathlib import Path


def corpus_signature(dataset_dir: Path | str, files: list[Path] | None = None) -> str | None:
    """Content signature for a corpus directory.

    Two modes, both `sha256` over concatenated file bytes in a fixed order:

    - Default (``files`` omitted): `sha256(corpus.jsonl bytes + qrels/test.tsv
      bytes)` — the established eval definition (`run._get_corpus_identity`).
      Returns ``None`` if neither defining file exists (nothing to sign).
    - Explicit (``files`` given): hashes exactly the given files, in the given
      order — for corpora that aren't `golden/`-shaped (e.g. a plain directory
      of heterogeneous reference-corpus files). Callers should pass a
      deterministically sorted list. Returns ``None`` if the list is empty or
      none of the given paths exist.

    Best-effort on read errors either way (a corpus identity must never be the
    thing that fails a build).
    """
    d = Path(dataset_dir)
    if files is not None:
        existing = [p for p in files if Path(p).is_file()]
        if not existing:
            return None
        try:
            h = hashlib.sha256()
            for p in existing:
                h.update(Path(p).read_bytes())
            return h.hexdigest()
        except OSError:
            return None
    corpus_p = d / "corpus.jsonl"
    qrels_p = d / "qrels" / "test.tsv"
    if not (corpus_p.is_file() or qrels_p.is_file()):
        return None
    try:
        h = hashlib.sha256()
        if corpus_p.is_file():
            h.update(corpus_p.read_bytes())
        if qrels_p.is_file():
            h.update(qrels_p.read_bytes())
        return h.hexdigest()
    except OSError:
        return None
