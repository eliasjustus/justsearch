---
title: "Troubleshooting: Reindex doesn't run (schema mismatch)"
type: reference
status: stable
updated: 2025-12-16
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

- `FAIL_CLOSED` (recommended in production): fail startup and surface a deterministic “schema mismatch / migration required” error via `/api/status` (Head carries the Worker start error string).
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

## Tradeoffs of the current fix

- **Fail-closed can reduce availability**: `FAIL_CLOSED` means the Worker may not start (search downtime) unless you opt into rebuild or blue/green migration.
- **Backup-first rebuild is still destructive to the active index** (but preserves a backup): rebuild time can be large.
- **Blue/green migrate increases complexity**: it relies on generation state (`state.json`) and a cutover step; during migration search can be briefly stale (Blue) while writes go to Green.
- **Partial coverage**: this check targets the class of failures we saw (keyword index options / docvalues mismatches). Other incompatibilities may still require additional guards or fingerprint parity to catch earlier.

## Additional codebase nuance

- **Commit-metadata parity now runs (but is still not the only migration signal)**:
  - The parity guard wiring/ordering has been fixed so it checks the effective on-disk index path.
  - Parity mismatches can be configured as warn-only (`justsearch.index.parity.allow_mismatch=true`) for dev/demo runs.
  - Legacy indexes may still require the `FieldInfos` inspection fallback when commit metadata is missing.
- **`/api/status` no longer probes Lucene files in the Head process**:
  - Index availability and failures are surfaced via Worker-reported status + explicit Worker startup error capture.
- **Dev defaults matter**:
  - Production should default to `index.schema_mismatch.policy=FAIL_CLOSED`.
  - Dev/demo can use `REBUILD_BACKUP_FIRST` when you prefer “self-heal” over strictness.
  - If you want “no search downtime on mismatch”, use `BLUE_GREEN_MIGRATE`.


