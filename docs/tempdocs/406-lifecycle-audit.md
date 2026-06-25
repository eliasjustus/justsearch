---
title: "406 — Lifecycle Audit (Phase 0)"
type: tempdocs
status: open
---

# 406 — Lifecycle Audit (Phase 0)

This is the Phase 0 deliverable for tempdoc 406. It enumerates every
field that lives today on `RuntimeContext` + `LuceneLifecycleManager`
and classifies it for the post-refactor split:

- **`IndexSchema`** — immutable, sharable across runtimes
- **`LuceneRuntimeBuilder`** — per-builder intent capture
- **`RuntimeSession`** — per-phase, AutoCloseable
- **`CommitOps`** — per-runtime, narrowly scoped post-refactor mutator
- **DELETED** — state-machine fields replaced by phase types

The verification gate at the end checks symmetry: every prospective
`RuntimeSession` field has a single ctor row + a single close row.

## 1. IndexSchema (immutable, sharable)

| Field | Today's location | Today's ctor site | Today's release | Notes |
|---|---|---|---|---|
| `metadataSourceSupplier` | `RuntimeContext:38` (final) | `RuntimeContext` ctor | never | Already final and reusable. Moves verbatim to schema. |
| `metadataValidator` | `RuntimeContext:39` (final) | `RuntimeContext` ctor | never | Already final and reusable. Moves verbatim to schema. |
| `fieldMapper` | `RuntimeContext:40` | `RuntimeContext` ctor / `LuceneLifecycleManager:152` | never | Mutable today (no setter callers found in main; treat as effectively immutable). Moves to schema. |
| `analyzerRegistry` | `RuntimeContext:82` | `LuceneLifecycleManager:155` (default `new SsotAnalyzerRegistry()` if null) | preserved across close (`LuceneLifecycleManager:540` comment) | Pre-flight verified: 3 additive `ConcurrentHashMap` caches, lazy load, never invalidated. Lucene `Analyzer` is thread-safe. Safe to share across runtimes via schema. The "preserve across close" hack disappears. |
| `knnVectorsFormatOverride` | `RuntimeContext:85` | `LuceneLifecycleManager:157` | never | Nullable override. Schema-time concern. The actual `KnnVectorsFormat` *used* (after applying defaults) is computed in `ComponentsFactory.build` line ~158-159 and lives in the session. Schema holds the *override*; session holds the *resolved* format. |

## 2. LuceneRuntimeBuilder (per-builder intent capture)

| Field | Today's location | Today's ctor site | Today's release | Notes |
|---|---|---|---|---|
| `indexPath` | `RuntimeContext:55` | `LuceneLifecycleManager:156` (ctor param) | n/a | Captured via `schema.atPath(p)` or `schema.ephemeral()`. |
| `fallbackIndexPath` | `RuntimeContext:98` | `LuceneLifecycleManager:153` (ctor param) | n/a | Legacy fallback. Builder fluent setter. |
| `resolvedConfig` | `RuntimeContext:58` (volatile) | `LuceneLifecycleManager:154` (default via `RuntimeContext.resolveFromConfigStore()`) | n/a | Captured via `.withConfig(rc)`; default = resolve at open time. |
| `telemetryEvents` | `RuntimeContext:78` (volatile) | setter `setTelemetryEvents` (called by KS) | n/a | Captured via `.withTelemetry(t)`. Optional. |
| `softDeletesMetrics` | `RuntimeContext:79` (volatile) | setter `setSoftDeletesMetricsListener` (called by KS) | n/a | Captured via `.withSoftDeletesMetrics(s)`. Optional. |
| `indexOpenGuard` | `RuntimeContext:97` | `LuceneLifecycleManager:160-164` (default `IndexMetadataParityGuard`) | n/a | Captured via `.withIndexOpenGuard(g)`. Default constructed at open time from `schema.metadataSourceSupplier()` + builder's `indexPath`. **Pre-flight: does NOT depend on `commitOps`** — guard's two suppliers are `indexPath` + metadata-source. |
| `initialBuildState` | n/a (NEW) | n/a (NEW) | n/a | NEW per pre-flight. Replaces start-time `setBuildState` calls (4 of 5 sites). Default `COMPLETE`. |
| `prebuiltComponents` | `RuntimeContext:99` | `LuceneLifecycleManager:165` (always `null`) | never | Test-only injection point. Builder fluent setter (or kept package-private for tests). |

## 3. RuntimeSession (per-phase, AutoCloseable)

### 3a. Resource fields (Lucene-managed, must close)

| Field | Today's ctor site | Today's release site | Read sites |
|---|---|---|---|
| `LifecycleSnapshot snapshot` (bundles `Directory directory`, `IndexWriter writer`, `SearcherManager searcherManager`, `Path indexPath`, `boolean ephemeralPath`, `Analyzer indexAnalyzer`) | `LuceneLifecycleManager.applyComponents:554` (single volatile publish) | `LuceneLifecycleManager.close:478-501` (capture, null, then close SM/writer/dir in order) | every ops class via `ctx.snapshot` (volatile read pattern) |
| `crtrt` (`ControlledRealTimeReopenThread`) | `LuceneLifecycleManager.applyComponents:562`; cycled by `CommitOps.suspendNrtRefresh:181` / `resumeNrtRefresh:202` | `LuceneLifecycleManager.close:484-487` (close + null) | `CommitOps` (suspend/resume), `LuceneLifecycleManager.close` |

### 3b. Counter / atomic state (per-runtime)

| Field | Today's ctor site | Today's release site | Read sites |
|---|---|---|---|
| `pendingDocs`, `commitCount`, `queueDepth`, `lastRefreshNanos`, `lastCommitNanos`, `lastRefreshTargetMs` | `RuntimeContext:65-70` (field initializers) | implicit (counters survive close today; new RuntimeSession resets them) | `CommitOps`, `IndexingCoordinator`, status accessors |
| `openTimeCommitUserData` | `LuceneLifecycleManager.start` (read from reader user data) | implicit via session GC | `latestCommitUserDataBestEffort` |

### 3c. Config-derived bits (set during start)

| Field | Today's ctor site | Today's release site | Read sites |
|---|---|---|---|
| `commitMetadataEnabled` | `LuceneLifecycleManager.applyComponents:552` | n/a | `CommitOps:49` |
| `validationMode` | `LuceneLifecycleManager.start:380-381` (sys prop read) | n/a | `IndexingCoordinator` |
| `maxQueueDepth` | `RuntimeContext:62` (default) | n/a | `IndexingCoordinator` |
| `nrtTargetMaxStaleMs`, `nrtHardMaxStaleMs` | `LuceneLifecycleManager.applyComponents:567-568` | n/a | `CommitOps`, `resumeNrtRefresh` |
| `vectorEfSearchOverrideOrNull` | `LuceneLifecycleManager.applyComponents:570-575` | n/a | search ops |
| `softDeleteField` | `LuceneLifecycleManager.applyComponents:564` | n/a | various ops |
| `uidField`, `hardDeleteField` | `RuntimeContext:90-91` (defaults) | n/a | various ops |
| standalone `indexPath` (copy of `snapshot.indexPath`) | `LuceneLifecycleManager` ctor `:156` + `applyComponents:563` | n/a | logging, `openWriterDeferred` ComponentsFactory args |

### 3d. Ops references (today on LLM, post-refactor on RuntimeSession)

| Field | Today's ctor site | Today's release site | Read sites |
|---|---|---|---|
| `readPathOps`, `writePathOps`, `hybridSearchOps`, `textQueryOps`, `chunkSearchOps`, `suggestOps`, `documentFieldOps`, `indexCountOps`, `pruneOps`, `facetingEngine`, `folderBrowseEngine` | `LuceneLifecycleManager.applyComponents:581-597` | `LuceneLifecycleManager.close:528-539` (nulled) | LLM getters |
| `indexingCoordinator` | `LuceneLifecycleManager` ctor `:168` (asymmetric — created in ctor, not applyComponents) | `LuceneLifecycleManager.close:536` (nulled) | `LLM.indexingCoordinator()` |
| `commitOps` | `LuceneLifecycleManager` ctor `:159` (asymmetric — `final` field) | NOT nulled (held by `final`) | `LLM.commitOps()` |

**Asymmetry today (the audit-confusion bug-class in evidence):**

- `commitOps`: `final` on LLM, never released. Other ops nulled in close.
- `indexingCoordinator`: created in LLM ctor (so `validate()` works
  before start), but nulled in close like the rest. Asymmetric ctor
  site vs close site.
- 11 ops created in `applyComponents`, nulled in `close`. Symmetric
  among themselves but split across LLM (these 11) + LLM ctor (these 2)
  + RuntimeContext (counters/derived bits). Three different homes for
  per-session field declarations.

**Post-refactor (Phase 2):** all 13 ops + all per-session counters +
all derived bits + `LifecycleSnapshot` + `crtrt` live as **`final`
fields on `RuntimeSession`**. Single ctor declares + initializes all of
them. Single `close()` releases them in order. `indexingCoordinator`
created lazily during ctor like the rest (the "needed before start"
caveat goes away when there's no separate "before start" — phase value
is constructed-or-not, period). `commitOps` created in ctor and closed
in `close()` — its commit timer cycles via `start/stopCommitTimer` per
its existing API.

## 4. CommitOps (post-refactor: holds the only mid-life mutator)

| Field | Today's location | Post-refactor |
|---|---|---|
| `currentBuildState` (new field, replaces `RuntimeContext.buildState`) | `RuntimeContext:59` (volatile) | New on `CommitOps`. Mutated by `commitWithBuildState(BuildState)`. Read by `commit()` (line 53 today: `meta.put("build_state", ctx.buildState.name())`) and by scheduled timer (`timerTick:251` → `commitAndTrack` → `commit`). Initial value passed via `LuceneRuntimeBuilder.withBuildState` → flowed into RuntimeSession ctor → into CommitOps ctor. |
| `commitTimer` / `commitTimerFuture` | `CommitOps:36-37` (volatile) | Unchanged. Timer cycled via existing `startCommitTimer` / `stopCommitTimer` from RuntimeSession ctor / close. |

## 5. DELETED (state machine + latch — replaced by phase types)

| Field | Today's location | Why deleted |
|---|---|---|
| `state` (`RuntimeState` enum: CREATED, RUNNING, READ_ONLY, CLOSING, CLOSED, FAILED) | `RuntimeContext:43` | Replaced by phase type identity (`RunningRuntime` vs `ReadOnlyRuntime` vs `DeferredRuntime`). |
| `stateLock` | `RuntimeContext:44` | Gone with `state`. |
| `deferredWriterPending` | `RuntimeContext:45` | Encoded in `DeferredRuntime` type. |
| `initialReadOnly` | `RuntimeContext:46` | Encoded in `ReadOnlyRuntime` type. |
| `writerReadyLatch` | `RuntimeContext:47` (final `CountDownLatch(1)`) | Pre-flight finding: write ops are not callable on `DeferredRuntime` (compile error, not latch wait). Latch is gone, not just internal. |
| `RuntimeContext` itself | the whole class | All fields classified above; the class is split across `IndexSchema` (immutable), `LuceneRuntimeBuilder` (intent), `RuntimeSession` (per-phase), `CommitOps` (commit-time mutator). |

## 6. Verification gate — symmetry confirmed

For every prospective `RuntimeSession` field listed in §3:

- **Single ctor site:** the new `RuntimeSession(IndexSchema, LuceneRuntimeBuilder, RuntimeMode, ...)` ctor.
- **Single release site:** the new `RuntimeSession.close()`.

The asymmetries documented in §3d (e.g., `commitOps` `final` and
unreleased today; `indexingCoordinator` created in LLM ctor not
`applyComponents`) **resolve into symmetry** in the post-refactor
`RuntimeSession`: every field is initialized in the ctor and released
in `close()`, in a single declaration block.

**No design revision required.** The audit confirms the Phase 2
extraction can be expressed as a single ctor + single close without
asymmetric edge cases. Phase 1 may proceed.

## 7. LuceneLifecycleManager kill-list (78 files, grouped by strategy)

Pre-flight grep showed 78 files mention `LuceneLifecycleManager`.
Filtering for files that USE the type as a parameter / field / return
(not just import) narrows the list dramatically.

### 7a. Mechanical (no behavioral change, type swap only)

In `modules/adapters-lucene/src/main`, only **3** files use the type
as a name (the rest only import it for compilation, since most ops
take `RuntimeContext` directly):

- `IndexRuntimeFactory.java` — Phase 1 makes this delegate to the builder.
- `SearchResultFormatter.java` — likely takes LLM as parameter; swap to phase type.
- `SearchAfterCursorHelper.java` — same.

The other ~17 adapters-lucene/src/main files import the type but use
`RuntimeContext` (or its successor) as the parameter — these are
zero-change once the type swap is mechanical.

### 7b. Holder-pattern (meaningful caller migration to `Supplier<R>`)

Production sites (Phase 3, not this session):

- `KnowledgeServer.java` — primary holder; `ingestLifecycle` /
  `searchLifecycle` fields become typed `RunningRuntime` /
  `LuceneRuntime` (sealed).
- `KnowledgeServerMigrationOps.java` — uses
  `Supplier<LuceneLifecycleManager>` already; trivially becomes
  `Supplier<RunningRuntime>`.
- `DefaultWorkerAppServices.java` (worker-services) — record holding
  lifecycle refs; becomes record holding suppliers.
- `GrpcSearchService.java` — captures direct ops refs; becomes
  `Supplier<LuceneRuntime>` re-read per request.
- `GrpcIngestService.java` — same pattern, `Supplier<RunningRuntime>`.
- `SearchOrchestrator.java` — same.
- `IndexStatusOps.java` (worker-services) — already takes config
  supplier; takes runtime supplier next.
- `InfraContext.java` (worker-core) — record signature change. Single
  construction site (`KnowledgeServer`).
- `EmbeddingCompatibilityController.java` (worker-core) — direct refs;
  supplier migration.
- 4 benchmarks (`IndexingOverheadProfiler`, `FilteredKnnBench`,
  `EngineVectorIndexBench`, `EngineIndexBench`) — Phase 1 migrates
  these to builder API directly (no supplier needed; benchmarks own
  the runtime).

Test sites (~22 ctor sites + `RuntimeTestBase.createRuntimeWithDim` +
`LifecycleTestAccessor`) — Phase 1 migrates `RuntimeTestBase`; the
rest follow in Phase 2 when the type they construct changes (LLM →
phase value).

### 7c. Self-references (deleted with LLM)

Deleted with `LuceneLifecycleManager.java` itself in Phase 2:

- `LuceneLifecycleManager.java`
- `RuntimeContext.java` (split per §1-5)
- `RuntimeState.java` (deleted with state machine)

## 8. Conclusion

- §1-5 classify every field; §6 confirms symmetry; §7 enumerates the
  caller migration surface.
- No design revision is required after the audit.
- Phase 1 may proceed: extract `IndexSchema` (per §1) and add
  `LuceneRuntimeBuilder` skeleton (per §2). Phase 1's open* methods
  return existing `LuceneLifecycleManager` (already-started); the
  phase-typed return values land in Phase 2 alongside the actual
  `RuntimeSession` extraction.
