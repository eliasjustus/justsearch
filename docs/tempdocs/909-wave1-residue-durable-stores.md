---
title: "Wave-1 residue R4: the eight parked durable write sites become READY register rows — and each one's real product work"
type: tempdocs
status: "IN PROGRESS (2026-09-02) — lane R4 of the wave-1 residue closure"
created: 2026-09-02
updated: 2026-09-02
lane: wave-1 residue R4 (pendingDurableClassification drain)
model: opus (implementation)
parent: 885-decision-review-lane-c-runtime-lifecycle-and-isolation
related:
  - 617-in-place-app-update-mechanism        # the durableStores register itself
  - 879-operation-policy-enforcement         # pathVerification + encryption disposition fields
  - 629-data-at-rest-encryption              # StoreCatalog cipher, faithful backup import
  - 875-agent-file-operations-hardening      # COPY-undo containment + nested-edit detection (§C.6)
  - 577-agent-loop-honesty                   # §2.14 Root III, the mtime conflict baseline
  - 824-ai-install-repair-convergence        # InstallAttemptMemory (§3.4)
  - 585-search-your-own-agent-history        # §D Phase 4, the agent-history transcripts
---

# 909 — Wave-1 residue R4: draining `pendingDurableClassification`

`governance/store-recoverability.v1.json` parks eight durable write sites in
`pendingDurableClassification`, each with a blocker that names real missing product work — not
missing documentation. This lane does the work, then promotes each site to a READY `durableStores`
row and empties the parked list (keeping the block and its `cap: 8` so the ratchet survives).

The register is a **wire contract**: `modules/shell/src-tauri/src/updater.rs` embeds it with
`include_str!` (`:31-32`) and `UpgradeReconciliationProbe.loadOwnerRegister`
(`modules/ui/.../UpgradeReconciliationProbe.java:196-225`) returns an EMPTY owner register — and
`/api/upgrade/reconcile` answers 409 — if **any** row is not READY. So every row added here is
READY or it is not added.

## §A Scope

| # | Parked site | Product work this lane does |
|---|---|---|
| 1 | `AgentHistoryIndexer` | boot + unlock re-derivation of missing/unreadable transcripts from `agent-runs` |
| 2 | `InstallAttemptMemory` | atomic write (temp + rename) via the existing `AtomicFileWrites` helper |
| 3 | `GplEvalSnapshot` | stated unparseable→absent→re-run policy, WARN **once** per file instead of per read |
| 4/5 | `GplStage3aAnalysisReport`, `GplStage3bBranchFusionReport` | a **test** that proves "nothing reads it back", instead of assuming it |
| 6 | `LlamaServerOps` | bounded retention for `logs/llama-server.log` (rotate on start, 3 generations) |
| 7/8 | `FileOperationExecutor`, `FileOperationsTool` | COPY-undo deletes only a byte-identical copy; legacy journal rows preserve |

## §B Pre-implementation pass — every `file:line` claim in the eight blockers, verified verbatim

Method: each claim below was read at the cited line in the worktree
(`.claude/worktrees/resid2-stores`, base `bff70561`). Corrections are recorded, not silently fixed.

### B.1 `AgentHistoryIndexer.java`

- **Claim** "written temp + ATOMIC_MOVE (:184-188)" — **CONFIRMED in substance, line range narrow.**
  `atomicWrite` is `:182-190`; `Files.writeString(tmp…)` is `:184`, the `ATOMIC_MOVE` is `:186`, the
  `AtomicMoveNotSupportedException` fallback `:187-189`. The cited range omits the fallback.
- **Claim** "re-derivation from agent-runs happens only on backup import (:104-115)" — **CONFIRMED;
  the method is `:105-115`** (`:96-104` is its javadoc). The only caller is
  `HeadAssembly.java:480`, inside the faithful-import block (`:455-486`). There is no boot caller.
- **Claim** "No read path states what an unparseable transcript does" — **CONFIRMED, and sharper
  than stated.** Nothing in `modules/` reads `agent-history/*.md` back at all; the only consumer is
  the Worker's ingest of the file path (`submitBatch(List.of(target), true, COLLECTION)`, `:124`).
  So the failure this row has to answer for is not "unparseable" in a JSON sense — it is *a
  transcript that is missing, empty or not the markdown this class writes*, which is silently
  un-searchable forever.
- **New fact (not in the blocker):** `historyDir` is `dataDir.resolve("agent-history")`
  (`HeadAssembly.java:626`) — a top-level data-dir directory, NOT inside an AUTHORED StoreCatalog
  directory, so `checkEncryptionDisposition`'s "lives entirely inside an AUTHORED catalog dir" arm
  does not force `SEALED_BY_STORE_CIPHER`.
- **Encryption disposition (the blocker's second half).** The comparable rows split two ways:
  `conversations` / `agent-runs` are `SEALED_BY_STORE_CIPHER` (StoreCatalog AUTHORED), while
  `gpl-training-triples` — user-derived text that is *regenerable from the source documents* —
  is `UNSEALED_DERIVED_OS_DISK_ENCRYPTION`. A transcript is the second kind: it is a projection of
  the sealed `agent-runs` terminal event, and it must stay readable as an ordinary file because the
  Worker process ingests it by path. `UNSEALED_DERIVED_OS_DISK_ENCRYPTION` + an `encryptionNote`
  naming the content is the honest disposition; sealing it would break the ingest that is its only
  purpose.

### B.2 `InstallAttemptMemory.java`

- **Claim** "JSON {version, files}, VERSION = 1 (:64), refused on mismatch (:81)" — **CONFIRMED.**
  `private static final int VERSION = 1;` at `:64`; `p.version() == VERSION` at `:81`. Note the
  guard is an equality test, so a *future* version yields an empty memory (it is not a loud refusal).
- **Claim** "The write is createDirectories + writeString with no temp-and-rename (:167-168)" —
  **CONFIRMED verbatim.** `Files.createDirectories(file.getParent())` `:167`,
  `Files.writeString(file, JSON.writeValueAsString(...))` `:168`.
- **Claim** "losing it re-offers a Repair that will not converge" — **CONFIRMED** via
  `isTerminal` (`:129-132`) reading `failedPasses >= MAX_FAILED_PASSES` (`:50`): a torn file loads
  as empty (`:85-89`), so `failedPasses` restarts at 0 and the terminal verdict never fires.
- **Existing helper to reuse:** `io.justsearch.configuration.persistence.AtomicFileWrites`
  (`replaceUtf8`, `:23-25`) already does sibling-temp + `ATOMIC_MOVE` + fallback + temp cleanup, and
  is already the atomicity source named by six register rows. `app-services` already depends on
  `modules:configuration`. **Do not write a second atomic-write helper.**

### B.3 `GplEvalSnapshot.java`

- **Claim** "temp + ATOMIC_MOVE (:81-90)" — **CONFIRMED** (`save` is `:81-95`; the move is `:87-91`).
- **Claim** "read at boot by GplOrchestration.java:88" — **PATH WRONG, line right.** The file is
  `modules/app-services/src/main/java/io/justsearch/app/services/bootstrap/phases/GplOrchestration.java`
  (the blocker omits `bootstrap/phases/`), and `GplEvalSnapshot.load(snapshotFile)` is at `:88`
  there. The snapshot path is `dataDir.resolve("gpl-eval-snapshot.json")` (`GplOrchestration:172`).
- **Claim** "No read path states what an unparseable snapshot does" — **PARTLY WRONG.** `load`
  already catches, logs WARN and returns null (`:71-77`), and `GplRevalidationTrigger` treats a null
  `lastEval` as "re-run". What is missing is (a) the register statement and a test that pins it, and
  (b) **boundedness of the WARN**: `HeadAssembly.headInfraRegistry()` publishes
  `() -> GplEvalSnapshot.load(gplSnapshotFile)` as a `Supplier<GplEvalData>` (`:1400-1401`), so a
  corrupt file WARNs on every call, not once. That is the real defect behind the blocker's words.

### B.4/B.5 `GplStage3aAnalysisReport.java` / `GplStage3bBranchFusionReport.java`

- **Claim** "createDirectories + writeValue, no temp-and-rename (:157-159)" — **CONFIRMED
  verbatim** for 3a (`write` `:156-160`), and for 3b at **`:210-214`** (blocker says `:211-213`,
  which omits the method signature line; the two write calls are `:212` and `:213`).
- **Claim** "Nothing reads it back" — **CONFIRMED by search, which is exactly why it needs a test.**
  Production references are only `GplJobCoordinator.java:738` and `:747`, both `…Report.write(...)`
  followed by a `log.info` of the path (`:736-752`). Every other reference is a test.
- **Path:** `reportPathFor` is `tripleStoreFile.resolveSibling("gpl-stage3a-analysis.json")`
  (`3a:89-91`) / `"gpl-stage3b-branch-fusion.json"` (`3b:130-132`), and the triple store is
  `<dataDir>/gpl-training-triples.ndjson` (the `gpl-training-triples` row), so both reports are
  **top-level DATA_DIR** files.
- **Content (for the encryption disposition):** both `Report` records hold only counts, rates and a
  `generatedAt` string (`3a:39-46`, `3b:70-76`); `3b`'s `SweepSummary`/`SelectedConfig` are numeric
  sweep parameters plus a `selectionReason` label (`3b:50-68`). No query text, no document path.

### B.6 `LlamaServerOps.java`

- **Claim** "appended across restarts by ProcessBuilder.Redirect.appendTo (:939-940)" —
  **CONFIRMED in substance, lines off by three.** The two `appendTo` calls are `:942-943` inside
  `configureServerLogRedirection` (`:935-945`).
- **Claim** "No rotation or retention policy is stated" — **CONFIRMED.** The file grows without
  bound across every launch; nothing prunes it. `lastLaunchLogOffset` (`:941`) only bounds *reading*.
- **Claim** "contents are verbosity-dependent and can include prompt-shaped diagnostics" —
  **CONFIRMED as a real risk**: stdout+stderr of `llama-server` are redirected wholesale, and the
  app reads them back for diagnostics (`readLaunchOutputBestEffort` `:767-789`,
  `DIAG_TAIL_BYTES` tail at `:1354`). Verbosity is the server's, not ours, so the honest disposition
  is `UNSEALED_DERIVED_OS_DISK_ENCRYPTION` with a note that says so — not `NOT_APPLICABLE`.
- **Existing pattern to mirror:** `WorkerSpawner.java:378-395` already rotates `worker.log` →
  `.1` → `.2` on each boot ("mirrors the alpha.12 lib.rs rotation policy"). Reusing the same shape
  keeps one retention story for the app's process logs.

### B.7/B.8 `FileOperationExecutor.java` / `FileOperationsTool.java`

- **Claim** "moved, copied and deleted in place (:274-285)" — **CONFIRMED in substance, range too
  narrow.** `executeOperation` is `:257-304`: `Files.move(…, ATOMIC_MOVE)` `:274`, the
  copy+delete fallback `:280-286`, `MKDIR` `:293`, and the **COPY arm `:295-302`** which the cited
  range excludes entirely.
- **Claim** "a COPY-undo deletes a real user file (FileOperationsTool.java:277-289)" —
  **CONFIRMED, and the guard that exists is weaker than it looks.** The COPY arm of undo is
  `:274-295`; `Files.delete(action.path)` is `:289` and `executor.deleteDirectory(action.path)` is
  `:286`. Two guards already run: containment in the *current* indexed roots (`:280-284`, tempdoc
  875 §C.6) and `modifiedSince` (`:229-233`, tempdoc 577 §2.14). `modifiedSince` is **mtime-only**
  (`:352-368`, tolerance from `AgentTimeouts.fileOpConflictToleranceMs()`), so a same-mtime
  replacement, an mtime-preserving editor, or a coarse filesystem timestamp all read as "untouched"
  and the undo deletes the user's file. Content identity is never checked.
- **Claim** "the register names the undo journal but not the authority that MUTATES the documents" —
  **CONFIRMED.** `file-operation-journal` owns `file-operations/*.json` only.
- **What the journal records today:** `startBatch` writes `op`/`source`/`destination`
  (`FileOperationLog.java:56-65`); `recordSuccess` writes `index`/`status`/`timestamp`
  (`:70-82`); `recordRename` adds `originalDestination`/`resolvedDestination` (`:114-128`).
  **There is no digest of anything.** `CURRENT_SCHEMA_VERSION = 1` (`:33`) and
  `requireReadableVersion` refuses only `version > CURRENT` (`:233-240`).
- **Root enum gap (new fact):** the gate's `ROOTS` set is
  `{DATA_DIR, AI_HOME, PROGRAM_DATA_OR_DATA_DIR}` (`check-store-recoverability.mjs:41`). The user's
  own documents live under none of them, so this row cannot be written without one new enumerated
  root. That is the minimum gate edit a new row strictly needs.

### B.9 Consumers a new row must not break (checked before writing any row)

- `updater.rs:874` — `release_owners.len() != local.durable_stores.len()` ⇒ every release descriptor
  must declare compatibility for **every** row. Descriptors are *generated from this register*
  (`scripts/release/app-release-assets.mjs:77-100`) and the Rust test derives its fixture from the
  embedded register (`updater.rs:2089-2092`), so adding rows is safe on both sides.
- **The register has a THIRD runtime consumer, which its own note did not name** (found in review):
  `app-release-assets.mjs:78-80` throws `durable store <id> is not READY` while generating the
  release descriptor, so a non-READY row does not merely fail a gate — it makes the release assets
  **unbuildable**. Its test is `app-release-assets.test.mjs:120`. The register note now names it
  alongside `updater.rs` and `UpgradeReconciliationProbe`.
- `scripts/dev/dev-runner.cjs:299-318` — the soft-clean keep-set takes the **first path segment of
  every AUTHORED row's `ownedPaths`**, skipping segments containing `*`. The one AUTHORED row added
  here (the user's documents) declares `**`, whose first segment is a glob, so the keep-set is
  unchanged. `scripts/dev/test-dev-runner-soft-clean-keep-set.mjs` derives its expectation from the
  register, so it does not pin a count.
- `modules/ui/.../UpgradeLifecycleContractTest.java` asserts `owners.size() > 0`, not a count.

## §C Post-implementation critical pass

### C.1 Wrong-gate / wrong-flag — does the guard fire in the target scenario?

- **The llama-server rotation is on the LAUNCH path, not beside it.** The rotation could easily have
  been a helper nothing calls. `configureServerLogRedirection` was made package-private and given the
  resolved path so the test drives the same method `startLlamaServer` calls
  (`LlamaServerOps.java:903`); commenting out the one `rotateServerLogGenerations(logFile)` line made
  **both** retention tests fail (§C.3), which is the proof the wiring is real.
- **`lastLaunchLogOffset` is read AFTER rotation, deliberately.** Rotation moves the live log aside,
  so `Files.exists(logFile)` is false and the offset is 0 — which is correct (this launch starts at
  the beginning of a fresh file) and is what `readLaunchOutputBestEffort` (`:767-789`) needs. Read
  before rotation it would have been the OLD file's size, and every launch-argument rejection would
  have been read from past the end of the new file: silence instead of a diagnosis.
- **The COPY digest is recorded for the RESOLVED destination.** Under `AUTO_SUFFIX`, `op` is
  re-bound to a new `FileOperation` with the suffixed destination before `executeOperation`
  (`FileOperationExecutor.java:213-218`), and the digest is computed from `op.destination()` after
  that — so a renamed copy records the file that actually exists, not the name that was asked for.
- **Only COPY records a digest, and only COPY consults one.** MOVE/RENAME-undo relocates the file
  (losing nothing) and MKDIR-undo deletes only an empty directory, so neither needs identity. Both
  facts are stated at the `recordSuccess` javadoc so a future op type does not inherit the silence.
- **Guard ORDER in undo:** containment → legacy-journal → content-mismatch → delete. The pre-existing
  mtime conflict check still runs earlier (`:229-233`) and still short-circuits with "changed since",
  which is why the two 875/577 tests keep passing unchanged.

### C.2 Audit conclusions independently verified

Every `file:line` in the eight blockers was re-read at source (§B); four were wrong or narrow and are
corrected there — most consequentially `GplOrchestration.java`'s path (the blocker omits
`bootstrap/phases/`) and the claim that "no read path states what an unparseable snapshot does",
which was already implemented. The real defect behind that blocker turned out to be different and
worse (an unbounded WARN on a per-request supplier), which a "write the policy down" reading would
have missed entirely.

### C.3 Test precision — every test was falsified once, and failed for the stated reason

| Test | Break applied to production | Result |
|---|---|---|
| `reconcileRebuildsAMissingTranscript`, `reconcileReplacesATornTranscript` | `isReadableTranscript` → always true | both FAILED (0 rebuilt) |
| `reconcilePreservesWhatItCannotRebuild` | delete the file when it cannot be rebuilt | FAILED (file gone) |
| `reconcileLeavesAHealthyTranscriptAlone` | `isReadableTranscript` → always false | FAILED (loaded events for a healthy transcript) |
| `tornMemoryReadsAsNoHistory…` (+ the pre-existing corrupt test) | `load`'s catch rethrows instead of degrading | both FAILED |
| `tornSnapshotMakesTheEvaluationReRun` (+ 2 pre-existing) | unparseable load returns a zeroed snapshot | FAILED |
| `unreadableSnapshotWarnsOnce` | WARN unconditionally (pre-909 behaviour) | FAILED (5 ≠ 1) |
| `noProductionCodeReadsTheStageReports` | add `GplStage3aAnalysisReport.reportPathFor` to `GplJobCoordinator` | FAILED |
| `tornStage3aReportIsReplaced…`, `…3b…` | `write()` returns early if the report exists | both FAILED |
| `launchRotatesAndBoundsGenerations`, `launchRedirectsToAFreshLiveLog` | comment out the rotation call | both FAILED |
| `undoRefusesToDeleteACopyWhoseContentChangedUnderAnUnchangedMtime`, `…CopiedTree…` | disable the digest-mismatch branch | both FAILED (file deleted) |
| `undoPreservesACopyRecordedByALegacyJournal…` | disable the missing-digest branch | FAILED (file deleted) |
| `legacyV1JournalRemainsReadable` | `requireReadableVersion` refuses `version != CURRENT` | FAILED |
| `rotationFailureLeavesOlderGenerationsIntact` (review S2-2) | restore the prune-then-move ordering | FAILED — `NoSuchFileException: …\logs\llama-server.log.1`, i.e. the retained set had already shrunk before the live move failed |
| `oversizedFileIsNotDigested`, `oversizedTreeIsNotDigested` (review S2-4) | `of(Path,long)` ignores `maxBytes` | both FAILED |
| `absentRecordNeverMatches` (review S2-4) | `matches` treats a blank record as a pass | FAILED |
| `fileDigestTracksContentNotMtime` (review S2-4) | `of` returns a size+mtime identity instead of a content hash | FAILED |
| `noProductionCodeReadsTheStageReports`, bind-then-read clause (review S2-6) | add `Files.readString(reportPath)` beside the coordinator's existing binding | FAILED |

Two tests are deliberately NOT guard tests and would still pass with the guard removed, which is
their point: `undoStillDeletesACopyWhoseContentIsUnchanged` (the guard must not over-fire) and the
pre-existing `undoCopyDeletesCopiedFile`. Both are recorded here so a reader does not mistake them
for evidence of the guard.

One honest limit: the **atomicity** of `InstallAttemptMemory.save` is not falsifiable from a
single-threaded test — a truncating write and an atomic replace are indistinguishable unless the
write is interrupted. The property is held by `AtomicFileWritesTest.writeFailurePreservesOriginalAndDeletesTemp`
in the shared helper (which is why the register row lists both the helper and its test), and the new
test covers the half that IS observable: what a torn file does when read.

### C.4 Scope checks that were run before writing a row

- `updater.rs:874` requires the release compatibility table to be a CLOSED set over `durableStores`
  — descriptors are generated from this register (`app-release-assets.mjs:77-100`) and the Rust test
  derives its fixture from the embedded register (`updater.rs:2089-2092`), so six new rows are safe.
- `dev-runner.cjs:299-318` keeps the first path segment of every AUTHORED row. The one AUTHORED row
  added here declares `**`, whose first segment is a glob and is skipped — the soft-clean keep-set is
  byte-identical, and `test-dev-runner-soft-clean-keep-set.mjs` still passes.
- ADR-0030 governs `agent/tools/` (consult-register region `workflow-agent-tool`) because operation
  metadata is ENFORCED policy rather than an MCP-style hint. This change does not touch an
  enforcement axis — it strengthens what one handler's `undo` will do — so the ADR's argument is
  unaffected and needs no edit.

## §D Report-back

| # | Site | Product work | Evidence |
|---|---|---|---|
| 1 | `AgentHistoryIndexer` | `reconcile`/`reconcileNow` re-derive a missing/torn transcript from the run's terminal event and re-index it; unrebuildable files are PRESERVED. Wired at boot **and** on key unlock. | `AgentHistoryIndexer.java:130-247`, `HeadAssembly.java:635-657`; row `agent-history-transcripts` |
| 2 | `InstallAttemptMemory` | `save()` goes through the existing `AtomicFileWrites.replaceUtf8` — no new helper. | `InstallAttemptMemory.java:166-181`; row `ai-install-attempt-memory` |
| 3 | `GplEvalSnapshot` | Policy stated + WARN bounded to once per file (it is a per-request supplier). | `GplEvalSnapshot.java:63-105`; row `gpl-eval-snapshot` |
| 4/5 | `GplStage3aAnalysisReport`, `GplStage3bBranchFusionReport` | The write-only claim is now a TEST that scans every production source for a reader, plus torn-file rewrite tests. One row covers both. | `GplStageReportWriteOnlyTest.java`; row `gpl-stage-analysis-reports` |
| 6 | `LlamaServerOps` | Rotate-on-launch, 3 generations, `RETAINED_LOG_GENERATIONS` constant; the live file is renamed aside BEFORE anything is pruned (review S2-2), so a rename blocked by an open handle leaves the retained set intact; encryption disposition stated as user-derived-in-the-clear rather than NOT_APPLICABLE. | `LlamaServerOps.java:958-1001`; row `llama-server-log` |
| 7/8 | `FileOperationExecutor`, `FileOperationsTool` | COPY records a content digest (`FileContentDigest`, capped at 2 GiB); COPY-undo deletes only a byte-identical destination, and refuses+reports otherwise (changed, unverifiable-legacy, or over the cap). Journal v1→v2, v0/v1 still readable. | `FileContentDigest.java`, `FileOperationExecutor.java:228-240`, `FileOperationsTool.java:293-325`, `FileOperationLog.java:33-40`; row `user-documents-under-agent-file-operations` + updated `file-operation-journal` |

`pendingDurableClassification.entries` is now `[]` with `cap: 8` retained.

### corruptionPolicy values used

Reused: `DISCARD_UNREADABLE_DERIVED_STATUS_AND_RESCAN` (×2), `RECREATE_DERIVED_METRIC`,
`ROTATE_OR_PRUNE_DIAGNOSTIC_ARTIFACT`, `VERIFY_HASH_OR_PRESERVE_USER_ASSET`.
Coined (1): `REGENERATE_FROM_RUN_EVENTS_OR_PRESERVE` — the nearest existing value,
`REGENERATE_OR_DROP_DERIVED_HISTORY`, prescribes dropping what cannot be rebuilt, and on a locked
encrypted install "cannot be rebuilt" is indistinguishable from "the run is gone", so dropping would
delete the user's entire agent history on the first locked boot.

One new **root** value, `USER_INDEXED_ROOTS`, was added to the gate's closed `ROOTS` set: the user's
own documents are under none of the three app-owned roots, and without it the mutation authority
could not be written down at all.

## §E Open items

1. **A transcript written while the Worker is down is never indexed.** `writeAndIndex` skips
   `submitBatch` when the knowledge client is null (`AgentHistoryIndexer.java:265-272`, the guard at
   `:271`), on the LIVE path
   as well as the reconciliation path, and the next reconciliation sees a healthy file and skips it.
   This is pre-existing and wider than item 1 (any run finishing while the Worker is down produces an
   un-indexed transcript, permanently); item 1 recovers the transcript FILE, which is the durable
   artifact. Closing it needs an index-side reconciliation ("which transcripts does the
   `agent-history` collection not contain?"), which is Worker-side work this lane does not own.
2. **An interrupted (non-terminal) run's events are re-read on every pass.** It has no transcript and
   no terminal event, so it is skipped — but only after `readEvents`. Bounded by run count and off the
   boot thread; a `state`-aware pre-filter on the session summary would remove it.
3. **The reconciliation shares the indexer's single daemon thread**, so a large first-boot backfill
   (up to `MAX_REBUILDS_PER_PASS = 200` ingest RPCs) can delay a live transcript write behind it.
   Delayed, never lost.
4. **A reconciliation pass costs a few filesystem operations PER PERSISTED RUN, on every boot and
   every unlock** (review S2-3). `MAX_REBUILDS_PER_PASS` bounds the rebuilds, not the scan:
   `AgentHistoryIndexer.java:175` stats every session the caller supplies, and
   `HeadAssembly.java:649` asks `listSessions(100_000)`, which reads and sorts every run's meta
   (`AgentRunStore.java:368-397`). Off the boot thread (verified) and small at realistic run counts,
   but per-run rather than constant. Mitigation, not taken here because it adds durable state to a
   lane that is closing one: either a per-pass **scan** cap (stat at most N sessions, newest first,
   and resume next pass) or a **"last reconciled" marker** so a pass only looks at runs newer than
   the last complete one. The constant's javadoc now states the real cost instead of implying the
   cap covers it.
5. **The COPY digest is a synchronous full extra read** (review S2-4), on the tool-call thread and
   again at undo — milliseconds for documents, seconds for very large files or trees, and every file
   of a copied directory. A 2 GiB cap (`FileContentDigest.MAX_DIGEST_BYTES`) is implemented and
   tested: above it no digest is recorded and the undo preserves rather than deletes. What is NOT
   done is moving the hash off the tool-call thread (it would have to complete before the journal is
   finalized, so it is a real ordering constraint, not a scheduling one).
6. **The verify→delete window is not atomic** (review S2-5): `FileOperationsTool.java:312` reads the
   digest, `:329` (tree) and `:333` (file) delete. A write landing in between is deleted unverified. Accepted, with the
   reason recorded at the site and in the row: closing it needs an exclusive lock on a file the USER
   owns held across both operations. It narrows an unbounded exposure to a microsecond one; it is
   not a guarantee.

## §F Behaviour change for existing installs

**A COPY-undo against a journal written by an earlier version now PRESERVES the copy instead of
deleting it.** A pre-v2 journal records no content identity, so the undo cannot prove the file is
still the agent's copy, and the conservative branch is the default one. The user is told by name
which files were left behind and why ("could not be verified against the operation log — not
deleted"). This is a deliberate, user-visible change of an existing behaviour, not only a new
guard: it makes an undo do LESS than it did before, on exactly the batches whose safety cannot be
established. New copies (schema v2) undo exactly as they did, verified. Copies above
`MAX_DIGEST_BYTES` take the same preserve branch for the same reason.

## Live product validation (2026-09-02)

Two independent reviewers ran a live product-validation campaign against this lane's items:

- **V4** — COPY-undo, all three cases PASS: verified copy deleted; unverifiable/pre-v2-journal
  copy preserved with the user told by name; oversized copy takes the same preserve branch.
- **V5** — rotation PASS.
- **V6** — transcript rebuild PASS after a restart.
- **V7** — the reconcile 409 is DEV-ONLY: `runningVersion` is set only by the Tauri shell, so a
  dev-runner backend never sets it and the 409 cannot occur in a shipped install.

Related PRs: #616, #617.
