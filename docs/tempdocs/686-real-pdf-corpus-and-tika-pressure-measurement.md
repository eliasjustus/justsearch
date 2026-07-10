---
title: "Real-PDF/office corpus acquisition + Tika-pressure measurement: the machine (and the eval harness) currently has NO real binary-document corpus — 3 fixture PDFs total; every 'pdf' dataset (ohr-bench-tika-pdf etc.) is pre-extracted TEXT, so live Tika/PDFBox/POI parse pressure is unexercised by all existing evals. This gap was hit concretely by 682 item 1 (the worker-heap measurement had to ship with a stated Tika-scope hole) and equally limits the OCR-routing (671) and VDU (677) streams. Work: acquire/build one license-clean, redistributable-or-locally-pinned corpus of real PDFs + office docs (mixed sizes incl. genuinely large files), register it as a jseval local dataset, then re-run 682's instrumented heap recipe against the new 1g default and record whether 1g absorbs parse pressure or the constant needs a second look."
type: tempdocs
status: "complete + MERGED (PR #124, squash 40138f7, 2026-07-10; sweep-hardening rider PR #128): corpus acquired + registered (mixed/realdocs-v1, 620 real files, pinned manifest), jseval raw-binary support shipped, TWO instrumented runs executed (run 1 partial/founder-stopped → heap verdict at the constant site; run 2 full-corpus post-706-engine → extraction 5× faster, real extraction_method distribution recorded, and a heap-exhaustion-induced native SPLADE-tokenizer crash found, root-caused, fixed in SpladeEncoder, and validated by resuming enrichment past the death zone). Remaining out-of-scope: the general raise-vs-bound heap decision (first instance answered: bound), and the chunk-embedding pacing cap (691's domain, inbox-logged)."
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

### Instrumented run + heap verdict (step 3) — partial run, verdict recorded

**Command** (682's recipe verbatim, new corpus): from the worktree, cwd `scripts/jseval`:
`JUSTSEARCH_JVM_OPTS="-Xlog:gc:file=<worktree>/tmp/worker-gc-686.log" python -m jseval run
--dataset mixed/realdocs-v1 --max-queries 0 --pipeline --start-backend --clean --json`.
Worker at the shipped `1g` default (verified in-log: `(1024M)` capacity on every GC line).

**Scope statement (honest limits):** run stopped by the founder at **31 min / 120 of 620 docs
indexed** (pace ~4-5 docs/min, dominated by serial per-page OCR of scanned gov PDFs — see cost
finding below). GC evidence below covers those 31 minutes, incl. several multi-page scans and
large office files; enrichment barely started, so this measures the **parse phase**, which was
exactly the unmeasured half 682 named. OCR env note: this machine's `eng.traineddata` was
overwritten with the tessdata_fast variant during setup (the "missing tessdata" observation was
stale — file existed); OCR ran live either way.

**GC watermark analysis** (`tmp/worker-gc-686-partial-snapshot.log`, preserved; 477 GC-line
events over 1,860s):

- **No Full GC, no OOM.** Live set after mixed collections ~500M (~50% of heap; e.g.
  `Prepare Mixed (G1 Humongous Allocation) 908M->508M(1024M)`).
- **72 distinct GC events with evacuation failures** and **179 humongous-allocation-triggered
  GCs** in 31 minutes; transient after-GC occupancy peaks to 926-945M (~92%) during
  concurrent-mark windows; max before-GC (young) 612M. For calibration: 682 judged FIVE
  evacuation failures in 543s at 512m "one step from OOM" and raised the default 2×.
- **Interpretation (interrogated, not just correlated):** the pressure is NOT live-set growth —
  it is **humongous allocation churn from large-document parse buffers** (Tika/PDFBox/POI whole-
  document strings/DOM + PDFBox page renders for OCR). G1 repeatedly fails to evacuate around
  the churn even though half the heap is reclaimable. Raising the heap buys headroom but does
  not remove the churn; bounding extraction buffers or G1 region-size tuning
  (`-XX:G1HeapRegionSize`) is the structural lever.

**Verdict recorded at the constant site** (`KnowledgeServerConfig.DEFAULT_WORKER_HEAP` javadoc,
dated addendum): 1g **survives** real parse pressure (no Full GC/OOM) but with **no safety
margin** during large-document parse. Per this doc's own out-of-scope rule ("fixing whatever
the measurement finds" is a new decision), the raise-vs-bound-extraction choice is left as the
recorded follow-up: options are (a) raise default to 2g (simple, costs residency on constrained
devices), (b) bound extraction buffer sizes (structural, touches the extraction path), (c) G1
region-size tuning (cheap experiment, may absorb humongous churn without more heap).

**Cost-tax finding (705-relevant, free byproduct):** per-document completion cadence was
bimodal — median gap ~0s (bursts of fast docs) vs 7 stalls of 35-350s, each a scanned PDF in
serial per-page render→tesseract OCR (observed live: page 29 of one doc, two tesseract
processes on one page image). Scanned documents cost minutes each on the baseline OCR path
while everything else costs ~nothing; this is the first direct measurement of WHERE the
extraction cost tax concentrates. Also logged to the inbox: the analyzers catalog was loaded
~40× per document during ingest (3,958 loads / 98 docs) — possible un-cached per-chunk path
worth a separate look.

### Full-corpus run 2 (2026-07-10, post-706 engine) — extraction 5× faster; the corpus claimed a second real defect (fixed + validated)

**Extraction phase (706's corpus-level validation): 615/620 docs indexed in ~33 min** vs 120/620
at 31 min on the old engine (~5× corpus-level; the residual 5 are honest legacy-office parse
failures / extraction-budget rejections, logged WARN). First real **extraction_method
distribution** (705 trigger-1 datum, harvested live via `/api/knowledge/folder-files`):
**TIKA_STRUCTURED 331 (54%) / OCR_TIKA 284 (46%)**; vdu_status NOT_NEEDED 494 / **PENDING 121
(19.7%)**; extraction_status SUCCESS_FULL 611 / SUCCESS_PARTIAL 4. On a real gov-heavy corpus
the OCR path is ~half of extraction — not a niche fallback — and a fifth of documents demand VDU.

**Enrichment phase crash-looped — the corpus's second scalp.** Three identical JVM native
crashes (`EXCEPTION_UNCAUGHT_CXX_EXCEPTION` in tokenizers.dll, thread "indexing-loop",
`TokenizersLibrary.getTokenCharSpans` ← `HuggingFaceTokenizer.batchEncode` ←
`SpladeEncoder.encodeBatchTokenBudget`), intervals shrinking 43→17→10 min. Forensics — every
hypothesis tested before fixing:

- Poison document: **falsified.** A JVM harness replayed all 86,296 stored content/chunk_content
  strings from the crashed index through the same tokenizer + batching: zero crashes
  (`SpladeIndexContentCrashHarnessTest`, worker-services, manual/asset-gated).
- Unpaired-surrogate input: **falsified** (`SpladeTokenizerSurrogateSafetyTest`, worker-core).
- **Confirmed: heap-exhaustion-induced JNI death.** All three hs_err dumps show the 1g heap at
  99.7-99.9% occupancy at crash. `encodeBatchTokenBudget` batch-tokenized the FULL caller list
  upfront with truncation disabled: ~100 doc-level contents × up to ~107k tokens each (content
  char-capped at 200k) materialized simultaneously — ids + token strings + char spans, up to
  ~1 GB short-lived — while only the first maxSeqLen tokens are ever used. DJL's JNI surfaces
  the failed allocation as an uncaught C++ exception instead of an OOME → native JVM death.
  This is run 1's heap verdict ("1g survives with no margin") escalating to a kill.

**Fix (worker-core `SpladeEncoder`):** chunked tokenization — native `batchEncode` calls bounded
by `TOKENIZE_GROUP_CHAR_BUDGET` (512k input chars ≈ tens of MB peak, derivation at the constant),
retaining only maxSeqLen-truncated arrays; full Encodings are short-lived per group. Regression:
`SpladeEncoderBoundedTokenizeTest` (multi-group batch ≡ singleton results by dominant-token
agreement — catches cross-wiring; exact float equality across padded batches is not expected and
was verified to differ only in near-zero tail terms).

**Validation experiment (quick, per founder directive — no full re-run):** enrichment resumed
over the crashed run's own index with the fixed encoder: sailed through the previous death zone
— 328/615 docs enriched (53%, vs crashes at ≤193) over ~15 min of observation, **zero crash
dumps**, no resets, stable cycles. Fix considered validated; the remaining chunk-embedding
backlog drain is bounded by a ~50-chunks-per-cycle pacing cap (logged to the inbox for 691's
domain — throughput, not correctness).

**Heap follow-up sharpened:** the raise-vs-bound decision now has its first concrete instance
answered — this was a "bound the consumer" case (allocation churn by a single consumer), not a
"raise the heap" case. The GC logs for run 2 + resume are preserved
(`tmp/worker-gc-686-full.log` — truncated per crash-restart, `tmp/worker-gc-686-resume.log`;
hs_err dumps archived in the session scratchpad, key numbers recorded here durably).

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

## Evidence durability + unverified assumptions (2026-07-10 closeout)

**Durable (re-derivable from repo/commits):** the corpus manifest + rebuild script
(`scripts/jseval/666-corpora/realdocs-v1/`, `scripts/search/fetch-realdocs-corpus.py --verify`);
all run commands verbatim in this doc; the SPLADE fix + its regression tests by name
(`SpladeEncoderBoundedTokenizeTest`, `SpladeTokenizerSurrogateSafetyTest`, and the manual replay
harness `SpladeIndexContentCrashHarnessTest` — re-runnable against any future index dir by
editing its INDEX_DIR constant); the hs_err key numbers (heap occupancy 99.7-99.9% at all three
crashes, crash stack) transcribed above.

**Local-only (this machine, gitignored — will not survive a clean checkout):** the crashed run's
index (`tmp/headless-eval-data/`), GC logs (`tmp/worker-gc-686-full.log` — truncated per
crash-restart, `tmp/worker-gc-686-resume.log`), the raw hs_err dumps and harvested document
texts (session scratchpad archive).

**Session-output-grade (recorded numbers only; not re-derivable):** the run-2 progress curve
(615/620 at ~33 min; watchdog observations), the extraction_method distribution harvest
(commands described above; re-derivable only while an index of this corpus exists), and the
crash-fix validation curve (enrichment resume: 100→328/615 docs across ~15 min, zero crash
dumps, past the ≤193 death zone).

**Unverified assumptions / deferred checks (do not treat as facts):**
1. The SPLADE fix is validated to 53% doc-level enrichment on the resumed index, NOT to full
   corpus enrichment completion — the chunk-embedding backlog (~85k chunks at ~50/cycle pacing,
   691's domain) makes a full drain impractical until pacing changes; no crash is *expected*
   beyond the validated zone (same code path, smaller inputs), but it was not observed.
2. The heap raise-vs-bound general decision remains open; this session answered one instance
   (bound the SPLADE consumer). Other unbounded consumers may exist (embedding/NER tokenize
   paths were NOT audited for the same upfront-materialization pattern — cheap follow-up).
3. `max_pages: 200` behavior on a packaged install remains unprobed (config-resolution trace
   only, inherited caveat from 706's S2).
