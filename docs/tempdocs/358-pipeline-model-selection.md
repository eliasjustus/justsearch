---
title: "358: Pipeline Model Selection"
---

# 358: Pipeline Model Selection

**Status:** In progress
**Goal:** Select production models for all ONNX pipeline components in JustSearch.
**Opened:** 2026-03-25

## Hard Requirements

These are non-negotiable. A model that fails any of these is eliminated.

| # | Requirement | Rationale |
|---|-------------|-----------|
| H1 | **Multilingual** (10+ languages minimum) | Product requirement. |
| H2 | **FP16-safe on CUDA** (head_dim ≤ 64) | head_dim=128 causes overflow risk. head_dim≥256 is confirmed broken (EmbeddingGemma NaN). FP16 tensor cores give 2× throughput vs TF32. |
| H3 | **ONNX Runtime compatible** | JustSearch uses ORT for all inference. Model must load and run correctly on ORT CUDA EP. |
| H4 | **Pre-built or trivially exportable FP16 ONNX** | We don't have ONNX export infrastructure for custom architectures. |
| H5 | **Retrieval-fine-tuned** | Must produce embeddings where cosine similarity = semantic relevance. MLM-only backbones (mmBERT) are not usable without fine-tuning. |
| H6 | **Permissive license** (Apache 2.0 or MIT) | EmbeddingGemma's Gemma Terms have restrictive flow-down obligations. Not acceptable for a desktop application. |
| H7 | **768 or 1024 embedding dimension** | Lucene vector fields are dimensioned. 384 is too small for quality. >1024 wastes memory. |
| H8 | **Under 600M parameters** | Must fit in 12GB VRAM alongside SPLADE (~1.5GB), NER (~0.5GB), and CUDA context (~1GB). |
| ~~H9~~ | ~~**Encoder-only architecture**~~ | ~~Decoder-based models (Qwen3, Gemma) are slower for embedding (no causal mask optimization for bidirectional).~~ **RELAXED** — decoder models accepted if they meet pipeline time target (S1). |

## Soft Preferences (ranked)

| Priority | Preference | Why |
|----------|-----------|-----|
| S1 | Pipeline time ≤ 150s (SciFact 5184 docs) | Tempdoc 334 target. |
| S2 | nDCG@10 ≥ 0.71 on SciFact full mode | Current Q4 Gemma baseline is 0.7128. |
| S3 | Lower VRAM usage | More headroom for other GPU models. |
| S4 | 8192+ max sequence length | Fewer chunks per long document = fewer ORT calls. |
| S5 | No query prefix required | Simpler integration (symmetric encoding). |
| S6 | **Released or updated within last 4 months** (Dec 2025+) | Favour recent models with active maintenance. |
| S7 | Matryoshka (MRL) support | Future option to truncate dimensions for memory savings. |

## Integration Constraints

JustSearch's `OnnxEmbeddingEncoder` supports:
- **Pooling:** CLS or mean (auto-detected from `pooling_config.json`)
- **Prefixes:** Document and query prefixes (loaded from `prefix_config.json`)
- **Token type IDs:** Auto-detected (some models need them, some don't)
- **Model files:** Resolved via `model_manifest.json` (cpu/gpu variants)
- **Fingerprinting:** SHA-256 of model file for compatibility tracking (uses manifest)
- **Dimension:** Auto-detected from model output

A model is **drop-in compatible** if it: outputs `last_hidden_state [batch, seq, dim]` as first ONNX output, uses CLS or mean pooling, and accepts `input_ids` + `attention_mask`.

## Key Findings from Research

### FP16 safety rule
- **head_dim ≤ 64:** Safe. All BERT/RoBERTa/ModernBERT/GTE-encoder models.
- **head_dim = 128:** Borderline. Qwen2/Qwen3 family has documented overflow issues.
- **head_dim ≥ 256:** Broken. EmbeddingGemma confirmed NaN on CUDA EP. Google docs state "activations do not support fp16."

### MTEB aggregate scores don't predict per-dataset performance
- arctic-embed-m-v2.0 (BEIR 55.4) scored **lower** than gte-base-en-v1.5 (BEIR 54.1) on SciFact: 0.7047 vs 0.7226.
- Published per-dataset SciFact scores (when available) are more predictive than aggregate BEIR.
- **Direct evaluation on our data is the only reliable signal.**

### The multilingual encoder gap (2024-2026)
No new retrieval-fine-tuned multilingual encoder model under 500M has been released since arctic-embed-m-v2.0 (Dec 2024). The landscape:
- **Jul 2024:** gte-multilingual-base (Alibaba) — 305M, 70+ langs
- **Dec 2024:** arctic-embed-m-v2.0 (Snowflake) — 305M, 74 langs (built on gte-multilingual backbone)
- **Sep 2025:** mmBERT-base (JHU-CLSP) — 307M, 1800+ langs, but **MLM-only, not retrieval-fine-tuned**
- Everything else multilingual is either decoder-based (Qwen3-Embedding), too large (BGE-M3 568M), or restrictively licensed (Gemma, Jina v3).

### Qwen chat model does not affect embedding selection

Investigated whether using a Qwen chat model creates synergies for Qwen-based
embedding models (shared tokenizer, shared weights). **No actionable benefit found:**

- **Tokenizer sharing:** Not possible. Chat model runs in llama-server (GGUF, native
  process) with tokenizer baked into the file, accessed via HTTP `/tokenize`. Embedding
  model loads `HuggingFaceTokenizer` from `tokenizer.json` in Java/ORT. Different
  processes, runtimes, and formats — no sharing even with identical vocabulary.
- **Weight sharing:** Not possible. Chat uses GGUF/llama.cpp, embedding uses ONNX/ORT.
  Even within ORT, each session loads its own weight copy (`use_device_allocator_for_initializers=1`).
  No weight-sharing infrastructure exists in the codebase.
- **Tokenization consistency for RAG:** Minor benefit — same tokenizer means consistent
  chunk boundaries between indexing and generation. Not a decision driver.

### Bug discovered: EmbeddingFingerprint hardcoded filename
`EmbeddingFingerprint.discoverModelPath()` hardcoded `"model.onnx"`. Models with non-standard filenames (e.g., `model_fp16.onnx` only) got fingerprint=UNAVAILABLE, silently blocking all embedding writes. **Fixed** — now uses `ModelManifest.loadOrDefault()`.

## Tested Models

All tested on SciFact (5184 docs, 300 queries), modes: lexical, bm25_splade, full.
Backend: `runHeadlessEval` with GPU (RTX 4070, 12GB VRAM).

### Results table

| Model | Params | Multilingual | full nDCG@10 | Pipeline | VRAM peak | FP16 ONNX | License |
|-------|--------|-------------|-------------|----------|-----------|-----------|---------|
| Q4 EmbeddingGemma-300M (current) | 308M | 100+ langs | 0.7128 | 220s | 3.0 GB | **No** (broken) | Gemma Terms |
| gte-base-en-v1.5 | 137M | **EN only** | **0.7226** | 204s | 3.3 GB | Yes (265 MB) | Apache 2.0 |
| arctic-embed-m-v2.0 | 305M | 74 langs | 0.7047 | 237s | 3.3 GB | Yes (585 MB) | Apache 2.0 |
| gte-modernbert-base | 149M | **EN only** | 0.7199 | **192s** | **1.9 GB** | Yes (285 MB) | Apache 2.0 |

### Per-mode breakdown

| Model | lexical | bm25_splade | full |
|-------|---------|-------------|------|
| Q4 EmbeddingGemma | 0.6610 | 0.6680 | 0.7128 |
| gte-base-en-v1.5 | 0.6610 | 0.6680 | 0.7226 |
| arctic-embed-m-v2.0 | 0.6623 | 0.6679 | 0.7047 |
| gte-modernbert-base | 0.6595 | 0.6668 | 0.7199 |

### Stage timing

| Model | Embed | SPLADE | NER | Chunks | Total |
|-------|-------|--------|-----|--------|-------|
| Q4 EmbeddingGemma | 214s | 218s | 209s | 184s | 220s |
| gte-base-en-v1.5 | 196s | 198s | 196s | 176s | 204s |
| arctic-embed-m-v2.0 | 226s | 230s | 175s | 166s | 237s |
| gte-modernbert-base | 182s | 186s | 153s | 139s | 192s |

## Exhaustive Model Search — Results

**Completed:** 2026-03-26. Searched MTEB/MMTEB leaderboards, HuggingFace model hub,
all known model families (GTE, BGE, E5, Arctic, Nomic, Stella, mxbai, Jina, Cohere,
LaBSE, SONAR, F2LLM, EuroBERT, MrBERT, mmBERT), and recent releases (Dec 2025–Mar 2026).

Every model below has been verified against `config.json` on HuggingFace for head_dim,
hidden_size, architecture type, and license.

### Models passing ALL hard requirements (H1-H9)

| Model | HF ID | Params | Langs | Dim | head_dim | Max Seq | Pooling | ONNX FP16 | License | Released | Notes |
|-------|-------|--------|-------|-----|----------|---------|---------|-----------|---------|----------|-------|
| **gte-multilingual-base** | Alibaba-NLP/gte-multilingual-base | 305M | 70+ | 768 | 64 | 8192 | CLS | Community export (torch.onnx, opset 14) | Apache 2.0 | Jul 2024 | Elastic dim [128-768], RoPE. Official Optimum not supported; community ONNX works. |
| **multilingual-e5-base** | intfloat/multilingual-e5-base | 278M | 94 | 768 | 64 | 512 | Mean | Official ONNX (1.11 GB FP32, O4 555 MB); FP16 trivially convertible | MIT | Feb 2024 | Requires `query:`/`passage:` prefix. XLM-R base. Short max seq (512). |

### Test result: gte-multilingual-base — PASSED (after lazy CPU session fix)

**Initial test (2026-03-26): FAILED — 20+ GB RAM.**

ORT CPU session consumed 20+ GB RAM when loading the FP16 model. Root cause:
XLM-R 250K-token vocabulary triggers a memory multiplication chain in ORT CPU:
1. Protobuf deserialization (2× model size)
2. FP16→FP32 Cast of all initializers (2× weight size, both copies coexist)
3. Weight pre-packing for SIMD (another FP32 copy)
4. CPU memory arena pre-allocation
5. Known per-thread FP16 memory leak (ORT Discussion #23484)

English-only models (~30K vocab) avoid this — embedding table overhead is
~235 MB vs ~1,920 MB for 250K vocab.

**Fix: lazy CPU session creation in OnnxEmbeddingEncoder.** When GPU is
configured, skip CPU session at init. Create it lazily only if GPU fails.
Input name probing (needsTokenTypeIds) uses a lightweight NO_OPT session
that loads and closes immediately (~50 MB peak, not 20 GB).

**Retest (2026-03-26): PASSED.**
- Config: FP16 ONNX (599 MB), CLS pooling, no prefix, 768-dim, 12 layers
- Pipeline: embed 172s, SPLADE 176s, NER 172s, chunks 153s, **total 181.3s**
- Quality: lexical=0.6610, bm25_splade=0.6680, **full=0.7132**
- **Quality matches baseline: +0.0004 nDCG@10 (within noise)**
- **Pipeline 39s faster than baseline (181s vs 220s)**
- **RAM: 1.8 GB** (down from 20+ GB)
- VRAM: 3.0-4.4 GB
- Multilingual: 70+ languages

**Only 2 models in existence pass all hard requirements.** This is consistent with
the "multilingual encoder gap" finding — no new retrieval-fine-tuned multilingual
encoder under 600M has shipped since Dec 2024.

### Models failing exactly ONE hard requirement (relaxation candidates)

| Model | HF ID | Fails | Params | Langs | Dim | head_dim | Max Seq | Pooling | ONNX | License | Released | Notes |
|-------|-------|-------|--------|-------|-----|----------|---------|---------|------|---------|----------|-------|
| **BGE-M3** | BAAI/bge-m3 | **H8** (568M) | 568M | 100+ | 1024 | 64 | 8192 | CLS | Community FP16 ONNX available | MIT | Feb 2024 | XLM-R large backbone. Dense+sparse+ColBERT. Proven at scale. Only 32M over limit. |
| **multilingual-e5-large** | intfloat/multilingual-e5-large | **H8** (560M) | 560M | 100 | 1024 | 64 | 514 | Mean | Official ONNX (2.24 GB FP32) | MIT | Feb 2024 | XLM-R large. Requires `query:`/`passage:` prefix. Short max seq (514). |
| **multilingual-e5-large-instruct** | intfloat/multilingual-e5-large-instruct | **H8** (560M) | 560M | 100 | 1024 | 64 | 514 | Mean | Official ONNX (2.24 GB FP32) | MIT | Feb 2024 | Instruction-tuned E5-large. Requires task instruction prefix. Short max seq (514). |
| **arctic-embed-l-v2.0** | Snowflake/snowflake-arctic-embed-l-v2.0 | **H8** (568M) | 568M | 74 | 1024 | 64 | 8192 | CLS | ONNX with FP16 available | Apache 2.0 | Dec 2024 | XLM-R large backbone. MRL support. `query:` prefix. |
| gte-modernbert-base | Alibaba-NLP/gte-modernbert-base | **H1** (EN only) | 149M | EN only | 768 | 64 | 8192 | CLS | Yes (285 MB) | Apache 2.0 | 2024 | Best speed/quality but English only. Already tested. |
| gte-base-en-v1.5 | Alibaba-NLP/gte-base-en-v1.5 | **H1** (EN only) | 137M | EN only | 768 | 64 | 8192 | CLS | Yes (265 MB) | Apache 2.0 | 2024 | Best SciFact quality but English only. Already tested. |
| mmBERT-base | jhu-clsp/mmBERT-base | **H5** (MLM only) | 307M | 1800+ | 768 | 64 | 8192 | Community ONNX | MIT | Sep 2025 | ModernBERT backbone. Massive language coverage. Needs retrieval fine-tuning. |
| nomic-embed-text-v2-moe | nomic-ai/nomic-embed-text-v2-moe | **H4** (hard ONNX) | 475M active | ~100 | 768 | 64 | 512 | Mean | Requires monkey-patching MoE layers; no pre-built ONNX | Apache 2.0 | Feb 2025 | MoE architecture. ONNX export proven possible but non-trivial. |
| **harrier-oss-v1-0.6b** | microsoft/harrier-oss-v1-0.6b | **H4** (no ONNX) | 600M | 94 | 1024 | 64 | 32768 | Last-token | No pre-built ONNX | MIT | Mar 2026 | Qwen3 decoder. MMTEB v2: 69.0 (SOTA). Not drop-in (last-token pooling + instruction prefix). Knowledge-distilled from 27B. |
| EmbeddingGemma-300M | google/embeddinggemma-300m | **H2, H6** | 308M | 100+ | 768 | 256 | CLS | No (FP16 broken) | Gemma Terms | Sep 2025 | FP16 NaN confirmed + restrictive license. Two failures. |

### Models failing multiple hard requirements (eliminated)

| Model | Fails | Params | Langs | Dim | Architecture | License | Notes |
|-------|-------|--------|-------|-----|-------------|---------|-------|
| Qwen3-Embedding-0.6B | H9 (decoder), H2 (head_dim=128) | 600M | 100+ | 1024 | Decoder (Qwen3) | Apache 2.0 | head_dim=128 borderline |
| pplx-embed-v1-0.6B | H9 (decoder), H2 (head_dim=128) | 600M | 100+ | 1024 | Decoder | MIT | head_dim=128 borderline |
| F2LLM-v2-0.6B | H9 (decoder), H2 (head_dim=128) | 600M | 44+ | 1024 | Decoder (Qwen3) | Apache 2.0 | Pruned from Qwen3. Mar 2026. |
| F2LLM-v2-330M | H9 (decoder), H2 (head_dim=128) | 330M | 44+ | 1024 | Decoder (Qwen3 pruned) | Apache 2.0 | Smaller pruned variant, still decoder |
| jina-embeddings-v3 | H6 (CC BY-NC), H8 (570M) | 570M | 89 | 1024 | Encoder | CC BY-NC 4.0 | Non-commercial. License changed from Apache. |
| jina-embeddings-v4 | H6 (CC BY-NC), H8 (3B), H9 (decoder) | 3B | 30+ | 2048 | Decoder (Qwen2.5-VL) | CC BY-NC 4.0 | Multimodal, way too large |
| bge-multilingual-gemma2 | H6 (Gemma), H8 (9B), H9 (decoder) | 9B | multi | — | Decoder (Gemma2) | Gemma Terms | 9B, Gemma-licensed |
| LaBSE | H5 (not retrieval-tuned), H7 (borderline) | 470M | 110 | 768 | Encoder (BERT) | Apache 2.0 | Sentence similarity, not retrieval. Max seq 256. Old. |
| Stella-en-400M | H1 (EN only) | 400M | EN | 1024 | Decoder (Qwen2) | — | English-only, decoder-based |
| EuroBERT-210M | H1 (15 langs), H4 (no ONNX), H5 (MLM only) | 210M | 15 | 768 | Encoder (ModernBERT) | Apache 2.0 | MLM backbone. No ONNX support yet. Mar 2025. |
| MrBERT | H5 (MLM only), H4 (no ONNX) | 308M | 35 | 768 | Encoder (ModernBERT) | Apache 2.0 | MLM backbone. No ONNX. Feb 2026. |
| SONAR | H4 (no ONNX), H5 (MT-aligned, not retrieval) | ~1B | 200 | 1024 | Encoder-decoder | MIT | Meta. Translation-aligned, not retrieval-tuned. |
| Cohere embed-multilingual-v3 | H4 (weights not public) | unknown | 100+ | 1024 | unknown | Proprietary | API-only, no self-hosting |
| paraphrase-multilingual-mpnet-base-v2 | H5 (paraphrase, not retrieval) | 278M | 50 | 768 | Encoder (XLM-R) | Apache 2.0 | Max seq 128. Paraphrase-trained, not retrieval. |
| multilingual-e5-small | H7 (384 dim) | 118M | 100 | 384 | Encoder (XLM-R) | MIT | Dimension too small |
| paraphrase-multilingual-MiniLM-L12-v2 | H7 (384 dim) | 118M | 50 | 384 | Encoder | Apache 2.0 | Dimension too small |
| harrier-oss-v1-270m | H2 (head_dim=160), H7 (640 dim) | 270M | 94 | 640 | Decoder (Gemma3) | MIT | Gemma3 arch. FP16-unsafe + dim too small. Mar 2026. |
| jina-embeddings-v5-text-nano | H6 (CC BY-NC 4.0) | 239M | multi | 768 | Encoder (EuroBERT) | CC BY-NC 4.0 | License still non-commercial as of Apr 2026. |
| jina-embeddings-v5-text-small | H6 (CC BY-NC 4.0), H8 (677M) | 677M | multi | 1024 | Decoder (Qwen3) | CC BY-NC 4.0 | License + too large. |

### Families fully explored (no viable candidates found)

| Family | Models checked | Outcome |
|--------|---------------|---------|
| **GTE** | gte-multilingual-base, gte-base-en-v1.5, gte-large-en-v1.5, gte-modernbert-base, gte-Qwen2-7B-instruct | Only gte-multilingual-base passes all. English variants fail H1. Qwen variants fail H8+H9. |
| **BGE** | bge-m3, bge-base-en-v1.5, bge-multilingual-gemma2, bge-en-icl | BGE-M3 closest (fails H8 by 32M over). Others fail H1 or H8+H9. |
| **E5** | multilingual-e5-small/base/large/large-instruct | Only base passes all. Small fails H7 (384d). Large variants fail H8 (560M). |
| **Arctic** | arctic-embed-xs/s/m/m-v2.0/l/l-v2.0 | m-v2.0 already tested (passes but poor quality). l-v2.0 fails H8 (568M). |
| **Nomic** | nomic-embed-text-v1.5, nomic-embed-text-v2-moe | v1.5 EN-only (H1). v2-moe ONNX export non-trivial (H4). |
| **Stella** | stella-en-400M-v5, stella-en-1.5B-v5, stella-pl | All English-only or EN+specific lang (H1). Decoder-based (H9). |
| **mxbai** | mxbai-embed-large-v1 | English-only (H1). 335M, Apache 2.0, 1024d but no multilingual. |
| **Jina** | jina-embeddings-v2-base-de, v3, v4 | v2-de bilingual only. v3 CC BY-NC (H6). v4 decoder+CC BY-NC. |
| **Cohere** | embed-multilingual-v3, embed-v4 | API-only, weights not public (H4). |
| **LaBSE** | LaBSE | Not retrieval-tuned (H5), max seq 256. |
| **SONAR** | SONAR text encoders | Translation-aligned, not retrieval (H5). No ONNX (H4). |
| **mmBERT** | mmBERT-small, mmBERT-base | MLM-only (H5). No retrieval fine-tune exists. |
| **EuroBERT** | EuroBERT-210M, 610M, 2.1B | MLM-only (H5). No ONNX (H4). Only 15 langs. |
| **MrBERT** | MrBERT, MrBERT-es, MrBERT-biomed, MrBERT-legal | MLM-only base (H5). Domain variants are ColBERT, not standard embedding. No ONNX (H4). |
| **F2LLM** | F2LLM-v2-80M to 14B | All decoder (Qwen3-based, H9). head_dim=128 (H2 borderline). |
| **Harrier** | harrier-oss-v1-270m, harrier-oss-v1-0.6b, harrier-oss-v1-27b | 270M: Gemma3 arch, head_dim=160 (H2), dim=640 (H7). 0.6B: Qwen3 arch, head_dim=64 (✅), 1024d, but no ONNX (H4) + last-token pooling (not drop-in). 27B: too large (H8). |
| **Jina v5** | jina-embeddings-v5-text-nano, v5-text-small | Still CC BY-NC 4.0 (H6). Confirmed Apr 2026. |
| **Gemini Embedding** | Gemini Embedding 2 (Google, Mar 2026) | API-only, no self-hostable weights (H4). |

### The multilingual encoder gap — confirmed

The exhaustive search confirms the tempdoc's original claim: **no new retrieval-fine-tuned
multilingual encoder model under 600M has been released since arctic-embed-m-v2.0 (Dec 2024).**

New backbones have appeared (mmBERT Sep 2025, EuroBERT Mar 2025, MrBERT Feb 2026), but
none have retrieval-fine-tuned embedding variants. The decoder world moved aggressively
(Qwen3-Embedding, F2LLM-v2, pplx-embed), and these become viable now that H9 is relaxed.

### H9 relaxation — decoder models re-evaluated

With H9 relaxed, three decoder-based models become candidates. All share the same
blocker: **head_dim=128 (H2 borderline)**. FP16 overflow is not confirmed broken at
head_dim=128 (unlike head_dim=256 which is proven broken), but it's a real risk:
- Qwen3-Embedding-8B has confirmed NaN on FP16 for certain tokens
- Qwen3-Embedding-0.6B has **no confirmed NaN reports** on FP16 (smaller models more stable)
- BF16 would bypass the issue but ORT CUDA EP BF16 support is not production-ready
  (no Python API I/O binding for BF16; only usable internally in ONNX graph, not as input/output)

| Model | HF ID | Params | Langs | Dim | head_dim | Max Seq | Pooling | ONNX | License | Released | Key risk |
|-------|-------|--------|-------|-----|----------|---------|---------|------|---------|----------|----------|
| **Qwen3-Embedding-0.6B** | Qwen/Qwen3-Embedding-0.6B | 600M | 100+ | 1024 | 128 | 32K | Last-token | Community FP32+INT8; no official FP16 | Apache 2.0 | Jun 2025 | H2 (head_dim=128), right at H8 limit |
| **pplx-embed-v1-0.6B** | perplexity-ai/pplx-embed-v1-0.6b | 600M | multi | 1024 | 128 | 32K | Mean | Official ONNX available | MIT | Feb 2025 | H2 (head_dim=128), bidirectional Qwen3 variant, native INT8 output |
| **F2LLM-v2-330M** | codefuse-ai/F2LLM-v2-330M | 330M | 44+ | 1024 | 128 | 40K | EOS-token | No pre-built ONNX | Apache 2.0 | Mar 2026 | H2 (head_dim=128), pruned from Qwen3-0.6B, needs ONNX export |

**Also investigated but eliminated:**
- **jina-embeddings-v5-text-nano** (239M, encoder, EuroBERT backbone, 768d, Feb 2026) —
  would be ideal but **CC BY-NC 4.0 license** (fails H6). Built on EuroBERT-210M with
  retrieval fine-tuning via distillation. Proof that the EuroBERT backbone works for
  retrieval, but the license blocks us.
- **jina-embeddings-v5-text-small** (677M, Qwen3-based, Feb 2026) — fails H8 (677M) and H6 (CC BY-NC).

## Untested Models — Candidates for Testing

### Tier 1: Passes all hard requirements (H1-H8, H9 relaxed)

| # | Model | Arch | Params | Langs | Dim | Max Seq | Pooling | Prefix | ONNX status | License | Priority |
|---|-------|------|--------|-------|-----|---------|---------|--------|-------------|---------|----------|
| 1 | **gte-multilingual-base** | Encoder | 305M | 70+ | 768 | 8192 | CLS | None | Community ONNX; needs FP16 conversion | Apache 2.0 | **High** — best fit on paper, head_dim=64 (safe) |
| 2 | **multilingual-e5-base** | Encoder | 278M | 94 | 768 | 512 | Mean | `query:`/`passage:` | Official ONNX; needs FP16 conversion | MIT | **High** — more languages, but short max seq (512) |

### Tier 2: Borderline H2 or H8 (test if Tier 1 disappoints)

| # | Model | Arch | Params | Langs | Dim | Max Seq | Pooling | Prefix | ONNX status | License | Risk | Priority |
|---|-------|------|--------|-------|-----|---------|---------|--------|-------------|---------|------|----------|
| 3 | **Qwen3-Embedding-0.6B** | Decoder | 600M | 100+ | 1024 | 32K | Last-token | Instruction | Community FP32+INT8 | Apache 2.0 | H2 (head_dim=128), H8 (600M exact) | **Medium** — strongest benchmarks, needs FP16 validation |
| 4 | **pplx-embed-v1-0.6B** | Bidirectional decoder | 600M | multi | 1024 | 32K | Mean | None | Official ONNX | MIT | H2 (head_dim=128), H8 (600M exact) | **Medium** — no prefix needed, native INT8, Feb 2025 |
| 5 | **BGE-M3** | Encoder | 568M | 100+ | 1024 | 8192 | CLS | None | Community FP16 ONNX | MIT | H8 (568M, 32M over) | **Medium** — proven at scale, head_dim=64 (safe) |
| 6 | **F2LLM-v2-330M** | Decoder | 330M | 44+ | 1024 | 40K | EOS-token | None | No pre-built ONNX | Apache 2.0 | H2 (head_dim=128), needs ONNX | **Low** — smallest decoder, very recent (Mar 2026), but unproven + needs ONNX work |
| 7 | **arctic-embed-l-v2.0** | Encoder | 568M | 74 | 1024 | 8192 | CLS | `query:` | FP16 ONNX in official repo | Apache 2.0 | H8 (568M, 32M over) | **Low** — same family as already-tested m-v2.0 |
| 8 | **harrier-oss-v1-0.6b** | Decoder (Qwen3) | 600M | 94 | 1024 | 32K | Last-token | Instruction | No pre-built ONNX | MIT | H4 (no ONNX), not drop-in (last-token pooling) | **Low** — MMTEB v2 SOTA (69.0), head_dim=64 (safe), but needs code changes + ONNX export. Mar 2026. |

### Not tested — context

| # | Model | Why deprioritized |
|---|-------|------------------|
| 8 | multilingual-e5-large-instruct | 560M, max seq 514, complex instruction prefix — Tier 2 models are better |

## Current Standing

The candidate space is fully mapped with H9 relaxed:
- **2 encoder models** pass all requirements with head_dim=64 (FP16-safe)
- **3 decoder models** become viable but all have head_dim=128 (FP16 risk — must validate empirically)
- **2 encoder models** are close H8 relaxation candidates with head_dim=64 (FP16-safe)
- The head_dim=128 / FP16 question can only be resolved by testing on our hardware

### Key decision point: head_dim=128 FP16 validation

Before investing in decoder model evaluation, run a quick FP16 smoke test with
Qwen3-Embedding-0.6B on ORT CUDA EP. If it produces NaN, all Tier 2 decoder
candidates are eliminated and we fall back to encoder-only options. If it works,
the decoder candidates become strong contenders with superior benchmarks and recency.

## Test Results — All Models

| Model | Multilingual | full nDCG@10 | Pipeline | RAM | VRAM | License |
|-------|-------------|-------------|----------|-----|------|---------|
| Q4 EmbeddingGemma (current) | 100+ | 0.7128 | 220s | 3.0 GB | 3.0 GB | Gemma Terms |
| gte-base-en-v1.5 FP16 | **EN only** | **0.7226** | 204s | 1.4 GB | 3.3 GB | Apache 2.0 |
| arctic-embed-m-v2.0 FP16 | 74 | 0.7047 | 237s | ? | 3.3 GB | Apache 2.0 |
| gte-modernbert-base FP16 | **EN only** | 0.7199 | 192s | 2.9 GB | 1.9 GB | Apache 2.0 |
| **gte-multilingual-base FP16** | **70+** | **0.7132** | **181s** | **1.8 GB** | 3.0-4.4 GB | Apache 2.0 |

## Production Model Decision

**gte-multilingual-base is the production embedding model.**

- Quality matches baseline (0.7132 vs 0.7128 — within noise)
- Fastest pipeline tested (181s, 39s faster than baseline)
- 70+ language support (hard requirement H1)
- Apache 2.0 license
- 768-dim, CLS pooling, no prefix — drop-in compatible
- FP16 on CUDA EP works with head_dim=64

**Required code change:** Lazy CPU session in OnnxEmbeddingEncoder (implemented).
Without this fix, XLM-R 250K-vocab models consume 20+ GB RAM from the
mandatory CPU fallback session. With the fix, RAM is 1.8 GB (GPU-only path).

**multilingual-e5-base not tested** — gte-multilingual-base already meets all
requirements with quality matching baseline. No need to test further candidates.

## Full Pipeline Model Inventory

All ONNX models run in the Worker process (ORT). Chat/LLM runs in a separate
llama-server native process.

| Role | Current Model | Params | Architecture | Released | ONNX variants | Notes |
|------|--------------|--------|-------------|----------|---------------|-------|
| **Embedding** | ~~embeddinggemma-300m~~ → **gte-multilingual-base** | 305M | Encoder (GTE) | Jul 2024 | FP16 (GPU), FP32 (CPU) | **Decided this tempdoc** |
| **SPLADE** | opensearch-neural-sparse-encoding-doc-v3-distill | 67M | DistilBERT (6L, 768d) | — | FP32 (CPU 346MB), FP16 (GPU 173MB) | Dir misnamed `naver-splade-v3` |
| **NER** | dslim/distilbert-NER | ~67M | DistilBERT (6L, 768d) | Old | INT8 (CPU 63MB), FP16 (GPU 125MB) | 9-label IOB tagging |
| **Reranker** | Alibaba-NLP/gte-reranker-modernbert-base | 149M | ModernBERT (22L, 768d) | 2024 | INT8 only (CPU 144MB) | No GPU variant |
| **Citation scorer** | cross-encoder/ms-marco-MiniLM-L-2-v2 | ~5M | BERT (2L, 384d) | Old | INT8 (CPU 16MB) | CPU-only, tiny |
| **Chat/LLM** | Qwen3.5-9B (dev) / Qwen3-VL-8B-Thinking (packaged) | 9B / 8B | Decoder (Qwen3.5) | Feb-Mar 2026 | GGUF Q4_K_M | llama-server, separate process. **Lite tier candidate:** Gemma 4 E4B (4.5B eff, Apache 2.0, audio+vision, ~5.3GB Q4). See Apr 2026 refresh. |

### Research candidates for future tempdocs

| Role | Worth researching? | Rationale |
|------|-------------------|-----------|
| **SPLADE** | **Yes** — upgrade identified | See SPLADE research below. Upgrade: multilingual-v1. |
| **Reranker** | **Yes** — upgrade identified | See reranker research below. Upgrade: gte-multilingual-reranker-base. |
| **Citation scorer** | **Yes** — upgrade identified | See citation scorer research below. Upgrade: ms-marco-MiniLM-L-6-v2. |
| **NER** | **Yes** — upgrade identified | See NER research below. Upgrade: distilbert-base-multilingual-cased-ner-hrl or xlm-roberta-base-wikiann-ner. |
| **Chat/LLM** | Rolling updates | Not a one-time research task; model swaps are config-driven via model registry. |

### Reranker research — full landscape (researched 2026-03-26)

#### Current model

`Alibaba-NLP/gte-reranker-modernbert-base` — 149M params, ModernBERT (22L, 768d),
**English-only**, 8192 max seq, Apache 2.0. CPU-only (INT8, 144MB, no GPU variant).
BEIR avg nDCG@10: 56.73. Runs in the search path (post-retrieval), not the indexing
pipeline — latency per query matters more than throughput.

#### Architecture types in the reranker landscape

| Type | How it works | Pros | Cons | Examples |
|------|-------------|------|------|----------|
| **Cross-encoder** (encoder) | Encodes query+doc pair together, outputs relevance score | Fast, efficient, ONNX-friendly | No cross-document comparison | gte-reranker, bge-reranker-v2-m3, MiniLM |
| **Generative reranker** (decoder) | LLM generates "yes/no" tokens, extracts logits as score | Higher quality, instruction-tunable | Slower (autoregressive), harder ONNX | Qwen3-Reranker, mxbai-rerank-v2 |
| **Late interaction** (ColBERT-style) | Per-token embeddings, MaxSim matching | Retrieval + reranking in one | Different integration pattern, not a drop-in cross-encoder | answerai-colbert-small |

JustSearch's `CrossEncoderReranker` expects a standard cross-encoder: takes `input_ids`
+ `attention_mask` from a tokenized (query, doc) pair, returns a single logit. Generative
rerankers (Qwen3-Reranker) and ColBERT models have fundamentally different output formats
and would require new encoder code, not a model swap.

#### Reranker model landscape — complete

**Encoder cross-encoders (drop-in compatible):**

| Model | Params | Base | Langs | Max Seq | head_dim | License | BEIR nDCG@10 | ONNX | Released | Notes |
|-------|--------|------|-------|---------|----------|---------|-------------|------|----------|-------|
| gte-reranker-modernbert-base (current) | 149M | ModernBERT (22L, 768d) | EN only | 8192 | 64 | Apache 2.0 | 56.73 | Yes | 2024 | Flash Attention, unpadded seqs. English-only. |
| **gte-multilingual-reranker-base** | 306M | GTE encoder (12L, 768d) | **70+ langs** | 8192 | 64 | Apache 2.0 | competitive | Not pre-built | Jul 2024 | Same GTE family + vocab (250K) as embedding model. |
| bge-reranker-v2-m3 | 568M | XLM-R (bge-m3 base) | 100+ | 512 | 64 | Apache 2.0 | ~51.8 | Community | Feb 2024 | Large. Short max seq (512). |

**Decoder/generative rerankers (NOT drop-in — require new encoder code):**

| Model | Params | Base | Langs | Max Seq | head_dim | License | BEIR nDCG@10 | ONNX | Released | Notes |
|-------|--------|------|-------|---------|----------|---------|-------------|------|----------|-------|
| Qwen3-Reranker-0.6B | 600M | Qwen3-0.6B | 100+ | 32K | 128 | Apache 2.0 | — (MTEB-R: 65.80) | Community | Jun 2025 | Generative yes/no scoring. H2 risk (head_dim=128). |
| mxbai-rerank-base-v2 | 500M | Qwen2.5 | 109+ | 8K | — | Apache 2.0 | 55.57 | Not pre-built | Jun 2025 | RL-trained (GRPO). Decoder. |
| mxbai-rerank-large-v2 | 1.5B | Qwen2.5 | 109+ | 8K | — | Apache 2.0 | — | Not pre-built | Jun 2025 | Too large for our budget. |
| bge-reranker-v2.5-gemma2-lightweight | ~9B base | Gemma2-9B | multi | — | — | **Gemma license** | SOTA | — | Jul 2024 | Gemma-licensed. Eliminated. |

**Late interaction (NOT drop-in — different architecture entirely):**

| Model | Params | Langs | License | Notes |
|-------|--------|-------|---------|-------|
| answerai-colbert-small-v1 | 33M | ~100 | MIT | Interesting but ColBERT integration is a different system. |
| jina-colbert-v2 | ~1B | multi | CC-BY-NC | Non-commercial. Eliminated. |

**Eliminated:**

| Model | Why |
|-------|-----|
| jina-reranker-v2-base-multilingual | CC-BY-NC-4.0 (fails license requirement) |
| jina-reranker-v3 | CC-BY-NC-4.0 |
| bge-reranker-v2.5-gemma2-lightweight | Gemma license |
| mxbai-rerank-large-v2 | 1.5B — too large |

#### Key findings

**1. Only one drop-in multilingual upgrade exists: gte-multilingual-reranker-base.**
It's the only encoder cross-encoder that's multilingual, permissively licensed, and
compatible with `CrossEncoderReranker` without code changes.

**2. Decoder rerankers are higher quality but not drop-in.**
Qwen3-Reranker-0.6B scores significantly higher (MTEB-R 65.80) but uses a
fundamentally different scoring mechanism (generative yes/no logits vs. classification
logit). Adopting it would require a new reranker implementation, not a model swap.
Also has head_dim=128 (FP16 risk) and is 600M params.

**3. The current model (ModernBERT) has architectural efficiency advantages.**
ModernBERT uses Flash Attention and unpadded sequence processing, making it faster
per pair than a standard BERT/GTE model of similar size. Switching to gte-multilingual-
reranker-base (306M, standard attention) may be slower per pair despite having fewer
layers (12 vs 22), because ModernBERT is optimized for throughput. This trade-off
needs benchmarking.

**4. bge-reranker-v2-m3 is not competitive for our use case.**
568M params (large), max seq 512 (short — our current reranker handles 8192), and
BEIR 51.8 (lower than our current 56.73).

#### Reranker upgrade decision

**Upgrade to: `Alibaba-NLP/gte-multilingual-reranker-base`** (306M, GTE encoder,
70+ langs, 8192 max seq, Apache 2.0, head_dim=64, Jul 2024).

Rationale:
- Only drop-in multilingual cross-encoder that meets all constraints
- Same GTE family and vocab (250048) as the new embedding model
- 70+ languages aligns with the multilingual pipeline
- 8192 max seq matches current reranker
- head_dim=64 (FP16-safe) — enables GPU variant for faster reranking
- ONNX export straightforward (`NewForSequenceClassification`)

Trade-offs to validate:
- 306M vs 149M params (2× larger)
- Standard attention vs ModernBERT Flash Attention — latency impact unknown
- Multilingual reranking quality on BEIR/MIRACL not directly benchmarked against current

### Citation scorer research — preliminary landscape (researched 2026-03-26)

#### Current model

`cross-encoder/ms-marco-MiniLM-L-2-v2` — ~5M params, BERT (2L, 384d), English-only,
512 max seq, Apache 2.0. CPU-only (INT8, 16MB). The smallest MS MARCO cross-encoder.

MRR@10 on MS MARCO dev: 34.85. NDCG@10 on TREC DL 19: 71.01.

#### MS MARCO MiniLM cross-encoder family

| Model | Layers | MRR@10 (MARCO) | NDCG@10 (TREC DL 19) | Docs/sec | License |
|-------|--------|---------------|----------------------|----------|---------|
| ms-marco-TinyBERT-L2-v2 | 2 | 32.56 | 69.84 | 9000 | Apache 2.0 |
| ms-marco-MiniLM-L2-v2 (current) | 2 | 34.85 | 71.01 | 4100 | Apache 2.0 |
| **ms-marco-MiniLM-L4-v2** | 4 | 37.70 | 73.04 | 2500 | Apache 2.0 |
| **ms-marco-MiniLM-L6-v2** | 6 | 39.01 | 74.30 | 1800 | Apache 2.0 |
| ms-marco-MiniLM-L12-v2 | 12 | 39.02 | 74.31 | 960 | Apache 2.0 |

L-6 achieves nearly identical quality to L-12 at 2× the speed. L-2 (our current model)
trades 4.16 MRR@10 points and 3.29 NDCG@10 points for 2.3× throughput over L-6.

#### Citation scorer upgrade decision

**Upgrade to: `cross-encoder/ms-marco-MiniLM-L-6-v2`** (~23M params, MiniLM 6L,
English-only, 512 max seq, Apache 2.0).

Rationale:
- +4.16 MRR@10 over current L-2 (34.85 → 39.01)
- +3.29 NDCG@10 over current L-2 (71.01 → 74.30)
- L-12 offers no meaningful gain over L-6 (39.02 vs 39.01) at half the throughput
- Citation scoring is not multilingual-sensitive (citations are typically in the
  document's language, and the scorer sees query+passage pairs already retrieved
  by the multilingual pipeline)

**GPU investigation needed:** `CitationScorer` is currently unconditionally CPU-only
(no GPU session, no dual-session pattern). With the upgrade from L-2 (~5M) to L-6
(~23M, 3× more layers), CPU inference will be slower. Investigate whether adding a
GPU path (matching the dual-session pattern used by other encoders) meaningfully
reduces citation scoring latency. This is a search-path operation — per-query
latency matters.

### NER research — preliminary landscape (researched 2026-03-26)

#### Current model

`dslim/distilbert-NER` — ~67M params, DistilBERT (6L, 768d), **English-only**,
9 IOB labels (O, B/I-PER, B/I-ORG, B/I-LOC, B/I-MISC). MISC is filtered out
in `BioTagDecoder` — only PER, ORG, LOC are used. Entities feed into entity
disambiguation (`DisambiguationService`, `EntityClusterStore`) for search faceting.

**Multilingual gap:** With all other pipeline models going multilingual, the NER model
becomes the one component that silently fails on non-English content. A French document
with "Paris" and "Emmanuel Macron" won't get proper entity extraction, breaking entity
facets and disambiguation for non-English users.

#### Multilingual NER model landscape

| Model | Params | Base | Langs | Labels | License | ONNX | Notes |
|-------|--------|------|-------|--------|---------|------|-------|
| dslim/distilbert-NER (current) | ~67M | DistilBERT | EN only | PER/ORG/LOC/MISC | MIT | Yes (community) | English-only. |
| **Davlan/distilbert-base-multilingual-cased-ner-hrl** | ~134M | mDistilBERT | **10 langs** (ar,de,en,es,fr,it,lv,nl,pt,zh) | PER/ORG/LOC | AFL-3.0 | Community (Xenova) | Same DistilBERT arch, drop-in compatible. No MISC label. |
| **Davlan/xlm-roberta-base-wikiann-ner** | ~278M | XLM-R base | **20 langs** (ar,as,bn,ca,en,es,eu,fr,gu,hi,id,ig,mr,pa,pt,sw,ur,vi,yo,zh) | PER/ORG/LOC | — | Not pre-built | More languages but 4× larger. XLM-R arch needs export. |
| Babelscape/wikineural-multilingual-ner | ~178M | mBERT | 9 langs (de,en,es,fr,it,nl,pl,pt,ru) | PER/ORG/LOC/MISC | **CC-BY-NC-SA** | Not pre-built | Non-commercial. Eliminated. |

#### NER upgrade decision

**Upgrade to: `Davlan/distilbert-base-multilingual-cased-ner-hrl`** (~134M, mDistilBERT,
10 langs, PER/ORG/LOC, AFL-3.0).

Rationale:
- Same DistilBERT architecture as current model — drop-in compatible with
  `BertNerInference` (same output format, same label types)
- 10 languages covering the major European/Asian languages (ar, de, en, es, fr,
  it, lv, nl, pt, zh) — aligns with multilingual pipeline
- PER/ORG/LOC labels match exactly what `BioTagDecoder` uses (MISC is already
  filtered out, so losing the MISC label is a non-issue)
- Community ONNX exists (Xenova/distilbert-base-multilingual-cased-ner-hrl)
- AFL-3.0 license is permissive (similar to Apache 2.0)

Trade-off: ~134M vs ~67M params (2× larger). Same architecture so inference speed
should scale linearly. The xlm-roberta-base variant (278M, 20 langs) covers more
languages but is 4× larger with a different architecture requiring more integration work.

### SPLADE research — preliminary landscape (researched 2026-03-26)

#### Current model

`opensearch-neural-sparse-encoding-doc-v3-distill` — 67M params, DistilBERT (6L, 768d),
English-only, 30522-dim sparse output, double-log activation baked into ONNX, Apache 2.0.
Inference-free queries (tokenizer + IDF lookup). BEIR avg nDCG@10: 0.517, SciFact: 0.708.

#### Sparse retrieval model landscape (complete)

The field is small. Every available model fits into three families:

**OpenSearch neural sparse (Apache 2.0):**

| Model | Params | Base | Langs | Vocab/Dim | Max Seq | BEIR nDCG@10 | SciFact | Released | Notes |
|-------|--------|------|-------|-----------|---------|-------------|---------|----------|-------|
| doc-v3-distill (current) | 67M | DistilBERT | EN | 30522 | 512 | 0.517 | 0.708 | Apr 2025 | Inference-free queries. ℓ₀ sparsification. |
| **doc-v3-gte** | 133M | GTE encoder (12L, 768d, RoPE) | EN | 30522 | 8192 | **0.546** | **0.725** | Apr 2025 | Same vocab as distill. GTE backbone = same family as our embedding model. Best English sparse model. |
| v2-distill | ~67M | DistilBERT | EN | 30522 | 512 | 0.528 | — | 2024 | Older, requires neural query inference. |
| **multilingual-v1** | 160M | BERT (12L, 768d) | **15 langs** | **105879** | 512 | — | — | Nov 2024 | First multilingual sparse model. MIRACL avg 0.629. Different vocab (105K vs 30K). |

**Naver SPLADE (CC-BY-NC-SA-4.0 — non-commercial):**

| Model | Params | Base | Langs | Vocab/Dim | Max Seq | BEIR nDCG@10 | Released | Notes |
|-------|--------|------|-------|-----------|---------|-------------|----------|-------|
| splade-v3 | ~110M | BERT | EN | 30522 | 512 | 0.517 | Mar 2024 | **Non-commercial license.** ReLU activation. |
| splade-v3-distilbert | ~67M | DistilBERT | EN | 30522 | 512 | 0.500 | Mar 2024 | **Non-commercial license.** |
| splade-v3-doc | ~110M | BERT | EN | 30522 | 512 | — | Mar 2024 | **Non-commercial license.** Doc-only variant. |

**Research-only (not deployable):**

| Model | Why not usable |
|-------|---------------|
| CSPLADE | 1B-8B params (Llama-3.1), research paper only, no released weights |
| SPLARE | 2B-7B params (LLM-based SAE), research paper only (Mar 2026), no released weights |

#### Key findings for a SPLADE tempdoc

**1. Only two upgrade candidates exist: doc-v3-gte and multilingual-v1.**
Naver SPLADE is non-commercial (eliminated). CSPLADE/SPLARE are research-only.
The entire practical search space is two OpenSearch models.

**2. doc-v3-gte is a clear upgrade over our current model.**
Same vocab (30522), same activation (double-log ℓ₀), same query approach (inference-free),
but GTE backbone gives +0.029 BEIR avg and +0.017 SciFact. 133M params vs 67M — double
the size but same architecture family as our new embedding model (GTE). Supports 8192
tokens vs 512. Direct drop-in if ONNX is available or exportable.

**3. multilingual-v1 is the only multilingual sparse model in existence.**
15 languages, 160M params, BERT backbone. But it uses a **different vocabulary** (105879
vs 30522) — this is NOT a drop-in replacement. Changing vocab means:
- `SpladeIdfQueryEncoder` IDF tables must be rebuilt
- Sparse index dimensions change (30K → 105K)
- All documents must be re-indexed
- Lucene inverted index field schema changes

**4. Integration constraints for any swap:**
- **Activation function:** Current model uses `log(1 + log(1 + relu(x)))` baked into ONNX.
  doc-v3-gte uses the same activation. multilingual-v1 may differ — must verify.
- **Query encoding:** Current model is inference-free (tokenizer + IDF lookup via
  `SpladeIdfQueryEncoder`). Both v3-gte and multilingual-v1 also support inference-free
  queries. Compatible.
- **ONNX availability:** Neither v3-gte nor multilingual-v1 has pre-built ONNX on
  HuggingFace. Both are PyTorch/Safetensors only. Export should be straightforward
  (standard BERT/GTE architecture, no custom ops) but needs to be done.
- **Vocab size coupling:** If vocab changes (multilingual-v1), the Lucene sparse field
  dimension and IDF tables must change. If vocab stays the same (v3-gte), it's a
  model-file swap only.

**5. BGE-M3 as alternative architecture:**
BGE-M3 (already integrated in `BgeM3Encoder`) produces dense + sparse in one pass.
If adopted for dense embeddings it would eliminate the separate SPLADE model entirely.
But we chose gte-multilingual-base for dense instead, so BGE-M3 is only relevant if
we want to unify dense+sparse into one model at the cost of 568M params.

#### SPLADE upgrade decision

**Upgrade to: `opensearch-neural-sparse-encoding-multilingual-v1`** (160M, BERT, 15 langs,
105879 vocab, Apache 2.0, Nov 2024). Aligns with the multilingual embedding model
(gte-multilingual-base) and fills the sparse retrieval gap for non-English content.

Breaking change: vocab 30522 → 105879 requires re-indexing, IDF table rebuild, and
Lucene sparse field schema update. To be implemented in a dedicated tempdoc.

## Cross-cutting analysis — all pipeline models

### ONNX export feasibility

| Model | ONNX status | Risk |
|-------|-------------|------|
| gte-multilingual-base (embedding) | Community export works (tested) | **Low** — proven |
| opensearch-neural-sparse-multilingual-v1 (SPLADE) | OpenSearch supports ONNX format; standard BERT arch | **Low** — straightforward export |
| gte-multilingual-reranker-base (reranker) | Community ONNX exists (onnx-community/) | **Low** — same GTE custom arch as embedding model, proven exportable |
| distilbert-base-multilingual-cased-ner-hrl (NER) | Community ONNX exists (Xenova/) | **Low** — standard DistilBERT, trivial export |
| ms-marco-MiniLM-L-6-v2 (citation scorer) | Widely available in ONNX | **None** — standard model |

### Recency analysis

None of the recommended upgrades meet the S6 preference (released within last 4 months):

| Model | Released | Age |
|-------|----------|-----|
| gte-multilingual-base | Jul 2024 | 20 months |
| opensearch-neural-sparse-multilingual-v1 | Nov 2024 | 16 months |
| gte-multilingual-reranker-base | Jul 2024 | 20 months |
| distilbert-base-multilingual-cased-ner-hrl | ~2022 | ~4 years |
| ms-marco-MiniLM-L-6-v2 | ~2021 | ~5 years |

This reflects the multilingual encoder gap identified in the embedding research —
the multilingual model ecosystem hasn't produced new small encoder models recently.
The industry moved to large decoder models (Qwen3, Gemma) which don't fit our
constraints. For pipeline models where "multilingual + small + encoder + permissive
license" is the filter, the available options are what they are.

### Multilingual coherence

With all upgrades applied, the pipeline's language coverage:

| Role | Model | Languages | Gap? |
|------|-------|-----------|------|
| **Embedding** | gte-multilingual-base | 70+ | — |
| **SPLADE** | opensearch-neural-sparse-multilingual-v1 | 15 | **Partial** — fewer langs than embedding |
| **Reranker** | gte-multilingual-reranker-base | 70+ | — |
| **NER** | distilbert-base-multilingual-cased-ner-hrl | 10 | **Partial** — fewer langs than embedding |
| **Citation scorer** | ms-marco-MiniLM-L-6-v2 | EN only | **Gap** — English-only |
| **Chat/LLM** | Qwen3.5-9B | 200+ | — |

SPLADE (15 langs) and NER (10 langs) don't cover all languages the embedding model
supports. Content in uncovered languages gets dense retrieval and reranking but no
sparse retrieval boost and no entity extraction. This is acceptable — degraded but
functional, not broken.

The citation scorer remains English-only. No tiny multilingual cross-encoder exists
in the <50M parameter range. If multilingual citation scoring becomes a requirement,
the only option is a larger model (gte-multilingual-reranker-base at 306M) which
would need significant code changes to the `CitationScorer` class.

## Research limitations — what we know vs. what we've tested

### Model research ≠ empirical validation

The embedding model selection was backed by empirical testing: SciFact pipeline runs,
measured nDCG@10, observed RAM/VRAM, timed pipelines. This testing revealed surprises
(arctic-embed-m-v2.0 underperformed its MTEB ranking, XLM-R vocab caused 20+ GB RAM).

**Every other upgrade recommendation is based solely on model cards and published
benchmarks.** No non-embedding model has been tested in our pipeline. Specifically:

| Model | Baseline measured? | Upgrade tested? | Risk |
|-------|-------------------|----------------|------|
| **Embedding** | Yes (0.7128 nDCG@10) | Yes (0.7132) | **Low** — validated |
| **SPLADE** | No individual contribution measured | No | **High** — unknown if multilingual-v1 works in our pipeline |
| **Reranker** | No individual contribution measured | No | **Medium** — drop-in swap but no quality comparison |
| **NER** | No entity extraction quality measured | No | **Medium** — same arch but different training data |
| **Citation scorer** | No citation quality measured | No | **Low** — same family, published benchmarks credible |

### No multilingual evaluation data

The entire rationale for upgrading 4 out of 5 models is "multilingual support." But
every benchmark we have is SciFact — English only. We are making a major pipeline
overhaul based on language coverage claims from model cards, with no way to verify
that multilingual models actually produce better results on non-English content in
our system.

To validate the multilingual upgrades, we need:
- A multilingual evaluation dataset (MIRACL, Mr.TyDi, or a custom corpus)
- End-to-end search quality measurements in at least 2-3 non-English languages
- Comparison: current English-only pipeline vs. upgraded multilingual pipeline on
  non-English queries

### No system-level measurement

The pipeline is a system: retrieval → fusion → reranking → citation scoring. Models
interact — changing one changes the optimal behavior of others:
- Better embedding may make SPLADE less important (or more)
- Better reranker may compensate for weaker retrieval (or amplify it)
- Fusion weights were tuned for the current model combination and may be suboptimal
  after swapping multiple models

We're optimizing components independently when they're interdependent. After swapping
models, the fusion weights and retrieval depth should be re-tuned, not assumed stable.

### What this means for implementation

The model research phase is complete — the candidate space is fully mapped, and each
upgrade is the best available option given our constraints. But the recommendations
for non-embedding models are hypotheses, not validated results. Implementation should
include per-model A/B testing where feasible: swap one model, measure quality delta,
then proceed to the next.

## Complete upgrade slate

| Role | Current → Upgrade | Key change | Integration difficulty |
|------|------------------|-----------|----------------------|
| **Embedding** | embeddinggemma-300m → **gte-multilingual-base** | Multilingual, FP16-safe | **Done** (this tempdoc) |
| **SPLADE** | doc-v3-distill → **multilingual-v1** | Multilingual (15 langs) | **Hard** — vocab change (30K→105K), re-index required |
| **Reranker** | gte-reranker-modernbert-base → **gte-multilingual-reranker-base** | Multilingual (70+ langs) | **Easy** — drop-in cross-encoder, same output format |
| **NER** | distilbert-NER → **distilbert-multilingual-ner-hrl** | Multilingual (10 langs) | **Easy** — same DistilBERT arch, same label format |
| **Citation scorer** | ms-marco-MiniLM-L-2-v2 → **ms-marco-MiniLM-L-6-v2** | Quality (+4 MRR) | **Easy** — same family, same output format |

## Next Steps

- [x] Execute exhaustive model search
- [x] Build verified candidate table with per-model checklist
- [x] Re-evaluate decoder models after H9 relaxation
- [x] Test gte-multilingual-base on SciFact (pipeline + queries)
- [x] Fix 20+ GB RAM issue (lazy CPU session in OnnxEmbeddingEncoder)
- [x] Retest gte-multilingual-base — PASSED (0.7132 nDCG@10, 181s, 1.8 GB RAM)
- [x] Select production model: **gte-multilingual-base**
- [x] Research all pipeline models (SPLADE, reranker, NER, citation scorer)
- [ ] Update tempdoc 334 with final model decision and pipeline impact
- [ ] Wire gte-multilingual-base as default model (OnnxModelDiscovery)
- [ ] Remove unused test model directories (arctic, e5-base) after adoption
- [ ] Swap reranker to gte-multilingual-reranker-base (drop-in)
- [ ] Swap NER to distilbert-base-multilingual-cased-ner-hrl (drop-in)
- [ ] Swap citation scorer to ms-marco-MiniLM-L-6-v2 (drop-in)
- [ ] Investigate GPU path for CitationScorer (currently CPU-only, L-6 is 3× more layers)
- [ ] Upgrade SPLADE to opensearch-neural-sparse-multilingual-v1 (breaking — vocab change, re-index)

## April 2026 Model Landscape Refresh

**Researched:** 2026-04-09. Checked for new models released since the March 2026 exhaustive
search. Triggered by Google Gemma 4 release (Apr 2, 2026) and Microsoft Harrier-OSS-v1
release (Mar 30, 2026).

### New embedding candidate: Microsoft Harrier-OSS-v1-0.6B

**Released:** ~March 30, 2026. SOTA on Multilingual MTEB v2 at release (score: 69.0).

| Property | Value | Hard req | Status |
|----------|-------|----------|--------|
| Architecture | Qwen3Model (decoder-only) | H9 (relaxed) | OK |
| Params | ~600M | H8 (≤600M) | Borderline |
| Languages | 94 | H1 | ✅ |
| Embedding dim | 1024 | H7 | ✅ |
| head_dim | **64** | H2 | ✅ FP16-safe |
| License | MIT | H6 | ✅ |
| Retrieval-tuned | Yes (knowledge-distilled from 27B) | H5 | ✅ |
| Pre-built FP16 ONNX | **No** (only 270M has community ONNX) | H4 | ❌ |
| Pooling | **Last-token** | — | NOT drop-in |
| Prefix | Instruction required for queries | — | Needs integration |
| Max seq | 32,768 | S4 | ✅ |
| Matryoshka/MRL | Not documented | S7 | Unknown |
| Recency | Mar 2026 | S6 | ✅ |

**Config verified from HuggingFace** (`microsoft/harrier-oss-v1-0.6b/config.json`):
`model_type: qwen3`, `hidden_size: 1024`, `num_attention_heads: 16`,
`num_key_value_heads: 8`, `num_hidden_layers: 28`, `head_dim: 64`,
`vocab_size: 151936`, `max_position_embeddings: 32768`.

**Integration blockers** (not a model swap — requires code changes):
1. **Last-token pooling**: `OnnxEmbeddingEncoder` supports CLS and mean pooling only.
   Adding last-token pooling requires modifying `extractEmbedding()` to select the
   last non-padding token position instead of CLS/mean.
2. **Instruction prefix**: Queries need `"Instruct: ...\nQuery: ..."` format. Current
   `prefix_config.json` supports document/query prefixes but not instruction-style
   formatting with newlines.
3. **No pre-built ONNX**: Qwen3 architecture should be exportable but needs work.
4. **Qwen3 vocab (151936)**: Large vocab — same lazy-CPU-session concern as
   gte-multilingual-base (already fixed).

**Assessment**: Tier 2 future candidate. Compelling MMTEB score and FP16-safe head_dim,
but not drop-in. Becomes worth testing if: (a) OnnxEmbeddingEncoder gains last-token
pooling support for other reasons, (b) gte-multilingual-base quality proves insufficient,
or (c) ONNX export becomes available. Until then, gte-multilingual-base remains the
better choice — validated quality, drop-in compatible, already implemented.

### Eliminated: Microsoft Harrier-OSS-v1-270M

**Config verified** (`microsoft/harrier-oss-v1-270m/config.json`):
`model_type: gemma3_text`, `hidden_size: 640`, `num_attention_heads: 4`,
`head_dim: 160`, `vocab_size: 262144`.

**Fails H2** (head_dim=160, FP16 overflow risk) and **H7** (640-dim, below 768 minimum).
Gemma 3 architecture — same FP16 problem family as EmbeddingGemma. Eliminated.

### Other models checked — no new viable candidates

| Model | Status | Why |
|-------|--------|-----|
| **Jina v5-text-nano** (239M, EuroBERT) | Still **CC BY-NC 4.0** | Confirmed on HuggingFace Apr 2026. Fails H6. |
| **Jina v5-text-small** (677M, Qwen3) | Still **CC BY-NC 4.0** | Fails H6 + H8 (677M). |
| **Gemini Embedding 2** (Google, Mar 2026) | API-only | No self-hostable weights. Fails H4. |
| **SPLARE** (Naver, 2026) | Research-only | No released weights. Not deployable. |
| **New EmbeddingGemma for Gemma 4** | Does not exist | Google released Gemma 4 as chat/multimodal only. |
| **nomic-embed-text-v2-moe** | MoE ONNX export non-trivial | Already in tempdoc. No change. |

**The multilingual encoder gap persists.** No new retrieval-fine-tuned multilingual
encoder model under 600M has been released since arctic-embed-m-v2.0 (Dec 2024).
The only new entrant (Harrier-OSS-v1-0.6B) is decoder-based.

### Reranker / NER / SPLADE / Citation scorer — no changes

No new models found that improve on the existing upgrade recommendations. The reranker
landscape added ZeroEntropy zerank-1-small (1.7B, Apache 2.0) but it's too large and
uses a different scoring mechanism (LoRA fine-tuned, not a standard cross-encoder).

### Gemma 4 analysis — chat/LLM model tier

**Context**: Gemma 4 released Apr 2, 2026 under **Apache 2.0** (was Gemma Terms). The
license blocker that eliminated EmbeddingGemma (H6) no longer applies to Gemma chat models.

Investigated E2B and E4B for JustSearch's Brain (llama-server chat model).

#### Architecture (from HuggingFace config.json)

| Property | E2B | E4B |
|----------|-----|-----|
| Effective params | 2.3B | 4.5B |
| Total params (w/ PLE embeddings) | 5.1B | 8B |
| hidden_size | 1536 | 2560 |
| num_attention_heads | 8 | 8 |
| **head_dim** | **256** | **256** |
| global_head_dim | 512 | 512 |
| num_hidden_layers | 35 | 42 |
| vocab_size | 262,144 | 262,144 |
| context | 128K | 128K |
| sliding_window | 512 | 512 |
| KV shared layers | 20 of 35 | 18 of 42 |
| Vision encoder | ~150M (768d, head_dim=64) | ~150M |
| Audio encoder | ~300M (conformer) | ~300M |
| Q4_K_M GGUF | ~2.5GB | ~5.3GB |
| mmproj | ~990MB | ~990MB |
| License | Apache 2.0 | Apache 2.0 |

**head_dim=256 confirms FP16 is broken on ORT CUDA EP** — same failure as EmbeddingGemma.
This only matters for ORT pipeline models (eliminated above). For llama-server (GGUF Q4),
quantization bypasses the FP16 issue entirely.

#### Unique features relevant to JustSearch

**1. Audio transcription (E2B/E4B only — 26B/31B do NOT have it)**

Native ASR via conformer audio encoder (~300M params). Supports speech-to-text and
speech-to-translated-text. Max 30 seconds per clip. Trained on speech only (not
music/environmental). CoVoST scores: E2B=33.47, E4B=35.54.

*Relevance*: If JustSearch adds audio file indexing (voice memos, podcast clips,
meeting recordings), the chat model could double as transcriber — zero new dependencies,
same "reuse llama-server" philosophy as VDU/PDF extraction. The 30-second limit
would require chunked audio processing. This is a future capability, not a current
need, but it's a unique differentiator no other small open model offers.

**2. Per-Layer Embeddings (PLE) — VRAM efficiency**

Each decoder layer gets its own small embedding table (256-dim per token). These tables
are large (explain the 5.1B total vs 2.3B effective gap in E2B) but are lookup-only —
they can live on CPU RAM or flash storage while compute parameters stay on GPU.

*Relevance*: On a 12GB consumer GPU shared with Worker ORT sessions (embedding, SPLADE,
NER), PLE means E4B's actual VRAM footprint during inference is closer to a 4.5B model
than an 8B model. Better VRAM coexistence than Qwen 3.5's flat 9B. Depends on
llama.cpp's PLE support for actual memory savings.

**3. Configurable image token budgets**

Vision encoder supports 70, 140, 280, 560, or 1120 tokens per image. JustSearch's VDU
pipeline currently uses fixed settings for PDF page processing.

*Relevance*: Enables a quality/speed dial for PDF extraction — 70 tokens for quick
triage, 560+ for high-fidelity layout transcription. Qwen 3.5 VLM doesn't expose
this granular control.

**4. Shared KV Cache**

18 of 42 layers (E4B) reuse K/V from earlier layers. Memory savings during generation,
especially for long contexts (agent loops, multi-turn RAG).

**5. Native function calling and thinking control**

Built into the chat template with `<|think|>` token. More standardized than Qwen 3.5's
template-kwargs-only approach (Qwen 3.5 dropped `/think`/`/nothink` soft switches).

#### Benchmark comparison (chat model tier)

| Benchmark | Qwen 3.5 9B | Gemma 4 E4B | Gemma 4 E2B |
|-----------|-------------|-------------|-------------|
| MMLU Pro | — | 69.4% | 60.0% |
| AIME 2026 | — | 42.5% | 37.5% |
| LiveCodeBench v6 | — | 52.0% | 44.0% |
| GPQA Diamond | — | 58.6% | 43.4% |
| MMMU Pro (Vision) | — | 52.6% | 44.2% |
| CoVoST (Audio) | N/A | 35.54 | 33.47 |
| Agent/BFCL | Strong | Decent | Unreliable |
| Q4 size + mmproj | ~6.9GB | ~6.3GB | ~3.5GB |

Direct head-to-head benchmarks on identical tasks not available. The deep research
report (external analysis, Apr 2026) concluded Qwen 3.5 9B is stronger on agent/tool-use
(TAU2-Bench, BFCL-V4) and has validated VDU quality in JustSearch's pipeline.

#### Chat model decision

**Default remains Qwen 3.5 9B VLM.** Stronger agent/tool-use, validated VDU quality.

**Gemma 4 E4B is a valid "lite/audio-capable" tier option** for the model registry:
- Similar footprint (~6.3GB vs ~6.9GB total)
- Unique audio transcription capability
- PLE memory efficiency for VRAM-constrained systems
- Configurable vision token budgets
- Apache 2.0 license

**Gemma 4 E2B is an "ultra-lite" option** (~3.5GB total) but agent quality is too
unreliable for JustSearch's tool-calling requirements.

**Not actionable in this tempdoc** — chat model is "rolling updates" (config-driven
model registry swaps). Adding Gemma 4 tiers to the model registry is a separate task.
