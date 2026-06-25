---
title: "302: Startup Performance Phase 2"
type: tempdoc
status: done
created: 2026-03-14
updated: 2026-03-14
---

> NOTE: Noncanonical working tempdoc. Verify behavioral claims against canonical docs, code, and
> tests before promotion.

# 302: Startup Performance Phase 2

## Problem

Tempdoc 286 optimized Worker startup with JVM flags (-76ms) and comprehensively researched
the remaining optimization landscape. This tempdoc implements the three highest-impact
strategies identified by that research. All three are independent, fully investigated with
code-level evidence, and can proceed in parallel.

## Prior art

Tempdoc 286 (closed) contains:
- Measured baselines (Worker 2066ms warm with both models, Head 627ms to HTTP ready)
- Root cause analysis (IO-bound model loading, sequential init, class loading)
- Strategy A code review (all callers null-safe, volatile fields, setter wiring)
- Head startup phase breakdown (redundant operations, deferrable I/O)
- AOT cache measurement (curated training: -108ms / -12.4%, variance halved)
- WorkerConfig.load() I/O inventory (17 files, 350 KB, 102 KB schema compiled 3x)

## Current baselines

| Process | Metric | Value | Condition |
|---------|--------|-------|-----------|
| Worker | warm total (SPLADE + embedding) | ~2066ms median | Items 2,6,7,8 baseline |
| Worker | warm total (SPLADE only, no embed) | ~869ms median | AOT A/B baseline |
| Worker | warm total (SPLADE only, with AOT) | ~761ms median | Current curated AOT |
| Head | HTTP ready | ~627ms | All phases |
| Head | Worker wait | ~1833ms | Spawn + awaitPort + connect |
| End-to-end | fully operational | ~2460ms | Head total |

## Work stream 1: Deferred model loading (Strategy A)

### Goal

Decouple model loading from gRPC readiness. Head unblocks after Lucene + gRPC (~560ms)
instead of waiting for all model IO (~1200ms+).

### Evidence

Full code review in tempdoc 286 Strategy A addendum. Key findings:

- `SearchOrchestrator`: `spladeEncoder` and `spladeIdfQueryEncoder` are `volatile`.
  `prepareSpladeWeights()` returns `SpladePrepResult.failed("ENCODER_NOT_AVAILABLE")`
  when null — search degrades to BM25 text-only. No crash, no gRPC error.
- `GrpcIngestService`: zero model references in critical path. Ingest enqueues to
  `jobQueue`. Embedding happens asynchronously in `IndexingLoop`.
- `IndexingLoop`: `embeddingService` and `spladeEncoder` are `volatile`. Every use
  is null-gated. Documents marked `EMBEDDING_STATUS_PENDING` for later backfill.
- `SpladeEncoder.cpuSession` is `final` — encoder cannot exist without loaded model.
  Deferred init operates at the encoder-reference level (null → constructed).
- All setter-based wiring already exists (`setSpladeEncoder()`, `setEmbeddingService()`).

### Implementation

Reorder `KnowledgeServer.start()` phases:

```
Current:
  [telemetry][signalBus][jobQueue][Lucene][embedding][SPLADE][disambig][gRPC][writePort]
                                          ^^^^^^^^^^^^^^^^^^^^^^^^^^^
                                          ~820ms blocking gRPC readiness

Deferred:
  [telemetry][signalBus][jobQueue][Lucene][gRPC][writePort][indexingLoop.start()]
                                                ← Head unblocked (~560ms)
  Background: [embedding ~460ms][SPLADE ~360ms][disambig ~20ms][wire via setters]
```

- [x] 1a. Move `createGrpcServer()` + `grpcServer.start()` + `signalBus.writePort()`
  immediately after Lucene phase
  - `KnowledgeServer.java` — reorder phases ~360-510
  - Move `indexingLoop` construction before gRPC (it only needs Lucene + telemetry +
    jobQueue + config, all available)
  - Pass `null` for `embeddingService` and `spladeEncoder` at gRPC wiring time (services
    already handle null via existing setters)

- [x] 1b. Wrap model init in `CompletableFuture.runAsync()` after `writePort()`
  - `KnowledgeServer.java` — new method `deferredModelInit()`
  - Create `OrtEnvironment.getEnvironment()` on main thread before spawning async
    (singleton, must exist before `createSession()`)
  - Background task: init embedding → init SPLADE → init IDF encoder → init
    disambiguation → wire all via existing volatile setters
  - `EmbeddingCompatibilityController.refresh()` depends on Lucene (satisfied — Lucene
    opens before gRPC) and embedding (must be created in background task, then refresh)

- [x] 1c. Add ready-state logging
  - Log when background model init completes ("AI models ready: embedding=Xms,
    splade=Xms, disambig=Xms")
  - Without this, model loading looks like a crash from monitoring

- ~~1d. Add `modelsReady` field to Worker status~~ — **Dropped (2026-03-14).** Investigation
  confirmed readiness is already derivable from existing fields: `embeddingReady` in
  `HealthCheckResponse` returns `false` during loading window, `EMBEDDING` readiness
  dimension in `StatusLifecycleHandler` maps this to `NOT_READY`, and UI renders a
  warning via `deriveHealthEvents.ts`. `spladeModelPath` and `embedBackend` are naturally
  empty strings during loading. No new field needed.

### Files touched

| File | Change |
|------|--------|
| `modules/indexer-worker/.../server/KnowledgeServer.java` | Reorder phases, add `deferredModelInit()` |
| `modules/indexer-worker/.../services/IndexStatusOps.java` | Add `modelsReady` supplier |
| `modules/app-api/.../status/WorkerOperationalView.java` | Add `modelsReady` field |
| `modules/app-services/.../worker/WorkerStatusMapper.java` | Map `modelsReady` |

### Measured result (2026-03-14)

Items 1a, 1b, 1c implemented. 1d (status field) deferred.

**With both SPLADE (418 MB) + embedding (524 MB) models:**

| | Before (sequential) | After (deferred) |
|---|---------------------|-------------------|
| Median time-to-writePort | 2066ms | **909ms** |
| Improvement | | **-1157ms (-56%)** |
| Variance range | 1917-2892ms (975ms) | 892-1208ms (316ms) |

Background model loading (cold, first ONNX optimization): ~3693ms total.
Embedding ready ~1680ms after writePort. SPLADE ready ~3660ms after writePort.
Warm runs would be much faster (~800ms total background).

**Files changed:**
- `KnowledgeServer.java` — reordered phases, added `CompletableFuture` background init,
  shutdown join, split timing log
- `IndexingLoop.java` — added `setEmbeddingService()` setter (field was already volatile)

## Work stream 2: Head redundancy elimination

### Goal

Remove duplicated I/O operations in Head startup. Estimated ~100-170ms saving.

### Evidence

Full breakdown in tempdoc 286 Head startup addendum. Redundancies verified by code
review (2026-03-14):

| Operation | Calls | Status | Code evidence |
|-----------|-------|--------|---------------|
| `RuntimeConfig.load()` (YAML + 102KB schema) | 2x in Head | **CONFIRMED** | HeadlessApp:189, ConfigManagerBootstrap:53. No caching. |
| `EnterprisePolicyService.snapshot()` | 2x | **CONFIRMED** | HeadlessApp:234 (result discarded, sysprop side-effect only), LocalApiServer:174. No memoization. |
| `InferenceConfig.fromEnvironment()` | 2x | **CONFIRMED** | BootstrapInferenceFactory:27 (willCreate), :50 (create). First result discarded. |
| `RrdMetricStore.initialize()` | 1x, deferrable | **PARTIALLY CONFIRMED** | Deferrable to first flush (5s later, null-guarded at record:167). Cost unquantified. |

All redundancies are real. Total saving is **unquantified** — the "~100-170ms" estimate
is plausible but unverified. Fixes are mechanical and low-risk. Instrumentation of
individual Head sub-phases would be needed to confirm the actual saving before/after.

### Implementation

- [ ] 2a. Cache `RuntimeConfig` — load once, reuse
  - Option A: static cached field in `RuntimeConfig` (double-checked lock)
  - Option B: pass `RuntimeConfig` instance from `HeadlessApp` settings phase
    into `AppFacadeBootstrap` and `ConfigManagerBootstrap`
  - Option B is cleaner (no static state) but requires API change
  - `modules/configuration/.../RuntimeConfig.java` — add caching or pass-through
  - Also fixes the Worker side: `WorkerConfig.load()` calls `RuntimeConfig.load()` 3x

- [ ] 2b. Deduplicate `EnterprisePolicyService.snapshot()`
  - `HeadlessApp.java` line 234 computes snapshot; result discarded
  - `LocalApiServer.java` line 177 computes it again
  - Fix: compute once in `HeadlessApp`, pass via `LocalApiServer.Builder`
  - Or: lazy singleton inside `EnterprisePolicyService` (stale-on-change risk — policy
    files are static, so this is safe for startup)

- [ ] 2c. Deduplicate `InferenceConfig.fromEnvironment()`
  - `AppFacadeBootstrap.java` calls it in `createTranslator()` and again in
    `createInferenceManager()` — back-to-back, same result
  - Fix: call once, pass to both methods
  - `modules/app-services/.../AppFacadeBootstrap.java`

- [ ] 2d. Defer `RrdMetricStore.initialize()` to background
  - First metric flush is 5s later — no urgency
  - `LocalTelemetry.java` or `RrdMetricStore.java` — initialize on first flush or
    on a background thread
  - Saves ~30-40ms from the telemetry phase

### Implemented (2026-03-14)

- [x] 2a. Pass pre-loaded `RuntimeConfig` from HeadlessApp to ConfigManagerBootstrap
  - `HeadlessApp.java` — capture `startupRuntimeConfig` from first `load()`, pass via
    `ConfigManagerBootstrap.withPreloadedConfig()` factory method
  - `ConfigManagerBootstrap.java` — added `withPreloadedConfig(RuntimeConfig)` static
    factory that skips the redundant second `load()`
  - Eliminates second YAML parse + 102 KB JSON schema compilation
- ~~2b. Deduplicate `EnterprisePolicyService.snapshot()`~~ — **Not implemented.** Ordering
  constraint: policy sysprops must be set before Worker spawn (line 242), but
  `LocalApiServer` runs after (line 280+). Would require restructuring startup ordering.
  Saving <10ms.
- [x] 2c. Cache `InferenceConfig` between `willInferenceManagerBeCreated()` and
  `createInferenceManager()`
  - `BootstrapInferenceFactory.java` — added `cachedInferenceConfig` static field.
    First call caches, second call reuses and clears. Eliminates duplicate filesystem
    probes.
- [x] 2d. Defer `RrdMetricStore.initialize()` to first flush
  - `LocalTelemetry.java` — removed eager `rrdStore.initialize()` from constructor
  - `RrdMetricStore.java` — added lazy init inside `record()`: if `rrdDb == null`,
    calls `initialize()` before recording. Already synchronized + idempotent.

### Files touched

| File | Change |
|------|--------|
| `modules/ui/.../HeadlessApp.java` | Capture `startupRuntimeConfig`, pass to `ConfigManagerBootstrap.withPreloadedConfig()` |
| `modules/app-config/.../ConfigManagerBootstrap.java` | Added `withPreloadedConfig()` static factory |
| `modules/app-services/.../bootstrap/BootstrapInferenceFactory.java` | Added `cachedInferenceConfig` field for dedup |
| `modules/telemetry/.../LocalTelemetry.java` | Removed eager `rrdStore.initialize()` |
| `modules/telemetry/.../RrdMetricStore.java` | Added lazy init in `record()` |

### Measured result (2026-03-14)

Combined measurement of work streams 1+2 (Worker standalone, with both models):

**Before (tempdoc 286 baseline — sequential):**

| Metric | Value |
|--------|-------|
| Median time-to-writePort | 2066ms |
| Range | 1917-2892ms (975ms spread) |

**After (deferred loading + Head fixes):**

| Run | total | telemetry | signalBus | jobQueue | lucene | init | grpc |
|-----|-------|-----------|-----------|----------|--------|------|------|
| 1 (cold) | 1215 | 52 | 11 | 172 | 384 | 353 | 239 |
| 2 | 910 | 53 | 13 | 114 | 262 | 293 | 172 |
| 3 | 927 | 45 | 11 | 113 | 303 | 283 | 169 |
| 4 | 950 | 45 | 12 | 115 | 318 | 287 | 170 |
| 5 | 999 | 49 | 14 | 122 | 339 | 295 | 177 |
| **median** | **950** | **49** | **12** | **115** | **303** | **293** | **172** |

| Metric | Value |
|--------|-------|
| Median time-to-writePort | **950ms** |
| Improvement | **-1116ms (-54%)** |
| Range | 910-1215ms (305ms spread, -69% tighter) |

Background model loading (warm): ~1420-1520ms total.
Embedding ready ~860ms after writePort. SPLADE ready ~1470ms after writePort.

## Work stream 3: AOT cache improvement — DEPRIORITIZED

### Original goal

Improve AOT cache effectiveness from 12.4% (curated 88-class training) to 40-60%
(production-trace training capturing all startup classes).

### Re-measurement against new baseline (2026-03-14)

The curated AOT cache was re-measured against the new deferred-loading baseline
to determine if trace training is worth pursuing.

**Baseline (deferred loading, no AOT, 5 runs):**

| Run | total |
|-----|-------|
| 1 | 974 |
| 2 | 945 |
| 3 | 979 |
| 4 | 952 |
| 5 | 930 |
| **median** | **952** |
| range | 930-979ms (49ms spread) |

**With curated AOT cache (5 runs):**

| Run | total |
|-----|-------|
| 1 | 894 |
| 2 | 914 |
| 3 | 899 |
| 4 | 1087 |
| 5 | 1060 |
| **median** | **914** |
| range | 894-1087ms (193ms spread) |

**Result: -38ms (-4.0%).** Down from -108ms (-12.4%) on the old sequential baseline.
Variance is WORSE with AOT (193ms spread vs 49ms, two outlier runs >1000ms).

**Root cause:** Deferred model loading moved the class-loading-heavy phases (ORT
session creation, DJL tokenizer, SPLADE encoder) to the background thread. The
synchronous path is now dominated by I/O (Lucene index open, SQLite, Netty TCP bind)
which AOT cannot help. The class-loading proportion of the sync path shrank, reducing
AOT's impact proportionally.

**Conclusion:** Even if trace training captured ALL startup classes (not just 88),
the theoretical ceiling is bounded by how much of the 952ms is class loading vs I/O.
With `lucene=269ms` + `grpc=180ms` being pure I/O, and `init=305ms` being a mix,
the class-loading-reducible portion is likely 200-300ms. Even a 50% reduction of that
yields 100-150ms — meaningful but not the 680-1020ms originally estimated.

**Disposition:** Stream 3 is deprioritized. The existing curated AOT cache gives a
small but measurable benefit (-38ms median) with zero risk. Trace training is a
complex change (needs running environment, exit timing, classpath matching) for
diminishing returns. Items 3a-3d are descoped.

Remaining low-effort item:
- [x] 3b. Fix dev-runner `TieredStopAtLevel=1` conflict for Head — implemented.
  `dev-runner.cjs` line 858: when `headAotOpts` is non-empty (AOT cache exists),
  `TieredStopAtLevel=1` is omitted from `JAVA_OPTS`. When absent, the flag is kept
  for faster JIT warmup. Head AOT cache generated (16 MB) and measured — Head-only
  phases show no measurable benefit (~5ms, within noise). The curated 76-class list
  covers too few classes to matter. Head startup is dominated by Jetty class loading
  and Netty imports, not the curated classes.

## Further research (2026-03-14)

Internet research on remaining optimization opportunities for the post-deferred-loading
baseline (~950ms sync, ~1.5s background).

### Near-term actionable

**Lucene read-only-first opening (~100-150ms sync path saving) — investigated 2026-03-14**
Open a read-only `DirectoryReader` immediately (for search), defer `IndexWriter` to
background (for indexing). Code review confirms this is feasible:
- `ComponentsFactory.build()` already has a hard `readOnly` branch: read-only uses
  `DirectoryReader.open(dir)` (no writer), read-write uses `DirectoryReader.open(writer)`
  (NRT reader, requires writer exists first).
- `createReadOnlyRuntime()` already exists in `KnowledgeServer` and is proven in
  production (Blue/Green migration path).
- `validateIndexableFields()` is pure in-memory — no writer needed.
- `drainSwitchBufferBestEffort()` needs the writer — must be deferred with it.
- `latestCommitUserDataBestEffort()` opens a fresh `DirectoryReader.open(dir)` — no
  writer needed.
- `prebuiltComponents` injection path exists in `LuceneIndexRuntime` (line 954).
- Schema mismatch detection happens before writer open (in read-only it logs and
  continues, in read-write it throws).
The main gap: no public `upgradeToReadWrite()` API. Would need either a package-private
hook or refactoring `KnowledgeServer` to open read-only first, then open read-write
in background and swap.

**ORT format + prepacked weights (~200-400ms background saving)**
Switch `OnnxSessionCache` from `.onnx.optimized` (protobuf) to `.ort` (flatbuffer)
for cached models. Eliminates protobuf deserialization on warm load. Add
`session.save_external_prepacked_constant_initializers=1` to save pre-packed weight
layouts — eliminates repacking on warm loads (~50-200ms per model). Both available
since ORT 1.20, project is on 1.24.3. Targets background path (shortens degraded-
search window), not sync path. Caveat: `.ort` format breaks across ORT version
upgrades — cache invalidation must handle this.

**ORT shared thread pools (small background saving)**
`CreateEnvWithGlobalThreadPools()` — share ORT thread pools across SPLADE + embedding
sessions. Currently each session creates its own thread pool. Reduces thread creation
overhead during background init.

### Medium-term / needs profiling

**OTel instrument reduction (part of 293ms init phase)**
The app registers 6 instrument selectors + 3 views + a `PeriodicMetricReader`. Each
adds overhead during `SdkMeterProvider.build()`. Profiling the `init` phase would
reveal how much of the 293ms is OTel vs IndexingLoop construction vs gauge registration.
If OTel dominates, reducing instruments or deferring gauge registration could help.

**`-XX:TieredOldPercentage` tuning (JetBrains technique)**
JetBrains uses this to balance L2 (fast compile) vs L4 (optimized code) during startup.
Not measured for this app. Would need JITWatch analysis to determine if it helps.

### Future (JDK upgrade required)

**JDK 26 AOT Object Caching (JEP 516) — HIGH potential**
Goes beyond class caching — pre-serializes Java heap objects in the AOT cache. A
baseline cache ships with JDK 26 itself (no custom training needed). GC-agnostic
format works with all collectors. The `init` phase (293ms) includes OTel
`SdkMeterProvider` construction (complex object graph), Jackson `ObjectMapper`
singletons, and gauge registrations — exactly the kind of object-heavy init that
JEP 516 targets. JDK 26 GA expected September 2026. Would require JDK upgrade from
25 to 26.

### Not viable / blocked

| Item | Status | Why |
|------|--------|-----|
| gRPC in-process transport | Blocked | Defeats process isolation (ADR-0001) |
| gRPC Windows named pipes | Blocked | No grpc-java implementation (issue #12027) |
| gRPC Unix domain sockets | Blocked | No Windows impl in grpc-java |
| SQLite WAL mode | Low value | Doesn't affect open time, only concurrent R/W |
| SQLite DLL pre-extraction | Low value | ~20ms one-time per JVM lifetime |
| Lucene `IndexInput.prefetch` | Windows-limited | Uses `madvise(MADV_WILLNEED)` — Linux/macOS only. Windows `PrefetchVirtualMemory` would need FFM bridge |

## Verification

1. **Compile**: `./gradlew.bat build -x test`
2. **Format**: `./gradlew.bat spotlessApply`
3. **Unit tests**: `./gradlew.bat :modules:indexer-worker:test` (Strategy A changes)
   + `./gradlew.bat :modules:ui:test` (Head changes)
4. **Startup measurement**: Worker standalone with models, compare phase timings
   against baselines in this tempdoc
5. **Functional smoke test**: After dev stack start, verify:
   - Search works (may be text-only for first few seconds, then hybrid)
   - Ingest works (queue accepts jobs, embedding backfills when ready)
   - `/api/status` shows readiness transition

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Phase-2 startup-performance work with Strategy-A implementation hooks. Subsequent startup optimization (tempdoc 275 cold-start baseline referenced in CLAUDE.md) ran; this tempdoc's strategy phase concluded.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

