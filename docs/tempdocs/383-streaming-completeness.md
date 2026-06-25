---
title: "383 — Streaming Completeness Contract"
---

# 383 — Streaming Completeness Contract

**Status:** Implemented
**Created:** 2026-04-08
**Implemented:** 2026-04-09 (branch `worktree-382-error-handling`)
**Origin:** W22 from tempdoc 378, split out from tempdoc 382 (error-handling audit)

## Problem

The streaming architecture has no completeness contract between any of its three layers. Each layer assumes the layer below will either deliver a complete response or throw an error. None handle incomplete delivery with no error signal.

## Architecture

```
llama-server (SSE) → OnlineModeOps (Java HttpClient) → Controllers (SseWriter) → Frontend (fetch ReadableStream)
        Layer 1                                              Layer 2                      Layer 3
```

## Layer 1 — Backend (OnlineModeOps)

`HttpResponse.BodyHandlers.ofLines()` returns a `Stream<String>` backed by the live TCP connection. When the stream is exhausted — whether by normal completion or clean TCP close (FIN) — the `forEach` loop exits normally. There is no exception.

`data: [DONE]` is parsed at lines 245 and 412 but only as a skip filter (`!line.equals("data: [DONE]")`). It is never tracked as a required terminal sentinel. No `finish_reason` field is inspected from chunk payloads.

After the loop exits, `onComplete.run()` is called unconditionally (line 286/460). This means:

- **Clean TCP close without `[DONE]`** (llama-server OOM, graceful exit, restart) → `onComplete` called → success reported with truncated content.
- **`CancellationException`** (client disconnect detected by `SseWriter.writeEvent` returning false) → caught at line 288/462 → also routes to `onComplete.run()`, not `onError`. Client disconnects are treated as success on the backend.

The `onError` path (line 295/469) only fires for actual Java exceptions during the HTTP call — `IOException` from TCP RST, `HttpTimeoutException` from the 2-minute request timeout, or parse errors in the lambda body.

Usage data (`onUsage` callback) is emitted as a separate final SSE chunk from llama-server. If the stream is truncated before this chunk, `onUsage` is never called — silently.

## Layer 2 — Controllers (SseWriter)

Controllers (SummaryController, RagStreamingHandler, etc.) receive `onComplete`/`onError` callbacks from Layer 1 and translate them to SSE events for the browser:

- `onComplete` → `SseWriter.writeEvent(ctx, "done", ...)` → `latch.countDown()`
- `onError` → `SseWriter.writeEvent(ctx, "error", ...)` → `latch.countDown()`

Controllers trust `onComplete` blindly. When it fires, they emit `event: done` without verifying that the LLM actually finished. The `done` SSE event to the browser means "the backend says it's done," not "the LLM confirmed completion."

The Javalin handler thread blocks on `latch.await(120, TimeUnit.SECONDS)`. If neither callback fires within 2 minutes, the handler returns — no event sent to the browser.

Client disconnect detection: `onChunk` callbacks check the return value of `SseWriter.writeEvent`. If it returns `false` (write failed), they throw `CancellationException` which propagates through `Stream.forEach` back to Layer 1. The `event: done` write in `onComplete` also fails silently on disconnect (return value ignored), so the browser receives nothing — neither done nor error.

## Layer 3 — Frontend (streams.ts, useAppAI, useSummary)

The fetch loop reads from a `ReadableStream`:
```typescript
while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  // parse SSE buffer, fire onChunk/onDone/onError per event type
}
```

`onDone` is called only when a `done` SSE event is parsed from the stream. `onError` is called only when an `error` SSE event is parsed or the `reader.read()` promise rejects (network error).

If `reader.read()` returns `{ done: true }` without a `done` or `error` SSE event in the buffer, the loop exits silently. Neither `onDone` nor `onError` fires.

**useAppAI (Q&A, batch summarize):** `onDone` calls `setAiLoading(false)`. If `onDone` never fires, `aiLoading` stays `true` forever — stuck spinner. No timeout exists in this hook.

**useSummary (single-doc summarize):** Has a partial mitigation — if `onError` fires after chunks have already been received (`streamStarted === true`), it calls `finalize()` and resolves the promise. This prevents a stuck spinner but silently presents truncated content as complete. A 20-second `AbortController` timeout exists (`SUMMARY_TIMEOUT_MS`), but only for the initial connection, not for mid-stream stalls.

**UI truncation indicator:** `InspectorContext.tsx` renders a "Truncated" pill when `truncationWarning === true`. This flag is set from `meta.truncated` in a backend-emitted SSE `meta` event — a pre-generation signal, not a post-generation detection. There is no frontend-side detection of "stream ended without completion."

## Failure Mode Matrix

| Scenario | Layer 1 result | Layer 2 SSE | Layer 3 result | User sees |
|----------|---------------|-------------|----------------|-----------|
| Normal completion | `onComplete` | `event: done` | `onDone` called | Complete response |
| Hard crash (TCP RST) | `onError` | `event: error` | `onError` called | Error message |
| Clean close without `[DONE]` | `onComplete` (wrong) | `event: done` (wrong) | `onDone` (wrong) | Truncated response, no indication |
| Client disconnect mid-chunk | `onComplete` (wrong) | write fails | neither callback | Spinner stuck (useAppAI) |
| Server hang (no data, no close) | `onError` after 2 min | `event: error` | `onError` after 2 min | Error after long delay |
| Network drop (no FIN, no RST) | `onError` after 2 min | `event: error` | `onError` after 2 min | Error after long delay |
| Latch timeout (120s, no callback) | N/A | nothing sent | loop exits, no callback | Spinner stuck |

## Root Cause

No layer defines what "complete" means. Each reports "done" when data stops flowing, not when it confirms the response is actually finished. The protocol between llama-server → Java → SSE → frontend has no end-to-end completeness acknowledgment.

## Production Design Research (2026-04-09)

Research across OpenAI, Anthropic, Vercel AI SDK, and llama-server streaming protocols reveals a universal pattern with three independent mechanisms:

### The universal completeness contract

**1. Terminal sentinel — "did the stream finish?"**
Every production LLM streaming API sends an explicit terminal event (`data: [DONE]`, `event: message_stop`, `finish` part). Its absence = truncation.

**2. Finish reason — "WHY did the model stop?"**
Every API carries a `finish_reason`/`stop_reason` in the final content chunk:
- `stop` / `end_turn` → model chose to stop (complete response)
- `length` / `max_tokens` → forced stop at token limit (semantically truncated even though protocol completed)
- `tool_calls` → tool invocation needed
- `content_filter` → safety intervention (N/A for local llama-server)

**3. Heartbeat / stall detection**
Anthropic sends `event: ping` periodically. SSE spec supports `: comment\n\n` keepalives. Clients track `lastEventTime` and tear down stalled connections after threshold (2-3x heartbeat interval). **llama-server sends NO heartbeats** — long prompt evaluation pauses are indistinguishable from dead connections.

### Two independent axes of completeness

| | Sentinel received | Sentinel missing |
|---|---|---|
| **finish_reason: stop** | Complete response | Truncated (relay/network failure) |
| **finish_reason: length** | Protocol OK, content truncated at token limit | Truncated + token limit |
| **No finish_reason** | N/A | Definitely truncated |

A stream can be protocol-complete (sentinel received) but semantically incomplete (`finish_reason: "length"`). Both axes must be checked independently.

### What no production system supports
- **Mid-stream resumption**: LLM generation is stateful/non-deterministic. KV cache gone after disconnect. Truncated streams require full retry.
- **At-least-once delivery**: SSE is best-effort. The sentinel is the only reliable completeness signal.

### llama-server specifics (JustSearch's backend)
- Sends `finish_reason` in the final content chunk (bundled with `usage` and `timings`, unlike OpenAI which uses a separate empty-choices chunk)
- Sends `data: [DONE]` as terminal sentinel
- **No heartbeat/keepalive** mechanism during generation
- `finish_reason` values: `"stop"`, `"length"`, `"tool_calls"` (no `"content_filter"`)
- SSE error messages use non-standard `error:` field (not spec-compliant, GitHub issue #16104)

---

## Complete Streaming Site Inventory

### Layer 1 — OnlineModeOps (app-inference)

| Method | Lines | Pattern |
|--------|-------|---------|
| `streamChat` | 182–304 | `BodyHandlers.ofLines().forEach()` → unconditional `onComplete.run()` at L286. CancellationException also routes to onComplete (L291). |
| `streamChatWithTools` | 325–478 | Identical pattern. `onComplete.run()` at L460, cancel→onComplete at L465. |
| `streamSummary` (3 overloads) | 482–512 | Delegates to `streamChat` |
| `streamAnswer` (3 overloads) | 516–549 | Delegates to `streamChat` |

Both `streamChat` and `streamChatWithTools` filter `[DONE]` at L245/L412 but never track receipt. Neither extracts `finish_reason` from chunk payloads.

### Layer 2 — Controllers (ui module)

| File | Method | Line | Timeout | Notes |
|------|--------|------|---------|-------|
| `SummaryController` | `handleHierarchicalSummaryStream` | 487 | 180s | Synthesis step |
| `SummaryController` | `streamStandardSummary` | 539 | 120s | Fallback non-hierarchical |
| `SummaryController` | `streamSummaryToSse` | 732 | configured | Direct streaming |
| `FullCoverageSummarizer` | `streamDirectSummary` | 171 | 180s | Full-coverage direct |
| `SectionProcessingOps` | `streamReduceAndSynthesize` | 255 | 600s | Map-reduce synthesis |
| `RagStreamingHandler` | `handleAskStream` | 232 | 120s | Q&A streaming |
| `RagStreamingHandler` | `handleQuickSummaryStream` | 431 | 120s | Batch summary |
| `MapReducePipeline` | `streamChatInternal` | 433 | configurable | Internal accumulation (no SSE emit, has heartbeat keepalive) |
| `AgentController` | agent loop | — | — | No latch, no disconnect detection via writeEvent return value |

All 8 SSE-emitting sites follow the identical pattern: `CountDownLatch(1)` + `onComplete` emits `event: done` + `onError` emits `event: error` + `latch.await(timeout)` emits timeout error. None verify completeness before emitting `done`.

### Layer 3 — Frontend (ui-web)

| File | Pattern | Gap |
|------|---------|-----|
| `streams.ts` | `while(true) { read(); if(done) break; parseSseBufferJson(...) }` | `done:true` without terminal SSE event → silent exit, no callback |
| `useAppAI.ts` | `onDone` clears `aiLoading`; `onError` clears `aiLoading` | No client-side timeout. Stuck spinner if neither fires. |
| `useSummary.ts` | 20s AbortController timeout; `streamStarted` guard; snapshot fallback | Only covers single-doc. Q&A/batch has no equivalent. |

### Existing infrastructure to leverage

- `StreamCallbacks` record in `OnlineAiService` (L49–68) — already groups 6 callbacks. Natural place to add `StreamResult`.
- `ToolCallParser.isToolCallFinishReason()` — already parses `finish_reason` from chunks (agent path only). Pattern exists.
- `MapReducePipeline` heartbeat (SSE comment every 250ms) — pattern exists for keepalive.
- `ApiErrorCode.STREAM_FAILED` — exists but generic. Need `STREAM_TRUNCATED` for truncation-specific signaling.
- `Faults` utility (from tempdoc 382) — available for fault-isolation wrapping.

---

---

## Design

### Design principles

1. **Fix the protocol, not the payloads.** The `Runnable onComplete` / `Consumer<Throwable> onError` callback contract is fine — the problem is that `onComplete` fires on truncation. Fix when they fire, not what they carry.
2. **No interface signature changes.** The `OnlineAiService` interface has ~12 streaming method overloads across 3 modules. Changing `Runnable onComplete` to `Consumer<StreamResult>` would touch every caller for marginal benefit. Keep the binary complete/error contract.
3. **Belt and suspenders.** Fix at Layer 1 (source), Layer 2 (relay), and Layer 3 (consumer) independently. Each layer should handle truncation even if the layer below fails to signal it.
4. **Defer semantic truncation.** `finish_reason: "length"` (model hit token limit) is metadata, not a protocol failure. The stream completed successfully — the response is just short. Log it, but don't treat it as an error. Can be surfaced to the UI in a future tempdoc.

### Decision log

| # | Decision | Status | Answer |
|---|----------|--------|--------|
| D1 | Interface changes? | **RESOLVED** | No. Keep `Runnable onComplete` / `Consumer<Throwable> onError`. Change behavior (when they fire), not signatures. |
| D2 | Where does `StreamTruncatedException` live? | **RESOLVED** | `app-inference` (alongside `LlmServerException`). It's an LLM transport concern, not an API concern. Mapped to `STREAM_TRUNCATED` in `SummaryErrorUtils`. |
| D3 | CancellationException handling? | **RESOLVED** | Keep current behavior (routes to `onComplete`). Client is gone — no user sees the semantically-wrong "done". Changing it would cause 120s latch waits for no benefit. |
| D4 | `finish_reason` extraction? | **RESOLVED** | Extract and log at WARN on truncation. Include in `StreamTruncatedException` message for diagnostics. Do NOT surface to UI in this tempdoc. |
| D5 | Frontend stall timeout? | **RESOLVED** | No separate timeout in `useAppAI`. The backend already has HTTP_TIMEOUT (2 min) → `onError`. The frontend fix (detect silent stream close in `streams.ts`) handles the remaining gap. Adding a client timeout creates complex cancellation coordination for marginal benefit. |
| D6 | Agent streaming? | **DEFERRED** | `AgentController` ignores `writeEvent` return value — agent loop continues after client disconnect. Different problem from completeness contract. Separate tempdoc. |

---

### Phase 1 — Layer 1: Sentinel tracking in OnlineModeOps

**Goal:** `onComplete` only fires on protocol-complete streams. Truncation routes to `onError`.

#### 1a. New exception: `StreamTruncatedException`

```java
// modules/app-inference/.../inference/StreamTruncatedException.java
public class StreamTruncatedException extends RuntimeException {
    private final String finishReason;

    public StreamTruncatedException(String finishReason) {
        super("LLM stream ended without [DONE] sentinel"
              + (finishReason != null ? " (last finish_reason: " + finishReason + ")" : ""));
        this.finishReason = finishReason;
    }

    public String finishReason() { return finishReason; }
}
```

#### 1b. Sentinel + finish_reason tracking in `streamChat` (L242–286)

Current:
```java
try (Stream<String> lines = response.body()) {
    lines.forEach(line -> {
        if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
            // parse chunk, call onChunk
        }
    });
}
onComplete.run();  // fires unconditionally
```

New:
```java
try (Stream<String> lines = response.body()) {
    boolean[] sawDone = {false};
    String[] lastFinishReason = {null};
    lines.forEach(line -> {
        if (line.equals("data: [DONE]")) {
            sawDone[0] = true;
            return;
        }
        if (line.startsWith("data: ")) {
            // existing parsing...
            String fr = node.path("choices").path(0).path("finish_reason").asText(null);
            if (fr != null) lastFinishReason[0] = fr;
            // existing onChunk/onUsage calls...
        }
    });

    if (sawDone[0]) {
        onComplete.run();
    } else {
        LOG.warn("LLM stream ended without [DONE] sentinel (finish_reason={})", lastFinishReason[0]);
        onError.accept(new StreamTruncatedException(lastFinishReason[0]));
    }
}
```

Same change in `streamChatWithTools` (L409–460).

**Note on forEach and [DONE] ordering:** The `[DONE]` line arrives AFTER the last content chunk (per llama-server protocol). `forEach` processes lines sequentially. When `data: [DONE]` is encountered, `sawDone` is set and the line is skipped. When the stream ends (socket close after `[DONE]`), `forEach` exits and the `sawDone` check runs. If the socket closes BEFORE `[DONE]` arrives, `forEach` exits with `sawDone=false` → truncation detected.

**Note on `finish_reason` extraction location:** Currently `extractUsageFromChatChunk` parses the `usage` object from chunks. The `finish_reason` extraction is 1 additional line (`node.path("choices").path(0).path("finish_reason").asText(null)`) in the same parsing block. No new method needed.

#### 1c. No changes to `streamSummary` / `streamAnswer` overloads

These delegate to `streamChat`. The fix propagates automatically.

---

### Phase 2 — Layer 2: Controller double-terminal guard

**Goal:** Prevent the race where timeout fires `error`, then delayed `onComplete` fires `done`. Only one terminal SSE event per stream.

**Pattern:** Add `AtomicBoolean terminalEmitted` to each latch-based site. Guard all three terminal paths (onComplete, onError, timeout) with `compareAndSet`.

Example (applied to all 7 SSE-emitting latch sites):
```java
AtomicBoolean terminalEmitted = new AtomicBoolean(false);
CountDownLatch latch = new CountDownLatch(1);
onlineAi.streamAnswer(
    ...,
    chunk -> { /* existing onChunk with writeEvent + CancellationException */ },
    () -> { // onComplete
        if (terminalEmitted.compareAndSet(false, true)) {
            sseWriter.writeEvent(ctx, "done", donePayload);
        }
        latch.countDown();
    },
    error -> { // onError
        if (terminalEmitted.compareAndSet(false, true)) {
            sseWriter.writeEvent(ctx, "error", errorPayload);
        }
        latch.countDown();
    });

if (!latch.await(120, TimeUnit.SECONDS)) {
    if (terminalEmitted.compareAndSet(false, true)) {
        sseWriter.writeEvent(ctx, "error", timeoutPayload);
    }
}
```

**7 sites to update:**
1. `RagStreamingHandler:232` — handleAskStream
2. `RagStreamingHandler:431` — handleQuickSummaryStream
3. `SummaryController:487` — handleHierarchicalSummaryStream
4. `SummaryController:539` — streamStandardSummary
5. `SummaryController:732` — streamSummaryToSse
6. `FullCoverageSummarizer:171` — streamDirectSummary
7. `SectionProcessingOps:255` — streamReduceAndSynthesize

`MapReducePipeline:433` is an internal accumulation site (no SSE emission) — no guard needed.

#### 2b. New `ApiErrorCode`: `STREAM_TRUNCATED`

```java
/** LLM stream ended without completion sentinel — response may be truncated. */
STREAM_TRUNCATED(ErrorClass.TRANSIENT),
```

Add after `STREAM_FAILED` (L294). ErrorClass is `TRANSIENT` because retry may succeed.

#### 2c. Map `StreamTruncatedException` in `SummaryErrorUtils`

```java
static ApiErrorCode resolveErrorCode(Throwable error) {
    if (error instanceof DocumentService.UnavailableException) {
        return ApiErrorCode.INDEX_UNAVAILABLE;
    }
    if (error instanceof UnsupportedOperationException) {
        return ApiErrorCode.TRANSLATOR_UNAVAILABLE;
    }
    // NEW: stream truncation
    if (error instanceof StreamTruncatedException) {
        return ApiErrorCode.STREAM_TRUNCATED;
    }
    // ... existing delegation ...
}
```

Also update `resolveErrorMessage`:
```java
if (error instanceof StreamTruncatedException) {
    return "The AI response was cut short — please try again";
}
```

---

### Phase 3 — Layer 3: Frontend completeness detection

**Goal:** Frontend detects silent stream close (no `done`/`error` in buffer) and fires `onError` instead of leaving `aiLoading` stuck.

#### 3a. `streams.ts` — Detect stream close without terminal event

```typescript
let receivedTerminal = false;

// In parseSseBufferJson callback:
} else if (event === 'done') {
    if (!receivedTerminal) {
        receivedTerminal = true;
        onDone?.(parsed as TDone);
    }
} else if (event === 'error') {
    if (!receivedTerminal) {
        receivedTerminal = true;
        // ... existing error construction ...
        onError?.(error);
    }
}

// After the while loop exits:
if (!receivedTerminal) {
    const error = new Error('Stream ended without completion') as Error & { code?: string };
    error.code = 'STREAM_INCOMPLETE';
    onError?.(error);
}
```

This serves two purposes:
1. **Once-guard:** Prevents double-terminal race at the frontend (belt + suspenders with Phase 2)
2. **Silent close detection:** If TCP closes without any terminal SSE event, `onError` fires with `STREAM_INCOMPLETE`

**Note on `citation_matches` after `done`:** `RagStreamingHandler` sends `citation_matches` AFTER `done` (post-latch code L275-289). The once-guard only gates `done` and `error` events. Non-terminal events (`chunk`, `meta`, `progress`, `rag_meta`, `citation_matches`) are still dispatched after a terminal event. This is correct — `citation_matches` is supplementary data, not a second terminal.

#### 3b. No changes to `useAppAI.ts` or `useSummary.ts`

Both already handle `onError` correctly:
- `useAppAI`: `onError` clears `aiLoading`, sets `aiError` → spinner stops, error shows
- `useSummary`: `onError` with `streamStarted=true` calls `finalize()` → accepts partial content

The `STREAM_INCOMPLETE` error from 3a flows through the existing `onError` handlers. No new code needed.

---

### Phase 4 — Testing

| Layer | Test | What it verifies |
|-------|------|-----------------|
| Layer 1 | `OnlineModeOpsStreamTest` — mock HTTP response that closes without `[DONE]` | `onError` fires with `StreamTruncatedException`, `onComplete` does NOT fire |
| Layer 1 | `OnlineModeOpsStreamTest` — mock HTTP response with `[DONE]` | `onComplete` fires, `onError` does NOT fire |
| Layer 1 | `OnlineModeOpsStreamTest` — finish_reason extraction | `StreamTruncatedException.finishReason()` carries last observed value |
| Layer 2 | Existing controller tests — verify they still pass after terminal guard | Regression check |
| Layer 2 | `SummaryErrorUtils` — `StreamTruncatedException` → `STREAM_TRUNCATED` | Error code mapping |
| Layer 3 | `streams.test.ts` — mock ReadableStream closes without terminal event | `onError` fires with `code: 'STREAM_INCOMPLETE'` |
| Layer 3 | `streams.test.ts` — double done event | Second `done` is suppressed, `onDone` called once |

---

### Implementation order

```
Phase 1 — Layer 1: OnlineModeOps sentinel tracking (no caller changes)
  ├── 1a. Create StreamTruncatedException in app-inference
  ├── 1b. Add sawDone + finishReason tracking to streamChat
  ├── 1c. Same change in streamChatWithTools
  └── Verify: ./gradlew.bat :modules:app-inference:test

Phase 2 — Layer 2: Controller hardening
  ├── 2a. Add STREAM_TRUNCATED to ApiErrorCode
  ├── 2b. Map StreamTruncatedException in SummaryErrorUtils
  ├── 2c. Add AtomicBoolean terminal guard to 7 latch sites
  └── Verify: ./gradlew.bat :modules:ui:test :modules:app-api:test

Phase 3 — Layer 3: Frontend completeness detection
  ├── 3a. Add receivedTerminal tracking + once-guard + silent-close detection in streams.ts
  └── Verify: cd modules/ui-web && npm run typecheck && npm run test:unit:run

Phase 4 — Testing
  ├── Layer 1 unit tests (OnlineModeOps mock HTTP)
  ├── Layer 2 regression (existing tests)
  └── Layer 3 unit tests (streams.ts mock ReadableStream)
```

Phases 1, 2, 3 are mostly independent (Phase 2c is behaviorally downstream of Phase 1 but compiles without it). Phase 4 runs after all three.

---

## Deferred work

- **D3 — PMD enforcement of `ignored` → `expected`**: 157 sites, separate tempdoc (tracked in 382)
- **D6 — AgentController disconnect detection**: `writeAgentEvent` ignores `writeEvent` return value. Agent loop continues after client disconnect. The agent's Layer 1 path IS now covered (streamChatWithTools has sentinel tracking), so truncated LLM responses are detected. The remaining gap is resource efficiency only (client already left). Research: AMD Gaia, Vercel AI SDK, and LangGraph all solve this with cooperative cancellation — an `AtomicBoolean` checked at safe points (before LLM calls, before tool executions). Javalin has the primitives: `SseClient.terminated()` and `onClose(Runnable)`. Implementation path: thread `AtomicBoolean cancelled` from `AgentController` → `AgentService.runAgent()`, set on disconnect, check between steps. Moderate effort.
- **Semantic truncation (`finish_reason: "length"` UI)**: The second axis of completeness (semantic, not protocol). Infrastructure is complete — `finish_reason` is extracted and logged in OnlineModeOps. Research: every production AI UI (ChatGPT, Claude.ai, LibreChat, Open WebUI) shows an indicator + offers recovery. Industry consensus: inline warning + "Continue" button. ChatGPT auto-shows the button on `finish_reason: "length"`. Implementation path: (1) include `finishReason` in controller `done` SSE payload (low effort), (2) frontend reads it and shows inline indicator (low effort), (3) optional "Continue" button with continuation request logic (medium effort). Best candidate for a follow-up tempdoc.
- **Heartbeat/keepalive**: llama-server sends no keepalives during prompt evaluation. Research: the industry has NOT converged on heartbeat injection — most deployments use large timeouts (5-10 min). LiteLLM rejected TTFT timeout requests. Java's HttpClient has no body-level idle timeout (JDK-8258397, open since 2021). Viable pattern: Javalin injects SSE comments (`": heartbeat\n\n"`) to the browser every 15s while waiting for llama-server (Javalin has `sendComment()` built in). llama-server-facing side needs a watchdog timer (Google GAX `Watchdog` pattern). Current 2-minute HTTP_TIMEOUT backstop is functional. Correctly deferred — moderate effort, no industry standard to follow.

---

## Investigation Log

- 2026-04-08: Split from tempdoc 382 (W22). Full three-layer architecture audit: OnlineModeOps streaming flow, SseWriter disconnect handling, frontend streams.ts/useAppAI/useSummary termination paths. Failure mode matrix constructed. Root cause identified as missing completeness contract across all layers — not a single-site bug.
- 2026-04-09: Production design research — OpenAI, Anthropic, Vercel AI SDK, llama-server streaming protocols. Universal pattern identified: terminal sentinel + finish reason + heartbeat. Two independent axes (protocol completeness vs semantic completeness). Complete streaming site inventory: 2 Layer 1 methods, 9 Layer 2 sites (8 SSE-emitting + 1 internal), 3 Layer 3 files. Existing infrastructure audit: StreamCallbacks, ToolCallParser finish_reason parsing, MapReducePipeline heartbeat, ApiErrorCode enum.
- 2026-04-09: Full design written. 3-phase approach: Layer 1 sentinel tracking (OnlineModeOps), Layer 2 double-terminal guard (7 controller sites), Layer 3 silent-close detection (streams.ts). Key decisions: no interface signature changes, StreamTruncatedException in app-inference, CancellationException→onError, no client-side stall timeout, agent disconnect deferred.
- 2026-04-09: Implementation complete. User decisions: (1) requireSentinel opt-out flag on streamChat for MapReducePipeline lenient mode, (2) CancellationException routed to onError for clean contract. Changes: 14 files modified/created, ~+200/-50 lines. New StreamTruncatedException, STREAM_TRUNCATED error code, sentinel+finish_reason tracking in both streamChat and streamChatWithTools, AtomicBoolean terminal guard on 7 controller latch sites, receivedTerminal once-guard + silent-close detection in streams.ts. All tests pass (155 app-inference, ui module, 200 frontend unit, app-api schema contract). Build clean.
- 2026-04-09: Post-implementation critical analysis → 3 fixes applied: (1) CancellationException log noise — 6 controller onError lambdas now skip `log.error` for CancellationException (client disconnects are routine, not errors), (2) lenient mode test — `streamChat_lenientModeCompletesOnMissingSentinel` verifies requireSentinel=false path, (3) frontend stream tests — 5 new tests in `streams.test.ts` covering STREAM_INCOMPLETE detection, once-guard (double done/error suppression), non-terminal pass-through. All 156 app-inference + ui module + 205 frontend tests pass.
- 2026-04-09: Research on deferred items. AgentController: AMD Gaia/Vercel AI SDK/LangGraph all use cooperative cancellation (AtomicBoolean at safe points); Javalin has primitives (terminated(), onClose). finish_reason UI: industry consensus is inline indicator + "Continue" button (ChatGPT, Claude.ai, LibreChat all do this); infrastructure already in place. Heartbeat: industry has NOT converged (LiteLLM rejected TTFT timeout requests); Java HttpClient has no body idle timeout (JDK-8258397 open since 2021); viable pattern is Javalin SSE comment injection to browser + watchdog timer for llama-server side.
- 2026-04-09: Merged to main (fast-forward). Indirect changes verified: schemas regenerated (no drift), lockfiles current, llms.txt/skills-sync current, module deps diagram current, tempdoc 378 updated (W18-W22 resolved, active count 22→17, Stream I marked RESOLVED). Dev server smoke tests: summary stream (event: done received correctly), Q&A stream (321 chunks + done with full metadata), backend logs (zero false StreamTruncatedException), jseval hybrid queries (completed successfully). All happy paths confirmed working.
