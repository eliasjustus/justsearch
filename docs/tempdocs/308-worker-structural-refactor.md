---
title: "308: Worker Structural Refactor (Phase 2 Hot-Reload Unblock)"
type: tempdoc
status: done
created: 2026-03-15
updated: 2026-03-16
---

> NOTE: Noncanonical working tempdoc. Verify behavioral claims against canonical docs, code, and
> tests before promotion.

# 308: Worker Structural Refactor (Phase 2 Hot-Reload Unblock)

## Context

Tempdoc 305 (Hot-Reload for Dev Iteration) identified two structural blockers preventing
Phase 2 (custom classloader restart). Both are structural issues that affect the project
beyond hot-reload: testability, lifecycle management, and contributor clarity.

This tempdoc investigates both blockers in detail and provides a concrete implementation plan.

**Status: ALL STEPS COMPLETE (2026-03-16).** Service registry, gRPC delegating wrappers,
prerequisite refactoring, and three-module split all done. The 98-class `indexer-worker`
monolith is now split into `worker-core` (41 files), `worker-services` (32 files), and
`indexer-worker` (25 files). 788 tests pass (2 pre-existing failures).

**Blocker 1 — No clean module boundary.** `indexer-worker` contains both infrastructure
classes holding native state and pure-logic application classes in the same module (77
classes total). A classloader split requires a class-level allowlist — fragile, no
compile-time enforcement.

**Blocker 2 — Entangled wiring in `KnowledgeServer.start()`.** The 1329-line
`KnowledgeServer.java` has a monolithic `start()` method (lines 195-589) with 7 phases,
a deferred model init `CompletableFuture` with 16+ volatile setter calls, telemetry gauge
lambdas that capture application objects directly, and gRPC services registered with final
constructor-injected dependencies. A reload manager would have to replicate this entire
wiring graph via reflection.

## Investigation Results

### indexer-worker class inventory (77 classes)

| Category | Count | Examples |
|----------|-------|---------|
| Infrastructure (native state) | 8 | `MmfWorkerSignalBus`, `SqliteJobQueue`, `OnnxEmbeddingEncoder`, `SpladeEncoder`, `BertNerInference`, `EntityClusterStore`, `IndexRootLock`, `KnowledgeServer` |
| ML orchestrators (wrap native state) | 10 | `EmbeddingService`, `NerService`, `EmbeddingCompatibilityController`, `SpladeIdfQueryEncoder`, `DisambiguationService`, `SpladeConfig`, `NerConfig`, `EmbeddingFingerprint`, `SpladeFingerprint`, `OrtCudaHelper` |
| Application (pure logic, reloadable) | 45 | `SearchOrchestrator`, `GrpcSearchService`, `GrpcIngestService`, `IndexingLoop`, all `*Ops` classes, `ContentExtractor`, `ChunkDocumentWriter` |
| Shared/interfaces | 6 | `WorkerSignalBus`, `JobQueue`, `ContentExtractorProvider`, `OperationalMetrics` (singleton), `WorkerConfig`, `AotTraining` |
| Server wiring | 4 | `KnowledgeServerGrpcWiring`, `KnowledgeServerMigrationOps`, `KnowledgeServerSafeMetrics`, `IndexerWorker` (entry point) |
| Util/records/constants | 4 | `SqliteSchema`, `EntityClusterSchema`, `NerResult`, `IngestResponses` |

The 45 reloadable application classes are the ones that change most during development:
search logic, ranking, indexing pipeline, API handlers, content extraction.

### KnowledgeServer.start() dependency graph

**Construction order** (7 phases):

```
Phase 0: telemetry = new LocalTelemetry(...)
Phase 1: signalBus = new MmfWorkerSignalBus(...); signalBus.open()
Phase 2: jobQueue = new SqliteJobQueue(...); jobQueue.open()
Phase 3: indexGenerationManager, searchRuntime, ingestRuntime (Lucene)
Phase 3.5: indexingLoop = new IndexingLoop(jobQueue, ingestRuntime, signalBus, ...)
           registerTelemetryGauges()
Phase 4: grpcServer = createGrpcServer(interceptors)
         → GrpcHealthService(jobQueue, searchRuntime, embeddingService[null], ...)
         → GrpcIngestService(jobQueue, indexingLoop, signalBus, ingestRuntime, ...)
         → GrpcSearchService(searchRuntime, embeddingService[null])
         grpcServer.start()
Phase 5: signalBus.writePort(boundPort)  [Head unblocked]
Phase 6: indexingLoop.start()
Phase 7: startSentinelThread()
```

**Deferred model init** (background CompletableFuture, lines 464-582):

| Step | Constructs | Volatile setter calls |
|------|------------|----------------------|
| A: IndexWriter | `ingestRuntime.openWriterDeferred()` | — |
| B: Embedding | `EmbeddingService.createWithAutoDiscovery()` | `indexingLoop.setEmbeddingService(es)` |
| C: ECC | `new EmbeddingCompatibilityController(...)` | `indexingLoop.setECC()`, `searchService.setECC()`, `ingestService.setECC()`, `embeddingFingerprintSupplier.set()` |
| D: NER | `NerService(nerConfig)` | `indexingLoop.setNerService()` |
| E: SPLADE | `new SpladeEncoder(spladeConfig, ...)` | `indexingLoop.setSpladeEncoder()`, `searchService.setSpladeEncoder()`, `searchService.setSpladeIdfQueryEncoder()` |
| F: Disambiguation | `new DisambiguationService(dataDir)` | `indexingLoop.setDisambiguationService()`, `searchService.setClusterSnapshotSupplier()` |
| G: GPU diagnostics | — | `ingestService.setSpladeOrtCudaStatusSupplier()`, etc. (5 calls) |

**Hard object captures** (problematic for hot-reload):

1. **`workerPausedGauge` lambda** (line 407): captures `indexingLoop` by field name. After
   reload, the gauge polls the old (stopped) instance.
2. **`GrpcIngestService` constructor** (line ~597): takes `indexingLoop` as a final param.
   After reload, ingest service keeps old reference.
3. **`GrpcHealthService` constructor**: takes `searchRuntime` and `embeddingService` as
   final params.
4. **`GrpcSearchService` constructor**: takes `searchRuntime` and `embeddingService` as
   final params.
5. **`IndexingLoop` constructor**: takes `ingestRuntime` and `jobQueue` as final params.

**Safe (indirected) captures**: All job-queue and runtime gauges dereference `this.*` fields
at call time. `indexingLoopStateSupplier` re-reads `this.indexingLoop`. `embeddingFingerprintSupplier`
is an `AtomicReference`.

### gRPC service structure

| Service | Extends | RPC Count | Constructor deps (final) |
|---------|---------|-----------|--------------------------|
| `GrpcSearchService` | `SearchServiceGrpc.ImplBase` | 9 | `LuceneIndexRuntime`, `EmbeddingService` |
| `GrpcIngestService` | `IngestServiceGrpc.ImplBase` | 18 | `JobQueue`, `IndexingLoop`, `WorkerSignalBus`, `LuceneIndexRuntime` (x2), + 4 more |
| `GrpcHealthService` | `HealthServiceGrpc.ImplBase` | 1 | `JobQueue`, `LuceneIndexRuntime`, `EmbeddingService`, + 2 more |

All 28 Worker-side RPCs are unary. gRPC-Java does not support runtime service replacement
after `server.start()` — `ServerBuilder.build()` produces an immutable `Server`.

Existing delegation pattern: `GrpcIngestService` already delegates to 4 ops objects
(`IndexStatusOps`, `SyncDirectoryOps`, `IngestSwitchBufferOps`, `MigrationControlOps`).
`GrpcSearchService` delegates to `SearchOrchestrator`, `CitationMatchOps`, `RagContextOps`.

## Recommended Architecture

### Module split: `worker-services` (new module)

Extract the 45 reloadable application classes into a new `worker-services` module.
`indexer-worker` becomes a thin server shell (entry point + infrastructure + wiring).

**`indexer-worker` retains (platform classloader)**:
- Entry: `IndexerWorker`, `WorkerConfig`, `AotTraining`
- Server: `KnowledgeServer`, `KnowledgeServerGrpcWiring`, `KnowledgeServerMigrationOps`, `KnowledgeServerSafeMetrics`
- Infrastructure: `MmfWorkerSignalBus`, `SqliteJobQueue`, `SqliteSchema`, `SqliteQueueMigrationOps`, `SqliteQueueSwitchBufferOps`
- ML services: `EmbeddingService`, `EmbeddingCompatibilityController`, `EmbeddingFingerprint`, `EmbeddingMetadataOverlay`, `OnnxEmbeddingEncoder`, `OnnxEmbeddingProvider`, `OnnxEmbeddingBackend`, `EmbeddingOnnxModelDiscovery`
- ML services: `SpladeEncoder`, `SpladeConfig`, `SpladeFingerprint`, `SpladeIdfQueryEncoder`, `SpladeModelDiscovery`, `SpladeTruncationEvidence`
- ML services: `BertNerInference`, `NerService`, `NerConfig`, `NerModelDiscovery`, `NerResult`, `BioTagDecoder`
- Disambiguation: `DisambiguationService`, `EntityClusterStore`, `EntityClusterSnapshot`, `EntityClusterSchema`, `EntityNormalizer`, `SoftTFIDF`
- Interfaces: `WorkerSignalBus`, `JobQueue`, `ContentExtractorProvider`
- gRPC infra: `GrpcContextKeys`, `RequestMetadataInterceptor`, `TracingServerInterceptor`
- Index gen: `IndexGenerationManager`, `MigrationProgressStore`, `MigrationProgressSnapshot`
- Shared: `OperationalMetrics` (JVM singleton), `OrtCudaHelper`, `IndexRootLock`, `Sha256SidecarCache`
- NEW: Delegating gRPC wrappers (see below)
- NEW: `WorkerAppServices` interface (see below)

**`worker-services` contains (application classloader)**:
- `SearchOrchestrator` — search routing, ranking, fuzzy correction
- `GrpcSearchService` — 9 RPC implementations
- `GrpcIngestService` — 18 RPC implementations
- `GrpcHealthService` — 1 RPC implementation
- `IndexingLoop` — background indexing loop
- `IndexingDocumentOps`, `EmbeddingBackfillOps`, `NerBackfillOps`, `SpladeBackfillOps`, `DisambiguationBackfillOps`, `LoopPacingPolicy`
- `CitationMatchOps`, `HighlightingOps`, `IndexStatusOps`, `IngestResponses`, `IngestSwitchBufferOps`, `MigrationControlOps`, `RagContextOps`, `SyncDirectoryOps`, `LanguageUtils`
- `ContentExtractor`, `TimeboxedContentExtractor`
- `ChunkDocumentWriter`
- `TextQualityAnalyzer`
- `ParseUtils`, `PathNormalizer`, `ProtoConverters`, `TextAnalysisUtils`, `VectorUtils`
- NEW: `DefaultWorkerAppServices` (factory, see below)

**Dependency direction**: `indexer-worker` → `worker-services` (compile-time for initial
construction). For hot-reload Phase 2: `worker-services` JAR goes in the child classloader,
constructed via a factory interface.

### Service registry: `WorkerAppServices` interface

Replace the monolithic wiring in `KnowledgeServer.start()` with an explicit contract.

```java
// In indexer-worker (platform classloader):
public interface WorkerAppServices extends Closeable {
    /** gRPC service binding for SearchService RPCs. */
    BindableService searchService();
    /** gRPC service binding for IngestService RPCs. */
    BindableService ingestService();
    /** gRPC service binding for HealthService RPCs. */
    BindableService healthService();

    /** Start the indexing loop background thread. */
    void startIndexingLoop();
    /** Get current indexing loop state (for telemetry/health). */
    String indexingLoopState();

    // --- Deferred model wiring (called by background init) ---
    void setEmbeddingService(EmbeddingService es);
    void setEmbeddingCompatController(EmbeddingCompatibilityController ecc);
    void setNerService(NerService ns);
    void setSpladeEncoder(SpladeEncoder enc);
    void setSpladeIdfQueryEncoder(SpladeIdfQueryEncoder idfEnc);
    void setDisambiguationService(DisambiguationService ds);
    void setGpuDiagnosticSuppliers(/* supplier bundle */);
}
```

**`InfraContext` record** — immutable bag of infrastructure refs passed to the factory:

```java
public record InfraContext(
    WorkerConfig config,
    JobQueue jobQueue,
    LuceneIndexRuntime searchRuntime,
    LuceneIndexRuntime ingestRuntime,
    WorkerSignalBus signalBus,
    Telemetry telemetry,
    Path indexBasePath,
    Path activeIndexPath,
    Supplier<MigrationProgressSnapshot> migrationProgressSupplier,
    Runnable initiateShutdownAction
) {}
```

**`DefaultWorkerAppServices`** (in `worker-services`):

```java
public class DefaultWorkerAppServices implements WorkerAppServices {
    private final IndexingLoop indexingLoop;
    private final GrpcSearchService searchService;
    private final GrpcIngestService ingestService;
    private final GrpcHealthService healthService;

    public DefaultWorkerAppServices(InfraContext ctx) {
        var contentExtractor = new TimeboxedContentExtractor(
            new ContentExtractor(), TimeboxedContentExtractor.DEFAULT_TIMEOUT, ctx.telemetry());
        this.indexingLoop = new IndexingLoop(
            ctx.jobQueue(), ctx.ingestRuntime(), ctx.signalBus(),
            ctx.config().pipeline(), null, ctx.telemetry(), contentExtractor);
        this.searchService = new GrpcSearchService(ctx.searchRuntime(), null);
        this.ingestService = new GrpcIngestService(
            ctx.jobQueue(), indexingLoop, ctx.signalBus(), ctx.indexBasePath(),
            ctx.activeIndexPath(), ctx.ingestRuntime(), ctx.searchRuntime(),
            ctx.migrationProgressSupplier(), /* switchingMaxDurationMs */,
            ctx.initiateShutdownAction());
        this.healthService = new GrpcHealthService(/* ... */);
    }

    // All deferred setter calls delegate internally — no reflection needed
    @Override public void setEmbeddingService(EmbeddingService es) {
        indexingLoop.setEmbeddingService(es);
    }
    // ... etc.
}
```

**KnowledgeServer.start() becomes**:

```java
// Phase 3.5: construct application services
InfraContext infraCtx = new InfraContext(config, jobQueue, searchRuntime, ...);
appServices = new DefaultWorkerAppServices(infraCtx);

// Phase 4: gRPC — register delegating wrappers, not the services directly
grpcServer = createGrpcServer(interceptors, appServices);

// Phase 6:
appServices.startIndexingLoop();

// Deferred init: calls appServices.setEmbeddingService(es) etc.
```

### gRPC delegating wrappers

Thin classes in `indexer-worker` (platform classloader) that extend the proto-generated
`*ImplBase` and delegate to a `volatile` reference. Registered once with the gRPC server.

```java
// In indexer-worker:
public final class DelegatingSearchService extends SearchServiceGrpc.SearchServiceImplBase {
    private volatile SearchServiceGrpc.SearchServiceImplBase delegate;

    public void setDelegate(SearchServiceGrpc.SearchServiceImplBase delegate) {
        this.delegate = delegate;
    }

    @Override
    public void search(SearchRequest req, StreamObserver<SearchResponse> obs) {
        delegate.search(req, obs);
    }
    // ... 8 more one-line forwards
}
```

All 28 RPCs are unary with uniform signature `void name(ReqType, StreamObserver<RespType>)`.
Each forward is a single line. Estimated ~50 lines per wrapper, ~150 lines total for 3 services.

On reload: `delegatingSearchService.setDelegate(newAppServices.searchService())`. In-flight
RPCs complete with the old implementation; new RPCs immediately see the new one. No request
drops.

### Telemetry gauge fix

The `workerPausedGauge` lambda captures `indexingLoop` directly (line 407-410):

```java
// BEFORE (broken after reload):
workerPausedGauge = telemetry.gauge("worker.indexing.paused", ...,
    () -> {
      String st = indexingLoop == null ? "" : indexingLoop.getCurrentState();
      return IndexingLoop.STATE_PAUSED.equals(st) ? 1.0 : 0.0;
    });
```

Fix: delegate through `appServices.indexingLoopState()`:

```java
// AFTER (reload-safe):
workerPausedGauge = telemetry.gauge("worker.indexing.paused", ...,
    () -> {
      String st = appServices == null ? "" : appServices.indexingLoopState();
      return "PAUSED".equals(st) ? 1.0 : 0.0;
    });
```

This works because the gauge lambda captures `this` (KnowledgeServer), and
`this.appServices` is the current reference, re-read on each gauge poll.

## Stress-test findings (2026-03-15)

### Finding 1: The factory is NOT a clean lifecycle boundary

The `WorkerAppServices` interface was designed to encapsulate construction + teardown of
application services. In practice, `KnowledgeServer` cannot delegate lifecycle to the factory:

- **Shutdown ordering is load-bearing.** The 17-step `close()` interleaves application
  and infrastructure closes: `indexingLoop.close()` must happen before `grpcServer.shutdown()`
  which must happen before `searchRuntime.close()`. The factory's `close()` cannot run
  independently — KnowledgeServer must still orchestrate the full sequence.
- **Deferred model init creates temporal coupling.** The `CompletableFuture.runAsync()` at
  line 464 writes to KnowledgeServer fields (`embeddingService`, `spladeEncoderInstance`)
  AND calls setters on factory-produced objects. The factory returns half-initialized objects.
  `close()` must await `deferredModelInit.get()` (line 902) before closing anything.
- ~~**`IndexingLoop.close()` double-closes shared services.**~~ **FIXED in Step 2.5d.**
  Removed closes of borrowed services. Added idempotency guard to `SpladeEncoder.close()`.
- **`IndexingLoop.close()` is not a hard stop.** 5s `join()` timeout at line 1250. ORT
  native inference calls are not interruptible. After `close()` returns, the loop thread
  may still be alive, calling `indexRuntime.index()` on a not-yet-closed runtime. For
  hot-reload, a new IndexingLoop could be started while the old thread is still running.

**Implication**: `WorkerAppServices` is better described as a "construction helper" — it
centralizes how application objects are created and wired, but does NOT own their lifecycle.
KnowledgeServer remains the lifecycle orchestrator. This is still valuable (cleaner
construction, testable wiring), but the tempdoc overclaimed what it provides.

### Finding 2: gRPC wrappers must expose all non-RPC methods

The tempdoc estimated ~150 lines for "thin one-line RPC forwards." In practice:

- `KnowledgeServer` calls ~12 non-RPC methods on `GrpcSearchService`:
  `setEmbeddingCompatController`, `setSpladeEncoder`, `setSpladeIdfQueryEncoder`,
  `setClusterSnapshotSupplier`, `setChunkRerankerConfig`, `setCitationScorerConfig`,
  `setSignalBus`, `onMainClaimedGpu`, `getOrtCudaStatus`
- `KnowledgeServer` calls ~8 non-RPC methods on `GrpcIngestService`:
  `setEmbeddingCompatController`, `setSpladeOrtCudaStatusSupplier`,
  `setSpladeModelPathSupplier`, `setEmbedOrtCudaStatusSupplier`,
  `setEmbedBackendSupplier`, `setEmbedGpuLayersSupplier`, `setOrtCudaStatusSupplier`,
  `setRerankerModelPathSupplier`
- `searchService::getOrtCudaStatus` is captured as a method-reference into `ingestService`
  at `KnowledgeServerGrpcWiring.java` line 94
- The sentinel thread calls `searchService.onMainClaimedGpu()` every second (line 810)

Each wrapper must expose ALL these methods and forward them to the volatile delegate.
Realistic estimate: ~300-400 lines total, coupled to the concrete service API surface.

### Finding 3: Module split had concrete coupling blockers — NOW RESOLVED

~~The tempdoc described Step 3 as "mechanical file moves, no Java source changes." In practice:~~

**All three coupling blockers resolved in Step 2.5 (2026-03-15):**

- ~~**`SqliteJobQueue` coupling**~~ **FIXED.** `JobQueue` interface expanded with
  `jobStateCounts()`, `deleteByPathPrefix()`, `deleteByExactPath()`. New
  `SwitchBufferCapableQueue` sub-interface for switch-buffer ops. All 3 application classes
  (`GrpcIngestService`, `IngestSwitchBufferOps`, `IndexStatusOps`) now use interface methods.
  Only 1 `instanceof SqliteJobQueue` remains (SQLite-only `queueDbHealthSnapshot`).
- ~~**`EntityClusterSnapshot` coupling**~~ **FIXED.** `ClusterEntry` extracted to top-level
  record. No compile-time dependency on `EntityClusterStore`.
- ~~**`EmbeddingService` coupling**~~ **Documented as no-op.** Both classes stay in
  `worker-core`; the `instanceof` doesn't cross a module boundary.

**Step 3 is now closer to mechanical file moves** (plus Gradle module wiring).

### Finding 4: Callbacks and suppliers leak across the boundary

- `this::initiateShutdown` (KS line 608): passed to `GrpcIngestService` via
  `MigrationControlOps`. Sets `KnowledgeServer.running = false` and calls
  `grpcServer.shutdown()`. The factory cannot own shutdown authority.
- `this::migrationProgressSnapshot` (KS line 606): closes over 10 `AtomicLong` fields on
  KnowledgeServer. The factory must receive this as a pre-built `Supplier`, not own it.
- OTel gauge lambdas (KS lines 404-410): `workerPausedGauge` captures `indexingLoop`
  by field. After factory replacement, gauge polls the old (closed) instance until handles
  are deregistered.
- Sentinel thread (KS line 810): calls `searchService.onMainClaimedGpu()` directly. Must
  use callback indirection instead of direct field access.

## Revised confidence (post-2.5, 2026-03-15)

| Step | Pre-stress-test | Post-stress-test | Post-2.5 | Final |
|------|----------------|-----------------|----------|-------|
| Step 2.5 (prereqs) | N/A | 40-50% | **DONE** | **DONE** |
| Step 1 (service registry) | Medium risk | High risk (60-70%) | 65-75% | **DONE** |
| Step 2 (gRPC delegation) | Low risk | Medium risk (75%) | 75% | **DONE** |
| Step 3 (module split) | Mechanical | Blocked by prereqs (40-50%) | 65-75% | **DONE** |
| Phase 2 overall | "Straightforward" | Overstated (50%) | 55-65% | **75-85%** — All structural work done, only reload manager remains |

### What the plan gets right

- The general direction (decouple wiring, introduce delegation, split modules) is sound
- The three-module solution for Gradle circular deps is correct
- The phased approach (registry first, split later) is prudent
- The class inventory and dependency graph are accurate
- The telemetry gauge fix is straightforward
- **Step 2.5 resolved the hardest prerequisite blockers** — interface design for JobQueue

### What the plan gets wrong (still true after 2.5)

- The factory is a construction helper, not a lifecycle boundary
- The gRPC wrappers are coupled to the concrete service API, not just proto
- ~~The module split requires interface design work, not just file moves~~ (FIXED by 2.5)
- ~~Step 3 has at least 3 prerequisite refactors before file moves can begin~~ (DONE)

### Remaining risks not addressed by any step

1. **Non-interruptible ORT calls.** `IndexingLoop.close()` has a 5s join timeout. If ORT
   inference is in progress, the loop thread may still be alive after `close()` returns.
   For hot-reload, this means the old loop could call infrastructure methods after the new
   loop starts. Mitigation: reload manager must poll `loopThread.isAlive()` before starting.
2. **Load-bearing shutdown order.** KnowledgeServer.close() interleaves application and
   infrastructure closes. The factory's close() is not free-standing — its position in the
   shutdown sequence is specified. This constrains the service registry design.
3. **Deferred model init temporal coupling.** The factory produces half-initialized objects.
   The background `CompletableFuture` completes them asynchronously. Any factory-replace flow
   must await `deferredModelInit.get()` before proceeding.

## Decision point: what to do next (2026-03-15)

Step 2.5 resolved the concrete coupling blockers and fixed a real bug. That was high-value
work regardless of whether Phase 2 ever happens. The remaining steps (1, 2, 3) are
Phase-2-specific preparation.

**Key question: should Steps 1-2 be done now, or deferred until Phase 2 is actively being
built?**

Arguments for deferring:
- Phase 1 (JBR + HotSwapPush) already works for the common dev case
- Steps 1-2 are ~700-1200 lines of complex refactoring with no immediate user-facing benefit
- The investigation, plan, and prerequisite refactoring are documented and committed — a
  future agent can pick up where this left off
- Step 1 (service registry) is high risk: refactoring 394 lines of KS.start() with implicit
  ordering, and the factory doesn't even own lifecycle
- Risk of breakage during refactoring with no immediate payoff

Arguments for proceeding:
- The plan and codebase understanding are fresh in context — reloading this later costs time
- Step 2 (gRPC delegation) is mechanical and directly enables runtime service swapping
- Steps 1-2 provide standalone value (testable construction, explicit wiring) even without
  Phase 2

**Recommendation**: If Phase 2 is not on the immediate roadmap, defer Steps 1-2. The
tempdoc captures everything needed to resume. If proceeding, do Step 2 first (lower risk,
more directly useful, independent of Step 1).

## Implementation plan

Execution order: Step 2.5 (done) → Step 2 (done) → Step 1 (done) → Step 3 (done).
All steps complete. Phase 2 hot-reload (custom classloader restart) is now unblocked.

### Step 1: `WorkerAppServices` interface + `InfraContext` record — DONE (2026-03-15)

- [x] 1a-f. Created `WorkerAppServices`, `InfraContext`, `GpuDiagnosticSuppliers`,
  `DefaultWorkerAppServices`. Updated KnowledgeServer (15→7 wire calls, appServices field).
  Simplified KnowledgeServerGrpcWiring (14-field record → 3 params). All tests pass.

### Step 2: gRPC delegating wrappers — DONE (2026-03-15)

- [x] 2a-f. Created 3 delegating wrappers (~383 lines). Null guards, deprecation suppression.
  All tests pass.

### Step 2.5: Prerequisite refactoring for module split — DONE (2026-03-15)

18 files changed, +232/-162 lines, 2 new files created. All indexer-worker tests pass
(2 pre-existing model-discovery failures unrelated to changes).

- [x] 2.5a. **Expanded `JobQueue` interface.** Added `jobStateCounts()` + `JobStateCounts`
  record (moved from SqliteJobQueue), `deleteByPathPrefix()`, `deleteByExactPath()` with
  default no-op implementations. Created `SwitchBufferCapableQueue extends JobQueue`
  sub-interface for migration switch-buffer ops (`putSwitchBuffer`, `switchBufferDepth`,
  `listSwitchBufferOps`, `clearSwitchBuffer`, `SwitchBufferOp` record). Updated all callers:
  `GrpcIngestService`, `IngestSwitchBufferOps`, `IndexStatusOps`, `KnowledgeServerSafeMetrics`,
  `KnowledgeServerMigrationOps`. Only 1 `instanceof SqliteJobQueue` remains
  (`queueDbHealthSnapshot` in `IndexStatusOps` — intentionally SQLite-specific).
- [x] 2.5b. **Extracted `ClusterEntry`** to top-level record in the `disambiguation` package.
  16 reference sites updated across 5 files. No logic changes.
- [x] 2.5c. **Documented `EmbeddingService`/`OnnxEmbeddingBackend` coupling** as intentional
  no-op. Both classes stay in `worker-core` together; the `instanceof` doesn't cross a module
  boundary. Comment added at line 239.
- [x] 2.5d. **Fixed `IndexingLoop.close()` double-close bug.** Removed closes of 3 borrowed
  services (`embeddingService`, `spladeEncoder`, `disambiguationService`) — KnowledgeServer
  is the owner. Kept `nerService` (sole closer) and `contentExtractor` (owned). Added
  `AtomicBoolean closed` idempotency guard to `SpladeEncoder.close()` (defense-in-depth).

### Step 3: Module split — three-module architecture — DONE (2026-03-16)

- [x] 3a-j. Created `worker-core` (41 source, 22 tests) and `worker-services` (32 source,
  31 tests). `indexer-worker` retains 25 source + 19 tests. `installDist` includes all 3
  JARs. 6 classification adjustments vs plan (embed.onnx, gRPC interceptors,
  SpladeTruncationEvidence → worker-core; ContentExtractorProvider, WorkerAppServices,
  TestDocumentBuilder → worker-services). Last `instanceof SqliteJobQueue` eliminated via
  `JobQueue.QueueDbHealthSnapshot` default method. system-tests dep + lock files updated.

### Step 4: Verify hot-reload Phase 2 is unblocked

After Steps 1-3, Phase 2 from tempdoc 305 becomes straightforward:

- [ ] 4a. Verify that `worker-services` JAR can be loaded by a child `URLClassLoader`
  while `indexer-worker` stays in the parent
- [ ] 4b. Verify that `DefaultWorkerAppServices` can be instantiated via reflection from
  the child classloader with an `InfraContext` from the parent
- [ ] 4c. Verify that delegating gRPC wrappers correctly forward to child-classloader
  service implementations
- [ ] 4d. Document the classloader boundary in `docs/explanation/01-system-overview.md`

## Impact assessment (revised post-2.5)

### Step 2.5 — DONE

| Metric | Actual |
|--------|--------|
| Files changed | 18 (2 new) |
| Lines | +232 / -162 |
| Risk realized | None — all tests pass |

### Step 2 (gRPC delegation) — DONE

| Metric | Actual |
|--------|--------|
| New code | ~383 lines (3 delegating wrappers) |
| Changed code | ~30 lines in KnowledgeServerGrpcWiring + KnowledgeServer |
| Risk realized | None |

### Step 1 (service registry) — DONE

| Metric | Actual |
|--------|--------|
| New code | ~299 lines (4 files: interface, 2 records, factory) |
| Changed code | KnowledgeServer -53 lines, GrpcWiring -66 lines |
| Risk realized | None |

### Step 3 (three-module split) — DONE

| Metric | Actual |
|--------|--------|
| New code | ~170 lines (2 build.gradle.kts, settings.gradle.kts) |
| Moved code | 73 source + 53 test files, 4 test fixtures |
| Java source changes | 2 files (IndexStatusOps instanceof removal, SqliteJobQueue @Override) |
| New modules | 2 (worker-core, worker-services) |
| Classification adjustments | 6 vs plan |
| Risk realized | All caught at compile time; iterative fix-and-recompile |

## Benefits beyond hot-reload

1. **Test isolation**: `worker-services` can be tested with mock `InfraContext` — no SQLite,
   no Lucene, no ORT needed for pure-logic unit tests.
2. **Explicit dependency graph**: `InfraContext` makes infrastructure dependencies visible
   at a glance. No more hidden field references across 1329 lines.
3. **Lifecycle clarity**: `WorkerAppServices.close()` has one place to stop all application
   services. No more 17-step teardown with interleaved infrastructure and application closes.
4. **Contributor onboarding**: New contributors can work in `worker-services` without
   understanding ORT, Lucene, or MMF internals.
5. **Compile-time safety**: The module boundary prevents accidental native-state coupling.
   If a new class in `worker-services` tries to `import OrtSession`, Gradle catches it.

## Circular dependency analysis (critical constraint)

**The naive two-module split creates a Gradle circular dependency.** `worker-services`
needs infrastructure types from `indexer-worker` (e.g., `EmbeddingService`, `JobQueue`,
`WorkerSignalBus`), and `indexer-worker` needs to construct `DefaultWorkerAppServices`
from `worker-services`. Gradle rejects project-level cycles regardless of configuration
type (`implementation` vs `runtimeOnly`).

**Confirmed by codebase precedent**: The `ai-bridge` pattern avoids cycles because the
interface module (`ai-bridge`) has ZERO dependency on the implementation module
(`indexer-worker`). ServiceLoader discovers implementations at runtime without a Gradle
dependency.

### Three-module solution (recommended for Phase 2)

```
worker-core (infrastructure types + interfaces)
    ↑                    ↑
    |                    |
worker-services          indexer-worker
(app logic)             (entry point, KnowledgeServer, wiring)
                              ↑
                              |
                        worker-services (also)
```

- **`worker-core`** — Infrastructure types currently in `indexer-worker` that the application
  layer depends on: `EmbeddingService`, `SpladeEncoder`, `NerService`, `DisambiguationService`,
  `WorkerSignalBus` (interface), `JobQueue` (interface), `ContentExtractorProvider` (interface),
  `OperationalMetrics`, `WorkerConfig`, `IndexGenerationManager`, `MigrationProgressSnapshot`,
  `EmbeddingCompatibilityController`, plus ML configs/fingerprints.
- **`worker-services`** — Application classes: `SearchOrchestrator`, `GrpcSearchService`,
  `GrpcIngestService`, `IndexingLoop`, all ops, extractors, utilities.
  Depends on `worker-core`.
- **`indexer-worker`** — Entry point + `KnowledgeServer` + delegating gRPC wrappers +
  native-state implementations (`MmfWorkerSignalBus`, `SqliteJobQueue`, `OnnxEmbeddingEncoder`).
  Depends on both `worker-core` and `worker-services`.

No circular dependency. For hot-reload Phase 2:
- Platform classloader: `worker-core` + `indexer-worker` + third-party JARs
- Application classloader: `worker-services` (reloadable)

### Phased approach (recommended)

The module split is significant (moving ~30 types into `worker-core`, ~45 into
`worker-services`). We should **defer the module split** until Phase 2 is actually
implemented. The service registry (Steps 1-2) can be done entirely within `indexer-worker`
and provides standalone value.

**Immediate (this tempdoc)**: Steps 1-2 (service registry + gRPC delegation within `indexer-worker`)
**Deferred (Phase 2 work)**: Step 3 (three-module split with `worker-core` + `worker-services`)

## Open questions

- [ ] Should `OperationalMetrics` (JVM singleton) live in `worker-core` or `telemetry`?
  Currently referenced by both infrastructure and application classes. As a singleton,
  it works across classloaders if it's in the parent. `worker-core` is simplest.
- [ ] Should `IndexGenerationManager` go in `worker-core` or `worker-services`? It manages
  the Blue/Green directory layout. No native state. Used by `GrpcIngestService` (application)
  and `KnowledgeServer` (wiring). Recommendation: `worker-core` — it's lifecycle infra.
- [ ] How many infrastructure types need to move to `worker-core`? Rough estimate: ~30
  classes. This is a significant file move. Verify that ArchUnit and PMD rules don't block
  cross-module references.

## Progress

- [x] Full class inventory of `indexer-worker` (77 classes categorized)
- [x] `KnowledgeServer.start()` dependency graph (7 phases, 16+ volatile setter calls)
- [x] Telemetry gauge lambda captures (1 problematic: `workerPausedGauge`)
- [x] gRPC service structure (28 RPCs, all unary, 3 services)
- [x] Close/shutdown path (17 steps)
- [x] Sentinel thread structure (3 checks: shouldDie, GPU lifecycle, periodic cleanup)
- [x] Circular dependency analysis (three-module solution identified)
- [x] Codebase precedent for ServiceLoader and thin API modules (`ai-bridge`, `app-api`)
- [x] Stress-test: factory lifecycle, gRPC non-RPC methods, coupling blockers, callbacks
- [x] **Step 2.5 complete**: JobQueue expansion, ClusterEntry extraction, double-close fix,
  EmbeddingService coupling documented (18 files, +232/-162 lines)
- [ ] Step 1: Service registry (`WorkerAppServices` + `InfraContext` + `DefaultWorkerAppServices`)
- [ ] Step 2: gRPC delegating wrappers (3 wrappers, ~300-400 lines)
- [ ] Step 3: Three-module split (deferred to Phase 2)
- [ ] Verify no ArchUnit or PMD rules block cross-module references (Step 3)
- [ ] Verify `installDist` correctly bundles multi-module Worker distribution (Step 3)
