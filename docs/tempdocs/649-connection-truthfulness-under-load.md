---
title: "Connection truthfulness under load — poll-based reachability defeated by SSE connection-pool exhaustion"
type: tempdoc
status: open
created: 2026-06-25
related:
  - 562-installer-build-pipeline-smoothness   # parent: split out of move 2; the alpha.28 "Reconnecting under load" finding
---

# 649. Connection truthfulness under load

> Scope of this doc: **the general idea + the derived issue only.** No solution design yet (deliberately —
> the fix likely supersedes a documented kernel decision and needs its own design pass). Split out of
> tempdoc 562's move 2 after live validation invalidated that move's premise.

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

## Status

`open` — general idea + derived issue captured; **no solution chosen**. Candidate directions exist
(SSE-liveness-based reachability, SSE consolidation, HTTP/2 multiplexing, a `/api/status` server-side
timeout) but each is a real design decision — and the SSE-liveness option overturns a kernel decision — so
they are left for a dedicated design pass.
