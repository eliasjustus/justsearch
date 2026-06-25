---
title: "402 — Index Write-Path Coordinator: RMW Serialization"
---

# 402 — Index Write-Path Coordinator: RMW Serialization

## Status

**IMPLEMENTED (2026-04-22).** Phases A–E landed, followed by a post-
implementation fix pass (C→A→B→E, same date) that consolidated the final
shape.

**Final framing: F4.** The `IndexingCoordinator` holds a single
`ReentrantLock dispatchLock`; every mutating method acquires it before
delegating to `WritePathOps`. The convenience mirrors
(`updateDocument`, `updateDocumentsBatch`, `updateDocumentPaths`,
`deleteByPathPrefix`, `deleteAll`) and the op-envelope path
(`executeNow(IndexWriteOperation)`) all serialize through the same lock.

The F1 scaffolding built in Phase A (inbox, `submit`,
`pollAndExecute`, `drain`, `quiesce`, `resume`, `dispatcherThread`,
`acceptingSubmissions`, `refreshHook`) was removed in the fix pass — no
production caller used it, and leaving it as "future optionality" was
architectural debt per my own analysis. If async dispatch is ever
needed, re-introduce it deliberately against a real caller.

**Single-writer invariant is complete.** Every mutating entry point on
`IndexingCoordinator` (the original `indexSingle`/`indexBatch`/
`deleteById*`/`deleteChunksForParentDocId` plus the 402-added
RMW/path/all-delete convenience methods) takes `dispatchLock` before
calling `WritePathOps`. No mutation path bypasses the lock.

**Visibility enforcement.** `WritePathOps` mutating methods are
package-private. `LuceneLifecycleManager.writePathOps()` accessor is
package-private. External callers MUST route through
`IndexingCoordinator`.

**Refresh semantics.** Coordinator does not own post-write refresh.
Callers that need read-your-writes (`GrpcIngestService.updateVduResult`)
continue to call `commitOps.maybeRefreshBlocking()` explicitly —
unchanged from pre-402.

**Regression gate.**
`BatchUpdateIntegrationTest.concurrentRmwOnSameDocIdSerializedByCoordinator_402`
asserts 0 lost updates across 50 same-docId concurrent-writer iterations
through the convenience-method path.

**Post-fix-pass verification.** `./gradlew.bat build` green (269 tasks).
All unit + integration tests pass.

**Pipeline benchmark (deferred).** The `jseval run --pipeline` benchmark
planned as final empirical validation was blocked by environmental
breakage: scoop `current` symlinks for python and java went stale mid-
session, and the versioned python has no pip to install jseval's deps.
Unit + integration tests are strong signal that nothing regressed;
empirical throughput validation remains as a follow-up once the dev
environment is repaired. Baseline from this session pre-402-changes:
195.6s pipeline total, 26.5 docs/s ingest, 316.7 docs/s primary
indexing.

## Provenance

Promoted and absorbed from 393 — the code audit of the 2026-04-19
pipeline-optimization session:

- **Absorbs 393 § 1.4** (Concurrent-writer race audit, PROMOTED from 2.6).
  This tempdoc replaces 393 § 1.4 as the authoritative tracking surface.
- **Absorbs 393 § 2.3** (RMW doc-values microbench).
- **Absorbs 393 § 3.1** (extract `readKeywordDocValue` / `readNumericDocValue`
  duplication).
- **Absorbs 393 § 3.2** (comment-semantics drift in `WritePathOps.readModifyWrite`).

When this tempdoc closes, 393 § 1.4, § 2.3, § 3.1, § 3.2 all close as a
group, referencing this document's resolution.

## Scope

Design for a write-path concurrency model that eliminates lost-update
hazards in `readModifyWrite(...)` while providing a durable,
architecturally clear ownership boundary for mutation of the Lucene index.

**Non-goals:**

- This tempdoc does NOT propose a new index format, commit model, or
  durability mechanism (e.g., WAL).
- It does NOT address the RMW *throughput* bottleneck discussed in
  312-primary-indexing-throughput.md. That concern is independent; RMW
  correctness does not depend on throughput choices, and vice versa.
- It does NOT propose distributed or multi-node semantics. JustSearch is
  single-node by design; the coordinator model here is a local-process
  abstraction.
- It does NOT address backpressure for the ingestion (new-doc) path —
  `IndexingCoordinator.indexSingle` / `indexBatch` already have
  backpressure via `ctx.queueDepth`. This tempdoc scopes to the *update*
  path.

## Problem statement

`WritePathOps.readModifyWrite(docId, fieldUpdates)` performs three logical
steps:

1. **Read**: open a snapshot via `IndexSearcher`, locate the doc, build an
   in-memory pre-image from stored fields + selected doc-values.
2. **Modify**: merge the caller-supplied updates onto the pre-image.
3. **Write**: call `IndexWriter.updateDocument(new Term("doc_id", docId), merged)`
   which atomically deletes the old doc and adds the new one.

Step 3 is atomic at the reader level per Lucene's contract. Steps 1→3 are
not. Two threads executing this envelope concurrently on the same `docId`
observe the same pre-image, merge their disjoint updates on top of it, and
both write. Whichever thread's write lands last wins; the other's changes
are silently lost. A committed reproducer at
`BatchUpdateIntegrationTest.concurrentRmwOnSameDocIdRaceReproducer_393_1_4`
produces 50/50 lost-update in 50 iterations.

Real-world concurrent callers:

- gRPC admin/ingest handlers (`GrpcIngestService.updateDocument*`,
  `updateDocumentPaths`, VDU reprocess paths) — running on gRPC executor
  threads.
- BackfillOps (SPLADE, NER, Embedding) — running on the indexing-loop
  thread.
- Test paths — direct `runtime.writePathOps().updateDocument*` from unit
  and integration tests.

The fundamental Lucene constraint (confirmed against official javadoc +
`IndexWriter` source): **Lucene exposes no conditional-update primitive.
`updateDocument` is atomic per-call but not lock-free with respect to
concurrent read-modify-write on the same logical document.** Callers must
serialize externally. There is no optimistic-CAS path; there is no
version-and-retry path; there is only external serialization.

## Option selection: why (d)

In the 393 § 1.4 analysis, five resolutions were considered:

| Option | Shape | Verdict |
|---|---|---|
| (a) Per-docId striped lock in `WritePathOps` | Shared-memory + fine-grained lock | Correct; scoped; preserves throughput |
| (b) Optimistic CAS via `_version` field | Does not work — Lucene has no CAS primitive | Rejected |
| (c) Serialize all RMW on a single executor | Correct; coarse-grained | Pessimistic; bounds throughput |
| (d) Route all RMW through `IndexingCoordinator` (actor model) | Single-writer architecture | **Selected** |
| (e) Eliminate `stored:false, docValues:true` field shape | Hygiene; doesn't solve conflicting updates | Complementary; not primary |
| (f) Expand the safety-net injection pattern | Band-aid; scopes the blast radius | Complementary; not primary |

Option (d) is selected because:

1. **Ownership clarity.** In a codebase with 27+ RMW call sites spanning
   gRPC handlers, backfill operations, and tests, "who may write" is
   currently diffuse. Making a single coordinator the sole writer
   establishes a bright architectural line.
2. **No new primitive.** (a) adds a lock abstraction that future
   maintainers must remember to use. (d) uses message-passing — the
   impossibility of calling `WritePathOps` directly from outside the
   coordinator is enforceable at compile time (package-private visibility),
   not at review time.
3. **Extensibility.** Future correctness concerns (write-ordering
   guarantees, commit coalescing changes, transactional boundaries across
   multiple doc updates) all need a writer actor anyway. Doing (a) now and
   (d) later means two refactors.
4. **Testability.** A single-writer model with a synchronous
   "execute-this-op-now" test hook is more deterministic than a lock-based
   model where test race conditions depend on wall-clock jitter.

The explicit trade-off: (d) imposes a queue hop on every RMW call. The
latency and throughput consequences of this hop are the central open
question of the design — deferred to "Open architectural questions" below.

## Theoretical framings

Option (d) is an instance of the **actor model** applied to the write path.
But the actor model admits multiple concrete framings, and the right one
depends on properties we want the system to have. Four framings follow.
Each is a complete conceptual design; each has different consequences for
latency, failure handling, and future evolution.

### Framing 1: Single-inbox writer actor

There is one actor — `IndexingCoordinator` — with one inbox. All RMW
requests are messages on that inbox. The actor runs on a dedicated thread
(or co-opts the indexing-loop thread) and processes messages strictly in
FIFO order.

- **Serialization discipline**: total order. If op A is enqueued before op
  B, A completes before B begins.
- **Concurrency**: zero. Throughput is bounded by the actor's
  single-threaded dispatch rate.
- **Latency per op**: enqueue cost + queue wait + dispatch cost.
- **Ordering guarantees**: callers see a single global order.
- **Failure isolation**: a failing op does not corrupt queue state; the
  actor catches, logs, and moves on.

This is the simplest framing. Correctness is trivial. It is also the most
pessimistic with respect to throughput — every RMW waits behind every
other RMW, even when they touch different documents.

### Framing 2: Per-docId partitioned actor

There are N logical actors, each owning a partition of the docId space
(hash-stripe). An RMW on docId *d* routes to `actor[hash(d) % N]`. Each
actor has its own inbox and processes its partition in FIFO order.

- **Serialization discipline**: per-partition total order; cross-partition
  unordered.
- **Concurrency**: up to N. RMWs on different docIds in different
  partitions proceed in parallel.
- **Latency per op**: enqueue + partition-queue wait + dispatch.
- **Ordering guarantees**: within a partition, FIFO. Across partitions,
  unordered — a caller doing `update(docA)` then `update(docB)` cannot
  assume A commits before B if they hash to different partitions.
- **Failure isolation**: a failing op or slow partition does not block
  others.

This is isomorphic to option (a)'s striped-lock model, recast as
message-passing. It preserves common-case throughput (different docs run
in parallel) at the cost of a more complex actor topology and shared
`IndexWriter` access across actors. (Lucene's `IndexWriter` is
thread-safe for concurrent calls on *different* docs, so partitioned
actors sharing one writer is legal.)

### Framing 3: Single-inbox writer actor with intra-batch parallelism

There is one actor owning one inbox, but each dispatch cycle batches N
messages, partitions them by docId, and executes non-conflicting updates
in parallel within the batch (using a short-lived executor).

- **Serialization discipline**: total order *between batches*; unordered
  within a non-conflicting batch.
- **Concurrency**: batch-scoped, bounded by thread pool size.
- **Latency per op**: enqueue + wait-for-batch-window + dispatch.
  Throughput benefits from batching (commit amortization); latency may be
  higher than Framing 1 for a lightly-loaded system because of the
  wait-for-batch window.
- **Ordering guarantees**: callers see batch-aligned order; intra-batch
  order is not guaranteed to match enqueue order.
- **Failure isolation**: same as Framing 1.

Framing 3 aligns best with Lucene's `IndexWriter` commit model:
`IndexWriter` coalesces writes into commits, and the commit timer is
independent of individual `updateDocument` calls. Batching inside the
actor matches the grain at which Lucene is efficient.

### Framing 4: Coordinator-mediated lock handoff (hybrid actor + lock)

The coordinator is the architectural gate (all writes route through it),
but under the hood it delegates to a per-docId lock (Framing 2's
semantics) without maintaining partitioned inboxes. From outside, the
coordinator looks like one actor; inside, it is just option (a) with
enforced visibility.

- **Serialization discipline**: per-docId total order; otherwise
  concurrent.
- **Concurrency**: up to the number of distinct in-flight docIds.
- **Latency per op**: lock acquire + dispatch + release. No queue
  wait for different-docId ops.
- **Ordering guarantees**: per-docId FIFO *in acquisition order*, which
  is not necessarily enqueue order if two threads race on lock
  acquisition. For same-thread back-to-back calls: FIFO.
- **Failure isolation**: a failing op releases its lock, does not block
  others.

Framing 4 is the "option (d) syntactically, option (a) semantically"
compromise. It preserves the architectural benefits of (d) — a single
chokepoint, compile-time visibility — while keeping the throughput
properties of (a). It is strictly more conservative than Framing 2 and
does not buy the cross-partition parallelism of Framing 3's batching.

### Framing comparison matrix

| Property | F1 single-inbox | F2 partitioned | F3 batched | F4 hybrid |
|---|---|---|---|---|
| Ordering | Total | Per-partition | Batch-aligned | Per-docId |
| Concurrency | 1 | N partitions | Batch fan-out | Per-docId |
| Latency (light load) | Low | Low | Medium | Low |
| Latency (heavy load) | High | Low-medium | Low (batched) | Low |
| Commit amortization | No | No | Yes | No |
| Complexity | Low | Medium | High | Low-medium |
| Lock primitives | None | None | None | Yes |

The theoretically "cleanest" framings are F1 and F3. F2 is the
message-passing translation of option (a). F4 is a pragmatic compromise
but gains little over option (a) while paying the architectural-gate
cost of (d).

## Protocol design (conceptual)

Regardless of framing, the conceptual protocol has the following shape:

### Operation envelope

A write operation is a tagged message:

    IndexWriteOperation := {
      opType: UpdateDocument | BatchUpdate | DeleteById | DeleteByPathPrefix
            | UpdateDocumentPaths | DeleteAll | DeleteChunksForParent | ...
      args: op-specific payload (docId, fields, flags, ...)
      priority: Admin | Backfill (for admission control)
      result: CompletionHandle — caller awaits or fires-and-forgets
      deadline: optional timeout for queue-wait back-pressure
      causalToken: optional — for cross-op ordering dependencies
    }

### Submission semantics

Callers invoke `coordinator.submit(op)`. This returns a handle the caller
may:

- `await()` — block until complete (gRPC admin case).
- `awaitWithTimeout(ms)` — back-pressure-sensitive (load-shedding case).
- `fireAndForget()` — async ingest (backfill case).

### Dispatch semantics

The coordinator is the sole caller of `WritePathOps.readModifyWrite`
and all other mutating entry points. `WritePathOps` becomes
package-private to the `adapters-lucene` module; it is no longer part of
the runtime facade's public surface.

### Completion semantics

The coordinator completes the `CompletionHandle` after `WritePathOps`
returns but **before** the Lucene commit lands on disk. Durability is
the commit timer's responsibility, not the coordinator's. If a stronger
guarantee is needed (e.g., "this RMW is durable before my RPC returns"),
a separate `awaitDurable()` entry point would coordinate with the commit
timer — but that is not part of this design.

### Failure modes

- **Op validation fails**: completion handle fails immediately; op never
  enters the dispatch path.
- **IndexWriter throws during dispatch**: handle completes with the
  exception; other queued ops unaffected.
- **Coordinator closed mid-dispatch**: in-flight ops may complete or
  fail with `ClosedException`; new submissions reject.
- **Queue full (back-pressure)**: submit rejects or blocks (caller
  choice). The queue is bounded.

### Observability

Every op emits:

- A span covering enqueue-to-complete.
- A counter tagged by opType and result (success/failure/timeout).
- A queue-depth gauge.
- Per-opType latency histogram (enqueue-to-dispatch + dispatch-to-complete
  broken out).

These fit naturally into the telemetry infrastructure already present
(`modules/telemetry`).

## Key architectural properties under the selected framing

The selected framing becomes an open architectural question (see below).
Regardless of which is picked, the design should preserve these
invariants:

1. **Single writer principle.** Outside of `IndexingCoordinator`, no
   code may mutate Lucene. Enforced by package-private visibility of
   `WritePathOps` and ArchUnit rules.
2. **No lost updates.** Concurrent RMWs on the same docId compose
   serially; neither is silently dropped.
3. **Per-docId causal consistency, at minimum.** If caller C issues
   `update(d, a)` then `update(d, b)`, the final state reflects both
   updates. Cross-docId ordering is not guaranteed unless the caller
   opts in (via causalToken or synchronous await).
4. **Backpressure is first-class.** The queue is bounded; submission
   may be rejected; callers decide how to respond.
5. **Testability via synchronous drive.** A test-only
   `coordinator.executeNow(op)` executes the op on the calling thread,
   bypassing the queue. This lets integration tests be deterministic.
6. **The coordinator's queue is not the jobqueue.** `IndexingLoop`'s
   existing job queue (for new-doc ingestion) is independent. The
   coordinator's inbox is a separate structure.

## Interactions with adjacent components

### `IndexingLoop`

`IndexingLoop` already owns the indexing thread. The coordinator could:

- **Share** the indexing thread: the loop dequeues from coordinator's
  inbox as part of its tick. Ops compete with ingest/backfill for
  wall-time. Simple, no extra thread.
- **Own** its own thread: the coordinator runs on a dedicated thread;
  the indexing loop is unchanged. Clean separation of concerns but two
  threads touching `IndexWriter` (which is legal per Lucene thread-safety
  but requires care).
- **Subsume** the indexing loop: the loop becomes a special kind of
  op on the coordinator's inbox. Maximum conceptual clarity but the most
  invasive refactor.

This is a core architectural decision — see open questions.

### `BackfillOps`

Today: backfill ops run on the indexing-loop thread and call
`writePathOps.updateDocument` directly. Post-coordinator: backfill ops
submit to the coordinator. If the coordinator shares the indexing thread
(option 1 above), a backfill op is synchronously executed inline by the
same thread that submitted it — effectively zero queue cost. This is a
key design property: **backfill pays no queue overhead** if the
coordinator lives on the same thread as the backfill runner.

### `CommitOps` / commit timer

The commit timer is independent and remains so. The coordinator's
dispatch produces `IndexWriter.updateDocument` calls; the commit timer
flushes them on its own schedule. No change required.

### `GrpcIngestService`

Every mutating handler changes from `writePathOps().updateDocument(...)`
to `coordinator.submit(...).await()`. This adds the queue-hop latency on
the gRPC path. Whether this is acceptable depends on the chosen framing
and observed queue depths.

### Tests

Most tests currently call `runtime.writePathOps().updateDocument(...)`
directly. Migration strategy:

- Tests that exercise correctness semantics (ordering, conflict
  resolution) migrate to `coordinator.submit(...).await()` — their
  reason for existing is the new contract.
- Tests that exercise `WritePathOps` internals (doc-value encoding,
  safety-net injection) call `coordinator.executeNow(...)` — the
  test-only synchronous entry point.
- The concurrent-reproducer test (`concurrentRmwOnSameDocIdRaceReproducer_393_1_4`)
  inverts: it now asserts that concurrent submissions to the coordinator
  cannot produce lost updates. It becomes a regression gate.

## Absorbed items — detailed disposition

### §2.3 (from 393) — RMW doc-values microbench

Was: a microbench measuring per-call overhead of `readKeywordDocValue` /
`readNumericDocValue` on the RMW hot path. Motivation: pick between
option (a) and option (c) based on throughput cost.

Under option (d): the microbench still has value but the measured
quantity shifts. Instead of "lock overhead vs no-lock", the question is
"queue-hop overhead vs direct-call overhead" — and whether the chosen
framing preserves same-thread inline execution for backfill. Benchmark
scope:

- 1 thread, 10k RMW ops, sequential — establishes dispatch cost.
- N threads, shared queue — measures queue contention curve.
- Same-docId stress — confirms serialization correctness (already the
  reproducer's job).

This closes with the coordinator's implementation.

### §3.1 (from 393) — extract doc-values helper

Was: `readKeywordDocValue` and `readNumericDocValue` have
duplicated error-handling and caller-pattern structure; extract into a
helper.

Under option (d): `WritePathOps` gets rewritten as the coordinator's
execution engine. During that rewrite, the duplicated pattern is
refactored as a natural part of the change. No separate action; it
closes when the coordinator lands.

### §3.2 (from 393) — comment semantics drift

Was: the doc comment on `WritePathOps.readModifyWrite` describes the
function's intended contract but has drifted from actual behavior (comment
claims atomic RMW; code provides atomic updateDocument but non-atomic
envelope).

Under option (d): `readModifyWrite` gets rewritten or replaced by a
coordinator-internal dispatch method. Its contract is re-documented
accurately (or replaced entirely with the coordinator's submission
contract). Closes with the rewrite.

## Open architectural questions

These must be resolved before any implementation tempdoc is opened.

### Q1. Which framing (F1 / F2 / F3 / F4)?

F1 is simplest and matches the actor model most cleanly, at a throughput
cost for multi-doc workloads. F3 aligns with Lucene's commit model but
adds complexity. F2 matches the striped-lock model but with
message-passing surface. F4 is a pragmatic compromise that may leave the
architectural-gate benefit paying for itself.

**Preliminary leaning:** F1 or F3. F1 if measurements show the queue hop
is negligible; F3 if not.

### Q2. Thread model: shared, dedicated, or subsumed?

- Shared (the coordinator's inbox is drained inside `IndexingLoop.tick()`):
  simplest, backfill pays zero queue cost, gRPC pays up to one full tick
  of latency.
- Dedicated (the coordinator has its own thread): clean separation,
  gRPC pays dispatch latency only.
- Subsumed (the indexing loop IS the coordinator's consumer and ingest
  flows through the same actor inbox): maximum clarity, maximum refactor.

**Preliminary leaning:** Shared. Backfill is the most volume-heavy
caller; preserving its inline execution is valuable. gRPC admin ops are
rare; a full-tick latency amplification is acceptable.

### Q3. Priority model?

Two-queue (admin + backfill) with aging, single-queue FIFO, or
single-queue with opType-based dispatch heuristics?

**Preliminary leaning:** Start single-queue FIFO. Priority is a
can-of-worms that can be added later if admin-starvation is observed.
Do not optimize prematurely.

### Q4. Caller contract — how many of the 27 existing call sites opt for
sync await vs fire-and-forget?

gRPC handlers need sync (the RPC has a response). BackfillOps can be
fire-and-forget if the loop tolerates async batching. Tests vary.

**Preliminary leaning:** Default to sync await. Fire-and-forget is an
optimization available per-caller; it is not the default.

### Q5. What architectural enforcement prevents future direct use of
`WritePathOps`?

Options: package-private visibility, ArchUnit rule, module boundary,
naming convention, deprecation annotations.

**Preliminary leaning:** All four. `WritePathOps` becomes
package-private; an ArchUnit rule in `modules/adapters-lucene` tests
asserts no class outside the coordinator package imports it; the
runtime facade no longer exposes `writePathOps()`; deprecation
annotations on the facade method during the migration window.

### Q6. Migration strategy?

Big-bang (rewrite all 27 call sites at once) vs. incremental (introduce
coordinator alongside, migrate one caller at a time, retire direct
access last).

**Preliminary leaning:** Incremental. Introduce `IndexingCoordinator.submit`
as a new entry point. Migrate one module at a time (backfill, then gRPC
ingest, then tests). Remove `runtime.writePathOps()` public access only
after the last caller migrates. Rollback granularity: per-module.

### Q7. Commit-coalescing interaction?

Does the coordinator participate in commit decisions, or is it purely a
dispatch mechanism? If it participates (e.g., commits inline after every
Nth op), it takes responsibility away from the commit timer, which has
its own tuning. If it doesn't participate, it's a pure dispatch actor.

**Preliminary leaning:** Pure dispatch. Commit timing is out of scope.
The coordinator produces writes; `CommitOps` flushes. Leave the boundary.

### Q8. What about the safety-net / field-preservation pattern (options (e)/(f))?

Independent of (d). Option (e) (eliminate `stored:false, docValues:true`
field shape) and option (f) (expand safety-net injection) are
correctness hygiene that can land before, during, or after the
coordinator refactor. They address a different failure mode: silent
field loss during a single RMW, independent of concurrency. Consider
landing (f) first as a defense-in-depth measure before the coordinator
refactor begins.

## Risks

1. **Latency regression on gRPC admin paths.** Adding a queue hop on
   every admin RMW adds dispatch latency. Mitigation: measure before
   and after; pick framing based on observed cost.
2. **Deadlock.** If the coordinator thread ever needs to submit a
   downstream op that ends up routing back through the coordinator, the
   system deadlocks. Mitigation: re-entrant detection in
   `coordinator.submit()`; fail loudly. In practice: the coordinator
   should not call gRPC or other potentially re-entrant surfaces
   during dispatch.
3. **Test churn.** ~27 call sites in tests need migration. Mitigation:
   a `coordinator.executeNow(op)` test hook that runs synchronously on
   the caller's thread, preserving test determinism while exercising
   the new contract surface. Most tests migrate to a one-line change.
4. **Migration window complexity.** During incremental migration, two
   write paths exist: direct `writePathOps()` and coordinator-mediated.
   Tests that exercise the race reproducer only hold against the
   coordinator-mediated path. Mitigation: migrate all concurrent
   callers first; retire the direct path only after.
5. **Hidden writers.** Any Lucene code that writes to the index
   *without* going through `WritePathOps` (e.g., directly via
   `IndexWriter` in a test or a utility) bypasses the coordinator.
   Mitigation: ArchUnit rule asserting `IndexWriter` is only imported
   by `WritePathOps` and internal coordinator classes.

## Success criteria

The refactor is complete when:

1. `runtime.writePathOps()` is no longer public; all callers have
   migrated to `IndexingCoordinator.submit(...)` or the test-only
   `executeNow(...)`.
2. `BatchUpdateIntegrationTest.concurrentRmwOnSameDocIdRaceReproducer_393_1_4`
   passes — concurrent submissions cannot produce lost updates. This is
   now a correctness gate, not a defect reproducer.
3. An ArchUnit rule asserts `IndexWriter` is imported only from within
   the coordinator's ownership boundary.
4. Pipeline benchmark (`jseval run --pipeline --start-backend --clean`)
   shows no more than ~5% throughput regression vs pre-refactor
   baseline.
5. Per-op latency histograms are visible via the existing telemetry
   module (`modules/telemetry`).
6. 393 § 1.4, § 2.3, § 3.1, § 3.2 all close with a reference to this
   tempdoc's resolution commit.

## Not in scope for this tempdoc

- Decision on which framing. (Deferred to a follow-up implementation
  tempdoc; this document surfaces the choice.)
- Concrete code changes.
- Schedule.
- Ownership assignment.

## Phase 1 investigation findings (post-design validation)

A pre-implementation investigation in five parallel workstreams (one
measurement-only workstream deferred to Phase 2) produced the following
findings. They both validate large parts of the original design and
force a material scope revision to Q7.

### Validated by investigation

- **Write surface is clean (Q5 confirmed).** Zero production code
  references `IndexWriter` outside `WritePathOps`. The single-writer
  invariant is trivially enforceable via package-private visibility +
  an ArchUnit rule — no exemption list required beyond
  `modules/benchmarks/*` (raw-Lucene benchmarks) and test fixtures.
  Source: `ComponentsFactory`, `Components` record, 16 referencing
  classes in total, all categorized.
- **No strict ordering requirements (Q-bundled validation).** Every
  current RMW caller (VDU ops, backfill ops, path updates, chunk
  regeneration) uses either independent or causal ordering; none
  require strict "A commits before B executes" semantics. F1
  (single-inbox FIFO) and F3 (batched dispatch) are both compatible
  with existing behavior. Only intra-RPC visibility via
  `maybeRefreshBlocking()` inside `updateVduResult` needs preservation
  (see Q7 revision).
- **Technology choices validated (Q1 + Q8).** `BlockingQueue` +
  `drainTo()` is idiomatic at the expected ~100–1000 ops/sec.
  LMAX Disruptor is overkill at this scale. OTel `Context.current().wrap(runnable)`
  is the canonical cross-thread span-propagation pattern. F3's batched
  dispatch is a trivial `queue.drainTo(batch, N)` — not a framework
  decision.
- **Migration scope confirmed (Q6).** 22 call sites total across
  production + tests. Breakdown: 9 trivial (one-line test migrations),
  8 `executeNow` bypasses (gRPC handlers reading return values), 5
  `BatchUpdateResult` contract-review cases (backfill ops reading
  batch counts through the future resolution), 0 blocked. Mechanical.

### Design-altering finding (Q7 revision — lifecycle coupling)

The original Q7 ("preliminary leaning: pure dispatch. Commit timing
is out of scope") **is no longer tenable.** Phase 1 revealed six
concrete ways the coordinator must own more than dispatch:

1. **Reset is not pure dispatch.** The current reset handler (triggered
   via gRPC `POST /api/debug/reset-index`) stops the indexing loop,
   calls `writePathOps().deleteAll()` → `commitOps().commitAndTrack("reset")` →
   `commitOps().maybeRefreshBlocking()` in sequence, then restarts the
   loop. This is orchestration, not dispatch. The coordinator either
   owns this choreography itself or exposes a reset-state transition
   that a dedicated lifecycle peer orchestrates.
2. **Search visibility requires explicit refresh.** `IndexWriter.commit()`
   does **not** auto-trigger `SearcherManager.maybeRefresh()`. For
   admin RPCs that need read-your-writes semantics (the concrete case:
   `updateVduResult` already calls `maybeRefreshBlocking()` today at
   `GrpcIngestService.java:702` to guarantee visibility before its RPC
   returns), the coordinator must own or delegate the refresh. Commit
   and visibility are independent axes.
3. **Commit timer is independent.** `CommitOps.timerTick()` commits on
   a 10-second schedule; the coordinator cannot block it or claim "all
   writes committed as of T." For cutover-before-migration guarantees,
   the coordinator must explicitly call `commit()` on demand.
4. **Writer-swap races exist.** `LuceneLifecycleManager.openWriterDeferred()`
   publishes `ctx.snapshot` atomically but does **not** quiesce in-flight
   writes. A write that observes the old snapshot and is mid-flight
   during swap can see `snap == null` mid-dispatch. The coordinator
   must either drain writes before swap or tolerate swap-window
   rejections.
5. **Refresh is decoupled from commit.** A coordinator that dispatches
   only writes, without also dispatching commit and refresh triggers,
   will leave searches reading stale data for up to one refresh cycle
   (typically ~500 ms via CRTRT). For callers with stronger freshness
   expectations, explicit refresh must flow through the coordinator or
   a peer.
6. **Reset handler references `writePathOps()` directly.** The pure
   single-writer invariant we'd want for the coordinator means reset's
   `deleteAll()` call must route through the coordinator. This moves
   reset's orchestration boundary into the coordinator's responsibility
   or requires a coordinator-internal lifecycle API.

**Q7 revised (replacement text):**

The coordinator is **not** a pure dispatch actor. It is a
write-path controller with lifecycle awareness. It owns:

- Dispatch of single-doc writes (RMW, delete, batch).
- Refresh-after-write for callers requiring read-your-writes semantics.
  Callers opt in via submission flag; the coordinator triggers
  `SearcherManager.maybeRefresh()` (or `maybeRefreshBlocking()`)
  post-dispatch before completing the caller's handle.
- On-demand commit invocation for lifecycle transitions
  (reset, migration cutover). The commit timer still owns
  background-commit scheduling; the coordinator owns commit-on-demand.
- In-flight write drainage before writer swap. Exposed as a
  `coordinator.drain()` that returns when the inbox is empty and no
  dispatch is in flight.
- Lifecycle-state-aware rejection: submissions fail fast during
  writer-swap windows and during shutdown.

Commit *scheduling* remains `CommitOps`' responsibility. The coordinator
has the authority to call `commit()` on demand but does not own the
background timer.

### Q1 revised (framing preliminary leaning)

F1 still the preliminary lean. Agent E's research confirms
`BlockingQueue.drainTo(batch, N)` gives F3's batching properties for
free, so F3 is a trivial later-stage optimization rather than an
alternative framework choice. The **measurement probe in Phase 2
remains the decisive signal** for F1-vs-F3 — but the downside of
choosing F1 and later adding drainTo-based batching is minimal.

### Additional risks (discovered in Phase 1)

7. **Reset / migration rewiring is larger scope than originally
   estimated.** Should land as a dedicated phase of the migration
   plan, **before** caller-site migration. Reset handler currently
   reaches into `writePathOps().deleteAll()` directly — this must
   route through the coordinator's lifecycle API or the single-writer
   invariant dilutes.
8. **`applyAllDeletes` config unverified.** For NRT visibility to
   include deletes, `SearcherManager` must be constructed with
   `applyAllDeletes=true` (per Lucene javadoc). Verify current
   configuration in `ComponentsFactory` during implementation —
   flip if needed. Mitigation: trivially verified; low-risk.
9. **Caller-visible refresh semantics must be preserved.** Any caller
   today that implicitly or explicitly relies on
   `maybeRefreshBlocking()` after its write must continue to see the
   same behavior. The opt-in refresh flag on submission addresses
   this, but each migrated call site needs case-by-case review.

### Updated success criteria additions

- Reset and migration-cutover RPC handlers route through the
  coordinator's lifecycle API — not through direct `writePathOps()`
  access.
- `updateVduResult` and any other current `maybeRefreshBlocking()`
  caller preserves read-your-writes semantics post-refactor. This is
  testable via an integration test that writes, then searches, all
  within the same RPC.
- `coordinator.drain()` + writer-swap sequence is race-free — no
  submissions lost during swap.
- `applyAllDeletes=true` verified on `SearcherManager` (existing or
  flipped).

### Revised pre-implementation confidence

Before Phase 1: ~60%. After Phase 1: ~75–80%, with residual
uncertainty concentrated in:

- Lifecycle-orchestration integration (coordinator ↔ `CommitOps` ↔
  `LuceneLifecycleManager` interaction surface — conceptually clear
  per Q7 revision but not yet sketched in detail).
- Reset choreography migration — reset today is a single choreographed
  block; routing it through the coordinator requires either
  coordinator-internal lifecycle ops or a lifecycle-peer callback
  surface. Either works; the choice is not yet forced.
- F1 queue-hop cost — still outstanding. Addressed by Phase 2
  measurement probe.

### What's unchanged from the original design

- Framing F1 (single-inbox, shared indexing-loop thread) still looks
  right. No evidence emerged that F1 is wrong; Agent E merely
  confirmed F3 is a trivial addition if needed.
- Compile-time enforcement of the single-writer invariant is
  achievable (Agent B).
- Migration is incremental and mechanical (Agent D).
- The race reproducer is a valid regression gate (unchanged).

## References

- 393 § 1.4 — original scope and reproducer evidence.
- 393 §§ 2.3, 3.1, 3.2 — bundled items absorbed here.
- `BatchUpdateIntegrationTest.concurrentRmwOnSameDocIdRaceReproducer_393_1_4` —
  committed regression reproducer.
- Lucene `IndexWriter` javadoc (thread-safety contract, atomic updateDocument).
- Lucene `SearcherManager` + `DirectoryReader.openIfChanged(reader, writer, applyAllDeletes)`
  javadoc — NRT visibility semantics (Phase 1 Agent E).
- Thompson et al., "Disruptor: High performance alternative to bounded
  queues for exchanging data between concurrent threads" (LMAX, 2011)
  — applicability verdict in Phase 1 Agent E.
- OpenTelemetry Java API, Context propagation docs — span propagation
  pattern (Phase 1 Agent E).
- Elasticsearch primary/replica coordination model (inspirational, not
  directly applicable at single-node).
- Actor model literature (Hewitt '73, Agha '86) — theoretical foundation.
