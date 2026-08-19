---
title: "825 — Worker recovery for the knowledgeServer==null state + worker.spawn.failed disambiguation"
type: tempdocs
status: "IMPLEMENTED (2026-08-19) — owner approved the D5 recommendations 2026-08-19 (all five). Option B built, tested to the isolated-backend tier; the LIVE dev-stack leg is the one open item (see §I.7)."
created: 2026-08-14
author: agent session 776e10cd-eef9-4873-a027-1fc2887a334d (Fable orchestration)
category: structural / lifecycle / state-truthfulness (821 class C1)
related:
  - 821 §O.4 (root cause, confirmed evidence chain, run IDs)
  - PR #434 / #436 / #439 (diagnostics + trigger mitigation, merged 2026-08-13)
---

# 825 — Worker recovery for the `knowledgeServer == null` state

## Problem (confirmed, not hypothesized)

When `KnowledgeServerBootstrap.start()` fails after retries, `HeadlessApp` pins the
worker capability DEGRADED with **no `KnowledgeServerHealthMonitor` started** — the
sole monitor construction site (`HeadlessApp.java:434-438` at charter time) is gated
on `knowledgeServer != null`, and the `else if` at `:447-455` has no recovery path.
`/api/health` then serves 503 for the life of the process while the API stays up.
Full evidence chain: 821 §O.4 (captured `headless-backend.log`, run 31742266298).
PR #439 narrowed the trigger (per-attempt deadlines, bounded bootstrap retry with a
supervision veto, spawn ceiling 6) but the terminal state itself is untouched — the
review of #439 explicitly recorded that the probabilistic mitigation must not be
recorded as the structural fix (`structural-defects-no-repeat`).

## Chartered work

1. **Recovery path for the null-knowledgeServer state.** Either a recovery monitor
   that periodically re-attempts `startWithRetry()` from the DEGRADED pin (bounded,
   backoff, narrated via `SupervisionEvents.onRecovering` / `RecoveryContext`), or a
   redesign that keeps one monitor authority alive regardless of bootstrap outcome.
   Design constraint from #439's review: coordinate with `SupervisionPolicy` — one
   restart authority; `WORKER_RESTART_EXHAUSTED` must stay terminal once emitted
   unless an operator-visible recovery event supersedes it honestly.
2. **`worker.spawn.failed` disambiguation.** The reason code is the generic fallback
   for every DEGRADED worker capability except `worker.restart_exhausted`
   (`StatusLifecycleHandler.java:1105` area), so consumers cannot distinguish a
   terminal spawn failure from a recoverable health-budget timeout. Split or qualify
   the code (wire-additive; carries `check-readiness-reason-codes` +
   `searchTraceExplain.ts`/`readinessNotice.ts` regen tails and the
   `LifecycleReasonCode` premerge check).
3. **Fixture fail-fast, revisited.** #436 deliberately omitted fail-fast on
   `worker.spawn.failed` because of the ambiguity in (2). Once (2) lands, the
   isolated-backend fixture can fail fast on the *terminal* code and cut the 240s
   blind wait; the provisional 240s health budget from #434 should then be replaced
   with a measured value from the uploaded failure logs.
4. **Latent recovery-branch hazard** (from #439's review, finding E): `initGeneration`
   is not reset by `close()`; if the transient classification ever widens to
   post-`completeReadyInitialization` failures, a retry would skip
   `tryIngestHelpFiles`. Guard or document before widening `isTransient`.

## Acceptance

- A transient bootstrap failure that exhausts #439's boot retries converges to a
  READY worker without process restart (live-verified on the dev stack, not just
  unit-tested — `static-green ≠ live-working`).
- Reason codes on `/api/health` distinguish terminal from recoverable at every point
  of the arc; no flapping occurrences for a boot that ultimately succeeds.
- Regression tests: recovery-from-pin, reason-code mapping, fixture fail-fast.

## DESIGN (2026-08-19 — cite-verified groundwork; implementation not started; awaiting owner sign-off on the four decisions in §D5)

> Produced by a read-only verification pass at HEAD (all charter cites re-checked;
> one charter claim corrected). Evidence below is `file:line` at 2026-08-19 HEAD.

### D1. Charter corrections at HEAD

- Cites (a)/(b) hold: sole monitor construction still gated on `knowledgeServer != null`
  (`HeadlessApp.java:432-438`), no-recovery else-if at `:447-454`, the null manufactured at
  `:957`. The operator escape hatch is dead in exactly this state too — `POST
  /api/worker/restart` 503s when the reference is null (`InferenceHandlers.java:610-615`).
- **Charter item 2 is ~80% delivered by 837** (PR #472): `worker.spawn.failed` is no longer
  the generic fallback — `resolveWorkerReasonCode` (`StatusLifecycleHandler.java:1071-1076`,
  DEGRADED arm `:1132-1134`) routes to the five 837 codes, and `spawn.failed` is scoped to
  "never started" by construction (`LifecycleReasonCode.java:196-201`). The charter's proposed
  split (spawn-failed vs health-budget) would re-litigate 837 — both are genuinely
  "never started". **The honest remaining axis is recoverable-in-flight vs
  we-have-stopped-trying**: keep `worker.spawn.failed` for "failed, recovery pending/in
  flight"; add exactly ONE terminal code (`worker.spawn_recovery_exhausted`, FAULT class)
  when the bounded recovery budget is spent. Do NOT reuse `worker.restart_exhausted` — that
  is supervision's verdict; conflating them destroys the distinction the fixture fail-fast
  keys on.
- The charter's `searchTraceExplain.ts` regen-tail claim is wrong — that file carries
  `SearchReasonCode`, not `worker.*` (different gate). The real FE tails are
  `readinessNotice.ts:232` (CAUSE_ROWS) and `:479` (RETRIEVAL_IMPAIRING_CODES), plus
  `LifecycleSnapshotTap.java:117-135` (an unmapped code yields NO health Condition).
- `initGeneration` hazard (charter item 4) confirmed still present (`closeForUpgrade`
  `:664-716` resets `started` at `:712` but never `initGeneration`; generation branch
  `:328-351`). Latent today; becomes LIVE under any recovery loop. Cheap closure:
  `tryIngestHelpFiles` is already marker-file idempotent (`:731-752`), so reset
  `initGeneration` in `closeForUpgrade` (or call help-ingest in both arms) as part of this
  lane, before the recovery loop lands.

### D2. Decision: Option B — one monitor authority alive regardless of bootstrap outcome

The discarded bootstrap is provably restartable (`KnowledgeServerBootstrapRestartabilityTest
.java:42-60`); the handover seams already exist and are null-guarded/reconnect-shaped
(`HeadAssembly.connectKnowledgeServer:1181-1245`, `LocalApiServer.lateBindKnowledgeServer
:836-869`, live supplier already passed at `HeadlessApp.java:751`); the monitor already owns
a no-overlap scheduler with a bootstrap callback (`KnowledgeServerHealthMonitor.java:67-81`,
`:99-106`). Option B = stop discarding the bootstrap at `HeadlessApp.java:957`, always
construct the monitor, give `tick()` a second guard arm: no-client ⇒ bounded re-attempt of
`startWithRetry()`; client ⇒ today's `checkHealth()`. One `Closeable`, one shutdown site
(`:883`), one give-up authority. Option A (a second recovery monitor) was assessed and
rejected: it duplicates the construction/handoff atomically in two places and is literally
the second-restart-authority #439's review warned about.

Four load-bearing mechanisms:
1. **Veto across recovery cycles.** `startWithRetry`'s supervision veto
   (`WorkerStartFailures.java:145-151`) bounds one call only. Expose
   `supervisionEngagedOnLastAttempt` (`KnowledgeServerBootstrap.java:107`, currently private)
   and refuse re-attempts when supervision engaged OR the held reason is
   `worker.restart_exhausted`. This is what keeps RESTART_EXHAUSTED terminal.
2. **Pure decision function** `BootRecoveryDecision.decide(state, policy)` mirroring
   `SupervisionDecision` — schedule/backoff/veto/budget unit-testable with no process; the
   declared policy sits next to `SupervisionPolicy` and is asserted by the (build-enforced)
   `governance/supervision-contract.v1.json` — whose worker `partial-boot` row (`:73-82`)
   declares today's brick verbatim and MUST be updated with a resolving guard in the same PR
   (`SupervisionContractTest.java:22-37`).
3. **Flap suppression across cycles.** Each failed attempt currently ends
   `closeForUpgrade()` → `OFFLINE + worker.shut_down` (`:700-708`) — three transitions per
   cycle and a lie about orderly teardown. Extend the existing `retryPending` suppression
   (`:98-104`, `:273`, `:703`) across recovery cycles or the "no flapping" acceptance fails.
4. **ReasonRetention interaction (the non-obvious trap):** `ReasonRetention.retainHeld`
   (`:49-62`) REJECTS a TRANSIENT code while a FAULT (`worker.spawn.failed`) is held and
   health is non-READY — a recovery monitor transitioning `PENDING/worker.starting` gets its
   reason write silently dropped, and `pendingReason()` is published raw on the runtime
   manifest + 503 body (`WorkerCapability.java:27-30`). Either narrate recovery with a code
   the retention rule admits, or add an explicit recovery-supersedes arm to the rule — this
   is the charter's "supersedes it honestly" clause, now with a concrete code location.

### D3. Reason-code + consumer inventory (wire-additive, one new code)

`LifecycleReasonCode.java` member + exhaustive-switch retention arm (FAULT);
`LifecycleSnapshotTap.java:117-135` MappingKey row (reuse the spawn-failed conditionId per
837 precedent); `readinessNotice.ts` CAUSE_ROWS + RETRIEVAL_IMPAIRING_CODES rows;
`check-readiness-reason-codes` gate is CI-wired (`ci.yml:236/:239`; extractor requires
`NAME("code")` shape). Tests: `StatusLifecycleWorkerReasonTest`, `ReasonRetentionTest`,
`LifecycleSnapshotTapTest`, `readinessNotice.test.ts`, `verdict.test.ts`; if a recovery
occurrence is emitted, `BootRecoveryEmitter`-pattern + `HealthEventEmitCoverageTest`.

### D4. Verification path (incl. the missing fault injector)

No fault-injection affordance exists for boot (#434/#436/#439 added none); the precedent is
`TransportFaultInjector` (`ai/install/TransportFaultInjector.java:14,23-34`). The existing
knob `justsearch.worker.pid_validation_timeout_ms` (`KnowledgeServerConfig.java:108-110`) set
to ~1ms deterministically reproduces the exact 821 §O.4 signature on EVERY attempt — proves
the pin, bounded recovery, and terminal narration, but cannot prove CONVERGENCE (permanent
for the process). The acceptance leg therefore needs a **prod-guarded countdown injector**
(`justsearch.worker.boot.faultInjectAttempts=N`: first N PID validations fail, then stop),
read from `KnowledgeServerConfig`/`WorkerSpawner` (the two classes allowlisted by
`AppServicesWorkerGuardrailsTest.java:33,35` — a new sysprop-reading class fails ArchUnit),
guarded on `!config.isProd()`. Test ladder: pure-decision unit tests → empty-tempdir
component fixture (`KnowledgeServerBootstrapRestartabilityTest.java:24-35` pattern: N
attempts, terminal code exactly once, never after supervisionEngaged) →
`IsolatedBackendFixture` lite-mode integration (worker NOT skipped in lite mode; converges
inside the existing 90s worker gate; fixture gains reason-code fail-fast in
`awaitHealthOk`/`awaitWorkerReady` — charter item 3) → live dev-stack leg with the injector.

### D5. Owner decisions needed before implementation

1. **Recovery budget shape:** RECOMMEND bounded-with-give-up + the new terminal code
   (enables item 3's fixture fail-fast; unbounded-with-capped-backoff leaves nothing for
   fail-fast to key on).
2. **May boot-recovery supersede `worker.restart_exhausted`?** RECOMMEND no (veto, mechanism
   1) — keeps supervision's verdict terminal; the "operator-visible supersede" clause then
   applies only to the spawn-failed pin.
3. **Ship the prod-guarded countdown injector?** RECOMMEND yes — without it the acceptance
   criterion's convergence half cannot be exercised deterministically at any tier.
4. **Scope `POST /api/worker/restart` into the same authority?** RECOMMEND yes (cheap): it
   503s in exactly the state an operator would reach for it (`InferenceHandlers.java:610-615`);
   routing it through the recovery authority gives the manual path for free.
5. Item 3's measured 240s-budget replacement stays gated on captured failure-log data
   (none found since 2026-08-13); independent of decisions 1-4.

## IMPLEMENTATION LOG (2026-08-19 — owner approved all five D5 recommendations)

Branch `worktree-825-impl`, built on the design branch merged with `main`. Line numbers are at the
final commit of that branch.

### I.1 Option B — one monitor authority (D2)

| Design point | Where it landed |
|---|---|
| Stop discarding the failed bootstrap (was `HeadlessApp.java:957`) | `HeadlessApp.tryStartKnowledgeServer` holds the instance outside the `try` and returns it on failure — `HeadlessApp.java:1007` |
| Always construct the monitor | `HeadlessApp.startHealthMonitor` (`:502`), called from BOTH the connected arm (`:436`) and the failed-boot arm (`:458`) |
| `tick()` second guard arm | `KnowledgeServerHealthMonitor.java:150-152` — `!bootstrap.hasClient()` ⇒ `runBootRecoveryArm()`; the health arm is otherwise unchanged |
| Bounded re-attempt | `attemptBootRecovery` (`:231`) calls `KnowledgeServerBootstrap.startForRecovery()` (`KnowledgeServerBootstrap.java:363`) — ONE start per cycle; the budget is the policy's, not `startWithRetry`'s |
| One shutdown site | unchanged: `performOrderedShutdown(… healthMonitor …)` (`HeadlessApp.java:883` region) now also covers the failed-boot case, because the monitor exists there too |
| Handover on success | `monitor.onRecoveryConnected(...)` (`HeadlessApp.java:505`) → `connectAndBind` (`:488`) = `HeadAssembly.connectKnowledgeServer` + `LocalApiServer.lateBindKnowledgeServer`, the same pair boot uses |

The four load-bearing mechanisms:

1. **Cross-cycle supervision veto** — `supervisionEngagedOnLastAttempt()` exposed
   (`KnowledgeServerBootstrap.java:383`), read by the monitor into
   `BootRecoveryDecision.Input.supervisionEngaged` (`KnowledgeServerHealthMonitor.java:214`) and
   resolved to `GIVE_UP`/`Veto.SUPERVISION_ENGAGED` with NO narration
   (`BootRecoveryDecision.java:116`, `KnowledgeServerHealthMonitor.narrateGiveUp:302`).
   `worker.restart_exhausted` held on the capability is a second, higher-precedence veto
   (`BootRecoveryDecision.java:113`).
2. **Pure decision function** — `BootRecoveryDecision.decide(Input, BootRecoveryPolicy)`
   (`modules/app-services/.../worker/BootRecoveryDecision.java`), with the declared policy in
   `BootRecoveryPolicy.java` (defaults: 4 attempts, 10s doubling to a 60s ceiling) sitting next to
   `SupervisionPolicy`. `governance/supervision-contract.v1.json`'s worker `partial-boot` row is
   rewritten with the new detection/recovery/terminal state, a `recoveryAuthorityBoundary` clause,
   and two resolving guards (`BootRecoveryDecisionTest`, `KnowledgeServerBootRecoveryTest`) —
   `SupervisionContractTest` passes.
3. **Cross-cycle flap suppression** — `retryPending` generalised to `narrationSuppressed()`
   (`KnowledgeServerBootstrap.java:373`), which also covers `bootRecoveryInFlight`; applied at the
   three narration sites (`start()` entry PENDING `:180`, `start()` catch `:300`,
   `closeForUpgrade()` OFFLINE `:797`) and in `startWithRetry`'s final verdict (`:341`). The arc
   therefore holds RECOVERING throughout and narrates 2 transitions total, not 4 per cycle.
4. **ReasonRetention narration trap — resolved by an explicit, tightly-scoped arm** (not by
   re-coding the narration). `ReasonRetention.recoverySupersedesSpawnFailure` (`:88`, consulted at
   `:57`) admits exactly `held == worker.spawn.failed && incoming == worker.recovering`. Rejected
   alternative: narrating with an admissible code — the only codes the general rule admits over a
   held FAULT are FAULT/STICKY, and calling an in-flight recovery a fault is the dishonesty the
   charter's "supersedes it honestly" clause forbids. **Which one we did is tested**:
   `ReasonRetentionTest.recoverySupersedesOnlyTheSpawnFailedPin` pins the arm AND its three
   non-neighbours (`restart_exhausted`, `index_corrupt`, `spawn_recovery_exhausted` all survive), and
   `KnowledgeServerBootRecoveryTest.firstTickReAttemptsAndNarratesRecovering` proves the write is not
   swallowed end-to-end.

### I.2 `initGeneration` (D1 last bullet / charter item 4)

Reset in `closeForUpgrade`'s `finally`, beside `started.set(false)` —
`KnowledgeServerBootstrap.java:810`. Latent before this tempdoc, live under a recovery loop: the
first successful start after any `close()` would have taken the generation>=1 branch and skipped
`tryIngestHelpFiles` for the process lifetime. Guard:
`KnowledgeServerBootRecoveryTest.closeResetsInitGeneration` (reflective read — the counter has no
consumer that would justify a public accessor).

### I.3 Reason-code + consumer inventory (D3) — wire-additive, one new code

- `LifecycleReasonCode.WORKER_SPAWN_RECOVERY_EXHAUSTED("worker.spawn_recovery_exhausted")` (`:40`),
  classified `FAULT` in the exhaustive retention switch (`:213`). No code renamed or removed.
- `LifecycleSnapshotTap` MappingKey row → `index.start-error`/ERROR, reusing the spawn-failed
  conditionId per the 837 precedent (`LifecycleSnapshotTap.java:140`).
- `readinessNotice.ts` CAUSE_ROWS row (`:239`) + `RETRIEVAL_IMPAIRING_CODES` (`:493`).
- `StatusLifecycleHandler` needed NO change — its DEGRADED arm already forwards `pendingReason()`.
- `check-readiness-reason-codes` green (52 emittable codes, 48 worded rows; producer direction OK).
- Tests: `StatusLifecycleWorkerReasonTest.bootRecoveryExhaustedPassesThrough`,
  `LifecycleSnapshotTapTest.bootRecoveryExhaustedEmitsStartError`, two new `ReasonRetentionTest`
  cases, one new `readinessNotice.test.ts` case (44 pass in that file; 5508 FE unit tests green).
- **No new occurrence ID.** The arc transitions to RECOVERING, so the existing
  `CapabilityHealthBridge` emits `worker.restart-attempted` / `worker.recovered` unchanged — hence no
  `BootRecoveryEmitter` and no `HealthEventEmitCoverageTest` change (D3's conditional did not fire).
  Its i18n message WAS reworded: "stopped responding and is being restarted" is false for a worker
  that never started, so `health-events.en.properties:166` now says only what is true of both arcs.

### I.4 Countdown fault injector (D4)

`justsearch.worker.boot.faultInjectAttempts` / `JUSTSEARCH_WORKER_BOOT_FAULT_INJECT_ATTEMPTS`, read
in `KnowledgeServerConfig.load()` (`:144`) — one of the two ArchUnit-allowlisted classes, so
`AppServicesWorkerGuardrailsTest` stays green. The prod guard is in the record's compact constructor
(`KnowledgeServerConfig.java:48`) rather than at the read site: a constructor cannot be forgotten by
a future caller, and it also disables the injector for a directly-constructed prod config. The
countdown itself is an instance `AtomicInteger` in the bootstrap, spent in `validateWorkerPid`
(`KnowledgeServerBootstrap.java:476`) by throwing `PidValidationTimeoutException` — the exact 821
§O.4 signature, transient-classified, so both the boot retry and the recovery arm engage.

### I.5 Fixture fail-fast (charter item 3)

`IsolatedBackendFixture.failFastOnTerminalWorkerReason` (`:412`), called from both
`awaitWorkerReady` (`:356`) and `awaitHealthOk` (`:394`); `withSystemProperty` (`:125`) forwards
`-D` flags to the spawned Head. Keyed on the TERMINAL code only —
`IsolatedBackendFixtureFailFastTest` pins that `worker.spawn.failed` does **not** abort, which is the
distinction 836 deliberately waited for. The 240s budget itself is **unchanged** (D5 decision 5).

### I.6 `POST /api/worker/restart` through the same authority (D5 decision 4)

`WorkerRecoveryAuthority` (new interface, implemented by the monitor:
`KnowledgeServerHealthMonitor.requestRecoveryNow:346`) → `LocalApiServer.bindWorkerRecovery:842` →
`InferenceHandlers.routeToRecoveryAuthority:677`, consulted from `handleRestartWorker:628` before
either 503 arm. An accepted request answers **202**, not 200: the attempt is scheduled on the
monitor's own single-threaded executor (so a manual request can never race a tick into two
concurrent spawns) and a boot takes tens of seconds. The operator's request clears the backoff wait
but not the budget and not the vetoes. Guard: `InferenceHandlersWorkerRestartTest` (4 cases).

### I.7 Verification

Fail-first evidence — each mechanism was mutated away and the matching test observed RED, then
restored:

| Mutation | Test that went RED |
|---|---|
| `recoverySupersedesSpawnFailure` disabled | `firstTickReAttemptsAndNarratesRecovering` (reason stayed `worker.spawn.failed`) + `arcDoesNotFlap` |
| `narrationSuppressed()` → `retryPending` only | `arcDoesNotFlap` (`[RECOVERING, DEGRADED/spawn.failed, OFFLINE/spawn.failed, …]`) |
| `restartExhaustedHeld` veto removed | `BootRecoveryDecisionTest` ×2 + `restartExhaustedIsNeverSuperseded` (arc narrated RECOVERING over supervision's verdict) |
| `gaveUp` guard removed | `budgetIsBoundedAndTerminal` (GIVE_UP repeated instead of NONE) |
| `initGeneration.set(0)` removed | `closeResetsInitGeneration` |

A real defect was found this way rather than argued: `BootRecoveryDecision.backoffMs`'s
overflow guard copied `SupervisionDecision`'s `scaled < 0` check, which misses `1000 << 62 == 0`
(1000 = 8·125; the 125 shifts clean out of the word). Fixed by guarding on
`Long.numberOfLeadingZeros(base)` (`BootRecoveryDecision.java:148`). The same latent bug remains in
`SupervisionDecision.backoffMs:98-108` — unreachable there (cap 3) and out of scope, so it is logged
to the observations inbox rather than fixed here.

Commands run (all green unless noted):

- `./gradlew.bat spotlessApply` then `./gradlew.bat build -x test` — BUILD SUCCESSFUL.
- `./gradlew.bat :modules:app-services:test :modules:app-api:test` — SUCCESSFUL.
  `KnowledgeServerHealthMonitorTest` needed a real update, not a workaround: its five health-arm
  cases mocked the bootstrap, so `hasClient()` defaulted to false and they took the NEW arm. Each now
  stubs `hasClient() == true` — the precondition that selects the health arm became explicit — and
  four of them were previously passing for the wrong reason (never-verifications over an arm that
  never ran).
- `./gradlew.bat :modules:ui:test` — SUCCESSFUL.
- `cd modules/ui-web && npm run test:unit:run` — 433 files / 5508 tests pass.
  `npm run typecheck` — clean.
- `node scripts/ci/check-readiness-reason-codes.mjs` — OK.
- `./gradlew.bat :modules:system-tests:integrationTest --tests "*WorkerBootRecoveryE2ETest*"` —
  SUCCESSFUL. **This is the convergence proof**: with 3 injected boot faults the Head exhausts its
  whole boot retry, the recovery arm re-attempts, and the worker reaches READY 26s after spawn in the
  same process. Oracle is the health-event occurrence stream, not the log (the fixture's
  `runtime/backend.log` carries only pre-Logback stdout): the snapshot holds exactly one
  `worker.restart-attempted` with `{"attempt":1,"faultKind":"boot","backoffMs":10000}` and one
  `worker.recovered` with `{"recoveredAfterAttempts":1}` — convergence, right-reason, and no-flapping
  in one assertion.
- `node scripts/governance/run.mjs --gate hook-integrity` FAILS with 6 `unwired-hook` findings, all
  pre-existing and unrelated (this worktree's gitignored `settings.local.json` predates six hooks).

**Open item — the LIVE dev-stack leg is PENDING.** Everything above is unit / component /
isolated-backend tier. The acceptance criterion's "live-verified on the dev stack, not just
unit-tested" (`static-green ≠ live-working`) has NOT been run: it is out of the implementing agent's
scope by instruction, and the orchestrator runs it supervised before merge. Suggested procedure:
start the stack with `-Djustsearch.worker.boot.faultInjectAttempts=3`, watch `/api/health` go
`worker.spawn.failed` → `worker.recovering` → READY, confirm ONE `worker.restart-attempted` in
Recent Events, and separately POST `/api/worker/restart` in the pinned state to see the 202.

### I.8 Deviations from the design, argued

1. **The recovery arm re-attempts a NON-transient failure too.** `WorkerStartFailures.isTransient`
   bounds the intra-call retry; the recovery arm calls `startWithRetry(1, 0)`, so the classification
   never gates it. Deliberate: the arm's premise is that the terminal pin was wrong, the operator
   escape hatch (§I.6) must work for a worker that never started for ANY reason, and the cost is
   bounded by the same 4-attempt budget that ends in the terminal code. Retrying a genuinely
   unstartable worker four times over ~2 minutes is the price of the state being recoverable at all.
2. **`BootRecoveryDecision` is NOT registered in `governance/logic-seams.v1.json`.** It fits the
   register (pure, budget/precedence law, sibling of the registered `worker-supervision` seam), but
   registration also requires a floor in `gates/test-efficacy/strength-baseline.v1.json`, which needs
   a measured PIT run this lane did not do; a guessed floor would either be vacuous or fail the
   ratchet. Deferred deliberately, not overlooked — a follow-up should measure and register it.
3. **`RuntimeManifestListenerWiring` needed a change the design did not name.** Its initial
   worker-state publish branched on `knowledgeServer != null`, which after I.1 no longer implies
   "connected" — a bricked boot would have published `worker.state=ready` with a null gRPC port. Now
   branches on `hasClient()` (`RuntimeManifestListenerWiring.java:118`).
4. **`health-events.en.properties` reworded** (see I.3). Not in the design's consumer inventory; the
   shared occurrence would otherwise have told a user whose worker never started that it "stopped
   responding".
5. **The failed-boot arm in `connectWorker` no longer re-stamps `worker.spawn.failed`**
   (`HeadlessApp.java:452` area). Found by the post-implementation critical-analysis pass, not by a
   test: the bootstrap has already narrated its own verdict exactly once, and re-stamping the generic
   code would overwrite a specific held one — `worker.restart_exhausted` and `worker.spawn.failed`
   are both FAULT, so `ReasonRetention` does not defend the held code. That would have destroyed
   supervision's terminal verdict AND silently disabled the new `restartExhaustedHeld` veto, which
   reads that same slot. Pre-existing in the `knowledgeServer == null` branch; this tempdoc is what
   made it load-bearing. The `null` branch (no instance at all, nothing narrated) keeps its
   transition. The E2E stayed green after the removal, which confirms the bootstrap's own narration
   is what the wire was reading all along.
6. **`requestRecoveryNow` catches `RejectedExecutionException`** (`KnowledgeServerHealthMonitor.java`
   `:346` region). Same pass: after `close()` the executor rejects, and an HTTP request must not
   become a 500 because the process is shutting down.
7. **No dedicated E2E for the give-up path.**
8. **(review F9) The E2E's shape is single-cycle.** Its run converges on recovery attempt 1, so the
   CROSS-CYCLE suppression — the property that a multi-attempt arc still narrates one
   `worker.restart-attempted` — is covered at component tier only (`arcDoesNotFlap`, 2 transitions
   over a 2-attempt arc). Making the E2E multi-cycle would mean an injector that fails the first
   recovery attempt too, i.e. a second knob; the live leg is the cheaper place to see it. An unrecoverable boot reaches the terminal code only
   after the full backoff arc (~2.5 min), which is a poor CI trade for a state already pinned at the
   component tier (`arcGivesUpOnceAfterTheBudget`) plus a fast in-process test of the fixture's
   fail-fast (`IsolatedBackendFixtureFailFastTest`).

### I.9 Review fixes (independent refute-first review, 2026-08-19 — READY-WITH-FIXES, 10 findings)

All ten dispositioned; the reviewer's cites were re-verified in the tree before each change. Two
findings turned out to be deeper than reported (F1 and F5), and the tests caught both.

| # | Disposition | Where |
|---|---|---|
| **F1** | FIXED, wider than reported | The overwrite was at `start()`'s per-attempt catch as well as `startWithRetry`'s final one, so the guard went into the ONE funnel every worker-down verdict passes through: `KnowledgeServerBootstrap.transitionWorkerDown:773` refuses to overwrite a held `worker.restart_exhausted` (the corrupt-index verdict is the one exception, and it is computed BEFORE the guard so the unrepeatable marker is still consumed). The three site-level guards are gone; the call sites at `:300`, `:310`, `:353` are now unconditional. The false comment at the failed-boot arm is corrected (`HeadlessApp.java:453`). `restartExhaustedIsNeverSuperseded` now builds its state through the real path via a new `brickedAfterSupervisionGaveUp` helper (the verdict lands, then the boot fails over it). |
| **F2** | FIXED, split as directed | (a) A live supervisor now yields `Action.STAND_DOWN` — this cycle only, no narration, no latch (`BootRecoveryDecision.java:149`); `narrateGiveUp` sets the latch per-arm so the supervision case can never set it. The input also changed from a latched fact to a LIVE one: `supervisionEngagedOnLastAttempt` is never cleared until the next `startWithRetry`, so gating on it would stand down forever and never run the attempt that clears it — the arm now asks `KnowledgeServerBootstrap.supervisionActive():418` (`spawner != null && spawner.supervisionEngaged()`), which is false once the failed start's `close()` dropped the spawner. (b) The permanent veto stays, and `worker.restart_exhausted` joins the fixture's terminal set (`IsolatedBackendFixture.TERMINAL_WORKER_REASONS`). (c) `requestRecoveryNow` answers `VETOED_SUPERVISION` (temporary) vs `VETOED_RESTART_EXHAUSTED` (terminal), with the difference documented on the enum. |
| **F3** | FIXED (root cause, not a re-publish) | The manifest's live-worker supplier now falls back to the boot-time instance (`HeadlessApp.java:803-809`). Deviation from the literal directive, argued: the READY transition fires INSIDE the attempt, when the signal bus is already live, so the fallback makes the publish correct AT the event rather than correcting it afterwards, and it adds no second publish path to keep in sync. The E2E asserts a non-null `grpcPort` in `runtime/manifest.json` after convergence. |
| **F4** | FIXED | `closed` flag checked before the slot and again before the spawn (`KnowledgeServerHealthMonitor.java:251`, `:262`, `:302`); `close()` = `shutdownNow` + bounded `awaitTermination(CLOSE_AWAIT_MS=5s)` so a return means no recovery is in flight (`:511`); `signalBus`/`spawner`/`client` are volatile (`KnowledgeServerBootstrap.java:72-74`, F10). |
| **F5** | FIXED, and the first fix was not enough | The executor-side re-decide landed as directed — but the burst test then showed two further holes the reviewer's sketch did not reach: (i) a dropped-because-stale attempt returned silently, so a manual-only sequence never reached its terminal state; it now narrates the give-up where the decision is made (`:275`); (ii) `requestRecoveryNow` checked "is one running" AFTER deciding, so ten requests queued ten runnables before the first started and all ten were told ACCEPTED. The slot is now RESERVED on the caller thread and handed to the runnable (`:422`, `slotAlreadyHeld`), which makes the ACCEPTED verdict true. Counter increments rather than assigns. |
| **F6** | FIXED (comment only) | The injector comment now states the actual guard — `isProduction()` is DETECTED (explicit flag or bundled-JRE layout), so a shipped build launched with the flag forced false can arm it; same reach as every other dev sysprop, not a security boundary. |
| **F7** | FIXED via the funnel | Rather than a fourth site-level guard, `narrationSuppressed()` moved into `transitionWorkerDown` (`:773`), so the health-budget branch and any future site inherit it. Test `workerDownFunnelOwnsTheSuppressionRule` pins both directions (narrates outside an arc, silent inside). Honest limit: provoking the specific spawn-but-never-healthy branch needs a half-alive worker — the live leg's territory — so what is pinned is the shared rule, not that one branch's call. |
| **F8** | FIXED (javadoc) | `BootRecoveryDecision.Input#msSinceLastAttempt` now says the first attempt is due immediately and names the poll interval as the spacing. |
| **F9** | RECORDED | Deviation 8 above. |
| **F10** | FIXED | Folded into F4 (the three volatile fields). |

Fail-first evidence for the merge-blockers (mutate, observe RED, restore):

| Mutation | Test that went RED |
|---|---|
| F1: funnel guard `supervisionVerdictHeld()` disabled | `supervisionVerdictSurvivesTheFailedStartThatFollowsIt` **and** `restartExhaustedIsNeverSuperseded` |
| F4: BOTH `closed` gates disabled | `closedMonitorNeverSpawns` (with only one disabled it stays green — the gates are deliberately redundant) |
| F5: caller-side slot reservation reverted to the pre-review "is one running" check | `manualBurstCannotOutspendTheBudget` |
| F3: supplier fallback removed | `WorkerBootRecoveryE2ETest` — manifest carried no `grpcPort` at all after recovery |

One mutation did NOT turn a test red and is reported as such: removing the GIVE_UP arm inside the
executor-side re-decide (`:275`). With the slot reservation in place, no currently-reachable sequence
queues a stale attempt, so that arm is defence-in-depth. It is kept because if the window ever
reopens, narrating beats dropping silently — the failure it would prevent is exactly the one the
burst test caught.

Re-verification after the fixes: `spotlessApply` + `build -x test` green; full `./gradlew.bat test`
green (1m12s); `:modules:system-tests:integrationTest` for `WorkerBootRecoveryE2ETest` +
`IsolatedBackendFixtureFailFastTest` green (READY 25.5s after spawn, manifest port asserted);
`check-readiness-reason-codes` OK. The FE was not touched by this pass. The reviewer's confirmed-clean
items (the ReasonRetention arm, the FE tail, deviation 5) were left alone.
