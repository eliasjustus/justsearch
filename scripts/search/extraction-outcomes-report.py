#!/usr/bin/env python3
"""Per-document extraction-outcome census for a raw-file corpus (tempdoc 790 §H).

For every document of a `raw_files` jseval corpus, reads back what the live extraction
chain actually produced — the indexed text itself, `textProvenance` (the Head's derived
tier label: `tika` / `ocr` / `vdu` / `vdu_pending` / `vdu_failed` / `vdu_empty` /
`vdu_rejected` / `none`, computed from `extraction_method` + `vdu_status` at
`PreviewController.computeTextProvenance`), `vduStatus`, `vduProcessed`, and the
`visual_extraction_evidence` route — via `GET /api/preview?docId=<path>`, the Head
surface over `FetchDocumentSlice`.

Why preview and not `POST /api/knowledge/search`: preview is keyed by document id, so it
returns exactly the requested document. The search path is a *ranked* surface — its
`doc_ids` scoping did not restrict the result set in practice (measured 2026-07-29), so
it cannot enumerate a corpus exhaustively.

This is the measurement instrument for "how many dropouts recovered, at which tier".

Usage:
  python scripts/search/extraction-outcomes-report.py \
      --base-url http://127.0.0.1:64214 \
      --corpus-dir datasets/mixed/ohr-bench-pdf-live/corpus-dir \
      --out tmp/790/outcomes.json
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


def alnum(s: str) -> int:
    return sum(1 for c in s if c.isalnum())


def fetch_doc(base_url: str, doc_path: str, max_chars: int, timeout: float) -> dict:
    """Full document slice: paginates `/api/preview` until the content is exhausted."""
    parts: list[str] = []
    meta: dict = {}
    offset = 0
    while True:
        url = (f"{base_url}/api/preview?docId={urllib.parse.quote(doc_path, safe='')}"
               f"&offsetChars={offset}&maxChars={max_chars}")
        with urllib.request.urlopen(url, timeout=timeout) as r:
            d = json.loads(r.read().decode("utf-8"))
        if not meta:
            meta = {k: v for k, v in d.items() if k != "content"}
        parts.append(d.get("content") or "")
        nxt = d.get("nextOffsetChars")
        if not d.get("truncated") or nxt is None or nxt <= offset:
            break
        offset = nxt
        if offset > 4_000_000:
            break
    meta["content"] = "".join(parts)
    return meta


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://127.0.0.1:64214")
    ap.add_argument("--corpus-dir", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--max-chars", type=int, default=100_000)
    ap.add_argument("--timeout", type=float, default=120.0)
    ap.add_argument("--sample-text-chars", type=int, default=400,
                    help="How much indexed text to keep per doc in the report")
    args = ap.parse_args()

    files = sorted(p for p in args.corpus_dir.iterdir() if p.is_file())
    print(f"{len(files)} corpus files under {args.corpus_dir}")

    rows = []
    errors = 0
    for i, p in enumerate(files):
        doc_path = str(p.resolve()).lower()
        doc_id = urllib.parse.unquote(p.stem).lower()
        try:
            d = fetch_doc(args.base_url, doc_path, args.max_chars, args.timeout)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as e:
            errors += 1
            rows.append({"doc_id": doc_id, "file": p.name, "error": str(e)})
            continue
        content = d.get("content") or ""
        ev = d.get("visualExtractionEvidence") or {}
        rows.append({
            "doc_id": doc_id,
            "file": p.name,
            "chars": len(content),
            "alnum": alnum(content),
            "text_provenance": d.get("textProvenance"),
            "vdu_status": d.get("vduStatus"),
            "vdu_processed": d.get("vduProcessed"),
            "vdu_enrichment": bool(d.get("vduEnrichment")),
            "vdu_page_count": d.get("vduPageCount"),
            "route": ev.get("route"),
            "text_quality_score": ev.get("textQualityScore"),
            "pages_missing_readable_text": ev.get("pagesMissingReadableText"),
            "image_page_count": ev.get("imagePageCount"),
            "text": content[:args.sample_text_chars],
        })
        if (i + 1) % 100 == 0:
            print(f"  [{i + 1}/{len(files)}] {errors} errors")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({"base_url": args.base_url,
                                    "corpus_dir": str(args.corpus_dir),
                                    "doc_count": len(rows),
                                    "errors": errors,
                                    "rows": rows}, indent=1, ensure_ascii=False),
                        encoding="utf-8")
    print(f"Wrote {args.out} ({len(rows)} rows, {errors} errors)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
