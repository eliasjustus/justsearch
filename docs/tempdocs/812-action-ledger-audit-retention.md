---
title: "812 — Action-ledger audit retention & projection (T-D): design"
status: "design settled 2026-08-06 by the campaign orchestrator; implementation NOT started; one owner ratification requested (§R1)"
created: 2026-08-06
updated: 2026-08-06
related: [809, 810, 550, 612, 561, 565]
---

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

## Not built (scope discipline)

No re-sourcing of the rail from the ledger (550's rejection stands). No cursor pagination
yet. No per-doc index durability — the journal records *that and what* a scan did, not 100k
per-file rows. No new severity taxonomy — `isRoutineActivity` is reused. No retro-backfill
of pre-journal history (impossible; the data no longer exists — stated plainly in the
Activity empty-state after first upgrade).
