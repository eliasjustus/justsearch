---
title: "286: Application Startup Performance"
type: tempdoc
status: done
created: 2026-03-13
updated: 2026-03-14
---

## Problem

The developer iteration loop is: edit → build → start → verify. Tempdoc 284
optimized the build phase (64s → 35s). This tempdoc targets the start phase — the
time from launching the application to it being ready to serve requests.

The start phase matters for both dev iteration (edit-restart cycles) and production
cold start (user launches app). It covers Head (UI Host) startup, Worker (Knowledge
Server) startup, ONNX model loading, and index opening.

## Measured Baselines (2026-03-13)

Dev stack via MCP `start` (installDist). Data: `modules/ui-web/.dev-data` with
existing index. No embedding model in dev; SPLADE model present (507 MB).

### Head process (HeadlessApp) — 2460ms total

| Phase | Time | What it does |
|-------|------|--------------|
| settings | 110ms | Load UI settings from registry/file |
| telemetry | 77ms | Initialize counters and gauges |
| policy | 18ms | Load enterprise policy snapshot |
| facade | 164ms | AppFacadeBootstrap (services, inference manager) |
| api | 247ms | Javalin HTTP server bind (Jetty init) |
| portEmit | 1ms | Write port file + stdout flush |
| worker | 1833ms | Spawn JVM + awaitPort + gRPC connect + late-bind |
| **total** | **2460ms** | |

Head HTTP ready at ~627ms. Remaining 1833ms is waiting for the Worker.

### Worker process (KnowledgeServer) — cold vs warm

| Phase | Cold | Warm | What it does |
|-------|------|------|--------------|
| telemetry | 73ms | 68ms | Initialize Worker telemetry |
| signalBus | 10ms | 11ms | Open MMF (worker_signal.lock) |
| jobQueue | 124ms | 148ms | SQLite open + recoverStuckJobs |
| lucene | 137ms | 360ms | Open Lucene directories + IndexWriter + SearcherManager |
| embedding | 4ms | 3ms | No model found → skip |
| **aiModels** | **2118ms** | **363ms** | SPLADE ORT session + NER + disambiguation |
| grpc | 282ms | 200ms | Netty gRPC server bind |
| loop | 0ms | 0ms | Start indexing loop thread |
| **total** | **2753ms** | **1157ms** | |

Cold = first start after ORT cache invalidation (`EXTENDED_OPT` graph optimization).
Warm = cached `.onnx.optimized` file loaded with `NO_OPT`.

**Variance note:** Warm Worker total varies 948-1274ms across runs (same binary,
same data). Phases like `lucene` (258-352ms) and `aiModels` (305-465ms) fluctuate
with OS page cache state, background processes, and Windows Defender activity. Any
optimization under ~100ms is within noise without 10+ samples.

### End-to-end timeline

```
0ms     Head main() starts
110ms   Settings loaded
627ms   HTTP port bound — dev-runner and Tauri unblocked (degraded mode)
~700ms  Worker JVM spawned (async, ForkJoinPool)
~1700ms Worker class loading complete, KnowledgeServer.start() begins
~2860ms Worker gRPC ready, port written to MMF
~2960ms Head discovers port (≤100ms poll)
2460ms  Late-bind complete — fully operational
```

## Root Causes

### Model loading is IO-bound on 500+ MB sequential reads

The warm-path 363ms for SPLADE is dominated by reading 507 MB from SSD (~1.5 GB/s
= ~340ms). Non-ORT work (tokenizer 466 KB, vocab 232 KB) totals 15-30ms. The cold
path adds `EXTENDED_OPT` graph optimization (CPU-intensive BERT node fusion) + 507 MB
write-back, totalling 2118ms.

The `OnnxSessionCache` strategy is correct — pre-generating optimized graphs at
build time is impractical (machine-specific, ORT-version-specific, 500+ MB each).
Current strategy: generate once, cache forever, invalidate on model change or ORT
version bump.

**Scaling risk:** Each additional model adds ~300-400ms warm *sequentially*. With
both embedding (521 MB) and SPLADE (507 MB), warm aiModels would be ~700ms serial.
This grows linearly with model count. → Item 1 (parallelize), Strategy A (defer past
gRPC readiness)

### Worker init is fully sequential despite 4 independent phases

`KnowledgeServer.start()` runs all phases on the main thread with no parallelism.
Lucene, EmbeddingService, SpladeEncoder, and DisambiguationService have no data
dependencies on each other — they only share trivial prerequisites (telemetry,
signalBus, config) that complete in <200ms. Note: `EmbeddingService` itself supports
lazy init (double-checked lock), but `KnowledgeServer` calls `initialize()` eagerly.
With a model present, this would add ~300-400ms to the serial chain.

ORT C API documents `OrtApi::CreateSession` as thread-safe. The Java wrapper delegates
directly via JNI. No synchronization exists around `createSession()` calls in this
codebase (`SpladeEncoder`, `OnnxEmbeddingEncoder`, `CrossEncoderReranker` all create
sessions without locks). Treat as safe for concurrent calls; verify with a
parallel-creation test before relying on this for Strategy A.

Parallel warm cost: max(360, 363, 363, 20) = 363ms vs serial 1106ms. → Item 1
(measured: no improvement with single model due to SSD IO contention; needs
multi-model to validate)

### gRPC Netty creates 16 threads for a single-client IPC channel

`NettyServerBuilder` defaults to `2 × availableProcessors` NIO event loop threads.
On 8 cores: 16 OS threads, each requiring stack allocation and kernel thread creation.
For a loopback single-client channel (Head → Worker), 1 boss + 1 worker suffices.

Additionally, `WorkerModelDiscovery.discoverAll()` runs synchronously inside
`KnowledgeServerGrpcWiring.createGrpcServer()` — 8 filesystem stat calls to probe
for reranker/citation-scorer models. Adds 20-80ms on cold NTFS metadata cache.

No SSL/TLS (correct for loopback). Route count (~3 services) irrelevant. → Item 4
(measured: reducing to 1+1 threads was 36ms *slower*; thread creation is not the
bottleneck — channel/selector init dominates)

### Javalin/Jetty creates 8 min-threads at startup

Jetty's default `QueuedThreadPool` eagerly creates 8 threads during `Server.start()`.
A loopback desktop app with one concurrent user needs 2 (1 acceptor + 1 handler).
Remaining cost (connector/selector init + class loading) is largely irreducible.

No SSL, no custom connectors. Route count (~80) adds <5ms. → Item 5
(likely <15ms based on Item 4 measurement — thread creation is cheap)

### TieredStopAtLevel=1 conflicts with AOT cache on JDK 25

The AOT cache is assembled with C2. Running with `-XX:TieredStopAtLevel=1` bypasses
all C2-compiled methods, wasting the cache entirely. These are mutually exclusive.

| Strategy | Startup saving | Steady-state impact |
|----------|---------------|---------------------|
| AOT cache alone | 1-1.5s | None |
| TieredStopAtLevel=1 alone | 0.3-0.5s | 30%+ throughput penalty |
| Both together | 0.3-0.5s (cache wasted) | 30%+ throughput penalty |

AOT wins 3-5x. Worker is long-lived and needs C2 for indexing throughput. → Item 2

### AOT cache infrastructure exists but is unused in dev

`WorkerSpawner` checks for `modules/ui/build/aot-dev/worker/worker.aot` and adds
`-XX:AOTCache=` if present. Gradle task `generateDevWorkerAotCache` exists with
proper `inputs.dir` / `outputs.dir` wiring. The file has never been generated.

Cache self-invalidates via classpath fingerprinting when any JAR changes. In active
Java development, JARs change on every rebuild — cache is only effective for repeated
restarts between code changes (UI/config iteration, not Java editing). → Item 3

### Worker dies with Head on every restart

The `WindowsJobObject` sets `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` — when Head exits
(cleanly or via crash), the OS kills the Worker. A secondary software "suicide pact"
via heartbeat (5s stale threshold, `MmfWorkerSignalBus.shouldDie()`) provides backup.

This is correct for production (prevents orphaned Workers holding Lucene write locks).
But in dev, it means every Head restart pays the full Worker cold start: JVM spawn
(1.7s) + model loading (363ms+) + gRPC bind (200ms+). → Strategy B

## Worker Init Dependency Graph

```
Phase 0: telemetry ──────────────────────────────────────┐
Phase 1: signalBus ────────────────────────────────────┐ │
Phase 2: jobQueue ←── needs telemetry                  │ │
Phase 3: config + IndexRootLock + generation layout    │ │
                                                       │ │
         ┌─── can run in parallel after phase 3 ───┐   │ │
         │                                         │   │ │
Phase 4: Lucene          ←── needs phase 3 only    │   │ │
Phase 3.5: Embedding     ←── needs ConfigStore (set in main())  │ │
Phase 6.1.5: SPLADE      ←── needs signalBus + ConfigStore      │ │
Phase 6.2: Disambiguation ←── needs dataDir only   │   │ │
         │                                         │   │ │
         └─── barrier: all must complete ──────────┘   │ │
                                                       │ │
Phase 3.7: EmbeddingCompatController ←── needs Lucene + Embedding
Phase 6: IndexingLoop construction ←── needs all above
Phase 4b: gRPC server create + start ←── needs all above
Phase 5: writePort to MMF ←── needs gRPC server
Phase 6b: IndexingLoop.start() ←── needs gRPC server
```

## Implementation Items (near-term)

### Measured (2026-03-13)

Items 1 and 4 were implemented and A/B tested (5 runs each, warm path). Neither
showed measurable improvement. System variance across runs was ~100-200ms, making
small improvements undetectable without many more samples.

- [x] 1. ~~**Parallelize Worker init phases**~~ — **No improvement measured.**
  Ran SPLADE + Disambiguation as `CompletableFuture` tasks overlapping Lucene.
  - With only one heavy model (SPLADE), the main thread finishes embedding in 3ms
    and immediately blocks on the SPLADE future join — nothing to overlap.
  - SSD IO contention: concurrent 507 MB SPLADE read + Lucene segment opens on the
    same NVMe increased `lucene` phase by ~60ms, negating any parallelism benefit.
  - **Requires multiple heavy models (SPLADE + embedding) to show benefit.** When
    both are present, parallel IO for two 500 MB reads should save ~300-400ms —
    but this could not be verified in current dev environment (no embedding model).
  - Reverted. Architecturally correct but premature until multi-model is the norm.
  - **Re-evaluation note (2026-03-14):** Items 2,6,7,8 baseline confirms embedding
    model now present (463ms). Parallel warm cost: max(360 Lucene, 363 SPLADE,
    463 embedding) ≈ 463ms vs serial ~1186ms — potential ~720ms saving. Not
    re-measured; descoped to follow-up.

- [x] 4. ~~**Right-size gRPC Netty thread pools**~~ — **No improvement measured.**
  Set `NioEventLoopGroup(1)` for boss + worker groups (down from default 16).
  - gRPC phase median went from 188ms → 224ms (+36ms). Worse, not better.
  - Netty thread creation is ~1-2ms per thread — never the bottleneck. The cost
    is dominated by channel initialization, selector setup, and service registration.
  - `WorkerModelDiscovery.discoverAll()` does 2 × `OnnxModelDiscovery.resolve()`
    (~4-8 filesystem stat calls). Measured <5ms warm — not worth extracting.
  - Reverted. The original defaults are fine for a startup-insensitive phase.

### Items 2, 6, 7, 8 — JVM flag batch (measured 2026-03-13)

All four flags implemented in `WorkerSpawner.java` (`buildCommand()`) and A/B tested
(5 runs each, same environment, warm path only). Environment has embedding model
installed (unlike original baseline), so absolute timings are higher.

**Baseline (original flags, 5 runs):**
| Run | total | embedding | aiModels | lucene | grpc |
|-----|-------|-----------|----------|--------|------|
| 1 | 2066 | 463 | 901 | 307 | 196 |
| 2 | 2383 | 536 | 1126 | 311 | 197 |
| 3 | 1917 | 397 | 879 | 250 | 191 |
| 4 | 2002 | 406 | 899 | 278 | 194 |
| 5 | 2892 | 668 | 1519 | 294 | 201 |
| **median** | **2066** | **463** | **901** | **294** | **196** |

**With JVM flags (Items 2+6+7+8, 5 runs, first is cold):**
| Run | total | embedding | aiModels | lucene | grpc |
|-----|-------|-----------|----------|--------|------|
| 1 (cold) | 3368 | 672 | 1698 | 557 | 216 |
| 2 | 2055 | 428 | 910 | 306 | 199 |
| 3 | 1972 | 406 | 907 | 253 | 200 |
| 4 | 1953 | 392 | 898 | 263 | 194 |
| 5 | 1990 | 404 | 893 | 297 | 194 |
| **median (warm)** | **1990** | **406** | **907** | **297** | **199** |

**Result: -76ms median (-3.7%).** Within variance (baseline range 1917-2892ms,
treatment range 1953-2055ms). Individual flag effects cannot be isolated from
the batch measurement — all four are low-cost, zero-risk flags worth keeping.

Per-phase observations:
- `grpc` unchanged (~196ms both) — `io.netty.machineId` had no effect on this
  machine (no virtual adapters active; machines with Hyper-V/VPN would see more)
- `embedding` slightly lower (463→406ms, ~12%) — could be noise or `-Xms=-Xmx`
  eliminating a GC resize during ORT session loading
- `aiModels` unchanged (901→907ms) — IO-bound on 507MB SPLADE model read
- Treatment variance noticeably tighter (102ms range vs 975ms baseline range),
  suggesting `-Xms=-Xmx` + `-XX:-UsePerfData` reduce jitter from GC resizing
  and Defender scanning

- [x] 2. **`-XX:-UsePerfData`** — implemented. Part of batch, individual effect
  not isolable. Eliminates hsperfdata writes to `%TEMP%`.
- [x] 6. **`-Dio.netty.machineId` / `-Dio.netty.processId`** — implemented. No
  measurable effect on this dev machine (no virtual adapters). Keeps proactive
  protection for end-user machines with Hyper-V/VPN/WSL2.
- [x] 7. **`-XX:+UseCompactObjectHeaders`** — implemented. Steady-state memory
  benefit (not visible in startup measurement).
- [x] 8. **`-Xms = -Xmx`** — implemented. Eliminates GC resize pauses. May
  contribute to tighter variance observed in treatment runs.

### Remaining (disposition noted per critical evaluation)

- [ ] 3. **Generate dev AOT cache** — **Descoped.** Exploratory JDK investigation,
  not a JVM-flag optimization. Deferred to a time-boxed attempt in a follow-up session.
  Only effective for repeated restarts between Java code changes (classpath
  fingerprinting invalidates on JAR change). Useful for: UI-only edits, config
  changes, repeated debugging restarts. Not useful during active Java development.
  - JDK 25 (JEP 514) simplifies to single-flag workflow:
    `java -XX:AOTCacheOutput=app.aot ...` (training + assembly in one run)
  - JEP 515 (JDK 25) adds method profiling to cache — JIT sees pre-warmed profiles
    and emits native code immediately. Additional 15-19% on top of JEP 483.
  - Measured: Spring PetClinic 42%, Kafka 59%, Flink 51% startup reduction
  - Expected saving for Worker: 40-60% of 1700ms class loading = **680-1020ms**
  - JNI caveat: native `.dll` loads (ORT, SQLite) are NOT cached. Java wrapper
    classes (`ai.onnxruntime.*`) are cached. Training classpath must match production.
  - **Tradeoffs:** Cache silently becomes a no-op after JDK patch updates (25.0.1 →
    25.0.2) — must regenerate as part of JDK update process. Cache file is 100-250 MB
    for 167 JARs. Head and Worker need separate caches (different classpaths). Use
    `-XX:AOTMode=auto` (default) — `on` mode fails hard if any constraint breaks.
    GC algorithm does NOT need to match between training and production (G1 is fine).
  - **Implementation confidence: LOW → MEDIUM (2026-03-14 reassessment).** JDK 25
    is confirmed as the project's JDK (`libs.versions.toml: jdk = "25"`), so JEPs
    483, 514, and 515 are all available. JEP 514 simplifies the workflow to a
    single training run. JEP 515 adds method profiling — real-world benchmarks show
    42% (Spring PetClinic), 15-19% additional from method profiling. The Gradle task
    `generateDevWorkerAotCache` exists but has never been run. Remaining unknowns:
    (a) what errors the task produces on first run,
    (b) how JNI-heavy classpath (ORT, SQLite) interacts with AOT training, (c) the
    "680-1020ms" estimate is extrapolated from Spring/Kafka benchmarks — this app's
    JNI-heavy startup may see much less benefit since native DLL loads are not cached.
    If the task works out of the box, measurement is straightforward. If it fails,
    debugging JDK AOT internals is beyond agent capability — escalate to human.

- [x] 5. ~~**Right-size Javalin/Jetty thread pool**~~ — **Dropped.**
  Item 4 (identical optimization on gRPC Netty side) was measured and was 36ms
  *worse*. Thread creation is not the bottleneck — connector/selector init
  dominates. Expected <15ms saving is within noise. Risk outweighs gain.

- [ ] 9. **Enable Lucene `MMapDirectory.setPreload(BASED_ON_LOAD_IO_CONTEXT)`** —
  **Disposition: Descoped — conditional on Defender exclusion benefit, not blocked.**
  pre-faults hot index files (`.tip`, `.kdi`, `.fdx`, `.tvx`) into page cache at
  open time.
  - File: `ComponentsFactory.java:104` — single `new MMapDirectory(resolvedPath)` call
  - These 4 file types are tiny (~0.17% of index) but accessed randomly on
    first query. Narrows the cold-warm variance gap.
  - Windows caveat: `madvise()` unavailable — preload is synchronous (direct
    memory fault), not async kernel advice. Adds small cost to startup, reduces
    first-query latency. Must benchmark on Windows.
  - One-line change: `mMapDir.setPreload(MMapDirectory.BASED_ON_LOAD_IO_CONTEXT);`
  - **Tradeoffs:** On Windows, preload is synchronous (blocks during page fault, not
    async kernel advice). Without Defender exclusions, preload triggers scanning of
    all preloaded pages at startup — can be slower than no preload. Only beneficial
    when combined with Strategy G (Defender exclusions). Memory pressure risk on 8GB
    machines with large indexes.
  - **Implementation confidence: MEDIUM.** API confirmed available in Lucene 10.4.0.
    Single construction site: `ComponentsFactory.java:104`. Remaining concerns:
    (a) optimizes first-query latency, not startup time — restart-timing methodology
    won't capture the benefit, (b) on Windows without Defender exclusions (Strategy G),
    preload may trigger synchronous page scanning — benefit is conditional. Descoped
    to follow-up.

## Long-Term Strategies

These go beyond the near-term items and address the fundamental architectural tensions
that cause startup cost.

### A: Deferred model loading (post-gRPC background init)

Current state by component:

- **`EmbeddingService`** — already lazy. Constructor stores only model path;
  `initialize()` is called on first `embed()` call (double-checked lock pattern).
  The 3ms measured at startup is `createWithAutoDiscovery()` probing for model files,
  not loading them. Removing the eager `embeddingService.initialize()` call in
  `KnowledgeServer.start()` is sufficient — no structural changes needed.

- **`SpladeEncoder`** — eagerly loads CPU ORT session, tokenizer, and vocabulary in
  constructor (`final` fields, 363ms warm / 2118ms cold). Used in **two** paths:
  (a) document encoding during backfill/ingest (background, latency-tolerant), and
  (b) query encoding during hybrid search (`SearchOrchestrator.prepareSpladeWeights()`,
  user-facing, latency-critical). When `SpladeIdfQueryEncoder` is active (IDF mode),
  query-time SPLADE bypasses the ONNX session entirely — but this is config-dependent.

- **`DisambiguationService`** — clean candidate for lazy loading. Only provides a
  cluster snapshot to search (returns null → search skips entity expansion) and
  entity tagging to IndexingLoop (background task).

The realistic strategy is not "true lazy" (load on first use) but **deferred
background init**: start gRPC immediately after Lucene opens, then load models in
a background thread pool. gRPC readiness (Head unblocked) no longer waits for model
IO. Models become available within seconds, transparently. The wiring supports this:
`KnowledgeServer` null-checks `spladeEncoderInstance` before passing to gRPC services
(lines 560, 579), and services receive encoders via post-construction setters, not
constructor injection. Strategy A works by keeping the encoder reference null during
background loading, then setting it atomically once ready. Note: `SpladeEncoder.cpuSession`
is a `final OrtSession` field — the encoder cannot exist without a loaded model. Deferred
init operates at the encoder-reference level (null → constructed), not at the session level.

```
Current:  [Lucene 360ms][Embedding 3ms][SPLADE 363ms][Disambig 20ms][gRPC 200ms]
Deferred: [Lucene 360ms][gRPC 200ms]
                        [──background: SPLADE 363ms, Embedding 3ms, Disambig 20ms──]
```

This is the **only strategy that decouples model count from gRPC readiness** — adding
models no longer delays the point at which Head can connect. However, it does not
eliminate model load cost entirely; it shifts it to a background thread. If a user's
first action after launch is a hybrid search and IDF mode is not active, the search
thread must wait for background SPLADE init to complete.

**Tradeoffs:**
- First hybrid search may block if SPLADE init is still in progress (mitigated by
  IDF query mode). First ingest blocks if embedding init is still in progress.
- `OrtEnvironment` must be created on main thread before spawning background workers
  (singleton, synchronized). Per C API documentation, `createSession()` is thread-safe;
  the codebase already calls it without synchronization. A parallel-creation smoke test
  is recommended before relying on this for Strategy A.
- Must handle UNAVAILABLE returns during loading window: gRPC service checks
  `AtomicBoolean` for model readiness and returns `UNAVAILABLE` (status 14) if not
  ready. Head has retry handling. This is the standard gRPC pattern for warm-up.
- Ready-state logging needed for observability — without it, "model loading" looks
  like a crash from monitoring perspective.

### B: Worker persistence across Head restarts (dev mode)

The `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` guarantee is essential for production but
wasteful in dev. If the Worker survived Head death, Head on restart could discover
the existing Worker via MMF port file and reconnect — skipping JVM spawn, model
loading, and index opening entirely.

Dev iteration becomes: edit → build Head only → start Head (627ms) → reconnect.

**Implementation sketch:**
- Dev-only flag (`justsearch.dev.worker.persistent=true`) disables Job Object
  kill-on-close
- Extend heartbeat stale threshold or disable heartbeat in persistent mode
- Head startup checks MMF for existing Worker port before spawning a new one
- Worker validates incoming Head connection (session token handshake)

**Tradeoff:** Orphaned Worker risk if dev-runner crashes. Mitigated by orphan
detection in `preflight` (already checks for stale processes). Not suitable for
production — Lucene write lock must be released on crash.

### C: Process pre-warming

Worker JVM is currently spawned after `AppFacadeBootstrap` completes (~164ms into
Head startup). Spawning earlier — even before settings load — overlaps JVM class
loading (1.7s) with Head init (627ms):

```
Current:  [--Head init 627ms--][-----Worker JVM 1700ms-----][--Worker init 1157ms--]
Pre-warm: [--Head init 627ms--]
          [-----Worker JVM 1700ms-----][--Worker init 1157ms--]
          Net saving: ~600ms overlap
```

**Implementation:** Spawn Worker with minimal args (dataDir, signalPath) at `main()`
entry. Worker starts class loading immediately. Head writes full config to shared
file. Worker reads config from signal bus extension and begins Lucene/model init.

**Tradeoff:** Two-phase startup protocol adds complexity to signal bus. Config must
be available before model init begins, so the overlap is limited to JVM class loading.

### D: OS page cache pre-warming via `PrefetchVirtualMemory`

On warm restart, model files may already be in the OS page cache from a prior Worker
run. A 507 MB read from page cache is a memcpy (~50ms) rather than a disk read
(~340ms). Today there's no awareness of this — ORT reads cause hundreds of small
page-fault-driven I/Os rather than a few large sequential reads.

Windows 8+ API `PrefetchVirtualMemory` (Kernel32.dll) issues large concurrent I/O
requests to pre-load mmap'd regions into the page cache. Proven: hard page faults
dropped from ~161,000 to **zero** for a ~500 MB file
([Windows-Dev-Performance #108](https://github.com/microsoft/Windows-Dev-Performance/issues/108)).
The call is non-blocking — returns immediately while I/O proceeds asynchronously.
Used by llama.cpp and other ML frameworks on Windows for model pre-loading.

**Implementation:** Call via Java FFM (JDK 22+) or a small JNI helper. The
`WIN32_MEMORY_RANGE_ENTRY` struct is `{VirtualAddress, NumberOfBytes}` — straightforward
FFM signature. Must mmap the model file first, then call prefetch before ORT
`createSession()`.

**ORT direct-bytes support (confirmed in Java API):** ORT v1.20.0 config keys
`session.use_ort_model_bytes_directly=1` and
`session.use_ort_model_bytes_for_initializers=1` let ORT parse directly from caller's
buffer — no copy. Combined with mmap + prefetch, the model stays in page cache and
ORT uses it in-place.

**Tradeoffs:**
- Platform-specific (Windows API). FFM bridge adds ~50 lines of code. FFM is stable
  on JDK 25 (finalized JDK 22, JEP 454).
- No-op when pages already warm (common on repeated restarts). Only helps cold start.
- `PrefetchVirtualMemory` is a hint — OS can silently ignore under memory pressure.
  On 8GB machines, prefetching 500+ MB can evict useful pages from other processes.
- **Defender interaction:** Without exclusions, prefetch triggers synchronous scanning
  of all prefetched pages — potentially slower than lazy page faults. Must combine
  with Strategy G for full benefit.
- `use_ort_model_bytes_for_initializers`: if any initializer gets prepacked, ORT
  allocates a new buffer anyway, partially negating the zero-copy benefit.

### E: FP16/INT8 quantization for CPU models

SPLADE model is 507 MB (FP32). FP16 halves to ~253 MB (170ms read). INT8 quarters
to ~127 MB (85ms read). GPU already uses FP16 opportunistically.

ORT's CPU EP handles FP16 via internal upcasting — ~5-10% throughput penalty, no
quality loss. INT8 dynamic quantization has minimal quality loss for BERT-family
models but SPLADE sparse encoding is sensitive to activation magnitude — quality
evaluation required.

**Tradeoff:** FP16 is safe. INT8 needs quality measurement before adoption.

### F: ORT format (.ort flatbuffer) + saved prepacked weights

The warm-path 363ms includes protobuf deserialization and weight repacking (MLAS kernel
layout transformation) on every session creation — even when loading an already-optimized
model. Both costs are eliminable.

**ORT format:** Convert cached model from `.onnx.optimized` (protobuf) to `.ort`
(flatbuffer). Flatbuffer eliminates protobuf parsing and stores weights in ORT's internal
tensor format. Mobile targets report 2-4x faster load times. Available in Java API via
`addConfigEntry("session.save_model_format", "ORT")` + `.ort` extension on
`setOptimizedModelFilePath()`. Our `OnnxSessionCache` cold path already does graph
optimization and write-back — switching to ORT format costs zero additional work.

**Saved prepacked weights (ORT v1.20.0, Nov 2024):**
`session.save_external_prepacked_constant_initializers=1` writes already-prepacked
weight layouts to a sidecar file on first creation. Subsequent loads skip repacking
entirely. For 500 MB transformer models, prepacking costs ~50-200ms — this eliminates
it on warm loads while preserving full inference performance (unlike `disable_prepacking`
which trades inference speed).

**Combined effect:** ORT format (no protobuf parse) + saved prepacked weights (no
repacking) should reduce the warm model load to approximately: SSD read time + mmap
overhead. For 507 MB at 1.5 GB/s = ~340ms floor, but with reduced CPU work the
effective saving is the 23-50ms currently spent on protobuf + repacking.

**Tradeoffs:**
- **ORT format breaks on ORT version upgrades.** Format version history shows multiple
  breaking changes (v1→v3→v4→v5). `.ort` files must be regenerated when ORT is
  upgraded — cannot be skipped. ORT v1.14+ added a compatibility check API.
- Cache invalidation now manages two sidecar files per model (`.ort` + prepacked
  weights). Architecture-specific (AVX2 vs AVX512 packing differs).
- `use_ort_model_bytes_directly` requires keeping the byte array alive for the
  session's entire lifetime (ORT holds a reference, not a copy). If GC'd → crash.
- "Fixed" optimization style in `.ort` bakes platform-specific kernel choices — a
  file built on one CPU microarchitecture may be suboptimal on another (relevant if
  build machine differs from user machines).

### G: Windows Defender mitigation (exclusions + Dev Drive)

Every JAR open, class load, and model page fault triggers synchronous Defender
real-time scanning on NTFS. This is a structural bottleneck on Windows that affects
all phases of startup.

**Process exclusion** (`Add-MpPreference -ExclusionProcess "java.exe"`): Excludes all
files opened by `java.exe` from real-time scans. IntelliJ and Eclipse teams document
IDEs going from 20+ second startup to seconds with proper exclusions.

**Folder exclusion** (`Add-MpPreference -ExclusionPath "D:\path\to\app"`): Excludes
app directories from all scans. Target: JDK directory, app lib/ directory, model
directory, and `%TEMP%\hsperfdata_*`.

**Dev Drive (Windows 11 22H2+):** ReFS-formatted volume with automatic async Defender
scanning (performance mode). File opens return immediately; scanning happens after the
fact. Microsoft measured 14-28% faster Java builds (Spring Framework benchmark).
Relevant to model loading: the 507 MB model read would not block on per-page scan
callbacks.

**Tradeoffs:**
- Process exclusions require admin elevation (installer or first-run setup).
- Disables Network Protection and ASR rules for the excluded process. **For a
  loopback-only app (127.0.0.1 binding, no outbound connections) with a bundled JRE,
  this is acceptable:** Network Protection monitors outbound traffic to malicious
  domains (irrelevant for local-only process), and relevant ASR rules (Office child
  processes, LSASS access) don't apply to a search/indexing JVM.
- Must use full-path exclusion to bundled JRE, not image-name `java.exe` (which would
  exclude all Java processes system-wide).
- Dev Drive: requires admin + 50 GB minimum + Windows 11 22H2+. Impractical for
  automatic app deployment — document as recommendation for power users/developers.
- Defender exclusions persist across reboots but enterprise MDM (Intune/GPO) can
  override local exclusions via `DisableLocalAdminMerge`.

### Agent confidence assessment for strategies

**Strategy A — Implementation confidence: MEDIUM-HIGH → HIGH (2026-03-14 reassessment).**
Highest-impact strategy. Code review (see Strategy A addendum) confirms all callers are
already null-safe: `SearchOrchestrator` degrades to BM25, `IndexingLoop` null-gates every
model use, `GrpcIngestService` has zero model references in its critical path. No
restructuring of `SpladeEncoder` needed — deferred init operates at the encoder-reference
level (volatile setter), not the session level. No gRPC UNAVAILABLE handling needed — the
code already degrades transparently. All model fields are `volatile`, all setters exist.
**Revised scope: ~2-3 files, ~70 lines** (down from 3-5 files, ~200 lines). Primary change
is reordering `KnowledgeServer.start()` phases and wrapping model init in a
`CompletableFuture`. **Risk (revised):** not race conditions (code is null-safe) but
user-experience — first few seconds show text-only search until models load.

**Strategy B — Implementation confidence: LOW.**
Requires changes to `WindowsJobObject` (JNI/native), heartbeat protocol, MMF signal
bus, and Head reconnection logic. Cross-cutting across 4+ modules. Agent has not read
any of the Job Object or heartbeat code. The reconnection protocol (Head discovers
existing Worker, validates it's healthy, binds to it) is new protocol design work that
needs human architectural input. Dev-only flag adds conditional paths that diverge
Head's startup flow. **Risk:** orphaned Workers holding Lucene write locks, stale
Worker with incompatible schema after code change.

**Strategy C — Implementation confidence: LOW-MEDIUM.**
Requires two-phase Worker startup protocol: spawn early with minimal args, Worker
begins class loading, then reads full config from signal bus extension once Head
writes it. Agent has not read the signal bus implementation or Worker's config
consumption path. The "config must be available before model init" constraint limits
the overlap to JVM class loading only (~600ms), but the protocol complexity is real.
Timing-dependent bugs (Worker reads config before Head writes it) are hard to test.

**Strategy D — Implementation confidence: LOW.**
Requires Java FFM (Foreign Function & Memory API) to call Windows `PrefetchVirtualMemory`.
Agent has no experience with FFM API, `MemoryLayout`, `Linker`, or `DowncallHandle`.
The `WIN32_MEMORY_RANGE_ENTRY` struct is simple but getting the FFM boilerplate right
(arena lifecycle, memory segment pinning, error handling) requires expertise the agent
lacks. Additionally depends on Strategy G (Defender exclusions) for full benefit —
without it, prefetch can be counterproductive.

**Strategy E — Implementation confidence: MEDIUM.**
FP16 conversion is a Python/ORT tooling task (offline model conversion), not a Java
code change. Agent could write the conversion script and modify `OnnxSessionCache` to
prefer `.fp16.onnx` variants. But quality evaluation (SPLADE sparse encoding accuracy
under FP16) requires running the eval pipeline and comparing retrieval metrics — scope
beyond a startup-performance tempdoc.

**Strategy F — Implementation confidence: MEDIUM.**
The ORT session config keys are well-documented and the change touches `OnnxSessionCache`
(a single file the agent has read). Setting `save_model_format=ORT` and
`save_external_prepacked_constant_initializers=1` are config-line additions. But cache
invalidation logic must be updated to manage `.ort` + prepacked sidecar files alongside
existing `.onnx.optimized`, and the ORT version compatibility concern (format breaks
across versions) needs testing. Expected saving is only 23-50ms — modest for the
invalidation complexity added.

**Strategy G — Implementation confidence: LOW.**
Not a code change — requires PowerShell with admin elevation to set Defender exclusions.
Agent cannot run elevated commands. Implementation is a documentation/installer task:
write a setup script or first-run wizard that requests elevation. The Defender exclusion
API (`Add-MpPreference`) is simple but enterprise MDM can silently override local
exclusions, making behavior unpredictable. Dev Drive is admin-only, 50GB minimum — pure
documentation recommendation.

### Strategy comparison

| Strategy | Startup saving | Dev iteration | Production | Complexity | Agent confidence |
|----------|---------------|---------------|------------|------------|-----------------|
| A: Deferred model load | Decouples models from gRPC readiness | Best | Good (first-request risk if IDF mode off) | Low (~70 lines) | **High** |
| B: Worker persistence | Eliminates Worker startup | Best for Head changes | N/A (dev only) | Medium | Low |
| C: Pre-warming | ~600ms overlap | Good | Good | Medium-High | Low-Medium |
| D: Page cache (PrefetchVirtualMemory) | 50-200ms per model (cold) | Moderate | Moderate | Medium | Low |
| E: FP16 CPU | ~170ms per model | Moderate | Good | Low | Medium |
| F: ORT format + prepacked weights | 23-50ms per model | Moderate | Good | Low | Medium |
| G: Defender exclusions + Dev Drive | Variable (significant on NTFS) | Good | Good | Low-Medium | Low |

A remains the highest-impact strategy — decouples model count from gRPC readiness.
F is additive to A (reduces the background model load time). G is a platform-level
fix that benefits all phases. B is highest impact for dev iteration specifically.

### Recommendations

**Closed with Items 2, 6, 7, 8 implemented** (-76ms median, -3.7%; within variance but
zero-risk flags worth keeping). Items 1, 5, 9 descoped or dropped. Items 3, Strategy A,
and Head redundancy elimination are recommended for a follow-up tempdoc.

- **Item 1** (parallel init): Descoped. Re-evaluation warranted now that embedding is
  present (~720ms potential). Subsumed by Strategy A — if models load in background,
  parallelizing them is a secondary optimization within that background task.
- **Item 5** (Jetty threads): Dropped. Item 4 proved counterproductive.
- **Item 9** (Lucene preload): Descoped. API confirmed available (Lucene 10.4.0),
  single construction site identified (`ComponentsFactory.java:104`). Conditional on
  Defender exclusion benefit.
- **Strategies B-G**: Separate work streams with distinct prerequisites.

### Recommended follow-up: Startup Performance Phase 2

A new tempdoc should implement the three highest-impact optimizations identified by
this tempdoc's investigation. All three are independent, fully researched with
code-level evidence, and can be parallelized across agents.

**Work stream 1: Strategy A — Deferred model loading (~600-800ms saving)**
- Confidence: HIGH. All callers null-safe, volatile fields, setter wiring.
- Scope: reorder `KnowledgeServer.start()` — move `createGrpcServer()` + `start()` +
  `writePort()` before model init, wrap model init in `CompletableFuture`.
- Files: `KnowledgeServer.java` (~50 lines), ready-state logging (~20 lines).
- No changes to `GrpcSearchService`, `GrpcIngestService`, `IndexingLoop`,
  `SearchOrchestrator` — all already null-safe.
- Risk: UX — first few seconds show text-only search until models load.
- See: Strategy A addendum for full code review.

**Work stream 2: Head redundancy elimination (~100-170ms saving)**
- Confidence: HIGH. Mechanical fixes — deduplicate calls, defer I/O.
- Scope:
  - Cache `RuntimeConfig.load()` result — eliminates 2 of 3 parses (Head + Worker).
  - Pass `EnterprisePolicyService` snapshot from HeadlessApp to LocalApiServer.
  - Deduplicate `InferenceConfig.fromEnvironment()` in `AppFacadeBootstrap`.
  - Defer `RrdMetricStore.initialize()` to background (first flush is 5s later).
  - Defer `maybeAutoSelectCuda12Variant()` to background if not GPU-critical.
- Files: `HeadlessApp.java`, `AppFacadeBootstrap.java`, `LocalApiServer.java`,
  `LocalTelemetry.java` or `RrdMetricStore.java`.
- Risk: minimal — removing duplicated calls, not adding new behavior.
- See: Head startup addendum for full phase breakdown.

**Work stream 3: AOT cache improvement (~108ms measured → 400-600ms with trace training)**
- Confidence: HIGH (works) / MEDIUM (unlocking full potential).
- **Measured (2026-03-14):** Curated 88-class training gives -108ms (-12.4%) with
  dramatically tighter variance. Cache works, zero risk. Already generated.
- Scope for Phase 2:
  - Switch from `Class.forName(false)` training to production-trace training: run
    actual Worker with `-XX:AOTMode=record`, let it reach gRPC readiness, shut down.
    This captures ALL startup classes, not just 88 curated ones.
  - Fix dev-runner `TieredStopAtLevel=1` conflict for Head AOT cache.
  - Generate Head AOT cache (`generateDevHeadAotCache`) and measure.
- Risk: production-trace training requires a running environment (data dir, models).
  Gradle task needs modification to run real `IndexerWorker` instead of `AotTraining`.
- See: Item 3 addendum + measurement addendum.

**Combined potential:** ~1.0-1.5s total saving. Current warm baseline with both models
is ~2066ms Worker + 627ms Head pre-Worker. Strategy A removes ~600-800ms from the
Worker critical path, Head fixes save ~100-170ms, and improved AOT cache training
could save ~400-600ms additional (extrapolated from 12% on 88 classes → 40-60% on
all classes, consistent with Spring PetClinic benchmarks).

**Execution order:**
1. AOT cache attempt first (30 min time-box) — outcome informs whether curated class
   list needs improvement, but doesn't block the other two.
2. Strategy A and Head redundancy can proceed in parallel — they touch different
   processes (Worker vs Head) with no overlapping files.

## Not Viable

| Item | Why |
|------|-----|
| CRaC (Checkpoint/Restore) | Linux-only (CRIU). Windows is primary platform. |
| GraalVM native-image | ORT JNI, Lucene Unsafe, SQLite-JDBC native extraction, Tika ServiceLoader, Netty reflection |
| Single-process architecture | Windows file locking (`MMapDirectory`) is a hard constraint (ADR-0001) |
| gRPC in-process transport | Requires same JVM — defeats process isolation |
| gRPC Unix domain sockets | Netty 4.1 doesn't support JDK 16 UDS; deferred to Netty 5 (still alpha). No Windows impl in grpc-java. |
| Hot-reload / DCEVM | Not present; impractical for JNI-heavy code (ORT sessions, Lucene handles) |
| `-XX:TieredStopAtLevel=1` | Conflicts with AOT cache on JDK 25. AOT saves 3-5x more. |
| `-XX:+UseSerialGC` for Worker | Worker is long-lived with sustained indexing. G1GC is correct. |
| `-Xverify:none` / `-noverify` | Deprecated JDK 13, will be removed. AOT cache already skips re-verification for cached classes. |
| `-XX:+AlwaysPreTouch` | Worker heap is 512MB; pre-touch costs ~25ms. Minimal startup benefit and commits full heap as physical memory — wasteful on 8GB machines. |
| `-XX:+UseLargePages` | No startup benefit. Throughput-only; requires admin GPO on Windows. |
| `NIOFSDirectory` over `MMapDirectory` | Worse on Windows (JVM bug #6265734 — poor multi-threaded perf). |
| Virtual threads | No startup impact (Netflix 2024 case study). Helps steady-state throughput only. |
| jlink custom runtime | No measured startup benefit alone. 60% size reduction but AOT cache provides the actual speedup. |
| JPMS module-path migration | Possibly negative — user-defined modules are NOT cached by AOT cache. Hurts Technique 2. |
| Pre-generate optimized ONNX | Machine-specific, ORT-version-specific, 500+ MB each |
| Signal bus poll optimization | 100ms poll adds ≤100ms. Worker init (1-2.7s) dominates. |
| Tokenizer sharing | Different models, different tokenizer files |
| Classpath reduction | 167 JARs all actively used. Tika defers via ServiceLoader. |

## Research Sources (2023-2026)

| Topic | Key sources |
|-------|------------|
| JDK AOT | [JEP 483](https://openjdk.org/jeps/483), [JEP 514](https://openjdk.org/jeps/514), [JEP 515](https://openjdk.org/jeps/515), [inside.java AOT cache (Jan 2026)](https://inside.java/2026/01/09/run-aot-cache/), [Morling JEP 483 benchmark](https://www.morling.dev/blog/jep-483-aot-class-loading-linking/), [JDK 25 perf improvements (Oct 2025)](https://inside.java/2025/10/20/jdk-25-performance-improvements/), [Leyden AOT Code Cache (Mar 2026)](https://www.javacodegeeks.com/2026/03/project-leydens-aot-code-cache-how-java-is-solving-its-cold-start-problem-without-graalvm.html), [JRuby JDK 25 AOT (Sep 2025)](https://blog.headius.com/2025/09/jruby-jdk25-startup-time-with-aotcache.html), [Spring Boot AOT Cache](https://medium.com/@anoopjohn02/supercharge-spring-boot-startup-performance-with-aot-cache-914b12c179f7) |
| Compact headers | [JEP 519](https://openjdk.org/jeps/519), [InfoQ JEP 519 (Jun 2025)](https://www.infoq.com/news/2025/06/java-25-compact-object-headers/) |
| ORT loading | [ORT format docs](https://onnxruntime.ai/docs/performance/model-optimizations/ort-format-models.html), [ORT v1.20.0 notes](https://github.com/microsoft/onnxruntime/releases/tag/v1.20.0), [Session config keys](https://github.com/microsoft/onnxruntime/blob/main/include/onnxruntime/core/session/onnxruntime_session_options_config_keys.h) |
| Netty startup | [Netty #2331](https://github.com/netty/netty/issues/2331), [Quarkus PR #9246](https://github.com/quarkusio/quarkus/pull/9246) |
| Windows perf | [PrefetchVirtualMemory](https://learn.microsoft.com/en-us/windows/win32/api/memoryapi/nf-memoryapi-prefetchvirtualmemory), [Dev Drive announcement](https://blogs.windows.com/windowsdeveloper/2023/06/01/dev-drive-performance-security-and-control-for-developers/), [MS Java Dev Drive blog](https://devblogs.microsoft.com/java/speed-up-your-java-development-on-windows-with-microsoft-dev-drive/) |
| Lucene | [MMapDirectory preload PR #11929](https://github.com/apache/lucene/pull/11929), [Lucene 10 release highlights](https://www.elastic.co/search-labs/blog/apache-lucene-10-release-highlights) |
| JVM tuning | [JDK-8246020 (-UsePerfData)](https://bugs.java.com/bugdatabase/view_bug?bug_id=8246020), [JetBrains JITWatch4i (Jan 2025)](https://blog.jetbrains.com/platform/2025/01/jitwatch4i-analyzing-intellij-idea-s-startup/), [GCeasy -Xms=-Xmx](https://blog.gceasy.io/benefits-of-setting-initial-and-maximum-memory-size-to-the-same-value/) |

## Addenda (2026-03-14)

### Pre-start() overhead

`IndexerWorker.main()` performs non-trivial work before `KnowledgeServer.start()`:

1. `ResolvedConfigBuilder.loadWorkerSnapshotFromSysprop()` — reads worker config snapshot
   from a temp file written by Head, deserializes JSON, populates `ConfigStore`
2. `WorkerConfig.load()`:
   - `RuntimeConfig.load()` — reads and merges runtime configuration
   - `SsotCommitMetadataSource.build()` — reads SSOT metadata
   - `IndexingPipelineLoader.loadResolved()` — reads and parses
     `SSOT/artifacts/pipelines/indexing.v1.resolved.json`
   - `sha256()` of `SSOT/manifests/repro/repro.v1.json` — reads and hashes file
   - `BudgetProfiles.load()` — reads budget profile configuration

This I/O falls inside the "class loading" gap (the ~1700ms between Worker JVM spawn and
`KnowledgeServer.start()` beginning) and is not instrumented in the startup phase log.
Likely <100ms total but contributes to the uninstrumented portion.

### Baseline reconciliation

Two baselines appear in this tempdoc measured under different conditions:

- **Original baseline** (no embedding model): Worker 1157ms warm total
- **Items 2,6,7,8 baseline** (with embedding model): Worker 2066ms warm median

The embedding model adds ~463ms (embedding phase) plus ~400ms additional aiModels time
(sequential ORT session creation). The current "real" baseline with both SPLADE and
embedding models installed is ~2066ms Worker warm, not 1157ms. The original 1157ms
baseline remains valid for its measurement conditions (SPLADE only, no embedding).

### External landscape check (2026-03-14)

All project dependencies are at latest versions. Only one significant development:

- **JDK 25 AOT cache (JEPs 483/514/515) — now proven and production-ready.** JDK 25
  shipped September 2025 (LTS). Project is already on JDK 25. JEP 514 simplifies the
  two-step train+assemble to a single `-XX:AOTCacheOutput=app.aot` flag. JEP 515 adds
  method profiling — JIT sees pre-warmed profiles at boot. Real-world: Spring PetClinic
  4.49s → 2.60s (42%), plus 15-19% additional from JEP 515. This upgrades Item 3's
  confidence from LOW to MEDIUM. The "680-1020ms" estimate may be conservative.
- **ORT 1.24.3** — already current. No new session loading improvements since 1.20.
- **Lucene 10.4.0** — already current. 10.1-10.4 brought query-time speedups, not
  startup. 10.3 changed default `ReadAdvice` from RANDOM to NORMAL (neutral for startup).
- **gRPC 1.79.0, Netty 4.1.131** — already current. No startup-relevant changes.

### Uninvestigated areas (2026-03-14)

The following areas were identified during review but not yet investigated. Numbered for
reference; no priority ordering implied.

1. **AOT cache Gradle task and `AotTraining.java` — investigated 2026-03-14.** See
   Item 3 addendum below for full findings. Summary: training classes use
   `Class.forName(name, false, loader)` (no static init, no JNI). Curated class lists
   (76 Head, 88 Worker) — potentially suboptimal vs running actual startup. Classpath
   ordering mismatch risk between training (sorted explicit JARs) and production
   (wildcard `-cp lib/*`). Dev-runner `TieredStopAtLevel=1` conflict confirmed.

2. **Head startup — investigated 2026-03-14.** See Head startup addendum below for
   full findings. Summary: 627ms decomposes into class loading (Jetty, OTel, Jackson,
   Netty), I/O (RRD4J, YAML, Windows dir listing), and OS thread creation (Jetty pool).
   Multiple redundant operations found: `RuntimeConfig.load()` called 3x,
   `EnterprisePolicyService.snapshot()` called 2x, `InferenceConfig.fromEnvironment()`
   called 2x. Deferrable: RRD4J init (first flush is 5s later), CUDA variant detection.

3. **`installDist` classpath composition.** [LOW priority] The tempdoc claims 167 JARs,
   all actively used. The actual JAR list has not been examined for heavy or slow-loading
   entries. Tika's `ServiceLoader` usage may contribute disproportionately to class
   loading time. Informs AOT cache effectiveness but not blocking.

4. **`WorkerConfig.load()` timing — investigated 2026-03-14.** See WorkerConfig addendum
   below. Summary: 17+ file reads totalling ~350 KB, dominated by 102 KB JSON Schema
   (`app-config.schema.json`) compiled 3x by networknt. `RuntimeConfig.load()` called 3x
   with no caching. `indexing.v1.resolved.json` read 3x. Estimated 20-60ms warm, up to
   100-200ms cold. Primary optimization target: cache `RuntimeConfig.load()` result.

5. **Strategy A implementation path — investigated 2026-03-14.** See Strategy A
   addendum below for full findings. Summary: ALL callers are already null-safe.
   Search degrades to BM25 text-only; ingest queues jobs normally (embedding is
   async in IndexingLoop); IndexingLoop null-gates every model use. No crashes,
   no gRPC error statuses. Strategy A scope is smaller than estimated — primarily
   reordering `KnowledgeServer.start()` phases. Confidence upgraded to HIGH.

6. **Cold start optimization.** [MEDIUM priority] Cold path is 2753ms (vs 1157ms warm).
   Frequency of cold starts (ORT upgrades, model changes) unknown. Whether graph
   optimization could run in background while serving with non-optimized session not
   investigated. Variant of Strategy A applied at the `OnnxSessionCache` layer.

7. **Production vs dev startup gap.** [LOW priority — partially resolved] AOT caches ARE
   bundled in production (`bundleSidecarResources` copies `head.aot` + `worker.aot` to
   Tauri resources under `aot/`). `WorkerSpawner` resolves `<libDir>/../aot/worker.aot`.
   Remaining gap: production startup timing not profiled.

8. **Memory impact.** [LOW priority — premature] Strategy A (deferred loading) creates a
   window where background model loading overlaps with incoming requests. Peak memory
   during this window not profiled. Only relevant when Strategy A is implemented.

9. **Lucene index opening variance.** [LOW priority] Lucene phase varies 250-360ms.
   Drivers (segment count, file sizes, NTFS metadata cache) not investigated. Even
   eliminating all variance saves at most ~110ms. Whether `IndexWriter` open could be
   deferred past gRPC readiness (read-only first) not analyzed.

### Item 3 addendum: AOT cache code review (2026-03-14)

**Files read:**
- `modules/indexer-worker/.../AotTraining.java` (88 lines)
- `modules/ui/.../AotTraining.java` (76 lines)
- `modules/ui/build.gradle.kts` lines 1002-1208 (all four AOT tasks)
- `modules/app-services/.../WorkerSpawner.java` lines 297-317 (AOT cache consumption)
- `scripts/dev/dev-runner.cjs` lines 803-858 (Head AOT consumption)

**Training approach:** Both `AotTraining` classes use `Class.forName(className, false,
ClassLoader.getSystemClassLoader())`. The `false` parameter means `initialize=false` —
static initializers are NOT run. No JNI libraries are loaded (no `System.loadLibrary()`
from ORT, SQLite, or Lucene MMap). No I/O, no network, no blocking. The JVM records
only that classes were loaded and linked by the classloader, then `System.exit(0)`.

**Worker training touches (88 lines):** SLF4J + Logback (3), Lucene (8 classes:
IndexWriter, DirectoryReader, IndexSearcher, KnnFloatVectorQuery, MMapDirectory,
StandardAnalyzer, Lucene100Codec, HnswGraphSearcher), gRPC server (3), Protobuf (1),
Tika (3: Tika, AutoDetectParser, Metadata), SQLite (2: JDBC, SQLiteConnection),
ORT (2: OrtEnvironment, OrtSession), OTel (2), JustSearch own (4: IndexerWorker,
KnowledgeServer, ConfigStore, LocalTelemetry).

**Head training touches (76 lines):** SLF4J + Logback (3), Jackson (3), Javalin (2),
OTel (2), gRPC client (2), Protobuf (1), JustSearch own (7: HeadlessApp,
LocalApiServer, ConfigStore, WorkerSpawner, LocalTelemetry, UiSettings,
UiSettingsStore).

**Gradle task workflow:** All four tasks use the manual two-step (record + create),
not JEP 514's single-flag `-XX:AOTCacheOutput=`. Comment at line 1002 explains this
is intentional to avoid 2x memory cost of the combined flag. Dev tasks have proper
`inputs.dir` / `outputs.dir` wiring for Gradle up-to-date checking. Production tasks
do NOT have `inputs.dir` (always re-run).

**Production bundling:** `bundleSidecarResources` copies `head.aot` and `worker.aot`
into Tauri's resource dir under `aot/`. Production `WorkerSpawner` resolves
`<libDir>/../aot/worker.aot` and adds `-XX:AOTCache=<path>` if the file exists.
**AOT caches ARE wired into production builds** (resolves uninvestigated item 7
partially).

**Issues identified:**

1. **Classpath ordering mismatch risk.** Training tasks build an explicit sorted JAR
   list (`lib/*.jar` sorted). `WorkerSpawner.buildCommand()` uses wildcard
   `-cp <workerLibDir>/*` (JVM expands). JDK AOT documentation requires: "the same
   JAR files must be present, in the same order." If JVM wildcard expansion order
   differs from alphabetical sort, the cache is silently invalidated. The production
   tasks use the bundled JLink runtime (same JVM) — order may match. Dev tasks use
   system JDK — expansion order is filesystem-dependent (NTFS returns alphabetical,
   which matches sorted order, but this is not guaranteed by spec).

2. **Curated class list is potentially suboptimal.** JDK AOT best practices say
   "structure the training run so that it loads the same classes that a production
   run loads when it starts." The `Class.forName(false)` approach only touches a
   curated subset (88 Worker, 76 Head). A real startup loads thousands of classes
   (167 JARs). Classes not in the training list fall back to on-demand loading at
   runtime — the AOT cache only helps for the listed classes. A more effective
   approach would be to run the actual application with `-XX:AOTMode=record`, let
   it reach gRPC readiness, then shut down. This would capture ALL classes loaded
   during startup, not just a hand-picked subset. Trade-off: requires a running
   environment (data dir, models, index) vs the current approach which needs nothing.

3. **dev-runner `TieredStopAtLevel=1` conflict.** `dev-runner.cjs` (lines 803-858)
   combines `-XX:TieredStopAtLevel=1` with `-XX:AOTCache=` in Head `JAVA_OPTS`.
   The tempdoc (lines 135-146) documents this as mutually exclusive —
   `TieredStopAtLevel=1` bypasses C2, wasting the AOT cache. This conflict is in
   the **Head** dev-runner only; `WorkerSpawner` does not set `TieredStopAtLevel=1`.
   Fix: dev-runner should drop `TieredStopAtLevel=1` when AOT cache is active.

4. **Training class list is manually maintained.** New dependencies or class loading
   patterns won't be reflected automatically. The lists were written when the tasks
   were created and may be stale (e.g., missing classes from recent module additions).
   A production-trace approach (issue 2 above) would eliminate this maintenance burden.

**Confidence reassessment:** The infrastructure is well-built (Gradle wiring, production
bundling, consumer detection all in place). The main risk is not "will it work" but
"will it help enough" — the curated class list may capture only 10-20% of classes
loaded during real startup, limiting the cache's effectiveness. Running the task and
measuring is the fastest way to resolve this. Confidence: **MEDIUM** (infrastructure
solid, effectiveness uncertain).

### Item 3 measurement: AOT cache A/B test (2026-03-14)

**Setup:** JDK 25.0.1 (Temurin LTS). Worker `installDist` with 168 JARs. Classpath
specified as sorted explicit JAR list with `;` separator (matching training). No
embedding model. SPLADE model present. Minimal data dir (no `JUSTSEARCH_DATA_DIR`).
Flags: `-Xms512m -Xmx512m -XX:-UsePerfData`. AOT cache: 16 MB (`worker.aot`).

**Step 1: `./gradlew.bat generateDevWorkerAotCache` — SUCCESS.** Completed in 15s.
Two-step workflow (record → create). No errors. Output: `worker.aot` (16 MB) +
`worker.aotconf` (15 MB).

**Step 2: AotTraining micro-benchmark (88 curated classes only):**

| | No AOT | With AOT |
|---|--------|----------|
| Run 1 | 238ms | 156ms |
| Run 2 | 214ms | 161ms |
| Run 3 | 221ms | 144ms |
| **Median** | **221ms** | **156ms** |

AOT cache savings for curated class loading: **-65ms (-29%)**.

**Step 3: Full Worker startup A/B test (5 runs each, `IndexerWorker.main()`):**

**Baseline (no AOT, 5 runs):**

| Run | total | telemetry | jobQueue | lucene | aiModels | grpc |
|-----|-------|-----------|----------|--------|----------|------|
| 1 | 1136 | 79 | 208 | 181 | 391 | 263 |
| 2 | 787 | 59 | 110 | 128 | 286 | 192 |
| 3 | 1184 | 114 | 162 | 177 | 470 | 238 |
| 4 | 789 | 62 | 113 | 135 | 275 | 191 |
| 5 | 869 | 62 | 124 | 136 | 305 | 231 |
| **median** | **869** | **62** | **124** | **136** | **305** | **231** |

**With AOT cache (5 runs):**

| Run | total | telemetry | jobQueue | lucene | aiModels | grpc |
|-----|-------|-----------|----------|--------|----------|------|
| 1 | 922 | 79 | 122 | 122 | 350 | 236 |
| 2 | 910 | 69 | 122 | 128 | 362 | 216 |
| 3 | 761 | 65 | 107 | 109 | 284 | 184 |
| 4 | 755 | 63 | 106 | 113 | 276 | 186 |
| 5 | 757 | 63 | 108 | 112 | 278 | 183 |
| **median** | **761** | **65** | **108** | **113** | **284** | **186** |

**Result: -108ms median (-12.4%).** Variance tightened dramatically: baseline range
787-1184ms (397ms spread) vs AOT range 755-922ms (167ms spread).

**Per-phase impact:** The saving is distributed across all phases, not concentrated
in one. gRPC saw the largest median reduction (-45ms), suggesting Netty/gRPC class
loading benefits most from the cache. `aiModels` saved ~21ms, `lucene` ~23ms,
`jobQueue` ~16ms. The curated 88-class list provides modest but real benefit even
though it's a small subset of all loaded classes.

**Assessment:** The AOT cache works and provides measurable improvement, but the
**-108ms (12.4%) is well below the tempdoc's estimated 680-1020ms (40-60%)**. The
gap is explained by the curated class list — 88 classes out of thousands loaded during
real startup. The cache helps with the listed classes (29% improvement for those) but
the majority of class loading time is uncached. A production-trace approach (run actual
app with `-XX:AOTMode=record`) would capture all startup classes and likely yield a
much larger improvement. The variance reduction (from 397ms to 167ms spread) is
arguably as valuable as the median improvement — it makes startup time more predictable.

**Conclusion:** Keep the AOT cache (zero risk, measurable benefit). To unlock the full
AOT potential, switch from curated `Class.forName(false)` training to
production-trace training (`-XX:AOTMode=record` with actual Worker startup). This is
a follow-up item for the Phase 2 tempdoc.

### Strategy A addendum: implementation path code review (2026-03-14)

**Files read:**
- `modules/indexer-worker/.../services/GrpcSearchService.java`
- `modules/indexer-worker/.../services/GrpcIngestService.java`
- `modules/indexer-worker/.../loop/IndexingLoop.java`
- `modules/indexer-worker/.../server/KnowledgeServer.java` (createGrpcServer wiring)

**Key finding: all callers are already null-safe.** The codebase already handles
missing models gracefully at every level. No code changes needed to tolerate a
loading window — only reordering of `KnowledgeServer.start()` phases.

**GrpcSearchService → SearchOrchestrator:**
- `spladeEncoder` and `spladeIdfQueryEncoder` are `volatile` fields in
  `SearchOrchestrator`, set via setters post-construction.
- `prepareSpladeWeights()`: if both encoders are null, returns
  `SpladePrepResult.failed("ENCODER_NOT_AVAILABLE")`. Search degrades to BM25
  text-only; `spladeSkipReason` is set in the response.
- `prepareVectorWeights()`: `embeddingService` is null-checked →
  `VectorPrepResult.failed(...)`. Dense leg skipped.
- `embeddingCompat()`: `controller == null || controller.allowQueryEmbeddings()`
  → returns true when controller is null (allows queries).
- **Result: search degrades transparently to text-only. No NPE, no gRPC error.**

**GrpcIngestService:**
- Has **zero** embedding/SPLADE references in its critical path.
- `ingest()`/`batch()` enqueue paths into `jobQueue`. No embedding in the gRPC
  handler. SPLADE/embedding-related setters are for status/observability only.
- **Result: ingest requests accepted and queued normally during loading window.**

**IndexingLoop:**
- `embeddingService` and `spladeEncoder` are `volatile` fields, set via constructor
  injection and also via setters.
- Every use is null-gated:
  - `embeddingService != null && embeddingService.isAvailable()` (lines 413, 628)
  - `spladeEncoder != null` (lines 445, 517, 757)
  - Documents without embeddings get `EMBEDDING_STATUS_PENDING` for later backfill.
- **Result: loop runs text-only during loading window. Backfill picks up embeddings
  when model becomes available. No crash.**

**KnowledgeServer.start() current wiring order:**
```
embedding init (blocking) → SPLADE init (blocking) → IDF encoder →
createGrpcServer (wires all via setters) → grpcServer.start() →
signalBus.writePort() → indexingLoop.start()
```

**Strategy A reordering would be:**
```
Phase 3:   Lucene open (360ms)
Phase 4:   createGrpcServer (no models wired yet)
Phase 4b:  grpcServer.start()
Phase 5:   signalBus.writePort() ← Head unblocked HERE (~560ms into start())
Phase 6:   indexingLoop.start() (loop runs, skips models it doesn't have)
Background: CompletableFuture.supplyAsync(() -> {
              embedding = initEmbedding();   // ~460ms
              splade = initSplade();         // ~360ms
              disambig = initDisambig();     // ~20ms
              // wire into services via existing volatile setters:
              searchOrchestrator.setSpladeEncoder(splade);
              indexingLoop.setSpladeEncoder(splade);
              indexingLoop.setEmbeddingService(embedding);
              // etc.
            })
```

**Thread safety analysis:**
- All model fields in `SearchOrchestrator`, `IndexingLoop`, and `GrpcIngestService`
  are `volatile` — writes from the background thread are immediately visible to
  service threads.
- `SpladeEncoder` and `OnnxEmbeddingEncoder` are immutable after construction
  (all `final` fields). Safe to publish via volatile reference.
- `OrtEnvironment.getEnvironment()` is a singleton — must be called at least once
  on any thread before `createSession()`. Already called in each encoder's
  constructor. Safe for background thread.
- `OnnxSessionCache.createCachedSession()` has no internal synchronization but
  operates on separate model files per encoder. No shared mutable state between
  concurrent calls.

**Remaining implementation concerns:**
1. `EmbeddingCompatibilityController.refresh()` (line 375) reads Lucene index
   metadata. This depends on Lucene being open (phase 3). In the deferred model,
   Lucene opens before gRPC, so this dependency is satisfied. But
   `EmbeddingCompatController` is set on both `IndexingLoop` and the search
   service — wiring it after background embedding init requires care.
2. `SpladeIdfQueryEncoder` depends on `SpladeEncoder` (shares tokenizer/vocab).
   Must be constructed after SPLADE init completes, in the same background task.
3. Ready-state logging: without it, "model loading" looks like a crash from
   monitoring. Add a log line when background init completes.
4. Whether to return gRPC `UNAVAILABLE` (status 14) during loading or silently
   degrade. Current code silently degrades — this is arguably better UX than
   failing requests that would succeed moments later.

**Scope estimate (revised down):**
- `KnowledgeServer.start()` — reorder phases, wrap model init in
  `CompletableFuture` (~50 lines changed)
- No changes to `GrpcSearchService`, `GrpcIngestService`, `IndexingLoop`,
  `SearchOrchestrator` — all already null-safe
- Add background-complete logging and optional `/api/status` readiness flag
  (~20 lines)
- Total: ~2-3 files, ~70 lines. Not the "3-5 files, ~200 lines" estimated
  in the original tempdoc.

**Confidence: HIGH.** All callers handle null models. All model fields are volatile.
All setter-based wiring supports post-construction updates. The main risk is not
correctness but user-experience: the first few seconds after launch may show
degraded search results (text-only) until models finish loading in background.

### WorkerConfig.load() addendum: I/O inventory (2026-03-14)

**Files read:**
- `modules/indexer-worker/.../WorkerConfig.java`
- `modules/indexer-worker/.../IndexerWorker.java`
- `modules/configuration/.../RuntimeConfig.java`
- `modules/ssot/.../SsotCommitMetadataSource.java`
- `modules/ssot/.../BudgetProfiles.java`

**Total I/O:** 17+ file reads, ~350 KB total, dominated by one 102 KB file read 3x.

**Key redundancies found:**

| Operation | Times called | Why |
|-----------|-------------|-----|
| `RuntimeConfig.load()` | **3x** | Once in `WorkerConfig.load()`, twice inside `SsotCommitMetadataSource.build()` via `resolvedConfigOrFallback()` and `boostsCanonicalJson()`. Each call reparses `application.yaml` (3 KB) and recompiles `app-config.schema.json` (102 KB) via networknt JSON Schema. No caching. |
| `indexing.v1.resolved.json` read | **3x** | Once for `dag_hash` in SSOT metadata, once in `IndexingPipelineLoader.loadResolved()`, once more internally in `PipelineLoader.load()` via `dagHashingService.dagHash()`. |
| `versions/catalog.json` read | **2x** | Once in `SsotCommitMetadataSource`, once in `BudgetProfiles.load()`. |

**Dominant cost:** The 102 KB JSON Schema (`SSOT/schemas/config/app-config.schema.json`)
is compiled 3x by networknt (`JsonSchemaFactory.getInstance().getSchema()`). Schema
compilation involves recursive resolution and indexing — significantly more expensive
than a flat JSON parse. All other files are under 10 KB.

**Timing estimate:** 20-60ms warm (OS page cache hot), 100-200ms cold (first boot,
NTFS metadata cold, Defender scanning). The original "<100ms" estimate is plausible
on warm cache but not guaranteed cold.

**Optimization target:** Cache `RuntimeConfig.load()` result (static field or pass-through).
Eliminates 2 of 3 schema compilations and 2 of 3 YAML parses. Expected saving: 10-40ms.

### Head startup addendum: phase breakdown (2026-03-14)

**Files read:**
- `modules/ui/.../HeadlessApp.java` (full startup sequence)
- `modules/ui/.../settings/UiSettingsStore.java`
- `modules/telemetry/.../LocalTelemetry.java` + `RrdMetricStore.java`
- `modules/ui/.../policy/EnterprisePolicyService.java`
- `modules/app-services/.../AppFacadeBootstrap.java`
- `modules/ui/.../api/LocalApiServer.java`
- `modules/app-services/.../bootstrap/BootstrapInferenceFactory.java`

**Phase-level decomposition:**

**settings (110ms):**
- `UiSettingsStore.load()`: file probe + JSON read of `settings.json` (~1 KB). Fast.
- `maybeAutoSelectCuda12Variant()`: when GPU enabled, does `Files.list(System32)` to
  find `cudart64_*.dll`. **Windows `System32` directory listing is expensive** (~20-40ms).
  Only runs when `gpuLayers > 0`.
- `RuntimeConfig.load()`: first call — YAML parse + 102 KB schema compilation.
- Config snapshot write to `<dataDir>/runtime/worker-config-snapshot.json`.

**telemetry (77ms):**
- `RrdMetricStore.initialize()`: opens or creates binary `.rrd` file (RRD4J library).
  **Deferrable** — first metric flush is 5s later.
- OTel `SdkMeterProvider` construction: 6 instrument selectors, metric reader. Class
  loading bound on first startup.

**policy (18ms):**
- `EnterprisePolicyService.snapshot()`: filesystem probes for machine/user policy files
  (typically absent on dev machines = fast). Jackson class loading on first call.
- **This result is discarded** — computed again in the `api` phase. Wasted work.

**facade (164ms):**
- `ConfigManagerBootstrap()` → another `RuntimeConfig.load()` (second parse).
- `InferenceConfig.fromEnvironment()` called **twice** (once in `createTranslator()`,
  once in `createInferenceManager()`). Each probes filesystem for llama-server + model.
  Duplication — one call suffices.
- Netty/gRPC class loading (from static imports in `AppFacadeBootstrap` even though
  the infra health gRPC server is disabled in headless mode).

**api (247ms):**
- Jetty class loading (~100ms): entire `org.eclipse.jetty` hierarchy loaded on first
  `Javalin.create()` call.
- `QueuedThreadPool` starts 8 min threads: OS thread creation.
- TCP loopback `bind()`.
- `EnterprisePolicyService.snapshot()` called **again** (second time).
- 80 routes registered (fast — data structure operations).
- `SlowRequestDumper.pruneOldDumps(30)`: directory listing + file deletion in constructor.

**Redundant operations across all phases:**

| Operation | Calls | Fix |
|-----------|-------|-----|
| `RuntimeConfig.load()` | 2-3x in Head | Pass `ResolvedConfig` from settings phase into facade |
| `EnterprisePolicyService.snapshot()` | 2x | Compute once in HeadlessApp, pass result to LocalApiServer |
| `InferenceConfig.fromEnvironment()` | 2x in facade | Call once, pass to both `createTranslator` and `createInferenceManager` |

**Deferrable operations:**

| Operation | Phase | Est. saving | Why deferrable |
|-----------|-------|-------------|----------------|
| `RrdMetricStore.initialize()` | telemetry | 30-40ms | First flush is 5s later |
| `maybeAutoSelectCuda12Variant()` | settings | 20-40ms | Only needed before llama-server start |
| `SlowRequestDumper.pruneOldDumps()` | api | 5-10ms | Cleanup can happen on background thread |

**Combined estimated Head savings from eliminating redundancy + deferral:**
- Redundancy elimination: ~50-80ms (2 extra `RuntimeConfig.load()` + 1 extra
  policy snapshot + 1 extra `InferenceConfig` probe)
- Deferral: ~55-90ms (RRD4J + CUDA variant detection + dump pruning)
- Total potential: ~100-170ms of 627ms (16-27% reduction)

**Note:** These are Head-side optimizations independent of Worker startup. They
would stack with Strategy A and AOT cache improvements.
