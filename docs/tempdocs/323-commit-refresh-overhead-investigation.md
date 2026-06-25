---
title: "Lucene Commit/Refresh Overhead Investigation"
status: done
created: 2026-03-18
---

# 323 — Lucene Commit/Refresh Overhead Investigation

## Purpose

Investigate and reduce Lucene commit/refresh overhead in the Worker process.
The primary finding was that GrpcIngestService committed per-RPC (~2 fsyncs
per document during bulk ingest) while IndexingLoop already batched commits
on the same IndexWriter. A periodic commit timer in `CommitOps` now serves
as the universal durability safety net, allowing the per-RPC commits to be
removed.

## What was done

### Phase 1: Commit timer in CommitOps (DC7)

Added a `ScheduledExecutorService` to `CommitOps` that fires every 10s.
On each tick: if `ctx.pendingDocs > 0 && ctx.writer != null`, calls
`commitAndTrack("timer")`. This is the universal safety net for writes
from any code path — GrpcIngestService, backfill ops, anything — even
when IndexingLoop is idle and its self-referential triggers don't fire.

- `startCommitTimer()` called from `LuceneLifecycleManager.start()`,
  guarded by `!ctx.readOnly`
- `stopCommitTimer()` called from `LuceneLifecycleManager.close()`,
  before CRTRT and SearcherManager close
- Single daemon thread named `"commit-timer"`, 10s fixed-rate interval
- `CommitOps.java` grew from 152 → 228 lines

### Phase 2: Remove per-RPC commits from GrpcIngestService

Removed `commitAndTrack()` from 4 call sites in GrpcIngestService:

| Removed site | Reason safe |
|---|---|
| `updateVduResult` | No read-after-write consumer. `maybeRefreshBlocking()` kept for NRT visibility. Commit timer provides durability within 10s. |
| `markVduProcessing` (success) | Recovery via `recoverVduProcessing` handles stuck-PROCESSING docs. |
| `markVduProcessing` (max retries) | Idempotent — doc re-fails on retry if crash loses the FAILED status. |
| `recoverVduProcessing` | Recovery is periodic; if crash loses the reset, next recovery call retries. |

3 commits retained (user-initiated operations with visibility contracts):
`deleteByPath`, `deleteById`, `updateDocumentPaths`.

Test fix: added `maybeRefreshBlocking()` in
`GrpcIngestServiceVduHardeningTest.recoverVduProcessingResetsProcessingDocsToPending`.

### Phase 3: Telemetry and backfill consistency

- Added `commitAndTrack(String reason)` overload for caller attribution.
  `TelemetryEvents.onCommit(long, String)` dispatches the reason
  (backward-compatible default delegates to `onCommit(long)`).
- Instrumented all 15 production commit sites: `indexing-loop/time`,
  `indexing-loop/buffer`, `indexing-loop/idle`, `indexing-loop/shutdown`,
  `indexing-loop/rebuild-stamp`, `grpc/deleteByPath`, `grpc/deleteById`,
  `grpc/updatePaths`, `sync/prune`, `prune`, `timer`,
  `backfill/embedding`, `backfill/embedding-chunk`, `backfill/ner`,
  `backfill/splade`.
- Fixed 4 backfill ops to call `commitAndTrack()` instead of raw
  `commit()` — they now update `lastCommitNanos`, `commitCount`, reset
  `pendingDocs`, and fire telemetry. (Inconsistency left by tempdoc 320.)

## Key design constraint: idle-phase commit gap (DC7)

IndexingLoop's commit triggers check `indexedSinceCommit > 0` — a counter
incremented only when IndexingLoop itself processes documents.
GrpcIngestService writes to the same IndexWriter but does not increment
this counter.

Without the commit timer, if IndexingLoop finishes primary indexing and
goes idle while VDU results continue arriving, those writes are
NRT-visible but never committed to disk. A crash loses all VDU results
written after the last IndexingLoop commit.

The commit timer closes this gap. It checks `pendingDocs > 0` regardless
of which code path produced the writes.

## VDU crash recovery invariant (DC2)

VDU state lives entirely in Lucene document fields (`vdu_status`), not in
the SQLite job queue. The Head's `VduBatchProcessor` has no `JobQueue`
reference. The crash recovery chain:

1. `markVduProcessing` sets `vdu_status = PROCESSING` (committed by timer)
2. If crash loses the uncommitted `updateVduResult` write → doc stays
   `PROCESSING` on disk
3. `recoverVduProcessing` on startup resets it to `PENDING`
4. Document is re-extracted — no permanent data loss

Minor risk: pathological crash loops within the 10s timer window could
reset the retry counter repeatedly (DC8). Unlikely in practice.

## Remaining commit/refresh landscape

### Commit sites after implementation

| Source | Sites | Trigger | API |
|---|---|---|---|
| IndexingLoop | 4 | Time (10s), buffer (1000 docs), idle, shutdown | `commitOps.commitAndTrack(reason)` |
| GrpcIngestService | 3 | deleteByPath, deleteById, updateDocumentPaths | `commitOps.commitAndTrack(reason)` |
| Backfill ops | 4 | Per 100-200 doc batch | `commitOps.commitAndTrack(reason)` |
| SyncDirectoryOps | 1 | After pruning orphans | `commitOps.commitAndTrack(reason)` |
| PruneOps | 1 | After pruning by path prefix | `commitOps.commitAndTrack(reason)` |
| Migration ops | 2 | Cutover seal + buffer drain | `commitOps.commit()` (raw, intentional) |
| Commit timer | 1 | Every 10s if pendingDocs > 0 | `commitOps.commitAndTrack("timer")` |

### Refresh sites (unchanged)

| Source | Sites | Type |
|---|---|---|
| CRTRT thread | 1 | Non-blocking, hardcoded 50ms min / 500ms target |
| SearchOrchestrator | 1 | `maybeRefresh()` (gated) |
| GrpcSearchService | 6 | `maybeRefresh()` (gated) |
| RagContextOps | 3 | `maybeRefresh()` (gated) |
| CitationMatchOps | 2 | `maybeRefresh()` (gated) |
| GrpcIngestService | 1 | `maybeRefreshBlocking()` after updateVduResult |

## Future work (not in this tempdoc's scope)

| # | Opportunity | Finding | Status |
|---|---|---|---|
| 1 | Search-idle refresh suppression | F5 | Not started — stop CRTRT when no queries arrive |
| 2 | HNSW tiny-segment bypass | F7 | Not started — Lucene 10.4 has the API, needs format verification |
| 3 | Merge-on-refresh | F6 | Not started — `maxFullFlushMergeWaitMillis` not set (defaults to 0) |
| 4 | NRTCachingDirectory | F10 | Not started — raw MMapDirectory in use |
| 5 | Unified NRT configuration | F5/F6 | Not started — CRTRT hardcoded, disconnected from config |
| 6 | Adaptive refresh | F11 | Not started — Lucene 10.3 API available |
| 7 | Empty commit guard | F12 | Not started — timer could fire with pendingDocs=0 after metadata change |
| 8 | Config-driven timer interval | — | Not started — hardcoded 10s; `commitDebounceMs` config key unused |

---

## Research Findings — External Sources (2024-2026)

Synthesis of current Lucene literature, Elasticsearch/OpenSearch engineering
blogs, and storage research. Findings informed the implementation decisions
above.

### F1: Commit ≠ visibility

Lucene's NRT model decouples **durability** (commit = fsync) from
**visibility** (refresh = reader reopen). Documents become searchable after
a reader reopen, not after a commit. An NRT reader opened via
`DirectoryReader.open(IndexWriter)` sees all flushed changes without any
fsync.

**Applied:** This insight justified removing per-RPC commits — visibility
comes from `maybeRefreshBlocking()`, not from the commit.

Sources: McCandless "NRT Readers with SearcherManager" (2011); Lucene Wiki
NearRealtimeSearch; Lucene 10.1 IndexWriter Javadoc.

### F2: fsync cost on consumer SSDs

Consumer NVMe SSDs deliver only ~300-2000 fsync/sec per thread (no PLP
capacitors — fsync waits for DRAM→NAND flush, ~0.5-3.5ms/call).

| Device class | fsync latency | fsync/sec (1 thread) |
|---|---|---|
| Consumer NVMe SSD | 0.5-3.5 ms | 300-2000 |
| Enterprise NVMe (PLP) | 0.01-0.05 ms | 7000-23000+ |

**Applied:** At 100 docs/sec VDU throughput with per-doc commits, 100-300ms/sec
was spent in fsyncs. Removing per-RPC commits reduced this ~1000x.

Sources: SmallDatum (Jan 2026); Percona (2024); CedarDB (2025).

### F3: ES/OpenSearch never commit per-document

Both interpose a translog between client and Lucene. Refresh every 1s for
visibility; flush (= Lucene commit) every 512 MB or 30 min. JustSearch's
equivalent: commit timer (10s) + IndexingLoop batching.

Sources: ES translog docs; Opster; OpenSearch tuning docs; UBC (2024).

### F4: Group commit

Database group commit (MySQL, PostgreSQL) coalesces concurrent fsyncs.
Not implemented — the timer-based approach eliminated the need.

Sources: MySQL 5.6+ binary log; PostgreSQL `commit_delay`; CedarDB.

### F5: Search-idle refresh suppression

ES 7+ suppresses shard refreshes when no searches arrive in 30s.
JustSearch's CRTRT thread runs continuously. Future optimization.

Sources: ES PR #27500; ES Issue #95544; OpenSearch Issue #9707.

### F6: Merge-on-refresh (~25% fewer segments)

`IndexWriterConfig.setMaxFullFlushMergeWaitMillis(500)` merges tiny
segments inline during refresh. JustSearch does not set this (defaults
to 0/disabled). One-line future optimization.

Sources: McCandless (2021); LUCENE-8962; OpenSearch Issue #1345.

### F7: HNSW tiny-segment bypass (~30% faster vector indexing)

Lucene 10.4 skips HNSW graph construction for segments below a threshold.
JustSearch is on Lucene 10.4 with `Lucene99HnswVectorsFormat`. Needs
verification on format support.

Sources: Lucene Issue #13447 / PR #14963; Elastic HNSW blog.

### F8: FeatureField (SPLADE) is cheap

SPLADE uses standard posting lists — no HNSW graph construction. Refresh
cost is comparable to a text field. Not a concern.

Sources: Lucene Issue #11799; FeatureField Javadoc; OpenSearch (2024).

### F9: Multi-segment vector search overhead is severe

kNN search must traverse each segment's HNSW graph independently. Fewer
segments = faster vector search. Motivates merge-on-refresh (F6) and
HNSW bypass (F7).

Sources: Elastic "Multi-graph vector search" (2025); ES kNN tuning docs.

### F10: NRTCachingDirectory

Caches small flushed segments in RAM (up to ~60 MB). JustSearch uses raw
MMapDirectory. Future optimization.

Sources: Lucene Issue #4165; Lucene Wiki NearRealtimeSearch.

### F11: Adaptive refresh (Lucene 10.3)

`RefreshCommitSupplier` allows stepping through intermediate commit points
instead of jumping to latest. Reduces refresh latency spikes during bulk
ingest. Future optimization.

Sources: Lucene 10.3 Changelog; OpenSearch (Dec 2025).

### F12: setLiveCommitData() overhead is negligible

Metadata stamping cost is dominated by the fsync, not serialization.
Caveat: `setLiveCommitData()` marks a "committable change" — the timer
could trigger unnecessary empty commits if metadata changes without
document writes. Mitigated by `pendingDocs > 0` guard.

Sources: Lucene 10.1 IndexWriter Javadoc; McCandless NRT blog.

### F13: Lucene 10 stored fields change

Eliminated ThreadLocal-based StoredFieldsReader caching. Reduces hidden
heap overhead of many small NRT segments. JustSearch is on Lucene 10.4
and benefits automatically.

Sources: Lucene PR #11998; Lucene 10 Migration Guide.

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 61 days at audit time.

