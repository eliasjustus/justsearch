---
title: "268: Embedding Inference Path Fidelity"
type: tempdoc
status: done
created: 2026-03-09
---

> NOTE: Noncanonical investigation doc. May drift.

# 268: Embedding Inference Path Fidelity

## Purpose

Investigate why our llama.cpp-based embedding inference produces dense
retrieval scores 34-39% below the published nomic-embed-text-v1.5
reference on ArguAna, regardless of quantization level.

## How This Issue Was Found

The eval campaign (tempdoc 251) ran BEIR benchmarks across multiple
datasets, retrieval modes, and quantization levels. ArguAna consistently
showed anomalously low dense retrieval scores — hybrid (BM25+Dense RRF)
scored *below* BM25 alone, meaning the dense leg was actively hurting
fusion. This was initially attributed to RRF being structurally wrong
for symmetric tasks, and separately to Q4 quantization quality loss.

To separate these hypotheses, a Q8 dense-only isolation experiment was
run (2026-03-09). The expectation: if the problem was quantization, Q8
dense-only should score ~0.50+ (close to published fp32 0.557). If the
problem was RRF fusion, Q8 dense-only should be healthy but RRF should
still degrade.

The result was neither: Q8 dense-only scored **0.339** — actually worse
than Q4's 0.370, and 39% below published. This eliminated both
quantization and RRF fusion as primary causes, pointing instead to a
structural issue in how our llama.cpp-based inference path produces
embeddings. The Q8 < Q4 delta (-0.031) is within HNSW graph
non-determinism range, so both quantizations are equivalently broken.

The issue is masked on other datasets (SciFact Q8 dense = 0.694 vs
published 0.704, only -1.4% gap) because those are asymmetric tasks
where BM25 carries most of the retrieval signal in hybrid mode. ArguAna
is the only symmetric task in our eval suite, making it uniquely
sensitive to dense retrieval quality — which is why it surfaced the
apparent inference-path problem. (Later analysis in tempdoc 251
found the dominant cause was an eval methodology difference —
BEIR's `ignore_identical_ids=True` default — not inference quality.)

## Evidence (from tempdoc 251 eval campaign)

| Config | Our result | Published | Gap |
|--------|-----------|-----------|-----|
| Q4 dense-only ArguAna | 0.370 | 0.557 (fp32) | -34% |
| Q8 dense-only ArguAna | 0.339 | 0.557 (fp32) | -39% |
| Q4 dense-only SciFact | 0.669 | 0.704 (fp32) | -5% |
| Q8 dense-only SciFact | 0.694 | 0.704 (fp32) | -1.4% |

SciFact shows the expected quantization ladder (Q4 < Q8 < fp32, small
gaps). ArguAna shows a large gap that does not shrink with higher
quantization — proving the issue is not quantization but something in
our inference path.

ArguAna is uniquely sensitive because it is a symmetric task (queries
are full-length arguments, not short keywords). Inference-path errors
that are masked by BM25 dominance on asymmetric tasks become visible
when dense retrieval must do all the work.

## Candidate Root Causes

### RC1: Pooling method mismatch — CLEARED (GGUF metadata correct)

nomic-embed-text-v1.5 uses **mean pooling** over all token embeddings.
llama.cpp's embedding extraction may default to a different method
(CLS token, last token, or first token). Mean pooling averages all
token representations; other methods use a single token and lose
distributed semantic information.

**Investigation:**
- [x] Check how `EmbeddingActor.java` / `LlamaService.java` extracts
  embeddings from the llama.cpp JNI bridge
- [x] Check what pooling method llama.cpp's `llama_get_embeddings()`
  returns by default for nomic-embed models
- [x] Compare against nomic-embed's `config.json` pooling specification
- [x] Verify GGUF file contains `pooling_type` metadata — confirmed `nomic-bert.pooling_type = 1` (MEAN)
- [x] No mismatch found — RC1 cleared

**Findings (2026-03-09 code audit):**

The embedding path is: `EmbeddingService` → `LlamaService.embed()` →
`EmbeddingActor.embed()` → `NativeLlamaBinding.embed()` →
`llama_get_embeddings_seq(ctx, 0)`. Embeddings use **direct JNI/FFM**
(not HTTP/llama-server).

**The suspected root cause:** `NativeLlamaBinding.newContext()`
(line 286-309) creates the llama context by calling
`llama_context_default_params()` and then overrides `n_ctx`, `n_batch`,
`n_ubatch`, `n_seq_max`, `n_threads`, and `embeddings` — but **never
sets `pooling_type`**. The field exists in `NativeStructs.CONTEXT_PARAMS_LAYOUT`
(line 51) but is left at the default value.

llama.cpp's default is `LLAMA_POOLING_TYPE_UNSPECIFIED` (-1), which defers
to the GGUF model metadata. If the GGUF file includes a `pooling_type`
key (stored as `{arch}.pooling_type`, e.g., `nomic-bert.pooling_type`),
that's used. **If the GGUF lacks this metadata** (common in older
quantizations or certain conversion tools), llama.cpp falls back to
`LLAMA_POOLING_TYPE_NONE` (0).

The full enum (from `llama.h`):
- `LLAMA_POOLING_TYPE_UNSPECIFIED = -1` (defer to model metadata)
- `LLAMA_POOLING_TYPE_NONE = 0` (per-token only, no sequence pooling)
- `LLAMA_POOLING_TYPE_MEAN = 1` (correct for nomic-embed)
- `LLAMA_POOLING_TYPE_CLS = 2` (first token / CLS)
- `LLAMA_POOLING_TYPE_LAST = 3` (last token)
- `LLAMA_POOLING_TYPE_RANK = 4` (reranking models)

**Critical nuance from upstream research (2026-03-09):**

With `NONE` pooling, `llama_get_embeddings_seq()` returns **NULL**
because no pooled result tensor is computed — only per-token embeddings
exist. Our code checks for NULL at `NativeLlamaBinding.java:648` and
throws `BackendException("Embedding pointer null")`. Since embeddings
ARE being produced (just of poor quality), this means **pooling is NOT
`NONE`**. The GGUF file must contain pooling metadata that resolves to
a non-NONE type.

The official `nomic-ai/nomic-embed-text-v1.5-GGUF` on HuggingFace
was re-converted after llama.cpp PR #5500 (commit `4524290`, Feb 2024)
which added pooling metadata extraction from `1_Pooling/config.json`.
The model card references compatibility with this commit. So if we're
using the official GGUF, `UNSPECIFIED` should resolve to `MEAN` via
model metadata.

**This means one of:**
1. We're using the official GGUF → pooling IS mean → RC1 is cleared
   and the root cause is elsewhere.
2. We're using a third-party GGUF (bartowski, etc.) converted before
   Feb 2024 → pooling metadata absent → `llama_get_embeddings_seq`
   returns NULL → we'd see `BackendException`, not low scores.
3. We're using a third-party GGUF with non-MEAN metadata → pooling
   is CLS or LAST → explains the quality degradation.

**GGUF metadata verification (2026-03-09):**

The Q8_0 GGUF file was inspected directly (binary header parse):
```
nomic-bert.pooling_type = 1 (type: uint32)
```
**Pooling type IS MEAN (1).** The GGUF metadata is correct. With
`LLAMA_POOLING_TYPE_UNSPECIFIED` (-1) as our context default, llama.cpp
reads this metadata and applies mean pooling. **RC1 is cleared.**

The defensive `pooling_type = MEAN` fix was still applied (commit
pending) as a safety net against future GGUF files that might lack
metadata, but it is NOT the cause of the ArguAna quality gap.

**This means the root cause is elsewhere.** The remaining hypotheses:

If pooling IS correctly mean, the dataset asymmetry explanation shifts:
- **SciFact** (short passages, -1.4% gap): Could be explained by minor
  tokenization differences between llama.cpp and HuggingFace tokenizers
  (a known source of small divergence per llama.cpp discussion #9980).
- **ArguAna** (long arguments, -34-39% gap): Something else is
  accumulating error across longer sequences — tokenization drift,
  numerical precision differences, or context handling.

Additionally, `evalTokens()` (line 944-989) only marks the **last token**
with `logitsFlags = 1`. For MEAN pooling this is irrelevant (pooling is
over all tokens regardless of logits flags), but for NONE/LAST pooling
this determines which token's embedding is returned.

**Proposed fix (defensive, recommended regardless of GGUF source):**

One-line change in `NativeLlamaBinding.newContext()`:
```java
// After line 300 (setByte for embeddings):
if (embeddings) {
  setInt(params, CONTEXT_PARAMS_LAYOUT, "pooling_type", 1); // LLAMA_POOLING_TYPE_MEAN
}
```

This removes the dependency on GGUF metadata correctness and makes the
binding self-documenting. Zero downside — nomic-embed requires mean
pooling regardless of what the GGUF says.

**Verification results (all completed 2026-03-09):**
- [x] Inspected GGUF metadata: `nomic-bert.pooling_type = 1` (MEAN) confirmed
- [x] Applied defensive `pooling_type = MEAN` fix in `NativeLlamaBinding.newContext()`
- [x] Ran three-way embedding comparison: 0.998+ cosine similarity — path is correct
- [ ] Re-run ArguAna dense-only (deferred — expected no change since embeddings are correct;
  the gap is in the eval pipeline, not embedding inference)

**Key files (with line references):**
- `NativeLlamaBinding.java:286-309` — `newContext()`, missing `pooling_type`
- `NativeLlamaBinding.java:644-660` — `embed()`, calls `llama_get_embeddings_seq`
- `NativeLlamaBinding.java:677-758` — `embedBatch()`, same pattern
- `NativeLlamaBinding.java:944-989` — `evalTokens()`, logits flag only on last token
- `NativeStructs.java:42-74` — `CONTEXT_PARAMS_LAYOUT`, pooling_type at line 51
- `EmbeddingActor.java:428-447` — `embedSingleChunk()`, L2 normalization post-extraction

### RC2: Task prefix handling — CLEARED (correctly implemented)

nomic-embed-text-v1.5 requires task-specific prefixes:
- `search_document: ` for documents being indexed
- `search_query: ` for queries at search time

The Q4 prefix A/B test on SciFact showed zero impact (0.669 vs 0.669),
but SciFact is an easy dataset where BM25 carries most signal. ArguAna's
symmetric nature means dense retrieval must distinguish arguments from
counterarguments — prefix semantics could matter more here.

**Investigation:**
- [x] Verify what prefixes are applied at index time vs query time
- [x] Check if both index-time and query-time prefixes are correctly
  applied in our pipeline
- [ ] A/B test: run ArguAna dense-only with explicit prefixes vs without

**Findings (2026-03-09 code audit):**

Prefixes are correctly and consistently applied:
- `EmbeddingService.java:58` — `DOCUMENT_PREFIX = "search_document: "`
- `EmbeddingService.java:61` — `QUERY_PREFIX = "search_query: "`
- `embedDocument()` (line 259) prepends document prefix before calling `embed()`
- `embedQuery()` (line 269) prepends query prefix before calling `embed()`
- `SearchOrchestrator.java:1139` calls `embeddingService.embedQuery(queryString)`
- `embedDocumentWithChunks()` (line 278) also prepends document prefix

Both index-time and query-time paths apply the correct prefix. This is
not the cause of the ArguAna degradation. A/B testing on ArguAna could
still be informative but is deprioritized given RC1 findings.

### RC3: L2 normalization — CLEARED (correctly implemented)

nomic-embed outputs must be L2-normalized before cosine similarity.
If our pipeline skips normalization or double-normalizes, the KNN
distance calculations will be incorrect.

**Investigation:**
- [x] Check if embeddings are L2-normalized after extraction
- [x] Check if Lucene's KNN query expects pre-normalized vectors or
  normalizes internally
- [ ] Compare raw embedding magnitudes between our path and reference

**Findings (2026-03-09 code audit):**

L2 normalization is applied consistently across all code paths:
- **Single-chunk embed:** `EmbeddingActor.embedSingleChunk()` (lines 436-446)
  computes L2 norm and divides each component by magnitude.
- **Batch embed:** `EmbeddingActor.normalizeVector()` (lines 364-376)
  applies the same L2 normalization to each batch result.
- **Multi-chunk mean-pool:** `EmbeddingActor.meanPool()` (lines 493-533)
  averages chunk vectors then **re-normalizes** the mean to unit length.

Lucene vector storage uses `new KnnFloatVectorField(def.id, vec)` without
an explicit `VectorSimilarityFunction` (`FieldMapper.java:245`), which
defaults to `COSINE` — correct for L2-normalized vectors. (Test code in
`VectorFormatDetectorTest.java` explicitly uses `COSINE` for all vectors,
confirming the intended behavior.)

No double-normalization or missed-normalization issues found. This is
not the cause.

### RC4: Context window / truncation — LOW CONCERN

ArguAna arguments can be long. If our embedding path truncates at a
different length than the reference implementation, argument structure
could be lost.

**Investigation:**
- [x] Check max token count in our embedding path vs nomic-embed's
  trained context (2048 tokens, 8192 with RoPE)
- [ ] Measure ArguAna document token distribution — what % exceeds
  our context limit?

**Findings (2026-03-09 code audit):**

- `EmbeddingService.resolveEmbedContextLength()` (line 229-235) defaults
  to **2048 tokens** unless overridden by `JUSTSEARCH_EMBED_CONTEXT_LENGTH`.
- `DeterminismProfile.from()` (line 72-130) auto-clamps context to model's
  `nCtxTrain` from GGUF metadata. For nomic-embed, `nCtxTrain` = 2048
  (though the model supports 8192 via RoPE scaling).
- `EmbeddingActor` (line 92-95) uses `maxContextTokens` from model metadata
  for chunking. Documents exceeding this limit are split into overlapping
  chunks with sliding window, then mean-pooled.

The chunking + mean-pool approach should handle long ArguAna arguments
gracefully. The 2048-token limit matches the model's training context.
This is unlikely to be the primary cause, though measuring ArguAna's
token distribution would confirm.

## Validation Protocol

For each candidate, the validation is:

1. Extract embeddings for 10 sample ArguAna documents via our path
2. Extract embeddings for the same 10 documents via reference
   Sentence Transformers (`nomic-ai/nomic-embed-text-v1.5`)
3. Compare: cosine similarity between our embeddings and reference
   embeddings per document (should be > 0.99 if inference is correct)
4. If < 0.95 for any document, the inference path has a structural bug

A standalone comparison script would be the most efficient approach —
extract from both paths, compute pairwise similarity, report divergence.

## Architectural Context: Why Embeddings Are Unique

Embedding generation is the only inference path in JustSearch that loads
a native model **directly into the JVM process** via Java FFM (Panama).
The chat/RAG LLM takes the conventional path: launch `llama-server.exe`
as a separate OS process (Brain) and communicate over HTTP.

No production-ready Java FFM/Panama binding for llama.cpp exists
externally — the existing Java bindings (`kherud/java-llama.cpp`, etc.)
all use JNI. JustSearch's `NativeLlamaBinding` is a custom FFM binding
built from scratch (struct layouts in `NativeStructs`, ABI verification
in `AbiAudit`, native memory management via `BridgeAllocator`).

### Why the split makes sense

| Factor | Chat LLM (llama-server) | Embeddings (FFM in-process) |
|--------|------------------------|----------------------------|
| Throughput | Low (one conversation) | High (thousands of docs during indexing) |
| Latency sensitivity | Low (user reads) | High (batch pipeline) |
| Statefulness | KV cache, conversation history | Stateless |
| Crash blast radius | Kills conversation | Loses one document embedding |
| HTTP overhead | Acceptable | Adds up across thousands of calls |

### Why it creates risk

`llama-server` has built-in safety nets that the FFM binding lacks:
- Its `/v1/embeddings` endpoint **rejects** `POOLING_TYPE_NONE` with an
  explicit error ("Pooling type 'none' is not OAI compatible").
- It exposes `--pooling` as a visible, documented CLI flag.
- It reads GGUF metadata and applies defaults automatically.

The FFM binding calls `llama_context_default_params()` and selectively
overrides fields. If you miss one — like `pooling_type` — there's no
safety net. The `CONTEXT_PARAMS_LAYOUT` struct has 24 fields; only 6
are explicitly set. Other fields left at defaults that could matter for
future models include `rope_scaling_type`, `attention_type`, and
`flash_attn_type`.

### Upstream references

- llama.cpp PR #5500 (commit `4524290`, Feb 2024): Added pooling metadata
  extraction from `1_Pooling/config.json` during GGUF conversion.
- llama.cpp discussion #9980: Sentence-transformers vs llama.cpp embedding
  differences — residual divergence attributed to tokenization, not pooling.
- llama.cpp issue #8956: `POOLING_TYPE_NONE` behavior — `llama_get_embeddings_seq`
  returns NULL when no pooled result tensor exists.
- llama.cpp issue #16451: `embd-bge-small-en-default` convenience flag
  incorrectly set pooling to NONE — same class of silent degradation bug.
- `nomic-ai/nomic-embed-text-v1.5-GGUF` (HuggingFace commit `18d1044`):
  Re-converted with pooling metadata after PR #5500.

## Embedding Path Validation (2026-03-09)

**VERDICT: The FFM embedding inference path is correct.**

Three-way comparison of the same 10 sample texts (short sentences with
`search_document:` prefix):

| Path | vs sentence-transformers (FP32) | Result |
|------|---------------------------------|--------|
| llama-server + Q8 GGUF (`--pooling mean`) | 0.9981-0.9988 cosine sim | PASS |
| Our FFM path (`NativeLlamaBinding.embed()`) | 0.9981-0.9988 cosine sim | PASS |
| llama-server, long texts (~300 words each) | 0.9985-0.9986 cosine sim | PASS |

All three paths produce essentially identical embeddings. The 0.998
similarity (vs perfect 1.000) is explained by Q8 quantization vs FP32.

**Key observations during FFM test:**
- llama.cpp auto-detects non-causal BERT model and calls `encode()`
  instead of `decode()` (`decode: cannot decode batches with this
  context (calling encode() instead)`)
- llama.cpp auto-overrides our last-token-only logits flags to mark
  ALL tokens as outputs (`init: embeddings required but some input
  tokens were not marked as outputs -> overriding`)
- GGUF metadata `nomic-bert.pooling_type = 1` (MEAN) confirmed correct
- The defensive `pooling_type = MEAN` fix was applied but is redundant

**The 34-39% ArguAna quality gap is NOT caused by the embedding
inference path.** The root cause must be in the evaluation pipeline —
document ingestion, Lucene HNSW indexing, or retrieval/scoring during
eval. This investigation needs to pivot from embedding fidelity to
eval pipeline debugging.

**Tooling (cleaned up 2026-03-09):**
- `scripts/eval/embed_reference.py` — sentence-transformers reference extractor
  with `--compare`, `--texts`, `--dim` flags for cross-path validation
- `modules/ai-bridge/.../EmbeddingExtractionTest.java` — self-validating FFM
  integration test (6 tests: dimension, norm, non-zero, determinism,
  differentiation, JSON extraction). Writes `build/ffm_embeddings.json`
  for external comparison.
- `scripts/eval/embed_llamaserver.py` — DELETED (redundant with integration test)
- `scripts/eval/long_test_texts.json` — DELETED (superseded by built-in samples)

## Research Findings (2026-03-09)

Research questions RQ1-RQ5 were investigated to determine the best
long-term embedding architecture. Key findings below.

### RQ1: Embedding Model Landscape

**Question:** Is nomic-embed-text-v1.5 still the best embedding model
for JustSearch's use case?

| Model | Params | Dim | MTEB Avg | ArguAna | Notes |
|-------|--------|-----|----------|---------|-------|
| nomic-embed-text-v1.5 | 137M | 768 | 62.28 | 0.557 | Current model. Matryoshka support (768/512/256/128/64). Task prefixes required. |
| nomic-embed-text-v2-moe | 475M | 768 | ~66 | — | MoE architecture, ~2x compute. Only 4 active experts per token (95M active). Announced late 2025. |
| snowflake-arctic-embed-m-v2.0 | 305M | 768 | 63.29 | — | Strong quality. Matryoshka support. MIT license. |
| bge-small-en-v1.5 | 33M | 384 | 56.70 | — | Very small, fast. Noticeably lower quality. |
| gte-base-en-v1.5 | 137M | 768 | 60.49 | — | Same size as nomic, slightly lower quality. |
| stella_en_400M_v5 | 435M | varies | 66.77 | — | High quality but large. Matryoshka 256-8192d. |
| jina-embeddings-v3 | 570M | varies | 65.5 | — | Task LoRA adapters. Too large for consumer VRAM budget. |

**Conclusion:** nomic-embed-text-v1.5 remains the right model for now.
It hits the sweet spot of quality, size, and Matryoshka flexibility.
snowflake-arctic-embed-m-v2.0 is the strongest alternative if we ever
need to switch, but doesn't justify a re-index today. nomic-embed-text-v2-moe
is worth monitoring but its 475M params may exceed our VRAM budget when
sharing with chat LLM.

**Matryoshka dimension reduction:** nomic-embed-text-v1.5 supports
Matryoshka representations. Using 256d instead of 768d gives ~1-3%
quality drop on MTEB with 3x storage reduction and faster HNSW search.
Worth evaluating if index size becomes a concern.

### RQ2: Model Format — ONNX Wins

**Question:** Should we stay with GGUF or move to ONNX?

**Key finding (corrected after verification):** The official ONNX export
on HuggingFace (`nomic-ai/nomic-embed-text-v1.5`, `onnx/` directory,
uploaded by Xenova via PR #3, Feb 2024) outputs **`last_hidden_state`**
— raw per-token embeddings. Mean pooling and L2 normalization must be
applied externally, same as with GGUF. The `sentence_embedding` output
node only exists in Optimum SentenceTransformer re-exports, not in the
official repo files.

**Available ONNX variants:**

| File | Size | Notes |
|------|------|-------|
| `onnx/model.onnx` | 547 MB | FP32 |
| `onnx/model_fp16.onnx` | 274 MB | FP16 |
| `onnx/model_int8.onnx` | 137 MB | INT8 quantized |
| `onnx/model_quantized.onnx` | 137 MB | Quantized |
| `onnx/model_q4.onnx` | 165 MB | 4-bit |

**Inputs:** `input_ids`, `attention_mask`, `token_type_ids` (standard
BERT inputs). Opset 14. Dynamic batch dimensions supported.

**Impact on migration:** ONNX does NOT eliminate pooling/normalization
code — we still need mean pooling + L2 norm in Java, same as today.
The advantage shifts to: (a) official Java bindings vs custom FFM,
(b) better encoder performance, (c) framework consolidation with
reranker/SPLADE.

| Aspect | GGUF (llama.cpp) | ONNX (ORT) |
|--------|-----------------|------------|
| Pooling | Handled by llama.cpp (via metadata or flag) | **Must implement in Java** (mean pool over `last_hidden_state`) |
| Normalization | Handled by llama.cpp (`--embd-normalize`) or Java-side | **Must implement in Java** (L2 norm) |
| Bug surface | Silent misconfiguration possible (24-field struct) | Simpler — just token-level output, explicit pooling |
| Encoder performance | Generalist (optimized for autoregressive) | **Optimized for encoders** — 1.5-9.5x faster (benchmarks vary) |
| Java bindings | Custom FFM (our code, only such binding in existence) | **Official** (`com.microsoft.onnxruntime`) |
| GPU support | CUDA/Vulkan/Metal via llama.cpp backends | CUDA/DirectML/TensorRT via ORT execution providers |

**Performance:** Multiple benchmarks show ONNX Runtime is 1.5-9.5x
faster than llama.cpp for encoder-only models (BERT-family). llama.cpp's
architecture is optimized for autoregressive generation (KV cache,
speculative decoding) — features that are wasted on embedding models.

**Conclusion:** ONNX remains the better choice for embeddings, but the
advantage is narrower than initially assumed. The main wins are official
bindings, encoder performance, and framework consolidation — not
elimination of pooling code.

### RQ3: Serving Infrastructure — ONNX Runtime In-Process

**Question:** What should serve the embedding model?

| Option | Pros | Cons |
|--------|------|------|
| **ONNX Runtime in-process** | No HTTP overhead; already in codebase (reranker); pooling baked in; fastest for embeddings | In-process crash risk (mitigated: ORT is very stable) |
| llama-server HTTP | Crash isolation; existing `LlamaServerOps` patterns | HTTP overhead for 1000s of docs; GGUF pooling config needed; generalist runtime |
| infinity-emb | Embedding-focused; sentence-transformers-identical | Python dependency; separate ecosystem |
| TEI (Hugging Face) | Very fast (Rust); battle-tested | Rust binary dependency; no GGUF |

**Decision: ONNX Runtime in-process.**

Rationale:
1. **Already in the codebase.** ONNX Runtime 1.19.2 is a dependency.
   `CrossEncoderReranker` and `SpladeEncoder` both use ORT in-process
   with GPU support. The patterns are proven and battle-tested.
2. **Best throughput.** No HTTP serialization/deserialization overhead.
   For indexing thousands of documents, this matters — each document
   embedding would otherwise require JSON-serializing a `float[768]`
   array and a loopback HTTP round-trip.
3. **Pooling correctness by construction.** The ONNX graph produces
   the correct `sentence_embedding` output directly. No `pooling_type`
   configuration, no metadata dependencies, no silent degradation.
4. **Framework consolidation.** Moving embeddings to ORT means the
   Worker uses a single inference framework (ONNX Runtime) for
   embeddings, reranking, and SPLADE. The entire llama.cpp FFM binding
   chain (~10 files) can be removed.
5. **Crash risk is acceptable.** ONNX Runtime has a substantially
   better stability track record than llama.cpp. It doesn't have the
   class of segfaults, memory leaks, and backend-specific issues that
   llama.cpp's rapid development produces. The reranker and SPLADE
   encoder already run in-process without issues.

### RQ5: Integration Patterns — Reranker as Blueprint

**Question:** Can we reuse existing ONNX Runtime patterns?

**Yes.** `CrossEncoderReranker.java` provides a complete blueprint:

- **Dual CPU+GPU sessions:** Lazy GPU session init with double-checked
  locking. CPU session always available as fallback.
- **GPU switching:** `selectSession()` checks `shouldUseGpu` callback
  (a `BooleanSupplier`) to pick GPU vs CPU session per-request.
- **VRAM arbitration:** `GpuConfig` record with `shouldUseGpu` supplier
  that checks `signalBus.isMainGpuActive()`. Integrates with existing
  ONLINE/INDEXING mode system and MMF `main_gpu_active` flag.
- **GPU memory:** 512MB default arena, `kSameAsRequested` strategy.
  Embedding model (~139 MiB Q8) needs less — 256MB should suffice.
- **DLL preflight:** Checks for ONNX RT CUDA provider DLLs before
  attempting GPU session creation. Graceful degradation to CPU.
- **Observability:** `OrtCudaStatus` enum (AVAILABLE, UNAVAILABLE,
  DLL_MISSING, INIT_FAILED) for diagnostics.
- **Thread safety:** `OrtSession.run()` is thread-safe. Per-request
  input tensors allocated on the calling thread.
- **Tokenizer:** DJL HuggingFace tokenizer (`ai.djl.huggingface:tokenizers:0.30.0`),
  already a dependency via `RerankerTokenizer`.
- **Model discovery:** `OnnxModelDiscovery` 5-step resolution
  (explicit override → AI Home → sidecar → install dir → dev fallback).

The new `OnnxEmbeddingEncoder` can follow this pattern almost exactly,
differing only in:
- Model-specific inputs (embedding models take `input_ids`,
  `attention_mask`, `token_type_ids`)
- Output extraction (`sentence_embedding` output node vs reranker's
  logits output)
- Batch handling (multiple sequences per forward pass vs reranker's
  single pair)

## Performance Research (2026-03-09)

### Current FFM Path — Architecture & Bottlenecks

The embedding pipeline is a 3-stage actor system:

| Stage | Where | Detail |
|-------|-------|--------|
| Tokenize + chunk | CPU thread pool (`availableProcessors/2` threads) | 512-token chunks, 128-token overlap |
| Embed | Single `llama-embed-actor` thread, GPU lock | Max batch 8, context reset per chunk |
| Normalize | Same actor thread | L2 norm, mean-pool across chunks |

Structural bottlenecks in the llama.cpp path:
- **Batch size capped at 8** (`EmbeddingActor.MAX_BATCH_SIZE`). Only
  single-chunk requests are batchable; multi-chunk docs processed serially.
- **Context reset per chunk** via `llama_kv_cache_clear()` — unnecessary
  for encoder models (no KV cache), but llama.cpp allocates it anyway.
- **`n_ubatch` must equal `n_batch`** for non-causal (encoder) models in
  llama.cpp — no micro-batching or continuous batching possible. Documented
  architectural constraint.
- **Single session, single slot** (`maxSlots(1)`, `maxSessions(1)` in
  `EmbeddingService`). Actor serialization through one GPU thread.
- **No encoder-specific optimizations** — llama.cpp's compute graph is
  autoregressive-first. The community itself recommends `bert.cpp` for
  encoder workloads.

### ONNX Runtime — What Changes

| Dimension | Current (FFM/llama.cpp) | Post-Migration (ORT) |
|-----------|------------------------|---------------------|
| Throughput | Baseline | **1.5-3.9x faster** (up to 13.4x at long sequences) |
| Encoder optimizations | None | EmbedLayerNorm, Attention, BiasGelu, SkipLayerNorm fusions |
| Quantization | GGUF Q8 (decoder-oriented) | INT8 dynamic: **2.9-3.3x CPU speedup** over FP32 |
| Batching | Max 8, no micro-batching | Dynamic batching, scales to batch 256+ |
| KV cache overhead | Allocated but unused | Not allocated (encoders don't need it) |
| Thread safety | Actor serialization (1 thread) | `OrtSession.run()` thread-safe, per-request tensors |
| Java binding | Custom FFM (~10 files) | Official JNI wrapper, <1% overhead |
| Model size | Q8 GGUF: ~150 MB | FP32: 547 MB; INT8: 137 MB (comparable to Q8) |
| GPU support | llama.cpp CUDA/Vulkan | ORT CUDA or DirectML |

### Throughput Projections

Extrapolated from BERT-class (137M param) benchmarks. No published
nomic-embed-text-v1.5 runtime benchmarks exist; directional advantage
is well-established but exact multipliers will vary.

| Scenario | FFM/llama.cpp (est.) | ORT CPU INT8 | ORT GPU (CUDA) |
|----------|---------------------|-------------|----------------|
| Single doc, 512 tokens | ~5-10 ms | ~2-5 ms | ~1-3 ms |
| Batch of 8 docs | ~40-80 ms | ~10-20 ms | ~3-8 ms |
| Bulk index 10K docs | Baseline | **2-4x faster** | **5-10x faster** |
| Queries/sec (CPU) | Not benchmarked | ~2,500 (FastEmbed INT8 ref) | N/A |

**Confidence:** Medium. Extrapolated from BERT-base benchmarks (similar
137M param size), not direct measurements. The biggest architectural
win — removing the single-threaded actor bottleneck — is certain.

### GPU vs CPU for This Model Size

- **CPU ORT with INT8** is competitive with GPU for batch sizes < 8-16.
  Below the crossover, CPU matches GPU due to kernel launch overhead.
- **DirectML on Windows** adds ~2x overhead vs CUDA. May be **slower than
  CPU INT8** for 137M models due to memory copy overhead (open ORT
  issue #20983).
- **CUDA** is the clear winner if available, but requires toolkit install.
- The codebase already handles this: `CrossEncoderReranker` has lazy GPU
  init with CPU fallback + `shouldUseGpu` arbitration via `signalBus`.

### What Doesn't Change

- Chunking logic (512 tokens, 128 overlap, mean-pool across chunks)
- Mean pooling + L2 normalization (still needed; ONNX outputs `last_hidden_state`)
- Query embedding cache (5s TTL)
- VRAM arbitration (`main_gpu_active` MMF flag)

### Risks

- **Model size on disk:** FP32 ONNX is 547 MB vs ~150 MB for Q8 GGUF.
  INT8 ONNX (137 MB) is comparable but needs quality validation.
- **First-inference warmup:** ORT may cache optimized graphs per batch
  size. First call with a new batch shape can be slow.
- **DirectML overhead:** If CUDA unavailable, DirectML may not beat
  CPU INT8 for this model size.

### Sources

- W&B Inference Speed Benchmarking (GPU/CPU, llama.cpp vs ONNX)
- Microsoft ONNX Runtime RAG deployment blog (2025)
- ORT Graph Optimizations + Transformers Optimizer docs
- ORT BERT Optimization on Intel CPU (Microsoft Open Source blog)
- llama.cpp issues #2872, #7712, #4130 (encoder limitations)
- FastEmbed ONNX 2025 benchmarks
- HuggingFace BERT CPU scaling blog (Parts 1 & 2)
- ORT DirectML issue #20983 (transformer performance penalty)

## Decision: Migrate to ONNX Runtime In-Process

### Recommendation: Migrate now

The performance research confirms ONNX Runtime in-process is the better
choice for embedding inference on both architectural and performance grounds:
- **2-4x throughput on CPU** (INT8 quantization + encoder fusions)
- **5-10x with CUDA GPU** for bulk indexing
- Eliminates single-threaded actor bottleneck (`OrtSession.run()` is thread-safe)
- Official Java bindings vs custom FFM code (sole such binding in existence)
- Already in the codebase with proven GPU integration patterns
- Enables removal of ~10 files of FFM binding code
- Framework consolidation (single inference runtime in Worker)

The strongest case is for **bulk indexing** (thousands of documents). For
single-query embedding at search time, the current path is already fast
enough (sub-10ms); improvement would be measurable but not transformative.

Note: ONNX does NOT eliminate pooling/normalization code — the official
model outputs raw `last_hidden_state`, requiring mean pooling + L2 norm
in Java. But this is explicit and testable, unlike the implicit
24-field struct configuration of the FFM binding.

## Migration Plan

### Phase 1: OnnxEmbeddingEncoder

Create an ONNX-based embedding encoder following `CrossEncoderReranker`
patterns.

**New file:**
- `modules/ai-bridge/src/main/java/.../embed/OnnxEmbeddingEncoder.java`

**Responsibilities:**
- Load nomic-embed-text-v1.5 ONNX model via `OrtSession`
- Tokenize input text via DJL HuggingFace tokenizer
- Run inference: `input_ids` + `attention_mask` + `token_type_ids` → `last_hidden_state`
- Apply mean pooling over `last_hidden_state` using `attention_mask`
- Apply L2 normalization to produce unit-length `float[768]`
- Dual CPU+GPU sessions (follow `CrossEncoderReranker` pattern)
- Batch support: multiple texts per forward pass for indexing throughput
- Thread-safe: per-request tensor allocation

**Key design notes:**
- **Mean pooling + L2 norm required.** The ONNX model outputs raw
  `last_hidden_state` (per-token embeddings). The encoder must apply
  mean pooling (masked by `attention_mask`) and L2 normalization. This
  is straightforward (~20 lines) and can be validated against reference
  sentence-transformers output.
- **No `EmbeddingActor` needed.** The actor's tokenize-pool/embed-queue
  pipeline was designed around llama.cpp's API. ONNX RT's `session.run()`
  is thread-safe and doesn't need actor-based concurrency.
- **Chunking stays in `EmbeddingService`.** Long documents are chunked
  before reaching the encoder. The encoder receives individual chunks
  and returns embeddings. `EmbeddingService` handles mean-pooling across
  chunks (this is document-level pooling, not token-level — still needed).

### Phase 2: Model Preparation

Ensure the ONNX model is available and correctly configured.

**Tasks:**
- [ ] Download official `nomic-embed-text-v1.5` ONNX from HuggingFace
  (`nomic-ai/nomic-embed-text-v1.5` — the ONNX export, not the GGUF)
- [ ] Verify `last_hidden_state` output node produces correct per-token
  embeddings; implement mean pooling + L2 normalization in Java
- [ ] Add ONNX model to `OnnxModelDiscovery` resolution chain
  (new model type: `EMBEDDING`)
- [ ] Add HuggingFace tokenizer config (`tokenizer.json`) for DJL
- [ ] Test INT8 quantized variant for CPU-only performance
- [ ] Benchmark: ONNX FP32 vs INT8 vs current GGUF Q8 on ArguAna

### Phase 3: Integration and Switchover

Wire `OnnxEmbeddingEncoder` into the embedding provider system.

**Tasks:**
- [ ] Create `OnnxEmbeddingProvider` implementing `EmbeddingProvider` SPI
- [ ] Register in `EmbeddingProviderRegistry` (ServiceLoader)
- [ ] Wire VRAM arbitration: `shouldUseGpu` checks `signalBus.isMainGpuActive()`
- [ ] Add config: `JUSTSEARCH_EMBED_BACKEND=onnx` (default) vs `llama` (fallback)
- [ ] Run full BEIR eval suite (ArguAna + SciFact) with ONNX backend
- [ ] A/B compare ONNX vs FFM embedding quality per-document
  (validation protocol: cosine similarity > 0.99 vs reference)
- [ ] Measure indexing throughput (docs/sec) — target: no regression vs FFM

### Phase 4: FFM Cleanup

After ONNX backend is validated and default:

**Remove:**
- `NativeLlamaBinding.java` — FFM bridge to llama.cpp
- `NativeStructs.java` — C struct layouts
- `AbiAudit.java` — ABI verification
- `EmbeddingActor.java` — pipelined embedding with batching
- `GenerationActor.java` — unused generation actor
- `LlamaService.java` — orchestrator for both actors
- `SharedModel.java` / `SharedContext.java` — ref-counted native resources
- `BridgeAllocator.java` — native memory management
- `LlamaEmbeddingProvider.java` — FFM-based provider
- Platform-specific native libraries (llama.dll, libllama.so, libggml.so)

This removes ~10 files and the entire native binary distribution
pipeline for embeddings. The Worker converges on ONNX Runtime as its
sole inference framework.

### Immediate fix (apply now, before migration)

Apply the defensive `pooling_type = MEAN` fix in `NativeLlamaBinding`
as a stopgap. This is one line, zero risk, and ensures correct behavior
until the migration lands:

```java
// NativeLlamaBinding.java, after line 300:
if (embeddings) {
  setInt(params, CONTEXT_PARAMS_LAYOUT, "pooling_type", 1);
}
```

Also verify the GGUF metadata:
```bash
python -m gguf.scripts.gguf_dump <model.gguf> | grep -i pool
```

## Priority and Sequencing

**Immediate (completed 2026-03-09):**
- [x] One-line `pooling_type = MEAN` fix in `NativeLlamaBinding.newContext()`
  (defensive — GGUF metadata was already correct)
- [x] Verify GGUF metadata: `nomic-bert.pooling_type = 1` (MEAN) confirmed
- [ ] Re-run ArguAna dense-only to validate (expected: no change, since
  pooling was already correct — but confirms the fix is harmless)

**Root cause investigation (all cleared 2026-03-09):**
- [x] Embedding comparison: FFM path vs sentence-transformers reference
  for 10 sample texts — **0.998+ cosine similarity** (PASS). Also validated
  llama-server path and long texts (~300 words). All paths equivalent.
- [x] `evalTokens()` logits flag: only last token flagged, but llama.cpp
  auto-overrides for embedding models ("overriding" log message observed).
  Mean pooling pools ALL tokens regardless of logits flags. **Cleared.**
- [x] Tokenization comparison: 0.998+ cosine similarity proves tokenization
  is effectively identical between FFM and reference. **Cleared.**

**Conclusion:** The entire FFM embedding inference path is validated correct.
The 34-39% ArguAna quality gap is NOT in embedding inference — it must be
in the eval pipeline (document ingestion, HNSW indexing, retrieval/scoring,
or eval metric computation). This is a different investigation scope.

**Short-term (next development slot):**
- [ ] Phase 1: `OnnxEmbeddingEncoder` (~1-2 days, following reranker blueprint)
- [ ] Phase 2: Model preparation and benchmarking (~0.5 days)

**Medium-term:**
- [ ] Phase 3: Integration, switchover, and eval validation (~1 day)
- [ ] Phase 4: FFM cleanup (~0.5 days)

**Estimated total:** 3-4 days for full migration, plus eval validation.

## Risk Mitigation

- **A/B validation:** Run ArguAna dense-only eval with both FFM and ONNX
  backends. ONNX should match or exceed published reference scores.
- **Throughput regression test:** Measure embedding throughput (docs/sec)
  during bulk indexing for both backends. Target: no regression (ONNX
  should be faster for encoder models).
- **Fallback:** Keep `LlamaEmbeddingProvider` registered during transition.
  Config switch `JUSTSEARCH_EMBED_BACKEND=llama` reverts to FFM path.
- **Gradual rollout:** ONNX backend can be tested on a single dataset
  before becoming the default.

## Dependencies

- tempdoc 251: source of all eval evidence
- tempdoc 253: model quality improvements (overlapping concern)
- `EmbeddingProvider` SPI: migration entry point
- `modules/reranker`: ONNX Runtime patterns (blueprint for Phase 1)
- `CrossEncoderReranker.java`: GPU session management patterns
- `SpladeEncoder.java`: second ORT reference implementation
- `OnnxModelDiscovery.java`: model resolution (extend for embedding model)
- `gradle/libs.versions.toml`: ONNX Runtime 1.19.2, DJL Tokenizers 0.30.0

## Upstream References

- llama.cpp PR #5500 (commit `4524290`, Feb 2024): Pooling metadata in GGUF
- llama.cpp discussion #9980: Sentence-transformers vs llama.cpp divergence
- llama.cpp issue #8956: `POOLING_TYPE_NONE` returns NULL from `_seq()`
- llama.cpp issue #16451: Silent pooling misconfiguration bug
- llama.cpp issue #15406: Batch embedding endpoint regression
- llama.cpp issue #17636: Segfault with repeated characters
- llama.cpp issue #19217: Memory leak with LoRA
- llama.cpp discussion #9276: No breaking-change notification system
- llama.cpp PR #11213: Major KV cache / context refactor
- `nomic-ai/nomic-embed-text-v1.5-GGUF` commit `18d1044`: Pooling metadata
- ONNX Runtime Java API: `com.microsoft.onnxruntime:onnxruntime` (1.19.2)
- nomic-embed-text-v1.5 ONNX: `nomic-ai/nomic-embed-text-v1.5` (HuggingFace)

## Conclusion (2026-03-09)

**Status: CONCLUDED — Embedding inference path validated correct.**

This investigation conclusively cleared the FFM embedding inference path as
the cause of the 34-39% ArguAna quality gap. All four root cause candidates
(RC1-RC4) were investigated and cleared. A three-way embedding comparison
(sentence-transformers, llama-server, FFM) showed 0.998+ cosine similarity
across all paths, proving the embeddings are correct.

**Deliverables:**
1. Defensive `pooling_type = MEAN` fix in `NativeLlamaBinding.newContext()`
2. Self-validating `EmbeddingExtractionTest.java` (6 tests)
3. Reference embedding script `embed_reference.py`
4. ONNX RT migration plan (Phases 1-4) documented above
5. Corrected ONNX model findings (`last_hidden_state`, not `sentence_embedding`)

**ArguAna quality gap — resolved in tempdoc 251 (2026-03-09):**

The 34-39% gap was investigated in the eval pipeline (tempdoc 251,
finding 8). Root cause decomposition:

| Factor | nDCG impact | % of gap |
|--------|-------------|----------|
| Self-retrieval (BEIR `ignore_identical_ids`) | -0.118 | 54% |
| K truncation (K=10 vs K=100) | -0.009 | 4% |
| Remaining (quantization + HNSW variance) | -0.092 | 42% |

ArguAna queries ARE corpus documents; BEIR's default evaluation filters
self-retrieval from metrics, but our eval pipeline does not. This
methodology difference inflates the apparent gap. The true quality gap
is 0.092 nDCG, attributable to Q8 quantization and HNSW graph variance
on an adversarial similarity task. Not a bug in the search engine or
eval pipeline. See tempdoc 251 finding 8 for full analysis.

**Remaining open question:**
- ONNX RT migration: justified on architectural grounds, can proceed as
  a separate workstream independent of the quality gap investigation.
