---
status: "measured (2026-07-29) — §G's open acceptance item is CLOSED: the recovery is quantified on real OHR-Bench PDF bytes and the VLM leg is live-verified (§H). Chartered 2026-07-28, implementation-licensed; all verification local/$0. Source evidence: register F-042 + the 786 per-query analysis (tmp/hero-arc-analysis/engine-joins/ohr-per-query.v1.json; methodology in F-042/786 §E)."
created: 2026-07-28
updated: 2026-07-29
---

# 790 — Extraction dropout fallback: target the 83% of the tax carried by empty extractions

## Evidence (why this is the extraction lever)

The 786 sweep put the shipped Tika path's full-pipeline extraction tax at −13.74% nDCG@10
vs clean text (F-042), and the per-query decomposition located it: **78.8% of the 962
queries are unaffected; 12.4% drop to zero and carry 83% of the total damage; 13 of the 15
worst-hit queries have a gold document whose extracted text is literally empty (0 chars).**
The corpus ships 126 empty-text placeholder docs (encrypted/failed PDFs kept as empty).
A better conventional OCR engine is measurement-rejected (GOT statistically ties Tika,
CIs overlap); MinerU is decisively worse. The three extractors fail on mostly DIFFERENT
documents (worst-50 Jaccard 0.11–0.16, a 6-query hard core failing under all three) — so
per-document fallback/ensemble beats any single-engine swap on this data.

## Work items

1. **Detect extraction dropout at index time.** A doc whose extracted text is empty or
   trivial (threshold TBD from the 126-doc set's distribution — measure, don't guess) gets
   flagged `extraction_dropout` instead of being silently indexed as an empty document.
   Check first what already exists: the VLM routing path (quality score < 0.3 →
   `JUSTSEARCH_LAYOUT_ENABLED` VLM extraction, ADR-0018) and `extraction_method` reason
   codes — this item may be a *broadening of an existing gate's trigger* (empty output is
   not currently a routing trigger) rather than new machinery. Explore-before-implementing
   applies hard here.
2. **Fallback chain on dropout**: re-extract through the next tier (structured Tika → OCR
   path → VLM per ADR-0018), bounded per-doc budget, recording which tier produced the
   indexed text. Never index empty silently; if every tier fails, index the doc with an
   explicit `extraction_failed` marker that search surfaces (an honest hole beats an
   invisible one — the epistemic-contract direction, 788 §3.D.19, applied at index time).
3. **Verification (all local, $0)**: re-run the 786 sweep shape on ohr-bench-tika-pdf with
   the fallback active. Acceptance: the 15 worst-degraded queries' gold docs have non-empty
   indexed text; hybrid nDCG@10 recovers a measurable share of the 0.1307 clean−Tika gap;
   NO regression on ohr-bench-clean (the fallback must not fire there) and no regression on
   the standard register sentinels (scifact hybrid within ±2σ). Per F-042's method: verify
   arms by corpus_identity signature.
4. **Register + F-042 annotation** with the measured recovery; catalog row correction
   (999→1000 docs with the 126-placeholder note) rides along.

## Non-goals

Extractor replacement (measurement-rejected); ensemble racing beyond the dropout set (only
if item 2's chain proves insufficient — the 6-doc all-extractors-fail core bounds what any
chain can recover); touching the VDU/VLM models themselves.

## Risks / honest limits

OHR-Bench is CC-BY-4.0 research-only — internal measurement fine, public claims need
attribution+scope. The dropout threshold must not misclassify legitimately short docs
(measure the clean corpus's length floor first). VLM fallback cost per doc is real
(GPU-time, not API dollars) — the per-doc budget and the dropout-set size (~2%) keep it
bounded, but measure enrichment-throughput impact (the 785 lane's numbers are the baseline).

---

# Implementation log — 2026-07-28

## §A. Seam chosen (item 1: explore before implementing)

The chain already existed as two disconnected halves. The work is a **broadening of the
existing gates**, plus one new pure classifier and one new budget record; no parallel
machinery, no new schema field, no new configuration surface.

| Where | file:line (pre-change) | What it already did | What 790 changed |
|---|---|---|---|
| **Tier-0/1 gate** | `modules/worker-services/src/main/java/io/justsearch/indexerworker/extract/PolicyDrivenTikaExtractor.java:149-191` (`evaluateOcrAttempt`) | Structured Tika → conditional OCR escalation, skipping OCR when `TextQualityAnalyzer.computeQualityScore(content, pages) >= 0.3` | Budget gate added FIRST (`OcrSkipReason.BUDGET`); the `TEXTUAL` skip can no longer fire on a dropout (was already unreachable for empty text — score 0.0 — so this makes an implicit invariant explicit and test-pinned rather than fixing a live bug) |
| **Tier-2 gate** | `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/VisualRoutingDecision.java:32-40` | `extraction_method == OCR_TIKA` + no visual-enrichment signal → `notNeeded("ocr_baseline_sufficient")` | **Live defect fixed**: an OCR pass that produced *nothing* declared itself sufficient and the VLM tier was never reached — for exactly the documents that need it most. Dropout is now checked before the per-method branches and routes to `VDU_STATUS_PENDING` / `baseline_text_missing` / reason `extraction_dropout`; every PENDING route is budget-gated |
| **Provenance write** | `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/IndexingDocumentOps.java:248-276` | Wrote `extraction_method`, `extraction_status`, `extraction_reason_code` (`SUCCESS_EMPTY` for blank output, tempdoc 671) | `markExtractionDropout` refines that terminal state: `EXTRACTION_DROPOUT_PENDING_FALLBACK` while a tier is queued, `extraction_method=NONE` + `EXTRACTION_DROPOUT_UNRECOVERED` when none remains |
| **Chain terminator** | `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/GrpcIngestService.java:679-720` (`updateVduResult`) | Terminal VDU outcomes (`SUCCESS_EMPTY`, `FAILED`, `REJECTED`) wrote `vdu_status` only | The three terminal outcomes now flip a pending dropout to `UNRECOVERED`. Verified terminal: only `PROCESSING` is recovered back to `PENDING` (`GrpcIngestService.java:1243`), `FAILED` is not re-queued |
| **Search surfacing** | `modules/worker-services/src/main/java/io/justsearch/indexerworker/services/GrpcSearchService.java:686-691` | Emitted `extraction_method` in `FetchDocumentSlice` metadata | Also emits `extraction_reason_code` — the hole is only honest if it is visible |

New code (both registered as logic seams in `governance/logic-seams.v1.json`, PIT-measured,
floors filed in `gates/test-efficacy/strength-baseline.v1.json`):

- `extract/ExtractionDropoutPolicy.java` — the measured threshold classifier (seam
  `extraction-dropout-policy`, PIT strength 93%: one equivalent mutant survives on the
  `count < limit` early-exit of the alphanumeric scan, which cannot change the verdict because
  `limit == MIN_USABLE_ALPHANUMERIC_CHARS`).
- `extract/ExtractionFallbackBudget.java` — per-document tier-count + wall-clock bound (seam
  `extraction-fallback-budget`, PIT strength 100%).

**Alternatives rejected.** (a) *A new `extraction_dropout` schema field* — rejected: the
`extraction_method` / `extraction_reason_code` / `extraction_status` fields already exist, are
already stored+docValued (`SSOT/catalogs/fields.v1.json:453,489`) and are already read by the
search path; a new field would have been a fork of an existing representation plus an SSOT
dual-copy migration for nothing. (b) *A new `ExtractionStatus` enum value* — rejected: the
status enum is a provenance ladder whose exhaustive switch (`IndexingDocumentOps:329-334`)
already forces a decision per value; dropout is a *routing* question layered over it, not a
seventh status. (c) *A new env-configurable budget* (`JUSTSEARCH_EXTRACTION_FALLBACK_*`) —
rejected: it would add a config surface, registry entry and drift risk with no measured
benefit; the budget sits inside the already-enforced `TimeboxedContentExtractor` 60 s timeout.
(d) *Lowering `TextQualityAnalyzer.MIN_GOOD_TEXT_LENGTH` (100) into a dropout trigger* —
rejected by the measurement below: it misclassifies 19/1000 legitimate documents.

## §B. Threshold measurement (item 2 — measured, not guessed)

Paired arms, 1000 single-PDF-page documents each, same doc ids:
`datasets/mixed/ohr-bench-tika-pdf/corpus.jsonl` (extractor output) vs
`datasets/mixed/ohr-bench-clean/corpus.jsonl` (ground truth).

Trimmed-length histogram:

| chars | 0 | 1-9 | 10-49 | 50-99 | 100-199 | 200-499 | 500+ |
|---|---|---|---|---|---|---|---|
| tika-pdf | **126** | 1 | 8 | 10 | 19 | 20 | 816 |
| clean | 35 | 1 | 8 | 10 | 24 | 36 | 886 |

- **126 empty (12.6%)** in the Tika arm — the charter's dropout set, reproduced exactly.
- Of those 126, **110 have real ground-truth text** (median 1156 chars, max 8036) and are in
  principle recoverable; **16 are blank pages in ground truth too**. So the recoverability
  ceiling of *any* fallback chain on this corpus is 110/126 = 87%, before the known
  6-query all-extractors-fail hard core is subtracted.
- **A character-count floor does not separate the classes.** Legitimate ground-truth documents
  exist at 5, 19, 22, 24, 28, 30, 34, 44, 47, 51, 57, 58, 65, 67, 68, 83, 97, 98 chars — 19
  clean documents under 100 chars. A 100-char floor (the existing `MIN_GOOD_TEXT_LENGTH`)
  would call all 19 dropouts.
- **Counting letters-and-digits does separate them.** Threshold sweep (`alnum < N` flags):

  | N | clean flagged (of which non-empty = FALSE POSITIVES) | tika flagged (of which non-empty) |
  |---|---|---|
  | 1 | 35 (**0**) | 127 (1) |
  | **2** | 35 (**0**) | 127 (1) |
  | 3 | 36 (**1** — `"$f 5$"`) | 127 (1) |
  | 10 | 36 (1) | 128 (2) |
  | 20 | 39 (4) | 132 (6) |
  | 100 | 63 (28) | 158 (32) |

  The clean arm's minimum legitimate alphanumeric count is 2 (`"$f 5$"`); the next is 18. The
  Tika arm's one sub-2 non-empty document is `academic/dude_72a8558a…_p1`, whose entire
  extracted text is a single backslash against 24 chars of ground truth.

**`MIN_USABLE_ALPHANUMERIC_CHARS = 2`** — the largest threshold with a zero false-positive rate
against legitimate content, i.e. *fewer than two letters-or-digits* is a dropout. Honest limit:
measured on single PDF pages from an OCR benchmark; generalization to arbitrary user documents
is an assumption, not a measurement.

## §C. Fallback chain + budget (item 2/3)

Order: **structured Tika (tier 0, never charged) → OCR (tier 1, in-extractor) → VDU/VLM
(tier 2, deferred backfill per ADR-0018) → honest hole.** Bound:
`ExtractionFallbackBudget(maxFallbackTiers = 2, perDocBudgetMs = 30 000)`, nested inside
`TimeboxedContentExtractor`'s 60 s whole-extraction timeout. `permitsTier` gates the tier
count at both gates; `exhausted(elapsedMs)` (inclusive boundary) stops tier 1 from *starting*
on a document that already burned its wall clock in structured extraction, reported as the new
`OcrSkipReason.BUDGET` (distinct from `TIMEOUT` — "did not fit" vs "ran and overran").

Which tier produced the indexed text is recorded in the **existing** `extraction_method`
vocabulary (`TIKA_STRUCTURED` / `OCR_TIKA` / `VDU`, the VDU write already existed at
`GrpcIngestService.java:688`), extended with one value — `NONE` — meaning *no tier produced
usable text*. Paired reason codes: `EXTRACTION_DROPOUT_PENDING_FALLBACK` (a tier is queued) and
`EXTRACTION_DROPOUT_UNRECOVERED` (terminal). The document is still indexed with its path,
filename, metadata and whatever text the last tier returned — the marker never discards content.

Reason-code register discipline: `IngestionReasonCodes` is a plain constants class with no
governance register or pre-merge check (the pre-merge table's reason-code checks cover
`LifecycleReasonCode` and `SearchReasonCode`, which this work does not touch — verified by
grepping `governance/`, `scripts/ci/`, `scripts/governance/`, `contracts/` for
`IngestionReasonCodes` / `extraction_method`: zero hits). `extraction_method` and
`extraction_reason_code` are untyped `keyword` fields in `SSOT/catalogs/fields.v1.json` with no
value enumeration, so extending the vocabulary needs no catalog change and no SSOT dual-copy sync.

## §D. Verification

| Tier | What | Result |
|---|---|---|
| a | `ExtractionDropoutPolicyTest` (6) — threshold classifier on verbatim corpus strings, both directions | **PASS** |
| a | `ExtractionFallbackBudgetTest` (5) — tier bound, wall-clock bound, inclusive boundary, rejected negatives | **PASS** |
| a | `ExtractionDropoutFallbackChainTest` (6) — tier-1 ordering: dropout escalates, healthy text does not, spent budget stops the chain, budget precedes the gates it could mask | **PASS** |
| a | `VisualRoutingDecisionTest` (+4, 7 total) — empty/trivial OCR output now reaches the VLM tier (regression for the fixed defect); healthy OCR still does not; budget still bounds it | **PASS** |
| a | `ExtractionDropoutMarkerTest` (5) — pending vs terminal marker, text never discarded, **no-fire on healthy and on legitimately short documents** | **PASS** |
| b | `ExtractionDropoutPdfFixturesTest` (2) — real PDF bytes through the production extractor: `pdf-image-only.pdf` (the same shape as the 126 empty extractions) is detected as a dropout, reaches the VLM tier, and carries the pending marker; `pdf-text-layer.pdf` is untouched | **PASS** |
| — | Full unit suite `./gradlew.bat test` | **PASS** (one pre-existing fixture updated — see §E) |
| — | `./gradlew.bat build -x test`, `check-logic-seams --mode gate`, `governance --gate test-efficacy` | **PASS** |
| c | scifact hybrid sentinel + dropout fire-count on clean text | see §F |
| d | Full 962-query `ohr-bench-tika-pdf` sweep re-measure | **OPEN** — see §G |

**VLM-output leg: pending-live-verify.** The doc-level check asserts the routing *decision*
(the document reaches the VLM tier and is marked as such). Asserting what the VLM then extracts
requires the local llama/VDU runtime under `JUSTSEARCH_LAYOUT_ENABLED`; that leg is not
verified here and is not claimed.

## §E. One pre-existing test fixture changed (deliberate contract change)

`IndexingDocumentOpsVduDemandTest.ocrImageClearsBaselineDemandWithoutVisualEnrichment` failed
after the tier-2 fix. Its fixture passed **empty** content while its own evidence JSON claimed
`textQualityScore: 0.7` — an internally inconsistent stand-in for "OCR produced adequate text"
(every sibling case in the file passes real text). Under 790 an empty OCR result is the
strongest possible demand for the next tier, so the fixture now carries the readable text its
evidence describes; **the assertion is unchanged** (an image OCR'd to adequate text does not
re-queue baseline demand). The empty-OCR case is now covered with the opposite, deliberate
expectation in `VisualRoutingDecisionTest`. This is the one behavioural contract this PR
changes on purpose, and it is the defect the charter names.

## §F. Sentinel run (item 3 / verification c) — PASS

`python -m jseval run --dataset scifact --modes hybrid --pipeline --start-backend --clean`
(git_sha `1e3ee46f`, 2026-07-28, this branch's code, 5184 docs / 300 queries, full enrichment,
CE on, `comparable: true`, `error_count 0`, `chunk_completeness: chunk-free` — the legitimate
verdict for a short-doc BEIR corpus).

| | value |
|---|---|
| hybrid nDCG@10 (this branch) | **0.76145** |
| register baseline | 0.76040 |
| relevance-gate floor | 0.74040 |
| `jseval relevance-gate --dataset beir/scifact` | **exit 0**, `ndcg10-no-regression: ok` |

**Fallback fire-count on clean text: 0.** The dropout verdict is a pure function of the indexed
text, so it is provable over the whole corpus rather than sampled: scanning all **5184**
materialized scifact documents (`scripts/jseval/tmp/eval-corpora/scifact`) with the shipped rule
(blank, or fewer than 2 letters-or-digits) yields **0 EMPTY + 0 TRIVIAL = 0 fires**. The chain
cannot have influenced the sentinel number, which is the point of running it: the +0.001 delta is
run-to-run noise inside the envelope, not an effect of this change.

The eval backend was started and stopped by jseval within this run; no listener remains on 33221.

## §G. Open acceptance item — the sweep re-measure, and a structural finding

The charter's headline acceptance ("the 15 worst-degraded queries' gold docs have non-empty
indexed text; hybrid nDCG@10 recovers a measurable share of the 0.1307 gap") **cannot be
produced from the shipped `ohr-bench-tika-pdf` corpus, for a structural reason that predates
this work**: that corpus is *pre-extracted text* (`corpus.jsonl`, 1000 records of
`{_id, title, text}` — `metadata.json`: "PDFs split to single pages, indexed by JustSearch Tika
extraction", built 2026-03-20), and the source PDFs are not on this machine (tempdoc 686's
title states the same for every "pdf" dataset; `datasets/` holds no `.pdf`). The fallback chain
re-extracts *bytes*; with no bytes there is nothing for tier 1 or tier 2 to read. Ingesting the
126 empty records through the new path therefore produces exactly the honest terminal state
(`extraction_method=NONE` + `EXTRACTION_DROPOUT_UNRECOVERED`) and **cannot** move nDCG — the
recovery this work enables is real only where the original document bytes exist.

Consequences, recorded rather than papered over:

1. The sweep re-measure on `ohr-bench-tika-pdf` would measure the marker, not the recovery. It
   remains **open**, and it needs a corpus of real PDF bytes to be meaningful: the registered
   `mixed/realdocs-v1` (620 real files, pinned manifest, tempdoc 686) is the substrate that
   exists; re-materializing OHR-Bench's source PDFs is the substrate that would answer the
   charter's question directly.
2. Until then the measured claim this PR makes is bounded and honest: the dropout is *detected*,
   the next tier is *reached* (proven on real PDF bytes in §D tier b), the terminal state is
   *visible to search*, and no legitimate document is misclassified (0 false positives at the
   measured threshold).
3. The recoverability ceiling from §B (110/126 = 87% of the dropout set has ground-truth text at
   all) bounds what the sweep could ever show, independent of this implementation.

---

# §H. The recovery, measured on real OHR-Bench PDF bytes — 2026-07-29

**§G is closed.** The missing substrate was built, the 1000 documents were ingested through the
live extraction chain with the 790 fallback active and the VDU/VLM tier reachable, and the same
962 queries were run against the result. Headline: **the shipped Tika path's −0.1303 extraction
tax shrinks to −0.0297 — 77.2% of the gap is recovered — and 95.2% of that gain comes from
exactly the queries 790 targeted.** The VLM leg is live-verified with quoted output.

Everything below is one session, one machine, `git_sha 8fccbf42` (this branch, #320 merged),
$0 external spend.

## §H.1 The substrate: `mixed/ohr-bench-pdf-live`

`pdfs.zip` from HuggingFace `opendatalab/OHR-Bench` (1 516 951 813 bytes,
`sha256 f9bc65f383172c4ea47940c47dfab01dd36c03a120bc0450d7a962917098c783`, 1261 PDFs, 7 domains)
split to the 1000 single-page PDFs matching the shipped arm's `_id` space.
Builder: `scripts/search/fetch-ohrbench-pdf-corpus.py`; recipe + per-file manifest +
reconstruction README under `scripts/jseval/666-corpora/ohr-bench-pdf-live/`. No corpus content
is committed (gitignored `datasets/`, 240 MB on disk).

- **Id → PDF mapping**: `_id` = `<domain>/<doc_name>_p<page_idx>`, matched case-insensitively
  against `<domain>/<DocName>.pdf`; **all 1000 ids resolve**, to 387 distinct source PDFs.
  `page_idx` is **0-based** — probed, not assumed (page `page_idx` is the best text match against
  the `ohr-bench-clean` ground truth among `{p-1, p, p+1}` on every sampled document, and
  `page_idx < page_count` holds for all 1000).
- **Queries/qrels are byte-identical** to `mixed/ohr-bench-tika-pdf` (SHA256-verified on both
  files) — the arms answer the same question set, which is the only way the comparison means
  anything.
- **License**: OHR-Bench is **CC-BY-4.0, research use**. Internal measurement is fine; any public
  claim needs attribution + scope (*OCR Hinders RAG*, arXiv 2412.02592, opendatalab/OHR-Bench).

**Validity control (pre-registered, run before any retrieval number was seen).** Splitting pages
could have destroyed the text layers, which would have made every document look like a dropout
and manufactured the result. Measured on the built corpus with PyMuPDF:

| | count |
|---|---|
| PDFs with a real text layer (≥ 2 alphanumerics) | 875 (median 2071 alnum chars) |
| PDFs with no text layer | 125 |
| …that the shipped Tika arm also extracts empty | **125 (all of them)** |
| Shipped-arm dropouts that DO have a text layer here | 2 |
| Shipped-arm healthy documents with no text layer | **0** |

The no-text-layer set *is* the shipped arm's dropout set. The split preserved what was there.
(127 = the dropout set at the shipped `alnum < 2` threshold — 126 empty + the one single-backslash
document §B already named; 111 of the 127 have real ground-truth text, so §B's 87% ceiling holds
at 111/127.)

## §H.2 What the extraction chain actually did — per-document census (all 1000)

Read back per document from `GET /api/preview` after ingest + a fully drained VDU queue
(`scripts/search/extraction-outcomes-report.py`, 1000/1000 documents, 0 errors).
`textProvenance` is the Head's derived tier label (`PreviewController.computeTextProvenance`,
from `extraction_method` + `vdu_status`).

| tier that produced the indexed text | non-empty | empty |
|---|---:|---:|
| `ocr` (tier 1, Tesseract via Tika) | 748 | 0 |
| `vdu` (tier 2, Qwen3.5-9B + mmproj vision) | 147 | 0 |
| `vdu_rejected` (VLM output vetoed by `VduAbstentionGate`, baseline retained) | 88 | **13** |
| `vdu_failed` | 4 | 0 |

**On the 127-document dropout set** — the documents the shipped arm loses entirely:

| outcome | n |
|---|---:|
| recovered by **OCR** (tier 1) | 43 |
| recovered by **VDU/VLM** (tier 2) | 64 |
| non-empty but VLM-rejected (OCR noise retained) | 7 |
| **still empty** (terminal honest hole) | 13 |
| **non-empty total** | **114 / 127 = 89.8%** |

Restricted to the 111 with real ground truth (§B's ceiling population): **106/111 = 95.5%**
non-empty. Recovered text lengths: median 1000 alnum chars, range 20–6697.

**Quality-adjusted, because "non-empty" is not the same as "right".** Word-overlap of the indexed
text against the clean ground truth, over the 111 recoverable documents:

| fidelity | n | share |
|---|---:|---:|
| useful (overlap ≥ 0.5) | **92** (52 VDU + 40 OCR) | 82.9% |
| partial (0.1–0.5) | 6 | 5.4% |
| noise (< 0.1) | 8 | 7.2% |
| still empty | 5 | 4.5% |

Per-tier fidelity: OCR median overlap **0.90** (n=43), VDU median **0.93** (n=64) — but VDU's
p10 is 0.00, i.e. roughly a tenth of VLM recoveries are text with no lexical relationship to the
page. The 7 `vdu_rejected`-but-non-empty documents have median overlap 0.00: the abstention gate
correctly vetoed the VLM, and what remains indexed is OCR noise, not a recovery. Counting them as
recoveries would overstate the result, which is why the fidelity table above is the number to
quote, not the 89.8%.

**The 13 terminal holes are mostly correct.** All 13 are `vdu_status=REJECTED` — the abstention
gate refused the VLM output and 790's `EXTRACTION_DROPOUT_UNRECOVERED` marker is the terminal
state. **8 of the 13 are blank in the ground truth too** (`gt_alnum = 0`): an honest hole is the
right answer there. Only **5** documents with real ground-truth text end unrecovered
(`academic/dude_72a8558a…_p1` 19 chars, `textbook/gnhk_eng_eu_014_p0` 85,
`textbook/gnhk_eng_na_137_p0` 168, `textbook/gnhk_eng_na_136_p0` 317,
`news/dude_d7d024763949a421053be022696238bc_p10` 1522).

**Control — the fallback does not fire where it shouldn't.** Of the 873 documents the shipped arm
extracted fine, **zero** end empty; none carries a dropout marker. Combined with §F's 0 fires on
5184 scifact documents, the threshold's zero-false-positive property holds on real bytes too.

## §H.3 VLM-output leg — live-verified (closes §D's "pending-live-verify")

The tier-2 leg ran for real: **147 documents carry VLM-extracted text as their indexed content**
(`GrpcIngestService.updateVduResult` replaces `content` and re-queues embedding on
`SUCCESS_TEXT`), produced by `Qwen_Qwen3.5-9B-Q4_K_M.gguf` + `mmproj-F16.gguf` through the local
cuda12 llama-server (`hasVisionCapability: true`, verified at `/api/inference/status` before the
run). Three of the 64 dropout-set VDU recoveries, quoted verbatim from the indexed text:

- `textbook/00e2c609b433…_p0` — indexed: *"tions are that the publishers are having moderate
  success with the first model, less with the second.\n\nAcquiring the critical mass\nPublisher
  buyouts are another response to market uncertainty and ar…"*; ground truth begins with the same
  sentence. 3919 alnum chars from a page the shipped arm indexed as empty.
- `manual/dude_59e7bde1539f952ab01c87a0aba014d8_p11` — indexed: *"WORKING DRAWINGS\n\n100-BUSHEL
  HOG FEEDER\n5-1308\n\nPLAN\nSECTION\n\n2x4 RIDGE\n2x4 BRACE\nCANVAS\nNAILED TO\nTHIS SIDE\nDOOR\nRIDGE…"* —
  a scanned engineering drawing; ground truth is *"WORKING DRAWINGS … 100-BUSHEL HOG FEEDER S-1308
  … PLAN … CORNER DETAIL "F" … FRONT ELEVATION"*. The VLM read the drawing's callouts.
- `administration/dude_1f31d620634896d8c87d7665e8ca1e13_p2` (OCR tier, for contrast) — indexed:
  *"POWER PURCHASE AGREEMENT\nTERMS AND CONDITIONS\nAgreement fail to include a provision that is
  required as a…"*, matching the ground truth's opening exactly.

**And one honest counter-example**, because the tail matters: `news/dude_ce4c991a…_p14` was
"recovered" by VDU with 3861 alnum chars about *"Set of $. DOLLAR CE 1795! Shorter life than the
1794-95 Flowing Anthony Dollar…"* while its ground truth is a Monsanto/Aroclor news story — the
VLM produced fluent, plausible, wrong text that the abstention gate did not catch. This is the
p10 = 0.00 tail made concrete: the tier-2 leg works, and its failure mode is confabulation, not
silence.

## §H.4 Retrieval recovery — three same-session arms, 962 queries each

All three arms: same backend, same session, hybrid + CE on, full enrichment, all leg modes,
`comparable: true`, `error_count 0`, arms confirmed distinct by `corpus_identity.signature`.

| arm | corpus_identity | nDCG@10 | P@1 | R@10 |
|---|---|---:|---:|---:|
| `mixed/ohr-bench-clean` (ceiling) | `641ec0b7ae96…` | **0.9508** | 0.9116 | 0.9875 |
| **`mixed/ohr-bench-pdf-live`** (real bytes, 790 chain) | `2e810833d5ce…` | **0.9211** | 0.8815 | 0.9543 |
| `mixed/ohr-bench-tika-pdf` (shipped, pre-extracted) | `f90ba56d8e73…` | **0.8205** | 0.7661 | 0.8649 |

**The two control arms reproduce 786 to the fourth decimal** — tika-text 0.8205 / 0.7661 / 0.8649
vs 786's 0.8205 / 0.7661 / 0.8649, and clean 0.9508 vs 0.9512 — on a *different backend* (dev
stack, not the eval backend) at a *different git SHA*. That is the comparability evidence the
headline rests on: the +0.1006 is not a harness artifact, because the same harness reproduces the
baselines exactly.

- extraction tax, shipped path: **−0.1303** (clean − tika)
- extraction tax, live path with the 790 chain: **−0.0297**
- **recovered: +0.1006 = 77.2% of the gap**

**Where the gain comes from (per-query decomposition, hybrid):**

| query group | n | tika-text | pdf-live | Δ | share of total gain |
|---|---:|---:|---:|---:|---:|
| gold document is in the dropout set | 110 | 0.0468 | **0.8855** | **+0.8387** | **95.2%** |
| gold document is not | 852 | 0.9203 | 0.9258 | +0.0054 | 4.8% |
| all | 962 | 0.8205 | 0.9211 | +0.1006 | 100% |

The clean-arm ceiling on those same 110 queries is 0.9710, so the dropout class goes from
**4.8% of its ceiling to 91.2% of it**. This is the charter's thesis measured end-to-end: the tax
lived in the empty-extraction class, and closing that class recovers essentially all of the
recoverable tax. 103 of the 130 queries that scored a flat 0.0 on the shipped arm now score above
zero.

**Honest limits on the retrieval number.**
1. This is *not* an isolated measurement of the 790 fallback. The pdf-live arm runs the **whole
   live extraction chain** — tier-0 structured Tika, tier-1 OCR escalation (748 documents ended
   `ocr`, far more than the dropout set), and tier-2 VDU. 790's contribution is the dropout
   detection + the tier-2 routing fix that made an empty OCR result reach the VLM; the +0.1006 is
   the chain's, not one commit's. The decomposition bounds it: 95.2% of the gain is on the
   dropout class, which is precisely the class 790 unblocked, and 64 of those recoveries came
   from the tier-2 route that §A shows was previously unreachable for empty-OCR documents.
2. **17 queries regressed to 0.0** that were non-zero on the shipped arm (44 zeros total vs 130).
   Live extraction is not a uniform improvement — for some documents the shipped corpus's
   offline-extracted text is better than what the live chain produces today.
3. Single run per arm, no multi-seed, one corpus family, extractive queries. Same scope caveats
   as F-042.
4. The 962-query set is shared with the corpus construction (§B's ceiling analysis), so the
   dropout-class split is descriptive, not held-out.

## §H.5 Throughput impact

Ingest of the same 1000 documents, same backend, same enrichment settings — the only difference
is whether the pipeline reads bytes or replays text:

| arm | end-to-end ingest | primary indexing | enrichment 100% at |
|---|---:|---:|---:|
| `ohr-bench-tika-pdf` (text) | **74 s** (13.5 docs/s) | 6.5 s (130 docs/s) | t = 69 s |
| `ohr-bench-clean` (text) | **80 s** (12.5 docs/s) | 6.5 s (117 docs/s) | t = 75 s |
| **`ohr-bench-pdf-live` (bytes)** | **1188 s** (0.84 docs/s) | **1087 s (0.92 docs/s)** | t = 1186 s |

**Real-byte extraction costs ~1.09 s/document and dominates ingest ~16× over replaying
pre-extracted text.** That cost is extraction, not enrichment: the enrichment tail is ~99 s for
1000 documents in the pdf-live run vs ~63 s in the text runs — the same order, and the ONNX
encoder profiles are comparable (`embed` p50 37.0 ms vs 18.2 ms ORT; `splade` p50 73.7 ms vs
71.5 ms). Against tempdoc 785's chars/s framing, the pdf-live enrichment tail runs at roughly
55 kB/s vs 785's ~35 kB/s register band — enrichment is *not* the regression surface here.

**The VDU tier is the expensive part and it is off the ingest critical path.** 252 documents were
queued for VDU (139 needing visual *text* — the dropout class — and 113 needing visual
enrichment); the queue drained in **~172 minutes ≈ 41 s/document wall-clock**, well above the
13.7 s/page recorded in tempdoc 705 (that number was a bare-model page benchmark; this is the
deferred backfill under a live stack, with per-document rendering, a 120 s per-call ceiling that
a minority of pages hit, and llama-server mode transitions between batches). Because it is a
deferred backfill, ingest readiness and search were never blocked on it — the corpus was fully
searchable at t = 1188 s and the VLM recoveries landed afterwards, each re-queuing its own
embedding.

## §H.6 Operational findings (filed to the inbox, not fixed here)

Four pre-existing issues surfaced while getting the tier-2 leg to run; all are logged as
observations and none is in this PR's scope:

1. **`JUSTSEARCH_LAYOUT_ENABLED` does not exist in code.** It is named as the VDU enable flag in
   ADR-0018, three canonical docs, and this tempdoc's own §charter — and `grep` over `modules/`
   returns zero hits. The real gating is capability-based: mmproj present + VRAM + LLM online +
   pending work. Setting the documented flag is a no-op. (This tempdoc's item-1 text inherits the
   error; treat §H as the correction.)
2. **Setting `llm.modelPath` via `POST /api/settings/v2` silently disables the vision tier** —
   `usingLlmModelOverride && !MMPROJ_MODEL.isSet()` nulls `mmprojPath`
   (`InferenceConfig.java:159-170`), so VDU blocks on `vdu.missing_mmproj`. Working around it
   needs `JUSTSEARCH_MMPROJ_MODEL`, which is what this campaign did.
3. **The dev-runner captures no Head-process stdout** (`backend.stdout.log` is 27 bytes), so
   Head-side VDU failures are undiagnosable from logs; every diagnosis here came from
   `/api/status` + `worker.log`.
4. **`POST /api/knowledge/search`'s `doc_ids` scoping did not restrict the result set** (2 ids →
   20 unrelated documents), contradicting the contract map. The census therefore uses
   `/api/preview`, which is keyed by document id and exact.

A fifth, worth naming because it affects a *guard*: the `chunk_completeness` oracle computes its
expectation from `corpus.jsonl`, which a `raw_files` corpus does not have — the pdf-live run
reports `expected: 0, observed: 3144, verdict: "chunk-free"` while the index plainly has 3144
chunk documents. The guard is blind on raw-file corpora rather than wrong, but it cannot catch a
degenerate chunk build there.

## §H.7 What ran, and what did not

Ran: corpus build + validity control; full 1000-document live ingest (twice — the first was
discarded, see below); full VDU drain to `pendingVduCount = 0`; 1000-document extraction census;
962-query hybrid+3-leg runs on all three arms.

Not run: multi-seed repeats; bootstrap CIs on the new arm (786's CIs are the reference, and the
+0.1006 is ~8× the clean/tika CI half-width, but no resampling was done here); a domain-stratified
breakdown (still unavailable for the same reason F-042 records); `mixed/realdocs-v1` as a second
substrate.

Discarded and re-run: the first ingest completed cleanly, but the VDU tier was then run under a
configuration that churned — an `/api/inference/reload` mid-batch, engine restarts, and documents
burning their 3 retries — so the VDU queue state was contaminated. The whole ingest was repeated
from a hard-clean data dir with the vision tier verified *before* the run, and only that second
run is reported above. Two mechanisms cost most of the wall clock and are worth knowing:
`VduBatchProcessor`'s cooperative interrupt treats **any `/api/preview` or `/api/knowledge/search`
call as user activity** and stops the batch (`VduPacingPolicy`, 5-minute idle threshold), so a
census run mid-drain silently halts VDU; and each manual
`POST /api/operations/core.trigger-offline-processing/invoke` processes at most a 100-document
slice (`VduOps.queryPendingVduDocIds(100)`), so draining 252 documents needs repeated triggers.
