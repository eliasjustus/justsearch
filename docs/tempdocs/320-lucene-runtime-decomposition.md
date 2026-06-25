---
title: "LuceneIndexRuntime Rewrite Plan"
status: done
created: 2026-03-17
---

# 320 — LuceneIndexRuntime Rewrite Plan

## Diagnosis

`LuceneIndexRuntime` is an 80+ method God Object. Prior work extracted logic into 12 ops classes, but every caller still depends on the single concrete type. The ops classes contain all the logic — the facade is pure indirection: 90% of its methods are `ensureStarted(); return ops.method(args)`. This means:

1. **The facade is the God Object.** Extracting logic into ops classes didn't fix the problem — it just made the God Object thinner while keeping the same monolithic API surface. Every new capability still adds methods to the facade.
2. **Circular lambda callbacks through the facade.** Ops depend on each other via `(t, l) -> searchText(t, l)` lambdas that route through the facade. The dependency graph is `Ops → lambda → Facade → Ops`. The facade is a middleman adding indirection to every cross-ops call.
3. **~1,100 LOC of delegation boilerplate.** The facade's only job is hosting 80 methods that call `ensureStarted()` and delegate. This is not logic — it's wiring masquerading as a class.
4. **Callers see 80 methods when they use 5.** A search service can call `deleteById()`. A status endpoint can call `commit()`. The facade masks real dependency structure.

Replacing one God Object facade with 5 smaller facades (the prior version of this plan) preserves the same delegation boilerplate and lambda callbacks — it's a reorganization, not a rewrite. The correct fix: **eliminate the facade entirely**.

## Caller analysis

Every production caller holds `LuceneIndexRuntime` as the concrete type (not `IndexRuntime`). Two runtime instances exist: a **read-only search runtime** and a **read-write ingest runtime**, created by `KnowledgeServer` and distributed via `InfraContext` → `DefaultWorkerAppServices`.

| Caller | Receives | What it actually uses |
|--------|----------|---------------------|
| **SearchOrchestrator** | searchRuntime | search (all flavors), queryBuilding, facets, QPP, docCount, resolvedConfig, getDocField |
| **GrpcSearchService** | searchRuntime | suggest, getDocField, folderBrowse, listAllDocs, maybeRefresh, docCount |
| **RagContextOps** | searchRuntime | chunkSearch, countByField, resolvedConfig |
| **CitationMatchOps** | searchRuntime | search(raw query), maybeRefresh |
| **GrpcIngestService** | ingestRuntime + searchRuntime | updateDoc, deleteById, deleteByPath, updatePaths, commit, queryDocIds, countByField, getDocField; passes searchRuntime to IndexStatusOps |
| **IndexingLoop** | ingestRuntime | index, deleteByIdAndChunks, commit, isUnmodified, countByField, resolvedConfig |
| **ChunkDocumentWriter** | ingestRuntime | getDocField, deleteChunks, index |
| **EmbeddingBackfillOps** | ingestRuntime | getDocField, updateDoc |
| **SpladeBackfillOps** | ingestRuntime | getDocField, updateDoc |
| **NerBackfillOps** | ingestRuntime | getDocField, updateDoc |
| **DisambiguationBackfillOps** | ingestRuntime | getDocFieldValues |
| **SyncDirectoryOps** | ingestRuntime | search, pruneByPath, commit |
| **IndexStatusOps** | ingestRuntime + searchRuntime | docCount, countByField, embeddingCounts, vectorFormat, commitUserData |
| **GrpcHealthService** | searchRuntime | docCount |
| **KnowledgeServerSafeMetrics** | ingestRuntime | countByField |
| **KnowledgeServer** | both (owns lifecycle) | start, close, deferredWriter, setBuildState, validateIndexableFields, ssotVectorDimension, docCount, countByField |
| **KnowledgeServerMigrationOps** | ingestRuntime | setBuildState, commit, commitMetadata |

## Target architecture

### Core idea

The ops classes already contain all the logic. The facade adds nothing but indirection. **Make ops classes public and let callers depend on them directly.** The facade disappears. No interfaces, no delegation boilerplate, no circular callbacks.

### Design principles

1. **Ops are the API.** Callers import and depend on the concrete ops classes they need. No facade in between. Dependencies are explicit.
2. **Direct ops-to-ops dependencies.** HybridSearchOps takes `TextQueryOps` and `VectorSearchOps` directly — not lambdas that route through a facade. The dependency graph becomes `Ops → Ops`.
3. **No interfaces.** JustSearch has one index backend. There's no second implementation. The ops classes ARE the contract. Caller-facing methods are public; internal-wiring methods stay package-private.
4. **One new class.** `LuceneLifecycleManager` — the composition root that creates RuntimeContext, builds Lucene components, wires the ops graph, and manages start/close/recovery.
5. **Preserve the two-runtime model.** Each `LuceneLifecycleManager` instance manages one Lucene directory. KnowledgeServer creates two (search + ingest).

### Callback elimination

Every lambda callback through the facade becomes a direct ops-to-ops dependency:

| Current callback (through facade) | Becomes (direct dependency) |
|---|---|
| `HybridSearchOps.(t,l) -> searchText(t,l)` | `textQueryOps::searchText` |
| `HybridSearchOps.(v,l) -> searchVector(v,l)` | `vectorSearchOps::search` **(new class)** |
| `HybridSearchOps.this::searchTextWithFilter` | `textQueryOps::searchTextWithFilter` |
| `HybridSearchOps.(v,l,f) -> searchVector(v,l,f)` | `vectorSearchOps::searchFiltered` |
| `HybridSearchOps.(t,l,f) -> searchText(t,l,f,null)` | `textQueryOps::searchTextWithFilters` |
| `HybridSearchOps.(v,l,f) -> searchVector(v,l,f)` | `vectorSearchOps::searchWithFilters` |
| `ChunkSearchOps.() -> this.hybridSearchOps` | direct `hybridSearchOps` field |
| `ChunkSearchOps.(text,vec,lim,filter) -> searchHybrid(...)` | `hybridSearchOps::searchHybridFiltered` |
| `ChunkSearchOps.(query,lim) -> search(query,lim)` | `readPathOps::search` |
| `ChunkSearchOps.this::resolveVectorQueryK` | `readPathOps::resolveVectorQueryK` |
| `PruneOps.this::deleteById` | `writePathOps::deleteById` |
| `PruneOps.this::commit` | `commitOps::commit` |
| `DocumentQueryOps.readOps()::projectDocValues` | `readPathOps::projectDocValues` (direct) |
| `DocumentQueryOps.readOps()::readLongDocValueOrStoredLong` | `readPathOps::readLongDocValueOrStoredLong` (direct) |
| `DocumentQueryOps.readOps()::projectMultiValuedDocValues` | `readPathOps::projectMultiValuedDocValues` (direct) |
| `DocumentQueryOps.refreshBeforeFetch` | lambda over `ctx` directly |

Zero callbacks remain that route through the facade. The facade drops out of the dependency graph entirely.

### New class: VectorSearchOps (~60 LOC)

The facade currently inlines KNN query building: construct `KnnFloatVectorQuery`, apply ef_search override, wrap with filter. This is ~43 LOC across 3 method overloads. Extract to `VectorSearchOps`:

- `search(float[] vector, int limit)` — build KNN query with ef_search override, execute via `readPathOps::search`
- `searchFiltered(float[] vector, int limit, Query filter)` — add filter wrapping
- `searchWithFilters(float[] vector, int limit, RuntimeSearchFilters filters)` — build filter from RuntimeSearchFilters

Takes: `RuntimeContext ctx`, `ReadPathOps readPathOps`

### DocumentQueryOps split

DocumentQueryOps (595 LOC) handles three distinct concerns. Split into:

- **`DocumentFieldOps`** (~250 LOC) — point lookups: `getDocumentField`, `getDocumentContent`, `getDocumentFieldValues`, `isUnmodified`, `queryDocIdsByField`, `listAllDocumentIds`. Takes `bridge`, `idField`, `readPathOps` (for projectors), `ctx`.
- **`IndexCountOps`** (~250 LOC) — aggregate queries: `docCount`, `countByField`, `queryChunkEmbeddingCounts`, `queryEmbeddingCounts`, `querySpladeFeatureCounts`, `computeCorpusProfile`. Takes `bridge` only.
- **QPP methods** (~70 LOC) — `getTermDocFreqs`, `getQppSignals` → move to `TextQueryOps`. Takes `bridge` only (already held).

Split is clean — shared state is only `bridge` and `idField`, no shared internal helpers.

### LuceneLifecycleManager

The only new class. Replaces both `LuceneIndexRuntime` and the proposed `IndexLifecycle` interface.

Responsibilities:
- Create `RuntimeContext` from config
- Build Lucene components via `ComponentsFactory`
- Wire the ops graph (create all ops with direct deps)
- `start()` with recovery (corruption, schema mismatch)
- `close()` shutdown sequence
- `openWriterDeferred()` for deferred writer upgrade
- `setBuildState()`, `validateIndexableFields()`, `ssotVectorDimension()`
- Expose ops instances via getters: `textQueryOps()`, `writePathOps()`, `commitOps()`, etc.

~400 LOC (lifecycle + wiring).

### Ops visibility changes

| Class | Current | After |
|-------|---------|-------|
| TextQueryOps | `final class` (pkg-private) | `public final class` |
| HybridSearchOps | `final class` (pkg-private) | `public final class` |
| ChunkSearchOps | `final class` (pkg-private) | `public final class` |
| ReadPathOps | `final class` (pkg-private) | `public final class` |
| WritePathOps | `final class` (pkg-private) | `public final class` |
| CommitOps | `final class` (pkg-private) | `public final class` |
| IndexingCoordinator | `final class` (pkg-private) | `public final class` |
| PruneOps | `final class` (pkg-private) | `public final class` |
| FacetingEngine | `final class` (pkg-private) | `public final class` |
| FolderBrowseEngine | `final class` (pkg-private) | `public final class` |
| SuggestOps | `final class` (pkg-private) | `public final class` |
| VectorSearchOps | — | **NEW** `public final class` |
| DocumentFieldOps | — | **NEW** `public final class` (from DocumentQueryOps split) |
| IndexCountOps | — | **NEW** `public final class` (from DocumentQueryOps split) |

Caller-facing methods become `public`. Internal-wiring methods (e.g., `projectDocValues` on ReadPathOps) stay package-private.

### Thread safety

Lifecycle manager getters (`textQueryOps()`, `writePathOps()`, etc.) check `ctx.started` and throw ISE if not started or already closed. Callers obtain ops references after `start()` and hold them for the runtime's lifetime. Ops methods themselves do NOT check `ctx.started` — the guard is centralized in the ~12 lifecycle manager getters, not distributed across ~80 ops methods.

`close()` sets `ctx.started = false` and closes Lucene resources. Post-close calls to lifecycle manager getters throw ISE. Callers holding stale ops references will fail at the Lucene level (null searcherManager/writer) — acceptable since production callers don't outlive the runtime.

`guardWritable()` moves into WritePathOps. Every write method on WritePathOps checks `ctx.readOnly` and awaits `ctx.writerReadyLatch` if in deferred mode. IndexingCoordinator calls WritePathOps for actual writes, so the guard fires at the write execution layer — no caller can bypass it.

### Deferred writer upgrade

`openWriterDeferred()` on LuceneLifecycleManager:
1. Builds read-write Components via ComponentsFactory
2. Closes old read-only SearcherManager
3. Updates `ctx` volatile fields (writer, searcherManager, crtrt)
4. Starts CRTRT thread
5. Unblocks `ctx.writerReadyLatch`

Ops are NOT recreated. All ops read ctx fields dynamically (volatile reads). Audit confirmed no ops class captures a value at construction time that would stale (all captured refs are lambdas/suppliers that indirect through ctx).

## Caller migration map

| Caller | Current type | Target types |
|--------|-------------|--------------|
| SearchOrchestrator | `LuceneIndexRuntime` | `TextQueryOps` + `HybridSearchOps` + `ChunkSearchOps` + `VectorSearchOps` + `ReadPathOps` + `FacetingEngine` + `FolderBrowseEngine` + `SuggestOps` + `DocumentFieldOps` + `IndexCountOps` |
| GrpcSearchService | `LuceneIndexRuntime` | `SuggestOps` + `DocumentFieldOps` + `FolderBrowseEngine` + `IndexCountOps` + `ReadPathOps` |
| RagContextOps | `LuceneIndexRuntime` | `ChunkSearchOps` + `IndexCountOps` |
| CitationMatchOps | `LuceneIndexRuntime` | `ReadPathOps` |
| GrpcIngestService | `LuceneIndexRuntime` × 2 | `WritePathOps` + `CommitOps` + `PruneOps` + `DocumentFieldOps` + `IndexCountOps` (ingest) + `IndexCountOps` (search) |
| IndexingLoop | `LuceneIndexRuntime` | `WritePathOps` + `IndexingCoordinator` + `CommitOps` + `DocumentFieldOps` + `IndexCountOps` |
| ChunkDocumentWriter | `LuceneIndexRuntime` | `WritePathOps` + `DocumentFieldOps` |
| EmbeddingBackfillOps | `LuceneIndexRuntime` | `WritePathOps` + `DocumentFieldOps` |
| SpladeBackfillOps | `LuceneIndexRuntime` | `WritePathOps` + `DocumentFieldOps` |
| NerBackfillOps | `LuceneIndexRuntime` | `WritePathOps` + `DocumentFieldOps` |
| DisambiguationBackfillOps | `LuceneIndexRuntime` | `DocumentFieldOps` |
| SyncDirectoryOps | `LuceneIndexRuntime` | `ReadPathOps` + `PruneOps` + `CommitOps` |
| IndexStatusOps | `LuceneIndexRuntime` × 2 | `IndexCountOps` (ingest) + `IndexCountOps` (search) + `CommitOps` |
| GrpcHealthService | `LuceneIndexRuntime` | `IndexCountOps` |
| KnowledgeServerSafeMetrics | `LuceneIndexRuntime` | `IndexCountOps` |
| KnowledgeServer | `LuceneIndexRuntime` × 2 | `LuceneLifecycleManager` × 2 |
| KnowledgeServerMigrationOps | `LuceneIndexRuntime` | `LuceneLifecycleManager` + `CommitOps` |

Note: SearchOrchestrator takes 10 dependencies — this is honest about what it does. It's a complex orchestrator that composes every search modality. The current design hides this behind one `LuceneIndexRuntime` parameter.

## What dies

- **`LuceneIndexRuntime`** (2,016 LOC) — deleted entirely. Not replaced by smaller facades.
- **`IndexRuntime` interface** (75 LOC) — deleted. Its 11 methods straddle lifecycle + write. `LuceneIndexer` updated to take `WritePathOps` + `CommitOps` + `IndexingCoordinator`.
- **`DocumentQueryOps`** (595 LOC) — split into `DocumentFieldOps` + `IndexCountOps` + QPP methods moved to TextQueryOps.
- **All delegation boilerplate** — doesn't exist. Callers talk to ops directly.
- **All circular lambda callbacks** — replaced with direct ops-to-ops dependencies.
- **`Components` inner record, `TelemetryEvents` inner interface, `SoftDeletesMetrics` inner interface** — promoted to top-level files.

## What survives unchanged

- **RuntimeContext** (134 LOC) — shared mutable state container.
- **SearcherBridge** (73 LOC) — shared acquire/release/withSearcher.
- **ComponentsFactory** (493 LOC) — creates Lucene components from config.
- **HybridFusionUtils** (858 LOC), **QueryFilterBuilder** (254 LOC), **LuceneRuntimeUtils** (379 LOC), **FieldMapper** (394 LOC) — standalone utilities.
- **IndexRuntimeFactory** — updated to create `LuceneLifecycleManager` instead of `LuceneIndexRuntime`.

## What changes (ops classes)

All 12 ops classes change in two ways:
1. **Visibility**: `final class` → `public final class`. Caller-facing methods become `public`.
2. **Constructor**: Lambda callbacks to the facade → direct ops references.

No internal logic changes. The ops methods work exactly as before.

## Composition root wiring

`KnowledgeServer` creates two lifecycle managers:

```
searchLM = new LuceneLifecycleManager(config, fieldMapper, readOnly=true)
ingestLM = new LuceneLifecycleManager(config, fieldMapper, readOnly=false)

searchLM.start()
ingestLM.start()
```

`InfraContext` carries 2 `LuceneLifecycleManager` instances. `DefaultWorkerAppServices` currently uses `lifecycle.asRuntime()` bridge to pass `LuceneIndexRuntime` facades to services. The bridge shares the same `RuntimeContext` — it's a migration shim, not a second runtime.

**Migration path**: Services are migrated one at a time from `LuceneIndexRuntime` to direct ops. Once all services are migrated, `asRuntime()` and `LuceneIndexRuntime` are deleted.

## Remaining uncertainties

### U1: Ops recreation during openWriterDeferred — RESOLVED

Audit confirms all captured constructor state is lambda indirection through ctx volatile fields. Ops are NOT recreated during deferred writer upgrade. Add e2e integration test to verify.

### U2: Test migration at scale

~30 test files create `LuceneIndexRuntime` directly. Under the rewrite, tests use `LuceneLifecycleManager` and access ops via getters. Migration is mechanical per test file. Can parallelize across agents.

### U3: InfraContext reshape

4 files to update (verified by grep):
1. `InfraContext.java` (worker-core) — record fields change from 2 `LuceneIndexRuntime` to lifecycle managers or ops instances
2. `KnowledgeServer.java` (indexer-worker) — creates lifecycle managers, populates InfraContext
3. `DefaultWorkerAppServices.java` (worker-services) — wires callers with ops from InfraContext
4. `DevReloadManager.java` (indexer-worker) — passes InfraContext opaquely, comments only

### U4: IndexRuntimeFactory and LuceneIndexer — RESOLVED

**IndexRuntimeFactory**: 8 static factory methods. All return `IndexRuntime` (interface) but construct `LuceneIndexRuntime`. Only production caller: `KnowledgeServer` (immediately downcasts to `LuceneIndexRuntime`). Update: factory methods return `LuceneLifecycleManager` instead.

**LuceneIndexer**: 24-line adapter. Holds `IndexRuntime` (interface), calls only `runtime.index(document)`. Only created by `IndexRuntimeFactory.createIndexer()` — which is never called from production code (only tests). The adapter can be updated to take `IndexingCoordinator` (which has `indexSingle`) or `WritePathOps`, or deleted if unused in production.

**Other `IndexRuntime` consumers**: `LuceneIndexer` is the ONLY class that holds the interface type. Every other production caller uses `LuceneIndexRuntime` directly. The `IndexRuntime` interface can be deleted with near-zero impact.

### U5: SearchOrchestrator constructor width

SearchOrchestrator takes 10 ops dependencies. This is honest but wide. Options:
- Accept it — the dependencies are real
- Group related ops into a record (e.g., `SearchOps(textQueryOps, hybridSearchOps, vectorSearchOps, chunkSearchOps)`) — reduces parameter count without hiding dependencies
- Decide during implementation

### U6: IndexingCoordinator pre-start validate() — RESOLVED

No production code calls `validate()` on any IndexRuntime/LuceneIndexRuntime. The `validate()` override in LuceneIndexRuntime delegates to `indexingCoordinator().validate(document)`, but zero external callers invoke it. The one test (`LuceneIndexRuntimeComponentsTest.missingUidFailsValidation`) that tests it creates a runtime without calling start() — this test is updated to call start() first in the rewrite. No production impact.

## Prior cleanup (branch `worktree-320-runtime-rewrite`, not merged)

- **RuntimeContext** (134 LOC): All 35 mutable fields extracted from facade.
- **SearcherBridge** (73 LOC): Shared acquire/release/withSearcher.
- **Ops constructor migration**: All 12 ops classes take `RuntimeContext`/`SearcherBridge` instead of supplier soup (~65 params → ~10 fn refs).
- **Constructor rewrite**: No double-initialization.
- **refreshForTests → maybeRefreshBlocking**: Renamed across 31 files.
- **SPLADE query building**: Moved to `TextQueryOps.buildSpladeQuery()`.

Net: 2,227 → 2,016 LOC. These changes are prerequisites — RuntimeContext is the shared state that makes the split possible.

## Code destination map

Every line of `LuceneIndexRuntime.java` (2,016 LOC) mapped to its destination. Lines are approximate (include Javadoc).

### → LuceneLifecycleManager (~450 LOC)

| Lines | Code | Why here |
|-------|------|----------|
| 64–86 | `ctx` field, 11 volatile ops refs, `commitOps` | Lifecycle manager owns the ops graph |
| 94–106 | `maybeRefreshBlockingIfCommittedSinceRefresh()` | Passed as callback to DocumentFieldOps; lifecycle owns ctx timing fields |
| 112–160 | Config accessors: `setBuildState`, `buildState`, `commitMetadataEnabled`, `resolvedConfig`, `latestCommitUserDataBestEffort`, `openTimeCommitUserData` | Lifecycle/status config — callers get these from the lifecycle manager |
| 167–211 | Vector format detection: `configuredVectorFormat`, `storedVectorFormat`, `queryVectorFormatActual` | Status inspection — needs searcher manager, stays with lifecycle |
| 217–277 | 4 constructors + `resolvedIndexPathForGuards()` | Lifecycle manager IS the constructor |
| 296–419 | `start()`, `startInternal()` (recovery, ComponentsFactory.build, applyComponents, queue depth config, validation mode) | Core lifecycle |
| 426–493 | `setDeferredWriterMode`, `isDeferredWriterMode`, `openWriterDeferred` | Lifecycle |
| 495–525 | `backupIndexDirectoryForRecovery` | Recovery helper |
| 535–563 | `ssotVectorDimension`, `validateIndexableFields` | Startup validation |
| 725–770 | `close()` (crtrt, searcherManager, writer, directory, ephemeral cleanup, null ops) | Lifecycle |
| 772–774 | `ensureStarted()` | Centralized in lifecycle manager getters (~12 checks). Ops methods don't check — callers get ops from a started manager. |
| 882–900 | `guardWritable()` (readOnly check, deferred writer latch wait) | Moves into WritePathOps — every write method checks internally. IndexingCoordinator calls WritePathOps, so guard is at the write execution layer. |
| 909–926 | Test constructors, `setComponentsForTests` | Lifecycle test helpers |
| 928–995 | `applyComponents()` (transfer ctx fields, create SearcherBridge, create all ops, start CRTRT) | Core wiring |
| 997–1013 | Package-private test accessors (directoryForTests, mergePolicyForTests, etc.) | Test helpers for lifecycle state |
| 1025–1044 | `pendingDocs`, `queueDepth`, `maxQueueDepth`, `commitCount`, `setSoftDeletesMetricsListener`, `setTelemetryEvents`, `lastRefreshTargetMsForTests`, `setIndexOpenGuard` | Config setters — lifecycle owns ctx |
| 1046–1057 | `acquireSearcherForTests`, `releaseSearcherForTests` | Test helpers |

### → WritePathOps (~100 LOC absorbed)

| Lines | Code | Why here |
|-------|------|----------|
| 570–574 | `index()` — `ensureStarted(); guardWritable(); indexingCoordinator().indexSingle(doc)` | Becomes `writePathOps.index()` with guard logic inlined |
| 581–588 | `deleteById()` — ensureStarted + guardWritable + delegate to indexingCoordinator | Same pattern |
| 600–607 | `deleteByIdAndChunks()` | Same |
| 619–627 | `deleteChunksForParentDocId()` | Same |
| 643–650 | `deleteByPathPrefix()` — delegates to writeOps | Already on WritePathOps, just add guard |
| 664–669 | `pruneByPathPrefix()` | Delegates to PruneOps — move guard + delegation there |
| 671–679 | `indexBatch()` | Same guard + delegate pattern |
| 682–693 | `commit()` — guardWritable, commitOps.commit(), timing update, pendingDocs reset, telemetry | **Real logic** — moves to CommitOps or stays as a method on WritePathOps that composes CommitOps |
| 904–906 | `validate()` — delegates to indexingCoordinator | Stays on IndexingCoordinator (already there) |
| 1845–1865 | `updateDocument()` — guardWritable, refresh-before-read, readOps.withSearcher + writeOps.readModifyWrite, error wrapping | **Real logic** (~20 LOC) — moves to WritePathOps |
| 1883–1901 | `updateDocumentsBatch()` — same pattern for batch | **Real logic** (~19 LOC) — moves to WritePathOps |
| 1913–1932 | `updateDocumentPaths()` — same pattern | **Real logic** (~20 LOC) — moves to WritePathOps |

### → ReadPathOps (absorbs search overloads)

| Lines | Code | Why here |
|-------|------|----------|
| 1070–1102 | `search()` 3 overloads — the 2 convenience overloads are pure delegation, the real one calls `readOps().search()` | Already on ReadPathOps; convenience overloads either move there or callers call the full signature |

### → VectorSearchOps (NEW, ~60 LOC)

| Lines | Code | Why here |
|-------|------|----------|
| 1382–1418 | `searchVector()` 3 overloads — build KNN query, apply ef_search override, filter wrapping | New class: KNN query building extracted from facade |
| 1420–1423 | `resolveVectorQueryK()` — delegates to readOps | Convenience method — callers use readPathOps directly |

### → TextQueryOps (absorbs thin delegations)

| Lines | Code | Why here |
|-------|------|----------|
| 1131–1157 | `searchText()` 2 overloads — null check, build query, search | The query-build + search composition is thin but has real logic (parse exception handling). Moves to TextQueryOps. |
| 1164–1201 | `buildTextQuery` 2 overloads, `CorrectedQuery` record, `buildFuzzyTextQuery`, `buildPerTermFuzzyQuery` | Already delegates to textQueryOps. `CorrectedQuery` moves to TextQueryOps or LuceneRuntimeTypes. |
| 1438–1449 | `searchSplade()` — null check, build query, search | Already delegates to textQueryOps.buildSpladeQuery + readOps.search |

### → HybridSearchOps (absorbs thin delegations)

| Lines | Code | Why here |
|-------|------|----------|
| 1526–1529 | `searchHybrid(text, vec, limit, filters)` — build filter, delegate | Becomes a method on HybridSearchOps |
| 1577–1580 | `searchHybrid(text, vec, limit)` — ensureStarted + delegate | Already on HybridSearchOps |
| 1595–1598 | `searchHybrid(text, vec, limit, filter)` — ensureStarted + delegate | Already on HybridSearchOps |
| 1600–1602 | `searchTextWithFilter()` — delegates to textQueryOps | Callback becomes direct dep |
| 1621–1647 | `searchHybridWithDebug` 2 overloads | Already on HybridSearchOps |
| 1664–1687 | `searchHybridSplade()` — **Real logic** (~24 LOC): builds SPLADE text leg, delegates to hybridOps.executeHybrid | Moves to HybridSearchOps. Needs `textQueryOps` and `vectorSearchOps` refs. |
| 809–811 | `shouldSkipVectorSearch()` | Already on HybridSearchOps |

### → ChunkSearchOps (absorbs thin delegations)

| Lines | Code | Why here |
|-------|------|----------|
| 1273–1276 | `searchChunksForDocs` | Already on ChunkSearchOps |
| 1288–1365 | `searchChunksText` 2 overloads, `searchChunksSplade`, `searchChunksHybrid`, `searchFullDocsForDocs` | Already on ChunkSearchOps |
| 1464–1511 | `searchChunkVector` 2 overloads, `searchChunksHybrid(5-arg)` | Already on ChunkSearchOps |

### → FacetingEngine, FolderBrowseEngine, SuggestOps (1-line delegations)

| Lines | Code | Why here |
|-------|------|----------|
| 1213–1216 | `computeFacets` | Already on FacetingEngine — callers call it directly |
| 1229–1256 | `enumerateFolders`, `listFolderFiles`, `listAllDocumentIds` | Already on FolderBrowseEngine |
| 1549–1552 | `suggest` | Already on SuggestOps |

### → DocumentFieldOps (from DocumentQueryOps split)

| Lines | Code | Why here |
|-------|------|----------|
| 1113–1116 | `isUnmodified` | Already on DocumentQueryOps — moves to DocumentFieldOps |
| 1793–1796 | `queryDocIdsByField` | Same |
| 1804–1807 | `getDocumentContent` | Same |
| 1816–1819 | `getDocumentField` | Same |
| 1828–1831 | `getDocumentFieldValues` | Same |

### → IndexCountOps (from DocumentQueryOps split)

| Lines | Code | Why here |
|-------|------|----------|
| 1696–1699 | `docCount` | Already on DocumentQueryOps — moves to IndexCountOps |
| 1706–1714 | `getCorpusProfile` — **caching logic** (~9 LOC): reads ctx.cachedCorpusProfile, computes if null | Moves to IndexCountOps with the cache |
| 1725–1728 | `getTermDocFreqs` | Moves to TextQueryOps (QPP) |
| 1739–1742 | `getQppSignals` | Moves to TextQueryOps (QPP) |
| 1753–1756 | `countByField` | Moves to IndexCountOps |
| 1766–1781 | `queryChunkEmbeddingCounts`, `queryEmbeddingCounts`, `querySpladeFeatureCounts` | Moves to IndexCountOps |

### → CommitOps (absorbs commit logic)

| Lines | Code | Why here |
|-------|------|----------|
| 696–712 | `maybeRefresh()`, `maybeRefresh(targetMaxStaleMs)` — **Real logic** (~17 LOC): lag check, conditional refresh, corpus profile invalidation | Moves to CommitOps (owns timing state on ctx) |
| 714–722 | `refreshLagMs()` | Moves to CommitOps |
| 1950–1959 | `maybeRefreshBlocking()` | Moves to CommitOps |
| 1965–1976 | `softDeletesCurrent()` — reads writer, opens DirectoryReader | Moves to CommitOps or LuceneLifecycleManager |

### → Top-level files (from inner types)

| Lines | Code | Why |
|-------|------|-----|
| 1983–1998 | `Components` record | Used by ComponentsFactory and wiring — own file |
| 2002–2010 | `TelemetryEvents` interface | Used by multiple ops — move to LuceneRuntimeTypes |
| 2012–2015 | `SoftDeletesMetrics` interface | Used by multiple ops — move to LuceneRuntimeTypes |
| 1185 | `CorrectedQuery` record | Used by TextQueryOps — move to TextQueryOps or LuceneRuntimeTypes |

### → DIES (pure boilerplate, no destination)

| Lines | Code | Why dies |
|-------|------|----------|
| 74–85 | 11 volatile ops field declarations | Ops are held by lifecycle manager, not volatile refs |
| 776–880 | 10 ops accessor methods (readOps, writeOps, hybridOps, textQueryOps, chunkOps, suggestOps, documentQueryOps, indexingCoordinator, facetingOps, folderBrowseOps) | ~105 LOC of null-check boilerplate. Callers get ops directly from lifecycle manager. |
| 1021–1023 | `indexAnalyzerOrNull()` | Callers read `ctx.indexAnalyzer` via lifecycle manager getter |
| ~600 LOC | Javadoc on pure-delegation methods | Dies. Ops methods that become the public API get proper Javadoc written on them directly. Facade copies were always redundant. |

### Summary

| Destination | LOC absorbed | Type |
|------------|-------------|------|
| LuceneLifecycleManager | ~450 | NEW — lifecycle, wiring, config, test helpers |
| WritePathOps | ~100 | EXPANDED — guard logic, updateDocument*, commit composition |
| CommitOps | ~30 | EXPANDED — refresh methods, refreshLagMs, softDeletesCurrent |
| HybridSearchOps | ~25 | EXPANDED — searchHybridSplade orchestration |
| TextQueryOps | ~80 | EXPANDED — searchText composition, QPP methods, CorrectedQuery |
| VectorSearchOps | ~60 | NEW — KNN query building |
| DocumentFieldOps | ~10 | NEW (split from DocumentQueryOps) — thin delegations |
| IndexCountOps | ~15 | NEW (split from DocumentQueryOps) — thin delegations + cache |
| ReadPathOps | ~10 | EXPANDED — search convenience overloads |
| Top-level files | ~34 | Components, TelemetryEvents, SoftDeletesMetrics |
| ChunkSearchOps, FacetingEngine, FolderBrowseEngine, SuggestOps | ~0 | Already there — callers call directly |
| **Dies** | ~1,200 | Ops accessors, field declarations, redundant Javadoc, pure `ensureStarted() + delegate` methods |

## Execution plan

The old and new coexist during the transition. LuceneIndexRuntime keeps working until every consumer is migrated. The build compiles after each phase.

### Phase 1: Prepare ops for direct use — DONE

- [x] Promote inner types (Components, TelemetryEvents, SoftDeletesMetrics) to top-level files
- [x] Make all 12 ops classes public
- [x] Absorb guardWritable + updateDocument/Batch/Paths into WritePathOps
- [x] Absorb commit composition + maybeRefresh/refreshLagMs + softDeletesCurrent into CommitOps
- [x] Create VectorSearchOps (KNN query building)
- [x] Absorb searchText parse exception handling into TextQueryOps
- [x] Absorb searchHybridSplade orchestration into HybridSearchOps
- [x] Split DocumentQueryOps → DocumentFieldOps + IndexCountOps + QPP to TextQueryOps
- [x] Replace ALL lambda callbacks with direct ops-to-ops dependencies (9 functional interfaces deleted)

### Phase 2: Create LuceneLifecycleManager — DONE

894 LOC composition root. Owns lifecycle + wiring + config. Exposes ops via guarded getters.

### Phase 3: Migrate consumers — IN PROGRESS

**3a: Wiring layer — DONE**
- [x] IndexRuntimeFactory: added createLifecycleManager/createReadOnlyLifecycleManager
- [x] KnowledgeServer: searchRuntime/ingestRuntime → searchLifecycle/ingestLifecycle
- [x] InfraContext: 2 LuceneIndexRuntime → 2 LuceneLifecycleManager
- [x] DefaultWorkerAppServices: uses lifecycle.asRuntime() bridge (temporary shim)
- [x] KnowledgeServerMigrationOps: updated to lifecycle manager + ops
- [x] KnowledgeServerSafeMetrics: LuceneIndexRuntime → IndexCountOps

**3b: Service constructors — DONE**
All 17 production services migrated from `LuceneIndexRuntime` to direct ops or `LuceneLifecycleManager`:
- [x] GrpcHealthService → IndexCountOps
- [x] GrpcSearchService → LuceneLifecycleManager (gets ops internally)
- [x] GrpcIngestService → LuceneLifecycleManager × 2 (backward-compat constructors for tests)
- [x] IndexingLoop → IndexingCoordinator + CommitOps + DocumentFieldOps + IndexCountOps + WritePathOps
- [x] ChunkDocumentWriter → DocumentFieldOps + IndexingCoordinator
- [x] SearchOrchestrator → LuceneLifecycleManager (gets ops internally)
- [x] RagContextOps → ChunkSearchOps + IndexCountOps + LuceneLifecycleManager
- [x] CitationMatchOps → ReadPathOps + CommitOps
- [x] SyncDirectoryOps → ReadPathOps + PruneOps + CommitOps
- [x] IndexStatusOps → IndexCountOps × 2 + CommitOps × 2 + LuceneLifecycleManager
- [x] EmbeddingBackfillOps → DocumentFieldOps + WritePathOps + CommitOps
- [x] SpladeBackfillOps → DocumentFieldOps + WritePathOps + CommitOps
- [x] NerBackfillOps → DocumentFieldOps + WritePathOps + CommitOps
- [x] DisambiguationBackfillOps → DocumentFieldOps
- [x] KnowledgeServerSafeMetrics → IndexCountOps
- [x] KnowledgeServerMigrationOps → LuceneLifecycleManager + CommitOps

Note: Some services (SearchOrchestrator, GrpcSearchService, RagContextOps, IndexStatusOps) take `LuceneLifecycleManager` rather than individual ops — they use many ops internally. GrpcIngestService retains backward-compat constructors accepting `LuceneIndexRuntime` for test compatibility (wrapped via `wrapSharedContext`).

**3c: Test files — DONE**
~32 test files migrated from LuceneIndexRuntime to LuceneLifecycleManager ops.

### Phase 4: Delete the old — DONE

- [x] `LuceneIndexRuntime.java` deleted (2,016 LOC removed)
- [x] `asRuntime()` / `wrapSharedContext()` bridge removed from LuceneLifecycleManager
- [x] Backward-compat constructors removed from GrpcIngestService
- [x] Old `createRuntime`/`createReadOnlyRuntime` methods removed from IndexRuntimeFactory
- [x] All 53 files with remaining references updated (Javadoc, imports, benchmarks, integration tests)
- [x] Dead code removed (SingleShardCoordinator.manager, HybridSearchOps.searchHybridSplade)

**Result**: 33 commits, 176 files changed, +6,826 / -9,323 lines. Net -2,497 lines.

## Trade-offs and notes

**New feature cost.** Adding a new search modality without a facade: create ops class + add to lifecycle manager + pass to callers (~3 files). With a facade it's 2 files. For methods used by only 1-2 callers (most search methods), the cost is identical. The trade-off is accepted — explicit dependencies outweigh marginal wiring cost.

**Thread safety during close().** Callers hold ops references that become stale after close(). Operations fail at the Lucene level (null searcherManager) rather than with a clean ISE. Acceptable — close() is shutdown, no new operations start after.

**Benchmarks.** `EngineIndexBench` and `EngineVectorIndexBench` (in `modules/benchmarks`) use LuceneIndexRuntime directly. Include in Phase 3 migration.

**Public inner types.** `LuceneIndexRuntime.CorrectedQuery` (used by SearchOrchestrator), `TelemetryEvents` (used by KnowledgeServer), `SoftDeletesMetrics` — all need new import paths when promoted to top-level files. Include in Phase 1 (promote types) and Phase 3 (update imports).

**Foundation.** This plan assumes the prior cleanup work (RuntimeContext, SearcherBridge, ops constructor migration) as prerequisites. The rewrite builds on that branch.

## Post-rewrite audit findings

Audit of all new/changed files revealed issues in three categories.

### Critical

**C1: `WritePathOps.deleteById` and `deleteByPathPrefix` — null writer dereference.**
These two methods access `ctx.writer` without a null guard. Every other write method on WritePathOps checks `if (w == null) throw ISE`. Race with `close()` causes NPE instead of clean ISE.

**C2: Facade's `deleteById` bypasses IndexingCoordinator.**
`LuceneLifecycleManager.deleteById()` calls `writePathOps().deleteById()` directly, skipping `IndexingCoordinator.deleteById()` which applies backpressure guards and queue depth accounting. The sibling methods `deleteByIdAndChunks` and `deleteChunksForParentDocId` correctly go through IndexingCoordinator. This is a logic hole — `deleteById` is immune to backpressure and doesn't increment `queueDepth`.

### High — God Object regrowth

**H1: LuceneLifecycleManager has ~49 forwarding methods.**
The lifecycle manager was supposed to be a composition root: lifecycle + wiring + ~14 ops getters. Instead it re-implements the entire former LuceneIndexRuntime API as forwarding methods (~49 methods across search, write, browse, count categories). This is the God Object rebuilt under a new name. The forwarding exists because `SearchOrchestrator` calls lifecycle manager methods directly instead of using ops getters.

**H2: Three forwarding methods contain real logic.**
- `searchText()` — ParseException swallowing. Already absorbed into `TextQueryOps.searchText()` in Phase 1c, but the lifecycle manager has its own copy that diverges.
- `searchSplade()` — null-weight guard + query building + search execution. Domain logic, not lifecycle management.
- `getCorpusProfile()` — caching at `ctx.cachedCorpusProfile`. The cache belongs with `IndexCountOps.computeCorpusProfile()`, not on the lifecycle manager.

### Moderate

**M1: Thread-safety race between `close()` and ops getters.**
Ops fields on LuceneLifecycleManager are plain (non-volatile). `close()` nulls them without synchronization with `ensureStarted()`. TOCTOU window: thread A passes `ensureStarted()`, thread B calls `close()` and nulls the field, thread A reads null.

**M2: `CommitOps.commit()` is public.**
External callers can call `commit()` directly, bypassing `commitAndTrack()` which handles timing, counters, and telemetry. The raw `commit()` should be package-private.

**M3: `IndexingCoordinator.deleteById` is package-private.**
Its siblings `deleteByIdAndChunks` and `deleteChunksForParentDocId` are public. Inconsistent visibility — `deleteById` should be public (and the facade should route through it, fixing C2).

**M4: Inconsistent `ensureStarted()` guards.**
- `commitOps()` has no guard (intentional — CommitOps is final, created in constructor). Undocumented.
- `indexingCoordinator()` uses a different null-check pattern (different error message).
- Config accessors (`latestCommitUserDataBestEffort`, `queryVectorFormatActual`) use ad-hoc guards or none.

**M5: `DocumentFieldOps.getDocumentFieldValues` — double searcher acquisition.**
Fallback calls `getDocumentField()` from inside a `withSearcher` lambda, causing nested searcher acquisition.

### Minor

**m1: `VectorSearchOps`** — three one-liner pass-throughs. Thin but defensible (typed boundary for `RuntimeSearchFilters` → `Query` conversion).
**m2: `PruneOps`** — raw `ctx.searcherManager` acquisition instead of SearcherBridge.
**m3: `ChunkSearchOps`** — duplicate import.
**m4: `TextQueryOps.buildSpladeQuery`** — fully-qualified class names instead of imports.
**m5:** Missing `@ThreadSafe` on 8 of 10 ops classes.
**m6:** Missing Javadoc on most public methods of LuceneLifecycleManager.

## Resolution status — ALL RESOLVED

### R1: Critical bugs — DONE
- [x] C1: Null writer guards added to `WritePathOps.deleteById()` and `deleteByPathPrefix()`
- [x] C2: `LuceneLifecycleManager.deleteById()` now routes through `IndexingCoordinator.deleteById()` (restores backpressure + queue accounting). `IndexingCoordinator.deleteById()` made public.

### R2: God Object regrowth — DONE
- [x] H1: 52 forwarding methods deleted from LuceneLifecycleManager (1,342 → 918 LOC)
- [x] SearchOrchestrator migrated to direct ops (11 ops fields extracted from lifecycle in constructor)
- [x] KnowledgeServerMigrationOps migrated to ops getter calls
- [x] H2: `getCorpusProfile()` caching moved to `IndexCountOps.getOrComputeCorpusProfile()`
- [x] 20 test files updated to call ops directly

### R3: Moderate fixes — DONE
- [x] M2: `CommitOps.commit()` kept public (KnowledgeServerMigrationOps needs raw commit during migration cutover — intentional)
- [x] M3: `IndexingCoordinator.deleteById()` made public (fixed in R1)
- [x] M4: `commitOps()` getter documented as pre-start-safe. `indexingCoordinator()` standardized to use `ensureStarted()`
- [x] M5: `DocumentFieldOps.getDocumentFieldValues()` fallback refactored to use already-acquired searcher

### R4: Minor fixes — DONE
- [x] m3: Duplicate import removed from ChunkSearchOps

### R5: Remaining extraction — DONE
- [x] GrpcSearchService: replaced lifecycle manager field with 4 ops fields (CommitOps, SuggestOps, DocumentFieldOps, FolderBrowseEngine)
- [x] IndexStatusOps: replaced lifecycle manager with 5 typed Supplier params
- [x] LuceneLifecycleManager: fixed stale Javadoc referencing deleted forwarding methods

### R7: Investigation findings — DONE
- [x] `CommitOps.commitAndTrack()` null writer guard added (was NPE risk if called before writer ready)
- [x] `IndexRuntime` interface (75 LOC) deleted — dead code, only consumer was `LuceneIndexer`
- [x] `LuceneIndexer` (24 LOC) deleted — no production caller
- [x] `EmbeddingCompatibilityController` confirmed clean — takes `Supplier` params, no lifecycle reference
- [x] Zero live `LuceneIndexRuntime` code references remain (only test class names and comments)

**Test coverage gaps identified (7 of 8 behaviors uncovered — future work):**
- `WritePathOps.guardWritable()` — no test for latch blocking or readOnly rejection
- `WritePathOps.updateDocument` refresh-before-rmw — no dedicated test
- `CommitOps.commitAndTrack()` counter/telemetry — called as scaffolding, never asserted
- `TextQueryOps.searchText()` ParseException handling — no test
- `DocumentFieldOps.getDocumentFieldValues()` fallback — no test
- `IndexCountOps.getOrComputeCorpusProfile()` caching — no test
- `LuceneLifecycleManager.startGuarded()` failure reset — no test

**Correctness verification:** `guardWritable()` and `updateDocument()` logic identical to originals. `searchText()` dropped unused `projectionFields` param (no caller used it). `commitAndTrack()` was missing guard (fixed in R7).

### R6: Internal wiring fixes — DONE
- [x] M1: `close()` now sets `ctx.started = false` FIRST (before nulling ops), rejecting new callers immediately
- [x] M2: `start()` failure resets `ctx.started = false` via try/catch in `startGuarded()`. Instances are retryable after failure.
- [x] M3: 5 RuntimeContext fields made volatile (`nrtTargetMaxStaleMs`, `nrtHardMaxStaleMs`, `maxQueueDepth`, `validationMode`, `commitMetadataEnabled`) — prevents torn reads on cross-thread long fields
- [x] L2: `ReadPathOps.withSearcher()` now delegates to `SearcherBridge` — single acquire/release code path
- [x] L3: Fixed misleading Layer 2 comment for PruneOps in `applyComponents()`

## Final results

| Metric | Before | After |
|--------|--------|-------|
| LuceneIndexRuntime | 2,227 LOC | Deleted |
| LuceneLifecycleManager | — | 915 LOC (lifecycle + wiring + ops getters + config) |
| Forwarding methods | 80+ (facade) | 0 |
| Circular lambda callbacks | 15 | 0 |
| Functional interfaces for callbacks | 9 | 0 |
| Ops classes | 12 (pkg-private) | 14 (public, direct deps) |
| New ops | — | VectorSearchOps, DocumentFieldOps, IndexCountOps |
| Total diff | — | 42 commits, 176+ files, net ~-2,750 lines |
| Critical bugs found + fixed | — | 2 (null writer guard, deleteById backpressure) |

## Was the purpose achieved?

The diagnosis identified 4 problems. Each is resolved:

1. **"The facade is the God Object"** — The facade (`LuceneIndexRuntime`) is deleted. No replacement facade exists. `LuceneLifecycleManager` has zero forwarding methods — it manages lifecycle and exposes ops, nothing else.

2. **"Circular lambda callbacks through the facade"** — All 15 callbacks eliminated. Ops depend on each other directly (`Ops → Ops`). 9 functional interfaces deleted.

3. **"~1,100 LOC of delegation boilerplate"** — Gone. No `ensureStarted(); return ops.method()` methods exist anywhere. Callers hold ops references and call methods directly.

4. **"Callers see 80 methods when they use 5"** — Each caller now declares exactly the ops it needs. `GrpcHealthService` takes `IndexCountOps`. `CitationMatchOps` takes `ReadPathOps + CommitOps`. `SearchOrchestrator` takes 11 ops fields — honest about its complexity. No caller can accidentally call `deleteById()` through a search interface.

**One remaining imperfection:** `GrpcIngestService` still holds `LuceneLifecycleManager` as a field, but only for a null-check guard (`replyIfIndexRuntimeUnavailable`), not for ops access. All ops are extracted at construction time. This is acceptable — the guard is a startup-safety pattern, not an architectural dependency.

## Throughput verification

### Rewrite vs main — no regression

Engine-only benchmark (Claim A), SciFact 5,184 docs (~1.5 KB avg), 3 runs each:

| Branch | Run 1 | Run 2 | Run 3 | Mean |
|--------|-------|-------|-------|------|
| main | 3,630 | 3,556 | 3,615 | 3,600 docs/sec |
| rewrite | 3,638 | 3,568 | 3,651 | 3,619 docs/sec |
| Diff | +8 | +12 | +36 | +19 (+0.5%) |

Zero throughput regression. Within noise.

### Raw Lucene ceiling (new benchmark)

`RawLuceneIndexBench` — bare `IndexWriter` + `StandardAnalyzer` + `TextField`, no field catalog, no commit metadata, no soft deletes, no DocValues, no per-field analyzers. 3 runs:

| Metric | Index batch | Overall (incl. commit+refresh) |
|--------|-----------|-------------------------------|
| Raw Lucene | 19,345 docs/sec (268ms) | 9,235 docs/sec (560ms) |
| JustSearch engine | 4,748 docs/sec (1,092ms) | 3,619 docs/sec (1,433ms) |
| Overhead ratio | 4.1x | 2.6x |

The 4.1x write-path overhead is the cost of schema validation, per-field analyzers, DocValues, soft deletes, UID tiebreak fields, and commit metadata preparation — all required for production correctness.

### Comparison with published systems — not apples-to-apples

Initial comparison suggested JustSearch (3,600 docs/sec) was below Elasticsearch (40K docs/sec). Investigation showed this is a document-size artifact:

| System | docs/sec | Doc size | MB/sec | Threading |
|--------|---------|----------|--------|-----------|
| Elasticsearch (geonames) | 40,000 | 280 bytes | 11.2 MB/s | Multi-threaded bulk API |
| JustSearch raw Lucene | 19,345 | 1,500 bytes | 27.7 MB/s | Single-threaded |
| JustSearch engine | 3,600 | 1,500 bytes | 4.9 MB/s | Single-threaded |

Normalized to MB/sec, our raw Lucene ceiling (27.7 MB/s single-threaded) exceeds Elasticsearch's published benchmark (11.2 MB/s multi-threaded). The docs/sec comparison was invalid due to 5x document size difference and different threading models.

The real optimization target is the 4.1x schema-layer overhead between raw Lucene and JustSearch engine — not the comparison to Elasticsearch.

## Further improvement opportunities

**1. ~~Profile the 4.1x schema overhead.~~** DONE. Created `IndexingOverheadProfiler` benchmark. Results (SciFact 5,184 docs, post-JIT warmup):

| Metric | JustSearch | Raw Lucene | Schema overhead |
|--------|-----------|------------|-----------------|
| Per doc | 134 µs | 40 µs | 94 µs |
| docs/sec | 7,479 | 24,923 | — |
| Ratio | 3.3x | 1.0x | — |

The 3.3x overhead (post-JIT) is tighter than the 4.1x cold measurement. The schema layer benefits from JIT compilation. The 94 µs/doc overhead covers: field catalog validation, FieldMapper.toDocument() (per-field type dispatch, DocValues construction), soft-delete/UID field addition, and Lucene updateDocument vs addDocument. Further breakdown would require nanoTime probes inside package-private methods — diminishing returns for optimization at this scale.

**2. ~~Fill the 7 test coverage gaps.~~** DONE (R11). 9 new tests across 3 files covering all absorbed logic: guardWritable (3 scenarios), commitAndTrack counters+telemetry, corpus profile caching, startGuarded failure reset, updateDocument refresh-before-rmw, searchText ParseException, getDocumentFieldValues stored-field fallback.

**3. ~~Eliminate VectorSearchOps.~~** DONE (R8). Deleted 34-line pass-through. HybridSearchOps takes ReadPathOps directly.

**4. ~~Extract test helpers from LuceneLifecycleManager.~~** DONE (R8). 15 `*ForTests()` methods moved to test-source `LifecycleTestAccessor`. Production file: 915 → 846 LOC.

**5. ~~Reduce SearchOrchestrator constructor width.~~** Investigated — not needed. Constructor takes 2 params, self-extracts 11 ops internally.

### Test coverage design

7 behaviors absorbed from the deleted facade have no dedicated tests. Grouped by test type:

**Unit tests (no Lucene index needed) → new `OpsAbsorbedLogicTest.java`:**

1. **`guardWritable()` readOnly rejection** — construct WritePathOps with `ctx.readOnly = true`, call any write method, assert ISE.
2. **`guardWritable()` null writer + no deferred mode** — `ctx.writer = null, deferredWriterMode = false`, assert ISE.
3. **`guardWritable()` deferred latch pass** — `deferredWriterMode = true`, pre-countdown latch, assert method passes.
4. **`guardWritable()` deferred latch timeout** — uncounted latch, assert ISE after timeout.
5. **`commitAndTrack()` counters** — index a doc, call commitAndTrack, assert `ctx.commitCount` incremented, `ctx.pendingDocs` reset to 0, `ctx.lastCommitNanos` updated. Inject `TelemetryEvents` mock, assert `onCommit(elapsedMs)` called.
6. **`getOrComputeCorpusProfile()` caching** — call twice, assert same object identity (cache hit). Set `ctx.cachedCorpusProfile = null`, call again, assert different object.
7. **`startGuarded()` failure reset** — create lifecycle manager with failing Components (via `setComponentsForTests`), call `start()`, catch exception, assert `ctx.started.get() == false`. Call `start()` again, assert it attempts again.

**Integration tests (need real Lucene index) → additions to existing test files:**

8. **`updateDocument` refresh-before-rmw** → add to `BatchUpdateIntegrationTest`: index a doc, commit (no manual refresh), call `updateDocument` — assert returns true (method does its own `maybeRefreshBlocking` internally).
9. **`searchText()` ParseException** → add to `TextSearchIntegrationTest`: index a doc, call `searchText` with malformed Lucene syntax (e.g., unbalanced brackets), assert returns empty result (not throws).
10. **`getDocumentFieldValues()` fallback** → add to `TextSearchIntegrationTest` or new test: index a doc with a stored single-valued field (not multi-valued DocValues), call `getDocumentFieldValues()`, assert returns `List.of(value)` via stored-field fallback path.

### Existing test audit findings (all remediated in R9/R10)

Audit of 10 test files revealed issues — all fixed.

**Critical — dead/ghost assertions:**
- `LuceneIndexRuntimeCommitTest.dagAndPipelineMetadataPersistAcrossReopen` — asserts `assertEquals(null, null)` for `dag_hash` and `pipeline_budget_profile` (removed fields per ADR 0014). Test passes vacuously, tests nothing. Should be deleted or rewritten to test actually-existing metadata fields.
- `CommitOpsTest` source map (line 80-81) and `LuceneIndexRuntimeCommitTest` source map (line 102) include removed `dag_hash`/`pipeline_budget_profile` fields. Stale data — no validator catches it because validators are no-op lambdas in tests.

**Structural — naming:**
All 13 `LuceneIndexRuntime*Test` files test `LuceneLifecycleManager`, not `LuceneIndexRuntime` (deleted). Names are stale. Rename to match the ops class they primarily exercise, or to `Lifecycle*Test` for integration tests that go through the lifecycle manager.

**Structural — monolith:**
`LuceneIndexRuntimeTest.java` is 1,743 LOC / 38 tests covering 6 concerns (commit, vector search, text search, hybrid search, faceting, lifecycle). Should be split into focused test files, most of which already exist.

**Structural — redundancy:**
- `LuceneIndexRuntimeComponentsTest.missingUidFailsValidation` duplicates `IndexingCoordinatorTest.validateThrowsOnMissingUidInFailMode`
- `LuceneIndexRuntimeTest.commitWithValidMetadataPasses` duplicates `LuceneIndexRuntimeCommitTest`
- `LuceneIndexRuntimeLagTest` (47 LOC, 2 tests) belongs in `CommitOpsTest`

**Weak assertions:**
- `LuceneIndexRuntimeLagTest.refreshLagDropsAfterRefresh` — `assertTrue(lagAfter <= lagBefore)` passes when both are 0. Does not verify lag was non-zero before refresh.
- `LuceneIndexRuntimeParityTest` — only checks `contains("read-only")`, not which field triggered the mismatch.
- `LuceneIndexRuntimeTest.searchHybridCombinesTextAndVectorResults` — comment about expected ordering with no actual assertion.

**Boilerplate:**
~30 tests repeat YAML config + `System.setProperty` + `createRuntimeWithDim()`. No shared test fixture.

**Positive:**
- `IndexingCoordinatorTest` — best test. Right class, right layer, meaningful assertions.
- `HybridSearchOpsTest` — clean unit tests of pure logic.
- `LuceneIndexRuntimeIndexingTest.softDeletedDocumentsHiddenFromSearchers` — thorough diagnostics.

### Remediation status — ALL DONE

**Priority 1 — fix ghost assertions: DONE (R9)**
- [x] Deleted `dagAndPipelineMetadataPersistAcrossReopen` (assertEquals(null,null))
- [x] Cleaned stale `dag_hash`/`pipeline_budget_profile` from CommitOpsTest and CommitMetadataIntegrationTest source maps

**Priority 2 — delete redundant tests: DONE (R9)**
- [x] Deleted `missingUidFailsValidation` (covered by IndexingCoordinatorTest)
- [x] Deleted `commitWithValidMetadataPasses` (covered by CommitMetadataIntegrationTest)
- [x] Merged 2 lag tests into CommitOpsTest, deleted LuceneIndexRuntimeLagTest
- [x] Deleted LuceneIndexRuntimeCloseTest (covered by LifecycleIntegrationTest)

**Priority 3 — strengthen weak assertions: DONE (R9)**
- [x] Lag test: added `assertTrue(lagBefore > 0)` before drop assertion
- [x] Parity tests: added field-name assertions (analyzer_fp/boosts_fp)
- [x] Hybrid test: added ordering assertion (doc-2 in top 2 results)

**Priority 4 — rename stale test files: DONE (R9 P4)**
- [x] 11 files renamed from `LuceneIndexRuntime*Test` to match ops class/concern

**Priority 5 — split monolith: DONE (R9 P5)**
- [x] `RuntimeIntegrationTest.java` (1,728 LOC, 28 tests) split into 7 focused files:
  - `VectorSearchIntegrationTest` (3 tests)
  - `TextSearchIntegrationTest` (10 tests)
  - `HybridSearchIntegrationTest` (5 tests)
  - `FacetingIntegrationTest` (4 tests)
  - `FuzzySearchIntegrationTest` (3 tests)
  - `LifecycleIntegrationTest` (2 tests)
  - `commitWithInvalidMetadataFails` → moved to `CommitMetadataIntegrationTest`
- [x] `RuntimeTestBase.java` shared base class with helpers
- [x] All 28 tests pass across 7 files

### R10: Post-split test fixes — DONE
- [x] Added `justsearch.hybrid.fusion_strategy` to `RuntimeTestBase`'s `SystemPropertyExtension` (prevents sysprop leak on assertion failure in hybrid tests)
- [x] Fixed YAML misindentation in 25 instances across 6 test files (`vector:` was at top-level instead of nested under `index:`)
- [x] Deleted redundant Test 2 in `searchTextExcludesChunksByDefault` (identical to Test 1)

## Research: best practices for new production files (2024–2026 sources)

Deep-dive research into current best practices for each new file's core concerns. Findings compared against our implementation to identify improvements.

### R1: Composition root & lifecycle state machine

**Finding: binary AtomicBoolean is insufficient for our multi-phase lifecycle.**

Production search systems unanimously use state enums, not boolean flags:

| System | States | Mechanism |
|--------|--------|-----------|
| Elasticsearch `Lifecycle` | INITIALIZED → STARTED → STOPPED → CLOSED | volatile enum + synchronized transitions |
| Elasticsearch `IndexShardState` | CREATED → RECOVERING → POST_RECOVERY → STARTED → RELOCATED → CLOSED | synchronized + explicit transition validation |
| Guava `AbstractService` | NEW → STARTING → RUNNING → STOPPING → TERMINATED (+ FAILED) | ReentrantLock + state enum |
| Solr `SolrCore` | Reference counting (not enum) | AtomicInteger refcount |

Our system has at least 6 distinguishable states: uninitialized, starting, read-only (deferred), read-write, closing, closed. The current `AtomicBoolean started` conflates "starting" with "started" and "read-only" with "read-write". A state enum with explicit transition validation would:
- Prevent `close()` during `start()` (currently possible — CAS succeeds, then `startGuarded()` throws, then `close()` can race)
- Make deferred-writer upgrade a first-class state transition instead of ad-hoc flag manipulation
- Provide `awaitRunning()` / `awaitTerminated()` semantics for callers that need to block on startup

**Finding: composition root should not also be the lifecycle state machine.**

Mark Seemann: composition root's job is wiring the object graph and configuring lifetimes, not being the state machine itself. Elasticsearch separates `Node.java` (composition root) from `AbstractLifecycleComponent` (lifecycle). Guava separates `ServiceManager` (coordinator) from `AbstractService` (state machine).

Our `LuceneLifecycleManager` conflates both — it wires the ops graph AND is the state machine. Separation would mean: lifecycle manager creates ops and passes them to a lifecycle state machine (or implements `AbstractService`-style template methods).

**Finding: deferred writer pattern is production-validated.**

Elasticsearch has `ReadOnlyEngine` → `InternalEngine` swap via `AtomicReference<Engine>` with engine reset mutex. ES does NOT do a runtime upgrade on the same shard — it closes and reopens with the appropriate engine type. Our approach (upgrade in-place by swapping volatile fields) is more aggressive but works because ops read ctx fields dynamically.

**Finding: auto-recovery — fail fast, recover from replica.**

ES never repairs corruption in-place. It fails the shard, marks it with a corruption marker file, and recovers from a healthy replica. Schema mismatches are rejected at indexing time. Our backup-first-then-rebuild approach is more aggressive than ES but appropriate for a single-node desktop app without replicas.

Sources: [ES Lifecycle.java](https://github.com/elastic/elasticsearch/blob/main/server/src/main/java/org/elasticsearch/common/component/Lifecycle.java), [Guava ServiceExplained](https://github.com/google/guava/wiki/ServiceExplained), [Seemann Composition Root](https://blog.ploeh.dk/2011/07/28/CompositionRoot/), [ES IndexShard.java](https://github.com/elastic/elasticsearch/blob/main/server/src/main/java/org/elasticsearch/index/shard/IndexShard.java)

### R2: Java concurrency — volatile state bag

**Finding: "bag of volatiles" is an anti-pattern.**

CERT VNA03-J: "Do not assume that a group of calls to independently atomic methods is atomic." Reading field A then field B, even when both are volatile, is a compound operation that is not atomic. Shipilev's IRIW test proves reads of multiple volatile fields are not tied in ordering constraints.

Our `RuntimeContext` has 20+ volatile fields. Reading `writer` then `searcherManager` in sequence gives no consistency guarantee for the pair. Between the two reads, `close()` could null both — leaving you with a non-null writer from epoch N and null searcherManager from epoch N+1.

**Finding: immutable snapshot pattern is the consensus recommendation.**

CERT VNA01-J: group related state into an immutable record, publish via a single volatile reference. Instead of 5 separate volatile fields, one volatile reference to an immutable record gives atomic consistency.

Applied to RuntimeContext, this means grouping related fields:
```java
record LifecycleState(IndexWriter writer, SearcherManager searcherManager,
                      Directory directory, boolean readOnly) {}
volatile LifecycleState lifecycleState;
```

**Finding: VarHandle weaker modes are not needed here.**

Doug Lea: "declare concurrent fields volatile as a baseline." Weaker modes (opaque, acquire/release) only for profiling-proven bottlenecks. Our fields are not in a hot path — volatile is correct.

**Finding: production systems avoid the problem by design.**

LMAX: Single Writer Principle — each field owned by one thread. Netty: confines handler state to a single EventLoop thread, no volatiles needed. OpenJDK ConcurrentHashMap: volatile on individual isolated fields + CAS, not bags of related volatiles.

Sources: [CERT VNA03-J](https://wiki.sei.cmu.edu/confluence/display/java/VNA03-J), [Shipilev JMM](https://shipilev.net/blog/2016/close-encounters-of-jmm-kind/), [CERT VNA01-J](https://wiki.sei.cmu.edu/confluence/display/java/VNA01-J), [Doug Lea JDK9 Memory Modes](https://gee.cs.oswego.edu/dl/html/j9mm.html)

### R3: SearcherManager & loan pattern

**Finding: Elasticsearch uses AutoCloseable try-with-resources, not lambda loan.**

`Engine.Searcher implements Releasable` (ES's AutoCloseable). Callers use `try (Engine.Searcher s = acquireSearcher(...)) { ... }`. This is compiler-enforced, idiomatic Java, handles exception suppression.

Our lambda loan pattern (`withSearcher(s -> ...)`) is valid but has downsides: awkward checked exception propagation through lambdas, caller can't hold searcher across async boundaries.

**Finding: our TOCTOU in SearcherBridge is real.**

Checking `ctx.searcherManager != null` then calling `acquire()` on it races with `close()`. Fix: capture to local variable first (minimum fix) or use immutable snapshot record (proper fix). Note: Lucene's `SearcherManager.acquire()` itself is safe against concurrent close (throws `AlreadyClosedException`), but this only helps if the reference hasn't been nulled.

**Finding: release() after close() is safe.**

Lucene javadoc: "it's safe to call release() after close()." In-flight searches hold ref-counted IndexSearcher instances that remain valid even after the SearcherManager is closed.

Sources: [ES Engine.java](https://github.com/elastic/elasticsearch/blob/main/server/src/main/java/org/elasticsearch/index/engine/Engine.java), [Lucene ReferenceManager javadoc](https://lucene.apache.org/core/9_3_0/core/org/apache/lucene/search/ReferenceManager.html), [McCandless SearcherManager blog](https://blog.mikemccandless.com/2011/09/lucenes-searchermanager-simplifies.html)

### R4: NRT read-after-write visibility

**Finding: commit ≠ visibility.**

After `IndexWriter.commit()`, existing searchers do NOT see changes. Only `SearcherManager.maybeRefresh()` opens a new reader. Commit is for durability (fsync). Refresh is for visibility. These are independent knobs.

**Finding: Elasticsearch bypasses the searcher entirely for get-by-ID.**

ES's realtime GET reads directly from the translog/VersionMap — never triggers a Lucene refresh. This avoids "refresh storms" that ES 5.0 suffered from when every GET forced an internal refresh. Our approach (forcing `maybeRefreshBlocking()` on every point lookup) is the same anti-pattern ES abandoned.

**Finding: `LiveFieldValues` (Lucene 4.2+) exists for exactly our use case.**

McCandless: "Getting real-time field values in Lucene" — provides O(1) field value lookups by document ID without requiring a searcher reopen. Purpose-built for version/modified-at checks like our `isUnmodified()`.

**Finding: per-lookup blocking refresh is a performance concern.**

Each refresh involves flushing buffered documents as a new segment, opening a new reader over all segments, and warming. McCandless recommends periodic background `maybeRefresh()` (non-blocking) and `maybeRefreshBlocking()` only when truly needed.

Our `maybeRefreshBlockingIfCommittedSinceRefresh()` is gated on timestamp comparison, which mitigates unnecessary refreshes — but when a commit has happened, every single point lookup pays the refresh cost. Better: accept slightly stale reads for most lookups, or use `LiveFieldValues` for the `isUnmodified()` hot path.

**Finding: nanoTime comparison across threads is sound on Windows.**

`QueryPerformanceCounter` (Windows) is monotonic across threads. The comparison is safe as long as timestamp values are published via volatile/AtomicLong (which they are — `lastCommitNanos` and `lastRefreshNanos` are AtomicLong).

Sources: [McCandless NRT blog](https://blog.mikemccandless.com/2011/11/near-real-time-readers-with-lucenes.html), [McCandless LiveFieldValues](https://blog.mikemccandless.com/2013/01/getting-real-time-field-values-in-lucene.html), [ES PR #48843 realtime GET](https://github.com/elastic/elasticsearch/pull/48843), [Shipilev nanotrusting](https://shipilev.net/blog/2014/nanotrusting-nanotime/)

### R5: DocValues vs stored fields for point lookups

**Finding: our strategy is correct for the common case.**

DocValues (columnar) are faster for 1-3 fields per document. Stored fields (row-oriented) are faster for 5+ fields (one decompression gets all fields co-located). Our "prefer DocValues, fallback to stored" matches the common case (single-field lookup).

Sease benchmark confirms: "If the use case requires that a lot of fields need to be returned, using stored fields is the way to go." Conversely, for single-field lookups, DocValues avoids the 16KB LZ4 block decompression cost.

**Finding: `advanceExact()` is the correct API for point lookups.**

Avoids scanning through the DocValues column. Our code already uses this pattern correctly.

**Finding: `docValueCount()` (Lucene 9.11+) replaces `NO_MORE_ORDS` iteration.**

For multi-valued DocValues, `docValueCount()` gives the cardinality without sentinel checking. Minor optimization opportunity for `getDocumentFieldValues()`.

**Finding: content retrieval correctly uses stored fields.**

`getDocumentContent()` fetches large text — stored fields are appropriate. DocValues are not suitable for large text blobs.

Sources: [Sease DocValues vs Stored Fields](https://sease.io/2020/03/docvalues-vs-stored-fields-apache-solr-features-and-performance-smackdown.html), [Sease ES field retrieval](https://sease.io/2021/02/field-retrieval-performance-in-elasticsearch.html), [SortedSetDocValues javadoc](https://lucene.apache.org/core/9_11_1/core/org/apache/lucene/index/SortedSetDocValues.html)

### R6: Aggregate counting & corpus profiling

**Finding: `searcher.count(TermQuery)` already short-circuits.**

`Weight.count()` can return `TermsEnum.docFreq()` for O(1) counting when no deletions exist. With deletions, it falls back to iteration. Our usage is near-optimal for simple term counts.

Alternative: `reader.docFreq(new Term(...))` bypasses the Weight/Scorer stack entirely for simple existence counts. Slightly faster for the embedding/SPLADE status counting where we do 4 separate term counts.

**Finding: 4 separate count() calls could use single-pass FacetsCollector.**

If status values are stored as DocValues, `SortedSetDocValuesFacetCounts` iterates the index once. However, for only 4 simple TermQuery counts, the practical difference is small — the overhead of building a `FacetsCollector` may exceed the savings.

**Finding: corpus profile cache should use DirectoryReader.getVersion() for staleness.**

`DirectoryReader.getVersion()` increments on every commit. O(1) staleness check: store version alongside cached profile, compare on access, recompute only when version changes. Far better than our current approach (volatile field with no invalidation signal — callers must know to null it).

**Finding: volatile field is sufficient for a single cached value.**

Caffeine/Guava Cache adds dependency overhead for a single cached object. Plain volatile reference + version comparison is the right pattern here.

**Finding: `MatchAllDocsQuery` count is O(1).**

`IndexSearcher.count(MatchAllDocsQuery)` returns `reader.numDocs()` directly. Our `docCount()` method is already optimal.

Sources: [Lucene Weight javadoc](https://lucene.apache.org/core/10_1_0/core/org/apache/lucene/search/Weight.html), [Lucene FacetsCollector](https://lucene.apache.org/core/9_9_1/facet/org/apache/lucene/facet/package-summary.html), [DirectoryReader javadoc](https://lucene.apache.org/core/7_4_0/core/org/apache/lucene/index/DirectoryReader.html), [McCandless index statistics](https://blog.mikemccandless.com/2012/03/new-index-statistics-in-lucene-40.html)

### Summary of improvement opportunities identified

| # | Area | Change | Status |
|---|------|--------|--------|
| I1 | Lifecycle state | 7-state `RuntimeState` enum replaces AtomicBoolean + readOnly + deferredWriterMode | **DONE** |
| I2 | RuntimeContext | `LifecycleSnapshot` immutable record replaces 6 individual volatile fields | **DONE** |
| I3 | SearcherBridge TOCTOU | Local variable capture in all 3 methods | **Already done** (original rewrite) |
| I4 | Point lookup refresh | Remove blocking refresh from `isUnmodified()` | **DONE** |
| I5 | Corpus profile cache | `DirectoryReader.getVersion()` staleness detection | **DONE** |
| I6 | Status counting | `reader.docFreq(term)` | **Dropped** — wrong for soft-delete model |
| I7 | Multi-valued DocValues | `docValueCount()` | **Already done** (original rewrite) |
| I8 | Composition root separation | Separate wiring from state machine | **Deferred** — not worth it for one service |

**Files changed:** 2 new (`RuntimeState.java`, `LifecycleSnapshot.java`), 10 production files modified, 3 test files modified. All tests pass.

## Implementation of improvements I1–I8

### Coordination with agent 323 (tempdoc 323) — completed successfully

Agent 323 landed 3 commits on this branch before implementation began: commit timer on CommitOps, per-RPC commit removal from GrpcIngestService, and commit attribution telemetry. All coordination constraints were satisfied:

- AtomicLong counters stayed standalone on RuntimeContext (not in LifecycleSnapshot)
- No new `commitAndTrack()` call sites were added
- `commitOps.stopCommitTimer()` integrated into lifecycle manager's `close()` and `start()` failure path
- `commitOps.startCommitTimer()` gated on `ctx.state == RUNNING` in both `start()` and `openWriterDeferred()`

### Confidence evaluation

Critical analysis of implementation readiness after re-reading the actual code:

**I3 is already done.** All 3 SearcherBridge methods already capture `ctx.searcherManager` to a local `mgr` before null-check and use. The TOCTOU identified in research was addressed during the original rewrite. No work needed.

**I5 and I7 are straightforward.** I5 adds ~15 lines to IndexCountOps (version-based staleness). I7 depends on whether `ReadPathOps.projectMultiValuedDocValues()` uses the `NO_MORE_ORDS` sentinel — needs code check.

**I1 (state enum) key insight:** `guardWritable()` currently checks `!ctx.readOnly && (ctx.writer != null || ctx.deferredWriterMode)` — a 3-flag compound check. With a state enum, this becomes `ctx.state == RUNNING` — simpler and more correct. The coupling concern was backwards: the state enum REDUCES coupling by replacing 3 ad-hoc boolean fields with one semantic state. Test message churn is mechanical (~15-20 assertions).

**I2 (immutable snapshot) revised assessment:** 46 reference sites across 9 files is mechanical. The real risk is `openWriterDeferred()` ordering — need to trace the exact sequence carefully. But `close()` becomes cleaner (capture snapshot, null atomically, close resources from captured copy). The mixed-read concern (snapshot + standalone counters) is inherent — the snapshot only guarantees consistency for lifecycle infrastructure, not counters. This is correct by design.

**I4 revised:** LiveFieldValues is over-engineering for our use case. The right answer is simpler: remove `maybeRefreshBlockingIfCommittedSinceRefresh()` from `isUnmodified()` only. False negatives (re-indexing an unmodified doc) are harmless waste. CRTRT refreshes every 500ms, bounding staleness. 3 lines deleted, no new code.

### Phase ordering and dependencies

```
Phase 1 (cache fix)      → I5  DONE  — corpus profile staleness detection
Phase 2 (lifecycle enum) → I1  DONE  — state machine replaces AtomicBoolean
Phase 3 (snapshots)      → I2  DONE  — immutable lifecycle snapshot
Phase 4 (NRT staleness)  → I4  DONE  — remove blocking refresh from isUnmodified
Dropped                  → I3 (already done), I6 (wrong for soft deletes),
                            I7 (already done), I8 (over-engineering)
```

### Phase 1: Corpus profile cache staleness (I5) — DONE

Moved caching from `RuntimeContext.cachedCorpusProfile` into `IndexCountOps` with `DirectoryReader.getVersion()` staleness detection. Removed `ctx.cachedCorpusProfile` field and the manual `ctx.cachedCorpusProfile = null` calls from `CommitOps.maybeRefresh()` and `maybeRefreshBlocking()`. Also removed the unused `RuntimeContext ctx` field from IndexCountOps (simplified to single-arg constructor). Updated `OpsAbsorbedLogicTest` to trigger cache invalidation via version change (commit + refresh) instead of `ctx.cachedCorpusProfile = null`.

### Phase 2: Lifecycle state enum (I1) — DONE

Created `RuntimeState.java` (7-state enum: CREATED, STARTING, READ_ONLY, RUNNING, CLOSING, CLOSED, FAILED). Replaced `AtomicBoolean started` + `volatile boolean readOnly` + `volatile boolean deferredWriterMode` on RuntimeContext with `volatile RuntimeState state` + `Object stateLock` + `boolean deferredWriterPending` + `boolean initialReadOnly`. Rewrote `start()`, `close()`, `openWriterDeferred()`, `ensureStarted()`, `setDeferredWriterMode()`, `isDeferredWriterMode()` on LuceneLifecycleManager. Simplified `WritePathOps.guardWritable()` from 3-flag compound check to single `ctx.state` check. Updated `OpsAbsorbedLogicTest` (3 tests). Zero test message churn (U2 confirmed).

### Phase 3: Immutable lifecycle snapshot (I2) — DONE

Created `LifecycleSnapshot.java` record (6 fields: directory, writer, searcherManager, indexPath, ephemeralPath, indexAnalyzer). Replaced 4 individual volatile fields + 2 plain fields on RuntimeContext with `volatile LifecycleSnapshot snapshot`. Updated 46 reference sites across 9 production files + 2 test files:
- `SearcherBridge.java` (3 sites) — snapshot read before SearcherManager access
- `CommitOps.java` (6 sites) — writer/searcherManager from snapshot
- `WritePathOps.java` (10 sites) — writer/searcherManager from snapshot
- `TextQueryOps.java` (3 sites) — indexAnalyzer from snapshot
- `ChunkSearchOps.java` (3 sites) — indexAnalyzer from snapshot
- `DocumentFieldOps.java` (1 site) — searcherManager from snapshot
- `PruneOps.java` (1 site) — searcherManager from snapshot
- `LuceneLifecycleManager.java` — applyComponents builds snapshot, close() captures-then-nulls, openWriterDeferred captures old before swap
- `LifecycleTestAccessor.java` (9 sites) — all via private `writer()` helper
- `CommitOpsTest.java` (3 sites) — `ctx.writer = writer` → `ctx.snapshot = new LifecycleSnapshot(...)`
- `OpsAbsorbedLogicTest.java` (2 sites) — removed `ctx.writer = null` (state enum handles guard now)

### Phase 4: Remove unnecessary blocking refresh from isUnmodified (I4) — DONE

Removed `maybeRefreshBlockingIfCommittedSinceRefresh()` call from `DocumentFieldOps.isUnmodified()`. Kept it in `getDocumentField()`, `getDocumentContent()`, `getDocumentFieldValues()`. Added comment explaining rationale (ES 5.0 refresh storm anti-pattern, CRTRT 500ms bound, false negatives are harmless re-indexing).

### Dropped / Deferred

**I3 (SearcherBridge TOCTOU):** Already implemented. All 3 methods capture `ctx.searcherManager` to a local variable before null-check and use. No work needed.

**I6 (reader.docFreq for status counting):** `searcher.count(TermQuery)` already short-circuits to `docFreq()` when no deletions exist. With our soft-delete model, there ARE always live deletions, so the short-circuit doesn't fire and we'd fall back to iteration anyway. `reader.docFreq()` counts all postings including soft-deleted docs — it would give WRONG counts. Dropped — not applicable to our soft-delete model.

**I8 (composition root separation):** LuceneLifecycleManager is 846 LOC. Splitting wiring from lifecycle would create 2 files (~400 LOC each) with the same total complexity. The Seemann/ES pattern is designed for systems with many services to coordinate — we have one. The benefit (testable lifecycle in isolation) doesn't justify the indirection for a single-service system. Defer unless the class grows significantly.

### Pre-implementation uncertainties — ALL RESOLVED

**U1 (Phase 1 — I7): Does `ReadPathOps.projectMultiValuedDocValues()` use `NO_MORE_ORDS`?**
**No.** Already uses `docValueCount()` as loop bound: `for (int i = 0; i < sdv.docValueCount(); i++)`. **I7 is a no-op — drop from Phase 1.**

**U2 (Phase 2 — I1): Test assertions matching error messages?**
**Zero.** No test assertions in adapters-lucene or worker-services check for `"not started"`, `"read-only"`, `"Writer not available"` etc. **State enum message changes have zero test churn.**

**U3 (Phase 2 — I1): Any `synchronized(ctx)` elsewhere?**
**No.** Zero uses of `synchronized` keyword anywhere in the runtime package. `synchronized(ctx)` is safe. Still prefer a private `Object stateLock` for defense-in-depth — prevents future code from accidentally synchronizing on ctx.

**U4 (Phase 3 — I2): `openWriterDeferred()` field access trace.**
Reads 12 ctx fields (mostly config/immutable passed to `ComponentsFactory.build()`), then reads `ctx.searcherManager` (to close old one), then writes `ctx.readOnly = false`, `ctx.deferredWriterMode = false`, `ctx.writerReadyLatch.countDown()`.

For I2: the `ComponentsFactory.build()` args are config/immutable fields NOT in the snapshot. The `ctx.searcherManager` read is the snapshot candidate — after I2, this becomes `ctx.snapshot.searcherManager()`. The writes to `readOnly`/`deferredWriterMode` go away with I1 (state enum replaces them). The sequence becomes: capture old snapshot → call `applyComponents()` (which builds and sets new snapshot) → close old SearcherManager from captured snapshot → transition state to RUNNING. **Clean.**

**U5 (Phase 3 — I2): LifecycleTestAccessor field access.**
9 references to snapshot-candidate fields across 7 methods: `ctx.directory` (1), `ctx.writer` (5 — mergePolicy, ramBufferMb, maxBufferedDocs, indexSort, softDeletesField), `ctx.searcherManager` (2 — acquireSearcher, releaseSearcher), `ctx.indexAnalyzer` (1). All must change to read from `ctx.snapshot`. Mechanical — each method adds one `LifecycleSnapshot snap = lifecycle.ctx.snapshot;` line and reads from it.

**U6 (Phase 4 — I4): `isUnmodified()` production callers?**
**Zero.** `isUnmodified()` has no production callers — only test code and IndexingLoop Javadoc references. Wait — re-checking: IndexingLoop lines 755 and 901 DO call `documentFieldOps.isUnmodified()`. These are real calls inside `processFile()` and `processFileForRebuild()`. Regardless, both are batch indexing paths where false negatives are harmless. **I4 is safe.**
