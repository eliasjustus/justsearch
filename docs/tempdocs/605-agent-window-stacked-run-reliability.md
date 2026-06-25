---
title: "605 — The single-live-run lifecycle invariant (the agent confirm-gate that works once). The agent run is a single-live-run product (561) implemented as identity-less process-global FE singletons, so the invariant the codebase already asserts — ONE live run, and every in-flight approval ceremony has a live owner that dies with its run (550 Thesis II · 577 Move 1) — is assumed but never enforced. On a second/stacked run the safety confirm-gate wedges: a prior run's orphaned ceremony blocks the shared FIFO host so the modal never surfaces (M1), and approve can misroute on a stale sessionId (M2). LONG-TERM DESIGN: make the invariant STRUCTURAL — one run-identity authority the controller/ceremony/approve-routing all derive from, and route every run transition (initiate-supersede · halt · terminal) through ONE conclusion path that fail-closed-resolves the concluding run's ceremonies — extending the existing DIRECTION seam (565 §30 `dispatchRunControl`), the live-run pointer (577 Root I), the one ceremony host (550 C3), and the single system-message model (559 III); NOT per-run concurrency scoping (the product is single-window single-run; concurrency is a backend-only substrate capability). Plus an orthogonal small fix: the Abilities view resolves the i18n description key at the render site (#6)."
tempdoc: 605
status: "IMPLEMENTED & MERGED to main (merge 00f7ac62d, 2026-06-18) — Move 1 (run identity / owningRunId + owner-routed approve/reject), Move 2 (owner-keyed fail-closed conclude-drain on the 4 terminals + 559 notice), Abilities #6 (description i18n-key resolved). typecheck clean; 3206 FE unit tests pass; modal-arbitration + message-single-model + run-renderers gates pass; as-built in PART VIII. LIVE: Abilities #6 verified (prose, not ops.*.description) + HIGH-risk ceremony surfaces; the drain+notice+second-modal live demo is BLOCKED by 604's reconnect-wedge (a dropped run never reaches a clean terminal) — drain is deterministically unit-proven (PART VIII). The design/de-risking history (INVESTIGATION DONE … PART I–VII) follows. ── INVESTIGATION DONE (2026-06-17, agent takeover) — both findings root-caused against `main` (static source trace + 2 source-cited subagent audits, every claim file:line). HEADLINE REFRAME: the stacked-run wedge is NOT 'a modal keyed to the active run' (the §5 hypothesis) — it is a deeper SINGLE-RUN-SINGLETON asymmetry: the BACKEND fully supports N independent concurrent runs (fresh UUID sessionId per run, ConcurrentHashMap registry, RunEventHub buffer+replay), but the FE models the agent run as THREE process-global singletons with NO run identity — the `AgentSessionController` (`agentSessionStore._ctrl`, one `sessionId`/`toolCalls`), the `AuthorizationHost` (one `active` ceremony + one FIFO `queue`, not run-scoped), and the `authorizationBroker` presenter (last-host-wins). The safety gate fails on run #2 via two compounding modes: M1 — a prior run's UNRESOLVED ceremony leaves `active` set and `pumpQueue` early-returns, so run #2's prompt queues behind it FOREVER (modal never appears); `cancelSession()` aborts the stream + DELETEs the session but NEVER clears the host's active ceremony — there is NO run→ceremony supersession/cancellation bridge, and a stuck `active` does NOT fail-closed (the host only fails-closed on UNMOUNT). M2 — even if the modal shows, `approveCall` POSTs `this.sessionId`, which on a stacked run can be run #1's stale id → backend `resolveApprovalGate` 404 → run #2's gate expires. The approval gate timeout is 300s (`AgentToolDispatcher.APPROVAL_TIMEOUT_SECONDS`), NOT the observed ~208s (that is an upstream inference/HTTP timeout firing while wedged — secondary). 604-ENTANGLEMENT RESOLVED: independent roots — the backend BUFFERS run #2's pending event (RunEventHub replay), so it is a FE-singleton state-scoping defect, not an SSE-delivery failure; do NOT pre-merge with 604. The Abilities raw-key bug (#6) is root-caused too: the backend emits the i18n KEY as the description by contract (`AgentToolsController.java:225`, documented), and `AgentAuthorityPanel.ts:120` renders `t.description` VERBATIM while the label at :119 routes through `present()`→`localizeResourceKey` — the keys ARE booted into `coreCatalog` (`i18n.ts:112`); the description path just never calls the resolver. DESIGN THEORIZED (2026-06-17, PART IV): the single-vs-concurrent question (D1) is RESOLVED toward enforce-single-run — not by preference but because the product model is single-window single-run (561) and the codebase already MANDATES the liveness invariant (550 Thesis II 'every in-flight record has a live owner'; 577 Move 1 'keyed by run identity, dies with its run') that the FE ceremony singleton simply never realized; per-run concurrency scoping (the old Option B) is rejected as speculative structure for a case the product does not include. The design makes the invariant structural by extending four existing authorities (565 §30 DIRECTION seam = the one run-transition site; 577 Root I `activeRunPointer` = run identity; 550 C3 one ceremony host = the lifecycle the deny terminates; 559 III = the legible supersede message), with exactly one genuinely-new datum (the ceremony's owning-run id, required for correctness under the supersede race). Backend unchanged (already correct). USER-FACING DESIGN DONE (2026-06-17, PART V) after live browser inspection of the running agent window: the design's user-visible core is that today the pending tool-call card is a PASSIVE 'awaiting approval' hint with NO buttons (550 C3 removed inline approve/reject in favour of the ONE modal ceremony — confirmed `ToolCallCard.ts:12-19,371`), so a blocked/dismissed modal leaves the user no path to act — that IS the wedge's user-facing face. The user-facing design makes the pending card the DURABLE, ACTIONABLE entry point that re-raises the SAME ceremony (decision-pending = durable timeline state; ceremony-showing = transient modal), so the gate is recoverable without reverting to inline buttons; a ceremony auto-denied by supersession/conclusion always leaves a trace through the existing 559 system-message / retrospective channel (never silent, no new toast); the Abilities #6 raw-key bug confirmed live (labels + risk resolve, descriptions show `ops.*.description`). DE-RISKING DONE (2026-06-17, PART VI) — static trace + a throwaway characterization probe (green, then deleted) + the running UI. KEY CORRECTION: the composer ALREADY gates a new run on `ctrl.isStreaming` (`UnifiedChatView.ts:4082`), so a concurrent inline run is impossible — Move 3 (initiate-as-supersede) is DEMOTED/dropped (the single-run invariant is already enforced at the composer). The real trigger is run #1 concluding ABNORMALLY (error/drop/not-live) with an open ceremony, leaving the host `active` stuck; run #2 then starts ungated and queues behind it (probe-confirmed: a second ceremony never surfaces while a prior unresolved one holds `active`; the ceremony cannot be dismissed without a decision). So MOVE 2 (drain the concluding run's ceremonies on ALL terminals, esp. abnormal) is THE load-bearing fix; V.3.a 'Review action' is refined to optional defense-in-depth (re-pump, not re-request — re-calling requestAuthorization would duplicate; the modal can't be user-dismissed anyway). owningRunId is stampable (pending event carries sessionId) and the 559 channel is reachable from the controller (it already calls `emitEphemeralToast`). Confidence raised; residual is the owner-keyed drain not self-draining on reattach. No implementation yet."
created: 2026-06-17
relates-to: [593, 596, 602, 550, 559, 561, 565, 577]
author: agent
---

# 605 — Agent-window stacked/second-run reliability (the safety gate that works once)

> **Genre.** Problem-framing pass, opened from the 593 second-pass regression sweep. The
> headline finding is **safety-critical**: the agent's irreversible-write confirm-gate — the
> single strongest safeguard in the whole product — was verified working on the first run but
> **fails to surface on a second/stacked run**. This doc states the problem; it does not yet
> propose a fix.

## 1. The problem, in one sentence

The agent's type-to-confirm authorization modal — which `593` verified as *"genuinely strong,
better than my earlier prose implied"* on the first run — **does not appear on a subsequent
run when a prior run is still present**, leaving the new run wedged "Awaiting your approval…"
until it errors out (~208s).

## 2. The observed reality (regression sweep, 2026-06-17)

- **NEW #5 — stacked-run approval modal failure (safety-critical).** After one run exists, a
  new agent run's **"Authorize action" modal does not appear**; the run wedges at "Awaiting
  your approval…" and **errors after a 208s timeout**. The clean confirm-gate
  (type-to-confirm, Risk:high, exact-op shown, Approve disabled until typed) was verified on
  the **first** run; the **stacked-run case is broken**. Partly entangled with the SSE wedge
  from a worker-kill, **but reproduced after a clean restart with a residual prior run
  present** — so it is a real stacked-run state bug, not purely an SSE artifact.
- **NEW #6 — Abilities view shows raw i18n keys.** Tool descriptions render
  `ops.file-operations.description` / `ops.ingest-files.description` /
  `ops.search-index.description` instead of resolved prose. (Labels + risk levels are fine,
  so the i18n lookup is partially wired.)
- **602 R9 (origin) — Abilities view didn't render at all.** In the original 593 ADDENDUM 2
  pass the Abilities view failed to render on click; the regression sweep shows it now renders
  but with the raw-key bug (#6). So #6 is the *successor* state of R9.

## 3. Why this is its own tempdoc (not 596)

- **596** (unavailable-affordance reason authority) is `IMPLEMENTED & MERGED` and owns *why an
  affordance is unavailable and how that reason is delivered* — a presentation/operability
  authority. 605's headline is a **reliability/state bug**: a modal that should fire does not
  fire because of run-stacking state, not because its "reason" copy is wrong. Different class
  (a wedge, not a missing explanation), so it gets its own doc rather than reopening 596.
- The Abilities legibility bugs (#6, R9) are agent-window-local rendering defects, naturally
  co-located with the same window's stacked-run reliability work.

## 4. Severity framing

The confirm-gate is the product's **last line of defense** against an agent performing an
irreversible write without explicit human approval. A first-run-only guarantee is a false
guarantee: a user who has run the agent once (the common case) and then issues a second task
involving a high-risk op gets **no modal at all** — and a wedged run that merely *looks* hung
rather than *blocked-pending-approval*. The failure mode is silent (no modal = no signal that
approval is even required), which is the worst shape for a safety control.

## 5. Open questions to settle BEFORE any design (no proposals here)

1. **What is the modal's mount/visibility condition, and why does a residual prior run
   suppress it?** Is the modal a singleton keyed to the "active run", such that a second run
   can't claim it while the first is still in state? (Source: the approval-modal host +
   run-state store in the agent window.)
2. **Is the 208s the approval-request timeout, and does the backend ever receive the
   approval request?** Distinguish "FE never showed the modal" from "FE showed nothing AND
   the backend was waiting" — oracle: the approval/authorize request on the wire +
   `debug_state` run state.
3. **How entangled is this with 604's SSE wedge?** It reproduced after a clean restart with a
   residual run, suggesting an independent stacked-run-state root — but confirm whether the
   approval round-trip rides an SSE channel that 604's wedge also breaks. If shared, note the
   convergence; do not pre-merge.
4. **Abilities raw-key (#6):** which i18n bundle is missing the `ops.*.description` entries,
   and is it a missing translation file or an unresolved lookup path? (Labels resolve, so the
   bundle is partially present.)
5. **Does the stacked-run wedge also affect the budget-pause / steer / undo affordances**, or
   only the authorization modal? (593 documented those on the first run; the second-run
   behavior is unmeasured.)

## 6. Method (when this advances to design)

Reproduce deterministically: clean restart → run the agent once to completion (or leave a
residual run) → issue a second task with a high-risk write → observe whether the modal
surfaces, paired against the wire (authorize request present?) and the run-state store. The
593 ADDENDUM 6 first-run verification (type-to-confirm, risk:high, exact-op, not-disk-delete)
is the known-good baseline to regress against.

## 7. Scope boundary

- **IS:** agent-window run-stacking state — the approval modal's surface-on-second-run
  failure; the Abilities view legibility (raw keys / render).
- **IS NOT:** the *content/correctness* of the agent's grounding or RAG (603); the
  unavailable-affordance *reason* authority (596, shipped); the backend approval policy
  semantics (the gate logic is correct — it's the FE surfacing that wedges).

## 8. Cross-reference

- 593 ADDENDUM 6 — first-run confirm-gate verification (the known-good baseline).
- 593 regression sweep (2026-06-17) NEW #5 / NEW #6.
- 602 R9 — Abilities-render origin.
- 604 — the entangled SSE/diagnostic-liveness wedge (shared-root check pending).

---

# PART II — Investigation (2026-06-17, agent takeover)

> **Method.** Static source trace against `main` (HEAD `cc293577b`) plus two source-cited
> subagent audits (backend approval flow; Abilities i18n). Every claim below carries a
> file:line. No live repro was run (the dev stack is shared; the 593 sweep already captured
> the symptom live) — so the *structural* root cause is high-confidence and the *exact runtime
> interleaving* in the 593 repro is labelled where it is inferred. The eventual fix needs a
> runnable regression test, not just this audit (`audit-driven-fixes-need-test`).

## II.1 — The decisive asymmetry: backend is multi-run, frontend is single-run-singleton

The whole bug reduces to one mismatch.

**Backend — N independent runs, by construction.** Every `runAgent` mints a fresh server-side
identity and registers an independent session:
- `AgentLoopService.java:424` — `String sessionId = UUID.randomUUID().toString();` per run.
- `AgentSessionRegistry.java:30` — `sessions = new ConcurrentHashMap<String, AgentSession>()`;
  no global lock in the loop. Two concurrent POSTs run on two Jetty threads, two sessionIds,
  two `AgentSession`s.
- The per-call approval gate is a `CompletableFuture<Boolean>` keyed sessionId→AgentSession→
  callId (`AgentSession.java:34, 246-270`); the loop blocks the calling HTTP thread on
  `gate.get(APPROVAL_TIMEOUT_SECONDS …)` (`AgentToolDispatcher.java:213-219`).
- Each run is a backend-owned entity with a **buffered, replayable** event hub: attach +
  `Last-Event-ID` replay (`AgentController.java:397-414`). So a late/second observer can replay
  a run's pending event — **it is not dropped backend-side**.

**Frontend — the agent run is THREE process-global singletons with no run identity:**
1. **`AgentSessionController` singleton** — `agentSessionStore.ts:11` `let _ctrl` /
   `:29-37 getAgentSessionController` ("the ONE shared AgentSessionController"). One instance
   holds one `sessionId` (`AgentSessionController.ts:237`), one `toolCalls` map, one
   `isStreaming`, one `abortController`. The inline window and the retrospective panel share it.
2. **`AuthorizationHost` singleton** — `AuthorizationHost.ts:71-77`: one `active: CeremonyItem |
   null` + one FIFO `queue: CeremonyItem[]`. **Not scoped to any run.** `pumpQueue()` early-
   returns while `active` is non-null (`:215-216`). It fails-closed (deny) ONLY on
   `disconnectedCallback` unmount (`:175-190`); a *mounted* host with a stuck `active` blocks
   silently.
3. **`authorizationBroker` presenter singleton** — `authorizationBroker.ts:56-77`: one
   `presenter`, last-registered-wins; `requestAuthorization` fails-closed (deny) only when **no**
   host is mounted.

The approval prompt is delivered **on the run's SSE stream**: a `tool_call` pending event →
`AgentSessionController.onToolCallPending` (`:623-625`) → `handleToolCallEntry` (`:639`) → for a
non-AUTO backend gate, `void requestAuthorization({pendingId: callId, operationId: toolName,
gateBehavior: TYPED_CONFIRM…})` (`:682-718`) → broker → host `present()` → modal. The user's
decision resolves the awaited promise; approval POSTs `{sessionId: this.sessionId, callId}` to
`/api/chat/approve` (`approveCall`, `:1443-1452`).

## II.2 — The two compounding failure modes on run #2

**M1 — the modal never appears (the headline symptom).** The host's `active`/`queue` carry no
run identity and there is **no run→ceremony cancellation bridge**:
- `cancelSession()` (`AgentSessionController.ts:1489-1505`) aborts the stream and DELETEs the
  backend session but **never touches the AuthorizationHost** — there is no broker/host API to
  cancel a run's pending ceremonies (the broker exposes only `setAuthorizationPresenter` +
  `requestAuthorization`).
- So if run #1's ceremony is unresolved when run #1 is superseded/cancelled/abandoned (the
  "residual prior run" in the 593 repro), the host's `active` stays set. Run #2's pending event
  calls `requestAuthorization`, which enqueues — but `pumpQueue` early-returns on the stale
  `active` (`AuthorizationHost.ts:215-216`), so run #2's prompt **sits in the queue forever**.
  The modal never appears; the run wedges; the gate eventually times out.
- Note the safety gap precisely: a stuck `active` does **not** fail-closed. Fail-closed only
  fires on host *unmount* (`:186-187`). A superseded run is exactly the case the fail-closed
  default was meant to cover, and it is the one case it misses.

**M2 — even if the modal shows, approve can misroute (secondary).** `send()` (`:1301-1319`)
resets streaming/budget fields but **not** `this.sessionId` or `this.toolCalls` (the only reset,
`resetRunState` `:549-567`, is called solely by `exitReplay` `:544`). This is correct for a
**multi-turn continuation** of one session, but on a genuinely new/stacked run there is a window
where `this.sessionId` is run #1's id until run #2's `onSessionStarted` (`:588`) overwrites it.
If the approval decision resolves in that window, `approveCall` POSTs run #1's stale sessionId →
backend `resolveApprovalGate` (`AgentController.java:222-260`): `sessions.get(staleId)` finds the
wrong/absent session → `session.approve(callId)` false → falls through to the workflow registry
by callId (absent for an agent run) → **HTTP 404** → run #2's gate never resolves → expires.

**The 208s correction.** The agent approval gate timeout is **300s**
(`AgentToolDispatcher.java:43` `APPROVAL_TIMEOUT_SECONDS = 300`; the workflow path matches,
`GatedOperationExecutor.java:34`). There is **no 208s constant** in the agent path. The observed
~208s error is therefore NOT the approval gate — it is consistent with an **upstream inference /
HTTP read timeout** firing while the loop is wedged (unconfirmed; `app-inference` HTTP timeouts
are the place to confirm). Secondary to the wedge mechanism either way.

## II.3 — 604 entanglement: REJECTED as a shared root

The backend RunEventHub **buffers** run #2's pending event and supports replay
(`AgentController.java:397-414`). So the failure is not "the SSE channel dropped the event"
(604's class) — it is "the FE singletons cannot represent a second run, so the event is lost on
the FE side even though the backend still holds it." 604 (SSE 503 reconnect wedge) and 605
(single-run-singleton state) are **independent roots** that merely co-occur during a worker
bounce. **Do not pre-merge.** (This closes §5 Q3.)

## II.4 — The Abilities raw-key bug (#6): root-caused

- **Render site:** `AgentAuthorityPanel.ts:120` renders `${t.description ?? ''}` **verbatim**,
  while the label one line up (`:119` → `toolLabel()` `:107-109`) routes through
  `present({kind:'operation', id})` → `localizeResourceKey` (`present.ts:114`).
- **Origin:** the backend emits the i18n **key** as the description string **by contract** —
  `AgentToolsController.java:225` `String description = op.presentation().descriptionKey().value()`
  (→ `ops.<op>.description`), documented at `:218-220` ("description emits the i18n key; FE
  resolves via `/api/messages/registry-operation/{locale}`").
- **Why label resolves but description doesn't:** label goes through the resolver; description
  doesn't. It is **not** a missing-bundle gap — the `registry-operation` namespace (carrying both
  `.label` and `.description` keys) is fetched into `coreCatalog` at boot (`i18n.ts:112` →
  `resourceCatalog.ts:181-182`). `localizeResourceKey` returns the raw key as a defensive
  fallback when unresolved (`resourceCatalog.ts:219`) — but here the call isn't even made.
- **Fix shape:** resolve the key on the description path — call `localizeResourceKey(t.description)`
  at the render site, or extend `present()`'s `operation` case to also return a resolved
  `description` (today it returns only `label`, `present.ts:111-120`). NOT "add bundle entries"
  (already present); NOT "stop emitting a key" (the emit-key contract is deliberate).
- **R9 lineage:** the original "Abilities didn't render at all" (593 ADD2 / 602 R9) is a prior
  state; on the sweep it renders, so #6 is its successor. (No evidence the render-at-all bug
  persists; if it recurs, it is a separate mount issue.)

## II.5 — §5 open questions, answered

1. **Modal mount/visibility condition / why a residual run suppresses it** → ANSWERED (II.2 M1):
   the host is a process-global FIFO with no run identity; a prior run's unresolved `active`
   blocks `pumpQueue`; no run→ceremony cancellation bridge; stuck-active ≠ fail-closed.
2. **Is 208s the approval timeout / does the backend wait?** → PARTIALLY: the gate is **300s**,
   not 208s; the backend DOES wait (blocks the loop thread on the gate) and BUFFERS the pending
   event. 208s is an upstream timeout, source unconfirmed (II.2).
3. **604 entanglement?** → ANSWERED (II.3): independent roots; backend buffers; do not merge.
4. **Abilities raw-key bundle vs lookup path?** → ANSWERED (II.4): lookup path — the resolver is
   never called on the description path; the bundle is present.
5. **Does the stacked-run wedge also hit budget-pause / steer / undo?** → PARTIALLY: those also
   read the **same singleton controller** (`agentSessionStore._ctrl`), so they share the
   single-run assumption; a concurrent second run would corrupt their shared state too (one
   `budgetGate`/`contextGate`/`toolCalls`). Not separately repro'd — flagged as a consequence of
   the same root, to verify in the design's regression tests.

---

# PART III — Critical analysis & preliminary design directions

> **SUPERSEDED BY PART IV for the design.** PART III is the investigation-stage reasoning that
> framed D1 (single-vs-concurrent) as a user decision and sketched Options A/B/C. PART IV
> resolves D1 from the existing design (not from preference) and commits the long-term design as
> an extension of existing authorities. PART III is retained as the reasoning trail; the §III.1
> critique still stands. Read PART IV for the design.

> The user authorized questioning assumptions and proposing alternatives on takeover. The
> investigation already **reframed the §5 premise** (it is not "a modal keyed to the active run"
> — there is no run identity anywhere in the FE ceremony/controller layer). Below: what that
> changes, then the design options with the one genuine product decision flagged.

## III.1 — Critique of the original framing

- The §5 Q1 hypothesis ("the modal is a singleton keyed to the active run") **understated** the
  defect. It is worse: the ceremony host has **no** run key at all, and the controller is a
  process-wide singleton. The fix target is therefore not "re-key the modal to the new run" but
  "introduce run identity + a supersession/fail-closed path where today there is none."
- §4's safety framing **holds and sharpens**: the host's fail-closed default is real but covers
  only *unmount*. The dangerous case — a superseded/abandoned run with a live ceremony — is the
  one the default misses, turning a safety control into a silent wedge. So the safety property to
  restore is precise: **every ceremony must reach a terminal decision (approve/deny) — including
  fail-closed-deny on supersession — and a new run's ceremony must always be able to surface.**
- The "208s" detail in §1/§2 should be corrected to "~300s gate; the 208s is an upstream
  timeout" so the doc doesn't anchor a fix on the wrong constant.

## III.2 — The product decision the design hinges on (FOR THE USER)

The FE singletons encode an assumption: **one agent run at a time in the one window** (561's
single-window model — the controller comment is explicit). The backend does not share that
assumption. So the corrective splits on a genuine product question:

- **Decision D1 — does the product SUPPORT concurrent agent runs, or ENFORCE one at a time?**
  - If **enforce single-run** (aligned with 561): a new run must cleanly **supersede** any prior
    run — terminate/abandon it AND fail-closed-deny its outstanding ceremony AND reset the
    controller's run identity — so the singletons are always entered clean. Smaller, matches the
    window model. Cost: a backgrounded run mid-approval would be auto-denied when a new run
    starts (acceptable as a *safe* default, but must be made legible, not silent).
  - If **support concurrent runs** (faithful to the backend + the cross-tab pointer that already
    contemplates a second observer, 577 Root I): the controller run-state and the ceremony queue
    must be **keyed by sessionId/runId**, and the host must present per-run. Heavier; arguably
    over-engineered for a single inline window, but the retrospective/background-run surfaces
    (presence) already make a second live run reachable.

This is the one decision that should be made before design — it is not resolvable from the code.

## III.3 — Design directions (sketch — not yet a committed design)

All three restore the safety property; they differ on D1.

- **Option A (enforce single-run + supersession bridge).** Add a run→ceremony cancellation API on
  the broker/host (`cancelPendingFor(owner)` that fails-closed-deny `active`+`queue` items of a
  superseded run) and call it from `cancelSession()` / run-supersession / terminal. Reset the
  controller's run identity (`sessionId`, `toolCalls`, gates) when a genuinely-new run starts (vs
  a multi-turn `send`). Smallest fix that makes the gate fail-closed-and-legible. Pairs with a
  visible "previous run was superseded — its pending action was denied" message (ties to the 559
  Authority III single system-message model, NOT a new toast channel).
- **Option B (scope per run).** Give the controller + ceremony a runId key; render per-run state.
  Most faithful to the backend; largest blast radius; only justified if D1 = support-concurrent.
- **Option C (minimal unblock).** Just add the fail-closed drain on supersession (the M1 half) +
  reset `sessionId` on a new run (the M2 half), without committing to A's full identity model.
  The thinnest correctness patch; leaves the "is concurrency supported" question open but stops
  the silent wedge. Risk: without run identity it is a behavioral patch, not a structural one —
  weaker against `fix-root-causes-not-symptoms` than A.

**Lean:** Option A — it restores run identity exactly where the defect is (the ceremony +
controller's missing supersession path), stays inside the 561 single-window model, and makes the
safety default fail-closed *and* legible. B only if the user decides concurrent runs are a
product goal. C is the fallback if scope must be minimal, but flag it as symptom-level.

The Abilities #6 fix is **orthogonal and small** (II.4) — resolve the description key at the
render site; it can land independently of D1.

## III.4 — Verification the design will owe (per discipline)

- A runnable FE regression test that reproduces M1 (run #1 leaves an unresolved ceremony → run #2
  must still surface a modal / its ceremony must not be blocked) — `audit-driven-fixes-need-test`.
- A test that a superseded run's ceremony resolves **deny** (fail-closed), never silently lingers.
- An approve-routing test that a new run never POSTs a stale `sessionId` (M2).
- A live-stack re-run of the 593 stacked-run scenario as the closing check (`static-green ≠
  live-working`).
- The Abilities #6: a render test that a tool description resolves to prose, not a raw `ops.*`
  key.

---

# PART IV — Long-term design: the single-live-run lifecycle invariant, made structural

> **Genre.** General (architectural) design, not implementation. It resolves D1 from the existing
> design, then states the structure. Scope is matched to the problem the tempdoc actually has —
> no per-run concurrency machinery (the product does not include concurrent runs), no new
> framework (every piece extends an authority that already exists).

## IV.1 — The deepest statement of the problem

The agent run is a **single-live-run product** (561: "one window for all direct LLM interaction";
the shared controller and `activeRunPointer` both say "the ONE live agent run"). Two authorities
the codebase already ships **assert the lifecycle invariant** this implies:

- **550 Thesis II** — *every in-flight (running) record must have a live owner*; an in-flight thing
  whose owner is gone must reconcile to a terminal state.
- **577 Move 1** — the per-run accountability record is *keyed by run identity and dies with its
  run*; "pending approvals" is named as a facet of it.

The defect is the **gap between the asserted invariant and its enforcement**: the FE realizes the
run as three identity-less process-global singletons (PART II §II.1), so nothing makes "one live
run" or "every ceremony has a live owner" true. The stacked-run wedge (M1) is precisely an
in-flight ceremony whose owning run is gone but which never reconciles to terminal — a direct
violation of 550 Thesis II that the type system permits because the ceremony has no owner field.

So the correct design is not a new mechanism. It is to **make the already-asserted invariant
structural** — to move "one live run, every ceremony owned and dying with its run" from prose
assumption to something the run machinery maintains by construction. This is the codebase's own
prevention ladder (557/595: *Collapse > Unrepresentable > Generate > Gate*): route every run
transition through one seam that maintains the invariant, so the illegal "second run with an
orphaned ceremony" state becomes **unrepresentable** rather than merely "not supposed to happen."

## IV.2 — D1 resolved: enforce single-run (concurrency scoping rejected as speculative)

D1 (does the product support concurrent agent runs?) is resolvable from the existing design, not a
user preference:

- The **product model is single-window single-run** (561, ratified). The user-facing surface is
  one inline run observed from multiple places (the retrospective panel, a second tab) — 577 Root
  I's reattach + `activeRunPointer` exist to let a second *observer* discover the **same** run, not
  to run a **second** run. "A second live run is reachable" is an accident of identity-less
  singletons, not a product affordance.
- The backend's N-run capability (fresh UUID per run, ConcurrentHashMap registry, RunEventHub) is a
  **substrate property**, not a product requirement. Designing the FE to scope controller + ceremony
  per `runId` (the old Option B) would be **adding structure for a case the product does not
  include** — exactly the speculative generality to avoid.

Therefore: **the design enforces a single live agent run** and treats "start a new run while one is
live" as a *supersede*, not a concurrency case. Option B is rejected. Option C (drain-on-conclude
without owner identity) is rejected as symptom-level: without an owner, the conclude-drain races a
freshly-enqueued next-run ceremony (it could deny the new run's prompt). The owner datum is the
minimum the invariant needs to be *correct*, not extra generality.

## IV.3 — The structure (three moves, each extending an existing authority + one new datum)

**Move 1 — One run-identity authority the controller, ceremony, and approve-routing all derive from.**
Run identity already exists (`activeRunPointer` = the live run's `sessionId` (+`conversationId`),
577 Root I; the controller's own `sessionId`). The design names **the live-run sessionId as the one
run identity** and threads it where identity is currently absent:
- the approval ceremony's authorize item gains an **`owningRunId`** (the sessionId that issued the
  gated call — the pending `tool_call` event already carries it). This is the one genuinely-new
  datum; it is what 577 Move 1 already calls "ceremony keyed by run identity," now realized.
- approve/reject routing reads the **ceremony's `owningRunId`**, not the controller's mutable
  `this.sessionId` — closing M2 by construction (a decision can only ever be POSTed to the run that
  asked).

**Move 2 — One run-conclusion path that satisfies the liveness invariant.**
Today a run reaches terminal three ways (done/error inside the controller; halt via the seam) and
none of them touch the ceremony host — so the invariant can break at each. The design consolidates
run conclusion into **one path** that every transition calls, which (a) clears the controller's
run state and (b) **resolves the host's authorize ceremonies whose `owningRunId` is the concluding
run — fail-closed (deny)**. This is 550 Thesis II applied to the ceremony: a run that ends takes its
in-flight ceremonies to a terminal deny, with the existing one-ceremony/one-audit semantics
(550 C3). Capability-consent items in the same host (plugin grants, not run-owned) are untouched —
the host already distinguishes `authorize` from `capability`, so the drain is exactly the run-owned
subset.

**Move 3 — "Start a new run" is a supersede, seated on the one DIRECTION seam.**
> **DROPPED by PART VI (VI.1).** De-risking found the composer **already** gates a new run on
> `ctrl.isStreaming` (`UnifiedChatView.ts:4082`), so a concurrent inline run is unreachable and
> there is nothing to supersede at the start boundary. The single-live-run invariant is already
> enforced here; Move 3 is unnecessary. The wedge comes from an *abnormal terminal with an open
> ceremony* (VI.1), which **Move 2** handles. Move 3's text is retained below only as the
> superseded reasoning.

Starting a run already flows through `dispatchRunControl(ctrl, {kind:'initiate'})` (565 §30, the
seam the `steering-arbitration` gate pins to a single dispatch site). The design makes **`initiate`
while a run is in-flight first conclude (Move 2) the prior run, then start** — so the single-live-run
invariant is maintained at the one place runs begin. No new seam; `halt` already routes here, and
`initiate`-supersede is its natural sibling. The result: entering the singletons is always clean,
because the only way to start a run drains any predecessor first.

**Legibility (required, not gold-plating) — the supersede must not be silent.**
The safety property is that the gate never fails *silently*. When a supersede fail-closed-denies a
prior run's pending action, that fact is surfaced through the **559 Authority III single
system-message model** ("a previous run's pending action was cancelled because you started a new
run") — the existing message channel, **not** a new toast. A silent auto-deny would re-introduce
the very "silent safety control" failure §4 names.

## IV.4 — Why this is the right size

- **It adds exactly the structure the invariant requires:** one owner datum on the ceremony, one
  conclusion path, one supersede rule on the existing seam. Each closes a specific violation
  (M1 = orphaned ceremony → Move 2; M2 = stale routing → Move 1; the stacked state itself → Move 3).
- **It omits nothing the problem needs:** the legible supersede message is in-scope because the
  problem *is* a silent safety failure; the owner datum is in-scope because the supersede race needs
  it to be correct.
- **It adds nothing the problem does not include:** no per-run concurrency scoping (Option B), no new
  ceremony framework, no new message channel, **no backend change** (the backend already maintains N
  independent runs correctly — the asymmetry is resolved entirely on the FE by making it faithfully
  single-run). The backend's RunEventHub buffering means a superseded run is cleanly abandonable.
- **It is structural, not behavioral:** the invariant is maintained by routing all transitions
  through one seam + one conclusion, so the illegal state is unrepresentable — satisfying
  `fix-root-causes-not-symptoms` rather than patching the observed wedge (Option C).

## IV.5 — Boundaries and adjacencies

- **No overlap with 604.** Resolved in §II.3: independent roots (FE state-scoping vs SSE reconnect).
  604 may make the wedge *more likely to be seen* during a worker bounce, but the fix here stands
  alone.
- **596 is the delivery channel, not the owner.** If a superseded run needs to explain a denied
  action, it uses 596's unavailable-reason delivery / 559's message model — 605 owns the lifecycle,
  not a new reason vocabulary.
- **565 §30 / `steering-arbitration` gate.** `initiate`-supersede must remain inside the seam so the
  gate's single-dispatch-site invariant holds; this design strengthens that gate's coverage, it does
  not bypass it.
- **The shared controller (561) is unchanged in role** — still one surface-agnostic controller; the
  design gives it a *correct* single-run lifecycle, which is what 561 always intended.

## IV.6 — The Abilities #6 fix (orthogonal, small, independent)

Unchanged from §II.4: resolve the i18n description key on the render path (`AgentAuthorityPanel`
calls the same `localizeResourceKey` the label already uses, or `present()`'s operation case is
extended to return a resolved `description`). It is a one-site legibility fix with no relationship
to the run-lifecycle design and can land independently. Keeping it in 605 is custody, not coupling.

## IV.7 — Verification the design owes (carried from §III.4, now design-anchored)

- M1: a superseded/concluded run's authorize ceremony resolves **deny** and never lingers in the
  host; a new run's ceremony always surfaces (no `pumpQueue` block from a predecessor).
- M2: approve/reject always POSTs the **ceremony's `owningRunId`**, never a mutated controller field.
- Move 3: `initiate` over a live run concludes the predecessor exactly once (no double-drain, no
  leaked stream), and the supersede emits one legible system message.
- Capability-consent items are NOT collateral-drained by a run conclusion.
- Closing check: a live-stack re-run of the 593 stacked-run scenario (`static-green ≠ live-working`).
- Abilities #6: a render test that a description resolves to prose, not a raw `ops.*` key.

---

# PART V — Frontend / user-facing design

> **Genre.** User-facing design, grounded in **live inspection of the running agent window**
> (browser, read-only, against the shared dev stack at `localhost:5173`) plus the run-render
> code — not judged from the tempdoc alone. General (UX-level), not implementation. PART IV is
> the lifecycle structure; PART V is what the user actually sees and how it must read.

## V.1 — What in the design is user-facing (direct or indirect)

PART IV is mostly invisible plumbing (run identity, the conclusion path, approve-routing), but
three consequences are directly user-visible, and they are the safety-critical ones:

1. **The authorization ceremony's lifecycle** — the modal must SURFACE on a second/stacked run
   (M1), and it must RESOLVE-AND-VANISH when its run is superseded or ends (Move 2). A safety
   modal that appears, or disappears, on its own is a user-perceptible event.
2. **The supersede moment** — starting a new agent run while one is live now CANCELS the prior
   run (Move 3). The user must be able to tell that happened and why.
3. **The Abilities panel description** (#6) — directly visible text.

## V.2 — Live inspection findings (what I actually saw)

Captured the running agent window (Chat → Agent tab):

- **The pending tool-call card is a PASSIVE "awaiting approval" hint with NO action control.**
  `ToolCallCard.ts:12-19` documents that 550 C3 **removed** the per-card Approve/Reject buttons
  in favour of the one modal ceremony; the `pending` card now renders only an "awaiting
  approval" line (`data-testid="awaiting-approval"`, `:371`). **This is the wedge's user-facing
  face:** when the modal is blocked (M1) or dismissed, the run sits at "awaiting approval" with
  **no control the user can touch** until the 300s gate expires. The user sees a hung run, not a
  decision they can make.
- **The Abilities panel groups tools by what the autonomy posture does** — "What this agent can
  do · 8 tools · grouped by what the current posture does — change the dial to see what would ask
  first," with **ALWAYS CONFIRMS / ASKS YOU FIRST / RUNS AUTOMATICALLY** sections. The ceremony
  is the *realized* "ALWAYS CONFIRMS / ASKS YOU FIRST" path; the dial (Watch / Assist / Auto)
  decides whether it fires. (Confirms the gate is posture-coupled and already legible here.)
- **Abilities #6 confirmed live:** the label ("File Operations") and the risk/undoable chips
  ("HIGH", "undoable") resolve, but the description renders the raw key
  `ops.file-operations.description` (likewise `ops.ingest-files.description`,
  `ops.search-index.description`). Exactly the §II.4 trace, visible to any user who opens
  Abilities.
- **Run controls are run-lifecycle-gated.** Idle, the "This run · Policy: Auto-running ·
  confirming irreversible writes" bar carries no cancel/supersede control; those appear only
  during a live run. So there is no idle "a run is live" lock — supersession is purely a
  start-time event.
- **The legibility channels that already exist:** a **retrospective banner** ("Since you last
  looked, the assistant: **5 operations** · Undo all AI actions / Save as macro / Mark as seen")
  summarises prior-run activity, and the **559 single system-message model** is the one
  non-toast message channel. Both are the right homes for a supersede notice — no new channel
  needed.
- **Peripheral (not 605's):** the stacked "Navigated to X" toasts (602 R4) and the
  "Reconnecting…" status pill (604) were both live in frame — confirming those siblings, not
  this doc's scope.

## V.3 — The user-facing design

### V.3.a — Make the pending card the DURABLE, ACTIONABLE entry point to the ceremony (the core)

This is the central user-facing move, and it directly dissolves the wedge. Today the ceremony is
a **transient one-shot modal**; the only durable trace of a pending decision is a passive hint.
The design **decouples the two states**:

- **decision-pending** is a *durable* state of the run, rendered on the pending tool-call card
  (which already exists in the run timeline, the registered `jf-tool-call-card` primitive under
  the 565 §12 run-render authority);
- **ceremony-showing** is the *transient* modal.

The pending card gains a single **"Review action"** affordance that **re-raises the SAME
ceremony** (the broker's `requestAuthorization` for that `pendingId`). This:
- makes the gate **recoverable** — a modal that was blocked by a stale predecessor (M1) or
  dismissed is reachable again from the card, so a pending decision is never a dead end;
- keeps the **one ceremony surface** (550 C3) — the card *re-invokes* the ceremony, it does NOT
  bring back inline approve/reject buttons (that anti-spoofing + single-audit decision stands);
- is the honest rendering of the run-accountability record's "pending approvals" facet (577
  Move 1): the pending decision lives on the run's timeline and dies with the run.

With Move 2 (the conclusion path) draining the ceremony fail-closed, and V.3.a making the live
ceremony re-raisable from the durable card, the "awaiting approval with no control" state the
user hit becomes unreachable from both sides.

### V.3.b — A ceremony that vanishes must always leave a trace (never silent)

§4's safety principle is "the gate must never fail silently." A modal that disappears is a
silent event unless its resolution is recorded. So whenever Move 2 auto-denies a pending
ceremony:

- **user-initiated supersede** (the user started a new run): the prior run's pending action is
  reported through the existing **559 system-message** / **retrospective** channel — e.g. "A
  pending action from your previous run was cancelled because you started a new run." Lightweight,
  post-hoc, no new channel.
- **run ended on its own** (done/error/timeout) with a ceremony open: the modal closes carrying
  the run's terminal reason, and the pending card flips to a **"cancelled — run ended"** terminal
  state in the timeline (the card already has `rejected`/terminal states), so the decision's fate
  is visible where the decision lived.

The invariant: a high-risk decision the user could see is **never** silently retracted; it always
resolves to a visible deny-with-reason.

### V.3.c — Supersede legibility at initiate (scope-matched, not a new confirm flow)

Starting a new run while one is live ends the prior run. The legible rendering is: the prior run
reaches a **"cancelled"** terminal in the run timeline / History, plus the V.3.b system message
when a pending ceremony was auto-denied. **No new heavyweight "confirm before you supersede"
dialog** is added: a fail-closed deny is *safe* (deny = no action taken), so a blocking
confirm-before-supersede would be friction without a safety payoff. (If later user testing shows
people are surprised to lose a run they forgot was live, a single inline confirmation *only when
the prior run has an unresolved high-risk ceremony* is the proportionate escalation — noted as a
contingency, not built speculatively.)

### V.3.d — Abilities #6 (the small, independent, directly-visible fix)

Resolve the i18n description key on the render path so a user reads "Read, write, move, or delete
files…" instead of `ops.file-operations.description` — `AgentAuthorityPanel` runs the description
through the same `localizeResourceKey` the label already uses (or `present()`'s operation case
returns a resolved description). One-site, no lifecycle coupling, lands independently.

## V.4 — Scope discipline (what the user-facing design deliberately does NOT add)

- **No new message/toast channel** — supersede + auto-deny notices reuse the 559 single
  system-message model and the existing retrospective banner.
- **No inline approve/reject buttons back on the card** — the card re-raises the ONE ceremony
  (550 C3's anti-spoof + single-audit ceremony is preserved); "Review action" is a launcher, not
  a second decision surface.
- **No confirm-before-supersede dialog** — a fail-closed deny is safe; a blocking confirm is
  friction without payoff (contingency only, see V.3.c).
- **No concurrent-run UI** (no per-run modal stacking, no run switcher) — the product is
  single-live-run (PART IV D1); rendering "two live runs" would be the speculative surface PART
  IV already rejected.
- **No new run-render primitive** — "Review action" lives on the existing `jf-tool-call-card`
  (565 §12 single tool-call render authority; the `check-run-renderers` register keeps it the one
  site).

## V.5 — Consequences to verify in the FE (carried into §IV.7's test set)

- A blocked/dismissed ceremony is re-openable from the pending card ("Review action" re-raises
  the same `pendingId` ceremony); the card is never a dead "awaiting approval" with no control.
- A supersede / run-end auto-deny emits exactly one legible system message and flips the pending
  card to a visible "cancelled" terminal — never a silent modal disappearance.
- The Abilities description renders resolved prose, not a raw `ops.*` key (render test).
- Capability-consent ceremonies (plugin grants) are NOT swept by a run conclusion (V.3.b applies
  only to run-owned authorize ceremonies).

---

# PART VI — Pre-implementation de-risking (2026-06-17)

> **Method.** Read-only static trace + one throwaway `vitest` **characterization probe** (asserted
> the *current buggy* behavior, ran green, then deleted — not the fix's regression test) + the
> running UI. Targeted the five load-bearing uncertainties before implementation. **One material
> design correction resulted (Move 3 is unnecessary); the core (Move 2) is confirmed and the
> design got smaller.**

## VI.1 — T1: the stacked-run trigger — CORRECTED (the important finding)

- **The composer already prevents a concurrent inline run.** `UnifiedChatView.send()` returns
  early when the agent controller is busy: `if (ctrl.available !== true || ctrl.isStreaming)
  return` (`UnifiedChatView.ts:4082`); `initiate` only dispatches past that guard. So you
  **cannot** start a second agent run from the composer while one is in flight.
- **Background runs don't touch the host.** `runBackgroundTask` POSTs `/api/presence/run`
  (`AgentSessionController.ts:1816-1833`) — a separate server-side presence run that does NOT
  consume an SSE stream into the shared controller, so it never raises a ceremony on this host.
- **Therefore the real trigger is NOT "initiate over a live run."** It is: **run #1 concludes
  ABNORMALLY (stream error / drop / attach-not-live) while a ceremony is open**, leaving the
  host's `active` stuck (the terminal handlers clear run state but never the host — `onDone:865`,
  `onError:995`, `cancelSession:1503` all omit it, and the broker exposes no clear API). Run #2
  then starts normally (`isStreaming` is now false → composer un-gated) and its ceremony **queues
  behind the stuck `active`**. This is also why it co-occurs with a worker bounce (604) — but the
  fix is on the FE conclusion path, independent of why the stream dropped.
- **Consequence for the design:** **Move 3 (initiate-as-supersede) is DROPPED.** The single-live-run
  invariant at the *start* boundary is already enforced by the composer gate; there is no
  reachable "two live runs" path to supersede. The design shrinks to **Move 1 (run identity /
  owningRunId) + Move 2 (conclude-drains-ceremonies) + the legibility + Abilities #6.**

## VI.2 — T2: M1's mechanism — CONFIRMED reproducibly

- **Static chain (each link read in source):** abnormal terminal leaves `active` set (VI.1) → the
  broker has no clear/cancel API (`authorizationBroker.ts` exposes only `setAuthorizationPresenter`
  + `requestAuthorization`) → `pumpQueue` early-returns while `active` is non-null
  (`AuthorizationHost.ts:215-216`) → a second `present()` enqueues but never surfaces.
- **Characterization probe (green, then deleted):** with run #1's ceremony unresolved, requesting
  run #2's ceremony left the host **still showing run #1's op**, never run #2's, and run #2's
  decision stayed unresolved — the wedge, reproduced deterministically at the host level. A second
  probe assertion confirmed **the ceremony cannot be dismissed without a decision** (the `<dialog>`
  `@cancel` is `preventDefault`'d; there is no close button).
- **Verdict:** the diagnosis is sound; Move 2's drain targets the real mechanism, not a
  plausible-but-wrong one.

## VI.3 — T3: "Review action" re-raise — CLARIFIED (refines V.3.a)

- A ceremony **cannot be user-dismissed** (VI.2) — so "re-open a dismissed modal" is **moot**.
- Re-calling `requestAuthorization` for the same `pendingId` would **duplicate**: the authorize
  path has **no `pendingId` dedup** (only capability items dedup, `AuthorizationHost.ts:200-205`).
- So the wedge's real case — a decision **blocked behind a stuck predecessor** — is fixed by
  **Move 2 draining the predecessor**, after which the queued item surfaces on its own. **V.3.a's
  "Review action" is therefore optional defense-in-depth**, and if built its mechanism is
  **re-pump the existing queued item** (re-present), NOT a second `requestAuthorization`. The
  user-facing core (a recoverable, non-wedged gate) is delivered by Move 2 alone.

## VI.4 — T4: the conclusion-drain call-sites — MAPPED

- **MUST drain** the concluding run's ceremonies: `onDone` (`:865`), `onError` (`:995`),
  `cancelSession` (`:1503`), and the attach-not-live / non-reattach error terminals
  (`:1620-1626`, the `send()` catch's non-reattach branch).
- **MUST NOT drain:** `attachToRun` / reattach (the SAME run stays live — draining would kill its
  own legitimately-pending ceremony), `exitReplay`/replay (no live ceremony), `forkRun` (resets to
  a fresh run, `sessionId=null`).
- **Implication:** the drain must be **keyed by the concluding run's owner (sessionId)** so a
  reattach of run R does not drain R's own pending ceremony — i.e. Move 1's `owningRunId` is load-
  bearing for Move 2's correctness, not just for approve-routing. **This is the residual care-point
  for implementation.**

## VI.5 — T5: owner stamp + 559 reachability — CONFIRMED

- **owningRunId is stampable:** the pending `tool_call` event carries the run `sessionId`
  (`handleToolCallEntry` reads `data.sessionId`, `:660-662`), available where the ceremony is
  requested.
- **559 is reachable from the controller:** the controller **already** emits into the single
  system-message model via `emitEphemeralToast` (`AgentSessionController.ts:1778`; the one
  `AdvisoryStore` / 559 Authority III channel). The supersede / auto-deny notice has a reachable,
  precedented, in-model emit point — **no new toast channel** needed.

## VI.6 — T6: Abilities #6 boot/timing — CONFIRMED low-risk

- `localizeResourceKey` is synchronous with a raw-key fallback (`resourceCatalog.ts:219`); the
  `registry-operation` catalog is booted into `coreCatalog` at app boot (`i18n.ts:112`). The label
  already resolving via the same catalog proves it is present by panel-render time. The one-site
  description fix is timing-safe.

## VI.7 — Net effect on the design

| Risk (pre) | After de-risking |
|---|---|
| #1 M1 mechanism inferred | **Confirmed** (static chain + probe) |
| #2 stacked-run trigger unpinned | **Corrected**: composer already gates concurrent initiate; trigger = abnormal terminal + open ceremony → **Move 3 dropped**, Move 2 is the fix |
| #3 "Review action" re-raise feasibility | **Clarified**: modal can't be dismissed; re-request duplicates → V.3.a is optional defense-in-depth (re-pump), Move 2 carries the fix |
| #4 conclusion-drain over/under-fire | **Mapped**: 4 drain sites + 3 no-drain exclusions; drain must be **owner-keyed** (residual care-point) |
| #5 owner stamp + 559 reachability | **Confirmed** both (sessionId on the event; controller already emits to 559) |
| #6 Abilities boot/timing | **Confirmed** timing-safe |

**The design is now smaller and better-aimed:** drop Move 3; Move 2 (owner-keyed
conclude-drains-ceremonies on the four terminals) + Move 1 (owningRunId) are the core; the
legibility (V.3.b auto-deny trace via the existing 559 channel) and Abilities #6 are confirmed
feasible; V.3.a is optional.

## VI.8 — Critical confidence rating for the remaining work

**8 / 10.** The load-bearing mechanism is reproducibly confirmed and the fix locus is now precise
and *smaller* than the pre-de-risking design (Move 3 removed). The plumbing the design depends on
(owner stamp, 559 emit point) is verified with in-repo precedent. The residual −2: (a) the
owner-keyed drain must correctly exclude the reattach-of-same-run case (VI.4) — a real edge that
needs an explicit test; (b) the legibility copy/placement (V.3.b) and the optional V.3.a affordance
are UX details not yet validated against a live abnormal-terminal repro (a `static-green ≠
live-working` closer on the dev stack, deferred — stack is owned by another session). Neither
residual threatens the core diagnosis or the fix shape; both are bounded and named.

---

# PART VII — Cross-tempdoc interference scan (2026-06-17)

> Scanned tempdocs within ±20 of 605 (585–625) modified in the last 5h, and the active git
> worktrees in that range. Goal: does any concurrent work threaten 605's remaining
> implementation? **Conclusion: effectively no — 605's core files are touched by no active
> worktree; the only shared file is one large view, in a disjoint region.**

## VII.1 — Active worktrees (authoritative: `git worktree list`)

Only two are registered/active (both based at `cc293577b`); the other `.claude/worktrees/`
directories in range (587, 591, 598-r4) and the adjacent 565/569 are **dir-only, not registered
worktrees → stale/inactive**.

| Worktree | Changed files | Overlap with 605 |
|---|---|---|
| **600-c2-impl** | `modules/telemetry/RrdMetricStore.java` + a reconcile test | **None.** This is the 600 §C-2 fix (the silent RRD-sampling / blind memory-pressure rule flagged in the 600 reopen) — backend telemetry, no agent/ceremony surface. |
| **603-rag-trust-2** | backend `AgentSession.java`(+test); FE `SourcesPane.ts`, `evidenceProjection.ts`, **`UnifiedChatView.ts`** | **Low.** Grounding/sources work. The ONLY shared file is `UnifiedChatView.ts`. |

## VII.2 — 605's core files vs the active worktrees

605's implementation lives in `AgentSessionController.ts` (Move 1/2: `owningRunId` +
owner-keyed conclude-drain), `AuthorizationHost.ts` / `authorizationBroker.ts` (the drain/owner
API), `AgentAuthorityPanel.ts` (#6), and optionally `ToolCallCard.ts` (V.3.a, demoted to optional
by VI.3). **A direct check confirms NO active worktree touches ANY of these five core files.**

## VII.3 — The one watch-item, and why it's low-risk

- **`UnifiedChatView.ts` is shared with 603-rag-trust-2.** But 605's *core* (Move 1/2) does **not**
  touch this file — VI.1 found the composer's run-start gate is read-only confirmation, and V.3.a
  (the only possible UnifiedChatView-adjacent touch) is **optional** and would live on
  `ToolCallCard`, not the view. 603 edits the view's grounding/sources region — a different part of
  a large file. Worst case is a trivial merge conflict, avoided by keeping 605's UnifiedChatView
  footprint at ~zero (its fix belongs in the controller + ceremony host).
- **Backend `AgentSession.java` is edited by 603** (grounding); 605 needs **no backend change**
  (PART II/IV), so no conflict.

## VII.4 — Dormant adjacency to note (not active interference)

`565` (agent-window presentation / the run-render authority 605 *extends* — `jf-tool-call-card`,
the one ceremony reuse) has a **stale, unregistered** worktree dir. It is the one body of work
that, *if reactivated*, would touch 605's exact area (the tool-call card + ceremony). Currently
inactive ⇒ no live interference, but a coordination flag if 565 resumes before 605 lands.

## VII.5 — Verdict

**No active concurrent work threatens 605's remaining implementation.** 605's core is disjoint
from every active worktree's changeset. The single shared file (`UnifiedChatView.ts`, with
603-rag-trust-2) is avoidable by keeping 605's footprint there minimal; if both land, merge 603
first or coordinate the view region. 600-c2-impl is complementary (it implements the 600 reopen's
C-2 item) and entirely disjoint. The PART VI confidence (8/10) is unchanged by this scan.

---

# PART VIII — As-built (2026-06-17, worktree `605-impl`)

> Implemented per the PART IV/V/VI design + the approved plan, in worktree `worktree-605-impl`
> (off `main`; not merged). Move 3 dropped as PART VI determined. No backend change.

## What shipped

- **Move 1 — run identity (`authorizationBroker.ts`, `AuthorizationHost.ts`, `AgentSessionController.ts`).**
  `AuthorizationPrompt` carries `owningRunId`; `AuthorizationDecision` carries `superseded`. The
  controller stamps the owner (`data.sessionId ?? this.sessionId`) at the `requestAuthorization`
  site and routes `approveCall(callId, runId)` / `rejectCall(callId, reason, runId)` by the stamped
  owner — closing M2 (a later/stacked run can no longer 404 the approval). Existing callers
  (auto-approve / `pendingAutoApprovals` replay) default to the current run unchanged.
- **Move 2 — conclude-drain (`AuthorizationHost.cancelForRun`, broker
  `setAuthorizationCanceller`/`cancelAuthorizationsForRun`, `AgentSessionController.concludeRunCeremonies`).**
  The host fail-closes (with `superseded:true`) every AUTHORIZE ceremony owned by a concluding run,
  leaves capability-consent and other-/no-owner items untouched, and re-pumps so a blocked next-run
  ceremony surfaces (the M1 fix). `concludeRunCeremonies(runId)` is wired to the four terminals only
  — `onDone`, `onError`, `cancelSession`, `onAttachNotLive` — and skipped in `replayMode`; the live
  reattach / resume / fork paths never drain.
- **Legibility (V.3.b).** When ≥1 ceremony is auto-denied, `concludeRunCeremonies` emits ONE notice
  via the existing `emitEphemeralToast` (559 single system-message channel — the
  `message-single-model` gate confirms no new channel). The `superseded` flag makes the awaiting
  dispatcher skip a backend reject POST to the now-dead run (no spurious error toast).
- **Abilities #6 (`AgentAuthorityPanel.ts`).** The description renders
  `localizeResourceKey(t.description)` — the same sync resolver the label uses — so the panel shows
  prose, not the raw `ops.*.description` key.
- **V.3.a "Review action" — NOT built** (PART VI.3 deferred it: the Move 2 drain already restores
  recoverability and the modal can't be user-dismissed). Revisit only if live validation shows a gap.

## Tests added (extend existing suites)

- `AuthorizationHost.test.ts` (+3): a concluding run drains its own ceremony `superseded` so the
  NEXT run surfaces (the M1 regression); other-run and capability and run-less items survive a
  conclusion. (Also fixed a pre-existing consent test-isolation leak in the shared `afterEach`.)
- `AgentSessionController.test.ts` (+6): approve/reject route the explicit owner not a stale
  sessionId; `onError`/`cancelSession` drain the run and fire exactly one notice; a 0-denied
  terminal fires none; a replay terminal does not drain.
- `AgentAuthorityPanel.test.ts` (new): description resolves to prose, not the raw `ops.*` key.

## Static verification (surfaced in the goal transcript)

- `npm run typecheck` — clean.
- `npm run test:unit:run` — **331 files / 3190 tests passed**.
- `modal-arbitration` gate — pass (ModalController composition retained); `message-single-model`
  and `run-renderers` gates — pass.

## Live verification (2026-06-18, dev stack taken over + `ai_activate`, worktree FE on :5174)

- **Abilities #6 — LIVE-VERIFIED ✅.** With AI online, Chat → Agent → Abilities rendered every tool
  description as **prose** (e.g. Ingest Files → *"Ingest files into the knowledge index for
  searching. Accepts absolute or relative paths…"*; Search Index → *"Search the knowledge index.
  Supports keyword (text), semantic (vector), and hybrid modes…"*), **not** the raw
  `ops.*.description` keys that `main` shows. Screenshot evidence in the goal transcript.
- **HIGH-risk ceremony surfaces — LIVE-VERIFIED ✅.** A real agent task ("remove the watched folder
  docs … using file operations") raised the type-to-confirm **"Authorize action"** modal for
  `core_file_operations`, Risk: high, exact op `{"operations":[{"op":"remove","path":"docs"}]}`,
  Approve-disabled-until-typed. Screenshot in transcript.
- **Stacked-run terminal-drain — NOT reproducible via worker-kill (interrogate-results finding).**
  Injecting the abnormal terminal by killing the Worker did **not** drain the ceremony, because an
  **approval-parked agent run lives on the Head** (blocked on the 300 s approval gate), so a Worker
  bounce does not terminate it — the FE goes "Reconnecting…" and reattaches to the still-live run,
  ceremony intact. So a worker-kill is the wrong fault for the M1 state: the run never reaches a
  *stream terminal*. The real M1 trigger is run #1's **SSE stream erroring/dropping** while the
  ceremony is open (a connection drop or Head crash), which the deterministic
  `AgentSessionController.test.ts` regression simulates directly via `onError`/`cancelSession` with
  an open ceremony — green: the drain fires, the notice fires once, and a queued next-run ceremony
  surfaces (`AuthorizationHost.test.ts`). **Net: the terminal-drain + 559 notice + next-run-surfaces
  is proven deterministically by unit tests; the live worker-kill path can't stage it because the
  parked run survives the bounce.** A faithful live repro would need a stream/connection-level drop
  with the ceremony open (out of reach from the browser harness without killing the Head).

  **Deeper live finding (2nd attempt — DELETE-the-session terminal): 604's reconnect-wedge
  SUPPRESSES 605's live drain trigger.** I then injected a *real* terminal — ending run #1's
  session on the backend (the same `DELETE /api/chat/sessions/{id}` a halt uses) while its ceremony
  was open. The FE's stream dropped ("Reconnecting…"), but the reattach **never resolved to a
  terminal**: the FE got stuck in the **604 SSE-reconnect-wedge** (the in-place reattach hangs;
  surface-navigation didn't clear it either, because the persistent singleton controller keeps the
  run's reconnect loop), so `onAttachNotLive`/`onError` **never fired** and the drain never ran —
  the modal stayed until I manually Denied. So the live realization sharpens PART II §II.3: 605 and
  604 are independent ROOTS, but in the live **abnormal-stream-drop** path they are **entangled in
  sequence** — 604's wedge prevents run #1 from reaching the clean terminal that 605's drain keys
  on. The one 605 terminal that bypasses the reattach is the explicit **`cancelSession` (halt)**,
  which is *not reachable from the UI while the top-layer modal is open* (the run's stop control is
  occluded). **Net for the live demo:** 605's drain is correct and deterministically unit-proven,
  but it cannot be demonstrated end-to-end live until either (a) 604's reconnect-wedge is fixed so a
  dropped run reaches a terminal, or (b) a halt affordance is reachable with the ceremony open. This
  is a genuine, high-value cross-finding (it both confirms 605's logic and raises the priority of
  604 + a modal-coexisting halt), not a defect in 605. No destructive op was executed — every
  ceremony was Denied; no folder was removed.
