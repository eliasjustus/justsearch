---
title: "604 — Diagnostic-surface liveness & recovery: the monitoring UI is least reliable exactly when you need it. The dashboard you'd open during an incident (System Health) stays stuck on 'Reconnecting…' for >75s after a worker bounce while the backend is fully READY, because its SSE streams return 503 and never re-establish in place — an in-place reload does NOT recover it, only navigating away and back does. Plus the idle-time over-pessimism: CONNECTION flaps to 'Reconnecting…' while /api/health reports healthy. Promotes 602 Tier-1 (R1+R2) to its own pass. Surfaced by the 593 walkthrough ADDENDUM 3 §S1 + the 2026-06-17 regression sweep (Still-Present #7/#8)."
tempdoc: 604
status: "IMPLEMENTED & MERGED to main (2026-06-18; commit fefb053c2 + merge) — Moves A (EnvelopeStream self-heal: readyState-CLOSED reconnect + heartbeat-absence watchdog + backoff/resume), C (agent-stream out-of-band heartbeat in AgentController + LivenessWatchdog in AgentSessionController), D (gen-stream-liveness-constants sibling generator + StreamLivenessWindows + 15-controller single-authority migration), B (HealthLitView badge poll-trust + debounce). VERIFICATION: Java build (PMD/spotbugs/check) green, :modules:ui:test green, ui-web typecheck + 3190 unit tests green (new EnvelopeStream-reconnect + LivenessWatchdog + HealthLitView tests), all governance gates green, gen --check idempotent. LIVE (PART VII): Move C backend heartbeat CONFIRMED LIVE (`event: heartbeat` on a parked agent run, after fixing the stale-jar pitfall via explicit installDist); Move A substrate CONFIRMED LIVE-LOADED (Logs SSE `head-log/stream → 200` through the modified EnvelopeStream; 17 live consumers inherit the self-heal). TARGETING CORRECTION (PART VII.3): the live System-Health surface is `HealthSurface` (already poll-trusting via subscribeAiState), NOT `HealthLitView` (debug/demo/ResourceView) — so Move B touched a secondary component; the live R2 ('reconnecting…' at idle) is the deferred B.3 poll-staleness (not reproduced live, stays deferred). Net live-relevant contribution = A (substrate self-heal) + C (agent-stream liveness). Pre-existing main class-size red (StatusLifecycleHandler/CoreOperationCatalog, not 604) logged in observations. ── PRIOR HISTORY: INVESTIGATION DONE + DESIGN PROPOSED (2026-06-18, agent takeover; PART II + PART III). ROOT CAUSE ESTABLISHED (spec-authoritative + source-traced, every claim file:line): the FE delegates live-stream re-establishment to the TRANSPORT, but no transport guarantees a timely recovery/terminal signal. (a) SSE streams use the browser `EventSource`, whose `EnvelopeStream.handleError` trusts native auto-reconnect — but per the WHATWG spec a 5xx/abnormal response makes the UA FAIL the connection (`readyState=CLOSED`) and NEVER reconnect; `EnvelopeStream` has no `readyState` check and `start()` early-returns on a non-null dead `source`, so the stream wedges in-place forever (R1). (b) The agent-run reattach uses a held-open `fetch` body (`consumeShapeStream`) with NO idle/read deadline, so `reader.read()` blocks indefinitely on a silent-but-open connection → `attachToRun` never resolves → the run never concludes (§9). KEY REFRAME of §5: Q1 (where the 503 originates) is NOT on the critical path — the health/condition streams are NOT capability-gated (static trace), so the 503 is environmental/transient, and the defect is that the FE cannot recover from ANY abnormal close; Q2's nav-vs-reload 'asymmetry' is a timing artifact for re-mounted surfaces (both re-create the EventSource; reload landed inside the bounce window) and only STRUCTURAL for the singleton-held agent run; Q4 RESOLVED — R1 and R2 are the SAME EventSource-error mechanism at two amplitudes (transient drop → native recovers → UI flickers = R2; 5xx/abnormal → native gives up → UI wedges = R1). DESIGN (PART III): FE-owned re-establishment + liveness deadlines at the substrate seam — (A) `EnvelopeStream` self-heals on `readyState===CLOSED` with bounded backoff + resume-token replay (fixes R1 for ALL SSE surfaces at one site); (B) connection verdict trusts the cheap `/api/health` poll, with a debounce so a sub-second native reconnect stops flickering 'Disconnected' and the staleness threshold tolerates poll jitter (R2/Q3); (C) bound the agent-run reattach with a liveness deadline so a hung reattach reaches onError/onAttachNotLive promptly (§9 functional locus → unblocks 605's terminal-drain). DESIGN REFINED (PART IV): Move C corrected from an IDLE deadline to an ESTABLISHMENT deadline — the agent SSE stream has NO heartbeat (`AgentSseWriter` is event-only), so a run blocked at an approval gate emits nothing for up to 300s; an idle deadline would false-abort the exact healthy case, whereas an establishment deadline (disarm on the first replayed frame) cleanly catches only the transport hang. Move B.3 (poll-pill staleness) DEFERRED as likely-unneeded + 595-governed; verdict-derivation gate constrains Move B to a presentation re-wire (no second verdict authority). LONG-TERM DESIGN DONE (PART V, 2026-06-18): the structural shape is NOT new machinery — it COMPLETES the codebase's existing liveness law (heartbeat/progress-stamp + stale-window backstop → recover; already applied to worker jobs via `recoverStuckJobs`, polled-state via `PolledStateLiveness`, the FE `isInFlightLive`, the poll channel via `aiStateStore.computeStaleness`, and the generated `LivenessWindows` window-coherence authority) over the ONE family that only half-has it: the live-stream channels. Diagnostic SSE has the heartbeat EMITTER (`SseEnvelopeWriter`) but no FE observer-backstop; the agent stream (`AgentSseWriter`) lacks even the emitter. Design: (1) make the heartbeat emitter universal — extend the SSE-envelope heartbeat to the agent stream (the keystone: a run blocked at an approval gate stays provably live, so PART IV's bespoke establishment-deadline DISSOLVES into the general law); (2) generalize the poll-channel watchdog into one reusable FE liveness-backstop that `EnvelopeStream` + the agent fetch-consumer arm and that OWNS re-establishment (resume-token replay); (3) derive one coherent stream liveness window from a single generated authority (mirror 575 §17 `LivenessWindows`/gen-liveness-constants — drift-proof); (4) the ONE connection verdict reads unified channel-liveness (closes R2/Q3 with no second verdict authority). PART III/IV are re-cast as the realization of this one authority, not three independent patches. Title/heading reframed: 'diagnostic-surface' → general 'live-channel liveness & re-establishment'. Scope judgment: structure matched to a genuinely multi-channel defect; explicitly NO new transport abstraction, NO FE per-run multiplexing (single-window single-run per 561/605), NO backend worker-recovery change (only the standard heartbeat added to the agent stream). CONFIDENCE PASS DONE (PART VI, 2026-06-18): each load-bearing assumption converted to cited evidence — U2 (5xx⟹EventSource CLOSED-no-retry) CONFIRMED by a throwaway Chromium probe (Move A's readyState gate is real & discriminating); U3 (agent heartbeat) CONFIRMED feasible iff emitted OUT-OF-BAND (like `attach_not_live`, not via RunEventHub — no conformance/replay collision); U4 CORRECTED (the true hang is a run parked at an unresolved gate, held by the 30-min `ATTACH_MAX_MINUTES` latch not the 300s gate; refines Move C to liveness-DETECTION that hands the parked-alive case to 605, not a forced terminal); U5 (verdict gate) CONFIRMED green (pure `retrieval ===` scan; a liveness INPUT to the single seam is idiomatic — `Stability` already has `channel-stale`); U6 CONFIRMED gap-free for FE-initiated resume + surfaced a latent stale-token bug in native reconnects that Move A also fixes; U7 REFINED to a SIBLING liveness-window generator (the 575 one's 3-value lease law doesn't fit a 2-value stream watchdog). No finding invalidated the design; three sharpened it. Only U1 (exact 503 trigger) still rests on inference, and the design is structured to be independent of it. No implementation yet — the one remaining oracle is the live §6 worker-bounce (optional, needs dev-stack coordination; also demos 605's drain). Still GATES 605's live drain demo."
created: 2026-06-17
relates-to: [575, 585, 593, 595, 602, 605]
author: agent
---

# 604 — Live-channel liveness & re-establishment (the FE owns no recover-when-the-stream-dies backstop)

> **Heading reframed (2026-06-18, PART V).** Opened as "diagnostic-surface liveness," the
> investigation (PART II) + the 605 cross-evidence (§9) established the defect spans **every live
> channel** (diagnostic SSE, the idle connection verdict, **and** the agent-run stream), not just
> the System-Health surface. The long-term design (PART V) treats it as one authority. The
> diagnostic surface is the *most visible* victim; the agent-run stream is the *highest-functional*
> one. Original problem framing (§§1–9) preserved below as written.

> **Genre.** Problem-framing + investigation-scoping pass, opened from the 593 second-pass
> regression sweep. This doc takes custody of the **highest-severity orphan** in the 602
> residual catalog (Tier-1, R1+R2) and states the problem precisely enough that a design
> round can begin. It does **not** yet propose a fix.

## 1. The problem, in one sentence

The **System Health** surface — the one a user or operator opens *because* something looks
wrong — is the surface most likely to render a false "Reconnecting…/down" state while the
backend is provably healthy, and it does not self-heal on an in-place reload.

## 2. The observed reality (593 + regression sweep)

Two findings, same family (the FE live-state machine is stickier and more pessimistic than
backend truth):

- **R1 — request-lifecycle / SSE wedge after a worker bounce.** In the original walkthrough
  (593 ADDENDUM 3 §S1) a hard worker-kill was recovered by the Head **in seconds** (new PID,
  data intact, `/api/health` READY, a real search worked). Yet the System-Health surface
  **stayed "Reconnecting…" for ~2 minutes**, across multiple **in-place page reloads**. The
  network tab showed ~110 API requests stuck `pending`. It **cleared only on navigating away
  and back**, never on reload.
- **R1 (mechanism refined, 2026-06-17 sweep Still-Present #8).** On the re-run the wedge
  reproduced (>75s while backend READY), and the mechanism sharpened from "110 pending" to:
  the **SSE streams** `/api/health/events/stream` and `/api/condition-recovery-index/stream`
  **return 503 and do not re-establish in place** after the worker generation restarts.
- **R2 — idle-time over-pessimism (593 ADDENDUM 3 baseline; sweep Still-Present #7).** Even
  with the API reporting `is_healthy:true` / lifecycle READY, the CONNECTION panel and status
  pill intermittently flip to "Reconnecting…". An SSE/poll reconnect artifact where the FE
  connection display is more pessimistic than reality — visible even at steady idle.

## 3. Why this is its own tempdoc (not 595, not 602-catalog)

- **595** owns the *settled-vs-provisional rendering* of an observed transition — i.e. *given*
  a state signal, render it truthfully (the data-loss-illusion fix, the verdict polarity).
  604 is upstream of that: the **live-connection / SSE re-establishment mechanism itself**
  wedges, so 595's renderer is fed a stale "disconnected" signal that never clears. 595
  can render perfectly and the surface still lies, because the *input* never recovers.
- **602** only **catalogs** R1+R2 (Tier-1) as un-homed problem statements; its own disposition
  note says R1+R2 are "the only Tier-1 work" and should become "one monitoring-surface
  liveness/recovery doc." 604 is that doc.

## 4. Severity framing (the walkthrough's own words)

593's verdict on S1: *"the dashboard you'd open during an incident is the one most likely to
mislead you — the opposite of what an operator needs."* The backend resilience is excellent
(fast, lossless worker respawn); the defect is entirely in the **monitoring surface's
liveness layer**. That asymmetry — robust system, misleading instrument — is what makes this
the highest-severity orphan: it converts a non-incident (clean auto-recovery) into a
perceived outage.

## 5. Open questions to settle BEFORE any design (no proposals here)

1. **Where does the SSE 503 originate?** Is it the Head's SSE handler refusing while the
   worker generation is mid-restart, or the browser EventSource not retrying after a 503
   (vs a network drop)? (Oracle: server-side stream logs + the network tab's EventSource
   lifecycle.)
2. **Why does navigation recover but reload not?** A fresh navigation re-mounts the surface
   and opens new EventSources; an in-place reload apparently re-attaches to a dead stream
   state. Is the reconnect/backoff state held somewhere that survives reload (a store, a
   module singleton) but not navigation?
3. **What is the authoritative liveness signal the surface SHOULD trust?** `/api/health`
   returns READY throughout — is the surface gating its "connected" verdict on the SSE
   stream's health rather than on the cheap poll that already works? (R2 suggests the
   connection verdict is too tightly coupled to the SSE channel.)
4. **Is R2 the same root as R1 at lower amplitude?** Both are SSE/poll reconnect artifacts;
   confirm whether one fix addresses both or they're independent.
5. **Blast radius of any reconnect change** — the same SSE streams feed the Logs and
   Activity tabs (593 ADD3 baseline), which worked fine. A fix must not regress those.

## 6. Method (when this advances to design)

Oracle-paired fault injection, exactly as 593 ADDENDUM 3 validated: inject a real worker
bounce, then compare the surface's rendered connection state against `/api/health`,
`debug_state`, and the **EventSource lifecycle in the network tab** at timestamps — so
"the UI looked stuck" stays a precise, reproducible measurement (backend READY + SSE 503 +
clears-on-nav-not-reload), not an impression.

## 7. Scope boundary (what this is and is NOT)

- **IS:** the diagnostic-surface live-connection / SSE re-establishment layer — detection
  latency, the in-place-reload-doesn't-recover wedge, idle over-pessimism.
- **IS NOT:** the *backend* worker recovery (already fast & lossless — do not touch); the
  *rendering* of a known transition (595); the *content* of what's degraded (600); the
  capability-source truthfulness (598).

## 8. Relationship to the entangled agent finding (605)

The 2026-06-17 sweep's NEW #5 (agent approval modal wedges on a stacked run, 208s timeout)
is **partly entangled** with this SSE wedge but reproduced after a clean restart with a
residual prior run — so it is tracked in its own doc (605). If 604's SSE-reconnect
investigation finds a shared root, note the convergence there; do not pre-merge the two.

**UPDATE (2026-06-18):** 605 is now implemented & merged, and its live verification produced
**concrete new evidence about THIS wedge** — see §9. Convergence verdict: 604 and 605 are
**independent roots** (605 = FE single-run-singleton state-scoping; 604 = the SSE/poll reconnect
mechanism), confirming "do not pre-merge." BUT they are **entangled in sequence** on the live
abnormal-stream-drop path: 604's wedge prevents a dropped agent run from reaching a clean
terminal, which **suppresses 605's terminal-drain** — so 604 now gates the live demonstration of
605's fix. That raises 604's priority.

## 9. Live evidence from 605 (2026-06-18) — the wedge has a FUNCTIONAL blast radius, not just a cosmetic one

While live-verifying 605 (the single-live-run ceremony invariant) against a taken-over dev stack
with AI online, I tried to inject an "abnormal terminal" on a live agent run (so 605's drain
would fire). Every injection ran into **this** wedge. The observations sharpen 604 materially:

- **The reconnect-wedge strikes the AGENT-RUN stream, not only the diagnostic surface.** When an
  agent run's SSE stream drops abnormally — a Worker-kill mid-run, **or** ending its session via
  `DELETE /api/chat/sessions/{id}` out from under the FE — the FE goes **"Reconnecting…"** and the
  reattach (`AgentSessionController.attachToRun` → `/api/chat/agent/{sessionId}/attach`) **hangs
  without ever resolving to a terminal** (`onError`/`onAttachNotLive` never fired). Consequence:
  **the dropped run never concludes** — it hangs with its open approval ceremony until the backend
  300 s gate timeout. So the wedge is **not merely "the dashboard misleads you while the backend
  is fine"** (§4); it can **wedge a real, functional agent run indefinitely**. This widens 604's
  severity from monitoring-surface-cosmetic to **functional run-liveness**.
- **"Clears on navigation" is SURFACE-SCOPED, not universal (refines §2 R1 / §5 Q2).** Navigating
  away and back did **not** clear the agent-run reconnect loop, because it is held by the
  **process-global singleton `AgentSessionController`** (the run-state controller persists across
  surface navigation, unlike a re-mounted diagnostic view). So the 593-era "clears only on
  navigating away/back, not in-place reload" holds for the System-Health *surface* but **not** for
  the singleton-held agent-run stream — a second, stickier locus of the same reconnect family.
- **The wedge can FREEZE the renderer (new).** A `javascript_tool` (`CDP Runtime.evaluate`) call
  **timed out after 45 s** while the reconnect loop was active — i.e. the wedge can lock the tab's
  main thread, not just show a stale label. (Recovery needed the tab-drain procedure.)
- **Same SSE/poll-reconnect family as R1/R2 (refines §5 Q4).** The agent-run reattach hang, the
  System-Health "Reconnecting…", and the idle CONNECTION over-pessimism are all the in-place
  reconnect-doesn't-re-establish mechanism — now observed across *three* loci (diagnostic surface,
  idle connection pill, **and** the agent-run stream).

**Design implication for 604.** Whatever fixes the in-place SSE/reattach re-establishment must
cover the **singleton-held agent-run stream** too (not just re-mounted diagnostic surfaces): a
dropped run must reach `onError`/`onAttachNotLive` promptly (so it concludes) instead of looping
"Reconnecting…" forever. Until 604 does this, **605's terminal-drain cannot be demonstrated live**
on the stream-drop path (it is deterministically unit-proven; 605 PART VIII records the blocker).
This also means 604's "monitoring surface" framing should be **broadened to a general
live-stream/reattach re-establishment authority** — the diagnostic surface is the *most visible*
victim, but the agent-run stream is the *highest-functional-impact* one.

---

# PART II — Investigation & root cause (2026-06-18, agent takeover)

> **Method.** Static source trace against `main` (HEAD `ea9402f17`) + two source-cited subagent
> code maps (FE streaming/connection; BE SSE handlers) + a WHATWG-spec confirmation of the
> browser `EventSource` failure semantics. Every claim carries a file:line or a primary-source
> citation. No live repro yet (the dev stack is shared; §6's oracle-paired fault injection is the
> closure step) — so the *structural* root cause is high-confidence and any *exact runtime
> interleaving* is labelled where inferred. Per `audit-driven-fixes-need-test`, the fix needs a
> runnable regression test, not just this audit.

## II.1 — The one root cause, three loci

**The FE delegates live-stream *re-establishment* and *liveness-detection* to the transport, and
no transport gives a timely recovery/terminal signal.** Two transports, two failure shapes, same
abdication:

### Locus 1 + 2 (R1/R2) — `EventSource`-backed SSE: `EnvelopeStream`

The generic SSE consumer `EnvelopeStream<T>` wraps a browser `EventSource`
(`modules/ui-web/src/shell-v0/streaming/EnvelopeStream.ts:98`) and, on `error`, does **nothing
but reflect disconnected state**, with the load-bearing comment:

```ts
// EnvelopeStream.ts:200-207
private handleError(): void {
  // EventSource handles its own reconnection. We just reflect the
  // disconnected state until the next frame or `open` event.
  if (this.isConnected) { this.isConnected = false; this.notify(); }
}
```

That comment is a **false assumption for the 5xx / abnormal-close case.** Per the WHATWG HTML
spec §9.2 (Server-sent events): a `200 OK` + `text/event-stream` response reconnects after a
*network drop*, but **any other response (a 5xx such as 503, a wrong MIME type, or a hard fail)
causes the UA to *fail the connection* — set `readyState` to `CLOSED` and fire `error` — and
once failed, "it does not attempt to reconnect."** (Confirmed: html.spec.whatwg.org §9.2;
MDN `EventSource: error_event`; javascript.info/server-sent-events — "If the server responds
with an error … then EventSource will stop reconnecting.")

`EnvelopeStream` never inspects `source.readyState`, and `start()` early-returns when
`this.source` is non-null (`EnvelopeStream.ts:93-96`) — and a `CLOSED` `EventSource` is still a
non-null object. So after a single 5xx the stream is **permanently wedged**: `isConnected=false`,
no further frames, no reconnect, and the only escape is `stop()`+`start()` — which only happens
when the consuming Lit view *re-mounts*. That is exactly the System-Health surface
(`HealthLitView.startStream()` → new `EnvelopeStream` per mount) wedging on "Disconnected"
(`HealthLitView.ts` conn-badge reads `this.isConnected`) — **R1**.

- **R2 is the SAME mechanism at lower amplitude (settles §5 Q4).** A *transient* SSE blip (a
  network-level drop, not a 5xx) makes the UA reconnect natively — but `handleError` has already
  flipped `isConnected=false`, so the CONNECTION panel / pill flickers "Reconnecting…" for the
  sub-second reconnect even though nothing was actually wrong. R1 = the *permanent* tail of the
  same error path (UA gives up); R2 = the *transient* head (UA recovers, UI over-reports). One
  fix family covers both: self-heal on `CLOSED` **and** debounce the disconnected paint so a
  fast native reconnect doesn't surface as a user-visible "Reconnecting…".

- **Same seam covers far more than Health.** Every `EnvelopeStream` consumer inherits the defect
  at one site: `/api/health/events/stream` (`HealthLitView`),
  `/api/condition-recovery-index/stream`, `/api/intent/stream`
  (`api/intent/bootIntentStreamBridge.ts` — module singleton),
  `/infra/capabilities/stream` (`api/contract/contractEventsBridge.ts`), the action-ledger and
  runtime-context streams. Fixing `EnvelopeStream` self-healing fixes **all** of them — high
  leverage, single locus.

### Locus 3 (§9) — `fetch`-body-backed agent run: `consumeShapeStream`

The agent run does **not** use `EventSource`. `AgentSessionController.attachToRun(sessionId)`
(`controllers/AgentSessionController.ts:1627`) POSTs `/api/chat/agent/{sessionId}/attach` via
`consumeShapeStream` (`api/streams.ts:537`), which reads a held-open response body in a loop with
**no idle/read deadline**:

```ts
// streams.ts:577-579
while (true) {
  const { done, value } = await reader.read();   // ← blocks forever on a silent-but-open conn
  if (done) break;
```

On an abnormal drop, `send()`'s catch reattaches **once** (`AgentSessionController.ts:1377-1389`,
the `reattachedThisRun` one-shot guard). If that reattach connects to a backend that holds the
socket open but emits nothing (the run is itself wedged behind a stuck approval ceremony — 605
M1 — so it produces no events until the backend `APPROVAL_TIMEOUT_SECONDS = 300s` gate), then
`reader.read()` blocks, `attachToRun` never resolves, and the run **never reaches a terminal**
(`onError` / `onAttachNotLive` never fire). That is the §9 "reattach hangs … the dropped run
never concludes." Because `AgentSessionController` is a **process-global singleton**
(`agentSessionStore._ctrl`), navigation does **not** re-mount it — confirming §9's "clears on
nav is surface-scoped only; the agent run is structurally stickier."

## II.2 — Settling §5's open questions (and reframing two of them)

1. **Q1 — Where does the SSE 503 originate? → NOT on the critical path.** Static trace:
   `/api/health/events/stream` (`HealthEventStreamController.handle` → `SseEnvelopeWriter.attach`)
   and `/api/condition-recovery-index/stream` are **NOT** in `RouteCapabilityPolicy.RULES`
   (only `/api/knowledge/*`, `/api/indexing/*`, `/api/chat/agent` are capability-gated —
   `RouteCapabilityPolicy.java:50-54`), and the handlers read **Head-local** stores
   (`conditions.currentSnapshot()`, `occurrences.recent()`), not the worker. So the 503 is
   **environmental/transient** during the bounce (e.g., a native `EventSource` reconnect attempt
   racing a moment of Head backpressure — the "~110 pending" — or a transient non-200), not a
   deliberate gate. **The fix does not depend on Q1's exact answer**: the defect is that the FE
   cannot recover from *any* abnormal close. Q1 stays a live-experiment item to *characterize*
   the trigger, but it is no longer a design blocker. *(This is the main critique of the original
   §5 framing, which treated Q1 as a must-settle-first gate.)*
2. **Q2 — Why does nav recover but reload not? → a timing artifact for surfaces; structural only
   for the singleton.** Both nav and full reload re-create the `EventSource`. The 593 observation
   that *reload* didn't recover is consistent with the reload landing **inside** the still-bouncing
   503 window (the fresh `EventSource` 5xx-closes again with no self-heal), while the later nav
   happened after the backend was READY. There is no store/module state that survives reload to
   "remember disconnected" for the re-mounted surfaces. The genuinely structural non-recovery is
   the **singleton-held agent run** (§9), which nav cannot re-mount.
3. **Q3 — What liveness signal should the surface trust? → the cheap poll, not the SSE channel.**
   `/api/health` (and the `aiStateStore` status/inference polls) report reachability independently
   of SSE attachment. The Health surface currently derives its top-level connection badge from the
   **SSE stream's** `isConnected` (`HealthLitView` conn-badge), so a wedged *enrichment* channel
   reads as a hard "Disconnected" while the backend is provably READY. Design B separates
   *backend-reachable* (poll authority) from *live-updates-attached* (SSE channel, a secondary
   "updates paused, retrying" hint).
4. **Q4 — Is R2 the same root as R1? → YES** (see II.1: two amplitudes of the same `EventSource`
   error path). One fix family.
5. **Q5 — Blast radius.** The Logs/Activity tabs that "worked fine" in 593 ADD3 are also
   `EnvelopeStream`/SSE consumers; they worked because their streams did not happen to 5xx in that
   run. Design A *helps* them (self-heal) and must not regress them — the resume-token replay
   (`?since=`) is already in `EnvelopeStream.urlWithResumeToken()`, so reconnect is gap-free by
   construction. Regression guard: the existing `EnvelopeStream.test.ts` fake-EventSource harness
   (extended with a `readyState`) plus the live §6 injection covering Logs/Activity.

## II.3 — The R2 staleness arithmetic (poll-based pill, a separate amplitude)

Distinct from the SSE-based Health badge, the **global status pill** derives from
`aiStateStore.computeStaleness()` (`state/aiStateStore.ts:250-265`): `stale` when
`now - max(lastInferenceSuccess, lastStatusSuccess) > STALE_THRESHOLD_MS (15_000)`. The inference
poll is 5s and the status poll is 10s, so at healthy idle `lastSuccess` refreshes every 5s and
never ages out — meaning a pill flip to "Reconnecting…" at idle implies **≥3 consecutive
inference-poll misses**, i.e. the poll itself transiently failed, not just clock drift. Design B's
poll-side half should therefore (a) gate `stale` on *consecutive observed failures* rather than a
bare wall-clock threshold a single jittered poll can trip, and (b) keep the 15s retention for the
*don't-wipe-to-zero* behavior (595) while not letting one slow fetch read as "Reconnecting…".

---

# PART III — Proposed design (FE-owned re-establishment + liveness deadlines)

> **Thesis.** Move re-establishment and liveness-detection **from the transport to the FE
> substrate seam**, so every live stream self-heals *in place* (no navigation, no process
> restart) and a dropped run reaches a terminal promptly. Three moves, each at a single
> high-leverage seam. Backend unchanged (the worker respawn is already fast & lossless — §7).

## Move A — `EnvelopeStream` self-healing reconnect (fixes R1 for ALL SSE surfaces)

In `EnvelopeStream.handleError()`, inspect `this.source.readyState`:

- `CONNECTING` (the UA is retrying natively, a transient drop) → reflect disconnected **debounced**
  (see Move B) and let the native reconnect proceed; the next `open`/`frame` clears it.
- `CLOSED` (the UA has *failed the connection* — 5xx/abnormal/wrong-MIME) → the FE now **owns**
  recovery: `stop()` the dead source, then schedule a `start()` after a **bounded exponential
  backoff** (e.g. 0.5s → 1s → 2s → … capped at ~10s, with small jitter). Because `start()` already
  appends the held `resumeToken` as `?since=`, replay is **gap-free**.
  - **DECIDED (2026-06-18, user):** reconnect is **infinite with a capped interval** (~10s + jitter),
    not capped-attempts-then-give-up. The diagnostic surface self-heals the moment the backend
    returns; the ~10s cap matches the existing 5–10s poll cadence, so the steady-state cost of a
    genuinely-down backend is one comparable retry per interval. A "give up → reload to retry"
    terminal state is explicitly rejected — it reintroduces the manual-recovery step 604 exists to
    eliminate.

Notes / guards:
- A `closedByUs` flag so an intentional `stop()` (view unmount) does **not** schedule a reconnect.
- The reconnect timer is cleared in `stop()` and on successful `open`.
- Idempotency/leak safety: never hold two live `EventSource`s; reset backoff on a clean `open`.
- **Single locus** = every consumer (Health, condition-recovery, intent, contract-events,
  action-ledger, runtime-context) self-heals with zero per-surface change.
- *Alternative considered & rejected:* a per-surface reconnect in each Lit view — rejected as
  N-fold duplication of exactly the substrate logic `EnvelopeStream` exists to own (AHA: unify
  what shares a reason to change).

## Move B — Connection verdict trusts the poll; debounce the SSE-disconnect paint (R2/Q3)

1. **Decouple reachability from SSE attachment on the Health surface.** The top-level connection
   verdict reads the `/api/health`/status-poll authority (already the `aiStateStore` source of
   truth); the SSE channel's `isConnected` becomes a *secondary* "live updates paused — retrying"
   hint, never a hard "Disconnected" while the poll says READY. (Keeps 595's verdict-single-source
   discipline — no second verdict authority; this is a *presentation* of the existing one.)
2. **Debounce the disconnected paint** so a sub-second native reconnect (R2 head) doesn't surface
   as "Reconnecting…": only paint disconnected after the error has persisted past a short grace
   (e.g. ~750ms–1s), shorter than Move A's first backoff so a real wedge still shows promptly.
3. **Poll-pill staleness (II.3):** gate `stale` on consecutive poll *failures* rather than a bare
   15s wall-clock the first jittered poll trips; retain the 15s last-known retention for 595.

## Move C — Agent-run reattach liveness deadline (§9 functional locus → unblocks 605)

Bound the one-shot reattach so a hung reattach can't loop forever:

- Wrap `attachToRun`'s `consumeShapeStream` with a **liveness deadline**: if no frame/terminal
  arrives within a bounded window (e.g. ~10–15s) **and** the backend is reachable per the poll,
  `abort()` the fetch and drive the run to a terminal — `onAttachNotLive` if the run is gone, else
  `onError` — so the run **concludes** instead of hanging to the 300s gate.
- Mechanism options (pick at impl): an `AbortController` armed on a timer that resets on each
  received frame (idle-deadline), or a wall-clock cap on a frame-less reattach. Prefer the
  idle-deadline (tolerates a slow-but-live run that is actually streaming).
- This is the *same principle* as Move A (FE owns the liveness deadline; don't trust the transport
  to signal), applied to the `fetch`-body transport. It makes the singleton-held agent run reach a
  clean terminal in place — which is the precondition 605's owner-keyed terminal-drain needs to be
  demonstrated live (605 PART VIII blocker).

## Sequencing, scope, and verification

- **Independent of the backend** (§7 boundary preserved). No `RouteCapabilityPolicy` / SSE-handler
  change is required by this design; Q1's 503 trigger can be characterized live but is not gated on.
- **Tests (per `audit-driven-fixes-need-test`):** (A) extend `EnvelopeStream.test.ts`'s
  `FakeEventSource` with a settable `readyState`; assert a `CLOSED` error schedules a reconnect
  (a second factory call) with the `?since=` token, and that a `CONNECTING` error does **not**;
  assert `stop()` cancels a pending reconnect. (B) unit-test the debounce + poll-failure staleness.
  (C) a regression test that a frame-less reattach hits the deadline and fires
  `onError`/`onAttachNotLive` (drives the run to terminal) within the window.
- **Live closure (§6 method, the real oracle):** inject a worker bounce; at timestamps compare the
  rendered connection state vs `/api/health` + `debug_state` + the network-tab `EventSource`
  lifecycle; confirm (1) Health self-heals in place (no nav), (2) the idle pill stops flickering,
  (3) a dropped agent run reaches a terminal promptly — and that Logs/Activity (§5 Q5 blast radius)
  do not regress. This live run is also where 605's terminal-drain finally gets its live demo.
- **Open implementation question for the user:** Move A's backoff/cap constants and Move C's
  deadline window are tunable — propose 0.5s→10s backoff and a ~12s idle reattach deadline as
  starting points, to be confirmed against the live bounce timing. *(Move C's "idle deadline"
  framing is corrected in PART IV — see below.)*

---

# PART IV — Design refinements (2026-06-18, post-review)

> Three refinements after a closer read, in response to "refine the design." The biggest is a
> **correction to Move C** that would otherwise have shipped a false-abort bug.

## IV.1 — Move C MUST key on *establishment*, not *idle* (correction, load-bearing)

**The flaw in the first draft.** Move C proposed an *idle* deadline ("no frame for ~12s →
abort"). But the agent SSE stream has **no heartbeat**: `AgentSseWriter` writes only when an
`AgentEvent` is published through the hub (`AgentSseWriter.java:71-99`; there is no
`scheduleAtFixedRate`/heartbeat anywhere in the agent path — unlike the 15s heartbeat the
diagnostic `SseEnvelopeWriter` streams carry). A run **legitimately blocked at an approval gate
emits nothing for up to `APPROVAL_TIMEOUT_SECONDS = 300s`**. An idle deadline would therefore
**falsely abort the single most important healthy case** — a run waiting on the very
confirm-gate ceremony 605 is about. Silence is *not* a wedge signal for the agent stream.

**The corrected signal: did the reattach *establish*?** A successful reattach to a live run
**replays the run's buffered events immediately** on connect — the backend hub buffers + replays
from `Last-Event-ID` (`AgentSseWriter.java:93-99` stamps the `id:`; `RunEventHub` replay; 605
PART II confirmed "the backend BUFFERS run #2's pending event"). So a healthy reattach to a
run-at-a-gate yields its pending `tool_call` **replay frame within ~1s**. The §9 hang is the
*absence of even that* — the connection is held open with no response/first-frame at all. So
the deadline must be an **establishment deadline**: armed when the reattach starts, **disarmed on
the first received frame** (replay / `attach_not_live` / any event). If it fires (no first frame
within, say, ~8–10s while the poll says the backend is reachable), the reattach is a transport
hang → `abort()` and drive the run to a terminal (`onError`/`onAttachNotLive`). Once the first
frame lands, the stream may sit quiet indefinitely (an approval-gate wait is legitimate) — no
further deadline.

- This covers **both** sub-cases of the §9 hang: a `fetch()` that never resolves its Response
  (no headers) *and* a `reader.read()` that never yields a first byte (headers, silent body).
- *Must-verify at impl:* that a reattach to a live-but-gated run does reliably receive an
  immediate replay frame (it should, per the hub-replay contract) — characterized in the live
  §6 run. If for some reason it does not, the fallback is a tiny backend **attach-ack frame**
  (the alternative below).
- *Alternative considered:* a backend "attached" ack frame emitted on every successful attach
  (so the FE always gets an immediate establishment signal independent of buffered events).
  Cleaner contract, but it is a backend change and the FE-owned establishment deadline already
  works off the existing replay — **recommend FE-only**, keep the ack as fallback.

## IV.2 — Defer the R2 *poll-pill* staleness tweak (Move B.3) unless the live run shows it

The R2 symptom has two amplitudes (II.1, II.3): the **SSE-side** Health badge flicker (the
`EventSource` error path — fixed by Move A self-heal + Move B.2 debounce) and the **poll-side**
status-pill flip (`aiStateStore.computeStaleness`). The poll-side flip requires **≥3 consecutive
missed inference polls** at healthy idle (5s poll vs 15s threshold), i.e. a *real* transient, not
clock drift — so the SSE-side half is almost certainly the R2 the walkthrough actually saw.
`computeStaleness` is **595-governed** (the don't-wipe-to-zero retention rides the same 15s).
**Recommendation: ship Move B as the SSE-side decoupling + debounce (B.1/B.2) only; treat the
poll-staleness change (B.3) as deferred/optional**, to be done *only if* the live §6 run
reproduces a poll-pill flicker with all polls succeeding. Avoids perturbing 595's retention for a
symptom that may not exist.

## IV.3 — Governance constraints that scope the edits (must-not-regress)

Editing `modules/ui-web/src/shell-v0/**` trips several gates; two bind this design:

- **`check-verdict-derivation.mjs`** — the system-health verdict is derived ONCE in
  `computeVerdict` and the `readiness.retrieval`-forming predicate may live ONLY in the seam +
  the declared `readinessNotice` projection. **Move B must NOT add a second verdict/readiness
  site**: the Health connection badge must *consume* `aiStateStore` (the existing poll authority),
  not compute a new reachability verdict from the SSE channel. The change is a *presentation*
  re-wire (badge reads the existing phase/verdict instead of raw `isConnected`), not a new
  authority — keeping the gate green by construction.
- **`consult-doc-hint` / `maintain-doc-hint`** — shell-v0 is a governed region pointing at
  ADR-0032 + `docs/explanation/27` (presentation kernel). If Move A/B change documented
  live-connection behavior, update that doc in the same change (or record that no documented
  behavior changed). Move A is substrate-internal (self-heal) — likely no doc change; Move B's
  badge-semantics change is the one to check against the kernel doc.

**Net refined plan:** Move A (self-heal `EnvelopeStream`, all SSE surfaces) and Move C
(establishment-deadline reattach) are the two load-bearing, low-controversy fixes; Move B ships
as SSE-side decoupling+debounce with B.3 deferred. Move A and C are independent and could land
separately (A unblocks every diagnostic surface; C unblocks 605's live drain).

---

# PART V — Long-term design: complete the existing liveness law over the live-stream family

> **Method.** This part steps back from the locus-by-locus fixes (PART III/IV) to ask the
> structural question: *what is the correct long-term shape?* The answer is **not new machinery.**
> The codebase already has a mature **liveness law**; the live-stream channels are the one family
> where it is only half-applied. The right-sized design **completes that law over this family** by
> extending three structures that already exist. Investigated against `main`, not assumed.

## V.1 — The reframe: this is the codebase's own liveness law, missing on one family

The codebase already enforces ONE recurring law everywhere a thing can be "running but actually
dead": **a live thing emits a positive liveness signal (heartbeat / progress-stamp); an observer
arms a stale-window backstop and, when the signal stops, reclaims or recovers.** It is applied,
uniformly and deliberately, across the system:

- **Worker indexing jobs** — `IndexingLoop` heartbeat (`last_updated` refresh) + the
  `KnowledgeServer` `recoverStuckJobs` reaper (575 §17).
- **Polled-state (install / pack-import)** — `PolledStateLiveness.isStaleRunning(state, updatedAt,
  now, staleMs)` + a lazy reaper (575 §17 Face C; "the backend analogue of the FE `isInFlightLive`
  authority").
- **FE in-flight badge** — `isInFlightLive` (575 §15 Pillar-3b).
- **The connection verdict's POLL channel** — `aiStateStore.computeStaleness` already runs exactly
  this backstop for polls: `lastSuccess` (a poll success *is* the heartbeat) + a `clockTick` timer
  + `STALE_THRESHOLD_MS` → `stale`/`disconnected` (`state/aiStateStore.ts:250-265`).
- **The liveness-window single-authority** — `LivenessWindows` (heartbeat cadence / display-stale /
  reaper-stale) is **generated from one source** (`governance/observed-happening.v1.json` →
  `gen-liveness-constants.mjs`), shared by producer + FE + reaper so the windows **cannot drift**
  (575 §17 Face A).

**The live-stream channels are the lone exception.** They have the *emitter* half but not the
*observer-backstop* half — and one channel lacks even the emitter:

| Live channel | Emitter (heartbeat) | Observer backstop (recover on absence) |
|---|---|---|
| Diagnostic SSE (health, condition, intent, capabilities, action-ledger, runtime-context) | ✅ `SseEnvelopeWriter` heartbeat (15s) + resume-token replay | ❌ `EnvelopeStream` tracks `isConnected` but arms **no** stale-window watchdog and owns **no** re-establishment |
| Connection verdict (poll) | ✅ poll success | ✅ `aiStateStore` staleness watchdog *(the one place the law IS applied on the FE)* |
| Agent-run stream | ❌ `AgentSseWriter` is event-only — **no heartbeat** | ❌ `consumeShapeStream` blocks on a silent body with no deadline |

So R1, R2, and §9 are not three bugs — they are **the same missing half of an existing law**, on
the three live channels that never got it. The poll channel already shows the FE *can* run this
backstop; the design generalizes that proven pattern to the rest.

## V.2 — The design: complete the law by extending three existing structures (not replacing)

**One authority — "every live channel emits a heartbeat and is observed by a stale-window backstop
that owns re-establishment" — realized by extending what exists:**

1. **Make the heartbeat emitter universal (extend the SSE-envelope contract to the agent stream).**
   The `connected/snapshot/heartbeat/reset/closing` + resume contract (`SseEnvelopeWriter`) is the
   established positive-liveness signal. The agent-run stream is the **only** live channel that does
   not emit it (`AgentSseWriter` writes only on a real `AgentEvent`). Give the agent stream the same
   heartbeat lifecycle frame. This is the keystone: it lets a run **legitimately blocked at an
   approval gate** stay provably *live* (heartbeats keep arriving) while a genuinely hung/dead
   reattach goes silent — the exact distinction PART IV's bespoke "establishment deadline" was
   reaching for. With a heartbeat, that special case **dissolves into the general law** (the sign
   the structure is right): no establishment-vs-idle special-casing — *absence of heartbeat past
   the window* is the one uniform "dead" signal for every channel.

2. **Add the observer-backstop to the FE live-stream consumers (generalize the watchdog the poll
   channel already runs).** `aiStateStore`'s staleness watchdog is the reference implementation;
   generalize its shape into one reusable per-channel liveness backstop that:
   - **`EnvelopeStream`** arms for SSE channels — on stale-window breach (or a transport `error`
     resolving to `readyState===CLOSED`, the fast-path), it **owns re-establishment**: reopen with
     the held `resumeToken` (gap-free replay already exists), infinite retry at a capped interval
     (DECIDED, PART III). This is Move A, now *positive-liveness-driven* so it also catches the
     **silent** wedge (EventSource OPEN-but-backend-silent; the "110 pending" / frozen-renderer
     case) that pure error-reaction misses.
   - **the agent fetch-consumer** (`consumeShapeStream` / `AgentSessionController` reattach) arms
     for the agent stream — heartbeat resets it; absence → bounded re-establishment (reattach) →
     terminal (`onError`/`onAttachNotLive`) so the run concludes (Move C, now an instance of the
     general law rather than a bespoke deadline). The existing one-shot `reattachedThisRun` guard
     becomes the watchdog's bounded-retry policy.

3. **Derive one coherent liveness window from a single authority (mirror 575 §17 Face A).** A
   watchdog window must be derived from the producer's heartbeat cadence × tolerance, or the two
   drift (raise the heartbeat to 30s, leave the watchdog at 20s → false recovery). The 15s SSE
   heartbeat is today a hand-set magic number on the producer with **no** consumer counterpart.
   Declare the **stream liveness window once** (heartbeat cadence + FE stale window) and generate
   both ends — exactly as `LivenessWindows`/`gen-liveness-constants.mjs` already does for the
   action-lifecycle window — so producer cadence and consumer backstop are coherent by
   construction. (This is the drift-proofing the codebase already prefers; the alternative — two
   hand-set constants — is the drift class 575 §17 eliminated.)

## V.3 — The connection verdict derives from unified channel-liveness (closes R2/Q3 structurally)

With every channel reporting a uniform liveness state, the **one** system-health/connection
verdict (595's `computeVerdict`) gains a single, well-defined input model: **poll reachability
(existing) + per-channel liveness (new, uniform)**. The System-Health badge and status pill
*consume* that verdict; they stop deriving "Disconnected" from a single channel's raw
`isConnected`. This dissolves R2/Q3 at the root — a wedged *enrichment* channel can no longer
masquerade as "backend down" while the poll says READY — **without** adding a second verdict
authority (the `verdict-derivation` gate stays green; the change is presentation + one new input to
the existing seam, not a new derivation site).

## V.4 — Why this scope is correct (the judgment, both directions)

**Structure the problem requires (do not omit):** the defect is genuinely multi-channel — the
tempdoc itself (§9) broadened to "a general live-stream/reattach re-establishment authority," and
every locus is a *real, observed* instance, not a hypothetical. A unifying liveness authority is
therefore matched to the problem, not speculative. Three patches that recover three different ways
would re-fork a law the codebase deliberately keeps single (the same drift 575 §17 closed for
job-liveness).

**Structure the problem does NOT include (do not add):**
- **No new transport/`LiveChannel` greenfield abstraction.** The design extends `EnvelopeStream`,
  `aiStateStore`'s watchdog, the `SseEnvelopeWriter` heartbeat, and the `LivenessWindows`
  generation pattern — all extant. The shared piece is a small liveness-backstop primitive, not a
  rewrite of the streaming substrate.
- **No FE per-run liveness multiplexing / run registry.** The product is single-window single-run
  (561; 605 enforces it at the composer). The agent stream joins the law as **one** emitter; there
  is no need to model N concurrent live runs on the FE.
- **No backend worker-recovery change** (§7 boundary intact). The only backend touch is adding the
  *already-standard* heartbeat to the agent stream — the same contract six other streams already
  emit — not new recovery machinery.

**The size of the change is the outcome of that judgment:** modest backend (one heartbeat added +
one declared liveness window generated), focused FE (one reusable backstop primitive armed by two
existing consumers + the verdict reading a new uniform input). Major where the law is genuinely
missing; nothing where it already holds.

## V.5 — Relationship to PART III/IV and to 605

- PART III/IV are **not superseded — they are re-cast as the realization of this one authority**,
  not three independent fixes. Move A = "`EnvelopeStream` arms the channel backstop + owns
  re-establishment"; Move C = "the agent stream becomes a heartbeat emitter + its consumer arms the
  same backstop" (this **supersedes** PART IV's establishment-deadline special case — the heartbeat
  makes it unnecessary); Move B = "the verdict reads unified channel-liveness." The transport-error
  fast path (PART II Move A) stays as an *optimization* layered on the watchdog (recover instantly
  on a known `CLOSED` rather than waiting a full window), not the primary signal.
- **605 entanglement, restated structurally:** the agent stream joining the liveness law is exactly
  what lets a dropped/abnormal run reach a clean terminal in place — the precondition 605's
  owner-keyed terminal-drain needs for its live demonstration (605 PART VIII blocker). 604 and 605
  stay **independent roots** (604 = channel liveness; 605 = run-identity/ceremony scoping) but the
  heartbeat is the seam where 604 unblocks 605.

## V.6 — Open boundaries (unchanged by this part)

- **Q1 (503 origin)** stays a *characterize-not-gate* item — the positive-liveness backstop recovers
  from any abnormal/silent close regardless of the trigger, so Q1 is not on the critical path.
- **Live §6 validation** remains the closure oracle: inject a worker bounce, confirm every channel
  self-heals in place (no nav), the idle verdict stops flickering, and a dropped agent run reaches a
  terminal promptly — and that Logs/Activity (§5 Q5 blast radius) do not regress.
- **No implementation in this pass** — PART V is the long-term design theory; the user has not
  asked for code. The realization order, when authorized, is: (1) generalize the FE liveness
  backstop + arm it in `EnvelopeStream` (recovers all diagnostic SSE — highest leverage); (2) add
  the agent-stream heartbeat + arm its consumer's backstop (unblocks 605); (3) route the verdict
  through unified channel-liveness; with the liveness-window generation underpinning all three.

---

# PART VI — Pre-implementation evidence (2026-06-18, confidence pass)

> **Purpose.** Before committing to implementation, convert each load-bearing *assumption* into
> *evidence* (the approved confidence plan). Method: static source traces (3 parallel agents,
> file:line) + a throwaway Chromium `EventSource` probe (the U2 oracle). The one item still
> un-run is the live worker-bounce (§6) — it needs the shared dev stack, which at the time of
> this pass was held by another owner (`callerIsOwner:false`, fresh lease); not injected
> unilaterally per branch-safety. **No feature code written.**

## VI.1 — Findings (each row was low/medium-confidence before this pass)

- **U2 — 5xx ⟹ `EventSource` CLOSED, no retry: CONFIRMED by direct probe.** A throwaway local
  server returning **503** on an SSE path, opened in Chromium (the WebView2 engine family): the
  `error` handler fired **exactly once** with `readyState === CLOSED(2)` and the connection
  **never reconnected** (4s snapshot: still `CLOSED`, 1 error). The control — a mid-stream
  **socket drop** — fired `error` with `readyState === CONNECTING(0)` and **auto-reconnected**
  repeatedly. So Move A's gate is real and discriminating: `CLOSED` ⟹ browser gave up (FE must
  self-heal); `CONNECTING` ⟹ browser retrying (reflect-disconnected, let it). **Move A
  validated.** *(The truly authoritative confirmation is the same observation in the app's own
  webview during the live §6 run — still pending — but Chromium is the correct engine family.)*

- **U3 — agent-stream heartbeat is feasible & contained: CONFIRMED (static), with a required
  path.** Safe **iff emitted out-of-band** — written directly to the socket via
  `AgentSseWriter`/`sseWriter.writeEvent` exactly as `attach_not_live` already is
  (`AgentController.java:413`), **not** through `RunEventHub.publish` and **not** as a new
  `AgentEvent` sealed variant. Out-of-band: no collision with the closed `produced == declared`
  conformance contract (`AgentEventSchemaConformanceTest` / `AgentEventPayloadConformanceTest`),
  carries no `trace.spanId` so `seqOfPayload` returns null ⟹ **no `id:` line ⟹ never pollutes
  the replay buffer or advances `Last-Event-ID`**, and the FE dispatch ignores it as an unmapped
  no-op (it can never set `receivedTerminal`, `streams.ts:590-592`). Modeling it as an
  `AgentEvent`/hub frame is the **unsafe** path (breaks both tests + pollutes replay). **PART V's
  keystone is buildable as specified.**

- **U4 — the agent hang, CORRECTED.** The reattach reaches a terminal in **every** drop scenario
  *except one*: a run **parked at an unresolved approval/budget/context gate that is neither
  resolved nor cancelled**. The backend attach blocks the SSE thread on a **`CountDownLatch`
  awaiting `ATTACH_MAX_MINUTES = 30 min`** (`AgentSessionRegistry.java:28,224`) — **not** the 300s
  approval gate (§9's stated number was imprecise). A session DELETE does **not** hang — `cancel()`
  completes the gates `false`, the loop runs to its `finally`, publishes the terminal through the
  hub, and the latch trips. **Design consequence (refines Move C):** in the parked case the run is
  **alive-but-silent, not dead** — so the heartbeat's job is to let the FE *distinguish* (a)
  parked-alive (heartbeats flowing ⟹ the channel is fine; the real defect is the *un-surfaced
  ceremony*, which is **605's** job to re-raise) from (b) transport-hung (no heartbeats ⟹
  re-establish/conclude). 604 owns the **liveness-detection**; the parked-alive remediation
  **hands off to 605**, it is not a forced terminal. This sharpens the 604↔605 seam.

- **U5 — verdict gate: CONFIRMED gate-green.** `check-verdict-derivation.mjs` is a pure substring
  scan for the `retrieval\s*[=!]==` predicate; it forbids only a *second file* forming the verdict
  from that predicate. A new SSE-channel-liveness **input** to the single
  `computeStability`/`computeVerdict` seam never touches the predicate ⟹ green. And the
  architecture is already shaped for it: `Stability` exists to generalize the connection phase and
  already carries a `channel-stale` provisional cause (`verdict.ts`). **Move B is gate-green and
  idiomatic — fold liveness into `StabilityInput`/`VerdictInput` + one `aiStateStore` signal.**

- **U6 — resume gap-free across a worker bounce: CONFIRMED + a latent-bug caveat.** The resume
  token encodes only `streamId:seq` (no boot/PID/lifetime id — `ResumeTokenCodec`/`StreamId`); the
  SSE channel, ring buffer, and seq counter live in the **Head** process, which a worker bounce
  does **not** restart — so an **FE-initiated** `stop()`+`start()` reconnect replays gap-free, and
  every FE reducer handles the `reset`+`snapshot` fallback cleanly. **Caveat (reinforces Move A):**
  the browser's **native** auto-reconnect reuses the URL's **frozen first-`start()` query token**
  and sends `Last-Event-ID`, but the server reads `?since=` from the **query only** and ignores
  `Last-Event-ID` — so *native* reconnects resume from a stale token (a latent gap today). Correct
  resume therefore requires the **FE-owned** reconnect Move A introduces. Move A both self-heals
  the 5xx wedge *and* closes this stale-token gap.

- **U7 — liveness-window authority: REFINED to a sibling generator.** Do **not** overload the 575
  `gen-liveness-constants.mjs`: it mandates *exactly one* window-bearing concept and a three-value
  heartbeat-lease ordering law (`heartbeat < displayStale ≤ reaper`, `displayStale ≥ 3·heartbeat`)
  that does **not** fit a two-value stream watchdog (cadence + watchdog-stale). Build a **sibling**
  generator emitting `STREAM_HEARTBEAT_*` (replacing the **15 controllers** that each hardcode
  `HEARTBEAT_SECONDS = 15L` with no consumer counterpart) + the FE watchdog window that does not
  exist today, and extend `check-liveness-constants-single-authority.mjs`'s `SCAN_ROOTS` to include
  `modules/ui/src/main/java` (currently unscanned). **Refines PART V.3** from "extend the 575
  authority" to "a sibling authority modeled on it."

## VI.2 — Net effect on the design

No finding **invalidated** the design; three **sharpened** it: (U4) Move C is liveness-*detection*
that hands the parked-alive case to 605 rather than forcing a terminal; (U6) Move A is also the fix
for a latent stale-token resume gap, strengthening its rationale; (U7) the window authority is a
sibling generator, not an extension. The PART V keystone (U3) and the gate-safety of Move B (U5) are
confirmed buildable. The only assumption still resting on inference is **U1** (the exact 503 trigger),
which the design was already structured to be **independent of** — and U2's confirmation that *any*
abnormal close wedges the channel makes U1's precise origin immaterial to correctness.

## VI.3 — The one remaining oracle (optional, needs dev-stack coordination)

The live §6 worker-bounce would (a) confirm U2 in the app's own webview, (b) observe U4's
parked-run path live, and (c) *characterize* U1's 503 trigger. It is **not** a correctness gate for
the design (every cell above is already cited or probe-confirmed), but it is the highest-fidelity
closure and the moment 605's terminal-drain finally demos. It requires the shared dev stack to be
freed/authorized for invasive fault injection — deferred to a coordinated run, not done unilaterally.

---

# PART VII — As-built & live validation (2026-06-18, IMPLEMENTED & MERGED)

> Implemented in worktree `worktree-604-liveness`, merged to `main` (merge commit after the
> Moves A–D commit `fefb053c2`). All automated tiers green; live-validated against a dev stack
> rebuilt from the merged code (apiPort 52307, AI online). Below: what shipped, what was confirmed
> live, and the one **targeting correction** the live run surfaced.

## VII.1 — What shipped (the 5 moves)

- **Move D** — `scripts/codegen/gen-stream-liveness-constants.mjs` (sibling of the 575 generator) →
  `StreamLivenessWindows.java` (`STREAM_HEARTBEAT_INTERVAL_MS/_SECONDS = 15s`) +
  `stream-liveness-constants.ts` (`STREAM_WATCHDOG_STALE_MS = 40s`). The 15 diagnostic SSE
  controllers migrated to the generated constant; `check-liveness-constants-single-authority.mjs`
  extended (new names + `modules/ui` scan root).
- **Move A** — `EnvelopeStream` self-heals: reactive reconnect on `error` with
  `readyState === CLOSED` (the 5xx/abnormal case the browser gives up on) + a heartbeat-absence
  watchdog (the silent-wedge case), bounded exponential backoff (0.5s→10s, jitter, infinite at the
  cap) + resume-token replay; `closedByUs` guards an intentional `stop()`. Tests in
  `EnvelopeStream.test.ts` (readyState-CLOSED ⟹ reconnect with `?since=`; CONNECTING ⟹ none;
  watchdog ⟹ reconnect; stop cancels).
- **Move C** — backend: `AgentController.withHeartbeat(...)` wraps every blocking agent stream
  (`engine.run`/attach/resume/fork) with an out-of-band `heartbeat` frame at the generated cadence
  (per-context-synchronized `SseWriter.writeEvent`, no replay/conformance impact). Frontend:
  `LivenessWatchdog` (`streaming/LivenessWatchdog.ts`) composed in `AgentSessionController`, reset
  on every received event (incl. heartbeat), scoped to `runKind==='agent'`; on expiry it aborts a
  hung stream and routes the catch to reattach-or-conclude (`onError`/`onAttachNotLive`) instead of
  hanging to the backend's 30-min `ATTACH_MAX_MINUTES` latch.
- **Move B** — `HealthLitView`'s connection badge re-wired to trust the poll (`aiStateStore`) for
  reachability + debounce the SSE-down paint (see the correction in VII.3).

## VII.2 — Live validation results

- **Move C backend heartbeat — CONFIRMED LIVE (decisive).** Driving a real agent run against the
  rebuilt stack (AI online), the SSE stream emits `event: heartbeat` during a parked/quiet run
  (≥2 beats over a 40s parked window). The **stale-jar pitfall bit first**: the initial
  `dev_start` ran the previous `ui` dist (installDist UP-TO-DATE), so zero heartbeats appeared until
  an explicit `:modules:ui:installDist` + relaunch from the fresh dist — then heartbeats appeared.
  (This is the documented "dev stack runs stale jar after Java edits" pitfall, observed first-hand.)
- **Move A substrate — CONFIRMED LIVE-LOADED.** The Logs tab opened
  `GET /api/diagnostic-channels/head-log/stream → 200` through the modified `EnvelopeStream`; the
  self-heal code path is live on a real surface. `EnvelopeStream` backs **17 live consumers** (Logs,
  indexing-jobs, advisory, action-ledger, intent, capabilities, diagnostic channels, …), so the
  self-heal is at the right substrate layer — every SSE surface inherits it. (A *forced* abnormal
  close to watch the reconnect live was not injected — the head-local SSE doesn't drop on a worker
  bounce, and a head restart would kill the session; the reconnect mechanism is unit + Chromium-probe
  proven, PART VI U2.)
- **Automated tiers — all green:** Java `build` (PMD/spotbugs/check), `:modules:ui:test`, ui-web
  typecheck + **3190** unit tests, all governance gates, `gen --check` idempotent.

## VII.3 — Targeting correction surfaced by the live run (Move B)

The live System Health surface is **`HealthSurface`** (`jf-health-surface`), **not** `HealthLitView`
(`jf-health-view`) — the latter is a debug-route / demo / `ResourceView` Resource-renderer component
(`main.jsx:101`, `ResourceView.ts:483`), not the primary surface. So **Move B as implemented touched
a non-primary component.** Crucially, `HealthSurface` **already** derives its connection status
(`apiStatus`: connected / connecting… / reconnecting… / disconnected) from the **poll authority**
(`subscribeAiState`, `HealthSurface.ts:502`) — i.e. the live surface already satisfies Move B's
intent (a wedged SSE channel does not read as "backend down"; reachability is the poll's call).

Consequences, honestly stated:
- The `HealthLitView` badge change is a **harmless, correct enhancement** to that (secondary)
  component, kept as-is — but it is **not** the live System-Health badge fix it was framed as.
- The **live R2** ("CONNECTION flips to reconnecting… at idle") is the **poll-staleness amplitude**:
  `apiStatus === 'reconnecting…'` ⟸ `aiStateStore` phase `stale` (≥3 missed inference polls). That is
  exactly **deferred item B.3** (relax `computeStaleness` so a single jittered poll doesn't flip it).
  The live run did **not** reproduce the flicker (the surface read steady "Online"), so B.3 stays
  deferred pending an actual reproduced flicker — consistent with PART IV.2's gate ("only if the live
  run shows it"). **The live R1/R2-on-the-primary-surface concern is therefore already handled by the
  existing poll-trust in `HealthSurface`; the SSE-consumer wedge class is handled by Move A.**

**Net:** Moves **A, C, D** are the load-bearing, validated, live-relevant fixes (Move A at the SSE
substrate for all 17 consumers; Move C's heartbeat confirmed live; Move D the drift-proof window).
Move B is a correct-but-secondary touch; the primary surface needed no badge re-wire, and the live
R2 (if it recurs) is the deferred B.3 poll-staleness change. No silent over-claim: the diagnostic
*primary* surface was already poll-trusting; 604's real contribution to the live product is the
**substrate self-heal (A) + the agent-stream liveness (C)**.
