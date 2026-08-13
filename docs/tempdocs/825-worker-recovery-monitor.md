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
