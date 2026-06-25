---
title: "386: SpladeEncoder Pinned Output Data Race"
type: tempdoc
status: done
created: 2026-04-09
---

> NOTE: Noncanonical doc. May drift.

# 386: SpladeEncoder Pinned Output Data Race

## Purpose

Fix a data race in `SpladeEncoder` where unsynchronized mutable pinned
output state is accessed from multiple threads concurrently. This can
produce silently corrupt SPLADE sparse vectors during concurrent
search + indexing.

## Evidence (from tempdoc 311 Phase 6 investigation, 2026-04-09)

Concurrency analysis of the ORT session usage revealed that
`DefaultWorkerAppServices.wireSpladeEncoder()` wires the **same
SpladeEncoder instance** to both paths:

```java
public void wireSpladeEncoder(SpladeEncoder enc) {
    indexingLoop.setSpladeEncoder(enc);   // backfill path (indexing-loop thread)
    searchService.setSpladeEncoder(enc);  // query-time path (gRPC Netty threads)
}
```

The `indexing-loop` thread calls `encodeBatch()` → `runOnnxInference()`
for SPLADE backfill. gRPC Netty threads call `encode(String query)` →
`runOnnxInference()` for query-time SPLADE in
`SearchOrchestrator.prepareSpladeWeights()`.

Both paths enter `runOnnxInference()` which accesses these
**unsynchronized mutable instance fields** (SpladeEncoder lines ~98-103):

```java
// --- Pinned output state (single-threaded access from indexing-loop) ---
private final String outputName;
private OnnxTensor pinnedOutputTensor;
private FloatBuffer pinnedOutputBuffer;
private int pinnedBatchSize;
private int pinnedSeqLen;
private boolean pinnedOutputsSupported = true;
```

The comment "single-threaded access from indexing-loop" is inaccurate.

## Failure Modes

1. **Shape mismatch:** Thread A (batch=8, seqLen=256) calls
   `ensurePinnedOutput()` which allocates a pinned tensor sized for
   8×256×30522. Thread B (batch=1, seqLen=64) enters concurrently and
   sees `pinnedBatchSize=8` — uses a tensor sized for the wrong batch.
   The 4-arg `session.run()` writes into a buffer with wrong dimensions.

2. **Buffer corruption:** Thread A reads `pinnedOutputBuffer` while
   Thread B's `session.run()` is writing into the same buffer from the
   GPU. The float data is torn (partially old, partially new values).

3. **Use-after-close:** `ensurePinnedOutput()` closes the old
   `pinnedOutputTensor` and creates a new one. A concurrent thread
   holding the old tensor reference passes it to `session.run()` →
   native memory access violation or ORT crash.

4. **GPU release during inference:** `releaseGpuSession()` calls
   `closePinnedOutput()` via `onBeforeGpuRelease` without acquiring
   the GPU inference semaphore. If the indexing-loop thread holds
   the semaphore and is mid-inference with the pinned tensor, the
   tensor is closed underneath it → native crash.

## Impact

- Silently wrong SPLADE sparse vectors for search queries (query-time
  SPLADE encodes single queries while backfill processes batches)
- Potential native crash from use-after-close on the pinned OnnxTensor
- Only manifests under concurrent search + indexing load (not during
  jseval with `--max-queries 0`)

## Root Cause

The `SpladeEncoder` was originally designed for single-threaded use from
the indexing loop. When query-time SPLADE was added (via
`SearchOrchestrator.prepareSpladeWeights()`), the same instance was
shared without adding synchronization for the pinned output state.

The GPU inference semaphore (tempdoc 311 Phase 6) serializes GPU
`session.run()` calls, and the lease wraps the entire `runOnnxInference()`
body — so `ensurePinnedOutput()` and buffer reads ARE inside the lease.
However, CPU sessions get **no-op leases** (no semaphore). The race
manifests when:

1. **CPU-only mode** (no GPU configured, GPU creation failed): both
   threads get CPU sessions with no-op leases → fully concurrent →
   unprotected pinned output access.
2. **GPU released** (main claims GPU): `gpuSessionReleasing = true` →
   `selectSession()` returns CPU → subsequent calls unserialized.
3. **GPU release callback race**: `releaseGpuSession()` calls
   `onBeforeGpuRelease` → `closePinnedOutput()` **without acquiring
   the GPU inference semaphore**. If the indexing-loop thread holds
   the semaphore mid-inference, `closePinnedOutput()` closes the tensor
   underneath it → use-after-close on native memory.

### Failure mode 4 (GPU release during inference)

`releaseGpuSession()` (OrtSessionManager:236) sets `gpuSessionReleasing`
then immediately calls `onBeforeGpuRelease.run()` → `closePinnedOutput()`.
It does not acquire the GPU inference semaphore first. A thread holding
the semaphore mid-inference with the pinned tensor sees the tensor closed
and nulled under it. This matches NVIDIA TensorRT's explicit warning:
"destroying an execution context while inference is in-flight is
undefined behavior."

## Industry Research (2026-04-09)

Every major inference framework converges on the same answer: mutable
output state must be per-owner, never shared across concurrent callers.

| Framework | Pattern | Source |
|-----------|---------|--------|
| **ONNX Runtime** | `session.run()` is thread-safe, but user-managed output tensors are NOT. OnnxTensor Javadoc: "accessing a buffer reference should be considered problematic when multiple threads hold references." | [Discussion #10107](https://github.com/microsoft/onnxruntime/discussions/10107) |
| **TensorRT** | "Using a context concurrently in different threads results in undefined behavior." Canonical: one execution context per thread, each with own output buffers and CUDA stream. | [NVIDIA Forums](https://forums.developer.nvidia.com/t/concurrent-inference-in-a-single-iexecutioncontext/111728) |
| **Vespa** (production ORT) | Creates "an ONNX Runtime session for each feature and thread" with per-thread pre-allocated output vectors. 50% throughput gain from single-threaded-per-session + system-level parallelism. | [Vespa Blog](https://blog.vespa.ai/stateful-model-serving-how-we-accelerate-inference-using-onnx-runtime/) |
| **Triton** | Per-request `cudaMalloc` was serializing GPU. Fix: pre-allocated per-instance memory pools. | [Issue #1106](https://github.com/triton-inference-server/server/issues/1106) |
| **YOLO/Ultralytics** | "Instantiate a separate model within each thread." | [Thread-safe docs](https://docs.ultralytics.com/guides/yolo-thread-safe-inference/) |
| **Torch-TensorRT** | Pre-allocated output buffers are request-scoped. Cannot reuse across concurrent calls. | [Pre-allocated output example](https://docs.pytorch.org/TensorRT/tutorials/_rendered_examples/dynamo/pre_allocated_output_example.html) |

No framework supports sharing a single mutable output buffer across
threads. The four industry patterns are:

1. **Per-thread state** (ThreadLocal / per-context) — full isolation,
   memory × threads
2. **Per-call allocation** (heap path) — zero shared state, allocation
   overhead per call
3. **Pooled allocation** (object pool of buffers) — amortized allocation,
   bounded memory
4. **Single-owner with routing** — zero overhead, relies on architectural
   guarantee that only one thread touches the mutable state

## Proposed Fix

### Fix A (primary): Separate query path from pinned output

Query-time SPLADE (`encode(String query)`) always processes batch=1.
Pinned outputs are a batch optimization — unnecessary for single queries.
Route query-time calls through a dedicated method that uses per-call
heap allocation, bypassing pinned output state entirely.

This matches industry pattern #4 (single-owner with routing): the
indexing-loop thread remains the sole owner of pinned output state.
Query-time threads use per-call allocation (pattern #2). The comment
"single-threaded access from indexing-loop" becomes accurate again.

```java
// In SpladeEncoder:
public Map<String, Float> encode(String query) {
    // ... tokenize ...
    return runOnnxInferenceSingle(inputIds, attentionMask, tokenTypeIds);
}

private Map<String, Float> runOnnxInferenceSingle(...) throws OrtException {
    // Uses heap path only — no pinned output state touched.
    // Thread-safe: per-call tensor allocation, no shared mutable state.
}
```

No locks, no contention, no perf impact on the batch path.

### Fix B (secondary): GPU release semaphore guard

`releaseGpuSession()` must acquire `gpuInferenceSemaphore` before
calling `onBeforeGpuRelease` (which calls `closePinnedOutput()`).
This ensures no in-flight GPU inference is using the pinned tensor
when it gets closed.

```java
// In OrtSessionManager.releaseGpuSession():
gpuSessionReleasing = true;
gpuInferenceSemaphore.acquireUninterruptibly(); // wait for in-flight
try {
    if (onBeforeGpuRelease != null) onBeforeGpuRelease.run();
    // ... close session ...
} finally {
    gpuInferenceSemaphore.release();
    gpuSessionReleasing = false;
}
```

Fix A eliminates the multi-thread contention on pinned output. Fix B
eliminates the GPU release teardown race. Both are needed.

### Rejected alternatives

**Synchronized pinned output access:** Serializes all SPLADE inference
(batch and single) even on CPU sessions where contention isn't needed.

**ThreadLocal pinned state:** GPU memory multiplied by thread count;
pinned tensors are large (~476 MB at batch=16 FP16 seqLen=512).
Vespa uses this pattern but with CPU-only sessions — unsuitable for
our GPU path.

## Implementation Checklist

- [x] **Fix A: Separate query path** — new `runOnnxInferenceSingle()`
  in `SpladeEncoder.java` using heap-only allocation. Route
  `encode(String)` through it.
- [x] **Fix B: GPU release semaphore guard** — acquire
  `gpuInferenceSemaphore` in `OrtSessionManager.releaseGpuSession()`
  before calling `onBeforeGpuRelease`.
- [x] **Update comment** at SpladeEncoder:98 — remove the inaccurate
  "single-threaded access" caveat, replace with threading contract.
- [x] **Tests** — worker-core, worker-services, ort-common modules.
- [x] **Compile + format** — spotlessApply, build -x test.

## Related Issues

- **278 Entry 18:** SpladeEncoder re-creation bug (every ~15s on config
  reload). Related but separate — that bug resets `gpuSessionAttempted`
  and `firstEncodeLogged`, not the pinned output state.
- **311 Phase 6:** GPU inference semaphore. Serializes GPU
  `session.run()` but CPU sessions get no-op leases. The semaphore
  accidentally protects pinned output when both threads use GPU, but
  provides zero protection for CPU paths.

## Code Locations

| File | Relevance |
|------|-----------|
| `worker-core/.../splade/SpladeEncoder.java:98-103` | Unsynchronized pinned output fields |
| `worker-core/.../splade/SpladeEncoder.java:ensurePinnedOutput()` | Allocates/resizes pinned tensor (not thread-safe) |
| `worker-core/.../splade/SpladeEncoder.java:runOnnxInference()` | Entry point for both batch and single-doc paths |
| `worker-services/.../DefaultWorkerAppServices.java:wireSpladeEncoder()` | Shares instance across threads |
| `worker-services/.../GrpcSearchService.java` | Query-time SPLADE call site |
| `indexer-worker/.../server/KnowledgeServer.java` | Backfill SPLADE call site |

## Verification

```bash
# After fix: confirm batch path still uses pinned outputs
./gradlew.bat :modules:worker-core:test
# Confirm query path works
./gradlew.bat :modules:worker-services:test
# Full pipeline (no regression)
cd scripts/jseval && python -m jseval run --dataset scifact --pipeline --start-backend --clean --modes hybrid
```
