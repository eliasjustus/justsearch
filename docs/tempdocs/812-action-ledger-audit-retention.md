---
title: "812 — Action-ledger audit retention & projection (T-D): design"
status: "design settled 2026-08-06; R1 adopted by default under the owner's autonomous-proceed directive (2026-08-06); ALL FOUR SLICES IMPLEMENTED 2026-08-06 — D1+D3 (audit journal + kind/limit API, PR #381), D2+D4 (scan rollup + FE audit-first tier, see the implementation record)"
created: 2026-08-06
updated: 2026-08-06
related: [809, 810, 550, 612, 561, 565]
---

> **Implementation correction (2026-08-06, D1):** the design's D1 said "StoreCatalog
> registration + check-store-recoverability". Implementation established that
> `StoreCatalog` is encryption-scoped (an entry without a `StoreCipher` would falsely
> declare seal-at-rest, and `StoreCatalogTest` deliberately pins the authored set), and
> that the gate's actual demand is a `durableStores` authority row — the precedent the
> three existing HEAD-owned journals (`durable-grants`, `run-events`,
> `file-operation-journal`) already follow. The journal is registered as a
> `durableStores` row only; this note supersedes D1's registration sentence.

# 812 — Action-ledger audit retention & projection (T-D)

Thread T-D of the human-validation campaign (finding 6 in tempdoc 809; charter in 810).
Ground truth from a read-only source investigation, 2026-08-06, main @ 3d3ee489.

## Correcting finding 6's mechanics (the defect is real; the mechanism is different)

Finding 6 said a full-corpus ingest "flushes every operation and grant entry out of the
retrievable window entirely." **The code does not do that.** `ActionEventStore`
(`app-observability .../ledger/ActionEventStore.java:36,42,62-89`) is a 500-entry in-memory
ring whose eviction is **index-first**: the oldest `INDEX` event is sacrificed before any
actor event, and only when no INDEX row remains does it drop the eldest overall. Under a
5,190-doc ingest, actor rows (grant/gate/operation) survive in-window; what is destroyed is
~4,690 per-document index outcomes. The observed 391/74/24/11 composition is the ring at
capacity mid-eviction.

The real defects are harsher than the misread one:

1. **Nothing persists.** The ring is a field of `ActionLedgerChangeRegistry` (`:37`),
   in-memory, per-process. **The audit trail resets on every Head restart.** No file, no
   rotation, no replay. Tempdoc 550 recorded disk persistence as unresolved (`550:297`) and
   explicitly rejected re-sourcing the rail from the ledger *because* it found this ring
   (`550:468`); no recorded decision grants retention to `grant` rows. The only durable
   grant artifact is `durable-grants.json` — current state, not an audit trail
   (`DurableGrantStore.java:297`).
2. **Actor rows still have a cliff.** Eviction order protects them only while INDEX rows
   remain to sacrifice; a long-lived session whose actor rows alone approach 500 evicts
   audit records with no warning and no durable copy.
3. **The API is an unpaginated whole-ring dump** — no `kind` filter, no `limit`, no cursor
   (`ActionLedgerController.java:190-216`); three FE consumers read the same snapshot
   (ActivitySurface, agent History via `originator=agent&correlationId`, AiActivityDigest).
4. **Index rows are audit-illegible by construction**: `ActionEvent.Index` carries
   `pathHash, collection, state, attempts, errorMessage` (`ActionEvent.java:151-166`) — no
   path (deliberate, `:145-149`), no scanId/batchId/root, so no rollup key except
   `collection`, and the FE ledger view never uses the existing `core.resolve-path-hash`
   resolver (the tasks bridge does, `indexingJobsBridge.ts:139-153`).
5. **Doc drift**: `docs/reference/api-contract-map.md:231` omits `index` from the kind list.

## Design

Principle (from 612:337 — Activity is an AUDIT surface — plus the write-time-witness
lineage of 798): **an audit record's lifetime must be a stated guarantee, not a side effect
of ring pressure.** Three tiers with different guarantees, matching what each kind is for:

### D1. Durable audit journal for actor events

A new append-only on-disk journal for **`grant`, `gate`, `operation`** kinds (the
consequential ~7%): JSONL under the data dir, size-bounded by file rotation (e.g. 4 MB × 8
generations — bounds growth without ever dropping the newest records silently), written
synchronously at the same call sites that append to the ring today (one new sink beside the
ring in `OperationSubstrateInit.java:195-219` and `ConsentCapsuleService` /
`AgentRunLedgerProjector` paths — the producers already fan out through listeners, so this
is a sink addition, not a rewrite). The ring stays as the hot unified feed exactly as 550
left it; **this does not re-source the rail** (550:468's rejection stands — the rail keeps
its own stores; the journal is a write-behind copy for audit reads only).

Consequences to handle: the journal is a new store → `StoreCatalog` registration +
`check-store-recoverability`; a new file under the data dir → `check-runtime-manifest-closure`
if it lands in `runtime/` (prefer a sibling `audit/` dir to keep the runtime manifest
closed); restart behavior: on boot the ledger READ path serves ring ∪ journal-tail so the
Activity surface no longer starts empty after every restart.

### D2. Batch identity + rollup for index events

Per-document `index` events remain ring-only ephemera (operational telemetry, not audit) —
but the *summary* becomes an `operation`-kind record and therefore durable: thread the
existing scan identity (`/api/knowledge/ingest` already returns `scanId` for directory
inputs) through the job records onto the bridge (`IndexingJobsBridgeWiring.java:63-106`),
and emit `operation` events for scan start/completion carrying `{scanId, collection, root,
docsDone, docsFailed, durationMs}`. The Activity default view then shows "Indexed 5,184
documents · scifact · 6m 12s" as one durable row, expandable to the surviving in-ring
per-doc rows (resolved to names via `core.resolve-path-hash`, closing the
`Indexed · default (f7e852)` illegibility) — matching finding 6's suggested projection but
grounded in a capture-side key instead of the FE's current adjacent-run render heuristic
(`ActionLedgerView.ts:403-428`), which stays as fallback for keyless legacy rows.

### D3. API: filters and bounds

`GET /api/action-ledger` gains `kind` (repeatable) and `limit` (default 500, capped) —
additive, existing consumers unaffected; agent History and AiActivityDigest can then stop
over-fetching. Cursor pagination is deliberately **deferred** until a consumer needs to walk
the journal beyond its tail (YAGNI; the journal files are the deep-history interface for
now). Fix the `api-contract-map.md:231` kind list in the same change.

### D4. FE default tier

Default Activity view = durable tiers (operations incl. scan rollups, grants, gates) +
non-routine effects; navigations and per-doc index rows behind the existing routine toggle —
the `isRoutineActivity` vocabulary (`messageRouting.ts:109-140`) already grades this; no new
classification invented.

## Bite proof (required)

- Journal: full-corpus ingest (≥ 5k docs) + Head restart → every pre-restart `grant`/
  `operation`/`gate` row still retrievable via the API. Fails today (everything vanishes).
- Cliff: append >500 actor events → none silently lost (journal has them all; ring holds
  the newest; a WARN records ring eviction of an actor row).
- Rollup: an N-doc scan yields exactly one durable scan-completion `operation` row with
  correct counts; ui-shot asserts one batch row, not N (finding 6's regression home).
- D3: `kind`/`limit` filters covered by controller tests; contract-map doc matched by test.

## R1 — Owner ratification requested

The one genuine product decision inside this design: **actor events become durable on disk**
(new file family in the data dir, survives restarts, bounded by rotation). Everything else
is projection/API mechanics that follow from calling Activity an audit surface. If the owner
instead wants the ledger to remain session-scoped by design, D1 is replaced by an explicit
"session-scoped, resets on restart" label on the Activity surface (the honest-label fallback)
and D2-D4 still stand. Default on silence: D1 as designed — a private-retrieval product
whose TYPED_CONFIRM audit trail evaporates on restart is the same defect class round 7-13
spent the campaign eliminating, applied to the audit layer itself.

## D2 + D4 implementation record (2026-08-06, branch `worktree-hv-b8`)

**Scan identity: the REAL key, not the bridge-side fallback.** The design permitted deriving rollup
identity from enumerator state if the job records could not carry a scan id; they can, so they do.
`jobs.scan_id` (SQLite migration V7→V8, `SqliteSchema.MIGRATE_V7_TO_V8_ADD_SCAN_ID`) →
`IndexingJobChangeFeed.JobRow.scanId` → proto `IndexingJobView.scan_id = 8` →
`app-api IndexingJobView.scanId` → `ActionEvent.Index.scanId`. `WorkerScanOps.flushBatch` passes
`ScanRequest.scanId()` (already present, worker-minted, already on `ScanRootProgress.scan_id`) into
`JobQueue.enqueue(paths, collection, scanId)`. Nullable/empty everywhere: single-file ingests, the
watcher, and pre-812 rows stay keyless and keep the FE's adjacency collapse. **Defect found and
fixed on the way**: `KnowledgeSearchController.handleIngest` returned a locally-minted
`UUID.randomUUID()` as the response `scanId` while the worker-allocated id (already returned by
`KnowledgeHttpApiAdapter.scanRoot` in `KnowledgeIngestResponse.scanId`) was discarded — every
`GET /api/scans/{scanId}/progress` subscribe against the returned id resolved to
`UNKNOWN_SCAN_OR_RETENTION_EXPIRED`. The endpoint now returns the worker's id, which is also the
rollup key.

**Rollup emission.** `ScanRollupLedger` (app-observability, beside the log it projects) opens a scan
on the adapter's first progress frame (`scanStarted`, emits a `STARTED` row so an unfinished scan
still left a trace), learns the admitted count when the walk ends (`scanEnumerated`), and counts
terminal outcomes off a new typed `ActionLedgerChangeRegistry.addEventListener` seam. It emits the
`FINISHED` row when every admitted document reached a terminal state (`COMPLETED`) or when the scan
goes quiet for 120s (`PARTIAL`). Emit chain: `ScanRollupLedger.emit` → `emitExecutor.execute` →
`ActionLedgerChangeRegistry.broadcastActionEvent` → `publish` → `store.append` + `channel.publish`
— the same fan-out every other actor event uses, so D1's journal picks it up for free.
`ActionEvent.ScanRollup.kind()` returns `OPERATION` deliberately: kind-keyed consumers (journal,
`kind` filter, FE tier split, index-first eviction) must treat it as consequential without each
learning a seventh kind.

**Counts are the real terminal states**, never the admitted count: the aggregator only counts
`ActionEvent.Index` events, which `IndexingJobsBridgeWiring.terminalIndexEvent` emits solely for
`DONE`/`FAILED` rows (`IndexingJobsBridgeWiring.java:95-97`). `docsAdmitted` is carried separately so
a partial scan reads as "N of M".

**D4 default tier.** `isRoutineActivity` gained ONE line — per-document `index` rows are routine
(the scan rollup is their audit record) — reusing the existing vocabulary rather than inventing a
classification; `isRoutineBackendRow` grades it before the direct-user guard because index rows are
system-originated. Grants, gates, operations and scan rollups are unaffected on every originator
(asserted explicitly in both FE test files). A rollup renders as one collapsible row expandable to
the per-document rows of THAT scan (matched by scanId), each resolved to a friendly name via the
same `core.resolve-path-hash` resolver the Tasks bridge uses; the row-altitude formatter moved to
`hooks/resolvePathLazy.ts` (`friendlyPathName`) now that it has two consumers. A scan's `STARTED`
row is suppressed once its completion row exists.

**Known gaps (logged as observations, not fixed here).** (1) The MCP/agent ingest path constructs a
SECOND `KnowledgeHttpApiAdapter` that never receives `setScanProgressRegistry` — nor the new
`setScanRollupLedger` — so agent-driven directory ingests get neither progress SSE nor a rollup.
(2) Watched-root scans dispatch `RemoteKnowledgeClient.scanRoot` directly, bypassing the adapter:
their jobs carry a worker-minted scanId but no Head-side scan is opened, so they produce per-document
rows with no rollup row.

**Merge with D1/D3 (#381) and 813 (#377), 2026-08-06.** Two resolutions worth recording because
both were near-silent:
1. **Migration-number collision.** 812 D2 and 813 Slice B were developed in parallel and BOTH
   authored a `V7 → V8` step (`scan_id` and `size_bytes`). Git auto-merged `SqliteSchema.java`
   with NO conflict marker: `TARGET_VERSION` stayed 8 while two different V8 constants existed, so
   whichever `case 8` survived would have left every installed queue at `user_version = 8` with the
   other column missing and the ladder finished — a permanently half-migrated database. 813 merged
   first, so it keeps V8; 812's `scan_id` is now `MIGRATE_V8_TO_V9_ADD_SCAN_ID` (`case 9`,
   `TARGET_VERSION = 9`).
2. **One enqueue write path, two facts.** 813 made `enqueueEntries` the single jobs-table write
   path *because* the insert is `INSERT OR REPLACE` — any column the call omits is reset on
   re-enqueue. D2's scan key needed exactly that property, so it rides the same signature
   (`enqueueEntries(entries, collection, scanId)`) rather than the separate `enqueue(paths,
   collection, scanId)` overload it originally added; a parallel path would have made a scan's rows
   carry sizes or a scan key, never both. Two traps inside that unification, both caught before the
   first build rather than by a test: the new overload's *default* delegates to the two-arg
   `enqueueEntries` (not straight to `enqueue`), or the three size-recording test fakes — which
   override only the two-arg form — would have stopped seeing scan enqueues entirely; and
   `SqliteJobQueue` must override the two-arg form as well, or `enqueue → enqueueEntries → enqueue`
   closes into infinite recursion.
3. **A journaled rollup must recover as a rollup.** D1's read path (`fromWireRow`, added by #381)
   rebuilt every `kind=operation` row as `ActionEvent.Operation`. A scan rollup is durable
   *because* its `kind()` is OPERATION — so it journaled correctly and then came back after every
   restart as a bare operation with its counts and scan key gone, rendering "Indexed 0 documents".
   Neither PR had this defect alone; the merge created it. `fromWireRow` now reconstructs the
   rollup (and round-trips `index.scanId`), with `ActionEventJournalTest.scanRollupRoundTripsWithItsSummary`
   as the regression home.

**Merge regression homes.** Three tests exist because the merge, not either feature, could break
them: the V7→V9 ladder walk asserts BOTH `size_bytes` and `scan_id` after one migration (proved to
bite by pinning `TARGET_VERSION` back to 8); `WorkerScanOpsTest.scanIdIsStampedOnTheEnqueuedJobs`
asserts the scan key reaches the queue through the rewired `enqueueEntries` seam alongside 813's
sizes (proved to bite by dropping the argument); and the journal round-trip above.

**Verification.** Full `./gradlew.bat test` BUILD SUCCESSFUL; ui-web `npm run typecheck` clean +
`npm run test:unit:run` 4102 passed; `operation-surface`, `register-guard-resolution` gates and
`check-wire-schema-types-regen` pass; `SSOT/schemas/indexing-job-view.v1.json` recaptured. New tests:
`ScanRollupLedgerTest` (9), `IndexingJobsChangeStreamTest.scanIdRoundTripsFromEnqueueToDelta`,
`IndexingJobsBridgeWiringTest.carriesScanId`, 5 view tests + 5 client tests. Six pre-existing view
tests changed tier deliberately (they used a per-doc index row as the stand-in for "a system event
the user opened Activity to see"; that role is now the scan rollup) — each rewritten to keep its
original intent, not weakened.

## Not built (scope discipline)

No re-sourcing of the rail from the ledger (550's rejection stands). No cursor pagination
yet. No per-doc index durability — the journal records *that and what* a scan did, not 100k
per-file rows. No new severity taxonomy — `isRoutineActivity` is reused. No retro-backfill
of pre-journal history (impossible; the data no longer exists — stated plainly in the
Activity empty-state after first upgrade).
