---
title: "311: GPU Memory Partitioning — Research Archive"
type: tempdoc-archive
parent: 311-gpu-memory-partitioning.md
created: 2026-03-16
---

# 311: GPU Memory Partitioning — Research Archive

Consolidated findings from 7 parallel internet research threads covering
ORT arena shrinkage, CUDA EP options, CUDA-level memory management,
multi-model serving architectures, session lifecycle strategies,
alternative runtimes, and ORT roadmap/bleeding edge.

Individual research documents are also preserved:
- `311-cuda-memory-research.md`
- `311-ort-session-lifecycle-research.md`
- `311-runtime-alternatives-research.md`

---

## Java API Availability Matrix (ORT 1.24.3)

The single most important constraint: many ORT memory features exist in
the C/C++ API but are **not exposed in the Java binding**.

| Feature | Java API? | How | Impact |
|---------|-----------|-----|--------|
| `gpu_mem_limit` per session | Yes | `cudaOpts.add(...)` | Cap each arena |
| `arena_extend_strategy` | Yes | `cudaOpts.add(...)` | `kSameAsRequested` more shrinkage-friendly |
| `memory.enable_memory_arena_shrinkage` | Yes | `RunOptions.addRunConfigEntry(...)` | Release idle VRAM after each run |
| `session.use_device_allocator_for_initializers` | Yes | `SessionOptions.addConfigEntry(...)` | **Required** for shrinkage to work |
| `setOptimizedModelFilePath()` | Yes | `SessionOptions` method | Cache optimized graph, faster session creation |
| `setMemoryPatternOptimization()` | Yes | `SessionOptions` method | Consolidate allocations on repeated shapes |
| Shared arena (env-level) | **No** | `CreateAndRegisterAllocatorV2` — C only | Collapse N arenas into 1 |
| Custom GPU allocator | **No** | `gpu_external_alloc` — function pointers | Unified memory pool |
| IoBinding / pre-allocated buffers | **No** | C++/C#/Python only | Pre-allocate shared VRAM |
| Fine-grained arena config | **No** | `OrtArenaCfg` — C only | `initial_chunk_size_bytes` etc. |
| Session eviction / residency | **No** | Feature request #18142 | Load/unload models on demand |
| Direct `Shrink()` call | **No** | Not exposed in any API | Must run dummy inference |
| Global device memory limit | **No** | Does not exist in any language | — |

---

## Thread 1: Arena Shrinkage Deep Dive

### How shrinkage works (from BFCArena source)

- Runs at the **end of every `session.run()`** where RunOptions carries
  `memory.enable_memory_arena_shrinkage = "gpu:0"`.
- Iterates every arena **region** (contiguous cudaMalloc block).
- Frees a region **only if ALL chunks in it are unused**. One in-use
  chunk pins the entire region.
- Model weight (initializer) allocations are in-use for the session
  lifetime. **If weights are in the arena, those regions never shrink.**

### Critical requirement: `session.use_device_allocator_for_initializers`

Setting `"1"` routes weight allocations through `cudaMalloc` directly,
**outside** the arena. This means weight memory is separate from scratch
memory. Scratch regions become fully freeable after inference completes.

Without this setting, weights pin arena regions permanently, making
shrinkage largely ineffective.

### Interaction with `arena_extend_strategy`

| Aspect | `kNextPowerOfTwo` | `kSameAsRequested` |
|--------|-------------------|---------------------|
| Region sizing | Powers of 2, growing | Exact request size |
| First region shrinkable? | No (always retained) | Yes |
| Shrinkage effectiveness | Low (large regions, one chunk pins all) | Higher (smaller regions) |
| Fragmentation | Higher | Lower |

**Recommended combination:** `kSameAsRequested` + `use_device_allocator_for_initializers = "1"` + per-run shrinkage.

### Multi-session behavior

With independent arenas (Java's only option): shrinking Session A
releases VRAM back to CUDA driver via `cudaFree()`. Session B can then
`cudaMalloc()` from that freed pool. This is indirect — not coordinated
— but works if sessions alternate.

### Latency cost

- Each `cudaFree()` is synchronous (implicit `cudaDeviceSynchronize()`).
- No published benchmarks for ORT shrinkage specifically.
- ORT team recommends conditional shrinkage — only set shrinkage
  RunOptions on runs where cleanup is desired, not every call.
- Safe with pinned output tensors (in-use check prevents freeing live
  allocations).

**Sources:** ORT issues #23339, #22297, #19445, #18845; Triton issue
#166; BFCArena source (`bfc_arena.cc`).

---

## Thread 2: CUDA EP Full Option Space

### Key options for memory management

All settable from Java via `cudaOpts.add(key, value)`:

- `gpu_mem_limit` — per-session arena cap (bytes)
- `arena_extend_strategy` — `kSameAsRequested` (1) recommended
- `cudnn_conv_algo_search` — irrelevant for transformer models
- `cudnn_conv_use_max_workspace` — irrelevant for transformer models
- `enable_cuda_graph` — **caution:** pins memory allocations after first
  run. May conflict with shrinkage. Default enabled in ORT 1.24.1.

### Shared arena: not accessible from Java

Three C-level mechanisms exist (`CreateAndRegisterAllocatorV2`,
`CreateSharedAllocator`, `gpu_external_alloc`) — none exposed in Java.

Even in C++, shared CUDA arena effectiveness is disputed (Discussion
#21577: "did not seem to have any impact on VRAM usage").

### CUDA Graphs warning

ORT 1.24.1 enables CUDA Graphs by default. Graphs capture fixed memory
addresses — arena shrinkage may not work as expected when graphs are
active. **Must test whether CUDA Graphs are implicitly enabled** in our
session configuration and whether disabling them improves shrinkage.

**Sources:** ORT CUDA EP docs, issues #7612, #21349, #5939, #16303;
Discussion #21577.

---

## Thread 3: CUDA-Level Memory Management

### Windows constraints (critical)

| Feature | Windows support | Notes |
|---------|----------------|-------|
| CUDA MPS | No | Linux/QNX only |
| Unified Memory oversubscription | No | Falls back to pre-Pascal behavior |
| CUDA Memory Pools (cudaMemPool) | Yes | But ORT doesn't use them |
| CUDA VMM (cuMemAddressReserve) | Yes | But ORT doesn't use them |
| Per-process VRAM limits | No | No OS/driver mechanism exists |
| WDDM Sysmem Fallback | Yes | Since driver 536.40 |

### WDDM Sysmem Fallback

Since driver 536.40, NVIDIA's "CUDA Sysmem Fallback Policy" transparently
places `cudaMalloc` allocations in system RAM when VRAM is exhausted.
Configurable per-executable via NVIDIA Control Panel.

**Performance penalty:** 10-50x slower for bandwidth-bound operations.
**Use case:** Safety net against OOM crashes on 8 GB cards, not a
primary strategy.

### PyTorch comparison

PyTorch has `expandable_segments`, `cudaMallocAsync` backend, pluggable
allocators. ORT's Java API exposes only `gpu_mem_limit` and shrinkage.
The flexibility gap is large.

**Bottom line:** On Windows + consumer GPU + ORT Java, the only levers
are `gpu_mem_limit`, shrinkage, and session lifecycle management.

**Sources:** NVIDIA CUDA docs, PyTorch CUDA allocator docs, NVIDIA
forums on Windows unified memory limitations.

---

## Thread 4: Multi-Model GPU Serving Architectures

### Triton Inference Server patterns (transferable)

- **Dynamic load/unload:** Models loaded on demand, evicted after idle
  timeout (~5 min default). Most relevant pattern for JustSearch.
- **Rate limiter:** Cross-model prioritization. Interactive models
  preempt batch models.
- **Model Analyzer:** Profiles per-model GPU memory under different
  batch sizes.

### vLLM / TGI lessons

- vLLM's `gpu_memory_utilization` parameter caps LLM VRAM claim.
  Equivalent for llama-server: `--n-gpu-layers` + `--ctx-size`.
- **Key insight:** Budget ONNX models against the LLM's *peak* usage
  (with full KV cache), not its idle usage.

### TensorRT

- `createExecutionContextWithoutDeviceMemory()` lets you provide your
  own activation buffer, shared across non-concurrent contexts.
- Weight streaming: stream from host on demand (layer granularity).
- **Relevant:** TensorRT EP in ORT could provide better memory
  efficiency for SPLADE, but doesn't solve the multi-arena problem.

### Academic research (most relevant)

| System | Key Insight | Applicability |
|--------|------------|---------------|
| **Clockwork** (OSDI 2020) | GPU memory as page cache. Model load ~8ms for 100MB | High — directly implementable |
| **RT-Swap** (RTAS 2024) | Proactive swap scheduling with timing guarantees | High — predictable pipeline |
| **MSched** (2025) | OS-level GPU multitasking under memory oversubscription | Medium — 58-74% of native perf |
| **ServerlessLLM** (OSDI 2024) | Custom binary format + O_DIRECT for 8.2x faster model loading | Low — overkill for our model sizes |

### Model swap latency for our models

| Model | Size | Est. swap latency (PCIe 3.0 x16) |
|-------|------|-----------------------------------|
| Cross-encoder | 22 MB | 2-5 ms |
| Embedding | 131 MB | 15-30 ms |
| SPLADE | 266 MB | 22-45 ms |
| 7B LLM (Q4_K_M) | ~4 GB | 330-660 ms (keep resident) |

**Recommended:** Keep LLM resident. Time-share ONNX models: load on
demand, cache in VRAM if space permits, evict LRU.

**Sources:** Triton docs, vLLM docs, TensorRT docs, Clockwork paper,
RT-Swap paper, MSched paper, ServerlessLLM paper.

---

## Thread 5: ORT Session Lifecycle Strategies

### Session creation cost

0.5-3s for BERT-class models after CUDA context warmup. Fast enough for
on-demand creation pattern.

### `OrtSession.close()` behavior

Does **not** reliably free all GPU memory. ~200-400 MB CUDA context
overhead persists until process exit. However, closing returns model
weights to the arena's free list (or to CUDA if using
`use_device_allocator_for_initializers`), so a subsequent session can
reuse that space.

10+ open issues spanning 2020-2026 confirm persistent CUDA context leak.

### No built-in session pool or hot-swap

- No "unload from GPU, keep on CPU" API.
- Session is bound to its EPs at creation.
- Must destroy and recreate to change GPU/CPU.

### Recommended pattern

1. **Lazy GPU session creation** — create on first inference.
2. **Idle timeout** — close after 30-60s idle.
3. **`setOptimizedModelFilePath()`** — cache optimized graph, saves
   ~200-500ms per session creation.
4. **Pre-warming** — run dummy inference to size arena correctly and
   eliminate 10-20x first-inference penalty.
5. **Target 1 active GPU session at a time** on constrained VRAM.

### HuggingFace Optimum approach

Uses subprocess isolation (each model in a separate process) to avoid
memory leaks. Most reliable but most complex pattern.

**Sources:** ORT issues #5957, #9990, #14641, #18142; HF Optimum docs.

---

## Thread 6: Alternative Runtimes and Hybrid Approaches

### Ranked by impact/effort

1. **ORT Shared Arena Allocator (C API JNI bridge)** — collapse 5.6 GB
   to ~2-3 GB. Requires JNI extension. Effectiveness on CUDA disputed.

2. **GGUF unification via llama-server** — `models.ini` supports
   multi-model routing (embedding + reranker + LLM from one process).
   `convert_hf_to_gguf.py` handles BERT-class models. Eliminates ORT
   entirely for embedding+CE. **Blocker:** SPLADE's sparse
   vocabulary-sized output is non-standard for llama-server's embedding
   endpoint.

3. **Move CE to CPU + right-size arenas** — save 512 MB GPU arena for a
   22 MB model that runs infrequently. Easy win.

### Not viable

| Approach | Why |
|----------|-----|
| TensorRT EP | Doesn't fix multi-arena problem |
| DirectML | 2x slower than CUDA for transformers, no memory sharing |
| Candle/Burn (Rust) | Wrong language ecosystem, JNI complexity |
| WebGPU/Vulkan | No production-ready Java integration |
| Tract | CPU-only |
| CUDA Graphs | Requires static shapes, increases peak memory |

**Sources:** ORT docs, llama.cpp docs, TensorRT docs, various runtime
project pages.

---

## Thread 7: ORT Bleeding Edge and Roadmap

### What exists but isn't in Java

| Feature | Status | Java? |
|---------|--------|-------|
| Shared arena (`CreateAndRegisterAllocatorV2`) | C API | No |
| Shared arena (newer `CreateSharedAllocator`) | C API | No |
| EP context sharing (`ep.share_ep_contexts`) | 1.22+ | Yes (but TRT only, not CUDA) |
| Weight sharing (`AddInitializer`) | C API | No, and CPU-only |
| IoBinding | C++/C#/Python | No |

### No planned features address multi-session GPU memory

The ORT roadmap mentions "lock contention due to memory allocations" and
"session creation time optimizations" but nothing about shared GPU
memory pools or session eviction.

### Session eviction (feature request #18142)

Proposes an API to page out inactive session GPU weights to system RAM.
Would be the ideal solution. No implementation timeline.

### ORT release notes 1.20-1.24

No major multi-session GPU memory improvements. The most relevant
addition is `ep.share_ep_contexts` in 1.22 (for TensorRT, not CUDA).

**Sources:** ORT roadmap, releases 1.20-1.24, issues #15301, #18142,
#20172.
