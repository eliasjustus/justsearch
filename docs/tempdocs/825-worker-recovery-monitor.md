---
title: "825 — Worker recovery for the knowledgeServer==null state + worker.spawn.failed disambiguation"
type: tempdocs
status: "CHARTER (2026-08-14) — not started. The structural half of 821 §O.4: PR #439 made the boot-brick trigger ~3x rarer; this tempdoc makes the outcome recoverable."
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
