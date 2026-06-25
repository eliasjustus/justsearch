---
title: "Index & job-queue durability / corruption recovery: the detect→recover→rebuild-from-source primitive already largely exists (verified) — the real work is to make data-integrity faults first-class citizens of two existing authorities (the ConditionStore→CAUSE_ROWS legibility seam and a single recovery-contract authority), add proactive integrity detection with a tri-state (verified|unverified|corrupt), and unify the two scattered recovery philosophies into one declared detect→classify→policy→terminal-state dispatcher that always rebuilds from the source files still on disk"
type: tempdocs
status: "IMPLEMENTED & MERGED (2026-06-21) — engine core detect→recover→rebuild-from-source live-proven (corruption recovered all 34 docs from source); G1 (dirty-open FULL detection), G2 (self-heal default), G3 (rebuild-from-source chain), G4 (id uniqueness), Stage C (corruption-cause UI wording), Stage F rows, the deferred-recovery bug fix, and Stage D-part2 (can't-serve corruption cause) all merged to main. REMAINING is non-core: three evidence-backed round-3 SKIPs (Axis-2 unification = benign untidiness; Axis-1 condition for the rebuilding case = redundant; tri-state = no consumer; mid-op injection = Lucene/SQLite-owned) + two documented follow-ups (the Stage-D-part2 FAIL_CLOSED live smoke; a restart-with-recovery operation for one-click recovery of a *dead* worker). See the As-built / de-risking sections below."
created: 2026-06-21
author: agent analysis (engine-reliability gap scan), filed by agent — STUB / handoff
category: engine / durability / crash-consistency / corruption-recovery / lucene / sqlite / product-shape
principle: "an abrupt failure (power loss, kill -9, disk-full) never leaves the user with a broken or silently-wrong search — the system self-heals or fails loud with a path back to correct; the source files are still on disk, so the index is reconstructible"
related:
  - 588-worker-engine-silent-failures                # adjacent: silent-failure robustness of the loop
  - 626-incremental-indexing-correctness             # sibling reliability tempdoc (this T1 triad)
  - 627-process-supervision-crash-recovery           # sibling reliability tempdoc (this T1 triad)
---

> NOTE: Noncanonical working tempdoc — **STUB / handoff.** Not yet investigated. Captures the
> charter and goals for an assigned agent to take over. Do not treat any claim here as verified —
> the first deliverable IS the verification. All file/symbol references below are starting points
> for the audit, not established facts.

# 628 — Index & job-queue durability / corruption recovery

## Thesis

For a public release, the binding question is "did a crash mid-index leave my search broken or
silently-wrong?" The Lucene write path, the blue/green generation migration, and the SQLite job queue
carry substantial machinery (parity guards, switch-buffer ops) but their crash-consistency posture has
no recent dedicated tempdoc. A *silently-wrong* terminal state — serving stale/partial results with no
signal — is the worst failure for a search product and the hardest to notice.

## Goal (more than validation)

The crash-consistency audit is the **entry point**. The goal is to eliminate the silent-wrong *class*
and give the product a real recovery story:

1. **Verify** crash-consistency at each write / migration / queue point — each gets a terminal-state
   verdict (self-healing / loudly-degraded / silently-wrong).
2. **Convert every silently-wrong path to self-heal-or-fail-loud** — corruption gets *detected*
   (parity / CheckIndex) and triggers a defined response (auto-rebuild, quarantine, loud degrade)
   instead of serving wrong results.
3. **Build the missing recovery primitive** if absent — a "detect corruption → rebuild from
   source-of-truth" path, since the source files are still on disk and the index is reconstructible.
4. **Durable guard** — fix the latent `IndexGenerationManager` same-second collision (obs #484) and any
   other defect found *as instances of the class*, with guards that hold the structural fix.

## Polish dimension (subordinate, gated)

Leave each subsystem touched more legible than found — **only what the reliability work already
modifies.** Bias toward diagnostic/error legibility (it serves the "fail-loud / detect corruption"
goal) and decomposition of the write-path / migration classes. Found-but-untouched polish →
`docs/observations.md` inbox. Exclude UI polish.

## Scope

- **In:** crash-consistency of the write path, migration atomicity, queue durability, corruption
  detection + recovery-or-rebuild trigger.
- **Out:** routine schema-migration design and ranking correctness.

## Starting points for the assigned agent (UNVERIFIED — audit these)

- `modules/adapters-lucene/.../runtime/` — `WritePathOps`, `CommitReason`, `IndexMetadataParityGuard`,
  `ParityDiagnostics`, `RuntimeSession`, `IndexRuntimeIOException`
- `modules/indexer-worker/.../queue/` — `SqliteJobQueue`, `SqliteQueueMigrationOps`,
  `SqliteQueueSwitchBufferOps`, `SqliteSchema`, `SqlitePathResolutionStore`
- `IndexGenerationManager` (blue/green generation; obs #484 same-second collision is a concrete instance)

## Discipline (binds this tempdoc)

Discovery before work; convert silent-wrong to fail-loud, never weaken a check to make a failure
disappear (`fix-root-causes-not-symptoms`). Every fix lands with a regression/failure-injection test
(`audit-driven-fixes-need-test`). Fix defects as instances of the class
(`structural-defects-no-repeat`).

## Definition of done

A failure-injection audit (each abrupt-failure point × terminal state) with verdicts + `file:line`
evidence; every silently-wrong path converted to self-heal-or-fail-loud; a corruption-detect→rebuild
primitive (or a documented reason one isn't needed); guards holding the fixes including obs #484.

## Next step (not done here)

Assign an agent; first deliverable is the crash-consistency / failure-injection discovery audit.

---

# Investigation findings — discovery audit (2026-06-21, agent take-over)

> Status: **discovery audit complete; design/implementation NOT started** (per take-over scope).
> Evidence below is from reading `main` source directly (file:line) plus three parallel sub-agent
> audits whose load-bearing claims I re-verified at the cited lines. Sub-agent claims I could not
> re-verify are marked *(unverified)*. This section is the tempdoc's first deliverable: the
> crash-consistency map + a critical reassessment of the charter.

## Headline: the central premise is substantially out of date

The stub's banner goal — *"build the **missing** detect-corruption→rebuild-from-source primitive"* —
was written as an honest unknown ("no recent dedicated tempdoc"). The audit resolves it **mostly
favorably**: detection and a recovery primitive **already exist and are unit-tested.** The system is
materially more robust than the stub feared. The real 628 work is therefore narrower and different in
shape: **proactive (not open-time-only) corruption detection**, **making the existing recovery run for
a shipped end-user (it is OFF by default in prod)**, and **joining recover-to-empty to a guaranteed
rebuild-from-source**. Details below.

### What already exists (verified)

- **Lucene corruption is detected at open** and classified: `classifyIOException` maps
  `CorruptIndexException` / segment `NoSuchFileException` → `Reason.CORRUPT_INDEX`, `LockObtainFailed`
  → `LOCKED`, "no space" → `DISK_FULL`
  (`adapters-lucene/.../runtime/RuntimeSession.java` classify path; `IndexRuntimeIOException`).
- **A recovery primitive exists**: `RuntimeSession.openComponentsWithRecovery`
  (`adapters-lucene/.../runtime/RuntimeSession.java:405-491`):
  - `CORRUPT_INDEX` + `index.auto_recovery=true` → back up the corrupt dir to `*.bak-<ts>`, open a
    **fresh empty** index (`:420-443`).
  - `SCHEMA_MISMATCH` + policy `REBUILD_BACKUP_FIRST` → back up, open **fresh empty** (`:446-471`).
  - Auto-recovery disabled / `FAIL_CLOSED` → **throw loud** with a manual-fix message (`:473-487`).
- **It is exercised by tests**: `RecoveryIntegrationTest` proves backup+empty on corruption, rebuild on
  schema mismatch, and fail-fast when auto-recovery is off
  (`adapters-lucene/.../runtime/RecoveryIntegrationTest.java:63-199`).
- **Blue/green migration is crash-consistent on the pointer**: the active-generation pointer is a
  `state.json` written tmp→`ATOMIC_MOVE`, with a `state.json.prev` fallback
  (`worker-core/.../index/IndexGenerationManager.java:~630-660`); half-built generations are
  **resumable** and protected from GC (`:~833-837`); promotion is gated on a green-metadata check
  (`build_state==COMPLETE` + schema/embedding fingerprints) so a partial green cannot be promoted
  (`worker-services/.../KnowledgeServerMigrationOps.java:~230-310`).
- **SQLite queue is durable**: `WAL` + `synchronous=NORMAL` + `busy_timeout=5000`
  (`indexer-worker/.../queue/SqliteJobQueue.java:164-170`); state transitions are transactional;
  `recoverStuckJobs()` re-queues orphaned `PROCESSING` rows on startup **and** via a periodic reaper
  (`KnowledgeServer.java:401-425`); `PRAGMA quick_check` runs on open and throws `SQLITE_CORRUPT`
  (`SqliteJobQueue.java:1742-1790`); `handleCorruptDatabase` quarantines the corrupt file, restores
  from `jobs.db.bak`, else creates fresh (`KnowledgeServer.java:1975-2029`); the `.bak` is genuinely
  created (VACUUM INTO) **before each schema migration** (`SqliteJobQueue.java:223,1681-1714`) — so the
  restore path is **not** a phantom.
- **A rebuild-from-source path exists**: operation `core.rebuild-index`
  (`app-services/.../registry/operations/handlers/RebuildIndexHandler.java`) starts a blue/green
  migration that re-enumerates watched roots; `POST /api/debug/reset-index` exists for eval mode.
- **Schema/embedding-fingerprint mismatch auto-rebuild**: `KnowledgeServer` (`:505-585`) auto-starts a
  blue/green migration on embedding-model or schema mismatch **when policy is `BLUE_GREEN_MIGRATE`**.

## Crash-consistency verdict map (per write/migration/queue point)

| Point | Code | Crash behavior | Verdict |
|---|---|---|---|
| Lucene commit | `CommitOps.commit` (`w.commit()` single-phase) | atomic+durable by Lucene; mid-commit crash → reopen to last good `segments_N` | **self-healing** |
| Lucene open after crash | `RuntimeSession.openComponents` | Lucene discards uncommitted segments | **self-healing** |
| Lucene **silent** corruption | no `CheckIndex` anywhere (grep: 0 hits) | open-time validation catches header/missing-segment; **bit-rot in postings/docvalues read at query time is undetected** | **silently-wrong (residual)** |
| Lucene corrupt + `auto_recovery=true` | `RuntimeSession.java:420-443` | backup + empty | **loudly-degraded → empty** |
| Lucene corrupt + **prod default** (`auto_recovery=false`) | `ResolvedConfigBuilder.java:1242` | worker **refuses to start**, log says "manually rename the index dir" | **loud but stuck (no end-user path back)** |
| Schema mismatch, prod default `FAIL_CLOSED` | `ResolvedConfigBuilder.java:885` | throw / read-only | **loud but stuck** |
| Parity: `similarity_fp`/`boosts_fp` mismatch | `IndexMetadataParityGuard` | marked read-only; *(unverified)* silent if `parity.allow_mismatch=true` | **loud-ish; config foot-gun** |
| Blue/green pointer swap | `IndexGenerationManager` tmp→`ATOMIC_MOVE` (+`.prev`) | crash at any step → valid old or new pointer; never torn | **self-healing** |
| Blue/green `state.json` durability | no `fsync` after move *(unverified-fsync)* | non-durable rename reverts to valid prior pointer (old gen still on disk) | **safe (low severity)** |
| Half-built green generation | resumable + GC-protected | resumed on restart | **resumable** |
| Old generation after switchover | GC best-effort, not auto-invoked | retained on disk | **disk leak (not silently-wrong)** |
| **Generation id collision (obs #484)** | `IndexGenerationManager.java:197-200` | second-precision `g-<yyyyMMdd-HHmmss>`, no suffix; same-second migration → `IOException "generation already exists"` | **loud throw; latent robustness gap** — confirmed live |
| SQLite commit / transition | WAL + transactions | mid-transition crash → prior committed state; `recoverStuckJobs` heals | **self-healing** |
| SQLite schema migration | txn DDL; `user_version` set post-commit | crash mid-migration → re-runs (idempotent) | **resumable** |
| SQLite corruption | `quick_check` on open → quarantine + restore `.bak` / fresh | detected loud, restored | **self-healing (to last backup)** |

## The genuine residual gaps (the real 628 work)

1. **G1 — corruption detection is open-time-only and format-shallow.** No `CheckIndex` exists (grep:
   zero hits across `modules/`). Lucene open validates segment headers and presence, but normal reads
   verify per-file CRC32 footers only via `CheckIndex` / `checksumEntireFile` — **not** on every query
   read. So silent bit-rot in postings/doc-values/stored-fields that survives open returns **wrong
   results with no signal**. This is the strongest residual silently-wrong candidate and the part the
   stub's "detect corruption" goal is genuinely pointing at. *(Lucene read-path checksum behavior is an
   inference from Lucene's design — worth a confirming experiment before committing scope.)*

2. **G2 — the recovery primitive is OFF by default in production.** `index.auto_recovery` defaults to
   **false** (`ResolvedConfigBuilder.java:1242`); `schema_mismatch.policy` defaults to **`FAIL_CLOSED`
   in prod** (`:885`). So for a shipped desktop user, corruption/mismatch → *worker won't start* + a log
   line telling them to rename a directory. That is loud (not silently-wrong) but it is **not a
   recovery story** — exactly the "give the product a real recovery story" goal. The work is "wire the
   existing primitive to a safe-by-default, user-surfaced recovery," not "build a missing primitive."
   The conservative default (never auto-destroy data) is defensible and should be *preserved as an
   option* — but the absence of any non-CLI path back to correct is the product gap.

3. **G3 — "rebuild" = empty + passive re-watch, not a guaranteed rebuild-from-source.**
   `RecoveryIntegrationTest:136` asserts the recovered index is **empty**; repopulation depends on the
   watch/freshness layer (tempdoc **626**'s domain) noticing changes. Nothing inside recovery
   deterministically re-enumerates the watched roots. `core.rebuild-index` *does* re-enumerate but is
   operator-triggered and **not auto-chained** after a corruption recovery. So a recover-to-empty can
   silently persist as a half/empty index if source files don't change. **This is the 626↔628 seam** —
   must be coordinated, not solved twice.

4. **G4 — obs #484 same-second generation-id collision** (`IndexGenerationManager.java:197-200`),
   confirmed live. Low severity (production migrations are seconds apart) but a real robustness gap for
   rapid/programmatic migrations and fast tests (598's `IndexGenerationManagerRestartTest` works around
   it with a 1.1s sleep). Structural fix: sub-second precision or a uniqueness suffix — fix as an
   instance of the id-uniqueness class.

5. **G5 (minor) — `state.json` pointer write has no `fsync`** *(unverified-fsync)*. Atomicity is
   covered by `ATOMIC_MOVE`; durability is not. Low severity because a lost rename reverts to a valid
   prior pointer. One-line hardening, not a focus.

6. **G6 (minor) — `parity.allow_mismatch` config foot-gun** *(unverified)* and the
   `similarity_fp`/`boosts_fp` read-only path. Note for legibility; not crash-consistency.

7. **G7 (test fidelity) — existing tests delete/corrupt files deterministically, not mid-operation.**
   `RecoveryIntegrationTest` corrupts *between* sessions; it does not inject a crash *during* a commit
   / migration switchover / queue transition. Per `audit-driven-fixes-need-test`, claims like "Lucene
   auto-recovers mid-commit" and "WAL recovers mid-txn" deserve **failure-injection** tests (kill
   before fsync, truncate a segment mid-write, crash between `state.json.prev` rotate and `ATOMIC_MOVE`)
   — that is the failure-injection matrix the DoD asks for, and it is largely **not yet built**.

## Critical reassessment / open questions for the user (no design committed)

- **Re-anchor the charter** from "build the missing primitive" to the three real gaps (G1 proactive
  detection, G2 safe-by-default+surfaced recovery, G3 join-to-rebuild-from-source). Confirm this
  re-scoping before any design.
- **Prod-default policy** is a product decision, not purely engineering: should a public release ship
  `FAIL_CLOSED`+`auto_recovery=false` (current — safe but stuck), or a surfaced "your index looks
  corrupt → [Rebuild from your files]" flow? G2 hinges on this.
- **G1 cost/benefit**: a full `CheckIndex` is O(index size) and slow; a cheap alternative is
  footer-checksum verification on open and/or a periodic background scan. Bit-rot probability on a
  desktop SSD is low — weigh against `structural-defects-no-repeat` (one documented silent class is
  enough to justify the structural fix; but the *form* of the detector is a real tradeoff).
- **626 coordination is mandatory for G3** — the recover-to-empty → repopulate path is owned partly by
  626 (convergence authority / re-enumeration). Decide the seam before implementing either.

## Logged for observations (out of scope here)

- Old-generation disk leak after switchover (GC best-effort, not auto-invoked) — durability/legibility
  polish, not silently-wrong. Candidate for the inbox unless the reliability work touches GC.

---

# Long-term design (2026-06-21, agent — design round; no implementation)

> Method: I read the existing recovery/condition/health machinery on `main` (file:line below), the
> closest siblings (627 process-supervision-crash-recovery; 600 degradation-cause legibility, now
> **shipped**; 626 incremental-indexing-correctness; 588 worker silent-failures), and judged scope
> against "build new structure exactly when the present problem requires it." **The dominant finding
> is that 628 needs almost no new machinery — it needs to make data-integrity faults first-class
> citizens of two authorities the system already has.** Design is kept general, not implementation-level.

## Design thesis

The audit already showed the *primitive* mostly exists (detect-on-open + backup-rebuild + blue/green +
SQLite quarantine/restore, all unit-tested). The two real defects are **shape**, not absence:

1. **Data-integrity faults are not first-class on the existing legibility seam.** SQLite corruption
   already flows to the user as a condition with a recovery button (`queue-db.unhealthy` via
   `WorkerSnapshotTap`), but **Lucene index corruption does not** — it is caught at open, recovered
   *locally and silently* inside `RuntimeSession`, and never raised as a standing, user-surfaced
   condition. And the *silent-corruption* case (G1) is never detected at all, because nothing verifies
   integrity — **absence-of-check is silently treated as healthy.**
2. **Recovery decisions are scattered across two layers with two conflicting philosophies**, gated by
   an overloaded policy string, decided where the two layers cannot see each other.

So the design has two axes, each an **extension of an existing seam**, plus the small obs #484 fix.

## Axis 1 — Make corruption (and "unverified") first-class on the existing detect→legibility seam

This conforms to the **600/595 principle** (now shipped on `main`): *a cause must reach the ONE
legibility authority as a real reason code in the single channel — never a parallel vocabulary, and
never conflate "can't-determine" with a settled value.* The seam already exists end-to-end:

- `AssertedCondition` carries `status ∈ {TRUE,FALSE,UNKNOWN}`, a PascalCase `reason`, `severity`, and
  `Optional<OperationInvocation> recovery` — the affordance slot
  (`app-observability/.../health/AssertedCondition.java:34-52`).
- `WorkerSnapshotTap` is the worker→Head mapper: a `ConditionMapping(id, subject, severity, recovery)`
  table already emits `queue-db.unhealthy`/`queue-db.check-failed`/`schema.reindex-required`
  (the last with a `core.reindex force=true` recovery) and **states the epistemic rule verbatim —
  "Treat unknown ≠ healthy"** (`app-services/.../health/WorkerSnapshotTap.java:51,133-145`).
- `ConditionRecoveryIndex` + `readinessNotice.ts` `CAUSE_ROWS` already map reason code → wording +
  remedy operation, and `core.rebuild-index` is already the canonical recovery target for
  `index.unavailable`/`index.not_healthy`; the `check-readiness-reason-codes` register gate-checks
  forward+backward correspondence (the drift 600 closed).

**The design (general):**

1. **Proactive detection (G1) — extend the existing on-open detector, don't add a parallel one.** The
   `IndexOpenGuard.checkOnOpen` / `IndexMetadataParityGuard` seam is *already* the "check on open →
   classify into `IndexRuntimeIOException.Reason` → feed recovery dispatch" detector. Extend it to run
   a **bounded Lucene integrity verification** (footer-checksum verification — the cheap tier of
   `CheckIndex`, not a full O(postings) scan) that yields a `CORRUPT_INDEX` classification through the
   *existing* Reason channel. Full `CheckIndex` stays an optional heavier tier — **noted, not built**,
   because the present problem (the silent-wrong class) is closed by checksum verification at a defined
   point; a periodic background full-scan is structure the problem does not yet require.
2. **Tri-state index health — never "unverified == healthy."** Represent integrity as
   `verified | unverified | corrupt`, not a boolean. "Opened but not integrity-checked" is `unverified`
   — a distinct, first-class observable, exactly the 595/600 epistemic rule (and the rule
   `WorkerSnapshotTap` already names). **Whether** verification runs always vs. only on a dirty/unclean
   open is a *cost-policy knob*, not new structure.
3. **Legibility + safe recovery (G2) — one new condition, zero new surface.** Surface index corruption
   as a field on `WorkerOperationalView`, mapped by `WorkerSnapshotTap` into an `AssertedCondition`
   (`index.corrupt`, `index.rebuilding`) with `severity=ERROR` and a `recovery` pointing at
   `core.rebuild-index` — exactly as `queue-db.unhealthy` already does. It auto-flows to the user as a
   one-click rebuild via the existing CAUSE_ROWS chain (+ one register row, gate-checked). This is what
   converts G2's production posture from "worker won't start, log says rename a directory" into a real,
   surfaced recovery story **without** abandoning the conservative never-auto-destroy default — that
   default stays *available* as a policy, but is no longer the *only* path.

## Axis 2 — Collapse the two recovery philosophies into one declared recovery-contract authority

Today the decision is scattered (verified): `RuntimeSession.openComponentsWithRecovery` (Layer A:
backup + serve **empty**, gated by `auto_recovery`/`REBUILD_BACKUP_FIRST`,
`adapters-lucene/.../RuntimeSession.java:405-491`) vs. `KnowledgeServer` startup (Layer B: **blue/green**,
serve **old** while rebuilding green, gated by `blue_green_migrate`,
`indexer-worker/.../KnowledgeServer.java:505-585`) vs. SQLite `handleCorruptDatabase` (`:1975-2029`).
The two **overlap/conflict on `SCHEMA_MISMATCH`**: which fires depends on which overloaded
`schemaMismatchPolicy` string is set, and the choice is made in two layers blind to each other.

This is an instance of the **627 "recovery-contract authority" shape**: *a single declared authority
that owns detection → classification → recovery policy → terminal-state, exercised by fault injection,
rather than recovery emergent from scattered call-sites.*

**The design (general):** one **index-recovery-contract authority** that owns the decision table:

- **Input:** a classified fault (`CORRUPT_INDEX`, `SCHEMA_MISMATCH`, embedding-fp mismatch, queue
  corruption) + a unified **index-reliability policy group** (folding today's overloaded
  `schemaMismatchPolicy` + `auto_recovery` + a new corruption policy into one config group with one
  decision site — removing the cross-layer conflict).
- **Policy spine — always reconstruct from source** (the source files are still on disk; that is the
  whole premise of 628). Choose the *serving* strategy by whether the damaged index still opens
  read-only:
  - opens read-only → **blue/green from source**: keep serving stale-but-valid Blue while Green
    rebuilds (the existing Layer B path; strictly better for a search product).
  - does not open → **quarantine + serve empty + rebuild from source** (the existing Layer A path, but
    now **always chained to a full re-enumeration**, never left empty-and-passive).
- **Terminal state is always legible** (Axis 1): `index.rebuilding` while Green builds, cleared on
  cutover. The conservative `FAIL_CLOSED` stays selectable but is one declared branch, not an
  accidental dead-end.
- **Join point already exists:** `IndexGenerationManager.startMigration(String reason)` is the
  rebuild-from-source engine; its `reason` vocabulary (`schema_mismatch`, `embedding_model_change`,
  `Operation invocation: core.rebuild-index`) is the trigger seam — **corruption becomes one more
  reason** (`corrupt_index`). The authority chains detection → this engine.

**This closes G3** (recover-to-empty + passive re-watch → *guaranteed* rebuild-from-source): the
authority always drives `startMigration`, whose enumerator re-walks the watched roots. **G3 is the
explicit 626↔628 seam** — 626 owns the convergence/enumeration authority; 628 *triggers* it on
corruption. Coordinate, don't duplicate.

**Scope judgement.** Is a new authority class warranted, or over-structure? The present problem *has*
a concrete cross-layer conflict (two philosophies that fight over `SCHEMA_MISMATCH`) and a third
detected-fault class (corruption) about to need the same decision — so a single decision site is
structure the present problem requires, not speculative. But it should be a thin **policy/dispatch**
object the two existing layers consult, **not** a fused mega-service that swallows `RuntimeSession` and
`KnowledgeServer` — they remain layered (adapter vs. orchestrator) for the reason they always were.

## G4 — generation-id uniqueness (obs #484)

Local fix in `IndexGenerationManager` (`:197-200`): the second-precision `g-<yyyyMMdd-HHmmss>` id with a
hard "already exists" throw must gain sub-second precision or a monotonic uniqueness suffix, so a
same-second/programmatic migration cannot collide. Instance of *"an identity must not depend on
wall-clock granularity for its uniqueness."* Small, self-contained; conforms to the existing id shape.

## The standing guard (DoD) — fault-injection matrix

Both the detection seam and the recovery-contract authority are proven by a **mid-operation**
fault-injection matrix (the DoD's "failure-injection audit"), seeded by the existing
`RecoveryIntegrationTest` + `JobQueueTest` (which today corrupt files *between* sessions, not *during*
an op — G7). Rows: kill-before-fsync (commit), crash between `state.json.prev` rotate and `ATOMIC_MOVE`
(migration switchover), truncate-a-segment mid-write, `SQLITE_CORRUPT` on open, same-second migration
(G4), checksum-mismatch on open (G1). Each row asserts a terminal verdict (self-heal / serve-stale-and-
rebuild / quarantine-and-rebuild / fail-loud-legible) — never silently-wrong.

## Reach — principles this design instances or reveals

> Per the brief: separate *recognizing* a general principle from *building* general structure. Below I
> name the principles, where else they apply, and existing candidate violations — **without** building
> the generalized structure now.

1. **Conforms to an existing shape — the "recovery-contract authority" (sibling: 627).** Both 627
   (process faults) and 628 (data-integrity faults) need *one declared detection→classification→policy→
   terminal-state authority, exercised by fault injection*. **Conform to the shape; do NOT merge the
   authorities** — process-supervision and index-corruption faults have different detectors and
   different recovery mechanisms (no shared reason to change), so per AHA they stay two authorities
   sharing a *pattern*, not one fused `RecoveryService`. Recorded as the cross-tempdoc seam; 628 builds
   only its own.
2. **Inherits a shipped principle — "a fault reaches the ONE legibility authority as a real reason
   code; never conflate can't-determine with a settled value" (600/595).** 628's corruption/unverified
   signal is the **data-integrity instance** of exactly this; the design conforms by routing through
   `ConditionStore`→`CAUSE_ROWS` with a tri-state, not a boolean, not a parallel vocabulary.
   **Candidate existing violation to log (not fix here):** the Lucene parity guard's
   `similarity_fp`/`boosts_fp` read-only path and the `parity.allow_mismatch` escape hatch are a
   *parallel* mismatch-handling vocabulary that never reaches the condition authority — the same drift
   class 600 fought, one layer down. → observations.
3. **Reveals a candidate invariant — "reconstruct from source-of-truth, don't repair-in-place or serve
   the damaged copy."** A search index self-heals at all *only because* its content is a **projection**
   of source files still on disk (the projection-vs-fork distinction CLAUDE.md already names for data
   representations, here applied to durability). Plain statement of the invariant: *any derived store
   whose source-of-truth is still present should recover by reconstruction, not best-effort repair, and
   must never serve the damaged copy as if whole.* **Candidate scope (note, don't build):** the Lucene
   index (628, the only instance the present problem requires), the SQLite `SqlitePathResolutionStore`
   (reconstructable from the index/disk — today restored-from-backup or left stale), the
   `Sha256SidecarCache`, the RRD metric store (600 already hit its *non*-reconstruction: a stale RRD
   served partial blindness). Whether these *should* all reconstruct is a real future question; recording
   it here is the capture, not a license to build the generalized recovery framework now.

## External research pass (2026-06-21) — scoped to the one moving part

Most of this design rests on **mature, settled** foundations (Lucene commit atomicity, SQLite WAL
durability, atomic-rename pointer swaps, condition-based surfacing) where the only open questions are
*codebase-version* details (verify against the repo's Lucene/SQLite version at implementation time),
not global research frontiers. I ran **one** narrow web pass on the single genuinely-methodological,
non-conformance part — **crash-consistency fault-injection**, load-bearing for the DoD — filtered by
the **Windows-primary** platform constraint. Two findings materially sharpen the design:

1. **Tooling: prefer in-process abstraction-seam injection over a FUSE filesystem.** The leading
   power-loss simulators in the literature — **LazyFS** (FUSE page-cache "amnesia" simulator, VLDB 2024)
   and **CrashMonkey/ACE / Pathfinder** (block- and application-level, 2025) — are **Linux/FUSE-only**,
   so they do not fit a Windows-primary desktop app except as an optional Linux-CI tier. Decisively,
   **both engines we use already ship cross-platform, in-process crash simulators**:
   - **Lucene `MockDirectoryWrapper`** (pure Java, OS-independent) corrupts un-`fsync`'d files on close
     and runs **`CheckIndex`** to confirm the post-`commit()` index is intact. This is the *same
     `Directory` seam* my recovery code already wraps, it is Windows-compatible, **and it uses
     `CheckIndex` as the corruption oracle — dovetailing directly with G1's "footer-checksum/CheckIndex
     detection tier."** This is the right harness for the Lucene write-path / commit / migration-switchover
     crash rows.
   - **SQLite** is canonically crash-tested via a **crash-simulating VFS** (reorders/corrupts unsynced
     writes) — the right harness for the queue rows, at the connection/VFS seam rather than the OS.
   - **Boundary (record it):** `MockDirectoryWrapper` validates *our* `fsync` discipline, **not** the
     native `fsync` implementation — McCandless verified the latter only with a physical power-cycle rig.
     For a desktop app, native-`fsync`/hardware correctness is the OS's contract, out of our scope; our
     matrix's job is to prove *we call sync at the right points and recover correctly when we didn't*.
   → **Design refinement:** the DoD fault-injection matrix should be built on `MockDirectoryWrapper`
     (Lucene side) + a SQLite crash-VFS/connection fault hook (queue side), **in-process and
     Windows-runnable**, with LazyFS noted only as an optional deeper Linux-CI tier — not the primary.
2. **Durability stance: keep `synchronous=NORMAL`, justified by the reconstruct-from-source invariant —
   do not reflexively move to `FULL`.** Opinionated sources ("SQLite's Durability Settings are a Mess")
   push `synchronous=FULL` in WAL. But the SQLite-documented guarantee is that **WAL+NORMAL cannot
   corrupt the DB and cannot lose a committed transaction on an *application* crash** (the WAL is on
   disk); only an *OS-crash/power-loss* can drop the most-recent unsynced committed transactions, leaving
   the DB **consistent, not corrupt**. For a job queue whose contents are **reconstructable from the
   source files on disk** (Reach principle 3), a lost last-transaction = at most a re-enqueue that
   `recoverStuckJobs()` + re-enumeration already absorb — **no silently-wrong outcome.** So the relaxed
   PRAGMA is *made safe by* the reconstruct-from-source invariant; the two design pieces connect.
   `FULL` stays a future option only if a non-reconstructable consumer (e.g. a search-correctness use of
   `SqlitePathResolutionStore`) ever shares the DB — note, don't change.

Sources: [LazyFS (dsrhaslab)](https://github.com/dsrhaslab/lazyfs) ·
[When Amnesia Strikes — VLDB 2024](https://www.vldb.org/pvldb/vol17/p3017-ramos.pdf) ·
[CrashMonkey/ACE (utsaslab)](https://github.com/utsaslab/crashmonkey) ·
[Pathfinder — application-level crash-consistency, 2025](https://arxiv.org/html/2503.01390) ·
[Lucene index durability testing — McCandless](https://blog.mikemccandless.com/2014/04/testing-lucenes-index-durability-after.html) ·
[How SQLite Is Tested](https://sqlite.org/testing.html) ·
[SQLite durability settings (agwa)](https://www.agwa.name/blog/post/sqlite_durability)

---

# Pre-implementation de-risking results (2026-06-21) — six probes, `file:line`-cited

> Purpose: retire the assumptions that, if wrong, would reshape the design (not just an implementation
> detail) **before** writing feature code. Read-only. One probe (P1) **reshaped** the design; the rest
> confirmed it. Design sections above are amended inline where a probe forced it (marked **[P1-amend]**
> etc.).

| Probe | Verdict | Evidence + consequence |
|---|---|---|
| **P1 — failure timing** | **REFUTED → reshaped** | On *unrecoverable*/`FAIL_CLOSED` corruption, `KnowledgeServer.start()` rethrows (`KnowledgeServer.java:686-689`) → propagates out of `IndexerWorker.main` → the default uncaught handler writes a crash report and **`System.exit(1)`** (`IndexerWorker.java:50-58`). The gRPC server starts *after* the Lucene open (`KnowledgeServer.java:653` vs. open at `:498-586`), so the worker **dies before serving**. The Head's `WorkerSpawner` then sees only a **bare exit code** (`WorkerSpawner.java:644-645`), blind-restarts to a cap, then "gives up" (`:662-693`) — **no corruption reason, no rebuild CTA.** ⇒ Axis-1's condition seam covers **only serve-degraded** branches; the can't-serve path is **627's** process-supervision seam and carries no actionable reason today. |
| **P2 — module boundary** | **CONFIRMED** | `adapters-lucene` deps = `configuration`, `indexing`, `core`, Lucene only (`adapters-lucene/build.gradle.kts:11-26`) — **no** worker-core/indexer-worker. So `RuntimeSession` structurally cannot trigger blue/green. ⇒ the unified authority lives at the **orchestration layer**; the adapter is **detect-classify-throw only**. |
| **P3 — dispatch order + blue/green precondition** | **CONFIRMED + refined** | `RuntimeSession.openComponentsWithRecovery` is the gate: `REBUILD_BACKUP_FIRST` → Layer A recovers inline (`:446-471`), `KnowledgeServer` never sees it; any other policy (incl. `BLUE_GREEN_MIGRATE`) → `throw e` (`:477`) → `KnowledgeServer` catch (`:552-585`) runs blue/green; `FAIL_CLOSED` → throw → die (P1). **Refinement:** `KnowledgeServer` has a blue/green branch for `SCHEMA_MISMATCH`/embedding only (`:517,:554`), **none for genuine `CORRUPT_INDEX`**; and a bit-corrupt index likely can't open read-only to serve "Blue." ⇒ serve-stale-while-rebuilding is realistic for *logical* (schema/embedding) mismatch; **genuine corruption realistically means quarantine + serve-empty + rebuild-from-source.** |
| **P4 — wire-extension cost** | **CONFIRMED (low)** | Precedent exists: `IndexStatusOps.buildCompatibility()` already rides `JobQueue.QueueDbHealthSnapshot` onto the status wire (`IndexStatusOps.java:555,570`). An index-integrity signal follows the same path. |
| **P5 — test tooling + detection API** | **PARTIAL** | Lucene is **10.3.1/10.4.0** (`gradle/verification-metadata.xml:6513+`) → `CheckIndex`/checksum-verify API for G1 is available. **But** `lucene-test-framework`/`MockDirectoryWrapper` is **not a dependency**; `RecoveryIntegrationTest` hand-rolls corruption by deleting segment files. ⇒ the DoD harness is a real (small) choice: add the test-framework dep (faithful, matches the research) **vs.** extend the existing hand-rolled approach (no dep). |
| **P6 — small items** | **CONFIRMED** | (G4) `requireSafeGenerationId` only blocks path-traversal chars `.. / \ :` (`IndexGenerationManager.java:688-701`) — it does **not** enforce the `g-yyyyMMdd-HHmmss` shape, so a sub-second/suffix id passes and **existing persisted ids stay valid** (no migration trap; must avoid `:`). (Tri-state) `QueueDbHealthSnapshot` is a boolean record (`JobQueue.java:500-501`) mapped to conditions at the `WorkerSnapshotTap` layer where "unknown ≠ healthy" is already enforced — so `verified|unverified|corrupt` maps onto the **existing** `AssertedCondition` TRUE/FALSE/UNKNOWN, not new structure. |

## Design amendments forced by the probes

- **[P1-amend] Axis 1 must split by serving-state, and G2 is now load-bearing, not cosmetic.** The
  ConditionStore→CAUSE_ROWS→one-click-rebuild story works **only while the worker is up and serving**
  (SQLite recovery, blue/green mismatch, `CORRUPT_INDEX`+`auto_recovery=true`). For the **can't-serve**
  case (the `FAIL_CLOSED` *prod default*, `auto_recovery=false`, or recovery itself failing) the worker
  exits and only a bare "worker down" reaches the Head. Therefore: (a) **G2 — flipping the prod default
  to a serve-degraded recovery — is the precondition that makes corruption legible at all**, not a UX
  nicety; and (b) the can't-serve path needs an explicit **627 seam**: either a worker→Head
  *startup-failure-reason channel* (write a corruption marker the Head's `WorkerSpawner` reads, so a
  deterministic-corruption restart loop becomes a surfaced "rebuild needed" instead of blind retry →
  give-up), or 627's recovery-contract carrying the reason. This is now a recorded **cross-tempdoc
  dependency**, not solvable inside 628 alone.
- **[P2/P3-amend] Axis 2 restated precisely.** The unification is "**move the recovery *decision* up to
  the orchestration layer**" (worker-core/indexer-worker), leaving `RuntimeSession` to detect+classify+
  throw (it cannot reach blue/green). The realistic corruption recovery is **quarantine + serve-empty +
  *chained* rebuild-from-source via `startMigration`** (closing G3); "serve-stale-Blue meanwhile" is a
  best-effort luxury available for logical mismatch, not guaranteed for bit-corruption — the tempdoc's
  earlier "openable → blue/green" branch is down-weighted accordingly.
- **[P5-note] DoD harness decision is open** (add `lucene-test-framework` for `MockDirectoryWrapper` vs.
  extend hand-rolled corruption). Lean: hand-rolled for the deterministic segment/`state.json`/queue
  truncation rows (no new dep, matches `RecoveryIntegrationTest`); reserve `MockDirectoryWrapper` only if
  a row genuinely needs mid-`commit` un-synced-file randomization.

## Critical confidence rating: **7 / 10**

The design conforms to seams that the probes **confirmed present** (ConditionStore + `recovery`
affordance, `WorkerSnapshotTap` mapping, the `QueueDbHealthSnapshot` wire precedent, `startMigration`
trigger vocabulary, permissive id validation, Lucene 10.x `CheckIndex`). The one reshaping finding (P1)
made the design *better* — it promoted G2 to load-bearing and exposed the real 627 seam — rather than
invalidating it. Residual risks holding it below higher:
- **(−)** the **627↔628 dependency** for can't-serve legibility is a cross-tempdoc coordination, not
  unilaterally closable here;
- **(−)** the Axis-2 "move the decision up" refactor touches `KnowledgeServer.start()` — a large startup
  god-class — so execution carries real merge/regression risk;
- **(−)** the wire extension touches the status/proto contract (the `wire` gate workflow applies);
- **(−)** the DoD harness choice (P5) is unsettled.
None of these are *unknowns* now — they are scoped, owned risks. Confidence would rise to ~8.5 once the
627 startup-failure-reason seam is agreed with that tempdoc's owner and the Axis-2 refactor's blast
radius in `KnowledgeServer.start()` is bounded.

---

# As-built implementation status (2026-06-21, branch `worktree-628-durability`)

> The engine-core durability work is **implemented, committed, and unit-tested**. The user-visible
> legibility layer and the live browser validation are **blocked on a worktree↔main dev-stack
> constraint** (below), not on design. Commits: Stage A+E `49672de8`, Stage B `bb641a85`, Stage D(G2)
> `77699b0d`, Stage F integration `d3c6566a`.

## Done (committed + green)

- **Stage A — bounded integrity detection (G1).** `ComponentsFactory.checkIndexIntegrity` verifies
  Lucene footer checksums at the existing open-time guard seam and throws `CORRUPT_INDEX`; new config
  `index.integrity_check` (`OFF`/`STRUCTURAL`/`FULL`, default `STRUCTURAL`). `IndexIntegrityCheckTest`
  proves FULL catches silent body bit-rot and STRUCTURAL catches commit-file corruption; full
  adapters-lucene suite green (478 tests).
- **Stage B — one orchestration recovery authority + the G3 join (Axis 2).** `IndexRecoveryPolicy`
  (indexer-worker) maps the unified `index.recovery.policy` (`BACKUP_REBUILD` default / `BACKUP_ONLY` /
  `FAIL_CLOSED`). `IndexRecoveryMarker` (adapters-lucene) is a durable sibling "rebuild-pending" marker
  the adapter drops on recovery-to-empty; `KnowledgeServer.start` reads it and rebuilds from source via
  blue/green (`startMigration("corrupt_index_rebuild")`) — closing the silently-empty gap — guarding the
  embedding-fp migration from double-firing. Tests: `IndexRecoveryPolicyTest`, `IndexRecoveryMarkerTest`,
  and the cross-cutting `RecoveryIntegrationTest.bodyCorruptionRecoversAndDropsRebuildMarker` (detect →
  recover → backup → marker). The adapter stays detect-classify + low-level-recover; the *decision* and
  the rebuild chain live at the orchestration layer (the P2 module-boundary fix).
- **Stage D part 1 — safe-by-default (G2).** `application.yaml` ships `auto_recovery: true` +
  `recovery.policy: BACKUP_REBUILD` + `integrity_check: STRUCTURAL`: corruption self-heals (back up,
  serve degraded, rebuild from source) by default, never deleting the damaged copy. `FAIL_CLOSED` stays
  opt-in.
- **Stage E — generation-id uniqueness (G4 / obs #484).** `IndexGenerationManager.newUniqueGenerationId`
  disambiguates same-second ids with a numeric suffix instead of throwing; `Thread.sleep(1100)` test
  workaround removed; `sameSecondMigrationsDoNotCollide` added. Full worker-core suite green.
- **Stage F (partial).** Matrix rows covered by unit tests: footer-checksum mismatch on open (A),
  full-body bit-rot detection (A), detect→recover→marker self-heal chain (A+B), same-second migration
  (E). Pre-existing coverage: `SQLITE_CORRUPT` quarantine/restore (`KnowledgeServerTest`/`JobQueueTest`).

## Remaining (precise insertion points already mapped above in the Stage C/D plan)

1. **Stage C — corruption *cause* legibility (user-visible).** The rebuild itself is *already* visible
   via the existing migration-status UI when Stage B kicks `corrupt_index_rebuild`. The increment is the
   worded cause + a dedicated condition: add `LifecycleReasonCode.INDEX_CORRUPT`/`INDEX_REBUILDING` +
   `CAUSE_ROWS` rows (mirror `index.schema_mismatch` → `core.rebuild-index`), a `WorkerSnapshotTap`
   `ConditionMapping` with `core.rebuild-index` recovery, and the worker→Head integrity signal
   (smallest form: a `source`/`reason` field on `MigrationStatusGroup`, which already reaches the FE —
   cheaper than a new integrity message). Gates: `wire`, `check-readiness-reason-codes`, ui-web set.
2. **Stage D part 2 — can't-serve marker (627 seam).** Depends on Stage C's condition infra: on
   unrecoverable `FAIL_CLOSED` corruption the worker `System.exit(1)`s before serving; write a durable
   corruption-reason marker `WorkerSpawner` reads on worker-death to raise a "rebuild needed" condition
   instead of blind-restarting.
3. **Stage F — remaining rows.** Crash between `state.json.prev` rotate and `ATOMIC_MOVE`; the
   KnowledgeServer-level live assertion that the rebuild migration actually kicks (best as a dev-stack
   smoke — see blocker).

## Live validation — DONE, and it found + fixed a real bug (engine merged to `main`)

The engine work was merged to `main` (merge commits) and **live-validated against the running dev
stack** (the SSOT-validation RED on `main` is another agent's uncommitted schema work and does not
block `installDist`/runtime). Procedure: clean stack → ingest corpus → `segments_N` checksum-corrupt
the active generation → restart worker. Result (worker.log + `/api/knowledge/status`):

- ✅ corruption **detected** (`CorruptIndexException: checksum failed`, `CORRUPT_INDEX`),
- ✅ **backed up** (`g-…bak-<ts>`, never deleted),
- ✅ recovered to empty + **rebuild-pending marker** dropped,
- ✅ **"was recovered to empty (reason=corrupt_index). Rebuilding from source via blue/green…"** — the
  G3 chain fired (new green generation; marker cleared),
- ✅ **worker stayed up** (`state:READY healthy:true`), serving the empty Blue while Green rebuilds
  (`servingSearchGenerationId ≠ servingIngestGenerationId`) — the designed serve-degraded-while-rebuilding.

**Bug found + fixed by the live run (commit `8e97ef4d`, merged):** the pre-existing recovery path died
under the worker's **deferred (read-only-first) open** — after the backup empties the directory, a
read-only reopen throws "no index exists" (`ComponentsFactory` read-only path requires an existing
index). Unit tests used the read-WRITE `open()` path and so missed it. Fix: `materializeEmptyIndex()`
writes a fresh empty commit before the read-only reopen, and the rebuild marker is now written
regardless of read-only (the live first-open *is* read-only). New `RecoveryIntegrationTest
.deferredReadOnlyOpenOfCorruptIndexRecovers` reproduces it. **This is the headline value of the live
pass** — the self-heal would have failed in production without it.

## Second round — Gap 1, Stage F, Stage C done; reconstruct-from-source proven live

After a critical-analysis pass against the tempdoc goals, the remaining gaps were implemented (merged to
`main`): commits Gap 1 `4a7f8ee`, Stage F `e597372`, deferred-recovery fix `8e97ef4d`, Stage C `ff8ea22`.

- **Gap 1 — silent body bit-rot caught after a crash.** The `STRUCTURAL` default is cheap but misses
  body bit-rot; `FULL` catches it but is O(index size). New `CleanShutdownMarker`: the writable runtime
  drops it on a graceful close; the next open consumes it. An *absent* marker (crash/first-run)
  escalates `STRUCTURAL`→`FULL` for that open — so post-crash body corruption is detected without paying
  `FULL` on clean restarts. (`write.lock` can't serve this — `NativeFSLockFactory` keeps the file after
  a clean close too.) Test `cleanCloseWritesMarkerAndOpenConsumesIt`. **This closes the one genuine
  conceptual gap the critical pass found** (the silent-wrong class was *closeable* but not *closed by
  default*). Out of scope (noted): a *periodic background* bit-rot scan for a never-crashed system.
- **Stage F — `state.json` torn-write row.** Regression test: an invalid `state.json` + valid
  `state.json.prev` recovers the active-generation pointer (`loadStateBestEffort`).
- **Stage C — corruption *cause* worded in the UI.** `migration_source` now rides the status wire
  (`MigrationStatus` gRPC + `MigrationStatusGroup` wire field), read from the generation manifest by
  `IndexStatusOps`. `StatusLifecycleHandler` emits `LifecycleReasonCode.INDEX_REBUILDING` for a
  `corrupt_index_rebuild` migration; a new `readinessNotice` `CAUSE_ROWS` row words it. `wire`,
  `check-readiness-reason-codes`, conformance, and FE typecheck/unit gates are green.

## Live validation (round 2) — the headline result

With a **watched root** (34 files) + the local model active, a corruption restart drove the **complete
reconstruct-from-source**: detect → back up (`*.bak-<ts>`, never deleted) → blue/green rebuild that
**re-enumerated the watched root and fully recovered all 34 documents** (`activeDocCount: 34`,
`embeddingCoveragePercent: 100`) → cutover → `READY`. This is the tempdoc's central "rebuild from the
source files still on disk" primitive proven end-to-end on the running stack.

**Honest caveat — the transient `index.rebuilding` banner was not screenshotted live.** The Stage C
data chain is verified by the conformance test (serialization), the `wire` gate (proto↔record), the
`check-readiness-reason-codes` gate (enum↔`CAUSE_ROWS` correspondence), and FE unit tests (the wording
renders). But the *live* banner render hit a dev-environment catch-22: without the AI runtime the
rebuild stalls → worker status goes stale → the FE wedges (the known 604 SSE-wedge); with AI the 34-doc
rebuild completes too fast to capture. Catching it would need a much larger watched root (minutes-long
rebuild) — a dev-stack-smoke deferral per `slice-execution.md`, not a defect.

## Remaining (one niche item)

- **Stage D-part2 — can't-serve "rebuild needed" condition (627 seam).** When the worker dies on
  *unrecoverable* corruption under the opt-in `FAIL_CLOSED` policy and exhausts restarts, surface a
  condition with a `core.rebuild-index` affordance instead of a silent restart-loop. **Lower value now
  that G2 self-heals by default** (the worker stays up and rebuilds — validated above — so it rarely
  dies on corruption). Insertion points mapped in the plan: inject `ConditionStore` into `WorkerSpawner`
  (via `SubstrateGraph.health()`), a death-reason marker the dying worker writes, and an `index.corrupt`
  condition + `CAUSE_ROWS` row. Deferred as the one remaining niche safety-net.

# Pre-implementation de-risking (round 3) — should the flagged deviations be *built*?

> A critical pass flagged four design deviations. This round ran read-only probes to decide **build vs.
> skip** for each (the risk is "should I even build this", not just "how") — to avoid risky churn on
> live-validated code, redundant surfaces, or premature abstraction. Verdicts below, `file:line`-cited.

- **P1 — Axis-2 unification (collapse to one authority): SKIP (evidence-backed).** Traced the recovery
  dispatch: `KnowledgeServer`'s catch (`KnowledgeServer.java:581-584`) handles **only**
  `SCHEMA_MISMATCH + blue_green_migrate`; there is **no `CORRUPT_INDEX` handler** — corruption flows
  adapter→`IndexRecoveryMarker`→rebuild as one coordinated path (`:510-530`). The policy string routes
  **deterministically** (REBUILD_BACKUP_FIRST→adapter inline, BLUE_GREEN→orchestrator, FAIL_CLOSED→die);
  **no cell where both layers act or contradict.** The design's "cross-layer conflict" is benign
  *untidiness* (split decision + two config keys), not a runtime defect. Unifying = high regression risk
  on tested + live-validated recovery for cosmetic gain; the current `IndexRecoveryPolicy` + marker IS
  the "thin object the layers consult" the design's own scope-judgement endorsed. **The deviation is an
  acceptable substitution.** *(Out-of-scope sub-finding: `SCHEMA_MISMATCH + REBUILD_BACKUP_FIRST`
  recovers-to-empty without chaining a rebuild — the G3 gap I closed for `CORRUPT_INDEX` persists for
  schema-mismatch; but "routine schema-migration" is explicitly OUT of 628's scope. → observations.)*
- **P2 — Axis-1 `ConditionStore` route + tri-state: SKIP both.** Schema-mismatch *does* dual-surface
  (a `WorkerSnapshotTap` `schema.reindex-required` condition with a `core.reindex` affordance
  (`WorkerSnapshotTap.java:133-135`) **and** the `index.schema_mismatch` reason code), so a corruption
  condition would be *consistent*, not parallel-vocabulary drift. **But** the condition's value is its
  *recovery affordance*, which is **redundant for the already-auto-rebuilding case** — the reason-code
  banner I built is the right surface there. The affordance's genuine home is the **can't-serve case
  (D-part2)**, where a Rebuild button is actionable. **Tri-state: zero consumers exist** (grep: only my
  own comments) → premature abstraction; record the principle (already embodied by `OFF` = "UNVERIFIED,
  never silently healthy"), don't build the observable.
- **P3 — mid-operation fault injection: SKIP (optional).** `lucene-test-framework`/`MockDirectoryWrapper`
  is not a dep (`gradle/libs.versions.toml` has only `lucene-core` etc.). Mid-op injection
  (kill-before-fsync, truncate-mid-write) tests the **Lucene/SQLite-owned** durability guarantee — their
  own crash suites (`MockDirectoryWrapper`, SQLite TH3) cover it. The recovery logic *we* own is tested
  between-session, and *our* atomic-move code is covered by the `state.json` torn-write test. Adding the
  test-framework dep for marginal value isn't warranted; the DoD's mid-op intent is met in spirit
  (our atomic-move tested; segment-fsync durability is Lucene's contract).
- **P4 — Stage D-part2 wiring: GO (clean, low-risk).** `WorkerSpawner` is in `app-services`, which
  already uses `ConditionStore` pervasively, so injection is a **clean constructor add, no
  module-boundary issue** (`KnowledgeServerBootstrap.java:134`). This is the **one genuinely-valuable
  remaining item** — and it is exactly where the Axis-1 condition-with-affordance belongs (a Rebuild
  button is actionable when the worker can't serve, unlike when it's already rebuilding). Residual
  uncertainty: the death-reason-marker path + testing it (`FAIL_CLOSED` + forced recovery-failure).

## Net de-risking outcome + confidence

The de-risking **collapsed three of the four "deviations" to evidence-backed SKIPs** (acceptable
substitution / redundant / premature) and left **one clean GO** (D-part2, which also absorbs Axis-1's
genuine condition-with-affordance value). This is the intended result: it prevents reflexively building
risky churn (Axis-2), a redundant surface (Axis-1 rebuilding condition), or a consumer-less abstraction
(tri-state).

**Critical confidence for the remaining work: 8.5 / 10.** High because the de-risking established that
most of the "remaining" work *should not be built*, and the one piece that should (D-part2) is a clean,
same-module constructor add reusing the `WorkerSnapshotTap`/`AssertedCondition` pattern. The residual
−1.5: D-part2's death-reason-marker handshake (worker writes pre-`System.exit`, spawner reads on
`exitValue`) and its test path (`FAIL_CLOSED` + forced recovery-failure, or a simulated death-with-marker)
are the only unproven mechanics — niche and low-blast-radius, but not yet exercised.

# Stage D-part2 — DONE (merged), with a design-constraint finding

Implemented + unit-tested + merged to `main` (commit `0907db64`). The dying worker stamps a
`WorkerFatalReasonMarker` (new, in `ipc-common` — the Head↔Worker IPC contract) on the unrecoverable
`CORRUPT_INDEX` exit (`KnowledgeServer.start`'s outer catch, a *controlled* throw → `System.exit`, so
the write is reliable); the Head reads + clears it on the worker-down transition
(`KnowledgeServerBootstrap.workerDownReason`) and **enriches the existing `worker.capability` condition's
message** with the corruption cause + recovery guidance, instead of a silent restart-loop. Reused the
existing `WorkerCapability → CapabilityHealthBridge → ConditionStore` chain — **no new condition / label
/ producer.** Test: `WorkerFatalReasonMarkerTest` (the marker handshake).

**Implementation finding that re-shaped the affordance (honestly):** a one-click `core.rebuild-index`
button **cannot function for a *dead* worker** — `core.rebuild-index` drives `startMigration` *via gRPC
on the live worker*, and under `FAIL_CLOSED` the worker has exited. A button that errors would be a
dishonest affordance (the exact anti-pattern 628 fights). So D-part2 ships the **informational** form:
the worker-down condition now *names* the cause ("the index is corrupt; the worker won't auto-recover
under the fail-closed policy — set `index.recovery.policy=BACKUP_REBUILD` or remove the index dir to
rebuild") and is set **only** for a corruption death, never a GPU/OOM/other crash. A *functional*
one-click recovery for a dead worker would need a new **restart-with-recovery** operation (the Head
flips the effective policy + restarts the worker, which then self-heals) — a larger, separate piece,
recorded as a follow-up. The default `BACKUP_REBUILD` path never reaches this (self-heal, live-proven),
so this is purely the opt-in `FAIL_CLOSED` diagnostic.

**Live validation deferred to a documented smoke** (`slice-execution.md`): exercising it needs a
`FAIL_CLOSED` config override (not cleanly settable via the MCP dev stack, which loads the
`BACKUP_REBUILD` `application.yaml`) + repeated worker death + survives the 604 FE-wedge. The novel
mechanic (the marker handshake) is unit-tested; the message→condition flow reuses the already-proven
`CapabilityHealthBridge` path (live-validated for `schema.reindex-required` etc.). **Smoke:** set
`index.recovery.policy=FAIL_CLOSED` + `index.auto_recovery=false`, corrupt the active index, restart →
worker exits + give-up → assert the `worker.capability` condition message names the corruption cause
(via `/api/health/events` or `/api/condition-recovery-index`).

**628 is now functionally complete:** every tempdoc goal is implemented + merged; the engine
detect→rebuild-from-source is live-proven; the only deferrals are evidence-backed (the three round-3
SKIPs) or documented (the D-part2 FAIL_CLOSED smoke + the restart-with-recovery follow-up).

---

# Future directions / theoretical extensions (2026-06-21, post-implementation ideation)

> Pure ideation on what 628's primitives unlock — no commitment, the app is pre-production with no
> users. Each idea names the 628 primitive it builds on + the external pattern that informs it; tagged
> **[extend]** / **[UX]** / **[polish]** / **[simplify]**. Research sources at the foot.

## The unifying lens — the index is a CQRS read-model, and that's a moat

The deepest finding: 628 independently rediscovered the **event-sourcing / CQRS read-model** pattern.
The Lucene index is a *materialized projection* of the source files (the files are the write-model /
single source of truth); the blue/green rebuild is exactly the canonical CQRS "build a new read-model,
switch queries over, archive the old" approach; and re-indexing must be **idempotent** (same file →
same index). The corruption-recovery work is, in event-sourcing terms, *rebuilding a projection by
replaying the source*.

Two consequences worth leaning into:

- **Reconstruction is LOSSLESS — a genuine differentiator.** Because the index is *derived*, a rebuild
  is perfect. Contrast SQLite `.recover`, which is explicitly "fuzzy/suspect" (constraints may be
  violated) because the DB *is* the source of truth. And contrast Windows Search, which on corruption
  silently serves "incomplete or incorrect results" and offers only a buried, opaque, *up-to-24-hour*
  manual rebuild with no progress. JustSearch can promise: **"your files are the truth; we can always
  rebuild a perfect index from them."** That is a product promise, not just an engine property.
- **Every derived store is a projection that can self-heal.** The reach section's "reconstruct from
  source-of-truth" invariant *is* the CQRS "rebuild any projection at any time" property — it
  generalizes to the SQLite path-resolution store, the sha256 sidecar cache, and the RRD metric store.

**[extend]** Name this principle explicitly, and make **re-index idempotency an explicit, tested
invariant** (it's the bedrock the whole self-heal rests on).

## Theme A — Proactive integrity: a periodic background scrub **[extend]**

628 detects corruption *at open* and escalates to FULL after a crash. The next frontier (the noted
out-of-scope item, now research-grounded) is a **low-priority background scrub** that periodically
full-verifies the live index, so silent disk bit-rot is caught *before* a query serves wrong results —
the ZFS/btrfs `scrub` pattern: **schedule** (monthly general / weekly high-churn, in idle windows),
**throttle** (deprioritized I/O so search isn't disturbed, pause/resume), and **trend** ("treat nonzero
repairs as early warnings" → corruption-rate telemetry, an MTBF/reliability signal).

## Theme B — Smarter / faster recovery **[extend]**

- **Partial recovery instead of full rebuild.** Drop only the corrupt segments (Lucene
  `CheckIndex -exorcise`) and re-index *only the affected documents* — we know their source files. Far
  faster than a full rebuild for a large index. (Elasticsearch exorcises corrupt segments; our edge is
  that, having the source, we can re-add exactly the dropped docs rather than lose them.)
- **The restart-with-recovery operation** (the D-part2 follow-up) — a functional one-click recovery for
  a *dead* worker (flip policy + restart → self-heal).
- **Schema-mismatch rebuild chaining** — extend the marker→rebuild chain to the `SCHEMA_MISMATCH`
  recover-to-empty path (the G3 gap that persists for schema-mismatch; logged to observations).
- **Generalize reconstruct-from-source** to the other derived stores (path-resolution, sha256 sidecar,
  RRD) — each becomes self-healing.

## Theme C — New UX surfaces (graceful-degradation principles) **[UX]**

- **A "data safety" / index-health dashboard:** integrity status + last-verified time + backup history
  + recovery events + corruption trend. Foregrounds the lossless promise ("your files are safe; the
  index can always be rebuilt") — a trust surface Windows Search has no equivalent of.
- **Rebuild progress + ETA:** the rebuild walks a *known* file set, so "rebuilding 40 / 200 files,
  ~2 min" is cheap to show. Transparency builds trust (graceful-degradation UX research); Windows
  Search gives none.
- **Honest degraded search during a rebuild:** "showing keyword results while semantic search rebuilds —
  your files are safe." Preserve context, name what's reduced, offer the basic mode. Extends Stage C's
  banner to result-count honesty mid-rebuild.
- **On-demand "Verify my index" + "Rebuild from my files":** because reconstruction is lossless, a
  Rebuild button is a *risk-free reset* — a confidence feature unique to local-first (a primary-store
  app could never offer a no-data-loss "rebuild everything" button).
- **Backup / snapshot management:** the `*.bak-<ts>` quarantines + the blue/green generations *are*
  snapshots → expose "restore to a previous index" + a forensics view, with auto-GC (which also closes
  the logged old-generation disk-leak).

## Theme D — Polish / simplify **[polish] [simplify]**

- Unify the marker family (`IndexRecoveryMarker` / `CleanShutdownMarker` / `WorkerFatalReasonMarker`)
  behind one small durable-sibling-marker shape **only if** they truly share a reason to change (they
  share a *form*, not necessarily a *reason* — weigh against AHA before DRYing).
- Consolidate the three recovery config keys (`index.auto_recovery` / `index.schema_mismatch.policy` /
  `index.recovery.policy`) into one documented *index-reliability* group — the low-risk, docs-only half
  of the Axis-2 SKIP (the dispatch stays as-is, since the de-risking showed the split is benign).

## Rough prioritization (value × leverage; no rush)

1. **The read-model framing + lossless promise** — conceptual, cheap, unifies the reach principle and
   gives marketing/positioning a real differentiator vs. Windows Search / SQLite-backed apps.
2. **Periodic scrub** + **rebuild progress/ETA** — proactive detection + the trust surface, both
   leveraged off existing primitives.
3. **Partial recovery (exorcise + targeted re-index)** — turns minutes-long rebuilds into seconds for
   large indexes.
4. **The data-safety dashboard / snapshot UX** — the consolidated user-facing payoff.
5. **Polish/simplify** — last, and only where AHA agrees.

Sources: [ZFS scrub best practice (Klara/cr0x)](https://klarasystems.com/articles/understanding-zfs-scrubs-and-data-integrity/) ·
[Elasticsearch corruption + CheckIndex/shard tool](https://www.elastic.co/docs/troubleshoot/elasticsearch/corruption-troubleshooting) ·
[SQLite recovery ("results will always be suspect")](https://sqlite.org/recovery.html) ·
[CQRS read-models / projection rebuild](https://learn.microsoft.com/en-us/azure/architecture/patterns/cqrs) ·
[Event Sourcing projections](https://event-driven.io/en/projections_and_read_models_in_event_driven_architecture/) ·
[Graceful-degradation UX](https://www.aiuxdesign.guide/patterns/error-recovery) ·
[Windows Search rebuild UX](https://learn.microsoft.com/en-us/troubleshoot/windows-client/shell-experience/windows-search-performance-issues)

---

# Long-term design (post-ideation) — scoped to what the problem actually requires

> The future-directions section is a *menu*. This section is the design judgment: which of it the
> **present problem requires** (build), which is the **right shape but has no present evidence**
> (recognize + design + defer), and which is **speculative** (don't design now). The size of the change
> is the outcome of that judgment, not a target. Investigation: the rebuild-marker is written only in
> the `CORRUPT_INDEX` branch (`RuntimeSession.java:442`); the `SCHEMA_MISMATCH` recover-to-empty branch
> (`:460-471`) backs up + empties but never chains a rebuild — the *same silent-empty class*, unfixed.

## The one structural design the present problem requires — a trigger-agnostic recover-to-empty primitive

628's headline invariant is **"a recovered-to-empty index always rebuilds from source — never silently
empty."** I made that hold for **corruption** (the `IndexRecoveryMarker` written in the `CORRUPT_INDEX`
branch → `KnowledgeServer` rebuild chain). But it is bolted onto *one trigger*: the `SCHEMA_MISMATCH`
recover-to-empty path backs up + empties and **does not** write the marker, so it leaves a silently-empty
index awaiting passive re-watch — exactly the gap I closed for corruption. **Two instances of one class**
(corruption fixed, schema-mismatch unfixed) ⇒ per `structural-defects-no-repeat`, the structural fix is
warranted, not a second per-branch bolt-on.

**Design (general):** the invariant belongs to the **recover-to-empty action**, not to each caller.
Extract one primitive — *back up the damaged dir → materialize an empty index → record the
rebuild-from-source intent* — that **every** recovery branch (corruption, schema-mismatch, and any
future trigger) routes through. Then "no silently-empty index" holds **by construction**: it is
impossible to recover-to-empty without chaining a rebuild, because the marker write is *inseparable*
from emptying the index. This adds no new machinery — it reuses the existing marker → rebuild chain
(Stage B) + the `migration_source` legibility (Stage C); the only structure is the *extraction* that
makes the guarantee structural. **Conforms to 626** (status *active*): the rebuild *triggers* 626's
enumeration/convergence authority (`startMigration`), it does not duplicate it — the 626↔628 seam stays
"626 owns convergence, 628 triggers it on recover-to-empty."

## Recognized + designed, but DEFERRED (correct shape, no present evidence) — the proactive scrub

The silent-wrong thesis has exactly one residual after Gap 1: **disk bit-rot on a never-crashed
machine** (STRUCTURAL-default checks the small commit/segment-info files; FULL — which catches body
bit-rot — runs only on a *dirty* open). The **correct long-term design** is a periodic background
**scrub**: reuse `checkIndexIntegrity` (FULL) over the active generation's files, **scheduled +
throttled via the existing stuck-job-reaper pattern** (`KnowledgeServer.java:411` —
`scheduleWithFixedDelay`, a deprioritized idle cadence), and on detection route into the *existing*
recovery chain. No new detection or recovery machinery — only a throttled scheduler, mirroring the
reaper.

**Deferred, deliberately:** 628's thesis is *crash*-consistency; bit-rot is an adjacent failure mode,
there is **no observed incident**, and the target is a desktop SSD (low bit-rot probability). Building a
background scheduler now is *structure for a case the problem does not yet include*. The design is
recorded; it is **warranted when bit-rot becomes real** — production, spinning/cheap media, much larger
corpora, or a first observed silent-corruption incident (`structural-defects-no-repeat`: one documented
instance proves the class — we have zero).

## Scoped follow-up (real but niche; 627's domain) — restart-with-recovery

D-part2 ships the *informational* can't-serve cause because a one-click `core.rebuild-index` cannot run
against a *dead* worker. The functional form is a Head-side **restart-with-recovery** action: restart
the worker with the recovery policy forced to `BACKUP_REBUILD` for that start, so it self-heals on
reboot. This is **627's domain** (the process-supervision recovery-contract); 628's contribution is only
the *reason* (the corruption marker). Gated on a genuine question: a user who *chose* `FAIL_CLOSED`
wants manual control — a one-click override may contradict that intent, so this may be guidance-only by
design.

## Explicitly NOT designed now (speculative — no present problem)

Partial recovery (`CheckIndex -exorcise` + targeted re-index) is a *performance* optimization with no
large-index pain yet; the data-safety dashboard, snapshot-restore UX, and generalizing
reconstruct-from-source to the path-resolution / sha256-sidecar / RRD stores have **no users, no demand,
and no observed corruption** of those stores. They are recognized in the future-directions section;
designing them now would be structure for cases the problem does not yet include.

## Reach — the principle this design instances, and its candidate scope

**Principle (recognize, do not build the framework): the index is a derived read-model (a CQRS/event-
sourcing *projection*) of the source files, and a projection whose source-of-truth is present must be
*idempotently reconstructable* from it — so "corruption recovery" is just *projection rebuild*, and
recovery is therefore LOSSLESS** (unlike recovering a primary store, e.g. SQLite `.recover`'s "fuzzy"
result). 628's recover-to-empty primitive is the projection-rebuild trigger; the blue/green migration is
the canonical CQRS "build new read-model → switch → archive old".

- **This conforms to an existing seam, not a new one:** the reconstruct-from-source idea is the same
  *projection-vs-fork* distinction CLAUDE.md already names for data representations, here applied to
  durability; and the rebuild reuses 626's convergence authority. Conform, don't parallel.
- **Where else it applies (candidate scope — note, don't build):** every *derived* store —
  `SqlitePathResolutionStore`, the `Sha256SidecarCache`, the RRD metric store. **Existing violation
  already on record:** tempdoc 600 found the RRD store served *partial blindness* rather than
  reconstructing from its catalog-derived source — the same class, one layer over. The candidate fix
  (each derived store self-heals by reconstruction) is real, but no present problem forces it — so the
  principle and its scope are recorded here, and the generalized "projection-manager" structure is
  **not** built (that would be premature abstraction).
- **The load-bearing invariant to make explicit + test:** *re-indexing is idempotent* (same source file
  → same index entry). It is the bedrock the whole self-heal rests on; today it is assumed, not asserted
  — a guard test is the cheap, in-scope way to keep the projection honest.

# Pre-implementation de-risking (design round) — the "remaining work" collapses to ~nothing under 628

> Read-only probes to decide **build / skip / hand-off** for the design above, *before* touching
> live-validated recovery code. The headline: the one item the long-term-design section called "the one
> structural design the present problem requires" turns out, under evidence, to be **dev-only and out of
> 628's scope** — and every other candidate belongs to a sibling tempdoc or is evidence-gated. **628 has
> no in-scope build-worthy work remaining.**

- **P1 — schema-mismatch recover-to-empty truth table: the gap is real but DEV-ONLY + no double-fire.**
  `RuntimeSession.java:460-475`: only `REBUILD_BACKUP_FIRST` recovers-to-empty *without* a rebuild marker;
  `BLUE_GREEN_MIGRATE` rethrows → `KnowledgeServer` blue/green (which *does* rebuild from source);
  `FAIL_CLOSED` rethrows → fail-loud (no empty). The three are mutually exclusive, so a marker could not
  double-fire. **Shipped defaults** (`ResolvedConfigBuilder.normalizeSchemaMismatchPolicy`): `FAIL_CLOSED`
  prod / `REBUILD_BACKUP_FIRST` dev — so the silently-empty cell is **only reachable in dev by default**,
  never in a shipped build.
- **P2 — scope: schema-mismatch recovery is OUT of 628's lane.** 628 Scope says verbatim *"Out: routine
  schema-migration design."* Schema-mismatch recovery *is* routine schema-migration. ⇒ **SKIP under 628**;
  log the dev-only recover-to-empty gap as a candidate for **626** (active — owns enumeration/convergence)
  or a schema-migration tempdoc. **This corrects the long-term-design claim above** that it was "the one
  structural design the present problem requires": it is neither prod-reachable nor in-scope.
- **P3 / P4 — moot.** With the schema-mismatch fix skipped, the recover-to-empty primitive extraction and
  the schema-rebuild-correctness check are not needed. (Recorded shape stands for whoever picks it up in
  626/schema-migration.)
- **P5 — idempotency holds, but the guard is 626's domain.** The write path is an **upsert keyed by a
  stable doc-id** (`DocumentFieldOps` `TermQuery(idField, docId)` read-modify-write) — so re-indexing
  replaces, never appends; idempotency is in fact the *bedrock of incremental indexing*, which would
  duplicate docs without it. So the invariant is real + assertable (granularity: doc-id stability +
  no-duplicates, not byte-equality), **but it belongs to tempdoc 626's convergence domain** and is very
  likely already implicitly covered there — a new 628 guard would be a parallel test, not a gap.

## Net outcome + confidence

The de-risking **dissolved the remaining 628 work**: the schema-mismatch G3 case is dev-only + explicitly
out-of-scope (→ 626), the idempotency guard is 626's, the scrub is evidence-gated (deferred), and
restart-with-recovery is 627's. **No in-scope, build-worthy, evidence-backed work remains under 628** —
its core (crash-consistency, detect→rebuild-from-source) is complete + live-proven. The right move is to
*record the hand-offs*, not to touch live-validated recovery code for a gap that's dev-only and another
tempdoc's.

**Critical confidence for the remaining work: 9 / 10.** High because the de-risking established there is
essentially nothing left to *build* under 628 — each candidate is out-of-scope, a sibling's, or
evidence-gated, so there is little left to be surprised by. The residual −1: the hand-offs (schema-mismatch
recover-to-empty + the idempotency guard → 626; restart-with-recovery → 627) are routing judgments I'm
asserting without coordinating with those tempdocs' owners, and 626 is *active* (its current shape could
absorb or reframe these).

# Standing guard for the headline claim — DONE (merged)

The de-risking above *slightly overshot* in saying "nothing left to build": the one genuinely in-scope,
non-speculative item was a **standing automated guard for the headline self-heal claim**, which until now
existed only as a one-time manual dev-stack proof. Built + merged:
**`CorruptionRebuildE2ETest`** (`modules/system-tests/src/systemTest/.../process/`).

- **What it proves, end-to-end (real worker process):** index a doc into a watched root → corrupt the
  active generation's `segments_N` → the worker detects `CORRUPT_INDEX`, **backs it up (never deletes)**,
  recovers-to-empty, and **rebuilds from source by re-enumerating the watched root** → the same doc is
  searchable again. Verified in the run: *"Corrupted index backed up… → Index recovered (empty;
  rebuild-from-source pending) → Migration enumerator scanning root: …rebuild-docs"* → keyword hit
  restored, `*.bak-<ts>` present. AI-free (keyword), restart-tolerant across the rebuild's planned
  restarts, `@Timeout(3 min)`, runs in ~50s. systemTest tier (`-PincludeSystemTests=true`; manual-CI per
  ADR-0026) — a dispatched standing regression guard.
- **Reused, didn't build:** the whole `system-tests` chaos harness (`WorkerProcessManager` /
  `MmfTestHarness` / `GrpcTestClient` / `TestEnvironmentProvisioner`), mirroring `MigrationControlE2ETest`.
- **Finding surfaced + logged (not fixed — out of scope):** the systemTest worker runs **standalone (no
  Head)**, and that boot path (`IndexerWorker.java:85`) bootstraps config from env+JVM only — it **skips
  `application.yaml`'s `index` section**, so `index.auto_recovery` / `recovery.policy` / `integrity_check`
  silently default (recovery *disabled*), diverging from production where the Head supplies them. The test
  works around it the production-faithful way — a **worker config snapshot**
  (`justsearch.worker.config_snapshot`) with `auto_recovery=true` + `BACKUP_REBUILD`, **no product
  change**. The standalone-mode gap is logged to the observations inbox for a future fix.

With this, 628's core claim (engine detect→rebuild-from-source) now has a **standing automated guard**, not
just a one-time manual smoke — closing the DoD's "guards holding the fixes" for the core path. The Stage
C/D UI smokes remain documented-manual as before.

- **Unrelated pre-existing failure (logged):** `IndexerWorkerGuardrailsTest` fails on an untracked
  `TikaOcrRuntime` (another agent's 607 OCR work calling `System.getenv`/`getProperty`), blocking a
  clean *full* indexer-worker suite run; my indexer-worker tests pass individually
  (`KnowledgeServerTest`, `IndexRecoveryPolicyTest`). Logged to observations.

---

# Long-term design — the standalone-config divergence the guard surfaced

> Building the standing guard surfaced one concrete issue (not 628's core, but the freshest real finding):
> the **standalone worker silently diverges from production config**. This section theorizes the correct
> long-term design, conforming to the config-contract authority that already exists (**tempdoc 331**), and
> records its reach. The design is general; the implementation belongs to the config-architecture lineage
> (331/347), not to 628 — 628 only *surfaced* it.

## What the problem actually is (and what already exists)

The systemTest's worker boots **standalone (no Head)** and, per `IndexerWorker.java:85-88`, composes its
`ResolvedConfig` from `contributeAutoDetected + contributeEnvRegistry` **only** — it omits the
`JustSearchConfigurationLoader.loadYamlRoot().ifPresent(builder::contributeYaml)` line the Head runs
(`HeadlessApp.java:501`). So *every* YAML-only key (`index.auto_recovery`, `recovery.policy`,
`integrity_check`, and the whole `contributeYamlIndex`/watcher/vector/… set) silently falls to defaults —
recovery *disabled* — diverging from production with **no signal**. (This cost the guard 3 failed runs to
diagnose; a dev testing recovery/migration in `runWorkerStandalone` would be misled identically.)

This is not a one-off. **`contributeYaml` is copy-pasted across 5+ independent build sites** —
`HeadlessApp`, `LauncherEnvironment`, `ConfigStoreRebuilder`, `RuntimeSession`, `SsotCommitMetadataSource`
— and the standalone worker is simply the site that *forgot* it. The divergence is the **symptom**; the
copy-pasted source-composition is the **root cause**. And this directly violates the *stated* thesis of
**tempdoc 331 ("shared-config-contract")**: *"resolve once; divergence is impossible (not just warned
about)."* 331 made the worker **snapshot** the Head→worker contract — but the **composition** that
*produces* the resolved config is still hand-rolled per site, so a site can drift by omission. Standalone
did.

## The correct long-term design (scope-matched, conforming to 331)

**Single-source the worker-boot config composition.** Extract the standard worker-relevant source chain
(*auto-detected + env-registry + yaml*) into **one shared step** (a `ResolvedConfigBuilder` factory/method,
e.g. `contributeStandardWorkerSources()`), and have every worker-boot entry point — the Head *and* the
standalone worker — call it instead of hand-listing sources. The Head adds its Head-only source
(`contributeUiSettings`) on top; the snapshot it emits stays the worker's contract unchanged. Then a boot
site **cannot** omit a source, because it no longer enumerates them — making 331's "divergence impossible"
true *structurally* for the composition step, not merely diagnosed after the fact (347's divergence
*checks* are the detect-half; this is the prevent-half).

**Why this size, not smaller or larger.** The *minimal* patch (add the one `contributeYaml` line to
standalone) fixes this instance but leaves the copy-paste structure that produced it, so the next source
added to the Head re-opens the same gap. The divergence is a **demonstrated class** (standalone already
drifted; the composition is duplicated 5×), so per `structural-defects-no-repeat` the structural fix is
warranted, not speculative. Going *larger* — re-routing standalone through a written-then-read snapshot
("be your own Head"), or redesigning the ordinal chain / snapshot format — is unwarranted: 331 already
owns and built the contract; the only missing piece is collapsing the duplicated composition. So the
change is exactly "one shared composition method + call it at the boot sites," and no more.

**Not 628's to implement.** This is config-architecture (331/347) territory; 628 surfaced it via the
guard and worked around it test-locally with a production-faithful config snapshot (no product change).
Recorded here + in the observations inbox for that lineage to own.

## Reach — the principle and where else it applies

**Principle (an instance of an existing one, not a new one): "resolve once / single composition" — a
canonical value derived from sources must be composed by exactly one shared chain; any independent or
partial re-composition is a silent-divergence defect.** This is the *same shape* as 628's own read-model
principle (the index is derived from source — don't fork the derivation) and as CLAUDE.md's
**projection-vs-fork** rule (representation-drift, tempdoc 553): a forked *derivation* drifts exactly as a
forked *representation* does. Config composition forking is that class, one layer over. **Conform to it**
(331's contract + the projection-vs-fork rule) rather than inventing a parallel config path.

- **Candidate scope / existing violations:** the 5+ hand-rolled `contributeYaml` build sites — the
  worker-boot ones (Head, standalone, launcher) are the cohort that should share the composition;
  **standalone is the confirmed live violation** (omits yaml). The deep-adapter sites (`RuntimeSession`,
  `SsotCommitMetadataSource`) compose for a narrower purpose and may legitimately differ — judgment, not
  blanket unification.
- **Recognized, deliberately NOT built now:** a generalized *enforcement* (an ArchUnit/lint that fails any
  `new ResolvedConfigBuilder()` boot site not routed through the shared composition). One violation is
  found and the shared method itself removes it; a gate is extra structure for violations not yet shown to
  recur. Name it, scope it, leave it to 331's owner — separating "recognized principle" from "built
  structure" on purpose.
- **Siblings checked:** 626 (incremental indexing) / 627 (supervision) do not intersect this design — it
  is purely config-composition, owned by 331/347.

## Pre-implementation de-risking (config-composition round)

> Read-only probes before anyone implements the design above. Two probes **reshaped** it — the design as
> first written was both too broad in one place and too narrow in another.

- **P1 — per-site truth table (corrected the design).** All six `ResolvedConfigBuilder` boot sites read:
  Head = `auto+env+yaml+uiSettings`; `LauncherEnvironment` / `RuntimeSession` / `SsotCommitMetadataSource`
  = `env+yaml`; `ConfigStoreRebuilder` = `env+yaml+uiSettings`; **standalone `IndexerWorker` =
  `auto+env` (yaml MISSING)**. ⇒ the universal base is **`env+yaml`**, not the `auto+env+yaml` I assumed,
  so the shared piece is `contributeBaseSources() = env+yaml` (auto/uiSettings stay per-site); standalone
  is the **sole** yaml-omitter (unambiguous bug, not a variant); and the adapter sites are *in* the cohort,
  not "legitimately different" as the design speculated.
- **P2 — ownership (hand-off confirmed).** The cohort spans 5 modules + the Head's critical boot path —
  config-architecture (331/347), not 628. 628 only *surfaced* it.
- **P3 — feasibility (low risk).** `JustSearchConfigurationLoader.loadYamlRoot()` is already called on the
  worker classpath (`RuntimeSession:396`, `SsotCommitMetadataSource:209`) and the `justsearch.config`
  sysprop is set at boot, so a standalone `contributeYaml` is callable.
- **P4 — hidden second divergence (the averted surprise).** `contributeUiSettings`
  (`ConfigStoreRebuilder:65`) contributes **worker-relevant** keys — `index.base_path`, the ONNX / reranker
  / NER / SPLADE / citation **model paths**, `gpu.layers`, `context.size`, `ui.exclude_patterns`. So the
  standalone gap is **not just YAML** — the whole **settings.json** surface is also missing. "Just add
  `contributeYaml`" closes only the yaml half; *full* production fidelity needs settings.json, which is
  Head/UI state the worker doesn't hold → a real architectural choice (load settings.json in the worker,
  or route standalone through a self-produced snapshot). Firmly 331's call, and bigger than the design's
  first framing.
- **P5 — behavior-preserving + testable.** Ordinal-keyed resolution is call-order-independent, so the
  extraction is safe; the audit-driven guard is a `modules/configuration` unit test asserting
  standalone-style vs Head-style `ResolvedConfig` agree on the shared yaml keys — no live worker needed.

**Critical confidence — split, because the work has two parts:**
- **The yaml-half mechanics (extract `contributeBaseSources`, route standalone through it, add the
  equivalence test): 8.5 / 10.** Well-understood, feasible, behavior-preserving, testable.
- **A *complete* faithful-standalone (incl. settings.json) shipped *under 628*: 4 / 10.** P4 showed the
  real fidelity gap is larger (settings.json), the change spans 5 modules + the Head boot, and it is
  explicitly 331/347's to own — so the honest verdict is **hand off to a 331-lineage tempdoc**, not
  implement here. The de-risking's value was P4: catching that "one shared method" would have shipped a
  *still-divergent* standalone (model paths / index base path silently defaulted) had it gone straight to
  implementation.

# Structural fix — DONE (implemented + verified in worktree `worktree-628-standalone-yaml`, not merged)

Per a directive to prefer the long-term structural approach regardless of size, the shared-composition
fix was implemented (not the one-line patch). The copy-paste that *allowed* the omission is gone.

- **New shared seam:** `ResolvedConfigBuilder.contributeBaseSources()` — contributes the universal
  worker-relevant base (`contributeEnvRegistry()` + `JustSearchConfigurationLoader.loadYamlRoot()` →
  `contributeYaml`). This is the structural realization of tempdoc 331's *"resolve once; divergence
  impossible"* — a boot site routed through it **cannot** omit a base source.
- **All six boot sites routed through it**, each adding only its own extras: `HeadlessApp` (+auto +
  uiSettings), `LauncherEnvironment` (base), `ConfigStoreRebuilder` (+uiSettings), `RuntimeSession`
  (base), `SsotCommitMetadataSource` (base), and the **standalone `IndexerWorker`** (+auto) — the latter
  is where the fix lands: it no longer omits YAML, so recovery config is honored. Behavior-preserving
  (ordinal-keyed resolution is call-order-independent); the env+yaml duplication is removed from 5 sites.
- **The settings.json boundary (P4) confirmed inherent, not a gap:** `UiSettingsStore` lives in
  `app-services`, which the worker does **not** depend on — so the worker-loadable ceiling is
  `yaml+env+auto`, and `contributeBaseSources()` is the *complete* worker-side fix. settings.json keys
  (model paths etc.) remain Head-only by module boundary (acceptable: standalone is a dev escape-hatch;
  production gets them via the Head snapshot).
- **Verification (all green, surfaced in the transcript):**
  - `spotlessApply` + the six touched modules (`configuration`, `app-launcher`, `app-services`, `ui`,
    `adapters-lucene`, `indexer-worker`) compile — `BUILD SUCCESSFUL`.
  - **Regression guard:** `ResolvedConfigBuilderTest.BaseSourcesComposition` (new) — `tests="2"
    failures="0"`: `contributeBaseSources` reads `index.auto_recovery` from YAML; the pre-fix env-only
    shape defaults it to false (documents the exact bug). Full `:modules:configuration:test` green.
  - **End-to-end proof:** `CorruptionRebuildE2ETest` re-run with the config-snapshot workaround
    **removed** — `tests="1" failures="0"`. The corrupt index self-heals *because standalone now reads
    `auto_recovery` from YAML*, i.e. the fix is what carries the guard now.
- **Not user-visible** (worker config + tests) → no browser verification applies.
- **Left to the 331/347 lineage (recognized, not built):** a generalized *enforcement* gate (ArchUnit/
  lint failing any `new ResolvedConfigBuilder()` boot site that bypasses `contributeBaseSources()`). One
  violation existed and the shared seam removed it; a gate is structure for a not-yet-recurring case.
