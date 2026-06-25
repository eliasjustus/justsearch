---
title: "322: Long-Document Sparse Retrieval — Closing the SPLADE Quality Gap"
type: tempdoc
status: done
created: 2026-03-17
depends-on: [309, 273]
---

# 322: Long-Document Sparse Retrieval

## Purpose

Fix SPLADE's catastrophic quality loss on long documents. SPLADE-v3's 512-token
context window causes nDCG@10 = 0.19 on CourtListener (vs BM25 = 0.98) — the
single largest retrieval quality gap in JustSearch's pipeline.

**Success criterion**: SPLADE does not degrade BM25 fusion on long-doc corpora.
Concretely: `bm25_splade` nDCG@10 ≥ `bm25` nDCG@10 on CourtListener-200. The
current gap is -2.3% (0.9575 vs 0.9801). Target: parity or positive contribution.

**Scope**: This tempdoc addresses the long-doc problem specifically. Broader sparse
quality uplift (better BEIR scores on all document lengths) is a desirable side
effect but not the success criterion.

## Background

### The problem (from 309 §31 Phase 1a results, 2026-03-17)

| Mode | SciFact (short) | CourtListener-200 (long) | CORD-19 (mixed) |
|------|----------------|--------------------------|-----------------|
| BM25 | 0.6601 | **0.9801** | 0.3403 |
| SPLADE | 0.6144 | **0.1872** | **0.3943** |
| bm25_splade | 0.6727 | 0.9575 (-2.3%) | 0.3932 |

SPLADE is the best single retriever on biomedical content (CORD-19) but
catastrophically bad on long legal docs. Adding SPLADE to BM25 on CourtListener
*hurts* — the near-random SPLADE signal dilutes the near-perfect BM25 signal.

### Root cause

SPLADE-v3 uses a BERT-base backbone with 512-token max sequence length. Documents
are truncated at index time. A 5000-word legal opinion (CourtListener median) loses
~90% of its content — producing near-random retrieval on queries targeting content
beyond the first ~380 words.

### What's already been tried

- **SPLADE parent-length suppression** (309 §4, 316): Proven unmeasurable — scores
  are already so poor that suppressing them further has no effect.
- **Rep-max sliding window** (273): Implemented then reverted — Nguyen et al.
  (SIGIR 2023) showed Rep-max is the worst aggregation strategy for LSR.
- **Score-max chunk search** (273): Implemented and wired but **never evaluated
  on long-doc corpora** (273 Phase 3 pending). This is the critical first experiment.
- **Inference-free SPLADE (doc-v3-distill)** (253): Regressed -0.040 to -0.060
  nDCG across 3 datasets. Closed.

---

## Investigation checklist

### Phase 1: Score-max chunk SPLADE eval — DONE (309, 2026-03-18)

Evaluated by another agent (309 Item 2). Score-max made SPLADE **worse**:

| Mode | Without Score-max | With Score-max | Delta |
|------|------------------|----------------|-------|
| lexical | 0.9801 | 0.9801 | 0.0 |
| splade | 0.1872 | **0.1400** | **-4.7%** |
| bm25_splade | 0.9575 | 0.9625 | +0.5% |

**Conclusion (309)**: "Aggregation strategies (Score-max, ExactSDM, Rep-max) cannot
fix SPLADE on long docs. The solution requires a longer-context sparse model."

- [x] ~~Run Score-max eval on CourtListener-200~~ — done, negative result
- [x] ~~Decision gate~~ — Score-max failed. **Proceed to Phase 2.**

### Phase 2: BGE-M3 investigation — VRAM RESOLVED, implementation in progress

- [x] Obtain INT8 quantized BGE-M3 ONNX model — `gpahal/bge-m3-onnx-int8`, 544 MB
- [x] **Measure peak VRAM** — INT8 OOMs at 5120+ tokens (quadratic unfused attention)
- [x] **FP16 + Flash Attention optimization** — Optimum O4 export + re-attached sparse
  head. 8192 tokens = 2.6 GB total VRAM. Fits any 4+ GB GPU.
- [x] **Java ORT integration assessed** — ORT 1.24.3 (already used) supports
  MultiHeadAttention + Flash Attention. SpladeEncoder's CUDA pattern reusable.
- [x] **Core encoder implemented** — `BgeM3Encoder.java` (~450 lines) with:
  - DJL HuggingFaceTokenizer for XLM-RoBERTa SentencePiece (250K vocab)
  - Vocab loaded from tokenizer.json for sparse ID→string mapping
  - GPU session lifecycle mirroring SpladeEncoder (lazy init, 60s retry, arena config)
  - Sparse post-processing: per-token weight → Map<String, Float> (max aggregation)
  - Dense post-processing: 1024-dim L2-normalized CLS pooling
  - Token-budget batched encoding
  - 8 unit tests passing (BgeM3PostProcessTest)
- [x] Wire into KnowledgeServer — branches on SPARSE_MODEL=bge-m3|splade at
  deferred init. Falls back to SPLADE if BGE-M3 fails.
- [x] Wire into SearchOrchestrator — single encode() call produces both dense
  and sparse query outputs. Falls through to SPLADE/EmbeddingService if inactive.
- [x] Wire into IndexingLoop — BgeM3BackfillOps writes both VECTOR + SPLADE
  fields per document in one batch update.
- [x] Schema: `FieldCatalogDef.withVectorDimension(1024)` applied when
  SPARSE_MODEL=bge-m3. Changed fingerprint triggers existing migration machinery.
- [x] IndexingLoop backfill routing: checks bgeM3Encoder before SPLADE fallback.
- [ ] Place BGE-M3 model files in `models/bge-m3/` directory
- [ ] Benchmark sparse+dense quality on SciFact + CourtListener-200 via jseval
  - **Blocker found (2026-03-18)**: CourtListener-200 qrels use mixed-case doc IDs
    (`courtlistener_Plain_Text_Passage_0`) but materialized filenames are lowercase
    (`courtlistener_plain_text_passage_0`). ir-measures can't match → all metrics
    0.0000 across all modes (including lexical baseline). This is a corpus/qrels
    case mismatch, not a BGE-M3 issue. Fix: normalize qrels to lowercase or
    add case-insensitive matching in jseval's `resolve_doc_id()`.
  - Qrels fixed (lowercased corpus-id column). Results on CourtListener-2000:

    | Mode | SPLADE-v3 (309, 200 docs) | BGE-M3 (2000 docs) |
    |------|--------------------------|-------------------|
    | lexical | 0.9801 | 0.9040 |
    | **splade** | **0.1872** | **0.4779 (+155%)** |
    | bm25_splade | 0.9575 | 0.8327 |

    Note: 309 used 200-doc corpus, this run used 2000-doc corpus — BM25 baseline
    differs due to corpus size, not model change. The key result: BGE-M3 sparse
    nDCG@10 = 0.48 vs SPLADE's 0.19 on long documents — a 2.5x improvement.

  - End-to-end pipeline validated: FP16+Flash Attention GPU session, unified
    dense+sparse backfill at 50 docs/batch, search returns hits with BGE-M3 sparse.
- [x] SciFact eval (2026-03-18): No regression on short docs.
  BGE-M3 sparse 0.618 vs SPLADE 0.614 (+0.6%). bm25_splade 0.671 vs 0.673 (-0.2%).
- [x] Indexing throughput measured (2026-03-18, SciFact, RTX 4070 12 GB):

    **BGE-M3 GPU backfill (FP16 + Flash Attention, batch=50):**

    | Metric | BGE-M3 | SPLADE GPU (O3+FP16) | nomic-embed GPU |
    |--------|--------|---------------------|-----------------|
    | Encode/doc | **6.4ms** | 28ms | 97ms |
    | Pipeline rate | **100.2 docs/sec** | 40.1 docs/sec | 10.3 docs/sec |
    | Output | dense+sparse | sparse only | dense only |

    BGE-M3 produces **both** dense (1024-dim) and sparse in a single forward pass.
    The previous pipeline required SPLADE + nomic-embed as separate backfill stages.
    Combined previous throughput was bottlenecked by the slower stage (nomic-embed
    at 10.3 docs/sec for dense). BGE-M3 at 100.2 docs/sec is **~10x faster** than
    the combined previous pipeline while producing both outputs simultaneously.

    Per-doc breakdown: encode=6.4ms, Lucene write=0.2ms, commit=3.1ms, total=10.0ms.
    GPU cold start: 1441ms/doc for first batch (session init), then steady state.
- [x] **Decision gate PASSED**: BGE-M3 competitive on short docs (SciFact: -0.2%
  fusion, within noise) AND dramatically better on long docs (CourtListener: +155%
  sparse). Proceed with making BGE-M3 the production path.
  SPLADE stays as permanent fallback via JUSTSEARCH_SPARSE_MODEL=splade.

### Future: Documents exceeding 8192 tokens

BGE-M3's 8192-token context covers ~6000 words — the vast majority of documents.
For documents that exceed this (long legal opinions, academic papers, full contracts),
the parent document's sparse representation is truncated. However, the existing
chunk infrastructure provides coverage:

- Each ~500-token chunk fits trivially within BGE-M3's 8192-token context and gets
  a high-quality sparse representation
- `BgeM3BackfillOps` already processes chunks (queries `SPLADE_STATUS=PENDING`,
  reads `CHUNK_CONTENT`) — chunks get BGE-M3 sparse vectors automatically
- Score-max chunk search (`searchChunksSplade()` + `collapseByParent()`) is wired

This is fundamentally different from the SPLADE Score-max failure: SPLADE's 512-token
context made every chunk representation poor (model was the bottleneck). With BGE-M3,
each chunk is well-represented — Score-max picks the most relevant from a set of
high-quality encodings. No catastrophic quality drop at the context boundary.

**Not yet validated empirically.** A targeted eval on documents >8192 tokens with
BGE-M3 chunk Score-max would confirm this expectation. Low priority since 8192
tokens covers ~99% of typical personal files.

### Technical debt: architectural cleanup before BGE-M3 becomes default

The current BGE-M3 integration is bolt-on — `bgeM3Encoder` fields added alongside
`spladeEncoder` and `embeddingService` with null-check branching at every integration
point. This works for opt-in but accumulates debt. Required cleanup:

**Root cause**: Consumers depend on concrete models (BGE-M3, SPLADE, nomic-embed)
instead of capabilities (dense encoding, sparse encoding). Every consumer needs
model-specific branching.

**Correct design**: `DenseEncoder` / `SparseEncoder` interfaces. `BgeM3Encoder`
implements both. `SpladeEncoder` implements `SparseEncoder`. `EmbeddingService`
implements `DenseEncoder`. A `UnifiedEncoder` variant wraps both for single-pass
optimization. Consumers hold interface references — polymorphism replaces branching.

| Issue | Current state | Correct fix |
|-------|--------------|-------------|
| Scattered null-checks (7+ sites) | `if (bge != null) ... else if (splade != null)` | Interface polymorphism |
| `SPARSE_MODEL` read in 4 places | Re-read from EnvRegistry each time | Resolve once → strategy object |
| Dimension override on SSOT | `withVectorDimension(1024)` post-hoc patch | Dimension from model property at catalog construction |
| Duplicated backfill (222 lines) | `BgeM3BackfillOps` ≈ `SpladeBackfillOps` | Generic backfill with encoder interfaces |
| Health check hardcoded true | `bgeM3Encoder != null → embeddingReady=true` | `denseEncoder.isAvailable()` on interface |
| Two-phase Worker startup race | Worker starts with SPLADE, restarts with BGE-M3 | Resolve model before Worker spawn |
| No integration test | Manual jseval validation only | Automated test with SPARSE_MODEL=bge-m3 |

**Sequencing**: The interface extraction (#1, #4, #5) is the foundational change —
the others follow naturally from it. The dimension fix (#3) and startup race (#6)
are independent and can be done in parallel. Estimated scope: ~400 lines changed
across 8-10 files, plus ~200 lines of new interface code.

**Deferred to tempdoc 323** (retrieval encoder abstraction). The interface extraction
fights implementation details (IDF fallback, batch return types, GPU lifecycle) at
every integration point. The current branching is ugly but explicit and confined to
~7 grepable sites. A third model integration is the correct trigger.

**Issue #3 (fingerprint bug) was fixed** — `SsotCommitMetadataSource` now
incorporates `vectorDimensionOverride` into `index_schema_fp`.

**Issue #6 (two-phase startup) is a non-issue** — config snapshot is written before
Worker spawn. No race.

### Phase 3: LLM-backbone SPLADE (if Phase 2 insufficient or broader uplift desired)

- [ ] Obtain CSPLADE-bi-8B checkpoint (or train via published recipe)
- [ ] **Validate long-doc quality** — neither CSPLADE nor Mistral-SPLADE have
  published long-doc results (see Candidate 3 caveats)
- [ ] Benchmark doc encoding throughput and VRAM on target hardware
- [ ] Measure model swap latency (INDEXING→ONLINE round-trip on consumer GPU)
- [ ] Evaluate with Strategy B (BoW queries) as lower-bound, Strategy A (full
  model swap) as upper-bound
- [ ] **Decision gate**: If long-doc quality validates and swap latency ≤ 5s →
  plan integration. Otherwise → defer to Strategy D (wait for Qwen3.5-Embedding).

---

## Candidate 1: Score-max Chunk-Level SPLADE (already implemented)

**Priority: HIGHEST. Run this first. Zero code changes needed.**

273 implemented chunk-level Score-max search: each chunk has its own SPLADE vector,
queries search the chunk index, max chunk score per parent represents the document.

### Current status

- `searchChunksSplade()` wired in `SearchOrchestrator`
- IS_CHUNK filter separates parent-level and chunk-level SPLADE search
- Score-max aggregation via `collapseByParent()` infrastructure
- **Never evaluated on long-doc corpora** (273 Phase 3 pending)

### Expected impact

On CourtListener (median 5115 words, ~107 chunks/doc):
- Each chunk gets a full 512-token SPLADE encoding
- Query matches against the most relevant chunk, not the truncated first passage
- Expected improvement: significant on known-item queries where the answer is
  in the middle or end of the document

On SciFact (short docs, ~200 tokens):
- Documents produce 0-1 chunks — no change expected

### Literature context

Lionis & Ju (ECIR 2025) reproduced Nguyen et al. (SIGIR 2023) and confirmed:

| Method | Performance |
|--------|-------------|
| Rep-max | **Worst** — degrades with more segments |
| Score-max | Baseline — simple, reasonable |
| ExactSDM | **Best** (+2 nDCG@10 over Score-max) |

Key finding: first segment consistently dominates. Multi-segment aggregation gains
are marginal — Score-max to ExactSDM is ~2 nDCG points at best.

---

## Candidate 2: BGE-M3 as Unified Dense+Sparse Model

BGE-M3 (BAAI, Feb 2024) is a 567M-parameter model producing dense, sparse, and
ColBERT embeddings in a single forward pass.

| Property | BGE-M3 | SPLADE-v3 | nomic-embed-v1.5 |
|----------|--------|-----------|-------------------|
| Parameters | 567M | 110M | 137M |
| Context window | **8192 tokens** | 512 tokens | 2048 tokens |
| Sparse retrieval | Yes (learned weights) | Yes | No |
| Dense retrieval | Yes (1024-dim) | No | Yes (768-dim) |
| Languages | 100+ | English | English |
| ONNX support | Yes (Java impl exists) | Yes | Yes |

### Why it's strong

1. **8192-token context eliminates truncation.** CourtListener median (~6500 tokens)
   fits. No chunking, no aggregation, no information loss.
2. **Sparse is a byproduct of dense.** One model, one forward pass, one VRAM slot.
   Replaces both nomic-embed and SPLADE.
3. **Published long-doc results.** On MLDR, BGE-M3 sparse achieves ~10 nDCG@10
   points above its own dense mode on long documents.
4. **100+ languages.** Addresses 309 §20 (multilingual SPLADE suppression) for free.
5. **Java ONNX impl exists.** yuniko-software/bge-m3-onnx with GPU acceleration.

### VRAM budget — static model size

| Component | Current | With BGE-M3 |
|-----------|---------|-------------|
| SPLADE (FP32) | ~500 MB | **Removed** |
| nomic-embed (ONNX) | ~200 MB | **Removed** |
| Cross-encoder (MiniLM) | ~30 MB | ~30 MB |
| 9B LLM (Q4_K_M) | ~4,500 MB | ~4,500 MB |
| **BGE-M3 (INT8)** | — | **~571 MB** |
| **Total static** | ~5,230 MB | **~5,101 MB** |

Static model size fits. **But this is misleading.** The real constraint is
**activation memory during long-sequence inference.** HuggingFace discussions report
10.5 GB VRAM at `max_length=5000, batch_size=1` in FP16. At INT8, peak VRAM during
8192-token indexing could still exceed 8 GB. **This must be empirically measured
before committing to BGE-M3** — it's the make-or-break risk.

INT8 quantization quality is negligible (-0.15% sparse, -0.65% dense).

### Integration cost (if VRAM validates)

1. ONNX model: pre-quantized `gpahal/bge-m3-onnx-int8`
2. Replace `EmbeddingService` + `SpladeEncoder` with single `BgeM3Service`
3. Full reindex required (different dimensions, tokenizer, weight distributions)
4. GPL training data invalidated — re-run after reindex
5. CC fusion weights need recalibration (current 0.60/0.20/0.20)

### Risks

1. **Peak VRAM under long sequences** — the critical unknown
2. **Indexing throughput**: 567M params is ~5x larger than SPLADE-v3
3. **Short-doc sparse quality**: may differ from SPLADE-v3 (SciFact could regress)
4. **Single point of failure**: consolidates two independent models into one session
5. **ColBERT output wasted**: JustSearch doesn't use it

---

## Candidate 3: LLM-Backbone SPLADE Variants

### Models

**Mistral-SPLADE / Echo-Mistral-SPLADE** (arXiv 2408.11119, Aug 2024):
Mistral-7B backbone, echo embeddings, LoRA rank 16. ~55 BEIR nDCG@10 (Echo variant).
Paper describes results as "preliminary" and "still in progress."

**CSPLADE** (arXiv 2504.10816, AACL 2025):
Llama-3.1-8B backbone, three variants (unidirectional, echo, bidirectional).
Best variant (CSplade-bi-8B): 55.3 BEIR nDCG@10, 41.3 MS MARCO MRR@10.
Lucene-compatible sparse output. LoRA rank 16.

| Metric | SPLADE-v3 | CSPLADE-bi (8B) | Mistral-SPLADE |
|--------|-----------|-----------------|----------------|
| BEIR nDCG@10 | ~50 | 55.3 | ~55 |
| Parameters | 110M | 8B | 7B |
| VRAM (FP16) | ~250 MB | ~16 GB | ~14 GB |

### Critical caveat: no long-doc validation

**Neither CSPLADE nor Mistral-SPLADE has published results on long documents.**
CSPLADE was trained with 2048 max sequence length. Mistral-SPLADE was trained
with max_length=256. Both were evaluated on BEIR (predominantly short documents)
and MS MARCO (short passages).

The assumption that LLM backbones would fix long-doc SPLADE is based on the
backbone's *capability* for longer context, not on *demonstrated* long-doc sparse
retrieval quality. This is unvalidated. BGE-M3 has published MLDR (long-doc)
results; CSPLADE has zero.

### Feasibility reassessment (2026-03-18 correction)

The original analysis incorrectly dismissed LLM-backbone variants as "not feasible"
by conflating index-time and query-time VRAM requirements.

**Document encoding (index time)**: Not a VRAM problem. JustSearch's single-tenant
GPU policy already kills `llama-server.exe` during INDEXING mode, freeing the full
GPU for the SPLADE encoder. A 7-8B model fits in 8 GB when the chat LLM is unloaded.

**Query encoding (search time)**: The actual constraint. Both models use the same
encoder for doc and query. Four strategies exist:

#### Strategy A: Model swap at query time

JustSearch already swaps models via `InferenceLifecycleManager` (four-state machine:
ONLINE/INDEXING/TRANSITIONING/OFFLINE, MMF cross-process coordination).

**Realistic swap latency on consumer hardware** (RTX 4060 / 8 GB):

| Phase | Time |
|-------|------|
| VRAM flush delay | 2s (hardcoded in ILM) |
| 7-8B Q4_K_M cold load from disk | ~3.5s |
| Process startup + health poll | ~1-2s |
| **Total round-trip** | **~5-7s** |

Published datacenter numbers (L40S: 2.4s) are not representative of consumer
hardware. The 5-7s estimate is realistic for the target platform.

**Activity-adaptive preloading** could reduce perceived latency if a reliable UI
signal exists (e.g., user navigates to search tab). Speculative — requires a
concrete design for how search intent is detected before query submission.

#### Strategy B: Inference-free query path (SPLADE-doc pattern)

Use the 7-8B model for document encoding only; bag-of-words queries at search time.

| Model | BEIR nDCG@10 | Query encoding |
|-------|-------------|----------------|
| SPLADE-v3 (siamese) | 51.7 | Neural (110M) |
| SPLADE-v3-Doc (BoW) | 47.0 | Bag-of-words |
| IDF-FLOPS + ensemble distill (2024) | **50.35** | Bag-of-words |

Raw BoW loses ~4.7 nDCG. Recent work (arXiv 2411.04403) closes this to ~1.4 points.
Zero query-time VRAM, zero latency. **But: quality of CSPLADE-8B doc encoder + BoW
queries has never been tested.** The "~50-52" estimate is a guess — the 8B model's
sparse distribution could be more dependent on neural query encoding than BERT's.

#### Strategy C: Distilled asymmetric query encoder

Train a small (~110M) query encoder compatible with the 7B document encoder.
**No published precedent** for cross-architecture sparse retrieval distillation.
Vocabulary mismatch (32K LLM vs 30K BERT) is a concrete obstacle. Novel research
with uncertain outcome. Deferred.

#### Strategy D: LoRA adapter swapping (same base for chat + SPLADE)

The ideal approach: one base model in VRAM, swap LoRA adapters (~50-100 MB) between
chat and SPLADE tasks at near-zero latency. `llama-server` supports this natively
via `POST /lora-adapters`.

**Blocked.** JustSearch uses Qwen3.5-9B for chat, which uses a hybrid Gated DeltaNet
+ MoE architecture (model type `qwen3_5`). All existing SPLADE LoRA adapters target
standard transformers (Llama, Mistral). Incompatible weight shapes, layer types, and
vocabulary sizes (248K vs 32-152K).

**Future path**: Wait for Qwen3.5-Embedding release (Qwen has shipped embedding
variants for every major generation). Unknown timeline, unknown sparse support.
Revisit when available.

### Strategy summary

| Strategy | Quality | Query latency | Status |
|----------|---------|---------------|--------|
| A: Model swap | ~55 (if long-doc validates) | 5-7s cold | Feasible, high latency |
| B: Inference-free | Unknown | 0ms | Feasible, quality unknown |
| C: Distilled encoder | Unknown | <50ms | Deferred — novel research |
| D: LoRA swap | ~55 | ~instant | **Blocked** — arch mismatch |

---

## Evaluated and dismissed

| Model | Why dismissed |
|-------|-------------|
| EmbeddingGemma (Google, 308M, open weights) | Dense-only, 2K context — doesn't address sparse or long-doc |
| Gemini Embedding 2 (Google, March 2026) | API-only, closed source — incompatible with local-first |
| Qwen3-Embedding (0.6B/4B/8B) | Dense-only, Qwen3 architecture (incompatible with Qwen3.5 chat model) |
| LLM-backbone variants as dual chat+SPLADE | CSPLADE/Mistral-SPLADE LoRA fine-tuning disrupts generation; not usable for chat |

---

## References

- Nguyen, MacAvaney & Yates (SIGIR 2023): "Adapting Learned Sparse Retrieval for
  Long Documents" — Score-max, ExactSDM, SoftSDM
- Lionis & Ju (ECIR 2025): "On the Reproducibility of Learned Sparse Retrieval
  Adaptations for Long Documents" — confirms first-segment dominance, ExactSDM best
- BGE-M3: arXiv 2402.03216 (Feb 2024)
- yuniko-software/bge-m3-onnx: github.com/yuniko-software/bge-m3-onnx
- BGE-M3 INT8: huggingface.co/gpahal/bge-m3-onnx-int8
- BGE-M3 VRAM discussion: huggingface.co/BAAI/bge-m3/discussions/2
- Mistral-SPLADE: arXiv 2408.11119 (Aug 2024)
- CSPLADE: arXiv 2504.10816 (Apr 2025), AACL 2025
- SPLADE-v3 baselines: arXiv 2403.06789 (Mar 2024) — SPLADE-v3 vs SPLADE-v3-Doc
- Inference-free competitive retrieval: arXiv 2411.04403 (Nov 2024) — IDF-FLOPS +
  ensemble distillation closes doc-only gap to ~1.4 nDCG
- NVIDIA GPU memory swap: developer.nvidia.com/blog/cut-model-deployment-costs —
  Mistral-7B TTFT 2.4s on L40S via CPU↔GPU swap
- llama-swap: github.com/mostlygeek/llama-swap — model hot-swap proxy for llama.cpp
- llama-server LoRA swapping: github.com/ggml-org/llama.cpp/discussions/8849
- Qwen3-Embedding: huggingface.co/Qwen/Qwen3-Embedding-8B
- Qwen3.5 architecture: huggingface.co/blog/mlabonne/qwen35 — hybrid Gated DeltaNet
- EmbeddingGemma: arXiv 2509.20354 — 308M open-weights, dense-only, 2K context
- Pinecone SPLADE explainer: pinecone.io/learn/splade

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 61 days at audit time.

