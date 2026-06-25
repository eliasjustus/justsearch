---
title: "355: Index Reset API for Pipeline Profiling"
type: tempdoc
status: done
created: 2026-03-24
updated: 2026-03-25
---

> NOTE: Noncanonical doc (architecture + investigation). May drift.

# 355: Index Reset API for Pipeline Profiling

## Goal

Pipeline profiling doesn't require a full backend restart. Code
changes are pushed via hot reload (~2-3s), the index is reset via
API call, and re-ingestion starts immediately — without killing
processes, deleting data directories, or waiting for cold startup.

## Root Cause

There is no way to clear the index without restarting the backend.
The current profiling workflow is:

1. Kill backend (Head + Worker)
2. Delete `tmp/headless-eval-data/` (the entire data directory)
3. Rebuild + restart backend via Gradle (~30-60s cold start)
4. Ingest and measure

Step 3 is the bottleneck — it includes Gradle configuration
resolution, classpath assembly, JVM startup, Worker subprocess
spawn, ORT session creation, and Lucene index initialization. Most
of this work is identical between runs.

With hot reload, code changes take ~2-3s (JDWP bytecode push +
MMF service reconstruction signal). But the index state is sticky —
enrichment status fields, embeddings, SPLADE features, and NER
entities from the previous run are still in Lucene. A new profiling
run needs a clean slate.

jseval's `--start-backend --clean` handles the full lifecycle
(tempdoc 334 Phase 8) but still pays the 30-60s cold start cost.
For iterative profiling (change code → measure → change → measure),
this overhead dominates the feedback loop.

## State That Must Be Cleared

A "reset to fresh-start" touches state in both processes. The
listing below is exhaustive — omitting any of these leaves stale
state that contaminates the next profiling run.

### Head-side (UI process)

| Location | Class | What to clear |
|----------|-------|---------------|
| Active file watchers | `WatcherBootstrap.activeWatchers` | Stop all watchers via `close()` or per-root `stopWatcher()` |
| Watched-root registry | `WatchedRootsState` (wraps `ConcurrentHashMap<Path, Instant>` in `RemoteKnowledgeClient`) | Clear the map |
| Persisted roots | `WatchedRootsStore` → `watched_roots.json` | Persist the now-empty map (otherwise restart reloads old roots) |

No existing "clear all roots" method covers all three. `RootLifecycleOps.removeWatchedPath()` is per-root and also issues
`DeleteByPath` gRPC calls (unnecessary when the Worker is about to
`deleteAll()`). A new `RootLifecycleOps.clearAllRoots()` method
that stops watchers + clears state + persists — without per-root
gRPC deletion — is the cleanest path.

### Worker-side (Knowledge Server process)

| Location | Class | What to clear |
|----------|-------|---------------|
| Lucene index | `WritePathOps` (new `deleteAll()` method) | `IndexWriter.deleteAll()` + `CommitOps.commitAndTrack("reset")` |
| Job queue | `JobQueue` (SQLite) | Clear pending + failed rows. Stale queued paths from the previous corpus must not leak into the next run. |
| Loop bookkeeping | `IndexingLoop` internal fields | `pendingMarkDone` (list of uncommitted paths), `forcedPaths` (reindex bypass set), `indexedSinceCommit`, `lastCommitTime`, batch counters (`batchStartTime`, `batchIndexed`, `batchSkipped`, `batchFailed`) |
| Disambiguation state | `IndexingLoop` | `disambiguationPassComplete` → false, `lastKnownNerCompletedCount` → 0 |
| SPLADE backoff | `IndexingLoop` | `consecutiveSpladeFailures` → 0, `nextSpladeRetryTime` → 0 |
| Metrics counters | `OperationalMetrics` | All `LongAdder` fields (via `LongAdder.reset()`), all `ConcurrentHashMap` timing/enrichment maps, `AtomicLong` gauges, histogram accumulators (min fields back to `Long.MAX_VALUE`). `ThroughputMonitor` needs a new `public synchronized void reset()` method (the `samples` deque is private with no public clear). |
| Entity clusters | `EntityClusterStore` (SQLite, inside `DisambiguationService`) | `deleteAll()` — NER disambiguation clusters from the previous run. Not directly accessible from `IndexingLoop` or `GrpcIngestService`; must go through `DisambiguationService`. |

### State that does NOT need clearing

- **ORT sessions** (embedding, SPLADE, NER models) — reusable across runs, no index-specific state.
- **Lucene `LifecycleSnapshot`** (`IndexWriter`, `SearcherManager`, `Directory`) — `deleteAll()` + commit leaves these in a valid empty state. No lifecycle reconstruction needed.
- **Generation state** (`IndexGenerationManager`, `state.json`) — the active generation is reused, not replaced.
- **`EmbeddingCompatibilityController`** — only relevant during model-change migrations, not profiling resets. If it happens to be in `REBUILDING` state, that's a separate concern.
- **NRT refresh thread** — `CommitOps` manages this. A commit after `deleteAll()` triggers a refresh naturally.

## Proposed Design

### Sequence

`POST /api/debug/reset-index` triggers a two-phase reset:

**Phase 1 — Worker clears index state (synchronous, via gRPC):**
1. New `IngestService.ResetIndex` gRPC RPC → `GrpcIngestService`
   handler → `IndexingLoop.resetForProfiling(Runnable)`:
   a. Stop the loop thread: `running.set(false)`, interrupt, join.
      **Do not call `close()`** — `close()` destroys `nerService`
      and `contentExtractor` (IndexingLoop is their sole owner),
      making the loop unrestartable.
      **Abort if thread doesn't stop within 5s** — concurrent
      Lucene writes would corrupt the index. Sets `running=true`
      and throws `IllegalStateException`.
   b. Execute the `Runnable externalCleanup` callback (while loop
      is stopped — prevents stale job pickup):
      - `JobQueue.clearAll()` — delete all pending/failed rows.
      - `WritePathOps.deleteAll()` → `IndexWriter.deleteAll()`.
      - `CommitOps.commitAndTrack("reset")`.
      - `DisambiguationService.reset()` (new method) — internally
        calls `EntityClusterStore.deleteAll()` + resets snapshot
        to `EntityClusterSnapshot.EMPTY`.
      - `OperationalMetrics.resetAll()`.
   c. Clear loop bookkeeping fields (see table above).
   d. Restart the loop thread via `start()` — **always runs, even
      if cleanup threw** (try/finally). A dead loop is worse than
      a partially-reset loop.

**Phase 2 — Head clears watcher state (synchronous, in-process):**
2. Only after Worker reset succeeds:
   `RootLifecycleOps.clearAllRoots()`: stop all active watchers,
   clear `WatchedRootsState`, persist empty `watched_roots.json`.
   If Worker reset failed, Head state is unchanged — no
   inconsistency between processes.

The handler returns 200 with `{"reset": true}` on success. Head
logs the reset event.

### Gating

Only available when `justsearch.eval.mode=true`. This property
is set by `applyHeadlessEvalContract()` in `build.gradle.kts`
(alongside the existing `disable_breath_holding`). Returns 404
with `{"error": "Not available outside eval mode"}` otherwise.

Pattern follows `Boolean.getBoolean("justsearch.eval.mode")`,
consistent with how `disable_breath_holding` is checked.

### jseval integration

`jseval run --reset` calls the reset API before ingestion instead
of `--start-backend --clean`. The backend stays running. Combined
with hot reload:

```bash
# After code change:
reload                                    # ~2-3s: push bytecode
cd scripts/jseval
python -m jseval run --dataset scifact \
  --max-queries 0 --pipeline --reset      # ~3-5min: ingest + enrich
```

vs current:

```bash
# After code change:
cd scripts/jseval
python -m jseval run --dataset scifact \
  --max-queries 0 --pipeline \
  --start-backend --clean                 # ~4-6min: rebuild + start + ingest + enrich
```

Saves 30-60s per iteration.

## Work Items

### gRPC contract (`ipc-common`)

1. [x] **`ResetIndex` RPC in `IngestService`.** New unary RPC in
   `indexing.proto`. Empty `ResetIndexRequest`, `ResetIndexResponse`
   with boolean `success` field.

### Worker-side (`adapters-lucene`, `worker-core`, `worker-services`)

2. [x] **`WritePathOps.deleteAll()`.** Follows `deleteByPathPrefix`
   pattern (null-check on `ctx.snapshot.writer()`, wraps IOException
   in `IndexRuntimeIOException`).

3. [x] **`OperationalMetrics.resetAll()`.** Zeroes 16 `LongAdder`
   fields, clears 4 `ConcurrentHashMap` maps, resets 3 `AtomicLong`
   gauges, resets 9 histogram `AtomicLong` accumulators (min fields
   to `Long.MAX_VALUE`), calls `throughputMonitor.reset()`.

3a. [x] **`ThroughputMonitor.reset()`.** `public synchronized`
    method, clears the `samples` deque.

4. [x] **`JobQueue.clearAll()`.** Default method on `JobQueue`
   interface (returns 0). `SqliteJobQueue` override follows
   `clearFailedJobs()` pattern. `InMemoryJobQueue` does not exist
   in the main checkout (only in worktree branches) — covered by
   the default method.

5. [x] **`IndexingLoop.resetForProfiling(Runnable)`.** Takes a
   `Runnable externalCleanup` callback. Sequence: stop thread →
   verify thread actually stopped (throws `IllegalStateException`
   if still alive after 5s) → run callback → clear 15 internal
   fields → restart via `start()`. Cleanup + restart wrapped in
   try/finally so the loop always restarts even if cleanup throws.
   Does not call `close()`.

5a. [x] **`DisambiguationService.reset()`.** Calls
    `store.deleteAll()`, sets `snapshot = EntityClusterSnapshot.EMPTY`,
    sets `initialized = false`.

5b. [x] **`IndexingLoop.getDisambiguationService()`.** New accessor
    so `GrpcIngestService` can reach the volatile field.

6. [x] **`GrpcIngestService.resetIndex()`.** Calls
   `indexingLoop.resetForProfiling(externalCleanup)` where the
   callback lambda runs: `jobQueue.clearAll()` →
   `writePathOps().deleteAll()` → `commitOps().commitAndTrack("reset")`
   → `disambiguationService.reset()` → `OperationalMetrics.resetAll()`.
   All external state cleared while loop is stopped; loop internal
   state cleared and restarted by `resetForProfiling()` itself.

### Head-side (`app-api`, `app-services`, `ui`)

7. [x] **`RootLifecycleOps.clearAllRoots()`.** Calls
   `watcherBootstrap.close()` (non-terminal) then
   `watchedRootsState.clearAll()` (new method: clears map + walk
   errors + persists empty `watched_roots.json`).

7a. [x] **`IndexingService` interface.** Added `clearAllRoots()`
    and `resetIndex()` default methods. `RemoteKnowledgeClient`
    implements both: `clearAllRoots()` delegates to
    `RootLifecycleOps`, `resetIndex()` issues the gRPC call.

8. [x] **`POST /api/debug/reset-index` handler.** Implemented as
   `LocalApiServer.handleResetIndex()` (private method, passed as
   `Handler` to `DebugRoutes.register()`). Gated on
   `Boolean.getBoolean("justsearch.eval.mode")`. Calls Worker
   reset first (`appFacade.indexing().resetIndex()`), then Head
   cleanup (`appFacade.indexing().clearAllRoots()`) only on
   success — prevents Head/Worker inconsistency on failure.

9. [x] **`justsearch.eval.mode` system property.** Added to
   `applyHeadlessEvalContract()` in `modules/ui/build.gradle.kts`.

### jseval

10. [x] **`--reset` flag on `jseval run`.** Mutually exclusive with
    `--start-backend`. Calls `_reset_index(base_url)` which POSTs
    to `/api/debug/reset-index` with 30s timeout. Handles 404
    (not eval mode), connection errors, and non-200 responses.

### Verification

11. [x] **E2E: reset + re-ingest produces identical results.**
    Verified: ingest 5184 scifact docs → reset (returns `{"reset":true}`,
    status shows 0 docs, search returns 0 hits) → re-ingest (Worker log:
    `5184 indexed, 0 skipped, 0 failed`). Three additional bugs discovered
    and fixed during E2E testing (see below).

## Resolved Issues (critical analysis)

Issues identified during critical analysis, all resolved:

| Severity | Issue | Resolution |
|----------|-------|------------|
| High | `externalCleanup` exception leaves loop permanently dead | try/finally around cleanup + restart |
| High | No `isAlive()` check after `join(5000)` — concurrent writes | Abort with `IllegalStateException` if thread still alive |
| High | Head/Worker inconsistency if gRPC fails | Worker reset first, Head cleanup second |
| Medium | `walk-bg` walk submits batches after reset | Cancellation checks at walk start, tail batch, and post-backpressure |
| Low | Watcher event TOCTOU — stray events after reset | `findWatchedRootFor(path)` guard in `handleCreateOrModify` and `handleDelete` |
| Low | One batch completes after `running.set(false)` | Inherent and safe — `deleteAll()` wipes unconditionally. Documented. |
| Low | Non-volatile field visibility relies on JMM | Correct via `Thread.start()` happens-before. Documented with maintenance note. |

### E2E-discovered bugs (fixed)

| Bug | Root Cause | Fix |
|-----|-----------|-----|
| gRPC returns UNIMPLEMENTED | `DelegatingIngestService` (explicit forwarding wrapper) missing `resetIndex` forward | Added `resetIndex` forward to `DelegatingIngestService.java` |
| `AlreadyClosedException` on second reset | `Thread.interrupt()` causes `ClosedByInterruptException` in Lucene NIO channels, permanently closing IndexWriter | Removed interrupt; rely on `running=false` + 10s join timeout |
| All files skipped as "unchanged" after reset | Stale `SearcherManager` — `docCount()` returned old count because NRT refresh thread was dead | Added `maybeRefreshBlocking()` after commit in reset cleanup |

### Pre-existing bug discovered (not caused by this feature)

`CommitOps.resumeNrtRefresh()` crashes with `IllegalArgumentException:
targetMaxScaleSec (= 0.5) < targetMinStaleSec (=9.22E15)` during
enrichment backfill's NRT resume. The `nrtHardMaxStaleMs` field reads
`Long.MAX_VALUE` (uninitialized). This kills the NRT refresh thread,
making the SearcherManager permanently stale. Affects `--pipeline`
enrichment runs independently of this feature.

## Alternatives Considered

- **`IndexWriter.deleteAll()` without pausing the loop.** Risk:
  the loop might process stale pending-ID queries during deletion.
  Pausing first is safer.
- **Drop and recreate the Lucene directory.** More thorough but
  requires closing the `IndexWriter` and `SearcherManager`, which
  means reconstructing the entire Lucene lifecycle — effectively
  a restart within the process. `deleteAll()` is cleaner.
- **Blue-green generation switch to an empty generation.** Uses
  existing migration infrastructure but is heavier than needed —
  migrations involve generation ID tracking, cutover logic, and
  cleanup. A simple `deleteAll()` is sufficient for profiling.
- **Per-root `removeWatchedPath()` loop + `deleteByPathPrefix()`
  per root.** Correct but wasteful — each root issues a separate
  gRPC deletion call, and then `deleteAll()` wipes the index
  anyway. Clearing watcher state without per-root gRPC calls is
  faster and simpler.

## Context: sleep hook and why this tempdoc matters

The `bash-guard.mjs` hook now blocks `sleep >= 1s` in all Bash
commands (added 2026-03-24). Agents are redirected to jseval's
`--start-backend --clean --pipeline` for pipeline profiling. This
eliminates manual sleep-based polling but doesn't eliminate the
30-60s cold start cost per profiling iteration.

The sleep block exposed a deeper problem: agents used sleeps because
the backend lifecycle was slow. Removing the sleeps without removing
the slowness just makes agents hit the hook repeatedly and look for
workarounds. The index reset API eliminates the root cause — the
cold start — by making the backend reusable across profiling runs.

Without this tempdoc, the feedback loop for pipeline optimization is:
- Code change: ~2-3s (hot reload)
- Backend restart: ~30-60s (kill + clean + rebuild + start)
- Ingest + enrich: ~3-5min
- **Total: ~4-6min, dominated by restart**

With this tempdoc:
- Code change: ~2-3s (hot reload)
- Index reset: ~1s (API call)
- Ingest + enrich: ~3-5min
- **Total: ~3-5min, dominated by actual work**

## Modules touched

| Module | Files | Changes |
|--------|-------|---------|
| `ipc-common` | `indexing.proto` | New `ResetIndex` RPC + messages |
| `adapters-lucene` | `WritePathOps.java` | New `deleteAll()` method |
| `worker-core` | `OperationalMetrics.java` | New `resetAll()` + `ThroughputMonitor.reset()` |
| `worker-core` | `JobQueue.java` (interface) | New `clearAll()` default method |
| `worker-core` | `DisambiguationService.java` | New `reset()` method |
| `indexer-worker` | `SqliteJobQueue.java` | `clearAll()` implementation |
| `indexer-worker` | `DelegatingIngestService.java` | `resetIndex` forward (gRPC delegation wrapper) |
| `worker-services` | `IndexingLoop.java` | New `resetForProfiling(Runnable)` + `getDisambiguationService()` |
| `worker-services` | `GrpcIngestService.java` | New `resetIndex()` RPC handler (orchestrator) |
| `app-api` | `IndexingService.java` | New `clearAllRoots()` + `resetIndex()` interface methods |
| `app-services` | `RemoteKnowledgeClient.java` | `clearAllRoots()` + `resetIndex()` implementations |
| `app-services` | `RootLifecycleOps.java` | New `clearAllRoots()` method |
| `app-services` | `WatchedRootsState.java` | New `clearAll()` method |
| `ui` | `LocalApiServer.java` | `handleResetIndex()` handler |
| `ui` | `DebugRoutes.java` | New `resetIndexHandler` parameter + route |
| `ui` | `build.gradle.kts` | `justsearch.eval.mode` system property |
| `ui` (test) | `LegacyEndpointGuardTest.java` | Updated `DebugRoutes.register()` call arity |
| jseval | `cli.py` | `--reset` flag + `_reset_index()` function |

## Dependencies

- **334 (Single-Pass Enrichment):** Primary consumer — iterative
  pipeline profiling.
- **354 (Map-Based Metrics):** `OperationalMetrics.resetAll()` must
  clear the map-based timing accumulators added by 354.
