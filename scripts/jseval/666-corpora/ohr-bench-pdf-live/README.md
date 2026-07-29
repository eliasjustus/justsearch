# `mixed/ohr-bench-pdf-live` — OHR-Bench as real PDF bytes

The paired **byte-level** arm of `mixed/ohr-bench-tika-pdf`. Same 1000 `_id`s,
byte-identical `queries.jsonl` and `qrels/test.tsv`; the difference is that the
documents are the **source single-page PDFs** instead of text somebody extracted
once, offline, in March 2026.

## Why it exists

`mixed/ohr-bench-tika-pdf` ships a `corpus.jsonl` of pre-extracted text, so
ingesting it never touches the Worker's extraction chain — it replays a recording.
Tempdoc 790 shipped an extraction-dropout fallback (structured Tika → OCR → VDU/VLM
→ honest hole) that re-extracts *bytes*; with no bytes there is nothing to measure
(790 §G). This corpus is that missing substrate: it makes the extraction chain the
thing under test, and it is a `raw_files` dataset, so `jseval run` points the watched
root straight at the PDFs (`ingest.py:_raw_corpus_dir`, the `mixed/realdocs-v1`
mechanism from tempdoc 686).

## Reconstruction

```bash
python scripts/search/fetch-ohrbench-pdf-corpus.py            # build
python scripts/search/fetch-ohrbench-pdf-corpus.py --verify   # sha256 vs manifest.json
python scripts/search/fetch-ohrbench-pdf-corpus.py --check-manifest   # build + fail on drift
```

Inputs:

- `pdfs.zip` from HuggingFace `opendatalab/OHR-Bench`
  (`sha256 f9bc65f383172c4ea47940c47dfab01dd36c03a120bc0450d7a962917098c783`,
  1 516 951 813 bytes, 1261 PDFs across 7 domains). Cached under the shared
  `scripts/jseval/tmp/dataset-fetch-cache/ohr-bench/` (tempdoc 709 convention).
- `datasets/mixed/ohr-bench-tika-pdf` for the doc-id space and the queries/qrels,
  which are copied byte-for-byte — the retrieval comparison is only meaningful if
  both arms answer the same question set.

Output: `datasets/mixed/ohr-bench-pdf-live/` (gitignored, ~240 MB) with
`corpus-dir/<urlquoted_doc_id>.pdf`, `queries.jsonl`, `qrels/test.tsv` and a
`metadata.json` carrying `"raw_files": true`.

## Id → PDF mapping (verified, not assumed)

A corpus `_id` is `<domain>/<doc_name>_p<page_idx>`, lowercased by the corpus writer
(`retriever._filename_to_doc_id` lowercases structurally — Windows filesystems are
case-preserving but not case-authoritative). The zip member is
`<domain>/<DocName>.pdf`, matched case-insensitively; all 1000 ids resolve, to 387
distinct source PDFs.

`page_idx` is **0-based**. Probed rather than assumed: for a random sample the text
of page `page_idx` is the best match against the `ohr-bench-clean` ground truth among
`{p-1, p, p+1}` in every case, and `page_idx < page_count` holds for all 1000.

## Validity control (why the corpus is not silently broken)

Splitting pages could have destroyed the text layers, which would make every document
look like an extraction dropout. Measured on the built corpus with PyMuPDF:

| | count |
|---|---|
| PDFs with a real text layer (≥ 2 alphanumerics) | 875 (median 2071 alnum chars) |
| PDFs with no text layer | 125 |
| …of which the shipped Tika arm also extracts empty | **125 (all of them)** |
| Tika-arm dropouts that DO have a text layer here | 2 |
| Tika-arm healthy documents with no text layer | 0 |

The no-text-layer set and the shipped arm's 126-document dropout set are the same
documents. The split preserved what was there.

## License

OHR-Bench is **CC-BY-4.0**, research use. Internal measurement is fine; any public
claim needs attribution and scope: *OCR Hinders RAG: Evaluating the Cascading Impact
of OCR on Retrieval-Augmented Generation*, arXiv 2412.02592, opendatalab/OHR-Bench.
No corpus content is committed to this repository — only this recipe and the
per-file manifest needed to rebuild and verify it.
