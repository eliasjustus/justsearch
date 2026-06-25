---
title: "387 — Finish Reason UI Surfacing"
---

# 387 — Finish Reason UI Surfacing

**Status:** Implemented (Phase 1 + Phase 2)
**Created:** 2026-04-09
**Origin:** Deferred from tempdoc 383 (streaming completeness contract)
**Prerequisites:** tempdoc 383 (implemented — finish_reason extraction infrastructure in place)

## Problem

When the LLM hits its `max_tokens` limit, the response silently ends mid-sentence. The user sees what appears to be a complete response but the model was forced to stop. There is no indication that the response is semantically incomplete, and no way to continue generation.

This is the second axis of completeness identified in tempdoc 383's research: protocol completeness (sentinel received?) vs semantic completeness (finish_reason == stop?). Tempdoc 383 solved axis 1. This tempdoc addresses axis 2.

## Current State

**Infrastructure already done (tempdoc 383):**
- `OnlineModeOps.streamChat` extracts `finish_reason` from every chunk via `node.path("choices").path(0).path("finish_reason").asText(null)` and stores the last non-null value in `lastFinishReason`
- `OnlineModeOps.streamChatWithTools` does the same
- `finish_reason` is logged at WARN when stream truncation occurs (no `[DONE]`)
- `finish_reason` is NOT currently passed through to controllers or the frontend on successful completion (`sawDone=true`)

**What's missing:**
1. Controllers don't include `finishReason` in the `done` SSE payload
2. Frontend doesn't read or act on `finishReason`
3. No UI indicator for "response may be incomplete"
4. No "Continue" button or continuation mechanism

## Industry Research (from tempdoc 383)

Every production AI UI handles this:

| Product | Indicator | Recovery |
|---------|-----------|----------|
| ChatGPT | "Continue generating" button (auto-appears on `finish_reason: "length"`) | Button resumes generation |
| Claude.ai | "Claude hit the maximum length" message | Manual "please continue" |
| LibreChat | Button triggered by `finish_reason: max_tokens` | Sends continuation request |
| Open WebUI | "Continue Response" button (always visible, buggy) | Often restarts instead of continuing |
| Anthropic API docs | `"[Response truncated due to token limit]"` inline | Auto-continue loop pattern |

Industry consensus: inline warning + "Continue" button. Silent acceptance is universally considered the worst option.

## Implementation Path

### Phase 1 — Pass `finishReason` through `done` SSE payload (low effort)

**Layer 1 change:** `onComplete` currently fires as a bare `Runnable`. To pass `finishReason` to controllers without changing the interface (per tempdoc 383 D1), the simplest approach is a thread-local or a new callback. Alternatives:
- Add `finishReason` to `AiUsage` (bundled in the same final chunk from llama-server)
- Add a new `Consumer<String> onFinishReason` callback
- Change `Runnable onComplete` to `Consumer<StreamResult>` (rejected in 383 D1 but may be worth revisiting for this narrower scope)

**Layer 2 change:** Controllers include `"finishReason": "stop"` or `"finishReason": "length"` in the `done` SSE payload.

### Phase 2 — Frontend inline indicator (low effort)

- `streams.ts`: Pass `finishReason` from the `done` payload through `onDone`
- `useAppAI.ts`: Store `finishReason` in `aiDoneMeta`
- UI component: When `finishReason === "length"`, show an inline pill or banner: "Response may be incomplete (token limit reached)"

### Phase 3 — "Continue" button (medium effort, optional)

- UI: "Continue" button appears when `finishReason === "length"`
- On click: Send a continuation request with the accumulated response as context + "Please continue from where you left off"
- Append the continuation to the existing response
- Known issues from industry: continuations can repeat content, lose context, or hallucinate. Consider a simple approach first.

## Scope

- Phase 1 + 2 are the core deliverable
- Phase 3 is optional / stretch goal
- Does NOT change the streaming protocol contract (tempdoc 383)
- Does NOT affect error handling paths

## Implementation Log

- 2026-04-09: Created from tempdoc 383 deferred item. Industry research already completed (see tempdoc 383 §Production Design Research and §Deferred work). Infrastructure (finish_reason extraction in OnlineModeOps) already in place.
- 2026-04-09: Full investigation of the data flow from `OnlineModeOps.lastFinishReason[0]` through the interface chain to controllers and frontend. Identified 7 browser-facing callsites and 5 non-browser callsites. Evaluated three approaches (bundle in AiUsage, new callback, change onComplete type). Selected **Approach C** — change `Runnable onComplete` → `Consumer<String> onComplete`.
- 2026-04-09: **Implemented Phase 1 + Phase 2.** Changes across 26 files:
  - **Backend interface chain (4 files):** Changed `Runnable onComplete` → `Consumer<String> onComplete` in `OnlineAiService`, `OnlineModeOps`, `InferenceLifecycleManager`, `OnlineAiServiceImpl`. Core fix: `onComplete.run()` → `onComplete.accept(lastFinishReason[0])` at 3 sites in `OnlineModeOps` (streamChat canonical, lenient mode, streamChatWithTools canonical).
  - **Browser-facing controllers (5 files, 7 sites):** Added `if (finishReason != null) payload.put("finishReason", finishReason)` to all done SSE payloads. Converted 2 immutable `Map.of()` payloads to mutable `HashMap` (SummaryController hierarchical + standard).
  - **Non-browser callsites (4 files):** Mechanical lambda changes (`latch::countDown` → `fr -> latch.countDown()`, etc.).
  - **Frontend types (2 files):** Added `finishReason?: string` to `SummarizeDonePayload`, `SummarizeBatchDonePayload`, `AskDonePayload`, and `AiDoneMeta`.
  - **UI indicator (3 files):** When `finishReason === 'length'`, the "Complete" checkmark in `InspectorAnswer` is replaced with an amber "Token limit reached" warning with tooltip. Threaded through `useInspectionContext` → `InspectionPane` → `InspectorAnswer`.
  - **Tests (9 test files + 2 new tests):** Mechanical `Runnable` → `Consumer<String>` updates across 9 test files (~55 touch points). Added `streamChat_passesStopFinishReasonToOnComplete` and `streamChat_passesLengthFinishReasonToOnComplete` in `OnlineModeOpsTest`. Added `passes finishReason through done payload` in `streams.test.ts`.
  - All tests pass. Pre-existing failures in `contract.test.ts` (modelDistribution) and `LifecycleContractTest` (indexStatusReason) are unrelated.
