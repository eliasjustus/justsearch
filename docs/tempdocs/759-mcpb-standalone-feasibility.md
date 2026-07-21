---
title: "MCPB bundle feasibility: can the MCP surface be packaged as a standalone .mcpb (stdio) artifact — the gate on the official MCP Registry, Anthropic Desktop Extensions, Smithery, and Cline listings"
type: tempdocs
status: "open — Phase 1 COMPLETE (2026-07-21): premise obsolete, the bridge already shipped (PR #184). Remaining work is operational (release + publish), mostly owner-gated. See §Findings."
created: 2026-07-21
author: agent (Fable orchestration), founder-directed distribution-readiness work (2026-07-21)
category: distribution / mcp
related:
  - 760-installer-distribution-readiness   # sibling distribution lane
  - 761-linux-build-cost-estimate          # sibling distribution lane
---

> Charter. Motivation: the MCP ecosystem's listing surfaces have converged on package-verified
> registration. The official MCP Registry (registry.modelcontextprotocol.io) accepts `mcpb` as a
> package type — an `.mcpb` bundle attached to a GitHub release, verified by URL + `fileSha256` —
> and it is the only package type JustSearch can satisfy without publishing to npm/PyPI/OCI.
> Anthropic's Desktop Extensions directory, Smithery's local-server path, and Cline's
> agent-installable requirement all share the same prerequisite. One artifact unblocks four
> surfaces; registry listing propagates automatically to downstream aggregators (PulseMCP, Glama).

# 759 — MCPB standalone feasibility

## The problem

JustSearch's MCP server (`POST /mcp`) is served by the Head process (Javalin, loopback-only —
Hard Invariant #2) and depends on the running stack (Head → Worker gRPC for index IO — Hard
Invariant #1). MCPB bundles assume a server a host (Claude Desktop) can launch itself over
**stdio**. The open question is which of these architectures is feasible and what each costs:

- **(a) Thin stdio↔HTTP bridge** — the bundle ships a small launcher that speaks stdio MCP to
  the host and proxies to the running JustSearch app on `127.0.0.1`. Requires the desktop app
  installed and running (or the bridge can start it). Analogous precedent: MCPB extensions that
  front a companion app. Cheapest; preserves both hard invariants untouched.
- **(b) Standalone headless server** — the bundle boots enough of the stack (Head+Worker) to
  serve retrieval without the desktop UI. Much heavier; JVM + model distribution inside or
  alongside a bundle needs scoping (the public repo ships no model blobs).
- **(c) Native stdio transport in-process** — Head gains a stdio MCP transport mode alongside
  HTTP, launched by the bundle. Middle ground; lifecycle questions (who owns the Worker?).

## Phase 1 — feasibility investigation (delegable, read-only)

1. **Map the MCP surface as built**: where `POST /mcp` is implemented, its transport assumptions,
   session/lifecycle coupling to Head, what it needs from Worker, startup time from cold.
   `file:line` evidence required.
2. **MCPB spec-side facts** (primary sources: anthropics/mcpb repo + registry docs + Desktop
   Extensions submission docs): exact manifest requirements, whether a bundle may depend on a
   separately-installed companion app, stdio expectations, platform fields (Windows-only bundles
   accepted?), signing/packaging constraints, size limits.
3. **Assess (a)/(b)/(c)** against the codebase: effort class (S/M/L), invariant risk, UX
   (what happens when the app isn't running), and what the registry/directory reviewers would
   see. Recommend one.
4. **Registry mechanics dry-run on paper**: `server.json` shape, namespace
   (`io.github.<owner>/justsearch`), release-asset naming containing "mcp", sha256 flow in CI.

**Acceptance:** a written verdict section in this tempdoc — GO/NO-GO per architecture with
`file:line` + spec citations, a recommended path, and a Phase-2 work list. No code changes in
Phase 1.

## Phase 2 — packaging (chartered on GO; separate worktree)

Placeholder: implement the recommended architecture, CI step producing the `.mcpb` + sha256 on
release, registry publish flow, Desktop-Extensions submission prerequisites (manifest privacy
fields — note the directory hard-gates a hosted privacy-policy URL; that dependency lives with
the website work, tracked outside this tempdoc).

## Findings — Phase 1 (2026-07-21, opus investigator; all claims file:line-verified)

**Headline: this tempdoc's central question was already answered on `main` — architecture (a)
is built, CI-wired, and documented.** The stdio↔HTTP bridge lives at `packaging/mcpb/`
(merged PR #184, `0c1acd32`; design history in tempdocs 500/655/726). The charter was written
from research notes and is superseded by shipped code (`tempdocs-are-dated-history`, applied to
this very file). Corrected framing: **gap-to-publish, not feasibility.**

**The MCP surface as built:** `POST /mcp` + `DELETE /mcp` registered in
`modules/ui/.../LocalApiServer.java:576-579`; Streamable HTTP, JSON-RPC 2.0
(`McpProtocolHandler.java:22-34`), protocol version `2025-11-25`, tool surface `0.4.0`
(single-sourced from `McpContractVersions`). In-memory sessions, `Mcp-Session-Id`, 30-min TTL
(`McpProtocolHandler.java:40-165`). Retrieval tools are Worker-gated (agent-tools boot phase
`PENDING→READY` on Worker connect, `api-contract-map.md:164`). Auth: bridge fetches
`GET /api/mcp/token`, sends `X-JustSearch-Session` (`packaging/mcpb/server/index.js:98-119`).
The `modules/app-services/.../mcphost/` package is a red herring — that's JustSearch as MCP
*host* of external servers, not this surface.

**Spec conformance (primary sources: anthropics/mcpb MANIFEST.md, registry package-types
docs):** the shipped bundle already conforms — `manifest_version 0.4`, `server.type node`,
zero-dependency 442-line bridge, `compatibility.platforms: ["win32"]` (Windows-only is
accepted), companion-app pattern is spec-legal (spec requires only a stdio server; a bridge is
one). Registry `mcpb` rules confirmed: `fileSha256` required, identifier URL must contain
"mcp" (satisfied: `justsearch-mcp.mcpb`), GitHub/GitLab release hosts only. `server.json`
already schema-valid, namespace `io.github.eliasjustus/justsearch`, README ownership marker
present (`README.md:10`). Deterministic packing + drift gate already in CI
(`scripts/ci/pack-mcpb.mjs`, `check-mcpb-consistency.mjs`, `ci.yml:104-108`;
release attachment `build-installer.yml:160-228`).

**Architecture verdicts:** (a) GO — done, ~0 effort, zero invariant risk. (b) standalone
headless NO-GO (L; model blobs + JVM in a bundle contradicts repo/model-distribution reality,
no upside). (c) native stdio in Head NO-GO (M; duplicates a working transport, reopens Worker
lifecycle ownership for nothing).

**Phase 2 = release + publish (revised; replaces the placeholder):**
1. Cut a release ≥ the first build shipping `/mcp` (v0.1.0 predates it) — version bump,
   `sync-version.ps1`, tag `v<ver>`, `build-installer.yml` attaches installer + `SHA256SUMS` +
   `.mcpb`. **Owner-gated (release = publish action).** (S)
2. Verify `check-mcpb-consistency.mjs --release-version <v>` + released sha256 matches
   `server.json.fileSha256`. (S)
3. `mcp-publisher login github` → `publish` from `packaging/mcpb/` once the asset is live.
   **Owner-gated (GitHub device login + explicit approval).** (S)
4. Anthropic Desktop-Extensions submission — blocked externally on a hosted privacy-policy URL
   (website work, outside 759). (S, blocked)

**Open flags (small, check before publishing):**
- Version-stamp gap: `manifest.json` `version` is `0.1.0` while `server.json` is `0.2.0`;
  `pack-mcpb.mjs --set-version` stamps only `server.json` (`pack-mcpb.mjs:204-219`). Confirm
  intended (bundle-version vs server-version may legitimately differ) or fix the stamper.
- Unverified: packaged-mode wiring makes `convApi.mcpProtocolHandler()` non-null in production
  (route registration is conditional, `LocalApiServer.java:576`) — one-line trace before publish.
- Cold-start figures are the dev-tool's stated numbers, not benchmarked.
