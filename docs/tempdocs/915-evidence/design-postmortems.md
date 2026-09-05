<!-- Sidecar of docs/tempdocs/915-lane-d-index-fingerprint-identity-and-reindex-bundle.md — moved here per the tempdoc size-cap split (930 §19.3 F4, 2026-09-05). Headings below are unchanged from the main file; this directory is exempt from check-tempdoc-size.mjs. -->

### §C.8 The repeat-rebuild brake

A green that never finishes leaves the same mismatch on the next boot, so an unbounded auto-start
rebuilds forever. `IndexGenerationManager.State` gains `auto_rebuild_key` (the `index_fingerprint`
the attempt targeted), `auto_rebuild_count`, `auto_rebuild_first_ms`, persisted through the existing
atomic `writeState` (`format_version` stays 2; absent fields read as null on an older file).

- Bound: `MAX_AUTO_REBUILD_ATTEMPTS = 3` per target fingerprint. Enough to absorb a crash mid-build
  or a disk that fills and clears; few enough that an unbuildable index stops costing a full
  rebuild every boot.
- Keyed by target, so a later unrelated upgrade is not refused for an earlier one's failures.
- Recorded **before** the rebuild starts — a build that crashes the process must still spend its
  attempt, or a crash loop is invisible to the brake.
- Cleared by `promoteBuildingGenerationToActive`: a completed cutover is the proof it converged.
- On exhaustion `KnowledgeServer` logs an operator-actionable error, opens the active generation
  **read-only**, and **finishes starting**.

**Corrected twice, and the second correction is the interesting one.** The first cut *rethrew*, which
is the dead-end `FAIL_CLOSED` produced, arriving three boots later. The second cut stopped rethrowing
but `return`-ed out of `start()` — which skipped `createGrpcServer`/`grpcServer.start()`,
`signalBus.writePort`, `appServices.startIndexingLoop()`, `startSentinelThread()`, and the whole
`infraCtx`/`appServices` construction that builds `GrpcIngestService` and with it `IndexStatusOps`.
The Worker exited 0 with no port and no fatal-reason marker, so *both* things this design promises —
"Blue keeps serving" and "the status surface says why" — were unreachable, while every unit test and
the reason-code gate stayed green. `start()` now sets a state and falls through:

| Step | With the brake exhausted |
|---|---|
| runtimes | active generation opens read-only; ingest == search; no Green |
| switch-buffer drain | skipped (no writer to drain into) |
| gRPC create + start, `writePort` | run — the Worker binds and is discoverable |
| indexing loop | not started, logged at ERROR (its only job is to write into a read-only runtime) |
| sentinel | runs |

Recovery is `core.rebuild-index` → `startMigration(user_requested_rebuild)` → cutover →
`promoteBuildingGenerationToActive`, which clears `auto_rebuild_*`. The exhaustion log line names it.

This is the `audit-without-test` case in its purest form: the constant existed, the mapping existed,
the gate counted it — and nothing could emit it. Only `BrakeExhaustedWorkerServesReadOnlyTest`, which
boots a real `KnowledgeServer` and reads the answer off the wire, could tell. It then immediately
found a *second* defect the static reasoning had missed (§D.24).

### §C.13 A mismatch from the deferred writer upgrade is not "non-fatal"

The second half of O7. `DeferredRuntime.upgradeWriter()` runs inside `initDeferredModels()`, whose
`catch (Exception e)` logged everything as `"Background model initialization failed (non-fatal)"`.
A `SCHEMA_MISMATCH` there is not a degraded capability — it is a stopped ingestion pipeline, because
the index cannot accept writes under this runtime's shape.

It is now **reported loudly rather than propagated**, and the choice is deliberate: this runs on a
background `CompletableFuture` with no caller left to receive an exception, `start()` having long
returned. Propagation would mean inventing a channel (a flag polled by the sentinel, a callback into
the boot handler) whose only job is to carry a condition the next boot's pre-open detection handles
correctly anyway — and the status surface already reports `BLOCKED_MISMATCH` with `reindex_required`
from the same fingerprint comparison, so the user is not waiting on a restart to be told. What was
missing was never the propagation; it was that the line said "non-fatal".

### §C.14 What moving the decision earlier broke

Two blockers, both from the same root: a check that used to run *inside* the open now runs *before*
it, so it lost the envelope the open provided and gained a decision the open never had to make.

**B4 — an unreadable commit is not a mismatch.** `inspectCommittedParity` raised `CORRUPT_INDEX`
when the directory could not be read. Inside the open that was harmless — `RuntimeSession`
caught it two frames up and ran backup-then-empty recovery, which ships enabled
(`index.auto_recovery: true`). At the new call site there is nothing above it but `start()`, so a
corrupt index that used to self-heal at boot killed the Worker instead. And because the cause of an
unreadable commit is an `IndexFormatTooOldException`, the same throw swallowed the legitimate
older-Lucene-major upgrade path. The method now answers one of *three* things, not two: match,
mismatch, or **could not read** — the third is a WARN naming the cause class and an empty diff list,
and the open decides (recovery, format upgrade, or the second-line guard). Every one of
`CorruptIndexException`, `IndexFormatTooOld/TooNew` and `IndexNotFoundException` is an
`IOException`, so one catch covers them, and `ComponentsFactory` classifies corruption on the
reader/writer open independently — nothing is lost by declining here.

**B5 — a mismatched Green must be abandoned, not retried.** Round 3 hoisted the schema-mismatch
`try` over the resumed-migration branch, which was necessary and not sufficient.
`IndexGenerationManager.startMigration` deliberately no-ops while a migration is in flight, so the
handler re-resolved the *same* Green and re-opened it with the builder that had just thrown: the
second `SCHEMA_MISMATCH` was raised inside the catch, uncaught, and `start()` died — on attempts 1,
2 and 3, i.e. three dead Workers before the brake that exists to bound exactly this repetition could
report anything. `abandonBuildingGeneration(reason)` is the missing move: clear
`building_generation`, return `migration_state` to `IDLE`, mark the directory for deletion, carry
the auto-rebuild budget over unchanged (abandoning a Green is not evidence the rebuild converged, so
it must not refresh the brake). One attempt is then spent and a fresh Green allocated — or, if the
budget is gone, the read-only Blue fall-through runs as before. `start()` returns in every case.

**Ride-along.** The catch opened a second read-only runtime on Blue when the resumed branch had
already opened one, overwriting the field without closing it — a leaked `Directory` +
`SearcherManager` holding Windows handles on Blue for the Worker's whole lifetime, in the
brake-exhausted state, which is the state that keeps running. Blue is held in a local and reused.

**Unknown policy values.** `normalizeSchemaMismatchPolicy` returned an unrecognised value verbatim,
which used to be merely inert. After §C.12 it was not: pre-open detection forces a writable open for
any policy it does not recognise, the guard raises, recovery refuses the destructive rebuild and the
Worker fails to start. A typo in one config key must not be a boot failure — it falls back to the
mode default with a WARN, in the config layer, so every consumer sees the same answer.

**Empty-index symmetry.** The `docCount` exclusion was applied only where the *stored* fingerprint
was blank, so a 0-document index with a *stale* one still took the changed branch and would have
spent a full blue/green migration rebuilding nothing. Both branches consult
`ParityDiagnostics.holdsNothingToMigrate(docCount)` now, and `IndexStatusOps` reports through it, so
the guard and the status surface cannot disagree about an empty index in one direction after
agreeing in the other. What actually happens to such an index: `CommitOps.setLiveCommitData`
replaces the whole user-data map, so the next commit **re-stamps** it.

### §C.16 The status surface describes the generation being SEARCHED

Live validation (2026-09-03) found three defects on one wire, and one cause behind all of them:
`IndexStatusOps` was fed entirely from the **ingest** runtime, which is the wrong authority for
every question the compatibility surface answers.

| Field | What it said | What it means |
|---|---|---|
| `indexSchemaFpStored` / `indexSchemaCompatState` / `reindexRequired` | mid-migration: Green's fingerprint, hence `COMPATIBLE` / `false` | the shape of the index the user's queries reach — that is Blue |
| the same three, braked | `""`, and `""` routes to `BLOCKED_LEGACY` if the brake check ever stops shadowing it | there is no ingest runtime at all in that state, so the supplier was null |
| `indexedDocuments` | `jobQueue.completedCount()` — DONE rows in `jobs.db`, pruned, unrelated to corpus size (observed: 5, against 205 searchable) | documents in the generation being written, or, when none is being written, the one being served |

The fix is the wiring, not a special case. `storedVectorFormat`, `openTimeCommitUserData` and
`latestCommitUserDataBestEffort` come from `searchLifecycle`, which is non-null in every state
including braked and deferred; `docCount` falls back to the serving reader before it falls back to a
queue counter. The **mid-migration contract the reviewer asked to be pinned then falls out of the
ordinary comparison**: Blue carries the old shape, so `BLOCKED_MISMATCH` + `schema_mismatch` +
`reindexRequired` hold for as long as the rebuild runs, next to `migration.state = MIGRATING`. There
is no `MIGRATING` value in the compat vocabulary and adding one would be worse than useless —
`WorkerSnapshotTap`'s `SCHEMA_COMPAT_TABLE` maps exactly `BLOCKED_LEGACY` and `BLOCKED_MISMATCH`, and
an unmapped value takes the preserve-prior + WARN-once branch, which FREEZES a stale condition
(tempdoc 726 F5's documented failure mode). `docCount` still reports Green while Green exists: that
is the build's progress, and it is the one number that legitimately describes the generation being
written.

This also settles §C.13's argument for reporting rather than propagating a deferred-upgrade mismatch.
That argument rested on "the status surface already reports `BLOCKED_MISMATCH` with
`reindex_required`", which was true only while no migration was in flight. It is true in every state
now — but it was the claim that was wrong, not the sentence, and the comparison is what was fixed.

### §C.17 A refusal is not a crash

`FAIL_CLOSED` did its job live — the index was left byte-identical — and the user was told the Worker
had crashed. `WorkerFatalReasonMarker` existed for exactly this and carried one value,
`index_corrupt`, written only when `isCorruptIndexCause(e)`; a `SCHEMA_MISMATCH` refusal fell through
to a bare `System.exit(1)`, so the Head reported `Worker process crashed (exit code 1) before writing
port to signal file` with `/api/health` unreachable and the real cause visible only in `worker.log`.

`index_schema_mismatch` joins the vocabulary, written from the same `catch` via the classifier §C.13
already added, read by the same `KnowledgeServerBootstrap.workerDownCode` funnel, and worded as
`worker.index_schema_mismatch`. A separate code rather than reusing `worker.index_corrupt` because
the remedy differs: the index is intact and the wrong shape, so the answer is a policy that permits a
rebuild, not a corruption repair.

### §C.18 What a self-restart takes with it

The cutover restart is the only shutdown the Worker performs on its own, and it is not a `stop()`:
the monitor calls `initiateShutdown()`, which shuts the gRPC server and lets `main` unwind. Two facts
were lost in that window, both because they were left to a later step:

- the **clean-shutdown marker**, so every boot after a cutover logged `Unclean previous shutdown
  detected` for the freshly promoted generation and paid a FULL integrity verification for an index
  that had just been committed and verified;
- the **metrics snapshot**, whose cadence is 60 s against a restart ~20 s after the migration starts,
  which is why `commit_by_reason` never carried `migration/cutover` in a live run despite both emit
  sites being production code (D4).

`preserveEvidenceBeforeRestart` writes both at the point they are true — immediately after the
promotion — instead of hoping the shutdown sequence completes first. Best-effort and independent: a
failure costs an integrity scan or a counter, never a cutover.
