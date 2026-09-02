---
title: "Wave-1 residue, Worker lane: the watcher's create-event size race, and what actually floors the commit count"
type: tempdocs
status: "IMPLEMENTED (2026-09-02) — item 1 fixed + 5/5 stable; item 2 instrumented and unit-verified; the live attribution run is the orchestrator's, not run here"
created: 2026-09-02
updated: 2026-09-02
lane: R7 (wave-1 residue closure, Worker side)
model: opus (implementation)
parent: 885-decision-review-lane-c-runtime-lifecycle-and-isolation
coordination: "→ lane R5 pins the WorkerMethvinWatcherTest flake in expected-state.v1.json with 'R7 merged' as its exit; that file is NOT edited here, so R5 removes the pin after this merges. → 885 'Residue live window, part A' owns the arm table this item-2 instrumentation exists to explain; the attribution run belongs to that window, not to this tempdoc."
related:
  - 885-decision-review-lane-c-runtime-lifecycle-and-isolation  # UL.10 third routed finding (:3624), live window part A / A3 (:3645, :3787), the no-fork rule (§3387)
  - 813-indexing-progress-queue-ux-design    # Slice B: EnqueueEntry sizes, PendingBytes tri-state
  - 417-metric-catalog-typed-instruments     # CommitReason enum, the reason-tagged commit_ms histogram
  - 626-watcher-consolidation                # §Axis-A: overflow/burst recovery on the Worker watcher
---

# 912 — Worker residue: watcher size race + commit-count floor

Two items routed by 885 to the Worker lane.

* **Item 1** — `WorkerMethvinWatcherTest` fails under full-suite load with
  `expected: <4096> but was: <0>`. 885 diagnosed a create-event race. Fixed in the watcher.
* **Item 2** — 885's A3 arm relaxed three commit-cadence knobs at once and moved the commit
  count only 17 %, so none of the three is the binding constraint. Nothing in the Worker could
  say which trigger *is*. This adds the accounting and names a hypothesis.

---

## §B — Pre-implementation pass (every brief claim verified verbatim)

### B.1 The failing test is misnamed in 885

885:3630 and the lane brief both name
`WorkerMethvinWatcherTest.deliversCreateEventToJobQueue`. The quoted assertion message —
`The watcher must record the file's real size, not the unknown-size sentinel` — occurs exactly
once in the repo, at
`modules/worker-services/src/test/java/io/justsearch/indexerworker/services/WorkerMethvinWatcherTest.java:96`,
inside **`createEventCarriesTheFilesRealSizeToTheQueue`**. `deliversCreateEventToJobQueue`
(same file, :43-62) asserts only the path and the collection tag and cannot produce that
message. The failing test is `createEventCarriesTheFilesRealSizeToTheQueue`. Corrected here;
885's text is dated history and is not edited.

### B.2 What is read, when, and what the sentinel is

* Read site: `WorkerMethvinWatcher.java:192` (pre-fix) —
  `jobQueue.enqueueEntries(List.of(JobQueue.EnqueueEntry.stat(path)), collection);` inside the
  `case CREATE, MODIFY` branch of `handleEvent`.
* `JobQueue.EnqueueEntry.stat(Path)` —
  `modules/worker-core/src/main/java/io/justsearch/indexerworker/queue/JobQueue.java:65-74`:
  `new EnqueueEntry(path, java.nio.file.Files.size(path))`, degrading to `ofUnknownSize` **only
  on `IOException`/`RuntimeException`**.
* The sentinel is `JobQueue.UNKNOWN_SIZE_BYTES = -1L` (`JobQueue.java:39`).

**The brief's and 885's mechanism is subtly wrong, and the correction matters.** The observed
value is `0`, not `-1`. The stat did **not** fail and the sentinel was **not** recorded — the
stat *succeeded* and returned `0` because the file existed but had no content yet.
`EnqueueEntry.stat` has no notion of "0 is suspicious", so a mid-write file is persisted as a
**known** size of zero. The defect is therefore not "records the sentinel" but the opposite:
*it records a fabricated fact where it should record the sentinel.*

### B.3 Who consumes the size — this decides the fix

`size_bytes` is written by exactly one statement,
`SqliteJobQueue.enqueueEntries` (`modules/indexer-worker/.../SqliteJobQueue.java:390-392`,
`INSERT OR REPLACE`), and read by `JobQueue.pendingBytes()` (`SqliteJobQueue.java:321`), which
returns `PendingBytes(knownBytes, unknownSizeJobs)` over **PENDING + PROCESSING** rows. That
flows to `CoreStatus.pending_bytes` / `pending_unknown_size_jobs` and on to
`CoreIndexView.pendingBytes` — a *progress / remaining-weight* consumer.

Two consequences:

1. **The brief's option (a) — "take the size at dequeue time" — is wrong for this consumer.**
   The aggregate exists to weigh work that has *not* been processed yet. Deferring the size to
   dequeue would mean PENDING rows never have one, which is the majority of what the number is
   supposed to describe.
2. **A known `0` is worse than an unknown.** 813 Slice B deliberately built a tri-state
   (`JobQueue.java:100-108`, `CoreIndexView.java:23-33`) so a consumer can tell *"nothing left"*
   from *"work left whose weight is unknown"*. A racy `0` is counted as a real, zero-weight job:
   `unknownSizeJobs` stays put and `pendingBytes` renders "0 bytes remaining" mid-backlog. A
   mid-write 4 GB file understates the backlog by 4 GB with **nothing** marking the estimate as
   incomplete. This is the `tri-state lookup` failure mode named in `slice-execution.md`.

### B.4 The brief's option (b) — bounded re-stat — evaluated and rejected

Rejected on two grounds, both from the code:

* It blocks the watcher's **event-delivery thread** on wall-clock time. This class already
  treats that as a hazard: `WorkerMethvinWatcher.java:70-74` dispatches reconciles onto
  `reconcileExecutor` explicitly "so a long walk never blocks event delivery". A sleep-and-retry
  in `handleEvent` reintroduces exactly what that comment exists to prevent, and a create-storm
  of genuinely empty files pays the budget on every event.
* It does not make the property deterministic. Under the load that produced the flake, a writer
  can be descheduled for far longer than any budget small enough to be safe — so a re-stat
  trades a *wrong* value for a *flaky* one. Correctness must not depend on the budget.

An **asynchronous** deferred re-stat + re-enqueue was also considered and rejected:
`JobQueue.reenqueue` (`SqliteJobQueue.java:442-482`) writes `collection = NULL` and resets
`state='PENDING', attempts=0`, so a late re-enqueue would drop the watcher's collection tag and
could resurrect a job that had already been processed.

### B.5 Item 2 — three brief claims corrected against source

| Brief claim | Source | Verdict |
|---|---|---|
| "a `reason` enum at the single commit funnel" (to be added) | `CommitReason.java:16-39` — 23 values, stable `wireValue()`s | **Already exists** since 417 Phase 1, and already threaded through every `commitAndTrack` call site and onto the `index.runtime.commit_ms` histogram tag. What is missing is the *count* per reason, not the enum. |
| "`RuntimeSession.commitCount` at ~:121 is the all-paths counter" | `RuntimeSession.java:151` | Line number off; the field is real. **"All-paths" is not exact** — see §C.4 for two uncounted durable commits. |
| "quintupling the doc buffer (1000→5000)" | `EnvRegistry.java:693-696` `BACKFILL_MAX_DOCS_BEFORE_COMMIT` default `1000` | Default confirmed; `5000` is the *arm's override*, not a default. Note the key is `justsearch.backfill.*` but is read by `LoopPacingPolicy` for the **primary indexing loop** — see the census, this mislabelling is load-bearing for the floor hypothesis. |

---

## §A — Item 1: the create-event size race

### What changed

`WorkerMethvinWatcher.java`:

* `handleEvent`'s `CREATE, MODIFY` branch extracted to a package-private `handleUpsert(root,
  collection, path)` — the same seam rationale the file already applies to `handleDelete` and
  `handleOverflow` ("package-private so the guard is deterministically unit-testable without a
  live `DirectoryWatcher`").
* New `static JobQueue.EnqueueEntry entryForLiveEvent(Path)`: stats as before, but maps a size
  of `0` to `UNKNOWN_SIZE_BYTES`.

The invariant, stated positively: **a single-file watcher event never records a known size of
zero.** For a genuinely empty file the two encodings contribute the same `0` to `knownBytes`
and differ only in that unknown *also* admits the weight is unvouched — which for a job that is
genuinely pending is the honest answer either way. For a mid-write file, `0` is a lie. There is
no case where the known zero is more informative.

Self-healing is a bonus, not the mechanism: the jobs insert is `INSERT OR REPLACE`, so any later
event for the path restates the size. That is *not* relied on — with
`FileHasher.LAST_MODIFIED_TIME` (`WorkerMethvinWatcher.java:139`) a create+write inside one
mtime tick may produce no follow-up MODIFY at all. Correctness comes from the encoding.

### Tests

| Test | Property | Falsified by | Observed failure |
|---|---|---|---|
| `liveEventOnAStillEmptyFileRecordsUnknownSizeNotAKnownZero` (new) | the race itself: a file that is empty at event time records UNKNOWN | reverting `entryForLiveEvent` to `return stated;` (the pre-fix path) | `A zero-byte stat on a live event is 'looked too early', not a known size of zero ==> expected: <-1> but was: <0>` |
| `liveEventOnAWrittenFileRecordsItsRealSize` (new) | the fix does not over-reach | `entryForLiveEvent` → always `ofUnknownSize` | `A settled file's real size must survive the zero-is-unknown mapping ==> expected: <2048> but was: <-1>` |
| `createEventCarriesTheFilesRealSizeToTheQueue` (fixture rewritten) | 813's call-site pin, now deterministic | same always-unknown mutation | `expected: <4096> but was: <-1>` |

The race test is fed a **synthetic** event through `handleUpsert`, so it does not depend on OS
scheduling — the state it constructs (file exists, is empty) is exactly the state the racing
watcher observes. The 813 call-site pin keeps its full bite but its fixture now writes the file
to a staging directory **outside** the watched root and `Files.move`s it in, so the file is
complete at the instant it becomes visible and the event thread cannot observe a partial write.

**Stability:** `:modules:worker-services:cleanTest test --tests '*WorkerMethvinWatcherTest*'
--no-build-cache`, five consecutive runs — **5/5 PASS**, 10 testcases / 0 failures each.

---

## §A2 — Item 2a: the commit-trigger census

Every path that reaches the commit funnel `CommitOps.commitAndTrack(CommitReason)`
(`modules/adapters-lucene/.../CommitOps.java:155`, counter at `:161`). Guards quoted verbatim.

| # | Reason | Call site | Guard | Interval / threshold |
|---|---|---|---|---|
| 1 | `TIMER` | `adapters-lucene/.../CommitOps.java:375` | `pendingDocs.get() > 0 && timerSnap != null && timerSnap.writer() != null` | `INDEX_COMMIT_TIMER_INTERVAL_MS` (`EnvRegistry.java:1305`) **10000 ms** |
| 2 | `DRAIN` | `adapters-lucene/.../RunningRuntime.java:207` | `pendingDocs.get() > 0 && commitOps != null` | none (event) |
| 3 | `PRUNE` | `adapters-lucene/.../PruneOps.java:139` | `pruned > 0` | none |
| 4 | `SYNC_PRUNE` | `worker-services/.../SyncDirectoryOps.java:127` | `filesDeleted > 0 && commitOps != null` | none |
| 5 | `GRPC_DELETE_BY_PATH` | `worker-services/.../GrpcIngestService.java:930` | unconditional post-delete | none |
| 6 | `GRPC_DELETE_BY_COLLECTION` | `GrpcIngestService.java:981` | unconditional | none |
| 7 | `GRPC_DELETE_BY_ID` | `GrpcIngestService.java:1042` | unconditional — **commits even when 0 docs matched** | none |
| 8 | `GRPC_UPDATE_PATHS` | `GrpcIngestService.java:1487` | unconditional — commits even if every mapping failed | none |
| 9 | `RESET` | `GrpcIngestService.java:1926` | unconditional inside `resetForProfiling` | none |
| 10 | `INDEXING_LOOP_IDLE` | `worker-services/.../loop/IndexingLoop.java:637` | `indexedSinceCommit > 0`, reached only when `jobs.isEmpty()` (`:621`) | **none — fires on every drain-to-idle** |
| 11 | `INDEXING_LOOP_TIME` | `IndexingLoop.java:695` | `timeSinceCommitMs >= commitIntervalMs && indexedSinceCommit > 0` (`LoopPacingPolicy.java:79-82`) | `BACKFILL_COMMIT_INTERVAL_MS` (`EnvRegistry.java:687`) **10000 ms** |
| 12 | `INDEXING_LOOP_BUFFER` | `IndexingLoop.java:695` | `indexedSinceCommit >= maxDocsBeforeCommit` (`LoopPacingPolicy.java:88-90`) | `BACKFILL_MAX_DOCS_BEFORE_COMMIT` (`EnvRegistry.java:693`) **1000 docs** |
| 13 | `INDEXING_LOOP_SHUTDOWN` | `IndexingLoop.java:793` | `indexedSinceCommit > 0` | none |
| 14 | `INDEXING_LOOP_REBUILD_STAMP` | `worker-services/.../EmbeddingProviderLifecycle.java:360` | debounced on 2 consecutive `pending == 0` reads (`:349`) | none |
| 15 | `INDEXING_LOOP_FRESH_STAMP` | `EmbeddingProviderLifecycle.java:466` | `reconcileStampEvidence()` + backoff window (`:457-461`) | `FRESH_STAMP_RETRY_BACKOFF_MS` (same file) |
| 16 | `BACKFILL_COMBINED` | `worker-services/.../loop/ops/CombinedEnrichmentBackfillOps.java:1090` | `written > 0 && batchesSinceCommit[0] >= 5` | **inline literal `5`, no constant, not configurable** |
| 17 | `BACKFILL_COMBINED_FINAL` | `worker-services/.../loop/BackfillScheduler.java:281` | `batchCommitCounter[0] > 0` | none |
| 18 | `BACKFILL_SPLADE` | `worker-services/.../loop/ops/SpladeBackfillOps.java:254` | `(processed > 0 \|\| failed > 0) && context.commitAfterBatch()` | batch size only |
| 19 | `BACKFILL_BGE_M3` | `worker-services/.../loop/ops/BgeM3BackfillOps.java:325` | `(processed > 0 \|\| failed > 0) && context.commitAfterBatch()` | batch size only |
| 20 | `BACKFILL_NER` | `worker-services/.../loop/ops/NerBackfillOps.java:133` | `processed > 0 \|\| failed > 0` — **no `commitAfterBatch` gate** | **none** |
| 21 | `BACKFILL_EMBEDDING` | `worker-services/.../loop/ops/EmbeddingBackfillOps.java:221` | `processed > 0 \|\| failed > 0` | **none** |
| 22 | `BACKFILL_EMBEDDING_CHUNK` | `EmbeddingBackfillOps.java:471` | `processed > 0 \|\| failed > 0` | **none** |
| 23 | `UNKNOWN` (migration cutover) | `indexer-worker/.../KnowledgeServerMigrationOps.java:224` via `commitWithBuildState` → `commitAndTrack()` | post `finalizeEmbeddingRebuildAction` | none — **attribution gap**, see §D |

Triggers the brief asked about that **do not exist**: no NRT-reopen-forces-commit
(`CommitOps.maybeRefresh*` at `:189-229` reopens the searcher only), no retry-ladder commit, no
scan-completion or per-root commit, no schema/config-change commit, no `forceMerge`,
`prepareCommit`, or explicit index `flush()` anywhere in `adapters-lucene`, `worker-services`,
`worker-core` or `indexer-worker` main source.

### Two durable commits the counter does not see

* `KnowledgeServerMigrationOps.java:771` calls the **low-level** `CommitOps.commit()` (guard
  `mutatedLucene && context.ingestLifecycle() != null`, `:769`), bypassing `commitCount`,
  `pendingDocs.set(0)`, `TelemetryEvents.onCommit` and the `CommitCompletedListener`.
* `RuntimeSession.java:635` `snap.writer().close()` — Lucene's `commitOnClose` defaults to true
  and there is no production `setCommitOnClose` call anywhere, so session teardown commits
  uncounted whenever `pendingDocs == 0` skipped the `DRAIN` pre-commit (`RunningRuntime.java:205`).

Both are in `modules/indexer-worker` / session teardown and are routed as open items (§D)
rather than fixed here — they are outside this lane's ownership and neither is a plausible
contributor to a per-minute commit *rate*.

---

## §A3 — Item 2b/2c: the accounting

**The enum already existed; the count did not.** So this extends the one counter rather than
adding a sibling, which is what 885 §3387 asked for.

* **`CommitCounters`** (new, `adapters-lucene/.../CommitCounters.java`) replaces
  `RuntimeSession.commitCount`'s `AtomicLong`. It holds an `AtomicLongArray` indexed by
  `CommitReason.ordinal()`; `get()` **sums** the slots. "The total equals the sum of the reasons"
  is therefore **structural** — there is no way to increment the total without naming a reason,
  and no second counter to drift. `get()` keeps the `AtomicLong` method name, so every existing
  reader (`RunningRuntime.java:230` and three tests) compiles and means the same thing.
* **`CommitOps.commitAndTrack`** increments `session.commitCount.increment(effectiveReason)` and
  emits **one DEBUG line per commit** (not per doc) naming the reason, the pending-doc count at
  commit, elapsed ms, and both the per-reason and total session counts.
* **`index.runtime.commit_total`** — a reason-tagged counter in `IndexRuntimeMetricCatalog`
  (template: the existing `VALIDATION_FAILURE_TOTAL`; `cardinalityLimit(32)` covers 23 reasons),
  incremented in `WorkerLuceneTelemetryAdapter.onCommit` from the same `CommitTags` the
  `commit_ms` histogram already uses. This is the surface the live run reads.
* **`scripts/jseval/jseval/cadence.py`** gains `commit_by_reason` + `commit_by_reason_total` in
  the `cadence` block. Read separately from `_COUNTERS` because the metric is *tagged*: each
  reason is its own NDJSON series under the same `name`, so the existing name-keyed max would
  report the largest single reason as if it were the total. Per-reason max, then summed.

### Why not a status-wire proto field

The brief asked for exposure "in the block that already carries `commit_total`/cadence". Two
findings redirect that:

1. **There is no cadence block on the wire.** `cadence` exists only in
   `scripts/jseval/jseval/cadence.py`; it reads the telemetry NDJSON, not `/api/status`.
2. **The two nearest fields were deliberately kept off the wire, by this same parent tempdoc.**
   `IndexRuntimeMetricCatalog.java` carries the decision verbatim for `REOPEN_COUNT` /
   `SEGMENTS_SINCE_REOPEN`, added by 885 item 19: *"Archived, not surfaced on /api/status: the
   cadence comparison reads a trend off the metrics NDJSON, and adding status-wire fields would
   drag a proto change into a measurement knob."* Adding `commit_by_reason` to `CoreStatus`
   would also require `modules/ipc-common`, `modules/app-api` and `modules/app-services` edits —
   the last two outside this lane's ownership — to reach a consumer.

So this follows the established route for its own siblings. Flagged for the orchestrator to
overrule if the status wire is wanted anyway; it is a proto field + two mapper lines away.

### Tests

| Test | Property | Falsified by | Observed failure |
|---|---|---|---|
| `CommitReasonAccountingTest.eachReasonAccruesToItsOwnSlotAndTheTotalIsTheirSum` | 3 TIMER / 2 IDLE / 1 NER land in their own slots; total == sum; never-fired reasons absent from `snapshot()` | `CommitCounters.increment` → always `UNKNOWN.ordinal()` | `TIMER must count its own three commits ==> expected: <3> but was: <0>` |
| `CommitReasonAccountingTest.theCounterAndTheTelemetryEventSeeTheSameReason` | the counter and `TelemetryEvents.onCommit` see one reason | same mutation | `The counter must see the SAME reason telemetry saw ==> expected: <1> but was: <0>` |
| `CommitReasonAccountingTest.aNullReasonIsCountedAsUnknownRatherThanDropped` | null → UNKNOWN, still summed | (correctly survives the above mutation) | — |
| `IndexRuntimeWireFormatRegressionTest.commitTotalCarriesOneCumulativeSeriesPerReason` | one cumulative NDJSON series per reason, 3 timer / 1 drain | `commitTotal.increment` gated to DRAIN only | `commit_total must report 3 for reason=timer` |
| `test_commit_by_reason_maxes_per_reason_not_across_reasons` (pytest) | per-reason max survives a counter reset; sum is the total | `by_reason[reason] = parsed` (last-wins) | `AssertionError` at the breakdown |
| `test_commit_by_reason_is_null_when_the_worker_publishes_none` | degrades to null, never crashes | — | — |

The asymmetric distributions (3/2/1, 3-vs-1) are deliberate: a counter that incremented once per
reason regardless of how many commits fired, or pooled every reason into one series, passes a
symmetric fixture.

**One test-precision defect was caught by falsification and fixed.** The first version of
`test_commit_by_reason_maxes_per_reason_not_across_reasons` did **not** fail under the
last-wins mutation, because `read_merged` returns records in timestamp order and the fixture's
last sample was also its largest — it passed for the wrong reason. The fixture now includes a
post-restart sample (`timer` 4 → 9 → **2**), where last-wins reports 2 and max reports 9. It
fails under the mutation and passes with it restored.

---

## §C — Post-implementation critical pass

**C.1 Wrong-gate check (item 1).** The changed gate is `stated.sizeBytes() == 0L`. Is `0`
reachable from `EnqueueEntry.stat`? Yes — `Files.size` returns `0` without throwing for an
existing empty file, so the `catch` in `JobQueue.java:71-73` is not on this path. Is the fix on
the path the flake takes? The flake's observed value was `0`, not `-1`, which is only producible
by the successful-stat branch. Gate and symptom match.

**C.2 Blast radius of the fix (item 1).** `EnqueueEntry.stat` has three callers:
`WorkerMethvinWatcher.java:192` (changed), `KnowledgeServerMigrationOps.java:903` and
`GrpcIngestService.java:2154`. The latter two re-enqueue files already settled on disk, where a
`0` is a true fact. The fix is deliberately **watcher-local**, not pushed into the shared
`stat()` helper, so settled-file callers keep recording a real zero.

**C.3 Test precision (item 1).** Would a wrong implementation pass? "Always return
`ofUnknownSize`" passes the race test — and is caught by
`liveEventOnAWrittenFileRecordsItsRealSize` and the 813 pin, both of which failed under exactly
that mutation. "Return `stated` unchanged" passes the two real-size tests and is caught by the
race test. No single mutation passes all three.

**C.4 "All-paths counter" is not literally true.** The brief and
`IndexRuntimeMetricCatalog.java` both describe `commitCount` as the all-paths commit counter. The
census found two durable commits it does not see (§A2). This does **not** invalidate the
attribution — both are one-shot lifecycle events, not rate contributors — but the claim is
overstated in the code comment and is routed as an open item rather than silently relied on.

**C.5 Per-session vs cross-session, restated.** `CommitCounters` inherits `commitCount`'s
lifetime: it lives on `RuntimeSession` and resets on `DeferredRuntime.upgradeWriter`, a
blue/green re-open, or the corruption-recovery rebuild. `index.runtime.commit_total` accumulates
across sessions. On a run that swapped a writer, `sum(commit_by_reason) >= commit_total` — this
is the same 46-vs-114 gap 885 recorded, not a bug in the breakdown. `cadence.py` and the
`RuntimeGaugesSnapshot` javadoc both say so at the point of use; **read the breakdown for
attribution, not as a checksum on the gauge.**

**C.6 Asymmetric lifecycle / stale flags.** `CommitCounters` has no start/stop and no
lifecycle; it is allocated with the session and left for post-close inspection alongside the
other atomics (`RuntimeSession.java:695`). Nothing to unwind.

---

## §E — What floors the commit count: hypothesis and its falsifier

### The finding to explain

885's A3 arm relaxed all three cadence knobs at once — timer 10s→30s, `commit_interval_ms`
10s→30s, `max_docs_before_commit` 1000→5000, all confirmed at ordinal 450 — and commits fell
only **53 → 44 (−17 %)**. Three multiplicative relaxations producing a sub-linear, single-digit
absolute change means the binding constraint is not among them.

### Hypothesis (best supported)

**The floor is held by commits gated on "work happened", not on any interval — and the A3 arm
did not touch a single one of them.** Two families, both from the census:

1. **The enrichment/backfill per-batch commits (rows 20, 21, 22, and 17).**
   `NerBackfillOps.java:133`, `EmbeddingBackfillOps.java:221` and `:471`, and
   `BackfillScheduler.java:281` commit on `processed > 0 || failed > 0` with **no interval and no
   `commitAfterBatch` gate** — one commit per batch, unconditionally. Row 16
   (`CombinedEnrichmentBackfillOps.java:1087`) is throttled only by an **inline literal `5`**,
   which no config key reaches.
   *The decisive detail:* the A3 arm's "tripled the backfill interval" changed
   `justsearch.backfill.commit_interval_ms`, which `LoopPacingPolicy.java:79-82` reads for the
   **primary indexing loop** (rows 11/12) — despite the `backfill` prefix. **No backfill op reads
   it.** So the arm believed it had relaxed backfill commits and had not relaxed any. 885's
   earlier window attributing 61/114 commits to backfill is consistent with this being the
   largest single family.

2. **`INDEXING_LOOP_IDLE` (row 10, `IndexingLoop.java:637`).** Fires on every transition to an
   empty queue with `indexedSinceCommit > 0`, with **no interval whatsoever**. This is also the
   mechanism that makes the timer/buffer relaxation *self-cancelling*, sharpening the diagnosis
   already recorded at `EnvRegistry.java:1291-1306`: deferring the loop's own commits leaves
   `indexedSinceCommit > 0` for longer, so the next drain-to-idle commits anyway. The relaxed
   knobs do not remove commits, they **relabel** them — `INDEXING_LOOP_TIME`/`BUFFER` become
   `INDEXING_LOOP_IDLE` and `TIMER`.

### What the attribution run must show if this is right

* `backfill/*` reasons plus `indexing-loop/idle` together account for the **majority** of
  `commit_by_reason` on the control arm.
* Re-running the A3 arm, the *sum* barely moves while the **mix** shifts: `indexing-loop/time`
  and `indexing-loop/buffer` fall sharply (they are the knobs that were relaxed), and
  `indexing-loop/idle` and/or `timer` rise to absorb them. A large fall in the two relaxed
  reasons with a small fall in the total **is** the self-cancelling mechanism, observed directly
  rather than inferred.
* `backfill/*` counts are **unchanged** between control and A3 — the prediction that most
  distinguishes this hypothesis from "the timer is the ceiling".

Falsifiers: if `timer` alone dominates the control arm, hypothesis (2) is wrong and the timer is
the ceiling after all. If `backfill/*` *does* fall in the A3 arm, then something does read
`commit_interval_ms` that this census missed, and the census is wrong.

### Exact live invocation (orchestrator's to run — NOT run here)

Control arm:

```
cd scripts/jseval && python -m jseval run --start-backend --clean --pipeline --json
```

A3 arm — same command with the three overrides the 885 window used
(`index.commit.timer_interval_ms=30000`, `justsearch.backfill.commit_interval_ms=30000`,
`justsearch.backfill.max_docs_before_commit=5000`), confirmed at ordinal 450 as before.

**Field to read:** in the run's `summary.json`, the `cadence` block:

* `cadence.commit_by_reason` — `{reason: count}`, the attribution (reason strings are
  `CommitReason.wireValue()`: `timer`, `indexing-loop/idle`, `backfill/ner`, …).
* `cadence.commit_by_reason_total` — their sum, cross-session.
* `cadence.commit_total` — the existing per-session gauge, for continuity with the 885 table.

Sanity check before trusting a run: `commit_by_reason` must be non-null. If it is null the
Worker did not publish `index.runtime.commit_total` — that is a wiring failure, not "no commits".

---

## §D — Open items (routed, not fixed here)

1. **`CommitOps.commitWithBuildState` drops attribution.** `CommitOps.java:143-147` calls the
   no-arg `commitAndTrack()`, so the blue/green migration-cutover commit
   (`KnowledgeServerMigrationOps.java:224`) is recorded as `UNKNOWN`. Wants a `CommitReason`
   parameter and a `MIGRATION_CUTOVER` value. Not done here: the call site is in
   `modules/indexer-worker`, outside this lane's ownership.
2. **`KnowledgeServerMigrationOps.java:771` bypasses the funnel** — calls the low-level
   `CommitOps.commit()`, so the switch-buffer DELETE replay skips `commitCount`,
   `pendingDocs.set(0)`, telemetry, and the `CommitCompletedListener` that keeps
   `EmbeddingCompatibilityController`'s fingerprint in sync. Same ownership reason.
3. **`RuntimeSession.java:635` `writer().close()` is an uncounted durable commit**
   (`commitOnClose` defaults true; no production `setCommitOnClose` exists). Owning tempdoc: 885
   item 19 / commit cadence.
4. **`GrpcIngestService.java:1042` and `:1487` commit unconditionally** even when the delete or
   update matched zero documents — spurious `segments_N` writes and `commitCount` increments on
   no-op RPCs. In this lane's module but out of this task's scope; it is a behavioural change to
   two RPCs, not instrumentation.
5. **`CombinedEnrichmentBackfillOps.java:1087`'s batch threshold is an inline `5`** with no
   constant and no config key, so it cannot be included in a cadence arm. If §E's hypothesis
   holds, this becomes a knob worth having.
6. **`justsearch.backfill.commit_interval_ms` / `max_docs_before_commit` are misnamed** — both
   are read by `LoopPacingPolicy` for the primary indexing loop, not by any backfill op
   (`EnvRegistry.java:687-696`). This mislabelling is what made the A3 arm believe it had
   relaxed backfill commits. Renaming is a config-surface change with a deprecation path;
   routed rather than done inside a measurement lane.
7. **`IndexRuntimeMetricCatalog`'s "all-paths commit counter" comment is overstated** given
   items 2 and 3. Left as-is pending those fixes rather than weakened.
8. **`--gate operation-surface` is RED on `origin/main`** (base `bff70561`), independently of
   this branch: `operation-surface/undeclared-surface` —
   `modules/ui-web/src/shell-v0/state/indexingJobStates.ts` references the canonical
   `IndexingJobView` lifecycle type but is not registered in
   `governance/operation-surfaces.v1.json`. That file was last touched by the base commit
   itself (PR #603) and is not in this branch's diff. Not pinned in
   `expected-state.v1.json` here because lane R5 owns that file this wave; routed to the
   orchestrator to pin + assign.

---

## Report-back

* **Item 1 — done.** Watcher fixed at the encoding, not with a timing budget; three tests, each
  falsified; 5/5 consecutive clean runs. Lane R5's `expected-state.v1.json` pin can be removed
  once this merges (that file is untouched here, as briefed).
* **Item 2 — instrumented, hypothesis stated, live run deferred.** The census is complete (23
  reachable triggers + 2 bypasses). The enum already existed; the per-reason **count** is new and
  rides the existing counter rather than forking it. Six tests, five falsified (one revealed a
  real precision defect in its own fixture, now fixed).
* **The measurement is deliberately not run here** — the dev-stack lease is the orchestrator's.
  §E gives the invocation, the field to read, and the predictions that would confirm or refute
  the hypothesis before the numbers are seen.
