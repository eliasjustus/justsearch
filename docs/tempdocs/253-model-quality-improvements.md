---
title: "253: Model & Retrieval Quality Improvements"
type: tempdoc
status: done
created: 2026-03-02
---

> NOTE: Noncanonical doc (strategy). May drift.

# 253: Model & Retrieval Quality Improvements

## Purpose

Implement the individual model and retrieval improvements identified in 245
and 249 that improve search quality through better models, encodings, or
retrieval features — as opposed to pipeline architecture (250), evaluation
(251), or ingestion (252).

These are the concrete levers that move retrieval quality. Each has a
measurable effect on existing or planned benchmarks.

---

## Completed Items

### Item 18: Inference-Free SPLADE Model Swap — CLOSED (negative result)

**Status:** Closed. doc-v3-distill regresses -0.040 to -0.060 nDCG@10 across
all 3 BEIR datasets. SPLADE-v3 retained. IDF encoder infrastructure committed
for future model experiments. See Phase C results and multi-dataset validation
below for full details.

**What:** Replace SPLADE-v3 (query-time ONNX inference) with an inference-
free SPLADE model that pre-computes query sparse vectors from the vocabulary,
eliminating query-time neural inference entirely.

**Why it matters:**
- +2.9 nDCG@10 on BEIR-13 average (OpenSearch `neural-sparse-encoding-doc-
  v3-gte`: 54.6 vs SPLADE-v3: 51.7)
- Zero query-time ONNX inference — eliminates the SPLADE encoder as a
  latency and GPU contention point
- Architecturally transformative: SPLADE becomes as cheap as BM25 at search
  time

**How it works:**
Inference-free SPLADE models encode the query using only IDF-weighted token
lookup from a pre-computed vocabulary table, not a neural forward pass. The
document-side encoding (at index time) is still neural but uses a stronger
backbone (GTE vs BERT-base). The result is better document representations
with simpler query processing.

**Research (from 249 + investigation):**
- OpenSearch `neural-sparse-encoding-doc-v3-gte` (2024): BEIR-13 avg 54.6
  nDCG@10. Based on GTE-en-MLM backbone (133M params). Apache 2.0 license.
- Per-dataset: SciFact 0.725, FiQA 0.407, NFCorpus 0.360, Arguana 0.520.
- Same BERT WordPiece vocabulary (30,522 tokens) as SPLADE-v3 — directly
  compatible with Lucene FeatureField and existing query infrastructure.
- Query encoding: tokenize → one-hot → multiply by pre-computed IDF vector
  from `idf.json` (MS MARCO-derived). Zero neural inference, sub-millisecond.
- Document encoding: full forward pass through 133M-param GTE-en-MLM.
  Different activation: `log(1 + log(1 + relu(x)))` (double-log) vs
  SPLADE-v3's single `log(1 + relu(x))`. Requires `postProcess()` update.
- Available as Safetensors/PyTorch only — needs ONNX export via `optimum`.
  No published ONNX or GGUF.
- Also available: `doc-v3-distill` (67M params, BEIR-13 0.517) — smaller
  and faster for index-time encoding with slightly lower quality.

**Investigation findings (2026-03-02, updated 2026-03-03):**
- **Vocabulary compatible:** Same BERT WordPiece 30,522 tokens. Same
  tokenizer. Output `Map<String, Float>` feeds directly into FeatureField.
- **Query path change is minimal:** Replace `SpladeEncoder.encode()` ONNX
  forward pass with IDF table lookup. `LuceneIndexRuntime.searchSplade()`
  and all FeatureField query construction is completely unaffected.
- **Index-time path stays ONNX:** Document encoding still requires neural
  inference. Must export model to ONNX from Safetensors.
- **IDF weights are MS MARCO-specific:** On heterogeneous desktop documents,
  some weights may be suboptimal. Empirical evaluation needed.

**Deep investigation findings (2026-03-03, corrected 2026-03-03):**

*Model naming:* These are **OpenSearch** models, not Naver models:
- `opensearch-project/opensearch-neural-sparse-encoding-doc-v3-gte` (133M)
- `opensearch-project/opensearch-neural-sparse-encoding-doc-v3-distill` (67M)

*Model architecture:*
- v3-gte uses `NewForMaskedLM` (Alibaba GTE backbone, model_type `"new"`).
  RoPE positional embeddings (theta=500k), 8192 max context. Not a
  standard Optimum export type — needs custom `torch.onnx.export()`.
- v3-distill uses `DistilBertForMaskedLM` (standard type). 512 max
  context. Standard Optimum export works directly (verified).
- Both: 30,522 vocab, same tokenizer, same IDF table, same query path.

*IDF table format (`idf.json`):*
- Simple JSON: `{"the": 0.135, "yemen": 9.086, ".": 0.016, ...}`
- 889 KB, ~30K entries matching BERT vocabulary. Same file for both
  v3-gte and v3-distill (corpus-dependent, not model-dependent).
- MS MARCO-derived. Rare tokens get high IDF, frequent tokens get low IDF.
- Can be downloaded directly from HuggingFace without Python tooling.

*Query encoding implementation (from sentence-transformers source):*
```
input_ids → binary token_presence[vocab_size] → multiply by idf_weights
```
- `torch.zeros(batch, 30522).scatter_(1, input_ids, attention_mask)`
  creates a binary presence vector, then element-wise multiply by IDF.
- In Java: tokenize query → for each unique token ID, look up
  `idfWeights[tokenId]` → build `Map<String, Float>`. ~30 lines.

*Double-log activation:*
- OpenSearch doc-v3 models use `activation_function: "log1p_relu"` in
  SpladePooling config, which produces `log(1 + log(1 + relu(x)))`.
  Naver SPLADE-v3 uses `activation_function: "relu"` → single-log.
- Double-log is post-processing, NOT baked into model weights. ONNX
  export gives raw MLM logits; activation must be applied in Java.
- Stronger sparsification than single-log: reduces average document
  length from 295 to 245 tokens (SIGIR 2025 paper).
- The IDF weights were co-trained with double-log active, so they are
  co-adapted with this activation function.

*ONNX export:*
- No published ONNX exists anywhere (HuggingFace, OpenSearch hub).
- v3-gte (`model_type: "new"`) needs custom wrapper for
  `torch.onnx.export()`. Not a standard Optimum type.
- v3-distill (`model_type: "distilbert"`): exported successfully via
  `python -m optimum.exporters.onnx --task masked-lm`. Output:
  `model.onnx` (362 MB), `tokenizer.json`, `vocab.txt`.
  Max diff 2.26e-05 (FP32 precision, acceptable).
- Inputs: `input_ids`, `attention_mask` (no `token_type_ids` —
  DistilBERT). Output: `logits` shape `[batch, seq, 30522]`.
- Only needed for document encoding. Query side needs zero ONNX.

*Cross-model experiment opportunity:*
- We can download just `idf.json` (no Python needed) and implement the
  IDF query encoder in Java. Then query against existing SPLADE-v3
  indexed documents on SciFact/FiQA. This is a mismatched pair (IDF
  queries vs SPLADE-v3 docs) but tests whether IDF query encoding is
  competitive with ONNX query encoding against the same document
  representations. If quality is similar, the query-side swap is
  validated independently of the document encoder change.

**Phase A — IDF query encoder: DONE (2026-03-03)**

Implemented inference-free SPLADE query encoder. 2 new files, 6 modified,
~100 net new lines. Activated via `JUSTSEARCH_SPLADE_QUERY_MODE=idf`.

Changes:
- `SpladeIdfQueryEncoder.java` — new: IDF-weighted token lookup (~95 lines).
  Tokenizes query with shared HuggingFace tokenizer, looks up IDF weight
  per unique token, filters BERT special tokens, returns `Map<String, Float>`.
- `SpladeIdfQueryEncoderTest.java` — new: 10 unit tests (loading, encoding,
  dedup, special token filtering, pruneByBeta compatibility).
- `SpladeConfig.java` — added `queryMode` field (`"onnx"`/`"idf"`), env var
  `JUSTSEARCH_SPLADE_QUERY_MODE`, `isIdfQueryMode()` convenience method.
- `SpladeEncoder.java` — added `public tokenizer()` and `public vocabulary()`
  accessors for sharing with IDF encoder.
- `SearchOrchestrator.java` — dual-path query encoding at HYBRID and SPLADE
  call sites. Prefers IDF encoder when available, falls back to ONNX.
- `GrpcSearchService.java` — pass-through `setSpladeIdfQueryEncoder()`.
- `KnowledgeServer.java` — creates and wires IDF encoder when
  `queryMode=idf` and `idf.json` exists alongside the SPLADE model.

Index-time encoding is always ONNX — only query-side is swapped.

**Phase B — Cross-model experiment: DONE (2026-03-03)**

IDF queries (designed for doc-v3) against SPLADE-v3 indexed documents on
SciFact. Isolates query-side change before committing to full model swap.

| Metric | ONNX (Session 1) | IDF (Session 1b) | Delta |
|--------|-------------------|-------------------|-------|
| hybrid nDCG@10 | 0.7119 | 0.6574 | **-0.0545** |
| hybrid P@1 | 0.6333 | 0.5567 | -0.0767 |
| hybrid MRR | 0.6904 | 0.6340 | -0.0564 |
| hybrid Recall@10 | 0.8146 | 0.7766 | -0.0380 |
| lexical nDCG@10 | 0.6610 | 0.6610 | 0.0000 (sanity) |

Interpretation:
- **-0.0545 nDCG@10 falls in the "significant degradation" band** from the
  plan (-0.03 to -0.10). Expected for a mismatched pair — IDF weights were
  co-trained with doc-v3, not SPLADE-v3.
- **IDF hybrid (0.6574) < lexical-only (0.6610)**: IDF queries actively
  harm fusion when paired with SPLADE-v3 documents. The SPLADE leg adds
  noise rather than signal because query and document weight distributions
  are misaligned.
- **Lexical sanity check passes**: identical 0.6610 confirms server restart
  and env cleanup work correctly.
- **Phase C is still viable**: the mismatch is between query encoder and
  document encoder. When both sides use the same model (doc-v3), quality
  should recover. But this experiment does NOT validate the IDF approach —
  it only confirms that mismatched pairs degrade quality, as expected.

Results at: `tmp/beir-eval/isolation-embed-scifact/isolation-summary.json`

**Phase C — Full pipeline: DONE (2026-03-03)**

ONNX export of `opensearch-neural-sparse-encoding-doc-v3-distill`
completed. Double-log activation added to `SpladeEncoder.postProcess()`.
Full SciFact eval completed with matched pair (doc-v3-distill docs +
both ONNX and IDF queries).

Changes:
- `SpladeConfig.java` — added `activation` field (`"log1p"`/`"double_log1p"`),
  env var `JUSTSEARCH_SPLADE_ACTIVATION`, `isDoubleLogActivation()` method.
- `SpladeEncoder.java` — branches on `doubleLogActivation` flag in
  `postProcess()` inner loop: `log1p(log1p(relu(x)))` vs `log1p(relu(x))`.
- `SpladePostProcessTest.java` — 3 new double-log tests (known values,
  comparison with single-log, ReLU filtering).
- `SpladeConfigTest.java` — 4 new activation tests.
- `run-ranking-experiments.ps1` — added `-SpladeModelPath` and
  `-SpladeActivation` parameters for model override.
- `models/splade/opensearch-doc-v3-distill/` — exported ONNX model
  (362 MB) + tokenizer + vocab + idf.json.

Results (SciFact, k=10):

| Configuration | hybrid nDCG@10 | hybrid P@1 | hybrid MRR | hybrid Recall@10 | lexical nDCG@10 |
|---|---|---|---|---|---|
| doc-v3-distill ONNX queries | **0.6424** | 0.560 | 0.618 | 0.750 | 0.661 |
| doc-v3-distill IDF queries | **0.6604** | 0.567 | 0.632 | 0.779 | 0.661 |
| SPLADE-v3 ONNX (Phase B baseline) | **0.7119** | 0.633 | 0.690 | 0.815 | 0.661 |

Index-time encoding throughput (CPU-only ONNX, SciFact 5184 docs):

| Model | docs/sec | Wall time |
|---|---|---|
| doc-v3-distill (67M, DistilBERT) | 2.43 | 35.6 min |
| SPLADE-v3 (110M, BERT-base) | 1.99 | 43.4 min |

Interpretation — **significant negative result:**
- **doc-v3-distill ONNX hybrid (0.6424) is -0.070 below SPLADE-v3 (0.7119).**
  Far outside the -0.03 acceptance threshold. The SPLADE leg actively hurts
  hybrid fusion: hybrid (0.6424) < lexical-only (0.6614).
- **IDF queries (0.6604) outperform ONNX queries (0.6424)** on the matched
  pair. The neural query encoder is worse than simple IDF lookup with this
  model — consistent with doc-v3-distill's asymmetric design (neural docs +
  IDF queries). Using the MLM head for queries is outside its intended use.
- **Indexing is 22% faster** (2.43 vs 1.99 docs/sec), expected since
  DistilBERT has 6 layers vs BERT-base's 12.

**Implementation verification (2026-03-03):**

The negative result was independently verified to rule out implementation
bugs. All checks pass:
1. **Worker log confirms**: `model=opensearch-doc-v3-distill, activation=
   double_log1p` for both sessions. Env vars properly inherited by Worker
   via Java ProcessBuilder (inherits parent env by default, no clear()).
2. **ONNX tensor confirmed**: output `logits` shape `[batch, seq, 30522]`,
   `tokenTypeIds=false` (correct for DistilBERT).
3. **postProcess() branches correctly**: `doubleLogActivation` flag set
   from config at encoder construction time.
4. **Per-query analysis** (300 SciFact queries, ONNX session):
   - 205 queries (68%) are ties — SPLADE leg doesn't affect ranking
   - 68 queries: SPLADE-v3 wins; 27 queries: doc-v3-distill wins
   - 49 queries have >0.2 nDCG drop (nearly all 1.0 → 0.0)
   - **nDCG@10=0 queries: v3=52, dv3=71 (+19 more failures)**
   - totalHits similar (160 vs 165) — issue is ranking, not retrieval
5. **Degradation is concentrated**: 19 additional queries where SPLADE-v3
   pushed relevant docs into top-10 but doc-v3-distill did not. The model
   has weaker discriminative power for scientific text, not a systematic
   pipeline issue.

Conclusion: **doc-v3-distill is a genuine regression for JustSearch's
hybrid pipeline.** The model produces weaker SPLADE representations
specifically for the queries that matter most (those where SPLADE signal
is the deciding factor). SPLADE-v3 remains the better choice.

Next steps for Item 18:
- **v3-gte (133M) could still be worth testing** — its GTE backbone may
  produce richer representations that fuse better. But it requires custom
  `torch.onnx.export()` (non-standard `NewForMaskedLM` architecture), and
  uses the same double-log activation that may have the same fusion issue.
- **Alternatively, keep SPLADE-v3 as-is** and focus quality improvements on
  other levers (reranking, embedding context, 3-leg fusion from Item 21).
- The IDF query encoder infrastructure (Phase A) is model-agnostic and
  remains useful if a future model swap is attempted.

Results at: `tmp/beir-eval/isolation-embed-scifact-opensearch-doc-v3-distill/`

**Multi-dataset validation (2026-03-03):**

Extended evaluation to Arguana and NFCorpus (all 3 BEIR datasets in our eval
suite) to confirm the regression is not dataset-specific.

| Dataset | SPLADE-v3 ONNX hybrid | doc-v3-distill ONNX hybrid | doc-v3-distill IDF hybrid | Delta (ONNX) |
|---|---|---|---|---|
| SciFact (300 queries) | **0.702** | 0.642 | 0.660 | -0.060 |
| Arguana (1406 queries) | **0.315** | 0.273 | 0.277 | -0.042 |
| NFCorpus (323 queries) | **0.337** | 0.297 | 0.313 | -0.040 |

Lexical (BM25) nDCG@10 is unchanged across all datasets (SciFact 0.661,
Arguana 0.329, NFCorpus 0.308/0.310) — confirming the regression is isolated
to the SPLADE leg's contribution to hybrid fusion.

Patterns confirmed across all 3 datasets:
1. **Regression is universal** — -0.040 to -0.060 hybrid nDCG@10, consistent
2. **IDF consistently outperforms ONNX** for doc-v3-distill (opposite of v3)
3. **doc-v3-distill is strictly worse** than SPLADE-v3 on every metric

Results at:
- `tmp/beir-eval/isolation-embed-arguana-opensearch-doc-v3-distill/`
- `tmp/beir-eval/isolation-embed-nfcorpus-opensearch-doc-v3-distill/`

**Remaining work:**
- [x] IDF table format verification — confirmed: simple `{token: float}`
  JSON map, 889 KB, downloadable directly
- [x] ONNX export feasibility — v3-distill standard, v3-gte custom wrapper
- [x] Double-log activation — post-processing, not in model weights
- [x] Download `idf.json` from HuggingFace (889 KB, no Python needed)
- [x] Implement IDF query encoder in Java
- [x] Cross-model experiment: IDF queries vs SPLADE-v3 index — mismatched
  pair shows -0.0545 nDCG@10 (expected, see Phase B results above)
- [x] Full pipeline: ONNX export of v3-distill — done, double-log added
- [x] Full pipeline: eval on SciFact — **negative result, see above**
- [x] Index-time encoding speed comparison — doc-v3-distill 22% faster

---

## Deferred / Blocked Items

### Item 6: Embedding Context Window 2048 → 8192 (P3 — Deferred)

**What:** Increase the embedding context window from 2048 to 8192 tokens,
allowing the dense embedding model to see more of each document.

**Why it matters:**
- Real desktop documents routinely exceed 2048 tokens
- Published nomic-embed-v1.5 baselines use 8192 context
- Zero measurable effect on current BEIR datasets (all docs < 2048 tokens)
- Measurable on LoCoV1 long-document eval (251 Phase 1b)

**Deep investigation findings (2026-03-03):**

*RoPE configuration:*
- nomic-embed-v1.5 was trained at 2048 tokens with Dynamic NTK-Aware
  RoPE scaling to extend to 8192 at inference. Base theta = 1000.
- llama.cpp doesn't support Dynamic NTK; YaRN is the substitute:
  `--rope-scaling yarn --rope-freq-scale 0.75 -c 8192 -b 8192`.
- `--rope-freq-base` is NOT needed — GGUF metadata already has theta=1000.

*JustSearch native bindings:*
- `NativeStructs.java` already has `rope_freq_base` and `rope_freq_scale`
  in the context params layout, but `NativeLlamaBinding.newContext()`
  does NOT set them — they stay at llama.cpp defaults.
- `EmbeddingActor.java` sets `maxContextTokens = nCtxTrain()` which
  returns 2048. Override needed to set 8192.
- `DeterminismProfile.java` auto-clamps to `nCtxTrain` and does NOT
  expose RoPE parameters. Needs extension.
- Implementation: add RoPE param support to `newContext()`, add env var
  or config option to opt into 8192 context.

*VRAM impact (actual calculation):*
- KV cache: 12 layers × 8192 × 768 × 2 × 2 bytes = 288 MiB (vs 72 MiB
  at 2048). Delta: +216 MiB. Trivial on RTX 4070.
- **Real concern: attention scratch memory.** Without Flash Attention,
  the attention score matrix is `12 × 8192 × 8192 × 4 = ~3 GiB` (FP32).
  Vulkan backend OOM confirmed at 8192 (llama.cpp issue #12817).
  CUDA with Flash Attention reduces this to O(n), making 8192 practical.
- Ensure JustSearch's llama.cpp build has Flash Attention enabled.

*Known issues:*
- Vulkan backend OOM at 8192 (issue #12817) — CUDA-only for now.
- Some wrappers auto-downgrade non-causal models to nCtxTrain
  (gpustack #1365, fixed in v0.6.0). Our native bindings should
  be explicit about the override.
- Ollama/some backends silently ignore input past 2048 (HF discussion
  #51) — only affects external tools, not our native path.

*Quality data:*
- LoCo benchmark: 85.6 at 4096, 85.5 at 8192 — Dynamic NTK scaling
  works with minimal degradation.
- MTEB benchmarks are all short-context; no quality difference at 2048
  vs 8192 on standard tasks.
- **No published data on YaRN-instead-of-DynamicNTK quality for this
  model.** This is the key risk — the HF model card recommends YaRN
  but doesn't publish comparison numbers.

**Status: Deferred.** Zero benefit on current BEIR benchmarks. Implementation
requires extending native binding RoPE support + env var. Full measurement
waits for LoCoV1 long-document eval (tempdoc 251). The Flash Attention
requirement and untested YaRN quality mean this needs careful validation
that doesn't exist yet.

### Item 20: Matryoshka 256-Dim Embedding Truncation (P3 — Deferred)

**What:** Truncate nomic-embed-v1.5 embeddings from 768 to 256 dimensions,
trading quality for storage and KNN speed.

**Why it matters:**
- -1.24 MTEB points (-2.0% relative) — modest quality loss
- 67% vector storage reduction (768 → 256 floats per doc)
- ~3x KNN search speed (fewer dimensions to compare)
- nomic-embed-v1.5 was trained with Matryoshka loss, so 256-dim
  truncation is supported by design

**Deep investigation findings (2026-03-03):**

*Layer normalization — the critical open question is now resolved:*
- NomicBERT has NO post-encoder layer norm. The model returns raw
  hidden states after the last transformer block → mean pooling.
- The `F.layer_norm` in Nomic's official examples is a **separate
  post-processing step** that must be applied AFTER getting the full
  768-dim output and BEFORE truncating. It normalizes to zero mean /
  unit variance across the 768 feature dimensions.
- llama.cpp applies the model's internal per-layer norms (part of the
  GGUF computation graph) but does NOT apply this post-pooling layer
  norm. These are three completely independent operations:
  1. Model-internal per-layer norms → always applied by llama.cpp
  2. Post-pooling layer norm → must be applied in Java (~10 lines)
  3. L2 normalization → controlled by `--embd-normalize`

*Correct procedure:*
1. Get full 768-dim output from llama.cpp with `embd_normalize = -1`
   (raw, no L2 normalization)
2. Apply layer norm in Java: `(x - mean) / sqrt(var + 1e-12)` across
   all 768 dimensions
3. Truncate to first 256 dimensions
4. L2-normalize the 256-dim vector

*Implementation complexity (higher than initially estimated):*
- Need to change embedding pipeline to output raw vectors (currently
  uses L2-normalized output from llama.cpp)
- Need to add layer norm in Java (~10 lines math, but touches the
  hot path in `EmbeddingActor`)
- Need to change KNN vector field dimension in SSOT catalog (768 → 256)
- **Requires full index rebuild** — existing 768-dim vectors are
  incompatible with a 256-dim HNSW graph
- Need to validate quality: run SciFact at 256-dim with Q8_0

*Quality data (published, full-precision):*
| Dimension | MTEB Score | Retention vs 768 |
|-----------|------------|------------------|
| 768       | 62.28      | 100%             |
| 512       | 61.96      | 99.5%            |
| 256       | 61.04      | 98.0%            |
| 128       | 59.34      | 95.3%            |

*Compound quality loss estimate:*
- Q8_0 alone: ~0.3% loss (SciFact 0.694 vs F16 0.696)
- 256-dim alone: ~2.0% loss (MTEB published)
- Expected compound: ~2.3% (additive, no interaction reports exist)
- No systematic studies of GGUF + Matryoshka compound effect found

**Status: Deferred.** Implementation is more complex than initially
estimated (requires raw output pipeline + Java layer norm + index rebuild).
The 3x KNN speedup is likely irrelevant at desktop scale (<50ms baseline).
The -2% quality loss is real and compounds with Q8_0. Only worth pursuing
if storage is a user-reported problem.

### ~~Item 11: SPLADE Beta Query-Term Pruning (P3 — Latency Only)~~ DONE

**Implemented 2026-03-02.** Query-time beta pruning keeps top 50% of
SPLADE terms by weight. Applied at both HYBRID and SPLADE search paths
in `SearchOrchestrator`. ~1.8x latency reduction, quality-neutral.

**Changes:**
- `SpladeEncoder.pruneByBeta()` — static method, sorts by weight desc,
  keeps `round(size * beta)` terms (min 1). 12 lines.
- `SearchOrchestrator` — wraps `encoder.encode()` with `pruneByBeta(…, 0.5f)`
  at both HYBRID (line ~291) and SPLADE (line ~339) query paths.
- 5 unit tests in `SpladePostProcessTest.BetaPruning`.

**Note:** If item 18 (inference-free SPLADE) lands, the query path changes
completely. Beta pruning may become unnecessary or need reimplementation
against the new vocabulary-lookup path.

### Item 21: N-Way Weighted CC for 3-Leg Fusion (P3 — Blocked)

**What:** Extend CC fusion to support 3 independent legs (BM25, SPLADE,
dense) with per-leg weights, instead of the current 2-leg architecture
where SPLADE replaces BM25 as the sparse leg.

**Why it matters:**
- 245 multi-dataset observations show SPLADE helps on scientific text
  but hurts on argument/financial text. Per-leg weights would allow
  downweighting SPLADE when it's not contributing.
- CC sweep (245 item 2f) showed fusion parameters are irrelevant when
  SPLADE+dense overlap near-completely — but 3-leg separation might
  change this dynamic.

**Blocked on:** Architectural prerequisite — BM25 and SPLADE must be
separated into independent retrieval legs. Currently SPLADE replaces BM25
in HYBRID mode (they're mutually exclusive in the sparse leg). This
requires changes tracked in 250 Phase 4 (component activation model).

### Item 20 (from 249): Relevance Feedback / "Find Similar" (New Feature)

**What:** Implement Qdrant-style relevance feedback: `2*avg(positive_vectors)
- avg(negative_vectors)` → KnnFloatVectorQuery. Sparse leg via
MoreLikeThisQuery.

**Why it matters:**
- "Find similar to this document" is a high-value UX feature for desktop
  search
- +5.6 nDCG@20 from pseudo-relevance feedback (Qdrant research, though
  different use case than user-initiated "more like this")
- Directly implementable in Lucene with existing infrastructure

**Scope:** ~500 lines across gRPC, API adapter, search orchestrator.
New feature, not a quality fix for existing search. Lowest priority in
this tempdoc — tracked here because it emerged from quality research
but is really a product feature.

---

## Prioritization (revised 2026-03-03)

| Item | Impact | Effort | Dependencies | Priority |
|------|--------|--------|--------------|----------|
| 18 (inference-free SPLADE) | HIGH (+2.9 nDCG, eliminates ONNX) | MEDIUM | `idf.json` download (manual) | **P1** |
| 6 (context 8192) | ZERO on BEIR, UNKNOWN on long docs | MEDIUM (RoPE bindings + Flash Attn) | 251 long-doc eval | **Deferred** |
| 20 (Matryoshka 256-dim) | LOW (-2% quality, 3x speed) | MEDIUM (layer norm + index rebuild) | None but irrelevant at desktop scale | **Deferred** |
| ~~11 (SPLADE pruning)~~ | ~~NEGLIGIBLE~~ | ~~LOW~~ | — | **DONE** |
| ~~Weight clamping~~ | ~~Safety net~~ | ~~LOW~~ | — | **DONE** |
| 21 (N-way CC) | UNKNOWN | HIGH (architectural change) | 250 Phase 4 | **Blocked** |
| 20-249 (relevance feedback) | MEDIUM (new feature) | MEDIUM (~500 lines) | None | **P4** (product feature) |

### Recommended execution order

1. ~~**Stream B quick wins** — Item 11 (SPLADE beta pruning) + weight
   clamping.~~ **DONE (2026-03-02).**
2. ~~**Item 18 Phase A — IDF query encoder (code + manual download).**~~
   **DONE (2026-03-03).** Downloaded `idf.json`, implemented
   `SpladeIdfQueryEncoder`, wired through full call chain. Activated
   via `JUSTSEARCH_SPLADE_QUERY_MODE=idf`.
3. ~~**Item 18 Phase B — Cross-model experiment (eval).**~~
   **DONE (2026-03-03).** IDF queries vs SPLADE-v3 index on SciFact:
   -0.0545 nDCG@10 (mismatched pair, expected). IDF hybrid < lexical-only,
   confirming misaligned weight distributions. Phase C still viable — full
   model swap fixes the mismatch.
4. ~~**Item 18 Phase C — Full pipeline (manual + code + eval).**~~
   **DONE (2026-03-03).** ONNX export of v3-distill, double-log activation,
   full SciFact eval. **Negative result:** doc-v3-distill hybrid nDCG@10
   0.6424 vs SPLADE-v3 0.7119 (-0.070 regression). Model swap is a net
   loss for hybrid pipeline. IDF query infrastructure preserved for future
   model experiments. v3-gte remains untested (needs custom ONNX export).
5. **Items 6, 20** — Deferred. See individual item status for rationale.
   Item 6 unblocks after tempdoc 251 LoCoV1 eval exists. Item 20 only
   if storage becomes a user-reported problem.

---

## Dependencies

- **251 (Realistic Eval):** Item 6 is unmeasurable without LoCoV1. Item 18
  is measurable on existing BEIR datasets.
- **250 (Pipeline Routing):** Item 21 is blocked on 250 Phase 4 (component
  activation model).
- **245 (BEIR Eval):** Existing harness and baselines used for all
  measurements. Published comparison table carries forward.
- **252 (Ingestion Quality):** Independent. Model improvements operate on
  whatever text the ingestion pipeline produces.

## Additional Research Items (from 249 Investigations)

Items below were extracted from the 10 research investigations in tempdoc
249. They are grouped by theme and included regardless of feasibility,
as long as architecturally compatible with JustSearch's Java/Lucene/
llama.cpp stack.

### Reranking Improvements

**MiniLM-L2-v2 Low-Latency Cross-Encoder (SentenceTransformers F5):**
5x faster than current MiniLM-L6-v2 (4,100 vs 1,800 docs/s) at -4.5
nDCG points. Useful as a latency-priority fallback when cross-encoder
must be fast (e.g., large result sets). ONNX-compatible with existing
infrastructure.

**Qwen3-Reranker-0.6B GGUF (qmd F2):**
600M-param decoder-only LLM as reranker, via llama.cpp yes/no logprob
method. 27x larger than MiniLM-L6-v2 but GPU-accelerated through
existing FFM bridge. Needs new FFM bindings for ranking (vs generative
inference). ~640 MB Q8_0. Published as competitive with much larger
cross-encoders on BEIR.

**Chunk-First Reranking (qmd F2b):**
Before reranking, chunk each candidate (900 tokens, 15% overlap) and
select best chunk by keyword overlap with query. Only that chunk goes to
reranker. Stronger than JustSearch's current first-match snippet approach
(`extractQueryFocusedSnippet`). `ChunkSplitter` already exists and could
be reused at rerank time.

**VRAM-Aware Reranker Context Pool (qmd F2c):**
Size parallel reranker contexts by available VRAM:
`min(8, floor(freeVRAM * 0.25 / perContextMB))`. Prevents OOM while
maximizing throughput. Relevant if GGUF reranker adopted or if ONNX
reranker is parallelized.

**Cross-Encoder Pre-Tokenization at Index Time (Vespa F33):**
Pre-tokenize documents at index time and store tokens. Cross-encoder at
query time only tokenizes the query and concatenates. Reduces reranking
latency. Requires additional index storage.

**SPLADE-as-Reranker Pattern (Vespa F18):**
Use SPLADE model as a second-stage reranker (dot product of query and
document sparse vectors) after first-stage BM25/KNN. Different quality/
latency tradeoff than cross-encoder.

### Embedding Model Quality

**GISTEmbedLoss Model Selection Criterion (SentenceTransformers F1/B):**
When evaluating future embedding models (replacement for nomic-embed-v1.5),
prefer models trained with GISTEmbedLoss or CachedMNRL (large effective
batch size). GISTEmbedLoss suppresses false negatives during training
using a frozen guide model.

**GPL-LambdaMART Failure Explained (SentenceTransformers F2):**
The measured -0.009 to -0.10 nDCG regression from GPL-LambdaMART is fully
explained: training with only hard negatives causes overfit. Fix requires
60-70% hard + 30-40% random negative mix. GPL synthetic queries are
structurally unable to produce random negatives. This closes the open
question from 245 — GPL-LambdaMART should remain disabled.

**L2 Normalization Option (OpenSearch 4.3, Infinity F4):**
L2 normalization of embedding vectors before KNN. JustSearch currently
relies on llama.cpp's built-in normalization. Explicit L2 norm in Java
would provide a safety net if model output is not pre-normalized.

**GGUF Layer Norm Verification (SentenceTransformers U2):**
Matryoshka truncation requires layer normalization before truncation. It
is unknown whether llama.cpp embedding output includes layer norm.
Verification experiment needed: compare raw llama.cpp output vs
HuggingFace output for identical inputs on nomic-embed-v1.5. If not
normalized, implement layer norm in Java (~20 lines).

**Binary Quantization Ruled Out (Qdrant F2):**
For nomic-embed-v1.5 at 768 dimensions, binary quantization recall is
0.94-0.96 — below acceptable threshold without heavy oversampling. Not
recommended unless dimension count increases significantly.

**FeatureField 8-bit Precision Ceiling (Milvus F10):**
Milvus stores SPLADE as native float32; Lucene's FeatureField uses 8-bit
mantissa truncation. Confirmed as a JustSearch-specific limitation with
no in-Lucene solution. Implication: SPLADE weight normalization at index
time is more important to get right since post-indexing precision is lossy.

### Sparse Retrieval Improvements

**~~SPLADE Weight Clamping (SentenceTransformers F4):~~ DONE (2026-03-02)**
Implemented in `FieldMapper.java` splade case: `Math.min(weight, 64.0f)`
clamp + `weight > 0.0f` positive guard. 5 unit tests in
`FieldMapperTest.SpladeFieldMapping`.

**SPLADE Symmetric Model Ceiling (SentenceTransformers E):**
Best symmetric SPLADE is OpenSearch's v2-distill at 52.8 BEIR-13 nDCG
(+1.1 over SPLADE-v3's 51.7). Limited headroom within symmetric
architecture — inference-free SPLADE (+2.9 from item 18) is more
impactful than a symmetric model swap.

**SEISMIC Cluster-Based Sparse Retrieval (Infinity F28, OpenSearch 1.3):**
SEISMIC (SIGIR 2024) clusters sparse vectors for faster traversal. 3x
faster than inverted index at comparable quality. Lucene implementation
would require a custom codec. Worth tracking as the posting-list-free
future of sparse search.

**Dual SPLADE Representation (Vespa F17):**
Store SPLADE as both Lucene FeatureField (for similarity scoring) and as
a stored field (for debugging/reuse). Enables SPLADE-as-reranker without
re-encoding at query time.

### Filtered Vector Search

**Brute-Force Fallback for Narrow Filters (Qdrant F4, Milvus F1/F3):**

*Investigation (2026-03-03) — largely addressed by Lucene 10.3.1:*

Lucene 10.3.1 already implements a three-tier strategy in
`AbstractKnnVectorQuery.searchLeaf()`:
1. If filter cost ≤ perLeafTopK → immediate `exactSearch()` (brute-force)
2. Otherwise → HNSW with ACORN-1 variant (10.2+): extends exploration to
   neighbors-of-neighbors when >40% filtered out. Recall: 0.924→0.980 at
   5% pass-through (PR apache/lucene#14160).
3. If approximate search returns insufficient results → fallback to
   `exactSearch()`.

JustSearch passes filter directly to `KnnFloatVectorQuery` in
`ReadPathOps.searchVector()` (line ~318). No custom wrapping needed.

The remaining gap is the "unhappy middle" (5-15% pass-through) where
ACORN may not fully solve recall. Milvus uses a hard 10% threshold;
Qdrant uses absolute cardinality. A custom `KnnFloatVectorQuery`
subclass overriding `searchLeaf()` could add a `selectivity < 0.10`
check, but the incremental value over ACORN is small. **Only worth
pursuing if users report missing results with narrow folder filters.**

**Dynamic Oversampling for Moderate Filters (Milvus F7):**
For 10-50% selectivity, dynamically increase ef_search based on estimated
filter selectivity. Already supported via `vectorEfSearchOverride` — needs
formula-level computation of the override value.

**Rescore + Oversampling Pattern (Qdrant F2):**
Retrieve `limit * oversampling_factor` candidates using quantized vectors,
then rescore with float32 for exact cosine. Most valuable at more
aggressive quantization (2-bit, binary). For current Int8 SQ (0.99
accuracy), marginal gain is small.

### Long-Document / Alternative Architectures

**ColBERT for Long Documents (Vespa F30):**
ColBERT stores per-token embeddings, enabling late interaction that works
across the full document without truncation. Published 53 nDCG@10 on
BEIR. Vespa ships native ColBERT embedder (2024). High storage cost
(~128 dims × n_tokens per doc) but architecturally interesting for long
desktop documents where current dense retrieval truncates at 2048 tokens.

**SPLADE v2-distill 67M Matching 133M Quality (OpenSearch 5.1):**
OpenSearch's distilled 67M-param SPLADE matches the 133M-param parent
quality. Smaller model = faster index-time encoding. Relevant if SPLADE
index-time latency becomes a bottleneck.

**Fine-Tuned Expansion Model (qmd A3):**
1.7B model trained specifically for query expansion using 50 handcrafted
examples + GRPO with rule-based reward. Q4_K_M ~1.1 GB, could coexist
with nomic-embed on RTX 4070. Outperforms prompt-engineering a larger
general-purpose model. Reproducible recipe documented.

---

## Non-Goals

- Training custom models. All items use existing published models.
- Changing the embedding model family. nomic-embed-v1.5 remains the
  dense embedding model. Only quantization and context parameters change.
- GPU architecture changes. Single-tenant GPU policy stays. Items that
  would require multi-GPU or GPU time-sharing are out of scope.

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 76 days at audit time.

