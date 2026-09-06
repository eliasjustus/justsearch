---
title: "NativeSessionHandle concurrency cluster: state slots with one writer regime, a session-factory seam, lock-order rule, @GuardedBy at error, a JCStress pilot, and device-lost handled by a supervised Worker restart — never by falling to CPU"
type: tempdocs
status: DESIGNED (2026-09-03), §4.5 SUPERSEDED (2026-09-05, tempdoc 930 §14/§18.1 row 10) — theorized + designed; open questions answered (§7); not started. Takes over 900 items 2-3 and 898 item 2 (see §0). Implement in the order of §9; do not re-litigate the decisions in §4 without writing why in §Status. The why for §4.5 is written in "Status amendment" below.
created: 2026-09-03
updated: 2026-09-05
lane: 887 L11 (900 items 2-3) + L19 (898 item 2)
model: fable (theorize + design) / opus (implementation)
parent: 900-static-analysis-and-concurrency-conventions, 898-inference-runtime-residuals
related:
  - 398-ort-concurrency-invariant-regression-gate   # the parked invariants — this tempdoc is the trigger it waited for; closes it
  - 397 (§14.19-14.28: Builder visibility, policy records, Lease choke point), 414 (TransitionReason seam), 710 (Lease.run choke point + ArchUnit pin)
  - 627 (Worker supervision: one budgeted decision point), 628 (WorkerFatalReasonMarker, controlled fatal exit)
  - 386 / 402 / 819 / 843 / 862                     # the one-off race fixes that prove the bug class
  - 872-retire-observations-store                   # retired 862's subject; corrects 900's scope list
  - 887-improvement-landscape-register               # A1 §1.5, A7 §7.2/7.3, A8 §8.1
---

# 919 — NativeSessionHandle concurrency cluster

## Briefing for the agent picking this up

Read this file, then `NativeSessionHandle.java` and `SessionHandle.java` in `modules/ort-common`
end to end (≈1000 lines together), then 398 §3 and §7, then `WorkerSpawner.superviseTick` and
`KnowledgeServerBootstrap`'s fatal-marker read (628). Load `/inference-runtime` (register — update
it before closing) and, for the JCStress/Error Prone wiring, read
`build-logic/.../ErrorProneConventionsPlugin.kt` and `MutationConventionsPlugin.kt` (the off-graph
precedent). Work in a worktree. Java is not auto-formatted (729). Four PRs in the order of §9.
§4 is decided; §7 records the answers to what was open. Every claim in §1 was source-verified on
2026-09-03 against `main` at `78f208b8`; re-verify line numbers before editing, not the facts.

**Owner directive (2026-09-03), binding for this design:** do not design or rely on CPU as a
fallback. Inference on CPU congests the whole machine and, being far slower, congests it for far
longer. Recovery designs here therefore degrade *honestly* (a stage is skipped and says so, or the
process restarts) rather than *quietly* (the same work on CPU). §5.1 lists the shipped behaviours
this directive also touches but this tempdoc does not change.

## Status amendment (2026-09-05, from tempdoc 930 — the "why" the status line demands)

Tempdoc 930 (replace/stop-doing analysis, founder-approved 2026-09-05) judged §4.5 on delivered
value: the fail-closed GPU policy has no user incident behind it, the shipped CPU fallback is what
ADR-0004 relies on, and a Worker that exits on device-lost would take BM25 search and indexing down
with it (§3.1 already names that shape as "worse than today"). Decision, recorded here as the
directive's "write why": **do not build §4.5 as specified** (never-fall-to-CPU + supervised Worker
restart on device-lost); keep the shipped CPU fallback and keep `llama-server.exe` process
separation. Nothing else in §4 is affected — the writer-regime slots (§4.1), the factory seam
(§4.2), lock order (§4.3), `close()` (§4.4), `@GuardedBy` (§4.7), the lock-order inventory (§4.8)
and the JCStress pilot (§4.9) stand as designed and still fix the three defects in §1.2. When §4.5
is revisited, the trigger is a reproduced device-lost incident, not a design preference; 930 §14
row "GPU fail-closed (919)" holds the evidence table.

## 0. What this tempdoc takes over

| From | Item | Here |
|---|---|---|
| 900 | item 2 — `@GuardedBy` pass over the race-fixed classes + `NativeSessionHandle`, Error Prone `GuardedBy` at error, lock-order inventory | §4.7, §4.8 |
| 900 | item 3 — JCStress pilot on `NativeSessionHandle` | §4.9 |
| 898 | item 2 — device-lost classification on the run path, recovery, transition telemetry, fault-injection test | §4.5, §4.6, §4.10 — **with 898's "recreate once, then CPU" decision replaced** (§3.2, §5) |
| 398 | the whole parked gate | closed by §4.9; 398 §Status gets "gate built in 919" |

900 items 1 and 4 (NullAway, internal-package rule) and 898 items 1, 3-5 stay where they are.
Reason for the split: the three items above share one object (`NativeSessionHandle`'s state
machine) and one failure shape (an interleaving that is silent under CPU-only tests and loud on a
user's GPU). Device-lost adds a fourth writer to fields that already have three writers under three
different exclusion regimes (§1.2); doing it before the state is unified would add a race, not
remove one. The order is therefore: unify state → prove it under JCStress → add device-lost.

## 1. Findings (source-verified 2026-09-03)

### 1.1 The machine today

`NativeSessionHandle` (NSH) owns a CPU session and an optional GPU session per encoder. It has three
exclusion primitives — `gpuSessionLock` and `cpuSessionLock` (monitors) and
`gpuInferenceSemaphore` (`Semaphore(1)`, serialises GPU `session.run`) — and eleven `volatile`
fields. The `Lease` returned by `acquire()` is an immutable record holding a raw `OrtSession`; every
production ORT run passes through `Lease.run` / `Lease.runPinned`, pinned by an ArchUnit rule
(`OrtRunChokePointTest`, 710).

### 1.2 Three writer regimes on the same fields — and three concrete defects

The GPU-side fields (`gpuSession`, `gpuRunOptions`, `gpuSessionAttempted`, `gpuAvailable`,
`gpuFailedAtMs`, `ortCudaStatus`) are written:

- under `gpuSessionLock` by `selectSession()` → `tryCreateGpuSession()` (NSH:223-246, 638-670);
- under **the semaphore only** by `releaseGpu()` (NSH:342-357);
- under **nothing** by `close()` (NSH:516-541).

`gpuSessionReleasing` is written under nothing (NSH:327, 362); `cpuSessionFailed`/`cpuFailureCause`
under nothing by `reportCpuSessionFailure()` (NSH:406-407) and under `cpuSessionLock` by
`getCpuSession()`. `onBeforeGpuRelease` is a plain non-volatile field mutated after publication
(NSH:378) and read on the releasing thread (NSH:334). `closed` is written once and read by nobody
but `close()` itself.

This yields three defects that no existing test can see:

1. **Torn read → lease with a null session.** `selectSession()` returns
   `gpuAvailable ? gpuSession : getCpuSession()` (NSH:248) — two volatile reads. `releaseGpu()`
   nulls `gpuSession` (NSH:346) before clearing `gpuAvailable` (NSH:355). A thread reading between
   the two gets `null`, fails the `session == gpuSession && session != null` check in `acquire()`
   (NSH:279), and is handed a **CPU lease wrapping `null`** (NSH:302). The next `Lease.run` throws
   `NullPointerException` from inside an encoder that expects `OrtException`. Trigger: the user opens
   chat while a query or enrichment batch is in flight — the exact scenario 398 §3 #2 describes.
2. **Creation racing a release → a GPU session that survives the release.** `tryCreateGpuSession`
   runs for seconds under `gpuSessionLock` (DLL check + up to two native session creations,
   NSH:609-631). `releaseGpu()` does not take that lock; it sets `gpuSessionReleasing`, acquires the
   free semaphore, finds `gpuSession == null`, flips the flags, and returns. The creating thread then
   publishes `gpuSession` and `gpuAvailable = true` (NSH:638, 647). Outcome: VRAM the Main process
   was promised is still held by a Worker session, and `isGpuAvailable()` reports true while the
   arbiter says CPU. Silent in tests (no GPU), loud on a 12 GB card when the chat model then fails to
   load.
3. **`close()` is unsynchronised and `closed` is never consulted.** `close()` nulls both sessions
   with no exclusion against in-flight leases or a concurrent `getCpuSession()`; because nothing
   reads `closed`, a post-close `acquire()` recreates a CPU session (NSH:577-579) that nobody will
   ever close. The stress test tolerates this today ("post-close acquires occur",
   `NativeSessionHandleConcurrentStressTest:146-178`) — it documents a leak as a property.

### 1.3 What is latent, and what is not

- **No path holds both monitors.** NSH:248 is outside both `synchronized` blocks; `acquire()`
  releases the semaphore (NSH:290) before calling `getCpuSession()`. 398's invariant #1
  (`gpuSessionLock → cpuSessionLock`) is a rule about code that does not exist yet.
- **No path holds a monitor while acquiring the semaphore**, and none acquires a monitor while
  holding the semaphore — except through the `onBeforeGpuRelease` callback (NSH:336), which runs an
  alien `Runnable` under the semaphore (843's pattern: alien call under a lock). Today's only
  callback, `SpladeEncoder::closePinnedOutput`, takes no lock, so this is latent too.
- **`events.onTransition` fires under `gpuSessionLock` and `cpuSessionLock`** (NSH:582, 657, 664)
  — another alien call under a lock; the production adapter is `OrtSessionTelemetryAdapter`
  (worker-services), which today only counts and logs.

### 1.4 The run path

- 14 call sites reach `Lease.run`/`runPinned`. **Six catch nothing** (`CitationScorer:276`,
  `BgeM3Encoder:323`, `OnnxEmbeddingEncoder:552,816`, `BertNerInference:244,435`); the rest catch
  `OrtException` only to run their own BFC-arena ladder (per-doc retry, then `acquireCpu()`), and
  only `CrossEncoderReranker` ever calls `reportCpuSessionFailure`. `EncoderBatchSweepBench:316`
  bypasses `Lease.run` by unpacking `session()` (benchmarks module — outside the ArchUnit rule's
  packages).
- `Lease` carries **no back-reference to its handle**: nothing inside `Lease.run` can ask the handle
  to do anything.
- `FailureCause.classifyGpuInitException` matches `out of memory`, `cuda`+`driver`,
  `cuda`+`unavailable`/`provider`/`no kernel image`; **nothing matches a run-time CUDA fault**
  (`CUDA failure <n>: …`, `CUBLAS failure …`, `CUDNN failure …`, illegal address, unspecified launch
  failure, device-side assert). The only run-time classifier is `isBfcArenaFailure` (three
  substrings).
- **Sticky CUDA errors.** The CUDA runtime documents a class of errors after which "any further CUDA
  work will return the same error; to continue using CUDA, the process must be terminated and
  relaunched" — illegal address, launch failure, launch timeout, device-side assert, hardware stack /
  illegal instruction / misaligned address / invalid PC, uncorrectable ECC, and `cudaErrorUnknown`.
  These are precisely the "device-lost mid-run" faults 898 targets. **An in-process close-and-
  recreate of an ORT session cannot succeed after one of them**; only a new process gets a new
  context. (Confirm the exact code list against the pinned CUDA runtime's `cudaError` documentation
  at implementation; the design does not depend on the precise set, only on the class existing.)
- `NdjsonInferenceTransitionLog` — the sink 898 names — lives in `app-inference` (Head side, the
  llama-server state machine's sidecar). `ort-common` cannot reach it and ORT sessions live in the
  Worker. The Worker-side channel that already exists is `OrtSessionTelemetryEvents.onTransition`
  → `OrtSessionTelemetryAdapter`.
- **There are no chaos or fault-injection hooks in `ort-common` tests.** 898's "existing chaos hooks
  in `ort-common` tests" is unsupported; the chaos infrastructure is in `system-tests` and targets
  processes, not sessions. `OrtSession` is a public non-final JNI-backed class with a
  package-private constructor: it cannot be subclassed, it can be mocked (Mockito inline), and
  today nothing in the module does either. **A test cannot make `session.run` fail.**

### 1.5 Worker supervision (the recovery unit that already exists)

- The Head spawns the Worker and supervises it through one budgeted decision point
  (`WorkerSpawner.superviseTick`, 627): a 1 s death monitor and a hang detector both feed
  `SupervisionDecision.decide`, whose policy is 3 restart attempts, exponential backoff 1 s → 30 s,
  and a 5-minute stability window that resets the budget. Exhaustion → `WORKER_RESTART_EXHAUSTED`
  and the supervisor gives up (`supervisionGaveUp`).
- A Worker that exits **deliberately** can say why: `WorkerFatalReasonMarker.write(dataDir, reason)`
  (`ipc-common`) is a one-line file the Head reads and clears at the next boot
  (`KnowledgeServerBootstrap:748`), mapping it to a `LifecycleReasonCode` — today the single reason
  is `INDEX_CORRUPT` → `WORKER_INDEX_CORRUPT`, which is `RetentionClass.STICKY` and **holds** the
  restart so the UI can offer "Rebuild index" instead of blind-restarting. The marker + reason-code
  pattern is exactly the seam a device-lost exit needs; the retention class is the one thing that
  differs (a device-lost restart should proceed).
- Worker warm restart is ~40 s to worker-ready (ONNX encoders reload); during it the Head reports the
  Worker as recovering (`WORKER_RECOVERING`) through the existing readiness surface — search is
  unavailable, not degraded to CPU.
- When encoders are absent the system already degrades honestly: `KnowledgeServer` runs deferred
  model init in the background and "callers are null-safe: search degrades to BM25, IndexingLoop
  skips embedding/SPLADE" (`KnowledgeServer:785-790`); the query-side reason vocabulary already has
  `NO_EMBEDDING_SERVICE` / `EMBEDDING_EXCEPTION` (`SearchReasonCode`, worded in the FE by the
  `check-search-degradation-reason-codes` gate). Enrichment backfill is *deferred*, not moved to
  CPU, while the chat model holds the GPU (`LoopPacingPolicy:58`).

### 1.6 Tooling

- Error Prone 2.47.0 on every module; the only promotion is `InvalidLink`. `GuardedBy` runs at its
  default WARNING; there is no warning count because no build log is kept. Error Prone is skipped on
  test sources locally and CI runs only `assemble` + module `test` tasks, so **Error Prone bites in
  CI only through `compileJava`** — fine for `GuardedBy`, which is a main-source concern.
- `@GuardedBy` exists in the repo exactly four times, all in `TransitionRunner` (app-inference),
  from `net.jcip.annotations`. `ort-common` has **neither** jcip nor `error_prone_annotations` on its
  compile classpath; the annotation dependency must be added.
- No JCStress anywhere. The off-graph precedent is PIT: `conventions.mutation` registers a task that
  nothing depends on (pure omission, not exclusion), opt-in per module, invoked by a script.
- Cross-module lock nesting is small: one deliberate two-lock nesting
  (`IndexingCoordinator`: `writeBarrier.readLock → dispatchLock`, 402/406), one same-monitor
  re-entry across a class boundary (`InferenceLifecycleManager` → `TransitionRunner`), two
  alien-call-under-lock sites (NSH:336, `TransitionRunner.notifyListeners`), three blocking-under-lock
  sites (NSH native creation, `InferenceLifecycleManager.stopLlamaServer`, `SqliteJobQueue` JDBC).
- Of 900's "five race-fixed classes", only three have a lock: `IndexingCoordinator.dispatchLock`
  (guards Lucene state, not a field), `OnlineModeOps.onlineRequestLock`, and NSH. `SpladeEncoder`
  is thread-confined, `EmbeddingCompatibilityController` is `AtomicReference` + monotone volatiles,
  and 862's subject was a Node script retired by 872.

## 2. Refactor judgment: is a rewrite of anything worth it?

**Yes, one bounded refactor inside `NativeSessionHandle`; no rewrite anywhere else.** The reasoning:

- **Annotate-only cannot reach the acceptance criterion.** `@GuardedBy("gpuSessionLock")` on the GPU
  fields fails to compile at `releaseGpu()` and `close()` today. The mechanical remedy — take
  `gpuSessionLock` inside both — leaves defect 1 (the torn read is between two reads, not two writes)
  and adds a `semaphore → monitor` nesting without deciding whether it is the rule. Annotations
  describe an exclusion regime; NSH does not have one to describe.
- **JCStress needs a seam that does not exist.** Every JCStress iteration constructs fresh state;
  real ORT sessions cost milliseconds each and need model files. Without a way to hand the handle a
  fake session, the pilot cannot run anywhere (398 §7.1's "mocked session layer" question, now
  forced). The same seam is the fault injector 898 item 2 assumed already existed.
- **Device-lost adds a fourth writer.** Marking a slot lost from a thread that holds the semaphore
  and a lease, while a release or a close may be in flight, must mutate the same GPU state. Adding
  that to three unreconciled regimes is how 398's "a loss racing a close" becomes real.
- **Everything around it is sound and stays.** `SessionHandle` (the encoder-facing interface),
  `Lease` as the run choke point, `OrtSessionAssembler` as the single construction path, the policy
  records, `SessionOptionsApplier`, the Builder visibility pin, the encoders' own arena ladders, the
  Worker supervisor and fatal-marker seam, and the other race-fixed classes are not touched beyond
  annotations and small additions. The 402 nesting in `IndexingCoordinator` is deliberate and
  documented; it gets a row in the inventory, not a change.

What the refactor is **not**: it is not an extraction of a "pure state machine" class separate from
the sessions and the semaphore (rejected in §3), and not a lock-free rewrite (rejected in §3). It is
~200-300 lines inside one 811-line class, with no change to any public API.

## 3. Theorization

### 3.1 Framings considered

- **"Add the missing locks."** The 900 framing. Correct diagnosis (fields lack a regime), wrong unit
  of repair: the unit is the *field group*, not the field. Three writers disagree because the state
  is eleven independent flags with no notion of "the GPU slot as a whole is in phase X".
- **"The state is a value."** Collapse each side into one immutable record (`GpuSlot`, `CpuSlot`)
  with an explicit phase; readers take one snapshot, writers replace the whole record under one lock.
  Torn reads become impossible by construction; `@GuardedBy` has exactly one field per lock to
  annotate; JCStress assertions are on snapshots. This is the framing adopted.
- **"Make it lock-free."** Phases as a CAS ladder on an `AtomicReference<GpuSlot>` (a thread that
  wins the CAS to `CREATING` creates; others go CPU). Eliminates lock order entirely — and with it
  any reason for `@GuardedBy` in NSH, which would leave 900 item 2 with nothing to annotate in its
  pilot class. Rejected on review cost: a CAS ladder with seconds-long native work between CAS and
  publish needs its own JCStress proof and is harder to read than "one monitor per slot". The
  monitor already exists and is not contended (creation is rare; the hot path is a volatile read).
- **"Extract the state machine so JCStress can test it without ORT."** Rejected as a projection-vs-
  fork question (CLAUDE.md, explore-before-implementing): a second class that knows "what phase is
  the GPU in" beside the one that owns the session is a fork that will drift. The seam belongs at
  the boundary where ORT is *created*, not where state is *decided* — hence a session factory.
- **"Wrap `OrtSession` in an interface so tests can fake it."** Rejected: `Lease.session()` returns
  `OrtSession` and encoders (SPLADE's recursive helpers, benchmarks) unpack it; changing the type
  ripples through 14 call sites for a test-only benefit. Mockito inline mocks the real class.
- **Device-lost: "recreate the session" vs "restart the process" vs "fall to CPU".** 898 chose
  recreate-then-CPU. Recreate cannot work for sticky errors (§1.4) and CPU is ruled out by the owner
  directive. Restarting the process is the only recovery that actually restores the GPU, and the
  machinery — supervisor, budget, backoff, fatal-reason marker, readiness reason codes — already
  exists for the crash case; a device-lost exit is a *labelled* crash. Adopted.
- **Device-lost crash loop: "give up" vs "come back without encoders" vs "come back on CPU".** A GPU
  that keeps dying (failing hardware, driver crash loop) would exhaust the restart budget in minutes
  and take BM25 search and indexing down with it — worse than today. CPU is ruled out. Coming back
  with the ONNX encoders *off* uses the degradation the system already has (BM25 search, enrichment
  skipped, readiness says why) and costs the user nothing they did not already lose. Adopted, with a
  narrow trigger (§4.5).

### 3.2 Hidden assumptions surfaced

- **898 assumed a fault hook exists.** It does not (§1.4). The design must build it; the JCStress
  pilot needs the same hook, which is why the two items belong together.
- **898 assumed the transition log is reachable.** It is Head-side. The Worker-side seam already
  exists (`OrtSessionTelemetryEvents`); using it is a correction, not a compromise (§4.6).
- **898 assumed an in-process recreate can succeed after device loss.** For the sticky class it
  cannot; "recreate once" would fail on every attempt and then hand the work to CPU — the directive's
  exact anti-pattern, reached by a mechanism that never had a success path.
- **398 assumed invariant #1 was the important one.** It is the only *latent* one; the two live
  defects are a torn read and a creation/release race — neither is about lock *order*. The
  inventory doc still matters (it is what stops the latent one from becoming real), but the JCStress
  pilot should spend its budget on the live ones.
- **900 assumed five annotatable classes.** Three have locks; one guards external state. The
  `@GuardedBy` pass is smaller than 900 priced it, and the cross-module inventory has two real rows.
- **Closing a session on a dead device may itself misbehave.** ORT's session destructor frees CUDA
  memory; after a device reset those calls return the sticky error and may, in the worst case, block.
  The design never closes a session on a lost device in-process: the process exit is the close.

### 3.3 Risks

- **Behaviour change at `close()`**: post-close `acquire()` will throw instead of leaking. Worker
  shutdown races an enrichment batch today; the batch will now see `IllegalStateException` from
  `acquire()`. Encoders already propagate exceptions from `run`; the stress test's tolerance flips
  to an assertion (§4.4).
- **A deliberate Worker exit mid-batch.** In-flight gRPC calls fail; the indexing loop's batch is
  lost and re-queued by the existing job-queue semantics; a Lucene commit in progress is closed by
  the controlled-exit path. This is the same exposure as any Worker crash today, which 627/628
  already made survivable — device-lost adds a *reason*, not a new failure mode. The exit is
  bounded (graceful close with a deadline, then hard exit) so a hung CUDA teardown cannot keep a
  dead Worker alive.
- **Misclassifying a non-sticky error as sticky** costs one unnecessary ~40 s restart. Misclassifying
  a sticky error as non-sticky costs every subsequent GPU call until something else restarts the
  Worker. The classifier therefore errs toward *sticky* for any wrapped CUDA fault it does not
  recognise, and the restart budget bounds the cost of being wrong.
- **Error Prone `GuardedBy` at error repo-wide** may surface warnings in modules this tempdoc does
  not touch. There is no count. The PR that promotes it fixes every finding (they are defects by
  definition of the convention) or, if a finding is a false positive of the checker, records it in
  §Status with the exact site — never a blanket suppression (`check-suppression-ratchet`).
- **JCStress on Windows under parallel-agent load**: a JCStress run is CPU-bound and forks JVMs; an
  interesting-but-not-forbidden outcome under starvation is not a failure. The pilot records outcome
  tables, not a pass/fail bit, and runs off-graph.
- **Mockito inline in the JCStress source set** adds a byte-buddy agent to a timing-sensitive
  harness. The fake sessions must be created in the `@State` constructor, never inside an `@Actor`,
  so mock cost is outside the measured window.

### 3.4 Ideas kept for later (not designed here)

- Once slots are values, `status()` can return the slot's phase directly and `/api/debug/state`
  can show `gpu: {phase, sinceMs, lastCause}` per encoder without a new field.
- The same slot shape fits the llama-server side's `ModeStateMachine` (already
  `@GuardedBy("lock")`); no action, but §6 names it.
- A `GuardedBy` *ratchet* (count of un-annotated mutable fields in classes that declare a lock)
  would enforce the convention beyond Error Prone's reach (write-guarded snapshots, §4.7). Not
  built now; §6 states the trigger.
- The "encoders-off" boot mode (§4.5) is also the honest floor for a machine whose GPU init fails
  at boot. Today that case falls to CPU sessions with a 60 s retry (NSH:658-671) — see §5.1.

## 4. Design

### 4.1 State slots with one writer regime each

Replace the eleven volatiles with two immutable records and two published references:

- `GpuSlot { phase, session, runOptions, failedAtMs, lostCause, status }` where `phase` is one of
  `UNCONFIGURED`, `UNATTEMPTED`, `CREATING`, `READY`, `FAILED`, `LOST`, `RELEASING`, `CLOSED`.
- `CpuSlot { phase, session, failureCause }` with `phase` in `DEFERRED`, `READY`, `FAILED_PENDING`,
  `CLOSED`.

Rules:

- **Every write** of a slot happens under its lock (`gpuSessionLock` / `cpuSessionLock`) and
  replaces the whole record. The published field is `volatile`; **reads never take the lock** — a
  reader snapshots once and decides from the snapshot. This is the *write-guarded snapshot* pattern
  (§4.7 names it for the convention).
- `gpuSessionReleasing` becomes `phase == RELEASING`; `gpuAvailable` becomes `phase == READY`;
  `gpuSessionAttempted` becomes `phase != UNATTEMPTED`; `ortCudaStatus` is a field of the slot (one
  write, one snapshot — no more status/flags skew).
- `cpuSessionFailed` + `cpuFailureCause` become `CpuSlot{FAILED_PENDING, cause}`; the
  deferred-recreate contract of `reportCpuSessionFailure` (D9/F-009) is unchanged in behaviour.
- `onBeforeGpuRelease` and `ortRunRecorder` remain late-bound and become `volatile` both.
- `acquire()` builds its lease from **one** snapshot: `READY` → GPU path; anything else → the CPU
  slot (unchanged semantics for the shipped CPU-default and arbitration cases, §5.1). The
  post-semaphore re-check (398 #2) compares the *same snapshot's session identity* against the
  current slot's, so the "captured session is closed" case is a single identity comparison and the
  "null session" case (defect 1) cannot be constructed.
- `tryCreateGpuSession` splits into transition + work + publish: under `gpuSessionLock`,
  `UNATTEMPTED|FAILED → CREATING` (exclusion, 398 #4); the seconds of native work run **unlocked**;
  then under the lock again, publish `READY` — **only if the slot is still the very `CREATING`
  record this thread installed** (identity, or a generation counter carried in the record). If a
  release or close replaced it meanwhile, the freshly created session is closed and nothing is
  published (defect 2 closed — and a "release completed, then creator publishes" sequence is
  excluded too, because the release's `UNATTEMPTED` record is not the creator's `CREATING` one).
  A second `acquire()` that observes `CREATING` **waits** on the monitor for the publish, exactly as
  it blocks on the lock today — it does not take the CPU slot. (Critical pass §10.2: routing waiters
  to CPU during a warm-up would have introduced a new CPU-fallback window, e.g. a 40 s CPU rerank for
  a query that lands during GPU session creation.) A release no longer waits on a DLL probe.
- `releaseGpu()` becomes: under `gpuSessionLock`, `READY|FAILED|LOST|CREATING → RELEASING` (return
  if `UNATTEMPTED|UNCONFIGURED|CLOSED`); run `onBeforeGpuRelease` (outside the lock, before the
  semaphore — new GPU leases are already refused); acquire the semaphore (in-flight run drains);
  under the lock again, close the session (never for `LOST`, §4.5) and publish `UNATTEMPTED`.

### 4.2 Session factory seam

A package-private `NativeSessionFactory { createCpu(); createGpu(Path model) }` whose default
implementation is today's `createCpuSession`/`createGpuSession` bodies (option application via
`SessionOptionsApplier`, cache via `OnnxSessionCache`). The Builder gains a package-private
`sessionFactory(...)`; `OrtSessionAssembler.buildManager` never sets it (production is the default).
Test fixtures expose a `FakeSessions` helper that builds Mockito-inline `OrtSession` mocks whose
`run` behaviour is scripted (succeed / throw a given `OrtException` once / throw always) and whose
`close()` flips an observable flag. Mockito is added to the module's **test and jcstress source sets
only**. The Builder visibility pin (`NativeSessionHandleBuilderVisibilityTest`) is unchanged: the
seam is reachable only from the package and testFixtures.

### 4.3 Lock-order rule and no alien calls under a lock

After §4.1 there is exactly one nesting inside NSH — `gpuInferenceSemaphore → gpuSessionLock`
(in `releaseGpu`, and in the device-lost path §4.5). The rule, recorded at the top of the class and
in the inventory doc:

> `gpuInferenceSemaphore` may be held while taking `gpuSessionLock`; never the reverse.
> `gpuSessionLock` and `cpuSessionLock` are never nested in either order.
> No alien code runs under either monitor or under the semaphore.

The third line changes three things: `events.onTransition` calls move outside the monitors (collect
the reason under the lock, emit after release — 843's remedy applied); `onBeforeGpuRelease` runs
at the `RELEASING` transition before the semaphore is taken; and the device-lost callback (§4.5)
runs after the lease's own lock-free classification, never under the semaphore or a monitor. If a
future callback needs to run after in-flight work drains, that is a new contract and a new lock-order
row, not a silent reordering.

### 4.4 `close()` semantics

`close()` transitions each slot to `CLOSED` under its own lock — sequentially, never nested — and
closes sessions after publishing (never a `LOST` session). `acquire()`/`acquireCpu()` on a `CLOSED`
handle throw `IllegalStateException`; `releaseGpu()` and `reportCpuSessionFailure` on a closed handle
are no-ops (lifecycle operations stay idempotent so a shutdown sequence that releases after closing
cannot throw — critical pass §10.7); `status()` reports a closed status. The stress test's "post-close acquires occur" assertion
becomes "post-close acquires throw and create nothing" (the factory call count is the oracle).
Idempotence of `close()` is preserved.

### 4.5 Device-lost: classify, mark, exit the Worker — never fall to CPU

**Classification.** A `RunFailureKind` classifier in `ort-common` on the `OrtException` from
`session.run`:

- `ARENA_OOM` — today's `isBfcArenaFailure`, unchanged; the session is healthy and the encoders'
  existing per-call handling owns it.
- `DEVICE_LOST` — a wrapped CUDA/cuBLAS/cuDNN/cuFFT fault from the CUDA EP (`CUDA failure <code>:
  …`, `CUBLAS failure …`, `CUDNN failure …`, `CUFFT failure …`) whose code is on the sticky list
  (§1.4), or whose code is unrecognised or absent (§3.3, sticky by default).
- `GPU_RESOURCE` — the **allocation class**, explicitly enumerated per library and never sticky:
  CUDA `cudaErrorMemoryAllocation`, `CUBLAS_STATUS_ALLOC_FAILED` / `NOT_INITIALIZED`,
  `CUDNN_STATUS_ALLOC_FAILED`, cuFFT alloc failures. These are VRAM pressure outside the BFC arena
  (cuBLAS/cuDNN workspaces) and occur precisely when the chat model is resident and VRAM is tight —
  the F-010 regime. Treating them as sticky would restart the Worker on VRAM pressure and, twice in
  five minutes, switch the encoders off during ordinary chat use (critical pass §10.1). They
  propagate like `OTHER`; the encoders' existing handling applies.
- `OTHER` — propagate unchanged.

**Escalation for a missed sticky.** The cost asymmetry runs both ways: a false sticky costs one
~40 s restart; a missed sticky leaves a Worker that answers every GPU call with the same error until
something else restarts it (search shows `EMBEDDING_EXCEPTION` on every query, every enrichment
batch fails). So the slot keeps a small run-failure counter: a **second wrapped CUDA fault of any
kind within the retry interval on the same handle** after a non-sticky classification is escalated
to `DEVICE_LOST`. One counter, reset on a successful run; no timer, no new thread.

`FailureCause` gains `DEVICE_LOST` so init and run failures share one telemetry vocabulary;
`classifyGpuInitException` is untouched. Every pattern has a test with the verbatim ORT message it
was taken from, plus a negative test that an arena message is not `DEVICE_LOST`.

**In the handle.** `Lease` gains one field: a `RunFailureHook` bound by the handle at lease creation
(the handle's own method reference; `Lease` stays a record and gains no handle type). On an
`OrtException` from a **GPU** lease, `Lease.run`/`runPinned` ask the hook to classify.
`ARENA_OOM`/`OTHER` → rethrow as today. `DEVICE_LOST` →

1. the hook transitions `READY → LOST{cause}` under `gpuSessionLock` **without closing the
   session** — the context is dead; the process exit is the close (§3.2);
2. the hook emits `GpuSessionLost(consumer, cause)` through `OrtSessionTelemetryEvents` (outside the
   lock) and **dispatches** the late-bound **`onDeviceLost(cause)`** callback to a dedicated
   one-shot thread. It is not invoked inline: the failing thread still holds the GPU semaphore
   through its open lease, so an inline call would be an alien call under the semaphore — the very
   thing §4.3 forbids (critical pass §10.3). The dispatch is the handle's only thread creation and
   happens at most once per handle;
3. the lease rethrows `GpuSessionLostException` (a subclass of `OrtException`, so every existing
   `catch (OrtException)` still matches and the six sites that catch nothing propagate it as they
   propagate everything else). `SpladeEncoder`'s pinned path will retry once on the heap path of the
   same lease before propagating (its existing non-arena branch); one extra failing call on a dead
   context is harmless and needs no edit.

Any later `acquire()` on a `LOST` slot **throws `GpuSessionLostException` immediately** — it does
not take the CPU slot. The process is exiting under a deadline that can span seconds (Lucene commit),
and a query landing in that window must fail fast, not start a CPU rerank it will never finish
(critical pass §10.4). `releaseGpu()` and `close()` on `LOST` publish their target phase without
touching the native session.

**In the Worker.** The composition root binds `onDeviceLost` on every handle to one process-wide
`GpuDeviceLossExit` (worker-core) that fires **once per process** (latched): it writes
`WorkerFatalReasonMarker.GPU_DEVICE_LOST` (a second constant beside `INDEX_CORRUPT`) with the cause
as detail, logs at ERROR naming the encoder and the CUDA code, runs the controlled shutdown 628
established (`closeQuietly` — index writer, gRPC, sessions **except lost ones**) under a deadline,
and exits with a distinct code. A deadline expiry hard-exits. This is the only place that decides
"the process ends"; `ort-common` never calls `System.exit`.

**In the Head.** `KnowledgeServerBootstrap` maps the marker to a new
`LifecycleReasonCode.WORKER_GPU_DEVICE_LOST` (`worker.gpu_device_lost`) with a **non-sticky**
retention class, so `superviseTick` restarts the Worker under the normal budget and backoff; the
readiness notice reads "GPU error — restarting the indexing service" while `WORKER_RECOVERING`
runs. Both codes are worded in `readinessNotice.ts` (`check-readiness-reason-codes` gate).

**Crash loop → encoders off, not CPU.** If a *second* `GPU_DEVICE_LOST` marker arrives within the
supervision stability window, the Head respawns the Worker with **ONNX encoders disabled** (a boot
flag the Worker honours by skipping deferred model init, which the null-safe callers already
tolerate: BM25 search, enrichment skipped, `NO_EMBEDDING_SERVICE` on the search trace) and holds a
sticky reason `WORKER_ENCODERS_DISABLED_AFTER_GPU_LOSS` with a remedy ("check GPU driver; restart
JustSearch to re-enable"). The flag is cleared by an app restart or a manual Worker restart from
the UI — the same lever `restart()` already exposes (`manualRestarting` already distinguishes it
from a supervised restart). No automatic re-enable, no timer, no CPU. The supervisor's own exhaustion
still applies to non-GPU crashes exactly as today.

Three facts the crash-loop rule depends on (critical pass §10.5, §10.6):

- The Head must **remember the previous device-lost marker's time** across a Worker boot: one
  in-memory timestamp in the spawner, cleared by an app restart — which is exactly the lifetime the
  "re-enable on app restart" rule wants.
- The hang detector cannot mistake an encoders-off Worker for a hung one: `checkHealth()` feeds
  `client.isHealthy()` — gRPC liveness — into the supervisor (`KnowledgeServerBootstrap:799-806`),
  while embedding readiness is a separate field on the health payload consumed only by the
  status/readiness surfaces (`WorkerStatusMapper:207`, `StatusLifecycleHandler:1625-1640`). Confirm
  at implementation that `isHealthy()` does not fold `embeddingReady` in; add a test that an
  encoders-off Worker is `READY` on the worker capability.
- The sticky reason must land on the **embedding** readiness surface, not only the worker one:
  today an encoder that never loads reads as `WORKER_HEALTH_EMBEDDING_NOT_READY` ("still loading")
  on that surface, which is the wrong message for a deliberate disable. The Worker's health payload
  carries the disabled state so the status handler words it as the sticky reason with its remedy.
- **The two null-safe claims are hypotheses until tested** (`audit-without-test`): the
  `KnowledgeServer` comment covers search and the indexing loop; NER enrichment, the reranker inside
  search, and the citation scorer inside chat/RAG must each be exercised with encoders off before
  the flag ships. One Worker-level test boots encoders-off and runs a search, an ingest, and a RAG
  turn.

### 4.6 Telemetry and status

- One new `TransitionReason`: `GpuSessionLost(consumer, FailureCause, cudaCode)`, emitted through
  `OrtSessionTelemetryEvents` and handled by `OrtSessionTelemetryAdapter` (counter
  `gpu_session_lost_total{cause}` beside the init-failure counter, plus the ERROR line). This is the
  Worker-side equivalent of what 898 asked from the Head-side transition log; the Head-side record
  is the lifecycle reason code the marker produces.
- `OrtCudaStatus` gains `lost(variant, path, message)`; because status is a slot field, `status()`
  and the Worker's readiness/debug surfaces show `LOST` in the instant before exit — useful in a
  post-mortem `worker.log.1`, which 374 already rotates for exactly this. If the debug-state shape
  changes, `check-runtime-manifest-closure` runs.
- The Head's `/api/debug/state` shows the lifecycle reason and, in encoders-off mode, the sticky
  notice; no new endpoint.

### 4.7 `@GuardedBy` convention and Error Prone at error

- **Annotation source:** `net.jcip.annotations` (the four existing sites use it; Error Prone's
  `GuardedBy` checker recognises it). `ort-common`, `app-inference`, and `adapters-lucene` declare
  the dependency explicitly rather than transitively.
- **Rule:** every non-final field that is written under a lock carries `@GuardedBy("<lock>")`; every
  class that declares a lock carries a one-line lock-order comment (or `@ThreadSafe` when it has one
  lock and no nesting). Error Prone `GuardedBy` promoted to `error` in `ErrorProneConventionsPlugin`
  for all modules.
- **The one documented exception — write-guarded snapshots.** A `volatile` field holding an
  immutable value that is written only under lock L and read lock-free is annotated on its
  **writer methods**, not on the field: the field carries a `// write-guarded by L; volatile publish,
  lock-free reads` comment and each writer method is `@GuardedBy("L")` (Error Prone checks that the
  method is only called with L held). This keeps the checker honest on writes and does not fight it
  on reads. The convention doc names the pattern so a reviewer recognises it instead of asking for a
  lock on the read.
- **Scope of the pass:** `NativeSessionHandle` (after §4.1: two fields, their writer methods),
  `OnlineModeOps.onlineRequestLock` (843), `IndexingCoordinator` (402 — a class-level comment naming
  the nesting; there is no field to annotate), `TransitionRunner` (already done; add the lock-order
  line). `SpladeEncoder` and `EmbeddingCompatibilityController` get `@NotThreadSafe`/`@ThreadSafe`
  with a one-line reason (thread confinement; monotone latches) — the convention should say *which*
  discipline a class uses, not only where it uses a lock.

### 4.8 Lock-order inventory (`docs/reference/concurrency-lock-order.md`)

A short canonical doc, honest about its size: a table of every place one exclusion primitive is
held while another is taken (today: `IndexingCoordinator` `writeBarrier.readLock → dispatchLock`;
NSH `gpuInferenceSemaphore → gpuSessionLock`), a second table of alien-call-under-lock sites and
their justification (after this tempdoc: `TransitionRunner.notifyListeners` only — with its reason
or a follow-up), a third of blocking-under-lock sites (`SqliteJobQueue` JDBC,
`InferenceLifecycleManager.stopLlamaServer`), and the three named disciplines (lock, thread
confinement, monotone latch) with one exemplar each. Maintained by hand; the consult-register hint
that already fires on concurrency-file edits points at it. No gate — the doc is a register of
declared nestings, and §6 states when a gate would be warranted.

### 4.9 JCStress pilot

**Wiring.** A `conventions.jcstress` plugin in `build-logic` modelled on `conventions.mutation`: a
`jcstress` source set in `modules/ort-common` compiled against main + testFixtures + Mockito, and a
`jcstress` `JavaExec` task running `org.openjdk.jcstress.Main` from `jcstress-core` (catalog entry;
no third-party Gradle plugin, the JavaExec is ten lines) with the report directory under
`build/reports/jcstress`. Nothing depends on the task (off-graph by omission, like PIT); `-PjcstressTime`
selects a short vs. full run. The task uses the module toolchain; if the harness rejects JDK 25, the
**task's** `javaLauncher` is pinned to the newest JDK it supports — the module is never downgraded
(§7 Q4). The result table (per test: outcome → count, expectation) is pasted into §Status once, and
the task is listed in the `/inference-runtime` register as a trigger-point verification beside the
stress test.

**Tests (fake sessions from §4.2; each `@State` constructs one handle with `gpuConfig` set and a
factory that returns fakes; each `@Actor` is one call; the arbiter reads the final slot):**

| Test | Actors | Forbidden outcome | 398 invariant |
|---|---|---|---|
| T1 acquire vs release | A: `acquire()` → record `isCpu`, `session == null`, `session.closed` at return; B: `releaseGpu()` | a GPU lease whose session is closed; any lease with a null session | #2 + defect 1 |
| T2 retry exclusion | A, B: `acquire()` on a `FAILED` slot with interval 0 | `createGpu` called more than once | #4 |
| T3 loss vs release | A: run on a fake that throws a sticky CUDA fault, close lease; B: arbiter flips to CPU, `releaseGpu()` | `onDeviceLost` dispatched more than once; its body observing a held monitor or semaphore (probe flag set by the callback thread); the lost session closed by anyone; any lease handed out after `LOST` instead of `GpuSessionLostException` | 898 item 2 + defect 2 |
| T5 create vs release | A: `acquire()` on `UNATTEMPTED` (factory blocks until B signals, then returns a fake); B: `releaseGpu()` then signal | a `READY` slot after B returned; the created fake not closed | defect 2 (the "release completed, then creator publishes" order) |
| T4 close vs acquire | A: `acquire()`; B: `close()` | a session created after `close()` published; a lease on a closed session without `IllegalStateException` | defect 3 |

Interesting-but-allowed outcomes (CPU-slot lease taken, `IllegalStateException` on acquire,
`CREATING` seen by the second actor) are declared `ACCEPTABLE_INTERESTING` so the table shows they
were exercised.

### 4.10 Tests beyond JCStress

- Unit (`NativeSessionHandleTest`): slot transitions per §4.1/§4.4/§4.5 on fake sessions,
  including `CREATING` seen by a second thread, `RELEASING` observed after native creation
  (session discarded), `LOST` never closed, and `close()` idempotence + post-close throws.
- Fault injection (898's acceptance, now in `ort-common` + `worker-core`): a fake GPU session that
  throws a verbatim `CUDA failure 700: an illegal memory access was encountered …` once; assert
  `GpuSessionLostException` reached the caller, `GpuSessionLost` was emitted with `cudaCode = 700`,
  `onDeviceLost` fired exactly once across two handles sharing the latch, no `close()` on the fake,
  and `status()` reports `LOST`. A Worker-level test drives `GpuDeviceLossExit` with the exit
  replaced by a probe and asserts the marker file content and the shutdown-deadline path.
- Head: `KnowledgeServerBootstrap` maps `GPU_DEVICE_LOST` to `WORKER_GPU_DEVICE_LOST` and restarts;
  a second marker within the window respawns with the encoders-off flag and holds
  `WORKER_ENCODERS_DISABLED_AFTER_GPU_LOSS`; a manual `restart()` clears it. Worker: the encoders-off
  flag skips deferred model init and search reports `NO_EMBEDDING_SERVICE`.
- Classification: one test per pattern with its verbatim ORT message; sticky code list pinned by a
  test that names each code so a CUDA-doc re-check is a diff, not a re-read.
- Stress (`NativeSessionHandleConcurrentStressTest`, `-PincludeStress`): keep the real-model
  CPU-only run; add a fake-session GPU-configured run so the GPU branches are finally exercised
  under load; flip the post-close assertion (§4.4). Rewrite its Javadoc coverage matrix: #1 is
  covered by the inventory + lock-order comment (a rule, not a test), #2 and #4 by T1/T2.
- Live (`use-every-verification-tier`): with a real model, `ai_activate` and provoke a release
  mid-batch (open chat during enrichment) — the production trigger for defect 1 — and confirm no NPE
  and a `GpuFallbackTaken` transition. A real device-lost cannot be provoked safely on a dev GPU; the
  fault-injection tests are the tier for that, stated as such. The Worker exit → Head restart →
  readiness-notice chain **is** live-verifiable by writing the marker by hand and killing the
  Worker; do that once and record the notice text observed.

## 5. Orphans, corrections, and the directive's reach

### 5.1 Shipped CPU-fallback behaviours this tempdoc does not change (owner directive, routed)

The directive rules out CPU fallback in *this* design. Three shipped behaviours also rely on it and
are out of this tempdoc's scope; they are routed to 898 (inference residuals) for the owner's call,
not changed silently here:

1. **GPU init failure at boot → CPU session + 60 s retry** (NSH:658-671, F-011). The encoders-off
   mode built in §4.5 is the honest alternative and would make this a one-line policy change.
2. **Query-time encoders on CPU while the chat model holds the GPU** (`releaseGpu` → CPU lease;
   ADR-0004 arbitration). Enrichment is already *deferred* rather than moved (`LoopPacingPolicy:58`);
   query-side SPLADE/rerank/citation still run on CPU during chat.
3. **Per-call BFC-arena ladders end on `acquireCpu()`** (`SpladeEncoder`, `OnnxEmbeddingEncoder`;
   F-010 default arena 6144 MB was chosen partly to avoid reaching that rung).

Also affected by the directive's spirit but not by its letter: the embedding backend's documented
default is CPU-only with GPU opt-in (`05-ai-architecture.md` §3). That is a default, not a fallback.

### 5.2 Corrections to the parent tempdocs

- **898 item 2**: "close + recreate the session once, then fall to the CPU session for the 60 s
  retry window" → replaced by §4.5 (sticky faults make in-process recreate futile; CPU ruled out).
  "emit the inference transition log entry (`NdjsonInferenceTransitionLog`)" → `GpuSessionLost` via
  `OrtSessionTelemetryEvents` plus the Head-side lifecycle reason code. "using the existing chaos
  hooks in `ort-common` tests" → the session-factory seam built here. 898 §Status records the
  hand-off and §5.1's routed items.
- **900 items 2-3** → pointer to this tempdoc; 900's scope list corrected: five race fixes, three
  lock-bearing classes, 862's subject retired by 872 (862 stays in the related list as evidence of
  the bug class, not as an annotation target).
- **398** → §Status "gate built in 919 (§4.9); invariant #1 became a documented rule in the
  inventory, #2/#4 are JCStress T1/T2"; its `related` line in 900 updated.

### 5.3 Orphans (deleted or replaced in the same PRs)

- **`NativeSessionHandleConcurrentStressTest`'s post-close tolerance** — replaced by an assertion
  (§4.4). Its Javadoc coverage matrix is rewritten, not appended to.
- **The eleven volatiles and the flags-based `OrtCudaStatus` skew** — replaced by slots; every
  comment/Javadoc that names a removed flag is updated in the same PR (`retire-with-a-sweep`).
- **`isBfcArenaFailure` stays** as the `ARENA_OOM` predicate (encoders call it by name); the new
  classifier wraps it rather than duplicating the substrings.
- **`EncoderBatchSweepBench:316`** bypasses `Lease.run` and therefore the device-lost path; it is a
  benchmark, so it is documented as out of the choke point rather than rewritten.
- **`WorkerFatalReasonMarker`'s single-reason shape** — gains a second constant; the Head's read site
  becomes a switch over reasons instead of one `equals`. No new file, no new format.

## 6. Reach

**Principle 1 — a mutable field group with more than one writer regime is a state value, not a set
of flags.** Instance here: `GpuSlot`/`CpuSlot`. Already conformed to elsewhere:
`TransitionRunner.modeState` (one `@GuardedBy("lock")` value), `EmbeddingCompatibilityController`
(atomic cells + monotone latches, a different but single regime). Candidate violators to check when
next touched, not now: `InferenceLifecycleManager`'s per-field flags around `runner.lock()`;
`SqliteJobQueue`'s ~30 lock sites (single lock, so one regime — likely conforming). **Earning its
keep:** a future concurrency fix in this repo that is a *slot transition* rather than a new flag; a
JCStress or stress finding expressed as "forbidden snapshot" rather than "flaky". **Retire when:**
two consecutive concurrency tempdocs find the slot shape added ceremony without removing a writer
regime.

**Principle 2 — no alien call under a lock** (843, now applied to NSH's two sites and to the
device-lost callback). Remaining known violator: `TransitionRunner.notifyListeners` under `lock`.
**Earning its keep:** the inventory's alien-call table shrinks to zero, or each remaining row
carries a written reason. **Retire when:** the table has been empty for two lanes and the
inventory's own maintenance is the only cost.

**Principle 3 — the fault seam is the construction seam.** Where a subsystem creates a native
resource is where a test injects a failing one; the state logic stays in one place and the seam is
one interface. Applies to llama-server process spawning (`LlamaServerOps`; the chaos tests there
kill processes rather than inject a failing spawn) and to `OnnxSessionCache`. **Earning its keep:**
the next "chaos hook" a tempdoc asks for already exists as a factory parameter. **Retire when:** a
generic injection framework lands and the per-subsystem factories become a duplicate.

**Principle 4 — recover at the unit that actually restores the resource, and label the recovery.**
A dead CUDA context is restored by a new process, not a new session; the Worker supervisor is that
unit, and the fatal-reason marker is the label that turns a crash into a diagnosis. Already conformed
to: 628's corrupt-index exit; `BrainSupervisionPolicy` for llama-server. Candidate scope: llama-server
CUDA faults (today a crash → generic supervision; the same marker pattern would name them), and the
boot-time GPU-init failure in §5.1. **Earning its keep:** a user-visible outage carries a reason
code instead of "worker process died"; the restart budget is spent on restarts that can succeed.
**Retire when:** every deliberate exit in the codebase already carries a reason and the marker is
the only channel — at which point it is infrastructure, not a principle.

**Not a principle, a scope note:** the lock-order inventory has two rows. That is the right size
for a repo that avoids nesting; a *gate* on it (ArchUnit or bytecode scan for nested monitor entry)
is warranted only when the table grows past a handful of rows or a reversed order is actually
observed — both are §6-recorded triggers, neither is met.

## 7. Questions — answered (2026-09-03, fable, owner delegated the decisions)

- **Q1 — closing a poisoned session.** Never, in-process. A `LOST` session is not closed by
  `releaseGpu`, `close`, or the loss path; the Worker exit is the close, and the exit has a deadline
  so a wedged CUDA teardown cannot keep the process alive. No timeout thread around `close()`.
- **Q2 — `GuardedBy` at error.** Repo-wide, in PR 4, after NSH is clean. If findings elsewhere are
  numerous, split the *fixes* across PRs; never split the promotion into a per-module flag.
- **Q3 — the transparent CPU rerun's concurrency.** Moot: there is no CPU rerun (owner directive).
  The failing call throws; the process restarts.
- **Q4 — JCStress on the JDK 25 toolchain.** Pin the `jcstress` task's launcher, never the module.
  Check `jcstress-core`'s supported range at implementation; record the version used in §Status.
- **Q5 (new) — restart budget for GPU losses.** Shared with crashes, not separate: a GPU that dies
  twice in five minutes is handled by the encoders-off respawn (§4.5) before the budget is exhausted,
  so a separate counter would never be reached.
- **Q6 (new) — should encoders-off ever re-enable automatically?** No. A timer would re-create the
  crash loop on the same bad driver; an app restart or a manual Worker restart is the user's
  explicit "try again".

## 8. Acceptance criteria

- `./gradlew.bat build -x test` green with Error Prone `GuardedBy` at error across all modules; an
  injected unlocked write to a `@GuardedBy` field in `ort-common` **fails compilation** (prove, then
  remove).
- `:modules:ort-common:test` green including the slot-transition, classification, fault-injection
  and post-close tests; `:modules:worker-core:test`, `:modules:worker-services:test`,
  `:modules:app-services:test`, `:modules:reranker:test` green (loss exit, telemetry adapter,
  bootstrap mapping, encoders-off boot).
- `./gradlew.bat test -PincludeStress=true --tests "*NativeSessionHandle*Stress*"` green on both the
  real-model CPU run and the fake-session GPU run.
- `./gradlew.bat :modules:ort-common:jcstress` runs; T1-T4 outcome tables in §Status with zero
  forbidden outcomes and every interesting outcome observed at least once.
- `check-readiness-reason-codes` green with the two new lifecycle codes worded;
  `check-runtime-manifest-closure` if the debug-state shape changes; `--gate adr-coverage` untouched
  (no ladder change).
- `docs/reference/concurrency-lock-order.md` exists, is linked from `docs/llms.txt`, and
  `agent-guide.md`'s static-analysis section names the `@GuardedBy` convention and the snapshot
  exception; `docs-regen` steps run. `05-ai-architecture.md` gains the device-lost paragraph (Worker
  exit → supervised restart → encoders-off on repeat).
- 398, 898, 900 statuses updated per §5; `/inference-runtime` register: new finding for the
  device-lost path, the encoders-off mode, and the JCStress trigger-point line; §5.1 routed to 898.
- Live checks per §4.10 recorded (release-mid-batch; hand-written marker → restart → notice text).

## 9. Sequence (four PRs)

1. **Slots + factory seam + lock order + close semantics** (§4.1-4.4) with unit + stress updates. No
   behaviour change except post-close throws. `@GuardedBy` on NSH's fields lands here at WARNING.
2. **JCStress pilot** (§4.9) — T1, T2, T4 (T3 waits for PR 3). Outcome tables to §Status. 398 closed.
3. **Device-lost** (§4.5-4.6): classifier, `LOST` phase + hook + callback, `GpuDeviceLossExit`,
   marker constant, Head mapping + encoders-off respawn + two lifecycle codes, T3, fault-injection,
   register update, `05-ai-architecture.md` paragraph.
4. **Convention** (§4.7-4.8): annotations on the other classes, Error Prone at error, inventory doc,
   agent-guide section, 900/898 pointers.

PR 4 is last so that the promotion to error lands on a codebase where NSH — the class most likely
to produce findings — is already clean. PR 3 is the only one that touches the Head; it can be split
into 3a (Worker: classify, mark, exit) and 3b (Head: map, respawn, encoders-off) if review size
demands, with 3a alone already correct (an unlabelled exit is today's crash path).

## 10. Critical-analysis pass (2026-09-03, same author, refute-first)

Findings against the design as first written; each is already folded into §4 where marked.

- **10.1 Sticky-by-default would have restarted the Worker on VRAM pressure.** cuBLAS/cuDNN
  workspace allocation failures are wrapped as `CUBLAS failure` / `CUDNN failure` and are not
  sticky; they occur when the chat model is resident and VRAM is tight (F-010). Under the first
  draft, two of them in five minutes would have switched the encoders off during ordinary chat.
  Fixed: an explicit non-sticky allocation class (`GPU_RESOURCE`) plus a two-strike escalation for a
  genuinely missed sticky (§4.5).
- **10.2 `CREATING → callers take CPU` was a new CPU-fallback window.** Today a second caller blocks
  on the monitor for the seconds of GPU creation and then uses the GPU; routing it to CPU would have
  put a query-time rerank on CPU during every warm-up — contrary to the owner directive and a
  regression. Fixed: waiters wait (§4.1).
- **10.3 The device-lost callback was specified as "outside the semaphore" but invoked from a thread
  holding it.** `Lease.run` executes inside an open GPU lease, i.e. with the semaphore held; an
  inline callback is an alien call under the semaphore, and the T3 probe would have flagged the
  design's own behaviour. Fixed: dispatch to a one-shot thread (§4.5).
- **10.4 `acquire()` on `LOST` taking the CPU slot was a CPU fallback with a fig leaf.** The exit
  deadline can span seconds; a query in that window would start a CPU rerank. Fixed: fail fast
  with `GpuSessionLostException` (§4.5).
- **10.5 "Release completed, then creator publishes" survived the `RELEASING` check.** A release
  that started during creation finishes by publishing `UNATTEMPTED`; a creator that then checks only
  "not `RELEASING`" would publish `READY` after the release — defect 2 reborn. Fixed: publish only
  if the slot is still the creator's own `CREATING` record; new JCStress T5 (§4.1, §4.9).
- **10.6 The crash-loop rule needed Head-side memory and a health-check guarantee.** "Second marker
  within the window" needs the previous marker's time to survive a Worker boot; and an encoders-off
  Worker must not be restarted as "hung". Verified: the hang detector consumes gRPC liveness only
  (`KnowledgeServerBootstrap:799-806`), embedding readiness is a separate surface. Added: the
  spawner timestamp, the embedding-surface reason, and the encoders-off end-to-end test — the
  null-safety of NER/reranker/citation with no models is an untested claim until then (§4.5).
- **10.7 `releaseGpu()` throwing on `CLOSED` could break shutdown ordering.** The arbitration
  coordinator may release after a close during teardown. Fixed: lifecycle operations stay
  idempotent no-ops on `CLOSED`; only lease acquisition throws (§4.4).
- **10.8 Still unverified, deliberately.** (a) The exact CUDA EP message format in the pinned ORT
  version — the design assumes `CUDA failure <code>: <text>` with a numeric code; if the code is
  absent the sticky-by-default rule carries the case, and the verbatim-message tests pin whatever
  the format is. (b) That `client.isHealthy()` ignores `embeddingReady` — one test in PR 3 makes
  this a fact. (c) `jcstress-core`'s JDK range — §7 Q4. None of the three changes the design's
  shape; each changes one line of it.
- **10.9 Residual risk accepted.** A deliberate exit loses the in-flight batch and any in-flight
  query; this is today's crash exposure with a label, and the job queue's re-queue semantics (627/
  628) are what make it acceptable. The PR-3 live check (hand-written marker → restart → notice)
  verifies the chain but not a real Xid fault; no dev-safe way to provoke one exists, and the
  design says so rather than claiming otherwise.

## Status

- 2026-09-03 — critical-analysis pass (§10): nine findings, seven folded into §4, two recorded as
  implementation-time verifications. Design considered settled.
- 2026-09-03 — theorized and designed (fable). Audits that fed §1: state inventory and run-path
  caller table; Error Prone/annotation/off-graph tooling and cross-module lock survey; Worker
  supervision and fatal-marker seam. Owner directive (no CPU fallback) received the same day; §4.5
  redesigned from "recreate once, then CPU" to "mark, exit, supervised restart, encoders-off on
  repeat"; §7 questions answered. Not started.
