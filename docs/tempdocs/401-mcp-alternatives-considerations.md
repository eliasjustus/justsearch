---
title: "401 — MCP Alternatives Considerations"
---

# 401 — MCP Alternatives Considerations

**Status:** Open (considerations register, not active work).
**Created:** 2026-04-22.
**Owner:** Unclaimed — awaiting design pass before any implementation.
**Scope:** Long-term evaluation of whether MCP remains the right
external agent interface for JustSearch, and how to evolve it.
**Parent context:** The question is specifically about agents
**using** JustSearch as a filesearch tool while working on other
tasks — not dev-loop agents coding on JustSearch. The production
MCP surface lives in `scripts/prod/justsearch-mcp/` (4 tools). The
dev MCP surface in `scripts/dev/justsearch-dev-mcp/` (15 tools) is
out of scope for this doc.

> NOTE: Noncanonical doc. Design register, not a design doc.
> Considerations live here until a specific design tempdoc is opened
> to address them. Items are not actionable in their current form —
> each needs its own design pass before implementation.

---

## Purpose

Re-examine the conclusion in ADR-0015 ("MCP is the emerging standard
— rejected OpenAPI") a year in, using the eval evidence accumulated
in tempdoc 366 Phases 1–7. The ADR's rejection of OpenAPI was a
single sentence. That's fine as a decision record but thin as a
long-term defence. This doc captures the actual tradeoffs so a
future design pass has the full picture instead of re-discovering it.

The question is **not** "should we replace MCP?" — the ecosystem
evidence says no. The question is "what are MCP's failure modes
that a future design pass needs to address?"

---

## Current surface (as of 2026-05)

> **Updated 2026-05-16.** The old TypeScript stdio server is deprecated.
> The production surface is now a Java MCP handler at `POST /mcp` on
> the existing Javalin server (tempdoc 500). See
> `docs/reference/mcp-production-server.md` for full reference.

**Production MCP handler:** `modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java`.
Streamable HTTP on loopback. Direct service-layer dispatch (no HTTP
roundtrip for search/answer).

**Five curated tools** (position-bias ordered, eval-informed descriptions):

| # | Tool | Backend | Purpose |
|---|------|---------|---------|
| 1 | `justsearch_answer` | `DocumentService.retrieveContext()` | RAG retrieval — assembled passages + quality signals + facet sidecar |
| 2 | `justsearch_search` | `KnowledgeHttpApiAdapter.search()` | Exploratory search with auto-facets on first page |
| 3 | `justsearch_browse` | `core.browse-folders` Operation | Folder structure exploration (new — not in old TS server) |
| 4 | `justsearch_ingest` | `core.ingest-files` Operation | File ingestion |
| 5 | `justsearch_status` | `KnowledgeHttpApiAdapter.status()` | Index health + enrichment coverage (formatted text) |

Plus: 3 MCP Prompts (with live system context), 4 proposed resource URIs,
9 catalog-driven resources, session TTL, trust lattice integration.

**Legacy server:** `scripts/prod/justsearch-mcp/server.mjs` (~800 LOC,
stdio, 4 tools). Deprecated — eval data and descriptions preserved as
design reference. Remove after new handler is eval-validated.

Progressive disclosure via **response-level hints** at decision time
(zero results, high hit count, filter tips, enrichment coverage,
facet sidecar on answer). Advanced params work when passed but are
NOT in the visible schema — eval proved making them visible hurts
accuracy (92% → 71%).

---

## Evidence supporting the current design

All from 50-query Haiku eval (tempdoc 366):

| Phase | Result |
|---|---|
| Phase 4 consolidation (7→4 tools) | 72% → **92%** accuracy (+20pp) |
| Phase 5 hybrid filter normalization | 91.8%, $0.053, 7.2 turns |
| Phase 6 final (NER + entity facets) | **94%**, $0.057, 7.2 turns, 0 errors |
| Phase 7 parallel + response hints | 90%, $0.065, 7.0 turns |
| Exp 2+3+4 (facet sidecar + description guidance) | **94%**, comparison_query +20pp |

**Token efficiency:** 10-result search = 1,749 tokens (MCP slim) vs
19,902 raw (~91% reduction).

**Cost asymmetry:** answer-first agents $0.023 avg, search-explore
$0.069. 3× cheaper for similar quality when the agent takes the
first-listed tool.

**Schema-bloat trap, measured:** Phase 6 adding `doc_ids` and
`return_full_documents` as first-class Zod params dropped Haiku
from 92% → **71%** with **zero** agents using the new params.
Moving behind `.passthrough()` recovered to 90–94%. Matches arXiv
2504.19277 (16.1% avg small-model degradation from optional schema
params without proportional description investment).

---

## Why MCP stays the right primary interface

1. **Host ecosystem lock-in works in our favor.** Claude Desktop,
   Claude Code, Cursor, Windsurf, and current-generation coding
   agents discover **MCP servers**, not REST endpoints. OpenAPI
   would require every host to implement a JustSearch-specific
   adapter. That's what ADR-0015 was actually defending, even if
   the one-sentence rejection didn't spell it out.
2. **Eager tool-definition injection** — an agent knows `answer`
   and `search` exist without prompting. For a 4-tool surface
   (~500–800 tokens of description budget) this is a win.
3. **Schema validation** via Zod catches malformed calls before
   they hit HTTP.
4. **Loopback + sidecar model** matches the Head-never-shares-Lucene
   invariant perfectly. No new network surface.

---

## Where MCP is currently fragile

These are the **actual** long-term concerns. Each one needs a design
pass before it becomes actionable.

### F1. Small- vs large-model tension lives inside `.passthrough()`

To protect Haiku accuracy, advanced params (`doc_ids`,
`return_full_documents`, `includeExcerpts`, LUCENE syntax, entity
filters) are schema-invisible. A capable Opus agent reading only
the schema can't see what it can do.

Haiku feature-adoption numbers from Phase 6 final eval:

| Feature | Adoption |
|---|---|
| entity filter | 2/50 |
| doc_ids | 3/50 |
| return_full_documents | 1/50 |
| includeExcerpts | 1/50 |
| LUCENE syntax | 0/50 |
| boostFilters | 0/50 |

Opus has never been formally evaluated on the 4-tool surface.
Item `4f. Opus eval` in tempdoc 366 is still `[ ]`. The whole design
is optimized for one data point (Haiku 50q) and extrapolated.
**Risk:** Opus-class agents leave capability on the table; there's
no path for them to use the full backend short of reading the tool
description carefully enough to guess.

### F2. Stdio-only transport

Only local stdio hosts are supported. No path for:
- Browser-based agents.
- Remote / cloud orchestrators.
- Multi-tenant agent servers hitting a user's local index.

MCP's Streamable HTTP transport (March 2026 spec) isn't adopted
yet. Adding it while keeping the 127.0.0.1 invariant is the obvious
evolution.

### F3. Drift risk between HTTP and MCP schemas

`docs/reference/mcp-production-server.md` explicitly flags this:
"if the HTTP API evolves, the MCP server must stay in sync." No
generated contract binds them. Precedent: `FilterNormalization`
was missing on the answer path for weeks because the two schemas
were maintained independently (tempdoc 366 Phase 7 transcript
analysis). Cheap to fix — generate one from the other or from a
shared OpenAPI spec — but currently not enforced.

### F4. MCP `resources` and `prompts` primitives are unused

JustSearch only uses MCP **tools**. The MCP spec has two other
primitives:
- **Resources** — content the host can list at connect time.
  An agent could discover "indexed roots," "top sources," "top
  entities" without burning a tool call.
- **Prompts** — templates for common agent patterns ("summarize
  corpus X," "compare sources on topic Y").

Currently an agent has to call `justsearch_status` just to learn
the index exists and is populated. That's friction small models
pay for disproportionately (one extra turn out of ~7).

### F5. ADR-0015's OpenAPI rejection is thin

The ADR rejects OpenAPI in one sentence: "MCP aligns with the
target ecosystem." That's correct for host compatibility but is
not a full defense against "what if the ecosystem fragments?" or
"what about DIY integrators who can't use stdio?" The decision
stands; the argument deserves more depth if it gets challenged.

---

## Alternatives considered (and rejected, for recorded reasons)

| Alternative | Why rejected |
|---|---|
| **Replace MCP with OpenAPI + REST** | Loses Claude Desktop / Cursor / Windsurf auto-discovery. Small models can't parse 50 KB of spec. Every integrator re-implements retry/hints. |
| **gRPC for external agents** | No LLM host supports it natively. Protobuf is hostile to LLM reasoning about schemas. Currently used internally Head↔Worker, appropriate there. |
| **Language-specific SDK (Python/TS)** | Locks in language. Doesn't fit host-based discovery. Duplicates HTTP semantics. |
| **CLI (`justsearch search ...`)** | Fine for scripts, lossy for agents (parsing text output is a regression from structured responses). |
| **One mega-tool with a `mode` param** | Loses position-bias benefit. Fights the task-oriented consolidation that earned the +20pp in Phase 4. |
| **Re-introduce `preview` / `match_citations` as standalone tools** | Eval killed them (0/50 and 2/50 standalone usage). `match_citations` works fine as an opt-in parameter on `answer`. |

---

## Directions a future design pass should evaluate

Not actionable yet — each needs its own design pass with eval gates
before any implementation. Listed in rough order of value-for-effort.

### D1. Contract-drift prevention between HTTP and MCP schemas

The cheapest long-term win. Either:
- (a) Generate an OpenAPI spec from `/api/knowledge/*` contract
  tests and derive MCP Zod schemas from it.
- (b) Flip: MCP Zod schemas are the source of truth and the HTTP
  contract tests import them.

Either closes F3 without any protocol change. Precedent bug
(`FilterNormalization` skew) makes this concrete.

### D2. Tiered MCP surface for large-model agents

Ship a second MCP server (or capability flag) that exposes the
`.passthrough()` features as first-class schema: `doc_ids`,
`return_full_documents`, `includeExcerpts`, entity filters, LUCENE
grammar, boost filters.

Users with Opus-class agents opt into the advanced server. Haiku-
class users keep the minimal surface and its measured 94% accuracy.

**Gating evidence:** `4f. Opus eval` must run first. If Opus on
the current 4-tool surface already hits ≥95% without the advanced
schema, D2 is unnecessary — large models compensate with careful
description reading. If Opus leaves capability on the table, D2
has a measured case.

### D3. MCP Streamable HTTP transport alongside stdio

**Background.** The MCP spec defines two transports:

- **stdio** — the server is a child process. The host launches it,
  writes JSON-RPC frames to stdin, reads responses from stdout.
  This is what JustSearch's production MCP server uses today
  (`StdioServerTransport` in `scripts/prod/justsearch-mcp/server.mjs:14`).
- **Streamable HTTP** — the server is an HTTP endpoint. The host
  POSTs JSON-RPC requests to a single URL; the server streams
  responses back (SSE for server-initiated messages, regular HTTP
  responses for tool calls). Introduced in the March 2026 MCP spec,
  replacing the older HTTP+SSE dual-endpoint design.

**Why stdio-only is a ceiling.**

1. The host must be able to spawn a process on the same machine.
   Claude Desktop, Cursor, Windsurf all can. A browser-based agent
   UI can't. A cloud-hosted orchestrator can't reach into a user's
   laptop to `exec` a binary.
2. One host, one server instance. Stdio is a 1:1 pipe. Two agents
   on the same machine = two server processes = two HTTP client
   pools talking to the same local JustSearch. Wasteful but
   tolerable.
3. No network addressability. Even on the same machine, another
   app wanting MCP access has to launch its own stdio child.

**What Streamable HTTP unlocks, keeping the loopback invariant.**
The JustSearch Head already runs an HTTP server on 127.0.0.1.
Adding an MCP endpoint (`POST http://127.0.0.1:<port>/mcp`) costs
one route on that same server. The hard invariant "loopback-only,
never bind 0.0.0.0" stays. What changes:

- Browser-based agents on the same machine can hit it (via a
  permitted extension or a desktop-shell-mediated bridge).
- Multiple agents share **one** server instance instead of each
  spawning their own child.
- The existing bearer-token path (`/api/mcp/token` in
  `LocalApiServer.java:616`) becomes the natural auth boundary —
  already hardened for the HTTP API.
- Future "expose my local JustSearch to my phone over Tailscale"
  stories become a config change (bind address + TLS), not a
  rewrite.

**What it does not unlock.** Remote cloud agents hitting a user's
laptop across the open internet. That's a security decision, not a
transport decision. Streamable HTTP makes it *possible*; the
loopback invariant says it stays *off by default*.

**Work involved.** The MCP TypeScript SDK ships
`StreamableHTTPServerTransport` alongside `StdioServerTransport`.
The tool handlers don't change. It's transport wiring plus an
auth/CORS pass. Rough estimate: one design pass, one implementation
tempdoc, 200–400 LOC.

**Open question:** auth model. Stdio inherits host trust. HTTP
needs the existing bearer-token path hardened (rotation, scope,
revocation).

### D4. Use MCP `resources` for index introspection

**Background.** The MCP spec defines three primitives a server can
expose:

- **Tools** — things the LLM calls. JustSearch uses these.
- **Resources** — content the host lists and the LLM can read as
  context. URI-addressable, static or dynamically generated.
  **JustSearch does not use these.**
- **Prompts** — named parameterised prompt templates the host
  offers to the user. Covered separately in D5.

**What resources would look like for JustSearch.** Today, an agent
connecting to JustSearch knows nothing about the index until it
calls `justsearch_status` — that's one tool call spent on "does
this index exist and have anything in it?" Resources let the host
answer that question before the LLM burns a turn.

Concrete candidates, all URI-addressable:

| Resource URI | Content |
|---|---|
| `justsearch://index/roots` | List of indexed folder paths. |
| `justsearch://index/summary` | Doc count, last-index time, enrichment coverage. |
| `justsearch://index/top-sources` | Top-N `meta_source` values with counts. |
| `justsearch://index/top-entities` | Top-N persons / organizations from NER. |
| `justsearch://doc/{id}` | Full indexed document text on demand. |

The host lists these at connection time (`resources/list`),
optionally fetches the summary ones eagerly, and injects them into
the agent's working context.

**Why this matters specifically for small models.** Every tool call
on a Haiku-class agent costs ~1 turn of its ~7-turn budget. The
current friction pattern logged in tempdoc 366:

- 29/50 agents used `meta_source` filters, but 7 of them explicitly
  complained "I had to guess what values were valid" (transcript
  analysis, 2026-04-07).
- The fix shipped was a **facet sidecar** on the answer tool — run
  a second `limit=0` search just to fetch facets. That costs
  backend latency (~50 ms) and response tokens on *every* answer call.

A `justsearch://index/top-sources` resource, listed at connect
time, gives the agent the same information **zero turns in**,
**zero tokens per call**. The fix moves from "pay on every
request" to "pay once at connection."

**Bonus: full-document retrieval as a resource, not a tool.**
Currently `return_full_documents: true` on `justsearch_answer`
re-retrieves. Exposing `justsearch://doc/{id}` as a resource is
more idiomatic — agents hand the host a URI and the host fetches
it, without a tool-call round-trip through the LLM.

**Open questions the design pass must resolve.**

1. **Cardinality control.** `top-entities` in a large news corpus
   could be thousands. Resources need to be listable (paginated)
   or capped (top-N). The `top-sources` and `top-entities` facets
   already exist in the backend — just expose them with a
   sensible cap (say 100).
2. **Freshness.** Resources have a `subscribe` mechanism for
   change notifications. Worth wiring if the index updates during
   a session; probably over-engineering for the first version.
3. **Host surface behaviour.** Claude Desktop surfaces resources
   to the user as "attach context." Cursor and Windsurf vary.
   Some hosts expose them directly to the LLM as readable URIs.
   Needs an ecosystem survey before committing — which is why
   D4 is not "do next."

**Work involved.** The TS MCP SDK supports
`server.registerResource(uri, handler)` the same way it supports
tools. The backend data (facets, roots, document text) all already
exist via HTTP. Rough estimate: design pass first (cardinality +
ecosystem survey), then 300 LOC for the initial resource set.

### D5. Use MCP `prompts` for common agent patterns

Templates for "summarize corpus X," "compare sources on topic Y,"
"find contradictions between documents A and B." Prompts are
host-visible and reduce the description-budget pressure on tool
descriptions themselves.

**Open question:** whether hosts that target JustSearch actually
surface prompts to end users, or whether they're ignored in
practice. Needs ecosystem survey before investing.

### D6. Running the deferred Opus eval (`366 item 4f`)

Not a new feature — closing an open eval from 366. Measures whether
Opus on the current 4-tool surface uses `.passthrough()` advanced
features when prompted carefully. Directly informs whether D2 is
necessary.

---

## Theoretical tool-surface changes (exploratory, unranked)

Beyond the near-term directions D1–D6, this section captures
design space that is further out. Items here are **theoretical** —
they describe design space and trade-offs, not plans. None of them
should be implemented without their own design pass and eval gate.

Organised in three categories: (T1–T13) MCP protocol features we
don't use; (T14–T23) JustSearch-specific tool ideas; (T24–T26)
HTTP surface we currently drop or hide.

### Category T1–T13: MCP protocol features not currently used

The MCP spec (latest stable 2025-06-18) offers more than tools.
JustSearch uses only tools and a minimal subset of annotations.
The following features are documented in the spec but unused.

#### T1. Sampling (server→host LLM calls)

MCP lets a server request LLM completions from the host. The
server sends a `sampling/createMessage` request and the host's
LLM generates a response, with user approval.

**Why it matters for JustSearch.** Today the `QueryUnderstanding`
service runs on a local llama-server that contends with query
expansion for the same single slot (tempdoc 366 §Phase 3). That
contention is why `JUSTSEARCH_QU_ENABLED=false` is the default.
If JustSearch asked the **host's** LLM via sampling, the local
llama-server is no longer in the critical path — the agent's own
model does the entity extraction and returns it for the next
search call.

**Risks.** User-visible approval dialogs on every query would be
intolerable. Sampling is only sensible for *background* server
operations where the user opt-in is once-per-session, not
per-call. Host support is uneven — not every MCP host implements
sampling yet.

#### T2. Elicitation (server→user prompts mid-tool-call)

Added in spec 2025-06-18. Lets a server ask the user for input
during a tool call ("did you mean X or Y?").

**Why it matters for JustSearch.** The filter-value confidence
problem logged in tempdoc 366 (29/50 agents used `meta_source`
filters but 7 complained they guessed) could be closed via
elicitation: if a filter value has no exact match but several
candidates, ask the user "did you mean 'cnbc' or 'cnbc | world
business news leader'?" before retrieving.

**Risks.** Elicitation is a 2025-06-18 feature, so host support
is thin. Breaks the "zero user interruption" model that makes
JustSearch useful as a background tool. Only worth it for
ambiguity classes where the current silent failure is clearly
worse than one question.

#### T3. Progress notifications

For long-running tool calls, the server streams progress tokens
(0 → N with human-readable status).

**Why it matters for JustSearch.** `justsearch_ingest` can
take minutes on a large folder. Today the agent sees a single
response after everything finishes. Progress notifications
would let the host show the user "ingesting 1,243 / 5,000
files" and let the agent reason about whether to wait.

**Risks.** None significant. The `/api/knowledge/ingest` endpoint
already tracks queue depth via `/api/status`. Wiring progress
tokens is a plumbing exercise, not a design question.

#### T4. Full tool annotations

JustSearch currently sets `readOnlyHint: true` on `justsearch_answer`
and `justsearch_search`. The spec defines four annotations:

| Annotation | Meaning |
|---|---|
| `readOnlyHint` | Tool does not modify state. |
| `destructiveHint` | Tool makes hard-to-reverse changes. |
| `openWorldHint` | Tool reaches external services. |
| `idempotentHint` | Repeat calls are safe and converge. |

**Why it matters for JustSearch.** `justsearch_ingest` should set
`destructiveHint: false` (indexing is reversible by re-indexing)
but `idempotentHint: true` (same files → same state). Hosts use
annotations to decide auto-approval policy. Without them, agents
rely on the host's default guess.

**Risks.** None. It's metadata. Cost is one line per tool.

#### T5. Resource links in tool results

Added in spec 2025-06-18. A tool result can include links to
resources the host can fetch lazily.

**Why it matters for JustSearch.** Today `justsearch_answer`
returns chunk excerpts up to a byte cap. With resource links,
it could return passages + `justsearch://doc/{id}` links; the
agent or user decides which full documents to fetch. Smaller
answer responses, on-demand deeper context.

**Risks.** Depends on D4 (resources) shipping first. Resource
links without resources support are just URIs the host can't
resolve.

#### T6. Structured tool output (typed content blocks)

Also 2025-06-18. Tool results can return `structuredContent` with
a declared output schema, not just text.

**Why it matters for JustSearch.** The MCP server already declares
output schemas via Zod (`SearchOutputSchema`, `AnswerOutputSchema`)
but returns them as JSON text inside the default `content` array.
Migrating to `structuredContent` lets hosts render results with
typed UI (hit lists, facet chips, citation cards) instead of
parsing JSON out of a text blob.

**Risks.** Host support varies. The fallback (JSON-in-text) still
works. Worth doing in the same pass as migrating schemas to
OpenAPI-shared types (D1).

#### T7. Prompts (already covered as D5)

Cross-reference only.

#### T8. Roots

Clients advertise filesystem roots the server can operate within.

**Why it matters for JustSearch.** The Tauri shell already knows
which folders the user has indexed. Exposing them as MCP roots
would let the agent confirm "this file is inside an indexed root"
without calling `justsearch_status`.

**Risks.** Overlaps with D4 (`justsearch://index/roots` as a
resource). The resource path is simpler; roots is a second way
to express the same information. Pick one.

#### T9. Completion (argument auto-completion)

Used for prompt arguments (D5) and resource URI templates.

**Why it matters for JustSearch.** If prompts land (D5), the
server should implement completion so hosts can auto-suggest
corpus names, source filters, entity names as the user types
arguments. Cheap to add; entirely driven by existing facet data.

**Risks.** Depends on D5.

#### T10. Resource subscribe / change notifications

Servers push `resources/list_changed` when the resource list
changes; clients subscribe to specific resources for content
change notifications.

**Why it matters for JustSearch.** If the user indexes new
content mid-session, subscribed agents learn about the new roots
/ sources / entities without polling.

**Risks.** Cardinality — sending a notification on every index
commit could be chatty. Needs debouncing and a "significant
change" threshold.

#### T11. Cancellation

Clients can cancel in-flight requests. The server must honor it.

**Why it matters for JustSearch.** Large ingest runs and long
answer calls (with LLM-based filter normalization) should be
cancellable. Today they aren't. This is the same failure mode
tempdoc 388 addresses for the agent path, one layer down.

**Risks.** None architecturally. Cost is plumbing.

#### T12. Logging

Server emits log entries the host can display.

**Why it matters for JustSearch.** Debug information that today
lives in stderr could flow to the host as structured log events
at configurable levels. Useful for power users debugging why a
query didn't find what they expected.

**Risks.** Log volume. Default level has to be strict.

#### T13. `title` field for human-friendly display

2025-06-18 addition. `name` stays programmatic, `title` is
user-facing. JustSearch's tool names (`justsearch_answer`) are
already mostly readable but resource names (e.g.,
`justsearch://index/top-entities`) aren't.

**Risks.** None. Cost is metadata.

---

### Category T14–T23: JustSearch-specific tool surface ideas

These are speculative tool redesigns that go beyond protocol
features. Each would need Haiku + Opus eval before any commitment.

#### T14. Split `justsearch_answer` by query intent

Current `answer` is one tool for every question type. Eval data
from tempdoc 366 shows distinct patterns: inference queries
(single-hop, 100% accuracy at 4.1 turns) vs comparison queries
(multi-source, 87% at 6.4 turns) vs null queries (absence-proof,
100% at 13.8 turns). The current single tool serves all three
but hints agents differently per case.

**Theoretical change.** Split into `justsearch_answer` (single-hop
QA) + `justsearch_compare_sources` (explicitly multi-source,
internally fans out per source) + a signal for null-query
abstention.

**Risks.** More tools = Haiku regression. The Phase 4 lesson is
clear: fewer tools win for small models. Only viable if the
compare-sources tool measurably outperforms the current
per-source description guidance (which gained +20pp in Exp 4).

#### T15. First-class document-discovery tool

Today "find documents that mention X" requires a search with
implicit ranking. A dedicated `justsearch_find_documents` tool
would return doc-level results (not chunks), ranked by
mention-density or recency, for "which documents discuss X?"
questions.

**Risks.** Overlaps with `justsearch_search` + facets. Only
justified if eval shows agents regularly fail the doc-level vs
chunk-level distinction today — which tempdoc 366 §Full
document retrieval (17 mentions) suggests they do.

#### T16. Retrieval-trace / explain tool

"Why did this chunk rank highly?" — for Opus-class users doing
retrieval debugging. Returns score breakdown (BM25, vector,
SPLADE, rerank), matched fields, synonyms hit, boost contributions.

**Risks.** Haiku will never use it. Purely for advanced agents,
so it only makes sense behind D2's tiered surface.

#### T17. Cursor-based / incremental search

`justsearch_search` returns top-N then an opaque cursor. Next
call with the cursor returns the next page. Today an agent
wanting depth has to re-run the query with higher limit, which
re-scores everything and wastes tokens.

**Risks.** State on the server side. Cursors must expire. Adds
complexity for a use case that's usually solvable by asking for
a larger `limit`.

#### T18. Server-side multi-query fan-out for comparison

An `answer` call for "how do CNBC and The Verge cover X?" today
requires the agent to make two calls with different
`meta_source` filters and synthesize. A multi-query variant
would take `{query, split_by: meta_source}` and return grouped
results in one call.

**Risks.** Unclear whether server-side fan-out is worth a new
tool vs the current per-source description guidance (which is
already achieving +20pp on comparison queries). Run the
experiment as description vs tool and see.

#### T19. Stateful search session

A session tool: `justsearch_set_context({sources: [...],
entities: [...]})` → subsequent answer/search calls inherit the
filters. Saves re-specifying filters on every call.

**Risks.** Hidden state is bad for debugging and bad for agents
(they forget session state across turns). Only worth it if the
cost of re-specifying filters is measurably high, which it
isn't per the 1,749-token-per-response baseline.

#### T20. Filter dry-run / preview

`justsearch_preview_filters({filters: {...}})` returns hit count
without retrieving content. Cheap way for agents to test filter
combinations before committing to a full answer call.

**Risks.** Overlaps with `justsearch_search` at `limit=0` —
which already works. Could be a description hint on `search`
rather than a new tool.

#### T21. OCR / visual content as tool result

For indexed PDFs with images or scanned documents, return page
thumbnails or OCR-extracted text as image content blocks. MCP
supports `image` and `audio` content types.

**Risks.** Adds binary weight to responses. Only valuable once
JustSearch actually indexes visual content meaningfully, which
it doesn't today.

#### T22. Autocomplete as a tool (wrapping `/api/knowledge/suggest`)

The HTTP `/api/knowledge/suggest` endpoint is unwrapped by MCP.
Adding `justsearch_suggest` would let agents query "what terms
match my partial query?" before running a full search.

**Risks.** Eval says 2/50 historical usage (why it was removed).
Unlikely to help small models. Might help Opus in the D2 tier.

#### T23. Folder-browsing tool (wrapping `/api/knowledge/folders`
and `/folder-files`)

Currently the agent has no way to ask "what folders are indexed
and what's in them?" without calling the agent-API internal
`browse_folders` tool (which isn't the external MCP surface).

**Risks.** Overlaps with D4 resources. If `justsearch://index/roots`
ships as a resource, a folder-browsing tool becomes redundant.

---

### Category T24–T26: Backend capabilities currently dropped by MCP

These aren't theoretical — the capability exists in HTTP but the
MCP server intentionally drops or hides it. Worth recording so a
future design pass knows what can be recovered cheaply.

#### T24. Retrieval provenance / fusion breakdown

`server.mjs:slimSearchResult()` drops `bm25_score`, `vector_score`,
`reranker_score`, `embedding_metadata` from hits by default.
Recoverable via `includeProvenance=true` but undocumented in the
tool description.

**Consideration.** Useful for T16 (explain tool) and for D2
(advanced tier). Not for the base Haiku surface — adds tokens
without a clear accuracy signal.

#### T25. `return_full_documents` documented in description

The parameter works (validated in tempdoc 366 §6c) but lives
behind `.passthrough()` with no LLM-visible schema and no
description-text guidance beyond a brief mention. Adoption:
1/50 Haiku, 7/50 after response-hint addition.

**Consideration.** Current invisibility is by design (schema
bloat penalty). D2 surfaces it first-class for Opus. No change
needed for base.

#### T26. Full-content responses vs preview truncation

Content is truncated to 200 chars unless `verbose=true`. Agents
that want full chunk content today either read the file directly
(their own tool) or pass `verbose=true` (undocumented).

**Consideration.** Deliberate — 91% token reduction matters
more than full-content discoverability. Worth documenting in
tool description ("Pass verbose=true for full chunk text")
without schema-bloating.

---



- Do not re-introduce `justsearch_preview` or `justsearch_match_citations`
  as standalone tools. Eval killed them for measured reasons.
- Do not expand tool descriptions with more workflow guidance.
  Response-level hints at decision time outperform description
  text (Algolia / Apigene / HuggingFace pattern, confirmed by
  Phase 7 experiments).
- Do not add optional schema params to the base 4-tool surface
  without Haiku eval proving adoption. arXiv 2504.19277 +
  tempdoc 366 Phase 6 (measured 72% → 71% regression) both say this.
- Do not migrate the primary surface away from MCP. The ecosystem
  argument from ADR-0015 holds even with the thin prose. OpenAPI /
  gRPC / SDK stay as complementary surfaces at most.

---

## Evidence pointers

| Claim | Source |
|---|---|
| 7→4 tool consolidation rationale | `docs/decisions/0015-mcp-tool-surface-design.md` |
| +20pp Haiku accuracy from consolidation | `docs/tempdocs/366-agent-search-interface.md` §Phase 4 |
| 94% Haiku final, 0 errors | `docs/tempdocs/366-agent-search-interface.md` §Phase 6 final eval |
| Schema-bloat regression 92%→71% | `docs/tempdocs/366-agent-search-interface.md` §Phase 6 eval and schema bloat discovery |
| Response-hint industry pattern | `docs/tempdocs/366-agent-search-interface.md` §Research finding 2026-04-07 |
| Production MCP server code | `scripts/prod/justsearch-mcp/server.mjs` |
| Dev MCP (out of scope here) | `scripts/dev/justsearch-dev-mcp/server.mjs` |
| Production-MCP reference + drift risk flag | `docs/reference/mcp-production-server.md` |
| Agent system architecture (MCP section) | `docs/explanation/22-agent-system-architecture.md` §MCP Tool Surface |
| Opus eval not yet run | `docs/tempdocs/366-agent-search-interface.md` §Phase 4 item 4f |

---

## Summary

MCP is the right long-term **primary** interface for agents using
JustSearch as a filesearch tool. The 4-tool surface is eval-proven
for small models.

**Near-term** (D1–D6) concerns worth a future design pass:

1. Small-vs-large model schema tension (D2, gated by D6 Opus eval).
2. Stdio-only transport blocks remote / browser agents (D3).
3. HTTP↔MCP schema drift with no enforcement (D1 — cheapest win).
4. Unused MCP primitives (D4 resources, D5 prompts) that would cut
   the discovery-tax on small models.

None of these change the MCP-is-primary conclusion. All of them
should be decided before committing to large implementation work
that assumes the current surface is frozen.

**Further out** (T1–T26): a design space of MCP protocol features
JustSearch doesn't currently use (sampling, elicitation, progress,
structured output, resource links, roots, subscribe, cancellation,
full tool annotations), JustSearch-specific tool redesigns (intent-
split answers, doc-discovery, retrieval trace, cursor search,
fan-out, stateful sessions, filter preview, OCR, suggest, folder
browse), and HTTP capabilities the MCP server currently drops or
hides (provenance, `return_full_documents`, full content). Each is
speculative — do not implement without its own design pass and
measured eval gate. Pattern-wise, the cheapest wins likely sit in
T3 (progress), T4 (tool annotations), T11 (cancellation), and T13
(`title`); the highest-leverage ones are T1 (sampling — removes
local LLM contention for QU) and T2 (elicitation — addresses the
filter-value confidence complaint) but both depend on host support
that isn't universal yet.
