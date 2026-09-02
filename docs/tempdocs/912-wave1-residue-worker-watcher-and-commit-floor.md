---
title: "Wave-1 residue, Worker lane: the watcher's create-event size race, and what actually floors the commit count"
type: tempdocs
status: "MEASURED (2026-09-02) — items 1+2 merged in #612 (33ffc3bb); live attribution run recorded in §E-live: the commit floor is BackfillScheduler's hardcoded 5 s CYCLE_BUDGET_MS, 70 % of commits are backfill/combined-final. The fix is open item 8, not done here."
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
message. The test that produced 885's quoted failure is
`createEventCarriesTheFilesRealSizeToTheQueue`. Corrected here; 885's text is dated history and is
not edited.

**A second-order correction, measured on review.** "`createEventCarriesTheFilesRealSizeToTheQueue`
is the failing test" describes the *pre-fix* code only. Under the fix reverted
(`entryForLiveEvent` → `return stated;`), the only test that fails is the NEW
`liveEventOnAStillEmptyFileRecordsUnknownSizeNotAKnownZero` — because the rewritten move-in
fixture makes the 813 pin deterministic, so it no longer observes the race at all. The race is
now pinned by the synthetic-event test and by nothing else, which is the intended split: one test
owns the race, one owns the call-site size contract.

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

### B.3a The consumer asymmetry — corrected on review, and its UX consequence

An earlier draft of the fix's javadoc said the two encodings "differ only in that unknown also
admits the weight is unvouched". **That is wrong, and the difference is the point.**
`unknownSizeJobs` is a hard **suppression input**, not a footnote:

* `modules/ui-web/src/shell-v0/state/indexingProgress.ts:523` — `if (unknownSizeJobs * 2 >
  jobsPending) return null;` — the byte weight is withdrawn entirely.
* `TaskList.ts:95` renders `null` as an absent segment: "N files remaining", no byte figure.

**Consequence, accepted deliberately — and it is conditional, not automatic.** A CREATE now
lands as UNKNOWN, so a copy-in whose in-flight CREATEs come to outnumber half the pending backlog
trips `unknownSizeJobs * 2 > jobsPending` and the UI drops the byte figure. A **sequential** copy
against a deep existing backlog stays under the ratio and keeps its estimate. The trigger is the
ratio, not "a copy is happening" — an earlier draft stated it unconditionally.

**How long the unknown window lasts.** Nothing re-stats at processing time — `size_bytes` is
written **only** by the enqueue path (`SqliteJobQueue.java:392` and `:473`) — so a row is healed
by the next watcher event for that path (`INSERT OR REPLACE`, `SqliteJobQueue.java:390-393`), not
by a later read. Two cases make that window longer than an earlier draft's "a few seconds":

* `FileHasher.LAST_MODIFIED_TIME` (`WorkerMethvinWatcher.java:139`) suppresses a MODIFY whose
  mtime equals the recorded one, and Windows updates an open file's mtime lazily — typically at
  close. For a large copy the healing MODIFY therefore tends to arrive when the copy **finishes**,
  so the unknown window is roughly the file's whole copy duration.
* If no further event ever arrives — a file created empty and left empty, or a create+write
  landing inside one mtime tick — the row keeps UNKNOWN for as long as it stays PENDING or
  PROCESSING, i.e. until it is indexed and leaves the aggregate entirely. There is no later
  correction.

Both are accepted, and the reason is the same in each: an unknown that persists is still a true
statement about what this producer observed, whereas the known `0` it replaces was false for that
entire window.

This is judged **right, and 813-consistent**, not a regression. `TaskList.ts:95`'s own contract is
that each figure "is withdrawn ENTIRELY (no placeholder, no '0 B') whenever the projection says it
has no honest basis for it — so an absent estimate is an absent segment." Withholding a number
during the exact window where the number would be wrong is that rule operating as designed; the
old behaviour showed a confident figure understating a mid-write backlog by gigabytes. The
trade is "no estimate for as long as the copy runs" against "a confidently wrong estimate over
that same window", and 813 already decided which of those it prefers. **Decision recorded
(2026-09-02): the unknown-size encoding is KEPT** — reviewer and orchestrator concur that an
absent segment beats a confidently wrong figure, per 813.

**A second asymmetry, deliberately left in place.** The bulk-walk producers record a known `0`
for a genuinely empty file (`WorkerScanOps.java:221`, `SyncDirectoryOps.java:317`, both from
`attrs.size()` on a settled file), so one empty file is encoded two ways depending on which
producer found it. That tracks how trustworthy the observation was — a walk's `attrs` are not
racing a writer, a live CREATE is — which is the distinction worth keeping. Unifying them would
mean either trusting the racy read or distrusting the settled one.

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
(`modules/adapters-lucene/.../CommitOps.java:155`, counter at `:163`). Guards quoted verbatim.

| # | Reason | Call site | Guard | Interval / threshold |
|---|---|---|---|---|
| 1 | `TIMER` | `adapters-lucene/.../CommitOps.java:388` | `pendingDocs.get() > 0 && timerSnap != null && timerSnap.writer() != null` | `INDEX_COMMIT_TIMER_INTERVAL_MS` (`EnvRegistry.java:1314`) **10000 ms** |
| 2 | `DRAIN` | `adapters-lucene/.../RunningRuntime.java:207` | `pendingDocs.get() > 0 && commitOps != null` | none (event) |
| 3 | `PRUNE` | `adapters-lucene/.../PruneOps.java:139` | `pruned > 0` | none |
| 4 | `SYNC_PRUNE` | `worker-services/.../SyncDirectoryOps.java:127` | `filesDeleted > 0 && commitOps != null` | none |
| 5 | `GRPC_DELETE_BY_PATH` | `worker-services/.../GrpcIngestService.java:930` | unconditional post-delete | none |
| 6 | `GRPC_DELETE_BY_COLLECTION` | `GrpcIngestService.java:981` | unconditional | none |
| 7 | `GRPC_DELETE_BY_ID` | `GrpcIngestService.java:1042` | unconditional — **commits even when 0 docs matched** | none |
| 8 | `GRPC_UPDATE_PATHS` | `GrpcIngestService.java:1487` | unconditional — commits even if every mapping failed | none |
| 9 | `RESET` | `GrpcIngestService.java:1926` | unconditional inside `resetForProfiling` | none |
| 10 | `INDEXING_LOOP_IDLE` | `worker-services/.../loop/IndexingLoop.java:637` | `indexedSinceCommit > 0`, reached only when `jobs.isEmpty()` (`:621`) | **none — fires on every drain-to-idle** |
| 11 | `INDEXING_LOOP_TIME` | `IndexingLoop.java:695` (ONE site; ternary at `:693` picks the reason) | `timeSinceCommitMs >= commitIntervalMs && indexedSinceCommit > 0` (`loop/ops/LoopPacingPolicy.java:79`) | `BACKFILL_COMMIT_INTERVAL_MS` (`EnvRegistry.java:692`) **10000 ms** |
| 12 | `INDEXING_LOOP_BUFFER` | `IndexingLoop.java:695` (same site as row 11) | `indexedSinceCommit >= maxDocsBeforeCommit` (`loop/ops/LoopPacingPolicy.java:88`) | `BACKFILL_MAX_DOCS_BEFORE_COMMIT` (`EnvRegistry.java:702`) **1000 docs** |
| 13 | `INDEXING_LOOP_SHUTDOWN` | `IndexingLoop.java:793` | `indexedSinceCommit > 0` | none |
| 14 | `INDEXING_LOOP_REBUILD_STAMP` | `worker-services/.../loop/EmbeddingProviderLifecycle.java:360` | TWO gates: `:349` `if (!oneShot && ++pendingZeroStreak < 2)` — the 2-read debounce is **skipped entirely when `oneShot`** — then `:354` `if (!controller.checkRebuildCompletion(queueDepth, pendingEmbeddings))` | none |
| 15 | `INDEXING_LOOP_FRESH_STAMP` | `loop/EmbeddingProviderLifecycle.java:466` | `:457` `reconcileStampEvidence()` + `:461` backoff window | `FRESH_STAMP_RETRY_BACKOFF_MS` (same file) |
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

### Four durable commits the counter does not see

An earlier draft of this section said "two". That was 2-of-4 — corrected on review, and the
count now has a gate behind it (`CommitFunnelArchTest`, below) so it cannot silently become
five.

* `KnowledgeServerMigrationOps.java:771` calls the **low-level** `CommitOps.commit()` (guard
  `mutatedLucene && context.ingestLifecycle() != null`, `:769`), bypassing `commitCount`,
  `pendingDocs.set(0)`, `TelemetryEvents.onCommit` and the `CommitCompletedListener`.
* `RuntimeSession.java:640` `snap.writer().close()` — Lucene's `commitOnClose` defaults to true
  and there is no production `setCommitOnClose` call anywhere, so session teardown commits
  uncounted whenever `pendingDocs == 0` skipped the `DRAIN` pre-commit (`RunningRuntime.java:204`).
* `RuntimeSession.java:806` `w.commit()` inside `materializeEmptyIndex` (`:799`) — a scratch
  `IndexWriter` in a try-with-resources, so it commits twice over (explicitly, then again on the
  generated `close()` at `:801-807`). It creates the empty index that lets the Head report
  `indexAvailable`, and no session exists yet to count it against.
* `ComponentsFactory.java:378` `if (w != null) w.close()` on the open-failure cleanup branch —
  same implicit commit-on-close, on a path where the session being built never materialised.

All four are lifecycle one-shots, not rate contributors, so the attribution in §E is unaffected.
Two are in this lane's modules but are structural (they want a `CommitReason` on a lifecycle
path); two are in `modules/indexer-worker` / factory teardown, outside this lane's ownership.
All are routed as open items (§D) rather than fixed here.

### The census is now an invariant, not a dated audit

`CommitFunnelArchTest` (`modules/adapters-lucene/src/test/.../CommitFunnelArchTest.java`) is
two ArchUnit rules over this module's bytecode:

1. No class outside `CommitOps` / `RuntimeSession` / `ComponentsFactory` may call
   `IndexWriter.commit()` or `IndexWriter.close()`. The allowlist is by class, with the reason
   each is on it written next to the constant — adding a name is a deliberate statement that the
   commit is invisible to the attribution and that this is acceptable there.
2. No class outside `CommitOps` may call the low-level `CommitOps.commit()`, so the funnel stays
   the only in-module route to a counted commit.

A static census is a hypothesis about a moment (`audit-without-test`); this is what keeps it
true. Scope limit, stated rather than implied: the importer sees only this module, so the
`KnowledgeServerMigrationOps` bypass in `modules/indexer-worker` is out of its reach and remains
a routed open item (§D.2) rather than a covered case.

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
  (tagged-counter shape copied from the existing `VALIDATION_FAILURE_TOTAL`, which does
  **not** set a cardinality limit; the `cardinalityLimit(32)` follows `AgentMetricCatalog.java:116`,
  and 32 covers the 23 reasons),
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
| `CommitReasonAccountingTest.aMixedCommitSequenceLandsInPerReasonSlotsAndNowhereElse` | 3 TIMER / 2 IDLE / 1 NER land in their own slots; `snapshot()` equals exactly that map | `CommitCounters.increment` → always `UNKNOWN.ordinal()` | `TIMER must count its own three commits ==> expected: <3> but was: <0>` |
| `CommitReasonAccountingTest.theCounterAndTheTelemetryEventSeeTheSameReason` | the counter and `TelemetryEvents.onCommit` see one reason | same mutation | `The counter must see the SAME reason telemetry saw ==> expected: <1> but was: <0>` |
| `CommitReasonAccountingTest.aNullReasonIsCountedAsUnknownRatherThanDropped` | null → UNKNOWN, still summed | (correctly survives the above mutation) | — |
| `IndexRuntimeWireFormatRegressionTest.commitTotalCarriesOneCumulativeSeriesPerReason` | one cumulative NDJSON series per reason, 3 timer / 1 drain | `commitTotal.increment` gated to DRAIN only | `commit_total must report 3 for reason=timer` |
| `test_commit_by_reason_maxes_per_reason_not_across_reasons` (pytest) | per-reason max survives a counter reset; sum is the total | `by_reason[reason] = parsed` (last-wins) | `AssertionError` at the breakdown |
| `test_commit_by_reason_is_null_when_the_worker_publishes_none` | degrades to null, never crashes | — | — |
| `CommitFunnelArchTest.onlyTheFunnelAndTheNamedLifecycleSitesTouchIndexWriterCommitOrClose` | the 4-site bypass census is an invariant | a scratch `w.commit()` added to `PruneOps` | `Architecture Violation … was violated (1 times): Method <io.justsearch.adapters.lucene.runtime.PruneOps.scratchBypassForFalsification(org.apache.lucene.index.IndexWriter)>` |
| `CommitFunnelArchTest.theLowLevelCommitIsReachedOnlyFromTheFunnel` | `CommitOps.commit()` has one in-module caller | (same scratch mutation) | — |

The asymmetric distributions (3/2/1, 3-vs-1) are deliberate: a counter that incremented once per
reason regardless of how many commits fired, or pooled every reason into one series, passes a
symmetric fixture.

**Two test-precision defects were caught and fixed.** The first was found on review: the
per-reason test's `snapshot()` assertion summed the map and compared it to `counters.get()`,
which merely restates `CommitCounters.get()`'s own definition — vacuous. It now asserts the whole
map (`{TIMER:3, INDEXING_LOOP_IDLE:2, BACKFILL_NER:1}`), so a snapshot that dropped or invented a
reason fails. The test was also renamed to `aMixedCommitSequenceLandsInPerReasonSlotsAndNowhereElse`,
since total-equals-sum is structural in `CommitCounters` and not what the test can pin.

**The second was caught by falsification.** The first version of
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
census found **four** durable commits it does not see (§A2) — an earlier draft of this section
said two, which was 2-of-4. This does **not** invalidate the attribution: all four are one-shot
lifecycle events, not rate contributors. The overstated comment is now corrected in place
(`IndexRuntimeMetricCatalog.java`) and the four sites are pinned by `CommitFunnelArchTest`, so a
fifth fails the build rather than quietly widening the gap.

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
   `commitAfterBatch` gate** — one commit per batch, unconditionally. Row 16 (call at
   `CombinedEnrichmentBackfillOps.java:1090`, guard at `:1087`) is throttled only by an **inline
   literal `5`**, which no config key reaches.
   *The decisive detail:* the A3 arm's "tripled the backfill interval" changed
   `justsearch.backfill.commit_interval_ms`, which `loop/ops/LoopPacingPolicy.java:79` reads for the
   **primary indexing loop** (rows 11/12) — despite the `backfill` prefix. **No backfill op reads
   it.** So the arm believed it had relaxed backfill commits and had not relaxed any. 885's
   earlier window attributing 61/114 commits to backfill is consistent with this being the
   largest single family.

2. **`INDEXING_LOOP_IDLE` (row 10, `IndexingLoop.java:637`) contributes AT MOST one commit per
   drain-to-idle, and only for a drain that indexed at least one doc since the last commit.**
   The guard at `:635` is `indexedSinceCommit > 0`, and `:640` sets `indexedSinceCommit = 0`
   immediately after the commit — so on every subsequent idle iteration the guard is false and no
   further IDLE commit fires. It is one commit per *drain*, not a per-iteration idle tick.

   Crucially the TIME/BUFFER path resets the same variable at `:703`, so a drain in which
   TIME or BUFFER already committed reaches idle with `indexedSinceCommit == 0` and yields
   **zero** IDLE commits. The two paths share one counter.

   **This corrects an earlier draft of this section twice over.** That draft claimed IDLE
   "absorbs" the relaxed TIME/BUFFER commits one-for-one — it cannot, because the `:640` reset
   caps IDLE at one per drain no matter how much work accumulated. The correction to that draft
   then over-shot in the other direction, calling the floor "invariant to the interval knobs" and
   saying a workload draining N times "commits at least N times". Both are wrong: the bound is an
   **upper** one (at most N, not at least N), and it is **not** knob-invariant — relaxing the
   knobs moves a given drain's IDLE contribution from 0 to 1, because suppressing that drain's
   TIME/BUFFER commit is exactly what leaves `indexedSinceCommit > 0` at idle.

   So the honest statement is a **weak, bounded** version of the relabelling idea: relaxing the
   knobs can convert a drain's TIME/BUFFER commit into an IDLE commit, but the total IDLE
   contribution is bounded above by the number of drains that indexed work — not by the number of
   commits the knobs suppressed. A workload with few, long drains cannot be floored high by this
   path; a workload with many short drains can.

   The `EnvRegistry.java:1300-1315` absorption argument is separately inapplicable to A3 as run:
   it was written for a window where the safety-net timer was hardcoded at 10 s, and A3 moved the
   timer to 30 s, so the remaining trigger was not being handed work on the cadence the argument
   assumes.

### What the attribution run must show — three distinct outcomes

The predictions are written so the three candidate explanations score differently. Read the
CONTROL arm first: it discriminates before any comparison is needed.

| Observation | (1) Backfill-dominated | (2) Idle floor | (3) Knobs were INERT |
|---|---|---|---|
| Control: `backfill/*` share | **majority** | minority | minority |
| Control: `indexing-loop/idle` share | minority | **majority** | either |
| Control: `indexing-loop/time` + `/buffer` | non-trivial | non-trivial | **≈ 0** |
| A3 vs control: `time` + `/buffer` | falls | falls | **already ≈ 0, cannot fall** |
| A3 vs control: `idle` | ~flat or weakly up | **up**, bounded above by the count of work-indexing drains | ~flat (nothing was suppressed to convert) |
| A3 vs control: `backfill/*` | **unchanged** | unchanged | unchanged |
| A3 vs control: total | small fall | small fall | **~no change attributable to the knobs** |

* **Outcome 3 is the one the earlier draft could not express.** If `indexing-loop/time` and
  `indexing-loop/buffer` are already near zero on the *control* arm, then the A3 arm relaxed
  triggers that were barely firing — the knobs were **inert**, not absorbed — and the 17 %
  movement 885 measured is noise or attributable to something else entirely. That is a different
  claim from "the commits moved elsewhere", and the control arm alone settles it.
* Outcomes 1 and 2 are not exclusive: both predict a small total fall, and they are separated by
  which family holds the majority share on the control arm. Outcome 2 is additionally the only
  one predicting `indexing-loop/idle` to **rise** between arms — if `idle` is flat while `time` +
  `/buffer` fall, the suppressed commits did not reappear as IDLE and the fall should show up in
  the total instead.

Falsifiers, sharpened: if `timer` alone dominates the control arm, none of the three holds and
the safety-net timer is the ceiling after all. If `backfill/*` *does* fall between control and
A3, then something reads `commit_interval_ms` that this census missed — the census is wrong and
`CommitFunnelArchTest`'s allowlist should be re-derived.

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

## §E-live — Live attribution (2026-09-02)

The run §E specified, executed by the orchestrator from this worktree's jseval after #612 merged
(`33ffc3bb`). **This section answers §E's open question: the floor is the backfill scheduler's
hardcoded 5-second cycle budget.**

### Run conditions

`scifact`, 5184 docs, `--clean --pipeline`, single Worker start, encoders on GPU, GPU idle before
start, no game running, **no search load** and no first-search probe. All four enrichment stages
completed: chunk 215 s, ner 226 s, splade 276 s. `total_elapsed` 278.7 s, 18.0 docs/s.
Log: `scripts/jseval/tmp/912-attribution.log`.

### Numbers (verbatim from the run's `cadence` block)

```
reopen_total              243
commit_total               69
commit_by_reason_total     69
commit_by_reason:
  backfill/combined-final  48
  indexing-loop/buffer      6
  backfill/combined         5
  backfill/splade           4
  timer                     3
  indexing-loop/idle        1
  indexing-loop/time        1
  indexing-loop/fresh-stamp 1
```

Cross-checked independently by parsing per-reason max out of
`tmp/headless-eval-data/telemetry/metrics-worker.ndjson`: identical, and max `commit_count` 69.

**The sum invariant held live.** `commit_by_reason_total == commit_total == 69` — the structural
property `CommitReasonAccountingTest` pins in unit tests, confirmed end-to-end on a real run.
(§C.5 predicts `sum >= commit_total`, with equality only when no session swap occurred; equality
here says this run used one session throughout, consistent with the single Worker start.)

### Caveat on throughput — the 18.0 vs 86.3 comparison is not like-for-like

885's arm table (885 line 3759) has **two** throughput columns:
`| Arm | Config | overall docs/s | primary docs/s | ... |`, and its A1 control reads
**overall 15.6**, **primary 86.3**. This run's 18.0 is an *overall* figure
(5184 / 278.7 s = 18.6), so it belongs against 885's **15.6**, not against 86.3. On that
comparison the run is ~15 % *faster* than the A1 control, and `total_elapsed` 278.7 s against
A1's 322.7 s is ~14 % shorter — both in the same direction, and both consistent with a verified
difference in run profile: 885's arms all ran `--search-load-qpm 10 --first-search-probe`
(885 §Method), this run had no foreground query load at all. Same corpus and same command shape
otherwise (885 used scifact, 5183 BEIR docs, `--dataset scifact --max-queries 0 --pipeline
--clean --start-backend`).

Not verified, so not claimed: whether the residual difference is the absent search load alone, JIT
state, or run-to-run variance. **n = 1**, and 885's own honest-limits note records a ±10 %
run-to-run band on the throughput columns — no throughput delta here should be read as an effect.

### Scoring the three-outcome table

| Prediction | Observed | Verdict |
|---|---|---|
| Control: `backfill/*` share | 57/69 = **83 %** (`combined-final` 48 alone = **70 %**) | **Outcome (1) HOLDS** |
| Control: `indexing-loop/idle` share | 1/69 = 1.4 % | Outcome (2) rejected as the *dominant* family |
| Control: `indexing-loop/time` + `/buffer` | 1 + 6 = **7**, not 0 | **Outcome (3) rejected — the knobs were NOT inert** |
| `indexing-loop/idle` at most one per work-indexing drain | **1** | Consistent with §E item 2's corrected bound |

* **Outcome (1) — backfill-dominated — holds, and more sharply than predicted.** The prediction
  was that `backfill/*` would be the majority; it is 83 %, and a *single* reason
  (`backfill/combined-final`) is 70 % of every commit in the run.
* **Outcome (3) is rejected on the control arm alone**, exactly as §E said it could be:
  `indexing-loop/time` + `/buffer` = 7 is small but not zero, so A3's knobs were relaxing triggers
  that do fire. They are simply a 10 % minority of the total, which is why tripling them moved the
  count so little.
* **The idle bound is confirmed.** `indexing-loop/idle` = 1 over the whole run. §E item 2's
  corrected claim — at most one per drain-to-idle, and only for a drain that indexed work since
  the last commit, because `IndexingLoop.java:703` resets the shared counter when TIME/BUFFER
  already committed — predicts a small number, and 1 sits at the bottom of that range. The
  earlier, retracted "IDLE absorbs the relaxed commits one-for-one" claim would have predicted a
  large IDLE count; it is decisively wrong.

### What `backfill/combined-final` is, and what fragments it

**Emission site.** `BackfillScheduler.java:280-281`:

```java
if (batchCommitCounter[0] > 0) {
  commitOps.commitAndTrack(CommitReason.BACKFILL_COMBINED_FINAL);
}
```

This sits at the end of the `commitOps.withNrtSuspended(...)` lambda opened at `:241`, so it runs
**once per cycle**, not once per batch — one durable commit for every backfill cycle that did any
batch work.

**What a "cycle" is, and what ends one.** The enclosing method is
`BackfillScheduler.runIdleCycle()` (`:188`), called from the indexing loop's idle branch at
`IndexingLoop.java:651`. It arms a deadline on entry (`:191-192`):

```java
final long cycleDeadlineNanos =
    cycleStartNanos + TimeUnit.MILLISECONDS.toNanos(CYCLE_BUDGET_MS);
```

with `CYCLE_BUDGET_MS = 5_000L` (`:63`) — **a hardcoded 5-second constant, reachable by no config
key and therefore by none of A3's three knobs.** The tight loop at `:243` exits on any of six
conditions: not running / interrupted (`:244`), `signalBus.shouldYieldGpuBackfill()` (`:247`,
tempdoc 630), `signalBus.hasPendingIngest()` (`:250`, tempdoc 798), the cycle deadline (`:251`),
the batch making no progress (`:265`, `useCombinedRef[0] = tightLoopOutcome.progressed()`), and
`tightLoopOutcome.aborted()` (`:275`, tempdoc 809 finding 3). **Every one of those paths falls
through to the `:280` commit.**

**Is the tempdoc-809 early yield the fragmenting mechanism? Partly — but it is not the clock.**
The 18 `worker.log` lines come from `CombinedEnrichmentBackfillOps.java:1130-1136`, emitted when
`aborted` is true, and they map to the `:275` break. Eighteen is at most 18 of the ~48 cycle-ends,
so the 809 yield accounts for **at most 37 %** of them and cannot by itself produce 48 commits.

**The arithmetic points at the cycle budget instead.** A productive cycle costs at most the 5 s
budget plus the inter-cycle sleep, which is `ACTIVE_IDLE_SLEEP_MS = 100 ms` while backfill is
doing work (`LoopPacingPolicy.java:9`, selected at `IndexingLoop.java:663-666`) — so about 5.1 s
per cycle. The enrichment window is about 276 s (splade, the last stage, completes there), giving
a ceiling of about 54 cycles. **Observed: 48.** The commit count sits at 89 % of the ceiling the
budget sets, and 48 x 5.1 s = 245 s covers 89 % of the enrichment window.

So the mechanism that floors the commit count is: **enrichment is chopped into ~5 s cycles by
`CYCLE_BUDGET_MS`, and every cycle that touched a document ends in a durable commit.** The 809
early-yield and the other four break conditions determine *which* cycles end early; the budget
determines *how many cycles there are*. That is why three multiplicative relaxations of the
timer/interval/buffer knobs bought only 17 % in 885's A3 — none of them is on this path.

Honest limit: 48 against a 54 ceiling is a strong fit, but it is one run, and the per-cycle
overhead is inferred from the constants rather than measured per cycle. The experiment below
discriminates directly rather than resting on the arithmetic.

### Actionable hypothesis and the next experiment

**Hypothesis.** The per-cycle `combined-final` commit is durability bookkeeping for a cycle
boundary that exists for *pacing* reasons, not durability ones. A cycle that yielded early with
work still pending has no reason to force a durable commit — the same documents are re-entered
next cycle. Committing on **backfill-queue drain** (enrichment genuinely finished), and otherwise
letting the safety-net `TIMER` cover the interval, would keep durability bounded while removing
the coupling between pacing granularity and commit count.

**Predicted effect if the hypothesis holds.** `backfill/combined-final` falls from 48 to the
number of genuine drains (single digits); `timer` rises by roughly `enrichment_window /
timer_interval` (about 276 s / 10 s = 27 at the default, fewer at 30 s). Net commit count lands
around 30-45 rather than 69 — a larger reduction than A3's 17 %, and from the trigger that
actually holds the floor. If instead the count barely moves, the cycle-budget mechanism is wrong,
and the `backfill/combined` every-5-batches path
(`CombinedEnrichmentBackfillOps.java:1087`, commit at `:1090`) or the individual stage commits are
carrying more than this run showed.

**Risk to first-search freshness (885's read rule).** Expected low, but it is the thing to
measure rather than assume: what publishes enrichment to searchers is an NRT **reopen**, not a
commit, and `reopen_total` (243) is an independent counter. NRT is suspended for the duration of
the tight loop (`withNrtSuspended`, `BackfillScheduler.java:241`, tempdoc 334 Phase 8) and resumes
when the lambda returns, so the publishing event is the reopen after resume, which this change
does not touch. The genuine exposure is **durability**, not freshness: a crash mid-enrichment
would lose up to one drain's worth of enrichment writes instead of one cycle's, and that work
would be re-derived on the next backfill pass. 885's read rule still governs the arm — reject if
reopen count drops but first-search p95 regresses > 20 %; accept only if throughput is within
10 % **and** first-search p95 is not worse.

**Exact arm to run** (the change needs a config key, which is not in scope here):

```
python -m jseval run --dataset scifact --max-queries 0 --pipeline --clean --start-backend \
  --search-load-qpm 10 --first-search-probe --json
```

Read from the `cadence` block: `commit_by_reason` (specifically `backfill/combined-final` and
`timer`), `commit_total`, `reopen_total`, and `first_search_after_indexing.latency_ms.p95`.

**Run the control with the same flags as the arm.** This attribution run had no search load, so it
is *not* a valid control for a first-search-p95 comparison; it is a valid control only for the
commit attribution.

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
3. **`RuntimeSession.java:640` `writer().close()` is an uncounted durable commit**
   (`commitOnClose` defaults true; no production `setCommitOnClose` exists). Owning tempdoc: 885
   item 19 / commit cadence.
3b. **`RuntimeSession.java:806` `materializeEmptyIndex` commits twice uncounted** — explicit
   `w.commit()` plus the try-with-resources `close()` at `:801-807`. Both are allowlisted in
   `CommitFunnelArchTest`; wants a lifecycle `CommitReason` rather than an allowlist entry.
3c. **`ComponentsFactory.java:378` open-failure `w.close()`** — implicit commit-on-close on a
   path where no session exists to count against. Same disposition as 3b.
4. **`GrpcIngestService.java:1042` and `:1487` commit unconditionally** even when the delete or
   update matched zero documents — spurious `segments_N` writes and `commitCount` increments on
   no-op RPCs. In this lane's module but out of this task's scope; it is a behavioural change to
   two RPCs, not instrumentation.
5. **`CombinedEnrichmentBackfillOps.java:1087`'s batch threshold is an inline `5`** (commit at `:1090`) with no
   constant and no config key, so it cannot be included in a cadence arm. If §E's hypothesis
   holds, this becomes a knob worth having.
6. **`justsearch.backfill.commit_interval_ms` / `max_docs_before_commit` are misnamed** — both
   are read by `loop/ops/LoopPacingPolicy` for the primary indexing loop, not by any backfill op.
   This mislabelling is what made the A3 arm believe it had relaxed backfill commits.
   **Ride-along fix applied:** the javadocs at `EnvRegistry.java:692` and `:702` and the two rows
   at `docs/reference/configuration/environment-variables.md:169-170` now say so explicitly (the
   page is hand-maintained — no generator — so the source and the page were both edited). The
   **rename** stays the open item: it is a config-surface change needing a deprecation path.
7. ~~**`IndexRuntimeMetricCatalog`'s "all-paths commit counter" comment is overstated**~~ —
   **fixed in place** (`IndexRuntimeMetricCatalog.java`): the comment now names the four
   funnel-bypassing commits and states that `COMMIT_TOTAL` is a second *projection* of the one
   authority, not the second authority the original sentence rules out.
8. **The commit floor itself: `BackfillScheduler`'s per-cycle `combined-final` commit.**
   Measured in §E-live as 48 of 69 commits (70 %). `CYCLE_BUDGET_MS = 5_000L`
   (`BackfillScheduler.java:63`) chops enrichment into ~5 s cycles and `:280-281` commits at the
   end of every cycle that touched a document, so commit frequency is bound to *pacing*
   granularity rather than to any durability requirement. Proposed change and its predicted
   effect, risk and measurement arm are in §E-live; it needs a config key to be A/B-able, so it is
   a change, not a knob, and is out of this lane's scope. **This is the actionable successor to
   885's A3 finding** — the arm 885 wanted and could not aim.
9. **`CYCLE_BUDGET_MS` is an inline constant with no config key** (`BackfillScheduler.java:63`),
   like `CombinedEnrichmentBackfillOps.java:1087`'s inline `5` (open item 5). Neither can be moved
   in a measurement arm, which is why 885's A3 could relax three knobs and still miss the binding
   trigger. Making both configurable is the cheap enabler for open item 8's experiment.
10. **`--gate operation-surface` is RED on `origin/main`** (base `bff70561`), independently of
   this branch: `operation-surface/undeclared-surface` —
   `modules/ui-web/src/shell-v0/state/indexingJobStates.ts` references the canonical
   `IndexingJobView` lifecycle type but is not registered in
   `governance/operation-surfaces.v1.json`. That file was last touched by the base commit
   itself (PR #603) and is not in this branch's diff. Not pinned in
   `expected-state.v1.json` here because lane R5 owns that file this wave; routed to the
   orchestrator to pin + assign.
11. **`BatchUpdateIntegrationTest.concurrentRmwOnSameDocIdSerializedByCoordinator_402`
    (`modules/adapters-lucene/src/test/java/io/justsearch/adapters/lucene/runtime/BatchUpdateIntegrationTest.java:562`)
    is load-flaky under a whole-repo `./gradlew test` only.** Observed 2026-09-02 on PR #613's
    full-suite run after merging `main` at `31a26b0d`: red under the full run, green isolated
    (12/12, `:modules:adapters-lucene:test --tests
    "*BatchUpdateIntegrationTest.concurrentRmwOnSameDocIdSerializedByCoordinator_402*"`) and green
    for the whole module (622/622, `:modules:adapters-lucene:test`). Pinned as
    `adapters-lucene-batchupdate-rmw-coordinator-load-flake` in
    `scripts/agent-analytics/expected-state.v1.json` (`added: 2026-09-02`,
    `reviewBy: 2026-09-30`, `exitProbeOmitted` — it passes in isolation, so a probe would report a
    false GONE; `fixOwner: "tempdoc 912 open item (lane R7 successor)"`).

    **Whether #612 altered timing on this path, checked rather than assumed.** `git log -3
    --oneline -- <the test file>` shows its three most recent touches are `967f94bf` (798
    ingest-livelock fix), `a8b24b2a` (742 residue sweep) and `4e9a17fa` (711 RMW field
    preservation) — **#612 (`33ffc3bb`) never touched the test file itself**, and `git show
    33ffc3bb --stat` confirms it also never touched `IndexingCoordinator.java`, the class under
    test. But `33ffc3bb` did touch two files in the same `adapters.lucene.runtime` package the
    test exercises directly: `CommitOps.java` (17 lines changed per `git show 33ffc3bb --stat`,
    the new `CommitCounters` accounting) and `RuntimeSession.java` (7 lines changed) — and the
    test's racing loop calls
    `runtime.commitOps().commitAndTrack()` / `.maybeRefreshBlocking()` once per iteration
    (`:591-592`) inside the same 50-iteration loop that races the two coordinator threads. So a
    timing-impact hypothesis is plausible (the commit path the loop calls every iteration changed
    shape) but not confirmed — it is equally consistent with a pre-existing starvation artefact
    that any full-suite load surfaces, the same shape as the `WatchedRootScanCollectionTest` and
    `InferenceLifecycleManagerExternalServer` pins already in `expected-state.v1.json`. Deciding
    between the two needs either a targeted load-repro (run this test alongside a concurrent
    Gradle lane, pre- and post-#612) or a next full-suite sighting to see whether the flake
    persists past `33ffc3bb` — neither is done here.

---

## Report-back

* **Item 1 — done.** Watcher fixed at the encoding, not with a timing budget; three tests, each
  falsified; 5/5 consecutive clean runs. Lane R5's `expected-state.v1.json` pin can be removed
  once this merges (that file is untouched here, as briefed).
* **Item 2 — instrumented, measured, and answered.** The census is complete (23 reachable
  triggers + 4 bypasses, now pinned by `CommitFunnelArchTest`). The enum already existed; the
  per-reason **count** is new and rides the existing counter rather than forking it. Eight tests,
  six falsified (one revealed a real precision defect in its own fixture, one a vacuous
  assertion — both fixed).
* **The measurement ran and the floor is identified (§E-live).** Predictions were written before
  the numbers were seen; **outcome (1), backfill-dominated, holds** — `backfill/*` is 83 % of
  commits and `backfill/combined-final` alone is 70 %. Outcome (3) is rejected on the control arm
  (the knobs were not inert, just a 10 % minority), and the corrected once-per-drain IDLE bound is
  confirmed (`indexing-loop/idle` = 1).
* **The floor is `BackfillScheduler.CYCLE_BUDGET_MS`** — a hardcoded 5 s pacing constant no config
  key reaches, which chops enrichment into ~5 s cycles each ending in a durable commit
  (`BackfillScheduler.java:63`, `:191-192`, `:280-281`). The tempdoc-809 early yield contributes
  at most 18 of ~48 cycle-ends, so it is a break *reason*, not the clock. **That is why 885's A3
  could triple three knobs and move the count only 17 %: none of them is on this path.**
* **The `commit_by_reason_total == commit_total` invariant held live** at 69 — the structural
  property the unit tests pin, confirmed end-to-end.
* **The fix is deliberately not attempted here** (open items 8-9). §E-live states the change, its
  predicted effect, the durability-vs-freshness risk under 885's read rule, and the exact arm —
  which needs a config key first, since `CYCLE_BUDGET_MS` is currently unreachable from a
  measurement arm.

## Live product validation (2026-09-02)

Two independent reviewers ran a live product-validation campaign against this lane's items:

- **V1** — unknown-size handling plus the honest byte estimate PASS.
- **V8** — commit-reason sums PASS.
- One load flake observed and routed (not a defect of this lane's items).

Related PRs: #616, #617.
