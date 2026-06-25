---
title: "Command acknowledgement: the in-flight control-state axis (sourced from the dispatch seam)"
type: tempdocs
status: implemented
created: 2026-06-18
updated: 2026-06-19
author: "user walkthrough note (motivating instance); agent (design + verification)"
category: frontend / ux / design-system / operability / control-state / command-feedback
related:
  - unavailable-affordance-reason-authority
  - notification-model-unification
  - activity-feed-non-user-action-semantics
  - surface-tab-state-preservation
  - folder-indexing-journey-no-status
  - residual-walkthrough-findings-fe-reliability-and-consistency
---

# 608 - Command acknowledgement: the in-flight control-state axis

> **What this document is.** A design for a *general* FE control-state axis — **command acknowledgement**,
> the in-flight lifecycle of a user-initiated command — and the principle that this state must be *sourced
> from the dispatch seam*, not hand-tracked per surface. It is motivated by, but not scoped to, a concrete
> Add-Folder defect, which is kept below as the worked example / proof-by-example.
>
> *Repurposed 2026-06-19* from the original Add-Folder-only problem statement, once investigation showed the
> defect was one instance of a missing, app-wide axis (a verified class — see §"Evidence it generalizes").
> All `file:line` citations are on `main`, HEAD `3f55a361`.

## The gap, in one axis

A control has **two** lifecycle axes, and the FE currently models only one:

| Axis | Question it answers | Status today |
|---|---|---|
| **Availability** (pre-activation) | *"May I activate this control right now, and if not, why?"* | An authority — tempdoc 596, on `jf-control` (`available \| blocked \| unavailable{reason,transient?} \| degraded{caveat}`), with a reachable reason and a queue-and-auto-run for not-ready controls (`Control.ts:48-130`). |
| **Acknowledgement** (post-activation, in-flight) | *"Is the command I just activated still running — did the app take it?"* | **Missing as an authority.** Exists only as a private boolean in one button type (`OpButton.pending`), and is absent everywhere a command dispatches through a pre-step. |

When the second axis is invisible, the user gets no confirmation their command was accepted, the app feels
frozen, and the natural response — click again — can cause real harm (see the worked example).

## Two principles this document commits to

1. **Acknowledgement is the second half of control state, and belongs on the one operable primitive.**
   Symmetric to 596 availability: the in-flight state should be a property of `jf-control`, so that "a live
   button sitting on top of an in-flight command with no acknowledgement" is *unrepresentable* — exactly as
   596 made "an unavailable control with no reachable reason" unrepresentable. This *extends* an existing,
   well-liked authority additively; it does not invent a parallel one.

2. **State an action produces is sourced from the dispatch seam — not re-tracked by each caller.** The root
   cause of the worked-example bug is that every submit site must *remember* to flip a busy flag, and one
   surface forgot. The fix that removes the *class* of bug is to make the in-flight state something the
   command dispatch *produces* (the invocation already owns the promise lifecycle), which the control
   *consumes*. This is the repo's projection-vs-fork discipline applied to *transient* state, the way single
   authorities are already applied to *data*.

Everything else below (re-entrancy = acknowledgement; transient-vs-in-flight; idempotency placement; local
receipt altitude) **falls out** of doing these two correctly — they are consequences, not separate machinery.

## The design

1. **A `busy` overlay state, owned by `jf-control` itself.** While a command is in flight the control
   renders disabled + a spinner — that render *is* the acknowledgement. Crucially, `busy` is **not** a new
   *host-supplied* availability kind; it is an **internal overlay the primitive computes from the activation
   promise it tracks** (principle 2), composed with — not added to — the host's `availability`
   (`availability.ts` stays the steady-state authority; see §"How the two axes compose"). This keeps the
   in-flight state where it can't be forgotten (the control), rather than as one more thing the host must
   remember to pass.
   - **Why it must not queue (a verified hard constraint — see U3).** `Control.resolveQueued()` auto-fires a
     held intent on settle, and the queue is armed *only* for a `transient` block (`Control.ts:363-366,
     390-408`). The existing "refresh-in-flight" pattern uses `unavailable{transient:true}` precisely
     *because re-running a refresh is safe*. A non-idempotent command (Add) must **not** auto-fire on
     settle, or it re-runs the command. Because `busy` is the control's own overlay from a tracked promise —
     not a `transient` availability — it sits entirely outside the queue path, so the "already running, do
     not re-fire" semantics hold by construction. (The "not ready yet, safe to queue" path remains 596's
     `transient`; the two are now cleanly distinct: one is host-supplied availability, the other is the
     primitive's activation overlay.)

2. **The operable primitive's *activation* is the seam — make it promise-aware.** The single path every
   command already flows through is `jf-control`'s `onActivate`. Today its return is `void` and the
   command's promise is *discarded* — that discard is, literally, the bug. The long-term mechanism is to let
   activation return a promise and have the control track it: while the returned promise is pending, the
   control is `busy`. Then acknowledgement is **opt-out, not opt-in** — a surface gets it by returning the
   promise it already holds, and *forgetting* now means actively throwing the promise away (the visible
   anti-pattern). This is what makes the bug-class unrepeatable for every control built on `jf-control`
   (i.e. `jf-button`, which the Add path and the whole pre-step→dispatch class use). It is strictly additive
   — a `void`-returning activation behaves exactly as today, and a census confirms every existing
   `.onActivate` callsite returns a `void` closure (V2). (See §"How the two axes compose" for the exact
   boundary and precedence.)

3. **`OpButton.pending` is *prior art and a future convergence target* — not a free fold-in (V1
   correction).** A probe showed `OpButton` does **not** compose `jf-control`: it renders `jf-action-button`
   / `ActionButton`, a *separate* primitive that draws its own raw `<button>` (still on pre-atom
   `--justsearch-shell-*` tokens) and carries its own `pending` + an `action-invoke` event model
   (`ActionButton.ts:134-244`). So promise-aware `onActivate` on `jf-control` does **not** automatically
   dissolve `OpButton.pending` — that fork lives on a different primitive. The honest framing: there are
   **two adopters of one axis** — the `jf-control` overlay (this work) and `ActionButton.pending` (existing).
   They should converge by migrating `ActionButton` onto `jf-control`, but that migration (ActionButton is a
   pre-atom component overdue for it anyway) is **a separate, larger change, out of scope here**. The axis is
   still right; only the "one primitive already owns everything" scope was over-claimed.

4. **Re-entrancy = acknowledgement (one mechanism).** The in-flight `busy` state blocks re-entry by
   construction, so the "did it work? *click again*" double-submit cannot be initiated. Feedback and
   duplicate-suppression are the *same* lock, not two features.

5. **Idempotency belongs where all callers converge — `RootLifecycleOps.addWatchedRoot` (V4-verified).** A
   control lock only protects clicks; non-UI callers (agent tools) bypass it. So the duplicate-effect guard
   lives server-side, at the single method every caller (UI op + agent tool) reaches:
   `RootLifecycleOps.addWatchedRoot` — predicate `watchedRoots.containsKey(normalized)` ⇒ no-op the re-add
   (covers both mid-walk and walk-complete). A probe confirmed this is safe: `markNeverIndexed` (the
   resetting mutator) is called *only* from the add paths, while reindex uses a different path
   (`reindexWatchedRoots`/`markIndexed`), so the guard does **not** break reindex — and it must sit in
   `addWatchedRoot`, **not** in `markNeverIndexed` (which `addWatchedPath` also reuses). (Earlier-draft
   correction, U1: the guard was first put on `OperationInvocationRequest.idempotencyKey`, which is
   **deferred/unimplemented**; the convergence-method guard needs no wire field.)

6. **Altitude = a local in-control receipt (governed by 613, not re-invented here).** In 613's routing
   taxonomy (local receipt · transient toast · system notice · advisory item · Activity event · blocking
   ceremony), acknowledgement is the *lightest* tier — rendered at the control, never promoted to a toast,
   a `SystemNotice`, or an Activity entry (612 independently says local acks must not dominate the feed).
   The busy→settle transition *is* the receipt; no separate receipt component is needed (U4).

### How the two axes compose (the part the generalization made necessary)

Elevating from one instance to an axis surfaces a question a single-surface patch never had to answer: a
control has *both* an availability and an acknowledgement state at once — how do they combine? The answer is
not "two rival axes plus a tiebreaker," but **one lifecycle with two contributors: a steady state and a
transient overlay.**

- **Availability is the steady state**, recomputed continuously from observed-state + intent gates (596,
  unchanged). It is what the control is *between* commands: `operable` / `degraded` / soft-`unavailable`
  (reason) / hard-`blocked` (inert).
- **`busy` is a transient overlay** the activation produces, reachable **only from an operable steady
  state** — because activation only fires `onActivate` from operable (a `blocked` control is inert; a
  soft-`unavailable` one surfaces its reason *instead of* firing). So the invariant **`busy ⟸ was-operable`**
  holds by construction; "a blocked control that is also busy" is unrepresentable.
- **Precedence is temporal, not a priority number.** While the activation promise is pending, the `busy`
  overlay supersedes the render (locked + spinner); underneath, availability keeps recomputing. On settle,
  the overlay clears and *whatever availability is by then* governs again. This resolves the
  "becomes-unavailable-mid-flight" case cleanly: if the backend goes offline while a command runs, the
  underlying state flips to `unavailable`, the overlay holds until the command settles (it is still running),
  and on settle — typically a failure surfaced by the caller's own error path — the now-`unavailable`
  reason shows. No tiebreaker, no lost state.

**Temporal boundary — what `busy` brackets.** Acknowledgement spans the **returned command promise**, whose
contents the *caller* defines, not the *pre-step* gesture:

- The pre-step (native folder picker, confirm dialog, typed-path field) is **not** "busy" — the system is
  not working on a command yet; the user is still composing it. Modal pre-steps (picker, confirm) lock the
  control on their own; the non-modal typed-path step is a separate, fast prior activation that returns
  before any dispatch. So spinning the button during the picker would be a lie, and the promise-aware model
  avoids it because the *command* promise begins at dispatch.
- The promise may legitimately include the surface's own "make the result observable" step — for Add,
  `invoke().then(refresh)` — so `busy` ends exactly when the user can see the outcome (the row appears),
  not a beat before. The seam owns *acknowledgement of* the command; the caller owns *what the command is*.

**The shape of the seam — deliberately the primitive, not a side controller.** Principle 2 could be realized
as a separate dispatch controller/helper each surface installs, but that re-introduces an opt-in step a
surface can skip. Putting it on `jf-control`'s activation contract (promise-aware `onActivate`) is *less*
structure and makes the omission unrepresentable. The exact ergonomic form (the precise return type, whether
a sibling promise-aware wrapper exists for non-`jf-control` callers) is left to implementation — pinning it
now, with one concrete adopter, would be structure ahead of need. The axis and its composition are the
durable decisions; the signature is not.

### One-line statement

> *Command acknowledgement is the post-activation, in-flight half of control state — a transient `busy`
> overlay reachable only from an operable steady state, produced by making the one operable primitive's
> activation promise-aware (so the surface gets it by returning the command promise it already holds, and
> can't forget), bracketing the caller-defined command promise (dispatch → observable result, never the
> pre-step), backed by a convergence-point idempotency guard (`RootLifecycleOps.addWatchedRoot`), rendered
> as a 613 local in-control receipt. `OpButton`/`ActionButton.pending` is a second adopter of the same axis
> on a separate primitive; its convergence onto `jf-control` is future, out-of-scope work.*

## Worked example (motivating instance): Add Folder

The defect that surfaced the axis. Useful as the concrete proof and the first adopter.

**The flow on `main`.** The header/inline **Add Folder** buttons are plain `jf-button`s whose `.onActivate`
calls `handleAddRoot()` (`LibrarySurface.ts:739-849`). `handleAddRoot` runs a *pre-step* (Tauri native
picker, or a typed-path field) then `await invoke(OP_ADD, …)`. `invoke()` is the whole feedback surface
(`LibrarySurface.ts:548-559`): it clears the error, awaits the full `invokeOperation` round-trip, then
`refresh()`s — with **no busy flag, no button disable, no provisional row** during the round-trip. The row
appears only when the *subsequent* `refresh()` GET returns. `jf-button` itself has no in-flight state
(`.onActivate` is fire-and-forget, the promise dropped — `Button.ts:88-96`), and the Add path *cannot* use
`OpButton` (the pre-step comes first) — so it inherits none of `OpButton.pending`'s acknowledgement.

**It is an acknowledgement defect, not a latency defect.** `core.add-watched-root` is fast — the handler
registers the root and *queues* the walk on a background thread, returning in tens of ms
(`AddWatchedRootHandler.java:71-84`, `RootLifecycleOps.java:255-275`). The point is that a command with
*zero* acknowledgement feels broken even at a few hundred ms.

**The harm is real, not cosmetic.** The predicted "click again" is actively damaging: a second add re-runs
`markNeverIndexed`, which drops `walkCompleted` and resets the timestamp (`WatchedRootsState.java:56-60`),
so a fully-indexed "✓" folder **reverts to "Scanning"** and re-walks. (Manual mode blocks re-adds via the
`alreadyWatched` preview, but the Tauri picker path has no such guard.) This is what motivates principles
4–5 above.

**Verification-question answer (from the original problem statement).** Case 1 (pre-row acknowledgement
gap) — **CONFIRMED, source-conclusive.** Case 2 (post-row progress) — already covered by the 599 row-status
work (the row shows "Scanning"). Case 3 (no gap) — rejected.

## Evidence it generalizes (the class, not a one-off)

The pre-step→dispatch pattern that escapes `OpButton` spans **≥3 surfaces** (U6): LibrarySurface (Add via
picker, Remove via confirm), SettingsSurface (`deleteCustomTheme`, `revokePlugin`), BrainSurface
(`hostPickFolder` + confirm). The richer **agent-run** lifecycle (565 `dispatchRunControl`, 610 live-run
controls) is a heavier sibling for long-running, steerable, side-effecting runs — *not* a model a one-shot
command should be dragged into. Between `OpButton.pending` and the agent-run machinery is a gap, and every
one-shot pre-step command falls into it. That breadth is what justifies an *axis* rather than a per-surface
patch. *(Note: SettingsSurface is moving confirm flows to a declared CONFIRM-CEREMONY statechart; the
in-flight axis should compose with that — the ceremony is *pre*-dispatch consent, busy is *post*-dispatch
execution — not duplicate it.)*

## Scope discipline — what this does *not* justify

- **No global command bus, no notification system.** Acknowledgement is local to the control; 613 owns the
  routing model and 612 owns the durable-ledger semantics. This document contributes a *mechanism* and one
  routing row, nothing larger.
- **No mass migration of every button.** Like 596, the axis is additive: existing consumers are untouched;
  the dispatch seam and pre-step submit sites adopt it, and `OpButton.pending` converges onto it. Breadth
  follows need, not a target.
- **No optimistic provisional row.** The authoritative row already arrives in a few hundred ms showing
  "Scanning" (599); an optimistic row would duplicate the busy state and add a reconcile problem. Out of
  scope unless a live measurement ever shows a genuinely long (>~1 s) gap (YAGNI, contingency only).
- **Which principles become structure:** principles 1 and 2 are the load-bearing ones to generalize (proven
  by the verified class). Principles 4–6 are *rules that fall out* of 1+2 done correctly — not separate
  authorities to build.

### Adjacent but distinct (named so it isn't silently folded in)

In the Add flow, the post-invoke `refresh()` also blanks the populated card list to a lone "Loading…"
(`LibrarySurface.ts:858-868`) — every existing folder card disappears momentarily. That is a
**state-retention / refresh-flicker** concern in the **609** lineage (don't tear down already-rendered
content on an ordinary transition), *not* the acknowledgement axis. It lives in the same `invoke()`→
`refresh()` path, so the work will touch this code, but it should be fixed-alongside and attributed to 609,
not absorbed here.

## Confidence / pre-implementation verification (agent, 2026-06-19)

Read-only probes run before any feature code, to retire the design's load-bearing assumptions. One probe
**refuted** a claim (U1) and one **constrained** the mechanism (U3); the design above already reflects both.

- **U1 — `idempotencyKey` provides submission dedup → REFUTED.** `OperationsController` documents the field
  as "deferred per §A.5" with no read-site; the only "retry" machinery (`RetryRateLimitValidator`,
  `RetryPolicy.idempotencyKey`) is catalog-validation-time, not runtime dedup; `host.data.invokeOperation`
  never even sets the key (`data.ts:68-88`). *Impact:* the machine-side guard is a **handler-level no-op**,
  not the dispatch key (principle 5). Surprise averted — the key would have been dead substrate.
- **U2 — does the axis trip a governance gate / need a register row? → NO BLOCKER.** Control state lives in
  plain TS (the availability union in `availability.ts`, the primitive in `Control.ts`); **no
  `governance/*.json` register enumerates control states**, so the `busy` overlay needs no register row.
  `message-single-model` governs only the toast seam (`emitEphemeralToast`); a busy *control-state render*
  is not a message channel (and any "in progress" message must reuse that seam, which `Control.activate`
  already does). `controls-a11y` is satisfied — a busy button is still a `jf-control`. *Impact:* the change
  is concentrated in `Control.ts` (promise-aware activation + the `busy` overlay render); `availability.ts`
  stays the steady-state authority and needs no new host-supplied kind. No governance changeset.
- **U3 — can `jf-control` host busy without re-firing the command? → YES, and the overlay model makes it
  structural.** The auto-fire-on-settle behaviour (`Control.ts:363-366,390-408`) fires only for a
  `transient` *availability*; because `busy` is the primitive's own activation overlay (not a `transient`
  availability), it sits outside the queue path entirely, so "already running, do not re-fire" holds by
  construction. See design principle 1 + §"How the two axes compose".
- **U4 — reusable local-receipt mechanism? → exists, not needed here.** Precedents exist
  (`components/chat/NavigationReceipt.ts`, `utils/clipboardCopy.ts`), so 613's "in-control receipt" tier is
  real; but the busy→settle transition is itself the receipt.
- **U5 — does this depend on 613/609 being designed first? → NO.** 613 is an open routing-model design; 609
  owns the refresh-flash. Neither gates this work; 613 later records the routing row, 609 owns the flash.
- **U6 — is the pre-step→dispatch pattern a real class? → CONFIRMED (≥3 surfaces).** See §"Evidence it
  generalizes".
- **U7 — click→row gap magnitude → UNMEASURED (deliberately).** Stack was dead; a cold start wasn't worth it
  for a LOW-impact contingency. The design is unaffected (busy helps at any latency).

## Confidence pass 2 — the promise-aware mechanism (agent, 2026-06-19)

A second read-only pass, run after the design deepened to promise-aware activation (a more invasive change to
the central primitive than pass 1 assumed). One probe forced a **scope correction** (V1) and one a
**placement correction** (V4); both are reflected in the design above.

- **V1 — does promise-aware `onActivate` fold in `OpButton.pending` for free? → NO (scope corrected).**
  `OpButton` renders `ActionButton` (`jf-action-button`), which is a *separate* primitive — it draws its own
  raw `<button>` and owns its own `pending`/`action-invoke` model, and does **not** compose `jf-control`
  (`ActionButton.ts:134-244`). So the axis lands cleanly on `jf-control`/`jf-button` (the Add-Folder class),
  but `ActionButton.pending` is a second adopter on another primitive; converging it is separate, out-of-scope
  work (design point 3 + one-liner softened accordingly).
- **V2 — is promise-aware activation strictly additive? → YES.** Every sampled `.onActivate` callsite returns
  a `void` closure (e.g. `AuthorizationHost.decide`, `ConfirmDialog.cancel`, `UnifiedChatView.toggleAffordance`,
  and the explicit opt-out `() => void this.handleSaveAsMacro()`). Busy engages only when a caller *opts in*
  by returning a promise; void callers (and their existing `Control`/`Button` tests) are unchanged.
- **V3 — can the consent ceremony be excluded from the busy window? → YES, structurally.** `ActionButton`'s
  TYPED/INLINE consent is a *pre-dispatch render stage* (`handleInitialClick` sets `confirmStage` and returns;
  the `action-invoke` dispatch fires only from `fireInvoke` after confirm — `ActionButton.ts:214-244`). So
  busy/pending already brackets *dispatch*, not consent — the boundary rule is implementable, not merely
  asserted.
- **V4 — idempotency predicate + reindex safety → RESOLVED, placement corrected.** `markNeverIndexed` (the
  resetting mutator) is called *only* from the add paths (`RootLifecycleOps.java:186,266`); reindex uses a
  different path (`reindexWatchedRoots`/`reindexPersistedRoots` → `markIndexed`, line 424). So the guard goes
  in **`RootLifecycleOps.addWatchedRoot`** (the convergence method for UI + agent-tool callers), predicate
  `watchedRoots.containsKey(normalized)` ⇒ no-op — **not** in `markNeverIndexed` (reused by `addWatchedPath`).
  Reindex is unaffected.
- **V5 — can `Control.ts` host the overlay cleanly? → YES.** Insertion point is `activate()`: capture
  `onActivate()`'s return; if thenable, set a reactive `busy`, `requestUpdate`, clear in `.finally`; early-
  return from `activate()` when already busy (the re-entrancy guard). Render adds a busy branch that supersedes
  availability; the `transient`-keyed queue (`resolveQueued`) is untouched because busy is not an availability.

### Residual risks going into implementation

- The `busy` overlay touches `Control.activate()`/`render()` and `jf-button`'s `availability` forwarding —
  small but central; existing `Control`/`Button` unit tests plus a new "busy does not queue/auto-fire" +
  "busy blocks re-entry" test are the guardrail (`audit-without-test`).
- Idempotency in `RootLifecycleOps.addWatchedRoot` is a backend behavioural change with its own test surface
  (`AddWatchedRootHandlerTest` / a `RootLifecycleOps` add-twice test), separate from the FE work.
- **Tracked-promise lifecycle.** A control is `busy` until its activation promise *settles*; a caller whose
  promise never resolves stays stuck busy. Honest (the command genuinely hasn't returned), but it puts the
  onus on callers to return an always-settling promise. Cancel/timeout for long commands is out of scope (the
  steerable long-run case is the agent-run lifecycle's, not this axis's).
- **`ActionButton`→`jf-control` convergence (V1) is deferred, not solved.** Until it lands, `OpButton`-class
  buttons keep their own `pending` — correct today, but two implementations of one axis coexist. Acceptable
  interim; flagged so it isn't mistaken for done.

### Confidence rating

**8 / 10** for the remaining implementation — up from 7 after pass 2 retired the promise-aware mechanism's
unknowns and corrected two claims before they could surface mid-build (the `OpButton` fold-in scope and the
idempotency placement). The mechanism, the additive contract, the consent boundary, the idempotency
predicate, and the `Control` insertion point are all source-verified. The residual −2 is honest: the change
still sits on the central operable primitive, and the end-to-end behaviour (busy render → settle → backend
no-op) has only been established statically — a live-stack exercise during implementation is the last tier.
No remaining design-level surprises are anticipated.

---

## Implementation status (agent, 2026-06-19)

**IMPLEMENTED** on branch `worktree-608-cmd-ack` (commit `2d6f36f54`; not merged). Scope as planned: the
in-flight axis + first adopter + backend guard. `ActionButton`/`OpButton` convergence, the 609 refresh-flash,
and the optimistic row remain out of scope.

Changes: `Control.ts` (promise-aware `activate()`/`runActivation()` + non-transient `busy` overlay:
spinner + hard-disable + `aria-busy`, re-entrancy guard, outside the transient queue), `Button.ts`
(`onActivate` widened to return a promise, forwarded), `LibrarySurface.ts` (Add buttons return the command
promise), `RootLifecycleOps.addWatchedRoot` (convergence-point idempotency: re-add of an already-watched
root is a no-op). Tests: `Control.test.ts`, `Button.test.ts`, new `RootLifecycleOpsIdempotencyTest.java`.

### Validation (all surfaced in the implementing transcript)

- `./gradlew.bat :modules:app-services:test` — green (incl. the new idempotency test).
- `cd modules/ui-web && npm run typecheck` — clean; `npm run test:unit:run` — **3246 passed**
  (Control + Button busy cases included).
- `check-controls-a11y` + `check-message-single-model` gates — green.
- `./gradlew.bat build -x test` — compiles green (spotless/PMD/archunit pass). **Caveat:** the
  `verifyGovernanceGates` `class-size` gate fails on **pre-existing baseline drift** in two files this work
  never touched (`CoreOperationCatalog.java` 1016→1022, `StatusLifecycleHandler.java` 1163→1208); logged to
  `docs/observations.md`. `build -x test -x verifyGovernanceGates` is green.
- **Live UI (worktree dist + Chrome):**
  - (a) the served `jf-control`, given a pending command, renders `aria-busy=true` + `disabled` + spinner,
    clears all on settle, and ignores re-entrant clicks (0 extra command calls) — deterministic probe.
  - (b) adding `F:\JustSearch\tmp\608-verify-folder` via the real UI produced an indexed folder row
    (doc count 5→7) — acknowledgement completed.
  - (c) two re-adds of that folder via the operation endpoint (the non-FE-gated/agent-tool path) left the
    row's `lastIndexed` timestamp byte-identical with `inFlightCount: 0` — no revert to "Scanning", no
    redundant walk. Visually confirmed (folder stayed green-check "indexed").

---

## Future directions — research (agent, 2026-06-19)

Post-implementation research on what the shipped `busy` axis enables: polish, extensions, and — equally
useful — what NOT to build (boundary preservation). **Documentation only; no code changed.** Each idea is
grounded against the codebase so it composes with existing authorities rather than reinventing. Ranked by
value-to-cost.

### 1. A11y polish — `busy` should be `aria-disabled`, not native `disabled` (highest value, lowest cost)

The shipped overlay hard-disables the button while busy (`?disabled=${... || this.busy}`). Accessibility
guidance is consistent that **native `disabled` on a loading button is an anti-pattern**: it drops the
button from the tab order and **loses keyboard focus**, so a user who pressed Enter is bounced to the top of
the DOM, and the state change is poorly announced. The recommended pattern is **`aria-disabled="true"` +
`aria-busy="true"`** (stays focusable; activation intercepted to a no-op) — which is exactly what this
control **already does for the 596 `unavailable` kind** (`Control.effective()` → `aria-disabled`, not native
disabled). The shipped `busy` instead mirrored the *old* `ActionButton.pending` (hard disable). So this is
not new design — it is aligning `busy` with the repo's own established a11y discipline, and the re-entrancy
guard in `activate()` already makes `aria-disabled` safe (a click can't re-fire). Two riders:
- **WCAG 2.1 SC 4.1.3 (Status Messages):** announce the busy→settled transition through an `aria-live`
  status ("Adding…" → "Added"), which `aria-busy` alone does not do.
- **No layout shift:** reserve the button's width when the spinner appears (a width jump on every command is
  its own small jank). `prefers-reduced-motion` is already handled (global `jf-spin`).

### 2. Latency-adaptive `busy` (the `spin-delay` pattern) — the strongest extension

An immediate spinner **flashes** on sub-second commands. Nielsen's thresholds: <0.1s feels instant, <1s the
user's flow is uninterrupted and *"no special feedback is necessary."* The established fix is the two-timer
**spin-delay** pattern: don't show the overlay until a short **delay (~200ms)** has elapsed (fast commands
show nothing), and once shown, keep it for a **minimum (~500ms)** so it never flickers. The local Add is a
few hundred ms — today it flashes; spin-delay would show nothing on a fast add and a clean spinner only when
the command is genuinely slow. This is a small, self-contained upgrade to the overlay's timing (the `busy`
flag becomes time-gated), with concrete, citeable thresholds. Best single extension.

### 3. Settle micro-receipt (busy → brief ✓) — optional, scoped, composes with existing substrate

The standard button micro-interaction is spinner → **brief success checkmark** (then reset), which is
exactly 613's **"local in-control receipt"** tier. Worth adding **only for commands whose success is not
already visible elsewhere** (e.g. "Apply excludes", "Save settings") — Add already shows the new row, so it
needs no receipt (the design's existing position holds). **Error** should stay on the existing 559
single-message authority / surface error banner — do NOT add a per-button error state (that would fragment
error display). Where a command is reversible, the receipt could compose with the already-present
undoable-effects substrate + `ActionLedgerClient` (a "Done · Undo" affordance) rather than a new mechanism.

### 4. What NOT to add (boundary preservation — a real research result)

- **Determinate progress on the button: NO.** Progress indicators are for *measurable* work; in this app the
  measurable long work is **background indexing**, which already has its own progress surface (the folder-row
  "Scanning · N remaining" status, 599; the 565 run lifecycle; `substrates/tasks` progressbars). The
  command-accept button's job ends at *"the command was accepted"* — a short **indeterminate** window. Putting
  a progress bar on the button would blur the deliberate 608↔599 boundary (acknowledgement vs the operation's
  own progress). Keep `busy` indeterminate.
- **Cancellation on the button: NO (for now).** Long, steerable, side-effecting work belongs to the agent-run
  lifecycle (565 `dispatchRunControl`), which already owns halt/steer. The acknowledgement axis is for short
  command-accept windows; a cancel affordance there would import run-control weight the one-shot case doesn't
  need.
- **A global "app is busy" aggregate: resist.** The axis makes a cheap "is any control in flight" signal
  *possible*, but acknowledgement is local by design (613/612). A global busy-bus is the kind of structure
  the design explicitly declined; note the temptation, don't build it.

### 5. Structural follow-ups

- **Converge `ActionButton`/`OpButton.pending` onto `jf-control`.** Already flagged (V1); finding #1
  strengthens it — `ActionButton` also hard-disables, so migrating it onto the promise-aware `jf-control`
  folds the fork AND upgrades those buttons to the better a11y + spin-delay timing for free.
- **A light single-authority signal.** Once `jf-control` is the one home for in-flight state, a hand-rolled
  `submitting`/`pending` boolean in a surface is a fork — a future early-warning check (mirroring
  `message-single-model` / the run-renderers register) could flag it, keeping the axis from re-forking.
- **Reliability framing, not just UX.** Because the re-entrancy guard is structural, every adopter gets
  double-submit protection by construction — a correctness property (no duplicated side effects) layered on
  top of the FE busy guard and the backend convergence-point idempotency. Worth stating explicitly when the
  axis spreads.

### Net take

The shipped axis is a small primitive with a large surface of *composable* follow-ups. The two highest-value
next steps are both refinements of the existing overlay, not new features: **(1)** switch `busy` to
`aria-disabled` + a live-region announcement (correctness, aligns with 596), and **(2)** add spin-delay
timing (kills the fast-command flash). Everything else (success receipts, ActionButton convergence) is
genuinely optional and additive; the most useful research output may be the **"don'ts"** — keeping progress
and cancellation on the operation/run surfaces, and acknowledgement local — which protect the boundaries the
design drew.

### Sources

- Nielsen Norman Group — [Response Time Limits (0.1s / 1s / 10s)](https://www.nngroup.com/articles/response-times-3-important-limits/); [Button States Communicate Interaction](https://www.nngroup.com/articles/button-states-communicate-interaction/)
- Accessible loading buttons — [Bekk: aria-disabled with friends](https://www.bekk.christmas/post/2023/24/accessible-loading-button); [Deque: Accessible ARIA buttons](https://www.deque.com/blog/accessible-aria-buttons/); [Primer button accessibility](https://primer.style/product/components/button/accessibility/)
- Anti-flicker spinner timing — [spin-delay (smeijer)](https://github.com/smeijer/spin-delay); [A React Hook to prevent flickering spinners (Raccoons)](https://www.raccoons.be/what-we-think/articles/a-react-hook-to-prevent-flickering-spinners)
- Progress indicators — [Material Design: Progress indicators](https://m3.material.io/components/progress-indicators/guidelines)

---

## Refinements implemented (agent, 2026-06-19) — a11y + spin-delay

The two highest-value "Future directions" items (#1 a11y, #2 spin-delay) are implemented on branch
`worktree-608-cmd-ack`, refining the shipped `jf-control` overlay. FE-only; no backend.

- **#1 A11y.** `busy` now renders **`aria-disabled` (not native `disabled`)** so the control stays
  keyboard-focusable (matches the 596 `unavailable` pattern); a separate **in-flight** flag arms the
  re-entrancy guard (so a second click — even during the pre-spinner delay window — can't re-fire). Added a
  polite `role="status"` visually-hidden region ("Working…", WCAG 2.1 SC 4.1.3), reusing the shared
  `.visually-hidden`. (No-layout-shift width reservation was deliberately NOT forced: hiding slot content to
  reserve width would drop the accessible name on slot-named controls; spin-delay already minimizes how often
  the spinner appears.)
- **#2 Spin-delay.** `runActivation` is now a two-timer state machine: don't show the overlay until
  `showDelayMs` (≈200) has elapsed (a sub-threshold command shows nothing — no flash), and once shown hold it
  for `minVisibleMs` (≈500) so it never flickers. Both thresholds are tunable instance props (tests set them
  to 0 to isolate the state machine; defaults match the researched 200/500).
- **Critical-analysis catch:** spin-delay opened a window where a command is in flight but `busy` is still
  false; guarding re-entrancy on the visual `busy` would have let a double-click in that window double-fire.
  Fixed by keying the guard on the synchronous in-flight flag; a dedicated test (`re-entrancy holds DURING
  the spin-delay window`) locks it.

**Validation (static/unit):** FE `typecheck` clean; `Control`/`Button` tests green; full FE suite **3249
passed**; `controls-a11y` + `message-single-model` + `a11y-closure` gates green; `build -x test
-x verifyGovernanceGates` green (the pre-existing `class-size` drift caveat persists).

**Live UI validation — DONE (2026-06-19).** Verified in Chrome against the running stack, which serves
main's merged source (so a takeover-restart was unnecessary; done non-disruptively via self-contained
client-side probes that never touched the backend or another agent's session). The served `jf-control`
confirmed as the merged code (`showDelayMs` prop + `role="status"` region present). Results:
- **A11y:** during a command → `aria-busy="true"` + **`aria-disabled="true"` with native `disabled=false`**
  (focus-preserving), the button stays **keyboard-focusable while busy**, the polite `role="status"` region
  reads "Working…"; on settle everything clears (status empties).
- **Spin-delay:** a fast command shows **no spinner** (no flash); a pending command shows the spinner.
- **Re-entrancy (the critical-analysis fix):** a second click **during the pre-spinner delay window** does
  not re-fire (`onActivate` called once).
- The `minVisibleMs` hold was **not cleanly measurable live** (Chrome throttles `setTimeout` in background
  tabs — sub-second timing is scrambled), but it is proven **deterministically by the fake-timer unit test**
  (`command pending PAST showDelayMs shows busy, then holds it for minVisibleMs`).

**Merged to `main`** (`954918c79`); worktree + feature branch cleaned up. Nothing outstanding for 608.
