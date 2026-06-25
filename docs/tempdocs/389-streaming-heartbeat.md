---
title: "389 — Streaming Heartbeat / Stall Detection"
---

# 389 — Streaming Heartbeat / Stall Detection

**Status:** Not started
**Created:** 2026-04-09
**Origin:** Deferred from tempdoc 383 (streaming completeness contract)
**Prerequisites:** tempdoc 383 (implemented — streaming completeness contract)

## Problem

llama-server sends no data during prompt evaluation (30-60+ seconds for large contexts). The client cannot distinguish "server is processing" from "server is dead." The only detection mechanism is the 2-minute HTTP_TIMEOUT backstop, which means users wait 2 minutes before seeing an error when the server has actually crashed.

This is the third pillar of the universal streaming completeness pattern identified in tempdoc 383's research: terminal sentinel (done), finish reason (done), heartbeat (this tempdoc).

## Two-Sided Gap

```
Browser ←[SSE, no heartbeats]→ Javalin ←[SSE, no heartbeats]→ llama-server
```

**Browser-facing (Javalin → Browser):** No keepalives sent during LLM processing. Proxies or browsers with aggressive idle timeouts could drop the connection. `MapReducePipeline` already does its own SSE comment heartbeats (every 250ms) for internal accumulation steps, but direct-to-client streaming paths do not.

**llama-server-facing (Javalin ← llama-server):** Java's `HttpClient` has no body-level idle timeout (`JDK-8258397`, open since 2021). Once headers arrive, there's no timeout for the body stream. A dead llama-server produces no exception until the 2-minute HTTP_TIMEOUT fires.

## Industry Research (from tempdoc 383)

- **No industry convergence** on heartbeat injection for LLM streaming. Most deployments use large timeouts (5-10 min).
- **LiteLLM rejected** TTFT timeout feature requests (#5859, #17991) — closed as NOT_PLANNED.
- **llama-server has no heartbeat mechanism** — no existing feature, no planned feature, no GitHub issues requesting it.
- **SSE spec** (WHATWG) recommends comment lines (`: heartbeat\n\n`) every 15 seconds for keepalive through proxies.
- **Google GAX `Watchdog`** pattern — `ScheduledExecutorService` checks `lastDataReceivedTimestamp`, throws `WatchdogTimeoutException` if idle exceeds threshold. Production-proven for gRPC streams.

## Implementation Path

### Browser-facing heartbeats (lower effort)

Inject SSE comments (`": hb\n\n"`) to the browser connection during LLM processing. This lets the browser distinguish "processing" (heartbeats arriving) from "dead" (heartbeats stopped).

**Approach options:**
1. **Per-controller timer:** Each latch-based controller site starts a `ScheduledFuture` that sends `SseWriter.writeSseComment(ctx, "hb")` every 15 seconds. Cancel when the stream completes.
2. **SseWriter-level auto-heartbeat:** Add `startHeartbeat(ctx, intervalMs)` / `stopHeartbeat(ctx)` to `SseWriter`. Controllers call start before streaming and stop in the terminal callback.
3. **Javalin's built-in `enableAutomaticPings()`:** If using `SseClient` directly (currently we use raw `Context` + `SseWriter`).

**Frontend change:** Track `lastEventTime` in `streams.ts`. If no event (including SSE comments) arrives within 45 seconds (3x heartbeat interval), consider the stream stalled and fire `onError({code: 'STREAM_STALLED'})`.

Note: SSE comments (`: ...\n\n`) are currently ignored by `parseSseBuffer` in `sse.ts` (line 104: `if (line.startsWith(':')) continue`). The frontend would need a separate mechanism to detect them — either extend the parser to track "last data received" time regardless of event type, or use a ReadableStream-level timestamp.

### llama-server-facing watchdog (higher effort)

Detect a truly dead llama-server faster than the 2-minute HTTP_TIMEOUT.

**Approach:** Wrap `HttpResponse.BodyHandlers.ofLines()` with a watchdog decorator:
- Track `lastLineReceivedTimestamp` on each line from the stream
- A `ScheduledFuture` checks periodically; if idle exceeds threshold (e.g., 90 seconds), cancel the HTTP request
- The cancelled request throws `HttpTimeoutException` → propagates to `onError`
- Threshold must be generous (>60s) to accommodate legitimate prompt evaluation pauses

**Risk:** False positives during genuinely long prompt evaluations. A 10K-token prompt on CPU could take 90+ seconds. The watchdog threshold must be conservative.

## Scope

- Browser-facing heartbeats are the primary deliverable
- llama-server-facing watchdog is stretch / Phase 2
- Does NOT modify llama-server (upstream C++)
- Does NOT change the streaming protocol contract (tempdoc 383)

## Investigation Log

- 2026-04-09: Created from tempdoc 383 deferred item. Industry research completed (see tempdoc 383). Key finding: no industry convergence on heartbeat injection — most use large timeouts. MapReducePipeline already implements browser-facing heartbeats (SSE comments every 250ms) for internal accumulation — pattern exists in codebase. Java HttpClient lacks body-level idle timeout (JDK-8258397). Google GAX Watchdog is the reference implementation for server-side idle detection.
