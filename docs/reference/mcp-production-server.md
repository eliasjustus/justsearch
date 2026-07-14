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

## Which port?

The MCP endpoint lives on the same loopback HTTP API as everything else, so "which port is the
MCP server on?" is exactly "which port is the API on?":

- **Default: `8080`.** The API port resolves through `justsearch.api.port` /
  `JUSTSEARCH_API_PORT` (`modules/configuration/.../EnvRegistry.java`); when nothing sets it,
  the built-in default is `8080` (`ResolvedConfigBuilder.buildPorts()`). The packaged desktop
  app sets no port override, so an installed app binds `http://127.0.0.1:8080`.
- **Ephemeral fallback.** If the configured port is already in use, the server logs a warning
  and rebinds on a random free port instead of failing
  (`modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java` — bind-failure fallback).
  Setting the port to `0` requests an ephemeral port explicitly.
- **Pin it:** set the `JUSTSEARCH_API_PORT` environment variable (or the
  `-Djustsearch.api.port=` system property for source runs) before launching. Note that a pin
  is a *preference*: if that port is taken, the ephemeral fallback above still applies.
- **Discover the actual port:** the backend writes it to
  `<data dir>\runtime\api-port.txt` — for the installed desktop app that is
  `%APPDATA%\io.justsearch.shell\runtime\api-port.txt`. It also prints
  `JUSTSEARCH_API_PORT=<port>` to stdout (captured in `logs\headless-backend.log`) and serves
  `GET /api/health` once up. (Gradle dev tasks like `runHeadless`/`devAll` default to `33221`,
  which is why older docs and scripts mention that number — the installed app does not use it.)

## Setup

### Claude Desktop one-click (MCPB) — available from the next release

An MCPB bundle (`justsearch-mcp.mcpb`, sources in
[`packaging/mcpb/`](../../packaging/mcpb/README.md)) will be attached to
JustSearch releases **starting with the next release** — it is not on any
published release yet, and the v0.1.0 app predates the `/mcp` endpoint, so
the bundle cannot work against it. Once shipped: download the `.mcpb` from
the release page and open it with Claude Desktop (Settings → Extensions) —
one click, no JSON editing. The bundle is a thin local stdio bridge to the
running app's `/mcp` endpoint; it handles port discovery via `api-port.txt`
automatically. Until then, use the connector flow below.

### Claude Desktop in ~2 minutes, starting from "launch the app"

1. **Launch JustSearch** (Start menu). Wait for the window to load — the API is up when
   `http://127.0.0.1:8080/api/health` answers in a browser. If it doesn't, read the actual
   port from `%APPDATA%\io.justsearch.shell\runtime\api-port.txt` and use that below.
2. **Claude Desktop → Settings → Connectors → Add custom connector**, URL:

   ```text
   http://127.0.0.1:8080/mcp
   ```

3. **Done.** Ask Claude something about your indexed files; it will call `justsearch_answer` /
   `justsearch_search`. (First useful answers require having pointed JustSearch at a folder and
   letting it finish indexing.)

If your Claude Desktop version has no Connectors UI, bridge stdio→HTTP with `mcp-remote`
(needs Node.js) in `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "justsearch": {
      "command": "npx",
      "args": ["mcp-remote", "http://127.0.0.1:8080/mcp"]
    }
  }
}
```

### Cursor / Windsurf / VS Code

Add to `.cursor/mcp.json` or equivalent:

```json
{
  "mcpServers": {
    "justsearch": {
      "url": "http://127.0.0.1:8080/mcp"
    }
  }
}
```

### Claude Code

```bash
claude mcp add justsearch --transport http http://127.0.0.1:8080/mcp
```

In all three: replace `8080` with the port from
`%APPDATA%\io.justsearch.shell\runtime\api-port.txt` if `8080` was taken on your machine
(see [Which port?](#which-port)).

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
prompts.

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
(6 vs 4), and direct service-layer dispatch.

The old server remains in the codebase for reference — its tool
descriptions and data from a tool-interface-design eval (tempdoc 366)
informed the new handler's design. Remove after the new handler is
eval-validated (tempdoc 500 gate).
