---
title: "311-R: Inference Runtime Alternatives Research"
type: tempdoc
status: done
created: 2026-03-16
parent: 311-gpu-memory-partitioning
---

> NOTE: Noncanonical doc (research notes). May drift.

# Inference Runtime Alternatives Research

Research into alternative inference runtimes and hybrid approaches that might
solve GPU memory contention better than ORT CUDA EP. Context: JustSearch runs
3 ONNX models (embedding 131 MB, SPLADE 266 MB, cross-encoder 22 MB) via ORT
plus a 7B LLM via llama-server.exe, all on one consumer GPU (8-12 GB).

Baseline problem: ORT arenas claim ~5.6 GB total, never release idle memory,
and the embedding session silently falls back to CPU when SPLADE's arena
exhausts available VRAM. See tempdoc 311 for details.

---

## 1. TensorRT as ORT Execution Provider

**What it is:** NVIDIA TensorRT optimizes the entire ONNX graph into a fused
engine. ORT offers both a standard TensorRT EP and a newer TensorRT-RTX EP
(designed for desktop RTX GPUs, Ampere+).

**Memory management vs CUDA EP:**
- TRT EP shares execution context memory *between TensorRT subgraphs* within
  a single session, which CUDA EP does not do. This reduces intra-session waste.
- Workspace memory is controlled via `trt_max_workspace_size` (default 1 GB).
  This is the scratch space for intermediate buffers during optimization, not
  the full allocation — the engine itself uses additional memory for weights
  and activations.
- TRT EP still uses the same ORT arena allocator for non-TRT nodes (ops that
  fall back to CUDA EP). So the arena problem persists for hybrid graphs.
- TRT EP does NOT solve the multi-session arena problem. Each ORT session
  still gets its own arena. The `CreateAndRegisterAllocator` shared-arena
  API is orthogonal to the EP choice.

**TensorRT-RTX EP (newer, desktop-focused):**
- Designed for RTX 30xx+ GPUs. Small footprint (~200 MB package).
- JIT optimizer compiles engines on the end user's GPU in <30 seconds.
- Supports engine caching, CUDA Graphs, BFloat16, memory-mapped engines.
- Memory-mapped engines could reduce peak VRAM by loading weights on demand.
- Still within ORT's arena model — same multi-session limitations.

**Dynamic shapes:** TRT EP requires `trt_profile_min_shapes` /
`trt_profile_max_shapes` for dynamic input dimensions. BERT-class models
with variable sequence lengths need careful profile configuration. Engine
rebuild is required if input shapes exceed the profiled range.

**Engine build overhead:** First inference triggers engine compilation
(minutes for large models). Cached engines eliminate this on subsequent runs.
This is a cold-start penalty unsuitable for desktop apps without pre-caching.

**Java API:** TRT EP options are available via the Java ORT API. The EP is
loaded as a shared library (`onnxruntime_providers_tensorrt.dll`), requiring
TensorRT runtime DLLs (~500 MB+ on disk).

**Verdict: Does not fundamentally solve multi-model memory contention.**
TRT EP improves per-session efficiency (fused kernels, shared subgraph context)
but does not address the core problem: three independent arenas competing for
VRAM. The TRT-RTX memory-mapped engine feature is interesting but unproven
for multi-session scenarios. The 500+ MB dependency footprint and engine build
cold start are significant drawbacks for a desktop app.

**Maturity:** Production (TRT EP), GA but newer (TRT-RTX EP, 2025).
**Windows/Java:** Supported via ORT Java API. Requires NVIDIA TensorRT runtime.

Sources:
- [ORT TensorRT EP docs](https://onnxruntime.ai/docs/execution-providers/TensorRT-ExecutionProvider.html)
- [ORT TensorRT-RTX EP docs](https://onnxruntime.ai/docs/execution-providers/TensorRTRTX-ExecutionProvider.html)
- [NVIDIA blog: CUDA and TRT EPs in ORT](https://developer.nvidia.com/blog/end-to-end-ai-for-nvidia-based-pcs-cuda-and-tensorrt-execution-providers-in-onnx-runtime/)

---

## 2. ONNX Runtime DirectML (Windows Native)

**What it is:** DirectML is Microsoft's hardware-accelerated DirectX 12 ML
library. Works on any DX12 GPU (NVIDIA, AMD, Intel). Windows-only.

**Memory management vs CUDA EP:**
- DirectML uses DX12 resource management, not CUDA arenas. GPU memory is
  managed by the DX12 runtime and Windows graphics kernel (WDDM).
- WDDM provides system-level memory management with virtual addressing and
  paging — theoretically better at multi-process/multi-session memory sharing
  than CUDA's user-mode arenas.
- However, DirectML EP requires `DisableMemPattern()` and
  `SetSessionExecutionMode(ORT_SEQUENTIAL)` — no memory pattern optimization,
  no parallel execution. This is a hard constraint.
- Only one thread may call `Run()` per session. Multiple threads can use
  different sessions concurrently.
- Known issue: GPU memory accumulation with repeated session creation
  (GitHub issue #1620 in onnxruntime-genai). Memory leaks reported with
  DirectML in transformer workloads.

**Performance vs CUDA EP:**
- Benchmarks show DirectML is significantly slower than CUDA for transformer
  models. One test: model load 14s (vs 2s CUDA), encoding 780ms (vs 370ms
  CUDA) — roughly 2x slower.
- Profiling shows excessive `Memcpy_token` kernel time (CPU-GPU data copies).
- DirectML is sometimes faster for small batch float32 workloads, but
  "particularly poor for float16 large batch sizes."
- BERT-class models: no evidence of competitive performance vs CUDA EP.

**Multi-session GPU memory:**
- No documented shared-memory mechanism between DirectML sessions.
- DX12's memory model is resource-based (committed/placed/reserved resources),
  which gives more granular control than CUDA arenas in theory, but ORT's
  DirectML EP does not expose this control.

**Verdict: Worse than CUDA EP for this use case.** DirectML trades CUDA's
arena waste for DX12's overhead, but performance is 2x slower for
transformers, it requires sequential execution (no parallelism), and there's
no evidence of better multi-session memory behavior. The only advantage is
AMD/Intel GPU support, which is not relevant for JustSearch's NVIDIA target.

**Maturity:** Production, well-maintained by Microsoft.
**Windows/Java:** Windows-only. Available via ORT Java API.

Sources:
- [ORT DirectML EP docs](https://onnxruntime.ai/docs/execution-providers/DirectML-ExecutionProvider.html)
- [DirectML performance issue #20983](https://github.com/microsoft/onnxruntime/issues/20983)
- [DirectML memory accumulation issue #1620](https://github.com/microsoft/onnxruntime-genai/issues/1620)
- [DirectML vs CUDA: issue #371](https://github.com/microsoft/DirectML/issues/371)

---

## 3. Running ONNX Models Inside llama-server (GGUF Unification)

**What it is:** Convert embedding, SPLADE, and cross-encoder models from ONNX
to GGUF format, then run all models (including the 7B LLM) through a single
llama-server process. This would eliminate ORT entirely and share one unified
ggml memory pool.

**Current llama-server multi-model support:**
- llama-server now supports `models.ini` configuration with model routing.
  Multiple model types (chat, embedding, reranker) can be served from one
  process on one port.
- With `--models-max 1`, only one model occupies VRAM at a time; the router
  swaps models in/out automatically. This is VRAM-efficient but adds swap
  latency.
- With `--models-max N`, multiple models can be resident simultaneously.
  ggml allocates from a shared memory pool, avoiding the per-session arena
  problem entirely.
- `/v1/embeddings` and `/v1/rerank` endpoints are supported.
- VRAM "fit" system: llama-server can automatically adjust `n-gpu-layers`
  and `ctx-size` to fit available VRAM.

**Model conversion:**
- `convert_hf_to_gguf.py` supports BERT-class models (BertModel,
  BertForMaskedLM, BertForSequenceClassification). This covers:
  - Embedding (nomic-embed-text-v1.5): BERT variant, likely convertible.
  - Cross-encoder (ms-marco-MiniLM): BertForSequenceClassification, supported.
  - SPLADE (naver/splade-v3): Uses BERT backbone with sparse projection head.
    The BERT encoder should convert, but the sparse projection (MLM head +
    ReLU + log) may need custom handling.
- **No direct ONNX-to-GGUF converter exists.** Must go through HuggingFace
  (PyTorch) weights. If the original HF model checkpoints are available
  (they are for all three models), conversion is straightforward.
- GGUF supports quantization (Q4, Q8, F16) which could reduce model sizes
  further.

**Reranker support:**
- Qwen3-Reranker GGUF models work with `--pooling rank` and `/v1/rerank`.
  Tested on Windows with RTX 3090 (2025-03-09 guide).
- Cross-encoder (BertForSequenceClassification) support exists but is less
  tested than Qwen-style rerankers.

**Architecture implications:**
- Eliminates ORT Java dependency entirely (~50 MB native libs).
- All inference goes through HTTP to llama-server — adds network latency
  (~1-2ms per call on loopback) but simplifies the Worker process.
- Single process manages all GPU memory — no arena contention by design.
- Model swap latency with `--models-max 1`: loading a BERT model into VRAM
  takes ~1-3s. Acceptable for batch processing, not for real-time queries
  (cross-encoder in the search path).
- With `--models-max 4` (all models resident): ggml's memory pool handles
  allocation cooperatively, but total VRAM usage depends on model sizes.

**Risk: SPLADE compatibility.** SPLADE's sparse output (vocabulary-sized
logits → ReLU → log1p) is non-standard for llama-server's embedding
pipeline. The `/v1/embeddings` endpoint returns dense vectors. SPLADE needs
the raw logits post-MLM-head, which llama-server may not expose. This is
the critical blocker — needs investigation.

**Risk: Performance regression.** ggml's BERT inference may be slower than
ORT's optimized CUDA kernels for small models. No benchmarks found comparing
ggml BERT vs ORT CUDA EP for batch inference.

**Verdict: Most promising for fundamentally solving memory contention, but
SPLADE compatibility is a critical unknown.** If SPLADE can be served through
llama-server (or if its sparse output can be obtained via a custom endpoint),
this approach eliminates the entire ORT arena problem. The single-process
memory pool is exactly what's needed for 8 GB GPUs. Worth prototyping for
embedding + cross-encoder first, with SPLADE as a stretch goal.

**Maturity:** Embedding/reranker in llama-server: production (2025).
Multi-model routing: recent but functional. SPLADE-specific: untested.
**Windows/Java:** llama-server.exe is already used. Java calls via HTTP.

Sources:
- [llama-server README](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)
- [models.ini guide for embedding + reranker](https://gist.github.com/VooDisss/42bce4eb5c76d3c325633886c5e348ee)
- [llama.cpp BERT support issue #2872](https://github.com/ggml-org/llama.cpp/issues/2872)
- [llama.cpp reranking API issue #8555](https://github.com/ggml-org/llama.cpp/issues/8555)
- [Jina multimodal embeddings in GGUF](https://jina.ai/news/multimodal-embeddings-in-llama-cpp-and-gguf/)

---

## 4. Candle (Hugging Face Rust)

**What it is:** Minimalist ML framework in Rust by Hugging Face. Designed for
serverless inference with small binaries. Supports CUDA GPU acceleration.

**GPU memory management:**
- Candle uses Rust's ownership model — tensors are freed when dropped, no GC.
  This gives deterministic memory release, unlike ORT's arena model.
- candle-vllm supports `--gpu-memory-fraction` for explicit VRAM budgeting.
- No arena pre-allocation — memory is allocated on demand and freed when the
  inference call completes. This is the opposite of ORT's "grab and hold"
  behavior.
- Multi-model: each model is a separate Rust struct. Memory is isolated by
  Rust's type system. Models can be loaded/unloaded independently.

**Java integration:**
- No native Java API. Would require building a Rust shared library with C ABI
  and calling via JNI.
- The `jni-rs` crate provides Rust-side JNI bindings. Production-quality.
- Architecture: Rust library (.dll) → JNI → Java. Requires maintaining a
  Rust build toolchain alongside Java/Gradle.
- Alternative: Run candle as an HTTP server (like candle-vllm) and call from
  Java. Adds network hop but avoids JNI complexity.

**Model support:**
- BERT, sentence-transformers, and other encoder models are supported.
- ONNX model loading via `candle-onnx` — can reuse existing ONNX models.
- Cross-encoder reranking: not explicitly documented but feasible since
  BertForSequenceClassification is a standard architecture.
- SPLADE: would need custom model definition (BERT + MLM head + sparse
  projection). Not available out of the box.

**Verdict: Solves memory management elegantly but impractical for JustSearch.**
Candle's deterministic memory model is ideal, and it can load ONNX models.
However, the JNI bridge (or HTTP sidecar) adds significant complexity,
requires maintaining a Rust toolchain, and SPLADE would need custom model
code. The benefit over "fix ORT arena settings" does not justify the
integration cost.

**Maturity:** Active development, used in production at HuggingFace.
Ecosystem is growing but smaller than ORT.
**Windows/Java:** Windows CUDA support exists. Java integration via JNI
(custom work required) or HTTP sidecar.

Sources:
- [Candle GitHub](https://github.com/huggingface/candle)
- [candle-vllm](https://github.com/EricLBuehler/candle-vllm)
- [jni-rs for Rust-Java bridge](https://github.com/jni-rs/jni-rs)
- [Candle intro (The New Stack)](https://thenewstack.io/candle-a-new-machine-learning-framework-for-rust/)

---

## 5. Burn (Rust ML Framework)

**What it is:** Next-generation tensor library and deep learning framework in
Rust by Tracel.ai. Supports multiple backends (CUDA, Vulkan, CPU, WebGPU).

**Memory management:**
- Rust ownership model — same deterministic memory as Candle.
- Burn+CUDA achieves 97% of PyTorch+CUDA performance with 30% lower memory
  overhead (benchmarked on Phi3 3.8B, 2025).
- "Router backend" (beta) composes multiple backends into one, allowing
  different ops to run on different hardware.
- Multi-GPU training support added in v0.8.1. Multi-model inference on single
  GPU should work via separate model instances.

**ONNX support:**
- `burn-import` crate can import ONNX models into Burn's native format.
- Model conversion is compile-time (generates Rust code from ONNX graph).
  This is unusual — models become compiled Rust code, not interpreted graphs.

**Java integration:**
- Same as Candle: no native Java API, requires JNI bridge or HTTP sidecar.
- Burn's compile-time model import makes it harder to use as a dynamic
  model-loading library compared to Candle or ORT.

**Verdict: Interesting technology but wrong fit.** Burn's compile-time ONNX
import and Rust-native models are elegant for Rust applications but create
friction for Java integration. The 30% memory improvement over PyTorch is
promising but the comparison point is PyTorch, not ORT's arena model.
Integration cost is even higher than Candle.

**Maturity:** Active development, v0.16+ as of 2026. Growing community.
**Windows/Java:** Windows support via CUDA/Vulkan backends. Java: custom JNI only.

Sources:
- [Burn GitHub](https://github.com/tracel-ai/burn)
- [Burn official site](https://burn.dev/)
- [Rust + CUDA for ML (2025)](https://dasroot.net/posts/2025/12/rust-cuda-gpu-programming-ml-applications/)

---

## 6. Tract (Rust ONNX Runtime)

**What it is:** Pure-Rust, lightweight ONNX inference engine. No external C++
dependencies. Focuses on CPU inference and portability (including WASM).

**GPU support:** Effectively none. Tract is CPU-only with WASM/WASI support.
No CUDA, no Vulkan, no DirectML. GPU inference in the Rust ecosystem is
handled by Candle, Burn, or the `ort` crate (Rust bindings to ONNX Runtime).

**Memory management:** Rust ownership, deterministic. Very small memory
footprint. But irrelevant without GPU support.

**Verdict: Not applicable.** Tract is a CPU-only runtime. It cannot help with
GPU memory contention because it doesn't use the GPU. Would only be useful if
we wanted to guarantee CPU-only inference for one model (e.g., cross-encoder)
to avoid GPU contention entirely — but ORT's CPU session already does this.

**Maturity:** Production, widely used for edge/embedded CPU inference.
**Windows/Java:** Windows CPU supported. No Java API (Rust only).

Sources:
- [WONNX/Tract landscape](https://github.com/webonnx/wonnx)
- [ort (Rust ORT bindings)](https://ort.pyke.io/)

---

## 7. CUDA Graphs in ORT (`enable_cuda_graph`)

**What it is:** CUDA Graphs capture a sequence of GPU operations (kernel
launches, memory copies) into a graph that can be replayed without CPU
involvement. ORT's CUDA EP supports this via `enable_cuda_graph` session
option.

**Memory implications:**
- CUDA Graphs require **static shapes** — input/output shapes and memory
  addresses must be identical across inference calls. If shapes change, the
  graph must be recaptured.
- Memory is allocated once during graph capture and reused on replay. This
  eliminates per-inference arena growth and allocation jitter.
- However, CUDA Graphs do NOT reduce peak memory — they pre-allocate exactly
  what the first capture needs, then hold it. The benefit is predictability,
  not reduction.
- Arena waste from power-of-two extension is avoided because the graph
  captures exact allocations. This could save 20-50% vs arena over-allocation
  for small models.

**Multi-session implications:**
- Each session with CUDA Graphs enabled captures its own graph independently.
  No cross-session memory sharing.
- All three sessions (embedding, SPLADE, CE) could use CUDA Graphs if inputs
  are padded to fixed shapes. This would make each session's VRAM usage
  predictable and minimal.
- Combined with shared arena allocator, this could significantly reduce waste.

**Constraints for JustSearch:**
- Embedding: batch sizes vary (1-8), sequence lengths vary (128-2048).
  Would need to pad to max (batch=8, seq=2048) — this INCREASES peak memory
  because the graph captures the worst case.
- SPLADE: similar variable shapes.
- Cross-encoder: pairs of query+doc, variable lengths.
- Dynamic shapes are "supported" in the sense that the graph is recaptured
  when shapes change, but this adds overhead and defeats the purpose.

**Verdict: Marginal benefit, high constraint cost.** CUDA Graphs eliminate
arena over-allocation jitter but require static shapes. Padding to max shapes
would increase peak memory, not decrease it. The real benefit is
predictability — knowing exactly how much VRAM each session needs. This is
useful for capacity planning but does not solve the fundamental contention
problem. Could be combined with right-sized arenas (item 8 in tempdoc 311)
as a "belt and suspenders" approach.

**Maturity:** Production in ORT.
**Windows/Java:** Supported via ORT Java API session options.

Sources:
- [ORT CUDA EP docs (enable_cuda_graph)](https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html)
- [CUDA Graph arena memory issue #14942](https://github.com/microsoft/onnxruntime/issues/14942)
- [ORT memory consumption docs](https://onnxruntime.ai/docs/performance/tune-performance/memory.html)

---

## 8. Mixed Backend Approach

**What it is:** Use different execution providers for different models based
on their characteristics. E.g., TensorRT for SPLADE (largest, most benefit
from optimization), CUDA EP for embedding, CPU for cross-encoder.

**Memory isolation:**
- Different EPs in different sessions use independent memory management.
  TRT EP manages its own workspace; CUDA EP uses its arena; CPU uses system RAM.
- Running cross-encoder on CPU frees ~512 MB of GPU arena. Cross-encoder is
  only used during search (not indexing), so CPU latency is acceptable if
  <100ms per query.
- TRT EP for SPLADE: the fused engine would use exactly the memory it needs
  (no arena over-allocation). SPLADE at 266 MB model + activations might
  need ~1-1.5 GB vs the current 4 GB arena.

**Per-session configuration:**
- ORT allows different `SessionOptions` per session. Each session can use a
  different EP: `session1.addTensorrt(...)`, `session2.addCuda(...)`,
  `session3 = CPU only`.
- This is fully supported in the Java API.

**Practical combination for JustSearch:**
```
Embedding (131 MB model):  CUDA EP, 1.5 GB arena, shrinkage enabled
SPLADE (266 MB model):     TensorRT EP, 1.5 GB workspace (builds fused engine)
Cross-encoder (22 MB):     CPU only (runs infrequently, <100ms latency)
Total GPU claim:           ~3 GB (down from 5.6 GB)
llama-server:              ~4-6 GB
Remaining for 12 GB card:  ~3-5 GB headroom
Remaining for 8 GB card:   ~0-1 GB headroom (still tight)
```

**Verdict: Incremental improvement, does not solve 8 GB GPU case.** Moving
cross-encoder to CPU is easy and saves 512 MB. TRT EP for SPLADE could reduce
its footprint. But on 8 GB cards, even optimized ORT + LLM barely fits.
This is a "make it less bad" approach, not a fundamental solution. Best
combined with arena shrinkage and right-sizing (tempdoc 311 items).

**Maturity:** All components are production.
**Windows/Java:** Fully supported.

Sources:
- [ORT TensorRT EP docs](https://onnxruntime.ai/docs/execution-providers/TensorRT-ExecutionProvider.html)
- [ORT Execution Providers overview](https://onnxruntime.ai/docs/execution-providers/)
- [ORT Java TRT issue #10352](https://github.com/microsoft/onnxruntime/issues/10352)

---

## 9. WebGPU / Vulkan Compute for Inference

**What it is:** Run ONNX inference on Vulkan compute shaders instead of CUDA,
using a separate GPU API that manages memory independently from CUDA.

**ORT Vulkan EP:** Does not exist. A feature request (issue #21917) is open
but no implementation. ORT's GPU options on Windows are CUDA, TensorRT, and
DirectML.

**WONNX (Rust WebGPU ONNX runtime):**
- Pure-Rust, uses `wgpu` (which backends to Vulkan on Windows, Metal on macOS,
  DX12 on Windows).
- Supports a subset of ONNX operators. Model support is limited.
- Benchmark on M1 Max: 572ms GPU vs 1384ms CPU (2.4x speedup). Much slower
  than CUDA for comparable hardware.
- No Java API. Would require JNI bridge or HTTP sidecar.
- Operator coverage is incomplete — may not support all ops needed for
  BERT/SPLADE models.

**ORT WebGPU EP (browser-focused):**
- ORT has a WebGPU EP but it targets the browser (via Dawn/wgpu in WASM).
- Reports of 20x speedup vs CPU WASM, but 6-14x slower than CUDA native
  (GitHub discussion #20177).
- Not designed for native desktop use. Using it outside a browser requires
  building ORT with Dawn, which is experimental.

**Vulkan compute shaders (raw):**
- Theoretically possible to write custom Vulkan compute shaders for each
  model, but this is essentially reimplementing an inference engine.
- Memory management would be Vulkan-native (VkDeviceMemory), completely
  independent from CUDA. Could coexist with llama-server's CUDA usage.
- Impractical — years of engineering for marginal benefit.

**Verdict: Not viable.** No production-ready Vulkan/WebGPU inference runtime
exists for native desktop Windows with Java integration. WONNX is the closest
but has limited operator support, no Java API, and 3-5x slower than CUDA.
The theoretical memory isolation benefit (Vulkan memory vs CUDA memory) is
real but not worth the performance and compatibility cost.

**Maturity:** Experimental (WONNX), browser-only (ORT WebGPU EP).
**Windows/Java:** No viable path.

Sources:
- [WONNX GitHub](https://github.com/webonnx/wonnx)
- [ORT WebGPU tutorial](https://onnxruntime.ai/docs/tutorials/web/ep-webgpu.html)
- [ORT Vulkan EP feature request #21917](https://github.com/microsoft/onnxruntime/issues/21917)
- [CUDA vs WebGPU perf discussion #20177](https://github.com/microsoft/onnxruntime/discussions/20177)

---

## 10. ORT with ROCm (AMD) — Memory Lessons

**What it is:** ORT's ROCm EP targets AMD GPUs. Not directly applicable to
JustSearch (NVIDIA-only) but may have memory management patterns worth learning.

**Memory management approach:**
- ROCm EP supported external allocators via `gpu_external_*` parameters,
  allowing integration with external memory managers (e.g., PyTorch's
  caching allocator).
- `ORT_ROCM_MEM_LIMIT` environment variable for global memory limits.
- Same shared arena allocator API as CUDA EP.

**Key lesson — external allocator pattern:**
- The external allocator API allows *replacing* ORT's built-in arena with a
  custom allocator. If ORT's CUDA EP supported this (it does via
  `gpu_external_alloc` / `gpu_external_free` / `gpu_external_empty_cache`),
  we could implement a single shared allocator across all sessions.
- This is more powerful than the shared arena API because the custom
  allocator can implement any policy: shrinkage, budgets, priority eviction.

**Deprecation note:** ROCm EP was removed in ORT 1.23, replaced by MIGraphX
EP. The external allocator pattern may still be available in CUDA EP.

**Verdict: The external allocator pattern is the most interesting lesson.**
If ORT's CUDA EP supports `gpu_external_alloc`, a custom allocator could
implement session-aware memory budgeting — e.g., giving embedding priority
over SPLADE when both need GPU memory. This needs investigation in the
ORT CUDA EP documentation. However, this is a C API feature that may not
be exposed in the Java bindings.

**Maturity:** ROCm EP deprecated. External allocator API: available in C API.
**Windows/Java:** External allocator requires C API access (not in Java API).

Sources:
- [ORT ROCm EP docs](https://onnxruntime.ai/docs/execution-providers/ROCm-ExecutionProvider.html)
- [ORT memory consumption docs](https://onnxruntime.ai/docs/performance/tune-performance/memory.html)
- [ORT MIGraphX EP docs](https://onnxruntime.ai/docs/execution-providers/MIGraphX-ExecutionProvider.html)

---

## 11. ORT Shared Arena Allocator (Cross-cutting)

This is not an alternative runtime but an underutilized ORT feature that is
directly relevant to every approach above.

**What it is:** ORT's C API provides `CreateAndRegisterAllocator` to register
a shared allocator with the `OrtEnv`. All sessions using that env share one
arena instead of each creating their own.

**How it works:**
1. Create an allocator with `CreateAndRegisterAllocator(env, mem_info, arena_cfg)`.
2. Set `session.use_env_allocators = "1"` on each session's options.
3. All sessions share the same GPU arena — one pre-allocation, one growth
   strategy, one shrinkage policy.

**Impact on JustSearch:**
- Instead of 3 arenas (1024 + 4096 + 512 = 5632 MB), one shared arena
  could serve all three models. Peak concurrent usage determines the size.
- Since models rarely run simultaneously (embedding during indexing, SPLADE
  during backfill, CE during search), the shared arena mostly serves one
  model at a time.
- Combined with `kSameAsRequested` extend strategy and arena shrinkage,
  actual VRAM usage would track real demand, not worst-case pre-allocation.

**Java API availability:**
- The `CreateAndRegisterAllocator` API is documented for C and C#.
- **Not confirmed in Java API.** The ORT Java bindings (`OrtEnvironment`,
  `OrtSession`) may not expose this method. Needs investigation of the
  `ai.onnxruntime` Java package.
- If not in Java API: could be accessed via JNI to the C API, or a feature
  request could be filed.

**Verdict: Highest-impact change within the current ORT architecture.** If
the Java API supports shared arena allocation, this single change could
reduce GPU memory from 5.6 GB to ~2-3 GB (one arena sized for peak single-
model usage). Combined with arena shrinkage, it could bring total ORT VRAM
to <1.5 GB during idle periods. **This should be investigated before any
runtime switch.**

Sources:
- [ORT memory consumption docs](https://onnxruntime.ai/docs/performance/tune-performance/memory.html)
- [Shared arena allocator for CUDA discussion #21577](https://github.com/microsoft/onnxruntime/discussions/21577)
- [ORT C API: CreateAndRegisterAllocator](https://onnxruntime.ai/docs/api/c/struct_ort_api.html)
- [ORT memory arena issue #11627](https://github.com/microsoft/onnxruntime/issues/11627)

---

## Summary Ranking

| # | Approach | Solves Contention? | Effort | Risk | Recommendation |
|---|----------|--------------------|--------|------|----------------|
| 11 | **ORT Shared Arena Allocator** | Largely yes | Low-Med | Low | **Investigate first** — check Java API support |
| 3 | **GGUF Unification (llama-server)** | Yes (fundamentally) | High | Med-High | **Prototype for embed+CE** — SPLADE is the unknown |
| 8 | **Mixed Backend (TRT+CUDA+CPU)** | Partially | Medium | Low | **Easy win** — move CE to CPU, right-size arenas |
| 7 | **CUDA Graphs** | Marginal | Low | Low | Combine with shared arena for predictability |
| 1 | **TensorRT EP** | No | Medium | Med | Only if TRT-RTX memory-mapped engines prove useful |
| 4 | **Candle (Rust)** | Yes (in theory) | Very High | High | Wrong language ecosystem for this project |
| 5 | **Burn (Rust)** | Yes (in theory) | Very High | High | Same as Candle — wrong fit |
| 2 | **DirectML** | No | Low | Med | 2x slower than CUDA, no benefit |
| 9 | **WebGPU / Vulkan** | N/A | Very High | Very High | No viable runtime exists |
| 6 | **Tract** | N/A | N/A | N/A | CPU only — not applicable |
| 10 | **ROCm lessons** | N/A | N/A | N/A | External allocator pattern is interesting |

### Recommended Investigation Order

1. **Shared arena allocator in Java API** — if available, this is the cheapest
   fix. Check `ai.onnxruntime.OrtEnvironment` for `createAndRegisterAllocator`
   or equivalent. If not in Java API, check if the C# API pattern can be
   replicated via JNI.

2. **Arena shrinkage + right-sizing** (tempdoc 311 items 1-3, 6-7) — these
   are ORT configuration changes, no runtime switch needed. Can be done
   immediately.

3. **Move cross-encoder to CPU-only** — saves 512 MB GPU, CE runs
   infrequently. Trivial change.

4. **GGUF unification prototype** — convert nomic-embed-text and
   ms-marco-MiniLM to GGUF, test through llama-server alongside the LLM.
   Measure VRAM, latency, quality. This is the "plan B" if ORT tuning
   doesn't solve the 8 GB GPU case.

5. **SPLADE in llama-server** — only after items 1-4. Requires investigating
   whether llama-server can output sparse (vocabulary-sized) vectors for
   SPLADE-style models.

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 63 days at audit time.

