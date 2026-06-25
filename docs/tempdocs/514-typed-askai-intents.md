---
title: "514 — Discriminated-union argument shape for askAi"
---

# 514 — Discriminated-union argument shape for askAi

**Status**: done

**Honest scope (revised by slice 515 §FIX-5):** This slice ships a
**discriminated-union argument shape for the `askAi` cross-surface helper**
— a per-callsite type contract that enforces the right fields per intent
kind (`ask` / `summarize-doc` / `ask-about-doc`). The original framing of
"Intent as universal verb consolidation" oversold the work: 514 does NOT
touch the substrate Intent system (`ShellAddress`, `IntentRouter`,
`IntentSource`), only the helper that dispatches a navigate intent into
`unifiedChatState`.

Slice 515 §FIX-5 also removed the `kind` discriminant from the navigation
state payload. The original 514 design carried `kind` through
`unifiedChatState` + the URL projector with the intent of future
per-kind ConversationShape routing. No consumer materialised, and
investigation showed that for the three current kinds, `kind` says
nothing that `affordance` + `docIds` don't already say. The discriminant
remains as a call-site type contract; a future kind that needs different
routing can re-introduce it with a named consumer.

**Live verification (2026-05-18, dev stack with --llm):**
- All four probe variants dispatched a navigate-with-context event with
  the expected payload:
  - `{kind: 'ask', question, docIds: ['d1'], affordance: 'documents'}` →
    state.query, docIds, affordance preserved
  - `{kind: 'summarize-doc', docId, docName: 'Report.pdf'}` →
    query="Summarize Report.pdf", docIds=[docId], affordance=documents
  - `{kind: 'ask-about-doc', question, docId, docName}` → raw question
    preserved, docIds=[docId], affordance=documents
  - Legacy `askAi('legacy form', {affordance: 'documents'})` → maps to the
    same shape as `{kind: 'ask'}`
- Capability gate verified: askAi returns false + no event fires when
  `capabilities.chat = false`.
- 9/9 askAi.test.ts unit tests pass (post-FIX-5 update).

**Scope**: Replace `askAi(question: string, opts)` with a typed
discriminated-union intent at the call site, while preserving the legacy
form for backward compatibility. The kind discriminant is consumed by the
helper's own `renderIntent` function (which picks the templated query +
docIds + affordance per kind); it is not carried beyond the dispatch.

## Thesis

The current `askAi(question, opts)` works but conflates "what the user
wants the AI to do" (intent) with "what string to render in the chat
input" (UI affordance). The three existing callsites paper over this by
templating the string ("Summarize X", "Tell me about X") at the call site
— intent semantics encoded in prose.

Per §G U7, the JustSearch substrate is asymmetric: plugins consume
Resources and dispatch Commands; the substrate's primitives are
typed records (Operation, Resource, Intent). For askAi-style invocations
to fit the substrate, the call needs a typed intent kind, not a string.

## Design decisions

### A.1 — Discriminated union of intent kinds

```typescript
export type AskAiIntent =
  | { kind: 'ask'; question: string; docIds?: string[]; affordance?: Affordance }
  | { kind: 'summarize-doc'; docId: string; docName?: string }
  | { kind: 'ask-about-doc'; question: string; docId: string; docName?: string };
```

Three V1 kinds map directly to the three current callsites. Each kind
declares its required + optional fields explicitly. Adding new kinds
(e.g., `'voice-input'`, `'plugin-emitted'`, `'branch-from'`) is a one-
line extension to the union.

### A.2 — `askAi` becomes overloaded; legacy form preserved

```typescript
export function askAi(intent: AskAiIntent): boolean;
export function askAi(question: string, opts?: AskAiOptions): boolean;  // legacy
```

The legacy form maps internally to `{kind: 'ask', question, ...opts}`.
This keeps the migration low-risk — callers can move to typed intents at
their own pace.

### A.3 — The typed intent is the cross-surface contract

For now the typed-intent → navigate-with-context translation happens
inside `askAi`. The discriminant is preserved in the navigation state
(`kind` field on unifiedChatState) so the chat surface can inspect it
in the future to select a different ConversationShape per kind.

V1 routes all kinds through the same code path (FreeChat with templated
question) for `'ask'` and `'ask-about-doc'`, and pre-fills with a
"Summarize: <docName>" string for `'summarize-doc'`. Substantively
identical to today's behavior; the contract is the type signature.

V2 (out of scope, named follow-up) would let the chat surface pick
SummarizeShape vs FreeChat vs RAGAsk based on the intent kind.

### A.4 — Out of scope (deferred to V2 or separate tempdocs)

- Voice IntentSource — needs Whisper-tiny ONNX bundling + mic permissions
- ACP IntentSource — needs external agent integration
- Per-kind ConversationShape routing — V2
- Typed intent emission from plugins — plugin substrate work (tempdoc 508)

## Closure criteria

1. **AskAiIntent union defined** in `askAi.ts` with three kinds.
2. **askAi function overloaded** — accepts either the typed intent or the
   legacy (question, opts) form. Both produce the same navigate-with-context
   event today, with the `kind` discriminant included in state.
3. **unifiedChatState** carries the optional `kind` field for forward use.
4. **Three existing callsites migrated** to typed intents:
   - `SearchSurface` line 540 → `{kind: 'ask', question, docIds, affordance: 'documents'}`
   - `BrowseSurface` line 478 → `{kind: 'summarize-doc', docId, docName: file.name}`
   - `BrowseSurface` line 480 → `{kind: 'ask-about-doc', question, docId, docName: file.name}`
5. **Unit tests** for askAi: each kind dispatches a navigate-with-context
   event whose detail.state carries the correct kind + fields.
6. **Typecheck clean + unit tests green.**
7. **Live verification**: trigger each kind from its consumer in the dev
   stack, observe the chat surface receives correct state.

## Critical files

- `modules/ui-web/src/shell-v0/utils/askAi.ts`
- `modules/ui-web/src/shell-v0/state/unifiedChatState.ts` (add `kind` field)
- `modules/ui-web/src/shell-v0/views/SearchSurface.ts` (migrate callsite)
- `modules/ui-web/src/shell-v0/views/BrowseSurface.ts` (migrate two callsites)

## Verification commands

- `cd modules/ui-web && npm run typecheck && npm run test:unit:run`
- Live: send a search → click "Ask AI" button → confirm chat surface
  receives `kind: 'ask'` in state. Browse → context-menu summarize a file
  → confirm chat receives `kind: 'summarize-doc'` with docId/docName.
