---
title: Production MCP Server Reference
type: reference
status: stable
description: "MCP server for connecting AI agents to a running JustSearch instance via Streamable HTTP."
---

# Production MCP Server

JustSearch exposes an MCP server at `POST /mcp` on its local API.
External AI tools (Claude Desktop, Cursor, VS Code Copilot, etc.)
connect to it and get access to the local knowledge base — search,
retrieve context, browse folders, ingest files, and check status.

**Source of truth:** `modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java`

## Setup

### Claude Desktop

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "justsearch": {
      "url": "http://127.0.0.1:33221/mcp"
    }
  }
}
```

Replace `33221` with the actual port if JustSearch uses an ephemeral
port. Check the JustSearch window or `GET /api/health` to find it.

### Cursor / Windsurf / VS Code

Add to `.cursor/mcp.json` or equivalent:

```json
{
  "mcpServers": {
    "justsearch": {
      "url": "http://127.0.0.1:33221/mcp"
    }
  }
}
```

### Claude Code

```bash
claude mcp add justsearch --transport http http://127.0.0.1:33221/mcp
```

### Claude Code: headless / non-interactive approval

Project-scope MCP servers in Claude Code (added with `claude mcp add` at the default project
scope, or picked up from a shared `.mcp.json`) require a one-time **interactive** approval per
project directory before Claude Code will actually connect to them. In a non-interactive context
— `claude -p`, or an Agent SDK session — launched from a directory where the server has never
been approved, the server is silently dropped: there is no error, it just does not connect.
`claude mcp list` shows it as **"Pending approval"** rather than connected.

Remedies:

- Run `claude` interactively once in that project directory and approve the server when
  prompted; subsequent non-interactive runs from the same directory pick it up.
- Or skip the project-scope approval flow entirely: pass the server programmatically via SDK
  options (Agent SDK), or via `--mcp-config <file> --strict-mcp-config` on the CLI.

## Transport

Streamable HTTP on the existing Javalin server (loopback-only,
`127.0.0.1`). No separate process — the MCP endpoint runs in the
same JVM as the Head process. No Node.js required.

Protocol version: `2025-11-25`. Capabilities: tools, resources,
prompts. Curated tool-surface version (MCP `serverInfo.version`,
single-sourced from `McpContractVersions.TOOL_SURFACE_VERSION`):
`0.3.1`.

## Available Tools (6, position-bias ordered)

| # | Tool | Backend | Purpose |
|---|------|---------|---------|
| 1 | `justsearch_answer` | `DocumentService.retrieveContext()` | RAG retrieval — assembled passages with source attribution. Primary QA tool. Position-biased first. |
| 2 | `justsearch_search` | `KnowledgeHttpApiAdapter.search()` | Exploratory search with facets, filters. For discovery and browsing. |
| 3 | `justsearch_browse` | `core.browse-folders` Operation | Folder structure exploration. |
| 4 | `justsearch_ingest` | `core.ingest-files` Operation | File indexing. The only mutating tool — see Trust Model below. |
| 5 | `justsearch_status` | `KnowledgeHttpApiAdapter.status()` | Index health + enrichment coverage. |
| 6 | `justsearch_runtime_manifest` | `RuntimeManifestPublisher` | Redacted runtime manifest (identity, lifecycle, AI runtime state) for identity-aware caching. |

All 6 tools validate their arguments against a declared JSON Schema at the MCP boundary before
dispatch (tempdoc 655) — a malformed call gets a clean tool error rather than an internal cast
failure.

## Response shape (tempdoc 725)

The human-readable `content` text on `justsearch_search` and `justsearch_answer` carries several
descriptive lines beyond the raw results, so an agent can judge a response without a second call:

- **`justsearch_answer` evidence-pack header** — the first line states the passage count, the
  distinct-document count, the retrieval mode, and that the pack is retrieved evidence, not a
  synthesized answer (plus a truncation note when the context was cut to fit token limits).
- **`justsearch_search` match lines** — each hit carries a `Matched:` line naming the distinctive
  terms that drove the match (or a `Match basis: semantic similarity` line when no term overlap
  was distinctive), so an agent can tell *why* a hit matched without opening the file.
- **Degradation and coverage lines** — a once-per-response note when semantic ranking degraded or
  fell back, and a "showing N of M" line when a response was capped below the total hit count.

**`response_format`** (optional on both tools; default `"detailed"`, which includes preview
snippets and full evidence passages). `"concise"` returns substantially fewer tokens per call:
`justsearch_search` results omit the preview line, and `justsearch_answer` packs cap at the 3
highest-ranked passages; the coverage, match, and header lines are kept in both modes.

### What these tools do and do not do (multi-step lookups)

`justsearch_answer` performs retrieval, not synthesis: it returns relevant passages with source
attribution, and the calling agent composes the answer. Questions whose answer spans a chain of
documents (for example, an entity named in one document whose details live in another) require
the calling agent to issue a follow-up retrieval for each step of the chain — the tools do not
traverse entity chains on the agent's behalf. In measurement, completion of such multi-step
lookups varies substantially with the calling agent's model tier; the response furniture above
(match lines, headers, coverage notes) makes each step's result legible but does not remove the
need for the follow-up step itself.

## Structured retrieval evidence (tempdoc 658)

`justsearch_search` and `justsearch_answer` return a machine-readable `structuredContent` object
alongside the human-readable `content` text, so an agent can inspect *why* it got these results —
not just read them. This is a projection of the same canonical records the desktop UI and REST API
already render (`SearchTrace`, `ContextCitation`); it introduces no new authority
(`governance/execution-surfaces.v1.json`).

- **`justsearch_search` → `structuredContent`**: the query-level `searchTrace` (effective mode,
  decision kind, degradation reason codes, and the per-stage list with status/reason/timing) plus a
  `results` list carrying, per hit, its ranking `trace` (which legs placed it, at what rank/score) and
  the fused-leg `legScores` (sparse/dense/splade/fused). The structural trace is always present; the
  numeric per-hit detail tier is included only when the call sets `detail: true`.
- **`justsearch_answer` → `structuredContent`**: the `citations` list (per-chunk provenance —
  `parentDocId`, char/line span, heading, score, excerpt) plus `quality` (chunks found/used, retrieval
  mode + reason code, and the CRAG-style confidence signals: coverage, best-chunk score, score gap,
  chunks considered/included, truncation). Citations are empty on the full-document fallback path.

**Data exposure.** MCP tool responses are **not redacted** — path redaction applies to the diagnostics
*export* bundle (a shareable artifact), not to live tool output. `citations[].parentDocId` is the
document's identity (its absolute file path) and `excerpt` is the passage text; both are the user's own
local data returned to the user's own local agent. This is the same identity `justsearch_search`
already returns (its `path` field) and the desktop UI already shows. The `/mcp` endpoint is
loopback-only (binds `127.0.0.1`; Hard Invariant #2), so nothing leaves the machine.

## Tool Selection

Use `justsearch_answer` first when the user asks a question about
indexed content. It retrieves relevant passages assembled with source
attribution — more efficient than searching and reading individually.

Use `justsearch_search` when the agent needs to discover what exists,
browse by source/category, or find specific files. Returns facets on
first call for filter discovery.

Use `justsearch_browse` to explore the folder structure before
searching — especially useful when the agent doesn't know what's
indexed.

Use `justsearch_ingest` when the user wants new content indexed.

Use `justsearch_status` to check index health, enrichment coverage,
and document count before diagnosing empty results.

This same comparative guidance — when to prefer the index over reading
files directly — is delivered to the agent at connect time through the
MCP `initialize` response's optional `instructions` field, which
compatible clients (e.g. Claude Code) inject into the model's context.
It is advisory guidance, not a contract, and states honestly that
ordinary file tools are equally good for a small set of files or an
exact string/filename lookup.

## Progressive Disclosure

The MCP surface uses response-level hints instead of schema complexity.
Tools return contextual guidance at decision time:

- **Zero results** → "try broader terms or check justsearch_status"
- **Many results** → "use facet values as filters to narrow down"
- **Low enrichment** → "enrichment in progress — semantic search may be limited"
- **Facet sidecar** → answer tool includes top sources and entities
- **Comparative hint** → after an `answer` that drew on more than one
  document, the response states factually how many distinct documents it
  assembled evidence from in a single call — surfacing the index's
  multi-document advantage at the moment the agent sees it worked, not only
  in the tool description (which agents read once and forget)

Advanced parameters (doc_ids, LUCENE syntax, entity filters) work when
passed but are NOT in the visible schema. This is intentional — eval
data shows making them visible degrades small-model accuracy (92% → 71%)
without increasing usage. Capable agents can use them by reading the
description carefully.

## MCP Prompts (3)

| Prompt | Arguments | Purpose |
|--------|-----------|---------|
| `search_files` | `topic` (string) | Search the knowledge base |
| `answer_question` | `question` (string) | Get an answer from indexed documents |
| `index_folder` | `path` (string) | Add a folder to the index |

Prompts expand with live system context (document count, enrichment
percentages) so the model has orientation before the user's query.

## MCP Resources

Four proposed URIs for agent orientation:

| URI | Content |
|-----|---------|
| `justsearch://index/summary` | Document count, enrichment coverage, readiness |
| `justsearch://index/roots` | Indexed folder paths |
| `justsearch://index/top-sources` | Top `meta_source` facet values |
| `justsearch://index/top-entities` | Top person/organization entity values |

Plus 9 catalog-driven resources (health events, indexing jobs, etc.)
for subscription support.

## Trust Model

MCP clients are registered as `SourceTier.UNTRUSTED` in the intent
substrate. The trust lattice gates `justsearch_ingest` (the one
mutating tool) behind `TYPED_CONFIRM`.

**Confirmation is resolved in the JustSearch app, not by the calling agent
(tempdoc 655).** When a gate fires, the tool call returns immediately —
it never blocks — with a message explaining that approval is now showing
in the JustSearch app; the agent does not need to retry the call. The
approval is the SAME `PendingAuthorizationStore` / capsule mechanism the
browser UI's own gated actions use (tempdoc 550), reached via a live SSE
announcement so the app can react even though it never made the
originating request. Once a human approves in the app, the server
completes the dispatch itself using the pending record's own stored
arguments — the browser never needs (and never receives) the full
argument payload for an MCP-originated request.

If the user grants "allow always" for `core.ingest-files` at the
`UNTRUSTED` source tier (via the approval dialog, or directly through
`POST /api/authorizations/grants`), future `justsearch_ingest` calls
succeed immediately with no prompt — the pre-existing durable-grant
mechanism already covers MCP callers, since `SourceTier` is
transport-agnostic.

## Legacy: Old TypeScript MCP Server

The previous TypeScript MCP server (`scripts/prod/justsearch-mcp/server.mjs`)
is **deprecated**. It ran as a separate Node.js process via stdio
transport with 4 tools. The Java MCP handler supersedes it with
better transport (Streamable HTTP, no separate process), more tools
(5 vs 4, adds browse), and direct service-layer dispatch.

The old server remains in the codebase for reference — its tool
descriptions and data from a tool-interface-design eval (tempdoc 366)
informed the new handler's design. Remove after the new handler is
eval-validated (tempdoc 500 gate).
