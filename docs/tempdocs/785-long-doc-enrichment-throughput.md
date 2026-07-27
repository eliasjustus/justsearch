---
title: "long-doc enrichment throughput — profile and fix the 3-hour progress bar (legal enriches at 1.0 doc/s vs 20-33 elsewhere)"
type: tempdocs
status: "chartered (2026-07-22). Profiling-first: no optimization lands before the profile names the dominant cost; acceptance is a measured multiple at unchanged quality."
created: 2026-07-22
author: agent (Fable orchestration), chartered from the 2026-07-22 remaining-work map (founder-directed)
category: indexing-pipeline / performance
related:
  - 784-chunk-splade-engine-integration  # will ADD long-doc enrichment work; sequence the measurements
  - 640 (perf relative ratchets)          # the quality/latency guardrails this lane must keep green
---

## §A. Problem

Enrichment throughput on long-document corpora is ~1.0 doc/s (legal-clerc-200, release
scorecard engine table) vs 20.5 (scifact), 23–33 (miracl), 6.2 (enron). A user pointing
JustSearch at a 10k-document legal/report corpus waits ~3 hours before search quality
claims apply to their content. Nobody has profiled WHY the multiplier is ~20-30×— candidate
suspects (unverified): NER over full long-doc content, single-pass + windowed embedding
double work, chunk explosion (per-chunk encoder calls), SPLADE windowing, extraction
re-parsing, backfill batching policy interacting badly with doc size. The eval pipeline
already emits per-stage timings (`Combined backfill: … embed=…, splade=…, ner=…` worker
lines + jseval `--timeline`), so the raw signal largely exists — unaggregated.

## §B. Scope

1. **Profile:** per-stage, per-doc-size-bin cost attribution on legal-clerc-200 (and one
   781 long-doc stratum), from existing timing lines + targeted instrumentation only where
   the existing signal is too coarse. Output: a cost table naming the dominant stage(s) and
   their scaling shape (linear in chars? in chunks? superlinear?).
2. **Fix what the profile indicts** — candidate classes, chosen by evidence: batching across
   docs for the dominant encoder; window/stride policy for enrichment stages that don't
   need full coverage; NER scope policy on long docs (if NER dominates: does entity quality
   for search actually need the full tail?); parallelism/queueing if the bottleneck is
   scheduling rather than compute.
3. **Guardrails:** 640 ratchets green; retrieval quality flat within the cohort envelope on
   affected corpora (a throughput win that costs recall on 783's floor is a loss); GPU
   memory envelope respected (arena limits, F-031 history).

## §C. Acceptance

- The profile is a tempdoc section with numbers (stage × size-bin), not an impression.
- A measured end-to-end throughput multiple on legal-class corpora at unchanged quality
  (target set AFTER the profile — pre-committing a number before attribution is the
  interrogate-results failure mode).
- Register engine-performance note updated; 784 coordination note (their measurements must
  not interleave on the same window).

## §B.1-analysis — what the BANKED profile data already answers (offline, 2026-07-27)

**Scope of this section.** Offline re-analysis of the run banked at `tmp/analysis-785/`
(`baseline-worker.log`, `baseline-head.log`, `baseline-legal-timeline.tsv`; clean
`jseval run --start-backend --clean --pipeline` on `mixed/legal-clerc-200`, hybrid,
2026-07-22 21:39:06 → 21:41:54 local). No GPU was available; nothing here is a new
measurement. Claims are tagged **[verified]** (primary-source `file:line` or arithmetic
directly over the banked artifacts) or **[inferred]** (a reading of aggregate log lines
that the logs cannot themselves prove — every one carries a falsifier in §B.1.5).

### §B.1.0 Headline correction — legal is NOT 20-30× slower; `docs/s` is the wrong unit

§A frames the problem as "legal enriches at 1.0 doc/s vs 20.5-33 elsewhere — a 20-30×
multiplier nobody has explained." Normalising the register's own engine-performance
table (`docs/reference/search-quality-register.md:132-138`) by measured mean document
size makes the multiplier almost entirely disappear: **[verified]**

| Corpus | Mean chars/doc | Register enrich docs/s | Implied enrich **chars/s** |
|---|---:|---:|---:|
| `beir/scifact` | 1,503 | 20.5 | 30,812 |
| `mixed/miracl-fr-2k` | 356 | 23.3 | 8,300 |
| `mixed/miracl-de-2k` | 451 | 33.4 | 15,064 |
| `mixed/enron-qa` | 5,664 | 6.2 | 35,118 |
| **`mixed/legal-clerc-200`** | **35,508** | **1.0** | **35,508** |

Sources: `datasets/<corpus>/corpus.jsonl` `text` field lengths for enron/miracl/legal;
`scripts/jseval/tmp/eval-corpora/scifact/*.txt` file sizes (5,184 files, mean 1,503 B)
for scifact, whose `corpus.jsonl` is not checked out locally.

Legal is the **fastest** corpus in the fleet per byte enriched (35.5 k chars/s), tied
with enron (35.1 k) and 15 % ahead of scifact (30.8 k); MIRACL's short documents are
2.4-4.3× *worse* per byte because per-document fixed overhead dominates at 350-450
chars/doc. The engine is, to first order, **throughput-constant in bytes**. `1.0 doc/s`
is simply what a constant ~35 kB/s enrichment rate looks like when a document is 35.5 kB.

This reframes §B.2: there is no 20-30× per-document pathology to hunt. The real
questions are (a) is ~35 kB/s an acceptable *absolute* enrichment rate — it implies
~3 h for a 10 k-document legal corpus **no matter what per-doc pathology is fixed** —
and (b) how much of that 35 kB/s is genuine encoder compute vs. re-work (§B.1.1 finds
~24-28 % re-work, i.e. a ~1.35× ceiling from de-duplication alone).

### §B.1.1 Verified stage attribution — the orchestrator's aggregate is arithmetically correct but attributes the wrong window

The first-pass aggregate over the 90 `Combined backfill` lines reproduces **exactly**:
90 batches, `docs=` 12,919, fetch 778 ms, embed 99,061 ms, splade 8,943 ms, ner
22,161 ms, write 5,694 ms, total 138,590 ms. **[verified]** — re-parsed from
`baseline-worker.log`; zero unparsed lines.

Three corrections to what those numbers *mean*:

**(a) `total=138,590 ms` is not the run.** The wall clock is **162.3 s**
(`baseline-legal-timeline.tsv` last row). The combined-backfill window is
22.3 s → 159.9 s. Ingest + primary indexing occupies 0 → 22.3 s (199 docs indexed).

**(b) SPLADE's real cost is 3× the `Combined backfill` figure.** The timeline's
cumulative `splade_ms` ends at **26,996 ms** over `splade_batches=5`, but only 8,943 ms
across 3 batches appears in `Combined backfill` lines. The missing **18,053 ms /
1 batch / 10 docs** is recorded by the timeline at `elapsed_s=22.3` — before the first
combined batch — i.e. an *individual* `SpladeBackfillOps` interleave pass
(`justsearch.backfill.splade_interleave_batch_size=10`, logged at worker start). At
1.8 s/doc it is ~3.6× the per-doc cost of SPLADE inside the combined pass and almost
certainly carries first-call CUDA/session warm-up. **[verified]** counts; **[inferred]**
warm-up attribution.

**(c) The run has two structurally different phases.** Segmenting the 90 batches:

| Phase | Batches | Wall (`total`) | embed | splade | ner | fetch | write | `docs=` slots |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| **P1 parent enrichment** | 1-3 | 101,837 ms | 69,752 | 8,906 | 22,161 | 113 | 898 | 450 |
| **P2 chunk-vector tail** | 4-90 | 36,753 ms | 29,309 | 37 | 0 | 665 | 4,796 | 12,469 |
| Total | 90 | 138,590 | 99,061 | 8,943 | 22,161 | 778 | 5,694 | 12,919 |

**Corrected stage attribution** (share of the 162.3 s wall clock):

| Stage | ms | % of combined window | % of full run |
|---|---:|---:|---:|
| Embedding (ORT + tokenize + pool) | 99,061 | 71.5 % | 61.0 % |
| NER | 22,161 | 16.0 % | 13.7 % |
| SPLADE — inside combined | 8,943 | 6.5 % | 5.5 % |
| SPLADE — individual interleave pass | 18,053 | (outside) | 11.1 % |
| Write (RMW + refresh) | 5,694 | 4.1 % | 3.5 % |
| Fetch | 778 | 0.6 % | 0.5 % |
| Ingest / primary indexing (0→22.3 s, overlaps SPLADE pass) | ~22,300 | — | 13.7 % |

**Embedding dominates, by 3.2× over the next stage.** That is the profile's answer to
"which stage dominates."

Encoder-side corroboration from the cumulative `Embed per-call profile` lines
(`OnnxEmbeddingEncoder.java:466-472`, `PROFILE_LOG_INTERVAL = 50` at line 90) —
the final line at 21:41:52 reads
`1300calls: ort=62176us, extract=1738us, tensor=45us, tokenize=4075us, total=68034us,
ort=[min=7387us, p50=43057us, p95=215482us, p99=358875us, max=413143us], batch=8,
seqLen=699`. `formatAvgPhases(calls)` divides by call count
(`EncoderProfileSnapshot.java:26`), so these are **cumulative means per ORT call**:
1,300 calls × 68.0 ms = **88.4 s inside the encoder**, of which 62.2 ms/call × 1,300 =
**80.8 s is ORT proper (91 %)** and 5.3 s is tokenization. That accounts for 89 % of the
99.1 s embed stage. **[verified]**

SPLADE's own profile is the opposite shape: `60calls: postProcess=211us,
tokenize=58016us, ort=77790us, total=136017us` — **43 % of SPLADE cost is
tokenization**, because the tokenizer is constructed with `"truncation", "false"`
(`SpladeEncoder.java:220`) so it tokenizes the *whole* ~9.2 k-token document before
`Math.min(encoding.getIds().length, maxSeqLen)` throws all but the first 512 tokens
away (`SpladeEncoder.java:257-260`). SPLADE therefore **sees ~5.5 % of a mean legal
document** (512 of 9,232 tokens) — a retrieval-quality fact, not just a cost one, and
the reason SPLADE looks cheap in the combined pass (30 ms/doc). **[verified]**

**No `NER per-call profile` line was emitted at all** in the banked run
(`BertNerInference.java:62`, `PROFILE_LOG_INTERVAL = 100`; emit at line 260-266) —
so NER has no encoder-level attribution in this data set.

#### What `docs=` actually counts, and what drives the 65× re-touch

`docs=` is `pendingIds.size()` at
`modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/ops/CombinedEnrichmentBackfillOps.java:660`.
`pendingIds` is an **`ArrayList`** (line 220) filled by popping up to
`batchSize` ids from `parentIdCache` (lines 221-223) and then up to
`chunkSlotsPerBatch` ids from `chunkIdCache` (lines 224-229). Measured config:
`justsearch.backfill.embedding_batch_size=100`, `justsearch.backfill.chunk_slots_per_batch=50`
→ `docs=150` per full batch. So **`docs=` is a per-batch slot count, never a distinct-document
count and never an enriched-document count.** 12,919 ÷ 199 = 64.9 is a *slot*/doc ratio.
**[verified]**

Three distinct mechanisms inflate it, in descending order of volume:

1. **Chunk documents are pulled through the *parent* lane to have their status marked.**
   `parentIdCache` is refilled from the union of `EMBEDDING_STATUS` / `SPLADE_STATUS` /
   `NER_STATUS` = `PENDING` queries (lines 169-199). Chunk documents carry those fields,
   and the code says so itself at line 350: *"A splade-PENDING doc with no CONTENT is a
   chunk doc picked up via the splade-status query."* Such a document has no `CONTENT`,
   so it takes the `content == null || content.isBlank()` branch (line 341) and is simply
   marked `COMPLETED` without any encoder work (lines 343-368). The timeline proves this
   at scale: `ner_done` climbs 299 → 4,312 while `enrich_ner` stays pinned at **299** —
   **4,013 documents were marked NER-complete with zero NER inference.** Parent-lane slots
   in P2 = 12,469 − 4,044 = **8,425**, against ~4,200 chunk documents: roughly two passes
   each. **[verified]** counts; **[inferred]** that all 8,425 are chunk docs.
2. **Parent documents are enriched ~1.5×.** Stage-op totals across P1: splade `ok`
   = 299, ner `ok` = 299, parent embeds = `singlePass` 170 + `longDocWindowed` 121 = 291 —
   against **199** documents. Batches 1 and 2 have **byte-identical** stage counts
   (`docs=150 (embed=89,splade=100,chunks=50) … singlePass=61,longDocWindowed=36`), which
   two disjoint document sets would not produce; and `embed_pct` in the timeline sits at
   **50.3 %** after two full batches (200 parent operations) and only reaches 100 % after
   the third. Reading: ~100 documents were fully enriched **twice** → **1.50× parent
   re-work**, ≈ 34 s of the 101.8 s P1 phase. **[inferred]** — the logs carry no doc ids.
3. **A document present in *both* caches is embedded twice in one batch.** `pendingIds`
   is a `List` with no dedup across the two pops (lines 220-229); `chunkIdsInBatch` is a
   `HashSet` (line 282) so the logged `chunks=` count dedupes, but `embedDocIds` /
   `embedContents` are `ArrayList`s (lines 272-273) so both occurrences are appended and
   both are sent to `provider.embedDocumentBatch(embedContents)` (line 481). Batches 5 and
   6 show exactly this shape: `embed=100, chunks=50` and `embed=99, chunks=50` at
   671 ms / 792 ms — **6.7 ms per item**, chunk-scale, not the ~240 ms/item a real parent
   costs in P1. **[verified]** structurally from the collection types; **[inferred]** that
   the ~108 excess embed entries are duplicate chunk encodes rather than real parents.

Write-path cost is real but small: 90 `RMW batch` lines total 2,417 ms of RMW proper over
12,784 doc-writes (max single doc 21 ms), while 91 `updateDocumentsBatch` lines total
5,716 ms of which **3,232 ms is `refresh=`** — i.e. **57 % of write time is NRT refresh**,
not the writes. **[verified]**

**Ceiling implied by removing all three re-work mechanisms**: ~34 s (parent double-pass)
+ ~5.5 s (no-op parent-lane fetch+write in P2) + ~0.7 s (duplicate chunk embeds) ≈ 40 s
of 138.6 s → a **~1.35-1.4× best case from de-duplication alone**, landing at ~1.9 docs/s.
The remaining ~100 s is genuine encoder compute. Any larger win must come from the
encoder work itself, not from scheduling hygiene.

### §B.1.2 Document-size distribution — "long-doc" quantified

`datasets/mixed/legal-clerc-200/corpus.jsonl`, 198 records, `text` field. **[verified]**
(199 `.txt` files are actually ingested from
`scripts/jseval/tmp/eval-corpora/mixed/legal-clerc-200/`, and `indexedDocuments`
reaches 199 — a one-document discrepancy against `corpus.jsonl`, noted, not chased.)

Total 7,030,680 chars. Token estimates use the pipeline's own ratio,
`LATIN_CHARS_PER_TOKEN = 5.0 / 1.3` ≈ 3.846
(`modules/indexing/src/main/java/io/justsearch/indexing/chunking/ChunkSplitter.java:112`).

| Percentile | chars | est. tokens |
|---|---:|---:|
| min | 111 | 29 |
| p10 | 11,732 | 3,050 |
| p25 | 18,569 | 4,828 |
| **p50** | **27,852** | **7,242** |
| **mean** | **35,508** | **9,232** |
| p75 | 46,083 | 11,982 |
| p90 | 70,347 | 18,290 |
| p95 | 91,660 | 23,832 |
| p99 | 121,342 | 31,549 |
| max | 129,915 | 33,778 |

Mass by size bin (chars):

| Bin (chars) | docs | total chars | share of corpus text |
|---|---:|---:|---:|
| < 2 k | 4 | 2,132 | 0.0 % |
| 2 k – 5 k | 2 | 7,838 | 0.1 % |
| 5 k – 10 k | 10 | 81,987 | 1.2 % |
| 10 k – 20 k | 45 | 716,157 | 10.2 % |
| 20 k – 50 k | 95 | 3,067,811 | 43.6 % |
| 50 k – 100 k | 35 | 2,352,889 | 33.5 % |
| ≥ 100 k | 7 | 801,866 | 11.4 % |

**Derived per-document work counts** (from the pipeline's own splitters, cross-checked
against the run):

| Quantity | Splitter parameters (file:line) | Predicted total | Per doc | Measured in run |
|---|---|---:|---:|---|
| Embed windows | `chunkSize = min(512, maxSeqLen)`, `chunkOverlap = 128` → stride 384 (`OnnxEmbeddingEncoder.java:113-114`, `createChunks` 878-895) | 4,777 | 24.1 | — (not separately counted) |
| NER windows | `NER_CHUNK_TOKENS = 400`, `NER_OVERLAP_TOKENS = 50` → stride 350 (`NerService.java:31,33`; split at :164) | 5,327 | 26.9 | — (no NER profile line emitted) |
| Chunk documents | `DEFAULT_CHUNK_TOKENS = 500`, `DEFAULT_OVERLAP_TOKENS = 50` (`ChunkSplitter.java:92,95`) | 4,163 | 21.0 | **4,194** distinct chunk slots (Σ `chunks=`) — **0.7 % error** |
| Docs fitting the late-chunking single pass | `lateChunkingMaxSeqLen = 8192` (`justsearch.embed.late_chunking_context_length=8192`; `OnnxEmbeddingEncoder initialized: maxSeqLen=2048, lateChunkingMaxSeqLen=8192`) | 110 / 198 = **56 %** | — | `singlePass` 170 / (170+121) = **58.4 %** |

Two independent cross-checks (chunk-document count to 0.7 %, single-pass fraction to
2 pp) confirm the token model, so the derived window counts can be trusted as
**[verified]** arithmetic over a validated model, even though no log line states them.

So "long-doc" concretely means: **a mean legal document is ~9.2 k tokens — 4.5× the
2,048 embedding context, 18× the 512-token SPLADE/NER window — and gets split into
~24 embedding windows, ~27 NER windows and ~21 chunk documents.** 88 % of the corpus
text lives in documents over 20 k chars; 45 % lives in documents over 50 k chars.

### §B.1.3 What the banked data CANNOT answer

**A per-size-bin cost table cannot be derived from these logs. Do not fabricate one.**
The reason is structural, not a matter of effort:

1. **Every stage timing is a batch aggregate over a heterogeneous slot list.**
   `embedMs` (line 538) brackets one `provider.embedDocumentBatch(embedContents)` call
   (line 481) whose input mixes ~50 chunk documents (~500 tokens each) with windowed
   parents (thousands of tokens each). `spladeMs` (line 575) brackets one
   `encoder.encodeBatch(...)`. There is no per-document decomposition to recover.
2. **No log line carries a document identifier or a content length.** The only
   doc-scoped lines in the whole worker log are the two late-chunking WARN paths
   (lines 450-465, 464) and the NER failure WARN (line 605) — none fired in this run.
   So re-touch (§B.1.1 items 2 and 3) can be *counted in aggregate* but never
   *attributed to specific documents*, and the 1.50× parent-re-work reading cannot be
   promoted from **[inferred]** to **[verified]** with this data.
3. **`encoderProfiles` / the per-call profile lines are size-blind.** They carry ORT
   duration percentiles and the *last* call's `batchSize`/`seqLen`
   (`OnnxEmbeddingEncoder.java:470-471`) — not a duration↔seq_len joint distribution.
   p50 = 43 ms and p95 = 215 ms are visibly two different populations, but nothing in
   the banked data says which is the 8×512 windowed batch and which is the batch-1
   8192-token single pass.
4. **NER has no encoder-level data at all** — zero `NER per-call profile` lines
   (interval 100, `BertNerInference.java:62`). NER's 22.2 s is a black box: window count,
   per-window cost, and GPU/CPU split are all unknown.
5. **GPU utilisation in the timeline is a 2 s-cadence poll**, so it cannot separate
   "GPU busy on embed" from "GPU busy on NER" — the two never appear in the same row.
6. **The register's `Enrich docs/s` definition is not re-derivable here.** §B.1.0's
   chars/s normalisation assumes the same denominator across corpora; that assumption
   is unverified against the scorecard generator and should be confirmed before the
   headline is published.

Also out of reach: whether the P1 double-pass is caused by NRT-refresh staleness
(refresh is suspended during the tight loop, comment at lines 640-641) or by the
RMW-destroys-non-stored-fields → reset-to-PENDING cycle the file documents at lines
41-57 and 318-322. Both are consistent with every number in the banked data.

### §B.1.4 The instrumented-run specification (one GPU window, one pass)

**Design rule: three of the five questions need no new code.** New emission is required
only for per-document size↔cost attribution and for distinct-document counting.

#### Lever 1 — existing, zero code: `encoderProfiles` via the pipeline summary

`EnrichmentProgressView.encoderProfiles`
(`modules/app-api/src/main/java/io/justsearch/app/api/status/EnrichmentProgressView.java:25`,
element type `EncoderProfileView` = `calls`, `phaseTotalUs`, `ortMin/Max/P50/P95/P99Us`)
is already polled by jseval and folded into the run summary as `encoder_profiles`
(`scripts/jseval/jseval/timeline.py:113-119` captures `_encoder_profiles`;
`:282-307` deltas it). It is **not** written to the TSV. Running with `--json` and
keeping the summary gives **exact per-encoder ORT totals and call counts for embed,
splade, ner, bgem3, reranker** — which settles "which stage dominates" at the ORT
layer and closes gap §B.1.3(4) with no code at all.

#### Lever 2 — existing, zero code: `encoder.ort_run` spans

`JUSTSEARCH_INDEX_TRACING_LEVEL=detailed` (read once at class-load,
`EncoderOrtRunSpans.java:30-34`; documented at
`docs/reference/configuration/environment-variables.md:49`) turns on per-ORT-call spans
carrying **`encoder.name`, `encoder.batch_size`, `encoder.seq_len`, `encoder.gpu`**
(`EncoderOrtRunSpans.java:67-77`), nested under `enrichment.batch`
(`CombinedEnrichmentBackfillOps.java:153`). Exported by `NdjsonSpanExporter` to
`<dataDir>/telemetry/traces.ndjson`. This yields the **duration ↔ seq_len ↔ batch_size
joint distribution** — i.e. the *scaling shape* of encoder cost — which the banked data
cannot provide. Caveat to check on the day: only attributes in
`NdjsonSpanExporter.ALLOWED_ATTRS` survive export; confirm the three `encoder.*`
attributes are listed before trusting the file.

#### Lever 3 — existing, zero code: stratified sub-corpora

Per-*size-bin* cost does not need per-document instrumentation if the corpus is
homogeneous. Build 4 sub-corpora of ~40 docs each from the §B.1.2 bins
(≈5 k, ≈20 k, ≈50 k, ≈120 k chars) and run each; `batchTiming` + `encoder_profiles` then
give per-bin per-document cost directly. This is the **single highest-value item** and it
requires no Java change — only corpus construction under
`scripts/jseval/666-corpora/`.

#### Lever 4 — new emission, genuinely required (3 log lines)

Nothing in the codebase carries document length into a timing line, and nothing counts
distinct documents per batch. These are the minimum additions:

| # | Emit at | Line to add | Answers |
|---|---|---|---|
| L4a | `CombinedEnrichmentBackfillOps.java:432` (inside the per-doc late-chunking loop, 427-475) | wrap `embedWithSpans` in a nanoTime pair and log `docId`, `lcContent.length()`, resulting ms, and whether it returned null (→ windowed) | **embed cost vs. document length, per document** — this loop is already per-document, so it is a 3-line change and the only place a whole-document embed is individually timed |
| L4b | `CombinedEnrichmentBackfillOps.java:594-599` (the NER per-doc loop, 583-617) | wrap `nerService.extractEntitiesBatch(List.of(content))` in a nanoTime pair; log `docId`, `content.length()`, ms | **NER cost vs. document length** — closes gap §B.1.3(4). Note this loop calls the *batch* API with a **single-element list**, so NER's cross-document chunk batching (`NerService.java:151-195`) is structurally defeated; the measurement is also the evidence for hypothesis H3 |
| L4c | `CombinedEnrichmentBackfillOps.java:655-679` (the `Combined backfill` line) | add `distinctDocs=<new HashSet<>(pendingIds).size()>`, `parentSlots=<pendingIds.size() - chunkIdsInBatch.size()>`, `dupEmbedEntries=<embedDocIds.size() - new HashSet<>(embedDocIds).size()>` | promotes §B.1.1 items 2-3 from **[inferred]** to **[verified]**; a one-line change to an existing log statement |

Everything else stays as-is. Do **not** add per-window timing inside
`OnnxEmbeddingEncoder` — Lever 2's spans already carry it.

#### The run command

```bash
# 0. Build the three additions (L4a/L4b/L4c) first; then, from the repo root:
./gradlew.bat spotlessApply && ./gradlew.bat build -x test && ./gradlew.bat :modules:ui:installDist

# 1. Baseline re-run of the WHOLE corpus, spans on, summary kept.
#    INSPECT_DISPLAY/PYTHONUTF8 per the Windows backgrounding caveat in CLAUDE.md.
cd scripts/jseval
JUSTSEARCH_INDEX_TRACING_LEVEL=detailed INSPECT_DISPLAY=none PYTHONUTF8=1 \
  python -m jseval run --dataset mixed/legal-clerc-200 \
    --start-backend --clean --pipeline --max-queries 0 --json \
    --timeline ../../tmp/analysis-785/instr-legal-timeline.tsv \
  > ../../tmp/analysis-785/instr-legal-summary.json

# 2. Four size-stratified sub-corpora (Lever 3), same flags, --corpus-dir per bin.
for BIN in 5k 20k 50k 120k; do
  JUSTSEARCH_INDEX_TRACING_LEVEL=detailed INSPECT_DISPLAY=none PYTHONUTF8=1 \
    python -m jseval run --corpus-dir ../../tmp/analysis-785/bins/$BIN \
      --start-backend --clean --pipeline --max-queries 0 --json \
      --timeline ../../tmp/analysis-785/instr-$BIN-timeline.tsv \
    > ../../tmp/analysis-785/instr-$BIN-summary.json
done
```

**Artifacts to bank per run:** the `--json` summary (carries `encoder_profiles`,
`inference`, `overhead`, `gpu`), the timeline TSV, the worker log, and
`<dataDir>/telemetry/traces.ndjson`.

**Sequencing note (784 coordination, §C):** all five runs must occupy one contiguous
lease. Declare `leaseDurationSec` generously at stack start; the baseline alone is
~165 s but a clean start + 5 runs is ~25-35 min.

**Pre-registered validity checks** (decided before results are seen, per the 2026-07-22
probe-leak lesson): (i) `indexedDocuments` must equal the sub-corpus document count in
every run — a short ingest invalidates the bin; (ii) `encoder_profiles.embed.calls`
must be > 0 and `arenaOomWindowed` must be 0 in every `Combined backfill` line — a BFC
arena OOM silently switches to a per-doc fallback ladder
(`OnnxEmbeddingEncoder.java:427-451`) and changes the cost model being measured;
(iii) `splade_churn_drops` must be absent from every summary; (iv) the tracing-on
baseline's `total_ms` must be within 15 % of the banked 138,590 ms — a larger gap means
`detailed` tracing perturbed the thing being measured and the spans must be discarded.

### §B.1.5 Ranked hypotheses, each with its falsifying observation

Ranked by expected share of the 138.6 s combined window. **No fixes are proposed here —
785 is profile-then-fix.**

**H1 — Embedding dominates because a mean legal document is windowed into ~24 forward
passes, and cost is ~linear in windows (hence in chars).** Expected share ~71 %.
Support: embed = 99.1 s of 138.6 s; ~4,777 predicted windows; per-byte throughput is
constant across four corpora spanning 100× in document size (§B.1.0).
**Falsified if:** the stratified runs (Lever 3) show embed-ms-per-**char** varying by
more than ~1.5× across the 5 k → 120 k bins, or if `encoder.ort_run` durations at fixed
`seq_len`/`batch_size` differ materially between bins. Either would mean something
super- or sub-linear is happening beyond window count.

**H2 — The late-chunking single pass (8192-token, batch-1) is quadratic in sequence
length and costs materially more per token than the 8×512 windowed path, so the
"single-pass" optimisation is a pessimisation on this corpus.** Expected share: it
handles 58 % of parents. Support: `ort` percentiles are visibly bimodal — p50 43 ms
(consistent with 8×512) vs p95 215-347 ms and max 413 ms (consistent with 1×8192);
naive per-token arithmetic makes the single pass ~2.6× dearer per token. Confounder to
respect: single-pass covers 8,192 tokens while an 8×512 windowed batch covers only
8×384 = 3,072 *new* tokens at stride 384.
**Falsified if:** Lever 2's spans show `encoder.seq_len≈8192, batch_size=1` durations at
or below `8192/3072 ×` the `seq_len≈512, batch_size=8` duration — i.e. the single pass is
per-token competitive after all.

**H3 — NER is 16 % of the window because the combined pass calls the batch API one
document at a time, defeating cross-document chunk batching.** Support:
`CombinedEnrichmentBackfillOps.java:583-595` loops per `docId` and calls
`nerService.extractEntitiesBatch(List.of(content))`; `NerService.java:151-195` exists
precisely to flatten chunks *across* documents into shared inference batches. The
in-file comment at lines 577-578 ("Per-doc at 2.0 ms/call is near-optimal") reflects a
short-document measurement — the banked run is **74 ms/doc** (22,161 ms ÷ 299 ops).
**Falsified if:** L4b shows NER ms-per-doc scaling flat-to-sublinear in document length,
or `encoder_profiles.ner` shows a mean batch size > 1 (meaning the flattening already
works despite the single-element call site).

**H4 — ~24-28 % of the combined window is re-work, not compute: parents enriched ~1.5×,
plus ~8,425 no-op parent-lane slots spent marking chunk documents' statuses.** Support:
299 splade/ner ops and 291 parent-embed ops against 199 documents; byte-identical
batches 1 and 2; `embed_pct` stuck at 50.3 % after 200 parent operations;
`ner_done` 4,312 vs `enrich_ner` 299.
**Falsified if:** L4c's `distinctDocs` shows ~199 distinct parents across batches 1-3
(i.e. the identical batch statistics were coincidence) — or if the P1 double-pass
disappears in the instrumented re-run, which would make it a transient rather than a
structural property.

**H5 — SPLADE's visible cheapness is an artifact of truncating away 94.5 % of each
document, and its real cost is dominated by tokenizing text it then discards.**
Support: `SpladeEncoder.java:220` disables tokenizer truncation, `:257-260` truncates to
512 of ~9,232 tokens; the profile line shows tokenize = 58.0 ms of 136.0 ms per call
(43 %). Cross-lane relevance: 784 turns on chunk-SPLADE, which will multiply SPLADE's
call count by ~21× on this corpus.
**Falsified if:** the stratified runs show SPLADE ms-per-**document** flat across the
5 k → 120 k bins *including* the tokenize phase of `encoder_profiles.splade.phaseTotalUs`
— that would mean tokenization is not scaling with document length and the discarded
work is free.

**H6 — Write-path cost is NRT refresh, not writing.** Expected share ~2 % of the run.
Support: 3,232 ms of the 5,716 ms `updateDocumentsBatch` total is `refresh=`, while the
RMW itself is 2,417 ms over 12,784 doc-writes. Lowest-value hypothesis; listed so it is
not re-discovered.
**Falsified if:** the instrumented run's `refresh=` share drops below ~25 % once
duplicate parent-lane slots (H4) are counted out.

### §B.1.6 Sequencing consequence for §B.2

If H1 holds and H2/H3/H4 are the only recoverable slack, the honest ceiling is roughly:
1.35-1.4× from de-duplication (H4) × whatever H2 and H3 return. That is very unlikely to
reach a 10× "3 hours → 20 minutes" outcome. **§D's progressive-availability option should
therefore be treated as a co-equal branch, not a fallback** — and the §C acceptance target
should be set only after the stratified runs land.

## §D. Notes

- This is a product-experience lane as much as an engineering one: if the honest ceiling is
  modest, progressive availability (search-while-enriching semantics, which the readiness/
  compatibility machinery partially supports) is a legitimate §B.2 alternative to raw speed.
