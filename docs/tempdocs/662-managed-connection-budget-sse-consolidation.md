---
title: "Managed connection budget — classify long-lived origin channels by demand and gate the always-on footprint"
type: tempdoc
status: open
created: 2026-06-30
related:
  - 649-connection-truthfulness-under-load   # parent: 649 fixed the truthfulness; this fixes the cause
  - 562-installer-build-pipeline-smoothness   # grandparent: the alpha.28 "Reconnecting under load" finding
  - 521                                        # the unbuilt `MultiplexedStream` this builds on
---

# 662. Managed connection budget — SSE stream consolidation

> **Spun out of 649 (D5 / "Settled long-term design").** 649 deliberately scoped the *truthfulness* fix
> (report "Catching up…" honestly, separate reachability from freshness) and explicitly left the *resource*
> fix — the actual cause of the starvation — to its own tempdoc. This is that tempdoc. 649 already settled the
> general design direction; this doc carries it forward to an implementable design. Tempdocs are dated
> history — verify 649's claims against `main` before trusting (it is the higher-numbered, more-current doc).

## The problem (the cause 649 chose not to fix)

The browser allows ~**6 concurrent connections per host** (HTTP/1.1, WontFix). The shell holds **5+ long-lived
SSE streams** to the backend origin (advisory operation-completed, advisory health-recoverable,
indexing-jobs, action-ledger, health-events, …). Under enrichment/ingest load these saturate the pool, so the
cheap `/api/status`/`/api/inference/status` polls **queue behind them and never resolve within the staleness
window**. 649 confirmed this at the source: a *fresh, non-browser* connection gets `READY` instantly while
in-browser polls hang in `pending`.

649's reachability authority makes this **truthful** (a stale poll while the streams heartbeat reads as the
calm "Catching up…", not a false "Reconnecting…"). But the system still *routinely sits in* "Catching up…"
under normal load, because the polls genuinely can't get a connection. The user is told the truth, but the
truth is "your data is lagging" far more often than it should be. **This tempdoc removes the cause so
"Catching up…" becomes rare instead of routine** — and frees headroom for any future origin request.

Why no incremental fix works (649 §"Consequence for the obvious fix"): adding *another* request (e.g. a
dedicated liveness probe — 562's reverted move 2) just queues behind the same exhausted pool and slightly
*worsens* the pressure. The ceiling is structural; the only fix is to **stop consuming so many connections**.

## Settled design direction (from 649 — REFINED by the 2026-07-01 design pass below)

> **Heads-up (2026-07-01):** 649 handed this off as *"one multiplexed SSE channel instead of N."* The design
> pass below (`## Design`) **refines** that: "multiplex all N" is one of *three* placements, and the wrong
> default. The settled direction is **classify each channel by its real demand** (breadth → the poll, depth →
> lazy, irreducible always-on event → *then* a multiplexer scoped to that small set), governed by a
> transport-agnostic budget register. Read this block as the inherited 649 framing; read `## Design` for the
> settled result. The two paragraphs below are kept as the historical handoff.

**One multiplexed SSE channel instead of N.** Collapse the independently-authored streams into a single
long-lived connection whose frames carry a discriminator, with a FE fan-out layer that routes each frame to
its consumer. This is the "managed connection budget": a finite platform quota (6 connections) gets **one
owner/multiplexer** instead of N independent claimants.

Building blocks that already exist (extend, don't fork):
- The **`SseEnvelope`** wire type already carries a `streamId` + `payload.kind` discriminator — it is
  **multiplex-ready by construction**; the missing piece is only the fan-out/fan-in layer, not the wire shape.
  (Refined below: the *envelope* is ready, but the *resume protocol* is single-stream — Investigation §D.)
- **521 proposed a `MultiplexedStream`** — **correction (Investigation):** 521's `MultiplexedStream` ("one
  connection per channel-id, fan-out, ref-counted") **was built**, as `EnvelopeStreamPool` (521 §γ3). That
  pool dedups *within* a URL (N subscribers → 1 socket per URL); it does **not** collapse *across* URLs. The
  genuinely-unbuilt thing is a **cross-channel** multiplexer (distinct URLs → one socket) — a different,
  larger structure than 521 proposed, and the one the design below scopes to the irreducible set only.
- **`EnvelopeStream`** already owns reconnect + heartbeat-watchdog liveness; a cross-channel multiplexer
  reuses that transport/liveness machinery but must replace its single-cursor seq/token bookkeeping with a
  per-`streamId` demux (Investigation §D).

A **"live-channel budget register"** (governance-register pattern, like the other `*.v1.json` registers)
would make the connection budget an explicit, gated invariant: every long-lived origin channel must be
declared, and the gate fails the build if the declared physical-connection count exceeds the budget — so the
exhaustion class can't silently reappear when a future feature adds "just one more stream." (The design below
keeps this, makes it **transport-agnostic**, and adds the runtime-peak half a static count structurally
lacks.)

## Scope

- **IN:** the fan-out/fan-in multiplexer over `SseEnvelope`; migrating the existing ~5 streams onto it; the
  budget register + gate; preserving per-consumer reconnect/liveness semantics through the single channel.
- **OUT (separate, gated on a prior TLS/desktop decision — 649 §D6):** the transport-swap family
  (HTTP/2/WebTransport need TLS-on-loopback; Tauri-IPC `ipc://localhost` bypasses the pool but couples to the
  desktop shell and diverges in browser/dev). These are *alternatives* to multiplexing, not part of it; record
  them as candidates, decide separately.
- **OUT:** anything touching 649's reachability/verdict truthfulness layer — that is shipped and correct;
  this doc only reduces how often its calm state is entered.

## The principle this carries (named in 649's "Reach")

> A finite platform quota consumed by independently-authored components needs **one owner/multiplexer, or it
> drifts to exhaustion.**

Candidate scope beyond SSE: any other place where independent features each grab a scarce shared resource
(timers, observers, workers, file handles) without a budget owner. Recognize it; do **not** pre-build the
generalized "resource-budget framework" — apply it to the connection budget here, and let a second instance
justify the generalization (per `structural-defects-no-repeat` / AHA).

## Open questions for the design pass
- Fan-out ordering/back-pressure: does one slow consumer head-of-line-block the others on the shared channel?
- Reconnect semantics: a single physical reconnect must resume *all* logical streams' positions (per-`streamId`
  resume), not just one.
- Backend side: does the multiplexed endpoint aggregate existing producers, or do producers publish to one
  fan-in writer? (Relates to `SseEnvelopeWriter` on the Head.)
- The budget number: what is the real safe ceiling (reserve headroom for the polls + any user-initiated
  request), and how is it enforced cross-browser (WebView2 inherits the same limit — 649 §D6)?

## Verification (when built)
- A measured repro of the starvation (the 649 condition) shows the polls resolve promptly with the streams
  consolidated — i.e. the surface no longer enters "Catching up…" under normal enrichment load.
- The budget register/gate fails the build on an Nth undeclared physical channel.
- Every migrated consumer still receives its frames + self-heals on a dropped channel (no regression vs the
  per-stream behavior).

## Investigation (2026-07-01)

Autonomous pass — verify 662's premise against shipped `main`, correct the building-block claims, and
critique the "one multiplexed channel" direction for feasibility/scope. **No solution chosen** (design still
deferred); evidence + critique only. All `file:line` against the main checkout `F:\justsearch-public`.

### A. Status reconciliation — 649 *shipped*, so 662 buys *freshness*, not *correctness*

649's epistemic fix is **merged** (commit `dc78c05`, "connection truthfulness under load"). Verified in
source: the origin-contact authority `state/originContact.ts` (`isOriginReachable`), the `connection`
liveness domain registered in `governance/inflight-liveness-projections.v1.json:30-38`, the contact bump on
every SSE frame/open (`streaming/EnvelopeStream.ts:244,256`) **and** on every poll success
(`state/aiStateStore.ts:829`), and the kernel doc row that documents it (`docs/explanation/27:73`). So the
false **"Reconnecting…"** alarm is already gone — a poll starved behind the pool while the streams heartbeat
reads as the calm **"Catching up…"**.

**Consequence for 662's value proposition (sharpen the doc's own framing):** with 649 shipped, the surface is
already *truthful*. 662 does **not** fix a correctness bug; it reduces how *often* the calm state is entered —
a **freshness/latency** improvement (data lags less under load), not a trust fix. The tempdoc says this ("makes
Catching up rare instead of routine"), but it should be stated as the headline cost/benefit, because it sets
the bar the work must clear: full SSE consolidation is the **largest-blast-radius** option among the resource
fixes (new backend endpoint + a changed resume protocol + migrating every consumer + a register/gate), spent
on a *latency* win for an *already-honest* state. That is a legitimate buy — but it must beat the cheaper
tiers and the Tauri-IPC alternative on the merits, not by default (see D, E).

### B. The stream inventory is verified — 5 always-on, and the lazy/other set is *larger* than 649 said

Traced every SSE opener in `modules/ui-web/src` (main only). **Every** SSE routes through `EnvelopeStream` —
there is no raw `new EventSource` in production code (the only `new EventSource` calls are the default
`eventSourceFactory` inside `EnvelopeStream.ts:125` and `handshake/CapabilitiesHandshake.ts:65`). So the
"universal stream seam" 662/649 relies on is real and single.

**5 distinct always-on EventSources** (opened at boot) — exactly the 649 §B set:

| # | URL | Opener | Pooled? |
|---|-----|--------|---------|
| 1 | `/api/indexing-jobs/stream` | `substrates/tasks/indexingJobsBridge.ts:344` (`subscribePooled`) | **pooled** |
| 2 | `/api/advisory/operation-completed/stream` | `components/advisory/AdvisoryStore.ts:466` (`new EnvelopeStream`) | own |
| 3 | `/api/advisory/health-recoverable/stream` | `components/advisory/AdvisoryStore.ts:466` | own |
| 4 | `/api/action-ledger/stream` | `operations/ActionLedgerClient.ts:387` (`AiActivityDigest.ts:99`, always-mounted `Shell.ts:2135`) | **un-pooled** |
| 5 | `/api/intent/stream` | `api/intent/bootIntentStreamBridge.ts:73` (via `BackendStreamSource.ts:96`, `Shell.ts:1462`) | own |

So at rest **5 SSE + 2 polls (status, inference) = 7 connection-wanters for 6 slots** — the structural
saturation 649 diagnosed is confirmed at baseline (the polls share the single free slot even before any lazy
surface opens). The premise holds.

**Corrections to 649's lazy list (the picture is worse than 649 documented):**
- **New lazy stream 649 missed:** `/api/diagnostic-channels/head-log/stream` (`views/LogSurface.ts:189`),
  opened when the Log surface mounts.
- The **duplicate un-pooled action-ledger** (`ActionLedgerView.ts:163` → a *second* `EnvelopeStream` to
  `/api/action-ledger/stream`) is verified — `openActionLedgerStream` builds an un-pooled stream per call, so
  the Activity surface opens a 6th socket to a URL stream #4 already holds.
- Two plugin-host data helpers open lazy pooled streams on demand (`plugin-api/capabilities/data.ts:94,102`).
- `/infra/capabilities/stream` and the contract-events stream have openers
  (`CapabilitiesHandshake.ts:164`, `api/contract/contractEventsBridge.ts:68`) but **no production boot path in
  `ui-web/src`** instantiates them (host-driven, outside this module) — so 649 was right to exclude
  capabilities from the always-on 5.

### C. **The budget is transport-agnostic — counting only EventSources is a false-assurance trap**

The single most important correction. The 6-per-host pool is consumed by **every** long-lived origin
connection, not just SSE EventSources. Found in the same trace:
- **Long-lived fetch-body streams** that are *not* EventSource and bypass `EnvelopeStream` entirely: the
  chat/agent/summarize generation streams (`api/streams.ts` `consumeShapeStream`,
  `plugin-api/pumpHostAiStream.ts`, POST response-body streaming) and **two Health surfaces** reading
  `/api/health/events/stream` + `/api/condition-recovery-index/stream` via `this.doFetch` body-streaming
  (`views/HealthSurface.ts:575,650`).
- The 2 timeout-less polls (`utils/statusPoll.ts:31`, `utils/inferencePoll.ts`).
- Transient fetches (lazy JS chunks, icons, search requests, file-action calls).

**Implication for the register/gate.** A "live-channel budget register" that enumerates only SSE EventSources
would pass the build while a live **AI generation** (a long-lived POST body-stream) plus enrichment load still
saturates the pool — i.e. it would *certify* a budget it does not actually enforce. The register MUST be
**transport-agnostic**: it governs *all* long-lived origin connections (EventSource **and** streaming fetch),
and the budget arithmetic must reserve a slot for an in-flight chat/agent stream. Consolidating the 5 SSE
streams to 1 is necessary but **not sufficient** on its own; the doc's "5 SSE → 1" framing understates the
denominator.

### D. The "wire is multiplex-ready, only fan-out is missing" claim is **half-right** — the *resume protocol* is single-stream by construction

662 says: *"the missing piece is only the fan-out/fan-in layer, not the wire shape."* The **envelope** is
indeed multiplex-ready (`SseEnvelope` carries `streamId` + `frameKind` + `payload`, `SseEnvelope.java:36-37`).
But the **resume/sequencing protocol is single-stream end to end**, and that is the real work the doc elides:

1. **Server seq + history are per-channel.** Each `SseStreamChannel` owns its *own* `StreamSequenceTracker`
   and *own* `FrameHistoryRingBuffer` (`SseStreamChannel.java:41-43`). There is no cross-stream sequence.
2. **The resume token encodes exactly one `(streamId, seq)`** (`ResumeTokenCodec.java:42`), base64'd, and the
   writer reads exactly one `?since=` param and resumes exactly one channel
   (`SseEnvelopeWriter.java:203-213`, `attemptResume` rejects a token whose `streamId` ≠ its channel,
   `:137-139`).
3. **The FE `EnvelopeStream` tracks one `seq` and one `resumeToken`** (`EnvelopeStream.ts:97-98,236-237`) and
   sends one `?since=` on reconnect (`:203-208`). On a multiplexed wire carrying 5 channels' *independent* seq
   counters, a single `seq`/`resumeToken` field is meaningless — it would jump between unrelated streams and a
   reconnect would resume only whichever stream emitted last, silently dropping the other 4's missed frames.

So multiplexing requires **new protocol**, not just a fan-out shim:
- A **composite resume cursor** — one token encoding a map `{streamId → seq}` (new codec), OR the FE holds N
  per-`streamId` tokens and sends them together; the endpoint replays each sub-channel from its own seq.
- A **`MultiplexedSseWriter`** that subscribes to N channels, sends each state-shaped sub-stream's snapshot on
  connect, forwards all N broadcasts over one client, emits **one** shared physical-channel heartbeat, and
  unsubscribes all N on close. The producers (change registries) don't change; this is a new aggregating
  controller (e.g. `/api/shell-events/stream`) + writer variant. Feasible and clean, but genuinely new
  backend code.
- A **FE `MultiplexedStream`** that **reuses** `EnvelopeStream`'s transport/reconnect/watchdog/contact-bump
  but **replaces** its single-cursor bookkeeping with a per-`streamId` demux (per-stream seq, resumeToken,
  reducer, and consumer fan-out). 662's "the multiplexer wraps/extends `EnvelopeStream`" is right in spirit
  but must carry this caveat: you inherit the *liveness machinery*, not the *seq/token semantics*.

This is exactly 662 open-question #2 ("a single physical reconnect must resume *all* logical streams'
positions"); flagging it here as the **load-bearing** complication, not a footnote.

### E. Critique of the open questions + a missed-weight alternative

- **Open-Q #1 (HOL blocking / back-pressure) is largely a non-issue.** SSE is server-push delivered in order
  to a single `onmessage` handler; all FE consumers run on the *same JS event loop*, so a "slow consumer" is a
  synchronous callback, not a wire stall. The real risk consolidation introduces is the **single point of
  failure** (one stream's flood/bug now affects all live updates) — already named in 649 §F with the standing
  mitigation: keep reachability `OR(poll, stream)` so the consolidated channel is never the *sole* signal
  (and 649 shipped exactly that). Re-aim open-Q #1 from HOL to SPOF.
- **An under-counted upside:** consolidation collapses **N reconnect state-machines + N heartbeat watchdogs
  into 1** (one physical channel's liveness instead of per-bridge). That is a real FE simplification beyond the
  connection count, and strengthens the case relative to the cheaper tiers.
- **Tauri-IPC is unfairly lumped with the TLS-gated transports (Scope "OUT").** 662 bundles the whole
  transport family as "gated on a prior TLS/desktop decision." But only HTTP/2 and WebTransport need
  TLS-on-loopback; **Tauri's `ipc://localhost` needs no TLS** (649 §D6 / D-research confirms it is intercepted
  natively and bypasses the browser HTTP pool *by construction*). For the **shipped** target (Tauri/WebView2
  desktop), an IPC-carried shell-event channel dissolves the 6-limit with **no multiplexed-resume protocol at
  all** — a different, possibly *smaller*, change than §D's new endpoint + composite cursor. Its real cost is
  a *different axis* than TLS: desktop-coupling + dev/pure-browser divergence. And note the external-MCP-client
  argument for keeping HTTP applies to the *search/knowledge* APIs — these **shell-internal event** streams
  are consumed only by the user's own shell, so the coupling objection is weaker for them specifically. The
  design pass should run **multiplex-effort vs Tauri-IPC-coupling head-to-head**, not dismiss IPC by
  association with TLS.
- **The cheaper tiers (649 §G) don't fix the baseline but should be recorded, and one is free.** Routing the
  **action-ledger duplicate** through `EnvelopeStreamPool` is a no-regret win independent of any structural
  choice (kills socket #6 on the Activity surface; `openActionLedgerStream` just needs to go through
  `subscribePooled` like indexing-jobs already does). Lazy-ifying more streams reduces *peak* but not the
  always-on *baseline* — pool-dedup "cannot drop below the 5 distinct URLs" (649 §B, verified: the 5 are
  distinct URLs). So only consolidation / lazy-fold-into-poll / IPC actually drops the baseline under
  `6 − 2 polls − headroom`. 662 is right to reach for a structural fix; it should still *record why* the cheap
  tiers are insufficient (this paragraph) rather than skip them, and bank the free duplicate-dedup regardless.

### F. The budget number (open-Q #4) — concrete arithmetic

6 per host (WontFix, WebView2 inherits it). Reserve: **2** for the polls (status + inference) + **~1–2**
headroom for transient fetches (lazy chunks, icons, search, file-action calls) **and** an in-flight
chat/agent generation stream (§C). ⇒ the always-on **long-lived** budget is ≈ **2–3** connections. Full
multiplex takes the 5 SSE to **1**, leaving generous headroom even with a live generation open. The register
should express the budget as *max concurrent long-lived origin connections*, classify each declared channel
**always-on / lazy / transient**, and account for **worst-case concurrent lazy opens** (static count ≠ runtime
peak) — a flat "N channels" list under-models it.

### G. Register/gate feasibility — mirrors `execution-surfaces`, with one schema subtlety

A `governance/live-channels.v1.json` + a gate scanning `modules/ui-web/src` for stream openers
(`new EnvelopeStream`, `subscribePooled`, and — per §C — the fetch-body stream helpers) that fails on (a) an
**undeclared** opener or (b) declared **always-on count > budget**. This is the proven `execution-surfaces`
shape (`governance/execution-surfaces.v1.json` + `execution-surface` gate) and inherits its **honest limit**
verbatim: import/grep-visible coverage only — a re-modeled opener that doesn't match the patterns slips
(553 §5). Schema subtlety from §C/§F: the register needs an **always-on/lazy/transient** classification and a
**transport** field (SSE vs fetch-stream), not a flat URL list, or the gate certifies a budget it doesn't
hold.

### H. Docs to update when built
- `docs/explanation/27-frontend-presentation-kernel.md:73` — the live-connection row's parenthetical ("the
  always-on streams exhaust the browser's 6-per-host pool so the cheap polls hang") changes once the streams
  are consolidated + budgeted.
- `docs/reference/api-contract-map.md` — register the new multiplexed endpoint (if the §D route is chosen).

### I. Net assessment

The premise is **confirmed** (5 always-on SSE + 2 polls > 6 at baseline) and 662 attacks the genuine
structural cause. Three substantive corrections for the design pass: **(1)** scope it honestly as a
*freshness* win over an already-truthful surface (649 shipped), so it must beat the alternatives on merit;
**(2)** the budget is **transport-agnostic** — govern long-lived *fetch* streams too, or the register gives
false assurance; **(3)** the *resume protocol* (not the envelope) is the load-bearing work, and **Tauri-IPC**
deserves a real head-to-head vs multiplex for the desktop target rather than dismissal-by-TLS. Plus one free
no-regret move available now regardless of direction: pool-dedup the action-ledger duplicate.

## Theorization (2026-07-01) — directions, framings, principles (NOT final design)

Deliberately opening the aperture before any design is settled. 662 currently pre-commits to *one* answer
("one multiplexed SSE channel"); this section holds several framings at once, surfaces the structural family
the doc skips, names the pivotal unexamined assumption, and asks whether 662 points at a broader invariant.
None of this is a commitment — it is option-generation to inform the design pass. (Companion to the
Investigation above, which is the evidence; this is the *spread of directions* that evidence allows.)

### A. Six ways to reframe the problem (the name pre-selects the fix)

1. **Tragedy of the commons / missing budget owner.** 6 slots are a commons; each feature grabs greedily;
   nobody owns the budget. → an *owner + quota* (662's framing). Fix lives in governance.
2. **The scarce resource is *permanence*, not *count*.** The cost isn't "a connection," it's "the *right to
   hold a socket indefinitely*." Polls/fetches are transient *borrows*; always-on streams are permanent
   *claims*. → don't multiplex everything; **cap how many things may be always-on** and tax permanence. Points
   at a *lease/eviction* model, not a *multiplex-all* model.
3. **Push where pull suffices (over-real-time).** Each always-on stream is justified by "low idle latency for
   a badge," but most carry *steady-state* facts (advisory counts, job state) a 10 s poll already covers. →
   split **depth** (sub-second updates *while a surface is open* → stream) from **breadth** (idle badges →
   poll). "Real-time-ness is a per-channel cost to be earned" (649 §D).
4. **The poll is *already* a multiplexer.** `/api/status` aggregates readiness + composites + tri-state onto
   **one** connection. The system already owns a fan-in channel — it's the poll. → instead of building a
   *new* multiplexed SSE bus, **widen the existing multiplexed poll** to carry the streams' steady-state and
   let the streams go lazy. Reuse-over-build (the AHA discipline) applied to the transport itself.
5. **A layering inversion — the FE pretends it owns a platform quota.** The *browser* owns the 6-slot pool;
   the FE treats it as infinite. → the FE needs a **broker/scheduler** mediating feature-demand against
   platform-supply, exactly like a thread pool or a DB connection pool. Note this is a *different mechanism*
   from a register: a **register declares + gates statically**; a **broker schedules dynamically at runtime**.
   They cover different halves (drift vs peak — see E).
6. **Stop optimizing against "6"; design for O(1) always-on cost.** The win that matters is *decoupling
   feature growth from the connection budget*, not "fit in 6." Multiplex → O(1) SSE; IPC → O(0) browser-pool
   cost; lazy+poll → O(visible surfaces). The register then guards the *O(1) invariant*, not a magic number
   that the next feature quietly violates.

### B. The wider solution space — **three structural families**, not one

662 reads as if "multiplex" is the settled structural answer. It is one of **three** families, and the doc
should weigh them head-to-head:

- **(A) Multiplex (662's current pick).** Keep all 5 streams always-on; collapse onto one connection with a
  discriminator + FE fan-out. *Buys:* O(1) SSE, one liveness watchdog instead of N (an under-counted upside,
  Investigation §E). *Costs:* a new aggregating endpoint + the **composite-resume protocol** (Investigation
  §D — the real work), a **single point of failure** for all live updates, and a broad all-or-nothing cutover
  on shared `main`.
- **(B) Demand-driven (the family 662 omits).** Make every stream **lazy** (open only while its surface is
  visible; the *already-built* resume-token replay covers events missed while closed) and **fold idle badges
  into the poll** (framing #3/#4). *Buys:* always-on baseline drops toward *the poll alone*, **with no new
  backend endpoint and no composite-resume protocol** — each lazy stream stays single-stream, so §D's hard
  part never arises. Incrementally shippable per-stream. *Costs:* requires classifying each stream's true
  idle-latency need; a badge that must update while its surface is hidden has to move to the poll (data-shape
  work), and lazy open/close adds reconnect churn.
- **(C) Transport escape.** Tauri-IPC (desktop) bypasses the browser pool by construction, no TLS, no
  composite resume (Investigation §E). *Buys:* the limit *dissolves*. *Costs:* desktop-coupling +
  dev/pure-browser divergence; only covers the shipped app, not browser-dev.

**The pivotal observation:** (A)'s entire complexity — the composite-resume protocol, the SPOF — **exists only
because you force 5 streams to stay always-on and coexist on one wire.** (B) dissolves that complexity by
attacking the *always-on* assumption instead of the *coexistence*. So (B) may **dominate** (A) on cost while
hitting the same baseline target. That makes (B) a first-class contender the design pass must not skip — not a
"cheaper partial" (Investigation §E), but a *different structural answer*.

### C. The convergence insight (the cheap and the structural may be the same move)

If framing #4 holds — most always-on streams carry steady-state the poll could carry — then **"widen the poll
+ lazy the streams"** simultaneously: (a) drops the always-on baseline toward 1 (the poll), (b) makes the
remaining streams demand-driven (cost = visible surfaces, which the 6-slot budget easily holds), (c) needs
**zero** new multiplexed endpoint and **zero** composite-resume work, and (d) composes with the budget
register either way. Where 649's convergence was "consolidation *is* the liveness fix," 662's may be
"**folding steady-state into the poll *is* the connection fix** — the multiplexer is only needed if the audit
proves the streams genuinely cannot be lazy." That inverts the doc's default.

### D. The pivotal hidden assumption (resolve this *before* settling design)

**"All 5 streams must be always-on."** This is inherited, not established (649 §B: "always-on by *necessity*
vs by *accident*"). It is the single fact that decides (A) vs (B), and it is **answerable by a cheap audit** —
for each of the 5, ask: *does anything need this stream's data while its surface is NOT visible?*
- `indexing-jobs` → a global progress badge in the chrome ⇒ plausibly genuinely always-on (or poll-carried).
- `advisory` ×2 → operation-completed / health-recoverable toasts ⇒ do these fire while no surface shows
  them? If they're notifications, maybe; if they back a panel, lazy.
- `action-ledger` → the always-mounted activity digest count ⇒ a badge ⇒ **poll candidate** (framing #4).
- `intent` → event-only router input (`IntentStreamController` is `attachEventOnly`) ⇒ genuinely push, but is
  it needed pre-interaction? Possibly lazy-on-first-route.

If even 2–3 are accidentally-always-on, (B) wins outright. **This audit is the highest-information next step
and forecloses nothing** (no-regret, §F). 662 settling on (A) *before* this audit would be premature
structure.

Other buried assumptions, each of which redirects the design if false:
- **"Multiplexing reduces risk."** It *concentrates* it. Independent streams degrade *independently*; a
  multiplexed channel degrades *atomically* — one fan-in bug or one flooding sub-stream takes down all live
  updates. There is a real **resilience-vs-efficiency** tradeoff; the per-stream architecture's isolation is a
  feature, not just waste.
- **"Composite resume generalizes cleanly."** It has a correctness trap: per-channel ring buffers age
  independently, so a composite token can be *partially* in-window (stream A resumable, stream B aged out).
  The endpoint must emit a **mixed reset+replay** response and the FE demux must accept it per-sub-stream —
  strictly more intricate than "one token, one resume / one reset."
- **"Heartbeat semantics carry over."** Collapsing to one physical heartbeat is fine for 649's contact-clock
  (any frame = positive contact) but erases any *per-domain* liveness a consumer derived from its own stream's
  heartbeat. Verify no consumer depends on per-stream heartbeat freshness before collapsing.
- **"Consumers don't depend on per-stream ordering / isolation."** Multiplexing imposes a *global* wire order
  across streams that didn't exist before — and removes per-stream independent ordering. Unlikely to matter,
  but a multiplexed wire can *accidentally* breed a cross-stream ordering dependency that's invisible until it
  breaks.

### E. Broader principle / recurring system shape

662 is not a one-off; it rhymes with shapes the codebase keeps rediscovering, and pushes two of them further:

- **The "register + gate" *meta-pattern* is the recurring shape — connections are just the next column.**
  The codebase repeatedly answers "a scarce/driftable resource consumed by independently-authored code" with:
  declare every claimant in a `*.v1.json` register + a gate that fails the build on an undeclared claimant or
  a ceiling breach. execution-surfaces (who describes the trace), operation-surfaces, inflight-liveness (who
  owns each liveness domain), and now **live-channels (who holds an origin connection)**. 662 should be framed
  as *conforming to that table*, not inventing a one-off budget mechanism. Candidate invariant (649 already
  named it; 662 is the first instance acted on): *any finite resource consumed by independently-authored
  components gets one owner + a register that makes every claimant explicit and a gate that enforces the
  ceiling — or it drifts to exhaustion.*
- **NEW wrinkle this instance exposes: a static register is necessary but *structurally insufficient* for a
  resource whose consumption is dynamic.** execution-surfaces gates a *static* fact ("who may reference the
  type"). The connection budget has a *runtime peak*: lazy streams + transient fetches + an in-flight
  generation create concurrency no static count captures (Investigation §C/§F). So this is the first governed
  resource that needs **both** a static register (declared always-on claimants) **and** a runtime mechanism (a
  broker that enforces the peak, *or* at minimum runtime telemetry asserting the peak stayed under budget).
  That gap generalizes to every future *runtime* quota (memory, workers, file handles, background timers): the
  register catches *drift*, but only a runtime signal catches *peak*. Worth naming as a distinct sub-principle:
  **"declare the claimants statically; measure the peak at runtime — a count of *declarations* is not a count
  of *concurrent holders*."**
- **662 is the *resource dual* of 649's *epistemic* split — and both reduce to "earn the expensive default."**
  649's family split conflated *signals* (reachability vs freshness; stability vs health). 662 splits a
  conflated *demand*: an always-on stream fuses "I need low-latency updates *while open*" with "I need a badge
  fresh *while closed*" — two demands wearing one mechanism (framing #3). The 649 "one X was actually two"
  shape recurs, on the *resource* axis instead of the *epistemic* axis. And the deepest unification: 649 makes
  the **alarm** default calm (*earn* the alarm with positive evidence); 662 makes the **connection** default
  transient (*earn* always-on with a justified idle-latency need). Same invariant, two domains —
  **"earn the expensive default":** the costly/alarming/permanent state must be *earned by a positive
  justification*, never *assumed*. A held socket is expensive like a raised alarm is expensive; both default to
  the cheap state until something earns the costly one. This is the through-line tying 649 and 662 into one
  principle, and a reusable lens for the next "always-on / always-alarm / always-allocated" decision.
- **Deliberate non-action (scope discipline, per `structural-defects-no-repeat` / AHA).** Recognizing the
  meta-pattern does **not** license building a generalized "resource-budget framework" or a runtime broker
  abstraction now. The present problem needs *one* resource (origin connections) brought under the existing
  register pattern, plus possibly a *narrow* runtime peak-telemetry for that one resource. The general broker
  waits until a second runtime quota actually needs it. Recording the principle + its candidate scope is the
  deliverable; building the abstraction is not.

### F. No-regret moves + sequencing (observations, not a decision)

Independent of which structural family wins, four moves have high option value and foreclose nothing — and
the discipline (`interrogate-results`) says *measure before building the multiplexer*:

1. **Audit each always-on stream's true idle-latency need (§D).** Pure investigation; decides (A) vs (B);
   highest information per unit effort. Do this *first*.
2. **Pool-dedup the action-ledger duplicate** (Investigation §E). Free, any direction.
3. **Batch the two polls** (status + inference) into one response/connection. Frees a slot; same "widen the
   poll" move as framing #4; tiny.
4. **Add runtime telemetry of the concurrent-origin-connection peak.** Makes the budget *measured* not
   *assumed*, grounds the register's number in data, makes a regression observable, and is the runtime half
   the static register structurally lacks (§E).

**Sequencing thesis:** do (1)–(4), *measure the new baseline*, **then** decide whether the structural
multiplexer (A) is still needed at all — (B)+the cheap moves may already drop the baseline under budget,
making (A)'s composite-resume complexity unnecessary. Building the multiplexer *before* that measurement would
be exactly the "structure for a case the present problem may not include" that 649 §D5 warned against. The
structural choice stays open; this is sequencing, not a settled design.

## Design (2026-07-01) — settled long-term direction

This **settles** the direction the Theorization opened. It is general/architectural; exact stream-class
assignments, the budget number, the register schema, and gate wiring are build-pass detail. The guiding
constraint (match scope to the problem): the tempdoc's problem is **the always-on origin-connection footprint
exceeds the browser's fixed budget**, so the polls starve and data lags; and — because the title word is
*managed* — **the exhaustion class must not silently recur** when a future feature adds "just one more
stream." 649 already made the lag *truthful*; this design makes it *rare* and makes recurrence *impossible to
ship unnoticed*.

The design has **two durable halves** — a *reduction mechanism* and a *governance invariant* — and the first
is a **classification**, not a multiplexer. The multiplexer survives only as a small, conditional sub-part.

### D1. The core move — classify each long-lived channel by its real *demand*, not collapse them

The 5 always-on streams are not "5 of the same thing to merge." They **conflate three different demands** under
one mechanism (a permanent EventSource). The design splits that conflated demand into three classes — the
**595/649 "one X was actually several" shape, applied to the resource axis** — and routes each class to the
placement that already fits it:

- **Breadth — steady-state that must stay current even while its surface is hidden** (badges, counts, "is
  there pending work"). This belongs in the **poll**, which is *already the system's steady-state
  multiplexer*: `/api/status` aggregates readiness + composites + tri-state onto one connection
  (`utils/statusPoll.ts`). **Fold these facts into the poll** and the stream stops being always-on. Likely
  members: the indexing-jobs *progress badge*, the action-ledger *activity-digest count*. (Reuse, not new
  structure — the poll already exists and already multiplexes.)
- **Depth — sub-second live updates that only matter while a surface is *open***. These become **lazy**: open
  on surface mount, close on unmount; the **already-built resume-token replay** (`?since=`, `EnvelopeStream` +
  `SseEnvelopeWriter.attemptResume`) covers events missed while closed. Cost = *visible surfaces only*, which
  the 6-slot budget holds comfortably. Already-lazy members (health-events, log, the per-resource ResourceView
  stream) are the existing proof this class works; more streams join it.
- **Irreducible always-on push — events that must be live *before* any surface shows them** (notifications;
  the agent's intent input). This is the **only** class that legitimately holds a socket at idle — it has
  *earned* always-on. Likely members: `intent` (event-only, `attachEventOnly`, the agent's input channel) and
  the two `advisory` notification streams. **Only this set is a candidate for a multiplexer**, and only if it
  is larger than one.

So the multiplexer is **not** "merge all 5." It is scoped to the *irreducible always-on event remainder* after
breadth→poll and depth→lazy. Two payoffs from that scoping:

1. **Minimal surface.** If the audit (D5) finds the remainder is 1, **no multiplexer is built at all** —
   baseline becomes poll(1) + that-one-stream(1) = 2 long-lived sockets, far under budget. If it is 2–3, the
   cross-channel multiplexer spans only those.
2. **The hard part softens.** The remainder is **event-only** (`attachEventOnly` — no snapshot state). So the
   composite-resume trap (Investigation §D — per-stream snapshot materialization, partially-in-window tokens)
   largely dissolves: event resume is "replay since cursor, else `reset` + clear the dedup LRU," which the FE
   intent consumer *already does*. The multiplexer's resume becomes a per-`streamId` cursor map over
   event replay, not N interleaved stateful snapshots.

### D2. The governance half — a transport-agnostic budget register + gate, with a runtime-peak signal

This is the durable structure the word *managed* names, and the part that prevents silent recurrence. It
**conforms to the existing register+gate seam** (`execution-surfaces` / `operation-surfaces` /
`inflight-liveness-projections` — a `*.v1.json` register + a kernel/CI gate). A **`live-channels` register**:

- Declares **every** long-lived origin channel with its **class** (breadth/depth/event) and **transport**
  (SSE EventSource **or** long-lived fetch-body stream). Transport-agnostic is non-negotiable (Investigation
  §C): a register that counts only EventSources would pass the build while a live chat/agent generation
  (a long-lived POST body-stream) plus load still saturates the pool — certifying a budget it does not hold.
- A **gate** fails the build on (a) an **undeclared** stream-opener in `modules/ui-web/src`, or (b) the
  declared **always-on** footprint exceeding the budget (≈ `6 − 2 polls − headroom-for-one-live-generation`
  ≈ 2–3). Same **honest limit** as `execution-surfaces` (import/grep-visible; a re-modeled opener slips —
  553 §5), accepted for the same reason.
- **The new wrinkle this resource forces: pair the static register with a runtime-peak signal.** A static
  declaration count catches *drift* but not *concurrency peak* — lazy opens + transient fetches + a live
  generation stack dynamically (Investigation §C/§F). So add **narrow runtime telemetry of the
  concurrent-origin-connection peak**, scoped to this one resource: it grounds the budget number in
  measurement, makes a peak regression observable, and supplies the half a static register structurally lacks.

### D3. What is deliberately NOT in this design (scope match)

- **NOT a generalized resource-budget framework or a runtime connection-broker/scheduler.** The problem needs
  *one* resource (origin connections) classified + governed. A dynamic broker (priority/preemption, slot
  reservation) is a heavier mechanism the present peak does not yet require once the always-on baseline is
  O(1); it waits for evidence the runtime peak still breaches budget after D1. (Recorded as the next move if
  telemetry shows it, not built now.)
- **NOT a transport swap (HTTP/2, WebTransport, Tauri-IPC).** The classification fixes the baseline without
  one. **Tauri-IPC stays a named candidate** *for the irreducible event channel only* (Investigation §E — it
  needs no TLS and dissolves the limit on desktop), to be weighed against the multiplexer at D5 *if* the
  remainder is large enough to make the multiplexer costly. It is an alternative *sink* for class-3, not a
  precondition.
- **NOT a change to 649's reachability authority.** The classification is invisible to `originContact`: every
  frame on every channel (poll, lazy stream, multiplexed event channel) is still positive contact, bumped at
  the same `EnvelopeStream` seam. Reachability stays `OR(poll, any-stream)` — never the sole-signal SPOF the
  multiplexer would otherwise create (Investigation §E).

### D4. Why this matches scope (not bigger, not smaller than the problem)

- **The classification + register/gate is *required* structure**, not speculative: the problem is literally
  "which channels may be always-on, and how is that ceiling held?" — the classification *is* the answer to the
  first clause, the register/gate *is* the answer to the second. Omitting either leaves the exhaustion class
  able to recur (the explicit in-scope failure).
- **The multiplexer is built only to the size the irreducible set forces** — possibly zero. Its size is an
  *outcome* of the D5 audit, not a target. This is the deliberate avoidance of "structure for a case the
  problem may not include" (a multiplexer over streams that didn't need to be always-on).
- **Almost everything is reuse:** the poll (breadth sink), lazy-mount + resume-replay (depth sink),
  `EnvelopeStream`/`EnvelopeStreamPool` (transport/within-URL dedup), the register+gate seam, and 649's
  contact authority. The single genuinely-new structure is the *cross-channel* event multiplexer — and only
  conditionally.

### D5. Sequencing (the design is staged; each stage is independently shippable)

1. **Audit each always-on stream's demand class** (the pivotal datum — sets the multiplexer's scope, possibly
   to zero). Pure investigation; forecloses nothing.
2. **No-regret reductions:** pool-dedup the un-pooled action-ledger duplicate; batch status+inference into one
   poll. Frees slots immediately, any direction.
3. **Build the governance half** (register + gate + runtime-peak telemetry) — independent of which streams
   remain; it is the durable invariant and should land early so subsequent reclassification is *measured*.
4. **Reclassify:** breadth→poll (data-shape work), depth→lazy.
5. **Re-measure the baseline.** Only if the irreducible always-on event remainder is >1, build the
   cross-channel multiplexer scoped to it (or choose Tauri-IPC for it on desktop).

Verification (superseding the original "Verification" list's multiplex assumption): the measured 649
starvation repro shows the polls resolve promptly at the new baseline; the gate fails on an undeclared/over-
budget channel; runtime peak telemetry stays under budget under load *including a live generation*; every
reclassified consumer still receives its frames + self-heals.

### D6. Research delta (2026-07-01) — a narrow, targeted pass that *changed* two design points

Most of this design rests on settled facts (the 6-per-host limit is WontFix; the classification / register /
poll-reuse are internal/timeless) so no broad survey was warranted. But the assistant's knowledge cutoff
(Jan 2026) predates the current date by ~6 months, and the two *moving* corners the design touches — emerging
transports and Tauri/WebView2 — were worth a narrow check (the same reason 649 ran one yesterday). Three
findings, two of which **change the design**:

1. **Transport landscape unchanged — "scoped OUT" still holds.** The 6-per-host HTTP/1.1 cap is still WontFix
   and still applies to EventSource; nothing surfaced a no-TLS path for loopback HTTP/2 or WebTransport. No
   change to §D3's exclusion.
2. **CHANGE — Tauri-IPC is *not* a viable sink for a long-lived event channel on the shipped target.** Prior
   notes (Investigation §E, §D3) kept Tauri-`ipc://` as a candidate sink for the irreducible event set on the
   reasoning that it bypasses the browser pool. Research contradicts that *for streaming on Windows*:
   **WebView2 does not support streaming custom-protocol responses** (Tauri discussion #5690 → MS WebView2
   feedback #3519); Tauri's `register_asynchronous_uri_scheme_protocol` is request/response only (one complete
   response per request — no incremental/long-lived push), and chunked/streams patterns are "fundamentally
   incompatible with WebView2's architecture." Since the shipped app is Windows/WebView2, an `ipc://`
   SSE-shaped event channel is **infeasible** there. (Honest scope: a Tauri `ipc::Channel` *postMessage* push
   bridge is a *different* mechanism that may still push events JS-ward off the HTTP pool — but it is a
   bespoke, non-SSE, shell-coupled bridge that diverges in browser/dev, a larger fork than the multiplexer,
   not a drop-in.) **Net: demote Tauri-IPC as the event-channel sink; the irreducible always-on events stay on
   a (multiplexed) SSE channel.** This *simplifies* the D5 decision — the multiplexer-vs-IPC fork for class-3
   collapses toward the multiplexer.
3. **CHANGE (simplifies the hard part) — conform the multiplexer's resume to the SSE/MCP standard, not a
   hand-rolled composite token.** Investigation §D framed multiplexed reconnect as needing a *composite*
   resume cursor (a per-`streamId` seq map) — flagged as the load-bearing complication. The **MCP Streamable
   HTTP transport** (the spec the project's own production MCP endpoint at `POST /mcp` already implements, and
   which adjacent tempdoc **655** is actively certifying) defines the standard answer, and it is **simpler**:
   a server **MAY** attach an SSE `id` to events that is *"globally unique across all streams within that
   session"* and acts as a *"cursor within that particular stream"*; the client resumes by issuing a GET with
   the **`Last-Event-ID`** header, and the server replays *"on the stream that was disconnected … MUST NOT
   replay messages that would have been delivered on a different stream."* Mapped onto our multiplexer: the
   **one physical multiplexed connection carries one monotonic session-scoped event-id sequence** and resumes
   from **one** `Last-Event-ID` cursor — the per-logical-stream demux lives **purely in the frame payload**
   (the `streamId` discriminator), **decoupled from resume**. So there is **no composite per-`streamId`
   token** and no "partially-in-window" multi-cursor trap (Investigation §D's hardest wrinkle dissolves); the
   backend change is a multiplexed channel with **one combined ordered history** keyed by the session-scoped
   id (vs today's N per-channel rings). Two conform-don't-reinvent consequences for the build pass: **(a)**
   prefer the SSE-standard `Last-Event-ID` + session-scoped event-id over the codebase's current bespoke
   `?since=<opaque (streamId,seq)>` query-param resume (the `seq`-per-channel model is already *shape*-
   isomorphic to MCP's per-stream cursor — it is the *transport binding* that diverges); **(b)** align the
   shell's multiplexed transport with the **same** Streamable-HTTP resumability model the MCP endpoint uses
   rather than forking a parallel one — a coherence point with 655's certification work. (Exact current MCP
   transport version in this checkout to confirm at build time; the *shape* alignment holds regardless.)

Net: the research **validated** the classification core and the governance half unchanged, **removed** a
candidate (Tauri-IPC streaming on the shipped target), and **simplified** the multiplexer's resume by pointing
it at an existing standard the project already runs. This is the conform-don't-reinvent discipline paying off
at the transport layer.

Sources: [Chromium issue 275955 (6-EventSource cap, WontFix)](https://issues.chromium.org/issues/40329530) ·
[MCP Streamable HTTP transport — resumability & Last-Event-ID](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports) ·
[Tauri IPC streaming discussion #5690](https://github.com/tauri-apps/tauri/discussions/5690) ·
[WebView2 streaming custom-protocol limitation #3519](https://github.com/MicrosoftEdge/WebView2Feedback/issues/3519)

## Confidence pass (2026-07-01) — verifying the design's assumptions against source before implementation

A pre-implementation pass to retire the surprises the settled design rests on. Read-only; `file:line`
against the main checkout. **Outcome: the pivotal uncertainty resolved cleanly, and in a direction that
*simplifies* the design — the scary cross-process "poll-fold" is essentially avoided.**

### U1 — Per-stream demand classification (RESOLVED — the audit the whole design hinged on)

Traced every always-on stream's consumer to source:

| Stream | Class | Evidence |
|---|---|---|
| `/api/indexing-jobs/stream` | **DEPTH → lazy** | Feeds the Tasks-tray per-job rows; its always-visible *count* already reads the `/api/status` poll, **not** the stream — the bridge's own comment says so (`indexingJobsBridge.ts:168-171`) and `visibleIndexQueueCount` reads `index.pendingJobs` from the poll (`SystemSelfView.ts:38-42`, `HealthSurface.ts:855`). No idle substrate consumer. |
| `/api/advisory/operation-completed/stream` | **EVENT → always-on** | Toasts must fire live + feeds an always-mounted rail badge `unreadCount` (`AdvisoryRailBadge.ts:99-101`). |
| `/api/advisory/health-recoverable/stream` | **EVENT → always-on** | Same `AdvisoryStore`; same toast + always-mounted badge. |
| `/api/action-ledger/stream` | **EVENT/BREADTH → always-on** | Feeds the **always-mounted** `AiActivityDigest` (`Shell.ts:2135`) "since you last looked: N searches…" summary — a genuine idle consumer (U3 confirmed). |
| `/api/intent/stream` | **EVENT → always-on** | Backend/agent intent envelopes → `IntentRouter.dispatch` (`bootIntentStreamBridge.ts:93-108`); a router, not a badge — must dispatch live. |

**This resolves the design's central open question** ("is a multiplexer needed, and how big?"): the irreducible
always-on push set is **four** streams (advisory ×2, intent, action-ledger) — so **yes, the multiplexer is
warranted** (not the "maybe (B) dominates and we need none" branch). It is scoped to those four; **indexing-jobs
goes lazy**. Baseline **5 always-on SSE → 1 multiplexed channel + 1 poll = 2 long-lived sockets**.

### U2 — "Breadth → fold into the poll" (RESOLVED — the cross-process risk is *avoided*, not mitigated)

The feared unknown (job/advisory state living only at the Worker, forcing cross-process plumbing) does not
bite:
- **Indexing-jobs badge is already on the poll.** `StatusResponse` carries `worker.core` (`CoreIndexView`)
  with `pendingJobs`, `indexState`, `recentJobQueueDepth` (`CoreIndexView.java:24-36`) — already aggregated
  Head-ward via gRPC. So lazy-ifying the stream needs **zero** fold work; the chrome count keeps reading the
  poll. (Precedent that counts ride the poll: `agentSessions` active-count, `StatusResponse.java:85`.)
- **Action-ledger does NOT need folding either** — and folding it is the *hard* case (its digest is a
  per-kind summary **relative to a client-side seen-cursor**, `AiActivityDigest.ts:133-136`, which a server
  poll can't reproduce). **Resolution: don't fold it — the multiplexer absorbs it.** Action-ledger is
  event-shaped and stays always-on, so it rides the multiplexed channel; the FE demux feeds the same frames to
  `AiActivityDigest`, which keeps computing its cursor-relative summary unchanged. **The U2 cross-process /
  cursor-semantics risk disappears** because no breadth stream actually needs poll-folding.

### U3 — Lazy-safety (RESOLVED)

Only `indexing-jobs` is reclassified to lazy, and it is **safe**: its only idle-relevant reader (the chrome
queue count) reads the poll, not the substrate (U1 evidence). The one genuine idle-substrate consumer
(`AiActivityDigest` ← action-ledger) is handled by keeping action-ledger always-on (on the multiplexer), not
lazy.

### U5 — Transport-agnostic gate feasibility (RESOLVED — feasible, fetch-streams ARE detectable)

- The gate pattern is directly reusable: `check-declared-surfaces.mjs` is the exemplar (read
  `governance/*.v1.json`, walk non-test `.ts`, strip comments, regex for the opener patterns), with the
  documented import-visible honest ceiling ("a re-model slips — early-warning register, the accepted norm for
  every presentation gate") — the same ceiling `execution-surfaces` accepts.
- **The false-assurance worry (fetch-body streams invisible) is mitigated:** long-lived fetch streams funnel
  through identifiable helpers — `consumeShapeStream` (`api/streams.ts`), `pumpHostAiStream`,
  `HealthSurface.doFetch` — plus the greppable `getReader()` / `ReadableStream` patterns. So the gate can scan
  a known opener set (`new EnvelopeStream`, `subscribePooled`, `consumeShapeStream`, `pumpHostAiStream`,
  `*.body.getReader()`) and is genuinely transport-agnostic, modulo the same import-visible ceiling.

### U7 — MCP-resume "reuse what we run" (RESOLVED — CORRECTED: it's conformance, not reuse)

The project's MCP server (`McpProtocolHandler.java`) declares itself "MCP Streamable HTTP transport" (protocol
`2025-11-25`) **but implements only the POST JSON-RPC half** — `handlePost` + `Mcp-Session-Id`, and **no SSE /
GET / `Last-Event-ID` / `keepAlive`** anywhere in it. So there is **no existing MCP SSE-resume implementation
to reuse.** Correction to D6: the reusable resume asset is the **`SseEnvelopeWriter` + `ResumeTokenCodec`
`?since=` model** (which exists, works, is tested), not MCP. "Conform to the SSE/`Last-Event-ID` standard"
stays a valid *aspiration* + a **655** coherence point (655 may be where MCP gains streaming), but the build
pass should **extend the existing `?since=` envelope resume** to the multiplexed channel — likely as one
session-scoped cursor over a combined history — rather than expect to lift MCP code.

### U4 — Runtime peak signal (FEASIBLE)

No browser API reports held sockets, but the FE *knows its own* long-lived channels: a module-global
high-water-mark counter bumped at the stream-construction seam (the same universal seam `originContact` uses —
increment on open, decrement on close) yields a peak of *intended* long-lived connections — which is exactly
the budget the register governs (transient JS-chunk/icon fetches are out of the register's scope anyway).
Approximate vs. OS sockets, but sufficient and cheap. No experiment needed.

### Residual risks carried into implementation (not retired here)

- **The cross-channel multiplexer is genuinely-new code with no in-repo exemplar** (the pool only dedups
  *within* a URL). The backend (aggregate 4 channels onto one writer + combined ordered history + one
  session-scoped resume cursor) and the FE demux (per-`streamId` fan-out reusing `EnvelopeStream`'s
  transport/liveness) are the real build risk — softened (event-only, single cursor) but not eliminated.
- **Migration blast radius:** 4 always-on consumers (advisory store, intent bridge, action-ledger client) +
  the always-mounted `AiActivityDigest` must keep working through the demux, on shared `main`.
- **U6 (live-load starvation repro)** remains the implementer's verification tier — deferred by design, not
  retired.

### Confidence rating: **7 / 10**

Up from ~5 pre-pass. The pivotal "is a multiplexer needed / how big" question is answered (yes; 4 push streams;
indexing-jobs lazy), the frightening cross-process poll-fold is **avoided** (not just mitigated), the gate and
runtime-signal are confirmed feasible with precedent, and the resume-reuse asset is correctly identified
(`SseEnvelopeWriter`, not MCP). Held below 8 because the cross-channel multiplexer + combined-history resume is
new code with no repo exemplar, the 4-consumer migration on shared `main` has real regression surface, and
live-load verification is still pending.

## Reach & principle (2026-07-01) — judging the design's scope of applicability

### This design is an instance of seams that already exist — conform, don't parallelize

It is not a new idea; it is **three existing system shapes meeting**:

1. **The register+gate meta-pattern for governed scarce resources.** `execution-surfaces` (who may describe
   the trace), `operation-surfaces`, `inflight-liveness-projections` (who owns each liveness domain) — each is
   a `*.v1.json` register + a gate failing the build on an undeclared claimant or a breached ceiling.
   `live-channels` is the **next sibling**, same shape. Note it is a *sibling*, not an extension of
   `inflight-liveness`: that register already has a `connection` domain, but it governs *liveness derivation*
   ("is the backend reachable"), a **different concern** from *connection budget* ("how many sockets"). Same
   meta-pattern, distinct register.
2. **The 595/649 "one signal/demand was actually several" split.** 595 split stability from health; 649 split
   reachability from freshness; this splits the always-on stream's *demand* into breadth/depth/event. Conform
   to that shape rather than inventing a bespoke budget mechanism.
3. **The poll as the existing steady-state multiplexer.** Reuse it as the breadth sink rather than building a
   parallel SSE fan-in for facts it could already carry.

### The principle this reveals (named) + candidate scope + existing violations

**Principle — "Earn the expensive default":** *the costly, permanent, or alarming state must be earned by a
positive justification, never assumed; the cheap/transient/calm state is the default until something earns the
expensive one.* This is the **dual of 649's "earn-the-failure"** (649 made the *alarm* default calm — earn it
with positive evidence; 662 makes the *held socket* default transient — earn always-on with a justified
idle-latency need). Both are the same invariant in two domains: a raised alarm and a held resource are both
*expensive defaults that must be earned*.

The recurring **violation shape** is concrete and greppable: an **always-on claim on a finite platform
resource with no recorded justification** — a permanent EventSource / timer / observer / worker / file handle
created at boot "to be safe," where the demand it serves is actually intermittent.

Candidate scope + where code already violates it (named, **not** fixed here):

- **Always-on origin connections** — the 5 always-on streams. The bug this tempdoc fixes. *(In scope — the
  design above.)*
- **Always-on poll timers** — `BrainSurface`'s four self-owned poll timers (`pollInstall`/`pollPack`/
  `pollRuntime`/`pollDiagnostics`, `views/BrainSurface.ts:270-273`, per 663) run regardless of whether the
  surface is visible — an always-on claim on the timer/connection budget for a surface that is usually closed.
  Same violation class; **663's domain**, not fixed here.
- **Any future "open it at boot to be safe" stream/timer/worker** — the default should be lazy/demand-driven,
  with always-on *earned* per channel and *declared* in the budget register.

**Second sub-principle (narrower, also revealed): "declare claimants statically; measure the peak at
runtime."** A static register catches *drift* (a new declared claimant) but is **structurally blind to
runtime concurrency peak** for a resource consumed dynamically (lazy + transient + in-flight). Candidate
scope: any *runtime* quota — memory, worker threads, file handles, background timers. Existing state: **no
codebase resource currently pairs a register with peak telemetry**; `live-channels` + the connection-peak
signal would be the first instance. The principle is worth recording because the same blind spot will recur
the next time a register is reached for to govern a dynamically-consumed resource.

### Deliberate non-action — recognize the principles, do not build the generalized structure

Per scope discipline (`structural-defects-no-repeat` / AHA): the present problem requires bringing **one**
resource (origin connections) under the register pattern with a **narrow** peak signal for that one resource.
It does **not** require a generalized "expensive-default governor," a generic runtime-quota framework, or a
dynamic connection-broker — even though connections, the brain's timers, and future workers are all instances
of the same principle. Recording the principle + its candidate scope (above) is the deliverable; building the
general structure waits until a second resource actually needs it. Separating *recognizing* the principle from
*building* for it is intentional — it captures the insight without premature abstraction.

## Build (2026-07-01) — implemented, verified live; one correction to the settled design

Built per the confidence-pass plan's 4 phases. **status: shipped** (pending PR/merge). Each phase compiled +
tested green before the next; verified end-to-end against a live dev stack + real browser (not just unit
tests) per the "validate the real UI" requirement.

### Correction to Design §D1/§D5 — indexing-jobs does NOT go lazy; it joins the multiplexer (all 5 do)

The settled design (§D1) proposed indexing-jobs as the one DEPTH stream to make lazy, with its breadth count
already riding the poll. The Phase-2 consumer-tracing investigation (during implementation planning) found a
**genuine idle consumer the design missed**: the per-job rows feed the **always-mounted** `<jf-task-list>`
bottom-left tray (`chrome/Shell.ts:2141`; `TaskList.ts` subscribes continuously, with no surface-mount guard).
Making indexing-jobs lazy would dark that ambient tray's live rows during exactly the load condition this
tempdoc exists to fix. **Correction: all 5 streams (intent, the two advisory classes, action-ledger,
indexing-jobs) join the multiplexer; none go lazy.** This simplifies the design (no poll-fold needed anywhere,
no cursor-relative-digest risk) at the cost of the multiplexer carrying one more channel — a cheap trade since
the multiplexer's resume is per-channel-bundle (§D6) regardless of channel count.

### What shipped

- **Backend:** `MultiplexedSseWriter` (new, `modules/ui/.../api/`) — fans in N `SseStreamChannel`s onto one
  `SseClient`, reusing per-channel `attemptResume`/`sendSnapshot`/`subscribe` unchanged (no new resume codec —
  the `?since=` bundle is the existing per-channel `streamId:seq` tokens, comma-joined and decoded with the
  existing `ResumeTokenCodec`). One shared heartbeat via a dedicated `system:shell-events-heartbeat`
  pseudo-channel (never a real data channel). `ShellEventsStreamController` assembles the 5 channel sources by
  reusing each existing controller's logic via new package-visible `channel()`/`snapshotExtras()` accessors
  added to `AdvisoryStreamController`, `ActionLedgerController`, `IndexingJobsStreamController` (extend, don't
  fork — no projection/lookup logic duplicated). Route `GET /api/shell-events/stream` registered in
  `ResourceApiModule`; the 5 individual routes stay live for non-shell consumers.
- **Frontend:** `MultiplexedStream` (new, `streaming/`) — one physical `EnvelopeStream`-style connection,
  demuxed by the envelope's existing `streamId` to each consumer's unchanged reducer. `EnvelopeStream` gained
  one small additive hook (`resumeTokenProvider`) so the multiplexer's resume bundle can override the
  single-token URL construction without touching single-channel consumers' behavior. All 5 consumers migrated
  (`bootIntentStreamBridge.ts`, `AdvisoryStore.ts`, `ActionLedgerClient.ts`'s `openActionLedgerStream`,
  `indexingJobsBridge.ts`) — each keeps a documented `fallback`-class direct-connection path for when no
  `multiplex` is configured (tests; an unrecognized future advisory class). The pre-existing duplicate
  action-ledger socket (`AiActivityDigest` + `ActionLedgerView` each independently opening
  `/api/action-ledger/stream`) collapsed for free — both now subscribe the same streamId on the same shared
  multiplexer.
- **Governance:** `governance/live-channels.v1.json` + `scripts/ci/check-live-channels.mjs` (modeled on
  `check-declared-surfaces.mjs`) — transport-agnostic (SSE **and** fetch-stream openers), positive-coverage +
  budget-ceiling gate (`maxAlwaysOnPhysical: 3`). Verified both failure modes live (an undeclared opener, a
  budget breach) before declaring it correct. Wired into the CLAUDE.md pre-merge ui-web gate-set row (this
  family of FE gates is not individually `ci.yml`-wired — confirmed by checking the siblings — so that table
  is the actual wiring point, matching the established pattern). **(2026-07-01 addendum)** the register also
  carries an optional per-channel `demandClass` (breadth/depth/event, Design §D2's literal ask) alongside the
  existing mechanism `class` — documentation-only, enum-validated by the gate, not placement-deriving.
- **Runtime peak signal:** `state/liveChannelBudget.ts` — a module-global high-water-mark, bumped at the
  `EnvelopeStream` open/close instants (the universal SSE seam, covering every consumer including fallbacks)
  and at `consumeShapeStream`'s body-reader open/close (the shared chat/agent-generation entry point). Honest
  scope, documented in the module: a few fetch-stream sites that read a response body directly without going
  through `consumeShapeStream` (`HealthSurface.ts`, `InspectorPane.ts`, `conversationListStore.ts`,
  `plugin-api/capabilities/ai.ts`) are declared in the register but not instrumented here — the peak is a
  lower bound, sufficient to ground the budget number and catch a gross regression, not exhaustive.

### Verification performed

- Unit/integration: `MultiplexedSseWriterTest` (backend fan-in/resume/heartbeat), `MultiplexedStream.test.ts`
  (FE demux/resume-bundle/connection-state fan-out), updated tests for all 5 migrated consumers (including new
  multiplexed-path cases), `liveChannelBudget.test.ts`. Full suites green: `./gradlew.bat build -x test` +
  `:modules:ui:test` + `:modules:app-observability:test`; `npm run typecheck` + `npm run test:unit:run`
  (3436/3437 — the one failure is pre-existing on unmodified `main`, unrelated, logged to the observations
  inbox).
- Gate: `check-live-channels.mjs` passes on the real codebase (23 declared openers, 1 always-on against budget
  3); both negative cases (an undeclared opener, a budget breach) confirmed to fail the gate, then reverted.
- **Live dev-stack + real-browser verification** (not just unit tests, per the "validate the real UI"
  requirement): started an isolated backend (`jseval dev`) + Vite, opened the live shell in Chrome. **Network
  tab confirms exactly ONE `GET /api/shell-events/stream` (200) across multiple navigations (Chat, Search,
  System/Activity surfaces) — zero requests to any of the 5 individual stream URLs.** A direct `curl` against
  the endpoint shows correctly interleaved multiplexed frames (`system:intent-envelopes`,
  `surface:advisory-operation-completed` connected+snapshot, `surface:advisory-health-recoverable`) on one
  connection. Zero console errors. The Activity surface's live ledger view renders with a "connected" stream
  badge. (Encountered and resolved mid-verification: a port collision with a concurrent agent session's own
  backend in a different worktree — see the observations inbox; resolved by using a distinct port, with no
  disruption to the other session, confirmed by checking its process survived untouched.)

### Follow-ups logged to the observations inbox (out of scope for this build, not fixed)

- `ResourceApiModule.shutdown()` never calls `intentStreamController::shutdown()` (pre-existing gap, found
  while wiring `ShellEventsStreamController`'s own shutdown).
- Post-662, an open `core.indexing-jobs` Resource view (`ResourceView.ts`'s generic `subscribePooled`) no
  longer shares a socket with the bridge (which moved to the multiplexer) — a minor, well-under-budget
  tradeoff; a future pass could teach the generic mechanism to also check the shell-events multiplexer.
- A pre-existing, unrelated test failure (`HealthLitView.test.ts`'s 604-Move-B connection-badge-tone test)
  reproduces identically on unmodified `main` — not caused by this build.

## Post-implementation critical-analysis pass (2026-07-01) — two confirmed bugs, both fixed

A dedicated post-implementation review (two independent Explore agents + direct `javap` bytecode
verification of one candidate concern) against the shipped Build above. Two real, substantive bugs found and
fixed; one plausible-sounding concern investigated and ruled out. Recorded here per the dated-history
convention — the Build section above still describes what originally shipped; this section is the correction.

### Bug #1 (HIGH, FIXED) — a late-registering consumer could permanently miss its initial snapshot

**Root cause.** `Shell.connectedCallback()` constructs the ONE shared `MultiplexedStream` and calls `.start()`
near the top of the method — the physical `EventSource` opens immediately. The backend
(`ShellEventsStreamController.handle()`) sends its one-time `connected`+`snapshot` burst for all 5 channels
at the moment THAT connection opens, independent of which streamIds the frontend has registered listeners
for yet. Two of the five migrated consumers register their `multiplex.subscribe(...)` call only AFTER an
independent, unsynchronized async network fetch resolves:
- **AdvisoryStore** — only on the *second* `reconcileFromCatalog()` call, triggered by `onCatalogChange`
  after `bootResourceRegistry()`'s fetch to `/api/registry/resources` resolves (kicked off from
  `modules/ui-web/src/i18n.ts:100`, entirely unordered relative to `Shell.connectedCallback()`).
- **Intent (BackendStreamSource)** — only inside `fetchAndRegisterSurfaceSchemas(this.apiBase).then(...)`
  (`Shell.ts`), gated behind a second, separate fetch.

`MultiplexedStream.routeFrame()` silently drops a frame for any streamId with no registered entry (dev-mode
`console.warn` only). Since heartbeats keep a healthy connection alive indefinitely, there was **no natural
retry** — a late-registering consumer (in practice: the advisory rail badge/inbox, or the remote-intent
router) could sit in its empty initial state for the entire session, until a live UPDATE happened to arrive
naturally on that channel. This was a genuine regression versus the pre-662 architecture, where each
consumer opened its **own** connection lazily, exactly when ready — consolidating onto one eagerly-opened
shared connection broke that guarantee for any consumer whose readiness is gated behind I/O. The race is
also *timing-dependent* and more likely to manifest under backend load — the exact condition this tempdoc
exists to improve, making it more than a corner case.

**Fix (frontend-only, no protocol/backend change).** `MultiplexedStream.subscribe()` now detects a genuinely
NEW entry registered while the connection has already completed its first connect (`lastConnected ===
true`) and schedules a **debounced** reconnect (`reconnectDebounceMs`, default 50ms) of the shared transport.
This reuses the existing resume protocol unchanged: the late entry has no resume token yet, so it is
naturally excluded from the `?since=` bundle, and the server already treats a bundle-absent channel as
"first-time subscriber, send a fresh snapshot" (`MultiplexedSseWriter.attachAll`); already-flowing entries
resume normally via their existing tokens. Debounced (not immediate) because a single synchronous
catalog-reconcile loop registers several advisory channels back-to-back — without coalescing, each would
trigger its own reconnect. A subscribe that happens *before* the connection has ever opened (the common/safe
case — `startIndexingJobsBridge`, `AiActivityDigest`) is unaffected: `lastConnected` is still `false`, no
reconnect is scheduled, and the first burst is received normally once the connection opens.

Tests added to `MultiplexedStream.test.ts`: subscribe-before-connect needs no reconnect; subscribe-after-connect
triggers exactly one debounced reconnect and the late entry receives a fresh snapshot; several late subscribes
within the debounce window coalesce into one reconnect; an already-flowing entry's data survives the reconnect
via its resume token (no loss/duplication); `stop()` cancels a pending debounced reconnect.

### Bug #2 (MEDIUM, FIXED) — a mid-loop backend failure could leak earlier channel subscriptions

`MultiplexedSseWriter.attachAll`'s per-channel loop added each subscription to a list incrementally, but
`client.onClose(...)` (the cleanup registration) was only wired up **after** the loop completed. If any
channel's processing threw partway through — e.g. `source.snapshotExtras().get()` for action-ledger's
`currentEvents()` or indexing-jobs's gRPC-backed `bridge.latestSnapshotPair()` — the exception propagated
before `onClose` was ever registered, so the **earlier**, already-subscribed channels in that connection
attempt were never explicitly unsubscribed. They would self-heal only once `SseStreamChannel.publish()`'s
`listeners.removeIf` evicted them on the next broadcast against the by-then-dead client — a transient
listener leak + wasted writes, not permanent, but a real gap specific to the multiplexed design (the
single-channel `SseEnvelopeWriter.attach()` has no analogous multi-subscription state to leak).

**Fix.** The per-channel loop body is now wrapped in `try { ... } catch (RuntimeException e) { unsubscribe
everything accumulated so far; throw e; }`, so a failure at any point leaves zero orphaned subscriptions.
Test added to `MultiplexedSseWriterTest.java`: a channel whose `snapshotExtras` supplier throws after an
earlier channel already succeeded — asserts the original exception propagates, `onClose` is never reached
(confirming the gap the fix closes), and a subsequent publish to the earlier channel does not reach the
(already-failed) client.

### Investigated and ruled out — Javalin `SseClient` concurrent multi-channel write safety

The review raised a plausible new risk: multiplexing means up to 5 channel-broadcast threads + 1 heartbeat
thread can now call `sendEvent` concurrently on the *same* `SseClient`, where pre-662 only one channel ever
wrote to a given client. Verified directly via `javap` bytecode inspection of `javalin-6.7.0.jar` (no sources
jar was cached, so bytecode was the ground truth, not assumption): `SseClient.sendEvent` delegates to a
single `Emitter` instance per client, and `Emitter.emit(...)` is **internally `synchronized`**
(`monitorenter`/`monitorexit` wrapping the entire write body). Concurrent multi-channel writes are therefore
safe — not a bug, no fix needed.

### Scope re-verified clean (no fix needed)

The same review independently re-confirmed, with citations: `MultiplexedStream.subscribe()`'s ref-counting
correctly handles duplicate-listener double-subscribe/unsubscribe (independent `released` closures per call,
not a shared flag); `stop()`/`start()` reuse self-heals via the existing resume-or-reset contract;
`AdvisoryStore`'s catalog-removal path correctly skips `.stream?.stop()` for multiplexed entries and never
touches the shared instance's lifecycle; `ActionLedgerClient`/`indexingJobsBridge` multiplexed-path
teardowns return only the per-subscriber unsubscribe; and the `live-channels` register's `fallback`-class
rows still match real, currently-present code (not stale).

## Design-alignment review (2026-07-01) — does the Build actually satisfy the settled Design?

A separate pass re-read the Design (§D1–D6) and Theorization sections in full, conceptually — not
line-level code review — and compared them against what shipped. Verdict: the *governance* half (register +
gate + runtime-peak signal) matches the design closely. Three gaps against the *classification* thesis and
the verification bar. This section resolves the first and records the schema/measurement follow-ups (below).

### Gap #1 (RESOLVED — investigated, current Build decision confirmed correct) — indexing-jobs on the multiplexer vs poll-fold

**The question.** §D1's central move is *classify demand, route each class to its cheapest placement,
multiplex only the irreducible remainder*. The pre-implementation audit (Confidence pass, U1) applied that
and concluded indexing-jobs is DEPTH → lazy. The Build then reclassified it back onto the multiplexer with
one paragraph of reasoning ("the always-mounted tray needs live rows") — never weighing the design's own
preferred cheaper tier for a steady-state/always-visible need: fold it into the poll, the same way
indexing-jobs' own progress *count* already does (U2). Does that shortcut hold up, or was it a real
regression from the design's classification discipline?

**Investigation.** Traced the actual data/cost path rather than assuming:
- `TaskList.ts` (`modules/ui-web/src/shell-v0/components/TaskList.ts`) — the always-mounted tray — renders
  per-job STATE badges (queued/running/failed), not a smooth percentage. `IndexingJobView`
  (`modules/app-api/.../indexing/IndexingJobView.java:20-27`) carries `state, attempts, lastUpdatedMs,
  errorMessage, retryAfterMs` — no per-job progress field. Job state transitions happen on the order of
  seconds-to-minutes, so a poll cadence would be visually indistinguishable from push for this UI. The
  *literal* poll-fold is not blocked by data granularity.
- The per-job list is already cheap to expose: `RemoteIndexingJobsBridge.latestSnapshot()`
  (`modules/app-services/.../worker/RemoteIndexingJobsBridge.java:152`) is a synchronously-readable cache
  kept fresh by a gRPC subscription started unconditionally at Head boot (`HeadAssembly.java:986`),
  independent of any SSE consumer. Adding it to `StatusResponse`/`CoreIndexView` would reuse that cache — no
  new Worker query, no new subscription. The literal mechanics are cheap, contrary to an initial assumption
  that this would require substantial new backend plumbing.
- **The actual blocker:** `indexingJobsBridge.ts` carries a purpose-built defect detector —
  `isFeedStalled`/`STALE_FEED_MS` (tempdoc 595 §4.4, `indexingJobsBridge.ts:157-222`) — that compares the
  SSE feed's own frame-arrival cadence against the `/api/status` poll's cadence to catch the *specific*
  documented defect where the tray silently freezes while the status bar keeps advancing. That detector is
  **meaningful only because indexing-jobs is independent of the poll**. Folding indexing-jobs onto the poll
  would make the tray and the status bar read the same source, so they could never diverge — the detector
  becomes vacuous. Removing it would mean either accepting the regression risk of retiring a mechanism
  purpose-built against a documented past defect, or building a replacement signal.
- **The marginal connection-budget cost of leaving indexing-jobs on the multiplexer is ~zero.** Unlike the
  original 5-vs-6 problem (every stream's own socket counted), the multiplexer socket already exists for
  advisory×2/action-ledger/intent regardless of whether indexing-jobs also rides it. The budget pressure
  that motivates poll-folding elsewhere does not actually apply to this specific stream once it is already
  sharing an open connection.

**Conclusion.** Keeping indexing-jobs on the multiplexer is the *correct* call, not a shortcut, once the
595 §4.4 detector and the near-zero marginal connection cost are both weighed — reversing it would trade a
working, purpose-built defect detector for a connection-budget win this specific stream doesn't actually
need (the socket is already open). This satisfies the design's own "prefer extending a usable existing
design over creating new structure" instinct: the existing multiplexer inclusion is the design to keep. The
gap the design-alignment review found was real, but it was in the *documentation* (a one-line assertion
where a weighed comparison belonged), not in the code.

**Scope note for the future.** This conclusion is specific to indexing-jobs' history (it has an existing
detector riding on its channel independence). A *future* always-mounted-but-steady-state stream that lacks
an analogous purpose-built staleness detector should default to poll-folding first, per §D1's original
preference order — this instance does not license a blanket "always keep everything on the multiplexer"
reading.

### Gap #3 (RESOLVED) — the register now carries a demand-class annotation

`governance/live-channels.v1.json` gained an optional `demandClass` field per channel (`breadth`/`depth`/
`event`, defined in a new `demandClasses` map alongside the existing mechanism `classes` map), enum-validated
by `scripts/ci/check-live-channels.mjs` when present. Documentation-only by design (the gate does not derive
placement from it — that judgment stays in tempdoc prose, per AHA scope discipline: the register's job is
anti-drift enforcement, not encoding every design rationale as machine-checked structure). The multiplexer's
channel row is annotated `"demandClass": "event"` with an explicit note that indexing-jobs is the one
BREADTH-demand stream riding along on it for the documented reason above, not because its demand is `event`
too — so a future reader of the register sees the real shape, not a flattened "everything here is the same
kind of always-on."

### Gap #2 (RESOLVED) — measured load repro

Ran the tempdoc's own named acceptance test live: isolated dev-stack backend (`jseval dev --clean --port
33273`) + Vite, real Chrome automation, direct `fetch()` timing probes (bypassing the app's own poll
scheduling to get precise per-call latency) against `/api/status` / `/api/inference/status`.

**In-scope test (one session, the tempdoc's actual claim).** One browser tab, navigated to the Health
surface (its 2 lazy fetch-body streams: `/api/health/events/stream`, `/api/condition-recovery-index/stream`)
alongside the baseline (`/api/shell-events/stream` multiplexer + the 2 native polls) — confirmed via
`read_network_requests` that all 3 long-lived connections were live (200, after an initial transient 503
warm-up unrelated to pooling). Six consecutive ad-hoc `/api/status` probes at this baseline: **43ms, 48ms,
43ms, 47ms, 43ms, 53ms** (plus one 379ms cold-start call) — every call resolved promptly, no queuing, no
growing backlog. `/api/inference/status` resolved in 325ms. This is the literal claim from the tempdoc's
Verification section and Design §D5's superseding version: **confirmed, measured, live.**

**Out-of-scope discovery (recorded honestly, not claimed as a tempdoc failure).** An earlier attempt tested
3 simultaneous browser *tabs* of the app (each tab boots its own full Shell → its own multiplexer + 2 polls;
3 tabs = 9 baseline connections competing for the same origin's 6-slot pool, plus Health/Log lazy streams on
top). Under that load, the app's own scheduled polls kept succeeding (49/49 observed 200s), but a single
ad-hoc extra `fetch()` genuinely stalled for 8+ seconds (`Promise.race` against an 8000ms timeout fired). This
is a real, reproduced contention effect — but it is **outside this tempdoc's stated scope**: the Problem/
Scope sections describe *one shell's* long-lived-connection footprint, not N independent Shell instances
(each with its own module-level state, including its own `liveChannelBudget.ts` peak counter) competing
across browser tabs. The register/gate have no visibility across tabs by construction (each tab is a
separate JS runtime). Recorded here as a genuine boundary condition for anyone extending this work to a
multi-tab/multi-window scenario — not a regression, not a missed requirement of tempdoc 662 as scoped.
