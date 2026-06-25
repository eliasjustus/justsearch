---
title: "398 — ORT session concurrency invariants: regression gate for state-machine changes"
---

# 398 — ORT session concurrency invariants: regression gate for state-machine changes

## 0. Status

**Parked idea, not active work.** This tempdoc exists to capture an architectural
observation before it's forgotten. It should be picked up reactively — as a
precondition to a future tempdoc that touches `NativeSessionHandle`'s state
machine — not built speculatively.

Trigger conditions (any one fires the work):

- A tempdoc is scoped that physically refactors `NativeSessionHandle`'s state
  machine (arena-sizing changes, scheduler wiring, absorb lifecycle deeper into
  `OrtSessionAssembler`, etc.). The formerly-listed candidates (rename + factory
  inlining) landed in tempdoc 397 §14.22 / §14.23 and are no longer triggers.
- Tempdoc 395 A1/A4/A7 adaptive work begins touching retry logic, arena sizing,
  or per-hardware session parameterisation.
- Tempdoc 394 P3 scheduler introduces `RunOptions.priority` / `deadlineNs` or
  any other per-call state that changes how acquire/release interleaves.
- A production incident surfaces that pipeline-style tests couldn't catch
  (unlikely in the current code; this row is the "in case we're wrong"
  fallback).

Non-triggers (do **not** start this work for these reasons):

- "We should have more tests." Defensive testing without a refactor to defend
  against produces maintenance debt.
- "CI should have GPU coverage." The real cost isn't CI infra; it's the test
  design — see §7.
- "It would be good discipline." Discipline at the cost of unmotivated
  complexity is theatre.

---

## 1. Purpose

Tempdoc 397 Phase 2 landed a CPU-only concurrent stress test for
`NativeSessionHandle` (`NativeSessionHandleConcurrentStressTest`). §14.21 R4
expanded it with metadata-read + delayed-close threads. The class-level
Javadoc now explicitly documents which invariants the test covers (#3
deferred CPU recreation, #5 partial) and which it does not (#1 lock ordering,
#2 semaphore re-check after GPU release, #4 60 s retry trigger).

This tempdoc records *why* the uncovered three matter, *what they guard
against*, and *what design decisions* a future author would have to make to
cover them. It deliberately does not prescribe an implementation. The goal is
that when a state-machine refactor gets scoped, the author can read this doc
in ten minutes, orient to the invariants at stake, and decide what testing
shape their refactor needs — rather than rediscover the invariants from
scratch or, worse, miss them.

---

## 2. The gap: what's not tested, why, and what it matters for

Two existing test surfaces cover `NativeSessionHandle`:

- **`NativeSessionHandleTest`** (same-package unit tests) — narrow unit-level
  assertions on construction, idempotent close, deferred-CPU materialisation.
  Single-threaded; no GPU path exercised.

- **Pipeline run (`jseval run --dataset scifact --max-queries 0 --pipeline`)**
  — end-to-end exercise of all six encoders: embed, SPLADE, NER, BGE-M3,
  reranker, citation. Real CUDA sessions, real inference, real arena usage.
  What it does **not** exercise:
  - `releaseGpu()` firing mid-query. jseval never toggles the Main-GPU-active
    signal; production does (every time a user opens chat while a query or
    batch is running).
  - High-cadence concurrent `acquire()` on a single handle. The indexing loop
    is one-batch-at-a-time; reranker/citation can see parallel queries but
    serialise via `gpuInferenceSemaphore` without typically racing the
    semaphore's release edge.
  - Post-close behaviour. Pipeline shuts cleanly; no thread is ever mid-acquire
    when the handle terminates.
  - `reportCpuSessionFailure()` concurrent with ongoing acquires. Real CPU
    failures (BFCArena OOM on long inputs) are rare enough that production
    rarely sees concurrent load during recreation.

- **`NativeSessionHandleConcurrentStressTest`** (the Phase 2 + R4 test) —
  CPU-only handle. Covers concurrent acquire/release/recreate/metadata-read/
  close at millisecond cadence. The GPU-path branches in `acquire()` / 
  `releaseGpu()` / `selectSession()` are unreachable because `gpuConfig` is
  null. Invariants that live on those branches are therefore untested.

---

## 3. The three invariants

Stated as contracts, not code. Each names what could break, what observable
production scenario would trigger the break, and what symptom would surface.

### #1 — Lock-acquisition ordering

**Contract:** if any code path ever needs both `gpuSessionLock` and
`cpuSessionLock`, it acquires them in the order
`gpuSessionLock → cpuSessionLock`, never reversed.

**Current state:** the invariant is latent. No current code holds both locks
simultaneously; `tryCreateGpuSession` touches only `gpuSessionLock`;
`getCpuSession` touches only `cpuSessionLock`. A reversed-order acquisition
introduced by a future refactor would deadlock against any canonical-order
caller.

**Trigger scenario:** a future method that coordinates GPU and CPU session
lifecycle — e.g., a "swap arena budget" or "flip execution provider" operation
introduced by 395 A1 adaptive work. If it holds `cpuSessionLock` and then
tries to acquire `gpuSessionLock`, canonical-order callers running concurrently
deadlock.

**Symptom:** thread dump at 90 s in the stress test's join window shows two
threads holding locks in opposing orders. Pipeline never surfaces it.

### #2 — Semaphore re-check after `acquireUninterruptibly`

**Contract:** after `gpuInferenceSemaphore.acquireUninterruptibly()` inside
`acquire()`, the caller re-checks two conditions before returning a GPU-backed
lease: `gpuSessionReleasing` must be false, and the captured `session` must
still equal `gpuSession`. If either fails, the caller releases the semaphore
and falls back to a CPU lease.

**Current state:** the re-check exists in `NativeSessionHandle`'s `acquire()` method. The
invariant is live — it runs in production every time Main claims the GPU
during an inflight query.

**Trigger scenario:** the real-world race that happens whenever a user opens
chat during a query or batch:

1. Thread A captures `session = gpuSession` and enters
   `gpuInferenceSemaphore.acquireUninterruptibly()`.
2. Thread B (Main-GPU-active signal fires) enters `releaseGpu()`, sets
   `gpuSessionReleasing = true`, acquires the semaphore first, closes
   `gpuSession`, nulls the reference, releases the semaphore.
3. Thread A acquires the semaphore. Its captured `session` reference now
   points at a closed ORT session.

Without the re-check, thread A returns a lease holding a closed session. The
next `session.run()` call either crashes with a native ORT error or silently
returns NaN outputs (F-009).

**Symptom:** native crash, NaN-polluted embeddings, or a cryptic
`OrtException` in logs at exactly the moment a user opens chat. Pipeline
never produces the race condition because jseval owns the GPU for its entire
run.

### #4 — Retry window exclusion

**Contract:** at most one GPU-session recreation attempt runs per
`gpuRetryIntervalMs` window (default 60 s). Concurrent threads entering the
retry predicate must coordinate to produce a single retry, not N.

**Current state:** the predicate uses `gpuSessionAttempted` + `gpuFailedAtMs`
under `gpuSessionLock` double-checked locking. The invariant is live under
production load — reranker + citation paths can see parallel queries that
both hit the retry predicate simultaneously.

**Trigger scenario:** after a transient CUDA failure (e.g., OOM during
creation), multiple concurrent queries each discover `!gpuAvailable` and
enter the retry predicate. Without correct double-checked-locking, each
thread attempts its own recreation — wasting work, racing on
`gpuSessionAttempted`, possibly leaving state flags inconsistent.

**Symptom:** log lines showing multiple simultaneous "GPU retry —
Xs since last failure, re-attempting" messages for the same handle. Resource
exhaustion from N simultaneous session-creation attempts competing for VRAM.

---

## 4. Why pipeline tests miss these

The common thread: pipeline testing measures *throughput and quality of the
happy path*. The invariants above guard against *rare, time-sensitive,
adversarial interleavings*. Pipeline's signal shape (elapsed time, nDCG,
docs indexed) is insensitive to sub-percent-rate concurrency bugs that
corrupt individual results.

Concretely:

- A 0.1 % NaN rate from a stale-session race would lower nDCG by a few
  basis points — indistinguishable from model-quality noise.
- A deadlock from reversed-order lock acquisition would appear as
  "pipeline hung"; attribution to lock ordering vs. any of ten other causes
  takes a thread dump and a day.
- A retry-window violation that triggers 3 simultaneous recreations burns
  VRAM for a few seconds — possibly causing an OOM that's attributed to
  "transient GPU memory pressure."

The invariants matter *because* they fail invisibly. A regression gate that
catches them explicitly — by name — is the only reliable early warning.

---

## 5. Why this is not work today

Three reasons stacked:

1. **Zero observed incidents.** The native state machine (`OrtSessionManager`, renamed to `NativeSessionHandle` in §14.23) has shipped since tempdoc
   359 (~6+ months). No production report traces to any of the three
   invariants. The state machine is correct; it just doesn't have a dedicated
   regression gate yet.

2. **Speculative tests are maintenance debt.** A test that defends against
   "bug classes we don't have, in refactors we haven't scoped" decays faster
   than it catches anything. Each CI run costs minutes; each flake is a
   half-day debug.

3. **The right test shape depends on what the refactor is doing.** A rename
   wants byte-for-byte behaviour preservation — scenario-based tests are
   ideal. Adaptive arena sizing wants statistical throughput characterisation
   — probabilistic stress is ideal. Building one test shape now risks it
   being the wrong shape for whatever actually lands.

The honest path is: park the observation, pick it up when a triggering
refactor lands, shape the test to that refactor's risk profile.

---

## 6. When this becomes work

The trigger tempdoc proposes a concrete refactor. Before that refactor
lands, the trigger tempdoc pays the cost of building a targeted regression
gate for the invariants its refactor could break.

Example mappings:

| Triggering work | Invariants most at risk | Test shape likely needed |
|---|---|---|
| ~~Rename `OrtSessionManager` → `NativeSessionHandle`~~ | None — pure mechanical | Landed in 397 §14.23; re-ran stress + pipeline, green |
| ~~Inline `OrtSessionFactory` into assembler~~ | None — no state-machine change | Landed in 397 §14.22; same |
| Absorb lifecycle into `OrtSessionAssembler` | #1, #2, #4 all at risk | Full scenario-based GPU regression suite |
| 395 A1 adaptive retry logic | #4 primarily | Time-compressed scenario test for retry coordination |
| 395 A4 per-hardware arena sizing | #2 (session replacement edges) | Acquire + release race under varying config |
| 394 P3 scheduler (`priority`, `deadlineNs`) | #2 (acquire path gains branching) | Acquire-cancellation scenarios |

The triggering tempdoc's scope includes designing *only* the invariant coverage
its changes risk. Not a catch-all suite.

---

## 7. Open design questions

A future author would have to answer these before implementing. This section
frames the questions without prescribing answers — the right answer depends
on the refactor's shape.

### 7.1 Dependency model

Should the test require real CUDA + VRAM, or mock the ORT session layer?

- **Real CUDA:** matches production bit-for-bit. Naturally exercises native
  session creation, including the specific bug classes that cause BFCArena
  failures and logger races. Cost: test only runs on machines with GPUs;
  CI-unfriendly; `assumeTrue(modelDir)` pattern needed.
- **Mocked session layer:** runs everywhere, isolates concurrency logic from
  native-library behaviour. Cost: significant mocking surface (ORT's Java
  bindings don't compose well with Mockito); risks testing the mock rather
  than the real state machine.
- **Hybrid:** real CUDA for #2 and #4 (they need session lifecycle); abstract
  or explicit test hooks for #1 (purely a lock-placement property).

Decision depends on: whether CI gets GPU runners; whether the refactor changes
session creation (making mocks brittle); team appetite for the abstraction.

### 7.2 Test shape: probabilistic stress vs. scheduled scenarios

- **Probabilistic stress** (the current test's shape): many threads, random
  interleavings, assert no observable failures. Easy to write; catches gross
  bugs; weak for narrow race windows.
- **Scheduled scenarios** (using explicit synchronisation points): "thread A
  pauses at acquireUninterruptibly's entry; thread B fires releaseGpu
  completely; resume A; assert CPU fallback." Deterministic; reproduces
  specific races exactly; expensive to write and fragile to refactor.
- **Formal concurrency testing frameworks** (JCStress, Lincheck): enumerate
  schedules systematically; highest confidence; steepest learning curve;
  requires extracting the state machine into a testable shape those tools
  understand.

Decision depends on: what invariant is being tested. #2 is a narrow race —
scenarios are the right tool. #4 is a statistical property — stress is fine.
#1 is a property (no-deadlock) — scenarios for specific lock acquisition
orders.

### 7.3 Time abstraction

Invariant #4 depends on `System.currentTimeMillis()` elapsed deltas.
Post-Phase-4, `gpuRetryIntervalMs` is configurable via the Builder, so
tests can set a short interval (e.g., 500 ms) and run in real time.

Question: is testing the 60 s-specific production value ever needed? Probably
not — if the retry *mechanism* is correct for 500 ms, it's correct for 60 s.
But a test that pins the default value flags accidental changes to the
constant.

### 7.4 Surfacing purely-latent invariants (#1)

#1 is latent: no current code path could violate it, because no current code
holds both locks. A regression test is a guard against *future* code that
might. Options:

- **Code-level assertion** (e.g., `LockOrderMonitor` that wraps `synchronized`
  blocks and asserts acquisition order). Cheap, always-on, fires in production
  too — which might be undesirable.
- **Architectural test** (ArchUnit-style: inspect bytecode, assert no method
  contains `synchronized (cpuSessionLock)` with `synchronized (gpuSessionLock)`
  nested inside). Cheap, compile-time, doesn't run in production, but fragile
  to code style.
- **No test; rely on code review.** Honest about the invariant's latency;
  cheapest; risks future violations.

Decision depends on: how much the future refactor might actually touch lock
structure. A rename doesn't; arena-sizing work might not; a full state-machine
absorption almost certainly does.

### 7.5 Scope creep vs. minimum viable regression gate

A complete suite that exercises all three invariants with high confidence
could take a week. The minimum viable version that catches #2 (the
highest-consequence invariant) might take half a day. The triggering
refactor's risk profile sets the bar: enough coverage that "passes this test
suite" is meaningful evidence that the refactor didn't break the invariant,
but not so much that the test suite becomes a second refactor.

---

## 8. What success looks like

When this eventually lands, the pattern should be:

1. The triggering tempdoc scopes a refactor (e.g., 395 A1 adaptive retry).
2. Its author reads this tempdoc.
3. They write a targeted test (not a full suite) that defends against the
   specific invariant their refactor touches.
4. The test lives alongside `NativeSessionHandleConcurrentStressTest` (same
   package, `@Tag("stress")` exclusion for CI, opt-in via
   `-PincludeStress=true`).
5. Their refactor lands with the test as its regression gate.
6. Future refactors inherit the test and extend it if they touch adjacent
   invariants.

The alternative — a standalone 398 PR that builds a comprehensive GPU
concurrency suite in isolation — is the wrong shape. It would be the test
suite equivalent of the "solution in search of a problem" anti-pattern:
correct in principle, decaying in practice, hard to justify when the
first real refactor arrives and wants a different test shape.

---

## 9. Related tempdocs

- **397 — Session policy centralisation.** Closed. §14.16 landed the Phase 2
  CPU-only stress test; §14.21 R4 added metadata-read + delayed-close
  coverage. This tempdoc picks up where §14.21 left off — documenting the
  invariants that remained out of scope for 397 and framing when they
  re-enter scope.
- **395 — Adaptive pipeline considerations.** A1/A4/A7 adaptive work is the
  most likely trigger tempdoc. When it begins, the author of this tempdoc
  should re-read it in that tempdoc's scoping phase.
- **394 — Encoder call-path batching.** P3 scheduler (priority / deadline)
  is a secondary trigger.
- **359 — Reranker architecture audit.** Original `OrtSessionManager`
  introduction; F-009 NaN-on-CPU-OOM finding. Historical context for why
  the state machine is shaped the way it is.
- **311 — ORT session lifecycle research.** Pre-history of the arena +
  session lifecycle model. Useful for understanding why the invariants
  exist in the first place.

---

## 10. What this tempdoc is not

To prevent scope creep if someone picks this up and wants to turn it into
a feature:

- **It is not a work plan.** Work plans prescribe implementations. This
  tempdoc prescribes *when* to make implementation choices.
- **It is not a call to add more tests now.** The whole point of §5 is that
  speculative tests are worse than no tests.
- **It is not a critique of pipeline testing.** Pipeline tests catch what
  they catch; this tempdoc just maps what they don't.
- **It is not a bug report.** There is no reported bug in any of the three
  invariants as of writing.
- **It is not an endorsement of any particular testing framework.** §7
  explicitly defers that choice to the triggering tempdoc.

If a future reader starts turning this into a work plan, they should pause
and verify the trigger condition has actually fired. If it hasn't, the
right move is to close their draft and wait.
