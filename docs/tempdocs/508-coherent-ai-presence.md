---
title: "508 — Coherent AI Presence"
---

# 508 — Coherent AI Presence

**Status**: done
**Scope**: Frontend AI state model, thinking visibility, citation interaction, consumer wiring  
**Prerequisite**: Slice 497 (Unified Chat V1) shipped. 4-SPI conversation substrate complete.

**Shipped:**
- AiStateStore — unified pub-sub model (capabilities, connection, runtime, activity, index)
- StatusDeck migration — reads from AiStateStore; shows correct mode + model name
- BrainSurface migration — consults AiStateStore; shows "AI Online" instead of "Not Installed"
- UnifiedChatView migration — reads capabilities.rag for RAG-first default; no direct statusPoll
- IndexingOverlayHost migration — reads from AiStateStore; no direct inferencePoll
- Thinking timer — elapsed counter driven by AiStateStore activity.startedAtMs
- Cancel/Stop button during streaming
- Citation interaction chain — cite-ref-click → citation-select bridge + hover card
- Copy button on assistant messages
- Affordance preview line
- Session continuity — per-session UUID, localStorage persistence, resume prompt, new-chat button
- Session REST endpoints — GET /api/chat/conversations + history
- Poller error notification — inferencePoll + statusPoll notify on failure (instant disconnect detection)
- Shell owns AiStateStore initialization

---

## Diagnosis

The conversation substrate works — shapes dispatch, SSE streams, citations arrive. But the
user-facing experience has a coherence problem: the frontend treats AI capabilities as
independent features (inference poller, install poller, chat streaming, reasoning controller,
citation renderer), while the user experiences them as one entity.

**Evidence from browser validation (2026-05-17, dev stack with Qwen 3.5 9B CUDA):**

| What the AI is doing | What the UI says |
|---|---|
| Runtime active, model loaded, `/api/inference/status` returns `mode: "online"` | Status deck: "offline" |
| GPU runtime activated, model serving requests | Brain surface: "Not Installed" |
| Processing prompt (60+ seconds) | Blinking cursor, no timer, no feedback |
| Streaming response with citation claims | Superscripts render but don't click through |
| FreeChat with backend persistence active | Conversation lost on page refresh |

**Root cause:** No unified model of AI state in the frontend. Five independent subsystems each
maintain a partial view:

1. `inferencePoll.ts` — polls `/api/inference/status` every 5s, knows mode/model/GPU
2. `statusPoll.ts` — polls `/api/status` every 10s, knows worker/embedding/schema state
3. `BrainSurface.ts` — polls install/runtime/pack endpoints with its own timers
4. `UnifiedChatView.ts` — owns streaming state locally (isStreaming, streamingText)
5. `StatusDeck.ts` — reads from inferencePoll, maps mode to pill class

No component sees the full picture. StatusDeck doesn't know about streaming activity.
BrainSurface doesn't cross-reference runtime activation against install state. The chat view
doesn't know whether the AI is capable before the user presses Send.

**Additional finding from experiments (2026-05-17):**

The "offline" problem is worse than a poll-cadence race. After a backend restart (port change),
the Vite proxy hangs on API requests. `inferencePoll.fetchOnce()` catches the network error
silently (`catch {}` — no state update, no listener notification). The `lastSnapshot` stays
null or stale indefinitely. The StatusDeck shows "offline" not because the AI is offline, but
because the frontend **can't reach the backend** — and there is no distinction between the two.

Root causes confirmed:
- `inferencePoll.ts:37-53`: `fetchOnce()` is fire-and-forget (`void fetchOnce()`), errors
  silently swallowed, `lastSnapshot` never updated on failure
- `StatusDeck.ts:176-181`: `inferencePillClass()` defaults to `'offline'` when `this.inference`
  is null — conflates "disconnected" with "AI offline"
- `reasoningBudget` defaults to `0` (disabled) in `ResolvedConfigBuilder.java:907` — the 60s
  silence during testing was Qwen 3.5 thinking with reasoning tokens suppressed, not prompt
  processing

---

## Design A: AI State Store

### Principle

A single `AiStateStore` that composes all AI-related state into one subscribable model.
It does not replace the individual pollers — it aggregates them, adding the **activity
dimension** they currently lack.

Follows the existing `AdvisoryStore` pattern (multi-stream reducer aggregating heterogeneous
sources into a unified snapshot with pub-sub fan-out).

### State Shape

```
AiState {
  capabilities: {
    chat: boolean          // inference online + model loaded
    rag: boolean           // chat + indexed documents > 0
    extract: boolean       // chat (same model)
    tools: boolean         // agent shapes registered + chat
    embedding: boolean     // worker ready + encoder loaded
  }

  connection: {
    reachable: boolean           // can the frontend reach the backend?
    lastSuccessMs: number | null // timestamp of last successful poll
    consecutiveFailures: number  // 0 when healthy
  }

  runtime: {
    mode: 'offline' | 'online' | 'indexing' | 'starting'
    modelId: string | null
    modelLabel: string | null
    contextWindow: number | null
    vramUsage: number | null
    gpu: { available: boolean, description: string } | null
    installed: boolean
    installing: boolean
  }

  activity: {
    state: 'idle' | 'thinking' | 'streaming' | 'extracting'
    shapeId: string | null
    startedAtMs: number | null
    canCancel: boolean
    cancel: (() => void) | null
  }

  // Derived
  statusLabel: string
  statusTier: 'online' | 'degraded' | 'offline' | 'disconnected'
}
```

### Source Composition

| Source | Feeds | Cadence |
|---|---|---|
| `inferencePoll` | runtime.mode, runtime.modelId, runtime.gpu, capabilities.chat | 5s poll |
| `statusPoll` | capabilities.rag, capabilities.embedding | 10s poll |
| Install/runtime API | runtime.installed, runtime.installing | On-demand (Brain) |
| Chat view calls | activity.state, activity.shapeId, activity.cancel | Event-driven |

### Consumer Migration

| Component | Current source | New source |
|---|---|---|
| `StatusDeck` | `inferencePoll` → `inferencePillClass()` | `AiStateStore` → `statusLabel` + `statusTier` |
| `BrainSurface` | Own pollers for install + runtime + inference | `AiStateStore.runtime` (+ own install detail poller for progress UI) |
| `UnifiedChatView` | `statusPoll` for RAG-first default | `AiStateStore.capabilities.rag` |
| `IndexingOverlay` | `inferencePoll` for mode | `AiStateStore.runtime.mode` |

### Activity State Protocol

Chat views report activity transitions to the store:

```
// UnifiedChatView.send()
setAiActivity({ state: 'thinking', shapeId, cancel: () => abort() })

// onChunk handler (first content token)
setAiActivity({ state: 'streaming', shapeId, cancel: () => abort() })

// onDone handler
setAiActivity({ state: 'idle' })
```

This makes streaming state **shell-visible** without coupling the StatusDeck to chat internals.

---

## Design B: Thinking Visibility

### Layer 1 — Elapsed Timer (always, no backend changes)

When `activity.state === 'thinking'` for longer than 2 seconds (grace period for fast responses),
the streaming block shows: **"Thinking... 12s"** with a live counter.

The timer starts when the SSE connection opens, not when reasoning chunks arrive. This covers
the prompt-processing phase where the model has not yet emitted any tokens.

The existing `ReasoningController.elapsedSeconds` starts when reasoning chunks arrive — too late.
The elapsed timer is a separate concern, driven by `activity.startedAtMs` in the AiStateStore.

### Layer 2 — Reasoning Trace (when model supports it)

The infrastructure is fully wired end-to-end:

```
LlamaServerOps (--reasoning-format deepseek)
  → OnlineModeOps.streamChatWithTools() (onReasoningChunk callback)
    → ConversationEngine (SseEvent "reasoning_chunk")
      → streams.ts (onReasoningChunk handler)
        → ReasoningController.handleReasoningChunk()
          → ReasoningBlock component (collapsible trace)
```

All shapes have generated `onReasoningChunk` handlers. The `ReasoningController` is already
instantiated in `UnifiedChatView`. The `ReasoningBlock` renders in `renderStreamingBlock()`.

**Verified:** `reasoningBudget` defaults to `0` (`ResolvedConfigBuilder.java:907`). Dev profile
does not override it. `LlamaServerOps.java:273-278` passes `--reasoning-budget 0` to
llama-server, which suppresses all reasoning token generation. Setting to `-1` via
`JUSTSEARCH_REASONING_BUDGET=-1` env var or `-Djustsearch.llm.reasoning_budget=-1` JVM arg
enables unrestricted reasoning. Live experiment was blocked by MCP process tree env propagation;
end-to-end reasoning flow needs manual verification with the env var set before session start.

### Cancel Affordance

The "Thinking..." / "Send" button becomes a Cancel button during streaming:

- `isStreaming && !streamingText` → show "Cancel" (clickable, calls `activity.cancel()`)
- `isStreaming && streamingText` → show "Stop" (clickable, same action)
- `!isStreaming` → show "Send"

The `AbortController` already exists in `UnifiedChatView`. Exposing it through
`AiStateStore.activity.cancel` lets any component cancel (not just the chat view).

---

## Design C: Citation Interaction Chain

### Existing Pieces

| Component | What it does | What's missing |
|---|---|---|
| `StreamingTextBlock` | Renders `[n]` superscripts, emits `cite-ref-click` (`composed: true`) | Nothing — verified |
| `Claim` interface | Maps `sentenceIndex` → `sourceRefs: number[]` | Nothing — verified |
| `CitationsPanel` | Emits `citation-select` (`composed: true`) with `CitationSelectDetail` | Nothing — verified |
| `InspectorPane` | `highlightCitation(startLine, endLine)` + pending highlight on preview load | Nothing — verified |
| `Shell` | Listens `citation-select` → calls `setSelected()` + `pane.highlightCitation()` | Nothing — verified |
| `UnifiedChatView` | Renders StreamingTextBlock + CitationsPanel | **Missing: `cite-ref-click` → `citation-select` bridge** |

### Completing the Chain

**Step 1 — Wire `cite-ref-click` to `citation-select` in UnifiedChatView:**

When a superscript click bubbles from StreamingTextBlock:
1. Look up `claims[sentenceIndex].sourceRefs[0]` → index into `sources` array
2. Get the `RetrievalCitation` at that index
3. Dispatch `citation-select` CustomEvent with `{ parentDocId, startLine, endLine, startChar, endChar }`

This is 10-15 lines of event handler code. The existing event types and data structures
already match.

**Step 2 — Add hover preview card:**

The only new component. A floating card that appears on superscript hover showing:
- Document name (from `parentDocId` → display name lookup)
- Excerpt text (from `RetrievalCitation.excerpt`)
- Grounding score (from `Claim.score`)

Positioned relative to the superscript element. Dismissed on mouse-out. Uses existing
design tokens (`surface-2`, `border-subtle`, `text-primary`).

This follows the Perplexity pattern (research finding): inline superscripts + hover
preview + sidebar panel. The sidebar (CitationsPanel) already exists.

---

## Consumer Improvements

These sit on top of the structural changes and can be implemented independently.

### Affordance Preview Line

Below the affordance bar, show one line of context:

| Affordance | Preview text |
|---|---|
| Documents (active) | "Searching 108 documents" (from statusPoll) |
| Schema (active) | "Extracting with schema (4 properties)" (parsed from schemaDraft) |
| None | (hidden) |

Data sources already available. Purely a render concern.

### Copy Buttons

Add clipboard copy to:
- Assistant message blocks (copy rendered text)
- Extract output blocks (copy raw JSON)

Every major chat product has this. Uses `navigator.clipboard.writeText()`.

### Session Continuity

Backend `FileConversationStore` is fully implemented with JSONL storage, session metadata
(`firstUserMessage`, `lastActiveAtMs`, `messageCount`), and `listSessions()` method.

**Note:** `ConversationStore.listSessions()` exists but has **no REST endpoint** for
FreeChatShape sessions. Only agent sessions are exposed via `GET /api/chat/sessions`
(`AgentController`). A new endpoint is needed in `ChatController` or a shared route.

Frontend changes:
1. Generate stable UUID sessionId (persisted to localStorage per conversation thread)
2. Pass sessionId in FreeChat dispatch body (engine loads history automatically)
3. On chat surface mount, check localStorage for recent sessionId → offer "Continue conversation?"
4. Optional: session list drawer using `listSessions()` API

### Brain Runtime Display

BrainSurface subscribes to `AiStateStore.runtime` for the top-level status card. Replaces
the current three-state label logic (`installStatus` only) with:

| AiState.runtime | Label |
|---|---|
| `mode === 'online'` | "Online — {modelLabel}" with VRAM + context info |
| `mode === 'indexing'` | "Indexing — embedding model active" |
| `mode === 'starting'` | "Starting..." with spinner |
| `installing === true` | "Installing..." with progress |
| `installed && mode === 'offline'` | "Installed — AI offline" |
| `!installed` | "Not Installed" |

BrainSurface retains its own detail pollers for install progress, pack import, and variant
selection — those are configuration concerns, not status. The top-level status card reads
from the unified model.

---

## Implementation Order

```
Phase 1: AiStateStore (foundation)
  ├── Create store with pub-sub pattern
  ├── Wire inferencePoll + statusPoll as sources
  ├── Migrate StatusDeck to read from store
  └── Verify: status deck shows correct mode within 5s

Phase 2: Thinking Visibility (highest UX impact)
  ├── Add activity state protocol to store
  ├── Wire UnifiedChatView to report activity transitions
  ├── Add elapsed timer to streaming block (2s grace)
  ├── Convert Send button to Cancel during streaming
  ├── Verify reasoningBudget config for reasoning trace
  └── Verify: timer shows during thinking, cancel works

Phase 3: Citation Interaction (completing existing chain)
  ├── Wire cite-ref-click → citation-select in UnifiedChatView
  ├── Add hover preview card component
  └── Verify: superscript click opens inspector with highlighted source

Phase 4: Consumer Improvements (independent of each other)
  ├── Affordance preview line
  ├── Copy buttons
  ├── Session continuity (frontend wiring)
  └── Brain runtime display (migrate to AiStateStore)
```

Phase 1 is the foundation — everything else reads from it. Phase 2 has the highest user
impact (the 60s silent thinking). Phase 3 connects existing pieces. Phase 4 items are
independent and can ship in any order.

---

## Future Directions (research findings, 2026-05-17)

Research across internet sources and codebase exploration identified opportunities that
the AiStateStore foundation enables. Grouped by effort and value.

### A. Capability-Gated Affordances

The established pattern (Cursor, LM Studio, VS Code Copilot) is **progressive affordance
reveal**: features remain visible but show their precondition ("AI offline — start AI in
Brain") rather than being hidden or silently broken. The anti-pattern is features that look
clickable but fail with a generic error.

Concrete opportunities in this codebase:

- **Affordance buttons in UnifiedChatView**: Documents and Schema buttons are disabled only
  during streaming. They should also disable when `!capabilities.rag` / `!capabilities.chat`
  with a tooltip explaining why.
- **InspectorPane Answer/Ask tabs**: Always visible, no guards. Should disable when
  `!capabilities.chat` with inline "AI offline" label.
- **Send button**: Should disable when `!capabilities.chat`, not just when input is empty.

### B. Ambient Activity Signal

The pattern (Raycast, Windows Copilot taskbar, AnythingLLM sidebar) is a **persistent
peripheral indicator** that survives navigation — the user sees "AI is busy" without
switching to the chat surface.

Concrete opportunity: add a pulsing activity dot to the Chat rail icon when
`activity.state !== 'idle'`. Green for streaming, teal for thinking. The rail is always
visible; the signal is zero-cost and additive.

### C. Lightweight Conversation Management

Research shows the minimum viable bar is: auto-generated title + recent-conversations
dropdown + markdown export to clipboard.

**Auto-title**: Fire a background LLM call after the first AI response, asking for a 3-5
word title. The model is already running (zero extra cost for local inference). Fallback:
truncate first user message to ~50 chars. Open WebUI and ChatGPT both use this pattern.

**Recent conversations dropdown**: A small clock/history icon button in the chat header
that opens a popover showing the 5-10 most recent sessions by title + relative timestamp.
No persistent sidebar needed. JetBrains AI Assistant uses this pattern. The backend
already has `GET /api/chat/conversations` and `listSessions()` returning `firstUserMessage`,
`lastActiveAtMs`, `messageCount`.

**Clipboard export**: One button → copy entire thread as Markdown. This is the "one-click"
default that Claude Exporter and AI Studio Chat Exporter implement. JSON file export is
secondary.

### D. Degraded-Mode Transparency

The Smashing Magazine 2026 pattern is a **capability audit list** rather than a single
banner: show specifically what works and what doesn't. The anti-pattern (still widespread)
is "AI service unavailable" when only one subsystem is down.

The `AiState.capabilities` object already computes this: `{ chat: true, rag: false,
extract: true, embedding: false }`. A compact status line in the chat header could render
this as pills: "Chat ✓ · RAG ✗ (no documents) · Embedding ✗ (blocked)".

### E. Cross-Surface AI Invocation

A global keyboard shortcut (e.g., `Ctrl+Shift+A`) that opens the chat from any surface,
gated on `capabilities.chat`. If AI is offline, show a toast instead of navigating.
The navigation infrastructure (Shell's `navigate-with-context` event) already supports
cross-surface dispatch.

### F. Search-to-Chat Bridge

SearchSurface currently has no "Ask AI about these results" affordance. When
`capabilities.rag` is true, a button on the search results page could navigate to
UnifiedChatView with the query pre-filled and Documents affordance active. The
`unifiedChatState` store already supports this cross-surface handoff.

### Priority Assessment

| Opportunity | Effort | Value | Dependencies |
|---|---|---|---|
| A. Capability-gated affordances | Small | High | None — AiStateStore ready |
| B. Rail activity dot | Small | Medium | None — AiStateStore activity ready |
| C. Auto-title + recent dropdown | Medium | High | Backend ready; FE needs dropdown component |
| D. Degraded-mode pills | Small | Medium | None — capabilities object ready |
| E. Global AI shortcut | Small | Medium | Shell keyboard handler |
| F. Search-to-chat bridge | Small | Medium | unifiedChatState already supports it |
