---
status: "chartered (2026-07-28), implementation-licensed — all verification is local/$0. Source evidence: register F-042 + the 786 per-query analysis (tmp/hero-arc-analysis/engine-joins/ohr-per-query.v1.json; methodology in F-042/786 §E)."
created: 2026-07-28
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
