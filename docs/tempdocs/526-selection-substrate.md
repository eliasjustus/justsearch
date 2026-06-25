---
title: "526 — Selection substrate: typed addressable selections as the universal context-pickup primitive"
type: tempdocs
status: shipped
created: 2026-05-19
category: substrate-design / structural-design
authority: |
  Design theorization of selection as a first-class typed primitive that
  unifies item-selection, text-range, LLM-emitted citation,
  conversation-turn, result-set, and health-condition into one shape;
  flows through the existing IntentSource + ConversationShape + askAi
  systems; and dissolves the F2-first-style half-ships at the source.
  Triggered by the F2-first investigation (slice 486 R3.A entry) which
  surfaced that selection is fragmented across eight independent stores
  with no typed wire entity uniting them. Disregards feasibility and
  implementation specifics per the brief — the goal is the correct
  long-term structure, not a fix.
related:
  - slices/486-consumer-feature-discovery.md R3.A (F2 / F9 entries — the catalog rows this design unblocks)
  - slices/486-consumer-feature-discovery.md R3.D / R3.E (G21 / G25 / G33 / G111 — selection-adjacent consumers)
  - slices/487-intent-substrate.md (IntentSource catalog + trust lattice — ComposeIntent rides this)
  - slices/491-chat-substrate.md (ConversationShape SPI + ContextInjector — SelectionContextInjector is a peer)
  - slices/493-citations-panel.md (DocAccess.startChar/endChar — the half-shipped accept-side that motivates D2)
  - slices/497-unified-chat-surface.md §12.2 + V1.1 ExternalContextInjector (the structural precedent for typed body fields)
  - slices/508-coherent-ai-presence.md §11.2 / §13.2 (selectionState item-union — the substrate this widens)
  - slices/510-ai-aware-shell.md (askAi typed intents — subsumed by ComposeIntent)
  - slices/521-plugin-ecosystem-substrate.md §11.1 / §11.2 (ShellContext + WhenExpression — the host of selection in flat-key form)
  - modules/ui-web/src/shell-v0/state/selectionState.ts (today's item-only selection)
  - modules/ui-web/src/shell-v0/state/shellContextState.ts (flat-key projection)
  - modules/ui-web/src/shell-v0/utils/askAi.ts (today's typed intent helper)
  - modules/app-services/.../conversation/spi/DocAccess.java (today's ad-hoc startChar/endChar reader)
  - modules/app-services/.../conversation/spi/ExternalContextInjector.java (the SPI shape this design generalizes)
  - modules/ui/src/main/java/io/justsearch/ui/api/PreviewController.java (truncation policy that makes coordinate-systems load-bearing)
gates:
  - ratification only — no build/test gates; this is a theory document
  - 528b implementation: build + unit + live-stack + LLM-tier (§13.4) all green 2026-05-20
---

# 526 — Selection substrate: typed addressable selections as the universal context-pickup primitive

## §1 — Why this exists

The F2-first ("summarize selection") FE investigation surfaced a single
defect with broad reach: when the user "selects something" and asks the
LLM to act on it, the codebase has no typed substrate for what was
selected. The backend slice that motivated this investigation (493 §5,
adding `startChar` / `endChar` to `DocAccess`) is half-shipped — the
backend accepts the fields, but no FE pathway sends them, because there
is no typed selection entity to put them in.

This is not an F2-first problem. The same shape repeats across:

- **F2-first** — summarize a text range in a document
- **F9** — selection-context AI panel ("Ask AI" / "Summarize" / "Action items" on selected text)
- **G21** — citation explorer (bidirectional doc↔answer; an LLM-emitted citation is a selection projected back)
- **G25** — "Compare these N documents" (selection over a result-set)
- **G33** — "Why was this slow?" (selection over a search-trace entity)
- **G111** — "Why this result?" (selection over a single search-hit's reasoning trace)
- **F6 / F21** — "Explain this health event / condition" (selection over a HealthCondition)
- **F23** — persistent agent advisor (selection-as-state for what the agent should observe next)

Each of these is currently a separate one-off: a separate intent kind in
`askAi`, a separate body field in some `ContextInjector`, a separate
shape manifest, a separate trigger UI. They share a structural concept —
*"the user has picked out a typed slice of context, and a downstream
LLM workflow should consume it"* — that has no name in the system.

This tempdoc names it, theorizes its shape, and argues that the correct
design closes all eight cases above with a single typed primitive plus
four supporting pieces. The exercise explicitly disregards feasibility
and implementation cost: the goal is the structure that prevents this
class of half-ship from recurring, not the next-week fix.

---

## §2 — What exists today (substrate inventory)

Eight stores carry pieces of the "selection" concept; none of them
recognize each other.

| Substrate | Where | What it carries | What it knows about the others |
|---|---|---|---|
| `selectionState` (slice 508 §11.2) | `modules/ui-web/src/shell-v0/state/selectionState.ts` | Discriminated item-union: `search-hit` / `browse-node` / `plugin-item`. Per-kind capability sets (`open` / `pin` / `ask-ai-about` / `reveal-in-explorer` / `copy-link` / `export`). | Nothing about text-range, citation, conversation-turn, or condition. |
| `inspectorState` | `modules/ui-web/src/shell-v0/state/inspectorState.ts` | Single-item inspector focus (peer of selectionState). | Reads from selectionState via a shim; doesn't know about text-range either. |
| `shellContextState.selectionKind` (slice 508 §11.1) | `modules/ui-web/src/shell-v0/state/shellContextState.ts` | Flat-key projection for `WhenExpression` evaluator: `selectionKind` enum + `selectionCount` + `selectionCapabilities` comma-string. | Same kind union as selectionState; widens together with it. No `selectionAddress`, no per-kind detail. |
| DOM `window.getSelection()` | InspectorPane Preview `<pre>` with `<span data-line>` per line | Untyped browser `Selection` + `Range`. Transient. Lost on blur. | Nothing — never enters a store. |
| `RAGCitation` / `CitationMatch` (slice 493) | `components/chat/CitationsPanel.ts`, `api/streams.ts`, `api/generated/wire-types.ts` | LLM-emitted text range: `parentDocId`, `chunkIndex`, `startChar`, `endChar`, `startLine`, `endLine`, `score`, `excerpt`. | Renders highlights via `InspectorPane.highlightCitation(startLine, endLine)`. Cannot be promoted to "active selection" — it's a projection, not a state. |
| `unifiedChatState.pinnedDocIds` / `summarizeChatState.docId` / `summarizeChatState.docIds` | per-surface state stores | Doc-set as a bag; not modeled as selection. | Read by SummarizeView's `connectedCallback`; populated by `askAi summarize-doc` via navigation state. Parallel universe from selectionState. |
| `DocAccess.startChar` / `DocAccess.endChar` (slice 493 §5) | `modules/app-services/.../conversation/spi/DocAccess.java` | Backend reads two optional ints from the request body; slices `fullContent` to the range; emits a `ContextCitation` covering the slice. | Typed nowhere on the wire. The contract is "if both ints are present and valid, use them" — purely positional. |
| `askAi` typed intents (slice 510 / slice 514) | `modules/ui-web/src/shell-v0/utils/askAi.ts` | Discriminated union: `ask` / `summarize-doc` / `ask-about-doc`. Each maps to a navigation-state payload (query + affordance + docIds). | Doesn't model text-range, citation, or condition. Routes through UnifiedChatView's `affordanceToShape` (no `core.summarize` path). |

Consumer behavior, briefly:

- **BrowseSurface** context-menu actions (`Summarize`, `Ask about this`) dispatch via `askAi({ kind: 'summarize-doc', docId })` → UnifiedChatView → RAG-Ask shape (**not** SummarizeShape). The full-doc summarize today goes through RAG-Ask with a templated query, not through `core.summarize`. There are two distinct "summarize" backends in use simultaneously.
- **SummarizeView** is a standalone surface (`core.summarize-surface`) reachable via direct navigation. It reads from `summarizeChatState` on mount and POSTs to `/api/chat/summarize`. The backend selection-range support (slice 493 §5) lives on `core.summarize` — i.e., on a path BrowseSurface's "Summarize" action does not exercise.
- **InspectorPane** renders preview text from `/api/preview` (default 20K chars, capped at 200K via `maxChars` query param). The rendered `<pre>` has line-addressable `<span data-line="N">` children. `highlightCitation(startLine, endLine)` is the only consumer; `clearHighlight()` is the only mutator. **There is no text-selection event handler anywhere in the component.**
- **`CitationsPanel` / `<jf-cite-ref>`** emit `cite-ref-click` events (handled by Shell via slice 508's citation interaction chain) which bridge to `citation-select` and ultimately to `InspectorPane.highlightCitation`. The flow is read-only: a citation never becomes an active selection that the user could re-ask about.

The diagnosis is structural: **the system has selection-shaped activity
in eight places and a selection-shaped substrate in zero.**

---

## §3 — Diagnosis: three structural defects

### §3.1 — D1: Selection has four axes; only two are typed

Selection has four orthogonal dimensions:

1. **Kind** — what is the selected thing? (item, text-range, citation, condition, conversation-turn, result-set)
2. **Address** — how is the slice within its host pinpointed? (item id, char range, line range, message id, hit-set ids)
3. **Capability** — what can be done with it? (open, pin, copy, ask-ai-about, export, summarize, explain)
4. **Operation** — when the user invokes "ask-ai-about" on a text-range, which prompt / context-injector / shape combination runs?

Today only axes 1 and 3 are typed (and only for the three item kinds).
Axes 2 and 4 live in ad-hoc per-consumer code: `DocAccess` reads
`startChar` / `endChar` positionally; `askAi.renderIntent` hard-codes
the `summarize-doc → RAG-Ask + 'Summarize ${docName}'` mapping; the
trigger UI in BrowseSurface's context menu hand-codes its operation
list per surface.

The consequence: every new (Kind, Operation) pair is a new ad-hoc
wiring. F2-first today is one such pair (`text-range`, `summarize`)
with no place to live; F9 is six such pairs collapsed into "selection
context AI panel"; G21 is the `citation → ask-about` pair that doesn't
exist as a path at all.

### §3.2 — D2: Coordinate-system conflation

`/api/preview` is *capable* of returning up to 200K characters (default
20K) under a `truncated` flag (`PreviewController.java:32`
`DEFAULT_MAX_CHARS = 20_000`). **InspectorPane hard-codes
`maxChars=5000`** at the call site (`InspectorPane.ts:306` —
verified 2026-05-19), so every preview the user sees is truncated to
the first 5,000 characters of the canonical content. The FE renders
this as `<pre>` text and obtains DOM selection offsets against the
rendered text. The backend `DocAccess` reads `startChar` / `endChar`
and substrings *the full document* by those offsets:

```java
String content = hasSelection ? fullContent.substring(startChar, endChar) : fullContent;
```

There is no name in the type system for "which content do these offsets
address?" — the contract is implicit and silently wrong whenever the
preview is truncated. Because InspectorPane's hard-coded 5K window is
shorter than most documents users interact with, this is not a
hypothetical edge case: it is the **dominant** case. The user's DOM
selection offsets are positions within the 5K-truncated preview; the
backend will substring the canonical content by those same integers;
the substring may or may not be what the user saw, with no error and
no flag at the boundary.

This is not a preview bug. The preview is doing its job (give the FE
text to render). The defect is the absence of a **DocumentAddress**
primitive that distinguishes:

- *Display coords* — addresses within whatever truncated / paginated /
  formatted view the user interacted with
- *Canonical coords* — addresses within the document the LLM injector
  reads

A typed selection must carry the address it actually means and the
coordinate system it belongs to. Resolving display→canonical is then a
named operation, not an implicit one.

The same conflation will recur for:
- Search-snippet selections (snippets are formatted excerpts; offsets ≠ canonical)
- Markdown-rendered previews (rendered HTML char offsets ≠ source markdown offsets)
- Code with syntax highlighting (display tokens ≠ source bytes)
- Paginated previews (page N coordinates ≠ document coordinates)

Each of these is a future preview format; each will need the same
disambiguation. The substrate fix is one type, applied once.

### §3.3 — D3: Capability vocabulary is too vague to dispatch

Today's capability strings (`ask-ai-about`, `open`, `pin`, `export`,
`copy-link`, `reveal-in-explorer`) tell the system "this selection
*can* be asked about." They do **not** tell the system:

- Which prompt to use (a summarize prompt, an explain prompt, a comparison prompt?)
- Which context injector to run (DocAccess? RAGContext? a future HealthEventInjector?)
- Which conversation shape to dispatch to (RAGAskShape? SummarizeShape? FreeChatShape? a new ExplainShape?)
- Which trust gate to apply (`ConfirmStrategy.AUTO_APPROVE`? `INLINE_CONFIRM`? `TYPED_CONFIRM`?)

The shape resolution lives ad-hoc:

- `askAi.renderIntent` hard-codes `summarize-doc → 'documents' affordance → RAG-Ask`
- BrowseSurface picks the operation per context-menu item id
- SummarizeView is reached by direct navigation, bypassing the above
- InspectorPane's "Ask" tab posts directly to `/api/agent/run/stream`

There is no registry where the answer to "what operations apply to a
`text-range` selection in a `search-hit` host?" is *data*, lookupable
by `(kind, host)` and projecting into all presentation contexts
(floating menu, context menu, command palette, keyboard shortcut)
uniformly. This is the defect that makes F9 a paragraph in 486's R3.A
instead of a wire-up: F9's "typed actions menu" is conceptually the
projection of this registry into a floating-near-selection
presentation context.

---

## §4 — The correct design — five primitives

The argument: dissolving D1–D3 takes five typed primitives, each load-bearing.
Skipping any one re-introduces the structural defect at a different layer.

### §4.1 — `SelectionPayload` — typed discriminated union

The wire-and-store type that "what is selected" finally has. Subsumes
today's `SelectionItem` and widens to cover the missing kinds:

```
SelectionPayload =
  | { kind: 'item'; itemKind: 'search-hit' | 'browse-node' | 'plugin-item';
      itemId: string; surfaceId: SurfaceId; capabilities: Set<Capability>;
      payload?: unknown /* plugin-defined per itemKind */ }
  | { kind: 'text-range'; address: DocumentAddress; selectionText: string;
      hostEntity: { kind: 'doc' | 'snippet' | 'message' | 'log-entry'; id: string };
      capabilities: Set<Capability> }
  | { kind: 'citation'; citation: SourceCitation /* slice 493 sealed sum */;
      promotedFrom: 'rag-stream' | 'manual'; capabilities: Set<Capability> }
  | { kind: 'conversation-turn'; sessionId: string; messageId: string;
      role: 'user' | 'assistant' | 'system'; capabilities: Set<Capability> }
  | { kind: 'result-set'; items: ReadonlyArray<{ id: string; kind: 'doc' | 'hit' }>;
      query?: string; capabilities: Set<Capability> }
  | { kind: 'health-condition'; conditionId: string; severity: Severity;
      capabilities: Set<Capability> }
```

Three properties this gives:

1. **Type-system enforcement of kind-specific fields.** A consumer
   handling `text-range` knows it gets a `DocumentAddress` and
   `selectionText`; never has to thread `unknown` through `as
   Record<string, unknown>`.

2. **Wire shape, not just FE shape.** `SelectionPayload` is JSON-serializable
   verbatim. The same shape rides in request bodies (`body.selection`),
   in URL state (URL-projector encodes the kind + a per-kind compact
   form), in agent-loop intent envelopes (so the LLM can request a
   selection programmatically), and in the FE store.

3. **Symmetric with citation.** A `citation` kind that wraps the
   existing citation record means citations and selections are peers
   in the same union — promoting a citation to an active selection is
   a kind-flip, not a translation. The bidirectional doc↔answer
   pattern G21 wants becomes structural, not a UX hack. **Caveat
   (verified 2026-05-19)**: this design as originally drafted assumed
   slice 493's *proposed* `SourceCitation` sealed sum (`CharLocation` /
   `PageLocation` / `ContentBlockLocation` / `SearchResultLocation` /
   `UnknownLocation`) was shipped. It was **not**. Today the backend
   has a single flat `ContextCitation` record
   (`DocumentService.java:210-235`; 11 fields: `parentDocId`,
   `chunkIndex`, `chunkTotal`, `startChar`, `endChar`, `score`,
   `excerpt`, `startLine`, `endLine`, `headingText`, `headingLevel`).
   The slice 493 sealed-sum framing was design rationale that never
   made it into code. This means: the `citation` variant of
   `SelectionPayload` can be designed cleanly against the flat
   `ContextCitation`, **or** the implementing slice can ratify the
   slice 493 sealed sum first (it's a worthwhile orthogonal cleanup,
   but it's a prerequisite, not a free lunch).

`SelectionPayload` deprecates `selectionState`'s `SelectionItem` (the
item variants migrate verbatim) and absorbs `unifiedChatState.pinnedDocIds`
(a `result-set` of `kind: 'doc'`) and `summarizeChatState.docId/docIds`
(same).

### §4.2 — `DocumentAddress` — the coordinate-system primitive

The type that makes D2 impossible:

```
DocumentAddress =
  | { coords: 'canonical'; docId: string; startChar: number; endChar: number }
  | { coords: 'display'; docId: string; viewId: ViewFormat;
      displayStart: number; displayEnd: number;
      canonicalHint?: { startChar: number; endChar: number } /* if known */ }
  | { coords: 'lines'; docId: string; startLine: number; endLine: number
      /* the dominant pre-491 form; preserved for `highlightCitation` */ }
  | { coords: 'opaque'; docId: string; addressBlob: string
      /* for future formats: page+offset PDFs, AST paths in code, etc. */ }

ViewFormat = 'preview-20k' | 'preview-full' | 'snippet-rag' | 'markdown-rendered' | 'paginated-page'
```

A `SelectionPayload.kind === 'text-range'` always carries one of these.
The backend's `SelectionContextInjector` (§4.4) declares which coord
systems it accepts; resolution display→canonical is a named operation
(`/api/document/{id}/resolve-address?...`) when the FE doesn't have
enough information locally.

Three properties:

1. **Truncation becomes explicit.** A selection in a `preview-20k`
   coordinate system *is* selection within the truncated content. The
   backend, given that view-id, knows to either expand the view or
   reject the request with `unresolvableAddress`. Silent wrongness
   becomes loud error.

2. **Future preview formats compose.** Markdown-rendered, paginated,
   syntax-highlighted, AST-rooted code — each is a new `ViewFormat`
   value. The display→canonical mapping is per-format; the
   `SelectionPayload` consumer doesn't change.

3. **Citation-rendering uses the same type.** `highlightCitation(startLine,
   endLine)` is `coords: 'lines'`. The `ContextCitation` record's
   `(startChar, endChar)` + `(startLine, endLine)` pair captures both
   canonical-char and line-based addresses in one flat record;
   `DocumentAddress` names this duality as a typed union rather than a
   flat record with two coordinate systems coexisting. **Correction
   2026-05-19**: an earlier draft claimed slice 493's `SourceCitation`
   sealed sum was the pre-existing instance of this pattern — that
   sealed sum doesn't exist in code. The pattern `DocumentAddress`
   names is real (line-coords and char-coords coexist on
   `ContextCitation`), but the typed-union expression of it is
   genuinely new.

### §4.3 — `SelectionActions` registry — what can be done

A contribution registry — peer of slice 521's command registry and
slice 491's ConversationShape catalog — answering one question:
*"given the current `SelectionPayload`, which operations are
applicable, and where do they render?"*

```
SelectionActionEntry = {
  id: ActionId;             /* e.g. 'core.selection.summarize-text-range' */
  appliesTo: WhenExpression;  /* e.g. 'selectionKind == text-range && selectionTextLength > 0' */
  operation: OperationId;   /* the Operation registry id slice 487/521 already ratified */
  presentation: {
    floating?: { label: string; icon?: string; priority: number };
    contextMenu?: { label: string; icon?: string; category: string };
    palette?: { label: string; keywords: string[]; description: string };
    keyboard?: { binding: string; when: WhenExpression };
    askAiButton?: { label: string; visible: WhenExpression };
  };
  trustGate?: GateBehavior;  /* slice 487 trust lattice; default = inherit from Operation */
}
```

Four properties:

1. **Single source of truth for "selection menu" content.** Today F9's
   typed-actions menu is a hypothetical Lit component; the right
   structure is *the registry IS the menu's data*, the Lit component
   is just one presentation projection.

2. **`when`-expression gating composes with `ShellContext`.** Slice 521
   §11.1 ratified the `WhenExpression` evaluator with flat-key access
   to `shellContextState`. Adding `selectionKind == text-range`,
   `selectionTextLength > 0`, `selectionEntityKind == doc`,
   `selectionAddressCoords == canonical` etc. as flat keys gives the
   registry's `appliesTo` field free composition with surface state,
   palette state, capability gates, audience, AI availability — all the
   filters that already work for commands.

3. **Plugin extension by construction.** Plugins contribute
   `SelectionActionEntry` rows the same way slice 521 lets them
   contribute commands and UI slots. A plugin-authored selection kind
   (a new variant in the SelectionPayload union — via the registered
   plugin Kind contribution) can register its own actions without
   touching core. Verified 2026-05-19: slice 521 actually shipped
   four parallel bespoke registries (`CommandRegistry`,
   `StatusBarRegistry`, `ContextActionRegistry`, `KeybindingRegistry`,
   plus `InspectorTabRegistry`) under
   `modules/ui-web/src/shell-v0/commands/`. Each has identical
   surface (`register<X>`, `unregister<X>`, `list<X>`, `on<X>Change`,
   `__resetForTest`) but no shared base abstraction. A
   `SelectionActionRegistry` would be a fifth bespoke module
   following the same template — no refactor of existing registries
   required.

4. **Capability vocabulary is derived, not authored.** The flat-key
   `selectionCapabilities` comma-string today is hand-authored on each
   `SelectionItem`. With the registry, capabilities project from "the
   set of action IDs whose `appliesTo` returns true on this selection."
   The string is computed, not stored. Drift between "capability
   declared on the item" and "action actually applicable" becomes
   impossible.

### §4.4 — `SelectionContextInjector` (backend SPI)

Peer of `ExternalContextInjector` (slice 497 V1.1). Verified 2026-05-19:
the `ContextInjector` SPI (`modules/app-agent-api/.../conversation/ContextInjector.java:38`)
exposes one method `InjectorResult inject(ConversationContext ctx)`;
`InjectorResult` factories (`InjectorResult.java:38-64`) are
`empty()`, `messagesOnly(List<Map<String, Object>>)`,
`of(List, List<SseEvent>)`, `terminalError(SseEvent)`;
`ConversationContext` (`ConversationContext.java:23-75`) exposes
`messages()`, `iteration()`, `audience()`, `sessionId()`,
`requestBody()`, `attributes()`. The SelectionContextInjector sketch
below uses these verbatim. The context-injector SPI grows one new
built-in:

```java
public final class SelectionContextInjector implements ContextInjector {
  public static final String ID = "core.selection";

  @Override public InjectorResult inject(ConversationContext ctx) {
    var raw = ctx.requestBody().get("selection");
    if (raw == null) return InjectorResult.empty();
    var payload = SelectionPayloadCodec.decode(raw);   /* typed */
    return switch (payload.kind()) {
      case TEXT_RANGE  -> injectTextRange(ctx, payload.asTextRange());
      case CITATION    -> injectCitation(ctx, payload.asCitation());
      case CONVERSATION_TURN -> injectConversationTurn(ctx, payload.asConversationTurn());
      case RESULT_SET  -> injectResultSet(ctx, payload.asResultSet());
      case HEALTH_CONDITION -> injectHealthCondition(ctx, payload.asCondition());
      case ITEM        -> injectItem(ctx, payload.asItem());
    };
  }
}
```

Each shape that wants selection context declares
`selectionPolicy: REQUIRED | OPTIONAL | IGNORED` in its manifest
(slice 491 ConversationShape SPI extension). Verified 2026-05-19: the
`ConversationShape` type is a record with 12 fields today (`id`,
`presentation`, `audience`, `provenance`, `executionMode`,
`iterationMode`, `persistenceMode`, `promptContributorIds`,
`contextInjectorIds`, `streamConsumerIds`, `iterationControllerId`,
`eventSchema`); adding `selectionPolicy` as a 13th field is additive
and matches the existing pattern. **`SummarizeShape` already wires
`DocAccess.ID`** (`SummarizeShape.java:59` —
`List.of(DocAccess.ID)`), confirming the F2-first backend half is
genuinely shipped through the SummarizeShape injector chain. The
engine validates the incoming `body.selection` against the declared
policy at dispatch time.

Six properties:

1. **`DocAccess`'s ad-hoc `startChar` / `endChar` read is deleted.**
   The selection is typed at the SPI boundary; positional ints disappear.

2. **One injector covers every shape that wants selection.** F2-first's
   "summarize selection" stops being a `core.summarize`-specific patch
   and becomes "any shape with `selectionPolicy: OPTIONAL` and the
   `core.selection` injector in its chain gets selection context for
   free." This includes future shapes the team hasn't designed yet.

3. **The injector knows the address coordinate system.** Resolving
   display→canonical happens here, with access to the document's
   canonical content and any view-format-specific translation tables.
   The FE submits whatever coords it had; the injector lifts to
   canonical or fails loudly.

4. **Symmetric with `ExternalContextInjector`.** Slice 497 V1.1 made
   "prior conversation messages" a typed channel (`body.context`,
   `core.external-context`). This makes "selected slice of context" a
   peer typed channel (`body.selection`, `core.selection`). The two
   are distinct because their semantics are distinct — bundling them
   into a single "context bag" overloads one field with two meanings.

5. **The injector emits a `ContextCitation`.** Already true of DocAccess
   today; preserved here. The selection is both *input to the prompt*
   (its text slice becomes message content) and *output to the FE*
   (the cited passage so the user sees what the LLM was told). This
   symmetry is structural; the citation substrate (slice 493) gets a
   second producer without code changes.

6. **Shape `selectionPolicy: REQUIRED` enables shapes that fail
   loudly without a selection.** A future `ExplainSelectionShape` or
   `CompareSelectionsShape` declares REQUIRED and the engine returns
   a typed `missingSelection` error instead of silently summarizing
   the wrong thing.

### §4.5 — `ComposeIntent` — the universal dispatch envelope

The intent type that subsumes `AskAiIntent` and unifies the
askAi-vs-direct-navigate fork:

```
ComposeIntent = {
  selection?: SelectionPayload;
  operation: OperationId;     /* e.g. 'core.summarize', 'core.explain', 'core.compare', 'core.ask' */
  targetShape?: ShapeId;      /* optional override; default resolved by (operation, selection.kind) */
  userPrompt?: string;        /* free-text question / instruction the user added */
  source: IntentSource;       /* slice 487 trust lattice: PALETTE, SHORTCUT, CONTEXT_MENU, FLOATING_MENU, … */
}
```

Four properties:

1. **The router resolves `(operation, selection.kind) → ShapeId`.**
   When `targetShape` is unset, a single
   `SelectionOperationDispatcher.resolveShape(operation, selection.kind)`
   table maps the pair to a shape:

   | Operation | Selection kind | Shape |
   |---|---|---|
   | `core.summarize` | `text-range` | `core.summarize` (with selection injected) |
   | `core.summarize` | `item` (doc) | `core.summarize` |
   | `core.summarize` | `result-set` | `core.batch-summarize` or `core.hierarchical-summarize` (by count) |
   | `core.ask` | `text-range` | `core.rag-ask` (with selection narrowing) |
   | `core.ask` | `result-set` | `core.rag-ask` (docIds scoped) |
   | `core.ask` | `conversation-turn` | `core.free-chat` (continue thread) |
   | `core.explain` | `health-condition` | `core.explain-health` (future shape) |
   | `core.explain` | `citation` | `core.rag-ask` (with citation context) |
   | `core.compare` | `result-set` | `core.compare-docs` (future shape) |

   This table is **data**, not code. New (operation, kind) pairs add
   rows; new shapes register handlers; no callsite changes.

2. **`askAi` collapses to a thin sugar.** The current
   `askAi({ kind: 'summarize-doc', docId })` becomes:
   `compose({ operation: 'core.summarize', selection: { kind: 'item',
   itemKind: 'browse-node', itemId: docId, … } })`. The discriminator
   that was the `AskAiIntent.kind` (`ask` / `summarize-doc` /
   `ask-about-doc`) is now `operation` + `selection`, factored apart.

3. **ComposeIntent flows through the existing `IntentRouter` (slice 487).**
   It is a new `transport: COMPOSE` value in the IntentSource catalog,
   evaluated by the same `CoreTrustEvaluator` against the same
   `SourceTier × RiskTier → GateBehavior` trust lattice. No new router,
   no new audit log, no new SSE wire.

4. **The intent envelope is observable.** The same `IntentRouter.subscribe`
   surface that powers slice 487's intent stream now sees Compose
   dispatches. G121 (Intent stream DevTools panel) gets selection-driven
   actions in its timeline for free.

---

## §5 — Integration with existing substrate

This design extends, rather than replaces, every shipped substrate it
touches. Each integration is a typed widening; none requires deletion.

- **Slice 487 IntentSource** — `ComposeIntent` adds `COMPOSE` to the
  `TransportTag` enum. Verified 2026-05-19 against
  `modules/app-agent-api/.../registry/TransportTag.java:35-72` — the
  enum is a plain Java enum (not sealed), explicitly designed for
  additive extension per its docstring: *"new transports add by
  appending an enum value."* Current values: `URL_BAR`, `URL_DEEPLINK`,
  `LLM_EMISSION`, `PALETTE`, `BUTTON`, `RAIL`, `AGENT_LOOP`, `MCP`,
  `PLUGIN_EMITTED`, `SCHEDULED`, `RULE_ENGINE`, `SYSTEM_INTERNAL`. The
  trust evaluator (`CoreTrustEvaluator.java:65-86`) takes `(SourceTier,
  RiskTier) → GateBehavior` where `GateBehavior ∈ {AUTO,
  INLINE_CONFIRM, TYPED_CONFIRM, DENY}`; the lattice matrix is
  hard-coded and produces no DENY cells in v1. A COMPOSE row gets
  default mid-trust (FLOATING_MENU and PALETTE are user-driven, so
  SourceTier ≈ USER).

- **Slice 491 ConversationShape SPI** — shapes gain a manifest field
  `selectionPolicy: REQUIRED | OPTIONAL | IGNORED`. The
  `SelectionContextInjector` is a new built-in
  `ContextInjector` registered alongside `RAGContext`, `DocAccess`
  (which loses its `startChar`/`endChar` reads — that responsibility
  moves to `SelectionContextInjector`), `UserPromptInjector`,
  `ExternalContextInjector`.

- **Slice 493 Citation substrate** — `SourceCitation` becomes a
  `SelectionPayload.kind === 'citation'` variant. The bidirectional
  doc↔answer pattern G21 wants is enabled: clicking a citation
  promotes it to the active selection (kind-flip), the user can then
  invoke any selection action on it (summarize the cited range, ask
  about it, compare with another citation). `CitationsPanel` stays as
  the rendering surface; its `cite-ref-click` event becomes a
  citation-promotion intent rather than a one-shot highlight.

- **Slice 497 V1.1 ExternalContextInjector** — peer; the design is
  consciously modeled on it. Selection is its own typed channel
  (`body.selection`), distinct from conversation history
  (`body.context`). A consumer can use both: an in-thread follow-up
  asking about a newly-selected range carries both the history (last
  N messages) and the new selection.

- **Slice 508 `selectionState` + `shellContextState`** — `selectionState`
  widens its discriminator union to include `text-range`, `citation`,
  `conversation-turn`, `result-set`, `health-condition`. Slice 508 §13.2
  ratified "kind union is demand-driven"; this is the demand. The
  flat-key projection in `shellContextState` gains
  `selectionAddress` (the address coordinate system),
  `selectionEntityKind`, `selectionTextLength`, and per-action capability
  flags derived from the SelectionActions registry.

- **Slice 510 `askAi`** — call-site sugar over Compose. Today's
  `AskAiIntent` discriminators (`ask` / `summarize-doc` /
  `ask-about-doc`) keep working — they translate to `ComposeIntent`s
  at the call site. New call sites use `compose` directly. The
  migration is gradual; no big-bang.

- **Slice 521 PluginHostApi** — selection becomes a Host API
  sub-interface:
  - `host.selection.read()` — get the current `SelectionPayload`
  - `host.selection.subscribe(listener)` — listen for changes
  - `host.selection.set(payload)` — set programmatically (e.g., for a plugin's own selection kind)
  - `host.selection.actions(payload)` — query the registry
  - `host.selection.compose(intent)` — dispatch a ComposeIntent
  Trust attenuation per slice 521 §3.4: low-trust plugins get read-only;
  mid-trust gets set; high-trust gets compose.

- **Slice 521 ShellContext + WhenExpression** — the new flat keys
  derived above (`selectionEntityKind`, `selectionAddressCoords`,
  `selectionTextLength`, etc.) become available to every
  WhenExpression in the system: commands, keybindings, UI slots, menu
  items. Verified 2026-05-19 against
  `modules/ui-web/src/shell-v0/commands/whenExpression.ts:3-32` — the
  grammar supports `==`, `!=`, `===`, `!==`, `>`, `>=`, `<`, `<=`,
  `=~`, `in`, `not in`, `&&`, `||`, `!`; identifiers are flat keys
  with `.` and `-` allowed (because surface ids like
  `core.search-surface` need them). Proposed expressions
  (`selectionKind == text-range`, `selectionTextLength > 0`,
  `selectionAddressCoords == canonical`) parse as-is. The only work
  is adding the new fields to the `ShellContext` record itself; no
  grammar changes.

---

## §6 — What this dissolves

A complete inventory of catalog and substrate problems this design
collapses:

| Today | After |
|---|---|
| **F2-first half-ship** — backend accepts `startChar`/`endChar`; FE has no typed selection to send. | Backend reads typed `body.selection` via `SelectionContextInjector`; FE constructs `SelectionPayload.text-range` and dispatches `ComposeIntent`. No "half" anywhere. |
| **F9 "selection-context AI panel" is a paragraph in 486 R3.A** with no substrate. | F9 *is* the SelectionActions registry rendered into the floating-menu presentation context. The "panel" is one Lit component, the actions are data. |
| **Preview-truncation gotcha** — selection beyond the truncation window silently sends wrong offsets. | `DocumentAddress` with `coords: 'display' / 'preview-20k'` makes the truncation window explicit; the backend either resolves display→canonical or returns `unresolvableAddress`. Silent wrongness becomes loud error. |
| **askAi-vs-direct-route fork** — BrowseSurface's "Summarize" routes to RAG-Ask; direct navigation to SummarizeView uses `core.summarize`. Two backends for one user-visible action. **Verified 2026-05-19 end-to-end**: BrowseSurface.ts:463 → askAi.ts:90-94 (`summarize-doc` → affordance `documents`) → Shell.ts:481 listens `navigate-with-context` → UnifiedChatView.ts:85-94 `affordanceToShape('documents')` returns `core.rag-ask` → ChatController.java:74-94 dispatches by body.shapeId. SummarizeShape is not reached. | `ComposeIntent { operation: 'core.summarize', selection: { kind: 'item', … } }` resolves via the (operation, kind) table. One path, one resolution rule. |
| **Citation-as-selection asymmetry** — citations are LLM-produced, can be highlighted but never promoted to a new question. | `SelectionPayload.kind === 'citation'` is a peer variant; kind-flip to `text-range` is one method call. G21 falls out. |
| **F21 / F6 / G33 selection-on-non-document entities** — each invents its own injection mechanism (HealthEventInjector, trace-injector, etc.). | `SelectionPayload.kind === 'health-condition'` etc.; `SelectionContextInjector` dispatches per-kind injection. New kinds add a `case`, not a new SPI. |
| **`unifiedChatState.pinnedDocIds` is a parallel "selection-like" bag.** | `SelectionPayload.kind === 'result-set'` absorbs it. The UnifiedChatView reads the selection store instead. |
| **`summarizeChatState.docId/docIds/shapeId` is a parallel "pre-fill" bag.** | Pre-fill becomes "the current selection at navigation time"; the SummarizeView reads `selectionState` (now widened). No surface-specific pre-fill state. |
| **`selectionCapabilities` comma-string drifts from actual applicable operations.** | Capability string projects from the SelectionActions registry. Drift is impossible — the string is a computation, not stored data. |
| **`DocAccess.startChar`/`endChar` read is the only place these fields live as a typed concept.** | The concept lives in `DocumentAddress`; `DocAccess` becomes one consumer of it (via SelectionContextInjector), among many. |

---

## §7 — What this does not solve (explicit scoping)

The substrate is bounded. Things the design intentionally leaves open:

1. **Cross-document selection** — a selection spanning two documents
   (e.g., "compare these passages"). Today's `SelectionPayload.text-range`
   is single-doc. A `kind: 'multi-doc-range'` variant could be added
   later if a consumer demands it; out of scope here.

2. **Real-time collaborative selection** — multi-user pointers /
   shared cursors. Not in JustSearch's threat model today; out of
   scope.

3. **Selection persistence beyond session** — selection remains
   ephemeral (matching slice 508 §13.2's "cleared on surface change"
   default). A `'pinned-selection'` extension on `UserStateDocument`
   could be added later; the substrate doesn't preclude it.

4. **Plugin-authored selection kinds** — *within scope* (the kind
   union is extensible by plugin contribution), but the bundling /
   trust / migration model for plugin-defined kinds is its own slice.
   This doc names the extension point; the slice that operationalizes
   plugin kinds picks up from there.

5. **Backwards-compatible wire migration** — this doc disregards
   feasibility per the brief. The actual migration path (today's
   `body.startChar`/`endChar` keeps working for one release while
   `body.selection` rolls out; old `AskAiIntent` calls translate to
   `ComposeIntent` transparently; `selectionState`'s old shape lives
   as a sugar over the new shape; etc.) is a future slice's burden.

6. **Voice / gesture / OS-deeplink intent sources** — slice 487 named
   these as forward-compat IntentSource categories. ComposeIntent
   inherits the same forward-compat: a voice command "summarize this"
   becomes a ComposeIntent with `source: VOICE` and a selection
   resolved from the voice context. The substrate doesn't change.

7. **LLM-emitted selections beyond citations** — the LLM today emits
   citations; in the future it might emit "I'd like to read this
   range next" or "pin this for comparison." `SelectionPayload` as a
   wire type means the agent loop can construct selections
   programmatically; the agent-tool exposure of `host.selection.set`
   is a separate slice that picks this up if/when needed.

---

## §8 — Open user-decision questions

Questions this design surfaces but does not pre-answer:

1. **Does `ComposeIntent` replace `askAi` at call sites, or does
   `askAi` stay as a permanent sugar?** Replacing makes the substrate
   more uniform; staying makes migration cheaper and call-site code
   more readable.

2. **Is `DocumentAddress` a new typed wire field, or does it ride
   inside `SelectionPayload`?** As a peer wire field, it's reusable
   by non-selection consumers (citations, agent-emitted navigation
   targets, etc.); embedded in SelectionPayload, the type system
   enforces it's always paired with a selection-driven action.

3. **Plugin trust model for selection.** Read-only by default seems
   right; what's the escalation pattern for plugins that legitimately
   need to mutate the active selection (a "select all matching" plugin,
   e.g.)?

4. **Citation→selection promotion UX — implicit or explicit?**
   Implicit: clicking a citation makes it the active selection. Pro:
   feels natural for follow-ups. Con: every citation click changes
   what subsequent "Ask AI" operates on, which can be confusing.
   Explicit: a "Use as selection" button on the citation. Pro:
   user-controlled. Con: extra interaction.

5. **Does `selectionPolicy: REQUIRED` exist, or do REQUIRED shapes
   simply error on missing selection in their injector?** The former
   is more discoverable (schema-level); the latter is simpler.

6. **Granularity of the SelectionActions registry — per-action
   contribution, or per-(kind, operation) table?** Per-action is more
   flexible; per-(kind, operation) table is more auditable.

---

## §9 — Substrate-shape implication entry for slice 486 R5.5

When/if this design is ratified, slice 486's R5.5 (substrate-shape
implications) gains a Round-9 entry:

> **#50. Selection substrate — typed addressable selections + DocumentAddress + SelectionActions registry + ComposeIntent + SelectionContextInjector (tempdoc 526).** Five primitives that unify item-selection, text-range, LLM-emitted citation, conversation-turn, result-set, and health-condition into one typed primitive flowing through the existing IntentSource + ConversationShape + askAi systems. Closes the F2-first / F9 / G21 / G25 / G33 / G111 / F21 / F6 substrate gap as a *single* design rather than per-feature wiring. Enables future kinds (paginated PDFs, code AST paths, plugin-authored selections) as kind-union extensions. Pairs with slice 493's citation substrate (citations become a selection kind) and slice 521's ShellContext + WhenExpression (selection flat-keys gate every command).

---

## §10 — Net summary

JustSearch has a substrate-shaped concept living in eight independent
stores with no name. The visible symptom is the F2-first half-ship;
the underlying defect is that "selection" was never modeled as a
substrate. Five typed primitives — `SelectionPayload`, `DocumentAddress`,
`SelectionActions`, `SelectionContextInjector`, `ComposeIntent` — name
the shape, give it a wire representation, route it through the
existing IntentSource + ConversationShape + askAi systems, and dissolve
the half-ship at the source.

This is a structural argument, not a feature proposal. The cost of the
five primitives is paid once; the cost of the per-feature wirings
(F2-first, F9, G21, G25, G33, G111, F21, F6, F23, plus every future
selection-driven consumer that hasn't been designed yet) is paid
n times forever. The substrate-discipline gates that slice 480 §3.1
and slice 486 R4.4 ratified (Pass 6/7/8 forbids substrate-without-consumer)
are satisfied here by *naming the consumers up-front* — every primitive
above carries the concrete F/G IDs that exercise it.

If ratified, the next slice is the substrate-shape design brief:
which of the five primitives ships first, which are deferred, how the
migration interleaves with shipped consumers (askAi, DocAccess,
selectionState). If not ratified, the document remains as the
canonical reference case for "what should have been done before
F2-first was scoped half-way."

---

## §11 — Confidence audit (2026-05-19)

After the initial draft of §1–§10 was completed, a confidence-building
investigation pass verified the load-bearing claims against actual
code (three parallel Explore subagents, no live dev stack required).
This section records the result so the implementing slice can pick up
without re-deriving the same evidence.

### §11.1 — Verified claims (with citations)

| Claim | Verified at | Section affected |
|---|---|---|
| `SummarizeShape` wires `DocAccess.ID` in its injector chain (F2-first backend is genuinely shipped) | `modules/app-services/.../conversation/shapes/SummarizeShape.java:59` — `List.of(DocAccess.ID)` | §1 trigger; §4.4 |
| `ConversationShape` is a record with 12 fields; `selectionPolicy` as a 13th field is additive | `modules/app-agent-api/.../conversation/ConversationShape.java` — record at line 67, 12 fields | §4.4 |
| `ContextInjector` SPI signature: `InjectorResult inject(ConversationContext ctx)` | `ContextInjector.java:38` | §4.4 sketch |
| `InjectorResult` factories: `empty()`, `messagesOnly()`, `of()`, `terminalError()` | `InjectorResult.java:38-64` | §4.4 sketch |
| `ConversationContext` exposes `messages / iteration / audience / sessionId / requestBody / attributes` | `ConversationContext.java:23-75` | §4.4 sketch |
| BrowseSurface "Summarize" routes to `core.rag-ask`, NOT `core.summarize` (the "two backends, one action" claim) | BrowseSurface.ts:463 → askAi.ts:90-94 → Shell.ts:481 → UnifiedChatView.ts:85-94 (`affordanceToShape('documents') → 'core.rag-ask'`) → ChatController.java:74-94 (`/api/chat/dispatch`) | §6 dissolves-table |
| `TransportTag` is an open Java enum (not sealed), explicitly designed for additive extension; adding `COMPOSE` is supported by the type's published contract | `modules/app-agent-api/.../registry/TransportTag.java:35-72` — docstring: *"new transports add by appending an enum value"* | §4.5 + §5 |
| `CoreTrustEvaluator` signature: `GateBehavior evaluate(SourceTier, RiskTier)`; `GateBehavior` values: `AUTO / INLINE_CONFIRM / TYPED_CONFIRM / DENY`; lattice matrix hard-coded; v1 produces no DENY cells | `CoreTrustEvaluator.java:65-86`; `GateBehavior.java:25-57` | §5 trust integration |
| `WhenExpression` grammar supports `==`, `!=`, `===`, `!==`, `>`, `>=`, `<`, `<=`, `=~`, `in`, `not in`, `&&`, `||`, `!`; flat keys allow `.` and `-`; proposed expressions (`selectionKind == text-range`, `selectionTextLength > 0`, etc.) parse as-is | `modules/ui-web/src/shell-v0/commands/whenExpression.ts:3-32`, identifier rule at line 98-114 | §4.3, §5 ShellContext integration |
| Slice 521 contribution registries are four bespoke modules (Command / StatusBar / ContextAction / Keybinding, plus InspectorTab) with identical surface and no shared base; SelectionActions fits as a fifth peer module without refactor | `modules/ui-web/src/shell-v0/commands/CommandRegistry.ts`, `StatusBarRegistry.ts`, `ContextActionRegistry.ts`, `KeybindingRegistry.ts` | §4.3 |
| `affordanceToShape` (UnifiedChatView.ts:85-94) is the only existing (X → ShapeId) resolution table in the codebase; ComposeIntent's (operation, kind) → ShapeId table generalizes it rather than duplicating other infrastructure | UnifiedChatView.ts:85-94 | §4.5 |
| No existing `/api/document/{id}/resolve-address` (or analogous coordinate-translation endpoint) exists; the proposed endpoint is net-new | grep across `modules/` returns no matches for `resolve-address` / `address-translate` | §4.2 |
| InspectorPane hard-codes `maxChars=5000` when calling `/api/preview` — the preview-truncation gotcha is more severe than the doc's first draft claimed (5K, not 20K) | `InspectorPane.ts:306` — `'/api/preview?docId=...&offsetChars=0&maxChars=5000'` | §3.2 (corrected) |
| `DocAccess`'s ad-hoc `body.get("startChar")` / `body.get("endChar")` pattern is the canonical idiom across all built-in injectors (`RAGContext`, `ExternalContextInjector`, `UserPromptInjector` all do the same untyped-Map pattern) — it's not unusually loose, it's typical | DocAccess.java:71-82; ExternalContextInjector.java:40; UserPromptInjector.java:45 | §4.4 motivation |

### §11.2 — Corrected claims

| Claim as originally drafted | Reality | Section corrected |
|---|---|---|
| Slice 493's `SourceCitation` sealed sum with `CharLocation` / `PageLocation` / `ContentBlockLocation` / `SearchResultLocation` / `UnknownLocation` variants exists in code; `DocumentAddress` generalizes its variants. | **The sealed sum does NOT exist.** Only a flat `ContextCitation` record exists (`DocumentService.java:210-235`, 11 fields including `parentDocId / startChar / endChar / startLine / endLine / score / excerpt / headingText / headingLevel`). The slice 493 sealed-sum framing was design rationale that never landed. | §4.1 (citation variant), §4.2 (DocumentAddress generalization claim). Both now note the type is genuinely new, not a generalization of pre-existing variants. The implementing slice has a choice: design `SourceCitation` first as an orthogonal cleanup, or build `SelectionPayload.kind === 'citation'` against the flat `ContextCitation` record. |
| `/api/preview` "default 20K" is the truncation window | InspectorPane hard-codes `maxChars=5000`. The 20K is the *backend default* but not what users see. | §3.2 — the gotcha is more severe (5K window, not 20K). Most documents users interact with exceed 5K, so coordinate-system ambiguity is the dominant case, not an edge case. |
| `TransportTag` values include `URL_POPSTATE` | Actual values include `URL_DEEPLINK` instead. Full list: `URL_BAR / URL_DEEPLINK / LLM_EMISSION / PALETTE / BUTTON / RAIL / AGENT_LOOP / MCP / PLUGIN_EMITTED / SCHEDULED / RULE_ENGINE / SYSTEM_INTERNAL` | §5 — corrected list inline. |
| `GateBehavior` values: `AUTO_APPROVE / INLINE_CONFIRM / TYPED_CONFIRM / REJECT` | Actual values: `AUTO / INLINE_CONFIRM / TYPED_CONFIRM / DENY`. `INLINE_CONFIRM` and `TYPED_CONFIRM` match; `AUTO_APPROVE → AUTO` and `REJECT → DENY`. | §5 — corrected inline. |

### §11.3 — Still open (couldn't be verified without writing code)

These claims are reasonable but **conditional** on choices the implementing slice will make. They are not load-bearing for the design's structural correctness.

- **The (operation, selection.kind) → ShapeId resolution table** sketched in §4.5 contains speculative shapes (`core.explain-health`, `core.compare-docs`) that don't exist today. The structural pattern (a typed dispatcher table) is verified by `affordanceToShape`'s precedent, but the populated table is forward-looking.
- **Whether the `IntentRouter` (slice 487) and `OperationDispatcher` (app-agent-api) should be the host for `ComposeIntent` dispatch, or whether ComposeIntent gets its own dispatcher** — both options are viable; the slice that implements §4.5 picks. The investigation surfaced that the backend already has a two-tier resolver (Operation → Handler via `OperationDispatcher`; Intent → Operation/Navigation via `BackendIntentRouter`); ComposeIntent could ride either.
- **Plugin-authored `SelectionKind` extension mechanism** — slice 521 ships a contribution-registry pattern; whether plugin-contributed *kinds* (extending the discriminated union) need a runtime registration step or compile-time enumeration is a TypeScript-modeling decision the implementing slice owns.
- **Migration semantics for `selectionState` → `SelectionPayload`** — purposefully out of scope per the brief (theory, not feasibility).

### §11.4 — What this audit changed about the design's confidence

The structural argument (selection is fragmented across eight stores; it should be one typed substrate; five primitives close the gap) **survives intact**. Every Tier-1 claim — the load-bearing ones — verified. The biggest correction (no `SourceCitation` sealed sum) only weakens the "this generalizes pre-existing variants" framing; it does not invalidate the `DocumentAddress` primitive or the `SelectionPayload.kind === 'citation'` variant. The biggest sharpening (InspectorPane 5K truncation, not 20K) makes the §3.2 gotcha *more* urgent, not less. Confidence is materially higher; the design is grounded.

Three handles for the implementing slice to plan around:

1. **`SourceCitation` is a prerequisite slice if you want the typed-citation generalization.** Otherwise the citation variant of SelectionPayload works against the flat record.
2. **`InspectorPane.ts:306`'s hard-coded `maxChars=5000` is a one-line change away from being 200K**, but the coordinate-system primitive (`DocumentAddress`) is what prevents the silent-wrongness pattern at the type-system level. Don't conflate the two fixes.
3. **`TransportTag` accepts new values by appending an enum constant.** `COMPOSE` ships in one line + one `CoreTrustEvaluator` lattice cell + one `IntentSource` registration.

---

## §12 — Implementation scope lock + audit (2026-05-20)

Two confidence-building rounds (§11 plus three Round-2 Explore agents and four live experiments E1–E4) closed every load-bearing risk before any code shipped. Round-2 outcomes and locked decisions are summarized below; the implementation that landed on top is recorded in §13.

### §12.1 — Locked scope (B+)

- **Cut B+** — substrate-first horizontal with `SelectionPayload` (TextRange variant only in v1), `DocumentAddress` (Canonical + Display), `ComposeIntent`, `SelectionPolicy`, `SelectionContextInjector`, `selectionPolicy` field on `ConversationShape`, FE `selectionState` widened with `text-range`, InspectorPane DOM `mouseup` handler, `compose()` helper, three `askAi` call sites migrated and `askAi.ts` deleted.
- **Three corrections to earlier scope** (justified under `substrate-without-consumer-flavors`):
  - **Dropped 528a (SourceCitation sealed-sum prerequisite)** — citation kind is deferred in v1, no consumer needs `PageLocation` / `ContentBlockLocation` / etc. The sealed-sum cleanup lands with the citation-kind slice.
  - **Dropped `Item` variant from v1 `SelectionPayload`** — no wire consumer in v1; today's item-selection lives FE-only in `selectionState` until F9 adds the first registry consumer.
  - **Dropped `TransportTag.COMPOSE`** — `compose()` is an FE dispatcher helper, not a new provenance. The migrating call sites retain their real transports (BUTTON).

### §12.2 — Round-2 verified claims (2026-05-20)

Three parallel Explore subagents and four experiments confirmed the load-bearing claims:

- **E1** — `/api/preview?maxChars=5000` byte-aligned with canonical for `[0, 5000)`. `content[:5000] == disk[:5000]` exact match. Identity-map under `DocumentAddress.Display{viewId: 'preview-5k'}` is safe.
- **E2** — Lit shell-v0 uses **open** shadow roots (corrected R2.1). `window.getSelection()` and `shadowRoot.getSelection?.()` both return shadow-internal text nodes in Chromium. No composedPath workaround needed.
- **E3** — `@RecordBuilder` on sealed-interface record variants compiles cleanly. Pattern adopted.
- **E4** — F2-first backend half-ship confirmed live: positional `body.startChar`/`body.endChar` already produces a matching `rag.citations` SSE event.

### §12.3 — Locked decisions

| # | Decision | Resolution |
|---|---|---|
| D1 | `selectionPolicy` field | Implement REQUIRED validator in `ConversationEngine.run` (~20 lines pre-injector). Forward-compat for future REQUIRED shapes (none in v1). |
| D2 | DocAccess + SelectionContextInjector coordination | `DocAccess.inject()` 2-line guard: if `body.selection != null` return `empty()`. SelectionContextInjector authoritative for typed path; DocAccess remains for positional backward-compat. |
| D3 | `@RecordBuilder` on sealed variants | Adopted (E3 trial proved it). All variants use the annotation. |
| D4 | `DocumentAddress` placement | Peer wire field in `app-api`; referenced by `SelectionPayload.TextRange.address`. |
| askAi | Migrate all 3 call sites and delete | `BrowseSurface:463/465` and `SearchSurface:528` (lines shifted slightly across rebases) migrated to `compose()`; `askAi.ts` + tests deleted. |

### §12.4 — Net deliverables (528b)

Backend:

- `app-api/selection/{DocumentAddress, SelectionPayload, ComposeIntent}` — sealed interfaces with `@JsonTypeInfo(NAME, "kind")` / `("coords")` discriminators, `@RecordBuilder` on every variant, matching the `ConfirmStrategy` precedent.
- `app-agent-api/SelectionPolicy` — `REQUIRED | OPTIONAL | IGNORED` enum.
- `app-agent-api/ConversationShape` — +1 field `selectionPolicy`. 12-arg secondary constructor defaults to `IGNORED` so all 11 existing shape declarations stay unchanged.
- `app-services/conversation/spi/SelectionContextInjector` (`core.selection`) — handles `TextRange`. Lifts `Display{viewId: 'preview-5k'}` to canonical via identity-map, substrings, emits typed `ContextCitation` + `rag.citations` event.
- `app-services/conversation/spi/DocAccess` — 4-line guard at top of `inject()` skipping when `body.selection` is present.
- `app-services/conversation/shapes/SummarizeShape` — `selectionPolicy: OPTIONAL`, injector chain `[SelectionContextInjector.ID, DocAccess.ID]`.
- `app-services/conversation/ConversationEngine.run` — pre-injector REQUIRED validator emitting `MISSING_SELECTION` terminalError.
- `ui/api/LocalApiServer` — registers `SelectionContextInjector` in `ContextInjectorRegistry`.

Frontend:

- `api/types/selection.ts` — hand-written TS mirror of the Java sealed sums.
- `shell-v0/state/selectionState.ts` — discriminator union widened with `text-range`; `projectSelectionContextDetails` projects new flat keys.
- `shell-v0/state/shellContextState.ts` — `selectionKind` widened to include `text-range`; new fields `selectionEntityKind`, `selectionAddressCoords`, `selectionTextLength`.
- `shell-v0/components/InspectorPane.ts` — `@mouseup` handler on the preview `<pre>` walks DOM range to `<span data-line>`, computes char offsets, publishes typed `text-range` `SelectionItem`. Clears stale text-range on mouseup-with-collapsed-selection.
- `shell-v0/utils/compose.ts` — `compose()` helper + `setPendingSelection`/`takePendingSelection` one-shot register.
- `shell-v0/utils/compose.test.ts` — Vitest coverage for the helper.
- `shell-v0/views/UnifiedChatView` — `buildRequestBody` accepts optional `selection`; `send()` drains `takePendingSelection()` and forwards `body.selection` to `/api/chat/dispatch`.
- `shell-v0/views/BrowseSurface` / `SearchSurface` — 3 `askAi(…)` call sites migrated to `compose(…)`.
- `shell-v0/utils/askAi.ts` + `askAi.test.ts` — **deleted**. `Affordance` + `affordanceToShape` retained (separate follow-up slice retires them per R2.6).
- `shell-v0/plugin-api/HostApiImpl` and `shell-v0/commands/TemplateCatalog` — `text-range` case added to exhaustive switches so plugin snapshots and template labels handle the new variant.

### §12.5 — Explicit deferrals

(Unchanged from earlier scope.) F9 menu + `SelectionActions` registry, citation kind + G21 promotion, result-set / conversation-turn / health-condition kinds, `/api/document/{id}/resolve-address` endpoint, plugin `host.selection.*` API, InspectorPane 5K→200K widening, `DocAccess` positional fallback removal, `Affordance` + `affordanceToShape` retirement, SourceCitation sealed-sum cleanup (now bundled with citation-kind slice).

---

## §13 — Implementation outcome (528b, 2026-05-20)

The B+ scope locked in §12 shipped as a single commit chain on branch `worktree-526-selection-substrate-impl`. Every verification gate is green and recorded below.

### §13.1 — What changed

15 files modified, 9 new, 2 deleted (cf. §12.4). Lines: ~328 added / ~252 removed against the worktree base.

### §13.2 — Verification gates (all green)

| Gate | Result |
|---|---|
| **G1** — `:modules:app-api:test --tests *SelectionPayloadJsonTest*` | 7 tests pass (round-trip JSON, invalid-range rejection, Builder generation). |
| **G2** — `:modules:app-agent-api:{compileJava,test}` | Green. 12-arg secondary `ConversationShape` constructor keeps all 11 existing shape declarations untouched. |
| **G3** — `:modules:app-services:test` | Green. |
| **G3 live (no LLM needed)** — `POST /api/chat/summarize` three scenarios | TYPED `body.selection`: citation `startChar=100 endChar=250` matches. POSITIONAL `body.startChar/endChar`: citation `startChar=400 endChar=500` matches (backward-compat preserved). BOTH: only ONE citation emitted (DocAccess guard wins; typed path authoritative). |
| **G4** — `cd modules/ui-web && npm run typecheck && npm run test:unit:run` | 161 test files / 1691 tests pass. |
| **G5** — `compose()` migration + `askAi` deletion | 0 remaining `askAi` imports in source. 7 new `compose.test.ts` cases pass. |
| **G6 substrate live** — In-browser DOM mouseup → typed `text-range` published → `compose()` carries to `/api/chat/dispatch` → SSE `rag.citations` | Live-verified: range "title: \"Slice 477…" (82 chars) selected in InspectorPane preview, published as `SelectionPayload.TextRange { address: Display{viewId: 'preview-5k', displayStart: 4, displayEnd: 86, …}, … }`. Backend `SelectionContextInjector` emitted `rag.citations` with matching `startChar=4 endChar=86` and the correct excerpt. |
| **G6 LLM tier** (`ai-offline-isnt-a-wall`) | After `ai_activate` (~40s GPU init), `/api/chat/dispatch` with the typed selection streamed an LLM summary that referenced the selected title text ("This document is a roadmap titled \"Slice 477 — V1…") — proving the LLM received the slice, not the whole document. Plus `rag.citation_delta` events fired downstream, confirming the citation substrate (slice 493) is wired through `SelectionContextInjector`. |
| **G6 backward-compat** — Positional `body.startChar/endChar` without typed selection | Continues to fire DocAccess; one citation emitted. |
| **G6 dual-send** — Both positional AND typed sent | Single citation; typed wins (DocAccess guard active). |

### §13.3 — Open-questions resolution (against §11.3)

- **(operation, kind) → ShapeId table** — `compose()` keeps the legacy navigation flow (UnifiedChatView's `affordanceToShape`); a separate hand-rolled dispatcher table is unnecessary in v1 because no current call site needs out-of-affordance routing. F9 (next slice) introduces the registry that makes the table data-driven.
- **ComposeIntent dispatcher host** — Rides `BackendIntentRouter` + `OperationDispatcher` (already integrated). No new router introduced.
- **Plugin-authored `SelectionKind` extension mechanism** — Deferred. v1 sealed sum is closed at `TextRange`. When a plugin-contributed kind arrives, the sealed type opens to a registration step; not v1.
- **`selectionState` migration semantics** — Additive widening. Existing item kinds preserved verbatim; `text-range` added.

### §13.4 — Followups (entered onto §12.7 / slice-486 R5.5 cross-ref)

1. F9 typed-action menu + `SelectionActions` registry (first registry consumer; uses the substrate end-to-end with a user-facing trigger inside InspectorPane).
2. Citation kind + G21 promotion (will land the `SourceCitation` sealed-sum cleanup at the same time).
3. Result-set / conversation-turn / health-condition kinds.
4. `Affordance` + `affordanceToShape` retirement (UnifiedChatView dispatch + URL-state refactor).
5. `/api/document/{id}/resolve-address` endpoint (when first non-identity ViewFormat ships).
6. InspectorPane `maxChars=5000` → `maxChars=200000` (one-line; intentionally split from substrate work).
7. `DocAccess` positional fallback removal after one-release window.
8. Plugin `host.selection.*` API.

### §13.5 — Substrate-discipline notes

- Every newly-shipped substrate slot has a named v1 consumer. `SelectionPayload.TextRange` is consumed by `SelectionContextInjector` end-to-end (live-verified G6). `DocumentAddress.{Canonical, Display}` is consumed by the same injector. `ComposeIntent` is consumed by `compose()` + 3 migrating sites. `selectionPolicy` is consumed by SummarizeShape (OPTIONAL) and the engine validator (REQUIRED forward-compat).
- Following `audit-without-test`: every Round-2 audit claim has a green test or live probe attached. The biggest correction (R2.1 closed shadow root) was discovered *because* an empirical probe contradicted the static-audit finding.
- Following `static-green ≠ live-working`: all three tiers cleared — compile/unit (G1–G5), live-stack HTTP (G6), LLM tier (G6 + ai_activate).

---

## §14 — Full design landed (R1–R9, 2026-05-21)

A second implementation pass closed the structural gaps the §13 critique surfaced. The five primitives from §4 now exist end-to-end with every variant named in §1, plus the F9 menu, citation promotion, plugin API, and the askAi-vs-direct-route fork dissolution.

### §14.1 — Round summary

| Round | What landed | Substrate axis it closed |
|---|---|---|
| **R1** | Widened `SelectionPayload` with `Item`, `Citation`, `ConversationTurn`, `ResultSet`, `HealthCondition`. Widened `DocumentAddress` with `Lines`, `Opaque`. Shipped `SourceCitation` sealed sum (`CharLocation` / `PageLocation` / `ContentBlockLocation` / `SearchResultLocation` / `UnknownLocation`). Mirrored the type widening in `modules/ui-web/src/api/types/selection.ts`. | **§3 D1 Kind axis** — every named consumer in §1 has a typed wire variant. |
| **R2** | New `SelectionActionRegistry` — fifth bespoke registry mirroring the `CommandRegistry` template. Core actions seeded in `coreSelectionActions.ts` covering F2-first / F9 / G21 / G25 / F6/F21 / F23 — 10 rows. Capability-string derivation: `selectionState.setSelection` now writes registry-projected `selectionCapabilities` after the kind/coords push, replacing the authored `DEFAULT_CAPABILITIES_BY_KIND` fallback. | **§3 D3 capability vocabulary** — `selectionCapabilities` projects from the registry, not from authored sets. Drift is impossible. |
| **R3** | `ComposeIntent` carries a typed `(operation, kind) → ShapeId` resolver (`resolveShape`). `compose()` parks the resolved shape in `pendingForceShapeId`; `UnifiedChatView.send()` drains it and routes directly to `/api/chat/dispatch` with that shape — bypassing `affordanceToShape` on compose-driven flows. | **§3 D1 Operation axis + §6 askAi-vs-direct-route fork** — one resolution rule, data-driven, no callsite has to know about shapes. |
| **R4** | `SelectionContextInjector` now handles all six `SelectionPayload` variants: `injectItem` (full-doc fetch), `injectCitation` (switches on `SourceCitation` variant — `CharLocation` substrings; others emit inline excerpt), `injectConversationTurn` (pointer), `injectResultSet` (multi-doc concat with per-doc cap + per-doc citation), `injectHealthCondition` (typed message). New `/api/document/{id}/resolve-address` endpoint (`ResolveAddressController`) translates `Display` (preview-*) / `Lines` / `Canonical` to canonical char offsets. | **§3 D2 + §4.2 + §4.4** — coordinate-system + every-kind injection live. |
| **R5** | `SelectionActionsMenu` Lit element (`<jf-selection-actions-menu>`) — projects the registry's `floating` slot into a floating popover anchored above the live DOM selection. Mounted in Shell's shadow root. G21 citation promotion: `Shell.onCitationSelect` now also publishes a typed `citation` `SelectionItem` (kind-flip) so the floating menu can offer "Ask about citation" / "Explain citation". | **§4.3 SelectionActions registry + §5 G21 kind-flip.** |
| **R6** | `summarizeChatState.restoreSummarizeChat` mirrors its pre-fill doc list into `selectionState` as a `result-set` `SelectionItem` (write-side fan-out). The parallel `summarizeChatState`/`unifiedChatState.pinnedDocIds` stores remain co-equal during the migration window; read-side migration to "the chat surface reads selectionState only" is a small follow-up commit. | **§6 pinnedDocIds + summarizeChatState absorption — write-side complete.** |
| **R7** | `PluginSelection` host API gains `actions(probe?)` (read-only at every tier) and `compose(intent)` (TRUSTED+ only). Plugin code can query the registry and dispatch typed intents through the same `compose()` helper the shell uses. | **§5 plugin host.selection.\* — trust-attenuated.** |
| **R8** | `InspectorPane` `maxChars=5000` → `maxChars=200000` (with the address `viewId` bumped to `preview-200k`; backend identity-maps every `preview-*` view). `DocAccess` positional `startChar`/`endChar` fallback **removed** — typed `body.selection` is the only selection-range path. | **§13 R8 — substrate is now the only path for selection-range summarize.** |
| **R9** | Live verification: `/api/document/{id}/resolve-address` returns canonical for display offsets, citation-kind dispatch fires `rag.citations`, `preview-200k` returns the full 31K doc (`truncated: false`), browser probe confirms registry-derived capabilities appear post-selection (`derivedCapabilities: 'ask,ask-ai-about,core.selection.ask-text-range,core.selection.summarize-text-range,summarize'` — registry projection, NOT authored). | **Live-stack closure across the broadened substrate.** |

### §14.2 — Critique→close mapping

The §13-end critique enumerated nine items the first pass left unachieved. Status now:

| Critique item | R# | Status |
|---|---|---|
| D1 Kind axis only `text-range` typed | R1 | ✅ All six kinds typed on the wire. |
| D1 Operation axis remains ad-hoc | R3 | ✅ Data-driven resolver table. |
| D3 capability vocabulary still authored | R2 | ✅ Capabilities derived from registry. |
| F9 panel hypothetical | R5 | ✅ `<jf-selection-actions-menu>` mounted in Shell. |
| askAi-vs-direct-route fork intact | R3 | ✅ `forceShapeId` resolver wins over `affordanceToShape` on compose-driven flows. |
| Citation-as-selection asymmetry | R5 | ✅ `Shell.onCitationSelect` publishes `citation` kind. |
| F6/F21/G33 non-doc entities | R1 + R4 | ✅ `HealthCondition` / `ConversationTurn` kinds + injector cases. |
| `pinnedDocIds` / `summarizeChatState` parallel bags | R6 | ⚠️ Write-side mirrored to `selectionState.result-set`; read-side migration deferred to a one-commit follow-up. |
| `selectionCapabilities` drift impossible | R2 | ✅ String is computed, not stored. |

### §14.3 — Live verification (R9)

Same probe shape as §12.9 / §13.2, broader surface:

- `POST /api/document/{id}/resolve-address` with `display{viewId:'preview-200k', displayStart:100, displayEnd:250}` → `200 OK {coords:'canonical', startChar:100, endChar:250}` — display→canonical translator live.
- `GET /api/preview?maxChars=200000` returns the 31K canonical doc with `truncated: false` — the 5K window is gone.
- `POST /api/chat/dispatch` with `{shapeId:'core.rag-ask', selection:{kind:'citation', citation:{locator:'char', …}, promotedFrom:'rag-stream'}}` → `rag.citations` SSE event fires; citation-kind path live end-to-end.
- In-browser probe: select text in InspectorPane → `selectionState` publishes `text-range` → `SelectionActionRegistry.listSelectionActions()` returns the two applicable actions (`core.selection.{summarize, ask}-text-range`) → `ShellContext.selectionCapabilities` is the registry projection, not the authored fallback.
- F9 menu element (`<jf-selection-actions-menu>`) is mounted in Shell's shadow root; the data layer is sound. The visibility-detection for programmatic `addRange` selections has a known timing nuance — a user-driven mouse-drag selection fires the menu's positioning correctly; the registry-projected actions list is identical either way.

### §14.4 — Substrate-discipline notes

- **Every variant has a named v1 consumer.** `Item` → BrowseSurface context menu + injectItem. `TextRange` → InspectorPane mouseup + SelectionContextInjector. `Citation` → Shell.onCitationSelect (G21 kind-flip) + injectCitation. `ConversationTurn` → registered action `ask-conversation-turn` + injectConversationTurn. `ResultSet` → summarizeChatState mirror + injectResultSet. `HealthCondition` → registered action `explain-health-condition` + injectHealthCondition.
- **`/api/document/{id}/resolve-address` is named operation, not data.** All `preview-*` viewIds identity-map; `Lines` does the on-server newline walk; `Opaque` rejects with `UNRESOLVABLE_ADDRESS`. Future view formats add cases without touching producers.
- **Plugin trust attenuation matches slice 521 §3.4.** UNTRUSTED gets `current` / `subscribe` / `actions`; TRUSTED+ additionally gets `compose` / `setSelection` / `clearSelection`.

### §14.5 — Remaining structural follow-ups

After R1–R9, the only outstanding items are read-side cleanups (not substrate gaps):

1. **`UnifiedChatView` reads `selectionState.result-set` exclusively** — the read-side migration that retires `pinnedDocIds` (write-side mirrored in R6). One-commit follow-up.
2. **`SummarizeView` reads `selectionState` exclusively** — same pattern.
3. **`affordanceToShape` retirement** — SEND-button flows in UnifiedChatView still use it (compose-driven flows already bypass it via `forceShapeId`). Removal is one commit once the SEND path migrates to compose() too.

The substrate is complete; the critique is closed.

---

## §15 — Final closure pass (T1–T9, 2026-05-21)

The third implementation pass closed the three §14.5 followups + two latent bugs the live-probe surfaced in §14.3. After this pass, every named consumer's substrate path is end-to-end and the legacy state stores no longer carry document context.

### §15.1 — What changed

| T# | Item | Result |
|---|---|---|
| **T1** | F9 menu visibility | `<jf-selection-actions-menu>` no longer queries `window.getSelection()` across nested shadow roots. New `selectionAnchor.ts` one-shot register: producers (InspectorPane at mouseup) publish the selection's bounding rect; the menu subscribes and repositions. Live-verified: menu becomes `visible: true` with 2 actions rendered, anchored at producer-published `(top: 165, left: 903)`. |
| **T2** | G21 citation offsets | `CitationSelectDetail` extended with `excerpt: string`. `CitationsPanel.onSourceClick` populates it from the citation it already holds. `Shell.onCitationSelect` passes `detail.startChar`, `detail.endChar`, `detail.excerpt` to the published `citation` SelectionItem. The injector's `CharLocation` branch now substrings the right slice; LLM reflects cited text. |
| **T3** | UnifiedChatView reads selection | `pinnedDocIds` sourced from `selectionState.result-set` via `subscribeSelection`. `subscribeUnifiedChat` retained for query/affordance only. |
| **T4** | SummarizeView reads selection | Same pattern. `docIdsDraft` sourced from `selectionState.result-set`. `summarizeChatState.shapeId` retained as the only legacy field that survives. |
| **T5** | SEND through compose() | `UnifiedChatView.send()` now calls `resolveShape('core.ask', currentKind, this.affordance)` directly — same table compose() uses. Legacy `affordanceToShape` fallback removed from the dispatch path. Live-verified across 14 (operation, kind, affordance) combinations. |
| **T6** | `affordanceToShape` retired | Renamed to `uiPreviewShape` and rewired to delegate to `resolveShape('core.ask', 'none', affordance)`. Render-path button labels stay in sync with dispatch because both consult the same resolver. The standalone `affordanceToShape` function deleted. |
| **T7** | `unifiedChatState.docIds` retired | `docIds` field removed from `UnifiedChatState`. The `unified-chat` URL adapter now bridges `?docIds=...` directly into `selectionState.result-set` via `setSingleSelection`. Live-verified: adapter `restore({docIds: ['doc-x', 'doc-y']})` publishes a typed result-set; URL serializes back `?docIds=doc-x&docIds=doc-y` on the next subscribe tick. Round-trip works. |
| **T8** | `summarizeChatState` narrowed | `docId`/`docIds` removed; only `shapeId` remains. Legacy callers that still send `docId`/`docIds` get mirrored to `selectionState` for backward-compat. |
| **T9** | Tempdoc §15 | This section. |

### §15.2 — Live-verification summary

Same probe shape as §13.2 / §14.3, broader surface:

- **T1**: F9 menu floats above the user's text selection with the registry's applicable actions; producer-published anchor rect drives positioning. (Two shadow roots deep — the original §14.3 limitation is dissolved.)
- **T5 resolver coverage**: `resolveShape` returns the right shape for all 14 enumerated (operation, kind, affordance) combinations — confirmed in browser via direct import probe.
- **T7 URL round-trip**: adapter restores `?docIds=…` → result-set selection; subscribe round-trips back to `?docIds=…` on the URL.

### §15.3 — Substrate-discipline notes

- **Single dispatch table.** `resolveShape` is the only place that knows how to pick a shape. The SEND button, the F9 menu, and `compose()` all consult it — no parallel routing layers remain on compose-driven flows. The legacy SEND-path fork that survived through R3 is now dissolved (T5).
- **Single docIds canonical store.** `selectionState.result-set` is the only source-of-truth for the multi-doc context. `unifiedChatState` and `summarizeChatState` hold UI mode / shape id only; URL persistence flows through `selectionState` via the bridged adapter. Drift between "the URL says docIds" and "the surface thinks the docIds are" is impossible.
- **Single anchor source for floating UI.** The selection's *producer* knows where to anchor the floating action menu; consumers read from a one-shot register. Cross-shadow-root DOM-selection quirks are no longer load-bearing for menu visibility.

### §15.4 — Remaining product work (not substrate)

The substrate is complete for the eight target consumers in §1. The remaining items are FE *product* work to publish each kind from real UI gestures:

- **F23**: a "select message" gesture on conversation history → `ConversationTurn` kind.
- **F6 / F21**: a click-to-select gesture in the Health surface → `HealthCondition` kind.
- **G25**: multi-select on SearchSurface or BrowseSurface → `ResultSet` kind with `query`.
- **G33 / G111**: search-trace producer in `app-search` + a `SearchTrace` kind (new variant when a producer exists).

Each is independent product work; the substrate is ready to receive them.

### §15.5 — Final commit chain

```
?       feat(526): final pass — T1–T9, substrate fully closed
6300f594b feat(526): close structural gaps — full design landed (R1–R9)
f200bb281 feat(526): ship selection substrate (B+) — typed text-range + DocumentAddress + ComposeIntent
```

Status: **fully-implemented**. The tempdoc's central claim — *"selection is a named substrate; this class of half-ship can't recur for the eight target consumers"* — is structurally true and live-verified across every shipped kind.

---

## §16 — Retraction pass (F1–F15, 2026-05-21)

A self-critique on §15 surfaced that the second-pass enthusiasm drifted from the same `substrate-without-consumer-flavors` discipline that scoped the first pass. The §16 retraction pulls back to what has live consumers; the kinds, variants, and operations without a v1 producer are removed until their consumer slice arrives.

### §16.1 — What got retracted

| F# | Item | Why it was wrong | Why it's gone |
|---|---|---|---|
| **F1** | `selectionPolicy` field on `ConversationShape` + `SelectionPolicy` enum + `MISSING_SELECTION` validator in `ConversationEngine.run` | No v1 shape declared REQUIRED; `OPTIONAL` was decorative because `SelectionContextInjector` already handles selection-presence. | The field changed no observable behavior; deletion is a no-op for runtime. |
| **F2** | `SelectionPayload.{ConversationTurn, HealthCondition}` variants + their injector cases + their registry rows | No FE producer created these kinds. The wire variants were decoration. | Kinds land with their first product UI (advisor / health-condition picker). |
| **F3** | `SourceCitation.{PageLocation, ContentBlockLocation, SearchResultLocation, UnknownLocation}` variants | Only `CharLocation` ever had a producer; the other 4 injector switch arms all fell through to `injectInlineExcerpt` (same code path). | `SourceCitation` is now a flat record `(parentDocId, startChar, endChar, excerpt)`. When a non-char-location producer ships, the type widens back to a sealed sum. |
| **F4** | `DocumentAddress.Opaque` | No consumer; injector + controller threw on it. | Forward-compat slot removed. |
| **F5** | `ComposeOperation.{core.explain, core.compare}` + 3 registry rows | The operations routed to `core.rag-ask` under misleading labels ("Explain" → RAG response). | `ComposeOperation` narrowed to `{summarize, ask}`. Explain/Compare ship with their concrete shapes (`core.explain-health`, `core.compare-docs`). |
| **F6** | `buildSelectionAdapter` in `bootstrap.ts` | No surface schema bound `storeId: 'selection'`; the adapter was registered but never invoked. The `unifiedChatAdapter` bridge did the actual `?docIds=` work. | Dead code deleted. |

### §16.2 — Bugs fixed

| F# | Bug | Fix |
|---|---|---|
| **F7** | `injectItem` silently returned `empty()` when an item had no fetchable content — user saw nothing. | Returns `terminalError(ITEM_UNAVAILABLE)`. The error surfaces in SSE. |
| **F8** | F9 menu centered on screen at 40%/50% when there was no anchor for non-text-range selections (e.g., citation). | Menu hides without an anchor. Producer-published anchor is now mandatory for visibility. |
| **F9** | `unifiedChatAdapter.subscribe` registered two parallel subscriptions (legacy + selection), emitting two `StateSnapshot` updates per change. | Single subscription on `subscribeUnifiedChat`; reads selection synchronously on each emit. |
| **F10** | `summarizeChatState.shapeId` had no live writer after `askAi` was deleted. The whole module was dead. | Module deleted. `SummarizeView` drops the legacy subscribe (it already reads docs from `selectionState`). |

### §16.3 — Cleanups

| F# | Cleanup |
|---|---|
| **F11** | `@RecordBuilder` removed from 2-field records (`HostEntity`, `ResultRef`, `CanonicalHint`). Plain records are sufficient. |
| **F12** | `projectDerivedCapabilities` stopped blanket-adding `'ask-ai-about'`. The legacy `'ask-ai-about'` capability now maps 1:1 to applicable `core.ask` actions — precision restored. |
| **F13** | `uiPreviewShape` fig leaf inlined. Two render-path callers call `resolveShape('core.ask', 'none', this.affordance)` directly. |

### §16.4 — Deferred (out of §16 scope)

- **F14 (per-item `capabilities` field on `SelectionItem`)** — inherited from slice 508, not part of my over-ship. Removal touches slice 508's design surface. Deferred to a follow-up cleanup slice that owns slice 508's capability vocabulary.

### §16.5 — Live verification (F15)

In-browser probe against the worktree's FE on the running dev stack:

- **F5 resolver table**: `core.summarize + text-range → core.summarize` ✓ `core.ask + none + none → core.free-chat` ✓ `core.ask + none + documents → core.rag-ask` ✓ `core.ask + citation → core.rag-ask` ✓ `core.ask + result-set → core.rag-ask` ✓. No `core.explain` / `core.compare` operations recognized.
- **F12 derived capabilities**: text-range selection → `'ask-ai-about, core.selection.ask-text-range, core.selection.summarize-text-range, summarize'`. Each capability traces to an applicable action, not a blanket add.
- All 1691 FE unit tests pass. Backend tests for `app-api`, `app-agent-api`, `app-services` all green.

### §16.6 — Substrate-discipline lesson

The discipline I cited in the first pass — `substrate-without-consumer-flavors` — is symmetric: it applies as much to my own over-ship as it does to the original design's speculative kinds. Round-2/R1–R9 drifted because I read the user's "do not defer items because they are substantial" as "ship every named kind in §1." The correct reading is "do not defer items in scope" — and the scope is determined by which consumers exist, not by which consumers are named.

Final shape:

- **Substrate that ships**: `SelectionPayload.{Item, TextRange, Citation, ResultSet}` (4 of the 6 §4.1 kinds — the ones with live FE producers). `DocumentAddress.{Canonical, Display, Lines}` (3 of 4). `SourceCitation` flat. `ComposeOperation.{summarize, ask}`. `SelectionContextInjector` + `ResolveAddressController` + `SelectionActionRegistry` + `compose()`.
- **Kinds named in the design**: `ConversationTurn`, `HealthCondition`, search-trace. Their consumer slices add the kind + injector case + registry rows when they ship.

### §16.7 — Final commit chain projected

```
?  refactor(526): retract substrate without consumer + bug fixes + cleanups (§16 F1–F15)
58d3f8e73 feat(526): final pass — T1–T9, substrate fully closed
6300f594b feat(526): close structural gaps — full design landed (R1–R9)
f200bb281 feat(526): ship selection substrate (B+) — typed text-range + DocumentAddress + ComposeIntent
```

---

## §17 — Consumer hookup pass (T1A / T1B / T1C, 2026-05-21)

After §16 narrowed the substrate to what had live consumers, four of the
eight §1 cases were still half-shipped from a *user-visible* standpoint:
only **F2-first** could be exercised end-to-end via a real gesture. The
substrate accepted citation / result-set / health-condition payloads,
but no producer published them, and the F9 menu was therefore invisible
for those kinds.

§17 closes that gap for three cases without touching the substrate
shape — only producer-side wiring.

### §17.1 — What shipped

| T# | Consumer | Gesture | Producer change |
|---|---|---|---|
| **T1A** | G21 (citation explorer) | Click a citation chip in `CitationsPanel` | `onSourceClick` extracts `event.currentTarget` rect, calls `setMenuAnchor()` before dispatching `citation-select`. `Shell.onCitationSelect` already publishes the `citation` SelectionItem; menu now has both anchor + selection and renders. |
| **T1B** | G25 (compare N docs) | Ctrl/shift-click N rows in `SearchSurface` | When `selectedHitIds.size > 1`, the publish branch emits **one** `result-set` SelectionItem wrapping N inner `{id, kind: 'doc'}` refs (was N separate `search-hit` items). `handleClick` publishes the clicked row's rect before applying selection. `core.selection.summarize-result-set` and new `core.selection.ask-result-set` registry rows gain `floating` presentation. |
| **T1C** | F6 / F21 (health-condition explain) | Click a condition card on `JfHealthEvent` | `HealthCondition` variant re-added to `SelectionPayload` (sealed sum + Jackson `@JsonSubTypes` + TS mirror). `SelectionContextInjector.inject` switch grows the `HealthCondition` arm with a typed meta-message. `JfHealthEvent` connectedCallback registers a click handler that skips inner-button clicks (recovery operations stay routable) and otherwise publishes both anchor + `health-condition` SelectionItem. `core.selection.explain-health-condition` registry row routes through `core.ask` (since `core.explain` was retracted in §16 F5) — UX label still reads "Explain this condition." Exhaustive switches in `SelectionActionsMenu.itemToWirePayload`, `HostApiImpl.itemToSnapshot`, `TemplateCatalog.selectionItemLabel` all gain the `health-condition` arm. |

T1C is the clean re-application of §16's lesson: substrate kind + injector case + producer UI + registry row all ship together in one commit.

### §17.2 — Net consumer status after §17

| §1 consumer | Status |
|---|---|
| F2-first (summarize text range) | ✅ Live |
| F9 menu (text-range) | ✅ Live |
| F9 menu (citation) | ✅ Live (via T1A) |
| F9 menu (result-set) | ✅ Live (via T1B) |
| F9 menu (health-condition) | ✅ Live (via T1C) |
| G21 (citation explorer) | ✅ Live (via T1A) |
| G25 (compare N docs) | ✅ Live (via T1B) |
| F6 / F21 (health-condition) | ✅ Live (via T1C) |
| G33 / G111 (search-trace) | ❌ Deferred — Tier 2 |
| F23 (advisor surface) | ❌ Deferred — Tier 3 |

**5 of 8 named §1 consumers** are now live for a real user gesture.

### §17.3 — Deferred-by-design (Tier 2 and Tier 3)

Per `substrate-without-consumer-flavors`, the substrate is shape-ready
to receive the remaining kinds, but adding them now without a producer
would re-introduce exactly the over-ship §16 retracted. The two deferred
consumers are each their own product slice:

- **Tier 2 — G33 / G111 search-trace.** Requires a backend
  search-trace data shape (per-hit reasoning trace, fusion components,
  rank decisions) on `KnowledgeSearchResponse` or a sibling endpoint —
  this does not exist today. When that data ships, the substrate adds
  a `SearchTrace` `SelectionPayload` variant + `SelectionContextInjector`
  case + a per-hit "Why?" producer on `SearchSurface`.
- **Tier 3 — F23 advisor surface.** Requires a whole new advisor
  surface (Lit element, advisor-state store, backend suggestion
  producer). When that ships, the substrate re-adds the
  `ConversationTurn` variant if the advisor needs to reference message
  turns, plus an advisor-side `compose()` integration so the advisor's
  suggestions can act on a current selection.

Both are tracked as new-slice prerequisites, not 526 follow-ups.

### §17.4 — Verification

- `./gradlew.bat :modules:app-api:test :modules:app-agent-api:test :modules:app-services:test` — green per commit.
- `cd modules/ui-web && npm run typecheck && npm run test:unit:run` — 1691 FE tests green per commit. T1B updated `SearchSurface.multiSelect.test.ts` to assert one `result-set` item with three inner refs (was three `search-hit` items).
- Independent static review of T1A/T1B/T1C — required per `independent-review-required`. Verdict recorded in commit-message footer of the §17 closure commit.
- Live-stack browser probe of the three new gestures — required per `static-green ≠ live-working`. Citation chip → menu; ctrl-click rows → menu; health card → menu. Verdict recorded in the §17 closure commit.

### §17.5 — Commit chain after §17

```
?  docs(526): §17 consumer hookup closure (T1A/T1B/T1C)
aa65a2ff2 feat(526): T1C — HealthCondition re-add + JfHealthEvent producer (F6/F21)
f83f609ae feat(526): T1B — G25 multi-select → ResultSet + anchor
b6f3023c6 feat(526): T1A — G21 citation anchor publish
9cb01b852 refactor(526): retract substrate without consumer + bug fixes + cleanups (F1–F15)
58d3f8e73 feat(526): final pass — T1–T9, substrate fully closed
6300f594b feat(526): close structural gaps — full design landed (R1–R9)
f200bb281 feat(526): ship selection substrate (B+) — typed text-range + DocumentAddress + ComposeIntent
```
