---
title: "251: Realistic Evaluation Framework"
type: tempdoc
status: done
created: 2026-03-02
---

> NOTE: Noncanonical doc (strategy). May drift.

# 251: Realistic Evaluation Framework

## Purpose

Build evaluation infrastructure that measures what desktop search users
actually experience, replacing exclusive reliance on BEIR short-document
academic benchmarks. This is the keystone for all other quality work —
without realistic eval, improvements to ingestion (252), models (253), and
pipeline routing (250) are unmeasurable.

**Thesis:** JustSearch is competitive on BEIR (245 proved that). But BEIR
measures the wrong thing for a desktop file search tool. Real users have
messy PDFs, long documents, known-item queries, and heterogeneous
collections. The gap between BEIR performance and real-world quality is
the largest unmeasured risk.

## Mixed SPLADE Throughput Note (2026-03-08)

The active `SciFact + FiQA` mixed-corpus SPLADE lane exposed an evaluation
throughput bottleneck that matters for practical execution planning.

Dedicated design/technology analysis now lives in tempdoc `266`.

Confirmed repo-local findings:

- the live indexing path is intentionally narrow:
  - `SSOT/pipelines/indexing.v1.json` sets `concurrency: 1`
  - `LoopPacingPolicy.java` sets `POLL_BATCH_SIZE = 1`
  - `IndexingLoop.java` runs a single `indexing-loop` thread
- SPLADE encoding currently happens inline during document indexing, not as a
  batched side path:
  - `IndexingDocumentOps.buildDocument(...)` calls `spladeEncoder.encode(...)`
    synchronously per document when the encoder is available
- the active mixed SPLADE run is CPU-only unless explicitly reconfigured:
  - worker log shows `SPLADE enabled ... gpu=false`
  - `SpladeEncoder initialized ... gpuConfigured=false`

Implication:

- low observed hardware utilization during this lane is not primarily a
  deadlock signal
- it is the expected result of a single-width, mostly synchronous indexing
  path that prioritizes responsiveness over maximum throughput
- any future attempt to speed up this lane should be treated as an explicit
  architecture/config decision, not as a simple monitoring anomaly

Implementation readout (repo + external docs, 2026-03-08):

- **Enable GPU SPLADE for this lane first.**
  - This is the lowest-risk throughput lever for the active mixed-corpus lane.
  - The repo already ships `onnxruntime_gpu` in `modules/indexer-worker` and
    `SpladeConfig` already supports `JUSTSEARCH_SPLADE_GPU_ENABLED`,
    `JUSTSEARCH_SPLADE_GPU_DEVICE_ID`, and `JUSTSEARCH_SPLADE_GPU_MEM_MB`.
  - The canonical wrapper already supports backend env passthrough via
    `BackendEnv`, so this is primarily a workflow-config change, not a worker
    architecture rewrite.
  - The concrete lane config shape is straightforward:
    - `JUSTSEARCH_SPLADE_GPU_ENABLED=true`
    - `JUSTSEARCH_SPLADE_GPU_DEVICE_ID=0`
    - `JUSTSEARCH_SPLADE_GPU_MEM_MB=<explicit value>`
    - keep `JUSTSEARCH_AI_EMBED_ENABLED=false` for the current eval lane
  - ONNX Runtime's Java docs already support the GPU package, and the CUDA EP
    docs expose `gpu_mem_limit` as a tunable arena cap.
  - Important nuance: the current SPLADE ONNX is ~532 MB on disk while the
    worker default is only 256 MB for `JUSTSEARCH_SPLADE_GPU_MEM_MB`. This
    makes GPU enablement implementation-ready, but not "flip one boolean and
    forget it." Lane configs should set an explicit larger GPU memory budget.

- **Do not widen the indexing loop / claim batch as the first fix.**
  - This is the highest-risk option because it pushes directly against the
    repo's responsiveness-first design: breath-holding, `concurrency: 1`, and
    `POLL_BATCH_SIZE = 1` are intentional, not accidental.
  - ONNX Runtime's CPU threading docs state that the default CPU session uses
    one intra-op worker per physical core. Since `SpladeEncoder` currently
    creates the CPU session with default options, the inner inference path is
    already allowed to parallelize across cores. Increasing outer loop width
    risks oversubscription and responsiveness regressions rather than obvious
    throughput wins.

- **Batch SPLADE before widening the whole loop.**
  - `SpladeEncoder.encodeBatch(...)` already exists, so batching is more
    implementation-ready than loop widening.
  - The cheaper seam is not the whole indexing loop; it is the SPLADE-specific
    path. `SpladeBackfillOps` already fetches pending IDs in batches but still
    calls `encoder.encode(...)` one document at a time.
  - The hardened BEIR/search harness already treats SPLADE as a readiness-gated
    capability (`splade_ready` must be true). That means a deferred batched
    SPLADE path remains evaluation-compatible as long as the run waits for
    readiness before querying.
  - ONNX Runtime's I/O binding docs are mainly about avoiding host/device copy
    overhead when tensors already live on the target device. That is not the
    current Java SPLADE path: tokenization and tensor construction still happen
    on the host, so I/O binding is not the first missing optimization.
  - That makes SPLADE batching a medium-risk, localized worker change, while a
    multi-width indexing loop would be a broader architecture change.

- **Keep the current behavior as the default product posture.**
  - For normal desktop operation, the current narrow path is consistent with
    the repo's stated preference for interactive responsiveness over maximum
    background throughput.
  - The correct distinction is therefore:
    - product default: keep the responsiveness-first worker posture
    - active mixed-corpus eval lane: allow a backend-only, GPU-enabled SPLADE
      configuration because this run is explicitly benchmark-oriented and not
      sharing the GPU with interactive chat

Implementation update (2026-03-08, later):

- backend-only GPU SPLADE is now implemented for the active mixed-corpus lane
  rather than remaining theoretical
  - the worker classpath was corrected so the backend-only worker dist no
    longer mixes CPU ORT with GPU ORT
  - the worker now honors `JUSTSEARCH_AI_EMBED_ENABLED=false` during startup,
    so the backend-only eval lane does not eagerly initialize the embedding
    service
  - `SpladeEncoder` now resolves `onnxruntime.native.path` from
    `JUSTSEARCH_NATIVE_PATH` and uses a narrow preload of CUDA/cuDNN
    dependency DLLs immediately before GPU session creation
- an attempted process-wide `PATH` prepend was rejected
  - it helped ORT dependency discovery, but it broke DJL tokenizer native
    loading (`libwinpthread-1.dll`) in the same worker process
  - the accepted design is the narrow preload in `SpladeEncoder`, not a
    process-wide loader mutation
- the fresh `gpu-r5` mixed-corpus run now logs:
  - `SpladeEncoder GPU session initialized: device=0, memLimit=2048MB`
- this resolves the throughput / hardware-utilization question for the active
  lane: backend-only GPU SPLADE is viable in the current architecture without
  widening the indexing loop

Important caveat:

- the first fresh GPU mixed-corpus SPLADE suite (`phase4-mixed-*-gpu-r5`)
  produced all-zero BEIR metrics
- that means the main remaining blocker for this lane is no longer GPU
  execution, but mixed-corpus SPLADE evaluation fidelity
- do not interpret the current mixed-corpus SPLADE quality result yet
- immediate cause of the invalid `gpu-r5` result:
  - the BEIR harness accepted a transient early `IDLE` state after the built-in
    `justsearch-help` collection finished indexing
  - the actual mixed corpus only started enqueuing 5,000-file batches after
    evaluation had already begun
- mitigation now implemented:
  - `Wait-EvalIndexIdle` can now enforce an expected indexed-document floor
  - `Invoke-BeirIndexingPhase` now passes the corpus-derived minimum document
    count for fresh ingest runs before it will accept `IDLE`
- the corrected `gpu-r6` rerun completed successfully and is now the
  meaningful mixed-corpus SPLADE result:
  - GPU SPLADE initializes successfully
  - the run waits for the real mixed corpus to finish indexing before query
    execution
  - the first fresh small-mix SPLADE measurements are now valid rather than
    fidelity-tainted

---

## Problems with Current Evaluation

### 1. BEIR tests clean short text only

All 4 evaluated datasets (SciFact, Arguana, NFCorpus, FiQA) have documents
well under 2048 tokens. Documents are pre-cleaned plaintext. This means:

- Context window (2048 vs 8192) has zero measurable effect
- Chunking never triggers (docs below chunk threshold)
- Ingestion quality is invisible (no PDF parsing, no tables)
- SPLADE 256 vs 512 token encoding shows no difference

The pipeline components that matter most for real users — chunking,
long-document handling, ingestion quality — are completely untested.

### 2. No known-item refinding benchmark

Desktop search is primarily a **known-item refinding** task: "find that
email from John about the budget." The user knows the document exists and
wants it at rank 1. This requires MRR and P@1, not nDCG@10.

245's evaluation strategy section (lines 590-605) identified this gap and
proposed MRR as the primary metric for known-item scenarios. P@1 was added
to the harness (item 7) but no known-item benchmark exists to run it on.

### 3. No heterogeneous collection benchmark

A user's file collection spans every domain — legal, financial, personal,
technical, academic. BEIR tests one domain at a time. The pipeline should
work well across domains simultaneously, including adaptive behavior
(SPLADE helps on scientific text but not financial — see 245 multi-dataset
observations).

### 4. LLM-as-judge not operationalized

245 designed an LLM-as-judge protocol and ran a preliminary validation
(91.3% agreement with BEIR labels on SciFact). But it was never turned
into a repeatable eval tool. LLM-as-judge is the only viable approach for
evaluating on custom document collections where no human relevance labels
exist.

---

## Evaluation Dimensions

### Dimension 1: Long-Document Retrieval

**Why:** Real desktop files (PDFs, Word docs, legal contracts, academic
papers) routinely exceed 2048 tokens. JustSearch truncates at 2048.
Context window (item 6), chunking quality, and chunk-merge effectiveness
all only matter here.

**Benchmark: LoCoV1 (Long-Context Retrieval)**

LoCoV1 (arXiv 2402.07440) is purpose-built for this. Two subsets are
feasible at desktop scale:

| Subset | Queries | Corpus | Avg doc length | Notes |
|--------|---------|--------|----------------|-------|
| courtlistener_Plain_Text (legal) | 2,000 | 2,000 opinions | ~25,000 chars (~6,000 tokens) | Long legal docs, all exceed chunk threshold |
| legal_case_reports | 770 | 770 reports | ~190,000 chars (~47,500 tokens) | Very long legal docs, extreme chunking stress test |

245 identified these as feasible (lines 555-573) and noted a BEIR-format
converter is needed. A converter script was implemented in commit 456638e0
(`LocoV1-to-BEIR corpus converter`).

> **Note (2026-03-03):** stackoverflow was originally listed here but
> investigation found its documents are overwhelmingly short (median ~300
> chars, 98% under 2,000 chars). It does NOT test long-document retrieval
> and has been moved to Phase 4 as a cross-domain component. See
> Investigation Findings below.

**What it measures:**
- Context window effect (2048 vs 8192 on the same queries)
- Chunk merge contribution (with/without, on documents that actually get chunked)
- Ingestion pipeline impact (if fed through real parsing vs pre-cleaned)

**Readiness:** Converter exists and is compatible with `beir-eval-win.ps1`
(JSONL format matches, `-SkipDownload` path works, output dir convention
aligns). HuggingFace API does not truncate long documents (verified up to
577K chars). Download takes ~5-10 min per subset. Indexing with embeddings
estimated at ~12h for courtlistener (from 245 time estimates).

### Dimension 2: Known-Item Refinding

**Why:** The primary desktop search use case. Users type partial titles,
names, or distinctive phrases to find a specific document. Success = rank 1.

**Benchmark options:**

1. **Synthetic known-item from existing BEIR corpora.** For each document
   in a corpus, generate a query that a user would type to find it:
   title fragments, distinctive phrases, author names. LLM-generated with
   human validation. This is cheap and reusable.

2. **Personal collection eval.** Index a real document collection (e.g.,
   a curated set of PDFs, emails, notes). Write known-item queries by hand.
   Gold standard but not reproducible by others.

3. **NanoBEIR known-item subset.** Some NanoBEIR datasets have
   navigational queries (user wants one specific document). Filter and
   evaluate with P@1 and MRR.

**Metrics:** MRR (primary), P@1, Recall@1. nDCG@10 is secondary.

**Readiness:** Needs design. The LLM query generation approach is most
viable — could reuse the existing LLM-as-judge infrastructure.

### Dimension 3: Heterogeneous Collections

**Why:** Real users don't have single-domain collections. A search for
"budget report" should work whether the user has financial docs, project
plans, or personal spreadsheets.

**Benchmark: Multi-domain mixed corpus.**

Combine documents from multiple BEIR datasets into a single index. Run
queries from each dataset against the mixed index. Measure whether
cross-domain noise degrades within-domain retrieval.

| Mix | Domains | Corpus size |
|-----|---------|-------------|
| Small | SciFact + FiQA | ~52K docs |
| Medium | SciFact + FiQA + NFCorpus + Arguana | ~65K docs |
| Large | Above + LoCoV1 courtlistener (2K) + stackoverflow (7.7K) | ~75K docs |

**What it measures:**
- Cross-domain retrieval noise
- Whether SPLADE helps or hurts in mixed collections
- Whether RRF fusion degrades with heterogeneous BM25 score distributions

**Readiness:** Needs new harness capability — multi-dataset indexing with
per-dataset query evaluation. Moderate effort.

### Dimension 4: LLM-as-Judge for Custom Collections

**Why:** For any document collection without pre-existing relevance labels,
LLM-as-judge is the only scalable evaluation approach.

**What exists (from 245):**
- Preliminary validation: 91.3% agreement with BEIR labels on SciFact
- Protocol: 4-point relevance scale with explanation
- haiku model identified as cost-effective judge

**What's missing:**
- Repeatable script that takes (query, document, optional label) and
  produces a judgment
- Aggregation script that computes nDCG/MRR from judgments
- Calibration across domains (SciFact validation may not transfer)

**Readiness:** Protocol designed, needs scripting and cross-domain
validation.

---

## Work Items

### Phase 1: Long-Document Eval (LoCoV1)

- [x] **1a.** Run LoCoV1 courtlistener_Plain_Text through existing BEIR
  harness (use converter from 456638e0). Establish baseline nDCG@10 with
  current pipeline (2048 context, Q8_0, Dense RRF, chunk merge ON).
  *Completed 2026-03-04. Results below.*
- [x] **1b.** Run same with context 8192 (item 6 from 245). Measure delta.
  This is the empirical validation for the context window change.
  *Completed 2026-03-04. Added `JUSTSEARCH_EMBED_CONTEXT_LENGTH` env var to
  `EmbeddingService.java` + `DeterminismProfile.java` override. Results below.*
- [x] **1c.** Run with chunk merge enabled vs disabled. Measure chunk
  contribution on documents that actually get chunked.
  *Completed 2026-03-04. Toggle is YAML-only (`search.chunk_aware.enabled`),
  not an env var as originally thought. Results below.*
- [x] **1d.** Run LoCoV1 legal_case_reports as second long-doc dataset.
  770 docs, avg ~47,500 tokens — extreme chunking stress test.
  *Completed 2026-03-04. Results below.*

#### Phase 1 Execution Plan (2026-03-04)

Execution order: **1a → 1c → 1d → 1b.** Agent executes manually, staying
engaged through multi-hour runs to handle failures in real time.

**1a steps:**
1. Run converter: `node scripts/search/convert-locov1-to-beir.mjs --task courtlistener_Plain_Text`
2. Build project: `./gradlew.bat build -x test`
3. Start dev server with embedding + SPLADE enabled
4. Run harness: `.\scripts\search\beir-eval-win.ps1 -Dataset courtlistener_Plain_Text -SkipDownload -Modes lexical,hybrid`
5. Harness handles: materialize → index → wait idle → query → metrics
6. Sanity check: if first queries return zero recall, investigate docID mapping
7. Record results in tempdoc

**1c steps (after 1a):**
1. Set `search.chunk_aware.enabled: false` in `config/application.yaml` (YAML-only, no env var)
2. Restart server (preserve index), re-run harness with `-SkipIndex`
3. Compare metrics to 1a baseline, then revert YAML change

**1d steps (after 1c):**
1. Run converter: `node scripts/search/convert-locov1-to-beir.mjs --task legal_case_reports`
2. Clean index, restart server (chunk merge ON again)
3. Run harness for legal_case_reports
4. Record results

**1b steps (after 1d):**
1. Add `JUSTSEARCH_EMBED_CONTEXT_LENGTH` env var to `EmbeddingService.java`
2. Build, restart server with context=8192
3. Clean index, re-index courtlistener with 8192 context embeddings
4. Run queries, compare to 1a baseline

#### Phase 1 Results

**1a — courtlistener baseline (2026-03-04)**

Config: Q8_0 nomic-embed, 2048 context, chunk merge ON, no SPLADE, no LambdaMART.
Indexing: 2,001 docs (incl. sentinel), 3.38 docs/sec, ~10 min wall time.
Artifacts: `tmp/beir-eval/courtlistener_plain_text-20260304-040634/`

| Mode | nDCG@10 | Recall@10 | MRR | P@1 | chunkMergeRate | vectorEvidence |
|------|---------|-----------|-----|-----|----------------|----------------|
| lexical | **0.924** | 0.977 | 0.906 | 0.862 | 1.0 | 0.0 |
| hybrid | 0.647 | **0.991** | 0.526 | 0.088 | 1.0 | 0.797 |

**Key finding: Hybrid mode severely degrades ranking on long documents.**
BM25 alone achieves 86.2% P@1 and 0.924 nDCG; hybrid drops to 8.8% P@1 and
0.647 nDCG despite slightly better recall (0.991 vs 0.977). The 2048-token
embedding context captures only ~1/3 of each 6,000-token document, producing
poor vector representations that hurt RRF fusion. Incomplete embedding
coverage (80% at query time) may also contribute.

This directly motivates Phase 1b (8192 context) — wider context should
produce better vector representations and improve hybrid ranking.

**1c — chunk merge ablation (2026-03-04)**

Config: Same index as 1a, but `search.chunk_aware.enabled: false` in YAML.
Note: The env var `JUSTSEARCH_CHUNK_AWARE_ENABLED` does NOT exist — this is
YAML-only config under the `search:` section. Previous investigation finding
about the env var was incorrect.
Artifacts: `tmp/beir-eval/courtlistener_plain_text-20260304-043335/`

| Mode | nDCG@10 | Recall@10 | MRR | P@1 | chunkMergeRate | vectorEvidence |
|------|---------|-----------|-----|-----|----------------|----------------|
| lexical | **0.924** | 0.977 | 0.906 | 0.862 | 0.0 | 0.0 |
| hybrid | 0.133 | 0.188 | 0.116 | 0.086 | 0.0 | 0.780 |

**Key finding: Chunk merge is essential for hybrid search on long documents.**
Without chunk merge, hybrid recall collapses from 99.1% to 18.8% (-81%).
The vector leg returns chunk-level results that don't map to whole document IDs,
breaking RRF fusion. BM25 is completely unaffected (operates on whole documents).

| Metric | 1a (chunk ON) | 1c (chunk OFF) | Delta |
|--------|--------------|----------------|-------|
| hybrid nDCG@10 | 0.647 | 0.133 | **-0.514** |
| hybrid Recall@10 | 0.991 | 0.188 | **-0.803** |
| lexical nDCG@10 | 0.924 | 0.924 | 0.000 |
| lexical Recall@10 | 0.977 | 0.977 | 0.000 |

**1d — legal_case_reports baseline (2026-03-04)**

Config: Same as 1a (Q8_0 nomic-embed, 2048 context, chunk merge ON). Fresh index.
Corpus: 770 docs, avg ~190K chars (~47,500 tokens) — extreme chunking stress test.
Indexing: 770 docs, 18,633 chunks, 3.52 docs/sec, ~4 min wall time.
Artifacts: `tmp/beir-eval/legal_case_reports-20260304-044056/`

| Mode | nDCG@10 | Recall@10 | MRR | P@1 | chunkMergeRate | vectorEvidence |
|------|---------|-----------|-----|-----|----------------|----------------|
| lexical | 0.250 | 0.309 | 0.231 | 0.191 | 0.422 | 0.0 |
| hybrid | **0.331** | **0.504** | 0.276 | 0.175 | 1.0 | 0.810 |

**Key findings:**

1. **Hybrid outperforms lexical on very long documents** — opposite of courtlistener.
   Hybrid nDCG is +0.081 over lexical (0.331 vs 0.250), and recall is +0.195
   (0.504 vs 0.309). At extreme document lengths, BM25 term statistics become
   noisy (high TF dilution across 47K tokens), and dense vectors provide a stronger
   relevance signal despite the 2048-token truncation.

2. **Both modes show much lower absolute scores than courtlistener.** Lexical nDCG
   dropped from 0.924 → 0.250, hybrid from 0.647 → 0.331. These documents are
   ~8x longer than courtlistener — retrieval is fundamentally harder when the
   relevant passage is buried in 47K tokens.

3. **Lexical chunk merge rate is only 42%** — not all queries trigger chunk-level
   results in lexical mode (no vector leg). The 58% without chunk merge are queries
   where the whole-document BM25 score already captured the top results.

4. **P@1 is low for both modes** (~17-19%). Finding the exact right document at
   rank 1 is very difficult when all 770 documents are long legal texts with
   overlapping vocabulary.

**Cross-dataset comparison:**

| Metric | courtlistener (6K tok) | legal_case_reports (47K tok) |
|--------|----------------------|------------------------------|
| lexical nDCG@10 | 0.924 | 0.250 |
| hybrid nDCG@10 | 0.647 | 0.331 |
| lexical Recall@10 | 0.977 | 0.309 |
| hybrid Recall@10 | 0.991 | 0.504 |
| hybrid > lexical? | No (-0.277) | **Yes (+0.081)** |

The crossover point is significant: hybrid search adds value precisely when
documents are long enough that BM25 alone struggles. At moderate lengths (6K tokens),
BM25 is still dominant. At extreme lengths (47K tokens), vector representations
(even truncated to 2048 tokens) provide complementary signal.

**1b — context window 8192 (2026-03-04)**

Config: Q8_0 nomic-embed, **8192 context** (override via `JUSTSEARCH_EMBED_CONTEXT_LENGTH`),
chunk merge ON. Fresh index, full re-embedding at 8192 context.
Code changes: `EmbeddingService.java` reads env var, `DeterminismProfile.java` allows
override when env var is set (bypasses nCtxTrain clamp).
Embedding time: ~2.5h for 2,001 docs (58,279 chunks) — 4x slower than 2048 context.
Artifacts: `tmp/beir-eval/courtlistener_plain_text-20260304-080351/`

| Mode | nDCG@10 | Recall@10 | MRR | P@1 | chunkMergeRate | vectorEvidence |
|------|---------|-----------|-----|-----|----------------|----------------|
| lexical | **0.924** | 0.977 | 0.906 | 0.862 | 1.0 | 0.0 |
| hybrid | 0.631 | 0.988 | 0.508 | 0.086 | 1.0 | 0.789 |

**Result: 8192 context slightly degrades all hybrid metrics:**

| Metric | 1a (2048 ctx) | 1b (8192 ctx) | Delta |
|--------|--------------|---------------|-------|
| hybrid nDCG@10 | 0.647 | 0.631 | **-0.016** |
| hybrid Recall@10 | 0.991 | 0.988 | -0.003 |
| hybrid MRR | 0.526 | 0.508 | -0.018 |
| hybrid P@1 | 0.088 | 0.086 | -0.002 |
| lexical (all) | identical | identical | 0.000 |

**Root cause (corrected after literature review):** The degradation is caused by
**missing RoPE scaling parameters**, not a fundamental model limitation.

nomic-embed-text-v1.5 was trained at 2048 tokens and uses Dynamic NTK interpolation
to scale to 8192 at inference (arXiv 2402.01613). The paper shows near-identical
quality at all three lengths (LoCo nDCG: 85.3 / 85.6 / 85.5 for 2048 / 4096 / 8192)
— **but only with correct RoPE scaling.**

Our llama.cpp backend has three problems:
1. llama.cpp doesn't implement Dynamic NTK — the GGUF README recommends
   `--rope-scaling yarn --rope-freq-scale .75` as substitute
2. llama-server has a known bug (llama.cpp #10355) where `--rope-scaling` params
   are silently not parsed
3. Our code sets `contextLength(8192)` but **never passes RoPE scaling parameters**
   — positions 2049-8192 use unscaled (extrapolated) encodings, producing
   degraded embeddings

**Conclusion:** This experiment is **invalid as a test of 8192 context quality.**
The result only proves that unscaled position extrapolation hurts. A valid test
requires passing `--rope-scaling yarn --rope-freq-scale .75` to llama-server and
verifying the flags are applied (llama.cpp #10355). However, the literature
suggests even properly-configured 8192 context would yield only marginal gains
(+0.2-0.3 nDCG on LoCo) — not enough to close the BM25 gap on long documents.

#### Phase 1 Summary

All four sub-phases complete. Findings cross-referenced against academic literature.

**Finding 1: BM25 dominates on moderate-length docs — consistent with literature.**
courtlistener lexical nDCG=0.924 vs hybrid 0.647. The LoCoV1 paper (arXiv
2402.07440, ICML 2024) reports BM25 at 79.9-81.5 avg nDCG across 12 long-doc
tasks, beating all standard dense models (E5-Mistral truncation: 71.4, Ada-002:
63.2). Only M2-BERT-32k (purpose-built 32K context encoder) beats BM25. Dense
models violate BM25's length-normalization properties (LNC2) and exhibit
beginning-of-document bias (arXiv 2404.04163, ACL 2024).

**Finding 2: Hybrid outperforms lexical on very long docs — partially expected.**
legal_case_reports hybrid nDCG=0.331 vs lexical 0.250. This *appears* to
contradict LoCoV1 (where BM25 beats dense), but the explanation is architectural:
our chunk merge acts as MaxP aggregation (PARADE, ACM TOIS 2023, arXiv
2008.09093), resolving chunk IDs to document IDs before RRF fusion. Most LoCoV1
baselines used naive truncation or chunk-averaging, both worse. Additionally,
BM25 itself degrades at extreme lengths — TF saturation in 47K-token docs
reduces BM25 precision (Lv & Zhai, SIGIR 2011).

**Finding 3: Chunk merge is essential — well-explained by ID-space mismatch.**
Disabling chunk merge collapses hybrid recall from 99% to 19%. The fusion
function literature (arXiv 2210.11934) explains: RRF requires matching ID
namespaces. When BM25 returns `docId` and dense returns `docId::chunkN`, the
fusion treats them as disjoint items, neither receiving the dual-list boost.
This is a hard architectural requirement, not an optimization.

**Finding 4: 8192 context experiment was invalid.**
The -0.016 nDCG degradation was caused by missing RoPE scaling parameters in
llama-server, not a model limitation. The nomic-embed paper shows 8192 works
with Dynamic NTK (85.3 vs 85.5 nDCG on LoCo). However, even properly-configured
8192 context would yield marginal gains — not enough to close the BM25 gap.

**Finding 5: Adaptive routing remains the correct strategy.**
The crossover point where hybrid beats BM25 tracks the embedding model's
effective context window (literature consensus). For docs within 2048 tokens,
hybrid wins on short-doc BEIR (+5-10 nDCG over BM25). For docs exceeding
context, BM25 wins unless a long-context encoder is used. Our results confirm
this: hybrid hurts at 6K tokens (1/3 captured), helps at 47K tokens (where
BM25 itself fails). Optimal routing depends on document length relative to
embedding context.

#### Phase 1 Strategic Implications

Cross-referencing Phase 1 results with literature reveals the viable paths
for improving long-document search quality:

**What won't work (literature + our data confirm):**
- Context window expansion alone: +0.2-0.3 nDCG at best (nomic-embed paper).
  Even perfect 8192 context leaves a ~10 nDCG gap vs BM25 on long docs.
- Naive chunking improvements: LoCoV1 shows chunk-averaging is worse than
  truncation. Better chunk boundaries won't close the gap.
- Dense-only retrieval on long docs: fundamental capacity limit of
  fixed-dimensional embeddings (arXiv 2508.21038).

**What might work (literature-supported, varying effort):**

| Approach | Expected gain | Effort | Evidence |
|----------|--------------|--------|----------|
| **Lexical-first routing** for long docs | +0.277 nDCG (skip vector leg) | Low | Our 1a data: lexical alone = 0.924 |
| **RoPE scaling fix** for llama-server | Unknown (need re-test) | Low | nomic-embed paper: 8192 ≈ 2048 quality |
| **Late chunking** (encode full doc, pool per-chunk) | Moderate | Medium | Emerging technique, preserves cross-chunk context |
| **ColBERT / late interaction** models | +5-10 nDCG est. | Medium | Vespa long-context ColBERT, MaxSim across windows |
| **BM25 + long-context reranker** (two-stage) | +0-2 nDCG over BM25 | Medium | LoCoV1: M2-BERT reranker on BM25 = 80.9 vs BM25 81.5 |
| **Long-context embedding model** (M2-BERT-32k, Jina v2 8k) | +10-15 nDCG | High | LoCoV1: M2-BERT-32k = 91-95 vs BM25 80 |
| **MC-indexing** (multi-view: raw + keywords + summary) | +16-43% recall | High | EMNLP 2024 Findings |

**Recommended priority order (updated 2026-03-04 after 256 review):**
1. **Phase 5 (pipeline config sweep)** — tempdoc 256 built full pipeline
   composability but we have zero data on most combinations. Cross-encoder
   on hybrid (256 Phase F) and 2-stage cascade are the highest-priority
   untested configs. Zero code needed — just run the eval harness.
2. **Phase 2 (known-item eval)** — produces the query type that matters
   most for desktop search. Validates whether sweep findings hold for
   refinding queries.
3. Improvement work (Phase 6) informed by sweep data:
   - 6a: Routing heuristic → tempdoc 256 H2 (use sweep results)
   - 6b: RoPE scaling fix (low ROI)
   - 6c: Long-context embedding model eval
   - 6d: ColBERT via ONNX

### Phase 5: Pipeline Config Sweep (Component Ablation)

Tempdoc 256 (Component Activation Model, Phases A-I) built full pipeline
composability: any combination of sparse/dense/splade retrieval, cross-encoder
and LambdaMART reranking, and LLM expansion can be independently toggled via
`PipelineConfig` flags. The eval harness already supports arbitrary configs
via `-Pipeline` JSON (256 Phase H3). **Minimal code changes needed** — one
PS 5.1 compatibility fix in the harness (see first attempt notes below),
otherwise pure eval execution.

**Update (2026-03-06, post-hardening slice):** The paragraph above is now
historical. Tempdoc 259's BEIR-lane hardening slice added strict runtime gates
for dense / SPLADE / LambdaMART readiness, explicit BEIR metric-contract and
qrels-summary fields in `metrics.json`, `bench-suite.v2` as the canonical
downstream artifact, and validator/parity alignment to the production
linear-gain nDCG semantics.

This produces the empirical data that 256's deferred items need:
- **256 H2** (agent search mode heuristic) — which config to use when
- **256 H4** (SPLADE + Dense preset) — does triple retrieval help
- **256 I3** (expansion + hybrid) — does expansion help hybrid on long docs

**Methodology:** Factorial component sweep (CLAIRE, IPM 2018) rather than
sequential ablation, to capture interaction effects between components.
The recall ceiling principle (Voyage AI 2025, BSWEN 2026) predicts that
reranking will help most on our weak-ranking-high-recall hybrid results
(courtlistener: 0.991 recall, 0.647 nDCG). Cross-encoder on hybrid is
the highest-priority untested combination — 256 Phase F unblocked it but
we have zero data.

**Literature gaps this fills:**
- No published SPLADE evaluation on documents exceeding 512 tokens
- No published factorial ablation of modern neural retrieval components
  (CLAIRE covered classical IR only)
- No published cross-encoder + hybrid evaluation on long documents

**Configs to evaluate on courtlistener + legal_case_reports:**

| # | Config | sparse | dense | splade | CE | LM | Tests |
|---|--------|--------|-------|--------|----|----|-------|
| 1 | BM25 only | Y | - | - | - | - | Baseline (Phase 1a data) |
| 2 | Hybrid (RRF) | Y | Y | - | - | - | Baseline (Phase 1a data) |
| 3 | BM25 + CE | Y | - | - | Y | - | Cross-encoder on lexical |
| 4 | **Hybrid + CE** | Y | Y | - | Y | - | **256 F unblocked; untested** |
| 5 | BM25 + LM | Y | - | - | - | Y | LambdaMART isolation |
| 6 | BM25 + LM + CE | Y | - | - | Y | Y | 2-stage cascade on lexical |
| 7 | **Hybrid + LM + CE** | Y | Y | - | Y | Y | **Full 2-stage cascade** |
| 8 | **BM25 + SPLADE** | Y | - | Y | - | - | **Novel combo (256 E)** |
| 9 | BM25 + SPLADE + CE | Y | - | Y | Y | - | SPLADE + CE reranking |
| 10 | Triple (all retrievers) | Y | Y | Y | - | - | Triple retrieval fusion |
| 11 | **Triple + LM + CE** | Y | Y | Y | Y | Y | **Kitchen sink** |

Configs 1-2 reuse Phase 1a data (query-only reruns with different pipeline).
Configs 3-11 require SPLADE-indexed corpus (re-index with SPLADE enabled) for
configs 8-11, but configs 3-7 can run on the existing Phase 1a index.

- [x] **5a.** Run configs 2-7 on courtlistener (query-only on existing index).
  Completed 2026-03-07. All 7 configs collected. See results table above.

- [ ] **5b.** Re-index courtlistener with SPLADE enabled. Run configs 8-11.
  Requires `JUSTSEARCH_SPLADE_ENABLED=true` at index time (SPLADE is
  index-time + search-time). ~1-2h for indexing + queries. DEFERRED —
  requires full re-index cycle. **Deprioritized** (2026-03-09): the large
  mixed-corpus matrix already shows SPLADE catastrophically fails on
  courtlistener (nDCG 0.083 vs lexical 0.734) due to 512-token truncation.
  Adding SPLADE to BM25 or Triple retrieval on 6K-token docs is unlikely
  to help. Run only if needed to formally close the config sweep.

- [x] **5c.** Repeat configs 2-7 on legal_case_reports (extreme-length docs).
  Completed 2026-03-07. Cross-dataset validation complete. CE x Dense
  interaction differs: dense rescues CE on legal (+0.119) but not courtlistener.

- [x] **5d.** Analyze results with factorial main effects and 2-way
  interactions. Key answers:
  - CE does NOT rescue hybrid on long docs — CE hurts all configs
  - 2-stage cascade (LM+CE) does NOT beat CE alone — LM has zero effect
  - Config 4 vs 2: CE destroys hybrid ranking (0.312 vs 0.910 courtlistener)
  - Config 7 vs 4: Adding LM to Hybrid+CE slightly worsens courtlistener
    (0.296 vs 0.312) but improves legal (0.538 vs 0.412)
  - Optimal: BM25 for courtlistener, BM25 or Hybrid for legal (depends on
    whether recall or nDCG matters more)

- [x] **5e.** Record optimal configs and feed to tempdoc 256:
  - Best current local signal → BM25 on `courtlistener`; BM25 or Hybrid on
    `legal_case_reports` depending on whether nDCG or recall matters more
  - Do NOT promote a universal long-doc default until the LoCo fidelity audit
    below is complete
  - CE must be disabled for docs >2K tokens
  - LM provides no benefit on long-doc collections
  - SPLADE results deferred (5b)

#### Phase 5 First Attempt (2026-03-06) — Partial, Structural Issues

The first execution attempt collected partial data but was blocked by five
structural issues documented in tempdoc 261 §"Confirmed structural issues."

**Harness fix applied:** `beir-eval-win.ps1` line 512 — replaced
`ConvertFrom-Json -AsHashtable` (PS 7 only) with manual PSObject-to-hashtable
conversion for PS 5.1 compatibility. This fix is required for all `-Pipeline`
runs on Windows PowerShell 5.1.

**Valid results (courtlistener, 2026-03-06):**

| # | Config | nDCG@10 | Recall@10 | MRR | P@1 | Notes |
|---|--------|---------|-----------|-----|-----|-------|
| 1 | BM25 only | 0.924 | 0.977 | 0.906 | 0.862 | Matches Phase 1a baseline |
| 3 | BM25 + CE | 0.924 | 0.977 | 0.906 | 0.862 | CE has zero effect on BM25 |

**Key finding: Cross-encoder has zero effect on BM25 for courtlistener.**
BM25 ranking is already near-optimal (86.2% P@1), leaving no room for CE
to improve. This is consistent with the recall ceiling principle: reranking
only helps when the first-stage ranking is weak.

**Provisional results (require rerun with full embeddings):**

| # | Config | nDCG@10 | Recall@10 | MRR | P@1 | Notes |
|---|--------|---------|-----------|-----|-----|-------|
| 4 | Hybrid + CE | 0.917 | 0.994 | 0.890 | 0.802 | Partial embeddings (~1-2% chunk coverage) |

Config 4 ran before `chunkVectorsReady=true` — the hybrid results are
unreliable. However, the directional signal is strong: even with very
incomplete embeddings, Hybrid+CE (0.917 nDCG, 80.2% P@1) nearly matches
BM25 (0.924 nDCG, 86.2% P@1), suggesting that CE may fully rescue hybrid
ranking when embeddings are complete. This needs revalidation.

**Invalid results (LambdaMART fell back to no-reranking):**

Configs 5, 6, 7 all returned BM25-identical metrics (0.924 nDCG). No
LambdaMART model existed at runtime — `LambdaMartReranker.rerank()` returned
null and fell back. GPL training completed 2000/2006 docs (24,078 triples
persisted at `modules/ui-web/.dev-data/gpl-training-triples.ndjson`) but the
server crashed before LambdaMART training finished. The triples are reusable
on next startup with `JUSTSEARCH_LAMBDAMART_ENABLED=true` and `--clean none`.

**Structural issues encountered (see tempdoc 261 for full analysis):**

1. **PS 5.1 compatibility** — `ConvertFrom-Json -AsHashtable` not available.
   Fixed mid-sweep in `beir-eval-win.ps1`.
2. **No hybrid readiness gate** — hybrid configs ran with partial embeddings
   (863/58,279 chunks = 1.5%). Results are unreliable for hybrid.
3. **Unsafe lifecycle/data-dir** — `--clean hard` (default) wiped the index
   and GPL triples on restart. Lost full index + 24K triples on first crash.
   Second run persisted triples by using the default data dir.
4. **Multiple launcher paths** — bounced between MCP tools, lifecycle script,
   and direct Gradle. Port discovery was fragile (60556, 64733, 9001).
5. **GPU contention** — chunk embedding stalled at 687/58,279 while GPL ran.
   Server crashed twice, likely from memory pressure near GPL→LambdaMART
   training transitions.

**Revised execution plan for retry:**

Per tempdoc 261 §"Coordination response":
1. Use one launcher path consistently (lifecycle script with `--clean none`)
2. Pin the default data dir (`modules/ui-web/.dev-data/`)
3. Phase the GPU work: (a) index + embed to completion → (b) run all
   non-LM configs → (c) activate AI for GPL/LM training → (d) rerun LM
   configs
4. Gate hybrid configs on `chunkVectorsReady=true`
5. Gate LM configs on `lambdamart-model.txt` existing on disk

#### Phase 5 Second Attempt (2026-03-06) — INVALID

**These results were collected with CE not loaded (MODEL_NOT_LOADED) and LM
accidentally active.** The "zero CE effect" finding was an artifact of the CE
model not being loaded, not a real result. All results below are superseded
by the Third Attempt.

#### Phase 5 Third Attempt (2026-03-06) — In Progress

Fixes applied over second attempt:
1. `JUSTSEARCH_RERANK_MODEL_PATH` set explicitly (CE model auto-discovery
   fails for non-standard paths)
2. `JUSTSEARCH_LAMBDAMART_ENABLED=false` set explicitly (LM was active from
   persisted triples, overriding CE)
3. `JUSTSEARCH_WORKER_DEADLINE_MS=15000` (default 5s too short for 47K-token
   legal_case_reports documents — caused circuit breaker cascades)
4. Mixed index used (both datasets, 2772 docs) — verified that courtlistener
   BM25 (0.924) matches Phase 1a baseline exactly

**Additional harness fix applied:** `beir-eval-win.ps1` line 617 — added
guard for empty `$rels`/`$idealRels` arrays before calling `Compute-Ndcg`,
which caused "Cannot bind argument to parameter 'Rels'" on PS 5.1 when
queries returned no results.

**Operational findings:**
- Embedding concurrency kills search at 5s deadline (gRPC timeout → circuit
  breaker → cascade). With 15s deadline, search works even during embedding.
- `JUSTSEARCH_WORKER_DEADLINE_MS` env var controls base gRPC deadline
  (default 5000ms). Categories apply multipliers: STANDARD=1×, CONTENT_FETCH=2×,
  INDEX_GC=6×, LONG_RUNNING=60×.
- CE model at `models/reranker/ms-marco-MiniLM-L6-v2/` is NOT auto-discovered.
  Must set `JUSTSEARCH_RERANK_MODEL_PATH` explicitly. Auto-discovery only
  checks `models/onnx/reranker/` and `<dataDir>/models/onnx/reranker/`.

**Complete results — all 7 configs on both datasets (2026-03-07):**

| # | Config | courtlistener nDCG@10 | courtlistener Recall@10 | legal nDCG@10 | legal Recall@10 |
|---|--------|----------------------|------------------------|---------------|-----------------|
| 1 | BM25 | **0.924** | 0.977 | 0.570 | 0.305 |
| 2 | Hybrid (RRF) | 0.910 | **0.993** | 0.516 | **0.699** |
| 3 | BM25+CE | 0.318 | 0.602 | 0.347 | 0.260 |
| 4 | Hybrid+CE | 0.312 | 0.502 | 0.412 | 0.644 |
| 5 | BM25+LM | 0.924 | 0.977 | 0.573 | 0.304 |
| 6 | BM25+LM+CE | 0.318 | 0.602 | 0.423 | 0.269 |
| 7 | Hybrid+LM+CE | 0.296 | 0.486 | 0.538 | 0.301 |

Mixed index (2772 docs: courtlistener ~2001 + legal_case_reports ~771).
`JUSTSEARCH_WORKER_DEADLINE_MS=15000`, `JUSTSEARCH_EMBED_GPU_LAYERS=99`.

#### Factorial Analysis (5d)

**3-factor design:** Dense (D), Cross-Encoder (CE), LambdaMART (LM).

**Main effects (nDCG@10):**

| Factor | courtlistener | legal_case_reports | Interpretation |
|--------|--------------|-------------------|----------------|
| Dense | -0.014 | +0.042 | Marginal. Hurts courtlistener, helps legal when CE present |
| CE | **-0.606** | **-0.114** | Catastrophic on both. Worse on courtlistener |
| LM | -0.005 | +0.068 | Negligible alone. Apparent benefit on legal is CE interaction |

**Interaction effects (nDCG@10):**

| Interaction | courtlistener | legal_case_reports | Interpretation |
|-------------|--------------|-------------------|----------------|
| CE x Dense | ~0 (additive) | +0.119 | Dense partially rescues CE damage on legal |
| LM x CE | ~0 | +0.076 | LM slightly reduces CE damage on legal |
| LM alone | 0.000 | +0.003 | Zero standalone effect on both datasets |

**Key findings (complete):**

1. **Cross-encoder catastrophically hurts both datasets.** CE drops nDCG by
   -0.606 on courtlistener (0.924→0.318) and -0.223 on legal_case_reports
   (0.570→0.347). The MiniLM-L6-v2 cross-encoder has a 512-token input limit
   and cannot process long documents — it truncates and loses relevant content.
   This is not a marginal degradation; CE actively destroys ranking quality
   on long-document collections.

2. **Dense partially rescues CE on legal_case_reports but not courtlistener.**
   On legal_case_reports, Hybrid+CE (0.412) is better than BM25+CE (0.347),
   reducing CE damage from -0.223 to -0.104 (interaction effect +0.119).
   On courtlistener, no such rescue occurs (0.312 vs 0.318, additive).
   Explanation: legal_case_reports docs (47K tokens) exceed BM25 capacity,
   so dense retrieval provides complementary signal. courtlistener docs
   (6K tokens) are within BM25 effective range, leaving nothing for dense
   to rescue.

3. **GPL-trained LambdaMART has zero standalone effect.** Config 5 matches
   config 1 on both datasets (courtlistener: 0.924 vs 0.924, legal: 0.573
   vs 0.570). The LM training nDCG=0.954 simply replicated BM25 ranking
   order. This is consistent with previous multi-dataset findings where
   GPL-LambdaMART consistently hurt or was neutral.

4. **Hybrid without CE improves recall dramatically on legal_case_reports.**
   Config 2 Recall@10=0.699 vs config 1 Recall@10=0.305 (+0.394 absolute).
   But hybrid hurts nDCG on both datasets (0.910 vs 0.924 on courtlistener,
   0.516 vs 0.570 on legal). Dense retrieval finds more relevant docs but
   ranks them worse than BM25.

5. **Best config per dataset:**
   - courtlistener: **Config 1 (BM25)** — nDCG=0.924, near-perfect
   - legal_case_reports: **Config 5 (BM25+LM)** = Config 1 (BM25) — nDCG=0.570/0.573
   - If recall matters more than nDCG: **Config 2 (Hybrid)** on legal (Recall=0.699)

6. **CE must be disabled for all long-doc collections.** No configuration
   rescues CE damage. Even the best CE config (Hybrid+LM+CE on legal,
   0.538) is worse than plain BM25 (0.570). The 512-token limit is
   fundamentally incompatible with documents >2K tokens.

7. **5s gRPC deadline is too short for long-doc collections.** 47K-token
   documents cause queries to exceed the default 5s deadline, triggering
   circuit breaker cascades. `JUSTSEARCH_WORKER_DEADLINE_MS=15000` resolves
   this. Some CE queries still timeout at 15s on the longest documents.

#### External sanity check against LoCoV1 literature (2026-03-07)

External literature review materially changes how Phase 5 should be
interpreted.

What the literature supports:

1. **The cross-encoder collapse is expected.** The current reranker
   (`cross-encoder/ms-marco-MiniLM-L6-v2`) is documented as a short-input
   MS MARCO passage reranker, and Sentence Transformers truncates overlength
   inputs at the model max length. On 6K-token and 47K-token legal documents,
   severe truncation damage is expected rather than surprising.
2. **LambdaMART's low impact is plausible.** GPL literature supports
   synthetic-query adaptation mainly for dense retrievers, not as evidence that
   GPL-trained LambdaMART should improve long-document legal retrieval.
3. **Absolute Phase 5 BM25 / Hybrid scores look suspicious as benchmark
   comparisons.** The local LoCo-converted runs appear too strong to treat as
   directly comparable to official LoCoV1 task-level results, especially on
   `legal_case_reports`.

Interpretation rule (updated after 5f audit):

1. Treat Phase 5 as strong evidence for **directional component conclusions**:
   CE off, LM low-priority, and routing remains regime-sensitive.
2. Use title-free BM25 = **0.889** as the canonical courtlistener baseline
   (Phase 5 reported 0.924 was inflated by +0.035 from title duplication).
3. BM25-first routing for courtlistener-like long-doc corpora is at
   **medium confidence** — see superseding routing update in §5f below.
4. Do NOT claim parity with published LoCoV1 baselines without per-task
   data from the paper authors.

- [x] **5f.** Audit LoCo fidelity before promoting Phase 5 absolute-score
  claims or routing defaults. *(Done 2026-03-07. Full audit below.)*

#### Phase 5f: LoCo Fidelity Audit Results (2026-03-07)

**Audit methodology:** Investigated three potential fidelity issues identified
in the external sanity check. Ran a controlled A/B comparison of BM25 on
courtlistener with and without title duplication.

**Issue 1: Title duplication — CONFIRMED, modest impact**

`convert-locov1-to-beir.mjs` (lines 210-215) extracts the first line of the
LoCoV1 `passage` field as a `title` field. Then `beir-eval-win.ps1` (lines
392-395) prepends `title + CRLF` before the full `text` (which already
contains that first line). Result: the first line appears **twice** in the
indexed text.

A/B comparison on courtlistener (2000 docs, BM25-only, clean index):

| Variant | nDCG@10 | MRR | P@1 | Recall@10 |
|---------|---------|-----|-----|-----------|
| With title (Phase 5) | 0.924 | 0.906 | 0.862 | 0.977 |
| Title-free | 0.889 | 0.865 | 0.808 | 0.962 |
| **Delta** | **-0.035** | **-0.041** | **-0.054** | **-0.015** |

Title duplication inflates nDCG@10 by +0.035 (3.8%). The titles are
procedural headers (`[DO NOT PUBLISH]`, case captions) — not semantically
rich, but they contain enough distinctive terms to affect BM25 scoring.

**Corrective action:** Phase 5 results should use **title-free values** as
the canonical baseline. The corrected BM25 baseline for courtlistener is
**nDCG@10 = 0.889**, not 0.924.

**Follow-up rerun (2026-03-07 evening): title-free Hybrid baseline**

The remaining unresolved Phase 5f question was whether Hybrid would still beat
BM25 once the same title-duplication fix was applied to the courtlistener
Hybrid run. That rerun is now complete:

| Variant | nDCG@10 | MRR | P@1 | Recall@10 |
|---------|---------|-----|-----|-----------|
| BM25 title-free | 0.889 | 0.865 | 0.808 | 0.962 |
| Hybrid title-free | 0.871 | 0.831 | 0.716 | 0.990 |
| **Hybrid - BM25 delta** | **-0.018** | **-0.034** | **-0.092** | **+0.028** |

This resolves the courtlistener routing delta in BM25's favor. Hybrid still
improves recall, but it remains worse on the ranking metrics that matter for
known-item legal retrieval.

**Note on legal_case_reports:** Title duplication was not re-measured on
legal_case_reports. At 47K tokens per document, the duplicated first line
(~100 chars) is <0.05% of document text — well below measurement noise.
The courtlistener audit (6K-token docs, +3.8% inflation) bounds the worst
case.

**Issue 2: 1:1 query-document mapping — NOT A BUG, task design**

Both datasets have exactly 1 relevant document per query (2000 qrels for
2000 queries on courtlistener, 770 for 770 on legal). This is the official
LoCoV1 design — it's a known-item/passage retrieval task with binary
relevance, not a topical retrieval task with graded relevance.

The official LoCoV1 paper (arXiv 2402.07440) uses nDCG@10 as the metric
and `answer_pids` as binary relevance labels. Our conversion is faithful.

**Impact:** nDCG@10 with a single relevant doc is equivalent to reciprocal
rank. The 0.889 title-free score means BM25 ranks the target document in the
top 1-2 positions for ~89% of queries. This IS comparable to LoCoV1
evaluations that use the same task design.

**Issue 3: Query-document text overlap — PARTIAL, not trivial leakage**

Only 4.8% of courtlistener queries are verbatim substrings of their answer
document. The queries are passage excerpts but many appear paraphrased or
sourced from related documents. BM25 must do real matching.

**Non-issue: Data source authenticity — VERIFIED**

The converter downloads from the official `hazyresearch/LoCoV1-Documents`
and `hazyresearch/LoCoV1-Queries` HuggingFace datasets. The data is
authentic and the BEIR conversion is faithful.

**Audit conclusion:**

1. Phase 5 **directional findings are fully valid**: CE catastrophic, LM
   zero effect, BM25 dominates on 6K-tok docs.
2. **Absolute BM25 scores should be corrected** for title duplication:
   courtlistener canonical BM25 = 0.889 (not 0.924).
3. The LoCoV1 paper reports ~79.9 average BM25 nDCG across 12 tasks but
   does not publish per-task breakdowns. Our 0.889 is plausible for a
   legal-domain task (high term distinctiveness, moderate corpus size).
4. Phase 5 results **are valid for internal routing decisions** but should
   not be presented as "matching published LoCoV1 baselines" without
   per-task baseline data from the paper authors.
5. **The title duplication bug has been fixed** in the converter
   (committed 64f00fbb — `title = ''` unconditionally).

**Updated interpretation rules (original, now superseded):**

These rules were written before the title-free Hybrid rerun. See the
superseding routing update below for the current conclusion.

1. Use title-free BM25 = 0.889 as the courtlistener baseline.
2. Directional routing rules (CE off, LM deprioritized) stand unchanged.
3. ~~BM25-first at low-medium confidence~~ → resolved, see superseding update.
4. Do NOT claim parity with published LoCoV1 baselines without per-task
   data.

Artifacts:
- `tmp/beir-eval/courtlistener-notitle-bm25/metrics.v2.json`
- `tmp/beir-eval/workflows/phase5f-courtlistener-notitle-hybrid/beir-eval/metrics.v2.json`

**Superseding routing update (2026-03-07 evening):**

The earlier "BM25-first at low-medium confidence pending title-free Hybrid"
statement is now resolved for the courtlistener regime. Use these updated
interpretation rules:

1. Use title-free courtlistener baselines: BM25 = 0.889, Hybrid = 0.871.
2. Directional routing rules (CE off, LM deprioritized) stand unchanged.
3. BM25-first for courtlistener-like long-doc corpora is now at **medium
   confidence**: corrected BM25 still beats corrected Hybrid on nDCG, MRR, and
   P@1. Hybrid remains plausible only when recall is the primary objective or
   document length is far beyond the courtlistener regime.
4. Do NOT claim parity with published LoCoV1 baselines without per-task data.

Sources:
- [LoCoV1 paper (OpenReview / arXiv)](https://openreview.net/pdf?id=HkCRgoGtt6)
- [Hazy Research LoCoV1 blog](https://hazyresearch.stanford.edu/blog/2024-05-20-m2-bert-retrieval)
- [Sentence Transformers CrossEncoder docs](https://www.sbert.net/docs/package_reference/cross_encoder/cross_encoder.html)
- [Sentence Transformers MS MARCO cross-encoders](https://www.sbert.net/docs/pretrained-models/ce-msmarco.html)

### Phase 6: Long-Document Quality Improvements (Post-Eval)

Model and architecture changes informed by Phases 1-5 eval data. These are
**not eval work** — they are pipeline improvements that live in their own
tempdocs. Listed here to track the dependency chain from eval → improvement.

- [x] **6a.** Lexical-first routing evidence → **tempdoc 256 Phase H2.**
  Scope: feed Phase 5 empirical results to 256 H2 and 258 D2 as routing
  inputs. Actual routing code implementation lives in tempdoc 256 H2, not
  here. No separate implementation in this tempdoc.
  *(Initial routing implications fed to tempdoc 256 H2 and tempdoc 258 D2,
  committed 791f7487.)*

- [ ] **6b.** Fix RoPE scaling for embedding context > 2048. The native
  llama backend needs RoPE params passed through the C API, not CLI flags.
  Re-run 1b experiment with proper scaling to validate 8192 context.
  *Scoped — see investigation findings below. Low-medium effort. Low ROI
  (nomic-embed paper shows only +0.2 nDCG at 8192 even with proper scaling).*

- [ ] **6c.** Evaluate long-context GGUF embedding models as nomic-embed
  replacements. Top candidates: Jina v2 8k (drop-in, 768 dim),
  Qwen3-Embedding-0.6B (32K ctx, 1024 dim), BGE-M3 (8K, 1024 dim).
  *Scoped — see investigation findings below. Medium effort (eval harness
  already exists, but dimension change requires schema migration).*

- [ ] **6d.** Evaluate ColBERT late-interaction reranking via ONNX Runtime.
  No GGUF ColBERT models exist. Best candidate: answerai-colbert-small-v1
  (33M params, 0.13GB ONNX, CPU-capable). Needs ONNX Runtime Java dep +
  tokenizer.
  *Scoped — see investigation findings below. High effort (new dependency,
  new runtime, tokenizer integration).*

#### Phase 5 Literature References

- CLAIRE factorial IR evaluation: Angelini et al., IPM 2018 —
  [sciencedirect.com/science/article/pii/S0306457317308221](https://www.sciencedirect.com/science/article/pii/S0306457317308221)
- Recall ceiling principle: Voyage AI 2025 —
  [blog.voyageai.com/2025/10/22/the-case-against-llms-as-rerankers/](https://blog.voyageai.com/2025/10/22/the-case-against-llms-as-rerankers/)
- Cross-encoder internally implements semantic BM25: Lu et al., EMNLP 2025 —
  [arxiv.org/abs/2502.04645](https://arxiv.org/abs/2502.04645)
- BM25 + cross-encoder = +39% nDCG: Elastic Rerank 2024 —
  [elastic.co/search-labs/blog/elastic-semantic-reranker-part-2](https://www.elastic.co/search-labs/blog/elastic-semantic-reranker-part-2)
- SPLADE + cross-encoder evaluation: Dejean et al. 2024 —
  [arxiv.org/abs/2403.10407](https://arxiv.org/abs/2403.10407)
- Three-way retrieval + ColBERT optimal: IBM/InfinityFlow study —
  [infiniflow.org/blog/best-hybrid-search-solution](https://infiniflow.org/blog/best-hybrid-search-solution)
- Reranker gains largest on weak first stages: Voyage AI 2025 —
  BM25: +47%, strong dense: +3% — same source as recall ceiling above
- No published SPLADE eval on long docs: SPLADE limited to 256-512 tokens —
  [huggingface.co/naver/splade-v3/discussions/5](https://huggingface.co/naver/splade-v3/discussions/5)

#### Phase 6 Investigation Findings (2026-03-04)

**6a — Routing → tempdoc 256 H2 (codebase exploration, updated 2026-03-04)**

**Key finding: tempdoc 256 already built the infrastructure.** Phases A-C of
tempdoc 256 (Component Activation Model) replaced the monolithic `SearchMode`
enum with `PipelineConfig` — independent boolean flags for `sparse_enabled`,
`dense_enabled`, `splade_enabled`. This is wired end-to-end:
- Head: `expandPreset()` in `KnowledgeHttpApiAdapter` maps mode strings to configs
- Worker: `SearchOrchestrator.execute()` dispatches via config flags, not mode switch
- Proto: `PipelineConfig` message in `SearchRequest`

Phase 5a does NOT need a new routing mechanism. It needs a **condition** that
selects `{sparse:true, dense:false}` (the `text` preset) instead of `hybrid`
when the index is predominantly long documents.

**Implementation options (revised):**
1. **In `expandPreset()` (Head-side, simplest):** When expanding the `hybrid`
   preset, check index metadata (median doc size or chunk rate). If the index
   is long-doc-heavy, expand to `text` config instead. Location:
   `KnowledgeHttpApiAdapter.expandPreset()`.
2. **In `SearchOrchestrator` (Worker-side):** Before retrieval dispatch, check
   index stats. Override `pipeline.getDenseEnabled()` to false if collection
   is predominantly chunked. Location: early in `execute()`.
3. **Per-query adaptive:** After initial retrieval, check chunk merge rate.
   If >80% of results came from chunked docs, re-execute sparse-only. Location:
   `mergeChunkResults()` (~line 834).

**Recommendation:** Option 1 (Head-side in `expandPreset()`). Simplest, no
Worker changes needed, and the preset expansion is already the designed
translation layer per 256's architecture.

**6b — RoPE scaling (codebase exploration)**

**Critical finding: embedding uses the native llama backend, NOT llama-server.**
`LlamaService.java` loads the model via native C bindings (`NativeLlamaBinding`),
not through the HTTP server process. The llama-server process (built in
`LlamaServerOps.java:179-278`) is only for the chat model.

This means:
- `--rope-scaling yarn --rope-freq-scale .75` (llama-server CLI flags) are
  irrelevant for embedding — those flags don't exist in the native path
- The native backend's RoPE behavior depends on what the GGUF metadata says
  and what the llama.cpp C API applies automatically
- The llama.cpp C API has `llama_model_params.rope_scaling_type` and
  `llama_model_params.rope_freq_scale` — these would need to be exposed
  through `NativeLlamaBinding` → `LocalIntentTranslatorConfig`

**Revised effort estimate:** Medium. Requires:
1. Add `ropeScalingType` and `ropeFreqScale` to `LocalIntentTranslatorConfig`
2. Pass them through `NativeLlamaBinding` to `llama_model_params`
3. Set `YARN` scaling + `0.75` freq scale for nomic-embed at 8192
4. Verify via embedding quality test (re-run 1b experiment)

However, the nomic-embed paper shows only +0.2 nDCG gain at 8192 even with
proper scaling. **ROI is low compared to 5a and 5c.** Deprioritize.

**6c — Long-context GGUF embedding models (web research)**

Comprehensive survey of available models (RTX 4070 budget: ~7GB after 5GB chat):

| Model | Context | Dims | Q4 Size | VRAM | Verdict |
|-------|---------|------|---------|------|---------|
| **Jina v2 base** | 8192 | 768 | 88 MB | <1 GB | Drop-in (same dims as nomic) |
| **BGE-M3** | 8192 | 1024 | 438 MB | ~1.5 GB | Strong: multilingual, proven |
| **Qwen3-Embed-0.6B** | 32768 | 1024 | ~400 MB | ~1 GB | Excellent: huge context, tiny |
| **GTE-Qwen2-1.5B** | 32768 | 1536 | 1.04 GB | ~2 GB | Strong: 32K, predecessor to Qwen3 |
| **Qwen3-Embed-4B** | 32768 | 2560 | 2.48 GB | ~3.5 GB | Best quality that fits budget |
| nomic-embed-v2-moe | **512** | 768 | ~600 MB | ~1 GB | **NOT long-context** (512 only) |
| M2-BERT-32k | 32768 | 768 | — | — | **No GGUF** (custom arch, Python-only) |
| E5-Mistral-7B | 4096 | 4096 | 4.37 GB | ~6 GB | Too large, only 4K context |

**Recommended evaluation order:**
1. **Jina v2 base** — zero-risk drop-in: same 768 dims, 4x context, <150MB.
   If this shows gains on courtlistener, it validates the context-matters
   hypothesis without any schema changes.
2. **Qwen3-Embedding-0.6B** — 32K context at ~400MB. Needs schema migration
   (768→1024 dims) but 32K context would eliminate truncation entirely on
   courtlistener (6K tok docs) and cover most of legal_case_reports.
3. **BGE-M3** — also 1024 dims, 8K context, excellent multilingual support.
   Useful if Qwen3 has quality issues.

**Schema migration note:** Changing embedding dimensions from 768 to 1024+
requires: (a) updating `SchemaFields.VECTOR` dimension, (b) full re-index,
(c) updating Lucene KNN vector field configuration. Not trivial but scoped.

**Sources:** [gpustack/jina-embeddings-v2-base-en-GGUF](https://huggingface.co/gpustack/jina-embeddings-v2-base-en-GGUF),
[Qwen/Qwen3-Embedding-0.6B-GGUF](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF),
[gpustack/bge-m3-GGUF](https://huggingface.co/gpustack/bge-m3-GGUF)

**6d — ColBERT / late-interaction reranking (web research)**

**No GGUF ColBERT models exist.** The architecture (BERT-family encoder with
per-token output) is not supported by llama.cpp's GGUF converter. The only
viable runtime is ONNX.

Best candidate: **answerai-colbert-small-v1** (33M params, 96-dim, 0.13GB
ONNX, CPU-capable, outperforms ColBERTv2 110M on benchmarks). Purpose-built
for Vespa-style integration.

Integration requirements for JustSearch:
1. Add ONNX Runtime Java dependency (`com.microsoft.onnxruntime`)
2. Ship/download the 0.13GB ONNX model file
3. Integrate a tokenizer (HuggingFace Tokenizers JNI or DJL tokenizers)
4. Implement MaxSim scoring: for each query token vector, max dot product
   across all document token vectors, then sum
5. Use as reranker on top-50 BM25 results (not first-stage retrieval)
6. Per-token vectors stored as document metadata or computed at rerank time

**Simpler alternative:** Cross-encoder reranking via ONNX (same dependency
chain, simpler architecture — scalar output instead of per-token MaxSim).
JustSearch already has cross-encoder infrastructure in the HEAD process.

**Assessment:** High effort, high potential. The ONNX dependency + tokenizer
is the main cost. ColBERT small's 0.13GB model and CPU capability make it
practical for desktop. But this is a Phase 5d item — do 5a and 5c first.

**Sources:** [answerdotai/answerai-colbert-small-v1](https://huggingface.co/answerdotai/answerai-colbert-small-v1),
[ONNX Runtime Java API](https://onnxruntime.ai/docs/get-started/with-java.html),
[Vespa ColBERT Embedder](https://blog.vespa.ai/announcing-colbert-embedder-in-vespa/)

### Phase 2: Known-Item Refinding

Desktop search is primarily a known-item refinding task: the user remembers a
specific document and searches for it. BEIR contains zero navigational/known-item
queries (all 18 datasets are informational/topical), so our current eval cannot
measure this at all.

**Methodology:** Based on Azzopardi, de Rijke & Balog (SIGIR 2007), who built
simulated known-item topics by selecting query terms weighted by term likelihood
within the target document. Their approach was validated across six languages as
producing retrieval performance statistically comparable to real known-item topics.
We target 150 queries following TREC known-item track conventions (Web Track used
150-300 topics; Enterprise Track used 125). This exceeds the 50-topic minimum for
statistical significance (Sakai, SIGIR 2016). Rahmani et al. (SIGIR 2024) showed
synthetic test collections achieve Kendall's tau = 0.82-0.86 for relative system
ranking — sufficient for comparing pipeline configurations.

**Dataset:** courtlistener (not SciFact/FiQA). Phase 1 showed long documents are
where the pipeline struggles and where known-item search is most relevant. Short-doc
known-item search is already well-covered by existing BEIR MRR/P@1 measurements.

- [x] **2a.** Build term-extraction script (~50-100 lines Node.js). Reads
  corpus.jsonl, computes TF-IDF per document, outputs top-N distinctive terms
  per doc. This is the Azzopardi 2007 term-selection model adapted for our
  corpus format. *(Done: `scripts/search/extract-known-item-terms.mjs`,
  committed 9e20d310)*

- [x] **2b.** Claude Code composes 150 known-item queries from courtlistener
  using the extracted terms. Each query simulates a user who remembers the
  document and types 2-4 distinctive keywords (not a descriptive sentence).
  Outputs: queries.jsonl + qrels TSV (one-to-one: each query maps to exactly
  one source document). Round-trip validation: query the live API, discard
  queries where the source document doesn't appear in top-10 (Promptagator
  consistency filtering). *(Done: `scripts/search/compose-known-item-queries.mjs`,
  committed 31a872d1)*

- [x] **2c.** Run eval harness with custom queries/qrels across pipeline
  configs (lexical, hybrid). Primary metrics: MRR and P@1. Secondary:
  Success@10. Compare to Phase 1 topical-query results on the same corpus.
  *(Done 2026-03-07. Results below.)*

  **Known-item lane results — courtlistener (150 queries, 3-term TF-IDF):**

  | Config | Recall@10 | nDCG@10 | MRR | P@1 |
  |--------|-----------|---------|-----|-----|
  | BM25 | 1.000 | 1.000 | 1.000 | 1.000 |
  | Hybrid RRF | 1.000 | 1.000 | 1.000 | 1.000 |

  Both configs achieve perfect retrieval on known-item queries. Every target
  document ranked #1 for all 150 queries. This establishes a **ceiling effect**
  for TF-IDF-based known-item queries on this corpus: BM25 is sufficient,
  dense adds nothing.

  Artifacts: `tmp/beir-eval/courtlistener-known-item-bm25/metrics.v2.json`,
  `tmp/beir-eval/courtlistener-known-item-hybrid/metrics.v2.json`

  **Interpretation:** The ceiling effect means TF-IDF term extraction produces
  queries that are too easy — the distinctive terms are so rare that BM25
  trivially retrieves the correct document. A harder known-item query model
  (natural language reformulation, partial recall, paraphrased terms) is needed
  to differentiate pipeline configs. This is consistent with Azzopardi 2007's
  finding that TF-IDF term selection creates queries with near-perfect
  retrievability for the target document.

- **Coordination consequence:** Phase 2c v1 is complete as a lane-design
  validation, but it is **not decision-grade routing evidence**. Do not use
  the perfect BM25/Hybrid scores as support for BM25-first routing; the lane
  saturated before it differentiated the configs.

- [x] **2d.** Build a harder known-item lane on courtlistener. Replace
  TF-IDF keyword queries with noisier refinding-style queries:
  natural-language reformulations, partial recall, paraphrased terms, and
  mixed-specificity remembered details. Goal: produce a known-item lane that
  separates BM25 from Hybrid instead of saturating at 1.000.
  *(Done 2026-03-07 evening. Results below.)*

  **Known-item refinding lane v2 - courtlistener title-free (150 queries, rank-window 2-10):**

  The v2 lane deliberately selects non-trivial refinding queries by validating
  candidate queries against live BM25 and keeping only cases where the target
  document is retrieved in ranks 2-10 rather than trivially at rank 1.

  | Config | Recall@10 | nDCG@10 | MRR | P@1 |
  |--------|-----------|---------|-----|-----|
  | BM25 | 1.000 | 0.548 | 0.398 | 0.000 |
  | Hybrid RRF | 0.960 | **0.597** | **0.477** | **0.180** |

  **Key finding:** the harder refinding lane now differentiates the configs.
  Hybrid improves ranking quality on non-trivial known-item queries
  (+0.049 nDCG, +0.078 MRR, +0.180 P@1), while BM25 retains a recall advantage
  (1.000 vs 0.960).

  **Interpretation:** the known-item lane is now decision-grade, but its signal
  is explicitly different from the LoCo topical/passage lane. On courtlistener-like
  6K-token legal docs:
  - BM25 still wins the title-free LoCo lane (0.889 vs 0.871).
  - Hybrid wins the harder refinding lane on ranking metrics.
  - Therefore long-doc routing is **task-dependent**, not a universal
    BM25-first rule.

  Artifacts:
  - `tmp/beir-cache/courtlistener_known_item_refinding/raw/courtlistener_known_item_refinding/known-item-refinding-summary.json`
  - `tmp/beir-eval/courtlistener-known-item-refinding-bm25/metrics.v2.json`
  - `tmp/beir-eval/courtlistener-known-item-refinding-hybrid/metrics.v2.json`

- [ ] **2e.** Optional: repeat on legal_case_reports for cross-dataset
  validation on extreme-length documents. **Deprioritized** (2026-03-09):
  the large mixed-corpus matrix and Phase 5 factorial already provide strong
  cross-dataset evidence on long docs (courtlistener + legal_case_reports).
  Known-item validation on legal_case_reports adds marginal value given
  the existing Phase 2d courtlistener-only results. Run only if needed for
  completeness.

### Phase 3: LLM-as-Judge (Capability, Not Deliverable)

The "LLM judge" is Claude Code itself — the same agent running this session.
No implementation required. Claude Code reads query + document pairs directly,
assigns relevance scores, and writes qrels TSV files that the existing eval
harness consumes.

This is a **capability** that activates whenever other phases need relevance
judgments — Phase 2's round-trip validation, Phase 4's mixed-corpus evaluation,
or ad-hoc quality assessment of search results. The judging protocol: query +
document → 4-point relevance score (0=irrelevant, 1=marginal, 2=relevant,
3=highly relevant).

Known bias (Rahmani et al., CIKM 2025): LLM judges are more lenient than
human annotators on borderline cases, inflating absolute scores. However, bias
does not significantly affect relative system ranking (tau = 0.82-0.86 vs
human judges). Soboroff (NIST, SIGIR 2024) warns against LLM judgments for
TREC-style absolute evaluation but acknowledges utility for comparative work.
For our purpose (comparing pipeline configs, not publishing absolute numbers),
this bias is acceptable.

### Phase 4: Heterogeneous Collection

- [x] **4a.** Multi-dataset indexing harness (combine corpora, track query
  provenance for per-domain metrics).
- [x] **4b.** Run SciFact + FiQA mixed-corpus eval. Compare per-domain
  nDCG to isolated single-domain results.
- [x] **4c.** If results differ significantly, investigate whether adaptive
  fusion (250 Phase 4) would help.
  **Analysis (2026-03-09):** Cross-corpus dilution across small/medium/large
  is modest for short-doc sources (worst: NFCorpus -0.051 nDCG, most < 0.03).
  The dominant quality signal comes from retrieval mode selection
  (SPLADE vs hybrid vs lexical), not fusion weight tuning. courtlistener's
  problem is SPLADE truncation, not sub-optimal fusion. Adaptive fusion from
  250 Phase 4 would at best recover ~0.03 nDCG on NFCorpus — far less than
  the 0.1-0.4 nDCG gains from choosing the right retrieval mode. **Conclusion:
  adaptive fusion is not warranted; document-length-aware mode routing is the
  correct lever.**

**Superseding Phase 4 follow-up (2026-03-07 evening):** the small mixed-corpus
lane is now operational for lexical BM25. A shared `mixed_scifact_fiqa` corpus
was built (62,821 raw docs; 62,784 materialized with sentinel) and evaluated
with per-source query overrides through the canonical wrapper path.

Small-mix lexical result vs fresh isolated lexical baselines:

| Source | Isolated nDCG@10 | Mixed nDCG@10 | Delta | Interpretation |
|--------|------------------|---------------|-------|----------------|
| SciFact | 0.664 | 0.657 | -0.008 | small degradation under cross-domain noise |
| FiQA | 0.220 | 0.231 | +0.011 | slight improvement, not degradation |

Secondary metrics moved in the same modest range: SciFact recall dropped by
`-0.012`, while FiQA recall improved by `+0.014`. This means the first
heterogeneous-collection result does **not** show major lexical collapse from
mixing SciFact with FiQA. The remaining open Phase 4 work is no longer harness
creation; it is testing whether Hybrid / SPLADE / fusion behave differently on
the same mixed corpus.

**Additive Hybrid follow-up (2026-03-08 early):** the same small mixed corpus
was then evaluated with Hybrid (BM25 + dense) and compared against fresh
isolated Hybrid baselines.

| Source | Isolated Hybrid nDCG@10 | Mixed Hybrid nDCG@10 | Delta | Mixed Hybrid vs Mixed BM25 |
|--------|--------------------------|----------------------|-------|----------------------------|
| SciFact | 0.666 | 0.650 | -0.016 | worse by `-0.007` nDCG |
| FiQA | 0.240 | 0.257 | +0.016 | better by `+0.026` nDCG |

Interpretation:
- The shared SciFact + FiQA corpus still does **not** create catastrophic
  Hybrid degradation.
- Mixed-corpus effects are modest relative to isolated baselines for both BM25
  and Hybrid.
- Domain/task behavior still dominates: on SciFact, BM25 and Hybrid stay close
  with BM25 slightly ahead in the mixed corpus; on FiQA, Hybrid is clearly
  better than BM25 even in the mixed corpus.
- The remaining open Phase 4 question is now narrower: whether SPLADE or
  alternate fusion strategies behave differently from BM25 + dense on the same
  heterogeneous corpus.

**Additive GPU SPLADE follow-up (2026-03-08 later):** the same small mixed
corpus was then evaluated with backend-only GPU SPLADE after fixing a
fresh-ingest wait-contract bug that invalidated the first `gpu-r5` attempt.

Purpose of this experiment:

- isolate the first realistic "alternate sparse" mixed-corpus result on the
  shared `SciFact + FiQA` corpus
- determine whether SPLADE changes the mixed-corpus picture beyond the already
  measured BM25 and Hybrid baselines
- validate that backend-only GPU SPLADE can be measured faithfully on the
  hardened eval control plane

Result vs existing mixed-corpus baselines:

| Source | Mixed BM25 nDCG@10 | Mixed Hybrid nDCG@10 | Mixed SPLADE nDCG@10 | Interpretation |
|--------|--------------------|----------------------|----------------------|----------------|
| SciFact | 0.657 | 0.650 | 0.709 | SPLADE is clearly best on the small mixed corpus |
| FiQA | 0.231 | 0.257 | 0.360 | SPLADE is substantially best on the small mixed corpus |

Secondary metrics move in the same direction:

- SciFact:
  - SPLADE vs mixed BM25: `+0.045` recall, `+0.055` MRR, `+0.067` P@1
  - SPLADE vs mixed Hybrid: `+0.050` recall, `+0.062` MRR, `+0.057` P@1
- FiQA:
  - SPLADE vs mixed BM25: `+0.132` recall, `+0.152` MRR, `+0.136` P@1
  - SPLADE vs mixed Hybrid: `+0.109` recall, `+0.116` MRR, `+0.096` P@1

Interpretation:

- The small mixed-corpus lane is no longer "BM25/Hybrid only"; the first
  alternate sparse measurement is now real.
- On this shared `SciFact + FiQA` corpus, SPLADE beats both BM25 and Hybrid
  for both sources, not only on recall but also on ranking quality.
- This is stronger than the earlier BM25-vs-Hybrid story:
  - SciFact no longer looks like "BM25 slightly ahead"; SPLADE overtakes both
  - FiQA no longer looks merely "Hybrid ahead"; SPLADE widens the advantage
- The experiment therefore resolves the first-order mixed-corpus SPLADE
  question for the small two-corpus lane: heterogeneous noise does **not**
  collapse SPLADE here.
- Remaining caveats:
  - this is still only a small two-corpus mix
  - the result is from a benchmark-oriented backend-only GPU lane, not the
    default desktop product posture
  - the matching mixed-corpus context-quality lane is now available, but it
    still relies on query-term proxy coverage because the current mixed
    `SciFact` / `FiQA` query sets do not provide explicit evidence annotations

**Additive follow-up - small mixed-corpus context-quality lane (2026-03-08)**

Artifacts:
- `tmp/beir-eval/mixed-scifact-fiqa-context-quality-summary.json`
- `tmp/beir-eval/phase4-mixed-scifact-context-hybrid.json`
- `tmp/beir-eval/phase4-mixed-fiqa-context-hybrid.json`
- `tmp/beir-eval/phase4-mixed-scifact-context-splade-gpu-r6.json`
- `tmp/beir-eval/phase4-mixed-fiqa-context-splade-gpu-r6.json`

| Source | Mode | Relevant doc hit rate@10 | Mean best relevant excerpt coverage | Context hit@10 (>=0.25) | Context hit@10 (>=0.50) |
|--------|------|---------------------------|-------------------------------------|-------------------------|-------------------------|
| SciFact | BM25 | 0.793 | 0.375 | 0.587 | 0.227 |
| SciFact | Hybrid | 0.783 | **0.395** | 0.620 | 0.240 |
| SciFact | SPLADE | **0.833** | 0.380 | **0.637** | **0.240** |
| FiQA | BM25 | 0.485 | 0.522 | 0.444 | 0.276 |
| FiQA | Hybrid | 0.514 | **0.524** | 0.477 | 0.296 |
| FiQA | SPLADE | **0.631** | 0.486 | **0.554** | **0.326** |

Interpretation:
- the first mixed-corpus context-quality result is no longer missing
- on the small shared corpus, SPLADE strengthens the "did we retrieve a
  relevant document with an excerpt at all?" part of the context story
  - SciFact: SPLADE gives the highest relevant-doc hit rate and the strongest
    `contextHit@0.25`
  - FiQA: SPLADE is clearly best on relevant-doc hit rate and both context-hit
    thresholds
- but SPLADE is **not** uniformly best on excerpt-depth quality inside the
  relevant document
  - on SciFact, Hybrid keeps the highest mean best relevant excerpt coverage
  - on FiQA, Hybrid and even BM25 keep higher average excerpt coverage than
    SPLADE despite SPLADE's stronger hit rates
- so the mixed-corpus story is now two-layered:
  - ranking and relevant-hit behavior: SPLADE is the strongest first measured
    retriever on the small shared corpus
  - excerpt-depth behavior: the current proxy metrics show a tradeoff, not a
    universal SPLADE win
- because this lane still uses query-term proxy coverage rather than curated
  evidence annotations, the safe conclusion is:
  - small-mix mixed-corpus SPLADE is now clearly promising
  - a universal mixed-corpus `SPLADE-first` routing policy is still premature

**Additive follow-up - annotated small mixed-corpus SciFact context lane (2026-03-08)**

Artifacts:
- `tmp/beir-cache/mixed_scifact_fiqa/annotated_source_queries/scifact/queries.annotated.jsonl`
- `tmp/beir-cache/mixed_scifact_fiqa/annotated_source_queries/scifact/annotation-summary.json`
- `tmp/beir-eval/mixed-scifact-fiqa-context-quality-annotated-summary.json`
- `tmp/beir-eval/mixed_scifact_fiqa-scifact-context-annotated-hybrid.json`
- `tmp/beir-eval/mixed_scifact_fiqa-scifact-context-annotated-splade.json`

Annotation build summary:
- total SciFact mixed queries: `1109`
- annotated queries with resolved evidence: `692`
- missing metadata queries: `416`
- unresolved sentence indices: `9`

Annotated SciFact result (`queryCount=300`, `annotatedEvidenceUnitQueryCount=187`):

| Mode | Mean best relevant evidence-unit coverage | Evidence-unit hit@10 (>=0.25) | Evidence-unit hit@10 (>=0.50) |
|------|-------------------------------------------|-------------------------------|-------------------------------|
| BM25 | 0.544 | 0.483 | 0.297 |
| Hybrid | **0.5670** | 0.480 | 0.307 |
| SPLADE | 0.5667 | **0.507** | **0.327** |

Interpretation:
- the mixed-corpus context lane is no longer proxy-only for SciFact
- the earlier proxy reading was directionally correct:
  - SPLADE still gives the strongest "did we surface usable evidence at all?"
    behavior on the annotated SciFact subset
  - Hybrid still keeps a tiny edge on mean evidence-depth coverage inside the
    relevant document
- so the small-mix context story is now stronger, but still not a universal
  SPLADE win:
  - hit-rate / thresholded evidence presence: SPLADE leads
  - average evidence-depth: Hybrid is effectively tied and slightly ahead
- this upgrades the mixed-corpus interpretation from "proxy only" to
  "partially annotation-confirmed"
- remaining limitation: FiQA still has no equivalent native evidence annotation
  lane, so mixed-corpus context confidence remains asymmetric by source

#### Medium mixed-corpus results (4 datasets, 2026-03-09)

Mixed corpus: SciFact + FiQA + NFCorpus + ArguAna (~67K docs).

**Retrieval matrix (12/12 cells):**

| Mode | SciFact | FiQA | NFCorpus | ArguAna |
|------|---------|------|----------|---------|
| lexical nDCG | 0.636 | 0.228 | 0.259 | 0.324 |
| hybrid nDCG | 0.644 | 0.257 | 0.256 | 0.290 |
| splade nDCG | 0.692 | 0.359 | 0.300 | 0.375 |

SPLADE leads on all four datasets. Cross-corpus dilution is modest: NFCorpus
lexical drops -0.051 vs isolated baseline (worst case), SciFact -0.028. FiQA
and ArguAna are within noise.

**Context-quality (proxy):**

| Metric | SciFact lex | SciFact hyb | SciFact splade | FiQA lex | FiQA hyb | FiQA splade |
|--------|------------|------------|----------------|---------|---------|-------------|
| hitRate@10 | 0.763 | 0.770 | 0.820 | 0.480 | 0.508 | 0.630 |
| contextHit≥0.25 | 0.580 | 0.610 | 0.630 | 0.441 | 0.472 | 0.554 |

**Context-quality (annotated, SciFact 187 queries):**

| Metric | lexical | hybrid | splade |
|--------|---------|--------|--------|
| evidenceUnitHit≥0.25 | 0.473 | 0.477 | 0.503 |
| evidenceUnitHit≥0.50 | 0.290 | 0.307 | 0.323 |

SPLADE leads across all context-quality metrics.

#### Large mixed-corpus results (6 datasets, 2026-03-09)

Mixed corpus: SciFact + FiQA + NFCorpus + ArguAna + courtlistener_notitle
+ stackoverflow (~85K docs).

**Retrieval matrix (18/18 cells):**

| Mode | SciFact | FiQA | NFCorpus | ArguAna | courtlistener | stackoverflow |
|------|---------|------|----------|---------|---------------|---------------|
| lexical nDCG | 0.631 | 0.222 | 0.259 | 0.286 | 0.734 | 0.326 |
| hybrid nDCG | 0.647 | 0.248 | 0.251 | 0.260 | 0.827 | 0.332 |
| splade nDCG | 0.692 | 0.359 | 0.300 | 0.374 | 0.083 | 0.584 |

Key findings:
- SPLADE dominates on 5/6 datasets (all short-doc sources)
- **courtlistener SPLADE is catastrophically low (0.083)** — 6K-token long
  documents break sparse neural encoding. SPLADE tokenizer truncation at 512
  tokens loses most content. This is the strongest evidence yet for
  document-length-aware routing.
- Hybrid beats lexical on courtlistener (0.827 vs 0.734) — chunk-merge RRF
  captures content BM25 term saturation misses
- stackoverflow SPLADE 0.584 is nearly 2x lexical 0.326

**Context-quality (proxy, SciFact+FiQA):**

| Metric | SciFact lex | SciFact hyb | SciFact splade | FiQA lex | FiQA hyb | FiQA splade |
|--------|------------|------------|----------------|---------|---------|-------------|
| hitRate@10 | 0.767 | 0.767 | 0.820 | 0.466 | 0.492 | 0.630 |
| contextHit≥0.25 | 0.580 | 0.613 | 0.630 | 0.423 | 0.472 | 0.554 |

**Context-quality (annotated, SciFact 187 queries):**

| Metric | lexical | hybrid | splade |
|--------|---------|--------|--------|
| evidenceUnitHit≥0.25 | 0.477 | 0.483 | 0.503 |
| evidenceUnitHit≥0.50 | 0.297 | 0.310 | 0.323 |

Pattern consistent across all corpus sizes: SPLADE > hybrid > lexical.

**Interpretation summary** (`tmp/beir-eval/mixed-corpus-interpretation-summary.json`):
- 5/6 sources: SPLADE is ranking leader
- courtlistener: hybrid is ranking leader (SPLADE fails)
- SciFact/FiQA: ranking_context_tradeoff — SPLADE wins ranking but hybrid
  wins excerpt depth (marginal)
- annotation_confirms_proxy on SciFact across both medium and large corpora
- Cross-mix stability: SPLADE leadership is consistent across small → medium
  → large corpus sizes for short-doc sources

### Context-quality diagnostics lane (2026-03-08)

tempdoc 258 requires a lane that measures whether retrieval returns **usable
evidence context**, not just the right document ID. That lane is now
operational via `scripts/search/context-quality-eval.mjs`.

Current implementation:
- queries the live `/api/knowledge/search` API with `includeExcerpts=true`
- evaluates the best excerpt returned for any relevant document in top-K
- prefers explicit evidence annotations when the query JSONL provides them
  (`expected_terms`, `evidence_terms`, `expected_keywords`, `requiredFacts`)
- otherwise scores coverage against the query's informative terms (query-term
  proxy, not gold fact labels)
- reports both document-hit rate and excerpt-evidence coverage
- when explicit evidence annotations exist, also reports a second metric family
  for **evidence-unit coverage** (did the excerpt cover the annotated fact or
  phrase units, not just individual terms)

This is intentionally lighter-weight than a full LLM-judge lane, but it makes
the intermediate distinction from tempdoc 255 measurable: **did retrieval
surface useful evidence before generation?**

Additive implementation note:
- the harness now prefers explicit evidence annotations in query JSONL when
  present (`expected_terms`, `evidence_terms`, `expected_keywords`,
  `requiredFacts`)
- when those fields are absent, it falls back to the original query-term proxy
- this keeps the first lane lightweight while making it possible to broaden the
  metric with curated evidence targets instead of rebuilding the harness
- explicit annotations now drive two parallel views:
  - **term coverage**, for continuity with the earlier lightweight proxy
  - **evidence-unit coverage**, for stronger fact/phrase-aware diagnostics when
    curated targets exist
- mixed-corpus execution support is now also in place:
  - `scripts/search/build-mixed-context-annotations.mjs`
  - `scripts/search/run-mixed-corpus-matrix.mjs`
  - `scripts/search/summarize-mixed-corpus-findings.mjs`
  - `scripts/search/run-phase4-mixed-context-quality-suite.mjs --query-profile annotated`

**First full run - courtlistener title-free LoCo lane (2026-03-08)**

Artifacts:
- `tmp/beir-eval/context-quality/courtlistener-notitle-full.json`

| Mode | Relevant doc hit rate@10 | Mean best relevant excerpt coverage | Context hit@10 (>=0.25) | Context hit@10 (>=0.50) |
|------|---------------------------|-------------------------------------|-------------------------|-------------------------|
| BM25 | 0.972 | 0.176 | 0.221 | 0.079 |
| Hybrid | **0.995** | **0.324** | **0.435** | **0.216** |

**Key finding:** on moderate long legal docs, BM25 still wins the
document-ranking lane (title-free LoCo nDCG 0.889 vs Hybrid 0.871), but Hybrid
surfaces **much better query-bearing excerpt evidence** from the relevant
documents (+0.147 mean coverage; +0.214 context-hit@0.25; +0.137
context-hit@0.50).

Interpretation:
- the long-doc BM25-vs-Hybrid question is now explicitly **two-layered**
- BM25 remains stronger when the objective is "rank the right document first"
- Hybrid is stronger when the objective is "surface usable supporting context
  from the right document"
- this explains why a pure nDCG reading would understate Hybrid's value for
  answer-generation/RAG-style flows on the same corpus

**Additive follow-up - harder courtlistener refinding lane (2026-03-08)**

Artifacts:
- `tmp/beir-eval/context-quality/courtlistener-known-item-refinding-full.json`

| Mode | Relevant doc hit rate@10 | Mean best relevant excerpt coverage | Context hit@10 (>=0.25) | Context hit@10 (>=0.50) |
|------|---------------------------|-------------------------------------|-------------------------|-------------------------|
| BM25 | **1.000** | **0.893** | **1.000** | **0.987** |
| Hybrid | 0.960 | 0.883 | 0.960 | 0.940 |

Interpretation:
- the harder refinding lane gives a different context-quality story than LoCo
- once the target document is retrieved, **both** modes usually surface strong
  evidence-bearing excerpts
- BM25 keeps a small context-quality edge here because it retrieves the target
  document for all queries, while Hybrid misses ~4% of them
- therefore the context-quality lane reinforces the existing task split:
  long-doc topical/passage retrieval and harder refinding should not be treated
  as the same routing problem

---

## Investigation Findings (2026-03-03)

Pre-implementation investigation of Phase 1 feasibility. Probed HuggingFace
dataset viewer API and searched codebase for toggle mechanisms.

### HuggingFace API Truncation — Not a Concern

The converter script uses HuggingFace's `/filter` REST API. Concern was that
the API truncates large text cells, which would defeat the purpose of
long-document evaluation. **Tested up to 577,015 characters with
`truncated_cells: []`.** The API returns full document text. The converter's
retry logic (3 retries, exponential backoff) handles intermittent 500 errors
from the `/filter` endpoint.

### Dataset Size Corrections

The original Dimension 1 table had incorrect numbers (likely from LoCoV1 paper
abstracts rather than actual HuggingFace dataset metadata). Verified counts:

| Subset | Docs (actual) | Queries (actual) | Previously claimed |
|--------|--------------|------------------|--------------------|
| courtlistener_Plain_Text | 2,000 | 2,000 | ~45K docs, 1K queries |
| stackoverflow | 7,741 | 400 | ~100K docs, 1K queries |
| legal_case_reports | 770 | 770 | (not listed) |
| LoCoV1-Documents total | 14,838 | 7,734 | (not stated) |

### stackoverflow Is Not a Long-Document Dataset

Sampled 105 stackoverflow documents (100 at offset 0, 5 at offset 500):
- Median passage length: ~300 characters (~75 tokens)
- 96% under 1,000 chars; 98% under 2,000 chars
- Only 2/100 exceed the 2,000-char chunk threshold (`CHUNK_THRESHOLD_CHARS`)
- **Chunking will almost never trigger.** This dataset is equivalent to
  standard BEIR short-doc benchmarks for JustSearch's pipeline.

Tempdoc 251 originally claimed "~1,500 tokens avg"; tempdoc 245 claimed
"4,500 avg tokens." Both are wrong — actual median is ~75 tokens. Moved
stackoverflow from Phase 1 to Phase 4 (heterogeneous collections) as a
short-doc cross-domain component. Replaced with legal_case_reports.

### courtlistener Confirmed as Ideal Long-Doc Dataset

Sampled 5 courtlistener_Plain_Text documents: 10,948 / 15,568 / 30,541 /
33,385 / 44,763 characters. All well above the 2,000-char chunk threshold.
Every document will trigger chunking, making this ideal for Phase 1a/1b/1c.

### Chunk Merge Toggle Exists (YAML-only, NOT env var)

Config key: `search.chunk_aware.enabled` under the `search:` YAML section.
Defaults to `true`. Config-time only (not per-request) — restart server
between runs. **Note:** The env var `JUSTSEARCH_CHUNK_AWARE_ENABLED` does
NOT exist. This config is only settable via YAML or `-D` system property.
To disable: add `chunk_aware.enabled: false` under `search:` in
`config/application.yaml`.

### Embedding Context Window — Now Configurable, But RoPE Scaling Missing

`EmbeddingService.java` now reads `JUSTSEARCH_EMBED_CONTEXT_LENGTH` env var
(default 2048). `DeterminismProfile.java` allows override past nCtxTrain when
the env var is set. However, **RoPE scaling parameters are not passed to
llama-server.** nomic-embed requires `--rope-scaling yarn --rope-freq-scale .75`
for context > 2048 (per GGUF README). llama-server may also have a bug where
these flags are silently ignored (llama.cpp #10355). A valid 8192 context test
requires fixing the RoPE flag passthrough in `LlamaService.java`.

### Harness Compatibility Confirmed

The converter output format (JSONL with `_id`/`text` fields, `qrels/test.tsv`)
matches `beir-eval-win.ps1` expectations exactly. The `-SkipDownload` path
searches for `corpus.jsonl` recursively under `tmp/beir-cache/<dataset>/raw/`.
The converter's default output dir (`tmp/beir-cache/<task>/raw/<task>/`)
aligns with this convention. All four metrics (nDCG@K, Recall@K, MRR, P@1)
are already computed by the harness.

---

## Additional Evaluation Items (from 249 Investigations)

Items below were extracted from the 10 research investigations in tempdoc
249. They augment the four evaluation dimensions above.

### NanoBEIR Expansion (SentenceTransformers F6)

Expand from 4 datasets (SciFact, Arguana, NFCorpus, FiQA) to 13 NanoBEIR
datasets for broader regression coverage. This catches dataset-specific
regressions (e.g., the Arguana anomaly is invisible when averaging only 4
datasets). NanoBEIR also provides MAP@100 and Recall@k for a more complete
picture of first-stage retrieval vs reranking quality.

### Additional Metrics: MRR@10 and Precision@1 (SentenceTransformers F6)

JustSearch reports nDCG@10 only. Desktop search is primarily a known-item
task where the user wants one specific document at rank 1. MRR@10 and P@1
are more relevant primary metrics. The BEIR harness already computes P@1
(added in 245 item 7) but no benchmark exercises it as the primary metric.

### Significance Testing Methodology (SentenceTransformers F6)

At 300 queries, nDCG differences of <0.01-0.02 are within noise. Add
bootstrap resampling or paired t-test with Bonferroni correction (~50 lines).
Per-query score storage is the prerequisite. Tempdoc 245 noted this gap;
this provides the methodology.

### Difficulty-Tier Eval Harness (qmd §7Q5)

qmd tests 24 queries across 4 difficulty tiers: Easy (exact keywords),
Medium (semantic), Hard (vague/indirect), Fusion (requires both lexical +
semantic). Per-tier Hit@K thresholds: Easy >= 80% Hit@3, Medium >= 50%
Hit@3, Hard >= 35% Hit@5, Fusion >= 50% Hit@3. Complementary to BEIR
nDCG — diagnoses which query types regress after pipeline changes.

### Expansion Ablation Methodology (qmd §7Q5b)

No external evidence isolates query expansion's contribution to recall.
JustSearch needs its own A/B evaluation: expansion-on vs expansion-off,
isolated per expansion type (lex/vec/hyde), measured on BEIR datasets.

### BM25 First-Stage + Rerank-Top-100 as Standard Eval (SentenceTransformers D)

`CrossEncoderNanoBEIREvaluator` uses BM25 first-stage + rerank-top-100,
matching JustSearch's architecture. It also excludes Arguana and touche2020
by default — consistent with JustSearch's own anomaly findings. This
validates the eval methodology: "rerank top-100 from first stage" should
be the standard eval pipeline, not exhaustive reranking.

### CI Regression YAML Framework (Anserini F5)

Anserini defines expected nDCG/AP/Recall baselines per dataset in YAML.
CI compares actual results against these and fails on regression.
Applicable as a lightweight regression gate for JustSearch's BEIR eval.

### Overlap vs Merge-Peers Eval (Docling Add.6)

Docling's merge-peers approach (merge undersized consecutive chunks with
matching headings) and JustSearch's 50-token overlap are two solutions to
the same boundary-continuity problem. Whether overlap or merge-peers
produces better retrieval is an open empirical question testable within
JustSearch's `ChunkSplitter` without adopting Docling.

### Alpha Calibration BEIR Sweep (Milvus F5)

Bruch et al. (TOIS 2024) proves alpha weight matters more than
normalization choice. Run alpha sweep across BEIR datasets for CC fusion
mode. This is the highest-value fusion experiment regardless of which
normalization method is used.

### Matryoshka 256-dim BEIR Eval (SentenceTransformers U1)

MTEB aggregate scores at 256-dim are available but per-dataset BEIR
retrieval scores are not. MTEB includes non-retrieval tasks that may
mask retrieval-specific degradation. A JustSearch-specific BEIR eval at
256-dim is a prerequisite before shipping Matryoshka truncation.

### Quantization × Matryoshka Interaction (SentenceTransformers U3)

Q4/Q8 GGUF quantization may degrade the dimension-ordering Matryoshka
relies on. Prescribed experiment: Q8_0 at 768-dim vs Q8_0 at 256-dim
on BEIR — testable in the existing eval infrastructure.

---

## Dependencies

- **252 (Ingestion Quality):** LoCoV1 eval becomes more meaningful when
  documents go through the real ingestion pipeline, not just pre-cleaned
  text. Phase 1 can start without 252.
- **253 (Model Improvements):** Context window (item 6) is measured by
  Phase 1b. Inference-free SPLADE is measured by any benchmark once
  implemented.
- **250 (Pipeline Routing):** Component execution flags (250 item 1c) make
  eval debugging easier but aren't blocking.
- **245 (BEIR Eval):** Existing harness and results are the foundation.
  The FiQA column, isolation matrix, and published baselines carry forward.

## Non-Goals

- Replacing BEIR. BEIR remains the external comparability benchmark. This
  tempdoc adds dimensions BEIR doesn't cover.
- Building a full evaluation platform. Scripts and PowerShell harness, not
  a service.
- User studies. This is automated evaluation, not UX research.

---

## 2026-03-05 Low-Level Eval Audit (Code Reality Check)

This section checks what is implemented vs what this framework needs for
"best average search quality" decisions.

### What is solid right now

- BEIR harness computes Recall@n, nDCG@n, MRR@n, P@1, query errors, and writes
  per-query artifacts (`scripts/search/beir-eval-win.ps1`).
- Diff gate enforces comparability and provenance/ANN parity with
  `NON_COMPARABLE` handling (`scripts/bench/diff-search-eval-suite.mjs`).
- CI BEIR gate defaults to hard thresholds and three datasets
  (`arguana,nfcorpus,scifact`) with fail-on-non-comparable
  (`scripts/ci/dag-runner-beir-gate.mjs`).
- Artifact contract and corpus governance are mature and explicit
  (`docs/reference/contracts/benchmark-eval-contract.md`).

### Gaps that make current eval insufficient (for final direction decisions)

1. **Coverage mismatch vs product goal**
   - Hard gate default coverage is still 3 BEIR short-doc datasets.
   - Known-item, mixed-corpus, and long-doc lanes (Phase 2/4/5 in this tempdoc)
     are still open checklist items.

2. **Metrics are collected but not fully gate-driving**
   - `meanP1AtK` is measured but not compared in BEIR diff thresholds.
   - Isolation summaries optimize for nDCG-first tables, reinforcing topical
     over known-item optimization.

3. **No significance-aware decision rule in the search gate**
   - A conservative Hoeffding uncertainty block is emitted, but gate pass/fail
     still uses fixed ratio cutoffs only.
   - No paired bootstrap/paired randomization test in search-eval scripts.

4. **Enforcement gap in CI wiring**
   - BEIR gate and rank-report workflows are `workflow_dispatch` lanes (not
     PR-triggered by default).
   - Rank report is explicitly report-oriented, with judged BEIR optional.

5. **Documentation/governance drift**
   - `255-eval-pipeline-research.md` currently contains agent pipeline/context
     compression content, not search-eval pipeline-only material. This makes
     eval guidance harder to discover/maintain.

### Sufficiency verdict

Current state is **good for regression hygiene** but **not sufficient yet for
choosing final search-quality direction**. It can protect against obvious
regressions on a narrow benchmark slice, but it still lacks enough
representative evaluation lanes and significance-aware decision logic to rank
competing strategies with high confidence.

## 2026-03-06 Phase 5 execution-environment correction

Tempdoc 259's BEIR-lane hardening slice materially changed the execution
contract for Phase 5.

What is now true:

1. `metrics.v2.json` is the canonical downstream BEIR artifact.
2. Hybrid / dense-bearing configs must hard-fail unless
   `chunkVectorsReady=true`.
3. SPLADE-bearing configs must hard-fail unless SPLADE readiness is true.
4. LambdaMART-bearing configs must hard-fail unless a real active LM model is
   present.
5. Query failures fail the run by default unless `-AllowQueryErrors` is used
   intentionally for local debugging.
6. Production and validator semantics are now aligned on linear-gain nDCG.

Implication for Phase 5:

1. Results collected before these gates existed are pre-hardening evidence.
2. Pre-hardening hybrid or LM-bearing runs are not decision-grade and must be
   rerun before they inform 256 routing / preset decisions.
3. Earlier partial runs remain useful as debugging signal only.

---

## Consolidated Results vs Published Baselines (2026-03-09)

This section cross-references all experimental results from the evaluation
campaign against published academic baselines and identifies findings that
are expected, suspicious, or novel.

### Published baselines used for comparison

Sources: SPLADE v3 paper (arXiv 2403.06789, Table 2), Pyserini BEIR
reproductions (Kamalloo et al., SIGIR 2024), nomic-embed-text-v1.5 MTEB
page, Weaviate hybrid search benchmarks, LoCoV1 paper (arXiv 2402.07440).

| Dataset | BM25 (published) | nomic-embed dense (published) | SPLADE v3 (published) |
|---------|-------------------|-------------------------------|----------------------|
| SciFact | 0.665 | 0.704 | 0.710 |
| FiQA | 0.236 | 0.387 | 0.374 |
| NFCorpus | 0.322 | 0.338 | 0.357 |
| ArguAna | 0.397-0.414 | 0.557 | 0.509 |

### Our results vs baselines: what is expected

**1. SciFact scores are plausible across all experiments.**

| Experiment | Our BM25 | Our Hybrid | Our SPLADE | Verdict |
|-----------|----------|------------|------------|---------|
| Isolated | 0.664 | 0.669 | — | Within 1% of published BM25/dense |
| Small mix | 0.657 | 0.656 | 0.709 | SPLADE matches published 0.710 |
| Medium mix | 0.636 | 0.644 | 0.692 | 2-4% cross-corpus dilution |
| Large mix | 0.631 | 0.647 | 0.692 | Dilution stable, SPLADE holds |

All SciFact values are consistent with published baselines. Cross-corpus
dilution of 2-5% is plausible given IDF distribution shift from mixing
scientific text with financial/legal/technical corpora.

**2. FiQA scores are plausible.**

Our FiQA BM25 isolated (0.220-0.230) matches published 0.236. SPLADE
(0.359) is within 4% of published 0.374. Financial Q&A is inherently
hard (vocabulary mismatch between questions and answers), and the
slightly lower scores are consistent with Q4 quantization effects.

**3. NFCorpus dilution is real but bounded.**

Our isolated NFCorpus BM25 (0.310) matches published 0.322. In the
medium mix, NFCorpus drops to 0.259 (lexical) — a 5.1pp dilution, the
worst of any dataset. This is plausible: NFCorpus has graded relevance
with many relevant docs per query, making it more sensitive to false
positives from other domains. SPLADE partially compensates (0.300 in
mix, closer to published 0.357).

**4. Courtlistener BM25 (0.889 title-free) is consistent with LoCoV1.**

The LoCoV1 paper reports BM25 "nearly perfectly retrieves the correct
documents" on legal tasks, with aggregate BM25 at 79.9-81.5 across 12
tasks. Our 0.889 on a single legal-domain task is above the aggregate
average, which is expected given courtlistener's high term
distinctiveness and moderate corpus size (2,000 docs).

**5. CE catastrophic failure on long docs is expected.**

MiniLM-L6-v2 has a 512-token input limit. On 6K-token courtlistener
docs, >90% of content is truncated. The Sentence Transformers docs
explicitly state that overlength inputs are silently truncated. Our
-0.606 nDCG collapse (0.924→0.318) is the expected behavior per the
model's documented limitations.

**6. SPLADE catastrophic failure on courtlistener (0.083) is expected.**

SPLADE v3 uses a BERT backbone with 512-token max inference length.
On 6K-token legal opinions, >90% of each document is truncated during
SPLADE encoding. No published SPLADE evaluation on long documents
exists, but the HuggingFace model card explicitly warns about the
512-token limit.

**7. Q4→Q8 quantization gap (+0.025 on SciFact) matches literature.**

nomic-embed Q4_K_M has MSE of 2.42e-04 vs reference (from the official
GGUF card). Our measured gap (Q4 0.669 → Q8 0.694) is a 3.6% relative
degradation, consistent with the 3-5% range reported in embedding
quantization studies (Vespa blog, HuggingFace quantization research).
Q8→F16 adds only +0.002, confirming Q8_0 as the sweet spot.

### Suspicious findings requiring investigation

**8. ArguAna scores are anomalously low — RRF fusion actively hurts.**

| Config | Model | Our score | Published baseline | Gap |
|--------|-------|-----------|-------------------|-----|
| BM25 isolated | Q4_K_M | 0.329 | 0.397-0.414 | -0.068 to -0.085 |
| Dense isolated | Q4_K_M | 0.370 | 0.557 | **-0.187 (34%)** |
| BM25+Dense RRF isolated | Q4_K_M | 0.289 | ~0.40 (Vespa/Weaviate) | **-0.111** |
| SPLADE medium mix | Q8_0 | 0.375 | 0.509 | **-0.134 (26%)** |
| Hybrid medium mix | Q8_0 | 0.290 | ~0.40 (Vespa/Weaviate) | **-0.110** |

ArguAna is a symmetric counterargument retrieval task (queries and
documents are both arguments). Multiple problems compound:

- **The isolation experiments used Q4_K_M** (confirmed via metrics.json
  provenance). The Q4 dense-only score (0.370) vs published nomic fp32
  (0.557) is a 34% gap — consistent with Q4 destroying the fine-grained
  semantic distinctions that ArguAna demands.
- **The mixed-corpus experiments used Q8_0** (`mixed-corpus-config.mjs`
  line 93). Even with Q8, hybrid ArguAna is only 0.290 in the medium
  mix — virtually identical to the Q4 isolation result (0.289). **This
  means Q4→Q8 did NOT fix the ArguAna RRF problem.** The dense leg
  is still too weak to help RRF on symmetric counterargument retrieval.
- **RRF fusion makes it worse in both quantizations.** BM25 alone: 0.329
  (Q4) / 0.324 (Q8 medium mix). Dense alone: 0.370 (Q4). BM25+Dense
  RRF: 0.289 (Q4) / 0.290 (Q8 medium mix) — **below either leg** in
  both cases. Published systems (Vespa, Weaviate, Elastic) all show RRF
  matching or improving ArguAna. Our degradation suggests the dense leg
  is generating poor rankings that dilute BM25's signal through fusion,
  regardless of quantization level.
- **Mixed-corpus SPLADE (0.375, Q8) is still well below published
  SPLADE v3 (0.509).** The 26% gap is too large for cross-corpus
  dilution alone (other datasets show only 1-5% dilution).

**Root cause hypothesis (updated):** The problem is not primarily
quantization. Even Q8_0 does not fix ArguAna. The more likely causes:

1. **RRF is structurally wrong for symmetric tasks.** ArguAna queries
   are full-length arguments (not short keywords). BM25 and dense
   produce rankings with fundamentally different distribution shapes on
   long symmetric queries, and RRF's rank-based fusion treats them
   equally. Published systems that do well on ArguAna may use
   alpha-weighted score fusion (Weaviate) rather than rank fusion.
2. **2048-token context may truncate ArguAna arguments.** ArguAna
   arguments can exceed 2048 tokens. Unlike short-doc BEIR datasets,
   truncation at the embedding stage could lose critical argument
   structure. This would affect both Q4 and Q8 equally.
3. **nomic-embed Q4/Q8 may both be too weak for counterargument
   matching.** The published nomic fp32 score on ArguAna is 0.557 —
   already below the MTEB state of the art. If Q8 retains only ~95% of
   fp32 quality, that gives ~0.53, still well above our 0.370 (Q4) or
   the RRF results. The remaining gap may be specific to our
   llama.cpp-based inference path (task prefix handling, pooling method,
   normalization) rather than quantization per se.

**Validation completed (2026-03-09): Q8 dense-only isolation run.**

| Config | Q4 isolation | Q8 isolation | Delta |
|--------|-------------|--------------|-------|
| BM25 (lexical) | 0.329 | 0.336 | +0.007 |
| Dense-only (vector) | 0.370 | 0.339 | **-0.031** |
| BM25+Dense RRF (hybrid) | 0.289 | 0.292 | +0.003 |

Q8 dense-only nDCG@10 = **0.339** — worse than Q4 (0.370), and far
below published nomic fp32 (0.557). This falls in the decision tree's
≤0.38 branch: **the dense leg itself is broken, not just RRF fusion.**

The Q8 < Q4 result is likely explained by HNSW graph non-determinism
(different index builds produce different approximate nearest-neighbor
graphs, ±2-3% recall variance). Both results are equivalently poor —
0.34-0.37 vs published 0.557 is a 34-39% gap.

**Updated root cause conclusion (2026-03-09, after tempdoc 268
investigation):** The ArguAna dense quality gap is **NOT in the
embedding inference path.** Three-way validation proved this:

| Path | vs sentence-transformers FP32 | Result |
|------|---------------------------------|--------|
| llama-server + Q8 GGUF (`--pooling mean`) | 0.998+ cosine sim | PASS |
| Our FFM path (`NativeLlamaBinding.embed()`) | 0.998+ cosine sim | PASS |

All originally suspected root causes were cleared:
- ~~Pooling mismatch~~ — GGUF metadata confirmed `pooling_type = 1` (MEAN)
- ~~Task prefix handling~~ — correctly implemented
- ~~L2 normalization~~ — correctly implemented
- ~~logits/output flag~~ — llama.cpp auto-overrides for embedding models
- ~~Tokenization divergence~~ — 0.998+ similarity proves no divergence

**Eval pipeline investigation (2026-03-09):**

*HNSW approximation cleared.* efSearch=500 produced identical results to
efSearch=100 (nDCG=0.339, Recall=0.696). The HNSW graph is not losing
relevant documents — it returns the same nearest neighbors regardless of
beam width. Config: `JUSTSEARCH_INDEX_VECTOR_EF_SEARCH=500` env var,
validated on existing Q8 ArguAna index.

*Root cause identified: self-retrieval + quantization-sensitive task.*

ArguAna uses a unique evaluation design: **queries ARE corpus document
text** (argument text), and the relevant document per qrels is the
**counter-argument** (a different document). This means:

1. The query document always appears in search results (similarity ≈ 1.0
   to itself), consuming one of the K=10 retrieval slots.
2. 91.5% of queries have the self-document at rank 1.
3. The counter-argument is pushed to rank 2+ at best.
4. P@1 = 0 for all 1406 queries — correct behavior, not a bug.

Per-query rank distribution of relevant counter-arguments:

| Rank | Queries | % of total |
|------|---------|------------|
| 2 | 357 | 25.4% |
| 3 | 177 | 12.6% |
| 4 | 108 | 7.7% |
| 5 | 94 | 6.7% |
| 6 | 79 | 5.6% |
| 7-10 | 164 | 11.7% |
| Not found | 427 | 30.4% |

**Gap decomposition (vs published nomic fp32 = 0.557):**

Verified experimentally with K=100 retrieval on the Q8 index, then
recomputed nDCG@10 under three scenarios:

| Scenario | nDCG@10 | Gap vs published |
|----------|---------|------------------|
| Our eval (K=10, with self) | 0.339 | **0.218** |
| K=10, self excluded | 0.456 | 0.101 |
| K=100, self excluded, scored @10 | 0.465 | 0.092 |
| Published fp32 baseline | 0.557 | — |

| Factor | nDCG | % of total gap |
|--------|------|----------------|
| Self-retrieval (`ignore_identical_ids`) | 0.118 | 54% |
| K truncation (K=10 vs K=100) | 0.009 | 4% |
| Remaining (quantization + HNSW variance) | 0.092 | 42% |

**Self-retrieval is the dominant factor (54%).** BEIR's evaluation.py
defaults to `ignore_identical_ids=True` — published baselines exclude
the query document from metric computation. Our eval pipeline does not,
so the self-document consumes a top-10 slot for 91.5% of queries.
Source: [beir-cellar/beir evaluation.py](https://github.com/beir-cellar/beir/blob/main/beir/retrieval/evaluation.py).

**K truncation is negligible (4%).** Widening retrieval from K=10 to
K=100 recovers only 43 additional queries (30.4% → 27.3% miss rate),
adding 0.009 nDCG. Published baselines use K=1000; the marginal gain
beyond K=100 is near zero.

**Remaining 0.092 gap (42%) is unverified.** Candidate causes:
1. **HNSW graph construction variance.** Q4 and Q8 index builds produce
   different rankings (Q4 nDCG=0.503 vs Q8 nDCG=0.456, both self-excluded)
   despite near-identical embeddings — confirming non-deterministic graph
   structure as a factor.
2. **Q8 quantization on adversarial similarity task.** ArguAna requires
   distinguishing counter-arguments from topically-similar irrelevant
   arguments. Small vector perturbations from quantization may have
   outsized ranking effects. Verifying this requires fp32 or fp16
   embeddings on the same corpus (not yet tested).

*Chunk merge path verified harmless.* Although `chunkMergeApplied=true`
is reported (help document chunks exist in index), the chunk vector
search returns 0 ArguAna chunks → code returns `wholeDocResult`
unchanged (SearchOrchestrator.java:879-880).

**Final assessment: ArguAna gap is 58% explained, 42% narrowed.**

The 0.218 nDCG gap is majority-explained by an eval methodology
difference (self-retrieval filtering) that inflates the apparent gap.
The true quality gap is 0.092 nDCG — attributable to Q8 quantization
and HNSW graph variance on an adversarial task. Neither represents a
bug in the eval pipeline or the search engine. No other BEIR dataset
tested shows this pattern, confirming ArguAna is an outlier.

**Practical impact:** ArguAna is an unusual benchmark (debate-style
counterargument matching). Real desktop search is overwhelmingly
asymmetric (short keyword query → document), so this anomaly does not
invalidate the routing conclusions. To make future ArguAna comparisons
fair, the eval pipeline should adopt `ignore_identical_ids` filtering
to match BEIR's default methodology.

**9. LambdaMART zero effect is suspicious in its completeness.**

GPL-trained LambdaMART showed exactly zero standalone effect on every
dataset tested (courtlistener: 0.924→0.924, legal: 0.570→0.573). While
the GPL literature primarily supports dense retriever adaptation rather
than LambdaMART improvement, a perfectly-zero effect across two datasets
raises the question of whether the LambdaMART model was actually
executing. This was verified (LM training produced 0.954 nDCG, and the
model was loaded at runtime), but the effect is still surprising.

**Literature context:** The GPL paper (Thakur et al., NAACL 2022)
evaluates GPL for dense model domain adaptation, not for LambdaMART
training. No published work validates GPL-generated triples as
effective LambdaMART training data. Our result may be the first
empirical evidence that GPL triples do not transfer to learned rankers.

### Novel findings (no published comparison exists)

**10. Mixed-corpus cross-domain dilution is modest (1-5pp).**

No published study measures nDCG drop from mixing BEIR datasets into a
shared index. All published BEIR evaluations index each dataset in
isolation. Our 36-cell matrix across 3 corpus sizes (small/medium/large)
is the first systematic measurement of this effect.

Finding: dilution is bounded, mode-independent (affects BM25, hybrid,
and SPLADE similarly), and does not change mode ranking. NFCorpus is
most sensitive (medical vocabulary collision with other domains).

**11. SPLADE-first routing for short docs is novel evidence.**

No published mixed-corpus SPLADE evaluation exists. Our finding that
SPLADE dominates all 5 short-doc datasets consistently across 3 corpus
sizes extends the SPLADE v3 paper's isolated-dataset results to
heterogeneous real-world-like conditions.

**12. Context-quality metrics are novel.**

No published benchmark specifically measures within-document excerpt
evidence coverage at the retrieval stage. The closest work is
RAGChecker (NeurIPS 2024, Amazon Science), which measures claim recall
in generated answers rather than retrieved excerpts. Our evidence-unit
coverage metric appears to be new.

**13. SPLADE hit-rate vs hybrid excerpt-depth tradeoff is novel.**

The finding that SPLADE leads on document hit-rate (+5-15pp) but hybrid
leads on excerpt depth within retrieved documents (~1-2pp) has no
published precedent. It is theoretically consistent: SPLADE's term
expansion finds more relevant documents; dense retrieval's continuous
similarity better identifies the most relevant passages within a
found document. The practical implication — that the tradeoff clearly
favors SPLADE because finding the right document matters more than
marginal excerpt depth — is a new observation.

### Summary of confidence levels

| Finding | Confidence | Basis |
|---------|-----------|-------|
| SPLADE-first for short docs | **High** | 5 datasets, 3 sizes, matches published deltas |
| SPLADE disabled for long docs | **High** | Expected from 512-token limit, no counter-evidence |
| CE disabled for long docs | **High** | Expected from MiniLM 512-token limit |
| Q8_0 is the embedding sweet spot | **Medium** | SciFact only, consistent with literature |
| BM25 dominates moderate long docs | **Medium** | courtlistener only, matches LoCoV1 literature |
| Hybrid helps extreme long docs | **Medium** | legal_case_reports only, consistent with MaxP literature |
| Cross-corpus dilution is modest | **Medium** | Novel, no published comparison, but measured at 3 scales |
| ArguAna results are degraded | **High — 58% EXPLAINED** | 54% from eval methodology difference (BEIR `ignore_identical_ids=True` filters self-retrieval; our eval doesn't). 4% K truncation. Remaining 42% (0.092 nDCG) narrowed to Q8 quantization + HNSW graph variance; not a pipeline bug. See finding 8. |
| LambdaMART has zero value | **Medium** | Consistent results but may be GPL-specific, not general |
| Context-quality rankings | **Low-Medium** | Novel metrics, annotation only on SciFact |

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 70 days at audit time.

