---
title: "311: ORT Session Lifecycle Research — GPU Memory Pressure"
type: tempdoc-research
parent: 311-gpu-memory-partitioning.md
created: 2026-03-16
---

# ORT Session Lifecycle Research — Reducing GPU Memory Pressure

Internet research on patterns for managing ONNX Runtime session lifecycle to
reduce GPU VRAM pressure. Context: JustSearch has 3 ORT CUDA sessions created at
startup (embedding, SPLADE, cross-encoder) that live permanently, even though
they're used at different pipeline stages. On 8-12 GB consumer GPUs, this causes
contention and silent CPU fallback (see tempdoc 311).

**ORT versions referenced**: 1.14-1.24 (Java `onnxruntime_gpu` artifact).
Latest stable as of March 2026: **1.24.3** (CUDA 12.x required).

---

## 1. Session Creation Cost on CUDA EP

### What happens during `OrtEnvironment.createSession()` with CUDA EP

1. **CUDA context initialization** (first session only): ~0.5-2s. Creates the
   CUDA context, loads cuDNN/cuBLAS libraries. Subsequent sessions reuse the
   context.
2. **Model deserialization**: Read ONNX protobuf, build in-memory graph.
   Proportional to model size (~50-200ms for BERT-base class models).
3. **Graph optimization**: Apply optimization passes (constant folding, fusion).
   For BERT-base at ORT_ENABLE_ALL: ~100-500ms. Can be cached (see section 8).
4. **CUDA kernel compilation / cuDNN algorithm search**: If
   `cudnn_conv_algo_search = EXHAUSTIVE`, cuDNN profiles every algorithm for
   each conv shape. For transformer models (no conv), this is minimal.
5. **GPU memory arena allocation**: BFC arena allocates initial chunk. Default
   strategy `kNextPowerOfTwo` can allocate significantly more than model size
   (a 70KB model allocated 376MB on T4 per issue #14526).
6. **Weight transfer to GPU**: Copy model weights from CPU to GPU memory.

### Measured benchmarks (from GitHub issues and community reports)

| Scenario | Reported Time | Source |
|----------|--------------|--------|
| First GPU session (any model, includes CUDA context) | 102s (RTX 3070, ORT 1.5.2) | [#5957](https://github.com/microsoft/onnxruntime/issues/5957) |
| First GPU session (RTX A4000/A6000) | 20-30 min (!!) | [#9990](https://github.com/microsoft/onnxruntime/issues/9990) |
| Second+ GPU session (CUDA context already warm) | "no time at all" | [#5957](https://github.com/microsoft/onnxruntime/issues/5957) |
| Session load (generic, online optimization) | ~170ms | [ORT perf tuning docs](https://onnxruntime.ai/docs/performance/tune-performance/troubleshooting.html) |
| First inference (warmup penalty) | 10-20x normal latency | [#3121](https://github.com/microsoft/onnxruntime/issues/3121) |

**Key insight**: The 102s / 30min outliers in issues #5957 and #9990 were
driver-specific bugs on RTX-series GPUs (resolved in later driver versions).
Normal CUDA EP session creation for a BERT-sized model is **0.5-3 seconds**
after the first CUDA context is initialized. This is fast enough for
on-demand creation if amortized across minutes of inference work.

### Java API

```java
var env = OrtEnvironment.getEnvironment();
var opts = new OrtSession.SessionOptions();
opts.addCUDA(0); // device_id
var session = env.createSession("model.onnx", opts);
```

Source: [ORT Java Getting Started](https://onnxruntime.ai/docs/get-started/with-java.html)

---

## 2. `OrtSession.close()` Behavior — Does It Free GPU Memory?

### The short answer: **Partially, and unreliably.**

Closing an ORT session releases the session's native resources but does **not**
reliably return all GPU memory to the system. This is the single most discussed
ORT GPU memory issue, with 10+ open issues spanning 2020-2026.

### Root causes

1. **BFC Arena never releases to system**: The arena allocator holds allocated
   GPU memory permanently. `session.close()` marks the arena's memory as free
   *within the arena*, but the arena itself is not deallocated. If sessions share
   an arena (via `CreateAndRegisterAllocator`), the memory persists as long as
   the environment lives.

2. **CUDA context overhead**: ~200-400MB of VRAM is consumed by the CUDA context
   itself (cuDNN handles, cuBLAS handles, internal buffers). This persists until
   process exit. Issue #20548 reports ~320MB retained after session deletion.

3. **cuDNN workspace caching**: cuDNN caches workspace allocations internally.
   These are tied to the CUDA context, not the ORT session.

### Relevant GitHub issues

| Issue | Description | Status |
|-------|-------------|--------|
| [#17142](https://github.com/microsoft/onnxruntime/issues/17142) | GPU memory not released in destructor (C++) | Open |
| [#20548](https://github.com/microsoft/onnxruntime/issues/20548) | ~320MB VRAM retained after session deletion | Open |
| [#14641](https://github.com/microsoft/onnxruntime/issues/14641) | Memory grows after reloading model (**Java API**) | Stale |
| [#11801](https://github.com/microsoft/onnxruntime/issues/11801) | Clear GPU memory without destroying session? | Open |
| [#25996](https://github.com/microsoft/onnxruntime/issues/25996) | Release inference VRAM without unloading model | Open (Jan 2026) |
| [#14957](https://github.com/microsoft/onnxruntime/issues/14957) | Cannot release GPU memory | Open |
| [#7463](https://github.com/microsoft/onnxruntime/issues/7463) | Release GPU memory without exiting process | Open |

### Java-specific behavior

- `OrtSession` implements `AutoCloseable`. Calling `close()` releases native
  handles via JNI → C API `OrtReleaseSession()`.
- `OrtEnvironment` is a **singleton per JVM lifetime**. Its `close()` is a
  **no-op** (changed in ORT 1.11). Actual cleanup happens via JVM shutdown hook.
- Issue #14641 (Java): Every `reloadModel()` call increases memory. JVM heap
  stays flat — the leak is in native memory / GPU arena.

### Practical implication for JustSearch

Closing and recreating sessions **will** reclaim the session's *model weight*
memory (returned to the arena's free list) if a new session is created later on
the same arena. But the arena's high-water mark never decreases. For our use
case (3 models, only 1 active at a time), a **shared arena** is the right
pattern — the arena grows to fit the largest model, and subsequent smaller models
reuse that space.

Source: [ORT Memory Consumption docs](https://onnxruntime.ai/docs/performance/tune-performance/memory.html)

---

## 3. Session Pooling Patterns

### No built-in ORT session pool exists.

ORT sessions are **thread-safe for concurrent `run()` calls** — a single session
can serve multiple threads simultaneously. So the typical "pool of N sessions"
pattern from JDBC/HTTP is unnecessary for throughput. One session suffices.

Source: [ORT Thread Safety Discussion #10107](https://github.com/microsoft/onnxruntime/discussions/10107), [Issue #114](https://github.com/microsoft/onnxruntime/issues/114)

### What IS needed: lifecycle management (create/destroy/share)

The pattern that matters for our case is not pooling but **lifecycle
management** — having exactly 0 or 1 GPU sessions per model, creating on
demand, destroying when idle. No third-party Java library implements this for
ORT. The closest patterns come from:

1. **NVIDIA Triton Inference Server**: Supports explicit model load/unload via
   control protocol. Unloading frees GPU memory (though system memory may leak —
   issue [triton#4966](https://github.com/triton-inference-server/server/issues/4966)).
   ONNX backend has known memory growth on load/unload cycles
   ([triton#5841](https://github.com/triton-inference-server/server/issues/5841)).

2. **HuggingFace Optimum**: Runs ORT validation in **separate subprocesses**
   to avoid memory leaks from session create/destroy cycles. This is the most
   reliable pattern for full cleanup — process exit is the only guaranteed way
   to release all GPU memory.

3. **Custom lifecycle wrapper**: Build an application-level manager that tracks
   session state (LOADED / IDLE / UNLOADED), creates sessions on demand, closes
   them after idle timeout. This is what JustSearch should implement.

---

## 4. Lazy GPU Session Creation

### Pattern: create GPU session on first inference, release after idle timeout

This is the recommended approach for JustSearch's use case. No ORT built-in
exists, but the pattern is straightforward:

```
State: UNLOADED → (first inference request) → LOADING → LOADED → (idle timeout) → UNLOADING → UNLOADED
```

**Implementation considerations:**

1. **Creation cost is acceptable**: 0.5-3s for BERT-class models after CUDA
   context warm-up. First inference adds another ~100ms warmup. Total: ~1-4s
   latency for cold start. For embedding batches (100 chunks) or SPLADE backfill
   (50 docs), this amortizes to <1% overhead per batch.

2. **Thread safety during transitions**: Use `ReentrantReadWriteLock` or
   `synchronized` to prevent concurrent creation attempts. Callers block during
   LOADING state.

3. **Idle timeout**: After last inference call, schedule close after N seconds.
   For JustSearch: embedding is hot during primary indexing (~continuous);
   SPLADE is hot during backfill (~continuous); cross-encoder is hot during
   search queries (~bursty). Timeout of 30-60s seems reasonable.

4. **Memory reclamation**: Closing the session returns model weights to the
   shared arena's free list. The arena's total allocation doesn't shrink, but
   the space becomes available for the next session to use. This is the key
   benefit — only one model's weights occupy the arena at a time.

### What ORT does NOT support

- **No "unload from GPU, keep on CPU" API.** A session is bound to its execution
  providers at creation time. You cannot migrate a session between GPU and CPU.
  You must destroy and recreate with different SessionOptions.

- **No "release inference memory" API.** Issue #25996 (Jan 2026) requests this
  explicitly. No resolution. The arena holds intermediate activation memory
  from inference indefinitely.

- **No session "suspend/resume".** The closest would be serialize the optimized
  model graph to disk (see section 8) and reload later, but this doesn't save
  the CUDA state.

---

## 5. Model Hot-Loading / Swapping

### Can a session release GPU memory without full destruction?

**No.** There is no ORT API for:
- Unloading model weights from GPU while keeping the session alive
- Moving a session from GPU EP to CPU EP at runtime
- Releasing the GPU arena while keeping the session graph in memory

The only way to free GPU memory associated with a session is to `close()` the
session entirely. And even then, the arena memory is not returned to the OS
(see section 2).

### Workarounds discussed in the community

1. **`cudaDeviceReset()` via JNI/ctypes**: Suggested in issue #20548. This is a
   nuclear option — it destroys ALL CUDA state for the entire process, including
   other sessions and any CUDA-using library (like llama-server if it shared the
   process). **Not viable for JustSearch** (separate processes, but still too
   dangerous).

2. **Subprocess isolation**: Run each model in its own process. Process exit
   guarantees full cleanup. HuggingFace Optimum uses this pattern. **High
   complexity for JustSearch** but would give perfect isolation.

3. **Shared arena with lifecycle management**: Create and destroy sessions on
   demand, sharing a single GPU arena. The arena's free list is reused. This is
   the pragmatic approach for our case.

---

## 6. `SessionOptions.setExecutionMode(PARALLEL vs SEQUENTIAL)`

### Memory implications

| Mode | Memory Pattern Support | Memory Reuse | Notes |
|------|----------------------|--------------|-------|
| `SEQUENTIAL` (default) | Yes | Yes | Optimal memory efficiency |
| `PARALLEL` | **No** | **No** | Higher memory usage |

Source: [ORT Threading docs](https://onnxruntime.ai/docs/performance/tune-performance/threading.html), [Issue #12891](https://github.com/microsoft/onnxruntime/issues/12891)

**`SEQUENTIAL` mode enables two memory optimizations:**
1. **Memory pattern**: ORT learns allocation patterns from input shapes and
   pre-allocates for subsequent runs.
2. **Memory reuse**: ORT reuses allocations across operators in the graph.

Both are disabled in `PARALLEL` mode because operators execute concurrently
and cannot share memory safely.

**Recommendation for JustSearch**: Use `SEQUENTIAL` (default). Our models run
single-batch inference — there's no benefit to parallel operator execution, and
the memory savings from pattern/reuse are significant.

### Java API

```java
opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.ORT_SEQUENTIAL);
opts.setMemoryPatternOptimization(true);  // default, but explicit
```

Source: [Java SessionOptions API](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html)

---

## 7. HuggingFace Optimum ORT Integration

### How Optimum handles multiple ORT sessions on one GPU

1. **I/O Binding**: Optimum uses `IOBinding` by default with CUDA EP to avoid
   CPU-GPU memory copies. Pre-allocates GPU memory for inputs and outputs.
   Set via `ORTModel.from_pretrained(..., use_io_binding=True)`.

2. **Subprocess isolation for validation**: When validating models, Optimum runs
   each session in a separate subprocess to avoid memory leaks from ORT session
   create/destroy cycles. This is the most important pattern.

3. **No shared arena**: Optimum does not appear to use the shared arena allocator
   API. Each session manages its own memory independently.

4. **No lifecycle management**: Sessions are created on load and live for the
   duration of the model's lifetime. No idle-timeout or lazy-loading pattern.

**Relevance to JustSearch**: The subprocess isolation pattern is the gold standard
for guaranteed cleanup but adds significant complexity (IPC, serialization). For
our use case with 3 small models, lifecycle management with a shared arena is
more practical.

Sources:
- [Optimum GPU docs](https://huggingface.co/docs/optimum-onnx/onnxruntime/usage_guides/gpu)
- [Optimum ONNX models reference](https://huggingface.co/docs/optimum-onnx/onnxruntime/package_reference/modeling_ort)

---

## 8. Graph Optimization Caching (`setOptimizedModelFilePath`)

### How it works

When graph optimization is set to a level > `ORT_DISABLE_ALL`, ORT runs
optimization passes (constant folding, operator fusion, etc.) on the model graph
at session creation. The `setOptimizedModelFilePath()` method saves the
optimized graph to disk so subsequent session creations can load the pre-optimized
model directly.

### Usage pattern (two-phase)

**Phase 1 — First run (save optimized model):**
```java
var opts = new OrtSession.SessionOptions();
opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ORT_ENABLE_ALL);
opts.setOptimizedModelFilePath("/cache/model_optimized.onnx");
opts.addCUDA(0);
var session = env.createSession("model.onnx", opts);
// optimized model saved to /cache/model_optimized.onnx
```

**Phase 2 — Subsequent runs (load optimized model):**
```java
var opts = new OrtSession.SessionOptions();
opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ORT_DISABLE_ALL);
opts.addCUDA(0);
var session = env.createSession("/cache/model_optimized.onnx", opts);
// skips optimization passes — faster creation
```

### Performance impact

- Graph optimization for BERT-class models: ~100-500ms savings per session create.
- **Must use same execution providers and hardware** as the target — an optimized
  model for CUDA EP cannot be used with CPU EP (different fusion patterns).
- For JustSearch's lazy-creation pattern: Saves ~200-500ms per session creation.
  Worthwhile, especially if sessions are created/destroyed frequently.

### Caveats

- Does not cache CUDA kernel compilation or cuDNN algorithm selection.
- Does not reduce GPU memory allocation (arena still allocates the same amount).
- The optimized model file can be larger than the original (fused operators
  may duplicate weights).

Source: [ORT Graph Optimizations docs](https://onnxruntime.ai/docs/performance/model-optimizations/graph-optimizations.html)

---

## 9. Pre-Warming / Dry-Run Inference

### Does a dummy inference help with arena sizing?

**Yes, significantly.** The first inference triggers:

1. **GPU memory arena extension**: The BFC arena allocates memory for intermediate
   activations based on actual tensor sizes seen during execution.
2. **cuDNN algorithm selection**: cuDNN picks optimal algorithms for each operation
   based on input shapes (if `cudnn_conv_algo_search != HEURISTIC`).
3. **Memory pattern learning**: With `enable_mem_pattern = true` (default in
   SEQUENTIAL mode), ORT records the allocation pattern and pre-allocates for
   subsequent runs.

**Measured first-inference penalty**: 10-20x normal latency (issue #3121). A
warmup run with representative input shapes eliminates this penalty for
subsequent real inferences.

### Recommendation for JustSearch

After lazy-creating a GPU session, run one dummy inference with representative
input shape before returning the session to callers:

```java
// After session creation, warm up with dummy input
var dummyInput = OnnxTensor.createTensor(env, dummyTokenIds);
session.run(Map.of("input_ids", dummyInput));
dummyInput.close();
```

This ensures:
- Arena is pre-sized for real workload
- cuDNN algorithms are selected
- Memory pattern is established
- First real inference has consistent latency

### `cudnn_conv_algo_search` configuration

| Value | Session create impact | First inference impact | Best for |
|-------|----------------------|----------------------|----------|
| `EXHAUSTIVE` (default) | Minimal for transformers | Slower first run | Max steady-state throughput |
| `HEURISTIC` | Minimal | Faster first run | Latency-sensitive, session cycling |
| `DEFAULT` | Minimal | Same as HEURISTIC | General use |

For lazy session creation with frequent cycling, **`HEURISTIC`** is preferred
to minimize the warmup penalty.

Source: [CUDA EP docs](https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html)

---

## 10. Java-Specific Session Lifecycle Gotchas

### `OrtEnvironment` is a JVM-lifetime singleton

- `OrtEnvironment.getEnvironment()` returns the same instance every time.
- `close()` is a **no-op** since ORT 1.11 ([PR #10670](https://github.com/microsoft/onnxruntime/pull/10670)).
- Actual native cleanup happens via JVM shutdown hook.
- **Implication**: You cannot reset the CUDA context or arena allocators
  without restarting the JVM process.

### `OrtSession` implements `AutoCloseable` correctly

- `session.close()` releases native session handle via JNI.
- After close, all methods throw `IllegalStateException`.
- Pinned outputs are NOT owned by `Result` — must be closed separately.
- `Result` objects are `AutoCloseable` — use try-with-resources.

### Native memory leak on session cycling (issue #14641)

- **Java-specific**: Each session create/close cycle leaks a small amount of
  native memory (outside JVM heap). Over many cycles, this accumulates.
- **Root cause**: Arena memory is never returned to system. Each new session
  may extend the arena slightly even if the previous session used the same
  amount.
- **Mitigation**: Use shared arena allocator so all sessions reuse the same
  arena memory.

### Shared arena allocator — Java API availability

The C API provides `CreateAndRegisterAllocator()` / `CreateAndRegisterAllocatorV2()`
for shared arena configuration. **The Java API does NOT expose this method as of
ORT 1.24.** The Java `OrtEnvironment` class does not have a public
`createAndRegisterAllocator()` method.

**Workaround options:**
1. Use JNI to call the C API directly (fragile, version-dependent).
2. Rely on ORT's internal behavior: sessions created on the same
   `OrtEnvironment` with the same CUDA device ID may share some CUDA context
   resources (cuDNN handles, cuBLAS handles) even without explicit arena sharing.
3. Wait for Java API support (no open issue requesting this specifically).

### `OrtSession.SessionOptions` CUDA configuration

```java
var opts = new OrtSession.SessionOptions();
opts.addCUDA(0);  // or addCUDA(OrtCUDAProviderOptions) for detailed config

// Key options for memory pressure:
// - arena_extend_strategy: "kSameAsRequested" (0) vs "kNextPowerOfTwo" (1)
// - gpu_mem_limit: cap in bytes
// - cudnn_conv_algo_search: "HEURISTIC" for faster session creation
```

Source: [Java OrtSession.SessionOptions API](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html)

---

## Summary: Recommended Architecture for JustSearch

Based on all findings, the recommended pattern for managing 3 ORT CUDA sessions
(embedding, SPLADE, cross-encoder) on consumer GPUs:

### Tier 1 — Lazy lifecycle management (recommended, implement first)

1. **Create sessions on demand**: Don't create all 3 at startup. Create each
   session when its first inference is requested.
2. **Close after idle timeout**: After 30-60s of no inference calls, close the
   session to free model weights back to the arena's free list.
3. **Cache optimized graphs**: Use `setOptimizedModelFilePath()` to skip graph
   optimization on subsequent session creates (~200-500ms savings).
4. **Pre-warm after creation**: Run one dummy inference to size the arena and
   warm cuDNN.
5. **Use SEQUENTIAL mode**: Keep `ORT_SEQUENTIAL` for memory pattern/reuse.
6. **Use HEURISTIC conv search**: Set `cudnn_conv_algo_search = HEURISTIC` to
   minimize warmup penalty during session cycling.
7. **Cap arena per session**: Set `gpu_mem_limit` based on model requirements.

**Expected benefit**: Instead of 3 sessions permanently holding VRAM, only 1
session holds VRAM at a time. The arena grows to fit the largest model (SPLADE
~4GB) and is reused by smaller models (embedding ~1GB, cross-encoder ~0.5GB).

### Tier 2 — Shared arena allocator (if Java API adds support)

8. Register a shared CUDA arena allocator on the `OrtEnvironment`.
9. All sessions reuse the same arena — no memory growth on session cycling.
10. Set `session.use_env_allocators = 1` on each session.

**Blocked on**: Java API does not expose `CreateAndRegisterAllocator()`.
Could implement via JNI bridge.

### Tier 3 — Subprocess isolation (if memory leaks are unacceptable)

11. Run each ORT session in a separate child process.
12. Communicate via gRPC or pipes.
13. Process exit guarantees full GPU memory cleanup.

**Complexity**: Very high. Only justified if Tier 1 memory leaks cause OOM
crashes on 8 GB GPUs over multi-hour sessions.

### Expected session creation cost (Tier 1)

| Phase | Time | Notes |
|-------|------|-------|
| Session create (warm CUDA context) | 0.5-2s | BERT-class model, cached graph |
| Dummy warmup inference | 0.1-0.5s | Single batch, representative shape |
| **Total cold-start latency** | **0.6-2.5s** | Amortized across batch of 50-100 items |

For JustSearch's batch workloads (100 chunks per embedding batch, 50 docs per
SPLADE batch), this 1-2s cold start adds <2% overhead to a batch that takes
60-120s.

---

## Source URLs

### ORT Official Documentation
- [CUDA Execution Provider](https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html)
- [Memory Consumption](https://onnxruntime.ai/docs/performance/tune-performance/memory.html)
- [Graph Optimizations](https://onnxruntime.ai/docs/performance/model-optimizations/graph-optimizations.html)
- [Threading](https://onnxruntime.ai/docs/performance/tune-performance/threading.html)
- [Java Getting Started](https://onnxruntime.ai/docs/get-started/with-java.html)
- [Java OrtSession.SessionOptions API](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtSession.SessionOptions.html)
- [Java OrtEnvironment API](https://onnxruntime.ai/docs/api/java/ai/onnxruntime/OrtEnvironment.html)

### GitHub Issues — GPU Memory Release
- [#17142 — GPU memory not released in destructor](https://github.com/microsoft/onnxruntime/issues/17142)
- [#20548 — 320MB VRAM retained after session deletion](https://github.com/microsoft/onnxruntime/issues/20548)
- [#14641 — Memory grows after reloading model (Java)](https://github.com/microsoft/onnxruntime/issues/14641)
- [#11801 — Clear GPU memory without destroying session](https://github.com/microsoft/onnxruntime/issues/11801)
- [#25996 — Release inference VRAM without unloading model](https://github.com/microsoft/onnxruntime/issues/25996)
- [#14957 — Cannot release GPU memory](https://github.com/microsoft/onnxruntime/issues/14957)
- [#7463 — Release GPU memory without exiting process](https://github.com/microsoft/onnxruntime/issues/7463)

### GitHub Issues — Session Creation Performance
- [#5957 — Model loading too slow with onnxruntime-gpu (102s)](https://github.com/microsoft/onnxruntime/issues/5957)
- [#9990 — Session init takes long time on RTX GPUs (20-30min)](https://github.com/microsoft/onnxruntime/issues/9990)
- [#19022 — Session creation takes too long](https://github.com/microsoft/onnxruntime/issues/19022)
- [#3121 — First inference 10-20x slower](https://github.com/microsoft/onnxruntime/issues/3121)

### GitHub Issues — Arena / Allocator
- [#14526 — GPU memory much larger than ONNX model size](https://github.com/microsoft/onnxruntime/issues/14526)
- [Discussion #21577 — Shared arena allocator for CUDA](https://github.com/microsoft/onnxruntime/discussions/21577)
- [#20027 — Shared arena env allocator across modules](https://github.com/microsoft/onnxruntime/issues/20027)

### GitHub Issues — Thread Safety
- [#114 — Is InferenceSession.Run thread-safe?](https://github.com/microsoft/onnxruntime/issues/114)
- [Discussion #10107 — ORT thread safety](https://github.com/microsoft/onnxruntime/discussions/10107)

### Triton Inference Server (reference architecture)
- [triton#4966 — ONNX model unload does not free system memory](https://github.com/triton-inference-server/server/issues/4966)
- [triton#5841 — GPU memory leak when loading/unloading models](https://github.com/triton-inference-server/server/issues/5841)

### HuggingFace Optimum
- [Optimum GPU acceleration docs](https://huggingface.co/docs/optimum-onnx/onnxruntime/usage_guides/gpu)
- [Optimum ONNX Runtime models](https://huggingface.co/docs/optimum-onnx/onnxruntime/package_reference/modeling_ort)

### Other
- [NVIDIA blog — CUDA and TensorRT EPs in ORT](https://developer.nvidia.com/blog/end-to-end-ai-for-nvidia-based-pcs-cuda-and-tensorrt-execution-providers-in-onnx-runtime/)
- [ORT Java OrtEnvironment singleton change PR #10670](https://github.com/microsoft/onnxruntime/pull/10670)
