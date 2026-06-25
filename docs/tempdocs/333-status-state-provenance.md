---
title: "333: Status State Provenance & Freshness"
type: tempdoc
status: done
created: 2026-03-21
updated: 2026-03-21
---

> NOTE: Noncanonical doc (architecture). May drift.

# 333: Status State Provenance & Freshness

## Root Cause

The status endpoint flattens internal state without distinguishing
source, freshness, or subsystem. `WorkerOperationalView` dumps ~80
fields onto a flat JSON root via `@JsonUnwrapped`. Fields from different
subsystems at different freshness levels (live gRPC query, init-time
cache, derived boolean) appear identically. Consumers cannot tell
whether a field reflects current state or a stale snapshot.

## Origin

Tempdoc 312 retrospective → tempdoc 330 §1-4 mitigations. 330 fixed
specific staleness bugs (ECC fingerprint, health service embedding
ready) and added structured groups + commit metadata endpoint. But the
underlying problem remains: new fields added to `WorkerOperationalView`
can silently be stale without any mechanism to detect or prevent it.

## Scope

Design a mechanism so that:
1. Each status field declares its data source and freshness guarantee
2. Stale-by-construction fields (init-time snapshots) are impossible
   or explicitly marked
3. The status response indicates when each subsystem was last updated

## Current Architecture (from investigation)

### Field freshness categories

All 80+ fields in `WorkerOperationalView` fall into 7 freshness groups:

| Group | Staleness | Source | Example fields |
|-------|-----------|--------|----------------|
| A. Live Lucene | seconds (searcher refresh) | `IndexCountOps` queries | `indexedDocuments`, `embeddingCompletedCount`, `spladeDocCount` |
| B. Live SQLite | milliseconds | `JobQueue` reads | `pendingJobs`, `failedJobs`, `indexState` |
| C. Startup snapshot | hours (index open time) | `openTimeCommitUserData` | `indexSchemaFpStored`, `embeddingFingerprintStored` (fixed by 330 §1) |
| D. Process lifetime | Worker uptime | Init-time closures | `rerankerOrtCuda`, `embedBackend`, `spladeModelPath` |
| E. Per-RPC computed | milliseconds | Inline computation | `indexSizeBytes` (Files.walk!), `throughputDocsPerSec` |
| F. Disk read per-RPC | milliseconds | `state.json` parse | `migrationState`, `activeGenerationId` |
| G. Head-assembled | milliseconds | JVM runtime, telemetry | `uptimeMs`, `memoryUsedBytes`, `diskPressure` |

### Existing provenance infrastructure

- `ReadinessComponentView` already has `source`, `observedAt`, `stale`,
  `stalenessMs` fields — but `stale`/`stalenessMs` are hardcoded to
  `false`/`0` and never populated
- The grouped sub-objects from 330 §4 (`embedding`, `schema`,
  `chunkCoverage`, `queueHealth`, `migration`) map to freshness
  boundaries
- `ReadinessDimension.source()` provides a vocabulary for data sources
  (`"worker_status"`, `"worker_health_check"`, `"lifecycle_snapshot"`)

### Performance concerns

- `indexSizeBytes`: `Files.walk()` on every status RPC — most expensive
  field. Should be cached with periodic refresh.
- `state.json` disk read: `indexGenerationManager.readStateBestEffort()`
  parses JSON from disk on every RPC. Migration state changes rarely.
- `latestCommitUserDataBestEffort()`: opens a new `DirectoryReader`
  each call (for `vectorFormatStored`).
- Two sequential gRPC RPCs per `/api/status`: `indexStatus` + health
  check. The health check provides only `embeddingReady`.

## Design

### Strategy: per-group `observedAtMs` on the 330 §4 sub-objects

Extend the grouped sub-objects already introduced in 330 §4 with an
`observedAtMs` timestamp. This is the least invasive approach — it
reuses existing API structure and the existing `ReadinessComponentView`
pattern.

### Work Items

### 1. [x] Add `observedAtMs` to each status group record

Each of the 5 group records (`EmbeddingStatusGroup`, `SchemaStatusGroup`,
`ChunkCoverageGroup`, `QueueHealthGroup`, `MigrationStatusGroup`) gets
a `long observedAtMs` field set to `System.currentTimeMillis()` at
construction time (in the Worker's `buildStatusResponse()`).

This tells the consumer: "this group's data was assembled at time X."
For live groups (A, B), X is within milliseconds of the request. For
init-time groups (C, D), X is the Worker's startup time.

### 2. [x] Cache `indexSizeBytes` with periodic refresh

Replace the per-RPC `Files.walk()` with a cached value refreshed
every 30 seconds by the existing telemetry periodic task. The status
RPC reads the cached value. Add `indexSizeObservedAtMs` to indicate
freshness.

### 3. [x] Cache `state.json` with dirty-flag invalidation

`IndexGenerationManager.readStateBestEffort()` already writes
`state.json` on every state change. Add a `volatile long stateJsonVersion`
counter incremented on each write. The status RPC reads the cached
`State` object and only re-parses from disk if the version changed.

### 4. [ ] Populate `ReadinessComponentView.stale` and `stalenessMs` [DEFERRED — crosses 3 layers]

The fields already exist in the record and the API contract. Populate
them based on the data source:
- `worker_status` source: stale if Worker gRPC call failed (using
  cached fallback)
- `worker_health_check` source: stale if health RPC failed
- `lifecycle_snapshot` source: never stale (Head-local, always fresh)

Set `stalenessMs` to `now - lastSuccessfulRpcMs` when stale.

### 5. [x] Document freshness guarantees in the status endpoint

Add a `_meta` object to the status response:
```json
"_meta": {
  "workerRpcAtMs": 1710000000000,
  "workerRpcStale": false,
  "groups": {
    "embedding": { "observedAtMs": 1710000000000 },
    "schema": { "observedAtMs": 1710000000000 },
    "chunkCoverage": { "observedAtMs": 1710000000000 },
    "queueHealth": { "observedAtMs": 1710000000000 },
    "migration": { "observedAtMs": 1710000000000 }
  }
}
```

This is purely informational. Consumers that don't care about freshness
ignore it. Consumers that do (monitoring, debugging) can check
`observedAtMs` to detect stale data.

## Confidence Assessment

**High confidence (90%)** for item 1. Adding a `long` field to 5
existing records and setting `System.currentTimeMillis()` in the
factory methods. Mechanical. Schema baseline regeneration needed.

**High confidence (85%)** for item 2. Replace `Files.walk()` with a
cached `AtomicLong` + periodic refresh. The telemetry periodic task
infrastructure already exists.

**High confidence (85%)** for item 3. `IndexGenerationManager` already
writes state.json. Adding a version counter and a cached read is
straightforward.

**Medium confidence (70%)** for item 4. The `stale`/`stalenessMs`
fields exist but have never been set. Need to understand the fallback
path in `RemoteKnowledgeClient.getWorkerOperationalView()` — when the
gRPC call fails, it returns `WorkerOperationalView.fallback()`. The
staleness detection needs to propagate from the gRPC client through
`StatusLifecycleHandler` to `ReadinessComponentView`. This crosses
3 layers.

**High confidence (80%)** for item 5. The `_meta` object is additive.
The frontend Zod schema uses `.loose()` so unknown keys are allowed.
No frontend changes required.

**Overall: 82%.** Items 1-3 and 5 are safe, mechanical changes. Item 4
is the only one that crosses multiple layers and requires careful
tracing of the fallback path.

## Verification

### Automated (CI-safe)

- [x] `StatusRecordSchemaTest` — 15 tests pass (schema + contract +
  serialization). Verifies grouped sub-objects have `observedAtMs`
  and `meta` field appears in serialized JSON.
- [x] Frontend `typecheck` — clean (Zod schema is `.loose()`, new
  fields don't break it)
- [x] Frontend `test:unit:run` — 184/184 pass

### Live verification (requires backend)

**333 §1 — `observedAtMs` on grouped sub-objects:**

1. Start backend, ingest a few docs
2. `curl /api/status | jq '.embedding.observedAtMs'`
3. Verify non-zero timestamp. Compare against system clock — should
   be within seconds.
4. Repeat for `.schema.observedAtMs`, `.chunkCoverage.observedAtMs`,
   `.queueHealth.observedAtMs`, `.migration.observedAtMs`

**333 §2 — cached `indexSizeBytes`:**

1. Start backend, ingest 100 docs
2. `curl /api/status | jq '.indexSizeBytes'` — note value
3. Immediately curl again — should return same value (cached, no
   `Files.walk`)
4. Wait 31 seconds, curl again — may show updated value (cache
   refreshed)
5. Check Worker log: `Files.walk` should NOT appear on every status
   poll (was per-RPC, now every 30s)

**333 §3 — cached `state.json`:**

1. Start backend, check `/api/status` — note `migrationState`
2. Poll `/api/status` 10 times rapidly
3. Check Worker log: `state.json` should be read once (cached),
   not 10 times

**333 §5 — `StatusMeta`:**

1. Start backend
2. `curl /api/status | jq '.meta'`
3. Verify `workerRpcAtMs` is a recent timestamp and
   `workerRpcStale` is `false`
4. Kill the Worker subprocess (but keep Head running)
5. `curl /api/status | jq '.meta'`
6. Verify `workerRpcStale` is `true` (Worker unreachable, using
   fallback data)

### What these verify against original issues

| Original issue | Verification |
|----------------|-------------|
| `embeddingFingerprintStored` permanently empty | §1: `embedding.observedAtMs` proves data is fresh |
| Can't tell if status data is stale | §5: `meta.workerRpcStale` explicitly flags it |
| `Files.walk` per RPC (expensive) | §2: cached with 30s TTL |
| `state.json` parsed per RPC | §3: cached with dirty-flag |
| 100+ flat fields hard to parse | §1: grouped sub-objects with timestamps |

## Dependencies

- **330 §4 (status grouping):** Must be done first (this adds fields
  to the group records introduced there).
- **330 §1-2 (ECC + health service fixes):** Must be done first
  (eliminates the two most prominent stale-state bugs).

---

## Staleness review (2026-05-18)

Marked `done` after per-doc triage in the Shape-2 staleness audit.

Status freshness/provenance design on top of 330. 330 is completed per cross-tempdoc references. Design phase concluded.

Body content preserved as design history per the README's promotion
policy. If this work should resume, open a new tempdoc citing this one
by title.

