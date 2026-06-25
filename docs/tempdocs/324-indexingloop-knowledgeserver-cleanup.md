---
title: "IndexingLoop & KnowledgeServer Cleanup"
status: done
created: 2026-03-18
---

# 324 — IndexingLoop & KnowledgeServer Cleanup

## Diagnosis

The two oldest production files (both ~4 months, created 2025-11-27) are the largest
hand-written Java files after the LuceneIndexRuntime rewrite. Neither is a God Object —
they don't have forwarding methods or hidden dependency graphs. They're procedural
orchestrators that grew organically. The issues are dead code, long methods, misplaced
utilities, and duplicated logic.

| File | LOC | Role | Core problem |
|------|-----|------|--------------|
| `IndexingLoop.java` | 1,527 | Background indexing + 5 backfill pipelines | Dead code, duplicated logic, misplaced utilities |
| `KnowledgeServer.java` | 1,411 | Worker process composition root | 440-line `start()` method, embedded model init lambda |

Neither warrants a rewrite. Method extraction and dead code removal are sufficient.

## IndexingLoop issues

### IL-1: Delete dead `processJob()` method (~118 LOC)

File: `modules/worker-services/src/main/java/io/justsearch/indexerworker/loop/IndexingLoop.java`

Lines 882-1000: `processJob()` is annotated `@SuppressWarnings("unused")`. It's the old
per-document processing path replaced by `processBatch()` + `extractJob()` +
`writeExtractedJob()`. The batch path is the live code. `processJob` is dead weight that
duplicates extraction, pre-checks, embedding, write, and error handling logic.

**Action:** Delete the method. Verify no callers via grep. If any test references it
via reflection, update the test.

**Impact:** -118 LOC, zero behavioral change.

### IL-2: Extract language detection utilities (~75 LOC)

Lines 1085-1160: 13 static helper methods (`contentPreview`, `normalizeMimeBase`,
`classifyFileKind`, `isMarkdownExtension`, `isCodeExtension`, `resolveLanguage`,
`resolveDefaultLanguageTag`, `detectLanguage`, `isLatin`, `isCyrillic`, `isHan`,
`isKana`, `isHangul`, `isArabic`, `isDevanagari`, `isGreek`).

These are pure utility functions with no dependency on IndexingLoop state. They belong
in a standalone helper class.

**Action:** Create `IndexingDocumentClassifier.java` (or similar) in the same package.
Move all 13 methods. Update callers within IndexingLoop (`extractJob`, `writeExtractedJob`,
`processJob` — but IL-1 deletes the last one).

**Impact:** -75 LOC from IndexingLoop, +75 LOC in new file. Cleaner separation.

### IL-3: Extract duplicated SPLADE retry/backoff logic

Lines 518-535 (idle backfill path) and 599-616 (interleave path) contain identical
retry backoff logic:

```java
if (success) {
    consecutiveSpladeFailures = 0;
    nextSpladeRetryTime = 0;
} else {
    consecutiveSpladeFailures++;
    long backoffMs = Math.min(
        LoopPacingPolicy.idleSleepMs() * (1L << consecutiveSpladeFailures), 60_000L);
    nextSpladeRetryTime = System.currentTimeMillis() + backoffMs;
    log.warn("SPLADE backfill failed ({} consecutive), next retry in {}ms",
        consecutiveSpladeFailures, backoffMs);
}
```

**Action:** Extract to `private void recordSpladeBackfillResult(boolean success)`.
Both call sites become one-liners.

**Impact:** -20 LOC duplication, cleaner intent.

### IL-4: Delete `buildDocument()` method if dead

Line 1063: `buildDocument()` is a private method. Check if it's only called from the
dead `processJob()`. If so, it dies with IL-1.

**Action:** Verify callers. Delete if dead.

## KnowledgeServer issues

### KS-1: Extract deferred model initialization (~160 LOC)

Lines 472-624: The `CompletableFuture.runAsync(() -> { ... })` lambda inside `start()`
is 160 LOC of model loading orchestration: embedding service, NER, SPLADE, BGE-M3,
disambiguation, compatibility controller, GPU diagnostics.

This is a self-contained concern — it runs on a background thread, accesses fields via
volatile setters, and has its own error handling. Embedding it inside `start()` makes
the method 440 lines.

**Action:** Extract to `private void initDeferredModels()`. The `CompletableFuture`
stays in `start()` but calls the extracted method:

```java
deferredModelInit = CompletableFuture.runAsync(this::initDeferredModels);
```

The extracted method takes no parameters — it reads instance fields (config, lifecycle
managers, signal bus) directly. Same behavior, just moved to its own method.

**Impact:** `start()` drops from 440 to ~280 LOC. `initDeferredModels()` is ~160 LOC.

### KS-2: Extract telemetry gauge registration (~30 LOC)

Lines 398-428: Twelve `telemetry.gauge(...)` calls with lambda suppliers. Pure boilerplate.

**Action:** Extract to `private void registerTelemetryGauges()`. Called from `start()`
after `appServices` is constructed.

**Impact:** -30 LOC from `start()`.

### KS-3: Extract migration initialization (~50 LOC)

Lines 281-357: Blue/Green migration detection, `searchLifecycle`/`ingestLifecycle` setup
based on migration state, schema mismatch auto-migration. This is a cohesive block with
its own branching logic.

**Action:** Extract to `private void initLuceneRuntimes(ResolvedConfig rc, ...)`.
Returns void — sets `searchLifecycle`, `ingestLifecycle`, `buildingIndexPath` as instance
fields (same as current).

**Impact:** -50 LOC from `start()`. Migration logic is isolated and testable in principle.

### KS-4: Extract startup timing into a helper

Lines 191-464: `start()` manually tracks phase timing with `tPrev`/`tPhase` variables
and computes deltas. This is cross-cutting boilerplate.

**Action:** Consider a lightweight `PhaseTimer` helper:

```java
var timer = new PhaseTimer();
// ... do telemetry init ...
timer.mark("telemetry");
// ... do signal bus ...
timer.mark("signalBus");
// at the end:
log.info("Startup phases (ms): {}", timer.summary());
```

~20 LOC helper, eliminates 10+ lines of manual timing in `start()`.

**Impact:** Minor — readability improvement only.

## Execution status

```
Phase 1: IL-1 + IL-4  DONE — deleted processJob (118 LOC) + buildDocument wrapper (24 LOC)
Phase 2: IL-2          DONE — deleted 16 dead reflection shims, callers use IndexingDocumentOps directly
Phase 3: IL-3          DONE — extracted recordSpladeBackfillResult() helper
Phase 4: KS-1          DONE — extracted initDeferredModels() (160 LOC)
Phase 5: KS-2          DONE — extracted registerTelemetryGauges() (30 LOC)
Phase 6: KS-R4        DONE — EmbeddingProvider interface + NoOpEmbeddingProvider (16 files)
Phase 7: KS-R4 fixes  DONE — IndexingLoop split fields, null coercion cleanup, listener type fix
Deferred: KS-3 + KS-4 — migration init extraction + phase timer (cosmetic, start() already 258 LOC)
```

**Note on IL-2:** The "language detection utilities" turned out to already be extracted to
`IndexingDocumentOps`. What remained on IndexingLoop were 16 dead reflection compatibility
shims that delegated to IndexingDocumentOps. All were unused (no test uses reflection to
call them). Deleted all shims, updated the 2 live callers (`normalizeMimeBase`,
`classifyFileKind`) to call `IndexingDocumentOps` directly.

## Verification

After each phase:
```bash
./gradlew.bat spotlessApply :modules:worker-services:test :modules:indexer-worker:test
```

After all phases:
```bash
./gradlew.bat build -x test  # full compilation
./gradlew.bat test -x :modules:system-tests:test  # full test suite
```

## Results

| File | Before | After | Change |
|------|--------|-------|--------|
| `IndexingLoop.java` | 1,527 LOC | 1,277 LOC | -250 LOC (-16%) |
| `KnowledgeServer.java` | 1,411 LOC | 1,439 LOC | +28 LOC (method signatures/docs), but `start()` 440 → 258 LOC |
| `IndexingLoopTest.java` | — | Updated reflection contract test |
| New files | — | None needed (utilities were already on IndexingDocumentOps) |

## Research: best practices analysis (2024–2026 sources)

Deep-dive research into current best practices for each file's core concerns. Seven topics investigated across background loop patterns, GPU arbitration, staged startup, Blue/Green migration, sentinel threads, and late-binding dependencies.

### IndexingLoop findings

**IL-R1: Poll-sleep loop vs BlockingQueue.poll(timeout) — suboptimal but acceptable**

The current `pollPending()` + `Thread.sleep(1000)` pattern adds up to 1s latency before new jobs are noticed. Research recommends `BlockingQueue.poll(timeout, unit)` — wakes instantly on new work OR after timeout. This is the standard pattern for desktop background processing (Jenkov, Baeldung).

However, the job queue is SQLite-backed (`SqliteJobQueue.pollPending()`), not an in-memory `BlockingQueue`. The polling is inherent in the database-backed architecture. To use `poll(timeout)`, you'd need an in-memory notification layer in front of SQLite (e.g., a `LinkedBlockingQueue` that `GrpcIngestService` pushes to when it enqueues a job). This would be a cross-module wiring change, not a simple swap.

**Verdict:** The current pattern is acceptable for a desktop app. The 1s latency floor is imperceptible to users. A BlockingQueue notification layer would be a future optimization for throughput-sensitive scenarios.

Sources: [Jenkov — Java BlockingQueue](https://jenkov.com/tutorials/java-util-concurrent/blockingqueue.html), [Baeldung — Guide to BlockingQueue](https://www.baeldung.com/java-blocking-queue)

**IL-R2: "Breath holding" — validated, correct name mapping**

The pattern is recognized in production:
- Windows Search calls it **"Indexer Backoff"** — reduces indexing speed when user activity is detected within a 4-minute window
- macOS Spotlight uses **QoS-level E-core pinning** — background tasks confined to Efficiency cores, system scheduling handles the rest
- Windows Task Scheduler defines **idle conditions** formally (no keyboard/mouse for 4 min + CPU/disk idle >90%)

Our implementation (check `signalBus.isUserActive()` → sleep 500ms) is the application-level equivalent. The OS-level approaches (thread priority, QoS) are complementary but not available at JVM granularity on Windows.

Sources: [NinjaOne — Indexer Backoff](https://www.ninjaone.com/blog/enable-or-disable-indexer-backoff/), [Eclectic Light — Spotlight Indexing](https://eclecticlight.co/2026/02/10/in-the-background-spotlight-indexing/), [Microsoft — Task Idle Conditions](https://learn.microsoft.com/en-us/windows/win32/taskschd/task-idle-conditions)

**IL-R3: Three-phase batch processing — correct**

Google Cloud ML inference guidance validates batching before inference ("reduces administrative overhead by amortizing admin costs across many elements"). For a desktop app with 16-doc batches where embedding is the bottleneck, three-phase is optimal. Pipelining (extract+embed in parallel) would add complexity without throughput gain at this batch size. Micro-batching is the middle ground for larger scales.

Sources: [Google Cloud — ML Inference in Dataflow](https://cloud.google.com/blog/products/data-analytics/ml-inference-in-dataflow-pipelines), [Manning — Parallel Functional Pipeline Pattern](https://freecontent.manning.com/the-parallel-functional-pipeline-pattern/)

**IL-R4: Sequential backfill orchestration — correct complexity level**

Netflix's Maestro simplification validates sequential chains over DAG executors for linear dependency graphs. Our graph (embedding → chunks → NER → disambiguation + SPLADE independent) is essentially linear. A DAG executor adds topological sort, concurrent tracking, and partial failure handling — none needed for 5 pipelines with clear preconditions.

**When to upgrade:** Only if fan-out/fan-in patterns emerge (one stage feeding multiple parallel downstream stages with a join). Not currently applicable.

Sources: [Netflix — 100x Faster Maestro](https://netflixtechblog.com/100x-faster-how-we-supercharged-netflix-maestros-workflow-engine-028e9637f041), [Medium — DAG Workflow Engine in Java](https://medium.com/@amit.anjani89/building-a-dag-based-workflow-execution-engine-in-java-with-spring-boot-ba4a5376713d)

**IL-R5: SPLADE exponential backoff — correct, missing jitter**

AWS Architecture Blog: "Exponential backoff and jitter are the industry-standard retry pattern." Our implementation has the exponential growth and cap (60s) but lacks jitter. AWS recommends Full Jitter: `sleep = random_between(0, min(cap, base * 2^attempt))`. For a single-client desktop app this is unlikely to cause issues (no thundering herd), but it's a theoretical gap.

Sources: [AWS — Exponential Backoff and Jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/), [AWS Builders Library — Timeouts, Retries and Backoff](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/)

**IL-R6: GPU lifecycle (unload/reload) — correct and industry-standard**

Ollama, LocalAI, and llama.cpp all use the same unload/reload pattern for consumer GPUs. CUDA MPS (the server-side alternative) is Linux-only, inapplicable to Windows desktop. MIG requires data-center GPUs (A100/H100). The edge-detection pattern on a polled boolean is correct when the signal source is itself polled (MMF).

Sources: [Ollama Keep-Alive Memory Management](https://markaicode.com/ollama-keep-alive-memory-management/), [LocalAI VRAM Management](https://localai.io/advanced/vram-management/), [NVIDIA MPS Documentation](https://docs.nvidia.com/deploy/mps/index.html)

### KnowledgeServer findings

**KS-R1: Staged startup with deferred init — correct and production-validated**

Elasticsearch ML model deployment: models have explicit states ("starting" → "started"), server accepts requests immediately. Solr: `loadOnStartup=false` defers core loading. Kubernetes three-probe model (startup → readiness → liveness) maps directly.

**Current gap:** The health endpoint doesn't distinguish startup phases. gRPC Health Checking Protocol supports per-service status — could return `NOT_SERVING` for inference endpoints while models load, `SERVING` for basic search. This would give callers better degradation signals.

Sources: [Kubernetes Probes](https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/), [gRPC Health Checking](https://grpc.io/docs/guides/health-checking/), [Elasticsearch ML Model Deployment](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-ml-start-trained-model-deployment)

**KS-R2: Blue/Green index migration — correct and standard**

Elasticsearch uses alias swap; Solr uses collection aliasing. Both are the same pattern as our two-directory approach. The research confirms: "Lucene's architecture fundamentally requires rebuild for schema changes — per-field data structures are immutable once created."

**Performance note:** Elasticsearch recommends `refresh_interval: -1` during reindex to avoid segment overhead from the long tail. Our Green index runs with normal NRT refresh during migration — a potential optimization for large corpora.

Sources: [Elastic Blog — Changing Mapping with Zero Downtime](https://www.elastic.co/blog/changing-mapping-with-zero-downtime), [SoundCloud — How to Reindex 1B Documents in 1 Hour](https://developers.soundcloud.com/blog/how-to-reindex-1-billion-documents-in-1-hour-at-soundcloud/)

**KS-R3: Sentinel thread — acceptable, minor improvements possible**

Android's `Watchdog.java` validates the consolidated polling sentinel for multiple concerns. Research suggests separating daily cleanup (fundamentally different cadence) to `ScheduledExecutorService.scheduleAtFixedRate()`. For file-based IPC signals, `WatchService` would react faster than 1Hz polling — marginal for most signals but could matter for reload latency (<100ms vs up to 1000ms).

Sources: [Android Watchdog](https://cs.android.com/android/platform/superproject/+/master:/frameworks/base/services/core/java/com/android/server/Watchdog.java), [Baeldung — Java WatchService](https://www.baeldung.com/java-nio2-watchservice)

**KS-R4: Late-binding volatile setters — pragmatic, better alternatives exist**

This is the most significant theoretical gap. The current pattern distributes null-safety across every consumer. Research identifies cleaner alternatives:

| Pattern | How it works | Eliminates null checks? | Complexity |
|---------|-------------|------------------------|------------|
| **Current (volatile setter)** | Services start null, wired later | No — every consumer null-checks | Lowest |
| **Null Object** | Inject `NoOpEmbeddingService` at construction, swap to real later | Yes — callers always get a valid reference | Low |
| **`Supplier<Optional<T>>`** | Encapsulate availability in supplier | Partially — callers get Optional, not null | Low |
| **`CompletableFuture<T>`** | Inject future at construction, `.join()` or `.isDone()` | Yes for blocking callers; `.isDone()` for branching | Medium |
| **Capability model** | Services expose `isEmbeddingAvailable()` | Yes — business-level query instead of null check | Medium |

The Null Object pattern is the simplest improvement: define `NoOpEmbeddingService` that returns empty results, inject at construction, swap via `AtomicReference.set()` when the real model loads. Zero null checks across all consumers. Combined with a capability query (`isEmbeddingAvailable()`), callers that need branching behavior check the capability; callers that want best-effort results call methods directly.

Neither Guice nor Spring solves this out-of-the-box — the constraint (service must serve before dependency exists) isn't standard DI. The volatile setter is pragmatic; the Null Object pattern is the theoretically clean solution.

Sources: [Null Object Pattern — Baeldung](https://www.baeldung.com/java-null-object-pattern), [Guice Provider](https://github.com/google/guice/wiki/injectingproviders), [DI Anti-Patterns — Manning](https://livebook.manning.com/book/dependency-injection-principles-practices-patterns/chapter-5)

### Summary of theoretical improvement opportunities

| # | Area | Current | Best practice | Gap severity |
|---|------|---------|---------------|--------------|
| IL-R1 | Job queue polling | poll + sleep(1000) | `BlockingQueue.poll(timeout)` with notification layer | Low — 1s latency acceptable for desktop |
| IL-R5 | SPLADE backoff | Exponential, no jitter | Add Full Jitter per AWS | Negligible — single client |
| KS-R1 | Health endpoint | Binary (running/not) | Per-service gRPC health status (SERVING/NOT_SERVING) | Medium — improves caller degradation |
| KS-R2 | Migration refresh | Normal NRT during Green build | Disable refresh during reindex | Low — optimization for large corpora |
| KS-R4 | Late-binding deps | Volatile setters + null checks | Null Object pattern + capability model | Medium — cleaner architecture, eliminates distributed null checks |

Everything else (breath holding, three-phase batch, backfill orchestration, GPU lifecycle, Blue/Green migration, sentinel thread, staged startup) is validated as correct by current best practices.

### Confidence evaluation — indirect / long-term benefits

Re-evaluation considering what each change enables, not just what it fixes today:

**IL-R5 (SPLADE jitter) — DROP.** No future scenario where jitter matters for a single-client app.

**IL-R1 (BlockingQueue notification) — DEFER.** Becomes a prerequisite if real-time indexing is ever a goal (file saved → indexed within 100ms). Also decouples the loop from SQLite polling cadence, enabling queue implementation swaps. Not worth implementing today — the 1s latency is imperceptible.

**KS-R2 (Migration refresh disable) — DEFER.** Only matters for large corpora (100K+ docs). Premature optimization today. The optimization is well-documented (ES recommends `refresh_interval: -1` during reindex) and can be added later when corpus sizes grow.

**KS-R1 (Health endpoint phases) — IMPLEMENT (future work).** Progressive readiness improves UX ("search available, embeddings loading...") and scales with model count. The proto already has `embedding_ready` — the gap is the Head consuming it meaningfully. As more models are added (BGE-M3, reranker, future models), phase-aware health becomes more valuable. Not in scope for this tempdoc — belongs in a UI/UX tempdoc.

**KS-R4 (Null Object pattern) — INVESTIGATE for staged implementation.** The long-term case is real:
- **Scalability:** Each new AI capability (summarization, translation, image captioning) adds another `volatile ModelService` + null checks in 3-5 consumer files. With 5 services today, manageable. With 10+, a maintenance burden.
- **Testability:** Testing SearchOrchestrator with embedding disabled requires passing `null` and hoping all null checks are correct. A `NoOpEmbeddingCapability` gives tests a clean, predictable "no embedding" state.
- **Staged approach:** Extract `EmbeddingService` interface first (1 service, most widely consumed), validate the pattern, then apply to other services in future work. This limits blast radius while proving the architecture.

**Critical blocker for KS-R4:** `EmbeddingService` is a `final class` with ~20 public methods, internal state (caches, GPU management, init retry logic), and static factory methods. Extracting a clean interface requires identifying which methods consumers actually use vs. which are internal lifecycle. Investigation completed — see below.

### KS-R4 deep investigation: late-bound service consumer map

Full analysis of all 5 late-bound services, their consumers, and the null-check sites.

**Service summary:**

| Service | Class type | Public methods | Consumer files | Null-check sites | Lifecycle owner |
|---------|-----------|---------------|---------------|------------------|----------------|
| `EmbeddingService` | `final class` | 18 | 7 | 20 | KnowledgeServer + IndexingLoop (GPU reload) |
| `SpladeEncoder` | `final class` | 8 | 3 | 5 | KnowledgeServer |
| `NerService` | `final class` | 3 | 2 | 3 | IndexingLoop (sole closer) |
| `BgeM3Encoder` | `class` | 8 | 3 | 4 | KnowledgeServer |
| `DisambiguationService` | `final class` | 5 | 3 | 3 | KnowledgeServer |
| **Total** | | 42 | — | **35** | |

**Key structural findings:**

1. **Clean lifecycle/operational split.** All 5 services have a clear boundary: lifecycle methods (factory, init, close) are called only from `KnowledgeServer.initDeferredModels()` (or `IndexingLoop` for NER close and embedding GPU reload). All consumer files call only operational methods. This means an interface extraction would only cover operational methods — lifecycle stays on the concrete class.

2. **EmbeddingService dominates.** 20 of 35 null-check sites (57%) are for `EmbeddingService`. It has 7 consumer files vs. 2-3 for each other service. If only one service gets the interface treatment, it should be this one.

3. **Mutual exclusivity simplifies.** `BgeM3Encoder` and `SpladeEncoder` are mutually exclusive — only one is initialized. This means some null checks are inherently "which sparse encoder is active?" decisions, not safety guards. A Null Object wouldn't help here — the branching decision is which *type* is active, not whether *any* service exists.

4. **Supplier pattern already halfway there.** `BackfillContext` records hold `Supplier<EmbeddingService>`, `Supplier<SpladeEncoder>`, etc. — not direct references. The Supplier already encapsulates "might be null." Backfill ops call `.get()` and null-check the result. With a Null Object, the Supplier would always return a non-null value, but the `isAvailable()` check would still be needed to decide whether to actually process.

5. **GPU lifecycle in IndexingLoop is the hard case.** `IndexingLoop.handleGpuStateTransition()` calls `embeddingService.close()`, `EmbeddingService.createWithAutoDiscovery()`, and `newService.initialize()` — all lifecycle methods. This code path manages the concrete `EmbeddingService`, not an abstract capability. The Null Object pattern doesn't simplify this — GPU lifecycle management needs the real class.

**EmbeddingService consumer method map:**

| Consumer | Methods used | Category |
|----------|-------------|----------|
| SearchOrchestrator | `isAvailable()`, `embedQuery()` | Query embedding |
| RagContextOps | `isAvailable()`, `embedQuery()`, `embedDocument()` | Query + doc embedding |
| CitationMatchOps | `isAvailable()`, `embedQuery()`, `embedDocument()` | Query + doc embedding |
| IndexingDocumentOps | `isAvailable()`, `embedDocument()` | Doc embedding |
| GrpcHealthService | `isAvailable()` | Status only |
| LoopPacingPolicy | `isUsingGpu()` | GPU arbitration |
| EmbeddingBackfillOps | `embedDocumentBatch()`, `embedDocument()` | Batch embedding |
| IndexingLoop | `isAvailable()`, `embedDocumentBatch()`, `isUsingGpu()`, `close()`, `initialize()`, `createWithAutoDiscovery()` | All categories |

**Minimal interface for Null Object pattern:**

Only operational methods used by consumers (excluding IndexingLoop lifecycle):

```java
public interface EmbeddingProvider {
    float[] embedDocument(String text);
    float[] embedQuery(String text);
    List<float[]> embedDocumentBatch(List<String> texts);
    int dimension();
    boolean isAvailable();
    boolean isUsingGpu();
}
```

6 methods. The query/status methods (`resolvedBackendId`, `getOrtCudaStatus`, `gpuLayers`, `modelPath`) are only used for diagnostics wiring in KnowledgeServer — not by consumer operational code. They can stay on the concrete class.

**NoOp implementation:**

```java
public final class NoOpEmbeddingProvider implements EmbeddingProvider {
    @Override public float[] embedDocument(String text) { return new float[0]; }
    @Override public float[] embedQuery(String text) { return new float[0]; }
    @Override public List<float[]> embedDocumentBatch(List<String> texts) { return List.of(); }
    @Override public int dimension() { return 0; }
    @Override public boolean isAvailable() { return false; }
    @Override public boolean isUsingGpu() { return false; }
}
```

**What null checks it eliminates:**

Of the 20 EmbeddingService null-check sites:
- **~12 checks become unnecessary** — they guard `embeddingService != null` before calling operational methods. With a NoOp, the call proceeds and `isAvailable()` returns false, causing the same early-return behavior.
- **~5 checks remain needed** — they're in IndexingLoop's GPU lifecycle code which manages the concrete class (close, factory, initialize).
- **~3 checks are in `LoopPacingPolicy.shouldRunBackfill()` and similar** — these test `embeddingService != null || !embeddingService.isUsingGpu()`. With a NoOp, `isUsingGpu()` returns false, which is correct behavior.

**What it doesn't help:**
- BgeM3/SPLADE mutual exclusivity branching (`if (bgeM3Encoder != null) ... else if (spladeEncoder != null)`)
- IndexingLoop GPU lifecycle (needs concrete EmbeddingService for close/init)
- NerService (only 3 null-check sites, 2 consumer files — not worth the interface overhead)
- DisambiguationService (only 3 null-check sites, snapshot supplier already abstracts access)

**Verdict:** The interface extraction is viable and clean for `EmbeddingService` alone. The 6-method `EmbeddingProvider` interface eliminates ~12 null checks across 6 consumer files. IndexingLoop keeps a reference to both `EmbeddingProvider` (for operational use) and `EmbeddingService` (for GPU lifecycle management). The other 4 services don't benefit enough to justify their own interfaces — 2-5 null checks each, with the branching logic being about *which* service is active rather than *whether any* service exists.

### KS-R4 implementation — DONE

Extracted `EmbeddingProvider` interface (6 methods) + `NoOpEmbeddingProvider` singleton. `EmbeddingService implements EmbeddingProvider`. 14 consumer files migrated from `EmbeddingService` to `EmbeddingProvider`:
- `SearchOrchestrator`, `RagContextOps`, `CitationMatchOps` — field + setter + null-check simplification
- `GrpcSearchService` — pass-through setter renamed
- `GrpcHealthService` — constructor param changed
- `IndexingDocumentOps`, `LoopPacingPolicy` — method param changed
- `EmbeddingBackfillOps` — BackfillContext record field changed
- `IndexingLoop` — keeps concrete `EmbeddingService` internally for GPU lifecycle, exposes `setEmbeddingProvider()` externally
- `DefaultWorkerAppServices`, `WorkerAppServices` — wiring method renamed
- `KnowledgeServer`, `DevReloadManager` — caller sites updated

16 files changed, +200 / -111 lines. All tests pass.
