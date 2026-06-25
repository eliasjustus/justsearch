---
title: "510 — AI-Aware Shell"
---

# 510 — AI-Aware Shell

**Status**: done
**Scope**: Framework-level AI capability gating, ambient activity signals, AI intent routing,
conversation management  
**Prerequisite**: Tempdoc 508 shipped (AiStateStore, consumer migration, poller fixes).

**Live verification results:**
- Capability pills: "Chat" (green) + "RAG unavailable (no documents)" (red) + "Embedding inactive" (red) correctly shown when degraded; hidden when all capabilities available
- History button: renders in header, dropdown loads from backend
- Affordance gating: Documents button disabled when !capabilities.rag; Send shows "AI Offline" when !capabilities.chat
- Rail activity dot + window title: wired (needs streaming activity to visually verify dot)
- Framework data-ai-available attribute: Stage sets on mounted surfaces
- askAi helper + Ctrl+Shift+A shortcut: wired
- SearchSurface "Ask AI" button: renders next to copy actions
- ConversationListStore + ConversationHistory dropdown: created and wired
- Auto-title via SummarizeShape: fires after first response

**Gap fixes (2026-05-18) — closed all 5 critical analysis findings:**
- Gap 1: Capability pills are now buttons that navigate to Brain/Library
- Gap 2: Conversation resume loads history from backend; thread populates correctly
- Gap 3: Activity dot color follows state (teal=thinking, green=streaming, amber=extracting)
- Gap 4: Navigation toast shows AI status badge with full statusLabel (e.g. "Online — Qwen_Qwen3.5-9B")
- Gap 5: History dropdown verified end-to-end with 4 stored conversations; click resume loads thread

**Two bugs surfaced during live verification, fixed:**
- FileConversationStore.listSessions assumed nested storage but appendMessage writes flat — fixed by iterating sessionDirs and reading shapeId from each meta.json
- askAi helper dispatched on document; Shell listens on itself. Fixed by dispatching from jf-shell element.

**Remaining items shipped (2026-05-18):**
- DELETE /api/chat/conversations/{sessionId} endpoint — wired to ConversationStore.deleteSession
- ConversationListStore: createConversationId(), resumeConversation(), deleteConversation() — store API now matches the spec
- ConversationHistory dropdown: per-item delete button (×) with ConfirmDialog confirmation; hover-reveal styling
- BrowseSurface: existing summarize/ask-about context menu actions refactored to use askAi() helper — unified dispatch pattern
- Stage: framework-level default CSS `[data-ai-available='false'] { filter: saturate(0.6); }` applies to AI-dependent surfaces when AI is offline — surfaces inherit without subscribing

**Verified live (2026-05-18):**
- DELETE endpoint: 4 sessions → DELETE one → 3 sessions returned, on-disk directory removed
- Delete button: hover reveals ×, click shows red ConfirmDialog with "Permanently delete..." copy, confirm removes from dropdown + disk reactively (no reload)
- data-ai-available CSS: getComputedStyle filter returns `saturate(0.6)` when attribute is `false`
- askAi from console: returns true, navigates to chat surface, pre-fills inputDraft + affordance

**Explicitly skipped per critical analysis (with reasoning):**
- InspectorPane Ask-tab redirect to chat — current in-place behavior is better UX (keeps inspector context)
- Ctrl+Shift+A command palette popup — current "navigate to chat" is functionally adequate; building a separate popover for one shortcut is feature creep

**Framework-absorb refactor (2026-05-18) — moves work out of UnifiedChatView and into framework/store layers, in line with the thesis ("framework provides AI awareness; surfaces inherit it"):**
- Capability pills became chrome: new `<jf-capability-pills>` component reads AiState directly; Shell renders it above the stage when active surface is AI-dependent. Any AI-dependent surface gets pills automatically — they're no longer chat-specific.
- Conversation resume moved to the store: `ConversationListStore.resumeConversation()` owns the history fetch + filter; view's `loadConversation` delegates. Restores the history-load that regressed in an earlier commit.
- Auto-title moved to the store: `ConversationListStore.generateConversationTitle()` owns the LLM call. View's `generateTitle` just looks up first user/assistant pair and delegates. Uses FreeChat throwaway session (then DELETEs it) — SummarizeShape can't accept raw text (requires docIds via DocAccess; the original tempdoc investigation was wrong about this).

**Live-verified (2026-05-18):**
- Pills render at chrome level above chat surface; absent on Library surface (not AI-dependent)
- Store's `resumeConversation()` called from console: returned sessionId, shapeId, 2 messages with correct content
- Auto-title via FreeChat throwaway + DELETE: backend curl confirmed streamed title + session cleanup

**Store-absorb refactor (2026-05-18) — moves recent-sessions persistence out of the view:**
- `RECENT_SESSIONS_KEY`, `MAX_RECENT`, the `RecentSession` shape, `getRecentSessions()`, `recordRecentSession()`, `forgetRecentSession()`, and `createConversationId()` all moved from `UnifiedChatView` statics into `ConversationListStore`.
- `deleteConversation()` now calls `forgetRecentSession()` so a deleted session can never come back via the resume prompt.
- `UnifiedChatView` no longer owns any sessionId/recent-session bookkeeping — it just calls the store helpers. localStorage layout is identical (same key + JSON shape), so existing user data continues to work.

**Live-verified (2026-05-18):**
- Module exports the four new functions + the previous helpers; mounted UnifiedChatView prototype has zero leftover static helpers (`generateSessionId`, `getRecentSessions`, `saveSession`, `RECENT_SESSIONS_KEY`, `MAX_RECENT` all absent).
- View's `sessionId` matches `^uc-[0-9a-f-]{36}$` — proving the production path uses `createConversationId()` from the store.
- Round-trip: `recordRecentSession` writes localStorage → `getRecentSessions` reads it back → `forgetRecentSession` removes it.
- `deleteConversation(id)` returns true and `getRecentSessions()` no longer contains the deleted id — `forgetRecentSession` is wired into the delete path as intended.

---

## Thesis

Tempdoc 508 made every component *able* to see AI state. This tempdoc makes the *framework*
see it — so that surfaces, rail icons, and operations inherit AI awareness automatically,
without each one subscribing and checking individually.

The analogy: design tokens propagate through CSS variables. Components don't subscribe to a
theme store — they inherit tokens from their container. AI capability should work the same
way. A surface that needs AI declares it once (via `conversationShapes` in its catalog entry);
the Shell handles the rest.

---

## Existing Infrastructure

| Primitive | Location | What it provides |
|---|---|---|
| `SurfaceConsumes.conversationShapes` | `surface.ts`, `CoreSurfaceCatalog.java` | Surfaces declare which AI shapes they host |
| `RequiredCapability` (sealed) | `OperationPolicy.java` | Operations declare runtime prerequisites (Worker, Inference, GPU, IndexedRoot) |
| `AiState.capabilities` | `aiStateStore.ts` | Live boolean map: chat, rag, extract, embedding |
| `AiState.activity` | `aiStateStore.ts` | Live activity state: idle, thinking, streaming |
| `ShellAddress` (sealed union) | `intentRouter.ts` | Intent variants: navigate, invoke |
| `Rail.renderButton()` | `Shell.ts` | Per-surface button with access to full `SurfaceCatalogEntry` |
| `Shell.handleGlobalKey()` | `Shell.ts` | Hardcoded keyboard shortcuts (Alt+arrows, Ctrl+L, Ctrl+D) |
| `FileConversationStore` | `FileConversationStore.java` | Session metadata: id, title, firstMessage, lastActive, messageCount |
| `GET /api/chat/conversations` | `AiRoutes.java` | REST endpoint for session listing |

---

## Design A: Capability Gating as a Framework Concern

### Principle

The Shell knows which surfaces need AI (from `SurfaceConsumes.conversationShapes`). When
AI capability changes, the Shell gates those surfaces automatically. Surfaces never subscribe
to AiStateStore for capability — they inherit the gated state.

### Mechanism

The Shell already subscribes to AiStateStore (initialized in `connectedCallback`). Add a
derived set: `aiDependentSurfaceIds` — surface IDs whose `consumes.conversationShapes` is
non-empty. When `AiState.capabilities.chat` transitions false→true or true→false, the
Shell propagates this to:

1. **Rail icons**: Surfaces in `aiDependentSurfaceIds` get a CSS class
   `ai-unavailable` on their rail button when `!capabilities.chat`. The button stays
   clickable (the user can still navigate there) but shows a visual indicator — a small
   muted badge or reduced opacity. Clicking navigates normally; the surface itself
   shows its own empty state ("AI offline — start AI in Brain").

2. **Mounted surfaces**: The Shell sets a `data-ai-available` attribute on the mounted
   surface element. Surfaces can style themselves based on this attribute without
   subscribing to any store:
   ```css
   :host([data-ai-available="false"]) .ai-feature { opacity: 0.5; pointer-events: none; }
   ```

3. **Navigation toasts**: When navigating to an AI-dependent surface while AI is offline,
   the toast includes a status badge: "Navigated to Chat — AI Offline".

### What surfaces don't need to do

- Subscribe to AiStateStore for capability checks
- Manually disable their own affordances based on AI state
- Render their own "AI offline" banners (they can if they want richer messaging,
  but the framework provides the default)

### What surfaces still own

- Their specific UX for degraded mode (e.g., UnifiedChatView shows "keyword search only"
  when rag is false but chat is true)
- Their activity reporting (setAiActivity when streaming)
- Their AI-specific interactions (citation click-through, reasoning block)

---

## Design B: Ambient Activity Signal

### Principle

When the AI is doing work (thinking, streaming, extracting), the Shell's chrome shows it
everywhere — without the active surface doing anything beyond reporting its activity state
to the AiStateStore (which it already does).

### Mechanism

The Shell reads `AiState.activity.state` and propagates it to:

1. **Rail icon for the active chat surface**: A pulsing dot overlay on the Chat icon.
   Color follows the activity state: teal for thinking, green for streaming. Disappears
   when idle. Implementation: the Rail's `renderButton` checks if the surface ID matches
   `activity.shapeId`'s hosting surface and renders a `<span class="activity-dot">`.

2. **Status deck label**: Already shows "Thinking... Ns" / "Streaming" via the statusLabel.
   This is shipped (508). No additional work.

3. **Window title**: When `activity.state !== 'idle'`, the document title could include
   "⟳ Thinking..." to show activity even when the app is in the background or the user
   is looking at the taskbar. Lightweight and universal.

---

## Design C: AI Intent Routing

### Principle

Any surface should be able to say "ask the AI about X" without knowing which surface hosts
the chat, which shape handles the request, or where the UI will render. The intent router
resolves all of this.

### Mechanism

**Investigation finding (2026-05-17):** Extending the `ShellAddress` sealed union is
unnecessary. An `ask-ai` intent is just a navigate intent to `core.unified-chat-surface`
with state `{ query, affordance, docIds }`. The `unifiedChatState` store already reads
these fields from navigation state. The `NavigationHandler` already distributes state
to stores. **Zero router changes needed.**

The correct implementation is a helper function:

```
function askAi(question: string, opts?: { affordance?: Affordance; docIds?: string[] }): void {
  const ai = getAiState();
  if (!ai.capabilities.chat) {
    showToast('AI offline — start AI in Brain surface');
    return;
  }
  setUnifiedChatState({
    query: question,
    affordance: opts?.affordance ?? 'documents',
    docIds: opts?.docIds ?? [],
  });
  intentRouter.dispatch({
    address: { kind: 'navigate', target: UNIFIED_CHAT_SURFACE_ID, state: {} },
    transport: 'PROGRAMMATIC',
  });
}
```

This gives every surface one API for AI dispatch. The capability gate is in the helper,
not the router — the dispatching surface doesn't need to check.

### Consumers

- **SearchSurface**: "Ask AI about these results" button calls `askAi(query, { docIds })`
- **InspectorPane**: "Ask" tab calls `askAi(question, { docIds: [selectedDocId] })`
- **Global shortcut** (Ctrl+Shift+A): Opens command input → calls `askAi(input)`
- **BrowseSurface**: Right-click on file → "Ask AI" calls `askAi('', { docIds: [docId] })`

---

## Design D: Conversation as Entity

### Principle

A conversation is a first-class object with identity, metadata, and lifecycle — not a
sessionId string that the chat view generates and forgets.

### Data Model

```
Conversation {
  id: string                    // UUID
  title: string | null          // Auto-generated or user-edited
  createdAt: number             // epoch ms
  lastActiveAt: number          // epoch ms
  messageCount: number
  firstUserMessage: string      // truncated preview
  shapeId: string               // which shape was primary
}
```

The backend's `FileConversationStore.SessionSummary` already provides all of these except
`title`. Title generation is a frontend concern (background LLM call after first response).

### Frontend: ConversationListStore

A separate lightweight store (not AiStateStore — conversations are a different domain).

```
ConversationListStore {
  conversations: Conversation[]   // most recent first
  activeId: string | null         // currently open conversation

  load(): void                    // fetches from GET /api/chat/conversations
  resume(id: string): void        // sets activeId, navigates to chat
  create(): string                // generates new UUID, returns it
  delete(id: string): void        // calls DELETE endpoint
  rename(id: string, title): void // local update + optional persist
  exportMarkdown(id: string): string
}
```

### UI: History Dropdown

A small clock icon button in the chat header. Clicking opens a popover showing the 5-10
most recent conversations:

```
┌────────────────────────────┐
│ Recent conversations       │
├────────────────────────────┤
│ 📝 Summarize Q3 report    │
│    3 messages · 2 hours ago│
│ 📝 What is SPLADE?        │
│    7 messages · yesterday  │
│ 📝 Extract author names   │
│    2 messages · 3 days ago │
├────────────────────────────┤
│ Export as Markdown         │
│ New conversation           │
└────────────────────────────┘
```

Selecting a conversation sets `activeId`, which triggers the backend to load history on
the next dispatch (the engine already does this when `sessionId` is present).

### Auto-Title

**Investigation finding:** Use the SummarizeShape (`core.summarize`) which is EPHEMERAL
(no session persistence). After the first assistant response completes, fire a background
POST to `/api/chat/summarize` with:
```
{ text: '[first user message]\n[first assistant response]',
  prompt: 'Generate a concise 3-5 word title for this conversation.' }
```

The response is a one-shot SSE stream. Parse the text as the title. Store in localStorage
keyed by conversation ID. No session pollution — SummarizeShape is designed for exactly
this kind of one-off inference.

The backend doesn't need to know about titles — they're a FE display concern.

### Export

One-click "Copy as Markdown" in the history dropdown. Format:

```markdown
# [title]

**User**: [message 1]

**Assistant**: [response 1]

**User**: [message 2]
...
```

Uses `navigator.clipboard.writeText()`. Secondary option: download as `.md` file.

---

## Design E: Degraded-Mode Transparency

### Principle

When AI is partially available, show specifically what works and what doesn't. The
anti-pattern is "AI service unavailable" when only embedding is broken.

### Mechanism

The chat header (or a collapsible banner below it) renders capability pills derived from
`AiState.capabilities`:

```
Chat ✓ · RAG ✗ (no documents) · Embedding ⟳ (processing)
```

Rules:
- Only show when at least one capability is false (don't clutter when everything works)
- Each pill links to the relevant action (clicking "RAG ✗" navigates to Library to add
  documents; clicking "Embedding ⟳" shows the indexing progress)
- Pills use the same color scheme as the StatusDeck tier: green/yellow/red

This is a consumer of `AiState.capabilities` — no new store needed.

---

## Implementation Order

```
Design A (framework gating) depends on: nothing new — Shell + Rail + SurfaceCatalog exist
Design B (ambient activity) depends on: nothing new — AiState.activity is shipped
Design C (AI intent routing) depends on: nothing new — just a helper function over navigate intent
Design D (conversation entity) depends on: ConversationListStore + history dropdown
Design E (degraded-mode pills) depends on: nothing new — capabilities object is shipped
```

Suggested order:
1. **E** (degraded-mode pills) — smallest scope, immediate value, validates the
   capabilities object is consumed correctly
2. **B** (ambient activity dot) — small scope, visible from every surface
3. **A** (framework gating) — medium scope, eliminates per-component capability checks
4. **C** (AI intent routing) — medium scope, enables cross-surface AI invocation
5. **D** (conversation entity) — largest scope, requires new store + UI component

---

## §F Follow-on research (2026-05-18)

Two rounds of research after the framework absorption was complete, looking at
what the framework's primitives make cheap and where 2026 AI-app UX has gone.

### Polish (small, rough edges)

- **Pills carry intent, not just navigation.** "RAG unavailable (no documents)"
  pill today navigates to Library — the user still has to click "add files."
  The pill could carry a structured intent (`add-docs`) so one click does the
  job. Same primitive, richer payload.
- **Two "recent conversations" sources** (backend `/api/chat/conversations`
  history and localStorage `jf-chat-recent-sessions` resume prompt) answer
  different questions. They're correctly separate; a one-paragraph comment in
  the store would prevent a future agent from collapsing them.
- **Auto-title can fail silently.** Add a "Generate title" item in the
  per-conversation menu for a retry path. Free, no new primitive.
- **Activity attribution.** Rail dot says "AI is doing something" — could say
  what. External signal: "adaptive changes must be visible, reversible, and
  explainable" (ambient-AI UX principle).

### Extend (using existing primitives)

- **Conversation pinning to a surface.** Pin "Q3 analysis" to Library →
  returning to Library auto-resumes that conversation. `Map<surfaceId, sessionId>`
  in localStorage + a History dropdown item. Fits the thesis exactly.
- **Cross-surface context lock.** When `askAi(query, { docIds })` fires from
  SearchSurface, the chat surface holds those docs as a sticky scope (visible
  "Locked to N docs ✕" chip) until cleared. Currently docIds arrive via nav
  state but are treated as one-shot.
- **Conversation search.** As history grows, indexing `messages.jsonl` as
  documents — JustSearch indexing its own conversation log — closes a meta
  loop with no new substrate.
- **Multi-conversation tabs.** Two or three concurrent conversations in
  UnifiedChatView, each with its own sessionId. `AiState.activity` would need
  to carry sessionId so the rail dot attributes correctly.

### Bigger patterns from 2026 external signal

- **Conversation branching as a first-class primitive.** Per TianPan's
  analysis + ChatGPT's late-2025 "Branch in new chat": linear threads conflate
  three intents — course correction (overwrite), alternative exploration
  (keep both), rollback (revert + preserve original). Mature data model is a
  DAG of immutable messages with pointer-based branches; prefix-shared on
  disk; KV cache stays warm across branches.
- **Agent inbox / async task tray.** Per Hatchworks' agent UX patterns and
  Zylos research on long-running agents: the 2026 shift is from chat-first to
  **taskboard + activity timeline + action receipts**. Useful when AI work
  takes minutes/hours. `AiState.activity` is the seed — extend with task
  identity + queue, render a tray off the rail.
- **Structured intents for `askAi`.** Zed's Agent Client Protocol decouples
  editor UI from agent capability. Our `askAi(question, opts)` is
  shape-flexible; a typed-intent variant (`{ kind: 'summarize-selection',
  selection, docId }`) would let an external agent or plugin emit askAi calls
  without knowing our shape catalog.
- **Voice as input modality.** Voice for AI commands grew 65% YoY. We have
  ORT runtime; a Whisper-tiny would fit. Separate concern but cheap if
  primitives stay decoupled.

### Highest-leverage starting moves

- Pills with intent payloads (tiny diff, real UX win)
- Conversation pinning (one localStorage map, validates surface-catalog-as-substrate on a new feature)
- Branching is the most ambitious but the data-model change is non-trivial —
  needs its own tempdoc

The framework is paying for itself: nearly every idea above is a small
extension to a primitive that's already there, not a fresh subsystem.

**Sources:** TianPan (conversation branching first-class primitive); Hatchworks
(agent UX patterns); Raw.Studio + groovyweb (ambient AI UX trends); builder.io
+ Octave (Zed ACP / cross-surface invocation); Zylos (long-running agents);
thefrontkit (AI chat UI best practices); tldraw (branching chat template).

---

## §G Confidence report — "Conversation as Resource" probe sweep (2026-05-18)

After theorizing the "Conversation as Resource" design, a confidence-building
probe sweep was run before opening a tempdoc around it. Three parallel Explore
agents investigated the load-bearing assumptions. The investigation
**materially reshaped the design** — the original framing was too ambitious
for what the substrate actually permits today. This section records what was
found and the resulting revised position.

### Findings by uncertainty

**U1. Resource governance & cardinality — BLOCKS the original framing**
- 13 production Resources today, all observability or capability-discovery
  (HealthResourceCatalog.java:73, IndexedRootsResourceCatalog.java:78,
  metrics catalogs, OperationHistoryResourceCatalog.java:73, etc.)
- Resources require build-time static registration via `ResourceCatalog`
  implementations aggregated in `RegistryController.java:197-211`. Plugin-
  contributed Resources are slated for V1.5+ but not shipped.
- `slice-execution.md` lines 49-186 require any new Resource instance to
  produce a §A appendix: truth-class audit + vocabulary-creep walk + Category
  fit check. The five existing Categories (STATE / EVENT_STREAM / HISTORY /
  TABULAR / TIMESERIES) do not cleanly fit "conversation branch" or "agent
  task state."
- Precedent: slice 448 retired `LOG_TAIL` and introduced `DiagnosticChannel`
  as a fourth primitive Category specifically to avoid truth-class
  conflation. A similar move would be required for conversations.
- **Implication:** Mapping conversations onto Resources is not a one-tempdoc
  refactor — it's a multi-slice substrate evolution (new Category primitive +
  per-Resource §A audits).

**U2. `Operation.availability` runtime evaluation — BLOCKS**
- Only consumer is `UIOperationEmitter.emit()` at lines 101-108, which
  serializes the availability AST to the FE wire envelope for display.
- AgentToolEmitter does not filter tools by availability. OperationDispatcher
  does not pre-check before dispatch. FE OperationClient does not pre-check.
- All existing Operations initialize `availability = OperationAvailability.empty()`;
  per slice 447 §X.11.2, non-empty values are deferred to a future
  declaration phase.
- **Implication:** The "pills bind to Operations whose `availability` field
  gates capability" claim doesn't work without substrate extension. Pills
  can dispatch arbitrary Intents today (Navigation or Invocation), but
  capability gating remains a separate concern — `AiStateStore.capabilities`
  stays the right place for it, not Operation availability.

**U3. Resource subscription throughput — REFINES (not blocking)**
- SSE envelope is uniform (`SseEnvelope` from slice 436 with seq + payload),
  delivered at `/api/.../stream` endpoints. Validation is structural via
  sealed record constructors, not per-emit JSON Schema validation.
- Existing Resources emit at human-scale cadence (HealthEvent has 15s
  heartbeats + ad-hoc deltas; metrics are timeseries-paced). Decentralized
  per-Resource emitters mean no central bottleneck.
- No fundamental rate limit observed; chat-token-rate emissions are
  technically feasible but would be a step-change in cadence.
- **Implication:** Throughput isn't the blocker. The blocker is U1 — finding
  a Category that fits.

**U4. KV-cache prefix sharing — BLOCKS the branching cost argument**
- `LlamaServerOps.java:240-330` constructs llama-server's argv with
  `--jinja`, `--metrics`, `--reasoning-format`, `--host`, and VRAM
  quantization flags. **Zero KV cache reuse flags.** No `--cache-reuse`,
  `--prompt-cache`, `--n-keep`, or `slot` configuration found.
- Health endpoint reports `slots_idle` / `slots_processing` indicating
  independent slot management without cross-request KV reuse.
- **Implication:** Branching saves disk via prefix-sharing of stored
  messages, but does not save compute on inference. The TianPan argument
  ("KV cache stays warm across branches") doesn't apply to our deployment
  without an llama-server flag addition. This doesn't kill branching, but
  it weakens the "branching is free" claim — it costs full prompt
  re-prefill per branch invocation.

**U5. ChatController → OperationClient absorption gap — REFINES**
- OperationClient: POST `/api/operations/{id}/invoke` → single JSON
  response. No streaming, no abort, no mid-stream events.
- ChatController: SSE with typed event vocabulary (`chunk`, `meta`,
  `progress`, `done`, `error`, plus shape-specific
  `agent.session_started`, `tools.tool_call_proposed`, `rag.citation_matches`),
  implicit abort via connection close.
- Gap dimensions: streaming response, AbortController plumbing, multiplex
  event semantics, error-non-terminal model.
- **Implication:** Absorbing chat dispatch into Operation invocation is
  real substrate work (extend OperationClient with a streaming variant,
  add abort plumbing, define event multiplex protocol). Not impossible,
  but not free.

**U6. CAS over FileConversationStore — CONFIRMS**
- `appendMessage()` writes opaque `Map<String, Object>` to JSONL append-only;
  no fields declared at the storage layer. Adding `id` + `parent_hash` +
  `content_hash` fields requires no storage rewrite, only a contract
  change at the `ConversationStore.java:36` API layer.
- **Implication:** Branching's data-model change is incremental, not a
  parallel-store migration. This is the most pleasant finding.

**U7. Plugin substrate boundaries (tempdoc 508) — REFINES**
- 508 §3.2 defines Commands as the unified projection (Operations + plugin
  commands + shell commands + surface-context commands). Plugins declare
  Commands and UI Contributions (`statusBarItems`, `inspectorTabs`,
  `contextActions`).
- Plugins do **not** declare Resources or Operations directly. Resources
  are external (subscribed via `subscribeResource`). Operations are
  backend-canonical, projected one-way to commands.
- **Implication:** The "plugins contribute Operations + Resources via the
  same catalog" claim is wrong. The plugin substrate is asymmetric:
  plugins consume Resources / dispatch Commands, they don't author
  substrate primitives. ACP integration follows the same pattern — an
  IntentSource consumer, not a primitive contributor.

**U8. Surface renderer thinness — REFINES**
- UnifiedChatView carries 12 reactive properties + 6 instance fields +
  4 subscriptions + 16 state-mutator methods. The `send()` handler alone
  mutates 8+ properties.
- **Implication:** "Surface = pure renderer" is unrealistic. The honest
  framing is "stateful renderer subscribing to framework streams." The
  510 thesis still holds (the framework absorbs cross-cutting concerns);
  just don't pretend the per-surface state goes to zero.

**U9. AgentRunStore vs ConversationStore — REFINES**
- `AgentRunStore.java:24-82` persists `iterationsUsed`, `toolCallsExecuted`,
  `totalTokensUsed`, budget, `agentProfiles`, `handoffHistory`,
  `terminationReason`. Javadoc at `ConversationStore.java:16-18`
  **explicitly** documents the separation: "The agent loop's
  AgentRunStore stays agent-specific — it stores richer data (tool calls,
  traces, handoff state, budget tracking). This interface handles simple
  message threads."
- **Implication:** The separation is deliberate and documented. Unifying
  them under one Resource fights design intent. Agent-inbox UX should
  project from AgentRunStore (via the existing `core.operation-history`
  Resource, which already has Category=HISTORY) — not from a unified
  conversation Resource.

**U10. Duplication claims — not specifically investigated**
- Status: deferred. The "two recent-conversation stores" claim is
  defensible (different questions, different durability). The "two
  activity-tracking systems" claim needs follow-up if it ever matters.

**U11. Resource count — CONFIRMS baseline**
- 13 static + N dynamic (advisory) Resources today. Mapping conversations
  + branches + tasks + scopes onto Resources would roughly triple this
  count, each requiring §A audit. This is the cardinality argument
  underneath U1.

### Revised design position

The "Conversation as Resource" framing is **not the right path** as
originally theorized. Three blockers (U1 Resource governance, U2
availability not evaluated, U4 KV cache not configured), one significant
refine (U5 streaming gap), and a documented design separation (U9
AgentRunStore) together mean the design would be a multi-slice substrate
evolution against the grain of existing intent.

**The unifying primitive that DOES work is Intent, not Resource.** Intents
are cheap to add (new transport tags, new sources, new typed argument
shapes). Conversations stay in their own well-designed substrate
(ConversationStore is CAS-ready per U6). AgentRunStore stays separate per
its deliberate design. Resources stay precious for observability/capability-
discovery. The framework absorbs cross-cutting AI awareness as already shipped
in 510.

### What this means for each follow-on idea

| Idea | Revised home |
|---|---|
| Pills carry intent | Bound to arbitrary Intents (Navigation or Invocation) — works today; capability gating via `AiStateStore.capabilities`, not Operation.availability |
| Conversation pinning | localStorage map; small surface-level feature |
| Cross-surface context lock | Chat-surface UI affordance on existing `unifiedChatState` |
| Multi-conversation tabs | UnifiedChatView extension; per-session activity attribution |
| Conversation search | Backend feature — index `messages.jsonl` as documents |
| **Branching** | Localized feature on ConversationStore (extend `appendMessage` signature for `parent_hash` + `msg_id`); KV cache stays cold per branch invocation but disk-side prefix sharing works |
| Agent inbox | Project from existing AgentRunStore via the existing `core.operation-history` Resource (Category=HISTORY) — already substrate-aligned |
| Structured askAi | TypeScript type refactor on the existing `askAi()` helper; typed argument shapes per intent kind |
| Voice input | New `IntentSource` — fits the existing intent-source abstraction cleanly |
| ACP external agents | New `IntentSource` + `IntentEmitter` pair — fits the existing pattern; the asymmetric plugin model (consume, dispatch, don't author primitives) matches ACP's protocol shape |
| Action receipts | Surface the existing Operation `lineage` + `provenance` fields that are already populated; UI work, not substrate |

### Go/no-go

- **Go** on writing tempdoc 513 around branching as a localized
  ConversationStore extension. Cleanest single feature with high follow-on
  value. CAS-ready per U6. Cost is bounded.
- **Go** on writing tempdoc 514 around an "Intent as universal verb"
  consolidation — typed argument shapes, voice IntentSource scaffolding,
  ACP-shaped IntentSource interface. Validates the framework absorption
  thesis without touching Resources.
- **No-go** on "Conversation as Resource" as a unified refactor. Three
  substrate-level prerequisites would have to land first (new Resource
  Category, Operation.availability evaluator, KV cache config), each
  multi-slice work. The cost-benefit collapses when each follow-on idea
  has a cheaper home in the existing substrate.

### Methodological note

The single most valuable probe was A2 (the `git grep .availability()`
consumer audit). The original design rested on the assumption that
availability evaluation was wired; a 30-second grep falsified it. The
lesson reinforces the CLAUDE.md guidance "verify, don't guess" — applied
to the design-theorization phase, not just the implementation phase. The
A1 governance investigation was the second most valuable. The two
together would have caught the design flaw before the theorization was
written if I had run them first.

For future design theorizations, the heuristic is: **before claiming a
field is consumed or a substrate scales, grep for the reader.**

