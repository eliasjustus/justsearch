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


def corpus_signature(dataset_dir: Path | str) -> str | None:
    """Content signature for a golden/mixed corpus directory.

    `sha256(corpus.jsonl bytes + qrels/test.tsv bytes)` — the established eval
    definition (`run._get_corpus_identity`). Returns ``None`` if neither defining
    file exists (nothing to sign). Best-effort on read errors (a corpus identity
    must never be the thing that fails a build).
    """
    d = Path(dataset_dir)
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
