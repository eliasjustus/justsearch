---
title: "498 — Cross-shape semantic context threading"
type: tempdocs
status: done
created: 2026-05-16
implemented: 2026-05-16
closed: 2026-05-19
category: backend / conversation substrate
authority: |
  Standalone design doc. Extracted from slice 497 §4 Layer 5 (V2 item)
  and §10.2 (investigation finding). Closure note (2026-05-19): the
  design proposed here was ratified inside slice 497 §12.2 as a V1.1
  promotion and shipped as `ExternalContextInjector` in
  `modules/app-services/.../conversation/spi/`. The injector ID
  (`core.external-context`), request-body field (`context`), token-
  capped sliding-window logic, and shape-manifest wiring
  (`[ExternalContextInjector.ID, RAGContext.ID]` on RAGAskShape;
  `[ExternalContextInjector.ID, "core.user-prompt"]` on ExtractShape)
  all match this tempdoc's §60–§105 design. FreeChatShape continues
  to use ConversationStore for history per §91. The tempdoc is
  retained as design rationale; the implementation is the slice 497
  V1.1 ship plus the file linked above.
related:
  - slices/497-unified-chat-surface.md §4 Layer 5, §10.2, §12.2 (V1.1 ratification + ship)
  - slices/491-chat-substrate.md (ConversationEngine + ContextInjector SPI)
  - modules/app-services/.../conversation/spi/ExternalContextInjector.java (implementation)
---

# 498 — Cross-shape semantic context threading

## The problem

The unified conversation surface (slice 497) dispatches each message
independently to a ConversationShape. The FE maintains a visual
conversation thread, but the backend sees each dispatch in isolation.

When the user sends a FreeChat message, then toggles Documents and sends
a follow-up, the RAG shape's `RAGContext` injector retrieves documents
based on the current question only — it has no knowledge of the prior
conversation. The user sees a continuous thread; the backend sees
independent requests.

This matters when follow-up questions depend on prior context:
- "What does the architecture doc say about the Head process?"
  (Documents affordance → RAG shape, works fine)
- "How does it communicate with the Worker?"
  (still Documents → RAG shape, but "it" has no referent — the backend
  doesn't know "it" = "the Head process" from the prior turn)

For FreeChat→FreeChat sequences this doesn't apply (FreeChat is
PERSISTENT; `ConversationStore` maintains history across turns within
the same sessionId). The gap is specifically cross-shape sequences and
EPHEMERAL shape sequences.

## What exists today

The `ConversationEngine.dispatchSubstrateDriven()` builds the LLM
input as: `[systemPrompt] + history (from ConversationStore, if
PERSISTENT) + injector messages`. For EPHEMERAL shapes (RAGAsk,
Extract, Summarize), history is empty — the engine starts fresh.

No existing `ContextInjector` reads externally-provided prior messages
from the request body. Verified against all four implementations:
- `UserPromptInjector` reads only `body.get("prompt")`
- `RAGContext` reads `body.get("question")`, `body.get("docIds")`,
  `body.get("topK")`
- `DocAccess` reads `body.get("docId")`, `body.get("content")`
- `BatchDocAccess` reads `body.get("docIds")`

The `ConversationStore` path (PERSISTENT shapes) loads history from
file-backed JSONL keyed by sessionId, not from the request body. These
are separate mechanisms.

## The design: ExternalContextInjector

A new `ContextInjector` implementation:

```
id: "core.external-context"
```

On `inject(ctx)`:
1. Read `ctx.requestBody().get("context")` — expect a
   `List<Map<String, Object>>` where each entry has `role` and
   `content` keys (the standard message format).
2. Validate: reject entries without `role`/`content`; cap at a
   configurable max (e.g., 10 messages) to prevent prompt stuffing.
3. Return `InjectorResult.messagesOnly(validatedMessages)` — these
   prepend before the shape's own injected messages.

The FE includes the last N messages from the conversation thread in
the request body as a `context` array. Only messages with actual
content (not error messages) are included.

### Shape manifest changes

Shapes that should receive external context add
`"core.external-context"` to their `contextInjectorIds` list, ordered
BEFORE their shape-specific injector:

- `core.rag-ask`: `["core.external-context", "core.rag-context"]`
  → prior messages appear before retrieved documents in the LLM input
- `core.extract`: `["core.external-context", "core.user-prompt"]`
  → prior messages provide context for the extraction prompt
- `core.free-chat`: unchanged (PERSISTENT; ConversationStore handles
  history via sessionId)

### FE changes

`UnifiedChatView.send()` includes the last N thread messages (excluding
the current message) in the request body:

```typescript
const context = this.thread
  .filter(m => m.content.trim())
  .slice(-10)
  .map(m => ({ role: m.role, content: m.content }));
body.context = context;
```

### What this does NOT do

- Does not merge ConversationStore history with external context (they
  are separate mechanisms; PERSISTENT shapes use the store, EPHEMERAL
  shapes use external context).
- Does not carry RAG citations or retrieval metadata across turns (only
  `role` + `content` text is threaded).
- Does not maintain a backend session for the unified surface (the FE
  thread is the session; the backend is stateless per-request for
  EPHEMERAL shapes).

## Scope

~50 LOC backend (new injector + registration), ~20 LOC FE (context
array in request body), ~2 lines per shape manifest that opts in.

## Open questions

1. **Should the context include the system's error messages?** Probably
   not — they're UI artifacts, not conversational content.

2. **How many prior messages to include?** Too few loses context; too
   many wastes prompt budget. A sliding window of 6-10 messages seems
   reasonable. The engine's `maxTokens` parameter already caps the
   response; the context just uses prompt budget.

3. **Should RAGContext use the threaded context for retrieval?** Today
   it retrieves based on `body.get("question")` only. With context
   available, it could concatenate the last user message + current
   question for richer retrieval. This is a separate enhancement.
