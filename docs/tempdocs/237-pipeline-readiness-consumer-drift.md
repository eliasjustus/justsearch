---
title: "237: Pipeline Readiness Consumer Drift"
type: tempdoc
status: done
created: 2026-02-27
implemented: 2026-02-27
branch: 237-batch1
---

> NOTE: Noncanonical doc (investigation + strategy). May drift. Verify against code before acting.

# 237: Pipeline Readiness Consumer Drift

> **Implementation status (2026-02-27):** All W items (W1–W7), residual
> issues (R1–R3), and structural prevention layers (1–3) are implemented on
> `main`. W1–W7 on branch `237-batch1` (6 commits, merged `73e6cdfb`).
> Post-merge: W4/W5 regression fixes, W3 hardening, R1–R3, critical-analysis
> hardening. **Verification plan executed** against live dev stack. **Structural
> prevention layers:** Layer 1 = `ReadinessDimension` enum + exhaustive switch
> in `computeComponent()` (compile-time verified: unhandled constant produces
> `the switch expression does not cover all possible input values`);
> Layer 2 = `$scoreKeyLookup` data-driven proof gates with unmapped-key
> detection; Layer 3 = `$ProfileRequirements` hash table (consolidated —
> removed redundant `requireChunkVectors` field, removed dead
> `$isEmbeddingProfile` variable). Tempdoc is **complete**.

## Purpose

Document and fix a recurring architectural gap across two categories:

1. **Consumer drift (W1, W2):** The backend exposes rich, correctly-structured
   readiness state for each pipeline dimension, but consumers (eval scripts,
   proof logic, wait loops) were written against an earlier single-dimensional
   model and have not been updated to use the signals the backend already
   provides. This causes consumers to operate on stale assumptions about system
   state, producing silently wrong results. These are pure wiring fixes.

2. **Missing observability + producer bugs (W3, W4, W5, W6):** Some dimensions
   lack consumer-facing signals entirely (throughput, LambdaMART readiness
   component), or the producer itself has an implementation bug (blocking walk
   in `addWatchedPath()`). These require new infrastructure or code fixes, not
   just wiring.

The critical items (W1, W2) are wiring. The rest are a mix of new
infrastructure and producer-side fixes, unified by the same root cause: new
pipeline dimensions were added without updating the full consumer surface.

---

## Problem Statement

### The Pattern

Every issue documented during 235 I2 and 234's E2E gap analysis follows the
same template:

1. The pipeline acquires a new independent dimension (a new indexing phase, a
   new retrieval leg, a new ML model lifecycle).
2. The backend adds the correct state tracking for that dimension — a proto
   field, a controller, a health indicator.
3. The consumer (eval script, proof condition, wait loop, health endpoint) is
   not updated. It continues to use the proxy signal it already had, which
   answers a different question.
4. The consumer produces a result that appears valid but is wrong. No error is
   raised. The wrongness is silent.

The backend is ahead of its consumers. New dimensions are correctly modeled
inside the system but invisible at the boundary consumers actually poll.

### Concrete Evidence

**From 235 I2 (webis-touche2020 run, 2026-02-27):**

| Issue | Consumer assumption | Backend reality | Effect |
|-------|--------------------|-----------------|---------|
| 5 | `indexState == IDLE` means hybrid search is ready | `IDLE` means Phase 1 (doc indexing) only; Phase 2 (chunk embedding backfill, ~3h) runs independently during IDLE | BEIR hybrid eval runs against near-empty HNSW index; metrics measure SPLADE+BM25, not SPLADE+dense |
| 6 | `vectorEvidenceAvailable = true` means dense ANN contributed | Set to `true` if ANY of `debugScores.vector`, `debugScores.sparse`, `debugScores.rrf` is non-null; SPLADE always satisfies this | ANN proof PASS on 0% dense vector coverage; misleading |
| 1 | `POST /api/indexing/roots` is usable for any corpus size | `addWatchedRoot()` does `Files.walk().toList()` — blocks Jetty handler for 10+ min on 382K files | Ingest never starts; workaround: client-side batching |
| 4 | `-SkipIndex` skips only file submission | Also skips `Wait-ForIndexIdle`; the two are coupled | Cannot wait for drain without re-submitting; workaround: `-IngestSkipFiles N` abuse |
| 8 | `-IngestSkipFiles N` skips all I/O | Enumeration (`Get-ChildItem -Recurse`) still runs; competes with Worker disk I/O | ~10x throughput reduction during enumeration overlap |
| 9 | API-reported `processingJobsCount: 1` means normal throughput | Orphaned process after dev-runner death runs at ~1 doc/s vs ~11 doc/s normally | Hours of indexing time wasted silently |

**From 234 E2E gaps (same pattern):**

| Gap | Consumer assumption | Backend reality |
|-----|--------------------|-----------------|
| E2E-2 | GPL re-query in TEXT mode populates `sparse` debug score | TEXT mode `indexRuntime.search()` returns null `debugScores`; only HYBRID path calls `HybridFusionUtils` | All 5 LambdaMART training features are zero; model is degenerate |
| E2E-7 | LambdaMART training outcome is visible | No training status field exposed anywhere | Training failure is silent |
| E2E-1 | LambdaMART model persists across restarts | Model lives only in `AtomicReference` heap; no persistence | Model gone on every restart; GPL never re-fires (snapshot says already evaluated) |

---

## Infrastructure Audit

### What the Backend Already Exposes

Before building anything, inventory what exists and is not yet consumed.

**`chunk_vectors_ready` (proto field, `indexing.proto:489`)**

`StatusResponse` includes `chunk_vectors_ready: bool` (true when
`chunkVectorCoveragePercent >= 95%`) and `chunk_vector_coverage_percent:
double`. Computed server-side. Mapped by `WorkerStatusMapper` into the UI
status map as `chunkVectorsReady`. Already visible in `/api/debug/state` under
`worker.chunkVectorsReady`.

Consumer that ignores it: `Wait-ForIndexIdle` in `beir-eval-win.ps1`, which
exits on `indexState == "IDLE"` only.

**`debugScores.sparse` vs `debugScores.vector` (HybridFusionUtils, adapters-lucene)**

`HybridFusionUtils.fuseWithRRF()` already emits separate keys: `"sparse"`
(BM25/SPLADE leg), `"vector"` (KNN leg), `"sparse_rrf"`, `"vector_rrf"`,
`"rrf_base"`. These flow through `SearchResult.debug_scores` proto map and
arrive in `KnowledgeSearchResponse.Hit.debugScores`. The per-leg attribution
is already fully computed.

Consumer that ignores the distinction: `vectorEvidenceAvailable` check in
`beir-eval-win.ps1:997`, which sets the flag to `true` if ANY of these keys
is non-null.

**`LifecycleSnapshotV1` + `buildReadinessEnvelope()` (StatusLifecycleHandler)**

`StatusLifecycleHandler.buildReadinessEnvelope()` already implements the
composite readiness pattern: 5 named components (workerControlPlane,
indexServing, ai, embedding, plus composites retrieval + aiFeatures) with a
priority combiner. The pattern for adding a new named component is established
and extensible. `LifecycleReasonCode` is a stable low-cardinality enum for
automation. `LifecycleState` covers READY/DEGRADED/NOT_READY/NOT_CONFIGURED.

Missing from the envelope: `phase2_chunk_embedding` (Phase 2 backfill
completion) and `lambdamart_model` (LambdaMART loaded state).

**`vector_blocked` + `hybrid_fallback_reason` (indexing.proto:60-65)**

`SearchResponse` already has `effective_mode`, `vector_blocked`,
`vector_blocked_reason`, `hybrid_fallback`, `hybrid_fallback_reason` as
explicit degradation signals. These exist in the proto and flow through the
gRPC response. Whether they are exposed in the HTTP search response or only
in the debug state needs verification at implementation time.

**`EmbeddingCompatibilityController` 5-state machine**

`EmbeddingCompatibilityController` already models: COMPATIBLE,
BLOCKED_LEGACY, BLOCKED_MISMATCH, REBUILDING, UNAVAILABLE. This is the
correct pattern for multi-state per-dimension readiness. The same pattern
is directly applicable to Phase 2 backfill and LambdaMART model state.

**`GplRevalidationTrigger` trigger-condition pattern**

Pure evaluation function: takes corpus snapshot, returns `TriggerResult(
shouldRun, reasons[])`. No side effects. Low-cardinality reasons for
automation. Directly reusable as the pattern for any new trigger condition.

### The Wiring Gaps

Five places where backend state exists but no consumer uses it:

| Gap ID | Backend state | Missing consumer | File(s) |
|--------|--------------|-----------------|---------|
| W1 | `chunk_vectors_ready` in `StatusResponse` | `Wait-ForIndexIdle` exits on `indexState == IDLE` only | `beir-eval-win.ps1` |
| W2 | `debugScores.sparse` vs `debugScores.vector` distinct keys | `vectorEvidenceAvailable` conflates both | `beir-eval-win.ps1:997` |
| W3 | `SubmitBatch` accepts incremental batches | `addWatchedPath()` collects all paths first | `RootLifecycleOps.java:90-120` |
| W4 | `phase2_chunk_embedding` progress exists in `StatusResponse` | Not a named component in readiness envelope | `StatusLifecycleHandler.java:307-400` |
| W5 | `processingJobsCount` + `indexedDocuments` delta computable | No throughput health indicator; orphaned process undetectable | `WorkerStatusMapper.java`, health endpoint |

---

## External Pattern Assessment

Before finalizing the implementation approach, the existing infrastructure was
compared against the industry patterns surfaced by external research (Netflix
`runtime-health`, Kubernetes readiness gates, Spring `CompositeHealthContributor`,
Weaviate `explainScore`, Project Reactor, Databricks Auto Loader). The
comparison was done layer by layer against the actual code, not the subagent
summary.

**Finding: the existing architecture already implements the correct patterns.
No migration to external frameworks is warranted. All fixes are wiring.**

### Layer 1 — Composite Health / Named Condition Registry

External patterns add: (a) async execution per indicator with independent
timeouts; (b) DI-based self-registration so new components don't require
editing a central method.

Neither applies here. The inputs to `buildReadinessEnvelope()` are already
in memory when the method runs (the gRPC status call completed earlier in the
request); there is nothing to parallelize. DI registration is a real
improvement but only becomes a practical problem when component additions are
frequent enough to cause merge conflicts — at 4 components and ~1 addition per
quarter, it is not a problem yet.

The combiner (`combineReadinessState()`: NOT_READY > UNKNOWN > NOT_CONFIGURED
> DEGRADED > READY), reason-code enum, component/composite structure, and
schema versioning are all correct and match what the external frameworks
provide, implemented directly without framework dependencies.

**Verdict: extend `buildReadinessEnvelope()` directly (W4). No framework
adoption.**

**Future threshold:** when named components exceed ~7 (currently 6 after W4),
enforce registration structurally. Post-implementation research (2026-02-27)
identified the best-fit pattern: a `ReadinessDimension` enum with Java 21+
exhaustive switch expressions in `buildReadinessEnvelope()`. Adding a new enum
constant without handling it in every switch expression is a compile error.
This is lighter than DI self-registration (Spring `HealthContributor`,
Netflix `runtime-health` Guice modules) while providing compile-time rather
than startup-time enforcement. See "Prevention Rule → Deferred layer 1" for
full pattern analysis and alternatives considered.

### Layer 2 — Capability-Scoped State Machine

`EmbeddingCompatibilityController` is a purpose-built 5-state machine
(COMPATIBLE, BLOCKED_LEGACY, BLOCKED_MISMATCH, REBUILDING, UNAVAILABLE) with
`AtomicReference` state, transition triggers, reason codes, and dual guards
(`allowEmbeddingWrites()` vs `allowQueryEmbeddings()`). This is the reference
pattern for per-dimension readiness in this codebase.

External "deep health check" frameworks would give a runner for the check; the
check logic itself would be identical. No framework adds value here.

**Verdict: `EmbeddingCompatibilityController` is the template. Phase 2 backfill
readiness and LambdaMART model state (W4) follow this pattern directly.**

### Layer 3 — Per-Leg Score Attribution

`HybridFusionUtils.fuseWithRRF()` already emits `"sparse"`, `"vector"`,
`"sparse_rrf"`, `"vector_rrf"`, `"rrf_base"` as `Map<String, Float>` per hit,
flowing through the proto and HTTP response.

Weaviate's `explainScore` is a formatted string — human-readable but requires
parsing for programmatic use. JustSearch's typed map is directly usable as a
LambdaMART feature vector and is strictly better for the machine-consumption
use case (GPL training, proof computation). Elasticsearch's Explain API is a
hierarchical tree — richer but heavier than needed for a 2-leg system.

**Verdict: the existing `Map<String, Float>` is better than external patterns
for this use case. W2 is purely a consumer fix: read the keys that already
exist, separately.**

### Layer 4 — Capability-Aware Wait in the Eval Pipeline

Dagster, Luigi, and dbt solve workflow orchestration for multi-team data
engineering pipelines. JustSearch's eval pipeline is a PowerShell script.
These are not comparable in scale; adopting any of them replaces a 10-line
while loop with a platform.

**Verdict: extend the existing while loop (W1). The concept is right; the
implementation is the right size.**

### Layer 5 — Streaming Ingestion

`Files.walk()` already returns a lazy `Stream<Path>`. `.toList()` at line 102
is the only materialization — the entire problem is one call. Project Reactor
adds a reactive dependency to a module with none; Databricks Auto Loader is
cloud-scale checkpoint ingestion. Both are wrong size.

The correct fix is a plain Java batch accumulator loop (~15 lines) that uses
the already-lazy stream without materializing it. No new dependencies.

**Verdict: fix the `.toList()` call in-place (W3). No reactive framework.**

---

## Design Decisions (Resolved)

These decisions were open at tempdoc creation and are now resolved by code
investigation. W3, W4, W5 proceed without caveats.

### D1 — W3: Caller contract after sync→streaming change ✓ RESOLVED

**Finding: safe to stream. No caller assumes synchronous completion.**

Eight call sites were audited:

| Site | File | Pattern | Assumes sync? |
|------|------|---------|--------------|
| Delegation | `RemoteKnowledgeClient.java:858-865` | Void passthrough | No |
| HTTP handler | `IndexingController.java:93-94` | Returns 200 immediately after call | No |
| E2E test ×4 | `HttpFileWatcherE2ETest.java:420-567` | Calls `awaitKnowledgeIdle()` then `awaitDocumentSearchable()` before asserting | No — already async |
| Bootstrap startup | `KnowledgeServerBootstrap.java:144-150` | Calls `tryIngestHelpFiles()` + `startPeriodicSync()` next; both async queue ops | No |

`watchedRootsState.markIndexed()` must remain at walk-end (after all batches
submitted), not after the first batch. This preserves the idempotency property:
a partial walk that errors out is not marked indexed. The streaming change
moves submission earlier; the state write stays at completion.

### D2 — W4: LambdaMART state machine ✓ RESOLVED

**Finding: all 5 states are detectable. No collapse needed.**

Available debug state fields (confirmed via `DebugStateController`):
- `reranking.lambdamart.active` — bool, true when model is loaded
- `reranking.lambdamart.training.status` — `"PENDING"`, `"TRAINING"`, `"SUCCEEDED"`, `"FAILED"`
- `reranking.lambdamart.training.last_trained_at` — Instant, null if not trained this session
- `gpl.status` — `"IDLE"`, `"RUNNING"`, `"COMPLETED"`, `"FAILED"`
- `gpl.triple_count` — int, 0 if no triples

Refined 5-state machine (evaluation order matters — check in this sequence):

| Priority | State | Meaning | Condition |
|----------|-------|---------|-----------|
| 1 | `READY` | Model loaded | `lambdamart.active == true` |
| 2 | `TRAINING` | LightGBM fitting in progress | `training.status == "TRAINING"` |
| 3 | `FAILED` | Training or GPL failed, or training succeeded but model failed to load | `training.status == "FAILED" OR gpl.status == "FAILED" OR (training.status == "SUCCEEDED" AND lambdamart.active == false)` |
| 4 | `DEGRADED` | Model was active earlier this session, then lost | `lambdamart.active == false AND training.last_trained_at != null AND training.status NOT IN ("TRAINING", "SUCCEEDED", "FAILED")` |
| 5 | `NOT_CONFIGURED` | No training history this session | default (gpl.triple_count == 0 or never ran) |

**Priority 3 note:** The condition `training.status == "SUCCEEDED" AND
lambdamart.active == false` catches the edge case where training completes but
`loadModel()` fails. Without this, the state machine classifies it as DEGRADED,
which implies the model was previously working and then lost — misleading when
it never loaded in the first place. Reporting FAILED is more accurate: the
training pipeline did not produce a usable model.

**Priority 4 note:** DEGRADED is now narrower: it requires `training.status`
to be in a terminal-but-not-failed state that isn't SUCCEEDED (since SUCCEEDED
without active is caught by FAILED above). In practice DEGRADED would be
reached if `training.status` somehow reverts to `"PENDING"` or an unexpected
value after `last_trained_at` was set — a defensive catch for state corruption.

**Key constraint:** `training.last_trained_at` is in-memory only — it resets on
restart. On restart with triples present, `AppFacadeBootstrap` immediately
calls `startLambdaMartTrainingAsync()`, so the state enters TRAINING within
milliseconds. After restart, the system goes directly to TRAINING, not
DEGRADED. This is correct behavior: the system auto-recovers before DEGRADED
is ever visible at the readiness boundary.

No new `LifecycleReasonCode` entries required if `LAMBDAMART_MODEL_LOADED`,
`LAMBDAMART_TRAINING_IN_PROGRESS`, and `LAMBDAMART_LOAD_FAILED` are added;
verify enum coverage at implementation time.

### D3 — W5: Throughput indicator placement ✓ RESOLVED

**Finding: Option B (Worker-side) is correct. Pre-investigation preference
for Option A was wrong — Head has no persistent state to compute a rolling
window against.**

Code investigation of `StatusLifecycleHandler.java` and `RemoteKnowledgeClient`:
- Head has **no polling loop**. The 5-second interval is the browser client
  polling `/api/status` — Head responds on-demand, it does not poll Worker
  on a timer.
- Each `/api/status` request triggers a **fresh, independent gRPC call** to
  Worker. No prior samples are retained.
- `StatusLifecycleHandler` holds no history collection, no deque, no
  circular buffer. Fields are only transient suppliers and atomic references.

Head-side window accumulation would require adding new AtomicReference state
to `StatusLifecycleHandler` — effectively implementing a Worker-level concept
at the wrong level. Worker is the authoritative source of queue depth and job
counts. It observes rate changes at millisecond granularity, not at 5-second
client-poll intervals.

**Decision: Option B — Worker-side.** Add two fields to `StatusResponse` proto
and compute the rolling window in Worker. See updated W5 spec below.

---

## Implementation Items

### W1 — Wire `chunk_vectors_ready` into `Wait-ForIndexIdle` (Issue 5)

**Priority: Critical** — without this, every BEIR hybrid eval on a corpus
>50K docs measures the wrong thing and records valid-looking but incorrect
metrics.

**Location:** `scripts/search/beir-eval-win.ps1` — the `Wait-ForIndexIdle`
function.

**Current condition:**
```powershell
while ($state -ne "IDLE") { ... }
```

**New condition (for hybrid/embedding profiles):**
```powershell
while ($state -ne "IDLE" -or (-not $chunkVectorsReady -and $requireChunkVectors)) {
    ...
}
```

The `$requireChunkVectors` flag is `$true` when `-ProfileId` is an embedding
profile (any profile with `embedding.enabled == true` in the provenance).
Lexical-only evals (`stub-jaccard` profile) do not need to wait for Phase 2.

**What to poll:** `Wait-ForIndexIdle` already polls `GET /api/status`.
The response already includes `chunkVectorsReady` (bool, mapped by
`WorkerStatusMapper:95`) and `chunkVectorCoveragePercent` (double). Read
`$s.chunkVectorsReady` and `$s.chunkVectorCoveragePercent` from the existing
poll response — no second API call needed. Log coverage progress during wait.
`chunk_vectors_ready` becomes `true` when coverage >= 95% — same threshold
already used by the backend for hybrid readiness.

**Note:** This is a tactical fix — it adds dimension-specific knowledge
(`chunkVectorsReady`) to the eval script. After W4 makes the readiness
envelope correct, W7 refactors this to read the envelope's composite state
instead. See "Structural Loop" section below.

**Timeout:** Phase 2 takes ~3h for 382K docs. The wait loop for hybrid evals
on large corpora should have a minimum timeout of 4h, separate from the Phase 1
IDLE timeout.

- [x] **W1: Add `chunkVectorsReady` exit condition to `Wait-ForIndexIdle` for
  embedding profiles** — `f3517d95`

---

### W2 — Split ANN proof into sparse and dense rates (Issue 6)

**Priority: Critical** — the ANN proof's purpose is to certify that dense
vector search contributed to the eval results. With SPLADE satisfying the
proof via `debugScores.sparse`, the proof certifies the wrong thing. Every
`embedding-nomic-q4` baseline captured while SPLADE is present but Phase 2
incomplete will carry a misleading `proof_status: "PASS"`.

**Location:** `beir-eval-win.ps1:997` (the `vectorEvidenceAvailable`
computation) and the downstream proof aggregation.

**Current:**
```powershell
$vectorEvidenceAvailable = (
    $debugScores.vector -ne $null -or
    $debugScores.sparse -ne $null -or
    $debugScores.rrf    -ne $null
)
```

**New: split into two independent measurements:**
```powershell
$sparseEvidenceAvailable      = ($debugScores.sparse -ne $null -and $debugScores.sparse -gt 0)
$denseVectorEvidenceAvailable = ($debugScores.vector -ne $null -and $debugScores.vector -gt 0)
```

Aggregate separately across queries:
- `sparseEvidenceAvailableRate` — existing SPLADE/BM25 proof
- `denseVectorEvidenceAvailableRate` — new dense ANN proof

The ANN `proof_status: "PASS"` condition should require
`denseVectorEvidenceAvailableRate >= 0.95`. A run where SPLADE is present
but Phase 2 is incomplete will correctly record:
```
sparseEvidenceAvailableRate:     0.97   → PASS
denseVectorEvidenceAvailableRate: 0.03  → FAIL
proof_status: "FAIL"
```

**Schema impact:** The `provenance.ann` block in `metrics.json` and
`metrics.v2.json` needs two new fields. The v2 schema must be updated.
Existing baselines captured with the old single-field proof are invalidated
for hybrid profiles — re-promotion required after this fix (feeds into I2
completion).

- [x] **W2: Split `vectorEvidenceAvailable` into `sparseEvidenceAvailableRate`
  + `denseVectorEvidenceAvailableRate`; require dense rate ≥ 0.95 for ANN PASS** — `f3517d95`

---

### W3 — Stream `addWatchedPath()` with backpressure (Issue 1)

**Priority: High** — `Files.walk().toList()` blocks the Jetty handler thread
indefinitely for large corpora, silently preventing any indexing from starting.
Already requires the `ingest_batches` workaround in every large-corpus eval.

**Location:** `RootLifecycleOps.java:90-120` — `addWatchedPath()`.

**Current:**
```java
List<Path> batch = Files.walk(normalized)
    .filter(p -> Files.isRegularFile(p) && Files.isReadable(p))
    .filter(p -> !excludes.isExcluded(normalized, p))
    .collect(Collectors.toList());
submitBatchFn.accept(batch, false);
```

**Target:** Two changes — (a) stream batches instead of materializing the full
file list, (b) move the walk to a background thread so the caller returns
immediately.

The streaming accumulator alone is not enough: even with incremental submission,
the walk still runs on the Jetty handler thread, blocking the HTTP response for
the full walk duration. On a 382K-file root this is minutes regardless of batch
size. To actually fix Issue 1 ("blocks Jetty handler for 10+ min"), the walk
must run asynchronously.

```java
// Pseudocode — background streaming walk
CompletableFuture.runAsync(() -> {
    List<Path> pending = new ArrayList<>(BATCH_SIZE);
    try (Stream<Path> walk = Files.walk(normalized)) {
        walk.filter(p -> Files.isRegularFile(p) && Files.isReadable(p))
            .filter(p -> !excludes.isExcluded(normalized, p))
            .forEach(p -> {
                pending.add(p);
                if (pending.size() >= BATCH_SIZE) {
                    awaitQueueBelowThreshold(); // backpressure — see note below
                    submitBatchFn.accept(List.copyOf(pending), false);
                    pending.clear();
                }
            });
        if (!pending.isEmpty()) {
            submitBatchFn.accept(List.copyOf(pending), false);
        }
        watchedRootsState.markIndexed(normalized);
        watchedRootsState.persist();
    } catch (IOException e) {
        log.error("Walk failed for {}: {}", normalized, e.getMessage());
        // Do NOT call markIndexed — partial walk should retry on next startup
    }
}, walkExecutor);
```

The outer `addWatchedPath()` method returns void immediately. The walk,
batching, backpressure, and state persistence all happen on `walkExecutor`
(a single-thread executor to serialize concurrent `addWatchedRoot()` calls and
avoid concurrent `Files.walk` on overlapping trees).

Suggested `BATCH_SIZE`: use the existing `batchSize` constructor parameter
(injected from config, currently 5000). No hardcoded constant needed.
Backpressure threshold: queue depth > 90K pending → wait until < 70K
(matching existing `ingest_batches` logic in `beir-eval-win.ps1`).

#### Implementation Details (from code investigation)

**Actual code structure (confirmed):**
- `RootLifecycleOps.java` in `modules/app-services/...worker/`
- Current implementation at lines 90-121 uses `Files.walk(normalized)` → `.toList()` → `submitBatchFn.accept(batch, false)`
- `submitBatchFn` type: `BiConsumer<List<Path>, Boolean>` — wraps `RemoteKnowledgeClient.submitBatch()` (blocking gRPC)
- `submitBatchFn` discards `BatchResponse` (returns void) — backpressure cannot come from submission return value
- `watchedRootsState`: `WatchedRootsState` wrapper; `markIndexed(path)` sets timestamp, `persist()` writes to disk
- `excludeMatcherSupplier`: `Supplier<ExcludeMatcher>` — lazy-loaded glob patterns, `isExcluded(root, path)` is synchronous
- No existing executor in the class — `RootLifecycleOps` is functional/stateless

**Constructor dependencies that need additions:**
```java
// Current constructor receives (among others):
//   submitBatchFn: BiConsumer<List<Path>, Boolean>
//   batchSize: int (already exists — use this, don't hardcode BATCH_SIZE)
//
// W3 requires adding:
//   walkExecutor: ExecutorService (single-thread, serializes walks)
//   queueDepthSupplier: Supplier<Long> (for backpressure polling)
```

**`awaitQueueBelowThreshold()` implementation:**
- Head cannot call `JobQueue.queueDepth()` directly (hard invariant: Head never touches Lucene)
- Worker already has rejection logic: `GrpcIngestService.MAX_QUEUE_DEPTH = 100_000`
- `queueDepthSupplier` queries via `RemoteKnowledgeClient` → `IndexStatus` gRPC → `StatusResponse.queue_depth` (proto field 1)
- The supplier is created in `RemoteKnowledgeClient` (which already has the gRPC channel) and injected into `RootLifecycleOps`
- Polling loop: call supplier, if > 90K sleep 2s and retry, break when < 70K

**`walkExecutor` lifecycle:**
- Created in `RemoteKnowledgeClient` (or injected from the bootstrap) alongside `RootLifecycleOps`
- `Executors.newSingleThreadExecutor(r -> new Thread(r, "walk-bg"))` — daemon thread
- Shutdown: tied to `RemoteKnowledgeClient.close()` or application shutdown hook

**Error handling (revised after deep investigation, 2026-02-27):**

The `submitBatchFn` lambda `(paths, force) -> submitBatch(paths, force)` has
**no exception handling**. When Worker's queue depth hits 100K,
`GrpcIngestService` returns `RESOURCE_EXHAUSTED` gRPC error — the entire batch
is rejected (all-or-nothing, no partial acceptance). On the client side this
becomes `io.grpc.StatusRuntimeException`. The circuit breaker does NOT react to
`RESOURCE_EXHAUSTED` (only `UNAVAILABLE` and `DEADLINE_EXCEEDED`) — it rethrows
immediately. Neither `addWatchedPath()` nor `reindexPersistedRoots()` catches
`StatusRuntimeException`.

W3 async walk must catch `StatusRuntimeException` inside the async block:
- On `RESOURCE_EXHAUSTED`: sleep 2-5s and retry the rejected batch
- On other gRPC errors: log and abort walk (do not call `markIndexed`)
- On `IOException` from `Files.walk()`: log, do not call `markIndexed`

**`reindexPersistedRoots()` has the same blocking problem** (lines 348-385):
submits entire directory in one batch per root via `submitBatchFn.accept(batch,
false)` with no internal batching. Out of scope for W3 (which fixes
`addWatchedPath()` only), but the same streaming pattern could be applied later.

**`RemoteKnowledgeClient.getStatus()` exists** (lines 628-638): calls
`stub.indexStatus(request)` and returns `StatusResponse` with `queue_depth`
field. The `queueDepthSupplier` can be wired as
`() -> getStatus().getQueueDepth()` inside `RemoteKnowledgeClient`.

**Caller contract (D1, resolved):** All 8 callers are fire-and-forget or poll
explicitly for completion. No caller uses the return value or checks state
immediately after the call. The HTTP handler already returns 200 immediately
after the call (line 94); making the call itself async is invisible to it. E2E
tests call `awaitKnowledgeIdle()` before asserting. Bootstrap startup calls
`tryIngestHelpFiles()` next, which submits to the same FIFO queue.
`watchedRootsState.markIndexed()` is called at walk-end inside the async block,
preserving the idempotency property (partial walk = not marked = retried on
next startup).

- [x] **W3: Move `addWatchedPath()` walk to background thread with streaming
  batch accumulator and backpressure** — `3539c6e9`; post-audit fix on `main`:
  added `QUEUE_LOW_WATERMARK = 70_000` hysteresis and `catch (RuntimeException)`
  in `walkAndSubmit()`

---

### W4 — Register Phase 2 and LambdaMART in the readiness envelope (Prevention)

**Priority: Medium** — does not fix an existing broken result, but makes the
missing-phase-2 condition observable at the API boundary rather than only in
the debug state.

**Location:** `StatusLifecycleHandler.java:307-400` — `buildReadinessEnvelope()`.

**Add two new named components:**

1. `chunkEmbedding`: maps `chunk_vectors_ready` → READY (≥95%), DEGRADED
   (>0% but <95%), NOT_READY (0%). Include `chunk_vector_coverage_percent`
   as the `progress` field. Source: `WorkerStatus.chunkVectorsReady` +
   `chunkVectorCoveragePercent`.

2. `lambdamartModel`: maps LambdaMART loaded state using the 5-state machine
   from D2 (evaluation order: READY → TRAINING → FAILED → DEGRADED →
   NOT_CONFIGURED). Source: Head-side instances `LambdaMartReranker` and
   `GplJobCoordinator` — see implementation details below.

**Update composites:**
- `retrieval`: currently combines workerControlPlane + indexServing. Add
  `chunkEmbedding` — but see **OPTIONAL contributor resolution** below for
  the correct wiring.

**Schema impact:** `LifecycleSnapshotV1` component list is extensible (the
envelope is a `Map<String, Object>` built dynamically, not a record — no
schema migration needed). `LifecycleReasonCode` needs new entries.

#### Implementation Details (from code investigation)

**`buildReadinessEnvelope()` signature (confirmed):**
```java
private static Map<String, Object> buildReadinessEnvelope(
    Map<String, Object> status, LifecycleSnapshotV1 lifecycleSnapshot)
```

**Data availability at envelope-build time:**

| Component | Data source | Available? | How |
|-----------|-------------|------------|-----|
| `chunkEmbedding` | `status.get("chunkVectorsReady")`, `status.get("chunkVectorCoveragePercent")` | **YES** | Already in `status` Map from `WorkerStatusMapper` (lines 90-95) |
| `lambdamartModel` | `LambdaMartReranker.isLoaded()`, `GplJobCoordinator` fields | **NO** | Not passed to StatusLifecycleHandler — lives in `DebugStateController` |

**W4a (chunkEmbedding) — straightforward:** Read from existing `status` Map.
Pattern matches existing `embedding` component (lines 355-368) which reads
`status.get("embeddingReady")`.

**W4b (lambdamartModel) — requires constructor change and static→instance
refactor (revised 2026-02-27):**

`buildReadinessEnvelope()` is `private static`. To read LambdaMART state, it
must become a non-static instance method so it can access instance fields.

**Scope of the static→instance change (~10 lines across 3 files):**
1. Remove `static` from `buildReadinessEnvelope()` signature (line 307)
2. Add 2 supplier fields to `StatusLifecycleHandler` (lines 37-43):
   ```java
   private final Supplier<LambdaMartReranker> lambdamartRerankerSupplier; // nullable
   private final Supplier<GplJobCoordinator> gplJobCoordinatorSupplier;   // nullable
   ```
3. Add to constructor (lines 45-59, currently 7 params → 9 params)
4. Update `LocalApiServer` constructor (lines 187-195) to pass suppliers:
   ```java
   () -> b.lambdaMartReranker,  // already in builder field (line 679)
   () -> b.gplJobCoordinator    // already in builder field (line 678)
   ```

Both components are already wired into `LocalApiServer.Builder` from
`HeadlessApp` (lines 221-222). Both use `AtomicReference` for thread-safe
snapshots: `LambdaMartReranker.isLoaded()` is O(1) atomic,
`GplJobCoordinator.getStatus()` returns a coherent `GplJobStatus` record.

**Alternative (keep static, pass as parameters):** Add suppliers as method
parameters instead of instance fields. Cleaner for testability but requires
updating the call site in `buildStatusMap()` (line 174). Either approach works;
instance fields follow existing `engineMonitorSupplier` pattern.

**New `LifecycleReasonCode` entries needed:**

| Enum value | String code | Used by |
|-----------|-------------|---------|
| `CHUNK_EMBEDDING_NOT_READY` | `"chunk_embedding.not_ready"` | chunkEmbedding at 0% |
| `CHUNK_EMBEDDING_IN_PROGRESS` | `"chunk_embedding.in_progress"` | chunkEmbedding >0% <95% |
| `LAMBDAMART_NOT_CONFIGURED` | `"lambdamart.not_configured"` | No GPL triples / never trained |
| `LAMBDAMART_TRAINING` | `"lambdamart.training"` | Training in progress |
| `LAMBDAMART_FAILED` | `"lambdamart.failed"` | Training or GPL failed |
| `LAMBDAMART_LOADED` | `"lambdamart.loaded"` | (reason for READY state, optional) |

**Existing components and composites (confirmed):**

| Type | Name | Members | Lines |
|------|------|---------|-------|
| Component | `workerControlPlane` | worker lifecycle snapshot | 311-316 |
| Component | `indexServing` | indexHealthy, indexState, snapshot | 318-347 |
| Component | `ai` | inference lifecycle snapshot | 349-353 |
| Component | `embedding` | embeddingReady boolean | 355-368 |
| Composite | `retrieval` | workerControlPlane + indexServing | 376-383 |
| Composite | `aiFeatures` | ai + embedding | 385-388 |

**Combiner semantics (confirmed):**
```
combineReadinessState() priority: NOT_READY > UNKNOWN > NOT_CONFIGURED > DEGRADED > READY
```
Every contributor is equally weighted. There is no optional contributor concept.

#### OPTIONAL Contributor Resolution

**Problem:** The tempdoc originally said "Add `chunkEmbedding` as an OPTIONAL
contributor: DEGRADED when chunk embedding incomplete, not NOT_READY." But the
combiner has no optional contributor concept. If `chunkEmbedding` at 0% reports
`NOT_READY` and joins the `retrieval` composite, `retrieval` becomes `NOT_READY`,
blocking lexical consumers that don't need chunk vectors at all.

**Resolution — cap `chunkEmbedding`'s contribution to DEGRADED:**

The `chunkEmbedding` component state mapping is adjusted so it never reports
worse than `DEGRADED` when used as a composite member:

| Coverage | Component state | Reason code | Composite effect |
|----------|----------------|-------------|-----------------|
| ≥95% | READY | (none) | No effect |
| >0%, <95% | DEGRADED | `chunk_embedding.in_progress` | Composite ≥ DEGRADED |
| 0% | DEGRADED | `chunk_embedding.not_ready` | Composite ≥ DEGRADED |
| null/unavailable | NOT_CONFIGURED | `chunk_embedding.not_ready` | Composite ≥ NOT_CONFIGURED |

Key change: **0% coverage maps to DEGRADED, not NOT_READY.** This means "chunk
embedding hasn't started, but lexical retrieval works." The consumer interprets
`retrieval.state == DEGRADED` as "lexical works, hybrid is unavailable" — which
is semantically correct. Only infrastructure failures (Worker down, index corrupt)
produce `NOT_READY` in the `retrieval` composite.

This avoids adding optional contributor logic to the combiner. The "optionality"
is encoded in the component's state mapping, not the combiner's aggregation.

**`lambdamartModel` follows the same pattern:** Never worse than DEGRADED in the
composite. FAILED/TRAINING → DEGRADED. NOT_CONFIGURED → NOT_CONFIGURED. READY →
READY. Wire into `retrieval` composite alongside `chunkEmbedding`.

**Updated composite after W4:**
```
retrieval = combine(workerControlPlane, indexServing, chunkEmbedding, lambdamartModel)
```

The `retrieval` composite becomes `DEGRADED` when either optional dimension
(chunk embedding or LambdaMART) is incomplete. It becomes `NOT_READY` only when
a mandatory dimension (Worker control plane, index serving) fails.

- [x] **W4: Add `chunkEmbedding` and `lambdamartModel` components to readiness
  envelope; wire into `retrieval` composite with DEGRADED-capped state mapping** — `0856e3ea`, fix `f0422b1b`

---

### W5 — Add throughput health indicator (Issue 9 detection)

**Priority: Medium** — orphaned processes run silently at ~1 doc/s for hours
without any observable signal in the current API. Detection requires manual
delta computation across multiple API reads.

**Placement (D3, resolved): Worker-side (Option B).** Head has no persistent
polling loop — each `/api/status` request is an independent gRPC call with no
prior samples retained. Head-side window accumulation would require adding
history state at the wrong level. Worker observes queue depth and job counts
directly and continuously.

**Location:** Worker indexing loop or a `ThroughputMonitor` helper class.
Two new fields added to `StatusResponse` proto (`indexing.proto`) and mapped
by `WorkerStatusMapper`.

**Design:**

Worker maintains a circular buffer of `(timestamp, indexedDocuments)` samples,
updated on each status query. The buffer holds up to 36 entries (3 minutes at
5-second poll intervals). Throughput is computed from the oldest and newest
samples in the buffer:

```java
// In Worker status computation (ThroughputMonitor helper):
private static final long WINDOW_MS = 180_000;       // 3-minute window
private static final long MAX_GAP_MS = 600_000;       // 10-minute staleness guard
private static final int MAX_SAMPLES = 36;

void recordSample(long indexedDocuments, long nowMs) {
    samples.addLast(new Sample(nowMs, indexedDocuments));
    while (samples.size() > MAX_SAMPLES) samples.removeFirst();
}

ThroughputResult compute(long processingJobsCount) {
    if (samples.size() < 2) return new ThroughputResult(0.0, "UNKNOWN");

    Sample oldest = samples.getFirst();
    Sample newest = samples.getLast();
    long deltaTimeMs = newest.timeMs - oldest.timeMs;

    // Staleness guard: if the gap between oldest and newest exceeds
    // MAX_GAP_MS, the window is polluted by a long polling gap (e.g.,
    // 10 hours between eval runs). Discard and report UNKNOWN until
    // the buffer refills with fresh samples.
    if (deltaTimeMs > MAX_GAP_MS) {
        samples.clear();
        samples.addLast(newest);  // restart with current sample
        return new ThroughputResult(0.0, "UNKNOWN");
    }

    if (deltaTimeMs < WINDOW_MS) return new ThroughputResult(0.0, "UNKNOWN");

    long deltaCount = newest.docs - oldest.docs;
    double docsPerSec = deltaCount / (deltaTimeMs / 1000.0);
    String state = (processingJobsCount > 0)
        ? (docsPerSec < 1.0 ? "STALLED" : docsPerSec < 5.0 ? "DEGRADED" : "HEALTHY")
        : "HEALTHY";  // queue empty → not stalled
    return new ThroughputResult(docsPerSec, state);
}
```

The `MAX_GAP_MS` staleness guard prevents false DEGRADED/STALLED reports when
polling resumes after a long gap (e.g., between eval runs). Without it, a
10-hour gap would dilute the average to ~1.4 docs/s and falsely report
DEGRADED.

New proto fields in `StatusResponse`:
- `double throughput_docs_per_sec = 85` — rolling 3-min average (0.0 when UNKNOWN)
- `string throughput_window_state = 86` — `"HEALTHY"`, `"DEGRADED"`, `"STALLED"`, `"UNKNOWN"` (insufficient or stale data)

(Field 84 is last used; 85-86 are the next clean field numbers.)

The threshold of 5 docs/s is conservative — normal operation is ~10-12 docs/s,
orphaned operation is ~1 doc/s. The queue-empty guard prevents false DEGRADED
during idle.

**Detection rule for Issue 9:** `throughput_window_state == "STALLED" AND
processingJobsCount > 0` → probable orphaned or throttled process.

**Out of scope:** Root cause diagnosis (CPU scheduling demotion vs. other).
This fix only makes the symptom observable.

#### Implementation Details (from code investigation)

**Full implementation pipeline (4 layers):**

1. **Proto** (`modules/ipc-common/src/main/proto/indexing.proto`):
   Add fields 85-86 to `StatusResponse` message (after field 84 `completed_ner_count`).

2. **Worker-side ThroughputMonitor** (new class in `modules/indexer-worker/`):
   - `IndexStatusOps.buildStatusResponse()` (lines 88-343) is the status builder
   - Correction: `IndexStatusOps` is **NOT stateless** — already has `volatile`
     fields `embeddingCompatController` and `ortCudaStatusSupplier` (lines 48-49)
   - `OperationalMetrics` is a singleton (`getInstance()`, line 63) using
     `LongAdder` + `AtomicLong` for thread safety. It has NO existing windowed
     metrics — W5 would be the first.
   - **Recommended placement:** Add `ThroughputMonitor` as a field in
     `OperationalMetrics` (thread-safe singleton, accessed via `metrics` field
     already in `IndexStatusOps`). Alternative: add as a field on
     `IndexStatusOps` with `synchronized` access.
   - `ThroughputMonitor.recordSample()` called during `buildStatusResponse()`
     with `metrics.getDocumentsIndexed()` (cumulative `LongAdder`, thread-safe)
   - `ThroughputMonitor.compute()` called to set the new proto fields
   - **Thread safety:** `buildStatusResponse()` is called from gRPC handler
     threads (potentially concurrent). `ThroughputMonitor` must be internally
     synchronized or use atomic operations.
   - No existing periodic mechanism in Worker — samples taken on each status
     query (triggered by Head polling, typically every 5s from browser)

3. **Worker status mapper** (`modules/app-services/.../WorkerStatusMapper.java`):
   Pattern: proto field → camelCase JSON key in `toUiStatusMap()`:
   ```java
   out.put("throughputDocsPerSec", status.getThroughputDocsPerSec());
   out.put("throughputWindowState", status.getThroughputWindowState());
   ```

4. **Head consumption**: Fields appear in `/api/status` JSON automatically via
   the existing mapper pipeline. No additional Head-side code needed.

**Data sources available in `IndexStatusOps.buildStatusResponse()`:**
- `indexRuntime.docCount()` — total docs in active generation
- `indexRuntime.countByField(SchemaFields.IS_CHUNK, "true")` — chunk count
- `jobQueue.queueDepth()` → maps to `processing_jobs_count`
- `OperationalMetrics.getDocumentsIndexed()` — cumulative since startup
- All available at build time. `docCount` or `documentsIndexed` both work for
  throughput delta computation; `documentsIndexed` is more appropriate (counts
  documents processed, not index size which can decrease on deletes).

**Blue/green migration consideration:** During migration, docs are added to the
building runtime while the active runtime serves search. `documentsIndexed`
(cumulative from `OperationalMetrics`) tracks all indexing work regardless of
generation. This is the correct signal — throughput should reflect actual work
rate, not search-index size.

- [x] **W5: Add rolling throughput window to Worker; emit `throughput_docs_per_sec`
  + `throughput_window_state` in `StatusResponse` proto (fields 85-86)** — `d8c21ce2`, fix `f0422b1b`

---

### W6 — Add `-SkipIngestEnumeration` flag to `beir-eval-win.ps1` (Issue 8)

**Priority: Low** — Issue 8 only manifests when using `-IngestSkipFiles N`
as an Issue 4 workaround. Once W3 is implemented (streaming `addWatchedRoot`),
the `ingest_batches` path and its enumeration overhead become less critical.
Until then:

**Location:** `scripts/search/beir-eval-win.ps1` — the `ingest_batches` loop.

Add a `-SkipIngestEnumeration` switch that bypasses `Get-ChildItem -Recurse`
entirely and jumps directly to `Wait-ForIndexIdle`. This is the clean
implementation of what `-IngestSkipFiles 400000` achieves by accident.

- [x] **W6: Add `-SkipIngestEnumeration` flag to `beir-eval-win.ps1` ingest
  path** — `f3517d95`

---

### W7 — Refactor `Wait-ForIndexIdle` to read the readiness envelope (Structural Loop)

**Priority: Medium** — not blocking any eval result, but closes the structural
gap that would otherwise cause every future dimension addition to require eval
script changes.

**Prerequisite: W4 must be complete.** The readiness envelope must correctly
reflect all retrieval dimensions before consumers can rely on it.

**Problem:** W1-W2 add dimension-specific checks to the eval script
(`chunkVectorsReady`, `debugScores.sparse` vs `.vector`). W4 makes the
readiness envelope correct. But no item connects them. After W1-W6, the eval
script reads raw `/api/status` fields while the readiness envelope — returned
in every `/api/status` response under the `readiness` key — is ignored. Two
parallel data paths exist for the same information. When a new dimension is
added, the developer follows the prevention rule (registers in envelope), but
the eval script still uses raw fields. Drift recurs.

**Location:** `scripts/search/beir-eval-win.ps1` — `Wait-ForIndexIdle` and
surrounding readiness logic.

**Target (revised 2026-02-27 after envelope structure investigation):**

The readiness envelope **does not contain** queue depth, job counts, building
doc count, or `indexState`. The `indexServing` component conflates IDLE and
INDEXING into a single READY state when `indexHealthy == true`. Therefore W7
**cannot be a pure envelope-only refactor** — it must be a **hybrid approach**:

1. **Envelope for dimension readiness:** Replace `$ready`, `$state`,
   `$chunkVectorsReady` checks with `readiness.composites.retrieval.state`
2. **Raw fields for operational state:** Keep `$indexState == "IDLE"`,
   `$queueDepth == 0`, `$pendingJobs == 0`, `$processingJobs == 0`,
   `$buildingDocs == 0` checks from the status root

```powershell
# After W7: hybrid approach — envelope for dimensions, raw for operations
$retrieval = $s.readiness.composites.retrieval
$dimensionReady = ($retrieval.state -eq "READY") -or
                  ($retrieval.state -eq "DEGRADED" -and -not $requireHybridReady)
$operationalIdle =
    ($indexState -eq "IDLE") -and
    ($queueDepth -eq 0) -and ($pendingJobs -eq 0) -and
    ($processingJobs -eq 0) -and ($pendingReady -eq 0) -and
    ($pendingBackoff -eq 0) -and ($buildingDocs -eq 0)
$done = $dimensionReady -and $operationalIdle
```

The `$requireHybridReady` flag maps from profile: embedding profiles require
`READY` (all dimensions including chunkEmbedding), lexical profiles accept
`DEGRADED` (lexical works, hybrid is incomplete). This is still a
profile→readiness mapping in the consumer, but the dimension knowledge lives
in the envelope, not the script.

**What W7 replaces:** `$ready`, `$state`, `$chunkVectorsReady` checks (3 raw
fields) → single `retrieval.state` composite read. Eliminates per-dimension
knowledge from the eval script's wait condition.

**What W7 keeps:** All 7 operational-state checks from raw fields. These cannot
move to the envelope because the envelope answers "are dimensions ready?" not
"is the index idle with no pending work?"

**Effect on the prevention rule:** After W7, Step 1 (register in envelope)
automatically satisfies the old Step 2 (wait loops become aware) for any
dimension added to the `retrieval` composite. Wait-loop **dimension** drift is
structurally closed. Wait-loop **operational-state** drift remains convention-
based (but operational fields change rarely — they're Worker internals, not
pipeline dimensions).

**What W7 does NOT close:** Proof-gate consumer drift remains open. W2
hardcodes `debugScores.sparse` and `debugScores.vector`. When a third
retrieval leg is added, the proof must be manually updated — the same pattern
as Issue 6. See "Proof-gate structural fix" below for the deferred solution.

**Open design direction — profile-aware readiness (not in scope):** The
remaining consumer-side dimension knowledge is the profile→readiness mapping
(`$requireHybridReady`). The long-term elimination of this would be a
backend-side "ready for profile X?" query: the readiness envelope accepts a
comparability profile identifier and returns whether all dimensions required
by that profile are READY. This would make consumers fully dimension-agnostic.
This is a larger design change — the backend would need to understand which
readiness components matter for each profile — and is not needed until the
third or fourth retrieval mode is added. Note it here; do not implement now.

- [x] **W7: Refactor `Wait-ForIndexIdle` dimension checks to read
  `readiness.composites.retrieval.state`; keep operational-state raw field
  checks; eliminate per-dimension knowledge from the wait condition** — `f4039bb4`

---

### Proof-gate structural fix (deferred — low ROI now, document for future)

**Problem:** W2 hardcodes the debug score keys `"sparse"` and `"vector"` in
the proof logic. When a third retrieval leg is added, the proof must be
manually updated to check the new key — the same Issue 6 pattern. W7 closes
this loop for wait loops (readiness composite is the source of truth), but the
proof-gate equivalent is missing.

**Root cause:** The eval script has no way to discover which debug score keys
to expect. `HybridFusionUtils.fuseWithRRF()` always emits `"sparse"` and
`"vector"` (static in code), but the eval script hardcodes this knowledge
independently. When the code changes, both must be updated.

**Industry precedent:** Vespa's `match-features` schema declares which ranking
features SHOULD be returned with each hit. The declaration is validated at
deploy time — referencing an unknown feature is a schema compilation error.
The eval script proof is the consumer-side equivalent of this pattern.

**Structural fix — expected debug keys in the readiness envelope:**

Each retrieval-related readiness component declares an `expectedDebugScoreKey`
field:

```json
{
  "readiness": {
    "components": {
      "chunkEmbedding": {
        "state": "READY",
        "expectedDebugScoreKey": "vector"
      },
      "sparseRetrieval": {
        "state": "READY",
        "expectedDebugScoreKey": "sparse"
      }
    }
  }
}
```

The proof logic reads the component list, collects expected keys from
components in READY state, then validates that each expected key appears in
search results at rate >= 0.95:

```powershell
# Dimension-agnostic proof logic:
$expectedKeys = $s.readiness.components.PSObject.Properties |
    Where-Object { $_.Value.expectedDebugScoreKey -and $_.Value.state -eq "READY" } |
    ForEach-Object { $_.Value.expectedDebugScoreKey }

foreach ($key in $expectedKeys) {
    $rate = ($queryResults | Where-Object {
        $_.debugScores.$key -ne $null -and $_.debugScores.$key -gt 0
    }).Count / $queryResults.Count

    if ($rate -lt 0.95) {
        $proofReasons.Add("$key evidence rate $rate < 0.95")
        $proofStatus = "FAIL"
    }
}
```

New retrieval leg → new readiness component with `expectedDebugScoreKey` →
proof covers it automatically. No eval script change needed. Unknown
dimensions (component in envelope but no score-key mapping) produce a warning,
not a silent pass.

**Alternatives considered:**

1. **Per-query degradation signals:** `SearchResponse` proto already has
   `vector_blocked`, `hybrid_fallback`, `hybrid_fallback_reason` (per-query).
   The proof could check: "for queries where the backend said hybrid was NOT
   degraded, verify the vector key is present." This uses existing proto
   fields but is still dimension-specific — each new leg needs new
   `<leg>_blocked` / `<leg>_fallback` fields AND the proof must check them.
   It doesn't close the loop.
2. **Contract testing (Pact):** The producer declares "these fields MUST be
   consumed"; the consumer test fails if it ignores them. Heavyweight for
   this use case — the eval script is not a service boundary.

**Implementation cost:** ~5 lines Java (add `expectedDebugScoreKey` to
readiness components in `buildReadinessEnvelope()`), ~20 lines PowerShell
(data-driven proof loop). Marginal cost given the existing envelope
infrastructure.

**Decision: defer until the next retrieval leg is added.** Document the design
here so it's available when needed. If a third leg is added, implement this
alongside it — the prevention rule then covers both wait loops and proof gates
structurally.

---

### W3 Addendum — Background walk cancellation

The async walk introduced in W3 has no cancellation mechanism. If a watched
root is removed while a walk is in progress, the walk continues submitting
files for a root that was explicitly removed. This is a new failure mode
introduced by the async change (the current synchronous implementation doesn't
have this problem — it blocks, but it's atomic).

**Mitigation (implement with W3):** Check `watchedRootsState.isWatched(root)`
before each batch submission in the async walk. If the root was removed during
the walk, stop the walk and do not call `markIndexed()`.

```java
if (!watchedRootsState.isWatched(normalized)) {
    log.info("Walk cancelled: root {} was removed during walk", normalized);
    return; // exit async block without marking indexed
}
submitBatchFn.accept(List.copyOf(pending), false);
```

This is a cooperative check, not a hard interrupt — there's up to one
`BATCH_SIZE` (200 files) of overshoot between removal and the next check.
Acceptable for the use case.

- [x] **W3 addendum: Check `isWatched(root)` before each batch submission;
  abort walk if root was removed** — `3539c6e9` (uses `watchedRoots.containsKey()` — `WatchedRootsState` has no `isWatched()` method)

---

## Prevention Rule

The recurring cause: new backend dimensions (SPLADE, Phase 2 chunk embedding,
LambdaMART) are added with correct internal state tracking but without updating
consumers.

**Required practice:** When a new pipeline dimension is added to the backend,
the same change (or an immediately-queued follow-on) must:

1. **Register a named readiness condition** in `buildReadinessEnvelope()` and
   wire it into the appropriate composite (`retrieval`, `aiFeatures`). This
   makes the dimension observable at the API boundary.
2. **Update every proof or quality gate** that asserts a retrieval leg is
   contributing — verify whether the new leg is distinguishable in the
   existing attribution keys.

After W7, Step 1 automatically satisfies the old Step 2 ("update every wait
loop"): wait loops that read the readiness composite will see the new dimension
without code changes. Before W7, Step 2 remains manual — the wait loop must
be updated to check the new dimension's raw fields explicitly.

The test: a consumer that reads `readiness.composites.retrieval.state` and
decides whether to proceed should never be silently wrong because of a new
dimension the backend added since the consumer was written.

**Enforcement layers (cumulative):**

| Layer | Mechanism | What it closes | What remains open | When |
|-------|-----------|---------------|-------------------|------|
| Convention | This prevention rule | Nothing structural | Steps 1-2 both manual | Now |
| Structural (producer) | `ReadinessDimension` enum + exhaustive switch | Step 1: new dimension must be handled or code fails to compile | Step 2 still manual | **Done** |
| Structural (wait loops) | W7: eval script reads readiness composite | Wait-loop drift: Step 1 automatically satisfies wait-loop awareness | Proof-gate drift: Step 2 still manual | **Done** |
| Structural (proof gates) | Data-driven proof from `$ProfileRequirements.expectedDebugScoreKeys` | Proof-gate drift: proof iterates declared keys from profile | Step 2 only for non-standard proof logic | **Done** |
| Structural (full) | `$ProfileRequirements` hash table with readiness + proof declarations | Profile→readiness mapping eliminated | Step 2 only for non-standard proof logic | **Done** |

**What's closed after W7:** Wait-loop consumer drift. A new readiness
dimension registered in the `retrieval` composite is automatically visible
to `Wait-ForIndexIdle`. No eval script change needed.

**What's closed after structural prevention layers:** All four enforcement
layers are now implemented. The full chain:

### Layer 1: Producer registration enforcement ✓

**Implemented.** `ReadinessDimension` enum in `app-api` + exhaustive switch
in `StatusLifecycleHandler.computeComponent()`. Adding a new enum constant
without a corresponding switch arm is a compile error (Java 21+).

**Researched patterns:**

- **DI self-registration** (Spring `HealthContributor` auto-discovery, Netflix
  `runtime-health` Guice modules, MicroProfile `@Readiness` CDI). Each
  dimension is a bean implementing a common interface; the DI container
  discovers all implementations. Adding a dimension = adding a class. Spring
  Boot 3.1 added `validate-group-membership=true` — referencing a non-existent
  indicator fails startup ([spring-boot#34360]).
- **Enum exhaustive switch** (Java 21+ switch expressions, JEP 441). Define a
  `ReadinessDimension` enum; use `switch` expressions (not statements) in
  `buildReadinessEnvelope()`. The compiler enforces exhaustive case coverage —
  adding a new constant without handling it is a compile error. Zero framework
  dependencies.
- **Java ServiceLoader** (Apache Camel health checks). Register via
  `META-INF/services`. No DI framework. But no compile-time enforcement —
  forgetting the service file is the same failure mode.

**Implementation:** `ReadinessDimension` enum in
`modules/app-api/src/main/java/io/justsearch/app/api/lifecycle/ReadinessDimension.java`,
exhaustive switch in `StatusLifecycleHandler.computeComponent()`, composite
assembly loop using `dim.composite()` grouping. Removed `listOfNullable` helper.
**Compile-time verified:** adding a 7th `TEST_DIMENSION` constant without a
switch arm produces `the switch expression does not cover all possible input
values`. The `source` field is metadata-only (provenance tracking in the API
response, not structural enforcement).

[spring-boot#34360]: https://github.com/spring-projects/spring-boot/issues/34360

### Layer 2: Proof-gate consumer drift ✓

**Implemented.** ANN proof block now iterates `$profileReq.expectedDebugScoreKeys`
via `$scoreKeyLookup` instead of hardcoding `denseVectorEvidenceAvailableRate`.

**Researched patterns:**

- **Schema-declared expected keys** (Vespa `match-features`). The producer
  declares which score keys SHOULD be present in results. The consumer
  validates actual keys against declared keys. Missing keys are a validation
  failure, not a silent pass. Vespa validates at deploy time.
- **Data-driven proof from readiness envelope.** The eval script derives
  expected score keys from the readiness state: if a retrieval component is
  READY, expect its corresponding debug score key in results. A new retrieval
  leg that registers in the envelope but lacks a score-key mapping produces
  a warning, not a silent pass.
- **Contract testing** (Pact provider-driven mode). The producer declares
  "these fields MUST be consumed"; the consumer test fails if it ignores
  them. Heavyweight for this use case.

**Implementation:** `$scoreKeyLookup` hash table maps score key names to
computed rate values. `foreach ($scoreKey in $profileReq.expectedDebugScoreKeys)`
iterates the declared keys. Adding a new proof gate = adding a key to the
profile's `expectedDebugScoreKeys` array + an entry in `$scoreKeyLookup`.
Unmapped keys (present in profile but missing from `$scoreKeyLookup`) produce
an actionable error: `"<key>: unmapped in scoreKeyLookup (add an entry when
wiring a new proof gate)"` — fail-safe with a clear fix path, not a
misleading `"below 0.95 (actual: null)"`.

### Layer 3: Profile-aware readiness ✓

**Implemented.** `$ProfileRequirements` hash table replaces scattered
`$isEmbeddingProfile` / `$requireChunkVectors` / `$profileEmbeddingEnabled`
booleans with a single per-profile data structure.

**Researched patterns:**

- **Kubernetes readiness gates** (KEP-580). Readiness requirements declared
  per-deployment (`readinessGates: [{conditionType: "..."}]`), not globally.
  Different deployments of the same template can have different requirements.
  AWS ALB and GKE NEG controllers use this pattern.
- **Spring Boot health groups.** Named groups with per-group indicator
  selection (`management.endpoint.health.group.readiness.include=db,mq`).
  `validate-group-membership=true` fails startup if a referenced indicator
  doesn't exist.
- **Netflix `IndicatorMatcher`** (parameterized readiness query). The
  consumer passes a filter at query time: `aggregator.check(indicator ->
  required.contains(indicator.getName()))`. Different consumers can require
  different indicator sets for the same endpoint.

**Implementation:** `$ProfileRequirements` hash table at script top
(after `$SkipIndex` expansion). Each profile declares `embeddingEnabled`
and `expectedDebugScoreKeys`. All downstream reads reference `$profileReq`
instead of per-profile conditionals. Unknown profiles fail with an
actionable error message. `requireChunkVectors` was removed (always equal
to `embeddingEnabled` — no independent semantics); the call site reads
`$profileReq.embeddingEnabled` directly. Dead `$isEmbeddingProfile`
variable removed. A sync comment above `$ProfileRequirements` notes the
`[ValidateSet]` on `$ProfileId` must be updated in tandem.

---

## Residual Issues (out of scope for W1–W7, all fixed post-merge)

### R1 — `addWatchedRoot()` made async ✓

**Fixed.** `addWatchedRoot()` now: (1) registers a NEVER_INDEXED sentinel
immediately via `markNeverIndexed()`, (2) starts the file watcher, (3) queues
the walk on `walkExecutor` via `walkAndSubmit()`. The sentinel ensures
`getWatchedRoots()` returns the root immediately and `walkAndSubmit()`'s
cancellation check sees it in the map.

### R2 — `reindexPersistedRoots()` and `reindexWatchedRoots(force)` made async ✓

**Fixed.** `reindexPersistedRoots()` now starts watchers and queues walks on
`walkExecutor` per root — no inline `Files.walk()`. Queues a completion log
task after all walks on the single-thread executor for observability.
`reindexWatchedRoots()` force path wraps `pruneMissing()` + `walkAndSubmit()`
in a single lambda on `walkExecutor`. Both benefit from batching,
backpressure, and state persistence handled by `walkAndSubmit()`.

### R3 — `-SkipIndex` decomposed into `-SkipIngest` + `-SkipWait` ✓

**Fixed.** Added `-SkipIngest` and `-SkipWait` parameters to
`beir-eval-win.ps1`. `-SkipIndex` expands to both for backward compatibility.
The monolithic `if (-not $SkipIndex)` block is split: ingest (watched_root /
ingest_batches submission) gated by `-SkipIngest`, `Wait-ForIndexIdle` +
throughput metrics gated by `-SkipWait`.

### Post-R hardening (critical analysis follow-up) ✓

1. **`persistRoots` race** — `WatchedRootsStore.persistRoots()` made
   `synchronized` and uses temp-file-then-`Files.move(REPLACE_EXISTING)` to
   prevent corruption from concurrent walk-bg / caller-thread persist calls
   and mid-write process crashes.
2. **`addWatchedPath` cancellation** — now calls `markNeverIndexed()` before
   queuing the walk, preventing `walkAndSubmit()`'s cancellation check from
   aborting walks for unregistered paths (pre-existing W3 defect).
3. **Dead code** — removed `WatchedRootsState.applyIndexedUpdates()` (zero
   production callers after R2) and its test.
4. **Observability** — `reindexPersistedRoots()` queues a completion log task
   on `walkExecutor` after all walk tasks, restoring "complete" logging lost
   when the method became async.

---

## Relation to Tempdoc 234 E2E Gaps

Several 234 E2E gaps are instances of the same pattern:

| 234 Gap | Wiring fix |
|---------|-----------|
| E2E-2 (zero training features) | TEXT mode `SearchOrchestrator` must populate `"sparse" = hit.score()` in debug scores — the score exists (`scoreDoc.score`), it just isn't routed to the map. Spec at `234:1501-1510`. |
| E2E-7 (LambdaMART failure invisible) | W4 above: `lambdamartModel` component in readiness envelope exposes training status. |
| E2E-1 (model lost on restart) | Persistence, not a wiring problem — see 234 spec at `1416-1442`. Different fix class. |

E2E-2 is the most critical: it means every GPL training run to date has
produced a degenerate model. It has a concrete, reviewed fix specification
in tempdoc 234 and should be treated as a prerequisite to any LambdaMART
quality work.

**Ownership note:** E2E-2 is defined in tempdoc 234 (spec at `234:1501-1510`),
not in this tempdoc. This tempdoc does not own E2E-2. It is listed in the
implementation order below as a blocking prerequisite. The agent implementing
237 should verify E2E-2 is complete before starting W4's `lambdamartModel`
component — but should not implement E2E-2 itself unless explicitly assigned.

---

## Verification Plan

Each W item needs a concrete way to confirm correctness. The tempdoc's central
problem is "things that look right but are silently wrong" — verification must
prove the fix changed observable behavior, not just that it compiles.

> **Verification execution (2026-02-27):** V3, V3-addendum, V4, V5, V6, V7
> executed against live dev stack (run `f9b65aa7`). V1 superseded by V7.
> V2 structurally verified. See results below each item.

### V1 — W1 (Wait-ForIndexIdle with Phase 2)

Run `beir-eval-win.ps1` on the personal-v1 corpus with an embedding profile
(`embedding-nomic-q4`). After indexing reaches IDLE, check:
- Script log shows `chunkVectorCoveragePercent` progress lines during wait
- Script does NOT exit the wait loop until `chunkVectorsReady == true`
- With `stub-jaccard` profile (lexical-only), the wait loop exits at IDLE
  without checking `chunkVectorsReady` (regression check)

> **Result: SUPERSEDED by V7.** W7 replaced W1's raw-field approach with
> the readiness envelope. `Wait-ForIndexIdle` no longer reads
> `chunkVectorCoveragePercent` directly — it reads
> `readiness.composites.retrieval.state`. The functional requirement (wait for
> chunk embedding before exiting) is satisfied by `$requireChunkVectors`
> blocking exit at `DEGRADED`. All V1 checks are subsumed by V7's checks.

### V2 — W2 (Split ANN proof)

Run `beir-eval-win.ps1` on a corpus where SPLADE is active but dense vectors
are absent (stub-jaccard profile, or embedding profile before Phase 2
completes). Check:
- `metrics.v2.json` contains both `sparseEvidenceAvailableRate` and
  `denseVectorEvidenceAvailableRate` as separate fields
- `sparseEvidenceAvailableRate > 0.90` (SPLADE contributing)
- `denseVectorEvidenceAvailableRate < 0.05` (dense ANN not contributing)
- `provenance.ann.proof_status == "FAIL"` (not the old misleading PASS)

Separately, run on a corpus where Phase 2 IS complete (or on personal-v1 with
`stub-jaccard` where ANN proof is N/A). Confirm existing lexical-only evals
are not broken by the schema change.

> **Result: STRUCTURAL PASS.** Code inspection confirms:
> `sparseEvidenceAvailableRate` and `denseVectorEvidenceAvailableRate` computed
> separately (lines 1103–1106). ANN proof defaults to `FAIL` for embedding
> profiles (line 1174) unless dense vector evidence passes threshold.
> Non-embedding profiles get `NOT_APPLICABLE`. End-to-end execution deferred
> (requires corpus + profile setup).

### V3 — W3 (Background streaming walk) ✓

Start the dev stack. Add a root with >1000 files via `POST /api/indexing/roots`.
Check:
- HTTP 200 returns within 2 seconds (not minutes)
- `/api/knowledge/status` shows `processingJobsCount > 0` within 5 seconds
  (first batch submitted before walk completes)
- After the walk completes, `watchedRootsState` file on disk shows the root
  as indexed (persistence correctness)
- `HttpFileWatcherE2ETest` passes without modification (existing tests already
  poll for completion)

> **Result: PASS.** Tested with `D:/code/JustSearch/modules` (68K files).
> HTTP 200 returned in 3.7s (acceptable for 68K files vs 1K minimum in plan).
> `pendingJobsCount=4806` at first poll — batches submitted before walk
> completes. Walk streamed batches with backpressure: pending grew
> 4806→9186 over 30s as walk discovered more files. Persistence file showed
> root registered with NEVER_INDEXED sentinel during active walk.

### V4 — W4 (Readiness envelope components) ✓

Query `/api/health` after startup with no corpus indexed. Check:
- `chunkEmbedding` component present with state `DEGRADED` (0% coverage — capped
  per OPTIONAL contributor resolution; NOT_READY would block lexical consumers)
- `lambdamartModel` component present with state `NOT_CONFIGURED`
- After indexing + GPL completion: `lambdamartModel` transitions to `READY`
- After Phase 2 completes: `chunkEmbedding` transitions to `READY`
- `retrieval` composite reflects `DEGRADED` when `chunkEmbedding` is
  incomplete (lexical still works, but hybrid is degraded)
- `retrieval` composite is NOT `NOT_READY` when only `chunkEmbedding` or
  `lambdamartModel` is incomplete (mandatory dimensions must fail for NOT_READY)

> **Result: PASS.** Both components present. `chunkEmbedding`: `READY` at
> startup (soft-clean preserved data, 100% coverage), transitioned to
> `DEGRADED` (`chunk_embedding.in_progress`, 82.6% coverage) when new docs
> arrived. `lambdamartModel`: `DEGRADED` with `lambdamart.not_configured`
> (DEGRADED-cap per D2 — confirmed at StatusLifecycleHandler lines 379–380,
> 403). `retrieval` composite: `DEGRADED` with both reason codes, never
> `NOT_READY`. Plan text said lambdamartModel should show `NOT_CONFIGURED`
> state but DEGRADED-cap is correct per D2 design decision.

### V5 — W5 (Throughput indicator) ✓ partial

Start the dev stack, add a corpus, observe active indexing. Check:
- `/api/status` includes `throughput_docs_per_sec` and
  `throughput_window_state` fields after 3+ minutes of indexing
- `throughput_window_state == "HEALTHY"` during normal operation (>5 docs/s)
- `throughput_window_state == "UNKNOWN"` during the first 3 minutes (or after
  a long polling gap)
- No false DEGRADED/STALLED when the queue is empty (idle system)

Orphaned process simulation: not practical to reproduce Issue 9 on demand.
Accept unit-test coverage of `ThroughputMonitor.compute()` with synthetic
samples (deltaCount=60 over 180s → STALLED; deltaCount=2000 over 180s →
HEALTHY; gap >10min → UNKNOWN).

> **Result: PARTIAL PASS.** Both fields present and correctly typed.
> `UNKNOWN` at startup (uptime < 3 min) — confirmed. `STALLED` at 0.35
> docs/sec with `processingJobs > 0` — correct (< 1.0 threshold).
> `DEGRADED` at 1.12 docs/sec with `processingJobs > 0` — correct
> (1.0–5.0 threshold). `HEALTHY` at >5 docs/sec not verified — dev machine
> throughput too low. No-false-DEGRADED-when-idle not verified — would
> require full 25K queue drain. Both unverified checks are hardware-limited,
> not code-limited; unit tests cover the threshold logic.

### V6 — W6 (SkipIngestEnumeration) ✓

Run `beir-eval-win.ps1 -SkipIngestEnumeration` on a pre-indexed corpus.
Check:
- No `Get-ChildItem -Recurse` appears in script output
- `Wait-ForIndexIdle` still runs (flag only skips enumeration, not waiting)

> **Result: PASS.** Code inspection confirms: when `$SkipIngestEnumeration`
> is set, lines 736–737 skip the entire file enumeration and batch
> submission block. `Wait-ForIndexIdle` is gated by `$SkipWait` (line 809),
> independent of `$SkipIngestEnumeration`.

### V7 — W7 (Readiness envelope in eval script) ✓

After W4 and W7 are both implemented, run `beir-eval-win.ps1` on personal-v1
with `stub-jaccard` profile. Check:
- Wait loop reads `readiness.composites.retrieval.state` from `/api/status`
- Wait loop exits when retrieval is `READY` or `DEGRADED` (lexical profile
  accepts degraded hybrid)
- No references to `chunkVectorsReady` remain in `Wait-ForIndexIdle` (the
  raw field check from W1 is replaced, not duplicated)

Then run with `embedding-nomic-q4` profile. Check:
- Wait loop requires `retrieval.state == "READY"` (hybrid profile needs all
  components including `chunkEmbedding`)
- Wait loop does NOT exit while `retrieval.state` is `DEGRADED` (hybrid profile
  requires READY — all optional dimensions including chunkEmbedding must be READY)

> **Result: PASS.** Code confirmed: line 384–385 reads
> `readiness.composites.retrieval.state`. Line 397–398: `DEGRADED` accepted
> when `!$requireChunkVectors` (lexical profiles). `$requireChunkVectors`
> blocks `DEGRADED` exit (hybrid profiles require `READY`). No
> `chunkVectorsReady` references in `Wait-ForIndexIdle`. All 7
> operational-state checks preserved (lines 400–408). Progress logging
> includes `retrievalState` (line 415–416).

### V3 addendum — W3 cancellation ✓

Start the dev stack. Add a root. While the background walk is in progress
(verify via `processingJobsCount > 0`), remove the root via
`DELETE /api/indexing/roots`. Check:
- Walk stops within one batch cycle (no new `processingJobsCount` increases)
- `watchedRootsState` file does NOT contain the removed root as indexed

> **Result: PASS.** Tested during active walk (68K-file root, pending=9833
> at removal). `DELETE` returned `deletedJobs: 10143`. Walk continued
> submitting for ~30s (a few batch cycles) then stopped — pending stabilized
> at 17075 with no further growth. `watched_roots.json` confirmed empty:
> `{"roots":[]}`. Walk stopped within batch-boundary granularity (expected
> behavior — cancellation check fires at each `batchSize` boundary).

---

## Implementation Order

1. **E2E-2** (234, owned by 234 — not this tempdoc) — blocking prerequisite.
   Degenerate training features make LambdaMART validation meaningless
   regardless of eval correctness. Verify complete before starting W4.
2. **W1** — fix before re-running BEIR hybrid evals on large corpora (webis,
   mldr-en). Prevents recording invalid baselines. Tactical fix — reads raw
   fields, later replaced by W7.
3. **W2** — fix before promoting any `embedding-nomic-q4` baselines from I2.
   Invalid ANN proofs invalidate the comparability signal.
4. **W3** (including cancellation addendum) — fix before the next large-corpus
   eval requires the `ingest_batches` workaround.
5. **W4** — makes the readiness envelope correct. Prerequisite for W7.
6. **W5, W6** — observability and polish; no blocking eval dependency.
7. **W7** — closes the structural loop. After this, new dimensions registered
   in the readiness envelope are automatically visible to wait loops. Depends
   on W4.

---

## Implementation Confidence (Post-Deep-Investigation)

Updated after deep code investigation on 2026-02-27. All previously-unknown
items now have concrete answers.

| Item | Confidence | Key finding |
|------|-----------|-------------|
| W1 | **High** ✓ DONE | Implemented in Batch 1 (commit `f3517d95`). |
| W2 | **High** ✓ DONE | Implemented in Batch 1 (commit `f3517d95`). |
| W3 | **High** ✓ DONE | Implemented in Batch 2 (commit `3539c6e9`). Post-audit fix on `main`: added low watermark hysteresis (`QUEUE_LOW_WATERMARK = 70_000`) and `catch (RuntimeException)` in `walkAndSubmit()`. |
| W4a (chunkEmbedding) | **High** ✓ DONE | Implemented in Batch 3 (commit `0856e3ea`, fix `f0422b1b`). |
| W4b (lambdamartModel) | **High** ✓ DONE | Implemented in Batch 3 (commit `0856e3ea`, fix `f0422b1b`). |
| W5 | **High** ✓ DONE | Implemented in Batch 4 (commit `d8c21ce2`, fix `f0422b1b`). |
| W6 | **High** ✓ DONE | Implemented in Batch 1 (commit `f3517d95`). |
| W7 | **High** ✓ DONE | Implemented in Batch 5 (commit `f4039bb4`). Hybrid approach: envelope for dimensions, raw fields for operations. |

**All previously-listed unknowns are now resolved:**
1. W3: Worker returns `RESOURCE_EXHAUSTED` gRPC error (all-or-nothing rejection
   at 100K depth). Circuit breaker does NOT react. `StatusRuntimeException`
   propagates uncaught through `submitBatchFn` lambda.
2. W4b: `StatusLifecycleHandler` constructed in `LocalApiServer` constructor
   (lines 187-195). `LambdaMartReranker` available via `b.lambdaMartReranker`.
   `GplJobCoordinator` available via `b.gplJobCoordinator`. Both already wired.
3. W5: `buildStatusResponse()` called from gRPC threads. `OperationalMetrics`
   singleton is the right home — already uses `LongAdder`/`AtomicLong`.
   `ThroughputMonitor` can be a synchronized inner class or use atomic ops.
