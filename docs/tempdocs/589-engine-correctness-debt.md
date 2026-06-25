---
title: "589 — Engine correctness debt: two concurrency/robustness defects in the Worker index + OCR paths, verified from a backend audit. (A) IndexGenerationManager's read-cache shares a NON-volatile field across the gRPC-handler threads that call it concurrently, so a status/migration RPC can get a cached State from a different generation (torn read). (B) OcrProcessor's per-request timeout is silently NOT enforced for tasks that overflow the bounded pool and run inline on the caller (CallerRunsPolicy), so a pathological scanned document can block the caller for the full, untimed OCR duration. Charter: verify each against source first, fix the real ones with regression tests, and DON'T change deliberate, test-pinned behavior unilaterally."
status: active (2026-06-15) — both findings verified against source (Fix A: `lastReadVersion` is non-volatile + the `(version,state)` pair updates non-atomically; Fix B: timeout is registered AFTER `submit`, and `submit` blocks under CallerRunsPolicy so the timer never arms for caller-run tasks). In implementation. Net-new (not from a prior tempdoc); surfaced by the same backend audit that produced 588.
created: 2026-06-15
updated: 2026-06-15
---

# 589 — Engine correctness debt (index-generation read-cache race + OCR caller-run timeout hole)

## 0. Thesis

Two backend correctness defects, both in the "frozen engine" the recent waves didn't touch, both
verified from source (not trusted from the audit):

- **A — IndexGenerationManager read-cache data race.** The dirty-flag cache that lets
  `readStateBestEffort()` skip re-parsing `state.json` on every RPC uses a **non-volatile**
  `lastReadVersion` field, paired non-atomically with a `volatile cachedState`. The method is
  called concurrently from gRPC status/migration handlers while a migration thread bumps
  `stateVersion`. A reader can therefore observe a `cachedState` that does not correspond to the
  current generation — a transient *wrong* answer about which generation is active/building.
- **B — OcrProcessor caller-run tasks bypass the timeout.** The OCR pool uses
  `CallerRunsPolicy`, so when it saturates, `executor.submit(task)` runs the OCR **inline on the
  caller** and only returns after it completes. The per-request timeout is registered *after*
  `submit()` returns — i.e. after the inline OCR is already done (or still blocking) — so caller-run
  tasks get **no timebox at all**, defeating the "a pathological scanned PDF can't hang the loop"
  guarantee exactly under load.

## 1. Provenance — net-new

Not a pickup of an existing tempdoc. Surfaced by the same backend-robustness audit that produced
588, while the active worktrees were in frontend / agent-SSE / API-explorer / gpu-capability.

## 2. Finding A — IndexGenerationManager read-cache data race (CONFIRMED)

`modules/worker-core/.../index/IndexGenerationManager.java`:

```
:110  private volatile long stateVersion = 0L;     // volatile
:111  private volatile State cachedState = null;    // volatile
:112  private long lastReadVersion = -1L;           // NON-volatile  ← the bug
```

`readStateBestEffort()` (`:474`):
```
long currentVersion = stateVersion;                              // volatile read
if (lastReadVersion == currentVersion && cachedState != null)    // reads NON-volatile lastReadVersion
    return cachedState;
... loadStateBestEffort() ...
cachedState = normalized;                                        // volatile write
lastReadVersion = currentVersion;                               // NON-volatile write (after)
```
`writeState()` invalidates via `stateVersion++` (`:656`).

- `lastReadVersion` is read at `:476` and written at `:483`/`:488`, only inside this method, but by
  **whichever threads call it** — and the callers are concurrent gRPC handlers (`startMigration`,
  `setMigrationPaused`, `updateMigrationState`, `promoteBuildingGenerationToActive`,
  `rollbackToPreviousGeneration`, plus status reads) with **no synchronization** (verified: no
  `synchronized` / lock anywhere on this path).
- Two readers racing a writer can observe the pair `(lastReadVersion, cachedState)` torn: e.g.
  `lastReadVersion == currentVersion` becomes visible while `cachedState` still holds the *previous*
  generation's State (the non-volatile write has no happens-before vs. the volatile one). The method
  then returns a `cachedState` that does not match `currentVersion`.
- `stateVersion++` is also a non-atomic read-modify-write on a volatile; harmless if `writeState` is
  single-threaded, but the fix removes the field entirely so it stops mattering.

**Consequence.** A status / migration RPC can momentarily report the wrong active/building
generation during a blue-green migration, or do redundant disk reads. Transient, timing-dependent —
a real data race, low-but-nonzero blast radius.

### 2.1 Fix A

Replace the `(stateVersion, cachedState, lastReadVersion)` triple with **one immutable holder behind
a single `volatile` reference**, and invalidate by nulling it on write:

```
private record CachedState(State value) {}           // value may be null (means "state.json absent")
private volatile CachedState cache = null;            // the ONE shared field

State readStateBestEffort() {
  CachedState c = cache;                  // single volatile read — atomic
  if (c != null) return c.value();
  ... load ...
  cache = new CachedState(normalized);    // single volatile write — atomic publish of the pair
  return normalized;
}
// writeState step 4: cache = null;       // invalidate (replaces stateVersion++)
```

The `(present?, value)` pair is now published atomically through one reference; there is no
non-volatile field and no non-atomic counter, so the torn read is unrepresentable. Same best-effort
semantics (a read racing the disk-write+invalidate sees either the old cache or a fresh load — never
a torn pair). `stateVersion` is deleted (verified sole consumer is this cache).

## 3. Finding B — OcrProcessor caller-run tasks bypass the timeout (CONFIRMED)

`modules/app-indexing/.../ocr/OcrProcessor.java`:

- Pool built with `new ThreadPoolExecutor(... , new CallerRunsPolicy())` (`:55-63`).
- `process()` calls `executor.submit(task)` (`:112`) and *then* registers the timeout
  (`scheduler.schedule(...)`, `:114-133`).
- Under `CallerRunsPolicy`, when the pool+queue are saturated, `submit()` runs the task **inline on
  the caller** and blocks until it completes. So for a caller-run task, control does not reach the
  timeout-registration block until the OCR has already finished — the timer never arms, and the
  caller is blocked for the full, **untimed** OCR duration.

**Consequence.** Pool-run tasks are timeboxed; caller-run tasks (which happen precisely under load)
are not. A single pathological scanned document can block the caller thread unbounded despite a
timeout being requested.

### 3.1 Design constraint — CallerRunsPolicy is deliberate and test-pinned

`OcrProcessorTest.queueOverflowRunsInCallerThread` (`:132`) explicitly pins CallerRuns: *"tasks that
overflow the queue are run by the caller thread instead of being rejected. This provides natural
backpressure without data loss."* So switching to `AbortPolicy` (drop-on-overflow) — the audit's
implied fix — would change **intended, test-pinned behavior**. Per `fix-root-causes-not-symptoms` /
`ask-when-uncertain`, that is an architectural change for the owner to make, not a unilateral fix.

### 3.2 Fix B — give caller-run tasks the SAME timebox as pool-run tasks (design-preserving)

Keep `CallerRunsPolicy`. Close only the *asymmetry*: register the timeout and capture the task's
`Future` **before** handing it to the executor, by wrapping the task in a `FutureTask` we hold a
reference to:

```
FutureTask<Void> ft = new FutureTask<>(task, null);
ScheduledFuture<?> timeout = (timeoutMs > 0) ? scheduler.schedule(() -> {
    if (!promise.isDone()) { timedOut.set(true);
      if (promise.complete(Result.failed("timeout", new TimeoutException(...)))) recordFailure(...,"timeout");
      ft.cancel(true);   // interrupts whichever thread runs ft — pool OR caller
    }}, timeoutMs, MILLISECONDS) : null;
if (timeout != null) promise.whenComplete((r,t) -> timeout.cancel(false));
try { executor.execute(ft); }   // CallerRuns runs ft.run() inline; the timer (already armed) can cancel it
catch (RuntimeException ex) { if (timeout != null) timeout.cancel(false); recordFailure(...,"execution_rejected"); promise.complete(...); }
```

Now a caller-run task is interrupted at its deadline just like a pool-run task. This preserves
CallerRuns backpressure and keeps both pinned tests green. **Honest residual limit (documented):**
`cancel(true)` only *interrupts*; a genuinely uninterruptible native OCR call ignores the interrupt —
but that limit is identical for pool-run tasks and is not specific to this fix. The fix removes the
*asymmetry* (caller-run tasks were uniquely un-timed), not the deeper native-uninterruptibility
property.

## 4. Test plan

- **A** (new `IndexGenerationManagerCacheTest`, the class had no unit test): `initializeOrLoad()` →
  two `readStateBestEffort()` return the *same cached instance* (cache hit); after a real write
  (`startMigration(...)`) a third read returns a *different* State reflecting the migration (cache
  invalidated on write). Deterministic single-threaded correctness; the race itself is fixed
  by-construction (single volatile holder).
- **B** (new `OcrProcessorTest` method): saturate the size-1 pool + size-1 queue with two blocking
  tasks, then submit a third (caller-run) whose interruptible operation sleeps past a short timeout;
  assert it completes with `reason == "timeout"`. Pre-fix it blocks for the full sleep and completes
  `SUCCESS`; post-fix it is timed out — the assertion discriminates. Existing
  `queueOverflowRunsInCallerThread` + `timeoutCancelsExecution` must stay green.
- **Build**: `spotlessApply` → `:modules:worker-core:test` + `:modules:app-indexing:test` →
  `build -x test` + `verifyGovernanceGates`.

## 5. Scope / non-goals

- In scope: Fix A, Fix B, regression tests, this doc.
- Out of scope: changing the OCR rejection policy to `AbortPolicy` (deliberate, test-pinned — owner's
  call); the deeper native-OCR-uninterruptibility limit (§3.2).
