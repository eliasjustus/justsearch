---
title: "Connection truthfulness under load — reachability as a positive-contact liveness authority, separated from freshness"
type: tempdoc
status: open
created: 2026-06-25
related:
  - 562-installer-build-pipeline-smoothness   # parent: split out of move 2; the alpha.28 "Reconnecting under load" finding
---

# 649. Connection truthfulness under load

> Scope of this doc: the general idea + the derived issue, **then** (2026-06-30) the investigation,
> theorization, and a **settled general design** (see "## Design"). The design is general/architectural, not
> implementation-level. Split out of tempdoc 562's move 2 after live validation invalidated that move's
> premise.

## The general idea

A user-facing surface should report the **connection state proportionate to the verified backend reality**
("alarm budget", 562): show the alarming **"Reconnecting…/Backend disconnected"** only when the backend is
*actually* unreachable, and a calmer state (e.g. "Catching up…") when the backend is reachable but the UI's
own data is merely behind. The shell already separates these in its vocabulary (`verdict.ts`:
`channel-stale → "Reconnecting…"`, `catching-up → "Catching up…"`, `disconnected → "Backend disconnected"`),
and the presentation kernel declares a single reachability authority: **reachability is owned by the cheap
`/api/status` poll (`aiStateStore.connection.reachable`), NOT by any SSE channel's up/down**
(`docs/explanation/27-frontend-presentation-kernel.md`).

The alpha.28 sandbox round found this is violated in practice: under enrichment/ingest load the Health
header, the Connection row, and the status bar all read **"Reconnecting…"** while the backend is provably
healthy (Index state Ready, uptime ticking, head+worker READY). A first-run user watching a long local
operation is told the app lost its backend when it did not — a trust regression on a product whose pitch is
honesty.

## The derived issue (evidence-grounded)

The root cause is **NOT** that the heavy `/api/status` poll is slow at the backend. It is **browser
HTTP/1.1 connection-pool exhaustion**:

- Probed via a *fresh, non-browser connection* (the dev MCP), `/api/health` and `/api/knowledge/status`
  return **instantly** with head + worker `READY` — the backend is responsive.
- In the **browser**, `/api/status` polls hang in **`pending`** (only the first resolves). The shell holds
  **5+ long-lived SSE streams** to the backend origin (advisory operation-completed, advisory
  health-recoverable, indexing-jobs, action-ledger, health-events…), which saturate Chrome's ~6
  connections-per-host limit. Every subsequent poll queues behind them and never resolves within the
  staleness window → the FE sees no successful poll → `phase` goes `stale` → **"Reconnecting…"**.

**Consequence for the obvious fix.** "Add a cheap independent liveness probe and key reachability off it"
(the 562 move-2 attempt, since reverted) **does not work**: the new probe is *another* request to the same
origin, so it queues behind the same exhausted pool and never succeeds either — and it slightly *worsens*
the connection pressure. The bottleneck is the **browser's connection ceiling**, not backend latency, so no
additional polling endpoint can fix it.

**Tension with the documented decision.** The kernel's "reachability via poll, not SSE" rule
(`explanation/27`) is precisely what fails here: polls are the thing starving, while the SSE streams that
caused the starvation stay connected. Any real fix that derives reachability from an already-open channel
(an SSE stream's liveness) would **supersede that documented decision** — which is why the design is
deferred to its own pass rather than bolted on here.

## Pointers (for the later design pass — not a design)

- The reachability/verdict seam: `modules/ui-web/src/shell-v0/state/aiStateStore.ts`
  (`computeConnection` / `computeStaleness`), `state/verdict.ts` (`channel-stale` / `catching-up` /
  `disconnected`), and the pollers in `utils/{statusPoll,inferencePoll}.ts`.
- The SSE machinery that holds the connections + already has liveness watchdogs:
  `streaming/EnvelopeStream.ts` + the generated `stream-liveness-constants.ts`.
- The governing decision to revisit: `docs/explanation/27-frontend-presentation-kernel.md`
  (reachability authority).

## Investigation (2026-06-30)

Autonomous investigation pass — corroborate the diagnosis against source + external evidence, correct the
stream list, and critique the candidate directions for feasibility. **No solution chosen** (design still
deferred); this section is evidence + critique only.

### A. The mechanism is confirmed at the source — and it hinges on a *timeout-less* poll

The "no successful poll → `stale` → Reconnecting…" chain is exact, and the load-bearing detail the prose
above understates is that **neither poller has a request timeout**:

- `utils/statusPoll.ts:29-48` and `utils/inferencePoll.ts:46-59` both issue a bare `fetch(...)` with **no
  `AbortController`/timeout**. A request that the browser *queues* (never dispatched, because the pool is
  full) therefore neither resolves nor rejects — so *neither* the success branch (`l(data)`) *nor* the
  catch branch (`l(null)`) ever runs. The last-success stamp simply freezes.
- `state/aiStateStore.ts`: `checkStaleness` runs every 5 s (`:611`), `STALE_THRESHOLD_MS = 15_000` (`:174`,
  a flat threshold). Once `now - lastSuccess > 15 s`, `computeStaleness().stale` flips (`:264`) →
  `computeConnection().reachable = false` (`:271`) → `phase = 'stale'` → `computeStability` returns
  `provisional/channel-stale` (`verdict.ts:113`) → `computeVerdict` returns `transitioning` →
  `verdictHeadline` returns **"Reconnecting…"** (`verdict.ts:208-209`). Confirmed end to end.
- Implication: the interval keeps firing a *new* `fetch` every 5–10 s, each of which also queues behind the
  saturated pool, so the pending count *grows*. A naive "add an `AbortController` timeout" does **not** fix
  anything — aborting a queued poll does not free an SSE-held socket, and the next poll re-queues.

External evidence corroborates the browser-side cause as a *permanent, documented* constraint, not a tuning
issue: Chrome caps **6 connections per host** on HTTP/1.1, **EventSource connections count against that
cap**, and the limit is marked WontFix; MDN itself recommends HTTP/2 specifically to escape the EventSource
limit. ([Chromium issue 275955](https://bugs.chromium.org/p/chromium/issues/detail?id=275955),
[MDN: Using server-sent events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events)).

### B. Correction to the stream list — count right ("5+"), membership off by one

A traced inventory of the **default** running shell (`chrome/Shell.ts`) holds **5 concurrent long-lived SSE
EventSources** to 127.0.0.1, but not the five the prose names:

| # | Endpoint | Started | Sharing |
|---|---|---|---|
| 1 | `/api/indexing-jobs/stream` | boot — `Shell.ts:683` `startIndexingJobsBridge` | **pooled** by URL (`EnvelopeStreamPool.ts:51`) |
| 2 | `/api/advisory/operation-completed/stream` | boot — `Shell.ts:677` `createAdvisoryStore` → `reconcileFromCatalog` | own EventSource (per-resource, `AdvisoryStore.ts:466`) |
| 3 | `/api/advisory/health-recoverable/stream` | boot — same | own EventSource |
| 4 | `/api/action-ledger/stream` | boot — always-mounted `<jf-ai-activity-digest>` (`Shell.ts:2135` → `AiActivityDigest.ts:99`) | **not** pooled (`ActionLedgerClient.ts:387`) |
| 5 | `/api/intent/stream` | boot — `Shell.ts:1470` → `bootIntentStreamBridge` | module singleton |

Corrections vs. the prose:
- **`health-events` (`/api/health/events/stream`) is LAZY**, not default-on — it opens only when the Health
  surface is navigated to (`HealthLitView.ts:261/270`; the surface is lazy-loaded, `shell-v0/index.ts:52-58`).
  So it is one of the streams that *tips the pool over the edge* when a user opens Health, not part of the
  baseline 5.
- **`/api/intent/stream` is the always-on stream the prose omitted.**
- **Two consistency defects sharpen the picture (and hint at the cheapest lever):** the `EnvelopeStreamPool`
  (dedup-by-URL) exists and indexing-jobs uses it, but advisory (×2) and action-ledger each open their own
  unpooled EventSource. Worse, `openActionLedgerStream` is *not* pooled, so navigating to the Activity
  surface opens a **second, duplicate** action-ledger connection (`ActionLedgerView.ts:163`) — a 6th socket
  that saturates the pool by itself. Routing every stream through the existing pool would at minimum kill the
  duplicate, though it cannot drop the count below the 5 distinct URLs.

Baseline arithmetic: 5 SSE + 2 polls (status, inference) = 7 connection-wanters for 6 slots, so even at rest
the polls share the single free slot with every other fetch (surface-schema fetch, lazy JS chunks, icons).
Opening Health (or Activity) adds a 6th SSE and the polls starve outright.

### C. Critical analysis of the four candidate directions

1. **HTTP/2 multiplexing — likely infeasible as stated; the most expensive, not the cheapest.** HTTP/2 would
   collapse all of this onto one connection (~100 streams), but **browsers do not support HTTP/2 over
   cleartext (h2c)** — they negotiate HTTP/2 only over TLS via ALPN
   ([corroborated](https://httpd.apache.org/docs/current/howto/http2.html); browsers fall back to HTTP/1.1
   on a cleartext endpoint). The local API is **loopback HTTP with no TLS** (Hard Invariant #2;
   `LocalApiServer.java:39-41`), and the embedded **Javalin 6.7.0 / Jetty** server is configured HTTP/1.1
   only (no `ServerConnector`/`HTTP2CServerConnectionFactory` anywhere; `LocalApiServer.java:326-331`).
   Enabling browser-reachable HTTP/2 would require adding **TLS on loopback** (self-signed cert + webview
   trust) — a substantial change that cuts against the loopback-HTTP simplicity. This candidate is much
   heavier than its one-line framing suggests and should probably be ruled out unless TLS-on-loopback is
   wanted for other reasons.
2. **Server-side `/api/status` timeout — a non-fix.** The request never reaches the server; it is *queued in
   the browser* behind the exhausted pool. A server-side timeout fires only on requests that *arrive*. This
   candidate addresses backend slowness, which the diagnosis already rules out. Recommend dropping it.
3. **SSE consolidation / pooling — feasible, has prior art, does not overturn the kernel.** This attacks the
   actual root cause (too many concurrent connections). Prior art already sketched for a different
   motivation: tempdoc 521 proposes an `EnvelopeStreamPool` / `MultiplexedStream` "multiplex SSE
   subscriptions by URL" (521:456/940/1426), and tempdoc 501 established `/infra/capabilities/stream` as a
   precedent for **multiplexing multiple event kinds onto one connection** (501:118/432/443). A
   shell-events fan-in endpoint (one SSE carrying advisory + jobs + ledger + intent frames) would take the
   baseline from 5 sockets to 1, leaving 5 slots for polls and lazy fetches. Cost: real backend work (a
   multiplexed event bus) and it pushes against the deliberate per-resource-stream architecture.
4. **SSE-liveness-based reachability — technically sound, but overturns a *merged* kernel decision.** An open
   SSE stream already carries a **heartbeat that is treated as proof-of-life** (`EnvelopeStream.ts:237-240`;
   the watchdog `STREAM_WATCHDOG_STALE_MS`), and reading it adds **zero** connection pressure (the socket is
   already held). So it is a reliable backend-reachability signal precisely *while the polls are starving*.
   The cost is governance, not mechanism: `explanation/27:73` (absorbing tempdoc **604**, merged 2026-06-18)
   declares reachability is owned by the poll, **NOT** by any SSE channel's up/down, with the SSE state only
   a "secondary, debounced hint." Deriving reachability from SSE liveness overturns that line — exactly the
   tension the doc flagged.

### D. Reframe + an alternative the four candidates miss

The four candidates conflate two separable sub-problems:

- **(a) connection pressure** (structural — 5 always-on sockets on a 6-slot bus) — fixed by #3
  (consolidation/pooling), independent of the kernel.
- **(b) truthful vocabulary while a poll is *transiently* starved** (presentation) — the "Reconnecting…"
  alarm fires for a backend that is provably alive.

These can be fixed **independently and additively**, and the cleanest (b)-fix may not require fully
overturning the kernel. A **hybrid** worth designing: keep the poll as the **freshness** authority (it still
owns "is our `/api/status` data current?"), but let any fresh SSE **heartbeat veto the alarm** — i.e.
reachability = `pollFresh || anySseHeartbeatFresh`. When the poll is stale *but* a heartbeat is fresh, the
verdict degrades to the calm **"Catching up…"** (already in the vocabulary, `verdict.ts:214`) instead of
"Reconnecting…"; only when *both* are stale does it escalate to "Reconnecting/Backend disconnected." This
realizes the doc's own "general idea" (alarm proportionate to verified reality) and arguably *refines* rather
than discards the kernel's "poll owns reachability" rule — the poll still owns the *fresh-data* fact; the
heartbeat owns only the *backend-alive* fact, which are genuinely different. Whether that counts as
"superseding 604" vs "extending it" is itself a design-pass decision and a doc update either way.

Defense-in-depth: do **both** — (a) so polls usually aren't starved at all, and (b) so the rare residual
starvation reads honestly. (a) alone leaves a thinner version of the bug whenever a future surface adds a
stream; (b) alone leaves the polls chronically late under load even if never *false*.

### E. Relationship to neighbouring tempdocs (verified)

- **604** (merged) fixed a *different* mechanism: a stream that **drops** (5xx/abnormal close → `readyState
  CLOSED`) and never reconnects. In 649 the streams are **healthy and holding** their sockets — they are the
  *cause*, not a casualty — so 604's FE-owned self-heal does nothing here. No overlap, no conflict.
- **586** (merged) independently diagnosed the same 6-conn / 15 s-staleness interaction (586:160-170) but
  shipped only a *proposed* mitigation ("don't flip on a single missed poll during a load burst"). That
  mitigation is **not present** in the current staleness logic — `computeStaleness` is a flat 15 s threshold
  with no burst-awareness (the only connection-space debounce is `HealthLitView`'s 604-era SSE-down debounce,
  `HealthLitView.ts:143`). So the bug is fully live; 586 left it unmitigated at the poll layer.
- **650–660** (the go-public cluster): none touch SSE/polling/reachability. 649 is the current frontier on
  this issue; nothing newer supersedes it.

## Related finding — the AI-engine state surface is the FE's most fragile (2026-06-30)

Recorded here (not a new tempdoc yet) because it shares 649's root theme — **FE state-truthfulness** — and
the *same* timeout-less poller: the brain's `inferencePoll` is one of the two pollers that starve under the
connection-pool exhaustion above (`utils/inferencePoll.ts:48`), so a starved poll can make the brain read
"AI Offline / Connecting…" wrongly for the same reason 649 makes the header read "Reconnecting…". The
diagnosis below is broader than 649's connection scope, so the *full* fix is a candidate for its own tempdoc
(see "Scope" at the end of this section).

### Why the brain breaks most (evidence)

The brain is the **one major subsystem deliberately left outside the FE's anti-drift kernel**. Search/retrieval
health was collapsed onto a single `SystemHealthVerdict` that every surface merely consumes (595,
`explanation/27`), gated against re-derivation. The AI/inference component was **explicitly excluded**:

> "the AI (inference) component is intentionally EXCLUDED … and is surfaced by its own AI-Engine card."
> — `state/verdict.ts:142-143`

That exclusion left the brain doing by hand what every other surface gets for free, producing five
compounding fragilities:

1. **`BrainSurface.deriveAiState()` reconciles ~5 overlapping state representations by a hand-ordered
   precedence ladder** (`views/BrainSurface.ts:1014-1047`): `installStatus`, local `busy[...]` flags,
   `runtimeStatus.onnxFeatures[].modelActive`, `_unifiedAiState.runtime`, and a *separate* raw `inference`
   snapshot. A code comment admits the drift it fights ("the runtime may be active even if install status is
   stale"). This is precisely the representation-drift class the kernel makes unwritable elsewhere.
2. **Those sources are fed by four self-owned poll timers** (`pollInstall`/`pollPack`/`pollRuntime`/
   `pollDiagnostics`, `views/BrainSurface.ts:270-273`) **plus** the shared `subscribeAiState` (`:520`) **plus**
   on-mount `fetch`es — each independently stale-able. `installStatus` alone has 6 write-sites
   (`:281,595,634,829,837,853`). Every source-combination is a potential wrong-state render.
3. **It models the most states / most complex lifecycle of any subsystem** — `not_installed → installing →
   offline → starting → connecting → online` (+ `indexing`/`transitioning`), × per-variant activate/deactivate
   (cuda12 vs default), GPU/VRAM layers, restart-ETA — because it fronts an **external native process**
   (`llama-server.exe`) with install/download/activation/model-load/crash-restart, far more failure modes than
   the in-process Lucene worker search talks to.
4. **The wire boundary is weakly typed.** The raw inference snapshot's `mode` is an untyped `string`
   (`utils/inferencePoll.ts:13`) and `BrainSurface` branches on `mode === 'transitioning'` (`:1040`), a value
   absent from the typed runtime union `'offline'|'online'|'indexing'|'starting'|'unknown'`
   (`state/aiStateStore.ts:72`) — so vocabulary drift escapes the compiler.
5. **It inherits 649's connection bug** (the shared timeout-less poller, above).

Surface area corroborates: `views/UnifiedChatView.ts` (4302 lines) and `views/BrainSurface.ts` (2051) are the
two largest view files in the frontend — both AI-facing.

> Honesty caveat: this checkout is a shallow public mirror (28 squashed commits), so "breaks **most
> frequently**" cannot be proven from git churn here. Confidence is in the structural *why* above, corroborated
> by the design history repeatedly revisiting this surface (518/586/601/604/627/630).

### Judgment — re-architect one seam, do NOT rewrite

A full "rewrite the brain" is the wrong tool and the riskier path:

- The fragility is **concentrated** in the state-reconciliation seam, not spread across render/install/variant
  code (which is downstream-but-correct).
- A rewrite **discards embedded edge-case knowledge** the precedence ladder encodes (runtime-active-while-
  install-stale, no-data≠not-installed, restart-ETA window, variant handling) — the classic second-system
  regression.
- The fix **already exists as a pattern**: apply the proven single-verdict kernel to the brain; invent nothing.
- "Rewrite the brain" slides into rewriting the two largest FE files on shared `main` — large blast radius for
  a bounded defect.

Recommended direction (state/health layer only):

1. Add an **`aiEngineVerdict`** authority parallel to the search verdict — one typed place that folds install
   state + runtime mode + inference snapshot into a single AI-engine state; type the boundary (kill the
   `mode: string` / `'transitioning'` drift).
2. Make `BrainSurface` a **pure consumer** — delete `deriveAiState()`'s 5-source ladder; collapse the four
   self-owned poll timers into the store.
3. **Keep the render markup** mostly as-is; once state is single-source it barely changes.
4. **Gate it** — extend the `verdict-derivation` gate so the AI verdict can't be re-forked.

Design insight that makes this a clean refactor rather than a philosophical reopening: the original exclusion
conflated *"don't raise a health alarm when AI is off"* (correct — AI is offline-by-design) with *"don't give
AI a single state authority"* (the actual mistake). A **calm-by-default, single-source AI verdict** that never
alarms but is single-authority satisfies both.

### Scope

The connection-pool fix (the rest of this doc) and the AI-engine-verdict re-architecture are **separable**:
649's connection-truthfulness fix does not depend on the brain re-architecture, and vice versa. This finding
is logged here for the shared root + shared poller; if/when the brain work is picked up it should likely become
its own tempdoc ("AI-engine verdict: single-authority state for the brain surface"), cross-referencing 649 for
the timeout-less-poller interaction. Not started — diagnosis + recommended direction only.

## Theorization (2026-06-30) — directions, framings, principles (NOT final design)

This section deliberately opens the aperture before any design is settled: alternative framings, the wider
solution space (beyond the four candidates), the tradeoffs/risks/hidden assumptions, and whether 649 points
at a broader invariant. None of this is a commitment; it is option-generation to inform the eventual design
pass.

### A. Five ways to reframe the problem

How we name the problem pre-selects the fix, so it is worth holding several frames at once:

1. **Resource contention (tragedy of the commons).** The FE treats the 6-slot connection budget as infinite.
   Long-lived streams are *greedy permanent holders*; polls are *transient borrowers*; nobody owns the
   budget. Frame → the FE is missing a **connection-budget authority** that reserves slots for liveness
   probes and caps greedy holders.
2. **Liveness ≠ freshness (a conflated axis).** A single poll's success bundles three independent facts —
   *the path to that endpoint is clear* + *the backend is up* + *this data is current*. Reading "backend
   down" off a stale poll conflates them. Frame → split **reachability** (any positive contact suffices)
   from **freshness** (this specific data is current).
3. **Negative inference from absence ("absence of evidence ≠ evidence of absence").** A missing response has
   many causes — backend down, network slow, **or client-side queuing** — yet the FE makes a confident
   *negative* claim ("disconnected") from a signal that cannot distinguish them. Frame → silence may at most
   yield `unknown`/`stale`, never `disconnected`, while *any* independent positive signal is fresh.
4. **Alarm-must-be-earned (a product-honesty invariant).** For a product whose pitch is honesty, an alarming
   state is a *falsifiable claim about reality* and must be earned by positive evidence of the bad condition
   — not fired by the absence of evidence of the good one. This elevates 562's "alarm budget" from UX nicety
   to invariant.
5. **"It's local, so it's free" is false.** Loopback removes network latency/bandwidth cost but **not** the
   browser engine's per-host connection accounting. The proliferation of always-on streams likely grew under
   the implicit assumption that localhost has no limits. Frame → the FE inherits browser quotas regardless of
   locality.

### B. The wider solution space (the four candidates are a slice)

Grouped by the layer they act on; the doc's four candidates are noted in place.

- **Transport layer.** *(candidate)* HTTP/2 — infeasible over loopback-HTTP without TLS (above). **WebSocket
  multiplexing** — a *new* option the doc omits: one bidirectional WS carries every event channel **and**
  could carry poll responses; Chrome's WS-per-host cap is far higher than 6, so the limit dissolves. Cost:
  abandons the SSE/`EnvelopeStream`/resume-token investment, new failure modes, larger change. **Tauri-native
  IPC heartbeat** — the desktop shell has a native command bridge that does **not** touch the browser HTTP
  pool at all; a liveness channel over Tauri IPC would be immune to exhaustion by construction. Cost:
  fragments the loopback-HTTP API contract (also consumed by external MCP clients), and diverges in pure-
  browser/dev mode where no Tauri bridge exists.
- **FE connection-governance layer (no backend change).** A **budget broker/scheduler** that reserves ≥1–2
  slots for polls and caps concurrent SSE. Route *all* streams through the existing `EnvelopeStreamPool`
  (kills the action-ledger duplicate; doesn't cut the 5 distinct URLs). **Lazy/on-demand streams** — open a
  stream only while its surface is visible; resume-token replay covers events missed while closed. Hidden
  question: which streams are always-on by *necessity* (badge counts need idle latency) vs by *accident*?
- **Data-shape layer.** **Fold steady-state into the poll, reserve streams for open-surface depth.** If
  advisory/recoverable state already (or could) ride `/api/status`, the always-on streams are redundant for
  idle badges; streams become lazy, only for low-latency while the relevant surface is open. ("Polls for
  steady-state *breadth*; streams for open-surface *depth*.") This can drop idle always-on streams toward
  zero without losing the badge.
- **Reachability-derivation layer.** *(candidate)* SSE-heartbeat veto (poll owns freshness; a fresh heartbeat
  vetoes the alarm). Generalized: a **contact-clock** — one FE-wide monotonic "last successful contact with
  the origin (poll success **or** any SSE frame **or** any successful fetch)" stamp; reachability is "time
  since last contact of *any* kind," and only the absence of *all* positive signals means `disconnected`.
- **Staleness-logic layer.** *(candidate)* server-side timeout — a non-fix (request never arrives). 586's
  unshipped burst-debounce — a band-aid. Note a subtlety: with timeout-less fetches there are no *failures*
  to count, only silence — so "N consecutive failures" logic never even triggers; **adding fetch timeouts**
  is a prerequisite for any failure-counting approach (and harmless only when paired with a positive-signal
  veto, since aborting a queued poll frees no socket).

### C. The convergence insight

Several directions are not competitors — the strongest design may collapse them into **one move**. If all
streams are **consolidated into a single multiplexed "shell-events" SSE** (the 521 `MultiplexedStream` / 501
"multiplex event kinds on one connection" prior art), then simultaneously: (a) connection pressure is solved
(1 stream + 2 polls fit in 6 with room to spare); (b) that one stream's heartbeat becomes the natural live
reachability signal; (c) the contact-clock is trivially fed by it. Consolidation *is* the liveness fix.

Critically, the **contact-clock framing dissolves the kernel tension** rather than overturning the kernel.
The kernel said "poll, not SSE" to stop a *flapping single SSE* from being mistaken for backend-down (604's
concern). The contact-clock honors that *intent* (no single channel's drop causes a false alarm) better than
the kernel's *letter* (only the poll counts): reachability is OR-of-all-positive-contact, so neither poll nor
stream is "the" authority — **origin-contact** is, and both feed it. That is arguably a *refinement* of 604 /
`explanation/27`, not a reversal — a distinction the design pass (and the doc update either way) should make
deliberately.

### D. Broader principle / recurring system shape

649 is not a one-off; it rhymes with shapes the codebase keeps rediscovering:

- **"One status axis was actually two."** The system has repeatedly found a single health signal secretly
  carried two facts and split it: 557 split *consumer-readiness* into tri-state; 595 split *stability*
  (settled vs in-flux) from *health* (ok vs degraded); 630 split *catching-up* out. 649 is the **next
  instance**: split *reachability* (backend alive) from *freshness* (data current). The **related AI-brain
  finding is the same shape again** — a state surface that hand-merges sources because it was never given a
  single authority. Recurring principle: *when a status signal can be wrong in two unrelated ways, it is two
  signals wearing one name.*
- **Single-authority discipline must extend from *presentation* concepts to *runtime* concepts.** The kernel
  (574/595) made "one authority per visual/behavioural concept" load-bearing. 649 (a connection-budget /
  origin-contact authority) and the brain finding (an `aiEngineVerdict` authority) are the *same thesis*
  applied to **runtime resources and epistemic axes**, not pixels. Candidate invariant worth naming: *a
  finite platform quota (connections, workers, storage, background timers) consumed by independently-authored
  components needs one owner + a register, or it drifts to exhaustion* — directly analogous to the existing
  governance registers (execution-surfaces, operation-surfaces).
- **Alarms are falsifiable claims.** Generalize frame A.4 into a status-surface invariant: *a failure state
  must be substantiated by positive observation of the failure (or the absence of every positive signal),
  never by the absence of one expected signal.* This is a re-usable lint-of-the-mind for every "if I haven't
  heard X in T, declare BAD" site in the codebase (the SSE watchdogs, lifecycle timeouts, the brain's
  offline derivation).
- **Real-time-ness is a cost to be justified per channel, demand-driven.** "Always-on" is a permanent claim
  on a scarce resource; the default should be lazy, with always-on earned by a real idle-latency need.

### E. Hidden assumptions to surface (each, if false, redirects the design)

- "The poll is cheap, so it'll get through." — cheap *at the server* ≠ schedulable *at the client*.
- "Idle SSE streams are free." — an idle stream still holds a socket for its whole lifetime.
- "A dedicated liveness probe will help." — 562 disproved this; another request joins the same queue.
- "Loopback = no limits." — the connection cap is locality-independent.
- "More real-time is better." — each always-on stream has an invisible cost.
- "The Tauri webview == Chrome." — *probably* (Chromium/WebView2), but the exact per-host cap **and** whether
  a custom Tauri protocol could bypass the HTTP pool are **unverified** and bear on the WS / Tauri-IPC
  options. Worth a probe before committing.
- "The 6 limit might relax." — it is WontFix, permanent, cross-browser; design around it.

### F. Risks of the leading directions

- **SSE-liveness / contact-clock:** false-*positive* liveness from a *wedged-but-open* stream — so the signal
  must be heartbeat-*freshness*, not mere `readyState===OPEN` (604's watchdog already provides this; reuse
  it, don't re-derive).
- **Single multiplexed stream:** becomes a single point of failure for *all* live updates **and** (if it is
  also the sole reachability signal) for reachability. Mitigation: keep reachability as OR(poll, stream) so
  the poll backs the stream and vice-versa — never make the consolidated stream the *sole* signal.
- **Lazy streams:** missing idle-relevant events (badge counts). Mitigation: poll carries steady-state;
  stream is depth-only while open.
- **WebSocket / Tauri-IPC:** out-of-proportion blast radius for a bounded defect unless other goals favor
  them; both risk dev-mode / external-client divergence.

### G. No-regret moves (high option value, low commitment) — for the design pass to weigh

Independent of the eventual structural choice, three moves appear *no-regret* — they make the alarm honest
now and foreclose none of the bigger directions:

1. **Contact-clock / OR-liveness** — FE-only, kernel-*refining*, immediately stops the false alarm.
2. **Route every stream through `EnvelopeStreamPool`** — free cleanup; kills the duplicate action-ledger
   socket regardless of final direction.
3. **Add fetch timeouts to the pollers** — prerequisite for any failure-aware logic; only safe *with* (1).

These are observations for sequencing, **not** a decision to implement — the structural choice (consolidation
vs WS vs lazy-streams vs status-fold) remains open and is the real design pass.

## Design (2026-06-30) — settled general direction

This **settles** the direction the Theorization opened. It is general/architectural; concrete signal names,
window constants, and gate wiring are implementation detail for the build pass. The guiding constraint
(matching scope to the problem): 649's problem is **truthfulness** — a false alarm. The design therefore
centers on the *epistemic* fix and treats the *resource* fix (stream count) as a separable follow-up,
because the epistemic fix makes the alarm honest **regardless of why** a poll is late.

### The design in one sentence

**Make backend reachability a declared liveness authority derived from the most recent *positive contact of
any channel* (a poll success OR any SSE frame/heartbeat), kept separate from data-freshness (poll-specific) —
conforming to the existing per-domain liveness-authority register rather than the current unregistered
inline-timestamp derivation.**

### D1. The core — an origin-contact reachability authority, separated from freshness

Two facts the system currently collapses into one (562 named this exact split; `562:391-394`):

- **Reachability** — *is the backend alive at all?* Earned by **any** positive contact with the origin within
  a window: a `/api/status`/`/api/inference/status` poll success **or any frame/heartbeat on any open
  `EnvelopeStream`**. Going `unreachable` requires the absence of **all** of these.
- **Freshness** — *is this specific poll's data current?* Unchanged: the poll-success recency that already
  exists (`computeStaleness`).

The verdict then reads the pair: reachable+fresh → operational; **reachable+stale → the calm
"Catching up…"/"Updating…" transitioning state** (the data is behind but the backend is provably alive);
unreachable (no contact of any kind) → the alarming "Reconnecting…/Backend disconnected". This is exactly
562's reverted move-2 vocabulary (`562:531-540, 647-653`) — but achievable now (see D4).

### D2. Conform to existing seams — extend, do not fork

The investigation found the structure to reuse is already present; the design is an **extension at four
existing seams**, not a new subsystem:

1. **The per-domain liveness-authority register** (`governance/inflight-liveness-projections.v1.json`,
   tempdoc 575 §17 Face C) already declares that *"every in-flight record's displayed state must derive from
   its domain's ONE liveness authority, **never from raw stream membership or an inline timestamp
   comparison**."* It has two domains today — `indexing-job` (`isInFlightLive`, heartbeat-lease model) and
   `brain-install` (`isAiInstallLive`, polled-state model). **Reachability is a third liveness domain** whose
   authority is a tiny pure function in the same shape (`is-live(lastContactMs, now, window)`), with one
   novelty: its input is **aggregated across channels** (a *multi-source positive-contact* model, alongside
   the existing heartbeat-lease and polled-state models). Today's `computeStaleness` is literally the
   *inline-timestamp comparison the register forbids* — it was never recognized as a liveness domain. (Open
   sub-decision: whether "transport reachability" lives as a third row in that register or a sibling under
   the same pattern — the register's current framing is *in-flight items*; reachability is *connection*
   liveness. Same principle either way; pick at build time. This is exactly the "conform vs sibling" call the
   Reach section flags.)
2. **The verdict/stability axis** (595, `state/verdict.ts`). 595's whole pattern is "one status signal was
   actually two epistemic facts → split into orthogonal axes expressed as a **closed cause-union** consumed
   by **one** verdict rollup" (`595:34-46,123-131`). Reachability-vs-freshness is that same move one axis
   over. The seam is `computeStability` (`verdict.ts:113`): today `phase==='stale'` unconditionally yields
   `channel-stale` → "Reconnecting…". The design adds a `StabilityInput` field ("contact fresh via another
   channel") so a stale poll with fresh contact yields a **calm** cause instead. **No new alarm tone and no
   new `VerdictKind`** — the calm transitioning family already exists (D3).
3. **The universal stream seam** for the contact stamp. Every `EnvelopeStream` — pooled or not — routes every
   frame/heartbeat through `handleFrame`/`handleOpen` (`EnvelopeStream.ts:237-255`); 604 already treats "a
   frame (incl. heartbeat) is proof of life" there. That single seam bumps the shared contact stamp, so all
   ~10 streams feed reachability from **one edit**, not per-bridge (the `EnvelopeStreamPool` is *not*
   universal — 7/10 bridges bypass it, so the pool is the wrong seam).
4. **The existing local precedent to generalize.** `HealthLitView` (`views/HealthLitView.ts:316-330`) already
   implements the poll-vs-channel split *locally* — "reachability owned by the poll, not the SSE channel,"
   distinguishing "Live updates paused — reconnecting…" (channel down, backend reachable) from a hard
   "Disconnected." The design **lifts that into the store as the one authority**, so `HealthLitView` becomes
   a consumer and stops carrying its own copy (drift removed, not added).

### D3. The load-bearing 604 constraint — heartbeat *freshness*, never channel up/down

604 deliberately took reachability **off** SSE because an SSE channel's up/down is *wedge-prone*: a 5xx /
abnormal close sets `readyState=CLOSED` and the channel stays "down" forever while the backend is READY
(`604` R1). The design must therefore use **heartbeat freshness as a positive-contact signal**, and must
**never** key reachability off the connect/disconnect *event*. This moves only in the safe direction: a fresh
heartbeat can only *veto a false "unreachable"* (make the verdict less pessimistic), never assert a false
"down". So this **refines** 604 rather than reversing it — the contact authority honors 604's *intent* (no
single channel's drop causes a false verdict) better than its *letter* (only the poll counts), because
reachability is now OR-of-all-positive-contact and no single channel can flip it either way.

### D4. Why this is 562's reverted move-2 done right

562 move-2 wanted "a cheap liveness signal **independent** of the heavy poll" — a *new* `/api/runtime/live`
poller. 649 proved that fails: a new request joins the **same** exhausted connection pool and never resolves
(`562:742-746`). The design's resolution: the independent liveness signal already exists and **costs zero new
connections** — it is the heartbeats on the **already-open** SSE streams (the very streams that *caused* the
exhaustion are immune to it, because heartbeats flow over established sockets, not new ones). That is the
single insight that makes the move-2 intent reachable.

### D5. Scope boundaries — what is deliberately NOT in this design

Matching structure to the problem the tempdoc *actually* has:

- **OUT: the connection-budget / stream-consolidation / WebSocket / WebTransport / HTTP-2 / Tauri-IPC family.**
  These are the *resource* concern — they reduce how often the poll is starved (improving freshness latency),
  but the tempdoc's problem is *truthfulness*, which the contact authority solves on its own. Building them
  now would be structure for a case the present problem does not include. They remain a **named candidate
  follow-up** (likely its own tempdoc); if pursued, a "live-channel budget register" would conform to the
  governance-register pattern, and the transport options are each gated on a prior decision (TLS-on-loopback
  for HTTP-2/WebTransport; desktop-coupling for Tauri-IPC — see Research below).
- **OUT: a new independent liveness probe endpoint** (562's reverted approach; 649 disproved it).
- **OUT: a new `VerdictKind` / new alarm wording** (reuse the existing calm transitioning family).
- **Sub-decision left to build time, not over-specified here:** whether reachable-stale reuses 630's
  `catching-up` cause verbatim or gets a sibling calm cause. Reuse minimizes vocabulary, but 630's *body
  text* is sleep-specific ("changes made while your computer was asleep"), so the **cause** may need to
  generalize even if the **headline** ("Catching up…") is shared. General requirement: calm transitioning
  family, proportionate tone — exact cause granularity is implementation.

### D6. Research pass (2026-06-30) — current global state, and why it was worth a narrow check

Most of the design rests on settled facts (the 6-connection limit is WontFix; HTTP/2 needs TLS) and timeless
distributed-systems theory (heartbeat/failure-detector liveness), so no broad survey was warranted. A
**narrow** pass on the genuinely-moving, design-relevant runtime questions returned:

- **The premise holds on the *shipped* runtime.** Tauri's webview is **WebView2 (Chromium)** and inherits
  Chrome's 6-per-host limit — so this is a release-app bug, not a dev-only artifact.
- **A non-HTTP liveness channel is technically possible but stays OUT of scope.** Tauri's `ipc://localhost`
  custom protocol is intercepted natively and **bypasses the browser HTTP connection pool** — a Tauri-IPC
  heartbeat would dodge the limit by construction, but it couples liveness to the desktop shell and diverges
  in browser/dev. Recorded for the resource follow-up only.
- **Emerging transports don't change the core.** **WebTransport reached Baseline (March 2026)**, but needs
  HTTPS/HTTP-3/QUIC — the *same* TLS-on-loopback obstacle as HTTP/2. Relevant only to the (scoped-out)
  transport follow-up, and gated on the same TLS decision.

Net: the research **validated** the design's premise and reinforced that the transport-agnostic *epistemic*
fix is the correctly-scoped core; nothing found alters it.

## Reach & principle (2026-06-30) — judging the design's scope of applicability

Stepping back from the immediate fix:

### This design is an instance of an existing seam — conform, don't parallelize

It is not a new idea; it is **two existing system principles meeting**: 575's *per-domain liveness authority*
register (liveness derives from a declared authority, never an inline timestamp) and 595's *epistemic
axis-split* (one signal that can be wrong two ways is two signals). The correct action is to **conform** —
register reachability as a liveness domain and extend the one verdict — not to build a parallel reachability
mechanism. The single novelty worth noting is a new *liveness model* within the existing pattern:
**multi-source positive-contact aggregation**, sitting beside the register's existing heartbeat-lease and
polled-state models.

### The principle this reveals (named) + candidate scope + existing violations

**Principle — "Earned-failure / positive-evidence liveness":** *a liveness, reachability, or failure claim
must be earned by positive evidence of the condition (or the absence of **every** positive signal), never
inferred from the absence of a **single** expected signal.* The 575 register is the *structural* form; this
is the *invariant* behind it ("absence of evidence ≠ evidence of absence"). The recurring **violation
shape** is concrete and greppable: an **unregistered inline-timestamp liveness derivation** — `now -
lastX > T ⇒ BAD` computed at a single site off a single source.

Candidate scope + where existing code already violates it (named, **not** fixed here):

- **Connection reachability** — `computeStaleness`/`computeConnection` (`aiStateStore.ts:251-275`): the bug
  this tempdoc fixes. Inline, single-source (poll-only), unregistered. *(In scope — the design above.)*
- **Brain runtime/offline state** — `BrainSurface.deriveAiState` (`views/BrainSurface.ts:1014-1047`) infers
  "offline" partly from the *absence* of poll/runtime data across ~5 hand-merged sources; only its *install*
  liveness is registered (`isAiInstallLive`), not its *runtime/reachability* state. This is the same
  violation class, and it is the structural heart of the **Related-finding** above — the strongest evidence
  the principle generalizes beyond 649.
- **Any future "if I haven't heard X in T, declare BAD" site** — lifecycle timeouts, feed-stall detectors,
  watchdogs. (Note: 604's SSE watchdog already conforms — it reconnects on heartbeat-absence rather than
  asserting a user-facing failure, which is the *positive-evidence* discipline done right and a good model.)

### Deliberate non-action — recognize the principle, do not build the generalized structure

Per scope discipline: the present problem requires bringing **one** domain (connection reachability) under the
existing register. It does **not** require a generalized "positive-evidence liveness framework," nor
refactoring the two existing single-source authorities (`isInFlightLive`/`isAiInstallLive`) into a generic
"liveness = recency of last positive evidence" base — even though they are special cases of it. Recording the
principle + its candidate scope (above) is the deliverable; building the general structure waits until a
second domain actually needs it (the brain finding is the likely trigger, in its own tempdoc). Separating
*recognizing* the principle from *building* for it is intentional — it captures the insight without premature
abstraction.

## Pre-implementation de-risking (results, 2026-06-30)

A confidence-building pass verified the design's load-bearing assumptions **before** implementation (no
feature code written). All resolved favorably; the design is unchanged.

- **U1 — idle heartbeats (the decisive runtime assumption): CONFIRMED (source-level).** All five always-on
  streams emit an **unconditional 15 s heartbeat** via `scheduleAtFixedRate(writer::sendHeartbeat, …)` with
  **no data-presence guard** (`modules/ui/.../SseEnvelopeWriter.java:217-219` and `:272-274`;
  `sendHeartbeat` → `LIFECYCLE`/`kind:"heartbeat"` at `:99-101`). Per-endpoint traced (indexing-jobs,
  both advisory, action-ledger via `attach`; intent via `attachEventOnly`) — none is event-only-without-beat.
  So an open stream proves "backend alive" at idle, exactly as the design needs. *(Residual: the live
  end-to-end curl/devtools confirmation was not run — no dev stack is up and the `justsearch-dev` MCP tooling
  is absent this session; the source evidence is conclusive, and a 1-minute "watch the SSE stream tick every
  15 s in devtools" check at build time closes it. It cannot change the design.)*
- **U6 — reachability window source: RESOLVED.** Derive it from the single-source generator
  `scripts/codegen/gen-stream-liveness-constants.mjs` (`WINDOW = {heartbeatMs:15_000, watchdogStaleMs:40_000}`,
  gate-enforced `watchdog ≥ 2×heartbeat`) — the same authority that already drives the backend cadence and FE
  `STREAM_WATCHDOG_STALE_MS`. The reachability window keys off the **40 s watchdog**, **not** the unrelated
  30 s/90 s action-lifecycle constants (`liveness-constants.ts`).
- **U2 — register fit: RESOLVED (conform possible; small build-time choice).** `check-inflight-liveness.mjs`
  is fully generic over `reg.domains` — a new `connection` domain (authority module/symbol + renderSites,
  `phantomCheck:false`, mirroring `brain-install`) is accepted with positive-coverage enforcement. The
  negative early-warning regex is hardwired to `PROCESSING`/`running` and unusable for connection (fine —
  brain-install is positive-only too). Semantic caveat: the register's framing is *per-record in-flight*
  liveness; transport reachability is a different *kind* — an awkward-but-permitted tenant. "Add a domain
  here" vs "sibling register" is a low-risk build-time call.
- **U3 — verdict-derivation gate: RESOLVED (in-seam).** The gate only forbids the literal `retrieval [=!]==`
  predicate outside `verdict.ts`. Adding a `StabilityInput` field + a `computeStability` branch *inside*
  `verdict.ts` trips nothing. Only pitfall: don't compute the new contact-freshness boolean via
  `readiness.retrieval === …` inside `aiStateStore.ts` (compute it from the contact stamp instead).
- **U4 — layering / `EnvelopeStream` side-effect: RESOLVED.** No import-layering gate forbids `streaming/` →
  `state/`, and no test pins `EnvelopeStream` purity — **except** the strict subscriber-ordering assertion
  (`[0,1,2,2]`, `EnvelopeStream.test.ts:369`): the contact bump must **not** trigger an extra `notify()`. A
  standalone `originContact` stamp module (stream writes, store reads) is the right shape — keeps
  `EnvelopeStream` from acquiring a `state/` dependency; discipline, not gate-enforced.
- **U5 — blast radius: RESOLVED (small, favorable).** The store/verdict is already the hub: `StatusDeck`,
  `LivenessReadout`, `availability.ts`, `readinessNotice` consume `aiState.verdict`/`phase`, so fixing the
  derivation *in the store* propagates to all of them with no per-consumer change. Only `HealthLitView`
  (`:203/:205`, its own local split) is simplified to a pure consumer. `AdvisoryStore.deriveIsConnected`
  needs **no change** — its streams auto-feed the global contact stamp via the universal `EnvelopeStream`
  seam.
- **U7/U8 — signal-graph + test seams: RESOLVED.** The contact stamp is an input signal (imperative-write /
  computed-read, like `lastStatusSuccessSig`) — no cycle. `__feedForTest`/`__tickClockForTest`/
  `__resetAiStateForTest` already exist; a contact-injection hook matches the pattern.

**Net:** every uncertainty retired in the design's favor; no fail-conditions hit. Remaining unknowns are
execution-risk (live verification under load is inherently fiddly) and two small build-time micro-decisions
(conform-domain vs sibling register; reuse `catching-up` vs a sibling calm cause), not design risk.

## Implementation (complete, 2026-06-30)

Built on branch `worktree-649-connection-reachability`, conforming to the four seams exactly as designed.

**Changes:**
- **New `state/originContact.ts`** — the registered `connection` liveness authority: `bumpOriginContact()` /
  `getLastOriginContactMs()` + the pure `isOriginReachable(lastContactMs, now, windowMs=STREAM_WATCHDOG_STALE_MS)`
  (no signal import, so `EnvelopeStream` stays decoupled).
- **`streaming/EnvelopeStream.ts`** — `bumpOriginContact()` in `handleFrame` + `handleOpen` (no `notify()`).
- **`state/aiStateStore.ts`** — new `computeReachability()` (contact-based, 40 s) feeds `connection.reachable`;
  poll-freshness (`computeStaleness`, 15 s) kept separate; `checkStaleness` bumps the tick on either flip;
  `reachableViaContact` threaded into `computeStability` + `computeVerdict`; `__feedContactForTest` seam.
- **`state/verdict.ts`** — new `'updating'` `ProvisionalCause`; `computeStability` stale branch →
  `updating` (reachable) vs `channel-stale` (not); `computeVerdict` disconnected → `connecting` when reachable;
  `verdictHeadline`/`verdictBody` `'updating'` → calm "Catching up…".
- **`governance/inflight-liveness-projections.v1.json`** — registered the `connection` domain (authority
  `isOriginReachable`, render site `aiStateStore.ts`).
- **Tests** — `originContact.test.ts` (new); extended `verdict.test.ts`, `aiStateStore.test.ts`,
  `EnvelopeStream.test.ts`.

**Static verification (all green):** `tsc` typecheck ✓; 80 unit tests across the 4 files ✓; gates
`check-verdict-derivation` ✓, `check-inflight-liveness` (3 domains) ✓, `check-observed-state-collapse` ✓,
`check-presentation-purity` ✓, `check-message-single-model` ✓; eslint on changed files ✓ (exit 0). (Two
**pre-existing** base-branch failures logged to the inbox, NOT caused by this work: `gen-stream-liveness
--check` drift on `StreamLivenessWindows.java`, and 21 base eslint errors in `errorCatalog.ts`/registry imports.)

**Live browser validation (real UI, dev stack on a worktree backend — required for a user-visible feature):**
the four connection states were exercised through the real store → verdict → render pipeline by starving the
polls (monkeypatched `fetch` on `/api/status` + `/api/inference/status`) while leaving the SSE `EventSource`
streams heartbeating — the faithful 649 scenario:
1. **Baseline** — `phase:connected`, `reachable:true` (green CONN; no false alarm).
2. **Poll starved + SSE alive** — `phase:stale`, `reachable:true`, verdict `transitioning/updating` →
   **status bar "Catching up…"** (calm amber, CONN dot green). *This is the fix — previously "Reconnecting…".*
3. **Recovery** — fetch restored → `phase:connected`, "Catching up…" cleared.
4. **Genuine unreachable** — backend stopped, ~71 s with no contact of any kind → `reachable:false` →
   **status bar "Reconnecting…"**. *The true alarm is preserved — the fix removes the false alarm without
   over-suppressing the real one.*

## Status

`open` — investigation, theorization, settled design, de-risking, **and implementation + live browser
validation** complete (2026-06-30, above). Code on `worktree-649-connection-reachability`; **not yet merged to
`main`**. The OUT-of-scope resource/transport follow-up and the AI-brain re-architecture (Related finding)
remain separate future tempdocs.

---

### (historical) pre-implementation status

`open` — investigation, theorization, a settled general design, **and a pre-implementation de-risking pass**
complete (2026-06-30, above); **implementation not started**.

**Settled design (see "## Design"):** make backend reachability a **declared liveness authority** derived
from the most recent *positive contact of any channel* (a poll success OR any SSE frame/heartbeat), kept
**separate from data-freshness** (poll-specific). Reachable+fresh → operational; reachable+stale → the
**calm "Catching up…"** state; unreachable (no contact of *any* kind) → the alarm. It **conforms to**
(extends, does not fork) four existing seams: the 575 per-domain liveness-authority register
(`inflight-liveness-projections.v1.json`), the 595 verdict/stability axis (`computeStability` branch), the
universal `EnvelopeStream.handleFrame` seam (feeds the contact stamp; pooled or not), and `HealthLitView`'s
existing local poll-vs-channel split (lifted into the store). It **refines** 604 — using heartbeat
*freshness* as a positive-contact veto, never SSE channel up/down — and realizes 562's reverted move-2 intent
using the **already-open** streams (zero new connections), which is why the move-2 "independent probe"
approach is superseded, not retried.

**Deliberately OUT of scope (separable follow-up):** stream consolidation / connection-budget / WebSocket /
WebTransport / HTTP-2 / Tauri-IPC — the *resource* concern (reduces poll starvation / freshness latency) is
not required for *truthfulness* and would be structure for a case 649 does not yet include. HTTP-2 and
WebTransport are gated on TLS-on-loopback; Tauri-IPC on desktop-coupling (Research D6).

**Reach (see "## Reach & principle"):** the design is an instance of two existing principles meeting (575
liveness-authority + 595 axis-split) — conform, don't parallelize. It names the principle
**"earned-failure / positive-evidence liveness"** (a failure claim must be earned by positive evidence, never
inferred from the absence of *one* signal; violation shape = an *unregistered inline-timestamp liveness
derivation*) and records its candidate scope + existing violators (`computeStaleness`; the brain's
`deriveAiState`) **without** building the generalized structure now.

A **related finding** (2026-06-30, above) was also logged: the AI-engine state surface is the FE's most
fragile because it was excluded from the unified verdict kernel and reconciles ~5 independently-polled sources
by hand. Recommended direction is a scoped re-architecture (an `aiEngineVerdict` single authority), **not** a
rewrite — separable from the connection-pool fix and a likely candidate for its own tempdoc when picked up.

## Future directions — building on the reachability authority (research, 2026-06-30)

A generative research pass (4 parallel agents: connection-status UX in mature apps; failure-detector +
adaptive-polling theory; internal seams; HTTP/2-on-loopback feasibility) on *what the 649 authority unlocks*.
**Research only — nothing chosen or built; option-generation, not a commitment.** The app is pre-production
with no users, so all of these are viable whenever.

### The leverage insight

649 did one structural thing with outsized reach: it **decoupled "is the backend alive?" from "how we find
out."** Reachability is now a single, channel-agnostic *positive-contact* authority (`state/originContact.ts`),
independent of any one transport. That decoupling is the leverage point — it makes directions A, E below cheap,
because each just *feeds or reads* the one authority rather than re-deriving liveness.

### Settled long-term design — a managed connection budget (design theorization, 2026-06-30)

The idea clusters below were *option-generation*; this converges them to the **one correct long-term design**,
scope-matched to the problem that actually remains. (Design only — general, not implementation-level; execution
is its own tempdoc, as always intended.)

**The problem the design must actually solve.** 649 fixed the *truthfulness symptom* (honest labels), but the
**root defect persists**: the FE **over-subscribes the browser's ~6-connection-per-host budget** (≈5 always-on
SSE streams + 2 unconditional polls), so under load data is genuinely stale — now honestly labelled, but still
behind. The complete long-term answer to "connection **under load**" therefore has two layers: **honesty**
(Layer 1 — delivered by 649) and **actually working under load** (Layer 2 — the remaining root fix). This design
is Layer 2.

**The design: consolidate the always-on event streams into ONE multiplexed channel.** The connection budget
becomes a *managed* resource with a single permanent holder (the consolidated channel) + transient
freshness polls:
- **Backend:** one fan-in multiplexed endpoint (e.g. `/api/shell-events/stream`) that carries every event
  *kind* (advisory ×2, indexing-jobs, action-ledger, intent) over one connection, reusing the existing
  per-connection lifecycle orchestrator (`SseEnvelopeWriter.attach`/`attachEventOnly`: connected → resume →
  snapshot → subscribe → heartbeat → close) — with a **multi-`streamId` resume model** (per-kind cursors),
  which is the one piece today's single-stream `?since=`/`ResumeTokenCodec` lacks.
- **Frontend:** build the **`MultiplexedStream` kind-router** — the exact wrapper tempdoc 521 §"SSE
  multiplexer" proposed and never built — owning ONE `EnvelopeStream` and dispatching each frame by
  `SseEnvelope.streamId` / `payload.kind` to the matching reducer; extend `EnvelopeStreamPool` from
  *URL-dedup* to *one-connection-with-kind-router*. The bridges (`AdvisoryStore`, `indexingJobsBridge`,
  `ActionLedgerClient`, `bootIntentStreamBridge`) become thin "subscribe to my kind on the shared channel"
  adapters; their **reducers are unchanged** (they already self-route by `frameKind`/`payload.kind`).
- **The one channel's heartbeat feeds 649's `originContact` authority** — it is the natural, single reachability
  witness. The poll is then a **freshness-only** transient consumer with abundant budget.

**Why this scope — root-cause, not short-term, not over-built:**
- It fixes the *present, observed* over-subscription defect at its source (it caused the alpha.28 starvation).
- It **EXTENDS substrate that was *designed* for exactly this**: the `SseEnvelope.streamId`/`payload.kind`
  discriminator is already on the wire, and **both 501 ("one stream, multiple event kinds") and 521
  (`MultiplexedStream`) explicitly proposed it** — yet the codebase repeatedly added *separate* endpoints
  instead (501's runtime-manifest got its own connection — a documented instance of this very
  over-subscription). Only the fan-out (FE router) + fan-in (backend endpoint) layer is genuinely missing.
- It is deliberately **NOT**: *adaptive polling* (a short-term fix — frees only the poll's own slot, leaves the
  5-stream dominance, recurs on the next stream; ruled out by "no short-term fixes"); *HTTP/2 over loopback*
  (deprioritized — browsers are TLS-only for h2 and self-signed-loopback trust is per-OS fragile); a
  *WebSocket switch* (throws away the SSE envelope/resume investment this design reuses); the *UX features*
  (graded badge / two-timestamps / diagnostics — enhancements for cases the present problem doesn't include —
  no users, no observed need); or the *Tauri heartbeat* (robustness the consolidated channel already provides).

**Genuinely-new structure (warranted by the present problem):** (1) the backend multiplexed endpoint + fan-in
writer + multi-`streamId` resume; (2) the FE `MultiplexedStream` kind-router (521's unbuilt proposal). The hard
adapter is `AdvisoryStore` (a dynamic, catalog-driven *N-stream* aggregator). Everything else — envelope,
transport, lifecycle, reducers, and the 649 contact authority — is reused.

### Reach — judging this design's scope (design theorization)

- **It is an instance of a principle 649 *already named* — conform/extend, do not fork.** The original 649
  Reach recorded: *"a finite platform quota (connections, workers, storage, timers) consumed by
  independently-authored components needs ONE owner/multiplexer, or it drifts to exhaustion."* The consolidated
  channel **is that one owner** for the always-on-stream slice of the connection budget — and it literally
  builds 521's proposed `MultiplexedStream` and extends `EnvelopeStreamPool`, rather than inventing a parallel
  mechanism. So this is *building part of the structure for an already-recognized principle, now that the
  present problem requires it* — the same single-authority kernel discipline (574/595), applied to a runtime
  *resource* instead of a visual concept.
- **The recurring shape it surfaces: "N independently-authored always-on consumers of a capped shared
  resource."** Present violations: the 5 always-on streams; the unpooled action-ledger duplicate; and the
  documented pattern of the codebase taking the over-subscribing path despite a multiplex-ready substrate
  (501). **Sharpest *future* scope: the plugin SDK / plugin host** — `subscribeResource`/`subscribeHealth`
  already pool per-URL, and third-party plugins each opening their own streams makes the over-subscription
  *acute and un-reviewable* (521's original motivation was exactly plugin fan-out). Beyond connections, the
  same shape governs web workers, background timers, and storage quotas.
- **Recognize the principle; do NOT pre-build the generalized structure.** The present problem requires
  consolidating the *known, bounded* set of always-on streams + the `MultiplexedStream` router — **not** a
  general "connection-budget broker/register" with quotas and priorities for arbitrary (incl. third-party)
  consumers. Build that general broker only when a second class of consumer actually arrives — i.e. when the
  plugin SDK ships third-party stream consumers. Recording the principle + its candidate scope here is the
  deliverable; separating *recognizing* it from *building* it keeps the insight without premature abstraction.

### Idea clusters (by the four asks) — the option-space the design above converged from

**EXTEND — A. Stream-gated adaptive polling (the standout; uniquely 649-enabled).**
Because reachability no longer depends on the poll, the expensive `/api/status` poll can **back off while an
SSE heartbeat is fresh** and resume fast only on contact loss (+ visibility-throttle when the tab is hidden).
This **directly relieves the 6-connection-per-host pressure that *caused* 649**, cheaply — no HTTP/2, no stream
consolidation. Seam: `utils/statusPoll.ts` / `inferencePoll.ts` are fixed `setInterval` → convert to a
self-rescheduling `setTimeout`; backoff math already exists at `streaming/EnvelopeStream.ts` `scheduleReconnect`,
the heartbeat signal at `handleFrame`. Tradeoff to honour: don't starve *data-freshness* — keep a floor and
don't back off while a count-sensitive surface is visible. Basis: "poll less when a stream is healthy" +
visibility throttling ([reactive polling](https://dev.to/alex-nguyen-duy-anh/reactive-polling-efficient-data-monitoring-3ed)).
*Value high · effort low.*

**NEW UX — B. Honest connection legibility: two timestamps + a calm graded badge.**
Make 649's invisible reachability/freshness split *user-visible* — the most on-brand idea for an honesty-pitched
app. Surface **two quiet timestamps**: "backend last reachable Ns ago" (contact) and "data last updated Ns ago"
(poll freshness) — the Google sync-UX + "Lie-fi" guidance to lead with your own measured contact, not
`navigator.onLine`. Render a **graded calm badge** via the existing tone authority (`utils/statusTone.ts`,
`StatusTier`, `LivenessReadout`'s 3-tone dot) with a calm colour-*temperature* ramp — grey/blue for
"Catching up", **yellow (not red)** for "Reconnecting", red only for confirmed total loss
([Google OHS](https://developers.google.com/open-health-stack/design/offline-sync-guideline)). Frame the
vocabulary in **Calm Technology** (periphery for calm, focus only when action is needed —
[Calm Tech](https://www.calmtech.institute/calm-tech-principles)). Positioning: a concrete answer to a problem
the **local-first manifesto explicitly lists as unsolved** — "how to communicate online/offline, available/
unavailable states" ([Ink & Switch](https://www.inkandswitch.com/essay/local-first/)). *Value high · effort low–med.*

**POLISH/HARDEN — C. Debounce the alarm + make `retrying` first-class.**
Sub-second contact gaps must stay calm — only promote after a real loss window (NN/g: under ~1s show no looped
indicator — [progress indicators](https://www.nngroup.com/articles/progress-indicators/)). Make the staged
promotion explicit and distinct: **Catching up (calm) → Reconnecting (visible backoff = `retrying`) →
Disconnected · "Reconnect now" (actionable `error`)** — don't merge the last two. Verify the SSE client resets
the contact clock on *keepalive/comment* frames, not just data, so a wedged-but-open stream still trips the
window ([RabbitMQ heartbeats](https://www.rabbitmq.com/docs/heartbeats)). *Value med · effort low.*

**EXTEND THE PRINCIPLE — D. Generalize "positive-evidence liveness" with teeth.**
(1) A shared `freshWithin(ts, window, now)` primitive the three authorities delegate to (`inFlightLiveness`,
`aiInstallLiveness`, `originContact`) — also fix `aiInstallLiveness`'s hardcoded `60_000` window (a drift seam)
to be generated like the others. (2) A **lint flagging inline `Date.now() - … > _MS` outside registered
authority modules** — catches the brain's `BrainSurface.deriveAiState` inferring "offline" from *absence* (the
exact anti-pattern) and any future re-fork. (3) Bring the brain's runtime state under the register (the
`aiEngineVerdict` idea). Note: failure-detector theory (phi-accrual) was researched and judged **overkill** for
a single-origin loopback app — the fixed 40s window (≈2.7× the 15s beat) is within best-practice ratios; don't
build CDF math. *Value med–high · effort med.*

**EXTEND — E. Tauri-native, pool-immune heartbeat.**
Because the contact authority is channel-agnostic, a **Rust-emitted `justsearch://origin-heartbeat` event**
(Tauri `emit`/`listen` — the *event bus*, NOT a custom-protocol stream, which WebView2 can't stream) that calls
`bumpOriginContact()` makes reachability **immune to total HTTP-pool exhaustion**. Bridge template already
exists (`router/tauriBridge.ts`, `utils/tauriRuntime.ts`); degrades gracefully in browser/dev. *Value med–high · effort med.*

**NEW UX — F. Connection diagnostics / inspectability.**
Extend `EnvelopeStream` with per-stream `lastFrameMs`, surface it past the single-boolean collapse into an
extended `AiConnection`, and render a "why is it catching up?" view in the existing `LogSurface` /
`diagnosticChannelStrategy` — conforming to the tempdoc-658 diagnostic-bundle concept. On-brand for an honesty
product. *Value med · effort med.*

### The structural root fix (the scoped-out resource follow-up — research verdict)

Ranking for *reducing connection pressure* (its own future tempdoc):

| Rank | Option | Why |
|---|---|---|
| 1 | **Consolidate the ~5 always-on SSE streams into ONE multiplexed stream** | Low effort, fully portable, no TLS; the 6-cap only bites because we run ~5 separate streams (521/501 prior art) |
| 2 | Single `ws://127.0.0.1` multiplex | WebSocket per-host cap ≈255, not 6; `ws://` loopback needs no TLS |
| 3 | Tauri native event IPC for push | Pool-free by construction, but Tauri-only |
| 4 | Adaptive polling (= A) | Relieves the *poll* specifically; cheap; doesn't fix the SSE fan-out |
| 5 | **HTTP/2 over loopback (deprioritized)** | Browsers are **TLS-only** for HTTP/2 (no h2c, unchanged 2025–26); self-signed-loopback trust is real but **per-OS fragile** (WebView2 redirect caveat, WKWebView WS gap, Tauri lacks native HTTPS devUrl) — highest effort, lowest portability |

So the eventual resource fix is **consolidation, not HTTP/2** — an evidence-backed reversal of the naive
"just use HTTP/2" instinct.

### Top picks (value × builds-on-649 × feasibility)

1. **A — stream-gated adaptive polling** (cheap; uniquely unlocked by 649; relieves 649's own root cause).
2. **B — two-timestamp calm graded badge** (high-value, on-brand, reuses the tone authority).
3. **C — alarm debounce + `retrying`-as-first-class** (low-risk hardening against flapping).
4. **E — Tauri pool-immune heartbeat** (high robustness; trivial via the channel-agnostic authority).
5. **D — generalize the principle (freshWithin + lint + brain under register)** (extends with enforcement).

### Reach — the broader principle this surfaces

- **One authority, many downstream wins.** Decoupling "alive" from "how we learn it" is a *leverage point*: it
  is what makes adaptive polling (A), multi-channel robustness (E), and quality grading (B) cheap — the payoff
  of single-authority discipline applied to a *runtime* concept, not just a visual one.
- **"Positive-evidence liveness" has external provenance.** It is the convergence of three named ideas: Calm
  Technology's **alarm budget**, the **Lie-fi** principle (trust your own measured contact, not the OS/browser
  online flag), and the failure-detector insight that **suspicion should come from observed heartbeats** (the
  useful half of phi-accrual, minus the CDF math). 649 is a concrete, citeable instance — not invented here.
- **Calm Technology as an app-wide rubric.** The alarm budget could govern *every* status/alarm surface, not
  just connection (candidate audit: proportionate tone everywhere; aligns with the 562 alarm-budget thesis).

## Frontend / user-facing design (live-grounded, 2026-06-30)

A live inspection pass (started the dev stack, opened the real UI in a browser) — *not* judged from the
tempdoc. The System Health surface happened to be **organically in the 649 "Catching up…" state** (the polls
starved behind the 5 always-on streams — the exact 649 condition), so all three connection-status surfaces
were observable at once.

### The user-facing consequence the live UI revealed (the headline)

**649 made the connection *words* honest, but the calm state still renders with a WARNING colour on the two
most-glanceable surfaces.** Confirmed at the data level for the live "Catching up…" state: `verdict.severity =
'busy'` → `verdictTone = 'info'` (**calm**), yet `statusTier = 'degraded'`. The result on screen:

| Surface | Tone source | Rendered tone for "Catching up…" |
|---|---|---|
| System Health **header badge** | `verdictTone(severity)` → `info` | **calm** green/teal ✓ |
| Bottom **status-bar pill** (`StatusDeck`) | `inferencePillTone(statusTier='degraded')` → `warning` | **amber/orange** ✗ |
| **CONNECTION → Retrieval** dot (`LivenessReadout`) | `statusTier='degraded'` → `--accent-warning` | **amber/orange** ✗ |

So the *same* calm state is calm in one place and an alarm-colour in two — and the two amber ones (the persistent
bottom-bar pill + the connection dot) are exactly the indicators a user glances at. The 649 core fixed the
sentence ("Catching up…") but left the **colour** alarming, which **undercuts the 649/562 "alarm budget" at the
pixel level** — the user is *told* calm and *shown* alarm.

### The correct frontend design — conform to the ONE tone authority (fix a fork)

This is a single-authority correction, exactly the 595 kernel: **every connection-status surface must project
its tone from the ONE verdict-tone authority (`verdictTone` / `severity`), not from a parallel `statusTier` →
tone mapping.** Today `computeStatusTier` collapses *every* `transitioning` verdict to `degraded`, so the
calm-busy transition (`updating` / `catching-up`) and an impairing degradation get the same amber. The design:

- **The calm transitioning state must map to a calm tier/tone** (info), distinct from an impairing
  `degraded`. Whether realized by `computeStatusTier` distinguishing severity `busy` (calm) from `warn`/`error`
  (amber), or by the consumers (`StatusDeck.inferencePillTone`, `LivenessReadout.toneFor`) reading
  `verdictTone(severity)` instead of `statusTier` — the invariant is the same: **`statusTier` must stop being a
  second tone authority that forks from `verdictTone`.** Then "Catching up…" looks calm everywhere.
- This is fixing a *fork*, not adding structure — it conforms to 595's "one verdict, one tone, every surface
  consumes it." It is the **primary** user-facing design for this tempdoc: the genuine consequence of the
  649 calm-state design that only the live UI exposed.

### Secondary (live-grounded placement of the deferred UX)

- The **CONNECTION panel** already has a structured rows layout (Retrieval · API endpoint · Index state ·
  Uptime) and the index cards already render a **"Last known"** freshness label — an existing honesty pattern.
  That panel is the natural home for cluster-B's **two timestamps** ("backend last reachable Ns ago" / "data
  last updated Ns ago") — *extend the existing rows*, don't invent a surface. Most of cluster-B's value,
  though, is just the tone fix above.
- The graded-calm colour ramp (cluster B) largely reduces to the tone-authority fix: give the reachable-but-stale
  state a calm tone in the one mapping.

### Indirect (the settled consolidation design)

Purely changes *how often* the surface sits in "Catching up…" under load (consolidation makes it rarer); **no
new UI element**. It does not change any of the above.

### Scope & reach

- **Scope:** the primary frontend design is the **tone-authority correction** (the calm state must *look*
  calm) — small, presentation-layer, conforming to 595; it needs neither the consolidation (separate resource
  tempdoc) nor the two-timestamp enhancement to ship. The two-timestamp legibility is a secondary enhancement
  now correctly *placed* (the existing CONNECTION panel) rather than dismissed.
- **Reach:** this is the **same "one axis was two / single authority" shape** the rest of 649 keeps surfacing —
  `statusTier` is a *second tone authority* forking from `verdictTone`, and the new calm state is what exposed
  the fork (older states happened to agree). Candidate scope: every site where `statusTier` drives tone/colour
  independently of `verdictTone` (`StatusDeck`, `LivenessReadout`, any status dot). Recorded; the in-scope fix
  is to re-seat those on `verdictTone`, not to build new structure.

### Implemented (2026-06-30)

The primary tone-authority fix + the secondary two-timestamp legibility are **implemented and live-verified**
(branch `worktree-649-toneux`). Changes:

- **One verdict-derived `statusTone`** (`aiStateStore.computeStatusTone`, the tone sibling of
  `computeStatusLabel`; new `AiState.statusTone: NoticeTone`). `StatusDeck.inferencePillTone` and
  `LivenessReadout.toneFor` now read it (the latter gains `info`→`--accent-tint` and `error`→`--accent-danger`
  dot tones — both pre-existing tokens, no new colour). `statusTier` is retained but no longer drives tone.
- **The ramp made real:** `channel-stale` ("Reconnecting…") was elevated `busy`→`warn` in `computeVerdict`, so
  lost contact is **amber**, distinct from the calm **info/tint** `updating` ("Catching up…"); `unreachable`
  stays **error/red**. One calm→amber→red ramp on every surface.
- **Two timestamps:** `AiConnection.lastContactMs` surfaced; the Health CONNECTION panel renders "Backend
  reachable" / "Data updated" via a new `relativeTime.formatRelativeMs` (epoch-ms + sub-minute "Ns ago" tier).
- **Tests:** `aiStateStore`/`StatusDeck`/`verdict` updated + a new `LivenessReadout.test`; typecheck + unit
  green; token/contrast/verdict gates green.
- **Live-verified** (dev stack + browser): the organic "Catching up…" rendered `info` (teal,
  `oklch(0.75 0.15 180)`) on the Health badge, the Retrieval dot, AND the status-bar pill (all matched); an
  induced contact loss flipped all three to `warning` (amber, `oklch(0.75 0.18 70)`) "Reconnecting…"; the two
  timestamps ticked honestly ("reachable just now / 2 min ago", "data 15s ago / 5 min ago").

Still out of scope (own future tempdoc): the SSE-consolidation "managed connection budget"; catalog
enhancements (alarm-debounce, retrying-vs-error nuance, Tauri heartbeat, positive-evidence lint).
