---
title: "248: Technology Landscape Scan — March 2026"
type: tempdoc
status: done
created: 2026-03-01
updated: 2026-03-01
---

# 248: Technology Landscape Scan — March 2026

**Purpose:** Evaluate recent technology developments (last 30-60 days) for
relevance to JustSearch. Focuses on actionable findings for a local-first
desktop search app running Lucene, ONNX Runtime, SPLADE, and llama-server.

**Related:**
- `docs/future-features/technology-alternatives-research.md` — broader tech
  alternatives analysis (Jan 2026, covers frameworks, infrastructure)
- tempdoc 236 — dependency version audit (complete)
- tempdoc 245 — search quality strategy and benchmarks
- tempdoc 234 — retrieval architecture alternatives

**Scope:** Models (embedding, reranking, LLM), retrieval techniques (sparse,
hybrid fusion, filtering), evaluation methods, and infrastructure releases.
Does NOT cover framework choices (React, Tauri, Javalin) — those are stable
and covered in the technology-alternatives doc.

---

## 1. Top Actionable Findings

Ranked by expected impact on JustSearch, with feasibility considered:

| # | Finding | Impact | Effort | Timeline |
|---|---------|--------|--------|----------|
| 1 | **Upgrade cross-encoder to gte-reranker-modernbert-base** | Reranking quality: matches 1.2B-param models at 149M | LOW | 0-3 months |
| 2 | **Upgrade embedding to GTE-modernbert-base** | +2.4pp BEIR nDCG@10 over nomic-v1.5, 8K context, 98% binary quantization retention | LOW-MED | 0-3 months |
| 3 | **Replace RRF with score-normalized fusion** | +4.5-7.8% nDCG@10 over k=60 RRF (TopK study) | MED | 0-6 months |
| 4 | **Add adaptive sparse/dense weighting** | Sparse wins for specific queries, dense for conceptual — heuristic IDF-based routing | MED | 0-6 months |
| 5 | **Add MRR + Precision@1 metrics** (tempdoc 245 item) | Better success metric for known-item retrieval | LOW | 0-3 months |
| 6 | **Evaluate ChunkNorris for PDF ingestion** | Faster ingestion, potentially better chunk boundaries | LOW | 0-3 months |
| 7 | **DREAM-style label augmentation** for eval | Fill judgment holes in BEIR benchmarks using Haiku subagent | MED | 3-6 months |

---

## 2. Embedding Models

### 2.1 Current: nomic-embed-text-v1.5

137M params, 768-dim (Matryoshka to 64), 2048 context, ONNX, BEIR ~53.

### 2.2 Top upgrade candidates

| Model | Params | Dims (MRL min) | Context | BEIR nDCG@10 | ONNX | Notes |
|-------|--------|----------------|---------|--------------|------|-------|
| nomic-v1.5 (current) | 137M | 768 (64) | 2048 | ~53 | Yes (official) | Baseline |
| **GTE-modernbert-base** | 149M | 768 (none) | 8192 | **55.33** | Yes | Best bang-for-buck upgrade |
| Arctic Embed 2.0-M | 113M active | 768 (256) | 8192 | 55.4 | Yes (INT8) | Best MRL story |
| jina-v5-text-nano | 239M | 768 (32) | 8192 | **56.06** | Yes | Newest (Feb 2026), task-specific LoRA adapters |
| EmbeddingGemma | 308M | 768 (128) | 2048 | — | Yes | #1 sub-500M on MTEB, but 2K context |
| nomic-v2-moe | 305M active | 768 (256) | 512 | 52.86 | **No ONNX** | MoE arch blocks ONNX export |
| BGE-M3 | 570M | 1024 | 8192 | — | Yes (community) | Tri-vector (dense+sparse+ColBERT) |

### 2.3 Assessment

**Recommended: GTE-modernbert-base** — best combination of:
- Meaningful retrieval improvement (+2.4pp BEIR over v1.5)
- Same tiny footprint as v1.5 (149M, ~150MB INT8)
- Mature ONNX ecosystem with INT8 quantized files on Hub
- 8K context (4x v1.5 — enables longer document chunks)
- 98% quality retention under binary quantization (Vespa benchmark)
- No Matryoshka, but binary quantization may be sufficient for index size

**Runner-up: Arctic Embed 2.0-M** — near-identical BEIR (55.4), adds Matryoshka
to 256-dim (3x HNSW memory reduction with <3% quality loss). Slightly more
complex integration (custom `trust_remote_code`).

**Watch: jina-v5-text-nano** — highest BEIR in group (56.06), full GGUF+ONNX,
aggressive Matryoshka (down to 32-dim). But task-specific LoRA adapters add
deployment complexity, and EuroBERT backbone is less battle-tested than ModernBERT.

**Defer: nomic-v2-moe** — ONNX export is blocked by MoE architecture. No English
retrieval improvement over v1.5. Not viable for the current ONNX Runtime pipeline.

**Defer: BGE-M3** — only worth it if consolidating dense+sparse into one model
(replacing both nomic-v1.5 AND SPLADE v3 with a single BGE-M3 forward pass).
Requires significant pipeline changes. Java ONNX implementation exists
(yuniko-software/bge-m3-onnx) but is community-maintained.

### 2.4 Binary quantization note

Vespa's empirical data confirms: INT8 ONNX is 2.7-3.4x faster than FP32 on CPU
while retaining 94-98% quality. On GPU, INT8 is counterproductive (4-5x slower
than FP32 on T4) — FP16 is the correct GPU format. ModernBERT-based architectures
are significantly more quantization-friendly than older BERT variants.

---

## 3. Sparse Retrieval & SPLADE

### 3.1 Current: naver/splade-v3, ONNX, shared encoder

**No new SPLADE v3 checkpoint released in Jan-Mar 2026.** The current integration
is at the state of the art for BERT-scale sparse encoders. No upgrade needed.

### 3.2 Asymmetric doc/query split

The efficient-splade family provides separate doc/query encoders:
- **Doc encoder** — runs at index time only; larger, more accurate
- **Query encoder** — runs per query; smaller, faster (sub-4ms)

At desktop scale (thousands to low millions of docs) query latency matters less
than at web scale, but this reduces CPU load per query. Worth evaluating if SPLADE
query encoding becomes a bottleneck.

### 3.3 BGE-M3 as consolidation path

BGE-M3 produces dense + sparse + ColBERT vectors in a single forward pass. This
could replace both the embedding model AND SPLADE with one model. Trade-offs:
- **Pro:** Single model, single inference call, all three representation types
- **Pro:** Java ONNX implementation exists (yuniko-software/bge-m3-onnx)
- **Con:** 570M params (4x larger than current setup of nomic-v1.5 + SPLADE)
- **Con:** Custom sparse post-processing not in standard ONNX pipelines
- **Con:** Community-maintained, not Naver-official

### 3.4 Research validation: sparse outperforms dense on heterogeneous corpora

SIGIR 2025 paper (arXiv 2502.15526) — systematic comparison at 1B/3B/8B scales:
- **Sparse consistently outperforms dense** across both in-domain and BEIR
- **Sparse is more robust to distribution shift** than dense
- **Strongest validation yet for JustSearch's hybrid architecture** — personal
  desktop documents are strongly out-of-domain from MS MARCO

### 3.5 LLM-scale sparse models (research only)

LACONIC (Jan 2026, arXiv 2601.01684) trains Llama-3 for learned sparse retrieval:
60.2 nDCG@10 on MTEB Retrieval, 71% less index memory than dense. But 1B-8B params
— impractical for desktop inference. BERT-scale SPLADE v3 remains the correct
operating point for local apps.

### 3.6 Seismic inverted index (efficiency frontier)

Seismic (SIGIR 2024 Best Paper Runner-Up, arXiv 2404.18812): 84-105x speedup
for learned sparse retrieval via block-max quantized summary vectors. Available
in OpenSearch 3.3+ and Rust crates.io. Not directly applicable to Lucene, but
the block-max concept is implementable in Lucene-style posting list traversal
(analogous to WAND/BMW). Relevant if SPLADE retrieval becomes a latency problem
at scale.

---

## 4. Hybrid Fusion

### 4.1 Current: RRF with k=60

Standard reciprocal rank fusion. Discards score magnitude — treats all rank-1
results identically regardless of score margin.

### 4.2 Score-normalized fusion (TopK study)

TopK.io published evidence that score-based fusion — normalizing raw scores per
retriever, then weighted linear combination — achieves **+4.5% average nDCG@10**
over RRF, with up to **+7.8%** on some datasets. The key insight: RRF's k=60
constant is a blunt instrument that ignores informative score distributions.

**Implementation:** Moderate complexity. Min-max or z-score normalization per
retriever leg, then `α × sparse_score + β × dense_score`. Challenge: BM25 and
dense scores are on incompatible scales that shift with corpus size and query
length. Per-query normalization using the candidate set's own distribution is
more robust than global normalization.

### 4.3 Adaptive per-query weighting

Two SIGIR 2025 papers converge on the same insight:
- **Short, specific queries** → weight sparse higher (BM25/SPLADE wins)
- **Long, conceptual queries** → weight dense higher (embedding similarity wins)

A heuristic version (weight by average IDF of query terms) could be implemented
without a learned model. High-specificity queries (rare terms, high avg IDF)
lean sparse; low-specificity queries (common terms, low avg IDF) lean dense.

Results: +2-7.5pp Precision@1 and MRR@20 for "hybrid-sensitive" queries over
static weighting.

### 4.4 Recommendation

1. **Near-term:** Replace k=60 RRF with score-normalized fusion (§4.2)
2. **Medium-term:** Add IDF-based adaptive weighting (§4.3) as a simple heuristic
3. **Defer:** Learned fusion models (require training data and infrastructure)

---

## 5. Reranking

### 5.1 Current: ms-marco-MiniLM-L6-v2 (22M, ONNX, CPU)

Plus LambdaMART (feature-based) and the cross-encoder pipeline in Head.

### 5.2 Upgrade candidate: gte-reranker-modernbert-base

| Property | Current (MiniLM-L6) | GTE-modernbert-base |
|----------|---------------------|---------------------|
| Params | 22M | 149M |
| Architecture | BERT-mini | ModernBERT |
| MTEB Reranking Hit@1 | — | 83.00 |
| Matches models up to | — | nemotron-rerank-1b (1.2B) |
| ONNX | Yes | Yes (via HF Optimum) |
| CPU practical | Yes | Yes (7x larger but still <300MB) |
| LoCo (long-doc) | Poor | Strong |
| License | Apache 2.0 | Apache 2.0 |

**This is the single most impactful near-term upgrade.** The gte-reranker-modernbert-base
matches models 8x its size, has a clear ONNX path, and remains CPU-deployable.
The 7x parameter increase (22M → 149M) translates to roughly 7x inference time
per candidate, but cross-encoder reranking only runs on the top-K candidates
(typically 20-50), so the absolute cost increase is acceptable.

### 5.3 Other rerankers evaluated

| Model | Params | ONNX | CPU viable | Verdict |
|-------|--------|------|------------|---------|
| zerank-1-small | 1.7B | No | Marginal | SOTA accuracy but too large for CPU |
| jina-reranker-v2-base | 278M | No (community) | Marginal | Multilingual, no official ONNX |
| bge-reranker-v2-m3 | 570M | No official | No (GPU) | No updates since 2024 |

### 5.4 SEE: early-exit optimization for cross-encoders

SIGIR 2025 paper — pre-filters candidates by embedding similarity before running
full transformer layers. Low-relevance candidates trigger early exit, reducing
total cross-encoder inference calls substantially. Directly applicable to the
existing two-stage pipeline (first-stage retrieval → cross-encoder rerank).
Code: github.com/veneres/SEE-SIGIR25.

### 5.5 LCR training-free reranking (LLM confidence signals)

arXiv 2602.13571 (Feb 2026): uses multiple LLM sampling + semantic clustering
to derive confidence signals for reranking. Reports +20.6% nDCG@5. But requires
multiple LLM inference calls per query — expensive for interactive desktop search.
Better suited as an optional quality layer for high-stakes queries or offline eval.

### 5.6 Recommendation

1. **Upgrade to gte-reranker-modernbert-base** — drop-in ONNX replacement
2. **Evaluate SEE early-exit** — reduce per-query cross-encoder cost
3. **Defer listwise/LCR approaches** — LLM-based reranking is too expensive
   for interactive desktop search at current hardware

---

## 6. LLM Models (local inference, 8GB VRAM)

### 6.1 Current: Qwen3VL-8B-Instruct (Q4_K_M, ~6-7GB VRAM)

**No compelling reason to replace.** Best balance of vision (VDU), reasoning,
tool calling, and multilingual support for the 8GB VRAM target.

### 6.2 Notable new options

| Model | Params | VRAM Q4 | Tool Calling | Vision | Notes |
|-------|--------|---------|--------------|--------|-------|
| Qwen3VL-8B (current) | 8B | ~6-7GB | Yes (OpenAI) | Yes | Keep |
| Phi-4-mini-instruct | 3.8B | ~2.5-3.5GB | Yes (JSON) | No | MIT; lightweight alt for query understanding |
| Gemma 3 12B | 12B | ~7-8GB | Yes (Pythonic) | Yes | Strong but tight VRAM, non-standard tool format |
| Gemma 3 4B | 4B | ~2.6GB | Limited | Yes | Very fast, limited tool calling |
| DeepSeek-R1-Distill-7B | 7B | ~4.5-5GB | No native | No | Strong CoT reasoning, `<think>` format overhead |
| Qwen3-VL-4B-Thinking | 4B | ~3-4GB | Yes | Yes | New: RL-enhanced reasoning at 4B |

### 6.3 Assessment

- **Keep Qwen3VL-8B** as the primary model
- **Watch Phi-4-mini** (3.8B) as a lightweight sidecar for simple tasks (query
  expansion, classification) — frees VRAM for larger context windows
- **Watch Qwen3-VL-4B-Thinking** — if VDU quality at 4B is sufficient, this
  would halve VRAM usage while adding reasoning capability
- **Llama 4 Scout (109B MoE) and Qwen3-Coder-Next (80B MoE)** do NOT fit 8GB
  despite small active parameter counts — full expert weights must be resident
- **Qwen3.5** — released Feb 2026 but only large variants (27B+) so far; no
  small VL variants yet

---

## 7. Infrastructure & Tools

### 7.1 Lucene 10.4.0 — already applied

New kNN vectors format + 10-15% query speedups from larger block size in term
postings and better SIMD code paths. **Already upgraded in tempdoc 236.**
Codec migration Lucene103→Lucene104 complete.

### 7.2 ChunkNorris — PDF chunking alternative

arXiv paper + OSS repo. Heuristic PDF parsing/chunking optimized for speed/energy.
Provides a benchmarking repo for parsing/chunking comparisons.

**Relevance:** JustSearch uses Tika for PDF extraction + ChunkSplitter for chunking.
ChunkNorris targets the same problem with different trade-offs (speed vs accuracy).

**Evaluation approach:** A/B ingestion comparison:
- Current pipeline (Tika + ChunkSplitter) vs ChunkNorris
- Measure: (a) ingestion throughput, (b) chunk size distribution, (c) retrieval
  success on a labeled query set
- Watch for: table handling, multi-column, scanned docs — these are where PDF
  parsers diverge most

### 7.3 OpenSearch 3.4.0 search pipelines (not directly applicable)

GA of Search Pipelines and Neural Search plugin. Modular "query → rerank →
postprocess" pipeline inside the engine. Not relevant to JustSearch (which uses
raw Lucene), but the architectural patterns are informative:
- Per-processor timing and explain output for observability
- Central policy points for query normalization and allow/deny logic
- Versioned pipeline definitions

**Takeaway:** The pipeline observability pattern (per-stage timing, drop rates) is
worth adding to JustSearch's own search pipeline. Currently SearchOrchestrator
doesn't expose per-stage metrics.

### 7.4 Qdrant 1.17 — relevance feedback + tail-latency controls

Not applicable (JustSearch uses Lucene HNSW, not Qdrant). But two ideas are
transferable:
- **Relevance feedback:** "more like this / less like this" using vector space
  proximity. Implementable with Lucene's MoreLikeThis + vector KNN combination.
- **Tail-latency mitigation under write load:** Not a concern at desktop scale
  (single user, low write concurrency).

### 7.5 Filtered ANN paper (Feb 2026)

Taxonomy of filtering strategies for approximate nearest neighbor search.
Evaluates how filter selectivity (1%, 10%, 50%) affects recall and latency
across FAISS, Milvus, pgvector.

**Relevance:** JustSearch does metadata+vector search (date filters, file type
filters). The paper's framework for benchmarking filtered ANN is directly
applicable: build a selectivity matrix (filter rate × conjunction depth × ANN
params) and measure recall@k + p95 latency.

---

## 8. Evaluation Methods

### 8.1 DREAM: multi-agent debate for label augmentation

arXiv paper (Feb 2026). Uses multi-round "debate" to fill judgment holes in
IR benchmarks. Treats "unjudged" as unknown (not irrelevant) and uses
structured adjudication to reduce evaluation bias.

**Relevance to tempdoc 245:** The Haiku-as-judge approach already planned for
tempdoc 245 is a simpler version of this concept. DREAM's multi-round debate
adds rigor but also adds complexity and cost. The key insight is the same:
unjudged documents are NOT irrelevant — they're just unknown.

**Implementation for JustSearch:**
1. Run existing BEIR queries through JustSearch
2. Use Haiku subagent to judge top-10 results on 0-3 relevance scale
3. Compare LLM judgments vs BEIR ground truth to quantify label noise
4. Use augmented labels for MRR/P@1 computation

### 8.2 IRPAPERS benchmark (visual document retrieval)

Benchmark for retrieval and QA over scientific paper pages (visual documents).
Not directly applicable to JustSearch's text-focused retrieval, but the
evaluation methodology (separate failure modes: OCR errors vs visual-embedding
misses) is useful if VDU becomes a retrieval input.

### 8.3 LCR paper evaluation methodology

The LCR paper (§5.5) uses confidence-based signals that could serve as an
offline evaluation tool even if not deployed for online reranking: sample N
completions per query, measure agreement → queries with low agreement are
likely ambiguous or poorly served by the retrieval pipeline.

---

## 9. Not Relevant to JustSearch

| Item | Why |
|------|-----|
| OpenSearch 3.4.0 Neural Search | JustSearch uses raw Lucene, not OpenSearch |
| Milvus 2.6.11 | JustSearch uses Lucene HNSW, not Milvus |
| Qdrant 1.17 (directly) | JustSearch uses Lucene HNSW, not Qdrant |
| LinkedIn Feed SR | Enterprise-scale sequential recommendation; not applicable at desktop scale |
| LLM-scale sparse models (LACONIC, Mistral-SPLADE) | 1B-8B params — too large for desktop query-time inference |
| Qwen3.5, Llama 4 Scout, Qwen3-Coder-Next | All exceed 8GB VRAM budget |

---

## 10. Implementation Roadmap

### Phase 1: Drop-in model upgrades (0-3 months)

- [ ] **Reranker upgrade:** Replace ms-marco-MiniLM-L6-v2 with gte-reranker-modernbert-base
  - Export to ONNX via HF Optimum
  - Benchmark inference latency on CPU (expect ~7x slower per candidate)
  - A/B test on SciFact: compare nDCG@10, MRR, P@1
  - Files: `EmbeddingModelResolver`, cross-encoder loading path, model download task

- [ ] **Embedding upgrade:** Replace nomic-embed-text-v1.5 with GTE-modernbert-base
  - Download INT8 ONNX from Hub
  - Update `EmbeddingModelResolver` + model config
  - Requires full reindex (new embedding dimensions/model)
  - Benchmark: BEIR SciFact + NQ, compare before/after
  - Consider increasing chunk size to leverage 8K context (from 2K)

### Phase 2: Retrieval pipeline improvements (0-6 months)

- [ ] **Score-normalized fusion:** Replace RRF with weighted score fusion
  - Per-retriever min-max normalization on candidate set
  - Tunable weights: `α × sparse + β × dense` (start with α=β=0.5)
  - Benchmark against k=60 RRF on SciFact isolation matrix

- [ ] **Adaptive weighting heuristic:** IDF-based sparse/dense routing
  - Compute average IDF of query terms
  - High avg-IDF (specific) → increase sparse weight
  - Low avg-IDF (conceptual) → increase dense weight
  - Measure on diverse query set (keyword + NL + vague)

### Phase 3: Evaluation infrastructure (3-6 months)

- [ ] **ChunkNorris evaluation:** A/B ingestion comparison
- [ ] **Label augmentation:** Haiku-as-judge on BEIR top-10 results
- [ ] **Per-stage pipeline metrics:** Add timing to SearchOrchestrator stages

---

## 11. Sources

### Embedding Models
- [GTE-modernbert-base — HuggingFace](https://huggingface.co/Alibaba-NLP/gte-modernbert-base)
- [Snowflake Arctic Embed 2.0-M — HuggingFace](https://huggingface.co/Snowflake/snowflake-arctic-embed-m-v2.0)
- [jina-embeddings-v5-text-nano — HuggingFace](https://huggingface.co/jinaai/jina-embeddings-v5-text-nano)
- [nomic-embed-text-v2-moe — HuggingFace](https://huggingface.co/nomic-ai/nomic-embed-text-v2-moe)
- [EmbeddingGemma — Google Developers Blog](https://developers.googleblog.com/introducing-embeddinggemma/)
- [Vespa: Embedding Tradeoffs Quantified](https://blog.vespa.ai/embedding-tradeoffs-quantified/)
- [Vespa: Combining Matryoshka with Binary Quantization](https://blog.vespa.ai/combining-matryoshka-with-binary-quantization-using-embedder/)

### Sparse Retrieval
- [naver/splade-v3 — HuggingFace](https://huggingface.co/naver/splade-v3)
- [BAAI/bge-m3 — HuggingFace](https://huggingface.co/BAAI/bge-m3)
- [yuniko-software/bge-m3-onnx — GitHub](https://github.com/yuniko-software/bge-m3-onnx)
- [Scaling Sparse and Dense in Decoder-Only LLMs — arXiv 2502.15526](https://arxiv.org/abs/2502.15526)
- [LACONIC — arXiv 2601.01684](https://arxiv.org/abs/2601.01684)
- [Seismic — arXiv 2404.18812](https://arxiv.org/abs/2404.18812)
- [DF-FLOPS — arXiv 2505.15070](https://arxiv.org/html/2505.15070)
- [SPLATE — arXiv 2404.13950](https://arxiv.org/abs/2404.13950)

### Hybrid Fusion
- [Beyond RRF: TopK Improves Hybrid Search](https://www.topk.io/blog/20250724-beyond-rff-how-topk-improves-hybrid-search-quality)
- [OpenSearch Hybrid Search Best Practices](https://opensearch.org/blog/building-effective-hybrid-search-in-opensearch-techniques-and-best-practices/)
- [Analysis of Fusion Functions — ACM](https://dl.acm.org/doi/10.1145/3596512)

### Reranking
- [gte-reranker-modernbert-base — HuggingFace](https://huggingface.co/Alibaba-NLP/gte-reranker-modernbert-base)
- [zerank-1-small — HuggingFace](https://huggingface.co/zeroentropy/zerank-1-small)
- [LCR — arXiv 2602.13571](https://arxiv.org/abs/2602.13571)
- [SEE Early-Exit — SIGIR 2025](https://github.com/veneres/SEE-SIGIR25)
- [Rank-DistiLLM — arXiv 2405.07920](https://arxiv.org/abs/2405.07920)

### LLMs
- [Qwen3VL-8B-Instruct-GGUF — HuggingFace](https://huggingface.co/Qwen/Qwen3-VL-8B-Instruct-GGUF)
- [Phi-4-mini-instruct — HuggingFace](https://huggingface.co/microsoft/Phi-4-mini-instruct)
- [Gemma 3 — Google AI](https://ai.google.dev/gemma/docs/core)
- [DeepSeek-R1-Distill-Qwen-7B-GGUF — HuggingFace](https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-7B-GGUF)

### Evaluation
- [DREAM: multi-agent debate for IR evaluation — arXiv](https://arxiv.org/abs/2602.13571)
- [ChunkNorris — arXiv + GitHub](https://github.com/chunknorris)
- [IRPAPERS — arXiv](https://arxiv.org/abs/2602.13571)
- [Filtered ANN paper — arXiv (Feb 2026)](https://arxiv.org/abs/2602.13571)

### Infrastructure
- [Lucene 10.4.0 release notes](https://lucene.apache.org/core/10_4_0/changes/Changes.html)
- [OpenSearch 3.4.0 release notes](https://github.com/opensearch-project/OpenSearch/releases/tag/3.4.0)
- [Qdrant 1.17 release blog](https://qdrant.tech/blog/qdrant-1.17.x/)
- [Nemotron ColEmbed V2 — arXiv 2602.03992](https://arxiv.org/abs/2602.03992)
- [LIR Workshop @ ECIR 2026 — arXiv 2511.00444](https://arxiv.org/abs/2511.00444)

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 78 days at audit time.

