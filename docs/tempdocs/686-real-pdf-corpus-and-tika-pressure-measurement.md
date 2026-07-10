---
title: "Real-PDF/office corpus acquisition + Tika-pressure measurement: the machine (and the eval harness) currently has NO real binary-document corpus — 3 fixture PDFs total; every 'pdf' dataset (ohr-bench-tika-pdf etc.) is pre-extracted TEXT, so live Tika/PDFBox/POI parse pressure is unexercised by all existing evals. This gap was hit concretely by 682 item 1 (the worker-heap measurement had to ship with a stated Tika-scope hole) and equally limits the OCR-routing (671) and VDU (677) streams. Work: acquire/build one license-clean, redistributable-or-locally-pinned corpus of real PDFs + office docs (mixed sizes incl. genuinely large files), register it as a jseval local dataset, then re-run 682's instrumented heap recipe against the new 1g default and record whether 1g absorbs parse pressure or the constant needs a second look."
type: tempdocs
status: "open — IN PROGRESS (2026-07-10, taken over via 705's takeover session at founder direction). Sourcing decided: public/license-clean hybrid (govdocs1 + NapierOne), no personal files. Acquisition script + pinned manifest + jseval raw-binary-dataset support in flight; instrumented heap run next."
created: 2026-07-07
updated: 2026-07-10
author: agent session 2026-07-07 (gap established empirically during 682 item 1: PDF search across F:\\JustSearch found 3 fixture PDFs; jseval mixed/ datasets verified to be corpus.jsonl text)
category: eval-corpora / measurement / indexing
related:
  - 682-inherited-constants-stabilization-batch   # the heap measurement whose scope hole this closes (its §Item 1 follow-up)
  - 671-tika-ocr-skip-routing-misclassification    # OCR-skip routing needs real binary docs to test against
  - 677-vdu-extraction-abstention-gate             # VDU eligibility/abstention likewise
  - 666-mixed-corpus-reproducibility               # corpus identity/pinning conventions to conform to
---

# 686 — Real-PDF/office corpus + Tika-pressure measurement

## The gap, stated plainly

Every existing "document" dataset in the eval harness is pre-extracted text (`corpus.jsonl`
materialized to `.txt`), including the ones named after PDF pipelines. Ingesting them
exercises chunking/embedding/SPLADE/NER — but never Tika, PDFBox, or POI. Consequences,
already felt:

1. **682 item 1** had to raise the worker heap on enrichment-side evidence alone, with the
   heap-riskiest path (large-document parse buffers — the origin docs' own stated reason for
   isolating extraction in the worker) explicitly unmeasured.
2. The OCR-skip routing (671) and VDU extraction (677) streams test against a handful of
   fixtures, not a distribution of real documents.
3. `TimeboxedContentExtractor`'s 60s timebox and the extraction retry policy have never been
   exercised against genuinely heavy files in an eval.

## The work

1. **Source the corpus.** Candidates to evaluate (license first — this repo is public and has
   a license-and-notices CI lane): public document corpora with redistributable licenses
   (e.g. govdocs-class collections), the OHR-Bench *original* PDFs (upstream provides them;
   check terms — even a locally-pinned, non-committed corpus with a recorded manifest/sha
   satisfies reproducibility per 666's conventions), or synthetic generation (real PDFs
   rendered from owned text — weakest realism, cleanest licensing). Target shape: hundreds of
   files, mixed PDF + office formats, a long tail of large files (tens of MB), some
   scanned/image-only pages for the OCR-routing stream. The corpus itself is almost certainly
   **not committed** (size, licensing) — what gets committed is the manifest (source, version,
   hashes, acquisition script), conforming to 666.
2. **Register it** as a jseval local dataset (`datasets/mixed/<name>` with qrels optional —
   ingest-only runs don't need queries; a small query set can come later).
3. **Re-run 682's instrumented recipe** against it: `jseval run --max-queries 0 --pipeline
   --start-backend --clean` with `-Xlog:gc` via `JUSTSEARCH_JVM_OPTS`, worker at the new `1g`
   default. Record watermark/evacuation behavior at the `DEFAULT_WORKER_HEAP` site (extending
   the existing derivation comment), closing 682's named follow-up: does `1g` absorb parse
   pressure, or does the constant need a second look (or extraction need memory bounds)?
4. **Hand the corpus to the neighbour streams:** note its availability in 671/677 (one line
   each) so their fixtures stop being the only binary-document coverage.

## Explicitly out of scope

- Fixing whatever the measurement finds (a new tempdoc if 1g proves insufficient — measure
  first, per the batching discipline).
- OCR/VDU quality work itself (671/677 own that); this tempdoc only supplies the substrate
  and the heap answer.
- Indexing the owner's personal files as the corpus without explicit direction (privacy;
  also non-reproducible).

## Acceptance / verification

A registered dataset with a committed manifest (source/version/hashes) and a documented
acquisition path; one completed instrumented ingest run over it; the heap verdict recorded at
the constant site and in this tempdoc with the same evidence discipline as 682 §Item 1 (GC-log
numbers, command line, scope statement). License check recorded for whatever is downloaded.

---

## Execution log (2026-07-10 takeover — decisions and evidence)

### Sourcing decision (step 1) — public hybrid, no personal files

Chosen: **govdocs1 + NapierOne**, both public and license-clean; the own-files-snapshot option
is rejected per this doc's own out-of-scope note (privacy, non-reproducible), and pure synthetic
generation is rejected as weakest-realism (kept only as a future gap-filler for scanned pages if
the sample turns out to carry too few).

- **govdocs1** (digitalcorpora.org): ~1M real files crawled from `.gov` web servers, published
  for research and "may be freely redistributed" (corpus homepage; citation: Garfinkel et al.,
  DFRWS 2009). Pinned: `zipfiles/000.zip` + `001.zip` (1000 files each; S3 ETags recorded in the
  manifest). Contributes: realistic size spread with a genuine large-file tail, legacy office
  formats (.doc/.xls/.ppt → POI HWPF/HSSF/HSLF pressure), and naturally occurring scanned/
  image-only gov PDFs.
- **NapierOne** (napierone.com, Edinburgh Napier University License — free use/redistribution
  with attribution): modern (2021) mixed-file dataset. Pinned: the per-type `*-tiny.zip` bundles
  for PDF/DOCX/XLSX/PPTX (plain variants only — the `-PASSWORD` encrypted variants are excluded
  from v1 to keep the first run's failure modes interpretable; an encrypted slice is a natural
  v2 axis). Contributes the modern-OOXML leg govdocs1 (2010) lacks. Its 2.2 GB TIF bundle was
  considered for the OCR-routing stream and deferred (v1 relies on govdocs1's natural scans;
  recorded here as a scoped decision, revisit if the extraction_method distribution shows too
  few image-only docs).

**Selection policy** (deterministic, seed=686, sorted-name order before seeded sampling): keep
ALL files ≥5 MB (the long tail), sample the rest per-extension to caps targeting ~600 files
total. Script: `scripts/search/fetch-realdocs-corpus.py`; committed pins:
`scripts/jseval/666-corpora/realdocs-v1/{manifest.json,recipe.json}` (per-file sha256s + archive
hashes + selection policy). Dataset lands at `datasets/mixed/realdocs-v1/corpus-dir/` (gitignored,
per the universal fetch-fresh-never-commit rule).

### Harness gap found + closed (step 2)

jseval could not previously ingest a binary corpus at all: `ingest.py`'s materialization
bookkeeping recognized only `.txt`/`.png` (`_MATERIALIZED_EXTS`), and even `--corpus-dir`
hard-failed on a directory of PDFs — while the Worker side needed nothing (a watched root
indexes arbitrary real files; that's production behavior). Fix shipped in this branch:
`metadata.json` `{"raw_files": true}` marks a raw binary dataset; `prepare_corpus` then points
the watched root at `corpus-dir/` directly (any extension, recursive), skips materialization
and the projection sidecar entirely (the files ARE the source; nothing can go stale), and
passes the real file count as the indexing floor. Ingest-only runs
(`jseval run --max-queries 0`) already skip queries/qrels by design, so no query set is needed
for this tempdoc's runs. The unused-but-designed `corpus_signature(files=...)` seam was noted
for identity work later; v1 identity is the committed sha256 manifest.
