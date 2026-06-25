---
title: "3-process supervision & crash-recovery: turn 'a crash in Body or Brain does not cascade' from an architectural claim into a designed, exercised contract — explicit per-fault recovery policy, hardened weak modes, fault-injection tests as the standing proof"
type: tempdocs
status: implemented
created: 2026-06-21
updated: 2026-06-21
author: agent analysis (engine-reliability gap scan); implemented by assigned agent 2026-06-21
category: engine / process-lifecycle / supervision / crash-recovery / reliability / product-shape
principle: "the recovery contract is a *declared policy per process* (detection → recovery → terminal-state per fault) expressed in the existing Capability vocabulary and exercised by fault-injection — not behavior assumed from scattered monitor/spawner logic, and not a single god-FSM unifying two different substrates. Underlying invariant (Observation-Actuation Closure): every health/liveness signal that can indicate a recoverable fault must terminate at an actuator that recovers or declares a terminal state — never solely at a status flag a human might read. 'We report degraded' (600/604) is not 'we recover correctly.'"
related:
  - 588-worker-engine-silent-failures                # adjacent: indexing-loop death honesty (upstream-done)
  - 600-degradation-cause-not-observable             # adjacent: degradation LEGIBILITY (not the recovery mechanism)
  - 604-diagnostic-surface-liveness-recovery         # adjacent: the monitoring UI's own liveness
  - 587-host-capability-sensing-substrate            # adjacent: capability sensing substrate
  - 626-incremental-indexing-correctness             # sibling reliability tempdoc (this T1 triad)
  - 628-index-durability-corruption-recovery         # sibling reliability tempdoc (this T1 triad)
---

> NOTE: Noncanonical working tempdoc. **Status: IMPLEMENTED (2026-06-21)** — the Worker supervision
> open-loop is closed, exercised by unit tests and a live kill→recover→give-up validation. See
> "Implementation outcome" at the foot. The investigation/design/confidence sections below are the
> dated record that led to the implementation; trust the outcome section for current truth.

# 627 — 3-process supervision & crash-recovery

## Thesis

"Local-first microservices: a crash in one process doesn't cascade" is the headline architectural
differentiator. Recent tempdocs touched only its *legibility* (600 degradation cause, 604
diagnostic-surface liveness) and the loop's *honesty* (588) — never the **recovery mechanism's actual
correctness** end-to-end. Whether the Head detects a dead/hung/zombie Worker or llama-server and returns
the system to a correct serving state is currently *assumed*, not *exercised*. Per `audit-without-test`,
each lifecycle claim is a hypothesis until a kill→recover path is tested.

## Goal (more than validation)

The fault-matrix audit is the **entry point**. The goal is a designed, exercised recovery contract:

1. **Verify** recovery across the fault matrix (clean exit / hang / OOM-kill / zombie / partial boot)
   for both Body and Brain — each failure mode gets a verdict against source.
2. **Define the recovery contract explicitly** — for each failure mode, the declared
   detection → recovery → terminal-state policy (restart cap, backoff, external-adoption, give-up), as a
   single authority rather than scattered monitor/spawner logic.
3. **Harden the weak modes** the audit exposes (hang vs clean-exit, zombie, partial-boot) — not only the
   modes that already happen to work.
4. **Durable guard** — fault-injection tests wired as the standing proof the contract holds, so recovery
   cannot quietly rot.

## Polish dimension (subordinate, gated)

Leave each subsystem touched more legible than found — **only what the reliability work already
modifies.** Bias toward diagnostic/log/error legibility (it serves the "make failure loud / declared
policy" goal) and structural decomposition of the monitor/spawner classes that make recovery hard to
reason about. Found-but-untouched polish → `docs/observations.md` inbox. Exclude user-facing/UI polish
of the monitoring surfaces (600/604 own that).

## Scope

- **In:** detection + recovery correctness, restart-cap/backoff semantics, adoption of an
  externally-started brain, partial-boot/zombie handling.
- **Out:** the legibility/diagnostic surfaces (600/604) and the indexing-loop-death honesty (588) —
  upstream-done.

## Starting points for the assigned agent (UNVERIFIED — audit these)

- `modules/app-services/.../worker/KnowledgeServerHealthMonitor.java`, `WorkerSpawner.java`,
  `KnowledgeServerBootstrap.java`
- `modules/worker-services/.../services/GrpcHealthService.java`
- `modules/ui/.../HeadlessApp.java`, `StreamLivenessWindows.java`
- the llama-server / Brain lifecycle manager (`BrainRuntimeService` + inference lifecycle in
  `modules/app-inference` / `modules/app-api`)

## Discipline (binds this tempdoc)

Discovery before work; distinguish "we report degraded" from "we recover correctly." Each lifecycle
claim is a hypothesis until a fault-injection test exercises it (`audit-without-test`,
`audit-driven-fixes-need-test`). Dev-stack-only recovery paths → document as user-driven smokes per
`slice-execution.md`. Fix weak modes as instances of the class (`structural-defects-no-repeat`).

## Definition of done

A fault-injection matrix (each failure mode × expected recovery) with a runnable test per row; a named
recovery-contract authority; hardened weak modes; the dev-stack-only rows documented as smokes.

## Next step (not done here)

Assign an agent; first deliverable is the fault-matrix discovery audit.

---

# Investigation — Deliverable 1: fault-matrix discovery audit

> Author: assigned agent (takeover 2026-06-21). Status: **audit complete; design/implementation NOT
> started.** Every claim below is cited to primary source (`file:line` on `main`). Subagent findings
> were re-verified by direct read of the cited lines before being recorded here (`audit-without-test`:
> these are still *hypotheses about behavior* until a fault-injection test exercises each row — see the
> "Standing-proof gap" below).

## Method

Read the supervision code on both sides directly (not log-grepping): Body = `WorkerSpawner`,
`KnowledgeServerHealthMonitor`, `KnowledgeServerBootstrap`, `MmfWorkerSignalBus`, `GrpcHealthService`,
`WindowsJobObject`; Brain = `LlamaServerOps`, `InferenceLifecycleManager`, `TransitionRunner`. Then
located every existing test that exercises a real kill/hang/restart, and checked whether those tests are
wired into any standing gate. The starting-point file list in the stub was **all confirmed to exist on
`main`** (paths accurate).

## The fault matrix (verified)

Legend: **RECOVERS** = source returns the system to a correct serving state for this fault;
**GAP** = fault is *detected but not recovered* (the thesis's "report degraded ≠ recover correctly"),
or not detected at all; **OK-by-design** = intentional non-recovery with a declared terminal state.

### Body (Worker / Knowledge Server) — restart authority: `WorkerSpawner`

| Fault mode | Detection | Recovery action | Terminal state | Verdict |
|---|---|---|---|---|
| **Clean process death** (exit, segfault, OS-kill) | `checkWorkerHealth` polls `!p.isAlive()` every 1s (`WorkerSpawner.java:631-645`) | Restart, cap `MAX_RESTART_ATTEMPTS=3` (`:44`), exp backoff 1/2/4s cap 30s (`:662-666`), counter reset after `stabilityWindowMs` of uptime (`:649-658`) | After cap: `running.set(false)`, `log.error` "giving up" (`:691-695`) — **no distinct user-surfaced terminal state**, capability left at last value | RECOVERS (cap'd). Give-up state under-declared. |
| **Hang** (process alive, gRPC unresponsive — deadlock, gRPC thread dead) | `KnowledgeServerHealthMonitor` polls `checkHealth()` every 10s (`:20`); failure → `workerCapability` → `DEGRADED` (`KnowledgeServerBootstrap.java:388-390`) | **NONE.** `checkHealth()` transitions to DEGRADED and returns; nothing calls `spawner.restart()` or kills the worker. `WorkerSpawner.checkWorkerHealth` only restarts on `!isAlive()`, which a hung process is not. | DEGRADED, indefinitely | **GAP — the central finding.** Detected, never recovered. |
| **OOM** (worker heap exhausted) | JVM exits non-zero → seen as clean process death | Restart (as above); `-XX:+HeapDumpOnOutOfMemoryError` dumps to `crashes/` (`:474-475`) | restart-cap give-up if OOM recurs every boot | RECOVERS-as-death (no OOM-specific policy; recurring OOM → 3 fast restarts then give up) |
| **Zombie** (worker outlives a dead Head) | (a) `MmfWorkerSignalBus.shouldDie()`: heartbeat stale > 5s (`HEARTBEAT_STALE_MS=5000`), 15s startup grace (`STARTUP_GRACE_MS`), checked every 1s by the worker sentinel thread; (b) `WindowsJobObject` `KILL_ON_JOB_CLOSE` (Windows only) | Worker self-exits (graceful) on stale heartbeat; OS force-kills on job-handle close | clean exit | RECOVERS (two redundant paths on Windows; **heartbeat-only on Linux/macOS** — Job Object is Windows-only) |
| **Partial boot** (process spawns, never publishes gRPC port) | `awaitPort` timeout (`:200-206`) | Initial boot: `start()` throws → `Bootstrap` catch → DEGRADED + `close()` (`:180-185`) — legible. **Restart path** (`:674-690`): `awaitPort` timeout is caught and logged, the spawned process is **not reaped** and `restartCount` stays incremented → a port-less but `isAlive()` process can sit indefinitely (next `checkWorkerHealth` tick sees `isAlive()==true` → returns) | varies | INITIAL boot RECOVERS legibly; **RESTART partial-boot is a suspected weak mode** (orphaned port-less worker). Needs a test to confirm. |

### Brain (llama-server / inference) — restart authority: `LlamaServerOps`

| Fault mode | Detection | Recovery action | Terminal state | Verdict |
|---|---|---|---|---|
| **Clean death / crash** | `Process.waitFor()` monitor future (`LlamaServerOps.java:560-570`) → `handleServerCrash()` | Restart, cap `MAX_CRASHES=3` (`:84`), backoff 0ms first / 5s after (`CRASH_RECOVERY_DELAY_MS`), reset on healthy restart (`:825`) | crashes≥3 → `forceOffline` (`:803-806`) → `ILM.handleMaxCrashOffline` → `TransitionRunner.runForceOffline` → OFFLINE + bounded failure-history ring | RECOVERS; **terminal OFFLINE explicitly declared + surfaced** |
| **Hang** (process alive, `/health` unresponsive) | `schedulePeriodicHealthCheck` every 30s (`:672-720`), `CONSECUTIVE_FAILURES_BEFORE_RESTART=3` (`:88`) → `handlePeriodicHealthFailure` (`:723-752`) | Managed: `handleServerCrash()` (restart). External-adopted: `goOfflineFromExternalFailure` (no restart — no handle) | OFFLINE after cap | **RECOVERS** — this is exactly what the Body lacks |
| **Partial boot** (model load fails / never HTTP-ready) | `waitForServerHealth` polls `isAlive()` + `/health`, 120s deadline (`:361-427`); diagnoses log tail (arch mismatch, CUDA OOM, port in use) | throw `ModeTransitionException` → revert to prior mode | OFFLINE, diagnosed | RECOVERS/legible |
| **Adopted external brain** | adoption-first on every start (`:200-205`); health-only adoption gated by dev sysprop | cannot restart (no process handle) → health failure forces OFFLINE | OFFLINE | OK-by-design (documented limitation) |

## The headline: a verified Body↔Brain asymmetry

The thesis ("we report degraded ≠ we recover correctly") has **one concrete, source-verified instance**:

- **Brain recovers from a hang** (periodic `/health` poll → 3 failures → restart, cap 3).
- **Body does not.** A hung-but-alive Worker is detected (`checkHealth` → DEGRADED) and then left there
  forever. The two Body monitors have a blind spot between them: `WorkerSpawner` watches *process
  liveness* (restarts on death), `KnowledgeServerHealthMonitor` watches *gRPC health* (only flips a
  capability flag). **Nothing bridges gRPC-health-failure → a bounded Worker restart.**

This is the single clearest "designed recovery is missing, not just un-tested" gap, and it is the
natural first hardening target (mirror the Brain's periodic-health→restart on the Body, *with the
caveats below*).

## The standing-proof gap (the "durable guard" goal is currently unmet)

Real kill→recover tests **do exist** — `ChaosSuiteTest` (`modules/system-tests/.../ChaosSuiteTest.java`)
genuinely kills a real worker (`workerRecoveryAfterMidTrafficKill`, `clientDetectsWorkerDeath`),
exercises the heartbeat suicide pact (`workerDiesWhenHeartbeatStale`), and `WindowsJobObjectTest`
exercises kill-on-close. **But none of them are a standing guard:**

- The `systemTest` Gradle task is `enabled = includeSystemTests` — **opt-in only**
  (`modules/system-tests/build.gradle.kts:229-231`); default `check` depends only on `test` (`:297-299`).
- **CI never enables it** — `grep` of `.github/workflows/` for `includeSystemTests` / `systemTest` /
  `INCLUDE_SYSTEM_TESTS` returns nothing.
- Combined with ADR-0026 (manual-only CI), the chaos tests can pass today and silently rot tomorrow;
  they are not the "fault-injection tests wired as the standing proof" the tempdoc asks for.

Coverage by mode (real-process exercise): clean-death ✓ (opt-in), zombie/suicide-pact ✓ (opt-in),
mid-traffic-kill+restart ✓ (opt-in). **No exercise at all** for: Body **hang**, restart-**cap**
exhaustion + give-up state, **partial-boot on the restart path**, **OOM**, and **any** real-process
Brain kill (all Brain crash/hang tests invoke `handleServerCrash()`/mocks directly — never spawn and
kill a real `llama-server.exe`).

## Critical analysis of the proposed solution (questioning the tempdoc)

1. **"Single declared authority" risks the wrong kind of DRY.** Body and Brain recover via genuinely
   different substrates (gRPC + MMF-heartbeat + JVM child vs HTTP + native exe; the Worker owns durable
   Lucene state, llama-server owns nothing durable). Forcing both through one code path violates AHA
   ("only unify what shares a reason to change"). What *is* worth unifying is a **declared policy
   *table*** (fault → detection → recovery → terminal-state, per process) that is (a) legible in one
   place and (b) the source the fault-injection tests assert against — not necessarily one shared
   recovery class. Recommend: contract-as-declared-data/doc + per-process enforcement, not a god-object.

2. **Naively "mirror the Brain" on the Body could introduce a corruption risk the Brain doesn't have.**
   Killing a hung Worker that is mid-Lucene-commit is precisely the durability hazard owned by sibling
   tempdoc **628** (index durability / corruption recovery). The Brain is stateless wrt disk, so its
   kill-and-respawn is safe; the Worker is not. Body hang-recovery must therefore be **sequenced with /
   gated on 628's durability guarantees** (e.g., only force-kill after a bounded graceful-stop that
   flushes/closes the index, or accept that recovery includes a corruption-repair step). This
   cross-tempdoc dependency is not noted in the stub and should be.

3. **"Hang" here is narrower than "the worker is broken."** The gRPC health probe checks resource
   connectivity (SQLite + Lucene readability), **not indexing-loop liveness** — a worker can report
   `serving=true` with a dead indexing loop (that honesty gap is tempdoc **588**, marked upstream-done).
   So Body "hang-recovery" in 627's scope = *gRPC-unresponsive*, a real but bounded slice. The tempdoc
   should state this boundary so the fix isn't mis-scoped to "detect all worker brokenness."

4. **The "durable guard" goal collides with ADR-0026 (manual-only CI).** A standing proof that only
   runs on opt-in/nightly isn't really standing. This needs an explicit decision (a question for the
   user, below): either (a) a **fast in-process fault-injection tier** that can live in default `check`
   (inject a fake hung/dead `Process` + a stub gRPC health into the real `WorkerSpawner`/monitor wiring
   — no JVM spawn, milliseconds), or (b) a **dispatched/nightly system-test lane** accepted as the guard
   despite manual-only CI. Option (a) is more in keeping with "local-first verification is the primary
   discipline" and would actually gate the give-up/hang/partial-boot rows.

5. **Give-up terminal state is under-declared on the Body** (vs the Brain's explicit `forceOffline` +
   failure ring). After `running.set(false)` the Worker capability isn't moved to a distinct
   "permanently failed / gave up" state separable from transient DEGRADED. The recovery contract needs a
   terminal state the surfaces (600/604) can read — defining the *state* is in-scope here even though the
   *UI* is not.

## Open questions for the user (before any design/impl)

- **Q1 (scope of the asymmetry fix):** Is closing the Body hang-recovery gap (bridge gRPC-health-failure
  → bounded Worker restart) the priority deliverable, or do you want the *full* matrix (OOM, partial-boot
  restart-path, give-up state) hardened in this pass? The stub says "harden the weak modes the audit
  exposes" (all of them) — confirming you want the complete set, not just the headline.
- **Q2 (628 sequencing):** Body hang-recovery (kill + respawn a Worker that may be mid-commit) depends on
  628's durability guarantees. Do 627 and 628 land together, does 627 gate on 628, or does 627 ship a
  conservative "graceful-stop-then-kill only" path now and defer corruption-repair to 628?
- **Q3 (standing-proof tier):** Given ADR-0026, should the durable guard be a fast in-process
  fault-injection tier in default `check` (recommended), or an accepted nightly/dispatched system-test
  lane?
- **Q4 (contract shape):** Declared *policy table* (data/doc the tests assert against) + per-process
  enforcement, or the stub's literal "single authority" code object? I recommend the former on AHA
  grounds.

---

# Design (long-term) — Deliverable 2

> Author: assigned agent, 2026-06-21. Design theorization only — **no implementation**. Investigated
> what already exists before proposing structure (below); the conclusion is to **extend existing seams,
> not build a parallel supervision framework.** Scope is matched to the verified gap, not to the
> abstract "supervision" topic.

## What already exists (and is reusable)

The investigation found that most of the substrate a recovery contract needs **already exists** — it is
just wired asymmetrically. The design extends it rather than replacing it.

- **`Capability` / `CapabilityHealth`** (`modules/app-api/.../lifecycle/Capability.java`,
  `CapabilityHealth.java`): the *shared* lifecycle-health seam, states `PENDING / READY / DEGRADED /
  RECOVERING / OFFLINE`. Implemented by **both** `WorkerCapability` and `InferenceCapability`
  (`modules/app-services/.../lifecycle/`). Both expose `transition(health, reason)` and
  `addListener(BiConsumer<old,new>)`. **This is the vocabulary the contract is expressed in** — not a new
  parallel state enum.
- **`WorkerSpawner.restart()`** (`WorkerSpawner.java:229-270`): a *graceful-stop-then-force* restart
  actuator **that already exists** (used today for config-apply). It calls `destroy()`, waits
  `workerShutdownTimeoutMs`, then `destroyForcibly()`. The Body's recovery *mechanism* is already built;
  only the *decision edge that calls it on a hang* is missing.
- **`LifecycleReasonCode`** (`.../lifecycle/LifecycleReasonCode.java`): closed, automation-stable code
  taxonomy already carrying `WORKER_SPAWN_FAILED`, `WORKER_UNAVAILABLE`, `WORKER_NOT_STARTED`, … — the
  terminal/give-up state has a reason vocabulary already.
- **`LifecycleProjection.derive(...)` + `LifecycleSnapshotV1`**: the aggregation + wire surface
  `/api/health` and `/api/status` read. A terminal Worker state expressed as a `CapabilityHealth` +
  `LifecycleReasonCode` is surfaced **for free** through this path (600/604's domain consumes it).
- **The Brain's recovery FSM** (`ModeStateMachine`, `TransitionRunner`, `TransitionReason`,
  `FailureRecord` ring×20, `InferenceTransitionLog` sidecar): the **reference shape** for a closed
  detection→decision→action→terminal loop with bounded forensic history.

## Design tenet: conform to the Capability seam; do **not** generalize the Brain's mode-FSM

The tempting move (and a subagent's recommendation) is to extract the Brain's `ModeStateMachine` +
`TransitionRunner` into a shared supervision framework both processes use. **Reject this.** The Brain's
states `ONLINE / INDEXING / TRANSITIONING` encode *inference mode-switching* (GPU yield to Worker, VDU
enter/exit, config-apply-with-restart) — concepts the Worker does not have. Pushing the Worker through a
4-mode FSM imports structure the Worker's problem does not include (AHA: don't add structure for cases
the problem doesn't yet have). The **genuinely shared shape already exists and is already shared**: the
`Capability` health states. That is the seam to conform to. Body and Brain keep their substrate-specific
mechanisms (gRPC + signal-bus + `WorkerSpawner` vs HTTP + `ProcessBuilder` + `LlamaServerOps`); they
converge only on the *vocabulary* and the *contract*, not on one code path.

## The contract: a declared **SupervisionPolicy** per process (data, not god-object)

Refine the stub's "single declared authority" from *one code object* to **one declared policy value per
supervised process, plus one decision seam per process, both expressed in the Capability vocabulary.**
The policy is the fault→detection→recovery→terminal-state table made into named data:

- detection sources (process-liveness; health-probe + consecutive-failure threshold),
- restart cap, backoff schedule, stability-reset window,
- graceful-stop budget before force,
- terminal `CapabilityHealth` + `LifecycleReasonCode` on give-up.

Today these live as scattered magic constants (`WorkerSpawner.MAX_RESTART_ATTEMPTS/…`;
`LlamaServerOps.MAX_CRASHES/CONSECUTIVE_FAILURES_BEFORE_RESTART/…`). Collapsing each side's constants
into its own declared `SupervisionPolicy` makes the contract legible **and** gives the fault-injection
tests a single thing to assert against. The two policies need not share values — the Worker and the Brain
are allowed different caps/timeouts. "Single authority" = single *declared* policy per process, not one
shared object across processes.

## The actual fix: close the Body's observation→actuation loop (matched to the verified gap)

The verified gap is small and structural: the Body's health signal **dead-ends at a status flag**.

- `WorkerSpawner.checkWorkerHealth` watches *process liveness* → actuates restart on death. (closed loop)
- `KnowledgeServerHealthMonitor` watches *gRPC health* → flips `WorkerCapability` to DEGRADED → **no
  actuator.** (open loop — the bug)

The design closes the loop by **consolidating the Body's two detection sources into one decision seam**
that owns the `SupervisionPolicy` and treats *either* a dead process *or* a sustained DEGRADED-from-health
as the same kind of fault requiring bounded restart through the **existing** `WorkerSpawner.restart()`.
This consolidation is itself justified by the problem (not just tidiness): today's cap counts only
process-death restarts, so a hang-induced restart and a death-induced restart would otherwise use two
independent counters and could race or double-spend the budget. One decision seam → one cap/budget →
correct give-up behavior. This is *extending and unifying existing Body code under its single
reason-to-change* (Worker recovery policy), the right kind of DRY — not a new framework.

Concretely (shape, not implementation):
1. A Worker-side **decision function**: given `(processAlive, healthState, policy, history)` → one of
   `{no-op, restart, give-up-terminal}`. Pure, deterministic, substrate-free.
2. The two detectors (process-watch, health-poll) feed this one function; it calls the existing
   `restart()` actuator (which already does graceful-stop-then-force — satisfying the 628 boundary) or
   drives `WorkerCapability` to a **terminal** state (`OFFLINE` or DEGRADED-with-terminal-reason-code) on
   cap-exhaustion, mirroring the Brain's `forceOffline`.
3. A bounded **failure history** on the Worker, mirroring the Brain's `FailureRecord` ring (reuse the
   pattern; this is the forensic complement 600 wants — lower priority than the loop-closure itself).

## Prior art / lineage (conform to established names, don't coin)

A bounded research pass (this is mature, settled craft — no fast-moving 2025–26 frontier would change
the design; the value is *vocabulary alignment*, per the project's "conform to an existing principle
rather than a parallel version" discipline) anchors three pieces of this design to standard names:

- **The `SupervisionPolicy` is Erlang/OTP *restart intensity*.** OTP supervisors declare
  `(intensity = MaxR, period = MaxT)`: if more than `MaxR` restarts occur within `MaxT` seconds, the
  supervisor gives up — terminates the child and itself. That is exactly this design's "restart cap +
  terminal give-up." The Body's current "3 consecutive + stability-window reset" is a cruder cousin of
  the canonical *rolling-window* `(MaxR, MaxT)`; the policy should adopt the rolling-window form (the
  stability-window already approximates `MaxT`). The Head-stays-up-when-the-Worker-dies shape is OTP's
  **`one_for_one`** strategy (restart only the failed child). Naming the policy fields `(maxRestarts,
  withinWindow, strategy=one_for_one, terminalState)` conforms to 30 years of prior art.
- **The verified Body gap is the Kubernetes *liveness-vs-readiness* conflation.** K8s draws the exact
  line this audit found: a **liveness** probe answers "should this be *restarted*?" (catches a deadlock —
  process running but stuck — and the kubelet *restarts* it); a **readiness** probe answers "should this
  receive *traffic*?" (on failure the container is marked not-ready and removed from service, **not
  restarted**); a **startup** probe gates the other two during boot. **The Worker today only has a
  readiness signal** (gRPC-health-failure → flip `WorkerCapability` to DEGRADED → removed from serving),
  where a *hung* Worker actually needs a **liveness** response (→ restart actuator). The fix is precisely
  "give the Worker a liveness probe, not just the readiness probe it has." K8s's own best practice —
  *"a liveness probe should fail only when the container cannot self-recover; if the failure is transient,
  restarting makes things worse"* — independently validates this design's consecutive-failure threshold
  (don't restart on one blip) and graceful-stop-before-force (don't corrupt on a transient). The
  partial-boot grace period is the **startup-probe** role.
- **"Observation-Actuation Closure" is *closed-loop control* / the *reconciliation loop*.** The principle
  named in the Reach section is the control-theory closed loop (controller drives actual state toward
  desired via feedback) — the same shape as a Kubernetes controller's reconciliation loop. The bug class
  has a standard name too: an **open loop** (a controller that senses but has no feedback path to an
  actuator). This design *closes an open loop*. Keep the local handle but anchor it to closed-loop
  control so we conform rather than coin.

## Cross-tempdoc boundaries (must be stated in the contract)

- **628 (durability):** the Worker owns durable Lucene/queue state, so the supervisor must never *raw*-
  kill a possibly-mid-commit Worker. The design mandates the restart actuator always attempt the bounded
  graceful-stop first (which `restart()`/`close()` already do via the signal bus `writeShutdown`).
  **Declared handoff:** 627 guarantees *graceful-stop-attempt-then-force*; 628 owns
  *detect-corruption-and-rebuild* for the force case (`kill -9`, power loss). Confirm with 628 before
  closing.
- **626 (change-correctness):** 627 does not touch live watcher DELETE paths; 626 fixes the unguarded
  Worker DELETE first. No structural overlap.
- **588 (loop honesty):** 627 *consumes* 588's fix — the decision function reads the now-honest
  `isRunning()` liveness, not the stale `currentState`. "Hang" in 627's scope = gRPC-unresponsive; loop-
  death honesty is 588's, upstream-done.
- **587 (host-capability sensing):** the supervisor *may* consult 587's confidence-tagged host facts
  (e.g. RAM) for policy decisions, but must not roll a second host-state observer. 587 is sensing, not
  actuation — orthogonal.

## Standing proof: a fast in-process fault-injection tier, registered as a logic-seam

The "durable guard" goal collides with ADR-0026 (manual-only CI) only if the proof must spawn real JVMs.
It need not. Because the design isolates a **pure decision function** behind a named seam, each
fault-matrix row becomes a millisecond in-process unit test (inject a fake `(processAlive, healthState)`
sequence + a stub `restart`/`transition`; assert the decision and the resulting terminal state). These
run in default `check` — a real standing guard. The existing `ChaosSuiteTest` real-process kills stay as
the **opt-in/nightly higher-fidelity tier** (unchanged), not the primary guard.

This conforms to an **existing** registered concept rather than inventing a parallel guard: the decision
function is exactly a `governance/logic-seams.v1.json` seam — pure, branch/arithmetic-dense, whose
failure mode is a *silent wrong value* (wrong restart/give-up verdict). Register it there with its law
("a recoverable fault never dead-ends at a status flag; cap is shared across fault sources; give-up is
terminal and reason-coded") and a guard test, so the `test-efficacy` gate measures whether its tests
bite. The `seam-hint` PostToolUse hook will prompt this on the new class.

## Explicitly out of scope (structure the problem does not require)

- **No** generalized multi-capability "SupervisorRegistry." Only the Worker currently has an *open* loop
  needing actuation (see reach analysis). GPU/host capabilities are sensing, not faults to recover; the
  Brain's loop is already closed. Building a generic framework for one real instance is premature.
- **No** porting of the Brain's `ModeStateMachine` to the Worker (the Worker has no modes).
- **No** new wire/UI surface for the terminal state — it rides the existing `LifecycleSnapshotV1`
  path; 600/604 own presentation.

---

# Reach — is this an instance of a wider principle?

## The principle: **Observation-Actuation Closure** (= closed-loop control, applied to fault signals)

> Every health/liveness signal that can indicate a *recoverable* fault must terminate at an **actuator**
> that either recovers the fault or declares a **terminal, reason-coded state**. A signal whose only
> sink is a status field a human might read is a latent "report-degraded-but-don't-recover" bug.

This is not a new idea — it is **closed-loop control** (the controller/reconciliation loop of control
theory and Kubernetes controllers) named for the fault-recovery domain. The bug class is its
control-theory negative: an **open loop** — a sensor with no feedback path to an actuator. Anchoring the
handle here (rather than presenting it as novel) is itself an instance of the project's "conform to an
existing principle" discipline.

The Body hang gap is one instance: `WorkerCapability → DEGRADED` is an observation with no actuator. The
fix is to give the observation a sink (the decision seam → restart/terminal). The principle names the
*class* of bug, of which the verified gap is a member.

## Where this shape already appears in the system (it is recurring, not unique)

- **604 (SSE liveness):** `EnvelopeStream` *observed* `readyState=CLOSED` but had no actuator to re-
  establish — wedged forever. Fixed by adding a self-heal actuator. **Same shape, transport tier.**
- **588 (loop honesty):** the loop died but `currentState` still read RUNNING — the *observation itself*
  lied, so no actuator could ever fire. 588 fixed the observation (`isRunning()`); 627 supplies the
  actuation on the same Worker. **Two halves of one closure at the process tier.**
- **600 (degradation legibility):** the DEGRADED *reason* reached the wire but the FE projection dropped
  it — the signal dead-ended before its consumer. **Same "signal dies before its sink" shape, one tier
  up (legibility rather than recovery).**

That three shipped tempdocs (604, 588, 600) and this one are all instances of the same closure failure at
different tiers (transport, detection, legibility, actuation) is strong evidence the principle is real and
load-bearing.

## Candidate scope, and where existing code already complies or violates

- **Complies:** the Brain's loop (`LlamaServerOps` health-poll/crash → `forceOffline`); the Brain's
  adopted-external case (health-failure → terminal OFFLINE, recovery deliberately declined but *terminal
  and declared*).
- **Violates (the one live instance):** the Worker hang path — this tempdoc's target.
- **Correctly exempt (do not actuate):** GPU/host capabilities from 587 — these are *sensing* values that
  inform policy, not faults to recover; their correct sink is a policy consumer, not an actuator. The
  principle applies to *recoverable-fault* signals, not to every observable.

## Deliberate non-generalization (record the principle, don't build the structure)

The principle is named and its candidate scope recorded — but the only present instance that *requires*
actuation is the Worker. Building a generalized "every Capability gets a supervisor" framework now would
add structure for cases that don't exist (the compliant Brain, the exempt host-capabilities). Per the
separation of *recognizing* a general principle from *building* general structure: **conform the Worker
fix to the principle, register the decision logic as a logic-seam, name Observation-Actuation Closure in
the contract doc — and stop there.** If a third recoverable-fault Capability with an open loop later
appears, *that* is when the shared supervisor structure earns its place.

---

# Confidence-building findings (pre-implementation) — Deliverable 3

> Author: assigned agent, 2026-06-21. Six load-bearing assumptions probed by direct source reading
> (read-only; no feature code). Each moved from "assumed" to a verdict with `file:line` evidence. **The
> pass caught one wrong subagent claim and one platform-specific surprise that change the implementation
> shape — exactly its purpose.**

| # | Assumption | Verdict | Evidence |
|---|---|---|---|
| U1 | Graceful stop commits the Lucene index cleanly | **CONFIRMED (subagent claim refuted)** | `setCommitOnClose` is **never called** in `adapters-lucene` (repo-wide grep: 0 hits); `ComponentsFactory.java:164` builds a plain `IndexWriterConfig`, so Lucene's default `commitOnClose=true` holds → `RuntimeSession.close()` `writer().close()` (`RuntimeSession.java:555`) commits. The graceful path reaches `close()` two ways: suicide-pact self-exit via `IndexerWorker` try-with-resources, and the JVM shutdown hook (`IndexerWorker.java:106-117`). |
| U2 | `isHealthy()==false` is what a hang produces; bounds "hang recovery" | **REFINED (bound made precise)** | DETECTED → restart-worthy: gRPC timeout/UNAVAILABLE, circuit-breaker-open (`RemoteKnowledgeClient.isHealthy()` + `GrpcCircuitBreaker`), SQLite/Lucene probe failure (`GrpcHealthService.check()` ~197-220). UNDETECTED (still `serving=true`): indexing-loop stalled while gRPC thread alive (588's domain), embedding-not-ready. **627 hang-recovery = gRPC-unresponsive + resource-probe failure; loop-death honesty stays 588.** |
| U3 | A decision layer can reach the restart actuator safely | **CONFIRMED + caveat** | Public seam already exists: `KnowledgeServerBootstrap.spawner()` (`:349`) and the `WorkerService.restart()` abstraction, already invoked by 4 callers (`WorkerServiceImpl:56`, `AiInstallService:787`, `AiPackImportService:587`, `InferenceHandlers:531`). **Caveat:** the manual `restart()` path does **not** touch `restartCount`, so it bypasses the death-path cap — the consolidation must reconcile the two restart-accounting paths into one budget. |
| U4 | Consolidating the two detectors won't break standalone `WorkerSpawner` users | **CONFIRMED feasible** | Standalone constructors (`KnowledgeServerIntegrationTest`, `RichDocumentIntegrationTest`) only `start()`/`close()` — none depend on the hang-decision; death-restart is internal to the production monitor wiring. |
| U5 | Terminal give-up maps onto existing lifecycle vocabulary | **CONFIRMED, no new enum state** | `CapabilityHealth` = `PENDING/READY/DEGRADED/RECOVERING/OFFLINE` (`CapabilityHealth.java`). `RECOVERING` is **unused by the Worker today** — semantically exact for "restart in progress"; give-up → `OFFLINE`/`DEGRADED` + a terminal `LifecycleReasonCode` (reuse `WORKER_UNAVAILABLE` or add one `WORKER_RESTART_EXHAUSTED`). No new state needed. |
| U6 | The Brain's loop is genuinely closed | **CONFIRMED** | `LlamaServerOps.handleServerCrash` (max-crashes) → `InferenceLifecycleManager.handleMaxCrashOffline` → `TransitionRunner.runForceOffline` → mode-change listener in `InferenceCapabilityWiring.java:41-60` → `InferenceCapability.transition(OFFLINE)`. Reach analysis stands: **exactly 1 open loop (Worker).** |

## The surprise that changes the implementation shape (U1 + U3 combined)

The graceful-stop sequence **exists but lives in `WorkerSpawner.close()`** (signal-bus
`writeShutdown` → `waitFor` → only then `destroyForcibly`). **`WorkerSpawner.restart()` does NOT use it**
— it calls bare `p.destroy()` then `clearShutdown()` (`WorkerSpawner.java:237-258`), never
`writeShutdown()`. On the target platform (Windows), `java.lang.Process.destroy()` is `TerminateProcess`
— a hard kill with no shutdown hooks and no commit (established JVM behavior: Windows has no graceful
process signal; `destroy()` == `destroyForcibly()`). **Therefore reusing `restart()` verbatim for
hang-recovery would hard-kill a possibly-mid-commit Worker on Windows — the exact 628 corruption hazard
the design promised to avoid.**

**Forced design adjustment:** the hang-recovery actuator must drive the **signal-bus graceful-stop**
(mirror `close()`: `writeShutdown` → bounded wait → respawn), not bare `restart()`. Cleanest shape:
extract the graceful-stop sequence shared by `close()` into a `gracefulRestart()` the supervisor calls,
leaving `restart()`'s hard path for the cases that want it. This keeps the 628 boundary honest: 627
guarantees *signal-then-bounded-wait-then-force*; 628 owns only the post-timeout forced-kill corruption
case.

**Pre-existing latent risk surfaced (in-scope-adjacent):** because *all* config-apply restarts route
through the same `restart()` (`AiInstallService`, `AiPackImportService`, `InferenceHandlers`,
`WorkerServiceImpl`), **every config-apply Worker restart on Windows is already a hard kill** with no
graceful flush — a bounded but real corruption window (bounded by the periodic commit timer + default
`commitOnClose` only protecting *committed* state). Fixing `restart()` to signal-first closes this for
all callers, not just hang-recovery. Flag for 628 coordination.

## S7 (live/unit open-loop probe) — deliberately skipped, with justification

The plan's optional probe (stand up the real monitor, force `isHealthy()=false`, observe no restart) was
**not run**: the open loop is already *proven by direct source reading* — `KnowledgeServerBootstrap.checkHealth()`
(`:379-393`) transitions to DEGRADED and returns with no actuator call, and `WorkerSpawner.checkWorkerHealth`
restarts only on `!isAlive()`. A probe would re-confirm an unambiguous static fact, not reduce a real
unknown. (The *recovery path* I'd build is unexercised end-to-end, but that is implementation-time
verification, not a pre-implementation unknown.)

## Critical confidence rating for the remaining implementation work: **7 / 10**

Raised from a notional ~5 by this pass: the actuator seam exists, the lifecycle vocabulary absorbs the
terminal state with no new enum, the Brain reference is confirmed closed, and the graceful-commit
question is settled in the design's favor. Held below 8 by three honest residuals: (a) the
graceful-restart fix touches `restart()` shared by 4 callers — non-trivial regression surface; (b) the
628 corruption-on-force-kill dependency sits on an *unstarted* sibling tempdoc, so the boundary is
agreed in principle but not yet co-designed; (c) no live fault-injection has exercised a real
hang→graceful-restart→recover cycle on Windows, where process/IPC timing has historically surprised this
codebase — the standing in-process logic-seam tests will de-risk the decision logic, but the live
end-to-end path remains to be proven during implementation.

---

# Implementation outcome — Deliverable 4 (SHIPPED 2026-06-21)

## What shipped

The Worker's open observation→actuation loop is closed, conforming to the design (extend the existing
`Capability` seam + mirror the Brain's callback-to-capability bridge; no parallel framework).

- **`SupervisionDecision`** (new, `modules/app-services/.../worker/SupervisionDecision.java`) — the pure
  recovery-contract authority: `decide((processAlive, consecutiveUnhealthy, restartAttempts,
  lastStartKnown, msSinceLastStart), policy) → {NONE, RESTART_RESPAWN, RESTART_GRACEFUL, GIVE_UP}` with a
  full `Decision` (reset-budget, next-attempt, backoff). Death and hang feed one budget.
- **`SupervisionPolicy`** (new) — declared policy (cap 3, backoff 1/2/4s cap 30s, stability window from
  `KnowledgeServerConfig`, hang threshold 3). Constants are fixed defaults (like the pre-existing
  cap/backoff); env reads stay in the allowlisted `KnowledgeServerConfig` (guardrail-clean).
- **`SupervisionEvents`** (new) — the `onRecovering/onRecovered/onGaveUp` callback, mirroring the Brain's
  `goOfflineFromMaxCrashes`.
- **`WorkerSpawner`** — extracted `stopProcess(p, graceful)` (shared by `close()`/`restart()`/supervised
  restart); `restart()` now uses the graceful signal-bus path (fixes the Windows hard-kill for *all* 4
  `restart()` callers); `checkWorkerHealth`→`superviseTick` under one `ReentrantLock` consulting
  `SupervisionDecision`; new `recordHealthResult(healthy)` hang detector.
- **`KnowledgeServerBootstrap`** — installs the `SupervisionEvents` bridge to `WorkerCapability`
  (RECOVERING on restart, DEGRADED+`worker.restart_exhausted` on give-up); forwards each `checkHealth`
  poll into `recordHealthResult`.
- **`LifecycleReasonCode.WORKER_RESTART_EXHAUSTED`** (`worker.restart_exhausted`) + `StatusLifecycleHandler`
  surfaces it on the worker component when the capability carries it + `readinessNotice.ts` CAUSE_ROW
  wording. `RECOVERING` (previously unused by the Worker) marks in-flight restarts.

## Definition-of-done status

- ✅ **Fault-matrix with a runnable test per row** — `SupervisionDecisionTest` (11 tests): no-fault,
  below-threshold blip, clean-death→respawn, hang→graceful-restart, shared-budget, cap→give-up,
  hang-at-cap, stability-reset, no-known-start, backoff schedule, null-rejection. All green.
- ✅ **Named recovery-contract authority** — `SupervisionDecision` + `SupervisionPolicy`.
- ✅ **Hardened weak modes** — hang recovery (the headline gap); graceful-stop-before-force on Windows;
  terminal give-up state distinct from transient.
- ✅ **Dev-stack rows as smokes** — the live give-up smoke is documented below; the hang-via-suspend
  row stays a documented dev-stack smoke (Windows process-suspend is flaky; covered by unit tests).
- ✅ **Durable guard / logic-seam registration — DONE (gap-closure pass, see Deliverable 5).**
  `SupervisionDecision` is registered in `governance/logic-seams.v1.json` (`worker-supervision`),
  `:modules:app-services` carries `conventions.mutation` (PIT scoped to the seam only — zero blast
  radius), and the `test-efficacy` baseline is seeded at **88% mutation strength** (24/27 mutants killed
  by `SupervisionDecisionTest`). `check-logic-seams` green.

## Verification evidence

- **Unit/module:** `SupervisionDecisionTest` green; full `:modules:app-services:test` green (1567 tests);
  `:modules:app-api:test` (reason code) — only the pre-existing `StatusWireContractConformanceTest` 607
  drift fails (logged, unrelated); `check-readiness-reason-codes` gate green; ui-web `typecheck` green;
  all modules compile.
- **Live (dev stack from this worktree's dist):** a kill-loop on the worker PID drove 3 successful
  supervised restarts (new PIDs 22932→47432→43724 — the consolidated death-path budget) then the terminal
  give-up. `/api/health` → `lifecycle.state=LIFECYCLE_STATE_ERROR`, worker `reason_code=worker.restart_exhausted`;
  `/api/status` readiness envelope → `workerControlPlane.reasonCode=worker.restart_exhausted` (source
  `lifecycle_snapshot`) leading the `retrieval` composite. UI surfaced the worker-down state live
  (advisories: "Index Unavailable (worker)").
- **Scope-boundary note (honest):** the advisory/banner *headline wording* uses the condition/index
  mapping (`index.not_healthy`), not the new `worker.restart_exhausted` CAUSE_ROW string. That selection
  is 600/604's presentation authority — explicitly excluded from 627's scope ("Exclude user-facing/UI
  polish of the monitoring surfaces"). 627 owns *producing* the terminal state + wire code (confirmed on
  both `/api/health` and the readiness envelope); the wording is wired (gate-verified) for when 600's
  surfaces render the code.

## Known pre-existing failures on the base HEAD (NOT 627; logged to observations inbox)

1. `:ssotValidateExec` — `field-catalog.schema.json` requires `analyzer`; 58/68 fields in
   `SSOT/catalogs/fields.v1.json` lack it (mid-flight ADR-0043 migration). Blocks full `build`/`verify`.
2. `StatusWireContractConformanceTest` — `worker.visualExtraction.visualEnrichmentNeededCount` missing
   from `contracts/wire/status.proto` (commit 607 visual-extraction work).

Both predate and are independent of this change (my diff touches no SSOT catalog, analyzer, visual-
extraction record, or status.proto). The 627 diff itself introduces zero test failures.

## Follow-ups (not done here)

- 628 co-design: confirm the graceful-stop-attempt-then-force boundary and corruption-repair ownership.
- (Optional) an opt-in `ChaosSuiteTest` real-process hang row to complement the unit-level hang coverage.

---

# Gap-closure — Deliverable 5 (SHIPPED 2026-06-21)

A conceptual re-read of the implementation against the literal Definition-of-Done surfaced five
breadth gaps (the core thesis — close the Worker hang open-loop — was already shipped). All five are now
closed by **extending existing patterns**, no new heavy infrastructure:

1. **Brain contract is now declared, not scattered.** `BrainSupervisionPolicy` (new,
   `modules/app-inference/.../BrainSupervisionPolicy.java`) lifts `LlamaServerOps`'s crash/health
   constants (`MAX_CRASHES`, `CRASH_RECOVERY_DELAY_MS`, `CONSECUTIVE_FAILURES_BEFORE_RESTART`,
   `PERIODIC_HEALTH_INTERVAL_MS`, `HEALTH_CHECK_TIMEOUT_MS`) into a named record. **Zero behavior
   change**: the local constants now *derive* from `SUPERVISION_POLICY = defaults()` (still `static final`,
   same sysprop-at-class-load timing). A pure `BrainSupervisionDecision` was deliberately **not** extracted
   (trivial branch, orchestration-bound — negative ROI per investigation).
2. **The "single authority" is literalized as a declared register.** `governance/supervision-contract.v1.json`
   enumerates **both** processes × every fault mode (worker: clean-exit/hang/oom/zombie/partial-boot;
   brain: clean-exit/hang/oom/partial-boot/**external-adoption**) → detection / recovery / policy /
   terminal-state / guard. This is the "single authority rather than scattered logic" Goal 2 asked for,
   now covering external-adoption explicitly.
3. **Test-per-row is honest.** `SupervisionContractTest` fails the build if (a) a process's declared
   `policy` block drifts from the live `SupervisionPolicy`/`BrainSupervisionPolicy` defaults, (b) the
   matrix is incomplete, or (c) any row's guard doesn't resolve to a real test or an explicit sentinel
   (`dev-stack-smoke`/`audit-verdict`). Added rows: Worker OOM-is-process-death
   (`SupervisionDecisionTest`), Brain reach-cap → terminal give-up (`LlamaServerOpsCrashTelemetryTest`).
   Genuinely-hard rows (real OOM, Windows missing-DLL, process-suspend hang) are declared smokes — no
   silent gaps.
4. **Partial-boot hardened (Worker).** `WorkerSpawner.doRestart` now reaps a spawned-but-port-less orphan
   on `awaitPort` failure, so a partial-boot restart can't leave a port-less zombie holding the data-dir
   lock.
5. **Durable guard wired.** `SupervisionDecision` is a registered logic-seam; `app-services` carries
   `conventions.mutation` (PIT scoped to the one seam); baseline seeded at **88% mutation strength**
   (24/27 mutants killed). The decision logic can no longer silently rot.

**Verification:** `:modules:app-inference:test` + `:modules:app-services:test` full suites green;
`SupervisionContractTest` + extended `SupervisionDecisionTest` + Brain give-up test green;
`check-logic-seams` green (6 seams); `check-readiness-reason-codes` green; PIT `worker-supervision` 88%.
No user-visible change this pass (the terminal-state UI was browser-validated in Deliverable 4), so no
new browser validation required. The two pre-existing base-HEAD failures (`ssotValidateExec`,
`StatusWireContractConformanceTest`) remain unrelated; the 627 diff adds zero test failures.

**Post-review fixes (Deliverable 5b).** A critical review of Deliverable 5 found two real issues, both
fixed: (a) `SupervisionContractTest.brainPolicyMatchesCode` compared the register against
`BrainSupervisionPolicy.defaults()`, whose `healthCheckTimeoutMs` reads a sysprop — a wrong-reason
flake. Now it asserts the `DEFAULT_*` constants (the declared default contract); verified it still
passes with `-Djustsearch.inference.health_check_timeout_ms=180000` set. (b) Enabling
`conventions.mutation` on `app-services` added a *locked* `pitest` configuration but left
`modules/app-services/gradle.lockfile` stale (0 pitest refs vs the 15 the other mutation modules carry)
— the CI lock-currency check (`ci.yml`: `resolveAndLockAll` + fail-on-diff) would have failed. Regenerated
the lockfile (scoped to app-services; the Windows-CRLF churn on the other 37 lockfiles was restored to
LF). Re-verified `:modules:app-services:pitest` resolves and `check-logic-seams` is green.

---

# Future directions — what we could build on the 627 substrate (research, not committed work)

> **Re-baselined by the second ideation pass (Deliverable 7, at the foot).** This first pass predates
> Deliverable 6 (calm RECOVERING + recovery occurrences) — several items below shipped or partly shipped
> since. Read Deliverable 7 for the current what's-done / what's-open picture and the new findings the
> first pass missed.

Ideation pass (2026-06-21, documentation only — no code). The implemented substrate (pure
`SupervisionDecision`, declared policies + `supervision-contract.v1.json`, the
`SupervisionEvents`→`WorkerCapability` bridge, terminal give-up, restart budget) is a clean base. A
3-round external research pass grounded the ideas below in settled practice and filtered out what
doesn't fit a *local-first, no-users-yet, single-machine* app. Nothing here is required; all are viable;
ordered roughly by value/effort.

**Framing insight (research):** the loop we built is a textbook **MAPE-K** self-healing loop —
*Monitor* (process-watch + health-poll) → *Analyze* (`SupervisionDecision`) → *Execute* (restart/
give-up) over *Knowledge* (`SupervisionPolicy` + the register). "Observation-Actuation Closure" is that
loop named for the fault domain (IBM autonomic computing, 2001). This is the lens for everything below.

## A. Data layer (highest value — unlocks the UX + telemetry)
- **A1. Worker restart-history ring.** The Brain already has a bounded `FailureRecord` ring
  (`TransitionRunner`, 20 entries); the Worker has none. Add a symmetric bounded ring (timestamp, fault
  kind death/hang, attempt, outcome recovered/gave-up). Feeds A2 + B1 + forensics. Low effort, high
  leverage.
- **A2. OTel supervision metrics.** The app has native OTel (tempdoc 622). Emit `process.uptime` (a
  real OTel semantic-convention gauge — seconds since last restart) for Worker/Brain, plus
  `supervisor.restart_total`, `supervisor.give_up_total`, `supervisor.recovery_duration`. Conforms to
  conventions; turns recovery into a measurable signal. Low risk.

## B. New UX (the app has no users yet — free to explore; 600/604 own the surface, these are features for it)
- **B1. Reliability timeline.** Mirror Windows Reliability Monitor: a per-process timeline of
  crash / restart / recovery / give-up events in the System Health surface, fed by A1 + the Brain's ring.
  The single most user-legible feature — answers "why did search blink just now?".
- **B2. Terminal-state guidance + one-click diagnostics.** When the Worker gives up, show (i) *what
  still works* (graceful-degradation honesty), (ii) a "open diagnostics" button to the already-written
  `worker.log` + `crashes/hs_err` dumps, and (iii) a diagnostic id to quote. Desktop crash-UX best
  practice ("stuck → clear state + guidance + id").
- **B3. Transient "recovering…" indicator** that auto-clears (the `RECOVERING` state already exists,
  currently rendered as ERROR) — a calm, non-alarming signal distinct from the terminal state; avoids
  re-introducing the wedge 604 fixed.
- **B4. Manual controls** — "Restart worker" (the operation already exists) and "Retry recovery" to
  re-arm the budget from a terminal give-up (pairs with C3).

## C. Mechanism refinements (research-backed correctness/legibility)
- **C1. Recovery confirmation via a healthy streak (hysteresis/debounce).** Today `onRecovered` fires on
  mere port-discovery and the budget resets on a *single* healthy poll. Two-threshold hysteresis: declare
  recovered (and reset the budget) only after N consecutive healthy polls — flap-damping, and it stops
  claiming "recovered" before health is actually confirmed. (Aligns with the existing `GrpcCircuitBreaker`
  half-open notion.)
- **C2. Crash-loop fast-classification.** Distinguish "crashes immediately on boot" (broken model/config
  — won't self-heal, give up faster with a distinct terminal reason) from "crashed after a stable run"
  (transient — full budget). Borrowed from K8s CrashLoopBackOff. Avoids burning 3 restarts on an
  unfixable boot failure.
- **C3. A named "backing-off / crash-looping" state** (à la `CrashLoopBackOff`) distinct from
  `RECOVERING`, so B1/B3 can say "crash-looping, backing off" vs "recovering".
- **C4. Half-open auto-recovery from terminal give-up (optional).** Instead of a *permanent* give-up, a
  long-cooldown single probe-restart (circuit-breaker half-open) so a transient root cause that clears
  (e.g. disk-full freed) self-heals without an app restart. Trade-off vs. the simpler user-initiated
  retry (B4); for a desktop app the manual path may be the better UX. Present as a choice.
- *Considered, low value here:* **backoff jitter** (AWS) — matters for thundering herds; with one
  Worker + one Brain there's no herd, so near-N/A (marginal only if both back off on a shared cause).

## D. Polish / simplify (small)
- **D1. `SupervisionEvents` → multi-listener.** Make it a listener list so capability-bridge + history
  (A1) + telemetry (A2) can all subscribe cleanly, instead of one callback.
- **D2. Partial-boot double-count tidy-up** (the reaped orphan's death counts a second slot) — minor; a
  "reaped by me" flag would avoid it. Cosmetic.
- *Considered, rejected (AHA):* unifying `SupervisionPolicy` + `BrainSupervisionPolicy` behind one type —
  they have genuinely different fields/reasons-to-change; the shared *register* already gives the
  legibility without forcing a shared code type.

## E. Generalize the principle (record, do not build — premature otherwise)
- **E1. Observation-Actuation Closure as a checked invariant.** A lint/ArchUnit that flags any
  *recoverable-fault* `Capability` reaching `DEGRADED` with no actuator/supervisor listening (MAPE-K
  without the E). Today only the Worker needed it; if a third such capability appears, this gate earns
  its place. Named now, built later.
- **E2. The register-asserts-code-defaults pattern** (`supervision-contract.v1.json` +
  `SupervisionContractTest`) generalizes to other declared policies (timeouts, retry budgets). Worth
  reusing rather than re-inventing per policy.

**Suggested first slice if revisited:** A1 + A2 (data layer) → B1 + B2 (timeline + terminal guidance).
That delivers the most user-visible value, is low-risk, and reuses the Brain's existing ring + the app's
existing OTel and `crashes/`/`worker.log` artifacts. Sources: K8s CrashLoopBackOff (pod-lifecycle docs),
AWS "timeouts, retries, backoff with jitter", OpenTelemetry process-metrics semconv (`process.uptime`),
circuit-breaker half-open (Azure Architecture Center), monitoring hysteresis/debounce, IBM MAPE-K /
autonomic computing, Windows Reliability Monitor / crash-report UX.

---

# Long-term design — recovery observability (theorized, scope-matched)

The brainstorm above lists many viable ideas; this section distills the one **the implemented work
actually requires** and shows why almost all of the rest is *already built* or *correctly deferred*. The
design turned out **smaller** than the brainstorm, because investigation found the substrate already
exists and is already shared.

## The real present problem (narrowed)

The supervisor's recovery **events** — "restart attempt 2", "recovered", "gave up after 3" — are not
recorded as discrete events anywhere on the Worker side; only the resulting **capability state** flips
(RECOVERING/DEGRADED via `CapabilityHealthBridge` → `ConditionStore`). The Brain, by contrast, records
rich recovery history in a **private** `TransitionRunner` ring + a durable `NdjsonInferenceTransitionLog`.
So the MAPE-K loop we built has a thin, asymmetric **Knowledge** layer: state is observable, the *log of
what the supervisor did* is not (Worker) or is siloed (Brain). That asymmetry — not metrics, not UX
surfaces — is the genuine structural gap. (Metrics already flow via `IpcTelemetry`
`recordRestartSuccess`/`recordRestartLimitExceeded`/…; the timeline FE surface already exists.)

## The design: emit recovery milestones as occurrences onto the existing shared substrate

There is already **one unified health-event substrate** that both processes feed and the FE already
renders: `OccurrenceLog` (bounded one-shot-event ring) + `HealthEventChangeRegistry` (SSE) +
`HealthEventBody.LifecycleEvent` (an occurrence with an attributes map) + the `HealthSurface` /
`HealthLitView` timeline. The correct long-term shape is therefore an **extension, not new structure**:

- The supervisor's existing `SupervisionEvents` callback (`onRecovering` / `onRecovered` / `onGaveUp`),
  which the bootstrap already bridges to the capability, **additionally emits a `LifecycleEvent`
  occurrence** (`worker.restart-attempted`, `worker.recovered`, `worker.restart-exhausted`) with an
  attributes map (attempt, fault kind death/hang, backoff). These ride the **existing** `OccurrenceLog`
  → SSE → `HealthSurface` path. The "reliability timeline" UX (B1) is then delivered with **no new
  surface, no new stream, no new store** — the Worker simply becomes a producer of occurrences the FE
  already shows.
- This makes the Worker **symmetric with the Brain through ONE shared substrate**, rather than giving
  the Worker a private ring. The decision/policy/actuation layers stay per-process (correct, per AHA);
  only the **Knowledge/observability layer unifies onto the substrate that already exists.**
- **Conform to 575** (the merged observed-happening register): declare "supervision recovery" as a
  governed *happening* — canonical source (the supervisor / `OccurrenceLog`), kind (EVENT_STREAM
  occurrence + history policy), projections (the Health surface), liveness owner. This is the same
  "one canonical source, governed projections" discipline `supervision-contract.v1.json` already follows
  for the *policy*; 575 governs the *event stream*.

One small, genuinely-present correctness refinement rides along (C1): `onRecovered` currently fires on
mere port-discovery, before health is re-confirmed. Gate it behind a **confirmed healthy streak** (the
`recordHealthResult` consecutive-counter already exists) so "recovered" is emitted only when the Worker
is actually serving — hysteresis, matching the existing `GrpcCircuitBreaker` half-open notion.

## Deliberately NOT built (and why — scope discipline)

- **A durable recovery-event log** (a Worker NDJSON sidecar like the Brain's). The shared `OccurrenceLog`
  is in-memory (200-event ring, no replay) and *itself flags durable persistence as "V2 work."* For a
  local, no-users app, in-memory occurrences + the existing on-disk `crashes/` dumps + `worker.log`
  rotation are sufficient. If durable forensics are later needed, the right move is a **generic
  occurrence sidecar on the shared substrate** (so all occurrences persist, not a recovery-private one) —
  conforming to that V2 marker, not forking.
- **OTel `process.uptime` / new metric path** — supervision counts already exist via `IpcTelemetry`; a
  formal uptime gauge is a nice-to-have through the existing `LocalTelemetry` registry, not 622's *agent*
  telemetry. Not a present need.
- **Crash-loop fast-classification (C2), half-open auto-recovery (C4), manual controls (B4), the
  timeline surface itself** — speculative (no users to hit boot-crash loops; the surface already exists).
  Recorded in the brainstorm; not designed into structure.

# Reach — principle & where it conforms / is violated

This design is **not new** — it is an instance of a principle the codebase already names in **575/622**:
**one canonical source per fact/happening; every other representation is a governed projection, not a
fork.** A recovery event is a "happening"; its canonical home is the shared occurrence substrate, and the
FE timeline is a projection.

- **Conforms to:** the shared `Capability` seam (already), the `OccurrenceLog`/`HealthEventChangeRegistry`
  substrate (already fed by both processes), and 575's observed-happening discipline.
- **Existing violation worth naming (do NOT fix now):** the Brain's `TransitionRunner` ring +
  `NdjsonInferenceTransitionLog` is effectively a **private fork** of recovery-event history — it records
  the Brain's recovery milestones in a Brain-only place *and* (separately) flips the shared capability
  condition. Per the single-canonical-source principle, the Brain's recovery events should ideally also
  be occurrences on the shared substrate. **But:** its NDJSON sidecar provides *durable* forensics the
  shared substrate deliberately lacks (the "V2" gap), so it is not a pure fork — it fills a real hole.
  The honest call: **reconcile only if/when a durable shared occurrence log lands** (then the Brain's
  private NDJSON becomes redundant and should fold in). Until then it stays — recorded as a known
  divergence, not migrated.
- **The MAPE-K reading (candidate scope):** *the Knowledge layer of any self-healing loop belongs in the
  shared observability substrate, not in per-actuator silos.* Candidate scope: the Worker supervisor (this
  design), the Brain (the named divergence above), and any future self-healing capability. **Recognized,
  not generalized** — only the Worker requires the change now; building a generic "recovery-history
  framework" for one live instance + one tolerable divergence would be premature abstraction.

# Frontend / user-facing design (theorized from live inspection of the System Health surface)

I drove the dev stack and inspected the real surfaces (System Health `core.health-surface`, tabs
**System Health | Logs | Activity**) rather than judging from the design alone. The user-facing footprint
is real but **lands entirely on surfaces that already exist** — the design adds *content + tone*, not new
UI.

## Principle: the recovery loop should self-narrate — calm while recovering, loud only when it gives up

Three beats, each on an existing surface:

1. **While recovering — a calm transient, NOT an error (the key call I could only see live).** A
   supervised restart drives the capability to `RECOVERING`, which `LifecycleProjection` maps to
   **ERROR** — so a *routine* self-heal would flash a scary "Service degraded" / red verdict. The app
   already has the right treatment for "temporarily out, hold tight": 604's **"Reconnecting… — holding
   last-known values"** banner + dimmed "Last known" stat cards + a calm amber badge. The Worker
   `RECOVERING` transient should render with **that** calm, hopeful tone ("Knowledge server restarting —
   holding last-known results"), not the terminal tone. *Authority note:* recovering-vs-error verdict
   tone lives in 600's `verdict.ts` / `readinessNotice.ts` — a coordinate-with-600 refinement, not a
   parallel banner.
2. **When it recovers — a positive event.** Emit a `worker.recovered` occurrence into the existing
   **RECENT EVENTS** stream (INFO/positive tone). That stream is currently all warnings/errors; a
   success-toned "Knowledge server recovered and is serving again" entry is what makes self-healing
   *visible and trustworthy* — the user sees the system fix itself.
3. **When it gives up — the strongest, action-bearing terminal state.** On `worker.restart_exhausted`
   (already wired to the readiness notice): (a) the verdict badge shows the terminal degradation, (b) the
   **"What you can do right now"** panel honestly states retrieval/search is affected (the graceful-
   degradation panel already exists), (c) a context **Quick Action** surfaces — *Restart* and *Export
   diagnostics* actions already exist on this surface and should appear in this state, and (d) the
   terminal RECENT EVENTS row points to the **Logs** tab for raw detail.

## How it rides existing surfaces (no new UI)

- **RECENT EVENTS** renders occurrences via `<jf-health-event>` (row = severity color-bar + i18n title +
  detail + subject tag + relative time). Recovery milestones (`worker.restart-attempted` /
  `worker.recovered` / `worker.restart-exhausted`) emit exactly like the existing `worker.job.failed` /
  `worker.job.retry-scheduled` occurrences (`WorkerSnapshotTap` + `OccurrenceLog` + `OCCURRENCE_APPENDED`),
  with new catalog IDs + i18n wording. The `HealthEventEmitCoverageTest` gate already requires every
  catalog ID to have an emit site — the contract stays enforced.
- **RECOVERING current-state** is a *condition* (already flows via `CapabilityHealthBridge` →
  `ConditionStore`); the work is the *verdict tone* (beat 1), in 600's authority.
- **Logs tab** already streams head+worker diagnostics live (the worker's crash/restart log lines land
  there); the terminal "see diagnostics" guidance just points here — no new diagnostics pane.
- **Quick Actions** (Restart / Export diagnostics) and the **degradation panel** already exist; the design
  is *when* they surface, not new controls.

## Scope discipline (FE)

The FE design is **content + one tone decision on existing surfaces**: new occurrence catalog IDs + i18n
wording (the backend-emit half is the recovery-observability design above), plus the coordinate-with-600
refinement so `RECOVERING` reads as a calm transient rather than an error. Deliberately **not** designed:
a bespoke recovery-timeline widget (RECENT EVENTS is it), a new diagnostics pane (the Logs tab is it), or
new manual buttons (Restart/Export already exist). Per the tempdoc's own scope note, *whole-screen UI
polish of the monitoring surfaces stays 600/604's* — this design specifies only the recovery *content*
those surfaces carry and the one tone call that keeps a routine self-heal from looking like a failure.

## Confidence-building findings (pre-implementation — design verified against source + live)

A read-only investigation + one live experiment confirmed/refined the design's load-bearing assumptions
before any implementation. Verdicts, with `file:line` evidence:

- **U4 — "recovering should read as a calm transient" — CONFIRMED + quantified + scope clarified.** Live:
  a single routine worker kill exposes `lifecycle=ERROR`, `worker=ERROR/worker.spawn.failed` on
  `/api/health` for **~15 seconds** (not a flash — gated by the 10s health-poll cadence + worker warmup),
  then returns to READY. So a *routine* self-heal shows "Service degraded" with the **misleading
  `worker.spawn.failed` reason** for ~15s — worth fixing, and more than a cosmetic blip. **Scope (refined):
  it is NOT a pure-FE one-liner.** `verdict.ts:191/245` drives the calm "Reconnecting… holding last-known"
  tone off `ConnectionPhase.stale` only; capability `RECOVERING` collapses to ERROR via
  `LifecycleProjection.java:36` and `StatusLifecycleHandler` maps `DEGRADED,RECOVERING → worker.spawn.failed`
  — indistinguishable from a real spawn failure. Fix = **(a)** backend: split the `RECOVERING` case to a
  distinct reason code (new `WORKER_RESTARTING`/`worker.recovering`); **(b)** FE `verdict.ts`: treat that
  reason as a `transitioning/busy` (calm) verdict; **(c)** the new code threads through `LifecycleReasonCode`
  + `readinessNotice` CAUSE_ROWS + the `check-readiness-reason-codes` gate. Small, but spans backend+FE+gate
  and is co-owned with 600.
- **U1 — emit reachability — RESOLVED (refined site).** Recovery occurrences are best emitted from
  `CapabilityHealthBridge` (it already observes the capability transitions `SupervisionEvents` drives), by
  injecting `OccurrenceLog` alongside the `ConditionStore`/`changeRegistry` it already holds — **not** from
  `WorkerSnapshotTap` (that's snapshot-poll-driven) nor a new bridge. Small wiring (one added param). The
  occurrence construction API is the `HealthEvent(id, instant, source, Severity, Optional<i18nKey>,
  LifecycleEvent(attrs))` + `OccurrenceLog.append` + `changes.broadcast(OCCURRENCE_APPENDED)` template
  (`WorkerSnapshotTap.java:401-410`).
- **U2/U5 — catalog + coverage gate + wording — RESOLVED, low ceremony.** A new occurrence ID touches ~4
  files: `HealthEventEmitCoverageTest.CANONICAL_IDS`, the emitter's `emittableIds()` set, the emit call,
  and `messages/health-events.en.properties` (wording). The coverage gate is bidirectional (every ID has
  an emit site and vice versa). No separate i18n-correspondence gate (FE falls back to the raw key).
- **U3 — event-vs-condition double-render — REAL, manageable.** The terminal give-up already emits a
  CONDITION (`worker.capability` / reason `WorkerRestartExhausted`) via `CapabilityHealthBridge`; a
  milestone OCCURRENCE would be a separate RECENT-EVENTS row. Design call: emit the *milestones*
  (`restart-attempted`/`recovered`/`gave-up`) as occurrences (the *events*), keep the persistent *state*
  as the condition, and word them distinctly (event "gave up restarting just now" vs condition
  "unavailable") — the same event-vs-condition split that already exists (`worker.job.failed` occurrence
  vs `schema.blocked` condition). Avoid emitting a `restart-exhausted` occurrence that merely restates the
  condition.
- **U6 — actions + healthy-streak — RESOLVED, good news.** Quick Actions (Restart / Export-diagnostics) are
  catalog-driven and always rendered (availability-gated) — already present, no new controls. A
  terminal-state action surfaces via the existing `ConditionRecoveryIndex` (declare a recovery op on the
  condition) → the "Recommended Actions" section. The C1 healthy-streak hook is small: add a
  `consecutiveHealthy` counter, relocate the `onRecovered` fire from `doRestart` (`WorkerSpawner.java:760`,
  fires on port-discovery) into `recordHealthResult` gated on a streak threshold (default 1 = unchanged).

**Critical confidence rating for the remaining (observability + FE) work: 8 / 10.** Everything is
well-precedented and the riskiest assumption (U4) is confirmed *and* tractable with a now-precise scope.
Held below 9 by two honest residuals: (a) the calm-`RECOVERING` tone threads a new reason code across
backend + FE `verdict.ts` + the reason-code gate and is co-owned with 600 (coordination, not just code);
(b) the event-vs-condition split (U3) needs careful wording/dedup so the terminal state doesn't render
twice. Neither is a structural unknown — both are understood, bounded edits.

---

# Recovery observability + frontend — Deliverable 6 (SHIPPED 2026-06-21)

## What shipped (content + one tone code on existing surfaces — no new UI)

- **Calm RECOVERING tone.** New `LifecycleReasonCode.WORKER_RECOVERING("worker.recovering")`;
  `StatusLifecycleHandler` splits the `DEGRADED,RECOVERING` case so RECOVERING surfaces a distinct
  `worker.recovering` reason at DEGRADED (not ERROR/`spawn.failed`); `verdict.ts` promotes
  `reasonCodes.includes('worker.recovering')` to the existing calm `transitioning`/`worker-restart`
  verdict ("Restarting…"); `readinessNotice` CAUSE_ROWS adds the `info` row (reason-code gate satisfied).
- **Recovery-event occurrences.** `CapabilityHealthBridge` (now injected with `OccurrenceLog`) emits
  `worker.restart-attempted` (→RECOVERING) and `worker.recovered` (RECOVERING→READY) into the existing
  RECENT EVENTS stream; restart-attempted fires once per episode (same-state RECOVERING is a no-op); the
  terminal give-up emits no occurrence (the existing `worker.capability` condition covers it). Registered
  in `WorkerSnapshotTap`/bridge `emittableIds()` + `HealthEventEmitCoverageTest` (29→31) + wording in
  `health-events.en.properties`. (575 register needs no per-occurrence entry — coarse `health-conditions`
  concept covers it.)

## Verification

- **Unit/gates:** `verdict.test.ts` (+2: worker.recovering→transitioning; spawn.failed stays
  degraded/error), `CapabilityHealthBridgeRecoveryTest` (+emit on transitions, terminal emits none),
  `HealthEventEmitCoverageTest` (31). Full `:modules:app-services:test` + `:modules:ui:test` green;
  `check-readiness-reason-codes` green; FE typecheck + verdict test green.
- **Live wire:** killing the worker once, `/api/health` shows `worker=DEGRADED/worker.recovering` (was
  `ERROR/worker.spawn.failed`); `/api/status` retrieval composite carries `worker.recovering` +
  `workerControlPlane.reasonCode=worker.recovering` — exactly the verdict's input.
- **Live browser (System Health):** the RECENT EVENTS stream rendered **"The knowledge server recovered
  and is serving again."** (the `worker.recovered` occurrence + its wording) and the restart was narrated;
  the UI stayed **calm** during recovery ("Reconnecting… — holding last-known values", not a red "Service
  degraded"). The recovery loop self-narrates in the real UI.

## Honest validation notes

- **The live badge showed "Reconnecting…" not "Restarting…".** Because inference was **offline** in the
  test session, the FE connection phase goes **channel-stale**, and `computeStability`'s channel-stale
  transient **dominates the verdict cascade ahead of** the readiness-based `worker.recovering` check. Both
  are *calm* (the design goal — no alarming "Service degraded" — is met either way). The
  `worker.recovering → "Restarting…"` path is proven by the verdict unit test + the live wire; the
  *inference-online* live case (where "Restarting…" would show) was unreachable — `ai_activate` failed
  (cuda12 GPU variant not installed; GPU staging is Install-AI's domain, out of dev-stack scope).
- **Minor redundancy at the restart *start*:** both the pre-existing `worker.capability` condition ("…
  worker process died; restarting", rendered with its raw id) and the new `worker.restart-attempted`
  occurrence narrate the start; the *recovered* end is uniquely the occurrence. The condition's raw-id
  rendering is 600/604 i18n territory. Acceptable; logged as a polish note, not changed (scope).
- **Give-up terminal** was not re-induced this round — it is unchanged (the `DEGRADED`+restart_exhausted
  mapping is untouched) and was browser-validated in Deliverable 4.

## Deferred (recorded, not built — scope discipline)
- **C1 healthy-streak recovery confirmation.** Recovery already requires a healthy gRPC poll before
  READY, and the window is already ~15s (10s poll cadence); requiring an extra consecutive-healthy poll
  would *slow* the "recovered" signal for marginal flap-damping no user hits. Not worth the worse UX.

---

# Future directions — second ideation pass (Deliverable 7, 2026-06-21, documentation only)

> A fresh research pass *after* Deliverable 6 shipped. Two parallel research threads: (1) a source map of
> the now-shipped substrate's real extension/simplification seams (`file:line` below), and (2) a web pass
> on angles the first brainstorm was thin on — desktop crash-UX (Chrome/VS Code/macOS), cross-session
> persistence, end-user reliability-signal design, and which SRE signals translate to a single machine.
> Output is documentation only. Each idea ends with a fit verdict; scope discipline = **record, don't build.**

## Re-baseline — what the first brainstorm (A–E above) proposed vs. what is now true

| First-pass item | Status now | Evidence |
|---|---|---|
| **B3** transient "recovering…" indicator (calm, not error) | **SHIPPED** (Deliverable 6) | `verdict.ts` promotes `worker.recovering` → calm `transitioning`; `WORKER_RECOVERING` reason code |
| **B1** reliability timeline (recovery events visible) | **Content shipped, widget not** | recovery milestones now emit to RECENT EVENTS (`CapabilityHealthBridge`); no dedicated per-process timeline widget, no cross-session history |
| **A2** OTel `process.uptime` / uptime surfacing | **Partly already exists** | worker `uptimeMs` is on the wire and already rendered — `HealthSurface.ts:981` via `formatUptime` (`:82`). What's missing is a *supervisor-tracked* "since last restart" gauge, not raw uptime. **See the corrective below — do NOT add a reliability %.** |
| **A1** Worker restart-history ring | **Not built; confirmed absent** | Worker keeps only scalars (`restartCount`, `consecutiveUnhealthy`, `lastSuccessfulStartTime` — `WorkerSpawner.java`); only the Brain has a 20-entry `FailureRecord` ring (`TransitionRunner`). Adding a Worker ring is net-new. |
| **D1** `SupervisionEvents` → multi-listener | **Still single-callback** | confirmed single-callback interface; wired inline in `KnowledgeServerBootstrap.start()`. Make it a list only when a 3rd subscriber actually appears (history + telemetry). |

## New findings the first pass missed (these are the value of this round)

**★ N1 — The Head-death blind spot (highest-value new idea; cheap; confirmed absent).** The in-process
supervisor can recover Body and Brain, but it *structurally cannot observe its own Head process dying* —
if the whole app crashes, nothing is left running to narrate it. Mature desktop apps solve this with a
**clean-shutdown sentinel**: Firefox/Chrome write a "clean exit" marker on graceful shutdown; a *missing*
marker on next launch ⇒ "the previous session ended unexpectedly" (drives the restore-session prompt).
Verified absent here (grep for clean-shutdown/unclean/previous-session markers found only pause/resume
migration + runtime-manifest, no session-crash sentinel). **Minimal form:** one file written on graceful
Head exit (a shutdown hook already exists — `IndexerWorker.java:106-117` is the Worker analog), checked
on boot; if missing, emit a calm `head.recovered-from-unclean-shutdown` occurrence onto the *existing*
RECENT EVENTS substrate. No new store, no telemetry. **Fit: yes — the one failure mode the shipped
supervisor cannot see, closeable on substrate that already exists.** Sources: Firefox/Chrome session-restore
crash sentinel.

**N2 — Recovery occurrences carry no forensic detail (cheap polish, refines A1).** `CapabilityHealthBridge`
emits the recovery occurrences with `LifecycleEvent.empty()` — no `attempt#`, no fault-kind (death vs
hang), no backoff. The `SupervisionDecision` already *has* these values at the decision site. Carrying
them as the occurrence's attributes map (the `LifecycleEvent` body already supports attributes — that's
how `worker.job.*` occurrences work) makes RECENT EVENTS answer "*why* did search blink — 2nd restart
after a hang" instead of just "restarting". **Fit: yes — small, extends a clean seam, no new structure.**
This is the *useful, bounded* slice of A1 (the full per-process ring is heavier and lower-value given the
occurrences already exist).

**N3 — De-dupe + escalate the narration tone (refines C3).** VS Code's single most-complained-about
recovery behaviour is *re-nagging* the same crash on every launch; macOS escalates its dialog verb
(Reopen → Try Again) on repeated crashes. Translation: (a) don't re-narrate a recovery once it has
settled — the shipped "recovered and serving again" closer is already the right shape; (b) on *repeated
identical* crashes within a window, escalate the message tone (calm "restarting…" → "this keeps
happening" → terminal "gave up"), rather than emitting the same calm line N times. The restart budget /
stability window already tracks the repeat count needed to drive this. **Fit: maybe — a wording/tone
ladder, co-owned with 600's verdict authority; low effort, real legibility win.** Sources: VS Code #79782/#80165,
macOS crash-dialog escalation.

**N4 — CORRECTIVE: do NOT surface a reliability %/MTBF/health-score to the user.** The first pass flirted
with uptime/reliability metrics (A2). End-user reliability-signal research is firm: on a single machine
the sample size is 1, so an "87% reliable this week" number is meaningless and reads as *broken* — it
manufactures anxiety. What *does* build trust (status-page guidance): visible, **resolved** events framed
as **resilience** ("recovered automatically — 2× this week"), never hidden perfection and never a raw
failure count. So: keep the raw "running for X" already shown; a resilience-framed "self-healed N times"
line is acceptable; a percentage/score/MTBF gauge is an anti-pattern. **Fit: this *removes* a tempting
wrong turn.** Sources: status-page best-practice (StatusDrop, UptimeRobot), NN/G indicators-vs-notifications.

**N5 — Signal selection for any future supervision telemetry (filters the SRE temptation).** Of the SRE
golden signals, only **errors** (worker RPC failures/crashes — already the supervisor's input),
**latency** (search-query duration — a *leading* indicator of a hang before it crashes), and
**saturation** (VRAM/RAM pressure — which matters *more* locally than in cloud because there's no
autoscaler) translate to one machine. **Traffic/RPS, SLOs, error-budgets, P99 percentile dashboards are
cloud/fleet noise at n=1.** Saturation is the one genuinely-new signal worth eventually wiring, because it
enables **predictive restart** (restart a saturating worker *before* it OOM-crashes) — but that risks
false-positive restarts, so it is an explicit **v2**, not now. **Fit: errors/latency already covered;
saturation-predictive-restart = recorded v2; the rest = N/A.** Sources: RED vs USE vs four-golden-signals,
self-healing-observability literature.

**N6 — Keep crash history *out* of the main view.** Every mature app (Chrome `chrome://crashes`, VS Code
logs) buries crash history behind a "details" affordance; the primary surface shows only *current state +
recovery action*. Design constraint for any future B1 timeline: it is a drill-down, not a default panel.
**Fit: design guidance, not a build item.** Sources: Chrome/VS Code crash-history placement.

## The severity-tier mapping (validates the shipped 3-beat design)

End-user notification research (NN/G; Carbon; status-page severity tiers) maps cleanly onto the three
recovery beats already shipped, confirming the design is well-precedented:

- **auto-recovery in flight** → *passive indicator* ("good to know") — the calm "restarting…" transient.
- **prolonged recovery** → *persistent but calm* ("investigate later") — keep the status live, don't freeze.
- **terminal give-up** → the *one* state allowed to be intrusive/actionable ("act now") — manual restart +
  diagnostics. This is also the cloud "manual escalation path when auto-remediation fails" pattern.

## Re-prioritized "if revisited" slice (supersedes the first pass's A1+A2→B1+B2)

Cheapest-highest-value, all extending shipped seams, all documentation-to-code-small:
1. **N1 clean-shutdown sentinel** — closes the Head blind spot on existing RECENT EVENTS substrate.
2. **N2 occurrence attributes** — forensic detail (attempt#, fault-kind) on the already-emitted occurrences.
3. **N3 de-dupe + escalating tone** — legibility on repeated crashes (co-own with 600).

Deferred with reason: cross-session durable occurrence persistence (do it **generically** on the shared
`OccurrenceLog` "V2" seam — not a recovery-private NDJSON; that would re-fork the Brain divergence already
named above); **predictive saturation restart** (v2 — false-positive risk); a dedicated timeline *widget*
(RECENT EVENTS already carries the content — N6 says keep history a drill-down); `SupervisionEvents`
multi-listener (D1 — build only when a 3rd subscriber appears).

## MAPE-K reading (consistent with the first pass)

These findings sharpen the same loop the first pass named: N1 extends *Monitor* to the un-monitored Head;
N2 enriches *Knowledge* (the occurrence record); N3/N6 are *Knowledge*-presentation discipline; N5's
saturation-predictive-restart would extend *Analyze* from "react to death" to "act on degradation." N4 is
the guardrail on how *Knowledge* is shown to a human. Nothing here argues for a generalized supervisor
framework — still exactly one open loop's worth of real work, now plus one newly-found blind spot (the Head).

---

# Long-term design — closing the supervisor's own blind spot (Deliverable 8, 2026-06-21, design theorization)

> Documentation-only design pass over the Deliverable-7 remaining slice (N1 Head-death, N2 forensic
> attributes, N3 escalating tone). **Investigation-first paid off heavily**: the "abnormal-shutdown
> sentinel" the research suggested *building* turns out to already exist as on-disk state — so the design
> is an *extension that adds one missing sink*, not new structure. General-level, not implementation.

## Restating the problem at the right tier

The shipped 627 work closes the Worker's in-life open loop (hang → restart) and narrates recovery as
occurrences (Deliverable 6). The Deliverable-7 pass found one *structural* gap remaining: **a process
cannot observe its own death, and the Head is the supervisor.** There are three tiers of fault
observation; 627 has closed two:

| Tier | Fault | Observer → sink | Status |
|---|---|---|---|
| Process, in-life | Worker hang/death | `SupervisionDecision` → `restart()` actuator | **shipped** |
| Event | recovery milestones | `SupervisionEvents` → `OccurrenceLog` → RECENT EVENTS | **shipped (D6)** |
| **Session, cross-life** | **Head / whole-app died last session** | **observed, but dead-ends at a log line** | **the open gap (N1)** |

## What already exists (the investigate-first payoff)

The "did the previous session crash?" observation is **already computed at every boot** — it is just
*unsinked*:

- **`RuntimeManifestPublisher` (tempdoc 501)** writes `<dataDir>/runtime/manifest.json` carrying
  `{pid, instanceId, startedAt, lifecycle}` and **deletes it on graceful `close()`** (the Head shutdown
  hook — `HeadlessApp.java:719-787`). A crash (SIGKILL/OOM/whole-app death) **leaves the manifest
  behind**. Per-instance history lives under `runtime/instances/<id>/` with a `start.log` whose final
  line on clean exit is literally `"publisher-close (clean shutdown)"`. **So the manifest already *is* a
  clean-shutdown sentinel** — present-with-a-dead-PID ⇒ the predecessor crashed.
- **`AppInstanceLock`** (`<dataDir>/app.lock`, `AppInstanceLock.java`) carries PID + start-instant
  metadata and `tryRecoverStaleLock()` already detects a dead/reused predecessor PID at boot — but its
  only sink is `log.warn(...)` + silent file-delete. **Crucial nuance:** `close()` *releases* the OS lock
  but **never deletes `app.lock`**, and the OS lock evaporates on any death — so the lock file is **not** a
  clean-vs-crash discriminator. The **leftover manifest with a dead PID is the reliable signal.**

This is the **same Observation-Actuation-Closure violation as the core 627 thesis, one tier up**: a
signal that indicates a recovered fault (the predecessor crashed; we are now back) terminates at a status
side-effect (a log line) instead of a sink a surface can read.

## The design (extend the manifest; add the one missing sink — do NOT build a sentinel)

1. **A pure boot-time predecessor classifier.** Given the leftover manifest (if any) + `ProcessHandle.of(pid)`
   liveness, classify the previous session as `NONE` (first boot / clean — no leftover) / `ABNORMAL`
   (leftover manifest, dead PID). A pure function over on-disk state — the **session-tier analogue of
   `SupervisionDecision`** (the same "pure classifier behind a named seam" shape, reusable as a logic-seam).
   Natural owner: **`RuntimeManifestPublisher`** — it already runs at boot, owns `manifestPath`, and
   already reads/prunes `runtime/`. It read-and-classifies the leftover **before `publishHead` overwrites
   it**, and exposes the verdict as a getter.
2. **One sink, on the substrate that already exists.** When `ABNORMAL`, once the `OccurrenceLog` substrate
   is up, the Head emits a calm INFO occurrence (`app.recovered-from-unclean-shutdown`) onto
   `OccurrenceLog` → SSE → RECENT EVENTS — **the exact path `worker.recovered` already uses.** No new
   store, no new surface, no new stream.
3. **Honest, resilience-framed wording (N4 + the 628 boundary).** "JustSearch didn't shut down cleanly
   last time; the app has resumed." **Not** "your data is guaranteed intact" — index-integrity is **628's**
   (durability) authority, not 627's. 627 surfaces the *event*; 628 owns the *verification*. **Declared
   handoff:** if/when 628 lands an integrity check, its result becomes an *attribute* on this same
   occurrence (N2's mechanism), rather than a second event.
4. **One mechanism per concern.** The manifest is the single freshness/liveness signal — its own class
   doc already forbids a second lock for exactly this reason ("two locks would violate the design's own
   closure rule"). The classifier consumes the manifest; it introduces no parallel marker file.

## N2 — close the Knowledge data path (forensic attributes)

The recovery occurrences emit `LifecycleEvent.empty()`, yet `SupervisionDecision` already computes
`attempt#`, fault-kind (death vs hang), and backoff at the decision site. **Widen the `SupervisionEvents`
callbacks from a bare reason `String` to a small immutable `RecoveryContext`**, and let
`CapabilityHealthBridge` place it in the `LifecycleEvent` *attributes map* (the `worker.job.*` occurrences
already carry attributes this way). Pure extension of a clean seam — structurally the **same move as 600
"carry the reason code to the wire,"** applied to the occurrence body.

## N3 — hand the presentation tier its signal; do not build the tone ladder here

The repeat-count within the stability window is **already** in `SupervisionDecision`'s input
(`restartAttempts`). 627's job is to **surface** it (via N2's attribute); the de-dupe + escalating tone
ladder (calm → "this keeps happening" → gave-up) is **600's `verdict.ts` authority**, explicitly outside
627's scope ("Exclude user-facing/UI polish of the monitoring surfaces"). So N3 reduces to "ensure
attempt#/repeat-count rides the wire," then coordinate the ladder with 600. **Build no FE here.**

## Scope discipline — explicitly NOT built (structure the problem does not require)

- **No out-of-process watchdog** (a 4th process). The children already suicide-pact on Head death
  (`MmfWorkerSignalBus`); an external watchdog whose only job is *narration* is over-structure for a
  single-machine app — the successor-detection path covers it for free.
- **No durable recovery-event log.** If cross-session forensics are later wanted, do it **generically on
  the shared `OccurrenceLog` "V2" persistence seam**, not a recovery-private NDJSON — a private one would
  re-fork the Brain `TransitionRunner` divergence already named in the Reach section.
- **No predictive/saturation restart** (v2 — false-positive risk), **no reliability %/score** (N4
  anti-pattern), **no `SupervisionEvents` multi-listener** until a third subscriber actually exists (D1).

---

# Reach — is this design an instance of a wider principle?

## It conforms to an already-named principle (do not coin): Observation-Actuation Closure, at the session tier

N1 is the **same shape as the shipped core fix**: an observation of a recoverable/recovered fault that
dead-ends at a status side-effect (here a `log.warn` + silent file-delete) instead of reaching a sink a
surface can read. The Worker-hang fix closed this at the *process* tier; this design closes it at the
*session* tier. N2 is the same "carry the evidence to the sink" the 600 work already embodies. **Conform
to the existing principle; introduce no parallel concept.**

## The recurring shape worth naming plainly

> **A process cannot observe its own death; the closure for the top-level supervisor is therefore a
> *peer* or a *successor*. The system already runs two such out-of-band detectors of the Head's death —
> but both exist only for *cleanup*, so each is an *unsinked observation*.**

The two pre-existing detectors:

1. **Peer / in-life:** the Worker's MMF heartbeat suicide-pact — the Worker sees the Head's heartbeat go
   stale and self-exits. Sink = self-cleanup.
2. **Successor / cross-life:** the leftover-manifest (+ stale-lock) detection at the *next* boot.
   Sink = `log.warn` + delete.

Neither narrates. **Candidate scope = exactly those two detectors. Existing violation = both** (cleanup-only
sinks). This is the same family as the **Brain's `TransitionRunner` private NDJSON ring** already named in
the earlier Reach section (a Knowledge-layer signal living off the shared substrate).

## Recognize, do not build the generalized structure

There is exactly **one** narration consumer worth adding: the successor boot-occurrence (the design
above). The peer/suicide path **cannot** narrate — when the Head dies the Worker dies too, and that death
is already covered by the successor's manifest signal — so a generalized "supervisor-liveness framework"
would be structure for cases that do not exist. **Name the principle, add the single missing sink, stop**
— the same recognize-vs-build separation 627 already applied to the Worker open loop. If a future
top-level component gains an independent failure mode that a live peer *could* narrate, *that* is when the
shared structure earns its place.

---

# Implementation outcome — Deliverable 9 (SHIPPED 2026-06-21)

The Deliverable-8 design (N1 Head-crash narration, N2 forensic attributes, N3 surfacing) is implemented,
conforming to the design (extend existing seams; no new framework). Two real bugs were caught **by live
validation** and fixed (recorded below — they are the value of the live pass).

## What shipped

- **N1 — Head-crash narration (Observation-Actuation Closure at the session tier).**
  - `RuntimeManifestPublisher.classifyPreviousShutdown(...)` (new, package-private + pure, injected
    PID-start probe so all branches are unit-testable) classifies the *previous* session at boot from the
    leftover `<dataDir>/runtime/manifest.json`: reads only `pid`/`startedAt` via a **JSON tree** (NOT a
    full `readValue` — see Bug B), flags unclean when the PID is **dead OR alive-but-start-mismatch**
    (PID-reuse aware, mirroring `AppInstanceLock.tryRecoverStaleLock`). Surfaced via
    `detectedUncleanPreviousShutdown()` / `previousInstancePid()`.
  - `BootRecoveryEmitter` (new, `app-services/.../observability/health/`) emits the calm INFO occurrence
    `head.unclean-shutdown-recovered` (i18n + `emittableIds()` for the coverage gate). `HeadlessApp` glues
    the verdict → emitter after `buildApi()` (substrate up). Wording is resilience-framed and does **not**
    claim index integrity (628's authority).
- **N2 — forensic attributes.** New `RecoveryContext(attempt, faultKind, backoffMs)`; `SupervisionEvents.onRecovering`
  widened to carry it; `WorkerSpawner` fills it from the `SupervisionDecision` (faultKind = death vs hang
  from the action); `WorkerCapability` parks the context; `CapabilityHealthBridge` attaches it to the
  occurrence (`worker.restart-attempted` → `{attempt, faultKind, backoffMs}`, `worker.recovered` →
  `{recoveredAfterAttempts}`). **`HeadAssembly` mirror bridge forwards the context across the two capability
  instances** (see Bug A).
- **N3 — escalating tone / repeat-count.** Satisfied by N2: the `attempt` attribute now rides the wire;
  the de-dupe (restart-attempted fires once per episode) was already shipped (D6). The tone *ladder* is
  600's `verdict.ts` authority — handed off, not built here (per 627 scope).

## Verification

- **Unit / gates (all green):** `RuntimeManifestPublisherShutdownClassifyTest` (dead→unclean,
  reused→unclean, same-live→clean, own-pid→clean, absent→clean, **with a `reachability`-bearing fixture** —
  the Bug-B regression), `BootRecoveryEmitterTest`, extended `CapabilityHealthBridgeRecoveryTest`
  (attributes present), `HealthEventEmitCoverageTest` (32), `WorkerCapabilityBridgeTest` (mirror unchanged),
  `SupervisionDecisionTest`/`SupervisionContractTest`. Full `:modules:app-services:test` + `:modules:ui:test`
  green; FE `typecheck` green; `check-readiness-reason-codes` green. The lone `:app-api` failure is the
  **pre-existing** `StatusWireContractConformanceTest` 607 wire-drift (`visualEnrichmentNeededCount` missing
  from `status.proto`) — independent of this diff (logged earlier; my diff touches no proto/visual surface).
- **N2 live (decisive) — validated end-to-end on the real running stack.** Killing the worker drove a
  supervised restart; `/api/health/events/stream` carried, from the live Head:
  `worker.restart-attempted` → `attributes:{attempt:1, faultKind:"death", backoffMs:1000}` and
  `worker.recovered` → `attributes:{recoveredAfterAttempts:1}`. The supervised-recovery loop self-narrates
  **with forensic detail** through the real substrate.
- **N1 live — classifier proven on the real boot path; full live narration blocked by the dev-runner
  harness (root-caused, not a product defect).** The Head boot executes `classifyPreviousShutdown` at the
  real `manifestPath` (diagnostic-confirmed). It returns clean under the dev-runner because
  **`scripts/dev/dev-runner.cjs:1165` deletes `<dataDir>/runtime/manifest.json` on every start** (to avoid
  reading a stale port) — so the Head never sees a leftover. In production (Tauri/headless launch, user
  relaunch after a crash) nothing deletes it, so the crashed predecessor's manifest persists and the new
  Head narrates. The dev-runner module is cached in the MCP server (a live edit to disable the unlink does
  not take effect), and the dev Head serves no SPA at root (the FE is Vite-served), so the dev-runner path
  cannot reproduce N1 in-browser. N1's occurrence rides the **same** OccurrenceLog→SSE pipeline that N2
  validated live, and the classifier+emitter are deterministically unit-tested.

## Two bugs caught by live validation (the reason the live pass mattered)

- **Bug A — N2 attributes empty live (two-capability mirror).** The supervisor sets the recovery context
  on `KnowledgeServerBootstrap`'s `WorkerCapability` (`ksCap`), but `CapabilityHealthBridge` listens to
  `HeadAssembly`'s mirror (`localCap`); the mirror listener (`HeadAssembly:798`) forwarded *health* but not
  the *context*, so the bridge read null → empty attributes. **Fix:** the mirror now forwards
  `setRecoveryContext(ksCap.lastRecoveryContext())` before each mirrored transition. (The unit test set the
  context on the same instance the bridge listened to, so it missed this — exactly the
  `static-green ≠ live-working` lesson.)
- **Bug B — N1 always-clean live (fragile deserialization).** The first classifier did a full
  `mapper.readValue(.., RuntimeManifest.class)`; the live manifest's polymorphic `reachability` block threw,
  the `catch` swallowed it, and it silently classified clean. The unit test used a manifest *without*
  `reachability`, so it passed. **Fix:** read only `pid`/`startedAt` via `readTree`; the regression fixture
  now embeds a `reachability` block.

## Honest validation notes / deferred

- **The current FE surfaces do not render a raw occurrence list.** The unified Activity feed (612 projection)
  filters `worker.*`/`head.*` recovery occurrences out, and `HealthSurface.ts`'s "Recent events" section
  (`renderEvents`) is not in the current nav (`core.health-surface` renders the system overview). So the
  occurrences are confirmed **delivered to the FE via live SSE** but are not currently shown in a list —
  a 600/604 presentation-layer matter, explicitly outside 627's scope ("600/604 own presentation"). Logged
  to the observations inbox (with a sibling note on the Vite dev-FE proxy going stale across dev restarts).
- **N1 reuse-aware start-instant cross-check** was added (matching `AppInstanceLock`) — robustness against a
  real Windows PID-reuse false-negative, not just the test. Kept because it's the established codebase
  pattern for this signal, not speculative.
- **Not built (scope held):** out-of-process watchdog, durable recovery-event log, predictive/saturation
  restart, reliability %/score, `SupervisionEvents` multi-listener, FE tone-ladder. No `governance/observed-happening`
  change (coarse `health-conditions` concept covers the new ID).

## Reach (unchanged by implementation)

N1 conforms to the already-named **Observation-Actuation Closure** principle at the session tier (an
existing observation — the crashed-predecessor manifest — gains its first user-legible sink). The recurring
shape ("a process cannot observe its own death; the closure is a peer or a successor; the two existing
detectors actuate only cleanup") is recorded; only the one successor narration sink is built. Recognize the
principle, add the single sink, stop.

---

# Long-term design — Deliverable 10: the WorkerCapability mirror (2026-06-21, design theorization)

> **Bug A** (Deliverable 9) — the recovery context silently vanished because two `WorkerCapability`
> instances are reconciled by a hand-written mirror — exposed the genuine remaining *structural* issue
> in 627's plumbing (distinct from the FE-rendering gap, which is 600/604's). This theorizes the correct
> long-term design. Documentation only; general-level, not implementation.

## The problem, restated structurally

The Worker's health lives in **two** `WorkerCapability` instances:

- **`ksCap`** (`KnowledgeServerBootstrap.workerCapability`, `:52`) — the supervisor drives transitions +
  the recovery context here; `KnowledgeServerHealthMonitor`, `WorkerStatusCache`, and
  `InferenceCapabilityWiring` read it.
- **`localCap`** (`HeadAssembly.capabilities.worker()`) — the `CapabilityHealthBridge`,
  `LifecycleProjection` / `/api/health`, and `available()` read it.

They are kept in sync by a hand-written, **field-by-field** mirror in `HeadAssembly.connectKnowledgeServer`
(`:796-799`): mirror the initial health, then `ksCap.addListener(forward health + reason)`. The mirror
forwards only the fields someone remembered — so any state added *after* it was written (Bug A: the
recovery context) silently fails to cross it. This is the `standalone-capability-stays-stuck` reference
case, re-instantiated.

## What already exists (investigate-first — the split is not fundamental)

The two-instance split is an **artifact of the async worker-start**, not a requirement:

- **Sync path:** when the KS exists before `buildApi`, `InferenceCapabilityWiring.wire` (`:30-31`) returns
  `ks.workerCapability()` as the `CapabilityGraph`'s worker capability → **one shared instance**; the
  mirror's `ksCap != localCap` guard skips. No drift.
- **Async path** (production — `HeadlessApp:664-669`): `tryStartKnowledgeServer` forks in parallel with
  `buildApi`, so KS is `null` at `CapabilityPhase` → `wire` builds a fresh standalone `localCap`;
  `connectWorker` (`:706`) late-binds `ksCap` and the mirror bridges them. **Two instances.** The drift surface.
- **`InferenceCapability` is single-instance by construction** — `wire` builds one, the manager drives it,
  every consumer reads it; the Brain never forks. So the Worker is the *only* two-instance capability, and
  *only* because of its async start.
- **Tempdoc 521 already named this:** §T2.5 introduced the mirror (the async-start placeholder stuck at
  PENDING); §16.9 considered a reusable `Capability.mirrorFrom()` helper and **explicitly rejected it**
  ("one consumer, one bug pattern, speculative"), citing the `standalone-capability-stays-stuck` handle.
  Bug A is the *second* consumer-side demand 521 said was absent — but it justifies the **root** fix
  (remove the mirror), not 521's rejected option (make the mirror reusable).

## The design: one canonical WorkerCapability, injected across the fork (delete the mirror)

Conform the async path to the sync path: **create the `WorkerCapability` once at the boot seam, before the
async fork, and inject the same instance into both branches.**

- `HeadlessApp` creates the capability before forking; passes it into `tryStartKnowledgeServer` →
  `KnowledgeServerBootstrap` (a new injected-capability constructor; the no-arg / standalone constructors
  keep creating their own, for tests and isolated launchers), **and** into `buildApi` → `CapabilityPhase` →
  `wire` (used instead of `new WorkerCapability()` when KS is `null`).
- Then `capabilities.worker() == ks.workerCapability()` in **both** paths; the mirror's guard never fires;
  the mirror (`HeadAssembly:796-799`) becomes dead code and is **removed**.

This is a structure-**removing** change: the recovery state no longer traverses a lossy hop because there is
no hop — one instance is the single source the supervisor writes and every surface reads. It preserves the
async worker-start (only the capability's *ownership* moves before the fork; the worker process still starts
in parallel). `WorkerCapability` is already thread-safe (`volatile` + copy-on-write listeners), so
cross-thread sharing is sound (the supervisor thread already writes while the API thread reads).

**Incidental wins:** the test-fidelity gap that hid Bug A disappears (with one instance,
`CapabilityHealthBridgeRecoveryTest` / `WorkerCapabilityBridgeTest` exercise the real path — there is no
mirror to bypass); and the N2 recovery-context delivery no longer depends on the `HeadAssembly` mirror
forwarding fix being load-bearing.

## Confidence-building findings (pre-implementation, S1–S4 — read-only trace)

A confidence pass probed the design's load-bearing assumptions against source before any implementation.
**It surfaced one real addition the original design glossed, and cleared the rest.**

- **★ S1 — the design needs ONE addition: a race-safe initial-state replay in the bridge.** The mirror does
  two jobs — (a) `localCap.transition(ksCap.health())` is a **synchronous initial-state copy** at
  connect-time, and (b) forwards future transitions. Job (a) is **load-bearing**: `CapabilityHealthBridge.
  wireListeners` (`OrchestrationPhase:110`, inside `buildApi`) only `addListener`s — it does **no initial
  read** — so today the bridge sees the worker's first state *only because* the mirror's initial-copy fires
  the already-wired listener (deterministic: connect runs after `buildApi`). Under a shared instance the
  worker drives the capability **concurrently** with `buildApi` wiring the bridge, so the bridge can miss
  the first PENDING→non-READY transition. **Bounded impact:** `/api/health` is unaffected (it reads
  `workerCap.health()` directly via `LifecycleProjection`), and the **recovery occurrences are unaffected**
  (they fire long after boot); the only gap is the cold-boot *condition/SSE* layer for a worker that boots
  straight to DEGRADED (a persistent miss, since a same-state re-poll fires no listener). **Fix (small,
  clean, generalizing):** `wireListeners` pushes the *current* condition for worker + inference at wire
  time (replay-then-subscribe) — replacing the mirror's initial-copy and making the bridge self-seeding.
  This is **modeled by the existing `agentToolsRegistration` check-then-listen** (`HeadAssembly:833`). So
  the design is "**delete the mirror + add replay-on-wire to the bridge**," not just "delete the mirror."
- **S2 — generation/consumer census: CLEAR.** No consumer reads `localCap.generation()`/`isFirstConnect()`;
  the only `WorkerCapability` generation reader is the KS's own init path (reads `ksCap`, which *becomes*
  the shared instance → identical semantics). The Brain's `generation()` is a separate `TransitionRunner`.
  No consumer relies on the two instances differing.
- **S3 — mirror role + guarding test: CLEAR.** The mirror does exactly initial-copy + forward (+ the N2
  context, now redundant). `WorkerCapabilityBridgeTest.listenerMirrorsTransitionsAcrossInstances` pins
  *exactly* that cross-instance primitive → becomes moot (rewrite to the single-instance invariant); the
  sibling `pendingReason…` default-state test is mirror-independent and stays. No hidden side-effect. The
  original bug the mirror fixed (the `/api/chat/agent` gate stuck PENDING) is solved *more directly* by
  sharing.
- **S4 — wiring + thread-safety: CLEAR.** `HeadlessApp:878` is the **sole** production KS construction
  (the two test sites use the 2-arg ctor and keep their own capability — injection is additive);
  `CapabilityPhase:56` is the **only** `wire()` caller (one threading point). `WorkerCapability` is
  thread-safe (`volatile` state, copy-on-write listeners, `AtomicLong` generation) — concurrent worker-write
  + main-thread `addListener` is sound; the only consequence is the S1 visibility race, which replay-on-wire
  closes.

## Scope discipline / explicitly NOT this design

- **Not** a reusable `Capability.mirrorFrom()` helper — 521 §16.9 rejected it, and this *removes* the
  mirror (the opposite of investing in it).
- **Not** a generalized "shared-capability injector" framework — there is exactly one two-instance
  capability (the Worker). Fix it; build no structure for the inference capability (already single) or
  hypothetical future ones.
- The **FE not rendering** recovery occurrences (Deliverable 9 note) is a separate 600/604 presentation
  problem — unaffected by this design; remains a coordinate-with-600 item.
- The N1 classifier ↔ `AppInstanceLock` PID-reuse duplication is a separate small DRY follow-up.

# Reach — principle & where it conforms / would apply

## Conforms to an existing principle (do not coin): one canonical instance per fact

The two `WorkerCapability` instances are a **fork** of one fact (Worker health), reconciled by a drifting
hand-written projection — the same anti-pattern 575/622 name as *"one canonical source; every other
representation is a governed projection, not a fork."* Here the cleanest projection is **none**: a single
shared instance. This is also the **root remedy the `standalone-capability-stays-stuck` postmortem already
prescribes** ("share the instance" over "mirror and forward") — Deliverable 9's `HeadAssembly` context-
forwarding fix was the *mirror-and-forward* workaround; this design is the root remedy.

## The recurring shape worth naming plainly

> An async fork that constructs the same stateful fact in two places and reconciles them by a hand-written,
> field-by-field mirror is a fork that silently drops any state added after the mirror was written. The
> correct shape is **create-once-before-the-fork-and-inject** (one instance), not **placeholder-then-mirror**.

**Candidate scope:** any async-start late-bind that today builds a placeholder + a real instance reconciled
by a mirror. The system has exactly **one** live instance (the Worker capability); the inference capability
already does it right (single instance), and the services graph uses *reassembly* (a different pattern), so
there is no second violation to fix. **Existing violation:** the Worker-capability mirror (this design).

**Recognize, do not generalize:** 521 already rejected the generalized mirror; the dual conclusion is to
build no generalized shared-instance *injector* either. Name the shape, fix the one Worker capability, stop
— the same recognize-vs-build separation 627 applied to the Worker open loop and the Head blind spot. If a
future async-late-bound fact appears with the same two-instance mirror, *that* is when a shared pattern
earns generalization.

---

# Implementation outcome — Deliverable 11 (SHIPPED 2026-06-21)

The two-`WorkerCapability` mirror is eliminated at its root: the Worker capability is now ONE shared
instance the supervisor writes and every surface reads.

## What shipped

- **One shared instance, injected across the async fork.** `HeadlessApp` creates a single
  `WorkerCapability` *before* the async worker-start fork and injects it into BOTH branches:
  `KnowledgeServerBootstrap` (new `(config, telemetry, WorkerCapability)` + `(WorkerCapability)` ctors; the
  no-arg / 2-arg ctors keep their own for tests/standalone) and `buildApi → HeadAssembly(… , shared) →
  CapabilityPhase.runWithOutcome(ks, inferenceConfigured, shared) → InferenceCapabilityWiring.wire`
  (`ks != null ? ks.workerCapability() : shared != null ? shared : new WorkerCapability()`). So
  `capabilities.worker() == ks.workerCapability()` in every production path — the sync path already did
  this; the async path now matches.
- **Mirror deleted.** `HeadAssembly.connectKnowledgeServer`'s `ksCap→localCap` mirror block (tempdoc 521
  T2.5) is removed; with one instance the surfaces read what the supervisor writes, so Bug A's silent-drift
  class is gone at the root (no hand-written field-by-field projection to forget a field).
- **Replay-on-wire (the S1 fix).** `CapabilityHealthBridge.wireListeners`, after `addListener`, replays the
  current condition for worker + inference via the existing `pushCondition` (skipping PENDING, like the
  inference listener). This replaces the mirror's load-bearing synchronous initial-copy, so a worker that
  the async start drove to DEGRADED *before* the bridge wired is still seeded — race-safe because
  `transition()` sets the `volatile` health before firing listeners.

## Verification (local — green)

- **Unit/targeted:** rewritten `WorkerCapabilityBridgeTest` (single-instance invariant — a listener on the
  one capability observes every supervisor transition; default-state test kept); new
  `CapabilityHealthBridgeReplayTest` (DEGRADED-before-wire → condition seeded; READY/PENDING → none);
  `CapabilityHealthBridgeRecoveryTest` unchanged and green (the recovery/N2 path now flows through the
  shared instance, not the mirror); `HeadAssemblyTest.connectKnowledgeServerRegistersAgentToolsWithoutBootNpe`
  updated to share the mocked KS capability (so `localCap == ks.workerCapability()`, no mirror) — green.
- **Full suites:** `:modules:app-services:test` + `:modules:ui:test` + `:modules:app-api:test` show **zero
  new failures** — only the documented pre-existing ones remain (`StatusWireContractConformanceTest`/607,
  and `UIOperationViewConformanceTest` + `ValidatorRunnerTest` + `RegistryControllerTest`/626 reconcile-root;
  `UnreferencedCodeTest`/626-607 dead code confirmed failing on `main` too;
  `AiInstallServiceLateBindTest` was a fresh-worktree missing-dist artifact that passes once
  `:modules:indexer-worker:installDist` is built). FE `typecheck` green.

## Live validation (final batch)

Validated against a dev stack started from this worktree (apiPort 64051), surfaced on the session wire:

- **Cold boot — PASS.** `/api/health` shows `worker = LIFECYCLE_STATE_READY` (not stuck PENDING) — the
  shared instance reached READY on the real concurrent boot and the surfaces read it. (Overall lifecycle
  DEGRADED is only `inference.offline`, expected.) This exercises the replay-on-wire / no-mirror path.
- **Chat gate — PASS (no worker-503).** `POST` to the agent endpoint returns `503
  {"unavailable":"inference","reason":"Inference not yet activated"}` — an *inference*-unavailable 503
  (AI not activated, orthogonal), **not** the *worker*-unavailable 503 of the original mirror bug. The
  gate's `capabilities.worker().available()` check passes against the shared instance.
- **Recovery regression — PASS (the decisive check).** Killing the worker produced, on the live
  `/api/health/events/stream`: `worker.restart-attempted` → `attributes {attempt:1, faultKind:"death",
  backoffMs:1000}` and `worker.recovered` → `attributes {recoveredAfterAttempts:1}`, plus the
  `worker.capability` "Recovering" condition. So the N2 forensic attributes still flow end-to-end through
  the ONE shared instance the supervisor writes and the bridge reads — confirming the mirror deletion did
  not break the recovery narration.
- **Browser — PASS (captured via headless Chromium).** The claude-in-chrome extension disconnected
  mid-batch (`list_connected_browsers` → `[]`, unrecoverable from the agent side after the prescribed
  tab-drain), so the screenshot was captured with the repo's own Playwright (headless chromium) against the
  live FE (`localhost:5173` → backend `64051`): load System Health → screenshot → kill the worker →
  screenshot the recovery. The post-kill shot shows the surface **staying calm** — a teal **"Reconnecting…"**
  verdict (not a red "Service degraded"), INDEX cards on **"Last known"** + Queue **"Rebuilding…"** (the
  holding-values treatment), and "Retrieval Reconnecting…" — i.e. the supervised worker death+recovery is
  narrated calmly through the real UI, end-to-end via the one shared capability. Screenshot surfaced in the
  session transcript.

## Reach (unchanged)

Conforms to the one-canonical-source principle (575/622) and the `standalone-capability-stays-stuck` root
remedy (share the instance, not mirror-and-forward); the recurring shape — "create-once-before-the-fork-and-
inject, not placeholder-then-mirror" — is recorded with its one live instance fixed and no generalized
structure built.
