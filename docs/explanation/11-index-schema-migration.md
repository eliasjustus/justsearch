---
title: Index Schema Migration (Blue/Green)
type: explanation
status: stable
description: "Blue/green migration mechanics and safety guarantees."
---

# 11. Index Schema Migration (Blue/Green)

JustSearch needs to evolve the Lucene index schema over time (new fields, new DocValues roles, new analyzers, etc.).
Lucene requires that a field’s schema remain consistent across segments, so schema drift must be handled explicitly.

This document describes the **current implemented architecture** for detecting schema mismatches and migrating safely.

## The failure mode we’re preventing

Schema mismatches can present as “reindex did nothing”:

- A reindex request succeeds at the HTTP layer (jobs are enqueued).
- Jobs then fail at write time because the on-disk index was created with incompatible field mappings.
- The queue drains (jobs become `FAILED`), so it can look like “reindex finished”.

The fix is to detect mismatches **before** we open an `IndexWriter`, surface the failure deterministically in status APIs, and provide safe recovery/migration policies.

## Error taxonomy: corruption vs schema mismatch

We intentionally separate:

- **Corruption** (`IndexRuntimeIOException.Reason.CORRUPT_INDEX`): missing segments, `CorruptIndexException`, etc.
  - Controlled by `index.auto_recovery` (backup-first rebuild, guarded).
- **Schema mismatch** (`IndexRuntimeIOException.Reason.SCHEMA_MISMATCH`): mapping contract drift.
  - Controlled by `index.schema_mismatch.policy` (see below).

This prevents “schema evolution” from being treated as “corruption” (which historically led to overly-destructive recovery paths).

## Fingerprinting + `/api/status` contract

JustSearch fingerprints both the Lucene schema and the embedding model, stamping them into Lucene commit metadata so mismatches can be detected deterministically.

### Schema fingerprint (`index_schema_fp`)

- **Stamping:** on commit, the Worker writes `index_schema_fp` into Lucene commit user-data. It is the SHA-256 of the canonical `SSOT/catalogs/fields.v1.json` file (`SsotCommitMetadataSource.java:81-93`), optionally re-hashed with a runtime vector-dimension override.
- **Validation:** on startup/open, `IndexMetadataParityGuard` compares the stored fp vs the current fp and classifies compatibility (compatible, legacy/missing, mismatch). **This guard does not currently enforce** — see [Enforcement status](#enforcement-status-2026-08) below.
- **UI/automation signal:** the Worker surfaces schema state via `/api/status`:
  - `indexSchemaFpStored`, `indexSchemaFpCurrent`
  - `indexSchemaCompatState`
  - `reindexRequired` + `reindexRequiredReason` (stable reason code: `schema_mismatch`)
- **Consumers:** none on the query path. `IndexStatusOps.safeSchemaCompatState()` computes the state for status reporting only; no planner, executor, or retrieval-leg decision reads it. The dense leg is gated by the *embedding* fingerprint below (`EmbeddingCompatibilityController.allowQueryEmbeddings()`, consumed at `SearchPlanner.java:87`). An index can therefore report `schema_mismatch` and still serve fully hybrid results — sandbox round 10 reproduced exactly that, which is why the frontend words this state as advisory (`info` severity, not a reindex cause).
- **Sensitivity:** because it hashes the whole catalog file, *any* byte edit flips it — including annotation-only edits with no physical index consequence. The three post-v0.1.0 catalog edits (a dead-field deletion and three `rmwPolicy` annotations) each flipped `index_schema_fp` against v0.1.0 indexes that remained physically compatible. A fingerprint over the *physical* schema (or per-field consequence classes) would be needed to make the signal truthful; that is not implemented.

### Embedding model fingerprint (`embedding_model_sha256`)

- **Stamping:** on commit, the Worker writes `embedding_model_sha256` into Lucene commit user-data (SHA-256 of the model file, computed by `EmbeddingFingerprint` via filesystem I/O — no ORT sessions needed).
- **Validation:** `EmbeddingCompatibilityController` (ECC) compares the stored fingerprint against the current model's fingerprint and enters one of: `COMPATIBLE` (fingerprint match or new index), `REBUILDING` (mismatch, re-embedding in progress), `BLOCKED_LEGACY` (no fingerprint stored).
- **Migration trigger (tempdoc 312 item 20):** When `BLUE_GREEN_MIGRATE` policy is set and the stored embedding fingerprint differs from the current model's fingerprint, `KnowledgeServer.start()` triggers a blue-green migration (same mechanics as schema mismatch — see below). This allows embedding model upgrades to rebuild the index with the new model's vectors without slow read-modify-write backfill.

The Head does not probe Lucene directly; it forwards these fields via the Worker status map (`RemoteKnowledgeClient.getStatusMapForUi()`).

Regression coverage:

- `modules/ui/src/integrationTest/java/io/justsearch/ui/api/SchemaMismatchStatusContractTest.java` seeds a mismatched stored fp and asserts `/api/status` exposes `reindexRequired=true` + `schema_mismatch`.

## On-disk layout: generation-scoped index root

The effective index root is resolved via `ConfigStore`/`ResolvedConfig` (the index base path):

- Default: `<dataDir>/index/<collection>` (collection defaults to `default`)
- Override: `JUSTSEARCH_INDEX_BASE_PATH` / `-Djustsearch.index.base_path=<path>`

Under the root, the Worker manages a generation layout:

```text
<indexBasePath>/
  state.json                # pointers + migration state (format_version=2)
  migration_progress.json   # best-effort enumerator progress snapshot
  indices/
    <generationId>/         # Lucene directory
      .justsearch-generation.sentinel
      .justsearch-index-generation.json
      segments_N / ...      # Lucene files
```

The Worker always opens Lucene against a **specific generation directory**, never against the root directly.

## Schema mismatch policies (startup behavior)

When the Worker detects `SCHEMA_MISMATCH` at startup, behavior is policy-controlled:

- **`FAIL_CLOSED`**: refuse to rebuild automatically (the value a production profile resolves to by default, `ResolvedConfigBuilder.normalizeSchemaMismatchPolicy`).
  - The Worker fails startup; the Head keeps the HTTP server up and surfaces the worker start error in `/api/status`.
  - In practice only the `ComponentsFactory` `FieldInfos` detector reaches this policy today; the fingerprint detector is disabled (below).
- **`REBUILD_BACKUP_FIRST`**: rename-to-backup and rebuild an empty index (dev convenience).
  - Backup-first, guarded filesystem operations (no recursive deletes).
- **`BLUE_GREEN_MIGRATE`**: availability-first migration.
  - Serve the existing active generation (“Blue”) **read-only** for search while building a new generation (“Green”) for writes.

Override sources:

- YAML: `index.schema_mismatch.policy`
- Env/sysprop: `JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY` / `-Dindex.schema_mismatch.policy=...`

## Enforcement status (2026-08)

The parity half of the design above is **wired but not enforcing in any shipped run**. Verified at source during the sandbox round-10 investigation (tempdoc 804 §D1):

- `HeadlessApp.java:267` (`setupInfra`) and `HeadlessApp.java:607` (sidecar entry) both set `justsearch.index.parity.allow_mismatch=true` **unconditionally** — there is no dev/prod condition on either set-site — and `WorkerSpawner` forwards `INDEX_PARITY_ALLOW_MISMATCH` into the Worker JVM.
- `IndexMetadataParityGuard.checkOnOpen()` therefore logs the diffs at WARN (`continuing in WARN mode`) and returns, so it never raises `SCHEMA_MISMATCH` for a rebuild-requiring parity key (`analyzer_fp` / `schema_ver` / `index_schema_fp`) and `index.schema_mismatch.policy` never sees one.
- What still enforces: the `FieldInfos` inspection in `ComponentsFactory`, which fires only when the on-disk index genuinely cannot accept writes under the current field mapping. That is the detector the “reindex did nothing” failure mode needs.
- **Deliberate, not an oversight to revert blindly.** With today’s content-hash fingerprint, enabling enforcement would refuse to start on indexes that are physically fine (round 10’s index was serving hybrid results while reporting `schema_mismatch`). Making the guard enforce is gated on a truthful fingerprint, not on flipping the flag.

## Blue/Green migration model (current MVP)

At a high level:

- **Blue (active generation)**: serves queries; kept **strictly read-only** for rollback safety.
- **Green (building generation)**: receives all writes; becomes the next active generation at cutover.

The Worker wires two runtimes during migration:

- `searchRuntime` → Blue (read-only)
- `ingestRuntime` → Green (read/write)

Cutover is performed as a **`state.json` pointer swap + Worker restart** (restart-based cutover), which avoids in-process hot-swapping complexity and is easier to make crash-safe.

## Embedding readiness gate (`embeddingReadyLatch`)

During blue-green migration, the migration enumerator (which walks the filesystem and enqueues jobs) waits on a `CountDownLatch` before starting enumeration. The latch is released by `initDeferredModels()` after the embedding provider and ECC are fully wired. This ensures all migration jobs are enqueued AFTER the `IndexingLoop` has access to the embedding provider, so inline embedding activates for ~100% of documents.

Without this gate, the enumerator starts immediately and the `IndexingLoop` processes jobs before the embedding provider is ready — resulting in most documents getting `PENDING` status instead of inline vectors (tempdoc 312: 35% coverage without latch → 99.7% with latch).

The latch has a 120-second timeout; if the embedding provider isn't ready by then, enumeration proceeds without inline embedding (graceful degradation — backfill will handle remaining docs after cutover).

## Inline embedding during migration

During blue-green migration, `IndexingLoop.canBatchEmbed` is conditionally enabled (via `migrationActiveSupplier`) so batch GPU/CPU embedding runs inline. This differs from normal primary indexing (where embedding is deferred to backfill). The rationale: during migration, Blue serves search and Green is not yet serving — "fast BM25" has no benefit. Green should optimize for total time including vectors (~8.6 docs/sec inline > 7 docs/sec RMW backfill total time).

`precomputedEmbedding` in `IndexingDocumentOps.buildDocument()` takes priority over `allowEmbeddingWrites`, so batch-computed vectors are written directly regardless of the deferred-embedding flag.

## Cutover fence: `SWITCHING` + durable buffering

The hardest correctness window is "right around cutover" (pointer swap + restart). During that window, we must not lose mutating operations.

The Worker uses a cutover fence:

- It enters a short **`SWITCHING`** state near the end of migration.
- While in `SWITCHING`, mutating ingest RPCs are **durably buffered** into `jobs.db.switch_buffer`.
- After restart on the new active generation, the Worker replays buffered ops before resuming normal processing.

**Fail-closed semantics:** Buffering is part of the write path—if `putSwitchBuffer()` fails (SQL error), gRPC handlers return `UNAVAILABLE` (retryable) instead of ACKing the operation. This prevents "ACK without durability" during cutover. The `worker.switch_buffer.write_failures` telemetry counter tracks such failures.

Buffered operations include (current):

- File ingest and deletes:
  - `submitBatch`, `deleteById`, `deleteByPath`
- Watcher reconciliation:
  - `syncDirectory(force=true)` buffered as `SYNC_ROOT(root, force)`
  - `pruneMissing` buffered as `PRUNE_PREFIX(prefix)`
- AI / VDU mutations:
  - `updateVduResult`, `markVduProcessing`, `recoverVduProcessing`

### Cutover policy for failed jobs

By default, permanently failed indexing jobs do **not** block auto-cutover (failures remain visible via status and keep the system “unhealthy”).

Optional guardrail:

- `JUSTSEARCH_INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS` /
  `-Dindex.migration.cutover.max_failed_jobs=<N>`

If configured, the Worker blocks cutover and marks the migration `FAILED` when `failed_count > N` at cutover drain time (keeps Blue active).

### Deadlines

To avoid hanging forever in `SWITCHING`, the Worker enforces a maximum switching duration and transitions to `FAILED` if it cannot drain in time (no pointer swap).

## Operator surface (Head REST → Worker gRPC)

The Head exposes operator endpoints (Head never touches Lucene or `state.json` directly):

- Migration controls:
  - `POST /api/indexing/migration/start`
  - `POST /api/indexing/migration/cutover`
  - `POST /api/indexing/migration/rollback`
  - `POST /api/indexing/migration/pause`
  - `POST /api/indexing/migration/resume`
- Generation GC (best-effort):
  - `POST /api/indexing/gc`

## Observability: trust `/api/status`

The UI and dev tooling should treat `GET /api/status` as the primary “what’s running?” signal (no Head-side filesystem probing).
Key fields include migration state/pointers, per-generation counts, switch-buffer depth, and queue drain breakdowns.

See `docs/explanation/08-observability.md` for the current `/api/status` field map.


