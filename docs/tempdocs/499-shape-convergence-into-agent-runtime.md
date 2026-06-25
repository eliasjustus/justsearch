---
title: "499 — Shape convergence into the agent runtime"
type: tempdocs
status: open
created: 2026-05-16
updated: 2026-05-19
category: architecture / conversation substrate
authority: |
  Design exploration. Examines whether the ConversationShape substrate
  should converge into the Agent runtime — making the agent the universal
  shape and promoting RAG retrieval, structured extraction, and other
  pipeline stages into agent tools. No implementation commitments.
status-note: |
  2026-05-19 reassessment. This tempdoc cited tempdoc 498 as "a problem
  this [convergence] dissolves." Tempdoc 498's symptom (cross-shape
  context discontinuity) has since been solved more cheaply: slice 497
  V1.1 ratified and shipped `ExternalContextInjector` (sliding-window
  message forwarding via a `context` request field). Tempdoc 498 is now
  closed `done`. The convergence thesis remains coherent — there is
  still a structural argument that FreeChat / RAGAsk / Extract are
  agent variants with pre-baked tool calls — but the *urgency* is
  reduced: context continuity is fixed, FreeChat persistence works
  without agent infrastructure, and slice 497 V2 explicitly deferred
  "agent convergence." Treat as a strategic forecast, not active work.
  Re-evaluate when (a) a new shape arrives that genuinely needs
  agent-runtime affordances, or (b) the agent runtime's per-session
  overhead becomes negligible enough to consider as the universal path.
related:
  - slices/491-chat-substrate.md (ConversationShape substrate — the system this would replace)
  - slices/497-unified-chat-surface.md (unified surface — the FE half of convergence; V1.1 shipped the lighter answer to 498)
  - slices/495-agent-surface-decomposition.md (AgentSessionController — the target runtime)
  - docs/tempdocs/498-cross-shape-context-threading.md (status: done; the problem this tempdoc claimed to dissolve was solved more cheaply)
---

# 499 — Shape convergence into the agent runtime

## The thesis

JustSearch's four conversation shapes — RAGAsk, FreeChat, Extract,
AgentRun — are not four fundamentally different capabilities. They are
one capability (talk to a local LLM) with three latency-optimized
pipeline specializations bolted on:

| Shape | What it really is | Why it's a separate pipeline |
|-------|-------------------|----------------------------|
| FreeChat | Agent with no tools | Avoids agent runtime overhead |
| RAGAsk | Agent that always calls a "search documents" tool first | Pre-LLM retrieval is faster than waiting for the model to decide to search (saves one LLM round-trip) |
| Extract | Agent that always calls a "validate JSON" tool after each response | Backend validation loop is more reliable than model self-validation |
| AgentRun | The general case | Tool-calling with approval gates |

From the user's perspective, these are all "talk to the AI." The unified
surface (slice 497) already consolidates them behind affordance toggles.
But the backend still dispatches to four separate pipelines with
different SPI compositions, different persistence modes, and different
SSE event vocabularies.

The convergence thesis: collapse all shapes into the agent runtime.
RAG retrieval becomes a tool the agent calls. JSON validation becomes
a tool the agent calls. FreeChat is an agent that happens to call no
tools. The affordance toggles become tool-availability flags on the
agent, not shape selectors.

## What this dissolves

Several current architectural complexities exist specifically because
shapes are separate pipelines:

1. **Cross-shape context threading (tempdoc 498).** Each EPHEMERAL
   shape dispatch is stateless — the backend doesn't see prior turns
   from other shapes. A converged agent runtime has one session with
   full history. The `ExternalContextInjector` workaround becomes
   unnecessary.

2. **Per-shape SSE event vocabularies.** RAGAsk emits `rag.meta`,
   `rag.citations`, `rag.citation_delta`, `rag.citation_matches`.
   Navigate emits `navigate.url_extracted`, `navigate.url_dispatched`.
   The agent emits `tool_call_proposed`, `tool_exec_started`. A
   converged runtime has one event vocabulary — tool calls and their
   results — with tool-specific payloads for citations, navigation
   receipts, etc.

3. **Dual persistence models.** FreeChat and AgentRun are PERSISTENT
   (with different stores — ConversationStore vs AgentRunStore).
   RAGAsk and Extract are EPHEMERAL. A converged agent has one
   session store.

4. **Model-assisted escalation (slice 497 §4 Layer 4).** The "should
   the model suggest switching to RAG?" question disappears. The model
   doesn't switch modes — it calls the search tool when it judges the
   question needs document grounding.

5. **The dynamic dispatch endpoint.** `POST /api/chat/dispatch` reads
   `shapeId` from the body because the FE needs to tell the backend
   which pipeline to use. A converged agent doesn't need this — the
   FE sends a message, the agent decides what tools to use.

## What convergence looks like concretely

### RAG retrieval as an agent tool

Today's pipeline:
```
User question
  → RAGContext.inject() — vector retrieval, chunk formatting
  → system prompt with retrieved context
  → LLM call (one-shot, grounded)
  → StreamingCitationMatcher.onChunk/onDone — citation attribution
  → response with citations
```

Converged pipeline:
```
User question
  → Agent sees available tool: search_documents(query, topK?)
  → Agent decides to call search_documents
  → Tool executes: vector retrieval, returns formatted chunks
  → Agent generates response grounded in tool result
  → Citation attribution runs on tool result + response
  → response with citations
```

The `RAGContext` injector's retrieval logic moves into a
`SearchDocumentsTool` handler. The `StreamingCitationMatcher` moves
into a post-processing step on the tool result. The prompt contributor
(`RAGQAStyle`) becomes part of the agent's system prompt (or a
tool-specific instruction).

**Cost**: one extra LLM round-trip. The model must first decide to
call the search tool, then generate the grounded response. On local
hardware (Qwen3.5-9B), this adds ~1-3 seconds.

**Benefit**: the model decides WHEN to search. A plain question ("tell
me a joke") doesn't trigger retrieval. A document question does. No
affordance toggle needed. The model also sees its own tool call in
context, enabling follow-up questions that reference prior retrievals
naturally.

### Structured extraction as an agent tool

Today's pipeline:
```
User prompt + JSON schema
  → LLM call
  → ValidationConsumer checks output against schema
  → If invalid: ValidatingController returns CONTINUE
    → correction prompt injected, re-run (up to 3x)
  → If valid: STOP_SUCCESS with structured output
```

Converged pipeline:
```
User prompt + JSON schema
  → Agent sees available tool: validate_json(output, schema)
  → Agent generates candidate JSON
  → Agent calls validate_json
  → If invalid: agent sees error, self-corrects, re-calls
  → If valid: agent returns structured output
```

The `ValidationConsumer` + `ValidatingController` pair moves into a
`ValidateJsonTool`. The iteration control shifts from the engine to the
agent's own tool-calling loop.

**Cost**: the agent must learn to self-validate, which may be less
reliable than the deterministic backend loop for weaker models.

**Benefit**: the agent can ask clarifying questions ("your schema
requires a 'name' field but I can't find one — should I infer it?")
rather than silently retrying. The extraction becomes conversational.

### FreeChat as agent-with-no-tools

No change needed. An agent session with an empty tool set IS FreeChat.
The agent runtime already handles this — `ToolIteratingShapeRunner`
with no tools proposed just streams the response.

### Affordance toggles as tool-availability flags

The unified surface's affordance toggles map directly:

| Affordance | Current behavior | Converged behavior |
|------------|-----------------|-------------------|
| None | FreeChat shape | Agent with no tools |
| Documents | RAGAsk shape | Agent with `search_documents` tool available |
| Schema | Extract shape | Agent with `validate_json` tool available |
| Tools | Agent shape (V2) | Agent with full tool palette |

The FE sends `{ availableTools: ["search_documents"] }` instead of
`{ shapeId: "core.rag-ask" }`. The agent runtime decides when and
whether to use the available tools.

## The latency trade-off

This is the central tension. Every pipeline stage promoted to a tool
adds one LLM round-trip:

| Operation | Current latency | Converged latency | Delta |
|-----------|----------------|-------------------|-------|
| RAG question | ~0s (pre-LLM retrieval) + LLM time | LLM decision (~1-3s) + retrieval + LLM grounded response | +1-3s |
| Extract | LLM time + backend validation (~0s) | LLM time + LLM self-validation (~1-3s) | +1-3s |
| FreeChat | LLM time | LLM time | 0 |
| Tool-calling | Agent loop time | Agent loop time | 0 |

On cloud models (fast inference), the delta is negligible. On local
hardware with a 9B model, 1-3 seconds per tool decision is noticeable.

### Mitigations

1. **Eager tool invocation.** If the Documents affordance is active,
   the agent's system prompt says "always search documents before
   answering." This eliminates the decision round-trip — the model
   calls the tool on the first turn without deliberation. Functionally
   equivalent to today's pre-LLM injection, but within the agent
   runtime.

2. **Parallel tool execution.** The agent proposes tool calls; the
   runtime executes them in parallel. For RAG, this means retrieval
   starts immediately when the tool call is proposed, overlapping with
   any other tool calls.

3. **Progressive disclosure.** Show the user "Searching documents..."
   while the tool executes, then stream the grounded response. The
   extra round-trip is visible as a tool-call card, not dead air.

## When to converge

The convergence is not urgent. The current substrate works and the
unified surface papers over the shape fragmentation at the UX layer.
Convergence becomes compelling when:

- **Local model speed improves** such that the extra round-trip is
  imperceptible (<500ms tool-decision latency)
- **More tool-like capabilities are added** (web search, calculator,
  code execution) where the agent runtime is the natural host
- **Cross-shape context threading** (tempdoc 498) proves insufficient
  and full session continuity is needed
- **The affordance-toggle UX feels limiting** — users want the model
  to decide, not to pre-select capabilities

Until then, the current design (unified surface + per-shape backend
dispatch) is the pragmatic answer. This tempdoc captures the
convergence direction so future work doesn't accidentally diverge
from it.

## Open questions

1. **Should eager tool invocation be the default?** If the Documents
   affordance always triggers `search_documents` without model
   deliberation, the converged pipeline has identical latency to
   today. But this makes the tool-calling formalism overhead with no
   model-agency benefit. Is that acceptable as a stepping stone?

2. **How do citation attribution and streaming interact with
   tool-calling?** Today's `StreamingCitationMatcher` runs on the
   LLM's streaming output. In the converged model, the tool result
   (retrieved documents) is available before streaming starts. Citation
   attribution could run on the tool result + streamed response. Does
   this change the attribution quality?

3. **What is the approval model for implicit tools?** Today, agent
   tool calls go through approval gates (auto-approve, inline confirm,
   typed confirm). If `search_documents` is an implicit tool triggered
   by the Documents affordance, should it still require approval? The
   user already signaled intent via the affordance toggle.

4. **Can the current AgentSessionController host non-tool-calling
   sessions efficiently?** A FreeChat-equivalent agent session with no
   tools should be as fast as today's FreeChat. Does the agent runtime
   add overhead (session creation, tool palette resolution, approval
   gate initialization) that makes this measurably slower?
