---
title: "588 — Worker indexing-engine silent-failure robustness: the indexing loop can die without anyone noticing. A discovery-driven slice (not a pre-existing tempdoc) that traces, verifies, and fixes two ways the Worker's indexing engine fails *silently* — an uncaught `Error` kills the loop thread while `loopState()` keeps reporting `RUNNING`, and the extraction sandbox leaks a child process + two reader threads when the request-write fails. Charter: every finding is verified against source before it becomes work; one plausible static-audit hypothesis (drain-the-outcome-journal-on-commit-failure) was investigated and REFUTED as correct-by-design, and is recorded here as a dead end so it isn't re-walked."
status: implemented + verified (2026-06-15) — Fix #1 (loop survivability + honest liveness) and Fix #3 (sandbox process leak) landed in `worktree-588-engine-silent-failures` with regression tests (`IndexingLoopErrorResilienceTest` 2/2); `:modules:worker-services:test` green, `build -x test` + `verifyGovernanceGates` (class-size) green. Fix #2 refuted (correct-by-design). Net-new: not derived from any prior tempdoc; surfaced by a backend-robustness audit of the "frozen engine" while the active worktrees were all in frontend/agent-SSE/API-explorer. Remaining: optional independent review + the deferred wire-state `FAILED` follow-up (§6, logged to observations).
created: 2026-06-15
updated: 2026-06-15
---

# 588 — The Worker indexing engine can die silently

## 0. Thesis in one paragraph

The retrieval/indexing engine got little direct attention across the recent UI + governance
waves (the observation that opens tempdoc 580). One consequence: the Worker's indexing loop has
two *silent* failure modes — failures that leave the process reporting healthy while real work has
stopped. (1) An uncaught `Error` (e.g. a `LinkageError`/`NoClassDefFoundError` from a plugin, an
`AssertionError`, or a fatal `OutOfMemoryError`) propagating out of one batch kills the
`indexing-loop` thread, because the loop's only per-iteration handler is `catch (Exception)` —
`Error` is not an `Exception`. The thread dies, but `running` stays `true` and `currentState`
stays `RUNNING`, so the worker-state wire keeps advertising `RUNNING` while indexing is
permanently dead and new documents sit in `PROCESSING` forever. (2) The process-extraction
sandbox leaks the child process and both stdout/stderr reader threads whenever serializing or
writing the request fails (a broken pipe from a child that already exited), because — unlike the
timeout and interrupt branches — the write path has no `destroyForcibly()`. This doc traces both
to source, fixes them with regression tests, and records one refuted hypothesis.

## 1. Provenance — why this is net-new

This is **not** a pickup of an existing tempdoc. It was surfaced by a deliberate backend-robustness
audit run while the three active worktrees were all elsewhere (shell-v0 frontend polish, the
585 agent-SSE payload work, the 583 §D API-explorer). Novelty was checked: `grep` over
`docs/observations.md` and `docs/tempdocs/` for `IndexingLoop` / `processBatch` / silent-loop-death
/ `ProcessExtractionSandbox` returned only unrelated, already-resolved entries (a metric-migration
follow-up, an embedding-service unload fix, a tempdoc-number-collision note). The "engine reports
healthy while indexing is dead" failure mode is undocumented.

## 2. Findings (each verified against source, file:line quoted)

### F-1 — Uncaught `Error` silently kills the indexing loop (CONFIRMED, high value)

`modules/worker-services/.../loop/IndexingLoop.java`:

- The loop thread body is `runLoop` (`:494`), started at `:472` with **no
  `UncaughtExceptionHandler`** (`new Thread(this::runLoop, "indexing-loop")` → `setDaemon(true)`
  → `start()`).
- The per-iteration handler set inside `while (running.get() ...)` is only
  `catch (InterruptedException)` (`:604`, break) and `catch (Exception e)` (`:607`, log + sleep
  + continue). An `Error` thrown from `processBatch` → `processBatchInner` (`:664`/`:681`, neither
  has a catch) is **not** an `Exception`, so it propagates out of `runLoop` and the thread dies.
- On death, nothing resets state: `running` (AtomicBoolean, `:107`) stays `true`, and
  `currentState` (volatile `LoopState`, `:160`) stays at its last value (`RUNNING` if it died
  mid-batch). `loopState()` (`:825`) / `getCurrentState()` (`:820`) therefore keep reporting a
  healthy state.
- **The wire lies, but one accessor already tells the truth.** `isRunning()` (`:802`) returns
  `running.get() && loopThread != null && loopThread.isAlive()` — it would correctly report
  `false` after the thread dies. The gap is that the worker-state wire path
  (`GrpcHealthService.determineWorkerState` → `workerStateSupplier` →
  `DefaultWorkerAppServices.indexingLoopState()` → `getCurrentState()`) reads the stale
  `currentState`, not `isRunning()`.

**Consequence.** A single pathological document that triggers an `Error` during extraction or
embedding permanently halts all indexing; the Worker's health/state continues to report
`RUNNING`; documents submitted afterward are enqueued and acknowledged but never processed
(the heartbeat reaper re-queues their `PROCESSING` rows, but the dead loop never drains them).

### F-3 — Extraction sandbox leaks the child process + 2 reader threads on write failure (CONFIRMED)

`modules/worker-services/.../extract/ProcessExtractionSandbox.java`:

- `extract()` spawns the child (`:58`) and starts two `CompletableFuture` reader tasks for
  stdout/stderr (`:59-62`), then serializes the request (`:64-69`) and writes it to the child's
  stdin (`:70-71`).
- `MAPPER.writeValueAsBytes(...)` can throw (`JsonProcessingException`), and
  `process.getOutputStream().write(request)` can throw `IOException` (broken pipe if the child
  already crashed/exited). Both propagate out of `extract()` **without** `process.destroyForcibly()`
  — unlike the interrupt branch (`:78`) and the timeout branch (`:82`), which both destroy.
- The child becomes a zombie (reaped only by `Process` finalization, if ever), and the two reader
  threads stay blocked in `read()` until the streams close.

**Consequence.** Under a flapping sandbox child (the exact condition the sandbox exists to contain),
each failed extraction leaks one process + two reader threads.

## 3. Refuted hypothesis (recorded so it isn't re-walked)

### F-2 (REFUTED) — "commit failure skips `journal.drainPending()` → lost outcomes"

A static audit flagged that `journal.drainPending()` sits *inside* the commit `try` blocks
(`:591` mid-loop, `:627` shutdown), so a thrown `commitAndTrack` skips the drain, and argued this
loses pending outcome transitions and causes duplicate re-indexing.

**Investigated and refuted.** `IngestionOutcomeJournal.drainPending()` (`IngestionOutcomeJournal.java:96`)
flushes `pendingMarkDone` — i.e. it marks the batch's jobs **DONE** in the queue. If the Lucene
commit *failed*, those documents are not durably persisted; marking them done would be data loss
(they'd vanish on restart with the queue saying "complete"). Skipping the drain on commit failure
is therefore **correct by design**: the jobs stay `PROCESSING`, `pendingMarkDone` carries forward,
and the next *successful* commit (which flushes all pending Lucene writes, including the earlier
batch) drains them honestly; if no commit ever succeeds, the reaper reprocesses — the desired
recovery, not a bug. **No change.** (This is the `critical-analysis-pass` / `interrogate-results`
discipline catching a plausible-but-wrong audit conclusion.)

## 4. Design decisions

### D-1 — Fix F-1 by making the loop *survive* recoverable Errors + telling the truth on fatal ones — NOT by adding a `FAILED` wire state

The honest-but-heavy option is a 4th `LoopState` value (`FAILED`) surfaced on the worker-state
wire. Rejected for this slice because `LoopState` is a wire-string identity with literal-pinning
consumers (`GrpcHealthService`, `ChaosSuiteTest`, the `KnowledgeServer` queue-depth gauge) and FE
readers; a new enum value pulls in the wire-Category gate, schema work, and **frontend coordination
— a collision with the active shell-v0 worktrees.** Instead:

1. **Survive recoverable Errors.** Add `catch (Throwable t)` after the existing `Exception` handler
   so a *non-VM* `Error` from one batch is logged and the loop continues to the next batch —
   exactly the resilience the `Exception` path already provides. A single bad document no longer
   kills the whole engine.
2. **Tell the truth on fatal VM errors.** Add `catch (VirtualMachineError e)` *before* the
   `Throwable` catch: log loudly, set `running.set(false)`, and re-throw. Continuing after
   `OutOfMemoryError` / `StackOverflowError` is unsafe — so we stop, but observably.
3. **Defense-in-depth.** Install `loopThread.setUncaughtExceptionHandler(...)` that flips
   `running.set(false)` and logs FATAL if the thread ever dies for any reason. Because
   `isRunning()` already ANDs `loopThread.isAlive()`, this makes the honest liveness signal correct
   even on paths we didn't foresee.

**Not the forbidden "broaden a catch to silence a failure" anti-pattern.** The added catches do the
opposite of hiding: they convert a *silent* thread-death into a logged, observable, recoverable
event, and re-throw the genuinely-fatal class. The existing `catch (Exception)` is unchanged.

**Follow-up (documented, deferred):** surfacing loop-death on the worker-state *wire* (so the UI
shows "indexing stopped" rather than a stale `RUNNING`) is a separate, FE-touching change. Backend
consumers can already read the truth via `isRunning()`; the wire-state reconciliation is logged as
a candidate follow-up, not done here, to keep this slice backend-only and collision-free.

### D-2 — Fix F-3 surgically: destroy the child if the request-write fails

Wrap only the serialize+write (`:64-71`) in a `try` that calls `process.destroyForcibly()` before
re-throwing. `destroyForcibly()` closes the child's streams, which unblocks (and completes) both
reader futures — so one call releases the process *and* the threads. The other exit paths are
already correct (interrupt/timeout destroy; the post-`waitFor` paths run after the child has
exited, so there is nothing to leak). Minimal diff, no behavior change on the success path.

## 5. Test plan

- **F-1 regression** (`IndexingLoopTest` / new methods, mirroring `IndexingLoopRestartTest`'s
  real-thread harness with mocked `JobQueue`/`IndexingCoordinator`/`CommitOps`):
  - `recoverableErrorInBatch_loopSurvivesAndReachesIdle`: `pollPending` throws a plain `Error`
    once then returns empty; assert the loop reaches `IDLE` and `loopThread.isAlive()` stays
    `true` (pre-fix: the thread dies). Stands in for any `Error` in the iteration body.
  - `fatalVmError_stopsLoopAndReportsNotRunning`: `pollPending` throws a `StackOverflowError`
    (a `VirtualMachineError`); assert the thread terminates and `isRunning()` returns `false`
    (honest liveness via the uncaught handler), not a stale `RUNNING`.
- **F-3**: keep the existing `ProcessExtractionSandboxTest` green (no regression). The broken-pipe
  leak path is timing-dependent on real process scheduling and not deterministically unit-testable
  without a fake-process seam; the fix is a straightforward resource-cleanup guard verified by
  reading. (Honest limit — recorded, not hidden.)
- **Build**: `spotlessApply` → `:modules:worker-services:test` → `build -x test`.

## 6. Scope / non-goals

- In scope: `IndexingLoop` survivability + honest liveness (F-1), `ProcessExtractionSandbox` leak
  (F-3), regression tests, this doc.
- Out of scope (logged): the wire-state `FAILED` surfacing (FE-touching follow-up, D-1); the
  adjacent worker-restart findings from the same audit (circuit-breaker-not-reset-on-fast-restart,
  catch-up-init-not-re-run) — real candidates for a *separate* slice, recorded in
  `docs/observations.md` so they aren't lost.
