---
title: "Incremental indexing correctness: finish the stalled 418 dual-watcher migration to a single authority — the reconciler (snapshot-diff) is the source of truth, the watcher is demoted to a pure fast-path, and the reconciler's drift observation becomes a first-class tri-state condition on the existing AssertedCondition/CAUSE_ROWS legibility seam (an instance of 627's Observation-Actuation Closure applied to external-world correspondence)"
type: tempdocs
status: "IMPLEMENTED & MERGED (2026-06-21, main 11b62dac6) — all phases (single watcher authority, worker-DELETE data-loss fix, non-silent reconcile, drift legibility on three surfaces) + the §Recency follow-on (per-root lastVerifiedAt heartbeat, scoped core.reconcile-root recovery) landed. The 626↔628 recover-to-empty→reconcile chaining is closed BY 628's merged G3 rebuild-from-source chain + CorruptionRebuildE2ETest. Deferred-by-design (named, not built): content-hash detector, change-feed ladder (USN/ExW/fanotify — FFM-cheaper but elevation/library-gated), the condition-seam as-of. Remaining honest gap: a live smoke of the Move-C recovery button (HealthSurface) when a dev stack is free."
created: 2026-06-21
updated: 2026-06-21
author: agent analysis (engine-reliability gap scan), filed by agent — investigated 2026-06-21
category: engine / indexing / file-watch / incremental / correctness / durability / product-shape
principle: "the index is a faithful, eventually-consistent mirror of the watched filesystem — correctness is owned by a single convergence authority and proven by a standing guard, not emergent from scattered watcher/freshness/re-index logic (structural-defects-no-repeat)"
related:
  - 588-worker-engine-silent-failures                # adjacent: fixed the indexing LOOP dying; did NOT cover change-CORRECTNESS
  - 599-folder-indexing-journey-no-status            # adjacent: first-run status UX (orthogonal layer)
  - 607-vdu-ocr-extraction-logic-analysis            # adjacent: extraction quality (orthogonal layer)
  - 627-process-supervision-crash-recovery           # sibling reliability tempdoc (this T1 triad)
  - 628-index-durability-corruption-recovery         # sibling reliability tempdoc (this T1 triad)
---

> NOTE: Noncanonical working tempdoc. **Investigated 2026-06-21** (see §Investigation). The
> §Thesis…§Definition-of-done above is the original charter; the §Investigation section that
> follows verifies the live topology against `main`, builds the transition matrix, and records
> findings tagged **[VERIFIED `file:line`]** vs **[HYPOTHESIS — needs test]**. No design or
> implementation has been done yet (per assignment) — §Investigation is understanding, not a plan.

# 626 — Incremental indexing correctness

## Thesis

The product's core promise is "the index stays correct as my files change." Today that correctness is
**emergent** — smeared across the watcher, the freshness layer, and the re-index path, each making local
decisions — rather than **owned and proven**. Recent indexing tempdocs are adjacent but orthogonal:
588 fixed the indexing loop *dying* (not change-correctness), 599 covered first-run *status UX*, 607
covered *extraction quality*. The file-lifecycle correctness of the watch→re-index path has not had a
dedicated tempdoc since the 200s.

## Goal (more than validation)

The audit is the **entry point**, not the destination. The goal is to leave behind a structural
guarantee, observability that makes failure loud, and a standing guard against regression:

1. **Verify** the filesystem-transition matrix (create / modify / move-within-root / move-across-root /
   rename / delete / rapid-save burst / watcher-drop + rescan) — each cell gets a verdict against source.
2. **Establish a convergence authority** — a single place that owns "given this fs event, the index must
   reach state X," so correctness isn't re-derived independently by watcher + freshness + re-index.
3. **Make divergence loud** — a reconciliation/parity check that detects index-vs-disk drift (orphaned
   stale hit, missed update) and surfaces it, so drift becomes observable instead of silent.
4. **Durable guard** — a standing property-style test over the transition matrix so a future watcher
   change cannot silently reintroduce a drift class.

## Polish dimension (subordinate, gated)

Leave each subsystem touched more legible than found — **only what the reliability work already
modifies.** Bias toward polish that *serves* the goal: diagnostic/log/error legibility (that IS the
"make failure loud" sub-goal) and structural decomposition of god-classes that make this path hard to
reason about. Found-but-untouched polish → `docs/observations.md` inbox, not this tempdoc. Exclude
user-facing/UI polish (that is the frontend stream's lane — 599/600/604).

## Scope

- **In:** change-*detection* and *convergence* correctness across the watch→freshness→re-index path.
- **Out:** extraction quality (607), first-run status UX (599), the loop-survivability already fixed
  in 588.

## Starting points for the assigned agent (UNVERIFIED — audit these)

- `modules/app-indexing/src/main/java/io/justsearch/app/indexing/watch/*`
  (`FileWatcherStrategy`, `MethvinWatcherStrategy`, `WatcherEventTags`), `WatcherBootstrap`
- `modules/app-services/.../worker/BurstDetector.java`, `WatcherEventOps.java`
- `modules/worker-services/.../services/RootWatcherRegistry.java`, `WorkerMethvinWatcher.java`
- `modules/worker-services/.../loop/FileFreshness.java`, `FileFreshnessSnapshot.java`
- `modules/worker-core/.../util/Sha256SidecarCache.java`

## Discipline (binds this tempdoc)

Discovery before work — nothing becomes an implementation item until verified against source; record
refuted hypotheses as dead ends so they aren't re-walked (588 pattern). Every fix lands with a
regression test (`audit-driven-fixes-need-test`). Fix defects as instances of the *class*, with a guard
that holds the structural fix (`structural-defects-no-repeat`).

## Definition of done

A transition-matrix audit with per-cell verdict + `file:line` evidence; a named convergence authority;
a drift-detection mechanism; and a green standing guard over the matrix (plus documented
"correct-by-design" for cells that already hold).

## Next step (not done here)

Assign an agent; first deliverable is the transition-matrix discovery audit.

---

# As-built (2026-06-21, implementation — branch `worktree-626-incremental-indexing`)

> Implementation of the approved plan. Phases 1–5 are **landed + verified**; Phases 6–7 remain.
> All work in the worktree; not yet merged.

**Phase 1 — Worker DELETE unmount-guard (data-loss fix) ✓ tested.** `WorkerMethvinWatcher.handleDelete`
now skips when `!Files.exists(root)` (root captured in the listener lambda), mirroring the Head's 599
guard. Regression tests `deleteCascadeIsSkippedWhenWatchedRootIsGone` / `deleteForChildIsForwardedWhenRootExists`.

**Phase 2 — Overflow/burst recovery relocated onto the Worker watcher ✓ tested.** New
`GrpcIngestService.reconcileRoot(root,force)` (routes through the full `syncDirectory` pipeline with a
swallowing observer) is injected as a `BiConsumer` reconcile sink. Worker OVERFLOW → forced reconcile
(off an executor thread); a ported `WorkerBurstDetector` schedules a coalesced reconcile on event spikes.
Tests: `overflowSchedulesForcedReconcile`, `WorkerBurstDetectorTest` (3).

**Phase 3 — Reconciler made non-silent ✓.** The 200k delete-detection skip now returns a distinct
`delete_detection_unverified` (new additive proto field on `SyncDirectoryResponse`) instead of a silent
`skipped`. Cadence documented as a contract on `SyncOps.SYNC_INTERVAL_SECONDS`; the mtime+size freshness
contract + limitation documented on `FileFreshness`.

**Phase 4 — Drift verification state on the wire ✓ tested.** `SyncOps` records the per-root outcome
(unverified set on cap-skip, cleared on clean sync, untouched on user-activity skip) into a transient
`WatchedRootsState` set → `IndexingService.WatchedRoot.deleteDetectionUnverified` → `IndexingController`
map → `IndexedRootView.deleteDetectionUnverified` (+ schema `indexed-root.v1.json` both copies + regen FE
type). Tests: `SyncOpsReconcileVerificationTest` (4).

**Phase 5 — FE drift legibility ✓ tested + BROWSER-VALIDATED.** `folderStatus.ts` gains an `unverified`
state/glyph (the ONE authority — `check-folder-status-derivation` green) so an indexed-but-unverifiable
folder shows a caution glyph **never the green ✓** (the 599 false-"✓" class generalized), with a per-folder
**Reindex** button (`core.reindex {force:true}`). Both icon renderers updated. Unit: `folderStatus.test`
(15 incl. 2 new); `npm run typecheck` clean. **Live browser capture (`jseval ui-shot library --fixtures`
with a temporary unverified row): the folder rendered the amber caution triangle + "couldn't verify
deletions — reindex to be sure"; axe 0 new, 0 real console errors.** Fixture reverted (not committed).

**Verification notes.** Targeted module suites green for all touched code (worker-services watcher/burst,
app-services sync/recorder). Three unrelated failures observed are environmental, NOT from this change:
`AiInstallServiceLateBindTest` ×2 (`Worker lib directory not found` — worktree not `installDist`-prepared)
and the known-flaky `VDU pdf-image-only` (#503). The umbrella `./gradlew build -x test` is blocked by a
**pre-existing** `ssotValidateExec` failure on `field-catalog.schema.json` (reproduced with this change
stashed; base is red — cf. obs #485), unrelated to 626.

**Phase 6 — Head watcher stack deleted (fork ended) ✓ compiled + tested.** Removed the redundant
Head-side event source entirely: `WatcherBootstrap`, `MethvinWatcherStrategy`, `FileWatcherStrategy`, Head
`WatcherEventOps`/`WatcherEventCallbacks`/`BurstDetector`, and the now-dead `WatcherMetricCatalog`/
`WatcherEventTags` (the `index.watcher.*` metric is Worker-only now) + their tests. Rewired
`RootLifecycleOps` (worker watch is the sole event source; `startWatcherIfAvailable`/`clearAllRoots`/
`removeWatchedPath` no longer touch a Head watcher; constructor lost `burstDetector`/`watcherEventOps`),
`RemoteKnowledgeClient` (dropped `WatcherEventOps`/`burstDetector`/`setWatcherBootstrap`),
`KnowledgeServerBootstrap` (dropped the watcher bootstrap; **kept** `reindexPersistedRoots` +
`startPeriodicSync` — the reconcile backstop), and the Head-boot metric registration in `HeadlessApp` +
`LauncherEnvironment`. Updated `RootLifecycleOpsIdempotencyTest` for the new constructor. The Worker
watcher is now the single event source; the periodic sync + startup reindex remain the source-of-truth
reconcile. `app-services` + `app-indexing` + `ui` + `app-launcher` compile; `app-services`/`app-indexing`
suites green (incl. the now-dist-prepared `AiInstallServiceLateBindTest`).

**Phase 7 — standing guards ✓ (met by the phase tests).** The pure-function guards the plan asked for are
in place: the unmount-guard predicate (`WorkerMethvinWatcher.handleDelete` — `deleteCascadeIsSkipped…`),
the reconcile verified/unverified classifier (`SyncOpsReconcileVerificationTest`, 4 cells), the burst
threshold (`WorkerBurstDetectorTest`), and the FE transition matrix (`folderStatus.test`, the
ready/unverified/indexing/unavailable cells). All run in default `check` (no JVM-spawn, ADR-0026-safe).

**Status: all 7 phases landed in the worktree.** The umbrella `./gradlew build -x test` remains blocked
only by the pre-existing `ssotValidateExec`/`field-catalog` base failure (not from 626). Pre-merge from a
green base: run `prepare-worktree` + the full suite, then `spotlessApply` + `build -x test`.

**Phase 8 — Axis C reconciled to its named legibility seam ✓ tested + BROWSER-VALIDATED.** A
conceptual-alignment review found the original Phase-5 work surfaced drift only via the per-folder
`folderStatus` glyph, not the tempdoc's titular thesis (drift as a first-class condition on the
`AssertedCondition`/`ConditionStore` seam — 627's Observation-Actuation Closure). Closed that gap with a
new **`IndexDriftHealthTap`** (`app-services/.../observability/health/`): a Head-side snapshot tap that
reads the per-root `WatchedRoot.deleteDetectionUnverified()` flag (Phase 4) and upserts/clears per-root
`index.drift-unknown` `AssertedCondition`s (subject = hashed root path, `status=TRUE`, severity=WARNING,
recovery = `core.reindex {force:true}`) into the existing `ConditionStore` — mirroring `WorkerSnapshotTap`,
wired late-bound into `StatusLifecycleHandler.buildStatusMap` via `CoreApiAssembly`. The condition reaches
the FE through the existing SSE `/api/health/events/stream` → HealthSurface, with the `healthEventActivityRow`
+ `ConditionRecoveryIndex` recovery path — **no new FE render code**. i18n entry added to
`health-events.en.properties`. The shipped per-folder glyph is kept as the precise complementary surface.

> *Two corrections to the original Axis-C design text, found in investigation:* (1) `AssertedCondition`/
> `ConditionStore` and `CAUSE_ROWS`/readiness are **separate seams** — conditions are per-subject (HealthSurface,
> ungated), `CAUSE_ROWS` is the *system-global* gated verdict banner; a per-root drift signal belongs in the
> condition seam (one folder's drift must not degrade the whole-system verdict), so **no `CAUSE_ROWS` change**.
> (2) `WorkerOperationalView` is scalar/global and cannot carry per-root data, so the literal "row in
> `WorkerSnapshotTap`" was not viable — a **Head-side tap** is the correct emitter (the flag already lives
> Head-side). User decision (locked): per-root condition **+** keep the folder glyph; no global banner.

Tests: `IndexDriftHealthTapTest` (5 — assert/clear lifecycle, recovery op, per-root subjects);
`WatcherTransitionMatrixGuardTest` (the consolidated §I.2 standing guard — Goal 4). **Browser validation
(`ui-shot health --fixtures` with a temporary injected `index.drift-unknown` condition + its recovery-index
entry): the condition was consumed and schema-validated in the real browser (0 `[WireContract]` errors),
populated `recommendedActions`, and flipped the System-Health header verdict from "All systems operational"
(green) → "Attention needed" (amber), rendering the Reindex recovery affordance.** Fixtures reverted (not
committed). (The specific recovery-panel row sits below the HealthSurface's internal-scroll-container fold,
which the viewport ui-shot harness can't grow into — the verdict flip is the user-visible proof.)

**Deliberately not built (scope):** the global readiness banner / `CAUSE_ROWS` (per-root ≠ system verdict);
content-hash detector; USN/ExW feed (deferred ladder); the `SyncDirectoryOps` pure-diff-function extraction
(per-cell guards + the matrix test already cover it); 626↔628 recover-to-empty→reconcile chaining (deferred
until 628 exists).

**Phase 9 — tri-state completed: `drift-detected` → `index.drift-corrected` Occurrence ✓ tested +
LIVE-VALIDATED.** Closes the last design gap (Axis C's tri-state; the bi-state shipped in Phase 8). De-risk
established the correct shape: the reconciler's **orphans-pruned** (`filesDeleted`) is the *clean* drift
signal (a deletion the live watcher missed); `filesAdded`/missing is **noisy** (re-counts in-flight files) so
it is **excluded**. Built as a one-shot `index.drift-corrected` **Occurrence** (informational/INFO — by the
time it surfaces the index is already correct), NOT a Condition. Flow mirrors the Phase-4/8 lane:
`SyncOps.syncDirectory` (force=false, `filesDeleted>0`) → `recordDriftCorrected` → `WatchedRootsState`
(per-root `{count, atMs}`) → `WatchedRoot.driftOrphan*` → `IndexDriftHealthTap` detects the at-ms advance
(dedup, mirroring `WorkerSnapshotTap.detectJobFailureOccurrence`) → `OccurrenceLog.append` +
`OCCURRENCE_APPENDED` broadcast → FE `healthEventActivityRow` lifecycle variant (no new FE code). i18n entry
added. Worker-in-process overflow/burst prunes are out of scope (overflow is already a non-silent signal).
Tests: `IndexDriftHealthTapTest` now 9 (5 condition + 4 occurrence — emit/dedup/re-emit/zero-count).

**Merge-enabler:** `class-size` declared-growth changeset (`gates/class-size/.changesets/626-…`) for
`StatusLifecycleHandler` (1237→1339, tap wiring) + `GrpcIngestService` (1955→2004, `reconcileRoot`) — the
gate now **passes** (`RemoteKnowledgeClient`/`HeadlessApp` *shrank* under pin from the Head-watcher deletion;
`KnowledgeServer` +3 is pre-existing base drift, not 626).

**Validation (this phase):** unit green (tap 9, SyncOps recorder 4); `--gate class-size` pass; `--gate wire`
pass; full-suite + gate isolation confirmed the only red is **pre-existing base** (607
`visualEnrichmentNeededCount`, `execution-surface`, `theme-token-closure`/RecentsMenu, `ssotValidateExec`) —
`deleteDetectionUnverified`/`driftOrphan` appear 0× in the conformance drift report. **Browser:** the
`index.drift-corrected` Occurrence was consumed + schema-validated in the real HealthSurface (0 `[WireContract]`
errors; verdict correctly unaffected — occurrences ≠ conditions); renders via the production-proven activity-feed
lifecycle path. **LIVE (retires the §B residual):** started the stack from this worktree's dist (Head watcher
deleted) → clean boot, worker ready, empty stderr; `core.add-watched-root` scan indexed `alpha.txt`; a **CREATE**
of `beta.txt` was indexed by the **sole worker watcher** (score 1.0), and a **DELETE** removed it from the index
immediately — confirming worker-only create/delete end-to-end with the Head watcher gone.

---

# Investigation (2026-06-21)

**Method.** First-hand read of the watch/freshness/sync source on `main`; cross-checked the
`io.methvin:directory-watcher` library + `WatchService` semantics against authoritative external
sources (library source, Oracle docs, Microsoft `ReadDirectoryChangesW`, IntelliJ VFS, Watchman).
No dev-stack experiment yet — the findings below are static (code + library semantics); the ones
that assert an end-to-end behavior are tagged **[HYPOTHESIS — needs test]** and are NOT to be
trusted until a regression test exists (`audit-without-test`).

## I.1 — Live topology (the single most important finding)

The stub assumed correctness is "smeared across the watcher, the freshness layer, and the re-index
path." The reality is sharper and more surprising: **there are TWO complete watcher stacks running
simultaneously in production, and the tempdoc-418 migration that was supposed to replace one with the
other never completed — the "soak window" became the steady state.**

**[VERIFIED]** Both watchers start for every registered root:
- **Head-side stack** (`modules/app-services` + `modules/app-indexing`): `WatcherBootstrap` →
  `MethvinWatcherStrategy` → events to `WatcherEventOps.createEventConsumer`. Started per-root at
  `RootLifecycleOps.java:485` (`watcherBootstrap.startWatcher(...)`). Wired unconditionally in
  `KnowledgeServerBootstrap.java:206-207` (gen-0 init). No config disables it — `WatcherBootstrap.createStrategy`
  only accepts `auto`/`methvin` and *throws* on anything else (`WatcherBootstrap.java:144-156`), so there is no
  "watcher off" path.
- **Worker-side stack** (`modules/worker-services`, tempdoc 418): `WorkerMethvinWatcher` →
  `JobQueue`/`deletePathSink`, registered per-root via the `WatchRoot` gRPC at `RootLifecycleOps.java:505`
  (`workerWatchFn.watch(...)`), wired in `DefaultWorkerAppServices.java:232-235`.
- The dual-run is **deliberate but transitional** by its own comment: `RootLifecycleOps.java:96-99` —
  "The Worker-side watcher runs alongside the Head-side watcher during the Phase B soak window; the
  SQLite jobs table uses INSERT OR REPLACE so duplicate enqueues from both watchers coalesce." The
  soak was never closed; this is current `main`.

**Consequence:** two native OS watch handles per root (2× `ReadDirectoryChangesW`/inotify), 2× event
processing, deduped *only for CREATE/MODIFY* at the SQLite `INSERT OR REPLACE` layer. DELETE and
OVERFLOW are NOT symmetric across the two stacks (see I.3). This single fact reframes the tempdoc: the
"convergence authority" the stub wants to *build* is partly an authority that needs to be *reclaimed*
from an unfinished migration — there are two authorities today.

**The reconciliation stack (the real backstop):**
- `SyncOps.startPeriodicSync` (`modules/app-services`, started at `KnowledgeServerBootstrap.java:212/216`):
  a 60s scheduler that syncs **ONE root per cycle**, round-robin, `force=false` (`SyncOps.java:122-172`).
- `SyncDirectoryOps.execute` (worker, `modules/worker-services`): prune orphans → compute indexed set →
  walk disk → enqueue missing → commit (`SyncDirectoryOps.java:78-136`).
- Startup reconciliation: `client.reindexPersistedRoots()` at `KnowledgeServerBootstrap.java:210/215`
  (gen-0 and on worker-recovery).

## I.2 — Filesystem-transition matrix (per-cell verdict)

| Transition | Live handling | Verdict |
|---|---|---|
| **Create** | Both watchers → enqueue; loop extracts (new doc) | OK **[VERIFIED]** path; reconcile walk also catches it |
| **Modify (mtime changes)** | methvin `LAST_MODIFIED_TIME` fires MODIFY → enqueue → loop `isUnmodified` sees new mtime → re-extract | OK **[VERIFIED]** `JobBatchExtractor.java:195` |
| **Modify (content changes, mtime preserved)** | methvin mtime-hash does NOT fire; sync enqueue + loop `isUnmodified` also key on mtime | **MISSED at every layer [VERIFIED]** — see I.3-F |
| **Delete** | Head path (guarded) AND worker path (unguarded) both fire | works, but **asymmetric guard [VERIFIED asymmetry / HYPOTHESIS data-loss]** — I.3-A |
| **Move / rename (within root)** | No move event — methvin emits DELETE(old)+CREATE(new), no ordering guarantee | likely OK; **ordering/atomicity unverified [HYPOTHESIS]** — I.3-D |
| **Atomic save (tmp+rename over original)** | DELETE+CREATE of target; mtime normally bumps | likely OK; **same-ms collapse risk [HYPOTHESIS]** |
| **Rapid-save burst** | Head burst-detector schedules a sync; worker stack has none | Head-only safety net **[VERIFIED]** — I.3-B |
| **OVERFLOW (events dropped)** | Head → `syncDirectory(force=true)`; worker → **logs only** | Head-only recovery **[VERIFIED]** — I.3-B |
| **inotify/handle exhaustion** | both stacks soft-fail; rely on periodic sync | depends on periodic sync teeth — I.3-C |
| **Change while app closed (downtime)** | startup `reindexPersistedRoots` walk | OK for adds; **delete-reconciliation scale-capped** — I.3-C |
| **Root unmount / disconnect** | Head delete-cascade GUARD (599); worker path has no guard | **data-loss risk via worker path [VERIFIED asymmetry]** — I.3-A |

## I.3 — Findings (drift risks), prioritized

### A. Worker DELETE path bypasses the tempdoc-599 unmount-cascade data-loss guard — HIGHEST VALUE
**[VERIFIED code asymmetry; HYPOTHESIS end-to-end data-loss — needs test]**
Tempdoc 599 §16/A1 added a guard: when a watched root goes unavailable (unmount/UNC disconnect), the OS
fires a cascade of child-DELETE events; deleting their docs would silently wipe the folder's index. The
**Head** path guards against this — `WatcherEventOps.handleDelete` checks `Files.exists(watchedRoot)` and
skips (`WatcherEventOps.java:107-114`). The **worker** path does NOT: `WorkerMethvinWatcher` DELETE →
`deletePathSink` → `IndexingCoordinator.deleteByIdAndChunks` (`WorkerMethvinWatcher.java:157-165`), and
`deleteByIdAndChunks` deletes unconditionally — no root-existence check (`IndexingCoordinator.java:385-392`).
Both watchers run on the same methvin library, so the same unmount cascade that motivated 599 reaches the
*unguarded* worker path. The worker delete path *also* skips the exclude-pattern check the Head path applies.
→ A known, already-fixed data-loss class is reopened by the parallel stack. Needs a regression test that
simulates a root-unavailable DELETE cascade against the worker delete sink.

### B. OVERFLOW + burst recovery are Head-only — a latent cliff if 418 is "finished"
**[VERIFIED]** The worker stack's OVERFLOW handler only logs ("periodic sync will catch up",
`WorkerMethvinWatcher.java:166`); it does not trigger a resync. Burst detection lives only Head-side
(`WatcherEventOps.handleOverflow:131-149` does `syncDirectory(force=true)`; `BurstDetector` +
`checkBurstAndScheduleSync`). Today this is fine *because the Head watcher still runs*. But the obvious
"complete the 418 migration / remove the duplicate watcher" cleanup would **silently delete overflow and
burst recovery** unless they are moved first. External confirmation that this matters: the methvin library
does **not** auto-rescan on OVERFLOW — recovery is entirely the caller's responsibility
(library source `DirectoryWatcher.java`; `ReadDirectoryChangesW` discards its ≤64 KB buffer and signals
`ERROR_NOTIFY_ENUM_DIR`). So overflow recovery is load-bearing, and it currently hangs off the stack that
the migration intends to delete.

### C. Reconciliation has weak teeth at scale — latency + a hard delete-blind-spot
**[VERIFIED]**
- Periodic sync is **one root per 60s**, round-robin (`SyncOps.java:145-159`). With N watched roots, any
  missed event for a given root is reconciled at best every **N×60 s** (10 roots → up to 10 min stale).
- Periodic sync runs `force=false`. `SyncDirectoryOps` **skips missing-file (delete) detection entirely
  when the indexed-path count exceeds `MAX_INDEXED_PATHS_FOR_MISSING_SCAN = 200_000`** and force=false
  (`SyncDirectoryOps.java:44-45,151-168`). So on a large corpus, a deletion that the live DELETE event
  missed (e.g. dropped during OVERFLOW) is **never reconciled by periodic sync** — orphaned hits persist
  until a user-forced full reindex. This is the "silently-wrong" terminal state the triad targets.

### D. Move/rename + ordering — likely OK but unproven
**[HYPOTHESIS — needs test]** methvin has no MOVE/RENAME event and no documented ordering guarantee
between the DELETE(old)/CREATE(new) it emits (library `DirectoryChangeEvent`; Oracle `WatchService`:
ordering "highly dependent on the underlying operating system"). Within-root and cross-root moves *should*
converge (old path pruned/deleted, new path indexed) because the two paths are keyed on distinct doc IDs,
but this is asserted, not tested — and it interacts with the dual-delete asymmetry (A).

### E. mtime-based change detection is systemic (watcher → sync → loop)
**[VERIFIED]** Three independent layers all key on mtime: the methvin watcher uses
`FileHasher.LAST_MODIFIED_TIME` (`MethvinWatcherStrategy.java:89`, `WorkerMethvinWatcher.java:101`); the
loop's unchanged fast-path is `documentFieldOps.isUnmodified(path, modifiedAtMs)`
(`JobBatchExtractor.java:195`); `FileFreshness.classify` keys on size→mtime→fileKey, no content hash
(`FileFreshness.java:19-38`). Library research confirms `LAST_MODIFIED_TIME` cannot see a content edit
that preserves mtime. → A content change with a preserved mtime is invisible **even to a forced
`syncDirectory(force=true)`** (which relies on the loop's mtime fast-path). Only a user-initiated *force
reindex* (`forcedPaths`, `JobBatchExtractor.java:192,209`) re-extracts it. This is a deliberate
cost/accuracy trade (content-hashing every file at scale is expensive — the canonical tension), so it is a
**candidate "documented limitation," not necessarily a bug** — but it must be a *named* matrix verdict,
not an unstated gap.

## I.4 — Critique of the stub's assumptions / reframing

1. **"Emergent, smeared across three components" undersells it.** The real defect is an *unfinished
   migration leaving two authorities* (I.1). A "convergence authority" can't just be *added* — one of the
   two existing event stacks has to be retired or demoted to a pure fast-path, and the Head-only safety
   nets (overflow/burst/unmount-guard) migrated, not dropped. That sequencing risk is the actual hard part.
2. **The canonical design already exists in-tree — it's just under-powered.** External best practice
   (IntelliJ VFS, Watchman recrawl, the universal "watcher is a fast-path, full walk is the truth"
   consensus) is *snapshot-diff reconciliation*. JustSearch *has* this (`SyncDirectoryOps`), so the goal is
   less "build a convergence authority" and more "make the existing reconciler authoritative and
   sound": kill the 200k delete-blind-spot, rethink the one-root-per-60s latency, and decide the mtime-vs-
   content-hash trade explicitly. This is a *strengthening*, not a greenfield.
3. **"Drift detection" (stub goal 3) is the genuinely missing primitive.** There is no mechanism that
   *detects* index-vs-disk divergence and surfaces it — reconciliation silently fixes (some of) it on a
   timer. A parity/where's-the-drift signal (à la Watchman's explicit "I may have missed changes" /
   `kFSEventStreamEventFlagUserDropped`) is the part with no current analog and the highest design value.
4. **Scope check against the triad.** The DELETE-asymmetry (A) borders 628 (durability) but is squarely a
   *change-correctness* defect (a live event path wiping data), so it stays in 626. The 200k delete-skip
   (C) is change-correctness (reconciliation completeness), not a crash-consistency issue — 626, not 628.

## I.5 — What a future test/design pass must prove (open questions, not yet answered)

- **Q1 (A):** Does an unmount-time DELETE cascade through the worker sink actually wipe the index? Build the
  regression test before any fix — this is the `audit-without-test` line.
- **Q2 (I.1):** Is the dual-watcher genuinely always-on, or is there an eval/headless/deferred mode where
  only one runs? (Checked: no config flag; but `ingestRunning == null` deferred mode makes the worker
  delete sink a no-op — `DefaultWorkerAppServices.java:226-229` — so during deferred mode *worker* deletes
  are dropped and only the Head path + later sync handle them. Verify this window.)
- **Q3 (C):** Confirm `reindexPersistedRoots` startup path's delete-reconciliation completeness under the
  200k cap — does an offline bulk-delete on a large corpus ever get pruned without a manual reindex?
- **Q4 (E):** Decide explicitly: is mtime-only the accepted contract (document it as a limitation), or is a
  cheap content-signal (size+mtime is current; add a content hash only on a reconcile walk?) warranted?

## I.6 — Dead ends / refuted

- *"Maybe the Head watcher is disabled in production and only the worker runs"* — **refuted**: no config
  path disables it; `WatcherBootstrap` is wired unconditionally and `createStrategy` throws on a "none"
  strategy (`WatcherBootstrap.java:144-156`, `KnowledgeServerBootstrap.java:206-207`). Both run.

## I.7 — External references (for the design pass)

- methvin `directory-watcher` event model (no MOVE event; OVERFLOW not auto-rescanned):
  https://github.com/gmethvin/directory-watcher (source `DirectoryWatcher.java`, `DirectoryChangeEvent.java`)
- `WatchService` ordering/timeliness OS-dependence; downtime gap: https://docs.oracle.com/javase/8/docs/api/java/nio/file/WatchService.html
- `ReadDirectoryChangesW` overflow (`ERROR_NOTIFY_ENUM_DIR`, ≤64 KB buffer → must re-enumerate): Microsoft semantics
- Canonical reconcile patterns: IntelliJ VFS (snapshot + full walk fallback)
  https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html ; Watchman recrawl / `since` / cookies
  https://facebook.github.io/watchman/docs/troubleshooting

---

# Long-term design (2026-06-21, agent — design round; no implementation)

> Method: read the existing reconcile/condition/health machinery on `main` (file:line below); read the
> closest siblings — **627** (process-supervision; names *Observation-Actuation Closure* + the Capability
> vocabulary) and **628** (durability; names the `AssertedCondition`/`WorkerSnapshotTap`/`CAUSE_ROWS`
> legibility seam and the *recover-to-empty→rebuild-from-source* boundary it shares with 626) — plus the
> shipped legibility lineage (600 degradation-cause, 595 observed-state, 599 folder-availability). Scope
> was judged against "new structure exactly when the present problem requires it." **The dominant finding
> mirrors 628's: 626 needs very little *new* machinery. The reconciler (snapshot-diff) and the
> legibility seam both already exist; the defect is that 626's domain (filesystem↔index correspondence)
> is wired as a *fork with two authorities* and its drift observation *dead-ends silently* instead of
> reaching the seam the rest of the system already uses.** Design kept general, not implementation-level.

## Design thesis

The investigation found three structural defects, not a pile of edge-case bugs:

1. **Two event authorities** (the stalled 418 migration — §I.1): a fork. Its concrete harms are the
   unguarded-worker-DELETE data-loss (§I.3-A) and the overflow/burst recovery that hangs off the stack a
   "cleanup" would delete (§I.3-B).
2. **The reconciler is not authoritative** — it is treated as a background catch-up, runs one-root-per-60s,
   and *silently skips* delete-detection above 200k paths (§I.3-C). The canonical design (IntelliJ VFS,
   Watchman; §I.7) is the inverse: the **full-walk reconcile is the source of truth, the watcher is a
   fast-path accelerator over it.** JustSearch has the reconciler; it just isn't the authority.
3. **Drift dead-ends silently** — the reconciler *computes* the index-vs-disk diff every run (orphans
   pruned, missing enqueued) and then throws the observation away. Nothing ever reaches the user; at scale
   the actuation silently doesn't fire at all. There is no "your index may be out of sync" signal.

The design has three axes; each **extends an existing seam** rather than adding a parallel one.

## What already exists (and is reusable)

- **The reconciler**: `SyncDirectoryOps.execute` (prune orphans → indexed-set → walk → enqueue-missing →
  commit, `worker-services/.../services/SyncDirectoryOps.java:78-136`) + `WorkerScanOps` (the walk). This
  **is** snapshot-diff reconciliation — the canonical pattern — already in-tree and tested.
- **The recovery operations the seam points at already exist**: `core.reindex` (force) and
  `core.rebuild-index` (blue/green re-enumerate watched roots, `RebuildIndexHandler`). 628 confirmed
  `core.rebuild-index` is already the canonical recovery target for `index.unavailable`/`index.not_healthy`.
- **The legibility seam (shipped, 600/595)**: `AssertedCondition` (tri-state `status ∈ {TRUE,FALSE,UNKNOWN}`,
  PascalCase `reason`, `severity`, `Optional<OperationInvocation> recovery`); `WorkerSnapshotTap`'s
  `ConditionMapping` table already emits worker→Head conditions with a recovery operation — e.g.
  `schema.reindex-required → core.reindex {force:true}` (`WorkerSnapshotTap.java:133-141`) — under an
  explicit **tri-state "Treat unknown ≠ healthy"** discipline (`:47-65`); `CAUSE_ROWS`/`readinessNotice.ts`
  + the `check-readiness-reason-codes` gate hold reason↔wording↔remedy correspondence.
- **The unmount-availability primitive (599)**: `RootLifecycleOps`'s `rootAvailable` cache + the
  `ROOT_NOT_DIRECTORY` walk-error already model "this root is unavailable" — the per-root state a drift/
  freshness signal extends.
- **The forced-vs-fast-path knobs**: the loop's `forcedPaths` bypass + `isUnmodified` mtime fast-path
  (`JobBatchExtractor.java:192-211`) already separate "trust the fast-path" from "force re-extract."

## Axis A — Reclaim a single convergence authority (finish 418 *correctly*)

Resolve the fork: **one event source.** The worker-side watcher is the right survivor (in-process with the
queue and index, no IPC hop — 418's own intent), and the reconciler `SyncDirectoryOps` is the declared
**source of truth**; the watcher is demoted to an explicitly-labelled *fast-path optimization that is never
trusted for completeness*. This conforms to the universal best-practice (§I.7) and to 627's pattern of
*unifying existing code under its single reason-to-change* rather than building a framework.

The migration is **gated on first relocating the three Head-only behaviors** so completing it cannot
silently drop them (the cliff in §I.3-B):

- the **unmount-cascade DELETE guard** (599) moves onto the worker delete path (this *is* the §I.3-A
  data-loss fix — do it first, with the regression test the audit demands);
- **OVERFLOW** triggers the reconciler *in-process* on the worker (today it only logs) — strictly simpler
  than the Head path's cross-process `syncDirectory` RPC;
- **burst-detection** folds into the same "OVERFLOW-like → reconcile this root" trigger.

After relocation, the Head watcher stack is deletable as a unit. The fork has one shared reason-to-change
(watch the same roots for the same purpose), so unifying is the correct DRY, not over-DRY.

## Axis B — Make the reconciler authoritative (it must actually converge, and never *silently* not)

- **Kill the silent delete blind-spot.** The 200k-path `force=false` skip of missing-file detection
  (`SyncDirectoryOps.java:44-45,151-168`) must not be a *silent* "✓". Either the reconciler scales its
  delete-detection (e.g. streamed/bounded set-diff) **or**, where it must bound cost, it emits a
  **`drift-unknown` condition** for that root (Axis C) — applying 595's "unknown ≠ healthy" to
  reconciliation completeness. The unacceptable state is the current one: skip + look healthy.
- **Name the reconcile cadence as a contract, not a magic constant.** One-root-per-60s (`SyncOps.java:29`)
  sets worst-case staleness = N_roots × 60s; make the interval/striping a declared value the design and a
  test can reason about. (Tuning, not new structure.)
- **Decide the mtime-only contract explicitly (§I.3-E).** mtime-stable content edits are invisible at every
  layer. The cheap-and-correct default is to *declare mtime+size as the freshness contract and document the
  limitation*; a content-signal belongs only on the reconcile walk if the problem ever demands it —
  **noted, not built** (it is structure for a case the binding problem does not include).

## Axis C — Drift becomes first-class on the existing legibility seam (the genuinely missing primitive)

The reconciler already computes the diff; the design **routes that observation to the seam the system
already uses for "your index needs attention,"** instead of discarding it. Concretely (shape, not impl):

- The reconcile result yields a **per-root freshness/drift state** — a tri-state
  `{ in-sync | drift-detected(orphans=N, missing=M) | drift-unknown(reconcile-skipped / root-unavailable) }`.
- This maps to a **new `ConditionMapping` row** in `WorkerSnapshotTap` (e.g. `index.drift-detected` /
  `index.drift-unknown`), **subject = the watched root**, recovery = the *already-existing* `core.reindex` /
  `core.rebuild-index` operation — exactly mirroring the `schema.reindex-required` row. No new channel, no
  new vocabulary: a row in the existing table + a `CAUSE_ROWS` entry behind the existing
  `check-readiness-reason-codes` gate.
- `drift-unknown` **must not render as "✓ indexed"** — this is the 599 stale-"✓" bug-class generalized to
  reconciliation completeness, and it is precisely the tri-state the seam was built to carry.

This closes the loop: the reconciler's drift observation now terminates **either at an actuator (it
reconciles) or at a declared, user-surfaced condition with a recovery affordance** — never at a silently-
discarded diff. That is 627's **Observation-Actuation Closure**, applied to the filesystem↔index domain.

## What this design deliberately does NOT build (scope match)

- **No content-hashing change-detector** — the mtime-stable-edit case is a documented-limitation candidate,
  not the binding problem (§I.3-E). Building it now is structure for a case the problem does not include.
- **No new condition channel / no new health framework** — drift is a *row* in the existing
  `WorkerSnapshotTap` table; it conforms, it does not fork.
- **No generalized "external-source reconciler" abstraction** — see Reach; the principle is named, the
  generalized structure is explicitly deferred (only one of its instances — this one — is being built).
- **No drift *auto-rebuild* policy decision** — whether `index.drift-detected` auto-actuates vs. only
  offers the recovery button is a product call shared with 628-G2; the design surfaces it, it does not
  unilaterally settle the prod default.

## Cross-tempdoc boundaries (declared, must hold)

- **626 ↔ 628 (the named seam, 628-G3):** 628's corruption-recovery recovers an index to *empty* and
  depends on 626 to *repopulate from source*. 626's reconciler **is** that repopulation authority. The
  handoff: 628 owns "detect corruption → recover-to-empty + raise the condition"; 626 owns "the reconciler
  re-enumerates the roots and converges the empty index, and a recover-to-empty must *chain* a reconcile
  rather than wait passively for a watcher event." Settle the chaining edge with 628 before either ships.
- **626 ↔ 627:** 627 explicitly defers the live watcher DELETE path to 626 (the §I.3-A fix). No structural
  overlap; 627's restart actuator must do graceful-stop-first so the reconciler isn't fighting a half-killed
  worker (627↔628 boundary already states this).
- **626 ↔ 599:** 599 shipped the per-root availability cache + the unmount DELETE guard (Head path). 626
  *extends* that primitive to the worker path and to a richer tri-state freshness condition — same authority,
  not a parallel one.

## Standing proof (matched to the design, ADR-0026-safe)

Because the design isolates a **pure reconcile-diff function** (indexed-set × disk-walk → {orphans,
missing, unknown}) and the relocated **delete-guard predicate**, each transition-matrix row (§I.2) and the
unmount-cascade data-loss case (§I.5-Q1) becomes a millisecond in-process unit test — a real standing guard
in default `check`, no JVM spawn. Live-stack smokes (real unmount, real OVERFLOW via bulk unzip) stay the
opt-in higher-fidelity tier per `slice-execution.md`.

## Change-feed frontier (internet research pass, 2026-06-21) — the fast-path is a deferred upgrade ladder behind the authority boundary

A targeted research pass (one agent; questions scoped to the *only* fast-moving part of this design — the
**change-feed source**, not the settled reconcile/closed-loop/condition-seam pieces) asked whether a better
change feed than native watchers exists and whether it changes the design. **It does not change the
architecture — it validates it — and it surfaces one concrete, deferrable upgrade.**

**The field-wide answer confirms Axis A.** Every production indexer studied runs *feed-as-fast-path +
full-walk-as-truth*, and **no feed removes the reconcile backstop**: Everything (NTFS MFT walk = truth, USN
journal = feed, re-reads the MFT when the journal can't be trusted); Watchman (recrawl re-syncs truth on
overflow); IntelliJ VFS (full walk when the watcher can't be trusted); recoll (batch walk is the baseline).
This is independent confirmation that "watcher = swappable fast-path, reconcile = source of truth" is the
correct authority boundary — and that the boundary is what makes the feed *upgradeable without redesign.*

**The upgrade ladder (all slot behind the same reconcile-as-truth authority — named, NOT built here):**

| Fast-path | Solves | Cost / blocker | When |
|---|---|---|---|
| **Current: methvin / JDK `WatchService`** (plain `ReadDirectoryChangesW`, mtime-hash) | baseline | weak renames (§I.3-D), no downtime replay, overflow-prone (§I.3-B) | now |
| **`ReadDirectoryChangesExW` + FileId** (Win10 1709+) | first-class **move/rename** correlation via FileId match — directly closes §I.3-D | NTFS-only; **no maintained Java lib** (needs JNI/JNA); still **no downtime replay** | future, no elevation needed |
| **NTFS USN Change Journal** (the Everything technique) | first-class renames **+ structurally closes the downtime gap** (persist `{NextUsn, UsnJournalID}`, replay on restart, ~1-week retention) | **requires admin/elevation** (privileged service); NTFS-only, per-volume; wrap / journal-ID-change still force a full rescan | future, gated on a packaging/elevation decision |
| **Linux `fanotify`** (5.13+ unprivileged FID mode; `FAN_RENAME` 5.17+) | inotify-exhaustion + renames for the deferred cross-platform axis | whole-FS marks still need root; kernel-version variance | the deferred cross-platform tempdoc (T-G), not 626 |

**Design consequences (kept general, not built):**
- The design **does not adopt USN/ExW now** — that is a native-code + *elevation* decision (USN needs admin;
  the product ships `currentUser`/non-elevated per product-readiness), i.e. structure the present problem
  does not require. Recording it as a deferred slot is the point.
- What 626 *does* take from this: it makes the §I.3-D move/rename gap and the downtime gap **explicitly the
  reconcile's responsibility for now** (the current feed cannot close them), and it **labels the fast-path as
  a declared, swappable port** so a later USN/ExW upgrade is a feed swap, not a re-architecture. The authority
  boundary Axis A draws is exactly what earns that future option — a concrete point in the design's favor.
- USN's residual-after-upgrade cases (journal wrap, journal-ID change, non-NTFS/network volumes, non-elevated
  fallback) are *the same set the reconcile already must cover* — so even the best feed leaves the reconcile's
  mandate intact, reinforcing that the reconcile (Axis B) and its drift legibility (Axis C) are the load-
  bearing investments, not the feed.

**References (research pass):** USN journal records + rename-pair + full-reindex-on-loss
https://learn.microsoft.com/en-us/windows/win32/fileio/change-journal-records ; Everything uses USN + MFT,
admin-required, ~1-week retention https://www.voidtools.com/faq/ ; `ReadDirectoryChangesExW` /
`FILE_NOTIFY_EXTENDED_INFORMATION` FileId rename-correlation
https://devblogs.microsoft.com/oldnewthing/20260508-00/?p=112310 ; `fanotify_init(2)` unprivileged-FID +
`FAN_RENAME` https://man7.org/linux/man-pages/man2/fanotify_init.2.html ; Watchman recrawl-as-truth
https://facebook.github.io/watchman/docs/troubleshooting.html .

## Reach — the principle, its scope, and where it is already violated

> Recorded per the assignment's "recognize the principle, do not build the generalized structure." Only
> the 626 instance is being built; the generalization below is named, not constructed.

**The principle (already named by the siblings — 626 conforms, it does not coin a competitor):**
627 named **Observation-Actuation Closure** — *every signal that can indicate a recoverable divergence
between intended and actual state must terminate at an actuator that converges it, or at a declared +
surfaced terminal/condition; never solely at a status flag or an un-run check.* 628 named its legibility
half — *data-integrity divergences are first-class citizens of the `AssertedCondition`/`CAUSE_ROWS` seam,
tri-state, with a recovery operation.* These are two halves of one closed-loop-control shape (627 anchored
it to control theory / the Kubernetes reconciliation loop; the bug-class is an **open loop** — sense
without feedback-to-actuator).

**What 626 adds to the principle's *scope*:** the three T1 tempdocs are three instances of the *same* shape
over three different "actual states":
- **627** — actual state = a **process's liveness** (diverges from "should be serving").
- **628** — actual state = a **stored artifact's integrity** (diverges from "valid index").
- **626** — actual state = **correspondence to an external source of truth JustSearch does not control**
  (the filesystem the index mirrors).

The 626 instance shows the closure is **not limited to our own processes and data** — it applies wherever
the system's state must track an **external world it doesn't own**. That is the broader scope worth naming:
*external-correspondence drift is the same closed-loop obligation as internal-fault recovery.*

**Other candidate instances (named, not built — verify before acting):**
- **Excludes-change drift** — when a user tightens exclude patterns, already-indexed docs matching the new
  excludes are (apparently) not retroactively pruned: a config↔index divergence with no signal. **Likely an
  existing violation** — worth a confirming check.
- **Cloud-placeholder hydration** (OneDrive `RECALL_ON_DATA_ACCESS`) — placeholder vs hydrated state is a
  filesystem-correspondence the ledger already tracks; check whether de-hydration drift is surfaced.
- **Embedding-model / schema fingerprint vs index** — this is a **confirming instance already done right**
  (`schema.reindex-required → core.reindex`): the model the index was built against is an external-ish
  source of truth, and its drift already reaches the seam with a recovery op. It is the template 626 copies.
- **Search result-count truthfulness (597)** — claimed-vs-actual cardinality is the same "surfaced
  divergence" family on the read side.

**Existing violations already on record** (the principle is real, not speculative — ≥3 documented instances):
the 200k silent delete-skip (626 §I.3-C), the open-loop gRPC-health→DEGRADED-with-no-actuator (627), and
Lucene corruption recovered silently-without-a-condition (628 axis-1). Three independent silent dead-ends,
same shape — which is exactly why naming the principle (without yet building a generalized reconciler
framework) is the right call: the recurrence is established; the abstraction is not yet required by any
single problem.

---

# Future directions (research pass, 2026-06-21, agent — ideas, NOT implementation)

> **Superseded/crystallized by the "Long-term design — Recency" round below (2026-06-21, round 2).** This
> ranked menu is the *research input*; the design round downstream settles which of these the present
> problem actually requires (it unifies menu #1/#2/#3 under one recency dimension, keeps #4 as tactical
> polish, and confirms the rejections). Read the design round for the conclusion.

> Method: with 626 implemented (single watcher authority; data-loss fix; non-silent reconcile; drift legible
> on three surfaces — the per-folder `folderStatus` glyph, the `index.drift-unknown` Condition, the
> `index.drift-corrected` Occurrence), a deliberate "what could we build on this?" pass. Three parallel
> internet-research streams (consumer sync/index-freshness UX; infra/devops *drift* vocabulary; a 2026 refresh
> of the change-feed upgrade ladder) were cross-checked against a verified internal-seam probe (what the wire,
> the FE states, the `OccurrenceLog`, and the operation catalog actually expose today). **Nothing here is
> built or scheduled** — this is a ranked menu of polish / simplify / extend / new-UX options with primary
> sources, plus an honest list of ideas the research says to *reject* for a consumer product. Per
> `tempdocs-are-dated-history`, treat as dated ideation, not a commitment.

## The convergent finding (the one idea three streams independently point at)

The single cheapest, highest-value extension is a **per-root "last verified" timestamp**, because three
research streams converge on it and the internal probe shows it is the one thing missing:

- **Internal seam (verified):** the wire exposes `IndexedRootView.lastIndexedIsoTime` (last *indexing* pass)
  but **no** `lastVerified` / `lastReconciled` time — only the `deleteDetectionUnverified` boolean. So today
  a folder that was verified 10 seconds ago and one that was last verified 3 days ago look identical (both
  "✓ indexed").
- **Consumer UX (Syncthing):** the strongest freshness pattern across Dropbox/OneDrive/Syncthing is a concrete
  **"Last scan" / "Last seen"** time — "your data may be stale" is vague; "Last verified 3 days ago" is a fact
  the user can judge ([Syncthing GUI](https://docs.syncthing.net/intro/gui.html)).
- **Infra (Debezium / replication lag):** a calm "in sync" reading is *only trustworthy if a liveness pulse
  proves the checker actually ran* — Debezium emits **heartbeat events** so a zero-lag number means "verified
  current," not "pipeline dead" ([Debezium CDC lag](https://techcommunity.microsoft.com/blog/adforpostgresql/performance-tuning-for-cdc-managing-replication-lag-in-azure-database-for-postgr/4473232)).

A `lastVerifiedAtMs` per root **is that heartbeat.** It turns the existing green ✓ from "we think it's fine"
into "verified 2 min ago" (trustworthy), and makes a *cap-skipped* verification visibly stale ("last verified
3 days ago") instead of invisibly stale — which is the exact 626 thesis ("couldn't verify never reads as
healthy") carried one rung further: *didn't-recently-verify* shouldn't read as freshly-healthy either. It
rides the same Phase-4 lane (`SyncOps` → `WatchedRootsState` → `WatchedRoot` → `IndexedRootView`) the
`deleteDetectionUnverified` flag already uses; it is an additive wire field + a relative-time subtext on the
folder row, no new channel.

## Ranked menu

| # | Bucket | Idea | Why / source convergence | Cost |
|---|---|---|---|---|
| 1 | **Extend** | Per-root **`lastVerifiedAtMs`** + "Verified Xm ago" folder subtext | The convergent finding above — the heartbeat that makes ✓ honest | low (additive wire field, existing lane) |
| 2 | **Extend** | **Targeted "Verify this folder now"** scoped recovery op (`core.reconcile-root {path}`) | Today the only recovery is corpus-wide `core.reindex {force:true}` — a sledgehammer on a large corpus. The worker's `GrpcIngestService.reconcileRoot(root, force)` **already targets one root**; it just isn't wrapped as a user-facing Operation. A scoped re-verify is *not* an infra-style "remediate?" prompt (those are correctly rejected, below) — it's one-click scoped convergence | low–med (wrap an existing RPC as a `core.*` op + recovery affordance) |
| 3 | **Simplify** | Unify the two per-root drift carriers (`deleteDetectionUnverified` Set + `driftCorrected` Map in `WatchedRootsState`) into one per-root **drift record** `{verifiedAtMs, unverified, orphanPrune}` | Both already mean "the latest reconcile's drift outcome for this root" — one shared reason-to-change, so this is correct DRY (AHA), not over-DRY. It also naturally *houses* idea #1's timestamp | low (internal refactor; guarded by existing `SyncOpsReconcileVerificationTest`) |
| 4 | **Polish** | Prune `IndexDriftHealthTap.priorDriftAtMs` against the live root set in `reconcile()` | The dedup map (`IndexDriftHealthTap.java:80`) is cleared for *removed* roots nowhere — `activeSubjects` is reconciled but `priorDriftAtMs` is not, so a removed-then-re-added root keeps a stale at-ms. Bounded (one entry/root) but a latent staleness bug | trivial (mirror the `activeSubjects` clear loop) |
| 5 | **New UX** | **"Out of sync items" quantified gap + optional drill-down** | Syncthing's "Out of Sync" count with a click-through to the actual item list is the highest-trust pattern — turns "may be stale" into an inspectable list and gives Reindex a visible target ([Syncthing syncing](https://docs.syncthing.net/users/syncing.html)). The reconcile already *computes* orphans + missing; surfacing the counts (the `index.drift-corrected` Occurrence already carries `orphanCount`) is the additive step | med (expose reconcile diff counts on the wire + a drill renderer) |
| 6 | **Polish** | Decompose `StatusLifecycleHandler` tap-wiring (declared in the class-size changeset) | The tempdoc-519 head-composition graph is the named home; the +102 LOC is consistent tap structure, not new responsibility | med (cross-cutting; belongs to 519, not 626) |

## Patterns the research says to REJECT for a consumer product (recorded so they aren't re-walked)

The infra-drift stream was explicit that several mature *operator* patterns would **over-complicate** a
consumer desktop surface — recording the rejections with their reason is the point:

- **Drift-history timelines / "auto-corrected N times this week" charts.** Built for operators managing fleets
  (Terraform Explorer's per-workspace drift counts); a single user with one index does not need a drift-over-
  time chart ([HCP Terraform health](https://developer.hashicorp.com/terraform/cloud-docs/workspaces/health)).
  Note: the `OccurrenceLog` is a 200-cap FIFO ring with **no** aggregation (verified) — so this would also be
  net-new machinery, not a cheap read. Reject.
- **Manual "remediate? apply / ignore" prompts.** Infra tools ask because changing prod is risky; a search
  index should just **auto-reconcile silently** — surfacing a remediation choice would be a *regression* of
  the whole "convergence is automatic" promise. (Idea #2 is distinct: a *scoped re-verify button*, not an
  apply/ignore decision.) Reject the prompt; keep the button.
- **The full 6-value health enum** (`Healthy/Progressing/Degraded/Suspended/Missing/Unknown`, ArgoCD). Collapse
  to ~3 user-facing states (Up to date / Updating / Needs attention) — which is essentially what `folderStatus`
  already does ([ArgoCD health](https://argo-cd.readthedocs.io/en/stable/operator-manual/health/)). Reject the
  full enum; the existing 7 internal states already roll up to the right ~3 glyphs.
- **Content-hash change-detector** for mtime-stable edits — already deferred in §I.3-E as a documented
  limitation, not the binding problem. The research adds nothing that changes that. Stays deferred.

One genuinely translatable infra idea worth *noting* but not over-pushing: ArgoCD's **two orthogonal axes** —
"in sync with desired?" *vs* "actually healthy/searchable?" are independent (a stale index can still be fully
searchable; a healthy index can be stale). `folderStatus` slightly conflates them today. For a consumer
product this is likely more precision than users want, but it is the clean conceptual model if the freshness
surface ever grows ([Sync vs Health](https://oneuptime.com/blog/post/2026-02-26-argocd-sync-status-vs-health-status/view)).

## Change-feed upgrade-ladder refresh (2026) — the deferral holds, but its *economics* shifted

A targeted refresh of the §"Change-feed frontier" ladder (USN journal / `ReadDirectoryChangesExW`+FileId /
`fanotify`) asked whether 2026 changed any deferral verdict. **Every deferral still holds** — but one input
moved, and two facts were corrected:

- **The one real change — Project Panama / FFM is stable (JDK 22, JEP 454, March 2024).** All three native
  bindings (USN ioctls, `ReadDirectoryChangesExW`/`FILE_NOTIFY_EXTENDED_INFORMATION`, `fanotify` syscalls) are
  plain `DeviceIoControl`/`kernel32`/syscall-over-struct work — FFM's sweet spot, with **no C toolchain / no
  `.dll` build step**. So the per-rung cost drops from *"JNI + native build"* to *"a few hundred lines of
  pure-Java FFM."* This **strengthens the design's central bet** — Axis A drew the watcher↔reconcile authority
  boundary precisely so the fast-path is a *swappable port*; FFM makes cashing in that option cheaper.
  Precedent it's production-viable: the active [oshi-ffm](https://github.com/oshi/oshi-ffm) rewrite.
- **Still no maintained drop-in JVM library** for USN, `ReadDirectoryChangesExW`/FileId, `fanotify`, or
  FSEvents-with-replay — every rung is still *build-it-yourself*, just now in FFM rather than JNI. USN still
  requires **Administrators-group membership** + a volume handle (MS docs reaffirmed 2025-03 —
  [Change Journal](https://learn.microsoft.com/en-us/windows/win32/fileio/creating-modifying-and-deleting-a-change-journal));
  unprivileged `fanotify` (`FAN_REPORT_FID` 5.1, `FAN_RENAME`/`FAN_REPORT_TARGET_FID` 5.17) still can't do
  whole-tree mount marking without `CAP_SYS_ADMIN` ([fanotify(7)](https://man7.org/linux/man-pages/man7/fanotify.7.html)).
- **Two corrections to the prior ladder text:** (1) `io.methvin:directory-watcher` **already uses FSEvents on
  macOS** (via JNA, `MacOSXListeningWatchService`) — not WatchService polling — so there *is* a live mac feed
  today; it simply runs real-time-only and **does not persist `sinceWhen` event IDs**, so it gives no downtime
  replay ([directory-watcher](https://github.com/gmethvin/directory-watcher)). (2) If downtime-replay ever
  becomes the binding requirement, the two reference designs are **Watchman's `clockspec`/named-cursor**
  (native cross-platform "what changed since cursor C," but a heavyweight out-of-process daemon —
  [clockspec](https://facebook.github.io/watchman/docs/clockspec.html)) and **Rust [`notify`](https://github.com/notify-rs/notify)**
  (healthy, the backend for rust-analyzer/Zed/Deno; best native-interop target, but no JVM binding and no
  replay layer of its own).

Net: the ladder stays deferred (no library; USN elevation; fanotify capability) — but FFM means the *day we
choose to climb it*, the cost is materially lower than the original design assumed, and idea-set #1–#5 above
(freshness legibility on the existing feed) remains the load-bearing investment regardless of which feed sits
underneath.

## Sources (this pass)

- Consumer sync/index UX: [Dropbox sync icons](https://help.dropbox.com/sync/sync-icons-windows) ·
  [OneDrive icons](https://support.microsoft.com/en-us/office/what-do-the-onedrive-icons-mean-11143026-8000-44f8-aaa9-67c985aa49b3) ·
  [Syncthing GUI](https://docs.syncthing.net/intro/gui.html) / [syncing](https://docs.syncthing.net/users/syncing.html) ·
  [Everything indexes](https://www.voidtools.com/support/everything/indexes/) ·
  [Windows Search indexing](https://support.microsoft.com/en-us/windows/search-indexing-in-windows-da061c83-af6b-095c-0f7a-4dfecda4d15a) ·
  [Recoll monitor](https://www.recoll.org/usermanual/webhelp/docs/RCL.INDEXING.MONITOR.html)
- Infra drift vocabulary: [HCP Terraform health/drift](https://developer.hashicorp.com/terraform/cloud-docs/workspaces/health) ·
  [Pulumi drift](https://www.pulumi.com/docs/iac/operations/stack-management/drift/) ·
  [ArgoCD health](https://argo-cd.readthedocs.io/en/stable/operator-manual/health/) ·
  [Flux Kustomization](https://fluxcd.io/flux/components/kustomize/kustomizations/) ·
  [ES near-real-time](https://www.elastic.co/docs/manage-data/data-store/near-real-time-search) ·
  [Debezium CDC lag](https://techcommunity.microsoft.com/blog/adforpostgresql/performance-tuning-for-cdc-managing-replication-lag-in-azure-database-for-postgr/4473232)
- Change-feed 2026: [oshi-ffm](https://github.com/oshi/oshi-ffm) ·
  [MS Change Journal](https://learn.microsoft.com/en-us/windows/win32/fileio/creating-modifying-and-deleting-a-change-journal) ·
  [fanotify(7)](https://man7.org/linux/man-pages/man7/fanotify.7.html) ·
  [directory-watcher](https://github.com/gmethvin/directory-watcher) ·
  [Watchman clockspec](https://facebook.github.io/watchman/docs/clockspec.html) ·
  [Rust notify](https://github.com/notify-rs/notify)

---

# Long-term design — Recency as a first-class dimension of correspondence legibility (2026-06-21, agent — design round 2; general, NOT implementation)

> Method: took the research menu above as input and asked the structural question — *what is the correct
> long-term shape for 626's remaining work, and what already exists that it should extend rather than
> replace?* Read the adjacent legibility tempdocs (595 Stability axis, 600 self-monitoring/`monitor.unobservable`,
> 627 Observation-Actuation Closure, 628 data-integrity tri-state, 625 measurement-provenance) and verified the
> live seams (`AssertedCondition`, the Phase-4 wire lane, `CoreOperationCatalog`, `OccurrenceLog`). Kept
> general. The conclusion is a *small* design — an extension of three existing seams — because that is exactly
> the scope the present problem requires; the bulk of the menu's larger ideas are deferred or rejected, below.

## The problem the remaining work actually has (stated precisely)

626's drift legibility is **edge-triggered and point-in-time.** It surfaces *transitions* — a root *became*
unverified (`index.drift-unknown` Condition), a reconcile *just* pruned orphans (`index.drift-corrected`
Occurrence) — and the per-folder glyph asserts a *current* verdict. What none of the three surfaces carries
is **recency of confirmation**: *when was this root last actually verified against disk?*

The seam itself proves the gap is structural, not cosmetic. `AssertedCondition` carries
`Instant lastTransitionTime` — *when the asserted state last **changed*** — but nothing for *when it was last
**confirmed still-true**.* So a condition that has been quietly TRUE (or quietly absent → "healthy") for three
days is indistinguishable from one re-verified ten seconds ago. On the folder surface the same hole: the wire
exposes `lastIndexed` (last *write*) but no `lastVerified` (last *correspondence check*), so a folder verified
moments ago and one whose delete-detection was cap-skipped days ago both render the identical calm "✓ indexed."

This is the **626 thesis carried one rung deeper.** Phase 5/8 established "*couldn't-verify* never reads as
healthy." The unfinished half is its temporal twin: **"*didn't-recently-verify* must not read as
freshly-healthy" either.** A "quiet" correspondence signal is only trustworthy if it can prove it is *fresh* —
otherwise quiet-because-verified is silently conflated with quiet-because-the-checker-stalled (the §I.3-C
one-root-per-60s striping means worst-case staleness is already N_roots × 60s, entirely invisible today).

## What already exists (extend these — do not build parallel structure)

- **The Phase-4 wire lane** (`SyncOps` → `WatchedRootsState` → `IndexingService.WatchedRoot` →
  `IndexedRootView`) already carries the per-root drift outcome (`deleteDetectionUnverified`, and internally
  `driftOrphan*`). A recency timestamp is an *additive field on this exact lane* — no new channel.
- **595's Stability axis** (`settled | provisional·cause`, "renderers as total functions over (value,
  stability)") is the FE shape to conform to. **Crucially, 595's binary does not cover 626's case:**
  "provisional" = *real-now-but-in-flux* (mid-rebuild); a cap-skipped folder is **settled** (not in flux) yet
  **stale** (its confirming check is old). Recency/confidence is a *distinct* dimension from
  stability/settledness. 595 owns the *general* Stability substrate (still open); 626 supplies the *per-root
  freshness instance* on the existing lane — it must align with 595's axis, not fork a competitor.
- **The `AssertedCondition` tri-state** (628's `verified|unverified|corrupt`; 600's `UNKNOWN`-for-no-sample;
  595's "unknown ≠ healthy") is the legibility vocabulary `index.drift-unknown` already uses. The recency
  notion *extends* it (an as-of/confirmation time), it does not replace it.
- **The Operation/recovery seam** (`CoreOperationCatalog.REINDEX` / `REBUILD_INDEX`) and the **already-existing
  per-root worker RPC `GrpcIngestService.reconcileRoot(root, force)`** are the actuation half. The scoped
  recovery is a *new row in the existing catalog* wrapping an RPC that already exists, not a new mechanism.
- **`OccurrenceLog`** already houses the `index.drift-corrected` edge event (200-cap FIFO). It stays the home
  for *events*; recency is a *level/state* property and belongs on the per-root record, not the event log.

## The design (general shape, three conforming moves)

**A. Promote per-root drift from point-in-time flags to a recency-bearing verification record.** Distinguish
*last-indexed* (last write) from **`lastVerifiedAt`** (last time a reconcile actually confirmed
index↔disk correspondence for the root). Fold the two existing carriers (`deleteDetectionUnverified` Set +
`driftCorrected` Map) into **one per-root verification record** `{ lastVerifiedAt, unverified, lastOrphanPrune }`
— they already share one reason-to-change ("the latest reconcile's outcome for this root"), so unifying is the
correct DRY (AHA), and the record is the natural home for the new timestamp. This is the **heartbeat**: a calm ✓
now *proves* the reconcile ran ("Verified 2 min ago"), and a cap-skipped root is *visibly* stale ("Last verified
3 days ago") instead of invisibly stale.

**B. The three surfaces become derivations of the one record, not separate signals.** `folderStatus.ready`
gains a recency qualifier; `index.drift-unknown` is the derivation "verification is stale or was skipped";
`index.drift-corrected` is the edge event the record's `lastOrphanPrune` feeds. One source → three projections,
conforming to the single-derivation discipline the folder glyph already follows (`check-folder-status-derivation`
is the one authority). Recency also gives the *Condition* seam the as-of it structurally lacks (the
`lastTransitionTime`-is-not-confirmation-time gap above), at least for this one condition family.

**C. Granularity-match the actuation: a scoped "verify this folder now" Operation.** Today the only recovery
the drift condition can offer is the corpus-wide `core.reindex {force:true}` — a sledgehammer on a large
library, and a **granularity mismatch**: the *observation* is per-root but the only *actuator* is global. That
is a latent open-loop at the per-root level (627's closure, unmet at the right granularity). Wrap the existing
`reconcileRoot` RPC as a per-root `core.*` operation so the recovery affordance re-verifies *one* folder. (This
is a scoped *re-verify button*, categorically not the infra-style "approve this remediation?" prompt the
research correctly rejects — convergence stays automatic; the button just lets the user demand it *now* for
*this* folder.)

That is the whole design. It is deliberately small because the problem is precise: the legibility is
unverifiable-when-quiet (→ A, B) and the actuator is mis-scoped (→ C). **Explicitly NOT built** (structure for
cases the present problem does not include): 595's general Stability substrate (595's job, not 626's);
drift-history timelines / "drifted N times" charts (operator-grade, rejected above); a content-hash detector
(§I.3-E documented-limitation, deferred); the change-feed ladder upgrades (deferred, FFM-cheaper but
still no-library/elevation-gated); a two-axis sync-vs-health split (more precision than a consumer wants).

## Standing proof (matched, ADR-0026-safe)

Recency is a pure function of (record, now), so each rendered state — *fresh* / *stale* / *unverified* / *just-
corrected* — is an in-process unit test over the verification record (no JVM spawn), extending the existing
`SyncOpsReconcileVerificationTest` / `folderStatus.test` matrix with a clock-driven staleness axis. The scoped
operation gets a wrapper test mirroring the existing `core.reindex` recovery assertions.

## Reach

**This design is an instance of principles the system already names — it conforms, it does not coin a competitor.**

- **595's Stability axis** — 626's recency is the *same family* (an epistemic dimension on observed state that
  renderers must be total over) and must align with it. 626's contribution to 595's axis is a **distinction
  595's binary doesn't yet carry: `settled-but-stale` ≠ `provisional`.** Stability answers "is this value still
  moving?"; recency answers "when did we last confirm it?" — orthogonal. When 595's general substrate lands, the
  per-root freshness instance should fold into it; until then it rides the existing per-root lane. (Conform;
  flag the missing axis-point to 595.)
- **627's Observation-Actuation Closure** — 626 already conforms (drift observation → condition + recovery).
  Move C **sharpens** the principle: *closure must be granularity-matched.* A per-subject observation whose only
  actuator is global is still a latent open loop at the subject level. Worth stating as a refinement, not a new
  principle.
- **628 / 600 tri-state on `AssertedCondition`** — the recency state extends the same tri-state vocabulary
  (`unknown ≠ healthy`), it does not add a channel.

**The principle this reveals, named plainly (recorded, NOT generalized into structure now):**

> **Recency is part of correctness for any periodically-verified state.** A "healthy"/"in-sync" level produced
> by a periodic check is an open-loop assertion unless it carries its *as-of* time, so that **absence of a
> fresh check is not read as evidence of health.** "Quiet because just-verified" must be distinguishable from
> "quiet because the checker stalled." (The Debezium-heartbeat / replication-lag insight, generalized: a
> zero-divergence reading is only meaningful alongside a liveness pulse.)

This is the **temporal complement** of 595's rule: 595 says *don't render provisional as settled*; this says
*don't render stale as fresh*. Same goal (the FE must not assert more confidence than the data warrants), a
different axis (in-flux vs. last-confirmed).

**Candidate scope beyond 626 (named for the register; do NOT build the generalized structure):**
- **Every periodic health tap** that asserts "healthy" from a poll which could have stalled — `WorkerSnapshotTap`,
  the gRPC health probe, and the taps' own *"absence is healthy" stale-short-circuits* (e.g. `IndexDriftHealthTap`'s
  null/empty-roots-clears-all; `WorkerSnapshotTap`'s stale short-circuit). These are **candidate existing
  violations**: a stalled producer leaves its last condition asserted, or absent-hence-"healthy", with no as-of
  the user could use to catch it. Worth a confirming check; the `AssertedCondition.lastTransitionTime`-≠-
  confirmation-time hole means the seam *cannot* express it today.
- **600's `monitor.unobservable`** and **595's connection over-pessimism (R2)** are adjacent, same-family
  recognitions already partially handled — evidence the principle is real and recurring, not speculative.
- **625 (asserted-measurement provenance)** is the **cousin in the measurement domain**: both are instances of
  the broader meta-shape *"an assertion must carry its provenance."* 625 = *origin* provenance for asserted
  **numbers** (a benchmark number traces to a reproducible cohort-identified run); 626 = *recency* provenance for
  asserted **states** (a health level traces to a verification time). Same shape, orthogonal axes (where-from vs.
  as-of), different domains and consumers — so per AHA they are **named as kin, not fused** into one structure.

**Why record-but-not-build:** exactly as 625 and the §Reach section already conclude for their principles — the
recurrence is established (three adjacent recognitions: 595, 600, 628) but no single problem yet requires a
system-wide "every condition carries staleness" substrate. 626 builds only the **per-root freshness instance**
the present problem needs; the principle and its candidate scope are recorded so the insight isn't lost and the
day the fork bites again, the generalization is already named.

## Confidence-building pass (de-risk, 2026-06-21 — read-only; NO implementation)

> Seven uncertainties in the Recency design (A/B/C) were retired against source before any code. All read-only;
> each tagged **[VERIFIED `file:line`]** or **[DECISION]**. The pass *raised* confidence and surfaced **one
> concrete coupling** the design must honour.

- **U1 — the single verify-stamp site. [VERIFIED]** `SyncOps.syncDirectory` records per-root verification only
  inside `if (!force && response.getError().isEmpty())` (`SyncOps.java:144`); the **clean-verify branch is
  `else if (!response.getSkipped())`** (`:148-149`, the `recordUnverified(root,false)` call) — entered only when
  the full indexed-set scan actually ran (not cap-skipped, not user-skipped). **`lastVerifiedAt` stamps exactly
  here**, never on the cap-skip (`:146-147`) or user-activity-skip path. The design's core correctness
  assumption holds verbatim.
- **U2 — `force=true` is a real scoped re-verify. [VERIFIED]** In `SyncDirectoryOps.execute`, STEP-1
  `pruneOrphansForSync` removes missed-delete orphans for **both** force values and **bypasses the user-active
  throttle when force=true** (`SyncDirectoryOps.java:97,102,226`), under the 599 unmount-guard (`:218`).
  `filesDeleted` (which already feeds `index.drift-corrected`) is the prune count, not the scan. For force=true,
  `indexedPathsForSync` returns `null` (`:230`) so the expensive indexed-set diff is skipped — but it is **not
  needed**: STEP-3 re-walks and re-enqueues the whole folder (`:117`) and the response is non-unverified
  (`:164→134`). So a per-root `force=true` verify (Move C) honestly re-converges *and* clears the unverified
  state. `GrpcIngestService.reconcileRoot(root,force)` passes `force` straight through (`GrpcIngestService.java:1044-1049,1033`).
- **NEW coupling found (the value of this pass). [VERIFIED]** Because the recording block is `!force`-only
  (`SyncOps.java:144`), a `force=true` reconcile converges the index but **records nothing** into
  `WatchedRootsState`. ⇒ **Move A and Move C are coupled: the force=true verify path must refresh per-root state
  (clear `unverified`, stamp `lastVerifiedAt`)** — by extending that block to handle force=true, or refreshing
  in the op handler. Otherwise "Verify this folder" would fix the index yet leave the UI stale. Bounded, clear,
  and now an explicit implementation step rather than a latent surprise.
- **U3 — wire/schema blast radius is contained. [VERIFIED]** `IndexedRootView` (`app-api/.../indexing/IndexedRootView.java:56-73`)
  defaults `lastIndexedIsoTime` to `""` (`:71`) — `lastVerifiedAt` mirrors it (additive `String`, empty
  default). Adding it touches exactly: the record, the **two** `indexed-root.v1.json` copies (`SSOT/schemas/`
  + `modules/ui/src/main/resources/SSOT/schemas/`), and the generated FE type
  (`modules/ui-web/src/api/generated/schema-types/indexed-root-view.ts`, regen via
  `scripts/codegen/gen-wire-schema-types.mjs`). **Confirmed NOT under `StatusResponse`** (served by the separate
  `/api/indexing-roots/substrate` Resource, zero `StatusWireContractConformanceTest` impact). *Correction to a
  subagent claim:* `deleteDetectionUnverified` **is** already present in both schema copies (`:32`) — my Phase-4
  work is schema-clean (verified directly, not trusted).
- **U4 — no shared `AssertedCondition` change. [DECISION/VERIFIED]** `AssertedCondition` carries
  `lastTransitionTime` (edge time) only; recency rides **folder-side on `IndexedRootView`** (independent
  substrate endpoint), so the design needs **no field on the shared condition record** — blast radius stays off
  every other tap. (Giving the *condition* its own as-of remains the named, deferred reach item, not core work.)
- **U5 — the scoped op is fully mapped (not "just wrap an RPC"). [VERIFIED]** A new `core.reconcile-root {path}`
  needs ~6 backend artifacts, templated by `core.reindex`: an `OperationRef` + `Operation` definition + def-list
  entry in `CoreOperationCatalog`, a handler mirroring `ReindexHandler`, its registration in
  `OperationHandlerRegistrations`, and an i18n label. **The FE auto-discovers ops** from `/api/registry/operations`
  (no static allowlist), and **operations need no schema regen**. Re-pointing the drift recovery is a one-line
  change to the `RECOVERY` constant in `IndexDriftHealthTap`. Possible gate: `operation-surface` only if the
  handler imports `IndexingJobView` (it won't).
- **U6 — conforms to the single FE authority; doc cost known. [VERIFIED/DECISION]** `folderStatus.ts`
  (`modules/ui-web/src/shell-v0/state/`) is the one derivation authority (`check-folder-status-derivation`) and
  its `metaText` already carries a timestamp — recency is a **format extension of the existing derivation**, not
  a second freshness authority (conforms to 595's "total function over (value, …)" shape). It is **shell-v0
  governed** ⇒ the `maintain-doc-hint` will require an ADR-0032 / presentation-kernel note in the same change
  (anticipated, not a surprise).
- **U7 — stale is display-only; tests are deterministic. [DECISION/VERIFIED]** **No hard staleness threshold**:
  show relative "Verified Xm ago" (consumer-research preference) and let the existing `index.drift-unknown`
  Condition stay the sole binary "needs attention" trigger — avoids false-alarm churn. Determinism: stamp via
  `WatchedRootsState`'s **injected `Clock`** (`WatchedRootsState.java:43,49`) rather than the
  `System.currentTimeMillis()` the current `driftCorrected` callback uses, so staleness unit tests are
  clock-driven; the read-side tap already holds a `Clock`.

**Residual risks (named, all bounded):** (1) the Move A↔C recording coupling above — known, one explicit step;
(2) the *stale* folder state is not live-provocable (needs >200k indexed paths), so it stays fixture + unit-test
validated as in Phases 8/9 (the *fresh* path is live-testable); (3) the `folderStatus.metaText` change is
shell-v0 governed (doc obligation); (4) on a >200k folder, force=true converges by re-walk+prune rather than the
indexed-set diff — confirmed sufficient for recovery, but worth a live note at implementation.

**Critical confidence rating: 8/10** for the remaining A/B/C work. High and well-grounded — the design extends
three existing seams with verified-bounded blast radii, the one correctness-critical assumption (the stamp site)
is pinned to `file:line`, and `force=true` recovery semantics are confirmed sound. It is not 9–10 only because of
the one genuine integration step the pass surfaced (the force-path recording coupling) and the fixture-bound
validation of the stale state — both understood and bounded, neither an unknown.

---

# Phase 10 — §Recency implemented (2026-06-21, branch `worktree-626-incremental-indexing`) ✓ tested + BROWSER-VALIDATED

Implements the approved Recency design (Moves A/B/C + polish). All extends existing seams; no new framework.

**Move A — the `lastVerifiedAt` heartbeat on the wire.** `WatchedRootsState` gains a parallel
`Map<Path,Instant> lastVerifiedAt`, **stamped via the injected `Clock`** inside `setDeleteDetectionUnverified(root,false)`
(the verified-clear path) — the heartbeat that proves a calm "✓" is fresh. The de-risk-found coupling is resolved
centrally in `SyncOps.syncDirectory`: the recording block was `!force`-only, so a **force=true success now also
records "verified"** (a force=true reconcile re-prunes + re-walks = a full re-converge); drift-corrected emission
stays `!force`-only (overflow noise). Carried additively through the existing Phase-4 lane:
`WatchedRoot.lastVerifiedAt` (9th component, back-compat ctors) → `RootLifecycleOps.getWatchedRoots` →
`IndexedRootView.lastVerifiedIsoTime` (defaults `""`, mirrors `lastIndexedIsoTime`) → `IndexingController` map →
both `indexed-root.v1.json` schema copies → regenerated FE type. **Confirmed not under `StatusResponse`** (the
sole app-api red is the pre-existing 607 `visualEnrichmentNeededCount`; `lastVerified` is 0× in that drift report).

**Move B — folder row shows the heartbeat (user-visible).** `folderStatus.ts` gains `verifiedRelativeTime` on
its context and appends `· Verified <time>` to the `ready` metaText — display-only, reusing the existing host
`formatRelativeTime`/`formatRelativeIso` util (no new formatter), staying inside the single derivation authority
(`check-folder-status-derivation` green). `LibrarySurface.ts` passes the new ctx field (both render paths). The
`index.drift-unknown` Condition keeps the binary "needs attention" alarm; recency adds no new threshold.

**Move C — scoped `core.reconcile-root` recovery (granularity-matched).** New `OperationRef RECONCILE_ROOT` +
`reconcileRoot()` definition (`{pathHash}` arg, LOW risk, UI+AGENT) in `CoreOperationCatalog`; new
`ReconcileRootHandler` (mirrors `ReindexHandler`); registered in `OperationHandlerRegistrations`;
`IndexingService.reconcileRoot(pathHash,force)` implemented in `RemoteKnowledgeClient` by resolving pathHash →
real path Head-side (hash each watched root — ADR-0028, raw path never crosses the wire) then
`syncDirectory(path,true)`; i18n `ops.reconcile-root.*` ("Verify Folder"). `IndexDriftHealthTap` now builds a
**per-subject** recovery `core.reconcile-root {pathHash}` (replacing the static `core.reindex {force:true}`), so
the drift condition's recovery verifies *that one folder*. FE auto-discovers the op (no FE allowlist change).

**Polish.** `IndexDriftHealthTap.reconcile` now prunes `priorDriftAtMs` to the live root set
(`retainAll(livePathHashes)`) — the leak the de-risk pass flagged.

**Validation.** Unit: `WatchedRootsStateTest` (stamp-on-verify + cleanup), `SyncOpsReconcileVerificationTest`
(force=true now records verified; force=false 3-way intact; force=true-skipped no-op), `IndexDriftHealthTapTest`
(per-subject `core.reconcile-root` recovery + `priorDriftAtMs` prune), new `ReconcileRootHandlerTest` (resolve →
force reconcile, not-found/missing-arg/unavailable), `folderStatus.test` (recency suffix present/absent),
`CoreOperationCatalogTest` (new op id) — all green; full FE suite **3380/3380**; FE typecheck clean. Gates:
`folder-status-derivation`, `wire`, `operation-surface`, `class-size` all **pass**. **Browser (real Lit UI via
`ui-shot library --fixtures`):** the folder rows render the heartbeat — `… indexed 15m ago · Verified 3m ago`
(fresh) and `… indexed 3d ago · Verified 3d ago` (mildly stale) — green ✓, **0 axe / 0 real console errors**;
fixture reverted. Move C's recovery target is unit-asserted to `core.reconcile-root`+pathHash; the condition's
browser consumption was proven in Phase 8 (the recovery row sits below the HealthSurface fold — documented
harness limit), and the op is catalog-registered + emitter-tested. Live backend→wire tier deferred only by peer
stack contention (627 holds the shared stack); the stamp/clear path is unit-proven and the stale state isn't
live-provocable anyway (needs >200k paths). The sole base-red gates (607 wire, `ssotValidateExec`, etc.) are
pre-existing, not 626.
