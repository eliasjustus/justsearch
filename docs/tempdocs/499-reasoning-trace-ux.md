---
title: "Tempdoc 499 — Reasoning trace UX"
type: tempdocs
status: active
created: 2026-05-16
updated: 2026-05-19
source-id: G128
category: UX / reasoning infrastructure
authority: design for reasoning as a first-class UX concept — shared controller, markdown rendering, accessibility, thinking budget control
related:
  - slices/486-consumer-feature-discovery.md R3.A (G128 entry — `shipped`)
  - slices/486-consumer-feature-discovery.md R6.2 (closure row, 2026-05-16)
  - slices/491-chat-substrate.md (ConversationShape substrate)
  - slices/495-agent-surface-decomposition.md (AgentSessionController pattern)
prior-work: |
  Multi-channel streaming infrastructure shipped 2026-05-16 (4 commits on main):
  unified stream(StreamRequest, StreamSink) interface, reasoning_chunk SSE events
  in all 8 shapes, <jf-reasoning-block> component, FreeChatView + AgentView
  integration. Live-verified: reasoning tokens flow end-to-end with
  JUSTSEARCH_REASONING_BUDGET=-1.
status-note: |
  2026-05-19 reclassification. The G128 core substrate + UX (ReasoningController
  shared state machine, ReasoningBlock with markdown + accessibility + live timer
  + copy button, per-request enableThinking toggle in FreeChatView, backend
  SamplingParams pass-through, all 8 views wired) shipped on 2026-05-16 and is
  recorded as closed in 486 R6.2. What remains "open" is §5 Future Directions:
  - §5.x Token-split visualization (reasoning vs content via onUsage callback —
    requires backend to separate reasoning_token from content_token, which
    llama-server may not provide today).
  - §5.4 Cross-turn reasoning timeline (visualization over the reasoningBlocks
    array; substrate present, viz missing).
  - Other §5 polish items.
  Treat the tempdoc as the design rationale for the deepening pass when one is
  scheduled; the core G128 capability is in production.
---

# Tempdoc 499 — Reasoning trace UX

## 1. Problem statement

The multi-channel streaming infrastructure works — reasoning tokens flow
from llama-server through the backend to the frontend. But the UX is
V1-minimum:

1. **Duplicated state** — FreeChatView and AgentSessionController each
   implement the same reasoning state machine (`isThinking`,
   `reasoningText`, `thinkingStartedAt`, block accumulation). Four other
   views don't support reasoning at all. Every new view must re-implement
   the same pattern.

2. **Plain text rendering** — Reasoning is displayed as monospace
   pre-wrapped text, but models produce structured thinking with numbered
   steps, bold markers, and bullet points. The existing `MarkdownBlock`
   component (with sanitization, streaming mend-pass, rAF throttle)
   renders markdown correctly but is not used.

3. **No accessibility** — The reasoning block has no ARIA attributes, no
   keyboard support. A screen reader cannot operate it.

4. **No user control** — Thinking is controlled by a server-startup
   environment variable (`JUSTSEARCH_REASONING_BUDGET`). Users cannot
   enable or disable thinking per-request. The `SamplingParams` system
   already supports per-request `enableThinking`, but no UI exposes it.

5. **No live feedback** — The block shows the final duration ("Thought
   for 4s") but no live elapsed counter during streaming.

These are not polish items — they are structural gaps between a working
pipe and a correct UX.

## 2. Correct design

### 2.1 ReasoningController — shared state machine

**Pattern precedent**: `AgentSessionController` is a framework-agnostic
TypeScript class that encapsulates complex state (14 SSE mutation paths,
tool call tracking, session lifecycle) separate from the Lit view. This
is the validated pattern for shared streaming state in this codebase.

**Design**: A `ReasoningController` class that owns the reasoning state
machine. Any view that wants reasoning support creates one and wires it:

```typescript
export class ReasoningController {
  // --- Observable state ---
  reasoningText = '';
  isThinking = false;
  reasoningBlocks: Array<{ text: string; durationMs: number }> = [];

  // --- Private ---
  private thinkingStartedAt: number | null = null;
  private timerInterval: number | null = null;
  private readonly onUpdate: () => void;

  constructor(onUpdate: () => void) {
    this.onUpdate = onUpdate;
  }

  /** Wire as the onReasoningChunk handler in any shape's event dispatch. */
  handleReasoningChunk(payload: unknown): void {
    const data = payload as Record<string, unknown>;
    if (!this.isThinking) {
      this.isThinking = true;
      this.thinkingStartedAt = Date.now();
      this.startTimer();
    }
    this.reasoningText += (data.text as string) ?? '';
    this.onUpdate();
  }

  /** Call when the first content chunk arrives (ends thinking phase). */
  endThinking(): void {
    if (!this.isThinking) return;
    this.isThinking = false;
    this.stopTimer();
    const duration = this.thinkingStartedAt
      ? Date.now() - this.thinkingStartedAt
      : 0;
    if (this.reasoningText) {
      this.reasoningBlocks.push({ text: this.reasoningText, durationMs: duration });
    }
    this.reasoningText = '';
    this.thinkingStartedAt = null;
    this.onUpdate();
  }

  /** Call on stream done/error to finalize any in-progress reasoning. */
  finalize(): void {
    this.endThinking();
  }

  /** Call before each new send to reset state. */
  reset(): void {
    this.reasoningText = '';
    this.isThinking = false;
    this.thinkingStartedAt = null;
    this.reasoningBlocks = [];
    this.stopTimer();
  }

  /** Elapsed seconds since thinking started (for live timer). */
  get elapsedSeconds(): number {
    if (!this.thinkingStartedAt) return 0;
    return Math.round((Date.now() - this.thinkingStartedAt) / 1000);
  }

  destroy(): void {
    this.stopTimer();
  }

  private startTimer(): void {
    this.stopTimer();
    this.timerInterval = window.setInterval(() => this.onUpdate(), 1000);
  }

  private stopTimer(): void {
    if (this.timerInterval !== null) {
      window.clearInterval(this.timerInterval);
      this.timerInterval = null;
    }
  }
}
```

**Integration in a view** (e.g., FreeChatView):
```typescript
private reasoning = new ReasoningController(() => this.requestUpdate());

// In event handlers:
const handlers = {
  onReasoningChunk: (p: unknown) => this.reasoning.handleReasoningChunk(p),
  onChunk: (p: unknown) => {
    this.reasoning.endThinking();
    // ... existing chunk logic
  },
  onDone: () => {
    this.reasoning.finalize();
    // ... existing done logic
  },
};

// In send():
this.reasoning.reset();

// In render():
${this.reasoning.isThinking || this.reasoning.reasoningBlocks.length > 0
  ? html`<jf-reasoning-block .controller=${this.reasoning}></jf-reasoning-block>`
  : nothing}
```

**What this eliminates**: the duplicated `isThinking`, `reasoningText`,
`thinkingStartedAt`, `reasoningBlocks` declarations and the identical
state-transition logic in every view. New views get reasoning by adding
3 lines (construct controller, wire handlers, render component).

### 2.2 ReasoningBlock enhancements

The component evolves from a plain-text dump to a production-quality
reasoning display:

**Markdown rendering**: Replace the `<div class="content">${this.text}</div>`
with a composed `<jf-markdown-block>`. This gives:
- Numbered lists rendered as proper HTML lists
- Bold/italic emphasis
- Code blocks with syntax highlighting
- Sanitization via DOMPurify (already in MarkdownBlock)
- Streaming mend-pass for unclosed syntax (already in MarkdownBlock)

**Accessibility**:
- Header toggle: `role="button"`, `tabindex="0"`, `aria-expanded="${!this.collapsed}"`
- Keyboard: Space/Enter on header toggles collapse
- `aria-label="Model reasoning trace"` on the container

**Live timer**: The controller's `elapsedSeconds` getter updates every
second (driven by the controller's `setInterval`). The header shows
"Thinking (3s)" during streaming, "Thought for 4s" when done.

**Copy button**: A small clipboard icon in the header that copies the
reasoning text. Uses `navigator.clipboard.writeText()`.

**Controller binding**: Instead of individual properties, the component
receives the entire `ReasoningController` and reads its state:

```typescript
export class ReasoningBlock extends LitElement {
  static properties = {
    controller: { attribute: false },
  };
  declare controller: ReasoningController;
}
```

This eliminates the parent-child state synchronization problem (the
previous `?collapsed` fight). The component reads from the controller;
internal UI state (collapsed toggle) stays component-local.

### 2.3 Thinking budget as two-layer control

**Current state**: `JUSTSEARCH_REASONING_BUDGET` is a server-startup env
var (default: 0). With budget=0, no reasoning tokens are produced
regardless of per-request settings. The only way to enable reasoning is
to restart the server with `-1`.

**Constraint discovered**: Changing the default to -1 is dangerous for
the agent loop. With `DEFAULT_MAX_TOKENS = 1024` and unlimited reasoning
budget, the model can spend all 1024 tokens on thinking and produce zero
content/tool calls. The guard catches this (EMPTY_RESPONSE error at
AgentLoopService:928) but doesn't prevent it — users see an error
instead of a response.

**Constraint discovered**: The ConversationEngine currently hardcodes
`sampling=null` in the StreamRequest (2-arg constructor). There is NO
mechanism to pass `enableThinking` from the UI through to the LLM for
substrate-driven shapes. Per-request control requires backend plumbing.

**Correct design**: Two independent layers:

1. **Server-level capability** (`--reasoning-budget`): Keep default at 0.
   The server admin sets `JUSTSEARCH_REASONING_BUDGET=-1` to enable
   reasoning capability. This is a one-time infrastructure decision, not
   a per-request UX preference. Avoids the agent token-exhaustion risk
   for deployments that don't want reasoning.

2. **Per-request preference** (`SamplingParams.enableThinking`): The UI
   exposes a "Show thinking" toggle. When the toggle is on, the request
   body includes `enableThinking: true`. The backend reads this and
   passes it via SamplingParams to the StreamRequest.

**Backend plumbing needed** (ConversationEngine):
- Read `enableThinking` from the request body map
- Construct a `SamplingParams` with the value
- Use the 3-arg `StreamRequest(messages, maxTokens, sampling)` constructor
- ~20 lines of change in `dispatchSubstrateDriven()`

**UI location**: A toggle in each chat surface's header bar. Persistent
per surface via localStorage. When the server budget is 0, the toggle is
present but non-functional (reasoning infrastructure is disabled at the
server level). No special UI indicator needed — absence of reasoning
blocks is self-evident.

**Why not change the default to -1**: The agent loop's 1024-token budget
was calibrated with reasoning-budget=0. Unrestricted reasoning within
that budget causes token exhaustion in ~20% of runs (per tempdoc 227
empirical data). The server default protects all callers; per-request
opt-in shifts risk to the user who explicitly wants thinking.

### 2.4 Universal view coverage

With the `ReasoningController` extracted, adding reasoning to the
remaining views is mechanical:

| View | Integration effort |
|------|-------------------|
| AskView | Create controller, wire 3 handlers, render component |
| SummarizeView | Same |
| NavigateView | Same |
| ExtractView | Same |
| UnifiedChatView | Same (needs controller per active shape) |
| FreeChatView | Refactor: replace inline state with controller |
| AgentView | Refactor: replace AgentSessionController reasoning fields with composed ReasoningController |

The AgentSessionController refactor is the most interesting: it currently
has reasoning fields (`reasoningText`, `isThinking`, `thinkingStartedAt`,
`reasoningBlocks`) mixed into its 14-handler state machine. Extracting
these into a composed `ReasoningController` cleans the boundary — the
session controller owns agent-specific state (tool calls, sessions,
handoffs), the reasoning controller owns reasoning state.

## 3. Design rationale

### Why a controller class, not a mixin?

Mixins in TypeScript/Lit are fragile — they create diamond inheritance
problems, complicate typing, and are hard to test independently. The
controller pattern (validated by AgentSessionController) is:
- Framework-agnostic (pure TypeScript, no Lit dependency)
- Independently testable (mock the onUpdate callback)
- Composable (a view can have multiple controllers)
- Explicit (no hidden prototype chain)

### Why markdown, not plain text?

Models like Qwen3 produce structured reasoning: "1. **Analyze the
Request:** ..." with numbered steps and bold markers. Plain text renders
this as flat strings. Markdown renders it as proper numbered lists with
emphasis — dramatically more readable. The MarkdownBlock component
already exists with streaming support, sanitization, and mend-pass for
unclosed syntax. The cost is one component swap; the benefit is
reasoning that reads like a structured document rather than a log dump.

### Why NOT change the default reasoning budget?

The current default (`--reasoning-budget 0`) was chosen to prevent
reasoning exhaustion in the agent loop. Investigation confirmed this is
correct: with `DEFAULT_MAX_TOKENS = 1024` and unlimited reasoning, the
model spends all tokens on thinking in ~20% of runs (tempdoc 227 data).
The guard at AgentLoopService:928 catches empty responses but doesn't
prevent the user-visible error. Keeping the default at 0 protects all
callers; per-request `enableThinking` lets users who set the budget to
-1 control thinking on a per-request basis.

### Why per-request toggle, not per-session?

A per-session toggle would require session state management. A per-request
toggle is simpler (a field in the POST body), stateless on the server, and
matches the `SamplingParams` model that already exists. The UI persists
the user's preference in localStorage; the backend sees it as just another
sampling parameter.

## 4. Phasing

### Phase 1: ReasoningController + view refactor
- [ ] Create `ReasoningController` class
- [ ] Refactor FreeChatView to use controller (remove inline state)
- [ ] Refactor AgentSessionController to compose ReasoningController
- [ ] Wire remaining views (Ask, Summarize, Navigate, Extract, UnifiedChat)
- [ ] Verify: all views accumulate reasoning from `reasoning_chunk` events

### Phase 2: ReasoningBlock enhancements
- [ ] Swap plain text for MarkdownBlock composition
- [ ] Add ARIA attributes and keyboard support
- [ ] Add live elapsed timer (driven by controller's setInterval)
- [ ] Add copy-to-clipboard button
- [ ] Verify: accessibility audit, visual verification

### Phase 3: Thinking budget UI control
- [ ] Backend: ConversationEngine reads `enableThinking` from request body
- [ ] Backend: construct SamplingParams and use 3-arg StreamRequest constructor
- [ ] Frontend: add "Show thinking" toggle to chat surface headers
- [ ] Frontend: include `enableThinking` in the POST body when toggle is on
- [ ] Frontend: persist toggle preference per surface in localStorage
- [ ] Verify with `JUSTSEARCH_REASONING_BUDGET=-1`: toggle on → reasoning; off → none

## 5. Future directions (out of scope, documented for context)

### 5.1 Per-step expandable cards

Parse reasoning into discrete numbered steps and render each as a
collapsible card with "thinking → complete" transitions. Requires
heuristic step-boundary detection (numbered lists, blank lines). Fragile
across model families; deferred until step-marker format stabilizes.

Reference: Vercel AI SDK `ChainOfThought` component, DeepSeek web UI.

### 5.2 Dynamic thinking labels

Parse the reasoning stream for phase keywords ("Analyzing...",
"Planning...", "Verifying...") and update the header label in real-time.
Lightweight string matching; no LLM call. Deferred as cosmetic polish.

### 5.3 Reasoning-aware token budget display

Show thinking vs. content token split using the `onUsage` callback.
Requires backend to separate reasoning token count from content token
count in the usage report — llama-server may not provide this breakdown.

### 5.4 Reasoning across turns

A "reasoning timeline" view showing all thinking blocks across a
multi-turn conversation. The `reasoningBlocks` array already supports
this; the visualization is the missing piece.
