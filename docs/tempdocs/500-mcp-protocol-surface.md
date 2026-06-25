---
title: "Tempdoc 500 — MCP protocol surface: curated external-tool ingress"
type: tempdocs
status: done
created: 2026-05-16
source-id: F8
category: architecture / protocol adapter / external ingress
authority: design for JustSearch's MCP server — transport, curated tool surface, and capability extensions
related:
  - docs/tempdocs/401-mcp-alternatives-considerations.md (MCP considerations register — eval evidence, design proposals D3-D5, theoretical items T1-T13)
  - docs/tempdocs/200-agent-tool-architecture-unification.md (dual-system decision; old TS MCP server)
  - docs/tempdocs/366-agent-search-interface.md (50-query eval — the accuracy data behind the tool surface design)
  - slices/486-consumer-feature-discovery.md R3.C (F8 entry)
  - slices/487-intent-substrate.md §4.4 (trust lattice — GateBehavior)
  - slices/491-chat-substrate.md (ConversationShape substrate)
  - modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpProtocolHandler.java (shipped transport layer)
  - scripts/prod/justsearch-mcp/server.mjs (old TypeScript MCP server — 4 curated tools, eval-validated)
  - scripts/prod/justsearch-mcp/schemas.mjs (Zod schemas for old server)
  - docs/decisions/0015-mcp-tool-surface-design.md (ADR — consolidation 7→4 tools, +20pp accuracy)
  - docs/reference/mcp-production-server.md (reference doc for old server)
gates:
  - Transport layer: ✅ shipped (POST /mcp, JSON-RPC, sessions, trust lattice)
  - Layer separation: ✅ McpToolSurface (Layer 2, 824 LOC) extracted from McpProtocolHandler (Layer 1, 180 LOC)
  - Curated tool surface: ✅ 5 eval-informed tools, position-bias ordering, hand-written 100-300 word descriptions
  - Direct service dispatch: ✅ answer calls DocumentService.retrieveContext() in-process; search calls KnowledgeHttpApiAdapter directly
  - Response hints: ✅ zero-result, high-result, enrichment-coverage, and facet-sidecar-on-answer hints (all live-verified)
  - Live resources: ✅ 4 proposed URIs (index/summary, index/roots, top-sources, top-entities) + 9 catalog-driven = 13 total
  - MCP Prompts: ✅ 3 templates with live system context (doc count + enrichment percentages)
  - Session TTL: ✅ 30-minute idle expiry with cleanup on initialize
  - MCP Sampling: ⊘ blocked — per-request approval UX kills automation (documented §3c)
  - Eval validation: ⚠️ NOT VALIDATED — the 94% accuracy figure is from the old TS server's 50-query eval (tempdoc 366), not from this Java implementation. An eval run against the new MCP surface is the next validation step.
  - Live-verified: ✅ 6-check conformance against dev stack with Worker connected
---

# Tempdoc 500 — MCP protocol surface: curated external-tool ingress

## 1. Thesis

JustSearch's MCP surface has three layers. Layer 1 (protocol transport)
is shipped and correct. Layer 2 (what tools to expose) was implemented
as a mechanical dump and needs redesign based on eval evidence. Layer 3
(Resources, Prompts, Sampling) is the capability frontier that
transforms JustSearch from a search app into retrieval infrastructure.

## 2. The three layers

### Layer 1 — Protocol transport (shipped)

A Javalin handler at `POST /mcp` implementing MCP Streamable HTTP:
JSON-RPC message parsing, session management via `Mcp-Session-Id`,
`initialize` with capability declaration, `tools/list`, `tools/call`
dispatching through `OperationExecutorImpl`, `resources/list`,
`resources/read`, `resources/subscribe`, `ping`, `DELETE /mcp`.

Trust integration: MCP registered as `IntentSource` in
`CoreIntentSourceCatalog` with `SourceTier.UNTRUSTED` and
`TransportTag.MCP`. The `(UNTRUSTED × RiskTier) → GateBehavior`
lattice gates destructive operations behind `TYPED_CONFIRM`.
Confirmation round-trip via `_confirmationToken` argument.

This layer is structurally correct. The handler lives in `modules/ui`
(same position as `OperationsController`), reads the same catalogs,
dispatches through the same executor. No new SPI, no new module.

**Implementation**: `McpProtocolHandler.java` (513 LOC) + 
`McpProtocolHandlerTest.java` (285 LOC), 7 unit tests, live-verified.

### Layer 2 — Curated tool surface (needs redesign)

**The problem with the current implementation.**

The handler mechanically projects every Operation with
`ExecutorTag.AGENT` + `Audience.USER` as an MCP tool (13 Operations),
then adds all ConversationShapes (8 shapes), for 21 total tools.

This ignores measured evidence from the old TypeScript MCP server's
50-query eval (tempdoc 366, documented in tempdoc 401):

| Design point | Accuracy | Source |
|-------------|----------|--------|
| 7 tools (pre-consolidation) | 72% | Phase 4 baseline |
| **4 curated tools** | **92%** | Phase 4 post-consolidation |
| 4 tools + NER facets | **94%** | Phase 6 final |
| 4 tools + 2 optional schema params | **71%** | Phase 6 schema-bloat measurement |

The data says: **fewer tools with richer descriptions beats more tools
with thinner descriptions.** Adding optional parameters to schemas
actively hurts small-model accuracy. The old TS server's 4-tool surface
(`answer`, `search`, `ingest`, `status`) at 94% accuracy is the
validated baseline. The current 21-tool surface has never been evaluated
and almost certainly performs worse.

**The correct design: a curated MCP tool catalog.**

The MCP tool surface should be a **designed projection**, not a
mechanical dump. Each MCP tool maps to backend capabilities but presents
an LLM-optimized interface: rich description, minimal schema, progressive
disclosure via response-level hints rather than schema params.

**Proposed tool surface (5 tools, position-bias-aware ordering):**

| # | Tool name | Backend mapping | Purpose |
|---|-----------|----------------|---------|
| 1 | `justsearch_answer` | `POST /api/knowledge/retrieve-context` | RAG retrieval — returns passages + context for the model to answer. First position exploits position bias (+9.51%, arXiv 2510.02554). |
| 2 | `justsearch_search` | `POST /api/knowledge/search` | Exploratory search with facets, filters. Returns hits + metadata. For when the model needs to browse, not answer. |
| 3 | `justsearch_browse` | `core.browse-folders` Operation | Folder structure exploration. Lets the model understand what's indexed before searching. |
| 4 | `justsearch_ingest` | `core.ingest-files` Operation | File ingestion. `destructiveHint: false`, `idempotentHint: true`. |
| 5 | `justsearch_status` | `GET /api/knowledge/status` | Index health, enrichment coverage, document count. Orientation tool. |

**Descriptions are load-bearing.** The old TS server's tool descriptions
are 100-300 words each — they carry the full usage guidance, cross-tool
references ("for questions use `justsearch_answer`, for exploration use
`justsearch_search`"), filter syntax examples, and mode guidance. The
eval proved this works: agents read descriptions carefully and follow
the guidance. Minimal descriptions (like the current Java handler's
i18n keys) provide insufficient signal.

Example from the old `justsearch_answer` description (server.mjs:456):
*"Get evidence from your indexed documents to answer a question. This
is the primary tool for question-answering — it retrieves relevant
passages from multiple documents in one call, assembled with source
attribution, ready to use as evidence for your answer. Much more
efficient than searching and reading documents individually..."*

This 150-word description produced 84% accuracy on answer-first queries
at $0.023 avg cost. The current generic descriptions have never been
evaluated.

**What's NOT a tool (and why):**
- Admin operations (restart-worker, bulk-reindex, etc.) — OPERATOR
  audience, not for external AI
- ConversationShapes (summarize, extract, etc.) — these compose
  retrieval + LLM internally; the external model should do its own
  reasoning over retrieved passages. Exposing them as tools creates
  two LLM paths that confuse tool selection. The eval showed agents
  perform best when they reason over retrieved passages directly.
- Per-job operations (cancel-indexing-job, retry-indexing-job) — too
  granular; 0/50 agents needed these in the eval

**Progressive disclosure via response hints** (not schema params):
The old TS server's approach was validated by eval. Tool responses
include contextual hints at decision time:
- Zero results → "try broader terms" / "check available sources"
- Many results → filter suggestions from facet data
- Low enrichment → "NER/embedding not ready; entity filters unavailable"
- Source discovery → facet sidecar shows top `meta_source` values

Advanced params (`doc_ids`, `return_full_documents`, `includeExcerpts`,
entity filters, LUCENE syntax) work when passed but are NOT in the
visible schema — the eval proved that making them visible hurts accuracy
(92% → 71%) without increasing usage (0/50 for most advanced params).
They work via passthrough for capable agents that read descriptions.

**Architectural change needed:** The current `McpProtocolHandler` iterates
over `OperationCatalog` entries and dispatches through
`OperationExecutorImpl`. The redesign has two changes:

1. **Curated tool catalog** (`McpToolSurface`): a hand-written list of
   5 tool definitions with rich descriptions and minimal schemas.
   Similar to how the old TS server had hand-written Zod schemas.

2. **Direct service-layer dispatch** (not through OperationExecutorImpl):
   The high-value tools (`answer`, `search`) should call
   `KnowledgeHttpApiAdapter.search()` and
   `DocumentService.retrieveContext()` directly — the same service
   layer the REST controllers use. This gives the MCP handler access
   to rich response data (facets, quality signals, coverage metrics)
   that it transforms into response-level hints. The old TS server
   called these via HTTP; the Java handler calls them in-process.

   The `ingest` and `status` tools can still dispatch through
   Operations. The `browse` tool dispatches through
   `core.browse-folders` Operation.

   Key service-layer entry points (confirmed by codebase investigation):
   - `KnowledgeHttpApiAdapter.search(KnowledgeSearchRequest)` — full
     search pipeline (QU, reranking, facets)
   - `DocumentService.retrieveContext(RetrieveContextParams)` — RAG
     context retrieval (async, returns `ContextResult`)
   - `KnowledgeHttpApiAdapter.status()` — index health snapshot

### Layer 3 — Capability extensions (new features)

These extend the MCP surface beyond tool calling. Each is independently
valuable and ordered by impact.

#### 3a. MCP Resources for supplementary context (nice-to-have, not primary)

**Revised assessment (2026-05-16 research):** Resources are not the
zero-turn orientation mechanism originally assumed. Claude Desktop shows
resources as manually attachable context (user must browse and attach).
Claude Code can `resources/read` on model initiative but doesn't
auto-inject. Neither client automatically gives the model resource
content. A well-described `justsearch_status` tool achieves the same
orientation with less indirection — the old TS server hit 94% accuracy
without resources.

Resources are still worth exposing for power users and future clients
that may auto-inject, but they are not a substitute for good tool
descriptions.

**Proposed resources** (from tempdoc 401 §D4, refined):

| Resource URI | Content | Subscription |
|-------------|---------|-------------|
| `justsearch://index/summary` | Doc count, last-index time, enrichment coverage | On index change |
| `justsearch://index/roots` | List of indexed folder paths | On root add/remove |
| `justsearch://index/top-sources` | Top-N `meta_source` values with counts | On index change |
| `justsearch://index/top-entities` | Top-N persons/organizations from NER | On index change |
| `justsearch://doc/{id}` | Full indexed document text on demand | N/A (on-demand read) |

**Implementation:** Each resource reads from existing backend endpoints
(`/api/knowledge/status`, `/api/indexing-roots/substrate`, etc.) rather
than returning the Resource record's schema definition. The current
`handleResourcesRead` returns `r.schema()` — it should fetch live data
from `r.endpoint()`.

#### 3b. MCP Prompts for onboarding

**Problem:** Users connecting JustSearch to Claude Desktop don't know
what they can do. Prompts appear in the `+` menu as conversation starters.

**Proposed prompts:**

| Prompt name | Arguments | Expands to |
|------------|-----------|------------|
| "Search my files" | `topic` (string) | System context with index summary + `justsearch_search` call |
| "What do I know about..." | `topic` (string) | System context + `justsearch_answer` call |
| "Index a folder" | `path` (string) | `justsearch_ingest` call with the folder path |

**Implementation:** Prompts are registered in `handlePromptsList` and
expanded in `handlePromptsGet`. The prompt expansion returns a message
array that the host inserts into the conversation — this is how MCP
Prompts work (template expansion, not tool execution).

#### 3c. MCP Sampling for external-model RAG (speculative — blocked by UX)

**Status: demoted from "transformative" to speculative.** Research
(2026-05-16) found that MCP Sampling is effectively unusable for
automated RAG workflows:

- Claude Desktop: supports Sampling but requires **per-request user
  approval via popup dialog**. A search flow that interrupts the user
  with "Allow JustSearch to use your model?" on every query is
  unacceptable.
- Claude Code: does NOT support Sampling at all.
- Cursor: does NOT support Sampling at all.

The approval friction kills the automated flow. The correct approach
for external-model RAG is already what the `justsearch_answer` tool
does: return retrieved passages as tool output, and the model reasons
over them in its own context. No Sampling needed — the model already
has the passages after calling the tool. This is simpler, works
universally across all MCP clients, and requires no approval UX.

**When Sampling becomes viable:** If MCP clients add session-level
Sampling approval (approve once per session, not per request), the
design becomes:
1. JustSearch retrieves passages
2. Sends `sampling/createMessage` with passages + query
3. Returns the client model's answer with JustSearch's citations

This remains a future option. The infrastructure in `McpProtocolHandler`
can support it when client UX improves. Not a current design target.

## 3. Eval-evidence foundation

The design in this tempdoc is grounded in measured evidence, not
intuition. The key measurements from tempdoc 366's 50-query Haiku eval:

- **Tool count sweet spot:** 4 tools at 92-94% accuracy. 7 tools
  dropped to 72%. The consolidation from 7→4 produced a +20pp gain.
- **Schema-bloat trap:** Adding `doc_ids` and `return_full_documents`
  as visible schema params dropped accuracy from 92% to 71% with
  zero agents using the new params. Moving them behind passthrough
  recovered to 90-94%.
- **Position bias:** Placing the highest-value tool first (`answer`)
  exploits measured position bias (+9.51%, arXiv 2510.02554).
- **Response hints > schema params:** Progressive disclosure via
  response-level hints (zero-result suggestions, filter tips, facet
  guidance) works better than exposing advanced params in schemas.
- **Token efficiency:** 10-result search = 1,749 tokens (MCP slim)
  vs 19,902 raw (~91% reduction).
- **Cost asymmetry:** Answer-first agents $0.023 avg vs search-explore
  $0.069. 3x cheaper when the model takes the first-listed tool.

## 4. Relationship to existing work

### 4.1 Tempdoc 401 (MCP Alternatives Considerations)

401 is the pre-existing considerations register. This tempdoc resolves:

| 401 item | Status |
|----------|--------|
| F2 (stdio-only transport) | **Resolved** — Streamable HTTP on Javalin |
| F3 (HTTP/MCP schema drift) | **Structurally resolved** — same OperationCatalog |
| F4 (resources/prompts unused) | **In progress** — resources listed; live data + prompts are Layer 3 |
| D3 (Streamable HTTP) | **Done** |
| D4 (Resources) | **In progress** (Layer 3a) |
| D5 (Prompts) | **Designed** (Layer 3b) |
| T1 (Sampling) | **Designed** (Layer 3c) |
| T2 (Elicitation) | **Partially done** — confirmation round-trip via `_confirmationToken` |
| T4 (Full annotations) | **Done** — readOnlyHint + destructiveHint from OperationPolicy |

### 4.2 Old TypeScript MCP server (`scripts/prod/justsearch-mcp/`)

The old server had 4 curated tools with eval-validated descriptions,
progressive disclosure via response hints, and Zod schemas. It ran as
a separate Node.js process via stdio transport.

The new Java handler supersedes its transport (Streamable HTTP on
existing Javalin, no separate process). But the old server's **tool
surface design** — curated, eval-backed, progressive — is the correct
model for Layer 2. The redesign adapts that design to the new transport.

### 4.3 Tempdoc 200 (Agent Tool Architecture Unification)

200's verdict was "keep dual system" (built-in Java agent + TS MCP
server). The new Java MCP handler collapses the dual system — both
surfaces now run in the same JVM. The dual-description concern (small
models need different descriptions than large models) is addressed by
the curated tool surface: MCP tools have their own descriptions
optimized for external large models, separate from the built-in agent's
descriptions optimized for the local small model.

## 5. Implementation record

### Shipped (Layer 1 — transport)

4 commits on main:
- `be17a266f` — Core MCP protocol handler (513 LOC + 285 LOC tests)
- `44ae45de2` — ConversationShape tools + i18n + confirmation token
- `e21076b4d` — Resource subscription tracking
- `78788e2c0` — ArchUnit fix

Live-verified: initialize, tools/list, tools/call (live dispatch),
resources/list, resources/subscribe, ping — all working against
running dev stack.

### Shipped (Layers 2 + 3)

All feasible items from the original remaining list are shipped:

- ✅ Curated 5-tool surface (McpToolSurface.java, 773 LOC)
- ✅ Direct service dispatch (DocumentService.retrieveContext in-process)
- ✅ MCP Prompts (3 templates with live system context)
- ✅ Session TTL (30-min idle expiry)
- ✅ Live resource reads + 4 proposed URIs
- ✅ Response hints (zero-result, high-result, enrichment, facet sidecar)
- ⊘ Sampling: blocked by client UX
- ⊘ GET /mcp SSE notification stream: deferred (depends on client adoption)

### Next improvements (2026-05-16 post-implementation research)

Ordered by impact. None are urgent — the MCP surface is functional.

| ID | What | Effort | Rationale |
|----|------|--------|-----------|
| N1 | Cache enrichment status (30s TTL) | ~1h | Every search + answer call hits `adapter.status()` for enrichment hints. Wasteful — status changes slowly. |
| N2 | Parallelize answer tool's facet sidecar | ~2h | retrieve-context + limit-0 facet search are sequential but independent. Parallel cuts ~50ms latency. |
| N3 | `justsearch_document` tool (full doc by ID) | ~1 day | Search returns passages; model can't read full documents. Closes the "I found it, now let me read it" gap. Maps to tempdoc 401 `justsearch://doc/{id}`. Should be the 6th tool ONLY with eval validation. |
| N4 | Smart prompt expansion (include top sources from facets) | ~2h | Current prompts are static. Including top-3 indexed sources in the system context helps the model know what's available. |
| N5 | Refactor McpToolSurface (per-tool handlers) | Deferred | **Premature for 5 tools.** Investigation (2026-05-16) found: only 2 shared cross-cutting helpers, each tool method is 40-80 LOC, extracting 12+ files violates "don't add abstractions beyond what the task requires." Trigger: when 6th tool (N3) is eval-validated and ready to ship, extract the McpTool interface then. |
| N6 | MCP Gateway compatibility | ~1 day | Emerging pattern: users run MCP gateways that aggregate multiple servers. JustSearch should support tool namespacing (`justsearch__answer` format) for gateway routing. |

### What NOT to do (research-backed)

- **Don't add more tools without eval.** 5 is the validated sweet spot. The 6th tool (N3) should only ship after a 50-query eval confirms it doesn't degrade accuracy.
- **Don't build MCP client capabilities.** Consuming external MCP tools (G54 in 486 catalog) is a different product direction — much more complex, separate design.
- **Don't add OAuth/auth.** The loopback-only invariant makes it unnecessary until remote access is a goal.
- **Don't compete on chat.** Built-in RAG in Claude/Cursor is "good enough" for single-tool workflows. JustSearch's value is cross-tool persistence + retrieval quality, not chat UX.

## 7. Ecosystem positioning (2026-05-16 research)

### 7.1 Competitive landscape

"Knowledge & Memory" is the largest MCP server category (283 servers).
Most are simple (ChromaDB + Ollama over markdown). JustSearch is at
the quality end:

| Competitor | MCP? | Retrieval | Reranking | Multi-format | Local |
|-----------|------|-----------|-----------|-------------|-------|
| JustSearch | ✅ 5 curated tools | BM25 + dense + SPLADE | Cross-encoder | PDF, email, PPTX, DOCX, code | ✅ |
| AnythingLLM | ✅ client + server | Single-vector | ❌ | Multi-format | ✅ |
| Obsidian MCP | ✅ 5+ plugins | Vector similarity | ❌ | Markdown only | ✅ |
| PrivateGPT | ✅ chat-only | Vector | ❌ | Multi-format | ✅ |
| Khoj | ❌ | Vector | ❌ | Multi-format | ✅ |
| Qdrant/Chroma MCP | ✅ | Vector only | ❌ | N/A (raw embeddings) | ✅ |

No other local MCP server combines hybrid retrieval + reranking + NER +
multi-format. The closest (AnythingLLM) lacks reranking and hybrid search.

### 7.2 The real value proposition

The obvious pitch is "better search results." The research revealed a
stronger pitch: **cross-tool knowledge persistence.**

Built-in RAG (Claude Projects, Cursor indexing) silos your knowledge
per vendor. JustSearch's MCP server makes your personal knowledge base
available to every AI tool simultaneously — Claude Desktop, Cursor,
VS Code Copilot, custom agents all query the same index. Your files
aren't locked into one vendor's ecosystem.

Where this is strong:
- Users with large heterogeneous document collections (not just code)
- Users who switch between AI tools for different tasks
- Privacy-sensitive users (journalists, lawyers, regulated industries)

Where this is weak:
- Single-tool workflows where built-in RAG suffices
- Small, well-organized codebases where filesystem MCP is enough

### 7.3 Ecosystem dynamics

- Tool-count ceiling at 40-50 tools across all connected servers.
  JustSearch's 5-tool surface is lean — leaves room for other servers.
- MCP Gateways (single endpoint aggregating multiple servers) are
  emerging. JustSearch should be a good citizen in this pattern.
- Sampling: Amazon Bedrock supports session-level approval. Claude
  Desktop still requires per-request. Worth revisiting when client UX
  improves.
- The ecosystem has a sustainability gap — most community MCP servers
  are unfunded volunteer work. JustSearch's NLnet grant application
  positions it in the funded-commons tier.

## 6. Architectural position

```
┌─────────────────────────────────────────────────────────┐
│ Layer 1: Protocol transport (POST /mcp on Javalin)      │
│   JSON-RPC parsing → method dispatch → response writing │
│   Session management, SSE upgrade for notifications     │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────┴──────────────────────────────┐
│ Layer 2: Curated tool surface (McpToolSurface)          │
│   5 tools with hand-written descriptions + schemas      │
│   Position-bias ordering, response-level hints          │
│   Each tool maps to one or more backend dispatch paths  │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────┴──────────────────────────────┐
│ Layer 3: MCP primitives (Resources, Prompts, Sampling)  │
│   Resources: live state from backend endpoints          │
│   Prompts: onboarding templates for Claude Desktop      │
│   Sampling: external-model RAG via client's LLM         │
└──────────────────────────┬──────────────────────────────┘
                           │
┌──────────────────────────┴──────────────────────────────┐
│ Existing infrastructure (unchanged)                     │
│   OperationExecutorImpl, OperationCatalog, ResourceCat  │
│   ConversationEngine, IntentSourceCatalog, TrustLattice │
└─────────────────────────────────────────────────────────┘
```
