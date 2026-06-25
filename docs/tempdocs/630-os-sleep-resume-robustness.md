---
title: "OS suspend/resume robustness: a resume falsely trips wall-clock supervision detections — most dangerously the Worker heartbeat suicide-pact (627's 'zombie' row) self-terminates on every wake. Close the liveness-continuity precondition on the 627 detection layer by gating the Worker suicide-pact on clock-immune Head-PID liveness (not a new power subsystem). [Originally designed as a resume detector + baseline-reset; the implemented Head-PID gate supersedes it — see Implementation outcome.]"
type: tempdoc
status: "MERGED to main (2026-06-22, feat commit cd8946d3c). (1) Crux: the Worker heartbeat suicide-pact no longer self-terminates on OS resume — gated on clock-immune Head-PID liveness. (2) Latency: the Head health-monitor doubles as a resume detector and eagerly reconnects gRPC + re-registers watchers/reconciles on wake. Remaining: attribution guard + FE refinements deferred-by-design; an eager Brain /health probe is a small noted follow-on. See '# Implementation outcome' + '## Latency-hardening slice'."
created: 2026-06-22
updated: 2026-06-22
related:
  - 627-process-supervision-crash-recovery   # the seam this conforms to; suspend is a confound on its detection layer
  - 626-incremental-indexing-correctness      # reconciler backstops a suspend-killed watcher; wake kicks core.reconcile-root
  - 628-index-durability-corruption-recovery  # a suspend mid-commit must not force the corruption path via a spurious kill
  - 629-data-at-rest-encryption               # sibling gap-analysis idea; orthogonal
---

> NOTE: Noncanonical working tempdoc. **Investigated + designed 2026-06-22** — see
> `# Investigation (2026-06-22)` for the verdicts against `main` (the absence
> claims hold, §A; failure-mode severities and the claim-2 framing are revised,
> §C/§D/§F) and `# Design (long-term) — 2026-06-22` + `# Reach` for the design.
> The headline: this is **not** a new power subsystem — it is a missing
> *validation step* on the time-based detections of the 627 supervision contract;
> a resume falsely trips the Worker heartbeat suicide-pact (627's "zombie" row).
> The charter below (§The gap … §Next step) is the original idea-stage record;
> trust the Design/Reach + Implementation-outcome sections for current truth.

---

# Implementation outcome (2026-06-22)

> Crux fix implemented in `worktree-630-resume-suicide-fix` (uncommitted, pending
> review). Scope was deliberately the **crux correctness fix only** (the confidence
> pass showed the resume detector is unnecessary for correctness once the suicide is
> gated on Head-liveness). All deferred items below are recorded, not built.

## What shipped — the clock-immune Head-liveness gate

The worker suicide-pact (`MmfWorkerSignalBus.shouldDie`) no longer kills the Worker
on a stale heartbeat **alone**. It now requires the heartbeat to be stale **AND**
Head to be actually gone. A suspended Head keeps its PID across the suspend, so on
resume the Worker sees `Head ALIVE` and does not suicide — **clock-immune**, needing
neither a resume detector nor a clock-seam refactor. This *supersedes* the Design's
"reset/grace" approach (§Design.2): it additionally closes the orphan-window risk a
timed grace would open on Linux/macOS (the confidence pass §C finding).

Components (all in the worktree):
- **`WorkerLivenessDecision`** (NEW pure seam, `modules/worker-services/.../loop/`):
  `shouldDie(shutdownRequested, uptimeMs, startupGraceMs, heartbeatEpochMs, nowMs,
  heartbeatStaleMs, HeadLiveness)`. Mirrors `SupervisionDecision` /
  `PolledStateLiveness` (pure, `nowMs` injected). `HeadLiveness ∈ {ALIVE, DEAD,
  UNKNOWN}`; `UNKNOWN` (no PID forwarded — standalone/tests) preserves pre-630
  heartbeat-only behavior. Registered as logic-seam `worker-liveness-suicide`
  (`governance/logic-seams.v1.json`); PIT-measured **93% test-strength, 0
  no-coverage** (`gates/test-efficacy/strength-baseline.v1.json`).
- **`MmfWorkerSignalBus`**: new 2-arg constructor `(Path, headPid)`; `shouldDie()`
  is now a thin shell gathering `nowMs`/heartbeat/`headLiveness()` (a
  `ProcessHandle.of(headPid)` probe) and delegating to the pure seam. 1-arg
  constructor preserved (`headPid=0` ⇒ UNKNOWN ⇒ legacy).
- **Head→Worker PID forwarding**: new `EnvRegistry.HEAD_PID`; `WorkerSpawner.build
  Command` adds `-Djustsearch.head.pid=<ProcessHandle.current().pid()>` (computed
  live in Head, the `HARDCODED_FORWARDED` pattern); `KnowledgeServer` reads it via
  `EnvRegistry.HEAD_PID.getLong(0)` at signal-bus init.
- **Contract**: `governance/supervision-contract.v1.json` zombie row gains a
  `livenessContinuity` clause + the new guard; `SupervisionContractTest` asserts it.
- **Class-size changeset** for the +7/+5 LOC on `EnvRegistry`/`KnowledgeServer`.

## Validation (done)

- Unit: `WorkerLivenessDecisionTest` (9-case truth table incl. the **resume
  regression** `stale + ALIVE ⇒ no die`, the boundary at uptime==grace, and
  `DEAD/UNKNOWN ⇒ die`), `WorkerSignalBusTest`, `SupervisionContractTest`,
  `WorkerSpawnerConfigForwardingTest`, `configuration:test` — all green.
- PIT mutation on the seam: 93% / 0 no-coverage.
- Gates: `check-logic-seams` + `class-size` green; full `build -x test`
  (compile + spotless + PMD) green.
- **Real-process** (`ChaosSuiteTest`, worktree): genuine zombie detection still
  fires (`Worker terminates when heartbeat goes stale` — UNKNOWN/legacy path),
  plus crash-recovery + mid-traffic-kill recovery — the non-regression is proven.
- Not feasible in-harness: a real OS suspend, and a live UI check of *this*
  worktree's backend (the dev stack is bound to `main`). The fix is subtractive
  (removes a spurious wake alarm) with no new user-visible feature, so there is no
  positive UI feature to validate; the user-driven smoke is a real laptop
  sleep→wake confirming the Worker survives.

## Latency-hardening slice — resume detector + eager re-validation (2026-06-22, IMPLEMENTED)

> Built on explicit user direction (the latency half was originally deferred as
> the PID-gate makes it unnecessary for *correctness*). Conforms to existing
> infrastructure — no new subsystem/thread.

The Head-side `KnowledgeServerHealthMonitor` (already ticking every 10 s) doubles
as the **per-process resume detector** (Design §1): a pure
`ResumeDetector.resumeGapMs(lastTickWallMs, nowWallMs, expectedIntervalMs,
toleranceFactor)` (`modules/app-services/.../worker/ResumeDetector.java`) flags a
tick whose inter-tick wall-clock gap exceeds `pollInterval × 3` (>30 s) as a
suspend/resume. On a detected resume the monitor **eagerly re-validates** the
Worker surface *before* its health check (so a stale post-wake channel doesn't
flip the capability to DEGRADED), using the **existing** actuators:
`RemoteKnowledgeClient.reconnect()` (gRPC channel) + `reindexPersistedRoots()`
(re-registers each root's watcher **and** kicks a freshness-skipping reconcile
walk — catching filesystem events missed while the watcher was frozen). Each step
is best-effort + guarded; the reactive paths (first-RPC reconnect, periodic sync)
remain the backstop. Wall-clock by necessity (a monotonic clock's suspend
behavior is platform-inconsistent — research pass). Benign both ways by design:
a missed resume falls back to reactive recovery; a false positive is one extra
cheap reconnect+reconcile — so it is **not** a logic-seam (no silent-wrong-value
law), and the tolerance is generous so GC/scheduler jitter never trips it.

**Validation:** `ResumeDetectorTest` (5-case truth table) + 4 new
`KnowledgeServerHealthMonitorTest` cases (first/normal tick ⇒ no re-validation;
1-hour gap ⇒ `reconnect()` + `reindexPersistedRoots()`; survives an unavailable
client without aborting the tick or flipping DEGRADED) — all green; compile +
class-size gate green. **Not in-harness reproducible:** the user-visible effect
(a shorter "Reconnecting…" window + faster post-sleep reconcile) needs a *real*
OS suspend, and the MCP dev stack runs `main`'s build, not this worktree — so the
live idle-no-false-positive check and the wake-latency effect are a **user-driven
real-suspend smoke** (per `slice-execution.md`: dev-stack-only items documented as
a runnable smoke). The no-false-positive *logic* is unit-proven against the real
10 s cadence.

**Remaining small follow-on (not built):** an analogous eager `/health` probe for
the Brain (llama-server) on resume — a *second* detector in `app-inference`. Left
out because the Brain self-recovers (per-request `HttpClient` reconnects on the
first post-wake request; 30 s periodic `/health`), so the value is marginal.

## Deferred-by-design (recorded, NOT built — superseded for correctness by the PID-gate)

- **`SupervisionDecision.recentlyResumed` attribution guard**: the confidence pass
  showed it is unlikely to ever fire (reconnect-before-RPC + 30 s streak).
- **FE "Reconnecting after sleep…" wording / proactive `resume`-event reconnect**:
  the FE already handles the wake window calmly (`aiStateStore` `channel-stale`).
- **nanoTime timeout micro-hardening; Tauri OS power hook** (already scoped out).

## Notes for review / merge

- The worktree base (a mid-flight 627 commit) has **pre-existing** governance-gate
  failures unrelated to 630 (`ts-any`, `clone`, `consumer-drift`,
  `execution-surface`, `contract-projection` — all referencing untouched TS/schema
  files; logged to observations). A clean pre-merge `build` requires those to be
  green on the base first; 630's own gates (`logic-seams`, `class-size`,
  `supervision-contract`) pass. The pre-existing `IndexerWorkerGuardrailsTest`
  ArchUnit failure (`TikaOcrRuntime` reads env) is likewise not 630's.

## Final disposition (2026-06-22)

630 is **closed at the crux**, with every deferred item declined on sound grounds
(not "wait-for-evidence" — these are correctness / AHA grounds, which
`structural-defects-no-repeat` permits). The bug-class's *harmful* manifestation
(the suicide-pact false-fire on wake) is eliminated; the remaining time-based
class-members are benign-direction, non-product, or already-backstopped:

- **Lease churn (271/606) — confirmed non-product + benign.** `OperationLease
  ServiceImpl` is **dev-runner-only**, gated on `JUSTSEARCH_DEV_RUNNER_STATE_ROOT`
  (set by `dev-runner.cjs`); absent that env var it is a no-op, so production
  launches are unaffected. A stale lease on wake touches only dev-stack *agent*
  coordination, and the 606 ownership model already resolves it via its
  user-approved takeover handshake. Not a user-facing bug → no fix.
- **Circuit breakers** (`EngineCircuitBreaker`, `ipc-common/GrpcCircuitBreaker`):
  a suspend only makes recovery happen *sooner* (benign direction) → no fix.
- **Watcher staleness**: 626's reconciler is the convergence authority → latency,
  not silent drift → no correctness fix needed.
- **gRPC / llama-server IPC**: `RemoteKnowledgeClient.reconnect()` runs before
  every RPC, so the first post-wake user action reconnects immediately; the
  background health flag clears within a poll, shown calmly by the FE.
- **Detector + eager re-validation / `recentlyResumed` guard / FE wording /
  nanoTime migration / Tauri OS hook**: latency-only, dead-code-prone, already
  handled, or contingent — building any now would be the premature generalization
  the Reach section argues against. They remain recorded candidate-scope, to be
  pulled in only if a specific site is shown to misfire with a real adverse
  outcome.

Title note: the headline originally named the *designed* mechanism (a per-process
resume detector + baseline-reset); the *shipped* mechanism is the clock-immune
Head-PID-liveness gate (a confidence-pass improvement that closes the orphan-
window a timed grace would open). The title has been updated to match.

# 630 — OS sleep / hibernate / resume robustness

## The gap

JustSearch runs as a background desktop app (Head + Body/Worker + Brain) that
indexes continuously and watches folders for changes. On a real laptop it is
**suspended mid-operation routinely** — lid close, system sleep, hibernate.
Today nothing in the codebase reacts to suspend/resume:

- **No power-event listeners.** Survey finding (unverified by author): no
  `WM_POWERBROADCAST` / `SystemEvents.PowerModeChanged` /
  `PowerSettingNotification` handling anywhere. The 8 grep hits for "resume" are
  all "resume *indexing*", unrelated to OS power state.
- **Wall-clock timeouts.** Timeouts/elapsed-time logic use
  `System.currentTimeMillis()` throughout, with no monotonic-clock guard. When
  the machine sleeps for an hour and wakes, wall-clock-based deadlines can fire
  spuriously (a "5-second" wait can observe a 1-hour delta).
- **No re-registration / reconnection on wake.** File watchers, the
  Head↔Worker gRPC channel, and the Head→llama-server HTTP connection are not
  re-validated after resume.

## Failure modes this leaves open

1. **Silent index staleness** — file watchers can go dead across a suspend; a
   missed filesystem event means a changed/added/deleted file is never picked up
   and the index silently drifts from disk. (The 626 reconciler is the
   eventual backstop, but only when it runs — between reconcile passes the index
   lies.)
2. **Spurious timeout firing** — health-check / readiness / lease timeouts
   computed on wall-clock time can trip on wake, potentially triggering
   unnecessary restarts, ownership-lease churn (271/606), or DEGRADED states.
3. **Dropped IPC after wake** — gRPC / llama-server sockets may be half-open
   after a long suspend; the first post-wake call fails until something
   reconnects. Whether the existing supervision (627) recovers this gracefully
   or surfaces a scary error is unknown.
4. **In-flight work at suspend** — an indexing commit or a model load
   interrupted by hibernate: does it resume cleanly, or does it look like a
   crash to 627/628's recovery paths?

## Why this isn't already covered

- **627 (3-process supervision & crash-recovery)** turns "a crash does not
  cascade" into an exercised contract — but **suspend is not a crash.** A
  suspended process is alive, its clocks paused, its sockets stale; the
  recovery policy for "process was frozen and is now thawed" is a different
  contract than "process died." 627 explicitly scopes to faults/crashes.
- **606 (dev-stack ownership / liveness)** and **604/605 (liveness & run
  reliability)** are about presence/observer liveness, not OS power state.
- **626 (incremental indexing correctness)** provides the reconciler that would
  *eventually* repair watcher-missed changes, but doesn't react to wake to
  trigger an immediate reconcile.

No existing tempdoc treats suspend/resume as a first-class lifecycle event.

## The idea (design questions, not yet decisions)

1. **Detection** — where does the power-event signal enter? Options:
   - Tauri/Rust shell (`modules/shell`) listening for OS power broadcasts and
     forwarding a "resumed" event to Head.
   - Java-side detection (monotonic-clock gap detector: a watchdog thread that
     notices `System.nanoTime()` elapsed ≫ expected sleep interval → infers the
     machine was suspended). This works even without an OS hook and is
     cross-platform.
   - Probably **both**: the gap-detector as the always-available signal, the
     OS hook as the precise/early one.
2. **Monotonic clock discipline** — audit timeout/elapsed logic and move
   duration math off wall-clock onto `System.nanoTime()` where a sleep-induced
   jump would cause a wrong decision. (This is a standalone correctness win even
   before any wake-handler.)
3. **Wake handler** — on resume, deterministically: re-validate/re-register file
   watchers, health-check + reconnect gRPC and llama-server, kick an immediate
   626 reconcile pass to catch missed filesystem events, and suppress the
   spurious-timeout restart storm.
4. **Boundary with 627/628** — a wake must not be misclassified as a crash. The
   supervision and durability layers need to distinguish "frozen then thawed"
   from "died then restarted" so they don't run unnecessary corruption rebuilds
   or restart caps.
5. **Scope** — sleep vs. hibernate vs. fast-startup; Windows first (primary
   target), but the monotonic-gap approach generalizes.

## Severity / horizon

Alpha-relevant reliability — this is a **common** real-world path (every laptop,
every day), not an edge case, and it's currently entirely unhandled. The
monotonic-clock half is cheap and independently valuable; the
watcher/IPC-re-validation half is the substantive design.

## Related

- [[627-process-supervision-crash-recovery]] — must distinguish thaw from
  crash; the wake handler may reuse 627's reconnect/restart actuators.
- [[626-incremental-indexing-correctness]] — the reconciler is the repair
  mechanism a wake should trigger to close watcher gaps.
- [[628-index-durability-corruption-recovery]] — a suspend during a commit must
  not be misread as corruption.
- [[629-data-at-rest-encryption]] — sibling idea from the same gap analysis.

## Next step

Investigation pass: confirm the absence claims against `main` (grep for any
power-event handling in `modules/shell` Rust + Java; audit wall-clock timeout
sites), enumerate the watcher/IPC surfaces that need re-validation on wake, and
decide the detection mechanism (gap-detector vs OS hook vs both) as a design
fork.

---

# Investigation (2026-06-22)

> Done per assignment: understanding, not a plan. No design or implementation
> yet. Findings are tagged **[VERIFIED `file:line`]** (the tempdoc author read
> the source) vs **[SURVEY]** (a subagent grep-survey reported it; line numbers
> are a starting point, not re-derived). The charter above is the original
> idea-stage record; this section is what holds against `main` on 2026-06-22.

## A. The three absence claims — verdicts

1. **No OS power-event listeners — CONFIRMED.** [VERIFIED] Grep across
   `modules/shell/src-tauri/**` (Rust) and all `**/*.java` for
   `WM_POWERBROADCAST` / `PBT_APMSUSPEND` / `PBT_APMRESUME*` /
   `PowerSettingNotification` / `SystemEvents.PowerModeChanged` /
   `GUID_ACDC_POWER_SOURCE` returns **zero** handling hits. Every "resume" hit
   is resume-*indexing* or resume-*agent-session* (e.g.
   `AgentLoopService.resumeLastSession`), unrelated to OS power state. The
   charter's "8 grep hits for resume are all resume-indexing" claim holds. The
   Tauri shell uses `tauri::{Emitter, Manager, WindowEvent}`
   (`modules/shell/src-tauri/src/lib.rs:16`) but `WindowEvent` does **not**
   include power-broadcast events — there is no subclassed window proc and no
   power plugin.

2. **Wall-clock timeouts — CONFIRMED, but the surface is smaller and more
   uneven than "throughout" implies.** ~769 `currentTimeMillis|Instant.now|
   nanoTime|Clock.` hits across 200 files, but the vast majority are
   benign (log timestamps, record `lastUpdated` fields, telemetry durations,
   cache TTLs that legitimately want real time). The **decision-bearing** sites
   — where a wall-clock jump flips a control decision — are enumerated in §B.
   Note there is already monotonic prior art to copy:
   `AgentStepRunner` parks on a `System.nanoTime()` deadline [SURVEY] — the
   codebase is not uniformly wall-clock.

3. **No re-registration / reconnection on wake — CONFIRMED as a *wake-trigger*
   gap, but the *actuators* already exist (626/627).** See §C: file-watcher
   re-registration, gRPC reconnect, and llama-server restart all have working
   actuators reached today by health-poll streaks. What is missing is a wake
   *signal* that invokes them *eagerly* instead of waiting 30–90 s for a
   failure streak to accumulate. This reframes claim 3 from "build reconnection"
   to "trigger existing reconnection earlier."

## B. Decision-bearing wall-clock sites (the spurious-timeout surface)

Author-verified, highest severity first:

- **`MmfWorkerSignalBus.shouldDie()` — WORKER SELF-TERMINATION.**
  [VERIFIED `modules/indexer-worker/.../coordination/MmfWorkerSignalBus.java:143-166`]
  `now = System.currentTimeMillis(); staleness = now - heartbeat; if (staleness
  > HEARTBEAT_STALE_MS) return true;` → the worker shuts itself down. Head
  writes the heartbeat; both processes freeze together on suspend. On thaw,
  `now` has jumped by the full suspend duration while `heartbeat` still holds
  its pre-suspend value, so `staleness ≫ HEARTBEAT_STALE_MS`. **If the Worker's
  `shouldDie()` poll thread runs before Head's heartbeat-writer thread on wake,
  the Worker self-terminates** — a thaw misread as "Head died." This is the
  single scariest site: a self-inflicted restart on every resume, exactly the
  "wake misclassified as crash" boundary the charter (§4) warns about. The
  monotonic fix here is non-trivial because the value is *shared cross-process
  via MMF* — `currentTimeMillis` is the only clock both processes agree on; two
  processes' `nanoTime` origins are unrelated. So this site needs a
  *suspend-aware* fix (detect the gap and reset the heartbeat baseline / grace
  the first post-wake poll), **not** a naive `nanoTime` swap. Good worked
  example of why "migrate every timeout to `nanoTime`" (charter §2) is too blunt.

- **`MmfWorkerSignalBus.isUserActive()`** [VERIFIED `:181-182`] —
  `staleness < 2000L` on a wall-clock activity stamp. On wake it reads "user
  idle" and *resumes* indexing (breath-holding releases). Low severity (wrong
  direction is the safe one — it indexes when it could have waited), but it is a
  wall-clock decision.

- **`EngineCircuitBreaker.updateStateIfNeeded()`**
  [VERIFIED `modules/ai-backend/.../EngineCircuitBreaker.java:205-214`] —
  `Instant.now().isAfter(openedAt.plus(recoveryTimeout))` → OPEN→HALF_OPEN.
  Wall-clock. **Low severity:** a suspend only makes recovery happen *earlier*
  (an extra probe request), which is the benign direction; it never holds the
  breaker open too long. Listed for completeness, not as a priority.

Survey-reported, not yet author-verified (treat as hypotheses to confirm before
any work — `audit-without-test`):

- `MainSignalBus.awaitPort()` deadline [SURVEY ~`:228-231`] — port-discovery
  loop; only live during startup, so suspend-mid-startup is a narrow window.
- `KnowledgeServerBootstrap` PID-validation + health-retry deadlines
  [SURVEY ~`:194`, `:290-312`] — startup-window only.
- `RuntimeActivationService` llama self-test deadline [SURVEY ~`:711-712`] —
  runs during `ai_activate`; suspend-mid-activation is narrow.
- `BackfillScheduler` SPLADE retry backoff [SURVEY ~`:167-280`] — on wake the
  backoff deadline is already past, so it retries immediately. Benign direction.
- `ipc-common/.../GrpcCircuitBreaker` cooldown [SURVEY] — takes an injectable
  `LongSupplier` but the production wiring defaults to `System::currentTimeMillis`.

**Severity reading:** exactly one site (`shouldDie()`) is high-severity and it
is *not* fixable by a clock swap. The startup-window deadlines are real but
low-probability (suspend must land inside a few-second boot). The backoff/breaker
sites mostly err in the benign direction (act *sooner*). This argues the
charter's claim-2 ("audit + migrate timeout logic to `nanoTime`") is the
*lower*-value half, not the cheap independent win it's framed as — and the one
site that matters most can't be migrated naively.

## C. Watcher / IPC surfaces and their existing actuators (§3 reframed)

| Surface | Owner / actuator [SURVEY file:line] | Today's recovery trigger | Wake-handler reuse |
|---|---|---|---|
| File watcher | `WorkerMethvinWatcher.registerRoot/unregisterRoot`; `RootWatcherRegistry.watch` | demand-driven: periodic `SyncDirectory` reconcile, OVERFLOW event, burst heuristic | **626 already provides the backstop** — reconciler is the single authority; `core.reconcile-root` is the scoped re-walk actuator a wake should kick |
| Head↔Worker gRPC | `RemoteKnowledgeClient.reconnect()` (called before each RPC); `KnowledgeServerHealthMonitor` 10 s poll; `WorkerSpawner.restart()`; `SupervisionPolicy` (hang ≥3 unhealthy) | health-poll failure streak → `SupervisionDecision` → restart (~30 s to notice) | invoke `reconnect()` / restart eagerly on wake instead of waiting for the streak |
| Head→llama-server HTTP | per-request `HttpClient` (no persistent socket); `schedulePeriodicHealthCheck` 30 s; `BrainSupervisionPolicy` (≥3 fail → restart/offline) | periodic health failure streak (~90 s) → restart or OFFLINE | eager `/health` probe + restart-if-stale on wake |

**Key correction to the charter's failure-mode #1.** The charter says a dead
watcher means "the index silently drifts from disk … the index lies." Post-626
(merged, `main 11b62dac6`) the **reconciler is the convergence authority and the
watcher is demoted to a pure fast-path.** A watcher killed by suspend therefore
causes *slower convergence*, not silent permanent drift — the per-root
`lastVerifiedAt` heartbeat + periodic reconcile backstop it. So a wake→reconcile
kick is a **latency optimization on an already-correct backstop**, not a
correctness fix. Severity of failure-mode #1 should be downgraded accordingly.
(The charter's NOTE already flags it was written from a git-history gap scan
before re-checking 626's merged outcome.)

## D. Critical analysis — the pivotal unknown and a cleaner design fork

### D.1 The load-bearing empirical question: does `System.nanoTime()` pause during Windows suspend?

The entire claim-2 (monotonic migration) **and** the gap-detector's clock choice
hinge on this, and it is **not settled** by the literature. On Windows
`nanoTime` is `QueryPerformanceCounter`; whether QPC advances across S3 sleep /
Modern-Standby (S0ix) is hardware- and firmware-dependent (TSC/HPET power state),
and community reports go both ways. This was not resolvable by web search and is
**the single highest-value experiment before any design**: on the actual target
hardware, log `currentTimeMillis` and `nanoTime` before/after a real
suspend-resume and measure both deltas.

The two halves of the charter want **opposite** clock behavior, which is the
crux:

- The **detector** needs a clock that *advances* during suspend (so the gap is
  visible). Wall-clock always does → the **detector must be wall-clock-based**,
  regardless of how `nanoTime` behaves. This part is robust.
- The **timeout fix** wants a clock that *pauses* during suspend (so deadlines
  don't fire). That is *only* `nanoTime`'s behavior **if** QPC pauses on this
  hardware. **If `nanoTime` keeps advancing during Windows suspend, migrating
  timeouts to `nanoTime` fixes nothing** — the spurious firing persists. So
  claim-2's value is *contingent* on the experiment, not independent of it as
  the charter asserts ("standalone correctness win even before any wake-handler").

### D.2 Alternative to wholesale `nanoTime` migration: detector + post-wake grace broadcast

Migrating dozens of `currentTimeMillis` sites is a large, ongoing-maintenance
surface (every future timeout is a regression risk) and — per §B — buys little,
since most decision-bearing sites are either benign-direction or startup-window,
and the one that matters (`shouldDie()`) *can't* be migrated (cross-process MMF
clock). A cheaper, better-targeted shape to weigh:

- One wall-clock **gap-detector** (a watchdog comparing scheduled vs observed
  wall-clock delta) emits a single "we were suspended for Δ" event.
- On that event, broadcast a **post-wake grace window**: timers/leases/health
  monitors that opt in reset their baselines / skip one evaluation. The
  `shouldDie()` heartbeat baseline reset and a one-poll health-check grace are
  the high-value consumers.

This localizes the fix to the few sites that matter and the one detector, rather
than spreading clock-discipline across the whole codebase. It also subsumes the
cross-process `shouldDie()` case that a `nanoTime` swap cannot. Worth raising as
the primary design fork against charter §2/§3.

### D.3 Detection-mechanism fork (charter §1): favor the Java gap-detector as primary; defer the OS hook

- **Java monotonic-gap detector** — in-process, cross-platform, **needs no new
  IPC and no Tauri plugin**, and works in every launch mode (Tauri shell, dev
  stack, headless eval, CI). This is the always-available signal and should be
  the primary.
- **OS hook (Tauri/Rust `WM_POWERBROADCAST`)** — earlier and more precise, but:
  (a) Tauri v2 does not expose power events; it needs a custom window-subclass
  or a third-party plugin (`tauri-plugin-app-events`); (b) the shell→Head channel
  today is process-spawn + manifest-file watch (`lib.rs` spawns `HeadlessApp`,
  watches a manifest, reads a spawn token) — there is **no existing event bus to
  Head**, so forwarding a resume event means adding a loopback endpoint Head
  must expose; (c) it is absent entirely when the backend runs outside the shell.
  Recommendation: **defer the OS hook to a precision follow-on**; ship the
  gap-detector first. The charter's "probably both" is right long-term but the
  ordering matters — both-at-once over-scopes the first cut.

### D.4 Boundary with 627 (charter §4) — the actuators exist, the *classification* is the work

627 (implemented) gives `SupervisionPolicy` / `WorkerSpawner.restart()` /
`BrainSupervisionPolicy` — the wake-handler does not need new recovery
machinery, it needs to (a) fire those actuators eagerly and (b) ensure a
post-wake eager restart is **not counted against restart caps / stability
windows** as a crash. The `shouldDie()` self-termination in §B is the concrete
way a thaw is currently *miscounted* as a Head-death. So the 627 boundary is
real but narrow: it is a *classification/attribution* change at a couple of
sites, not a parallel recovery subsystem.

## E. Open questions / next experiments (for the design pass, not done here)

1. **[Blocking] Empirical `nanoTime`-across-suspend test on target Windows
   hardware** (§D.1). Determines whether claim-2 has any value and which clock
   the detector uses. Everything else is contingent on this.
2. Confirm the [SURVEY] line refs in §B against source before scoping any of
   them (`audit-without-test`).
3. Reproduce the `shouldDie()` self-termination: does the Worker actually exit
   on a real resume, or does Head's heartbeat-writer reliably win the wake race?
   A test that injects a clock jump (or a live suspend smoke) is the truth here,
   not the static read.
4. Decide the design fork: **(a)** charter's wholesale `nanoTime` migration vs
   **(b)** §D.2's detector + post-wake grace broadcast. Lean (b) on the §B
   evidence, but confirm with the user.
5. Scope ordering: gap-detector-only first cut (§D.3) vs detector+OS-hook
   together.

## F. Summary of where the charter holds vs needs revision

- **Holds:** no power-event handling exists (§A.1); a real wall-clock
  spurious-timeout surface exists (§A.2/§B); no eager wake re-validation (§A.3);
  the wake-vs-crash boundary is a genuine hazard, concretely via `shouldDie()`.
- **Needs revision:** failure-mode #1 severity is overstated post-626 (reconciler
  backstops a dead watcher — latency, not silent drift, §C); claim-2's
  "standalone, independent correctness win" is **contingent** on the unsettled
  `nanoTime`-suspend behavior and is the *lower*-value half (§D.1/§B); the one
  high-severity site can't be fixed by a clock swap (§B `shouldDie()`); the
  detector and the timeout-fix want *opposite* clock behavior, which points to a
  detector + grace-broadcast design rather than wholesale migration (§D.2); and
  the "probably both detection mechanisms" should be sequenced, gap-detector
  first (§D.3).

---

# Design (long-term) — 2026-06-22

> Design theorization only — **no implementation.** "Investigate what exists
> before proposing structure" was done first (§A–§C above + a read of 627's
> implemented seams and `governance/supervision-contract.v1.json`). The
> conclusion is to **extend and complete the 627 supervision seam, not build a
> parallel power-management subsystem.** Scope is matched to the one verified
> correctness bug + its attribution edge + its declared-contract clause — not to
> the broad topic of "OS power handling." General/architectural level, not
> implementation.

## The reframe (what this problem actually is)

This is **not** "JustSearch needs a power-management subsystem." It is a
**confound on the detection layer of the 627 supervision contract.**

Every time-based detection in `governance/supervision-contract.v1.json` carries
an **unstated precondition**: *the wall-clock interval I measured ≈ the interval
during which this process was actually alive and able to make progress.* OS
suspend violates that precondition globally and simultaneously for every process
on the machine. The detections then fire on a lie:

- **Zombie** (Worker `MmfWorkerSignalBus.shouldDie`, heartbeat staleness > 5 s):
  on resume the heartbeat written by Head pre-suspend looks ancient, so the
  Worker can **self-terminate before Head's 1 s heartbeat scheduler writes
  again** (`WorkerSpawner.writeHeartbeat` is `scheduleAtFixedRate`, so its first
  post-wake fire races the Worker's `shouldDie` poll). This is the **one real
  correctness bug** — the supervision contract's own "zombie" row mis-fires on a
  benign event, exactly the "thaw misread as crash" hazard.
- **Hang** (worker/brain `/health` failure streak): the first post-wake probe
  may fail on a half-open socket → counts toward the restart streak.
- **Partial-boot / brain health-timeout** (`awaitPort`, `healthCheckTimeoutMs`):
  a suspend inside the boot/activation window blows the deadline.

So suspend is a **measurement-invalidating event**, not a fault. Left unhandled
it turns the *closed loop 627 deliberately built* into a loop that actuates on
invalid input. The fix belongs **inside** that seam.

## What already exists and is reused (no new framework)

- **`Capability` / `CapabilityHealth`** (`PENDING/READY/DEGRADED/RECOVERING/
  OFFLINE`) — the shared lifecycle vocabulary. The design adds **no new state
  enum**; a resume is an *input* to existing detections, not a new capability
  state.
- **`SupervisionDecision.decide(Input, SupervisionPolicy)`** — the Worker's
  registered pure logic-seam (`governance/logic-seams.v1.json`). It already
  carries a *stability-reset guard* (`lastStartKnown` + `msSinceLastStart`); the
  resume guard is the **same shape, same place** — one more parameter, no new
  authority.
- **`MmfWorkerSignalBus.shouldDie()`** already has a **`STARTUP_GRACE_MS`
  window** — a "don't evaluate staleness yet" concept to reuse for the post-wake
  grace, rather than inventing one.
- **`WorkerSpawner.restart()` / signal-bus graceful-stop**, **`RemoteKnowledge
  Client.reconnect()`**, **llama `/health` probe**, **`core.reconcile-root`
  (626)** — the re-validation actuators already exist; a wake only needs to fire
  them *earlier*.
- **`supervision-contract.v1.json` + `SupervisionContractTest`** — the
  declared-data-asserted-by-a-test shape suspend handling should live in, so it
  can't silently rot.
- **No `Clock`/`TimeSource` seam exists** (verified) — so the design must *not*
  introduce a global clock abstraction; the problem doesn't need one.

## The design (three parts, scope-matched)

### 1. One new seam: a per-process **liveness-continuity monitor** (the resume detector)

A small watchdog that periodically samples **wall-clock** time; when the observed
inter-sample gap exceeds the scheduled interval beyond a tolerance, it concludes
the process was frozen for ~Δ and emits a single discrete observation
`Resumed(gapMillis, wallClockAtWake)`. Properties:

- **Wall-clock by necessity** — only a clock that *advances* during the freeze
  can see the gap. (This is why the design does **not** depend on the unsettled
  `nanoTime`-during-suspend question from §D.1: detection uses wall-clock, which
  always advances; the fix resets wall-clock baselines.)
- **One per process that hosts suspend-sensitive detections** — Head and Worker.
  Each process freezes and thaws independently of the others' threads, and each
  has its *own* local consumers, so the monitor is **decentralized** (no new
  IPC). The Worker self-defends its own suicide-pact; Head self-triggers its own
  re-validation.
- **Cause-agnostic** — it fires identically for OS suspend, a multi-second
  stop-the-world GC, a VM pause, or a debugger stop. It does not need to know
  *why* the gap happened, because the correct response (reset staleness
  baselines, re-validate connections) is the same for all of them. This is the
  *only* genuinely new structural element.

This is **not** the OS power hook (deferred — see §detection fork) and **not** a
clock abstraction.

### 2. Consumers conform to existing seams (a resume is an input, not a subsystem)

- **Correctness fix — the suicide-pact (the one that matters):**
  `shouldDie()` consumes the Worker monitor's `Resumed` and **resets / graces its
  staleness evaluation** for a bounded window after wake — reusing the existing
  `STARTUP_GRACE_MS` grace concept. A heartbeat read inside the post-wake grace
  is not counted as stale, giving Head's scheduler time to write a fresh
  heartbeat. *This is the single true correctness change.*
- **Attribution fix — don't bill a thaw to the restart budget:** extend
  `SupervisionDecision.Input` with a `recentlyResumed` flag that, like the
  existing `lastStartKnown` stability guard, prevents a post-wake unhealthy blip
  from spending the shared restart cap. The decision stays pure and total; the
  resume fact is just another parameter the fault-injection tests can drive.
- **Latency optimization — eager re-validation (lowest priority):** Head's
  `Resumed` fires the *existing* actuators immediately — `reconnect()` + a gRPC
  health probe, a llama `/health` probe, and a `core.reconcile-root` kick —
  instead of waiting 10–90 s for a failure streak. Explicitly an optimization on
  an **already-correct backstop** (626 reconciler + 627 supervision), so it adds
  no new recovery machinery, only an earlier trigger edge.

### 3. Declare it in the contract (so it can't rot)

Add a **`livenessContinuity` clause** to each *time-based* fault-mode row in
`supervision-contract.v1.json` — naming the resume-detector seam, the grace /
budget-exemption it applies, and a guard test. `SupervisionContractTest` then
asserts every time-based detection has declared its suspend behavior, so a future
time-based detection is forced to state how it tolerates a resume. The guards are
**pure, millisecond unit tests** (inject `Resumed(Δ)` into `shouldDie` and
`SupervisionDecision`; assert no false suicide and no budget spend) — they live in
default `check`, satisfying ADR-0026 the same way 627's fault-injection tier does.

## Explicitly out of scope (matching scope to the problem)

- **Wholesale `nanoTime` migration of timeout sites (charter §2).** Dropped from
  the core design. It is contingent on the unsettled QPC-pause question (§D.1),
  helps only the in-process *startup-window* timeouts even then, and **cannot**
  fix the cross-process heartbeat (the two processes only agree on wall-clock via
  the MMF — `nanoTime` origins are unrelated). The detector + baseline-reset
  subsumes the one site that matters and needs none of it. A targeted `nanoTime`
  swap at a specific startup deadline can be a later micro-hardening if a real
  suspend-mid-boot misfire is observed — not now.
- **OS power hook now** (see fork below).
- **A generalized observation-validity framework** across all leases/breakers
  (see Reach — named, not built).

## Detection-mechanism fork (charter §1) — resolved

**Primary: the in-process Java liveness-continuity monitor.** Cross-platform, no
new IPC, works in every launch mode (Tauri shell, dev stack, headless eval, CI).
**Defer the OS hook** (Tauri `WM_POWERBROADCAST` → Head): it needs a new
shell→Head loopback edge (today the shell only spawns Head + watches a manifest
file — there is no event bus to Head), a window-subclass or third-party plugin,
and it is absent whenever the backend runs outside the shell. Its only advantage
is earlier/precise notice; the gap-detector's latency is ≤ one sample interval
and its inability to distinguish suspend from a same-duration GC is irrelevant
because the response is identical. The charter's "probably both" is right
long-term but must be **sequenced** — monitor first, OS hook as a precision
follow-on that simply provides an *additional* `Resumed` source into the same
seam.

## Cross-tempdoc boundaries

- **627:** the resume detector feeds the *existing* `SupervisionDecision` and
  reuses `WorkerSpawner.restart()`/graceful-stop; no parallel supervisor. The
  `recentlyResumed` guard is the attribution change. The `livenessContinuity`
  clause extends the existing contract.
- **626:** the wake handler kicks the existing `core.reconcile-root`; pure
  latency optimization since the reconciler already owns convergence correctness.
- **628:** a suspend mid-commit is still handled by 627's graceful-stop-then-
  force + 628's corruption-repair. The detector's value to 628 is *negative
  work* — it prevents the **spurious** kill that would otherwise drive the
  durability/repair path needlessly on every wake.
- **629:** orthogonal (encryption).

## Prior art (conform, don't coin)

- The detector is the standard **suspend / clock-jump watchdog** — the same idea
  as Linux `CLOCK_MONOTONIC` vs `CLOCK_BOOTTIME` (monotonic excludes suspend;
  boottime includes it) and the "the machine slept" heuristics in browser/JVM
  schedulers.
- "Don't actuate on a deadline that elapsed during a pause" is the JVM/GC
  **safepoint-pause-shouldn't-trip-timeouts** concern and gRPC/Netty's
  **idle-vs-keepalive** distinction.
- Invalidating a reading taken while the sensor was offline is **input-validity /
  fault-confound rejection** in closed-loop control — the precondition-dual of
  627's Observation-Actuation Closure (itself anchored to closed-loop/
  reconciliation control).

---

# Reach (step back) — the principle and its candidate scope

## The principle: the **Liveness-Continuity Precondition** (Observation Validity)

> A staleness- or deadline-based observation is only sound to actuate on if the
> observing process was **continuously live and scheduled throughout the
> measurement window**. When continuity is broken — OS suspend, long stop-the-
> world GC, VM pause/migration, debugger stop, severe thread starvation — the
> observation is **invalid** and must be reset/graced *before* it reaches an
> actuator.

This is the **precondition-dual of 627's Observation-Actuation Closure**: 627
guarantees every fault observation *reaches* an actuator (no open loops); this
guarantees an *invalid* observation does *not* actuate (no closed loop firing on
a lie). The two together state the full contract of a feedback loop: *observe →
**validate** → decide → actuate.* 627 built the loop; 630 supplies the missing
validate step for the time-based sensors. It is an **instance of an existing
seam**, so it conforms to it (input to `SupervisionDecision` + a contract clause)
rather than spawning a parallel mechanism — per the project's "conform, don't
coin" discipline.

## Candidate scope (where it already applies — possibly violated today)

Named here, **deliberately not built now** (the present problem requires the gate
at exactly one correctness site + one attribution guard + one contract clause):

- **Every time-based row in `supervision-contract.v1.json`:** zombie heartbeat
  (the proven bug — fix now), worker hang health-streak, brain hang health-
  streak, brain `healthCheckTimeoutMs`, partial-boot `awaitPort` timeout.
  *Status:* mostly benign-direction (act sooner) or backstopped; retrofit each
  only if shown to misfire.
- **Operation/ownership leases (606 / 271):** a lease "expired" because its
  holder was suspended is a *false* expiry → ownership churn. Likely a real
  latent instance of the same class.
- **Circuit breakers** (`EngineCircuitBreaker` wall-clock `Instant`,
  `ipc-common/GrpcCircuitBreaker` `currentTimeMillis` cooldown): recovery-timeout
  measured across a pause. Benign direction (recover *sooner*), low priority.
- **Any "haven't heard from X in T → X is dead" liveness inference:** SSE
  stream-liveness windows (`StreamLivenessWindows`), agent-run liveness,
  diagnostic-surface liveness (604). Each rests on the same precondition.

The **resume detector is the shared mechanism** that *could* feed all of these
later, but each consumer must opt in individually with its own grace/exemption
semantics — there is no correct one-size broadcast (a lease wants its deadline
*extended* by Δ; the suicide-pact wants its baseline *reset*; a breaker wants its
cooldown *extended*). So building a generalized validity-gating framework now
would be premature abstraction over consumers that want different things.
**Recommendation: record the principle (done), fix the one correctness site, and
let the candidate scope pull the detector in one verified misfire at a time.**

## A second, smaller invariant worth recording

The heartbeat suicide-pact exposes a narrower rule: **a value shared across
process boundaries via the MMF signal bus must be expressed in a clock both
processes agree on — which is wall-clock, never `nanoTime`** (two processes'
`nanoTime` origins are unrelated). This bounds *where* the monotonic-clock remedy
can ever apply (in-process only) and is why the cross-process heartbeat fix had
to be detector-based. Candidate scope: any future cross-process timing value on
the signal-bus layout. Worth a one-line note at the `MmfWorkerSignalLayoutV1`
seam; not a structural change.

---

# User-facing / frontend design — 2026-06-22

> Per the "think about user-facing consequences" pass. **No code changed.** The
> existing shell-v0 health/status UX was inspected live against a running dev
> stack (UI `:5173`, worker ready) with screenshots, and the governing FE state
> code was read (`verdict.ts`, `readinessNotice.ts`, `aiStateStore.ts`,
> `StatusDeck.ts`, `LivenessWatchdog.ts`). Conclusion: **the core design is
> mostly backend; its user-facing payoff is *subtractive* (removing a spurious
> alarm), and the benign wake window is *already* handled correctly by the FE.**

## What in the design is user-visible (direct / indirect)

The detector, the suicide-pact grace, the `recentlyResumed` guard, and the
contract clause are all **internal** — no surface renders them. But they have a
real **indirect** user-facing consequence, because the things they suppress or
trigger flow to the UI through the existing health pipeline:

- **`worker.recovering` → "Restarting…"** and **`worker.restart_exhausted` →
  "The knowledge server stopped responding and could not be recovered"** are
  real FE states (`readinessNotice.ts` CAUSE_ROWS; `verdict.ts`). Today a resume
  that trips the suicide-pact would mint these — so on opening the laptop a user
  could see a calm "Restarting…" blip or, after repeats, a **red terminal error
  for a fault that never happened.**
- The verdict is the **single authority** consumed by the Health surface badge,
  the bottom `StatusDeck`, an **aria-live announcer**, and a **"settled"
  completion toast** (`StatusDeck.ts:307,533`). So a spurious "Restarting…" is
  not just visual — it is *announced to screen-reader users and pops a toast.*
  The subtractive backend fix is therefore an **accessibility** win too.

## Live inspection findings (don't judge from the tempdoc alone)

- **Normal Health surface** (screenshotted): header verdict badge ("Reindex
  required" in this corpus), INDEXING/INDEX/Files/Size/Memory/Queue cards, a
  "What you can do right now" capability list, and a CONNECTION panel
  (Retrieval / API endpoint / Index state / Uptime). The verdict badge and the
  CONNECTION panel are exactly where a wake transient would show.
- **The benign wake window is already handled — and handled well.**
  `aiStateStore.computePhase()` returns `'disconnected'` **only when the FE has
  *never* connected** (`lastSuccessMs === null`). After any prior successful
  connect — always true before a laptop sleeps — a poll gap can produce **only
  `'stale'`**, never `'disconnected'`. `'stale'` →
  `verdict.computeStability` `channel-stale` → **`transitioning` / "Reconnecting…"**,
  which **retains last-known values** (no blank/zeroed UI) and **auto-clears** on
  the next 5 s staleness-timer poll (`STALE_THRESHOLD_MS = 15_000`,
  `setInterval(checkStaleness, 5000)`). The FE staleness check uses wall-clock
  (`Date.now()`) — the *correct* clock to notice the gap. **So the FE is itself a
  correct, already-shipped instance of the liveness-continuity principle on the
  browser-tab-suspend side:** wall-clock gap detection + last-known retention +
  calm transitioning wording, no alarm.

## The frontend design (scope-matched: mostly conform + subtract)

1. **Primary (delivered by the backend half — no FE change): remove the
   spurious alarm.** The suicide-pact grace + `recentlyResumed` attribution
   guard stop the backend from emitting `worker.recovering` /
   `worker.restart_exhausted` on a benign resume. The UX payoff — no false
   "Restarting…" / "could not be recovered" badge, announcement, or toast on
   wake — is *entirely* a consequence of the backend fix. **No new FE surface,
   no new reason code is required for the common case.** This is the right
   scope: the existing `channel-stale` → "Reconnecting…" state already covers the
   honest, benign wake window.

2. **Conform, don't fork.** Any wake transient that *should* be shown routes
   through the **one verdict authority** (`computeVerdict` → `transitioning`),
   never a parallel "system resumed" banner/surface. The `check-verdict-
   derivation` gate already forbids re-forking the readiness→verdict predicate;
   a wake indicator must respect that seam.

## Optional FE refinements (named, deliberately NOT built now)

Neither is required by the present problem; record as candidates, pull in only
if judged worth it:

- **Resume-specific wording.** Add a `'resumed'` `ProvisionalCause` →
  "Reconnecting after sleep…" so the wake window self-explains instead of the
  generic "Reconnecting…". One row on the closed `ProvisionalCause` union +
  `verdictHeadline` switch — conforms to the existing vocabulary. **Low value:**
  the generic "Reconnecting…" is already honest, and the FE only *knows* it was
  a sleep if it consults the Page Lifecycle `resume` event or a wall-clock-gap
  check (the FE-side analog of the backend detector). Worth it only if user
  testing shows "Reconnecting…" reads as a fault.
- **Proactive reconnect on tab resume.** Wire the Page Visibility / Page
  Lifecycle `resume` / `visibilitychange` event to immediately re-poll status
  and re-arm the SSE streams (`EnvelopeStream` / `LivenessWatchdog`) on wake,
  instead of waiting up to one staleness interval for the timer to notice.
  Snappier recovery; conforms to the existing watchdog primitives. Pure latency
  polish on an already-correct path. (Edge case it would also smooth: an
  in-flight agent-run fetch-body stream whose `LivenessWatchdog` trips `onStale`
  after a sleep — minor, since the user is not driving a run while asleep.)

## Reach (frontend) — the principle already spans the process boundary

The FE's `aiStateStore` staleness handling and the backend's proposed resume
detector are **the same Liveness-Continuity Precondition on two substrates**: a
browser tab frozen on suspend is the same confound as a JVM process frozen on
suspend, and the FE *already* solves it the same way the backend design
proposes — wall-clock gap detection, retain-last-known, present a calm
transitioning state rather than actuating a fault. This is confirmation the
principle is real and recurring (it independently appeared on the FE side), and
a reason to **conform the backend half to the FE's already-shipped shape**, not
invent a new vocabulary. It is *not* a reason to extract a shared cross-language
abstraction — the two substrates share a principle, not a reason to change
together (AHA). Record the cross-substrate symmetry; build nothing shared.

---

# External research pass (2026-06-22)

> Targeted pass on the **one genuinely volatile** fact the design touches:
> monotonic-clock behavior across modern Windows suspend. Everything else the
> design rests on (OTP supervision, K8s liveness/readiness, closed-loop control,
> the Capability/verdict seams) is settled craft already prior-art-validated by
> 627, so it was *not* re-researched. Findings sharpen §D.1, the detector
> mechanism, and the prior-art anchoring; **none change the core design** (which
> is wall-clock by construction and decoupled from this question).

## What the research settled

1. **The "monotonic clocks are platform-inconsistent across suspend" claim is now
   evidence-backed, not assumed.** The canonical distinction is **`CLOCK_MONOTONIC`
   (excludes suspend) vs `CLOCK_BOOTTIME` (includes suspend)**, and
   `CLOCK_MONOTONIC`'s suspend behavior **differs by distro** — Ubuntu does *not*
   advance it during suspend; Fedora *does* (a kernel patch to unify this was
   reverted). This validates the design's load-bearing choice: **the detector
   must use a clock *guaranteed* to include suspend (wall-clock / BOOTTIME),
   because a monotonic clock cannot be portably relied on to either pause *or*
   advance.**
2. **The textbook suspend-detection technique is a two-clock divergence.**
   `suspendedΔ ≈ (suspend-including clock Δ) − (suspend-excluding clock Δ)` over
   the same interval — on Linux `CLOCK_BOOTTIME − CLOCK_MONOTONIC`; the precise
   Windows analog is **`GetTickCount64` (documented to include sleep) −
   `QueryUnbiasedInterruptTime` ("unbiased" = excludes sleep)**. This is the
   established shape the design's detector conforms to.
3. **On Windows, `System.nanoTime()` (QueryPerformanceCounter) very likely
   *pauses* during true suspend.** Sources: `GetTickCount64` is documented to
   include sleep/hibernation whereas QPC is the sleep-*excluding* counter and
   "behaves badly on suspend/resume." **Two caveats keep this from being
   load-bearing:** (a) **Modern Standby (S0ix)** — CPU in shallow C-states with
   background activity, unlike S3 which powers down RAM-and-little-else — has **no
   authoritative doc** on whether QPC advances, and is now the common laptop
   sleep state; (b) QPC is documented as not reliably steady across power-state
   changes. So the *expected* Windows behavior is favorable ("nanoTime pauses → an
   in-process nano-deadline would NOT spuriously fire on wake"), but **the
   on-target measurement still gates only the *optional* nanoTime micro-hardening,
   not the core design.**
4. **Conforming prior art for the in-process duration model:** Go's `time` uses a
   monotonic reading for `Sub`/`Before`/`After` that "pauses when the machine
   sleeps and continues from where it left off — not counting sleep time." That is
   exactly the property a nano-based in-process deadline wants; name it, don't
   coin.

## How this updates the design (sharpen, not change)

- **§D.1 downgraded from "unresolved" to "characterized."** The core design was
  always wall-clock and is unaffected. The residual unknown is now precisely
  scoped: *only* whether QPC advances under **S0ix Modern Standby**, and it
  matters *only* for the optional nanoTime micro-hardening — which stays
  scoped-out (it still cannot fix the cross-process MMF heartbeat). Net: the
  "blocking experiment" is **not** blocking for the core; it gates only an
  optional path.
- **Detector mechanism — add a precise secondary signal.** Keep the
  **scheduled-vs-observed wall-clock-gap** watchdog as the **robust primary**
  (wall-clock always advances during suspend; the frozen watchdog thread observes
  a gap ≫ its schedule — works on *every* platform regardless of monotonic
  behavior; detection latency ≤ one interval). Add the **two-clock divergence
  `Δwall − ΔnanoTime`** as a **pure-Java, no-JNI secondary** that yields the
  suspend *duration* directly and is independent of scheduler wakeup timing — but
  it is **platform-contingent** (sensitivity depends on `nanoTime` pausing; on a
  Fedora-like or S0ix box where the monotonic clock also advances, the divergence
  collapses to ~0 and only the primary fires). So: primary = wall-clock-gap
  (always works); secondary = wall−nano divergence (precise where available).
  Both are pure Java.
- **Prior art to cite (conform, don't coin):** `CLOCK_MONOTONIC` vs
  `CLOCK_BOOTTIME`; the Windows pair `GetTickCount64` vs
  `QueryUnbiasedInterruptTime`; the two-clock-divergence suspend-detection
  technique; Go's sleep-pausing monotonic duration model; TigerBeetle's
  "three clocks" reasoning. These supersede the vaguer "suspend / clock-jump
  watchdog" framing in the Design's Prior-art note.

## Sources

- OpenJDK `nanoTime`/QPC + GetTickCount sleep semantics:
  [Stas — What is behind System.nanoTime()](http://stas-blogspot.blogspot.com/2012/02/what-is-behind-systemnanotime.html),
  [JVM Advent — Measuring Time](https://www.javaadvent.com/2019/12/measuring-time-from-java-to-kernel-and-back.html),
  [Acquiring high-resolution time stamps (Microsoft Learn)](https://learn.microsoft.com/en-us/windows/win32/sysinfo/acquiring-high-resolution-time-stamps).
- Modern Standby (S0ix) vs S3:
  [Modern Standby (Microsoft Learn)](https://learn.microsoft.com/en-us/windows-hardware/design/device-experiences/modern-standby),
  [System power states (Microsoft Learn)](https://learn.microsoft.com/en-us/windows/win32/power/system-power-states).
- `CLOCK_MONOTONIC` vs `CLOCK_BOOTTIME` + three-clock reasoning:
  [clock_gettime(2) man page](https://www.man7.org/linux/man-pages/man2/clock_gettime.2.html),
  [TigerBeetle — Three Clocks are Better than One](https://tigerbeetle.com/blog/2021-08-30-three-clocks-are-better-than-one/),
  [Baeldung — Timekeeping and Clocks in Linux](https://www.baeldung.com/linux/timekeeping-clocks).
- Go monotonic-on-sleep model:
  [VictoriaMetrics — Monotonic and Wall Clock Time in Go](https://victoriametrics.com/blog/go-time-monotonic-wall-clock/).

---

# Pre-implementation confidence pass (2026-06-22)

> Read-only investigation + one existing-test run, to convert the design's
> load-bearing assumptions into verified facts before implementation
> (`audit-without-test`). **No feature code written.** Two findings *improve* the
> fix shape (below). `file:line` evidence is author-verified.

## Verified facts

- **(A) The suicide bug is real and the worker likely loses the wake-race
  near-deterministically in the common Windows-sleep case — stronger than the
  earlier "race" framing.** The worker sentinel loop is
  `while(running){ Thread.sleep(1000); if (signalBus.shouldDie()) initiateShutdown(); … }`
  (`KnowledgeServer.java:1473-1481`) — so on thaw it evaluates `shouldDie()` within
  ~0–1 s. Head's heartbeat writer is
  `scheduler.scheduleAtFixedRate(this::writeHeartbeat, 1s, 1s)`
  (`WorkerSpawner.java:200-204`), whose next fire is tracked in `nanoTime`; if
  `nanoTime` *pauses* during suspend (the likely Windows case, per the research
  pass), the catch-up heartbeat is not written until the next +1 s tick — *after*
  the sentinel has already read the stale value and shut down. The only heartbeat
  writers are this scheduled task + `start()`/`restart()` (`WorkerSpawner.java:194,259`);
  **no path re-writes the heartbeat synchronously on wake.** The existing
  `ChaosSuiteTest.workerDiesWhenHeartbeatStale` (`:201-203`, @Timeout 180s)
  exercises exactly this mechanism (stop heartbeat → worker dies); the fast
  `WorkerSignalBusTest` is green (ran this pass; one *unrelated* pre-existing
  ArchUnit guardrail failure in the module, logged to observations, not 630).
- **(B) The clock-seam refactor is a confirmed prerequisite.**
  `MmfWorkerSignalBus` takes only `Path`; `startupTime` and `shouldDie()` call
  `System.currentTimeMillis()` inline (`:61,143,159`). `STARTUP_GRACE_MS=15s` is
  anchored to the constructor-time stamp, so it **does not** cover resume (a
  resumed worker has uptime ≫ 15 s) — the fix needs a *separately re-armable*
  grace, and the fast test cannot reach the stale path at all without injectable
  time (`WorkerSignalBusTest.shouldDieReturnsFalseWithinGracePeriod` only
  exercises within-grace). So: inject a time source into `MmfWorkerSignalBus`
  first, then the fix is fast-unit-testable.
- **(D) The Head-side attribution guard is *smaller and more optional* than
  framed.** `superviseTick` builds `SupervisionDecision.Input(alive,
  consecutiveUnhealthy, restartCount, known, sinceMs)` from local fields
  (`WorkerSpawner.java:696-697`) — so `recentlyResumed` is one field + one
  parameter + one branch. **But** the hang path needs `consecutiveUnhealthy ≥
  hangUnhealthyThreshold(3)` at a 10 s poll (≈30 s sustained) *and*
  `RemoteKnowledgeClient.reconnect()` runs before every RPC — so a post-wake
  stale-socket blip is unlikely to ever reach the restart threshold. The guard is
  **defensive, low-priority**, not the crux. The crux fix lives entirely in the
  *worker* process (`shouldDie`), a different process from the Head-side
  `SupervisionDecision`.
- **Contract extension is compatible.** `SupervisionContractTest` asserts the
  `policy` block equals live policy defaults + fault-mode vocabulary/guard
  resolution. A `livenessContinuity` *sub-clause* on existing time-based
  fault-mode rows touches none of those (it is a modifier, not a new fault mode),
  so it does not break the test — but *enforcing* it requires adding one new
  assertion.

## Two findings that improve the fix shape

1. **Prefer a clock-immune Head-liveness gate over a timed grace** (resolves the
   §C orphan-window risk). The suicide-pact's real job is to kill a worker whose
   Head actually died; today the *only* cross-platform Head-death signal is the
   heartbeat (the `WindowsJobObject` backstop is Windows-only), so a timed
   post-resume grace would open an orphan window on Linux/macOS. The worker
   currently has **no** parent/Head-PID signal (grep: no `headPid`/PPID in worker
   code). But `ProcessHandle` makes a clock-immune signal cheap: capture Head's
   PID at worker startup (via `ProcessHandle.current().parent()` *at startup*, or
   forward it Head→Worker through the existing `-D` sysprop channel), then gate
   the suicide on **"heartbeat stale AND the captured Head PID is dead."** The
   heartbeat becomes a fast-path; Head-liveness is the authority. This removes the
   orphan window entirely and is arguably *more* correct than the original
   reset/grace. **Edge to design through:** PID reuse and Windows reparenting/Job-
   Object interaction (capture at startup, re-read a *specific* PID's liveness,
   not `parent()` which lies after reparenting to init).
2. **The clock seam is needed only for the fast-path/testing, not for the
   PID-gate's correctness** — which further de-risks (B): even if injectable time
   is deferred, the PID-gate fix is correct without it; the seam is what makes the
   grace/staleness logic unit-testable.

## Residual unknowns (carry into implementation)

- Real-hardware suspend behavior (`nanoTime`/QPC under S0ix) — *decoupled* from
  the core design (detector is wall-clock; the PID-gate is clock-immune); only the
  optional in-process nanoTime micro-hardening depends on it.
- The PID-gate edge cases above (reuse, reparenting, Job-Object interplay) need a
  focused design decision.
- No in-harness real suspend/resume is possible; final correctness rests on a
  simulated-clock/PID unit test + the existing chaos mechanism, not a live wake.
- §E (SURVEY-row severities) deprioritized — does not gate the crux fix.

---

# Future directions / ideation (2026-06-22)

> Pure research/ideation pass (documentational only) building on the shipped 630
> substrate — the resume detector, the clock-immune liveness gate, and the
> Liveness-Continuity principle. Goal: what does *knowing the machine slept*
> unlock? Findings are grounded in an internal codebase survey + an external
> survey of how comparable tools behave. Nothing here is committed work — it is a
> menu of options (none urgent; the app has no users yet).

## The reframe: from "suspend-aware" to "energy/activity-aware"

The 630 detector gives the app its first notion that **the activity timeline ≠
wall-clock time** — it knows when it was frozen. The external survey shows this is
one member of a *family* of OS energy signals every laptop app faces. Electron's
`powerMonitor` (the canonical cross-OS reference) exposes `suspend`/`resume`,
`on-ac`/`on-battery`, `thermal-state-change`, `speed-limit-change` (CPU throttle
%), `lock`/`unlock`. And **Windows Search itself** ships "respect power settings
when indexing" — it throttles/stops indexing under battery-saver / best-efficiency
mode. So a local-first app doing *heavy continuous background work* (indexing, GPU
embeddings, SPLADE/NER, OCR/VDU, LLM) on people's laptops is *expected* to be
power-aware. The internal survey found JustSearch already has a sophisticated
"when should heavy work run?" arbitration — **breath-holding** (pause indexing on
user activity, `MmfWorkerSignalBus.isUserActive` + `LoopPacingPolicy`) and
**GPU-yield** (Worker unloads embedding/SPLADE/NER backfill when the Brain claims
the GPU, broadcast Main→Worker over the MMF) — but **zero** battery/AC/thermal
sensing. The resume detector is the seed of the missing axis.

## Ideas, by category (value · effort · fit)

### A. Power-aware background work — *extension; highest product value*
Sense power-source / energy-mode / thermal state and feed it as a new signal into
the **existing** arbitration: gate `LoopPacingPolicy.shouldRunBackfill(...)` and
the GPU-heavy backfill on "on battery / battery-saver / thermal-critical" the same
way it already gates on `isMainGpuActive()`. Defer the heavy GPU/OCR/embedding/VDU
backfill on battery, run full-speed on AC; optionally honor the OS energy-saver /
"effective power mode". **Reuses:** the GPU-yield broadcast template, an MMF byte
(34 reserved bytes free), the `shouldRunBackfill` gate. **Precedent:** Windows
Search's "respect power settings"; Windows dev guidance (defer opportunistic work
to AC; warn on battery-saver). **Effort:** medium (needs a power-sensing source —
see C — plus the gate). **Fit:** excellent (extends a proven pattern; matches OS
norms; "don't drain my battery / spin my fans re-indexing" is the #1 laptop
expectation).

### B. Legible "catching up after sleep" UX — *new UX; high fit, low cost*
On wake the app already kicks a reconcile (re-walk watched roots). Surface it as a
calm transient verdict — "Catching up on changes since your computer was asleep…"
→ "Up to date" — instead of an unexplained CPU/disk burst. **Reuses:** the one
verdict authority (`verdict.ts` `ProvisionalCause` — add `'catching-up'`, mapping
like `worker.recovering → "Restarting…"`), a `LifecycleReasonCode` +
`readinessNotice` CAUSE_ROWS row, and the **626 drift-legibility** surface
(`index.drift-unknown` / "Verify this folder" / `core.reconcile-root`) already
shipped. **Zero new UI components.** **Precedent:** Dropbox's "Syncing… → Your
files are up to date." **Effort:** low. **Fit:** high (turns invisible catch-up
into understood, trusted behavior — trust is the core local-first currency).

### C. OS power hook in the Tauri shell — *enabler; medium effort*
The shell detects the full power palette (`WM_POWERBROADCAST` suspend/resume +
`GetSystemPowerStatus` / power-setting notifications for AC/battery/% + thermal)
and forwards to Head. **Today there is no shell→Head push channel** (only
Head→shell manifest + shell→webview emit), so this needs one new edge: a loopback
`POST /api/.../system-event` endpoint, OR augment the runtime manifest the shell
already writes/Head already reads. Does **double duty**: an *instant, precise*
resume signal (vs the ≤10 s gap-detector latency) **and** the power-source sensing
that unlocks (A). The in-process gap-detector stays as the always-available
fallback (works headless / outside the shell). **Effort:** medium. **Fit:** good;
it is the natural source for both A and a snappier B.

### D. Resume as a first-class event + wake-stampede guard — *robustness / principle-reach*
- Emit "Resumed(Δ)" as a first-class `HealthEvent` lifecycle Occurrence (the tap →
  broadcast → SSE pipeline already exists) for telemetry + FE consumption, instead
  of a local log line.
- **Wake-stampede mitigation (non-obvious):** the external survey confirms wake is
  a classic *thundering-herd* trigger — on resume, many timers/connections fire at
  once. Best practice is **jitter + coalescing + gradual (half-open) circuit-breaker
  recovery**, not a synchronized stampede. Our eager re-validation (reconnect +
  reconcile) and the `EngineCircuitBreaker`/`GrpcCircuitBreaker` should recover
  *gradually* on wake. This extends the Liveness-Continuity principle: a wake is
  not only a *measurement-invalidation* (don't false-fire), it is a *synchronized
  trigger* (don't stampede).

### E. Polish / simplify — *cheap*
Make the resume tolerance factor + post-wake reconcile behavior configurable;
consider scoping `reindexPersistedRoots` by suspend duration for large corpora
(a multi-second wake doesn't need a full re-walk); run the now-feasible empirical
`nanoTime`/QPC-across-S0ix experiment to settle §D.1; the OS hook (C) collapses the
≤10 s detection latency to instant.

### F. Thermal / energy-mode-aware quality scaling — *speculative new UX*
Using `thermal-state-change` / `speed-limit-change`, gracefully scale heavy quality
features under thermal pressure (defer VDU/OCR/reranker, shrink embedding batch
sizes) — graceful degradation rather than fighting the OS's throttle. On-theme but
speculative; lowest priority.

## The principle this reveals (named, not built)

630's **Liveness-Continuity Precondition** (an observation is only valid to
actuate on if the observer was continuously live during the measurement window) is
the *time-axis* instance of a broader shape this research surfaced:

> **The Energy/Activity-Budget principle:** a local-first desktop app must treat
> compute as a *variable, OS-governed resource* and gate heavy background work on
> the machine's *current energy/activity budget* — never assume continuous,
> full-capability wall-clock time. A laptop violates that assumption on many axes:
> suspend (time frozen), battery / battery-saver (energy-limited), thermal /
> CPU-speed-limit (capability-throttled), user-foreground contention (politeness).

JustSearch already honors two members — **breath-holding** (user-activity axis) and
**GPU-yield** (resource-contention axis). 630's resume-handling adds the **time
axis**. The missing members are the **energy axis** (battery/AC/energy-saver) and
the **thermal axis** (thermal-state/speed-limit). **Candidate scope** (every heavy
subsystem that should consult the budget): the indexing loop, embedding / SPLADE /
NER backfill, OCR / VDU, the reranker, and LLM activation. Per project discipline
this is **recorded, not built** — name the shape + scope; let each member be added
when its value is concrete (A is the strongest candidate). The cohesive vehicle, if
pursued, is a single "ActivityBudget / EnergyState" signal on the existing MMF
broadcast that the existing `shouldRunBackfill`-style gates consult — *not* a new
arbitration framework (the arbitration already exists; it just lacks the energy +
thermal inputs).

## External references

- Electron `powerMonitor` (cross-OS power-event palette):
  [electronjs.org/docs/latest/api/power-monitor](https://www.electronjs.org/docs/latest/api/power-monitor).
- Windows Search "respect power settings when indexing" (battery-saver / power-mode
  throttling precedent):
  [elevenforum.com — Respect Power Settings when Indexing](https://www.elevenforum.com/t/turn-on-or-off-respect-power-settings-when-indexing-in-windows-11.2960/).
- Battery-aware background activity (Windows dev guidance — defer to AC, warn on
  battery-saver):
  [Windows Developer Blog — Battery awareness and background activity](https://blogs.windows.com/windowsdeveloper/2016/08/01/battery-awareness-and-background-activity/).
- Thundering-herd / wake-stampede mitigation (jitter, coalescing, gradual recovery):
  [Wikipedia — Thundering herd problem](https://en.wikipedia.org/wiki/Thundering_herd_problem),
  [PayPal Tech — Thundering Herd + Jitter](https://medium.com/paypal-tech/thundering-herd-jitter-63a57b38919d).
- Calm catch-up status UX model:
  [Dropbox — check sync status](https://help.dropbox.com/sync/check-sync-status).

---

# Long-term design — energy-aware background work (2026-06-22)

> Design-theory for the strongest, most-grounded ideation item (idea A,
> power-aware background work) — kept general, not implementation-level.
> Feasibility/phasing deliberately set aside; the question is only "what is the
> correct long-term shape, and which existing seams does it conform to?" The
> decisive finding: **this is not a new subsystem — it is the *composition of two
> seams the codebase already has*** (587 host-capability sensing on the SENSE
> side; the existing work-gating arbitration on the CONSUMER side). It coins no
> new seam.

## What already exists (extend, do not replace)

1. **The SENSE seam — tempdoc 587 host-capability sensing** (Phase-1 GPU shipped;
   axis-generalization is design-theory). Its shape is exactly what a power sense
   needs: a **Capability** (typed host fact policy consumes) answered by
   authority-ordered **Probes** (best-effort, never-throw, platform-aware,
   liveness-short-circuited) through one **Resolver** into an **Effective view
   `(value, source, confidence, diagnostics)` that survives to the policy
   decision**, consumed by a **policy** that is *handed the confidence* so it can
   be conservative under uncertainty. 587 also names the exact anti-pattern to
   avoid: the **`HardwareProfile` bootstrap-static cliff** that collapses
   confidence and freezes a value sensed once.

2. **The CONSUMER seam — the existing work-gating arbitration.** The app already
   throttles heavy background work on two axes: **breath-holding**
   (`MmfWorkerSignalBus.isUserActive` → `LoopPacingPolicy`) and **GPU-yield**
   (`InferenceWiring.wireGpuStatusBroadcast` → MMF `isMainGpuActive` →
   `LoopPacingPolicy.shouldRunBackfill` unloads embedding/SPLADE/NER backfill when
   the Brain claims the GPU). 630 added a third axis (time-continuity / resume).
   The Main→Worker **MMF broadcast** + `shouldRunBackfill` gate is the seam a
   power input joins.

## The design (general)

**Add ONE new capability axis — power/energy-state — to the 587 substrate, and
feed its Effective view into the existing work-gating arbitration.**

1. **Sense (conforms to 587):** `PowerState` is a new 587 capability — a
   confidence-carrying Effective view `(value ∈ {AC, ON_BATTERY, BATTERY_SAVER,
   UNKNOWN}, batteryPercent?, source, confidence)`. It is the substrate's **first
   genuinely runtime-varying capability** (power source flips at plug/unplug), so
   it is **resolved live (event-invalidated / short-TTL), explicitly NOT written
   into the static `HardwareProfile`** — which is precisely the 587 cliff. This is
   a small, useful *refinement to 587*: the substrate gains an event-driven
   capability variant alongside its mostly-static ones (GPU/RAM/disk).
2. **Source (two-tier, mirroring 630's own detection choice):** authority-ordered
   probes, exactly per 587 — an **OS-event probe** (the Tauri shell hook of idea
   C: `WM_POWERBROADCAST` / power-setting notifications → forwarded to Head;
   HIGH, push/precise) above an **in-process polled syscall** (`GetSystemPowerStatus`
   via FFM/JNA; MEDIUM, always-available incl. headless), degrading to **UNKNOWN**
   where neither applies (non-Windows / no battery probe). This is the same
   "always-available baseline + precise OS-hook" two-tier 630 chose for resume
   detection — and the OS-event probe yields the precise resume signal too, so
   idea C is *one source serving both*.
3. **Consume (conforms to the arbitration seam):** the Effective `PowerState` is
   broadcast Main→Worker over the MMF (the GPU-active template) and read by
   `LoopPacingPolicy.shouldRunBackfill(...)` as a new gate input. On
   `ON_BATTERY`/`BATTERY_SAVER` the heaviest GPU/OCR/VDU/embedding backfill is
   deferred (mirroring the GPU-yield decision); on `AC`, full speed. **Crucially,
   under `UNKNOWN` confidence the policy stays conservative — it does NOT throttle**
   (587 invariant: carry confidence to the decision; never degrade on a sense we
   are unsure of). So a host where power can't be read behaves exactly as today.
4. **UX projection (conforms to the verdict seam, 595):** the resume + catch-up
   surface (idea B) is the *presentation* projection of the same substrate — a
   calm `'catching-up'` `ProvisionalCause` ("Catching up on changes since your
   computer was asleep…" → "Up to date"), reusing 626's drift-legibility. A
   "Paused — on battery" indicator is the analogous projection of `PowerState`.
   These are 587's "policy/requirements projection" applied to *presentation*.
5. **Wake-stampede guard (conforms to backoff/breaker seams):** the eager
   re-validation and the circuit breakers recover *gradually* on wake (jitter /
   half-open), not as a synchronized stampede.

## Scope match (what this design does and does NOT include)

- **In:** one capability member (`PowerState`), its two-tier source, one
  arbitration input, one UX projection, and the wake-stampede refinement. Each
  plugs into an existing seam.
- **Out (recorded, not built):** a god-arbiter rewrite of the work-gating logic;
  migrating the *existing* scattered senses (`isUserActive`, `isMainGpuActive`,
  the resume detector) onto the 587 axis (that is 587's own axis-generalization,
  separately scoped); the **thermal** axis (idea F, speculative); the full 587
  substrate generalization. The present problem warrants the energy member, not
  the framework.

## Reach — principle and candidate scope

**This design is an instance of two principles the system already has, composed:**
587's *host-capability-sensing* principle (the sense) and the existing
*work-gating arbitration* (the consumer). Per the project's "conform, don't coin"
discipline it conforms to both rather than spawning a parallel "energy" structure.

**The recurring shape it reveals:** *every input to the "should heavy background
work run now?" decision is a host-capability/activity sense — and they should all
be sourced through the 587 confidence-carrying substrate and consumed through one
work-gating policy.* Name it the **Work-Budget projection**: the arbitration is
587's "policy consumer" for a *family* of capability senses (user-activity,
GPU-contention, time-continuity, energy, and — later — thermal). 630's
Liveness-Continuity precondition is the **time-axis** member of this family;
power/energy is the **energy-axis** member.

**Existing code already mildly violates it:** today's work-gating senses
(`isUserActive`, `isMainGpuActive` on the MMF; the 630 resume gap-detector) are
ad-hoc booleans living *outside* the 587 capability axis — confidence-free,
scattered, each re-derived at its consumer. They are exactly the "degenerate
single-source-naive instances" 587 catalogs for other host facts, one substrate
over. **Candidate scope** for the Work-Budget projection: unify those scattered
senses onto the 587 axis and add energy (now) + thermal (later) as new members,
all consumed by one `LoopPacingPolicy`-style gate. **Recorded, not built** — the
unification is 587's axis-generalization work; this tempdoc's problem warrants
only the energy member. Naming the shape captures the insight (the arbitration is
a capability-policy projection) without prematurely building the framework.

### Research refinement (2026-06-22): gate on the OS *energy-intent*, not a battery heuristic

A targeted pass on the one genuinely-volatile area the design touches — the OS
energy model — sharpens the capability's **value-shape** (a design-level choice,
not an implementation detail):

- **Windows changed the model.** Win11 24H2's **"Energy Saver" replaced "Battery
  Saver" and runs even on AC**, explicitly *reducing background activity*;
  **Adaptive Energy Saver** toggles it automatically by load, regardless of
  battery level. So the OS now exposes its **own authoritative "reduce background
  work" intent** — a hand-rolled "battery < 20% / on-battery" heuristic is the
  *obsolete* model.
- **The intent signal is uniform cross-OS.** macOS `ProcessInfo.isLowPowerModeEnabled`
  (+ `thermalState`); Linux `power-profiles-daemon`'s `power-saver` profile (D-Bus).
  Every major OS answers "should I reduce background work?" directly. So this is a
  real, platform-uniform capability axis, not a Windows battery hack.

**Refinement to the design (§ point 1/3):** `PowerState` becomes a normalized
**energy-intent** capability — value ≈ `{ FULL, REDUCED, UNKNOWN }` — resolved
587-style from **authority-ordered probes: the OS's own energy-saver / low-power /
power-saver-profile signal (HIGH) > a battery-vs-AC fallback heuristic (LOW) >
UNKNOWN**. The work-gate then reads one OS-authoritative bit ("OS wants reduced
background activity → defer heavy GPU/OCR/VDU backfill"), which is simpler,
**AC-capable**, adaptive, cross-OS-uniform, and exactly conforms to the OS's own
"respect power settings when indexing" guidance — rather than re-deriving battery
policy the OS already decides. Battery source / % stay as secondary diagnostics.
(`thermalState` / CPU-speed-limit is the *sibling* energy axis — idea F, still
deferred.) Everything else in the design is unchanged; this only narrows the
capability's value + probe precedence.

External references:
[Energy Saver (Microsoft Learn)](https://learn.microsoft.com/en-us/windows-hardware/design/component-guidelines/energy-saver),
[Windows 11 24H2 Energy Saver replaces Battery Saver](https://www.windowslatest.com/2024/08/02/microsoft-details-windows-11-24h2s-new-energy-saver-which-replaces-battery-saver/),
[Apple `isLowPowerModeEnabled`](https://developer.apple.com/documentation/foundation/nsprocessinfo/1617047-lowpowermodeenabled),
[Linux power-profiles-daemon over D-Bus](https://linuxconfig.org/how-to-manage-power-profiles-over-d-bus-with-power-profiles-daemon-on-linux).

## Frontend / user-facing design (2026-06-22, live-inspected)

> The energy design has real user-facing consequences (an automatic throttle is
> alarming if invisible — the user sees indexing stop and wonders if it broke).
> Designed against the *running* UI (FE dev server + Chrome), not from the tempdoc
> alone. Decisive finding from the inspection: **the app already has a calm,
> mature status vocabulary — and the energy/catch-up states are near-clones of
> states it already renders.** Two further findings shape the design.

### What the live UI showed (the patterns to conform to)

1. **A calm "transitioning" verdict vocabulary already exists and was captured
   live.** The verdict badge, the bottom `StatusDeck` pill, and the CONNECTION
   panel all rendered **"Reconnecting…"** simultaneously (a `channel-stale`
   provisional cause) — calm orange, holds last-known values, auto-clears. Siblings
   are "Restarting…", "Rebuilding…". This is the exact treatment a catch-up /
   on-battery state should wear.
2. **The readiness NOTICE banner** (top of the Chat/Search surface — e.g. "⚠
   Reindex required … Force Rebuild") is the home for a self-explaining,
   optionally-actionable system notice.
3. **Indexing has NO "paused" state today.** The Health surface Queue card renders
   only `Processing` / `Idle` / `Rebuilding…` / `Unknown`. The two *existing*
   automatic throttles — breath-holding (pause while you type) and GPU-yield
   (pause while the Brain uses the GPU) — are **completely invisible**. So
   "Paused — on battery" introduces the *first throttle-legibility* concept.
4. **Settings has no indexing-behavior controls at all** (only encryption,
   data-delete, trust grants, a read-only registry). The app's philosophy is
   **automatic, invisible, calm** smart behavior — *zero* user knobs for how/when
   indexing runs. So the correct design is **legibility-first, automatic-by-
   default — not a toggle.**

### The design: two states, both projections of the same signals, zero new components

**(a) "Catching up after sleep"** — the resume/catch-up state. A new
`ProvisionalCause` (`'catching-up'`; treated exactly like `channel-stale`) → a
calm `transitioning`/`busy` verdict. Headline ≈ "Catching up…", body ≈ "Re-checking
your files for changes made while your computer was asleep." Renders in the same
three places "Reconnecting…" does (badge / StatusDeck / CONNECTION), holds
last-known counts, and **auto-clears to "Up to date"** when the post-wake reconcile
finishes — the Dropbox *"Syncing… → Up to date"* model. Essentially free (a clone
of an existing state) and it turns the post-wake activity burst into understood
behaviour.

**(b) "Paused — on battery"** — power-throttle legibility. The Queue card sub-text
gains a `Paused — on battery` value (the new paused concept), plus a **calm
`info`-severity readiness notice** (`indexing.paused-on-battery` → "Indexing paused
to save battery — it'll resume when you plug in"), modelled on existing calm-info
rows like `gpu.saturated` ("The GPU is busy; results may be slower"). **No remedy
button, no toggle** in the first cut: "resume when you plug in" is self-explaining,
and a control has no home + contradicts the automatic-everything philosophy. A
"keep indexing on battery" toggle is **deferred** (no users yet; add only if an
override is actually wanted — exactly how breath-hold / GPU-yield shipped: automatic,
no knob).

**Conformance:** both states reuse the ONE verdict authority (`verdict.ts`
`ProvisionalCause` + `presentVerdict`), the `readinessNotice` CAUSE_ROWS vocabulary,
the `LifecycleReasonCode` enum, and the existing surfaces (StatusDeck, Health Queue
card, the notice banner). **Zero new UI components**; the `check-verdict-derivation`
gate keeps the predicate in the one seam.

### Reach (user-facing) — "throttle-reason legibility" is the presentation twin of the Work-Budget principle

Introducing "Paused — on battery" forces a new presentation concept the UI lacks:
**a calm, first-class "indexing is paused because X" reason.** Today only the
*outcome* (Processing / Idle) is shown, never the *reason* — so breath-holding and
GPU-yield are silent. This is the **presentation projection of the backend
Work-Budget principle**: just as every *gating input* is a capability sense
(user-activity, GPU, battery, time), every *pause* should have a calm reason
projection — "Paused — you're working", "Paused — the AI is using the GPU",
"Paused — on battery". On-battery is simply the first member that *demands* a
reason (a stopped indexer with no explanation reads as broken). **Candidate scope
(recorded, not built):** a single "indexing paused: <reason>" legibility that the
existing invisible throttles could also adopt — but the present problem warrants
only the battery + catch-up members, conforming to the verdict/notice seam, not a
new legibility framework.

---

# Pre-implementation confidence — energy-aware remaining work (2026-06-22)

> Read-only confidence pass over the *designed, not-built* energy-aware feature.
> All five load-bearing assumptions confirmed against `main`; no feature code.

- **(A) In-process energy-intent probe — CONFIRMED feasible.** `GetSystemPowerStatus`'s
  `SYSTEM_POWER_STATUS.SystemStatusFlag` byte is the documented "battery/energy
  saver engaged" bit (MS guidance: "avoid resource-intensive tasks when battery
  saver is on") alongside `ACLineStatus`/`BatteryFlag`/`%`. The codebase already
  calls Win32 kernel32 from Java via FFM — `WindowsJobObject`
  (`modules/app-util/.../WindowsJobObject.java`) is the direct precedent (Linker /
  SymbolLookup / StructLayout, "no external deps", null off-Windows). A
  `GetSystemPowerStatus` probe is *simpler* than it. **No Tauri shell dependency
  for the polled baseline**; `RegisterPowerSettingNotification(GUID_POWER_SAVING_STATUS)`
  is the optional push/precision upgrade.
- **(B) Capability shape — CONFIRMED.** `GpuCapabilities` (`Effective(value, source,
  Confidence{HIGH/MED/LOW/UNKNOWN})`) + the single-probe `Cuda(functional, source,
  confidence)` record is the exact standalone shape `PowerState` mirrors. 587's
  general axis is design-theory (unbuilt), so scope = **one standalone capability**,
  not "extend a generic axis".
- **(C) Consumer gate — CONFIRMED clean.** `LoopPacingPolicy.shouldRunBackfill` /
  `shouldInterruptBackfill` are **pure functions**; an `energyReduced` boolean
  composes trivially (AND into run, OR into interrupt), read at the call site where
  `mainGpuActive` is read — fully unit-testable, mirrors the GPU-yield gate.
- **(D) MMF broadcast — CONFIRMED clean.** Free reserved byte (offset 17);
  `main_gpu_active` (offset 24, Main-writes/Worker-reads) is the write/read template;
  `InferenceWiring.wireGpuStatusBroadcast` is the Head-side broadcast template.
- **(E) UX reason-code/verdict — CONFIRMED gated workflow.** `check-readiness-reason-codes.mjs`
  (LifecycleReasonCode ↔ readinessNotice parity) + `check-verdict-derivation.mjs`
  (one-verdict seam) enforce the add-a-state path for "catching-up" / "paused-energy".

**Residual risks (not blockers):** (1) the Win11 24H2 "Energy Saver" ↔
`SystemStatusFlag` fidelity should be verified on target hardware (a flagged MS Q&A
suggests edge cases) — a test item, not a design blocker; (2) the OS-event *push*
(`RegisterPowerSettingNotification` callback) adds FFM-callback complexity if
early/precise notification is wanted — the polled baseline avoids it; (3) it is a
multi-seam feature (sense + IPC + consumer + UX across ~3 modules), so coordination
surface is non-trivial even though each piece is low-risk; (4) live power-saving
effect is unmeasured (no users / no measurement infra — low stakes).

**Confidence for the remaining energy-aware implementation: 8/10.**

---

# Implementation outcome — energy-aware backend (2026-06-23)

> The energy-aware feature's **backend half is implemented + tested + merged**; the
> two new user-facing UI states and the stampede guard are dispositioned below.

## Shipped (backend energy-aware throttle)

The Worker now **defers GPU-heavy bulk backfill** (embeddings / SPLADE / NER /
BGE-M3) when the OS requests reduced background work — conforming to the existing
GPU-yield arbitration (no new arbitration framework):

- **Sense:** `WindowsPowerStatus` FFM probe (`GetSystemPowerStatus`, mirrors
  `WindowsJobObject`; null/UNKNOWN off-Windows or on failure) + `EnergyState`
  (`intent ∈ {FULL,REDUCED,UNKNOWN}` + `source`; the modern OS energy-saver
  `SystemStatusFlag` bit, AC-capable per the research refinement; **UNKNOWN ⇒
  never throttle**). Pure derivation unit-tested (`WindowsPowerStatusTest`).
- **Signal:** MMF byte 17 (`energy_reduced`); `MainSignalBus.writeEnergyReduced` /
  `MmfWorkerSignalBus.isEnergyReduced` (mirror of the GPU-active byte). Head polls
  every 15 s in `WorkerSpawner` → writes the byte + caches `EnergyState` for
  `/api/status`. `EnvRegistry.POWER_FORCE_ENERGY_STATE` dev override for testing.
- **Throttle:** `WorkerSignalBus.shouldYieldGpuBackfill()` = `isMainGpuActive() ||
  isEnergyReduced()` (default-composed; `WorkerSignalBusEnergyTest`). The
  GPU-heavy bulk-backfill gates read it instead of `isMainGpuActive()`. Foreground
  indexing + the GPU load/unload lifecycle deliberately unchanged (energy-saver
  defers *background* work, never user-requested work or model churn).

Validated: unit (`WindowsPowerStatusTest`, `WorkerSignalBusEnergyTest`, worker-services
backfill/loop suites unaffected by the rename), compile + spotless + PMD +
`checkNoDirectJustsearchSysProp`. The throttle is invisible by nature (like the
existing breath-hold / GPU-yield throttles) — no new UI — and "indexing eases on
energy saver" is the *expected* behaviour the OS itself signals. Real-hardware
Energy-Saver→throttle effect is a user-driven smoke (not in-harness reproducible).

## Wake-stampede guard (V4) — NOT warranted

The resume handler (`KnowledgeServerHealthMonitor.eagerlyRevalidateAfterResume`) is
already **two ordered, individually-guarded best-effort calls** (reconnect, then
reindex) — there is no synchronized stampede to guard. The thundering-herd concern
from the research is real in general but does not manifest in this code; building a
jitter guard here would solve a non-problem. Recorded as "not warranted".

## Remaining (follow-on slice): the two user-facing UI states

"Paused — saving energy" and "Catching up after sleep" (the §Frontend design) are a
**focused follow-on slice, not yet built**. Each needs backend status-plumbing —
the `EnergyState` (and the resume state) surfaced onto `/api/status` as a reason
code / provisional cause — threaded through the `StatusLifecycleHandler` dim →
composite framework, then the FE `readinessNotice` CAUSE_ROW / verdict
`ProvisionalCause` + the Queue-card "Paused" state, browser-validated. This was
deliberately **not rushed** at the tail of a long session: the status framework is
deep, "Paused" is semantically an *indexing* state (not retrieval degradation, so
placement needs care), and proper live browser validation is impeded with the
dev-stack MCP disconnected this session. The backend throttle stands alone
correctly until then (invisible like the existing throttles).

## Post-merge fix — energy ≠ GPU-conflict (CPU-embedding escape)

A critical-analysis pass found the first cut **silently no-opped energy saver when
embeddings run on CPU** — the common case on the GPU-less battery laptops where
energy saver matters most. Cause: the energy reason was folded into the
`shouldYieldGpuBackfill()` boolean and passed through `LoopPacingPolicy.shouldRunBackfill`,
which has a `!embeddingProvider.isUsingGpu()` escape. That escape is correct for the
**GPU-yield** reason (no VRAM conflict on CPU embeddings) but wrong for the **energy**
reason (CPU backfill still drains the battery).

Fixed by separating the two axes in the pacing policy: `shouldRunBackfill(mainGpuActive,
energyReduced, ep)` returns `false` on `energyReduced` **regardless of GPU/CPU**, and
applies the `isUsingGpu()` escape only to the GPU-yield reason. Callers
(`BackfillScheduler`, `EmbeddingBackfillOps`) pass the two signals separately; the
chunk-embedding tight-loop break (`BackfillScheduler`) was switched to
`shouldYieldGpuBackfill()` for consistency with the combined-loop break. A new
`LoopPacingPolicyTest` pins the truth table — the `energyReduced + CPU` row is the
guard that fails against the pre-fix code. Energy saver now pauses **all** bulk
backfill (CPU included) until exited / plugged in — which makes the still-unbuilt
"Paused — saving energy" UX more important (indexing genuinely stalls on CPU laptops).

## Implementation outcome — FE legibility shipped + browser-validated (2026-06-23)

The two user-facing states are now implemented and **validated in the real UI**:

- **"Paused — saving energy"** — Queue card sub-text when `power.energyReduced &&
  pendingJobs > 0`. Browser-confirmed: Queue shows "Paused — saving energy".
- **"Catching up after sleep"** — a new `catching-up` `ProvisionalCause` → calm
  "Catching up…" transitioning verdict (busy/info, the same calm treatment as
  "Reconnecting…"), derived from `status.catchingUp`. Browser-confirmed: the header
  badge, Queue sub-text, and Retrieval line all read "Catching up…".

Neither is a degradation (search keeps working), so both deliberately avoid the
reason-code / `CAUSE_ROWS` degradation path — they surface as small Head-lifecycle
status fields (`power: PowerStatusView`, `catchingUp: boolean`) read by the existing
verdict + Queue-card seams. Backend: `KnowledgeServerBootstrap` exposes null-safe
`energyState()` + `markResumed()`/`recentlyResumed()` (the health monitor stamps the
resume; the window auto-clears against the request clock); `StatusLifecycleHandler`
populates both via a late-bound bootstrap supplier wired through
`HeadAssembly.currentKnowledgeServer()`.

Validation: backend unit (bootstrap signals, `StatusRecordSchemaTest`), FE unit
(verdict derivation + calm-transition, full suite green), and browser screenshots of
both states via `__feedForTest` injection against the worktree FE. Remaining
on-hardware smoke (not in-harness): real Windows Energy Saver toggle and real
sleep/resume. **All tempdoc-630 work is now complete.**

## Follow-up — energy-pause main-surface legibility via StatusDeck (2026-06-23)

Critical analysis flagged that "Paused — saving energy" was legible only on the Health
Queue card, not on the main surface the user is on. The FE design wanted a calm
main-surface notice (modelled on `gpu.saturated`). Investigation proved that model is
**not viable** for a non-degradation: the main-surface degradation banner renders only
when `verdict.kind === 'degraded'` (`readinessNotice.ts`), locked to `retrieval ===
'degraded'` (`verdict.ts`). `gpu.saturated` only ever shows *riding alongside* a real
retrieval degradation; a calm code on a ready composite is suppressed. Routing "paused"
there would mean **falsely degrading search** or a **new component** (the design said
zero new components).

The conformant home is the **global `StatusDeck`** (the bottom bar on every surface,
where "Catching up…" already shows via the verdict pill). Its `core.queue` item now
renders **"queue: N · paused"** (calm `moon` glyph) when `status.power.energyReduced &&
pendingJobs > 0` — so a queue that sits still on the Search/Chat surface reads as paused
to save energy, not stalled. No degradation framing, no new component, no verdict change.
**Browser-validated** on the Search surface (non-Health): the bottom bar shows "queue: 5
· paused"; the Health Queue card and the "Catching up…" pill still render. FE unit:
`StatusDeck.test.ts` paused/active cases; full suite green.

**Catching-up clears on a ~30s window, not on reconcile-completion** — kept as-is: the
design's requirement is "auto-clears" (met); completion-precise clearing would need a
signal threaded out of the fire-and-forget post-resume reconcile, invasive for a
sub-second cosmetic difference. This closes the last legibility gap; tempdoc 630 work is
complete (pending the `main` merge, blocked on unrelated overlapping WIP on main).

---

# Post-implementation research — what we could build on this substrate (2026-06-23)

> Pure research pass (no code, documentational only). Three parallel research clusters
> (OS power/sleep/idle APIs · gold-standard background-work patterns · principles +
> green-software frontier) + a codebase-grounding pass + a Tauri follow-up. The app is
> **pre-production with no users**, so every direction below is viable and none is urgent —
> this is an opportunity map, not a plan. Value/effort/fit are rough.

## The substrate these ideas build on

The shipped work gives four reusable pieces: a **host energy-sense** (Windows
`GetSystemPowerStatus`), a **work-gating policy** (`LoopPacingPolicy.shouldRunBackfill` —
already consuming user-activity + GPU + energy senses), a **sleep/resume continuity
detector** (`ResumeDetector` + the Head health-monitor), and a **legibility layer**
(verdict "Catching up…", StatusDeck "queue: N · paused"). Plus two named principles: the
**Liveness-Continuity Precondition** and the **Work-Budget** (every pause is a policy
decision over a family of host senses).

## Five headline findings (the things that actually change the design)

1. **"Sleep = frozen machine" is wrong on Modern Standby (S0)** — the default on laptops
   shipped in the last ~6 years. The machine stays in S0 and wakes periodically; a desktop
   process is throttled by the Desktop Activity Moderator, the JVM heartbeat may keep
   ticking irregularly, and our **wall-clock-gap `ResumeDetector` can mis-fire or miss
   resumes** there. ([Modern Standby vs S3](https://learn.microsoft.com/en-us/windows-hardware/design/device-experiences/modern-standby-vs-s3))
2. **Authoritative OS power events beat inference + polling.** `PowerRegisterSuspendResumeNotification`
   → `PBT_APMRESUMEAUTOMATIC` is the *correct* "we resumed" signal (windowless — works for
   our headless Java process via FFM, and correct on Modern Standby where the clock gap is
   not). `RegisterPowerSettingNotification` on `GUID_POWER_SAVING_STATUS` + `GUID_ACDC_POWER_SOURCE`
   replaces the 15 s energy poll with a push. *Where to hook it:* the Java backend via FFM —
   Tauri v2 has **no** power-monitor API ([plugins-workspace#990](https://github.com/tauri-apps/plugins-workspace/issues/990)),
   so it stays next to the policy that consumes it. ([power events](https://learn.microsoft.com/en-us/windows/win32/api/powerbase/nf-powerbase-powerregistersuspendresumenotification),
   [power-setting GUIDs](https://learn.microsoft.com/en-us/windows/win32/power/power-setting-guids))
3. **The canonical background-work model is a *declarative constraint set, re-evaluated
   continuously, with auto stop/resume* — plus an *intensity ladder* (not on/off).** Android
   `WorkManager` (`requiresCharging`/`requiresDeviceIdle`/`requiresBatteryNotLow`/…), iOS
   `BGProcessingTask` (`requiresExternalPower` + opportunistic "run while charging+idle"),
   and Spotlight's shipped default — *idle + AC ⇒ full speed; busy or on-battery ⇒ back off*.
   This is exactly the generalization of our `LoopPacingPolicy`.
   ([WorkManager](https://developer.android.com/reference/androidx/work/Constraints),
   [Spotlight backoff](https://www.howtogeek.com/311362/what-are-mds-and-mdworker-and-why-are-they-running-on-my-mac/))
4. **Our "Liveness-Continuity Precondition" is a fresh name for Kleppmann's
   process-pause / lease-validity problem.** The textbook *stronger* fix is a **fencing /
   epoch token** (reject the stale actor at the resource). We don't need it: our staleness
   judgment only gates **self-termination** (with a Windows Job Object backstop), so
   self-validation (the Head-PID gate) is sufficient — the epoch token is the hardening to
   reach for only if a staleness judgment ever gates a destructive *shared-state* write.
   ([Kleppmann — distributed locking](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html))
5. **Carbon-aware time-shifting is real at cloud scale but a poor fit for us.** GSF /
   SCI (ISO/IEC 21031) / Carbon-Aware SDK + WattTime / Electricity Maps exist, but it needs
   a live grid-API call (conflicts with loopback-only), the marginal signal can *backfire*
   ([Electricity Maps](https://www.electricitymaps.com/technology/optimizing-electricity-consumption-with-a-marginal-signal-may-not-reduce-its-carbon-footprint)),
   and a local indexer's energy is tiny. File as "interesting, not for us." By contrast our
   *energy-saver-aware* work is documented best practice (energy proportionality;
   OS battery-awareness) — a strength, not novelty.

## Opportunity map

### A. Polish the shipped robustness
| # | Idea | Value | Effort | Notes |
|---|------|-------|--------|-------|
| A1 | **Authoritative resume event** (`PBT_APMRESUMEAUTOMATIC` via FFM) as the primary resume trigger; keep `ResumeDetector` as the cross-platform fallback | High | Med | Correct on Modern Standby, where the clock gap is not. Same FFM idiom as `WindowsJobObject`. |
| A2 | **Event-driven energy/AC** (`RegisterPowerSettingNotification`) replacing the 15 s poll | Med | Med | Lower latency + overhead, no missed-toggle window. |
| A3 | **Harden the clock-gap fallback with a monotonic-vs-wall divergence test** — real sleep = big wall gap + *small* `nanoTime` gap; an NTP/DST/manual clock jump = *both* gaps large | Med | Low | `ResumeDetector`'s own comment dismissed monotonic as "unreliable"; the research shows the **divergence** is precisely the robust discriminator. Low-harm today (a false trigger = one cheap reconcile) but a clean correctness upgrade. |
| A4 | **Modern-Standby awareness** (detect S0 vs S3 via `powercfg /a` / `CallNtPowerInformation` AoAc) and stop fighting the OS's DAM throttling | Med | Med | Mostly informs A1; lets the policy treat MS-sleep as real sleep. |

### B. Simplify / consolidate
| # | Idea | Value | Effort | Notes |
|---|------|-------|--------|-------|
| B1 | **Unify the scattered gates into ONE declarative constraint set** (the Work-Budget the tempdoc already named). Today user-activity / GPU-yield / energy are checked in several backfill ops; make one authority each stage declares against. | Med-High | Med | Simplifies *and* powers the legibility (you always know *which* constraint fired → the exact pause reason, for free). Validated by the WorkManager model. |

### C. Extend the throttle policy (biggest opportunity)
| # | Idea | Value | Effort | Notes |
|---|------|-------|--------|-------|
| C1 | **Constraint set + adaptive intensity ladder** (not binary): scale *concurrency/batch-size* by power-source / battery-level / idle / thermal. e.g. AC+idle+cool ⇒ full; AC+active ⇒ reduced; battery ⇒ light stages; battery-low OR saver OR hot ⇒ paused. A few **named profiles** ("Balanced" default), not a slider. | High | Med-High | The headline extension — a strict generalization of today's gate; mirrors Spotlight + Edge tiers. |
| C2 | **Cross-platform energy + thermal** — macOS `isLowPowerModeEnabled` + `thermalState` (clean 4-level; thermal is **macOS-first** — Windows has no app-facing thermal API), Linux `power-profiles-daemon` + `UPower` | Med | Med | `EnergyState` is already platform-abstracted (UNKNOWN off-Windows); this fills the others in when JustSearch ships there. |
| C3 | **Idle-aware "catch up while you're away"** (Windows `GetLastInputInfo`): do the heaviest catch-up when the user is away + on AC, like Spotlight | Med | Med | Best-effort/portability-gated (macOS needs Accessibility permission; Wayland has no portable idle signal). |
| C4 | **Thermal-aware throttling** — shed heavy stages as `thermalState` escalates (Apple's explicit guidance) | Low-Med | Med | macOS-clean; Windows/Linux degraded. Folds into C1's "thermalOK" input. |

### D. New UX (the trust layer — best fit; pre-users, so we can shape it now)
| # | Idea | Value | Effort | Notes |
|---|------|-------|--------|-------|
| D1 | **Calm status vocabulary + explicit "Up to date" terminal state** (Dropbox model). We have "Paused — saving energy" + "Catching up". Add a **bounded progress denominator** ("Indexing 1,240 of 3,500" so "behind" feels finite) and the **explicit "Up to date"** — the single most trust-building message. | High | Low-Med | Extends the StatusDeck/verdict we already built. |
| D2 | **One "Indexing paused: <reason>" that surfaces *all* the invisible throttles** — today breath-hold + GPU-yield are silent; only energy-pause is legible. Cover "you're typing" / "the AI is using the GPU" / "on battery" / "energy saver" / "machine is hot". | Med | Med | The presentation twin of the Work-Budget; B1's constraint set hands it the reason string for free. |
| D3 | **"Catch up now" / "index anyway on battery" override** — trust comes from being *able* to override the automatic policy (the research's escape-hatch principle) | Med | Low-Med | One-click; no settings page needed. |
| D4 | **"Welcome back" resume report** — turn catch-up into a positive moment: "Re-checked your files — N changed while you were away" | Low | Low | Delight; Dropbox-y. |
| D5 | **Energy-impact transparency** — a tiny "indexing used ~X% battery today" (macOS Activity Monitor "Energy Impact" precedent) | Low | Med | Nice-to-have, low priority. |

### E. Principle grounding + frontier (documentational)
- **E1 — Cite the lineage of the Liveness-Continuity Precondition** (Kleppmann process-pauses / unreliable clocks / leases; monotonic-vs-`CLOCK_BOOTTIME` suspend gap) and record the fencing/epoch token as the known stronger fix *for the shared-state case we deliberately don't have*. Honest framing: a fresh, useful **observer-side name** for an established idea.
- **E2 — Carbon-aware computing** recorded as a real but ill-fitting frontier (network-dependent, contested signal, low payoff for a local indexer) — explicitly *not* pursued.

## If we did just three things
**A1** (authoritative resume event — fixes a real Modern-Standby gap in shipped code),
**C1** (constraint set + intensity ladder — the high-value generalization), and **D1**
(progress denominator + "Up to date" — the cheap, high-trust UX completion). B1 underpins
both C1 and D2, so it is the natural enabler if we go past three.

### Sources
OS APIs: [Modern Standby](https://learn.microsoft.com/en-us/windows-hardware/design/device-experiences/modern-standby-vs-s3) ·
[suspend/resume notify](https://learn.microsoft.com/en-us/windows/win32/api/powerbase/nf-powerbase-powerregistersuspendresumenotification) ·
[power-setting GUIDs](https://learn.microsoft.com/en-us/windows/win32/power/power-setting-guids) ·
[macOS thermalState](https://developer.apple.com/documentation/foundation/nsprocessinfo/1417480-thermalstate) ·
[Linux power-profiles-daemon](https://linuxconfig.org/how-to-manage-power-profiles-over-d-bus-with-power-profiles-daemon-on-linux) ·
[GetLastInputInfo](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getlastinputinfo).
Patterns: [WorkManager Constraints](https://developer.android.com/reference/androidx/work/Constraints) ·
[iOS BackgroundTasks](https://www.andyibanez.com/posts/modern-background-tasks-ios13/) ·
[Spotlight backoff](https://www.howtogeek.com/311362/what-are-mds-and-mdworker-and-why-are-they-running-on-my-mac/) ·
[Chrome timer throttling](https://developer.chrome.com/blog/timer-throttling-in-chrome-88) ·
[Dropbox sync status](https://help.dropbox.com/sync/check-sync-status).
Principles: [Kleppmann — distributed locking](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html) ·
[Energy-proportional computing](https://research.google/pubs/the-case-for-energy-proportional-computing/) ·
[Green Software Foundation / SCI](https://greensoftware.foundation/standards/sci/) ·
[Electricity Maps — marginal-signal caveat](https://www.electricitymaps.com/technology/optimizing-electricity-consumption-with-a-marginal-signal-may-not-reduce-its-carbon-footprint).

---

# Settled long-term design — the Activity-Budget seam (2026-06-23)

> Design-theory pass over the post-implementation opportunity map (the directions above).
> Goal: settle the *correct long-term shape*, conform it to existing seams, and match its
> scope to the problem the tempdoc actually has. General, not implementation-level.
> **Title note:** the tempdoc's centre of gravity has shifted from "sleep/resume robustness"
> (the spine, shipped) to **host-conditioned background work — the Activity-Budget seam**;
> the filename is kept (cross-references), but read the tempdoc as that.

## The decisive framing: this is not a new subsystem — it is one three-layer seam, already present

The opportunity-map directions (authoritative power events, cross-platform senses, the
intensity ladder, unifying the scattered gates, the "all-throttles-legible" UX) look like
five separate features. They are not. They are **refinements to the three layers of one
pipeline the codebase already has** — two as established seams, one in embryo:

| Layer | What it is | Existing home | Status |
|---|---|---|---|
| **1 · Sense** | each host condition as a confidence-carrying capability | **587 host-capability sensing** (`Effective(value, source, confidence)` resolved by authority-ordered probes) | established; energy member shipped (630) |
| **2 · Policy** | map the sense-set → a gating decision **+ its firing reason** | the work-gating arbitration (`LoopPacingPolicy` + inline checks in the backfill ops) | **embryonic** — binary, scattered, reason only implicit |
| **3 · Projection** | render the firing reason to the user | the verdict / StatusDeck / readinessNotice presentation seam | established; energy + catching-up shipped (630) |

Once seen this way, every opportunity-map item lands in a layer **without new framework**:

- **Sense (1):** authoritative OS power/suspend events (A1/A2) and the Modern-Standby-correct
  resume signal are **a higher-authority probe inserted above the existing poll / clock-gap
  probe** — pure 587 authority-ordering, not new structure. Cross-platform energy/thermal
  (C2/C4) = more probes behind the same `Effective` shape. Resume/time-continuity is itself a
  sense. The layer's invariants already hold: carry confidence; **never throttle under
  UNKNOWN**.
- **Policy (2):** the binary `shouldRunBackfill` becomes one pure authority
  `decide(senses) → (intensity ∈ {FULL, REDUCED, LIGHT, PAUSED}, reason)` — a declarative
  constraint set re-evaluated continuously with auto stop/resume (the WorkManager model the
  research validated), where **the firing condition is a first-class output**. This is the
  intensity ladder (C1) and the gate-unification (B1) — they are *the same move*.
- **Projection (3):** "surface every throttle reason" (D2) and "X-of-Y / Up to date" (D1) are
  projections of layer 2's output. D2 is **gated on** layer 2 emitting the reason as a
  first-class output — you cannot render a reason the policy never names.

## The one structural step — and why it is deferred

The only genuinely new structure the full vision needs is **consolidating layer 2** (the
embryonic Policy): from scattered binary booleans into one pure authority that emits a graded
decision + reason. The Sense layer (587) and the Projection layer (presentation seam) already
have homes, so future senses and UX conform without new structure — **only the middle layer
is under-built.**

It is consolidated **when its trigger fires**, not before. The trigger is **either** the
*intensity ladder* (a graded decision genuinely wanted — which forces a real policy) **or**
the *all-throttles-legibility* need (D2 — which forces the reason to be a first-class output).
The present problem — **sleep/resume robustness + energy-aware indexing — is solved and
correct with the binary gate**, and the app is pre-users, so neither trigger has fired. Per
project discipline (and 587/C-018: do not build substrate for cases the problem does not yet
include), the design is **settled and recorded, not built.** Building the graded policy now
would be premature abstraction over a binary gate that works.

So the scope match is exact: **the present problem warranted the energy *member* (shipped),
not the policy *framework*** — and the framework is one consolidation step, triggered, that
two existing seams already bracket.

## What this supersedes / refines

This refines the earlier "Long-term design — energy-aware background work" section above: that
section designed *adding the energy member* (now shipped) and correctly called the framework
out-of-scope. This section, with the energy member built and the research in hand, **names the
full three-layer seam, places every remaining direction in a layer, and identifies layer 2 as
the single deferred structural step + its trigger** — converting a flat opportunity list into
one shaped seam with a clear build-order-when-triggered.

---

# Reach — principle, conformance, and candidate scope

## Conformance (an instance of existing seams — do not fork)

This design coins **no new seam.** It is the composition of three the system already has:
**587** (Sense), the **presentation/reason-code seam** (Projection), and — for the Policy
layer's *shape* — **627's "a declared policy per subject, expressed in the existing Capability
vocabulary, exercised by injection."** The work-gating Policy should be a declared policy in
the 587 capability vocabulary, exactly as 627's recovery policy is. The **Activity-Budget**
principle itself was already named earlier in this tempdoc; this conforms to it rather than
coining a parallel.

## The principle this reveals — **Decision-Legibility Closure**

> **Every condition that gates user-affecting work must terminate at a projection that names
> *why* — never solely at an internal boolean (a silent throttle).** The firing condition is a
> first-class output of the gate, not a side effect hidden inside it.

This is the **decision-side dual of 627's Observation-Actuation Closure.** 627: a *fault*
signal must close at a recovery **actuator** — "never solely at a status flag a human might
read." This: a *gating decision* must close at a legible **projection** — never solely at a
silent internal flag. They are siblings in one **Closure** family: *a meaningful condition must
reach its terminal consumer (an actuator that recovers, or a projection that explains) — it
must not dead-end at an internal flag read by neither.* 627 closes the fault→recovery loop;
this closes the decision→legibility loop.

## Candidate scope (where it applies beyond the immediate problem)

- **Activity-Budget (the seam):** every host-conditioned background subsystem that should
  consult the budget — indexing-loop, embedding / SPLADE / NER backfill, OCR / VDU, the
  reranker, and LLM activation. (Same scope 587/630 already named.)
- **Decision-Legibility Closure (the principle):** any user-affecting gate, not just
  throttling — e.g. "search ran keyword-only because the dense leg wasn't ready", "this result
  was dropped by the safety filter". The **readiness reason-code / CAUSE_ROWS seam already
  embodies this principle** for degradations — which is the strongest evidence it is real and
  worth conforming to, not inventing.

## Existing violations (recorded, not fixed)

- The **breath-hold** (user-active) and **GPU-yield** throttles are **silent**: they gate
  indexing but emit no user-visible reason — they dead-end at an internal boolean, the exact
  Decision-Legibility-Closure violation. 630's energy-pause + "catching up" work is the **first
  throttle to close the loop** (gate → legible reason). So the older throttles violate the
  principle the new work demonstrates — and closing them is precisely the layer-2 + D2 work
  above. Recorded; not fixed now (no trigger, no users).
- Layer 2's **scattered binary gates** are the embryonic, un-consolidated Policy — the thing the
  deferred structural step consolidates. Recorded as the known shape, not yet a problem.

**Separating recognition from construction (deliberate):** the principle and its scope are
named here so the insight is captured; the generalized structure (the graded Policy authority)
is *not* built, because the present problem does not yet require it. When a second graded
decision or the all-throttles-legibility need arrives, conform to this — do not re-derive it.

---

# Pre-implementation confidence — opportunity-map remaining work (2026-06-23)

> Read-only confidence pass (no feature code) over the highest-value remaining directions
> (A1 authoritative power events · C1 intensity ladder · D1 progress/"Up to date"), plus one
> throwaway FFM-upcall experiment. Reduces surprises before any future implementation.

| # | Uncertainty | Verdict | Evidence |
|---|---|---|---|
| U1 | **FFM *upcall* feasible? (A1)** — receiving OS power callbacks needs native→Java, which the codebase has never done (all downcalls) | **Resolved** | Throwaway `EnumWindows`-callback smoke (JDK-21 `Linker.upcallStub`) — the OS invoked our Java upcall **262×**, test green. Mechanism viable. *Residual:* the smoke is *synchronous*; the real power callback is *async on a foreign OS thread* (FFM-spec-supported, not exercised). |
| U2 | **Intensity ladder has real knobs? (C1)** | **Resolved, favourable** | `LoopPacingPolicy` is already the central pacing home — batch sizes (embed 100 / NER 100 / SPLADE 200 / disambig 500), idle/active sleep, interleave + commit intervals, all behind accessors, alongside the gates. C1 = parameterize these by intensity (lift the `static final`s) + route the scattered sense-reads through it. **No concurrency knob** (loop is sequential) → intensity = batch-size + interval modulation (still a continuum). |
| U3 | **Progress denominator for "X of Y"? (D1)** | **Resolved, favourable** | `CoreIndexView` exposes `indexedDocuments` + `pendingJobs`. "Up to date" = pendingJobs 0 + healthy (free); "X of Y" = `indexed / (indexed + pending)` (derivable). *Caveat:* Y grows if new files appear mid-catch-up; a *stable* denominator wants one small new "batch-total" field (optional). |
| U4 | **`nanoTime` freezes during Windows suspend? (A3)** | **Partial — A3 weaker than assumed** | `nanoTime` = QueryPerformanceCounter confirmed, but freeze-on-suspend is platform-inconsistent + not crisply documented for Windows-S3, and QPC "can leap." So the wall-vs-monotonic **divergence test is a best-effort fallback whose Windows soundness is itself uncertain** — A1 (events) is the real fix; A3 is a weak hardening. Real-suspend confirmation = residual. |
| U5 | **Gate-consolidation blast radius (B1)** | **Resolved** | ~38 sense-read occurrences / ~12 production consumer sites across the backfill/indexing pipeline; `LoopPacingPolicy` is the existing home to consolidate *into*. Moderate, bounded. |
| U6 | **Modern Standby real misfire rate (A4)** | **Residual** | Needs a Modern-Standby machine + suspend cycles to observe; out of in-harness reach. |

**What changed in my picture:** the *biggest* unknown (FFM upcalls for A1) is now de-risked —
proven viable — and the data/knobs for D1 + C1 already exist, so those are lower-risk than
assumed. Conversely A3 (the "cheap divergence-test win") is *shakier* than it looked, which
only strengthens the case that **A1 is the correct resume-correctness fix** and A3 is optional.
The dominant residuals are **empirical** (real suspend / Modern-Standby behavior — needs
hardware) and the **first-async-upcall** edge. The heaviest piece (C1's graded Policy) stays
deliberately deferred (no trigger), and the confidence pass lowered, not raised, its perceived
cost.

**D1 shipped (2026-06-23).** The one ready, browser-validatable, seam-conforming item — the
explicit **"Up to date" terminal state** — is implemented: `HealthSurface.queueSubLabel` now
returns "Up to date" on an idle + verified-healthy (`worker.core.indexHealthy`) index (honest
"Idle" fallback when health isn't confirmed), and "Indexing" (was "Processing") while work is
queued. This **completes the calm-status vocabulary** — "Catching up…" and "Paused — saving
energy" now resolve to an explicit "Up to date" close (the Dropbox model) automatically. No
backend/schema/verdict change. Browser-validated on the Health surface (Queue 0 + healthy ⇒
"Up to date"; Queue 50 ⇒ "Indexing"); `HealthSurface.render.test.ts` covers the four cases; full
FE suite + ui-web a11y/presentation gates green. The dropped "X of Y" denominator was a correct
call: a re-indexed file counts in both `indexedDocuments` and `pendingJobs`, so "X of Y" would
mislead. **Everything else in the opportunity map stays deferred per the settled design** (A1/A2
events — not required + not browser-validatable without real suspend; C1/B1/D2 framework — no
trigger; C2/C3/C4 — platform-future; A3 — shaky).

**Critical confidence for the remaining work: 7/10** — mechanisms + data confirmed for the top
items (A1 viable, D1/C1 knobs present); residuals are mostly empirical (need a real sleep cycle
to validate resume/Modern-Standby behavior) and the async-upcall edge; A3's Windows soundness
is the one genuinely shaky assumption, but it is low-stakes (a benign fallback).
