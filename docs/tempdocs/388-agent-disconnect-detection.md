---
title: "388 — Agent Disconnect Detection"
---

# 388 — Agent Disconnect Detection

**Status:** Not started
**Created:** 2026-04-09
**Origin:** Deferred from tempdoc 383 (streaming completeness contract, item D6)
**Prerequisites:** tempdoc 383 (implemented — CancellationException routing, terminal guards on latch-based sites)

## Problem

When a user closes the browser tab during an agent session, the agent loop continues running on the server — making LLM calls, executing tools, and consuming GPU/CPU resources. The `AgentController.writeAgentEvent` method ignores the return value of `SseWriter.writeEvent`, so client disconnect is never detected.

This is a resource efficiency problem, not a correctness problem. The agent's LLM calls DO have sentinel tracking (tempdoc 383 covers `streamChatWithTools`), so truncated LLM responses are detected. The gap is strictly: the outer agent loop doesn't know the client left.

## Current Architecture

```
AgentController.handleRunStream()
  → agentService.runAgent(request, event -> writeAgentEvent(ctx, event))
      → agent loop: LLM call → tool call → LLM call → ... → done
```

- `writeAgentEvent` (line ~225) calls `sseWriter.writeEvent(ctx, eventType, payload)` — return value ignored for ALL event types including `chunk`
- `agentService.runAgent()` is synchronous and blocking — it runs the full agent loop on the handler thread
- The latch-based streaming sites (summary, Q&A) use `CancellationException` thrown from `onChunk` to propagate disconnect. The agent path has no equivalent mechanism.

## Industry Research (from tempdoc 383)

| Framework | Pattern |
|-----------|---------|
| Vercel AI SDK | `AbortSignal` forwarded from request; `onAbort` callback for cleanup |
| LangGraph | `on_disconnect="cancel"` — preserves state to last checkpoint |
| AMD Gaia | Cooperative `AtomicBoolean` checked at safe points (before LLM calls, before tool executions) |

**Javalin primitives available:**
- `SseClient.terminated()` — returns true after a failed write (`AtomicBoolean` internally)
- `SseClient.onClose(Runnable)` — fires when connection terminates
- Critical limitation: disconnect only detected when a write is attempted (no passive notification on Jetty)

## Implementation Path

### Approach: Cooperative cancellation with heartbeat-assisted detection

1. **`AgentController`:** Check `writeEvent` return value in `writeAgentEvent`. If `false`, set a shared `AtomicBoolean cancelled`.

2. **Thread cancellation flag through `AgentService.runAgent()`:** Add `AtomicBoolean cancelled` parameter (or wrap in a `CancellationToken` type).

3. **Agent loop checks at two safe points:**
   - Before each LLM call (`streamChatWithTools`)
   - Before each tool execution
   - If `cancelled.get()`, break out of the loop cleanly (persist partial state if applicable)

4. **Heartbeat between steps (optional):** Send SSE comment (`": hb\n\n"`) between agent iterations to detect disconnect earlier. Without this, disconnect is only detected when the next `writeAgentEvent` call fails — which could be after a 30-60s tool execution.

### Files to modify

- `modules/ui/src/main/java/io/justsearch/ui/api/AgentController.java` — check `writeEvent` return, set cancel flag
- `modules/app-agent/src/main/java/io/justsearch/agent/AgentService.java` — accept and propagate cancel flag
- Agent internal loop (wherever iterations are driven) — check cancel between steps

## Scope

- Fix the resource waste on client disconnect
- Do NOT change the agent's functional behavior (tool calling, response generation)
- Do NOT add agent-level persistence or state recovery (that's LangGraph-style, much larger scope)

## Investigation Log

- 2026-04-09: Created from tempdoc 383 deferred item D6. Industry research completed (AMD Gaia cooperative cancellation pattern, Vercel AbortSignal, LangGraph on_disconnect). Javalin primitives identified (terminated(), onClose). Implementation path designed: AtomicBoolean cancel flag threaded through AgentService, checked at LLM call and tool execution boundaries.
