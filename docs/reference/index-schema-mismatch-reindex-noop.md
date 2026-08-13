---
title: "Troubleshooting: Reindex doesn't run (schema mismatch)"
type: reference
status: stable
updated: 2026-08-03
description: 'Diagnosing "reindex doesn''t run" due to schema mismatch.'
---

# Troubleshooting: “Reindex doesn’t run” (schema mismatch)

If a reindex appears to “do nothing” even though the UI/API returns success, schema mismatch is a common cause.

For the overall migration architecture, see `docs/explanation/11-index-schema-migration.md`.

## Problem: “Reindex doesn’t run” (but the button/API returns 200)

In the browser UI (and via `POST /api/indexing/reindex`), a reindex can appear to “do nothing” even though:

- The HTTP endpoint returns success (e.g. `{"status":"reindex triggered"}`).
- `/api/status` may briefly show `indexState=INDEXING` with `pendingJobs>0`, then quickly return to `IDLE`.
- The indexed document count may stay constant.
- The Library root’s `lastIndexed` timestamp may update even though content wasn’t re-processed.

### What’s actually happening

The reindex request *does* enqueue jobs. The worker then attempts to index the files but fails each job at write time because the **on-disk Lucene index was created with an older field schema** and is no longer compatible with the current code’s field mapping.

The “tell” is in the worker log (dev: `modules/ui-web/.dev-data/logs/worker.log`, desktop: `%LOCALAPPDATA%/JustSearch/logs/worker.log`):

- Example failure:
  - `IllegalArgumentException: cannot change field "mime" from index options=NONE to inconsistent index options=DOCS`

Lucene requires that a field’s schema (index options, docvalues type, etc.) remains consistent across segments. If we change how a field is mapped (e.g., a keyword field becomes indexed for filtering), Lucene will reject updates for that field.

### Why it looks like a “no-op” in the UI

- Jobs that fail repeatedly are eventually marked `FAILED` and stop contributing to `queue_depth`.
- Even with better status reporting (we now expose `failed_count` / last failure in `/api/status`), users may still interpret “queue drained” as “success” unless we make failures and migration lifecycle very explicit.

So the UX becomes: “reindex succeeded” + “queue drained” + “docs unchanged”.

## Current fix (implemented): startup schema-mismatch guard + explicit policy

We added a **schema compatibility check at Lucene runtime startup**:

- It opens the existing index read-only and inspects Lucene `FieldInfos`.
- It compares observed field schema to the schema implied by the current `FieldMapper` / `FieldCatalogDef` rules (for the keyword/docvalues fields that were triggering failures).
- If a mismatch is detected, it throws an `IndexRuntimeIOException` with `Reason.SCHEMA_MISMATCH` (not `CORRUPT_INDEX`).

What happens next is **explicitly policy-controlled** via `index.schema_mismatch.policy`:

- `FAIL_CLOSED`: fail startup and surface a deterministic “schema mismatch / migration required” error via `/api/status` (Head carries the Worker start error string). This is the resolved default in a production profile (`ResolvedConfigBuilder.normalizeSchemaMismatchPolicy`), but see [What is actually enforced today](#what-is-actually-enforced-today-2026-08) — the *fingerprint* detector that would hand it a mismatch is disabled in every shipped run, so in practice this policy only ever sees the `FieldInfos` check below.
- `REBUILD_BACKUP_FIRST` (convenient in dev): rename the index directory to a `.bak-*` backup and rebuild a fresh empty index (backup-first, guarded).
- `BLUE_GREEN_MIGRATE` (availability-first): start Blue (existing active generation) in **read-only** mode for search, build Green in a fresh generation directory, then cut over by swapping `state.json` and restarting the Worker.

### Current `BLUE_GREEN_MIGRATE` behavior (MVP, as of 2025-12-15)

- **Build verification**:
  - The Worker stamps Lucene commit metadata key `build_state` (`BUILDING|COMPLETE`).
  - Cutover verifies `build_state=COMPLETE` and `index_schema_fp` before swapping `state.json`.
- **Cutover fence (`SWITCHING`)**:
  - The Worker enters `SWITCHING` near the end of migration (a small “quiesce + buffer” window) and enforces a `SWITCHING` deadline; if it can’t drain, it marks the migration `FAILED` (no pointer swap).
  - Failed indexing jobs do **not** block auto-cutover by default (failures are surfaced via status as `failed_count` / unhealthy).
    - Optional guardrail: set a failure budget to block auto-cutover and keep Blue active:
      - env: `JUSTSEARCH_INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS`
      - sysprop: `-Dindex.migration.cutover.max_failed_jobs=<N>`
    - Nuance: “file not found” jobs are treated as **deletes**, not FAILED, to avoid counting benign races as failures.
- **What is buffered during `SWITCHING` (durable, Worker-side)**:
  - `submitBatch`, `deleteById`, `deleteByPath`
  - VDU mutations: `updateVduResult`, `markVduProcessing`, `recoverVduProcessing` (buffered as `VDU_RECOVER_PROCESSING`)
  - `syncDirectory(force=true)` is buffered as `SYNC_ROOT(root, force)`
  - These are stored durably in `jobs.db` (`switch_buffer`) and replayed after the Worker restarts on the new active generation.

## What is actually enforced today (2026-08)

This section records the difference between the design above and the shipped runtime. Verified at source while investigating sandbox round 10 (tempdoc 804 §D1).

- **The commit-metadata parity guard never enforces.** `HeadlessApp.java:267` (`setupInfra`) and `HeadlessApp.java:607` (the sidecar entry) both set `justsearch.index.parity.allow_mismatch=true` **unconditionally** — no dev/prod condition — and `WorkerSpawner` forwards `INDEX_PARITY_ALLOW_MISMATCH` into the Worker JVM. So `IndexMetadataParityGuard.checkOnOpen()` logs `Parity mismatch detected but justsearch.index.parity.allow_mismatch=true; continuing in WARN mode.` and returns. The `SCHEMA_MISMATCH` it would otherwise raise on a rebuild-requiring key (`analyzer_fp` / `schema_ver` / `index_schema_fp`, `ParityDiagnostics.REBUILD_REQUIRING_KEYS`) is never raised, so `index.schema_mismatch.policy` never acts on a fingerprint mismatch. Treat “`FAIL_CLOSED` is shipped production enforcement” as **false** for the fingerprint path.
- **Still live**: the `FieldInfos` inspection in `ComponentsFactory` (an index that genuinely cannot accept writes under the current field mapping) raises `SCHEMA_MISMATCH` on its own and *is* routed through the policy. That is the detector this document’s “Problem” section is about.
- **The schema fingerprint is advisory, not a search gate.** `index_schema_fp` is the SHA-256 of the canonical `SSOT/catalogs/fields.v1.json` (`SsotCommitMetadataSource.java:81-93`, plus a vector-dimension override when set), compared in `IndexStatusOps.safeSchemaCompatState()`. Nothing on the query path reads the schema compat state: the dense leg is gated by the **embedding** fingerprint (`EmbeddingCompatibilityController.allowQueryEmbeddings()`, consumed at `SearchPlanner.java:87`). An index can report `schema BLOCKED_MISMATCH` and serve fully hybrid (semantic + keyword) results — round 10 reproduced exactly that.
- **The fingerprint over-triggers.** Because it is a content hash of the whole catalog file, *any* byte edit flips it — including annotation-only edits with no physical index consequence. The three post-v0.1.0 catalog edits (one dead-field deletion, three `rmwPolicy` annotations) each flipped `index_schema_fp` for a v0.1.0 index that was, physically, still perfectly compatible.
- **Do not “fix” this by enabling the guard.** Turning `allow_mismatch` off (or forcing `FAIL_CLOSED`) with today’s fingerprint would have refused to start on a fully working index. The truthful fix is a fingerprint over *physical* schema (or per-field consequence classes), which is not implemented; until then the schema state must be presented as advisory. The UI treats it that way as of tempdoc 804 (`index.schema_mismatch` is `info`-severity and is not in the frontend’s reindex-cause bucket).

## Tradeoffs of the current fix

- **Fail-closed can reduce availability**: `FAIL_CLOSED` means the Worker may not start (search downtime) unless you opt into rebuild or blue/green migration. With today’s content-hash fingerprint that downtime would also be *unjustified* on the common case (see above), which is why the parity detector is currently in WARN mode.
- **Backup-first rebuild is still destructive to the active index** (but preserves a backup): rebuild time can be large.
- **Blue/green migrate increases complexity**: it relies on generation state (`state.json`) and a cutover step; during migration search can be briefly stale (Blue) while writes go to Green.
- **Partial coverage**: this check targets the class of failures we saw (keyword index options / docvalues mismatches). Other incompatibilities may still require additional guards or fingerprint parity to catch earlier.

## Additional codebase nuance

- **Commit-metadata parity is wired but currently warn-only in every run**:
  - The parity guard wiring/ordering has been fixed so it checks the effective on-disk index path.
  - Parity mismatches are warn-only whenever `justsearch.index.parity.allow_mismatch=true` — which the Head sets unconditionally today (`HeadlessApp.java:267` / `:607`), so this is the shipped behavior, not a dev/demo opt-in. See [What is actually enforced today](#what-is-actually-enforced-today-2026-08).
  - Legacy indexes may still require the `FieldInfos` inspection fallback when commit metadata is missing.
- **`/api/status` no longer probes Lucene files in the Head process**:
  - Index availability and failures are surfaced via Worker-reported status + explicit Worker startup error capture.
- **Dev defaults matter**:
  - A production profile resolves `index.schema_mismatch.policy=FAIL_CLOSED` by default — but only the `FieldInfos` detector can reach it today (the fingerprint detector is warn-only, above).
  - Dev/demo can use `REBUILD_BACKUP_FIRST` when you prefer “self-heal” over strictness.
  - If you want “no search downtime on mismatch”, use `BLUE_GREEN_MIGRATE`.


