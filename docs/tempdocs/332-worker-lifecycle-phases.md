---
title: "332: Worker Lifecycle Phase Boundaries"
type: tempdoc
status: done
created: 2026-03-21
updated: 2026-03-22
---

> NOTE: Noncanonical doc (architecture). May drift.

# 332: Worker Lifecycle Phase Boundaries

## Root Cause

The Worker initializes asynchronously but exposes state synchronously,
with no lifecycle phase boundaries. `KnowledgeServer.start()` sets up
infrastructure synchronously, then kicks off `initDeferredModels()`
async. Components constructed during `start()` receive null/no-op
references that are later replaced. There is no explicit boundary
between "infrastructure ready" and "models ready" — it's implicit in
field assignment order and volatile visibility.

## Origin

Tempdoc 312 item 20 (migration enumerator timing gap) → tempdoc 330 §5
(deferred). The `embeddingReadyLatch` is a band-aid for one timing
dependency. The SPLADE timing gap during migration is another. The
health service stale-state bug (330 §2) is a third symptom.

## Scope

Decompose `KnowledgeServer.start()` + `initDeferredModels()` into
explicit named phases with typed outputs and dependency declarations,
so that timing relationships are visible in the type system rather than
implicit in field ordering.

## Current Architecture (from investigation)

### `start()` (main thread, synchronous) — 8 steps

| Step | What | Fields set | Thread-safety |
|------|------|-----------|---------------|
| 0 | Telemetry + tracing | `telemetry`, `tracingBootstrap` | plain |
| 1 | Signal bus | `signalBus` | plain, pkg-private |
| 2 | Job queue (SQLite) | `jobQueue` | plain |
| 3 | Lucene runtimes + migration trigger | `indexBasePath`, `activeIndexPath`, `buildingIndexPath`, `searchLifecycle`, `ingestLifecycle`, generation manager | plain |
| 3.5 | `DefaultWorkerAppServices` | `appServices` (volatile), `infraCtx` | volatile for appServices |
| 4 | gRPC server + port advertisement | `grpcServer`, delegates | plain |
| 6 | IndexingLoop start | background thread begins | loop reads volatile fields |
| 8 | `deferredModelInit` launch | `deferredModelInit` (volatile) | fork-join pool |

### `initDeferredModels()` (fork-join thread, async) — 8 steps

| Step | What | Wires to |
|------|------|----------|
| A | Open deferred IndexWriter | `ingestLifecycle` |
| B | EmbeddingService | `IndexingLoop.embeddingProvider` (volatile), `GrpcSearchService`, `GrpcHealthService` |
| C | ECC + latch release | `IndexingLoop.embeddingCompatController` (volatile), `GrpcSearchService`, `GrpcIngestService`, `embeddingFingerprintSupplier` |
| D | NER | `IndexingLoop.nerService` (volatile) |
| E | BGE-M3 | `IndexingLoop.bgeM3Encoder` (volatile), `GrpcSearchService`, `GrpcHealthService` |
| F | SPLADE | `IndexingLoop.spladeEncoder` (volatile), `GrpcSearchService` |
| G | Disambiguation | `IndexingLoop.disambiguationService` (volatile) |
| H | GPU diagnostics | `GrpcIngestService` |

### Cross-thread safety

- `InfraContext` fields (from `start()`) are safely published to the
  fork-join thread via `CompletableFuture.runAsync()` happens-before
- All `IndexingLoop` model fields are `volatile` — safe for cross-thread
- `embeddingFingerprintSupplier` is `AtomicReference` — safe
- `GrpcHealthService.embeddingProvider` was `final` (now `volatile`
  after 330 §2 fix)
- Plain fields on `KnowledgeServer` (e.g., `embeddingService`,
  `spladeEncoderInstance`) are only read by `DevReloadManager` which
  awaits `deferredModelInit` completion first — safe via
  `CompletableFuture.get()` happens-before

### Timing gaps (3 identified)

1. **Migration enumerator before SPLADE** — latch releases after step C,
   but SPLADE wires at step F. Migration docs in this window have no
   sparse vectors.
2. **Normal jobs before embedding** — IndexingLoop starts at step 6,
   models wire at step B. Jobs get `PENDING` embedding status.
3. **Commits before ECC** — commits between step A and step C don't
   stamp `embedding_model_sha256`. (Mitigated by 330 §1 fix.)

### Existing phase-like patterns

- `InfraContext` record — already a typed Phase 1 output
- `IndexGenerationManager.IndexLayout` — typed generation layout result
- `SyncDirectoryOps.SyncWalkPhaseResult` — minimal phase result record
- `DevReloadManager.performReload()` — reuses `InfraContext`, re-wires
  models, proves the phase boundary works

### Key constraint: IndexingLoop must start before models load

Delaying the IndexingLoop until Phase 4 would regress first-doc latency
by ~15-20s (model loading time). The current design (start immediately
with no-op providers, wire models when ready) is intentional — BM25
results appear immediately, vector results come online later.

## Design

### Phase decomposition with typed result records

```
Phase 1: Infrastructure → InfraContext (exists)
Phase 2: Services       → ServiceContext (new: appServices + gRPC)
Phase 3: Readiness      → port advertisement, Head unblocks
Phase 4: Models         → ModelContext (new: all model instances)
Phase 5: Migration      → runs after Phase 4 completes
```

### Work Items

### 1. [x] Create `ModelContext` record ✓ DONE

Created at `modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/ModelContext.java`.
No `@Nullable` annotations (jspecify not in this module; matches `InfraContext` convention).

Bundle all model-instance fields into a single typed record:
```java
record ModelContext(
    @Nullable EmbeddingService embeddingService,
    @Nullable EmbeddingCompatibilityController ecc,
    @Nullable NerService nerService,
    @Nullable SpladeEncoder spladeEncoder,
    @Nullable SpladeIdfQueryEncoder spladeIdfQueryEncoder,
    @Nullable BgeM3Encoder bgeM3Encoder,
    @Nullable DisambiguationService disambiguationService) {}
```

`initDeferredModels()` returns `ModelContext` instead of setting
scattered fields on `KnowledgeServer`. `DevReloadManager.rewireModels()`
takes `ModelContext` as input instead of reading package-private fields.

### 2. [deferred] Create `ServiceContext` record

Bundle gRPC delegates and appServices:
```java
record ServiceContext(
    DefaultWorkerAppServices appServices,
    DelegatingSearchService searchDelegate,
    DelegatingIngestService ingestDelegate,
    DelegatingHealthService healthDelegate,
    Server grpcServer) {}
```

### 3. [x] Extract `initDeferredModels()` to return `ModelContext` ✓ DONE

Changed from void (setting fields) to `ModelContext` return. `deferredModelInit` type
changed from `CompletableFuture<Void>` to `CompletableFuture<ModelContext>`. Caller
updated to `CompletableFuture.supplyAsync(this::initDeferredModels)`. Fields are still
set inside `initDeferredModels()` for immediate volatile visibility to IndexingLoop.
The `ModelContext` is also returned for `DevReloadManager` and the latch gate.

### 4. [x] Gate migration enumerator on full `ModelContext` readiness ✓ DONE

Renamed `embeddingReadyLatch` → `modelReadyLatch`. Moved `countDown()` from after ECC
(step C) to after GPU diagnostics (step H) — just before the return. The `finally`
block safety net is retained to ensure the latch releases on partial failure.
The enumerator now waits for embedding + ECC + NER + BGE-M3/SPLADE + disambiguation,
closing the SPLADE timing gap identified in 312.

### 5. [x] Wire `ModelContext` into `DevReloadManager` ✓ DONE

`DevReloadManager.awaitDeferredInit()` now returns `ModelContext` (was void).
`DevReloadManager.rewireModels()` takes `ModelContext` as input, reads all wire-*
calls from record fields. BGE-M3 wiring added (was missing from the old field-reading
version). A `rewireModelsFromFields()` fallback is retained for the null-ModelContext
case (init failed before returning). The `performReload()` method passes the `ModelContext`
from the completed future.

## Confidence Assessment

**High confidence (80%)** for items 1-2. Pure data structure extraction,
no behavioral change. The records mirror fields that already exist.

**High confidence (75%)** for item 3. The `initDeferredModels()` method
is long but linear (no branching across steps). Extracting to return
a record is mechanical. The main risk is the `finally` block safety
net for latch release — need to ensure it still fires on partial failure.

**High confidence (85%)** for item 4. Renaming and broadening the latch
is a small change. The enumerator thread already waits — just moving
the countDown to a later point.

**High confidence (80%)** for item 5. `DevReloadManager` already
accesses these fields; changing from field reads to record fields is
mechanical.

**Overall: 80%.** The refactoring is large (~200 lines touched) but
each step is mechanical and independently testable. The main risk is
a subtle thread-safety regression from reordering field assignments.
Mitigation: run the full test suite after each item.

## Verification

### Automated (CI-safe)

- [x] `indexer-worker:test` — all tests pass (model init, gRPC wiring)
- [x] `worker-services:test` — all tests pass (IndexingLoop, health)
- [ ] **New test needed:** verify `ModelContext` returned by
  `initDeferredModels()` is non-null and contains the expected model
  instances when models are available. This is a unit test for the
  record construction — currently only tested implicitly via
  `DevReloadManager` wiring.

### Live verification (requires backend)

**332 §4 — SPLADE timing gap fixed:**

This is the key behavioral improvement. Previously, the migration
enumerator started after embedding loaded but before SPLADE loaded.
Migration docs in that window had no sparse vectors.

1. Ingest 100 docs with default models
2. Restart with `JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY=blue_green_migrate`
   + a different embedding model to trigger migration
3. Check Worker log: "Migration enumerator: embedding provider not ready"
   should NOT appear (latch releases after all models, not just embedding)
4. After migration completes, check that migrated docs have SPLADE
   status != PENDING (they got sparse vectors inline)

Previously: enumerator released after embedding + ECC only. Now:
released after embedding + ECC + NER + BGE-M3 + SPLADE + disambiguation.

**332 §5 — DevReloadManager uses ModelContext:**

1. Start backend with `hotReload: true`
2. Trigger a hot-reload (modify a Java file, run `reload`)
3. Check Worker log: should show model re-wiring without errors
4. Verify embedding + SPLADE still work after reload

### What these verify against original issues

| Original issue | Verification |
|----------------|-------------|
| Migration enumerator before embedding | §4: latch now gates on ALL models |
| SPLADE not ready during migration | §4: same latch, verified via SPLADE status |
| Implicit timing dependencies | §3: `ModelContext` makes dependencies type-safe |
| DevReloadManager reads scattered fields | §5: reads from typed record |

## Dependencies

- **330 §2 (health service late-bind):** Must be done first (changes
  `GrpcHealthService` constructor signature).
- **330 §5 (deferred decomposition):** This tempdoc IS the full
  design for 330 §5.

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Worker lifecycle phase boundaries design (full design for 330 §5). Builds on 330 which is completed per 341's "Related" annotation. Design concluded.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

