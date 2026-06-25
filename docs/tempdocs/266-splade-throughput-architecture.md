---
title: "266: SPLADE Throughput Architecture"
type: tempdoc
status: done
created: 2026-03-08
---

> NOTE: Noncanonical investigation doc. May drift.

# 266: SPLADE Throughput Architecture

## Purpose

Define how JustSearch should reason about SPLADE throughput after the mixed-
corpus lane exposed a real tradeoff between:

- desktop responsiveness-first indexing
- benchmark-oriented sparse throughput
- Windows native-runtime complexity for ONNX Runtime CUDA

This doc is the dedicated home for the throughput question that was previously
spread across tempdocs 251, 258, and 261.

---

## Current State (2026-03-08)

The repo now has a working backend-only GPU SPLADE path for benchmark-oriented
lanes, but the broader throughput question is still architectural.

What is already true:

1. The current indexing path is intentionally narrow.
   - `SSOT/pipelines/indexing.v1.json` sets `concurrency: 1`
   - `LoopPacingPolicy.java` sets `POLL_BATCH_SIZE = 1`
   - `IndexingLoop.java` runs a single `indexing-loop` thread
2. SPLADE is currently executed inline during indexing.
   - `IndexingDocumentOps.buildDocument(...)` calls
     `spladeEncoder.encode(...)` synchronously per document
3. The worker can now run backend-only GPU SPLADE cleanly.
   - CPU ORT and GPU ORT are no longer co-resolved in the backend-only worker
     path
   - `JUSTSEARCH_AI_EMBED_ENABLED=false` now actually suppresses worker
     embedding startup
   - `SpladeEncoder` uses a narrow CUDA dependency preload strategy via
     `onnxruntime.native.path`
4. A separate eval-control-plane bug was fixed.
   - the first fresh GPU run (`gpu-r5`) looked like a bad SPLADE result, but
     the real problem was that the BEIR wait path accepted a transient early
     `IDLE` state before the mixed corpus had actually enqueued
   - the corrected run is `gpu-r6`, not `gpu-r5`
   - `gpu-r6` completed successfully, so throughput is no longer the blocker
     for the first mixed-corpus SPLADE quality interpretation

Implication:

- low observed hardware utilization during the mixed SPLADE lane is not, by
  itself, evidence of a deadlock
- the repo is currently paying for a narrow, synchronous indexing design on
  purpose
- throughput work should therefore be treated as an explicit architecture
  decision, not as a small monitoring tweak

---

## Repo-Local Findings

### 1. The current bottleneck is mostly architectural, not merely config drift

The mixed-corpus SPLADE lane exposed a worker path that is intentionally
serialized:

- claim one job
- extract one file
- compute sparse representation inline
- write chunks / metadata
- move to the next file

This means:

- CPU use may stay modest even while work is progressing
- GPU use can remain lower than expected if tokenization, extraction, queue
  bookkeeping, and Lucene writes dominate wall-clock time
- widening the outer loop is a broad change because it pushes against a
  responsiveness-first design that currently exists in SSOT and code

### 2. The repo already has a stronger seam than the top-level loop

The codebase already contains a better optimization seam than "make the whole
indexer more concurrent":

- `SpladeEncoder.encodeBatch(...)` already exists
- `SpladeBackfillOps` already works on batches of pending document IDs
- the search-eval harness already treats SPLADE as a readiness-gated
  capability, so deferred sparse completion is evaluation-compatible as long as
  the lane waits for readiness before querying

This makes SPLADE-specific batching materially easier than a general
multi-width indexing-loop rewrite.

### 3. Windows native handling is a first-class design constraint

The active lane proved that Windows native loading is not incidental glue:

- process-wide `PATH` mutation was explicitly rejected because it broke DJL
  tokenizer native loading in the same worker process
- the accepted design is:
  - local ORT CUDA runtime
  - explicit `onnxruntime.native.path`
  - narrow preload of only required CUDA/cuDNN DLLs inside `SpladeEncoder`

Any future architecture must preserve or improve this isolation story.

---

## External Official-Source Findings

### ONNX Runtime

- ONNX Runtime's default CPU execution path already uses intra-op threading and
  can utilize physical CPU cores without any outer-loop concurrency changes.
  That means widening the JustSearch indexing loop is not the first obvious
  CPU throughput lever.
- The CUDA Execution Provider exposes a configurable device-memory arena
  (`gpu_mem_limit`), so backend-only GPU SPLADE is a supported configuration
  rather than a hack.
- ONNX Runtime's Python package now exposes `preload_dlls()` for CUDA/cuDNN
  DLL loading on Windows, which materially improves the case for a Python
  sidecar if JustSearch ever wants to move sparse GPU inference out of the
  Java worker.

### Gradle

- Gradle capabilities are meant for "these two dependencies provide the same
  functionality" conflicts.
- That is directly relevant to preventing CPU ORT and GPU ORT from appearing in
  the same runtime graph again.

### DJL

- DJL's WorkLoadManager is explicitly designed to handle workers, routing, and
  batching for inference workloads.
- It is therefore a realistic Java-native option if JustSearch wants a batched
  sparse-inference subsystem without immediately adopting a separate external
  server.

### Triton

- Triton is explicitly built around server-side model execution, worker pools,
  and dynamic batching.
- It is much heavier than the current JustSearch posture, but it is the clearest
  off-the-shelf solution if the project ever decides that sparse GPU inference
  should become a dedicated service rather than an in-process worker concern.

### TensorRT RTX

- ONNX Runtime's TensorRT RTX Execution Provider is optimized for RTX 30xx+
  client GPUs and advertises faster model compile/load and runtime cache
  support.
- It is attractive only if JustSearch is willing to narrow hardware assumptions
  toward RTX-class clients and invest in a more NVIDIA-specific acceleration
  path.

---

## Option Analysis

### Option A: Keep the current behavior as the default product posture

Recommendation: **Yes, as the product default.**

Why:

- It matches the repo's current responsiveness-first design.
- It avoids turning background indexing into a resource hog on desktop systems.
- It does not require large architectural change.

Limitation:

- It is a poor fit for benchmark-style sparse lanes that are intentionally
  throughput-oriented.

### Option B: Use backend-only GPU SPLADE for benchmark/eval lanes

Recommendation: **Yes. This is the first lever.**

Why:

- It is the cheapest throughput improvement with the smallest blast radius.
- It is already implemented in the repo.
- It keeps interactive embedding/chat contention out of the lane by combining:
  - `JUSTSEARCH_AI_EMBED_ENABLED=false`
  - backend-only launch
  - SPLADE-specific GPU enablement and explicit GPU memory budget

Current stance:

- This should become the standard posture for benchmark-oriented sparse eval
  lanes, not the desktop default for every indexing workload.

### Option C: Batch SPLADE at the sparse seam

Recommendation: **Best next code change after GPU enablement.**

Why:

- `encodeBatch(...)` already exists
- the batching seam is narrower than the whole indexing loop
- it aligns with how GPUs want to be fed work
- it avoids immediately rewriting queueing, extraction, or Lucene write policy

Best first place to try it:

- `SpladeBackfillOps`, or a deferred sparse-encoding phase with explicit
  readiness gating

### Option D: Increase indexing-loop width / claim batch size

Recommendation: **Do not do this first.**

Why:

- it conflicts directly with the current responsiveness-first indexing posture
- it risks oversubscription because ONNX Runtime already parallelizes work
  internally on CPU
- it broadens the blast radius from "sparse throughput" to "all indexing
  behavior"

This remains implementable, but it is the riskiest option and the weakest
first move.

### Option E: Move sparse GPU inference into a dedicated sidecar

Recommendation: **Strongest long-term architecture option if batching inside
the worker still proves awkward.**

Why:

- isolates native runtime conflicts from the main worker
- creates a natural place for batching and admission control
- decouples sparse throughput policy from the main indexing loop

Possible forms:

- Java sidecar with ORT CUDA
- Java sidecar with DJL WorkLoadManager
- Python ORT CUDA sidecar

This is the clearest route if JustSearch decides sparse inference deserves its
own process boundary.

### Option F: Use a heavier external inference stack

Recommendation: **Only if the project explicitly wants a service-style GPU
layer.**

Candidates:

- Triton Inference Server
- TensorRT RTX EP

These can outperform the current posture for the right workloads, but they
raise the operational and packaging cost materially. They are not good
"default desktop app" first steps.

---

## Recommended Order

1. **Keep the current narrow indexing posture as the product default.**
2. **Use backend-only GPU SPLADE for benchmark/eval lanes.**
   - already implemented
3. **If sparse throughput is still too slow, batch SPLADE before widening the
   outer loop.**
4. **If batching inside the worker becomes awkward, move sparse inference to a
   dedicated sidecar.**
5. **Only revisit claim-batch / loop-width changes after the narrower options
   are exhausted.**
6. **Treat Triton / TensorRT RTX as later-stage alternatives, not the first
   response.**

---

## Concrete Follow-Up Candidates

### Repo-local hardening

- add a Gradle capability rule preventing CPU ORT and GPU ORT from appearing
  in the same runtime graph
- expose explicit sparse-provider status in `/api/status` so logs are not the
  only truthful CUDA signal

### Local throughput prototype

- prototype batched sparse encoding in `SpladeBackfillOps`
- compare:
  - current inline per-doc sparse path
  - backend-only GPU SPLADE without batching
  - backend-only GPU SPLADE with batching

### Longer-term architecture prototype

- define a `SparseEncoderService` boundary and pilot either:
  - a Java ORT sidecar
  - a Python ORT sidecar using `preload_dlls()`

---

## Decision Snapshot

Current confidence:

- keep current default posture: **high**
- backend-only GPU SPLADE for benchmark lanes: **high**
- SPLADE batching before loop widening: **medium-high**
- loop-width / claim-batch rewrite as first response: **low**
- dedicated sparse sidecar as the best long-term architecture: **medium-high**

Current best answer:

- product default should stay narrow and responsiveness-first
- benchmark sparse lanes should use backend-only GPU SPLADE
- the next meaningful code experiment should be SPLADE batching, not a
  generalized indexing-loop concurrency rewrite

---

## Official Sources

- [ONNX Runtime CUDA Execution Provider](https://onnxruntime.ai/docs/execution-providers/CUDA-ExecutionProvider.html)
- [ONNX Runtime Thread Management](https://onnxruntime.ai/docs/performance/tune-performance/threading.html)
- [Build with Different Execution Providers](https://onnxruntime.ai/docs/build/eps.html)
- [Gradle Capabilities](https://docs.gradle.org/current/userguide/component_capabilities.html)
- [DJL WorkLoadManager](https://docs.djl.ai/master/docs/serving/wlm/index.html)
- [DJL Serving Architecture](https://docs.djl.ai/master/docs/serving/serving/docs/architecture.html)
- [NVIDIA Triton Inference Server User Guide](https://docs.nvidia.com/deeplearning/triton-inference-server/user-guide/docs/index.html)
- [ONNX Runtime TensorRT RTX Execution Provider](https://onnxruntime.ai/docs/execution-providers/TensorRTRTX-ExecutionProvider.html)

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

SPLADE throughput architecture (337 lines) referencing ORT CUDA EP, DJL WorkLoadManager, Triton, TensorRT-RTX. Architecture-exploration tempdoc; current SPLADE in `modules/indexer-worker/splade/` either consumed or evolved past the proposals.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

