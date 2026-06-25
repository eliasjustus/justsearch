"""BEIR corpus materialization to .txt files for JustSearch ingestion."""

from __future__ import annotations

import logging
import urllib.parse
from pathlib import Path

log = logging.getLogger(__name__)

SENTINEL_DOC_ID = "__jseval_sentinel__"
SENTINEL_CONTENT = "JustSearch eval sentinel document. Do not delete during evaluation."


def materialize(
    corpus_iter,
    output_dir: Path,
    *,
    skip_existing: bool = True,
) -> int:
    """Write corpus documents as .txt files for JustSearch ingestion.

    Args:
        corpus_iter: iterable of objects with `doc_id`, `text`, and optionally
            `title` attributes (e.g., from ir-datasets ``docs_iter()``).
            Also accepts dicts with ``_id`` and ``text`` keys (BEIR JSONL format).
        output_dir: directory to write .txt files into.
        skip_existing: if True, skip files that already exist.

    Returns:
        Number of documents written.
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    written = 0

    for doc in corpus_iter:
        doc_id, text, title = _extract_doc_fields(doc)
        filename = doc_id_to_filename(doc_id)
        filepath = output_dir / filename

        if skip_existing and filepath.exists():
            continue

        content = f"{title}\n\n{text}" if title else text
        filepath.write_text(content, encoding="utf-8")
        written += 1

        if written % 1000 == 0:
            log.info("Materialized %d documents", written)

    # Write sentinel document
    sentinel_path = output_dir / doc_id_to_filename(SENTINEL_DOC_ID)
    if not sentinel_path.exists() or not skip_existing:
        sentinel_path.write_text(SENTINEL_CONTENT, encoding="utf-8")

    log.info("Materialized %d documents to %s", written, output_dir)
    return written


def doc_id_to_filename(doc_id: str) -> str:
    """Convert a BEIR document ID to a filename for JustSearch ingestion.

    Applies URL-encoding (percent-encoding) to the doc ID and appends .txt.
    This is reversed by ``retriever.resolve_doc_id()``.
    """
    safe = urllib.parse.quote(doc_id, safe="")
    return f"{safe}.txt"


def verify_sentinel(docs_dir: Path) -> bool:
    """Check that the sentinel document exists in the materialized corpus."""
    sentinel_path = docs_dir / doc_id_to_filename(SENTINEL_DOC_ID)
    return sentinel_path.is_file()


def _extract_doc_fields(doc) -> tuple[str, str, str | None]:
    """Extract (doc_id, text, title) from an ir-datasets doc or a dict."""
    if isinstance(doc, dict):
        return str(doc["_id"]), doc.get("text", ""), doc.get("title")
    # ir-datasets namedtuple (BeirTitleDoc has doc_id, text, title)
    doc_id = getattr(doc, "doc_id", None) or str(getattr(doc, "_id", ""))
    text = getattr(doc, "text", "")
    title = getattr(doc, "title", None)
    return doc_id, text, title
