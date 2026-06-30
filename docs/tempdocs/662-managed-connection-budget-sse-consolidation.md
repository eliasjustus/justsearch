---
title: "Managed connection budget — consolidate the SSE streams so the poll stops starving"
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

## Settled design direction (from 649, to be detailed here)

**One multiplexed SSE channel instead of N.** Collapse the independently-authored streams into a single
long-lived connection whose frames carry a discriminator, with a FE fan-out layer that routes each frame to
its consumer. This is the "managed connection budget": a finite platform quota (6 connections) gets **one
owner/multiplexer** instead of N independent claimants.

Building blocks that already exist (extend, don't fork):
- The **`SseEnvelope`** wire type already carries a `streamId` + `payload.kind` discriminator — it is
  **multiplex-ready by construction**; the missing piece is only the fan-out/fan-in layer, not the wire shape.
- **521 proposed a `MultiplexedStream`** that was never built — this is the place to build it.
- **`EnvelopeStream`** already owns reconnect + heartbeat-watchdog liveness; the multiplexer wraps/extends it
  so every logical consumer inherits one self-healing physical channel.

A **"live-channel budget register"** (governance-register pattern, like the other `*.v1.json` registers)
would make the connection budget an explicit, gated invariant: every long-lived origin channel must be
declared, and the gate fails the build if the declared physical-connection count exceeds the budget — so the
exhaustion class can't silently reappear when a future feature adds "just one more stream."

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
