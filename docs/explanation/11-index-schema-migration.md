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

### Index fingerprint (`index_fingerprint`)

Tempdoc 915 replaced the old five-key parity set (`schema_ver` / `schema_fp` / `index_schema_fp` /
`analyzer_fp` / `similarity_fp`) with a single rebuild-requiring key, `index_fingerprint`, plus the
one benign (never-rebuild) key `boosts_fp`. The old keys were untruthful in both directions:
`schema_ver` tracked the search-intent grammar version and could never fire, while `index_schema_fp`
hashed the whole catalog *file*, so an annotation-only edit (an `rmwPolicy` added to a field) flipped
it against an index that was physically still perfectly compatible (tempdoc 804).

- **What it is:** a SHA-256 over canonical JSON of the effective *physical* index shape —
  everything that decides what bytes end up in the Lucene directory, and nothing else
  (`IndexFingerprint.java`). Inputs:
  - `catalog_schema_version` — the catalog's own declared `version`.
  - Per-field physical projection: `id`, `type`, `stored`, `docValues`, `multiValued`, `analyzer`,
    `roles`, and for vector fields `dimension` + `similarity`.
  - The analyzer definitions (index-time analysis fingerprint).
  - `vector_format` (`float32` vs `int8_sq`).
  - HNSW `m` + `ef_construction` (graph-construction parameters), hashed as the **effective**
    values the codec builds with (`ResolvedConfig.Index.effectiveVectorHnswM()` and friends), not
    the raw nullable config — so writing a default out explicitly stays a no-op instead of costing a
    reindex.
  - Chunking: target/overlap/minimum tokens, `threshold_chars` (the size above which a document is
    chunked at all, so it decides whether chunk documents exist), and the splitter's algorithm
    version.
  - `preview.max_chars` — the bound on `content_preview`, a `stored:true` field.
  - `analysis.lucene_version` + `analysis.icu_version` — the libraries that perform index-time
    analysis. An analyzer upgrade changes the postings with every descriptor unchanged, so the
    versions are inputs. Deliberately coarse: a Lucene or ICU minor bump triggers one rebuild even
    when the analysis is unchanged in practice. That is the intended trade — the alternative is a
    silent postings change no detector can see.
  - `embedding_model_sha256` / `splade_model_sha256` / `ner_model_sha256` — the models whose output
    is stored in the vector, sparse and entity fields. (`entity_*_raw` are `stored` + `docValues`
    fields written from NER output, so swapping the NER model changes index content with no other
    descriptor moving.)
- **Deliberately excluded:**
  - **`rmwPolicy` field annotations** — the read-modify-write preservation policy never changes
    bytes on disk (`FieldMapper` rejects an `rmwPolicy` on any stored/doc-values field by
    construction), so it can only affect runtime backfill behaviour. This exclusion is the specific
    fix for the 804 over-trigger.
  - **Query-time scoring:** BM25 `k1`/`b` (`similarity_fp`), field boosts (`boosts_fp`), and HNSW
    `ef_search`. These change ranking, not storage, so a change to them must never cost the user a
    reindex.
  - The search-intent grammar, prompt packs, and templates — they never touched the index.
- **Tri-state model inputs:** a model fingerprint is tri-state (`IndexFingerprint.ModelState`):
  `NOT_CONFIGURED` (no model resolvable for this deployment) hashes as JSON `null` — a determinate
  answer. `INDETERMINATE` (a model file is configured but its digest could not be read) is *not* an
  answer: `IndexFingerprint.compute()` returns empty rather than inventing one, and
  `SsotCommitMetadataSource` then stamps **no** `index_fingerprint` key at all into commit
  user-data. `ParityDiagnostics.diff()` skips the comparison when the **expected** side is blank,
  and `IndexMetadataParityGuard` logs a WARN once per boot naming the input that went unresolved, so
  a check that is not running never looks like a check that passed. **A transiently unreadable model
  file must never look like a swapped one** — the consequence of the latter is a full rebuild.
  A *missing* model file is a different thing and is read as `NOT_CONFIGURED`, a determinate answer:
  most installs have no SPLADE or NER model, and reading their absence as "no answer" would switch
  the parity check off on every one of them.
- **Legacy indexes migrate once, by design.** Every index built before this key existed has a blank
  *stored* fingerprint, and it always will. Skipping that case would leave the guard permanently
  inert on exactly the installs it exists to protect, so a blank stored value on a rebuild-requiring
  key **is** a mismatch: the diff carries the `legacy-index-without-fingerprint` hint, and under the
  production `BLUE_GREEN_MIGRATE` default the Worker rebuilds once, beside the live index, while
  search keeps serving. This is the deliberate one-time upgrade rebuild the wave-2 release is built
  around — existing installs pay for it once, and afterwards their shape is recorded. A blank stored
  value on the *benign* key (`boosts_fp`) is still skipped: an unverifiable scoring descriptor is not
  worth reporting, let alone acting on.
- **Stamping:** on commit, the Worker writes `index_fingerprint` into Lucene commit user-data via
  `SsotCommitMetadataSource` (rendering version `IndexFingerprint.RENDERING_VERSION`).
- **Validation:** on startup/open, `IndexMetadataParityGuard.checkOnOpen()` compares the stored
  value vs the current value. See [Enforcement status](#enforcement-status-2026-09) below — the
  guard now enforces.
- **UI/automation signal:** the Worker surfaces schema state via `/api/status` under the same
  wire field names as before (unchanged by the 915 rename, now backed by `index_fingerprint`):
  - `indexSchemaFpStored`, `indexSchemaFpCurrent`
  - `indexSchemaCompatState`
  - `reindexRequired` + `reindexRequiredReason` (stable reason codes: `schema_mismatch`,
    `rebuild_brake_exhausted`)
- **Consumers:** none on the query path. `IndexStatusOps.safeSchemaCompatState()` computes the state
  for status reporting only; no planner, executor, or retrieval-leg decision reads it. The dense leg
  is gated by the *embedding* fingerprint below (`EmbeddingCompatibilityController.allowQueryEmbeddings()`,
  consumed at `SearchPlanner.java:87`).

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

- **`FAIL_CLOSED`**: refuse to rebuild automatically.
  - The Worker fails startup; the Head keeps the HTTP server up and surfaces the worker start error in `/api/status`.
  - This is the pre-915 default and remains available as an explicit opt-in, but it is no longer what a production profile resolves to (see defaults below).
- **`REBUILD_BACKUP_FIRST`**: rename-to-backup and rebuild an empty index.
  - Backup-first, guarded filesystem operations (no recursive deletes).
  - This is the **dev default** — a developer wants the fast rebuild, not a second generation on disk.
- **`BLUE_GREEN_MIGRATE`**: availability-first migration.
  - Serve the existing active generation (“Blue”) **read-only** for search while building a new generation (“Green”) for writes.
  - This is the **production default** (tempdoc 915 §C). `FAIL_CLOSED` was the old default and is the wrong answer for a desktop app: a schema-changing upgrade left the user with an index that refuses to open and no path forward. Blue/green keeps the existing index serving reads while the new one is built beside it.

Defaults are resolved by `ResolvedConfigBuilder.normalizeSchemaMismatchPolicy(raw, isProd)`: `null`/blank config resolves to `BLUE_GREEN_MIGRATE` in production, `REBUILD_BACKUP_FIRST` in dev.

Override sources:

- YAML: `index.schema_mismatch.policy`
- Env/sysprop: `JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY` / `-Dindex.schema_mismatch.policy=...`

### Repeat-rebuild brake

Because the production default auto-starts a rebuild on a `SCHEMA_MISMATCH`, and an index that fails
to build a valid Green presents the same mismatch again on the next boot, the Worker bounds how many
times it will auto-start a rebuild targeting the same `index_fingerprint` before it stops and leaves
the decision to an operator (`IndexGenerationManager`, tempdoc 915 §C):

- `MAX_AUTO_REBUILD_ATTEMPTS = 3` — enough to absorb a transient failure (a crash mid-build, a full
  disk that clears) and few enough that a genuinely unbuildable index stops costing a full rebuild on
  every boot.
- Tracked in `state.json` as `auto_rebuild_key` (the target `index_fingerprint`), `auto_rebuild_count`
  (1-based attempt number), and `auto_rebuild_first_ms`. `recordAutoRebuildAttempt(targetKey)` is
  persisted **before** the rebuild starts, not after it succeeds, so a rebuild that crashes the
  process still consumes its attempt.
- The count resets whenever `targetKey` differs from the last recorded one, so a new upgrade is never
  refused because an older one exhausted the budget.
- Cleared (`auto_rebuild_key`/`count`/`first_ms` all set back to `null`) on a successful cutover —
  `IndexGenerationManager.promoteBuildingGenerationToActive()` releases the brake once the rebuild has
  converged, so a future, unrelated upgrade starts with a full budget.
- A boot whose target fingerprint is **uncomputable** charges nothing. Folding those into a shared
  bucket would let three boots with an unreadable model file exhaust the budget for an unrelated real
  shape, so the index that genuinely needed a rebuild would never get one.

**On exhaustion the Worker finishes starting.** Refusing to open would be the same dead-end the old
`FAIL_CLOSED` default produced, three boots later and with no explanation. So the brake sets a state
and `start()` continues through the rest of its sequence rather than returning: the gRPC server is
created and bound, the port is written to the signal bus, `appServices` (and with it the status
surface) is constructed, and the sentinel thread runs. Concretely, in that state:

| Startup step | Behaviour with the brake exhausted |
|---|---|
| Lucene runtimes | the active generation opens **read-only**; ingest and search share it, and no Green is allocated |
| switch-buffer drain | skipped — there is no writer to drain into |
| `createGrpcServer` + `start` | runs; the Worker binds a real port |
| `signalBus.writePort` | runs; the Head discovers the Worker normally |
| indexing loop | **not started**, and the reason is logged at ERROR — its only job is to write into a read-only runtime |
| sentinel thread | runs |

Search therefore keeps serving everything already indexed, ingestion stops, and the status surface
says so explicitly: `schemaCompatState = BLOCKED_REBUILD_BRAKE` →
`reindexRequiredReason = rebuild_brake_exhausted` → the `index.rebuild_brake_exhausted` readiness
reason code, worded and given a rebuild remedy on the frontend.

**Recovery is a user-initiated rebuild.** `core.rebuild-index` (`RebuildIndexHandler` →
`startMigration(user_requested_rebuild)`) allocates a Green generation beside the read-only Blue, and
the cutover that completes it calls `promoteBuildingGenerationToActive()`, which clears
`auto_rebuild_*` — so a successful rebuild both fixes the index and restores the automatic budget.
Clearing `auto_rebuild_*` in `state.json` by hand grants a fresh automatic budget without rebuilding,
which is the right move only when the cause of the repeated failures has been fixed some other way.

## Enforcement status (2026-09)

The parity guard now enforces. Tempdoc 915 removed the two unconditional set-sites in
`HeadlessApp.java` (`setupInfra` and the sidecar entry) that used to set
`justsearch.index.parity.allow_mismatch=true` on every boot — the reason the guard never enforced
anything for its whole prior life (tempdoc 804 §D1).

- `IndexMetadataParityGuard.checkOnOpen()` diffs the stored vs. expected `PARITY_KEYS`
  (`ParityDiagnostics`: `index_fingerprint` and `boosts_fp`). On any diff it logs at WARN, then:
  - if `justsearch.index.parity.allow_mismatch=true` is set (see below), continues in WARN mode
    (the operator escape hatch);
  - else if the diff includes `index_fingerprint` (a `REBUILD_REQUIRING_KEYS` member), raises
    `IndexRuntimeIOException(SCHEMA_MISMATCH)`, which `index.schema_mismatch.policy` acts on;
  - else (a `boosts_fp`-only diff — query-time config) throws `IllegalStateException`, marking the
    shard read-only until the config is realigned; this is never a reindex trigger.
- `justsearch.index.parity.allow_mismatch` / `JUSTSEARCH_INDEX_PARITY_ALLOW_MISMATCH` now survives
  **only** as an explicit operator escape hatch — nothing sets it by default any more. An operator can
  still set it to open a known-divergent index read-only for diagnosis, and nothing else.
- What also still enforces, independently: the `FieldInfos` inspection in `ComponentsFactory`, which
  fires only when the on-disk index genuinely cannot accept writes under the current field mapping.
  That is the detector the “reindex did nothing” failure mode originally needed, and it is unaffected
  by the 915 change.

### Known limitation: a deferred open does not raise the mismatch (2026-09)

Where the guard is *consulted* it enforces. But on the boot path most installs take, the active
generation is opened with `openDeferred()` (chosen whenever the index has segments), and
`RuntimeSession` treats `Mode.DEFERRED` as a read-only open — so `ComponentsFactory` takes its
`readOnly` branch and **logs** the mismatch instead of raising it:

> `Index open guard reported a mismatch at {} but continuing in read-only mode: {}`

The mismatch then reappears when the background writer upgrade runs
(`DeferredRuntime.upgradeWriter()` inside `initDeferredModels()`), where it is caught as
`Background model initialization failed (non-fatal)`.

The observable consequence: on such a boot the index opens, **search keeps serving**, the status
surface still reports `BLOCKED_MISMATCH` with `reindex_required`, and the *user* is told to reindex —
but the automatic blue/green rebuild does **not** start by itself, because
`KnowledgeServer.start()`'s `SCHEMA_MISMATCH` handler is never entered. Automatic migration is
reached today from the other two paths: a resumed migration whose Green is mismatched, and an active
generation opened non-deferred.

This is a gap in reachability, not in the policy: `index.schema_mismatch.policy` does what this
document describes wherever the exception is raised. Closing it is tracked as tempdoc 915 open item
O7 — the choice is between distinguishing `DEFERRED` from `READ_ONLY` at the guard call site and
routing `upgradeWriter()`'s `SCHEMA_MISMATCH` into the same handler.

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


